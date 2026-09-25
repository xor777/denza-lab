package dev.denza.tools.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** Responses from the real v2 serializer, used by the app parser cross-check. */
public final class CloudProtocol2FixtureTest {
    private static final String ID="0123456789abcdef0123456789abcdef";
    private static final String RUNTIME="abc123abc123-abc123abc123";
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    private static void marker(Path path,long generation,String desired)throws Exception{
        Files.createDirectories(path.getParent());
        Files.write(path,("{\"protocol\":2,\"install_id\":\""+ID+"\",\"generation\":"+
            generation+",\"desired\":\""+desired+"\"}").getBytes(StandardCharsets.UTF_8));
    }
    private static void item(JSONArray all,String label,JSONObject response)throws Exception{
        all.put(new JSONObject().put("name",label).put("response",response));
    }
    private static JSONArray fixtures()throws Exception{
        JSONArray all=new JSONArray();
        item(all,"probe_absent",CloudNativeBridge.absent(1,"PROBE",47,"",false));
        item(all,"probe_cleanup_debt",CloudNativeBridge.absentDebt(9,"PROBE",47,""));
        Path base=Files.createTempDirectory("cloud-v2-fixtures-");
        Path path=base.resolve("Android/data/dev.denza.apps/files/cloud/install.json");
        marker(path,1,"custom");CloudInstallMarker install=CloudInstallMarker.read(path);
        AtomicLong clock=new AtomicLong(1000);
        CloudGateJournal gate=new CloudGateJournal(base.resolve("gate.pending"));
        final boolean[] permanent={false};
        CloudGuardianState.Worker worker=new CloudGuardianState.Worker(){
            boolean closed;
            public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
                String stage=op.equals("STOP")?"stopped":permanent[0]&&op.equals("STATUS")?"failed":"connected";
                String code=stage.equals("failed")?"unsupported_firmware":stage.equals("stopped")?"owner_stopped":"connected";
                return new JSONObject().put("protocol",1).put("ok",true).put("stage",stage)
                    .put("code",code).put("session_live",stage.equals("connected"))
                    .put("updated_elapsed_ms",1000).put("connected_elapsed_ms",1000)
                    .put("last_rx_elapsed_ms",0).put("last_tx_elapsed_ms",0)
                    .put("last_report_elapsed_ms",0).put("next_retry_elapsed_ms",0)
                    .put("attempts",1).put("reports_sent",0).put("status_replies",0)
                    .put("commands_forwarded",0).put("commands_completed",0)
                    .put("reconnects",0).put("callback_age_ms",-1).put("native_events",new JSONArray());
            }
            public boolean isAlive(){return !closed;}
            public void close(){closed=true;}
        };
        CloudGuardianState owner=new CloudGuardianState(path,install,RUNTIME,gate,
            new CloudStopFence(base.resolve("stop.fence")),
            new CloudRegistrationJournal(base.resolve("registration.pending")),
            new CloudStockGate.BinderAccess(){public int tcp(){return 0;}public String profile(){return "triple_apn";}},
            ()->worker,()->48,clock::get);
        item(all,"probe_present",owner.execute(2,"PROBE",install,null));
        item(all,"attach",owner.execute(3,"ATTACH",install,null));
        CloudRuntimeSupervisor.Identity pair=new CloudRuntimeSupervisor.Identity(
            "89860700000000000000","460010000000000");
        item(all,"start",owner.execute(4,"START",install,pair));
        item(all,"status",owner.execute(5,"STATUS",install,null));
        permanent[0]=true;owner.tick();
        item(all,"permanent_unsupported_firmware",owner.execute(6,"STATUS",install,null));
        marker(path,2,"custom");
        item(all,"transient_config_changed",owner.execute(7,"ATTACH",CloudInstallMarker.read(path),null));
        marker(path,3,"off");
        item(all,"stop",owner.execute(8,"STOP",CloudInstallMarker.read(path),null));
        check(all.length()==9);
        check(all.getJSONObject(8).getJSONObject("response").getString("stage").equals("stopped"));
        return all;
    }
    public static void main(String[]args)throws Exception{
        JSONArray result=fixtures();
        if(args.length==1)Files.write(Path.of(args[0]),result.toString(2).getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS protocol2 response fixtures cases="+result.length());
    }
}
