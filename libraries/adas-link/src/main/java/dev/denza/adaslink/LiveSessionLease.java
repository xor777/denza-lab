package dev.denza.adaslink;

import java.util.Map;
import java.util.UUID;

/** Explicit factory-sender arm bound to one consumer, boot and reader.
 * Legacy experiment variants are rejected rather than entering the native sender. */
public final class LiveSessionLease {
    public enum FrameMode { WGS84, NATIVE_GCJ_UNRESTRICTED_CANDIDATE, ALTERNATE_ROUTES }
    public static final String STOCK_PACKAGE="com.byd.launchermap";
    public static final long MAX_LIFETIME_MS=4L*60*60*1000, INPUT_TTL_MS=2500;
    public final String bootId, consumerSession, readerSession, armId;
    public final long issuedAt, expiresAt;
    public final String canonical;
    public final FrameMode frameMode;
    private LiveSessionLease(Map<String,Object> m) {
        bootId=(String)m.get("bootId"); consumerSession=(String)m.get("consumerSession");
        readerSession=(String)m.get("readerSession"); armId=(String)m.get("armId");
        issuedAt=integer(m,"issuedAtElapsedMs"); expiresAt=integer(m,"expiresAtElapsedMs");
        frameMode=m.containsKey("coordinateFrame")?FrameMode.valueOf((String)m.get("coordinateFrame")):FrameMode.WGS84;
        canonical=Json.write(m);
    }
    public static LiveSessionLease read(String text,String boot,String consumer,long now) {
        Map<String,Object> m=object(text);
        if(m.containsKey("variant") || m.size()!=(m.containsKey("coordinateFrame")?10:9)
                ||m.containsKey("coordinateFrame")&&!(m.get("coordinateFrame") instanceof String)
                || !"denza-adas-live-arm-v1".equals(m.get("schema"))
                || !uuid(boot) || !uuid(consumer) || !boot.equals(m.get("bootId")) || !consumer.equals(m.get("consumerSession"))
                || !uuid(m.get("readerSession")) || !uuid(m.get("armId"))
                || !STOCK_PACKAGE.equals(m.get("stockPackage")) || !Boolean.TRUE.equals(m.get("stockDisabled")))
            throw new IllegalArgumentException("arm identity");
        LiveSessionLease lease=new LiveSessionLease(m);
        if(lease.issuedAt<0 || lease.issuedAt>now || lease.expiresAt<=now || lease.expiresAt<=lease.issuedAt
                || lease.expiresAt-lease.issuedAt>MAX_LIFETIME_MS) throw new IllegalArgumentException("arm clock");
        return lease;
    }
    /** The shell reader observes all stock-map process names. Package disabled state is checked separately. */
    public long isolationStart(String text,long now) {
        Map<String,Object> m=object(text);
        if(!"denza-adas-stock-isolation-v1".equals(m.get("schema")) || !bootId.equals(m.get("bootId"))
                || !consumerSession.equals(m.get("consumerSession")) || !readerSession.equals(m.get("readerSession"))
                || !Boolean.TRUE.equals(m.get("stockAbsent"))) throw new IllegalArgumentException("stock isolation");
        long start=integer(m,"startedAtElapsedMs"),end=integer(m,"completedAtElapsedMs");
        if(start<0 || end<start || end>now || now-start>INPUT_TTL_MS) throw new IllegalArgumentException("stock isolation clock");
        return start;
    }
    public boolean same(LiveSessionLease other) { return other!=null && canonical.equals(other.canonical); }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(String text) {
        if(text==null || text.length()>16384) throw new IllegalArgumentException("file size");
        // Bound recursive parser depth before invoking it.
        int depth=0; boolean quoted=false,escape=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) { if(escape)escape=false; else if(c=='\\')escape=true; else if(c=='"')quoted=false; }
            else if(c=='"')quoted=true;
            else if(c=='{' || c=='[') { if(++depth>8)throw new IllegalArgumentException("file depth"); }
            else if(c=='}' || c==']') { if(--depth<0)throw new IllegalArgumentException("file nesting"); }
        }
        if(depth!=0 || quoted)throw new IllegalArgumentException("file syntax");
        Object parsed=Json.parse(text);
        if(!(parsed instanceof Map))throw new IllegalArgumentException("object required");
        return (Map<String,Object>)parsed;
    }
    static long integer(Map<String,Object> map,String key) {
        Object v=map.get(key);
        if(!(v instanceof Long) && !(v instanceof Integer))throw new IllegalArgumentException("integer "+key);
        return ((Number)v).longValue();
    }
    public static boolean uuid(Object value) {
        if(!(value instanceof String) || ((String)value).length()!=36)return false;
        try{return UUID.fromString((String)value).toString().equals(value);}catch(IllegalArgumentException e){return false;}
    }
}
