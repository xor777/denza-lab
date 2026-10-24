package dev.denza.adaslink;

import java.util.*;

/** Immutable matcher view of Yandex's captured online tiles. No geographic package or defaults. */
public final class GraphRoadNetwork implements RoadNetwork {
    private static final double R = 6371008.8, CELL = 150;
    private final double originLon, originLat, mx, my;
    private final List<Fragment> fragments = new ArrayList<>();
    private final List<double[]> nodes = new ArrayList<>();
    private final Map<Long, int[]> cells = new HashMap<>();
    private int[] starts, adjacency;
    private int conflictingGroups, conflictingVertices, skippedEdges;

    private static final class Group {
        String key, name;
        List<GraphTile.Point> points;
        Long from, to;
        Integer zFrom, zTo;
        boolean forward, backward, conflict;
        int legalForward, legalBackward;
    }
    private static final class VertexEvidence {
        long lon, lat;
        Integer z;
        boolean conflict;
        VertexEvidence(GraphTile.Point p, Integer level) { lon = grid(p.lon); lat = grid(p.lat); z = level; }
        void add(GraphTile.Point p, Integer level) {
            if (grid(p.lon) != lon || grid(p.lat) != lat || (z != null && level != null && !z.equals(level))) conflict = true;
            if (z == null) z = level;
        }
    }

    public GraphRoadNetwork(List<GraphTile> tiles, YandexRoute route) {
        if (route == null || route.lon.length < 2) throw new IllegalArgumentException("route geometry required");
        originLon = route.lon[0]; originLat = route.lat[0];
        my = R * Math.PI / 180; mx = my * Math.cos(Math.toRadians(originLat));
        if (Math.abs(mx) < 1) throw new IllegalArgumentException("unsupported polar projection");
        double minLon = originLon, maxLon = originLon, minLat = originLat, maxLat = originLat;
        for (int i = 0; i < route.lon.length; i++) {
            minLon = Math.min(minLon, route.lon[i]); maxLon = Math.max(maxLon, route.lon[i]);
            minLat = Math.min(minLat, route.lat[i]); maxLat = Math.max(maxLat, route.lat[i]);
        }
        double padLon = 2000 / Math.abs(mx), padLat = 2000 / my;
        Map<String, Group> groups = new TreeMap<>();
        List<GraphTile> ordered = new ArrayList<>(tiles);
        ordered.sort(Comparator.comparing(GraphTile::sha256));
        for (GraphTile tile : ordered) for (GraphTile.Edge edge : tile.edges()) {
            if (!edge.hasGeometry || edge.points.size() < 2) { skippedEdges++; continue; }
            double a = Double.POSITIVE_INFINITY, b = Double.NEGATIVE_INFINITY;
            double c = Double.POSITIVE_INFINITY, d = Double.NEGATIVE_INFINITY;
            for (GraphTile.Point p : edge.points) { a = Math.min(a,p.lon); b = Math.max(b,p.lon); c = Math.min(c,p.lat); d = Math.max(d,p.lat); }
            if (b < minLon-padLon || a > maxLon+padLon || d < minLat-padLat || c > maxLat+padLat) continue;
            List<GraphTile.Point> points = new ArrayList<>(edge.points);
            if (edge.reverseGeometry) Collections.reverse(points);
            Long from = edge.reverseGeometry ? edge.to : edge.from, to = edge.reverseGeometry ? edge.from : edge.to;
            Integer zFrom = edge.reverseGeometry ? edge.endZLevel : edge.beginZLevel;
            Integer zTo = edge.reverseGeometry ? edge.beginZLevel : edge.endZLevel;
            StringBuilder k = new StringBuilder(Long.toUnsignedString(edge.edgeDataId,16));
            for (GraphTile.Point p : points) k.append('/').append(grid(p.lon)).append(',').append(grid(p.lat));
            String key = k.toString();
            Group g = groups.get(key);
            if (g == null) {
                if (groups.size() >= 100000) throw new IllegalArgumentException("graph fragment budget exceeded");
                g = new Group(); g.key = key; g.points = points; g.from = from; g.to = to; g.zFrom = zFrom; g.zTo = zTo;
                groups.put(key, g);
            } else {
                if (!Objects.equals(g.from,from) || !Objects.equals(g.to,to)
                        || unequalKnown(g.zFrom,zFrom) || unequalKnown(g.zTo,zTo)) g.conflict = true;
                // Missing evidence can be supplemented; retain it so a later known
                // level cannot disagree silently with a previously observed level.
                if (g.zFrom == null) g.zFrom = zFrom;
                if (g.zTo == null) g.zTo = zTo;
            }
            if (g.name == null) for (String name : edge.names) if (name != null && !name.isEmpty()) { g.name = name; break; }
            int limit = legalLimit(edge.speedLimitMps);
            if (edge.reverseGeometry) {
                if (g.backward && g.legalBackward > 0 && limit > 0 && g.legalBackward != limit) g.conflict = true;
                g.backward = true; if (limit > 0) g.legalBackward = limit;
            } else {
                if (g.forward && g.legalForward > 0 && limit > 0 && g.legalForward != limit) g.conflict = true;
                g.forward = true; if (limit > 0) g.legalForward = limit;
            }
        }
        Map<Long,VertexEvidence> evidence = new HashMap<>();
        for (Group g : groups.values()) if (!g.conflict) {
            addEvidence(evidence,g.from,g.points.get(0),g.zFrom);
            addEvidence(evidence,g.to,g.points.get(g.points.size()-1),g.zTo);
        }
        for (VertexEvidence v : evidence.values()) if (v.conflict) conflictingVertices++;
        Map<String,Integer> nodeIds = new HashMap<>();
        Map<Long,TreeSet<Integer>> index = new HashMap<>();
        int cellEntries = 0;
        for (Group g : groups.values()) {
            if (g.conflict) { conflictingGroups++; continue; }
            int n = g.points.size();
            double[] x = new double[n], y = new double[n], cum = new double[n];
            for (int i=0;i<n;i++) {
                x[i]=planarX(g.points.get(i).lon); y[i]=planarY(g.points.get(i).lat);
                if(i>0) cum[i]=cum[i-1]+Math.hypot(x[i]-x[i-1],y[i]-y[i-1]);
            }
            if (cum[n-1] < 0.01) { skippedEdges++; continue; }
            Set<Long> touched = new HashSet<>();
            for (int i=1;i<n;i++) {
                int ax=cell(Math.min(x[i-1],x[i])), bx=cell(Math.max(x[i-1],x[i]));
                int ay=cell(Math.min(y[i-1],y[i])), by=cell(Math.max(y[i-1],y[i]));
                if ((long)(bx-ax+1)*(by-ay+1)>4096) throw new IllegalArgumentException("graph geometry cell budget exceeded");
                for(int cx=ax;cx<=bx;cx++) for(int cy=ay;cy<=by;cy++) touched.add(key(cx,cy));
            }
            cellEntries += touched.size();
            if(cellEntries>1000000) throw new IllegalArgumentException("graph spatial index budget exceeded");
            int from=node(nodeIds,nodeKey(g,g.from,0,evidence),x[0],y[0]);
            int to=node(nodeIds,nodeKey(g,g.to,1,evidence),x[n-1],y[n-1]);
            int f=fragments.size();
            fragments.add(Fragment.partial(f,from,to,g.legalForward,g.legalBackward,g.forward,g.backward,g.name==null?"":g.name,x,y,cum));
            for(long cell:touched) index.computeIfAbsent(cell,v->new TreeSet<>()).add(f);
        }
        for(Map.Entry<Long,TreeSet<Integer>> e:index.entrySet()) cells.put(e.getKey(),ints(e.getValue()));
        List<List<Integer>> ends=new ArrayList<>();
        for(int i=0;i<nodes.size();i++) ends.add(new ArrayList<>());
        for(Fragment f:fragments) { ends.get(f.from).add(f.index<<1); ends.get(f.to).add(f.index<<1|1); }
        starts=new int[nodes.size()+1]; adjacency=new int[fragments.size()*2]; int at=0;
        for(int i=0;i<nodes.size();i++) { starts[i]=at; for(int e:ends.get(i)) adjacency[at++]=e; }
        starts[nodes.size()]=at;
    }

    private static boolean unequalKnown(Integer a,Integer b) { return a!=null && b!=null && !a.equals(b); }
    private static int legalLimit(Double mps) {
        if (mps==null || !Double.isFinite(mps) || mps<=0 || mps*3.6>Integer.MAX_VALUE) return 0;
        return (int)Math.round(mps*3.6);
    }
    private static long grid(double v) { return Math.round(v*1000000); }
    private static void addEvidence(Map<Long,VertexEvidence> all,Long id,GraphTile.Point p,Integer z) {
        if(id==null)return; VertexEvidence v=all.get(id); if(v==null)all.put(id,new VertexEvidence(p,z));else v.add(p,z);
    }
    private static String nodeKey(Group g,Long id,int end,Map<Long,VertexEvidence> evidence) {
        // Missing or inconsistent source IDs never join merely because coordinates coincide.
        return id!=null && !evidence.get(id).conflict ? "v"+Long.toUnsignedString(id,16) : "local:"+g.key+":"+end;
    }
    private int node(Map<String,Integer> ids,String key,double x,double y) {
        Integer id=ids.get(key); if(id!=null)return id; int n=nodes.size(); ids.put(key,n); nodes.add(new double[]{x,y});return n;
    }
    private static int cell(double v) { return (int)Math.floor(v/CELL); }
    private static long key(int x,int y) { return ((long)x<<32)|(y&0xffffffffL); }
    private static int[] ints(Collection<Integer> values) { int[] a=new int[values.size()];int i=0;for(int v:values)a[i++]=v;return a; }
    @Override public Coordinates.Frame frame() { return Coordinates.Frame.WGS84; }
    @Override public double planarX(double lon) { return (lon-originLon)*mx; }
    @Override public double planarY(double lat) { return (lat-originLat)*my; }
    @Override public double nodeX(int node) { return nodes.get(node)[0]; }
    @Override public double nodeY(int node) { return nodes.get(node)[1]; }
    @Override public Fragment fragment(int index) { return fragments.get(index); }
    @Override public int adjacencyStart(int node) { return starts[node]; }
    @Override public int adjacencyEnd(int node) { return starts[node+1]; }
    @Override public int adjacency(int k) { return adjacency[k]; }
    @Override public int[] near(double x,double y,double radius) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(radius)||radius<0||radius>1000)throw new IllegalArgumentException("graph query bounds");
        TreeSet<Integer> out=new TreeSet<>();
        for(int cx=cell(x-radius);cx<=cell(x+radius);cx++)for(int cy=cell(y-radius);cy<=cell(y+radius);cy++) {
            int[] f=cells.get(key(cx,cy)); if(f!=null)for(int i:f)out.add(i);
        }
        return ints(out);
    }
    public int fragmentCount() { return fragments.size(); }
    public Map<String,Object> summary() {
        Map<String,Object> m=Json.object(); m.put("source","yandex-native-online");m.put("fragments",fragments.size());
        m.put("vertices",nodes.size());m.put("conflictingGroups",conflictingGroups);m.put("conflictingVertices",conflictingVertices);
        m.put("skippedEdges",skippedEdges);m.put("roadClassKnown",false);m.put("continuousLaneCountKnown",false); return m;
    }
}
