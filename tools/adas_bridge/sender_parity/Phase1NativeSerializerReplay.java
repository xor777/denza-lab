package dev.denza.adaslink.stocknative;

import dev.denza.adaslink.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;

/** Host-only projection of ORIGINAL phase1 getter snapshots, with no defaults. */
public final class Phase1NativeSerializerReplay {
    static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    static List<Object> list(Object value){return (List<Object>)value;}
    static Object required(Map<String,Object> value,String name){if(!value.containsKey(name))throw new AssertionError("missing phase1 getter "+name);return value.get(name);}
    static long number(Object value){return value instanceof Number?((Number)value).longValue():Long.parseLong((String)value);}
    static Object scalar(Object value,Class<?> type){if(type==long.class)return number(value);if(type==int.class)return (int)number(value);return value;}
    static Object proxy(Class<?> type,Map<String,Object> value){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(owner,method,args)->{
        String name=method.getName();
        if(name.equals("getSegmentInfo"))return proxy(NativePath.Segment.class,map(list(required(value,"segments")).get((int)number(args[0]))));
        if(name.equals("getLinkInfo"))return proxy(NativePath.Link.class,map(list(required(value,"links")).get((int)number(args[0]))));
        if(name.equals("getSegmentCount"))return (long)list(required(value,"segments")).size();
        if(name.equals("getLinkCount"))return (long)list(required(value,"links")).size();
        if(name.equals("getGroupSegmentCount"))return (long)list(required(value,"groups")).size();
        if(name.equals("getGroupSegment")){List<Object> g=list(list(required(value,"groups")).get((int)number(args[0])));return new NativePath.Group((int)number(g.get(0)),(int)number(g.get(1)),(String)g.get(2));}
        if(name.equals("getPathViaRoadInfo")){List<Object> roads=list(required(value,"viaRoadInfo"));if(!roads.isEmpty())throw new AssertionError("nonempty phase1 ViaRoad DTO unsupported");return new ArrayList<NativePath.ViaRoad>();}
        if(name.equals("getAllTrafficLights")){List<NativePath.Point> out=new ArrayList<>();for(Object point:list(required(value,"trafficLights"))){List<Object> p=list(point);out.add(new NativePath.Point(((Number)p.get(0)).doubleValue(),((Number)p.get(1)).doubleValue()));}return out;}
        if(name.equals("getPoints")){Object lon=required(value,"lon"),lat=required(value,"lat");if(lon==null&&lat==null)return null;List<Object>x=list(lon),y=list(lat);if(x.size()!=y.size())throw new AssertionError("point arrays differ");List<NativePath.IntPoint>out=new ArrayList<>();for(int i=0;i<x.size();i++)out.add(new NativePath.IntPoint((int)number(x.get(i)),(int)number(y.get(i))));return out;}
        String key;
        switch(name){case "getPathID":key="pathId";break;case "get64TopoID":return new BigInteger((String)required(value,"topoId"));case "getRoadDirection":key="direction";break;case "hasParallelRoad":key="hasParallel";break;case "getURID":key="urid";break;
        default:key=name.startsWith("get")?Character.toLowerCase(name.charAt(3))+name.substring(4):name;}
        return scalar(required(value,key),method.getReturnType());
    });}
    public static void main(String[] args)throws Exception{
        Map<String,Object> input=map(Json.parse(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8)));
        Map<String,Object> strings=map(input.get("strings"));
        NativeEnvironment env=(NativeEnvironment)Proxy.newProxyInstance(NativeEnvironment.class.getClassLoader(),new Class<?>[]{NativeEnvironment.class},(o,m,a)->{
            if(!m.getName().equals("stringResource"))throw new AssertionError("unrecorded environment read "+m.getName());return required(strings,(String)a[0]);});
        NativeRouteSerializer serializer=new NativeRouteSerializer(env);
        for(Object object:list(input.get("routes"))){Map<String,Object> row=map(object),path=map(row.get("path"));NativePath original=(NativePath)proxy(NativePath.class,path);
            Map<String,Object> result=new LinkedHashMap<>();result.put("input_seq",row.get("seq"));result.put("path_json",NativeJson.write(serializer.y(original.getGroupSegmentCount()>0,original)));System.out.println(Json.write(result));}
    }
}
