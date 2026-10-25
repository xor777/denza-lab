package dev.denza.adaslink.stocknative;

import static dev.denza.adaslink.stocknative.NativeModels.*;
import static dev.denza.adaslink.stocknative.StockRuntime.Queue.*;
import java.util.List;

/** Literal DriveRouteManager g. No new ACK lease, route-id allocation or safety policy belongs here. */
public final class OriginalRoutePolicy {
    private final StockRuntime clock;private final NativeEnvironment env;private final NativeRouteSender sender;
    private final OriginalOddSender odd;private final StockSender.Gates gates;
    private volatile long current=-1,selected=-1;
    private RouteCarResultData route;
    private boolean listenerRegistered=true;
    private final StockLifecycle lifecycle;
    /** Read-only producer state for the product overlay; never an ACK generator. */
    public long selectedPathId(){return selected;}
    boolean listenerRegistered(){return listenerRegistered;}
    OriginalRoutePolicy(StockRuntime clock,NativeEnvironment env,NativeRouteSender sender,OriginalOddSender odd,StockSender.Gates gates,StockLifecycle lifecycle){this.lifecycle=lifecycle;this.clock=clock;this.env=env;this.sender=sender;this.odd=odd;this.gates=gates;}
    private void post(String suffix,String purpose,Object token,long delay,Runnable body){StockTask task=new StockTask("k.h.e0.c."+suffix,purpose,body);clock.post(SOMEIP,task,token,delay,task);}
    public void C(long path){
clock.processingEnter("k.h.e0.c.g.C", path);try{if(route!=null){current=path;clock.removeToken(SOMEIP,"checkPathByUpdateNavi");env.setAvpSupport(0);sender.i(route,path,true);}}finally{clock.processingExit("k.h.e0.c.g.C");}}
    public void E(){
clock.processingEnter("k.h.e0.c.g.E");try{RouteCarResultData r=route;if(r!=null){List<NativePath> paths=r.getPathInfos();if(route.getFocusIndex()<paths.size()){NativePath path=paths.get(route.getFocusIndex());current=path.getPathID();clock.removeToken(SOMEIP,"checkPathByUpdateNavi");env.setAvpSupport(0);sender.i(route,path.getPathID(),false);}}}finally{clock.processingExit("k.h.e0.c.g.E");}}
    public void G(RouteCarResultData r){
clock.processingEnter("k.h.e0.c.g.G", r);try{if(r==null)return;List<NativePath>paths=r.getPathInfos();if(r.getFocusIndex()<paths.size()){
        NativePath path=paths.get(r.getFocusIndex());path.getPathID();r.getRouteType(); // original Logger getters
        if(current!=path.getPathID()){clock.removeToken(SOMEIP,"checkPathByUpdateNavi");current=path.getPathID();
            if(sender.e()!=r.getRequestId()){env.setAvpSupport(0);}else if(!env.someIpV2()){sender.select(path.getPathID());sender.h();return;}sender.i(r,path.getPathID(),false);}}}finally{clock.processingExit("k.h.e0.c.g.G");}}
    public void I(long path){
clock.processingEnter("k.h.e0.c.g.I", path);try{if(route!=null){current=path;clock.removeToken(SOMEIP,"checkPathByUpdateNavi");env.setAvpSupport(0);sender.i(route,path,true);}}finally{clock.processingExit("k.h.e0.c.g.I");}}
    public void K(long path,NativePath nativePath){
clock.processingEnter("k.h.e0.c.g.K", path, nativePath);try{long request=sender.e();if(current!=-1&&route!=null&&request!=route.getRequestId()){current=path;route.getRequestId();}if(current==selected||selected==-1)return;nativePath.getPathID();}finally{clock.processingExit("k.h.e0.c.g.K");}}
    public void g(long path){selected=path;if(current!=path)onSelectMainPathStatus(current,1);}
    public void w(){onSelectMainPathStatus(current,1);}
    public void l(NaviInfo info,NativePath path){odd.e(info,path);long id=path.getPathID();if(current==id)return;clock.removeToken(SOMEIP,"checkPathByUpdateNavi");post("c","compare path after progress","checkPathByUpdateNavi",3000,()->K(id,path));}
    private boolean enabled(){return (gates.service&&gates.effectiveSwitch)||env.adasType()==6;}
    public void onChangeNaviPath(long previous,long path){if(enabled())post("e","changed path",null,0,()->C(path));}
    public void onDnpSwitchState(boolean enabled,boolean ignored1,boolean ignored2){if(enabled){clock.removeToken(SOMEIP,"sendPathByNoaSwitch");post("b","switch route resend","sendPathByNoaSwitch",300,this::E);}else{current=-1;clock.removeToken(SOMEIP,"checkPathByUpdateNavi");sender.h();}lifecycle.resetRouteSignals();}
    public void onNavStop(){listenerRegistered=false;current=-1;selected=-1;route=null;odd.f();clock.removeToken(SOMEIP,"sendPathByNoaSwitch");clock.removeToken(SOMEIP,"checkPathByUpdateNavi");sender.h();lifecycle.resetRouteSignals();}
    public void onSelectMainPathStatus(long path,int result){if(enabled()&&result==1)post("a","resend selected path",null,0,()->I(path));}
    public void onSpeedChange(float speed){odd.g(speed);}
    public void t(RouteCarResultData value){route=value;if(enabled())post("d","route data",null,0,()->G(value));}
}
