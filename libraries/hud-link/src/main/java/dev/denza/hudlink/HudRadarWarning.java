package dev.denza.hudlink;

import java.io.*;

/** A HUD Speed observation, not HUD fields or artwork. Times use Android elapsedRealtime. */
public final class HudRadarWarning {
    public static final int VERSION = 1;
    public static final long TTL_MS = 3_000;
    public static final int MAX_BYTES = 512;
    public final long sampledAt;
    public final boolean active;
    public final int objectId, type, meters, limit, unit, averageSpeed;
    public final boolean section;
    public final String title;

    public HudRadarWarning(long sampledAt, boolean active, int objectId, int type, String title,
            int meters, int limit, int unit, boolean section, int averageSpeed) {
        if (sampledAt < 0 || title == null || title.length() > 80 ||
                hasControl(title) ||
                (active && (title.trim().isEmpty() || type < 0 || meters < 0 || meters > 100_000 ||
                        limit < 0 || limit > 400 || (unit != 1 && unit != 2) ||
                        averageSpeed < -1 || averageSpeed > 500))) {
            throw new IllegalArgumentException("invalid radar observation");
        }
        this.sampledAt = sampledAt;
        this.active = active;
        this.objectId = objectId;
        this.type = type;
        this.title = title;
        this.meters = meters;
        this.limit = limit;
        this.unit = unit;
        this.section = section;
        this.averageSpeed = averageSpeed;
    }

    private static boolean hasControl(String title) {
        if (title == null) return true;
        for (int i = 0; i < title.length(); i++) if (Character.isISOControl(title.charAt(i))) return true;
        return false;
    }

    public static HudRadarWarning clear(long now) {
        return new HudRadarWarning(now, false, 0, 0, "", 0, 0, 1, false, -1);
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
            out.writeBoolean(active);
            if (active) {
                out.writeInt(objectId); out.writeInt(type); out.writeUTF(title);
                out.writeInt(meters); out.writeInt(limit); out.writeInt(unit);
                out.writeBoolean(section); out.writeInt(averageSpeed);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static HudRadarWarning decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) return null;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != VERSION) return null;
            long at = in.readLong();
            HudRadarWarning value = in.readBoolean()
                    ? new HudRadarWarning(at, true, in.readInt(), in.readInt(), in.readUTF(),
                            in.readInt(), in.readInt(), in.readInt(), in.readBoolean(), in.readInt())
                    : clear(at);
            return in.available() == 0 ? value : null;
        } catch (IOException | IllegalArgumentException invalid) { return null; }
    }
}
