package dev.denza.mirrors.probe;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

public class ProjectionCommandReceiver extends BroadcastReceiver {
    private static final String TAG = "DenzaProjectionProbe";
    private static final String SERVICE_DESCRIPTOR =
            "com.byd.cluster.projectionmanager.service.IContentProjectionManager";
    private static final ComponentName PROJECTION_SERVICE = new ComponentName(
            "com.example.amapservice",
            "com.byd.cluster.projectionmanager.service.BydProjectionService"
    );

    private static final int TRANSACTION_START = 3;
    private static final int TRANSACTION_STOP = 4;

    private static final int CLUSTER_FULL = 0;
    private static final int CLUSTER_LEFT = 1;

    private static final int MAP_VIEW = 8;
    private static final int MINI_MAP_CARD = 9;
    private static final int TBT_CARD = 15;

    @Override
    public void onReceive(Context context, Intent intent) {
        String command = intent == null ? null : intent.getStringExtra("command");
        if (command == null) {
            command = "bind";
        }
        new CommandRun(context.getApplicationContext(), command, goAsync()).start();
    }

    private static final class CommandRun {
        private final Context context;
        private final String command;
        private final PendingResult pendingResult;
        private final Handler handler = new Handler(Looper.getMainLooper());

        private IBinder projectionBinder;
        private boolean bound;
        private boolean finished;

        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                projectionBinder = service;
                Log.i(TAG, "receiver connected " + name.flattenToShortString());
                runCommand();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                projectionBinder = null;
                bound = false;
                Log.i(TAG, "receiver disconnected " + name.flattenToShortString());
            }
        };

        CommandRun(Context context, String command, PendingResult pendingResult) {
            this.context = context;
            this.command = command;
            this.pendingResult = pendingResult;
        }

        void start() {
            Log.i(TAG, "receiver command=" + command);
            Intent bindIntent = new Intent();
            bindIntent.setComponent(PROJECTION_SERVICE);
            try {
                bound = context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException e) {
                Log.i(TAG, "receiver bind failed", e);
                finish();
                return;
            }
            Log.i(TAG, "receiver bind result=" + bound);
            if (!bound) {
                finish();
                return;
            }
            handler.postDelayed(this::finish, 5000);
        }

        private void runCommand() {
            if ("start_full".equals(command)) {
                logResult("start_full", transactProjection(TRANSACTION_START, CLUSTER_FULL, MAP_VIEW));
            } else if ("start_mini".equals(command)) {
                logResult("start_mini", transactProjection(TRANSACTION_START, CLUSTER_LEFT, MINI_MAP_CARD));
            } else if ("start_tbt".equals(command)) {
                logResult("start_tbt", transactProjection(TRANSACTION_START, CLUSTER_LEFT, TBT_CARD));
            } else if ("stop_all".equals(command)) {
                logResult("stop_full", transactProjection(TRANSACTION_STOP, CLUSTER_FULL, MAP_VIEW));
                logResult("stop_mini", transactProjection(TRANSACTION_STOP, CLUSTER_LEFT, MINI_MAP_CARD));
                logResult("stop_tbt", transactProjection(TRANSACTION_STOP, CLUSTER_LEFT, TBT_CARD));
            } else {
                Log.i(TAG, "receiver no projection command=" + command);
            }
            finish();
        }

        private void logResult(String command, int result) {
            Log.i(TAG, "receiver " + command + " result=" + result);
        }

        private int transactProjection(int code, int position, int type) {
            if (projectionBinder == null) {
                Log.i(TAG, "receiver not bound");
                return Integer.MIN_VALUE;
            }
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR);
                data.writeInt(position);
                data.writeInt(type);
                boolean ok = projectionBinder.transact(code, data, reply, 0);
                if (!ok) {
                    return Integer.MIN_VALUE + 1;
                }
                reply.readException();
                return reply.readInt();
            } catch (RemoteException | RuntimeException e) {
                Log.i(TAG, "receiver binder error", e);
                return Integer.MIN_VALUE + 2;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            handler.removeCallbacksAndMessages(null);
            if (bound) {
                try {
                    context.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // The binding may already be gone if the remote service died.
                }
                bound = false;
            }
            projectionBinder = null;
            pendingResult.finish();
        }
    }
}
