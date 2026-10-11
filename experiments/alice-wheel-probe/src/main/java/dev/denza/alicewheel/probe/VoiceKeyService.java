package dev.denza.alicewheel.probe;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.List;

/** Disposable ordinary-UID test, one Alice attempt per bounded arm window. */
public final class VoiceKeyService extends AccessibilityService {
    static VoiceKeyService instance;
    private static final String TAG = "AliceWheelProbe";
    private static final String PACKAGE = "ru.yandex.yandexnavi";
    private static final String VIEW = PACKAGE + ":id/navi_service_open_voice_search";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VoicePress press = new VoicePress();
    private final ArrayDeque<String> journal = new ArrayDeque<>();
    private long armedUntil;
    private long cachedAt;
    private int cachedWindow = -1;
    private int pressWindow = -1;
    private int pressGeneration;
    private int generation;
    private int clicks;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (SystemClock.uptimeMillis() >= armedUntil) {
                cachedWindow = -1; return;
            }
            int before = cachedWindow;
            cachedWindow = -1;
            try {
                AccessibilityNodeInfo node = findMic(-1);
                if (node != null) { cachedWindow = node.getWindowId(); node.recycle(); }
            } catch (RuntimeException error) { note("SCAN failed=" + error.getClass().getSimpleName()); }
            cachedAt = SystemClock.uptimeMillis();
            if (before != cachedWindow) note("READY window=" + cachedWindow);
            handler.postDelayed(this, 300);
        }
    };

    @Override protected void onServiceConnected() {
        instance = this;
        note("CONNECTED uid=" + android.os.Process.myUid());
    }

    void arm(int seconds) {
        generation++;
        armedUntil = SystemClock.uptimeMillis() + Math.max(1, Math.min(180, seconds)) * 1000L;
        handler.removeCallbacks(refresh);
        refresh.run();
        note("ARM " + status());
    }

    void disarm() {
        generation++;
        armedUntil = 0;
        cachedWindow = -1;
        handler.removeCallbacks(refresh);
        // Keep ownership of any accepted DOWN until its UP; disarming cancels only the click.
        note("DISARM");
    }

    String status() {
        return "remainingMs=" + Math.max(0, armedUntil - SystemClock.uptimeMillis())
                + " readyWindow=" + cachedWindow + " clicks=" + clicks;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (SystemClock.uptimeMillis() < armedUntil
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            cachedWindow = -1;
            handler.removeCallbacks(refresh);
            handler.post(refresh);
        }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != 320) return false;
        long start = SystemClock.uptimeMillis();
        boolean eligible = false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            eligible = start < armedUntil && cachedWindow >= 0 && start - cachedAt < 900
                    && focusedWindow() == cachedWindow;
            if (eligible) { pressWindow = cachedWindow; pressGeneration = generation; }
        }
        VoicePress.Result result = press.event(event.getAction(), event.getRepeatCount(),
                event.getDeviceId(), event.getDownTime(), eligible, event.isCanceled());
        if (result.click()) {
            int expectedWindow = pressWindow;
            int expectedGeneration = pressGeneration;
            // Return the filter result before doing any potentially slow cross-process click.
            handler.post(() -> click(expectedWindow, expectedGeneration));
        }
        note("KEY action=" + event.getAction() + " repeat=" + event.getRepeatCount()
                + " consumed=" + result.consumed() + " eligible=" + eligible
                + " device=" + event.getDeviceId()
                + " callbackMs=" + (SystemClock.uptimeMillis() - start));
        return result.consumed();
    }

    private void click(int expectedWindow, int expectedGeneration) {
        if (expectedGeneration != generation || SystemClock.uptimeMillis() >= armedUntil) {
            note("CLICK canceled=disarmed"); return;
        }
        // One attempt only. Another press needs an explicit shell arm command.
        armedUntil = 0;
        cachedWindow = -1;
        handler.removeCallbacks(refresh);
        AccessibilityNodeInfo target = findMic(expectedWindow);
        if (target == null) { note("CLICK canceled=foreground-or-control-changed"); return; }
        try {
            if (focusedWindow() != expectedWindow || !target.refresh()
                    || !target.isVisibleToUser() || !target.isEnabled()) {
                note("CLICK canceled=last-check"); return;
            }
            boolean accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (accepted) clicks++;
            note("CLICK accepted=" + accepted + " window=" + expectedWindow);
        } finally { target.recycle(); }
    }

    private int focusedWindow() {
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            for (AccessibilityWindowInfo w : windows) {
                if (w.getDisplayId() == 0 && w.isFocused()) {
                    return w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION ? w.getId() : -1;
                }
            }
            return -1;
        } finally { for (AccessibilityWindowInfo w : windows) w.recycle(); }
    }

    private AccessibilityNodeInfo findMic(int expectedWindow) {
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            for (AccessibilityWindowInfo w : windows) {
                if (w.getDisplayId() != 0 || !w.isFocused()
                        || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        || (expectedWindow >= 0 && w.getId() != expectedWindow)) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) return null;
                try {
                    if (!PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return null;
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(VIEW);
                    AccessibilityNodeInfo result = null;
                    int matches = 0;
                    for (AccessibilityNodeInfo node : nodes) {
                        if (node.isVisibleToUser() && node.isEnabled() && node.isClickable()
                                && PACKAGE.contentEquals(node.getPackageName() == null ? "" : node.getPackageName())) {
                            matches++;
                            if (result == null) result = AccessibilityNodeInfo.obtain(node);
                        }
                        node.recycle();
                    }
                    if (matches != 1) {
                        if (result != null) result.recycle();
                        return null;
                    }
                    return result;
                } finally { root.recycle(); }
            }
            return null;
        } finally { for (AccessibilityWindowInfo w : windows) w.recycle(); }
    }

    private void note(String text) {
        String line = SystemClock.uptimeMillis() + " " + text;
        synchronized (journal) {
            journal.addLast(line);
            while (journal.size() > 160) journal.removeFirst();
        }
        Log.w(TAG, line);
    }
    @Override protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.println(status());
        synchronized (journal) { for (String line : journal) writer.println(line); }
    }
    @Override public void onInterrupt() { disarm(); }
    @Override public void onDestroy() {
        disarm();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
