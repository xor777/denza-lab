"""Internal mutable state is compared, never supplied as environment input."""
import copy
import unittest

from . import phase2_bench as pb, phase2_capture as p2
from .test_phase2_capture import fixture, ref


def state_fixture():
    method = 'com.byd.map.signal.someip.pilot.bean.FontRoundBean.getFont3kmIdx()'
    receiver = ref('bean', 'com.byd.map.signal.someip.pilot.bean.FontRoundBean')
    result = dict(type='list', id='indices', **{'class': '[I'}, items=[dict(type='i32', value=2)])
    rows = [dict(kind='call_enter', seq=0, call_id='state', parent_call_id=None,
                 role='internal_processing', method=method, receiver=receiver, args=[]),
            dict(kind='call_exit', seq=1, call_id='state', method=method, args_after=[], result=result, error=None)]
    def binding(path, original, port):
        return dict(argument_path=path, original_id=original['id'], original_class=original['class'],
                    port_id=port, port_class='computed.' + original['class'])
    receipts = [dict(kind='consumption_receipt', source_seq=0, method=method, operation='state entry',
                     args_verified=True, receiver_verified=True, state_bindings=[binding('receiver', receiver, 'b1')]),
                dict(kind='consumption_receipt', source_seq=1, method=method, operation='state exit',
                     args_after_verified=True, result_verified=True, receiver_verified=True,
                     state_bindings=[binding('receiver', receiver, 'b1'), binding('result', result, 'a1')])]
    return rows, receipts


class StateAssertionTests(unittest.TestCase):
    def test_all_computed_state_assertions_and_identities_are_mandatory(self):
        rows, receipts = state_fixture()
        self.assertEqual(pb.validate_diagnostics(receipts, rows), [])
        for index, flag in ((0, 'args_verified'), (0, 'receiver_verified'), (1, 'args_after_verified'),
                            (1, 'result_verified'), (1, 'receiver_verified')):
            changed = copy.deepcopy(receipts)
            changed[index][flag] = False
            self.assertTrue(pb.validate_diagnostics(changed, rows), flag)
        for index in (0, 1):
            self.assertTrue(pb.validate_diagnostics(receipts[:index] + receipts[index+1:], rows))
        changed = copy.deepcopy(receipts)
        changed[0]['operation'] = 'processing entry'
        self.assertTrue(pb.validate_diagnostics(changed, rows))

    def test_missing_changed_aliased_and_remapped_identity_fail(self):
        rows, receipts = state_fixture()
        for mutation in ('missing', 'wrong_path', 'wrong_original', 'remapped', 'aliased', 'duplicate'):
            changed = copy.deepcopy(receipts)
            bindings = changed[1]['state_bindings']
            if mutation == 'missing': bindings.pop()
            elif mutation == 'wrong_path': bindings[-1]['argument_path'] = 'args[0]'
            elif mutation == 'wrong_original': bindings[-1]['original_id'] = 'invented'
            elif mutation == 'remapped': bindings[0]['port_id'] = 'another-bean'
            elif mutation == 'aliased':
                bindings[-1]['port_id'], bindings[-1]['port_class'] = bindings[0]['port_id'], bindings[0]['port_class']
            else: bindings.append(copy.deepcopy(bindings[-1]))
            self.assertTrue(pb.validate_diagnostics(changed, rows), mutation)

    def test_setter_array_aliases_must_remain_identical_through_exit(self):
        rows, receipts = state_fixture()
        method = 'com.byd.map.signal.someip.pilot.bean.FontRoundBean.setParam(int,int,int,int,double[],int,double[],int[])'
        scalar = dict(type='i32', value=-1)
        array = dict(type='list', id='coordinates', **{'class': '[D'}, items=[])
        indices = dict(type='list', id='indices', **{'class': '[I'}, items=[])
        args = [scalar, scalar, scalar, scalar, array, scalar, array, indices]
        rows[0].update(method=method, args=copy.deepcopy(args))
        rows[1].update(method=method, args_after=copy.deepcopy(args), result={'type': 'null'})
        for index, receipt in enumerate(receipts):
            receipt['method'] = method
            receipt['state_bindings'] = receipt['state_bindings'][:1]
            key = 'args' if index == 0 else 'args_after'
            for position in (4, 6, 7):
                value = args[position]
                receipt['state_bindings'].append(dict(argument_path=f'{key}[{position}]',
                    original_id=value['id'], original_class=value['class'],
                    port_id='computed-'+value['id'], port_class=value['class']))
        self.assertEqual(pb.validate_diagnostics(receipts, rows), [])
        receipts[0]['state_bindings'][2]['port_id'] = 'second-allocation-for-same-original'
        self.assertTrue(pb.validate_diagnostics(receipts, rows))

    def test_state_calls_cannot_be_promoted_to_external_inputs(self):
        rows = fixture()
        call = next(row for row in rows if row['kind'] == 'call_enter')
        call['method'] = 'com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurSegIdx()'
        self.assertTrue(any('source-owned state' in str(gap) for gap in p2.audit_ledger(rows, ['original.P'])['gaps']))
