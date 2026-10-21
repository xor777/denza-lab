"""Wire-format boundary checks; no firmware or vehicle required."""
import unittest
from decode_dicar_config import decode, java_type, varint


class FeatureListTest(unittest.TestCase):
    def test_proto3_defaults_and_repeated_features(self):
        self.assertEqual(decode(bytes.fromhex('0a0208070a0410192001')),
                         [[7, 0, 0, 0], [0, 25, 0, 1]])

    def test_duplicate_scalar_last_value(self):
        self.assertEqual(decode(bytes.fromhex('0a0408010802')), [[2, 0, 0, 0]])

    def test_unknown_or_truncated_messages_fail(self):
        for wire in ['0a03', '0a020880', '1200', '0a022800', '0a0108', '80']:
            with self.subTest(wire=wire), self.assertRaises(ValueError):
                decode(bytes.fromhex(wire))

    def test_varint_bounds(self):
        self.assertEqual(varint(bytes.fromhex('ffffffffffffffffff01'), 0), (2**64 - 1, 10))
        with self.assertRaises(ValueError):
            varint(bytes.fromhex('ffffffffffffffffff02'), 0)

    def test_type_four_is_double_not_float(self):
        self.assertEqual([java_type(i) for i in range(5)], ['int', 'byte[]', 'float', 'double', 'double'])


if __name__ == '__main__':
    unittest.main()
