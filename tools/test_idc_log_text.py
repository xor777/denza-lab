"""Offline tests for the strict IdcLogCollect container reader."""
import json
from pathlib import Path
import struct
import tempfile
import unittest

import idc_log_text as reader


def timestamp(epoch_ms):
    return struct.pack('>Q', epoch_ms)


def frame(header, payload=b''):
    if len(payload) > reader.FRAME_SIZE - 2:
        raise ValueError('test payload too large')
    return header + payload.ljust(reader.FRAME_SIZE - 2, b'\0')


def text_frame(text):
    return frame(reader.TEXT_HEADER, text.encode('utf-8'))


class IdcLogTextTests(unittest.TestCase):
    def test_mixed_stream_and_external_timestamps(self):
        first_ms = 1793433696747
        second_ms = first_ms + 1000
        first = '9.880868048 2026/10/31 08:01:36 Info [MCU] started'
        second = '9.880868148 2026/10/31 08:01:37 Error [AVM] synthetic failure'
        data = (timestamp(first_ms) + text_frame(first) + frame(reader.BINARY_HEADER, b'\x01\x02') +
                frame(reader.SECONDARY_BINARY_HEADER, b'\x03\x04') +
                timestamp(second_ms) + text_frame(second))
        summary, records = reader.parse_idc_log(data)
        self.assertEqual(summary['consumedBytes'], len(data))
        self.assertEqual(summary['collectorTimestamps']['count'], 2)
        self.assertEqual({key: value for key, value in summary['frames']['byType'].items() if value},
                         {'0x0049': 1, '0x0149': 1, '0x0159': 2})
        self.assertEqual(summary['binaryFrames'], 2)
        self.assertEqual([record['text'] for record in records], [first, second])
        self.assertEqual(records[0]['offset'], 8)
        self.assertEqual(records[1]['offset'], 8 + 3 * reader.FRAME_SIZE + 8)
        self.assertEqual(records[0]['collectorTimestampUtc'], reader.utc_iso(first_ms))
        self.assertEqual(records[1]['collectorTimestampUtc'], reader.utc_iso(second_ms))

    def test_ascii_inside_binary_telemetry_is_not_text(self):
        misleading = b'12.0 2026/10/31 08:01:36 Info [NOT-A-LOG]'
        data = timestamp(reader.MIN_EPOCH_MS) + frame(reader.BINARY_HEADER, misleading)
        summary, records = reader.parse_idc_log(data)
        self.assertEqual(records, [])
        self.assertEqual(summary['binaryFrames'], 1)

    def test_malformed_text_payload_is_not_emitted(self):
        data = (timestamp(reader.MIN_EPOCH_MS) +
                text_frame('random ASCII without the IDC text prefix'))
        summary, records = reader.parse_idc_log(data)
        self.assertEqual(records, [])
        self.assertEqual(summary['nonTextPayloadFrames'], 1)

    def test_truncated_tail_is_an_error(self):
        data = timestamp(reader.MIN_EPOCH_MS) + frame(reader.BINARY_HEADER)[:-1]
        with self.assertRaisesRegex(reader.IdcLogFormatError, 'truncated frame'):
            reader.parse_idc_log(data)

    def test_unknown_header_is_an_error(self):
        data = timestamp(reader.MIN_EPOCH_MS) + frame(b'\x00\x01')
        with self.assertRaisesRegex(reader.IdcLogFormatError, 'unknown frame header'):
            reader.parse_idc_log(data)

    def test_timestamp_without_frame_is_an_error(self):
        with self.assertRaisesRegex(reader.IdcLogFormatError, 'no following frame'):
            reader.parse_idc_log(timestamp(reader.MIN_EPOCH_MS))

    def test_outputs_are_not_overwritten(self):
        data = timestamp(reader.MIN_EPOCH_MS) + frame(reader.BINARY_HEADER)
        summary, records = reader.parse_idc_log(data)
        with tempfile.TemporaryDirectory() as tmp:
            prefix = Path(tmp) / 'decoded'
            paths = reader.write_outputs(prefix, summary, records)
            self.assertTrue(paths['.summary.json'].exists())
            self.assertEqual(json.loads(paths['.summary.json'].read_text()), summary)
            with self.assertRaisesRegex(FileExistsError, 'refusing to overwrite'):
                reader.write_outputs(prefix, summary, records)


if __name__ == '__main__':
    unittest.main()
