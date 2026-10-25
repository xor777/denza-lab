package dev.denza.adaslink.yandexnative;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Owner-observed one lane per direction (two total), scoped to exact source geometry edges.
 * This catalog supplies neither road ownership nor lane facts on any other edge.
 */
public final class OwnerLaneEvidence {
    public static final String SCHEMA="denza-owner-confirmed-lanes-v1";
    public static final OwnerLaneEvidence EMPTY=new OwnerLaneEvidence(PublicRoadEvidence.ExactEdges.EMPTY);
    private final PublicRoadEvidence.ExactEdges exact;
    private OwnerLaneEvidence(PublicRoadEvidence.ExactEdges exact){this.exact=exact;}
    public static OwnerLaneEvidence parse(String json){return new OwnerLaneEvidence(PublicRoadEvidence.ExactEdges.parse(json,SCHEMA,true));}
    public int edgeCount(){return exact.edges.size();}
    public String sha256(){return exact.sha256;}
    public Map<String,Object> provenance(){
        Map<String,Object> out=new LinkedHashMap<>(exact.provenance(SCHEMA,"owner-observed settlement streets: one lane per direction, two total; exact confirmed source edges only"));
        out.put("laneNum",2);return Collections.unmodifiableMap(out);
    }
    boolean covers(double[] lon,double[] lat,double begin,double end){return exact.covers(lon,lat,begin,end);}
}
