package dev.denza.adaslink.stocknative;

import java.math.BigInteger;
import java.util.List;

/** Virtual SDK getter boundary. A getter is read each time original code reads it; values may change. */
public interface NativePath {
    long getPathID();
    long getSegmentCount();
    Segment getSegmentInfo(long index);
    long getGroupSegmentCount();
    Group getGroupSegment(long index);
    List<Point> getAllTrafficLights();
    List<ViaRoad> getPathViaRoadInfo();
    boolean isValid();

    interface Segment {
        int getSegmentIndex();
        long getLinkCount();
        Link getLinkInfo(long index);
        long getLength();
        long getTravelTime();
        int getMainAction();
        int getAssistantAction();
        long getTrafficLightNum();
    }
    interface Link {
        BigInteger get64TopoID();
        int getRoadClass();
        String getRoadName();
        int getLength();
        int getLinkType();
        int getFormway();
        boolean hasTrafficLight();
        int getLaneNum();
        boolean hasMultiOut();
        int getMainAction();
        int getAssistantAction();
        long getAdcode();
        int getOwnership();
        boolean hasParallelRoad();
        int getRoadDirection();
        boolean hasMixFork();
        int getURID();
        List<IntPoint> getPoints();
    }
    final class IntPoint {
        public int lon, lat;
        public IntPoint(){}
        public IntPoint(int lon,int lat) { this.lon=lon;this.lat=lat; }
    }
    final class Point extends NativeModels.Coord2DDouble {
        public Point(){}
        public Point(double lon,double lat) { super(lon,lat); }
    }
    final class Group {
        public int startSegmentIndex, segmentCount;
        public String roadName;
        public Group(){}
        public Group(int begin,int count,String name) {startSegmentIndex=begin;segmentCount=count;roadName=name;}
    }
    final class ViaRoad {
        public String roadName;
        public short minLaneNum,maxLaneNum,minLimitSpeed,maxLimitSpeed,roadClass,reserved;
        public int aggregateLenth;
        public Point point;
    }
}
