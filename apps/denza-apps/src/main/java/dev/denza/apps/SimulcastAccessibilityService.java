package dev.denza.apps;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import dev.denza.apps.platform.accessibility.AccessibilityHost;
import dev.denza.apps.platform.accessibility.RiderDispatch;
import dev.denza.apps.platform.accessibility.RiderHost;

/**
 * The app's one accessibility service, and only a host: it hands its connect, every event, every
 * key and its going to the features that ride on it ({@link DenzaAccessibilityRiders}, in that
 * list's order, through {@link RiderDispatch}) and does nothing else.
 *
 * <p>Its name is not the projection's any more than the rest of it, but it stays: the car records
 * {@code dev.denza.apps/dev.denza.apps.SimulcastAccessibilityService} in
 * {@code enabled_accessibility_services}, and a new name would leave every rider unbound until a
 * repair. The rest of the process reaches it through {@link AccessibilityHost}.
 */
public class SimulcastAccessibilityService extends AccessibilityService implements RiderHost {
    private static final String TAG = "DenzaSimulcastA11y";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RiderDispatch<AccessibilityService, AccessibilityEvent> riders =
            new RiderDispatch<>(
                    DenzaAccessibilityRiders.INSTANCE.create(),
                    (rider, call, error) -> Log.e(TAG, "rider " + rider + " failed on " + call, error));

    @Override
    protected void onServiceConnected() {
        AccessibilityHost.INSTANCE.bind(this);
        Log.i(TAG, "service connected");
        riders.connected(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        riders.event(event, event.getEventType(), event.getPackageName());
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        return riders.key(event.getKeyCode(), event.getAction(), event.getRepeatCount());
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        goAway();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        goAway();
        super.onDestroy();
    }

    /** Unbind and destroy both end the service, and the riders hear both, as they always did. */
    private void goAway() {
        AccessibilityHost.INSTANCE.unbind(this);
        riders.disconnected(this);
    }

    @Override
    public <R> R rider(Class<R> type) {
        return riders.rider(type);
    }

    @Override
    public void post(Runnable call) {
        handler.post(call);
    }
}
