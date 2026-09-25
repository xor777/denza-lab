package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Primitive FFI bridge, without climate/lock/telemetry command handlers. */
public final class CloudPrimitiveBridge {
    public interface Platform {
        int getInt(int device,int fid)throws Exception;
        CloudPlatform.BufferResult getBuffer(int device,int fid)throws Exception;
        float getFloat(int device,int fid)throws Exception;
        String property(String key)throws Exception;
        void setInt(int device,int fid,int value)throws Exception;
        void setProperty(String key,String value)throws Exception;
        byte[][] dnsLookup(String hostname)throws Exception;
        /** Actual SDK event or timeout, never inferred awake. Uses the original condition deadline. */
        String waitForEvent(long deadlineNs)throws Exception;
    }
    private final Platform platform;
    interface Entropy { void fill(byte[] bytes); }
    private final Entropy entropy;
    private final Map<String,String> localProperties=new HashMap<>();
    private final Map<String,String> counters;
    private static final Set<String> PRIVATE_PUBLICATIONS=Set.of(
        "persist.sys.cloud.unlock_type","sys.cloud.unlock_uuid","sys.cloud_532_reply",
        "sys.tcp_reg_errcode","persist.sys.cloud.user_id","sys.cloud.201_send_status");
    public CloudPrimitiveBridge(Platform platform){
        this(platform,new java.security.SecureRandom()::nextBytes);
    }
    CloudPrimitiveBridge(Platform platform,Entropy entropy){
        this(platform,entropy,new HashMap<>());
    }
    CloudPrimitiveBridge(Platform platform,Entropy entropy,Map<String,String> counters){
        this.platform=platform;this.entropy=entropy;localProperties.put("sys.tcp_step","0");
        this.counters=counters;
        counters.putIfAbsent("sys.cloud.unlock_index","0");
        counters.putIfAbsent("sys.vin_valid_record_time","0");
        localProperties.put("sys.tcp_connect_status","0");
        // Original 505 request bookkeeping belongs to this isolated session.
        // A physical stock value must not suppress its own post-login request.
        localProperties.put("persist.sys.505_req_status","0");
    }
    String propertyValue(String key)throws Exception{
        if(counters.containsKey(key))return counters.get(key);
        if(localProperties.containsKey(key))return localProperties.get(key);
        require(!PRIVATE_PUBLICATIONS.contains(key));
        return platform.property(key);
    }
    public String call(String kind,List<String> args)throws Exception{
        switch(kind){
            case "RANDOM_BYTES":{
                require(args.size()==1&&args.get(0).equals("16"));
                byte[] bytes=new byte[16];entropy.fill(bytes);
                return "BYTES "+CloudNativeConnection.hex(bytes);
            }
            case "GET_INT":
                require(args.size()==2);return "VALUE "+platform.getInt(device(args.get(0)),fid(args.get(1)));
            case "GET_BUFFER":{
                require(args.size()==2);
                CloudPlatform.BufferResult value=platform.getBuffer(device(args.get(0)),fid(args.get(1)));
                require(value!=null);
                if(value.status!=0){require(value.bytes==null||value.bytes.length==0);
                    return "BUFFER "+value.status+" -";}
                require(value.bytes!=null&&value.bytes.length<=512);
                return "BUFFER 0 "+(value.bytes.length==0?"-":CloudNativeConnection.hex(value.bytes));
            }
            case "GET_FLOAT":{
                require(args.size()==2);float value=platform.getFloat(device(args.get(0)),fid(args.get(1)));
                require(Float.isFinite(value));return "VALUE "+Integer.toUnsignedString(Float.floatToRawIntBits(value));
            }
            case "PROPERTY_GET":{
                require(args.size()==1);String key=ascii(args.get(0),96);
                String value=propertyValue(key);
                require(value!=null&&value.length()<=91&&value.matches("[ -~]*"));
                return "VALUE "+(value.isEmpty()?"-":CloudNativeConnection.hex(value.getBytes(StandardCharsets.US_ASCII)));
            }
            case "PROPERTY_SET":{
                require(args.size()==2);String key=ascii(args.get(0),96),value=ascii(args.get(1),91);
                if(counters.containsKey(key)){
                    require(value.matches("-?(0|[1-9][0-9]{0,9})"));
                    long count=Long.parseLong(value);require(count>=Integer.MIN_VALUE&&count<=Integer.MAX_VALUE);
                    counters.put(key,value);return "OK";
                }
                if(PRIVATE_PUBLICATIONS.contains(key)){
                    // This is the stock wake-lock handshake, not a cloud ACK.
                    // The custom worker has no ownership of its global value.
                    if(key.equals("sys.cloud.201_send_status"))require(value.matches("[0-3]"));
                    localProperties.put(key,value);return "OK";
                }
                if(key.equals("sys.tcp_step")){
                    require(value.matches("[0-9]{1,2}"));localProperties.put(key,value);return "OK";
                }
                if(key.equals("sys.tcp_connect_status")||key.equals("persist.sys.505_req_status")){
                    require(value.equals("0")||value.equals("1"));
                    localProperties.put(key,value);return "OK";
                }
                if(key.equals("sys.virtual.vin")){
                    // Original 0x5e6c8 already holds this HAL value in its object.
                    // Publish only inside this session's property namespace.
                    require(value.length()==19&&value.charAt(0)!='0');
                    localProperties.put(key,value);return "OK";
                }
                if(key.equals("sys.cloud_domin_ip")){
                    // Original 0x4c2b0 publishes diagnostics from its internal
                    // address buffers; it does not use the shared property.
                    require(value.matches("211:[0-9.]{0,15} _200:[0-9.]{0,15}"));
                    localProperties.put(key,value);return "OK";
                }
                // The firmware profile admits supported shared properties at the SDK boundary.
                // The forwarding layer does not infer or implement vehicle-command semantics.
                platform.setProperty(key,value);return "OK";
            }
            case "PROPERTY_SET_NULL":{
                require(args.size()==1);String key=ascii(args.get(0),96);
                require(key.equals("persist.sys.cloud.user_id")||key.equals("sys.cloud.unlock_uuid"));
                localProperties.put(key,"");return "OK";
            }
            case "SET_INT":
                require(args.size()==3);platform.setInt(device(args.get(0)),fid(args.get(1)),signed(args.get(2)));return "OK";
            case "WAIT":
                require(args.size()==1&&args.get(0).matches("[0-9]{1,19}"));
                long deadline=Long.parseLong(args.get(0));require(deadline>=0);
                return platform.waitForEvent(deadline);
            case "LOCALTIME":{
                require(args.size()==1&&args.get(0).matches("-?(0|[1-9][0-9]{0,18})"));
                final long seconds;
                try{seconds=Long.parseLong(args.get(0));}catch(NumberFormatException bad){throw new CloudNativePipe.ProtocolFailure();}
                require(seconds>=Long.MIN_VALUE/1000 && seconds<=Long.MAX_VALUE/1000);
                Calendar time=Calendar.getInstance();time.setTimeInMillis(seconds*1000);
                return "TM "+time.get(Calendar.SECOND)+" "+time.get(Calendar.MINUTE)+" "+
                    time.get(Calendar.HOUR_OF_DAY)+" "+time.get(Calendar.DAY_OF_MONTH)+" "+
                    time.get(Calendar.MONTH)+" "+(time.get(Calendar.YEAR)-1900)+" "+
                    (time.get(Calendar.DAY_OF_WEEK)-1)+" "+(time.get(Calendar.DAY_OF_YEAR)-1)+" "+
                    (time.getTimeZone().inDaylightTime(time.getTime())?1:0);
            }
            case "MKTIME":{
                require(args.size()==9);
                int[] tm=new int[9];
                for(int i=0;i<tm.length;i++)tm[i]=signed(args.get(i));
                require(tm[5]>=-1800 && tm[5]<=8100 && tm[8]>=-1 && tm[8]<=1);
                Calendar time=Calendar.getInstance();time.clear();time.setLenient(true);
                time.set(tm[5]+1900,tm[4],tm[3],tm[2],tm[1],tm[0]);
                return "VALUE "+Math.floorDiv(time.getTimeInMillis(),1000);
            }
            case "DNS_LOOKUP":{
                require(args.size()==1);String host=ascii(args.get(0),253);
                CloudDnsResolver.requirePublicHost(host);
                byte[][] addresses=platform.dnsLookup(host);
                require(addresses!=null&&addresses.length<=4);
                if(addresses.length==0)return "IPV4 -";
                StringBuilder result=new StringBuilder("IPV4");
                for(byte[] address:addresses){require(address!=null&&address.length==4);
                    result.append(' ').append(CloudNativeConnection.hex(address));}
                return result.toString();
            }
            default:throw new CloudNativePipe.ProtocolFailure();
        }
    }
    private static int device(String value)throws IOException{int n=signed(value);require(n>=1000&&n<=1100);return n;}
    private static int signed(String value)throws IOException{
        require(value.matches("-?(0|[1-9][0-9]{0,9})"));
        try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new CloudNativePipe.ProtocolFailure();}
    }
    private static int fid(String value)throws IOException{
        require(value.matches("[0-9]{1,10}"));long parsed=Long.parseLong(value);require(parsed<=0xffffffffL);return (int)parsed;
    }
    private static String ascii(String value,int bound)throws IOException{
        if(value.equals("-"))return "";
        byte[] bytes=CloudNativeConnection.unhex(value,bound);for(byte b:bytes)require(b>=32&&b<=126);
        return new String(bytes,StandardCharsets.US_ASCII);
    }
    private static void require(boolean ok)throws IOException{if(!ok)throw new CloudNativePipe.ProtocolFailure();}
}
