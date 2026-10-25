"""Producer boundary checks actual input returns and one SDK call, never state."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from . import input_boundary_audit as gate, runtime_contract as runtime


def fixture(callback=None):
    metadata = {'input_boundary_policy': gate.POLICY}
    if callback: metadata['callback_contract'] = {'case': callback}
    declaration = dict(schema=gate.SCHEMA, policy=gate.POLICY, sample_count=2, callback_case=callback,
                       service_id=13, service_class=gate.SERVICE, observer_class=gate.OBSERVER, method=gate.METHOD)
    receipt = dict(declaration, delivered_samples=2, started=True, navigation_active=False,
        callback_fixture_complete=True, callback_completion_basis='observed-fixture-end' if callback else 'baseline-no-callback-fixture', attempt=1,
        service_ref=dict(type='object', id='service13', **{'class':gate.SERVICE}, native_pointer=None),
        observer_ref=dict(type='object', id='gao', **{'class':gate.OBSERVER}, native_pointer=None))
    raw = [dict(declaration, kind='input_boundary_installed',status='installed',source_line=1),
           dict(kind='reroute_gnss_input_return',count=1,source_line=2),
           dict(kind='reroute_gnss_input_return',count=2,source_line=3)]
    if callback: raw.append(dict(kind='native_callback_fixture_end',case=callback,status='scheduled-inputs-complete',source_line=4))
    raw += [dict(receipt,kind='input_boundary_disconnect_enter',source_line=5),
            dict(receipt,kind='input_boundary_disconnect_exit',status='call-returned',source_line=6),
            dict(kind='capture_end',schema='denza-stock-sender-phase2-v1',source_line=7)]
    loaded = b'const REROUTE_CASE='+json.dumps({'samples':[{},{}]}).encode()+b';\n'
    return raw, metadata, loaded


class InputBoundaryAuditTests(unittest.TestCase):
    def test_completed_baseline_and_callback_return_proofs(self):
        for callback in (None,'rare-active'):
            raw, metadata, loaded = fixture(callback)
            report = gate.audit(raw, metadata, loaded)
            self.assertEqual(report['gaps'],[])
            self.assertTrue(report['call_returned'])
            self.assertEqual(report['gnss_return_count'],2)
        raw, metadata, loaded = fixture()
        for row in raw:
            if row['kind'].startswith(gate.PREFIX): row['kind'] = 'adas_'+row['kind']
        self.assertEqual(gate.audit(raw,metadata,loaded)['gaps'],[])
        raw, metadata, loaded = fixture()
        raw[1]['count'], raw[2]['count'] = 2, 1
        self.assertEqual(gate.audit(raw,metadata,loaded)['gaps'],[])
        self.assertTrue(gate.audit(raw,metadata,loaded)['gnss_delivery_reordering'])

    def test_missing_returns_prerequisites_identities_or_errors_cannot_pass(self):
        for mutation in ('missing_return','duplicate_return','late_return','extra_return','count','active','pending_fixture','identity','order','error','metadata'):
            raw, metadata, loaded = fixture('rare-active')
            enter = next(r for r in raw if r['kind']=='input_boundary_disconnect_enter')
            exit = next(r for r in raw if r['kind']=='input_boundary_disconnect_exit')
            if mutation=='missing_return': del raw[1]
            elif mutation=='duplicate_return': raw[2]['count']=1
            elif mutation=='late_return': raw[2]['source_line']=8
            elif mutation=='extra_return':
                raw.append(dict(kind='reroute_gnss_input_return',count=3,source_line=4))
                enter['delivered_samples']=3;exit['delivered_samples']=3
            elif mutation=='count': raw[0]['sample_count']=1
            elif mutation=='active': enter['navigation_active']=True
            elif mutation=='pending_fixture': raw.remove(next(r for r in raw if r['kind']=='native_callback_fixture_end'))
            elif mutation=='identity': exit['observer_ref']=dict(exit['observer_ref'],id='other')
            elif mutation=='order': exit['source_line']=4
            elif mutation=='error': raw.append(dict(schema=gate.SCHEMA,policy=gate.POLICY,kind='input_boundary_error',error='SDK call failed'))
            else: metadata={}
            self.assertTrue(gate.audit(raw,metadata,loaded)['gaps'],mutation)

    def test_helper_hash_and_actual_loaded_source_are_reverified(self):
        raw, metadata, declaration = fixture()
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            helper=root/'helper.js'; helper.write_bytes(gate.POLICY.encode()+b' immutable helper')
            agent=root/'agent.js'; agent.write_bytes(declaration+helper.read_bytes())
            artifact=dict(role='input_boundary_fixture',path=str(helper),sha256=hashlib.sha256(helper.read_bytes()).hexdigest())
            self.assertEqual(runtime.audit(raw,metadata,[artifact],agent)['gaps'],[])
            self.assertTrue(runtime.audit(raw,{},[artifact],agent)['gaps'])
            self.assertTrue(runtime.audit(raw,metadata,[],agent)['gaps'])
            helper.write_bytes(b'mutated helper')
            artifact['sha256']=hashlib.sha256(helper.read_bytes()).hexdigest()
            self.assertTrue(runtime.audit(raw,metadata,[artifact],agent)['gaps'])
