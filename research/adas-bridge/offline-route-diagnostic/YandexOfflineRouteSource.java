package dev.denza.hud.probe;

import dev.denza.adaslink.OfflineRegionalGraph;
import dev.denza.adaslink.OfflineRouteAssociation;
import dev.denza.adaslink.YandexRoute;
import java.nio.ByteBuffer;
import java.util.*;

/** Parked, not packaged: extraction of raw regional attributes over selected online geometry.
 * The result is evidence for conversion decisions, not an additional installed-map gate. */
final class YandexOfflineRouteSource {
    private static final long REFRESH_MS=5000;
    private static final int MAX_ROUTE_PACKAGES=16, MAX_ROUTE_EDGES=20000, MAX_REPORT_SPANS=4096;
    private final Thread owner=Thread.currentThread();
    private String lastKey, lastDecodedIdentity, cachedPackage;
    private OfflineRegionalGraph cachedGraph;
    private Map<String,Object> report=Collections.emptyMap();
    private Map<String,Object> lastDecodedReport=Collections.emptyMap();
    private long lastAttempt=-REFRESH_MS;

    Map<String,Object> inspect(YandexRoute route,long epoch,String geometrySha,
            YandexOfflineCachePath.PathSnapshot path,YandexOfflineInventory.Snapshot inventory,long now) {
        owner();
        String key=route==null?null:route.routeId+"|"+epoch+"|"+geometrySha+"|"
                +(path==null?-1:path.generation)+"|"+(inventory==null?"":inventory.fingerprint);
        if(route==null) { clear();return report; }
        if(Objects.equals(key,lastKey)&&now>=lastAttempt&&now-lastAttempt<REFRESH_MS)return report;
        lastKey=key;lastAttempt=now;
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("schema","denza-yandex-offline-route-source-v1");out.put("routeId",route.routeId);out.put("routeEpoch",epoch);
        out.put("geometrySha256",geometrySha);out.put("capturedElapsedMs",now);out.put("scope","whole_selected_route");
        out.put("state","reading");out.put("additionalAdmissionGate",false);
        out.put("path",path==null?null:path.status());
        try {
            YandexOfflineGraphFiles.Snapshot files=YandexOfflineGraphFiles.discover(path,inventory,now);
            out.put("files",files.status());
            if(!files.valid) { out.put("state",files.reasonCode);return save(out); }
            String decodedIdentity=key+"|"+files.identity;
            if(decodedIdentity.equals(lastDecodedIdentity)) {
                Map<String,Object> retained=new LinkedHashMap<>(lastDecodedReport);
                retained.put("files",files.status());retained.put("recheckedElapsedMs",now);return save(retained);
            }
            if(files.files.size()>MAX_ROUTE_PACKAGES) { out.put("state","route_package_budget");return save(out); }
            List<OfflineRegionalGraph.Query> queries=new ArrayList<>();List<Map<String,Object>> packages=new ArrayList<>();
            int edgeCount=0;
            for(YandexOfflineGraphFiles.Package file:files.files) {
                OfflineRegionalGraph graph;
                if(file.identity.equals(cachedPackage))graph=cachedGraph;
                else {
                    // No mapped file escapes the I/O fence. The owned byte[] stays unchanged.
                    byte[] bytes=YandexOfflineGraphFiles.readStable(file);
                    graph=OfflineRegionalGraph.decode(ByteBuffer.wrap(bytes),"yandex-offline-region:"+file.regionId);
                    cachedPackage=file.identity;cachedGraph=graph;
                }
                OfflineRegionalGraph.Query query=graph.queryRoute(route.lon,route.lat,15,
                        Math.min(OfflineRegionalGraph.MAX_QUERY_EDGES,Math.max(1,MAX_ROUTE_EDGES-edgeCount)));
                queries.add(query);edgeCount+=query.edges.size();
                Map<String,Object> p=new LinkedHashMap<>();p.put("regionId",file.regionId);p.put("downloadedRelease",file.downloadedRelease);
                p.put("packageIdentity",file.identity);p.put("packageSha256",graph.sha256());p.put("fileVersion",graph.version());
                p.put("candidateEdges",query.edges.size());p.put("queryTruncated",query.truncated);p.put("skippedNonBase",query.skippedNonBase);
                packages.add(p);
                if(edgeCount>=MAX_ROUTE_EDGES) {out.put("state","route_edge_budget");out.put("packages",packages);return save(out);}
            }
            OfflineRouteAssociation.Result result=OfflineRouteAssociation.match(route,queries);
            out.put("packages",packages);out.put("association",result.summary());
            List<Map<String,Object>> spans=new ArrayList<>();
            for(OfflineRouteAssociation.Span span:result.spans) {if(spans.size()>=MAX_REPORT_SPANS)break;spans.add(span.evidence());}
            out.put("spans",spans);out.put("reportedSpans",spans.size());out.put("spanReportTruncated",spans.size()!=result.spans.size());
            out.put("state",result.queryTruncated?"candidate_query_truncated":"raw_attributes_associated");
            out.put("adasFieldsPromoted",0);
            lastDecodedIdentity=decodedIdentity;
            lastDecodedReport=Collections.unmodifiableMap(new LinkedHashMap<>(out));
            return save(out);
        } catch(java.io.IOException|RuntimeException error) {
            cachedGraph=null;cachedPackage=null;
            out.put("state","regional_read_failed");out.put("error",error.getClass().getSimpleName()+": "+error.getMessage());
            return save(out);
        }
    }
    void clear() {owner();lastKey=null;lastDecodedIdentity=null;cachedPackage=null;cachedGraph=null;report=Collections.emptyMap();lastDecodedReport=Collections.emptyMap();lastAttempt=-REFRESH_MS;}
    private Map<String,Object> save(Map<String,Object> value) { report=Collections.unmodifiableMap(value);return report; }
    private void owner() {if(Thread.currentThread()!=owner)throw new IllegalStateException("regional source worker required");}
}
