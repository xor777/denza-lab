package dev.denza.hudlink;

import dev.denza.hudlink.HudProtocol.Road;
import java.util.List;

/**
 * The road packet {@code 0x8001} from the fields that won. Fields go out in one fixed order, the
 * order both senders wrote before the compositor - Denza Apps' with the module's lanes first - so
 * moving them onto it changed no byte (`HudWireGoldenTest`, the module's `ArTest`). One value is
 * rewritten on the way: field 9 never says less than {@link Road#MANEUVER_METERS_MIN}.
 */
public final class HudRoadPacket {
    static final int[] ORDER = {
            Road.LANE_COUNT, Road.LANES_PNG,
            Road.REMAINING_METERS, Road.REMAINING_SECONDS,
            Road.MANEUVER_PNG, Road.MANEUVER_METERS, Road.ROAD_NAME, Road.SPEED_LIMIT,
            Road.SUMMARY_DISTANCE, Road.SUMMARY_TIME, Road.STOCK_MANEUVER,
            Road.LONGITUDE, Road.LATITUDE, Road.SPEED, Road.ALTITUDE,
            Road.AR_LINE, Road.AR_POINT, Road.HEADING, Road.PROGRESS,
    };

    private HudRoadPacket() {
    }

    /** «Navigation on» with every field of {@code winners}, one role's fields each. */
    public static byte[] navigating(List<HudFields> winners) {
        int capacity = 256;
        for (HudFields fields : winners) {
            capacity += fields.byteSize();
        }
        HudMessage message = new HudMessage(capacity)
                .number(Road.COUNTER, Road.STOCK_COUNTER)
                .number(Road.STATE, Road.NAVIGATING);
        for (int field : ORDER) {
            for (HudFields fields : winners) {
                if (fields.has(field)) {
                    Object value = fields.value(field);
                    if (field == Road.MANEUVER_METERS && value instanceof Long && (Long) value < Road.MANEUVER_METERS_MIN) {
                        // At the manoeuvre itself the block keeps a distance rather than turn Chinese.
                        message.number(field, Road.MANEUVER_METERS_MIN);
                    } else {
                        fields.writeTo(message, field);
                    }
                    break;
                }
            }
        }
        return message.embedded(Road.ENVELOPE);
    }

    /**
     * The clear: navigation off. With {@code lanes} it also says «no lanes» (field 5 = 0), as the
     * module's clear does once it has sent lanes; a sender that never sent any leaves it out.
     */
    public static byte[] clear(boolean lanes) {
        HudMessage message = new HudMessage(16)
                .number(Road.COUNTER, Road.STOCK_COUNTER)
                .number(Road.STATE, Road.OFF);
        if (lanes) {
            message.number(Road.LANE_COUNT, 0);
        }
        return message.embedded(Road.ENVELOPE);
    }
}
