package dev.denza.adaslink.yandexnative;

import dev.denza.adaslink.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owner-confirmed road ownership for exact traversed WGS84 source edges, not Yandex map facts.
 * Matching quantizes endpoints to 1e-7 degrees and ignores direction; no corridor or proximity match.
 */
public final class PublicRoadEvidence {
    public static final String SCHEMA="denza-owner-confirmed-public-roads-v1";
    public static final PublicRoadEvidence EMPTY=new PublicRoadEvidence(Collections.<String>emptySet(),null);
    private static final int MAX_EDGES=10000,MAX_JSON_CHARS=2000000;
    private final Set<String> edges;
    private final String sha256;
    private PublicRoadEvidence(Set<String> edges,String sha256){this.edges=Collections.unmodifiableSet(new HashSet<>(edges));this.sha256=sha256;}

    public static PublicRoadEvidence parse(String json){
        if(json==null||json.length()>MAX_JSON_CHARS)throw new IllegalArgumentException("public-road catalog size");
        Object parsed=Json.parse(json);
        if(!(parsed instanceof Map))throw new IllegalArgumentException("public-road catalog object");
        Map<?,?> source=(Map<?,?>)parsed;
        if(!SCHEMA.equals(source.get("schema")))throw new IllegalArgumentException("public-road catalog schema");
        Object raw=source.get("edges");
        if(!(raw instanceof List)||((List<?>)raw).size()>MAX_EDGES)throw new IllegalArgumentException("public-road catalog edges limit 10000");
        Set<String> result=new HashSet<>();
        for(Object item:(List<?>)raw){
            if(!(item instanceof List)||((List<?>)item).size()!=4)throw new IllegalArgumentException("public-road catalog edge");
            List<?> edge=(List<?>)item;double[] point=new double[4];
            for(int i=0;i<4;i++){
                if(!(edge.get(i) instanceof Number))throw new IllegalArgumentException("public-road catalog coordinate");
                point[i]=((Number)edge.get(i)).doubleValue();
            }
            result.add(key(point[0],point[1],point[2],point[3],true));
        }
        return new PublicRoadEvidence(result,digest(json));
    }
    public int edgeCount(){return edges.size();}
    public String sha256(){return sha256;}
    public Map<String,Object> provenance(){
        Map<String,Object> out=new LinkedHashMap<>();out.put("schema",SCHEMA);out.put("sha256",sha256);out.put("edges",edges.size());
        out.put("source","owner-confirmed public ownership on fully traversed source geometry edges");
        out.put("matching","WGS84 endpoints Math.round(degrees*1e7), undirected, exact; every intersecting source edge required");
        return Collections.unmodifiableMap(out);
    }
    boolean covers(double[] lon,double[] lat,double begin,double end){
        if(edges.isEmpty()||lon==null||lat==null||lon.length!=lat.length||!Double.isFinite(begin)||!Double.isFinite(end)
                ||begin<0||end<=begin||end>lon.length-1)return false;
        for(int i=(int)Math.floor(begin);i<(int)Math.ceil(end);i++){
            String key=key(lon[i],lat[i],lon[i+1],lat[i+1],false);
            if(key==null||!edges.contains(key))return false;
        }
        return true;
    }
    private static String key(double lon1,double lat1,double lon2,double lat2,boolean reject){
        if(!coordinate(lon1,lat1)||!coordinate(lon2,lat2)){
            if(reject)throw new IllegalArgumentException("public-road catalog WGS84 coordinate bounds");return null;
        }
        long x1=Math.round(lon1*1e7),y1=Math.round(lat1*1e7),x2=Math.round(lon2*1e7),y2=Math.round(lat2*1e7);
        if(x1==x2&&y1==y2){if(reject)throw new IllegalArgumentException("public-road catalog degenerate quantized edge");return null;}
        if(x1>x2||(x1==x2&&y1>y2)){long x=x1,y=y1;x1=x2;y1=y2;x2=x;y2=y;}
        return x1+","+y1+","+x2+","+y2;
    }
    private static boolean coordinate(double lon,double lat){return Double.isFinite(lon)&&Double.isFinite(lat)&&lon>=-180&&lon<=180&&lat>=-90&&lat<=90;}
    private static String digest(String json){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
