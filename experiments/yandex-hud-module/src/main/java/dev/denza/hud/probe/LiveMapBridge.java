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
import dev.denza.adaslink.Coordinates;
import dev.denza.adaslink.DryRunRecorder;
import dev.denza.adaslink.FixFreshness;
import dev.denza.adaslink.Json;
import dev.denza.adaslink.LiveEgressGuard;
import dev.denza.adaslink.LiveSessionLease;
import dev.denza.adaslink.NavigatorDriver;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** One explicitly armed process. Real gates, remote availability and real route acknowledgements. */
final class LiveMapBridge {
    private final Context context;
    private final Handler worker;
    private final AndroidScheduler scheduler;
    private final String consumer=UUID.randomUUID().toString(), boot;
    private final DryRunRecorder audit;
    private final Set<String> attempted=new HashSet<>();
    private static final class CallbackBudget {
        final AtomicInteger queued=new AtomicInteger();
        volatile boolean overflow;
    }
    private CallbackBudget callbacks;
    private volatile AdasFeed.Snapshot published=AdasFeed.DISABLED;
    private volatile LiveSessionLease arm;
    private AdasGateSnapshot.Tracker gates;
    private AdasGateSnapshot.Snapshot lastGates;
    private LiveMapTransport transport;
    private AdasPassiveClient answers;
    private LiveEgressGuard guard;
    private StockFeed feed;
    private NavigatorDriver driver;
    private String state="DISARMED", reason="Сеанс передачи не включён";
    private String lastArmId;
    private long activeEpoch=-1, lastSend=-1;
    private boolean everStarted, faulted;
    private volatile boolean finished;
    private final java.util.function.LongSupplier wallClock;
    private byte[] handlingAck;
    private YandexRoute lastRoute;
    private RoadNetwork roads;
    private LiveEgressGuard.Readiness lastReadiness;
    private final Runnable poll=new Runnable() {
        @Override public void run() {
            try { tick(); }
            catch (Exception failed) { stop("Ошибка сеанса: "+failed.getClass().getSimpleName()); }
            finally { worker.postDelayed(this,1000); }
        }
    };

    LiveMapBridge(Context context,Handler parent) throws IOException {
        this(context,parent,bootIdentity(context));
    }
    /** Explicit identity seam for the host runtime harness; production reads Android's boot count. */
    LiveMapBridge(Context context,Handler parent,String bootIdentity) throws IOException {
        this(context,parent,bootIdentity,System::currentTimeMillis);
    }
    /** Deterministic wall clock for the host harness; production uses the system clock. */
    LiveMapBridge(Context context,Handler parent,String bootIdentity,java.util.function.LongSupplier wallClock) throws IOException {
        this.wallClock=wallClock;
        this.context=context.getApplicationContext();
        // A separate Handler gives dry-run and live scheduler tokens separate ownership.
        worker=new Handler(parent.getLooper()); scheduler=new AndroidScheduler(worker);
        boot=LiveSessionLease.uuid(bootIdentity)?bootIdentity:null;
        audit=new DryRunRecorder(new AdasBridge.RotatingWriter(file("denza-adas-live.jsonl")),false);
        worker.post(poll);
    }
    private static String bootIdentity(Context context) {
        try { return BootIdentity.fromCount(Integer.toString(Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT))); }
        catch(Exception unavailable) { return null; }
    }
    AdasFeed.Snapshot status() { return published; }
    private File file(String name) { return new File(context.getFilesDir(),name); }

    private void tick() throws IOException {
        long now=SystemClock.elapsedRealtime();
        Map<String,Object> heartbeat=Json.object();
        heartbeat.put("schema","denza-adas-consumer-v1"); heartbeat.put("bootId",boot);
        heartbeat.put("consumerSession",consumer); heartbeat.put("elapsedMs",now);
        RouteSnapshot.writeAtomic(file("denza-adas-consumer.json"),heartbeat);
        File armed=file("denza-adas-live-arm.json");
        if(!armed.isFile()) {
            if(arm!=null && !finished) stop("Сеанс отключён");
            if(arm==null) { state="DISARMED"; reason=boot==null?"Неизвестен запуск Android":"Сеанс передачи не включён"; }
        } else {
            LiveSessionLease incoming;
            try { incoming=LiveSessionLease.read(read(armed,16384),boot,consumer,now); }
            catch(Exception invalid) {
                if(arm!=null && !finished) stop("Разрешение сеанса истекло или изменилось");
                state="BLOCKED"; reason="Нет действующего разрешения для этого запуска";
                publish(); return;
            }
            if(arm==null || !incoming.armId.equals(arm.armId)) {
                if(arm!=null && !finished) stop("Сеанс заменён");
                if(attempted.contains(incoming.armId) || attempted.size()>=64) {
                    state="BLOCKED"; reason="Этот сеанс уже завершён"; publish(); return;
                }
                begin(incoming);
            } else if(!arm.same(incoming) && !finished) stop("Параметры сеанса изменились");
            if(!finished) {
                LiveEgressGuard.Readiness ready=readiness(now);
                if(faulted || overflowed()) stop(reason);
                else if(everStarted && (guard==null || guard.isClosed())) stop("Передача маршрута завершена");
                else if(everStarted && (!open(ready) || ready.epoch!=activeEpoch)) stop(reason);
                else if(!everStarted && open(ready)) startFeed(ready);
            }
        }
        publish();
    }

    private void begin(LiveSessionLease incoming) {
        arm=incoming; lastArmId=incoming.armId; attempted.add(incoming.armId);
        final CallbackBudget incomingCallbacks=new CallbackBudget();callbacks=incomingCallbacks;
        gates=new AdasGateSnapshot.Tracker(boot,consumer,LiveSessionLease.INPUT_TTL_MS);
        finished=false; faulted=false; everStarted=false; activeEpoch=-1;
        state="WAITING"; reason="Ожидание сигналов машины и ADAS";
        transport=new LiveMapTransport(context,worker,value->{
            if(arm==incoming && !finished) transportState(value);
        },audit,topic->arm==incoming && !finished && finalAdmission(topic));
        answers=new AdasPassiveClient(context,new AdasPassiveClient.Sink() {
            @Override public boolean enqueue(Runnable task) {
                if(arm!=incoming || finished) return false;
                if(incomingCallbacks.queued.incrementAndGet()>32) {
                    incomingCallbacks.queued.decrementAndGet();incomingCallbacks.overflow=true;return false;
                }
                boolean posted=worker.post(()->{
                    try { if(arm==incoming && !finished)task.run(); }
                    finally { incomingCallbacks.queued.decrementAndGet(); }
                });
                if(!posted) { incomingCallbacks.queued.decrementAndGet();incomingCallbacks.overflow=true; }
                return posted;
            }
            @Override public void state(String event,String detail) {
                if(arm!=incoming || finished) return;
                try { DriveEvidence.passiveState("live",incoming.armId,event,detail); }
                catch(RuntimeException ignored) { /* Recording must not change transport state. */ }
                if(event.equals("binder_disconnected") || event.equals("null_binding") || event.equals("bind_failed")
                        || event.equals("rebind_exhausted") || event.equals("client_setup_error")) faulted=true;
                note("client",event+":"+detail);
            }
            @Override public void event(long topic,long timestamp,long receivedAt,byte[] payload) {
                if(arm!=incoming || finished) return;
                try { DriveEvidence.passive("live",incoming.armId,topic,timestamp,receivedAt,payload); }
                catch(RuntimeException ignored) { /* Original bytes still reach ACK processing below. */ }
                if(topic==Topics.PATH_SELECT_CONFIRM && guard!=null && payload!=null) {
                    Map<String,Object> row=Json.object(); row.put("topic",topic); row.put("payloadHex",Pb.hex(payload));
                    row.put("receivedAtElapsedMs",receivedAt); row.put("source","actual_ADAS_callback");
                    audit.note("liveFeedback",row,SystemClock.elapsedRealtime());
                    handlingAck=payload;
                    try { guard.onAck(epoch(),topic,receivedAt,payload); }
                    finally { handlingAck=null; }
                }
            }
        },true);
        transport.start(); answers.start(); note("armed",incoming.armId);
    }
    private void transportState(String value) {
        if(!value.equals("server_connected")) faulted=true;
        note("server",value);
    }
    private boolean overflowed() { return callbacks!=null && callbacks.overflow; }
    private long epoch() {
        if(transport==null || answers==null)return -1;
        long server=transport.generation(), client=answers.epoch();
        return server<=0 || server>0x7fffffffL || client<=0 || client>0xffffffffL ? -1 : server<<32 | client;
    }

    /** Reads independent snapshots again at each fire; a poll's old success is never a lease. */
    private LiveEgressGuard.Readiness readiness(long now) {
        LiveSessionLease checkedArm=arm;
        LiveEgressGuard checkedGuard=guard;
        boolean started=everStarted;
        try {
            if(finished || overflowed() || faulted || arm==null) throw new IllegalStateException("Связь сеанса потеряна");
            LiveSessionLease current=LiveSessionLease.read(read(file("denza-adas-live-arm.json"),16384),boot,consumer,now);
            if(!arm.same(current)) throw new IllegalStateException("Сеанс изменился");
            if(context.getPackageManager().getApplicationEnabledSetting(LiveSessionLease.STOCK_PACKAGE)
                    !=PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) throw new IllegalStateException("Штатная карта включена");
            String isolationText=read(file("denza-adas-stock-isolation.json"),16384);
            long isolation=arm.isolationStart(isolationText,SystemClock.elapsedRealtime());
            String gateText=read(file("denza-adas-gates.json"),16384);
            lastGates=gates.read(gateText,SystemClock.elapsedRealtime());
            if(!lastGates.valid || !arm.readerSession.equals(lastGates.readerSession))
                throw new IllegalStateException("Нет свежих сигналов машины: "+lastGates.reason);
            boolean server=transport!=null && transport.ready(), replies=answers!=null && answers.liveAnswersReady();
            long oldest=Math.min(isolation,lastGates.startedAtElapsedMs);
            if(!lastGates.hardwareGateOpen()) reason="Передача карты выключена сигналами машины";
            else if(!server || !replies) reason="Ожидание доступности сервисов ADAS";
            else reason="Передача готова";
            LiveEgressGuard.Readiness evaluated=new LiveEgressGuard.Readiness(epoch(),oldest,Math.min(arm.expiresAt,oldest+LiveSessionLease.INPUT_TTL_MS),
                    true,true,lastGates.valid && lastGates.noSleep!=null && !Boolean.TRUE.equals(lastGates.downtime),
                    Boolean.TRUE.equals(lastGates.dnpService),Boolean.TRUE.equals(lastGates.dnpSwitchRaw),
                    Boolean.TRUE.equals(lastGates.noSleep),server,replies);
            if(arm!=checkedArm || guard!=checkedGuard) return null;
            lastReadiness=evaluated;
            if(started && arm==checkedArm && guard==checkedGuard && (!open(lastReadiness) || lastReadiness.epoch!=activeEpoch))
                faulted=true;
            return lastReadiness;
        } catch(Exception invalid) {
            if(arm==checkedArm && guard==checkedGuard) {
                if(started && !finished) faulted=true;
                lastReadiness=null; reason=invalid.getMessage()==null?"Данные сеанса недоступны":invalid.getMessage();
            }
            return null;
        }
    }
    /** No Binder/disk I/O here: final check immediately before the actual map-event transaction. */
    private boolean finalAdmission(long topic) {
        LiveEgressGuard.Readiness r=lastReadiness;long now=SystemClock.elapsedRealtime();
        if(finished || faulted || overflowed() || !open(r) || r.observedAtMs>now || r.expiresAtMs<=now || r.epoch!=epoch())return false;
        return guard!=null && guard.finalLeaseValid(topic);
    }
    private static boolean open(LiveEgressGuard.Readiness r) {
        long now=SystemClock.elapsedRealtime();
        return r!=null && r.epoch>0 && r.observedAtMs>=0 && r.observedAtMs<=now && r.expiresAtMs>now
                && r.armed && r.stockDisabled && r.gatesKnown && r.dnpService && r.dnpSwitch && r.noSleep
                && r.serverReady && r.answersReady;
    }
    private void startFeed(LiveEgressGuard.Readiness ready) {
        guard=new LiveEgressGuard(transport,audit,scheduler,this::readiness,id->{
            // Only the original callback bytes that passed RealPathAck reach the producer.
            if(feed!=null && handlingAck!=null) feed.onPathSelectConfirm(handlingAck);
        },true);
        guard.setWallClock(wallClock::getAsLong);
        StockFeed.Config config=new StockFeed.Config(); config.frame=Coordinates.Frame.WGS84;
        config.roadSpeedLimit=true;
        config.roadFacilities=true;
        config.acceptSimulatedLocations=false; config.emulatedAckDelayMs=-1; config.livePathIds=this::nextPathId;
        feed=new StockFeed(scheduler,guard,config); driver=new NavigatorDriver(feed,config.frame,scheduler);
        driver.setRoads(roads); activeEpoch=ready.epoch;
        feed.start(ready.dnpService,ready.dnpSwitch,ready.noSleep);
        if(!transport.servicesOffered()) { stop("Сервисы отправки не запущены"); return; }
        everStarted=true; state="LIVE"; reason="Ожидание маршрута Яндекса";
    }
    private long nextPathId() {
        SharedPreferences prefs=context.getSharedPreferences("denza-adas-path-ids",Context.MODE_PRIVATE);
        // Use a fresh positive signed-32-bit domain compatible with the accepted
        // parked probe. Persist separately from build 11's wider ID namespace.
        // Receiver width is not proved; this is a bounded compatibility choice.
        long old=prefs.getLong("lastCompatible32",0), wall=wallClock.getAsLong()/1000L;
        if(old<0 || old>=Integer.MAX_VALUE || wall<=0 || wall>=Integer.MAX_VALUE)
            throw new IllegalStateException("Path ID storage/clock");
        long id=Math.max(old+1,wall);
        if(!prefs.edit().putLong("lastCompatible32",id).commit()) throw new IllegalStateException("Path ID persistence");
        return id;
    }

    /** Called on this looper after dry-run graph preparation, with the original immutable fix anchor. */
    void observe(NavigatorDriver.Observation o,FixFreshness.Sample fix,RoadNetwork currentRoads) {
        if(o.route!=null) lastRoute=o.route;
        if(o.routeId==null || o.mode.equals("off")) lastRoute=null;
        roads=currentRoads;
        if(!everStarted || finished || driver==null || guard==null) return;
        try {
            LiveEgressGuard.Readiness r=readiness(SystemClock.elapsedRealtime());
            if(!open(r) || r.epoch!=activeEpoch) { stop(reason); return; }
            if(guard.isClosed()) { stop("Передача маршрута завершена"); return; }
            driver.setRoads(roads);
            // The route may have been copied while the real channel was still waiting for hardware.
            if(o.route==null && !driver.naving() && lastRoute!=null && lastRoute.routeId.equals(o.routeId)) o.route=lastRoute;
            driver.observe(o);
            guard.fineFix(guard.currentPathId(),fix,o.simulated?"SIMULATED":o.locationClass);
            published=liveSnapshot();
        } catch(RuntimeException failed) { stop("Ошибка маршрута: "+failed.getClass().getSimpleName()); }
    }
    private AdasFeed.Snapshot liveSnapshot() {
        if(feed==null || finished || transport==null || transport.isClosed())
            return new AdasFeed.Snapshot(AdasFeed.Mode.DISABLED,lastSend,-1,false,-1,state,reason);
        BridgeStatus.Snapshot s=feed.status();
        return new AdasFeed.Snapshot(AdasFeed.Mode.LIVE,transport.lastActualSend(),s.currentPathId,s.routeActive,
                guard==null?-1:guard.confirmedPathId(),state,reason);
    }
    private void stop(String why) {
        reason=why==null?"Сеанс остановлен":why; finished=true; state="STOPPED";
        if(feed!=null) feed.close();
        if(guard!=null) guard.close();
        if(transport!=null) { lastSend=Math.max(lastSend,transport.lastActualSend()); transport.close(); }
        if(answers!=null) answers.close();
        published=AdasFeed.DISABLED;
        note("stopped",reason);
        try { publish(); } catch(IOException failed) { Log.w(AdasBridge.TAG,"live state unavailable",failed); }
    }
    private void publish() throws IOException {
        boolean serverClosed=transport==null || transport.isClosed(), answersClosed=answers==null || answers.isClosed();
        published=liveSnapshot();
        Map<String,Object> value=Json.object();
        value.put("schema","denza-adas-live-state-v1"); value.put("bootId",boot); value.put("consumerSession",consumer);
        value.put("armId",lastArmId); value.put("elapsedMs",SystemClock.elapsedRealtime()); value.put("state",state);
        value.put("quiescent",serverClosed && answersClosed && (feed==null || finished));
        value.put("serverClosed",serverClosed); value.put("answersClosed",answersClosed);
        value.put("lastActualSendElapsedMs",transport==null?lastSend:Math.max(lastSend,transport.lastActualSend()));
        value.put("pathId",published.pathId); value.put("confirmedPathId",guard==null?-1:guard.confirmedPathId());
        value.put("reason",reason);
        if(lastGates!=null) { value.put("gateReason",lastGates.reason); value.put("gateSequence",lastGates.sequence);
            value.put("dnpService",lastGates.dnpService); value.put("dnpSwitch",lastGates.dnpSwitchRaw); value.put("noSleep",lastGates.noSleep); }
        RouteSnapshot.writeAtomic(file("denza-adas-live-state.json"),value);
    }
    private void note(String kind,String detail) {
        try { Map<String,Object> m=Json.object();m.put("detail",detail);m.put("consumerSession",consumer);m.put("armId",lastArmId);
            audit.note("liveSession."+kind,m,SystemClock.elapsedRealtime()); }
        catch(RuntimeException error) { faulted=true; }
    }
    private static String read(File file,int maximum) throws IOException {
        if(file.length()>maximum)throw new IOException("file cap");
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[1024];int count;
            while((count=in.read(buf))!=-1) { if(count>maximum-out.size())throw new IOException("file cap");out.write(buf,0,count); }
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
