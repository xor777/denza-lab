"""Negative boundaries and synthetic child cases; the ECM control is captured."""
import json
from pathlib import Path
import unittest
from decode_child_identity import decode


CODE = '32440011E004000000'
FRAME = bytes.fromhex('0A07E80C01010162F194' + CODE)


def call(frame=FRAME, **overrides):
    args = dict(request_id=0x7E0, response_id=0x7E8, did=0xF194, cached_code=CODE)
    args.update(overrides)
    return decode(frame, **args)


class ChildIdentityTest(unittest.TestCase):
    def test_recorded_ecm_control_and_unused_tail(self):
        # captures/diagnostics-candidates-20261020/candidates1-run/probe.txt:4
        for frame in (FRAME, FRAME + b'\xff' * 40):
            self.assertEqual(call(frame)['codeHex'], CODE)
            self.assertTrue(call(frame)['matchesCachedCode'])

    def test_all_eight_child_fixtures_are_synthetic(self):
        rows = json.loads(Path(__file__).with_name('ecu-child-identification-candidates-20261021.json').read_text())['candidates']
        self.assertEqual(len(rows), 8)
        for row in rows:
            with self.subTest(ecu=row['ecuId']):
                route = row['route']
                frame = b'\x0a' + route['responseId'].to_bytes(2, 'big') + bytes.fromhex('0C010101' + row['expectedPositivePrefixHex'] + row['cachedSoftwareCode'])
                answer = call(frame, request_id=route['requestId'], response_id=route['responseId'], did=int(row['codeDid'], 16), cached_code=row['cachedSoftwareCode'])
                self.assertTrue(answer['matchesCachedCode'])
                self.assertFalse(answer['childDtcRead'])

    def test_wrong_did_on_same_parent_is_rejected(self):
        with self.assertRaises(ValueError):
            call(did=0xF8C4)

    def test_foreign_and_partial_frames_are_rejected(self):
        bad = [b'', FRAME[:8], b'\x01' + FRAME[1:], FRAME[:2] + b'\xe9' + FRAME[3:],
               FRAME[:4] + b'\x02\x01' + FRAME[6:], FRAME[:4] + b'\x01\x02' + FRAME[6:],
               FRAME[:6] + b'\x00' + FRAME[7:]]
        for frame in bad:
            with self.subTest(frame=frame.hex()), self.assertRaises(ValueError):
                call(frame)

    def test_no_response_and_pending_are_not_identities(self):
        for code in (3, 4, 5):
            row = call(bytes.fromhex('0A07E8000101') + bytes([code]))
            self.assertIsNone(row['codeHex'])
            self.assertIsNone(row['matchesCachedCode'])
        row = call(bytes.fromhex('0A07E8030101027F2278'))
        self.assertEqual(row['state'], 'response_pending')
        self.assertIsNone(row['codeHex'])

    def test_stale_cache_and_zero_are_not_confirmed_identity(self):
        self.assertFalse(call(cached_code='32440011E004000001')['matchesCachedCode'])
        row = call(FRAME[:10] + b'\0' * 9)
        self.assertEqual(row['state'], 'zero_placeholder')
        self.assertFalse(row['matchesCachedCode'])

    def test_wrong_length_and_negative_service_are_rejected(self):
        for frame in (FRAME[:3] + b'\x0b' + FRAME[4:], bytes.fromhex('0A07E8030101027F1978')):
            with self.assertRaises(ValueError):
                call(frame)


if __name__ == '__main__':
    unittest.main()
