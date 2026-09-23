package dev.denza.splitevents.probe;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shell-only seam that exercises the firmware's split signals from an ordinary app UID.
 *
 * <p>Three questions, each answered by the corpus and never tried from an app: can this UID
 * subscribe to the area push through {@code android.app.UnionActivityManager}, does it receive the
 * {@code homekey} close-system-dialogs broadcast, and can it flip the split gate with a raw
 * {@code activity_task} transaction 126. Every action answers on the ordered-broadcast result
 * channel with code 0 (it ran) or 1 (it threw) and one line of {@code key=value} tokens; judging
 * them against the system log is the host script's job.
 */
public final class ProbeReceiver extends BroadcastReceiver {

    static final String TAG = "SplitEventsProbe";

    private static final String PREFIX = "dev.denza.splitevents.probe.";
    static final String ACTION_ARM = PREFIX + "ARM";
    static final String ACTION_READ = PREFIX + "READ";
    static final String ACTION_GATE = PREFIX + "GATE";
    static final String ACTION_REPORT = PREFIX + "REPORT";

    private static final String UNION = "android.app.UnionActivityManager";
    private static final String UNION_LISTENER =
            "android.app.UnionActivityManager$ScreenAreaInfoForMultiListener";
    private static final int TX_GET_SCREEN_AREA = 30;
    private static final int TX_SET_START_TO_SPLIT = 126;

    /** One value per process, so the host can tell a survived process from a restarted one. */
    static final String NONCE = String.format(Locale.US, "%08x", new SecureRandom().nextInt());

    private static final Object LOCK = new Object();
    private static final List<String> EVENTS = new ArrayList<>();
    private static HandlerThread thread;
    private static Object areaListener;
    private static BroadcastReceiver homeReceiver;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = intent == null ? null : intent.getAction();
            StringBuilder line = new StringBuilder()
                    .append("op=").append(action == null ? "none" : action.substring(PREFIX.length()))
                    .append(" uid=").append(Process.myUid())
                    .append(" nonce=").append(NONCE);
            boolean ok;
            try {
                line.append(' ').append(run(context.getApplicationContext(), action, intent));
                ok = true;
            } catch (Throwable error) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                line.append(" error=").append(cause.getClass().getName())
                        .append(" detail=").append(String.valueOf(cause.getMessage()).replace(' ', '_'));
                ok = false;
            }
            String result = line.toString();
            Log.i(TAG, result);
            if (isOrderedBroadcast()) {
                setResultCode(ok ? 0 : 1);
                setResultData(result);
            }
        } catch (Throwable fatal) {
            Log.e(TAG, "probe failed to answer", fatal);
        }
    }

    private String run(Context context, String action, Intent intent) throws Exception {
        if (ACTION_ARM.equals(action)) return arm(context);
        if (ACTION_READ.equals(action)) return read(context);
        if (ACTION_GATE.equals(action)) return gate(intent.getIntExtra("value", -1));
        if (ACTION_REPORT.equals(action)) return report();
        throw new IllegalArgumentException("action=" + action);
    }

    // region arm

    private String arm(Context context) throws Exception {
        synchronized (LOCK) {
            if (thread != null) return "armed=already";
            thread = new HandlerThread("split-events-probe");
            thread.start();
            Handler handler = new Handler(thread.getLooper());

            homeReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    record("homekey", "reason=" + i.getStringExtra("reason"));
                }
            };
            context.registerReceiver(homeReceiver,
                    new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), null, handler,
                    Context.RECEIVER_EXPORTED);

            Class<?> listenerType = Class.forName(UNION_LISTENER);
            InvocationHandler invocation = (proxy, method, args) -> {
                if ("onScreenAreaInfoForMultiChanged".equals(method.getName())) {
                    record("area", "value=" + args[0]);
                    return null;
                }
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("toString".equals(method.getName())) return "SplitEventsProbeListener";
                return null;
            };
            areaListener = Proxy.newProxyInstance(
                    ProbeReceiver.class.getClassLoader(), new Class<?>[] {listenerType}, invocation);
            Object manager = union(context);
            long started = SystemClock.elapsedRealtimeNanos();
            Object registered = manager.getClass()
                    .getMethod("registerScreenAreaInfoForMultiListener", listenerType, Handler.class)
                    .invoke(manager, areaListener, handler);
            return "armed=true area_registered=" + registered
                    + " register_us=" + micros(started) + " home_receiver=true";
        }
    }

    private static void record(String kind, String detail) {
        String line = kind + " " + detail + " elapsed=" + SystemClock.elapsedRealtime()
                + " wall=" + System.currentTimeMillis();
        synchronized (LOCK) {
            EVENTS.add(line);
        }
        Log.i(TAG, "event " + line);
    }

    // endregion

    // region read

    private String read(Context context) throws Exception {
        Object manager = union(context);
        Class<?> type = manager.getClass();
        StringBuilder out = new StringBuilder();

        long started = SystemClock.elapsedRealtimeNanos();
        int area = (Integer) type.getMethod("getScreenAreaInfoForMulti").invoke(manager);
        out.append("area=").append(area).append(" area_us=").append(micros(started));

        started = SystemClock.elapsedRealtimeNanos();
        out.append(" raw30=").append(transactInt(TX_GET_SCREEN_AREA, null))
                .append(" raw30_us=").append(micros(started));

        for (int areaId : new int[] {1, 2, 4}) {
            int root = (Integer) type.getMethod("getRootTaskIdByAreaId", int.class)
                    .invoke(manager, areaId);
            started = SystemClock.elapsedRealtimeNanos();
            List<?> tasks = (List<?>) type.getMethod("getTasksOrderByAreaId", int.class)
                    .invoke(manager, areaId);
            out.append(" root").append(areaId).append('=').append(root)
                    .append(" panes").append(areaId).append("_us=").append(micros(started))
                    .append(" panes").append(areaId).append('=');
            if (tasks == null || tasks.isEmpty()) {
                out.append('-');
                continue;
            }
            for (int index = 0; index < tasks.size(); index++) {
                ActivityManager.RunningTaskInfo task = (ActivityManager.RunningTaskInfo) tasks.get(index);
                ComponentName top = task.topActivity;
                if (index > 0) out.append(',');
                out.append(task.taskId).append(':')
                        .append(top == null ? "null" : top.flattenToShortString());
            }
        }
        return out.toString();
    }

    // endregion

    // region gate

    private String gate(int value) throws Exception {
        if (value != 0 && value != 1) throw new IllegalArgumentException("value=" + value);
        long started = SystemClock.elapsedRealtimeNanos();
        transactVoid(TX_SET_START_TO_SPLIT, value);
        return "gate=" + value + " gate_us=" + micros(started);
    }

    // endregion

    private String report() {
        synchronized (LOCK) {
            StringBuilder out = new StringBuilder("events=").append(EVENTS.size());
            for (int index = 0; index < EVENTS.size(); index++) {
                out.append(" e").append(index).append('=').append(EVENTS.get(index).replace(' ', '|'));
            }
            return out.toString();
        }
    }

    private static Object union(Context context) throws Exception {
        return Class.forName(UNION).getMethod("getInstance", Context.class).invoke(null, context);
    }

    private static IBinder activityTask() throws Exception {
        IBinder service = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "activity_task");
        if (service == null) throw new IllegalStateException("no activity_task service");
        return service;
    }

    private static int transactInt(int code, Integer argument) throws Exception {
        IBinder service = activityTask();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            if (argument != null) data.writeInt(argument);
            service.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void transactVoid(int code, int argument) throws Exception {
        IBinder service = activityTask();
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(service.getInterfaceDescriptor());
            data.writeInt(argument);
            service.transact(code, data, reply, 0);
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static long micros(long startedNanos) {
        return (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1000L;
    }
}
