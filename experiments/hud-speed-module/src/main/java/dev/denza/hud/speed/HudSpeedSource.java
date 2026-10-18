package dev.denza.hud.speed;

import dev.denza.hudlink.HudRadarWarning;
import java.lang.reflect.Method;
import java.util.List;

/** The reviewed 79.0 model only. Snapshot on the event's thread, before queuing any IPC. */
public final class HudSpeedSource {
    public interface Labels {
        String title(int type) throws Exception;
        int distance(double lat, double lon, double carLat, double carLon) throws Exception;
    }

    public static HudRadarWarning read(Object service, Labels labels, long now) throws Exception {
        Object radar = field(service, "c");
        // w: radar running; h(): GPS_ON with a valid fix. Lists can outlive a GPS fix.
        if (!Boolean.TRUE.equals(field(radar, "w")) || !Boolean.TRUE.equals(call(radar, "h"))) {
            return HudRadarWarning.clear(now);
        }
        List<?> active = (List<?>) call(radar, "f");
        Object position = field(radar, "n");
        Object object;
        boolean section = active.isEmpty() && Boolean.TRUE.equals(field(radar, "G"));
        if (!active.isEmpty()) object = active.get(0); // Same ordered choice as l.c.f().
        else if (section) object = field(radar, "H");
        else return HudRadarWarning.clear(now);
        if (object == null) return HudRadarWarning.clear(now);
        // S is the vehicle category, NOT a speed unit. The camera owns its unit (u()).
        byte vehicle = ((Number) call(field(service, "d"), "S")).byteValue();
        int type = number(call(object, "v"));
        int unit = number(call(object, "u"));
        int meters = section ? number(field(radar, "J")) : labels.distance(
                decimal(call(object, "k")), decimal(call(object, "l")),
                decimal(field(position, "d")), decimal(field(position, "e")));
        int limit = number(object.getClass().getMethod("s", byte.class).invoke(object, vehicle));
        int average = section ? Math.round((float) decimal(call(position, "a")) * (unit == 2 ? 2.237f : 3.6f)) : -1;
        return new HudRadarWarning(now, true, number(call(object, "j")), type, labels.title(type),
                meters, Math.max(0, limit), unit, section, average);
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
