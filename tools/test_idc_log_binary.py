"""Synthetic checks for the binary IDC record envelope decoder."""
import struct
import unittest

import idc_log_binary as decoder
import idc_log_text as container


def binary_frame(sequence, marker=0x38, end=0xFE):
    payload = bytearray(253)
    payload[0] = sequence & 0xFF
    payload[1] = marker
    payload[2:7] = bytes((10, 31, 12, 34, 56))
    payload[8:11] = (123456).to_bytes(3, 'big')
    payload[11:19] = bytes.fromhex('020069290f1a0106')
    payload[-1] = end
    return bytes((0x49, sequence >> 8)) + payload


def stream(*frames):
    return struct.pack('>Q', container.MIN_EPOCH_MS) + b''.join(frames)


class IdcLogBinaryTests(unittest.TestCase):
    def test_sequence_time_tag_and_body(self):
        summary, records = decoder.decode_binary(
            stream(binary_frame(255), binary_frame(256)), 2026)
        self.assertEqual([record['sequence'] for record in records], [255, 256])
        self.assertEqual(records[0]['controllerTime'], '2026-10-31T12:34:56')
        self.assertEqual(records[0]['uptimeMs'], 123456)
        self.assertEqual(records[0]['protocolTagHex'], '020069290f1a0106')
        self.assertEqual(len(bytes.fromhex(records[0]['bodyHex'])), 233)
        self.assertEqual(summary['sequenceDiscontinuities'], [])
        self.assertEqual(summary['normalRecords'], 2)
        self.assertEqual(summary['normalUptimeDeltaMs'], {'0': 1})

    def test_unsynchronized_startup_marker_is_preserved(self):
        payload = bytearray(binary_frame(1, marker=0)[2:])
        payload[2:7] = bytes((1, 1, 0, 0, 3))
        frame = b'\x49\x00' + payload
        summary, records = decoder.decode_binary(stream(frame), 2026)
        self.assertEqual(records[0]['markerHex'], '0x00')
        self.assertEqual(records[0]['controllerTime'], '2026-01-01T00:00:03')
        self.assertEqual(summary['markers'], {'0x00': 1})
        self.assertEqual(summary['normalRecords'], 0)

    def test_missing_end_marker_is_an_error(self):
        with self.assertRaisesRegex(decoder.IdcBinaryFormatError, 'end marker'):
            decoder.decode_binary(stream(binary_frame(1, end=0)), 2026)

    def test_sequence_reset_is_explicit(self):
        summary, _ = decoder.decode_binary(
            stream(binary_frame(300), binary_frame(2)), 2026)
        self.assertEqual(summary['sequenceDiscontinuities'], [{
            'previousSequence': 300,
            'sequence': 2,
            'deltaMod65536': 65238,
            'previousControllerTime': '2026-10-31T12:34:56',
            'controllerTime': '2026-10-31T12:34:56',
        }])


if __name__ == '__main__':
    unittest.main()
