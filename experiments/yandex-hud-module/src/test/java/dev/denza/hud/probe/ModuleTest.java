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
        public List<Manoeuvre> getManoeuvres() { return manoeuvres; } public List<Object> getLaneSigns() { return lanes; }
        List<Object> lanes = java.util.Collections.emptyList();
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
        held=source.read(guidance,true,3101);
        check(held.mode.equals("guidance")&&held.staleMs==3001&&held.drawMap()&&held.point!=null,"a gap in fixes holds the picture");
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
        System.out.println(assertions+" native module assertions passed");
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
    /** Icons beside their point keep off the route and the cursor; cameras take places first. */
    private static void placement() {
        Map<String,float[]> sizes=new TreeMap<>();
        sizes.put("camera-limit",new float[]{64,38});sizes.put("bump",new float[]{34,29.4f});sizes.put("works",new float[]{31.4f,31.4f});
        float[] cursor={134,120,32,44};
        HudEvents events=new HudEvents(4,300,180,4,.2f,7,cursor,new String[]{"right","right-high","left","left-high","above","below"},sizes);
        java.util.List<float[]> line=Arrays.asList(new float[]{150,145},new float[]{150,20});
        float[] route=HudEvents.samples(line,3);
        java.util.List<HudEvents.Mark> marks=Arrays.asList(new HudEvents.Mark("b","bump",150,114,0),
                new HudEvents.Mark("w","works",148,82,0),new HudEvents.Mark("c","camera",144,33,60));
        java.util.List<HudEvents.Placed> placed=events.place(marks,route);
        check(placed.size()==3&&placed.get(0).kind.equals("camera-limit"),"a camera with its limit is placed first");
        for(HudEvents.Placed p:placed){
            check(!HudEvents.overlaps(p.box,cursor,2),"an icon never covers the car: "+p.kind);
            boolean onRoute=false;
            for(int i=0;i+1<route.length;i+=2)if(route[i]>=p.box[0]-2&&route[i]<=p.box[0]+p.box[2]+2&&route[i+1]>=p.box[1]-2&&route[i+1]<=p.box[1]+p.box[3]+2)onRoute=true;
            check(!onRoute,"an icon stands beside the route, not on it: "+p.kind+" "+p.side);
        }
        check(events.place(java.util.Collections.nCopies(6,new HudEvents.Mark("x","unknown-kind",100,100,0)),route).isEmpty(),"no icon for an unknown kind");
        // At the top edge the icon slides down beside the route instead of dropping onto it.
        java.util.List<HudEvents.Placed> top=events.place(Arrays.asList(new HudEvents.Mark("t","camera",150,18,50),new HudEvents.Mark("u","works",150,62,0)),route);
        check(top.size()==2&&top.get(0).side.startsWith("right")&&top.get(0).box[1]==0&&!top.get(1).side.equals("below"),"an icon at the top edge keeps beside the route");
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
