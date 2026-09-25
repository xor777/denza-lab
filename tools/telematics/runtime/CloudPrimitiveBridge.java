package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

/** Primitive FFI bridge, without climate/lock/telemetry command handlers. */
public final class CloudPrimitiveBridge {
    public interface Platform {
        int getInt(int device,int fid)throws Exception;
        CloudPlatform.BufferResult getBuffer(int device,int fid)throws Exception;
        float getFloat(int device,int fid)throws Exception;
        String property(String key)throws Exception;
        void setInt(int device,int fid,int value)throws Exception;
        void setProperty(String key,String value)throws Exception;
        default int setPropertyStatus(String key,String value)throws Exception{
            throw new IOException("unsupported property status");
        }
        byte[][] dnsLookup(String hostname)throws Exception;
        /** Actual SDK event or timeout, never inferred awake. Uses the original condition deadline. */
        String waitForEvent(long deadlineNs)throws Exception;
    }
    private final Platform platform;
    private final CloudReadOnlyFiles files;
    interface Entropy { void fill(byte[] bytes); }
    private final Entropy entropy;
    private final Map<String,String> localProperties=new HashMap<>();
    private final Map<String,String> counters;
    private static final Set<String> PRIVATE_PUBLICATIONS=Set.of(
        "persist.sys.cloud.unlock_type","sys.cloud.unlock_uuid","sys.cloud_532_reply",
        "sys.tcp_reg_errcode","persist.sys.cloud.user_id","sys.cloud.201_send_status");
    private static final String STRING_REPORT_CACHE="persist.sys.cloud_412_data";
    // Original client bookkeeping. Read the stock snapshot initially, then
    // retain this engine's own publications without changing stock state.
    private static final Set<String> REPORT_CACHE=Set.of(
        "persist.sys.sentrymode_record","persist.sys.smart_charge_stage_record",
        "persist.sys.record_610_upload","persist.sys.system_info",
        "persist.sys.cloud.app_reg_status","persist.sys.cloud_fid_uploaded",
        "persist.sys.record_421_notify","persist.sys.record_499_upload",
        "persist.sys.316_req_status","persist.sys.mcu_func_record",
        "persist.sys.vehicle_sales_record");
    private static final Pattern C_ATOF_PREFIX=Pattern.compile(
        "[\\x09-\\x0d\\x20]*([+-]?(?:(?i:infinity|inf|nan)|"+
        "0[xX](?:[0-9a-fA-F]+(?:\\.[0-9a-fA-F]*)?|\\.[0-9a-fA-F]+)(?:[pP][+-]?[0-9]+)?|"+
        "(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?))");
    public CloudPrimitiveBridge(Platform platform){
        this(platform,new java.security.SecureRandom()::nextBytes);
    }
    CloudPrimitiveBridge(Platform platform,Entropy entropy){
        this(platform,entropy,new HashMap<>());
    }
    CloudPrimitiveBridge(Platform platform,Entropy entropy,Map<String,String> counters){
        this(platform,entropy,counters,null);
    }
    CloudPrimitiveBridge(Platform platform,Entropy entropy,Map<String,String> counters,CloudReadOnlyFiles files){
        this.platform=platform;this.entropy=entropy;localProperties.put("sys.tcp_step","0");
        this.files=files;
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
        // An unpublished value is absent in this session's namespace. The
        // original property_get caller supplies its own default; absence is
        // not a native protocol failure or a read of another client's state.
        if(PRIVATE_PUBLICATIONS.contains(key))return "";
        return platform.property(key);
    }
    public String call(String kind,List<String> args)throws Exception{
        switch(kind){
            case "FILE_OPEN":{
                require(args.size()==2);require(files!=null);
                String path=ascii(args.get(0),CloudReadOnlyFiles.MAX_PATH_LENGTH);
                String mode=ascii(args.get(1),3);
                return "VALUE "+files.open(path,mode);
            }
            case "FILE_ACCESS":{
                require(args.size()==2);require(files!=null);
                String path=ascii(args.get(0),CloudReadOnlyFiles.MAX_PATH_LENGTH);
                return "VALUE "+files.access(path,signed(args.get(1)));
            }
            case "FILE_REMOVE":{
                require(args.size()==1);require(files!=null);
                String path=ascii(args.get(0),CloudReadOnlyFiles.MAX_PATH_LENGTH);
                return "VALUE "+files.remove(path);
            }
            case "FILE_WRITE":{
                require(args.size()==2);require(files!=null);
                int fd=signed(args.get(0));
                byte[] bytes=args.get(1).equals("-")?new byte[0]:CloudNativeConnection.unhex(args.get(1),512);
                return "VALUE "+files.write(fd,bytes);
            }
            case "FILE_READ":{
                require(args.size()==2);require(files!=null);
                CloudReadOnlyFiles.ReadResult result=files.read(signed(args.get(0)),signed(args.get(1)));
                return "BUFFER "+result.status+" "+(result.bytes==null||result.bytes.length==0
                    ?"-":CloudNativeConnection.hex(result.bytes));
            }
            case "FILE_EOF":
                require(args.size()==1);require(files!=null);
                return "VALUE "+files.eof(signed(args.get(0)));
            case "FILE_CLOSE":
                require(args.size()==1);require(files!=null);
                return "VALUE "+files.close(signed(args.get(0)));
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
            case "MATH_POW":{
                require(args.size()==2);
                double base=Double.longBitsToDouble(unsignedBits(args.get(0)));
                double exponent=Double.longBitsToDouble(unsignedBits(args.get(1)));
                return "VALUE "+Long.toUnsignedString(Double.doubleToRawLongBits(StrictMath.pow(base,exponent)));
            }
            case "C_ATOF":{
                require(args.size()==1);
                double value=cAtof(args.get(0));
                return "VALUE "+Long.toUnsignedString(Double.doubleToRawLongBits(value));
            }
            case "PROPERTY_GET":{
                require(args.size()==1);String key=ascii(args.get(0),96);
                String value=propertyValue(key);
                require(value!=null&&value.length()<=91&&value.matches("[ -~]*"));
                return "VALUE "+(value.isEmpty()?"-":CloudNativeConnection.hex(value.getBytes(StandardCharsets.US_ASCII)));
            }
            case "PROPERTY_SET_STATUS":{
                require(args.size()==2);
                String key=ascii(args.get(0),96),value=ascii(args.get(1),91);
                require(key.equals("persist.sys.edge.enable.sre")&&(value.equals("0")||value.equals("1")));
                int status=platform.setPropertyStatus(key,value);
                require(status==0||status==-1);
                return "VALUE "+status;
            }
            case "PROPERTY_SET":
            case "PROPERTY_SET_RESULT":{
                require(args.size()==2);String key=ascii(args.get(0),96),value=ascii(args.get(1),91);
                int status=setProperty(key,value);
                if(kind.equals("PROPERTY_SET_RESULT"))return "VALUE "+status;
                require(status==0);return "OK";
            }
            case "PROPERTY_SET_NULL":{
                require(args.size()==1);String key=ascii(args.get(0),96);
                require(key.equals("persist.sys.cloud.user_id")||key.equals("sys.cloud.unlock_uuid")||
                    key.equals("persist.sys.cloud_412_data"));
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
    private int setProperty(String key,String value)throws Exception{
        if(STRING_REPORT_CACHE.equals(key)){
            localProperties.put(key,value);return 0;
        }
        if(REPORT_CACHE.contains(key)){
            signed(value);localProperties.put(key,value);return 0;
        }
        if(counters.containsKey(key)){
            require(value.matches("-?(0|[1-9][0-9]{0,9})"));
            long count=Long.parseLong(value);require(count>=Integer.MIN_VALUE&&count<=Integer.MAX_VALUE);
            counters.put(key,value);return 0;
        }
        if(PRIVATE_PUBLICATIONS.contains(key)){
            // This includes the stock wake-lock handshake, not a cloud ACK.
            // The custom worker has no ownership of these global values.
            if(key.equals("sys.cloud.201_send_status"))require(value.matches("[0-3]"));
            localProperties.put(key,value);return 0;
        }
        if(key.equals("sys.tcp_step")){
            require(value.matches("[0-9]{1,2}"));localProperties.put(key,value);return 0;
        }
        if(key.equals("sys.tcp_connect_status")||key.equals("persist.sys.505_req_status")){
            require(value.equals("0")||value.equals("1"));
            localProperties.put(key,value);return 0;
        }
        if(key.equals("sys.virtual.vin")){
            // Original 0x5e6c8 already holds this HAL value in its object.
            require(value.length()==19&&value.charAt(0)!='0');
            localProperties.put(key,value);return 0;
        }
        if(key.equals("sys.cloud_domin_ip")){
            require(value.matches("211:[0-9.]{0,15} _200:[0-9.]{0,15}"));
            localProperties.put(key,value);return 0;
        }
        if(key.equals("sys.cloud.remote_controling")&&value.equals("0")){
            platform.setProperty(key,value);return 0;
        }
        // Preserve libc's negative result for an unqualified shared write.
        // Never publish stock privacy, auth, camera, or vehicle state here.
        return -1;
    }
    private static int device(String value)throws IOException{int n=signed(value);require(n>=1000&&n<=1100);return n;}
    private static int signed(String value)throws IOException{
        require(value.matches("-?(0|[1-9][0-9]{0,9})"));
        try{return Integer.parseInt(value);}catch(NumberFormatException e){throw new CloudNativePipe.ProtocolFailure();}
    }
    private static int fid(String value)throws IOException{
        require(value.matches("[0-9]{1,10}"));long parsed=Long.parseLong(value);require(parsed<=0xffffffffL);return (int)parsed;
    }
    private static long unsignedBits(String value)throws IOException{
        require(value.matches("0|[1-9][0-9]{0,19}"));
        try{return Long.parseUnsignedLong(value);}catch(NumberFormatException e){throw new CloudNativePipe.ProtocolFailure();}
    }
    private static double cAtof(String encoded)throws IOException{
        byte[] bytes=encoded.equals("-")?new byte[0]:CloudNativeConnection.unhex(encoded,128);
        for(byte b:bytes)require((b&0x80)==0);
        String text=new String(bytes,StandardCharsets.US_ASCII);
        Matcher prefix=C_ATOF_PREFIX.matcher(text);
        if(!prefix.lookingAt())return 0.0;
        String number=prefix.group(1);
        boolean negative=number.charAt(0)=='-';
        String unsigned=(number.charAt(0)=='-'||number.charAt(0)=='+')?number.substring(1):number;
        String special=unsigned.toLowerCase(Locale.ROOT);
        if(special.startsWith("inf"))return negative?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
        if(special.equals("nan"))return Double.longBitsToDouble(
            Double.doubleToRawLongBits(Double.NaN)|(negative?Long.MIN_VALUE:0));
        // Java requires a binary exponent on hex floats; libc also accepts a hex prefix without one.
        if(unsigned.startsWith("0x")||unsigned.startsWith("0X")){
            if(unsigned.indexOf('p')<0&&unsigned.indexOf('P')<0)number+="p0";
        }
        try{return Double.parseDouble(number);}catch(NumberFormatException bad){throw new CloudNativePipe.ProtocolFailure();}
    }
    private static String ascii(String value,int bound)throws IOException{
        if(value.equals("-"))return "";
        byte[] bytes=CloudNativeConnection.unhex(value,bound);for(byte b:bytes)require(b>=32&&b<=126);
        return new String(bytes,StandardCharsets.US_ASCII);
    }
    private static void require(boolean ok)throws IOException{if(!ok)throw new CloudNativePipe.ProtocolFailure();}
}
