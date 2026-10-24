"""Independent Python decode/re-encoding of actual original Amap Beijing fires."""
import hashlib
import json
from pathlib import Path
import unittest

from tools.adas_bridge import stock

ROOT = Path(__file__).resolve().parents[3]
FIXTURES = ROOT / 'captures/yandex-feed11-20261024/lanes/factory-wire-fixtures.json'


class FactoryLaneWireTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixtures = json.loads(FIXTURES.read_text())
        for row in cls.fixtures:
            raw = (ROOT / row['source']).read_bytes()
            if hashlib.sha256(raw).hexdigest() != row['sourceSha256']:
                raise AssertionError('Factory source hash changed')

    def test_intersection_factory_lane_order_and_default_omission(self):
        row = self.fixtures[0]
        raw = bytes.fromhex(row['payloadHex'])
        body = stock.decode_topic(int(row['topic']), raw)['intersection']
        self.assertEqual(body, {'foreground':'0101ffff', 'background':'01010303', 'numOfLaneOfNextIntscn':4})
        inner = stock.INTERSECTION.encode(0, bytes.fromhex('0101ffff'), bytes.fromhex('01010303'), 4, None, None, None)
        self.assertEqual(stock.INTERSECTION_NOTIFY.encode(inner), raw)

    def test_next_lane_factory_bytes_and_double_timestamp(self):
        row = self.fixtures[1]
        raw = bytes.fromhex(row['payloadHex'])
        body = stock.decode_topic(int(row['topic']), raw)['nextIntersectionLanesInfo']
        self.assertEqual(body['backLane'], '01010303')
        self.assertEqual(body['frontLane'], '0101ffff')
        self.assertEqual(body['extendLane'], '00000000')
        for key in ('recomendLane', 'backLaneType', 'frontLaneType'):
            self.assertEqual(body[key], 'ffffffff')
        self.assertEqual((body['segmentIndex'], body['linkIndex']), (3, 17))
        self.assertIsInstance(body['timestamp'], float)
        self.assertLessEqual(abs(body['timestamp'] - row['record']['wall_ms']), 10)
        arrays = [bytes.fromhex(body[key]) for key in ('backLane','frontLane','extendLane','recomendLane','backLaneType','frontLaneType')]
        inner = stock.NEXT_LANES.encode(*arrays, 3, 17, body['timestamp'])
        self.assertEqual(stock.NEXT_LANES_NOTIFY.encode(inner), raw)

    def test_clear_is_empty_message_not_a_fabricated_lane(self):
        empty = stock.INTERSECTION_NOTIFY.encode(stock.INTERSECTION.encode(0, b'', b'', 0, None, None, None))
        self.assertEqual(empty.hex(), '0a00')
        self.assertEqual(stock.INTERSECTION_NOTIFY.decode(empty), {'intersection':{}})


if __name__ == '__main__':
    unittest.main()
