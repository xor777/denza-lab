package dev.denza.hud.probe;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Separate bounded packet/history rings and the drive's change journal. Recording only: no receiver
 * or transport exists here. Each received packet is one row, once: a payload equal to the same
 * receiver's previous one on that topic is a compact repeat row without its bytes.
 */
final class DriveEvidenceStreams {
    /**
     * Ring sizes for a two-hour drive (at least {@code generations - 1} full files kept), from the
     * 2026-10-24 journals at the stock cadence: packets ~0.35 MiB a minute once repeats are compact
     * and service 13 is recorded by one receiver; history ~1.3 KiB a second; the live journal ~0.31
     * and the dry run ~0.2 MiB a minute. See DriveEvidenceStreamsTest for the arithmetic.
     */
    static final long RING_BYTES = 8L << 20, DRIVE_MS = 2L * 60 * 60 * 1000;
    static final int PACKET_GENERATIONS = 7, HISTORY_GENERATIONS = 3, LIVE_GENERATIONS = 6, DRY_RUN_GENERATIONS = 5;
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
    private final AdasStateJournal journal;
    final QueueBudget rawQueue=new QueueBudget(32),criticalQueue=new QueueBudget(32);
    private final AtomicLong historyErrors=new AtomicLong(),packetErrors=new AtomicLong(),journalErrors=new AtomicLong(),repeats=new AtomicLong();
    private final Map<String,byte[]> lastPayload=new HashMap<>();
    DriveEvidenceStreams(DriveEvidenceLog history,DriveEvidenceLog packets){this(history,packets,null);}
    DriveEvidenceStreams(DriveEvidenceLog history,DriveEvidenceLog packets,AdasStateJournal journal){this.history=history;this.packets=packets;this.journal=journal;}

    /** The row for one received packet: in full, or as a repeat of the receiver's previous payload on its topic. */
    synchronized Map<String,Object> passive(String receiver,String receiverSession,String processSession,
            long topic,long stamp,long received,byte[] bytes,long capturedAt) {
        Map<String,Object> full=packet(receiver,receiverSession,processSession,topic,stamp,received,bytes,capturedAt);
        if(critical(full)||bytes==null)return full; // Answers and availability always keep their bytes.
        String key=receiver+":"+receiverSession+":"+topic;
        byte[] previous=lastPayload.put(key,bytes.clone());
        if(previous==null||!Arrays.equals(previous,bytes))return full;
        repeats.incrementAndGet();
        // Only what differs from the full row it repeats (same receiver, session and topic): its receipt.
        Map<String,Object> row=new LinkedHashMap<>();row.put("kind","adas_passive_repeat");
        row.put("receiver",receiver);row.put("topic",Long.toHexString(topic));row.put("timestamp",stamp);
        row.put("receivedAtElapsedMs",received);
        return row;
    }

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
    static boolean packet(Map<String,Object> row){return "adas_passive_packet".equals(row.get("kind"))||"adas_passive_repeat".equals(row.get("kind"));}
    static boolean critical(Map<String,Object> row){
        if("adas_passive_repeat".equals(row.get("kind")))return false;
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
        if(AdasStateJournal.journal(row)) {
            if(journal==null)return;
            try{journal.append(line);}catch(IOException error){journalErrors.incrementAndGet();throw error;}
            return;
        }
        // A failing raw ring must not prevent a rare receipt from reaching long history.
        if(critical(row))try{history.append(line);}catch(IOException error){historyErrors.incrementAndGet();failure=error;}
        if(packet(row))try{packets.append(line);}catch(IOException error){packetErrors.incrementAndGet();if(failure==null)failure=error;}
        if(failure!=null)throw failure;
    }
    Map<String,Object> stats(){
        Map<String,Object> m=new LinkedHashMap<>();m.put("rawQueued",rawQueue.queued.get());m.put("criticalQueued",criticalQueue.queued.get());
        m.put("rawDropped",rawQueue.dropped.get());m.put("criticalDropped",criticalQueue.dropped.get());
        m.put("rawPostFailures",rawQueue.postFailures.get());m.put("criticalPostFailures",criticalQueue.postFailures.get());
        m.put("historyWriteErrors",historyErrors.get());m.put("packetWriteErrors",packetErrors.get());
        m.put("repeatRows",repeats.get());m.put("journalWriteErrors",journalErrors.get());
        m.put("journalBytes",journal==null?null:journal.written());m.put("journalDropped",journal==null?null:journal.dropped());return m;
    }
}
