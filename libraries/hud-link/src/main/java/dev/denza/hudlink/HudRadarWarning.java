package dev.denza.hudlink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * A HUD Speed observation, not HUD fields or artwork: whether its radar runs, its GPS bars, and
 * the warning it shows, if any, with the names of HUD Speed's own drawables for it - the type's
 * tile and the limit's frame - which the host draws from the installed app. Times use Android
 * elapsedRealtime, the same in every process.
 *
 * <p>Version 2 added the radar's state, its GPS level and the drawable names; version 1, from the
 * first adapter builds, still decodes, as a running radar with no GPS level and no names.
 */
public final class HudRadarWarning {
    public static final int VERSION = 2;
    public static final long TTL_MS = 3_000;
    public static final int MAX_BYTES = 512;
    /** HUD Speed's own GPS bars, `ic_signal_1` to `_4`: off, searching, no valid fix, a fix. */
    public static final int GPS_UNKNOWN = 0;
    public static final int GPS_OFF = 1;
    public static final int GPS_SEARCHING = 2;
    public static final int GPS_NO_FIX = 3;
    public static final int GPS_FIX = 4;

    public final long sampledAt;
    /** The radar runs; when it does not, nothing of it is shown. */
    public final boolean running;
    /** {@link #GPS_UNKNOWN} to {@link #GPS_FIX}. */
    public final int gpsLevel;
    /** A warning is shown; the fields below describe it. */
    public final boolean active;
    public final int objectId, type, meters, limit, unit, averageSpeed;
    public final boolean section;
    public final String title;
    /** HUD Speed's drawable for the type, e.g. `cam_type_1`; empty when unknown. */
    public final String icon;
    /** HUD Speed's drawable for the limit's frame, e.g. `cam_speed_box_red`; empty when unknown. */
    public final String frame;

    /** An active warning without the version-2 state: a running radar, unknown GPS, no names. */
    public HudRadarWarning(long sampledAt, boolean active, int objectId, int type, String title,
            int meters, int limit, int unit, boolean section, int averageSpeed) {
        this(sampledAt, active, GPS_UNKNOWN, active, objectId, type, title, meters, limit, unit,
                section, averageSpeed, "", "");
    }

    public HudRadarWarning(long sampledAt, boolean running, int gpsLevel, boolean active,
            int objectId, int type, String title, int meters, int limit, int unit, boolean section,
            int averageSpeed, String icon, String frame) {
        if (sampledAt < 0 || title == null || title.length() > 80 || hasControl(title)
                || gpsLevel < GPS_UNKNOWN || gpsLevel > GPS_FIX
                || !resourceName(icon) || !resourceName(frame)
                || (active && (!running || title.trim().isEmpty() || type < 0 || meters < 0
                        || meters > 100_000 || limit < 0 || limit > 400 || (unit != 1 && unit != 2)
                        || averageSpeed < -1 || averageSpeed > 500))) {
            throw new IllegalArgumentException("invalid radar observation");
        }
        this.sampledAt = sampledAt;
        this.running = running;
        this.gpsLevel = running ? gpsLevel : GPS_UNKNOWN;
        this.active = active;
        this.objectId = objectId;
        this.type = type;
        this.title = title;
        this.meters = meters;
        this.limit = limit;
        this.unit = unit;
        this.section = section;
        this.averageSpeed = averageSpeed;
        this.icon = icon;
        this.frame = frame;
    }

    private static boolean hasControl(String title) {
        if (title == null) return true;
        for (int i = 0; i < title.length(); i++) if (Character.isISOControl(title.charAt(i))) return true;
        return false;
    }

    /** A drawable's entry name: at most 40 of a-z, 0-9 and underscore; empty for none. */
    private static boolean resourceName(String name) {
        if (name == null || name.length() > 40) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_')) return false;
        }
        return true;
    }

    /** The radar does not run: nothing of it is shown. */
    public static HudRadarWarning clear(long now) {
        return new HudRadarWarning(now, false, GPS_UNKNOWN, false, 0, 0, "", 0, 0, 1, false, -1, "", "");
    }

    /** The radar runs with nothing to warn of: only its GPS bars. */
    public static HudRadarWarning idle(long now, int gpsLevel) {
        return new HudRadarWarning(now, true, gpsLevel, false, 0, 0, "", 0, 0, 1, false, -1, "", "");
    }

    /** The same observation sampled again at {@code now}; only for a state that is not a warning. */
    public HudRadarWarning resampled(long now) {
        if (active) throw new IllegalStateException("a warning is never made fresh again");
        return running ? idle(now, gpsLevel) : clear(now);
    }

    /** Queuing/reconnection never makes an old observation fresh again. */
    public long remainingMs(long now) {
        return now < sampledAt || now - sampledAt >= TTL_MS ? 0 : TTL_MS - (now - sampledAt);
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(VERSION);
            out.writeLong(sampledAt);
            out.writeBoolean(running);
            out.writeByte(gpsLevel);
            out.writeBoolean(active);
            if (active) {
                out.writeInt(objectId); out.writeInt(type); out.writeUTF(title);
                out.writeInt(meters); out.writeInt(limit); out.writeInt(unit);
                out.writeBoolean(section); out.writeInt(averageSpeed);
                out.writeUTF(icon); out.writeUTF(frame);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static HudRadarWarning decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            HudRadarWarning value;
            if (version == 1) {
                long at = in.readLong();
                value = in.readBoolean()
                        ? new HudRadarWarning(at, true, in.readInt(), in.readInt(), in.readUTF(),
                                in.readInt(), in.readInt(), in.readInt(), in.readBoolean(), in.readInt())
                        : clear(at);
            } else if (version == VERSION) {
                long at = in.readLong();
                boolean running = in.readBoolean();
                int gps = in.readByte();
                value = in.readBoolean()
                        ? new HudRadarWarning(at, running, gps, true, in.readInt(), in.readInt(),
                                in.readUTF(), in.readInt(), in.readInt(), in.readInt(),
                                in.readBoolean(), in.readInt(), in.readUTF(), in.readUTF())
                        : running ? idle(at, gps) : clear(at);
            } else {
                return null;
            }
            return in.available() == 0 ? value : null;
        } catch (IOException | IllegalArgumentException invalid) { return null; }
    }
}
