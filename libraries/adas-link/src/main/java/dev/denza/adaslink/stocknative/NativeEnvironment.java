package dev.denza.adaslink.stocknative;

import java.util.List;
import java.util.Set;

/** Original SDK getter inputs and public receiver/subscription operations.
 * No emitted packet or post-processing sender bean enters this API. */
public interface NativeEnvironment {
    int adasType();
    boolean tipIsland();
    boolean laneUi();
    /** Original NoaState.q() return; its service-global assignment is retained by StockLifecycle. */
    boolean noaConfigured();
    void startClientOrSubscribe(java.util.Map<Long,Long[]> values);
    void stopClient(java.util.Map<Long,Long[]> values);
    void unSubscribe(java.util.Map<Long,Long[]> values);
    /** Original incoming ADAS receiver-state f0.q().l(), separate from map sender state. */
    void resetRouteSignals();
    /** Original upstream AVP receiver/global texture support setter. */
    void setAvpSupport(int support);
    boolean someIpV2();
    boolean isNaving();
    boolean isKD();
    boolean isMainThread();
    double realSpeed();
    boolean hasMapDataService();
    int getAdcodeByLonLat(double lon,double lat);
    int getTownAdcodeByLonLat(double lon,double lat);
    /** null is the original getCityInfo returning null; otherwise CityItemInfo.belongedProvince. */
    Integer getCityProvince(int adcode);
    long queryAppointLanesInfo(int segmentIndex,int linkIndex,int count);
    String cachedOpenCityJson();
    void writeCachedOpenCityJson(String json);
    List<Integer> avpMapIds(String poiId);
    /** LocationUtil external native/platform helper; inputs and result remain in the getter transcript. */
    double distanceBetweenPoints(double lon1,double lat1,double lon2,double lat2);
    /** The current LocationController location, null when absent. */
    NativeModels.Coord2DDouble lastLocation();
    void setLeaveTunnelDist(int distance);
    void setEnterTunnelDist(int distance);
    /** Raw Android string-resource getter, identified by original resource name (not a derived description). */
    String stringResource(String resourceName);
}
