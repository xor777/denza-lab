package dev.denza.mirrors.probe;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class AvcTurnSignalMonitorActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent serviceIntent = new Intent(this, AvcTurnSignalMonitorService.class);
        if (getIntent() != null && getIntent().getExtras() != null) {
            serviceIntent.putExtras(getIntent().getExtras());
        }
        if (getIntent() != null && getIntent().getAction() != null) {
            serviceIntent.setAction(getIntent().getAction());
        }
        try {
            startService(serviceIntent);
            Log.i(TAG, "turn monitor activity started service");
        } catch (RuntimeException e) {
            Log.i(TAG, "turn monitor activity start service failed", e);
        }
        finishAndRemoveTask();
    }
}
