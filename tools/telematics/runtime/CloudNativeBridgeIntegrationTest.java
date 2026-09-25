package dev.denza.tools.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Research-only real ARM64 IPC replay. Every external input/effect is an explicit fixture. */
public final class CloudNativeBridgeIntegrationTest {
    private static String key(int device,int fid){return device+"/"+String.format(Locale.ROOT,"%08x",fid);}
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("python wrapper binary fixture required");
        JSONObject fixture=new JSONObject(Files.readString(Path.of(args[3])));
        JSONObject integers=fixture.getJSONObject("integers"),properties=fixture.getJSONObject("properties");
        Map<String,Integer> integerValues=new HashMap<>();
        Map<String,String> propertyValues=new HashMap<>();
        Map<String,CloudPlatform.BufferResult> bufferValues=new HashMap<>();
        JSONObject bufferFixtures=fixture.optJSONObject("buffer_results");
        if(bufferFixtures!=null)for(Iterator<String> keys=bufferFixtures.keys();keys.hasNext();){
            String k=keys.next();JSONObject value=bufferFixtures.getJSONObject(k);
            int status=value.getInt("status");String bytes=value.optString("bytes","");
            bufferValues.put(k,new CloudPlatform.BufferResult(status,
                bytes.isEmpty()?null:CloudNativeConnection.unhex(bytes,512)));
        }
        for(Iterator<String> keys=integers.keys();keys.hasNext();){String k=keys.next();integerValues.put(k,integers.getInt(k));}
        for(Iterator<String> keys=properties.keys();keys.hasNext();){String k=keys.next();propertyValues.put(k,properties.getString(k));}
        float soc=(float)fixture.getDouble("soc");
        Set<String> dnsHosts=new HashSet<>();
        JSONArray hosts=fixture.getJSONArray("dns_hosts");
        for(int i=0;i<hosts.length();i++)dnsHosts.add(hosts.getString(i));
        byte[] vin=fixture.getString("vin").getBytes(StandardCharsets.US_ASCII);
        byte[] autoVin=fixture.getString("auto_vin").getBytes(StandardCharsets.US_ASCII);
        byte[] params=CloudNativeConnection.unhex(fixture.getString("parameters"),33);
        List<String> effects=new ArrayList<>(),calls=new ArrayList<>();
        try(CloudRuntimeSupervisor.Scope scope=new CloudRuntimeSupervisor.Scope()){
            CloudPlatform platform=CloudPlatform.open(scope,new CloudRuntimeBoundaryTest.FakeBackend(){
                @Override public byte[] getBuffer(int device,int fid){
                    if((device==1001||device==1027)&&fid==0x9900021a)return vin.clone();
                    if(device==1027&&fid==0x99000035)return autoVin.clone();
                    if(device==1034&&fid==0x99000005)return params.clone();
                    throw new AssertionError("unprovided buffer fixture "+key(device,fid));
                }
                @Override public CloudPlatform.BufferResult getBufferResult(int device,int fid){
                    CloudPlatform.BufferResult known=bufferValues.get(key(device,fid));
                    return known!=null?known:new CloudPlatform.BufferResult(0,getBuffer(device,fid));
                }
                @Override public int getInt(int device,int fid){
                    String k=key(device,fid);
                    if(!integerValues.containsKey(k))throw new AssertionError("unprovided integer fixture "+k);
                    return integerValues.get(k);
                }
                @Override public float getFloat(int device,int fid){
                    if(device==1014&&fid==CloudPlatform.SOC)return soc;
                    throw new AssertionError("unprovided float fixture "+key(device,fid));
                }
                @Override public String property(String name){
                    if(!propertyValues.containsKey(name))throw new AssertionError("unprovided property fixture "+name);
                    return propertyValues.get(name);
                }
                @Override public int setBuffer(int device,int fid,byte[] bytes){
                    effects.add("fixture AUTO "+key(device,fid)+" bytes="+bytes.length);return 0;
                }
                @Override public int setInt(int device,int fid,int value){
                    effects.add("fixture SET_INT "+key(device,fid));return 0;
                }
                @Override public void setProperty(String name,String value){effects.add("fixture PROPERTY_SET "+name);}
            },fixture.getString("iccid"),fixture.getString("imsi"),128);
            CloudPrimitiveBridge bridge=new CloudPrimitiveBridge(new CloudPrimitiveBridge.Platform(){
                public int getInt(int d,int f)throws Exception{return platform.getInt(d,f);}
                public CloudPlatform.BufferResult getBuffer(int d,int f)throws Exception{return platform.getNativeBuffer(d,f);}
                public float getFloat(int d,int f)throws Exception{return platform.getFloat(d,f);}
                public String property(String k)throws Exception{return platform.property(k);}
                public void setInt(int d,int f,int v)throws Exception{platform.sendNativeInt(d,f,v);}
                public void setProperty(String k,String v)throws Exception{platform.sendNativeProperty(k,v);}
                public byte[][] dnsLookup(String host){
                    if(!dnsHosts.contains(host))
                        throw new AssertionError("unprovided DNS fixture "+host);
                    return new byte[][]{{(byte)203,0,113,7},{(byte)203,0,113,8},{(byte)203,0,113,9}};
                }
                public String waitForEvent(long deadline){throw new AssertionError("WAIT fixture not provided");}
            });
            Process child=new ProcessBuilder(args[0],args[1],args[2])
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            CloudNativePipe pipe=CloudNativePipe.open(scope,child,1);
            CloudSecondaryTransport[] secondary={null};
            boolean[] discovered={false};
            ArrayDeque<Integer> writtenFrames=new ArrayDeque<>();
            // Deliberately a research-level protocol replay, not a product START:
            // capability values are untouched and never upgraded by this test.
            CloudNativePipe.Effects forwarding=new CloudNativePipe.Effects(){
                public String call(String kind,List<String> arguments)throws Exception{
                    String boundary=kind;
                    if(kind.startsWith("PROPERTY_")&&!arguments.isEmpty())
                        boundary+=" "+new String(CloudNativeConnection.unhex(arguments.get(0),96),StandardCharsets.US_ASCII);
                    calls.add(boundary);
                    if(kind.equals("LINK_STATE"))return CloudNativeConnection.nativeLinkState(arguments);
                    if(kind.equals("SECONDARY_CONNECT")){
                        if(arguments.size()!=3||secondary[0]!=null||!discovered[0])throw new AssertionError("secondary phase");
                        String host=new String(CloudNativeConnection.unhex(arguments.get(0),253),StandardCharsets.US_ASCII);
                        String ip=new String(CloudNativeConnection.unhex(arguments.get(1),15),StandardCharsets.US_ASCII);
                        if(!List.of("203.0.113.7","203.0.113.8","203.0.113.9").contains(ip))
                            throw new AssertionError("secondary selected IP outside fixture");
                        secondary[0]=scope.own(new CloudSecondaryTransport((h,p)->{
                            if(!h.equals("test.denzacloud.com")||p!=6003)throw new AssertionError("secondary endpoint outside fixture");
                        },(h,address,p,owner)->new CloudSecondaryTransport.Channel(){
                            public int read(byte[] bytes,int timeout){return -1;}
                            public int write(byte[] bytes){effects.add("fixture SECONDARY_WRITE bytes="+bytes.length);return bytes.length;}
                            public void close(){}
                        }));
                        secondary[0].connect(host,java.net.InetAddress.getByName(ip),Integer.parseInt(arguments.get(2)),1000);
                        return "CONNECTED 1";
                    }
                    if(kind.equals("SECONDARY_WRITE")){
                        if(arguments.size()!=1||secondary[0]==null)throw new AssertionError("secondary write phase");
                        return "WRITTEN "+secondary[0].write(CloudNativeConnection.unhex(arguments.get(0),1024),1000);
                    }
                    if(kind.equals("SECONDARY_CLOSE")){
                        if(!arguments.isEmpty())throw new AssertionError("secondary close arity");
                        if(secondary[0]!=null){scope.retire(secondary[0]);secondary[0]=null;}
                        return "OK";
                    }
                    return bridge.call(kind,arguments);
                }
                public void event(String kind,List<String> arguments)throws Exception{
                    if(kind.equals("AUTO")){
                        if(arguments.size()!=2)throw new AssertionError("AUTO arity");
                        platform.sendNativeBuffer(CloudPlatform.YUN,Integer.parseUnsignedInt(arguments.get(0)),
                            CloudNativeConnection.unhex(arguments.get(1),256));
                    }else if(kind.equals("NET")){
                        if(arguments.size()!=2)throw new AssertionError("NET metadata missing");
                        CloudNativeConnection.unhex(arguments.get(1),1024);
                        // The offline transport explicitly accepts this complete
                        // opaque frame; production acknowledges only a real write.
                        writtenFrames.addLast(Integer.parseInt(arguments.get(0)));
                        effects.add("fixture NET");
                    }else if(List.of("ARM","CANCEL","FIRED","NOTIFY").contains(kind))effects.add("fixture "+kind);
                    else throw new AssertionError("unknown event "+kind);
                }
            };
            JSONArray script=fixture.getJSONArray("script");
            JSONObject expected=fixture.optJSONObject("expected_results");
            JSONObject expectedNets=fixture.optJSONObject("expected_net_counts");
            JSONObject expectedFailure=fixture.optJSONObject("expected_failure");
            boolean failureObserved=false;
            for(int i=0;i<script.length();i++){
                String operation=script.getString(i);
                try{
                    long netsBefore=effects.stream().filter(e->e.equals("fixture NET")).count();
                    List<String> results=pipe.exchange(operation,forwarding,10000);
                    for(int count=0;!writtenFrames.isEmpty();count++){
                        if(count>=64)throw new AssertionError("unbounded send completion fixture");
                        pipe.exchange("SENT "+writtenFrames.removeFirst(),forwarding,10000);
                    }
                    if(expected!=null&&expected.has(Integer.toString(i))&&
                       !results.contains(expected.getString(Integer.toString(i))))
                        throw new AssertionError("native result did not match fixture at step "+i);
                    if(expectedNets!=null&&expectedNets.has(Integer.toString(i))&&
                       effects.stream().filter(e->e.equals("fixture NET")).count()-netsBefore!=
                       expectedNets.getInt(Integer.toString(i)))
                        throw new AssertionError("native wire effect count did not match fixture at step "+i);
                    if(results.contains("ENDPOINT test.denzacloud.com 6003"))discovered[0]=true;
                }
                catch(Exception|Error failure){
                    if(expectedFailure!=null&&expectedFailure.getInt("step")==i&&
                       expectedFailure.getString("type").equals(failure.getClass().getSimpleName())){
                        failureObserved=true;break;
                    }
                    // Boundary names identify missing dependencies without logging identities or payloads.
                    System.err.println("native integration step="+i+" op="+operation.split(" ",2)[0]+" calls="+calls+" effects="+effects);
                    throw failure;
                }
            }
            if(expectedFailure!=null&&!failureObserved)throw new AssertionError("expected failure did not occur");
            System.out.println("PASS original native FFI steps="+script.length()+" capabilities="+pipe.capabilities()+
                " calls="+calls+" effects="+effects+"; synthetic external boundaries, no live qualification");
        }
    }
}
