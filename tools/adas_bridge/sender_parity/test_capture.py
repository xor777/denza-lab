"""Independent synthetic raw capture fixtures test capture integrity, not parity."""
import base64
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from . import capture
from .bench import digest, read_jsonl, validate_inputs

SOURCE = {k: 'a' * 64 for k in ('apk_sha256', 'dex_sha256', 'recorder_sha256')}


def fixture():
    return [
        dict(kind='sender_hook', method='route-hook', status='installed', required=True),
        dict(kind='sender_hooks_ready', required=['route-hook']),
        dict(kind='adas_boundary_installed', mono_ms=990),
        dict(kind='adas_fire', mono_ms=995, seq=0, topic='1', payload='', bytes=0),
        dict(kind='sender_start', mono_ms=1000.9, locale='EN', gates=dict(service=True, switch=True, no_sleep=True)),
        dict(kind='sender_input', schema=capture.INPUT_SCHEMA, seq=0, mono_ms=1001.7, wall_ms=12345,
             event='nav_start', data={}),
        dict(kind='sender_input', schema=capture.INPUT_SCHEMA, seq=1, mono_ms=1002.8, event='route',
             data=dict(path=dict(pathId='100002', nativeField=None))),
        dict(kind='adas_fire', seq=1, mono_ms=1003.2, topic='2', payload=base64.b64encode(b'\x08\x01').decode(), bytes=2),
        dict(kind='adas_fire', seq=2, mono_ms=4005.9, topic='3', payload='', bytes=0)]


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def normalize(self, rows):
        raw = self.root / 'events.jsonl'
        raw.write_text(''.join(json.dumps(dict(type='send', payload=r)) + '\n' for r in rows))
        agent = self.root / 'agent.js'
        if not agent.exists():
            agent.write_text('exact loaded agent')
            (self.root / 'report.json').write_text(json.dumps(dict(apk_sha256=SOURCE['apk_sha256'], agent_sha256=digest(agent))))
        return capture.normalize(raw, self.root / 'out', source=SOURCE, required_hooks=['route-hook'],
                                 receipt=dict(status='observed', baseline_restored=True))

    def test_capture_preserves_inputs_and_bytes_with_explicit_clipping(self):
        report = self.normalize(fixture())
        self.assertEqual(report['status'], 'CAPTURED')
        self.assertFalse(report['parityEstablished'])
        inputs = read_jsonl(self.root / 'out/inputs.jsonl')
        validate_inputs(inputs)
        self.assertEqual(inputs[2]['data']['path']['nativeField'], None)
        self.assertEqual(inputs[1]['mono_ms'], 1001.7)
        self.assertEqual(inputs[1]['at_ms'], 1001)
        self.assertEqual(inputs[-1]['at_ms'], 4005)
        reference = read_jsonl(self.root / 'out/reference.jsonl')
        self.assertEqual(reference[0]['input_sha256'], digest(self.root / 'out/inputs.jsonl'))
        self.assertEqual(reference[1]['payloadHex'], '0801')
        self.assertEqual(report['prestart_records'], 1)
        self.assertEqual(read_jsonl(self.root / 'out/prestart.jsonl')[0]['raw']['seq'], 0)

    def test_egress_never_creates_route_input(self):
        rows = [r for r in fixture() if r.get('event') != 'route']
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertIn({'required_callback_missing': 'route'}, report['gaps'])
        self.assertTrue(any(r['kind'] == 'coverage_gap' for r in read_jsonl(self.root / 'out/reference.jsonl')))

    def test_missing_required_hook_and_boundary_fail_closed(self):
        rows = [r for r in fixture() if r.get('kind') not in ('sender_hook', 'adas_boundary_installed')]
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertIn({'required_hooks_missing': ['route-hook']}, report['gaps'])
        self.assertTrue(any('reference_before_boundary' in gap for gap in report['gaps']))

    def test_capture_loss_and_payload_corruption_fail(self):
        rows = fixture()
        rows[6]['seq'] = 2
        rows[-1]['seq'] = 9
        rows[7]['payload'] = '**corrupt**'
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertTrue(any('invalid_callback' in gap for gap in report['gaps']))
        self.assertTrue(any('fire_sequence_loss' in gap for gap in report['gaps']))
        self.assertTrue(any('invalid_reference' in gap for gap in report['gaps']))

    def test_internal_observation_sidecar_and_unknown_callback_preserved(self):
        rows = fixture()
        rows.insert(7, dict(kind='sender_input', schema=capture.INPUT_SCHEMA, seq=2, mono_ms=1002.9,
                            event='internal_observation', data=dict(method='reset')))
        rows.insert(8, dict(kind='sender_input', schema=capture.INPUT_SCHEMA, seq=3, mono_ms=1003,
                            event='unsupported_callback', data=dict(method='new-method', rawArgs=[None, 3])))
        report = self.normalize(rows)
        inputs = read_jsonl(self.root / 'out/inputs.jsonl')
        self.assertEqual(report['internal_observation_count'], 1)
        self.assertFalse(any(r.get('event') == 'internal_observation' for r in inputs))
        self.assertEqual(next(r for r in inputs if r.get('event') == 'unsupported_callback')['data']['rawArgs'], [None, 3])
        self.assertEqual(read_jsonl(self.root / 'out/internal-observations.jsonl')[0]['seq'], 2)

    def test_scripted_ack_origin_matches_only_preceding_exact_fixture_packet(self):
        rows = fixture()
        rows[4]['fixture'] = dict(noaSwitch=True)
        fake = dict(kind='adas_fake_answer', mono_ms=1003, topic='44', payload='CAHI', statusCode=200, fake=True)
        # CAHI is arbitrary bytes here; identity annotation does not decode or
        # generate the callback payload, and route inputs remain independent.
        rows.insert(7, fake)
        rows.insert(8, dict(kind='sender_input', schema=capture.INPUT_SCHEMA, seq=2, mono_ms=1003.1,
                            event='ack', data=dict(topic='44', payloadB64='CAHI')))
        rows.insert(9, dict(kind='sender_input', schema=capture.INPUT_SCHEMA, seq=3, mono_ms=1003.1,
                            event='ack', data=dict(topic='45', payloadB64='CAHI')))
        report = self.normalize(rows)
        inputs = read_jsonl(self.root / 'out/inputs.jsonl')
        acknowledgements = [r for r in inputs if r.get('event') == 'ack']
        self.assertEqual(acknowledgements[0]['data'], dict(topic='44', payloadB64='CAHI'))
        provenance = acknowledgements[0]['provenance']
        self.assertEqual(provenance['origin'], 'scripted_emulator_answer')
        self.assertFalse(provenance['ecuEvidence'])
        self.assertEqual(provenance['fixture_packet'], fake)
        self.assertEqual(provenance['fixture_source_line'], 8)
        self.assertEqual(acknowledgements[1]['provenance']['origin'], 'original_callback_origin_unverified')
        fixtures = inputs[0]['fixture_inputs']
        self.assertEqual(fixtures['sender_start_fixture'], dict(noaSwitch=True))
        self.assertEqual(fixtures['scriptedAnswers'][0]['raw'], fake)
        self.assertEqual(report['fixture_inputs'], fixtures)
        self.assertEqual(read_jsonl(self.root / 'out/reference.jsonl')[0]['fixture_inputs'], fixtures)

    def test_independent_internal_restart_is_explicit_coverage_gap(self):
        rows = fixture()
        rows[5].update(thread='main', call_id=40, parent_call_id=None)
        rows.insert(7, dict(kind='sender_input', schema=capture.INPUT_SCHEMA, seq=2, mono_ms=1002.9,
                            event='internal_observation', method='e0.p', thread='MmtSingle',
                            call_id=100, parent_call_id=None, data=dict(method='e0.p', state=1)))
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertFalse(report['parityEstablished'])
        internal = read_jsonl(self.root / 'out/internal-observations.jsonl')[0]
        self.assertEqual(internal['thread'], 'MmtSingle')
        self.assertEqual(internal['call_id'], 100)
        self.assertIsNone(internal['parent_call_id'])
        self.assertEqual(internal['data'], dict(method='e0.p', state=1))
        self.assertIn({'unrepresented_internal_processing': internal}, report['gaps'])
        reference = read_jsonl(self.root / 'out/reference.jsonl')
        self.assertTrue(any(r.get('kind') == 'coverage_gap' and
                            r.get('data', {}).get('unrepresented_internal_processing') == internal for r in reference))
        nav_start = read_jsonl(self.root / 'out/inputs.jsonl')[1]
        self.assertEqual(nav_start['thread'], 'main')
        self.assertEqual(nav_start['call_id'], 40)
        self.assertIsNone(nav_start['parent_call_id'])

    def test_reported_extra_required_hook_is_mandatory(self):
        rows = fixture()
        rows.insert(1, dict(kind='sender_hook', method='optional-processing', status='failed', required=True))
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertIn('optional-processing', report['required_hooks'])

    def test_parse_real_recorder_hook_inventory_without_executing_js(self):
        path = self.root / 'recorder.js'
        path.write_text("const SP_REQUIRED_HOOKS = ['a', 'b'];")
        self.assertEqual(capture.required_hooks(path), ['a', 'b'])

    def test_multithread_fire_delivery_reordering_uses_original_boundary_sequence(self):
        rows = fixture()
        rows[-2], rows[-1] = rows[-1], rows[-2]
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED')
        self.assertTrue(report['frida_delivery_reordering'])
        canonical = read_jsonl(self.root / 'out/reference.jsonl')[1:]
        delivered = read_jsonl(self.root / 'out/reference-delivery.jsonl')[1:]
        self.assertEqual([r['seq'] for r in canonical], [1, 2])
        self.assertEqual([r['seq'] for r in delivered], [2, 1])
        self.assertEqual(canonical[0]['source_line'], delivered[1]['source_line'])
        self.assertEqual(canonical[0]['raw'], delivered[1]['raw'])
        # Reordering cannot hide an absent boundary invocation.
        rows[-1]['seq'] = 3
        missing = self.normalize(rows)
        self.assertTrue(any('fire_sequence_loss' in g for g in missing['gaps']))

    def test_runtime_report_binds_actual_agent_and_apk_identity(self):
        self.normalize(fixture())
        report = self.normalize(fixture())
        self.assertEqual(report['runtime_identity']['agent_sha256'], digest(self.root / 'agent.js'))
        (self.root / 'agent.js').write_text('changed agent')
        changed = self.normalize(fixture())
        self.assertEqual(changed['status'], 'INCOMPLETE')
        self.assertTrue(any('runtime_agent_identity_mismatch' in g for g in changed['gaps']))

    def test_original_dex_inventory_reuses_one_shared_artifact_and_refuses_overwrite(self):
        apk, recorder = self.root / 'original.apk', self.root / 'agent.js'
        recorder.write_text('original recorder')
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('classes.dex', b'other original code')
            archive.writestr('classes15.dex', b'original sender code')
            archive.writestr('classes25.dex', b'other original code 25')
        shared = self.root / 'shared-dex'
        with patch.object(capture, 'APK_SHA256', digest(apk)):
            source, artifacts = capture.original_artifacts(apk, recorder, self.root / 'case1', artifact_dir=shared)
            stamp = (shared / 'classes15.dex').stat().st_mtime_ns
            again, _ = capture.original_artifacts(apk, recorder, self.root / 'case2', artifact_dir=shared)
            self.assertEqual(source, again)
            self.assertEqual(len(source['dex_entries']), 3)
            self.assertEqual(list(shared.iterdir()), [shared / 'classes15.dex'])
            self.assertEqual((shared / 'classes15.dex').stat().st_mtime_ns, stamp)
            self.assertEqual([a['role'] for a in artifacts], ['apk', 'recorder', 'dex'])
            self.assertFalse((self.root / 'case1').exists())
            (shared / 'classes15.dex').write_bytes(b'wrong preserved evidence')
            with self.assertRaisesRegex(ValueError, 'refusing overwrite'):
                capture.original_artifacts(apk, recorder, self.root / 'case3', artifact_dir=shared)
            self.assertEqual((shared / 'classes15.dex').read_bytes(), b'wrong preserved evidence')

    def test_serial_guard_rejects_car_and_shared_emulator(self):
        for serial in ('127.0.0.1:5555', 'emulator-5580', 'device123', 'emulator-5620;touch /tmp/x'):
            with self.assertRaises(argparse.ArgumentTypeError):
                capture.emulator_serial(serial)
        self.assertEqual(capture.emulator_serial('emulator-5620'), 'emulator-5620')


if __name__ == '__main__':
    unittest.main()
