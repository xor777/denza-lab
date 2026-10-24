package dev.denza.adaslink;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-packet live admission. Every method, prerequisite callback and ACK consumption belongs to
 * the same worker. Pure Java; constructing this class neither binds nor enables a sender.
 * Readiness must describe the real transport epoch, exact required answer subscriptions, current
 * vehicle gates and the separately established stock-publisher exclusion; assumptions are false.
 */
public final class LiveEgressGuard implements StockFeed.GuardedLivePublisher, AutoCloseable {
    public static final int REJECTED = -1200;
    private static final int LEASE_REJECTED = -1201;
    /** Owned Android transport returns this before TX6 when its final admission check declines. */
    private static final int PRE_TRANSACT_REJECTED = -203;
    public static final int MAX_CHUNKS = 16, MAX_PACKET_BYTES = 600 * 1024, MAX_ROUTE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IDS = 4096;
    private static final long MAX_EXACT_PATH_ID = (1L << 53) - 1; // curSDRouteID is a double on the wire.
    private static final long STOP_GRACE_MS = 1000;

    /** Called anew immediately before every transport transaction; UNKNOWN must be represented as false/null. */
    public interface Prerequisites { Readiness current(long nowMs); }
    public static final class Readiness {
        public final long epoch, observedAtMs, expiresAtMs;
        public final boolean armed, stockDisabled, gatesKnown, dnpService, dnpSwitch, noSleep, serverReady, answersReady;
        public Readiness(long epoch, long observedAtMs, long expiresAtMs, boolean armed, boolean stockDisabled,
                boolean gatesKnown, boolean dnpService, boolean dnpSwitch, boolean noSleep,
                boolean serverReady, boolean answersReady) {
            this.epoch=epoch; this.observedAtMs=observedAtMs; this.expiresAtMs=expiresAtMs;
            this.armed=armed; this.stockDisabled=stockDisabled; this.gatesKnown=gatesKnown;
            this.dnpService=dnpService; this.dnpSwitch=dnpSwitch; this.noSleep=noSleep;
            this.serverReady=serverReady; this.answersReady=answersReady;
        }
        boolean allows(long now) {
            return epoch > 0 && observedAtMs >= 0 && observedAtMs <= now && expiresAtMs > now
                    && armed && stockDisabled && gatesKnown && dnpService && dnpSwitch && noSleep && serverReady && answersReady;
        }
    }

    private final Publisher transport, audit;
    private final Scheduler clock;
    private final Prerequisites prerequisites;
    private final RealPathAck acknowledgements;
    private final boolean terminalFailures;
    private final Set<Long> joined = new HashSet<>(), usedIds = new HashSet<>();
    private final Object stopToken = new Object();
    private long epoch = -1, ackToken = -1, currentPath = -1, routeGeneration;
    private Readiness lastReadiness;
    private long firingTopic = -1, firingPath, firingGeneration, firingEpoch;
    private int finalLeaseRejection;
    private long supersededReadiness;
    private long fineRelative = -1, fineAbsolute = -1, finePath = -1;
    private FixFreshness.Sample fine;
    private boolean fineClass, closed, stopping, stopPending, hadRoute;
    private long stopDeadline, stopSequence;
    private Group group;
    private static final class Group {
        final long path, generation;
        final int count;
        final StringBuilder json = new StringBuilder();
        long envelopeTime = -1;
        int sent, bytes;
        Group(long path, long generation, int count) { this.path=path; this.generation=generation; this.count=count; }
    }

    /** The sink updates only local feed selection state; no request/control method is supplied. */
    public LiveEgressGuard(Publisher transport, Publisher audit, Scheduler clock, Prerequisites prerequisites,
            RealPathAck.Sink selected) {
        this(transport,audit,clock,prerequisites,selected,2500,10000);
    }
    /** Production arm policy: observed prerequisite/transport/audit failure ends this owned session. */
    public LiveEgressGuard(Publisher transport, Publisher audit, Scheduler clock, Prerequisites prerequisites,
            RealPathAck.Sink selected, boolean terminalFailures) {
        this(transport,audit,clock,prerequisites,selected,2500,10000,terminalFailures);
    }
    public LiveEgressGuard(Publisher transport, Publisher audit, Scheduler clock, Prerequisites prerequisites,
            RealPathAck.Sink selected, long maxCallbackDelayMs, long maxRequestAgeMs) {
        this(transport,audit,clock,prerequisites,selected,maxCallbackDelayMs,maxRequestAgeMs,false);
    }
    private LiveEgressGuard(Publisher transport, Publisher audit, Scheduler clock, Prerequisites prerequisites,
            RealPathAck.Sink selected, long maxCallbackDelayMs, long maxRequestAgeMs, boolean terminalFailures) {
        if (transport==null || audit==null || clock==null || prerequisites==null || selected==null
                || transport.mode()!=BridgeStatus.Mode.LIVE || audit.mode()==BridgeStatus.Mode.LIVE)
            throw new IllegalArgumentException("Live transport and local-only audit required");
        this.transport=transport; this.audit=audit; this.clock=clock; this.prerequisites=prerequisites;
        this.terminalFailures=terminalFailures;
        acknowledgements=new RealPathAck(()->currentPath,selected,maxCallbackDelayMs,maxRequestAgeMs);
    }
    @Override public BridgeStatus.Mode mode() { return BridgeStatus.Mode.LIVE; }

    private boolean readiness(long now) {
        if (closed) return false;
        long queriedEpoch=epoch, queriedPath=currentPath, queriedGeneration=routeGeneration;
        Readiness r;
        try { r=prerequisites.current(now); }
        catch (RuntimeException failure) { r=null; }
        if (!owns(queriedEpoch,queriedPath,queriedGeneration)) { supersededReadiness++; return false; }
        long checkedAt=clock.now();
        if (checkedAt<now || r==null || !r.allows(checkedAt)) { failure("prerequisites lost"); return false; }
        if (terminalFailures && epoch>0 && epoch!=r.epoch) { failure("transport epoch changed"); return false; }
        if (epoch!=r.epoch || ackToken<0) {
            boolean changed=epoch!=r.epoch;
            invalidate("transport epoch changed",true);
            if (closed) return false;
            if (changed) joined.clear();
            epoch=r.epoch; ackToken=acknowledgements.sessionReady(checkedAt);
        }
        lastReadiness=r;
        return true;
    }
    private boolean owns(long ownerEpoch,long ownerPath,long ownerGeneration) {
        return !closed && epoch==ownerEpoch && currentPath==ownerPath && routeGeneration==ownerGeneration;
    }
    private void failure(String reason) {
        if (closed) return;
        if (terminalFailures) { close(); noteDecision("terminal",0,REJECTED,reason); }
        else invalidate(reason,true);
    }
    private void invalidate(String reason, boolean forgetPath) {
        group=null; acknowledgements.invalidateSession(); ackToken=-1; lastReadiness=null;
        if (forgetPath) { currentPath=-1; routeGeneration++; }
        // Do not discard the original FINE anchor or high-water identity on transient loss.
        fineClass=false;
        noteDecision("invalidated",0,REJECTED,reason);
    }
    private boolean servicesReady() {
        for (long service:Topics.SERVICES) if (!joined.contains(service)) return false;
        return true;
    }
    private static boolean serviceAllowed(long topic) {
        for (long service:Topics.SERVICES) if (service==topic) return true;
        return false;
    }
    @Override public int startService(long service) {
        if (!serviceAllowed(service) || !readiness(clock.now()) || stopping) return reject(service,"service not admitted");
        long offeredEpoch=epoch;
        long offeredPath=currentPath, offeredGeneration=routeGeneration;
        int result;
        try { result=transport.startService(service); }
        catch (RuntimeException failure) { result=REJECTED; }
        if (!owns(offeredEpoch,offeredPath,offeredGeneration)) return REJECTED;
        if (!readiness(clock.now()) || epoch!=offeredEpoch) return reject(service,"offer completed on old session");
        if (result==0 || result==13) joined.add(service);
        else { joined.remove(service); failure("service offer failed"); }
        noteDecision("service",service,result,"own offer/join");
        return result;
    }

    @Override public void routeChanged(long pathId) {
        if (closed) return;
        // Cancelling guidance ends the old route, not the separately armed session.
        // A new route may supersede the pending one-shot stop before its queued send.
        if (stopping) finishNavigationStop();
        long now=clock.now();
        if (!readiness(now)) return;
        if (!servicesReady() || pathId<=0 || pathId>MAX_EXACT_PATH_ID || usedIds.contains(pathId)) {
            invalidate("route not admitted",true); return;
        }
        routeGeneration++; group=null; acknowledgements.routeChanged(); currentPath=pathId;
        // A held fix from a previous path must not acquire this path's ownership.
        noteDecision("route",pathId,0,"new model route");
    }
    @Override public void beginRoute(long pathId, int chunkCount) {
        if (closed || stopping || !readiness(clock.now())) return;
        if (!servicesReady() || pathId!=currentPath || pathId<=0
                || chunkCount<1 || chunkCount>MAX_CHUNKS || usedIds.contains(pathId) || usedIds.size()>=MAX_IDS) {
            if (group!=null && group.path==pathId && group.generation==routeGeneration) group=null;
            reject(Topics.ROUTE,"old/invalid/reused route group"); return;
        }
        usedIds.add(pathId); acknowledgements.routeChanged();
        group=new Group(pathId,routeGeneration,chunkCount);
    }

    /** Must be called with the immutable source sample, not a receipt-time replacement. */
    public void fineFix(FixFreshness.Sample sample, String locationClass) { fineFix(currentPath,sample,locationClass); }
    public void fineFix(long pathId, FixFreshness.Sample sample, String locationClass) {
        fineClass="FINE".equals(locationClass);
        if (!fineClass) return; // Prediction timestamps must not advance the FINE stream's high-water.
        if (closed || sample==null || sample.relativeMs<0 || sample.absoluteMs<0 || sample.relativeMs<fineRelative) {
            fineClass=false; return;
        }
        if (sample.relativeMs==fineRelative) {
            if (sample.absoluteMs!=fineAbsolute || pathId!=finePath) fineClass=false;
            return; // Same identity never refreshes its time or route ownership.
        }
        fineRelative=sample.relativeMs; fineAbsolute=sample.absoluteMs; finePath=pathId; fine=sample;
    }
    private boolean fresh(long now) {
        return fineClass && fine!=null && finePath==currentPath
                && fine.isFresh("FINE",now,wallNow(now),NavigatorDriver.MAX_FIX_AGE_MS);
    }
    // The wall clock is supplied with the source clock through this explicit hook for host tests.
    public interface WallClock { long now(); }
    private WallClock wallClock=System::currentTimeMillis;
    public void setWallClock(WallClock wallClock) {
        if (wallClock==null) throw new IllegalArgumentException("wall clock"); this.wallClock=wallClock;
    }
    private long wallNow(long ignored) { return wallClock.now(); }

    public RealPathAck.Decision onAck(long capturedEpoch, long topic, long receivedAtMs, byte[] payload) {
        long now=clock.now();
        if (!readiness(now) || !servicesReady() || stopping) return RealPathAck.Decision.NOT_READY;
        if (capturedEpoch!=epoch) return RealPathAck.Decision.WRONG_SESSION;
        now=clock.now(); // Include prerequisite I/O in the callback's queue-age policy too.
        RealPathAck.Result result=acknowledgements.event(ackToken,topic,receivedAtMs,payload,now);
        noteDecision("ack",topic,result.decision.ordinal(),result.decision.name());
        return result.decision;
    }
    public long confirmedPathId() { return acknowledgements.confirmedPathId(); }
    public long currentPathId() { return currentPath; }
    public long sessionEpoch() { return epoch; }
    public boolean isClosed() { return closed; }
    public boolean isStopping() { return stopping; }

    /**
     * Final post-marshalling check for the transport call currently owned by this guard. No
     * prerequisite callbacks, Binder, file writes or audit operations occur here. The Android
     * owner must also compare the actual current transport epoch with the cached readiness.
     */
    public boolean finalLeaseValid(long topic) {
        long now=clock.now();
        if (closed || firingTopic!=topic) return false;
        finalLeaseRejection=0;
        if (!packetAllowed(topic) || firingEpoch!=epoch || firingPath!=currentPath || firingGeneration!=routeGeneration) {
            finalLeaseRejection=1; return false;
        }
        if (lastReadiness==null || !lastReadiness.allows(now) || lastReadiness.epoch!=epoch || ackToken<0 || !servicesReady()) {
            finalLeaseRejection=2; return false;
        }
        if (stopping) {
            boolean valid=topic==Topics.NAVIGATION_STATUS && now<=stopDeadline;
            if (!valid) finalLeaseRejection=1;
            return valid;
        }
        if (currentPath<=0) { finalLeaseRejection=1; return false; }
        if (topic==Topics.ROUTE || topic==Topics.PATH_SELECTED) return true;
        boolean valid=acknowledgements.confirmedPathId()==currentPath && fresh(now);
        if (!valid) finalLeaseRejection=1;
        return valid;
    }

    @Override public int fire(long topic, byte[] payload, long ignoredReceiptTime) {
        long now=clock.now();
        if (payload==null || payload.length>MAX_PACKET_BYTES || !packetAllowed(topic) || !readiness(now) || !servicesReady())
            return reject(topic,"packet prerequisites/allowlist");
        if (stopping) {
            if (!stopPending || now>stopDeadline || topic!=Topics.NAVIGATION_STATUS || !status100(payload))
                return reject(topic,"session stopping");
            stopPending=false;
            long stoppedSequence=stopSequence;
            int result=actual(topic,payload,now);
            if (stopping && stopSequence==stoppedSequence) {
                if (result==0 || terminalFailures && result==LEASE_REJECTED) finishNavigationStop(); else close();
            }
            return result;
        }
        if (currentPath<=0) return reject(topic,"no current route");
        if (topic==Topics.ROUTE) return routePacket(payload,now);
        if (topic==Topics.PATH_SELECTED) return selectPacket(payload,now);
        if (acknowledgements.confirmedPathId()!=currentPath || !fresh(now))
            return reject(topic,"real selection/FINE lease required");
        if (topic==Topics.SD_VEHICLE_LOCATION && !positionPathMatches(payload,currentPath))
            return reject(topic,"position belongs to another/unknown path");
        if (topic==Topics.NAVIGATION_STATUS && status100(payload))
            return reject(topic,"idle status requires explicit navigation stop");
        return actual(topic,payload,now);
    }
    private int routePacket(byte[] payload,long now) {
        Group g=group;
        if (g==null || g.path!=currentPath || g.generation!=routeGeneration || g.sent>=g.count)
            return reject(Topics.ROUTE,"no current chunk group");
        String part;
        try {
            Map<?,?> inner=(Map<?,?>)Pb.NAVI_SD_ROUTE_NOTIFY.decode(payload).get("naviSDRouteStruct");
            if (inner==null || integer(inner.get("navigationSDLink2Num"))!=1) throw new IllegalArgumentException("path count");
            Object text=inner.get("navigationSDLink2"); if (!(text instanceof String)) throw new IllegalArgumentException("chunk text");
            Map<?,?> envelope=(Map<?,?>)boundedJson((String)text);
            if (integer(envelope.get("currentIndex"))!=g.sent || integer(envelope.get("length"))!=g.count
                    || integer(envelope.get("pathCount"))!=1) throw new IllegalArgumentException("chunk index/count");
            long time=integer(envelope.get("time"));
            if (time<0 || g.envelopeTime>=0 && time!=g.envelopeTime) throw new IllegalArgumentException("chunk time");
            Object p=envelope.get("payload"); if (!(p instanceof String)) throw new IllegalArgumentException("part");
            part=(String)p; int bytes=part.getBytes(StandardCharsets.UTF_8).length;
            if (bytes>MAX_ROUTE_BYTES-g.bytes) throw new IllegalArgumentException("route bytes");
            if (g.sent==g.count-1) validatePath(g.json.toString()+part,g.path);
            g.envelopeTime=time;
        } catch (RuntimeException bad) { group=null; return reject(Topics.ROUTE,"malformed/foreign chunk"); }
        int result=actual(Topics.ROUTE,payload,now);
        if (result!=0 || group!=g || currentPath!=g.path) {
            if (group==g) group=null;
            return result!=0?result:REJECTED;
        }
        g.json.append(part); g.bytes+=part.getBytes(StandardCharsets.UTF_8).length; g.sent++; hadRoute=true;
        return result;
    }
    private int selectPacket(byte[] payload,long now) {
        Group g=group;
        if (g==null || g.path!=currentPath || g.generation!=routeGeneration || g.sent!=g.count)
            return reject(Topics.PATH_SELECTED,"complete successful route required");
        try {
            Map<?,?> inner=(Map<?,?>)Pb.PATH_SELECT_NOTIFY.decode(payload).get("naviPathUserSelectStsStruct");
            if (inner==null || integer(inner.get("pathIDReq"))!=currentPath) throw new IllegalArgumentException("selection ID");
        } catch (RuntimeException bad) { group=null; return reject(Topics.PATH_SELECTED,"malformed/foreign selection"); }
        group=null;
        long selectedToken=ackToken;
        long[] preFire={-1};
        int result=actual(Topics.PATH_SELECTED,payload,now,preFire);
        if (result!=0) {
            if (owns(epoch,g.path,g.generation)) acknowledgements.routeChanged();
            return result;
        }
        RealPathAck.Decision commit=acknowledgements.selectionSent(selectedToken,g.path,preFire[0],clock.now());
        if (commit!=RealPathAck.Decision.ARMED) { failure("selection commit rejected: "+commit); return REJECTED; }
        return 0;
    }
    private int actual(long topic,byte[] payload,long now) {
        return actual(topic,payload,now,null);
    }
    private int actual(long topic,byte[] payload,long now,long[] preFire) {
        if (firingTopic!=-1) return reject(topic,"nested actual fire");
        long sentEpoch=epoch, sentPath=currentPath, generation=routeGeneration;
        if (!noteDecision("attempt",topic,0,"actual transport")) {
            if (owns(sentEpoch,sentPath,generation)) failure("audit unavailable");
            return REJECTED;
        }
        if (!owns(sentEpoch,sentPath,generation)) return REJECTED;
        // Parsing and local recording can block. Their earlier admission must not become a lease.
        long fireNow=clock.now();
        if (!readiness(fireNow) || !servicesReady() || !owns(sentEpoch,sentPath,generation)) return REJECTED;
        fireNow=clock.now(); // The callback itself may block on files or Binder readiness queries.
        if (!stopping && topic!=Topics.ROUTE && topic!=Topics.PATH_SELECTED
                && (acknowledgements.confirmedPathId()!=currentPath || !fresh(fireNow)))
            return reject(topic,"FINE/selection lease expired before actual fire");
        if (stopping && (topic!=Topics.NAVIGATION_STATUS || fireNow>stopDeadline || !status100(payload)))
            return terminalFailures?LEASE_REJECTED:reject(topic,"stop allowance expired before actual fire");
        if (preFire!=null) preFire[0]=fireNow;
        int result;
        finalLeaseRejection=0;
        firingTopic=topic; firingPath=currentPath; firingGeneration=routeGeneration; firingEpoch=epoch;
        try { result=transport.fire(topic,payload,fireNow); }
        catch (RuntimeException failure) { result=REJECTED; }
        finally { firingTopic=-1; }
        int leaseRejection=finalLeaseRejection;
        Map<String,Object> packet=Json.object(); packet.put("topic",topic); packet.put("result",result);
        packet.put("payloadHex",Pb.hex(payload)); packet.put("source","real_transport_result"); packet.put("epoch",sentEpoch);
        boolean recorded=true;
        try { audit.note("liveEgress.packet",packet,clock.now()); } catch(RuntimeException failure) { recorded=false; }
        if (!owns(sentEpoch,sentPath,generation)) return REJECTED;
        // A stale position or superseded local packet is not a vehicle/transport failure.
        if (recorded && result==PRE_TRANSACT_REJECTED && leaseRejection==1) return LEASE_REJECTED;
        if (!recorded || result!=0) {
            failure("transport/audit fire failed");
            return recorded?result:REJECTED;
        }
        if (!readiness(clock.now()) || !owns(sentEpoch,sentPath,generation)) return REJECTED;
        return result;
    }
    private static long integer(Object value) {
        if (!(value instanceof Long) && !(value instanceof Integer)) throw new IllegalArgumentException("integer required");
        return ((Number)value).longValue();
    }
    private static Object boundedJson(String text) {
        if (text.length()>MAX_ROUTE_BYTES) throw new IllegalArgumentException("JSON budget");
        int depth=0; boolean quoted=false,escape=false;
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (quoted) { if (escape) escape=false; else if(c=='\\')escape=true; else if(c=='"')quoted=false; }
            else if(c=='"')quoted=true;
            else if(c=='{' || c=='[') { if(++depth>64)throw new IllegalArgumentException("JSON depth"); }
            else if(c=='}' || c==']') { if(--depth<0)throw new IllegalArgumentException("JSON nesting"); }
        }
        if (depth!=0 || quoted) throw new IllegalArgumentException("JSON incomplete");
        return Json.parse(text);
    }
    private static void validatePath(String json,long path) {
        Map<?,?> root=(Map<?,?>)boundedJson(json); Object data=root.get("data");
        if (!(data instanceof List) || ((List<?>)data).size()!=1) throw new IllegalArgumentException("one path required");
        Object item=((List<?>)data).get(0);
        if (!(item instanceof Map) || integer(((Map<?,?>)item).get("pathID"))!=path) throw new IllegalArgumentException("path ID mismatch");
    }
    private static boolean status100(byte[] payload) {
        try {
            Map<?,?> body=(Map<?,?>)Pb.NAVIGATION_STATUS_NOTIFY.decode(payload).get("navigationStatusLinkInfoNotifyStruct");
            return body!=null && integer(body.get("navigationStatus"))==100;
        } catch(RuntimeException bad) { return false; }
    }
    private static boolean positionPathMatches(byte[] payload,long path) {
        try {
            Map<?,?> body=(Map<?,?>)Pb.SD_VEHICLE_LOCATION_NOTIFY.decode(payload).get("sdVehicleLocation");
            Object id=body==null?null:body.get("curSDRouteID");
            return id instanceof Number && Double.isFinite(((Number)id).doubleValue())
                    && ((Number)id).doubleValue()==(double)path;
        } catch(RuntimeException bad) { return false; }
    }
    private static boolean packetAllowed(long topic) {
        return Topics.schema(topic)!=null && topic!=Topics.PATH_SELECT_CONFIRM;
    }
    @Override public void navigationStopped() {
        if (closed || stopping) return;
        long stoppedGeneration=routeGeneration, stoppedPath=currentPath, stoppedEpoch=epoch;
        long querySequence=supersededReadiness;
        boolean healthy=readiness(clock.now()) && servicesReady();
        if (supersededReadiness!=querySequence) return;
        if (!healthy) { close(); return; }
        if (!owns(stoppedEpoch,stoppedPath,stoppedGeneration)) return;
        boolean mayStop=hadRoute && healthy;
        currentPath=-1; routeGeneration++; group=null; acknowledgements.routeChanged(); fineClass=false;
        stopping=true; stopPending=mayStop; stopSequence++;
        long now=clock.now(); stopDeadline=now<=Long.MAX_VALUE-STOP_GRACE_MS?now+STOP_GRACE_MS:now;
        if (!healthy) close();
        else if (!mayStop) finishNavigationStop();
        else {
            long sequence=stopSequence;
            clock.post(stopToken,STOP_GRACE_MS,()->{
                if (!stopping || stopSequence!=sequence || closed) return;
                boolean ready=readiness(clock.now()) && servicesReady();
                if (!stopping || stopSequence!=sequence || closed) return;
                if (ready) finishNavigationStop(); else close();
            });
        }
    }
    private void finishNavigationStop() {
        clock.cancel(stopToken);
        stopping=false; stopPending=false; hadRoute=false;
        currentPath=-1; group=null; fineClass=false;
        acknowledgements.routeChanged();
        // Keep usedIds and the FINE high-water mark: old ACKs/fixes cannot acquire
        // the next route's ownership. All new sends still recheck the same lease.
        noteDecision("idle",0,0,"navigation ended; armed session awaits a new route");
    }
    @Override public void close() {
        if (closed) return;
        closed=true; stopping=true; stopPending=false; currentPath=-1; group=null; fineClass=false;
        lastReadiness=null;
        clock.cancel(stopToken); acknowledgements.invalidateSession(); joined.clear();
        if (transport instanceof AutoCloseable) try { ((AutoCloseable)transport).close(); }
        catch(Exception failure) { noteDecision("close",0,REJECTED,"own transport cleanup failed"); }
        noteDecision("closed",0,0,"own guarded session only");
    }
    @Override public void note(String kind,Map<String,Object> fields,long monotonicMs) {
        long notedEpoch=epoch, notedPath=currentPath, notedGeneration=routeGeneration;
        try { audit.note(kind,fields,monotonicMs); }
        catch(RuntimeException failure) { if (owns(notedEpoch,notedPath,notedGeneration)) failure("audit unavailable"); }
    }
    private int reject(long topic,String reason) { noteDecision("rejected",topic,REJECTED,reason); return REJECTED; }
    private boolean noteDecision(String kind,long topic,int result,String reason) {
        long notedEpoch=epoch, notedPath=currentPath, notedGeneration=routeGeneration;
        Map<String,Object> f=Json.object(); f.put("topic",topic); f.put("result",result); f.put("reason",reason);
        f.put("pathId",currentPath); f.put("epoch",epoch);
        try { audit.note("liveEgress."+kind,f,clock.now()); return true; }
        catch(RuntimeException failure) {
            if (terminalFailures && owns(notedEpoch,notedPath,notedGeneration)) close();
            return false;
        }
    }
}
