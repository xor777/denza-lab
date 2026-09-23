package dev.denza.avcstock.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/** Starts the process and nothing else, so the self-start gate lets the next broadcast through. */
public final class ProbeWakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(ProbeReceiver.TAG, "op=wake uid=" + Process.myUid());
        finish();
    }
}
