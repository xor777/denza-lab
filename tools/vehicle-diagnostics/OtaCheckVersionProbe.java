import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;

/** Isolated GCY metadata request. No stock OTA, Android/BYD API or file writer.
 * Only the owning host may supply GO, after the temporary export has closed.
 * Shares strict parsing/TLS utilities with the separately pinned map reader.
 */
public final class OtaCheckVersionProbe {
    static final String URI = "/ota/otaService/checkVersion";
    static final String ENC = "gcy-1;gcy-2;gcy-3;gcy-4";
    static final String[] FILES = {
        "/data/ota_package/ECUVersion.json",
        "/data/ota_package/ota/result/strategy.json",
        "/data/ota_package/ota_ghy/result/strategy.json",
        "/data/ota_package/ota_productline/result/strategy.json"
    };
    static final List<String> ECU_FIELDS = Arrays.asList(
        "ecuId", "ecuSoftCode", "ecuSoftVer", "ecuHardVer", "ecuConfigCode", "ecuConfigVer");
    static final List<String> PACKAGE_LISTS = Arrays.asList(
        "sourcePackageInfo", "targetPackageInfo", "configSourceInfo", "configPackageInfo");

    static void require(boolean value, String code) throws EcuMapMetadataProbe.Failure {
        EcuMapMetadataProbe.require(value, code);
    }
    static Map<String,Object> object(Object value) throws Exception {
        return EcuMapMetadataProbe.object(value);
    }
    static Object parse(byte[] raw) throws Exception {
        return EcuMapMetadataProbe.Json.parse(EcuMapMetadataProbe.utf8(raw));
    }
    static String hash(byte[] raw) throws Exception {
        return EcuMapMetadataProbe.hex(MessageDigest.getInstance("SHA-256").digest(raw));
    }
    static String scalar(Object value, String pattern, int max) throws Exception {
        require(value instanceof String && ((String)value).length() <= max &&
            ((String)value).matches(pattern), "check_projection");
        return (String)value;
    }
    static Map<String,Object> tuple(Object value, boolean complete) throws Exception {
        Map<String,Object> row=object(value), out=new LinkedHashMap<>();
        if(complete)require(row.keySet().equals(new HashSet<>(ECU_FIELDS)), "check_cache_schema");
        for(String key:ECU_FIELDS) {
            Object v=row.get(key);
            if(v==null && !complete && !key.equals("ecuId"))continue;
            String pattern=key.equals("ecuId")?"[A-Za-z0-9_ -]{1,128}":"[A-Za-z0-9_.-]{0,128}";
            String text=scalar(v,pattern,128);
            if(key.equals("ecuId"))require(!text.trim().isEmpty(),"check_projection");
            out.put(key,text);
        }
        return out;
    }
    static List<Object> tuples(Object value, boolean complete) throws Exception {
        require(value instanceof List && ((List<?>)value).size()<=2048,"check_projection");
        List<Object> result=new ArrayList<>(); Set<Object> ids=new HashSet<>();
        for(Object item:(List<?>)value) {
            Map<String,Object> row=tuple(item,complete);
            require(ids.add(row.get("ecuId")),"check_duplicate_ecu");result.add(row);
        }
        return result;
    }
    static Map<String,Object> cache(byte[] raw, String currentVersion) throws Exception {
        Map<String,Object> doc=object(parse(raw));
        require(doc.keySet().equals(new HashSet<>(Arrays.asList("version","uuid","encType","ecuInfo"))),"check_cache_schema");
        require(currentVersion.equals(doc.get("version")),"check_cache_version");
        require(ENC.equals(doc.get("encType")),"check_cache_enctype");
        scalar(doc.get("uuid"),"[ -~]{0,256}",256);
        List<Object> rows=tuples(doc.get("ecuInfo"),true);
        require(!rows.isEmpty(),"check_cache_empty");
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("version",currentVersion);result.put("ecuInfo",rows);result.put("sha256",hash(raw));
        return result;
    }
    static byte[] body(EcuMapMetadataProbe.Inputs inputs, Map<String,Object> cached) throws Exception {
        require(inputs.version.equals(cached.get("version")),"check_cache_version");
        Map<String,Object> request=new LinkedHashMap<>();
        request.put("version",inputs.version);request.put("uuid",inputs.uuid);request.put("encType",ENC);
        request.put("ecuInfo",cached.get("ecuInfo"));
        return EcuMapMetadataProbe.Json.encode(request).getBytes(StandardCharsets.UTF_8);
    }
    static Map<String,Object> plan(byte[] raw, boolean response) throws Exception {
        Map<String,Object> doc=object(parse(raw)), out=new LinkedHashMap<>();
        out.put("sha256",hash(raw));
        if(response)out.put("status",scalar(doc.get("status"),"[0-9]{3}",3));
        Object cause=doc.get("cause");
        out.put("causeKind",cause==null?"absent":
            "请求的ECU与策略中ECU版本升级详情不匹配!".equals(cause)?"ecu_strategy_mismatch":"other_omitted");
        Map<String,Object> strategy=new LinkedHashMap<>();
        if(doc.get("strategy")!=null) {
            Map<String,Object> source=object(doc.get("strategy"));
            for(String field:Arrays.asList("batchId","strategyVer","oldStrategyVer","isForce","strategyType")) {
                Object v=source.get(field);
                if(v!=null)strategy.put(field,scalar(v,"[A-Za-z0-9_.-]{0,128}",128));
            }
            strategy.put("sequence",source.get("sequence")==null?new ArrayList<>():tuples(source.get("sequence"),false));
        }
        out.put("strategy",strategy);
        for(String name:PACKAGE_LISTS) {
            Object values=doc.get(name);List<Object> result=new ArrayList<>();
            if(values!=null) {
                require(values instanceof List && ((List<?>)values).size()<=2048,"check_projection");
                for(Object value:(List<?>)values) {
                    Map<String,Object> source=object(value),row=tuple(source,false);
                    Object sha=source.get("encryptSHA256");
                    if(sha!=null && !"".equals(sha))row.put("encryptSHA256",scalar(sha,"[0-9a-fA-F]{64}",64));
                    row.put("hasDownloadUrl",source.get("versionPath") instanceof String && !((String)source.get("versionPath")).isEmpty());
                    result.add(row);
                }
            }
            out.put(name,result);
        }
        return out;
    }
    static void privacy(Object value,EcuMapMetadataProbe.Inputs inputs) throws Exception {
        if(value instanceof String) {
            String s=(String)value;
            require(!EcuMapMetadataProbe.containsPrivate(s,inputs.token) &&
                    !EcuMapMetadataProbe.containsPrivate(s,inputs.uuid),"privacy_projection");
        } else if(value instanceof Map) {
            for(Object child:((Map<?,?>)value).values())privacy(child,inputs);
        } else if(value instanceof List) {
            for(Object child:(List<?>)value)privacy(child,inputs);
        }
    }
    static byte[] fetch(int index,long end) throws Exception {
        require(index>=0 && index<FILES.length,"input_index");
        Socket socket=new Socket();EcuMapMetadataProbe.active=socket;
        try {
            socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),46321),EcuMapMetadataProbe.remaining(end));
            socket.setSoTimeout(EcuMapMetadataProbe.remaining(end));
            String path="/data/local/tmp/logtool/../../../../"+FILES[index].substring(1);
            byte[] json=("{\"filePath\":"+EcuMapMetadataProbe.Json.quote(path)+"}").getBytes(StandardCharsets.UTF_8);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            out.writeInt(EcuMapMetadataProbe.MAGIC);out.writeInt(1);out.writeInt(json.length);out.write(json);out.flush();
            return decode(socket.getInputStream(),index,end);
        } finally {socket.close();EcuMapMetadataProbe.active=null;}
    }
    static byte[] decode(InputStream in,int index,long end) throws Exception {
        ByteBuffer h=ByteBuffer.wrap(EcuMapMetadataProbe.readExact(in,12,end));
        int magic=h.getInt(),status=h.getInt(),length=h.getInt();
        require(magic==EcuMapMetadataProbe.MAGIC && length>0 && length<=4096,"transfer_header");
        // Absence is allowed only for historical plans, never for request data.
        if(status==1 && index>0)return null;
        if(status==1)throw new EcuMapMetadataProbe.Failure("check_cache_missing");
        if(status==5)throw new EcuMapMetadataProbe.Failure("input_access_denied");
        require(status==0,"transfer_header");
        Map<String,Object> meta=object(parse(EcuMapMetadataProbe.readExact(in,length,end)));
        require(meta.keySet().equals(new HashSet<>(Arrays.asList("code","fileName","fileSize","md5"))) && Long.valueOf(0).equals(meta.get("code")),"transfer_metadata");
        require(FILES[index].substring(FILES[index].lastIndexOf('/')+1).equals(meta.get("fileName")),"transfer_name");
        Object size=meta.get("fileSize"),md5=meta.get("md5");
        require(size instanceof Long && (Long)size>0 && (Long)size<=2*1024*1024,"transfer_size");
        require(md5 instanceof String && ((String)md5).matches("[0-9a-fA-F]{32}"),"transfer_md5");
        byte[] bytes=EcuMapMetadataProbe.readExact(in,((Long)size).intValue(),end);
        require(EcuMapMetadataProbe.readOne(in,end)==-1,"transfer_trailing");
        require(EcuMapMetadataProbe.hex(MessageDigest.getInstance("MD5").digest(bytes)).equalsIgnoreCase((String)md5),"transfer_md5");
        return bytes;
    }
    static byte[] httpRequest(EcuMapMetadataProbe.Inputs inputs,byte[] payload) throws Exception {
        String headers="POST "+URI+" HTTP/1.1\r\nHost: "+EcuMapMetadataProbe.HOST+":6113\r\nUser-Agent: BYD\r\n"+
            "Content-Type: application/json;charset=utf-8\r\nAccept: application/json\r\nAccept-Encoding: identity\r\n"+
            "Connection: close\r\nAuthorization: basic"+inputs.token+"\r\nContent-Length: "+payload.length+"\r\n\r\n";
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(payload);return out.toByteArray();
    }
    static byte[] post(EcuMapMetadataProbe.Inputs inputs,SSLContext context,byte[] body,long end) throws Exception {
        Socket raw=new Socket();EcuMapMetadataProbe.active=raw;
        try {
            InetAddress address=InetAddress.getByName(EcuMapMetadataProbe.HOST);EcuMapMetadataProbe.remaining(end);
            raw.connect(new InetSocketAddress(address,6113),EcuMapMetadataProbe.remaining(end));
            raw.setSoTimeout(EcuMapMetadataProbe.remaining(end));
            SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(raw,EcuMapMetadataProbe.HOST,6113,true);EcuMapMetadataProbe.active=tls;
            SSLParameters params=tls.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");
            params.setServerNames(Collections.singletonList(new SNIHostName(EcuMapMetadataProbe.HOST)));tls.setSSLParameters(params);
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});tls.setSoTimeout(EcuMapMetadataProbe.remaining(end));tls.startHandshake();
            EcuMapMetadataProbe.remaining(end);byte[] request=httpRequest(inputs,body);
            try {tls.getOutputStream().write(request);tls.getOutputStream().flush();}
            finally {Arrays.fill(request,(byte)0);}
            return EcuMapMetadataProbe.readHttp(tls.getInputStream(),end);
        } finally {EcuMapMetadataProbe.closeActive();EcuMapMetadataProbe.active=null;raw.close();}
    }
    public static void main(String[] args) {
        PrintStream protocol=System.out;
        try {
            EcuMapMetadataProbe.silenceRuntimeOutput();EcuMapMetadataProbe.watchdog(55000);EcuMapMetadataProbe.androidShellGuard();
            require(args.length==2 && "--nonce".equals(args[0]) && args[1].matches("[0-9a-f]{32}"),"arguments");
            long end=EcuMapMetadataProbe.deadline(18000);byte[][] privateFiles=new byte[4][];
            EcuMapMetadataProbe.Inputs inputs;
            try {
                for(int i=0;i<4;i++){EcuMapMetadataProbe.stage=EcuMapMetadataProbe.INPUT_STAGES[i];privateFiles[i]=EcuMapMetadataProbe.fetch(i,end);}
                EcuMapMetadataProbe.stage="input_validate";inputs=EcuMapMetadataProbe.prepare(privateFiles);
            } finally {for(byte[] bytes:privateFiles)if(bytes!=null)Arrays.fill(bytes,(byte)0);}
            EcuMapMetadataProbe.stage="input_cache";byte[] raw=fetch(0,end);Map<String,Object> cached;
            try {cached=cache(raw,inputs.version);}finally {Arrays.fill(raw,(byte)0);}
            Map<String,Object> snapshots=new LinkedHashMap<>();
            for(int i=1;i<FILES.length;i++) {
                EcuMapMetadataProbe.stage="input_plans";raw=fetch(i,end);
                try {snapshots.put(FILES[i],raw==null?null:plan(raw,false));}
                finally {if(raw!=null)Arrays.fill(raw,(byte)0);}
            }
            EcuMapMetadataProbe.stage="trust";SSLContext context=EcuMapMetadataProbe.tlsContext(inputs);
            EcuMapMetadataProbe.remaining(end);
            Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("cache",cached);snapshot.put("plans",snapshots);
            privacy(snapshot,inputs);
            Map<String,Object> ready=new LinkedHashMap<>();ready.put("state","INPUTS_READY");ready.put("nonce",args[1]);ready.put("snapshot",snapshot);
            protocol.println(EcuMapMetadataProbe.Json.encode(ready));protocol.flush();
            EcuMapMetadataProbe.stage="go_wait";EcuMapMetadataProbe.awaitGo(System.in,args[1]);
            EcuMapMetadataProbe.watchdog(25000);end=EcuMapMetadataProbe.deadline(25000);
            EcuMapMetadataProbe.stage="https";byte[] body=body(inputs,cached);
            try {raw=post(inputs,context,body,end);}finally {Arrays.fill(body,(byte)0);}
            EcuMapMetadataProbe.stage="projection";Map<String,Object> result;
            try {result=plan(raw,true);}finally {Arrays.fill(raw,(byte)0);}
            privacy(result,inputs);EcuMapMetadataProbe.remaining(end);
            Map<String,Object> out=new LinkedHashMap<>();out.put("state","RESULT");out.put("response",result);
            protocol.println(EcuMapMetadataProbe.Json.encode(out));protocol.flush();EcuMapMetadataProbe.finished=true;
        } catch(Throwable error) {
            String code=error instanceof EcuMapMetadataProbe.Failure?((EcuMapMetadataProbe.Failure)error).code:"operation_failed";
            protocol.println("{\"state\":\"ERROR\",\"stage\":"+EcuMapMetadataProbe.Json.quote(EcuMapMetadataProbe.stage)+",\"code\":"+EcuMapMetadataProbe.Json.quote(code)+"}");
            protocol.flush();EcuMapMetadataProbe.finished=true;System.exit(1);
        } finally {EcuMapMetadataProbe.closeActive();}
    }
}
