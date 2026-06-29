package dev.denza.mirrors.probe;

import android.app.Activity;
import android.os.Bundle;

public class DiShareProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiShareProbeReceiver.runProbe(this, getIntent(), this::finish);
    }
}
