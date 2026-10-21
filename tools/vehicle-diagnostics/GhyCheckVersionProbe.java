import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;

/** One GHY catalogue request, then at most one cached-version metadata request.
 * The separately pinned, previously exercised OncarTls supplies bounded factory
 * TLS signing. No stock OTA classes, token refresh, download or installation.
 */
public final class GhyCheckVersionProbe {
    static final String HOST="api-ota-cn.denzacloud.com";
    static final String PREFIX="/client-service/fota/api/v1/service/";
    static final String[] FILES={
        "/data/user/0/com.byd.otaupdate2/shared_prefs/share_data_prefs.xml",
        "/collect2/SourceVehicleVersion", "/data/ota_package/ECUVersion.json"};
    static final int[] LIMITS={1024*1024,256,2*1024*1024};
    interface Network { byte[] post(String operation,byte[] body) throws Exception; }
    static void need(boolean ok,String code) throws Exception {EcuMapMetadataProbe.require(ok,code);}
    static Map<String,Object> object(Object value) throws Exception {return EcuMapMetadataProbe.object(value);}
    static byte[] encode(Object value) {return EcuMapMetadataProbe.Json.encode(value).getBytes(StandardCharsets.UTF_8);}
    static Object parse(byte[] value) throws Exception {return OtaCheckVersionProbe.parse(value);}
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;
    }
    static EcuMapMetadataProbe.Inputs inputs(byte[] prefs,byte[] version) throws Exception {
        Map<String,String> values=EcuMapMetadataProbe.preferences(prefs);
        String token=values.get("user_token"),uuid=values.getOrDefault("upgradeUuid","");
        need(token!=null && !"null".equalsIgnoreCase(token) && token.matches("[!-~]{1,4096}"),"token_invalid");
        need(uuid.isEmpty() || (uuid.matches("[ -~]{1,256}")&&!uuid.trim().isEmpty()),"uuid_invalid");
        // VehicleOTAUtil.getCurrentVehicleVersion uses this exact constant for
        // an absent/zero-length file. null only comes from the validated status-1
        // exporter response for FILES[1]; access/transport errors still abort.
        String v=version==null||version.length==0?"v1.0.0":EcuMapMetadataProbe.utf8(version).trim();
        need(v.matches("[vV][A-Za-z0-9_.-]{1,63}"),"version_invalid");
        return new EcuMapMetadataProbe.Inputs(null,null,token,uuid,v);
    }
    static String versionSource(byte[] version) {
        return version==null?"stock_default_exporter_missing":version.length==0?"stock_default_empty":"file";
    }
    static Map<String,Object> cached(byte[] raw) throws Exception {
        Map<String,Object> doc=object(parse(raw));
        String version=OtaCheckVersionProbe.scalar(doc.get("version"),"V[A-Za-z0-9_.-]{1,63}",64);
        return OtaCheckVersionProbe.cache(raw,version);
    }
    static Map<String,Object> catalogue(byte[] raw) throws Exception {
        Map<String,Object> doc=object(parse(raw));
        String status=OtaCheckVersionProbe.scalar(doc.get("status"),"[0-9]{3}",3);
        List<String> ids=new ArrayList<>();
        if("100".equals(status)) {
            Object rows=doc.get("ecuList");
            need(rows instanceof List && !((List<?>)rows).isEmpty() && ((List<?>)rows).size()<=2048,"ecu_list");
            Set<String> unique=new HashSet<>();
            for(Object item:(List<?>)rows) {
                String id=OtaCheckVersionProbe.scalar(object(item).get("ecuId"),"[A-Za-z0-9_ -]{1,128}",128);
                need(!id.trim().isEmpty() && unique.add(id),"check_duplicate_ecu");ids.add(id);
            }
        }
        return map("status",status,"sha256",OtaCheckVersionProbe.hash(raw),"ecuIds",ids);
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> query(EcuMapMetadataProbe.Inputs in,Map<String,Object> cache,Network net) throws Exception {
        byte[] body=encode(map("version",in.version,"uuid",in.uuid)),raw=null;
        Map<String,Object> cat;
        try {raw=net.post("getEcuList",body);cat=catalogue(raw);}
        finally {Arrays.fill(body,(byte)0);if(raw!=null)Arrays.fill(raw,(byte)0);}
        Map<String,Object> out=map("getEcuList",cat,"missingCacheIds",new ArrayList<>(),"checkVersion",null,"checkFailure",null);
        if(!"100".equals(cat.get("status"))) {out.put("outcome","catalogue_rejected");return out;}
        Map<Object,Object> byId=new HashMap<>();
        for(Object row:(List<?>)cache.get("ecuInfo"))byId.put(object(row).get("ecuId"),row);
        List<Object> selected=new ArrayList<>();List<String> missing=(List<String>)out.get("missingCacheIds");
        for(String id:(List<String>)cat.get("ecuIds")) {
            if(!byId.containsKey(id))missing.add(id);else selected.add(byId.get(id));
        }
        // Never invent a version, omit an unknown requested ECU, or start diagnostics.
        if(!missing.isEmpty()) {out.put("outcome","cache_incomplete");return out;}
        body=encode(map("version",in.version,"uuid",in.uuid,"ecuInfo",selected));raw=null;
        try {raw=net.post("checkVersion",body);out.put("checkVersion",OtaCheckVersionProbe.plan(raw,true));}
        catch(Exception e) {
            // Preserve the first response even if the second handshake/response fails.
            // The request may already have reached the server: no retry is permitted.
            out.put("outcome","check_failed");
            out.put("checkFailure",e instanceof EcuMapMetadataProbe.Failure?((EcuMapMetadataProbe.Failure)e).code:"operation_failed");
            return out;
        }
        finally {Arrays.fill(body,(byte)0);if(raw!=null)Arrays.fill(raw,(byte)0);}
        out.put("outcome","check_returned");return out;
    }
    static byte[] fetch(int index,long end) throws Exception {
        need(index>=0&&index<FILES.length,"input_index");
        Socket s=new Socket();EcuMapMetadataProbe.active=s;
        try {
            s.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),46321),EcuMapMetadataProbe.remaining(end));
            s.setSoTimeout(EcuMapMetadataProbe.remaining(end));
            byte[] request=encode(map("filePath","/data/local/tmp/logtool/../../../../"+FILES[index].substring(1)));
            DataOutputStream output=new DataOutputStream(s.getOutputStream());
            output.writeInt(EcuMapMetadataProbe.MAGIC);output.writeInt(1);output.writeInt(request.length);output.write(request);output.flush();
            return decode(s.getInputStream(),index,end);
        } finally {s.close();EcuMapMetadataProbe.active=null;}
    }
    static byte[] decode(InputStream input,int index,long end) throws Exception {
            need(index>=0&&index<FILES.length,"input_index");
            ByteBuffer h=ByteBuffer.wrap(EcuMapMetadataProbe.readExact(input,12,end));
            int magic=h.getInt(),status=h.getInt(),length=h.getInt();
            need(magic==EcuMapMetadataProbe.MAGIC&&length>0&&length<=4096,"transfer_header");
            if(status==1) {
                if(index!=1)throw new EcuMapMetadataProbe.Failure("missing_input"+index);
                Map<String,Object> missing=object(parse(EcuMapMetadataProbe.readExact(input,length,end)));
                need(missing.keySet().equals(new HashSet<>(Arrays.asList("code","message")))&&
                    Long.valueOf(1).equals(missing.get("code"))&&
                    ("File not found: /data/local/tmp/logtool/../../../../"+FILES[1].substring(1)).equals(missing.get("message")),"transfer_metadata");
                need(EcuMapMetadataProbe.readOne(input,end)==-1,"transfer_trailing");
                return null;
            }
            if(status==5)throw new EcuMapMetadataProbe.Failure("input_access_denied");
            need(status==0,"transfer_header");Map<String,Object> meta=object(parse(EcuMapMetadataProbe.readExact(input,length,end)));
            need(meta.keySet().equals(new HashSet<>(Arrays.asList("code","fileName","fileSize","md5")))&&Long.valueOf(0).equals(meta.get("code")),"transfer_metadata");
            need(FILES[index].substring(FILES[index].lastIndexOf('/')+1).equals(meta.get("fileName")),"transfer_name");
            Object size=meta.get("fileSize"),md5=meta.get("md5");
            need(size instanceof Long&&(Long)size>=(index==1?0:1)&&(Long)size<=LIMITS[index],"transfer_size");
            need(md5 instanceof String&&((String)md5).matches("[0-9a-fA-F]{32}"),"transfer_md5");
            byte[] bytes=EcuMapMetadataProbe.readExact(input,((Long)size).intValue(),end);
            need(EcuMapMetadataProbe.readOne(input,end)==-1,"transfer_trailing");
            need(EcuMapMetadataProbe.hex(MessageDigest.getInstance("MD5").digest(bytes)).equalsIgnoreCase((String)md5),"transfer_md5");return bytes;
    }
    static Object factoryCall(String method,Class<?>[] types,Object... args) throws Exception {
        // Exact retained adapter hash is pinned by the build/runner. Its main is never called.
        java.lang.reflect.Method m=Class.forName("dev.denza.tools.OncarTls").getDeclaredMethod(method,types);
        m.setAccessible(true);return m.invoke(null,args);
    }
    static final class FactoryNetwork implements Network {
        final EcuMapMetadataProbe.Inputs inputs;final long end;int requests;
        FactoryNetwork(EcuMapMetadataProbe.Inputs in,long end){inputs=in;this.end=end;}
        public byte[] post(String operation,byte[] payload) throws Exception {
            need((requests==0&&operation.equals("getEcuList"))||(requests==1&&operation.equals("checkVersion")),"application_status");
            EcuMapMetadataProbe.remaining(end);requests++;
            SSLSocket tls=(SSLSocket)factoryCall("connect",new Class<?>[]{String.class,int.class},HOST,443);
            EcuMapMetadataProbe.active=tls;
            try {
                tls.setSoTimeout(EcuMapMetadataProbe.remaining(end));
                String h="POST "+PREFIX+operation+" HTTP/1.1\r\nHost: "+HOST+"\r\nUser-Agent: BYD\r\n"+
                    "Content-Type: application/json;charset=utf-8\r\nAccept: application/json\r\nAccept-Encoding: identity\r\n"+
                    "Connection: close\r\nAuthorization: basic"+inputs.token+"\r\nContent-Length: "+payload.length+"\r\n\r\n";
                byte[] header=h.getBytes(StandardCharsets.US_ASCII);
                try {tls.getOutputStream().write(header);tls.getOutputStream().write(payload);tls.getOutputStream().flush();}
                finally {Arrays.fill(header,(byte)0);}
                return EcuMapMetadataProbe.readHttp(tls.getInputStream(),end);
            } finally {
                // A second request must make its own verified handshake and single signature.
                tls.getSession().invalidate();tls.close();EcuMapMetadataProbe.active=null;
            }
        }
    }
    public static void main(String[] args) {
        PrintStream protocol=System.out;
        try {
            EcuMapMetadataProbe.silenceRuntimeOutput();EcuMapMetadataProbe.watchdog(75000);EcuMapMetadataProbe.androidShellGuard();
            need(args.length==2&&"--nonce".equals(args[0])&&args[1].matches("[0-9a-f]{32}"),"arguments");
            long end=EcuMapMetadataProbe.deadline(18000);byte[] prefs=null,version=null,raw=null;
            EcuMapMetadataProbe.Inputs in;Map<String,Object> cache;String versionSource;
            try {
                EcuMapMetadataProbe.stage="input_prefs";prefs=fetch(0,end);
                EcuMapMetadataProbe.stage="input_version";version=fetch(1,end);
                EcuMapMetadataProbe.stage="input_validate";in=inputs(prefs,version);versionSource=versionSource(version);
                EcuMapMetadataProbe.stage="input_cache";raw=fetch(2,end);cache=cached(raw);
            } finally {for(byte[] b:new byte[][]{prefs,version,raw})if(b!=null)Arrays.fill(b,(byte)0);}
            Map<String,Object> snap=map("cache",cache,"ghyVersion",in.version,"ghyVersionSource",versionSource);
            OtaCheckVersionProbe.privacy(snap,in);
            protocol.println(new String(encode(map("state","INPUTS_READY","nonce",args[1],"snapshot",snap)),StandardCharsets.UTF_8));protocol.flush();
            EcuMapMetadataProbe.stage="go_wait";EcuMapMetadataProbe.awaitGo(System.in,args[1]);
            // No chip access or OEM connection before the host confirms export closure.
            end=EcuMapMetadataProbe.deadline(45000);EcuMapMetadataProbe.stage="trust";
            factoryCall("factoryIdentity",new Class<?>[]{});
            EcuMapMetadataProbe.stage="https";Map<String,Object> response=query(in,cache,new FactoryNetwork(in,end));
            EcuMapMetadataProbe.stage="projection";OtaCheckVersionProbe.privacy(response,in);
            protocol.println(new String(encode(map("state","RESULT","response",response)),StandardCharsets.UTF_8));protocol.flush();
            EcuMapMetadataProbe.finished=true;
        } catch(Throwable e) {
            String code=e instanceof EcuMapMetadataProbe.Failure?((EcuMapMetadataProbe.Failure)e).code:"operation_failed";
            protocol.println("{\"state\":\"ERROR\",\"stage\":"+EcuMapMetadataProbe.Json.quote(EcuMapMetadataProbe.stage)+",\"code\":"+EcuMapMetadataProbe.Json.quote(code)+"}");protocol.flush();
            EcuMapMetadataProbe.finished=true;System.exit(1);
        } finally {EcuMapMetadataProbe.closeActive();}
    }
}
