package dev.denza.hud.probe;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host checks of actual getter names, unknown handling, geometry indexing and atomic evidence writes. */
public final class RouteSnapshotTest {
    private static int checks;
    public enum Action { LEAVE_ROUNDABOUT }
    public enum Kind { PLAIN_LANE, BUS_LANE }
    public enum Direction { STRAIGHT_AHEAD, RIGHT90 }
    public enum Placement { LEFT, MIDDLE, RIGHT }
    public enum SignImage { AIRPORT_IMAGE }
    public static class Point {
        final double lon, lat;
        Point(double lon, double lat) { this.lon = lon; this.lat = lat; }
        public double getLongitude() { return lon; }
        public double getLatitude() { return lat; }
    }
    public static class Geometry {
        List<Point> points = Arrays.asList(new Point(37.1,55.1),new Point(37.2,55.2),new Point(37.3,55.3));
        public List<Point> getPoints() { return points; }
    }
    public static class Position {
        final int index; final double fraction;
        Position(int index, double fraction) { this.index=index; this.fraction=fraction; }
        public int getSegmentIndex() { return index; }
        public double getSegmentPosition() { return fraction; }
    }
    public static class Range {
        public Position getBegin() { return new Position(0,.25); }
        public Position getEnd() { return new Position(1,.75); }
    }
    public static class RangeItem { public Range getPosition() { return new Range(); } }
    public static class RuggedRoad extends RangeItem {
        public boolean getInPoorCondition() { return true; }
        public boolean getUnpaved() { return false; }
    }
    public static class Localized {
        final double value; Localized(double value) { this.value=value; }
        public double getValue() { return value; }
        public String getText() { return "text"; }
    }
    public static class Weight {
        public Localized getDistance() { return new Localized(123.4); }
        public Localized getTime() { return new Localized(10.2); }
        public Localized getTimeWithTraffic() { return new Localized(12.3); }
    }
    public static class LeaveRoundabout { public int getExitNumber() { return 3; } }
    public static class ActionMetadata {
        public LeaveRoundabout getLeaveRoundaboutMetadata() { return new LeaveRoundabout(); }
        public Object getTurnMetadata() { return null; }
        public Object getExitMetadata() { return null; }
        public Object getUturnMetadata() { return null; }
    }
    public static class Annotation {
        public Action getAction() { return Action.LEAVE_ROUNDABOUT; }
        public String getToponym() { return "улица \"Мира\"\n"; }
        public String getDescriptionText() { return null; }
        public boolean getInSeriesWithNext() { return false; }
        public ActionMetadata getActionMetadata() { return new ActionMetadata(); }
        public AnnotationLanes getAnnotationLanes() { return new AnnotationLanes(); }
        public Hd getHdAnnotation() { return new Hd(); }
    }
    public static class AnnotationLane {
        public Kind getLaneKind() { return Kind.PLAIN_LANE; }
        public Placement getPlacement() { return Placement.RIGHT; }
        public boolean getAnnotated() { return true; }
    }
    public static class AnnotationLanes {
        public List<AnnotationLane> getLanes() { return Arrays.asList(new AnnotationLane(), new NoPlacementLane()); }
    }
    public static class NoPlacementLane extends AnnotationLane { public Placement getPlacement() { return null; } }
    public static class Hd {
        public Geometry getActionArea() { return new Geometry(); }
        public Object getTrajectory() { return null; }
    }
    public static class SectionMetadata {
        public int getLegIndex() { return 2; }
        public Object getSchemeId() { return null; }
        public List<Position> getViaPointPositions() { return Collections.singletonList(new Position(1,.5)); }
        public Weight getWeight() { return new Weight(); }
        public Annotation getAnnotation() { return new Annotation(); }
    }
    public static class Section {
        public Range getGeometry() { return new Range(); }
        public SectionMetadata getMetadata() { return new SectionMetadata(); }
    }
    public static class Lane {
        final Kind kind; Lane(Kind kind) { this.kind=kind; }
        public Kind getLaneKind() { return kind; }
        public List<Direction> getDirections() { return Arrays.asList(Direction.STRAIGHT_AHEAD,Direction.RIGHT90); }
        public Direction getHighlightedDirection() { return kind==Kind.BUS_LANE ? null : Direction.RIGHT90; }
    }
    public static class LaneSign {
        public Position getPosition() { return new Position(0,.625); }
        public Boolean getAnnotated() { return null; }
        public List<Lane> getLanes() { return Arrays.asList(new Lane(Kind.BUS_LANE),new Lane(Kind.PLAIN_LANE)); }
    }
    public static class Light {
        Position position = new Position(1,.125);
        public Position getPosition() { return position; }
        public String getId() { return null; }
    }
    public static class SignStyle {
        public int getBgColor() { return 0xff000000; }
        public int getTextColor() { return 0xffffffff; }
    }
    public static class SignIcon {
        public SignImage getImage() { return SignImage.AIRPORT_IMAGE; }
        public SignStyle getStyle() { return new SignStyle(); }
    }
    public static class SignToponym {
        final SignStyle style; SignToponym(SignStyle style) { this.style=style; }
        public String getText() { return "Внуково"; }
        public SignStyle getStyle() { return style; }
    }
    public static class SignItem {
        final boolean styled; SignItem(boolean styled) { this.styled=styled; }
        public SignIcon getIcon() { return styled ? new SignIcon() : null; }
        public Object getExit() { return null; }
        public Object getRoad() { return null; }
        public SignToponym getToponym() { return new SignToponym(styled ? new SignStyle() : null); }
    }
    public static class DirectionSign {
        public Position getPosition() { return new Position(1,0); }
        public Direction getDirection() { return Direction.STRAIGHT_AHEAD; }
        public List<SignItem> getItems() { return Arrays.asList(new SignItem(true),new SignItem(false)); }
    }
    public static class Metadata {
        public Weight getWeight() { return new Weight(); }
        public List<String> getTags() { return Arrays.asList("tag"); }
        public Object getDescription() { return null; }
        public Object getFlags() { return null; }
        public byte[] getDescriptor() { return new byte[]{0,1,-1}; }
        public byte[] getTraits() { return new byte[]{1}; }
        public String getUri() { return null; }
    }
    public static class GeometryOnly {
        final Geometry geometry = new Geometry();
        public String getRouteId() { return "real-route-id"; }
        public Geometry getGeometry() { return geometry; }
    }
    public static class RichRoute extends GeometryOnly {
        List<Float> limits = Arrays.asList(13.888889f,null);
        final Light light = new Light();
        public Metadata getMetadata() { return new Metadata(); }
        public List<Section> getSections() { return Collections.singletonList(new Section()); }
        public List<Float> getSpeedLimits() { return limits; }
        public List<LaneSign> getLaneSigns() { return Collections.singletonList(new LaneSign()); }
        public List<Light> getTrafficLights() { return Collections.singletonList(light); }
        public List<RangeItem> getHighways() { return Collections.singletonList(new RangeItem()); }
        public List<RangeItem> getTunnels() { return Collections.emptyList(); }
        public List<RuggedRoad> getRuggedRoads() { return Collections.singletonList(new RuggedRoad()); }
        public List<RangeItem> getTollRoads() { return Collections.emptyList(); }
        public List<RangeItem> getHdSections() { return Collections.emptyList(); }
        public List<RangeItem> getStandingSegments() { return Collections.emptyList(); }
        public List<Object> getEvents() { return Collections.emptyList(); }
        public List<DirectionSign> getDirectionSigns() { return Collections.singletonList(new DirectionSign()); }
    }
    public static class Unreadable extends RichRoute {
        public List<Light> getTrafficLights() { throw new UnsupportedOperationException("private-handle-must-not-leak"); }
        public List<LaneSign> getLaneSigns() { return null; }
    }
    public static class ChangedIdentity extends GeometryOnly {
        int reads;
        public String getRouteId() { return ++reads==1 ? "old" : "new"; }
    }
    public static class Opaque {
        public String toString() { throw new AssertionError("Opaque object stringified"); }
    }
    public enum JamType { FREE }
    public static class Jam {
        public JamType getJamType() { return JamType.FREE; }
        public double getSpeed() { return 10.0; }
    }
    public static class ReversedRange extends Range {
        public Position getBegin() { return new Position(1,.75); }
        public Position getEnd() { return new Position(0,.25); }
    }
    public static class ReversedItem extends RangeItem { public Range getPosition() { return new ReversedRange(); } }
    /** CAP-8: ranges, jams and positions the cutter must never receive unflagged. */
    public static class MalformedRoute extends RichRoute {
        int jams = 1;
        public List<RangeItem> getHighways() { return Collections.<RangeItem>singletonList(new ReversedItem()); }
        public List<Jam> getJamSegments() { List<Jam> out = new java.util.ArrayList<>(); for (int i = 0; i < jams; i++) out.add(new Jam()); return out; }
    }
    public static class OpaqueRoute extends RichRoute {
        public List<Object> getEvents() { return Collections.<Object>singletonList(new Opaque()); }
    }

    public static void main(String[] args) throws Exception {
        refreshPolicy();
        RouteSnapshot reader = new RouteSnapshot(new Reflect());
        Map<String,Object> snapshot = reader.read(new RichRoute());
        check(RouteSnapshot.SCHEMA.equals(snapshot.get("schema")),"versioned schema");
        check("wgs84".equals(snapshot.get("coordinateFrame")),"explicit unconverted frame");
        check("lon,lat".equals(snapshot.get("pointOrder")),"explicit coordinate order");
        check("real-route-id".equals(snapshot.get("routeId")),"legacy identity");
        check(((List<?>)snapshot.get("points")).get(0).equals(Arrays.asList(37.1,55.1)),"legacy point arrays retained");
        check(((List<?>)snapshot.get("speedLimits")).get(1)==null,"nullable segment limit preserved");
        check("known".equals(state(snapshot,"speedLimits")),"matching nullable speed limits known");
        Map<?,?> section = first(snapshot,"sections"), geometry = object(section,"geometry");
        check(object(geometry,"begin").get("segmentPosition").equals(.25),"fractional section begin");
        check(object(geometry,"end").get("segmentPosition").equals(.75),"fractional section end");
        Map<?,?> meta=object(section,"metadata"), annotation=object(meta,"annotation");
        check(meta.get("legIndex").equals(2),"section leg index");
        check(annotation.get("action").equals("LEAVE_ROUNDABOUT"),"semantic action enum, no HUD ids");
        check(object(object(meta,"weight"),"distance").get("value").equals(123.4),"numeric section weight");
        check(object(object(meta,"weight"),"timeWithTraffic").get("value").equals(12.3),"traffic time distinct");
        check(object(object(annotation,"actionMetadata"),"leaveRoundabout").get("exitNumber").equals(3),"roundabout exit number");
        Map<?,?> annotationLane=first(object(annotation,"annotationLanes"),"lanes");
        check(annotationLane.get("placement").equals("RIGHT"),"annotation lane placement is separate from lane directions");
        check(annotationLane.get("annotated").equals(true),"annotation lane recommendation");
        check(!annotationLane.containsKey("directions"),"no invented directions on AnnotationLane");
        Map<?,?> unplaced=(Map<?,?>)((List<?>)object(annotation,"annotationLanes").get("lanes")).get(1);
        check(unplaced.get("placement")==null,"runtime-proven absent lane placement retained");
        check("known".equals(state(snapshot,"sections[0].metadata.annotation.annotationLanes.lanes[1].placement")),"nullable placement is known absent");
        check(((List<?>)object(annotation,"hdAnnotation").get("actionArea")).size()==3,"HD action area full points");
        check(object(annotation,"hdAnnotation").get("trajectory")==null,"nullable HD trajectory remains absent");
        check("known".equals(state(snapshot,"sections[0].metadata.annotation.descriptionText")),"nullable text known absent");
        Map<?,?> sign=first(snapshot,"laneSigns");
        check(object(sign,"position").get("segmentPosition").equals(.625),"lane sign fractional position");
        check(((List<?>)sign.get("lanes")).size()==2,"special lanes retained");
        check(first(sign,"lanes").get("laneKind").equals("BUS_LANE"),"special lane kind");
        check(first(sign,"lanes").get("highlightedDirection")==null,"unhighlighted special lane preserved");
        check(object(first(snapshot,"trafficLights"),"position").get("segmentPosition").equals(.125),"light position is not coordinate");
        check(((List<?>)snapshot.get("tunnels")).isEmpty() && "known".equals(state(snapshot,"tunnels")),"empty returned list known empty");
        check(object(first(snapshot,"highways"),"position").containsKey("begin"),"range getter uses position then begin/end");
        check(Boolean.TRUE.equals(first(snapshot,"ruggedRoads").get("inPoorCondition")),"rugged poor condition retained");
        check(Boolean.FALSE.equals(first(snapshot,"ruggedRoads").get("unpaved")),"rugged false is known false, not missing");
        check(object(snapshot,"metadata").get("descriptorBase64").equals("AAH/"),"opaque descriptor bytes");
        check(object(snapshot,"metadata").get("descriptorByteLength").equals(3),"opaque descriptor length");
        check(object(snapshot,"metadata").get("traitsBase64").equals("AQ=="),"opaque traits bytes");
        check(object(snapshot,"metadata").get("traitsByteLength").equals(1),"opaque padded byte length");
        Map<?,?> signItem=first(first(snapshot,"directionSigns"),"items");
        check(object(signItem,"icon").get("image").equals("AIRPORT_IMAGE"),"direction sign icon is an image/style object");
        check(object(object(signItem,"icon"),"style").get("bgColor").equals(0xff000000),"icon background color preserves signed ARGB int");
        check(object(object(signItem,"toponym"),"style").get("textColor").equals(0xffffffff),"toponym style is colors, not enum");
        Map<?,?> unstyledItem=(Map<?,?>)((List<?>)first(snapshot,"directionSigns").get("items")).get(1);
        check(unstyledItem.get("icon")==null && object(unstyledItem,"toponym").get("style")==null,"absent optional sign icon/style preserved");
        check("known".equals(state(snapshot,"directionSigns")),"complete object-valued direction signs known");

        Map<String,Object> missing=reader.read(new GeometryOnly());
        check(missing.get("sections")==null && "unknown".equals(state(missing,"sections")),"missing list is not empty known");
        check(object(object(missing,"issues"),"sections").get("kind").equals("missing_getter"),"missing getter identified");
        check(Boolean.FALSE.equals(missing.get("complete")),"partial diagnostic not complete");
        Map<String,Object> unreadable=reader.read(new Unreadable());
        check(unreadable.get("trafficLights")==null && "unknown".equals(state(unreadable,"trafficLights")),"throwing getter unknown");
        check(unreadable.get("laneSigns")==null && "unknown".equals(state(unreadable,"laneSigns")),"null required list unknown");
        check(!RouteSnapshot.json(unreadable).contains("private-handle"),"exception messages not exported");
        check(object(object(unreadable,"issues"),"trafficLights").get("exception").equals("java.lang.UnsupportedOperationException"),"unwrapped failure type");
        RichRoute mismatch = new RichRoute(); mismatch.limits=Collections.singletonList(10f);
        Map<String,Object> misaligned=reader.read(mismatch);
        check("partial".equals(state(misaligned,"speedLimits")),"array length mismatch flagged");
        check(((List<?>)misaligned.get("speedLimits")).size()==1,"misaligned raw data retained diagnostically");
        check(object(object(misaligned,"issues"),"speedLimits.alignment").get("expected").equals(2),"alignment expected segment count");
        RichRoute invalidPosition=new RichRoute(); invalidPosition.light.position=new Position(2,.5);
        Map<String,Object> invalid=reader.read(invalidPosition);
        check("unknown".equals(state(invalid,"trafficLights[0].position")),"position outside original geometry unknown");
        check("partial".equals(state(invalid,"trafficLights")),"nested failed position marks array partial");
        RichRoute terminal=new RichRoute(); terminal.light.position=new Position(2,0);
        Map<String,Object> ended=reader.read(terminal);
        check(object(first(ended,"trafficLights"),"position").get("segmentIndex").equals(2),"canonical terminal vertex position retained");
        check("known".equals(state(ended,"trafficLights")),"terminal vertex with zero fraction accepted");
        RichRoute overflow=new RichRoute(); overflow.light.position=new Position(0,1.5);
        Map<String,Object> beyond=reader.read(overflow);
        check("unknown".equals(state(beyond,"trafficLights[0].position")),"fraction above 1 rejected (CAP-8)");
        check("partial".equals(state(beyond,"trafficLights")),"fraction above 1 marks the list partial");
        MalformedRoute malformed=new MalformedRoute();
        Map<String,Object> bad1=reader.read(malformed);
        check(!"known".equals(state(bad1,"highways")) && first(bad1,"highways").get("position")==null,"reversed range rejected (CAP-8)");
        check(object(object(bad1,"issues"),"jamSegments.alignment")!=null && "partial".equals(state(bad1,"jamSegments")),"jam list of the wrong length flagged (CAP-8)");
        malformed.jams=2;
        Map<String,Object> aligned=reader.read(malformed);
        check(object(object(aligned,"issues"),"jamSegments.alignment")==null && "known".equals(state(aligned,"jamSegments")),"one jam per edge is known");
        Map<String,Object> opaque=reader.read(new OpaqueRoute());
        check("partial".equals(state(opaque,"events")),"opaque item not stringified or discarded as known empty");
        check(first(opaque,"events").get("polylinePosition")==null,"opaque object's missing getter explicit");
        rejects(() -> reader.read(new ChangedIdentity()),"identity race rejects snapshot");
        GeometryOnly bad=new GeometryOnly(); bad.geometry.points=Arrays.asList(new Point(Double.NaN,55),new Point(37,55));
        rejects(() -> reader.read(bad),"nonfinite geometry rejected");
        GeometryOnly empty=new GeometryOnly(); empty.geometry.points=Collections.singletonList(new Point(37,55));
        rejects(() -> reader.read(empty),"single-point route rejected");
        check(RouteSnapshot.json("\"\\\n\t\u0000").equals("\"\\\"\\\\\\u000a\\u0009\\u0000\""),"JSON control and quote escaping");
        check(RouteSnapshot.json("улица").equals("\"улица\""),"UTF-8 text retained");
        rejects(() -> RouteSnapshot.json(Double.POSITIVE_INFINITY),"nonfinite JSON rejected");
        rejects(() -> RouteSnapshot.json(new Opaque()),"opaque JSON rejected without toString");
        check(RouteSnapshot.json(snapshot).contains("\\\"Мира\\\"\\u000a"),"annotation escaping survives whole snapshot");

        Path directory=Files.createTempDirectory("route-snapshot-test-");
        try {
            File target=directory.resolve("route.json").toFile();
            Files.write(target.toPath(),"old-complete-snapshot".getBytes(StandardCharsets.UTF_8));
            RouteSnapshot.writeAtomic(target,snapshot);
            String saved=new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8);
            check(saved.equals(RouteSnapshot.json(snapshot)),"atomic write stores exact complete UTF-8 snapshot");
            Map<String,Object> invalidTree=new LinkedHashMap<>(); invalidTree.put("bad",new Opaque());
            rejects(() -> RouteSnapshot.writeAtomic(target,invalidTree),"serialization failure rejects write");
            check(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8).equals(saved),"failed write preserves previous snapshot");
            File blocked=directory.resolve("blocked").toFile(); check(blocked.mkdir(),"blocked destination fixture");
            Files.write(blocked.toPath().resolve("keep"),new byte[]{1});
            rejects(() -> RouteSnapshot.writeAtomic(blocked,snapshot),"rename failure surfaced");
            try(java.util.stream.Stream<Path> paths=Files.list(directory)) {
                check(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")),"failed atomic move cleans temporary file");
            }
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(directory)) {
                for(Path path:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("RouteSnapshotTest: "+checks+" checks");
    }

    private static void refreshPolicy() {
        RouteSnapshot.Refresh policy=new RouteSnapshot.Refresh();
        Map<String,Object> first=policy.begin("a",100);
        long epoch=((Number)first.get("routeGeneration")).longValue();
        check(first.get("revision").equals(1L),"initial copy revision");
        check(policy.begin("a",10099)==null,"no unbounded per-tick copy");
        check(policy.begin("a",10100).get("revision").equals(2L),"bootstrap conditions retry after10s");
        check(policy.begin("a",40099)==null,"periodic copy waits30s");
        Map<String,Object> third=policy.begin("a",40100);
        check(third.get("revision").equals(3L),"passive periodic refresh after30s");
        Map<String,Object> copied=new LinkedHashMap<>();RouteSnapshot.captured(copied,third,40110,1000000);
        check(object(copied,"conditionsCapture").get("sourceUpdatedAtKnown").equals(false),"copy does not claim condition freshness");
        check(object(copied,"conditionsCapture").get("completedAtElapsedMs").equals(40110L),"completion time separate from SDK source time");
        check(!third.containsKey("completedAtElapsedMs"),"capture stamp does not mutate scheduler token");
        policy.begin("b",40111);check(!policy.current(epoch),"reroute invalidates queued old copy");
        long replacement=policy.route("b");policy.route(null);check(!policy.current(replacement),"stop invalidates queued replacement");
        check(policy.begin("a",40112).get("revision").equals(1L),"same ID after stop starts new generation");
    }

    private interface Attempt { void run() throws Exception; }
    private static void rejects(Attempt attempt,String message) {
        boolean rejected=false;
        try { attempt.run(); } catch(Exception expected) { rejected=true; }
        check(rejected,message);
    }
    private static Map<?,?> object(Map<?,?> value,String key) { return (Map<?,?>)value.get(key); }
    private static Map<?,?> first(Map<?,?> value,String key) { return (Map<?,?>)((List<?>)value.get(key)).get(0); }
    private static Object state(Map<?,?> snapshot,String path) { return object(snapshot,"availability").get(path); }
    private static void check(boolean condition,String message) { checks++; if(!condition)throw new AssertionError(message); }
}
