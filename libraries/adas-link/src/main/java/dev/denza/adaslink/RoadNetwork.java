package dev.denza.adaslink;

/**
 * A decoded road network for {@link RoadMatcher}: fragments
 * (one stock road record each) with their geometry in planar metres of the store's frame, the
 * nodes they meet at and the attributes known by the source. Yandex's online tiles supply
 * partial attributes; unknown values must not erase Navigator's route annotations.
 */
public interface RoadNetwork {
    /** Independently supplied stock fields; null retains the previous source's value.
     * A functional road-class approximation is identified separately in vector evidence. */
    final class KnownAttributes {
        public static final KnownAttributes NONE = new KnownAttributes(null, null, null, null, null, null);
        public final Integer roadClass, formway, linkType, laneNum, direction, ownership;

        public KnownAttributes(Integer roadClass, Integer formway, Integer linkType, Integer laneNum,
                               Integer direction, Integer ownership) {
            this.roadClass = nonnegative(roadClass); this.formway = nonnegative(formway);
            this.linkType = nonnegative(linkType); this.laneNum = nonnegative(laneNum);
            if (direction != null && direction != 0 && direction != 1)
                throw new IllegalArgumentException("stock direction must be 0 or 1; unknown is null");
            this.direction = direction; this.ownership = nonnegative(ownership);
        }

        private static Integer nonnegative(Integer value) {
            if (value != null && value < 0) throw new IllegalArgumentException("unknown road attribute must be null");
            return value;
        }

        private KnownAttributes overlay(KnownAttributes newer) {
            return new KnownAttributes(newer.roadClass == null ? roadClass : newer.roadClass,
                    newer.formway == null ? formway : newer.formway, newer.linkType == null ? linkType : newer.linkType,
                    newer.laneNum == null ? laneNum : newer.laneNum, newer.direction == null ? direction : newer.direction,
                    newer.ownership == null ? ownership : newer.ownership);
        }
    }

    /** One road record: its end nodes, attributes as exported to ADAS, planar geometry. */
    final class Fragment {
        public final int index, from, to;
        public final int roadClass, formway, linkType, laneNum, direction, ownership, speedKmh;
        /** Legal limit along and against the fragment's point order, km/h; 0 none. */
        public final int legalForwardKmh, legalBackwardKmh;
        public final boolean forward, backward;
        public final String name;
        /** False when the source has only a subset of the stock road attributes. */
        public final boolean completeAttributes;
        /** Field presence is independent of completeAttributes (which also gates historical speed). */
        public final KnownAttributes knownAttributes;
        /** Planar metres of every point, and the length along it up to each point. */
        public final double[] x, y, cum;

        public Fragment(int index, int from, int to, int roadClass, int formway, int linkType, int laneNum, int direction,
                        int ownership, int speedKmh, int legalForwardKmh, int legalBackwardKmh, boolean forward, boolean backward, String name,
                        double[] x, double[] y, double[] cum) {
            this(index, from, to, roadClass, formway, linkType, laneNum, direction, ownership, speedKmh,
                    legalForwardKmh, legalBackwardKmh, forward, backward, name, x, y, cum, true,
                    new KnownAttributes(known(roadClass), known(formway), known(linkType), known(laneNum),
                            direction == 0 || direction == 1 ? direction : null, known(ownership)));
        }

        private Fragment(int index, int from, int to, int roadClass, int formway, int linkType, int laneNum, int direction,
                         int ownership, int speedKmh, int legalForwardKmh, int legalBackwardKmh, boolean forward,
                         boolean backward, String name, double[] x, double[] y, double[] cum, boolean completeAttributes,
                         KnownAttributes knownAttributes) {
            this.index = index; this.from = from; this.to = to; this.roadClass = roadClass; this.formway = formway;
            this.linkType = linkType; this.laneNum = laneNum; this.direction = direction; this.ownership = ownership;
            this.speedKmh = speedKmh; this.legalForwardKmh = legalForwardKmh; this.legalBackwardKmh = legalBackwardKmh;
            this.forward = forward; this.backward = backward;
            this.name = name; this.x = x; this.y = y; this.cum = cum;
            this.completeAttributes = completeAttributes;
            this.knownAttributes = knownAttributes;
        }

        public static Fragment partial(int index, int from, int to, int legalForwardKmh, int legalBackwardKmh,
                                       boolean forward, boolean backward, String name, double[] x, double[] y, double[] cum) {
            return new Fragment(index, from, to, -1, -1, -1, 0, -1, -1, 0,
                    legalForwardKmh, legalBackwardKmh, forward, backward, name, x, y, cum, false, KnownAttributes.NONE);
        }

        /** An immutable metadata overlay; absent fields retain existing knowledge and topology is unchanged. */
        public Fragment withKnownAttributes(KnownAttributes attributes) {
            if (attributes == null) throw new IllegalArgumentException("known attributes required");
            KnownAttributes merged = knownAttributes.overlay(attributes);
            return new Fragment(index, from, to, value(merged.roadClass, roadClass), value(merged.formway, formway),
                    value(merged.linkType, linkType), value(merged.laneNum, laneNum), value(merged.direction, direction),
                    value(merged.ownership, ownership), speedKmh, legalForwardKmh, legalBackwardKmh, forward, backward,
                    name, x, y, cum, completeAttributes, merged);
        }

        private static Integer known(int value) { return value < 0 ? null : value; }
        private static int value(Integer known, int fallback) { return known == null ? fallback : known; }

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
