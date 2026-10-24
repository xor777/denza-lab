package dev.denza.adaslink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A Yandex route on an observed road graph ({@link RoadNetwork}): for the stretches it matches, the junction
 * nodes the route passes (where Amap's links end) and the road under every route position; where it
 * does not match, nothing, and {@link RouteConverter} keeps its Yandex-only rules. The geometry stays
 * Yandex's: only supported cuts and attributes come from that graph.
 *
 * <p>A hidden Markov model over samples every {@link #STEP_M} metres of the route, in costs: a
 * sample's states are the fragments within {@link #RADIUS_M} in an allowed direction of travel
 * heading within 60 degrees of the route ((distance / SIGMA)^2 + HEADING_K (1 - cos)); a transition
 * costs |network distance - route distance| / BETA, the network distance by a bounded Dijkstra
 * over allowed directions (so a divided road's other carriageway and a parallel service road need
 * a path through a junction to be reached, and lose); a no-road state carries the route over roads
 * the graph lacks. Viterbi picks the cheapest sequence; runs shorter than {@link #MIN_RUN_M} or farther
 * than {@link #MAX_MEAN_M} on average are dropped. Only + - * / and sqrt, in the order of
 * {@code tools/adas_bridge/roads.py}, so the host oracle gives the same doubles and choices.
 */
public final class RoadMatcher {
    static final double STEP_M = 12.0;
    static final double RADIUS_M = 30.0;
    static final double SIGMA_M = 7.0;
    static final double HEADING_K = 10.0;
    static final double MIN_COS = 0.5;
    static final int MAX_CANDIDATES = 8;
    static final double BETA_M = 3.0;
    static final double BACK_TOLERANCE_M = 3.0;
    static final double SEARCH_SLACK_M = 40.0;
    static final double NULL_EMISSION = 9.0;
    static final double NULL_TRANSITION = 15.0;
    static final double MIN_RUN_M = 30.0;
    static final double DEAD_END_M = 5.0;
    static final double MAX_MEAN_M = 12.0;
    static final int JUNCTION_DEGREE = 3;

    /** The matched stretches: cuts (route scalars, ascending) and pieces {begin, end, fragment, 1 if travelled against its points}. */
    public static final class Match {
        public final RoadNetwork roads;
        public final double[] cuts;
        public final List<double[]> pieces;
        public final int samples, matchedSamples, runs, keptRuns;
        public final double matchedM, totalM;

        Match(RoadNetwork roads, double[] cuts, List<double[]> pieces, int samples, int matchedSamples, int runs, int keptRuns,
              double matchedM, double totalM) {
            this.roads = roads; this.cuts = cuts; this.pieces = pieces; this.samples = samples; this.matchedSamples = matchedSamples;
            this.runs = runs; this.keptRuns = keptRuns; this.matchedM = matchedM; this.totalM = totalM;
        }

        /** The matched piece under a route position, or null. */
        public double[] pieceAt(double scalar) {
            int lo = 0, hi = pieces.size() - 1;
            if (hi < 0 || scalar < pieces.get(0)[0]) return null;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (pieces.get(mid)[0] <= scalar) lo = mid; else hi = mid - 1;
            }
            double[] piece = pieces.get(lo);
            return piece[0] <= scalar && scalar < piece[1] ? piece : null;
        }

        /** The matched road under a route position, or null. */
        public RoadNetwork.Fragment fragmentAt(double scalar) {
            double[] piece = pieceAt(scalar);
            return piece == null ? null : roads.fragment((int) piece[2]);
        }

        /** The matched road's legal limit under a route position in the direction travelled, km/h; 0 none. */
        public int legalKmhAt(double scalar) {
            double[] piece = pieceAt(scalar);
            if (piece == null) return 0;
            RoadNetwork.Fragment f = roads.fragment((int) piece[2]);
            return piece[3] != 0 ? f.legalBackwardKmh : f.legalForwardKmh;
        }
    }

    static final class Candidate {
        final int fragment;
        final boolean reverse;
        final double offset, cost, distance;
        Candidate(int fragment, boolean reverse, double offset, double cost, double distance) {
            this.fragment = fragment; this.reverse = reverse; this.offset = offset; this.cost = cost; this.distance = distance;
        }
    }

    /** The route on the network, or null when no part of the route can be placed in its frame. */
    public static Match match(YandexRoute route, double[] cumulative, RoadNetwork roads) {
        int edges = route.edges();
        double[] xs = new double[edges + 1], ys = new double[edges + 1];
        try {
            for (int i = 0; i <= edges; i++) {
                double[] p = Coordinates.toSdk(route.lon[i], route.lat[i], roads.frame());
                xs[i] = roads.planarX(p[0]);
                ys[i] = roads.planarY(p[1]);
            }
        } catch (IllegalArgumentException outside) {
            return null;
        }
        double[][] units = new double[edges][];
        for (int e = 0; e < edges; e++) {
            double dx = xs[e + 1] - xs[e], dy = ys[e + 1] - ys[e];
            double length = Math.sqrt(dx * dx + dy * dy);
            if (length > 1e-6) units[e] = new double[]{dx / length, dy / length};
        }
        double[] last = null;
        for (int e = 0; e < edges; e++) { if (units[e] == null) units[e] = last; last = units[e]; }
        for (int e = edges - 1; e >= 0; e--) { if (units[e] == null) units[e] = last; last = units[e]; }
        if (units[0] == null) return null;

        // Samples {scalar, metres, x, y, hx, hy}; at a vertex the heading bisects both edges.
        List<double[]> samples = new ArrayList<>();
        for (int e = 0; e < edges; e++) {
            double length = cumulative[e + 1] - cumulative[e];
            int k = Math.max(1, (int) Math.ceil(length / STEP_M));
            for (int j = 0; j < k; j++) {
                double f = (double) j / k;
                double hx = units[e][0], hy = units[e][1];
                if (j == 0 && e > 0) {
                    double sx = units[e - 1][0] + hx, sy = units[e - 1][1] + hy;
                    double n = Math.sqrt(sx * sx + sy * sy);
                    if (n > 0.5) { hx = sx / n; hy = sy / n; }
                }
                samples.add(new double[]{e + f, cumulative[e] + f * length, xs[e] + f * (xs[e + 1] - xs[e]),
                        ys[e] + f * (ys[e + 1] - ys[e]), hx, hy});
            }
        }
        samples.add(new double[]{edges, cumulative[edges], xs[edges], ys[edges], units[edges - 1][0], units[edges - 1][1]});
        int count = samples.size();

        List<List<Candidate>> candidates = new ArrayList<>();
        for (double[] s : samples) {
            List<Candidate> found = new ArrayList<>();
            for (int index : roads.near(s[2], s[3], RADIUS_M)) {
                RoadNetwork.Fragment f = roads.fragment(index);
                double[] p = project(f, s[2], s[3]);
                if (p == null || p[0] > RADIUS_M) continue;
                // Past a dead end the route is where the road is not.
                if (p[4] != 0 && p[0] > DEAD_END_M) {
                    int node = p[4] < 0 ? f.from : f.to;
                    if (roads.adjacencyEnd(node) - roads.adjacencyStart(node) == 1) continue;
                }
                double q = p[0] / SIGMA_M;
                for (int r = 0; r < 2; r++) {
                    boolean reverse = r == 1;
                    if (!(reverse ? f.backward : f.forward)) continue;
                    double cos = (s[4] * p[2] + s[5] * p[3]) * (reverse ? -1.0 : 1.0);
                    if (cos < MIN_COS) continue;
                    found.add(new Candidate(index, reverse, p[1], q * q + HEADING_K * (1 - cos), p[0]));
                }
            }
            Collections.sort(found, (a, b) -> a.cost != b.cost ? Double.compare(a.cost, b.cost)
                    : a.fragment != b.fragment ? Integer.compare(a.fragment, b.fragment) : Boolean.compare(a.reverse, b.reverse));
            candidates.add(found.size() > MAX_CANDIDATES ? new ArrayList<>(found.subList(0, MAX_CANDIDATES)) : found);
        }

        // Viterbi; the no-road state is index -1, kept last in each pointer row.
        List<Candidate> first = candidates.get(0);
        double[] score = new double[first.size()];
        for (int k = 0; k < score.length; k++) score[k] = first.get(k).cost;
        double nul = NULL_EMISSION;
        int[][] back = new int[count][];
        back[0] = new int[0];
        for (int i = 1; i < count; i++) {
            double delta = samples.get(i)[1] - samples.get(i - 1)[1];
            List<Candidate> prev = candidates.get(i - 1), nexts = candidates.get(i);
            Double[][] nets = new Double[prev.size()][];
            for (int k = 0; k < prev.size(); k++) nets[k] = transitions(roads, prev.get(k), nexts, delta);
            double[] fresh = new double[nexts.size()];
            int[] pointers = new int[nexts.size() + 1];
            for (int j = 0; j < nexts.size(); j++) {
                double best = Double.POSITIVE_INFINITY;
                int arg = -1;
                for (int k = 0; k < prev.size(); k++) {
                    Double net = nets[k][j];
                    if (net == null) continue;
                    double c = score[k] + Math.abs(net - delta) / BETA_M;
                    if (c < best) { best = c; arg = k; }
                }
                double c = nul + NULL_TRANSITION;
                if (c < best) { best = c; arg = -1; }
                fresh[j] = best + nexts.get(j).cost;
                pointers[j] = arg;
            }
            double best = nul;
            int arg = -1;
            for (int k = 0; k < prev.size(); k++) {
                double c = score[k] + NULL_TRANSITION;
                if (c < best) { best = c; arg = k; }
            }
            pointers[nexts.size()] = arg;
            back[i] = pointers;
            score = fresh;
            nul = best + NULL_EMISSION;
        }
        double best = nul;
        int state = -1;
        for (int k = 0; k < score.length; k++) if (score[k] < best) { best = score[k]; state = k; }
        int[] states = new int[count];
        for (int i = count - 1; i >= 0; i--) {
            states[i] = state;
            if (i > 0) state = state < 0 ? back[i][back[i].length - 1] : back[i][state];
        }

        // Runs of matched samples -> traversals with route scalars -> pieces and cuts.
        List<double[]> pieces = new ArrayList<>();
        List<Double> cuts = new ArrayList<>();
        int runs = 0, kept = 0, matchedSamples = 0;
        for (int s : states) if (s >= 0) matchedSamples++;
        double matchedM = 0;
        int i = 0;
        while (i < count) {
            if (states[i] < 0) { i++; continue; }
            int j = i;
            while (j + 1 < count && states[j + 1] >= 0) j++;
            runs++;
            double begin = i == 0 ? 0.0 : samples.get(i)[0];
            double end = j == count - 1 ? (double) edges : samples.get(j)[0];
            double length = RouteConverter.metres(cumulative, end) - RouteConverter.metres(cumulative, begin);
            double mean = 0.0;
            for (int k = i; k <= j; k++) mean += candidates.get(k).get(states[k]).distance;
            mean /= j - i + 1;
            if (length >= MIN_RUN_M && mean <= MAX_MEAN_M) {
                kept++;
                matchedM += length;
                List<double[]> run = new ArrayList<>();
                Candidate head = candidates.get(i).get(states[i]);
                run.add(new double[]{head.fragment, begin, head.reverse ? 1 : 0});
                List<double[]> crossings = new ArrayList<>();
                for (int k = i + 1; k <= j; k++) {
                    Candidate a = candidates.get(k - 1).get(states[k - 1]), b = candidates.get(k).get(states[k]);
                    List<int[]> between = new ArrayList<>();
                    List<Integer> nodes = path(roads, a, b, samples.get(k)[1] - samples.get(k - 1)[1], between);
                    if (nodes.isEmpty()) continue;
                    double at = samples.get(k - 1)[0];
                    for (int n = 0; n < nodes.size(); n++) {
                        int g = n < between.size() ? between.get(n)[0] : b.fragment;
                        int reverse = n < between.size() ? between.get(n)[1] : b.reverse ? 1 : 0;
                        double s = nodeScalar(roads, nodes.get(n), xs, ys, samples.get(k - 1)[0], samples.get(k)[0]);
                        at = Math.max(at, s);
                        crossings.add(new double[]{at, nodes.get(n), run.get(run.size() - 1)[0], g});
                        run.add(new double[]{g, at, reverse});
                    }
                }
                for (double[] c : crossings) {
                    int node = (int) c[1];
                    int degree = roads.adjacencyEnd(node) - roads.adjacencyStart(node);
                    if (degree >= JUNCTION_DEGREE || !sameAttributes(roads.fragment((int) c[2]), roads.fragment((int) c[3]))) cuts.add(c[0]);
                }
                for (int k = 0; k < run.size(); k++) {
                    double s = run.get(k)[1], e = k + 1 < run.size() ? run.get(k + 1)[1] : end;
                    if (e > s) pieces.add(new double[]{s, e, run.get(k)[0], run.get(k)[2]});
                }
            }
            i = j + 1;
        }
        double[] cutArray = new double[cuts.size()];
        for (int k = 0; k < cutArray.length; k++) cutArray[k] = cuts.get(k);
        return new Match(roads, cutArray, pieces, count, matchedSamples, runs, kept, matchedM, cumulative[edges]);
    }

    /** What a link carries from its road; a change of it ends the link (Amap's road record). */
    static boolean sameAttributes(RoadNetwork.Fragment a, RoadNetwork.Fragment b) {
        return a.roadClass == b.roadClass && a.formway == b.formway && a.linkType == b.linkType && a.laneNum == b.laneNum
                && a.direction == b.direction && a.ownership == b.ownership && a.name.equals(b.name);
    }

    /**
     * Nearest point of a fragment: {distance, offset along it, unit direction x, y of that segment, -1 / 1 when
     * it is the fragment's first / last point else 0}; null if degenerate.
     */
    static double[] project(RoadNetwork.Fragment f, double x, double y) {
        double[] xs = f.x, ys = f.y, cum = f.cum;
        double bestD = 0, bestT = 0;
        int bestK = -1;
        for (int k = 0; k + 1 < xs.length; k++) {
            double ax = xs[k], ay = ys[k];
            double dx = xs[k + 1] - ax, dy = ys[k + 1] - ay;
            double ll = dx * dx + dy * dy;
            double t = 0.0;
            if (ll > 0) {
                t = ((x - ax) * dx + (y - ay) * dy) / ll;
                if (t < 0) t = 0.0; else if (t > 1) t = 1.0;
            }
            double qx = ax + t * dx - x, qy = ay + t * dy - y;
            double d = Math.sqrt(qx * qx + qy * qy);
            if (bestK < 0 || d < bestD) { bestD = d; bestK = k; bestT = t; }
        }
        int k = bestK;
        double dx = xs[k + 1] - xs[k], dy = ys[k + 1] - ys[k];
        double length = Math.sqrt(dx * dx + dy * dy);
        if (!(length > 0)) {
            dx = xs[xs.length - 1] - xs[0];
            dy = ys[ys.length - 1] - ys[0];
            length = Math.sqrt(dx * dx + dy * dy);
            if (!(length > 0)) return null;
        }
        double end = k == 0 && bestT == 0.0 ? -1 : k == xs.length - 2 && bestT == 1.0 ? 1 : 0;
        return new double[]{bestD, cum[k] + bestT * (cum[k + 1] - cum[k]), dx / length, dy / length, end};
    }

    /** Bounded Dijkstra over allowed directions: distances, and predecessors {prev node, fragment, reverse}. */
    static void search(RoadNetwork roads, int start, double startCost, double bound, Map<Integer, Double> dist, Map<Integer, int[]> pred) {
        dist.put(start, startCost);
        PriorityQueue<double[]> heap = new PriorityQueue<>((p, q) -> p[0] != q[0] ? Double.compare(p[0], q[0]) : Double.compare(p[1], q[1]));
        heap.add(new double[]{startCost, start});
        Set<Integer> done = new HashSet<>();
        while (!heap.isEmpty()) {
            double[] top = heap.poll();
            double d = top[0];
            int u = (int) top[1];
            if (done.contains(u) || d > dist.get(u)) continue;
            done.add(u);
            for (int k = roads.adjacencyStart(u), end = roads.adjacencyEnd(u); k < end; k++) {
                int ref = roads.adjacency(k);
                RoadNetwork.Fragment g = roads.fragment(ref >> 1);
                int v;
                boolean reverse;
                if ((ref & 1) != 0) {
                    if (!g.backward) continue;
                    v = g.from; reverse = true;
                } else {
                    if (!g.forward) continue;
                    v = g.to; reverse = false;
                }
                double nd = d + g.length();
                Double old = dist.get(v);
                if (nd <= bound && (old == null || nd < old)) {
                    dist.put(v, nd);
                    pred.put(v, new int[]{u, ref >> 1, reverse ? 1 : 0});
                    heap.add(new double[]{nd, v});
                }
            }
        }
    }

    /** {node, metres left to it} where a candidate leaves its fragment, and where one enters its own. */
    static double[] exit(RoadNetwork roads, Candidate c) {
        RoadNetwork.Fragment f = roads.fragment(c.fragment);
        return c.reverse ? new double[]{f.from, c.offset} : new double[]{f.to, f.length() - c.offset};
    }

    static double[] entry(RoadNetwork roads, Candidate c) {
        RoadNetwork.Fragment f = roads.fragment(c.fragment);
        return c.reverse ? new double[]{f.to, f.length() - c.offset} : new double[]{f.from, c.offset};
    }

    /** Network distance from a to each next candidate (null: no path within the bound). */
    static Double[] transitions(RoadNetwork roads, Candidate a, List<Candidate> nexts, double delta) {
        Double[] out = new Double[nexts.size()];
        double bound = 2 * delta + SEARCH_SLACK_M;
        Map<Integer, Double> dist = null;
        for (int j = 0; j < nexts.size(); j++) {
            Candidate b = nexts.get(j);
            if (b.fragment == a.fragment && b.reverse == a.reverse) {
                double net = a.reverse ? a.offset - b.offset : b.offset - a.offset;
                if (net >= -BACK_TOLERANCE_M) { out[j] = net; continue; }
            }
            if (dist == null) {
                dist = new HashMap<>();
                double[] x = exit(roads, a);
                if (x[1] <= bound) search(roads, (int) x[0], x[1], bound, dist, new HashMap<Integer, int[]>());
            }
            double[] n = entry(roads, b);
            Double reach = dist.get((int) n[0]);
            if (reach != null) {
                double net = reach + n[1];
                if (net <= bound) out[j] = net;
            }
        }
        return out;
    }

    /** Nodes crossed from a to b (empty when b follows on a's own fragment); fills the fragments between. */
    static List<Integer> path(RoadNetwork roads, Candidate a, Candidate b, double delta, List<int[]> between) {
        List<Integer> nodes = new ArrayList<>();
        if (b.fragment == a.fragment && b.reverse == a.reverse) {
            double net = a.reverse ? a.offset - b.offset : b.offset - a.offset;
            if (net >= -BACK_TOLERANCE_M) return nodes;
        }
        double[] x = exit(roads, a);
        int node = (int) x[0];
        Map<Integer, Double> dist = new HashMap<>();
        Map<Integer, int[]> pred = new HashMap<>();
        search(roads, node, x[1], 2 * delta + SEARCH_SLACK_M, dist, pred);
        int target = (int) entry(roads, b)[0];
        List<int[]> steps = new ArrayList<>();
        int v = target;
        while (v != node) {
            int[] p = pred.get(v);
            steps.add(new int[]{p[1], p[2], v});
            v = p[0];
        }
        Collections.reverse(steps);
        nodes.add(node);
        for (int[] s : steps) { between.add(new int[]{s[0], s[1]}); nodes.add(s[2]); }
        return nodes;
    }

    /** The route position nearest a network node between two samples' positions. */
    static double nodeScalar(RoadNetwork roads, int node, double[] xs, double[] ys, double s0, double s1) {
        double x = roads.nodeX(node), y = roads.nodeY(node);
        double best = Double.POSITIVE_INFINITY, at = s0;
        int e = (int) Math.floor(s0);
        while (true) {
            double sa = s0 > e ? s0 : (double) e;
            double sb = s1 < e + 1 ? s1 : (double) (e + 1);
            if (sb >= sa && e < xs.length - 1) {
                double ax = xs[e] + (sa - e) * (xs[e + 1] - xs[e]), ay = ys[e] + (sa - e) * (ys[e + 1] - ys[e]);
                double bx = xs[e] + (sb - e) * (xs[e + 1] - xs[e]), by = ys[e] + (sb - e) * (ys[e + 1] - ys[e]);
                double dx = bx - ax, dy = by - ay;
                double ll = dx * dx + dy * dy;
                double t = 0.0;
                if (ll > 0) {
                    t = ((x - ax) * dx + (y - ay) * dy) / ll;
                    if (t < 0) t = 0.0; else if (t > 1) t = 1.0;
                }
                double qx = ax + t * dx - x, qy = ay + t * dy - y;
                double d = qx * qx + qy * qy;
                if (d < best) { best = d; at = sa + t * (sb - sa); }
            }
            e++;
            if (e >= s1 || e >= xs.length - 1) break;
        }
        return at;
    }

    private RoadMatcher() {}
}
