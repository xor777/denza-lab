package dev.denza.apps.feature.hud;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Product wrapper around the stock SOME/IP road-guidance endpoint verified by the HUD probe. */
final class HudSomeIpClient {
    private static final String TAG = "DenzaHudGuidance";
    private static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    private static final String ACTION = "com.ts.car.someip.SomeIpServerService";
    private static final String PACKAGE = "com.ts.car.someip.service";
    private static final String SERVICE = "com.ts.car.someip.service.manager.SomeIpServerService";
    private static final int TX_START = 4;
    private static final int TX_STOP = 5;
    private static final int TX_FIRE = 6;
    private static final long SVC_HUD_NAVI = 3097367205183488L;
    private static final long TOPIC_HUD_ROAD = 1127042368241665L;
    private static final long SHUTDOWN_DELAY_MS = 350L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int MAX_SCHEMATIC_ROUNDABOUT_EXIT = 12;
    private final Map<String, byte[]> iconCache = new HashMap<>();
    private final Runnable shutdownRunnable = this::stopAndUnbind;
    private IBinder binder;
    private boolean bound;
    private boolean serviceStarted;
    private HudGuidance pending;
    private int counter;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            serviceStarted = startSomeIpService();
            Log.i(TAG, "connected " + name.flattenToShortString() + " start=" + serviceStarted);
            HudGuidance guidance = pending;
            if (serviceStarted && guidance != null) {
                fireGuidance(guidance);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            serviceStarted = false;
            Log.w(TAG, "disconnected " + name.flattenToShortString());
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "binding died " + name.flattenToShortString());
            binder = null;
            serviceStarted = false;
            if (bound) {
                try {
                    context.unbindService(this);
                } catch (RuntimeException ignored) {
                }
                bound = false;
            }
            if (pending != null) {
                handler.postDelayed(HudSomeIpClient.this::ensureConnected, 500L);
            }
        }
    };

    HudSomeIpClient(Context context) {
        this.context = context.getApplicationContext();
    }

    void publish(HudGuidance guidance) {
        handler.removeCallbacks(shutdownRunnable);
        pending = guidance;
        ensureConnected();
        if (serviceStarted) {
            fireGuidance(guidance);
        }
    }

    void clear() {
        pending = null;
        if (serviceStarted) {
            int result = fire(TOPIC_HUD_ROAD, buildPayload(true, null, ++counter));
            Log.i(TAG, "clear ret=" + result);
        }
    }

    void shutdown() {
        pending = null;
        clear();
        handler.removeCallbacks(shutdownRunnable);
        handler.postDelayed(shutdownRunnable, SHUTDOWN_DELAY_MS);
    }

    private void ensureConnected() {
        handler.removeCallbacks(shutdownRunnable);
        if (bound) {
            return;
        }
        Intent intent = new Intent(ACTION);
        intent.setComponent(new ComponentName(PACKAGE, SERVICE));
        intent.setType(context.getPackageName());
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bind=" + bound);
        } catch (RuntimeException error) {
            bound = false;
            Log.e(TAG, "bind failed", error);
        }
    }

    private boolean startSomeIpService() {
        IBinder service = binder;
        if (service == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeLong(SVC_HUD_NAVI);
            service.transact(TX_START, data, reply, 0);
            reply.readException();
            return reply.readInt() == 0;
        } catch (RuntimeException | RemoteException error) {
            Log.e(TAG, "start failed", error);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void fireGuidance(HudGuidance guidance) {
        String iconKey = guidance.getManeuver().name() + ":" + guidance.getRoundaboutExitNumber();
        byte[] icon = iconCache.get(iconKey);
        if (icon == null) {
            icon = renderIcon(guidance.getManeuver(), guidance.getRoundaboutExitNumber());
            iconCache.put(iconKey, icon);
        }
        int result = fire(TOPIC_HUD_ROAD, buildPayload(false, guidance, ++counter, icon));
        if (result == 0) {
            Log.i(TAG, "published " + guidance.getInstruction() + " "
                    + guidance.getManeuverDistanceMeters() + "m"
                    + " route=" + guidance.getRemainingDistanceMeters()
                    + "m/" + guidance.getRemainingTimeSeconds() + "s"
                    + " eta=" + guidance.getEta()
                    + " roundaboutExit=" + guidance.getRoundaboutExitNumber()
                    + " road=" + guidance.getNextRoadName());
        } else {
            Log.w(TAG, "publish ret=" + result);
        }
    }

    private int fire(long topic, byte[] payload) {
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

    private void stopAndUnbind() {
        IBinder service = binder;
        if (serviceStarted && service != null) {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(DESCRIPTOR);
                data.writeLong(SVC_HUD_NAVI);
                service.transact(TX_STOP, data, reply, 0);
                reply.readException();
                Log.i(TAG, "stop ret=" + reply.readInt());
            } catch (RuntimeException | RemoteException error) {
                Log.w(TAG, "stop failed", error);
            } finally {
                reply.recycle();
                data.recycle();
            }
        }
        serviceStarted = false;
        binder = null;
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException error) {
                Log.w(TAG, "unbind failed", error);
            }
            bound = false;
        }
    }

    private static byte[] buildPayload(boolean clear, HudGuidance guidance, int counter) {
        return buildPayload(clear, guidance, counter, null);
    }

    private static byte[] buildPayload(
            boolean clear,
            HudGuidance guidance,
            int counter,
            byte[] icon) {
        ByteArrayOutputStream message = new ByteArrayOutputStream(256 + (icon == null ? 0 : icon.length));
        intField(message, 2, counter);
        intField(message, 16, clear ? 1 : 2);
        if (!clear && guidance != null) {
            if (guidance.getRemainingDistanceMeters() != null) {
                intField(message, 3, guidance.getRemainingDistanceMeters());
            }
            if (guidance.getRemainingTimeSeconds() != null) {
                intField(message, 4, guidance.getRemainingTimeSeconds());
            }
            bytesField(message, 8, icon);
            intField(message, 9, guidance.getManeuverDistanceMeters());
            stringField(message, 10, guidance.getNextRoadName());
            stringField(message, 26, routeDistance(guidance));
            stringField(message, 27, guidance.getRemainingTimeText());
            intField(message, 28, guidance.getManeuver().getStockId());
        }
        return embed(1, message.toByteArray());
    }

    static String routeDistance(HudGuidance guidance) {
        Integer remainingMeters = guidance.getRemainingDistanceMeters();
        return remainingMeters == null ? "" : formatDistance(remainingMeters);
    }

    private static String formatDistance(int meters) {
        if (meters < 1000) {
            return meters + " м";
        }
        if (meters < 10_000) {
            return String.format(Locale.ROOT, "%.1f км", meters / 1000.0).replace('.', ',');
        }
        return Math.round(meters / 1000.0) + " км";
    }

    static byte[] renderIcon(HudManeuver maneuver, Integer roundaboutExitNumber) {
        if (maneuver == HudManeuver.UNKNOWN) {
            return new byte[0];
        }
        final int size = 192;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(20f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        boolean mirror = shouldMirrorIcon(maneuver);
        if (mirror) {
            canvas.scale(-1f, 1f, size / 2f, size / 2f);
        }
        if (maneuver == HudManeuver.STRAIGHT) {
            canvas.drawLine(96f, 164f, 96f, 38f, paint);
            drawArrow(canvas, paint, 96f, 28f, -90f);
        } else if (maneuver == HudManeuver.U_TURN_LEFT || maneuver == HudManeuver.U_TURN_RIGHT) {
            Path path = new Path();
            path.moveTo(132f, 164f);
            path.lineTo(132f, 78f);
            path.cubicTo(132f, 34f, 58f, 34f, 58f, 78f);
            path.lineTo(58f, 116f);
            canvas.drawPath(path, paint);
            drawArrow(canvas, paint, 58f, 126f, 90f);
        } else if (maneuver == HudManeuver.ROUNDABOUT_LEFT || maneuver == HudManeuver.ROUNDABOUT_RIGHT) {
            canvas.drawArc(48f, 42f, 144f, 138f, 70f, 285f, false, paint);
            canvas.drawLine(96f, 164f, 96f, 138f, paint);
            drawPassedRoundaboutExits(canvas, paint, roundaboutExitNumber);
            canvas.drawLine(132f, 58f, 158f, 36f, paint);
            drawArrow(canvas, paint, 164f, 30f, -38f);
        } else if (maneuver == HudManeuver.SLIGHT_LEFT || maneuver == HudManeuver.SLIGHT_RIGHT) {
            Path path = new Path();
            path.moveTo(64f, 164f);
            path.lineTo(64f, 112f);
            path.cubicTo(64f, 86f, 82f, 72f, 104f, 60f);
            path.lineTo(142f, 40f);
            canvas.drawPath(path, paint);
            drawArrow(canvas, paint, 150f, 36f, -28f);
        } else {
            Path path = new Path();
            path.moveTo(56f, 164f);
            path.lineTo(56f, 94f);
            path.cubicTo(56f, 64f, 76f, 50f, 104f, 50f);
            path.lineTo(144f, 50f);
            canvas.drawPath(path, paint);
            drawArrow(canvas, paint, 154f, 50f, 0f);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    private static void drawPassedRoundaboutExits(
            Canvas canvas,
            Paint maneuverPaint,
            Integer exitNumber) {
        int passedExits = schematicPassedExitCount(exitNumber);
        if (passedExits == 0) {
            return;
        }
        Paint branchPaint = new Paint(maneuverPaint);
        branchPaint.setStrokeWidth(exitNumber != null && exitNumber > 8 ? 7f : 9f);
        final float centerX = 96f;
        final float centerY = 90f;
        final float innerRadius = 54f;
        final float outerRadius = 72f;
        final float entryAngle = 90f;
        final float targetAngle = 318f;
        int schematicExitNumber = passedExits + 1;
        for (int index = 1; index <= passedExits; index++) {
            float angle = entryAngle
                    + (targetAngle - entryAngle) * index / schematicExitNumber;
            double radians = Math.toRadians(angle);
            float cosine = (float) Math.cos(radians);
            float sine = (float) Math.sin(radians);
            canvas.drawLine(
                    centerX + innerRadius * cosine,
                    centerY + innerRadius * sine,
                    centerX + outerRadius * cosine,
                    centerY + outerRadius * sine,
                    branchPaint);
        }
    }

    static int schematicPassedExitCount(Integer exitNumber) {
        if (exitNumber == null || exitNumber <= 1) {
            return 0;
        }
        return Math.min(exitNumber, MAX_SCHEMATIC_ROUNDABOUT_EXIT) - 1;
    }

    static boolean shouldMirrorIcon(HudManeuver maneuver) {
        return maneuver == HudManeuver.LEFT
                || maneuver == HudManeuver.SLIGHT_LEFT
                || maneuver == HudManeuver.SHARP_LEFT
                || maneuver == HudManeuver.U_TURN_RIGHT
                || maneuver == HudManeuver.ROUNDABOUT_LEFT;
    }

    private static void drawArrow(Canvas canvas, Paint paint, float x, float y, float angleDegrees) {
        canvas.save();
        canvas.rotate(angleDegrees, x, y);
        Path arrow = new Path();
        arrow.moveTo(x - 28f, y - 26f);
        arrow.lineTo(x, y);
        arrow.lineTo(x - 28f, y + 26f);
        canvas.drawPath(arrow, paint);
        canvas.restore();
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
