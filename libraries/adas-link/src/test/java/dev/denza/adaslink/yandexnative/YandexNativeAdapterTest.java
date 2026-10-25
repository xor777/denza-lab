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
