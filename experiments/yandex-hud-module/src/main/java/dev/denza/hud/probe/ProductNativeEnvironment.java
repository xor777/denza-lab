package dev.denza.hud.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Looper;
import dev.denza.adaslink.AdasGateSnapshot;
import dev.denza.adaslink.stocknative.NativeEnvironment;
import dev.denza.adaslink.stocknative.NativeModels;
import dev.denza.adaslink.yandexnative.NativeResources;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Product SDK/platform boundary. Missing Amap services stay absent, not road facts.
 * Driver owns fresh input admission, arm epochs and publication permission.
 */
final class ProductNativeEnvironment implements NativeEnvironment {
    interface ClientOperations {
        void startClientOrSubscribe(Map<Long,Long[]> values);
        void stopClient(Map<Long,Long[]> values);
        void unSubscribe(Map<Long,Long[]> values);
    }
    private final Context context;
    private final BooleanSupplier naving;
    private final Supplier<AdasGateSnapshot.Snapshot> gates;
    private final DoubleSupplier speed;
    private final ClientOperations clients;
    private final SharedPreferences cache;
    private final Integer autoType;
    private final String product;
    private volatile NativeModels.Coord2DDouble location;
    private volatile int avpSupport,leaveTunnelDistance,enterTunnelDistance;
    private volatile long routeSignalResetCount;

    ProductNativeEnvironment(Context context,BooleanSupplier naving,Supplier<AdasGateSnapshot.Snapshot> gates,
                             DoubleSupplier realSpeed,ClientOperations clients) {
        if(context==null||naving==null||gates==null||realSpeed==null||clients==null)
            throw new IllegalArgumentException("native environment dependencies");
        this.context=context.getApplicationContext();this.naving=naving;this.gates=gates;this.speed=realSpeed;this.clients=clients;
        cache=this.context.getSharedPreferences("denza-native-sender",Context.MODE_PRIVATE);
        autoType=readAutoType();product=readStockProduct(this.context);
    }
    /** Source n.a/b caches getAutoType()==210. Missing property never becomes a type. */
    private static Integer readAutoType() {
        try {
            Class<?> properties=Class.forName("android.os.SystemProperties");
            String value=(String)properties.getMethod("get",String.class).invoke(null,"persist.sys.AutoType");
            if(value==null||!value.matches("[0-9]+"))return null;
            return Integer.valueOf(value);
        } catch(ReflectiveOperationException|NumberFormatException|SecurityException unavailable) {return null;}
    }
    /** CommonUtil reads PRODUCT metadata; read the original package even while disabled. */
    private static String readStockProduct(Context context) {
        try {
            ApplicationInfo info=context.getPackageManager().getApplicationInfo("com.byd.launchermap",
                    PackageManager.GET_META_DATA|PackageManager.MATCH_DISABLED_COMPONENTS);
            Object value=info.metaData==null?null:info.metaData.get("PRODUCT");
            return value instanceof String?(String)value:null;
        } catch(PackageManager.NameNotFoundException unavailable) {return null;}
    }
    boolean ready(){return autoType!=null;}
    void location(double lon,double lat){
        if(!Double.isFinite(lon)||!Double.isFinite(lat)||lon < -180||lon > 180||lat < -90||lat > 90)
            throw new IllegalArgumentException("actual location required");
        location=new NativeModels.Coord2DDouble(lon,lat);
    }
    Map<String,Object> sourceStatus(){
        Map<String,Object> result=new LinkedHashMap<>();result.put("auto_type",autoType);result.put("stock_product",product);
        AdasGateSnapshot.Snapshot snapshot=gates.get();
        result.put("tip_island_raw",snapshot!=null&&snapshot.valid?snapshot.rawValues.get(AdasGateSnapshot.TIP_ISLAND_SUPPORT):null);
        result.put("geocoder","absent: no Amap MapDataService");result.put("lane_query","absent: no Amap GuideService; returns -1");
        result.put("city_cache","actual product private preferences; empty until supplied");
        result.put("vehicle_speed","autoservice FLOAT7 device1013 FID0x94400008, km/h; independent timestamped read");
        result.put("distance_provider","Android Location.distanceBetween; not established equivalent to Amap BizLayerUtil native calculation");
        result.put("route_signal_resets",routeSignalResetCount);result.put("avp_support",avpSupport);
        result.put("leave_tunnel_distance",leaveTunnelDistance);result.put("enter_tunnel_distance",enterTunnelDistance);
        return result;
    }
    public int adasType(){return 1;}
    public boolean tipIsland(){AdasGateSnapshot.Snapshot s=gates.get();return s!=null&&s.valid&&Integer.valueOf(2).equals(s.rawValues.get(AdasGateSnapshot.TIP_ISLAND_SUPPORT));}
    public boolean laneUi(){if(autoType==null)throw new IllegalStateException("actual AutoType unavailable");return autoType==210;}
    public boolean noaConfigured(){AdasGateSnapshot.Snapshot s=gates.get();return s!=null&&s.valid&&Boolean.TRUE.equals(s.dnpService);}
    public void startClientOrSubscribe(Map<Long,Long[]> values){clients.startClientOrSubscribe(values);}
    public void stopClient(Map<Long,Long[]> values){clients.stopClient(values);}
    public void unSubscribe(Map<Long,Long[]> values){clients.unSubscribe(values);}
    public void resetRouteSignals(){routeSignalResetCount++;}
    public void setAvpSupport(int support){avpSupport=support;}
    public boolean someIpV2(){return false;}
    public boolean isNaving(){return naving.getAsBoolean();}
    public boolean isKD(){return "kd".equals(product);}
    public boolean isMainThread(){return Looper.myLooper()==Looper.getMainLooper();}
    public double realSpeed(){double value=speed.getAsDouble();if(!Double.isFinite(value))throw new NativeEnvironment.DataUnavailable("vehicle speed unavailable");return value;}
    public boolean hasMapDataService(){return false;}
    public int getAdcodeByLonLat(double lon,double lat){return -1;}
    public int getTownAdcodeByLonLat(double lon,double lat){return -1;}
    public Integer getCityProvince(int adcode){return null;}
    public long queryAppointLanesInfo(int segmentIndex,int linkIndex,int count){return -1;}
    public String cachedOpenCityJson(){return cache.getString("open_city_json","");}
    public void writeCachedOpenCityJson(String json){cache.edit().putString("open_city_json",json).apply();}
    public List<Integer> avpMapIds(String poiId){return Collections.emptyList();}
    public double distanceBetweenPoints(double lon1,double lat1,double lon2,double lat2){float[] result=new float[1];Location.distanceBetween(lat1,lon1,lat2,lon2,result);return result[0];}
    public NativeModels.Coord2DDouble lastLocation(){NativeModels.Coord2DDouble value=location;return value==null?null:new NativeModels.Coord2DDouble(value.lon,value.lat);}
    public void setLeaveTunnelDist(int distance){leaveTunnelDistance=distance;}
    public void setEnterTunnelDist(int distance){enterTunnelDistance=distance;}
    public String stringResource(String name){return NativeResources.get(name,"en".equals(context.getResources().getConfiguration().getLocales().get(0).getLanguage()));}
}
