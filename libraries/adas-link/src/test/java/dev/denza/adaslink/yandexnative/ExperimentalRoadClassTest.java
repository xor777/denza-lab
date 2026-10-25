package dev.denza.adaslink.yandexnative;

import dev.denza.adaslink.RoadNetwork;
import dev.denza.adaslink.RouteAttributes;
import dev.denza.adaslink.YandexRoute;
import dev.denza.adaslink.stocknative.NativeModels;
import dev.denza.adaslink.stocknative.NativePath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent input policy checks; these do not establish sender or vehicle acceptance. */
public final class ExperimentalRoadClassTest {
    private static YandexRoute route() {
        return new YandexRoute("typed-renderer-input", new double[]{37,37.001,37.002},
                new double[]{55,55,55},
                Collections.singletonList(new YandexRoute.Section(0,2,"STRAIGHT","Road",20)),
                null,new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
    }

    private static RouteAttributes.RendererClass raw(int category, boolean link) {
        return RouteAttributes.RendererClass.literal(category+(link?"_link":""),"string",category,"uint32");
    }

    private static final class Source implements RouteAttributes {
        final YandexRoute route;
        final RouteAttributes.RendererClass[] intervals;
        final List<double[]> queries=new ArrayList<>();
        Source(YandexRoute route, RouteAttributes.RendererClass... intervals) {
            this.route=route;this.intervals=intervals;
        }
        public boolean matches(YandexRoute value) { return value==route; }
        public double[] cuts() {
            double[] cuts=new double[intervals.length-1];
            for(int i=0;i<cuts.length;i++)cuts[i]=2d*(i+1)/intervals.length;
            return cuts;
        }
        public RoadNetwork.KnownAttributes attributesBetween(double begin,double end) {
            throw new AssertionError("Legacy converted attributes are not raw renderer evidence");
        }
        public RouteAttributes.RendererClass rendererClassBetween(double begin,double end) {
            int index=(int)Math.round(begin*intervals.length/2);
            assertEquals(2d*index/intervals.length,begin,0);
            assertEquals(2d*(index+1)/intervals.length,end,0);
            queries.add(new double[]{begin,end});
            return intervals[index];
        }
        public Map<String,Object> summary() {
            return Collections.<String,Object>singletonMap("source","independent-typed-interval-fixture");
        }
    }

    private static YandexNativeAdapter.AdaptedRoute prepare(Source source,YandexNativeAdapter.Policy policy) {
        return YandexNativeAdapter.prepare(source.route,101,202,0,source,policy);
    }

    @Test public void experimentalPolicyUsesWholeRawIntervalsAndPreservesTheDocumentedGroups() {
        int[] expected={6,7,7,7,8,8,9,9,9};
        for(boolean link:new boolean[]{false,true})for(int category=1;category<=9;category++) {
            YandexRoute route=route();Source source=new Source(route,raw(category,link));
            YandexNativeAdapter.AdaptedRoute adapted=prepare(source,YandexNativeAdapter.Policy.TEST_ROAD_DATA);
            assertTrue(adapted.admitted());
            int actual=adapted.getSegmentInfo(0).getLinkInfo(0).getRoadClass();
            assertEquals("raw class "+category+(link?"_link":""),expected[category-1],actual);
            assertNotEquals("No renderer class independently establishes native Highway",0,actual);
            assertEquals(1,source.queries.size());
        }
    }

    @Test public void classOneProjectionRequiresTheExplicitTestPolicy() {
        YandexRoute route=route();
        for(YandexNativeAdapter.Policy policy:new YandexNativeAdapter.Policy[]{
                YandexNativeAdapter.Policy.STRICT,YandexNativeAdapter.Policy.EXPLICIT_FALSE}) {
            YandexNativeAdapter.AdaptedRoute adapted=prepare(new Source(route,raw(1,false)),policy);
            assertEquals(-1,adapted.getSegmentInfo(0).getLinkInfo(0).getRoadClass());
        }
        assertEquals(6,prepare(new Source(route,raw(1,false)),YandexNativeAdapter.Policy.TEST_ROAD_DATA)
                .getSegmentInfo(0).getLinkInfo(0).getRoadClass());
    }

    @Test public void legacyKnownAttributesCannotSupplyRoadClassWithoutRawEvidence() {
        final YandexRoute route=route();
        RouteAttributes legacy=new RouteAttributes() {
            public boolean matches(YandexRoute value) { return value==route; }
            public double[] cuts() { return new double[]{1}; }
            public RoadNetwork.KnownAttributes attributesBetween(double begin,double end) {
                throw new AssertionError("Adapter must not query legacy converted enums");
            }
            public Map<String,Object> summary() { return Collections.emptyMap(); }
        };
        YandexNativeAdapter.AdaptedRoute adapted=YandexNativeAdapter.prepare(route,101,202,0,legacy,
                YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        assertEquals(2,adapted.getSegmentInfo(0).getLinkCount());
        for(int i=0;i<2;i++)assertEquals(-1,adapted.getSegmentInfo(0).getLinkInfo(i).getRoadClass());
    }

    @Test public void conflictAbsentNonDrivingAndInconsistentTypedValuesStayUnknown() {
        RouteAttributes.RendererClass[] unavailable={
            RouteAttributes.RendererClass.unknown("source_unavailable"),
            RouteAttributes.RendererClass.consensus(raw(1,false),raw(2,false)),
            RouteAttributes.RendererClass.literal("1","string",2,"uint32"),
            RouteAttributes.RendererClass.literal("1","string",1d,"float"),
            RouteAttributes.RendererClass.literal("1","string","1","string"),
            RouteAttributes.RendererClass.literal(1,"uint32",1,"uint32"),
            RouteAttributes.RendererClass.literal("pedestrian","string",1,"uint32")
        };
        Source source=new Source(route(),unavailable);
        YandexNativeAdapter.AdaptedRoute adapted=prepare(source,YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        for(int i=0;i<unavailable.length;i++)assertEquals(-1,adapted.getSegmentInfo(0).getLinkInfo(i).getRoadClass());
        assertEquals(unavailable.length,source.queries.size());
        assertEquals(-1,YandexNativeAdapter.prepare(route(),101,202,0,null,
                YandexNativeAdapter.Policy.TEST_ROAD_DATA).getSegmentInfo(0).getLinkInfo(0).getRoadClass());
    }

    @Test public void staticAndDynamicRoadClassesUseTheSamePartitionAtEveryBoundary() {
        Source source=new Source(route(),raw(1,false),raw(2,false),raw(5,true),raw(9,false));
        YandexNativeAdapter.AdaptedRoute adapted=prepare(source,YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        double[] scalars={0,0.25,0.5,0.75,1,1.25,1.5,1.75,2};
        int[] links={0,0,1,1,2,2,3,3,3};
        int[] classes={6,7,8,9};
        for(int i=0;i<scalars.length;i++) {
            double scalar=scalars[i];
            YandexNativeAdapter.Location location=new YandexNativeAdapter.Location();
            location.scalar=scalar;location.matchedLon=37+scalar*0.001;location.matchedLat=55;
            location.rawLon=location.matchedLon+0.00001;location.rawLat=55.00001;
            location.speedMps=10;location.headingDeg=90;
            NativeModels.LocInfo dynamic=YandexNativeAdapter.location(adapted,location);
            assertNotNull(dynamic);
            assertEquals(links[i],adapted.position(scalar).linkIndex);
            NativePath.Link fixed=adapted.getSegmentInfo(0).getLinkInfo(links[i]);
            assertEquals(classes[links[i]],fixed.getRoadClass());
            assertEquals(fixed.getRoadClass(),dynamic.matchInfo.get(0).roadClass);
            assertEquals(fixed.get64TopoID().longValue(),dynamic.matchInfo.get(0).linkId);
        }
    }

    @Test public void everyLinkExposesItsSourceAndPolicyDecisionIncludingAmbiguity() {
        Source source=new Source(route(),raw(1,false),raw(4,true),
                RouteAttributes.RendererClass.unknown("raw_class_or_type_conflict"));
        YandexNativeAdapter.AdaptedRoute adapted=prepare(source,YandexNativeAdapter.Policy.TEST_ROAD_DATA);
        assertEquals("independent-typed-interval-fixture",adapted.attributeEvidence().get("source"));
        for(int link=0;link<3;link++) {
            FieldDecision decision=null;
            for(FieldDecision candidate:adapted.fieldDecisions())
                if(candidate.field.equals("segment[0].link["+link+"].roadClass")) {
                    assertNull("Exactly one decision per link",decision);decision=candidate;
                }
            assertNotNull("Missing per-link road-class decision",decision);
            assertTrue(decision.source.contains("interval=["));
            assertTrue(decision.source.contains("quality="));
            assertTrue(decision.meaning.contains("policy=EXPERIMENTAL_FUNCTIONAL"));
            if(link==0) {
                assertEquals(FieldDecision.Kind.ENUM_MAPPING,decision.kind);
                assertTrue(decision.source.contains("class=1 type=string"));
                assertTrue(decision.source.contains("fc=1 type=uint32"));
                assertTrue(decision.meaning.contains("nativeCandidates=[0,6]"));
                assertTrue(decision.meaning.contains("exact 0 versus 6 unknown"));
            } else if(link==1) {
                assertTrue(decision.source.contains("class=4_link type=string"));
                assertTrue(decision.meaning.contains("chosen=7"));
            } else {
                assertEquals(FieldDecision.Kind.SDK_NULL,decision.kind);
                assertTrue(decision.source.contains("raw_class_or_type_conflict"));
                assertTrue(decision.meaning.contains("chosen=-1"));
            }
        }
    }
}
