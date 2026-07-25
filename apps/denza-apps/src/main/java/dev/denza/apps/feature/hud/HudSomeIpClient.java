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
import android.os.SystemClock;
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
    private static final long RECOVERY_DELAY_MS = 1000L;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int MAX_SCHEMATIC_ROUNDABOUT_EXIT = 12;
    private final Map<String, byte[]> iconCache = new HashMap<>();
    private final Runnable shutdownRunnable = this::stopAndUnbind;
    private final Runnable recoveryRunnable = () -> {
        recoveryScheduled = false;
        recoverConnection();
    };
    private IBinder binder;
    private boolean bound;
    private boolean serviceStarted;
    private boolean recoveryScheduled;
    private HudGuidance pending;
    private int counter;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            cancelRecovery();
            binder = service;
            Log.i(TAG, "connected " + name.flattenToShortString());
            if (pending != null) {
                startSessionAndPublishPending();
            } else {
                HudSomeIpRuntime.onIdle();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            serviceStarted = false;
            Log.w(TAG, "disconnected " + name.flattenToShortString());
            scheduleRecovery("Штатный сервис HUD отключился");
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "binding died " + name.flattenToShortString());
            resetBinding();
            scheduleRecovery("Привязка к штатному HUD потеряна");
        }
    };

    HudSomeIpClient(Context context) {
        this.context = context.getApplicationContext();
    }

    void publish(HudGuidance guidance) {
        handler.removeCallbacks(shutdownRunnable);
        pending = guidance;
        ensureConnected();
        if (binder != null && !serviceStarted) {
            startSessionAndPublishPending();
        } else if (serviceStarted) {
            fireGuidance(guidance);
        }
    }

    void clear() {
        pending = null;
        cancelRecovery();
        if (serviceStarted) {
            int result = fire(TOPIC_HUD_ROAD, buildPayload(true, null, ++counter));
            HudSomeIpRuntime.onFireResult(result);
            Log.i(TAG, "clear ret=" + result);
        }
        HudSomeIpRuntime.onIdle();
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
            if (binder == null && pending != null) {
                scheduleRecovery("Ожидаю переподключение штатного HUD");
            }
            return;
        }
        HudSomeIpRuntime.onBinding();
        Intent intent = new Intent(ACTION);
        intent.setComponent(new ComponentName(PACKAGE, SERVICE));
        intent.setType(context.getPackageName());
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bind=" + bound);
            if (!bound) {
                scheduleRecovery("Штатный сервис HUD не принял подключение");
            }
        } catch (RuntimeException error) {
            bound = false;
            Log.e(TAG, "bind failed", error);
            scheduleRecovery("Ошибка подключения к штатному HUD");
        }
    }

    private int startSomeIpService() {
        IBinder service = binder;
        if (service == null) {
            return -100;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeLong(SVC_HUD_NAVI);
            service.transact(TX_START, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } catch (RuntimeException | RemoteException error) {
            Log.e(TAG, "start failed", error);
            return -200;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void startSessionAndPublishPending() {
        HudSomeIpRuntime.onStarting();
        int result = startSomeIpService();
        HudSomeIpRuntime.onStartResult(result);
        serviceStarted = result == 0;
        Log.i(TAG, "start ret=" + result);
        if (!serviceStarted) {
            scheduleRecovery("Не удалось запустить сессию HUD: " + result);
            return;
        }
        HudGuidance guidance = pending;
        if (guidance != null) {
            fireGuidance(guidance);
        }
    }

    private void fireGuidance(HudGuidance guidance) {
        byte[] icon = HudNotificationArtworkRuntime.resolve(
                guidance,
                SystemClock.uptimeMillis());
        if (icon == null) {
            String iconKey = guidance.getManeuver().name()
                    + ":" + guidance.getRoundaboutExitNumber();
            icon = iconCache.get(iconKey);
            if (icon == null) {
                icon = renderIcon(guidance.getManeuver(), guidance.getRoundaboutExitNumber());
                iconCache.put(iconKey, icon);
            }
        }
        int result = fire(TOPIC_HUD_ROAD, buildPayload(false, guidance, ++counter, icon));
        HudSomeIpRuntime.onFireResult(result);
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
            serviceStarted = false;
            scheduleRecovery("Штатный HUD отклонил подсказку: " + result);
        }
    }

    private void scheduleRecovery(String reason) {
        if (pending == null) {
            return;
        }
        if (recoveryScheduled) {
            return;
        }
        HudSomeIpRuntime.onRecovering(reason);
        recoveryScheduled = true;
        handler.postDelayed(recoveryRunnable, RECOVERY_DELAY_MS);
    }

    private void cancelRecovery() {
        handler.removeCallbacks(recoveryRunnable);
        recoveryScheduled = false;
    }

    private void recoverConnection() {
        if (pending == null) {
            return;
        }
        resetBinding();
        ensureConnected();
    }

    private void resetBinding() {
        IBinder oldBinder = binder;
        boolean wasBound = bound;
        binder = null;
        serviceStarted = false;
        bound = false;
        if (wasBound || oldBinder != null) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException error) {
                Log.w(TAG, "reset unbind failed", error);
            }
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
        cancelRecovery();
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
        HudSomeIpRuntime.onIdle("Сессия HUD остановлена");
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
            canvas.drawCircle(96f, 90f, 48f, paint);
            canvas.drawLine(96f, 164f, 96f, 138f, paint);
            drawPassedRoundaboutExits(canvas, paint, roundaboutExitNumber);
            drawRoundaboutTargetExit(canvas, paint, roundaboutExitNumber);
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
        final float innerRadius = 52f;
        final float outerRadius = 72f;
        int schematicExitNumber = passedExits + 1;
        for (int index = 1; index <= passedExits; index++) {
            float angle = schematicRoundaboutExitAngle(schematicExitNumber, index);
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

    private static void drawRoundaboutTargetExit(
            Canvas canvas,
            Paint maneuverPaint,
            Integer exitNumber) {
        int schematicExitNumber = exitNumber == null || exitNumber < 1
                ? 0
                : Math.min(exitNumber, MAX_SCHEMATIC_ROUNDABOUT_EXIT);
        float angle = schematicExitNumber == 0
                ? -42f
                : schematicRoundaboutExitAngle(schematicExitNumber, schematicExitNumber);
        double radians = Math.toRadians(angle);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        final float centerX = 96f;
        final float centerY = 90f;
        final float innerRadius = 48f;
        final float shaftRadius = 81f;
        final float tipRadius = 89f;
        Paint targetExitPaint = new Paint(maneuverPaint);
        targetExitPaint.setStrokeWidth(15f);
        canvas.drawLine(
                centerX + innerRadius * cosine,
                centerY + innerRadius * sine,
                centerX + shaftRadius * cosine,
                centerY + shaftRadius * sine,
                targetExitPaint);
        drawArrow(
                canvas,
                targetExitPaint,
                centerX + tipRadius * cosine,
                centerY + tipRadius * sine,
                angle,
                22f,
                19f);
    }

    static int schematicPassedExitCount(Integer exitNumber) {
        if (exitNumber == null || exitNumber <= 1) {
            return 0;
        }
        return Math.min(exitNumber, MAX_SCHEMATIC_ROUNDABOUT_EXIT) - 1;
    }

    static float schematicRoundaboutExitAngle(int exitNumber, int exitIndex) {
        if (exitNumber <= 0 || exitIndex <= 0 || exitIndex > exitNumber) {
            return -42f;
        }
        if (exitNumber <= 4) {
            final float[] conventionalAngles = {0f, -90f, -180f, -225f};
            return conventionalAngles[exitIndex - 1];
        }
        int boundedExitNumber = Math.min(exitNumber, MAX_SCHEMATIC_ROUNDABOUT_EXIT);
        int boundedExitIndex = Math.min(exitIndex, boundedExitNumber);
        return -245f * (boundedExitIndex - 1) / (boundedExitNumber - 1);
    }

    static boolean shouldMirrorIcon(HudManeuver maneuver) {
        return maneuver == HudManeuver.LEFT
                || maneuver == HudManeuver.SLIGHT_LEFT
                || maneuver == HudManeuver.SHARP_LEFT
                || maneuver == HudManeuver.U_TURN_RIGHT
                || maneuver == HudManeuver.ROUNDABOUT_LEFT;
    }

    private static void drawArrow(Canvas canvas, Paint paint, float x, float y, float angleDegrees) {
        drawArrow(canvas, paint, x, y, angleDegrees, 28f, 26f);
    }

    private static void drawArrow(
            Canvas canvas,
            Paint paint,
            float x,
            float y,
            float angleDegrees,
            float length,
            float halfWidth) {
        canvas.save();
        canvas.rotate(angleDegrees, x, y);
        Path arrow = new Path();
        arrow.moveTo(x - length, y - halfWidth);
        arrow.lineTo(x, y);
        arrow.lineTo(x - length, y + halfWidth);
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
