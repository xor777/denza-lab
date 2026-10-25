package dev.denza.adaslink.yandexnative;

import dev.denza.adaslink.RoadNetwork;
import dev.denza.adaslink.RouteAttributes;
import dev.denza.adaslink.YandexRoute;
import dev.denza.adaslink.stocknative.NativeModels;
import dev.denza.adaslink.stocknative.NativePath;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Input-adapter invariants, not expected packet bytes or acceptance of the sender core. */
public final class YandexNativeAdapterTest {
    private static YandexRoute route(){
        return new YandexRoute("actual-source-route",new double[]{37,37.001,37.002},new double[]{55,55,55},
            Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT","First",12),new YandexRoute.Section(1,2,"LEFT","Second",14)),
            null,new double[]{0.5,1.5},Collections.emptyList(),Collections.emptyList(),null,null);
    }
    /** Tests exercise a declared hypothetical policy; production STRICT never opts into it. */
    private static YandexNativeAdapter.AdaptedRoute hypothetical(YandexRoute r){
        return YandexNativeAdapter.prepare(r,100,200,0,null,new YandexNativeAdapter.Policy(YandexNativeAdapter.MissingBooleans.EXPLICIT_FALSE));
    }
    @Test public void missingRoadFlagsBlockAndRemainExplicit(){
        YandexNativeAdapter.AdaptedRoute r=YandexNativeAdapter.prepare(route(),100,200,0);
        assertFalse(r.admitted());assertEquals(3,r.blockers().size());
        assertTrue(r.blockers().get(0).contains("hasMultiOut"));
        YandexNativeAdapter.Guidance g=new YandexNativeAdapter.Guidance();g.scalar=0.2;g.timeToFinishS=24;
        assertNull(YandexNativeAdapter.navi(r,g));
        int unavailable=0;for(FieldDecision d:r.fieldDecisions())if(d.kind==FieldDecision.Kind.UNAVAILABLE)unavailable++;
        assertTrue(unavailable>=4);
    }
    @Test public void syntheticPartitionOwnsAllStaticAndDynamicIndices(){
        YandexNativeAdapter.AdaptedRoute r=hypothetical(route());
        assertTrue(r.admitted());assertEquals(2,r.getSegmentCount());
        assertEquals(2,r.getSegmentInfo(0).getLinkCount());
        YandexNativeAdapter.Position p=r.position(0.75);
        assertEquals(0,p.segmentIndex);assertEquals(1,p.linkIndex);assertEquals(0,p.pointIndex);
        NativePath.Segment s=r.getSegmentInfo(p.segmentIndex);
        assertEquals(p.linkRemainM,p.segmentRemainM);
        assertEquals(p.segmentRemainM+r.getSegmentInfo(1).getLength(),p.routeRemainM);
        assertEquals(s.getLinkInfo(p.linkIndex).get64TopoID(),r.getSegmentInfo(0).getLinkInfo(1).get64TopoID());
        assertEquals(1,r.position(1).segmentIndex);assertEquals(0,r.position(1).linkIndex);
        assertEquals(0,r.position(2).routeRemainM);assertEquals(1,r.position(2).pointIndex);
        assertNull(r.getSegmentInfo(-1));assertNull(r.getSegmentInfo(2));
    }
    @Test public void geometryIsCopiedAndWgs84IsNotSilentlyProjected(){
        YandexRoute source=route();YandexNativeAdapter.AdaptedRoute r=hypothetical(source);
        source.lon[0]=99;source.lat[0]=88;
        assertArrayEquals(new double[]{37,55},r.coordinate(0),0);
        NativePath.Link first=r.getSegmentInfo(0).getLinkInfo(0);
        List<NativePath.IntPoint> points=first.getPoints();
        assertEquals(133200000,points.get(0).lon);assertEquals(198000000,points.get(0).lat);
        points.get(0).lon=0;assertEquals(133200000,first.getPoints().get(0).lon);
        assertEquals(37.0005,r.getAllTrafficLights().get(0).lon,1e-10);
        assertTrue(first.hasTrafficLight());
        assertEquals(2,r.remainingLights(0));assertEquals(1,r.remainingLights(0.5));
    }
    @Test public void identitiesAreStablePerEpochAndNotYandexIds(){
        YandexNativeAdapter.AdaptedRoute a=hypothetical(route()),b=hypothetical(route());
        assertEquals(a.getSegmentInfo(0).getLinkInfo(0).get64TopoID(),b.getSegmentInfo(0).getLinkInfo(0).get64TopoID());
        YandexNativeAdapter.AdaptedRoute c=YandexNativeAdapter.prepare(route(),101,201,0,null,new YandexNativeAdapter.Policy(YandexNativeAdapter.MissingBooleans.EXPLICIT_FALSE));
        assertNotEquals(a.getSegmentInfo(0).getLinkInfo(0).get64TopoID(),c.getSegmentInfo(0).getLinkInfo(0).get64TopoID());
        assertTrue(a.getSegmentInfo(0).getLinkInfo(0).get64TopoID().signum()>0);
        assertEquals(100,a.result().getPathInfos().get(0).getPathID());assertEquals(200,a.result().getRequestId());
        assertTrue(a.result().getPathInfos() instanceof java.util.ArrayList);
        assertNull(a.result().getToPOI());
    }
    @Test public void naviRequiresActualProgressAndEtaButDoesNotInventHistoricalSpeed(){
        YandexNativeAdapter.AdaptedRoute r=hypothetical(route());YandexNativeAdapter.Guidance g=new YandexNativeAdapter.Guidance();
        g.scalar=0.75;assertNull(YandexNativeAdapter.navi(r,g));g.timeToFinishS=20.5;
        NativeModels.NaviInfo ni=YandexNativeAdapter.navi(r,g);assertNotNull(ni);
        assertEquals(21,ni.routeRemain.time);assertEquals(0,ni.curLinkSpeed);assertEquals(0,ni.NaviInfoFlag);
        assertEquals(r.position(g.scalar).linkRemainM,ni.linkRemainDist);
        assertEquals(r.position(g.scalar).segmentRemainM,ni.NaviInfoData.get(0).segmentRemain.dist);
        assertEquals(2,ni.crossManeuverID);assertEquals(1,r.getSegmentInfo(0).getMainAction());
        assertEquals(1,r.getSegmentInfo(0).getLinkInfo(1).getMainAction());
        assertEquals(0,r.getSegmentInfo(0).getLinkInfo(0).getMainAction());
        g.scalar=Double.NaN;assertNull(YandexNativeAdapter.navi(r,g));
        g.scalar=-0.1;assertNull(YandexNativeAdapter.navi(r,g));
        g.scalar=0;g.timeToFinishS=Double.POSITIVE_INFINITY;assertNull(YandexNativeAdapter.navi(r,g));
    }
    private static YandexNativeAdapter.Policy proxyPolicy(){
        return YandexNativeAdapter.Policy.TEST_ROAD_DATA.withHistorySpeed(YandexNativeAdapter.HistorySpeedPolicy.EXPERIMENTAL_YANDEX_SECTION_FREE_FLOW_PROXY);
    }
    private static YandexNativeAdapter.Guidance freshGuidance(double scalar){
        YandexNativeAdapter.Guidance g=new YandexNativeAdapter.Guidance();g.scalar=scalar;g.timeToFinishS=24;g.fixAgeMs=0;g.onRoute=true;return g;
    }
    @SuppressWarnings("unchecked") private static YandexRoute weightedSnapshot(boolean outdated){
        // Exact source quantities from drive21: distance metres, base/traffic time seconds.
        return YandexRoute.fromSnapshot((Map<String,Object>)dev.denza.adaslink.Json.parse(
            "{\"routeId\":\"weighted\",\"points\":[[37,55],[37.001,55],[37.002,55]],\"trafficLights\":[],\"speedLimits\":[99,99],\"conditionsOutdated\":"+outdated+",\"sections\":["
            +"{\"geometry\":{\"begin\":{\"segmentIndex\":0,\"segmentPosition\":0},\"end\":{\"segmentIndex\":1,\"segmentPosition\":0}},\"metadata\":{\"weight\":{\"distance\":{\"value\":29.814388281225792},\"time\":{\"value\":14.907194140612896},\"timeWithTraffic\":{\"value\":100}}}},"
            +"{\"geometry\":{\"begin\":{\"segmentIndex\":1,\"segmentPosition\":0},\"end\":{\"segmentIndex\":2,\"segmentPosition\":0}},\"metadata\":{\"weight\":{\"distance\":{\"value\":7885.988738446845},\"time\":{\"value\":639.9210393890257},\"timeWithTraffic\":{\"value\":2000}}}}]}"));
    }
    @Test public void experimentalFreeFlowUsesSourceUnitsAndCurrentSectionNotTrafficOrLegalLimit(){
        YandexRoute source=weightedSnapshot(false);
        assertEquals(7.2,source.sections.get(0).freeFlowKmh,1e-9);
        assertEquals(44.36416012437097,source.sections.get(1).freeFlowKmh,1e-8);
        YandexNativeAdapter.AdaptedRoute adapted=YandexNativeAdapter.prepare(source,100,200,1,null,proxyPolicy());
        for(double at:new double[]{0,0.5,0.999999})assertEquals(7,YandexNativeAdapter.navi(adapted,freshGuidance(at)).curLinkSpeed);
        for(double at:new double[]{1,1.5,1.999999})assertEquals(44,YandexNativeAdapter.navi(adapted,freshGuidance(at)).curLinkSpeed);
        assertEquals(0,YandexNativeAdapter.navi(adapted,freshGuidance(2)).curLinkSpeed); // no current interval at route end
        assertEquals(0,YandexNativeAdapter.navi(hypothetical(source),freshGuidance(0.5)).curLinkSpeed);
        YandexNativeAdapter.Guidance g=freshGuidance(0.5);g.historicalSpeedKmh=18;
        assertEquals(18,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
        boolean audited=false;for(FieldDecision d:adapted.fieldDecisions())if(d.field.equals("NaviInfo.curLinkSpeed")){
            assertEquals(FieldDecision.Kind.DERIVED,d.kind);assertTrue(d.meaning.contains("EXPERIMENTAL_YANDEX_SECTION_FREE_FLOW_PROXY"));
            assertTrue(d.meaning.contains("section-average"));audited=true;
        }assertTrue(audited);
        RouteAttributes cuts=new RouteAttributes(){
            public boolean matches(YandexRoute r){return r==source;}
            public double[] cuts(){return new double[]{0.5};}
            public RoadNetwork.KnownAttributes attributesBetween(double a,double b){throw new AssertionError("legacy attributes consulted");}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
        YandexNativeAdapter.AdaptedRoute split=YandexNativeAdapter.prepare(source,100,200,1,cuts,proxyPolicy());
        assertEquals(1,split.position(0.75).linkIndex);assertEquals(0,split.position(0.75).segmentIndex);
        assertEquals(7,YandexNativeAdapter.navi(split,freshGuidance(0.75)).curLinkSpeed);
        assertEquals(44,YandexNativeAdapter.navi(split,freshGuidance(1)).curLinkSpeed);
        YandexNativeAdapter.AdaptedRoute shifted=YandexNativeAdapter.prepare(source,100,200,1,cuts,
            proxyPolicy().withFrame(dev.denza.adaslink.Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE));
        assertEquals(7,YandexNativeAdapter.navi(shifted,freshGuidance(0.75)).curLinkSpeed);
        assertEquals(44,YandexNativeAdapter.navi(shifted,freshGuidance(1)).curLinkSpeed);
    }
    @Test public void experimentalFreeFlowClearsOnMissingStaleOffRouteOrInvalidSource(){
        YandexNativeAdapter.AdaptedRoute adapted=YandexNativeAdapter.prepare(weightedSnapshot(false),100,200,1,null,proxyPolicy());
        YandexNativeAdapter.Guidance g=freshGuidance(0.5);
        assertEquals(7,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
        for(long age:new long[]{-1,1501,Long.MAX_VALUE}){g.fixAgeMs=age;assertEquals(0,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);}
        g.fixAgeMs=1500;assertEquals(7,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
        g.onRoute=false;assertEquals(0,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
        assertEquals(0,YandexNativeAdapter.navi(YandexNativeAdapter.prepare(weightedSnapshot(true),100,200,1,null,proxyPolicy()),freshGuidance(0.5)).curLinkSpeed);
        YandexRoute base=route();
        for(double speed:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-1,(double)Integer.MAX_VALUE+1}){
            YandexRoute source=new YandexRoute(base.routeId,base.lon,base.lat,
                Collections.singletonList(new YandexRoute.Section(0,2,null,null,12,speed)),null,new double[0],null,null,null,null);
            assertEquals(0,YandexNativeAdapter.navi(YandexNativeAdapter.prepare(source,100,200,1,null,proxyPolicy()),freshGuidance(0.5)).curLinkSpeed);
        }
        assertEquals(0,YandexNativeAdapter.navi(YandexNativeAdapter.prepare(base,100,200,1,null,proxyPolicy()),freshGuidance(0.5)).curLinkSpeed);
        YandexRoute absent=new YandexRoute(base.routeId,base.lon,base.lat,Collections.emptyList(),null,new double[0],null,null,null,null);
        assertEquals(0,YandexNativeAdapter.navi(YandexNativeAdapter.prepare(absent,100,200,1,null,proxyPolicy()),freshGuidance(0.5)).curLinkSpeed);
    }
    @Test public void experimentalFreeFlowPreservesFrameAndEveryOtherNativeInputField() throws Exception {
        YandexNativeAdapter.Policy policy=proxyPolicy().withFrame(dev.denza.adaslink.Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE);
        assertEquals(proxyPolicy().historySpeed,policy.historySpeed);
        assertEquals(policy.frame,policy.withHistorySpeed(YandexNativeAdapter.HistorySpeedPolicy.OBSERVED_HISTORY_ONLY).frame);
        YandexRoute source=weightedSnapshot(false);
        YandexNativeAdapter.AdaptedRoute before=YandexNativeAdapter.prepare(source,100,200,1,null,YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        YandexNativeAdapter.AdaptedRoute after=YandexNativeAdapter.prepare(source,100,200,1,null,proxyPolicy());
        NativeModels.NaviInfo a=YandexNativeAdapter.navi(before,freshGuidance(0.5)),b=YandexNativeAdapter.navi(after,freshGuidance(0.5));
        for(java.lang.reflect.Field f:NativeModels.NaviInfo.class.getFields())if(f.getType().isPrimitive()&&!f.getName().equals("curLinkSpeed"))assertEquals(f.getName(),f.get(a),f.get(b));
        assertEquals(a.routeRemain.dist,b.routeRemain.dist);assertEquals(a.routeRemain.time,b.routeRemain.time);
        assertEquals(a.NaviInfoData.get(0).segmentRemain.dist,b.NaviInfoData.get(0).segmentRemain.dist);
        for(int s=0;s<before.getSegmentCount();s++)for(int l=0;l<before.getSegmentInfo(s).getLinkCount();l++){
            NativePath.Link x=before.getSegmentInfo(s).getLinkInfo(l),y=after.getSegmentInfo(s).getLinkInfo(l);
            assertEquals(x.get64TopoID(),y.get64TopoID());assertEquals(x.getOwnership(),y.getOwnership());assertEquals(Integer.MIN_VALUE,y.getOwnership());
            assertEquals(x.getLaneNum(),y.getLaneNum());assertEquals(x.getAdcode(),y.getAdcode());assertEquals(x.getURID(),y.getURID());assertEquals(x.getRoadClass(),y.getRoadClass());
            assertEquals(x.getLength(),y.getLength());assertEquals(x.getPoints().size(),y.getPoints().size());
            for(int i=0;i<x.getPoints().size();i++){assertEquals(x.getPoints().get(i).lon,y.getPoints().get(i).lon);assertEquals(x.getPoints().get(i).lat,y.getPoints().get(i).lat);}
        }
    }
    @Test public void ownerPublicEvidenceRequiresEverySourceEdgeAndKeepsAllOtherLinkFields() throws Exception {
        YandexRoute original=route();
        YandexRoute source=new YandexRoute(original.routeId,original.lon,original.lat,
            Collections.singletonList(new YandexRoute.Section(0,2,null,null,30,18)),null,new double[0],null,null,null,null);
        PublicRoadEvidence partial=PublicRoadEvidence.parse("{\"schema\":\"denza-owner-confirmed-public-roads-v1\",\"edges\":[[37,55,37.001,55]]}");
        PublicRoadEvidence full=PublicRoadEvidence.parse("{\"schema\":\"denza-owner-confirmed-public-roads-v1\",\"edges\":[[37,55,37.001,55],[37.002,55,37.001,55]]}");
        YandexNativeAdapter.Policy owner=proxyPolicy().withPublicRoadEvidence(full);
        assertSame(full,owner.withFrame(dev.denza.adaslink.Coordinates.Frame.WGS84).publicRoads);
        assertSame(full,owner.withHistorySpeed(YandexNativeAdapter.HistorySpeedPolicy.OBSERVED_HISTORY_ONLY).publicRoads);
        YandexNativeAdapter.AdaptedRoute absent=YandexNativeAdapter.prepare(source,100,200,1,null,proxyPolicy());
        YandexNativeAdapter.AdaptedRoute incomplete=YandexNativeAdapter.prepare(source,100,200,1,null,proxyPolicy().withPublicRoadEvidence(partial));
        YandexNativeAdapter.AdaptedRoute confirmed=YandexNativeAdapter.prepare(source,100,200,1,null,owner);
        assertEquals(Integer.MIN_VALUE,incomplete.getSegmentInfo(0).getLinkInfo(0).getOwnership());
        assertEquals(0,confirmed.getSegmentInfo(0).getLinkInfo(0).getOwnership());
        NativePath.Link a=absent.getSegmentInfo(0).getLinkInfo(0),b=confirmed.getSegmentInfo(0).getLinkInfo(0);
        for(java.lang.reflect.Method method:NativePath.Link.class.getMethods())if(!method.getName().equals("getOwnership")&&!method.getName().equals("getPoints"))
            assertEquals(method.getName(),method.invoke(a),method.invoke(b));
        assertEquals(a.getPoints().size(),b.getPoints().size());for(int i=0;i<a.getPoints().size();i++){
            assertEquals(a.getPoints().get(i).lon,b.getPoints().get(i).lon);assertEquals(a.getPoints().get(i).lat,b.getPoints().get(i).lat);
        }
        YandexNativeAdapter.Location location=new YandexNativeAdapter.Location();location.scalar=0.75;location.matchedLon=37.00075;location.matchedLat=55;
        location.rawLon=37.0007;location.rawLat=55;location.speedMps=10;location.headingDeg=90;
        assertEquals(0,YandexNativeAdapter.location(confirmed,location).matchInfo.get(0).ownership);
        location.onRoute=false;assertEquals(Integer.MIN_VALUE,YandexNativeAdapter.location(confirmed,location).matchInfo.get(0).ownership);
        RouteAttributes split=new RouteAttributes(){
            public boolean matches(YandexRoute r){return r==source;}public double[] cuts(){return new double[]{0.5,1};}
            public RoadNetwork.KnownAttributes attributesBetween(double a,double b){throw new AssertionError("legacy attributes consulted");}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
        YandexNativeAdapter.AdaptedRoute cut=YandexNativeAdapter.prepare(source,100,200,1,split,proxyPolicy().withPublicRoadEvidence(partial));
        assertEquals(0,cut.getSegmentInfo(0).getLinkInfo(0).getOwnership());assertEquals(0,cut.getSegmentInfo(0).getLinkInfo(1).getOwnership());
        assertEquals(Integer.MIN_VALUE,cut.getSegmentInfo(0).getLinkInfo(2).getOwnership());
        @SuppressWarnings("unchecked")Map<String,Object> provenance=(Map<String,Object>)confirmed.attributeEvidence().get("publicRoadEvidence");
        assertEquals(full.sha256(),provenance.get("sha256"));assertTrue(provenance.get("source").toString().contains("owner-confirmed"));
    }
    @Test public void locationRequiresIndependentRawFixAndDoesNotInventSpeed(){
        YandexNativeAdapter.AdaptedRoute r=hypothetical(route());YandexNativeAdapter.Location l=new YandexNativeAdapter.Location();
        l.scalar=0.75;l.matchedLon=37.00075;l.matchedLat=55;l.headingDeg=90;
        l.speedMps=10;assertNull(YandexNativeAdapter.location(r,l));
        l.rawLon=37.00070;l.rawLat=55.0001;NativeModels.LocInfo info=YandexNativeAdapter.location(r,l);assertNotNull(info);
        assertEquals(36,info.speed,0);assertEquals(l.rawLon,info.gpsPos.lon,0);
        assertEquals(l.matchedLon,info.matchInfo.get(0).stPos.lon,0);
        assertNotEquals(info.gpsPos.lon,info.matchInfo.get(0).stPos.lon,0);
        NativePath.Link link=r.getSegmentInfo(0).getLinkInfo(1);
        assertEquals(link.get64TopoID().longValue(),info.matchInfo.get(0).linkId);
        assertEquals(-1,info.matchInfo.get(0).roadClass);assertEquals(Integer.MIN_VALUE,info.matchInfo.get(0).ownership);
        l.speedMps=Double.NaN;assertNull(YandexNativeAdapter.location(r,l));
        l.simulated=true;assertEquals(1,YandexNativeAdapter.location(r,l).isSimulate);
    }
    @Test public void sourceUnknownAndKnownEmptyStayDifferent(){
        YandexRoute r=route();
        YandexRoute absent=new YandexRoute(r.routeId,r.lon,r.lat,r.sections,null,null,null,null,null,null);
        assertEquals(4,YandexNativeAdapter.prepare(absent,100,200,0).blockers().size());
        YandexNativeAdapter.AdaptedRoute a=hypothetical(absent);
        assertTrue(a.getAllTrafficLights().isEmpty());assertEquals(-1,a.getSegmentInfo(0).getLinkInfo(0).getLinkType());
        YandexNativeAdapter.AdaptedRoute empty=hypothetical(r);
        assertEquals(-1,empty.getSegmentInfo(0).getLinkInfo(0).getLinkType());
    }
    @Test public void acquisitionCutsAndEvidenceDoNotMakeLegacyConvertedEnumsNativeFacts(){
        final YandexRoute source=route();final int[] queries={0};
        RouteAttributes attrs=new RouteAttributes(){
            public boolean matches(YandexRoute r){return r==source;}
            public double[] cuts(){return new double[]{0.25};}
            public RoadNetwork.KnownAttributes attributesBetween(double a,double b){queries[0]++;assertTrue(b>a);return a<0.25?new RoadNetwork.KnownAttributes(7,null,null,3,1,null):RoadNetwork.KnownAttributes.NONE;}
            public Map<String,Object> summary(){return Collections.<String,Object>singletonMap("quality","declared-test-evidence");}
        };
        YandexNativeAdapter.AdaptedRoute r=YandexNativeAdapter.prepare(source,100,200,0,attrs,new YandexNativeAdapter.Policy(YandexNativeAdapter.MissingBooleans.EXPLICIT_FALSE));
        assertEquals(0,queries[0]);assertEquals(-1,r.getSegmentInfo(0).getLinkInfo(0).getRoadClass());
        assertEquals(0,r.getSegmentInfo(0).getLinkInfo(0).getLaneNum());assertEquals(-1,r.getSegmentInfo(0).getLinkInfo(1).getRoadClass());
        assertEquals("declared-test-evidence",r.attributeEvidence().get("quality"));
        try{YandexNativeAdapter.prepare(route(),100,200,0,attrs);fail("wrong route");}catch(IllegalArgumentException expected){}
    }
    @Test public void roadClassDiagnosticsAreBoundedWithoutTruncatingData(){
        final YandexRoute source=route();
        RouteAttributes attrs=new RouteAttributes(){
            public boolean matches(YandexRoute r){return r==source;}
            public double[] cuts(){double[] cuts=new double[140];for(int i=0;i<cuts.length;i++)cuts[i]=2.0*(i+1)/141;return cuts;}
            public RoadNetwork.KnownAttributes attributesBetween(double a,double b){throw new AssertionError("legacy class consulted");}
            public RendererClass rendererClassBetween(double a,double b){return RendererClass.literal("1","string",1L,"uint32");}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
        YandexNativeAdapter.AdaptedRoute adapted=YandexNativeAdapter.prepare(source,100,200,0,attrs,YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        int links=0,examples=0;
        for(int si=0;si<adapted.getSegmentCount();si++)for(int li=0;li<adapted.getSegmentInfo(si).getLinkCount();li++){
            links++;assertEquals(6,adapted.getSegmentInfo(si).getLinkInfo(li).getRoadClass());
        }
        for(FieldDecision d:adapted.fieldDecisions())if(d.field.startsWith("segment[")&&d.field.endsWith(".roadClass"))examples++;
        assertTrue(links>128);assertEquals(128,examples);
        @SuppressWarnings("unchecked")Map<String,Object> projection=(Map<String,Object>)adapted.attributeEvidence().get("rendererClassProjection");
        assertEquals(links,projection.get("links"));assertEquals(links-128,projection.get("omittedDecisions"));
        assertEquals(Collections.singletonMap("6",links),projection.get("nativeClassCounts"));
        assertNotNull(dev.denza.adaslink.Json.parse(dev.denza.adaslink.Json.write(adapted.attributeEvidence())));
    }
    @Test public void namedEnumMappingsPreserveBranchDirectionWithoutGuessingAnglesOrUnknowns(){
        assertEquals(1,NativeEnums.action("LEFT").main);assertEquals(19,NativeEnums.action("UTURN_RIGHT").icon);
        assertEquals(19,NativeEnums.action("BOARD_FERRY").assistant);
        // Original SDK AssistantAction names identify branch direction. They do not establish
        // a merge, a freeway/ramp classification, a turn angle or a corresponding native icon.
        for(String name:new String[]{"FORK_LEFT","FORK_RIGHT","EXIT_LEFT","EXIT_RIGHT"}){
            NativeEnums.Action branch=NativeEnums.action(name);
            assertTrue(branch.mapped);assertEquals(name.endsWith("LEFT")?8:7,branch.assistant);
            assertEquals(0,branch.main);assertEquals(0,branch.icon);
        }
        assertFalse(NativeEnums.action("future-enum").mapped);assertEquals(0,NativeEnums.action(null).icon);
    }
    @Test public void polylineVerticesRemainGeometryInsteadOfOneLinkEach(){
        YandexRoute source=new YandexRoute("curve",new double[]{37,37.001,37.001,37.002},new double[]{55,55,55.001,55.001},
            Collections.singletonList(new YandexRoute.Section(0,3,"STRAIGHT","Curve",30)),null,new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
        YandexNativeAdapter.AdaptedRoute r=hypothetical(source);
        assertEquals(1,r.getSegmentInfo(0).getLinkCount());assertEquals(4,r.getSegmentInfo(0).getLinkInfo(0).getPoints().size());
        assertEquals(1,r.position(1.5).pointIndex);assertEquals(3,r.position(3).pointIndex);
        assertEquals(0,r.position(1.5).bearingDeg,0.01);assertEquals(90,r.position(2.5).bearingDeg,0.01);
    }
    @Test public void repeatedSignalsAtOnePositionAreCountedIndividually(){
        YandexRoute source=route();YandexRoute repeated=new YandexRoute(source.routeId,source.lon,source.lat,source.sections,null,new double[]{0.5,0.5},null,null,null,null);
        YandexNativeAdapter.AdaptedRoute r=hypothetical(repeated);
        assertEquals(2,r.getSegmentInfo(0).getTrafficLightNum());assertEquals(2,r.remainingLights(0));
        assertEquals(2,r.getAllTrafficLights().size());
    }
    @Test public void invalidGeometryAndSectionsFailBeforePublication(){
        YandexRoute r=route();r.lon[0]=Double.NaN;
        try{hypothetical(r);fail("NaN geometry");}catch(IllegalArgumentException expected){}
        YandexRoute gap=new YandexRoute("gap",new double[]{37,37.001,37.002},new double[]{55,55,55},
            Arrays.asList(new YandexRoute.Section(0,0.5,null,null,10),new YandexRoute.Section(1,2,null,null,10)),null,null,null,null,null,null);
        try{hypothetical(gap);fail("section gap");}catch(IllegalArgumentException expected){}
    }
}
