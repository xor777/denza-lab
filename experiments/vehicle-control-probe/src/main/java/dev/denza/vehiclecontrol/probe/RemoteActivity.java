package dev.denza.vehiclecontrol.probe;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import dev.denza.disharebridge.LocalAdbClient;
import java.io.InputStream;
import java.util.*;
import org.json.*;

/** Native Views only; transport/controller work never runs on the main thread. */
public final class RemoteActivity extends Activity {
    private NativeRuntime runtime;
    private volatile boolean visible,remoteScreen,focused;
    private volatile int epoch;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,feedback;
    private Button voiceButton;
    private Button voiceConfirmButton;
    private VoiceCommands.Plan voiceConfirmation;
    private int voiceConfirmationEpoch;
    private VoiceInput voiceInput;
    private boolean voiceActive;
    private LinearLayout body;
    private ScrollView scroll;
    private JSONObject state;
    private boolean stale;
    private String renderedPage="",paintedTab="";
    private record Binding(String target,String action,boolean hold,boolean allowed) { }
    private String section="windows",seat="fl",climateZone="driver";
    private final List<Button> controls=new ArrayList<>();
    private Button heldButton;
    private NativeController.Hold ownedHold;
    private final Map<Button,String> stopControls=new HashMap<>();
    private final Map<Button,String> tabButtons=new LinkedHashMap<>();
    private static final int BG=Color.rgb(16,22,25),CARD=Color.rgb(27,40,46),TEXT=Color.rgb(238,244,244),MUTED=Color.rgb(166,184,189),ACCENT=Color.rgb(129,199,186);
    private final Runnable poll=new Runnable() {public void run(){if(visible&&remoteScreen){refresh();handler.postDelayed(this,4000);}}};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null){section=saved.getString("section","windows");seat=saved.getString("seat","fl");climateZone=saved.getString("climateZone","driver");}
        try {runtime=NativeRuntime.get(getApplicationContext());
            if(new java.io.File(getNoBackupFilesDir(),"adb_auth_key_v1").isFile()) showRemote();else showAccess();
        } catch(Exception error){TextView message=new TextView(this);message.setText("Пульт недоступен: "+error.getMessage());setContentView(message);}
    }
    @Override protected void onSaveInstanceState(Bundle saved){super.onSaveInstanceState(saved);saved.putString("section",section);saved.putString("seat",seat);saved.putString("climateZone",climateZone);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);return layout;}
    private TextView text(String content,int size,int color){TextView v=new TextView(this);v.setText(content);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(4),0,dp(8));return v;}
    private Button button(String title){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(TEXT);b.setMinHeight(dp(54));return b;}
    private void postAccess(Runnable task){int generation=epoch;handler.post(()->{if(visible&&!remoteScreen&&!isDestroyed()&&generation==epoch)task.run();});}
    void showRemote() {
        cancelVoice();releaseHold();remoteScreen=true;epoch++;state=null;stale=false;renderedPage="";paintedTab="";tabButtons.clear();controls.clear();stopControls.clear();
        LinearLayout root=column();root.setPadding(dp(24),dp(14),dp(24),dp(12));root.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Denza · Пульт",27,TEXT);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        voiceButton=button("Голос");voiceButton.setOnClickListener(v->toggleVoice());
        voiceButton.setOnLongClickListener(v->{
            try{startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));}
            catch(Exception error){feedback.setText("Откройте системные настройки специальных возможностей: Голос пульта · кнопка руля");}
            return true;
        });header.addView(voiceButton);
        voiceConfirmButton=button("Выполнить план");voiceConfirmButton.setVisibility(View.GONE);
        voiceConfirmButton.setOnClickListener(v->{
            VoiceCommands.Plan plan=voiceConfirmation;int generation=voiceConfirmationEpoch;
            clearVoiceConfirmation();
            if(plan==null||!voiceKeyAvailable()||generation!=epoch)return;
            recordVoiceDecision(plan.text(),"confirmed","Подтверждено нажатием");
            executeVoicePlan(plan,generation);
        });header.addView(voiceConfirmButton);
        Button access=button("Доступ ADB");access.setOnClickListener(v->showAccess());header.addView(access);
        Button update=button("Обновить");update.setOnClickListener(v->refresh());header.addView(update);root.addView(header);
        status=text("Читаю состояние автомобиля…",16,ACCENT);root.addView(status);
        root.addView(text("Голос: «открой левое окно», «закрой правую дверь», «закрой окна и открой левую заднюю дверь». Руль: долгое нажатие «Голос» для настройки.",13,MUTED));
        LinearLayout tabs=new LinearLayout(this);
        for(String tab:List.of("windows","shades","lights","doors","seats","climate")){
            Button b=button(Map.of("windows","Окна","shades","Панорама","lights","Свет","doors","Двери","seats","Сиденья","climate","Климат").get(tab));
            tabButtons.put(b,tab);b.setOnClickListener(v->{cancelVoice();releaseHold();section=tab;feedback.setText(runtime.activeHold==null?"Выберите действие. Принятие команды ещё не подтверждает движение.":"Остановка кресла запрошена; ожидаю ответ STOP.");render();});tabs.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        }root.addView(tabs);
        scroll=new ScrollView(this);body=column();scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        feedback=text("Выберите раздел и действие. Принятие команды ещё не подтверждает движение.",16,TEXT);root.addView(feedback);
        root.addView(text("Управление в P · Для ручной работы завершите отдых/кемпинг · Кресла: удерживайте, до 5 с",13,MUTED));
        setContentView(root);render();if(visible){handler.removeCallbacks(poll);handler.post(poll);}
    }
    private void refresh(){
        if(voiceActive||!visible||!remoteScreen||runtime==null||runtime.writing.get()||!runtime.reading.compareAndSet(false,true))return;
        int generation=epoch;
        runtime.operations.execute(()->{
            if(!visible||!remoteScreen||generation!=epoch){runtime.reading.set(false);return;}
            JSONObject result=null;String error=null;
            try{result=runtime.controller.snapshot();}catch(Exception failure){error=failure.getMessage();}
            finally{runtime.reading.set(false);}
            JSONObject answer=result;String problem=error;
            handler.post(()->{if(!visible||!remoteScreen||isDestroyed()||generation!=epoch)return;
                if(problem==null){state=answer;stale=false;status.setText("N9 · "+(answer.optInt("park",-1)==1?"P · управление доступно":"не в P / неизвестно")+" · "+java.time.LocalTime.now().withNano(0));}
                else {stale=true;status.setText("Данные устарели · "+problem);}
                render();});
        });
    }
    private void updateButtons(){for(Button button:controls){Binding binding=(Binding)button.getTag();boolean release=runtime.activeHold!=null&&binding.action().equals("stop")&&binding.target().equals(runtime.activeHold.target);boolean held=button==heldButton&&runtime.activeHold!=null&&!runtime.activeHold.released.get();button.setEnabled(release||held||(state!=null&&(!stale||binding.action().equals("stop"))&&!runtime.writing.get()&&(!voiceActive||binding.action().equals("stop"))&&binding.allowed()));}
        if(voiceButton!=null){voiceButton.setText(voiceActive?"Отменить голос":"Голос");voiceButton.setEnabled(voiceActive||(state!=null&&!runtime.writing.get()));}
        if(voiceConfirmButton!=null){voiceConfirmButton.setVisibility(voiceConfirmation==null?View.GONE:View.VISIBLE);voiceConfirmButton.setEnabled(voiceConfirmation!=null&&!runtime.writing.get());}
    }
    boolean voiceKeyAvailable(){return visible&&remoteScreen&&focused;}
    int voiceKeyGeneration(){return epoch;}
    void toggleVoice(){
        if(voiceActive){cancelVoice();feedback.setText("Голосовая команда отменена.");refresh();return;}
        clearVoiceConfirmation();
        if(!visible||!remoteScreen||!focused||state==null||runtime.writing.get()){if(feedback!=null)feedback.setText("Пульт ещё не готов или выполняет действие. Нажмите голос после завершения.");return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},41);return;}
        int generation=epoch;
        voiceActive=true;updateButtons();
        if(voiceInput==null)voiceInput=new VoiceInput(getApplicationContext());
        voiceInput.start(new VoiceInput.Listener(){
            private boolean current(){return voiceActive&&visible&&remoteScreen&&!isDestroyed()&&generation==epoch;}
            public void onStatus(String message){if(current())feedback.setText(message);}
            public void onError(String message){if(!current())return;voiceActive=false;feedback.setText(message);updateButtons();refresh();}
            public void onResult(JSONObject result){
                if(!current())return;
                voiceActive=false;
                final VoiceCommands.Assessment assessment;
                try{assessment=VoiceCommands.assessVosk(result.toString());}
                catch(Exception error){recordVoiceDecision(result.optString("text"),"rejected",error.getMessage());feedback.setText("«"+result.optString("text")+"» · "+error.getMessage());updateButtons();refresh();return;}
                VoiceCommands.Plan plan=assessment.plan();
                if(assessment.needsConfirmation()){
                    voiceConfirmation=plan;voiceConfirmationEpoch=generation;
                    recordVoiceDecision(plan.text(),"confirmation_required",assessment.reason());
                    feedback.setText("Распознано: «"+plan.text()+"». План: "+describeVoicePlan(plan)+".\n"+assessment.reason()+" Нажмите «Выполнить план» или скажите заново.");
                    updateButtons();refresh();return;
                }
                recordVoiceDecision(plan.text(),"accepted",assessment.reason());
                executeVoicePlan(plan,generation);
            }
        });
    }
    private void executeVoicePlan(VoiceCommands.Plan plan,int generation){
        if(!voiceKeyAvailable()||generation!=epoch)return;
        if(!runtime.writing.compareAndSet(false,true)){feedback.setText("Пульт занят. Голосовая команда не поставлена в очередь.");updateButtons();return;}
        feedback.setText("«"+plan.text()+"» · "+(runtime.reading.get()?"ожидаю текущее чтение…":"выполняю…"));updateButtons();
        runtime.operations.execute(()->{
            String message;
            try{message=VoiceCommands.execute(plan,runtime.controller,()->visible&&remoteScreen&&focused&&generation==epoch).message();}
            catch(Exception error){message=error.getMessage()+". Команда не повторялась.";}
            finally{runtime.writing.set(false);}
            String answer=message;handler.post(()->{if(!visible||!remoteScreen||isDestroyed()||generation!=epoch)return;
                feedback.setText("«"+plan.text()+"» · "+answer);updateButtons();refresh();});
        });
    }
    private String describeVoicePlan(VoiceCommands.Plan plan){
        Map<String,String> labels=Map.of("fl","переднее левое окно","fr","переднее правое окно","rl","заднее левое окно","rr","заднее правое окно",
            "door.fl","передняя левая дверь","door.fr","передняя правая дверь","door.rl","задняя левая дверь","door.rr","задняя правая дверь");
        Map<String,String> verbs=Map.of("open","открыть","close","закрыть","stop","остановить");
        List<String> actions=new ArrayList<>();for(VoiceCommands.Command command:plan.commands())actions.add(verbs.get(command.action())+": "+labels.get(command.target()));
        return String.join("; ",actions);
    }
    private void recordVoiceDecision(String text,String decision,String reason){
        if(voiceInput==null)return;
        try{voiceInput.recordDecision(new JSONObject().put("time_ms",System.currentTimeMillis()).put("text",text).put("decision",decision).put("reason",reason));}
        catch(JSONException ignored){/* Diagnostic metadata cannot authorize an action. */}
    }
    private void clearVoiceConfirmation(){
        if(voiceConfirmation!=null)recordVoiceDecision(voiceConfirmation.text(),"cancelled","План отменён или заменён");
        voiceConfirmation=null;if(voiceConfirmButton!=null)voiceConfirmButton.setVisibility(View.GONE);
    }
    private void cancelVoice(){
        clearVoiceConfirmation();
        voiceActive=false;if(voiceInput!=null)voiceInput.cancel();
        if(runtime!=null&&voiceButton!=null)updateButtons();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==41&&feedback!=null&&remoteScreen)feedback.setText(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED?"Микрофон разрешён. Нажмите «Голос» и дождитесь «Говорите».":"Доступ к микрофону не разрешён.");
    }
    private void action(String target,String action){
        cancelVoice();
        if(!visible||!remoteScreen||!runtime.writing.compareAndSet(false,true))return;
        int generation=epoch;feedback.setText(runtime.reading.get()?"Команда ожидает текущего чтения…":"Отправляю команду…");updateButtons();
        runtime.operations.execute(()->{
            if(!visible||!remoteScreen||generation!=epoch){runtime.writing.set(false);return;}
            JSONObject result=null;String error=null;
            try{result=runtime.controller.act(target,action,()->visible&&remoteScreen&&generation==epoch);}catch(Exception failure){error=failure.getMessage();}finally{runtime.writing.set(false);}
            finishOperation(generation,result,error);
        });
    }
    private void startHold(Button source,String target,String direction){
        if(voiceActive)return;
        clearVoiceConfirmation();
        if(!visible||!remoteScreen||!runtime.writing.compareAndSet(false,true))return;
        final NativeController.Hold hold;
        try{hold=runtime.controller.newHold(target,direction);}catch(Exception failure){runtime.writing.set(false);feedback.setText(failure.getMessage());return;}
        runtime.activeHold=hold;ownedHold=hold;heldButton=source;int generation=epoch;
        feedback.setText(runtime.reading.get()?"Удерживайте: движение начнётся после текущего чтения. Отпускание отменяет его.":"Удерживайте · до 5 с. Отпустите для остановки.");
        for(Button b:controls)if(b!=source)b.setEnabled(hold.target.equals(stopControls.get(b)));
        runtime.operations.execute(()->{
            if(!visible||!remoteScreen||generation!=epoch){runtime.activeHold=null;runtime.writing.set(false);return;}
            JSONObject result=null;String error=null;
            try{result=runtime.controller.hold(hold);}catch(Exception failure){error=failure.getMessage();}
            finally{runtime.activeHold=null;runtime.writing.set(false);}
            finishOperation(generation,result,error);
        });
    }
    private void finishOperation(int generation,JSONObject result,String error){handler.post(()->{
        if(!visible||!remoteScreen||isDestroyed()||generation!=epoch)return;
        heldButton=null;
        feedback.setText(error!=null?error+". Команда не повторялась.":result.optBoolean("cancelled")&&(!result.has("stop_accepted")||result.optBoolean("stop_accepted"))?"Нажатие отменено; новое движение не запущено.":result.optBoolean("accepted")?"Команда принята"+(result.has("stop_accepted")?" · остановка также принята":"")+". Проверьте фактический результат.":result.optString("error","Команда не подтверждена · код "+result.optInt("code",-1)));
        updateButtons();refresh();});}
    private void releaseHold(){if(runtime!=null&&ownedHold!=null&&runtime.activeHold==ownedHold)runtime.releaseHold();ownedHold=null;heldButton=null;}
    private LinearLayout card(String name,String value,String reason){
        LinearLayout card=column();card.setPadding(dp(18),dp(12),dp(18),dp(14));
        GradientDrawable background=new GradientDrawable();background.setColor(CARD);background.setCornerRadius(dp(14));card.setBackground(background);
        TextView title=text(name,19,TEXT);title.setTypeface(null,Typeface.BOLD);card.addView(title);
        TextView reading=text(value==null?"":value,21,ACCENT);reading.setVisibility(value==null?View.GONE:View.VISIBLE);card.addView(reading);
        TextView why=text(reason==null?"":reason,14,MUTED);why.setVisibility(reason==null?View.GONE:View.VISIBLE);card.addView(why);return card;
    }
    private LinearLayout row(LinearLayout card){LinearLayout row=new LinearLayout(this);card.addView(row);return row;}
    private Button control(LinearLayout row,String target,String action,String label,String reason,boolean hold){
        Button b=button(label);bindControl(b,new Binding(target,action,hold,action.equals("stop")||reason==null));controls.add(b);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));return b;
    }
    private void bindControl(Button b,Binding binding){
        b.setTag(binding);if(binding.action().equals("stop"))stopControls.put(b,binding.target());
        if(binding.hold()){
            b.setContentDescription(b.getText()+" · удерживайте до 5 секунд");b.setOnTouchListener((v,event)->{
                Binding current=(Binding)b.getTag();
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){if(!b.isEnabled())return false;v.getParent().requestDisallowInterceptTouchEvent(true);startHold(b,current.target(),current.action());return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&(event.getX()<0||event.getY()<0||event.getX()>v.getWidth()||event.getY()>v.getHeight())){releaseHold();return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){releaseHold();v.getParent().requestDisallowInterceptTouchEvent(false);b.performClick();return true;}return true;});
            b.setOnClickListener(v->{});
        }else b.setOnClickListener(v->{Binding current=(Binding)b.getTag();if(current.action().equals("stop")&&runtime.activeHold!=null){releaseHold();return;}action(current.target(),current.action());});
    }
    private String blocked(JSONObject object){return object.isNull("blocked")?null:object.optString("blocked","Нет данных");}
    private String percent(JSONObject object){return object.isNull("percent")?"Нет положения":String.format(java.util.Locale.ROOT,"%.0f%%",object.optDouble("percent"));}
    private void motion(LinearLayout card,String target,String reason){LinearLayout r=row(card);control(r,target,"open","Открыть",reason,false);control(r,target,"close","Закрыть",reason,false);control(r,target,"stop","Стоп",null,false);}
    private void addGrid(List<LinearLayout> cards){
        LinearLayout current=null;
        for(int i=0;i<cards.size();i++){
            if(i%2==0){current=new LinearLayout(this);body.addView(current);}
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(5),dp(5),dp(5),dp(5));current.addView(cards.get(i),lp);
        }
        if(cards.size()%2==1)current.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
    }
    private void render(){
        if(body==null||!remoteScreen)return;
        if(!paintedTab.equals(section)){for(var tab:tabButtons.entrySet()){boolean selected=section.equals(tab.getValue());tab.getKey().setTextColor(selected?ACCENT:TEXT);tab.getKey().setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected?Color.rgb(35,68,65):Color.rgb(47,61,68)));}paintedTab=section;}
        LinearLayout previous=body;String page=state==null?"loading":section+":"+seat+":"+climateZone;
        body=column();controls.clear();stopControls.clear();
        if(state==null){body.addView(text("Нет свежих данных. Действия недоступны до чтения автомобиля.",18,MUTED));commitBody(previous,page);return;}
        try{
            JSONArray pending=state.getJSONArray("pending_stops");
            LinearLayout recoveryContainer=column();body.addView(recoveryContainer);
            if(pending.length()>0){LinearLayout recovery=card("Проверьте остановку кресла","Ответ STOP не подтверждён",null);for(int i=0;i<pending.length();i++)control(row(recovery),pending.getString(i),"stop",pending.getString(i)+" · Стоп",null,false);recoveryContainer.addView(recovery);}
            List<LinearLayout> cards=new ArrayList<>();
            if(section.equals("windows")||section.equals("shades")){
                JSONObject group=state.getJSONObject(section);for(String key:NativeController.keys(group)){
                    JSONObject c=group.getJSONObject(key);LinearLayout card=card(c.getString("name"),percent(c),blocked(c));motion(card,section.equals("windows")?key:"shade."+key,blocked(c));cards.add(card);
                }
            }else if(section.equals("doors")){
                JSONObject locks=state.getJSONObject("locks"),doors=state.getJSONObject("doors");boolean locked=true,unlocked=true;
                for(String key:NativeController.keys(doors)){locked&=doors.getJSONObject(key).optInt("lock",-1)==2;unlocked&=doors.getJSONObject(key).optInt("lock",-1)==1;}
                LinearLayout card=card("Центральный замок",locked?"Все двери заперты":unlocked?"Все двери разблокированы":"Замки различаются / нет данных",blocked(locks));LinearLayout r=row(card);control(r,"locks","lock","Запереть",blocked(locks),false);control(r,"locks","unlock","Разблокировать",blocked(locks),false);cards.add(card);
                for(String key:NativeController.keys(doors)){JSONObject c=doors.getJSONObject(key);card=card(c.getString("name"),Map.of(1,"Открыта",2,"Закрыта",3,"Приоткрыта").getOrDefault(c.optInt("position",-1),"Нет положения"),blocked(c));motion(card,"door."+key,blocked(c));cards.add(card);}
            }else if(section.equals("lights")){
                JSONObject group=state.getJSONObject("lights");for(String key:NativeController.keys(group)){JSONObject c=group.getJSONObject(key);LinearLayout card=card(c.getString("name"),lightLabel(c.optString("state")),blocked(c));LinearLayout r=row(card);JSONArray actions=c.getJSONArray("actions");for(int i=0;i<actions.length();i++){String a=actions.getString(i);Button b=control(r,"light."+key,a,lightLabel(a),blocked(c),false);b.setSelected(a.equals(c.optString("state")));}cards.add(card);}
            }else if(section.equals("climate")){
                JSONObject group=state.getJSONObject("climate");
                List<String> zones=List.of("driver","passenger","middle_left","middle_right","rear");
                List<String> zoneNames=List.of("Водитель","Передний пассажир","Второй ряд · слева","Второй ряд · справа","Третий ряд");
                Spinner picker=new Spinner(this);picker.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,zoneNames));picker.setSelection(Math.max(0,zones.indexOf(climateZone)));body.addView(picker);
                picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int index,long id){if(!climateZone.equals(zones.get(index))){climateZone=zones.get(index);render();}}});
                List<String> ordered=NativeController.keys(group);ordered.sort(Comparator.comparingInt(k->group.optJSONObject(k).optInt("order",999)));
                for(String key:ordered){
                    JSONObject c=group.getJSONObject(key);String zone=c.optString("zone","general");if(!zone.equals("general")&&!zone.equals(climateZone))continue;
                    JSONObject labels=c.getJSONObject("labels");String current=c.optString("state","");
                    LinearLayout card=card(c.getString("name"),c.isNull("state")?"Нет состояния":labels.optString(current,current)+c.optString("unit"),blocked(c));
                    JSONArray actions=c.getJSONArray("actions");LinearLayout r=row(card);
                    if(c.optString("presentation").equals("stepper")){
                        int index=-1;for(int i=0;i<actions.length();i++)if(actions.getString(i).equals(current))index=i;
                        control(r,"climate."+key,index>0?actions.getString(index-1):"", "−",index>0?blocked(c):"Нижняя граница",false);
                        control(r,"climate."+key,index>=0&&index+1<actions.length()?actions.getString(index+1):"", "+",index>=0&&index+1<actions.length()?blocked(c):"Верхняя граница",false);
                    }else for(int i=0;i<actions.length();i++){if(i>0&&i%3==0)r=row(card);String a=actions.getString(i);Button b=control(r,"climate."+key,a,labels.optString(a,a),blocked(c),false);b.setSelected(a.equals(current));}
                    cards.add(card);
                }
                if(group.length()==0)body.addView(text("Нет подтверждённых контролов климата",18,MUTED));
            }else if(section.equals("seats")){
                JSONObject seats=state.getJSONObject("seats");List<String> ids=NativeController.keys(seats);if(!ids.contains(seat))seat=ids.get(0);Spinner picker=new Spinner(this);List<String> labels=new ArrayList<>();for(String id:ids)labels.add(seats.getJSONObject(id).getString("name"));ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);picker.setAdapter(adapter);picker.setSelection(ids.indexOf(seat));body.addView(picker);picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int index,long id){if(!seat.equals(ids.get(index))){releaseHold();seat=ids.get(index);render();}}});
                JSONObject spec=seats.getJSONObject(seat),comfort=spec.getJSONObject("comfort"),axes=spec.getJSONObject("axes");
                for(String kind:NativeController.keys(comfort)){JSONObject c=comfort.getJSONObject(kind);LinearLayout card=card(kind.equals("heat")?"Обогрев":"Вентиляция",c.isNull("level")?"Нет состояния":"Уровень "+c.optInt("level"),blocked(c));LinearLayout r=row(card);for(int i=0;i<=3;i++){Button b=control(r,"comfort."+seat+"."+kind,""+i,i==0?"Выкл.":""+i,blocked(c),false);b.setSelected(!c.isNull("level")&&c.optInt("level")==i);}cards.add(card);}
                if(state.getJSONObject("massages").has(seat)){JSONObject c=state.getJSONObject("massages").getJSONObject(seat);LinearLayout card=card("Массаж",c.isNull("enabled")?"Состояние не подтверждено":c.optBoolean("enabled")?"Включён":"Выключен",blocked(c));int count=0;LinearLayout r=null;for(String a:List.of("on","off","wave","pulse","stretch","waist","shoulders","level1","level2","level3")){if(count++%4==0)r=row(card);control(r,"massage."+seat,a,Map.of("on","Вкл.","off","Выкл.","wave","Волна","pulse","Пульсация","stretch","Растяжка","waist","Поясница","shoulders","Плечи","level1","Мягко","level2","Средне","level3","Сильно").get(a),blocked(c),false);}cards.add(card);}
                for(String axis:NativeController.keys(axes)){JSONObject c=axes.getJSONObject(axis);LinearLayout card=card(c.getString("name"),percent(c)+" · удерживайте до 5 с",blocked(c));LinearLayout r=row(card);control(r,"seat."+seat+"."+axis,"plus",c.getJSONArray("labels").getString(0),blocked(c),true);control(r,"seat."+seat+"."+axis,"minus",c.getJSONArray("labels").getString(1),blocked(c),true);control(r,"seat."+seat+"."+axis,"stop","Стоп",null,false);cards.add(card);}
            }
            addGrid(cards);commitBody(previous,page);updateButtons();
        }catch(Exception failure){body=previous;stale=true;status.setText("Данные устарели · "+failure.getMessage());collectControls(body);updateButtons();}
    }
    /** Reconcile an unattached render plan into the existing hierarchy. Ordinary polling
     * never detaches a live View, replaces a selector adapter or scrolls the container. */
    private void commitBody(LinearLayout previous,String page){
        LinearLayout plan=body;body=previous;
        if(!page.equals(renderedPage)){body.removeAllViews();while(plan.getChildCount()>0){View child=plan.getChildAt(0);plan.removeViewAt(0);body.addView(child);}renderedPage=page;}
        else reconcile(body,plan);
        body.setAlpha(stale?0.65f:1f);controls.clear();stopControls.clear();collectControls(body);
    }
    private void reconcile(View live,View plan){
        if(live instanceof Spinner)return; // Preserve adapter, open popup, selection and focus.
        if(live instanceof TextView target&&plan instanceof TextView source){if(!android.text.TextUtils.equals(target.getText(),source.getText()))target.setText(source.getText());}
        if(live.getVisibility()!=plan.getVisibility())live.setVisibility(plan.getVisibility());
        if(live.isSelected()!=plan.isSelected())live.setSelected(plan.isSelected());
        if(live instanceof Button b&&plan.getTag() instanceof Binding binding)bindControl(b,binding);
        if(live instanceof ViewGroup target&&plan instanceof ViewGroup source){
            List<View> desired=new ArrayList<>();for(int i=0;i<source.getChildCount();i++)desired.add(source.getChildAt(i));
            for(int i=0;i<desired.size();i++){
                View candidate=desired.get(i);
                if(i<target.getChildCount()&&target.getChildAt(i).getClass()==candidate.getClass())reconcile(target.getChildAt(i),candidate);
                else {if(i<target.getChildCount())target.removeViewAt(i);source.removeView(candidate);target.addView(candidate,i);}
            }
            while(target.getChildCount()>desired.size())target.removeViewAt(target.getChildCount()-1);
        }
    }
    private void collectControls(View root){if(root instanceof Button b&&b.getTag() instanceof Binding binding){controls.add(b);if(binding.action().equals("stop"))stopControls.put(b,binding.target());}if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)collectControls(group.getChildAt(i));}
    private String lightLabel(String a){return Map.of("on","Вкл.","off","Выкл.","auto","AUTO","low","Ближний","other","Другой режим").getOrDefault(a,"Нет данных");}
    void showAccess() {
        cancelVoice();releaseHold(); remoteScreen=false; epoch++; handler.removeCallbacks(poll);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 24, 32, 24);
        TextView status = new TextView(this);
        status.setText("Разрешите пульту локальный доступ ADB. Подтвердите запрос на экране машины и сравните отпечаток. Ключ создаётся только для этого приложения.");
        if (new java.io.File(getNoBackupFilesDir(), "adb_auth_key_v1").isFile()) {
            try { status.append("\nОтпечаток ключа пульта:\n" + LocalAdbClient.loopbackOnly(getApplicationContext()).publicKeyFingerprint()); }
            catch (Exception error) { status.append("\nНе удалось прочитать ключ: " + error.getMessage()); }
        }
        layout.addView(status);
        android.widget.Button request = new android.widget.Button(this);
        request.setText("Разрешить доступ ADB");
        request.setEnabled(!getSharedPreferences("access", MODE_PRIVATE).getBoolean("requested", false));
        layout.addView(request);
        android.widget.Button check = new android.widget.Button(this);
        check.setText("Я подтвердил — проверить");
        layout.addView(check);
        android.widget.Button again = new android.widget.Button(this);
        again.setText("Разрешить новую попытку запроса");
        layout.addView(again);
        android.widget.Button importKey = new android.widget.Button(this);
        importKey.setText("Дополнительно: импорт существующего ключа");
        importKey.setOnClickListener(v -> pickKey());
        layout.addView(importKey);
        setContentView(layout);
        request.setOnClickListener(v -> {
            // Latch on the UI thread before key preparation, so recreation cannot rearm it.
            if (getSharedPreferences("access", MODE_PRIVATE).getBoolean("requested", false)) return;
            if (!getSharedPreferences("access", MODE_PRIVATE).edit().putBoolean("requested", true).commit()) {
                status.append("\nНе удалось сохранить защиту от повтора; запрос не отправлен.");
                return;
            }
            request.setEnabled(false); check.setEnabled(false); again.setEnabled(false); importKey.setEnabled(false);
            new Thread(() -> {
                try {
                    LocalAdbClient client = LocalAdbClient.loopbackOnly(getApplicationContext());
                    String fingerprint = client.publicKeyFingerprint();
                    java.util.concurrent.CountDownLatch shown = new java.util.concurrent.CountDownLatch(1);
                    postAccess(() -> {
                        status.setText("Отпечаток ключа пульта:\n" + fingerprint + "\nПодтвердите соответствующий запрос ADB на машине.");
                        status.postOnAnimation(shown::countDown);
                    });
                    if (!shown.await(3, java.util.concurrent.TimeUnit.SECONDS))
                        throw new java.io.IOException("Экран с отпечатком не готов; запрос не отправлен");
                    LocalAdbClient.AuthorizationRequestResult result = client.requestAuthorization();
                    postAccess(() -> status.setText("Отпечаток ключа пульта:\n" + fingerprint +
                            (result == LocalAdbClient.AuthorizationRequestResult.ALREADY_AUTHORIZED
                            ? "\nДоступ уже разрешён. Нажмите «Я подтвердил — проверить»."
                            : "\nОдин запрос отправлен. Подтвердите на машине, затем нажмите «Я подтвердил — проверить».")));
                } catch (Exception error) {
                    postAccess(() -> status.append("\nЗапрос не подтверждён: " + error.getMessage() + "\nАвтоматических повторов нет."));
                } finally {
                    postAccess(() -> { check.setEnabled(true); again.setEnabled(true); importKey.setEnabled(true); });
                }
            }, "vehicle-adb-request").start();
        });
        check.setOnClickListener(v -> {
            check.setEnabled(false); request.setEnabled(false); again.setEnabled(false); importKey.setEnabled(false);
            new Thread(() -> {
                try {
                    if (!new java.io.File(getNoBackupFilesDir(), "adb_auth_key_v1").isFile())
                        throw new java.io.IOException("Сначала нажмите «Разрешить доступ ADB»");
                    LocalAdbClient.loopbackOnly(getApplicationContext()).shell("true");
                    postAccess(this::recreate);
                } catch (Exception error) {
                    postAccess(() -> status.append("\nДоступ пока не подтверждён: " + error.getMessage()));
                } finally {
                    postAccess(() -> { check.setEnabled(true); again.setEnabled(true); importKey.setEnabled(true);
                        request.setEnabled(!getSharedPreferences("access", MODE_PRIVATE).getBoolean("requested", false)); });
                }
            }, "vehicle-adb-check").start();
        });
        again.setOnClickListener(v -> {
            if (getSharedPreferences("access", MODE_PRIVATE).edit().putBoolean("requested", false).commit()) {
                request.setEnabled(true);
                status.append("\nРазрешена новая попытка. Запрос ещё не отправлен; ключ и системная очередь не менялись.");
            }
        });
    }
    private void pickKey() {
        android.content.Intent pick = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        pick.setType("*/*");
        pick.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick, 1);
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream input = getContentResolver().openInputStream(data.getData())) {
            byte[] bytes = input.readNBytes(32769);
            java.io.DataInputStream parsed = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));
            if (bytes.length > 32768 || parsed.readInt() != 0x44414b31) throw new Exception("Неверный формат ключа");
            java.security.KeyFactory factory = java.security.KeyFactory.getInstance("RSA");
            int privateLength = parsed.readInt();
            if (privateLength < 1 || privateLength > 16384) throw new Exception("Неверный ключ");
            java.security.PrivateKey privateKey = factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(parsed.readNBytes(privateLength)));
            int publicLength = parsed.readInt();
            if (publicLength < 1 || publicLength > 16384) throw new Exception("Неверный ключ");
            java.security.PublicKey publicKey = factory.generatePublic(new java.security.spec.X509EncodedKeySpec(parsed.readNBytes(publicLength)));
            if (((java.security.interfaces.RSAKey) privateKey).getModulus().bitLength() != 2048) throw new Exception("Нужна RSA2048 ADB identity");
            if (parsed.available() != 0) throw new Exception("Лишние данные");
            java.security.Signature proof = java.security.Signature.getInstance("SHA256withRSA");
            proof.initSign(privateKey); proof.update(new byte[] {1,2,3}); byte[] signature = proof.sign();
            proof.initVerify(publicKey); proof.update(new byte[] {1,2,3});
            if (!proof.verify(signature)) throw new Exception("Ключи не образуют пару");
            android.util.AtomicFile target = new android.util.AtomicFile(new java.io.File(getNoBackupFilesDir(), "adb_auth_key_v1"));
            java.io.FileOutputStream output = target.startWrite();
            try { output.write(bytes); target.finishWrite(output); }
            catch (Exception e) { target.failWrite(output); throw e; }
            recreate();
        } catch (Exception error) {
            android.widget.Toast.makeText(this, "Импорт не выполнен: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onPause(){visible=false;epoch++;cancelVoice();VoiceKeyService.detach(this);handler.removeCallbacks(poll);releaseHold();super.onPause();}
    @Override public void onWindowFocusChanged(boolean hasFocus){
        super.onWindowFocusChanged(hasFocus);boolean changed=focused!=hasFocus;focused=hasFocus;
        if(changed&&!hasFocus){epoch++;cancelVoice();releaseHold();}
        else if(changed&&hasFocus&&visible&&remoteScreen)refresh();
    }
    @Override protected void onResume(){super.onResume();visible=true;VoiceKeyService.attach(this);if(remoteScreen){if(feedback!=null&&runtime.activeHold==null)feedback.setText("Выберите действие. Принятие команды ещё не подтверждает движение.");handler.removeCallbacks(poll);handler.post(poll);}}
    @Override protected void onDestroy(){if(voiceInput!=null)voiceInput.close();VoiceKeyService.detach(this);handler.removeCallbacksAndMessages(null);releaseHold();super.onDestroy();}
    @Override public void onBackPressed(){cancelVoice();releaseHold();super.onBackPressed();}
}
