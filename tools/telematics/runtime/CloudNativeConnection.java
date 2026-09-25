package dev.denza.tools.runtime;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import dev.denza.tools.OncarTls;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

/** On-car wiring. All cloud and MCU payloads are produced/decoded by the isolated firmware code. */
public final class CloudNativeConnection implements CloudSessionLoop.Connection, CloudNativePipe.Effects {
    private static final String FIRMWARE_SHA="9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9";
    /** Public-network view exposed only to the isolated firmware copy. */
    private static final String VIRTUAL_NATIVE_PROFILE="double_apn";
    interface Dependencies {
        void verifyFirmware()throws Exception;
        Process launch(Path executable)throws Exception;
        CloudPlatform platform(Scope resources,Identity pair)throws Exception;
        void initializeTls()throws Exception;
        CloudTransport transport();
        default CloudSecondaryTransport secondary(CloudSecondaryTransport.EndpointAuthorizer authorizer){
            return new CloudSecondaryTransport(authorizer);
        }
        default Path gateJournalPath(){return CloudLocalControl.STATE.resolve("gate.pending");}
        default Path registrationJournalPath(){return CloudLocalControl.STATE.resolve("registration.pending");}
    }
    private static final class AndroidDependencies implements Dependencies {
        public void verifyFirmware()throws Exception {
            try{CloudFirmwareProfile.verify(CloudFirmwareProfile.androidProbe());}
            catch(CloudSessionLoop.PermanentFailure mismatch){throw mismatch;}
            catch(Exception unavailable){throw new CloudSessionLoop.PermanentFailure(Code.UNSUPPORTED_FIRMWARE);}
        }
        public Process launch(Path executable)throws Exception{return new ProcessBuilder(executable.toString()).redirectError(new File("/dev/null")).start();}
        public CloudPlatform platform(Scope resources,Identity pair)throws Exception{return CloudPlatform.open(resources,pair.iccid,pair.imsi,8192);}
        public void initializeTls()throws Exception{
            try{CloudTransport.initializeFactoryIdentity();}
            catch(OncarTls.FactoryIdentityMismatch mismatch){
                throw new CloudSessionLoop.PermanentFailure(Code.UNSUPPORTED_IDENTITY);
            }
        }
        public CloudTransport transport(){return new CloudTransport();}
    }
    /** Archive/build verification helper; never called on protected live cloudmanager. */
    static void verifyFirmwareFile(Path path)throws Exception {
        final byte[] original;
        try{original=Files.readAllBytes(path);}
        catch(IOException|SecurityException unavailable){
            // The protected installed file is unreadable on the researched shell UID.
            // Until an alternative compatibility proof is qualified, fail once before
            // touching the SDK/cloud; retrying the same unavailable file cannot help.
            throw new CloudSessionLoop.Unavailable();
        }
        if(!hex(MessageDigest.getInstance("SHA-256").digest(original)).equals(FIRMWARE_SHA))
            throw new CloudSessionLoop.Unavailable();
    }
    private final Dependencies dependencies;
    /** Survives socket reconnects within one owner, never a mode/pair change or process restart. */
    public static final class Registration {
        private final CloudPowerGuard.Continuity continuity=new CloudPowerGuard.Continuity();
        private final Map<String,String> nativeCounters=new HashMap<>();
    }
    private final Scope resources=new Scope();
    private final Object operationLock=new Object();
    private final Path executable;
    private final Identity pair;
    private final long epoch;
    private final Clock clock;
    private final CloudRuntimeSupervisor.Sink sink;
    private final Registration registration;
    private final SecureRandom random=new SecureRandom();
    private volatile boolean closed;
    private CloudNativePipe nativePipe;
    private CloudPlatform platform;
    private CloudPowerGuard power;
    private CloudPrimitiveBridge primitives;
    private final ArrayDeque<CloudPlatform.Event> deferred=new ArrayDeque<>();
    // A fresh native child must consume fresh registration/discovery replies.
    // Endpoint caching cannot initialize its original internal sender state.
    private String discoveryHost;
    private int discoveryPort;
    private static final long KEEPALIVE_INTERVAL_MS=250_000;
    private long nextKeepaliveUptimeMs=Long.MAX_VALUE;
    private CloudTransport transport;
    private CloudSecondaryTransport secondary;
    private final Map<String,Set<String>> nativeDns=new HashMap<>();
    private FrameSource source;
    private boolean nativeWrite;
    private String stockProfile;

    public CloudNativeConnection(Path executable,Identity pair,long epoch,Clock clock,
            CloudRuntimeSupervisor.Sink sink,Registration registration) {
        this(executable,pair,epoch,clock,sink,registration,new AndroidDependencies());
    }
    CloudNativeConnection(Path executable,Identity pair,long epoch,Clock clock,
            CloudRuntimeSupervisor.Sink sink,Registration registration,Dependencies dependencies) {
        this.executable=executable;this.pair=pair;this.epoch=epoch;this.clock=clock;this.sink=sink;this.registration=registration;this.dependencies=dependencies;
    }
    @Override public void establish(CloudSessionLoop.Progress progress) throws Exception {
        synchronized(operationLock){
            check();
            long uptime=clock.nativeMonotonicMs();registration.continuity.check(clock.nowMs(),uptime);
            dependencies.verifyFirmware();
            nativePipe=CloudNativePipe.open(resources,dependencies.launch(executable),epoch);
            // A bounded proof executable must never silently become a product service.
            try{nativePipe.requireCapabilities("PROTO2","REG","DATA","CONTROL_AWAKE",
                "WAKE_ACK_AWAKE","TIMERS_AWAKE","POST_LOGIN_AWAKE","HEARTBEAT");}
            catch(IOException missing){throw new CloudSessionLoop.PermanentFailure(Code.NATIVE_UNAVAILABLE);}
            check();
            try{platform=dependencies.platform(resources,pair);}
            catch(CloudSessionLoop.PermanentFailure refusal){throw refusal;}
            catch(IllegalArgumentException|IllegalStateException wrongIdentity){
                throw new CloudSessionLoop.PermanentFailure(Code.UNSUPPORTED_IDENTITY);
            }catch(Exception unavailable){throw new CloudNativePipe.NativeFailure();}
            power=new CloudPowerGuard(platform::getInt,clock::nowMs,clock::nativeMonotonicMs,registration.continuity);
            power.check();
            primitives=new CloudPrimitiveBridge(new CloudPrimitiveBridge.Platform(){
                public int getInt(int device,int fid)throws Exception{return platform.getInt(device,fid);}
                public CloudPlatform.BufferResult getBuffer(int device,int fid)throws Exception{
                    return platform.getNativeBuffer(device,fid);
                }
                public float getFloat(int device,int fid)throws Exception{return platform.getFloat(device,fid);}
                public String property(String key)throws Exception{
                    return key.equals("persist.sys.byd.apn_type")
                        ? VIRTUAL_NATIVE_PROFILE : platform.property(key);
                }
                public void setInt(int device,int fid,int value)throws Exception{ensureStockPaused();platform.sendNativeInt(device,fid,value);}
                public void setProperty(String key,String value)throws Exception{ensureStockPaused();platform.sendNativeProperty(key,value);}
                public byte[][] dnsLookup(String host)throws Exception{
                    ensureStockPaused();byte[][] addresses=CloudDnsResolver.resolve(host,()->closed);
                    Set<String> approved=new HashSet<>();
                    for(byte[] address:addresses)approved.add(java.net.InetAddress.getByAddress(address).getHostAddress());
                    if(!nativeDns.containsKey(host)&&nativeDns.size()>=8)throw new CloudNativePipe.ProtocolFailure();
                    nativeDns.put(host,approved);return addresses;
                }
                public String waitForEvent(long deadlineNs)throws Exception{return awaitSdkEvent(deadlineNs);}
            },random::nextBytes,registration.nativeCounters);
            CloudPlatform.Identity identity=platform.identity;
            try{stockProfile=platform.property("persist.sys.byd.apn_type");}
            catch(CloudSessionLoop.PermanentFailure refusal){throw refusal;}
            catch(Exception unavailable){throw new CloudNativePipe.NativeFailure();}
            try{CloudGateJournal.goneValue(stockProfile);}
            catch(IOException unsupported){throw new CloudSessionLoop.PermanentFailure(Code.UNSUPPORTED_FIRMWARE);}
            input("V",identity.vin());input("K",identity.key());input("U",identity.uuid());
            input("C",identity.iccid());input("M",identity.imsi());input("S",identity.serial());
            // Only the native copy sees the virtual public transport profile.
            // The physical stock profile remains separate for gate ownership.
            input("A",VIRTUAL_NATIVE_PROFILE.getBytes(StandardCharsets.US_ASCII));freshEntropy();command("START");
            // START initializes the isolated firmware. Its first native clock sample
            // must precede every registration/login continuation.
            tick();
            progress.pulse();
            dependencies.initializeTls();
            check();
            if(!stockProfile.equals(platform.property("persist.sys.byd.apn_type")))
                throw new CloudSessionLoop.PermanentFailure(Code.STOCK_OWNER_COMPETED);
            // This debt is published only after preflight, just before the
            // original profile-specific void notify_nw gone transaction.
            CloudGateJournal gate=new CloudGateJournal(dependencies.gateJournalPath());
            int observedTcp=platform.tcpState();
            if(gate.pending() && observedTcp!=0)
                throw new CloudSessionLoop.PermanentFailure(Code.STOCK_OWNER_COMPETED);
            power.check();
            gate.beforePause(stockProfile);
            check();platform.stockGate(CloudGateJournal.goneValue(stockProfile));
            if(!stockProfile.equals(platform.property("persist.sys.byd.apn_type")))
                throw new CloudSessionLoop.PermanentFailure(Code.STOCK_OWNER_COMPETED);
            long until=clock.nowMs()+8000;
            while(platform.tcpState()!=0){
                if(clock.nowMs()>=until)throw new CloudSessionLoop.PermanentFailure(Code.STOCK_OWNER_COMPETED);
                if(!stockProfile.equals(platform.property("persist.sys.byd.apn_type")))
                    throw new CloudSessionLoop.PermanentFailure(Code.STOCK_OWNER_COMPETED);
                progress.pulse();Thread.sleep(100);
            }
            // The original network-state callback initiates 211, whose reply
            // initiates 200, whose reply initiates 220. Java supplies each
            // transport and passes bytes unchanged; it never invokes G/D/L too.
            progress.stage(Stage.REGISTERING,Code.REGISTERING);
            Produced initial=collectNativeFrame("NETSTATE 4");
            Produced registered=bootstrap("dilinkreg-cn.denzacloud.com",6001,
                requireFrame(initial),"R211",true);
            if(!singleResult(registered.results).equals("REG 1"))
                throw new CloudSessionLoop.PermanentFailure(Code.REGISTRATION_REJECTED);
            progress.stage(Stage.DISCOVERING,Code.DISCOVERING);
            Produced discovered=bootstrap("dilinkaddr-cn.denzacloud.com",6021,
                requireFrame(registered),"R200",false);
            String[] endpoint=singleResult(discovered.results).split(" ");
            if(endpoint.length!=3||!endpoint[0].equals("ENDPOINT")||
               !endpoint[1].matches("[a-z0-9-]+\\.denzacloud\\.com"))throw new CloudSessionLoop.Unavailable();
            final int port;
            try{port=Integer.parseInt(endpoint[2]);}catch(NumberFormatException malformed){throw new CloudSessionLoop.Unavailable();}
            if(port<1||port>65535)throw new CloudSessionLoop.Unavailable();
            discoveryHost=endpoint[1];discoveryPort=port;
            progress.stage(Stage.CONNECTING,Code.CONNECTING);
            transport=openTransport(discoveryHost,discoveryPort);
            refreshAndTick();
            ensureStockPaused();transport.sendNativeFrame(requireFrame(discovered));sink.count(Metric.TX);
            byte[] loginReply=transport.readFrame();
            refreshAndTick();
            String result=singleResult(command("R220 "+hex(loginReply)));
            sink.count(Metric.RX);
            if(!result.equals("LOGIN 1")){discoveryHost=null;throw new CloudSessionLoop.PermanentFailure(Code.LOGIN_REJECTED);}
            ensureStockPaused();transport.setIdleTimeoutMs(1000);
            // Stock system_server stimulates keepalive(1) immediately after
            // actual login and every 250s. This local stimulus never calls the
            // stock Binder or constructs its 201 payload/timeout decisions.
            keepalive();
            source=resources.own(new FrameSource(transport));source.start();
            progress.pulse();
        }
    }
    private CloudTransport openTransport(String host,int port)throws Exception{
        check();ensureStockPaused();CloudTransport channel=resources.own(dependencies.transport());
        try{channel.setReadTimeoutMs(10_000);channel.setIdleTimeoutMs(10_000);sink.progress();
            channel.open(host,port,15_000);sink.progress();ensureStockPaused();return channel;}
        catch(Exception failure){
            try{resources.retire(channel);}catch(Exception cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            if(certificateFailure(failure))throw certificateRefusal(failure);
            throw failure;
        }
    }
    private static boolean certificateFailure(Throwable failure){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause())
            if(cause instanceof java.security.cert.CertificateException||
               cause instanceof java.security.cert.CertPathValidatorException||
               cause instanceof javax.net.ssl.SSLPeerUnverifiedException)return true;
        return false;
    }
    private static CloudSessionLoop.PermanentFailure certificateRefusal(Throwable failure){
        CloudSessionLoop.PermanentFailure refusal=new CloudSessionLoop.PermanentFailure(Code.NATIVE_UNAVAILABLE);
        refusal.initCause(failure);return refusal;
    }
    private static final class Produced {
        final List<String> results;
        final byte[] frame;
        Produced(List<String> results,byte[] frame){this.results=results;this.frame=frame;}
    }
    private boolean collectingFrame;
    private byte[] collectedFrame;
    private Produced collectNativeFrame(String operation)throws Exception{
        if(collectingFrame)throw new CloudNativePipe.ProtocolFailure();
        collectingFrame=true;collectedFrame=null;
        try{return new Produced(command(operation),collectedFrame);}
        finally{collectingFrame=false;collectedFrame=null;}
    }
    private static byte[] requireFrame(Produced produced)throws IOException{
        if(produced.frame==null)throw new CloudNativePipe.ProtocolFailure();
        return produced.frame;
    }
    private Produced bootstrap(String host,int port,byte[] request,String receiver,boolean registrationRequest)throws Exception{
        CloudTransport channel=openTransport(host,port);
        try{
            refreshAndTick();
            if(registrationRequest)
                new CloudRegistrationJournal(dependencies.registrationJournalPath()).customMayRegister();
            ensureStockPaused();channel.sendNativeFrame(request);sink.count(Metric.TX);
            byte[] received=channel.readFrame();sink.count(Metric.RX);
            refreshAndTick();
            return collectNativeFrame(receiver+" "+hex(received));
        }finally{resources.retire(channel);}
    }
    @Override public void pump(long maxWaitMs,CloudSessionLoop.Progress progress)throws Exception{
        synchronized(operationLock){
            check();ensureStockPaused();refreshAndTick();drainPlatform();
            if(clock.nativeMonotonicMs()>=nextKeepaliveUptimeMs)keepalive();
            byte[] frame=source.poll(Math.min(250,maxWaitMs));
            power.check();
            if(frame!=null){
                refreshAndTick();input("H",le(platform.getInt(CloudPlatform.CHARGING,CloudPlatform.CHARGE)));
                sink.count(Metric.RX);List<String> results=command("RX "+hex(frame));
                for(String result:results){
                    if(result.startsWith("STATUS ")){
                        String[] words=result.split(" ");if(words.length!=2)throw new CloudNativePipe.ProtocolFailure();
                        send(unhex(words[1],1024));sink.count(Metric.REPORT);sink.count(Metric.STATUS_REPLY);
                    }
                }
            }
            progress.pulse();
        }
    }
    private void keepalive()throws Exception{
        ensureStockPaused();
        command("KEEPALIVE 1");
        nextKeepaliveUptimeMs=clock.nativeMonotonicMs()+KEEPALIVE_INTERVAL_MS;
    }
    private void drainPlatform()throws Exception{
        if(!platform.healthy())throw new CloudNativePipe.NativeFailure();
        // Bound one turn so telemetry cannot starve TCP, STOP or native timers.
        for(int i=0;i<128;i++){
            CloudPlatform.Event event=deferred.isEmpty()?platform.poll(0):deferred.removeFirst();if(event==null)break;
            check();sink.callbackObserved();
            if(event.kind==CloudPlatform.Event.Kind.INTEGER)
                CloudPowerGuard.checkEvent(event.device,event.fid,event.value);
            power.check();
            if(event.kind==CloudPlatform.Event.Kind.INTEGER){
                // ACC is our awake-only guard subscription. The original SDK
                // observer subscribes to MCU state, not this added ACC signal.
                if(event.device==CloudPlatform.POWER&&event.fid==CloudPlatform.POWER_ACC)continue;
                refreshAndTick();
                command("INT "+event.device+" "+Integer.toUnsignedString(event.fid)+" "+event.value);
            }else if(event.fid==CloudPlatform.YUN_DATA){input("I",event.bytes);}
            else{
                refreshAndTick();command("MCU "+hex(event.bytes));
            }
        }
    }
    private byte[] environmentBytes()throws Exception{
        // This is the FFI getter result vector, not a vehicle telemetry packet.
        ByteBuffer out=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        int mask=0;int[] values=new int[8];
        int[][] getters={{1001,0x12d0002a},{1005,0x99000003},{1014,0x14400008},{1023,0x2f4000fa}};
        for(int i=0;i<4;i++)try{values[i]=platform.getInt(getters[i][0],getters[i][1]);mask|=1<<i;}catch(Exception missing){}
        try{float soc=platform.getFloat(1014,CloudPlatform.SOC);if(Float.isFinite(soc)&&soc>=0&&soc<=100){values[4]=Float.floatToRawIntBits(soc);mask|=16;}}catch(Exception missing){}
        try{String value=platform.property("persist.sys.repair_mode.enable");values[5]=value.isEmpty()?0:Integer.parseInt(value);mask|=32;}catch(Exception missing){}
        try{values[6]=Integer.parseInt(platform.property("persist.sys.energytype"));mask|=64;}catch(Exception missing){}
        try{values[7]=platform.getInt(1000,0x40400010);mask|=128;}catch(Exception missing){}
        for(int value:values)out.putInt(value);out.putInt(mask);return out.array();
    }
    private byte[] extraEnvironment()throws Exception{
        ByteBuffer out=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);int mask=0;int[] values=new int[3];
        try{values[0]=platform.getInt(1001,0x40d00010);mask|=1;}catch(Exception missing){}
        try{String raw=platform.property("persist.sys.record_610_upload");values[1]=raw.isEmpty()?0:Integer.parseInt(raw);mask|=2;}catch(Exception missing){}
        try{String raw=primitives.propertyValue("sys.cloud.unlock_index");values[2]=raw.isEmpty()?0:Integer.parseInt(raw);mask|=4;}catch(Exception missing){}
        for(int value:values)out.putInt(value);out.putInt(mask);return out.array();
    }
    private String awaitSdkEvent(long deadlineNs)throws Exception{
        long deadlineMs=TimeUnit.NANOSECONDS.toMillis(deadlineNs)+(deadlineNs%1_000_000==0?0:1);
        if(deadlineMs-clock.nativeMonotonicMs()>5000)throw new CloudNativePipe.ProtocolFailure();
        while(clock.nativeMonotonicMs()<deadlineMs){
            check();power.check();if(!platform.healthy())throw new CloudNativePipe.NativeFailure();
            long remaining=deadlineMs-clock.nativeMonotonicMs();if(remaining<=0)break;
            CloudPlatform.Event event=platform.poll(Math.min(50,remaining));
            if(event==null)continue;
            sink.callbackObserved();
            // A queued OFF/sleep event terminates this awake session even if a
            // newer getter has already returned ON. Never defer it behind a wait.
            if(event.kind==CloudPlatform.Event.Kind.INTEGER)
                CloudPowerGuard.checkEvent(event.device,event.fid,event.value);
            if(event.kind==CloudPlatform.Event.Kind.INTEGER && event.device==CloudPlatform.POWER &&
               event.fid==CloudPlatform.MCU_STATE && (event.value==0||event.value==1)){
                String env=hex(environmentBytes()),extra=hex(extraEnvironment());long now=clock.nativeMonotonicMs();
                if(now<=deadlineMs)return "EVENT "+event.device+" "+Integer.toUnsignedString(event.fid)+" "+event.value+" "+now+" "+clock.nowMs()+" "+clock.wallMs()+" "+env+" "+extra;
                // The event remains real and queued; a late getter must not backdate the native wait.
            }
            if(deferred.size()>=512)throw new CloudNativePipe.NativeFailure();
            deferred.addLast(event);
        }
        return "TIMEOUT "+clock.nativeMonotonicMs()+" "+clock.nowMs()+" "+clock.wallMs();
    }
    private void ensureStockPaused()throws Exception{
        check();power.check();check();
        final String currentProfile;final int tcp;
        try{currentProfile=platform.property("persist.sys.byd.apn_type");tcp=platform.tcpState();}
        catch(CloudSessionLoop.PermanentFailure refusal){throw refusal;}
        catch(Exception unavailable){throw new CloudNativePipe.NativeFailure();}
        if(!stockProfile.equals(currentProfile)||tcp!=0)
            throw new CloudSessionLoop.PermanentFailure(Code.STOCK_OWNER_COMPETED);
    }
    private void tick()throws Exception{
        command("TICK "+clock.nowMs()+" "+clock.nativeMonotonicMs()+" "+clock.wallMs());
    }
    private void refreshAndTick()throws Exception{
        input("E",environmentBytes());input("X",extraEnvironment());tick();
    }
    private void send(byte[] frame)throws Exception{
        ensureStockPaused();if(transport==null)throw new CloudNativePipe.ProtocolFailure();
        transport.sendNativeFrame(frame);sink.count(Metric.TX);
    }
    @Override public void event(String kind,List<String> args)throws Exception{
        check();power.check();
        switch(kind){
            case "NET":
                if(args.size()!=1)throw new CloudNativePipe.ProtocolFailure();
                byte[] frame=unhex(args.get(0),1024);
                if(collectingFrame){
                    if(collectedFrame!=null)throw new CloudNativePipe.ProtocolFailure();
                    collectedFrame=frame;
                }else send(frame);
                break;
            case "AUTO":
                if(args.size()!=2)throw new CloudNativePipe.ProtocolFailure();
                ensureStockPaused();platform.sendNativeBuffer(CloudPlatform.YUN,(int)Long.parseLong(args.get(0)),unhex(args.get(1),256));
                nativeWrite=true;break;
            case "NOTIFY":
                if(args.size()!=1||!args.get(0).matches("[0-9]{1,10}"))throw new CloudNativePipe.ProtocolFailure();break;
            case "ARM":case "CANCEL":case "FIRED":
                if(args.size()!=(kind.equals("ARM")?2:1))throw new CloudNativePipe.ProtocolFailure();
                for(String arg:args)if(!arg.matches("[0-9]{1,16}"))throw new CloudNativePipe.ProtocolFailure();
                // TICK drives original callbacks. Never synthesize completion from a timer.
                break;
            default:throw new CloudNativePipe.ProtocolFailure();
        }
    }
    @Override public String call(String kind,List<String> args)throws Exception{
        check();if(primitives==null)throw new CloudNativePipe.ProtocolFailure();
        power.check();
        if(kind.startsWith("SECONDARY_"))return secondaryCall(kind,args);
        return primitives.call(kind,args);
    }
    private String secondaryCall(String kind,List<String> args)throws Exception{
        ensureStockPaused();
        switch(kind){
            case "SECONDARY_CONNECT":{
                if(args.size()!=3||secondary!=null||!args.get(2).matches("[1-9][0-9]{0,4}"))
                    throw new CloudNativePipe.ProtocolFailure();
                byte[] name=unhex(args.get(0),253);
                for(byte b:name)if(b<33||b>126)throw new CloudNativePipe.ProtocolFailure();
                String host=new String(name,StandardCharsets.US_ASCII);int port=Integer.parseInt(args.get(2));
                String selected=new String(unhex(args.get(1),15),StandardCharsets.US_ASCII);
                if(!nativeDns.getOrDefault(host,Collections.emptySet()).contains(selected))
                    throw new CloudNativePipe.ProtocolFailure();
                String[] octets=selected.split("\\.",-1);byte[] address=new byte[4];
                if(octets.length!=4)throw new CloudNativePipe.ProtocolFailure();
                for(int i=0;i<4;i++)address[i]=(byte)Integer.parseInt(octets[i]);
                secondary=resources.own(dependencies.secondary((h,p)->{
                    if(discoveryHost==null||!discoveryHost.equals(h)||discoveryPort!=p)
                        throw new CloudNativePipe.ProtocolFailure();
                }));
                try{
                    secondary.connect(host,java.net.InetAddress.getByAddress(address),port,5000);
                    ensureStockPaused();return "CONNECTED 1";
                }catch(java.net.ConnectException|java.net.NoRouteToHostException|
                        java.net.UnknownHostException|java.net.SocketTimeoutException unavailable){
                    resources.retire(secondary);secondary=null;ensureStockPaused();return "CONNECTED 0";
                }catch(javax.net.ssl.SSLException tls){
                    if(certificateFailure(tls))throw certificateRefusal(tls);
                    // Peer EOF/reset during handshake has no application write.
                    // It is not evidence that this firmware/identity is invalid.
                    resources.retire(secondary);secondary=null;ensureStockPaused();return "CONNECTED 0";
                }catch(java.security.GeneralSecurityException trust){
                    throw new CloudSessionLoop.PermanentFailure(Code.NATIVE_UNAVAILABLE);
                }
            }
            case "SECONDARY_WRITE":{
                if(args.size()!=1||secondary==null)throw new CloudNativePipe.ProtocolFailure();
                byte[] payload=unhex(args.get(0),1024);
                int count=secondary.write(payload,2000);
                // A partial/unknown completion must not authorize the original
                // success callback or replay this command on another stream.
                if(count!=payload.length){resources.retire(secondary);secondary=null;throw new CloudNativePipe.ProtocolFailure();}
                ensureStockPaused();return "WRITTEN "+count;
            }
            case "SECONDARY_CLOSE":
                if(!args.isEmpty())throw new CloudNativePipe.ProtocolFailure();
                if(secondary!=null){resources.retire(secondary);secondary=null;}
                return "OK";
            default:throw new CloudNativePipe.ProtocolFailure();
        }
    }
    private List<String> command(String value)throws Exception{
        check();sink.progress();nativeWrite=false;List<String> result=nativePipe.exchange(value,this,8000);sink.progress();
        String[] done=result.get(result.size()-1).split(" ");
        if(done.length==4 && done[0].equals("DONE") && done[1].equals("532")){
            if(nativeWrite && value.startsWith("RX "))sink.count(Metric.COMMAND_FORWARDED);
            if(done[3].equals("1"))sink.count(Metric.COMMAND_COMPLETED);
        }
        return result;
    }
    private void input(String key,byte[] bytes)throws Exception{command(key+" "+hex(bytes));}
    private void freshEntropy()throws Exception{byte[] nonce=new byte[16];random.nextBytes(nonce);input("N",nonce);input("T",le((int)(System.currentTimeMillis()/1000)));}
    private static String singleResult(List<String> lines)throws IOException{
        if(lines.size()!=2||!lines.get(1).startsWith("DONE "))throw new CloudNativePipe.ProtocolFailure();return lines.get(0);
    }
    private void check(){if(closed)throw new CancellationException("owner_stopped");resources.check();}
    @Override public void close()throws Exception{
        closed=true;resources.close();
        synchronized(operationLock){resources.close();}
        // STOP stops local ownership. It never claims that factory cloud identity was restored.
    }
    static byte[] le(int value){return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();}
    static String hex(byte[] bytes){char[] out=new char[bytes.length*2];String digits="0123456789abcdef";for(int i=0;i<bytes.length;i++){int v=bytes[i]&255;out[i*2]=digits.charAt(v>>>4);out[i*2+1]=digits.charAt(v&15);}return new String(out);}
    static byte[] unhex(String text,int bound)throws IOException{
        if(text.length()%2!=0||text.length()>bound*2||!text.matches("[0-9a-f]+"))throw new CloudNativePipe.ProtocolFailure();
        byte[] result=new byte[text.length()/2];for(int i=0;i<result.length;i++)result[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);return result;
    }
    private static final class FrameSource implements AutoCloseable {
        final CloudTransport transport;final ArrayBlockingQueue<byte[]> frames=new ArrayBlockingQueue<>(16);
        volatile boolean closed,failed;Thread reader;
        FrameSource(CloudTransport transport){this.transport=transport;}
        synchronized void start(){
            if(closed)throw new CancellationException("owner_stopped");
            reader=new Thread(()->{try{while(!closed){
                    try{if(!frames.offer(transport.readFrame()))throw new IOException("network_queue_overflow");}
                    catch(CloudTransport.IdleTimeout idle){ /* No bytes consumed; the native timer loop remains active. */ }
                }}catch(Exception error){failed=true;}},"cloud-frame-reader");reader.setDaemon(true);reader.start();
        }
        byte[] poll(long wait)throws Exception{
            if(closed||failed)throw new IOException("network_reader_failed");
            byte[] result=frames.poll(wait,TimeUnit.MILLISECONDS);
            if(closed||failed)throw new IOException("network_reader_failed");return result;
        }
        public void close()throws Exception{
            Thread thread;synchronized(this){closed=true;frames.clear();thread=reader;}
            transport.close();
            if(thread!=null){thread.interrupt();thread.join(1000);if(thread.isAlive())throw new IOException("network_reader_cleanup_failed");}
        }
    }
}
