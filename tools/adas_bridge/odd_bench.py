"""Offline ODD-distance differential bench against the original Amap APK.

No publisher or vehicle access. `prepare` writes an emulator observer using a
retained factory Beijing route and a retained ADAS city-list snapshot. `check`
replays native link URIDs/lengths through an independent host implementation,
and compares both distances and protobuf bytes with the original calculator.
All factory data, coordinates and oracle captures stay under captures/.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path

from . import stock

MAX = 2147483647
TOPIC = 1125929972105220
ROOT = Path(__file__).resolve().parents[2]


def distance(segments, segment, link, remain, open_cities):
    """Stock f.e()/f.d(): finite lookahead, not a universal coverage predicate.

    The original checks URID transitions before adding a future link's length.
    A same-URID horizon exceeding 2000 m returns MAX even for an excluded city.
    An empty city list also returns MAX. Preserve these observed source rules.
    """
    cities = set(open_cities)
    if not cities:
        return MAX
    current = segments[segment][link]['urid']
    length = remain
    for s in range(segment, len(segments)):
        for item in segments[s][link + 1 if s == segment else 0:]:
            if item['urid'] != current:
                return 0 if current not in cities else MAX if item['urid'] in cities else length
            # Java int arithmetic, including overflow.
            length = (length + item['length'] + 2**31) % 2**32 - 2**31
            if length > 2000:
                return MAX
    return MAX if current in cities else 0


def identity(path):
    p = Path(path)
    return dict(path=str(p.resolve()), sha256=hashlib.sha256(p.read_bytes()).hexdigest())


def prepare(args):
    case = json.loads(args.factory_case.read_text())
    receipt = json.loads(args.factory_receipt.read_text())
    build = receipt.get('build', '')
    if not receipt.get('baseline_restored') or receipt.get('vehicle_operations') != 0:
        raise ValueError('expected restored emulator-only stock reference receipt')
    if '/stock-map-beijing-pack/' in build:
        reference_kind = 'factory-beijing'
    elif '/stock-map-review-20261023/car/package-' in build:
        reference_kind = 'custom-moscow-stock-map'
    else:
        raise ValueError('unapproved stock reference build')
    line = next(x for x in args.city_log.read_text().splitlines() if 'handNOa city code:[' in x)
    cities = json.loads(line.split('handNOa city code:', 1)[1])
    plan = dict(pairs=[dict(id='factory-odd', **{'from': case['route_start_sdk'], 'to': case['route_end_sdk']})], timeoutMs=25000)
    args.out.mkdir(parents=True, exist_ok=False)
    here = Path(__file__).resolve().parent
    observer = ('const PLAN_CASE=' + json.dumps(plan) + ';\nconst ODD_BENCH=' + json.dumps(dict(openCities=cities)) + ';\n'
                + (here / 'stock_plan_observer.js').read_text() + '\n' + (here / 'odd_bench_observer.js').read_text())
    (args.out / 'observer.js').write_text(observer)
    (args.out / 'view.json').write_text(json.dumps(dict(lon=case['route_start_sdk'][0], lat=case['route_start_sdk'][1], zoom=14)) + '\n')
    (args.out / 'inputs.json').write_text(json.dumps(dict(schema='denza-odd-bench-inputs-v1',
        referenceKind=reference_kind, factoryCase=identity(args.factory_case), factoryReceipt=identity(args.factory_receipt),
        cityLog=identity(args.city_log), cityListCount=len(cities), cityListIsRetainedSnapshot=True,
        originalCalculator=identity(ROOT / 'captures/hud-firmware-20260923/jadx/BydLaunchermap/sources/k/h/e0/c/f.java'),
        vehicleOperations=0, publicationReady=False), indent=2) + '\n')


def check(args):
    route, cases, counts, distributions, failures = None, {}, {}, {}, []
    ready = done = False
    samples = 0
    for line_number, line in enumerate(args.events.read_text().splitlines(), 1):
        p = json.loads(line).get('payload') or {}
        kind = p.get('kind')
        if kind == 'odd_ready': ready = p.get('emulatorOnly') is True
        elif kind == 'odd_done': done = True
        elif kind == 'odd_error': failures.append(dict(line=line_number, error=p))
        elif kind == 'odd_factory_route': route = p
        elif kind == 'odd_case': cases[p['id']] = p
        elif kind == 'odd_sample':
            samples += 1
            c = cases[p['caseId']]
            segments = [[dict(item, urid=c['overrides'].get(item['key'], item['urid'])) for item in s] for s in route['segments']]
            expected = distance(segments, p['segment'], p['link'], p['remain'], c['cities'])
            # The original app can emit an unrelated status packet reentrantly; select this calculator's topic.
            packets = [packet for packet in p['packets'] if int(packet['topic']) == TOPIC]
            matches = len(packets) == 1
            expected_bytes = stock.no_odd_region_distance(expected)
            matches = matches and base64.b64decode(packets[0]['payload']) == expected_bytes
            # Older captures may omit the optional diagnostic field; the original protobuf is authoritative.
            matches = matches and ('lastDistance' not in p or p['lastDistance'] == expected)
            counts[c['id']] = counts.get(c['id'], 0) + 1
            dist = distributions.setdefault(c['id'], {})
            dist[str(expected)] = dist.get(str(expected), 0) + 1
            if not matches: failures.append(dict(line=line_number, case=c['id'], expected=expected, actual=p))
    required = {'factory-observed-city-list', 'factory-native-cities', 'factory-empty-city-list', 'factory-excluded-cities',
                'synthetic-covered-to-excluded', 'synthetic-excluded-to-covered'}
    if not ready or not done or route is None or not required <= counts.keys():
        failures.append(dict(error='incomplete oracle run', ready=ready, done=done, counts=counts))
    result = dict(schema='denza-odd-bench-check-v1', status='PASS' if not failures else 'FAIL', samples=samples,
        cases=counts, expectedDistanceDistributions=distributions, failures=failures,
        nativeUrids=route['nativeUrids'] if route else [], oracle=identity(args.events),
        vehicleOperations=0, publicationReady=False,
        scope='Original APK calculator with native factory Beijing links; ODD city-list inputs are controlled snapshots. '
              'Synthetic boundary URIDs are labelled. No ADAS ECU or NOA acceptance is simulated.')
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(dict(status=result['status'], samples=samples, cases=len(counts), failures=len(failures))))
    if failures: raise SystemExit(1)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest='command', required=True)
    prep = sub.add_parser('prepare')
    for name in ('factory-case', 'factory-receipt', 'city-log', 'out'): prep.add_argument('--' + name, type=Path, required=True)
    prep.set_defaults(run=prepare)
    verify = sub.add_parser('check')
    verify.add_argument('--events', type=Path, required=True)
    verify.add_argument('--out', type=Path, required=True)
    verify.set_defaults(run=check)
    args = p.parse_args(); args.run(args)


if __name__ == '__main__':
    main()
