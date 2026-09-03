package dev.denza.dicarmedia.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * Starts the process and nothing else: the firmware self-start gate drops an explicit broadcast to
 * a third-party UID that has no live process, so the host wakes the probe here before it
 * broadcasts.
 */
public final class ProbeWakeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(ProbeReceiver.TAG, "op=wake uid=" + Process.myUid());
        finish();
    }
}
