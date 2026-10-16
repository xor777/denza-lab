package dev.denza.vehiclecontrol.probe;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.lang.ref.WeakReference;

/** The already proven key-filter path, owned only while this remote is focused. */
public final class VoiceKeyService extends AccessibilityService {
    private static WeakReference<RemoteActivity> remote=new WeakReference<>(null);
    private final Handler main=new Handler(Looper.getMainLooper());
    private WeakReference<RemoteActivity> pressed=new WeakReference<>(null);
    private long downTime;
    private int device;
    private int activityGeneration;
    private boolean ownsPress;
    static void attach(RemoteActivity activity){remote=new WeakReference<>(activity);}
    static void detach(RemoteActivity activity){if(remote.get()==activity)remote.clear();}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){}
    @Override public void onInterrupt(){ownsPress=false;pressed.clear();}
    @Override protected boolean onKeyEvent(KeyEvent event){
        if(event.getKeyCode()!=320)return false;
        // A disconnected device can lose UP. A fresh physical DOWN starts a new press.
        if(ownsPress&&event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0
                &&(event.getDeviceId()!=device||event.getDownTime()!=downTime)){ownsPress=false;pressed.clear();}
        if(ownsPress){
            if(event.getDeviceId()!=device||event.getDownTime()!=downTime)return false;
            if(event.getAction()==KeyEvent.ACTION_UP){
                ownsPress=false;RemoteActivity activity=pressed.get();pressed.clear();
                long queuedAt=SystemClock.uptimeMillis();int expectedGeneration=activityGeneration;
                if(!event.isCanceled()&&fresh(event)&&activity!=null&&activity.voiceKeyAvailable()&&activity.voiceKeyGeneration()==expectedGeneration)
                    main.post(()->{if(SystemClock.uptimeMillis()-queuedAt<=350&&activity.voiceKeyAvailable()&&activity.voiceKeyGeneration()==expectedGeneration)activity.toggleVoice();});
            }
            return true;
        }
        RemoteActivity activity=remote.get();
        if(event.getAction()!=KeyEvent.ACTION_DOWN||event.getRepeatCount()!=0||!fresh(event)||activity==null||!activity.voiceKeyAvailable())return false;
        ownsPress=true;device=event.getDeviceId();downTime=event.getDownTime();activityGeneration=activity.voiceKeyGeneration();pressed=new WeakReference<>(activity);
        return true;
    }
    private static boolean fresh(KeyEvent event){long age=SystemClock.uptimeMillis()-event.getEventTime();return age>=0&&age<=350;}
    @Override public void onDestroy(){main.removeCallbacksAndMessages(null);pressed.clear();super.onDestroy();}
}
