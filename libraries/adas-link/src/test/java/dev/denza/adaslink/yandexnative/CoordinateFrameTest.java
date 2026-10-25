package dev.denza.adaslink.yandexnative;

import dev.denza.adaslink.Coordinates;
import dev.denza.adaslink.YandexRoute;
import dev.denza.adaslink.stocknative.NativeModels;
import dev.denza.adaslink.stocknative.NativePath;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Coordinate input-boundary tests, separate from the formula's native-vector oracle. */
public final class CoordinateFrameTest {
    private static final Coordinates.Frame CANDIDATE=Coordinates.Frame.NATIVE_GCJ_UNRESTRICTED_CANDIDATE;

    private static YandexRoute route() {
        return new YandexRoute("frame-independent-source",new double[]{37,37.001,37.002,37.004},
                new double[]{55,55.0003,55.001,55.0015},
                Arrays.asList(new YandexRoute.Section(0,1.5,"STRAIGHT","First",12),
                        new YandexRoute.Section(1.5,3,"LEFT","Second",14)),
                null,new double[]{0.5,2.5},Collections.emptyList(),Collections.emptyList(),null,null);
    }

    private static YandexNativeAdapter.AdaptedRoute prepare(YandexRoute source,Coordinates.Frame frame) {
        return YandexNativeAdapter.prepare(source,101,202,0,null,
                YandexNativeAdapter.Policy.EXPLICIT_FALSE.withFrame(frame));
    }

    private static YandexNativeAdapter.Location fix(double scalar) {
        YandexNativeAdapter.Location fix=new YandexNativeAdapter.Location();
        fix.scalar=scalar;fix.matchedLon=37.0013;fix.matchedLat=55.0005;
        fix.rawLon=37.0012;fix.rawLat=55.0006;fix.speedMps=10;fix.headingDeg=92;
        return fix;
    }

    private static void samePosition(YandexNativeAdapter.Position a,YandexNativeAdapter.Position b) {
        assertEquals(a.segmentIndex,b.segmentIndex);assertEquals(a.linkIndex,b.linkIndex);
        assertEquals(a.pointIndex,b.pointIndex);assertEquals(a.linkRemainM,b.linkRemainM);
        assertEquals(a.segmentRemainM,b.segmentRemainM);assertEquals(a.routeRemainM,b.routeRemainM);
        assertEquals(a.bearingDeg,b.bearingDeg,0);assertEquals(a.scalar,b.scalar,0);
    }

    @Test public void frameSelectionPreservesWgsGeometryMetricsIdentitiesAndProgress() {
        YandexRoute source=route();
        YandexNativeAdapter.AdaptedRoute wgs=prepare(source,Coordinates.Frame.WGS84),sdk=prepare(source,CANDIDATE);
        assertTrue(wgs.admitted());assertTrue(sdk.admitted());
        assertEquals(Coordinates.Frame.WGS84,wgs.frame());assertEquals(CANDIDATE,sdk.frame());
        assertEquals(wgs.getPathID(),sdk.getPathID());assertEquals(wgs.getSegmentCount(),sdk.getSegmentCount());
        for(int si=0;si<wgs.getSegmentCount();si++) {
            NativePath.Segment a=wgs.getSegmentInfo(si),b=sdk.getSegmentInfo(si);
            assertEquals(a.getLength(),b.getLength());assertEquals(a.getTravelTime(),b.getTravelTime());
            assertEquals(a.getLinkCount(),b.getLinkCount());
            for(int li=0;li<a.getLinkCount();li++) {
                NativePath.Link x=a.getLinkInfo(li),y=b.getLinkInfo(li);
                assertEquals(x.get64TopoID(),y.get64TopoID());assertEquals(x.getLength(),y.getLength());
            }
        }
        for(double at:new double[]{0,0.25,0.5,1,1.5,2,2.5,2.75,3}) {
            samePosition(wgs.position(at),sdk.position(at));
            assertArrayEquals(wgs.coordinate(at),sdk.coordinate(at),0);
            YandexNativeAdapter.Guidance guidance=new YandexNativeAdapter.Guidance();
            guidance.scalar=at;guidance.timeToFinishS=20;
            NativeModels.NaviInfo a=YandexNativeAdapter.navi(wgs,guidance),b=YandexNativeAdapter.navi(sdk,guidance);
            assertEquals(a.curSegIdx,b.curSegIdx);assertEquals(a.curLinkIdx,b.curLinkIdx);
            assertEquals(a.curPointIdx,b.curPointIdx);assertEquals(a.linkRemainDist,b.linkRemainDist);
            assertEquals(a.routeRemain.dist,b.routeRemain.dist);
        }
        assertArrayEquals(new double[]{37,55},sdk.coordinate(0),0);
    }

    @Test public void everyStaticCoordinateIsTransformedOnceAtTheNativeBoundary() {
        YandexNativeAdapter.AdaptedRoute sdk=prepare(route(),CANDIDATE);
        double[][][] scalars={{{0,0.5},{0.5,1,1.5}},{{1.5,2,2.5},{2.5,3}}};
        for(int si=0;si<scalars.length;si++)for(int li=0;li<scalars[si].length;li++) {
            List<NativePath.IntPoint> points=sdk.getSegmentInfo(si).getLinkInfo(li).getPoints();
            assertEquals(scalars[si][li].length,points.size());
            for(int pi=0;pi<points.size();pi++) {
                double[] wgs=sdk.coordinate(scalars[si][li][pi]);
                double[] once=Coordinates.toSdk(wgs[0],wgs[1],CANDIDATE);
                double[] twice=Coordinates.toSdk(once[0],once[1],CANDIDATE);
                assertEquals(Coordinates.units(once[0]),points.get(pi).lon);
                assertEquals(Coordinates.units(once[1]),points.get(pi).lat);
                assertNotEquals(Coordinates.units(twice[0]),points.get(pi).lon);
            }
        }
        List<NativePath.Point> lights=sdk.getAllTrafficLights();assertEquals(2,lights.size());
        for(int i=0;i<lights.size();i++) {
            double[] wgs=sdk.coordinate(i==0?0.5:2.5);
            double[] once=Coordinates.toSdk(wgs[0],wgs[1],CANDIDATE);
            assertEquals(once[0],lights.get(i).lon,0);assertEquals(once[1],lights.get(i).lat,0);
        }
    }

    @Test public void rawAndMatchedFixesAreIndependentAndEachTransformedOnceIncludingOffRoute() {
        YandexNativeAdapter.AdaptedRoute sdk=prepare(route(),CANDIDATE);
        for(boolean onRoute:new boolean[]{true,false}) {
            YandexNativeAdapter.Location fix=fix(1.25);fix.onRoute=onRoute;
            NativeModels.LocInfo result=YandexNativeAdapter.location(sdk,fix);assertNotNull(result);
            double[] raw=Coordinates.toSdk(fix.rawLon,fix.rawLat,CANDIDATE);
            double[] matched=Coordinates.toSdk(fix.matchedLon,fix.matchedLat,CANDIDATE);
            assertEquals(raw[0],result.gpsPos.lon,0);assertEquals(raw[1],result.gpsPos.lat,0);
            assertEquals(matched[0],result.matchInfo.get(0).stPos.lon,0);
            assertEquals(matched[1],result.matchInfo.get(0).stPos.lat,0);
            assertNotEquals(result.gpsPos.lon,result.matchInfo.get(0).stPos.lon,0);
            double[] twice=Coordinates.toSdk(raw[0],raw[1],CANDIDATE);
            assertNotEquals(twice[0],result.gpsPos.lon,0);
            assertEquals(37.0012,fix.rawLon,0);assertEquals(37.0013,fix.matchedLon,0);
        }
    }

    @Test public void defaultAndExplicitWgsPreserveExistingNativeCoordinatesExactly() {
        YandexRoute source=route();
        YandexNativeAdapter.AdaptedRoute explicit=prepare(source,Coordinates.Frame.WGS84);
        YandexNativeAdapter.AdaptedRoute defaults=YandexNativeAdapter.prepare(source,101,202,0,null,
                YandexNativeAdapter.Policy.EXPLICIT_FALSE);
        assertEquals(Coordinates.Frame.WGS84,defaults.frame());
        NativePath.IntPoint first=defaults.getSegmentInfo(0).getLinkInfo(0).getPoints().get(0);
        assertEquals(133200000,first.lon);assertEquals(198000000,first.lat);
        for(int si=0;si<defaults.getSegmentCount();si++)for(int li=0;li<defaults.getSegmentInfo(si).getLinkCount();li++) {
            List<NativePath.IntPoint> a=defaults.getSegmentInfo(si).getLinkInfo(li).getPoints();
            List<NativePath.IntPoint> b=explicit.getSegmentInfo(si).getLinkInfo(li).getPoints();
            assertEquals(a.size(),b.size());
            for(int pi=0;pi<a.size();pi++) {assertEquals(a.get(pi).lon,b.get(pi).lon);assertEquals(a.get(pi).lat,b.get(pi).lat);}
        }
        for(int i=0;i<2;i++) {
            double[] expected=defaults.coordinate(i==0?0.5:2.5);
            assertEquals(expected[0],defaults.getAllTrafficLights().get(i).lon,0);
            assertEquals(expected[1],defaults.getAllTrafficLights().get(i).lat,0);
        }
        YandexNativeAdapter.Location fix=fix(1.25);NativeModels.LocInfo location=YandexNativeAdapter.location(defaults,fix);
        assertEquals(fix.rawLon,location.gpsPos.lon,0);assertEquals(fix.rawLat,location.gpsPos.lat,0);
        assertEquals(fix.matchedLon,location.matchInfo.get(0).stPos.lon,0);
        assertEquals(fix.matchedLat,location.matchInfo.get(0).stPos.lat,0);
    }

    @Test public void unsupportedRouteBlocksAdmissionAndUnsupportedFixReturnsNoObservation() {
        // Valid endpoints cannot conceal an unsupported interior vertex.
        YandexRoute outside=new YandexRoute("outside-envelope",new double[]{37,41,37.002},new double[]{55,55,55},
                Collections.singletonList(new YandexRoute.Section(0,2,"STRAIGHT","Outside",10)),
                null,new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
        YandexNativeAdapter.AdaptedRoute blocked=prepare(outside,CANDIDATE);
        assertFalse(blocked.admitted());assertFalse(blocked.blockers().isEmpty());
        assertTrue(prepare(outside,Coordinates.Frame.WGS84).admitted());
        YandexNativeAdapter.AdaptedRoute sdk=prepare(route(),CANDIDATE);
        assertTrue(sdk.supportsCoordinate(37,55));
        assertArrayEquals(Coordinates.toSdk(37,55,CANDIDATE),sdk.sdkCoordinate(37,55),0);
        assertFalse(sdk.supportsCoordinate(41,55));assertNull(sdk.sdkCoordinate(41,55));
        assertFalse(sdk.supportsCoordinate(37,59));assertNull(sdk.sdkCoordinate(37,59));
        assertFalse(sdk.supportsCoordinate(Double.NaN,55));assertNull(sdk.sdkCoordinate(Double.NaN,55));
        YandexNativeAdapter.AdaptedRoute wgs=prepare(route(),Coordinates.Frame.WGS84);
        assertTrue(wgs.supportsCoordinate(41,55));assertArrayEquals(new double[]{41,55},wgs.sdkCoordinate(41,55),0);
        for(boolean onRoute:new boolean[]{true,false})for(boolean raw:new boolean[]{true,false}) {
            YandexNativeAdapter.Location fix=fix(1.25);fix.onRoute=onRoute;
            if(raw)fix.rawLon=41;else fix.matchedLat=59;
            assertNull(YandexNativeAdapter.location(sdk,fix));
        }
    }
}
