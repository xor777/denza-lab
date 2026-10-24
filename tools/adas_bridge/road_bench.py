#!/usr/bin/env python3
"""Before/after of matching recorded Yandex routes onto the road store, against the factory profile.

For each Navigator route snapshot: convert without and with the store (the oracle, byte-equal to the
Java bridge), and tabulate links per route, link length, what share of links carry each attribute
(class, formway, LaneNum, direction, ownership, name: not the stock's NULL), and per road group (by
the matched road's class and form, compare.py/coverage.py's grouping) the attribute histograms next
to the factory Beijing profile's. Output holds counts and values, never coordinates.

    python3 -m tools.adas_bridge.road_bench --roads roads.bin --out OUT.json SNAPSHOT.json [...]
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import statistics
import time

from . import amap_injector
from . import roads as roads_module
from . import stock
from .coverage import road_group

ROOT = Path(__file__).resolve().parents[2]
PROFILE = 'research/stock-map-native/factory-beijing-profile-20261023.json'  # in the amap-injector checkout
FIELDS = ('roadClass', 'formway', 'laneNum', 'direction', 'ownership', 'roadName')
UNKNOWN = dict(roadClass=-1, formway=-1, laneNum=0, direction=None, ownership=-1, roadName='')
# Factory categories (profile_factory_map.py) per coverage.road_group.
FACTORY_GROUPS = {'expressway': ('freeway', 'city_speedway'), 'arterial': ('national', 'provincial', 'main_road'),
                  'secondary': ('secondary_road',), 'ordinary': ('common_road',), 'minor': ('service_or_internal',)}


def flat(converted):
    out = []
    for segment in converted['path']['segments']:
        out.extend(segment['links'])
    return out


def coverage(links, direction_known):
    n = len(links) or 1
    share = {}
    for field in FIELDS:
        if field == 'direction':
            share[field] = round(sum(direction_known) / n, 3)
        else:
            share[field] = round(sum(l[field] != UNKNOWN[field] for l in links) / n, 3)
    return share


def one(path, store, frame):
    snapshot = json.loads(Path(path).read_text())
    route = stock.route_from_snapshot(snapshot)
    before = stock.convert(route, frame)
    started = time.monotonic()
    after = stock.convert(route, frame, store)
    seconds = time.monotonic() - started
    b, a = flat(before), flat(after)
    matched = [s == s for s in after['linkSpeedKmh']]          # not NaN: the link lies on a matched road
    groups_after = [road_group({'roadclass': l['roadClass'], 'formway': l['formway']}) if m else 'unmatched'
                    for l, m in zip(a, matched)]
    by_group = {}
    for group in sorted(set(groups_after)):
        rows = [l for l, g in zip(a, groups_after) if g == group]
        by_group[group] = dict(links=len(rows), lengthM=sum(l['length'] for l in rows),
                               medianLinkM=statistics.median(l['length'] for l in rows),
                               roadClass=dict(Counter(l['roadClass'] for l in rows).most_common(4)),
                               formway=dict(Counter(l['formway'] for l in rows).most_common(4)),
                               laneNum=dict(Counter(l['laneNum'] for l in rows).most_common(5)),
                               direction=dict(Counter(l['direction'] for l in rows).most_common(2)),
                               ownership=dict(Counter(l['ownership'] for l in rows).most_common(2)),
                               named=round(sum(bool(l['roadName']) for l in rows) / len(rows), 2))
    stats = after['roadMatch'] or {}
    return dict(route=Path(path).parent.name, lengthM=round(after['totalM']), seconds=round(seconds, 2),
                matchedShare=round(stats.get('matchedM', 0) / after['totalM'], 3), match=stats,
                before=dict(links=len(b), medianLinkM=statistics.median(l['length'] for l in b),
                            coverage=coverage(b, [l['direction'] == 1 for l in b])),
                after=dict(links=len(a), medianLinkM=statistics.median(l['length'] for l in a),
                           linksOnMatchedRoads=sum(matched),
                           coverage=coverage(a, [m or l['direction'] == 1 for l, m in zip(a, matched)])),
                groups=by_group)


def factory(profile):
    out = {}
    for group, categories in FACTORY_GROUPS.items():
        rows = {c: profile['categories'][c] for c in categories if c in profile['categories']}
        out[group] = {c: dict(roads=v.get('roads'), lengthMedianM=v.get('length_median_m'), lanesHist=v.get('lanes_hist'),
                              formwayTop=v.get('formway_top'), direction=v.get('direction'), ownership=v.get('ownership'),
                              speed4269Median=v.get('speed_4269_median'))
                      for c, v in rows.items()}
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('snapshots', nargs='+', type=Path)
    parser.add_argument('--roads', type=Path, required=True)
    parser.add_argument('--frame', default='native-gcj-unrestricted-candidate')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    store = roads_module.RoadStore(args.roads.read_bytes())
    result = dict(schema='adas-bridge-road-bench-v1', roads=str(args.roads), frame=args.frame,
                  routes=[one(p, store, args.frame) for p in args.snapshots],
                  factory=factory(json.loads(amap_injector.path(PROFILE).read_text())))
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1) + '\n')
    for r in result['routes']:
        print(json.dumps({k: r[k] for k in ('route', 'lengthM', 'seconds', 'matchedShare')} |
                         {'links': (r['before']['links'], r['after']['links']),
                          'coverageAfter': r['after']['coverage']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
