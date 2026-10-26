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
    @Test public void ownerLaneEvidenceIsIndependentExactCoverageAndPreservesEveryOtherInput() throws Exception {
        YandexRoute original=route();
        YandexRoute source=new YandexRoute(original.routeId,original.lon,original.lat,
            Collections.singletonList(new YandexRoute.Section(0,2,null,null,30,18)),null,new double[0],null,null,null,null);
        String edges="[[37,55,37.001,55]]";
        OwnerLaneEvidence partial=OwnerLaneEvidence.parse("{\"schema\":\"denza-owner-confirmed-lanes-v1\",\"laneNum\":2,\"edges\":"+edges+"}");
        PublicRoadEvidence publicRoads=PublicRoadEvidence.parse("{\"schema\":\"denza-owner-confirmed-public-roads-v1\",\"edges\":"+edges+"}");
        RouteAttributes cuts=new RouteAttributes(){
            public boolean matches(YandexRoute r){return r==source;}public double[] cuts(){return new double[]{0.5,1};}
            public RoadNetwork.KnownAttributes attributesBetween(double a,double b){throw new AssertionError("legacy attributes consulted");}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
        for(dev.denza.adaslink.Coordinates.Frame frame:dev.denza.adaslink.Coordinates.Frame.values()){
            YandexNativeAdapter.Policy baseline=proxyPolicy().withFrame(frame).withPublicRoadEvidence(publicRoads);
            YandexNativeAdapter.Policy policy=baseline.withLaneEvidence(partial);
            assertSame(partial,policy.withHistorySpeed(YandexNativeAdapter.HistorySpeedPolicy.OBSERVED_HISTORY_ONLY).laneEvidence);
            assertSame(partial,policy.withPublicRoadEvidence(PublicRoadEvidence.EMPTY).laneEvidence);assertSame(partial,policy.withFrame(frame).laneEvidence);
            assertSame(publicRoads,policy.publicRoads);assertEquals(frame,policy.frame);assertEquals(baseline.historySpeed,policy.historySpeed);
            YandexNativeAdapter.AdaptedRoute whole=YandexNativeAdapter.prepare(source,100,200,1,null,policy);
            assertEquals(1,whole.getSegmentInfo(0).getLinkCount());assertEquals(0,whole.getSegmentInfo(0).getLinkInfo(0).getLaneNum());
            YandexNativeAdapter.AdaptedRoute before=YandexNativeAdapter.prepare(source,100,200,1,cuts,baseline);
            YandexNativeAdapter.AdaptedRoute after=YandexNativeAdapter.prepare(source,100,200,1,cuts,policy);
            for(int i=0;i<3;i++){
                NativePath.Link a=before.getSegmentInfo(0).getLinkInfo(i),b=after.getSegmentInfo(0).getLinkInfo(i);
                assertEquals(0,a.getLaneNum());assertEquals(i<2?2:0,b.getLaneNum());
                for(java.lang.reflect.Method method:NativePath.Link.class.getMethods())if(!method.getName().equals("getLaneNum")&&!method.getName().equals("getPoints"))
                    assertEquals(method.getName(),method.invoke(a),method.invoke(b));
                assertEquals(a.getPoints().size(),b.getPoints().size());for(int p=0;p<a.getPoints().size();p++){
                    assertEquals(a.getPoints().get(p).lon,b.getPoints().get(p).lon);assertEquals(a.getPoints().get(p).lat,b.getPoints().get(p).lat);
                }
            }
            NativeModels.NaviInfo a=YandexNativeAdapter.navi(before,freshGuidance(0.75)),b=YandexNativeAdapter.navi(after,freshGuidance(0.75));
            for(java.lang.reflect.Field f:NativeModels.NaviInfo.class.getFields())if(f.getType().isPrimitive())assertEquals(f.getName(),f.get(a),f.get(b));
            YandexNativeAdapter.Location l=new YandexNativeAdapter.Location();l.scalar=0.75;l.matchedLon=37.00075;l.matchedLat=55;l.rawLon=37.0007;l.rawLat=55;l.speedMps=10;l.headingDeg=90;
            NativeModels.LocMatchInfo x=YandexNativeAdapter.location(before,l).matchInfo.get(0),y=YandexNativeAdapter.location(after,l).matchInfo.get(0);
            assertEquals(x.linkId,y.linkId);assertEquals(x.ownership,y.ownership);assertEquals(x.stPos.lon,y.stPos.lon,0);assertEquals(x.stPos.lat,y.stPos.lat,0);
            l.onRoute=false;x=YandexNativeAdapter.location(before,l).matchInfo.get(0);y=YandexNativeAdapter.location(after,l).matchInfo.get(0);
            assertEquals(-1,y.linkId);assertEquals(Integer.MIN_VALUE,y.ownership);assertEquals(x.stPos.lon,y.stPos.lon,0);assertEquals(x.stPos.lat,y.stPos.lat,0);
            assertNull(YandexNativeAdapter.location(null,l));
            @SuppressWarnings("unchecked")Map<String,Object> provenance=(Map<String,Object>)after.attributeEvidence().get("ownerLaneEvidence");
            assertEquals(partial.sha256(),provenance.get("sha256"));assertEquals(2,provenance.get("laneNum"));
        }
        YandexNativeAdapter.AdaptedRoute laneOnly=YandexNativeAdapter.prepare(source,100,200,1,cuts,proxyPolicy().withLaneEvidence(partial));
        assertEquals(2,laneOnly.getSegmentInfo(0).getLinkInfo(0).getLaneNum());assertEquals(Integer.MIN_VALUE,laneOnly.getSegmentInfo(0).getLinkInfo(0).getOwnership());
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
    @Test public void compatibilityCnChangesOnlyDeclaredDonorInputsAndKeepsRealRoute(){
        YandexNativeAdapter.Policy baselinePolicy=YandexNativeAdapter.Policy.TEST_ROAD_DATA;
        YandexNativeAdapter.Policy donorPolicy=YandexNativeAdapter.Policy.COMPATIBILITY_CN;
        assertTrue(donorPolicy.compatibilityCn);assertFalse(baselinePolicy.compatibilityCn);
        assertEquals(YandexNativeAdapter.HistorySpeedPolicy.EXPERIMENTAL_FIXED_25,donorPolicy.historySpeed);
        assertSame(donorPolicy.publicRoads,donorPolicy.withHistorySpeed(YandexNativeAdapter.HistorySpeedPolicy.OBSERVED_HISTORY_ONLY).publicRoads);
        assertTrue(donorPolicy.withFrame(dev.denza.adaslink.Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE).compatibilityCn);
        YandexNativeAdapter.AdaptedRoute before=YandexNativeAdapter.prepare(route(),100,200,1,null,baselinePolicy);
        YandexNativeAdapter.AdaptedRoute after=YandexNativeAdapter.prepare(route(),100,200,1,null,donorPolicy);
        assertTrue(after.admitted());assertEquals(before.getSegmentCount(),after.getSegmentCount());
        assertEquals(before.getAllTrafficLights().size(),after.getAllTrafficLights().size());
        for(int s=0;s<before.getSegmentCount();s++){
            NativePath.Segment x=before.getSegmentInfo(s),y=after.getSegmentInfo(s);
            assertEquals(x.getLinkCount(),y.getLinkCount());assertEquals(x.getLength(),y.getLength());
            assertEquals(x.getTravelTime(),y.getTravelTime());assertEquals(x.getMainAction(),y.getMainAction());
            assertEquals(x.getAssistantAction(),y.getAssistantAction());assertEquals(x.getTrafficLightNum(),y.getTrafficLightNum());
            for(int l=0;l<x.getLinkCount();l++){
                NativePath.Link a=x.getLinkInfo(l),b=y.getLinkInfo(l);
                assertEquals(7,b.getRoadClass());assertEquals(1,b.getFormway());assertEquals(0,b.getOwnership());
                assertEquals(1,b.getRoadDirection());assertEquals(0,b.getLaneNum());assertEquals(110000,b.getAdcode());
                assertEquals(1,b.getURID());assertTrue(b.hasMultiOut());
                assertEquals(a.get64TopoID(),b.get64TopoID());assertEquals(a.getLength(),b.getLength());
                assertEquals(a.getRoadName(),b.getRoadName());assertEquals(a.getLinkType(),b.getLinkType());
                assertEquals(a.hasTrafficLight(),b.hasTrafficLight());assertEquals(a.hasParallelRoad(),b.hasParallelRoad());
                assertEquals(a.hasMixFork(),b.hasMixFork());assertEquals(a.getMainAction(),b.getMainAction());
                assertEquals(a.getAssistantAction(),b.getAssistantAction());assertEquals(a.getPoints().size(),b.getPoints().size());
                for(int p=0;p<a.getPoints().size();p++){
                    assertEquals(a.getPoints().get(p).lon,b.getPoints().get(p).lon);
                    assertEquals(a.getPoints().get(p).lat,b.getPoints().get(p).lat);
                }
            }
        }
        YandexNativeAdapter.Location location=new YandexNativeAdapter.Location();location.scalar=.75;
        location.matchedLon=37.00075;location.matchedLat=55;location.rawLon=37.0007;location.rawLat=55;
        location.speedMps=10;location.headingDeg=90;
        NativeModels.LocInfo a=YandexNativeAdapter.location(before,location),b=YandexNativeAdapter.location(after,location);
        assertEquals(7,b.matchInfo.get(0).roadClass);assertEquals(1,b.matchInfo.get(0).formway);assertEquals(0,b.matchInfo.get(0).ownership);
        assertEquals(a.gpsPos.lon,b.gpsPos.lon,0);assertEquals(a.gpsPos.lat,b.gpsPos.lat,0);
        assertEquals(a.matchInfo.get(0).stPos.lon,b.matchInfo.get(0).stPos.lon,0);
        assertEquals(a.matchInfo.get(0).stPos.lat,b.matchInfo.get(0).stPos.lat,0);
        location.onRoute=false;NativeModels.LocInfo off=YandexNativeAdapter.location(after,location);
        assertEquals(-1,off.matchInfo.get(0).linkId);assertEquals(-1,off.matchInfo.get(0).roadClass);
        assertEquals(Integer.MIN_VALUE,off.matchInfo.get(0).ownership);
        assertEquals(25,YandexNativeAdapter.navi(after,freshGuidance(.75)).curLinkSpeed);
        YandexNativeAdapter.Guidance guidance=freshGuidance(.75);guidance.fixAgeMs=1501;
        assertEquals(0,YandexNativeAdapter.navi(after,guidance).curLinkSpeed);
        guidance=freshGuidance(.75);guidance.onRoute=false;
        assertEquals(0,YandexNativeAdapter.navi(after,guidance).curLinkSpeed);
        assertEquals(0,YandexNativeAdapter.navi(after,freshGuidance(2)).curLinkSpeed);
        guidance=freshGuidance(.75);guidance.historicalSpeedKmh=18;
        assertEquals(18,YandexNativeAdapter.navi(after,guidance).curLinkSpeed);
        @SuppressWarnings("unchecked")Map<String,Object> profile=(Map<String,Object>)after.attributeEvidence().get("compatibilityProfile");
        assertEquals("compatibility-cn",profile.get("id"));assertEquals(110000L,profile.get("region"));assertEquals(1L,profile.get("urid"));
        int donorDecisions=0;for(FieldDecision decision:after.fieldDecisions())if(decision.kind==FieldDecision.Kind.EXPERIMENTAL_DONOR)donorDecisions++;
        assertTrue(donorDecisions>=8);assertFalse(before.attributeEvidence().containsKey("compatibilityProfile"));
    }
    private static YandexRoute donorRoute(String firstScheme,String secondScheme){
        YandexRoute base=route();
        return new YandexRoute(base.routeId,base.lon,base.lat,Arrays.asList(
            new YandexRoute.Section(0,1,"STRAIGHT","First",12,Double.NaN,firstScheme),
            new YandexRoute.Section(1,2,"LEFT","Second",14,Double.NaN,secondScheme)),
            base.speedLimits,base.trafficLights,base.tunnels,base.ferries,base.wayPoints,base.cameras);
    }
    private static RouteAttributes donorAttributes(final YandexRoute source,final RouteAttributes.RendererClass first,final RouteAttributes.RendererClass second){
        return new RouteAttributes(){
            public boolean matches(YandexRoute route){return route==source;}
            public double[] cuts(){return new double[]{0.25,1.25};}
            public RoadNetwork.KnownAttributes attributesBetween(double a,double b){throw new AssertionError("converted class consulted");}
            public RendererClass rendererClassBetween(double a,double b){return a<1?first:second;}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
    }
    private static RouteAttributes.RendererClass fc(int value){return RouteAttributes.RendererClass.literal(Integer.toString(value),"string",(long)value,"uint32");}
    private static YandexNativeAdapter.AdaptedRoute donor(YandexRoute source,RouteAttributes.RendererClass first,RouteAttributes.RendererClass second){
        return YandexNativeAdapter.prepare(source,100,200,1,donorAttributes(source,first,second),YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);
    }
    @Test public void stockLikeHighwaySchemeOverridesFunctionalClassOnEveryLink(){
        YandexNativeAdapter.AdaptedRoute adapted=donor(donorRoute("HIGHWAY","SMALL"),fc(2),fc(7));
        for(int i=0;i<adapted.getSegmentInfo(0).getLinkCount();i++){
            assertEquals(6,adapted.getSegmentInfo(0).getLinkInfo(i).getRoadClass());
            assertEquals(85,YandexNativeAdapter.navi(adapted,freshGuidance(i==0?0.1:0.75)).curLinkSpeed);
        }
        // Highway metadata also takes priority when typed fc is conflicting or absent.
        assertEquals(6,donor(donorRoute("HIGHWAY","SMALL"),RouteAttributes.RendererClass.unknown("conflict"),fc(7)).getSegmentInfo(0).getLinkInfo(0).getRoadClass());
    }
    @Test public void stockLikeTypedFunctionalGroupsAndUnknownUseDonorSpeeds(){
        int[] classes={6,7,7,7,8,8,9,9,9},speeds={85,47,47,47,25,25,25,25,25};
        for(int fc=1;fc<=9;fc++){
            YandexNativeAdapter.AdaptedRoute adapted=donor(donorRoute("MEDIUM","SMALL"),fc(fc),fc(7));
            assertEquals(classes[fc-1],adapted.getSegmentInfo(0).getLinkInfo(0).getRoadClass());
            assertEquals(speeds[fc-1],YandexNativeAdapter.navi(adapted,freshGuidance(0.1)).curLinkSpeed);
        }
        for(RouteAttributes.RendererClass unknown:new RouteAttributes.RendererClass[]{RouteAttributes.RendererClass.unknown("absent"),
                RouteAttributes.RendererClass.literal("1","string",2L,"uint32"),RouteAttributes.RendererClass.literal("1","string",1.0,"double")}){
            YandexNativeAdapter.AdaptedRoute adapted=donor(donorRoute(null,"SMALL"),unknown,fc(7));
            assertEquals(9,adapted.getSegmentInfo(0).getLinkInfo(0).getRoadClass());
            assertEquals(25,YandexNativeAdapter.navi(adapted,freshGuidance(0.1)).curLinkSpeed);
        }
    }
    @Test public void stockLikeSpeedUsesCurrentLinkIncludingWithinOneSection(){
        YandexRoute source=donorRoute("HIGHWAY","SMALL");
        YandexNativeAdapter.AdaptedRoute adapted=donor(source,fc(2),fc(7));
        assertEquals(85,YandexNativeAdapter.navi(adapted,freshGuidance(0.999999)).curLinkSpeed);
        assertEquals(25,YandexNativeAdapter.navi(adapted,freshGuidance(1)).curLinkSpeed);
        RouteAttributes varying=new RouteAttributes(){
            public boolean matches(YandexRoute route){return route==source;}
            public double[] cuts(){return new double[]{1.5};}
            public RoadNetwork.KnownAttributes attributesBetween(double a,double b){throw new AssertionError();}
            public RendererClass rendererClassBetween(double a,double b){return a<1.5?fc(2):fc(1);}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
        adapted=YandexNativeAdapter.prepare(source,100,200,1,varying,YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);
        assertEquals(47,YandexNativeAdapter.navi(adapted,freshGuidance(1.499999)).curLinkSpeed);
        assertEquals(85,YandexNativeAdapter.navi(adapted,freshGuidance(1.5)).curLinkSpeed);
    }
    @Test public void stockLikeSpeedKeepsFreshnessAndObservedHistoryPriority(){
        YandexNativeAdapter.AdaptedRoute adapted=donor(donorRoute("HIGHWAY","SMALL"),fc(2),fc(7));
        for(int age:new int[]{-1,1501}){YandexNativeAdapter.Guidance g=freshGuidance(.1);g.fixAgeMs=age;assertEquals(0,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);}
        YandexNativeAdapter.Guidance g=freshGuidance(.1);g.fixAgeMs=1500;assertEquals(85,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
        g.onRoute=false;assertEquals(0,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
        assertEquals(0,YandexNativeAdapter.navi(adapted,freshGuidance(2)).curLinkSpeed);
        YandexNativeAdapter.AdaptedRoute outdated=YandexNativeAdapter.prepare(weightedSnapshot(true),100,200,1,null,YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);
        assertEquals(0,YandexNativeAdapter.navi(outdated,freshGuidance(.1)).curLinkSpeed);
        g=freshGuidance(.1);g.historicalSpeedKmh=62;assertEquals(62,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
        // Existing observed-input priority is retained even when the donor fallback is unavailable.
        g.onRoute=false;g.fixAgeMs=2000;assertEquals(62,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
        g.scalar=2;assertEquals(62,YandexNativeAdapter.navi(adapted,g).curLinkSpeed);
    }
    @Test public void stockLikeDonorFieldsPreserveGeometryNamesAndIdentity(){
        YandexRoute source=donorRoute("HIGHWAY","SMALL");RouteAttributes attrs=donorAttributes(source,fc(2),fc(7));
        YandexNativeAdapter.AdaptedRoute before=YandexNativeAdapter.prepare(source,100,200,1,attrs,YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        YandexNativeAdapter.AdaptedRoute after=YandexNativeAdapter.prepare(source,100,200,1,attrs,YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);
        for(int si=0;si<after.getSegmentCount();si++)for(int li=0;li<after.getSegmentInfo(si).getLinkCount();li++){
            NativePath.Link a=before.getSegmentInfo(si).getLinkInfo(li),b=after.getSegmentInfo(si).getLinkInfo(li);
            assertEquals(990001,b.getAdcode());assertEquals(372,b.getURID());assertEquals(1,b.getFormway());assertEquals(0,b.getOwnership());
            assertEquals(1,b.getRoadDirection());assertEquals(0,b.getLaneNum());assertTrue(b.hasMultiOut());assertFalse(b.hasParallelRoad());assertFalse(b.hasMixFork());
            assertEquals(a.getLength(),b.getLength());assertEquals(a.getRoadName(),b.getRoadName());assertEquals(a.get64TopoID(),b.get64TopoID());
            assertEquals(a.getLinkType(),b.getLinkType());assertEquals(a.hasTrafficLight(),b.hasTrafficLight());
            for(int pi=0;pi<a.getPoints().size();pi++){assertEquals(a.getPoints().get(pi).lon,b.getPoints().get(pi).lon);assertEquals(a.getPoints().get(pi).lat,b.getPoints().get(pi).lat);}
        }
    }
    @Test public void stockLikeBothFramesKeepClassesSpeedsAndIds(){
        YandexRoute source=donorRoute("HIGHWAY","SMALL");RouteAttributes attrs=donorAttributes(source,fc(2),fc(7));
        YandexNativeAdapter.AdaptedRoute a=YandexNativeAdapter.prepare(source,100,200,1,attrs,YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);
        YandexNativeAdapter.AdaptedRoute b=YandexNativeAdapter.prepare(source,100,200,1,attrs,YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD.withFrame(dev.denza.adaslink.Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE));
        for(int si=0;si<a.getSegmentCount();si++)for(int li=0;li<a.getSegmentInfo(si).getLinkCount();li++){
            assertEquals(a.getSegmentInfo(si).getLinkInfo(li).getRoadClass(),b.getSegmentInfo(si).getLinkInfo(li).getRoadClass());
            assertEquals(a.getSegmentInfo(si).getLinkInfo(li).get64TopoID(),b.getSegmentInfo(si).getLinkInfo(li).get64TopoID());
        }
        for(double at:new double[]{0.1,1})assertEquals(YandexNativeAdapter.navi(a,freshGuidance(at)).curLinkSpeed,YandexNativeAdapter.navi(b,freshGuidance(at)).curLinkSpeed);
    }
    @Test public void snapshotPreservesSchemeIdAndMissingRemainsNull(){
        @SuppressWarnings("unchecked") Map<String,Object> snapshot=(Map<String,Object>)dev.denza.adaslink.Json.parse(
            "{\"routeId\":\"scheme\",\"points\":[[37,55],[37.001,55],[37.002,55]],\"sections\":["
            +"{\"geometry\":{\"begin\":{\"segmentIndex\":0,\"segmentPosition\":0},\"end\":{\"segmentIndex\":1,\"segmentPosition\":0}},\"metadata\":{\"schemeId\":\"HIGHWAY\"}},"
            +"{\"geometry\":{\"begin\":{\"segmentIndex\":1,\"segmentPosition\":0},\"end\":{\"segmentIndex\":2,\"segmentPosition\":0}},\"metadata\":{}}]}");
        YandexRoute parsed=YandexRoute.fromSnapshot(snapshot);assertEquals("HIGHWAY",parsed.sections.get(0).schemeId);assertNull(parsed.sections.get(1).schemeId);
        assertNull(new YandexRoute.Section(0,1,null,null,1).schemeId);
    }

    @Test public void stockLikeAuditPublishesSourcedTablesRulesAndHighwayMetres(){
        YandexNativeAdapter.AdaptedRoute adapted=donor(donorRoute("HIGHWAY","SMALL"),fc(2),RouteAttributes.RendererClass.unknown("conflicting"));
        @SuppressWarnings("unchecked") Map<String,Object> profile=(Map<String,Object>)adapted.attributeEvidence().get("compatibilityProfile");
        assertEquals("stock-like-by-road",profile.get("id"));assertEquals(990001L,profile.get("region"));assertEquals(990001L,profile.get("adminCode"));assertEquals(372L,profile.get("urid"));
        @SuppressWarnings("unchecked") List<Map<String,Object>> roadRules=(List<Map<String,Object>>)profile.get("roadClassRules");
        @SuppressWarnings("unchecked") List<Map<String,Object>> speedRules=(List<Map<String,Object>>)profile.get("historySpeedByRoadClass");
        assertEquals(6,roadRules.size());assertEquals(4,speedRules.size());
        for(Map<String,Object> row:roadRules)assertTrue(((String)row.get("source")).length()>0);
        for(Map<String,Object> row:speedRules)assertTrue(((String)row.get("source")).length()>0);
        @SuppressWarnings("unchecked") Map<String,Object> sources=(Map<String,Object>)profile.get("sources");
        for(String field:new String[]{"region","adminCode","urid","country","speedLimit","formway","direction","laneNum","ownership","hasMultiOut","hasParallelRoad","hasMixFork"})assertTrue(sources.containsKey(field));
        @SuppressWarnings("unchecked") Map<String,Object> projection=(Map<String,Object>)adapted.attributeEvidence().get("rendererClassProjection");
        @SuppressWarnings("unchecked") Map<String,Integer> counts=(Map<String,Integer>)projection.get("ruleCounts");
        assertEquals(Integer.valueOf(3),counts.get("HIGHWAY_SCHEME"));assertEquals(Integer.valueOf(0),counts.get("FUNCTIONAL_CLASS"));assertEquals(Integer.valueOf(3),counts.get("UNKNOWN_FALLBACK"));
        assertEquals(adapted.getSegmentInfo(0).getLength(),projection.get("class6Metres"));
        boolean highway=false,fallback=false,region=false;
        for(FieldDecision decision:adapted.fieldDecisions()){
            if(decision.field.equals("segment[0].link[0].roadClass")){
                highway=true;assertTrue(decision.meaning.contains("rule=HIGHWAY_SCHEME"));assertTrue(decision.source.contains("schemeId=HIGHWAY"));assertTrue(decision.source.contains("fc=2"));
            }
            if(decision.field.equals("segment[1].link[0].roadClass")){
                fallback=true;assertTrue(decision.meaning.contains("rule=UNKNOWN_FALLBACK"));assertTrue(decision.source.contains("schemeId=SMALL"));assertTrue(decision.source.contains("fc=null"));
            }
            if(decision.field.equals("Region")){region=true;assertEquals(FieldDecision.Kind.EXPERIMENTAL_DONOR,decision.kind);}
        }
        assertTrue(highway);assertTrue(fallback);assertTrue(region);
        assertEquals(YandexNativeAdapter.HistorySpeedPolicy.EXPERIMENTAL_STOCK_LIKE_BY_ROAD,YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD.historySpeed);
        assertSame(NativeDonorProfile.STOCK_LIKE_BY_ROAD,YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD.withFrame(dev.denza.adaslink.Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE).donorProfile);
        assertNotNull(dev.denza.adaslink.Json.parse(dev.denza.adaslink.Json.write(adapted.attributeEvidence())));
    }

    @Test public void donorCompactAuditKeepsEveryLinkWhenHighwayStartsAfterVerboseLimit(){
        final YandexRoute source=new YandexRoute("late-highway",new double[]{37,37.001,37.002},new double[]{55,55,55},
            Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT","Street",12,Double.NaN,"SMALL"),
                new YandexRoute.Section(1,2,"STRAIGHT","Highway",14,Double.NaN,"HIGHWAY")),
            null,new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
        RouteAttributes attributes=new RouteAttributes(){
            public boolean matches(YandexRoute route){return route==source;}
            public double[] cuts(){double[] cuts=new double[143];for(int i=0;i<140;i++)cuts[i]=(i+1)/141.0;for(int i=0;i<3;i++)cuts[140+i]=1+(i+1)/4.0;return cuts;}
            public RoadNetwork.KnownAttributes attributesBetween(double begin,double end){throw new AssertionError("converted class consulted");}
            public RendererClass rendererClassBetween(double begin,double end){return begin<.1?RendererClass.unknown("source_unavailable"):fc(begin<1?7:2);}
            public Map<String,Object> summary(){return Collections.emptyMap();}
        };
        YandexNativeAdapter.AdaptedRoute adapted=YandexNativeAdapter.prepare(source,100,200,1,attributes,YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD);
        @SuppressWarnings("unchecked") Map<String,Object> projection=(Map<String,Object>)adapted.attributeEvidence().get("rendererClassProjection");
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows=(List<Map<String,Object>>)projection.get("linkDecisions");
        assertNotNull(rows);assertEquals(145,rows.size());assertEquals(rows.size(),projection.get("links"));
        assertEquals(128,projection.get("retainedDecisions"));assertEquals(17,projection.get("omittedDecisions"));
        int verbose=0;for(FieldDecision decision:adapted.fieldDecisions())if(decision.field.startsWith("segment[")&&decision.field.endsWith(".roadClass")){
            verbose++;assertFalse("late highway lies beyond verbose examples",decision.source.contains("schemeId=HIGHWAY"));
        }
        assertEquals(128,verbose);
        int at=0,highway=0;double through=0;
        for(int si=0;si<adapted.getSegmentCount();si++)for(int li=0;li<adapted.getSegmentInfo(si).getLinkCount();li++){
            Map<String,Object> row=rows.get(at++);assertEquals(si,row.get("segment"));assertEquals(li,row.get("link"));
            double begin=((Number)row.get("begin")).doubleValue(),end=((Number)row.get("end")).doubleValue();
            assertEquals(through,begin,0);assertTrue(end>begin);through=end;
            assertEquals(adapted.getSegmentInfo(si).getLinkInfo(li).getRoadClass(),row.get("roadClass"));
            assertEquals(si==0?"SMALL":"HIGHWAY",row.get("schemeId"));
            RouteAttributes.RendererClass raw=attributes.rendererClassBetween(begin,end);
            assertTrue(row.containsKey("fc"));assertEquals(raw.rawFc,row.get("fc"));assertEquals(raw.fcType,row.get("fcType"));
            assertEquals(raw.rawClass,row.get("rawClass"));assertEquals(raw.classType,row.get("classType"));assertEquals(raw.quality,row.get("quality"));
            assertFalse("source prose belongs to the shared profile table",row.containsKey("source"));
            if(si==1){highway++;assertTrue(at>128);assertEquals("HIGHWAY_SCHEME",row.get("rule"));assertEquals(6,row.get("roadClass"));}
            else assertEquals(begin<.1?"UNKNOWN_FALLBACK":"FUNCTIONAL_CLASS",row.get("rule"));
        }
        assertEquals(2,through,0);assertEquals(4,highway);
        assertEquals(adapted.getSegmentInfo(1).getLength(),projection.get("class6Metres"));
        @SuppressWarnings("unchecked") Map<String,Object> serialized=(Map<String,Object>)dev.denza.adaslink.Json.parse(dev.denza.adaslink.Json.write(projection));
        assertEquals(rows.size(),((List<?>)serialized.get("linkDecisions")).size());
        YandexNativeAdapter.AdaptedRoute ordinary=YandexNativeAdapter.prepare(source,100,200,1,attributes,YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        @SuppressWarnings("unchecked") Map<String,Object> ordinaryProjection=(Map<String,Object>)ordinary.attributeEvidence().get("rendererClassProjection");
        assertFalse(ordinaryProjection.containsKey("linkDecisions"));
    }

}
