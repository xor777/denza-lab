#!/usr/bin/env python3
"""Bridge side of the ADAS bench: what the Yandex->ADAS bridge would fire, as a feed trace.

Two inputs, always labelled in the trace's provenance:

  synth   SYNTHETIC Yandex input built from a stock trace: each stock route's own
          geometry becomes a `denza-yandex-driving-route-v1` snapshot carrying only
          what Yandex also gives (polyline, manoeuvre sections with their actions and
          next-road names, traffic-light positions, tunnel ranges, way points); each
          GNSS tick of the stock run becomes a `denza-yandex-guidance-v1` sample
          projected on the current route. A new Yandex route appears when the stock
          fired a new route (reroute timing borrowed from stock). Coordinates are
          passed through unchanged (target frame "wgs84" = identity), so the stock
          and bridge geometry share one frame; the WGS->SDK transform is not tested here.
  yandex  REAL Yandex Navigator snapshot + native guidance ring (the emulator captures
          of the instrumented Navigator), through capture_replay.

    python3 -m tools.adas_bridge.bridge_trace synth --stock-trace RUN/stock-trace.json --out RUN/bridge-trace.json
    python3 -m tools.adas_bridge.bridge_trace yandex --snapshot S.json --progress DIR --out OUT/bridge-trace.json

Nothing is sent anywhere; LiveBridge and FakeReceiver are local.
"""
import argparse
import bisect
import copy
import glob
import json
import math
from pathlib import Path

from .bridge import read_json
from .feed import RouteAssembler, decode_event, read_trace, write_trace, NAMES
from .watch import LiveBridge
from .wire import decode_someip_route

ROOT = Path(__file__).resolve().parents[2]
GOLDEN = ROOT / 'captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.someip.bin'
# Inverse of bridge.MAIN_ACTION for the synthetic input (UTURN -> UTURN_LEFT).
ARRIVAL_M = 15  # synthetic Yandex: guidance ends this close to the destination
YANDEX_ACTION = {8: 'STRAIGHT', 1: 'LEFT', 2: 'RIGHT', 3: 'SLIGHT_LEFT', 4: 'SLIGHT_RIGHT', 5: 'HARD_LEFT',
                 6: 'HARD_RIGHT', 7: 'UTURN_LEFT', 11: 'ENTER_ROUNDABOUT', 12: 'LEAVE_ROUNDABOUT'}


def metres(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * 111320.0 * math.cos(lat), (b[1] - a[1]) * 110574.0)


def snapshot_from_stock(route, *, route_id, session, generation):
    points, link_range = [], []
    for link in route['links']:
        first = len(points) - 1 if points else 0
        for k in range(link['pntBegIdx'], link['pntBegIdx'] + link['pntCnt']):
            p = [route['pnts'][k]['x'], route['pnts'][k]['y']]
            if points and p == points[-1]:
                continue
            points.append(p)
        link_range.append((first, len(points) - 1))
    edges = len(points) - 1
    pos = lambda index: ({'segmentIndex': index, 'segmentPosition': 0.0} if index < edges
                         else {'segmentIndex': edges, 'segmentPosition': 0.0})
    end = lambda index: {'segmentIndex': index - 1, 'segmentPosition': 1.0}
    segments = [s for g in route.get('guideGroups') or [] for s in g.get('segments') or []]
    sections = []
    for k, segment in enumerate(segments):
        a = link_range[segment['linkBegIdx']][0]
        b = link_range[segment['linkBegIdx'] + segment['crntSegmLinkCnt'] - 1][1]
        if b <= a:
            continue
        previous = segments[k - 1] if k else None
        annotation = None if previous is None else {
            'action': YANDEX_ACTION.get(previous.get('navigationMainAction')),
            'toponym': previous.get('navigationNextRoadName') or None,
            'descriptionText': previous.get('description')}
        sections.append({'geometry': {'begin': pos(a), 'end': end(b)}, 'metadata': {'annotation': annotation}})
    if sections:
        sections[0]['geometry']['begin'] = pos(0)
        sections[-1]['geometry']['end'] = end(edges)
    lights = []
    for i, light in enumerate(route.get('trafficLights') or []):
        q = [light['x'], light['y']]
        j = min(range(len(points)), key=lambda n: metres(points[n], q))
        lights.append({'id': 'stock-light-%d' % i, 'position': pos(j)})
    tunnels, start = [], None
    for index, link in enumerate(route['links'] + [{}]):
        if link.get('linktype') == 2 and start is None:
            start = link_range[index][0]
        elif link.get('linktype') != 2 and start is not None:
            tunnels.append({'begin': pos(start), 'end': end(link_range[index - 1][1])})
            start = None
    return {'schema': 'denza-yandex-driving-route-v1', 'routeId': route_id, 'sourceSessionId': session,
            'generation': generation, 'coordinateFrame': 'wgs84', 'pointOrder': 'lon,lat', 'points': points,
            'sections': sections, 'trafficLights': lights, 'tunnels': tunnels,
            'wayPoints': [pos(0), pos(edges)],
            'availability': {'trafficLights': 'known', 'tunnels': 'known', 'wayPoints': 'known', 'sections': 'known'},
            'synthetic': 'from stock NaviToEHPData pathID %s' % route.get('pathID')}


class Projector:
    """Monotone projection of positions onto one polyline (Yandex RoutePosition stand-in)."""

    def __init__(self, points):
        self.points = points
        self.cumulative = [0.0]
        for a, b in zip(points, points[1:]):
            self.cumulative.append(self.cumulative[-1] + metres(a, b))
        self.last = 0.0

    def project(self, q, window_m=400):
        best = None
        lo = bisect.bisect_left(self.cumulative, self.last - 30) - 1
        for i in range(max(0, lo), len(self.points) - 1):
            if self.cumulative[i] > self.last + window_m:
                break
            a, b = self.points[i], self.points[i + 1]
            lat = math.cos(math.radians(a[1]))
            ax, ay, bx, by, qx, qy = a[0] * lat, a[1], b[0] * lat, b[1], q[0] * lat, q[1]
            dx, dy = bx - ax, by - ay
            f = 0.0 if dx == dy == 0 else max(0.0, min(1.0, ((qx - ax) * dx + (qy - ay) * dy) / (dx * dx + dy * dy)))
            p = [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f]
            d = metres(p, q)
            along = self.cumulative[i] + f * (self.cumulative[i + 1] - self.cumulative[i])
            if best is None or d < best[0] - 1e-6:
                best = (d, i, f, along)
        d, i, f, along = best
        if d < 40:
            self.last = max(self.last, along)
        return {'segmentIndex': i, 'segmentPosition': f}, d, along


def stock_routes(trace):
    """(t_ms, NaviToEHPData) for every complete stock route publication."""
    return [(e['t_ms'], e['route']['data'][0]) for e in trace['events']
            if e.get('name') == 'Route' and e.get('route') and e['route'].get('data')]


def synth_samples(trace, routes, session):
    ticks = trace['provenance'].get('gnssTicks') or []
    out, snapshots, sequence = [], {}, 0
    projectors, finished = {}, False
    for tick in ticks:
        current = [r for r in routes if r[0] <= tick['t_ms']]
        if not current:
            continue
        generation = len(current)
        t_route, route = current[-1]
        route_id = 'stock-path-%s-g%d' % (route['pathID'], generation)
        if route_id not in snapshots:
            snapshots[route_id] = snapshot_from_stock(route, route_id=route_id, session=session, generation=generation)
            projectors[route_id] = Projector(snapshots[route_id]['points'])
        projector = projectors[route_id]
        position, gap, along = projector.project(tick['sdk'])
        total = projector.cumulative[-1]
        now = int(round(tick['t_ms'])) + 1_000_000
        sequence += 1
        if finished or (generation == len(routes) and total - along <= ARRIVAL_M):
            # Yandex ends guidance at the destination: the ring then reports mode "off", no route
            # (as the real route-final-m4 ring does after its guidance).
            finished = True
            out.append({'schema': 'denza-yandex-guidance-v1', 'routeId': None, 'sourceSessionId': session,
                        'generation': generation, 'sequence': sequence, 'mode': 'off', 'freshLocation': False,
                        'capturedAtMs': now, 'capturedUptimeMs': now, 't_ms': tick['t_ms'], 'syntheticGapM': 0.0,
                        'syntheticArrival': True})
            continue
        out.append({'schema': 'denza-yandex-guidance-v1', 'routeId': route_id, 'positionRouteId': route_id,
                    'positionMatchesRoute': True, 'sourceSessionId': session, 'generation': generation,
                    'sequence': sequence, 'mode': 'guidance', 'freshLocation': True, 'fixAgeMs': 0,
                    'coordinateFrame': 'wgs84', 'capturedAtMs': now, 'capturedUptimeMs': now,
                    'sourcePolylinePosition': position,
                    'location': {'longitude': tick['sdk'][0], 'latitude': tick['sdk'][1], 'accuracy': 3.0,
                                 'heading': tick.get('course'), 'speed': tick.get('speed_mps'),
                                 'absoluteTimestamp': now, 'relativeTimestamp': now},
                    'distanceToFinishM': max(0.0, total - along), 'timeToFinishS': None,
                    'syntheticGapM': round(gap, 2), 't_ms': tick['t_ms']})
    return out, snapshots


def bench_events(engine_log):
    """LiveBridge receipts -> feed events (decoded with the same decoder as stock fires)."""
    events, assembler = [], RouteAssembler()
    for t_ms, receipt, bench, order in engine_log:
        if bench is not None:
            for name, data in (('Route', bench['binder']), ('PathSelected', bench['selection'])):
                record = decode_event(NAMES[name], data, assembler)
                record.update(t_ms=t_ms, kind='fire', thread='bridge', order=order)
                events.append(record)
                order += 0.01
            events.append(dict(t_ms=t_ms + 0.5, kind='answer', fake=True, name='MMTPathSelected', fields={
                'pathIDResp': bench['payload']['data'][0]['pathID'], 'statusCode': 200, 'mock': 'FakeReceiver'}, order=order))
        packets = (receipt.get('dynamicBinderPackets') or {}).get('packets') or []
        for packet in packets:
            name = {'sdVehicleLocation': 'VehicleLocation', 'TrafficInfo': 'TrafficInfo',
                    'NavigationStatus': 'NavigationStatus'}[packet['name']]
            record = decode_event(NAMES[name], bytes.fromhex(packet['binderProtobufHex']))
            record.update(t_ms=t_ms + 1, kind='fire', thread='bridge', order=order + 0.1,
                          missingAttributes=packet.get('missingAttributes'))
            events.append(record)
        if not packets and receipt.get('navigationStatusBinderHex'):
            record = decode_event(NAMES['NavigationStatus'], bytes.fromhex(receipt['navigationStatusBinderHex']))
            record.update(t_ms=t_ms + 1, kind='fire', thread='bridge', order=order + 0.1)
            events.append(record)
        events.append(dict(t_ms=t_ms, kind='context', name='bridge_tick', order=order,
                           fields={k: receipt.get(k) for k in ('accepted', 'reason', 'sequence', 'sourceGeneration',
                                                               'publicationEpoch')}))
    return events


def cmd_synth(args):
    trace = read_trace(args.stock_trace)
    routes = stock_routes(trace)
    if not routes:
        raise SystemExit('stock trace has no complete route publication')
    session = 'synthetic-from-stock'
    samples, snapshots = synth_samples(trace, routes, session)
    engine = LiveBridge(decode_someip_route(GOLDEN.read_bytes()), 'wgs84')
    log = []
    for order, sample in enumerate(samples):
        before = engine.bench
        receipt = engine.tick(sample, now_ms=sample['capturedUptimeMs'], snapshot=snapshots.get(sample['routeId']))
        log.append((sample['t_ms'], receipt, engine.bench if engine.bench is not before else None, order))
    events = bench_events(log)
    provenance = {'source': 'SYNTHETIC Yandex input from the stock trace (geometry, sections, lights, tunnels, way points '
                            'of each stock route; positions projected on it); identity frame',
                  'stockTrace': str(args.stock_trace), 'samples': len(samples),
                  'maxProjectionGapM': max((s['syntheticGapM'] for s in samples), default=None),
                  'routes': [{'routeId': k, 'sections': len(v['sections']), 'points': len(v['points']),
                              'lights': len(v['trafficLights'])} for k, v in snapshots.items()],
                  'gnssTicks': trace['provenance'].get('gnssTicks')}
    # Keep the stock trace's time base: ticks align one to one.
    out = write_trace(args.out, 'bridge', 'synth:' + Path(args.stock_trace).parent.name, events, provenance, rebase=False)
    print(json.dumps(dict(trace=str(args.out), events=len(out['events']), samples=len(samples),
                          routes=len(snapshots))))


def cmd_yandex(args):
    """capture_replay's loop (recorded uptime, zero delivery delay), keeping every receipt."""
    from .dynamic import status_bytes
    snapshot = read_json(args.snapshot)
    samples = sorted((read_json(p) for p in glob.glob(str(args.progress / 'sample-*.json'))),
                     key=lambda r: r.get('capturedUptimeMs', -1))
    engine = LiveBridge(decode_someip_route(GOLDEN.read_bytes()), args.target_frame)
    log, accepted = [], 0
    for order, sample in enumerate(samples):
        now = sample.get('capturedUptimeMs')
        if sample.get('routeId') != snapshot.get('routeId'):
            engine.receiver.end(now_ms=now)
            status = engine.receiver.status(now_ms=now)
            receipt = {'accepted': False, 'reason': 'cancelled_or_route_not_in_supplied_archive',
                       'navigationStatusBinderHex': status_bytes(status).hex(), 'sequence': sample.get('sequence')}
            log.append((now, receipt, None, order))
            continue
        before = engine.bench
        receipt = engine.tick(sample, now_ms=now, snapshot=snapshot)
        accepted += bool(receipt.get('accepted'))
        log.append((now, receipt, engine.bench if engine.bench is not before else None, order))
    events = bench_events(log)
    provenance = {'source': 'REAL Yandex Navigator snapshot and native guidance ring (instrumented emulator build)',
                  'snapshot': str(args.snapshot), 'progress': str(args.progress), 'targetFrame': args.target_frame,
                  'samples': len(samples), 'accepted': accepted,
                  'gnssTicks': [{'t_ms': s.get('capturedUptimeMs'), 'mode': s.get('mode'),
                                 'speed_mps': (s.get('location') or {}).get('speed')} for s in samples]}
    out = write_trace(args.out, 'bridge', 'yandex:' + args.snapshot.parent.name, events, provenance)
    origin = min((s.get('capturedUptimeMs') for s in samples if s.get('capturedUptimeMs') is not None), default=0)
    for tick in out['provenance']['gnssTicks']:
        tick['t_ms'] = None if tick['t_ms'] is None else tick['t_ms'] - origin
    print(json.dumps(dict(trace=str(args.out), events=len(out['events']), samples=len(samples), accepted=accepted)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('synth')
    p.add_argument('--stock-trace', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.set_defaults(func=cmd_synth)
    p = sub.add_parser('yandex')
    p.add_argument('--snapshot', type=Path, required=True)
    p.add_argument('--progress', type=Path, required=True)
    p.add_argument('--target-frame', default='native-gcj-unrestricted-candidate')
    p.add_argument('--out', type=Path, required=True)
    p.set_defaults(func=cmd_yandex)
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
