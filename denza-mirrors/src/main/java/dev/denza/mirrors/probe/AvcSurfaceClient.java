package dev.denza.mirrors.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

final class AvcSurfaceClient {
    interface Logger {
        void log(String message);
    }

    private static final String TAG = "DenzaAvcSurface";

    private final Context context;
    private final int viewpoint;
    private final boolean uTurnEnabled;
    private final Logger logger;
    private Surface surface;
    private AvcAidlClient avcClient;
    private boolean bound;
    private boolean initAttempted;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            avcClient = new AvcAidlClient(service);
            bound = true;
            log("avc connected " + name.flattenToShortString());
            initIfReady();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            log("avc disconnected " + name.flattenToShortString());
            avcClient = null;
            bound = false;
            initAttempted = false;
        }
    };

    AvcSurfaceClient(Context context, int viewpoint, boolean uTurnEnabled, Logger logger) {
        this.context = context.getApplicationContext();
        this.viewpoint = viewpoint;
        this.uTurnEnabled = uTurnEnabled;
        this.logger = logger;
    }

    void start(Surface surface) {
        this.surface = surface;
        Intent intent = new Intent("com.byd.avc.aidl.service");
        intent.setPackage("com.byd.avc");
        try {
            boolean result = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            log("avc bind result=" + result + " vp=" + viewpoint);
            if (!result) {
                initAttempted = false;
            }
        } catch (RuntimeException e) {
            log("avc bind failed: " + shortError(e));
        }
    }

    void release() {
        if (avcClient != null) {
            try {
                avcClient.freeDisplay();
                if (uTurnEnabled) {
                    avcClient.setuTurnEnable(false);
                }
                log("avc free display ok");
            } catch (RemoteException | RuntimeException e) {
                log("avc release failed: " + shortError(e));
            }
        }
        if (bound) {
            try {
                context.unbindService(serviceConnection);
            } catch (IllegalArgumentException e) {
                log("avc unbind skipped: " + shortError(e));
            }
        }
        avcClient = null;
        bound = false;
        initAttempted = false;
        surface = null;
    }

    private void initIfReady() {
        if (!bound || avcClient == null || surface == null || !surface.isValid()) {
            return;
        }
        if (initAttempted) {
            return;
        }
        initAttempted = true;
        String step = "getName";
        try {
            String name = avcClient.getName();
            step = "bufferType";
            int bufferType = avcClient.getSupportPushBufferType();
            if (uTurnEnabled) {
                step = "setuTurn(true)";
                avcClient.setuTurnEnable(true);
            }
            step = "initDisplay";
            boolean initialized = avcClient.initDisplay(surface);
            step = "setViewpoint";
            avcClient.setViewpoint(viewpoint);
            log("avc " + name + " init=" + initialized
                    + " buffer=" + bufferType + " vp=" + viewpoint);
        } catch (RemoteException | RuntimeException e) {
            log("avc " + step + " failed: " + shortError(e));
            Log.i(TAG, "avc init failed", e);
        }
    }

    private void log(String message) {
        Log.i(TAG, message);
        if (logger != null) {
            logger.log(message);
        }
    }

    private static String shortError(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        String message = throwable.getMessage();
        if (message == null || message.isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + " " + message;
    }

    private static final class AvcAidlClient {
        private static final String DESCRIPTOR = "com.byd.avc.aidl.IAVCAidlInterface";
        private static final int TRANSACTION_GET_NAME = 1;
        private static final int TRANSACTION_SET_VIEWPOINT = 5;
        private static final int TRANSACTION_GET_SUPPORT_PUSH_BUFFER_TYPE = 6;
        private static final int TRANSACTION_INIT_DISPLAY = 7;
        private static final int TRANSACTION_FREE_DISPLAY = 9;
        private static final int TRANSACTION_SET_UTURN_ENABLE = 10;

        private final IBinder remote;

        AvcAidlClient(IBinder remote) {
            this.remote = remote;
        }

        String getName() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TRANSACTION_GET_NAME, data, reply, 0);
                reply.readException();
                return reply.readString();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        int getSupportPushBufferType() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TRANSACTION_GET_SUPPORT_PUSH_BUFFER_TYPE, data, reply, 0);
                reply.readException();
                return reply.readInt();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        boolean initDisplay(Surface surface) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                if (surface == null) {
                    data.writeInt(0);
                } else {
                    data.writeInt(1);
                    surface.writeToParcel(data, 0);
                }
                remote.transact(TRANSACTION_INIT_DISPLAY, data, reply, 0);
                reply.readException();
                return reply.readInt() != 0;
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void setViewpoint(int viewpoint) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(viewpoint);
                remote.transact(TRANSACTION_SET_VIEWPOINT, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void setuTurnEnable(boolean enabled) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeInt(enabled ? 1 : 0);
                remote.transact(TRANSACTION_SET_UTURN_ENABLE, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }

        void freeDisplay() throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                remote.transact(TRANSACTION_FREE_DISPLAY, data, reply, 0);
                reply.readException();
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
    }
}
