package dev.denza.vehiclecontrol.probe;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/** Microphone power preference, separate from per-command wake activation. No boot receiver. */
final class HandsFreeAccess {
    static boolean enabled(Context c){return c.getSharedPreferences("hands-free",Context.MODE_PRIVATE).getBoolean("enabled",true);}
    static void setEnabled(Context c,boolean on){c.getSharedPreferences("hands-free",Context.MODE_PRIVATE).edit().putBoolean("enabled",on).apply();}
    /** Commands run by default; the voice settings switch turns execution off. Guards stay in the controller. */
    static boolean executionEnabled(Context c){return c.getSharedPreferences("hands-free",Context.MODE_PRIVATE).getBoolean("execute",true);}
    static void setExecutionEnabled(Context c,boolean enabled){WakeProbeService.cancelCurrentTurn();c.getSharedPreferences("hands-free",Context.MODE_PRIVATE).edit().putBoolean("execute",enabled).apply();}
    static String missing(Context c){
        if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)return "Нужен доступ к микрофону";
        if(!Settings.canDrawOverlays(c))return "Нужно разрешение для индикатора поверх приложений";
        if(!new java.io.File(c.getNoBackupFilesDir(),"adb_auth_key_v1").isFile())return "Сначала подключите ADB пульта";
        return "";
    }
    static void start(Context c){c.startForegroundService(new Intent(c,WakeProbeService.class));}
    static void stop(Context c){setEnabled(c,false);if(WakeProbeService.active)c.startService(new Intent(c,WakeProbeService.class).setAction("stop"));}
    static void request(Activity a,int code){
        setEnabled(a,true);
        if(a.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},code);return;}
        if(!Settings.canDrawOverlays(a)){a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+a.getPackageName())));return;}
        if(missing(a).isEmpty())start(a);
    }
}
