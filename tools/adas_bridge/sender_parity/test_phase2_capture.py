"""Synthetic phase2 fixtures check transcript integrity, never stock equivalence."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from . import bench, phase2_capture as p2


def ref(name, klass='java.lang.Object', pointer=None):
    return dict(type='object', id=name, **{'class': klass}, native_pointer=pointer)


def null():
    return dict(type='null')


def fixture():
    handler, runnable, message = ref('handler'), ref('runnable'), ref('message')
    timer = dict(post_id='post1', handler=handler, runnable=runnable, message=message, queue_id='MMT')
    rows = [
        dict(kind='hook', method='original.P', status='installed', required=True),
        dict(kind='ready', required=['original.P']),
        # Startup/idle output before the first external input remains reference.
        dict(kind='output', operation='start_service', topic='43', payload_b64=None),
        dict(kind='output', operation='fire', topic='7', payload_b64='CAHI'),
        dict(kind='call_enter', role='external_input', method='original.onLocInfoUpdate(LocInfo)',
             receiver=ref('sender'), args=[dict(type='struct', **{'class': 'LocInfo'}, id='loc1',
                                              fields={'speed': dict(type='f64', bits='8000000000000000', value=-0.0)})], call_id='c1'),
        dict(kind='call_enter', role='internal_processing', method='original.P(LocInfo)',
             receiver=ref('sender'), args=[ref('loc1', 'LocInfo')], call_id='c2', parent_call_id='c1'),
        dict(kind='getter', method='PathInfo.getLaneNum()', receiver=ref('path1', 'PathInfo', '9007199254740993'),
             args=[], result=dict(type='i64', decimal='9223372036854775807'), call_id='c2', parent_call_id='c1'),
        dict(kind='clock', method='SystemClock.elapsedRealtime()', result=dict(type='i64', decimal='1000'),
             call_id='c2', parent_call_id='c1'),
        dict(kind='timer_post_enter', **timer, token=null(), due_uptime_ms='2000', insertion_seq=0,
             call_id='c2', parent_call_id='c1'),
        dict(kind='timer_post_result', post_id='post1', result=True, call_id='c2', parent_call_id='c1'),
        dict(kind='call_exit', result=null(), error=None, call_id='c2', parent_call_id='c1'),
        dict(kind='call_exit', result=null(), error=None, call_id='c1'),
        dict(kind='timer_execute_enter', **timer, call_id='timer:post1'),
        dict(kind='getter', method='PathInfo.getLaneNum()', receiver=ref('path1', 'PathInfo', '9007199254740993'),
             args=[], result=dict(type='i32', value=3), call_id='timer:post1'),
        dict(kind='output', operation='fire', topic='33282', payload_b64='CAHI', call_id='timer:post1'),
        dict(kind='timer_execute_exit', **timer, call_id='timer:post1'),
        dict(kind='output', operation='client_send', topic='13', payload_b64=''),
        dict(kind='output', operation='stop_service', topic='43', payload_b64=None),
        dict(kind='capture_end', pending_posts=[]),
    ]
    with_spans = []
    read = 0
    calls = {}
    for row in rows:
        if row['kind'] == 'call_enter':
            calls[row['call_id']] = row
        elif row['kind'] == 'call_exit':
            row['args_after'] = copy.deepcopy(calls[row['call_id']]['args'])
        if row['kind'] in ('getter', 'clock'):
            read += 1
            row['read_id'] = 'read' + str(read)
            row['error'] = None
            entered = {key: copy.deepcopy(value) for key, value in row.items() if key not in ('result', 'error')}
            entered['kind'] += '_enter'
            with_spans.append(entered)
        with_spans.append(row)
    rows = with_spans
    for seq, row in enumerate(rows):
        row.setdefault('schema', p2.SCHEMA)
        row.setdefault('seq', seq)
        row.setdefault('mono_ns', str(1000000000 + seq * 1000000))
        row.setdefault('thread', dict(id='1', name='main'))
        row.setdefault('call_id', None)
        row.setdefault('parent_call_id', None)
    return rows


class Phase2CaptureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.raw = self.root / 'app/events.jsonl'
        self.raw.parent.mkdir()
        apk, dex, recorder = [self.root / role for role in ('original.apk', 'sender.dex', 'observer.js')]
        dex.write_bytes(b'original-dex')
        recorder.write_bytes(b'IMMUTABLE ORIGINAL OBSERVER')
        with zipfile.ZipFile(apk, 'w') as archive:
            archive.writestr('classes15.dex', dex.read_bytes())
        agent = self.raw.parent / 'agent.js'
        agent.write_bytes(b'fixture startup\n' + recorder.read_bytes())
        (self.raw.parent / 'report.json').write_text(json.dumps(dict(apk_sha256=bench.digest(apk), agent_sha256=bench.digest(agent))))
        self.source = dict(apk_sha256=bench.digest(apk), dex_sha256=bench.digest(dex), recorder_sha256=bench.digest(recorder),
                           dex_entry='classes15.dex', dex_entries={'classes15.dex': bench.digest(dex)})
        self.artifacts = [dict(role=role, path=str(path), sha256=bench.digest(path))
                          for role, path in [('apk', apk), ('dex', dex), ('recorder', recorder)]]

    def normalize(self, rows, metadata=None):
        self.raw.write_text(''.join(json.dumps(dict(type='send', payload=row)) + '\n' for row in rows))
        with patch.object(p2, 'APK_SHA256', self.source['apk_sha256']):
            return p2.normalize(self.raw, self.root / 'normalized', source=self.source, required_hooks=['original.P'],
                                artifacts=self.artifacts, receipt=dict(status='observed', baseline_restored=True), metadata=metadata)

    def test_declared_factory_scenario_requires_source_closure_beyond_structure(self):
        from . import scenario_gate
        contract = scenario_gate.factory_drive_contract()
        path = self.root / 'scenario.json'
        path.write_text(scenario_gate.canonical(contract))
        self.artifacts.append(dict(role='scenario_contract', path=str(path), sha256=bench.digest(path)))
        agent = self.raw.parent / 'agent.js'
        agent.write_text(scenario_gate.declaration(contract) + '\n' + agent.read_text())
        (self.raw.parent / 'report.json').write_text(json.dumps(dict(apk_sha256=self.source['apk_sha256'], agent_sha256=bench.digest(agent))))
        report = self.normalize(fixture(), metadata={'scenario_contract': contract})
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertEqual(report['scenario_audit']['scenario'], 'factory-drive-v1')
        self.assertTrue(any('scenario_receipt_missing' in gap for gap in report['gaps']))
        self.assertFalse(any('scenario_declaration_invalid' in gap for gap in report['gaps']))
        # Removing only metadata cannot silently demote the declared original run.
        report = self.normalize(fixture())
        self.assertTrue(any('scenario_declaration_invalid' in gap for gap in report['gaps']))

    def test_callback_fixture_error_cannot_be_ignored_as_nonledger_metadata(self):
        rows = fixture()
        rows.append(dict(kind='native_callback_fixture_error', case='rare-active', stage='setup', error='missing original guidance'))
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertTrue(report['callback_audit']['gaps'])
        self.assertEqual(report['callback_audit']['fixture_receipts'][0]['error'], 'missing original guidance')

    def test_boxed_i32_preserves_wrapper_class_and_rejects_wrong_ranges(self):
        for klass, lo, hi in [('java.lang.Byte', -128, 127), ('java.lang.Short', -32768, 32767),
                              ('java.lang.Integer', -(1 << 31), (1 << 31)-1)]:
            for number in (lo, hi):
                self.assertEqual(p2.typed_errors(dict(type='i32', value=number, boxed_class=klass)), [])
            for number in (lo-1, hi+1):
                self.assertTrue(p2.typed_errors(dict(type='i32', value=number, boxed_class=klass)))
        for klass in (None, 'java.lang.Long', 'Short'):
            self.assertTrue(p2.typed_errors(dict(type='i32', value=1, boxed_class=klass)))
        self.assertTrue(p2.typed_errors(dict(type='null', boxed_class='java.lang.Short')))
        self.assertEqual(p2.typed_errors(dict(type='i32', value=1)), [])
        rows = fixture()
        args = next(row for row in rows if row.get('method', '').startswith('original.P('))['args']
        args.append(dict(type='i32', value=12, boxed_class='java.lang.Short'))
        exit_row = next(row for row in rows if row['kind'] == 'call_exit' and row['call_id'] == 'c2')
        exit_row['args_after'].append(copy.deepcopy(args[-1]))
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED', report['gaps'])
        ledger = bench.read_jsonl(self.root/'normalized/ledger.jsonl')
        self.assertTrue(any(row.get('args', []) and row['args'][-1].get('boxed_class') == 'java.lang.Short' for row in ledger))

    def test_lossless_full_reference_and_consumed_getter_transcript(self):
        rows = fixture()
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED', report['gaps'])
        self.assertFalse(report['parityEstablished'])
        self.assertFalse(report['startup_clipping'])
        ledger = bench.read_jsonl(self.root / 'normalized/ledger.jsonl')
        getter = next(row for row in ledger if row.get('kind') == 'getter')
        self.assertEqual(getter['result']['decimal'], '9223372036854775807')
        self.assertEqual(getter['receiver']['native_pointer'], '9007199254740993')
        self.assertEqual(ledger[5]['args'][0]['fields']['speed']['bits'], '8000000000000000')
        receipts = bench.read_jsonl(self.root / 'normalized/external-inputs.jsonl')[1:]
        self.assertEqual([row['call_id'] for row in receipts], ['c1'])
        outputs = bench.read_jsonl(self.root / 'normalized/reference.jsonl')[1:]
        self.assertEqual([row['operation'] for row in outputs], ['start_service', 'fire', 'fire', 'client_send', 'stop_service'])
        self.assertLess(outputs[0]['seq'], receipts[0]['seq'])
        self.assertEqual(report['timer_states'], {'post1': 'executed'})

    def test_delivery_reordering_is_explicit_and_canonical_seq_keeps_all_rows(self):
        rows = fixture()
        rows[6], rows[7] = rows[7], rows[6]
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED', report['gaps'])
        self.assertTrue(report['frida_delivery_reordering'])
        canonical = bench.read_jsonl(self.root / 'normalized/ledger.jsonl')[1:]
        self.assertEqual([row['seq'] for row in canonical], list(range(len(rows))))
        delivery = bench.read_jsonl(self.root / 'normalized/raw-delivery.jsonl')
        self.assertEqual(delivery[6]['raw']['payload']['seq'], 7)
        self.assertEqual(canonical[6]['source_line'], 8)

    def test_sequence_loss_unknown_type_and_unknown_kind_cannot_pass(self):
        for mutate in ('loss', 'type', 'kind'):
            with self.subTest(mutate=mutate):
                rows = fixture()
                if mutate == 'loss':
                    del rows[6]
                elif mutate == 'type':
                    rows[6]['result'] = dict(type='unknown', value=3)
                else:
                    rows[6]['kind'] = 'silently_ignored_callback'
                report = self.normalize(rows)
                self.assertEqual(report['status'], 'INCOMPLETE')
                self.assertTrue(any(row['kind'] == 'coverage_gap' for row in bench.read_jsonl(self.root / 'normalized/reference.jsonl')[1:]))

    def test_getter_actual_values_are_not_derived_from_output(self):
        rows = fixture()
        before = copy.deepcopy(next(row for row in rows if row['kind'] == 'getter')['result'])
        rows[3]['payload_b64'] = 'AA=='
        next(row for row in rows if row['kind'] == 'output' and row['call_id'] == 'timer:post1')['payload_b64'] = 'AQ=='
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED')
        getter = next(row for row in bench.read_jsonl(self.root / 'normalized/ledger.jsonl') if row.get('kind') == 'getter')
        self.assertEqual(getter['result'], before)
        self.assertEqual(getter['args'], [])

    def test_call_parent_and_unclosed_processing_fail_completeness(self):
        rows = fixture()
        rows[5]['parent_call_id'] = 'unrelated'
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertTrue(any('parent' in gap.get('invalid_event', '') for gap in report['gaps']))
        rows = [row for row in fixture() if row['kind'] != 'timer_execute_exit']
        for seq, row in enumerate(rows):
            row['seq'] = seq
        report = self.normalize(rows)
        self.assertTrue(any('unclosed_timer_executions' in gap for gap in report['gaps']))

    def test_cancelled_occurrence_cannot_execute(self):
        rows = fixture()
        execute = next(i for i, row in enumerate(rows) if row['kind'] == 'timer_execute_enter')
        cancel = copy.deepcopy(rows[execute])
        cancel.update(kind='timer_cancel', call_id=None, method='Handler.removeCallbacks(Runnable)',
                      args=[copy.deepcopy(next(row for row in rows if row['kind'] == 'timer_post_enter')['runnable'])], matched_post_ids=['post1'])
        rows.insert(execute, cancel)
        for seq, row in enumerate(rows):
            row['seq'] = seq
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertTrue(any('cancelled/rejected' in gap.get('invalid_event', '') for gap in report['gaps']))

    def test_null_remove_callbacks_is_noop_even_with_pending_work(self):
        rows = fixture()
        execute = next(i for i, row in enumerate(rows) if row['kind'] == 'timer_execute_enter')
        cancel = copy.deepcopy(rows[execute])
        cancel.update(kind='timer_cancel', call_id=None, method='Handler.removeCallbacks(Runnable)',
                      args=[null()], matched_post_ids=[])
        rows.insert(execute, cancel)
        for seq, row in enumerate(rows):
            row['seq'] = seq
        self.assertEqual(self.normalize(rows)['status'], 'CAPTURED')
        cancel['matched_post_ids'] = ['post1']
        self.assertEqual(self.normalize(rows)['status'], 'INCOMPLETE')

    def test_exit_mutation_snapshots_preserve_identity_and_are_mandatory(self):
        rows = fixture()
        exit_row = next(row for row in rows if row['kind'] == 'call_exit' and row['call_id'] == 'c1')
        exit_row['args_after'][0]['fields']['speed'] = dict(type='f64', bits='3ff0000000000000', value=1.0)
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED', report['gaps'])
        self.assertEqual(len(report['input_argument_mutations']), 1)
        exit_row['args_after'][0]['id'] = 'replacement'
        self.assertEqual(self.normalize(rows)['status'], 'INCOMPLETE')
        del exit_row['args_after']
        self.assertEqual(self.normalize(rows)['status'], 'INCOMPLETE')

    def test_retained_lightbar_assertion_requires_original_input_identity(self):
        rows = fixture()
        enter = next(row for row in rows if row['kind'] == 'call_enter' and row['call_id'] == 'c1')
        enter['method'] = 'k.h.a0.c.c.a.e0.B0(java.util.List)'
        enter['args'] = [dict(type='list', id='bars', **{'class': 'java.util.ArrayList'}, items=[])]
        exit_row = next(row for row in rows if row['kind'] == 'call_exit' and row['call_id'] == 'c1')
        exit_row['args_after'] = copy.deepcopy(enter['args'])
        reset = copy.deepcopy(enter)
        reset.update(method='k.h.a0.c.c.a.e0.z()', args=[], call_id='reset')
        reset_exit = copy.deepcopy(exit_row)
        reset_exit.update(call_id='reset', args_after=[], retained_inputs_after={'lightbar': copy.deepcopy(enter['args'][0])})
        rows[-1:-1] = [reset, reset_exit]
        for seq, row in enumerate(rows):
            row['seq'] = seq
        self.assertEqual(self.normalize(rows)['status'], 'CAPTURED')
        reset_exit['retained_inputs_after']['lightbar']['id'] = 'unrelated'
        self.assertEqual(self.normalize(rows)['status'], 'INCOMPLETE')
        del reset_exit['retained_inputs_after']
        self.assertEqual(self.normalize(rows)['status'], 'INCOMPLETE')

    def test_pending_future_work_is_retained_not_silently_drained(self):
        all_rows = fixture()
        rows = all_rows[:next(i for i, row in enumerate(all_rows) if row['kind'] == 'timer_execute_enter')]
        post = next(row for row in rows if row['kind'] == 'timer_post_enter')
        envelope = {'schema', 'kind', 'seq', 'mono_ns', 'thread', 'call_id', 'parent_call_id'}
        end = copy.deepcopy(all_rows[-1])
        end['seq'] = len(rows)
        end['pending_posts'] = [{key: value for key, value in post.items() if key not in envelope}]
        rows.append(end)
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED', report['gaps'])
        self.assertEqual(report['pending_timers'][0]['post_id'], 'post1')
        self.assertEqual(report['pending_timers'][0]['due_uptime_ms'], '2000')
        self.assertEqual(report['timer_states'], {'post1': 'pending'})
        self.assertEqual(report['reference_outputs'], 2)

    def test_actual_loaded_agent_and_snapshot_are_verified(self):
        report = self.normalize(fixture())
        self.assertEqual(report['status'], 'CAPTURED')
        (self.raw.parent / 'agent.js').write_text('tampered loaded code')
        report = self.normalize(fixture())
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertTrue(any('provenance_validation_failed' in gap for gap in report['gaps']))

    def test_ready_and_all_required_hooks_are_mandatory(self):
        rows = fixture()
        rows[1]['required'].append('missing-hook')
        report = self.normalize(rows)
        self.assertIn(dict(required_hooks_missing=['missing-hook']), report['gaps'])
        rows = fixture()
        rows[1]['kind'] = 'recorder_error'
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertIn(dict(ready_event_count=0), report['gaps'])

    def test_failed_restored_run_without_raw_writes_incomplete_manifest(self):
        receipt = dict(status='runner-failed', baseline_restored=True)
        report = p2.normalize(self.raw, self.root / 'failed', source=self.source, required_hooks=['original.P'],
                              artifacts=self.artifacts, receipt=receipt)
        self.assertEqual(report['status'], 'INCOMPLETE')
        self.assertEqual(report['receipt'], receipt)
        self.assertIsNone(report['ledger_sha256'])
        self.assertEqual(report['produced_transcripts'], [])
        self.assertIn('raw_capture_unavailable', report['gaps'][0])
        saved = json.loads((self.root / 'failed/capture-manifest.json').read_text())
        self.assertEqual(saved, report)
        self.assertFalse((self.root / 'failed/ledger.jsonl').exists())

    def test_native_read_initiation_and_completion_are_required(self):
        for mutation in ('missing_enter', 'missing_completion', 'wrong_id'):
            with self.subTest(mutation=mutation):
                rows = fixture()
                index = next(i for i, row in enumerate(rows) if row['kind'] == 'getter')
                if mutation == 'missing_enter':
                    del rows[index - 1]
                elif mutation == 'missing_completion':
                    del rows[index]
                else:
                    rows[index]['read_id'] = 'different-read'
                for seq, row in enumerate(rows):
                    row['seq'] = seq
                report = self.normalize(rows)
                self.assertEqual(report['status'], 'INCOMPLETE')
                self.assertTrue(any('native read' in gap.get('invalid_event', '') or 'unclosed_native_reads' in gap
                                    for gap in report['gaps']))

    def test_native_out_parameter_mutation_retained_without_snapshot_substitution(self):
        rows = fixture()
        enter = next(row for row in rows if row['kind'] == 'getter_enter')
        finish = next(row for row in rows if row['kind'] == 'getter')
        enter['args'] = [dict(type='list', id='out-param', **{'class': 'java.util.ArrayList'}, items=[])]
        finish['args'] = [dict(type='list', id='out-param', **{'class': 'java.util.ArrayList'},
                               items=[dict(type='i32', value=42)])]
        report = self.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED', report['gaps'])
        mutation = report['native_out_argument_mutations'][0]
        self.assertEqual(mutation['before']['items'], [])
        self.assertEqual(mutation['after']['items'][0]['value'], 42)

    def test_capture_end_must_be_last_quiet_and_exact_pending_snapshot(self):
        for mutation in ('missing', 'not_last', 'forged_pending', 'active_read'):
            with self.subTest(mutation=mutation):
                rows = fixture()
                if mutation == 'missing':
                    rows.pop()
                elif mutation == 'not_last':
                    extra = copy.deepcopy(rows[-2])
                    extra['seq'] = len(rows)
                    rows.append(extra)
                elif mutation == 'forged_pending':
                    rows[-1]['pending_posts'] = [dict(post_id='invented')]
                else:
                    rows = [row for row in rows if row['kind'] != 'getter']
                    for seq, row in enumerate(rows):
                        row['seq'] = seq
                report = self.normalize(rows)
                self.assertEqual(report['status'], 'INCOMPLETE')
                self.assertTrue(any('capture_end' in gap.get('invalid_event', '') or
                                    'capture_end_count' in gap or 'event_after_capture_end' in gap
                                    for gap in report['gaps']))

    def test_exact_ieee_and_integer_wire_types(self):
        self.assertEqual(p2.typed_errors(dict(type='f64', bits='7ff8000000000042', value=None)), [])
        self.assertEqual(p2.typed_errors(dict(type='f32', bits='80000000', value=-0.0)), [])
        self.assertTrue(p2.typed_errors(dict(type='i64', decimal=9007199254740993)))
        self.assertTrue(p2.typed_errors(dict(type='f64', bits='3ff0000000000000', value=2)))
        self.assertTrue(p2.typed_errors(dict(type='i32', value=True)))


if __name__ == '__main__':
    unittest.main()
