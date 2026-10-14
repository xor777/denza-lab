package dev.denza.hud.probe;

import java.util.Arrays;

/** Host tests of wire compatibility and visibility/route gates, without the foreign APK. */
public final class ModuleTest {
    public static class Config { public boolean isBackgroundOnRouteEnabled() { return enabled; } boolean enabled = true; }
    public static class Automotive { public String getRouteStatus() { return status; } String status = "ON_ROUTE"; }
    public static class Navigation { public Automotive getGuidance() { return automotive; } final Automotive automotive = new Automotive(); }
    public static class Point { public double getLatitude() { return 55.7; } }
    public static class Location {
        public long getAbsoluteTimestamp() { return stamp; }
        public Point getPosition() { return new Point(); }
        public Double getHeading() { return 0d; }
        long stamp = 1;
    }
    public static class Classified {
        public Location getLocation() { return location; }
        public String getLocationClass() { return accuracy; }
        final Location location = new Location(); String accuracy = "FINE";
    }
    public static class Guidance {
        public boolean isValid() { return true; }
        public Object route() { return route; }
        public boolean isGuidanceResumed() { return resumed; }
        public Config bgConfigurator() { return config; }
        public Navigation navigation() {
            if (!resumed || route == null) throw new AssertionError("Native guidance accessed before an active route");
            return navigation;
        }
        public Object getLocation() { return location; }
        public Object getRoutePosition() { return location == null ? null : new Object(); }
        public Object getNextManeuver() { return null; }
        public Double getNextLaneSignDistance() { return null; }
        Classified location;
        Object route = new Object(); boolean resumed = true;
        final Config config = new Config(); final Navigation navigation = new Navigation();
    }
    public static void main(String[] args) {
        check(Arrays.equals(HudWire.road(true),new byte[]{10,5,16,2,(byte)128,1,2}),"active road protobuf");
        check(Arrays.equals(HudWire.road(false),new byte[]{10,5,16,2,(byte)128,1,1}),"clear road protobuf");
        check(Arrays.equals(HudWire.map(new byte[]{0,1,2}),new byte[]{10,4,'A','A','E','C'}),"map has no road envelope");
        NavigatorSource source = new NavigatorSource(new Reflect());
        Guidance guidance = new Guidance();
        check(source.read(guidance,true,1).active(),"visible active route");
        check(source.read(guidance,false,2).active(),"hidden active route with background enabled");
        guidance.config.enabled=false;
        check(!source.read(guidance,false,3).active(),"hidden without background must clear");
        check(source.read(guidance,true,4).active(),"background preference cannot suppress visible navigation");
        guidance.resumed=false;
        check(!source.read(guidance,true,5).active(),"preview/suspended route must stay off");
        guidance.resumed=true;guidance.navigation.automotive.status="ROUTE_FINISHED";
        check(!source.read(guidance,true,6).active(),"finished route must stay off");
        guidance.navigation.automotive.status="ROUTE_LOST";
        check(source.read(guidance,true,7).mode.equals("rerouting"),"lost route does not keep stale map");
        guidance.navigation.automotive.status="ON_ROUTE";
        check(source.read(guidance,true,8).mode.equals("waiting-location"),"missing location does not keep stale map");
        guidance.route=null;
        check(!source.read(guidance,true,9).active(),"cancellation stays off");
        guidance.route=new Object();guidance.location=new Classified();
        check(source.read(guidance,true,100).drawMap(),"fresh guidance draws the map");
        check(!source.read(guidance,true,3101).drawMap(),"stale guidance cannot retain the map");
        guidance.location.location.stamp++;
        check(source.read(guidance,true,3200).drawMap(),"fresh sample recovers rendering");
        guidance.location.accuracy="COARSE";
        check(!source.read(guidance,true,3300).drawMap(),"coarse fix cannot draw a precise route");
        System.out.println("16 native module assertions passed");
    }
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
}
