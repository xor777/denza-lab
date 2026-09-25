package dev.denza.tools.runtime;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Guardian-owned Java session process. Identity is written only to its private stdin. */
final class CloudInternalWorker implements CloudGuardianState.Worker {
    private final Process process;
    private final OutputStream input;
    private final ArrayBlockingQueue<String> lines=new ArrayBlockingQueue<>(16);
    private final Thread reader;
    private final String marker;
    private long nextId;
    private boolean closed,reaped;

    CloudInternalWorker(Path workdir) throws Exception {
        byte[] nonce=new byte[16];new SecureRandom().nextBytes(nonce);
        StringBuilder suffix=new StringBuilder();for(byte b:nonce)suffix.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        marker="DENZA_SERVE_"+suffix;
        ProcessBuilder builder=new ProcessBuilder("app_process","/system/bin",
            "dev.denza.tools.runtime.CloudNativeMain","worker",workdir.toString(),suffix.toString());
        builder.environment().put("CLASSPATH",workdir.resolve("cloud-native-proxy.jar").toString());
        builder.redirectError(new File("/dev/null"));
        process=builder.start();input=process.getOutputStream();
        reader=new Thread(this::readOutput,"cloud-java-worker-output");reader.setDaemon(true);
        try {
            reader.start();
            if(!(marker+":READY").equals(take(System.nanoTime()+TimeUnit.SECONDS.toNanos(8))))
                throw new IOException("java_worker_startup");
            JSONObject probe=request("PROBE",null);
            if(!probe.getBoolean("ok") || !"owner_absent_confirmed".equals(probe.getString("code")))
                throw new IOException("java_worker_probe");
        }catch(Exception|Error failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private void readOutput(){
        try(InputStream stream=process.getInputStream()){
            ByteArrayOutputStream line=new ByteArrayOutputStream();
            for(int c;(c=stream.read())!=-1;){
                if(c=='\n'){
                    if(!lines.offer(line.toString("UTF-8")))break;
                    line.reset();
                }else{
                    if(c<32||c>126||line.size()>=32768)break;
                    line.write(c);
                }
            }
        }catch(Exception ignored){}
        finally{lines.offer("");}
    }
    private String take(long deadline)throws Exception{
        for(;;){
            if(closed)throw new IOException("java_worker_closed");
            long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IOException("java_worker_timeout");
            String line=lines.poll(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)),TimeUnit.NANOSECONDS);
            if(line!=null){if(line.isEmpty())throw new IOException("java_worker_exited");return line;}
            if(!process.isAlive()&&lines.isEmpty())throw new IOException("java_worker_exited");
        }
    }
    @Override public JSONObject request(String op,CloudRuntimeSupervisor.Identity pair)throws Exception{
        return request(op,pair,0);
    }
    @Override public synchronized JSONObject request(String op,CloudRuntimeSupervisor.Identity pair,long ceiling)throws Exception{
        if(closed)throw new IOException("java_worker_closed");
        boolean leased=op.equals("START")||op.equals("PERMIT")||op.equals("STATUS");
        if(leased && ceiling<=0 || !leased && ceiling!=0)throw new IOException("java_worker_lease_shape");
        long id=++nextId;JSONObject json=new JSONObject().put("id",id).put("op",op);
        if(pair!=null)json.put("iccid",pair.iccid).put("imsi",pair.imsi);
        if(leased)json.put("lease_until_uptime_ms",ceiling);
        byte[] request=(json.toString()+"\n").getBytes(StandardCharsets.UTF_8);
        if(request.length>4097)throw new IOException("java_worker_request_bound");
        input.write(request);input.flush();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(op.equals("STOP")?5:10);
        if(!(marker+":BEGIN").equals(take(deadline)))throw new IOException("java_worker_begin");
        String answer=take(deadline);
        if(!(marker+":END ok").equals(take(deadline)))throw new IOException("java_worker_end");
        JSONObject parsed=new JSONObject(answer);
        if(parsed.getLong("id")!=id||!op.equals(parsed.getString("op"))||parsed.getInt("protocol")!=1)
            throw new IOException("java_worker_response");
        return parsed;
    }
    @Override public boolean isAlive(){return !closed&&process.isAlive();}
    @Override public void abort(){process.destroyForcibly();}
    @Override public synchronized void close()throws Exception{
        if(reaped)return;
        closed=true;
        if(process.isAlive())process.destroy();
        if(process.isAlive()&&!process.waitFor(2,TimeUnit.SECONDS)){
            process.destroyForcibly();
            if(!process.waitFor(2,TimeUnit.SECONDS))throw new IOException("java_worker_cleanup");
        }
        input.close();reader.join(500);
        if(reader.isAlive())throw new IOException("java_worker_reader_cleanup");
        reaped=true;
    }
}
