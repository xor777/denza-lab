package dev.denza.hudframes.probe;

import java.io.ByteArrayOutputStream;

import static dev.denza.hudframes.probe.HudSomeIpSender.*;

/** Isolated test of the stock LaneInfo fields; never sends ADAS or vehicle setters. */
final class HudLanePacket {
    // AutoNavi LaneAction: left, ahead, right. Only the middle lane is recommended.
    static final String IDS = "1,255|0,0|3,255|";

    static boolean validMode(String mode) {
        return "control".equals(mode) || "ids".equals(mode) || "image".equals(mode)
                || "both".equals(mode) || "both-city".equals(mode);
    }

    static byte[] build(String mode, byte[] lanesPng, byte[] markerPng) {
        if (!validMode(mode)) throw new IllegalArgumentException("Unknown lane mode " + mode);
        boolean picture = "image".equals(mode) || mode.startsWith("both");
        boolean ids = "ids".equals(mode) || mode.startsWith("both");
        if (picture && (lanesPng == null || lanesPng.length == 0)) {
            throw new IllegalArgumentException("Lane picture required");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        intField(out, 2, 2); // normal road packet, not a frame counter
        intField(out, 5, "control".equals(mode) ? 0 : 3);
        // Stock RoadClass: 7 = main road, 6 = city expressway. Separate test of a reported gate.
        intField(out, 6, "both-city".equals(mode) ? 6 : 7);
        if (picture) bytesField(out, 7, lanesPng);
        bytesField(out, 8, markerPng); // known working slot, distinct from the lane picture
        intField(out, 9, 300);
        stringField(out, 10, "LANES " + mode);
        intField(out, 16, 2);
        intField(out, 28, 11); // straight
        if (ids) stringField(out, 29, IDS);
        return embed(1, out.toByteArray());
    }

    static byte[] clear() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        intField(out, 2, 2);
        intField(out, 5, 0);
        intField(out, 6, -1); // stock reset RoadClass
        intField(out, 16, 1);
        // Omitted bytes/strings decode as empty in this complete protobuf message.
        return embed(1, out.toByteArray());
    }

    private HudLanePacket() {}
}
