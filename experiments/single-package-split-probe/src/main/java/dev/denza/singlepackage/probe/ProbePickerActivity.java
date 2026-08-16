package dev.denza.singlepackage.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Set;

/** Pane-neutral picker analogue launched twice with the exact BYD pane categories. */
public final class ProbePickerActivity extends Activity {
    private static final String TAG = "SinglePackageProbe";
    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        renderState("created");
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderState("resumed");
    }

    private LinearLayout buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(28), dp(28), dp(28), dp(28));
        root.setBackgroundColor(Color.rgb(8, 10, 12));

        TextView title = new TextView(this);
        title.setText("Single-package picker");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(245, 193, 92));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        state = new TextView(this);
        state.setTextSize(17);
        state.setTextColor(Color.WHITE);
        state.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stateParams = matchWrap();
        stateParams.topMargin = dp(18);
        root.addView(state, stateParams);

        Button close = new Button(this);
        close.setText("Закрыть picker-task");
        close.setOnClickListener(ignored -> finishAndRemoveTask());
        LinearLayout.LayoutParams closeParams = wrapWrap();
        closeParams.topMargin = dp(26);
        root.addView(close, closeParams);
        return root;
    }

    private void renderState(String phase) {
        if (state == null) return;
        Intent restoreIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        ComponentName restoreComponent = restoreIntent == null ? null : restoreIntent.getComponent();
        String restore = restoreComponent == null ? "null" : restoreComponent.flattenToShortString();
        Set<String> categories = getIntent() == null ? null : getIntent().getCategories();
        String value = "taskId=" + getTaskId()
                + "\npackage=" + getPackageName()
                + "\ncategories=" + categories
                + "\nrestore=" + restore;
        state.setText(value);
        Log.i(TAG, "PICKER_" + phase.toUpperCase() + " " + value.replace('\n', ' '));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
