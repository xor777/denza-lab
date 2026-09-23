package dev.denza.hudframes.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The stock HUD navigation service 266 on `com.ts.car.someip.service`, driven the way
 * Denza Apps' `HudSomeIpClient` drives it (bind by action + component, tx 4 start, tx 6 fire,
 * tx 5 stop). Blocking calls; use it from one worker thread.
 */
final class HudSomeIpSender {
    static final String TAG = "DenzaHudFramesProbe";

    private static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    private static final String ACTION = "com.ts.car.someip.SomeIpServerService";
    private static final String PACKAGE = "com.ts.car.someip.service";
    private static final String SERVICE = "com.ts.car.someip.service.manager.SomeIpServerService";
    private static final int TX_START = 4;
    private static final int TX_STOP = 5;
    private static final int TX_FIRE = 6;

    static final long SVC_HUD_NAVI = 3097367205183488L;
    /** Event 0x8001, `HudRoadInfo_EG{1: HudRoadInfoNotifyStruct}`. */
    static final long TOPIC_HUD_ROAD = 1127042368241665L;
    /** Event 0x8003, `HudNavigationmap{1: string}` with a Base64 image, no envelope. */
    static final long TOPIC_HUD_MAP = 1127042368241667L;

    private final Context context;
    private final CountDownLatch connected = new CountDownLatch(1);
    private volatile IBinder binder;
    private boolean bound;
    private boolean started;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            connected.countDown();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            Log.w(TAG, "someip disconnected");
        }
    };

    HudSomeIpSender(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Binds and offers service 266; returns the tx 4 result, or a negative local error. */
    int open(long timeoutMs) throws InterruptedException {
        Intent intent = new Intent(ACTION)
                .setComponent(new ComponentName(PACKAGE, SERVICE))
                .setType(context.getPackageName());
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            return -1;
        }
        if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            return -2;
        }
        int result = call(TX_START, SVC_HUD_NAVI);
        started = result == 0;
        return result;
    }

    /** Fires one event; returns the service result (0 = accepted). */
    int fire(long topic, byte[] payload) {
        IBinder service = binder;
        if (service == null) {
            return -100;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(1);
            data.writeLong(topic);
            data.writeLong(0L);
            data.writeInt(payload.length);
            data.writeByteArray(payload);
            service.transact(TX_FIRE, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RuntimeException | RemoteException error) {
            Log.e(TAG, "fire failed", error);
            return -200;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** Sends the stock "not navigating" packet, withdraws the offer and unbinds. */
    void close() {
        close(true, null);
    }

    /**
     * Withdraws the offer and unbinds. `clearRoad` sends the stock "not navigating" packet first;
     * the Yandex channel leaves the road event to Denza Apps' own guidance and instead blanks the
     * map window with `lastMap` (black is invisible on a HUD).
     */
    void close(boolean clearRoad, byte[] lastMap) {
        if (started) {
            if (lastMap != null) {
                Log.i(TAG, "blank map ret=" + fire(TOPIC_HUD_MAP, mapPayload(lastMap)));
            }
            if (clearRoad) {
                Log.i(TAG, "clear ret=" + fire(TOPIC_HUD_ROAD, roadPayload(1, true, null, 0, "", "", "")));
            }
            Log.i(TAG, "stop ret=" + call(TX_STOP, SVC_HUD_NAVI));
        }
        started = false;
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException error) {
                Log.w(TAG, "unbind failed", error);
            }
            bound = false;
        }
        binder = null;
    }

    private int call(int transaction, long serviceId) {
        IBinder service = binder;
        if (service == null) {
            return -100;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeLong(serviceId);
            service.transact(transaction, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RuntimeException | RemoteException error) {
            Log.e(TAG, "tx " + transaction + " failed", error);
            return -200;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * The compact road packet Denza Apps sends, field for field: 2 flag, 16 navigation state
     * (2 navigating, 1 cleared), 8 maneuver picture, 9 distance to it, 10 road text, 26 and 27
     * the two summary strings, 28 the maneuver id. Field 2 carries the stock's own "normal"
     * value 2 rather than a counter.
     */
    static byte[] roadPayload(
            int navigationState,
            boolean clear,
            byte[] icon,
            int distanceMeters,
            String road,
            String summary,
            String time) {
        ByteArrayOutputStream message = new ByteArrayOutputStream(256 + (icon == null ? 0 : icon.length));
        intField(message, 2, 2);
        intField(message, 16, navigationState);
        if (!clear) {
            bytesField(message, 8, icon);
            intField(message, 9, distanceMeters);
            stringField(message, 10, road);
            stringField(message, 26, summary);
            stringField(message, 27, time);
            // 11 = straight in the recovered HUD icon table; the picture in field 8 is ours.
            intField(message, 28, 11);
        }
        return embed(1, message.toByteArray());
    }

    /** `HudNavigationmap{navigation_map = Base64(image)}`, exactly as the stock navigator. */
    static byte[] mapPayload(byte[] image) {
        ByteArrayOutputStream message = new ByteArrayOutputStream(image.length * 4 / 3 + 16);
        stringField(message, 1, Base64.encodeToString(image, Base64.NO_WRAP));
        return message.toByteArray();
    }

    private static byte[] embed(int field, byte[] message) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(message.length + 8);
        varint(output, (field << 3) | 2);
        varint(output, message.length);
        output.write(message, 0, message.length);
        return output.toByteArray();
    }

    private static void stringField(ByteArrayOutputStream output, int field, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        bytesField(output, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytesField(ByteArrayOutputStream output, int field, byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        varint(output, (field << 3) | 2);
        varint(output, value.length);
        output.write(value, 0, value.length);
    }

    private static void intField(ByteArrayOutputStream output, int field, long value) {
        varint(output, field << 3);
        varint(output, value);
    }

    private static void varint(ByteArrayOutputStream output, long value) {
        while (true) {
            if ((value & ~0x7fL) == 0L) {
                output.write((int) value);
                return;
            }
            output.write((int) ((value & 0x7fL) | 0x80L));
            value >>>= 7;
        }
    }
}
