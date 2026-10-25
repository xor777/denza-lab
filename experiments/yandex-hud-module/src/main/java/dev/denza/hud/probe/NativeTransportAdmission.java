package dev.denza.hud.probe;

/** Admission only. Factory packet bytes and lifecycle are never rewritten here. */
final class NativeTransportAdmission {
    private volatile boolean closed;
    private final java.util.function.LongPredicate prerequisites;
    NativeTransportAdmission(java.util.function.LongPredicate prerequisites){this.prerequisites=prerequisites;}
    boolean allow(long topic){return !closed&&prerequisites.test(topic);}
    void close(){closed=true;}
    static boolean terminal(long topic){return topic==dev.denza.adaslink.Topics.NAVIGATION_STATUS
            ||topic==dev.denza.adaslink.Topics.SD_TUNNEL||topic==dev.denza.adaslink.Topics.SD_TRAFFIC_INCIDENT;}
}
