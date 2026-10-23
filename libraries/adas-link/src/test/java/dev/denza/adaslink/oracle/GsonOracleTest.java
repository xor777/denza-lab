package dev.denza.adaslink.oracle;

import static org.junit.Assert.assertEquals;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import dev.denza.adaslink.AdasLinkTestAccess;
import dev.denza.adaslink.StockPath;
import dev.denza.adaslink.StockRouteJson;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Real Gson, configured as the stock map's ({@code k.a}), on beans shaped and filled as the stock's
 * ({@code com.byd.noa.plan.data.sdroute}, {@code l.y/r/a/d/o/b}), must print exactly what
 * StockRouteJson prints for the same path. Fields are declared in ART's order (by name).
 */
public class GsonOracleTest {
    static final String[] ROUTES = {
            "captures/yandex-adas-prototype-20261023/route-final-m4/denza-hud-native-route.json",
            "captures/yandex-adas-prototype-20261023/route-final-city/denza-hud-native-route.json",
            "captures/yandex-adas-prototype-20261023/highway-02/denza-hud-native-route.json",
            "captures/yandex-adas-prototype-20261023/route-01/denza-hud-native-route.json"};

    static final class EndPoiInfo {
        @SerializedName("AVPMapIdList") List<Integer> AVPMapIds = new ArrayList<>();
        @SerializedName("PTPStateFlag") int PTPStateFlag = 1;
    }
    static final class MessageSend {
        @SerializedName("code") int code = 200;
        @SerializedName("data") List<NaviToEHPData> data = new ArrayList<>();
        @SerializedName("endPOIInfo") EndPoiInfo endPOIInfo = new EndPoiInfo();
        @SerializedName("mapProvider") int mapProvider = 1;
        @SerializedName("msg") String msg = "response";
        @SerializedName("RouteType") int routeType;
    }
    static final class NaviToEHPData {
        @SerializedName("guideGroups") List<GuideGroup> guideGroups = new ArrayList<>();
        @SerializedName("linkCnt") int linkCnt;
        @SerializedName("links") List<LinkItem> links = new ArrayList<>();
        @SerializedName("pathID") long pathID;
        @SerializedName("pntCnt") int pntCnt;
        @SerializedName("pnts") List<Pnt> pnts = new ArrayList<>();
        @SerializedName("trafficLights") List<Pnt> trafficLights = new ArrayList<>();
        @SerializedName("viaRoadInfo") List<Object> viaRoadInfo = new ArrayList<>();
    }
    static final class Pnt {
        @SerializedName("x") double x;
        @SerializedName("y") double y;
        Pnt(double x, double y) { this.x = x; this.y = y; }
    }
    static final class GuideGroup {
        @SerializedName("groupIconType") int groupIconType;
        @SerializedName("groupLen") int groupLen;
        @SerializedName("groupName") String groupName;
        @SerializedName("groupTime") int groupTime;
        @SerializedName("groupTrafficLightsCount") int groupTrafficLightsCount;
        @SerializedName("groupEnterCoord") Pnt mGroupEnterCoord;
        @SerializedName("segments") List<SegmentItem> segments = new ArrayList<>();
    }
    static final class SegmentItem {
        @SerializedName("crntSegmLinkCnt") int crntSegmLinkCnt;
        @SerializedName("description") String description = "";
        @SerializedName("isArriveWayPoint") boolean isArriveWayPoint;
        @SerializedName("linkBegIdx") long linkBegIdx;
        @SerializedName("navigationAssitAction") int navigationAssitAction;
        @SerializedName("navigationLen") int navigationLen;
        @SerializedName("navigationMainAction") int navigationMainAction;
        @SerializedName("navigationNextRoadName") String navigationNextRoadName = "";
        @SerializedName("trafficLightNum") long trafficLightNum;
    }
    static final class LinkItem {
        @SerializedName("adminCode") int adminCode;
        @SerializedName("assistantAction") int assistantAction;
        @SerializedName("direction") int direction;
        @SerializedName("formway") int formway;
        @SerializedName("hasMixFork") boolean hasMixFork;
        @SerializedName("hasMultiOut") boolean hasMultiOut;
        @SerializedName("hasParallel") boolean hasParallel;
        @SerializedName("hasTrafficLight") boolean hasTrafficLight;
        @SerializedName("laneNum") int laneNum;
        @SerializedName("len") float len;
        @SerializedName("linkID") long linkID;
        @SerializedName("linktype") int linktype;
        @SerializedName("mainAction") int mainAction;
        @SerializedName("ownership") int ownership;
        @SerializedName("pntBegIdx") int pntBegIdx;
        @SerializedName("pntCnt") int pntCnt;
        @SerializedName("roadclass") int roadclass;
        @SerializedName("roadname") String roadname;
        @SerializedName("speedLimit") int speedLimit;
    }

    /** l.f(): the stock's filling order. */
    static MessageSend stock(StockPath path, int routeType) {
        NaviToEHPData d = new NaviToEHPData();
        d.pathID = path.pathId;
        for (double[] t : path.trafficLights) d.trafficLights.add(new Pnt(t[0], t[1]));
        GuideGroup g = new GuideGroup();
        g.groupName = "无名道路";
        for (int i = 0; i < path.segments.size(); i++) {
            StockPath.Segment s = path.segments.get(i);
            g.groupTrafficLightsCount += s.trafficLightNum;
            g.groupTime += s.travelTime;
            g.groupLen += s.length;
            if (i == 0) {
                g.groupIconType = s.mainAction;
                if (!s.links.isEmpty() && s.links.get(0).pointCount() > 0)
                    g.mGroupEnterCoord = new Pnt(s.links.get(0).lon[0] / 3600000.0d, s.links.get(0).lat[0] / 3600000.0d);
            }
            SegmentItem item = new SegmentItem();
            if (i + 1 < path.segments.size() && !path.segments.get(i + 1).links.isEmpty())
                item.navigationNextRoadName = AdasLinkTestAccess.lastRoadName(path.segments.get(i + 1));
            item.crntSegmLinkCnt = s.links.size();
            item.description = AdasLinkTestAccess.description(s, item.navigationNextRoadName);
            item.navigationLen = (int) s.length;
            item.navigationMainAction = s.mainAction;
            item.navigationAssitAction = s.assistantAction;
            item.trafficLightNum = s.trafficLightNum;
            item.linkBegIdx = d.linkCnt;
            item.isArriveWayPoint = s.assistantAction == 35 || s.assistantAction == 36;
            for (StockPath.Link l : s.links) {
                d.linkCnt++;
                LinkItem li = new LinkItem();
                li.linkID = l.topoId; li.roadclass = l.roadClass; li.roadname = l.roadName; li.len = l.length; li.linktype = l.linkType;
                li.formway = l.formway; li.hasTrafficLight = l.hasTrafficLight; li.laneNum = l.laneNum; li.hasMultiOut = l.hasMultiOut;
                li.mainAction = l.mainAction; li.assistantAction = l.assistantAction; li.adminCode = (int) l.adcode;
                li.ownership = l.ownership >= 1 && l.ownership <= 4 ? 1 : l.ownership;
                li.hasParallel = l.hasParallel; li.direction = l.direction; li.speedLimit = 0; li.hasMixFork = l.hasMixFork;
                li.pntBegIdx = d.pntCnt; li.pntCnt = l.pointCount();
                d.links.add(li);
                for (int k = 0; k < l.pointCount(); k++) { d.pntCnt++; d.pnts.add(new Pnt(l.lon[k] / 3600000.0d, l.lat[k] / 3600000.0d)); }
            }
            g.segments.add(item);
        }
        if (!path.segments.isEmpty()) d.guideGroups.add(g);
        MessageSend send = new MessageSend();
        send.data.add(d);
        send.routeType = routeType;
        return send;
    }

    @Test
    public void payloadIsWhatStockGsonPrints() throws Exception {
        File root = new File(System.getProperty("denza.root", "../.."));
        Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
        for (String name : ROUTES) {
            StockPath path = AdasLinkTestAccess.route(root, name);
            assertEquals(name, gson.toJson(stock(path, 1)), StockRouteJson.messageSend(path, 1, StockRouteJson.Strings.EN));
            // And the stock's own model reads it back with no int/null complaint (F3, F4).
            MessageSend back = gson.fromJson(StockRouteJson.messageSend(path, 1, StockRouteJson.Strings.EN), MessageSend.class);
            assertEquals(path.linkCount(), back.data.get(0).links.size());
        }
    }
}
