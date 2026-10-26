"""Annotate one converted Yandex route with URIDs observed on an Amap reference route.

This is an offline, route-specific evidence tool. It samples both geometries in the
stock SDK frame and copies an adcode/URID only for a well-matched, unambiguous link.
Missing or ambiguous links deliberately keep URID 0. It neither publishes to ADAS
nor turns a Moscow stock-map fixture into a Russia-wide road catalog.
"""
from __future__ import annotations

import argparse
import copy
import json
import math
from pathlib import Path

from . import feed, roads, stock
from .odd_bench import MAX, distance, identity

SCHEMA = 'denza-urid-route-annotation-v1'
REPLAY_SCHEMA = 'denza-urid-route-replay-expectations-v1'
MIN_MATCH_FRACTION = 0.90


def _load_oracle(path):
    native = survey = None
    ready = done = False
    for line in path.read_text().splitlines():
        payload = json.loads(line).get('payload') or {}
        kind = payload.get('kind')
        if kind == 'odd_ready':
            ready = payload.get('emulatorOnly') is True and payload.get('vehicleOperations') == 0
        elif kind == 'odd_done':
            done = True
        elif kind == 'odd_factory_route':
            native = payload
        elif kind == 'survey_route':
            if payload.get('local') is not True or payload.get('paths') != 1:
                raise ValueError('expected one local Amap reference path')
            survey = json.loads(payload['export'])
    if not ready or not done or native is None or survey is None:
        raise ValueError('incomplete emulator-only Amap oracle capture')
    if survey.get('code') != 200 or len(survey.get('data') or []) != 1:
        raise ValueError('Amap route export is not one successful path')
    return native, survey['data'][0]


def _native_links(native, exported):
    flat = [dict(link) for segment in native['segments'] for link in segment]
    links, points = exported['links'], exported['pnts']
    if exported.get('linkCnt') != len(links) or exported.get('pntCnt') != len(points):
        raise ValueError('Amap export counts disagree with arrays')
    if len(flat) != len(links):
        raise ValueError('native LinkInfo and exported route have different link counts')
    result = []
    next_point = 0
    for index, (link, out) in enumerate(zip(flat, links)):
        if link['key'] != f"{link['segment']}:{link['link']}":
            raise ValueError('invalid native link key')
        if int(link['adcode']) != int(out['adminCode']) or link['length'] != round(float(out['len'])):
            raise ValueError(f'native/export link mismatch at {index}')
        begin, count = out['pntBegIdx'], out['pntCnt']
        if begin != next_point or count < 2 or begin + count > len(points):
            raise ValueError(f'invalid or non-contiguous point range at {index}')
        geometry = [(float(p['x']), float(p['y'])) for p in points[begin:begin + count]]
        if not all(math.isfinite(v) for point in geometry for v in point):
            raise ValueError('non-finite Amap coordinate')
        result.append(dict(key=link['key'], urid=int(link['urid']), adcode=int(link['adcode']),
                           length=int(link['length']), geometry=geometry))
        next_point += count
    if next_point != len(points):
        raise ValueError('unreferenced Amap route points')
    return result


def _projector(references):
    lat = sum(p[1] for link in references for p in link['geometry']) / sum(len(link['geometry']) for link in references)
    mx = 111320.0 * math.cos(math.radians(lat))
    my = 110574.0
    if not mx > 0:
        raise ValueError('degenerate local projection')
    return lambda lon, lat: (lon * mx, lat * my)


def _project_segment(a, b, x, y):
    dx, dy = b[0] - a[0], b[1] - a[1]
    length2 = dx * dx + dy * dy
    if not length2 > 0:
        return None
    t = max(0.0, min(1.0, ((x - a[0]) * dx + (y - a[1]) * dy) / length2))
    qx, qy = x - a[0] - t * dx, y - a[1] - t * dy
    length = math.sqrt(length2)
    return math.sqrt(qx * qx + qy * qy), dx / length, dy / length


def _candidates(reference, x, y, ux, uy):
    best = None
    for a, b in zip(reference['xy'], reference['xy'][1:]):
        projected = _project_segment(a, b, x, y)
        if projected is not None and (best is None or projected[0] < best[0]):
            best = projected
    if best is None or best[0] > roads.RADIUS_M or ux * best[1] + uy * best[2] < roads.MIN_COS:
        return None
    return best[0]


def _samples(points):
    lengths = [math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(points, points[1:])]
    cumulative = [0.0]
    for length in lengths:
        cumulative.append(cumulative[-1] + length)
    if not cumulative[-1] > 0:
        raise ValueError('zero-length Yandex link geometry')
    count = max(1, math.ceil(cumulative[-1] / roads.STEP_M))
    result = []
    for index in range(count + 1):
        target = cumulative[-1] * index / count
        edge = min(next((i for i in range(len(lengths)) if cumulative[i + 1] >= target), len(lengths) - 1),
                   len(lengths) - 1)
        fraction = (target - cumulative[edge]) / lengths[edge] if lengths[edge] > 0 else 0.0
        a, b = points[edge], points[edge + 1]
        x, y = a[0] + fraction * (b[0] - a[0]), a[1] + fraction * (b[1] - a[1])
        ux, uy = (b[0] - a[0]) / lengths[edge], (b[1] - a[1]) / lengths[edge]
        result.append((x, y, ux, uy))
    return result


def annotate_path(path, references, project):
    """Return (annotated copy, per-link evidence), without inferring missing values."""
    for reference in references:
        reference['xy'] = [project(*p) for p in reference['geometry']]
    annotated = copy.deepcopy(path)
    evidence = []
    for segment_index, segment in enumerate(annotated['segments']):
        for link_index, link in enumerate(segment['links']):
            points = [project(lon / stock.SCALE, lat / stock.SCALE) for lon, lat in zip(link['lon'], link['lat'])]
            matched_distances = []
            keys, urids, adcodes = set(), set(), set()
            samples = _samples(points)
            for x, y, ux, uy in samples:
                candidates = []
                for reference in references:
                    distance_m = _candidates(reference, x, y, ux, uy)
                    if distance_m is not None:
                        candidates.append((distance_m, reference))
                if candidates:
                    matched_distances.append(min(c[0] for c in candidates))
                    keys.update(c[1]['key'] for c in candidates)
                    urids.update(c[1]['urid'] for c in candidates)
                    adcodes.update(c[1]['adcode'] for c in candidates)
            fraction = len(matched_distances) / len(samples)
            if len(urids) > 1 or len(adcodes) > 1:
                status = 'ambiguous'
            elif fraction < MIN_MATCH_FRACTION or not urids:
                status = 'unmapped'
            else:
                status = 'mapped'
            link['adcode'] = next(iter(adcodes)) if status == 'mapped' else 0
            link['urid'] = next(iter(urids)) if status == 'mapped' else 0
            evidence.append(dict(key=f'{segment_index}:{link_index}', status=status, length=link['length'],
                                 adcode=link['adcode'], urid=link['urid'], sampleCount=len(samples),
                                 matchedSamples=len(matched_distances), matchedFraction=fraction,
                                 meanNearestDistanceM=(sum(matched_distances) / len(matched_distances)
                                                       if matched_distances else None),
                                 maxNearestDistanceM=max(matched_distances) if matched_distances else None,
                                 candidateAmapKeys=sorted(keys), candidateUrids=sorted(urids),
                                 candidateAdcodes=sorted(adcodes)))
    return annotated, evidence


def _summary(evidence):
    total_length = sum(row['length'] for row in evidence)
    mapped_length = sum(row['length'] for row in evidence if row['status'] == 'mapped')
    return dict(total=len(evidence), mapped=sum(r['status'] == 'mapped' for r in evidence),
                unmapped=sum(r['status'] == 'unmapped' for r in evidence),
                ambiguous=sum(r['status'] == 'ambiguous' for r in evidence),
                totalLengthM=total_length, mappedLengthM=mapped_length,
                mappedLengthFraction=mapped_length / total_length if total_length else 0.0)


def _longest_mapped_excerpt(path, evidence):
    flat = [(s, l, link) for s, segment in enumerate(path['segments']) for l, link in enumerate(segment['links'])]
    runs, current = [], []
    for index, row in enumerate(evidence):
        if row['status'] == 'mapped':
            current.append(index)
        elif current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    if not runs:
        return None
    run = max(runs, key=lambda indices: (sum(flat[i][2]['length'] for i in indices), -indices[0]))
    excerpt = {'pathId': 0, 'segments': [{'length': sum(flat[i][2]['length'] for i in run),
               'travelTime': 0, 'mainAction': 0, 'assistantAction': 0, 'trafficLightNum': 0,
               'links': [copy.deepcopy(flat[i][2]) for i in run]}], 'trafficLights': [], 'groups': []}
    return dict(startFlatLink=run[0], endFlatLink=run[-1], linkKeys=[evidence[i]['key'] for i in run], stockPath=excerpt)


def _samples_for_path(path):
    return [(s, l, link, remain) for s, segment in enumerate(path['segments'])
            for l, link in enumerate(segment['links']) for remain in (link['length'], link['length'] // 2, 0)]


def make_replay(annotation, odd_response):
    cities = [row['oDDRegionCodeValue'] for row in feed.decode('ODDRegionCodeResp', odd_response)['oDDRegionCodeRspInfo']]
    excerpt = annotation['mappedExcerpt']
    if excerpt is None:
        raise ValueError('no fully mapped excerpt to replay')
    full = annotation['stockPath']
    cases = [dict(id='annotated-full-actual', path='full', cities=cities, uridOverrides={}, samples=[]),
             dict(id='mapped-excerpt-actual', path='excerpt', cities=cities, uridOverrides={}, samples=[]),
             dict(id='mapped-excerpt-synthetic-boundary', path='excerpt', cities=[372], uridOverrides={}, samples=[])]
    boundary_key = excerpt['linkKeys'][-1]
    cases[2]['uridOverrides'][boundary_key] = 1
    for case in cases:
        source = full if case['path'] == 'full' else excerpt['stockPath']
        original_keys = None if case['path'] == 'full' else iter(excerpt['linkKeys'])
        keys_by_position = {}
        segments = []
        for s, segment in enumerate(source['segments']):
            rows = []
            for l, link in enumerate(segment['links']):
                key = f'{s}:{l}' if original_keys is None else next(original_keys)
                keys_by_position[(s, l)] = key
                rows.append(dict(link, urid=case['uridOverrides'].get(key, link.get('urid', 0))))
            segments.append(rows)
        complete = all(link['urid'] != 0 for segment in segments for link in segment)
        for s, l, link, remain in _samples_for_path(source):
            key = keys_by_position[(s, l)]
            expected = (distance(segments, s, l, remain, case['cities']) if complete else MAX)
            case['samples'].append(dict(key=key, segment=s, link=l, remain=remain, expectedDistance=expected))
        case['completeUrids'] = complete
        case['expectedDistanceDistribution'] = {str(value): sum(s['expectedDistance'] == value for s in case['samples'])
                                                for value in sorted(set(s['expectedDistance'] for s in case['samples']))}
        case['productionFallbackApplied'] = not complete
    return dict(schema=REPLAY_SCHEMA, annotation=annotation['schema'], routeId=annotation['routeId'],
                oddResponse=identity(annotation['_oddResponsePath']), cases=cases,
                scope='Independent Python expectations for production Java replay. Incomplete full routes use the StockFeed MAX fallback; '
                      'the excerpt is a controlled fully annotated subset, not the whole Yandex route. No original-Amap differential or vehicle operation.')


def annotate(args):
    native, exported = _load_oracle(args.events)
    references = _native_links(native, exported)
    snapshot = json.loads(args.snapshot.read_text())
    converted = stock.convert(stock.route_from_snapshot(snapshot), 'native-gcj-unrestricted-candidate')
    path, evidence = annotate_path(converted['path'], references, _projector(references))
    summary = _summary(evidence)
    annotation = dict(schema=SCHEMA, routeId=converted['routeId'], coordinateFrame='native-gcj-unrestricted-candidate',
                      coordinateGrid='stock 1/3600000 degree; local equirectangular metres for matching only',
                      sources=dict(yandexSnapshot=identity(args.snapshot), amapEvents=identity(args.events)),
                      amapReference=dict(pathId=native['pathId'], links=len(references),
                                         lengthM=sum(link['length'] for link in references), local=True,
                                         urids=sorted(set(link['urid'] for link in references)),
                                         adcodes=sorted(set(link['adcode'] for link in references))),
                      matching=dict(sampleStepMaxM=roads.STEP_M, radiusM=roads.RADIUS_M,
                                    minimumHeadingCosine=roads.MIN_COS, maximumHeadingDegrees=60,
                                    minimumMatchedFraction=MIN_MATCH_FRACTION, endpointsIncluded=True,
                                    rule='mapped only when matchedFraction >= threshold and exactly one candidate URID/adcode; otherwise unknown'),
                      yandexRoute=dict(totalM=converted['totalM'], links=sum(len(s['links']) for s in converted['path']['segments'])),
                      summary=summary, links=evidence, stockPath=path, mappedExcerpt=_longest_mapped_excerpt(path, evidence),
                      vehicleOperations=0, publicationReady=False,
                      scope='Route-specific offline annotation against one local Amap route. Geometry disagreement remains unknown; '
                            'not a Russia-wide URID catalog, an original-Amap Yandex differential, or proof of NOA coverage.')
    # Only used to hash the exact response in the replay receipt; not serialized into the annotation.
    annotation['_oddResponsePath'] = str(args.odd_response.resolve())
    replay = make_replay(annotation, args.odd_response.read_bytes())
    del annotation['_oddResponsePath']
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(annotation, indent=2, ensure_ascii=False) + '\n')
    args.replay_out.write_text(json.dumps(replay, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps(dict(annotation=str(args.out), replay=str(args.replay_out), summary=summary,
                          excerptLinks=len(annotation['mappedExcerpt']['linkKeys']) if annotation['mappedExcerpt'] else 0)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    command = sub.add_parser('annotate')
    for name in ('snapshot', 'events', 'odd-response', 'out', 'replay-out'):
        command.add_argument('--' + name, type=Path, required=True)
    command.set_defaults(run=annotate)
    args = parser.parse_args()
    args.run(args)


if __name__ == '__main__':
    main()
