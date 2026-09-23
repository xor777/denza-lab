package dev.denza.avcstock.probe;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Shell-only seam that talks to the stock AVC's exported Messenger from an ordinary app UID.
 *
 * <p>Read from the OTA image (2026-09-23): {@code com.byd.avc/.AutoVideoService}, action
 * {@code com.byd.action.AVCSERVICE}, is exported with no permission and its handler checks no
 * caller. {@code what=35} replies with the current mode in {@code arg2} (5000 idle, 5095 PIP left,
 * 5096/5099 PIP right). {@code what=1011} replies {@code what=1012}: {@code arg1} 3 no PIP support,
 * 1 meter usable, 2 host only; {@code arg2} 0 PIP with left on the meter, 1 PIP on the head unit,
 * 2 full-screen, 3 off. {@code what=1013} with {@code arg1} 0..3 writes that choice and answers
 * like 1011.
 *
 * <p>Every action answers on the ordered-broadcast result channel with code 0 (the operation ran)
 * or 1 (it threw) and one line of {@code key=value} tokens; judging them is the host's job.
 */
public final class ProbeReceiver extends BroadcastReceiver {

    static final String TAG = "AvcStockProbe";

    private static final String PREFIX = "dev.denza.avcstock.probe.";
    private static final String ACTION_STATE = PREFIX + "STATE";
    private static final String ACTION_SET_LIGHT = PREFIX + "SET_LIGHT";

    private static final String AVC_PACKAGE = "com.byd.avc";
    private static final String AVC_ACTION = "com.byd.action.AVCSERVICE";

    private static final int WHAT_MODE = 35;
    private static final int WHAT_LIGHT_READ = 1011;
    private static final int WHAT_LIGHT_STATE = 1012;
    private static final int WHAT_LIGHT_WRITE = 1013;

    private static final long BIND_TIMEOUT_MS = 3000L;
    private static final long REPLY_TIMEOUT_MS = 2000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            String action = intent == null ? null : intent.getAction();
            StringBuilder line = new StringBuilder()
                    .append("op=").append(action == null ? "none" : action.substring(PREFIX.length()))
                    .append(" uid=").append(Process.myUid());
            boolean ok;
            try {
                line.append(' ').append(run(app, action, intent));
                ok = true;
            } catch (Throwable error) {
                line.append(" error=").append(error.getClass().getSimpleName())
                        .append(" detail=").append(String.valueOf(error.getMessage()).replace(' ', '_'));
                ok = false;
            }
            String result = line.toString();
            Log.i(TAG, result);
            pending.setResultCode(ok ? 0 : 1);
            pending.setResultData(result);
            pending.finish();
        }, "avc-stock-probe").start();
    }

    private String run(Context context, String action, Intent intent) throws Exception {
        if (ACTION_STATE.equals(action)) {
            return withService(context, (avc, replies) -> state(avc, replies));
        }
        if (ACTION_SET_LIGHT.equals(action)) {
            int value = intent.getIntExtra("value", -1);
            if (value < 0 || value > 3) throw new IllegalArgumentException("value_0_to_3");
            return withService(context, (avc, replies) -> {
                String before = state(avc, replies);
                long started = SystemClock.elapsedRealtimeNanos();
                Message written = ask(avc, replies, WHAT_LIGHT_WRITE, value);
                long elapsed = SystemClock.elapsedRealtimeNanos() - started;
                String after = state(avc, replies);
                return "before[" + before + "] wrote=" + value
                        + " write_reply=" + written.what + ":" + written.arg1 + ":" + written.arg2
                        + " write_ms=" + millis(elapsed) + " after[" + after + "]";
            });
        }
        throw new IllegalArgumentException("action=" + action);
    }

    private String state(Messenger avc, BlockingQueue<Message> replies) throws Exception {
        long started = SystemClock.elapsedRealtimeNanos();
        Message mode = ask(avc, replies, WHAT_MODE, 0);
        long modeNanos = SystemClock.elapsedRealtimeNanos() - started;
        started = SystemClock.elapsedRealtimeNanos();
        Message light = ask(avc, replies, WHAT_LIGHT_READ, 0);
        long lightNanos = SystemClock.elapsedRealtimeNanos() - started;
        if (light.what != WHAT_LIGHT_STATE) throw new IllegalStateException("light_reply_what=" + light.what);
        return "mode=" + mode.arg2 + " mode_ms=" + millis(modeNanos)
                + " light_support=" + light.arg1 + " light_choice=" + light.arg2
                + " light_ms=" + millis(lightNanos);
    }

    private Message ask(Messenger avc, BlockingQueue<Message> replies, int what, int arg1)
            throws RemoteException, InterruptedException {
        replies.clear();
        Message request = Message.obtain(null, what, arg1, 0);
        request.replyTo = replyMessenger;
        avc.send(request);
        Message reply = replies.poll(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (reply == null) throw new IllegalStateException("no_reply_what=" + what);
        return reply;
    }

    private interface Session {
        String run(Messenger avc, BlockingQueue<Message> replies) throws Exception;
    }

    private Messenger replyMessenger;

    private String withService(Context context, Session session) throws Exception {
        HandlerThread thread = new HandlerThread("avc-stock-replies");
        thread.start();
        BlockingQueue<Message> replies = new ArrayBlockingQueue<>(8);
        replyMessenger = new Messenger(new Handler(thread.getLooper()) {
            @Override
            public void handleMessage(Message message) {
                replies.offer(Message.obtain(message));
            }
        });
        BlockingQueue<IBinder> bound = new ArrayBlockingQueue<>(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                bound.offer(service);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        long started = SystemClock.elapsedRealtimeNanos();
        Intent intent = new Intent(AVC_ACTION).setPackage(AVC_PACKAGE);
        // Flags 0: attach to the running service only. Creating it would run AVC's
        // AbsAndroidService.onCreate, whose context state 6002 hides a showing meter PIP.
        boolean requested = context.bindService(intent, connection, 0);
        try {
            if (!requested) throw new IllegalStateException("bind_refused");
            IBinder binder = bound.poll(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (binder == null) throw new IllegalStateException("bind_timeout");
            long bindNanos = SystemClock.elapsedRealtimeNanos() - started;
            return "bind_ms=" + millis(bindNanos) + " " + session.run(new Messenger(binder), replies);
        } finally {
            if (requested) context.unbindService(connection);
            thread.quitSafely();
        }
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.US, "%.2f", nanos / 1_000_000.0);
    }
}
