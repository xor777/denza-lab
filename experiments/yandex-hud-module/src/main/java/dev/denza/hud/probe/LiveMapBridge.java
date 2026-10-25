package dev.denza.hud.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import dev.denza.adaslink.AdasGateSnapshot;
import dev.denza.adaslink.BootIdentity;
import dev.denza.adaslink.DryRunRecorder;
import dev.denza.adaslink.FixFreshness;
import dev.denza.adaslink.Json;
import dev.denza.adaslink.LiveSessionLease;
import dev.denza.adaslink.OfflineMapGate;
import dev.denza.adaslink.Pb;
import dev.denza.adaslink.RoadNetwork;
import dev.denza.adaslink.YandexRoute;
import dev.denza.adaslink.Topics;
import dev.denza.adaslink.stocknative.StockTransport;
import dev.denza.adaslink.yandexnative.PublicRoadEvidence;
import dev.denza.adaslink.stocknative.NativeEnvironment;
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
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** One arm, one factory sender and one owned transport. No legacy packet or ACK policy. */
final class LiveMapBridge {
    static final long REFRESH_MS=250;
    static final int RESTART_LIMIT=8;
    static final String ARM="denza-adas-live-arm.json",STOP="denza-adas-live-stop.json",LINEAGE="denza-adas-live-lineage.json",CONSUMER_ID="denza-adas-consumer-id.json";
    static final long DRAIN_MS=6000;
    private final Context context;
    private final Handler worker;
    private final String consumer,boot,process=UUID.randomUUID().toString();
    private final DryRunRecorder audit;
    private final java.util.function.LongSupplier wallClock;
    private final Set<String> attempted=new HashSet<>();
    private volatile AdasFeed.Snapshot published=AdasFeed.DISABLED;
    private volatile LiveSessionLease arm;
    private volatile OfflineMapGate.Subject sourceSubject;
    private volatile OfflineMapGate.Snapshot offlineSnapshot;
    private long sourceEpoch=-1,activeEpoch=-1,lastSend=-1,lastObservation=-1;
    private volatile boolean finished=true;
    private String state="DISARMED",reason="Сеанс передачи не включён",lastArmId,stopReason,stopDetail,fault,lastPrerequisite;
    private long prerequisiteChanges;
    private AdasGateSnapshot.Tracker gates;
    private volatile Inputs inputs;
    private LiveMapTransport transport;
    private AdasPassiveClient answers,coreClients;
    private ProductNativeEnvironment environment;
    private CallbackQueue callbacks;
    private volatile AndroidStockRuntime runtime;
    private NativeMapSession session;
    private NativeTransportAdmission admission;
    private List<String> lineageProcesses=new ArrayList<>();
    private NativeNavigationInput latest;
    private YandexRoute retainedRoute;
    private long retainedRouteEpoch=-1;
    private volatile double gpsSpeedKmh=Double.NaN;
    private volatile long gpsSpeedAt=-1;
    private OfflineMapGate.Subject publishedSubject;
    private long publishedGeneration=-1;
    private final Object handoff=new Object();
    private Pending pending;
    private long coalesced;
    private volatile long sourceGeneration;
    private long deliveredSourceGeneration=-1;
    private volatile Pending locationInput;
    private boolean draining,drainArrival,drainIdle,drainTunnel,drainIncidents,drainReady;
    private int drainArrivals;
    private long drainDeadline=-1,vehicleReadSequence=-1,oddRequestedEpoch=-1;
    private long oddAttemptEpoch=-1,oddNextAttemptAt=-1;
    private int oddAttempts;
    private long speedSequence=-1,speedPolicyPath=-1;
    private String dataUnavailable;
    private static final class Inputs {
        LiveSessionLease arm; String armFailure,isolationFailure,gatesFailure;
        int stockState;long readAt,isolationStart=-1;AdasGateSnapshot.Snapshot gates;boolean server,replies;
    }
    private static final class Pending {
        final NativeNavigationInput input;final FixFreshness.Sample fix;final long generation,fixAgeAtInput,inputAt;
        Pending(NativeNavigationInput input,FixFreshness.Sample fix,long generation){this.input=input;this.fix=fix;this.generation=generation;fixAgeAtInput=input.fixAgeMs;inputAt=input.monotonicMs;}
    }
    LiveMapBridge(Context context,Handler worker)throws IOException{this(context,worker,bootIdentity(context));}
    LiveMapBridge(Context context,Handler worker,String boot)throws IOException{this(context,worker,boot,System::currentTimeMillis);}
    LiveMapBridge(Context context,Handler sender,String boot,java.util.function.LongSupplier wall)throws IOException{
        this.context=context.getApplicationContext();this.worker=new Handler(sender.getLooper());this.wallClock=wall;
        this.boot=LiveSessionLease.uuid(boot)?boot:null;consumer=consumerIdentity();
        audit=new DryRunRecorder(new AdasBridge.RotatingWriter(file("denza-adas-live.jsonl"),AdasBridge.LIVE_GENERATIONS),false);
        worker.post(poll);worker.postDelayed(refresher,REFRESH_MS);
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

    AdasFeed.Snapshot status(){return published;}
    private File file(String name){return new File(context.getFilesDir(),name);}
    synchronized void sourceRoute(OfflineMapGate.Subject subject){
        if(subject!=null&&subject.epoch<sourceEpoch)return;
        if(subject!=null)sourceEpoch=subject.epoch;
        if(!java.util.Objects.equals(subject,sourceSubject)){sourceGeneration++;AndroidStockRuntime owned=runtime;if(owned!=null)owned.inputGeneration(sourceGeneration);}
        sourceSubject=subject;
    }
    void offlineMap(OfflineMapGate.Snapshot snapshot){offlineSnapshot=snapshot;}
    private OfflineMapGate.Decision offline(long now){return OfflineMapGate.check(sourceSubject,offlineSnapshot,now);}
    private final Runnable poll=new Runnable(){public void run(){
        try{tick();}catch(Exception error){stop("Ошибка сеанса",error.toString());}
        finally{worker.postDelayed(this,1000);}
    }};
    private final Runnable refresher=new Runnable(){public void run(){
        try{if(arm!=null&&!finished){refresh(SystemClock.elapsedRealtime());reconcile();}}
        catch(RuntimeException error){stop("Ошибка сигналов машины",error.toString());}
        finally{worker.postDelayed(this,REFRESH_MS);}
    }};
    private void tick()throws IOException{
        long now=SystemClock.elapsedRealtime();
        Map<String,Object> heartbeat=Json.object();heartbeat.put("schema","denza-adas-consumer-v1");
        heartbeat.put("sender","stocknative");heartbeat.put("bootId",boot);heartbeat.put("consumerSession",consumer);
        heartbeat.put("processSession",process);heartbeat.put("elapsedMs",now);
        RouteSnapshot.writeAtomic(file("denza-adas-consumer.json"),heartbeat);
        File armed=file(ARM);
        if(!armed.isFile()){
            if(arm!=null&&!finished)stop("Сеанс отключён","arm file removed");
            if(arm==null){state="DISARMED";reason="Сеанс передачи не включён";}
        }else{
            LiveSessionLease incoming;
            try{incoming=LiveSessionLease.read(read(armed,16384),boot,consumer,now);}
            catch(Exception invalid){if(!finished)stop("Разрешение сеанса истекло",invalid.getMessage());else{state="BLOCKED";reason=invalid.getMessage();}publish();return;}
            if(arm==null||!incoming.armId.equals(arm.armId)){
                if(!finished)stop("Сеанс заменён","new arm");
                if(!begin(incoming)){publish();return;}
            }else if(!arm.same(incoming)&&!finished)stop("Параметры сеанса изменились","arm content changed");
            if(!finished){refresh(now);readStopRequest();reconcile();}
        }
        publish();
    }
    private boolean begin(LiveSessionLease incoming){
        if(attempted.contains(incoming.armId)||attempted.size()>=64){state="BLOCKED";reason="Сеанс уже завершён";return false;}
        Map<String,Object> lineage=readLineage(incoming.armId);
        List<String> processes=new ArrayList<>();
        if(lineage!=null){
            if(lineage.get("endedReason")!=null){state="BLOCKED";reason="Сеанс уже завершён";return false;}
            Object list=lineage.get("processes");if(list instanceof List)for(Object item:(List<?>)list)if(item instanceof String)processes.add((String)item);
            // A crashed producer cannot establish which route the receiver retains. Require a fresh arm.
            if(!processes.isEmpty()){state="BLOCKED";reason="После перезапуска требуется новое разрешение";return false;}
        }
        processes.add(process);lineageProcesses=processes;arm=incoming;lastArmId=incoming.armId;attempted.add(incoming.armId);
        session=null;runtime=null;environment=null;
        finished=false;fault=null;stopReason=null;stopDetail=null;activeEpoch=-1;inputs=null;publishedSubject=null;publishedGeneration=-1;deliveredSourceGeneration=-1;
        draining=false;drainDeadline=-1;vehicleReadSequence=-1;resetCityRequest();speedSequence=-1;speedPolicyPath=-1;dataUnavailable=null;
        state="WAITING";reason="Ожидание сигналов машины и ADAS";
        gates=new AdasGateSnapshot.Tracker(boot,consumer,LiveSessionLease.INPUT_TTL_MS);
        final LiveSessionLease owner=incoming;final CallbackQueue queue=new CallbackQueue(worker::post);callbacks=queue;
        admission=new NativeTransportAdmission(topic->arm==owner&&!finished&&finalAdmission(topic));
        final NativeTransportAdmission ownedAdmission=admission;
        transport=new LiveMapTransport(context,worker,event->{if(arm==owner&&!finished){if(!"server_connected".equals(event))fault=event;note("server",event);}},audit,topic->ownedAdmission.allow(topic));
        answers=new AdasPassiveClient(context,new AdasPassiveClient.Sink(){
            public boolean enqueue(Runnable task){return enqueue(-1,task);}
            public boolean enqueue(long topic,Runnable task){return arm==owner&&!finished&&queue.enqueue(true,()->{if(arm==owner&&!finished)task.run();});}
            public void state(String event,String detail){if(arm!=owner||finished)return;note("client",event+":"+detail);
                if(event.equals("binder_disconnected")||event.equals("null_binding")||event.equals("bind_failed")||event.equals("rebind_exhausted")||event.equals("client_setup_error"))fault="ADAS answers "+event;}
            public void event(long topic,long timestamp,long receivedAt,byte[] payload){
                if(arm!=owner||finished)return;
                try{DriveEvidence.passive("live",owner.armId,topic,timestamp,receivedAt,payload);}catch(RuntimeException ignored){}
                if(topic==Topics.PATH_SELECT_CONFIRM&&payload!=null&&session!=null)session.pathSelected(payload);
                if(topic==0x0001000D000D0002L)resetCityRequest();
                if(topic==0x0001000D000D0001L)requestCities();
            }
            public void oddRegionCodes(List<Integer> codes){if(arm==owner&&!finished&&!draining&&session!=null&&hardwareFailure(SystemClock.elapsedRealtime())==null)session.cityCodes(codes);}
        },true);
        coreClients=new AdasPassiveClient(context,new AdasPassiveClient.Sink(){
            public boolean enqueue(Runnable task){return arm==owner&&!finished&&queue.enqueue(true,()->{if(arm==owner&&!finished)task.run();});}
            public void state(String event,String detail){if(arm!=owner||finished)return;note("coreClient",event+":"+detail);
                if("subscribed".equals(event)&&session!=null)session.clientInitialized(true);
                if(event.equals("binder_disconnected")||event.equals("bind_failed")||event.equals("rebind_exhausted")||event.equals("client_setup_error"))fault="core client "+event;}
            public void event(long topic,long timestamp,long receivedAt,byte[] payload){if(arm==owner&&!finished&&session!=null)session.serviceEvent(topic);}
        },false,true);
        transport.start();answers.start();coreClients.start();
        note("armed",incoming.armId);writeLineageQuiet();return true;
    }
    private long epoch(){if(transport==null||answers==null)return -1;long a=transport.generation(),b=answers.epoch();return a<=0||a>0x7fffffffL||b<=0||b>0xffffffffL?-1:a<<32|b;}
    private void refresh(long now){
        LiveSessionLease checked=arm;if(checked==null||finished)return;
        Inputs in=new Inputs();in.arm=checked;
        try{if(!checked.same(LiveSessionLease.read(read(file(ARM),16384),boot,consumer,now)))in.armFailure="arm content changed";}catch(Exception error){in.armFailure=error.toString();}
        try{in.stockState=context.getPackageManager().getApplicationEnabledSetting(LiveSessionLease.STOCK_PACKAGE);}catch(RuntimeException error){in.stockState=-1;}
        try{in.isolationStart=checked.isolationStart(read(file("denza-adas-stock-isolation.json"),16384),SystemClock.elapsedRealtime());}catch(Exception error){in.isolationFailure=error.toString();}
        try{in.gates=gates.read(read(file("denza-adas-gates.json"),16384),SystemClock.elapsedRealtime());
            if(!in.gates.valid)in.gatesFailure=in.gates.reason;
            else if(!checked.readerSession.equals(in.gates.readerSession))in.gatesFailure="reader session differs";
            else if(!in.gates.rawValues.containsKey(AdasGateSnapshot.TIP_ISLAND_SUPPORT))in.gatesFailure="TIP_ISLAND_SUPPORT unavailable";
        }catch(Exception error){in.gatesFailure=error.toString();}
        in.server=transport!=null&&transport.ready();in.replies=answers!=null&&answers.liveAnswersReady()&&coreClients!=null&&coreClients.binderAlive();
        in.readAt=SystemClock.elapsedRealtime();if(arm==checked)inputs=in;
        if(in.gates!=null&&in.gates.valid&&in.gates.sequence!=vehicleReadSequence){
            vehicleReadSequence=in.gates.sequence;Map<String,Object> row=Json.object();row.put("armId",checked.armId);
            row.put("sequence",vehicleReadSequence);row.put("vehicleSpeed",in.gates.vehicleSpeed);row.put("noaQuit",in.gates.noaQuit);
            audit.note("nativeVehicleRead",row,in.readAt);
        }
        updateVehicleSpeed();
    }
    private String hardwareFailure(long now){
        Inputs in=inputs;
        if(finished||arm==null)return "session closed";
        if(fault!=null)return fault;
        if(in==null||in.arm!=arm)return "readiness not read";
        if(in.armFailure!=null)return "arm: "+in.armFailure;
        if(now<in.readAt||now-in.readAt>=LiveSessionLease.INPUT_TTL_MS||arm.expiresAt<=now)return "readiness expired";
        if(in.stockState!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)return "stock map not disabled-user";
        if(in.isolationFailure!=null)return "stock isolation: "+in.isolationFailure;
        if(in.isolationStart>now||now-in.isolationStart>=LiveSessionLease.INPUT_TTL_MS)return "stock isolation stale";
        if(in.gatesFailure!=null)return "vehicle gates: "+in.gatesFailure;
        AdasGateSnapshot.Snapshot g=in.gates;
        if(g==null||g.startedAtElapsedMs>now||now-g.startedAtElapsedMs>=LiveSessionLease.INPUT_TTL_MS)return "vehicle gates stale";
        if(!Boolean.TRUE.equals(g.dnpService))return "dnpService off";
        if(!Boolean.TRUE.equals(g.dnpSwitchRaw))return "dnpSwitch off";
        if(!Boolean.TRUE.equals(g.noSleep)||Boolean.TRUE.equals(g.downtime))return "noSleep off";
        if(!in.server)return "map service not ready";
        if(!in.replies)return "ADAS answer subscriptions not ready";
        return null;
    }
    private boolean finalAdmission(long topic){
        long now=SystemClock.elapsedRealtime();
        if(hardwareFailure(now)!=null||activeEpoch!=epoch())return false;
        // Status is the original process-wide timer; every path-owned payload must still
        // belong to the input subject whose callback produced it at this final TX boundary.
        if(topic!=Topics.NAVIGATION_STATUS&&runtime!=null&&!runtime.currentInput(runtime.inputGeneration()))return false;
        if(draining)return NativeTransportAdmission.terminal(topic);
        // The original idle/arrival status and reset callbacks may finish after route withdrawal.
        // Route/location/lookahead egress stays closed until the replacement subject is admitted.
        boolean withdrawn=sourceSubject!=null&&sourceSubject.routeId==null;
        boolean arrived=latest!=null&&latest.finished;
        if(session!=null&&!session.navigating()&&(withdrawn||arrived))
            return NativeTransportAdmission.terminal(topic);
        if(topic!=Topics.NAVIGATION_STATUS&&topic!=Topics.ROUTE&&topic!=Topics.PATH_SELECTED&&!dynamicFresh(now))return false;
        return offline(now).ready&&java.util.Objects.equals(publishedSubject,sourceSubject);
    }
    private void reconcile(){
        if(finished)return;long now=SystemClock.elapsedRealtime();String failure=hardwareFailure(now);
        if(!java.util.Objects.equals(failure,lastPrerequisite)){lastPrerequisite=failure;prerequisiteChanges++;note("prerequisite",failure==null?"all prerequisites hold":failure);}
        if(session!=null){
            Inputs in=inputs;if(in!=null&&in.gates!=null&&in.gates.valid)runtime.inputScope(publishedGeneration,()->session.gates(Boolean.TRUE.equals(in.gates.dnpService),Boolean.TRUE.equals(in.gates.dnpSwitchRaw),Boolean.TRUE.equals(in.gates.noSleep)));
            if(fault!=null||inputs!=null&&inputs.armFailure!=null||inputs!=null&&inputs.stockState!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER||activeEpoch!=epoch()){
                stop("Сеанс передачи остановлен",failure==null?"transport epoch changed":failure);return;
            }
            if(draining){
                if(failure!=null){stop("Остановка без завершения передачи",failure);return;}
                if(now>=drainDeadline){stop("Остановка без завершения передачи","original terminal output deadline");return;}
                state="DRAINING";reason="Завершение штатной передачи";return;
            }
            if(!dynamicFresh(now))session.invalidateLocation();
            if(lastObservation>=0&&now-lastObservation>10000){stop("Нет данных Яндекса","observation watchdog");return;}
            state=failure==null&&dynamicFresh(now)?"LIVE":"PAUSED";reason=failure!=null?pauseLabel(failure):dynamicFresh(now)?"Передача через штатный отправитель":"Нет свежих данных положения или скорости";
        }
        if(failure!=null){if(session==null){state="WAITING";reason=pauseLabel(failure);}return;}
        OfflineMapGate.Decision decision=offline(now);
        if(!decision.ready){if(session!=null&&publishedSubject!=null&&!java.util.Objects.equals(publishedSubject,sourceSubject)){
                // Keep the same core across a verified route replacement; admission is closed until the new subject is ready.
                reason=decision.reason;state="PAUSED";
            }else{reason=decision.reason;state="WAITING";}return;}
        if(session==null)startSender();
        requestCities();
    }
    private void requestCities(){
        long now=SystemClock.elapsedRealtime();
        if(finished||draining||session==null||answers==null||hardwareFailure(now)!=null||activeEpoch!=epoch()||oddRequestedEpoch==activeEpoch)return;
        if(oddAttemptEpoch!=activeEpoch){oddAttemptEpoch=activeEpoch;oddAttempts=0;oddNextAttemptAt=-1;}
        if(oddAttempts>=3||now<oddNextAttemptAt)return;
        oddAttempts++;oddNextAttemptAt=now+5000;
        if(answers.requestOddRegionCodes()==0)oddRequestedEpoch=activeEpoch;
    }
    private void resetCityRequest(){oddRequestedEpoch=oddAttemptEpoch=oddNextAttemptAt=-1;oddAttempts=0;}
    private void startSender(){
        final LiveSessionLease owner=arm;final LiveMapTransport ownedTransport=transport;final AdasPassiveClient ownedAnswers=coreClients;
        final AndroidStockRuntime ownedRuntime=new AndroidStockRuntime(error->worker.post(()->{if(arm==owner&&!finished)stop("Ошибка штатного отправителя",error.toString());}),
                missing->worker.post(()->{if(arm==owner&&!finished&&!dynamicFresh(SystemClock.elapsedRealtime())){dataUnavailable=missing.getMessage();if(session!=null)session.invalidateLocation();state="PAUSED";reason=dataUnavailable;}}));
        synchronized(this){ownedRuntime.inputGeneration(sourceGeneration);runtime=ownedRuntime;}
        ProductNativeEnvironment env=new ProductNativeEnvironment(context,()->session!=null&&session.navigating(),()->inputs==null?null:inputs.gates,()->{
            AdasGateSnapshot.Snapshot snapshot=inputs==null?null:inputs.gates;
            Double speed=snapshot==null?null:snapshot.vehicleSpeedKmh(SystemClock.elapsedRealtime(),1500);
            if(speed==null)throw new NativeEnvironment.DataUnavailable("vehicle speed unavailable");return speed;
        },new ProductNativeEnvironment.ClientOperations(){
            public void startClientOrSubscribe(Map<Long,Long[]> values){onWorker(()->{ownedAnswers.nativeStart(values);return null;});}
            public void stopClient(Map<Long,Long[]> values){onWorker(()->{ownedAnswers.nativeStop(values);return null;});}
            public void unSubscribe(Map<Long,Long[]> values){onWorker(()->{ownedAnswers.nativeUnsubscribe(values);return null;});}
        });
        if(!env.ready())throw new IllegalStateException("actual AutoType unavailable");
        environment=env;note("environment",Json.write(env.sourceStatus()));
        StockTransport wire=new StockTransport(){
            public int fire(long topic,byte[] payload){final long generation=ownedRuntime.inputGeneration();return onWorker(()->ownedRuntime.inputScope(generation,()->{
                if(arm!=owner||finished||transport!=ownedTransport||runtime!=ownedRuntime||topic!=Topics.NAVIGATION_STATUS&&!ownedRuntime.currentInput(generation))return -203;
                if(draining&&topic==Topics.NAVIGATION_STATUS){long status=navigationStatus(payload);if(status!=100&&status!=110)return -203;}
                int result=ownedTransport.fire(topic,payload,SystemClock.elapsedRealtime());
                if(result==0&&draining)terminalSent(topic,payload);
                if(result!=0&&result!=-203){fault="map fire result "+result;worker.post(()->{if(arm==owner&&!finished)stop("Ошибка отправки",fault);});}
                return result;
            }));}
            public int startService(long topic){return onWorker(()->arm==owner&&hardwareFailure(SystemClock.elapsedRealtime())==null?ownedTransport.startService(topic):-203);}
            public int stopService(long topic){return onWorker(()->ownedTransport.stopService(topic));}
            public int request(long topic,byte[] payload,boolean response){throw new UnsupportedOperationException("native request not present in selected sender closure");}
        };
        activeEpoch=epoch();publishedSubject=sourceSubject;publishedGeneration=sourceGeneration;
        session=new NativeMapSession(runtime,runtime,env,wire,this::nextPathId,value->audit.note("nativeInputPolicy",value,SystemClock.elapsedRealtime()),owner.frameMode,readPublicRoadEvidence());
        AdasGateSnapshot.Snapshot g=inputs.gates;session.start(Boolean.TRUE.equals(g.dnpService),Boolean.TRUE.equals(g.dnpSwitchRaw),Boolean.TRUE.equals(g.noSleep));
        session.clientInitialized(true);
        if(!transport.servicesOffered()){stop("Сервисы отправки не запущены","own offers incomplete");return;}
        state="LIVE";reason="Передача через штатный отправитель";
        updateVehicleSpeed();
    }
    private PublicRoadEvidence readPublicRoadEvidence(){
        // Packaged with this APK: no mutable on-device file can widen ownership coverage.
        try(java.io.InputStream input=context.getAssets().open("denza-hud/public-road-evidence.json")){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;
            while((n=input.read(chunk))!=-1){
                if(bytes.size()+n>2000000)throw new IOException("public-road catalog exceeds 2 MB");
                bytes.write(chunk,0,n);
            }
            PublicRoadEvidence evidence=PublicRoadEvidence.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            note("publicRoadEvidence",Json.write(evidence.provenance()));return evidence;
        }catch(IOException|IllegalArgumentException error){
            note("publicRoadEvidenceUnavailable",error.toString());return PublicRoadEvidence.EMPTY;
        }
    }
    private void updateVehicleSpeed(){
        if(finished||draining||session==null||!session.navigating()||inputs==null||inputs.gates==null)return;
        OfflineMapGate.Subject subject=sourceSubject;
        if(!java.util.Objects.equals(publishedSubject,subject)||subject==null||!session.acceptsRoute(subject.routeId,subject.epoch,subject.geometrySha256))return;
        AdasGateSnapshot.Snapshot snapshot=inputs.gates;Double value=snapshot.vehicleSpeedKmh(SystemClock.elapsedRealtime(),1500);
        if(value!=null&&(snapshot.sequence!=speedSequence||session.pathId()!=speedPolicyPath)){
            long generation=publishedGeneration;speedSequence=snapshot.sequence;speedPolicyPath=session.pathId();runtime.inputScope(generation,()->session.vehicleSpeedUpdated(value.floatValue()));
        }
    }
    private <T>T onWorker(Callable<T> task){
        try{if(Looper.myLooper()==worker.getLooper())return task.call();
            FutureTask<T> result=new FutureTask<>(task);if(!worker.post(result))throw new IllegalStateException("sender worker stopped");
            try{return result.get(3000,TimeUnit.MILLISECONDS);}catch(Exception error){result.cancel(false);throw error;}
        }catch(Exception error){throw new IllegalStateException("sender transport dispatch",error);}
    }
    private long nextPathId(){
        SharedPreferences preferences=context.getSharedPreferences("denza-native-path-ids",Context.MODE_PRIVATE);
        long previous=preferences.getLong("last",0);if(previous<0||previous==Long.MAX_VALUE)throw new IllegalStateException("path identity exhausted");
        long next=Math.max(previous+1,wallClock.getAsLong());if(next<=0||!preferences.edit().putLong("last",next).commit())throw new IllegalStateException("path identity persistence");return next;
    }
    void observe(NativeNavigationInput original,FixFreshness.Sample fix,RoadNetwork unusedRoads){
        NativeNavigationInput copy=original.copy();boolean post;
        final Pending observation;
        synchronized(this){if(!matchesSubject(copy,sourceSubject))return;observation=new Pending(copy,fix,sourceGeneration);}
        locationInput=observation;
        synchronized(handoff){if(pending!=null){coalesced++;if(copy.route==null&&pending.input.route!=null&&java.util.Objects.equals(copy.routeId,pending.input.routeId)&&copy.routeEpoch==pending.input.routeEpoch)copy.route=pending.input.route;}post=pending==null;pending=observation;}
        if(post)worker.post(this::observePending);
    }
    private void observePending(){
        Pending next;synchronized(handoff){next=pending;pending=null;}if(next==null)return;
        NativeNavigationInput o=next.input;final OfflineMapGate.Subject subject;
        synchronized(this){subject=sourceSubject;if(next.generation!=sourceGeneration||!matchesSubject(o,subject))return;}
        latest=o;lastObservation=SystemClock.elapsedRealtime();
        if(o.route!=null){retainedRoute=o.route;retainedRouteEpoch=o.routeEpoch;}
        if(o.routeId==null){retainedRoute=null;retainedRouteEpoch=-1;}
        if(o.route==null&&retainedRoute!=null&&retainedRouteEpoch==o.routeEpoch&&java.util.Objects.equals(retainedRoute.routeId,o.routeId))o.route=retainedRoute;
        if(next.fix!=null)o.fixAgeMs=next.fix.ageMs(lastObservation,wallClock.getAsLong());
        if(o.rawAtElapsedMs<0||o.rawAtElapsedMs>lastObservation||lastObservation-o.rawAtElapsedMs>1500){o.rawLon=Double.NaN;o.rawLat=Double.NaN;}
        if(!o.simulated&&"FINE".equals(o.locationClass)&&o.fixAgeMs>=0&&o.fixAgeMs<=1500&&Double.isFinite(o.speedMps)&&o.speedMps>=0){gpsSpeedKmh=o.speedMps*3.6;gpsSpeedAt=lastObservation-o.fixAgeMs;}
        if(finished||draining)return;
        try{refresh(lastObservation);reconcile();if(finished||session==null||next.generation!=sourceGeneration)return;
            // Refresh performs real file/Binder reads. Its snapshot is newer than the receipt
            // timestamp, so admit against the consumption clock and age the original anchors.
            final long admissionNow=SystemClock.elapsedRealtime();
            o.fixAgeMs=inputAge(next,admissionNow);
            if(o.rawAtElapsedMs<0||o.rawAtElapsedMs>admissionNow||admissionNow-o.rawAtElapsedMs>1500){o.rawLon=Double.NaN;o.rawLat=Double.NaN;}
            runtime.inputScope(next.generation,()->{
                if(next.generation!=sourceGeneration)return;
                if(deliveredSourceGeneration!=next.generation){session.sourceGenerationChanged();deliveredSourceGeneration=next.generation;}
                if(o.routeId==null||"off".equals(o.mode)||o.finished){session.observe(o);return;}
                if(!offline(admissionNow).ready||hardwareFailure(admissionNow)!=null)return;
                if(!locationFresh(admissionNow)||!o.positionOnRoute)session.invalidateLocation();
                if(environment!=null&&locationFresh(admissionNow)){double[] sdk=session.sdkCoordinate(o.routeId,o.lon,o.lat);if(sdk!=null)environment.location(sdk[0],sdk[1]);}
                session.observe(o);
                if(o.route!=null&&session.acceptsRoute(o.routeId,o.routeEpoch,o.offlineGeometrySha256)){publishedSubject=subject;publishedGeneration=next.generation;}
                updateVehicleSpeed();
                if(locationFresh(admissionNow))dataUnavailable=null;
            });
            if(session.blocked()!=null){state="BLOCKED";reason=session.blocked();}
            publish();
        }catch(NativeEnvironment.DataUnavailable missing){dataUnavailable=missing.getMessage();session.invalidateLocation();state="PAUSED";reason=dataUnavailable;}
        catch(Exception error){stop("Ошибка входных данных",error.toString());}
    }
    private static boolean matchesSubject(NativeNavigationInput o,OfflineMapGate.Subject subject){return subject!=null&&subject.epoch==o.routeEpoch&&java.util.Objects.equals(subject.routeId,o.routeId)&&java.util.Objects.equals(subject.geometrySha256,o.offlineGeometrySha256);}
    private boolean locationFresh(long now){
        Pending current=locationInput;if(current==null)return false;NativeNavigationInput o=current.input;
        OfflineMapGate.Subject subject=sourceSubject;
        if(subject==null||subject.epoch!=o.routeEpoch||!java.util.Objects.equals(subject.routeId,o.routeId)
                ||!java.util.Objects.equals(subject.geometrySha256,o.offlineGeometrySha256)||o.simulated||!"FINE".equals(o.locationClass))return false;
        long age=inputAge(current,now);
        return age>=0&&age<=1500&&o.rawAtElapsedMs>=0&&now>=o.rawAtElapsedMs&&now-o.rawAtElapsedMs<=1500
                &&coordinate(o.rawLon,o.rawLat)&&coordinate(o.lon,o.lat)
                &&Double.isFinite(o.speedMps)&&o.speedMps>=0&&Double.isFinite(o.heading)
                &&(session==null||session.supportsCoordinates(o.routeId,o.lon,o.lat,o.rawLon,o.rawLat));
    }
    private long inputAge(Pending input,long now){return input.fix!=null?input.fix.ageMs(now,wallClock.getAsLong())
            :now<input.inputAt||input.fixAgeAtInput<0||input.fixAgeAtInput>1500?Long.MAX_VALUE:input.fixAgeAtInput+now-input.inputAt;}
    private static boolean coordinate(double lon,double lat){return Double.isFinite(lon)&&Double.isFinite(lat)&&lon>=-180&&lon<=180&&lat>=-90&&lat<=90;}
    private boolean dynamicFresh(long now){return locationFresh(now)&&inputs!=null&&inputs.gates!=null&&inputs.gates.vehicleSpeedKmh(now,1500)!=null;}
    private void readStopRequest(){
        if(finished||draining||arm==null||!file(STOP).isFile())return;
        try{Map<String,Object> request=LiveSessionLease.object(read(file(STOP),4096));long now=SystemClock.elapsedRealtime();
            if(!"denza-adas-live-stop-v1".equals(request.get("schema"))||!boot.equals(request.get("bootId"))
                    ||!consumer.equals(request.get("consumerSession"))||!arm.armId.equals(request.get("armId"))
                    ||!arm.readerSession.equals(request.get("readerSession")))return;
            Object stamp=request.get("requestedAtElapsedMs");if(!(stamp instanceof Long)&&!(stamp instanceof Integer))return;long at=((Number)stamp).longValue();
            if(at<arm.issuedAt||at>now||now-at>10000)return;
            if(hardwareFailure(now)!=null||session==null){stop("Сеанс завершён","explicit stop without active sender");return;}
            draining=true;drainDeadline=Math.min(arm.expiresAt,now+DRAIN_MS);drainIdle=drainTunnel=drainIncidents=drainReady=false;drainArrivals=0;
            drainArrival=session.beginGracefulStop();state="DRAINING";reason="Завершение штатной передачи";note("draining",drainArrival?"original arrival completion":"original cancellation completion");
            Object barrier=new Object();runtime.post(dev.denza.adaslink.stocknative.StockRuntime.Queue.MMT,barrier,null,0,
                    ()->worker.post(()->{if(draining&&!finished)drainReady=true;}));
        }catch(IOException|IllegalArgumentException ignored){/* An unrelated/stale request cannot authorize output. */}
    }
    private static long navigationStatus(byte[] payload){
        try{Map<?,?> body=(Map<?,?>)Pb.NAVIGATION_STATUS_NOTIFY.decode(payload).get("navigationStatusLinkInfoNotifyStruct");return ((Number)body.get("navigationStatus")).longValue();}
        catch(RuntimeException malformed){return -1;}
    }
    private void terminalSent(long topic,byte[] payload){
        if(topic==Topics.SD_TUNNEL)drainTunnel=true;
        if(topic==Topics.SD_TRAFFIC_INCIDENT)drainIncidents=true;
        if(topic==Topics.NAVIGATION_STATUS){long status=navigationStatus(payload);if(status==110)drainArrivals++;
            // The original reset runs after any queued arrival receipt. An older idle timer
            // cannot complete a drain before that receipt has reached the MMT queue.
            if(status==100&&drainReady&&drainTunnel&&drainIncidents)drainIdle=true;
        }
        if(drainIdle&&drainTunnel&&drainIncidents)worker.post(()->{if(draining&&!finished)stop("Сеанс завершён","original terminal output sent");});
    }
    static String pauseLabel(String detail){
        if(detail==null)return "Ожидание данных";
        if(detail.contains("dnpSwitch")||detail.contains("dnpService"))return "NOA выключен";
        if(detail.contains("noSleep"))return "ADAS в режиме сна";
        if(detail.contains("isolation"))return "Проверка штатной карты";
        if(detail.contains("service")||detail.contains("subscriptions"))return "Ожидание сервисов ADAS";
        return "Нет свежих сигналов машины";
    }
    private void stop(String label,String detail){
        if(stopReason==null){stopReason=label;stopDetail=detail;note("stopping",label+":"+detail);}
        if(finished)return;
        // Revoke admission before disposing anything: even a running old Runnable cannot send.
        finished=true;if(admission!=null)admission.close();
        try{if(session!=null)session.close();}catch(RuntimeException error){note("shutdown",error.toString());}
        finally{if(runtime!=null)runtime.close();if(callbacks!=null)callbacks.close();
            if(answers!=null){answers.stopAdmission();answers.close();}if(coreClients!=null){coreClients.stopAdmission();coreClients.close();}if(transport!=null){lastSend=Math.max(lastSend,transport.lastActualSend());transport.close();}}
        state="STOPPED";reason=stopReason;note("stopped",stopReason+":"+stopDetail);writeLineageQuiet();try{publish();}catch(IOException error){Log.w(AdasBridge.TAG,"native state write",error);}
    }
    private AdasFeed.Snapshot liveSnapshot(){
        boolean active=!finished&&session!=null&&transport!=null&&!transport.isClosed();
        return new AdasFeed.Snapshot(active?AdasFeed.Mode.LIVE:AdasFeed.Mode.DISABLED,transport==null?lastSend:Math.max(lastSend,transport.lastActualSend()),active?session.pathId():-1,active&&session.navigating(),active?session.confirmedPathId():-1,state,reason);
    }
    private Map<String,Object> readLineage(String id){
        try{Map<String,Object> value=LiveSessionLease.object(read(file(LINEAGE),16384));return "denza-adas-live-lineage-v1".equals(value.get("schema"))&&java.util.Objects.equals(boot,value.get("bootId"))&&consumer.equals(value.get("consumerSession"))&&id.equals(value.get("armId"))?value:null;}catch(Exception error){return null;}
    }
    private void writeLineageQuiet(){
        if(lastArmId==null||boot==null)return;Map<String,Object> value=Json.object();value.put("schema","denza-adas-live-lineage-v1");value.put("sender","stocknative");value.put("bootId",boot);value.put("consumerSession",consumer);value.put("armId",lastArmId);value.put("processes",new ArrayList<Object>(lineageProcesses));value.put("heldPathId",session==null?-1:session.pathId());value.put("endedReason",finished?stopReason:null);
        try{RouteSnapshot.writeAtomic(file(LINEAGE),value);}catch(IOException error){fault="lineage persistence unavailable";}
    }
    private void publish()throws IOException{
        published=liveSnapshot();Map<String,Object> value=Json.object();value.put("schema","denza-adas-live-state-v1");value.put("sender","stocknative");
        value.put("bootId",boot);value.put("consumerSession",consumer);value.put("processSession",process);value.put("armId",lastArmId);value.put("armProcesses",(long)lineageProcesses.size());value.put("elapsedMs",SystemClock.elapsedRealtime());value.put("state",state);value.put("reason",reason);value.put("stopReason",stopReason);value.put("stopDetail",stopDetail);
        boolean serverClosed=transport==null||transport.isClosed(),answersClosed=(answers==null||answers.isClosed())&&(coreClients==null||coreClients.isClosed());
        value.put("coordinateFrameMode",arm==null?null:arm.frameMode.name());value.put("coordinateFrame",session==null||session.frame()==null?null:session.frame().name());value.put("coordinateRouteOrdinal",session==null?-1:session.frameOrdinal());
        value.put("quiescent",serverClosed&&answersClosed&&(runtime==null||!runtime.active()));value.put("serverClosed",serverClosed);value.put("answersClosed",answersClosed);value.put("lastActualSendElapsedMs",published.lastSendMs);value.put("pathId",published.pathId);value.put("confirmedPathId",published.confirmedPathId);value.put("heldPathId",published.pathId);value.put("guidanceActive",session!=null&&session.navigating());value.put("prerequisite",lastPrerequisite);value.put("prerequisiteChanges",prerequisiteChanges);value.put("callbacks",callbacks==null?null:callbacks.stats());
        OfflineMapGate.Decision offline=offline(SystemClock.elapsedRealtime());value.put("offlineReady",offline.ready);value.put("offlineCode",offline.code);value.put("offlineReason",offline.reason);synchronized(handoff){value.put("observationsCoalesced",coalesced);}
        RouteSnapshot.writeAtomic(file("denza-adas-live-state.json"),value);
    }
    private void note(String kind,String detail){try{Map<String,Object> row=Json.object();row.put("detail",detail);row.put("armId",lastArmId);row.put("sender","stocknative");audit.note("liveSession."+kind,row,SystemClock.elapsedRealtime());}catch(RuntimeException error){fault="journal unavailable";}}
    private static String read(File file, int maximum) throws IOException {
        if (file.length() > maximum) throw new IOException("file cap");
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024]; int count;
            while ((count = in.read(buf)) != -1) { if (count > maximum - out.size()) throw new IOException("file cap"); out.write(buf, 0, count); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
