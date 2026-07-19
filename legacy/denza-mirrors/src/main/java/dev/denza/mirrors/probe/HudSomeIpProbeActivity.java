package dev.denza.mirrors.probe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class HudSomeIpProbeActivity extends Activity {
    private static final String TAG = "DenzaHudSomeIp";
    private static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    private static final String ACTION = "com.ts.car.someip.SomeIpServerService";
    private static final String PACKAGE = "com.ts.car.someip.service";
    private static final String SERVICE = "com.ts.car.someip.service.manager.SomeIpServerService";
    private static final int TX_START = 4;
    private static final int TX_STOP = 5;
    private static final int TX_FIRE = 6;

    private static final long SVC_HUD_NAVI = 3097367205183488L;
    private static final long TOPIC_HUD_ROAD = 1127042368241665L;
    private static final long TOPIC_HUD_MAP = 1127042368241667L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private File statusFile;
    private IBinder someIpBinder;
    private boolean bound;
    private int fireCount;
    private int repeatCount;
    private long intervalMs;
    private boolean roadProbe;
    private byte[] turnPng;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            someIpBinder = service;
            log("connected " + name.flattenToShortString());
            startSomeIpService();
            if (!roadProbe && getIntent().getBooleanExtra("enable_hud_map", true)) {
                enableHudMap();
            }
            fireLoop();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            someIpBinder = null;
            bound = false;
            log("disconnected " + name.flattenToShortString());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusFile = new File(getFilesDir(), "hud_someip_status.txt");
        resetStatus();
        roadProbe = "road".equals(getIntent().getStringExtra("mode"));
        if (roadProbe) {
            turnPng = buildProbeTurnPng();
        }
        repeatCount = Math.max(1, getIntent().getIntExtra("repeat", 5));
        intervalMs = Math.max(roadProbe ? 80L : 200L,
                getIntent().getLongExtra("interval_ms", roadProbe ? 100L : 700L));
        bindSomeIp();
        handler.postDelayed(this::finishAndRemoveTask,
                Math.max(5000L, repeatCount * intervalMs + 2500L));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (bound) {
            try {
                unbindService(connection);
            } catch (RuntimeException e) {
                log("unbind failed: " + shortError(e));
            }
            bound = false;
        }
        super.onDestroy();
    }

    private void bindSomeIp() {
        Intent intent = new Intent(ACTION);
        intent.setComponent(new ComponentName(PACKAGE, SERVICE));
        intent.setType(getPackageName());
        try {
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            log("bind result=" + bound);
        } catch (RuntimeException e) {
            log("bind failed: " + shortError(e));
        }
    }

    private void startSomeIpService() {
        IBinder binder = someIpBinder;
        if (binder == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeLong(SVC_HUD_NAVI);
            binder.transact(TX_START, data, reply, 0);
            reply.readException();
            log("start svc ret=" + reply.readInt());
        } catch (RuntimeException | android.os.RemoteException e) {
            log("start svc failed: " + shortError(e));
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void enableHudMap() {
        try {
            Class<?> managerClass = Class.forName("com.byd.car.feature.vision.ICarHudManager");
            Object manager = Class.forName("com.byd.car.DiCar")
                    .getMethod("getCarManager", Context.class, Class.class)
                    .invoke(null, this, managerClass);
            Object result = managerClass.getMethod("setNavigationMapEnabled", boolean.class)
                    .invoke(manager, true);
            log("enable hud map result=" + result);
        } catch (Throwable e) {
            log("enable hud map failed: " + shortError(e));
        }
    }

    private void fireLoop() {
        if (fireCount >= repeatCount) {
            log("fire loop done count=" + fireCount);
            if (roadProbe) {
                int clearResult = fireRoad(true);
                log("fire road clear ret=" + clearResult);
                handler.postDelayed(this::stopSomeIpService, 300L);
            }
            return;
        }
        int result = roadProbe ? fireRoad(false) : fireMap();
        log("fire " + (roadProbe ? "road" : "map") + " #" + fireCount + " ret=" + result);
        fireCount++;
        handler.postDelayed(this::fireLoop, intervalMs);
    }

    private int fireMap() {
        return fire(TOPIC_HUD_MAP, buildHudMapPayload(buildProbePng()));
    }

    private int fireRoad(boolean clear) {
        return fire(TOPIC_HUD_ROAD, buildHudRoadPayload(clear, fireCount, turnPng));
    }

    private int fire(long topic, byte[] payload) {
        IBinder binder = someIpBinder;
        if (binder == null) {
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
            binder.transact(TX_FIRE, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RuntimeException | android.os.RemoteException e) {
            log("fire map failed: " + shortError(e));
            return -200;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void stopSomeIpService() {
        IBinder binder = someIpBinder;
        if (binder == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeLong(SVC_HUD_NAVI);
            binder.transact(TX_STOP, data, reply, 0);
            reply.readException();
            log("stop svc ret=" + reply.readInt());
        } catch (RuntimeException | android.os.RemoteException e) {
            log("stop svc failed: " + shortError(e));
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private byte[] buildProbePng() {
        int width = getIntent().getIntExtra("width", 480);
        int height = getIntent().getIntExtra("height", 180);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawColor(Color.rgb(2, 10, 16));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(0, 210, 180));
        canvas.drawRect(0, 0, width / 2f, height, paint);
        paint.setColor(Color.rgb(255, 216, 64));
        canvas.drawRect(width / 2f, 0, width, height, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(3f, width / 120f));
        paint.setColor(Color.WHITE);
        canvas.drawRect(2, 2, width - 2, height - 2, paint);
        canvas.drawLine(width / 2f, 0, width / 2f, height, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTextSize(Math.max(20f, height / 5f));
        canvas.drawText("HUD SOME/IP", width / 2f, height * 0.42f, paint);
        paint.setTextSize(Math.max(14f, height / 9f));
        canvas.drawText("denza gateway " + System.currentTimeMillis(),
                width / 2f, height * 0.65f, paint);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, output);
        bitmap.recycle();
        byte[] png = output.toByteArray();
        log("png bytes=" + png.length + " size=" + width + "x" + height);
        return png;
    }

    private byte[] buildProbeTurnPng() {
        int size = 192;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(20f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        Path path = new Path();
        path.moveTo(52f, 156f);
        path.lineTo(52f, 92f);
        path.cubicTo(52f, 66f, 72f, 48f, 99f, 48f);
        path.lineTo(142f, 48f);
        canvas.drawPath(path, paint);

        Path arrow = new Path();
        arrow.moveTo(116f, 22f);
        arrow.lineTo(144f, 48f);
        arrow.lineTo(116f, 74f);
        canvas.drawPath(arrow, paint);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        byte[] png = output.toByteArray();
        log("turn png bytes=" + png.length + " size=" + size + "x" + size);
        return png;
    }

    private static byte[] buildHudMapPayload(byte[] pngBytes) {
        String base64 = Base64.encodeToString(pngBytes, Base64.NO_WRAP);
        ByteArrayOutputStream message = new ByteArrayOutputStream(base64.length() + 8);
        sfield(message, 1, base64);
        return embed(1, message.toByteArray());
    }

    private static byte[] buildHudRoadPayload(boolean clear, int counter, byte[] turnPng) {
        ByteArrayOutputStream message = new ByteArrayOutputStream(96);
        ifield(message, 2, counter);
        ifield(message, 16, clear ? 1 : 2);
        if (!clear) {
            ifield(message, 3, 25_000);
            ifield(message, 4, 1_200);
            bfield(message, 8, turnPng);
            ifield(message, 9, 120);
            sfield(message, 10, "HUD TEST");
            sfield(message, 26, "12:34");
            sfield(message, 27, "20 min");
            ifield(message, 28, 2);
        }
        return embed(1, message.toByteArray());
    }

    private static byte[] embed(int field, byte[] message) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(message.length + 8);
        varint(output, (field << 3) | 2);
        varint(output, message.length);
        output.write(message, 0, message.length);
        return output.toByteArray();
    }

    private static void sfield(ByteArrayOutputStream output, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varint(output, (field << 3) | 2);
        varint(output, bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void bfield(ByteArrayOutputStream output, int field, byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        varint(output, (field << 3) | 2);
        varint(output, value.length);
        output.write(value, 0, value.length);
    }

    private static void ifield(ByteArrayOutputStream output, int field, long value) {
        varint(output, field << 3);
        varint(output, value);
    }

    private static void varint(ByteArrayOutputStream output, long value) {
        while (true) {
            if ((value & ~0x7fL) == 0) {
                output.write((int) value);
                return;
            }
            output.write((int) ((value & 0x7fL) | 0x80L));
            value >>>= 7;
        }
    }

    private void log(String message) {
        String line = System.currentTimeMillis() + " " + message;
        Log.i(TAG, message);
        try (FileOutputStream output = new FileOutputStream(statusFile, true)) {
            output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "status write failed", e);
        }
    }

    private void resetStatus() {
        try (FileOutputStream output = new FileOutputStream(statusFile, false)) {
            output.write(new byte[0]);
        } catch (IOException e) {
            Log.w(TAG, "status reset failed", e);
        }
    }

    private static String shortError(Throwable error) {
        if (error == null) {
            return "null";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
