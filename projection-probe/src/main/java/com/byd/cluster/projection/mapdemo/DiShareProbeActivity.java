package com.byd.cluster.projection.mapdemo;

import android.app.Activity;
import android.os.Bundle;

public class DiShareProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiShareProbeReceiver.runProbe(this, getIntent(), this::finish);
    }
}
