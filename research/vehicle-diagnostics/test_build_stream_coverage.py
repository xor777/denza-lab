"""Offline regression cases for evidence joins and measurement-quality boundaries."""
import unittest

from build_stream_coverage import export_snapshot_rows, observations, parse_sweep, resolve_devices


def sample(integer='0x00000000', floating=None, step=1):
    return [10 + step, step, step * 1000, integer, floating]


class CoverageEvidenceTest(unittest.TestCase):
    def test_private_or_location_alias_redacts_values_without_erasing_evidence(self):
        original = [sample('0x12345678', '0x41333333')]
        for name in ['SET_HOTSPOT_PASSWORD', 'LOCATION_LATITUDE_INT_SET', 'DEVICE_VIN']:
            public, private = export_snapshot_rows(original, ['innocent_alias', name])
            self.assertTrue(private)
            self.assertEqual(public[0], [11, 1, 1000, None, None])
            self.assertEqual(original[0][3], '0x12345678')
        public, private = export_snapshot_rows(original, ['STEERING_ANGLE'])
        self.assertFalse(private)
        self.assertEqual(public, original)

    def test_same_fid_on_different_devices_is_not_one_channel(self):
        lines = []
        for device in (1000, 1001):
            lines.append(f'CAT\t{device}\t0x12345678\tsame_name')
        for step in (1, 2):
            for device in (1000, 1001):
                lines.append(f'FID\t{step}\t100\t{device}\t0x12345678\t0x00000000\t')
        _, channels = parse_sweep('\n'.join(lines))
        self.assertEqual(set(channels), {(1000, '0x12345678'), (1001, '0x12345678')})

    def test_duplicate_or_partial_pass_is_rejected(self):
        header = 'CAT\t1001\t0x12345678\tname\n'
        first = 'FID\t1\t100\t1001\t0x12345678\t0x00000000\t\n'
        second = first.replace('\t1\t', '\t2\t', 1)
        for body in (first, first + first, second + first):
            with self.subTest(body=body), self.assertRaises(ValueError):
                parse_sweep(header + body)
        with self.assertRaises(ValueError):
            parse_sweep(first + second)  # No CAT record for the observed channel.

    def test_wrong_integer_transact_does_not_discard_float_reply(self):
        types, flags, changed = observations([sample('0xFFFFD8E3', '0x415B3333')])
        self.assertEqual(types, ['float32'])  # -10013 followed by 13.7f.
        self.assertIn('native_error_word_seen', flags)
        self.assertNotIn('no_nonerror_scalar_value', flags)
        self.assertFalse(changed)

    def test_zero_and_marker_are_never_health_proof(self):
        _, flags, _ = observations([sample(), sample(step=2)])
        self.assertIn('zero_only_not_health_or_presence_proof', flags)
        _, flags, _ = observations([sample('0x0000FFFF')])
        self.assertIn('sentinel_like_integer_requires_field_contract', flags)

    def test_invalid_float_is_field_dependent_and_errors_are_retained(self):
        for word, marker in [('0xBF800000', 'minus_one_float_marker_requires_field_contract'),
                             ('0x7FC00000', 'nonfinite_float')]:
            _, flags, _ = observations([sample('0xFFFFD8E3', word)])
            self.assertIn(marker, flags)
            self.assertIn('no_nonerror_scalar_value', flags)
        _, flags, _ = observations([sample('0x800003E8')])
        self.assertIn('no_nonerror_scalar_value', flags)

    def test_changed_raw_value_is_not_called_a_stream(self):
        _, flags, changed = observations([sample(), sample('0x00000001', step=2)])
        self.assertTrue(changed)
        self.assertNotIn('continuous', flags)
        _, flags, changed = observations([])
        self.assertFalse(changed)
        self.assertEqual(flags, {'no_retained_scalar_read'})

    def test_only_source_bound_rse_device_is_resolved(self):
        raw = {
            'rseMapper': b'return BYDAutoRSEDevice.getInstance(context);',
            'rseDevice': b'private static int mDeviceType = BYDAutoConstants.BYDAUTO_DEVICE_RSE;',
            'deviceConstants': b'public static final int BYDAUTO_DEVICE_RSE = 1062;',
        }
        rows = [{'mapper': 'RseMapper', 'nativeDevice': None},
                {'mapper': 'OtherMapper', 'nativeDevice': None},
                {'mapper': 'BodyMapper', 'nativeDevice': 1001}]
        effective, proof = resolve_devices(rows, raw)
        self.assertEqual(effective, [1062, None, 1001])
        self.assertEqual(proof['propertyRowIndexes'], [0])
        self.assertIsNone(rows[0]['nativeDevice'])  # Original evidence is immutable.
        raw['deviceConstants'] = raw['deviceConstants'].replace(b'1062', b'1063')
        with self.assertRaises(ValueError):
            resolve_devices(rows, raw)


if __name__ == '__main__':
    unittest.main()
