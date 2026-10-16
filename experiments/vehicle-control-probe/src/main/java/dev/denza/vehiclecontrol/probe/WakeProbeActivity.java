package dev.denza.vehiclecontrol.probe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;

/** Voice mode, observation and seat calibration. No wheel-key listener. */
public final class WakeProbeActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView state,phase,heard,decision,wake,place,calibration,counts;
    private Button listen;private final Button[] seats=new Button[6];
    private final ProgressBar[] meters=new ProgressBar[6];
    private final Runnable poll=new Runnable(){public void run(){render();main.postDelayed(this,150);}};
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private TextView text(String s,int size){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(Color.WHITE);v.setPadding(dp(8),dp(6),dp(8),dp(6));return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(17);b.setAllCaps(false);b.setMinHeight(dp(52));return b;}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root=column();root.setPadding(dp(24),dp(12),dp(24),dp(12));root.setBackgroundColor(Color.rgb(16,22,25));
        root.addView(text("«Привет, Денза» · слышимость и места",28));
        root.addView(text("«Привет, Денза» → команда → план. Выполнение включается отдельно; сомнительный разбор требует подтверждения.",16));
        Switch execute=new Switch(this);execute.setText("Выполнять голосовые команды автомобиля");execute.setTextColor(Color.WHITE);execute.setTextSize(17);
        execute.setChecked(HandsFreeAccess.executionEnabled(this));execute.setOnCheckedChangeListener((b,on)->HandsFreeAccess.setExecutionEnabled(this,on));root.addView(execute);
        state=text("",16);root.addView(state);phase=text("",22);root.addView(phase);
        LinearLayout bars=new LinearLayout(this);for(int i=0;i<6;i++){ProgressBar b=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);b.setMax(100);meters[i]=b;LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(18),1);p.setMargins(dp(6),0,dp(6),dp(8));bars.addView(b,p);}root.addView(bars);
        LinearLayout content=new LinearLayout(this);LinearLayout left=column(),right=column();
        left.addView(text("Калибровка: выберите кресло, уже сидя на нём. Скажите «Привет, Денза» 3 раза с паузами 3 с. Сохранение образца не зависит от распознавания имени.",17));
        for(int row=0;row<3;row++){LinearLayout line=new LinearLayout(this);for(int side=0;side<2;side++){int n=row*2+side;Button b=button(SeatProfiles.LABELS[n]);seats[n]=b;b.setOnClickListener(v->startService(new Intent(this,WakeProbeService.class).setAction("calibrate").putExtra("seat",n)));line.addView(b,new LinearLayout.LayoutParams(0,dp(56),1));}left.addView(line);}
        calibration=text("Выберите первое кресло: Водитель",20);left.addView(calibration);
        heard=text("",23);right.addView(heard);decision=text("",18);right.addView(decision);wake=text("Пока не было принятых обращений",19);right.addView(wake);place=text("Место: нужны образцы кресел",24);right.addView(place);counts=text("",16);right.addView(counts);
        right.addView(text("Сначала смотрите «Услышано» и причину отказа. Это помогает отличить захват речи от проверки слова «Денза».",16));
        content.addView(left,new LinearLayout.LayoutParams(0,-2,1));content.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        ScrollView scroll=new ScrollView(this);scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);listen=button("Слушать");listen.setOnClickListener(v->{if(WakeProbeService.active)stopListening();else begin();});actions.addView(listen,new LinearLayout.LayoutParams(0,-2,1));
        Button hide=button("Оставить в фоне");hide.setOnClickListener(v->moveTaskToBack(true));actions.addView(hide,new LinearLayout.LayoutParams(0,-2,1));
        Button back=button("Вернуться в пульт");back.setOnClickListener(v->finish());actions.addView(back,new LinearLayout.LayoutParams(0,-2,1));root.addView(actions);setContentView(root);
    }
    private void begin(){HandsFreeAccess.request(this,64);}
    private void stopListening(){HandsFreeAccess.stop(this);}
    private void render(){
        JSONObject s=WakeProbeService.snapshot();state.setText(s.optString("status"));phase.setText(s.optString("phase"));listen.setText(s.optBoolean("active")?"Остановить":"Слушать");
        heard.setText("Услышано: «"+s.optString("heard")+"»");decision.setText(s.optString("decision"));
        String command=s.optString("turn_text");if(command.isEmpty())command=s.optString("last_command");if(!s.optString("turn_message").isEmpty())command+="\n"+s.optString("turn_message");else if(!s.optString("last_plan").isEmpty())command+="\nПлан: "+s.optString("last_plan");
        String w=s.optString("last_wake");if(!w.isEmpty())wake.setText(w+" · всего "+s.optLong("hits")+(command.isEmpty()?"":"\nКоманда: "+command));String p=s.optString("seat");if(!p.isEmpty())place.setText(p);
        String c=s.optString("calibration");if(!c.isEmpty())calibration.setText(c);
        JSONArray a=s.optJSONArray("samples"),levels=s.optJSONArray("levels");int done=0;
        for(int i=0;i<6;i++){int n=a==null?0:a.optInt(i);if(n>=3)done++;seats[i].setEnabled(s.optBoolean("ready"));seats[i].setText((n>=3?"✓ ":"")+SeatProfiles.LABELS[i]+" · "+n+"/3");double value=levels==null?0:levels.optDouble(i);meters[i].setProgress((int)Math.min(100,Math.max(0,20*Math.log10(Math.max(1,value))/60*100)));}
        counts.setText("Реплик услышано: "+s.optLong("clips")+" · разобрано: "+s.optLong("decoded")+"\nКресла: "+done+"/6. Место — предположение по уровню микрофонов.");
    }
    @Override protected void onResume(){super.onResume();main.post(poll);}
    @Override protected void onPause(){main.removeCallbacks(poll);super.onPause();}
    @Override public void onBackPressed(){super.onBackPressed();}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] v){super.onRequestPermissionsResult(r,p,v);if(r==64&&v.length>0&&v[0]==PackageManager.PERMISSION_GRANTED)begin();}
}
