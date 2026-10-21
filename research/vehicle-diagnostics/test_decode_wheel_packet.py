"""Synthetic boundary checks, not evidence of live wheel measurements."""
import unittest

from decode_wheel_packet import decode


class WheelPacketTest(unittest.TestCase):
    def test_distinct_big_endian_words_preserve_producer_order(self):
        result = decode(bytes.fromhex('000101230ABC0FFF00000000'))
        self.assertTrue(result['acceptedByRecoveredContract'])
        self.assertEqual([w['wheel'] for w in result['wheels']], ['FL', 'FR', 'RL', 'RR'])
        self.assertEqual([w['raw12'] for w in result['wheels']], [1, 291, 2748, 4095])

    def test_each_status_is_preserved_and_blocks_consumer(self):
        for index in range(4):
            packet = bytearray(12)
            packet[8 + index] = 1
            result = decode(packet)
            self.assertTrue(result['structurallyValid'])
            self.assertFalse(result['acceptedByRecoveredContract'])
            self.assertEqual(result['wheels'][index]['statusRaw'], 1)

    def test_out_of_domain_speed_is_not_masked_or_sign_extended(self):
        for word in (0x1000, 0x8000, 0xFFFF):
            result = decode(word.to_bytes(2, 'big') + bytes(10))
            self.assertFalse(result['structurallyValid'])
            self.assertFalse(result['acceptedByRecoveredContract'])
            self.assertTrue(result['glStatusGatePasses'])
            self.assertEqual(result['wheels'][0]['raw16'], word)
            self.assertIsNone(result['wheels'][0]['raw12'])
            self.assertIsNone(result['wheels'][0]['glScaledCandidate'])

    def test_out_of_domain_status_is_not_a_fault_definition(self):
        result = decode(bytes(8) + b'\x02\x00\x00\x00')
        self.assertFalse(result['structurallyValid'])
        self.assertEqual(result['wheels'][0]['statusRaw'], 2)

    def test_wrong_size_and_mcu_offline_placeholder_are_rejected(self):
        for raw in (b'', bytes(11), bytes(13), b'MCU_OFFLINE'):
            result = decode(raw)
            self.assertFalse(result['structurallyValid'])
            self.assertFalse(result['acceptedByRecoveredContract'])
            self.assertEqual(result['wheels'], [])

    def test_zero_packet_does_not_establish_units_or_freshness(self):
        result = decode(bytes(12))
        self.assertTrue(result['acceptedByRecoveredContract'])
        self.assertIsNone(result['qualifiedPhysicalUnit'])
        self.assertEqual(result['freshness'], 'unknown')
        self.assertEqual([w['glScaledCandidate'] for w in result['wheels']], [0.0] * 4)

    def test_gl_float_arithmetic_at_nonzero_and_maximum(self):
        result = decode(bytes.fromhex('000100900FFF000000000000'))
        values = [w['glScaledCandidate'] for w in result['wheels']]
        self.assertAlmostEqual(values[0], 19.09722328186035, places=6)
        self.assertAlmostEqual(values[1], 2750.0, places=3)
        self.assertAlmostEqual(values[2], 78203.125, places=2)


if __name__ == '__main__':
    unittest.main()
