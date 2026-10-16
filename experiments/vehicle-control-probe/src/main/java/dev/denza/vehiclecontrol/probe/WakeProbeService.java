package dev.denza.vehiclecontrol.probe;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import android.util.AtomicFile;
import android.util.Log;
import dev.denza.disharebridge.LocalAdbClient;
import org.json.*;
import org.vosk.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.*;

/** One cabin capture for wake, seat estimate and whole-utterance remote commands. No audio focus. */
public final class WakeProbeService extends Service {
    private static final String TAG="DenzaWakeProbe";
    private static final AssistantTurn TURN=new AssistantTurn();
    private static volatile WakeProbeService owner;
    private static volatile String lastCommand="";
    static void cancelCurrentTurn(){TURN.cancel();}
    private AssistantOverlay overlay;
    private volatile CommandAudio commandAudio;
    private volatile boolean resetGate;
    private final Runnable renderTurn=new Runnable(){public void run(){
        if(!running)return;
        TURN.tick(SystemClock.elapsedRealtime());
        try{overlay.render(TURN.state());}catch(RuntimeException e){TURN.cancel();overlay.hide();status="Индикатор недоступен: "+e.getMessage();Log.w(TAG,status);}
        main.postDelayed(this,100);
    }};
    private static volatile String status="Нажмите «Слушать»",lastWake="",lastSeat="",lastText="";
    private static volatile long hits,clips,decoded;
    private static volatile String phase="Микрофон выключен",heard="Ещё нет распознанных слов",decision="Ещё не было проверки фразы";
    private static volatile double[] levels=new double[6];
    private static volatile int[] sampleCounts=new int[6];
    private volatile boolean decoding;
    private final Object calibrationLock=new Object();
    private Calibration enrollment;
    private static final class Calibration {
        final int seat;final long started=SystemClock.elapsedRealtime();
        final List<double[]> samples=new ArrayList<>();
        Calibration(int seat){this.seat=seat;}
    }
    static volatile boolean active;
    private static volatile boolean ready;
    private static volatile boolean[] calibrated=new boolean[6];
    private static volatile String calibration="";
    private static volatile double level;
    private volatile boolean running;
    private volatile AudioRecord recorder;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor decoder=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1));
    private SeatProfiles profiles;
    private Model model;
    private String commandGrammar;
    private volatile VoiceCommandRoute pendingRoute;
    private volatile long pendingTurn;
    private static volatile String lastPlan="",lastExecution="";
    private boolean started;
    private long lastHit;
    static JSONObject snapshot() {
        AssistantTurn.State turn=TURN.state();
        try{return new JSONObject().put("turn_stage",turn.stage().name()).put("turn_text",turn.text()).put("turn_seat",turn.seat()).put("last_command",lastCommand).put("turn_message",turn.message()).put("last_plan",lastPlan).put("last_execution",lastExecution).put("active",active).put("ready",ready).put("phase",phase).put("heard",heard).put("decision",decision).put("clips",clips).put("decoded",decoded).put("levels",new JSONArray(levels)).put("samples",new JSONArray(sampleCounts)).put("status",status).put("last_wake",lastWake).put("seat",lastSeat)
                .put("candidate",lastText).put("hits",hits).put("calibrated",new JSONArray(calibrated))
                .put("calibration",calibration).put("level",Math.round(level));}
        catch(JSONException e){throw new IllegalStateException(e);}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        String action=intent==null?"":intent.getAction();
        if("stop".equals(action)){HandsFreeAccess.setEnabled(this,false);TURN.cancel();running=false;status="Останавливаю…";stopRecorder();stopSelf();return START_NOT_STICKY;}
        if("calibrate".equals(action)) {
            int seat=intent.getIntExtra("seat",-1);
            if(running&&ready&&seat>=0&&seat<6)synchronized(calibrationLock) {
                TURN.cancel();enrollment=new Calibration(seat);calibration="Кресло: "+SeatProfiles.LABELS[seat]+" · 0/3. Скажите фразу трижды с паузами 3 с";
            }
            return START_NOT_STICKY;
        }
        if(started)return START_NOT_STICKY;
        started=true;owner=this;TURN.cancel();active=true;ready=false;running=true;status="Загружаю русскую модель…";phase="Подготовка микрофона";
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("wake-probe","Голосовой пробник",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,RemoteActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WakeProbeService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(73,new Notification.Builder(this,"wake-probe").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Denza · голосовой пульт").setContentText("Ожидаю «Привет, Денза» · голосовой пульт")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        overlay=new AssistantOverlay(this,WakeProbeService::cancelCurrentTurn,this::confirmCurrentPlan);
        main.post(renderTurn);
        new Thread(this::capture,"wake-capture").start();return START_NOT_STICKY;
    }
    private void check() throws IOException {if(!running)throw new IOException("Прослушивание остановлено");}
    private void capture() {
        AudioRecord record=null;
        String baseline="";LocalAdbClient adb=null;
        try {
            if(!android.provider.Settings.canDrawOverlays(this))throw new IOException("Нужно разрешение для плавающего индикатора");
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new IOException("Нет разрешения на микрофон");
            if(!new File(getNoBackupFilesDir(),"adb_auth_key_v1").isFile())throw new IOException("Существующий доступ ADB отсутствует");
            profiles=new SeatProfiles(read("seat-profiles.json"));updateProfiles();
            status="Подготовка словаря команд пульта…";commandGrammar=VoiceCommands.grammarJson();
            model=new Model(VoiceModelFiles.install(this,this::check).getAbsolutePath());check();
            adb=LocalAdbClient.loopbackOnly(this);
            baseline=MicrophoneProbeStats.stockLine(adb.shell("dumpsys audio",4000));check();
            record=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelIndexMask(0x3ff).build())
                    .setBufferSizeInBytes(320_000).build();recorder=record;check();
            if(record.getState()!=AudioRecord.STATE_INITIALIZED||record.getChannelCount()!=10||record.getSampleRate()!=16000||record.getFormat().getChannelIndexMask()!=0x3ff)throw new IOException("Не получен ожидаемый поток 10 × 16 кГц");
            record.startRecording();check();
            status="Секунда тишины для оценки шума…";
            CabinSpeech gate=new CabinSpeech();short[] pcm=new short[3200];
            long begin=SystemClock.elapsedRealtime(),nextConfig=begin+1000,nextStock=begin+5000;
            CabinSpeech.Listener frames=new CabinSpeech.Listener(){
                public void utterance(CabinSpeech.Utterance speech){enqueue(speech);}
                public void frame(short[] interleaved){CommandAudio stream=commandAudio;if(stream!=null&&TURN.listening(stream.id))stream.offerFrame(interleaved);}
            };
            while(running) {
                int count=record.read(pcm,0,pcm.length,AudioRecord.READ_NON_BLOCKING);
                if(count<0)throw new IOException("Ошибка чтения микрофона: "+count);
                if(resetGate){gate.resetUtterance();resetGate=false;}
                if(count>0)gate.add(pcm,count,frames);else SystemClock.sleep(5);
                level=gate.level;levels=gate.levels;phase=decoding?"Распознаю захваченную речь…":gate.speaking()?"Слышу речь…":"Микрофон работает · жду речь";
                long now=SystemClock.elapsedRealtime();
                if(now>=nextConfig) {
                    AudioRecordingConfiguration cfg=record.getActiveRecordingConfiguration();
                    if(cfg==null||cfg.isClientSilenced())throw new IOException("Микрофон недоступен или заглушён");
                    synchronized(calibrationLock){if(enrollment!=null&&now-enrollment.started>60_000){enrollment=null;calibration="Время калибровки истекло. Выберите кресло ещё раз";}}
                    ready=true;status="Ожидаю «Привет, Денза» · голосовой пульт включён";
                    nextConfig=now+1000;
                }
                if(now>=nextStock){MicrophoneProbeStats.requireSameStock(baseline,MicrophoneProbeStats.stockLine(adb.shell("dumpsys audio",4000)));nextStock=SystemClock.elapsedRealtime()+5000;}
            }
        }catch(Exception|LinkageError e){if(running)status="Остановлено: "+e.getMessage();Log.w(TAG,status,e);}
        finally {
            running=false;if(owner==this){TURN.cancel();active=false;ready=false;}phase="Микрофон выключен";synchronized(calibrationLock){enrollment=null;}calibration="";
            stopRecorder();if(record!=null)record.release();recorder=null;
            if(adb!=null&&!baseline.isEmpty())try{MicrophoneProbeStats.requireSameStock(baseline,MicrophoneProbeStats.stockLine(adb.shell("dumpsys audio",4000)));}catch(Exception e){status="Проверьте штатный помощник: "+e.getMessage();}
            if(status.startsWith("Слушаю")||status.startsWith("Останавливаю"))status="Прослушивание выключено";
            // Native model is closed on its sole decoder after any queued utterance.
            decoder.getQueue().clear();decoder.execute(()->{if(model!=null){model.close();model=null;}});decoder.shutdown();
            save("last-state.json",snapshot());main.post(()->{main.removeCallbacks(renderTurn);if(overlay!=null)overlay.hide();stopSelf();});
        }
    }
    private void enqueue(CabinSpeech.Utterance speech) {
        if(!running)return;
        if(commandAudio!=null)return;
        if(TURN.state().stage()==AssistantTurn.Stage.EXECUTING)return;
        boolean calibrationCapture;synchronized(calibrationLock){calibrationCapture=enrollment!=null;}
        clips++;collectCalibration(speech);
        if(decoder.getActiveCount()>0||!decoder.getQueue().isEmpty()){decision="Речь захвачена; распознаватель занят предыдущей репликой";return;}
        decoder.execute(()->decode(speech,calibrationCapture));
    }
    private void collectCalibration(CabinSpeech.Utterance speech) {
        synchronized(calibrationLock) {
            Calibration e=enrollment;if(e==null)return;
            long now=SystemClock.elapsedRealtime();
            if(now-e.started>60_000)return;
            // No wake/ASR condition: the name cannot block calibration. Bound voiced duration and start time.
            if(speech.voicedSamples()<4800||speech.voicedSamples()>64000||now-speech.pcm().length/16<e.started-200){calibration=SeatProfiles.LABELS[e.seat]+": образец слишком короткий/длинный; повторите фразу";return;}
            e.samples.add(speech.energy());int n=e.samples.size();
            calibration=SeatProfiles.LABELS[e.seat]+" · захвачено "+n+"/3";
            if(n==3)try{profiles.replace(e.seat,e.samples);save("seat-profiles.json",profiles.json());updateProfiles();calibration="✓ "+SeatProfiles.LABELS[e.seat]+": сохранено 3/3 · выберите следующее кресло";enrollment=null;}
            catch(Exception failure){calibration="Ошибка сохранения образца: "+failure.getMessage();enrollment=null;}
        }
    }
    private void decode(CabinSpeech.Utterance speech,boolean calibrationCapture) {
        if(!running)return;
        decoding=true;long begin=SystemClock.elapsedRealtime();
        try {
            short[] pcm=CabinSpeech.normalize(speech.pcm());
            // Always show the unrestricted transcript, including rejected wake phrases.
            JSONObject verification=recognize(pcm,null);heard=verification.optString("text","");
            if(heard.isBlank())heard="Речь захвачена, слов не разобрано";
            JSONObject candidate=recognize(pcm,WakePhrase.GRAMMAR);decoded++;
            boolean narrow=WakePhrase.accepts(candidate),full=WakePhrase.verifies(verification),accepted=WakePhrase.matches(candidate,verification);
            lastText=candidate.optString("text","");
            decision=accepted?"Фраза принята":narrow?"Отказ полной модели: "+WakePhrase.reason(verification,true):"Отказ детектора: "+WakePhrase.reason(candidate,false);
            if(!running)return;
            JSONObject attempt=new JSONObject().put("time_ms",System.currentTimeMillis()).put("candidate",candidate)
                    .put("verification",verification).put("accepted",accepted).put("reason",decision).put("mic",speech.microphone())
                    .put("energy",new JSONArray(speech.energy())).put("decode_ms",SystemClock.elapsedRealtime()-begin);
            save("last-recognition.json",attempt);Log.i(TAG,"DECODE "+attempt);
            if(!accepted)return;
            if(SystemClock.elapsedRealtime()-lastHit<2000){decision="Фраза принята · пауза между отметками 2 с";return;}
            SeatProfiles.Guess guess=profiles.guess(speech.energy());
            lastHit=SystemClock.elapsedRealtime();hits++;lastWake=java.time.LocalTime.now().withNano(0)+" · «Привет, Денза»";
            lastSeat=guess.calibrated()<6?"Место: калибровка "+guess.calibrated()+"/6 кресел":guess.label();
            JSONObject event=snapshot().put("time_ms",System.currentTimeMillis()).put("asr",candidate).put("verification",verification)
                    .put("mic",speech.microphone()).put("energy",new JSONArray(speech.energy()))
                    .put("seat_score",guess.score()).put("seat_margin",guess.margin())
                    .put("decode_ms",SystemClock.elapsedRealtime()-begin).put("commands_enabled",HandsFreeAccess.executionEnabled(this));
            save("last-wake.json",event);Log.i(TAG,"WAKE "+event);
            boolean calibrating;synchronized(calibrationLock){calibrating=enrollment!=null;}
            if(!calibrating&&!calibrationCapture)listenForCommand(speech);
        }catch(Exception|LinkageError e){if(running){Log.w(TAG,"Decode failed",e);status="Ошибка распознавания: "+e.getMessage();running=false;stopRecorder();}}
        finally{decoding=false;}
    }
    private void listenForCommand(CabinSpeech.Utterance wake) throws Exception {
        if(!running)return;
        pendingRoute=null;pendingTurn=0;
        long id=TURN.arm(lastSeat,SystemClock.elapsedRealtime());
        int peak=0;for(short sample:wake.pcm())peak=Math.max(peak,Math.abs((int)sample));
        CommandAudio stream=new CommandAudio(id,wake.microphone(),peak==0?1:Math.min(8,12000.0/peak));
        commandAudio=stream;
        try(Recognizer recognizer=new Recognizer(model,16000,commandGrammar)) {
            recognizer.setWords(true);long nextPartial=0;JSONObject endpoint=null;
            while(running&&TURN.listening(id)) {
                TURN.tick(SystemClock.elapsedRealtime());if(!TURN.listening(id))break;
                if(stream.overflow)throw new IOException("Не успеваю распознавать команду, повторите обращение");
                short[] block=stream.poll();if(block==null)continue;
                if(recognizer.acceptWaveForm(block,block.length)) {
                    JSONObject resultJson=new JSONObject(recognizer.getResult());String text=resultJson.optString("text","").trim();
                    if(!text.isEmpty()){endpoint=resultJson;TURN.finish(id,text,SystemClock.elapsedRealtime());break;}
                }
                long now=SystemClock.elapsedRealtime();
                if(now>=nextPartial){TURN.partial(id,new JSONObject(recognizer.getPartialResult()).optString("partial",""));nextPartial=now+120;}
            }
            AssistantTurn.State result=TURN.state();
            if(result.id()==id&&result.visible()) {
                lastCommand=result.text();heard=lastCommand.isEmpty()?"После обращения слов не разобрано":lastCommand;
                decision=result.stage()==AssistantTurn.Stage.RESULT?"Команда распознана · разбираю план":"Время ожидания команды истекло";
                save("last-command.json",new JSONObject().put("time_ms",System.currentTimeMillis()).put("turn",id)
                        .put("text",result.text()).put("seat",result.seat()).put("stage",result.stage().name()).put("commands_enabled",HandsFreeAccess.executionEnabled(this)));
                Log.i(TAG,"COMMAND "+result.stage()+" turn="+id+" words="+(result.text().isBlank()?0:result.text().split(" ").length));
                if(result.stage()==AssistantTurn.Stage.RESULT&&endpoint!=null)routeCommand(id,endpoint);
            }
        } finally {if(commandAudio==stream)commandAudio=null;resetGate=true;}
    }
    private void routeCommand(long id,JSONObject endpoint) {
        if(!running||!TURN.current(id))return;
        try {
            VoiceCommandRoute route=VoiceCommandRoute.prepare(endpoint.toString());lastPlan=route.description();lastExecution="";
            save("last-plan.json",new JSONObject().put("time_ms",System.currentTimeMillis()).put("turn",id)
                    .put("text",route.assessment.plan().text()).put("plan",lastPlan).put("recognition",endpoint)
                    .put("needs_confirmation",route.assessment.needsConfirmation()).put("reason",route.assessment.reason())
                    .put("execution_enabled",HandsFreeAccess.executionEnabled(this)));
            if(!HandsFreeAccess.executionEnabled(this)) {
                decision="План разобран · выполнение выключено";
                TURN.report(id,"План: "+lastPlan+"\nВыполнение выключено; ничего не отправлено",SystemClock.elapsedRealtime());return;
            }
            if(route.assessment.needsConfirmation()) {
                pendingRoute=route;pendingTurn=id;decision="Проверьте план и подтвердите";
                TURN.review(id,"План: "+lastPlan+"\n"+route.assessment.reason(),SystemClock.elapsedRealtime());
            }else executeRoute(id,route,false);
        }catch(Exception e){lastPlan="";decision=e.getMessage();TURN.report(id,decision,SystemClock.elapsedRealtime());}
    }
    private void confirmCurrentPlan() {
        TURN.tick(SystemClock.elapsedRealtime());
        VoiceCommandRoute route=pendingRoute;long id=pendingTurn;
        if(route==null||TURN.state().id()!=id||TURN.state().stage()!=AssistantTurn.Stage.REVIEW)return;
        pendingRoute=null;executeRoute(id,route,true);
    }
    private void executeRoute(long id,VoiceCommandRoute route,boolean confirmed) {
        if(!running||!HandsFreeAccess.executionEnabled(this)||!TURN.current(id))return;
        NativeRuntime acquired=null;
        try {
            NativeRuntime runtime=NativeRuntime.get(this);
            if(!runtime.writing.compareAndSet(false,true)){TURN.report(id,"Пульт занят; команда не поставлена в очередь",SystemClock.elapsedRealtime());return;}
            acquired=runtime;
            if(!TURN.beginExecution(id,"План: "+route.description())){runtime.writing.set(false);return;}
            runtime.operations.execute(()->{
                VoiceCommands.Execution result;
                try {
                    result=route.execute(runtime.controller,()->running&&owner==this&&TURN.executing(id)
                                    &&HandsFreeAccess.executionEnabled(this)&&android.provider.Settings.canDrawOverlays(this),
                            HandsFreeAccess.executionEnabled(this),confirmed);
                    lastExecution=result.message();
                    JSONArray outcomes=new JSONArray();for(VoiceCommands.Outcome outcome:result.outcomes())outcomes.put(new JSONObject()
                            .put("target",outcome.target()).put("action",outcome.action()).put("status",outcome.status()).put("message",outcome.message()));
                    save("last-execution.json",new JSONObject().put("time_ms",System.currentTimeMillis()).put("turn",id)
                            .put("text",route.assessment.plan().text()).put("complete",result.complete()).put("outcomes",outcomes).put("message",result.message()));
                    TURN.report(id,result.message(),SystemClock.elapsedRealtime());
                }catch(Exception e){lastExecution=e.getMessage()+". Команда не повторялась";TURN.report(id,lastExecution,SystemClock.elapsedRealtime());}
                finally {runtime.writing.set(false);}
            });
        }catch(Exception e){if(acquired!=null)acquired.writing.set(false);TURN.report(id,e.getMessage(),SystemClock.elapsedRealtime());}
    }
    private JSONObject recognize(short[] pcm,String grammar) throws Exception {
        try(Recognizer r=grammar==null?new Recognizer(model,16000):new Recognizer(model,16000,grammar)) {
            r.setWords(true);JSONArray words=new JSONArray();StringBuilder text=new StringBuilder();
            for(int start=0;start<pcm.length;start+=3200) {
                check();int n=Math.min(3200,pcm.length-start);short[] block=new short[n];System.arraycopy(pcm,start,block,0,n);
                if(r.acceptWaveForm(block,n))append(new JSONObject(r.getResult()),text,words);
            }
            append(new JSONObject(r.getFinalResult()),text,words);
            return new JSONObject().put("text",text.toString().trim()).put("result",words);
        }
    }
    private static void append(JSONObject result,StringBuilder text,JSONArray words) {
        String value=result.optString("text","");if(!value.isEmpty()){if(text.length()>0)text.append(' ');text.append(value);}
        JSONArray list=result.optJSONArray("result");if(list!=null)for(int i=0;i<list.length();i++)words.put(list.opt(i));
    }
    private void updateProfiles(){boolean[] flags=new boolean[6];int[] counts=new int[6];for(int i=0;i<6;i++){counts[i]=profiles.samples(i);flags[i]=counts[i]>=3;}calibrated=flags;sampleCounts=counts;}
    private JSONObject read(String name){try{return new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(),"wake-probe/"+name).toPath()),StandardCharsets.UTF_8));}catch(Exception ignored){return null;}}
    private void save(String name,JSONObject data){
        try{File dir=new File(getFilesDir(),"wake-probe");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("directory");AtomicFile f=new AtomicFile(new File(dir,name));FileOutputStream o=f.startWrite();try{o.write(data.toString(2).getBytes(StandardCharsets.UTF_8));f.finishWrite(o);}catch(Exception e){f.failWrite(o);throw e;}}
        catch(Exception e){Log.w(TAG,"Diagnostic write failed",e);}
    }
    private void stopRecorder(){AudioRecord r=recorder;if(r!=null)try{r.stop();}catch(IllegalStateException ignored){}}
    @Override public void onDestroy(){running=false;if(owner==this){TURN.cancel();active=false;ready=false;}main.removeCallbacksAndMessages(null);if(overlay!=null)overlay.hide();stopRecorder();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
