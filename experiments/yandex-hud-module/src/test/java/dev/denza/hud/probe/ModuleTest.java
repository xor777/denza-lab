package dev.denza.hud.probe;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Host tests of wire compatibility and visibility/route gates, without the foreign APK. */
public final class ModuleTest {
    public static class Config { public boolean isBackgroundOnRouteEnabled() { return enabled; } boolean enabled = true; }
    public static class Automotive {
        public String getRouteStatus() { return status; }
        public Object getWindshield() { return windshield; }
        public Object getSpeedLimit() { return limit; }
        String status = "ON_ROUTE"; Windshield windshield; Limit limit;
    }
    public enum Action { RIGHT, ENTER_ROUNDABOUT, LEAVE_ROUNDABOUT, FINISH }
    public static class Limit { public double getValue() { return 16.67; } public String getText() { return "60"; } }
    public static class Position {
        public double distanceToFinish() { return finish; } public double timeToFinish() { return finish / 10; }
        final double finish; Position(double finish) { this.finish = finish; }
    }
    public static class Leave { public int getExitNumber() { return exit; } final int exit; Leave(int exit) { this.exit = exit; } }
    public static class Metadata { public Leave getLeaveRoundaboutMetadata() { return leave; } final Leave leave; Metadata(Leave leave) { this.leave = leave; } }
    public static class Annotation {
        public Action getAction() { return action; } public String getToponym() { return toponym; }
        public Metadata getActionMetadata() { return metadata; }
        final Action action; final String toponym; final Metadata metadata;
        Annotation(Action action, String toponym, Metadata metadata) { this.action = action; this.toponym = toponym; this.metadata = metadata; }
    }
    public static class Manoeuvre {
        public Position getPosition() { return position; } public Annotation getAnnotation() { return annotation; }
        final Position position; final Annotation annotation;
        Manoeuvre(double finish, Annotation annotation) { position = new Position(finish); this.annotation = annotation; }
    }
    public static class Windshield {
        public List<Manoeuvre> getManoeuvres() { return manoeuvres; } public List<Object> getLaneSigns() { return java.util.Collections.emptyList(); }
        List<Manoeuvre> manoeuvres = java.util.Collections.emptyList();
    }
    public static class Route { public Position getRoutePosition() { return new Position(1000); } }
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
        check(NavigatorSource.speedLimit(16.666f)==60 && NavigatorSource.speedLimit(27.78f)==100,"road-event limits are m/s, shown in km/h");
        check(NavigatorSource.speedLimit(null)==0 && NavigatorSource.speedLimit(0f)==0 && NavigatorSource.speedLimit(Float.NaN)==0,"no limit, no number");
        // The hint comes from guidance itself: the passed manoeuvre is skipped, metres are measured
        // along the route, the limit is m/s on the wire from MapKit and km/h on the HUD.
        guidance.route=new Route();
        Windshield windshield=new Windshield();guidance.navigation.automotive.windshield=windshield;
        windshield.manoeuvres=Arrays.asList(new Manoeuvre(1100,new Annotation(Action.RIGHT,"Старая",null)),
                new Manoeuvre(750,new Annotation(Action.RIGHT," Тверская ",null)));
        guidance.navigation.automotive.limit=new Limit();
        guidance.location.accuracy="FINE";guidance.location.location.stamp++;
        NavigatorSource.Hint hint=source.read(guidance,true,3400).hint;
        check(source.hintFailure==null,"hint read: "+source.hintFailure);
        check(hint!=null&&"right".equals(hint.icon)&&hint.stockId==2&&hint.distance==250&&hint.road.equals("Тверская"),"next manoeuvre ahead, with its street");
        check(hint.remainingMeters==1000&&hint.remainingSeconds==100&&hint.speedLimit==60,"remaining route and the posted limit");
        windshield.manoeuvres=Arrays.asList(new Manoeuvre(750,new Annotation(Action.RIGHT,"Моховая улица",null)));
        guidance.config.enabled=true;
        NavigatorSource.Hint visibleHint=source.read(guidance,true,3410).hint;
        NavigatorSource.Hint hiddenHint=source.read(guidance,false,3420).hint;
        check(visibleHint.road.equals("Моховая ул.")&&hiddenHint.road.equals(visibleHint.road),"street formatting is independent of Activity visibility");
        Map<Integer,Object> compactPacket=fields(HudWire.road(true,hiddenHint,new byte[]{7,7}));
        check(text(compactPacket,10).equals("Моховая ул.")&&compactPacket.get(9).equals(250L)&&compactPacket.get(28).equals(2L),"compact UTF-8 street reaches field 10 without changing the manoeuvre");
        windshield.manoeuvres=Arrays.asList(new Manoeuvre(750,new Annotation(Action.RIGHT,"Raketostroiteley Avenue",null)));
        check(source.read(guidance,false,3430).hint.road.equals("Raketostroiteley Ave"),"hidden route changes never reuse a previous street");
        windshield.manoeuvres=Arrays.asList(new Manoeuvre(750,new Annotation(Action.RIGHT,null,null)));
        check(source.read(guidance,false,3440).hint.road.isEmpty(),"missing toponym clears the street instead of retaining it");
        windshield.manoeuvres=Arrays.asList(new Manoeuvre(900,new Annotation(Action.ENTER_ROUNDABOUT,"",null)),
                new Manoeuvre(850,new Annotation(Action.LEAVE_ROUNDABOUT,"",new Metadata(new Leave(3)))));
        hint=source.read(guidance,true,3500).hint;
        check("roundabout3".equals(hint.iconKey())&&hint.stockId==13,"a roundabout takes its exit from the manoeuvre that leaves it");
        guidance.navigation.automotive.limit=null;windshield.manoeuvres=java.util.Collections.emptyList();
        hint=source.read(guidance,true,3600).hint;
        check(hint.icon==null&&hint.speedLimit==0&&hint.remainingMeters==1000,"no manoeuvre and no sign stay unsent");

        Map<Integer,Object> road=fields(HudWire.road(true,withManoeuvre(),new byte[]{7,7}));
        check(road.get(2).equals(2L)&&road.get(16).equals(2L)&&road.get(3).equals(4200L)&&road.get(4).equals(660L),"route totals");
        check(Arrays.equals((byte[])road.get(8),new byte[]{7,7})&&road.get(9).equals(250L)&&road.get(28).equals(2L),"manoeuvre picture, metres and id");
        check(text(road,10).equals("Тверская")&&road.get(11).equals(60L)&&text(road,26).equals("4,2 км")&&text(road,27).equals("11 мин"),"street, limit and summary texts");
        NavigatorSource.Hint bare=new NavigatorSource.Hint();
        check(Arrays.equals(HudWire.road(true,bare,null),HudWire.road(true)),"nothing known sends only 'navigating'");
        check(Arrays.equals(HudWire.road(false,withManoeuvre(),new byte[]{7}),HudWire.road(false)),"the clear never carries a hint");
        check(HudWire.duration(59).equals("1 мин")&&HudWire.duration(3600).equals("1 ч")&&HudWire.duration(3900).equals("1 ч 5 мин"),"remaining time text");
        check(HudWire.distance(950).equals("950 м")&&HudWire.distance(12345).equals("12 км"),"route distance text");
        check(NavigatorSource.manoeuvre("FORK_LEFT")[1].equals("3")&&NavigatorSource.manoeuvre("FINISH")[0].equals("finish")&&NavigatorSource.manoeuvre("UNKNOWN")==null,"field 28 table");
        System.out.println("36 native module assertions passed");
    }
    private static NavigatorSource.Hint withManoeuvre() {
        NavigatorSource.Hint hint=new NavigatorSource.Hint();
        hint.icon="right";hint.stockId=2;hint.distance=250;hint.road="Тверская";hint.remainingMeters=4200;hint.remainingSeconds=660;hint.speedLimit=60;
        return hint;
    }
    /** Fields of the road message inside its field-1 envelope: varints as Long, the rest as bytes. */
    private static Map<Integer,Object> fields(byte[] packet) {
        int[] at={0};
        check(varint(packet,at)==10&&varint(packet,at)==packet.length-at[0],"field-1 envelope");
        Map<Integer,Object> result=new TreeMap<>();
        while(at[0]<packet.length){
            long key=varint(packet,at);
            if((key&7)==0) result.put((int)(key>>>3),varint(packet,at));
            else{int length=(int)varint(packet,at);result.put((int)(key>>>3),Arrays.copyOfRange(packet,at[0],at[0]+length));at[0]+=length;}
        }
        return result;
    }
    private static String text(Map<Integer,Object> fields,int field) { return new String((byte[])fields.get(field),StandardCharsets.UTF_8); }
    private static long varint(byte[] input,int[] at) {
        long value=0;int shift=0;
        while(true){int b=input[at[0]++]&255;value|=(long)(b&127)<<shift;if((b&128)==0)return value;shift+=7;}
    }
    private static void check(boolean ok,String message) { if(!ok)throw new AssertionError(message); }
}
