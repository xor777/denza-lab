package dev.denza.adaslink.stocknative;
import java.util.*;
import java.math.BigInteger;
/** Literal active type1/v1 l.f/y/q/r/a/d/o/b/n/l/j/k/A route getter consumer. */
final class NativeRouteSerializer {
    private final NativeEnvironment env;
    NativeRouteSerializer(NativeEnvironment env){this.env=env;}
    public String A(long j2) {
        String i2;
        StringBuilder sb = new StringBuilder();
        int i3 = (int) j2;
        if (i3 >= 1000) {
            int i4 = i3 / 1000;
            int i5 = (i3 % 1000) / 100;
            if (i4 < 10 && i5 > 0) {
                sb.append(i4);
                sb.append(".");
                sb.append(i5);
            } else {
                sb.append(i4);
            }
            sb.append(' ');
            i2 = "km";
        } else {
            sb.append(i3);
            sb.append(' ');
            i2 = "route_meter";
        }
        sb.append(env.stringResource(i2));
        return sb.toString();
    }

    public GuideGroup a(int i2, int i3, String str, NativePath pathInfo, NaviToEHPData naviToEHPData) {
        List<NativePath.IntPoint> points;
        GuideGroup guideGroup = new GuideGroup();
        guideGroup.groupName = str;
        for (int i4 = 0; i4 < i3; i4++) {
            NativePath.Segment segmentInfo = pathInfo.getSegmentInfo(i2 + i4);
            guideGroup.groupTrafficLightsCount = (int) (((long) guideGroup.groupTrafficLightsCount) + segmentInfo.getTrafficLightNum());
            guideGroup.groupTime = (int) (((long) guideGroup.groupTime) + segmentInfo.getTravelTime());
            guideGroup.groupLen = (int) (((long) guideGroup.groupLen) + segmentInfo.getLength());
            if (i4 == 0) {
                long linkCount = segmentInfo.getLinkCount();
                guideGroup.groupIconType = segmentInfo.getMainAction();
                if (linkCount > 0 && (points = segmentInfo.getLinkInfo(0L).getPoints()) != null && points.size() > 0) {
                    GroupEnterCoord groupEnterCoord = new GroupEnterCoord();
                    guideGroup.mGroupEnterCoord = groupEnterCoord;
                    groupEnterCoord.f2961x = ((double) points.get(0).lon) / 3600000.0d;
                    guideGroup.mGroupEnterCoord.f2962y = ((double) points.get(0).lat) / 3600000.0d;
                }
            }
            guideGroup.segments.add(d(pathInfo, segmentInfo, naviToEHPData));
        }
        return guideGroup;
    }

    public LinkItem b(NativePath.Link linkInfo, int i2) {
        LinkItem linkItem = new LinkItem();
        BigInteger bigInteger = linkInfo.get64TopoID();
        linkItem.linkID = (bigInteger.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0 || bigInteger.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) ? -1L : bigInteger.longValue();
        linkItem.roadclass = linkInfo.getRoadClass();
        linkItem.roadname = linkInfo.getRoadName();
        linkItem.len = linkInfo.getLength();
        linkItem.linktype = linkInfo.getLinkType();
        linkItem.formway = linkInfo.getFormway();
        linkItem.hasTrafficLight = linkInfo.hasTrafficLight();
        linkItem.laneNum = linkInfo.getLaneNum();
        linkItem.hasMultiOut = linkInfo.hasMultiOut();
        linkItem.mainAction = linkInfo.getMainAction();
        linkItem.assistantAction = linkInfo.getAssistantAction();
        linkItem.adminCode = (int) linkInfo.getAdcode();
        int ownership = linkInfo.getOwnership();
        linkItem.ownership = ownership;
        if (ownership >= 1 && ownership <= 4) {
            linkItem.ownership = 1;
        }
        linkItem.hasParallel = linkInfo.hasParallelRoad();
        linkItem.direction = linkInfo.getRoadDirection();
        linkItem.speedLimit = 0;
        linkItem.hasMixFork = linkInfo.hasMixFork();
        List<NativePath.IntPoint> points = linkInfo.getPoints();
        linkItem.pntBegIdx = i2;
        if (points != null) {
            linkItem.pntCnt = points.size();
        }
        return linkItem;
    }

    public SegmentItem d(NativePath pathInfo, NativePath.Segment segmentInfo, NaviToEHPData naviToEHPData) {
        NativePath.Segment segmentInfo2;
        SegmentItem segmentItem = new SegmentItem();
        int segmentIndex = segmentInfo.getSegmentIndex();
        long linkCount = segmentInfo.getLinkCount();
        boolean z2 = true;
        long j2 = segmentIndex + 1;
        if (pathInfo.getSegmentCount() > j2 && (segmentInfo2 = pathInfo.getSegmentInfo(j2)) != null && segmentInfo2.getLinkCount() != 0) {
            segmentItem.navigationNextRoadName = j(segmentInfo2);
        }
        segmentItem.crntSegmLinkCnt = (int) linkCount;
        segmentItem.description = k(segmentInfo, segmentItem.navigationNextRoadName);
        segmentItem.navigationLen = (int) segmentInfo.getLength();
        segmentItem.navigationMainAction = segmentInfo.getMainAction();
        segmentItem.navigationAssitAction = segmentInfo.getAssistantAction();
        segmentItem.trafficLightNum = segmentInfo.getTrafficLightNum();
        segmentItem.linkBegIdx = naviToEHPData.linkCnt;
        int i2 = segmentItem.navigationAssitAction;
        if (i2 != 35 && i2 != 36) {
            z2 = false;
        }
        segmentItem.isArriveWayPoint = z2;
        o(segmentInfo, naviToEHPData);
        return segmentItem;
    }

    public synchronized MessageSend<ArrayList<NaviToEHPData>> f(ArrayList<NativePath> arrayList) {
        ArrayList arrayList2;
        arrayList2 = new ArrayList();
        for (NativePath pathInfo : arrayList) {
            NaviToEHPData naviToEHPDataY = y(pathInfo.getGroupSegmentCount() > 0, pathInfo);
            arrayList2.add(naviToEHPDataY);
        }
        return new MessageSend<>("response", 200, arrayList2);
    }

    public String j(NativePath.Segment segmentInfo) {
        String roadName = "";
        int i2 = 0;
        while (true) {
            long j2 = i2;
            if (j2 >= segmentInfo.getLinkCount()) {
                break;
            }
            NativePath.Link linkInfo = segmentInfo.getLinkInfo(j2);
            if (linkInfo != null) {
                roadName = linkInfo.getRoadName();
            }
            i2++;
        }
        return empty(roadName) ? "无名道路" : roadName;
    }

    public String k(NativePath.Segment segmentInfo, String str) {
        int mainAction = displayAction(segmentInfo.getMainAction(), segmentInfo.getAssistantAction());
        StringBuilder sb = new StringBuilder();
        sb.append(env.stringResource("autonavi_car_result_share_driving"));
        String i2 = "route_browser_fragment_distance_space";
        sb.append(env.stringResource(i2));
        sb.append(A(segmentInfo.getLength()));
        sb.append(env.stringResource(i2));
        sb.append(actionString(mainAction));
        if (empty(str) && mainAction != 11 && mainAction != 12) {
            str = env.stringResource("route_foot_navi_no_name_road");
        }
        if (!empty(str)) {
            sb.append(env.stringResource(i2));
            sb.append(env.stringResource("autonavi_car_result_share_enter"));
            sb.append(env.stringResource(i2));
            sb.append(str);
        }
        return sb.toString();
    }

    public ArrayList<ViaRoadInfoItem> n(NativePath pathInfo) {
        ArrayList<ViaRoadInfoItem> arrayList = new ArrayList<>();
        for (NativePath.ViaRoad viaRoadInfo : pathInfo.getPathViaRoadInfo()) {
            String str = viaRoadInfo.roadName;
            short s2 = viaRoadInfo.maxLimitSpeed;
            short s3 = viaRoadInfo.minLimitSpeed;
            short s4 = viaRoadInfo.minLaneNum;
            short s5 = viaRoadInfo.maxLaneNum;
            int i2 = viaRoadInfo.aggregateLenth;
            short s6 = viaRoadInfo.roadClass;
            NativePath.Point coord2DDouble = viaRoadInfo.point;
            arrayList.add(new ViaRoadInfoItem(str, s2, s3, s4, s5, i2, s6, new ViaRoadInfoItemPos(coord2DDouble.lon, coord2DDouble.lat)));
        }
        return arrayList;
    }

    public void o(NativePath.Segment segmentInfo, NaviToEHPData naviToEHPData) {
        long linkCount = segmentInfo.getLinkCount();
        int i2 = 0;
        while (true) {
            long j2 = i2;
            if (j2 >= linkCount) {
                return;
            }
            NativePath.Link linkInfo = segmentInfo.getLinkInfo(j2);
            if (linkInfo != null) {
                naviToEHPData.linkCnt++;
                naviToEHPData.links.add(b(linkInfo, naviToEHPData.pntCnt));
                for (NativePath.IntPoint coord2DInt32 : Optional.ofNullable(linkInfo.getPoints()).orElse(new ArrayList<NativePath.IntPoint>())) {
                    naviToEHPData.pntCnt++;
                    naviToEHPData.pnts.add(new PntItem(((double) coord2DInt32.lon) / 3600000.0d, ((double) coord2DInt32.lat) / 3600000.0d));
                }
            }
            i2++;
        }
    }

    public void q(NativePath pathInfo, NaviToEHPData naviToEHPData) {
        int groupSegmentCount = (int) pathInfo.getGroupSegmentCount();
        for (int i2 = 0; i2 < groupSegmentCount; i2++) {
            NativePath.Group groupSegment = pathInfo.getGroupSegment(i2);
            naviToEHPData.guideGroups.add(a(groupSegment.startSegmentIndex, groupSegment.segmentCount, groupSegment.roadName, pathInfo, naviToEHPData));
        }
    }

    public void r(NativePath pathInfo, NaviToEHPData naviToEHPData) {
        int segmentCount = (int) pathInfo.getSegmentCount();
        GuideGroup guideGroupA = a(0, segmentCount, "无名道路", pathInfo, naviToEHPData);
        if (segmentCount > 0) {
            naviToEHPData.guideGroups.add(guideGroupA);
        }
    }

    public NaviToEHPData y(boolean z2, NativePath pathInfo) {
        NaviToEHPData naviToEHPData = new NaviToEHPData();
        naviToEHPData.pathID = pathInfo.getPathID();
        naviToEHPData.viaRoadInfo = n(pathInfo);
        naviToEHPData.trafficLights = l(pathInfo);
        if (z2) {
            q(pathInfo, naviToEHPData);
        } else {
            r(pathInfo, naviToEHPData);
        }
        return naviToEHPData;
    }
    public ArrayList<TrafficLights> l(NativePath path){ArrayList<TrafficLights> out=new ArrayList<>();List<NativePath.Point> points=path.getAllTrafficLights();if(points!=null)for(NativePath.Point p:points)out.add(new TrafficLights(p.lon,p.lat));return out;}
    private static boolean empty(String s){return s==null||s.length()==0;}
    static int displayAction(int main,int assistant){if(assistant==5)return 16;switch(assistant){case 33:return 13;case 34:return 14;case 35:return 10;case 36:return 15;default:switch(main){case 1:return 2;case 2:return 3;case 3:case 9:return 4;case 4:case 10:return 5;case 5:return 6;case 6:return 7;case 7:return 8;case 11:return 11;case 12:return 12;default:return 9;}}}
    private String actionString(int action){String name;switch(action){case 2:name="turnleft";break;case 3:name="turnright";break;case 4:name="left_front";break;case 5:name="right_front";break;case 6:name="left_back";break;case 7:name="right_back";break;case 8:name="turn_left_back";break;case 11:name="enter_ring";break;case 12:name="leave_ring";break;case 13:name="along_rest";break;case 14:name="along_charge";break;default:name="along";}return env.stringResource("navi_action_"+name);}

}
