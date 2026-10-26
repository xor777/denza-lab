"""Geometric admission and fallback checks for route-specific URID annotation."""
from pathlib import Path
import unittest

from tools.adas_bridge import stock
from tools.adas_bridge.odd_bench import MAX
from tools.adas_bridge.urid_annotation import annotate_path, make_replay

ROOT = Path(__file__).resolve().parents[3]


def project(lon, lat):
    return lon * 100000.0, lat * 100000.0


def link(length, x0=0.0, x1=None, y=0.0):
    if x1 is None:
        x1 = length / 100000.0
    return dict(stock.new_link(), length=length,
                lon=[round(x0 * stock.SCALE), round(x1 * stock.SCALE)],
                lat=[round(y * stock.SCALE), round(y * stock.SCALE)])


def path(links):
    return {'pathId': 0, 'segments': [{'length': sum(x['length'] for x in links), 'travelTime': 0,
            'mainAction': 0, 'assistantAction': 0, 'trafficLightNum': 0, 'links': links}],
            'trafficLights': [], 'groups': []}


def reference(key, urid, x0, x1, y=0.0, adcode=990001):
    return dict(key=key, urid=urid, adcode=adcode, length=round((x1 - x0) * 100000),
                geometry=[(x0, y), (x1, y)])


class UridAnnotationTest(unittest.TestCase):
    def test_geometry_admits_only_unique_well_matched_link(self):
        annotated, evidence = annotate_path(path([link(120)]), [reference('a', 372, 0, 0.0012)], project)
        self.assertEqual('mapped', evidence[0]['status'])
        self.assertEqual(1.0, evidence[0]['matchedFraction'])
        self.assertEqual((990001, 372), (annotated['segments'][0]['links'][0]['adcode'],
                                        annotated['segments'][0]['links'][0]['urid']))

    def test_missing_geometry_and_partial_match_stay_unknown(self):
        cases = [([reference('a', 372, 0, 0.0012)], link(120, y=0.0004)),
                 ([reference('a', 372, 0.00108, 0.0012)], link(120))]
        for references, yandex in cases:
            with self.subTest(references=references, yandex=yandex):
                annotated, evidence = annotate_path(path([yandex]), references, project)
                self.assertEqual('unmapped', evidence[0]['status'])
                self.assertEqual(0, annotated['segments'][0]['links'][0]['urid'])
                self.assertEqual(0, annotated['segments'][0]['links'][0]['adcode'])

    def test_conflicting_urids_are_ambiguous_not_a_coin_flip(self):
        references = [reference('a', 372, 0, 0.0012), reference('b', 1, 0, 0.0012)]
        annotated, evidence = annotate_path(path([link(120)]), references, project)
        self.assertEqual('ambiguous', evidence[0]['status'])
        self.assertEqual([1, 372], evidence[0]['candidateUrids'])
        self.assertEqual(0, annotated['segments'][0]['links'][0]['urid'])

    def test_replay_preserves_full_route_fallback_and_finite_excerpt_boundary(self):
        response = ROOT / 'captures/adas-h0-listen-20261024/H0-odd-response.bin'
        full = path([link(100), link(200)])
        full['segments'][0]['links'][0].update(adcode=990001, urid=372)
        excerpt = path([link(100), link(200)])
        for row in excerpt['segments'][0]['links']:
            row.update(adcode=990001, urid=372)
        annotation = dict(schema='denza-urid-route-annotation-v1', routeId='fixture', stockPath=full,
                          mappedExcerpt=dict(linkKeys=['old:0', 'old:1'], stockPath=excerpt),
                          _oddResponsePath=str(response))
        replay = make_replay(annotation, response.read_bytes())
        full_case, actual_case, boundary_case = replay['cases']
        self.assertTrue(full_case['productionFallbackApplied'])
        self.assertEqual({str(MAX): 6}, full_case['expectedDistanceDistribution'])
        self.assertEqual({str(MAX): 6}, actual_case['expectedDistanceDistribution'])
        self.assertEqual([100, 50, 0, 0, 0, 0], [s['expectedDistance'] for s in boundary_case['samples']])
        self.assertEqual({'old:1': 1}, boundary_case['uridOverrides'])


if __name__ == '__main__':
    unittest.main()
