package dev.denza.hudlink;

import java.util.Base64;
import java.util.Locale;

/**
 * The stock SOME/IP endpoint the HUD listens on, its two topics and the road packet's fields, as
 * the stock navigator fills them (docs/hud-projection-findings.md, docs/instrument-display-findings.md
 * "The stock road packet field by field").
 */
public final class HudProtocol {
    public static final String PACKAGE = "com.ts.car.someip.service";
    public static final String SERVICE_CLASS = "com.ts.car.someip.service.manager.SomeIpServerService";
    public static final String ACTION = "com.ts.car.someip.SomeIpServerService";
    public static final String DESCRIPTOR = "ts.car.someip.sdk.ISomeIpServerInterface";

    /** Binder transactions: start and stop a service session, fire an event. */
    public static final int TX_START = 4;
    public static final int TX_STOP = 5;
    public static final int TX_FIRE = 6;

    /** The HUD navigation service, IVI service 266. */
    public static final long SERVICE_HUD_NAVI = 3097367205183488L;
    /** Event 0x8001, the road packet: field 1 of an outer message. */
    public static final long TOPIC_ROAD = 1127042368241665L;
    /** Event 0x8003, {@code HudNavigationmap{1: Base64 PNG}}: the map window beside the speed. */
    public static final long TOPIC_MAP = 1127042368241667L;

    /** Fields of the road packet. */
    public static final class Road {
        public static final int ENVELOPE = 1;
        /** {@code 2} as the stock navigator sends it; {@code 1} only under its own junction picture. */
        public static final int COUNTER = 2;
        public static final int REMAINING_METERS = 3;
        public static final int REMAINING_SECONDS = 4;
        public static final int LANE_COUNT = 5;
        public static final int LANES_PNG = 7;
        public static final int MANEUVER_PNG = 8;
        public static final int MANEUVER_METERS = 9;
        public static final int ROAD_NAME = 10;
        /** currentMaxSpeedLimit, km/h. */
        public static final int SPEED_LIMIT = 11;
        /** {@link #NAVIGATING} or {@link #OFF}; the map window shows only while navigating. */
        public static final int STATE = 16;
        public static final int LONGITUDE = 19;
        public static final int LATITUDE = 20;
        /** m/s, unlike the speed limit. */
        public static final int SPEED = 21;
        public static final int ALTITUDE = 22;
        public static final int SUMMARY_DISTANCE = 26;
        public static final int SUMMARY_TIME = 27;
        public static final int STOCK_MANEUVER = 28;
        public static final int AR_LINE = 30;
        public static final int AR_POINT = 31;
        public static final int HEADING = 32;
        public static final int PROGRESS = 33;

        public static final int STOCK_COUNTER = 2;
        public static final int NAVIGATING = 2;
        public static final int OFF = 1;

        private Road() {
        }
    }

    private HudProtocol() {
    }

    /**
     * {@code HudNavigationmap{1: Base64(png)}} exactly as the stock navigator sends it: field 1 as
     * a string, Base64 without line breaks, and no envelope around the message.
     */
    public static byte[] mapPayload(byte[] png) {
        byte[] text = Base64.getEncoder().encode(png);
        return new HudMessage(text.length + 8).bytes(1, text).toByteArray();
    }

    /** The route summary's distance: metres below 1 km, one decimal below 10 km, «12 км» above. */
    public static String routeDistance(int meters) {
        if (meters < 1000) {
            return meters + " м";
        }
        if (meters < 10_000) {
            return String.format(Locale.ROOT, "%.1f км", meters / 1000.0).replace('.', ',');
        }
        return Math.round(meters / 1000.0) + " км";
    }

    /** Remaining time as Yandex prints it: «17 мин», «1 ч», «1 ч 5 мин». */
    public static String duration(int seconds) {
        int minutes = Math.max(1, Math.round(seconds / 60f));
        if (minutes < 60) {
            return minutes + " мин";
        }
        return minutes % 60 == 0 ? minutes / 60 + " ч" : minutes / 60 + " ч " + minutes % 60 + " мин";
    }
}
