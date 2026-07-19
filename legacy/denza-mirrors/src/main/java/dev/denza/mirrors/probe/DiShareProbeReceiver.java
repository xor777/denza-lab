package dev.denza.mirrors.probe;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class DiShareProbeReceiver extends BroadcastReceiver {
    public static final String ACTION =
            "dev.denza.mirrors.DISHARE_COMMAND";

    private static final String TAG = "DenzaDiShareProbe";
    private static final String CONTROL_DESCRIPTOR =
            "com.byd.dishare.control.IDiShareControl";
    private static final String LISTENER_DESCRIPTOR =
            "com.byd.dishare.IDiShareListener";
    private static final String CONTROL_ACTION =
            "com.byd.dishare.control.DiShareControlService";
    private static final String DISHARE_PACKAGE = "com.byd.dishare";

    private static final String DEFAULT_PACKAGE = "com.byd.dishare";
    private static final String DEFAULT_PROVIDER = "screen_ivi";
    private static final String DEFAULT_RECEIVER = "screen_hud";
    private static final String DEFAULT_APP = "com.bilibili.bilithings";

    private static final int TX_REGISTER = 0x2;
    private static final int TX_UNREGISTER = 0x3;
    private static final int TX_GET_SCREENS = 0x4;
    private static final int TX_GET_STATE = 0x5;
    private static final int TX_START = 0x6;
    private static final int TX_STOP = 0x7;
    private static final int TX_CLOSE_DISHARE = 0xb;
    private static final int TX_CAN_START = 0x12;

    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pendingResult = goAsync();
        runProbe(context.getApplicationContext(), intent, pendingResult::finish);
    }

    static void runProbe(Context context, Intent intent, Runnable finishCallback) {
        String command = stringExtra(intent, "command", "probe");
        new Run(context.getApplicationContext(), intent, command, finishCallback).start();
    }

    private static String stringExtra(Intent intent, String name, String fallback) {
        String value = intent == null ? null : intent.getStringExtra(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        return value.trim();
    }

    private static final class Run {
        private final Context context;
        private final Intent intent;
        private final String command;
        private final Runnable finishCallback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final DiShareListenerBinder listener;
        private final File statusFile;

        private IBinder controlBinder;
        private boolean bound;
        private boolean finished;

        private final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                controlBinder = service;
                log("connected " + name.flattenToShortString());
                runCommand();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                controlBinder = null;
                bound = false;
                log("disconnected " + name.flattenToShortString());
            }
        };

        Run(Context context, Intent intent, String command, Runnable finishCallback) {
            this.context = context;
            this.intent = intent;
            this.command = command;
            this.finishCallback = finishCallback;
            this.statusFile = new File(context.getFilesDir(), "dishare_probe_status.txt");
            this.listener = new DiShareListenerBinder(this::log);
        }

        void start() {
            resetStatus();
            log("command=" + command);
            Intent bindIntent = new Intent();
            bindIntent.setAction(CONTROL_ACTION);
            bindIntent.setPackage(DISHARE_PACKAGE);
            try {
                bound = context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException e) {
                log("bind failed: " + shortError(e));
                finish();
                return;
            }
            log("bind result=" + bound);
            if (!bound) {
                finish();
                return;
            }
            handler.postDelayed(this::finish, 12000);
        }

        private void runCommand() {
            String packageName = stringExtra(intent, "package", DEFAULT_PACKAGE);
            String provider = stringExtra(intent, "provider", DEFAULT_PROVIDER);
            String receiver = stringExtra(intent, "receiver", DEFAULT_RECEIVER);
            String appName = stringExtra(intent, "app", DEFAULT_APP);
            String sessionId = stringExtra(intent, "session_id", "");

            try {
                if ("unregister".equals(command)) {
                    unregister(packageName);
                    finish();
                    return;
                }

                register(packageName);

                if ("start_hud".equals(command)) {
                    logScreens(packageName);
                    log("canStart " + provider + "=" + canStart(provider, packageName));
                    Bundle result = startShare(provider, receiver, appName, packageName);
                    log("start_hud result=" + bundleToString(result));
                    handler.postDelayed(() -> {
                        try {
                            log("state_after_start=" + readState(packageName));
                        } catch (RuntimeException e) {
                            log("state_after_start failed: " + shortError(e));
                        }
                        finish();
                    }, 1500);
                    return;
                }

                if ("stop".equals(command)) {
                    DiShareState state = readState(packageName);
                    String targetSession = sessionId.isEmpty() && state != null
                            ? state.sessionId : sessionId;
                    if (targetSession == null || targetSession.isEmpty()) {
                        log("stop skipped: no session, state=" + state);
                    } else {
                        log("stop session=" + targetSession
                                + " result=" + bundleToString(stopShare(targetSession, packageName)));
                    }
                    log("state_after_stop=" + readState(packageName));
                    finish();
                    return;
                }

                if ("close".equals(command)) {
                    log("close " + receiver
                            + " result=" + bundleToString(closeDiShare(receiver, packageName)));
                    finish();
                    return;
                }

                logScreens(packageName);
                log("state=" + readState(packageName));
                log("canStart " + provider + "=" + canStart(provider, packageName));
                finish();
            } catch (RuntimeException e) {
                log("command failed: " + shortError(e));
                Log.i(TAG, "command failed", e);
                finish();
            }
        }

        private void register(String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeStrongBinder(listener);
                data.writeString(packageName);
                transact(TX_REGISTER, data, reply);
                reply.readException();
                log("registered package=" + packageName);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void unregister(String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeStrongBinder(listener);
                data.writeString(packageName);
                transact(TX_UNREGISTER, data, reply);
                reply.readException();
                log("unregistered package=" + packageName);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void logScreens(String packageName) {
            List<DiShareScreen> screens = getScreens(packageName);
            log("screens " + screens.size() + "=" + screens);
        }

        private List<DiShareScreen> getScreens(String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(packageName);
                transact(TX_GET_SCREENS, data, reply);
                reply.readException();
                return readScreens(reply);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private DiShareState readState(String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(packageName);
                transact(TX_GET_STATE, data, reply);
                reply.readException();
                if (reply.readInt() == 0) {
                    return null;
                }
                return DiShareState.read(reply);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private boolean canStart(String provider, String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(provider);
                data.writeString(packageName);
                transact(TX_CAN_START, data, reply);
                reply.readException();
                return reply.readInt() != 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private Bundle startShare(String provider, String receiver, String appName,
                String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(provider);
                data.writeStringList(Collections.singletonList(receiver));
                data.writeString(appName);
                data.writeString(packageName);
                transact(TX_START, data, reply);
                return readControlResult(reply);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private Bundle stopShare(String sessionId, String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(sessionId);
                data.writeString(packageName);
                transact(TX_STOP, data, reply);
                return readControlResult(reply);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private Bundle closeDiShare(String screenId, String packageName) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(screenId);
                data.writeString(packageName);
                transact(TX_CLOSE_DISHARE, data, reply);
                return readControlResult(reply);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void transact(int code, Parcel data, Parcel reply) {
            if (controlBinder == null) {
                throw new IllegalStateException("not bound");
            }
            try {
                boolean ok = controlBinder.transact(code, data, reply, 0);
                if (!ok) {
                    throw new IllegalStateException("transact " + code + " returned false");
                }
            } catch (RemoteException e) {
                throw new IllegalStateException("transact " + code + " failed", e);
            }
        }

        private Bundle readControlResult(Parcel reply) {
            reply.readException();
            if (reply.readInt() == 0) {
                return null;
            }
            return reply.readBundle(DiShareProbeReceiver.class.getClassLoader());
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
                    // Binding can already be gone if the remote service died.
                }
                bound = false;
            }
            controlBinder = null;
            log("finished");
            finishCallback.run();
        }

        private void resetStatus() {
            try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
                output.write(("command=" + command + '\n').getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.i(TAG, "status reset failed: " + shortError(e));
            }
        }

        private void log(String message) {
            Log.i(TAG, message);
            String line = System.currentTimeMillis() + " " + message + '\n';
            try (FileOutputStream output = new FileOutputStream(statusFile, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.i(TAG, "status write failed: " + shortError(e));
            }
        }
    }

    private static final class DiShareListenerBinder extends Binder {
        private final ProbeLogger logger;

        DiShareListenerBinder(ProbeLogger logger) {
            this.logger = logger;
            attachInterface(null, LISTENER_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(LISTENER_DESCRIPTOR);
                }
                return true;
            }
            if (code == 2) {
                data.enforceInterface(LISTENER_DESCRIPTOR);
                logger.log("listener screens=" + readScreens(data));
                return true;
            }
            if (code == 3) {
                data.enforceInterface(LISTENER_DESCRIPTOR);
                DiShareState state = data.readInt() == 0 ? null : DiShareState.read(data);
                logger.log("listener state=" + state);
                return true;
            }
            if (code == 4) {
                data.enforceInterface(LISTENER_DESCRIPTOR);
                logger.log("listener foreground=" + (data.readInt() != 0));
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private interface ProbeLogger {
        void log(String message);
    }

    private static List<DiShareScreen> readScreens(Parcel parcel) {
        int size = parcel.readInt();
        if (size < 0) {
            return Collections.emptyList();
        }
        List<DiShareScreen> screens = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            if (parcel.readInt() == 0) {
                screens.add(null);
            } else {
                screens.add(DiShareScreen.read(parcel));
            }
        }
        return screens;
    }

    private static String bundleToString(Bundle bundle) {
        if (bundle == null) {
            return "null";
        }
        Set<String> keys = new TreeSet<>(bundle.keySet());
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                builder.append(", ");
            }
            first = false;
            builder.append(key).append('=').append(bundle.get(key));
        }
        return builder.append('}').toString();
    }

    private static String shortError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + " " + message;
    }

    private static final class DiShareScreen {
        final String deviceId;
        final String screenId;
        final boolean available;

        private DiShareScreen(String deviceId, String screenId, boolean available) {
            this.deviceId = deviceId;
            this.screenId = screenId;
            this.available = available;
        }

        static DiShareScreen read(Parcel parcel) {
            return new DiShareScreen(parcel.readString(), parcel.readString(),
                    parcel.readByte() != 0);
        }

        @Override
        public String toString() {
            return "DiShareScreen{deviceId='" + deviceId
                    + "', screenId='" + screenId
                    + "', available=" + available + '}';
        }
    }

    private static final class DiShareState {
        final String sessionId;
        final String provider;
        final String[] receivers;
        final String sharedApp;

        private DiShareState(String sessionId, String provider, String[] receivers,
                String sharedApp) {
            this.sessionId = sessionId;
            this.provider = provider;
            this.receivers = receivers == null ? new String[0] : receivers;
            this.sharedApp = sharedApp;
        }

        static DiShareState read(Parcel parcel) {
            return new DiShareState(parcel.readString(), parcel.readString(),
                    parcel.createStringArray(), parcel.readString());
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("DiShareState{sessionId='");
            builder.append(sessionId).append("', provider='").append(provider)
                    .append("', receivers=[");
            for (int i = 0; i < receivers.length; i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(receivers[i]);
            }
            return builder.append("], sharedApp='").append(sharedApp).append("'}").toString();
        }
    }
}
