#!/usr/bin/env python3
"""Factory-data reference scenarios: the stock map on the car's own Beijing package.

The Beijing offline package the car's map downloaded (`a1`: m1, m2 with factory
ERAT/DRT, m3, m4_pro, m5a, m5b) is installed on an owned emulator that keeps the
STOCK global.db, which covers Beijing. Scenarios are chosen from the factory data
itself, not by hand:

    survey   plan many O/D pairs with the original offline router and keep each route's
             original NOA export (k.h.e0.d.f.l.f), stock_plan_observer.js
    select   greedy set of routes that covers the most transition kinds (coverage.py)
    cases    a moving GNSS trace along each chosen route (speed by the route's own road
             class, slowing into turns, stopping at signals), arrival included; a
             `miss` variant leaves the route at a junction onto another factory route
    run      stock_trace batch over the cases (stock_feed_observer.js)

    python3 -m tools.adas_bridge.beijing survey --out captures/adas-bench/beijing/survey-01
    python3 -m tools.adas_bridge.beijing select --survey captures/adas-bench/beijing/survey-01 --limit 6
    python3 -m tools.adas_bridge.beijing cases  --survey captures/adas-bench/beijing/survey-01
    python3 -m tools.adas_bridge.stock_trace batch --plan captures/adas-bench/beijing/survey-01/plan.json \
        --serial emulator-5602 --frida-port 27071

Coordinates are SDK (GCJ) degrees and stay under captures/.
"""
import argparse
import json
import math
from pathlib import Path
import random

from . import coverage
from .stock_trace import install_run

HERE = Path(__file__).resolve().parent
PACK = Path('captures/stock-map-beijing-pack/car-01')
# Survey geography (box, anchor points, explicit second-round pairs, view centre) lives in
# captures/adas-bench/beijing/geography.json with the other Beijing coordinates.
GEOGRAPHY = Path('captures/adas-bench/beijing/geography.json')
PART_SAMPLES = 500  # app run <= 600 s with start-up and arrival dwell
SPEED = {'expressway': 22.0, 'ramp': 12.0, 'roundabout': 7.0, 'arterial': 14.0, 'secondary': 10.0,
         'ordinary': 8.0, 'minor': 5.0, 'unknown': 8.0}


def metres(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot((b[0] - a[0]) * 111320.0 * math.cos(lat), (b[1] - a[1]) * 110574.0)


def pairs(count, seed, *, low=1500, high=9000, box=None, explicit=False, geography=GEOGRAPHY):
    geo = json.loads(Path(geography).read_text())
    box = box or tuple(geo['box'])
    rng = random.Random(seed)
    points = [tuple(p) for p in geo['anchors']]
    while len(points) < 40:
        points.append((rng.uniform(box[0], box[2]), rng.uniform(box[1], box[3])))
    out = []
    if explicit:
        for a, b in geo['explicit']:
            out.append(dict(id='x%03d' % len(out), **{'from': list(a), 'to': list(b)}))
    while len(out) < count:
        a, b = rng.sample(points, 2)
        if low <= metres(a, b) <= high:
            out.append(dict(id='p%03d' % len(out), **{'from': [round(a[0], 6), round(a[1], 6)],
                                                        'to': [round(b[0], 6), round(b[1], 6)]}))
    return out


def cmd_survey(args):
    box = tuple(float(v) for v in args.box.split(',')) if args.box else None
    plan = dict(pairs=pairs(args.pairs, args.seed, low=args.min_m, high=args.max_m, box=box, explicit=args.explicit),
                timeoutMs=25000)
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / 'pairs.json').write_text(json.dumps(plan, indent=1) + '\n')
    observer = 'const PLAN_CASE=' + json.dumps(plan) + ';\n' + (HERE / 'stock_plan_observer.js').read_text()
    seconds = args.seconds or 30 + 8 * len(plan['pairs'])
    centre = json.loads(GEOGRAPHY.read_text())['view']
    view = dict(lon=centre[0], lat=centre[1], zoom=14)
    receipt = install_run(args.build, observer, view, args.out / 'run', seconds=seconds,
                          serial=args.serial, frida_port=args.frida_port)
    summarize_survey(args.out)
    print(json.dumps(dict(status=receipt['status'], restored=receipt['baseline_restored'])))


def summarize_survey(out):
    plan = json.loads((out / 'pairs.json').read_text())
    by_id = {p['id']: p for p in plan['pairs']}
    routes = {}
    for line in (out / 'run/app/events.jsonl').read_text().splitlines():
        payload = json.loads(line).get('payload') or {}
        if payload.get('kind') == 'survey_route' and payload.get('pair') in by_id:
            message = json.loads(payload['export'])
            data = message.get('data') or []
            if data:
                routes[payload['pair']] = dict(pair=by_id[payload['pair']], length=payload['length'],
                                               local=payload['local'], route=data[0],
                                               routeType=message.get('RouteType'))
    (out / 'routes.json').write_text(json.dumps(routes, ensure_ascii=False) + '\n')
    kinds = {}
    for item in routes.values():
        for kind in coverage.kinds(item['route']):
            kinds[kind] = kinds.get(kind, 0) + 1
    print(json.dumps(dict(routes=len(routes), of=len(by_id), kinds=dict(sorted(kinds.items()))), ensure_ascii=False))


def cmd_merge(args):
    merged = {}
    for survey in args.survey:
        for key, value in json.loads((survey / 'routes.json').read_text()).items():
            merged[survey.name + ':' + key] = value
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / 'routes.json').write_text(json.dumps(merged, ensure_ascii=False) + '\n')
    print(json.dumps(dict(routes=len(merged), out=str(args.out))))


def cmd_select(args):
    routes = json.loads((args.survey / 'routes.json').read_text())
    chosen, covered = coverage.greedy({k: v['route'] for k, v in routes.items()}, args.limit, max_len=args.max_m)
    universe = sorted({k for v in routes.values() for k in coverage.kinds(v['route'])})
    selection = dict(chosen=[dict(id=i, gains=g, kinds=coverage.kinds(routes[i]['route']),
                                  lengthM=sum(l.get('len') or 0 for l in routes[i]['route']['links'])) for i, g in chosen],
                     covered=covered, surveyUniverse=universe, notInSurvey=sorted(set(coverage.TARGET_KINDS) - set(universe)))
    (args.survey / 'selection.json').write_text(json.dumps(selection, indent=1, ensure_ascii=False) + '\n')
    print(json.dumps(selection, ensure_ascii=False))


def route_polyline(route):
    """Per-link point slices -> one polyline with each point's road group and the link index."""
    points, info = [], []
    for index, link in enumerate(route['links']):
        start, count = link['pntBegIdx'], link['pntCnt']
        for k in range(start, start + count):
            p = route['pnts'][k]
            point = [p['x'], p['y']]
            if points and point == points[-1]:
                continue
            points.append(point)
            info.append((index, coverage.road_group(link)))
    return points, info


def segment_turn_links(route, actions=(1, 2, 3, 4, 5, 6, 7, 11, 12)):
    out = []
    for group in route.get('guideGroups') or []:
        for segment in group.get('segments') or []:
            if segment.get('navigationMainAction') in actions and segment.get('crntSegmLinkCnt'):
                out.append(segment['linkBegIdx'] + segment['crntSegmLinkCnt'] - 1)
    return out


def trace_along(points, info, route, *, stop_s=3, dwell_end_s=12):
    """1 Hz samples along the polyline: class speed, slower near manoeuvre ends, stops at lights."""
    cumulative = [0.0]
    for a, b in zip(points, points[1:]):
        cumulative.append(cumulative[-1] + metres(a, b))
    links = route['links']
    link_end = {}
    for j, (index, _) in enumerate(info):
        link_end[index] = cumulative[j]
    lights = sorted(link_end[i] for i, l in enumerate(links) if l.get('hasTrafficLight') and i in link_end)
    turns = sorted(link_end[i] for i in segment_turn_links(route) if i in link_end)
    samples, s, t, waited = [], 0.0, 0.0, set()
    total = cumulative[-1]

    def locate(dist):
        j = max(0, min(len(points) - 2, next((k for k in range(len(cumulative) - 1) if cumulative[k + 1] >= dist), len(points) - 2)))
        span = cumulative[j + 1] - cumulative[j] or 1.0
        f = max(0.0, min(1.0, (dist - cumulative[j]) / span))
        a, b = points[j], points[j + 1]
        course = math.degrees(math.atan2((b[0] - a[0]) * math.cos(math.radians(a[1])), b[1] - a[1])) % 360
        return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f], course, info[j][1]

    speed = 0.0
    while s < total:
        point, course, group = locate(s)
        target = SPEED.get(group, 8.0)
        for mark in turns:
            if 0 <= mark - s < 60:
                target = min(target, 6.0)
        for mark in lights:
            if mark not in waited and 0 <= mark - s < 8:
                waited.add(mark)
                for _ in range(stop_s):
                    samples.append(dict(sdk=point, heading_degrees_true=round(course, 1), speed_mps=0.0, distance_m=round(s, 1)))
                speed = 0.0
        speed = min(target, speed + 2.5) if speed < target else max(target, speed - 3.0)
        samples.append(dict(sdk=point, heading_degrees_true=round(course, 1), speed_mps=round(speed, 2), distance_m=round(s, 1)))
        s += max(speed, 1.0)
        t += 1
    end, course, _ = locate(total)
    for _ in range(dwell_end_s):
        samples.append(dict(sdk=end, heading_degrees_true=round(course, 1), speed_mps=0.0, distance_m=round(total, 1)))
    return samples


def cmd_cases(args):
    routes = json.loads((args.survey / 'routes.json').read_text())
    selection = json.loads((args.survey / 'selection.json').read_text())
    cases_dir = args.survey / 'cases'
    cases_dir.mkdir(exist_ok=True)
    plan = []
    for item in selection['chosen']:
        entry = routes[item['id']]
        points, info = route_polyline(entry['route'])
        samples = trace_along(points, info, entry['route'])
        # online_cache_emulator.py bounds an app run to 600 s: drive a long route in parts, each
        # starting where the previous one ended (the stock re-plans from there to the same
        # destination); only the last part arrives.
        parts = [samples[i:i + PART_SAMPLES] for i in range(0, len(samples), PART_SAMPLES)]
        if len(parts) > 1 and len(parts[-1]) < 50:
            parts[-2:] = [parts[-2] + parts[-1]]
        for k, part in enumerate(parts):
            start = entry['pair']['from'] if k == 0 else part[0]['sdk']
            case = dict(schema='drive-log-replay-v1', status='SYNTHETIC_ALONG_FACTORY_ROUTE', device_operations=0,
                        source=dict(survey=str(args.survey), pair=item['id'], part=k, parts=len(parts)), frame='SDK',
                        route_start_sdk=start, route_end_sdk=entry['pair']['to'],
                        transitions=coverage.transitions(entry['route']) if k == 0 else None, samples=part)
            name = item['id'].replace(':', '-') + ('' if len(parts) == 1 else '-part%d' % (k + 1))
            path = cases_dir / (name + '.json')
            path.write_text(json.dumps(case) + '\n')
            plan.append(dict(build=str(args.build), case=str(path), out=str(args.survey.parent / ('run-' + name)),
                             onlineWaitMs=0))
    if args.miss and len(selection['chosen']) >= 1:
        plan.extend(miss_cases(routes, selection, cases_dir, args))
    (args.survey / 'plan.json').write_text(json.dumps(plan, indent=1) + '\n')
    print(json.dumps([dict(case=p['case'], out=p['out']) for p in plan]))


def miss_cases(routes, selection, cases_dir, args):
    """Missed turn: follow route A to its first turn, then continue along another surveyed
    route B that passes that junction but does not turn there (factory geometry both ways)."""
    out = []
    first = selection['chosen'][0]['id']
    a = routes[first]['route']
    pa, ia = route_polyline(a)
    # The link after a segment that ends with a left/right turn (factory link mainAction is mostly NULL).
    turn_links = [i + 1 for i in segment_turn_links(a, actions=(1, 2, 5, 6)) if i > 1 and i + 1 < len(a['links'])]
    for index in turn_links:
        junction = pa[max(j for j, (k, _) in enumerate(ia) if k == index - 1)]
        for other, entry in routes.items():
            if other == first:
                continue
            pb, ib = route_polyline(entry['route'])
            near = [j for j, p in enumerate(pb) if metres(p, junction) < 15]
            if not near or near[-1] + 20 >= len(pb):
                continue
            j = near[-1]
            # B must leave the junction in a different direction than A does.
            ahead_a = pa[min(len(pa) - 1, max(k for k, (x, _) in enumerate(ia) if x == index) )]
            if metres(pb[min(len(pb) - 1, j + 3)], ahead_a) < 40:
                continue
            cut = max(k for k, (x, _) in enumerate(ia) if x == index - 1)
            points = pa[:cut + 1] + pb[j + 1:j + 60]
            info = ia[:cut + 1] + ib[j + 1:j + 60]
            samples = trace_along(points, [(0, g) for _, g in info], {'links': [{}]})
            case = dict(schema='drive-log-replay-v1', status='SYNTHETIC_MISSED_TURN_FACTORY_ROUTES',
                        device_operations=0, source=dict(survey=str(args.survey), routeA=first, routeB=other,
                                                         missedLink=index), frame='SDK',
                        route_start_sdk=routes[first]['pair']['from'], route_end_sdk=routes[first]['pair']['to'],
                        samples=samples)
            path = cases_dir / ('miss-' + first.replace(':', '-') + '.json')
            path.write_text(json.dumps(case) + '\n')
            out.append(dict(build=str(args.build), case=str(path), out=str(args.survey.parent / ('run-miss-' + first.replace(':', '-'))),
                            onlineWaitMs=0))
            return out
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('survey')
    p.add_argument('--build', type=Path, default=PACK)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--pairs', type=int, default=60)
    p.add_argument('--seed', type=int, default=20261023)
    p.add_argument('--seconds', type=int)
    p.add_argument('--min-m', type=float, default=1500)
    p.add_argument('--max-m', type=float, default=9000)
    p.add_argument('--box', help='west,south,east,north (SDK)')
    p.add_argument('--explicit', action='store_true', help='add the second-round anchor pairs')
    p.add_argument('--serial', default='emulator-5602')
    p.add_argument('--frida-port', type=int, default=27071)
    p.set_defaults(func=cmd_survey)
    p = sub.add_parser('summarize')
    p.add_argument('--survey', type=Path, required=True)
    p.set_defaults(func=lambda a: summarize_survey(a.survey))
    p = sub.add_parser('merge')
    p.add_argument('--survey', type=Path, action='append', required=True)
    p.add_argument('--out', type=Path, required=True)
    p.set_defaults(func=cmd_merge)
    p = sub.add_parser('select')
    p.add_argument('--survey', type=Path, required=True)
    p.add_argument('--limit', type=int, default=6)
    p.add_argument('--max-m', type=float, default=14000, help='longest route a scenario may drive')
    p.set_defaults(func=cmd_select)
    p = sub.add_parser('cases')
    p.add_argument('--survey', type=Path, required=True)
    p.add_argument('--build', type=Path, default=PACK)
    p.add_argument('--miss', action='store_true', default=True)
    p.set_defaults(func=cmd_cases)
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
