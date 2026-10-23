package dev.denza.adaslink;

/**
 * Payloads of the stock senders, parameter for parameter: {@code SomeIPMatrixManager} ({@code
 * k.h.a0.c.c.a.f0}) methods {@code s q e h r k l p f o c i}, {@code SendSomeIpDataManager}
 * ({@code k.h.e0.c.j.e0}) {@code k l}, {@code DnpOddDistanceController.h}. Each builds the same
 * builder calls; unset builder fields stay unset.
 */
public final class StockMessages {
    /** f0.s(lon, lat, roadLon, roadLat, vehicleSpeed, gpsSpeed, course, pathId, segIdx, linkIdx, segDist, linkRemain, ownership). */
    public static byte[] vehicleLocation(double lon, double lat, double roadLon, double roadLat, int vehicleSpeed, int gpsSpeed,
                                         int course, long pathId, int segIdx, int linkIdx, int segDist, int linkRemain, int ownership) {
        // setCurSDRouteID(long) widens to the double field.
        byte[] inner = Pb.SD_VEHICLE_LOCATION.encode(lat, lon, roadLat, roadLon, vehicleSpeed, gpsSpeed, (double) pathId, course,
                segIdx, linkIdx, linkRemain, segDist, ownership, null, null);
        return Pb.SD_VEHICLE_LOCATION_NOTIFY.encode((Object) inner);
    }

    /** f0.q(...): TrafficInfo, all nineteen setters. */
    public static byte[] trafficInfo(int jamLength, int distToJam, int jamStatus, int passTime, int construction, double cLon, double cLat,
                                     int roadSpeed, int eyeSpeed, int eyeDist, double iStartLon, double iStartLat, double iEndLon,
                                     double iEndLat, int iSpeed, boolean offCourse, int distToDest, int timeToDest, int roadClass) {
        byte[] inner = Pb.TRAFFIC_INFO.encode(jamLength, distToJam, jamStatus, passTime, construction, cLat, cLon, roadSpeed, eyeSpeed,
                eyeDist, iStartLon, iStartLat, iEndLon, iEndLat, iSpeed, offCourse, distToDest, timeToDest, roadClass);
        return Pb.TRAFFIC_INFO_NOTIFY.encode((Object) inner);
    }

    /** f0.e(state): NavigationStatus with navigationStatus only. */
    public static byte[] navigationStatus(int state) {
        byte[] inner = Pb.NAVIGATION_STATUS.encode(null, null, null, state, null, null, null, null, null, null, null);
        return Pb.NAVIGATION_STATUS_NOTIFY.encode((Object) inner);
    }

    /** f0.h(regionId, countryCode, provinceCode, cityCode, weatherType). */
    public static byte[] region(int regionId, long countryCode, int provinceCode, int cityCode, int weatherType) {
        // int setters into uint64 fields: sign-extended, as the generated setter takes a long.
        byte[] inner = Pb.REGIONAL_AND_WEATHER.encode((long) regionId, countryCode, (long) provinceCode, (long) cityCode, weatherType, null, null, null);
        return Pb.REGIONAL_AND_WEATHER_NOTIFY.encode((Object) inner);
    }

    /** f0.r(TunnelParam). */
    public static byte[] tunnel(int dist, int state, double startLon, double startLat, double endLon, double endLat) {
        byte[] inner = Pb.TUNNEL.encode(state, startLat, startLon, endLat, endLon, dist, null, null, null);
        return Pb.TUNNEL_NOTIFY.encode((Object) inner);
    }

    /** f0.k(list): one item per event; empty list is an empty message. */
    public static byte[] incidents(int[] types, int[] lanes, double[] lons, double[] lats) {
        byte[][] items = new byte[types.length][];
        for (int i = 0; i < types.length; i++)
            items[i] = Pb.SD_TRAFFIC_INCIDENT_ITEM.encode(types[i], lanes[i], lats[i], lons[i], null, null, null);
        return Pb.SD_TRAFFIC_INCIDENT_NOTIFY.encode((Object) items);
    }

    /** f0.l(exist, lon, lat, state, start, end, dir, waitNum, dist): the distance is logged, never set. */
    public static byte[] trafficLight(int exist, double lon, double lat, int state, long start, long end, int dir, int waitNum) {
        byte[] inner = Pb.SD_TRAFFIC_LIGHT.encode(exist, lat, lon, state, start, end, dir, waitNum, null, null, null);
        return Pb.SD_TRAFFIC_LIGHT_NOTIFY.encode((Object) inner);
    }

    /** f0.p(rampDist, tidalLane). */
    public static byte[] tidalLane(int rampDist, int tidal) {
        byte[] inner = Pb.TIDAL_LANE.encode(tidal, rampDist, null, null, null);
        return Pb.TIDAL_LANE_NOTIFY.encode((Object) inner);
    }

    /** f0.f(iconType, mainAction, assistantAction, distance, cameraType, cameraDistance). */
    public static byte[] actionAndCamera(int icon, int main, int assistant, int distance, int cameraType, int cameraDistance) {
        byte[] inner = Pb.NAVI_ACTION_AND_CAMERA.encode(icon, main, assistant, distance, cameraType, cameraDistance, null, null, null);
        return Pb.NAVI_ACTION_AND_CAMERA_NOTIFY.encode((Object) inner);
    }

    /** f0.o(trafficFlowSpeed, historySpeed, hasParallelRoad, hasMixFork, hasLongSolidLane, distance, voice). */
    public static byte[] speedIntersection(int flow, int history, int parallel, int mixFork, int solid, int distance, String voice) {
        byte[] inner = Pb.SPEED_INTERSECTION.encode(flow, history, parallel, mixFork, solid, distance, voice, null, null, null);
        return Pb.SPEED_INTERSECTION_NOTIFY.encode((Object) inner);
    }

    /** f0.i(list): Gson JSON of the 3 km lane beans. */
    public static byte[] aheadLanes(String json) {
        // setAheadIntersectionsLanesInfoBytes(ByteString.copyFromUtf8(json)) on a string field.
        return Pb.AHEAD_LANES_NOTIFY.encode(json);
    }

    /** f0.c(toStart, toEnd, _): genernalNavigation with newNoODDRegionDist -1. */
    public static byte[] generalNavigation(int toStart, int toEnd) {
        byte[] inner = Pb.GENERAL_NAVIGATION.encode(toStart, toEnd, -1, null, null, null, null, null, null, null);
        return Pb.GENERAL_NAVIGATION_NOTIFY.encode((Object) inner);
    }

    /** DnpOddDistanceController.h(dist). */
    public static byte[] noOddRegionDistance(int dist) {
        byte[] inner = Pb.ADV_AUTO_FUN_INFO.encode(dist);
        return Pb.ADV_AUTO_FUN_INFO_NOTIFY.encode((Object) inner);
    }

    /** e0.k(json, pathCount, ...): naviSDRouteStruct with the chunk and the path count. */
    public static byte[] route(String chunk, int pathCount) {
        byte[] inner = Pb.NAVI_SD_ROUTE.encode(null, null, pathCount, chunk);
        return Pb.NAVI_SD_ROUTE_NOTIFY.encode((Object) inner);
    }

    /** e0.l(pathId): NaviPathUserSelectStsStruct.pathIDReq. */
    public static byte[] pathSelected(long pathId) {
        byte[] inner = Pb.PATH_SELECT.encode(null, null, pathId, null, null, null);
        return Pb.PATH_SELECT_NOTIFY.encode((Object) inner);
    }

    /** ADAS's MMTPathSelected, as the car sends it (for tests and the emulated acknowledgement). */
    public static byte[] pathSelectConfirm(long pathId, int statusCode) {
        byte[] inner = Pb.PATH_SELECT_CONFIRM.encode(null, null, pathId, statusCode, null, null, null, null);
        return Pb.PATH_SELECT_CONFIRM_NOTIFY.encode((Object) inner);
    }

    private StockMessages() {}
}
