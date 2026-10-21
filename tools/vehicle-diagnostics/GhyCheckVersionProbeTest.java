import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Offline synthetic transport: never calls main/fetch/FactoryNetwork/factoryCall. */
public final class GhyCheckVersionProbeTest {
    static int checks;
    interface Action {void run() throws Exception;}
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    static void reject(Action action)throws Exception {
        checks++;try{action.run();throw new AssertionError("invalid accepted");}
        catch(EcuMapMetadataProbe.Failure expected){}
    }
    static byte[] bytes(String v){return v.getBytes(StandardCharsets.UTF_8);}
    static Map<String,Object> map(Object... v){return GhyCheckVersionProbe.map(v);}
    static byte[] frame(int status,Map<String,Object> metadata,byte[] body)throws Exception {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);
        byte[] json=GhyCheckVersionProbe.encode(metadata);
        out.writeInt(EcuMapMetadataProbe.MAGIC);out.writeInt(status);out.writeInt(json.length);out.write(json);out.write(body);
        return buffer.toByteArray();
    }
    static byte[] decode(byte[] frame,int index)throws Exception {
        return GhyCheckVersionProbe.decode(new ByteArrayInputStream(frame),index,EcuMapMetadataProbe.deadline(1000));
    }
    static Map<String,Object> row(String id){return map("ecuId",id,"ecuSoftCode","4B310011E001540100",
        "ecuSoftVer","4.00.36","ecuHardVer","0.4.1","ecuConfigCode","FFFFFFFFFFFFFFFFFF","ecuConfigVer","6.55.35");}
    static EcuMapMetadataProbe.Inputs inputs()throws Exception {
        return GhyCheckVersionProbe.inputs(bytes("<map><string name=\"user_token\">PRIVATE_TOKEN_TEST</string><string name=\"upgradeUuid\">PRIVATE_UUID_TEST</string></map>"),bytes("v1.2.3\n"));
    }
    static Map<String,Object> cache()throws Exception {
        return GhyCheckVersionProbe.cached(GhyCheckVersionProbe.encode(map("version","V1.7.0","uuid","CACHE_UUID",
            "encType",OtaCheckVersionProbe.ENC,"ecuInfo",Arrays.asList(row("A"),row("B")))));
    }
    static class Fake implements GhyCheckVersionProbe.Network {
        final List<String> operations=new ArrayList<>();final List<Object> bodies=new ArrayList<>();
        String status="100";List<Object> candidates=Arrays.asList(map("ecuId","B"),map("ecuId","A"));
        boolean failSecond,malformedSecond;
        public byte[] post(String op,byte[] body)throws Exception {
            operations.add(op);bodies.add(GhyCheckVersionProbe.parse(body));
            if(operations.size()==1)return GhyCheckVersionProbe.encode(map("status",status,"ecuList",candidates,"token","DO_NOT_EXPOSE"));
            if(failSecond)throw new java.io.IOException("PRIVATE_TOKEN_TEST");
            if(malformedSecond)return bytes("not json");
            return GhyCheckVersionProbe.encode(map("status","100","strategy",map("isForce","1","batchId","BATCH"),
                "targetPackageInfo",Arrays.asList(map("ecuId","A","versionPath","https://private.example/", "secretKey","DO_NOT_EXPOSE"))));
        }
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        EcuMapMetadataProbe.Inputs in=inputs();check(in.version.equals("v1.2.3"));check(in.privateKey==null&&in.certificate==null);
        byte[] prefs=bytes("<map><string name=\"user_token\">x</string></map>");
        check(GhyCheckVersionProbe.inputs(prefs,null).version.equals("v1.0.0"));
        check(GhyCheckVersionProbe.inputs(prefs,new byte[0]).version.equals("v1.0.0"));
        reject(()->GhyCheckVersionProbe.inputs(prefs,bytes(" \n")));
        reject(()->GhyCheckVersionProbe.inputs(prefs,bytes("garbage")));
        check(GhyCheckVersionProbe.versionSource(null).equals("stock_default_exporter_missing"));
        check(GhyCheckVersionProbe.versionSource(new byte[0]).equals("stock_default_empty"));
        check(GhyCheckVersionProbe.versionSource(bytes("v1.2.3")).equals("file"));
        // Captured first-session failure: input_version / missing_input1. The
        // exact error-frame schema below comes from pinned FileTransferHandler.
        Map<String,Object> missing=map("code",1,"message","File not found: /data/local/tmp/logtool/../../../../collect2/SourceVehicleVersion");
        check(decode(frame(1,missing,new byte[0]),1)==null);
        reject(()->decode(frame(1,missing,new byte[0]),0));
        reject(()->decode(frame(1,missing,new byte[0]),2));
        reject(()->decode(frame(5,map("code",5,"message","Access denied"),new byte[0]),1));
        reject(()->decode(frame(4,map("code",4,"message","IO error"),new byte[0]),1));
        reject(()->decode(frame(1,map("code",1,"message","File not found: /another/path"),new byte[0]),1));
        reject(()->decode(frame(1,map("code",5,"message",missing.get("message")),new byte[0]),1));
        reject(()->decode(frame(1,missing,new byte[]{1}),1));
        Map<String,Object> emptyFile=map("code",0,"fileName","SourceVehicleVersion","fileSize",0,"md5","d41d8cd98f00b204e9800998ecf8427e");
        check(decode(frame(0,emptyFile,new byte[0]),1).length==0);
        reject(()->decode(frame(0,emptyFile,new byte[]{1}),1));
        Map<String,Object> emptyCache=new LinkedHashMap<>(emptyFile);emptyCache.put("fileName","ECUVersion.json");
        reject(()->decode(frame(0,emptyCache,new byte[0]),2));
        reject(()->GhyCheckVersionProbe.inputs(bytes("<map><string name=\"user_token\">x\nHeader: bad</string></map>"),bytes("v1.0")));
        Fake f=new Fake();Map<String,Object> result=GhyCheckVersionProbe.query(in,cache(),f);
        check(f.operations.equals(Arrays.asList("getEcuList","checkVersion")));
        Map<String,Object> get=GhyCheckVersionProbe.object(f.bodies.get(0));
        check(get.keySet().equals(new HashSet<>(Arrays.asList("version","uuid"))));
        Map<String,Object> request=GhyCheckVersionProbe.object(f.bodies.get(1));
        check(request.keySet().equals(new HashSet<>(Arrays.asList("version","uuid","ecuInfo"))));
        check(request.get("version").equals("v1.2.3")&&request.get("uuid").equals(in.uuid));
        check(request.get("ecuInfo").equals(Arrays.asList(row("B"),row("A"))));
        check(result.get("outcome").equals("check_returned"));
        String output=EcuMapMetadataProbe.Json.encode(result);
        check(!output.contains("DO_NOT_EXPOSE")&&!output.contains("https://")&&!output.contains(in.uuid));
        check(output.contains("\"isForce\":\"1\""));OtaCheckVersionProbe.privacy(result,in);
        f=new Fake();f.candidates=Arrays.asList(map("ecuId","UNKNOWN"));result=GhyCheckVersionProbe.query(in,cache(),f);
        check(f.operations.size()==1&&result.get("outcome").equals("cache_incomplete"));
        check(result.get("missingCacheIds").equals(Arrays.asList("UNKNOWN"))&&result.get("checkVersion")==null);
        f=new Fake();f.status="104";result=GhyCheckVersionProbe.query(in,cache(),f);
        check(f.operations.size()==1&&result.get("outcome").equals("catalogue_rejected"));
        f=new Fake();f.failSecond=true;result=GhyCheckVersionProbe.query(in,cache(),f);
        check(f.operations.size()==2&&result.get("outcome").equals("check_failed"));
        check(result.get("getEcuList")!=null&&result.get("checkFailure").equals("operation_failed"));
        check(!EcuMapMetadataProbe.Json.encode(result).contains(in.token));
        f=new Fake();f.malformedSecond=true;result=GhyCheckVersionProbe.query(in,cache(),f);
        check(f.operations.size()==2&&result.get("outcome").equals("check_failed"));
        reject(()->GhyCheckVersionProbe.catalogue(GhyCheckVersionProbe.encode(map("status","100","ecuList",Arrays.asList(map("ecuId","A"),map("ecuId","A"))))));
        reject(()->GhyCheckVersionProbe.catalogue(GhyCheckVersionProbe.encode(map("status","100","ecuList",Arrays.asList(map("ecuId","https://bad"))))));
        reject(()->GhyCheckVersionProbe.catalogue(GhyCheckVersionProbe.encode(map("status","100","ecuList",new ArrayList<>()))));
        f=new Fake();f.candidates=Arrays.asList(map("ecuId","A"));result=GhyCheckVersionProbe.query(in,cache(),f);
        check(GhyCheckVersionProbe.object(f.bodies.get(1)).get("ecuInfo").equals(Arrays.asList(row("A"))));
        f=new Fake();GhyCheckVersionProbe.query(GhyCheckVersionProbe.inputs(prefs,null),cache(),f);
        check(GhyCheckVersionProbe.object(f.bodies.get(0)).get("version").equals("v1.0.0")&&
              GhyCheckVersionProbe.object(f.bodies.get(1)).get("version").equals("v1.0.0"));
        if(args.length==1) {
            Map<String,Object> real=GhyCheckVersionProbe.cached(Files.readAllBytes(Paths.get(args[0])));
            List<Object> rows=(List<Object>)real.get("ecuInfo");List<Object> candidates=new ArrayList<>();
            for(Object r:rows)candidates.add(map("ecuId",GhyCheckVersionProbe.object(r).get("ecuId")));
            f=new Fake();f.candidates=candidates;GhyCheckVersionProbe.query(in,real,f);
            check(rows.size()==225&&GhyCheckVersionProbe.object(f.bodies.get(1)).get("ecuInfo").equals(rows));
        }
        System.out.println("PASS "+checks+" offline GHY checks");
    }
}
