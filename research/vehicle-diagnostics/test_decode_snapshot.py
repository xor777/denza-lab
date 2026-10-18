import unittest

from decode_snapshot import decode, payload


def frame(data):
    packet = bytes([10, 7, 0x28, len(data), 1, 1, 1]) + bytes(data)
    return "FRAME phase=response raw=" + packet.hex().upper()


# Real payloads; the original 240-byte callback tails stay in ignored captures.
COUNT = frame(bytes.fromhex("5901FF000008"))
LIST = frame(bytes.fromhex(
    "5902FF51600628C29D8728C1218728A1480228A2500028A25B0028C2298728C22A8728"))


class DecoderTest(unittest.TestCase):
    def test_real_snapshot_keeps_saved_separate_from_active(self):
        result = decode(COUNT, LIST)
        self.assertEqual(result['read_count'], 8)
        self.assertEqual(result['entries'][2]['dtc_raw'], 'C12187')
        for record in result['entries']:
            self.assertEqual(record['status_flags'],
                             ['confirmedDTC', 'testFailedSinceLastClear'])

    def test_callback_duplicates_and_tail_are_not_extra_dtcs(self):
        padded = LIST + '32' + 'FF' * 197
        self.assertEqual(decode(COUNT + '\n' + COUNT, padded + '\n' + padded),
                         decode(COUNT, LIST))

    def test_lbms_zero_is_scoped_and_support_is_only_09(self):
        count = frame(bytes.fromhex("590109000000")).replace("0A0728", "0A076A")
        listed = frame(bytes.fromhex("590209")).replace("0A0728", "0A076A")
        result = decode(count, listed, "lbms")
        self.assertEqual(result['read_count'], 0)
        self.assertEqual(result['status_availability_mask'], '09')
        self.assertEqual(result['response_id'], '0x76A')
        with self.assertRaises(ValueError):
            decode(count, listed)

    def test_empty_transport_is_not_zero_faults(self):
        with self.assertRaises(ValueError):
            decode('', '')

    def test_explicit_complete_zero_response(self):
        self.assertEqual(decode(frame([0x59, 1, 255, 0, 0, 0]),
                                frame([0x59, 2, 255]))['read_count'], 0)

    def test_truncated_packet_is_not_padded(self):
        with self.assertRaisesRegex(ValueError, 'Truncated'):
            payload(LIST[:-2], 2)

    def test_foreign_address_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'address'):
            payload(LIST.replace('0A0728', '0A074F'), 2)

    def test_multi_frame_is_not_misreported_as_complete(self):
        with self.assertRaisesRegex(ValueError, 'Multi-frame'):
            payload(LIST.replace('23010101', '23020101'), 2)

    def test_negative_result_is_not_empty_list(self):
        with self.assertRaisesRegex(ValueError, 'not success'):
            payload(frame([0x7F, 0x19, 0x22]).replace('03010101', '03010102'), 2)

    def test_changed_count_is_explicit(self):
        with self.assertRaisesRegex(ValueError, 'Count/list differ'):
            decode(COUNT[:-2] + '09', LIST)


if __name__ == '__main__':
    unittest.main()
