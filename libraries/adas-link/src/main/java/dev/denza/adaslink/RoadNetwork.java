package dev.denza.adaslink;

/**
 * Our OSM road network as the stock map exports it to ADAS, for {@link RoadMatcher}: fragments
 * (one stock road record each) with their geometry in planar metres of the store's frame, the
 * nodes they meet at and the attributes the build's producers wrote. {@link RoadStore} reads it
 * from the file {@code tools/adas_bridge/road_store.py} writes; the host oracle is
 * {@code tools/adas_bridge/roads.py}.
 */
public interface RoadNetwork {
    /** One road record: its end nodes, attributes as exported to ADAS, planar geometry. */
    final class Fragment {
        public final int index, from, to;
        public final int roadClass, formway, linkType, laneNum, direction, ownership, speedKmh;
        /** Legal limit along and against the fragment's point order, km/h; 0 none. */
        public final int legalForwardKmh, legalBackwardKmh;
        public final boolean forward, backward;
        public final String name;
        /** Planar metres of every point, and the length along it up to each point. */
        public final double[] x, y, cum;

        public Fragment(int index, int from, int to, int roadClass, int formway, int linkType, int laneNum, int direction,
                        int ownership, int speedKmh, int legalForwardKmh, int legalBackwardKmh, boolean forward, boolean backward, String name,
                        double[] x, double[] y, double[] cum) {
            this.index = index; this.from = from; this.to = to; this.roadClass = roadClass; this.formway = formway;
            this.linkType = linkType; this.laneNum = laneNum; this.direction = direction; this.ownership = ownership;
            this.speedKmh = speedKmh; this.legalForwardKmh = legalForwardKmh; this.legalBackwardKmh = legalBackwardKmh;
            this.forward = forward; this.backward = backward;
            this.name = name; this.x = x; this.y = y; this.cum = cum;
        }

        public double length() { return cum[cum.length - 1]; }
    }

    /** The frame the geometry is in; the matcher brings the Yandex route into it. */
    Coordinates.Frame frame();

    /** Planar metres of a target-frame position (degrees). */
    double planarX(double lon);
    double planarY(double lat);

    /** Planar metres of a node. */
    double nodeX(int node);
    double nodeY(int node);

    /** Fragments whose cells meet the square of {@code radius} metres around a planar point, ascending. */
    int[] near(double x, double y, double radius);

    Fragment fragment(int index);

    /** Fragment ends at a node: {@code adjacency(k)} for k in [start, end) is (fragment << 1 | 1 at its last point). */
    int adjacencyStart(int node);
    int adjacencyEnd(int node);
    int adjacency(int k);
}
