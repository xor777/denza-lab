package dev.denza.tools.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real protocol-3 guardian and bridge serializers for the APK parser contract. */
public final class CloudProtocol3FixtureTest {
    private static final String INSTALL="0123456789abcdef0123456789abcdef";
    private static final String RUNTIME="abc123abc123-abc123abc123";
    private static final String FIRST="11111111111111111111111111111111";
    private static final String SECOND="22222222222222222222222222222222";

    private static void marker(Path path,long generation,String desired)throws Exception{
        Files.createDirectories(path.getParent());
        Files.write(path,("{\"protocol\":2,\"install_id\":\""+INSTALL+"\",\"generation\":"+
            generation+",\"desired\":\""+desired+"\"}").getBytes(StandardCharsets.UTF_8));
    }
    private static CloudControlRequest request(long id,String op,JSONObject extra)throws Exception{
        JSONObject value=new JSONObject().put("protocol",3).put("id",id).put("op",op);
        if(extra!=null)for(java.util.Iterator<String> keys=extra.keys();keys.hasNext();){
            String key=keys.next();value.put(key,extra.get(key));
        }
        return CloudControlRequest.parse(value.toString());
    }
    private static JSONObject fields(Object... pairs)throws Exception{
        JSONObject value=new JSONObject();
        for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);
        return value;
    }
    private static void add(JSONArray all,String name,JSONObject response)throws Exception{
        all.put(new JSONObject().put("name",name).put("response",response));
    }
    private static final class Worker implements CloudGuardianState.Worker {
        boolean closed;
        public JSONObject request(String operation,CloudRuntimeSupervisor.Identity pair)throws Exception{
            boolean stop=operation.equals("STOP");
            return new JSONObject().put("ok",true).put("stage",stop?"stopped":"connected")
                .put("code",stop?"owner_stopped":"connected").put("session_live",!stop)
                .put("updated_elapsed_ms",1000).put("connected_elapsed_ms",1000)
                .put("last_rx_elapsed_ms",0).put("last_tx_elapsed_ms",0)
                .put("last_report_elapsed_ms",0).put("next_retry_elapsed_ms",0)
                .put("attempts",1).put("reports_sent",0).put("status_replies",0)
                .put("commands_forwarded",0).put("commands_completed",0)
                .put("reconnects",0).put("callback_age_ms",-1)
                .put("native_events",new JSONArray());
        }
        public boolean isAlive(){return !closed;}
        public void close(){closed=true;}
    }
    private static JSONArray fixtures()throws Exception{
        JSONArray all=new JSONArray();
        add(all,"probe_absent",CloudNativeBridge.version(
            CloudNativeBridge.absent(1,"PROBE",47,"",false),3));
        add(all,"probe_cleanup_debt",CloudNativeBridge.version(
            CloudNativeBridge.absentDebt(2,"PROBE",47,""),3));
        Path base=Files.createTempDirectory("cloud-v3-fixtures-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        marker(path,1,"custom");CloudInstallMarker install=CloudInstallMarker.read(path);
        AtomicLong elapsed=new AtomicLong(1000),uptime=new AtomicLong(900);
        AtomicInteger starts=new AtomicInteger(),proofs=new AtomicInteger();
        CloudGuardianState owner=new CloudGuardianState(path,install,RUNTIME,
            new CloudGateJournal(base.resolve("gate.pending")),
            new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){
                public int tcp(){return 0;}
                public String profile(){return "double_apn";}
            },()->{starts.incrementAndGet();return new Worker();},()->48,
            elapsed::get,uptime::get,pair->proofs.incrementAndGet(),false);
        String ownerId=owner.ownerId();
        add(all,"probe_present",owner.execute(request(3,"PROBE",null),install));
        add(all,"start",owner.execute(request(4,"START",fields(
            "profile","awake-alpha-v1","iccid","89860700000000000000",
            "imsi","460010000000000","service_instance",FIRST,"renew_seq",1)),install));
        add(all,"status",owner.execute(request(5,"STATUS",null),install));
        add(all,"attach_same",owner.execute(request(6,"ATTACH",fields(
            "owner_id",ownerId,"service_instance",FIRST)),install));
        add(all,"renew",owner.execute(request(7,"RENEW",fields(
            "owner_id",ownerId,"service_instance",FIRST,"renew_seq",2)),install));
        add(all,"attach_new",owner.execute(request(8,"ATTACH",fields(
            "owner_id",ownerId,"service_instance",SECOND)),install));
        add(all,"stale_token_stop",owner.execute(request(9,"STOP",fields(
            "owner_id",ownerId,"service_instance",FIRST)),install));
        add(all,"renew_new",owner.execute(request(10,"RENEW",fields(
            "owner_id",ownerId,"service_instance",SECOND,"renew_seq",1)),install));
        add(all,"active_empty_stop_rejected",owner.execute(request(11,"STOP",fields(
            "owner_id","","service_instance","")),install));
        add(all,"legacy_stop_rejected",owner.execute(
            CloudControlRequest.parse("{\"id\":12,\"op\":\"STOP\"}"),install));
        marker(path,2,"off");CloudInstallMarker off=CloudInstallMarker.read(path);
        add(all,"stop_off",owner.execute(request(13,"STOP",fields(
            "owner_id","","service_instance","")),off));
        Path lostPath=base.resolve("lost/Android/data/dev.denza.apps/files/cloud/install.json");
        marker(lostPath,1,"custom");CloudInstallMarker lostInstall=CloudInstallMarker.read(lostPath);
        CloudGuardianState lost=new CloudGuardianState(lostPath,lostInstall,RUNTIME,
            new CloudGateJournal(base.resolve("lost/gate.pending")),
            new CloudStopFence(base.resolve("lost/stop.fence")),
            new CloudRegistrationJournal(base.resolve("lost/registration.pending")),
            new CloudStockGate.BinderAccess(){
                public int tcp(){return 0;}
                public String profile(){return "double_apn";}
            },()->new Worker(),()->49,elapsed::get,uptime::get,
            pair->{throw new CloudSessionLoop.PermanentFailure(CloudRuntimeSupervisor.Code.POWER_LOST);},false);
        add(all,"power_lost",lost.execute(request(14,"START",fields(
            "profile","awake-alpha-v1","iccid","89860700000000000000",
            "imsi","460010000000000","service_instance",FIRST,"renew_seq",1)),lostInstall));
        if(starts.get()!=2||proofs.get()!=3||
           !all.getJSONObject(9).getJSONObject("response").getBoolean("ok")||
           all.getJSONObject(10).getJSONObject("response").getBoolean("ok")||
           all.getJSONObject(11).getJSONObject("response").getBoolean("ok")||
           !all.getJSONObject(12).getJSONObject("response").getBoolean("ok")||
           !all.getJSONObject(13).getJSONObject("response").getString("code").equals("power_lost"))
            throw new AssertionError("protocol3_fixture_state");
        return all;
    }
    public static void main(String[]args)throws Exception{
        JSONArray result=fixtures();
        if(args.length==1)Files.write(Path.of(args[0]),result.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS protocol3 response fixtures cases="+result.length());
    }
}
