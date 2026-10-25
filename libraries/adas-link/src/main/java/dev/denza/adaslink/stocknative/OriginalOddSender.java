package dev.denza.adaslink.stocknative;

import dev.denza.adaslink.StockMessages;
import java.util.Set;
import static dev.denza.adaslink.stocknative.NativeModels.*;
import static dev.denza.adaslink.stocknative.StockRuntime.Queue.*;

/** Literal DnpOddDistanceController f, including direct egress outside f0's sleep gate. */
final class OriginalOddSender {
    private final StockRuntime clock;private final NativeEnvironment env;private final StockTransport transport;
    private final StockSender.Gates gates;private final NativeCityCache cities;private int previousCity,lastDistance;
    private StockTask stationary;
    OriginalOddSender(StockRuntime clock,NativeEnvironment env,StockTransport transport,StockSender.Gates gates,NativeCityCache cities){this.clock=clock;this.env=env;this.transport=transport;this.gates=gates;this.cities=cities;
        stationary=new StockTask("k.h.e0.c.f$a","stationary ODD",()->{h(lastDistance);clock.post(SOMEIP,this.stationary,null,1000,this.stationary);});}
    private void h(int value){gates.noOddRegionDistance=value;transport.fire(1125929972105220L,StockMessages.noOddRegionDistance(value));}
    private void distance(int value){h(value);lastDistance=value;}
    private boolean d(NativePath path,NativePath.Segment segment,int index,long count,int city,int ignoredPrevious,int length){
        long links=segment.getLinkCount();Set<Integer>open=cities.i();int i=index,acc=length;
        while(true){if((long)i>=links){if(segment.getSegmentIndex()+1>=count||path==null)return false;return d(path,path.getSegmentInfo(segment.getSegmentIndex()+1),0,count,city,ignoredPrevious,acc);}
            NativePath.Link link=segment.getLinkInfo(i);int next=link.getURID();if(next!=city){distance(!open.contains(city)?0:open.contains(next)?Integer.MAX_VALUE:acc);return true;}
            acc+=link.getLength();if(acc>2000){distance(Integer.MAX_VALUE);return true;}i++;}
    }
    void e(NaviInfo info,NativePath path){
clock.processingEnter("k.h.e0.c.f.e", info, path);try{if(info==null||path==null)return;if(gates.effectiveSwitch){Set<Integer>open=cities.i();if(open.size()==0){distance(Integer.MAX_VALUE);return;}
        long count=path.getSegmentCount();int remain=info.linkRemainDist,seg=info.curSegIdx,idx=info.curLinkIdx;NativePath.Segment segment=path.getSegmentInfo(seg);if(segment==null)return;NativePath.Link link=segment.getLinkInfo(idx);if(link==null)return;
        int city=link.getURID();segment.getSegmentIndex(); // Logger argument is a real native read.
        if((long)seg<count){if(d(path,segment,idx+1,count,city,previousCity,remain))return;
            if(previousCity==0)distance(open.contains(city)?Integer.MAX_VALUE:0);else distance(open.contains(previousCity)?Integer.MAX_VALUE:0);}}
    }finally{clock.processingExit("k.h.e0.c.f.e");}}
    void f(){previousCity=0;lastDistance=0;clock.removeCallbacks(SOMEIP,stationary);/* m.b clears route cache, not open-city set. */}
    void g(float speed){clock.removeCallbacks(SOMEIP,stationary);if(!(speed>0.0f)&&gates.service&&gates.effectiveSwitch)clock.post(SOMEIP,stationary,null,0,stationary);}
}
