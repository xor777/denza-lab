"""Synthetic comparator fixtures exercise rejection gates, never prove stock parity."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from . import bench, phase2_bench as pb, phase2_capture as p2
from .test_phase2_capture import fixture


class Phase2BenchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.raw = self.root / 'app/events.jsonl'
        self.raw.parent.mkdir()
        apk, dex, recorder = [self.root / name for name in ('original.apk', 'sender.dex', 'observer.js')]
        dex.write_bytes(b'original-dex'); recorder.write_bytes(b'IMMUTABLE RECORDER')
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('classes15.dex', dex.read_bytes())
        agent = self.raw.parent / 'agent.js'
        agent.write_bytes(b'actual startup\n' + recorder.read_bytes())
        (self.raw.parent / 'report.json').write_text(json.dumps(dict(apk_sha256=bench.digest(apk), agent_sha256=bench.digest(agent))))
        self.source = dict(apk_sha256=bench.digest(apk), dex_sha256=bench.digest(dex), recorder_sha256=bench.digest(recorder),
                           dex_entry='classes15.dex', dex_entries={'classes15.dex': bench.digest(dex)})
        self.patch = patch.object(p2, 'APK_SHA256', self.source['apk_sha256'])
        self.patch.start(); self.addCleanup(self.patch.stop)
        self.artifacts = [dict(role=role, path=str(path), sha256=bench.digest(path))
                          for role, path in [('apk', apk), ('dex', dex), ('recorder', recorder)]]
        self.rows = fixture()
        self.raw.write_text(''.join(json.dumps(dict(type='send', payload=row)) + '\n' for row in self.rows))
        self.out = self.root / 'capture'
        self.capture_report = p2.normalize(self.raw, self.out, source=self.source, required_hooks=['original.P'],
                                          artifacts=self.artifacts, receipt=dict(status='observed', baseline_restored=True))
        self.assertEqual(self.capture_report['status'], 'CAPTURED', self.capture_report['gaps'])
        self.manifest = self.out / 'capture-manifest.json'
        self.port = self.root / 'port.jsonl'
        self.header = dict(kind='header', schema=pb.PORT_SCHEMA, source=self.source,
                           ledger_sha256=bench.digest(self.out / 'ledger.jsonl'))
        self.effects = [copy.deepcopy(row) for row in self.rows if row['kind'] in pb.EFFECT_KINDS]
        post = next(row for row in self.rows if row['kind'] == 'timer_post_enter')
        reasons = {'handler': 'first handler queue binding', 'runnable': 'first originalClass-matched runnable post',
                   'message': 'environment Message allocation identity'}
        self.diagnostics = [dict(kind='consumption_receipt', source_seq=row['seq'], method=row.get('method'),
                                 operation={'getter_enter': 'getter initiation', 'clock_enter': 'clock initiation',
                                            'getter': 'read completion', 'clock': 'read completion'}[row['kind']])
                            for row in self.rows if row['kind'] in ('getter_enter', 'clock_enter', 'getter', 'clock')]
        self.diagnostics += [dict(kind='consumption_receipt', source_seq=row['seq'], method=row.get('method'),
                                  operation='external callback receipt' if row['kind'] == 'call_enter' else 'external callback exit',
                                  **({} if row['kind'] == 'call_enter' else {'args_after_verified': True, 'result_verified': True}))
                             for row in self.rows if row.get('call_id') == 'c1' and row['kind'] in ('call_enter', 'call_exit')]
        self.bindings = [dict(kind='identity_binding', identity_kind=kind, original=copy.deepcopy(post[kind]),
                              port_id='computed-' + kind, queue_id='MMT', source_seq=post['seq'], reason=reason)
                         for kind, reason in reasons.items()]

    def compare(self, effects=None, tail=None):
        rows = [self.header] + self.bindings + self.diagnostics + (self.effects if effects is None else effects)
        rows += [dict(kind='replay_complete', unconsumed=0)] if tail is None else tail
        bench.write_jsonl(self.port, rows)
        return pb.compare(self.manifest, self.port)

    def test_equal_requires_full_effects_and_complete_consumption(self):
        report = self.compare()
        self.assertEqual(report['verdict'], 'EQUAL')
        self.assertTrue(report['equivalenceEstablished'])
        self.assertEqual(report['normalization'], [])
        self.assertEqual(report['counts']['original_effects'], len(self.effects))
        self.assertEqual(report['mismatch_count'], 0)
        self.assertEqual({a['role'] for a in report['verified_artifacts']},
                         {'apk', 'dex', 'recorder', 'runtime_agent', 'runtime_report'})

    def test_declared_terminal_policy_is_pinned_and_revalidated_on_replay(self):
        from . import runtime_contract as rc
        for row in self.rows:
            if row['kind'] in ('ready', 'capture_end'): row['terminal_policy'] = rc.TERMINAL_POLICY
            if row['kind'] == 'capture_end': row.update(observer_uptime_ms='3000', recorder_diagnostics=[])
        self.raw.write_text(''.join(json.dumps(dict(type='send', payload=row))+'\n' for row in self.rows))
        report = p2.normalize(self.raw,self.out,source=self.source,required_hooks=['original.P'],
            artifacts=self.artifacts,receipt=dict(status='observed',baseline_restored=True),
            metadata={'terminal_policy':rc.TERMINAL_POLICY})
        self.assertEqual(report['status'],'CAPTURED',report['gaps'])
        self.assertEqual(bench.read_jsonl(self.out/'ledger.jsonl')[0]['terminal_policy'],rc.TERMINAL_POLICY)
        self.header['ledger_sha256'] = bench.digest(self.out/'ledger.jsonl')
        self.assertEqual(self.compare()['verdict'],'EQUAL')
        manifest=json.loads(self.manifest.read_text());del manifest['metadata']['terminal_policy']
        self.manifest.write_text(json.dumps(manifest))
        self.assertEqual(self.compare()['verdict'],'INCOMPLETE')

    def test_declared_input_boundary_is_revalidated_and_cannot_drop_helper(self):
        from . import input_boundary_audit as boundary
        from .test_input_boundary_audit import fixture as boundary_fixture
        receipts, metadata, declaration = boundary_fixture()
        helper = self.root/'input-boundary.js'
        helper.write_bytes(Path(__file__).with_name('runtime').joinpath('input-boundary.js').read_bytes())
        agent = self.raw.parent/'agent.js'
        agent.write_bytes(agent.read_bytes()+b'\n'+declaration+helper.read_bytes())
        (self.raw.parent/'report.json').write_text(json.dumps(dict(apk_sha256=self.source['apk_sha256'],agent_sha256=bench.digest(agent))))
        self.artifacts.append(dict(role='input_boundary_fixture',path=str(helper),sha256=bench.digest(helper)))
        events = self.rows[:-1]+receipts[:-1]+self.rows[-1:]
        self.raw.write_text(''.join(json.dumps(dict(type='send',payload=row))+'\n' for row in events))
        report=p2.normalize(self.raw,self.out,source=self.source,required_hooks=['original.P'],artifacts=self.artifacts,
            receipt=dict(status='observed',baseline_restored=True),metadata=metadata)
        self.assertEqual(report['status'],'CAPTURED',report['gaps'])
        self.assertEqual(bench.read_jsonl(self.out/'ledger.jsonl')[0]['input_boundary_policy'],boundary.POLICY)
        self.header['ledger_sha256']=bench.digest(self.out/'ledger.jsonl')
        self.assertEqual(self.compare()['verdict'],'EQUAL')
        manifest=json.loads(self.manifest.read_text())
        manifest['artifacts']=[a for a in manifest['artifacts'] if a['role']!='input_boundary_fixture']
        self.manifest.write_text(json.dumps(manifest))
        self.assertEqual(self.compare()['verdict'],'INCOMPLETE')

    def test_input_mutation_exit_must_be_verified_before_acceptance(self):
        receipt = next(row for row in self.diagnostics if row['operation'] == 'external callback exit')
        receipt['args_after_verified'] = False
        self.assertEqual(self.compare()['verdict'], 'INCOMPLETE')
        receipt['args_after_verified'] = True
        receipt['result_verified'] = False
        self.assertEqual(self.compare()['verdict'], 'INCOMPLETE')
        receipt['result_verified'] = True
        self.diagnostics.remove(receipt)
        self.assertEqual(self.compare()['verdict'], 'INCOMPLETE')

    def test_retained_input_assertion_cannot_be_waived_by_exit_receipt(self):
        enter = dict(kind='call_enter', seq=0, call_id='reset', method='k.h.a0.c.c.a.e0.z()',
                     role='internal_processing', parent_call_id=None)
        end = dict(kind='call_exit', seq=1, call_id='reset', method=enter['method'],
                   retained_inputs_after={'lightbar': {'type': 'list'}}, args_after=[])
        receipt = dict(kind='consumption_receipt', source_seq=1, method=end['method'],
                       operation='processing exit', args_after_verified=True)
        self.assertTrue(pb.validate_diagnostics([receipt], [enter, end]))
        receipt['retained_lightbar_verified'] = True
        self.assertEqual(pb.validate_diagnostics([receipt], [enter, end]), [])

    def test_planning_component_root_uses_single_verified_external_exit(self):
        method = 'k.h.e0.d.f.k.f(com.autosdk.bussiness.navi.route.model.RouteCarResultData)'
        enter = dict(kind='call_enter', seq=0, call_id='planning', method=method,
                     role='external_input', parent_call_id=None)
        end = dict(kind='call_exit', seq=1, call_id='planning', method=method, args_after=[])
        receipts = [dict(kind='consumption_receipt', source_seq=0, method=method, operation='external callback receipt'),
                    dict(kind='consumption_receipt', source_seq=1, method=method, operation='external callback exit', args_after_verified=True, result_verified=True)]
        self.assertEqual(pb.validate_diagnostics(receipts, [enter, end]), [])
        # The same method nested in an already owned receipt retains its barrier.
        outer = dict(kind='call_enter', seq=2, call_id='outer', method='original.callback()', role='external_input', parent_call_id=None)
        enter['parent_call_id'] = 'outer'
        outer_receipt = dict(kind='consumption_receipt', source_seq=2, method=outer['method'], operation='external callback receipt')
        self.assertTrue(pb.validate_diagnostics(receipts + [outer_receipt], [outer, enter, end]))
        receipts[1]['operation'] = 'processing exit'
        self.assertEqual(pb.validate_diagnostics(receipts + [outer_receipt], [outer, enter, end]), [])

    def test_wire_bytes_client_requests_and_lifecycle_all_compare(self):
        for mutation in ('wire', 'request', 'start', 'stop'):
            with self.subTest(mutation=mutation):
                actual = copy.deepcopy(self.effects)
                if mutation == 'wire':
                    next(r for r in actual if r.get('operation') == 'fire')['payload_b64'] = 'CAHIAA=='
                else:
                    operation = {'request': 'client_send', 'start': 'start_service', 'stop': 'stop_service'}[mutation]
                    actual.remove(next(r for r in actual if r.get('operation') == operation))
                report = self.compare(actual)
                self.assertEqual(report['verdict'], 'DIFFERENT')
                self.assertFalse(report['equivalenceEstablished'])
                self.assertGreater(report['mismatch_count'], 0)

    def test_timers_and_output_interleaving_are_not_resynchronized(self):
        actual = copy.deepcopy(self.effects)
        index = next(i for i, r in enumerate(actual) if r['kind'] == 'timer_execute_enter')
        actual[index], actual[index + 1] = actual[index + 1], actual[index]
        report = self.compare(actual)
        self.assertEqual(report['verdict'], 'DIFFERENT')
        self.assertEqual(len(report['first_divergences']), 2)
        actual = copy.deepcopy(self.effects)
        next(r for r in actual if r['kind'] == 'timer_post_enter')['due_uptime_ms'] = '2001'
        report = self.compare(actual)
        self.assertEqual(report['verdict'], 'DIFFERENT')
        self.assertIn('due_uptime_ms', report['first_divergences'][0]['differing_fields'])

    def test_missing_completion_unknown_rows_and_unconsumed_inputs_cannot_pass(self):
        for tail in ([], [dict(kind='replay_complete', unconsumed=1)],
                     [dict(kind='unmodelled_getter', value=1), dict(kind='replay_complete', unconsumed=0)]):
            with self.subTest(tail=tail):
                report = self.compare(tail=tail)
                self.assertEqual(report['verdict'], 'INCOMPLETE')
                self.assertTrue(report['driver_gaps'])
        report = self.compare(tail=[dict(kind='coverage_gap', data='unhandled callback'),
                                    dict(kind='replay_complete', unconsumed=0)])
        self.assertEqual(report['verdict'], 'INCOMPLETE')

    def test_port_header_must_bind_exact_original_ledger(self):
        self.header['ledger_sha256'] = 'f' * 64
        report = self.compare()
        self.assertEqual(report['verdict'], 'INCOMPLETE')
        self.assertIn('port_header_does_not_bind_exact_original_ledger', report['driver_gaps'][0])

    def test_declared_scenario_is_revalidated_during_replay(self):
        from . import scenario_gate
        manifest = json.loads(self.manifest.read_text())
        manifest['metadata']['scenario_contract'] = scenario_gate.factory_drive_contract()
        self.manifest.write_text(json.dumps(manifest))
        report = self.compare()
        self.assertEqual(report['verdict'], 'INCOMPLETE')
        self.assertTrue(any('scenario_declaration_invalid' in gap for gap in report['capture_gaps']))

    def test_runtime_artifact_tampering_rejected_again_at_comparison(self):
        for name in ('agent.js', 'report.json'):
            with self.subTest(name=name):
                path = self.raw.parent / name
                saved = path.read_bytes(); path.write_bytes(saved + b'tamper')
                report = self.compare()
                self.assertEqual(report['verdict'], 'INCOMPLETE')
                self.assertTrue(report['capture_gaps'])
                path.write_bytes(saved)

    def test_normalized_getter_cannot_replace_actual_raw_even_if_rehashed(self):
        path = self.out / 'ledger.jsonl'
        ledger = bench.read_jsonl(path)
        next(row for row in ledger if row.get('kind') == 'getter')['result'] = dict(type='i32', value=999)
        bench.write_jsonl(path, ledger)
        manifest = json.loads(self.manifest.read_text())
        manifest['ledger_sha256'] = bench.digest(path)
        reference_path = self.out / 'reference.jsonl'
        reference = bench.read_jsonl(reference_path)
        reference[0]['ledger_sha256'] = manifest['ledger_sha256']
        bench.write_jsonl(reference_path, reference)
        manifest['reference_sha256'] = bench.digest(reference_path)
        self.manifest.write_text(json.dumps(manifest))
        report = self.compare()
        self.assertEqual(report['verdict'], 'INCOMPLETE')
        self.assertIn('exactly preserve every original event', report['capture_gaps'][0]['capture_provenance_or_integrity_failure'])

    def test_identity_mappings_cannot_alias_or_disappear(self):
        saved = copy.deepcopy(self.bindings)
        self.bindings = []
        report = self.compare()
        self.assertEqual(report['verdict'], 'INCOMPLETE')
        self.assertTrue(any('missing_identity_binding' in gap for gap in report['driver_gaps']))
        self.bindings = saved
        conflicting = copy.deepcopy(self.bindings[0])
        conflicting['port_id'] = 'different-handler'
        self.bindings.append(conflicting)
        report = self.compare()
        self.assertEqual(report['verdict'], 'INCOMPLETE')
        self.assertTrue(any('remapped' in gap.get('invalid_identity_binding', '') for gap in report['driver_gaps']))

    def test_native_read_diagnostics_cannot_be_missing_duplicated_or_relabelled(self):
        saved = copy.deepcopy(self.diagnostics)
        for mutation in ('missing', 'duplicate', 'method', 'unproven_calendar'):
            with self.subTest(mutation=mutation):
                self.diagnostics = copy.deepcopy(saved)
                if mutation == 'missing':
                    self.diagnostics.pop()
                elif mutation == 'duplicate':
                    self.diagnostics.append(copy.deepcopy(self.diagnostics[0]))
                elif mutation == 'method':
                    self.diagnostics[0]['method'] = 'different native getter'
                else:
                    row = next(r for r in self.diagnostics if r['operation'] == 'clock initiation')
                    row.update(kind='environment_observation', reason='source-proven diagnostic Calendar/Java logger clock; value never enters sender state')
                report = self.compare()
                self.assertEqual(report['verdict'], 'INCOMPLETE')
                self.assertTrue(report['driver_gaps'])
        self.diagnostics = saved

    def test_wall_timestamp_payload_has_no_exception(self):
        import base64
        actual = copy.deepcopy(self.effects)
        row = next(row for row in actual if row.get('operation') == 'fire')
        row['payload_b64'] = base64.b64encode(b'{"time":123456789}').decode()
        report = self.compare(actual)
        self.assertEqual(report['verdict'], 'DIFFERENT')
        self.assertIn('payload_hex', report['first_divergences'][0]['differing_fields'])
        self.assertEqual(report['normalization'], [])


if __name__ == '__main__':
    unittest.main()
