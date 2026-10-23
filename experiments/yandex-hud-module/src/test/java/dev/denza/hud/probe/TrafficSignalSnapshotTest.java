package dev.denza.hud.probe;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** No native objects or network: literal signal values and explicit unknowns at API boundaries. */
public final class TrafficSignalSnapshotTest {
    private static int checks;
    public enum Signal { GREEN, RED, RED_AND_YELLOW, YELLOW }
    public enum Arrow { FORWARD, LEFT, RIGHT, UTURN_LEFT }
    public enum Section { MAIN, ADDITIONAL }
    public static class Position {
        public int getSegmentIndex() { return 3; }
        public double getSegmentPosition() { return .375; }
    }
    public static class RoutePosition {
        boolean matched=true; int positionReads;
        public boolean onRoute(String id) { return matched && id.equals("selected-route"); }
        public Position positionOnRoute(String id) { positionReads++; return new Position(); }
    }
    public static class Light {
        final RoutePosition position=new RoutePosition(); Signal signal=Signal.RED; Integer seconds=7;
        public String getId() { return "light-id"; }
        public Signal getSignal() { return signal; }
        public Integer getSecondsLeft() { return seconds; }
        public Arrow getArrow() { return Arrow.RIGHT; }
        public Section getSectionType() { return Section.ADDITIONAL; }
        public RoutePosition getPosition() { return position; }
    }
    public static class FailingLight extends Light {
        public Signal getSignal() { throw new IllegalStateException("private-message-not-to-export"); }
    }
    public static class Windshield {
        boolean valid=true; int listReads;
        List<?> lights=Collections.singletonList(new Light());
        public boolean isValid() { return valid; }
        public List<?> getTrafficLightsWithSignal() { listReads++; return lights; }
    }
    public static class Opaque {
        public String toString() { throw new AssertionError("Opaque object stringified"); }
    }

    public static void main(String[] args) {
        Reflect api=new Reflect(); Windshield windshield=new Windshield();
        Map<String,Object> snapshot=TrafficSignalSnapshot.read(api,windshield,"selected-route");
        check("known".equals(snapshot.get("status")),"readable signal list known");
        check(Boolean.TRUE.equals(snapshot.get("complete")),"complete means attempted fields read, not signal freshness");
        Map<?,?> light=first(snapshot);
        check("RED".equals(light.get("signal")),"literal state");
        check(Integer.valueOf(7).equals(light.get("secondsLeft")),"literal nullable seconds");
        check("RIGHT".equals(light.get("arrow")),"literal direction arrow");
        check("ADDITIONAL".equals(light.get("sectionType")),"main/additional section distinct");
        check("light-id".equals(light.get("id")),"light identity");
        Map<?,?> position=(Map<?,?>)light.get("position"), fraction=(Map<?,?>)position.get("polylinePosition");
        check(Boolean.TRUE.equals(position.get("onRoute")),"membership checked against selected route");
        check(Integer.valueOf(3).equals(fraction.get("segmentIndex")) && Double.valueOf(.375).equals(fraction.get("segmentPosition")),"source fractional position retained");
        check(snapshot.get("sourceTimestamp")==null && "unknown".equals(state(snapshot,"sourceTimestamp")),"no inferred source timestamp");
        windshield.lights=Collections.emptyList();
        snapshot=TrafficSignalSnapshot.read(api,windshield,"selected-route");
        check("known".equals(snapshot.get("status")) && ((List<?>)snapshot.get("trafficLightsWithSignal")).isEmpty(),"returned empty known list");
        windshield.lights=null;
        snapshot=TrafficSignalSnapshot.read(api,windshield,"selected-route");
        check("unknown".equals(snapshot.get("status")) && snapshot.get("trafficLightsWithSignal")==null,"null list is unknown");
        windshield.valid=false; int before=windshield.listReads;
        snapshot=TrafficSignalSnapshot.read(api,windshield,"selected-route");
        check("unknown".equals(snapshot.get("status")) && windshield.listReads==before,"invalid native windshield never traversed");
        windshield.valid=true; Light nullable=new Light(); nullable.signal=null; nullable.seconds=null;
        windshield.lights=Collections.singletonList(nullable);
        snapshot=TrafficSignalSnapshot.read(api,windshield,"selected-route");
        check(first(snapshot).get("signal")==null && first(snapshot).get("secondsLeft")==null,"absent state/countdown preserved");
        check("known".equals(state(snapshot,"trafficLightsWithSignal[0].signal")),"known-null signal distinct from failed getter");
        nullable.seconds=-1;
        check(Integer.valueOf(-1).equals(first(TrafficSignalSnapshot.read(api,windshield,"selected-route")).get("secondsLeft")),"literal sentinel not coerced to countdown");
        nullable.position.matched=false;
        snapshot=TrafficSignalSnapshot.read(api,windshield,"selected-route");
        check(((Map<?,?>)first(snapshot).get("position")).get("polylinePosition")==null,"foreign-route position not projected");
        check(nullable.position.positionReads==2,"positionOnRoute not called after membership false");
        check("unknown".equals(state(snapshot,"trafficLightsWithSignal[0].position.polylinePosition")),"foreign route explicitly unknown");
        windshield.lights=Arrays.asList(new FailingLight(),null,new Opaque());
        snapshot=TrafficSignalSnapshot.read(api,windshield,"selected-route");
        check("known".equals(snapshot.get("status")) && Boolean.FALSE.equals(snapshot.get("complete")),"partially unreadable list retained");
        check("unknown".equals(state(snapshot,"trafficLightsWithSignal[0].signal")),"throwing getter unknown");
        check(!RouteSnapshot.json(snapshot).contains("private-message"),"failure messages/handles not exported");
        check("unknown".equals(state(snapshot,"trafficLightsWithSignal[2].id")),"opaque item never stringified");
        check("unknown".equals(TrafficSignalSnapshot.read(api,null,"selected-route").get("status")),"missing windshield unknown");
        check("unknown".equals(TrafficSignalSnapshot.read(api,windshield,null).get("status")),"missing route identity unknown");
        System.out.println("TrafficSignalSnapshotTest: "+checks+" checks");
    }
    private static Map<?,?> first(Map<String,Object> value) { return (Map<?,?>)((List<?>)value.get("trafficLightsWithSignal")).get(0); }
    private static Object state(Map<?,?> snapshot,String path) { return ((Map<?,?>)snapshot.get("availability")).get(path); }
    private static void check(boolean condition,String message) { checks++; if(!condition)throw new AssertionError(message); }
}
