package dev.denza.tools.runtime;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Host-only boundary checks with fake SDK and socket. No vehicle, cloud or TLS credentials. */
public final class CloudRuntimeBoundaryTest {
    static void need(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    static class FakeBackend implements CloudPlatform.Backend {
        final List<CloudPlatform.Sink> sinks = new ArrayList<>();
        int registrations, unregistrations, failAt, writes;
        boolean failUnsubscribe;
        public byte[] getBuffer(int d, int f) {
            if (d == CloudPlatform.BODYWORK) return "TEST123456789ABCD".getBytes();
            byte[] b = new byte[33]; b[0] = 1; return b;
        }
        public int getInt(int d, int f) { return 1; }
        public float getFloat(int d, int f) { return 74.0f; }
        public int tcpState() { return 0; }
        public void stockGate(int value) { }
        public String property(String key) { return key.equals("persist.sys.cloud_enable") ? "" : "0"; }
        public void setProperty(String key, String value) {
            need(key.equals("sys.cloud.remote_controling") && value.equals("0"), "property escaped allowlist");
            writes++;
        }
        public String serial() { return "TESTSERIAL"; }
        public Object subscribe(int d, int[] f, CloudPlatform.Sink sink) throws Exception {
            if (++registrations == failAt) throw new IOException("synthetic subscription failure");
            if(d==CloudPlatform.POWER)
                need(Arrays.equals(f,new int[]{CloudPlatform.MCU_STATE,CloudPlatform.POWER_ACC}),
                    "awake guard power subscription incomplete");
            sinks.add(sink); return sink;
        }
        public void unsubscribe(Object token) throws Exception {
            unregistrations++;
            if (failUnsubscribe) throw new IOException("synthetic unregister failure");
        }
        public int setBuffer(int d, int f, byte[] b) { writes++; return 0; }
        public int setInt(int d, int f, int value) { writes++; return 0; }
    }
    static class FakeSocket extends SSLSocket {
        InputStream input; final ByteArrayOutputStream output = new ByteArrayOutputStream();
        volatile boolean closed;
        FakeSocket(byte[] input) { this.input = new ByteArrayInputStream(input); }
        public InputStream getInputStream() { return input; }
        public OutputStream getOutputStream() throws IOException { return output; }
        public synchronized void close() throws IOException { closed = true; }
        public void startHandshake() { }
        public String[] getSupportedCipherSuites() { return new String[0]; }
        public String[] getEnabledCipherSuites() { return new String[0]; }
        public void setEnabledCipherSuites(String[] c) { }
        public String[] getSupportedProtocols() { return new String[0]; }
        public String[] getEnabledProtocols() { return new String[0]; }
        public void setEnabledProtocols(String[] p) { }
        public SSLSession getSession() { return null; }
        public void addHandshakeCompletedListener(HandshakeCompletedListener l) { }
        public void removeHandshakeCompletedListener(HandshakeCompletedListener l) { }
        public void setUseClientMode(boolean b) { }
        public boolean getUseClientMode() { return true; }
        public void setNeedClientAuth(boolean b) { }
        public boolean getNeedClientAuth() { return true; }
        public void setWantClientAuth(boolean b) { }
        public boolean getWantClientAuth() { return false; }
        public void setEnableSessionCreation(boolean b) { }
        public boolean getEnableSessionCreation() { return false; }
    }
    static byte[] frame(int n) {
        byte[] b = new byte[n + 5]; b[0] = (byte)0xfe; b[1] = (byte)0xfe;
        b[2] = 3; b[3] = (byte)(n >>> 8); b[4] = (byte)n; return b;
    }
    static void alreadySatisfiedSharedProperty() throws Exception {
        final String[] observed={"0"};
        final int[] reads={0};
        FakeBackend backend=new FakeBackend(){
            @Override public String property(String key){
                need(key.equals("sys.cloud.remote_controling"),"wrong shared property read");
                reads[0]++;return observed[0];
            }
        };
        CloudPlatform.AndroidBackend.satisfyRemoteControlZero(
            "sys.cloud.remote_controling","0",backend);
        need(reads[0]==1&&backend.writes==0,"idempotent zero invoked shared setter");
        for(String current:new String[]{"1",""}){
            observed[0]=current;
            try{
                CloudPlatform.AndroidBackend.satisfyRemoteControlZero(
                    "sys.cloud.remote_controling","0",backend);
                throw new AssertionError("unqualified shared property accepted");
            }catch(IOException expected){
                need("shared_property_original_unqualified".equals(expected.getMessage()),
                    "wrong shared property failure");
            }
        }
        need(reads[0]==3&&backend.writes==0,"unqualified value invoked shared setter");
        try{
            CloudPlatform.AndroidBackend.satisfyRemoteControlZero(
                "sys.cloud.remote_controling","1",backend);
            throw new AssertionError("unsupported value accepted");
        }catch(IllegalArgumentException expected){}
        need(reads[0]==3&&backend.writes==0,"unsupported value read or wrote shared property");
    }
    static void deniedOptionalProperty() throws Exception {
        FakeBackend backend=new FakeBackend();
        try(CloudPlatform p=CloudPlatform.open(new CloudRuntimeSupervisor.Scope(),backend,
                "89010000000000000001","001010123456789",4)){
            for(String value:new String[]{"0","1"})
                need(p.sendNativePropertyStatus("persist.sys.edge.enable.sre",value)==-1,
                    "optional property refusal invented success");
            for(String key:new String[]{"ril.imsi","sys.tcp_connect_status"}){
                try{p.sendNativePropertyStatus(key,"0");throw new AssertionError("property escaped scope");}
                catch(IllegalStateException expected){}
            }
            try{p.sendNativePropertyStatus("persist.sys.edge.enable.sre","2");throw new AssertionError("value escaped scope");}
            catch(IllegalStateException expected){}
            need(backend.writes==0,"denied optional property invoked backend");
        }
    }
    static void awakeStartupState() throws Exception {
        java.util.Map<String,String> values=new java.util.HashMap<>();
        FakeBackend backend=new FakeBackend(){
            public String property(String key){return values.getOrDefault(key,key.equals("persist.sys.energytype")?"0":"");}
            public int getInt(int device,int fid){return -17;}
        };
        try(CloudPlatform p=CloudPlatform.open(new CloudRuntimeSupervisor.Scope(),backend,
                "89010000000000000001","001010123456789",4)){
            p.requireAwakeStartup();
            for(String key:new String[]{"persist.sys.cloudtest","persist.sys.repair_mode.enable",
                    "persist.sys.repair_mode_record","persist.sys.cloud_enable","persist.sys.version",
                    "persist.sys.mcu_version"}){
                values.put(key,key.equals("persist.sys.cloud_enable")?"0":"1");
                try{p.requireAwakeStartup();throw new AssertionError("unsupported startup accepted");}
                catch(CloudSessionLoop.PermanentFailure expected){
                    need(expected.code==CloudRuntimeSupervisor.Code.UNSUPPORTED_FIRMWARE,"wrong startup refusal");
                }
                values.clear();
            }
            for(int[] getter:new int[][]{{1009,0x47002011},{1009,0x47002012},
                    {1023,0x2940002a},{1023,0x4540000c},{1025,0x4f401038}})
                need(p.getInt(getter[0],getter[1])==-17,"SDK value changed");
            need(backend.writes==0,"startup preflight changed stock state");
        }
    }
    static void platform() throws Exception {
        FakeBackend emptySerial = new FakeBackend() {
            @Override public String serial() { return ""; }
        };
        try (CloudPlatform p = CloudPlatform.open(new CloudRuntimeSupervisor.Scope(), emptySerial,
                "89010000000000000001", "001010123456789", 4)) {
            need(p.identity.serial().length == 0 && emptySerial.registrations == 4,
                "empty original serial rejected or replaced");
            need(emptySerial.writes == 0, "empty serial triggered vehicle write");
        }
        need(emptySerial.unregistrations == 4, "empty serial subscriptions leaked");
        FakeBackend failing = new FakeBackend(); failing.failAt = 3;
        try { CloudPlatform.open(new CloudRuntimeSupervisor.Scope(), failing, "89010000000000000001", "001010123456789", 2);
            throw new AssertionError("partial constructor passed"); }
        catch (IOException expected) { need(failing.unregistrations == 2, "partial subscriptions leaked"); }
        FakeBackend badCleanup = new FakeBackend(); badCleanup.failAt = 2; badCleanup.failUnsubscribe = true;
        try { CloudPlatform.open(new CloudRuntimeSupervisor.Scope(), badCleanup, "89010000000000000001", "001010123456789", 2);
            throw new AssertionError("constructor failure passed"); }
        catch (IOException expected) {
            need(expected.getMessage().contains("subscription") && expected.getSuppressed().length == 1,
                "cleanup replaced original failure");
        }
        FakeBackend backend = new FakeBackend();
        CloudPlatform platform = CloudPlatform.open(new CloudRuntimeSupervisor.Scope(), backend, "89010000000000000001", "001010123456789", 1);
        byte[] pair = platform.identity.iccid(); pair[0] = 0;
        need(platform.identity.iccid()[0] == '8', "identity mutated");
        CloudPlatform.Sink yun = backend.sinks.get(0);
        yun.buffer(CloudPlatform.YUN, CloudPlatform.YUN_MCU, new byte[6]);
        yun.buffer(CloudPlatform.YUN, CloudPlatform.YUN_MCU, new byte[6]);
        need(!platform.healthy(), "queue overflow ignored");
        try { platform.sendNativeBuffer(CloudPlatform.YUN, CloudPlatform.MCU_ENVELOPE, new byte[6]);
            throw new AssertionError("write after overflow passed"); }
        catch (IllegalStateException expected) { need(backend.writes == 0, "write after overflow"); }
        platform.close(); need(backend.unregistrations == 4, "normal unsubscribe");
        yun.error(); need(platform.poll(0) == null, "stale callback queued");
        FakeBackend valid = new FakeBackend();
        try (CloudPlatform p = CloudPlatform.open(new CloudRuntimeSupervisor.Scope(), valid, "89010000000000000001", "001010123456789", 4)) {
            need(p.getNativeBuffer(1027,0x9900021a).status==0,
                "original network-ready buffer getter unavailable");
            need(p.getNativeBuffer(1027,0x99000035).status==0,
                "original secondary VIN getter unavailable");
            try{p.getNativeBuffer(1027,0x9900021b);throw new AssertionError("unknown buffer getter admitted");}
            catch(IllegalStateException expected){}
            valid.sinks.get(1).integer(CloudPlatform.POWER, CloudPlatform.MCU_STATE, 0);
            CloudPlatform.Event e = p.poll(0);
            need(e != null && e.kind == CloudPlatform.Event.Kind.INTEGER && e.value == 0, "real MCU state lost");
            for(int acc:new int[]{1,0}){
                valid.sinks.get(1).integer(CloudPlatform.POWER,CloudPlatform.POWER_ACC,acc);
                CloudPlatform.Event power=p.poll(0);
                need(p.healthy()&&power!=null&&power.device==CloudPlatform.POWER&&
                    power.fid==CloudPlatform.POWER_ACC&&power.value==acc,"real ACC event lost or invalidated platform");
            }
            p.sendNativeBuffer(CloudPlatform.YUN, CloudPlatform.MCU_ENVELOPE, new byte[6]);
            p.sendNativeInt(CloudPlatform.POWER, CloudPlatform.MCU_WAKE, 1);
            p.sendNativeProperty("sys.cloud.remote_controling", "0");
            need(valid.writes == 3, "approved native writes");
            try { p.sendNativeBuffer(CloudPlatform.BODYWORK, CloudPlatform.MCU_ENVELOPE, new byte[6]);
                throw new AssertionError("unknown setter passed"); }
            catch (IllegalStateException expected) { need(valid.writes == 3, "unknown setter wrote"); }
            for (String[] property : new String[][]{{"sys.cloud.remote_controling", "1"},
                    {"sys.cloud.unlock_index", "1"}, {"ril.imsi", "460010000000000"}}) {
                try { p.sendNativeProperty(property[0], property[1]);
                    throw new AssertionError("unqualified shared property passed"); }
                catch (IllegalStateException expected) { need(valid.writes == 3, "unqualified property wrote"); }
            }
        }
    }
    static void transport() throws Exception {
        byte[] raw = frame(48); FakeSocket socket = new FakeSocket(raw);
        CloudTransport t = new CloudTransport((host, port) -> socket);
        t.open("example.invalid", 6041, 1000);
        need(Arrays.equals(raw, t.readFrame()), "complete frame");
        t.sendNativeFrame(raw); need(Arrays.equals(raw, socket.output.toByteArray()), "native send");
        t.close(); need(socket.closed, "socket not closed");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeSocket late = new FakeSocket(raw);
        CloudTransport delayed = new CloudTransport((host, port) -> {
            entered.countDown(); for(;;)try{release.await();break;}catch(InterruptedException ignored){} return late;
        });
        Thread opening = new Thread(() -> {
            try { delayed.open("example.invalid", 6041, 30000); throw new AssertionError("cancelled open passed"); }
            catch (SocketTimeoutException expected) { }
            catch (Exception error) { throw new AssertionError(error); }
        });
        opening.start(); need(entered.await(1, TimeUnit.SECONDS), "connect did not start");
        try { delayed.close(); throw new AssertionError("pending connector cleanup passed"); }
        catch(IOException expected) { }
        opening.join(3000); need(!opening.isAlive(), "cancel did not release opener");
        try { delayed.sendNativeFrame(raw); throw new AssertionError("cancelled socket sent registration"); }
        catch (IOException expected) { }
        release.countDown();
        for (int i = 0; i < 100 && !late.closed; i++) Thread.sleep(10);
        need(late.closed, "late TLS socket was adopted");delayed.close();
        need(late.output.size() == 0, "late socket sent native data");
    }
    static void faultBoundaries() throws Exception {
        FakeBackend uncertain=new FakeBackend(){
            public Object subscribe(int d,int[] f,CloudPlatform.Sink sink)throws Exception {
                if(registrations==1)throw new CloudPlatform.SubscriptionCleanupUncertain(new IOException("partial register"));
                return super.subscribe(d,f,sink);
            }
        };
        CloudRuntimeSupervisor.Scope scope=new CloudRuntimeSupervisor.Scope();
        try { CloudPlatform.open(scope,uncertain,"89010000000000000001","001010123456789",4);throw new AssertionError(); }
        catch(CloudPlatform.SubscriptionCleanupUncertain expected){}
        try{scope.close();throw new AssertionError("uncertain listener released scope");}catch(IOException expected){}
        need(uncertain.unregistrations==1,"prior subscription cleanup skipped");
        FakeBackend backend=new FakeBackend();CloudRuntimeSupervisor.Scope owner=new CloudRuntimeSupervisor.Scope();
        CloudPlatform p=CloudPlatform.open(owner,backend,"89010000000000000001","001010123456789",4);
        p.close();try{p.stockGate(4);throw new AssertionError("late stock gate");}catch(IllegalStateException expected){}
        FakeSocket failedClose=new FakeSocket(frame(48)){
            public synchronized void close()throws IOException{throw new IOException("synthetic close uncertainty");}
        };
        CloudTransport unresolved=new CloudTransport((host,port)->failedClose);unresolved.open("example.invalid",6041,1000);
        try{unresolved.close();throw new AssertionError("socket close debt lost");}catch(IOException expected){}
        try{unresolved.close();throw new AssertionError("socket close debt reset");}catch(IOException expected){}
        FakeSocket partialWrite=new FakeSocket(frame(48)){
            public OutputStream getOutputStream(){return new OutputStream(){public void write(int b)throws IOException{throw new IOException("partial write");}};}
        };
        CloudTransport broken=new CloudTransport((host,port)->partialWrite);broken.open("example.invalid",6041,1000);
        try{broken.sendNativeFrame(frame(48));throw new AssertionError();}catch(IOException expected){}
        need(partialWrite.closed,"failed stream kept open");
        try{broken.sendNativeFrame(frame(48));throw new AssertionError("next frame used broken stream");}catch(IOException expected){}
        FakeSocket idleSocket=new FakeSocket(frame(48));
        idleSocket.input=new ByteArrayInputStream(frame(48)){
            boolean first=true;
            public synchronized int read(){if(first){first=false;return super.read();}return super.read();}
        };
        // A socket timeout before the first byte must keep the stream usable.
        InputStream following=idleSocket.input;
        idleSocket.input=new InputStream(){boolean first=true;
            public int read()throws IOException{if(first){first=false;throw new SocketTimeoutException();}return following.read();}
            public int read(byte[] b,int off,int len)throws IOException{return following.read(b,off,len);}
        };
        CloudTransport idle=new CloudTransport((host,port)->idleSocket);idle.open("example.invalid",6041,1000);
        try{idle.readFrame();throw new AssertionError();}catch(CloudTransport.IdleTimeout expected){}
        need(!idleSocket.closed && Arrays.equals(idle.readFrame(),frame(48)),"idle timeout poisoned a complete later frame");idle.close();
        byte[] bytes=frame(48);FakeSocket trickle=new FakeSocket(bytes);
        trickle.input=new InputStream(){int at;public int read()throws IOException{
            try{Thread.sleep(50);}catch(InterruptedException e){throw new IOException();}
            return at<bytes.length?bytes[at++]&255:-1;
        }public int read(byte[] b,int off,int len)throws IOException{int n=read();if(n<0)return -1;b[off]=(byte)n;return 1;}};
        CloudTransport slow=new CloudTransport((host,port)->trickle);slow.open("example.invalid",6041,1000);slow.setReadTimeoutMs(1000);
        long start=System.nanoTime();try{slow.readFrame();throw new AssertionError("trickle exceeded frame budget");}catch(SocketTimeoutException expected){}
        need(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2)&&trickle.closed,"whole frame timeout");
    }
    public static void main(String[] args) throws Exception {
        platform(); alreadySatisfiedSharedProperty(); deniedOptionalProperty(); awakeStartupState(); transport(); faultBoundaries();
        System.out.println("PASS cloud runtime boundaries");
    }
}
