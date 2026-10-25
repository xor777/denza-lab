package dev.denza.adaslink.stocknative;

import java.util.ArrayList;
import java.util.List;

/** Original SDK callback structures at the PROCESSING boundary. Only source-consumed fields are modeled. */
public final class NativeModels {
    public static class Coord2DDouble { public double lon,lat; public Coord2DDouble(){} public Coord2DDouble(double lon,double lat){this.lon=lon;this.lat=lat;} }
    public static class Coord3DDouble extends Coord2DDouble {public double z;public Coord3DDouble(){}public Coord3DDouble(double lon,double lat){super(lon,lat);}}
    public static class LocInfo {
        public float speed,alt; public int isSimulate,sourType;
        public Coord3DDouble gpsPos; public ArrayList<LocMatchInfo> matchInfo;
    }
    public static class LocMatchInfo {
        public Coord3DDouble stPos; public float course,roadCourse,roadLen; public int roadClass,formway,linkType,ownership; public long linkId;
    }
    public static class TimeAndDist { public int dist,time; public TimeAndDist(){} public TimeAndDist(int dist,int time){this.dist=dist;this.time=time;} }
    public static class NaviInfoPanel { public TimeAndDist segmentRemain; }
    public static class NaviInfo {
        public long pathID; public TimeAndDist routeRemain;
        public int routeRemainLightCount,linkRemainDist,curSegIdx,curLinkIdx,curPointIdx,crossManeuverID,NaviInfoFlag,curLinkSpeed;
        public ArrayList<NaviInfoPanel> NaviInfoData;
    }
    public static class NaviCameraExt {
        public long cameraId; public Coord2DDouble coord2D;public Coord3DDouble coord3D;
        public int distance,segmentIndex,linkIndex,distanceToEnd,roadClass; public boolean isHidden;
        public ArrayList<NaviSubCameraExt> subCameras;
        public NaviCameraExt(){}
        public NaviCameraExt(long id,Coord2DDouble p,Coord3DDouble p3,int dist,int seg,int link,int remain,int cls,boolean hidden,ArrayList<NaviSubCameraExt> subs){cameraId=id;coord2D=p;coord3D=p3;distance=dist;segmentIndex=seg;linkIndex=link;distanceToEnd=remain;roadClass=cls;isHidden=hidden;subCameras=subs;}
    }
    public static class NaviSubCameraExt { public int subType,priority; public boolean isMatch; public ArrayList<Short> speed; }
    public static class LaneInfo {
        public ArrayList<Integer> backLane,frontLane,backExtenLane,extensionLane,optimalLane,backLaneType,frontLaneType;
        public int segmentIdx,linkIdx;
    }
    public static class TrafficEventInfo { public int type,layer,laneId;public Coord2DDouble coord2D; }
    public static class LightBarItem { public int status,length; }
    public static class NaviCongestionDetailInfo { public int scopeFlag,status; }
    public static class NaviFacility { public int type,remainDist;public String name; }
    public static class NaviRoadFacility { public int type,distance;public Coord2DDouble coord2D; }
    public static class MixForkInfo { public int dist,segmentIndex;public Coord2DDouble pos; }
    public static class WeatherInfo { public int weatherID; }
    public static class CruisePredictiveTunnelInfo { public int state,entranceDistance,exitDistance; }
    public static class SoundInfo {public String text;}
    public static class CruiseRoadSpeed {public int speed;}
    public interface RouteOption {int getRouteType();}
    public static class QueryLanesInfo {public int segmentIdx,linkIdx,num;public QueryLanesInfo(){}public QueryLanesInfo(int segment,int link,int count){segmentIdx=segment;linkIdx=link;num=count;}}
    public interface Poi {String getId();String getCreateID();}
    /** Methods preserve live result identity; paths are virtual and NOT snapshotted by the sender. */
    public interface RouteCarResultData {
        List<NativePath> getPathInfos();
        int getFocusIndex();
        long getRequestId();
        int getRouteType();
        Poi getToPOI();
    }
    private NativeModels(){}
}
