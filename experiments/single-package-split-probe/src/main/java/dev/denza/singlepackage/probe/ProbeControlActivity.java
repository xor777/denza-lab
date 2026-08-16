package dev.denza.singlepackage.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Permanent app entry used to prove that a second launcher alias in the same package can be
 * shown and hidden without changing the package-wide BYD split marker.
 */
public final class ProbeControlActivity extends Activity {
    public static final String EXTRA_ICON_ENABLED =
            "dev.denza.singlepackage.probe.extra.ICON_ENABLED";

    private static final String TAG = "SinglePackageProbe";
    private static final String ALIAS_CLASS =
            "dev.denza.singlepackage.probe.SplitEntryAlias";

    private Switch iconSwitch;
    private TextView status;
    private boolean rendering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildContent());
        applyRequestedState(getIntent());
        renderState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyRequestedState(intent);
        renderState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderState();
    }

    private LinearLayout buildContent() {
        int padding = dp(40);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.rgb(12, 15, 20));

        TextView title = text("Single-package split probe", 30, Color.WHITE);
        root.addView(title, matchWrap());

        TextView explanation = text(
                "Одна package identity: постоянный app entry, управляемый split alias и INFO picker.",
                18,
                Color.rgb(185, 194, 207));
        LinearLayout.LayoutParams explanationParams = matchWrap();
        explanationParams.topMargin = dp(14);
        root.addView(explanation, explanationParams);

        iconSwitch = new Switch(this);
        iconSwitch.setText("Показывать иконку «Разделить экран — probe»");
        iconSwitch.setTextColor(Color.WHITE);
        iconSwitch.setTextSize(20);
        LinearLayout.LayoutParams switchParams = matchWrap();
        switchParams.topMargin = dp(34);
        root.addView(iconSwitch, switchParams);
        iconSwitch.setOnCheckedChangeListener(this::onSwitchChanged);

        status = text("", 17, Color.rgb(109, 220, 255));
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(24);
        root.addView(status, statusParams);
        return root;
    }

    private void onSwitchChanged(CompoundButton ignored, boolean enabled) {
        if (rendering) return;
        setAliasEnabled(enabled);
        renderState();
    }

    private void applyRequestedState(Intent intent) {
        if (intent != null && intent.hasExtra(EXTRA_ICON_ENABLED)) {
            setAliasEnabled(intent.getBooleanExtra(EXTRA_ICON_ENABLED, false));
            intent.removeExtra(EXTRA_ICON_ENABLED);
        }
    }

    private void setAliasEnabled(boolean enabled) {
        int state = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        getPackageManager().setComponentEnabledSetting(
                alias(),
                state,
                PackageManager.DONT_KILL_APP | PackageManager.SYNCHRONOUS);
        Log.i(TAG, "ALIAS_SET enabled=" + enabled + " state=" + state);
    }

    private void renderState() {
        if (iconSwitch == null || status == null) return;
        boolean enabled = isAliasEnabled();
        rendering = true;
        iconSwitch.setChecked(enabled);
        rendering = false;

        Intent restoreIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        ComponentName restoreComponent = restoreIntent == null ? null : restoreIntent.getComponent();
        String restore = restoreComponent == null ? "null" : restoreComponent.flattenToShortString();
        String value = "alias=" + (enabled ? "ENABLED" : "DISABLED")
                + "\ngetLaunchIntentForPackage=" + restore
                + "\ncontrolTaskId=" + getTaskId();
        status.setText(value);
        Log.i(TAG, "STATE " + value.replace('\n', ' '));
    }

    private boolean isAliasEnabled() {
        return getPackageManager().getComponentEnabledSetting(alias())
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
    }

    private ComponentName alias() {
        return new ComponentName(getPackageName(), ALIAS_CLASS);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
