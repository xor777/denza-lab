package dev.denza.hud.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import dev.denza.adaslink.AdasGateSnapshot;
import dev.denza.adaslink.AndroidScheduler;
import dev.denza.adaslink.BridgeStatus;
import dev.denza.adaslink.BootIdentity;
import dev.denza.adaslink.DryRunRecorder;
import dev.denza.adaslink.FieldDefaults;
import dev.denza.adaslink.FixFreshness;
import dev.denza.adaslink.Json;
import dev.denza.adaslink.LiveEgressGuard;
import dev.denza.adaslink.LiveSessionLease;
import dev.denza.adaslink.NavigatorDriver;
import dev.denza.adaslink.OfflineMapGate;
import dev.denza.adaslink.Pb;
import dev.denza.adaslink.RoadNetwork;
import dev.denza.adaslink.StockFeed;
import dev.denza.adaslink.Topics;
import dev.denza.adaslink.YandexRoute;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One explicitly armed session of actual publication: real gates, remote availability and real
 * route acknowledgements. Everything here runs on the sender thread the host gives it (normal
 * priority, apart from the dry run's background worker), except {@link #observe}, which converts
 * the route on the caller's thread and hands the observation over.
 *
 * <ul>
 * <li>Readiness inputs (arm, stock isolation, gates, package state, HAL readiness) are read on a
 * {@link #REFRESH_MS} timer and before an observation that may publish a route, never per fire;
 * every fire still checks their age against the arm's TTL.</li>
 * <li>Binder callbacks go through a {@link CallbackQueue}: never blocking, never ending the session
 * by volume, selection ACKs never dropped.</li>
 * <li>A prerequisite gap pauses the feed (the guard keeps the path); a terminal cause ends the
 * session, keeping its first cause. A session that ends with a route of ours at ADAS sends the
 * stock's end statuses first; so does a session resumed after a Navigator restart that left one.</li>
 * <li>The arm binds boot and consumer. The consumer identity lasts for the boot, so a restarted
 * Navigator process registers its own process session against the same unexpired arm and resumes,
 * with every gate and isolation check enforced again; an arm whose session ended stays ended.</li>
 * </ul>
 */
final class LiveMapBridge {
    static final long REFRESH_MS = 250, END_LEAD_MS = 5000, END_WAIT_MS = LiveEgressGuard.END_SESSION_MS + 1000;
    static final int RESTART_LIMIT = 8;
    static final String ARM = "denza-adas-live-arm.json", LINEAGE = "denza-adas-live-lineage.json",
            CONSUMER_ID = "denza-adas-consumer-id.json";
    private static final long LIFECYCLE = -1;
    private final Context context;
    private final Handler worker;
    private final AndroidScheduler scheduler;
    private final String consumer, boot, process = UUID.randomUUID().toString();
    private final DryRunRecorder audit;
    private final Set<String> attempted = new HashSet<>();
    private CallbackQueue callbacks;
    private volatile AdasFeed.Snapshot published = AdasFeed.DISABLED;
    private volatile LiveSessionLease arm;
    private AdasGateSnapshot.Tracker gates;
    private LiveMapTransport transport;
    private AdasPassiveClient answers;
    private LiveEgressGuard guard;
    private StockFeed feed;
    private NavigatorDriver driver;
    private String state = "DISARMED", reason = "Сеанс передачи не включён";
    private String lastArmId, stopReason, stopDetail, fault, blockedDetail, lastPrerequisite;
    private long activeEpoch = -1, lastSend = -1, endBy = -1, heldPath = -1, resumedHeld = -1, prerequisiteChanges;
    private boolean everStarted, ending, lineageDirty, lastDnpSwitch;
    private volatile boolean finished;
    private final java.util.function.LongSupplier wallClock;
    private byte[] handlingAck;
    private YandexRoute lastRoute;
    private NavigatorDriver.Prepared lastPrepared;
    private RoadNetwork roads;
    private LiveEgressGuard.Readiness lastReadiness;
    private Inputs inputs;
    private List<String> lineageProcesses = new ArrayList<>();
    // Observation hand-off: the newest one waits, older ones coalesce into it.
    private final Object handoff = new Object();
    private Pending pending;
    private long coalesced;
    // Graph frozen per route ID: the sender's published one, else the caller's first sight (see observe).
    private String frozenRouteId;
    private RoadNetwork frozenRoads;
    /** {route ID, graph} of the table the sender's driver published: the worker converts on the same graph. */
    private volatile Object[] publishedTable = {null, null};
    private volatile OfflineMapGate.Subject sourceSubject;
    private volatile OfflineMapGate.Snapshot offlineSnapshot;
    private long sourceEpoch = -1;
    private OfflineMapGate.Subject publishedSubject;
    private boolean offlineTeardown, offlineHold;

    /** Main-thread seam: publish identity before any queued route work, including a partial geometry read. */
    synchronized void sourceRoute(OfflineMapGate.Subject subject) {
        if (subject != null && subject.epoch < sourceEpoch) return;
        if (subject != null) sourceEpoch = subject.epoch;
        sourceSubject = subject;
    }
    /** A proof never changes the current route subject; late callbacks cannot resurrect a previous route. */
    void offlineMap(OfflineMapGate.Snapshot snapshot) { offlineSnapshot = snapshot; }

    private OfflineMapGate.Decision offline(long now) { return OfflineMapGate.check(sourceSubject, offlineSnapshot, now); }
    private boolean sourceCurrent(NavigatorDriver.Observation o) {
        OfflineMapGate.Subject subject = sourceSubject;
        return subject != null && subject.epoch == o.routeEpoch && java.util.Objects.equals(subject.routeId, o.routeId)
                && java.util.Objects.equals(subject.geometrySha256, o.offlineGeometrySha256);
    }
    private void reconcileOffline(long now) {
        if (!everStarted || feed == null || guard == null || driver == null || guard.isClosed() || offlineTeardown) return;
        if (driver.publishedRouteId() == null && guard.currentPathId() <= 0 && !guard.endPending()) return;
        OfflineMapGate.Subject subject = sourceSubject;
        if (offline(now).ready && java.util.Objects.equals(publishedSubject, subject)
                && subject != null && (driver.publishedRouteId() == null
                    || java.util.Objects.equals(subject.routeId, driver.publishedRouteId()))) return;
        offlineTeardown = true;
        offlineHold = subject == null || subject.routeId != null;
        note("offlineWithdrawal", offline(now).reason);
        feed.endGuidance();
        guard.withdrawRoute("offline: " + offline(now).reason);
        driver = new NavigatorDriver(feed, FieldDefaults.FRAME, scheduler);
        driver.setRoads(roads);
        publishedSubject = null; publishedTable = new Object[]{null, null};
        if (!guard.endPending()) { offlineTeardown = false; offlineHold = false; }
    }

    /** The last read of every readiness input, with when it was read. */
    private static final class Inputs {
        LiveSessionLease arm;
        String armFailure, isolationFailure, gatesFailure;
        int stockState;
        long readAt, isolationStart = -1;
        AdasGateSnapshot.Snapshot gates;
        boolean server, replies;
    }
    private static final class Pending {
        final NavigatorDriver.Observation o; final FixFreshness.Sample fix; final RoadNetwork roads;
        Pending(NavigatorDriver.Observation o, FixFreshness.Sample fix, RoadNetwork roads) { this.o = o; this.fix = fix; this.roads = roads; }
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try { tick(); }
            catch (Exception failed) { stop("Ошибка сеанса", failed.toString(), true); }
            finally { worker.postDelayed(this, 1000); }
        }
    };
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            try { if (arm != null && !finished) { long now = SystemClock.elapsedRealtime(); refresh(now); reconcileOffline(now); } }
            catch (RuntimeException failed) { fault("readiness refresh: " + failed); }
            finally { worker.postDelayed(this, REFRESH_MS); }
        }
    };

    LiveMapBridge(Context context, Handler sender) throws IOException {
        this(context, sender, bootIdentity(context));
    }
    /** Explicit identity seam for the host runtime harness; production reads Android's boot count. */
    LiveMapBridge(Context context, Handler sender, String bootIdentity) throws IOException {
        this(context, sender, bootIdentity, System::currentTimeMillis);
    }
    /** Deterministic wall clock for the host harness; production uses the system clock. */
    LiveMapBridge(Context context, Handler sender, String bootIdentity, java.util.function.LongSupplier wallClock) throws IOException {
        this.wallClock = wallClock;
        this.context = context.getApplicationContext();
        // A separate Handler gives dry-run and live scheduler tokens separate ownership.
        worker = new Handler(sender.getLooper()); scheduler = new AndroidScheduler(worker);
        boot = LiveSessionLease.uuid(bootIdentity) ? bootIdentity : null;
        consumer = consumerIdentity();
        audit = new DryRunRecorder(new AdasBridge.RotatingWriter(file("denza-adas-live.jsonl"), AdasBridge.LIVE_GENERATIONS), false);
        worker.post(poll);
        worker.postDelayed(refresher, REFRESH_MS);
    }
    private static String bootIdentity(Context context) {
        try { return BootIdentity.fromCount(Integer.toString(Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT))); }
        catch (Exception unavailable) { return null; }
    }
    /** One consumer identity per boot: a restarted process of this boot keeps it, a new boot does not. */
    private String consumerIdentity() {
        File id = file(CONSUMER_ID);
        if (boot != null && id.isFile()) {
            try {
                Map<String, Object> m = LiveSessionLease.object(read(id, 4096));
                if ("denza-adas-consumer-id-v1".equals(m.get("schema")) && boot.equals(m.get("bootId"))
                        && LiveSessionLease.uuid(m.get("consumerSession"))) return (String) m.get("consumerSession");
            } catch (Exception unreadable) { /* A new identity below. */ }
        }
        String fresh = UUID.randomUUID().toString();
        if (boot != null) {
            Map<String, Object> m = Json.object();
            m.put("schema", "denza-adas-consumer-id-v1"); m.put("bootId", boot); m.put("consumerSession", fresh);
            try { RouteSnapshot.writeAtomic(id, m); } catch (IOException unwritable) { Log.w(AdasBridge.TAG, "consumer identity not kept", unwritable); }
        }
        return fresh;
    }
    AdasFeed.Snapshot status() { return published; }
    private File file(String name) { return new File(context.getFilesDir(), name); }

    private void tick() throws IOException {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> heartbeat = Json.object();
        heartbeat.put("schema", "denza-adas-consumer-v1"); heartbeat.put("bootId", boot);
        heartbeat.put("consumerSession", consumer); heartbeat.put("processSession", process); heartbeat.put("elapsedMs", now);
        RouteSnapshot.writeAtomic(file("denza-adas-consumer.json"), heartbeat);
        if (ending && (guard == null || guard.isClosed() || now >= endBy)) finish();
        File armed = file(ARM);
        if (!armed.isFile()) {
            if (arm != null && !finished) stop("Сеанс отключён", "arm file removed", false);
            if (arm == null) { state = "DISARMED"; reason = boot == null ? "Неизвестен запуск Android" : "Сеанс передачи не включён"; }
        } else {
            LiveSessionLease incoming;
            try { incoming = LiveSessionLease.read(read(armed, 16384), boot, consumer, now); }
            catch (Exception invalid) {
                if (arm != null && !finished) stop("Разрешение сеанса истекло", "arm: " + invalid.getMessage(), false);
                if (arm == null) block("Разрешение не выдано", "arm: " + invalid.getMessage());
                publish(); return;
            }
            if (arm == null || !incoming.armId.equals(arm.armId)) {
                // The old session's channels close (no send without its arm) before the new arm opens its own.
                if (arm != null && !finished) stop("Сеанс заменён", "a new arm " + incoming.armId, false);
                if (!begin(incoming)) { publish(); return; }
            } else if (!arm.same(incoming) && !finished) stop("Параметры сеанса изменились", "arm content changed", false);
            if (!finished && !ending) {
                refresh(now);
                if (!everStarted && resumedHeld > 0) offlineTeardown = true;
                LiveEgressGuard.Readiness ready = readiness(now);
                prerequisite(ready.failure(now));
                if (fault != null) stop(everStarted ? "Связь с сервисами потеряна" : "Сервисы не подключились", fault, false);
                else if (everStarted && ready.terminal) stop(ready.failure(now).startsWith("stock map") ? "Штатная карта включена" : "Разрешение сеанса изменилось", ready.failure(now), false);
                else if (everStarted && guard.isClosed()) stop("Ошибка отправки", guard.terminalReason(), false);
                else if (everStarted && ready.epoch > 0 && ready.epoch != activeEpoch)
                    stop("Сервисы ADAS перезапущены", "transport epoch " + activeEpoch + " -> " + ready.epoch, false);
                else if (everStarted && arm.expiresAt - now <= END_LEAD_MS) stop("Разрешение сеанса истекает", "arm expires at " + arm.expiresAt, true);
                else if (!everStarted && open(ready)) startFeed(ready);
                else if (everStarted) gatesChanged();
                if (!everStarted && !offline(now).ready) reason = offline(now).reason;
            }
        }
        if (lineageDirty) writeLineage();
        publish();
    }

    /** The session under {@code incoming}, unless it already ended in this boot or restarted too often. */
    private boolean begin(LiveSessionLease incoming) {
        if (attempted.contains(incoming.armId) || attempted.size() >= 64) { block("Сеанс уже завершён", "arm " + incoming.armId + " ended in this process"); return false; }
        Map<String, Object> lineage = readLineage(incoming.armId);
        List<String> processes = new ArrayList<>();
        long held = -1;
        if (lineage != null) {
            if (lineage.get("endedReason") != null) { block("Сеанс уже завершён", "arm " + incoming.armId + " ended: " + lineage.get("endedReason")); return false; }
            Object listed = lineage.get("processes");
            if (listed instanceof List) for (Object p : (List<?>) listed) if (p instanceof String) processes.add((String) p);
            if (processes.size() >= RESTART_LIMIT) { block("Слишком много перезапусков", processes.size() + " processes under arm " + incoming.armId); return false; }
            Object path = lineage.get("heldPathId");
            if (path instanceof Number) held = ((Number) path).longValue();
        }
        boolean resumed = !processes.isEmpty();
        processes.add(process);
        lineageProcesses = processes; heldPath = held; resumedHeld = held; lineageDirty = true;
        arm = incoming; lastArmId = incoming.armId; attempted.add(incoming.armId);
        final LiveSessionLease owner = incoming;
        final CallbackQueue queue = new CallbackQueue(worker::post); callbacks = queue;
        gates = new AdasGateSnapshot.Tracker(boot, consumer, LiveSessionLease.INPUT_TTL_MS);
        finished = false; ending = false; fault = null; everStarted = false; activeEpoch = -1; inputs = null;
        offlineTeardown = false; offlineHold = false; publishedSubject = null; publishedTable = new Object[]{null, null};
        stopReason = null; stopDetail = null; blockedDetail = null; lastPrerequisite = null;
        state = "WAITING"; reason = resumed ? "Возобновление после перезапуска" : "Ожидание сигналов машины и ADAS";
        transport = new LiveMapTransport(context, worker, value -> {
            if (arm == owner && !finished) transportState(value);
        }, audit, topic -> arm == owner && !finished && finalAdmission(topic));
        answers = new AdasPassiveClient(context, new AdasPassiveClient.Sink() {
            @Override public boolean enqueue(Runnable task) { return enqueue(LIFECYCLE, task); }
            @Override public boolean enqueue(long topic, Runnable task) {
                if (arm != owner || finished) return false;
                return queue.enqueue(essential(topic), () -> { if (arm == owner && !finished) task.run(); });
            }
            @Override public void state(String event, String detail) {
                if (arm != owner || finished) return;
                try { DriveEvidence.passiveState("live", owner.armId, event, detail); }
                catch (RuntimeException ignored) { /* Recording must not change transport state. */ }
                if (event.equals("binder_disconnected") || event.equals("null_binding") || event.equals("bind_failed")
                        || event.equals("rebind_exhausted") || event.equals("client_setup_error")) fault("ADAS answers " + event + ": " + detail);
                note("client", event + ":" + detail);
            }
            @Override public void event(long topic, long timestamp, long receivedAt, byte[] payload) {
                if (arm != owner || finished) return;
                // Service-13 status is recorded once, by the overlay's receiver; this one keeps its answers and availability.
                if ((topic >>> 48) != 4 || Topics.service(topic) != 13) {
                    try { DriveEvidence.passive("live", owner.armId, topic, timestamp, receivedAt, payload); }
                    catch (RuntimeException ignored) { /* Original bytes still reach ACK processing below. */ }
                }
                if (topic == Topics.PATH_SELECT_CONFIRM && guard != null && payload != null) {
                    Map<String, Object> row = Json.object(); row.put("topic", topic); row.put("payloadHex", Pb.hex(payload));
                    row.put("receivedAtElapsedMs", receivedAt); row.put("source", "actual_ADAS_callback");
                    row.put("queueDelayMs", SystemClock.elapsedRealtime() - receivedAt);
                    audit.note("liveFeedback", row, SystemClock.elapsedRealtime());
                    handlingAck = payload;
                    try { guard.onAck(epoch(), topic, receivedAt, payload); }
                    finally { handlingAck = null; }
                }
            }
        }, true);
        transport.start(); answers.start();
        note("armed", incoming.armId + (resumed ? " resumed by process " + process + " after " + (processes.size() - 1) : " process " + process)
                + (held > 0 ? ", route " + held + " may be at ADAS" : ""));
        return true;
    }
    private static boolean essential(long topic) {
        return topic == LIFECYCLE || (topic >>> 48) == 1 || Topics.service(topic) == Topics.SVC_PATH_MATCH;
    }
    private void block(String label, String detail) {
        state = "BLOCKED"; reason = label; blockedDetail = detail;
    }
    private void transportState(String value) {
        if (!value.equals("server_connected")) fault("map service " + value);
        note("server", value);
    }
    /** A terminal cause from a callback; the first one stays. */
    private void fault(String why) { if (fault == null) fault = why; }
    private long epoch() {
        if (transport == null || answers == null) return -1;
        long server = transport.generation(), client = answers.epoch();
        return server <= 0 || server > 0x7fffffffL || client <= 0 || client > 0xffffffffL ? -1 : server << 32 | client;
    }

    /** Reads every readiness input once; the only file and Binder reads behind admission. */
    private void refresh(long now) {
        LiveSessionLease checked = arm;
        if (checked == null || finished) return;
        Inputs in = new Inputs(); in.arm = checked;
        try {
            LiveSessionLease current = LiveSessionLease.read(read(file(ARM), 16384), boot, consumer, now);
            if (!checked.same(current)) in.armFailure = "arm content changed";
        } catch (Exception invalid) { in.armFailure = invalid.getMessage() == null ? invalid.toString() : invalid.getMessage(); }
        try { in.stockState = context.getPackageManager().getApplicationEnabledSetting(LiveSessionLease.STOCK_PACKAGE); }
        catch (RuntimeException unknown) { in.stockState = -1; }
        try { in.isolationStart = checked.isolationStart(read(file("denza-adas-stock-isolation.json"), 16384), SystemClock.elapsedRealtime()); }
        catch (Exception invalid) { in.isolationFailure = invalid.getMessage() == null ? invalid.toString() : invalid.getMessage(); }
        try {
            in.gates = gates.read(read(file("denza-adas-gates.json"), 16384), SystemClock.elapsedRealtime());
            if (!in.gates.valid) in.gatesFailure = in.gates.reason;
            else if (!checked.readerSession.equals(in.gates.readerSession)) in.gatesFailure = "reader session differs from the arm";
        } catch (Exception invalid) { in.gatesFailure = invalid.getMessage() == null ? invalid.toString() : invalid.getMessage(); }
        in.server = transport != null && transport.ready();
        in.replies = answers != null && answers.liveAnswersReady();
        in.readAt = SystemClock.elapsedRealtime();
        if (arm == checked) inputs = in;
    }

    /** Built from the last {@link #refresh}, per fire, with no I/O; the guard checks its age. */
    private LiveEgressGuard.Readiness readiness(long now) {
        LiveEgressGuard.Readiness r = evaluate(now);
        lastReadiness = r;
        return r;
    }
    private LiveEgressGuard.Readiness evaluate(long now) {
        reconcileOffline(now);
        LiveEgressGuard.Readiness hardware = hardwareReadiness(now);
        if (hardware.failure(now) != null) return hardware;
        if (offlineTeardown && (resumedHeld > 0 || guard != null && guard.endPending())) return hardware;
        OfflineMapGate.Decision decision = offline(now);
        return decision.ready ? hardware : LiveEgressGuard.Readiness.missing("offline: " + decision.reason, false);
    }
    /** Original arm/isolation/hardware/transport gates also apply to the teardown-only exception. */
    private LiveEgressGuard.Readiness hardwareReadiness(long now) {
        Inputs in = inputs;
        if (finished || arm == null) return LiveEgressGuard.Readiness.missing("session finished", true);
        if (fault != null) return LiveEgressGuard.Readiness.missing(fault, true);
        if (in == null || in.arm != arm) return LiveEgressGuard.Readiness.missing("readiness inputs not read yet", false);
        if (in.armFailure != null) return LiveEgressGuard.Readiness.missing("arm: " + in.armFailure, true);
        if (in.stockState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
            return LiveEgressGuard.Readiness.missing("stock map package state " + in.stockState + ", not disabled-user", true);
        if (in.isolationFailure != null) return LiveEgressGuard.Readiness.missing("stock isolation: " + in.isolationFailure, false);
        if (in.gatesFailure != null) return LiveEgressGuard.Readiness.missing("vehicle gates: " + in.gatesFailure, false);
        AdasGateSnapshot.Snapshot g = in.gates;
        long oldest = Math.min(in.isolationStart, g.startedAtElapsedMs);
        String detail = "gates seq " + g.sequence + " from " + (now - g.startedAtElapsedMs) + " ms ago, isolation from "
                + (now - in.isolationStart) + " ms ago" + (g.noSleep == null ? ", SLEEP_STATUS " + g.rawValues.get(AdasGateSnapshot.SLEEP_STATUS) + " (transition)" : "")
                + (Boolean.TRUE.equals(g.downtime) ? ", disconnection downtime" : "");
        return new LiveEgressGuard.Readiness(epoch(), oldest, Math.min(arm.expiresAt, oldest + LiveSessionLease.INPUT_TTL_MS),
                true, true, g.valid && g.noSleep != null && !Boolean.TRUE.equals(g.downtime),
                Boolean.TRUE.equals(g.dnpService), Boolean.TRUE.equals(g.dnpSwitchRaw),
                Boolean.TRUE.equals(g.noSleep), in.server, in.replies, detail, false);
    }
    /** Every change of the first failing prerequisite, with its own words, into the live journal. */
    private void prerequisite(String failure) {
        if (failure == null ? lastPrerequisite == null : failure.equals(lastPrerequisite)) return;
        prerequisiteChanges++;
        note("prerequisite", failure == null ? "all prerequisites hold" : failure);
        lastPrerequisite = failure;
    }
    /** No Binder/disk I/O here: final check immediately before the actual map-event transaction. */
    private boolean finalAdmission(long topic) {
        long now = SystemClock.elapsedRealtime();
        reconcileOffline(now);
        LiveEgressGuard.Readiness r = hardwareReadiness(now);
        if (finished || fault != null || !open(r) || r.observedAtMs > now || r.expiresAtMs <= now || r.epoch != epoch()) return false;
        if (offlineTeardown) {
            if (topic != Topics.NAVIGATION_STATUS || guard == null || !guard.endPending()) return false;
        } else if (!offline(now).ready || !java.util.Objects.equals(publishedSubject, sourceSubject)) return false;
        return guard != null && guard.finalLeaseValid(topic);
    }
    private static boolean open(LiveEgressGuard.Readiness r) {
        return r != null && r.failure(SystemClock.elapsedRealtime()) == null;
    }
    private void startFeed(LiveEgressGuard.Readiness ready) {
        // The stock reads the ODD region list before its first route. This is a list query,
        // never the 13/0x0001 DNP/ICC switch. Its raw reply is retained by the answer client.
        int oddResult = answers.requestOddRegionCodes();
        note("odd_region_query", "result=" + oddResult);
        guard = new LiveEgressGuard(transport, audit, scheduler, this::readiness, id -> {
            // Only the original callback bytes that passed RealPathAck reach the producer.
            if (feed != null && handlingAck != null) feed.onPathSelectConfirm(handlingAck);
            // Kept at once: a process that dies now leaves this route to its successor's end sequence.
            heldPath = id;
            try { writeLineage(); } catch (IOException unwritable) { lineageDirty = true; }
        }, true);
        guard.setWallClock(wallClock::getAsLong);
        guard.setEndListener(() -> { heldPath = -1; lineageDirty = true; offlineTeardown = false; offlineHold = false; });
        StockFeed.Config config = FieldDefaults.navigator(new StockFeed.Config());
        config.acceptSimulatedLocations = false; config.emulatedAckDelayMs = -1; config.livePathIds = this::nextPathId;
        feed = new StockFeed(scheduler, guard, config); driver = new NavigatorDriver(feed, config.frame, scheduler);
        driver.setRoads(roads); activeEpoch = ready.epoch; lastDnpSwitch = ready.dnpSwitch;
        feed.start(ready.dnpService, ready.dnpSwitch, ready.noSleep);
        if (!transport.servicesOffered()) { stop("Сервисы отправки не запущены", "own service offers incomplete", false); return; }
        // A route an earlier process of this arm may have left at ADAS: the stock's end before anything new.
        if (resumedHeld > 0) { guard.requestEnd("route " + resumedHeld + " left by an earlier process of this arm"); resumedHeld = -1; }
        everStarted = true; state = "LIVE"; reason = "Ожидание маршрута Яндекса";
    }
    /** The stock resends its path 300 ms after the DNP switch comes back on. */
    private void gatesChanged() {
        Inputs in = inputs;
        if (in == null || in.gates == null || !in.gates.valid || feed == null) return;
        boolean on = Boolean.TRUE.equals(in.gates.dnpSwitchRaw);
        if (on && !lastDnpSwitch) { note("dnpSwitch", "on: the stock resends its route"); feed.onDnpSwitchOn(); }
        lastDnpSwitch = on;
    }
    private long nextPathId() {
        SharedPreferences prefs = context.getSharedPreferences("denza-adas-path-ids", Context.MODE_PRIVATE);
        // Use a fresh positive signed-32-bit domain compatible with the accepted
        // parked probe. Persist separately from build 11's wider ID namespace.
        // Receiver width is not proved; this is a bounded compatibility choice.
        long old = prefs.getLong("lastCompatible32", 0), wall = wallClock.getAsLong() / 1000L;
        if (old < 0 || old >= Integer.MAX_VALUE || wall <= 0 || wall >= Integer.MAX_VALUE)
            throw new IllegalStateException("Path ID storage/clock");
        long id = Math.max(old + 1, wall);
        if (!prefs.edit().putLong("lastCompatible32", id).commit()) throw new IllegalStateException("Path ID persistence");
        return id;
    }

    /**
     * Called on the dry run's worker with the original immutable fix anchor: the route is converted
     * here, off the sender, on the newest graph and on the graph frozen for its route ID; the newest
     * observation then waits for the sender, older ones coalesce into it.
     */
    void observe(NavigatorDriver.Observation original, FixFreshness.Sample fix, RoadNetwork currentRoads) {
        NavigatorDriver.Observation o = original.copy();
        o.prepared = null;
        if (o.route != null && o.routeId != null) {
            Object[] sent = publishedTable;
            if (o.routeId.equals(sent[0])) { frozenRouteId = o.routeId; frozenRoads = (RoadNetwork) sent[1]; }
            else if (!o.routeId.equals(frozenRouteId)) { frozenRouteId = o.routeId; frozenRoads = currentRoads; }
            NavigatorDriver.prepare(o, FieldDefaults.FRAME, currentRoads, frozenRoads);
        }
        boolean post;
        synchronized (handoff) {
            if (pending != null) {
                coalesced++;
                if (o.route == null && pending.o.route != null && pending.o.route.routeId.equals(o.routeId)) {
                    o.route = pending.o.route; o.prepared = pending.o.prepared;
                }
            }
            post = pending == null;
            pending = new Pending(o, fix, currentRoads);
        }
        if (post) worker.post(this::observePending);
    }
    private void observePending() {
        Pending next;
        synchronized (handoff) { next = pending; pending = null; }
        if (next != null) observeNow(next.o, next.fix, next.roads);
    }
    private void observeNow(NavigatorDriver.Observation o, FixFreshness.Sample fix, RoadNetwork currentRoads) {
        if (!sourceCurrent(o)) return;
        if (o.route != null) { lastRoute = o.route; lastPrepared = o.prepared; }
        if (o.routeId == null || o.mode.equals("off")) { lastRoute = null; lastPrepared = null; }
        roads = currentRoads;
        if (!everStarted || finished || ending || driver == null || guard == null) return;
        try {
            long now = SystemClock.elapsedRealtime();
            boolean verifiedReplacement = !offlineHold && o.route != null && offline(now).ready
                    && !java.util.Objects.equals(driver.publishedRouteId(), o.routeId);
            if (verifiedReplacement) { offlineTeardown = false; publishedSubject = sourceSubject; }
            else reconcileOffline(now);
            if (offlineTeardown || !offline(now).ready) { published = liveSnapshot(); return; }
            // An observation that can publish a route reads the inputs now; dynamics use the timer's read.
            boolean mayPublish = o.route != null || !driver.naving() && lastRoute != null && lastRoute.routeId.equals(o.routeId);
            if (mayPublish) refresh(now);
            if (guard.isClosed()) { stop("Ошибка отправки", guard.terminalReason(), false); return; }
            driver.setRoads(roads);
            // The route may have been copied while the real channel was still waiting for hardware.
            if (o.route == null && !driver.naving() && lastRoute != null && lastRoute.routeId.equals(o.routeId)) {
                o.route = lastRoute; o.prepared = lastPrepared;
            }
            // Include hand-off and conversion delay since the original, immutable anchor.
            o.monotonicMs = SystemClock.elapsedRealtime();
            if (fix != null) o.fixAgeMs = fix.ageMs(o.monotonicMs, wallClock.getAsLong());
            if (!sourceCurrent(o)) return;
            publishedSubject = sourceSubject;
            driver.observe(o);
            publishedTable = new Object[]{driver.publishedRouteId(), driver.publishedRoads()};
            guard.fineFix(guard.currentPathId(), fix, o.simulated ? "SIMULATED" : o.locationClass);
            published = liveSnapshot();
        } catch (RuntimeException failed) { stop("Ошибка маршрута", failed.toString(), true); }
    }
    private AdasFeed.Snapshot liveSnapshot() {
        if (feed == null || finished || transport == null || transport.isClosed())
            return new AdasFeed.Snapshot(AdasFeed.Mode.DISABLED, lastSend, -1, false, -1, state, reason);
        BridgeStatus.Snapshot s = feed.status();
        String shown = state, label = reason;
        if ("LIVE".equals(state) && guard != null && guard.suspension() != null) { shown = "PAUSED"; label = pauseLabel(guard.suspension()); }
        if ("LIVE".equals(state)) {
            OfflineMapGate.Decision decision = offline(SystemClock.elapsedRealtime());
            if (!decision.ready && (sourceSubject == null || sourceSubject.routeId != null)) { shown = "PAUSED"; label = decision.reason; }
            else if (offlineTeardown && offlineHold) { shown = "PAUSED"; label = "Завершение прежнего маршрута для ADAS"; }
        }
        return new AdasFeed.Snapshot(AdasFeed.Mode.LIVE, transport.lastActualSend(), s.currentPathId, s.routeActive,
                guard == null ? -1 : guard.confirmedPathId(), shown, label);
    }
    /** The overlay's words for a gap: what the driver can see, not the code's. */
    static String pauseLabel(String suspension) {
        if (suspension.startsWith("offline: ")) return suspension.substring(9);
        if (suspension.contains("dnpSwitch") || suspension.contains("dnpService")) return "NOA выключен";
        if (suspension.contains("noSleep")) return "ADAS в режиме сна";
        if (suspension.contains("isolation")) return "Проверка штатной карты";
        if (suspension.contains("ADAS answer") || suspension.contains("map service")) return "Ожидание сервисов ADAS";
        return "Нет свежих сигналов машины";
    }

    /** {@code graceful}: the session may still send, so a route of ours at ADAS gets the stock's end first. */
    private void stop(String label, String detail, boolean graceful) {
        if (stopReason == null) {
            stopReason = label; stopDetail = detail;
            String first = guard == null ? null : guard.firstFailure();
            note("stopping", label + (detail == null ? "" : ": " + detail) + (first == null ? "" : "; first failure: " + first));
        }
        if (finished) return;
        if (ending) { if (!graceful) finish(); return; } // No sending without the arm: the end is cut short.
        if (graceful && guard != null && !guard.isClosed() && feed != null && !evaluate(SystemClock.elapsedRealtime()).terminal) {
            ending = true; state = "ENDING"; reason = "Завершение маршрута для ADAS";
            endBy = SystemClock.elapsedRealtime() + END_WAIT_MS;
            feed.endGuidance();
            guard.endSession(label + (detail == null ? "" : ": " + detail));
            if (!guard.isClosed()) { published = liveSnapshot(); return; }
        }
        finish();
    }
    private void finish() {
        finished = true; ending = false; state = "STOPPED"; reason = stopReason == null ? "Сеанс остановлен" : stopReason;
        if (feed != null) feed.close();
        if (guard != null) guard.close();
        if (transport != null) { lastSend = Math.max(lastSend, transport.lastActualSend()); transport.close(); }
        if (answers != null) answers.close();
        if (callbacks != null) callbacks.close();
        published = AdasFeed.DISABLED;
        lineageDirty = true;
        note("stopped", reason + (stopDetail == null ? "" : ": " + stopDetail)
                + (guard != null && guard.firstFailure() != null ? "; first failure: " + guard.firstFailure() : "")
                + (heldPath > 0 ? "; route " + heldPath + " may remain at ADAS" : ""));
        try { writeLineage(); publish(); } catch (IOException failed) { Log.w(AdasBridge.TAG, "live state unavailable", failed); }
    }

    private Map<String, Object> readLineage(String armId) {
        File f = file(LINEAGE);
        if (!f.isFile()) return null;
        try {
            Map<String, Object> m = LiveSessionLease.object(read(f, 16384));
            if (!"denza-adas-live-lineage-v1".equals(m.get("schema")) || boot == null || !boot.equals(m.get("bootId"))
                    || !consumer.equals(m.get("consumerSession")) || !armId.equals(m.get("armId"))) return null;
            return m;
        } catch (Exception unreadable) { return null; }
    }
    /** Which processes ran this arm, whether it ended, and a route of ours ADAS may hold. Rare writes only. */
    private void writeLineage() throws IOException {
        lineageDirty = false;
        if (lastArmId == null || boot == null) return;
        Map<String, Object> m = Json.object();
        m.put("schema", "denza-adas-live-lineage-v1"); m.put("bootId", boot); m.put("consumerSession", consumer);
        m.put("armId", lastArmId); m.put("processes", new ArrayList<Object>(lineageProcesses));
        m.put("heldPathId", heldPath);
        m.put("endedReason", finished ? (stopReason == null ? "stopped" : stopReason) : null);
        RouteSnapshot.writeAtomic(file(LINEAGE), m);
    }
    private void publish() throws IOException {
        boolean serverClosed = transport == null || transport.isClosed(), answersClosed = answers == null || answers.isClosed();
        published = liveSnapshot();
        Map<String, Object> value = Json.object();
        value.put("schema", "denza-adas-live-state-v1"); value.put("bootId", boot); value.put("consumerSession", consumer);
        value.put("processSession", process); value.put("armProcesses", (long) lineageProcesses.size());
        value.put("armId", lastArmId); value.put("elapsedMs", SystemClock.elapsedRealtime()); value.put("state", published.runtimeState == null ? state : published.runtimeState);
        value.put("quiescent", serverClosed && answersClosed && (feed == null || finished));
        value.put("serverClosed", serverClosed); value.put("answersClosed", answersClosed);
        value.put("lastActualSendElapsedMs", transport == null ? lastSend : Math.max(lastSend, transport.lastActualSend()));
        value.put("pathId", published.pathId); value.put("confirmedPathId", guard == null ? -1 : guard.confirmedPathId());
        value.put("heldPathId", heldPath);
        value.put("reason", published.reason == null ? reason : published.reason);
        value.put("stopReason", stopReason); value.put("stopDetail", stopDetail); value.put("blockedDetail", blockedDetail);
        value.put("firstFailure", guard == null ? null : guard.firstFailure());
        value.put("suspension", guard == null ? null : guard.suspension());
        value.put("prerequisite", lastPrerequisite); value.put("prerequisiteChanges", prerequisiteChanges);
        value.put("lastRejection", guard == null ? null : guard.lastRejection());
        value.put("callbacks", callbacks == null ? null : callbacks.stats());
        OfflineMapGate.Decision offline = offline(SystemClock.elapsedRealtime());
        value.put("offlineReady", offline.ready); value.put("offlineCode", offline.code);
        value.put("offlineReason", offline.reason); value.put("offlineTeardown", offlineTeardown);
        synchronized (handoff) { value.put("observationsCoalesced", coalesced); }
        AdasGateSnapshot.Snapshot g = inputs == null ? null : inputs.gates;
        if (g != null) { value.put("gateReason", g.reason); value.put("gateSequence", g.sequence);
            value.put("dnpService", g.dnpService); value.put("dnpSwitch", g.dnpSwitchRaw); value.put("noSleep", g.noSleep); }
        RouteSnapshot.writeAtomic(file("denza-adas-live-state.json"), value);
        try { DriveEvidence.feedState(String.valueOf(value.get("state")), String.valueOf(value.get("reason")),
                stopDetail != null ? stopDetail : guard != null && guard.suspension() != null ? guard.suspension() : blockedDetail); }
        catch (RuntimeException ignored) { /* Recording must not change transport state. */ }
    }
    private void note(String kind, String detail) {
        try { Map<String, Object> m = Json.object(); m.put("detail", detail); m.put("consumerSession", consumer); m.put("processSession", process);
            m.put("armId", lastArmId); audit.note("liveSession." + kind, m, SystemClock.elapsedRealtime()); }
        catch (RuntimeException error) { fault("live journal unavailable: " + error); }
    }
    private static String read(File file, int maximum) throws IOException {
        if (file.length() > maximum) throw new IOException("file cap");
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024]; int count;
            while ((count = in.read(buf)) != -1) { if (count > maximum - out.size()) throw new IOException("file cap"); out.write(buf, 0, count); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
