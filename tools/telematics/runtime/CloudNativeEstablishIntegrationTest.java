package dev.denza.tools.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

/** Real production establish orchestration and ARM64 code; synthetic SDK/DNS/TLS only. */
public final class CloudNativeEstablishIntegrationTest {
    private static void need(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static String key(int device,int fid){return device+"/"+Integer.toHexString(fid);}
    public static void main(String[] args)throws Exception{
        JSONObject fixture=new JSONObject(Files.readString(Path.of(args[3])));
        JSONObject ints=fixture.getJSONObject("integers"),props=fixture.getJSONObject("properties");
        JSONArray absentProperties=fixture.optJSONArray("absent_properties");
        boolean expectStartupRefusal=args.length==5&&args[4].equals("expect-startup-refusal");
        JSONObject buffers=fixture.optJSONObject("buffer_results");
        Map<String,byte[]> replies=new HashMap<>();
        JSONArray script=fixture.getJSONArray("script");
        for(int i=0;i<script.length();i++){
            String[] operation=script.getString(i).split(" ",2);
            if(Set.of("R211","R200","R220").contains(operation[0]))
                replies.put(operation[0],CloudNativeConnection.unhex(operation[1],1024));
        }
        need(replies.size()==3,"bootstrap response fixture required");
        List<String> trace=new ArrayList<>();
        List<CloudRuntimeBoundaryTest.FakeSocket> sockets=new ArrayList<>();
        long uptimeBase=fixture.optLong("uptime_ms",1000);
        long elapsedOffset=fixture.optLong("elapsed_ms",uptimeBase)-uptimeBase;
        long wallOffset=fixture.optLong("wall_ms",1700000000000L+uptimeBase)-uptimeBase;
        AtomicLong time=new AtomicLong(uptimeBase);
        Clock clock=new Clock(){public long nowMs(){return time.get()+elapsedOffset;}
            public long nativeMonotonicMs(){return time.get();}
            public long wallMs(){return time.get()+wallOffset;}};
        Path dir=Files.createTempDirectory("cloud-establish-");
        CloudRuntimeBoundaryTest.FakeBackend sdk=new CloudRuntimeBoundaryTest.FakeBackend(){
            public byte[] getBuffer(int d,int f){
                trace.add("GET_BUFFER "+key(d,f));
                if((d==1001||d==1027)&&f==0x9900021a)return fixture.optString("vin").getBytes(StandardCharsets.US_ASCII);
                if(d==1027&&f==0x99000035)return fixture.optString("auto_vin").getBytes(StandardCharsets.US_ASCII);
                if(d==1034&&f==0x99000005)try{return CloudNativeConnection.unhex(fixture.optString("parameters"),33);}
                    catch(IOException impossible){throw new AssertionError(impossible);}
                throw new AssertionError("missing buffer "+key(d,f));
            }
            public CloudPlatform.BufferResult getBufferResult(int d,int f){
                String k=key(d,f);trace.add("GET_BUFFER_RESULT "+k);
                if(buffers!=null&&buffers.has(k)){
                    JSONObject result=buffers.optJSONObject(k);String hex=result.optString("bytes","");
                    int status=result.optInt("status");
                    try{return new CloudPlatform.BufferResult(status,
                        hex.isEmpty()?(status==0?new byte[0]:null):CloudNativeConnection.unhex(hex,512));}
                    catch(IOException impossible){throw new AssertionError(impossible);}
                }
                return new CloudPlatform.BufferResult(0,getBuffer(d,f));
            }
            public int getInt(int d,int f){
                String k=key(d,f);if(!ints.has(k))throw new AssertionError("missing getter "+k);
                return ints.optInt(k);
            }
            public float getFloat(int d,int f){
                need(d==1014&&f==CloudPlatform.SOC,"unexpected float getter");return (float)fixture.optDouble("soc");
            }
            public String property(String k){
                trace.add("GET_PROPERTY "+k);
                if(!props.has(k)&&absentProperties!=null){
                    for(int i=0;i<absentProperties.length();i++)
                        if(k.equals(absentProperties.optString(i)))return "";
                }
                need(props.has(k),"missing property "+k);return props.optString(k);
            }
            public String serial(){return "";}
            public int setBuffer(int d,int f,byte[] value){trace.add("SET_BUFFER "+key(d,f));return 0;}
            public int setInt(int d,int f,int value){trace.add("SET_INT "+key(d,f));return 0;}
            public void setProperty(String k,String value){
                need(k.equals("sys.cloud.remote_controling")&&value.equals("0"),
                    "unsupported shared property write "+k);
                trace.add("SET_PROPERTY "+k);
                try{props.put(k,value);}catch(JSONException impossible){throw new AssertionError(impossible);}
            }
            public void stockGate(int state){need(state==-5,"unexpected physical gate");trace.add("STOCK_PAUSED");}
        };
        CloudRuntimeSupervisor supervisor=new CloudRuntimeSupervisor(clock,new OwnerLock(){
            public boolean acquire(){return true;}public void release(){}public void close(){}
        },()->{},(scope,pair,sink)->{},code->{});
        CloudNativeConnection.Dependencies dependencies=new CloudNativeConnection.Dependencies(){
            public void verifyFirmware(){}
            public Process launch(Path executable)throws Exception{
                return new ProcessBuilder(args[0],args[1],args[2]).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            }
            public CloudPlatform platform(Scope scope,Identity pair)throws Exception{
                return CloudPlatform.open(scope,sdk,pair.iccid,pair.imsi,128);
            }
            public void initializeTls(){trace.add("TLS_IDENTITY");}
            public Path gateJournalPath(){return dir.resolve("gate");}
            public Path registrationJournalPath(){return dir.resolve("registration");}
            public void waitForNativeTimer(long duration){time.addAndGet(duration);}
            public byte[][] dnsLookup(String host,java.util.function.BooleanSupplier stopped)throws Exception{
                JSONArray allowed=fixture.optJSONArray("dns_hosts");boolean found=false;
                for(int i=0;i<allowed.length();i++)if(allowed.optString(i).equals(host))found=true;
                need(found,"unexpected DNS endpoint");
                trace.add("DNS");return CloudDnsResolver.resolve(host,stopped,name->new java.net.InetAddress[]{
                    java.net.InetAddress.getByAddress(new byte[16]),
                    java.net.InetAddress.getByAddress(new byte[]{(byte)203,0,113,7})});
            }
            public CloudTransport transport(){return new CloudTransport((host,port)->{
                int index=sockets.size();need(index<3,"unexpected fourth TLS session");
                String expectedHost=index==0?"dilinkreg-cn.denzacloud.com":index==1?
                    "dilinkaddr-cn.denzacloud.com":"test.denzacloud.com";
                int expectedPort=index==0?6001:index==1?6021:6003;
                need(host.equals(expectedHost)&&port==expectedPort,"wrong TLS endpoint");
                trace.add("TLS_CONNECT "+index);
                byte[] response=replies.get(index==0?"R211":index==1?"R200":"R220");
                CloudRuntimeBoundaryTest.FakeSocket socket=new CloudRuntimeBoundaryTest.FakeSocket(response);
                if(index==2){
                    ByteArrayInputStream login=new ByteArrayInputStream(response);
                    socket.input=new InputStream(){public int read()throws IOException{
                        if(login.available()>0)return login.read();
                        try{Thread.sleep(10);}catch(InterruptedException stopped){throw new IOException(stopped);}
                        throw new java.net.SocketTimeoutException("fixture idle");
                    }};
                }
                sockets.add(socket);return socket;
            });}
        };
        CloudNativeConnection connection=new CloudNativeConnection(Path.of(args[2]),
            new Identity(fixture.getString("iccid"),fixture.getString("imsi")),1,clock,
            supervisor.new Sink(1),new CloudNativeConnection.Registration(),dependencies);
        try{
            connection.establish(new CloudSessionLoop.Progress(){public void pulse(){}
                public void stage(Stage s,Code c){trace.add("STAGE "+s);}});
            need(!expectStartupRefusal,"expected unsupported firmware startup refusal");
            need(sockets.size()==3,"full bootstrap did not connect");
            need(sockets.get(0).closed&&sockets.get(1).closed&&!sockets.get(2).closed,
                "bootstrap transports not retired");
            need(sockets.stream().allMatch(s->s.output.size()>0),"opaque requests not written");
            // Optional explicit SDK inputs exercise production callback wiring
            // and original lazy constructors. They are not real telemetry or
            // proof of command success; no protocol fields are built here.
            JSONArray dataCallbacks=fixture.optJSONArray("data_callbacks");
            if(dataCallbacks!=null)for(int i=0;i<dataCallbacks.length();i++){
                sdk.sinks.get(0).buffer(CloudPlatform.YUN,CloudPlatform.YUN_DATA,
                    CloudNativeConnection.unhex(dataCallbacks.getString(i),74));
                connection.pump(0,new CloudSessionLoop.Progress(){
                    public void pulse(){}public void stage(Stage s,Code c){}
                });
            }
        }catch(CloudSessionLoop.PermanentFailure refusal){
            if(!expectStartupRefusal||refusal.code!=Code.UNSUPPORTED_FIRMWARE)
                System.err.println("production establish refusal="+refusal.code+
                    " trace_tail="+trace.subList(Math.max(0,trace.size()-24),trace.size()));
            need(expectStartupRefusal&&refusal.code==Code.UNSUPPORTED_FIRMWARE,
                "unexpected permanent refusal "+refusal.code);
            need(trace.contains("STOCK_PAUSED")==false&&
                trace.stream().noneMatch(s->s.startsWith("TLS_CONNECT "))&&sockets.isEmpty(),
                "startup refusal mutated stock gate or connected TLS");
        }catch(Exception|Error failure){
            System.err.println("production establish boundary trace="+trace);throw failure;
        }finally{
            connection.close();
            need(sdk.registrations==sdk.unregistrations,"subscription leaked");
            need(sockets.stream().allMatch(s->s.closed),"socket leaked");
            try(java.util.stream.Stream<Path> paths=Files.list(dir)){for(Path path:paths.toList())Files.delete(path);}
            Files.delete(dir);
        }
        System.out.println(expectStartupRefusal
            ? "PASS expected UNSUPPORTED_FIRMWARE before stock pause/TLS"
            : "PASS production establish with original ARM64 bootstrap/post-login/keepalive and empty serial; synthetic SDK/DNS/TLS, no live qualification");
    }
}
