package dev.denza.vehiclecontrol.probe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Non-focusable application overlay with a scrollable plan, explicit confirmation and cancel. Main thread only. */
final class AssistantOverlay {
    private final Context context;
    private final Runnable cancel,confirm;
    private Button apply;
    private final WindowManager manager;
    private LinearLayout root;
    private TextView title,transcript,seat;
    private ImageView microphone;
    private ValueAnimator pulse;
    AssistantOverlay(Context context,Runnable cancel,Runnable confirm){this.context=context;this.cancel=cancel;this.confirm=confirm;manager=context.getSystemService(WindowManager.class);}
    private int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    private TextView text(int size,int color){TextView v=new TextView(context);v.setTextSize(size);v.setTextColor(color);return v;}
    void render(AssistantTurn.State state){
        if(!state.visible()){hide();return;}
        if(!Settings.canDrawOverlays(context))throw new IllegalStateException("Нет разрешения показывать индикатор поверх приложений");
        if(root==null)show();
        title.setText(switch(state.stage()){case LISTENING->state.text().isEmpty()?"Слушаю…":"Слушаю команду…";case REVIEW->"Подтвердите план";case EXECUTING->"Выполняю…";case RESULT->"Результат";case TIMEOUT->"Время ожидания истекло";default->"";});
        transcript.setText(!state.message().isEmpty()?state.text()+"\n"+state.message():state.text().isEmpty()?state.listening()?"Скажите команду":"Речь не распознана":state.text());
        apply.setVisibility(state.stage()==AssistantTurn.Stage.REVIEW?View.VISIBLE:View.GONE);
        seat.setText(state.seat()+(HandsFreeAccess.executionEnabled(context)?"":" · выполнение выключено"));
        microphone.setAlpha(state.listening()?1f:0.8f);
        if(state.listening()&&!pulse.isStarted())pulse.start();else if(!state.listening()&&pulse.isStarted())pulse.cancel();
    }
    private void show(){
        int accent=Color.rgb(131,224,204);
        root=new LinearLayout(context);root.setGravity(Gravity.CENTER_VERTICAL);root.setPadding(dp(18),dp(14),dp(12),dp(14));
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(24,36,43));bg.setCornerRadius(dp(22));bg.setStroke(dp(2),accent);root.setBackground(bg);root.setElevation(dp(16));
        microphone=new ImageView(context);microphone.setImageResource(android.R.drawable.ic_btn_speak_now);microphone.setColorFilter(accent);microphone.setContentDescription("Микрофон голосового помощника");root.addView(microphone,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),0,dp(12),0);
        title=text(23,Color.WHITE);title.setTypeface(null,Typeface.BOLD);body.addView(title);
        transcript=text(20,Color.WHITE);ScrollView details=new ScrollView(context);details.setFillViewport(false);details.addView(transcript);
        body.addView(details,new LinearLayout.LayoutParams(-1,dp(138)));
        seat=text(14,accent);body.addView(seat);root.addView(body,new LinearLayout.LayoutParams(0,-2,1));
        apply=new Button(context);apply.setText("Выполнить");apply.setAllCaps(false);apply.setOnClickListener(v->confirm.run());apply.setVisibility(View.GONE);root.addView(apply);
        Button dismiss=new Button(context);dismiss.setText("×");dismiss.setTextSize(26);dismiss.setContentDescription("Отменить прослушивание");dismiss.setOnClickListener(v->cancel.run());root.addView(dismiss,new LinearLayout.LayoutParams(dp(58),dp(58)));
        int width=Math.min(dp(690),context.getResources().getDisplayMetrics().widthPixels-dp(48));
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=dp(76);p.setTitle("DenzaVoiceAssistant");
        try{manager.addView(root,p);}catch(RuntimeException e){root=null;throw e;}
        pulse=ValueAnimator.ofFloat(0.35f,1f);pulse.setDuration(700);pulse.setRepeatCount(ValueAnimator.INFINITE);pulse.setRepeatMode(ValueAnimator.REVERSE);pulse.addUpdateListener(a->{if(microphone!=null)microphone.setAlpha((float)a.getAnimatedValue());});
    }
    void hide(){
        if(pulse!=null){pulse.cancel();pulse.removeAllUpdateListeners();pulse=null;}
        if(root!=null){root.setVisibility(View.INVISIBLE);try{manager.removeViewImmediate(root);}catch(RuntimeException ignored){try{manager.removeView(root);}catch(RuntimeException ignoredAgain){}}root=null;}
    }
}
