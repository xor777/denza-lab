package dev.denza.hud.speed;

import dev.denza.hudlink.HudRadarWarning;
import java.lang.reflect.Method;
import java.util.List;

/**
 * The reviewed 79.0 model only. Snapshot on the event's thread, before queuing any IPC: whether the
 * radar runs, its GPS bars, and the warning its own overlay would show, with the names of the
 * app's drawables for it.
 */
public final class HudSpeedSource {
    public interface Labels {
        String title(int type) throws Exception;
        int distance(double lat, double lon, double carLat, double carLon) throws Exception;
        /**
         * The type's drawables as the app's own table names them (g.i.w(type)): the tile, the
         * limit's frame and the frame without a limit; null for a type the table lacks.
         */
        String[] art(int type) throws Exception;
    }

    public static HudRadarWarning read(Object service, Labels labels, long now) throws Exception {
        Object radar = field(service, "c");
        // w: radar running; h(): GPS_ON with a valid fix. Lists can outlive a GPS fix.
        if (!Boolean.TRUE.equals(field(radar, "w"))) {
            return HudRadarWarning.clear(now);
        }
        boolean fix = Boolean.TRUE.equals(call(radar, "h"));
        int gps = gpsLevel(radar, fix);
        if (!fix) {
            return HudRadarWarning.idle(now, gps);
        }
        List<?> active = (List<?>) call(radar, "f");
        Object position = field(radar, "n");
        Object object;
        boolean section = active.isEmpty() && Boolean.TRUE.equals(field(radar, "G"));
        if (!active.isEmpty()) object = active.get(0); // Same ordered choice as l.c.f().
        else if (section) object = field(radar, "H");
        else return HudRadarWarning.idle(now, gps);
        if (object == null) return HudRadarWarning.idle(now, gps);
        // S is the vehicle category, NOT a speed unit. The camera owns its unit (u()).
        byte vehicle = ((Number) call(field(service, "d"), "S")).byteValue();
        int type = number(call(object, "v"));
        int unit = number(call(object, "u"));
        int meters = section ? number(field(radar, "J")) : labels.distance(
                decimal(call(object, "k")), decimal(call(object, "l")),
                decimal(field(position, "d")), decimal(field(position, "e")));
        int limit = number(object.getClass().getMethod("s", byte.class).invoke(object, vehicle));
        int average = section ? Math.round((float) decimal(call(position, "a")) * (unit == 2 ? 2.237f : 3.6f)) : -1;
        String[] art = labels.art(type);
        String icon = art == null ? "" : name(art[0]);
        String frame = art == null ? "" : name(limit > 0 ? art[1] : art[2]);
        return new HudRadarWarning(now, true, gps, true, number(call(object, "j")), type, labels.title(type),
                meters, Math.max(0, limit), unit, section, average, icon, frame);
    }

    /**
     * The GPS bars the app's own overlay shows (l.c.f, ic_signal_1 to _4): GPS off, searching,
     * on without a valid fix, a fix. z is the engine's GPS state string.
     */
    static int gpsLevel(Object radar, boolean fix) {
        Object state;
        try {
            state = field(radar, "z");
        } catch (Exception unknown) {
            return HudRadarWarning.GPS_UNKNOWN;
        }
        if ("GPS_OFF".equals(state)) return HudRadarWarning.GPS_OFF;
        if (!"GPS_ON".equals(state)) return HudRadarWarning.GPS_SEARCHING;
        return fix ? HudRadarWarning.GPS_FIX : HudRadarWarning.GPS_NO_FIX;
    }

    /** A drawable name as the host accepts it, or empty. */
    private static String name(String value) {
        return value != null && value.matches("[a-z0-9_]{1,40}") ? value : "";
    }

    private static Object field(Object owner, String name) throws Exception {
        return owner.getClass().getField(name).get(owner);
    }

    private static Object call(Object owner, String name) throws Exception {
        Method method = owner.getClass().getMethod(name);
        return method.invoke(owner);
    }

    private static int number(Object value) { return ((Number) value).intValue(); }
    private static double decimal(Object value) { return ((Number) value).doubleValue(); }
}
