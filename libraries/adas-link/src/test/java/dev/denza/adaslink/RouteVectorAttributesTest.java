package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static dev.denza.adaslink.VectorRoadTileTest.*;

/** Source-only in this task. Parent may compile/run these plain Java 8 checks later. */
public final class RouteVectorAttributesTest {
    private static int checks;
    private static void expect(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static final VectorRoadTile.TileId TILE = new VectorRoadTile.TileId(20000, 10000, 15);

    private static VectorRoadTile tile(int[][] paths, String[] classes, Object[] ways, String[] structures, int[] levels) {
        Object[] rawClasses=classes.clone(),fc=new Object[classes.length];
        for(int i=0;i<classes.length;i++)fc[i]=(long)(classes[i].charAt(0)>='1'&&classes[i].charAt(0)<='9'?classes[i].charAt(0)-'0':4);
        return typedTile(paths,rawClasses,fc,ways,structures,levels);
    }
    private static byte[] literal(Object value){
        return value instanceof String?str(1,(String)value):value instanceof Float?float32(5,(Float)value):v(3,((Number)value).longValue());
    }
    private static VectorRoadTile typedTile(int[][] paths,Object[] classes,Object[] fc,Object[] ways,String[] structures,int[] levels){
        byte[] keys = msg(4, join(str(1, "class"), str(1, "fc"), str(1, "oneway"), str(1, "struct"),
                str(1, "zlev"), str(1, "min_zlev"), str(1, "max_zlev"), str(1, "z_level")));
        byte[] values = new byte[0], roads = new byte[0]; List<Integer> coordinates = new ArrayList<>();
        long[] lengths = new long[paths.length];
        for (int i = 0; i < paths.length; i++) {
            int[] p = paths[i]; lengths[i] = 2;
            coordinates.add(p[0]); coordinates.add(p[1]); coordinates.add(p[2] - p[0]); coordinates.add(p[3] - p[1]);
            byte[] way = ways[i] instanceof String ? str(1, (String)ways[i]) : v(3, ((Number)ways[i]).longValue());
            values = join(values, msg(5, literal(classes[i])), msg(5, literal(fc[i])), msg(5, way), msg(5, str(1, structures[i])), msg(5, v(3, levels[i])));
            int base = i * 5;
            byte[] attributes = msg(6, join(v(1, 0), packed(2, base, base + 1, base + 2, base + 3, base + 4, base + 4, base + 4, base + 4)));
            roads = join(roads, msg(2, join(feature(line(1), 100 + i), attributes)));
        }
        int[] points = new int[coordinates.size()]; for (int i = 0; i < points.length; i++) points[i] = coordinates.get(i);
        byte[] roadLayer = msg(6, join(str(1, "road"), roads));
        return VectorRoadTile.decode(join(v(1, 14), coords(points), packed(3, lengths), keys, values, roadLayer), TILE, TILE, TILE);
    }
    private static VectorRoadTile single(String roadClass, Object way, boolean reverse) {
        return tile(new int[][]{reverse ? new int[]{5000, 0, -5000, 0} : new int[]{-5000, 0, 5000, 0}},
                new String[]{roadClass}, new Object[]{way}, new String[]{"none"}, new int[]{0});
    }
    private static YandexRoute route(VectorRoadTile tile, boolean reverse) {
        VectorRoadTile.RoadFeature feature = tile.features().get(0);
        VectorRoadTile.Point a = feature.points.get(reverse ? 1 : 0), b = feature.points.get(reverse ? 0 : 1);
        return route("whole-selected-route", new double[]{a.lon, b.lon}, new double[]{a.lat, b.lat});
    }
    private static YandexRoute route(String id, double[] lon, double[] lat) {
        return new YandexRoute(id, lon, lat, Collections.<YandexRoute.Section>emptyList(), null, null,
                Collections.<double[]>emptyList(), Collections.<double[]>emptyList(), null, Collections.<YandexRoute.Camera>emptyList());
    }
    private static RouteVectorAttributes.Result match(YandexRoute route, VectorRoadTile... tiles) {
        RouteVectorAttributes.Builder builder = new RouteVectorAttributes.Builder(route);
        for (VectorRoadTile tile : tiles) expect(builder.accept(tile), "fixture within matcher bounds");
        RouteVectorAttributes.Result result = builder.finish(); expect(result == builder.finish(), "finish idempotent"); return result;
    }
    private static void independentFieldsAndIdentity() {
        VectorRoadTile tile = single("4", "1", false); YandexRoute route = route(tile, false);
        RouteVectorAttributes.Result result = match(route, tile, tile);
        expect(result.attributes(0).roadClass == 7 && result.attributes(0).direction == 1, "full route literal fields");
        expect(result.attributes(0).laneNum == null && result.attributes(0).formway == null
                && result.attributes(0).ownership == null && result.attributes(0).linkType == null, "unavailable fields remain null");
        expect(result.matches(route) && !result.exactNativeId && result.knownEdges == 1, "route identity and geometric quality");
        expect(result.attributesAt(1).direction == 1, "final scalar uses final span");
        YandexRoute copied = route(route.routeId, route.lon.clone(), route.lat.clone());
        expect(result.matches(copied), "same geometry copied into a new route object");
        copied.lon[0] += .00001; expect(!result.matches(copied), "same id with changed geometry rejected");
        expect(!result.matches(route("another-route", route.lon.clone(), route.lat.clone())), "different route id rejected");
        RouteVectorAttributes.Result reversed = match(route(tile, true), tile);
        expect(reversed.attributes(0).direction == 1, "renderer order and route traversal do not reverse stock one-way boolean");
        RouteVectorAttributes.Result invalidWay = match(route, single("4", 1L, false));
        expect(invalidWay.attributes(0).direction == null && invalidWay.attributes(0).roadClass == 7, "numeric one is not string one; class independent");
        RouteVectorAttributes.Result category1 = match(route, single("1", "0", false));
        expect(category1.attributes(0).roadClass == null && category1.attributes(0).direction == 0, "category1 cannot invent freeway versus urban expressway");
    }
    private static void competingCandidatesAndRawLevels() {
        VectorRoadTile base = single("4", "1", false); YandexRoute route = route(base, false);
        VectorRoadTile competitors = tile(new int[][]{{-5000, 0, 5000, 0}, {-5000, 0, 5000, 0}},
                new String[]{"4", "4"}, new Object[]{"1", "0"}, new String[]{"bridge", "none"}, new int[]{2, 0});
        RouteVectorAttributes.Result result = match(route, competitors);
        expect(result.attributes(0).roadClass == 7 && result.attributes(0).direction == null, "unanimous class retained through direction conflict");
        expect(result.conflictSamples > 0 && result.edges.get(0).ambiguousSamples > 0, "ambiguity counted");
        expect(result.spans.get(0).levelStructureConflict && result.spans.get(0).literalConflict, "raw same-renderer level/structure conflict retained");
        expect(result.evidence.size() == 2 && result.evidence.get(0).containsKey("literalTypes"), "bounded raw and scalar types retained");
        for (List<VectorRoadTile> order : Arrays.asList(Arrays.asList(base, single("6", "1", false)), Arrays.asList(single("6", "1", false), base))) {
            RouteVectorAttributes.Result conflict = match(route, order.toArray(new VectorRoadTile[0]));
            expect(conflict.attributes(0).roadClass == null && conflict.attributes(0).direction == 1, "candidate order cannot select a class winner");
        }
        VectorRoadTile crossing = tile(new int[][]{{0, -5000, 0, 5000}}, new String[]{"6"}, new Object[]{"0"}, new String[]{"none"}, new int[]{0});
        expect(match(route, base, crossing).attributes(0).roadClass == 7, "perpendicular crossing rejected by heading");
    }
    private static void wholeRouteCutsAndGaps() {
        VectorRoadTile base = single("4", "1", false); YandexRoute route = route(base, false);
        VectorRoadTile changes = tile(new int[][]{{-5000, 0, -300, 0}, {-300, 0, 5000, 0}},
                new String[]{"4", "6"}, new Object[]{"1", "0"}, new String[]{"none", "none"}, new int[]{0, 0});
        RouteVectorAttributes.Result result = match(route, changes);
        expect(result.attributes(0).roadClass == null && result.attributes(0).direction == null, "one original edge contains attribute changes");
        expect(result.cuts().length >= 2, "uncertain transition bin bounded by scalar cuts");
        expect(result.attributesBetween(.05, .2).roadClass == 7 && result.attributesBetween(.8, .95).roadClass == 8, "scalar spans cover both ends of whole route");
        expect(result.attributesBetween(.05, .95).roadClass == null, "interval crossing change has no unanimous class");
        double[] cuts = result.cuts(); double first = cuts[0]; cuts[0] = -1; expect(result.cuts()[0] == first, "cuts defensive copy");
        VectorRoadTile gaps = tile(new int[][]{{-5000, 0, -500, 0}, {500, 0, 5000, 0}},
                new String[]{"4", "4"}, new Object[]{"1", "1"}, new String[]{"none", "none"}, new int[]{0, 0});
        RouteVectorAttributes.Result gap = match(route, gaps);
        expect(gap.unmatchedSamples > 0 && gap.attributes(0).roadClass == null, "partial pieces cannot label whole edge");
        expect(gap.attributesBetween(.4, .6).roadClass == null && gap.attributesBetween(.01, .1).roadClass == 7, "gap stays local and does not erase earlier sampled knowledge");
        RouteVectorAttributes.Result none = new RouteVectorAttributes.Builder(route).finish();
        expect(none.unmatchedEdges == 1 && !none.truncated && none.knownEdges == 0, "missing tiles diagnostic, not coverage gate");
    }
    private static void budgetRetractionAndDefensiveRouteCopy() {
        VectorRoadTile base = single("4", "1", false); YandexRoute route = route(base, false);
        RouteVectorAttributes.Limits limits = new RouteVectorAttributes.Limits(10, 10000, 1, 100, 100, 100000, 100000, 1000, 2, 100000);
        RouteVectorAttributes.Builder builder = new RouteVectorAttributes.Builder(route, limits);
        expect(builder.accept(base), "first tile within budget");
        expect(!builder.accept(single("6", "1", false)), "late competitor exceeds tile budget");
        RouteVectorAttributes.Result failed = builder.finish();
        expect(failed.truncated && failed.knownEdges == 0 && failed.attributesAt(.5).roadClass == null,
                "unseen competitor risk retracts earlier attributes");
        RouteVectorAttributes.Builder copied = new RouteVectorAttributes.Builder(route);
        route.lon[0] += .001;
        expect(copied.accept(base) && copied.finish().knownEdges == 1 && !copied.finish().matches(route), "builder owns copied source geometry");
    }
    private static void thresholdsAndSdkHash() {
        VectorRoadTile base = single("4", "1", false); YandexRoute route = route(base, false);
        double twoMetres = Math.toDegrees(2 / 6371008.8), fourMetres = Math.toDegrees(4 / 6371008.8);
        YandexRoute near = route(route.routeId, route.lon.clone(), new double[]{route.lat[0] + twoMetres, route.lat[1] + twoMetres});
        YandexRoute far = route(route.routeId, route.lon.clone(), new double[]{route.lat[0] + fourMetres, route.lat[1] + fourMetres});
        expect(match(near, base).knownEdges == 1 && match(far, base).knownEdges == 0, "distance threshold admits 2m, rejects 4m");
        double middle = (route.lat[0] + route.lat[1]) / 2;
        double dy = (route.lon[1] - route.lon[0]) * Math.cos(middle * Math.PI / 180) * Math.tan(20 * Math.PI / 180);
        YandexRoute rotated = route(route.routeId, route.lon.clone(), new double[]{middle - dy / 2, middle + dy / 2});
        expect(match(rotated, base).edges.get(0).matchedSamples == 0, "20 degree heading has no accepted samples");
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream pairs = new java.io.DataOutputStream(bytes);
            for (int i = 0; i < route.lon.length; i++) { pairs.writeDouble(route.lon[i]); pairs.writeDouble(route.lat[i]); }
            pairs.flush();
            expect(RouteVectorAttributes.geometryFingerprint(route).equals(VectorRoadTile.digest(bytes.toByteArray())), "SDK big-endian double-pair hash");
        } catch (java.io.IOException impossible) { throw new AssertionError(impossible); }
        YandexRoute large = route("hash-utility-sdk-bound", new double[200000], new double[200000]);
        expect(RouteVectorAttributes.geometryFingerprint(large).length() == 64, "hash utility accepts SDK 200000 point bound independently of matcher budget");
    }
    private static void typedRendererClassConsensus(){
        VectorRoadTile base=single("1","1",false);YandexRoute route=route(base,false);
        RouteAttributes.RendererClass raw=match(route,base).rendererClassBetween(0,1);
        expect("1".equals(raw.rawClass)&&"string".equals(raw.classType)&&Long.valueOf(1).equals(raw.rawFc)&&"uint32".equals(raw.fcType),"source carries literal class/fc and protobuf types separately from legacy roadClass");
        expect(raw.functionalClass()==1,"ambiguous category1 survives raw contract even when old converted class is null");
        expect(match(route,base,single("1","0",false)).rendererClassBetween(0,1).functionalClass()==1,"oneway-only conflict does not erase independently unanimous class/fc");
        VectorRoadTile link=single("1_link","1",false);
        expect(match(route,link).rendererClassBetween(0,1).functionalClass()==1,"typed _link class retains functional group");
        for(Object[] values:new Object[][]{{1L,1L},{"1","1"},{"1",1.0f},{"1",2L},{"pedestrian",1L}}){
            VectorRoadTile bad=typedTile(new int[][]{{-5000,0,5000,0}},new Object[]{values[0]},new Object[]{values[1]},new Object[]{"1"},new String[]{"none"},new int[]{0});
            expect(match(route,bad).rendererClassBetween(0,1).functionalClass()==null,"wrong class/fc type, inconsistent fc, and non-driving source stay unknown");
        }
        VectorRoadTile incompatibleType=typedTile(new int[][]{{-5000,0,5000,0}},new Object[]{"1"},new Object[]{1.0f},new Object[]{"1"},new String[]{"none"},new int[]{0});
        expect(match(route,base,incompatibleType).rendererClassBetween(0,1).functionalClass()==null,"matching numeric-looking competitor with wrong protobuf type invalidates consensus");
        VectorRoadTile overpass=tile(new int[][]{{-5000,0,5000,0},{-5000,0,5000,0}},new String[]{"1","1"},new Object[]{"1","1"},new String[]{"bridge","none"},new int[]{2,0});
        RouteAttributes.RendererClass level=match(route,overpass).rendererClassBetween(0,1);
        expect(level.functionalClass()==null&&"level_structure_conflict".equals(level.quality),"overlapping different levels cannot supply an invented route class");
        RouteVectorAttributes.Limits noEvidence=new RouteVectorAttributes.Limits(10,10000,10,100,100,100000,100000,1000,0,100000);
        RouteVectorAttributes.Builder builder=new RouteVectorAttributes.Builder(route,noEvidence);expect(builder.accept(base),"raw source within work budget");
        RouteVectorAttributes.Result withoutEvidence=builder.finish();expect(withoutEvidence.evidence.isEmpty()&&withoutEvidence.rendererClassBetween(0,1).functionalClass()==1,"complete raw consensus does not depend on bounded evidence snippets");
    }
    private static void rawBoundariesGapsAndRetraction(){
        VectorRoadTile base=single("2","1",false);YandexRoute route=route(base,false);
        VectorRoadTile changes=tile(new int[][]{{-5000,0,-300,0},{-300,0,5000,0}},new String[]{"2","4"},new Object[]{"1","1"},new String[]{"none","none"},new int[]{0,0});
        RouteVectorAttributes.Result result=match(route,changes);
        expect(result.attributesBetween(0,1).roadClass==7,"legacy converted class intentionally same on both sides");
        expect(result.rendererClassBetween(.01,.1).functionalClass()==2&&result.rendererClassBetween(.8,.9).functionalClass()==4,"raw classes remain distinct despite equal old numeric conversion");
        expect(result.rendererClassBetween(0,1).functionalClass()==null,"whole interval cannot take one midpoint class");
        expect(result.cuts().length>=2,"raw-only change contributes partition boundaries including unknown transition bin");
        for(int i=1;i<result.spans.size();i++){
            RouteVectorAttributes.Span before=result.spans.get(i-1),after=result.spans.get(i);
            if(!before.rendererClass.equals(after.rendererClass)){
                expect(result.rendererClassBetween(before.begin,before.end).equals(before.rendererClass),"half-open interval ending at boundary uses preceding bin only");
                expect(result.rendererClassBetween(after.begin,after.end).equals(after.rendererClass),"interval starting at boundary uses following bin only");
            }
        }
        VectorRoadTile gaps=tile(new int[][]{{-5000,0,-500,0},{500,0,5000,0}},new String[]{"2","2"},new Object[]{"1","1"},new String[]{"none","none"},new int[]{0,0});
        RouteVectorAttributes.Result gap=match(route,gaps);
        expect(gap.rendererClassBetween(.01,.1).functionalClass()==2&&gap.rendererClassBetween(.4,.6).functionalClass()==null&&gap.rendererClassBetween(0,1).functionalClass()==null,"unmatched gap remains local and cannot be promoted by interval midpoint");
        RouteVectorAttributes.Builder limited=new RouteVectorAttributes.Builder(route,new RouteVectorAttributes.Limits(10,10000,1,100,100,100000,100000,1000,2,100000));
        expect(limited.accept(base)&&!limited.accept(single("3","1",false)),"late raw competitor triggers bounded truncation");
        expect(limited.finish().rendererClassBetween(0,1).functionalClass()==null,"late truncation retracts all raw class consensus");
        expect(match(route,base,single("3","1",false)).rendererClassBetween(0,1).functionalClass()==null,"different raw classes conflict even when legacy converted number agrees");
    }
    public static void main(String[] args) {
        independentFieldsAndIdentity(); competingCandidatesAndRawLevels(); wholeRouteCutsAndGaps(); budgetRetractionAndDefensiveRouteCopy(); thresholdsAndSdkHash(); typedRendererClassConsensus();rawBoundariesGapsAndRetraction();
        System.out.println("RouteVectorAttributesTest: " + checks + " checks");
    }
}
