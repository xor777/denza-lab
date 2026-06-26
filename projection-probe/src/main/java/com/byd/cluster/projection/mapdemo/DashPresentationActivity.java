package com.byd.cluster.projection.mapdemo;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

public class DashPresentationActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String CLUSTER_DISPLAY_NAME = "shared_fission_bg_XDJAScreenProjection_0";
    private static final int DEFAULT_DISPLAY_ID = 3;
    private static final long DEFAULT_DURATION_MS = 10000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Presentation presentation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        int requestedDisplayId = getIntent().getIntExtra("display_id", DEFAULT_DISPLAY_ID);
        long durationMs = getIntent().getLongExtra("duration_ms", DEFAULT_DURATION_MS);
        Display display = findDisplay(requestedDisplayId);
        if (display == null) {
            Log.i(TAG, "dash presentation display not found id=" + requestedDisplayId);
            finishAndRemoveTask();
            return;
        }

        try {
            presentation = new DashPresentation(this, display);
            presentation.show();
            Log.i(TAG, "dash presentation shown displayId=" + display.getDisplayId()
                    + " name=" + display.getName());
        } catch (RuntimeException e) {
            Log.i(TAG, "dash presentation show failed", e);
            finishAndRemoveTask();
            return;
        }

        handler.postDelayed(this::finishAndRemoveTask, durationMs);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
        super.onDestroy();
    }

    private Display findDisplay(int requestedDisplayId) {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            return null;
        }
        Display display = displayManager.getDisplay(requestedDisplayId);
        if (display != null) {
            return display;
        }
        for (Display candidate : displayManager.getDisplays()) {
            if (candidate.getName() != null && candidate.getName().contains(CLUSTER_DISPLAY_NAME)) {
                return candidate;
            }
        }
        return null;
    }

    private static final class DashPresentation extends Presentation {
        DashPresentation(Context outerContext, Display display) {
            super(outerContext, display);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            LinearLayout root = new LinearLayout(getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setPadding(48, 32, 48, 32);
            root.setBackgroundColor(Color.rgb(11, 86, 69));

            TextView title = new TextView(getContext());
            title.setText("DASH PRESENTATION TEST");
            title.setTextColor(Color.WHITE);
            title.setTextSize(34);
            title.setGravity(Gravity.CENTER);
            title.setAllCaps(false);
            root.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            TextView subtitle = new TextView(getContext());
            subtitle.setText("display 3 / 2560x720");
            subtitle.setTextColor(Color.rgb(210, 255, 242));
            subtitle.setTextSize(22);
            subtitle.setGravity(Gravity.CENTER);
            root.addView(subtitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            setContentView(root);
        }
    }
}
