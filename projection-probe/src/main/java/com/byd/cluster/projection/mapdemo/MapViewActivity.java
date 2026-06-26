package com.byd.cluster.projection.mapdemo;

import android.graphics.Color;

public class MapViewActivity extends ProjectionTargetActivity {
    @Override
    protected String titleText() {
        return "FULL PROJECTION";
    }

    @Override
    protected int backgroundColor() {
        return Color.rgb(12, 44, 52);
    }
}
