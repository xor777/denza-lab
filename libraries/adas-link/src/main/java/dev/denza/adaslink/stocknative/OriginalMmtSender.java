package dev.denza.adaslink.stocknative;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.denza.adaslink.stocknative.NativeModels.*;
import static dev.denza.adaslink.stocknative.StockRuntime.Queue.*;

/** Literal source-neutral port of k.h.a0.c.c.a.e0 (ADAS type1/v1).
 * Public SDK receipts enqueue the original wrappers; processing methods execute only when dispatched.
 * No Yandex conversion, snapshots of emitted state, adapter scalar or coordinate transform is used. */
public final class OriginalMmtSender {
    private final StockRuntime clock;
    private final NativeEnvironment env;
    private final NativeWire wire;
    private final FontRoundBean f13228b=new FontRoundBean();
    private final RegionalWeatherParam f13229c=new RegionalWeatherParam();
    private final SpeedIntersectionInformationParam f13230d=new SpeedIntersectionInformationParam();
    private final NaviActionAndCameraParam f13231e=new NaviActionAndCameraParam();
    private final GenernalNavigationParam f13232i=new GenernalNavigationParam();
    private final List<TrafficEventBean> f13233l=new ArrayList<>();
    private final TunnelParam f13234q=new TunnelParam();
    private final NaviInfoParam f13235u=new NaviInfoParam();
    private final IntersectionLaneInfoParam f13236x;
    private final List<QueryAppointLanesInfoBean> f13237y=new ArrayList<>();
    private final LightParam v1=new LightParam();
    private final AtomicBoolean v2=new AtomicBoolean(false);
    private final TrafficDataParam G3=new TrafficDataParam();
    private List<LightBarItem> H3;
    private int K3;
    private StatusTask L3;
    private long throttleLast=0, throttlePeriod=1000;

    OriginalMmtSender(StockRuntime clock,NativeEnvironment env,NativeWire wire){this.clock=clock;this.env=env;this.wire=wire;this.f13236x=new IntersectionLaneInfoParam(clock);}
    private static boolean empty(Collection<?> list){return list==null||list.isEmpty();}
    private static boolean textEmpty(String s){return s==null||s.length()==0;}
    private double distance(Coord2DDouble a,Coord2DDouble b){return env.distanceBetweenPoints(a.lon,a.lat,b.lon,b.lat);}
    private void post(String suffix,String purpose,Runnable body){StockTask task=new StockTask("k.h.a0.c.c.a."+suffix,purpose,body);clock.post(MMT,task,null,0,task);}
    private void postReset(){post("e","full reset",this::z);}
    public void initializeThrottle(long last,long period){this.throttleLast=last;this.throttlePeriod=period;}
    private boolean throttle(){long delta=(clock.elapsedRealtime()-throttleLast)-throttlePeriod;if(delta<0)return false;if(delta<50){throttleLast=clock.elapsedRealtime()-delta;return true;}throttleLast=clock.elapsedRealtime();return true;}

    public void B0(List list) {
        clock.processingEnter("k.h.a0.c.c.a.e0.B0", list);
        try {
        if (list == null) {
            return;
        }
        this.H3 = list;

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.B0");}
    }

    public void D0(boolean show,ArrayList events){
        clock.processingEnter("k.h.a0.c.c.a.e0.D0", show, events);
        try {
        f13233l.clear();
        if(show&&!empty(events)){TrafficEventInfo construction=null;
            for(Object raw:events){TrafficEventInfo event=(TrafficEventInfo)raw;f13233l.add(new TrafficEventBean(G0(event.layer,event.type),event.laneId,event.coord2D.lon,event.coord2D.lat));if(event.type==201&&construction==null)construction=event;}
            if(construction!=null)G3.setConstruction(2,0,construction.coord2D.lon,construction.coord2D.lat);else G3.reSetConstruction();
            return;
        }
        wire.k(f13233l);G3.reSetConstruction();

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.D0");}
    }

    public void F(int i2, int i3, Map map) {
        clock.processingEnter("k.h.a0.c.c.a.e0.F", i2, i3, map);
        try {
        ArrayList arrayList = new ArrayList();
        long pathId = this.f13235u.getPathId();
        for (QueryAppointLanesInfoBean queryAppointLanesInfoBean : this.f13237y) {
            int segmentIndex = queryAppointLanesInfoBean.getSegmentIndex();
            boolean z2 = false;
            if (segmentIndex < i2 || (segmentIndex == i2 && queryAppointLanesInfoBean.getLinkIndex() <= i3)) {
                if (segmentIndex > this.f13235u.getCurSegIdx() || (segmentIndex == this.f13235u.getCurSegIdx() && queryAppointLanesInfoBean.getLinkIndex() >= this.f13235u.getCurLinkIdx())) {
                    z2 = true;
                }
                boolean zEquals = Objects.equals((Long) map.getOrDefault(Long.valueOf(queryAppointLanesInfoBean.getRequestId()), 0L), Long.valueOf(pathId));
                if (z2 && zEquals) {
                    if (arrayList.size() >= 6) {
                        break;
                    }
                    queryAppointLanesInfoBean.setPathId(pathId);
                    arrayList.add(queryAppointLanesInfoBean);
                }
            }
        }
        wire.i(arrayList);
        this.f13237y.clear();

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.F");}
    }

    public void H() {
        clock.processingEnter("k.h.a0.c.c.a.e0.H");
        try {
        this.G3.reSetTrafficJam();
        this.G3.reSetConstruction();
        this.v1.reSetLightLocationParam();
        this.f13235u.getTollGateLaneTypes().clear();
        this.f13230d.reSetIntersectionInfoParam();

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.H");}
    }

    public void J(int i2) {
        clock.processingEnter("k.h.a0.c.c.a.e0.J", i2);
        try {
        this.G3.setCurrentRoadSpeed(Math.max(i2, 0));

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.J");}
    }

    public void L(double d2, double d3) {
        clock.processingEnter("k.h.a0.c.c.a.e0.L", d2, d3);
        try {
        this.f13234q.setTunnelParam(0, 2, d2, d3, 0.0d, 0.0d);
        wire.r(this.f13234q);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.L");}
    }

    public void N(double d2, double d3) {
        clock.processingEnter("k.h.a0.c.c.a.e0.N", d2, d3);
        try {
        this.f13234q.setTunnelParam(0, this.K3 == 6 ? 2 : 4, 0.0d, 0.0d, d2, d3);
        wire.r(this.f13234q);
        this.f13234q.clearTunnelParam();

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.N");}
    }

    public void P(LocInfo locInfo) {
        clock.processingEnter("k.h.a0.c.c.a.e0.P", locInfo);
        try {
        char c2;
        if (locInfo == null) {
            return;
        }
        if (locInfo.isSimulate == 1) {
            return;
        }
        int i2 = (int) locInfo.speed;
        int i3 = locInfo.sourType;
        float f2 = locInfo.alt;
        ArrayList<LocMatchInfo> arrayList = locInfo.matchInfo;
        int iRealSpeed = (int) env.realSpeed();
        Coord3DDouble coord3DDouble = locInfo.gpsPos;
        if (empty(arrayList)) {
            return;
        }
        LocMatchInfo locMatchInfo = arrayList.get(0);
        Coord3DDouble coord3DDouble2 = locMatchInfo.stPos;
        double d2 = coord3DDouble2.lon;
        double d3 = coord3DDouble2.lat;
        this.v2.set(locMatchInfo.linkType == 2);
        int i4 = (int) locMatchInfo.course;
        float f3 = locMatchInfo.roadCourse;
        int i5 = locMatchInfo.roadClass;
        long j2 = locMatchInfo.linkId;
        int i6 = locMatchInfo.formway;
        int i7 = locMatchInfo.linkType;
        float f4 = locMatchInfo.roadLen;
        double d4 = d3;
        double d5 = d2;
        C(d2, d4, i5, this.G3.getRoadClass(), j2, this.G3.getLinkId());
        NaviInfoParam naviInfoParam = this.f13235u;
        int i8 = locMatchInfo.ownership;
        if (i8 == Integer.MIN_VALUE) {
            i8 = -1;
        }
        naviInfoParam.setOwnership(i8);
        this.G3.setRoadClass(i5);
        this.G3.setLinkId(j2);
        if (throttle()) {
            wire.h(this.f13229c.getRegionId(), this.f13229c.getCountryCode(), this.f13229c.getProvinceCode(), this.f13229c.getCityCode(), this.f13229c.getWeatherType());
            this.f13229c.setWeatherType(0);
            if (env.isKD()) {
                d4 = 0.0d;
                d5 = 0.0d;
            }
            H0(i2, iRealSpeed, coord3DDouble, d5, d4, i4);
            v(d5, d4, iRealSpeed, this.G3);
            c2 = 0;
            this.G3.setOffCourse(false);
        } else {
            c2 = 0;
        }
        Object[] objArr = new Object[13];
        objArr[c2] = Integer.valueOf(i5);
        objArr[1] = Integer.valueOf(i6);
        objArr[2] = Integer.valueOf(i7);
        objArr[3] = Integer.valueOf(i3);
        objArr[4] = Integer.valueOf(i2);
        objArr[5] = Double.valueOf(d5);
        objArr[6] = Double.valueOf(d4);
        objArr[7] = Double.valueOf(coord3DDouble.lon);
        objArr[8] = Double.valueOf(coord3DDouble.lat);
        objArr[9] = Float.valueOf(f2);
        objArr[10] = Integer.valueOf(i4);
        objArr[11] = Float.valueOf(f3);
        objArr[12] = Float.valueOf(f4);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.P");}
    }

    public void R() {
        clock.processingEnter("k.h.a0.c.c.a.e0.R");
        try {
        StatusTask dVar = this.L3;
        if (dVar != null) {
            dVar.a((short) 1);
        }

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.R");}
    }

    public void T(int i2) {
        clock.processingEnter("k.h.a0.c.c.a.e0.T", i2);
        try {
        this.f13234q.setTunnelParam(i2, 1, 0.0d, 0.0d, 0.0d, 0.0d);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.T");}
    }

    public void V(int i2) {
        clock.processingEnter("k.h.a0.c.c.a.e0.V", i2);
        try {
        this.f13234q.setTunnelParam(i2, 3, 0.0d, 0.0d, 0.0d, 0.0d);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.V");}
    }

    public void X(SoundInfo soundInfo) {
        clock.processingEnter("k.h.a0.c.c.a.e0.X", soundInfo);
        try {
        if (soundInfo == null) {
            return;
        }
        String str = soundInfo.text;
        if (textEmpty(str)) {
            return;
        }
        boolean zF = NativeDataHelper.f(str);
        this.f13230d.setNaviVoiceCommand(soundInfo.text);
        if (zF) {
            this.f13230d.setHasLongSolidLane(1);
            int iH = NativeDataHelper.h(str);
            if (iH > 0 && iH < 1500) {
                this.f13230d.setRecordDist(this.G3.getDistToDsttn());
                this.f13230d.setFinalmeter(iH);
            }
        }

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.X");}
    }

    public void Z(ArrayList arrayList, long j2) {
        clock.processingEnter("k.h.a0.c.c.a.e0.Z", arrayList, j2);
        try {
        QueryAppointLanesInfoBean queryAppointLanesInfoBean;
        ArrayList<Integer> arrayList2;
        ArrayList<Integer> arrayList3;
        ArrayList<Integer> arrayList4;
        ArrayList<Integer> arrayList5;
        ArrayList<Integer> arrayList6;
        ArrayList<Integer> arrayList7;
        int i2;
        int i3;
        QueryAppointLanesInfoBean queryAppointLanesInfoBean2;
        long j3;
        long jCurrentTimeMillis = clock.currentTimeMillis();
        Iterator it = arrayList.iterator();
        while (it.hasNext()) {
            LaneInfo laneInfo = (LaneInfo) it.next();
            QueryAppointLanesInfoBean queryAppointLanesInfoBean3 = new QueryAppointLanesInfoBean(clock);
            if (laneInfo.extensionLane.size() == 0) {
                arrayList2 = laneInfo.backLane;
                arrayList3 = laneInfo.frontLane;
                arrayList4 = new ArrayList<>(laneInfo.backExtenLane);
                arrayList5 = laneInfo.optimalLane;
                arrayList6 = laneInfo.backLaneType;
                arrayList7 = laneInfo.frontLaneType;
                i2 = laneInfo.segmentIdx;
                i3 = laneInfo.linkIdx;
                queryAppointLanesInfoBean2 = queryAppointLanesInfoBean3;
                j3 = jCurrentTimeMillis;
                queryAppointLanesInfoBean = queryAppointLanesInfoBean3;
            } else {
                queryAppointLanesInfoBean = queryAppointLanesInfoBean3;
                arrayList2 = laneInfo.backLane;
                arrayList3 = laneInfo.frontLane;
                arrayList4 = laneInfo.extensionLane;
                arrayList5 = laneInfo.optimalLane;
                arrayList6 = laneInfo.backLaneType;
                arrayList7 = laneInfo.frontLaneType;
                i2 = laneInfo.segmentIdx;
                i3 = laneInfo.linkIdx;
                queryAppointLanesInfoBean2 = queryAppointLanesInfoBean;
                j3 = jCurrentTimeMillis;
            }
            queryAppointLanesInfoBean2.setLaneParam(arrayList2, arrayList3, arrayList4, arrayList5, arrayList6, arrayList7, i2, i3, j3, j2);
            this.f13237y.add(queryAppointLanesInfoBean);
        }

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.Z");}
    }

    public void b0(RouteOption routeOption) {
        clock.processingEnter("k.h.a0.c.c.a.e0.b0", routeOption);
        try {
        this.G3.reSetTrafficJam();
        this.G3.reSetConstruction();
        this.v1.reSetLightLocationParam();
        this.f13235u.getTollGateLaneTypes().clear();
        this.f13230d.reSetIntersectionInfoParam();
        this.G3.setOffCourse(routeOption.getRouteType() == 2);
        this.f13233l.clear();
        if (routeOption.getRouteType() == 2) {
            this.f13234q.clearTunnelParam();
            wire.r(this.f13234q);
        }

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.b0");}
    }

    public void d0() {
        clock.processingEnter("k.h.a0.c.c.a.e0.d0");
        try {
        this.G3.reSetTrafficJam();
        this.G3.reSetConstruction();
        this.v1.reSetLightLocationParam();
        this.f13235u.getTollGateLaneTypes().clear();
        this.f13230d.reSetIntersectionInfoParam();

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.d0");}
    }

    public void f0(List list, boolean z2) {
        clock.processingEnter("k.h.a0.c.c.a.e0.f0", list, z2);
        try {
        TrafficDataParam trafficDataParam;
        double d2;
        double d3;
        int i2;
        int i3;
        double d4;
        double d5;
        if (list == null) {
            return;
        }
        if (z2) {
            NaviCameraExt naviCameraExt = (NaviCameraExt) list.get(0);
            this.f13231e.setCameraType(NativeDataHelper.g(((NaviCameraExt) list.get(0)).subCameras));
            this.f13231e.setCameraDistance(naviCameraExt.distance);
            wire.f(this.f13231e.getIconType(), this.f13231e.getMainAction(), this.f13231e.getAssistantAction(), this.f13231e.getNaviDistance(), this.f13231e.getCameraType(), this.f13231e.getCameraDistance());
            List<NaviCameraExt> listA = NativeDataHelper.a(list, 2000);
            if (empty(listA)) {
                this.G3.intervalCameraStartLonAndLat();
                this.G3.reSetCameraSpeedParam();
            } else {
                NaviCameraExt naviCameraExt2 = listA.get(0);
                Coord2DDouble coord2DDouble = naviCameraExt2.coord2D;
                int i4 = naviCameraExt2.distance;
                NaviSubCameraExt naviSubCameraExt = naviCameraExt2.subCameras.get(0);
                short sShortValue = ((Short) Collections.max(naviSubCameraExt.speed)).shortValue();
                int i5 = naviSubCameraExt.subType;
                boolean z3 = naviSubCameraExt.isMatch;
                if (i5 == 25) {
                    if (z3) {
                        this.G3.setIntervalCameraSpeedParam(sShortValue, i4, coord2DDouble.lon, coord2DDouble.lat, 0.0d, 0.0d, 0);
                        this.f13232i.setIntervalCameraSpeedDisttoStart(i4);
                        wire.c(this.f13232i.getIntervalCameraSpeedDisttoStart(), 0, Integer.valueOf(this.f13232i.getNewNoODDRegionDist()));
                    } else {
                        trafficDataParam = this.G3;
                        d2 = 0.0d;
                        d3 = 0.0d;
                        d4 = 0.0d;
                        d5 = 0.0d;
                        i2 = 0;
                        i3 = i4;
                        trafficDataParam.setIntervalCameraSpeedParam(sShortValue, i3, d2, d3, d4, d5, i2);
                    }
                } else if (i5 == 26) {
                    trafficDataParam = this.G3;
                    d2 = 0.0d;
                    d3 = 0.0d;
                    i2 = 0;
                    i3 = i4;
                    d4 = coord2DDouble.lon;
                    d5 = coord2DDouble.lat;
                    trafficDataParam.setIntervalCameraSpeedParam(sShortValue, i3, d2, d3, d4, d5, i2);
                } else {
                    this.G3.setCameraSpeedParam(sShortValue, i4);
                }
            }
        } else {
            this.G3.reSetCameraSpeedParam();
            this.G3.reSetIntervalCameraSpeed();
            this.f13231e.reSetNaviActionAndCameraParam();
        }

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.f0");}
    }

    public void h0(boolean z2, ArrayList arrayList, ArrayList arrayList2, ArrayList arrayList3, ArrayList arrayList4, ArrayList arrayList5, ArrayList arrayList6, int i2, int i3, ArrayList arrayList7) {
        clock.processingEnter("k.h.a0.c.c.a.e0.h0", z2, arrayList, arrayList2, arrayList3, arrayList4, arrayList5, arrayList6, i2, i3, arrayList7);
        try {
        ArrayList arrayList8;
        int i4;
        int i5;
        ArrayList arrayList9;
        ArrayList arrayList10;
        ArrayList arrayList11;
        ArrayList arrayList12;
        ArrayList arrayList13;
        ArrayList arrayList14;
        IntersectionLaneInfoParam intersectionLaneInfoParam;
        long jCurrentTimeMillis = clock.currentTimeMillis();
        if (!z2 || arrayList == null || arrayList2 == null) {
            arrayList8 = new ArrayList();
            this.f13236x.setLaneDist(-1);
            i4 = -1;
            i5 = -1;
            arrayList9 = arrayList8;
            arrayList10 = arrayList8;
            arrayList11 = arrayList8;
            arrayList12 = arrayList8;
            arrayList13 = arrayList8;
            arrayList14 = arrayList8;
            intersectionLaneInfoParam = this.f13236x;
        } else {
            arrayList9 = arrayList;
            arrayList10 = arrayList2;
            arrayList11 = arrayList3;
            arrayList12 = arrayList4;
            arrayList13 = arrayList5;
            arrayList14 = arrayList6;
            i4 = i2;
            i5 = i3;
            arrayList8 = arrayList7;
            intersectionLaneInfoParam = this.f13236x;
        }
        intersectionLaneInfoParam.setLaneParam(arrayList9, arrayList10, arrayList11, arrayList12, arrayList13, arrayList14, i4, i5, jCurrentTimeMillis, arrayList8);
        wire.g(this.f13236x);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.h0");}
    }

    public void i0(boolean z2, ArrayList arrayList) {
        clock.processingEnter("k.h.a0.c.c.a.e0.i0", z2, arrayList);
        try {
        if (!z2 || arrayList == null) {
            wire.j(0, 0, 0, 0.0d, 0.0d);
            return;
        }
        NaviRoadFacility naviRoadFacility = (NaviRoadFacility) arrayList.get(0);
        int i2 = naviRoadFacility.type;
        int iCalcDistanceBetweenPoints = naviRoadFacility.distance;
        Coord2DDouble coord2DDouble = naviRoadFacility.coord2D;
        Coord2DDouble lastLocation = env.lastLocation();
        if (lastLocation != null) {
            iCalcDistanceBetweenPoints = (int) distance(new Coord2DDouble(lastLocation.lon, lastLocation.lat), coord2DDouble);
        }
        wire.j(iCalcDistanceBetweenPoints, i2, 0, coord2DDouble.lon, coord2DDouble.lat);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.i0");}
    }

    public void k0(ArrayList arrayList) {
        clock.processingEnter("k.h.a0.c.c.a.e0.k0", arrayList);
        try {
        if (empty(arrayList)) {
            this.f13229c.setWeatherType(0);
        } else {
            this.f13229c.setWeatherType(((WeatherInfo) arrayList.get(0)).weatherID);
        }

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.k0");}
    }

    public void l0(ArrayList arrayList) {
        clock.processingEnter("k.h.a0.c.c.a.e0.l0", arrayList);
        try {
        NativeWire f0VarB;
        ArrayList<MixForkInfoParam> arrayList2;
        if (empty(arrayList)) {
            f0VarB = wire;
            arrayList2 = new ArrayList<>();
        } else {
            arrayList2 = new ArrayList<>();
            Iterator it = arrayList.iterator();
            while (it.hasNext()) {
                MixForkInfo mixForkInfo = (MixForkInfo) it.next();
                MixForkInfoParam mixForkInfoParam = new MixForkInfoParam();
                mixForkInfoParam.setDistance(mixForkInfo.dist);
                mixForkInfoParam.setLon(mixForkInfo.pos.lon);
                mixForkInfoParam.setLat(mixForkInfo.pos.lat);
                mixForkInfoParam.setSegmentIndex(mixForkInfo.segmentIndex);
                arrayList2.add(mixForkInfoParam);
            }
            f0VarB = wire;
        }
        f0VarB.n(arrayList2);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.l0");}
    }

    public void n0(boolean z2, List list) {
        clock.processingEnter("k.h.a0.c.c.a.e0.n0", z2, list);
        try {
        this.f13235u.getTollGateLaneTypes().clear();
        if (!z2 || list == null) {
            return;
        }
        this.f13235u.getTollGateLaneTypes().addAll(list);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.n0");}
    }

    public void r0(CruiseRoadSpeed cruiseRoadSpeed) {
        clock.processingEnter("k.h.a0.c.c.a.e0.r0", cruiseRoadSpeed);
        try {
        this.G3.setCurrentRoadSpeed(Math.max(cruiseRoadSpeed.speed, 0));

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.r0");}
    }

    public void t0(boolean z2, ArrayList arrayList, int i2) {
        clock.processingEnter("k.h.a0.c.c.a.e0.t0", z2, arrayList, i2);
        try {
        if (!z2 || arrayList == null) {
            this.G3.reSetCameraSpeedParam();
            this.G3.reSetIntervalCameraSpeed();
        } else {
            this.G3.setIntervalCameraSpeed(((Short) Collections.max(arrayList)).shortValue(), 0, i2);
        }
        this.f13232i.setIntervalCameraSpeedDisttoEnd(i2);
        wire.c(0, this.f13232i.getIntervalCameraSpeedDisttoEnd(), Integer.valueOf(this.f13232i.getNewNoODDRegionDist()));

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.t0");}
    }

    public void v0(NaviInfo naviInfo, NativePath pathInfo) {
        clock.processingEnter("k.h.a0.c.c.a.e0.v0", naviInfo, pathInfo);
        try {
        NativePath.Link linkInfo;
        if (naviInfo == null || pathInfo == null) {
            return;
        }
        long j2 = naviInfo.pathID;
        TimeAndDist timeAndDist = naviInfo.routeRemain;
        int i2 = naviInfo.routeRemainLightCount;
        int i3 = naviInfo.linkRemainDist;
        int i4 = naviInfo.curSegIdx;
        int i5 = naviInfo.curLinkIdx;
        int i6 = naviInfo.curPointIdx;
        int i7 = naviInfo.crossManeuverID;
        ArrayList<NaviInfoPanel> arrayList = naviInfo.NaviInfoData;
        int i8 = !empty(arrayList) ? arrayList.get(naviInfo.NaviInfoFlag).segmentRemain.dist : 0;
        NativeLookahead.update(this.f13228b, pathInfo, this.v2.get(), i4, i5, i6, i3, i2, this.f13236x.getSegmentIndex(), this.f13236x.getLinkIndex());
        NativePath.Segment segmentInfo = pathInfo.getSegmentInfo(i4);
        if (segmentInfo == null || (linkInfo = segmentInfo.getLinkInfo(i5)) == null) {
            return;
        }
        if (this.f13235u.getPathId() != j2) {
            J0(pathInfo);
        }
        boolean zHasParallelRoad = linkInfo.hasParallelRoad();
        this.f13230d.setHasMixFork(linkInfo.hasMixFork() ? 1 : 2);
        this.f13230d.setHasParallelRoad(zHasParallelRoad ? 1 : 2);
        int mainAction = linkInfo.getMainAction();
        if (mainAction == 0) {
            segmentInfo.getMainAction();
        }
        int assistantAction = linkInfo.getAssistantAction();
        if (assistantAction == 0) {
            assistantAction = segmentInfo.getAssistantAction();
        }
        this.f13231e.setNaviDistance(i8);
        this.f13231e.setNaviActionParam(i7, mainAction, assistantAction, i8);
        this.f13236x.setLaneDist(this.f13228b.getDstLinkDistance());
        this.f13235u.setNaviInfoParam(i8, j2, i4, i5, i3);
        this.G3.setDestTimeAndDist(timeAndDist.dist, timeAndDist.time);
        y(timeAndDist);
        this.v1.setLightLocationParam(this.f13228b.getDstLinkDistance(), this.f13228b.getLightLocation()[0], this.f13228b.getLightLocation()[1]);
        this.f13230d.setHistorySpeed(naviInfo.curLinkSpeed);
        this.f13231e.setIconType(naviInfo.crossManeuverID);
        env.setLeaveTunnelDist(this.f13228b.getLeaveTunnelDist());
        if (this.f13228b.getEnterTunnelDist() < 2000) {
            env.setEnterTunnelDist(this.f13228b.getEnterTunnelDist());
        }
        wire.p(this.f13228b.getToRampDist(), this.f13236x.getTidalLane());
        if (this.f13236x.getBackLane().size() > 0 && this.f13236x.getSegmentIndex() == i4) {
            wire.g(this.f13236x);
        }
        wire.f(this.f13231e.getIconType(), this.f13231e.getMainAction(), this.f13231e.getAssistantAction(), this.f13231e.getNaviDistance(), this.f13231e.getCameraType(), this.f13231e.getCameraDistance());
        wire.o(this.f13230d.getTrafficFlowSpeed(), this.f13230d.getHistorySpeed(), this.f13230d.getHasParallelRoad(), this.f13230d.getHasMixFork(), this.f13230d.getHasLongSolidLane(), this.f13230d.getDistance(), this.f13230d.getNaviVoiceCommand());
        this.f13230d.setNaviVoiceCommand("");

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.v0");}
    }

    public void x0(boolean z2, ArrayList arrayList) {
        clock.processingEnter("k.h.a0.c.c.a.e0.x0", z2, arrayList);
        try {
        int i2;
        int i3;
        int i4;
        String str;
        int i5;
        ArrayList arrayList2 = new ArrayList();
        int i6 = 0;
        int i7 = 1;
        if (!z2 || arrayList == null) {
            i2 = 0;
            i3 = 0;
            i4 = 1;
            str = "";
            i5 = 0;
        } else {
            Iterator it = arrayList.iterator();
            int i8 = 0;
            int i9 = 0;
            int i10 = 1;
            int i11 = 1;
            String str2 = "";
            int i12 = 0;
            while (it.hasNext()) {
                NaviFacility naviFacility = (NaviFacility) it.next();
                int i13 = naviFacility.type;
                if (i13 == 0) {
                    i9++;
                    if (i9 == 1) {
                        i11 = 2;
                        str2 = naviFacility.name;
                        i8 = naviFacility.remainDist;
                    } else {
                        if (i9 == 2) {
                            i12 = naviFacility.remainDist;
                        }
                        i11 = 2;
                    }
                } else if (i13 == 1 && i10 != 2) {
                    i6 = naviFacility.remainDist;
                    arrayList2.addAll(this.f13235u.getTollGateLaneTypes());
                    i10 = 2;
                }
            }
            i3 = i6;
            str = str2;
            i7 = i11;
            i4 = i10;
            i2 = i12;
            i5 = i8;
        }
        wire.m(i7, i5, str, 0, i2, i4, i3, arrayList2);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.x0");}
    }

    public void z0(boolean z2, List list, long j2, long j3) {
        clock.processingEnter("k.h.a0.c.c.a.e0.z0", z2, list, j2, j3);
        try {
        int i2;
        if (!z2) {
            this.G3.reSetTrafficJam();
        } else if (!empty(this.H3) && list != null) {
            NaviCongestionDetailInfo naviCongestionDetailInfo = (NaviCongestionDetailInfo) list.get(0);
            int i3 = naviCongestionDetailInfo.scopeFlag;
            int i4 = naviCongestionDetailInfo.status;
            if (i3 != 1) {
                i2 = 0;
                for (LightBarItem lightBarItem : this.H3) {
                    if (lightBarItem.status >= 2) {
                        break;
                    } else {
                        i2 += lightBarItem.length;
                    }
                }
            } else {
                i2 = 0;
            }
            this.G3.setTrafficJam((int) j2, i2, i4, (int) j3);
        }

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.z0");}
    }

    public final int G0(int i2, int i3) {
        if (i2 == 1055) {
            return 1;
        }
        if (i2 == 1050) {
            return 2;
        }
        if (i2 == 1065) {
            return 3;
        }
        if (i2 == 1070 || i2 == 1060) {
            return 4;
        }
        if (i2 == 1075) {
            if (i3 >= 401 && i3 <= 418) {
                return 5;
            }
            if (i3 >= 501 && i3 <= 512) {
                return 6;
            }
            if (i3 >= 701 && i3 <= 705) {
                return 8;
            }
        } else if (i2 == 1080) {
            if (i3 >= 601 && i3 <= 607) {
                return 7;
            }
            if (i3 >= 901 && i3 <= 903) {
                return 9;
            }
        } else if (i2 == 1085) {
            if (i3 == 101302 || i3 == 102302) {
                return 10;
            }
            if (i3 == 201302) {
                return 11;
            }
            if (i3 == 404302 || i3 == 406302 || i3 == 409302 || i3 == 410302) {
                return 12;
            }
            if (i3 == 501302 || i3 == 502302 || i3 == 503302 || i3 == 504302) {
                return 13;
            }
        } else if (i2 == 1076) {
            return 2;
        }
        return 255;
    }

    public final void v(double d2, double d3, int i2, TrafficDataParam trafficDataParam) {
        if (trafficDataParam.getCnstrctnCrdLon() == 0.0d || trafficDataParam.getCnstrctnCrdLat() == 0.0d) {
            return;
        }
        double dW = w(d2, d3, trafficDataParam);
        if ((dW >= 26.82d || i2 > 60) && ((dW >= 40.23d || i2 <= 60 || i2 > 90) && (dW >= 53.64d || i2 <= 90))) {
            return;
        }
        trafficDataParam.reSetConstruction();
    }

    public final double w(double d2, double d3, TrafficDataParam trafficDataParam) {
        return distance(new Coord2DDouble(d2, d3), new Coord2DDouble(trafficDataParam.getCnstrctnCrdLon(), trafficDataParam.getCnstrctnCrdLat()));
    }

    public final void y(TimeAndDist timeAndDist) {
        if (this.f13230d.getHasLongSolidLane() == 1) {
            int recordDist = this.f13230d.getRecordDist() - timeAndDist.dist;
            int finalmeter = this.f13230d.getFinalmeter() - recordDist;
            if (finalmeter > 0) {
                if (finalmeter < 1500) {
                    this.f13230d.setDistance(finalmeter);
                    return;
                } else {
                    this.f13230d.setDistance(-1);
                    return;
                }
            }
            if (this.f13230d.getFinalmeter() - recordDist > 0) {
                return;
            } else {
                this.f13230d.setHasLongSolidLane(2);
            }
        }
        this.f13230d.setDistance(-1);
        this.f13230d.setFinalmeter(0);
        this.f13230d.setRecordDist(0);
    }

    public final void z() {
        clock.processingEnter("k.h.a0.c.c.a.e0.z");
        try {
        this.f13236x.reSetNextIntersectionLaneInfoParam();
        this.f13235u.reSetNaviInfo();
        this.G3.reSetTrafficParam();
        List<LightBarItem> list = this.H3;
        if (list != null) {
            list.clear();
        }
        this.v1.reSetLightLocationParam();
        this.f13232i.reSetGenernalNavigationParam();
        this.f13231e.reSetNaviActionAndCameraParam();
        this.f13230d.reSetSpeedIntersectionInformationParam();
        this.f13237y.clear();
        this.f13234q.clearTunnelParam();
        wire.r(this.f13234q);
        this.f13233l.clear();
        wire.k(this.f13233l);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.z");}
    }

    public void I0(int i2) {
        if (this.K3 == 6) {
            return;
        }
        this.K3 = i2;
    }

    public void C(double lon,double lat,int roadClass,int oldClass,long linkId,long oldId){
        if(lon==0 || lat==0 || !env.hasMapDataService())return;
        if(roadClass==oldClass && linkId==oldId && f13229c.getProvinceCode()!=0)return;
        int adcode=env.getAdcodeByLonLat(lon,lat);int town=env.getTownAdcodeByLonLat(lon,lat);Integer province=env.getCityProvince(adcode);
        if(province!=null)f13229c.setRegionParam(town,province,adcode,1);
    }
    public void F0(boolean show,long path,long current,TrafficLightState state,Coord2DDouble point,int direction,int wait){
        clock.processingEnter("k.h.a0.c.c.a.e0.F0", show, path, current, state, point, direction, wait);
        try {
        if(!show){v1.setLightParam(1,0,0,0,0,0,0,0);return;}
        if(path==current){int type=state==null?0:state.type;long begin=state==null?0:state.stime,end=state==null?0:state.etime;
            int value=type==2?1:type==3?2:type==4?3:type;v1.setLightParam(2,point.lon,point.lat,value,begin,end,direction,wait);}

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.F0");}
    }
    public void p0(CruisePredictiveTunnelInfo p){
        clock.processingEnter("k.h.a0.c.c.a.e0.p0", p);
        try {
        int state=p.state;if(state==1||state==2)f13234q.setTunnelParam(p.entranceDistance,state,0,0,0,0);
        else if(state==3||state==4)f13234q.setTunnelParam(p.exitDistance,K3==6?2:state,0,0,0,0);
        else f13234q.setTunnelParam(-1,-1,0,0,0,0);

        } finally {clock.processingExit("k.h.a0.c.c.a.e0.p0");}
    }
    private void sendBundle(Coord3DDouble raw,double lon,double lat,int real,int gps,int course,boolean includeTunnel){
        wire.s(raw.lon,raw.lat,lon,lat,real,gps,course,f13235u.getPathId(),f13235u.getCurSegIdx(),f13235u.getCurLinkIdx(),f13235u.getCurSegDist(),f13235u.getLinkRemainDist(),f13235u.getOwnership());
        wire.q(G3.getTrafficJamLength(),G3.getDistToStartTrafficJam(),G3.getTrafficJamStatus(),G3.getPassTime(),G3.getCnstrctnRmnd(),G3.getCnstrctnCrdLon(),G3.getCnstrctnCrdLat(),G3.getSpdLmtSpeedValue(),G3.getSpdLmtEleEyeSpeedValue(),G3.getSpdLmtEleEyeDist(),G3.getIntervalCameraStartLon(),G3.getIntervalCameraStartLat(),G3.getIntervalCameraEndLon(),G3.getIntervalCameraEndLat(),G3.getIntervalCameraSpeedValue(),G3.getOffCourse(),G3.getDistToDsttn(),G3.getTimeToDsttn(),G3.getRoadClass());
        if(includeTunnel)wire.r(f13234q);
        if(!f13233l.isEmpty())wire.k(f13233l);
        if((v1.getDistance()<500||v1.getStartTime()>0)&&env.isNaving())wire.l(v1.getLightExist(),v1.getLon(),v1.getLat(),v1.getLightState(),v1.getStartTime(),v1.getEndTime(),v1.getDirection(),v1.getWaitNum(),v1.getDistance());
    }
    public void H0(int gps,int real,Coord3DDouble raw,double lon,double lat,int course){
        clock.removeToken(MMT,"MSG_TOKEN_SEG");
        if(real==0||gps==0){
            final StockTask[] self=new StockTask[1];self[0]=new StockTask("k.h.a0.c.c.a.e0$a","stationary location",()->{sendBundle(raw,lon,lat,real,gps,course,false);clock.post(MMT,self[0],"MSG_TOKEN_SEG",1000,self[0]);});
            clock.post(MMT,self[0],"MSG_TOKEN_SEG",0,self[0]);return;
        }
        sendBundle(raw,lon,lat,real,gps,course,true);
    }
    private final class StatusTask implements Runnable {
        volatile int state;short arrivals;final StockTask key;
        StatusTask(int state){this.state=state;this.key=new StockTask("k.h.a0.c.c.a.e0$d","navigation status",this);}
        void a(short value){arrivals=value;}
        @Override public void run(){int out;if(env.isNaving()){arrivals=0;out=101;}else if(arrivals<=0||arrivals>3){arrivals=0;out=state;}else{arrivals=(short)(arrivals+1);out=110;}wire.e(out);clock.post(MMT,key,null,1000,key);}
    }
    public void p(int state){if(L3==null)L3=new StatusTask(state);L3.state=state;clock.removeCallbacks(MMT,L3.key);clock.post(MMT,L3.key,null,0,L3.key);}
    public void B(int segment,int link,Map<Long,Long> requests){post("l","lane query result flush",()->F(segment,link,requests));}
    public void J0(NativePath path){
        clock.removeToken(MAIN,"MSG_TOKEN_QUERY_LANES");clock.removeToken(MMT,"MSG_TOKEN_QUERY_LANES");
        final StockTask[] task=new StockTask[1];task[0]=new StockTask("k.h.a0.c.c.a.e0$b","query ahead lanes",()->{
            if(path==null||!path.isValid())return;Map<Long,Long> requests=new ConcurrentHashMap<>();int[]font=f13228b.getFont3kmIdx();if(font[0]==0&&font[1]==0)return;
            int segment=font[0],link=font[1];long count=path.getSegmentCount();int cur=f13235u.getCurSegIdx();
            while((long)cur<count){NativePath.Segment s=path.getSegmentInfo(cur);if(s==null)return;if(cur>segment)break;
                int queryLink=cur!=f13235u.getCurSegIdx()?0:f13235u.getCurLinkIdx();
                int querySegment=s.getSegmentIndex();
                requests.put(env.queryAppointLanesInfo(querySegment,queryLink,6),path.getPathID());cur++;}
            clock.removeToken(MMT,"MSG_TOKEN_QUERY_LANES");StockTask flush=new StockTask("k.h.a0.c.c.a.a","post lane query result",()->B(segment,link,requests));
            clock.post(MMT,flush,"MSG_TOKEN_QUERY_LANES",2000,flush);clock.post(MAIN,task[0],"MSG_TOKEN_QUERY_LANES",10000,task[0]);
        });clock.post(MAIN,task[0],"MSG_TOKEN_QUERY_LANES",0,task[0]);
    }
    // PUBLIC SDK receipts. They enqueue literal wrapper instances instead of replaying P/v0 twice.
    public void onLocInfoUpdate(LocInfo info){post("c","location callback",()->P(info));}
    public void l(NaviInfo info,NativePath path){post("b","navi callback",()->v0(info,path));}
    public void t(RouteCarResultData r){if(r!=null){r.getFocusIndex();r.getPathInfos();}}
    public void d(){postReset();}
    public void onCruiseStart(){postReset();}
    public void onCruiseStop(){postReset();}
    public void onNaviSimStart(){postReset();}
    public void onNavStop(){postReset();clock.removeToken(MAIN,"MSG_TOKEN_QUERY_LANES");}
    public void onChangeNaviPath(long oldPath,long path){post("f","path change reset",this::H);}
    public void onSelectMainPathStatus(long path,int result){if(result==1)post("h","path selected reset",this::d0);}
    public void onCurrentRoadSpeed(int speed){post("c0","road speed",()->J(speed));}
    public void onEnterTunnel(double lon,double lat){post("q","tunnel enter",()->L(lon,lat));}
    public void onLeaveTunnel(double lon,double lat){post("z","tunnel leave",()->N(lon,lat));}
    public void onNaviArrive(long path,int type){post("b0","arrival",this::R);}
    public void onNearEnterTunnel(int distance){if(env.isMainThread()&&distance<2000)post("y","near enter tunnel",()->T(distance));}
    public void onNearLeaveTunnel(int distance){if(env.isMainThread()){if(K3==6)f13234q.setTunnelParam(distance,2,0,0,0,0);else post("x","near leave tunnel",()->V(distance));}}
    public void onPlayTTS(SoundInfo info){post("t","TTS",()->X(info));}
    public void onQueryAppointLanesInfo(long request,ArrayList<LaneInfo>lanes){post("k","lane query response",()->Z(lanes,request));}
    public void onReroute(RouteOption option){post("p","reroute",()->b0(option));}
    public void onShowNaviWeather(ArrayList<WeatherInfo> weather){post("w","weather",()->k0(weather));}
    public void onShowSameDirectionMixForkInfo(ArrayList<MixForkInfo> forks){post("d","mixed forks",()->l0(forks));}
    public void onUpdateCruisePredictiveTunnelInfo(CruisePredictiveTunnelInfo p){post("g","cruise tunnel",()->p0(p));}
    public void onUpdateCruiseRoadSpeed(CruiseRoadSpeed p){post("i","cruise speed",()->r0(p));}
    public void q(List<LightBarItem>bars){post("o","light bar",()->B0(bars));}
    public void c(boolean show,List<NaviCameraExt> cameras){post("v","camera callback",()->f0(cameras,show));}
    public void f(boolean show,List<Integer> toll){post("n","toll lanes",()->n0(show,toll));}
    public void m(boolean show,ArrayList<TrafficEventInfo> events){post("j","traffic events",()->D0(show,events));}
    public void a(boolean show,ArrayList<NaviRoadFacility> items){post("s","road facilities",()->i0(show,items));}
    public void b(boolean show,List<NaviCongestionDetailInfo> jams,long length,long time){post("u","congestion",()->z0(show,jams,length,time));}
    public void r(boolean show,ArrayList<Short> speeds,int distance){post("m","interval camera end",()->t0(show,speeds,distance));}
    public void s(boolean show,ArrayList<NaviFacility> services){post("d0","service area",()->x0(show,services));}
    public void u(boolean show,long path,long current,TrafficLightState state,Coord2DDouble point,int direction,int wait){post("a0","traffic light",()->F0(show,path,current,state,point,direction,wait));}
    public void x(boolean show,ArrayList<Integer> back,ArrayList<Integer> front,ArrayList<Integer> extension,ArrayList<Integer> recommend,ArrayList<Integer> backType,ArrayList<Integer>frontType,int segment,int link,ArrayList<Integer>backExt){post("r","lanes",()->h0(show,back,front,extension,recommend,backType,frontType,segment,link,backExt));}
}
