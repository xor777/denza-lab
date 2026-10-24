package dev.denza.adaslink;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Saved online geometry is associated with the installed regional graph, never recalculated. */
public final class OfflineRouteAssociationTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        File root=new File(args.length==0?".":args[0]);
        File corpus=new File(root,"captures/yandex-offline-region-20261024");
        OfflineRegionalGraph graph=OfflineRegionalGraph.decode(Files.readAllBytes(new File(corpus,"region-43/driving.fb").toPath()),"test-regional");
        Map<String,Object> capture=(Map<String,Object>)Json.parse(new String(Files.readAllBytes(new File(corpus,"runtime/online-short.json").toPath()),StandardCharsets.UTF_8));
        Map<String,Object> raw=(Map<String,Object>)((List<?>)capture.get("routes")).get(0);
        Map<?,?> geometry=(Map<?,?>)raw.get("getGeometry"),points=(Map<?,?>)geometry.get("getPoints");
        List<?> list=(List<?>)points.get("items"); double[] lon=new double[list.size()],lat=new double[list.size()];
        for(int i=0;i<list.size();i++) { Map<?,?> p=(Map<?,?>)list.get(i); lon[i]=((Number)p.get("getLongitude")).doubleValue();lat[i]=((Number)p.get("getLatitude")).doubleValue(); }
        YandexRoute route=new YandexRoute(String.valueOf(raw.get("routeId")),lon,lat,Collections.emptyList(),null,null,null,null,null,null);
        OfflineRegionalGraph.Query query=graph.queryRoute(lon,lat,15,10000);
        OfflineRouteAssociation.Result result=OfflineRouteAssociation.match(route,Collections.singletonList(query));
        check(!query.truncated,"saved route candidate query fits budget");
        check(result.totalM>1000&&result.samples>200,"entire recorded route sampled");
        check(result.uniqueM>result.totalM*.75,"substantial real-geometry association");
        check(result.unmatchedM<result.totalM*.01,"captured route remains within regional graph");
        check(Math.abs(result.totalM-result.uniqueM-result.ambiguousM-result.unmatchedM)<1e-5,"all route metres accounted once");
        check(result.spans.get(0).begin==0&&result.spans.get(result.spans.size()-1).end==route.edges(),"both ends included");
        check(Boolean.FALSE.equals(result.summary().get("exactRouteEdgeIdentityProved")),"geometry never upgraded to native identity");
        check(Integer.valueOf(0).equals(result.summary().get("adasFieldsPromoted")),"unmapped raw enums never exported to ADAS");
        check(result.fieldConsensusMetres.get("rawCategory")>result.totalM*.99,"category consensus survives ambiguous edge identities");
        check(result.fieldConsensusMetres.get("rawType")<result.totalM,"actual type conflicts are retained");
        for(OfflineRouteAssociation.Span span:result.spans)if(span.edge!=null) {
            check(span.edge.packageSha256.equals(graph.sha256()),"every unique candidate retains package identity");
            check(span.evidence().containsKey("rawAccessMask"),"raw access retained separately");
        }
        OfflineRouteAssociation.Result twice=OfflineRouteAssociation.match(route,Arrays.asList(query,query));
        check(Math.abs(twice.uniqueM-result.uniqueM)<1e-6,"same package/index input deduplicates");
        OfflineRouteAssociation.Result none=OfflineRouteAssociation.match(route,Collections.emptyList());
        check(none.uniqueM==0&&none.unmatchedM==none.totalM,"missing graph explicit over full route");
        OfflineRegionalGraph.Query truncated=graph.queryRoute(lon,lat,15,1);
        check(truncated.truncated,"small candidate budget reports truncation");
        OfflineRouteAssociation.Result limited=OfflineRouteAssociation.match(route,Collections.singletonList(truncated));
        check(limited.uniqueM==0&&limited.queryTruncated,"incomplete competitor set cannot declare unique matches");
        System.out.println("OfflineRouteAssociationTest: "+checks+" checks");
    }
}
