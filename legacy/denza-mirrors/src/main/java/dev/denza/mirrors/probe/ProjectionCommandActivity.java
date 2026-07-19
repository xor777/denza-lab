package dev.denza.mirrors.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

public class ProjectionCommandActivity extends Activity {
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String pendingCommand;
    private IBinder projectionBinder;
    private boolean bound;
    private boolean finishingCommand;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            projectionBinder = service;
            Log.i(TAG, "command activity connected " + name.flattenToShortString());
            runPendingCommand();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            projectionBinder = null;
            bound = false;
            Log.i(TAG, "command activity disconnected " + name.flattenToShortString());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        setCommand(getIntent());
        bindProjectionService();
        handler.postDelayed(this::finishCommand, 5000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        setCommand(intent);
        if (projectionBinder != null) {
            runPendingCommand();
        }
    }

    @Override
    protected void onDestroy() {
        cleanup();
        super.onDestroy();
    }

    private void setCommand(Intent intent) {
        pendingCommand = intent == null ? null : intent.getStringExtra("command");
        if (pendingCommand == null) {
            pendingCommand = "bind";
        }
        Log.i(TAG, "command activity command=" + pendingCommand);
    }

    private void bindProjectionService() {
        Intent intent = new Intent();
        intent.setComponent(PROJECTION_SERVICE);
        try {
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            Log.i(TAG, "command activity bind failed", e);
            finishCommand();
            return;
        }
        Log.i(TAG, "command activity bind result=" + bound);
        if (!bound) {
            finishCommand();
        }
    }

    private void runPendingCommand() {
        String command = pendingCommand;
        pendingCommand = null;
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
            Log.i(TAG, "command activity no projection command=" + command);
        }
        finishCommand();
    }

    private void logResult(String command, int result) {
        Log.i(TAG, "command activity " + command + " result=" + result);
    }

    private int transactProjection(int code, int position, int type) {
        if (projectionBinder == null) {
            Log.i(TAG, "command activity not bound");
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
            Log.i(TAG, "command activity binder error", e);
            return Integer.MIN_VALUE + 2;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void finishCommand() {
        if (finishingCommand) {
            return;
        }
        finishingCommand = true;
        cleanup();
        finishAndRemoveTask();
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            try {
                unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // The binding may already be gone if the remote service died.
            }
            bound = false;
        }
        projectionBinder = null;
    }
}
