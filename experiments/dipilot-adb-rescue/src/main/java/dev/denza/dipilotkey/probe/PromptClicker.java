package dev.denza.dipilotkey.probe;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Presses Allow on SystemUI's ADB dialog, including a window the driver cannot see.
 *
 * <p>Armed only for the duration of one rescue. Left enabled in Settings it does nothing until
 * the screen asks again, so a later dialog from some other computer is not approved on its own.
 */
public final class PromptClicker extends AccessibilityService {
    private static final long CLICK_COOLDOWN_MS = 1200;
    private static final long SCAN_INTERVAL_MS = 400;

    private static volatile PromptClicker instance;
    private static volatile boolean armed;
    private static final AtomicInteger clicks = new AtomicInteger();
    private static final AtomicInteger attempts = new AtomicInteger();
    private static final AtomicInteger scans = new AtomicInteger();
    private static final AtomicInteger windows = new AtomicInteger();
    private static final Set<Integer> dialogs = new HashSet<>();
    private static volatile String note = "";
    private static final Map<Integer, Long> clickedAt = new HashMap<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scan = new Runnable() {
        @Override
        public void run() {
            if (!armed || clicks.get() >= PromptDecision.MAX_CLICKS) {
                return;
            }
            try {
                scanOnce();
            } catch (RuntimeException error) {
                note = "Проверка окон не удалась: " + error.getClass().getSimpleName();
                // A window can go away between the list and the click. The next pass retries.
            }
            if (armed && clicks.get() < PromptDecision.MAX_CLICKS) {
                handler.postDelayed(this, SCAN_INTERVAL_MS);
            }
        }
    };

    static void resetDiagnostics() {
        disarm();
        clicks.set(0);
        attempts.set(0);
        scans.set(0);
        windows.set(0);
        note = "";
        synchronized (clickedAt) {
            clickedAt.clear();
            dialogs.clear();
        }
    }

    static void arm() {
        armed = true;
        PromptClicker current = instance;
        if (current != null) {
            current.kick();
        }
    }

    static void disarm() {
        armed = false;
    }

    static int clicks() {
        return clicks.get();
    }

    static String note() {
        String value = note;
        return value == null ? "" : value;
    }

    static boolean connected() {
        return instance != null;
    }

    static String diagnostics() {
        int found;
        synchronized (clickedAt) {
            found = dialogs.size();
        }
        return "Служба подключена: " + (connected() ? "ДА" : "НЕТ")
                + "\nПроверок окон: " + scans.get()
                + "; окон в последней проверке: " + windows.get()
                + "\nНайдено окон ADB с кнопкой: " + found
                + "\nПопыток «Разрешить»: " + attempts.get()
                + "; ACTION_CLICK принят: " + clicks.get()
                + (note.isEmpty() ? "" : "\n" + note)
                + "\nПринятое нажатие проверяется отдельно открытием shell.\n";
    }

    static boolean enabled(Context context) {
        String services = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (services == null || services.isEmpty()) {
            return false;
        }
        ComponentName component = new ComponentName(context, PromptClicker.class);
        String flat = component.flattenToString();
        String shortName = component.flattenToShortString();
        for (String part : services.split(":")) {
            if (part.equalsIgnoreCase(flat) || part.equalsIgnoreCase(shortName)) {
                return true;
            }
        }
        return false;
    }

    private void kick() {
        handler.removeCallbacks(scan);
        handler.post(scan);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        if (armed) {
            kick();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (armed) {
            kick();
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacks(scan);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        handler.removeCallbacks(scan);
        super.onDestroy();
    }

    private void scanOnce() {
        if (!armed || clicks.get() >= PromptDecision.MAX_CLICKS) {
            return;
        }
        SparseArray<List<AccessibilityWindowInfo>> byDisplay = getWindowsOnAllDisplays();
        List<AccessibilityWindowInfo> all = new ArrayList<>();
        for (int i = 0; i < byDisplay.size(); i++) {
            List<AccessibilityWindowInfo> list = byDisplay.valueAt(i);
            if (list != null) {
                all.addAll(list);
            }
        }
        scans.incrementAndGet();
        windows.set(all.size());
        try {
            for (AccessibilityWindowInfo window : all) {
                if (consider(window)) {
                    return;
                }
            }
        } finally {
            for (AccessibilityWindowInfo window : all) {
                window.recycle();
            }
        }
    }

    private boolean consider(AccessibilityWindowInfo window) {
        List<AccessibilityNodeInfo> owned = new ArrayList<>();
        try {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) {
                return false;
            }
            StringBuilder text = new StringBuilder();
            CharSequence title = window.getTitle();
            if (title != null) {
                text.append(title).append('\n');
            }
            List<PromptDecision.Node> nodes = new ArrayList<>();
            collect(root, owned, nodes, text);
            CharSequence packageName = root.getPackageName();
            PromptDecision decision = PromptDecision.decide(
                    packageName == null ? "" : packageName.toString(), text.toString(), nodes);
            if (decision.clickIndex < 0) {
                return false;
            }
            synchronized (clickedAt) {
                dialogs.add(window.getId());
            }
            if (recent(window.getId())) {
                return false;
            }
            if (!armed || clicks.get() >= PromptDecision.MAX_CLICKS) {
                return false;
            }
            if (decision.checkIndex >= 0) {
                if (!owned.get(decision.checkIndex).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    note = "галочка «Всегда разрешать» не нажалась";
                    return false;
                }
            }
            attempts.incrementAndGet();
            boolean clicked = owned.get(decision.clickIndex)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (!clicked) {
                note = "окно запроса нашлось, нажатие не принялось";
                return false;
            }
            remember(window.getId());
            int count = clicks.incrementAndGet();
            note = "нажато «Разрешить» (" + count + ")";
            return true;
        } finally {
            for (AccessibilityNodeInfo info : owned) {
                info.recycle();
            }
        }
    }

    private static void collect(
            AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> owned,
            List<PromptDecision.Node> nodes,
            StringBuilder text) {
        if (node == null) {
            return;
        }
        owned.add(node);
        CharSequence raw = node.getText() != null ? node.getText() : node.getContentDescription();
        String shown = raw == null ? "" : raw.toString();
        if (!shown.isEmpty()) {
            text.append(shown).append('\n');
        }
        nodes.add(new PromptDecision.Node(
                node.getViewIdResourceName(), shown, node.isCheckable(), node.isChecked()));
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), owned, nodes, text);
        }
    }

    private static boolean recent(int windowId) {
        synchronized (clickedAt) {
            Long at = clickedAt.get(windowId);
            return at != null && SystemClock.uptimeMillis() - at < CLICK_COOLDOWN_MS;
        }
    }

    private static void remember(int windowId) {
        synchronized (clickedAt) {
            clickedAt.put(windowId, SystemClock.uptimeMillis());
        }
    }
}
