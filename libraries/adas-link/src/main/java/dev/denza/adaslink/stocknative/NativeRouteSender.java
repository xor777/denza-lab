package dev.denza.adaslink.stocknative;

import dev.denza.adaslink.StockMessages;
import dev.denza.adaslink.StockRouteJson;
import java.util.*;
import static dev.denza.adaslink.stocknative.NativeModels.*;

/** MomentaUtil k.f/g/i and SDRouteUtil n.a, classic EHP/SOMEIPv1 fallback branch (ADAS types other than2/6). */
final class NativeRouteSender {
    private final StockRuntime clock;
    private final NativeEnvironment env;
    private final NativeSignalTransport transport;
    private final NativeRouteSerializer serializer;
    private long requestId=-1;
    private final Object routeLock=new Object();
    NativeRouteSender(StockRuntime clock,NativeEnvironment env,StockTransport transport){this.clock=clock;this.env=env;this.transport=new NativeSignalTransport(clock,transport);serializer=new NativeRouteSerializer(env);}
    private static void requireType(int type){if(type==2||type==6)throw new AssertionError("Native EHP sender does not implement original ADAS branch "+type);}
    private static void requireV1(boolean v2){if(v2)throw new AssertionError("Native sender domain requires original SOMEIPv1");}
    long e(){return requestId;}
    void h(){requestId=-1;}
    private EndPoiInfo b(RouteCarResultData r,int state){Poi poi=r.getToPOI();List<Integer>ids=new ArrayList<>();
        if(poi!=null && (!empty(poi.getId())||!empty(poi.getCreateID())))ids.addAll(env.avpMapIds(!empty(poi.getId())?poi.getId():poi.getCreateID()));
        return new EndPoiInfo(ids,state);}
    private static boolean empty(String s){return s==null||s.length()==0;}
    void f(RouteCarResultData r){synchronized(routeLock){
clock.processingEnter("k.h.e0.d.f.k.f", r);try{try{requestId=r.getRequestId();ArrayList<NativePath>paths=(ArrayList<NativePath>)r.getPathInfos();
        // Original builds its logging id list BEFORE the serializer. Native reads are retained.
        for(NativePath path:paths)path.getPathID();
        requireType(env.adasType());requireV1(env.someIpV2());
        MessageSend<ArrayList<NaviToEHPData>> message=serializer.f(paths);message.endPOIInfo=b(r,2);message.routeType=StockRouteJson.routeType(r.getRouteType(),false);
        chunks(NativeJson.write(message),message.data.size());
    }catch(Exception originalCaught){/* Original k.f catches; malformed host ledger errors extend Error. */}}finally{clock.processingExit("k.h.e0.d.f.k.f");}}}
    void g(NativePath path,RouteCarResultData r,boolean resend){
clock.processingEnter("k.h.e0.d.f.k.g", path, r, resend);try{try{
        requireType(env.adasType());
        path.getPathID(); // k.g's path-id label is an actual consumed native getter.
        requireV1(env.someIpV2());
        ArrayList<NativePath>paths=new ArrayList<>();paths.add(path);MessageSend<ArrayList<NaviToEHPData>> message=serializer.f(paths);
        message.endPOIInfo=b(r,1);message.routeType=StockRouteJson.routeType(r.getRouteType(),resend);chunks(NativeJson.write(message),message.data.size());
    }catch(Exception originalCaught){/* Selection in i still follows a failed serialization, as original. */}}finally{clock.processingExit("k.h.e0.d.f.k.g");}}
    boolean i(RouteCarResultData r,long pathId,boolean resend){synchronized(routeLock){
clock.processingEnter("k.h.e0.d.f.k.i", r, pathId, resend);try{for(NativePath path:r.getPathInfos())if(pathId==path.getPathID()){g(path,r,resend);select(pathId);return true;}return false;}finally{clock.processingExit("k.h.e0.d.f.k.i");}}}
    void select(long path){transport.fire(1126093183352833L,StockMessages.pathSelected(path));}
    private void chunks(String payload,int count){int length=payload.length();if(length+24<524288){send(new NaviDataDetail(clock.currentTimeMillis(),count,1,0,payload),count);return;}
        long wall=clock.currentTimeMillis();int parts=length/524264+(length%524264==0?0:1);
        for(int index=0;index<parts;index++){NaviDataDetail d=new NaviDataDetail();d.time=wall;d.pathCount=count;d.length=parts;d.currentIndex=index;
            d.payload=index<parts-1?payload.substring(index*524288,(index+1)*524288):payload.substring(index*524288);send(d,count);}}
    private void send(NaviDataDetail detail,int count){transport.fire(1126084593287170L,StockMessages.route(NativeJson.write(detail),count));}
}
