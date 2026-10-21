import json
from pathlib import Path
import unittest

from decode_isg_gateway_trace import decode_capture, decode_can_trace, join_report


HERE = Path(__file__).resolve().parent
TEXT = (HERE/'fixtures/isg-failure-report-20261021.txt').read_text()
PLAN = {'plans': {'isg-trace': dict(maxRequests=3, overallSoftMs=15000, overallHardMs=17000,
    targets=[dict(name='ISG', requestIdHex='7E6', responseIdHex='7EE',
        expectedSoftwareCode='48370011E0EA000000', requests=['22F194', '190109', '190A'], expectedFormatIdHex='01')])}}


class IsgTraceReplayTest(unittest.TestCase):
    def test_live_fixture_report_is_complete_but_uds_is_partial(self):
        v = decode_capture(TEXT, PLAN)
        report, can = v['report'], v['report']['can']
        self.assertTrue(report['publishedReportComplete'])
        self.assertEqual((report['traceByteCount'], can['nativeRecordCount']), (260, 20))
        self.assertEqual((can['recoveredUdsBytes'], can['declaredUdsBytes'], can['unrecoveredByteCount']), (125, 231, 106))
        self.assertEqual((can['completeRawRecordCount'], can['partialDtcTailHex']), (30, '183F'))
        self.assertTrue(can['sequenceWrapObserved'])
        self.assertFalse(can['reconstructedUdsComplete'])
        self.assertFalse(can['acceptedSuccessfulRegistry'])
        self.assertFalse(can['displayCodeConversionApplied'])
        self.assertEqual(v['formatRead']['formatId'], 1)
        self.assertEqual(v['formatRead']['memoryCountForMask09'], 0)
        self.assertEqual(v['events'][2]['requestHex'], '190A')
        self.assertEqual(report['retainedRequestPrefixHex'], '190AFC')
        self.assertEqual(v['qualifiedFactoryDefinitions'], [])

    def test_corrupt_transcripts_fail_closed(self):
        lines = TEXT.splitlines()
        first = next(l for l in lines if l.startswith('GATEWAY_TRACE_FRAME'))
        last = [l for l in lines if l.startswith('GATEWAY_TRACE_FRAME')][-1]
        mutations = [
            TEXT.replace('REGISTERED\n', '', 1),
            TEXT.replace('max_requests=3', 'max_requests=4'),
            TEXT.replace('request=190A ', 'request=190AFF '),
            TEXT.replace('raw=0A07E602190A', 'raw=0A07E603190AFF'),
            TEXT.replace('48370011E0EA000000', '48370011E0EA000001'),
            TEXT.replace('590109010000', '590109000000'),
            TEXT.replace(first, first.replace('GATEWAY_TRACE_FRAME', 'FRAME')),
            TEXT.replace(first+'\n', ''),
            TEXT.replace(last+'\n', ''),
            TEXT.replace(last, last+'AA'),
            TEXT.replace(last, last.replace('0A07EE1E0202', '0A07EE1E0302')),
            TEXT.replace(first, first.replace('0A07EE', '0A07EF', 1)),
            TEXT.replace('payload_bytes=263', 'payload_bytes=262'),
            TEXT.replace('trace_bytes=260', 'trace_bytes=263'),
            TEXT.replace('state=GATEWAY_TRACE_CAPTURED', 'state=SUPPORTED_READ'),
            TEXT.replace('callbacks=8', 'callbacks=7'),
            TEXT.replace('cleanup=true', 'cleanup=false'),
            TEXT.replace('queued=0', 'queued=1'),
            TEXT.replace('UNREGISTERED\n', ''),
            TEXT+'EXTRA\n',
            TEXT.replace(last, last.replace('162878618943590', '162881564586589')),
            TEXT.replace('TRANSPORT_RESULT seq=3 result=0\n', '').replace('STEP_END seq=3', 'TRANSPORT_RESULT seq=3 result=0\nSTEP_END seq=3'),
        ]
        for i, text in enumerate(mutations):
            with self.subTest(case=i), self.assertRaises(ValueError):
                decode_capture(text, PLAN)

    def test_plan_is_fixed(self):
        for field, value in [('expectedFormatIdHex', '00'), ('requestIdHex', '7E0'), ('expectedSoftwareCode', '00'*9)]:
            plan = json.loads(json.dumps(PLAN));plan['plans']['isg-trace']['targets'][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                decode_capture(TEXT, plan)

    def test_malformed_or_incomplete_trace_frames(self):
        frames = decode_capture(TEXT, PLAN)['events'][2]['frames']
        with self.assertRaises(ValueError): join_report(frames[:1], complete=True)
        with self.assertRaises(ValueError): join_report(frames[::-1], complete=True)
        with self.assertRaises(ValueError): join_report(frames+[frames[-1]], complete=True)
        self.assertEqual(len(join_report(frames[:1], complete=False)), 233)

    def test_request_and_callback_cannot_shift_past_overall_bound(self):
        lines = TEXT.splitlines()
        shifted = []
        for line in lines:
            if 'seq=3 ' in line and 't_ns=' in line:
                parts = line.split()
                parts = ['t_ns='+str(int(p[5:])+13_000_000_000) if p.startswith('t_ns=') else p for p in parts]
                line = ' '.join(parts)
            shifted.append(line)
        with self.assertRaises(ValueError): decode_capture('\n'.join(shifted)+'\n', PLAN)

    def test_native_records_and_sequence_integrity(self):
        payload = bytes.fromhex(decode_capture(TEXT, PLAN)['report']['payloadHex'])[3:]
        with self.assertRaises(ValueError): decode_can_trace(payload[:-1], 9)
        for offset, value in [(18*13+5, 0x21), (2*13+5, 0x20), (2*13+9, 8), (3*13+6, 2),
                              (2, 1), (3*13+2, 1), (4, 0xE5), (13+5, 0x31), (7, 2), (3*13+4, 0xED),
                              (2*13+6, 0x7D)]:
            damaged = bytearray(payload);damaged[offset] = value
            with self.subTest(offset=offset), self.assertRaises(ValueError): decode_can_trace(damaged, 9)


if __name__ == '__main__':
    unittest.main()
