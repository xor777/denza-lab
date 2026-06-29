package dev.denza.apps;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "Big slider" app picker: a horizontally scrollable strip of installed apps. Tap
 * to mark/unmark an app for Simulcast. Selection is capped at
 * {@link SimulcastApps#MAX_SELECTED}; at the cap you must unmark one to add another.
 * Selection order is the row order. denza-apps ships defaults (Russian apps that
 * are installed) but works on any car with any app set.
 */
public class AppPickerActivity extends Activity {
    private final List<String> selected = new ArrayList<>();
    private final List<TileRef> tiles = new ArrayList<>();
    private TextView counterText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        selected.addAll(SimulcastApps.getSelected(this));
        setContentView(buildContent());
        updateCounter();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 10, 12));
        root.setPadding(dp(40), dp(36), dp(40), dp(36));

        TextView title = new TextView(this);
        title.setText("Приложения для трансляции");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        counterText = new TextView(this);
        counterText.setTextColor(Color.rgb(150, 162, 176));
        counterText.setTextSize(19);
        LinearLayout.LayoutParams counterParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        counterParams.topMargin = dp(8);
        counterParams.bottomMargin = dp(20);
        root.addView(counterText, counterParams);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        for (AppEntry entry : loadApps()) {
            strip.addView(buildTile(entry));
        }
        scroller.addView(strip);

        // Center the strip vertically with some breathing room.
        ScrollView outer = new ScrollView(this);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stripParams.topMargin = dp(24);
        wrap.addView(scroller, stripParams);
        outer.addView(wrap);
        LinearLayout.LayoutParams outerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(outer, outerParams);

        return root;
    }

    private View buildTile(AppEntry entry) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(168),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tileParams.rightMargin = dp(14);
        tile.setLayoutParams(tileParams);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(entry.icon);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(112), dp(112));
        tile.addView(icon, iconParams);

        TextView label = new TextView(this);
        label.setText(entry.label);
        label.setTextColor(Color.rgb(222, 228, 236));
        label.setTextSize(18);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(10);
        tile.addView(label, labelParams);

        TileRef ref = new TileRef(entry.packageName, tile);
        tiles.add(ref);
        tile.setOnClickListener(v -> onTileTapped(ref));
        applyTileStyle(ref);
        return tile;
    }

    private void onTileTapped(TileRef ref) {
        if (selected.contains(ref.packageName)) {
            selected.remove(ref.packageName);
        } else if (selected.size() >= SimulcastApps.MAX_SELECTED) {
            Toast.makeText(this,
                    "Максимум " + SimulcastApps.MAX_SELECTED
                            + ". Снимите выбор с другого приложения.",
                    Toast.LENGTH_SHORT).show();
            return;
        } else {
            selected.add(ref.packageName);
        }
        SimulcastApps.setSelected(this, selected);
        applyTileStyle(ref);
        updateCounter();
    }

    private void applyTileStyle(TileRef ref) {
        boolean on = selected.contains(ref.packageName);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        if (on) {
            bg.setColor(Color.rgb(20, 46, 74));
            bg.setStroke(dp(3), Color.rgb(48, 144, 255));
        } else {
            bg.setColor(Color.rgb(18, 22, 28));
            bg.setStroke(dp(2), Color.rgb(36, 42, 50));
        }
        ref.view.setBackground(bg);
    }

    private void updateCounter() {
        if (counterText != null) {
            counterText.setText("Выбрано " + selected.size() + " из "
                    + SimulcastApps.MAX_SELECTED + " · нажмите, чтобы отметить");
        }
    }

    private List<AppEntry> loadApps() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
        Set<String> seen = new LinkedHashSet<>();
        List<AppEntry> entries = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            CharSequence label = ri.loadLabel(pm);
            Drawable icon = ri.loadIcon(pm);
            entries.add(new AppEntry(pkg, label == null ? pkg : label.toString(), icon));
        }
        // Selected first (in order), then the rest alphabetically.
        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int ia = selected.indexOf(a.packageName);
                int ib = selected.indexOf(b.packageName);
                if (ia >= 0 || ib >= 0) {
                    if (ia < 0) {
                        return 1;
                    }
                    if (ib < 0) {
                        return -1;
                    }
                    return Integer.compare(ia, ib);
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return entries;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class AppEntry {
        final String packageName;
        final String label;
        final Drawable icon;

        AppEntry(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final class TileRef {
        final String packageName;
        final View view;

        TileRef(String packageName, View view) {
            this.packageName = packageName;
            this.view = view;
        }
    }
}
