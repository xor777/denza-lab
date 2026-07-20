package dev.denza.tools;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Read-only shell-UID probe for the stock IVI-to-FSE upgrade channel.
 *
 * It sends only the connection, version-info, and platform-info requests. The
 * package transfer, verification, UI-control, and upgrade transactions are
 * deliberately not implemented here.
 */
public final class FseUpgradeInfoProbe {
    private static final String UPGRADE_DESCRIPTOR = "com.byd.upgradesdk.IUpgradeServer";
    private static final String ZMQ_LISTENER_DESCRIPTOR = "com.byd.upgradesdk.IZmqListener";
    private static final String SERVICE_NAME = "upgrade_server";
    private static final int IVI_DEVICE_ID = 1;
    private static final int FSE_DEVICE_ID = 2;
    private static final int TRANSACTION_REGISTER_LISTENER = 4;
    private static final int TRANSACTION_UNREGISTER_LISTENER = 5;
    private static final int TRANSACTION_SEND_ZMQ_CMD = 6;
    private static final int CONNECTION_RESPONSE = 257;
    private static final int VERSION_INFO_RESPONSE = 258;
    private static final int PLATFORM_INFO_RESPONSE = 259;

    private FseUpgradeInfoProbe() {
    }

    public static void main(String[] args) throws Exception {
        long timeoutSeconds = args.length == 0 ? 12L : Long.parseLong(args[0]);
        if (timeoutSeconds < 1L || timeoutSeconds > 60L) {
            throw new IllegalArgumentException("timeout must be in [1, 60] seconds");
        }

        IBinder upgradeServer = getService(SERVICE_NAME);
        if (upgradeServer == null) {
            throw new IllegalStateException(SERVICE_NAME + " is not registered");
        }

        ResponseListener listener = new ResponseListener();
        registerListener(upgradeServer, listener);
        try {
            sendInfoRequest(upgradeServer, 1);
            sendInfoRequest(upgradeServer, 2);
            sendInfoRequest(upgradeServer, 3);
            boolean complete = listener.await(timeoutSeconds, TimeUnit.SECONDS);
            System.out.println(complete
                    ? "FSE_INFO_COMPLETE responses=3/3"
                    : "FSE_INFO_TIMEOUT responses=" + listener.responseCount() + "/3");
        } finally {
            unregisterListener(upgradeServer, listener);
        }
    }

    private static IBinder getService(String name) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getMethod("getService", String.class);
        return (IBinder) getService.invoke(null, name);
    }

    private static void registerListener(IBinder server, IBinder listener) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(UPGRADE_DESCRIPTOR);
            data.writeStrongBinder(listener);
            requireTransact(server, TRANSACTION_REGISTER_LISTENER, data, reply);
            reply.readException();
            System.out.println("listener registered for FSE sender=" + FSE_DEVICE_ID);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void unregisterListener(IBinder server, IBinder listener) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(UPGRADE_DESCRIPTOR);
            data.writeStrongBinder(listener);
            requireTransact(server, TRANSACTION_UNREGISTER_LISTENER, data, reply);
            reply.readException();
            System.out.println("listener unregistered");
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void sendInfoRequest(IBinder server, int cmdType) throws Exception {
        JSONObject command = new JSONObject();
        command.put("cmdContent", JSONObject.NULL);
        command.put("cmdReceiver", FSE_DEVICE_ID);
        command.put("cmdSender", IVI_DEVICE_ID);
        command.put("cmdType", cmdType);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(UPGRADE_DESCRIPTOR);
            data.writeInt(IVI_DEVICE_ID);
            data.writeInt(FSE_DEVICE_ID);
            data.writeString(command.toString());
            requireTransact(server, TRANSACTION_SEND_ZMQ_CMD, data, reply);
            reply.readException();
            boolean accepted = reply.readInt() != 0;
            System.out.println("request type=" + cmdType + " accepted=" + accepted);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void requireTransact(IBinder binder, int code, Parcel data, Parcel reply)
            throws Exception {
        if (!binder.transact(code, data, reply, 0)) {
            throw new IllegalStateException("Binder transaction " + code + " was rejected");
        }
    }

    private static final class ResponseListener extends Binder implements IInterface {
        private final CountDownLatch responses = new CountDownLatch(3);
        private final Set<Integer> responseTypes = new HashSet<>();

        ResponseListener() {
            attachInterface(this, ZMQ_LISTENER_DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            try {
                if (code == INTERFACE_TRANSACTION) {
                    reply.writeString(ZMQ_LISTENER_DESCRIPTOR);
                    return true;
                }
                data.enforceInterface(ZMQ_LISTENER_DESCRIPTOR);
                if (code == FIRST_CALL_TRANSACTION) {
                    reply.writeNoException();
                    reply.writeInt(FSE_DEVICE_ID);
                    return true;
                }
                if (code == FIRST_CALL_TRANSACTION + 1) {
                    String raw = data.readString();
                    System.out.println("FSE_RESPONSE " + raw);
                    JSONObject response = new JSONObject(raw);
                    int cmdType = response.optInt("cmdType", -1);
                    if (isExpectedResponse(cmdType)) {
                        synchronized (responseTypes) {
                            if (responseTypes.add(cmdType)) {
                                responses.countDown();
                            }
                        }
                    }
                    reply.writeNoException();
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            } catch (Exception error) {
                if (reply != null) {
                    reply.writeException(error);
                }
                return true;
            }
        }

        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return responses.await(timeout, unit);
        }

        int responseCount() {
            synchronized (responseTypes) {
                return responseTypes.size();
            }
        }

        private static boolean isExpectedResponse(int cmdType) {
            return cmdType == CONNECTION_RESPONSE
                    || cmdType == VERSION_INFO_RESPONSE
                    || cmdType == PLATFORM_INFO_RESPONSE;
        }
    }
}
