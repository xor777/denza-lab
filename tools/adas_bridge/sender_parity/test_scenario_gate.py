"""Source closure tests are synthetic; satisfying them is not sender parity."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from . import scenario_gate as gate


def ref(name, klass):
    return dict(type='object', id=name, **{'class': klass}, native_pointer=None)


def fixture():
    rows, stack = [], []
    def emit(kind, **data):
        row = dict(kind=kind, seq=len(rows), thread={'id': 'main', 'name': 'main'},
                   call_id=stack[-1] if stack else None, parent_call_id=stack[-2] if len(stack) > 1 else None, **data)
        rows.append(row)
        return row
    def enter(method, receiver=None, args=None):
        cid = str(len(rows))
        parent = stack[-1] if stack else None
        stack.append(cid)
        row = emit('call_enter', method=method, receiver=receiver, args=args or [])
        row['parent_call_id'] = parent
        return row
    def exit():
        emit('call_exit', error=None)
        stack.pop()
    enter(gate.D+'.o()')
    for topic in gate.SERVICES:
        emit('output', operation='start_service', topic=topic)
    exit()
    enter(gate.D+'.i()')
    emit('getter', method='k.h.e0.b.f.r()', result={'type': 'boolean', 'value': False}, error=None)
    observer = ref('observer1', gate.G)
    enter(gate.G+'.$init()', observer)
    exit(); exit()
    route = ref('route1', 'com.autosdk.bussiness.navi.route.model.RouteCarResultData')
    enter(gate.ROUTE, args=[route])
    enter(gate.G+'.t(com.autosdk.bussiness.navi.route.model.RouteCarResultData)', observer, [route])
    exit(); exit()
    enter(gate.E+'.p(int)', args=[{'type': 'i32', 'value': 100}])
    handler, runnable = ref('mmt', 'android.os.Handler'), ref('status', gate.E+'$d')
    emit('timer_cancel', method='android.os.Handler.removeCallbacks(java.lang.Runnable)', handler=handler, args=[runnable])
    emit('clock', method='android.os.SystemClock.uptimeMillis()', result={'type': 'i64', 'decimal': '1234'})
    emit('timer_post_enter', post_id='pending1', handler=handler, runnable=runnable, queue_id='MMT', due_uptime_ms='1234')
    emit('timer_post_result', post_id='pending1', result=True)
    exit()
    enter(gate.D+'.j()')
    enter(gate.G+'.onNavStop()', observer)
    exit(); exit()
    # No timer dispatch: immediate post may legitimately remain pending.
    status_start = next(i for i, r in enumerate(rows) if r.get('method') == gate.E+'.p(int)')
    status_end = next(i for i in range(status_start + 1, len(rows)) if rows[i]['kind'] == 'call_exit')
    status = rows[status_start:status_end + 1]
    del rows[status_start:status_end + 1]
    created = next(i for i, r in enumerate(rows) if r.get('method') == gate.D+'.i()')
    rows[created:created] = status
    for seq, row in enumerate(rows):
        row['seq'] = seq
    return rows


class ScenarioGateTests(unittest.TestCase):
    def setUp(self):
        self.contract = gate.factory_drive_contract()

    def test_complete_receipts_allow_pending_dispatch(self):
        report = gate.audit(fixture(), self.contract)
        self.assertEqual(report['gaps'], [])
        self.assertTrue(all(receipt['source_seqs'] for receipt in report['receipts'] if receipt['trigger_seq'] is not None))

    def test_later_idle_status_cannot_mask_missing_startup_status(self):
        rows = fixture()
        begin = next(i for i, r in enumerate(rows) if r.get('method') == gate.E+'.p(int)')
        end = next(i for i in range(begin + 1, len(rows)) if rows[i]['kind'] == 'call_exit')
        status = rows[begin:end + 1]
        del rows[begin:end + 1]
        rows += status
        for seq, row in enumerate(rows):
            row['seq'] = seq
        report = gate.audit(rows, self.contract)
        self.assertTrue(any(gap.get('scenario_receipt_missing', {}).get('obligation') == 'map-state-100' for gap in report['gaps']))

    def test_installed_hooks_and_outputs_cannot_replace_startup_descendants(self):
        rows = [r for r in fixture() if r.get('method') not in ('k.h.e0.b.f.r()', gate.G+'.$init()')]
        rows.append(dict(kind='hook', method=gate.G+'.$init', status='installed', required=True, seq=100))
        self.assertTrue(gate.audit(rows, self.contract)['gaps'])

    def test_each_missing_trigger_fails_even_without_outputs(self):
        for method in (gate.D+'.i()', gate.D+'.j()', gate.D+'.o()', gate.E+'.p(int)', gate.ROUTE):
            with self.subTest(method=method):
                rows = [r for r in fixture() if r.get('method') != method]
                self.assertTrue(gate.audit(rows, self.contract)['gaps'])

    def test_route_requires_same_original_argument_observer_and_causality(self):
        for changed in ('route', 'observer', 'parent'):
            with self.subTest(changed=changed):
                rows = fixture()
                route = next(r for r in rows if r.get('method', '').startswith(gate.G+'.t('))
                if changed == 'route':
                    route['args'] = [ref('another-route', route['args'][0]['class'])]
                elif changed == 'observer':
                    route['receiver'] = ref('another-observer', gate.G)
                else:
                    route['parent_call_id'] = None
                self.assertTrue(gate.audit(rows, self.contract)['gaps'])

    def test_start_service_source_order_and_destroy_receiver_are_required(self):
        rows = fixture()
        offers = [r for r in rows if r['kind'] == 'output']
        offers[0]['topic'], offers[1]['topic'] = offers[1]['topic'], offers[0]['topic']
        self.assertTrue(gate.audit(rows, self.contract)['gaps'])
        rows = fixture()
        next(r for r in rows if r.get('method') == gate.G+'.onNavStop()')['receiver'] = ref('other', gate.G)
        self.assertTrue(gate.audit(rows, self.contract)['gaps'])

    def test_post0_needs_actual_due_same_runnable_removal_and_success(self):
        for changed in ('due', 'runnable', 'result'):
            with self.subTest(changed=changed):
                rows = fixture()
                if changed == 'due':
                    next(r for r in rows if r['kind'] == 'timer_post_enter')['due_uptime_ms'] = '1235'
                elif changed == 'runnable':
                    next(r for r in rows if r['kind'] == 'timer_cancel')['args'] = [ref('other', gate.E+'$d')]
                else:
                    next(r for r in rows if r['kind'] == 'timer_post_result')['result'] = False
                self.assertTrue(gate.audit(rows, self.contract)['gaps'])

    def test_contract_is_fixed_and_bound_to_actual_pre_run_agent(self):
        with tempfile.TemporaryDirectory() as tmp:
            path, agent = Path(tmp)/'contract.json', Path(tmp)/'agent.js'
            path.write_text(gate.canonical(self.contract))
            artifact = dict(role='scenario_contract', path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
            agent.write_text(gate.declaration(self.contract)+'\n// actual recorder')
            self.assertEqual(gate.validate_declaration(self.contract, [artifact], agent), [])
            self.assertEqual(gate.declared_audit(fixture(), {'scenario_contract': self.contract}, [artifact], agent)['gaps'], [])
            self.assertTrue(gate.declared_audit(fixture(), {}, [artifact], agent)['gaps'])
            agent.write_text('// declaration omitted')
            self.assertTrue(gate.validate_declaration(self.contract, [artifact], agent))
            path.write_text('{}')
            self.assertTrue(gate.validate_declaration(self.contract, [artifact], agent))
        modified = copy.deepcopy(self.contract)
        modified['obligations'] = []
        self.assertTrue(gate.audit(fixture(), modified)['gaps'])
        self.assertIsNone(gate.declared_audit([], {}, [], None))
