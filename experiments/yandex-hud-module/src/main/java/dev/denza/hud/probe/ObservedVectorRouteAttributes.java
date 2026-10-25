package dev.denza.hud.probe;

import dev.denza.adaslink.RoadNetwork;
import dev.denza.adaslink.RouteAttributes;
import dev.denza.adaslink.RouteVectorAttributes;
import dev.denza.adaslink.VectorRoadTile;
import dev.denza.adaslink.VectorRoadTileCache;
import dev.denza.adaslink.YandexRoute;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conversion-worker-owned, once-per-route raw renderer fallback. No acquisition or map requests. */
final class ObservedVectorRouteAttributes {
    static final String VERSION="observed-vector-route-attributes-v1";
    private final RouteVectorAttributes.Limits limits;
    private String key;
    private Frozen frozen;
    private YandexRoute checkedRoute;
    private String checkedGeometry;

    ObservedVectorRouteAttributes() {
        this(new RouteVectorAttributes.Limits(100000,100000,32,100000,200000,
                2000000L,2000000L,100000,128,16000000L));
    }
    ObservedVectorRouteAttributes(RouteVectorAttributes.Limits limits) { this.limits=limits; }
    void clear() { key=null;frozen=null;checkedRoute=null;checkedGeometry=null; }

    RouteAttributes prepare(YandexRoute route,long epoch,String geometry,
                            VectorRoadTileCache.Snapshot snapshot,String offlineReason) {
        if(route==null||epoch<0||geometry==null)return null;
        if(route!=checkedRoute) {checkedGeometry=RouteVectorAttributes.geometryFingerprint(route);checkedRoute=route;}
        if(!geometry.equals(checkedGeometry))return null;
        String next=route.routeId+"|"+epoch+"|"+geometry;
        if(next.equals(key))return frozen;
        RouteVectorAttributes.Builder builder;
        try {builder=new RouteVectorAttributes.Builder(route,limits);}
        catch(IllegalArgumentException rejected) {
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("schema",VERSION);report.put("source","navigator-vector-native-success");
            report.put("inputFields","raw renderer class/fc only; legacy converted attributes unavailable");
            report.put("reason","observed_route_budget_or_geometry");report.put("offlineReason",offlineReason);
            report.put("routeId",route.routeId);report.put("epoch",epoch);report.put("geometrySha256",geometry);
            report.put("staticAttributesFrozen",true);report.put("fullRouteCoverageProved",false);
            report.put("coverageAdmissionRequired",false);report.put("error",rejected.getMessage());
            key=next;frozen=new Frozen(null,report);return frozen;
        }
        List<String> fingerprints=new ArrayList<>();
        String reason=snapshot==null?"observed_source_unavailable":snapshot.released?"observed_source_released":snapshot.tiles.isEmpty()?"observed_snapshot_empty":"prepared_partial_observed_snapshot";
        if(snapshot!=null&&!snapshot.released)for(VectorRoadTile tile:snapshot.tiles) {
            if(fingerprints.size()<limits.maxTiles)fingerprints.add(tile.fingerprint());
            if(!builder.accept(tile))break;
        }
        RouteVectorAttributes.Result result=builder.finish();
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("schema",VERSION);report.put("source","navigator-vector-native-success");
        report.put("inputFields","raw renderer class/fc only; legacy converted attributes unavailable");
        report.put("reason",result.truncated?"observed_match_budget":reason);report.put("offlineReason",offlineReason);
        report.put("routeId",route.routeId);report.put("epoch",epoch);report.put("geometrySha256",geometry);
        report.put("snapshotGeneration",snapshot==null?-1:snapshot.generation);
        report.put("snapshotEpoch",snapshot==null?-1:snapshot.epoch);
        report.put("snapshotReleased",snapshot!=null&&snapshot.released);
        report.put("observedTiles",snapshot==null||snapshot.released?0:snapshot.tiles.size());
        report.put("tileFingerprints",Collections.unmodifiableList(fingerprints));
        report.put("staticAttributesFrozen",true);report.put("fullRouteCoverageProved",false);
        report.put("coverageAdmissionRequired",false);report.put("coverage","sampled actual observed tiles only; uncovered and conflicting intervals remain unknown");
        report.put("fieldEvidence",result.evidence);
        key=next;frozen=new Frozen(result,report);return frozen;
    }

    private static final class Frozen implements RouteAttributes {
        final RouteVectorAttributes.Result raw;
        final double[] cuts;
        final Map<String,Object> report;
        Frozen(RouteVectorAttributes.Result raw,Map<String,Object> report) {
            this.raw=raw;
            List<Double> boundaries=new ArrayList<>();
            for(int i=1;raw!=null&&i<raw.spans.size();i++) {
                RouteVectorAttributes.Span before=raw.spans.get(i-1),after=raw.spans.get(i);
                if(before.end==after.begin&&!before.rendererClass.equals(after.rendererClass))boundaries.add(after.begin);
            }
            cuts=new double[boundaries.size()];for(int i=0;i<cuts.length;i++)cuts[i]=boundaries.get(i);
            if(raw!=null) {
                Map<String,Object> coverage=new LinkedHashMap<>(raw.summary());
                // Converted-class/oneway counters do not describe this raw-only input policy.
                for(String field:new String[]{"roadClassMapping","knownEdges","knownSamples","conflictEdges","conflictSamples"})coverage.remove(field);
                coverage.put("cuts",cuts.length);report.put("attributes",Collections.unmodifiableMap(coverage));
            }
            this.report=Collections.unmodifiableMap(report);
        }
        public boolean matches(YandexRoute route){return raw!=null?raw.matches(route):route!=null&&report.get("routeId").equals(route.routeId)&&report.get("geometrySha256").equals(RouteVectorAttributes.geometryFingerprint(route));}
        public double[] cuts(){return cuts.clone();}
        public RoadNetwork.KnownAttributes attributesBetween(double begin,double end){return RoadNetwork.KnownAttributes.NONE;}
        public RouteAttributes.RendererClass rendererClassBetween(double begin,double end){return raw==null?RouteAttributes.RendererClass.unknown("observed_route_budget_or_geometry"):raw.rendererClassBetween(begin,end);}
        public Map<String,Object> summary(){return report;}
    }
}
