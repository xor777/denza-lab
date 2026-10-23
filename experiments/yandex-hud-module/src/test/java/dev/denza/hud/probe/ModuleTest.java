package dev.denza.hud.probe;

import dev.denza.hudlink.HudComposition;
import dev.denza.hudlink.HudFields;
import dev.denza.hudlink.HudImage;
import dev.denza.hudlink.HudMode;
import dev.denza.hudlink.HudRect;
import dev.denza.hudlink.HudRole;
import dev.denza.hudlink.HudScene;
import dev.denza.hudlink.HudSink;
import dev.denza.hudlink.HudSource;
import dev.denza.hudlink.HudWindow;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
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
        public List<Manoeuvre> getManoeuvres() { return manoeuvres; } public List<Object> getLaneSigns() { return lanes; }
        List<Object> lanes = java.util.Collections.emptyList();
        List<Manoeuvre> manoeuvres = java.util.Collections.emptyList();
    }
    public static class Route {
        String id = "route-a";
        public String getRouteId() { return id; }
        public Position getRoutePosition() { return new Position(1000); }
    }
    public static class Navigation { public Automotive getGuidance() { return automotive; } final Automotive automotive = new Automotive(); }
    public static class Point { public double getLatitude() { return 55.7; } }
    public static class Location {
        public long getAbsoluteTimestamp() { return stamp; }
        public Point getPosition() { return new Point(); }
        public Double getHeading() { return 0d; }
        public Double getSpeed() { return speed; }
        long stamp = 1;
        Double speed = 5d;
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
        public Double getNextLaneSignDistance() { return laneDistance; }
        Double laneDistance;
        Classified location;
        Object route = new Object(); boolean resumed = true;
        final Config config = new Config(); final Navigation navigation = new Navigation();
    }
    public static class LaneSign {
        public LaneSign getLaneSign() { return this; }
        public List<Lane> getLanes() { return Arrays.asList(new Lane()); }
    }
    public static class Lane {
        public String getLaneKind() { return "PLAIN_LANE"; }
        public List<String> getDirections() { return Arrays.asList("STRAIGHT_AHEAD", "RIGHT90"); }
        public String getHighlightedDirection() { return "RIGHT90"; }
    }
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("assets")) { assetNames(); return; }

        check(Arrays.equals(HudWire.road(true),new byte[]{10,7,16,2,(byte)128,1,2,40,0}),"active road protobuf");
        check(Arrays.equals(HudWire.road(false),new byte[]{10,7,16,2,(byte)128,1,1,40,0}),"clear road protobuf");
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
        check(source.read(guidance,true,7).mode.equals("waiting"),"no fix yet draws nothing, even while rebuilding");
        guidance.navigation.automotive.status="ON_ROUTE";
        check(source.read(guidance,true,8).mode.equals("waiting")&&!source.read(guidance,true,8).drawMap(),"no fix yet draws nothing");
        guidance.route=null;
        check(!source.read(guidance,true,9).active(),"cancellation stays off");
        guidance.route=new Object();guidance.location=new Classified();
        NavigatorSource.Frame held=source.read(guidance,true,100);
        check(held.drawMap()&&held.mode.equals("guidance")&&held.staleMs==0,"fresh guidance draws the map");
        check(held.speed==5,"a fresh fix carries its speed for the camera");
        held=source.read(guidance,true,3101);
        check(held.mode.equals("guidance")&&held.staleMs==3001&&held.drawMap()&&held.point!=null,"a gap in fixes holds the picture");
        check(Double.isNaN(held.speed),"a held fix says nothing of the speed now");
        held=source.read(guidance,true,30101);
        check(held.mode.equals("lost")&&held.drawMap(),"a long gap dims the map, never blanks it");
        guidance.location.location.stamp++;
        held=source.read(guidance,true,30200);
        check(held.mode.equals("guidance")&&held.staleMs==0,"a fresh fix recovers at once");
        guidance.location.accuracy="COARSE";
        held=source.read(guidance,true,30300);
        check(held.mode.equals("guidance")&&held.drawMap()&&held.point!=null,"a coarse fix keeps the last good one");
        guidance.navigation.automotive.status="ROUTE_LOST";
        held=source.read(guidance,true,30400);
        check(held.mode.equals("rerouting")&&held.drawMap()&&held.hint==null&&held.lanes.isEmpty(),"rebuilding keeps the map, without hint or lanes");
        guidance.navigation.automotive.status="ON_ROUTE";
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
        for(int metres:new int[]{-1,0,4,10}){
            NavigatorSource.Hint near=withManoeuvre();near.distance=metres;
            check(fields(HudWire.road(true,near,new byte[]{7,7})).get(9).equals(11L),"the last ten metres go out as 11, not the HUD's Chinese «now»: "+metres);
        }
        NavigatorSource.Hint bare=new NavigatorSource.Hint();
        check(Arrays.equals(HudWire.road(true,bare,null),HudWire.road(true)),"nothing known sends only 'navigating'");
        check(Arrays.equals(HudWire.road(false,withManoeuvre(),new byte[]{7}),HudWire.road(false)),"the clear never carries a hint");
        check(HudWire.duration(59).equals("1 мин")&&HudWire.duration(3600).equals("1 ч")&&HudWire.duration(3900).equals("1 ч 5 мин"),"remaining time text");
        check(HudWire.distance(950).equals("950 м")&&HudWire.distance(12345).equals("12 км"),"route distance text");
        check(NavigatorSource.manoeuvre("FORK_LEFT")[1].equals("3")&&NavigatorSource.manoeuvre("FINISH")[0].equals("finish")&&NavigatorSource.manoeuvre("UNKNOWN")==null,"field 28 table");
        nativeLanes();
        windshield.lanes=Arrays.<Object>asList(new LaneSign());guidance.laneDistance=100d;guidance.config.enabled=true;
        check(source.read(guidance,false,3700).lanes.size()==1,"background guidance reads actual lane signs");
        guidance.laneDistance=801d;
        check(source.read(guidance,false,3800).lanes.isEmpty(),"distant lane signs clear");
        guidance.laneDistance=Double.NaN;
        check(source.read(guidance,false,3801).lanes.isEmpty(),"non-finite distance clears");
        guidance.laneDistance=100d;windshield.lanes=java.util.Collections.emptyList();
        check(source.read(guidance,false,3900).lanes.isEmpty(),"missing lane sign clears during guidance");
        windshield.lanes=Arrays.<Object>asList(new LaneSign());
        check(source.read(guidance,false,7000).lanes.size()==1,"a held fix keeps the lanes");
        guidance.location.location.stamp++;guidance.config.enabled=false;
        check(source.read(guidance,false,7100).lanes.isEmpty(),"disabled background clears lanes");
        guidance.navigation.automotive.status="ROUTE_LOST";
        check(source.read(guidance,true,7200).lanes.isEmpty(),"rerouting clears lanes");
        roles();
        positionOwnership();
        System.out.println(assertions+" native module assertions passed");
    }

    /** Route replacement must not relabel the visual hold as a fresh pose on the new route. */
    private static void positionOwnership() {
        NavigatorSource source=new NavigatorSource(new Reflect());
        Guidance guidance=new Guidance(); guidance.location=new Classified(); guidance.route=new Route();
        NavigatorSource.Frame first=source.read(guidance,true,1000);
        check("route-a".equals(first.positionRouteId),"fresh position records its route owner");
        Route next=new Route(); next.id="route-b"; guidance.route=next;
        NavigatorSource.Frame retained=source.read(guidance,true,1100);
        check("route-a".equals(retained.positionRouteId),"route change without a new fix retains original position owner");
        check(retained.position==first.position && retained.point==first.point && retained.drawMap()
                && "guidance".equals(retained.mode),"ownership tagging preserves the existing HUD visual hold");
        guidance.location.location.stamp++;
        NavigatorSource.Frame fresh=source.read(guidance,true,1200);
        check("route-b".equals(fresh.positionRouteId) && fresh.position!=first.position,"next fresh fix attaches to replacement route");
        next=new Route(); next.id="route-c"; guidance.route=next;
        guidance.location.accuracy="COARSE"; guidance.location.location.stamp++;
        retained=source.read(guidance,true,1300);
        check("route-b".equals(retained.positionRouteId) && retained.position==fresh.position,"coarse fix cannot relabel retained position");
        guidance.route=null;
        check(source.read(guidance,true,1400).positionRouteId==null,"cancellation clears held position ownership");
        guidance.route=next; guidance.location.accuracy="FINE";
        check("route-c".equals(source.read(guidance,true,1500).positionRouteId),"resumed route starts from newly accepted fix");
        guidance.route=new Object(); guidance.location.location.stamp++;
        NavigatorSource.Frame unknown=source.read(guidance,true,1600);
        check(unknown.positionRouteId==null && unknown.drawMap(),"unavailable diagnostic owner does not suppress HUD");
    }
    /** What a frame publishes and withdraws through HudLink, and what the compositor makes of it. */
    private static void roles() {
        // A route without a fix: the black frame, an empty manoeuvre block and the explicit zero lanes.
        Map<HudRole,HudFields> roles=ModuleFields.of(null,null,0,null,null);
        check(roles.keySet().equals(EnumSet.of(HudRole.MANEUVER,HudRole.LANES)),"nothing known: manoeuvre and lanes only");
        check(roles.get(HudRole.MANEUVER).fields().length==0&&Arrays.equals(roles.get(HudRole.LANES).fields(),new int[]{5})
                &&roles.get(HudRole.LANES).value(5).equals(0L),"an empty block and «no lanes»");
        byte[] png=png(300,180);
        HudImage map=HudImage.png(png);
        SceneSink sink=new SceneSink();
        ModuleFields.publish(sink,map,roles);
        check(sink.calls.equals(Arrays.asList("layer MAP map","fields MANEUVER","fields LANES","withdraw ROAD_LIMIT","withdraw AR")),"published and withdrawn: "+sink.calls);
        HudComposition shown=sink.scene.compose(0);
        check(shown.mode==HudMode.NAVIGATION&&!HudRole.fillsNavigation(roles.get(HudRole.MANEUVER)),"the module's route opens navigation without a manoeuvre, as it always did");
        check(shown.passThrough(HudWindow.MAP)!=null&&shown.passThrough(HudWindow.MAP).image.png()==png,"the painter's PNG goes to the HUD as painted");
        check(Arrays.equals(shown.road(),HudWire.road(true)),"the frame's road packet is the snapshot's");
        check(Arrays.equals(shown.clear(),HudWire.road(false)),"the compositor's clear is the module's clear, lanes included");
        sink.withdraw(HudSource.MODULE);
        check(sink.scene.compose(0).mode==HudMode.OFF&&sink.scene.compose(0).road()==null,"withdrawn: navigation off");

        // Everything known: picture, metres, street, id, lanes, the limit and AR.
        HudAr.Frame ar=ar();
        roles=ModuleFields.of(withManoeuvre(),new byte[]{7},3,new byte[]{1,2,3},ar);
        check(roles.keySet().equals(EnumSet.of(HudRole.MANEUVER,HudRole.LANES,HudRole.ROAD_LIMIT,HudRole.AR)),"a full frame has every role");
        check(Arrays.equals(roles.get(HudRole.MANEUVER).fields(),new int[]{3,4,8,9,10,26,27,28}),"the manoeuvre block");
        check(Arrays.equals(roles.get(HudRole.LANES).fields(),new int[]{5,7})&&roles.get(HudRole.LANES).value(5).equals(3L),"the lane row and its count");
        check(Arrays.equals(roles.get(HudRole.ROAD_LIMIT).fields(),new int[]{11})&&roles.get(HudRole.ROAD_LIMIT).value(11).equals(60L),"the limit, km/h");
        check(Arrays.equals(roles.get(HudRole.AR).fields(),new int[]{19,20,21,22,30,31,32,33}),"AR geometry");
        sink=new SceneSink();
        ModuleFields.publish(sink,map,roles);
        check(sink.calls.equals(Arrays.asList("layer MAP map","fields MANEUVER","fields LANES","fields ROAD_LIMIT","fields AR")),"all published: "+sink.calls);
        check(Arrays.equals(sink.scene.compose(0).road(),HudWire.road(true,withManoeuvre(),new byte[]{7},3,new byte[]{1,2,3},ar)),"a full frame's road packet");
        check(sink.scene.compose(HudScene.LAYER_TTL_MS-1).mode==HudMode.NAVIGATION&&sink.scene.compose(HudScene.LAYER_TTL_MS).mode==HudMode.OFF
                &&sink.scene.compose(HudScene.LAYER_TTL_MS).layers(HudWindow.MAP).isEmpty(),"a silent module leaves the glass after 1.5 s");

        // A hint without a manoeuvre, a limit or AR: summaries only, the rest withdrawn.
        NavigatorSource.Hint ahead=withManoeuvre();ahead.icon=null;ahead.speedLimit=0;
        roles=ModuleFields.of(ahead,new byte[]{7},0,null,null);
        check(roles.keySet().equals(EnumSet.of(HudRole.MANEUVER,HudRole.LANES))&&Arrays.equals(roles.get(HudRole.MANEUVER).fields(),new int[]{3,4,26,27}),"summaries only");
        sink=new SceneSink();
        ModuleFields.publish(sink,map,roles);
        check(sink.calls.equals(Arrays.asList("layer MAP map","fields MANEUVER","fields LANES","withdraw ROAD_LIMIT","withdraw AR")),"limit and AR withdrawn: "+sink.calls);
        // A manoeuvre without its stock picture keeps metres, street and id; metres are never under 11,
        // already in the module's fields, for a Denza Apps host whose encoder has no floor.
        NavigatorSource.Hint passed=withManoeuvre();passed.distance=-1;
        HudFields block=ModuleFields.of(passed,null,0,null,null).get(HudRole.MANEUVER);
        check(Arrays.equals(block.fields(),new int[]{3,4,9,10,26,27,28})&&block.value(9).equals(11L),"no picture, metres floored at 11");
        passed.distance=7;
        check(ModuleFields.of(passed,null,0,null,null).get(HudRole.MANEUVER).value(9).equals(11L),"the module's own fields say 11 in the last ten metres");
        check(!ModuleFields.of(null,null,0,null,ar).containsKey(HudRole.AR)&&!ModuleFields.of(null,null,0,null,ar).containsKey(HudRole.ROAD_LIMIT),"AR and the limit ride on a hint");
        for(int count:new int[]{0,-1,7}){
            HudFields lanes=ModuleFields.of(null,null,count,new byte[]{1},null).get(HudRole.LANES);
            check(Arrays.equals(lanes.fields(),new int[]{5})&&lanes.value(5).equals(0L),"invalid lane count: explicit zero lanes "+count);
        }
        HudFields empty=ModuleFields.of(null,null,3,new byte[0],null).get(HudRole.LANES);
        check(Arrays.equals(empty.fields(),new int[]{5})&&empty.value(5).equals(0L),"an empty lane picture: explicit zero lanes");
    }
    /** A sink straight into a scene at time 0, writing its calls down. */
    private static final class SceneSink implements HudSink {
        final HudScene scene=new HudScene();
        final List<String> calls=new ArrayList<>();
        @Override public void layer(HudSource source,String id,HudRole role,HudRect rect,HudImage image,float opacity,long ttlMs) {
            check(source==HudSource.MODULE&&ttlMs==HudScene.LAYER_TTL_MS&&opacity==1f&&rect.equals(HudRect.whole(HudWindow.MAP)),"the map is the module's, whole and opaque");
            calls.add("layer "+role+" "+id);scene.layer(source,id,role,rect,image,opacity,ttlMs,0);
        }
        @Override public void removeLayer(HudSource source,String id) { calls.add("remove "+id);scene.removeLayer(source,id); }
        @Override public void fields(HudSource source,HudRole role,HudFields fields,long ttlMs) {
            check(source==HudSource.MODULE&&ttlMs==HudScene.LAYER_TTL_MS,"fields are the module's, for 1.5 s");
            calls.add("fields "+role);scene.fields(source,role,fields,ttlMs,0);
        }
        @Override public void withdraw(HudSource source,HudRole role) { calls.add("withdraw "+role);scene.withdraw(source,role); }
        @Override public void withdraw(HudSource source) { calls.add("withdraw "+source);scene.withdraw(source); }
    }
    /** A PNG header of the given size: all HudImage reads. */
    private static byte[] png(int width,int height) {
        byte[] png=new byte[33];
        byte[] head={(byte)0x89,'P','N','G','\r','\n',0x1a,'\n',0,0,0,13,'I','H','D','R'};
        System.arraycopy(head,0,png,0,head.length);
        for(int i=0;i<4;i++){png[16+i]=(byte)(width>>>(24-8*i));png[20+i]=(byte)(height>>>(24-8*i));}
        return png;
    }
    private static HudAr.Frame ar() {
        HudAr.Pose pose=HudAr.Pose.read(new HudAr.Point(37.5,55.7),90d,10d,4d,150d,1000L,1000L,100L);
        HudAr.Point[] line={new HudAr.Point(37.5,55.7),new HudAr.Point(37.501,55.701)};
        return new HudAr.Frame(pose,line[1],line,.25);
    }
    private static NavigatorSource.Lane lane(String selected, String... directions) {
        return new NavigatorSource.Lane("PLAIN_LANE",Arrays.asList(directions),selected);
    }
    private static void nativeLanes() {
        List<NavigatorSource.Lane> sign=Arrays.asList(lane(null,"straightahead"),lane("straightahead","straightahead","left90"),lane("right90","straightahead","right90"));
        check(Arrays.equals(StockHudSymbols.lanes(sign),new String[]{"landback_hud_0","landfront_hud_20","landfront_hud_43"}),"stock unselected and partial recommended branches");
        check(Arrays.equals(StockHudSymbols.lanes(Arrays.asList(lane("left180","straightahead","left180"))),new String[]{"landfront_hud_95"}),"stock U-turn composite");
        // Every lane gets a cell of BYD's pixels; nothing hides the row.
        check(StockHudSymbols.cell(lane("left45","left45","left90")).equals("landfront_hud_1"),"slight and plain left share BYD's left arrow");
        check(StockHudSymbols.cell(lane("leftshift","leftshift")).equals("shift:L:1")&&StockHudSymbols.cell(lane(null,"rightshift")).equals("shift:R:0"),"lane shift is BYD's shifted lane, mirrored for the right");
        check(StockHudSymbols.cell(lane("right90","left90")).equals("landback_hud_1"),"a highlight outside the lane is ignored");
        check(StockHudSymbols.cell(lane("leftfromright","straightahead","leftfromright")).equals("landfront_hud_21"),"a turn from the far lane is the plain turn");
        check(StockHudSymbols.cell(lane(null,"unknown")).equals("landback_hud_22"),"an unknown arrow is BYD's empty lane");
        check(StockHudSymbols.cell(lane("straightahead","left180","left90","straightahead","right90")).equals("landfront_hud_70"),"a combination BYD never drew loses its U-turn first");
        NavigatorSource.Lane bus=new NavigatorSource.Lane("BUS_LANE",java.util.Collections.<String>emptyList(),null);
        String[] reserved=StockHudSymbols.lanes(Arrays.asList(bus,lane("straightahead","straightahead"),bus));
        check(Arrays.equals(reserved,new String[]{"landback_hud_15","landfront_hud_0","landback_hud_15"}),"a bus lane is BYD's bus in its place");
        check(StockHudSymbols.cell(new NavigatorSource.Lane("TRAM_LANE",java.util.Collections.<String>emptyList(),null)).equals("tram:0")
                &&StockHudSymbols.cell(new NavigatorSource.Lane("TAXI_LANE",java.util.Collections.<String>emptyList(),null)).equals("taxi:0"),"tram and taxi lanes have their own cells");
        check(Arrays.equals(StockHudSymbols.assets("tram:1"),new String[]{"landfront_hud_15"})&&StockHudSymbols.assets("taxi:0").length==0
                &&Arrays.equals(StockHudSymbols.assets("shift:R:0"),new String[]{"landfront_hud_d"}),"cells name the stock pictures they need");
        placement();
        hold();
        check(StockHudSymbols.lanes(java.util.Collections.nCopies(7,sign.get(0))).length==0,"never truncate an oversized sign");
        byte[] png={1,2,3};
        Map<Integer,Object> road=fields(HudWire.road(true,null,null,3,png));
        check(road.get(5).equals(3L)&&Arrays.equals((byte[])road.get(7),png),"raw lane PNG without a hint");
        road=fields(HudWire.road(true,withManoeuvre(),png,3,png));
        check(road.containsKey(7)&&road.containsKey(8)&&road.containsKey(10),"one packet preserves lanes and manoeuvre/street");
        for(int count:new int[]{0,-1,7}) {
            road=fields(HudWire.road(true,null,null,count,png));
            check(road.get(5).equals(0L)&&!road.containsKey(7),"invalid lane count clears");
        }
        road=fields(HudWire.road(true,null,null,3,null));
        check(road.get(5).equals(0L)&&!road.containsKey(7),"no image means explicit zero lanes");
        road=fields(HudWire.road(false,withManoeuvre(),png,3,png));
        check(road.size()==3&&road.get(5).equals(0L)&&!road.containsKey(7),"cancel drops both pictures and clears lane count");
        NavigatorSource.Hint hint=withManoeuvre();
        check(StockHudSymbols.manoeuvre(hint).equals("ar_hud_sou3"),"native right manoeuvre");
        hint.icon="finish";hint.stockId=45;
        check(StockHudSymbols.manoeuvre(hint).equals("ar_hud_sou10"),"waypoint differs from finish despite shared semantic icon");
        hint.stockId=48;check(StockHudSymbols.manoeuvre(hint).equals("ar_hud_sou15"),"native finish");
        hint.icon="roundabout";hint.stockId=13;hint.exit=10;
        check(StockHudSymbols.manoeuvre(hint).equals("ar_hud_sou59"),"stock tenth roundabout exit");
        hint.exit=11;check(StockHudSymbols.manoeuvre(hint).equals("ar_hud_sou11"),"unknown exit must not display a wrong number");
        hint.stockId=24;check(StockHudSymbols.manoeuvre(hint).equals("ar_hud_sou12"),"generic roundabout leave");
    }
    /** The manoeuvre block through a gap: a rebuilt route up to 30 s, no manoeuvre ahead up to 3 s, never past the route. */
    private static void hold() {
        ManeuverHold hold=new ManeuverHold();
        NavigatorSource.Frame frame=new NavigatorSource.Frame();frame.mode="guidance";frame.hint=withManoeuvre();
        check(hold.apply(frame,0)==frame.hint&&hold.holding==null,"a manoeuvre ahead goes out as it is");
        NavigatorSource.Frame rebuild=new NavigatorSource.Frame();rebuild.mode="rerouting";
        NavigatorSource.Hint held=hold.apply(rebuild,29000);
        check(held!=null&&"right".equals(held.icon)&&held.distance==250&&held.speedLimit==60&&"rerouting".equals(hold.holding),"rerouting repeats the last manoeuvre");
        Map<Integer,Object> road=fields(HudWire.road(true,held,new byte[]{7,7}));
        check(road.get(9).equals(250L)&&road.get(28).equals(2L)&&text(road,10).equals("Тверская"),"so the block keeps its arrow, metres and street, not the HUD's Chinese");
        check(hold.apply(rebuild,31000)==null&&hold.holding==null,"not past the lost-position hold");
        NavigatorSource.Hint none=new NavigatorSource.Hint();none.remainingMeters=900;none.remainingSeconds=120;none.speedLimit=40;
        NavigatorSource.Frame empty=new NavigatorSource.Frame();empty.mode="guidance";empty.hint=none;
        hold.apply(frame,40000);
        held=hold.apply(empty,42000);
        check("right".equals(held.icon)&&held.remainingMeters==900&&held.remainingSeconds==120&&held.speedLimit==40&&"no-manoeuvre".equals(hold.holding),"a blink keeps the manoeuvre with the frame's totals and limit");
        check(hold.apply(empty,43500)==none,"a turn already taken is not carried down a straight");
        NavigatorSource.Frame lost=new NavigatorSource.Frame();lost.mode="lost";lost.hint=frame.hint;
        check(hold.apply(lost,44000)==frame.hint,"a held position keeps its own hint");
        check(hold.apply(new NavigatorSource.Frame(),45000)==null&&hold.apply(rebuild,45100)==null,"a new route starts from nothing");
    }
    /** One icon a place, the nearest two places, each beside the route and off the cursor. */
    private static void placement() {
        Map<String,float[]> sizes=new TreeMap<>();
        for(String kind:Arrays.asList("camera","bump","works","lane-camera","crossroad-camera","no-stopping-camera"))sizes.put(kind,new float[]{34,34});
        sizes.put("camera-limit",new float[]{38,38});
        float[] cursor={134,120,32,44};
        HudEvents events=new HudEvents(2,300,180,4,.2f,7,50,120,3,cursor,new String[]{"right","right-high","left","left-high","above","below"},sizes);
        float[] route=HudEvents.samples(Arrays.asList(new float[]{150,145},new float[]{150,20}),3);
        java.util.List<HudEvents.Mark> marks=Arrays.asList(new HudEvents.Mark("b","bump",150,114,0,40),
                new HudEvents.Mark("n","no-stopping-camera",150,78,0,118),new HudEvents.Mark("c","camera",150,72,50,126),
                new HudEvents.Mark("x","crossroad-camera",150,68,0,134),new HudEvents.Mark("w","works",136,30,0,300));
        java.util.List<HudEvents.Mark> places=events.places(marks);
        check(places.size()==3&&places.get(1).key.equals("c"),"cameras at one crossing are one place, shown by the speed camera");
        java.util.List<HudEvents.Placed> placed=events.place(marks,route);
        check(placed.size()==2&&placed.get(0).kind.equals("bump")&&placed.get(1).kind.equals("camera-limit"),"the nearest two places, nothing more");
        for(HudEvents.Placed p:placed){
            check(!HudEvents.overlaps(p.box,cursor,2),"an icon never covers the car: "+p.kind);
            boolean onRoute=false;
            for(int i=0;i+1<route.length;i+=2)if(route[i]>=p.box[0]-2&&route[i]<=p.box[0]+p.box[2]+2&&route[i+1]>=p.box[1]-2&&route[i+1]<=p.box[1]+p.box[3]+2)onRoute=true;
            check(!onRoute,"an icon stands beside the route, not on it: "+p.kind+" "+p.side);
        }
        java.util.List<HudEvents.Mark> bumps=Arrays.asList(new HudEvents.Mark("1","bump",150,110,0,60),new HudEvents.Mark("2","bump",150,96,0,100),
                new HudEvents.Mark("3","bump",150,84,0,140),new HudEvents.Mark("4","bump",150,70,0,180),new HudEvents.Mark("5","bump",150,56,0,220));
        check(events.places(bumps).size()==2,"a run of bumps splits after the span, not into one endless place");
        check(events.place(java.util.Collections.nCopies(6,new HudEvents.Mark("x","unknown-kind",100,100,0,10)),route).isEmpty(),"no icon for an unknown kind");
        // At the top edge the icon slides down beside the route instead of dropping onto it.
        java.util.List<HudEvents.Placed> top=events.place(Arrays.asList(new HudEvents.Mark("t","camera",150,18,50,300)),route);
        check(top.size()==1&&top.get(0).side.startsWith("right")&&top.get(0).box[1]==0,"an icon at the top edge keeps beside the route");
        // Driving up to a camera: beside the route all the way, gone at the cursor's nose instead of
        // jumping onto the route ahead of it (emulator, 2026-10-18).
        for(int y=24;y<=140;y+=2){
            java.util.List<HudEvents.Placed> one=events.place(Arrays.asList(new HudEvents.Mark("a","camera",150,y,60,y)),route);
            if(y<=110)check(one.size()==1,"shown beside the route at y="+y);
            for(HudEvents.Placed p:one){
                int under=0;
                for(int i=0;i+1<route.length;i+=2)if(route[i]>=p.box[0]-2&&route[i]<=p.box[0]+p.box[2]+2&&route[i+1]>=p.box[1]-2&&route[i+1]<=p.box[1]+p.box[3]+2)under++;
                check(under<=3,"never on the route ahead of the cursor: y="+y+" "+p.side);
            }
        }
    }
    /** Enumerate every reachable mapping; the builder verifies each resource exists in the pinned APK. */
    private static void assetNames() {
        java.util.Set<String> names=new java.util.TreeSet<>();
        String[] directions={"straightahead","left90","right90","left180","right180","leftshift","unknown"};
        for(int mask=1;mask<128;mask++) {
            java.util.List<String> values=new java.util.ArrayList<>();
            for(int bit=0;bit<directions.length;bit++)if((mask&(1<<bit))!=0)values.add(directions[bit]);
            for(int highlight=-1;highlight<values.size();highlight++) {
                NavigatorSource.Lane lane=new NavigatorSource.Lane("PLAIN_LANE",values,highlight<0?null:values.get(highlight));
                for(String key:StockHudSymbols.lanes(Arrays.asList(lane)))names.addAll(Arrays.asList(StockHudSymbols.assets(key)));
            }
        }
        for(String kind:Arrays.asList("BUS_LANE","TRAM_LANE","TAXI_LANE"))for(String on:Arrays.asList(null,"x")){
            NavigatorSource.Lane lane=new NavigatorSource.Lane(kind,java.util.Collections.<String>emptyList(),on);
            names.addAll(Arrays.asList(StockHudSymbols.assets(StockHudSymbols.cell(lane))));
        }
        for(String action:Arrays.asList("STRAIGHT","LEFT","RIGHT","SLIGHT_LEFT","SLIGHT_RIGHT","HARD_LEFT","HARD_RIGHT","UTURN_LEFT","UTURN_RIGHT","FINISH","WAYPOINT","ENTER_ROUNDABOUT","LEAVE_ROUNDABOUT")) {
            String[] kind=NavigatorSource.manoeuvre(action);NavigatorSource.Hint hint=new NavigatorSource.Hint();
            hint.icon=kind[0];hint.stockId=Integer.parseInt(kind[1]);
            for(int exit=0;exit<=11;exit++){hint.exit=exit;names.add(StockHudSymbols.manoeuvre(hint));}
        }
        for(String name:names)System.out.println(name);
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
    private static int assertions;
    private static void check(boolean ok,String message) { assertions++; if(!ok)throw new AssertionError(message); }
}
