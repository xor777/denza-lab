package dev.denza.hud.probe;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Separate bounded packet/history rings. Recording only: no receiver or transport exists here. */
final class DriveEvidenceStreams {
    static final class QueueBudget {
        private final int limit;
        private final AtomicInteger queued=new AtomicInteger();
        private final AtomicLong dropped=new AtomicLong(),postFailures=new AtomicLong();
        QueueBudget(int limit){if(limit<1)throw new IllegalArgumentException("queue limit");this.limit=limit;}
        boolean acquire(){
            if(queued.incrementAndGet()<=limit)return true;
            queued.decrementAndGet();dropped.incrementAndGet();return false;
        }
        void done(){queued.decrementAndGet();}
        void postFailed(){postFailures.incrementAndGet();done();}
    }
    private final DriveEvidenceLog history,packets;
    final QueueBudget rawQueue=new QueueBudget(32),criticalQueue=new QueueBudget(32);
    private final AtomicLong historyErrors=new AtomicLong(),packetErrors=new AtomicLong();
    DriveEvidenceStreams(DriveEvidenceLog history,DriveEvidenceLog packets){this.history=history;this.packets=packets;}

    static Map<String,Object> packet(String receiver,String receiverSession,String processSession,
            long topic,long stamp,long received,byte[] bytes,long capturedAt) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("kind","adas_passive_packet");
        row.put("receiver",receiver);row.put("receiverSession",receiverSession);row.put("sourceSessionId",processSession);
        row.put("capturedAtMs",capturedAt);row.put("topic",Long.toHexString(topic));row.put("timestamp",stamp);
        row.put("receivedAtElapsedMs",received);row.put("receivedUptimeMs",received); // Historical alias, same exact input.
        int count=bytes==null?0:Math.min(bytes.length,AdasWire.MAX_PAYLOAD);
        row.put("payloadNull",bytes==null);row.put("bytes",bytes==null?null:bytes.length);
        row.put("base64",bytes==null?null:Base64.getEncoder().encodeToString(
                count==bytes.length?bytes:Arrays.copyOf(bytes,count)));
        row.put("truncated",bytes!=null&&count<bytes.length);
        return row;
    }
    static boolean packet(Map<String,Object> row){return "adas_passive_packet".equals(row.get("kind"));}
    static boolean critical(Map<String,Object> row){
        if(!packet(row))return true;
        Object raw=row.get("topic");if(!(raw instanceof String))return false;
        long topic;try{topic=Long.parseLong((String)raw,16);}catch(NumberFormatException invalid){return false;}
        return topic==AdasWire.SELECTED || topic==AdasWire.PATH_MATCH
                ||topic==0x0001000D000D0001L||topic==0x0001000D000D0002L
                ||topic==0x0001002C002C0001L||topic==0x0001002C002C0002L;
    }
    QueueBudget budget(Map<String,Object> row){return critical(row)?criticalQueue:rawQueue;}

    void append(Map<String,Object> row)throws IOException {
        String line=RouteSnapshot.json(row);IOException failure=null;
        // A failing raw ring must not prevent a rare receipt from reaching long history.
        if(critical(row))try{history.append(line);}catch(IOException error){historyErrors.incrementAndGet();failure=error;}
        if(packet(row))try{packets.append(line);}catch(IOException error){packetErrors.incrementAndGet();if(failure==null)failure=error;}
        if(failure!=null)throw failure;
    }
    Map<String,Object> stats(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("rawQueued",rawQueue.queued.get());m.put("criticalQueued",criticalQueue.queued.get());
        m.put("rawDropped",rawQueue.dropped.get());m.put("criticalDropped",criticalQueue.dropped.get());
        m.put("rawPostFailures",rawQueue.postFailures.get());m.put("criticalPostFailures",criticalQueue.postFailures.get());
        m.put("historyWriteErrors",historyErrors.get());m.put("packetWriteErrors",packetErrors.get());return m;
    }
}
