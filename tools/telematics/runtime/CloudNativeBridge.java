package dev.denza.tools.runtime;

import android.net.LocalSocket;
import android.os.Process;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** App-facing resident framing; its EOF never stops the detached owner. */
final class CloudNativeBridge {
    private final Path workdir,markerPath;
    private final String nonce;
    private final boolean allowStart;
    private LocalSocket socket;
    private long lastId;
    CloudNativeBridge(Path workdir,Path markerPath,String nonce){
        this(workdir,markerPath,nonce,true);
    }
    CloudNativeBridge(Path workdir,Path markerPath,String nonce,boolean allowStart){
        this.workdir=workdir;this.markerPath=markerPath;this.nonce=nonce;this.allowStart=allowStart;
    }
    void serve(InputStream input,PrintStream output)throws Exception{
        String frame="DENZA_SERVE_"+nonce;
        output.print(frame+":READY\n");output.flush();
        try{
            for(;;){
                String line=CloudLocalControl.readLine(input,4096);if(line==null)return;
                CloudControlRequest request=CloudControlRequest.parse(line);
                if(request.id<=lastId)throw new IOException("control_id_reused");lastId=request.id;
                JSONObject answer=handle(line,request);
                output.print(frame+":BEGIN\n");output.print(answer.toString());
                output.print("\n"+frame+":END ok\n");output.flush();
                if(output.checkError())throw new IOException("control_output_failed");
            }
        }finally{if(socket!=null)socket.close();}
    }
    private JSONObject handle(String raw,CloudControlRequest request)throws Exception{
        if(!allowStart && (request.op.equals("START")||request.op.equals("ATTACH")||
                request.op.equals("RENEW")))throw new IOException("control_only_active_forbidden");
        if(socket==null){
            try{socket=authenticate();}
            catch(Exception unavailable){
                if(!globalAbsent())throw unavailable;
                if(request.op.equals("STOP") && hasCleanupDebt()){
                    launchRecoveryGuardian();
                    try{socket=waitForGuardian();}
                    catch(Exception raced){
                        // A recovery-only guardian may finish and exit before
                        // ATTACH. Confirm both kernel absence and cleared debt.
                        if(globalAbsent()&&!hasCleanupDebt())return version(absent(request.id,request.op),request.protocol);
                        throw raced;
                    }
                }else if(!request.op.equals("START"))
                    return version(hasCleanupDebt()?absentDebt(request.id,request.op):absent(request.id,request.op),request.protocol);
                if(socket==null){
                CloudInstallMarker marker=CloudInstallMarker.read(markerPath);
                if(!marker.desired.equals("custom"))return version(absent(request.id,request.op),request.protocol);
                launchGuardian();socket=waitForGuardian();
                }
            }
        }
        try{
            CloudLocalControl.writeLine(socket.getOutputStream(),raw);
            String result=CloudLocalControl.readLine(socket.getInputStream(),32768);
            if(result==null)throw new IOException("guardian_eof");
            JSONObject answer=new JSONObject(result);
            if(answer.getInt("protocol")!=request.protocol||answer.getLong("id")!=request.id||
               !request.op.equals(answer.getString("op")))throw new IOException("guardian_response_mismatch");
            return answer;
        }catch(Exception failure){
            try{socket.close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}socket=null;throw failure;
        }
    }
    static JSONObject version(JSONObject response,int protocol)throws Exception{
        if(protocol==3)response.put("protocol",3).put("profile","awake-alpha-v1")
            .put("lease_until_uptime_ms",0).put("lease_active",false);
        return response;
    }
    private LocalSocket authenticate()throws Exception{
        LocalSocket channel=CloudLocalControl.connect();
        try{
            if(channel.getPeerCredentials().getUid()!=2000)throw new IOException("guardian_peer_uid");
            String challenge=CloudLocalControl.readLine(channel.getInputStream(),80);
            if(challenge==null||!challenge.matches("CHALLENGE [0-9a-f]{32}"))throw new IOException("guardian_challenge");
            CloudInstallMarker marker=CloudInstallMarker.read(markerPath);
            String mac=CloudLocalControl.mac(CloudLocalControl.secret(false),
                challenge.substring(10),marker.installId,marker.generation);
            CloudLocalControl.writeLine(channel.getOutputStream(),
                "HELLO "+marker.installId+" "+marker.generation+" "+mac);
            return channel;
        }catch(Exception failure){try{channel.close();}catch(IOException ignored){}throw failure;}
    }
    private LocalSocket waitForGuardian()throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        Exception failure=null;
        while(System.nanoTime()<deadline){
            try{return authenticate();}
            catch(Exception retry){failure=retry;Thread.sleep(50);}
        }
        throw new IOException("guardian_start_failed",failure);
    }
    private boolean globalAbsent()throws Exception{
        Files.createDirectories(CloudLocalControl.STATE);
        if(!Files.isDirectory(CloudLocalControl.STATE,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("owner_state_directory");
        Files.setPosixFilePermissions(CloudLocalControl.STATE,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        CloudRuntimeSupervisor.KernelOwnerLock lock=new CloudRuntimeSupervisor.KernelOwnerLock(
            CloudLocalControl.STATE.resolve("owner.lock"));
        CloudRuntimeSupervisor.KernelOwnerLock worker=new CloudRuntimeSupervisor.KernelOwnerLock(
            CloudLocalControl.STATE.resolve("worker.lock"));
        try{return lock.acquire() && worker.acquire();}
        finally{worker.close();lock.close();}
    }
    private static boolean hasCleanupDebt(){
        return Files.exists(CloudLocalControl.STATE.resolve("gate.pending"),LinkOption.NOFOLLOW_LINKS)||
            Files.exists(CloudLocalControl.STATE.resolve("property.pending"),LinkOption.NOFOLLOW_LINKS);
    }
    private JSONObject absentDebt(long id,String op)throws Exception{
        return absentDebt(id,op,Process.myPid(),"");
    }
    static JSONObject absentDebt(long id,String op,int pid,String runtimeId)throws Exception{
        return absent(id,op,pid,runtimeId,false).put("ok",false).put("stage","failed")
            .put("code","cleanup_uncertain").put("retryable",op.equals("PROBE"));
    }
    private void launchGuardian()throws Exception{
        if(!allowStart)throw new IOException("control_only_start_forbidden");
        Path jar=workdir.resolve("cloud-native-proxy.jar");
        if(!Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS))throw new IOException("guardian_jar_missing");
        ProcessBuilder builder=new ProcessBuilder("app_process","/system/bin",
            "dev.denza.tools.runtime.CloudNativeMain","guardian",workdir.toString(),markerPath.toString());
        builder.environment().put("CLASSPATH",jar.toString());
        builder.redirectInput(new File("/dev/null"));
        builder.redirectOutput(new File("/dev/null"));
        builder.redirectError(new File("/dev/null"));
        builder.start();
    }
    private void launchRecoveryGuardian()throws Exception{
        Path jar=controlJar();
        ProcessBuilder recovery=new ProcessBuilder("app_process","/system/bin",
            "dev.denza.tools.runtime.CloudNativeMain","recover",markerPath.toString(),controlRuntimeId(jar));
        recovery.environment().put("CLASSPATH",jar.toString());
        recovery.redirectInput(new File("/dev/null"));
        recovery.redirectOutput(new File("/dev/null"));
        recovery.redirectError(new File("/dev/null"));
        recovery.start();
    }
    private static Path controlJar()throws IOException{
        String raw=System.getenv("CLASSPATH");
        if(raw==null||raw.indexOf(':')>=0)throw new IOException("control_jar_path");
        Path jar=java.nio.file.Paths.get(raw);
        if(!jar.isAbsolute()||!Files.isRegularFile(jar,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("control_jar_missing");
        return jar;
    }
    private static String controlRuntimeId(Path jar)throws Exception{
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        try(InputStream stream=Files.newInputStream(jar)){
            byte[] block=new byte[8192];int count;
            while((count=stream.read(block))!=-1)sha.update(block,0,count);
        }
        byte[] digest=sha.digest();StringBuilder prefix=new StringBuilder();
        for(int i=0;i<6;i++)prefix.append(String.format(java.util.Locale.ROOT,"%02x",digest[i]&255));
        return prefix+"-000000000000";
    }
    private JSONObject absent(long id,String op)throws Exception{
        return absent(id,op,Process.myPid(),"",
            Files.exists(CloudLocalControl.STATE.resolve("registration.pending"),LinkOption.NOFOLLOW_LINKS));
    }
    static JSONObject absent(long id,String op,int pid,String runtimeId,boolean registrationUncertain)throws Exception{
        if(runtimeId==null||!(runtimeId.isEmpty()||runtimeId.matches("[0-9a-f]{12}-[0-9a-f]{12}")))
            throw new IllegalArgumentException("runtime_id_shape");
        JSONObject result=new JSONObject().put("id",id).put("op",op).put("protocol",2).put("ok",true)
            .put("pid",pid).put("session_live",false).put("stage","stopped")
            .put("code","owner_absent_confirmed").put("owner_id","")
            .put("runtime_id",runtimeId)
            .put("config_generation",0).put("retryable",false)
            .put("registration_uncertain",registrationUncertain)
            .put("capabilities",new JSONArray()).put("updated_elapsed_ms",0)
            .put("connected_elapsed_ms",0).put("last_rx_elapsed_ms",0).put("last_tx_elapsed_ms",0)
            .put("last_report_elapsed_ms",0).put("next_retry_elapsed_ms",0).put("attempts",0)
            .put("reports_sent",0).put("status_replies",0).put("commands_forwarded",0)
            .put("commands_completed",0).put("reconnects",0).put("callback_age_ms",-1)
            .put("native_events",new JSONArray());
        return result;
    }
}
