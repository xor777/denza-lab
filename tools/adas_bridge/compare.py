#!/usr/bin/env python3
"""ADAS bench comparator: a stock feed trace against a bridge feed trace (feed.py format).

Two separate judgements, as the owner set them (2026-10-23):

* DYNAMICS / PROTOCOL - the stock code is the reference: which messages, how often,
  in what order, the route -> selection -> answer handshake, pathId lifecycle, the
  per-tick position/segment/link/offset semantics, NavigationStatus, reroutes,
  arrival and the idle state after it.
* CONTENT - judged against Amap's own semantics (coverage.py enums, the stock
  consumers' expectations), for BOTH sides. A stock value is a content reference
  only when the stock ran on factory data (`--content-reference`, e.g. the car's
  Beijing package); on our OSM-derived packages a stock/bridge content difference is
  never by itself a bridge defect.

Every difference gets one class:
  bridge-defect            the bridge's own logic departs from the stock protocol or Amap semantics
  source-data-gap          Yandex (or the synthetic Yandex input) has no source for the field (expected)
  stock-not-modelled       the stock sends a message/behaviour the bridge has no model for yet
  reference-not-authoritative  content differs, but the stock side ran on OSM-derived data
  stock-semantics          the stock side itself departs from Amap semantics (data/package issue)
  info                     recorded, not a difference

    python3 -m tools.adas_bridge.compare scenario RUN_DIR [--content-reference]
        (RUN_DIR/stock-trace.json; RUN_DIR/bridge-trace.json is synthesised when absent)
    python3 -m tools.adas_bridge.compare traces --stock S.json --bridge B.json --out DIR [--content-reference]
"""
from __future__ import annotations

import argparse
import bisect
import json
import math
from pathlib import Path
import statistics

from . import coverage
from .feed import read_trace

LINK_FIELDS = ('roadclass', 'formway', 'linktype', 'laneNum', 'direction', 'ownership', 'adminCode', 'hasTrafficLight',
               'hasParallel', 'hasMixFork', 'hasMultiOut', 'mainAction', 'assistantAction', 'speedLimit', 'roadname')
SEGMENT_FIELDS = ('navigationMainAction', 'navigationAssitAction', 'isArriveWayPoint', 'trafficLightNum',
                  'navigationNextRoadName', 'description')
# Fields Yandex has no inspected source for (research/adas-bridge/README.md, "NaviToEHPData field by field").
YANDEX_HAS_NO_SOURCE = {'roadclass', 'formway', 'laneNum', 'direction', 'ownership', 'adminCode', 'hasParallel',
                        'hasMixFork', 'hasMultiOut', 'assistantAction', 'navigationAssitAction', 'roadname',
                        'linktype', 'currentRoadOwnership', 'roadClass'}
TICK_POSITION = ('curStepId', 'curLinkId', 'linkOffset', 'distanceToNextStep', 'vehicleSpeed', 'gPSspeed', 'reserve4',
                 'currentRoadOwnership')
TICK_TRAFFIC = ('roadClass', 'distToDsttn', 'timeToDsttn', 'spdLmtSpeedValue', 'offCourse', 'trafficJamDist',
                'trafficJamStatus')


def metres(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * 111320.0 * math.cos(lat), (b[1] - a[1]) * 110574.0)


def fires(trace, name=None):
    return [e for e in trace['events'] if e['kind'] in ('fire', 'client_send') and (name is None or e['name'] == name)]


def routes(trace):
    return [e for e in fires(trace, 'Route') if e.get('route')]


# --------------------------------------------------------------------------- dynamics

def cadence(events):
    times = [e['t_ms'] for e in events]
    gaps = [b - a for a, b in zip(times, times[1:])]
    if not gaps:
        return dict(count=len(times))
    return dict(count=len(times), medianMs=round(statistics.median(gaps), 1),
                p90Ms=round(sorted(gaps)[int(0.9 * (len(gaps) - 1))], 1), maxMs=round(max(gaps), 1),
                jitterMs=round(statistics.pstdev(gaps), 1), firstMs=round(times[0], 1), lastMs=round(times[-1], 1))


def inventory(stock, bridge):
    names = sorted({e['name'] for e in fires(stock)} | {e['name'] for e in fires(bridge)})
    rows, diffs = [], []
    for name in names:
        s, b = cadence(fires(stock, name)), cadence(fires(bridge, name))
        rows.append(dict(name=name, stock=s, bridge=b))
        if s['count'] and not b['count']:
            diffs.append(diff('dynamics', 'stock-not-modelled', f'{name}: stock sends {s["count"]} (median {s.get("medianMs")} ms), bridge never',
                              name=name))
        elif b['count'] and not s['count']:
            diffs.append(diff('dynamics', 'bridge-defect', f'{name}: bridge sends it, stock never does in this scenario', name=name))
        elif s.get('medianMs') and b.get('medianMs') and abs(s['medianMs'] - b['medianMs']) > 250:
            diffs.append(diff('dynamics', 'bridge-defect', f'{name}: cadence {b["medianMs"]} ms vs stock {s["medianMs"]} ms', name=name))
    return rows, diffs


def diff(area, cls, text, **detail):
    return dict(area=area, cls=cls, text=text, **detail)


def status_timeline(trace):
    out, last = [], None
    for e in fires(trace, 'NavigationStatus'):
        value = e['fields'].get('navigationStatus', 0)
        if value != last:
            out.append([round(e['t_ms']), value])
            last = value
    return out


def path_timeline(trace):
    out, last = [], None
    for e in fires(trace, 'VehicleLocation'):
        value = e['fields'].get('curSDRouteID', 0.0)
        if value != last:
            out.append([round(e['t_ms']), value])
            last = value
    return out


def handshake(trace):
    """Per route publication: chunks, selection delay, answer delay, resends of the same pathID."""
    out, seen = [], {}
    events = trace['events']
    for e in routes(trace):
        path = e['route']['data'][0]['pathID']
        seen[path] = seen.get(path, 0) + 1
        sel = next((x for x in events if x['t_ms'] >= e['t_ms'] and x.get('name') == 'PathSelected'), None)
        ans = next((x for x in events if sel and x['t_ms'] >= sel['t_ms'] and x.get('name') == 'MMTPathSelected'), None)
        first_pos = next((x for x in fires(trace, 'VehicleLocation') if x['t_ms'] >= e['t_ms']
                          and x['fields'].get('curSDRouteID') == float(path)), None)
        out.append(dict(t=round(e['t_ms']), pathID=path, publication=seen[path], links=len(e['route']['data'][0]['links']),
                        chunks=(e.get('chunk') or {}).get('length'), jsonBytes=e.get('jsonBytes'),
                        selectionDelayMs=None if sel is None else round(sel['t_ms'] - e['t_ms'], 1),
                        selectionPathMatches=None if sel is None else sel['fields'].get('pathIDReq') == path,
                        answerDelayMs=None if ans is None else round(ans['t_ms'] - sel['t_ms'], 1),
                        firstPositionOnPathMs=None if first_pos is None else round(first_pos['t_ms'] - e['t_ms'], 1),
                        envelope={k: v for k, v in e['route'].items() if k != 'data'}))
    return out


def lifecycle(stock, bridge):
    s_hand, b_hand = handshake(stock), handshake(bridge)
    s_status, b_status = status_timeline(stock), status_timeline(bridge)
    diffs = []
    if len(s_hand) != len(b_hand):
        diffs.append(diff('dynamics', 'bridge-defect' if len(b_hand) < len(s_hand) else 'info',
                          f'route publications: stock {len(s_hand)}, bridge {len(b_hand)}'))
    for s, b in zip(s_hand, b_hand):
        if s['selectionDelayMs'] is not None and b['selectionDelayMs'] is not None and abs(s['selectionDelayMs'] - b['selectionDelayMs']) > 100:
            diffs.append(diff('dynamics', 'info', f'selection {b["selectionDelayMs"]} ms after route vs stock {s["selectionDelayMs"]} ms'))
        if s['envelope'] != b['envelope']:
            keys = sorted(k for k in set(s['envelope']) | set(b['envelope']) if s['envelope'].get(k) != b['envelope'].get(k))
            diffs.append(diff('content', 'bridge-defect' if set(keys) - {'RouteType'} else 'info', 'MessageSend envelope differs '
                              '(bridge copies the car golden template): ' + ', '.join(
                f'{k}: stock {s["envelope"].get(k)!r} bridge {b["envelope"].get(k)!r}' for k in keys), keys=keys))
        if s['firstPositionOnPathMs'] is not None and b['firstPositionOnPathMs'] is not None:
            delta = b['firstPositionOnPathMs'] - s['firstPositionOnPathMs']
            if abs(delta) > 1500:
                diffs.append(diff('dynamics', 'bridge-defect', f'position switches to the new pathId {b["firstPositionOnPathMs"]} ms '
                                  f'after the route, stock {s["firstPositionOnPathMs"]} ms'))
    s_values, b_values = [v for _, v in s_status], [v for _, v in b_status]
    if s_values != b_values:
        diffs.append(diff('dynamics', 'bridge-defect' if set(s_values) - set(b_values) else 'info',
                          f'NavigationStatus sequence stock {s_values} vs bridge {b_values}'))
    if s_status and b_status and s_status[0][1] == 100 and b_status[0][1] != 100:
        diffs.append(diff('dynamics', 'stock-not-modelled', 'stock asserts NavigationStatus 100 (idle) before any route; bridge is silent until its first route'))
    s_tail, b_tail = tail_after_end(stock), tail_after_end(bridge)
    gnss = stock['provenance'].get('gnssTicks') or []
    stop = next((e['t_ms'] for e in stock['events'] if e.get('name') in ('adas_navi_arrive', 'adas_presenter_stop')), None)
    if gnss and stop is not None and stop > gnss[-1]['t_ms']:
        diffs.append(diff('dynamics', 'info', 'guidance end not exercised inside the GNSS window (the run stopped after the feed)'))
    elif s_tail != b_tail:
        diffs.append(diff('dynamics', 'bridge-defect', f'after guidance end: stock {s_tail}, bridge {b_tail}'))
    return dict(stockHandshake=s_hand, bridgeHandshake=b_hand, stockStatus=s_status, bridgeStatus=b_status,
                stockPath=path_timeline(stock), bridgePath=path_timeline(bridge), stockAfterEnd=s_tail,
                bridgeAfterEnd=b_tail), diffs


def tail_after_end(trace):
    """What keeps coming after the last 101: statuses, position pathId, cadence."""
    status = fires(trace, 'NavigationStatus')
    last101 = max((e['t_ms'] for e in status if e['fields'].get('navigationStatus') == 101), default=None)
    if last101 is None:
        return None
    after = [e for e in fires(trace) if e['t_ms'] > last101]
    values = []
    for e in status:
        if e['t_ms'] > last101:
            v = e['fields'].get('navigationStatus', 0)
            if not values or values[-1][0] != v:
                values.append([v, round(e['t_ms'] - last101)])
    paths = sorted({e['fields'].get('curSDRouteID', 0.0) for e in after if e['name'] == 'VehicleLocation'})
    return dict(statuses=values, positionPathIds=paths, messages=sorted({e['name'] for e in after}))


# --------------------------------------------------------------------------- content

def link_spans(route):
    out, total = [], 0.0
    for i, link in enumerate(route['links']):
        out.append((total, total + (link.get('len') or 0), i))
        total += link.get('len') or 0
    return out, total


def at_distance(spans, distance):
    starts = [s for s, _, _ in spans]
    i = max(0, bisect.bisect_right(starts, distance) - 1)
    return spans[i][2]


def semantics(route, side):
    """Amap-semantics checks on one NaviToEHPData; returns findings (never compares sides)."""
    out = []
    links, pnts = route.get('links') or [], route.get('pnts') or []
    enums = (('roadclass', coverage.ROAD_CLASS), ('formway', coverage.FORMWAY), ('linktype', coverage.LINK_TYPE))
    for key, enum in enums:
        bad = [i for i, l in enumerate(links) if l.get(key) not in enum or l.get(key) == -1]
        if bad:
            out.append(dict(side=side, rule=f'{key} must be an SDK {key} value (not null / NULL sentinel)',
                            links=len(bad), of=len(links), example=links[bad[0]].get(key)))
    # Factory exports carry laneNum 0 until guidance updates the path (plan-time and first sends);
    # a non-integer is never an SDK value.
    bad = [i for i, l in enumerate(links) if not isinstance(l.get('laneNum'), int) or l['laneNum'] < 0]
    if bad:
        out.append(dict(side=side, rule='laneNum: integer lane count in the travel direction (0 = not yet known to the SDK)',
                        links=len(bad), of=len(links), example=links[bad[0]].get('laneNum')))
    bad = [i for i, l in enumerate(links) if l.get('direction') not in (0, 1)]
    if bad:
        out.append(dict(side=side, rule='direction: exported 0/1 (raw != 3)', links=len(bad), of=len(links),
                        example=links[bad[0]].get('direction')))
    bad = [i for i, l in enumerate(links) if l.get('ownership') not in (0, 1)]
    if bad:
        out.append(dict(side=side, rule='ownership: exported 0 public / 1 not public', links=len(bad), of=len(links),
                        example=links[bad[0]].get('ownership')))
    bad = [i for i, l in enumerate(links) if not isinstance(l.get('adminCode'), int)]
    if bad:
        out.append(dict(side=side, rule='adminCode: integer adcode', links=len(bad), of=len(links)))
    for key in ('hasParallel', 'hasMixFork', 'hasMultiOut', 'hasTrafficLight'):
        bad = [i for i, l in enumerate(links) if not isinstance(l.get(key), bool)]
        if bad:
            out.append(dict(side=side, rule=f'{key}: boolean', links=len(bad), of=len(links), example=links[bad[0]].get(key)))
    bad = [i for i, l in enumerate(links) if l.get('speedLimit') != 0]
    if bad:
        out.append(dict(side=side, rule='speedLimit: the stock serializer always writes 0', links=len(bad), of=len(links)))
    # Lights belong to the incoming link: every light coordinate at the END of a hasTrafficLight link.
    lights = route.get('trafficLights') or []
    ends = []
    for l in links:
        if l.get('hasTrafficLight') and l.get('pntCnt'):
            p = pnts[l['pntBegIdx'] + l['pntCnt'] - 1]
            ends.append([p['x'], p['y']])
    off = [q for q in lights if not ends or min(metres([q['x'], q['y']], e) for e in ends) > 10]
    if off:
        out.append(dict(side=side, rule='traffic light at the end of its incoming link (hasTrafficLight)', lights=len(off),
                        of=len(lights)))
    if len(ends) != len(lights):
        out.append(dict(side=side, rule='one trafficLights[] entry per hasTrafficLight link', flagged=len(ends), lights=len(lights)))
    # Link geometry: len equals the polyline length of its points.
    bad = []
    for i, l in enumerate(links):
        pts = [[p['x'], p['y']] for p in pnts[l['pntBegIdx']:l['pntBegIdx'] + l['pntCnt']]]
        length = sum(metres(a, b) for a, b in zip(pts, pts[1:]))
        if isinstance(l.get('len'), (int, float)) and abs(length - l['len']) > max(8, 0.10 * l['len']):
            bad.append(i)
    if bad:
        out.append(dict(side=side, rule='link len = polyline length of its points', links=len(bad), of=len(links)))
    # Every link carries the action at its end; the segment's last link carries the segment action.
    groups = route.get('guideGroups') or []
    final = (groups[-1].get('segments') or []) if groups else []
    if final and (final[-1].get('navigationAssitAction') != coverage.ASSIST_ARRIVE or final[-1].get('isArriveWayPoint') is not True):
        out.append(dict(side=side, rule='last segment of the route: assistant action 36 ArriveDestination, isArriveWayPoint',
                        value=[final[-1].get('navigationAssitAction'), final[-1].get('isArriveWayPoint')]))
    segs_all = [x for g in groups for x in g.get('segments') or []]
    bad = [x for x in segs_all[:-1] if not isinstance(x.get('isArriveWayPoint'), bool)]
    if bad:
        out.append(dict(side=side, rule='isArriveWayPoint: boolean on every segment (false before the destination)',
                        segments=len(bad), of=len(segs_all)))
    for g in groups:
        nulls = [k for k in ('groupName', 'groupTime', 'groupIconType') if g.get(k) is None]
        if nulls:
            out.append(dict(side=side, rule='guide group fields populated', missing=nulls))
    # Divided roads: two one-way links (formway 1 with direction 1).
    divided = [l for l in links if l.get('formway') == 1]
    if divided and any(l.get('direction') != 1 for l in divided):
        out.append(dict(side=side, rule='divided road (formway 1) digitised as one-way links (direction 1)',
                        links=sum(l.get('direction') != 1 for l in divided), of=len(divided)))
    if route.get('viaRoadInfo') is None:
        out.append(dict(side=side, rule='viaRoadInfo: list (stock writes [] when empty)', value=None))
    return out


def route_content(stock_route, bridge_route, *, content_reference):
    s_spans, s_total = link_spans(stock_route)
    b_spans, b_total = link_spans(bridge_route)
    per_field, diffs = {}, []
    for start, end, i in s_spans:
        middle = (start + end) / 2 * (b_total / s_total if s_total else 1)
        j = at_distance(b_spans, middle)
        s_link, b_link = stock_route['links'][i], bridge_route['links'][j]
        for key in LINK_FIELDS:
            a, b = s_link.get(key), b_link.get(key)
            row = per_field.setdefault(key, dict(agree=0, total=0, pairs={}))
            row['total'] += 1
            row['agree'] += a == b
            if a != b:
                pair = f'{a!r}->{b!r}'
                row['pairs'][pair] = row['pairs'].get(pair, 0) + 1
    for key, row in per_field.items():
        if row['agree'] == row['total']:
            continue
        if key in YANDEX_HAS_NO_SOURCE:
            cls = 'source-data-gap'
        elif key in ('hasTrafficLight', 'mainAction', 'speedLimit'):
            cls = 'bridge-defect' if content_reference or key != 'hasTrafficLight' else 'reference-not-authoritative'
        else:
            cls = 'bridge-defect' if content_reference else 'reference-not-authoritative'
        top = sorted(row['pairs'].items(), key=lambda kv: -kv[1])[:4]
        diffs.append(diff('content', cls, f'link {key}: {row["agree"]}/{row["total"]} stock links agree; ' +
                          ', '.join(f'{p} x{n}' for p, n in top), field=key))
    structure = dict(stockLinks=len(stock_route['links']), bridgeLinks=len(bridge_route['links']),
                     stockLengthM=round(s_total, 1), bridgeLengthM=round(b_total, 1),
                     stockPoints=stock_route.get('pntCnt'), bridgePoints=bridge_route.get('pntCnt'),
                     stockLights=len(stock_route.get('trafficLights') or []), bridgeLights=len(bridge_route.get('trafficLights') or []),
                     stockSegments=sum(len(g.get('segments') or []) for g in stock_route.get('guideGroups') or []),
                     bridgeSegments=sum(len(g.get('segments') or []) for g in bridge_route.get('guideGroups') or []))
    if structure['bridgeLinks'] * 2 < structure['stockLinks']:
        diffs.append(diff('content', 'bridge-defect', f'link digitisation: bridge cuts {structure["bridgeLinks"]} links where the '
                          f'stock route has {structure["stockLinks"]} (stock links are road links between junctions; '
                          'bridge cuts only at manoeuvres/ranges)'))
    if abs(s_total - b_total) > max(5, 0.01 * s_total):
        diffs.append(diff('content', 'info', f'route length stock {s_total:.0f} m, bridge {b_total:.0f} m'))
    s_segs = [s for g in stock_route.get('guideGroups') or [] for s in g.get('segments') or []]
    b_segs = [s for g in bridge_route.get('guideGroups') or [] for s in g.get('segments') or []]
    seg_fields = {}
    for k, s in enumerate(s_segs):
        if k >= len(b_segs):
            break
        b = b_segs[k]
        for key in SEGMENT_FIELDS + ('navigationLen',):
            a, c = s.get(key), b.get(key)
            if key == 'navigationLen' and isinstance(a, (int, float)) and isinstance(c, (int, float)):
                same = abs(a - c) <= 3
            else:
                same = a == c
            row = seg_fields.setdefault(key, dict(agree=0, total=0, pairs={}))
            row['total'] += 1
            row['agree'] += same
            if not same:
                pair = f'{a!r}->{c!r}' if key != 'navigationLen' else 'differs>3m'
                row['pairs'][pair] = row['pairs'].get(pair, 0) + 1
    for key, row in seg_fields.items():
        if row['agree'] == row['total']:
            continue
        cls = 'source-data-gap' if key in YANDEX_HAS_NO_SOURCE else 'bridge-defect'
        top = sorted(row['pairs'].items(), key=lambda kv: -kv[1])[:4]
        diffs.append(diff('content', cls, f'segment {key}: {row["agree"]}/{row["total"]} agree; ' +
                          ', '.join(f'{p} x{n}' for p, n in top), field=key))
    if len(s_segs) != len(b_segs):
        diffs.append(diff('content', 'bridge-defect', f'guide segments: stock {len(s_segs)}, bridge {len(b_segs)}'))
    top = {k: stock_route.get(k) for k in stock_route if k not in ('links', 'pnts', 'guideGroups', 'trafficLights')}
    btop = {k: bridge_route.get(k) for k in bridge_route if k not in ('links', 'pnts', 'guideGroups', 'trafficLights')}
    missing = sorted(set(top) ^ set(btop))
    if missing:
        diffs.append(diff('content', 'bridge-defect', 'NaviToEHPData keys differ: ' + ', '.join(missing)))
    link_keys = sorted(set().union(*[l.keys() for l in stock_route['links']]) ^ set().union(*[l.keys() for l in bridge_route['links']]))
    if link_keys:
        diffs.append(diff('content', 'bridge-defect', 'LinkItem keys differ: ' + ', '.join(link_keys)))
    return dict(structure=structure, linkFields=per_field, segmentFields=seg_fields), diffs


# --------------------------------------------------------------------------- per tick

def along(route, point):
    """Along-route distance of the nearest route point (for tick alignment and transitions)."""
    best, acc, prev = None, 0.0, None
    for link in route['links']:
        for k in range(link['pntBegIdx'], link['pntBegIdx'] + link['pntCnt']):
            p = [route['pnts'][k]['x'], route['pnts'][k]['y']]
            if prev is not None and p != prev:
                acc += metres(prev, p)
            d = metres(p, point)
            if best is None or d < best[0]:
                best = (d, acc)
            prev = p
    return best[1] if best else None


def ticks(stock, bridge, s_route_by_path, b_route_by_path):
    s_pos, b_pos = fires(stock, 'VehicleLocation'), fires(bridge, 'VehicleLocation')
    s_tr, b_tr = fires(stock, 'TrafficInfo'), fires(bridge, 'TrafficInfo')
    b_times = [e['t_ms'] for e in b_pos]
    rows = []
    for e in s_pos:
        if not b_times:
            break
        j = bisect.bisect_left(b_times, e['t_ms'] - 1000)
        candidates = [b_pos[k] for k in range(j, min(len(b_pos), j + 3)) if abs(b_pos[k]['t_ms'] - e['t_ms']) <= 1000]
        b = min(candidates, key=lambda x: abs(x['t_ms'] - e['t_ms'])) if candidates else None
        s_traffic = min(s_tr, key=lambda x: abs(x['t_ms'] - e['t_ms'])) if s_tr else None
        b_traffic = min(b_tr, key=lambda x: abs(x['t_ms'] - b['t_ms'])) if (b and b_tr) else None
        row = dict(t=round(e['t_ms']), stock={k: e['fields'].get(k, 0) for k in TICK_POSITION},
                   bridge=None if b is None else {k: b['fields'].get(k, 0) for k in TICK_POSITION},
                   stockPath=e['fields'].get('curSDRouteID', 0.0), bridgePath=None if b is None else b['fields'].get('curSDRouteID', 0.0))
        if s_traffic:
            row['stock'].update({k: s_traffic['fields'].get(k, 0) for k in TICK_TRAFFIC})
        if b_traffic:
            row['bridge'].update({k: b_traffic['fields'].get(k, 0) for k in TICK_TRAFFIC})
        sm = [e['fields'].get('locationLongitudeAssociateRoad'), e['fields'].get('locationLatitudeAssociateRoad')]
        if b is not None:
            bm = [b['fields'].get('locationLongitudeAssociateRoad'), b['fields'].get('locationLatitudeAssociateRoad')]
            if None not in sm + bm:
                row['matchedGapM'] = round(metres(sm, bm), 2)
        route = s_route_by_path.get(e['fields'].get('curSDRouteID'))
        if route and None not in sm:
            row['alongM'] = along(route, sm)
        rows.append(row)
    return rows


def tick_diffs(rows):
    diffs, stats = [], {}
    on = [r for r in rows if r['bridge'] is not None and r['stockPath'] not in (-1.0, 0.0)]
    for key in TICK_POSITION + TICK_TRAFFIC:
        pairs = [(r['stock'].get(key), r['bridge'].get(key)) for r in on]
        if not pairs:
            continue
        if key in ('linkOffset', 'distanceToNextStep', 'distToDsttn', 'timeToDsttn'):
            errors = [abs((a or 0) - (b or 0)) for a, b in pairs]
            # The stock sends ~0.4-0.9 s after the fix it matched; allow that much travel (+10 m).
            tolerance = [10 + 1.0 * (r['stock'].get('vehicleSpeed') or 0) / 3.6 for r in on]
            stats[key] = dict(ticks=len(pairs), medianAbsErr=statistics.median(errors), maxAbsErr=max(errors),
                              withinTolerance=sum(e <= t for e, t in zip(errors, tolerance)),
                              tolerance='10 m + 1 s of travel' if key != 'timeToDsttn' else '10 s + speed term')
            bad = stats[key]['ticks'] - stats[key]['withinTolerance']
            if bad > 0.2 * len(pairs):
                cls = 'source-data-gap' if key == 'timeToDsttn' else 'bridge-defect'
                diffs.append(diff('dynamics', cls, f'{key}: {bad}/{len(pairs)} on-route ticks differ beyond 10 m + 1 s of travel '
                                  f'(median |err| {stats[key]["medianAbsErr"]}, max {stats[key]["maxAbsErr"]})', field=key))
        else:
            agree = sum(a == b for a, b in pairs)
            stats[key] = dict(ticks=len(pairs), agree=agree, examples=sorted({f'{a}->{b}' for a, b in pairs if a != b})[:5])
            if agree < len(pairs):
                cls = 'source-data-gap' if key in YANDEX_HAS_NO_SOURCE else 'bridge-defect'
                if key in ('curStepId', 'curLinkId') and agree >= 0.9 * len(pairs):
                    cls = 'info'
                diffs.append(diff('dynamics', cls, f'{key}: {agree}/{len(pairs)} on-route ticks agree; e.g. ' +
                                  ', '.join(stats[key]['examples'][:3]), field=key))
    gaps = [r['matchedGapM'] for r in on if 'matchedGapM' in r]
    if gaps:
        stats['matchedPointGapM'] = dict(median=statistics.median(gaps), max=max(gaps))
    off = [r for r in rows if r['stockPath'] not in (-1.0, 0.0) and r['bridge'] is None]
    if off:
        diffs.append(diff('dynamics', 'bridge-defect', f'{len(off)} stock position ticks (on a path) have no bridge position '
                          'within 1 s (bridge feed stopped or rejected samples)'))
    return stats, diffs


def transitions_report(stock_route, bridge_route, rows, window=150):
    out = []
    s_spans, _ = link_spans(stock_route)
    b_spans, _ = link_spans(bridge_route) if bridge_route else ([], 0)
    for hit in coverage.transitions(stock_route):
        lo, hi = hit['distM'] - window, hit['distM'] + window
        s_links = [dict({k: stock_route['links'][i].get(k) for k in ('roadclass', 'formway', 'linktype', 'laneNum', 'hasTrafficLight',
                                                                      'hasParallel', 'hasMixFork', 'mainAction', 'len')}, i=i)
                   for s, e, i in s_spans if e >= lo and s <= hi]
        b_links = [dict({k: bridge_route['links'][i].get(k) for k in ('roadclass', 'formway', 'linktype', 'laneNum', 'hasTrafficLight',
                                                                       'hasParallel', 'hasMixFork', 'mainAction', 'len')}, i=i)
                   for s, e, i in b_spans if e >= lo and s <= hi] if bridge_route else []
        passing = [r for r in rows if r.get('alongM') is not None and lo <= r['alongM'] <= hi]
        out.append(dict(hit, stockLinks=s_links, bridgeLinks=b_links,
                        ticks=[dict(t=r['t'], alongM=round(r['alongM']), stock={k: r['stock'].get(k) for k in
                                    ('curStepId', 'curLinkId', 'linkOffset', 'distanceToNextStep', 'roadClass')},
                                    bridge=None if r['bridge'] is None else {k: r['bridge'].get(k) for k in
                                    ('curStepId', 'curLinkId', 'linkOffset', 'distanceToNextStep', 'roadClass')}) for r in passing]))
    return out


def extras_around(trace, kinds=('NextAction', 'SpeedIntersection', 'NextIntersectionLanes', 'Lanes3km', 'TrafficLight',
                                'TidalLane', 'Intersection', 'MixFork', 'ServiceAreaToll', 'RoadFacilities', 'Tunnel')):
    """Value histograms of the stock's guidance extras (what the bridge does not model)."""
    out = {}
    for name in kinds:
        values = {}
        for e in fires(trace, name):
            key = json.dumps({k: v for k, v in e['fields'].items() if not isinstance(v, (list, dict))}, sort_keys=True)
            values[key] = values.get(key, 0) + 1
        if values:
            out[name] = dict(count=sum(values.values()), distinct=len(values),
                             top=sorted(values.items(), key=lambda kv: -kv[1])[:5])
    return out


# --------------------------------------------------------------------------- road-type profile (cross-city)

PROFILE_LINK = ('roadclass', 'formway', 'linktype', 'laneNum', 'direction', 'ownership', 'hasTrafficLight', 'hasParallel',
                'hasMixFork', 'hasMultiOut', 'mainAction', 'assistantAction', 'adminCode', 'speedLimit')
PROFILE_TICK = {'TrafficInfo': ('roadClass', 'spdLmtSpeedValue', 'offCourse'),
                'NextAction': ('iconType', 'mainAction', 'assistantAction', 'cameraType'),
                'SpeedIntersection': ('historySpeed', 'trafficFlowSpeed', 'hasParallelRoad', 'hasMixFork', 'hasLongSolidLane'),
                'TrafficLight': ('trafficLightExist', 'lightStateType'),
                'TidalLane': ('rampRmnd', 'tidalLane'),
                'NextIntersectionLanes': ('segmentIndex',),
                'VehicleLocation': ('currentRoadOwnership',)}


def global_link(route, step, link):
    segments = [s for g in route.get('guideGroups') or [] for s in g.get('segments') or []]
    if step is None or not 0 <= step < len(segments):
        return None
    index = segments[step]['linkBegIdx'] + (link or 0)
    return index if 0 <= index < len(route['links']) else None


def histogram(rows):
    out = {}
    for key, value in rows:
        bucket = out.setdefault(key, {})
        name = json.dumps(value)
        bucket[name] = bucket.get(name, 0) + 1
    return {k: dict(sorted(v.items(), key=lambda kv: -kv[1])[:6]) for k, v in out.items()}


def profile(trace, groups_of=None):
    """Per road group: route-link field histograms and what arrives per tick while on that group."""
    by_path = {float(r['route']['data'][0]['pathID']): r['route']['data'][0] for r in routes(trace)}
    links_rows, tick_rows, tick_counts, kinds = {}, {}, {}, {}
    for route in by_path.values():
        groups = groups_of(route) if groups_of else [coverage.road_group(l) for l in route['links']]
        for link, group in zip(route['links'], groups):
            links_rows.setdefault(group, []).extend((k, link.get(k)) for k in PROFILE_LINK)
    positions = fires(trace, 'VehicleLocation')
    others = {name: fires(trace, name) for name in PROFILE_TICK}
    for e in positions:
        route = by_path.get(e['fields'].get('curSDRouteID'))
        if route is None:
            continue
        index = global_link(route, e['fields'].get('curStepId', 0), e['fields'].get('curLinkId', 0))
        if index is None:
            continue
        group = (groups_of(route) if groups_of else [coverage.road_group(l) for l in route['links']])[index]
        tick_counts[group] = tick_counts.get(group, 0) + 1
        for name, keys in PROFILE_TICK.items():
            near = [x for x in others[name] if abs(x['t_ms'] - e['t_ms']) <= 600]
            if near:
                kinds.setdefault(group, {}).setdefault(name, 0)
                kinds[group][name] += 1
                tick_rows.setdefault(group, []).extend((name + '.' + k, near[0]['fields'].get(k, 0)) for k in keys)
    out = {}
    for group in sorted(set(links_rows) | set(tick_counts)):
        out[group] = dict(links=len(links_rows.get(group, [])) // len(PROFILE_LINK), ticks=tick_counts.get(group, 0),
                          linkFields=histogram(links_rows.get(group, [])),
                          tickPresence={k: round(v / tick_counts[group], 2) for k, v in (kinds.get(group) or {}).items()},
                          tickFields=histogram(tick_rows.get(group, [])))
    return out


def yandex_groups(snapshot_path, target_frame, roads=None, trace_roads=None):
    """Bridge links grouped by what Yandex itself says (highway/tunnel ranges, lights); class is unknown.

    roads: a road store to group by instead: every link by the OSM road under its middle as the
    matcher places the route ('osm-' + coverage.road_group, 'osm-unmatched'), the same grouping for
    a trace converted with or without roads. trace_roads: the store the trace itself was converted
    with (its link table), None for a Yandex-only trace.
    """
    from .bridge import route_convert
    snapshot = json.loads(Path(snapshot_path).read_text())
    converted = route_convert(snapshot, target_frame=target_frame, roads=trace_roads)
    if roads is not None:
        from . import roads as roads_module, stock
        geometry = converted['stockGeometry']
        match = roads_module.match(stock.route_from_snapshot(snapshot), geometry['cumulative'], roads)
        groups = []
        for begin, end in zip(geometry['linkBeginScalar'], geometry['linkEndScalar']):
            road = None if match is None else match.fragment_at((begin + end) / 2)
            groups.append('osm-unmatched' if road is None else
                          'osm-' + coverage.road_group({'roadclass': road['roadClass'], 'formway': road['formway']}))
        return lambda route: groups if len(groups) == len(route['links']) else ['unknown'] * len(route['links'])
    groups = []
    for link in converted['intermediate']['links']:
        member = link['rangeMembership']
        groups.append('yandex-highway' if member.get('highways') else 'yandex-tunnel' if member.get('tunnels') else
                      'yandex-signalised' if link.get('trafficLightCount') else 'yandex-unclassified')
    return lambda route: groups if len(groups) == len(route['links']) else ['unknown'] * len(route['links'])


def cmd_profile(args):
    from .roads import RoadStore
    result = {}
    stores = {}

    def store(path):
        if path and path not in stores:
            stores[path] = RoadStore(Path(path).read_bytes())
        return stores.get(path)

    for spec in args.stock or []:
        result['stock:' + spec] = profile(read_trace(spec))
    for spec in args.bridge or []:
        trace = read_trace(spec)
        snapshot = trace['provenance'].get('snapshot')
        groups_of = yandex_groups(snapshot, trace['provenance'].get('targetFrame', 'wgs84'), store(args.roads and str(args.roads)),
                                  store(trace['provenance'].get('roads'))) if snapshot else None
        result['bridge:' + spec] = profile(trace, groups_of)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1) + '\n')
    print(json.dumps({k: {g: dict(links=v['links'], ticks=v['ticks']) for g, v in p.items()} for k, p in result.items()}))


def cmd_matrix(args):
    """Coverage matrix over scenario compare results: per transition kind, what each side sends."""
    rows, classes = {}, {}

    def fmt(link):
        return '%s/%s/%s/%s/%d' % (link['roadclass'], link['formway'], link['linktype'], link['laneNum'],
                                    int(bool(link['hasTrafficLight'])))
    for run in args.runs:
        result = json.loads((run / 'compare/compare.json').read_text())
        for d in result['differences']:
            key = (d['cls'], d['text'].split(':')[0][:80])
            classes.setdefault(key, set()).add(run.name)
        for t in result['transitions']:
            row = rows.setdefault(t['kind'], dict(scenarios=set(), hits=0, stock=set(), bridge=set(), ticks=0,
                                                  tickStock=set(), tickBridge=set()))
            row['scenarios'].add(run.name)
            row['hits'] += 1
            row['stock'].update(fmt(l) for l in t['stockLinks'])
            row['bridge'].update(fmt(l) for l in t['bridgeLinks'])
            row['ticks'] += len(t['ticks'])
            for tick in t['ticks']:
                row['tickStock'].add(tick['stock'].get('roadClass'))
                if tick['bridge']:
                    row['tickBridge'].add(tick['bridge'].get('roadClass'))
    lines = ['# ADAS bench coverage matrix', '',
             'link = class/formway/linktype/laneNum/light; tick roadClass = TrafficInfo while passing', '',
             '| transition | scenarios | hits | ticks | stock links | bridge links | stock roadClass | bridge roadClass |',
             '|---|---|---|---|---|---|---|---|']
    for kind in sorted(set(rows) | set(coverage.TARGET_KINDS)):
        row = rows.get(kind)
        if row is None:
            lines.append('| %s | - | 0 | 0 | not covered | | | |' % kind)
            continue
        lines.append('| %s | %d | %d | %d | %s | %s | %s | %s |' % (
            kind, len(row['scenarios']), row['hits'], row['ticks'], ' '.join(sorted(row['stock'])[:6]),
            ' '.join(sorted(row['bridge'])[:4]), sorted(row['tickStock'], key=str), sorted(row['tickBridge'], key=str)))
    lines += ['', '## Differences across scenarios', '', '| class | difference | scenarios |', '|---|---|---|']
    for (cls, text), runs in sorted(classes.items()):
        lines.append('| %s | %s | %d |' % (cls, text, len(runs)))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text('\n'.join(lines) + '\n')
    print(json.dumps(dict(out=str(args.out), kinds=len(rows),
                          notCovered=sorted(set(coverage.TARGET_KINDS) - set(rows)))))


# --------------------------------------------------------------------------- driver

def compare(stock, bridge, *, content_reference=False):
    inv_rows, diffs = inventory(stock, bridge)
    life, d = lifecycle(stock, bridge)
    diffs += d
    s_routes, b_routes = routes(stock), routes(bridge)
    contents, paired = [], set()
    # Pair each bridge publication with the stock route current at that moment (the source of a
    # synthetic Yandex route, or the stock's answer to the same situation).
    for k, b in enumerate(b_routes):
        current = [i for i, s in enumerate(s_routes) if s['t_ms'] <= b['t_ms'] + 100]
        if not current:
            continue
        i = current[-1]
        if i in paired:
            continue
        paired.add(i)
        s = s_routes[i]
        content, d = route_content(s['route']['data'][0], b['route']['data'][0], content_reference=content_reference)
        contents.append(dict(stockIndex=i, bridgeIndex=k, stockPath=s['route']['data'][0]['pathID'],
                             bridgePath=b['route']['data'][0]['pathID'], delayMs=round(b['t_ms'] - s['t_ms'], 1), **content))
        for item in d:
            item['route'] = i
        diffs += d
    unpaired = [s['route']['data'][0]['pathID'] for i, s in enumerate(s_routes) if i not in paired]
    if unpaired and b_routes:
        diffs.append(diff('dynamics', 'bridge-defect', f'{len(unpaired)} stock route publications have no bridge publication '
                          f'(stock pathIDs {unpaired[:6]}): the bridge did not publish the new route while the stock did',
                          pathIDs=unpaired))
    semantic = []
    for side, rs in (('stock', s_routes), ('bridge', b_routes)):
        if rs:
            for finding in semantics(rs[0]['route']['data'][0], side):
                semantic.append(finding)
                cls = 'bridge-defect' if side == 'bridge' else 'stock-semantics'
                if side == 'bridge' and any(f in finding['rule'] for f in ('roadclass', 'formway', 'laneNum', 'direction',
                                                                          'ownership', 'adminCode', 'hasParallel', 'hasMixFork', 'hasMultiOut')):
                    cls = 'source-data-gap'
                diffs.append(diff('content', cls, f'[{side}] Amap semantics: {finding["rule"]} - ' +
                                  ', '.join(f'{k}={v}' for k, v in finding.items() if k not in ('side', 'rule'))))
    s_by_path = {float(r['route']['data'][0]['pathID']): r['route']['data'][0] for r in s_routes}
    b_by_path = {float(r['route']['data'][0]['pathID']): r['route']['data'][0] for r in b_routes}
    rows = ticks(stock, bridge, s_by_path, b_by_path)
    gnss = stock['provenance'].get('gnssTicks') or []
    if gnss:
        last = gnss[-1]['t_ms'] + 1500
        rows = [r for r in rows if r['t'] <= last]
    tick_stats, d = tick_diffs(rows)
    diffs += d
    trans = transitions_report(s_routes[0]['route']['data'][0], b_routes[0]['route']['data'][0] if b_routes else None, rows) if s_routes else []
    counts = {}
    for item in diffs:
        counts[item['cls']] = counts.get(item['cls'], 0) + 1
    return dict(schema='denza-adas-bench-compare-v1', stock=dict(label=stock['label'], provenance={k: v for k, v in stock['provenance'].items() if k != 'gnssTicks'}),
                bridge=dict(label=bridge['label'], provenance={k: v for k, v in bridge['provenance'].items() if k != 'gnssTicks'}),
                contentReference=content_reference, classCounts=counts, differences=diffs, inventory=inv_rows,
                lifecycle=life, routeContent=contents, semantics=semantic, tickStats=tick_stats,
                transitions=trans, stockExtras=extras_around(stock), ticks=rows)


def markdown(result):
    lines = [f'# ADAS bench: {result["stock"]["label"]} vs {result["bridge"]["label"]}', '',
             f'Stock: {result["stock"]["provenance"].get("source")}  ',
             f'Bridge: {result["bridge"]["provenance"].get("source")}  ',
             f'Content reference: {"factory data (stock content authoritative)" if result["contentReference"] else "NOT authoritative (stock on OSM-derived data): content judged by Amap semantics only"}',
             '', '## Differences by class', '']
    for cls in ('bridge-defect', 'stock-not-modelled', 'source-data-gap', 'reference-not-authoritative', 'stock-semantics', 'info'):
        items = [d for d in result['differences'] if d['cls'] == cls]
        if items:
            lines.append(f'### {cls} ({len(items)})')
            lines += [f'- [{d["area"]}] {d["text"]}' for d in items]
            lines.append('')
    lines += ['## Message inventory', '', '| message | stock n | stock median ms | bridge n | bridge median ms |', '|---|---|---|---|---|']
    for row in result['inventory']:
        lines.append(f'| {row["name"]} | {row["stock"]["count"]} | {row["stock"].get("medianMs", "")} | {row["bridge"]["count"]} | {row["bridge"].get("medianMs", "")} |')
    life = result['lifecycle']
    lines += ['', '## Lifecycle', '', f'- stock status: {life["stockStatus"]}', f'- bridge status: {life["bridgeStatus"]}',
              f'- stock pathId on position: {life["stockPath"]}', f'- bridge pathId on position: {life["bridgePath"]}',
              f'- stock after end: {life["stockAfterEnd"]}', f'- bridge after end: {life["bridgeAfterEnd"]}', '',
              '| side | t | pathID | links | selection ms | answer ms | first position on path ms |', '|---|---|---|---|---|---|---|']
    for side in ('stock', 'bridge'):
        for h in life[side + 'Handshake']:
            lines.append(f'| {side} | {h["t"]} | {h["pathID"]} | {h["links"]} | {h["selectionDelayMs"]} | {h["answerDelayMs"]} | {h["firstPositionOnPathMs"]} |')
    if result['transitions']:
        lines += ['', '## Transitions on the first route', '', '| kind | at m | stock links (class/formway/type/lanes/light/main) | bridge links | ticks |', '|---|---|---|---|---|']
        for t in result['transitions']:
            fmt = lambda ls: ' '.join(f'{l["roadclass"]}/{l["formway"]}/{l["linktype"]}/{l["laneNum"]}/{int(bool(l["hasTrafficLight"]))}/{l["mainAction"]}' for l in ls[:6])
            lines.append(f'| {t["kind"]} | {t["distM"]} | {fmt(t["stockLinks"])} | {fmt(t["bridgeLinks"])} | {len(t["ticks"])} |')
    return '\n'.join(lines) + '\n'


def write(result, out):
    out.mkdir(parents=True, exist_ok=True)
    (out / 'compare.json').write_text(json.dumps(result, ensure_ascii=False, indent=1) + '\n')
    (out / 'compare.md').write_text(markdown(result))
    print(json.dumps(dict(out=str(out), classCounts=result['classCounts']), ensure_ascii=False))


def cmd_scenario(args):
    stock_path = args.run / 'stock-trace.json'
    bridge_path = args.bridge or args.run / 'bridge-trace.json'
    if not bridge_path.exists():
        from .bridge_trace import cmd_synth
        cmd_synth(argparse.Namespace(stock_trace=stock_path, out=bridge_path))
    write(compare(read_trace(stock_path), read_trace(bridge_path), content_reference=args.content_reference),
          args.run / 'compare')


def cmd_traces(args):
    write(compare(read_trace(args.stock), read_trace(args.bridge), content_reference=args.content_reference), args.out)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('scenario')
    p.add_argument('run', type=Path)
    p.add_argument('--bridge', type=Path)
    p.add_argument('--content-reference', action='store_true', help='the stock side ran on factory data')
    p.set_defaults(func=cmd_scenario)
    p = sub.add_parser('traces')
    p.add_argument('--stock', type=Path, required=True)
    p.add_argument('--bridge', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--content-reference', action='store_true')
    p.set_defaults(func=cmd_traces)
    p = sub.add_parser('profile', help='per road type: route link fields and per-tick messages, any number of traces')
    p.add_argument('--stock', action='append', help='stock trace (factory or OSM)')
    p.add_argument('--bridge', action='append', help='bridge trace (real Yandex: grouped by Yandex ranges)')
    p.add_argument('--roads', type=Path, help='road store: group bridge links by the OSM road the route matches')
    p.add_argument('--out', type=Path, required=True)
    p.set_defaults(func=cmd_profile)
    p = sub.add_parser('matrix', help='transition coverage matrix over scenario results')
    p.add_argument('runs', type=Path, nargs='+')
    p.add_argument('--out', type=Path, required=True)
    p.set_defaults(func=cmd_matrix)
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
