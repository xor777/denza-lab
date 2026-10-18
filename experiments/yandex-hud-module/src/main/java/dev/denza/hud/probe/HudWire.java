package dev.denza.hud.probe;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Same protobuf envelopes as Denza Apps HudSomeIpClient, the hint fields in its order. No vehicle
 * setters: the car's own speed sign needs a BYDAUTO permission this process does not hold.
 */
final class HudWire {
    static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";
    static final long SERVICE = 3097367205183488L;
    static final long ROAD = 1127042368241665L, MAP = 1127042368241667L;

    static byte[] road(boolean active) { return road(active, null, null); }

    /** Road packet 0x8001: navigating (16 = 2) with whatever of the hint is known, or the clear (16 = 1). */
    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon) {
        return road(active, hint, icon, 0, null);
    }

    /** One complete road snapshot: lanes do not depend on a manoeuvre being available. */
    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon, int laneCount, byte[] lanes) {
        return road(active, hint, icon, laneCount, lanes, null);
    }

    static byte[] road(boolean active, NavigatorSource.Hint hint, byte[] icon, int laneCount, byte[] lanes, HudAr.Frame ar) {
        boolean hasLanes = active && laneCount > 0 && laneCount <= 6 && lanes != null && lanes.length > 0;
        ByteArrayOutputStream body = new ByteArrayOutputStream(256 + (icon == null ? 0 : icon.length)
                + (hasLanes ? lanes.length : 0));
        number(body, 2, 2);
        number(body, 16, active ? 2 : 1);
        number(body, 5, hasLanes ? laneCount : 0); // Explicit clear while the route stays active, too.
        if (hasLanes) bytes(body, 7, lanes);
        if (active && hint != null) {
            if (hint.remainingMeters >= 0) number(body, 3, hint.remainingMeters);
            if (hint.remainingSeconds >= 0) number(body, 4, hint.remainingSeconds);
            if (hint.icon != null) {
                bytes(body, 8, icon);
                number(body, 9, Math.max(0, hint.distance));
                text(body, 10, hint.road);
            }
            if (hint.speedLimit > 0) number(body, 11, hint.speedLimit); // currentMaxSpeedLimit, km/h
            if (hint.remainingMeters >= 0) text(body, 26, distance(hint.remainingMeters));
            if (hint.remainingSeconds >= 0) text(body, 27, duration(hint.remainingSeconds));
            if (hint.icon != null) number(body, 28, hint.stockId);
            if (ar != null) {
                decimal(body, 19, ar.pose.point.lon);
                decimal(body, 20, ar.pose.point.lat);
                number(body, 21, Math.round(ar.pose.speed)); // m/s, unlike the road speed-limit field.
                number(body, 22, Math.round(ar.pose.altitude));
                text(body, 30, ar.lineJson());
                text(body, 31, ar.target.tuple());
                decimal(body, 32, ar.pose.heading);
                decimal(body, 33, ar.progress);
            }
        }
        return field(1, body.toByteArray());
    }

    static byte[] map(byte[] png) { return field(1, Base64.getEncoder().encode(png)); }

    /** The route summary as Denza Apps writes it: metres below 1 km, one decimal below 10 km. */
    static String distance(int meters) {
        if (meters < 1000) return meters + " м";
        if (meters < 10_000) return String.format(Locale.ROOT, "%.1f км", meters / 1000.0).replace('.', ',');
        return Math.round(meters / 1000.0) + " км";
    }

    /** Remaining time as Yandex prints it: «17 мин», «1 ч», «1 ч 5 мин». */
    static String duration(int seconds) {
        int minutes = Math.max(1, Math.round(seconds / 60f));
        if (minutes < 60) return minutes + " мин";
        return minutes % 60 == 0 ? minutes / 60 + " ч" : minutes / 60 + " ч " + minutes % 60 + " мин";
    }

    private static void number(ByteArrayOutputStream out, int field, long value) { varint(out, field << 3); varint(out, value); }

    private static void decimal(ByteArrayOutputStream out, int field, double value) {
        varint(out, (field << 3) | 1);
        long bits = Double.doubleToLongBits(value);
        for (int i = 0; i < 8; i++) { out.write((int) (bits & 255)); bits >>>= 8; }
    }

    private static void text(ByteArrayOutputStream out, int field, String value) {
        if (value != null && !value.isEmpty()) bytes(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(ByteArrayOutputStream out, int field, byte[] value) {
        if (value == null || value.length == 0) return;
        varint(out, (field << 3) | 2); varint(out, value.length); out.write(value, 0, value.length);
    }

    private static byte[] field(int field, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length + 8);
        varint(out, (field << 3) | 2); varint(out, value.length); out.write(value, 0, value.length);
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7fL) != 0) { out.write((int) (value & 0x7fL) | 0x80); value >>>= 7; }
        out.write((int) value);
    }
}
