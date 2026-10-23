package dev.denza.adaslink;

/**
 * SOME/IP topics of the stock map's ADAS feed, as {@code kind(4) | service(16) | instance(16) |
 * id(16)} (BydLaunchermap {@code SomeIpId}, the literals in {@code f0}, {@code e0}, {@code f}).
 */
public final class Topics {
    static long event(int service, int id) { return (4L << 48) | ((long) service << 32) | ((long) service << 16) | id; }
    static long service(int service) { return (0xBL << 48) | ((long) service << 32) | ((long) service << 16); }

    public static final int SVC_NAV_STATUS = 7, SVC_ROUTE = 43, SVC_SELECT = 45, SVC_SD_MAP = 0x8202, SVC_PATH_MATCH = 44;

    /** startSomeIpService on these four, as {@code NoaCanSomeIpLink.o()}. */
    public static final long[] SERVICES = {service(SVC_NAV_STATUS), service(SVC_ROUTE), service(SVC_SELECT), service(SVC_SD_MAP)};

    public static final long NAVIGATION_STATUS = event(SVC_NAV_STATUS, 0x8001);   // 1125929972105217
    public static final long TRAFFIC_INFO = event(SVC_NAV_STATUS, 0x8003);        // 1125929972105219
    public static final long ADV_AUTO_FUN_INFO = event(SVC_NAV_STATUS, 0x8004);   // 1125929972105220
    public static final long GENERAL_NAVIGATION = event(SVC_NAV_STATUS, 0x8006);  // 1125929972105222
    public static final long ROUTE = event(SVC_ROUTE, 0x8002);                    // 1126084593287170
    public static final long PATH_SELECTED = event(SVC_SELECT, 0x8001);           // 1126093183352833
    public static final long SD_TRAFFIC_LIGHT = event(SVC_SD_MAP, 0x8001);        // 1268847189590017
    public static final long SD_TRAFFIC_INCIDENT = event(SVC_SD_MAP, 0x8002);
    public static final long SD_TUNNEL = event(SVC_SD_MAP, 0x8004);
    public static final long SD_INTERSECTION = event(SVC_SD_MAP, 0x8006);
    public static final long SD_TIDAL_LANE = event(SVC_SD_MAP, 0x8007);
    public static final long SD_VEHICLE_LOCATION = event(SVC_SD_MAP, 0x8008);     // 1268847189590024
    public static final long SD_REGION = event(SVC_SD_MAP, 0x8009);
    public static final long SD_SPEED_INTERSECTION = event(SVC_SD_MAP, 0x800A);
    public static final long SD_ACTION_CAMERA = event(SVC_SD_MAP, 0x800B);
    public static final long SD_NEXT_LANES = event(SVC_SD_MAP, 0x800C);
    public static final long SD_AHEAD_LANES = event(SVC_SD_MAP, 0x800E);
    /** Inbound, ADAS to IVI: MMTPathSelected ({@code PathSelectedProxy}, 1126088888320002). */
    public static final long PATH_SELECT_CONFIRM = event(SVC_PATH_MATCH, 0x8002);

    public static int service(long topic) { return (int) ((topic >>> 32) & 0xFFFF); }
    public static int id(long topic) { return (int) (topic & 0xFFFF); }

    public static String name(long topic) {
        if (topic == NAVIGATION_STATUS) return "NavigationStatus";
        if (topic == TRAFFIC_INFO) return "TrafficInfo";
        if (topic == ADV_AUTO_FUN_INFO) return "AdvAutoFunInfo";
        if (topic == GENERAL_NAVIGATION) return "genernalNavigation";
        if (topic == ROUTE) return "naviSDRoute";
        if (topic == PATH_SELECTED) return "pathSelected";
        if (topic == SD_TRAFFIC_LIGHT) return "sdTrafficLight";
        if (topic == SD_TRAFFIC_INCIDENT) return "sdTraffiIncident";
        if (topic == SD_TUNNEL) return "tunnel";
        if (topic == SD_INTERSECTION) return "intersection";
        if (topic == SD_TIDAL_LANE) return "tidalLane";
        if (topic == SD_VEHICLE_LOCATION) return "sdVehicleLocation";
        if (topic == SD_REGION) return "regionalAndWeather";
        if (topic == SD_SPEED_INTERSECTION) return "speedIntersectionInformation";
        if (topic == SD_ACTION_CAMERA) return "naviActionAndCamera";
        if (topic == SD_NEXT_LANES) return "nextIntersectionLanesInfo";
        if (topic == SD_AHEAD_LANES) return "aheadIntersectionsLanesInfo";
        if (topic == PATH_SELECT_CONFIRM) return "MMTPathSelected";
        return "0x" + Long.toHexString(topic);
    }

    /** The outer notify message of a topic, for decoding a recorded payload; null when not ours. */
    public static Pb.Msg schema(long topic) {
        if (topic == NAVIGATION_STATUS) return Pb.NAVIGATION_STATUS_NOTIFY;
        if (topic == TRAFFIC_INFO) return Pb.TRAFFIC_INFO_NOTIFY;
        if (topic == ADV_AUTO_FUN_INFO) return Pb.ADV_AUTO_FUN_INFO_NOTIFY;
        if (topic == GENERAL_NAVIGATION) return Pb.GENERAL_NAVIGATION_NOTIFY;
        if (topic == ROUTE) return Pb.NAVI_SD_ROUTE_NOTIFY;
        if (topic == PATH_SELECTED) return Pb.PATH_SELECT_NOTIFY;
        if (topic == SD_TRAFFIC_LIGHT) return Pb.SD_TRAFFIC_LIGHT_NOTIFY;
        if (topic == SD_TRAFFIC_INCIDENT) return Pb.SD_TRAFFIC_INCIDENT_NOTIFY;
        if (topic == SD_TUNNEL) return Pb.TUNNEL_NOTIFY;
        if (topic == SD_TIDAL_LANE) return Pb.TIDAL_LANE_NOTIFY;
        if (topic == SD_VEHICLE_LOCATION) return Pb.SD_VEHICLE_LOCATION_NOTIFY;
        if (topic == SD_REGION) return Pb.REGIONAL_AND_WEATHER_NOTIFY;
        if (topic == SD_SPEED_INTERSECTION) return Pb.SPEED_INTERSECTION_NOTIFY;
        if (topic == SD_ACTION_CAMERA) return Pb.NAVI_ACTION_AND_CAMERA_NOTIFY;
        if (topic == SD_AHEAD_LANES) return Pb.AHEAD_LANES_NOTIFY;
        if (topic == PATH_SELECT_CONFIRM) return Pb.PATH_SELECT_CONFIRM_NOTIFY;
        return null;
    }

    private Topics() {}
}
