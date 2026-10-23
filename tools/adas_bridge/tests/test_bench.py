"""ADAS bench: feed decoding, Amap-semantics coverage, synthetic Yandex input and the comparator."""
import copy
import json
import struct
import unittest

from tools.adas_bridge import compare, coverage
from tools.adas_bridge.bridge import route_convert
from tools.adas_bridge.bridge_trace import Projector, snapshot_from_stock
from tools.adas_bridge.dynamic import POSITION, encode_struct
from tools.adas_bridge.feed import NAMES, RouteAssembler, decode, decode_event
from tools.adas_bridge.wire import encode_binder_route, varint


def link(**fields):
    base = dict(linkID=1, roadclass=9, roadname='', len=100, linktype=0, formway=15, hasTrafficLight=False, laneNum=2,
                hasMultiOut=False, mainAction=8, assistantAction=0, adminCode=110000, ownership=0, hasParallel=False,
                direction=0, speedLimit=0, hasMixFork=False, pntBegIdx=0, pntCnt=2)
    base.update(fields)
    return base


def stock_route(classes=((0, 15), (0, 9), (7, 15)), light_on=None, path_id=100002):
    """A straight east-going route, one link per (class, formway), 100 m each, duplicated boundaries."""
    links, pnts, x = [], [], 116.40
    step = 100 / (111320.0 * 0.7660)
    for i, (rc, fw) in enumerate(classes):
        links.append(link(linkID=i + 1, roadclass=rc, formway=fw, pntBegIdx=len(pnts), pntCnt=2,
                          hasTrafficLight=i == light_on, mainAction=2 if i == 1 else 8))
        pnts += [{'x': x, 'y': 40.0}, {'x': x + step, 'y': 40.0}]
        x += step
    end = len(classes) - 1
    segments = [dict(crntSegmLinkCnt=2, linkBegIdx=0, navigationLen=200, navigationMainAction=2, navigationAssitAction=0,
                     trafficLightNum=0, isArriveWayPoint=False, description='right', navigationNextRoadName='B'),
                dict(crntSegmLinkCnt=end - 1, linkBegIdx=2, navigationLen=100 * (end - 1), navigationMainAction=0,
                     navigationAssitAction=36, trafficLightNum=0, isArriveWayPoint=True, description='arrive',
                     navigationNextRoadName='')]
    lights = []
    if light_on is not None:
        p = pnts[links[light_on]['pntBegIdx'] + 1]
        lights.append({'x': p['x'], 'y': p['y']})
    return dict(pathID=path_id, linkCnt=len(links), links=links, pntCnt=len(pnts), pnts=pnts,
                guideGroups=[dict(groupName='A', groupIconType=2, groupLen=300, groupTime=30, groupTrafficLightsCount=0,
                                  groupEnterCoord=pnts[0], segments=segments)],
                trafficLights=lights, viaRoadInfo=[])


def trace(side, route, *, positions, statuses, extra=()):
    envelope = dict(msg='response', code=200, data=[route], endPOIInfo=dict(AVPMapIdList=[], PTPStateFlag=1),
                    RouteType=1, mapProvider=1)
    events = [dict(t_ms=1000, kind='fire', name='Route', topic=NAMES['Route'], fields={}, route=envelope),
              dict(t_ms=1001, kind='fire', name='PathSelected', topic=NAMES['PathSelected'], fields={'pathIDReq': route['pathID']})]
    for t, fields in positions:
        events.append(dict(t_ms=t, kind='fire', name='VehicleLocation', topic=NAMES['VehicleLocation'],
                           fields=dict(fields, curSDRouteID=float(route['pathID']))))
    for t, value in statuses:
        events.append(dict(t_ms=t, kind='fire', name='NavigationStatus', topic=NAMES['NavigationStatus'],
                           fields={'navigationStatus': value}))
    events += list(extra)
    events.sort(key=lambda e: e['t_ms'])
    return dict(schema='denza-adas-feed-trace-v1', side=side, label=side, provenance={'source': side}, events=events)


class FeedTest(unittest.TestCase):
    def test_position_decodes_by_stock_field_names_and_negative_int32(self):
        data = encode_struct({'latitude': 40.0, 'longitude': 116.4, 'curSDRouteID': 100002, 'curStepId': -1,
                              'linkOffset': 55}, POSITION)
        event = decode_event(NAMES['VehicleLocation'], data)
        self.assertEqual(event['name'], 'VehicleLocation')
        self.assertEqual((event['service'], event['event']), (0x8202, 0x8008))
        self.assertEqual(event['fields']['curStepId'], -1)
        self.assertEqual(event['fields']['curSDRouteID'], 100002.0)
        self.assertEqual(event['fields']['locationLatitude'], 40.0)
        self.assertNotIn('curLinkId', event['fields'])  # absent proto3 scalar stays absent

    def test_route_chunks_reassemble_only_when_complete(self):
        payload = json.dumps({'data': [{'pathID': 7}]})
        halves = [payload[:10], payload[10:]]
        assembler, results = RouteAssembler(), []
        for i, part in enumerate(halves):
            text = json.dumps({'time': 5, 'length': 2, 'pathCount': 1, 'currentIndex': i, 'payload': part})
            results.append(decode_event(NAMES['Route'], encode_binder_route(text, route_count=1), assembler))
        self.assertNotIn('route', results[0])
        self.assertEqual(results[1]['route']['data'][0]['pathID'], 7)

    def test_unknown_field_is_kept_by_number(self):
        data = varint(99 << 3) + varint(5)
        self.assertEqual(decode('TrafficInfo', data), {'#99': 5})


class CoverageTest(unittest.TestCase):
    def test_exit_ramp_signal_tunnel_and_roundabout(self):
        route = stock_route(classes=((0, 15), (0, 9), (7, 15), (7, 4), (9, 15)), light_on=2)
        route['links'][4]['linktype'] = 2
        kinds = coverage.kinds(route)
        for kind in ('expressway->exit->arterial', 'roundabout-entry', 'roundabout-exit', 'tunnel', 'start:expressway',
                     'arrival:ordinary'):
            self.assertIn(kind, kinds)
        self.assertTrue(any(k.startswith('signal:') for k in kinds))

    def test_greedy_prefers_new_kinds(self):
        a = stock_route(classes=((0, 15), (0, 9), (7, 15)))
        b = copy.deepcopy(a)
        c = stock_route(classes=((9, 15), (9, 4), (9, 15)))
        chosen, covered = coverage.greedy({'a': a, 'b': b, 'c': c}, 3)
        self.assertEqual({i for i, _ in chosen}, {'a', 'c'})
        self.assertIn('roundabout-entry', covered)


class SyntheticYandexTest(unittest.TestCase):
    def test_stock_route_becomes_a_convertible_snapshot_with_shifted_actions(self):
        route = stock_route(light_on=0)
        snapshot = snapshot_from_stock(route, route_id='r', session='s', generation=1)
        self.assertEqual(len(snapshot['points']), 4)  # boundaries deduplicated
        converted = route_convert(snapshot, target_frame='wgs84')
        segments = converted['candidateNaviToEHPData']['guideGroups'][0]['segments']
        self.assertEqual(len(segments), 2)
        self.assertEqual(segments[0]['navigationMainAction'], 2)       # stock segment 0 ends with a right turn
        self.assertEqual(segments[-1]['navigationAssitAction'], 36)     # arrival from the way points
        self.assertTrue(converted['candidateNaviToEHPData']['links'][0]['hasTrafficLight'])

    def test_projector_is_monotone_and_reports_gap(self):
        projector = Projector([[116.40, 40.0], [116.41, 40.0]])
        position, gap, along = projector.project([116.405, 40.0001])
        self.assertLess(gap, 15)
        self.assertGreater(along, 0)
        self.assertEqual(position['segmentIndex'], 0)


class CompareTest(unittest.TestCase):
    def positions(self, offsets):
        return [(2000 + 1000 * i, {'curStepId': 0, 'linkOffset': o, 'distanceToNextStep': 200 - 10 * i,
                                   'locationLongitudeAssociateRoad': 116.40, 'locationLatitudeAssociateRoad': 40.0})
                for i, o in enumerate(offsets)]

    def test_identical_traces_have_no_bridge_defect(self):
        route = stock_route()
        stock = trace('stock', route, positions=self.positions([90, 80, 70]), statuses=[(500, 100), (1500, 101)])
        result = compare.compare(stock, copy.deepcopy(stock))
        self.assertEqual([d for d in result['differences'] if d['cls'] == 'bridge-defect'], [])

    def test_missing_extras_and_idle_status_are_stock_not_modelled(self):
        route = stock_route()
        extra = [dict(t_ms=2000 + i * 1000, kind='fire', name='NextAction', topic=NAMES['NextAction'], fields={'mainAction': 2})
                 for i in range(3)]
        stock = trace('stock', route, positions=self.positions([90, 80, 70]), statuses=[(500, 100), (1500, 101)], extra=extra)
        bridge = trace('bridge', route, positions=self.positions([90, 80, 70]), statuses=[(1500, 101)])
        classes = {(d['cls'], d['text'].split(':')[0]) for d in compare.compare(stock, bridge)['differences']}
        self.assertIn(('stock-not-modelled', 'NextAction'), classes)
        self.assertTrue(any(c == 'stock-not-modelled' and 'idle' in t for c, t in
                            ((d['cls'], d['text']) for d in compare.compare(stock, bridge)['differences'])))

    def test_content_difference_is_not_a_defect_without_factory_reference(self):
        route = stock_route()
        other = copy.deepcopy(route)
        for l in other['links']:
            l['linktype'] = 3
        stock = trace('stock', route, positions=self.positions([90]), statuses=[(1500, 101)])
        bridge = trace('bridge', other, positions=self.positions([90]), statuses=[(1500, 101)])
        cls = lambda ref: {d['cls'] for d in compare.compare(stock, bridge, content_reference=ref)['differences']
                           if d.get('field') == 'linktype'}
        self.assertEqual(cls(False), {'source-data-gap'})  # Yandex has no link-type source except tunnels
        other['links'][0]['hasTrafficLight'] = True
        bridge = trace('bridge', other, positions=self.positions([90]), statuses=[(1500, 101)])
        light = [d['cls'] for d in compare.compare(stock, bridge)['differences'] if d.get('field') == 'hasTrafficLight']
        self.assertEqual(light, ['reference-not-authoritative'])
        light = [d['cls'] for d in compare.compare(stock, bridge, content_reference=True)['differences']
                 if d.get('field') == 'hasTrafficLight']
        self.assertEqual(light, ['bridge-defect'])

    def test_per_tick_offset_semantics_difference_is_a_defect(self):
        route = stock_route()
        stock = trace('stock', route, positions=self.positions([90, 80, 70, 60]), statuses=[(1500, 101)])
        bridge = trace('bridge', route, positions=self.positions([190, 180, 170, 160]), statuses=[(1500, 101)])
        texts = [d['text'] for d in compare.compare(stock, bridge)['differences'] if d['cls'] == 'bridge-defect']
        self.assertTrue(any(t.startswith('linkOffset') for t in texts))

    def test_amap_semantics_light_belongs_to_incoming_link(self):
        route = stock_route(light_on=1)
        self.assertEqual(compare.semantics(route, 'stock'), [])
        moved = copy.deepcopy(route)
        moved['trafficLights'] = [dict(moved['pnts'][0])]
        rules = [f['rule'] for f in compare.semantics(moved, 'bridge')]
        self.assertTrue(any('incoming link' in r for r in rules))
        moved = copy.deepcopy(route)
        moved['links'][0]['roadclass'] = -1
        moved['links'][0]['laneNum'] = None
        rules = ' '.join(f['rule'] for f in compare.semantics(moved, 'bridge'))
        self.assertIn('roadclass', rules)
        self.assertIn('laneNum', rules)


if __name__ == '__main__':
    unittest.main()
