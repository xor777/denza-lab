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
    private volatile boolean visible,remoteScreen;
    private volatile int epoch;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TextView status,feedback;
    private LinearLayout body;
    private ScrollView scroll;
    private JSONObject state;
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
        releaseHold();remoteScreen=true;epoch++;state=null;tabButtons.clear();controls.clear();stopControls.clear();
        LinearLayout root=column();root.setPadding(dp(24),dp(14),dp(24),dp(12));root.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Denza · Пульт",27,TEXT);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button access=button("Доступ ADB");access.setOnClickListener(v->showAccess());header.addView(access);
        Button update=button("Обновить");update.setOnClickListener(v->refresh());header.addView(update);root.addView(header);
        status=text("Читаю состояние автомобиля…",16,ACCENT);root.addView(status);
        LinearLayout tabs=new LinearLayout(this);
        for(String tab:List.of("windows","shades","lights","doors","seats","climate")){
            Button b=button(Map.of("windows","Окна","shades","Панорама","lights","Свет","doors","Двери","seats","Сиденья","climate","Климат").get(tab));
            tabButtons.put(b,tab);b.setOnClickListener(v->{releaseHold();section=tab;feedback.setText(runtime.activeHold==null?"Выберите действие. Принятие команды ещё не подтверждает движение.":"Остановка кресла запрошена; ожидаю ответ STOP.");render();});tabs.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        }root.addView(tabs);
        scroll=new ScrollView(this);body=column();scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        feedback=text("Выберите раздел и действие. Принятие команды ещё не подтверждает движение.",16,TEXT);root.addView(feedback);
        root.addView(text("Управление в P · Для ручной работы завершите отдых/кемпинг · Кресла: удерживайте, до 5 с",13,MUTED));
        setContentView(root);render();if(visible){handler.removeCallbacks(poll);handler.post(poll);}
    }
    private void refresh(){
        if(!visible||!remoteScreen||runtime==null||!runtime.busy.compareAndSet(false,true))return;
        int generation=epoch;status.setText("Обновляю показания · ADB занят…");updateButtons();
        runtime.operations.execute(()->{
            if(!visible||!remoteScreen||generation!=epoch){runtime.busy.set(false);return;}
            JSONObject result=null;String error=null;
            try{result=runtime.controller.snapshot();}catch(Exception failure){error=failure.getMessage();}
            finally{runtime.busy.set(false);}
            JSONObject answer=result;String problem=error;
            handler.post(()->{if(!visible||!remoteScreen||isDestroyed()||generation!=epoch)return;
                state=answer;status.setText(problem!=null?problem:"N9 · "+(answer.optInt("park",-1)==1?"P · управление доступно":"не в P / неизвестно")+" · "+java.time.LocalTime.now().withNano(0));render();});
        });
    }
    private void updateButtons(){for(Button button:controls)button.setEnabled(state!=null&&!runtime.busy.get()&&Boolean.TRUE.equals(button.getTag()));}
    private void action(String target,String action){
        if(!visible||!remoteScreen||!runtime.busy.compareAndSet(false,true))return;
        int generation=epoch;feedback.setText("Отправляю команду…");updateButtons();
        runtime.operations.execute(()->{
            if(!visible||!remoteScreen||generation!=epoch){runtime.busy.set(false);return;}
            JSONObject result=null;String error=null;
            try{result=runtime.controller.act(target,action,()->visible&&remoteScreen&&generation==epoch);}catch(Exception failure){error=failure.getMessage();}finally{runtime.busy.set(false);}
            finishOperation(generation,result,error);
        });
    }
    private void startHold(Button source,String target,String direction){
        if(!visible||!remoteScreen||!runtime.busy.compareAndSet(false,true))return;
        final NativeController.Hold hold;
        try{hold=runtime.controller.newHold(target,direction);}catch(Exception failure){runtime.busy.set(false);feedback.setText(failure.getMessage());return;}
        runtime.activeHold=hold;ownedHold=hold;heldButton=source;int generation=epoch;
        feedback.setText("Удерживайте · до 5 с. Отпустите для остановки.");
        for(Button b:controls)if(b!=source)b.setEnabled(hold.target.equals(stopControls.get(b)));
        runtime.operations.execute(()->{
            if(!visible||!remoteScreen||generation!=epoch){runtime.activeHold=null;runtime.busy.set(false);return;}
            JSONObject result=null;String error=null;
            try{result=runtime.controller.hold(hold);}catch(Exception failure){error=failure.getMessage();}
            finally{runtime.activeHold=null;runtime.busy.set(false);}
            finishOperation(generation,result,error);
        });
    }
    private void finishOperation(int generation,JSONObject result,String error){handler.post(()->{
        if(!visible||!remoteScreen||isDestroyed()||generation!=epoch)return;
        heldButton=null;
        feedback.setText(error!=null?error+". Команда не повторялась.":result.optBoolean("cancelled")&&(!result.has("stop_accepted")||result.optBoolean("stop_accepted"))?"Нажатие отменено; новое движение не запущено.":result.optBoolean("accepted")?"Команда принята"+(result.has("stop_accepted")?" · остановка также принята":"")+". Проверьте фактический результат.":result.optString("error","Команда не подтверждена · код "+result.optInt("code",-1)));
        state=null;render();refresh();});}
    private void releaseHold(){if(runtime!=null&&ownedHold!=null&&runtime.activeHold==ownedHold)runtime.releaseHold();ownedHold=null;heldButton=null;}
    private LinearLayout card(String name,String value,String reason){
        LinearLayout card=column();card.setPadding(dp(18),dp(12),dp(18),dp(14));
        GradientDrawable background=new GradientDrawable();background.setColor(CARD);background.setCornerRadius(dp(14));card.setBackground(background);
        TextView title=text(name,19,TEXT);title.setTypeface(null,Typeface.BOLD);card.addView(title);if(value!=null)card.addView(text(value,21,ACCENT));if(reason!=null)card.addView(text(reason,14,MUTED));return card;
    }
    private LinearLayout row(LinearLayout card){LinearLayout row=new LinearLayout(this);card.addView(row);return row;}
    private Button control(LinearLayout row,String target,String action,String label,String reason,boolean hold){
        Button b=button(label);if(action.equals("stop"))stopControls.put(b,target);b.setTag(action.equals("stop")||reason==null);controls.add(b);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        if(hold){b.setContentDescription(label+" · удерживайте до 5 секунд");b.setOnTouchListener((v,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){if(!b.isEnabled())return false;v.getParent().requestDisallowInterceptTouchEvent(true);startHold(b,target,action);return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&(event.getX()<0||event.getY()<0||event.getX()>v.getWidth()||event.getY()>v.getHeight())){releaseHold();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){releaseHold();v.getParent().requestDisallowInterceptTouchEvent(false);b.performClick();return true;}return true;});
            b.setOnClickListener(v->{/* Motion start is only DOWN, never accessibility click/repeat. */});
        }else b.setOnClickListener(v->{if(action.equals("stop")&&runtime.activeHold!=null){releaseHold();return;}action(target,action);});
        return b;
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
        for(var tab:tabButtons.entrySet()){boolean selected=section.equals(tab.getValue());tab.getKey().setTextColor(selected?ACCENT:TEXT);tab.getKey().setBackgroundTintList(android.content.res.ColorStateList.valueOf(selected?Color.rgb(35,68,65):Color.rgb(47,61,68)));}
        int y=scroll.getScrollY();body.removeAllViews();controls.clear();stopControls.clear();
        if(state==null){body.addView(text("Нет свежих данных. Действия недоступны до чтения автомобиля.",18,MUTED));return;}
        try{
            JSONArray pending=state.getJSONArray("pending_stops");
            if(pending.length()>0){LinearLayout recovery=card("Проверьте остановку кресла","Ответ STOP не подтверждён",null);for(int i=0;i<pending.length();i++)control(row(recovery),pending.getString(i),"stop",pending.getString(i)+" · Стоп",null,false);body.addView(recovery);}
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
                        if(index>0)control(r,"climate."+key,actions.getString(index-1),"−",blocked(c),false);
                        if(index>=0&&index+1<actions.length())control(r,"climate."+key,actions.getString(index+1),"+",blocked(c),false);
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
            addGrid(cards);updateButtons();scroll.post(()->scroll.scrollTo(0,y));
        }catch(Exception failure){status.setText("Не удалось показать данные: "+failure.getMessage());state=null;updateButtons();}
    }
    private String lightLabel(String a){return Map.of("on","Вкл.","off","Выкл.","auto","AUTO","low","Ближний","other","Другой режим").getOrDefault(a,"Нет данных");}
    void showAccess() {
        releaseHold(); remoteScreen=false; epoch++; handler.removeCallbacks(poll);
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

    @Override protected void onPause(){visible=false;epoch++;handler.removeCallbacks(poll);releaseHold();super.onPause();}
    @Override protected void onResume(){super.onResume();visible=true;if(remoteScreen){if(feedback!=null&&runtime.activeHold==null)feedback.setText("Обновляю состояние. Не подтверждённый STOP блокирует новые движения кресел.");handler.removeCallbacks(poll);handler.post(poll);}}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);releaseHold();super.onDestroy();}
    @Override public void onBackPressed(){releaseHold();super.onBackPressed();}
}
