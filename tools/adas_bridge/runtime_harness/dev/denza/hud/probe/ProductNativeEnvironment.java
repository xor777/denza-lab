package dev.denza.hud.probe;
import android.content.Context;import android.os.Looper;import dev.denza.adaslink.*;import dev.denza.adaslink.stocknative.*;import java.util.*;import java.util.function.*;
/** Test boundary for platform-only resources/properties/native SDK absence. */
final class ProductNativeEnvironment implements NativeEnvironment {
    static int realSpeedReads;
    interface ClientOperations {void startClientOrSubscribe(Map<Long,Long[]> v);void stopClient(Map<Long,Long[]> v);void unSubscribe(Map<Long,Long[]> v);}
    final BooleanSupplier naving;final Supplier<AdasGateSnapshot.Snapshot> gates;final DoubleSupplier speed;final ClientOperations clients;
    ProductNativeEnvironment(Context c,BooleanSupplier n,Supplier<AdasGateSnapshot.Snapshot> g,DoubleSupplier s,ClientOperations ops){naving=n;gates=g;speed=s;clients=ops;}
    boolean ready(){return true;}Map<String,Object> sourceStatus(){return Json.object();}void location(double lon,double lat){if(!Double.isFinite(lon)||!Double.isFinite(lat)||lon < -180||lon > 180||lat < -90||lat > 90)throw new IllegalArgumentException("actual location required");}
    public int adasType(){return 1;}public boolean tipIsland(){return true;}public boolean laneUi(){return false;}public boolean noaConfigured(){return Boolean.TRUE.equals(gates.get().dnpService);}
    public void startClientOrSubscribe(Map<Long,Long[]> v){clients.startClientOrSubscribe(v);}public void stopClient(Map<Long,Long[]> v){clients.stopClient(v);}public void unSubscribe(Map<Long,Long[]> v){clients.unSubscribe(v);}
    public void resetRouteSignals(){}public void setAvpSupport(int value){}public boolean someIpV2(){return false;}public boolean isNaving(){return naving.getAsBoolean();}public boolean isKD(){return false;}public boolean isMainThread(){return Looper.myLooper()==Looper.getMainLooper();}public double realSpeed(){realSpeedReads++;return speed.getAsDouble();}
    public boolean hasMapDataService(){return false;}public int getAdcodeByLonLat(double a,double b){return -1;}public int getTownAdcodeByLonLat(double a,double b){return -1;}public Integer getCityProvince(int a){return null;}public long queryAppointLanesInfo(int a,int b,int c){return -1;}
    public String cachedOpenCityJson(){return "";}public void writeCachedOpenCityJson(String s){}public List<Integer> avpMapIds(String s){return Collections.emptyList();}public double distanceBetweenPoints(double a,double b,double c,double d){return Coordinates.distance(a,b,c,d);}public NativeModels.Coord2DDouble lastLocation(){return null;}public void setLeaveTunnelDist(int d){}public void setEnterTunnelDist(int d){}public String stringResource(String name){return dev.denza.adaslink.yandexnative.NativeResources.get(name,false);}
}
