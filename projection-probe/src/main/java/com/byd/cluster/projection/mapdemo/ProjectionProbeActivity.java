package com.byd.cluster.projection.mapdemo;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ProjectionProbeActivity extends Activity {
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

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private TextView logView;
    private ScrollView logScrollView;
    private IBinder projectionBinder;
    private String pendingCommand;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            projectionBinder = service;
            appendLog("connected " + name.flattenToShortString());
            runPendingCommand();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            projectionBinder = null;
            appendLog("disconnected " + name.flattenToShortString());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        requestNotificationPermission();
        appendLog("package=" + getPackageName());
        setPendingCommand(getIntent());
        if (pendingCommand == null) {
            appendLog("ready");
        } else if (isMonitorCommand(pendingCommand)) {
            runPendingCommand();
        } else {
            bindProjectionService();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        setPendingCommand(intent);
        runPendingCommand();
    }

    @Override
    protected void onDestroy() {
        if (projectionBinder != null) {
            unbindService(connection);
            projectionBinder = null;
        }
        super.onDestroy();
    }

    private View createContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 24);
        root.setBackgroundColor(Color.rgb(5, 7, 10));

        TextView title = new TextView(this);
        title.setText("Dash Projection");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.START);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 20, 0, 20);
        root.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        row.addView(button("Start monitor", v -> {
            SideCameraOverlayMonitorService.start(this);
            appendLog("monitor start requested");
        }));
        row.addView(button("Stop monitor", v -> {
            SideCameraOverlayMonitorService.stop(this);
            appendLog("monitor stop requested");
        }));
        row.addView(button("Test ADB", v -> testLocalAdb()));

        logView = new TextView(this);
        logView.setTextColor(Color.rgb(200, 230, 255));
        logView.setTextSize(14);
        logView.setLineSpacing(0, 1.08f);

        logScrollView = new ScrollView(this);
        logScrollView.addView(logView);
        root.addView(logScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        return root;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        params.setMargins(0, 0, 12, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void bindProjectionService() {
        Intent intent = new Intent();
        intent.setComponent(PROJECTION_SERVICE);
        boolean bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        appendLog("bind result=" + bound);
    }

    private void setPendingCommand(Intent intent) {
        if (intent != null) {
            pendingCommand = intent.getStringExtra("command");
        }
    }

    private static boolean isMonitorCommand(String command) {
        return "start_monitor".equals(command) || "stop_monitor".equals(command);
    }

    private void startProjection(int position, int type) {
        int result = transactProjection(TRANSACTION_START, position, type);
        appendLog("start position=" + position + " type=" + type + " result=" + result);
    }

    private void stopAll() {
        appendLog("stop full map result=" + transactProjection(TRANSACTION_STOP, CLUSTER_FULL, MAP_VIEW));
        appendLog("stop mini result=" + transactProjection(TRANSACTION_STOP, CLUSTER_LEFT, MINI_MAP_CARD));
        appendLog("stop tbt result=" + transactProjection(TRANSACTION_STOP, CLUSTER_LEFT, TBT_CARD));
    }

    private void runPendingCommand() {
        if (pendingCommand == null) {
            return;
        }
        String command = pendingCommand;
        pendingCommand = null;
        if ("start_full".equals(command)) {
            startProjection(CLUSTER_FULL, MAP_VIEW);
        } else if ("start_mini".equals(command)) {
            startProjection(CLUSTER_LEFT, MINI_MAP_CARD);
        } else if ("start_tbt".equals(command)) {
            startProjection(CLUSTER_LEFT, TBT_CARD);
        } else if ("stop_all".equals(command)) {
            stopAll();
        } else if ("start_monitor".equals(command)) {
            SideCameraOverlayMonitorService.start(this);
            appendLog("monitor start requested");
        } else if ("stop_monitor".equals(command)) {
            SideCameraOverlayMonitorService.stop(this);
            appendLog("monitor stop requested");
        } else {
            appendLog("command=" + command);
        }
    }

    private int transactProjection(int code, int position, int type) {
        if (projectionBinder == null) {
            appendLog("not bound");
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
            appendLog("binder error: " + e);
            return Integer.MIN_VALUE + 2;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void appendLog(String message) {
        String line = timeFormat.format(new Date()) + " " + message;
        Log.i(TAG, line);
        if (logView != null) {
            logView.append(line + "\n");
            if (logScrollView != null) {
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private void testLocalAdb() {
        appendLog("adb test started");
        new Thread(() -> {
            try {
                String output = new LocalAdbClient(this).shell("echo dash-projection-ok");
                runOnUiThread(() -> appendLog("adb test: " + output.trim()));
            } catch (Exception e) {
                runOnUiThread(() -> appendLog("adb test failed: " + e.getClass().getSimpleName()
                        + " " + e.getMessage()));
            }
        }, "dash-projection-adb-test").start();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7);
        }
    }
}
