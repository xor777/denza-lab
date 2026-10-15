package dev.denza.hud.probe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Geometry/pose regressions: crooked exits, fractional manoeuvres, stale routes and stock wire. */
public final class ArTest {
    private static int checks;
    private static final long WALL = 1_700_000_000_000L;
    private static HudAr.Point p(double x, double y) { return new HudAr.Point(37 + x / 100_000, 55 + y / 100_000); }
    private static HudAr.Position pos(int segment, double fraction) { return new HudAr.Position(segment, fraction); }
    private static HudAr.Pose pose(HudAr.Point p) { return HudAr.Pose.read(p, 12d, 8d, 4d, 140d, WALL, WALL, 100); }
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) {
        HudAr.Point[] points = {p(0,0), p(0,25), p(-8,45), p(-28,50), p(-35,82), p(-9,95)};
        HudAr.Route route = new HudAr.Route(points);
        HudAr.Frame frame = route.frame(pos(0,.4),pos(2,0),pos(4,1),pose(p(0,10)));
        check(frame != null && frame.line.length == 6, "real winding exit is kept, not a schematic angle");
        for (int i = 1; i < points.length; i++) check(HudAr.distance(frame.line[i], points[i]) < .001, "original vertex " + i);
        check(HudAr.distance(frame.target,points[2]) < .001, "guide point is exact native manoeuvre position");
        check(frame.progress > 0 && frame.progress < 1, "whole-route progress, not proximity to turn");
        HudAr.Frame middle = route.frame(pos(0,.4),pos(1,.6),pos(3,.5),pose(p(0,10)));
        check(middle != null && middle.line.length == 6, "fractional target and following step are inserted in order");
        check(Math.abs(middle.target.lon - (points[1].lon + (points[2].lon - points[1].lon)*.6)) < 1e-10, "fractional target uses geometry");
        check(route.frame(pos(2,0),pos(1,0),null,pose(points[2])) == null, "passed turn clears");
        check(route.frame(pos(0,0),pos(1,0),pos(1,0),pose(points[0])) == null, "no invented exit");
        check(route.frame(pos(0,0),pos(1,0),null,pose(p(100,100))) == null, "pose detached from route clears");
        check(route.frame(pos(-1,0),pos(1,0),null,pose(points[0])) == null, "invalid segment clears");
        check(route.frame(pos(0,Double.NaN),pos(1,0),null,pose(points[0])) == null, "invalid fraction clears");
        HudAr.Point[] dense = new HudAr.Point[300];
        for(int i=0;i<dense.length;i++)dense[i]=p(i%2,i*.1);
        check(new HudAr.Route(dense).frame(pos(0,0),pos(290,0),null,pose(dense[0]))==null,"oversize geometry suppressed, not flattened");
        HudAr.Route longExit = new HudAr.Route(new HudAr.Point[]{p(0,0),p(0,20),p(1000,20)});
        HudAr.Frame clipped=longExit.frame(pos(0,0),pos(1,0),null,pose(p(0,0)));
        check(Math.abs(HudAr.distance(clipped.target,clipped.line[clipped.line.length-1])-120)<.1,"long exit bounded on original segment");
        HudAr.Route seam = new HudAr.Route(new HudAr.Point[]{new HudAr.Point(179.999,0),new HudAr.Point(-179.999,0),new HudAr.Point(-179.998,0)});
        HudAr.Frame across=seam.frame(pos(0,.5),pos(1,0),null,pose(new HudAr.Point(-180,0)));
        check(across!=null && Math.abs(across.line[0].lon)==180,"dateline interpolation takes short segment");
        for(Double heading:new Double[]{null,Double.NaN,Double.POSITIVE_INFINITY,-1d,361d})
            check(HudAr.Pose.read(points[0],heading,8d,4d,null,WALL,WALL,100)==null,"unknown heading cannot become north");
        check(HudAr.Pose.read(points[0],0d,0d,4d,null,WALL,WALL,100)!=null,"valid zero heading/speed are not missing");
        check(HudAr.Pose.read(points[0],0d,8d,21d,null,WALL,WALL,100)==null,"poor accuracy clears");
        check(HudAr.Pose.read(points[0],0d,null,4d,null,WALL,WALL,100)==null,"missing speed clears");
        check(HudAr.Pose.read(points[0],0d,8d,4d,null,WALL-2501,WALL,100)==null,"stale fix clears even on first read");
        check(HudAr.Pose.read(points[0],0d,8d,4d,null,WALL+1001,WALL,100)==null,"future fix clears");
        check(HudAr.Pose.read(points[0],0d,8d,4d,null,WALL-2000,WALL,100).expiresAt==600,"worker has remaining freshness budget");
        check(!HudAr.supported("BOARD_FERRY")&&!HudAr.supported("FINISH")&&HudAr.supported("ENTER_ROUNDABOUT"),"only real road manoeuvres");
        wire(frame);
        adapter(points);
        System.out.println(checks+" AR assertions passed");
    }

    private static void wire(HudAr.Frame ar) {
        NavigatorSource.Hint hint=new NavigatorSource.Hint();hint.icon="left";hint.stockId=1;hint.distance=45;
        Map<Integer,Object> fields=decode(HudWire.road(true,hint,new byte[]{1},0,null,ar));
        check(fields.get(19).equals(ar.pose.point.lon)&&fields.get(20).equals(ar.pose.point.lat),"fixed64 longitude/latitude order");
        check(fields.get(21).equals(8L)&&fields.get(22).equals(140L),"speed m/s and altitude integers");
        check(fields.get(32).equals(12d)&&fields.get(33).equals(ar.progress),"heading and route progress doubles");
        check(fields.get(30).equals(ar.lineJson())&&fields.get(31).equals(ar.target.tuple()),"stock JSON and guide point strings");
        Map<Integer,Object> clear=decode(HudWire.road(false,hint,new byte[]{1},0,null,ar));
        check(clear.size()==3&&!clear.containsKey(30),"inactive snapshot drops every AR field");
        Map<Integer,Object> compact=decode(HudWire.road(true,hint,new byte[]{1},0,null,null));
        check(compact.containsKey(28)&&!compact.containsKey(19)&&!compact.containsKey(30),"AR loss preserves ordinary hint, drops geometry");
    }

    public static final class PolyPosition {
        int index; double fraction;
        PolyPosition(int i,double f){index=i;fraction=f;}
        public int getSegmentIndex(){return index;} public double getSegmentPosition(){return fraction;}
    }
    public static final class RoutePosition {
        String id="a"; final PolyPosition position;
        RoutePosition(int i,double f){position=new PolyPosition(i,f);}
        public boolean onRoute(String route){return id.equals(route);}
        public PolyPosition positionOnRoute(String route){return onRoute(route)?position:null;}
    }
    public static final class Point {
        final HudAr.Point point;Point(HudAr.Point point){this.point=point;}
        public double getLatitude(){return point.lat;}public double getLongitude(){return point.lon;}
    }
    public static final class Route {
        String id="a";final Point[] points;int reads;
        Route(HudAr.Point[] p){points=new Point[p.length];for(int i=0;i<p.length;i++)points[i]=new Point(p[i]);}
        public String getRouteId(){return id;} public Route getGeometry(){reads++;return this;}
        public List<Point> getPoints(){return Arrays.asList(points);}
    }
    public static final class Location {
        public Double getHeading(){return 0d;}public Double getSpeed(){return 8d;}
        public Double getAccuracy(){return 4d;}public Double getAltitude(){return null;}
        public long getAbsoluteTimestamp(){return WALL;}
    }
    private static void adapter(HudAr.Point[] points) {
        HudArSource source=new HudArSource(new Reflect());
        NavigatorSource.Frame f=new NavigatorSource.Frame();f.mode="guidance";f.route=new Route(points);
        f.position=new PolyPosition(0,0);f.point=new Point(points[0]);f.location=new Location();
        f.hint=new NavigatorSource.Hint();f.hint.action="LEFT";f.hint.position=new RoutePosition(2,0);f.hint.followingPosition=new RoutePosition(4,1);
        check(source.read(f,100,WALL)!=null&&source.failure==null,"native position adapter");
        for(int i=0;i<100;i++)check(source.read(f,100+i,WALL+i)!=null,"moving samples reuse native geometry");
        check(((Route)f.route).reads==1&&source.geometryBuilds==1,"geometry copied once per route, not once per frame");
        f.mode="rerouting";check(source.read(f,300,WALL)==null,"recalculation clears AR");
        f.mode="guidance";check(source.read(f,400,WALL)!=null&&source.geometryBuilds==2,"recovery rebuilds from current route");
        ((Route)f.route).id="b";
        check(source.read(f,500,WALL)==null,"old manoeuvre cannot attach to replacement route");
        ((RoutePosition)f.hint.position).id="b";((RoutePosition)f.hint.followingPosition).id="b";
        check(source.read(f,600,WALL)!=null&&source.geometryBuilds==3,"new route positions recover");
        for(String mode:new String[]{"waiting-location","off"}) {f.mode=mode;check(source.read(f,700,WALL)==null,"mode clears AR: "+mode);}
        f.mode="guidance";f.location=new Object();
        check(source.read(f,800,WALL)==null&&source.failure!=null,"optional API failure stays within AR adapter");
    }
    private static Map<Integer,Object> decode(byte[] packet) {
        ByteBuffer b=ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        check(varint(b)==10&&varint(b)==b.remaining(),"road envelope");
        Map<Integer,Object> result=new TreeMap<>();
        while(b.hasRemaining()) {
            int key=(int)varint(b);Object value;
            if((key&7)==0)value=varint(b);
            else if((key&7)==1)value=b.getDouble();
            else {byte[] bytes=new byte[(int)varint(b)];b.get(bytes);value=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);}
            result.put(key>>>3,value);
        }
        return result;
    }
    private static long varint(ByteBuffer b){long value=0;int shift=0;while(true){int n=b.get()&255;value|=(long)(n&127)<<shift;if((n&128)==0)return value;shift+=7;}}
}
