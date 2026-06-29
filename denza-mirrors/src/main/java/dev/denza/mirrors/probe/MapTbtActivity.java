package dev.denza.mirrors.probe;

import android.graphics.Color;

public class MapTbtActivity extends ProjectionTargetActivity {
    @Override
    protected String titleText() {
        return "TBT PROJECTION";
    }

    @Override
    protected int backgroundColor() {
        return Color.rgb(34, 30, 62);
    }
}
