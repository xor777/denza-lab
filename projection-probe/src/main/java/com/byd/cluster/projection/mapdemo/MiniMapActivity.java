package com.byd.cluster.projection.mapdemo;

import android.graphics.Color;

public class MiniMapActivity extends ProjectionTargetActivity {
    @Override
    protected String titleText() {
        return "MINI PROJECTION";
    }

    @Override
    protected int backgroundColor() {
        return Color.rgb(48, 30, 18);
    }
}
