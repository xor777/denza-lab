package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.List;

/**
 * What the stock serializer reads from Amap's {@code PathInfo}: segments (one per manoeuvre),
 * their links, each link's attributes and its {@code Coord2DInt32} points (1/3 600 000 degree).
 * Field for field the getters {@code l.b()}, {@code l.d()}, {@code l.a()} call; nothing more.
 */
public final class StockPath {
    public static final int ROAD_CLASS_NULL = -1;   // RoadClass.RoadClassNULL
    public static final int FORMWAY_NULL = -1;      // Formway.FormayNULL
    public static final int LINK_TYPE_NULL = -1;    // LinkType.LinkTypeNull
    public static final int LINK_TYPE_FERRY = 1;
    public static final int LINK_TYPE_TUNNEL = 2;
    /** The stock's own unknown ownership: {@code MmtSomeIpDataManager.P} turns MIN_VALUE into -1. */
    public static final int OWNERSHIP_UNKNOWN = -1;
    public static final int SCALE = 3600000;

    public static final class Link {
        public long topoId;
        public int roadClass = ROAD_CLASS_NULL;
        public String roadName = "";
        public int length;
        public int linkType = LINK_TYPE_NULL;
        public int formway = FORMWAY_NULL;
        public boolean hasTrafficLight;
        public int laneNum;
        public boolean hasMultiOut;
        public int mainAction;
        public int assistantAction;
        public long adcode;
        public int ownership = OWNERSHIP_UNKNOWN;
        public boolean hasParallel;
        public int direction;
        public boolean hasMixFork;
        /** Coord2DInt32 lon/lat, in order. */
        public int[] lon = new int[0], lat = new int[0];
        public int pointCount() { return lon.length; }
    }

    public static final class Segment {
        public final List<Link> links = new ArrayList<>();
        public long length;
        public long travelTime;
        public int mainAction;
        public int assistantAction;
        public long trafficLightNum;
    }

    public long pathId;
    public final List<Segment> segments = new ArrayList<>();
    /** {@code PathInfo.getAllTrafficLights()}: Coord2DDouble lon/lat pairs. */
    public final List<double[]> trafficLights = new ArrayList<>();

    public int linkCount() {
        int n = 0;
        for (Segment s : segments) n += s.links.size();
        return n;
    }

    /** The table that identifies a published route: every byte the serializer reads, except pathId. */
    public String contentKey() {
        StringBuilder b = new StringBuilder();
        for (Segment s : segments) {
            b.append('S').append(s.length).append(',').append(s.travelTime).append(',').append(s.mainAction).append(',')
                    .append(s.assistantAction).append(',').append(s.trafficLightNum).append(';');
            for (Link l : s.links) {
                b.append('L').append(l.topoId).append(',').append(l.roadClass).append(',').append(l.roadName).append('\u0001')
                        .append(l.length).append(',').append(l.linkType).append(',').append(l.formway).append(',')
                        .append(l.hasTrafficLight).append(',').append(l.laneNum).append(',').append(l.hasMultiOut).append(',')
                        .append(l.mainAction).append(',').append(l.assistantAction).append(',').append(l.adcode).append(',')
                        .append(l.ownership).append(',').append(l.hasParallel).append(',').append(l.direction).append(',')
                        .append(l.hasMixFork).append(':');
                for (int i = 0; i < l.lon.length; i++) b.append(l.lon[i]).append(' ').append(l.lat[i]).append(' ');
                b.append(';');
            }
        }
        for (double[] light : trafficLights) b.append('T').append(light[0]).append(' ').append(light[1]).append(';');
        return b.toString();
    }
}
