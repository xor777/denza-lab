package dev.denza.alicewheel.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit shell-only commands; the probe is never armed automatically. */
public final class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        VoiceKeyService service = VoiceKeyService.instance;
        if (service == null) {
            setResultCode(1); setResultData("service-not-connected"); return;
        }
        String op = intent.getStringExtra("op");
        if ("arm".equals(op)) service.arm(intent.getIntExtra("seconds", 120));
        else if ("disarm".equals(op)) service.disarm();
        else if (!"status".equals(op)) { setResultCode(2); return; }
        setResultCode(0); setResultData(service.status());
    }
}
