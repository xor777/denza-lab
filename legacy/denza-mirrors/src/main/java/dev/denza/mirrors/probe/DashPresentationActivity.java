package dev.denza.mirrors.probe;

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

import java.util.ArrayList;
import java.util.List;

public class DashPresentationActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String CLUSTER_DISPLAY_NAME = "shared_fission_bg_XDJAScreenProjection_0";
    private static final int DEFAULT_DISPLAY_ID = 3;
    private static final long DEFAULT_DURATION_MS = 10000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Presentation presentation;
    private final List<Presentation> presentations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        int requestedDisplayId = getIntent().getIntExtra("display_id", DEFAULT_DISPLAY_ID);
        long durationMs = getIntent().getLongExtra("duration_ms", DEFAULT_DURATION_MS);
        if (requestedDisplayId == -1) {
            showAllDisplays(durationMs);
            return;
        }

        Display display = findDisplay(requestedDisplayId);
        if (display == null) {
            Log.i(TAG, "dash presentation display not found id=" + requestedDisplayId);
            finishAndRemoveTask();
            return;
        }

        try {
            presentation = new DashPresentation(this, display, display.getDisplayId(),
                    display.getName());
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

    private void showAllDisplays(long durationMs) {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            finishAndRemoveTask();
            return;
        }

        Display defaultDisplay = getWindowManager().getDefaultDisplay();
        setContentView(buildProbeView(this, defaultDisplay.getDisplayId(), defaultDisplay.getName()));
        for (Display display : displayManager.getDisplays()) {
            if (display.getDisplayId() == defaultDisplay.getDisplayId()) {
                continue;
            }
            try {
                Presentation next = new DashPresentation(this, display, display.getDisplayId(),
                        display.getName());
                next.show();
                presentations.add(next);
                Log.i(TAG, "dash presentation all shown displayId=" + display.getDisplayId()
                        + " name=" + display.getName());
            } catch (RuntimeException e) {
                Log.i(TAG, "dash presentation all failed displayId=" + display.getDisplayId(), e);
            }
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
        for (Presentation next : presentations) {
            next.dismiss();
        }
        presentations.clear();
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
        private final int displayId;
        private final String displayName;

        DashPresentation(Context outerContext, Display display, int displayId,
                String displayName) {
            super(outerContext, display);
            this.displayId = displayId;
            this.displayName = displayName;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(buildProbeView(getContext(), displayId, displayName));
        }
    }

    private static LinearLayout buildProbeView(Context context, int displayId, String displayName) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 32, 48, 32);
        root.setBackgroundColor(probeColor(displayId));

        TextView title = new TextView(context);
        title.setText("DISPLAY " + displayId);
        title.setTextColor(Color.WHITE);
        title.setTextSize(56);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView subtitle = new TextView(context);
        subtitle.setText(displayName == null ? "unknown" : displayName);
        subtitle.setTextColor(Color.rgb(230, 245, 255));
        subtitle.setTextSize(22);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        return root;
    }

    private static int probeColor(int displayId) {
        switch (displayId) {
            case 0:
                return Color.rgb(42, 78, 180);
            case 2:
                return Color.rgb(159, 63, 33);
            case 3:
                return Color.rgb(11, 86, 69);
            case 4:
                return Color.rgb(97, 59, 164);
            default:
                return Color.rgb(70, 70, 70);
        }
    }
}
