package dev.denza.apps.feature.hud;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.io.ByteArrayOutputStream;

/**
 * The picture of the road packet's field 8 for a manoeuvre read from Yandex: a white arrow on
 * transparent, {@value #ICON_SIZE} pixels square, drawn here when Yandex's notification gives no
 * artwork of its own ({@link HudNotificationArtworkRuntime}).
 */
final class HudManeuverIcon {
    private static final int MAX_SCHEMATIC_ROUNDABOUT_EXIT = 12;
    static final int ICON_SIZE = 192;
    private static final float ROUNDABOUT_CENTER_X = 96f;
    private static final float ROUNDABOUT_CENTER_Y = 90f;
    private static final float ROUNDABOUT_TIP_RADIUS = 89f;

    private HudManeuverIcon() {
    }

    static byte[] render(HudManeuver maneuver, Integer roundaboutExitNumber) {
        float[] tip = baseArrowTip(maneuver, roundaboutExitNumber);
        if (tip == null) {
            return new byte[0];
        }
        Bitmap bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(20f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        if (shouldMirrorIcon(maneuver)) {
            canvas.scale(-1f, 1f, ICON_SIZE / 2f, ICON_SIZE / 2f);
        }
        if (maneuver == HudManeuver.STRAIGHT) {
            canvas.drawLine(96f, 164f, 96f, 38f, paint);
            drawArrow(canvas, paint, tip[0], tip[1], -90f);
        } else if (maneuver == HudManeuver.U_TURN_LEFT || maneuver == HudManeuver.U_TURN_RIGHT) {
            Path path = new Path();
            path.moveTo(132f, 164f);
            path.lineTo(132f, 78f);
            path.cubicTo(132f, 34f, 58f, 34f, 58f, 78f);
            path.lineTo(58f, 116f);
            canvas.drawPath(path, paint);
            drawArrow(canvas, paint, tip[0], tip[1], 90f);
        } else if (maneuver == HudManeuver.ROUNDABOUT) {
            canvas.drawCircle(ROUNDABOUT_CENTER_X, ROUNDABOUT_CENTER_Y, 48f, paint);
            canvas.drawLine(96f, 164f, 96f, 138f, paint);
            drawPassedRoundaboutExits(canvas, paint, roundaboutExitNumber);
            drawRoundaboutTargetExit(canvas, paint, roundaboutExitNumber, tip);
        } else if (maneuver == HudManeuver.SLIGHT_LEFT || maneuver == HudManeuver.SLIGHT_RIGHT) {
            Path path = new Path();
            path.moveTo(64f, 164f);
            path.lineTo(64f, 112f);
            path.cubicTo(64f, 86f, 82f, 72f, 104f, 60f);
            path.lineTo(142f, 40f);
            canvas.drawPath(path, paint);
            drawArrow(canvas, paint, tip[0], tip[1], -28f);
        } else {
            Path path = new Path();
            path.moveTo(56f, 164f);
            path.lineTo(56f, 94f);
            path.cubicTo(56f, 64f, 76f, 50f, 104f, 50f);
            path.lineTo(144f, 50f);
            canvas.drawPath(path, paint);
            drawArrow(canvas, paint, tip[0], tip[1], 0f);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    /**
     * Where the tip of the maneuver arrow lands on the finished icon, as {x, y} in icon pixels with
     * the origin at the top left, after the mirroring {@link #render} applies; null when the
     * icon has no arrow. {@link #render} draws every arrow at {@link #baseArrowTip}, so the side
     * this reports is the side the driver sees.
     */
    static float[] arrowTip(HudManeuver maneuver, Integer roundaboutExitNumber) {
        float[] tip = baseArrowTip(maneuver, roundaboutExitNumber);
        if (tip != null && shouldMirrorIcon(maneuver)) {
            tip[0] = ICON_SIZE - tip[0];
        }
        return tip;
    }

    /** The arrow tip in drawing coordinates, before the canvas is mirrored. */
    private static float[] baseArrowTip(HudManeuver maneuver, Integer roundaboutExitNumber) {
        switch (maneuver) {
            case UNKNOWN:
                return null;
            case STRAIGHT:
                return new float[] {96f, 28f};
            case U_TURN_LEFT:
            case U_TURN_RIGHT:
                return new float[] {58f, 126f};
            case SLIGHT_LEFT:
            case SLIGHT_RIGHT:
                return new float[] {150f, 36f};
            case ROUNDABOUT:
                return roundaboutPoint(
                        roundaboutTargetExitAngle(roundaboutExitNumber),
                        ROUNDABOUT_TIP_RADIUS);
            default:
                return new float[] {154f, 50f};
        }
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
        int schematicExitNumber = passedExits + 1;
        for (int index = 1; index <= passedExits; index++) {
            float angle = schematicRoundaboutExitAngle(schematicExitNumber, index);
            float[] inner = roundaboutPoint(angle, 52f);
            float[] outer = roundaboutPoint(angle, 72f);
            canvas.drawLine(inner[0], inner[1], outer[0], outer[1], branchPaint);
        }
    }

    private static void drawRoundaboutTargetExit(
            Canvas canvas,
            Paint maneuverPaint,
            Integer exitNumber,
            float[] tip) {
        float angle = roundaboutTargetExitAngle(exitNumber);
        float[] base = roundaboutPoint(angle, 48f);
        float[] shaft = roundaboutPoint(angle, 81f);
        Paint targetExitPaint = new Paint(maneuverPaint);
        targetExitPaint.setStrokeWidth(15f);
        canvas.drawLine(base[0], base[1], shaft[0], shaft[1], targetExitPaint);
        drawArrow(canvas, targetExitPaint, tip[0], tip[1], angle, 22f, 19f);
    }

    /** The target exit's direction on the schematic, in canvas degrees (0 = right, -90 = up). */
    private static float roundaboutTargetExitAngle(Integer exitNumber) {
        int schematicExitNumber = exitNumber == null || exitNumber < 1
                ? 0
                : Math.min(exitNumber, MAX_SCHEMATIC_ROUNDABOUT_EXIT);
        return schematicExitNumber == 0
                ? -42f
                : schematicRoundaboutExitAngle(schematicExitNumber, schematicExitNumber);
    }

    private static float[] roundaboutPoint(float angleDegrees, float radius) {
        double radians = Math.toRadians(angleDegrees);
        return new float[] {
                ROUNDABOUT_CENTER_X + radius * (float) Math.cos(radians),
                ROUNDABOUT_CENTER_Y + radius * (float) Math.sin(radians),
        };
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

    /**
     * The turn and slight artwork is drawn to the right and the U-turn to the left, so their other
     * side is the mirror image. The roundabout is not: it is drawn counter-clockwise, exit 1 to the
     * right, 2 ahead, 3 to the left, which is the only way round a roundabout turns here.
     */
    private static boolean shouldMirrorIcon(HudManeuver maneuver) {
        return maneuver == HudManeuver.LEFT
                || maneuver == HudManeuver.SLIGHT_LEFT
                || maneuver == HudManeuver.SHARP_LEFT
                || maneuver == HudManeuver.U_TURN_RIGHT;
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
}
