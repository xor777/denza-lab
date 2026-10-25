package dev.denza.adaslink;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A Yandex route as the Amap {@code PathInfo} the stock serializer expects. One stock segment per
 * Yandex section (one manoeuvre each); its links are cut at every known traffic light (the light
 * ends its link, as Amap's signal node does), every change of the per-edge legal limit and every
 * tunnel or ferry boundary, never at jams (those are TrafficInfo, not topology). Link lengths are
 * whole metres summing exactly to the segment's (largest remainder). Attributes Yandex does not
 * give carry the stock's own NULL values (roadclass, formway and linktype -1, ownership -1, actions
 * 0) or Amap's zero; nothing is guessed. Link IDs hash the link's own grid points, so the same piece
 * of road has the same ID in every route. The algorithm is mirrored in tools/adas_bridge/stock.py.
 */
public final class RouteConverter {
    /** Cuts closer than this to the previous one are merged (a light keeps its place over others). */
    static final double MIN_LINK_M = 1.0;

    /** A converted route: the path (pathId still 0) and the geometry needed to place progress on it. */
    public static final class Converted {
        public final String routeId;
        public final StockPath path;
        public final double totalM;
        /** Per link in path order: segment, index in segment, exact begin/end metres from route start. */
        public final int[] linkSegment, linkInSegment;
        public final double[] linkBeginM, linkEndM, linkBeginScalar, linkEndScalar;
        public final double[] segmentBeginM, segmentEndM;
        /** Distance of every known light from route start, ascending; null when Yandex reported none. */
        public final double[] lightM;
        public final double[] cumulative;
        /** Tunnels as [beginM, endM] and their entry/exit points in the target frame grid. */
        public final List<double[]> tunnelM;
        public final List<double[]> tunnelEnds;
        /** Speed cameras ahead: [positionM, limit km/h]. */
        public final List<double[]> speedCameras;
        /** Per segment: its Yandex section's free-flow speed, km/h (NaN unknown): the factory's typical link speed. */
        public final double[] segmentFreeFlowKmh;
        /** Per link: the graph road’s historical speed, km/h; NaN when not supplied. */
        public double[] linkSpeedKmh;
        /** Per link: the matched graph road's legal limit in the direction travelled, km/h; 0 none or unmatched. */
        public int[] linkLegalKmh;
        /** The route on the road graph, or null when converted without a road network or outside it. */
        public RoadMatcher.Match roadMatch;
        public final String contentKey;
        /** Actual point-scoped lane signs, independent of continuous link attributes. */
        public LaneGuidance laneGuidance;
        public RoadFacilities.View roadFacilities;
        /** The variant's link overrides on this table: switch -> {value, links set, links that kept Yandex's}. Empty: none. */
        public Map<String, Object> variantApplied = Json.object();
        /** The variant the table was converted under. */
        public ExperimentVariant variant = ExperimentVariant.NONE;

        Converted(String routeId, StockPath path, double totalM, int[] linkSegment, int[] linkInSegment, double[] linkBeginM, double[] linkEndM,
                  double[] linkBeginScalar, double[] linkEndScalar, double[] segmentBeginM, double[] segmentEndM, double[] lightM,
                  double[] cumulative, List<double[]> tunnelM, List<double[]> tunnelEnds, List<double[]> speedCameras,
                  double[] segmentFreeFlowKmh) {
            this.routeId = routeId; this.path = path; this.totalM = totalM; this.linkSegment = linkSegment; this.linkInSegment = linkInSegment;
            this.linkBeginM = linkBeginM; this.linkEndM = linkEndM; this.linkBeginScalar = linkBeginScalar; this.linkEndScalar = linkEndScalar;
            this.segmentBeginM = segmentBeginM; this.segmentEndM = segmentEndM; this.lightM = lightM; this.cumulative = cumulative;
            this.tunnelM = tunnelM; this.tunnelEnds = tunnelEnds; this.speedCameras = speedCameras;
            this.segmentFreeFlowKmh = segmentFreeFlowKmh;
            this.contentKey = path.contentKey();
        }

        /** Metres from route start of a polyline position. */
        public double metres(double scalar) { return RouteConverter.metres(cumulative, scalar); }

        /** Amap's NaviInfo for a position: {segIdx, linkIdx, globalLink, linkRemain, segRemain, routeRemain, remainLights}. */
        public int[] naviInfo(double passedM) {
            int link = linkIndex(passedM);
            int seg = linkSegment[link];
            StockPath.Segment segment = path.segments.get(seg);
            StockPath.Link l = segment.links.get(linkInSegment[link]);
            int linkRemain = clamp(round(linkEndM[link] - passedM), 0, l.length);
            int segRemain = clamp(round(segmentEndM[seg] - passedM), 0, (int) segment.length);
            int routeRemain = Math.max(0, round(totalM - passedM));
            int lights = 0;
            if (lightM != null) for (double d : lightM) if (d > passedM) lights++;
            return new int[]{seg, linkInSegment[link], link, linkRemain, segRemain, routeRemain, lights};
        }

        public int linkIndex(double passedM) {
            int lo = 0, hi = linkBeginM.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (linkBeginM[mid] <= passedM) lo = mid; else hi = mid - 1;
            }
            return lo;
        }
    }

    /** Yandex Action to Amap {MainAction, AssistantAction} for the manoeuvre at a segment's end. */
    public static int[] actions(String yandex) {
        if (yandex == null) return new int[]{0, 0};
        switch (yandex) {
            case "STRAIGHT": return new int[]{8, 0};          // MainActionContinue
            case "LEFT": return new int[]{1, 0};
            case "RIGHT": return new int[]{2, 0};
            case "SLIGHT_LEFT": case "FORK_LEFT": case "EXIT_LEFT": return new int[]{3, 0};
            case "SLIGHT_RIGHT": case "FORK_RIGHT": case "EXIT_RIGHT": return new int[]{4, 0};
            case "HARD_LEFT": return new int[]{5, 0};
            case "HARD_RIGHT": return new int[]{6, 0};
            case "UTURN_LEFT": case "UTURN_RIGHT": return new int[]{7, 0};
            case "ENTER_ROUNDABOUT": return new int[]{11, 0};
            case "LEAVE_ROUNDABOUT": return new int[]{12, 0};
            case "BOARD_FERRY": return new int[]{0, 19};       // AssiActionEntryFerry
            case "LEAVE_FERRY": return new int[]{0, 20};       // AssiActionLeftferry
            case "WAYPOINT": return new int[]{0, 35};          // AssiActionArriveWay
            case "FINISH": return new int[]{0, 36};            // AssiActionArriveDestination
            default: return new int[]{0, 0};
        }
    }

    /** Yandex Action to Amap ManeuverIconID (NaviInfo.crossManeuverID). */
    public static int icon(String yandex) {
        if (yandex == null) return 0;
        switch (yandex) {
            case "STRAIGHT": return 9;
            case "LEFT": return 2;
            case "RIGHT": return 3;
            case "SLIGHT_LEFT": case "FORK_LEFT": case "EXIT_LEFT": return 4;
            case "SLIGHT_RIGHT": case "FORK_RIGHT": case "EXIT_RIGHT": return 5;
            case "HARD_LEFT": return 6;
            case "HARD_RIGHT": return 7;
            case "UTURN_LEFT": return 8;
            case "UTURN_RIGHT": return 19;
            case "ENTER_ROUNDABOUT": return 11;
            case "LEAVE_ROUNDABOUT": return 12;
            case "BOARD_FERRY": return 46;
            case "WAYPOINT": return 10;
            case "FINISH": return 15;
            default: return 0;
        }
    }

    public static Converted convert(YandexRoute route, Coordinates.Frame frame) {
        return convert(route, frame, null);
    }

    /**
     * As {@link #convert(YandexRoute, Coordinates.Frame)}; where the route matches {@code roads}
     * ({@link RoadMatcher}), links are also cut at the network's junction nodes and attribute
     * changes and carry its attributes. {@code roads} null: Yandex data only.
     */
    public static Converted convert(YandexRoute route, Coordinates.Frame frame, RoadNetwork roads) {
        return convert(route, frame, roads, ExperimentVariant.NONE);
    }

    /**
     * As {@link #convert(YandexRoute, Coordinates.Frame, RoadNetwork)} under an experiment variant:
     * its link overrides fill only the attributes Yandex left unknown ({@link #applyVariant}).
     */
    public static Converted convert(YandexRoute route, Coordinates.Frame frame, RoadNetwork roads, ExperimentVariant variant) {
        if (variant == null) throw new IllegalArgumentException("variant");
        if (route.additionalAttributes != null && !route.additionalAttributes.matches(route))
            throw new IllegalArgumentException("additional attributes belong to another route geometry");
        int edges = route.edges();
        double[] cumulative = new double[edges + 1];
        for (int i = 0; i < edges; i++)
            cumulative[i + 1] = cumulative[i] + Coordinates.distance(route.lon[i], route.lat[i], route.lon[i + 1], route.lat[i + 1]);
        double total = cumulative[edges];
        if (!(total > 0)) throw new IllegalArgumentException("route geometry has zero length");
        List<YandexRoute.Section> sections = new ArrayList<>(route.sections);
        if (sections.isEmpty()) sections.add(new YandexRoute.Section(0, edges, null, null, Double.NaN));
        double previous = 0;
        for (YandexRoute.Section s : sections) {
            if (Math.abs(s.begin - previous) > 1e-9 || !(s.end > s.begin)) throw new IllegalArgumentException("sections not contiguous");
            previous = s.end;
        }
        if (Math.abs(previous - edges) > 1e-9) throw new IllegalArgumentException("sections do not reach the route end");

        // Internal cut candidates with their kind: 1 a light, 0 anything else.
        List<double[]> candidates = new ArrayList<>();
        double[] lights = route.trafficLights == null ? new double[0] : route.trafficLights.clone();
        Arrays.sort(lights);
        for (double s : lights) if (s > 0 && s < edges) candidates.add(new double[]{s, 1});
        if (route.speedLimits != null)
            for (int i = 1; i < edges; i++) if (!Objects.equals(route.speedLimits[i], route.speedLimits[i - 1])) candidates.add(new double[]{i, 0});
        for (List<double[]> ranges : Arrays.asList(route.tunnels, route.ferries, route.highways)) {
            if (ranges == null) continue;
            for (double[] r : ranges) for (double s : r) if (s > 0 && s < edges) candidates.add(new double[]{s, 0});
        }
        // Junctions Yandex marks: a lane sign (kept like a light, so a highway approach carries its
        // lane count), a direction sign, a restricted turn or entry. Amap's links run junction to
        // junction; Yandex names no other junction.
        if (route.laneSigns != null) for (double[] sign : route.laneSigns)
            if (sign[0] > 0 && sign[0] < edges) candidates.add(new double[]{sign[0], 1});
        if (route.junctions != null) for (double s : route.junctions) if (s > 0 && s < edges) candidates.add(new double[]{s, 0});
        // Junction nodes and attribute changes of the graph roads the route matched (Amap's road records).
        RoadMatcher.Match match = roads == null ? null : RoadMatcher.match(route, cumulative, roads);
        if (match != null) for (double s : match.cuts) if (s > 0 && s < edges) candidates.add(new double[]{s, 0});
        if (route.additionalAttributes != null) for (double s : route.additionalAttributes.cuts()) {
            if (!Double.isFinite(s) || s < 0 || s > edges) throw new IllegalArgumentException("attribute cut outside route");
            if (s > 0 && s < edges) candidates.add(new double[]{s, 0});
        }
        candidates.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(b[1], a[1]));

        StockPath path = new StockPath();
        List<Integer> linkSegment = new ArrayList<>(), linkInSegment = new ArrayList<>();
        List<Double> linkBegin = new ArrayList<>(), linkEnd = new ArrayList<>(), linkBeginS = new ArrayList<>(), linkEndS = new ArrayList<>();
        double[] segBegin = new double[sections.size()], segEnd = new double[sections.size()];
        for (int si = 0; si < sections.size(); si++) {
            YandexRoute.Section section = sections.get(si);
            List<double[]> kept = new ArrayList<>();
            kept.add(new double[]{section.begin, 2});
            for (double[] c : candidates) {
                if (!(c[0] > section.begin && c[0] < section.end)) continue;
                double[] last = kept.get(kept.size() - 1);
                if (c[0] == last[0]) continue;
                if (metres(cumulative, c[0]) - metres(cumulative, last[0]) < MIN_LINK_M) {
                    if (c[1] == 1 && last[1] == 0) kept.set(kept.size() - 1, c);
                    continue;
                }
                kept.add(c);
            }
            if (kept.size() > 1 && metres(cumulative, section.end) - metres(cumulative, kept.get(kept.size() - 1)[0]) < MIN_LINK_M)
                kept.remove(kept.size() - 1);
            kept.add(new double[]{section.end, 2});

            StockPath.Segment segment = new StockPath.Segment();
            YandexRoute.Section next = si + 1 < sections.size() ? sections.get(si + 1) : null;
            int[] action = next == null ? new int[]{0, 36} : actions(next.action);
            segment.mainAction = action[0];
            segment.assistantAction = action[1];
            segment.travelTime = Double.isNaN(section.timeS) ? 0 : round(section.timeS);
            double[] exact = new double[kept.size() - 1];
            for (int k = 0; k + 1 < kept.size(); k++) {
                double a = kept.get(k)[0], c = kept.get(k + 1)[0];
                StockPath.Link link = new StockPath.Link();
                link.roadName = section.toponym == null ? "" : section.toponym;
                points(route, frame, a, c, link);
                link.topoId = topoId(link);
                double middle = (a + c) / 2;
                // Yandex lists tunnels and ferries in full; any other link is the SDK's common link (0).
                // Bridges and elevated roads are not reported apart, so they are common here too.
                if (inside(route.tunnels, middle)) link.linkType = StockPath.LINK_TYPE_TUNNEL;
                else if (inside(route.ferries, middle)) link.linkType = StockPath.LINK_TYPE_FERRY;
                else link.linkType = StockPath.LINK_TYPE_COMMON;
                exact[k] = metres(cumulative, c) - metres(cumulative, a);
                segment.links.add(link);
                linkSegment.add(si);
                linkInSegment.add(k);
                linkBegin.add(metres(cumulative, a));
                linkEnd.add(metres(cumulative, c));
                linkBeginS.add(a);
                linkEndS.add(c);
            }
            // Amap's link length is whole metres cut down (bench: stock len = haversine - 0.47 m
            // median over 1 177 factory links); the segment's is their sum, as the stock route.
            int[] lengths = new int[exact.length];
            for (int k = 0; k < exact.length; k++) lengths[k] = (int) Math.floor(exact[k]);
            long sum = 0;
            for (int k = 0; k < lengths.length; k++) { segment.links.get(k).length = lengths[k]; sum += lengths[k]; }
            segment.length = sum;
            segBegin[si] = metres(cumulative, section.begin);
            segEnd[si] = metres(cumulative, section.end);
            path.segments.add(segment);
        }
        // A light ends the link it stands at the end of (Amap's signal node). One within MIN_LINK_M of a
        // link boundary belongs to the link ending there; the route start has no incoming link.
        List<StockPath.Link> all = new ArrayList<>();
        for (StockPath.Segment s : path.segments) all.addAll(s.links);
        for (double s : lights) {
            double d = metres(cumulative, s);
            int owner = -1;
            double best = Double.MAX_VALUE;
            for (int j = 0; j < all.size(); j++) {
                double gap = Math.abs(linkEnd.get(j) - d);
                if (gap < best) { best = gap; owner = j; }
            }
            if (best > MIN_LINK_M) {
                owner = -1;
                for (int j = 0; j < all.size(); j++) if (s > linkBeginS.get(j) && s <= linkEndS.get(j)) owner = j;
            }
            if (d < MIN_LINK_M) owner = -1;
            if (owner < 0) continue;
            all.get(owner).hasTrafficLight = true;
            path.segments.get(linkSegment.get(owner)).trafficLightNum++;
        }
        int[] sourced = new int[linkBeginS.size()];
        double[] linkSpeed = attributes(route, sections, path, linkBeginS, linkEndS, linkEnd, cumulative, match, sourced);
        Map<String, Object> applied = applyVariant(path, sourced, variant);
        groups(sections, path);
        double[] segmentFree = new double[sections.size()];
        for (int i = 0; i < segmentFree.length; i++) segmentFree[i] = sections.get(i).freeFlowKmh;
        double[] lightM = route.trafficLights == null ? null : new double[lights.length];
        for (int i = 0; i < lights.length; i++) {
            double[] p = sdk(route, frame, lights[i]);
            path.trafficLights.add(new double[]{Coordinates.grid(p[0]), Coordinates.grid(p[1])});
            if (lightM != null) lightM[i] = metres(cumulative, lights[i]);
        }
        List<double[]> tunnelM = new ArrayList<>(), tunnelEnds = new ArrayList<>();
        if (route.tunnels != null) for (double[] r : route.tunnels) {
            tunnelM.add(new double[]{metres(cumulative, r[0]), metres(cumulative, r[1])});
            double[] b = sdk(route, frame, r[0]), e = sdk(route, frame, r[1]);
            tunnelEnds.add(new double[]{Coordinates.grid(b[0]), Coordinates.grid(b[1]), Coordinates.grid(e[0]), Coordinates.grid(e[1])});
        }
        List<double[]> cameras = new ArrayList<>();
        for (YandexRoute.Camera c : route.cameras)
            if (c.tags.contains("SPEED_CONTROL") && Double.isFinite(c.speedLimitMps) && c.speedLimitMps > 0)
                cameras.add(new double[]{metres(cumulative, c.position), round(c.speedLimitMps * 3.6)});
        cameras.sort((a, b) -> Double.compare(a[0], b[0]));
        Converted converted = new Converted(route.routeId, path, total, ints(linkSegment), ints(linkInSegment), doubles(linkBegin), doubles(linkEnd),
                doubles(linkBeginS), doubles(linkEndS), segBegin, segEnd, lightM, cumulative, tunnelM, tunnelEnds, cameras, segmentFree);
        converted.linkSpeedKmh = linkSpeed;
        converted.linkLegalKmh = new int[linkSpeed.length];
        if (match != null)
            for (int k = 0; k < linkSpeed.length; k++)
                converted.linkLegalKmh[k] = match.legalKmhAt((linkBeginS.get(k) + linkEndS.get(k)) / 2);
        converted.roadMatch = match;
        converted.variantApplied = applied;
        converted.variant = variant;
        converted.laneGuidance = LegacyLaneGuidance.create(route.laneGuidanceSource, converted);
        converted.roadFacilities = LegacyRoadFacilities.onRoute(route.roadFacilitiesSource, route, converted, frame);
        return converted;
    }

    /**
     * A match with complete attributes carries the graph road's fields as the stock map
     * exports them (class, formway, link type, LaneNum, direction, ownership, name), and its ERAT
     * 0x4269 speed is the link's speed (returned per link, NaN elsewhere). Any other link keeps
     * what the factory Beijing profile lets us state from Yandex data alone
     * (amap-injector research/stock-map-native/factory-beijing-profile-20261023.json):
     * <ul>
     * <li>link MainAction stays 0: on every factory route the stock sent (bench, 1 177 links) the
     *   link that ends a segment carries 0 and only inner links carry 8/9/10 (continue, merges),
     *   which Yandex does not report;</li>
     * <li>a roundabout section (entered with ENTER_ROUNDABOUT) is formway 4, direction 1 (100% of
     *   factory roundabout links).</li>
     * </ul>
     * Highway ranges remain link boundaries, but neither their presence nor posted speed proves
     * class, divided form, ownership or direction. Junction sign width is point-scoped guidance,
     * not a continuous lane count. Absent independent fields retain the wire defaults (including
     * direction 0, which the stock interprets as two-way); z-levels are not taken for bridges.
     */
    static double[] attributes(YandexRoute route, List<YandexRoute.Section> sections, StockPath path,
                               List<Double> beginS, List<Double> endS, List<Double> endM, double[] cumulative, RoadMatcher.Match match) {
        return attributes(route, sections, path, beginS, endS, endM, cumulative, match, new int[beginS.size()]);
    }

    // Which attributes of a link Yandex gave a value (a complete or partial graph road, the offline
    // maps' class and one-way, a roundabout): a variant never replaces them.
    static final int SOURCED_CLASS = 1, SOURCED_FORMWAY = 2, SOURCED_OWNERSHIP = 4, SOURCED_DIRECTION = 8, SOURCED_LANES = 16;

    /** The {@code SOURCED_*} bits of the fields a Yandex source knows. */
    static int sourced(RoadNetwork.KnownAttributes known) {
        return (known.roadClass != null ? SOURCED_CLASS : 0) | (known.formway != null ? SOURCED_FORMWAY : 0)
                | (known.ownership != null ? SOURCED_OWNERSHIP : 0) | (known.direction != null ? SOURCED_DIRECTION : 0)
                | (known.laneNum != null ? SOURCED_LANES : 0);
    }

    static double[] attributes(YandexRoute route, List<YandexRoute.Section> sections, StockPath path,
                               List<Double> beginS, List<Double> endS, List<Double> endM, double[] cumulative, RoadMatcher.Match match,
                               int[] sourced) {
        int link = 0;
        double[] speeds = new double[beginS.size()];
        for (int si = 0; si < path.segments.size(); si++) {
            boolean roundabout = "ENTER_ROUNDABOUT".equals(sections.get(si).action);
            for (StockPath.Link l : path.segments.get(si).links) {
                double middle = (beginS.get(link) + endS.get(link)) / 2;
                RoadNetwork.Fragment road = match == null ? null : match.fragmentAt(middle);
                speeds[link] = road == null || !road.completeAttributes ? Double.NaN : road.speedKmh;
                if (road != null && road.completeAttributes) {
                    l.roadClass = road.roadClass; l.formway = road.formway; l.linkType = road.linkType;
                    l.laneNum = road.laneNum;
                    if (road.knownAttributes.direction != null) l.direction = road.knownAttributes.direction;
                    l.ownership = road.ownership;
                    l.roadName = road.name;
                    sourced[link] = SOURCED_CLASS | SOURCED_FORMWAY | SOURCED_OWNERSHIP | SOURCED_LANES
                            | (road.knownAttributes.direction != null ? SOURCED_DIRECTION : 0);
                    link++;
                    continue;
                }
                if (roundabout) { l.formway = 4; l.direction = 1; }
                if (roundabout) sourced[link] |= SOURCED_FORMWAY | SOURCED_DIRECTION;
                // A partial source can confirm individual fields independently of the other
                // attributes, preserving tunnel/ferry and roundabout annotations when absent.
                if (road != null) {
                    RoadNetwork.KnownAttributes known = road.knownAttributes;
                    if (known.roadClass != null) l.roadClass = known.roadClass;
                    if (known.formway != null) l.formway = known.formway;
                    if (known.linkType != null) l.linkType = known.linkType;
                    if (known.laneNum != null) l.laneNum = known.laneNum;
                    if (known.direction != null) l.direction = known.direction;
                    if (known.ownership != null) l.ownership = known.ownership;
                    sourced[link] |= sourced(known);
                }
                // Legal limits and individually known fields do not establish historical speed.
                if (road != null && (l.roadName == null || l.roadName.isEmpty())
                        && road.name != null && !road.name.isEmpty()) l.roadName = road.name;
                if (route.additionalAttributes != null) {
                    // Nearby cuts may be merged to avoid sub-metre links. Ask for unanimity over
                    // the resulting whole interval, so a merged boundary cannot extend a fact.
                    RoadNetwork.KnownAttributes known = route.additionalAttributes.attributesBetween(beginS.get(link), endS.get(link));
                    if (known != null) {
                        if (known.roadClass != null) l.roadClass = known.roadClass;
                        if (known.direction != null) l.direction = known.direction;
                        sourced[link] |= sourced(known) & (SOURCED_CLASS | SOURCED_DIRECTION);
                    }
                }
                link++;
            }
        }
        return speeds;
    }

    /**
     * The variant's link overrides, each on the links whose attribute Yandex left unknown: a value
     * from a graph road, the offline maps' class and one-way or a roundabout is never replaced
     * (adminCode has no source at all). Highway ranges only cut links and give no value.
     * Returns, per switch that is set, {value, links it set, links that kept their own value}.
     */
    static Map<String, Object> applyVariant(StockPath path, int[] sourced, ExperimentVariant v) {
        Map<String, Object> applied = Json.object();
        if (!v.overridesLinks()) return applied;
        String[] keys = {"link.roadclass", "link.formway", "link.ownership", "link.direction", "link.laneNum", "link.adminCode"};
        Number[] values = {v.linkRoadClass, v.linkFormway, v.linkOwnership, v.linkDirection, v.linkLaneNum, v.linkAdminCode};
        int[] bits = {SOURCED_CLASS, SOURCED_FORMWAY, SOURCED_OWNERSHIP, SOURCED_DIRECTION, SOURCED_LANES, 0};
        for (int k = 0; k < keys.length; k++) {
            if (values[k] == null) continue;
            long set = 0, kept = 0;
            int link = 0;
            for (StockPath.Segment s : path.segments) for (StockPath.Link l : s.links) {
                if ((sourced[link++] & bits[k]) != 0) { kept++; continue; }
                int value = values[k].intValue();
                switch (k) {
                    case 0: l.roadClass = value; break;
                    case 1: l.formway = value; break;
                    case 2: l.ownership = value; break;
                    case 3: l.direction = value; break;
                    case 4: l.laneNum = value; break;
                    default: l.adcode = values[k].longValue();
                }
                set++;
            }
            Map<String, Object> row = Json.object();
            row.put("value", values[k].longValue()); row.put("links", set); row.put("keptSourced", kept);
            applied.put(keys[k], row);
        }
        return applied;
    }

    /** The link's grid points: its begin, every vertex inside, its end; repeated grid points once. */
    static void points(YandexRoute route, Coordinates.Frame frame, double a, double c, StockPath.Link link) {
        List<Double> scalars = new ArrayList<>();
        scalars.add(a);
        for (int v = (int) Math.floor(a) + 1; v < Math.ceil(c); v++) scalars.add((double) v);
        scalars.add(c);
        List<int[]> grid = new ArrayList<>();
        for (double s : scalars) {
            double[] p = sdk(route, frame, s);
            int[] g = {Coordinates.units(p[0]), Coordinates.units(p[1])};
            if (grid.isEmpty() || grid.get(grid.size() - 1)[0] != g[0] || grid.get(grid.size() - 1)[1] != g[1]) grid.add(g);
        }
        if (grid.size() == 1) grid.add(grid.get(0).clone());
        link.lon = new int[grid.size()];
        link.lat = new int[grid.size()];
        for (int i = 0; i < grid.size(); i++) { link.lon[i] = grid.get(i)[0]; link.lat[i] = grid.get(i)[1]; }
    }

    static double[] sdk(YandexRoute route, Coordinates.Frame frame, double scalar) {
        int e = Math.min((int) Math.floor(scalar), route.edges() - 1);
        double f = scalar - e;
        double lon = route.lon[e] + f * (route.lon[e + 1] - route.lon[e]);
        double lat = route.lat[e] + f * (route.lat[e + 1] - route.lat[e]);
        return Coordinates.toSdk(lon, lat, frame);
    }

    /**
     * Amap's group segments are the route's roads: consecutive manoeuvres on one named road. Yandex
     * names the road each section enters; runs of equal names become groups, an unnamed run is
     * 无名道路. A route with no name at all has no groups (the stock's {@code l.r()} path).
     */
    static void groups(List<YandexRoute.Section> sections, StockPath path) {
        boolean named = false;
        for (YandexRoute.Section s : sections) named |= s.toponym != null && !s.toponym.isEmpty();
        if (!named) return;
        int start = 0;
        for (int i = 1; i <= sections.size(); i++) {
            String a = sections.get(start).toponym, b = i < sections.size() ? sections.get(i).toponym : null;
            boolean same = i < sections.size() && (a == null ? b == null : a.equals(b));
            if (same) continue;
            path.groups.add(new Object[]{start, i - start, a == null || a.isEmpty() ? StockRouteJson.UNNAMED : a});
            start = i;
        }
    }

    /** Initial bearing, degrees [0, 360), of the polyline edge under a position. */
    public static double bearing(YandexRoute route, double scalar) {
        int e = Math.min((int) Math.floor(scalar), route.edges() - 1);
        double p1 = route.lat[e] * Coordinates.DEG, p2 = route.lat[e + 1] * Coordinates.DEG;
        double dl = (route.lon[e + 1] - route.lon[e]) * Coordinates.DEG;
        double y = Math.sin(dl) * Math.cos(p2), x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        double b = Math.toDegrees(Math.atan2(y, x));
        return b < 0 ? b + 360 : b;
    }

    /** WGS-84 point of a polyline position. */
    public static double[] wgs(YandexRoute route, double scalar) {
        int e = Math.min((int) Math.floor(scalar), route.edges() - 1);
        double f = scalar - e;
        return new double[]{route.lon[e] + f * (route.lon[e + 1] - route.lon[e]), route.lat[e] + f * (route.lat[e + 1] - route.lat[e])};
    }

    static long topoId(StockPath.Link link) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < link.lon.length; i++) b.append(link.lon[i]).append(',').append(link.lat[i]).append(';');
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.US_ASCII));
            long id = 0;
            for (int i = 0; i < 8; i++) id = (id << 8) | (d[i] & 255L);
            id &= Long.MAX_VALUE;
            return id == 0 ? 1 : id;
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Whole metres with sum == round(sum(exact)), the largest remainders taking the extra metre. */
    static int[] wholeMetres(double[] exact) {
        double sum = 0;
        for (double e : exact) sum += e;
        long total = round(sum);
        int[] out = new int[exact.length];
        long floors = 0;
        Integer[] order = new Integer[exact.length];
        for (int i = 0; i < exact.length; i++) { out[i] = (int) Math.floor(exact[i]); floors += out[i]; order[i] = i; }
        long extra = total - floors;
        if (extra < 0 || extra > exact.length) throw new IllegalStateException("length rounding");
        Arrays.sort(order, (x, y) -> {
            int c = Double.compare(exact[y] - Math.floor(exact[y]), exact[x] - Math.floor(exact[x]));
            return c != 0 ? c : Integer.compare(x, y);
        });
        for (int i = 0; i < extra; i++) out[order[i]]++;
        return out;
    }

    static boolean inside(List<double[]> ranges, double s) {
        if (ranges == null) return false;
        for (double[] r : ranges) if (s >= r[0] && s < r[1]) return true;
        return false;
    }

    static double metres(double[] cumulative, double scalar) {
        int e = Math.min((int) Math.floor(scalar), cumulative.length - 2);
        return cumulative[e] + (scalar - e) * (cumulative[e + 1] - cumulative[e]);
    }

    /** Half up, as Python's floor(x + 0.5) in the host mirror. */
    static int round(double x) { return (int) Math.floor(x + 0.5); }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int[] ints(List<Integer> l) { int[] a = new int[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a; }
    private static double[] doubles(List<Double> l) { double[] a = new double[l.size()]; for (int i = 0; i < a.length; i++) a[i] = l.get(i); return a; }

    private RouteConverter() {}
}
