"""Host oracle of libraries/adas-link RoadStore + RoadMatcher: a Yandex route on our OSM roads.

The store (`road_store.py` writes it) holds our OSM network as the stock map exports it: per
fragment its geometry on the stock grid and the factory-convention attributes the build's
producers wrote. The matcher places the Yandex polyline on it and returns, for the stretches it
matched, the junction nodes the route passes (where Amap's links end) and the fragment under every
route position; where it does not match, nothing, and the converter keeps its Yandex-only rules.
The geometry stays Yandex's: only cuts and attributes come from OSM.

Matching is a hidden Markov model over samples every STEP_M metres of the route (Newson and
Krumm's form, in costs instead of probabilities): a sample's states are the fragments within
RADIUS_M in an allowed direction of travel heading within 60 degrees of the route, each costing
(distance / SIGMA_M)^2 + HEADING_K (1 - cos); a transition costs |network distance - route
distance| / BETA_M, the network distance found by a bounded Dijkstra over allowed directions; a
no-road state per sample (NULL_EMISSION) entered and left at NULL_TRANSITION carries the route
over roads missing from OSM and over jumps no network path explains. Viterbi picks the cheapest
sequence; matched runs shorter than MIN_RUN_M or farther than MAX_MEAN_M on average are dropped.

Every number is computed with + - * / and sqrt in one fixed order, so the Java mirror
gives the same doubles and the same choices (tests/test_stock_equivalence.py checks it).
"""
from __future__ import annotations

import heapq
import math
import struct

SCALE = 3600000
FRAMES = {0: "wgs84", 1: "native-gcj-unrestricted-candidate"}
HEADER = struct.Struct(">4sHHiiddiiiiiii7ii")
FRAGMENT = struct.Struct(">iiiHBBBBBBBB3s")
NO_NAME = 0xFFFFFF

STEP_M = 12.0
RADIUS_M = 30.0
SIGMA_M = 7.0
HEADING_K = 10.0
MIN_COS = 0.5
MAX_CANDIDATES = 8
BETA_M = 3.0
BACK_TOLERANCE_M = 3.0
SEARCH_SLACK_M = 40.0
NULL_EMISSION = 9.0
NULL_TRANSITION = 15.0
MIN_RUN_M = 30.0
DEAD_END_M = 5.0
MAX_MEAN_M = 12.0
JUNCTION_DEGREE = 3
INF = float("inf")


class StoreError(ValueError):
    pass


class RoadStore:
    """RoadStore.java: the store file, decoded on demand."""

    def __init__(self, data):
        data = bytes(data)
        if len(data) < HEADER.size:
            raise StoreError("short road store")
        h = HEADER.unpack_from(data, 0)
        if h[0] != b"DZRN" or h[1] != 1:
            raise StoreError("not a version 1 road store")
        if h[2] not in FRAMES:
            raise StoreError("unknown store frame")
        self.frame = FRAMES[h[2]]
        (self.origin_x, self.origin_y, self.mx, self.my, self.cell_x, self.cell_y, self.cols, self.rows,
         self.node_count, self.fragment_count, name_count) = h[3:14]
        o_nodes, o_adj, o_frag, o_geom, o_cells, o_items, o_names = h[14:21]
        if h[21] != len(data):
            raise StoreError("road store length")
        self.data = data
        n = self.node_count
        xy = struct.unpack_from(">%di" % (2 * n), data, o_nodes)
        self.node_x, self.node_y = xy[0::2], xy[1::2]
        self.adj_start = struct.unpack_from(">%di" % (n + 1), data, o_adj)
        self.adj = struct.unpack_from(">%di" % self.adj_start[n], data, o_adj + 4 * (n + 1))
        self.o_frag, self.o_geom = o_frag, o_geom
        self.cell_start = struct.unpack_from(">%di" % (self.cols * self.rows + 1), data, o_cells)
        self.items = struct.unpack_from(">%di" % self.cell_start[-1], data, o_items)
        starts = struct.unpack_from(">%di" % (name_count + 1), data, o_names)
        base = o_names + 4 * (name_count + 1)
        self.names = [data[base + starts[i]:base + starts[i + 1]].decode("utf-8") for i in range(name_count)]
        self._fragments = {}

    def fragment(self, index):
        """{from, to, roadClass, formway, linkType, laneNum, forward, backward, direction, ownership,
        speed, legalForward, legalBackward, name, x, y (planar metres of every point), cum (planar metres along it)}."""
        f = self._fragments.get(index)
        if f is not None:
            return f
        a, b, offset, count, rc, fw, lt, lanes, flags, speed, legal_f, legal_b, name = FRAGMENT.unpack_from(
            self.data, self.o_frag + FRAGMENT.size * index)
        ni = int.from_bytes(name, "big")
        ux, uy = self.node_x[a], self.node_y[a]
        xs, ys = [(ux - self.origin_x) * self.mx], [(uy - self.origin_y) * self.my]
        at = self.o_geom + offset
        data = self.data
        for _ in range(count - 1):
            values = []
            for _ in range(2):
                shift = result = 0
                while True:
                    byte = data[at]
                    at += 1
                    result |= (byte & 0x7F) << shift
                    shift += 7
                    if byte < 0x80:
                        break
                values.append((result >> 1) ^ -(result & 1))
            ux += values[0]
            uy += values[1]
            xs.append((ux - self.origin_x) * self.mx)
            ys.append((uy - self.origin_y) * self.my)
        cum = [0.0]
        for k in range(count - 1):
            dx, dy = xs[k + 1] - xs[k], ys[k + 1] - ys[k]
            cum.append(cum[k] + math.sqrt(dx * dx + dy * dy))
        f = dict(index=index, a=a, b=b, roadClass=rc, formway=fw, linkType=lt, laneNum=lanes,
                 forward=bool(flags & 1), backward=bool(flags & 2), direction=(flags >> 2) & 1,
                 ownership=(flags >> 3) & 1, speed=speed, legalForward=legal_f, legalBackward=legal_b,
                 name="" if ni == NO_NAME else self.names[ni], x=xs, y=ys, cum=cum)
        self._fragments[index] = f
        return f

    def degree(self, node):
        return self.adj_start[node + 1] - self.adj_start[node]

    def near(self, x, y, radius):
        """Fragment indices whose cells meet the square of `radius` planar metres around (x, y), ascending."""
        wx, wy = self.cell_x * self.mx, self.cell_y * self.my
        r = radius + 1.0
        cx0 = max(0, int(math.floor((x - r) / wx)))
        cx1 = min(self.cols - 1, int(math.floor((x + r) / wx)))
        cy0 = max(0, int(math.floor((y - r) / wy)))
        cy1 = min(self.rows - 1, int(math.floor((y + r) / wy)))
        found = set()
        for cy in range(cy0, cy1 + 1):
            for cx in range(cx0, cx1 + 1):
                c = cy * self.cols + cx
                found.update(self.items[self.cell_start[c]:self.cell_start[c + 1]])
        return sorted(found)


def attributes_key(f):
    """What a link carries from its fragment; a change of it ends the link (Amap's road record)."""
    return (f["roadClass"], f["formway"], f["linkType"], f["laneNum"], f["direction"], f["ownership"], f["name"])


class _Candidate:
    __slots__ = ("fragment", "reverse", "offset", "cost", "distance")

    def __init__(self, fragment, reverse, offset, cost, distance):
        self.fragment, self.reverse, self.offset, self.cost, self.distance = fragment, reverse, offset, cost, distance


def _project(f, x, y):
    """Nearest point of the fragment: (distance, offset along it, unit direction x, y of that segment,
    -1 / 1 when it is the fragment's first / last point, else 0)."""
    xs, ys, cum = f["x"], f["y"], f["cum"]
    best = None
    for k in range(len(xs) - 1):
        ax, ay = xs[k], ys[k]
        dx, dy = xs[k + 1] - ax, ys[k + 1] - ay
        ll = dx * dx + dy * dy
        t = 0.0
        if ll > 0:
            t = ((x - ax) * dx + (y - ay) * dy) / ll
            if t < 0:
                t = 0.0
            elif t > 1:
                t = 1.0
        qx, qy = ax + t * dx - x, ay + t * dy - y
        d = math.sqrt(qx * qx + qy * qy)
        if best is None or d < best[0]:
            best = (d, k, t)
    d, k, t = best
    dx, dy = xs[k + 1] - xs[k], ys[k + 1] - ys[k]
    length = math.sqrt(dx * dx + dy * dy)
    if not length > 0:
        dx, dy = xs[-1] - xs[0], ys[-1] - ys[0]
        length = math.sqrt(dx * dx + dy * dy)
        if not length > 0:
            return None
    end = -1 if k == 0 and t == 0.0 else 1 if k == len(xs) - 2 and t == 1.0 else 0
    return d, cum[k] + t * (cum[k + 1] - cum[k]), dx / length, dy / length, end


class RoadMatch:
    """RoadMatcher.Match: cuts (route scalars, ascending), pieces [begin, end, fragment, reverse], statistics."""

    def __init__(self, store, cuts, pieces, stats):
        self.store, self.cuts, self.pieces, self.stats = store, cuts, pieces, stats
        self._begins = [p[0] for p in pieces]

    def piece_at(self, scalar):
        """The matched piece (begin, end, fragment, reverse) under a route position, or None."""
        lo, hi = 0, len(self.pieces) - 1
        if hi < 0 or scalar < self.pieces[0][0]:
            return None
        while lo < hi:
            mid = (lo + hi + 1) >> 1
            if self.pieces[mid][0] <= scalar:
                lo = mid
            else:
                hi = mid - 1
        piece = self.pieces[lo]
        return piece if piece[0] <= scalar < piece[1] else None

    def fragment_at(self, scalar):
        """The matched fragment under a route position, or None."""
        piece = self.piece_at(scalar)
        return None if piece is None else self.store.fragment(piece[2])

    def legal_at(self, scalar):
        """The legal limit of the matched road under a route position in the direction travelled, km/h; 0 none."""
        piece = self.piece_at(scalar)
        if piece is None:
            return 0
        f = self.store.fragment(piece[2])
        return f["legalBackward"] if piece[3] else f["legalForward"]


def _frame_point(store, lon, lat):
    from .stock import to_sdk
    p = to_sdk(lon, lat, store.frame)
    return (p[0] * SCALE - store.origin_x) * store.mx, (p[1] * SCALE - store.origin_y) * store.my


def _search(store, start, start_cost, bound):
    """Bounded Dijkstra over allowed directions from a node: {node: distance}, {node: (prev, fragment, reverse)}."""
    dist, pred, heap = {start: start_cost}, {}, [(start_cost, start)]
    done = set()
    while heap:
        d, u = heapq.heappop(heap)
        if u in done or d > dist[u]:
            continue
        done.add(u)
        for k in range(store.adj_start[u], store.adj_start[u + 1]):
            ref = store.adj[k]
            g = store.fragment(ref >> 1)
            if ref & 1:
                if not g["backward"]:
                    continue
                v, reverse = g["a"], True
            else:
                if not g["forward"]:
                    continue
                v, reverse = g["b"], False
            nd = d + g["cum"][-1]
            if nd <= bound and (v not in dist or nd < dist[v]):
                dist[v] = nd
                pred[v] = (u, ref >> 1, reverse)
                heapq.heappush(heap, (nd, v))
    return dist, pred


def _exit(store, c):
    f = store.fragment(c.fragment)
    return (f["a"], c.offset) if c.reverse else (f["b"], f["cum"][-1] - c.offset)


def _entry(store, c):
    f = store.fragment(c.fragment)
    return (f["b"], f["cum"][-1] - c.offset) if c.reverse else (f["a"], c.offset)


def _transitions(store, a, nexts, delta):
    """Network distance from candidate a to each of the next sample's candidates (None: no path)."""
    out = [None] * len(nexts)
    bound = 2 * delta + SEARCH_SLACK_M
    search = None
    for j, b in enumerate(nexts):
        if b.fragment == a.fragment and b.reverse == a.reverse:
            net = a.offset - b.offset if a.reverse else b.offset - a.offset
            if net >= -BACK_TOLERANCE_M:
                out[j] = net
                continue
        if search is None:
            node, rest = _exit(store, a)
            search = _search(store, node, rest, bound) if rest <= bound else ({}, {})
        node, into = _entry(store, b)
        if node in search[0]:
            net = search[0][node] + into
            if net <= bound:
                out[j] = net
    return out


def _path(store, a, b, delta):
    """The (fragment, reverse) traversals strictly between candidates a and b, and the nodes crossed."""
    if b.fragment == a.fragment and b.reverse == a.reverse:
        net = a.offset - b.offset if a.reverse else b.offset - a.offset
        if net >= -BACK_TOLERANCE_M:
            return [], []
    node, rest = _exit(store, a)
    dist, pred = _search(store, node, rest, 2 * delta + SEARCH_SLACK_M)
    target, _ = _entry(store, b)
    steps, v = [], target
    while v != node:
        u, g, reverse = pred[v]
        steps.append((g, reverse, v))
        v = u
    steps.reverse()
    return [(g, r) for g, r, _ in steps], [node] + [v for _, _, v in steps]


def match(route, cumulative, store):
    """RoadMatcher.match: the Yandex route on the store, or None when no part of it lies inside."""
    lon, lat = route["lon"], route["lat"]
    edges = len(lon) - 1
    try:
        points = [_frame_point(store, lon[i], lat[i]) for i in range(edges + 1)]
    except ValueError:
        return None
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    units = [None] * edges
    for e in range(edges):
        dx, dy = xs[e + 1] - xs[e], ys[e + 1] - ys[e]
        length = math.sqrt(dx * dx + dy * dy)
        if length > 1e-6:
            units[e] = (dx / length, dy / length)
    last = None
    for e in range(edges):
        if units[e] is None:
            units[e] = last
        last = units[e]
    for e in range(edges - 1, -1, -1):
        if units[e] is None:
            units[e] = last
        last = units[e]
    if units[0] is None:
        return None

    # Samples: scalar, route metres, planar point, heading (at a vertex: the bisector of both edges).
    samples = []
    for e in range(edges):
        length = cumulative[e + 1] - cumulative[e]
        k = max(1, int(math.ceil(length / STEP_M)))
        for j in range(k):
            f = j / k
            hx, hy = units[e]
            if j == 0 and e > 0:
                sx, sy = units[e - 1][0] + hx, units[e - 1][1] + hy
                n = math.sqrt(sx * sx + sy * sy)
                if n > 0.5:
                    hx, hy = sx / n, sy / n
            samples.append((e + f, cumulative[e] + f * length, xs[e] + f * (xs[e + 1] - xs[e]),
                            ys[e] + f * (ys[e + 1] - ys[e]), hx, hy))
    samples.append((float(edges), cumulative[edges], xs[edges], ys[edges], units[edges - 1][0], units[edges - 1][1]))

    candidates = []
    for s in samples:
        found = []
        for index in store.near(s[2], s[3], RADIUS_M):
            f = store.fragment(index)
            p = _project(f, s[2], s[3])
            if p is None or p[0] > RADIUS_M:
                continue
            d, offset, ux, uy, end = p
            # Past a dead end the route is where the road is not.
            if end and d > DEAD_END_M and store.degree(f["a"] if end < 0 else f["b"]) == 1:
                continue
            q = d / SIGMA_M
            for reverse, allowed, sign in ((False, f["forward"], 1.0), (True, f["backward"], -1.0)):
                if not allowed:
                    continue
                cos = (s[4] * ux + s[5] * uy) * sign
                if cos < MIN_COS:
                    continue
                found.append(_Candidate(index, reverse, offset, q * q + HEADING_K * (1 - cos), d))
        found.sort(key=lambda c: (c.cost, c.fragment, c.reverse))
        candidates.append(found[:MAX_CANDIDATES])

    # Viterbi; the no-road state is index -1. Ties keep the earlier option (candidates in order, then no road).
    score = [c.cost for c in candidates[0]]
    null = NULL_EMISSION
    back = [[]]
    for i in range(1, len(samples)):
        delta = samples[i][1] - samples[i - 1][1]
        nexts = candidates[i]
        nets = [_transitions(store, a, nexts, delta) for a in candidates[i - 1]]
        new, pointers = [], []
        for j, b in enumerate(nexts):
            best, arg = INF, -1
            for k in range(len(candidates[i - 1])):
                net = nets[k][j]
                if net is None:
                    continue
                c = score[k] + abs(net - delta) / BETA_M
                if c < best:
                    best, arg = c, k
            c = null + NULL_TRANSITION
            if c < best:
                best, arg = c, -1
            new.append(best + b.cost)
            pointers.append(arg)
        best, arg = null, -1
        for k in range(len(candidates[i - 1])):
            c = score[k] + NULL_TRANSITION
            if c < best:
                best, arg = c, k
        null_new = best + NULL_EMISSION
        pointers.append(arg)
        back.append(pointers)
        score, null = new, null_new

    best, state = null, -1
    for k in range(len(score)):
        if score[k] < best:
            best, state = score[k], k
    states = [-1] * len(samples)
    for i in range(len(samples) - 1, -1, -1):
        states[i] = state
        if i > 0:
            pointers = back[i]
            state = pointers[-1] if state < 0 else pointers[state]

    # Runs of matched samples -> traversals with route scalars -> pieces and cuts.
    pieces, cuts = [], []
    runs = kept = 0
    matched_m = 0.0
    i = 0
    while i < len(samples):
        if states[i] < 0:
            i += 1
            continue
        j = i
        while j + 1 < len(samples) and states[j + 1] >= 0:
            j += 1
        runs += 1
        begin = 0.0 if i == 0 else samples[i][0]
        end = float(edges) if j == len(samples) - 1 else samples[j][0]
        length = _metres(cumulative, end) - _metres(cumulative, begin)
        mean = 0.0
        for k in range(i, j + 1):
            mean += candidates[k][states[k]].distance
        mean /= j - i + 1
        if length >= MIN_RUN_M and mean <= MAX_MEAN_M:
            kept += 1
            matched_m += length
            first = candidates[i][states[i]]
            run = [[first.fragment, begin, first.reverse]]
            crossings = []
            for k in range(i + 1, j + 1):
                a, b = candidates[k - 1][states[k - 1]], candidates[k][states[k]]
                between, nodes = _path(store, a, b, samples[k][1] - samples[k - 1][1])
                if not nodes:
                    continue
                at = samples[k - 1][0]
                for n, (g, reverse) in zip(nodes, between + [(b.fragment, b.reverse)]):
                    s = _node_scalar(store, n, xs, ys, samples[k - 1][0], samples[k][0])
                    at = max(at, s)
                    crossings.append((at, n, run[-1][0], g))
                    run.append([g, at, reverse])
            for at, n, f1, f2 in crossings:
                if store.degree(n) >= JUNCTION_DEGREE or attributes_key(store.fragment(f1)) != attributes_key(store.fragment(f2)):
                    cuts.append(at)
            for k, (g, s, reverse) in enumerate(run):
                e = run[k + 1][1] if k + 1 < len(run) else end
                if e > s:
                    pieces.append((s, e, g, reverse))
        i = j + 1
    total = cumulative[edges]
    stats = dict(samples=len(samples), matchedSamples=sum(s >= 0 for s in states), runs=runs, keptRuns=kept,
                 matchedM=matched_m, totalM=total, cuts=len(cuts))
    return RoadMatch(store, cuts, pieces, stats)


def _node_scalar(store, node, xs, ys, s0, s1):
    """The route position nearest a network node between two samples' positions."""
    x = (store.node_x[node] - store.origin_x) * store.mx
    y = (store.node_y[node] - store.origin_y) * store.my
    best, at = INF, s0
    e = int(math.floor(s0))
    while True:
        sa = s0 if s0 > e else float(e)
        sb = s1 if s1 < e + 1 else float(e + 1)
        if sb >= sa and e < len(xs) - 1:
            ax, ay = xs[e] + (sa - e) * (xs[e + 1] - xs[e]), ys[e] + (sa - e) * (ys[e + 1] - ys[e])
            bx, by = xs[e] + (sb - e) * (xs[e + 1] - xs[e]), ys[e] + (sb - e) * (ys[e + 1] - ys[e])
            dx, dy = bx - ax, by - ay
            ll = dx * dx + dy * dy
            t = 0.0
            if ll > 0:
                t = ((x - ax) * dx + (y - ay) * dy) / ll
                if t < 0:
                    t = 0.0
                elif t > 1:
                    t = 1.0
            qx, qy = ax + t * dx - x, ay + t * dy - y
            d = qx * qx + qy * qy
            if d < best:
                best, at = d, sa + t * (sb - sa)
        e += 1
        if e >= s1 or e >= len(xs) - 1:
            break
    return at


def _metres(cumulative, scalar):
    e = min(int(math.floor(scalar)), len(cumulative) - 2)
    return cumulative[e] + (scalar - e) * (cumulative[e + 1] - cumulative[e])
