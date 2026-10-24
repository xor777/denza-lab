package dev.denza.adaslink;

import java.util.*;

/** Full selected-route association with downloaded Yandex edge shapes. This is source evidence,
 * not a legal-access decision or an ADAS enum conversion. No alternate route is calculated. */
public final class OfflineRouteAssociation {
    private static final double R = 6371008.8, CELL = 100, RADIUS = 3, STEP = 5;
    private static final double HEADING_COS = Math.cos(Math.toRadians(15));
    private static final int MAX_SEGMENTS = 500000, MAX_CELLS = 2000000, MAX_SAMPLES = 500000, MAX_SPANS = 100000;
    private static final String[] FIELDS = {"rawType","rawStructType","rawCategory","rawAccessMask"};

    public static final class Span {
        public final double begin, end, lengthM;
        public final String state;
        public final OfflineRegionalGraph.Edge edge;
        public final Map<String,Long> rawConsensus;
        public final List<String> candidateIds;
        public final int candidateCount;
        private Span(double begin, double end, double length, String state, OfflineRegionalGraph.Edge edge,
                     Map<String,Long> consensus,List<String> ids,int count) {
            this.begin = begin; this.end = end; lengthM = length; this.state = state; this.edge = edge;
            rawConsensus=Collections.unmodifiableMap(new LinkedHashMap<>(consensus));
            candidateIds=Collections.unmodifiableList(new ArrayList<>(ids)); candidateCount=count;
        }
        public Map<String,Object> evidence() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("beginScalar",begin); m.put("endScalar",end); m.put("lengthM",lengthM); m.put("state",state);
            m.put("rawConsensus",rawConsensus); m.put("candidateCount",candidateCount); m.put("candidateIds",candidateIds);
            m.put("candidateIdsTruncated",candidateCount>candidateIds.size());
            if (edge != null) {
                m.put("localEdgeId",edge.localId); m.put("packageSha256",edge.packageSha256);
                m.put("directedIndex",edge.index); m.put("attributeIndex",edge.attributeIndex);
                m.put("rawType",edge.rawType); m.put("rawStructType",edge.rawStructType);
                m.put("rawCategory",edge.rawCategory); m.put("rawAccessMask",edge.rawAccessMask);
            }
            return Collections.unmodifiableMap(m);
        }
    }

    public static final class Result {
        public final List<Span> spans;
        public final boolean queryTruncated;
        public final double totalM, uniqueM, ambiguousM, unmatchedM;
        public final int samples, candidates;
        public final Map<String,Double> fieldConsensusMetres;
        private Result(List<Span> spans, boolean truncated, double total, double unique, double ambiguous,
                       double unmatched, int samples, int candidates,Map<String,Double> fieldConsensus) {
            this.spans = Collections.unmodifiableList(spans); queryTruncated = truncated;
            totalM = total; uniqueM = unique; ambiguousM = ambiguous; unmatchedM = unmatched;
            this.samples = samples; this.candidates = candidates;
            fieldConsensusMetres=Collections.unmodifiableMap(new LinkedHashMap<>(fieldConsensus));
        }
        public Map<String,Object> summary() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("schema","denza-yandex-regional-route-association-v1"); m.put("scope","whole_selected_route");
            m.put("method","directed_shape_samples"); m.put("queryTruncated",queryTruncated);
            m.put("totalM",totalM); m.put("uniqueM",uniqueM); m.put("ambiguousM",ambiguousM); m.put("unmatchedM",unmatchedM);
            m.put("uniqueFraction",totalM == 0 ? 0 : uniqueM/totalM); m.put("samples",samples); m.put("candidates",candidates);
            m.put("rawFieldConsensusM",fieldConsensusMetres);
            m.put("spans",spans.size()); m.put("sampleStepM",STEP); m.put("radiusM",RADIUS); m.put("headingDegrees",15);
            m.put("exactRouteEdgeIdentityProved",false); m.put("legalAccessDecoded",false);
            m.put("continuousLaneCountDecoded",false); m.put("adasFieldsPromoted",0);
            m.put("meaning","2D geometric association with raw attributes; not a route coverage prerequisite or an ADAS eligibility result");
            return Collections.unmodifiableMap(m);
        }
    }

    private static final class Segment {
        final int candidate;
        final double x,y,dx,dy,squared;
        Segment(int c,double x,double y,double dx,double dy) {
            candidate=c; this.x=x; this.y=y; this.dx=dx; this.dy=dy; squared=dx*dx+dy*dy;
        }
    }

    public static Result match(YandexRoute route, List<OfflineRegionalGraph.Query> queries) {
        if (route == null || queries == null || route.lon.length < 2 || route.lon.length > 200000)
            throw new IllegalArgumentException("bounded route and regional queries required");
        double originLon=route.lon[0], originLat=route.lat[0], my=R*Math.PI/180, mx=my*Math.cos(Math.toRadians(originLat));
        if (Math.abs(mx)<1) throw new IllegalArgumentException("polar projection unsupported");
        // Keep regions/releases distinct. Coincident edges from different packages are competing
        // evidence until native cross-package identities, levels and access are understood.
        List<OfflineRegionalGraph.Edge> edges = new ArrayList<>(); Set<String> identities=new HashSet<>();
        Map<Long,List<Segment>> cells=new HashMap<>(); boolean truncated=false; int segments=0, entries=0;
        for (OfflineRegionalGraph.Query query:queries) {
            if(query==null)throw new IllegalArgumentException("null regional query");
            truncated |= query.truncated;
            for(OfflineRegionalGraph.Edge edge:query.edges) {
                if(!identities.add(edge.localId))continue;
                int candidate=edges.size(); edges.add(edge);
                for(int p=1;p<edge.pointCount();p++) {
                    double x=(edge.lon(p-1)-originLon)*mx,y=(edge.lat(p-1)-originLat)*my;
                    Segment s=new Segment(candidate,x,y,(edge.lon(p)-edge.lon(p-1))*mx,(edge.lat(p)-edge.lat(p-1))*my);
                    if(s.squared<1e-10)continue;
                    if(++segments>MAX_SEGMENTS)throw new IllegalArgumentException("regional segment budget");
                    int ax=cell(Math.min(x,x+s.dx)-RADIUS),bx=cell(Math.max(x,x+s.dx)+RADIUS);
                    int ay=cell(Math.min(y,y+s.dy)-RADIUS),by=cell(Math.max(y,y+s.dy)+RADIUS);
                    long count=((long)bx-ax+1)*((long)by-ay+1);
                    if(count<0||count>4096||count>MAX_CELLS-entries)throw new IllegalArgumentException("regional cell budget");
                    entries+=(int)count;
                    for(int cx=ax;cx<=bx;cx++)for(int cy=ay;cy<=by;cy++)
                        cells.computeIfAbsent(key(cx,cy),ignored->new ArrayList<>()).add(s);
                }
            }
        }
        List<Span> spans=new ArrayList<>(); int samples=0;
        Map<String,Double> fieldMetres=new LinkedHashMap<>();for(String field:FIELDS)fieldMetres.put(field,0.0);
        double total=0,unique=0,ambiguous=0,unmatched=0;
        for(int p=1;p<route.lon.length;p++) {
            double x=(route.lon[p-1]-originLon)*mx,y=(route.lat[p-1]-originLat)*my;
            double dx=(route.lon[p]-route.lon[p-1])*mx,dy=(route.lat[p]-route.lat[p-1])*my,length=Math.hypot(dx,dy);
            if(!Double.isFinite(length)||Math.abs(route.lon[p]-route.lon[p-1])>180)
                throw new IllegalArgumentException("invalid or antimeridian route segment");
            if(length<1e-6)continue;
            double needed=Math.ceil(length/STEP);
            if(needed>MAX_SAMPLES-samples)throw new IllegalArgumentException("regional sample budget");
            int n=(int)needed; samples+=n; double weight=length/n;
            for(int i=0;i<n;i++) {
                double fraction=(i+.5)/n,px=x+dx*fraction,py=y+dy*fraction;
                Set<Integer> matches=new HashSet<>(); List<Segment> near=cells.get(key(cell(px),cell(py)));
                if(near!=null)for(Segment s:near) {
                    // Directed shape order is used only to associate the chosen route. Presence of
                    // a directed index never asserts that opposite-direction driving is forbidden.
                    if((dx*s.dx+dy*s.dy)/(length*Math.sqrt(s.squared))<HEADING_COS)continue;
                    double u=Math.max(0,Math.min(1,((px-s.x)*s.dx+(py-s.y)*s.dy)/s.squared));
                    if(Math.hypot(px-s.x-u*s.dx,py-s.y-u*s.dy)<=RADIUS)matches.add(s.candidate);
                }
                total+=weight;
                String state; OfflineRegionalGraph.Edge edge=null;
                if(truncated) { state="candidate_query_truncated"; unmatched+=weight; }
                else if(matches.isEmpty()) { state="unmatched"; unmatched+=weight; }
                else if(matches.size()>1) { state="ambiguous_geometry"; ambiguous+=weight; }
                else { state="unique_geometry"; unique+=weight; edge=edges.get(matches.iterator().next()); }
                // Identity can remain ambiguous while an individual raw attribute agrees in every
                // candidate. Retain that fact without selecting an edge or inventing field meaning.
                Map<String,Long> consensus=new LinkedHashMap<>(); List<String> ids=new ArrayList<>();
                if(!truncated&&!matches.isEmpty()) {
                    for(String field:FIELDS) {
                        Long value=null; boolean agrees=true;
                        for(int id:matches) { long next=raw(edges.get(id),field);if(value==null)value=next;else if(value!=next){agrees=false;break;} }
                        if(agrees) { consensus.put(field,value);fieldMetres.put(field,fieldMetres.get(field)+weight); }
                    }
                    for(int id:matches)ids.add(edges.get(id).localId);
                    Collections.sort(ids);if(ids.size()>8)ids=new ArrayList<>(ids.subList(0,8));
                }
                double begin=p-1+(double)i/n,end=p-1+(double)(i+1)/n;
                Span last=spans.isEmpty()?null:spans.get(spans.size()-1);
                if(last!=null&&last.end==begin&&last.state.equals(state)&&same(last.edge,edge)
                        &&last.rawConsensus.equals(consensus)&&last.candidateIds.equals(ids)&&last.candidateCount==matches.size())
                    spans.set(spans.size()-1,new Span(last.begin,end,last.lengthM+weight,state,edge,consensus,ids,matches.size()));
                else {
                    if(spans.size()>=MAX_SPANS)throw new IllegalArgumentException("regional span budget");
                    spans.add(new Span(begin,end,weight,state,edge,consensus,ids,matches.size()));
                }
            }
        }
        return new Result(spans,truncated,total,unique,ambiguous,unmatched,samples,edges.size(),fieldMetres);
    }
    private static long raw(OfflineRegionalGraph.Edge edge,String field) {
        switch(field) {
            case "rawType":return edge.rawType;
            case "rawStructType":return edge.rawStructType;
            case "rawCategory":return edge.rawCategory;
            case "rawAccessMask":return edge.rawAccessMask;
            default:throw new IllegalArgumentException("raw field");
        }
    }
    private static boolean same(OfflineRegionalGraph.Edge a,OfflineRegionalGraph.Edge b) {
        return a==b || a!=null&&b!=null&&a.localId.equals(b.localId);
    }
    private static int cell(double value) { return (int)Math.floor(value/CELL); }
    private static long key(int x,int y) { return ((long)x<<32)|(y&0xffffffffL); }
    private OfflineRouteAssociation() { }
}
