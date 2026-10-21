import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Synthetic/offline checks only. No main(), fetch(), TLS or post() invocation. */
public final class OtaCheckVersionProbeTest {
    static int checks;
    interface Action {void run() throws Exception;}
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    static void reject(Action action)throws Exception {
        checks++;try{action.run();throw new AssertionError("accepted invalid input");}
        catch(EcuMapMetadataProbe.Failure expected){}
    }
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static String json(Object o){return EcuMapMetadataProbe.Json.encode(o);}
    static Map<String,Object> row(String id){
        Map<String,Object> r=new LinkedHashMap<>();r.put("ecuId",id);
        r.put("ecuSoftCode","4B310011E001540100");r.put("ecuSoftVer","4.00.36");r.put("ecuHardVer","0.4.1");
        r.put("ecuConfigCode","FFFFFFFFFFFFFFFFFF");r.put("ecuConfigVer","6.55.35");return r;
    }
    static Map<String,Object> cached(){
        Map<String,Object> c=new LinkedHashMap<>();c.put("version","V1.7.0");c.put("uuid","old-cache-uuid");
        c.put("encType",OtaCheckVersionProbe.ENC);c.put("ecuInfo",Arrays.asList(row("ECU_ONE")));return c;
    }
    static EcuMapMetadataProbe.Inputs inputs(){return new EcuMapMetadataProbe.Inputs(null,null,"PRIVATE_TOKEN_12345","current-real-uuid","V1.7.0");}
    public static void main(String[] args)throws Exception {
        Map<String,Object> c=OtaCheckVersionProbe.cache(bytes(json(cached())),"V1.7.0");
        check(c.size()==3 && !c.containsKey("uuid"));
        check(((Map<?,?>)((List<?>)c.get("ecuInfo")).get(0)).get("ecuConfigVer").equals("6.55.35"));
        Map<String,Object> request=OtaCheckVersionProbe.object(OtaCheckVersionProbe.parse(OtaCheckVersionProbe.body(inputs(),c)));
        check(request.get("uuid").equals("current-real-uuid"));
        check(request.get("ecuInfo").equals(c.get("ecuInfo")));
        check(request.get("version").equals("V1.7.0") && request.get("encType").equals(OtaCheckVersionProbe.ENC));
        reject(()->OtaCheckVersionProbe.cache(bytes(json(cached())),"V2.0.0"));
        Map<String,Object> bad=cached();bad.put("encType","guess");reject(()->OtaCheckVersionProbe.cache(bytes(json(bad)),"V1.7.0"));
        Map<String,Object> empty=cached();empty.put("ecuInfo",new ArrayList<>());reject(()->OtaCheckVersionProbe.cache(bytes(json(empty)),"V1.7.0"));
        Map<String,Object> duplicate=cached();duplicate.put("ecuInfo",Arrays.asList(row("A"),row("A")));reject(()->OtaCheckVersionProbe.cache(bytes(json(duplicate)),"V1.7.0"));
        Map<String,Object> injected=row("A");injected.put("versionPath","https://unwanted.example/");reject(()->OtaCheckVersionProbe.tuple(injected,true));
        reject(()->OtaCheckVersionProbe.cache(bytes("{\"version\":\"V1.7.0\",\"version\":\"V1.7.0\"}"),"V1.7.0"));
        Map<String,Object> reply=new LinkedHashMap<>();reply.put("status","111");
        reply.put("cause","请求的ECU与策略中ECU版本升级详情不匹配!");
        Map<String,Object> projected=OtaCheckVersionProbe.plan(bytes(json(reply)),true);
        check(projected.get("causeKind").equals("ecu_strategy_mismatch"));
        check(((List<?>)projected.get("targetPackageInfo")).isEmpty());
        reply.put("status","100");reply.put("cause","VIN=PRIVATE_TOKEN_12345");
        Map<String,Object> pkg=row("A");pkg.put("secretKey","DO_NOT_OUTPUT_KEY");pkg.put("versionPath","https://download.example/private-token");
        reply.put("targetPackageInfo",Arrays.asList(pkg));reply.put("token","DO_NOT_OUTPUT_TOKEN");
        Map<String,Object> strategy=new LinkedHashMap<>();strategy.put("isForce","1");strategy.put("batchId","BATCH-1");strategy.put("sequence",Arrays.asList(row("A")));reply.put("strategy",strategy);
        projected=OtaCheckVersionProbe.plan(bytes(json(reply)),true);
        check(!json(projected).contains("DO_NOT_OUTPUT") && !json(projected).contains("https://"));
        check(projected.get("causeKind").equals("other_omitted"));
        check(((Map<?,?>)((List<?>)projected.get("targetPackageInfo")).get(0)).get("hasDownloadUrl").equals(true));
        check(((Map<?,?>)projected.get("strategy")).get("isForce").equals("1"));
        OtaCheckVersionProbe.privacy(projected,inputs());
        reject(()->OtaCheckVersionProbe.privacy(Arrays.asList("xPRIVATE_TOKEN_12345x"),inputs()));
        reject(()->OtaCheckVersionProbe.privacy(Arrays.asList("current-real-uuid"),inputs()));
        pkg.put("ecuId","PRIVATE_TOKEN_12345");
        Map<String,Object> poisoned=OtaCheckVersionProbe.plan(bytes(json(reply)),true);
        reject(()->OtaCheckVersionProbe.privacy(poisoned,inputs()));
        String wire=new String(OtaCheckVersionProbe.httpRequest(inputs(),OtaCheckVersionProbe.body(inputs(),c)),StandardCharsets.UTF_8);
        check(wire.startsWith("POST /ota/otaService/checkVersion HTTP/1.1\r\nHost: fota-tsp.denzacloud.com:6113\r\n"));
        check(wire.contains("Authorization: basicPRIVATE_TOKEN_12345\r\n"));
        check(wire.contains("\"ecuConfigVer\":\"6.55.35\""));
        ByteArrayOutputStream transfer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(transfer);
        out.writeInt(EcuMapMetadataProbe.MAGIC);out.writeInt(1);out.writeInt(1);
        byte[] missing=transfer.toByteArray();
        check(OtaCheckVersionProbe.decode(new ByteArrayInputStream(missing),1,EcuMapMetadataProbe.deadline(1000))==null);
        reject(()->OtaCheckVersionProbe.decode(new ByteArrayInputStream(missing),0,EcuMapMetadataProbe.deadline(1000)));
        // Retained request must remain byte-for-byte equivalent at all six ECU fields.
        if(args.length==1){
            byte[] retained=Files.readAllBytes(Paths.get(args[0]));Map<String,Object> original=OtaCheckVersionProbe.object(OtaCheckVersionProbe.parse(retained));
            Map<String,Object> audited=OtaCheckVersionProbe.cache(retained,(String)original.get("version"));
            check(original.get("ecuInfo").equals(audited.get("ecuInfo")));
            check(((List<?>)audited.get("ecuInfo")).size()==225);
        }
        System.out.println("PASS "+checks+" offline checks");
    }
}
