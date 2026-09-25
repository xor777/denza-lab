package dev.denza.tools.runtime;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

/** A fake native child and socket check clock ordering without vehicle or cloud access. */
public final class CloudNativeClockTest {
    private static void need(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void child(Path trace,String mode)throws Exception{
        System.out.println("READY");
        System.out.println("CAPS PROTO2=1 REG=1 DATA=1 CONTROL_AWAKE="+
            (mode.equals("missing-bridge")?"0":"1")+
            " WAKE_ACK_AWAKE=1 TIMERS_AWAKE=1 POST_LOGIN_AWAKE=1 HEARTBEAT=1");
        System.out.flush();
        boolean registrationAnswered=false,prematureNet=mode.equals("premature");
        try(BufferedReader in=new BufferedReader(new InputStreamReader(System.in))){
            for(String line;(line=in.readLine())!=null;){
                String[] words=line.split(" ");
                String operation=words[3];
                Files.writeString(trace,operation+(operation.equals("TICK")?" "+words[4]+" "+words[5]+" "+words[6]:"")+"\n",
                    StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                String id=words[1],epoch=words[2];
                if(prematureNet&&registrationAnswered&&operation.equals("TICK"))
                    System.out.println("NET "+id+" "+epoch+" "+CloudNativeConnection.hex(CloudRuntimeBoundaryTest.frame(48)));
                if(operation.equals("NETSTATE")||operation.equals("R211")||operation.equals("R200")||operation.equals("KEEPALIVE"))
                    System.out.println("NET "+id+" "+epoch+" "+CloudNativeConnection.hex(CloudRuntimeBoundaryTest.frame(48)));
                if(operation.equals("R200"))System.out.println("RESULT "+id+" "+epoch+" ENDPOINT test.denzacloud.com 6000");
                else if(operation.equals("R220"))System.out.println("RESULT "+id+" "+epoch+" LOGIN 1");
                else if(operation.equals("R211")){System.out.println("RESULT "+id+" "+epoch+" REG 1");registrationAnswered=true;}
                System.out.println("DONE "+id+" "+epoch+" OK");System.out.flush();
            }
        }
    }
    private static void missingStockBridgeFailsBeforePlatform()throws Exception{
        Path trace=Files.createTempFile("native-capability-", ".txt");
        java.util.concurrent.atomic.AtomicInteger platformCalls=new java.util.concurrent.atomic.AtomicInteger();
        CloudNativeConnection.Dependencies dependencies=new CloudNativeConnection.Dependencies(){
            public void verifyFirmware(){}
            public Process launch(Path executable)throws Exception{
                return new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",
                    System.getProperty("java.class.path"),CloudNativeClockTest.class.getName(),"--child",trace.toString(),"missing-bridge")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            }
            public CloudPlatform platform(Scope resources,Identity pair){platformCalls.incrementAndGet();throw new AssertionError();}
            public void initializeTls(){platformCalls.incrementAndGet();throw new AssertionError();}
            public CloudTransport transport(){platformCalls.incrementAndGet();throw new AssertionError();}
        };
        CloudNativeConnection connection=new CloudNativeConnection(Path.of("unused"),
            new Identity("89010000000000000001","001010123456789"),1,()->1000,null,
            new CloudNativeConnection.Registration(),dependencies);
        try{
            try{connection.establish(new CloudSessionLoop.Progress(){public void pulse(){}public void stage(Stage s,Code c){}});
                throw new AssertionError("unqualified stock lifecycle admitted");}
            catch(CloudSessionLoop.PermanentFailure expected){need(expected.code==Code.NATIVE_UNAVAILABLE,"wrong capability refusal");}
            need(platformCalls.get()==0&&Files.size(trace)==0,"capability failure touched platform");
        }finally{connection.close();Files.deleteIfExists(trace);}
    }
    private static void primaryTlsFailure(boolean certificate)throws Exception{
        Path dir=Files.createTempDirectory("native-tls-refusal-");
        Path trace=dir.resolve("trace");
        java.util.concurrent.atomic.AtomicInteger attempts=new java.util.concurrent.atomic.AtomicInteger();
        Clock clock=()->1000;
        CloudRuntimeSupervisor supervisor=new CloudRuntimeSupervisor(clock,new OwnerLock(){
            public boolean acquire(){return true;}public void release(){}public void close(){}
        },()->{},(scope,pair,sink)->{},code->{});
        CloudNativeConnection.Dependencies dependencies=new CloudNativeConnection.Dependencies(){
            public void verifyFirmware(){}
            public Process launch(Path executable)throws Exception{
                return new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",
                    System.getProperty("java.class.path"),CloudNativeClockTest.class.getName(),"--child",trace.toString(),"normal")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            }
            public CloudPlatform platform(Scope resources,Identity pair)throws Exception{
                return CloudPlatform.open(resources,new CloudRuntimeBoundaryTest.FakeBackend(){
                    public String property(String key){return key.equals("persist.sys.byd.apn_type")?"double_apn":"0";}
                },pair.iccid,pair.imsi,8);
            }
            public void initializeTls(){}
            public Path gateJournalPath(){return dir.resolve("gate");}
            public Path registrationJournalPath(){return dir.resolve("registration");}
            public CloudTransport transport(){return new CloudTransport((host,port)->{
                attempts.incrementAndGet();
                javax.net.ssl.SSLException failure=new javax.net.ssl.SSLHandshakeException("synthetic handshake failure");
                failure.initCause(certificate?new java.security.cert.CertificateException("synthetic trust failure"):
                    new EOFException("synthetic peer reset"));
                throw failure;
            });}
        };
        CloudNativeConnection connection=new CloudNativeConnection(Path.of("unused"),
            new Identity("89010000000000000001","001010123456789"),1,clock,supervisor.new Sink(1),
            new CloudNativeConnection.Registration(),dependencies);
        try{
            try{connection.establish(new CloudSessionLoop.Progress(){public void pulse(){}public void stage(Stage s,Code c){}});
                throw new AssertionError("TLS failure accepted");}
            catch(CloudSessionLoop.PermanentFailure failure){
                need(certificate&&failure.code==Code.NATIVE_UNAVAILABLE,"transient handshake marked permanent");
            }catch(javax.net.ssl.SSLException failure){need(!certificate,"certificate failure remained retryable");}
            need(attempts.get()==1,"failed handshake retried within attempt");
        }finally{
            connection.close();
            try(java.util.stream.Stream<Path> files=Files.list(dir)){for(Path path:files.toList())Files.delete(path);}
            Files.delete(dir);
        }
    }
    private static void clockBeforeLogin(boolean prematureNet,boolean suspendPreflight,
            CloudNativeConnection.Registration registration,long base)throws Exception{
        Path trace=Files.createTempFile("native-clock-order-", ".txt");
        Path gate=trace.resolveSibling(trace.getFileName()+".gate");
        Path registrationMarker=trace.resolveSibling(trace.getFileName()+".registration");
        AtomicLong now=new AtomicLong(base+1000);
        AtomicLong uptime=new AtomicLong(base+900);
        Clock clock=new Clock(){public long nowMs(){return now.get();}
            public long nativeMonotonicMs(){return uptime.get();}
            public long wallMs(){return 1700000000000L+now.get();}};
        CloudRuntimeSupervisor supervisor=new CloudRuntimeSupervisor(clock,new OwnerLock(){
            public boolean acquire(){return true;}public void release(){}public void close(){}
        },()->{},(scope,pair,sink)->{},code->{});
        CloudRuntimeSupervisor.Sink sink=supervisor.new Sink(1);
        List<CloudRuntimeBoundaryTest.FakeSocket> sockets=new ArrayList<>();
        java.util.concurrent.atomic.AtomicReference<CloudRuntimeBoundaryTest.FakeBackend> sdk=
            new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger secondaryWrites=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean partial=new java.util.concurrent.atomic.AtomicBoolean();
        CloudNativeConnection.Dependencies dependencies=new CloudNativeConnection.Dependencies(){
            public void verifyFirmware(){}
            public Process launch(Path executable)throws Exception{
                return new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",
                    System.getProperty("java.class.path"),CloudNativeClockTest.class.getName(),"--child",trace.toString(),prematureNet?"premature":"normal")
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            }
            public CloudPlatform platform(Scope resources,Identity pair)throws Exception{
                if(suspendPreflight)now.addAndGet(1_800_000);
                CloudRuntimeBoundaryTest.FakeBackend backend=new CloudRuntimeBoundaryTest.FakeBackend(){
                    public String property(String key){return key.equals("persist.sys.byd.apn_type")?"double_apn":"0";}
                };
                sdk.set(backend);return CloudPlatform.open(resources,backend,pair.iccid,pair.imsi,8);
            }
            public void initializeTls(){}
            public Path gateJournalPath(){return gate;}
            public Path registrationJournalPath(){return registrationMarker;}
            public CloudSecondaryTransport secondary(CloudSecondaryTransport.EndpointAuthorizer authorizer){
                return new CloudSecondaryTransport(authorizer,(host,selected,p,owner)->{
                    need(host.equals("test.denzacloud.com")&&p==6000&&
                        selected.getHostAddress().equals("192.0.2.42"),"secondary route/peer identity changed");
                    return new CloudSecondaryTransport.Channel(){
                        public int read(byte[] bytes,int timeout){return -1;}
                        public int write(byte[] bytes){secondaryWrites.incrementAndGet();return partial.get()?1:bytes.length;}
                        public void close(){}
                    };
                });
            }
            public CloudTransport transport(){return new CloudTransport((name,p)->{
                now.set(base+10000);uptime.set(base+9900);CloudRuntimeBoundaryTest.FakeSocket socket=new CloudRuntimeBoundaryTest.FakeSocket(CloudRuntimeBoundaryTest.frame(48));
                sockets.add(socket);return socket;
            });}
        };
        CloudNativeConnection connection=new CloudNativeConnection(Path.of("unused"),
            new Identity("89010000000000000001","001010123456789"),1,clock,sink,registration,dependencies);
        try{
            if(suspendPreflight){
                try{connection.establish(new CloudSessionLoop.Progress(){public void pulse(){}public void stage(Stage s,Code c){}});
                    throw new AssertionError("preflight sleep was accepted");}
                catch(CloudSessionLoop.PermanentFailure expected){need(expected.code==Code.POWER_LOST,"wrong preflight sleep failure");}
                need(sockets.isEmpty()&&Files.size(trace)==0&&sdk.get().writes==0,
                    "preflight sleep reached native/vehicle/cloud effect");return;
            }
            if(prematureNet){
                try{connection.establish(new CloudSessionLoop.Progress(){public void pulse(){}public void stage(Stage s,Code c){}});
                    throw new AssertionError("bootstrap timer NET routed to registration socket");}
                catch(IOException expected){}
                List<String> operations=Files.readAllLines(trace);
                need(operations.contains("R211")&&sockets.size()==2,"discovery timer phase not reached");
                need(sockets.get(0).output.size()==CloudRuntimeBoundaryTest.frame(48).length,
                    "native timer NET escaped into registration endpoint");
                need(sockets.get(1).output.size()==0,"native timer NET escaped into discovery endpoint");
                return;
            }
            connection.establish(new CloudSessionLoop.Progress(){public void pulse(){}public void stage(Stage s,Code c){}});
            List<String> operations=Files.readAllLines(trace);
            int start=operations.indexOf("START"), login=operations.indexOf("R220");
            need(start>=0&&login>start,"login sequence absent");
            need(operations.containsAll(List.of("NETSTATE","R211","R200"))&&sockets.size()==3,
                "new native child reused an endpoint without original registration/discovery");
            need(!operations.contains("G")&&!operations.contains("D")&&!operations.contains("L"),
                "Java duplicated a firmware-produced bootstrap request");
            need(Collections.frequency(operations,"KEEPALIVE")==1&&operations.indexOf("KEEPALIVE")>login,
                "initial native keepalive missing or sent before actual login");
            need(sockets.get(2).output.size()==2*CloudRuntimeBoundaryTest.frame(48).length,
                "login or original keepalive sent to wrong transport");
            need(operations.get(start+1).equals("TICK "+(base+1000)+" "+(base+900)+" "+(1700000001000L+base)),"initial clock not set after START");
            int fresh=-1;for(int i=start+2;i<login;i++)if(operations.get(i).startsWith("TICK "))fresh=i;
            need(fresh>=0&&operations.get(fresh).equals("TICK "+(base+10000)+" "+(base+9900)+" "+(1700000010000L+base)),
                "login received stale clock after TLS connect");
            need(operations.get(fresh-2).equals("E")&&operations.get(fresh-1).equals("X"),
                "due timer ran before fresh getter snapshots");
            String host=CloudPrimitiveBridgeTest.hex("test.denzacloud.com");
            String address=CloudPrimitiveBridgeTest.hex("192.0.2.42");
            try{connection.call("SECONDARY_CONNECT",List.of(host,address,"6000"));
                throw new AssertionError("unobserved DNS result admitted");}
            catch(CloudNativePipe.ProtocolFailure expected){}
            java.lang.reflect.Field dns=CloudNativeConnection.class.getDeclaredField("nativeDns");dns.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String,Set<String>> addresses=(Map<String,Set<String>>)dns.get(connection);
            addresses.put("test.denzacloud.com",Set.of("192.0.2.42"));
            need(connection.call("SECONDARY_CONNECT",List.of(host,address,"6000")).equals("CONNECTED 1"),"secondary connect reply");
            need(connection.call("SECONDARY_WRITE",List.of("010203")).equals("WRITTEN 3"),"opaque write reply");
            need(connection.call("SECONDARY_CLOSE",List.of()).equals("OK"),"secondary close reply");
            connection.call("SECONDARY_CONNECT",List.of(host,address,"6000"));partial.set(true);
            try{connection.call("SECONDARY_WRITE",List.of("010203"));throw new AssertionError("partial write acknowledged");}
            catch(CloudNativePipe.ProtocolFailure expected){}
            need(secondaryWrites.get()==2,"uncertain secondary command replayed");
            java.lang.reflect.Method drain=CloudNativeConnection.class.getDeclaredMethod("drainPlatform");
            drain.setAccessible(true);
            sdk.get().sinks.get(1).integer(CloudPlatform.POWER,CloudPlatform.POWER_ACC,1);
            drain.invoke(connection);
            need(Files.readAllLines(trace).equals(operations),"guard-only ACC event reached native command observer");
            // The getter still says ON; a queued OFF must nevertheless terminate
            // the session rather than being hidden by its later ON sample.
            sdk.get().sinks.get(1).integer(CloudPlatform.POWER,CloudPlatform.POWER_ACC,0);
            try{drain.invoke(connection);throw new AssertionError("queued OFF was ignored");}
            catch(java.lang.reflect.InvocationTargetException expected){
                need(expected.getCause() instanceof CloudSessionLoop.PermanentFailure&&
                    ((CloudSessionLoop.PermanentFailure)expected.getCause()).code==Code.POWER_LOST,
                    "wrong queued power refusal");
            }
        }finally{connection.close();Files.deleteIfExists(trace);Files.deleteIfExists(gate);Files.deleteIfExists(registrationMarker);}
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0){child(Path.of(args[1]),args[2]);return;}
        CloudNativeConnection.Registration owner=new CloudNativeConnection.Registration();
        clockBeforeLogin(false,false,owner,0);
        clockBeforeLogin(false,false,owner,20000);
        clockBeforeLogin(true,false,new CloudNativeConnection.Registration(),0);
        clockBeforeLogin(false,true,new CloudNativeConnection.Registration(),0);
        missingStockBridgeFailsBeforePlatform();
        primaryTlsFailure(true);primaryTlsFailure(false);
        System.out.println("PASS native clock, fresh-child bootstrap, TLS refusal, secondary boundary and capability cases=7");
    }
}
