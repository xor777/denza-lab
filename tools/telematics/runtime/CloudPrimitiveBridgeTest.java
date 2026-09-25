package dev.denza.tools.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.*;
import static dev.denza.tools.runtime.CloudRuntimeSupervisor.*;

public final class CloudPrimitiveBridgeTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static String hex(String text){return CloudNativeConnection.hex(text.getBytes(StandardCharsets.US_ASCII));}
    static String bits(double value){return Long.toUnsignedString(Double.doubleToRawLongBits(value));}
    static void checkAtof(CloudPrimitiveBridge bridge,String input,double expected)throws Exception{
        check(bridge.call("C_ATOF",List.of(input.isEmpty()?"-":hex(input))).equals("VALUE "+bits(expected)));
    }
    static void primitives()throws Exception{
        List<String> calls=new ArrayList<>();
        AtomicReference<CloudPlatform.BufferResult> buffer=new AtomicReference<>(
            new CloudPlatform.BufferResult(0,new byte[]{1,2,3}));
        AtomicInteger entropyCalls=new AtomicInteger();
        AtomicInteger propertyStatus=new AtomicInteger();
        AtomicReference<IOException> propertyStatusFailure=new AtomicReference<>();
        CloudPrimitiveBridge p=new CloudPrimitiveBridge(new CloudPrimitiveBridge.Platform(){
            public int getInt(int device,int fid){calls.add("int:"+device+":"+Integer.toUnsignedString(fid));return -17;}
            public CloudPlatform.BufferResult getBuffer(int device,int fid){
                calls.add("buffer:"+device+":"+Integer.toUnsignedString(fid));
                return buffer.get();
            }
            public float getFloat(int device,int fid){return 74.5f;}
            public String property(String key){calls.add("property:"+key);return "";}
            public void setInt(int device,int fid,int value){calls.add("set:"+device+":"+Integer.toUnsignedString(fid)+":"+value);}
            public void setProperty(String key,String value)throws IOException{
                if(!key.equals("sys.cloud.remote_controling")||!value.equals("0"))throw new IOException("unsupported fixture property");
                calls.add("property_set:"+key+":"+value);
            }
            public int setPropertyStatus(String key,String value)throws IOException{
                calls.add("property_status:"+key+":"+value);
                IOException failure=propertyStatusFailure.get();
                if(failure!=null)throw failure;
                return propertyStatus.get();
            }
            public byte[][] dnsLookup(String host){calls.add("dns:"+host);return new byte[][]{{1,2,3,4},{5,6,7,8}};}
            public String waitForEvent(long deadline){return "TIMEOUT 1000";}
        },bytes->Arrays.fill(bytes,(byte)entropyCalls.incrementAndGet()));
        check(p.call("RANDOM_BYTES",List.of("16")).equals("BYTES "+"01".repeat(16)));
        check(p.call("RANDOM_BYTES",List.of("16")).equals("BYTES "+"02".repeat(16)));
        try{p.call("RANDOM_BYTES",List.of("17"));throw new AssertionError();}
        catch(CloudNativePipe.ProtocolFailure expected){}
        check(entropyCalls.get()==2);
        for(String key:List.of("persist.sys.sentrymode_record","persist.sys.smart_charge_stage_record",
                "persist.sys.cloud_fid_uploaded","persist.sys.record_421_notify",
                "persist.sys.record_499_upload")){
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE -"));
            int before=calls.size();
            check(p.call("PROPERTY_SET",List.of(hex(key),hex("4"))).equals("OK"));
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE 34"));
            check(calls.size()==before);
        }
        int beforeMath=calls.size();
        check(p.call("MATH_POW",List.of(bits(10),bits(0))).equals("VALUE "+bits(1)));
        check(p.call("MATH_POW",List.of(bits(10),bits(-2))).equals("VALUE "+bits(0.01)));
        check(p.call("MATH_POW",List.of(bits(10),bits(3))).equals("VALUE "+bits(1000)));
        check(p.call("MATH_POW",List.of(bits(10),bits(400))).equals("VALUE "+bits(Double.POSITIVE_INFINITY)));
        check(p.call("MATH_POW",List.of(bits(10),bits(-400))).equals("VALUE "+bits(0)));
        check(p.call("MATH_POW",List.of(bits(-0.0),bits(3))).equals("VALUE "+bits(-0.0)));
        check(p.call("MATH_POW",List.of("18446744073709551615",bits(0))).equals("VALUE "+bits(1)));
        for(List<String> invalid:List.of(List.<String>of(),List.of(bits(10)),
                List.of(bits(10),bits(2),bits(3)))){
            try{p.call("MATH_POW",invalid);throw new AssertionError();}
            catch(CloudNativePipe.ProtocolFailure expected){}
        }
        for(String invalid:List.of("","-1","+1","00","01"," 1","1 ","1.0",
                "18446744073709551616","999999999999999999999")){
            for(List<String> args:List.of(List.of(invalid,bits(2)),List.of(bits(10),invalid))){
                try{p.call("MATH_POW",args);throw new AssertionError();}
                catch(CloudNativePipe.ProtocolFailure expected){}
            }
        }
        check(calls.size()==beforeMath);
        int beforeAtof=calls.size();
        checkAtof(p,"123.5",123.5);
        checkAtof(p,"\t\n  +.5E2 trailing",50);
        checkAtof(p," -0.01,2",-0.01);
        checkAtof(p,"7,8",7);
        checkAtof(p,"1e+oops",1);
        checkAtof(p,"1e-",1);
        checkAtof(p,"0x1p+",1);
        checkAtof(p,"0x",0);
        checkAtof(p,"",0);
        checkAtof(p,"  + 1",0);
        checkAtof(p,"junk",0);
        checkAtof(p," -0",-0.0);
        checkAtof(p,"-1e-9999",-0.0);
        checkAtof(p,"1e9999",Double.POSITIVE_INFINITY);
        checkAtof(p,"INFplus",Double.POSITIVE_INFINITY);
        checkAtof(p,"-infinity;",Double.NEGATIVE_INFINITY);
        checkAtof(p,"NaN(payload)",Double.NaN);
        checkAtof(p,"-NaN",Double.longBitsToDouble(0xfff8000000000000L));
        checkAtof(p,"0x1.8p+2!",6);
        checkAtof(p,"0X1.8!",1.5);
        checkAtof(p,"1\u0000ignored",1);
        checkAtof(p,"1"+"x".repeat(127),1);
        for(List<String> invalid:List.of(List.<String>of(),List.of("31","32"),
                List.of(""),List.of("0"),List.of("zz"),List.of("0A"),
                List.of("80"),List.of("31ff"),List.of(hex("1".repeat(129))))){
            try{p.call("C_ATOF",invalid);throw new AssertionError();}
            catch(CloudNativePipe.ProtocolFailure expected){}
        }
        check(calls.size()==beforeAtof);
        check(p.call("GET_INT",Arrays.asList("1005","2566914051")).equals("VALUE -17"));
        check(p.call("GET_BUFFER",Arrays.asList("1027","2566914050")).equals("BUFFER 0 010203"));
        check(p.call("GET_BUFFER",Arrays.asList("1027","2566914586")).equals("BUFFER 0 010203"));
        buffer.set(new CloudPlatform.BufferResult(-7,null));
        check(p.call("GET_BUFFER",Arrays.asList("1027","2566914050")).equals("BUFFER -7 -"));
        buffer.set(new CloudPlatform.BufferResult(0,new byte[513]));
        try{p.call("GET_BUFFER",Arrays.asList("1027","2566914050"));throw new AssertionError();}
        catch(IOException expected){}
        buffer.set(new CloudPlatform.BufferResult(0,new byte[]{1,2,3}));
        check(p.call("GET_FLOAT",Arrays.asList("1014","1246777400")).equals("VALUE "+Integer.toUnsignedString(Float.floatToRawIntBits(74.5f))));
        check(p.call("PROPERTY_GET",List.of(hex("persist.sys.record_610_upload"))).equals("VALUE -"));
        String sre=hex("persist.sys.edge.enable.sre");
        check(p.call("PROPERTY_SET_STATUS",List.of(sre,hex("1"))).equals("VALUE 0"));
        propertyStatus.set(-1);
        check(p.call("PROPERTY_SET_STATUS",List.of(sre,hex("0"))).equals("VALUE -1"));
        check(calls.contains("property_status:persist.sys.edge.enable.sre:1")&&
            calls.contains("property_status:persist.sys.edge.enable.sre:0"));
        IOException backendFailure=new IOException("property backend failure");
        propertyStatusFailure.set(backendFailure);
        try{p.call("PROPERTY_SET_STATUS",List.of(sre,hex("1")));throw new AssertionError();}
        catch(IOException expected){check(expected==backendFailure);}
        propertyStatusFailure.set(null);
        propertyStatus.set(1);
        try{p.call("PROPERTY_SET_STATUS",List.of(sre,hex("1")));throw new AssertionError();}
        catch(CloudNativePipe.ProtocolFailure expected){}
        int beforeInvalidStatus=calls.size();
        for(List<String> invalid:List.of(List.<String>of(),List.of(sre),
                List.of(sre,hex("1"),hex("0")),
                List.of(hex("sys.tcp_step"),hex("1")),List.of(sre,hex("2")),
                List.of(sre,hex("01")),List.of(sre,"zz"))){
            try{p.call("PROPERTY_SET_STATUS",invalid);throw new AssertionError();}
            catch(CloudNativePipe.ProtocolFailure expected){}
        }
        check(calls.size()==beforeInvalidStatus);
        check(p.call("PROPERTY_SET",Arrays.asList(hex("sys.tcp_step"),hex("6"))).equals("OK"));
        int count=calls.size();check(p.call("PROPERTY_GET",List.of(hex("sys.tcp_step"))).equals("VALUE 36"));check(calls.size()==count);
        check(p.call("PROPERTY_GET",List.of(hex("sys.tcp_connect_status"))).equals("VALUE 30"));
        p.call("PROPERTY_SET",Arrays.asList(hex("sys.tcp_connect_status"),hex("1")));
        check(p.call("PROPERTY_GET",List.of(hex("sys.tcp_connect_status"))).equals("VALUE 31"));
        check(calls.size()==count);
        check(p.call("PROPERTY_GET",List.of(hex("persist.sys.505_req_status"))).equals("VALUE 30"));
        for(String state:List.of("1","0","1")){
            p.call("PROPERTY_SET",List.of(hex("persist.sys.505_req_status"),hex(state)));
            check(p.call("PROPERTY_GET",List.of(hex("persist.sys.505_req_status"))).equals("VALUE "+hex(state)));
        }
        try{p.call("PROPERTY_SET",List.of(hex("persist.sys.505_req_status"),hex("2")));throw new AssertionError();}
        catch(CloudNativePipe.ProtocolFailure expected){}
        check(calls.size()==count); // No global property reads or writes for the latch.
        String virtualVin="ABTEST123456789ABCD";
        p.call("PROPERTY_SET",List.of(hex("sys.virtual.vin"),hex(virtualVin)));
        check(p.call("PROPERTY_GET",List.of(hex("sys.virtual.vin"))).equals("VALUE "+hex(virtualVin)));
        check(calls.size()==count); // No physical VIN publication.
        try{p.call("PROPERTY_SET",List.of(hex("sys.virtual.vin"),hex("short")));throw new AssertionError();}
        catch(CloudNativePipe.ProtocolFailure expected){}
        p.call("PROPERTY_SET",List.of(hex("sys.cloud_domin_ip"),hex("211: _200:")));
        p.call("PROPERTY_SET",List.of(hex("sys.cloud_domin_ip"),hex("211:192.0.2.1 _200:192.0.2.2")));
        check(calls.size()==count); // Isolated diagnostic publication, not stock DNS state.
        p.call("SET_INT",Arrays.asList("1005","2852126794","1"));p.call("PROPERTY_SET",Arrays.asList(hex("sys.cloud.remote_controling"),hex("0")));
        check(calls.contains("set:1005:2852126794:1")&&calls.contains("property_set:sys.cloud.remote_controling:0"));count=calls.size();
        try{p.call("PROPERTY_SET",Arrays.asList(hex("ril.imsi"),hex("460010000000000")));throw new AssertionError();}catch(IOException expected){}
        for(String key:List.of("sys.cloud.unlock_index","sys.vin_valid_record_time")){
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE 30"));
            p.call("PROPERTY_SET",List.of(hex(key),hex("123")));
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE "+hex("123")));
            for(String invalid:List.of("2147483648","-2147483649","01","x")){
                try{p.call("PROPERTY_SET",List.of(hex(key),hex(invalid)));throw new AssertionError();}
                catch(CloudNativePipe.ProtocolFailure expected){}
            }
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE "+hex("123")));
        }
        for(String key:List.of("persist.sys.cloud.unlock_type","sys.cloud.unlock_uuid",
                "sys.cloud_532_reply","sys.tcp_reg_errcode","persist.sys.cloud.user_id","sys.cloud.201_send_status")){
            // An unpublished local value is absent; original code owns fallback.
            int before=calls.size();
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE -"));
            check(calls.size()==before);
            p.call("PROPERTY_SET",List.of(hex(key),hex("1")));
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE 31"));
        }
        for(String key:List.of("persist.sys.cloud.user_id","sys.cloud.unlock_uuid")){
            p.call("PROPERTY_SET_NULL",List.of(hex(key)));
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE -"));
        }
        try{p.call("PROPERTY_SET_NULL",List.of(hex("sys.tcp_reg_errcode")));throw new AssertionError();}
        catch(CloudNativePipe.ProtocolFailure expected){}
        try{p.call("PROPERTY_SET",List.of(hex("sys.cloud.201_send_status"),hex("4")));throw new AssertionError();}
        catch(CloudNativePipe.ProtocolFailure expected){}
        try{p.call("UNKNOWN_NATIVE_CALL",List.of());throw new AssertionError();}
        catch(CloudNativePipe.ProtocolFailure expected){check(expected.code==Code.NATIVE_UNAVAILABLE);}
        check(calls.size()==count);check(p.call("WAIT",List.of("1000000000")).equals("TIMEOUT 1000"));
        check(p.call("DNS_LOOKUP",List.of(hex("dilinkaddr-cn.denzacloud.com"))).equals("IPV4 01020304 05060708"));
        check(calls.contains("dns:dilinkaddr-cn.denzacloud.com"));
        int beforeDns=calls.size();
        try{p.call("DNS_LOOKUP",List.of(hex("attacker.example")));throw new AssertionError();}catch(IOException expected){}
        check(calls.size()==beforeDns);
        TimeZone original=TimeZone.getDefault();
        try{
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            check(p.call("LOCALTIME",List.of("0")).equals("TM 0 0 0 1 0 70 4 0 0"));
            check(p.call("MKTIME",Arrays.asList("0","0","0","1","0","70","4","0","0")).equals("VALUE 0"));
        }finally{TimeZone.setDefault(original);}
    }
    static void privateCounterLifetime()throws Exception{
        CloudPrimitiveBridge.Platform forbidden=new CloudPrimitiveBridge.Platform(){
            public int getInt(int d,int f){throw new AssertionError();}
            public CloudPlatform.BufferResult getBuffer(int d,int f){throw new AssertionError();}
            public float getFloat(int d,int f){throw new AssertionError();}
            public String property(String k){throw new AssertionError();}
            public void setInt(int d,int f,int v){throw new AssertionError();}
            public void setProperty(String k,String v){throw new AssertionError();}
            public byte[][] dnsLookup(String h){throw new AssertionError();}
            public String waitForEvent(long t){throw new AssertionError();}
        };
        Map<String,String> ownerCounters=new HashMap<>();
        CloudPrimitiveBridge first=new CloudPrimitiveBridge(forbidden,b->{},ownerCounters);
        try{first.call("PROPERTY_SET_STATUS",List.of(hex("persist.sys.edge.enable.sre"),hex("1")));throw new AssertionError();}
        catch(IOException expected){check("unsupported property status".equals(expected.getMessage()));}
        first.call("PROPERTY_SET",List.of(hex("sys.cloud.unlock_index"),hex("255")));
        first.call("PROPERTY_SET",List.of(hex("sys.vin_valid_record_time"),hex("12345")));
        first.call("PROPERTY_SET",List.of(hex("persist.sys.505_req_status"),hex("1")));
        CloudPrimitiveBridge nextChild=new CloudPrimitiveBridge(forbidden,b->{},ownerCounters);
        check(nextChild.propertyValue("sys.cloud.unlock_index").equals("255"));
        check(nextChild.propertyValue("sys.vin_valid_record_time").equals("12345"));
        check(nextChild.propertyValue("persist.sys.505_req_status").equals("0"));
        CloudPrimitiveBridge newOwner=new CloudPrimitiveBridge(forbidden,b->{},new HashMap<>());
        check(newOwner.propertyValue("sys.cloud.unlock_index").equals("0"));
        check(newOwner.propertyValue("sys.vin_valid_record_time").equals("0"));
    }
    static void propertySetResult()throws Exception{
        List<String> calls=new ArrayList<>();
        AtomicReference<IOException> sharedFailure=new AtomicReference<>();
        CloudPrimitiveBridge.Platform platform=new CloudPrimitiveBridge.Platform(){
            public int getInt(int d,int f){throw new AssertionError();}
            public CloudPlatform.BufferResult getBuffer(int d,int f){throw new AssertionError();}
            public float getFloat(int d,int f){throw new AssertionError();}
            public String property(String key){calls.add("get:"+key);return "17";}
            public void setInt(int d,int f,int v){throw new AssertionError();}
            public void setProperty(String key,String value)throws IOException{
                calls.add("set:"+key+":"+value);
                IOException failure=sharedFailure.get();if(failure!=null)throw failure;
            }
            public byte[][] dnsLookup(String h){throw new AssertionError();}
            public String waitForEvent(long t){throw new AssertionError();}
        };
        CloudPrimitiveBridge p=new CloudPrimitiveBridge(platform,b->{});
        for(String key:List.of("persist.sys.316_req_status","persist.sys.record_499_upload",
                "persist.sys.cloud_fid_uploaded","persist.sys.mcu_func_record",
                "persist.sys.vehicle_sales_record")){
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE "+hex("17")));
            int before=calls.size();
            check(p.call("PROPERTY_SET_RESULT",List.of(hex(key),hex("4"))).equals("VALUE 0"));
            check(p.call("PROPERTY_GET",List.of(hex(key))).equals("VALUE "+hex("4")));
            check(calls.size()==before);
        }
        String data="persist.sys.cloud_412_data";
        check(p.call("PROPERTY_GET",List.of(hex(data))).equals("VALUE -"));
        int before=calls.size();
        check(p.call("PROPERTY_SET_RESULT",List.of(hex(data),hex("opaque-string"))).equals("VALUE 0"));
        check(p.call("PROPERTY_GET",List.of(hex(data))).equals("VALUE "+hex("opaque-string")));
        check(p.call("PROPERTY_SET_NULL",List.of(hex(data))).equals("OK"));
        check(p.call("PROPERTY_GET",List.of(hex(data))).equals("VALUE -"));
        check(calls.size()==before);
        for(String key:List.of("persist.sys.privacy_switch",
                "persist.sys.user_authentication_status",
                "persist.sys.vehicle_camera_support_mark",
                "persist.sys.vehicle_40d_code","ril.imsi")){
            check(p.call("PROPERTY_SET_RESULT",List.of(hex(key),hex("1"))).equals("VALUE -1"));
            check(calls.size()==before);
        }
        check(p.call("PROPERTY_SET_RESULT",List.of(hex("sys.cloud.remote_controling"),hex("1"))).equals("VALUE -1"));
        check(calls.size()==before);
        try{p.call("PROPERTY_SET",List.of(hex("persist.sys.privacy_switch"),hex("1")));throw new AssertionError();}
        catch(CloudNativePipe.ProtocolFailure expected){}
        check(calls.size()==before);
        check(p.call("PROPERTY_SET_RESULT",List.of(hex("sys.cloud.remote_controling"),hex("0"))).equals("VALUE 0"));
        check(calls.get(calls.size()-1).equals("set:sys.cloud.remote_controling:0"));
        IOException failure=new IOException("shared operation failed");sharedFailure.set(failure);
        try{p.call("PROPERTY_SET_RESULT",List.of(hex("sys.cloud.remote_controling"),hex("0")));throw new AssertionError();}
        catch(IOException expected){check(expected==failure);}
    }
    static void incapableNativeCannotTouchPlatform()throws Exception{
        AtomicInteger platformCalls=new AtomicInteger();AtomicReference<Process> process=new AtomicReference<>();
        CloudNativeConnection.Dependencies deps=new CloudNativeConnection.Dependencies(){
            public void verifyFirmware(){}
            public Process launch(java.nio.file.Path executable)throws Exception{Process p=CloudNativePipeTest.child("ok");process.set(p);return p;}
            public CloudPlatform platform(Scope owner,Identity pair){platformCalls.incrementAndGet();throw new AssertionError();}
            public void initializeTls(){platformCalls.incrementAndGet();throw new AssertionError();}
            public CloudTransport transport(){platformCalls.incrementAndGet();throw new AssertionError();}
        };
        CloudNativeConnection c=new CloudNativeConnection(Paths.get("unused"),new Identity("89860700000000000000","460010000000000"),1,
            ()->1000,null,new CloudNativeConnection.Registration(),deps);
        try{c.establish(new CloudSessionLoop.Progress(){public void pulse(){}public void stage(Stage s,Code code){}});throw new AssertionError();}
        catch(CloudSessionLoop.PermanentFailure expected){check(expected.code==Code.NATIVE_UNAVAILABLE);}finally{c.close();}
        check(platformCalls.get()==0&&!process.get().isAlive());
    }
    static void unavailableFirmwareIsNotNetworkRetry()throws Exception{
        java.nio.file.Path directory=java.nio.file.Files.createTempDirectory("cloud-firmware-check-");
        try{
            try{CloudNativeConnection.verifyFirmwareFile(directory.resolve("missing"));throw new AssertionError();}
            catch(CloudSessionLoop.Unavailable expected){}
            java.nio.file.Path different=directory.resolve("different");
            java.nio.file.Files.write(different,new byte[]{1,2,3});
            try{CloudNativeConnection.verifyFirmwareFile(different);throw new AssertionError();}
            catch(CloudSessionLoop.Unavailable expected){}
            finally{java.nio.file.Files.delete(different);}
        }finally{java.nio.file.Files.delete(directory);}
    }
    public static void main(String[]args)throws Exception{
        primitives();privateCounterLifetime();propertySetResult();incapableNativeCannotTouchPlatform();unavailableFirmwareIsNotNetworkRetry();
        System.out.println("PASS primitive forwarding and native capability gate cases=5");
    }
}
