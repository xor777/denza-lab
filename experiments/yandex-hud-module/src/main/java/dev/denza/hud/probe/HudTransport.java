package dev.denza.hud.probe;

import android.content.*;
import android.os.*;
import android.util.Log;

import dev.denza.hudlink.HudSomeIp;

/** Worker-thread-owned Binder session. Latest frames expire; reconnect never replays an old map. */
final class HudTransport {
    private static final String TAG = "DenzaYandexHud";
    private final Context context;
    private final Handler worker;
    private final byte[] blank, inactiveRoad = HudWire.road(false);
    /** The newest hint packet and the one the HUD last got; a changed hint goes out at once. */
    private byte[] road = HudWire.road(true), sentRoad;
    private IBinder binder;
    private ServiceConnection connection;
    private boolean started, desired;
    private long bindingSince, lastFrame, retryAt, lastRoad;
    private int generation, clearRemaining;
    long sent, failures;
    private final Runnable maintenance = this::maintain;

    HudTransport(Context context, Handler worker, byte[] blankPng) {
        this.context = context;
        this.worker = worker;
        blank = HudWire.map(blankPng);
    }

    void publish(byte[] png, byte[] hint) {
        if (!desired) lastRoad = 0;
        desired = true;
        clearRemaining = 0;
        road = hint;
        lastFrame = SystemClock.elapsedRealtime();
        worker.removeCallbacks(maintenance);
        ensureConnected();
        if (started) {
            // Heartbeat every 500 ms as before; a changed hint (metres, a new manoeuvre, the limit)
            // goes out at once but not more than four times a second.
            long since = lastFrame - lastRoad;
            if (since >= 500 || (since >= 250 && !java.util.Arrays.equals(road, sentRoad))) {
                if (!fire(HudWire.ROAD, road)) { recover(); return; }
                lastRoad = lastFrame;
                sentRoad = road;
            }
            if (fire(HudWire.MAP, HudWire.map(png))) sent++;
            else recover();
        }
        worker.postDelayed(maintenance, 500);
    }

    void clear() {
        if (!desired && clearRemaining > 0) return;
        desired = false;
        worker.removeCallbacks(maintenance);
        clearRemaining = started ? 3 : 0;
        sendClear();
    }

    private void sendClear() {
        if (desired) return;
        if (started && clearRemaining > 0) {
            fire(HudWire.MAP, blank);
            fire(HudWire.ROAD, inactiveRoad);
            clearRemaining--;
            worker.postDelayed(maintenance, clearRemaining > 0 ? 120 : 110);
        } else disconnect(true);
    }

    private void maintain() {
        if (!desired) { sendClear(); return; }
        if (SystemClock.elapsedRealtime() - lastFrame >= 1500) {
            Log.w(TAG, "frame source expired; clearing HUD");
            clear();
            return;
        }
        ensureConnected();
        worker.postDelayed(maintenance, 500);
    }

    private void ensureConnected() {
        long now = SystemClock.elapsedRealtime();
        if (!desired || now < retryAt) return;
        if (connection != null) {
            if (!started && now - bindingSince >= 3000) recover();
            return;
        }
        final int token = ++generation;
        ServiceConnection next = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                worker.post(() -> {
                    if (token != generation || !desired) return;
                    binder = service;
                    started = counted(HudSomeIp.start(binder, TAG)) == 0;
                    if (started) {
                        Log.i(TAG, "transport started");
                        if (!fire(HudWire.ROAD, road)) recover();
                        lastRoad = SystemClock.elapsedRealtime();
                        sentRoad = road;
                    } else recover();
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) { worker.post(() -> { if (token == generation) recover(); }); }
            @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
            @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
        };
        connection = next;
        bindingSince = now;
        Intent intent = HudSomeIp.bindIntent(context.getPackageName());
        try {
            if (!context.bindService(intent, next, Context.BIND_AUTO_CREATE)) recover();
        } catch (RuntimeException error) { Log.w(TAG, "HUD bind failed", error); recover(); }
    }

    private boolean fire(long topic, byte[] payload) { return counted(HudSomeIp.fire(binder, topic, payload, TAG)) == 0; }

    /** A refused or failed call counts; no binder yet and an unhandled code do not, as before :hud-link. */
    private int counted(int result) {
        if (result != 0 && result != HudSomeIp.NO_BINDER && result != HudSomeIp.NOT_HANDLED) failures++;
        return result;
    }

    private void recover() {
        disconnect(false);
        retryAt = SystemClock.elapsedRealtime() + 1000;
        worker.removeCallbacks(maintenance);
        if (desired) worker.postDelayed(maintenance, 1000);
    }

    private void disconnect(boolean stop) {
        ++generation;
        if (stop && started) counted(HudSomeIp.stop(binder, TAG));
        started = false;
        binder = null;
        ServiceConnection previous = connection;
        connection = null;
        if (previous != null) try { context.unbindService(previous); } catch (RuntimeException ignored) { }
    }
}
