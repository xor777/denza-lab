package dev.denza.adaslink;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The stock map's protobuf messages, written the way the generated protobuf-java lite classes
 * write them: proto3 fields in field-number order, a scalar at its default is left out, a set
 * sub-message always goes out (even empty), uint32 is the value's 32 bits as an unsigned varint
 * (so -1 is five bytes), int32 is sign-extended to ten. Field numbers and types are the
 * {@code newMessageInfo} tables of the generated classes in BydLaunchermap
 * ({@code someip/sd/map/inform/service}, {@code someip/navigation/...}).
 */
public final class Pb {
    public static final int DOUBLE = 0, FLOAT = 1, INT32 = 4, UINT32 = 11, UINT64 = 3, BOOL = 7, STRING = 8, BYTES = 10,
            MESSAGE = 9, MESSAGE_LIST = 27;

    public static final class Field {
        public final int number, type;
        public final String name;
        public final Msg message;
        Field(int number, int type, String name, Msg message) { this.number = number; this.type = type; this.name = name; this.message = message; }
    }

    /** One generated message: its fields in the generated table's order (= field-number order). */
    public static final class Msg {
        public final String name;
        public final Field[] fields;
        Msg(String name, Field... fields) {
            this.name = name; this.fields = fields;
            for (int i = 1; i < fields.length; i++)
                if (fields[i].number <= fields[i - 1].number) throw new IllegalArgumentException(name + ": fields out of order");
        }

        /** values[i] is fields[i]'s value; null leaves a field unset. */
        public byte[] encode(Object... values) {
            if (values.length != fields.length) throw new IllegalArgumentException(name + ": " + values.length + " values for " + fields.length + " fields");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < fields.length; i++) write(out, fields[i], values[i]);
            return out.toByteArray();
        }

        public Map<String, Object> decode(byte[] data) {
            Map<String, Object> result = new LinkedHashMap<>();
            int[] at = {0};
            Map<Integer, Field> byNumber = new LinkedHashMap<>();
            for (Field field : fields) byNumber.put(field.number, field);
            while (at[0] < data.length) {
                long tag = readVarint(data, at);
                int number = (int) (tag >>> 3), wire = (int) (tag & 7);
                Field field = byNumber.get(number);
                if (field == null) throw new IllegalArgumentException(name + ": unknown field " + number);
                Object value;
                switch (wire) {
                    case 0: {
                        long raw = readVarint(data, at);
                        if (field.type == BOOL) value = raw != 0;
                        else if (field.type == UINT32) value = raw & 0xFFFFFFFFL;
                        else if (field.type == INT32) value = (long) (int) raw;
                        else value = raw;
                        break;
                    }
                    case 1: {
                        long bits = 0;
                        for (int i = 0; i < 8; i++) bits |= (data[at[0] + i] & 255L) << (8 * i);
                        at[0] += 8;
                        value = Double.longBitsToDouble(bits);
                        break;
                    }
                    case 5: {
                        int bits = 0;
                        for (int i = 0; i < 4; i++) bits |= (data[at[0] + i] & 255) << (8 * i);
                        at[0] += 4;
                        value = (double) Float.intBitsToFloat(bits);
                        break;
                    }
                    case 2: {
                        int size = (int) readVarint(data, at);
                        if (size < 0 || at[0] + size > data.length) throw new IllegalArgumentException(name + ": truncated field " + number);
                        byte[] bytes = new byte[size];
                        System.arraycopy(data, at[0], bytes, 0, size);
                        at[0] += size;
                        if (field.type == STRING) value = new String(bytes, StandardCharsets.UTF_8);
                        else if (field.type == MESSAGE || field.type == MESSAGE_LIST) value = field.message.decode(bytes);
                        else value = hex(bytes);
                        break;
                    }
                    default: throw new IllegalArgumentException(name + ": wire type " + wire);
                }
                if (field.type == MESSAGE_LIST) {
                    @SuppressWarnings("unchecked") List<Object> list = (List<Object>) result.get(field.name);
                    if (list == null) result.put(field.name, list = new ArrayList<>());
                    list.add(value);
                } else {
                    if (result.containsKey(field.name)) throw new IllegalArgumentException(name + ": repeated field " + number);
                    result.put(field.name, value);
                }
            }
            return result;
        }
    }

    static Field f(int number, int type, String name) { return new Field(number, type, name, null); }
    static Field m(int number, String name, Msg message) { return new Field(number, MESSAGE, name, message); }
    static Field list(int number, String name, Msg message) { return new Field(number, MESSAGE_LIST, name, message); }

    private static void write(ByteArrayOutputStream out, Field field, Object value) {
        if (value == null) return;
        switch (field.type) {
            case DOUBLE: {
                double v = ((Number) value).doubleValue();
                long bits = Double.doubleToRawLongBits(v);
                if (bits == 0) return;
                tag(out, field.number, 1);
                for (int i = 0; i < 8; i++) out.write((int) (bits >>> (8 * i)) & 255);
                return;
            }
            case FLOAT: {
                int bits = Float.floatToRawIntBits(((Number) value).floatValue());
                if (bits == 0) return;
                tag(out, field.number, 5);
                for (int i = 0; i < 4; i++) out.write((bits >>> (8 * i)) & 255);
                return;
            }
            case UINT32: {
                int v = ((Number) value).intValue();
                if (v == 0) return;
                tag(out, field.number, 0);
                varint(out, v & 0xFFFFFFFFL);
                return;
            }
            case INT32: {
                int v = ((Number) value).intValue();
                if (v == 0) return;
                tag(out, field.number, 0);
                varint(out, v);
                return;
            }
            case UINT64: {
                long v = ((Number) value).longValue();
                if (v == 0) return;
                tag(out, field.number, 0);
                varint(out, v);
                return;
            }
            case BOOL:
                if (!(Boolean) value) return;
                tag(out, field.number, 0);
                out.write(1);
                return;
            case STRING: {
                String s = (String) value;
                if (s.isEmpty()) return;
                bytes(out, field.number, s.getBytes(StandardCharsets.UTF_8));
                return;
            }
            case BYTES: {
                byte[] b = (byte[]) value;
                if (b.length == 0) return;
                bytes(out, field.number, b);
                return;
            }
            case MESSAGE:
                bytes(out, field.number, (byte[]) value);
                return;
            case MESSAGE_LIST:
                for (byte[] item : (byte[][]) value) bytes(out, field.number, item);
                return;
            default: throw new IllegalArgumentException("type " + field.type);
        }
    }

    private static void bytes(ByteArrayOutputStream out, int number, byte[] data) {
        tag(out, number, 2);
        varint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void tag(ByteArrayOutputStream out, int number, int wire) { varint(out, ((long) number << 3) | wire); }

    static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    static long readVarint(byte[] data, int[] at) {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            if (at[0] >= data.length) throw new IllegalArgumentException("truncated varint");
            int b = data[at[0]++] & 255;
            value |= (long) (b & 0x7F) << shift;
            if (b < 0x80) return value;
        }
        throw new IllegalArgumentException("oversized varint");
    }

    public static String hex(byte[] data) {
        StringBuilder s = new StringBuilder(data.length * 2);
        for (byte b : data) s.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return s.toString();
    }

    public static byte[] unhex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    // --- someip.sd.map.inform.service.SomeipSdMapInformService ---
    public static final Msg SD_VEHICLE_LOCATION = new Msg("sdVehicleLocation",
            f(1, DOUBLE, "locationLatitude"), f(2, DOUBLE, "locationLongitude"),
            f(3, DOUBLE, "locationLatitudeAssociateRoad"), f(4, DOUBLE, "locationLongitudeAssociateRoad"),
            f(5, UINT32, "vehicleSpeed"), f(6, UINT32, "GPSspeed"), f(7, DOUBLE, "curSDRouteID"), f(8, UINT32, "reserve4"),
            f(9, UINT32, "curStepId"), f(10, UINT32, "curLinkId"), f(11, UINT32, "linkOffset"), f(12, UINT32, "distanceToNextStep"),
            f(13, UINT32, "currentRoadOwnership"), f(14, UINT32, "reserve2"), f(15, FLOAT, "reserve3"));
    public static final Msg SD_VEHICLE_LOCATION_NOTIFY = new Msg("sdVehicleLocationNotify", m(1, "sdVehicleLocation", SD_VEHICLE_LOCATION));
    public static final Msg REGIONAL_AND_WEATHER = new Msg("regionalAndWeather",
            f(1, UINT64, "regionId"), f(2, UINT64, "countryCode"), f(3, UINT64, "provinceCode"), f(4, UINT64, "cityCode"),
            f(5, UINT32, "weatherType"), f(6, UINT64, "reserve1"), f(7, UINT64, "reserve2"), f(8, DOUBLE, "reserve3"));
    public static final Msg REGIONAL_AND_WEATHER_NOTIFY = new Msg("regionalAndWeatherNotify", m(1, "regionalAndWeather", REGIONAL_AND_WEATHER));
    public static final Msg TUNNEL = new Msg("tunnel",
            f(1, UINT32, "tunneStates"), f(2, DOUBLE, "tunnelStartPiointLat"), f(3, DOUBLE, "tunnelStartPiointLon"),
            f(4, DOUBLE, "tunnelEndPiointLat"), f(5, DOUBLE, "tunnelEndPiointLon"), f(6, UINT32, "toTunnelDist"),
            f(7, UINT64, "reserve1"), f(8, UINT32, "reserve2"), f(9, FLOAT, "reserve3"));
    public static final Msg TUNNEL_NOTIFY = new Msg("tunnelNotify", m(1, "tunnel", TUNNEL));
    public static final Msg ROAD_FACILITIES = new Msg("roadFacilities",
            f(1, UINT32, "naviFacilityType"), f(2, UINT32, "cruiseFacilityType"), f(3, UINT32, "distance"),
            f(4, DOUBLE, "boardSignLocationLon"), f(5, DOUBLE, "boardSignLocationLat"), f(6, UINT64, "reserve1"),
            f(7, UINT32, "reserve2"), f(8, FLOAT, "reserve3"));
    public static final Msg ROAD_FACILITIES_NOTIFY = new Msg("roadFacilitiesNotify", m(1, "roadFacilities", ROAD_FACILITIES));
    public static final Msg SD_TRAFFIC_INCIDENT_ITEM = new Msg("sdTraffiIncidentItem",
            f(1, UINT32, "naviCongestionInfo"), f(2, UINT32, "occupiedLane"), f(3, DOUBLE, "cnstrctnCrdLatitude"),
            f(4, DOUBLE, "cnstrctnCrdLongitude"), f(5, UINT64, "naviCongestionDistLen"), f(6, UINT32, "occupiedLaneDtl"), f(7, FLOAT, "reserve3"));
    public static final Msg SD_TRAFFIC_INCIDENT_NOTIFY = new Msg("sdTraffiIncidentNotify", list(1, "sdTraffiIncident", SD_TRAFFIC_INCIDENT_ITEM));
    public static final Msg SD_TRAFFIC_LIGHT = new Msg("sdTrafficLight",
            f(1, UINT32, "trafficLightExist"), f(2, DOUBLE, "trafficLightLat"), f(3, DOUBLE, "trafficLightLon"),
            f(4, UINT32, "lightStateType"), f(5, UINT64, "startTime"), f(6, UINT64, "endTime"), f(7, UINT32, "lightDir"),
            f(8, UINT32, "lightWaitNum"), f(9, UINT64, "reserve1"), f(10, UINT32, "trafficLightDist"), f(11, FLOAT, "reserve3"));
    public static final Msg SD_TRAFFIC_LIGHT_NOTIFY = new Msg("sdTrafficLightNotify", m(1, "sdTrafficLight", SD_TRAFFIC_LIGHT));
    public static final Msg TIDAL_LANE = new Msg("tidalLane",
            f(1, UINT32, "tidalLane"), f(2, UINT32, "rampRmnd"), f(3, UINT64, "reserve1"), f(4, UINT32, "reserve2"), f(5, FLOAT, "reserve3"));
    public static final Msg TIDAL_LANE_NOTIFY = new Msg("tidalLaneNotify", m(1, "tidalLane", TIDAL_LANE));
    public static final Msg NAVI_ACTION_AND_CAMERA = new Msg("naviActionAndCamera",
            f(1, UINT32, "iconType"), f(2, UINT32, "mainAction"), f(3, UINT32, "assistantAction"), f(4, UINT32, "distance"),
            f(5, UINT32, "cameraType"), f(6, UINT32, "cameraDistance"), f(7, UINT64, "reserve1"), f(8, UINT64, "reserve2"), f(9, DOUBLE, "reserve3"));
    public static final Msg NAVI_ACTION_AND_CAMERA_NOTIFY = new Msg("naviActionAndCameraNotify", m(1, "naviActionAndCamera", NAVI_ACTION_AND_CAMERA));
    public static final Msg SPEED_INTERSECTION = new Msg("speedIntersectionInformation",
            f(1, UINT32, "trafficFlowSpeed"), f(2, UINT32, "historySpeed"), f(3, UINT32, "hasParallelRoad"), f(4, UINT32, "hasMixFork"),
            f(5, UINT32, "hasLongSolidLane"), f(6, UINT32, "assistantActionDistance"), f(7, STRING, "naviVoiceCommand"),
            f(8, UINT64, "reserve1"), f(9, UINT64, "reserve2"), f(10, DOUBLE, "reserve3"));
    public static final Msg SPEED_INTERSECTION_NOTIFY = new Msg("speedIntersectionInformationNotify", m(1, "speedIntersectionInformation", SPEED_INTERSECTION));
    public static final Msg INTERSECTION = new Msg("intersection",
            f(1, UINT32, "distToNextGudc"), f(2, BYTES, "foreground"), f(3, BYTES, "background"),
            f(4, UINT32, "numOfLaneOfNextIntscn"), f(5, UINT64, "reserve1"), f(6, UINT32, "reserve2"), f(7, FLOAT, "reserve3"));
    public static final Msg INTERSECTION_NOTIFY = new Msg("intersectionNotify", m(1, "intersection", INTERSECTION));
    public static final Msg NEXT_LANES = new Msg("nextIntersectionLanesInfo",
            f(1, BYTES, "backLane"), f(2, BYTES, "frontLane"), f(3, BYTES, "extendLane"), f(4, BYTES, "recomendLane"),
            f(5, BYTES, "backLaneType"), f(6, BYTES, "frontLaneType"), f(7, UINT32, "segmentIndex"),
            f(8, UINT32, "linkIndex"), f(9, DOUBLE, "timestamp"));
    public static final Msg NEXT_LANES_NOTIFY = new Msg("nextIntersectionLanesInfoNotify", m(1, "nextIntersectionLanesInfo", NEXT_LANES));
    public static final Msg AHEAD_LANES_NOTIFY = new Msg("aheadIntersectionsLanesInfoNotify", f(1, STRING, "aheadIntersectionsLanesInfo"));

    // --- someip.navigation.status_.link.info.service.SomeipNavigationStatusLinkInfoService ---
    public static final Msg NAVIGATION_STATUS = new Msg("NavigationStatus_LinkInfoNotifyStruct",
            f(1, UINT32, "checksum"), f(2, UINT32, "counter"), f(3, DOUBLE, "timestamp"), f(4, UINT32, "navigationStatus"),
            f(5, UINT32, "matchingTableStatus"), f(6, UINT32, "remainDistance"), f(7, UINT32, "viaPointDistance"),
            f(8, UINT32, "hDStartDistance"), f(9, UINT32, "dNPSwitch"), f(10, UINT32, "aNPRoad"), f(11, UINT32, "mapVersion"));
    public static final Msg NAVIGATION_STATUS_NOTIFY = new Msg("NavigationStatus_LinkInfoNotify", m(1, "navigationStatusLinkInfoNotifyStruct", NAVIGATION_STATUS));
    public static final Msg TRAFFIC_INFO = new Msg("TrafficInfo",
            f(1, UINT32, "trafficJamDist"), f(2, UINT32, "distToStartTrafficJam"), f(3, UINT32, "trafficJamStatus"), f(4, UINT32, "passTime"),
            f(5, UINT32, "cnstrctnRmnd"), f(6, DOUBLE, "cnstrctnCrdLatitude"), f(7, DOUBLE, "cnstrctnCrdLongitude"),
            f(8, UINT32, "spdLmtSpeedValue"), f(9, UINT32, "spdLmtEleEyeSpeedValue"), f(10, UINT32, "spdLmtEleEyeDist"),
            f(11, DOUBLE, "intervalCameraStartPointLon"), f(12, DOUBLE, "intervalCameraStartPointLat"),
            f(13, DOUBLE, "intervalCameraEndPointLon"), f(14, DOUBLE, "intervalCameraEndPointLat"),
            f(15, UINT32, "intervalCameraSpeedValue"), f(16, BOOL, "offCourse"), f(17, UINT32, "distToDsttn"),
            f(18, UINT32, "timeToDsttn"), f(19, UINT32, "roadClass"));
    public static final Msg TRAFFIC_INFO_NOTIFY = new Msg("TrafficInfoNotify", m(1, "trafficInfo", TRAFFIC_INFO));
    public static final Msg ADV_AUTO_FUN_INFO = new Msg("AdvAutoFunInfo", f(1, UINT32, "noODDRegionDist"));
    public static final Msg ADV_AUTO_FUN_INFO_NOTIFY = new Msg("AdvAutoFunInfoNotify", m(1, "advAutoFunInfo", ADV_AUTO_FUN_INFO));
    public static final Msg GENERAL_NAVIGATION = new Msg("genernalNavigation",
            f(1, UINT32, "intervalCameraSpeedDisttoStart"), f(2, UINT32, "intervalCameraSpeedDisttoEnd"), f(3, UINT32, "newNoODDRegionDist"),
            f(4, UINT64, "reserve1"), f(5, UINT32, "reserve2"), f(6, DOUBLE, "reserve3"), f(7, DOUBLE, "reserve4"),
            f(8, DOUBLE, "reserve5"), f(9, DOUBLE, "reserve6"), f(10, DOUBLE, "reserve7"));
    public static final Msg GENERAL_NAVIGATION_NOTIFY = new Msg("genernalNavigationNotify", m(1, "genernalNavigation", GENERAL_NAVIGATION));

    // --- someip.navigation.sd.link2.service.SomeipNavigationSdLink2Service ---
    public static final Msg NAVI_SD_ROUTE = new Msg("naviSDRouteStruct",
            f(1, UINT32, "checksCRC32"), f(2, UINT32, "counter"), f(3, UINT32, "navigationSDLink2Num"), f(4, STRING, "navigationSDLink2"));
    public static final Msg NAVI_SD_ROUTE_NOTIFY = new Msg("naviSDRouteNotify", m(1, "naviSDRouteStruct", NAVI_SD_ROUTE));

    // --- someip.navi.path.user.select.sts.service.SomeipNaviPathUserSelectStsService ---
    public static final Msg PATH_SELECT = new Msg("NaviPathUserSelectStsStruct",
            f(1, UINT32, "checksCRC32"), f(2, UINT32, "counter"), f(3, UINT64, "pathIDReq"), f(4, UINT64, "reserve1"),
            f(5, UINT32, "reserve2"), f(6, FLOAT, "reserve3"));
    public static final Msg PATH_SELECT_NOTIFY = new Msg("NaviPathUserSelectStsNotify", m(1, "naviPathUserSelectStsStruct", PATH_SELECT));

    // --- inbound: someip.navigation.path.match.status.service.SomeipNavigationPathMatchStatusService ---
    public static final Msg PATH_SELECT_CONFIRM = new Msg("NaviPathUserSelectStsConfirmStruct",
            f(1, UINT32, "checksCRC32"), f(2, UINT32, "counter"), f(3, UINT64, "pathIDResp"), f(4, INT32, "statusCode"),
            f(5, STRING, "errorMsg"), f(6, UINT64, "reserve1"), f(7, UINT32, "reserve2"), f(8, FLOAT, "reserve3"));
    public static final Msg PATH_SELECT_CONFIRM_NOTIFY = new Msg("NaviPathUserSelectStsConfirmNotify",
            m(1, "naviPathUserSelectStsConfirmStruct", PATH_SELECT_CONFIRM));

    private Pb() {}
}
