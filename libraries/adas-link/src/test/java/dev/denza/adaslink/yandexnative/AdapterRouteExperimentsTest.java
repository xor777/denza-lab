package dev.denza.adaslink.yandexnative;

import dev.denza.adaslink.Coordinates;
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

/** Source/partition invariants for declared adapter experiments; no factory-output oracle. */
public final class AdapterRouteExperimentsTest {
    private static final AdapterExperiments LIGHT=new AdapterExperiments(true,false,false,false);
    private static final AdapterExperiments MERGE=new AdapterExperiments(false,false,false,true);
    private static RouteAttributes.RendererClass raw(int fc){return RouteAttributes.RendererClass.literal(Integer.toString(fc),"string",fc,"uint32");}
    private static YandexRoute route(double[] metres,double[] lights,List<YandexRoute.Section> sections,List<double[]> tunnels,List<double[]> ferries){
        double[] lon=new double[metres.length],lat=new double[metres.length];
        for(int i=0;i<lon.length;i++)lon[i]=Math.toDegrees(metres[i]/6371008.8);
        return new YandexRoute("independent-source-partition",lon,lat,sections==null?Collections.singletonList(new YandexRoute.Section(0,lon.length-1,"STRAIGHT","Road",30)):sections,null,lights,tunnels,ferries,null,null);
    }
    private static YandexRoute route(double... metres){return route(metres,new double[0],null,Collections.emptyList(),Collections.emptyList());}
    private static final class Source implements RouteAttributes {
        final YandexRoute route;final RouteAttributes.RendererClass[] evidence;
        Source(YandexRoute route,RouteAttributes.RendererClass... evidence){this.route=route;this.evidence=evidence;}
        public boolean matches(YandexRoute value){return value==route;}
        public double[] cuts(){double[] out=new double[route.lon.length-2];for(int i=0;i<out.length;i++)out[i]=i+1;return out;}
        public RoadNetwork.KnownAttributes attributesBetween(double a,double b){throw new AssertionError("Converted legacy attributes are not adapter evidence");}
        public RouteAttributes.RendererClass rendererClassBetween(double a,double b){return evidence.length==1?evidence[0]:evidence[(int)a];}
        public Map<String,Object> summary(){return Collections.<String,Object>singletonMap("source","independent-whole-interval-evidence");}
    }
    private static YandexNativeAdapter.AdaptedRoute prepare(YandexRoute route,AdapterExperiments experiments,RouteAttributes.RendererClass... evidence){
        return YandexNativeAdapter.prepare(route,100,200,0,new Source(route,evidence),YandexNativeAdapter.Policy.TEST_ROAD_DATA.withExperiments(experiments));
    }
    private static NativeModels.NaviInfo navi(YandexNativeAdapter.AdaptedRoute route,double scalar){
        YandexNativeAdapter.Guidance in=new YandexNativeAdapter.Guidance();in.scalar=scalar;in.timeToFinishS=30;return YandexNativeAdapter.navi(route,in);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> audit(YandexNativeAdapter.AdaptedRoute route){return (Map<String,Object>)route.attributeEvidence().get("shortLinkMerge");}
    private static long links(YandexNativeAdapter.AdaptedRoute route){long count=0;for(int i=0;i<route.getSegmentCount();i++)count+=route.getSegmentInfo(i).getLinkCount();return count;}

    @Test public void lightMaskUsesAlongRouteDistanceAndExactBoundaryButKeepsStaticFacts(){
        YandexRoute source=route(new double[]{0,1000,2000},new double[]{0.75,1.4},null,Collections.emptyList(),Collections.emptyList());
        YandexNativeAdapter.AdaptedRoute baseline=prepare(source,AdapterExperiments.BASELINE,raw(2));
        YandexNativeAdapter.AdaptedRoute masked=prepare(source,LIGHT,raw(2));
        assertEquals(2,navi(baseline,0).routeRemainLightCount);assertEquals(0,navi(masked,0).routeRemainLightCount);
        assertEquals(0,navi(masked,0.249999).routeRemainLightCount);
        assertEquals(2,navi(masked,0.25).routeRemainLightCount); // Exactly 500m to the next known light.
        assertEquals(2,navi(masked,0.250001).routeRemainLightCount);
        assertEquals(0,navi(masked,0.75).routeRemainLightCount); // Passed light is excluded; next is 650m ahead.
        assertEquals(1,navi(masked,0.900001).routeRemainLightCount);
        assertEquals(0,navi(masked,1.4).routeRemainLightCount);
        assertEquals(2,masked.getAllTrafficLights().size());assertEquals(2,masked.getSegmentInfo(0).getTrafficLightNum());
        assertEquals(links(baseline),links(masked));
        for(int i=0;i<baseline.getSegmentInfo(0).getLinkCount();i++){
            NativePath.Link before=baseline.getSegmentInfo(0).getLinkInfo(i),after=masked.getSegmentInfo(0).getLinkInfo(i);
            assertEquals(before.hasTrafficLight(),after.hasTrafficLight());assertEquals(before.get64TopoID(),after.get64TopoID());
        }
        assertEquals(2,masked.remainingLights(0)); // Source facts remain available independently of the navi mask.
        boolean declared=false;for(FieldDecision d:masked.fieldDecisions())if(d.field.equals("NaviInfo.routeRemainLightCount")){declared=true;assertEquals(FieldDecision.Kind.EXPERIMENTAL_DONOR,d.kind);assertTrue(d.absence.contains("no factory-equivalence"));}assertTrue(declared);
    }

    @Test public void emptyAndUnavailableLightsRetainExplicitExistingAbsencePolicy(){
        for(double[] lights:new double[][]{null,new double[0]}){
            YandexRoute source=route(new double[]{0,1000},lights,null,Collections.emptyList(),Collections.emptyList());
            YandexNativeAdapter.AdaptedRoute masked=prepare(source,LIGHT,raw(2));
            assertTrue(masked.admitted());assertEquals(0,navi(masked,0).routeRemainLightCount);assertTrue(masked.getAllTrafficLights().isEmpty());
        }
        YandexRoute source=route(new double[]{0,1000},null,null,Collections.emptyList(),Collections.emptyList());
        assertFalse(YandexNativeAdapter.prepare(source,100,200,0,null,YandexNativeAdapter.Policy.STRICT.withExperiments(LIGHT)).admitted());
    }

    @Test public void compatibleTinySpansMergeWithoutDroppingGeometryOffsetsOrEndpointAction(){
        YandexRoute source=route(0,67.4,69.6,69.8,806.8);
        YandexNativeAdapter.AdaptedRoute baseline=prepare(source,AdapterExperiments.BASELINE,raw(2));
        YandexNativeAdapter.AdaptedRoute merged=prepare(source,MERGE,raw(2));
        assertEquals(4,links(baseline));assertEquals(2,links(merged));
        NativePath.Link combined=merged.getSegmentInfo(0).getLinkInfo(0);
        assertEquals(69,combined.getLength());assertEquals(4,combined.getPoints().size());
        assertEquals(0,merged.position(0.5).pointIndex);assertEquals(1,merged.position(1).pointIndex);assertEquals(2,merged.position(2).pointIndex);
        for(double scalar:new double[]{0,0.3,0.999,1,1.5,2,2.5,2.999,3,3.5,4}){
            assertEquals("Source-span offsets at "+scalar,baseline.position(scalar).routeRemainM,merged.position(scalar).routeRemainM);
            assertEquals(baseline.position(scalar).segmentRemainM,merged.position(scalar).segmentRemainM);
            YandexNativeAdapter.Location fix=new YandexNativeAdapter.Location();double[] point=merged.coordinate(scalar);
            fix.scalar=scalar;fix.matchedLon=point[0];fix.matchedLat=point[1];fix.rawLon=point[0];fix.rawLat=point[1];fix.speedMps=10;fix.headingDeg=90;
            NativeModels.LocInfo loc=YandexNativeAdapter.location(merged,fix);YandexNativeAdapter.Position position=merged.position(scalar);
            assertEquals(merged.getSegmentInfo(position.segmentIndex).getLinkInfo(position.linkIndex).get64TopoID().longValue(),loc.matchInfo.get(0).linkId);
            assertEquals(position.linkIndex,navi(merged,scalar).curLinkIdx);
        }
        assertEquals(baseline.getSegmentInfo(0).getLength(),merged.getSegmentInfo(0).getLength());
        assertEquals(36,merged.getSegmentInfo(0).getLinkInfo(1).getAssistantAction());
        assertEquals(4,audit(merged).get("beforeLinks"));assertEquals(2,audit(merged).get("afterLinks"));
        assertEquals(1,audit(merged).get("beforeZeroLengthLinks"));assertEquals(0,audit(merged).get("retainedZeroLengthLinks"));
        assertTrue(Math.abs((Double)audit(merged).get("roundingErrorMetres"))<=(Double)audit(merged).get("roundingBudgetMetres"));
        assertEquals(combined.get64TopoID(),prepare(source,MERGE,raw(2)).getSegmentInfo(0).getLinkInfo(0).get64TopoID());
        assertTrue(combined.get64TopoID().signum()>0);
    }

    @Test public void finalTinyLinkCanTransferItsActionAtTheSameEndpoint(){
        YandexRoute source=route(0,100,102);
        YandexNativeAdapter.AdaptedRoute merged=prepare(source,MERGE,raw(2));
        assertEquals(1,links(merged));assertEquals(102,merged.getSegmentInfo(0).getLinkInfo(0).getLength());
        assertEquals(36,merged.getSegmentInfo(0).getLinkInfo(0).getAssistantAction());
        assertEquals(3,merged.getSegmentInfo(0).getLinkInfo(0).getPoints().size());
    }

    @Test public void differentAndUnknownClassesMergeWithAuditedDominantOriginalEvidence(){
        YandexRoute source=route(0,67,69,69.2,806.2);
        RouteAttributes.RendererClass unknown=RouteAttributes.RendererClass.unknown("coverage_gap");
        YandexNativeAdapter.AdaptedRoute merged=prepare(source,MERGE,raw(2),raw(3),unknown,raw(2));
        assertEquals(2,links(merged));assertEquals(0,audit(merged).get("retainedZeroLengthLinks"));
        String evidence=audit(merged).get("mapping").toString();assertTrue(evidence.contains("discardedAttributeEvidence"));assertTrue(evidence.contains("coverage_gap"));
        assertEquals(7,merged.getSegmentInfo(0).getLinkInfo(0).getRoadClass());
        YandexNativeAdapter.AdaptedRoute donor=YandexNativeAdapter.prepare(source,100,200,0,new Source(source,raw(7),raw(7),unknown,raw(6)),YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD.withExperiments(MERGE));
        assertEquals(2,links(donor)); // The unknown 0m interval is deliberately absorbed and its evidence retained.
        assertEquals(0,audit(donor).get("retainedZeroLengthLinks"));
    }

    @Test public void ownerEvidenceDifferencesMergeAndChosenDiscardedAttributesRemainAuditable(){
        YandexRoute source=route(0,67,69,806);
        String edge="[["+source.lon[1]+",0,"+source.lon[2]+",0]]";
        PublicRoadEvidence ownership=PublicRoadEvidence.parse("{\"schema\":\""+PublicRoadEvidence.SCHEMA+"\",\"edges\":"+edge+"}");
        OwnerLaneEvidence lanes=OwnerLaneEvidence.parse("{\"schema\":\""+OwnerLaneEvidence.SCHEMA+"\",\"laneNum\":2,\"edges\":"+edge+"}");
        YandexNativeAdapter.Policy policy=YandexNativeAdapter.Policy.TEST_ROAD_DATA.withExperiments(MERGE).withPublicRoadEvidence(ownership).withLaneEvidence(lanes);
        YandexNativeAdapter.AdaptedRoute kept=YandexNativeAdapter.prepare(source,100,200,0,new Source(source,raw(2)),policy);
        assertEquals(2,links(kept));assertEquals(NativeEnums.OWNERSHIP_ERROR,kept.getSegmentInfo(0).getLinkInfo(0).getOwnership());assertEquals(0,kept.getSegmentInfo(0).getLinkInfo(0).getLaneNum());
        String originalEvidence=audit(kept).get("mapping").toString();assertTrue(originalEvidence.contains("ownership=0"));assertTrue(originalEvidence.contains("laneNum=2"));assertTrue(originalEvidence.contains("discardedAttributeEvidence"));
        YandexNativeAdapter.Policy donor=YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD;
        YandexNativeAdapter.AdaptedRoute baseline=YandexNativeAdapter.prepare(source,100,200,0,new Source(source,raw(2)),donor);
        YandexNativeAdapter.AdaptedRoute merged=YandexNativeAdapter.prepare(source,100,200,0,new Source(source,raw(2)),donor.withExperiments(MERGE));
        NativePath.Link before=baseline.getSegmentInfo(0).getLinkInfo(0),after=merged.getSegmentInfo(0).getLinkInfo(0);
        assertEquals(2,links(merged));assertEquals(before.getRoadClass(),after.getRoadClass());assertEquals(before.getRoadName(),after.getRoadName());
        assertEquals(before.getLinkType(),after.getLinkType());assertEquals(before.getFormway(),after.getFormway());assertEquals(before.getLaneNum(),after.getLaneNum());assertEquals(before.getOwnership(),after.getOwnership());assertEquals(before.getRoadDirection(),after.getRoadDirection());
        assertEquals(before.getAdcode(),after.getAdcode());assertEquals(before.getURID(),after.getURID());assertEquals(before.hasMultiOut(),after.hasMultiOut());assertEquals(before.hasParallelRoad(),after.hasParallelRoad());assertEquals(before.hasMixFork(),after.hasMixFork());
    }

    @Test public void old67Plus2PlusZeroNeverBecomes69AcrossItsActualSectionAndClassBoundaries(){
        YandexRoute source=route(new double[]{0,67,69,69.2,806.2},new double[0],Arrays.asList(new YandexRoute.Section(0,1,"STRAIGHT","Seg3",3),new YandexRoute.Section(1,4,"RIGHT","Seg4",30)),Collections.emptyList(),Collections.emptyList());
        YandexNativeAdapter.AdaptedRoute merged=YandexNativeAdapter.prepare(source,100,200,0,new Source(source,raw(2),raw(7),RouteAttributes.RendererClass.unknown("coverage_gap"),raw(6)),YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD.withExperiments(MERGE));
        assertEquals(2,links(merged));assertEquals(67,merged.getSegmentInfo(0).getLength());assertEquals(739,merged.getSegmentInfo(1).getLinkInfo(0).getLength());
        assertEquals(0,audit(merged).get("retainedZeroLengthLinks"));assertEquals(2,merged.getSegmentInfo(0).getLinkInfo(0).getMainAction());
        assertEquals(NativeDonorProfile.STOCK_LIKE_BY_ROAD.roadClass(null,raw(6)).roadClass,merged.getSegmentInfo(1).getLinkInfo(0).getRoadClass());
    }

    @Test public void sectionManeuverSignalAndStructureBoundariesRemainAtTheirSourceScalars(){
        List<YandexRoute.Section> sections=Arrays.asList(new YandexRoute.Section(0,2,"STRAIGHT","Road",3),new YandexRoute.Section(2,4,"RIGHT","Road",30));
        YandexRoute source=route(new double[]{0,67,69,69.2,806.2},new double[0],sections,Collections.emptyList(),Collections.emptyList());
        YandexNativeAdapter.AdaptedRoute sectioned=prepare(source,MERGE,raw(2));
        assertEquals(2,sectioned.getSegmentCount());assertEquals(1,sectioned.getSegmentInfo(0).getLinkCount());assertEquals(1,sectioned.getSegmentInfo(1).getLinkCount());
        assertEquals(2,sectioned.getSegmentInfo(0).getLinkInfo(0).getMainAction());assertEquals(1,sectioned.position(2).segmentIndex);
        for(boolean ferry:new boolean[]{false,true}){
            List<double[]> range=Collections.singletonList(new double[]{1,2});
            YandexRoute structured=route(new double[]{0,67,69,69.2,806.2},new double[0],null,ferry?Collections.emptyList():range,ferry?range:Collections.emptyList());
            YandexNativeAdapter.AdaptedRoute merged=prepare(structured,MERGE,raw(2));assertEquals(3,links(merged));
            assertEquals(ferry?NativeEnums.LINK_FERRY:NativeEnums.LINK_TUNNEL,merged.getSegmentInfo(0).getLinkInfo(1).getLinkType());assertTrue(audit(merged).get("retainedTiny").toString().contains("link_type_boundary"));
        }
        YandexRoute signaled=route(new double[]{0,67,69,69.2,806.2},new double[]{2},null,Collections.emptyList(),Collections.emptyList());
        YandexNativeAdapter.AdaptedRoute light=prepare(signaled,MERGE,raw(2));assertEquals(2,links(light));
        assertEquals(1,light.getSegmentInfo(0).getTrafficLightNum());assertTrue(light.getSegmentInfo(0).getLinkInfo(0).hasTrafficLight());assertEquals(0,light.position(2).linkIndex);
        assertEquals(signaled.lon[2],light.getAllTrafficLights().get(0).lon,0);assertEquals(0,audit(light).get("retainedZeroLengthLinks"));
    }

    @Test public void dominantClassUsesLongestOriginalConstituentWithEarliestTieAndRecomputedDonorHistory(){
        YandexRoute source=route(0,6,11,19);
        YandexNativeAdapter.AdaptedRoute merged=prepare(source,MERGE,raw(2),raw(3),raw(6));
        assertEquals(1,links(merged));assertEquals(8,merged.getSegmentInfo(0).getLinkInfo(0).getRoadClass());
        // Previous merged length 11 must not lend its inherited class an advantage over original length 8.
        YandexRoute tie=route(0,6,0);YandexNativeAdapter.AdaptedRoute tied=prepare(tie,MERGE,raw(2),raw(6));
        assertEquals(1,links(tied));assertEquals(7,tied.getSegmentInfo(0).getLinkInfo(0).getRoadClass());
        YandexNativeAdapter.Policy donorPolicy=YandexNativeAdapter.Policy.STOCK_LIKE_BY_ROAD.withExperiments(MERGE);
        YandexNativeAdapter.AdaptedRoute donor=YandexNativeAdapter.prepare(source,100,200,0,new Source(source,raw(2),raw(3),raw(6)),donorPolicy);
        int chosen=NativeDonorProfile.STOCK_LIKE_BY_ROAD.roadClass(null,raw(6)).roadClass;
        assertEquals(chosen,donor.getSegmentInfo(0).getLinkInfo(0).getRoadClass());
        for(double scalar:new double[]{0,1,2,2.5}){
            YandexNativeAdapter.Guidance guidance=new YandexNativeAdapter.Guidance();guidance.scalar=scalar;guidance.timeToFinishS=20;guidance.fixAgeMs=0;guidance.onRoute=true;
            NativeModels.NaviInfo navi=YandexNativeAdapter.navi(donor,guidance);assertEquals(0,navi.curLinkIdx);assertEquals((int)scalar,navi.curPointIdx);
            assertEquals(NativeDonorProfile.STOCK_LIKE_BY_ROAD.historySpeedKmh(chosen),navi.curLinkSpeed);
        }
        assertTrue(audit(donor).get("mapping").toString().contains("donorRule="));
    }

    @Test public void baselineAndAllPolicyCopiesPreserveFlagsAndCombinedPartitionIsCoherent(){
        YandexNativeAdapter.Policy policy=YandexNativeAdapter.Policy.TEST_ROAD_DATA.withExperiments(AdapterExperiments.CANDIDATE)
                .withFrame(Coordinates.Frame.WGS84).withHistorySpeed(YandexNativeAdapter.HistorySpeedPolicy.OBSERVED_HISTORY_ONLY)
                .withPublicRoadEvidence(PublicRoadEvidence.EMPTY).withLaneEvidence(OwnerLaneEvidence.EMPTY).withCompatibilityCn(true).withDonorProfile(null);
        assertSame(AdapterExperiments.CANDIDATE,policy.experiments);assertSame(AdapterExperiments.BASELINE,YandexNativeAdapter.Policy.TEST_ROAD_DATA.experiments);
        YandexRoute source=route(new double[]{0,67.4,69.6,69.8,806.8},new double[]{3.9},null,Collections.emptyList(),Collections.emptyList());
        YandexNativeAdapter.AdaptedRoute baseline=prepare(source,AdapterExperiments.BASELINE,raw(2));
        YandexNativeAdapter.AdaptedRoute explicit=YandexNativeAdapter.prepare(source,100,200,0,new Source(source,raw(2)),YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        assertEquals(links(explicit),links(baseline));assertFalse(baseline.attributeEvidence().containsKey("shortLinkMerge"));
        for(int i=0;i<links(baseline);i++){NativePath.Link a=baseline.getSegmentInfo(0).getLinkInfo(i),b=explicit.getSegmentInfo(0).getLinkInfo(i);assertEquals(a.get64TopoID(),b.get64TopoID());assertEquals(a.getLength(),b.getLength());}
        YandexNativeAdapter.AdaptedRoute combined=YandexNativeAdapter.prepare(source,100,200,0,new Source(source,raw(2)),policy);
        assertTrue(links(combined)<links(baseline));assertEquals(0,navi(combined,0).routeRemainLightCount);assertEquals(1,navi(combined,3.5).routeRemainLightCount);
        for(double scalar:new double[]{0,1,2,3,3.5,3.9,4})assertEquals(baseline.position(scalar).routeRemainM,combined.position(scalar).routeRemainM);
    }
}
