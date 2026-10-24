package dev.denza.hud.probe;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Regression for the real trip's raw-packet flood and the separate live receiver. */
public final class DriveEvidenceStreamsTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static String read(File file)throws Exception{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
    private static Map<String,Object> packet(long topic,byte[] bytes){
        return DriveEvidenceStreams.packet("live","arm-session","process-session",topic,17,1234,bytes,5678);
    }
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("drive-evidence-streams");
        try {
            File history=directory.resolve("progress.jsonl").toFile(),raw=directory.resolve("adas-packets.jsonl").toFile();
            DriveEvidenceStreams streams=new DriveEvidenceStreams(new DriveEvidenceLog(history,8192),new DriveEvidenceLog(raw,2048));
            Map<String,Object> guidance=new LinkedHashMap<>();guidance.put("kind","guidance");guidance.put("routeId","retained-route");
            streams.append(guidance);String before=read(history);
            byte[] pilot=Base64.getDecoder().decode("CggIi969jQoYAg==");
            Map<String,Object> row=packet(AdasWire.PILOT,pilot);
            for(int i=0;i<1000;i++)streams.append(row);
            check(read(history).equals(before),"raw status flood cannot rotate or evict guidance");
            check(raw.length()<=2048&&new File(raw+".1").length()<=2048,"raw ring stays bounded");
            check(!new File(raw+".2").exists(),"only one raw predecessor");
            check(row.get("receiver").equals("live")&&row.get("receiverSession").equals("arm-session"),"receiver ownership retained");
            check(row.get("sourceSessionId").equals("process-session")&&row.get("receivedAtElapsedMs").equals(1234L),"process and original callback time retained");
            check(java.util.Arrays.equals(Base64.getDecoder().decode((String)row.get("base64")),pilot),"raw actual pilot bytes retained");
            Map<String,Object> match=packet(AdasWire.PATH_MATCH,Base64.getDecoder().decode("ChEIrb6ZvAkiCQiMvpeVBCDaBg=="));
            streams.append(match);
            check(read(history).contains("4002c002c8001")&&read(raw).contains("4002c002c8001"),"PathMatch appears in both bounded streams");
            streams.append(packet(AdasWire.SELECTED,new byte[]{10,0}));
            check(read(history).contains("4002c002c8002"),"selection retained in long history");
            Map<String,Object> absent=packet(0x0001000D000D0001L,null),empty=packet(0x0001000D000D0001L,new byte[0]);
            check(Boolean.TRUE.equals(absent.get("payloadNull"))&&absent.get("base64")==null&&absent.get("bytes")==null,"null availability stays null");
            check(Boolean.FALSE.equals(empty.get("payloadNull"))&&empty.get("base64").equals("")&&empty.get("bytes").equals(0),"empty payload is distinct");
            streams.append(absent);check(read(history).contains("1000d000d0001"),"availability kept as critical history");
            check(!DriveEvidenceStreams.critical(packet(0x0004002C00018001L,pilot)),"different instance is not a critical path result");
            Map<String,Object> bounded=packet(AdasWire.PATH_MATCH,new byte[65537]);
            check(Boolean.TRUE.equals(bounded.get("truncated"))&&Base64.getDecoder().decode((String)bounded.get("base64")).length==65536,"payload cap is explicit");
            for(int i=0;i<32;i++)check(streams.rawQueue.acquire(),"bounded raw reservation");
            check(!streams.rawQueue.acquire()&&streams.criticalQueue.acquire(),"raw saturation leaves critical queue capacity");
            for(int i=0;i<32;i++)streams.rawQueue.done();streams.criticalQueue.postFailed();
            Map<String,Object> stats=streams.stats();
            check(((Number)stats.get("rawDropped")).longValue()==1&&((Number)stats.get("criticalPostFailures")).longValue()==1,"drops and post failures counted");
            check(((Number)stats.get("rawQueued")).longValue()==0&&((Number)stats.get("criticalQueued")).longValue()==0,"queue accounting recovers");
            File blocked=directory.resolve("blocked-raw").toFile();blocked.mkdir();
            DriveEvidenceStreams failing=new DriveEvidenceStreams(new DriveEvidenceLog(history,8192),new DriveEvidenceLog(blocked,8192));
            int old=read(history).length();
            try {failing.append(match);throw new AssertionError("raw write should fail");}catch(IOException expected){}
            check(read(history).length()>old&&((Number)failing.stats().get("packetWriteErrors")).longValue()==1,"raw write failure does not lose critical history");
            System.out.println("DriveEvidenceStreamsTest: "+checks+" checks passed");
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(directory)){files.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException ignored){}});}
        }
    }
}
