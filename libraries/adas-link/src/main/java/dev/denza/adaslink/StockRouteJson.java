package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The stock route payload for ADAS type 1 (NaviToEHPData), line for line from BydLaunchermap:
 * {@code k.g()} (single path, {@code endPOIInfo} PTPStateFlag 1), {@code l.f/y/r/a/d/o/b}
 * (groups, segments, links, points), {@code l.k/A} (segment description) and {@code n.a()}
 * (NaviDataDetail and chunking). Bean keys in ART field order (sorted by Java field name).
 */
public final class StockRouteJson {
    /** The Amap SDK strings the description is built from, by app locale ({@code values}, {@code values-en}). */
    public enum Strings {
        EN("travel", " ", "km", "meter(s)", "get into", "Nameless Road",
                new String[]{"Straight ahead", "Turn left", "Turn right", "Drive forward to the left", "Drive right ahead",
                        "Turn left and back", "Turn right back", "Turn left and turn around", "Enter the roundabout",
                        "Drive out of the roundabout", "Passing through the service", "area"}),
        DEFAULT("行驶", "", "公里", "米", "进入", "无名道路",
                new String[]{"直行", "左转", "右转", "向左前方行驶", "向右前方行驶", "向左后转", "向右后转", "左转调头", "进入环岛",
                        "驶出环岛", "经过服务区", "经过收费站"});

        final String driving, space, km, meter, enter, noNameRoad;
        /** along, turnleft, turnright, left_front, right_front, left_back, right_back, turn_left_back, enter_ring, leave_ring, along_rest, along_charge. */
        final String[] actions;
        Strings(String driving, String space, String km, String meter, String enter, String noNameRoad, String[] actions) {
            this.driving = driving; this.space = space; this.km = km; this.meter = meter; this.enter = enter;
            this.noNameRoad = noNameRoad; this.actions = actions;
        }
    }

    /** Literal in {@code l.j()} and {@code l.r()}, not a resource. */
    static final String UNNAMED = "无名道路";
    /** {@code NaviDataDetail} chunking thresholds of {@code n.a()}. */
    static final int CHUNK = 524288, CHUNK_COUNT_DIVISOR = 524264;

    /** {@code k.a(routeType, resend)}: Amap RouteType to the payload's RouteType. */
    public static int routeType(int amapRouteType, boolean resend) {
        if (resend) return 14;
        switch (amapRouteType) {
            case 16: case 28: return 99;
            case 17: case 26: return 17;
            case 23: return 9;
            case 35: return 35;
            case 998: return 98;
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            case 4: return 4;
            case 5: case 6: case 7: return 99;
            case 9: return 9;
            case 10: case 11: case 13: return 99;
            case 12: return 12;
            case 14: return 14;
            default: return 50;
        }
    }

    /** {@code MessageSend} JSON for one path ({@code k.g()} with ADAS type 1, not SOME/IP v2). */
    public static String messageSend(StockPath path, int payloadRouteType, Strings strings) {
        Map<String, Object> data = naviToEhp(path, strings);
        List<Object> list = new ArrayList<>();
        list.add(data);
        Map<String, Object> end = Json.object();
        end.put("AVPMapIdList", new ArrayList<>());
        end.put("PTPStateFlag", 1L);
        Map<String, Object> send = Json.object();
        send.put("code", 200L);
        send.put("data", list);
        send.put("endPOIInfo", end);
        send.put("mapProvider", 1L);
        send.put("msg", "response");
        send.put("RouteType", (long) payloadRouteType);
        return Json.write(send);
    }

    /** {@code n.a()}: the NaviDataDetail strings in send order; indices 1-based as {@code e0.k}'s log. */
    public static List<String> chunks(String payload, int pathCount, long nowMs) {
        List<String> out = new ArrayList<>();
        int length = payload.length();
        if (length + 24 < CHUNK) {
            out.add(detail(0, 1, pathCount, payload, nowMs));
            return out;
        }
        int count = (length / CHUNK_COUNT_DIVISOR) + (length % CHUNK_COUNT_DIVISOR == 0 ? 0 : 1);
        for (int i = 0; i < count; i++) {
            // As stock: the count uses 524264, the cut 524288. A cut past the end throws there and
            // the remaining chunks are never sent; the caller catches it the same way.
            String part = i < count - 1 ? payload.substring(i * CHUNK, (i + 1) * CHUNK) : payload.substring(i * CHUNK);
            out.add(detail(i, count, pathCount, part, nowMs));
        }
        return out;
    }

    static String detail(int index, int length, int pathCount, String payload, long time) {
        Map<String, Object> d = Json.object();
        d.put("currentIndex", (long) index);
        d.put("length", (long) length);
        d.put("pathCount", (long) pathCount);
        d.put("payload", payload);
        d.put("time", time);
        return Json.write(d);
    }

    /** {@code l.y(false, path)}: no group segments, so one group named {@value #UNNAMED} ({@code l.r()}). */
    static Map<String, Object> naviToEhp(StockPath path, Strings strings) {
        List<Object> links = new ArrayList<>(), pnts = new ArrayList<>(), groups = new ArrayList<>();
        int[] counts = {0, 0}; // linkCnt, pntCnt
        int segmentCount = path.segments.size();
        Map<String, Object> group = group(path, 0, segmentCount, UNNAMED, links, pnts, counts, strings);
        if (segmentCount > 0) groups.add(group);
        List<Object> lights = new ArrayList<>();
        for (double[] light : path.trafficLights) {
            Map<String, Object> t = Json.object();
            t.put("x", light[0]);
            t.put("y", light[1]);
            lights.add(t);
        }
        Map<String, Object> data = Json.object();
        data.put("guideGroups", groups);
        data.put("linkCnt", (long) counts[0]);
        data.put("links", links);
        data.put("pathID", path.pathId);
        data.put("pntCnt", (long) counts[1]);
        data.put("pnts", pnts);
        data.put("trafficLights", lights);
        data.put("viaRoadInfo", new ArrayList<>());
        return data;
    }

    /** {@code l.a()}. */
    static Map<String, Object> group(StockPath path, int start, int count, String name, List<Object> links, List<Object> pnts,
                                     int[] counts, Strings strings) {
        long lights = 0, time = 0, len = 0;
        int iconType = 0;
        Map<String, Object> enter = null;
        List<Object> segments = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StockPath.Segment segment = path.segments.get(start + i);
            lights = (int) (lights + segment.trafficLightNum);
            time = (int) (time + segment.travelTime);
            len = (int) (len + segment.length);
            if (i == 0) {
                iconType = segment.mainAction;
                if (!segment.links.isEmpty() && segment.links.get(0).pointCount() > 0) {
                    enter = Json.object();
                    enter.put("x", segment.links.get(0).lon[0] / 3600000.0d);
                    enter.put("y", segment.links.get(0).lat[0] / 3600000.0d);
                }
            }
            segments.add(segment(path, start + i, links, pnts, counts, strings));
        }
        Map<String, Object> g = Json.object();
        g.put("groupIconType", (long) iconType);
        g.put("groupLen", len);
        g.put("groupName", name);
        g.put("groupTime", time);
        g.put("groupTrafficLightsCount", lights);
        g.put("groupEnterCoord", enter);
        g.put("segments", segments);
        return g;
    }

    /** {@code l.d()} with {@code l.o()} and {@code l.b()}. */
    static Map<String, Object> segment(StockPath path, int index, List<Object> links, List<Object> pnts, int[] counts, Strings strings) {
        StockPath.Segment segment = path.segments.get(index);
        String nextRoadName = "";
        if (index + 1 < path.segments.size() && !path.segments.get(index + 1).links.isEmpty())
            nextRoadName = lastRoadName(path.segments.get(index + 1));
        Map<String, Object> s = Json.object();
        s.put("crntSegmLinkCnt", (long) segment.links.size());
        s.put("description", description(segment, nextRoadName, strings));
        s.put("isArriveWayPoint", segment.assistantAction == 35 || segment.assistantAction == 36);
        s.put("linkBegIdx", (long) counts[0]);
        s.put("navigationAssitAction", (long) segment.assistantAction);
        s.put("navigationLen", (long) (int) segment.length);
        s.put("navigationMainAction", (long) segment.mainAction);
        s.put("navigationNextRoadName", nextRoadName);
        s.put("trafficLightNum", segment.trafficLightNum);
        for (StockPath.Link link : segment.links) {
            counts[0]++;
            links.add(link(link, counts[1]));
            for (int i = 0; i < link.pointCount(); i++) {
                counts[1]++;
                Map<String, Object> p = Json.object();
                p.put("x", link.lon[i] / 3600000.0d);
                p.put("y", link.lat[i] / 3600000.0d);
                pnts.add(p);
            }
        }
        return s;
    }

    static Map<String, Object> link(StockPath.Link link, int pntBegIdx) {
        Map<String, Object> l = Json.object();
        l.put("adminCode", (long) (int) link.adcode);
        l.put("assistantAction", (long) link.assistantAction);
        l.put("direction", (long) link.direction);
        l.put("formway", (long) link.formway);
        l.put("hasMixFork", link.hasMixFork);
        l.put("hasMultiOut", link.hasMultiOut);
        l.put("hasParallel", link.hasParallel);
        l.put("hasTrafficLight", link.hasTrafficLight);
        l.put("laneNum", (long) link.laneNum);
        l.put("len", new Json.F(link.length));
        l.put("linkID", link.topoId);
        l.put("linktype", (long) link.linkType);
        l.put("mainAction", (long) link.mainAction);
        l.put("ownership", (long) (link.ownership >= 1 && link.ownership <= 4 ? 1 : link.ownership));
        l.put("pntBegIdx", (long) pntBegIdx);
        l.put("pntCnt", (long) link.pointCount());
        l.put("roadclass", (long) link.roadClass);
        l.put("roadname", link.roadName);
        l.put("speedLimit", 0L);
        return l;
    }

    /** {@code l.j()}: the last link's road name wins; empty is {@value #UNNAMED}. */
    static String lastRoadName(StockPath.Segment segment) {
        String name = "";
        for (StockPath.Link link : segment.links) name = link.roadName;
        return name == null || name.isEmpty() ? UNNAMED : name;
    }

    /** {@code l.k()}. */
    static String description(StockPath.Segment segment, String nextRoadName, Strings strings) {
        int action = displayAction(segment.mainAction, segment.assistantAction);
        StringBuilder b = new StringBuilder();
        b.append(strings.driving).append(strings.space).append(distance(segment.length, strings)).append(strings.space)
                .append(actionText(action, strings));
        String road = nextRoadName;
        if ((road == null || road.isEmpty()) && action != 11 && action != 12) road = strings.noNameRoad;
        if (road != null && !road.isEmpty()) b.append(strings.space).append(strings.enter).append(strings.space).append(road);
        return b.toString();
    }

    /** {@code l.A()}. */
    static String distance(long length, Strings strings) {
        StringBuilder b = new StringBuilder();
        int metres = (int) length;
        if (metres >= 1000) {
            int km = metres / 1000, tenth = (metres % 1000) / 100;
            if (km < 10 && tenth > 0) b.append(km).append('.').append(tenth);
            else b.append(km);
            b.append(' ').append(strings.km);
        } else b.append(metres).append(' ').append(strings.meter);
        return b.toString();
    }

    /** {@code RouteActionUtil.getMainAction(main, assistant)}. */
    static int displayAction(int main, int assistant) {
        if (assistant == 5) return 16;
        switch (assistant) {
            case 33: return 13;
            case 34: return 14;
            case 35: return 10;
            case 36: return 15;
            default:
                switch (main) {
                    case 1: return 2;
                    case 2: return 3;
                    case 3: case 9: return 4;
                    case 4: case 10: return 5;
                    case 5: return 6;
                    case 6: return 7;
                    case 7: return 8;
                    case 11: return 11;
                    case 12: return 12;
                    default: return 9;
                }
        }
    }

    /** {@code RouteActionUtil.getNaviActionStr()}. */
    static String actionText(int action, Strings s) {
        switch (action) {
            case 2: return s.actions[1];
            case 3: return s.actions[2];
            case 4: return s.actions[3];
            case 5: return s.actions[4];
            case 6: return s.actions[5];
            case 7: return s.actions[6];
            case 8: return s.actions[7];
            case 11: return s.actions[8];
            case 12: return s.actions[9];
            case 13: return s.actions[10];
            case 14: return s.actions[11];
            default: return s.actions[0];
        }
    }

    private StockRouteJson() {}
}
