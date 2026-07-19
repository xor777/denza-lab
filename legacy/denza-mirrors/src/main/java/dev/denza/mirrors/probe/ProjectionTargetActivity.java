package dev.denza.mirrors.probe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public abstract class ProjectionTargetActivity extends Activity {
    private static final String TAG = "DenzaProjectionProbe";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Display display = getDisplay();
        int displayId = display == null ? -1 : display.getDisplayId();
        Log.i(TAG, getClass().getSimpleName() + " displayId=" + displayId);
        setContentView(createView(displayId));
    }

    private LinearLayout createView(int displayId) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(40, 28, 40, 28);
        root.setBackgroundColor(backgroundColor());

        TextView title = new TextView(this);
        title.setText(titleText());
        title.setTextColor(Color.WHITE);
        title.setTextSize(42);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView details = new TextView(this);
        details.setText("package " + getPackageName() + "\ndisplay " + displayId);
        details.setTextColor(Color.rgb(210, 225, 235));
        details.setTextSize(22);
        details.setGravity(Gravity.CENTER);
        root.addView(details);

        return root;
    }

    protected abstract String titleText();

    protected abstract int backgroundColor();
}
