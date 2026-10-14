package dev.denza.hud.probe;

import android.content.*;
import android.os.*;
import android.util.Log;

/** Worker-thread-owned Binder session. Latest frames expire; reconnect never replays an old map. */
final class HudTransport {
    private static final String TAG = "DenzaYandexHud";
    private final Context context;
    private final Handler worker;
    private final byte[] blank, activeRoad = HudWire.road(true), inactiveRoad = HudWire.road(false);
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

    void publish(byte[] png) {
        if (!desired) lastRoad = 0;
        desired = true;
        clearRemaining = 0;
        lastFrame = SystemClock.elapsedRealtime();
        worker.removeCallbacks(maintenance);
        ensureConnected();
        if (started) {
            if (lastFrame - lastRoad >= 500) {
                if (!fire(HudWire.ROAD, activeRoad)) { recover(); return; }
                lastRoad = lastFrame;
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
                    started = transact(4, HudWire.SERVICE, null) == 0;
                    if (started) {
                        Log.i(TAG, "transport started");
                        if (!fire(HudWire.ROAD, activeRoad)) recover();
                        lastRoad = SystemClock.elapsedRealtime();
                    } else recover();
                });
            }
            @Override public void onServiceDisconnected(ComponentName name) { worker.post(() -> { if (token == generation) recover(); }); }
            @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
            @Override public void onNullBinding(ComponentName name) { onServiceDisconnected(name); }
        };
        connection = next;
        bindingSince = now;
        Intent intent = new Intent("com.ts.car.someip.SomeIpServerService")
                .setComponent(new ComponentName("com.ts.car.someip.service", "com.ts.car.someip.service.manager.SomeIpServerService"))
                .setType(context.getPackageName());
        try {
            if (!context.bindService(intent, next, Context.BIND_AUTO_CREATE)) recover();
        } catch (RuntimeException error) { Log.w(TAG, "HUD bind failed", error); recover(); }
    }

    private boolean fire(long topic, byte[] payload) { return transact(6, topic, payload) == 0; }

    private int transact(int code, long id, byte[] payload) {
        if (binder == null) return -100;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(HudWire.DESCRIPTOR);
            if (code == 6) {
                data.writeInt(1); data.writeLong(id); data.writeLong(0);
                data.writeInt(payload.length); data.writeByteArray(payload);
            } else data.writeLong(id);
            if (!binder.transact(code, data, reply, 0)) return -201;
            reply.readException();
            int result = reply.readInt();
            if (result != 0) failures++;
            return result;
        } catch (RuntimeException | RemoteException error) { failures++; Log.w(TAG, "HUD transaction failed", error); return -200; }
        finally { data.recycle(); reply.recycle(); }
    }

    private void recover() {
        disconnect(false);
        retryAt = SystemClock.elapsedRealtime() + 1000;
        worker.removeCallbacks(maintenance);
        if (desired) worker.postDelayed(maintenance, 1000);
    }

    private void disconnect(boolean stop) {
        ++generation;
        if (stop && started) transact(5, HudWire.SERVICE, null);
        started = false;
        binder = null;
        ServiceConnection previous = connection;
        connection = null;
        if (previous != null) try { context.unbindService(previous); } catch (RuntimeException ignored) { }
    }
}
