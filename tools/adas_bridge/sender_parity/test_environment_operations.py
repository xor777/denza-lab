"""SDK public operation ordering/arguments are independent of wire outputs."""
import copy
import unittest
from . import bench, phase2_bench as pb, phase2_capture as p2
from . import test_phase2_capture as capture_fixture
from .test_phase2_capture import fixture, ref, null


def arguments():
    entries = []
    for index, service in enumerate(('3096284874276864', '3096276284211200')):
        events = dict(type='list', **{'class': '[Ljava.lang.Long;'}, id='events'+str(index),
                      items=[dict(type='i64', decimal='1125955742302210'), null(), dict(type='i64', decimal='1125955742302211')])
        entries.append(dict(type='list', **{'class': 'entry'}, items=[dict(type='i64', decimal=service), events]))
    return [dict(type='struct', **{'class': 'android.util.ArrayMap'}, id='services',
                 fields={'entries': dict(type='list', **{'class': 'entries'}, items=entries)})]


def env_rows():
    rows = fixture()
    index = next(i for i, r in enumerate(rows) if r.get('call_id') == 'c2' and r['kind'] == 'call_enter') + 1
    base = dict(schema=p2.SCHEMA, mono_ns='1000000000', thread={'id': '1', 'name': 'main'},
                call_id='c2', parent_call_id='c1', read_id='sdk1', method=p2.ENVIRONMENT_METHODS['client_start'])
    enter = dict(base, kind='getter_enter', args=arguments(), receiver=ref('sdk-client'))
    effect = dict(base, kind='environment_operation', operation='client_start', args=arguments())
    end = dict(enter, kind='getter', result=null(), error=None)
    rows[index:index] = [enter, effect, end]
    for seq, row in enumerate(rows): row['seq'] = seq
    return rows


def bindings(operation):
    return [dict(kind='environment_argument_binding', source_seq=operation['seq'], read_id=operation['read_id'],
                 argument_path=path, original_id=value['id'], original_class=value['class'],
                 port_id='computed-'+value['id'], port_class='computed.'+value['class'],
                 reason='SDK API argument identity/class after complete content verification')
            for path, value in p2.environment_argument_objects(operation).items()]


class EnvironmentOperationTests(unittest.TestCase):
    def setUp(self):
        self.capture = capture_fixture.Phase2CaptureTests('test_lossless_full_reference_and_consumed_getter_transcript')
        self.capture.setUp(); self.addCleanup(self.capture.doCleanups)

    def test_complete_sdk_effect_preserved_in_full_reference(self):
        rows = env_rows(); report = self.capture.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED', report['gaps'])
        original = next(r for r in rows if r['kind'] == 'environment_operation')
        reference = bench.read_jsonl(self.capture.root/'normalized/reference.jsonl')
        recorded = next(r for r in reference if r['kind'] == 'environment_operation')
        self.assertEqual(recorded['args'], original['args'])
        self.assertEqual(pb.effect(recorded), pb.effect(original))
        self.assertEqual(pb.validate_environment_bindings(bindings(original), rows), [])

    def test_missing_duplicate_or_unbound_sdk_operation_rejects_capture(self):
        for mutation in ('missing', 'duplicate', 'read', 'args'):
            rows = env_rows(); effect = next(r for r in rows if r['kind'] == 'environment_operation')
            if mutation == 'missing': rows.remove(effect)
            elif mutation == 'duplicate': rows.insert(rows.index(effect), copy.deepcopy(effect))
            elif mutation == 'read': effect['read_id'] = 'not-initiated'
            else: effect['args'][0]['fields']['entries']['items'].reverse()
            for seq, row in enumerate(rows): row['seq'] = seq
            self.assertEqual(self.capture.normalize(rows)['status'], 'INCOMPLETE', mutation)

    def test_actual_map_array_order_nulls_and_values_remain_exact(self):
        original = next(r for r in env_rows() if r['kind'] == 'environment_operation')
        for mutation in ('map_order', 'array_order', 'key', 'null', 'extra', 'operation'):
            port = copy.deepcopy(original); entries = port['args'][0]['fields']['entries']['items']
            if mutation == 'map_order': entries.reverse()
            elif mutation == 'array_order': entries[0]['items'][1]['items'].reverse()
            elif mutation == 'key': entries[0]['items'][0]['decimal'] = '123'
            elif mutation == 'null': entries[0]['items'][1]['items'][1] = dict(type='i64', decimal='0')
            elif mutation == 'extra': entries[0]['items'][1]['items'].append(null())
            else:
                port['operation'] = 'client_stop'; port['method'] = p2.ENVIRONMENT_METHODS['client_stop']
            self.assertNotEqual(pb.effect(original), pb.effect(port), mutation)

    def test_argument_identity_binding_cannot_mask_content_or_borrow_enumeration(self):
        rows = env_rows(); original = next(r for r in rows if r['kind'] == 'environment_operation')
        valid = bindings(original)
        self.assertTrue(pb.validate_environment_bindings(valid[:-1], rows))
        changed = copy.deepcopy(valid); changed[0]['original_id'] = 'another-map'
        self.assertTrue(pb.validate_environment_bindings(changed, rows))
        changed = copy.deepcopy(valid); changed[0]['reason'] += ' and Map enumeration'
        self.assertTrue(pb.validate_environment_bindings(changed, rows))
        later = copy.deepcopy(original); later['seq'] = 100
        changed = bindings(later); changed[0]['port_id'] = 'new-computed-map'
        self.assertTrue(pb.validate_environment_bindings(valid + changed, rows + [later]))

    def test_avp_support_is_exact_scalar_effect_with_consumed_sdk_span(self):
        rows = env_rows()
        for row in rows:
            if row.get('read_id') == 'sdk1':
                row['method'] = p2.ENVIRONMENT_METHODS['avp_support']
                row['args'] = [dict(type='i32', value=0)]
                if row['kind'] == 'environment_operation': row['operation'] = 'avp_support'
        report = self.capture.normalize(rows)
        self.assertEqual(report['status'], 'CAPTURED', report['gaps'])
        effect = next(r for r in rows if r['kind'] == 'environment_operation')
        self.assertEqual(p2.environment_argument_objects(effect), {})
        self.assertEqual(pb.validate_environment_bindings([], rows), [])
        changed = copy.deepcopy(effect)
        changed['args'][0]['value'] = 1
        self.assertNotEqual(pb.effect(effect), pb.effect(changed))
        changed['args'] = [dict(type='i64', decimal='0')]
        with self.assertRaisesRegex(ValueError, 'i32'):
            pb.effect(changed)
        rows.remove(effect)
        self.assertEqual(self.capture.normalize(rows)['status'], 'INCOMPLETE')

    def test_receiver_reset_has_explicit_zero_argument_effect(self):
        effect = dict(kind='environment_operation', operation='route_signal_reset',
                      method=p2.ENVIRONMENT_METHODS['route_signal_reset'], read_id='reset', args=[])
        self.assertEqual(p2.environment_argument_objects(effect), {})
        self.assertEqual(pb.effect(effect)['args'], [])
        effect['args'] = [null()]
        with self.assertRaises(ValueError): pb.effect(effect)

    def test_sdk_completion_requires_independent_argument_verification(self):
        method = p2.ENVIRONMENT_METHODS['client_start']
        rows = [dict(kind='getter_enter', seq=0, read_id='sdk', thread={'id': '1'}, method=method),
                dict(kind='getter', seq=1, read_id='sdk', thread={'id': '1'}, method=method)]
        receipts = [dict(kind='consumption_receipt', source_seq=0, method=method, operation='getter initiation'),
                    dict(kind='consumption_receipt', source_seq=1, method=method, operation='read completion', sdk_args_verified=True)]
        self.assertEqual(pb.validate_diagnostics(receipts, rows), [])
        receipts[1]['sdk_args_verified'] = False
        self.assertTrue(pb.validate_diagnostics(receipts, rows))
