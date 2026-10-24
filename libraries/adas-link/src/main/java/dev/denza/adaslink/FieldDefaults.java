package dev.denza.adaslink;

import java.util.Map;

/**
 * The one place for field values the Yandex feed sends where it has no source of its own. Each
 * value here is today's behaviour, kept as it was; a separate experiment decides the real values
 * (captures/adas-live-drive-20261024/IMPLICIT-DEFAULTS.md). Switch them here, not at the call
 * sites. Both Navigator hosts, the dry run and the live publisher, take their config from here.
 */
public final class FieldDefaults {
    private FieldDefaults() { }

    /** R33: coordinates go out in the source WGS-84 frame (the stock sends its GCJ-style SDK frame). */
    public static final Coordinates.Frame FRAME = Coordinates.Frame.WGS84;
    /** T02: TrafficInfo.SpdLmtSpeedValue from Yandex's posted limit, route edge, then graph. */
    public static final boolean ROAD_SPEED_LIMIT = true;
    /** F01/F02: road facilities (0x8202/0x8005) from Yandex's route events. */
    public static final boolean ROAD_FACILITIES = true;

    /** Where sdVehicleLocation.vehicleSpeed comes from (P04). */
    public enum VehicleSpeed {
        /** No car speed reaches Navigator's process (no BYDAUTO permission): GNSS speed stands in. */
        GNSS_STANDS_IN
    }
    public static final VehicleSpeed VEHICLE_SPEED = VehicleSpeed.GNSS_STANDS_IN;

    /** G01: Region regionId/province/city/weather and countryCode while Yandex names no region. */
    public static final int REGION_ID = 0, PROVINCE_CODE = 0, CITY_CODE = 0, WEATHER = 0;
    public static final long COUNTRY_CODE = 1;

    /** P01/T01: the stock's unmatched values, sent while no complete graph road is under the car. */
    public static final int ROAD_CLASS_UNMATCHED = StockPath.ROAD_CLASS_NULL, OWNERSHIP_UNMATCHED = StockPath.OWNERSHIP_UNKNOWN;

    /** The Navigator hosts' feed values; the library's own Config defaults stay the factory oracle's. */
    public static StockFeed.Config navigator(StockFeed.Config config) {
        config.frame = FRAME;
        config.roadSpeedLimit = ROAD_SPEED_LIMIT;
        config.roadFacilities = ROAD_FACILITIES;
        return config;
    }

    /** Recorded in each trace header, so a log says which defaults produced it. */
    public static void describe(Map<String, Object> header) {
        header.put("fieldDefaults", "FieldDefaults");
        header.put("vehicleSpeedSource", VEHICLE_SPEED.name());
        header.put("defaultFrame", FRAME.name());
        header.put("regionDefault", REGION_ID + "/" + COUNTRY_CODE + "/" + PROVINCE_CODE + "/" + CITY_CODE);
    }
}
