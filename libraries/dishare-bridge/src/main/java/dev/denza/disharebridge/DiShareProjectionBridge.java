package dev.denza.disharebridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class DiShareProjectionBridge {
    public interface Callback {
        void onLog(String message);

        void onStarted(Bundle result);

        void onFailed(String message);

        void onStopped(String message);
    }

    private static final String TAG = "DenzaDiShareBridge";
    private static final String API_ACTION = "com.byd.dishare.api.DiShareApiService";
    private static final String API_PACKAGE = "com.byd.dishare";
    private static final String API_DESCRIPTOR = "com.byd.dishare.api.IDiShareApiService";
    private static final String API_CLIENT_DESCRIPTOR = "com.byd.dishare.api.IDiShareApiClient";
    private static final String CONTROL_ACTION = "com.byd.dishare.control.DiShareControlService";
    private static final String CONTROL_DESCRIPTOR = "com.byd.dishare.control.IDiShareControl";
    private static final String CONTROL_PACKAGE = "com.byd.dishare";
    private static final String LISTENER_DESCRIPTOR = "com.byd.dishare.IDiShareListener";

    private static final int TX_CREATE_CLIENT = 1;
    private static final int TX_REMOVE_CLIENT = 7;
    private static final int TX_FINISH_SHARE = 10;
    private static final int TX_SET_GESTURE_SHARE = 9;
    private static final int TX_SET_VIDEO_SIZE = 11;
    private static final int TX_SET_VIDEO_BOUNDS = 12;
    private static final int TX_REGISTER = 0x2;
    private static final int TX_GET_STATE = 0x5;
    private static final int TX_START = 0x6;
    private static final int TX_STOP = 0x7;
    private static final int TX_CLOSE_DISHARE_UI = 0xb;
    private static final int SOURCE_VIDEO_WIDTH = 2560;
    private static final int SOURCE_VIDEO_HEIGHT = 1440;
    private static final int TARGET_VIDEO_WIDTH = 1024;
    private static final int TARGET_VIDEO_HEIGHT = 576;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ApiClientBinder apiClient = new ApiClientBinder();
    private final DiShareListenerBinder listener = new DiShareListenerBinder();
    private final String targetPackage;
    private final Callback callback;

    private IBinder apiBinder;
    private IBinder controlBinder;
    private boolean apiBound;
    private boolean controlBound;
    private boolean started;
    private boolean failed;
    private boolean sourceOnly;
    private boolean copyCurrentShareTarget;
    private List<String> receiverOverride;
    private int targetVideoWidth;
    private int targetVideoHeight;
    private Rect targetVideoBounds;

    private final ServiceConnection apiConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            apiBinder = service;
            log("api connected " + name.flattenToShortString());
            try {
                createApiSource();
                if (sourceOnly) {
                    Bundle result = new Bundle();
                    result.putInt("source_only", 0);
                    started = true;
                    handler.removeCallbacksAndMessages(null);
                    callback.onStarted(result);
                } else {
                    bindControl();
                }
            } catch (RuntimeException e) {
                fail("api failed: " + shortError(e));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            apiBinder = null;
            apiBound = false;
            log("api disconnected " + name.flattenToShortString());
        }
    };

    private final ServiceConnection controlConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            controlBinder = service;
            log("control connected " + name.flattenToShortString());
            try {
                registerControlClient();
                Bundle result = startShare();
                if (isSuccessfulResult(result)) {
                    started = true;
                    unbindControl();
                    handler.removeCallbacksAndMessages(null);
                    callback.onStarted(result);
                } else {
                    fail("start returned " + bundleToString(result));
                }
            } catch (RuntimeException e) {
                fail("control failed: " + shortError(e));
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            controlBinder = null;
            controlBound = false;
            log("control disconnected " + name.flattenToShortString());
        }
    };

    public DiShareProjectionBridge(Context context, String targetPackage, Callback callback) {
        this.context = context.getApplicationContext();
        this.targetPackage = targetPackage;
        this.callback = callback;
    }

    public static void stopCurrentShare(Context context, Callback callback) {
        new CurrentShareStopper(context.getApplicationContext(), callback).start();
    }

    public static void closeUi(Context context, String screenId, Callback callback) {
        new DiShareUiCloser(context.getApplicationContext(), screenId, callback).start();
    }

    public String getTargetPackage() {
        return targetPackage;
    }

    public boolean isStarted() {
        return started;
    }

    public void start() {
        start(false, false);
    }

    public void startToReceiver(String receiver) {
        startToReceivers(Collections.singletonList(receiver));
    }

    public void startToReceiver(String receiver, int videoWidth, int videoHeight) {
        startToReceivers(Collections.singletonList(receiver), videoWidth, videoHeight);
    }

    public void startToReceiver(String receiver, int videoWidth, int videoHeight,
            Rect videoBounds) {
        start(false, false, Collections.singletonList(receiver),
                videoWidth, videoHeight, videoBounds);
    }

    public void startToReceivers(List<String> receivers) {
        startToReceivers(receivers, 0, 0);
    }

    public void startToReceivers(List<String> receivers, int videoWidth, int videoHeight) {
        start(false, false, receivers, videoWidth, videoHeight, null);
    }

    public void startSourceOnly() {
        start(true, false);
    }

    public void startLikeCurrentShare() {
        start(false, true);
    }

    private void start(boolean sourceOnly, boolean copyCurrentShareTarget) {
        start(sourceOnly, copyCurrentShareTarget, null, 0, 0, null);
    }

    private void start(boolean sourceOnly, boolean copyCurrentShareTarget,
            List<String> receiverOverride, int videoWidth, int videoHeight,
            Rect videoBounds) {
        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            fail("target package is empty");
            return;
        }
        this.sourceOnly = sourceOnly;
        this.copyCurrentShareTarget = copyCurrentShareTarget;
        this.receiverOverride = sanitizeReceivers(receiverOverride);
        this.targetVideoWidth = sanitizeVideoDimension(videoWidth);
        this.targetVideoHeight = sanitizeVideoDimension(videoHeight);
        this.targetVideoBounds = sanitizeVideoBounds(videoBounds);
        this.failed = false;
        this.started = false;
        log("start target=" + targetPackage
                + " copyCurrentShareTarget=" + copyCurrentShareTarget
                + " video=" + targetVideoWidth + "x" + targetVideoHeight
                + " bounds=" + targetVideoBounds);
        Intent intent = new Intent();
        intent.setAction(API_ACTION);
        intent.setPackage(API_PACKAGE);
        try {
            apiBound = context.bindService(intent, apiConnection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException e) {
            fail("bind api failed: " + shortError(e));
            return;
        }
        if (!apiBound) {
            fail("bind api returned false");
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                fail("timeout");
            }
        }, 5000L);
    }

    public void stop() {
        handler.removeCallbacksAndMessages(null);
        String stopResult = "stopped";
        try {
            if (apiBinder != null) {
                if (!sourceOnly) {
                    callFinishShare();
                }
                callRemoveClient();
            }
        } catch (RuntimeException e) {
            stopResult = "stop warning: " + shortError(e);
        }
        cleanup();
        started = false;
        callback.onStopped(stopResult);
    }

    private void createApiSource() {
        final int videoWidth = sourceOnly ? SOURCE_VIDEO_WIDTH
                : (targetVideoWidth > 0 ? targetVideoWidth : TARGET_VIDEO_WIDTH);
        final int videoHeight = sourceOnly ? SOURCE_VIDEO_HEIGHT
                : (targetVideoHeight > 0 ? targetVideoHeight : TARGET_VIDEO_HEIGHT);
        final Rect videoBounds = targetVideoBounds != null
                ? new Rect(targetVideoBounds)
                : new Rect(0, 0, videoWidth, videoHeight);
        transactApi(TX_CREATE_CLIENT, new ParcelWriter() {
            @Override
            public void write(Parcel data) {
                data.writeStrongBinder(apiClient);
                data.writeString(targetPackage);
            }
        });
        transactApi(TX_SET_VIDEO_SIZE, new ParcelWriter() {
            @Override
            public void write(Parcel data) {
                data.writeStrongBinder(apiClient);
                data.writeInt(videoWidth);
                data.writeInt(videoHeight);
            }
        });
        transactApi(TX_SET_VIDEO_BOUNDS, new ParcelWriter() {
            @Override
            public void write(Parcel data) {
                data.writeStrongBinder(apiClient);
                data.writeInt(1);
                videoBounds.writeToParcel(data, 0);
            }
        });
        transactApi(TX_SET_GESTURE_SHARE, new ParcelWriter() {
            @Override
            public void write(Parcel data) {
                data.writeStrongBinder(apiClient);
                data.writeInt(1);
            }
        });
        log("api source registered target=" + targetPackage
                + " size=" + videoWidth + "x" + videoHeight
                + " bounds=" + videoBounds);
    }

    private void bindControl() {
        Intent intent = new Intent();
        intent.setAction(CONTROL_ACTION);
        intent.setPackage(API_PACKAGE);
        controlBound = context.bindService(intent, controlConnection, Context.BIND_AUTO_CREATE);
        if (!controlBound) {
            throw new IllegalStateException("bind control returned false");
        }
    }

    private void registerControlClient() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CONTROL_DESCRIPTOR);
            data.writeStrongBinder(listener);
            data.writeString(CONTROL_PACKAGE);
            transactControl(TX_REGISTER, data, reply);
            reply.readException();
            log("control client registered package=" + CONTROL_PACKAGE);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private Bundle startShare() {
        DiShareState currentState = copyCurrentShareTarget ? readShareState() : null;
        String provider = "screen_ivi";
        List<String> receivers = Collections.singletonList("screen_hud");
        if (receiverOverride != null && !receiverOverride.isEmpty()) {
            receivers = receiverOverride;
            log("use receiver override " + receivers);
        } else if (currentState != null && currentState.receivers != null
                && !currentState.receivers.isEmpty()) {
            if (currentState.provider != null && !currentState.provider.trim().isEmpty()) {
                provider = currentState.provider;
            }
            receivers = currentState.receivers;
            log("copy current share state session=" + currentState.sessionId
                    + " provider=" + provider
                    + " receivers=" + receivers
                    + " sharedApp=" + currentState.sharedApp);
        } else if (copyCurrentShareTarget) {
            log("current share state empty, fallback receivers=" + receivers);
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CONTROL_DESCRIPTOR);
            data.writeString(provider);
            data.writeStringList(receivers);
            data.writeString(targetPackage);
            data.writeString(CONTROL_PACKAGE);
            transactControl(TX_START, data, reply);
            reply.readException();
            if (reply.readInt() == 0) {
                log("start result=null");
                return null;
            }
            Bundle result = reply.readBundle(DiShareProjectionBridge.class.getClassLoader());
            log("start result=" + bundleToString(result));
            return result;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private List<String> sanitizeReceivers(List<String> receivers) {
        if (receivers == null || receivers.isEmpty()) {
            return null;
        }
        ArrayList<String> clean = new ArrayList<>();
        for (String receiver : receivers) {
            if (receiver != null && !receiver.trim().isEmpty()
                    && !clean.contains(receiver.trim())) {
                clean.add(receiver.trim());
            }
        }
        return clean.isEmpty() ? null : Collections.unmodifiableList(clean);
    }

    private int sanitizeVideoDimension(int value) {
        return value >= 180 && value <= 4096 ? value : 0;
    }

    private Rect sanitizeVideoBounds(Rect bounds) {
        if (bounds == null || bounds.width() < 180 || bounds.height() < 180
                || bounds.width() > 4096 || bounds.height() > 4096) {
            return null;
        }
        return new Rect(bounds);
    }

    private DiShareState readShareState() {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CONTROL_DESCRIPTOR);
            data.writeString(CONTROL_PACKAGE);
            transactControl(TX_GET_STATE, data, reply);
            reply.readException();
            if (reply.readInt() == 0) {
                log("current share state=null");
                return null;
            }
            DiShareState state = DiShareState.readFrom(reply);
            log("current share state=" + state);
            return state;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void callFinishShare() {
        transactApi(TX_FINISH_SHARE, new ParcelWriter() {
            @Override
            public void write(Parcel data) {
                data.writeStrongBinder(apiClient);
            }
        });
        log("finish share ok");
    }

    private void callRemoveClient() {
        transactApi(TX_REMOVE_CLIENT, new ParcelWriter() {
            @Override
            public void write(Parcel data) {
                data.writeStrongBinder(apiClient);
            }
        });
        log("remove client ok");
    }

    private void transactApi(int code, ParcelWriter writer) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(API_DESCRIPTOR);
            writer.write(data);
            if (apiBinder == null || !apiBinder.transact(code, data, reply, 0)) {
                throw new IllegalStateException("api transact false code=" + code);
            }
            reply.readException();
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactControl(int code, Parcel data, Parcel reply) {
        try {
            if (controlBinder == null || !controlBinder.transact(code, data, reply, 0)) {
                throw new IllegalStateException("control transact false code=" + code);
            }
        } catch (RemoteException e) {
            throw new IllegalStateException(e);
        }
    }

    private void fail(String message) {
        if (failed || started) {
            return;
        }
        failed = true;
        cleanup();
        log("failed " + message);
        callback.onFailed(message);
    }

    private void cleanup() {
        handler.removeCallbacksAndMessages(null);
        unbindControl();
        if (apiBound) {
            try {
                context.unbindService(apiConnection);
            } catch (RuntimeException ignored) {
                // Service can already be gone if DiShare restarted.
            }
            apiBound = false;
        }
        apiBinder = null;
        controlBinder = null;
    }

    private void unbindControl() {
        if (!controlBound) {
            return;
        }
        try {
            context.unbindService(controlConnection);
        } catch (RuntimeException ignored) {
            // Service can already be gone if DiShare restarted.
        }
        controlBound = false;
        controlBinder = null;
    }

    private boolean isSuccessfulResult(Bundle result) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        Set<String> keys = result.keySet();
        for (String key : keys) {
            Object value = result.get(key);
            if (value instanceof Integer && ((Integer) value).intValue() != 0) {
                return false;
            }
        }
        return true;
    }

    private void log(String message) {
        Log.i(TAG, message);
        callback.onLog(message);
    }

    public static String bundleToString(Bundle bundle) {
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

    private static String shortError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static final class DiShareState {
        final String sessionId;
        final String provider;
        final List<String> receivers;
        final String sharedApp;

        DiShareState(String sessionId, String provider, List<String> receivers,
                String sharedApp) {
            this.sessionId = sessionId;
            this.provider = provider;
            this.receivers = receivers;
            this.sharedApp = sharedApp;
        }

        static DiShareState readFrom(Parcel parcel) {
            String sessionId = parcel.readString();
            String provider = parcel.readString();
            String[] receiverArray = parcel.createStringArray();
            String sharedApp = parcel.readString();
            List<String> receivers = new ArrayList<>();
            if (receiverArray != null) {
                Collections.addAll(receivers, receiverArray);
            }
            return new DiShareState(sessionId, provider, receivers, sharedApp);
        }

        @Override
        public String toString() {
            return "DiShareState{sessionId=" + sessionId
                    + ", provider=" + provider
                    + ", receivers=" + receivers
                    + ", sharedApp=" + sharedApp
                    + '}';
        }
    }

    private interface ParcelWriter {
        void write(Parcel data);
    }

    private static final class CurrentShareStopper {
        private final Context context;
        private final Callback callback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final DiShareListenerBinder listener = new DiShareListenerBinder();
        private IBinder controlBinder;
        private boolean controlBound;
        private boolean finished;

        private final ServiceConnection controlConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                controlBinder = service;
                log("stop current control connected " + name.flattenToShortString());
                runStop();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                controlBinder = null;
                controlBound = false;
                log("stop current control disconnected " + name.flattenToShortString());
            }
        };

        CurrentShareStopper(Context context, Callback callback) {
            this.context = context;
            this.callback = callback;
        }

        void start() {
            Intent intent = new Intent();
            intent.setAction(CONTROL_ACTION);
            intent.setPackage(CONTROL_PACKAGE);
            try {
                controlBound = context.bindService(intent, controlConnection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException e) {
                fail("bind control failed: " + shortError(e));
                return;
            }
            if (!controlBound) {
                fail("bind control returned false");
                return;
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fail("timeout");
                }
            }, 5000L);
        }

        private void runStop() {
            try {
                registerControlClient();
                DiShareState state = readShareState();
                if (state == null || state.sessionId == null || state.sessionId.trim().isEmpty()) {
                    finishStopped("no active share");
                    return;
                }
                Bundle result = stopShare(state.sessionId);
                if (isSuccessfulControlResult(result)) {
                    finishStopped("stop current ok " + bundleToString(result));
                } else {
                    fail("stop current returned " + bundleToString(result));
                }
            } catch (RuntimeException e) {
                fail("stop current failed: " + shortError(e));
            }
        }

        private void registerControlClient() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeStrongBinder(listener);
                data.writeString(CONTROL_PACKAGE);
                transactControl(TX_REGISTER, data, reply);
                reply.readException();
                log("stop current registered package=" + CONTROL_PACKAGE);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private DiShareState readShareState() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(CONTROL_PACKAGE);
                transactControl(TX_GET_STATE, data, reply);
                reply.readException();
                if (reply.readInt() == 0) {
                    log("stop current state=null");
                    return null;
                }
                DiShareState state = DiShareState.readFrom(reply);
                log("stop current state=" + state);
                return state;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private Bundle stopShare(String sessionId) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(sessionId);
                data.writeString(CONTROL_PACKAGE);
                transactControl(TX_STOP, data, reply);
                reply.readException();
                if (reply.readInt() == 0) {
                    return null;
                }
                return reply.readBundle(DiShareProjectionBridge.class.getClassLoader());
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void transactControl(int code, Parcel data, Parcel reply) {
            try {
                if (controlBinder == null || !controlBinder.transact(code, data, reply, 0)) {
                    throw new IllegalStateException("control transact false code=" + code);
                }
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        }

        private void finishStopped(String message) {
            if (finished) {
                return;
            }
            log(message);
            cleanup();
            callback.onStopped(message);
        }

        private void fail(String message) {
            if (finished) {
                return;
            }
            log("failed " + message);
            cleanup();
            callback.onFailed(message);
        }

        private void cleanup() {
            finished = true;
            handler.removeCallbacksAndMessages(null);
            if (controlBound) {
                try {
                    context.unbindService(controlConnection);
                } catch (RuntimeException ignored) {
                    // Service can already be gone if DiShare restarted.
                }
                controlBound = false;
            }
            controlBinder = null;
        }

        private void log(String message) {
            Log.i(TAG, message);
            callback.onLog(message);
        }
    }

    private static final class DiShareUiCloser {
        private final Context context;
        private final String screenId;
        private final Callback callback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final DiShareListenerBinder listener = new DiShareListenerBinder();
        private IBinder controlBinder;
        private boolean controlBound;
        private boolean finished;

        private final ServiceConnection controlConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                controlBinder = service;
                log("close ui control connected " + name.flattenToShortString());
                runClose();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                controlBinder = null;
                controlBound = false;
                log("close ui control disconnected " + name.flattenToShortString());
            }
        };

        DiShareUiCloser(Context context, String screenId, Callback callback) {
            this.context = context;
            this.screenId = screenId == null || screenId.trim().isEmpty()
                    ? "screen_ivi" : screenId.trim();
            this.callback = callback;
        }

        void start() {
            Intent intent = new Intent();
            intent.setAction(CONTROL_ACTION);
            intent.setPackage(CONTROL_PACKAGE);
            try {
                controlBound = context.bindService(intent, controlConnection, Context.BIND_AUTO_CREATE);
            } catch (RuntimeException e) {
                fail("bind control failed: " + shortError(e));
                return;
            }
            if (!controlBound) {
                fail("bind control returned false");
                return;
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    fail("timeout");
                }
            }, 5000L);
        }

        private void runClose() {
            try {
                registerControlClient();
                Bundle result = closeUi();
                if (isSuccessfulControlResult(result)) {
                    finishClosed("close ui ok " + bundleToString(result));
                } else {
                    fail("close ui returned " + bundleToString(result));
                }
            } catch (RuntimeException e) {
                fail("close ui failed: " + shortError(e));
            }
        }

        private void registerControlClient() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeStrongBinder(listener);
                data.writeString(CONTROL_PACKAGE);
                transactControl(TX_REGISTER, data, reply);
                reply.readException();
                log("close ui registered package=" + CONTROL_PACKAGE);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private Bundle closeUi() {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CONTROL_DESCRIPTOR);
                data.writeString(screenId);
                data.writeString(CONTROL_PACKAGE);
                transactControl(TX_CLOSE_DISHARE_UI, data, reply);
                reply.readException();
                if (reply.readInt() == 0) {
                    return null;
                }
                return reply.readBundle(DiShareProjectionBridge.class.getClassLoader());
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        private void transactControl(int code, Parcel data, Parcel reply) {
            try {
                if (controlBinder == null || !controlBinder.transact(code, data, reply, 0)) {
                    throw new IllegalStateException("control transact false code=" + code);
                }
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
        }

        private void finishClosed(String message) {
            if (finished) {
                return;
            }
            log(message);
            cleanup();
            callback.onStopped(message);
        }

        private void fail(String message) {
            if (finished) {
                return;
            }
            log("failed " + message);
            cleanup();
            callback.onFailed(message);
        }

        private void cleanup() {
            finished = true;
            handler.removeCallbacksAndMessages(null);
            if (controlBound) {
                try {
                    context.unbindService(controlConnection);
                } catch (RuntimeException ignored) {
                    // Service can already be gone if DiShare restarted.
                }
                controlBound = false;
            }
            controlBinder = null;
        }

        private void log(String message) {
            Log.i(TAG, message);
            callback.onLog(message);
        }
    }

    private static final class ApiClientBinder extends Binder {
        ApiClientBinder() {
            attachInterface(null, API_CLIENT_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(API_CLIENT_DESCRIPTOR);
                return true;
            }
            if (code == 1) {
                data.enforceInterface(API_CLIENT_DESCRIPTOR);
                Log.i(TAG, "api active=" + (data.readInt() != 0));
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static final class DiShareListenerBinder extends Binder {
        DiShareListenerBinder() {
            attachInterface(null, LISTENER_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(LISTENER_DESCRIPTOR);
                return true;
            }
            if (code == 2 || code == 3 || code == 4) {
                data.enforceInterface(LISTENER_DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }

    private static boolean isSuccessfulControlResult(Bundle result) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        Set<String> keys = result.keySet();
        for (String key : keys) {
            Object value = result.get(key);
            if (value instanceof Integer && ((Integer) value).intValue() != 0) {
                return false;
            }
        }
        return true;
    }
}
