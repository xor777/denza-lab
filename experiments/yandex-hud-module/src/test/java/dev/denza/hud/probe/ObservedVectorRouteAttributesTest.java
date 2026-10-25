package dev.denza.hud.probe;

import dev.denza.adaslink.RouteAttributes;
import dev.denza.adaslink.RouteVectorAttributes;
import dev.denza.adaslink.VectorRoadTile;
import dev.denza.adaslink.VectorRoadTileCache;
import dev.denza.adaslink.YandexRoute;
import dev.denza.adaslink.yandexnative.YandexNativeAdapter;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/** Actual typed protobuf tile fixtures through the product fallback and native input adapter. */
public final class ObservedVectorRouteAttributesTest {
    private static final VectorRoadTile.TileId TILE=new VectorRoadTile.TileId(20000,10000,15);
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static byte[] join(byte[]... parts){ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] p:parts)out.write(p,0,p.length);return out.toByteArray();}
    private static byte[] vi(long value){ByteArrayOutputStream out=new ByteArrayOutputStream();do{int b=(int)(value&127);value>>>=7;out.write(b|(value==0?0:128));}while(value!=0);return out.toByteArray();}
    private static byte[] v(int field,long value){return join(vi((long)field<<3),vi(value));}
    private static byte[] msg(int field,byte[] bytes){return join(vi((long)field<<3|2),vi(bytes.length),bytes);}
    private static byte[] str(int field,String value){return msg(field,value.getBytes(StandardCharsets.UTF_8));}
    private static byte[] packed(int field,long... values){byte[][] raw=new byte[values.length][];for(int i=0;i<values.length;i++)raw[i]=vi(values[i]);return msg(field,join(raw));}
    private static byte[] tile(int end,int... classes){
        long[] coords=new long[classes.length*4],lengths=new long[classes.length];byte[] values=new byte[0],features=new byte[0];
        for(int i=0;i<classes.length;i++) {
            int[] line={-5000,0,end+5000,0};
            for(int k=0;k<4;k++)coords[4*i+k]=((long)line[k]<<1)^(line[k]>>31);
            lengths[i]=2;
            values=join(values,msg(5,str(1,Integer.toString(classes[i]))),msg(5,v(3,classes[i])));
            byte[] geometry=msg(2,v(1,1));
            features=join(features,msg(2,join(geometry,msg(6,join(v(1,0),packed(2,2*i,2*i+1))))));
        }
        return join(v(1,14),packed(2,coords),packed(3,lengths),msg(4,join(str(1,"class"),str(1,"fc"))),values,msg(6,join(str(1,"road"),features)));
    }
    private static VectorRoadTileCache cache(byte[] bytes){VectorRoadTileCache c=new VectorRoadTileCache(32,32L<<20);check(c.put(bytes,TILE,TILE,TILE,1)!=null,"fixture decoded as an actual typed cache tile");return c;}
    private static YandexRoute route(String id){
        VectorRoadTile tile=VectorRoadTile.decode(tile(5000,4),TILE,TILE,TILE);
        VectorRoadTile.Point a=tile.features().get(0).points.get(0),b=tile.features().get(0).points.get(1);
        return new YandexRoute(id,new double[]{a.lon,b.lon},new double[]{a.lat,b.lat},Collections.emptyList(),null,
                new double[0],Collections.emptyList(),Collections.emptyList(),null,null);
    }
    private static RouteAttributes prepare(ObservedVectorRouteAttributes source,YandexRoute route,long epoch,VectorRoadTileCache cache){
        return source.prepare(route,epoch,RouteVectorAttributes.geometryFingerprint(route),cache.snapshot(),"no_supported_package");
    }
    private static int nativeClass(YandexRoute route,RouteAttributes attrs,int link){
        return YandexNativeAdapter.prepare(route,101,202,1,attrs,YandexNativeAdapter.Policy.TEST_ROAD_DATA)
                .getSegmentInfo(0).getLinkInfo(link).getRoadClass();
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> coverage(RouteAttributes attrs){return(Map<String,Object>)attrs.summary().get("attributes");}

    private static void actualRawInputsAndFreeze(){
        YandexRoute route=route("observed");VectorRoadTileCache cache=cache(tile(5000,4));
        ObservedVectorRouteAttributes source=new ObservedVectorRouteAttributes();RouteAttributes attrs=prepare(source,route,1,cache);
        check(attrs.rendererClassBetween(0,1).functionalClass()==4,"whole interval exposes typed raw class and integral fc");
        check(attrs.attributesBetween(0,1).roadClass==null,"legacy converted attributes never become fallback input");
        check(nativeClass(route,attrs,0)==7,"observed class4 reaches the product native adapter");
        check("navigator-vector-native-success".equals(attrs.summary().get("source")),"actual observed source named");
        check("no_supported_package".equals(attrs.summary().get("offlineReason")),"offline acquisition limitation retained");
        check(Boolean.FALSE.equals(attrs.summary().get("fullRouteCoverageProved")),"sampled consensus is not full route coverage proof");
        check(((Number)coverage(attrs).get("rendererClassKnownSamples")).intValue()>0,"actual raw sample coverage reported");
        check(((java.util.List<?>)attrs.summary().get("tileFingerprints")).size()==1,"source fingerprint retained");
        cache.put(tile(5000,6),TILE,TILE,TILE,2);
        check(prepare(source,route,1,cache)==attrs&&nativeClass(route,attrs,0)==7,"later cache replacement cannot change published static inputs");
        cache.clear();check(prepare(source,route,1,cache)==attrs,"source clear cannot mutate frozen route partition");
        RouteAttributes empty=prepare(source,route,2,cache);
        check(empty!=attrs&&empty.rendererClassBetween(0,1).functionalClass()==null,"new route epoch uses current empty source");
        cache.put(tile(5000,1),TILE,TILE,TILE,3);
        YandexRoute replacement=route("replacement");RouteAttributes next=prepare(source,replacement,3,cache);
        check(nativeClass(replacement,next,0)==6,"new route receives new observed class1 under explicit test policy");
        source.clear();cache.release();
        RouteAttributes released=prepare(source,replacement,3,cache);
        check(released.rendererClassBetween(0,1).functionalClass()==null&&"observed_source_released".equals(released.summary().get("reason")),"released snapshot never supplies stale tiles");
        check(source.prepare(replacement,3,"wrong-geometry",cache.snapshot(),"failure")==null,"geometry identity checked before preparation");
    }
    private static void partialConflictAndBudgets(){
        YandexRoute route=route("partial");
        RouteAttributes partial=prepare(new ObservedVectorRouteAttributes(),route,1,cache(tile(-500,4)));
        check(partial.rendererClassBetween(.01,.1).functionalClass()==4,"observed portion retains raw class");
        check(partial.rendererClassBetween(.8,.9).functionalClass()==null,"uncovered route interval remains unknown");
        check(((Number)coverage(partial).get("rendererClassUnknownSamples")).intValue()>0,"unknown coverage is counted");
        double[] cuts=partial.cuts();check(cuts.length>0,"raw known/unknown transition owns a static cut");
        double cut=cuts[0];cuts[0]=-1;check(partial.cuts()[0]==cut,"partition cuts are copied");
        RouteAttributes conflict=prepare(new ObservedVectorRouteAttributes(),route,1,cache(tile(5000,4,6)));
        check(conflict.rendererClassBetween(0,1).functionalClass()==null&&nativeClass(route,conflict,0)==-1,"competing raw class literals cannot select a winner");
        RouteVectorAttributes.Limits limits=new RouteVectorAttributes.Limits(100,1000,2,1,100,10000,10000,1000,8,10000);
        RouteAttributes capped=prepare(new ObservedVectorRouteAttributes(limits),route,1,cache(tile(5000,4,6)));
        check(Boolean.TRUE.equals(coverage(capped).get("truncated"))&&"observed_match_budget".equals(capped.summary().get("reason")),"feature budget is explicit");
        check(capped.rendererClassBetween(0,1).functionalClass()==null,"truncation invalidates unseen-competitor assumptions");
        RouteVectorAttributes.Limits oneEdge=new RouteVectorAttributes.Limits(1,1000,2,10,100,10000,10000,1000,8,10000);
        YandexRoute large=new YandexRoute("two-edges",new double[]{route.lon[0],(route.lon[0]+route.lon[1])/2,route.lon[1]},
                new double[]{route.lat[0],route.lat[0],route.lat[1]},Collections.emptyList(),null,new double[0],
                Collections.emptyList(),Collections.emptyList(),null,null);
        ObservedVectorRouteAttributes bounded=new ObservedVectorRouteAttributes(oneEdge);
        VectorRoadTileCache available=cache(tile(5000,4));RouteAttributes rejected=prepare(bounded,large,1,available);
        check(rejected.matches(large)&&rejected.rendererClassBetween(0,2).functionalClass()==null,"oversized route freezes explicit unknown instead of throwing");
        check(rejected==prepare(bounded,large,1,available),"rejected route is not rebuilt on every fix");
    }
    public static void main(String[] args){actualRawInputsAndFreeze();partialConflictAndBudgets();System.out.println("ObservedVectorRouteAttributesTest: "+checks+" checks passed");}
}
