package dev.denza.adaslink;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-packet live admission. Every method, prerequisite callback and ACK consumption belongs to
 * the same worker. Pure Java; constructing this class neither binds nor enables a sender.
 * Readiness must describe the real transport epoch, exact required answer subscriptions, current
 * vehicle gates and the separately established stock-publisher exclusion; assumptions are false.
 *
 * <p>A prerequisite that is momentarily missing (a late gate snapshot, an old isolation proof, a
 * gate switched off) suspends egress and nothing more: the published path, its real ACK and the
 * FINE anchor stay, and sending resumes on the same path at the next packet that finds every
 * prerequisite again, as the stock map keeps its route through its own gaps. The session ends
 * only on a terminal cause: an actual send or audit failure, a changed transport epoch, a
 * prerequisite the caller marks terminal, or {@link #endSession}. The first failure of any kind is
 * kept and never overwritten ({@link #firstFailure}).
 *
 * <p>When guidance ends, the stock's NavigationStatus loop ({@code MmtSomeIpDataManager.d}) says
 * 110 three times after an arrival and 100 after any end, once a second. This guard admits that
 * end sequence (110 ×3 after arrival, then 100 ×3) and nothing else until a new route; a session
 * that closes with a route at ADAS sends it before it releases the transport.
 */
public final class LiveEgressGuard implements StockFeed.GuardedLivePublisher, AutoCloseable {
    public static final int REJECTED = -1200;
    private static final int LEASE_REJECTED = -1201;
    /** Owned Android transport returns this before TX6 when its final admission check declines. */
    private static final int PRE_TRANSACT_REJECTED = -203;
    public static final int MAX_CHUNKS = 16, MAX_PACKET_BYTES = 600 * 1024, MAX_ROUTE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IDS = 4096;
    private static final long MAX_EXACT_PATH_ID = (1L << 53) - 1; // curSDRouteID is a double on the wire.
    /** The stock's status loop: 110 for three ticks after arrival, then the idle 100. */
    public static final int STATUS_ARRIVED = 110, STATUS_IDLE = 100, END_REPEATS = 3;
    /** A closing session waits at most this long for its end statuses at the stock's 1 s cadence. */
    public static final long END_SESSION_MS = 3500;

    /** Called anew immediately before every transport transaction; UNKNOWN must be represented as false/null. */
    public interface Prerequisites { Readiness current(long nowMs); }
    public static final class Readiness {
        public final long epoch, observedAtMs, expiresAtMs;
        public final boolean armed, stockDisabled, gatesKnown, dnpService, dnpSwitch, noSleep, serverReady, answersReady;
        /** The caller's own words for what is missing or why (the input, its age); null when nothing is. */
        public final String detail;
        /** The caller says the failure ends the session (arm gone, stock map enabled), not a gap. */
        public final boolean terminal;
        public Readiness(long epoch, long observedAtMs, long expiresAtMs, boolean armed, boolean stockDisabled,
                boolean gatesKnown, boolean dnpService, boolean dnpSwitch, boolean noSleep,
                boolean serverReady, boolean answersReady) {
            this(epoch,observedAtMs,expiresAtMs,armed,stockDisabled,gatesKnown,dnpService,dnpSwitch,noSleep,serverReady,answersReady,null,false);
        }
        public Readiness(long epoch, long observedAtMs, long expiresAtMs, boolean armed, boolean stockDisabled,
                boolean gatesKnown, boolean dnpService, boolean dnpSwitch, boolean noSleep,
                boolean serverReady, boolean answersReady, String detail, boolean terminal) {
            this.epoch=epoch; this.observedAtMs=observedAtMs; this.expiresAtMs=expiresAtMs;
            this.armed=armed; this.stockDisabled=stockDisabled; this.gatesKnown=gatesKnown;
            this.dnpService=dnpService; this.dnpSwitch=dnpSwitch; this.noSleep=noSleep;
            this.serverReady=serverReady; this.answersReady=answersReady; this.detail=detail; this.terminal=terminal;
        }
        /** A readiness that names only what failed, for callers that cannot evaluate the rest. */
        public static Readiness missing(String detail, boolean terminal) {
            Readiness r=new Readiness(-1,-1,-1,false,false,false,false,false,false,false,false,detail,terminal);
            r.missingOnly=true; return r;
        }
        private boolean missingOnly;
        boolean allows(long now) { return failure(now) == null; }
        /** The first failing prerequisite at {@code now}, in the fixed order below; null when all hold. */
        public String failure(long now) {
            if (missingOnly) return detail == null ? "prerequisites unavailable" : detail;
            String first = epoch <= 0 ? "transport epoch unknown"
                    : observedAtMs < 0 ? "inputs never observed"
                    : observedAtMs > now ? "inputs observed in the future"
                    : expiresAtMs <= now ? "inputs stale: observed " + (now - observedAtMs) + " ms ago, valid " + (expiresAtMs - observedAtMs) + " ms"
                    : !armed ? "no valid arm"
                    : !stockDisabled ? "stock map not proved disabled"
                    : !gatesKnown ? "vehicle gates unknown"
                    : !dnpService ? "dnpService off"
                    : !dnpSwitch ? "dnpSwitch off"
                    : !noSleep ? "noSleep off"
                    : !serverReady ? "own map service not ready"
                    : !answersReady ? "ADAS answer subscriptions not ready" : null;
            if (first == null) return null;
            return detail == null || detail.isEmpty() ? first : first + " (" + detail + ")";
        }
    }

    private final Publisher transport, audit;
    private final Scheduler clock;
    private final Prerequisites prerequisites;
    private final RealPathAck acknowledgements;
    private final boolean terminalFailures;
    private final Set<Long> joined = new HashSet<>(), usedIds = new HashSet<>();
    private final Object endToken = new Object();
    private long epoch = -1, ackToken = -1, currentPath = -1, routeGeneration;
    private Readiness lastReadiness;
    private long firingTopic = -1, firingPath, firingGeneration, firingEpoch;
    private int finalLeaseRejection;
    private long supersededReadiness;
    private long fineRelative = -1, fineAbsolute = -1, finePath = -1;
    private FixFreshness.Sample fine;
    private boolean fineClass, closed, hadRoute, closeAfterEnd;
    private String suspended, firstFailure, terminalReason, lastRejection;
    private long firstFailureAt = -1, suspensions, endDeadline = Long.MAX_VALUE, endsSent;
    /** Pending end statuses; endIndex == endSequence.length when none is pending. */
    private int[] endSequence = new int[0];
    private int endIndex;
    private Runnable endListener;
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
    /** Production arm policy: an actual send/audit failure or a transport epoch change ends this owned session. */
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
        Readiness r; String thrown=null;
        try { r=prerequisites.current(now); }
        catch (RuntimeException failure) { r=null; thrown=failure.getClass().getSimpleName()+": "+failure.getMessage(); }
        if (!owns(queriedEpoch,queriedPath,queriedGeneration)) { supersededReadiness++; return false; }
        long checkedAt=clock.now();
        String missing=checkedAt<now ? "clock went back " + (now-checkedAt) + " ms"
                : r==null ? (thrown==null ? "prerequisites unavailable" : "prerequisites failed: "+thrown)
                : r.failure(checkedAt);
        if (missing!=null) {
            lastReadiness=null;
            if (r!=null && r.terminal) failure("prerequisite: "+missing);
            else suspend(missing);
            return false;
        }
        if (epoch>0 && epoch!=r.epoch) {
            String why="transport epoch changed "+epoch+" -> "+r.epoch;
            if (terminalFailures) { failure(why); return false; }
            invalidate(why,true);
            if (closed) return false;
            joined.clear();
            epoch=r.epoch; ackToken=acknowledgements.sessionReady(checkedAt);
        } else if (ackToken<0) {
            // Session establishment, or the first query after a local invalidation: not an epoch change.
            epoch=r.epoch; ackToken=acknowledgements.sessionReady(checkedAt);
            noteDecision("session",0,0,"selection session ready on transport epoch "+epoch);
        }
        if (suspended!=null) {
            String was=suspended; suspended=null;
            noteDecision("resumed",0,0,"prerequisites back after: "+was);
        }
        lastReadiness=r;
        return true;
    }
    private boolean owns(long ownerEpoch,long ownerPath,long ownerGeneration) {
        return !closed && epoch==ownerEpoch && currentPath==ownerPath && routeGeneration==ownerGeneration;
    }
    private void recordFailure(String reason) {
        if (firstFailure==null) { firstFailure=reason; firstFailureAt=clock.now(); }
    }
    /** A gap, not an end: path, ACK, joins and the FINE anchor stay; egress waits for the next ready query. */
    private void suspend(String reason) {
        recordFailure(reason);
        if (reason.equals(suspended)) return;
        suspended=reason; suspensions++;
        noteDecision("suspended",0,REJECTED,reason);
    }
    private void failure(String reason) {
        if (closed) return;
        recordFailure(reason);
        if (terminalFailures) { terminalReason=reason; close(); noteDecision("terminal",0,REJECTED,reason); }
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
    /** Why the last {@link #readiness} said no: the terminal reason, the current gap, or closure. */
    private String notAdmitted() {
        return closed ? "session closed: "+(terminalReason==null?"explicit close":terminalReason)
                : suspended!=null ? "suspended: "+suspended : "prerequisites superseded";
    }
    @Override public int startService(long service) {
        if (!serviceAllowed(service)) return reject(service,"not one of the four own services");
        if (!readiness(clock.now())) return reject(service,"offer not admitted, "+notAdmitted());
        long offeredEpoch=epoch;
        long offeredPath=currentPath, offeredGeneration=routeGeneration;
        int result;
        try { result=transport.startService(service); }
        catch (RuntimeException failure) { result=REJECTED; }
        if (!owns(offeredEpoch,offeredPath,offeredGeneration)) return REJECTED;
        if (!readiness(clock.now()) || epoch!=offeredEpoch) return reject(service,"offer completed on old session");
        if (result==0 || result==13) joined.add(service);
        else { joined.remove(service); failure("own service offer failed: "+Long.toHexString(service)+" result "+result); }
        noteDecision("service",service,result,"own offer/join");
        return result;
    }

    /** Model bookkeeping only (no packet): a new route supersedes a pending end even during a gap. */
    @Override public void routeChanged(long pathId) {
        if (closed || closeAfterEnd) return;
        clearEnd("superseded by a new route");
        if (!servicesReady() || pathId<=0 || pathId>MAX_EXACT_PATH_ID || usedIds.contains(pathId)) {
            invalidate(!servicesReady()?"route not admitted: own service offers incomplete":"route not admitted: path ID reused/out of range "+pathId,true);
            return;
        }
        routeGeneration++; group=null; acknowledgements.routeChanged(); currentPath=pathId;
        // A held fix from a previous path must not acquire this path's ownership.
        noteDecision("route",pathId,0,"new model route");
    }
    @Override public void beginRoute(long pathId, int chunkCount) {
        if (closed || endPending()) return;
        if (!readiness(clock.now())) { reject(Topics.ROUTE,"route group not begun, "+notAdmitted()); return; }
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

    /**
     * A receipt, not a send: during a prerequisite gap the real ACK of the current selection is still
     * consumed, so the gap cannot cost the path its confirmation. Closure and a changed or unknown
     * session still refuse it.
     */
    public RealPathAck.Decision onAck(long capturedEpoch, long topic, long receivedAtMs, byte[] payload) {
        long now=clock.now();
        boolean ready=readiness(now);
        if (closed || endPending() || !ready && suspended==null || ackToken<0 || !servicesReady()) return RealPathAck.Decision.NOT_READY;
        if (capturedEpoch!=epoch) return RealPathAck.Decision.WRONG_SESSION;
        now=clock.now(); // Include prerequisite I/O in the callback's queue-age policy too.
        RealPathAck.Result result=acknowledgements.event(ackToken,topic,receivedAtMs,payload,now);
        noteDecision("ack",topic,result.decision.ordinal(),result.decision.name()+(ready?"":" during gap: "+suspended));
        return result.decision;
    }
    public long confirmedPathId() { return acknowledgements.confirmedPathId(); }
    public long currentPathId() { return currentPath; }
    public long sessionEpoch() { return epoch; }
    public boolean isClosed() { return closed; }
    /** An end sequence is waiting to be sent (or sending). */
    public boolean isStopping() { return endPending(); }
    public boolean endPending() { return !closed && endIndex<endSequence.length; }
    /** End statuses actually sent by this guard. */
    public long endStatusesSent() { return endsSent; }
    /** The first failure of this guard, transient or terminal, as it was first seen; null if none. */
    public String firstFailure() { return firstFailure; }
    public long firstFailureAtMs() { return firstFailureAt; }
    /** The cause that closed this guard; null while open or after an explicit close. */
    public String terminalReason() { return terminalReason; }
    /** The current prerequisite gap; null while every prerequisite holds. */
    public String suspension() { return suspended; }
    public long suspensions() { return suspensions; }
    public String lastRejection() { return lastRejection; }
    /** Ready right now by the last query, without a new one: the producer's cheap retry gate. */
    @Override public boolean admitting() { return !closed && suspended==null && ackToken>=0 && servicesReady() && !closeAfterEnd; }
    /** Run once when a pending end sequence is complete (sent in full), on this worker. */
    public void setEndListener(Runnable listener) { endListener=listener; }

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
        if (endPending()) {
            boolean valid=topic==Topics.NAVIGATION_STATUS && now<=endDeadline;
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
        if (payload==null) return reject(topic,"no payload");
        if (payload.length>MAX_PACKET_BYTES) return reject(topic,"payload "+payload.length+" bytes over "+MAX_PACKET_BYTES);
        if (!packetAllowed(topic)) return reject(topic,"topic never published");
        if (!readiness(now)) return reject(topic,notAdmitted());
        if (!servicesReady()) return reject(topic,"own service offers incomplete");
        if (endPending()) return endPacket(topic,payload,now);
        if (currentPath<=0) return reject(topic,"no current route");
        if (topic==Topics.ROUTE) return routePacket(payload,now);
        if (topic==Topics.PATH_SELECTED) return selectPacket(payload,now);
        if (acknowledgements.confirmedPathId()!=currentPath) return reject(topic,"real selection ACK required");
        if (!fresh(now)) return reject(topic,"FINE lease required (no fresh FINE fix on this path)");
        if (topic==Topics.SD_VEHICLE_LOCATION && !positionPathMatches(payload,currentPath))
            return reject(topic,"position belongs to another/unknown path");
        if (topic==Topics.NAVIGATION_STATUS && status(payload)==STATUS_IDLE)
            return reject(topic,"idle status only in an end sequence");
        return actual(topic,payload,now);
    }
    /** The pending end sequence admits exactly its next status; 100 may cut short unsent 110s. */
    private int endPacket(long topic,byte[] payload,long now) {
        if (topic!=Topics.NAVIGATION_STATUS) return reject(topic,"ending: only the end status sequence");
        if (now>endDeadline) { String why="end sequence deadline passed"; reject(topic,why); if (closeAfterEnd) close(); else clearEnd(why); return REJECTED; }
        int status=status(payload), expected=endSequence[endIndex];
        if (status!=expected && !(status==STATUS_IDLE && expected==STATUS_ARRIVED))
            return reject(topic,"ending: expected status "+expected+", got "+status);
        int[] sequence=endSequence; int index=endIndex;
        int result=actual(topic,payload,now);
        // A blocked worker can pass the session-end deadline inside the call: release without its status.
        if (result!=0 && closeAfterEnd && !closed && clock.now()>endDeadline) { close(); return result; }
        if (result==0 && endSequence==sequence && endIndex==index) {
            endsSent++;
            if (status==STATUS_IDLE) while (endIndex<endSequence.length && endSequence[endIndex]==STATUS_ARRIVED) endIndex++;
            endIndex++;
            if (endIndex>=endSequence.length) endComplete();
        }
        return result;
    }
    private void endComplete() {
        clock.cancel(endToken);
        endSequence=new int[0]; endIndex=0; endDeadline=Long.MAX_VALUE;
        noteDecision("idle",0,0,"stock end sequence sent; ADAS holds no route of ours");
        Runnable listener=endListener;
        if (closeAfterEnd) close();
        if (listener!=null) listener.run();
    }
    private void clearEnd(String why) {
        if (endIndex>=endSequence.length) return;
        clock.cancel(endToken);
        endSequence=new int[0]; endIndex=0; endDeadline=Long.MAX_VALUE;
        noteDecision("endCancelled",0,0,why);
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
        } catch (RuntimeException bad) { group=null; return reject(Topics.ROUTE,"malformed/foreign chunk: "+bad.getMessage()); }
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
        boolean ending=endPending();
        if (!noteDecision("attempt",topic,0,"actual transport")) {
            if (owns(sentEpoch,sentPath,generation)) failure("audit unavailable before actual send");
            return REJECTED;
        }
        if (!owns(sentEpoch,sentPath,generation)) return REJECTED;
        // Parsing and local recording can block. Their earlier admission must not become a lease.
        long fireNow=clock.now();
        if (!readiness(fireNow) || !servicesReady() || !owns(sentEpoch,sentPath,generation)) return REJECTED;
        fireNow=clock.now(); // The callback itself may block on files or Binder readiness queries.
        if (!ending && topic!=Topics.ROUTE && topic!=Topics.PATH_SELECTED
                && (acknowledgements.confirmedPathId()!=currentPath || !fresh(fireNow)))
            return reject(topic,"FINE/selection lease expired before actual fire");
        if (ending && (topic!=Topics.NAVIGATION_STATUS || fireNow>endDeadline))
            return terminalFailures?LEASE_REJECTED:reject(topic,"end allowance expired before actual fire");
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
        if (recorded && result==PRE_TRANSACT_REJECTED && leaseRejection==2) return reject(topic,"cached readiness lapsed before the transaction");
        if (!recorded || result!=0) {
            failure(recorded ? "actual send failed: topic "+Long.toHexString(topic)+" result "+result : "audit unavailable after actual send");
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
    /** NavigationStatus value of a payload; -1 when it is not one. */
    private static int status(byte[] payload) {
        try {
            Map<?,?> body=(Map<?,?>)Pb.NAVIGATION_STATUS_NOTIFY.decode(payload).get("navigationStatusLinkInfoNotifyStruct");
            return body==null ? -1 : (int)integer(body.get("navigationStatus"));
        } catch(RuntimeException bad) { return -1; }
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

    @Override public void navigationStopped() { navigationStopped(false); }
    /**
     * Guidance ended: the route stops being ours at once (no dynamics, no ACK) and the stock's end
     * sequence becomes due, sent by the producer's own 1 s status loop when every prerequisite holds.
     * Nothing to say when no chunk of a route ever reached ADAS.
     */
    @Override public void navigationStopped(boolean arrived) {
        if (closed) return;
        boolean held=hadRoute;
        currentPath=-1; routeGeneration++; group=null; acknowledgements.routeChanged(); fineClass=false; hadRoute=false;
        if (!held) {
            if (!endPending()) noteDecision("idle",0,0,"navigation ended before any route reached ADAS");
            return; // A pending end keeps its place in the sequence.
        }
        beginEnd(arrived, "navigation ended" + (arrived ? " on arrival" : ""));
    }
    private void beginEnd(boolean arrived,String why) {
        int[] sequence=new int[(arrived?END_REPEATS:0)+END_REPEATS];
        for (int i=0;i<sequence.length;i++) sequence[i]=arrived && i<END_REPEATS ? STATUS_ARRIVED : STATUS_IDLE;
        endSequence=sequence; endIndex=0;
        noteDecision("end",0,0,why+"; stock end sequence "+Arrays.toString(sequence));
    }
    /**
     * A route of ours may still be at ADAS although this guard never sent it (an earlier process or
     * arm of the same boot): send the stock's end before any new route.
     */
    public void requestEnd(String why) {
        if (closed || currentPath>0 || endPending()) return;
        beginEnd(false,why);
    }
    /**
     * Ends the owned session the stock's way: guidance (if any) ends, the end sequence goes out at
     * the producer's cadence within {@link #END_SESSION_MS}, then the transport is released. With
     * nothing of ours at ADAS the session closes at once. The caller owns the producer's stop.
     */
    public void endSession(String why) {
        if (closed) return;
        if (terminalReason==null) terminalReason=why; // An intended end, not a failure.
        if (currentPath>0 || hadRoute) navigationStopped(false);
        if (!endPending()) { close(); return; }
        closeAfterEnd=true;
        endDeadline=Math.min(endDeadline,clock.now()+END_SESSION_MS);
        clock.cancel(endToken);
        clock.post(endToken,END_SESSION_MS+1,()->{ if (!closed) { noteDecision("endTimeout",0,REJECTED,"end sequence incomplete at session end"); close(); } });
        noteDecision("ending",0,0,why);
    }
    @Override public void close() {
        if (closed) return;
        closed=true; currentPath=-1; group=null; fineClass=false;
        lastReadiness=null;
        clock.cancel(endToken); acknowledgements.invalidateSession(); joined.clear();
        if (transport instanceof AutoCloseable) try { ((AutoCloseable)transport).close(); }
        catch(Exception failure) { noteDecision("close",0,REJECTED,"own transport cleanup failed"); }
        noteDecision("closed",0,0,"own guarded session only"+(terminalReason==null?"":": "+terminalReason));
    }
    @Override public void note(String kind,Map<String,Object> fields,long monotonicMs) {
        long notedEpoch=epoch, notedPath=currentPath, notedGeneration=routeGeneration;
        try { audit.note(kind,fields,monotonicMs); }
        catch(RuntimeException failure) { if (owns(notedEpoch,notedPath,notedGeneration)) failure("audit unavailable"); }
    }
    private int reject(long topic,String reason) { lastRejection=reason; noteDecision("rejected",topic,REJECTED,reason); return REJECTED; }
    private boolean noteDecision(String kind,long topic,int result,String reason) {
        long notedEpoch=epoch, notedPath=currentPath, notedGeneration=routeGeneration;
        Map<String,Object> f=Json.object(); f.put("topic",topic); f.put("result",result); f.put("reason",reason);
        f.put("pathId",currentPath); f.put("epoch",epoch);
        try { audit.note("liveEgress."+kind,f,clock.now()); return true; }
        catch(RuntimeException failure) {
            if (terminalFailures && owns(notedEpoch,notedPath,notedGeneration)) { recordFailure("audit unavailable"); close(); }
            return false;
        }
    }
}
