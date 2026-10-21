"""Synthetic producer boundaries; moving-car calibration is not simulated."""
import unittest

from decode_wheel_source import reconstruct


class WheelSourceTests(unittest.TestCase):
    def source(self, payload):
        return reconstruct(can_id=0x122, sub_id=0, channel=1, payload=payload)

    def test_distinct_fields_and_cross_byte_status_order(self):
        result = self.source(bytes.fromhex('01A02301BC0AFF0F'))
        self.assertEqual(result['predictedPacketHex'], '000101230ABC0FFF01000001')
        self.assertEqual([wheel['wheel'] for wheel in result['decodedCandidate']['wheels']],
                         ['FL', 'FR', 'RL', 'RR'])

    def test_each_shared_status_bit_has_its_own_wheel(self):
        for wheel, bit in enumerate((5, 4, 6, 7)):
            payload = bytearray(8)
            payload[1] = 1 << bit
            candidate = self.source(payload)['decodedCandidate']
            self.assertEqual([w['statusRaw'] for w in candidate['wheels']],
                             [int(i == wheel) for i in range(4)])
            self.assertEqual([w['raw12'] for w in candidate['wheels']], [0] * 4)

    def test_high_nibbles_do_not_become_speed_sign_bits(self):
        result = self.source(bytes.fromhex('FFFFFFFFFFFFFFFF'))
        self.assertEqual(result['predictedPacketHex'], '0FFF0FFF0FFF0FFF01010101')
        self.assertFalse(result['decodedCandidate']['acceptedByRecoveredContract'])

    def test_exact_source_tuple_required(self):
        for cid, sub, channel in ((0x121, 0, 1), (0x122, 1, 1), (0x122, 0, 0), (0x122, 0, 2)):
            with self.assertRaises(ValueError):
                reconstruct(can_id=cid, sub_id=sub, channel=channel, payload=bytes(8))

    def test_wrong_lengths_rejected(self):
        for length in (0, 7, 9, 12, 64):
            with self.assertRaises(ValueError):
                self.source(bytes(length))

    def test_stationary_source_does_not_prove_event_unit_or_freshness(self):
        result = self.source(bytes(8))
        self.assertEqual(result['predictedPacketHex'], '00' * 12)
        self.assertFalse(result['eventObserved'])
        self.assertFalse(result['producerGateKnown'])
        self.assertIsNone(result['decodedCandidate']['qualifiedPhysicalUnit'])
        self.assertEqual(result['decodedCandidate']['freshness'], 'unknown')


if __name__ == '__main__':
    unittest.main()
