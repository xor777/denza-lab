"""Independent decoder/re-encoder for retained original Amap facility payloads."""
import hashlib
import json
from pathlib import Path
import unittest
from tools.adas_bridge import stock

ROOT = Path(__file__).resolve().parents[3]


class FactoryFacilitiesWireTests(unittest.TestCase):
    def test_original_packets(self):
        proof = json.loads((ROOT / 'captures/yandex-followup-20261024/facilities-proof.json').read_text())
        pins = {p['path']: p for p in proof['sourcePins']}
        checked = {}
        for row in proof['beijing']['rawPacketGoldens']:
            source = row['source']
            if source not in checked:
                # Source trace remains authoritative; sourcePins may include only producer code.
                lines = (ROOT / source).read_text().splitlines()
                self.assertIn(row['payloadBase64'], lines[row['sourceLine'] - 1])
                if source in pins:
                    self.assertEqual(hashlib.sha256((ROOT / source).read_bytes()).hexdigest(), pins[source]['sha256'])
                checked[source] = lines
            self.assertIn(row['payloadBase64'], checked[source][row['sourceLine'] - 1])
            raw = bytes.fromhex(row['payloadHex'])
            self.assertEqual(hashlib.sha256(raw).hexdigest(), row['sha256'])
            decoded = stock.decode_topic(1268847189590021, raw)['roadFacilities']
            self.assertEqual(decoded, row['fields'])
            f = row['fields']
            inner = stock.ROAD_FACILITIES.encode(f.get('naviFacilityType', 0), f.get('cruiseFacilityType', 0),
                f.get('distance', 0), f.get('boardSignLocationLon', 0), f.get('boardSignLocationLat', 0), None, None, None)
            self.assertEqual(stock.ROAD_FACILITIES_NOTIFY.encode(inner), raw)


if __name__ == '__main__':
    unittest.main()
