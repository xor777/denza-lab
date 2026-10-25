package dev.denza.adaslink.stocknative;

import dev.denza.adaslink.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Literal f0 encoder/gate behavior. ODD and route senders deliberately bypass this sleep gate. */
final class NativeWire {
    private final StockTransport transport;
    private final StockRuntime runtime;
    boolean sleeping;
    NativeWire(StockRuntime runtime,StockTransport transport){this.runtime=runtime;this.transport=transport;}
    private int fire(long topic,byte[] data){return transport.fire(topic,data);}
    static byte[] a(List<Integer> values){byte[]b=new byte[values.size()];for(int i=0;i<b.length;i++)b[i]=values.get(i).byteValue();return b;}
    void c(int start,int end,Object ignored){runtime.processingEnter("k.h.a0.c.c.a.f0.c",start,end,ignored);try{if(sleeping)return;fire(1125929972105222L,StockMessages.generalNavigation(start,end));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.c");}}
    boolean d(int distance,int count,List<Integer>back,List<Integer>front){runtime.processingEnter("k.h.a0.c.c.a.f0.d",distance,count,back,front);try{if(sleeping)return false;return fire(1268847189590022L,Pb.INTERSECTION_NOTIFY.encode((Object)Pb.INTERSECTION.encode(distance,a(front),a(back),count,null,null,null)))==0;}finally{runtime.processingExit("k.h.a0.c.c.a.f0.d");}}
    void e(int state){runtime.processingEnter("k.h.a0.c.c.a.f0.e",state);try{if(sleeping)return;fire(1125929972105217L,StockMessages.navigationStatus(state));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.e");}}
    void f(int icon,int main,int assist,int distance,int camera,int camDist){runtime.processingEnter("k.h.a0.c.c.a.f0.f",icon,main,assist,distance,camera,camDist);try{if(sleeping)return;fire(1268847189590027L,StockMessages.actionAndCamera(icon,main,assist,distance,camera,camDist));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.f");}}
    void g(IntersectionLaneInfoParam p){runtime.processingEnter("k.h.a0.c.c.a.f0.g",p);try{if(sleeping)return;d(p.getLaneDist(),p.getBackLane().size(),p.getBackLane(),p.getFrontLane());
        fire(1268847189590028L,StockMessages.nextLanes(a(p.getBackLane()),a(p.getFrontLane()),a(p.getExtendLane().isEmpty()?p.getBackExtenLane():p.getExtendLane()),a(p.getRecommendLane()),a(p.getBackLaneType()),a(p.getFrontLaneType()),p.getSegmentIndex(),p.getLinkIndex(),p.getTimestamp()));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.g");}}
    void h(int region,long country,int province,int city,int weather){runtime.processingEnter("k.h.a0.c.c.a.f0.h",region,country,province,city,weather);try{if(sleeping)return;fire(1268847189590025L,StockMessages.region(region,country,province,city,weather));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.h");}}
    void i(List<QueryAppointLanesInfoBean> items){runtime.processingEnter("k.h.a0.c.c.a.f0.i",items);try{if(!sleeping)fire(1268847189590030L,StockMessages.aheadLanes(NativeJson.write(items)));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.i");}}
    void j(int distance,int type,int cruise,double lon,double lat){runtime.processingEnter("k.h.a0.c.c.a.f0.j",distance,type,cruise,lon,lat);try{if(sleeping)return;fire(1268847189590021L,Pb.ROAD_FACILITIES_NOTIFY.encode((Object)Pb.ROAD_FACILITIES.encode(type,cruise,distance,lon,lat,null,null,null)));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.j");}}
    void k(List<TrafficEventBean> events){runtime.processingEnter("k.h.a0.c.c.a.f0.k",events);try{if(sleeping)return;int n=events.size();int[]types=new int[n],lanes=new int[n];double[]lon=new double[n],lat=new double[n];for(int i=0;i<n;i++){TrafficEventBean e=events.get(i);types[i]=e.getType();lanes[i]=e.getLaneId();lon[i]=e.getLon();lat[i]=e.getLat();}fire(1268847189590018L,StockMessages.incidents(types,lanes,lon,lat));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.k");}}
    void l(int exist,double lon,double lat,int state,long start,long end,int dir,int wait,int ignoredDist){runtime.processingEnter("k.h.a0.c.c.a.f0.l",exist,lon,lat,state,start,end,dir,wait,ignoredDist);try{if(sleeping)return;fire(1268847189590017L,StockMessages.trafficLight(exist,lon,lat,state,start,end,dir,wait));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.l");}}
    void m(int hasService,int distance,String name,int type,int next,int hasToll,int tollDistance,List<Integer>lanes){runtime.processingEnter("k.h.a0.c.c.a.f0.m",hasService,distance,name,type,next,hasToll,tollDistance,lanes);try{if(sleeping)return;
        Proto p=new Proto();p.uint32(1,hasService).uint32(2,distance).bytes(3,name.getBytes(StandardCharsets.UTF_8),false).uint32(4,type).uint32(5,next).uint32(6,hasToll).bytes(7,a(lanes),false).uint32(8,tollDistance);
        fire(1268847189590019L,new Proto().bytes(1,p.bytes(),true).bytes());}finally{runtime.processingExit("k.h.a0.c.c.a.f0.m");}}
    void n(ArrayList<MixForkInfoParam> items){runtime.processingEnter("k.h.a0.c.c.a.f0.n",items);try{if(!sleeping)fire(1268847189590029L,new Proto().bytes(1,NativeJson.write(items).getBytes(StandardCharsets.UTF_8),false).bytes());}finally{runtime.processingExit("k.h.a0.c.c.a.f0.n");}}
    void o(int flow,int history,int parallel,int mix,int solid,int distance,String voice){runtime.processingEnter("k.h.a0.c.c.a.f0.o",flow,history,parallel,mix,solid,distance,voice);try{if(sleeping)return;fire(1268847189590026L,StockMessages.speedIntersection(flow,history,parallel,mix,solid,distance,voice));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.o");}}
    void p(int ramp,int tidal){runtime.processingEnter("k.h.a0.c.c.a.f0.p",ramp,tidal);try{if(sleeping)return;fire(1268847189590023L,StockMessages.tidalLane(ramp,tidal));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.p");}}
    void q(int jamLength,int jamDist,int jamStatus,int pass,int construction,double cLon,double cLat,int roadSpeed,int eyeSpeed,int eyeDist,double startLon,double startLat,double endLon,double endLat,int intervalSpeed,boolean offCourse,int destDist,int destTime,int roadClass){runtime.processingEnter("k.h.a0.c.c.a.f0.q",jamLength,jamDist,jamStatus,pass,construction,cLon,cLat,roadSpeed,eyeSpeed,eyeDist,startLon,startLat,endLon,endLat,intervalSpeed,offCourse,destDist,destTime,roadClass);try{if(sleeping)return;fire(1125929972105219L,StockMessages.trafficInfo(jamLength,jamDist,jamStatus,pass,construction,cLon,cLat,roadSpeed,eyeSpeed,eyeDist,startLon,startLat,endLon,endLat,intervalSpeed,offCourse,destDist,destTime,roadClass));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.q");}}
    void r(TunnelParam p){runtime.processingEnter("k.h.a0.c.c.a.f0.r",p);try{if(sleeping)return;fire(1268847189590020L,StockMessages.tunnel(p.getTunnelDist(),p.getTunnelState(),p.getStartLon(),p.getStartLat(),p.getEndLon(),p.getEndLat()));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.r");}}
    void s(double lon,double lat,double roadLon,double roadLat,int real,int gps,int course,long path,int seg,int link,int segDist,int remain,int ownership){runtime.processingEnter("k.h.a0.c.c.a.f0.s",lon,lat,roadLon,roadLat,real,gps,course,path,seg,link,segDist,remain,ownership);try{if(sleeping)return;fire(1268847189590024L,StockMessages.vehicleLocation(lon,lat,roadLon,roadLat,real,gps,course,path,seg,link,segDist,remain,ownership));}finally{runtime.processingExit("k.h.a0.c.c.a.f0.s");}}
    void t(boolean noSleep){sleeping=!noSleep;}
    static final class Proto {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        void varint(long v){while((v&~127L)!=0){out.write((int)v&127|128);v>>>=7;}out.write((int)v);}
        Proto uint32(int field,int value){long v=value&0xffffffffL;if(v!=0){varint((long)field<<3);varint(v);}return this;}
        Proto bytes(int field,byte[]b,boolean force){if(force||b.length!=0){varint((long)field<<3|2);varint(b.length);out.write(b,0,b.length);}return this;}
        byte[]bytes(){return out.toByteArray();}
    }
}
