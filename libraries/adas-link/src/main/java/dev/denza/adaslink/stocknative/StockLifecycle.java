package dev.denza.adaslink.stocknative;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Original NoaCanSomeIpLink d and AutoPilotHub subscription-map decisions.
 * SDK client execution/reconnect and incoming ADAS receiver state are environment seams.
 */
final class StockLifecycle {
    private final NativeEnvironment env;
    private final StockSender.Gates gates;
    private final Map<Long,Long[]> offers=new NativeLongArrayMap(),subscriptions=new NativeLongArrayMap();
    private boolean hubInitialized,connectionJudgmentInitialized;
    private volatile boolean navigationActive;
    StockLifecycle(NativeEnvironment env,StockSender.Gates gates){this.env=env;this.gates=gates;}
    void activate(){navigationActive=true;}
    void observerCreated(){if(!hubInitialized){hubInitialized=true;initSubscriptions();}}
    void created(boolean tip,boolean lane){if(gates.service&&gates.effectiveSwitch&&!tip){if(lane)env.startClientOrSubscribe(offers());env.startClientOrSubscribe(subscriptions());}}
    void started(){env.laneUi();if(gates.service&&gates.effectiveSwitch&&env.laneUi()){long[] ids={3096284874276864L,3096276284211200L};env.startClientOrSubscribe(subset(offers(),ids));env.startClientOrSubscribe(subset(subscriptions(),ids));}}
    void stopped(){env.laneUi();if(gates.service&&gates.effectiveSwitch&&env.laneUi())env.stopClient(subset(subscriptions(),3096284874276864L,3096276284211200L));}
    void beforeDestroy(){env.laneUi();navigationActive=false;if(gates.service&&gates.effectiveSwitch){if(env.laneUi())env.stopClient(offers());else{Map<Long,Long[]> original=subscriptions(),values=new NativeLongArrayMap(original);values.remove(3096413725261824L);Long[] current=original.get(3096280579244032L);List<Long> retained=new ArrayList<>();if(current!=null)for(Long value:current)if(!Long.valueOf(0).equals(value))retained.add(value);values.put(3096280579244032L,retained.toArray(new Long[0]));env.unSubscribe(values);}}}
    void onSwitch(boolean enabled){boolean naving=env.isNaving(),tip=env.tipIsland();if(enabled)prepareClients(naving,tip);else env.stopClient(offers());}
    void supportTipIsland(boolean enabled){if(enabled){Map<Long,Long[]> values=new java.util.concurrent.ConcurrentHashMap<>();values.put(3096280579244032L,new Long[]{1125955742302210L,1125955742302211L});env.unSubscribe(values);}}
    void clientInitialized(boolean ready){boolean naving=env.isNaving(),enabled=gates.service&&gates.effectiveSwitch,tip=env.tipIsland();if(ready&&enabled)prepareClients(naving,tip);}
    void prepareClients(boolean naving,boolean tip){
        env.startClientOrSubscribe(offers());boolean lane=env.laneUi();Map<Long,Long[]> values;
        if(naving){values=new java.util.concurrent.ConcurrentHashMap<>(subscriptions());if(tip&&!lane)values.remove(3096280579244032L);}
        else if(!tip&&gates.memory){values=new java.util.concurrent.ConcurrentHashMap<>();values.put(3096280579244032L,new Long[]{0L});env.startClientOrSubscribe(values);return;}
        else if(lane)return;else values=subset(subscriptions(),3096413725261824L);
        env.startClientOrSubscribe(values);
    }
    boolean available(long topic){long service=service(topic);if(service==0||!navigationActive&&service!=3096413725261824L)return false;if(service==3096280579244032L&&env.tipIsland())return true;env.startClientOrSubscribe(subset(subscriptions(),service));return true;}
    boolean unavailable(long topic){long service=service(topic-1);if(service==0)return false;env.unSubscribe(subset(subscriptions(),service));return true;}
    private static long service(long topic){if(topic==281517927038977L)return 3096267694145536L;if(topic==281535107170305L)return 3096284874276864L;if(topic==281526517104641L)return 3096276284211200L;if(topic==281530812137473L)return 3096280579244032L;if(topic==281663958155265L)return 3096413725261824L;return 0;}
    void resetRouteSignals(){
        // f0.q() lazily constructs g0 once. Its initial support-tip GET controls
        // the external connection-judgment thread, not the map sender state.
        if(!connectionJudgmentInitialized){connectionJudgmentInitialized=true;env.tipIsland();}
        env.resetRouteSignals();
    }
    private void initSubscriptions(){
        gates.service=env.noaConfigured();
        if(!gates.service)return;
        if(!env.laneUi()){
            put(3096280579244032L,new Long[]{281530812137473L,281530812137474L},new Long[]{1125955742302213L,1125955742302210L,1125955742302211L});
            put(3096413725261824L,new Long[]{281663958155265L,281663958155266L},new Long[]{1126088888320001L,1126088888320002L,1126088888320004L});
            return;
        }
        put(3096267694145536L,new Long[]{281517927038977L,281517927038978L},new Long[]{1125942857203713L});
        put(3096284874276864L,new Long[]{281535107170305L,281535107170306L},new Long[]{1125960037335041L});
        put(3096276284211200L,new Long[]{281526517104641L,281526517104642L},new Long[]{1125951447269377L,1125960037335041L});
        put(3096280579244032L,new Long[]{281530812137473L,281530812137474L},new Long[]{1125955742302209L,1125955742302210L,1125955742302211L});
    }
    private void put(long id,Long[] available,Long[] events){offers.put(id,available);subscriptions.put(id,events);}
    private Map<Long,Long[]> offers(){if(offers.isEmpty())initSubscriptions();return offers;}
    private Map<Long,Long[]> subscriptions(){if(offers.isEmpty())initSubscriptions();return subscriptions;}
    private static Map<Long,Long[]> subset(Map<Long,Long[]> input,long...ids){Map<Long,Long[]> result=new NativeLongArrayMap();for(long id:ids){Long[] value=input.get(id);if(value!=null)result.put(id,value);}return result;}
}
