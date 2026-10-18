package dev.denza.hudsurfaces.probe;

import dev.denza.hudlink.HudMessage;
import dev.denza.hudlink.HudProtocol;
import dev.denza.hudlink.HudProtocol.Road;

/**
 * The probe's road and map packets. Every road packet carries field 2 = 2 and field 16 first,
 * then the fields its scenario needs in ascending order; anything not written decodes as zero or
 * empty, which is how the stock navigator's protobuf-lite encoder leaves default values off the
 * wire too. Field numbers and stock values are cited in this module's README.
 */
final class SurfacePackets {
    // Fields of HudRoadInfoNotifyStruct the shared HudProtocol.Road does not name yet; numbers
    // from the stock schema (SomeipHudNaviInfoService.java:811-839), all uint32.
    /** {@code distance2SpeedLimitZone}: metres already driven in an average-speed section. */
    static final int SECTION_DRIVEN = 13;
    /** {@code lengthOfSpeedLimit}: the section's length in metres. */
    static final int SECTION_LENGTH = 14;
    /** {@code speedLimit}: the section's limit, km/h. */
    static final int SECTION_LIMIT = 15;
    /** {@code cameraAheadStatus}: the stock sender writes only 1 (a camera ahead) or 0. */
    static final int CAMERA_AHEAD = 17;
    /** {@code theDistance2Camera}: metres to the nearest camera. */
    static final int CAMERA_DISTANCE = 18;
    /** {@code dangerSigns}: an AutoNavi road-facility type. */
    static final int DANGER_SIGN = 23;

    static final int CAMERA_PRESENT = 1;
    static final int CAMERA_START_M = 300;
    static final int CAMERA_STEP_M = 10;
    static final int CAMERA_MIN_M = 20;
    static final int LIMIT_KMH = 60;
    static final int SECTION_LENGTH_M = 2000;
    static final int SECTION_DRIVEN_START_M = 200;
    static final int SECTION_STEP_M = 20;
    /** AutoNavi {@code FacilityTypeAttentionDanger}: the generic "danger ahead" facility. */
    static final int DANGER_ATTENTION = 30;
    /** Field 28 for AutoNavi "continue" (the stock table maps icon 9 to 11). */
    static final int MANEUVER_STRAIGHT = 11;

    /** The route of scenario 2: an ordinary manoeuvre block, as Denza Apps fills it. */
    static final int ROUTE_MANEUVER_M = 800;
    static final String ROUTE_ROAD = "ПРОБА";
    static final int ROUTE_REMAINING_M = 5000;
    static final int ROUTE_REMAINING_S = 600;

    /** The four stretches of scenarios 1 and 2, one stock camera input each. */
    enum CameraPhase {
        /** 17 = 1, 18 = metres, as the stock sender reports the nearest camera. */
        CAMERA,
        /** The same and 11 = 60: the stock sender folds a camera's limit into field 11. */
        CAMERA_LIMIT,
        /** 13 / 14 / 15: an average-speed section, without the point camera. */
        SECTION,
        /** 23 = 30: a road-facility warning alone. */
        DANGER,
    }

    private SurfacePackets() {
    }

    /** Metres to the camera on tick {@code tick} of a phase: 300, 290, ... never below 20. */
    static int cameraMeters(int tick) {
        return Math.max(CAMERA_MIN_M, CAMERA_START_M - CAMERA_STEP_M * tick);
    }

    /** Metres driven in the section on tick {@code tick}: 200, 220, ... never past its length. */
    static int sectionDriven(int tick) {
        return Math.min(SECTION_LENGTH_M, SECTION_DRIVEN_START_M + SECTION_STEP_M * tick);
    }

    /**
     * Scenarios 1 and 2: the camera inputs of {@code phase}. Navigating (16 = 2) also fills the
     * manoeuvre block with {@code arrowPng} so the HUD has no empty block to caption; not
     * navigating (16 = 1) writes nothing of the manoeuvre block.
     */
    static byte[] cameraFields(boolean navigating, CameraPhase phase, int tick, byte[] arrowPng) {
        if (navigating && (arrowPng == null || arrowPng.length == 0)) {
            throw new IllegalArgumentException("A navigating packet needs its manoeuvre picture");
        }
        HudMessage message = head(navigating);
        if (navigating) {
            message.bytes(Road.MANEUVER_PNG, arrowPng)
                    .number(Road.MANEUVER_METERS, ROUTE_MANEUVER_M)
                    .text(Road.ROAD_NAME, ROUTE_ROAD);
        }
        if (phase == CameraPhase.CAMERA_LIMIT) {
            message.number(Road.SPEED_LIMIT, LIMIT_KMH);
        }
        if (phase == CameraPhase.SECTION) {
            message.number(SECTION_DRIVEN, sectionDriven(tick))
                    .number(SECTION_LENGTH, SECTION_LENGTH_M)
                    .number(SECTION_LIMIT, LIMIT_KMH);
        }
        if (phase == CameraPhase.CAMERA || phase == CameraPhase.CAMERA_LIMIT) {
            message.number(CAMERA_AHEAD, CAMERA_PRESENT)
                    .number(CAMERA_DISTANCE, cameraMeters(tick));
        }
        if (phase == CameraPhase.DANGER) {
            message.number(DANGER_SIGN, DANGER_ATTENTION);
        }
        if (navigating) {
            message.text(Road.SUMMARY_DISTANCE, HudProtocol.routeDistance(ROUTE_REMAINING_M))
                    .text(Road.SUMMARY_TIME, HudProtocol.duration(ROUTE_REMAINING_S))
                    .number(Road.STOCK_MANEUVER, MANEUVER_STRAIGHT);
        }
        return message.embedded(Road.ENVELOPE);
    }

    /**
     * Scenario 3: navigation on for the warning only, the manoeuvre block filled by the warning
     * itself. 8 = the camera picture, 9 = metres to the camera, 10 = the limit as text, 28 =
     * straight; 26 and 27 stay empty and no stock camera field is written.
     */
    static byte[] warningManeuver(byte[] cameraPng, int meters, String limitText) {
        if (cameraPng == null || cameraPng.length == 0) {
            throw new IllegalArgumentException("The warning needs its camera picture");
        }
        return head(true)
                .bytes(Road.MANEUVER_PNG, cameraPng)
                .number(Road.MANEUVER_METERS, meters)
                .text(Road.ROAD_NAME, limitText)
                .number(Road.STOCK_MANEUVER, MANEUVER_STRAIGHT)
                .embedded(Road.ENVELOPE);
    }

    /**
     * Scenario 4: one session's navigating packet. Every field the owner can read names the
     * session: its letter in the picture and the street, A's 100 m / 1,0 км / 1 мин, B's
     * 200 m / 2,0 км / 2 мин.
     */
    static byte[] sessionManeuver(SessionId session, byte[] letterPng) {
        if (letterPng == null || letterPng.length == 0) {
            throw new IllegalArgumentException("The session needs its picture");
        }
        int index = session.ordinal() + 1;
        return head(true)
                .bytes(Road.MANEUVER_PNG, letterPng)
                .number(Road.MANEUVER_METERS, 100 * index)
                .text(Road.ROAD_NAME, "СЕССИЯ " + session.name())
                .text(Road.SUMMARY_DISTANCE, HudProtocol.routeDistance(1000 * index))
                .text(Road.SUMMARY_TIME, HudProtocol.duration(60 * index))
                .number(Road.STOCK_MANEUVER, MANEUVER_STRAIGHT)
                .embedded(Road.ENVELOPE);
    }

    /** The clear: navigation off and nothing else, so every camera field reads 0 again. */
    static byte[] clear() {
        return head(false).embedded(Road.ENVELOPE);
    }

    /** {@code HudNavigationmap{1: Base64 PNG}}, as the stock navigator sends it. */
    static byte[] map(byte[] png) {
        if (png == null || png.length == 0) {
            throw new IllegalArgumentException("A map frame needs a picture");
        }
        return HudProtocol.mapPayload(png);
    }

    private static HudMessage head(boolean navigating) {
        return new HudMessage(256)
                .number(Road.COUNTER, Road.STOCK_COUNTER)
                .number(Road.STATE, navigating ? Road.NAVIGATING : Road.OFF);
    }
}
