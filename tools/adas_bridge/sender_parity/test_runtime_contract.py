"""Synthetic provider/backend provenance checks; no original execution oracle."""
import copy
import hashlib
from pathlib import Path
import tempfile
import unittest
from . import runtime_contract as rc


class RuntimeContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.metadata = dict(observer_backend='jvmti', vehicle_provider='virtual')
        self.artifacts = []
        sources = []
        for role in ('jvmti_recorder', 'vehicle_fixture'):
            path = self.root/(role+'.js'); path.write_bytes((role+' IMMUTABLE SOURCE').encode()); sources.append(path.read_bytes())
            self.artifacts.append(dict(role=role, path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        self.agent = self.root/'agent.js'
        self.agent.write_bytes(b"const P2_OBSERVER_BACKEND='jvmti';\n"+b'\n'.join(sources))
        schema = 'denza-stock-sender-phase2-v1'
        observer = dict(backend='JVMTI original MethodEntry/Exit', fresh_environment=True, running=True, stopped=False,
                        callback_errors=0, callbacks_in_flight=0, unclosed_frames=[], sdk_read_boundary=rc.SDK_POLICY,
                        suppressed_sdk_descendant_reads=1, suppressed_sdk_methods=[dict(method='SDK.internal()', outer_method='SDK.public()', count=1)])
        self.raw = [dict(kind='ready', schema=schema, observer_backend='jvmti'), dict(kind='capture_end', schema=schema, observer=observer),
                    dict(kind='vehicle_noa_fixture_installed', class_name='Fixture', mechanism='registered_virtual_subclass',
                         original_sender_methods_replaced=False, platform_device_attached=False),
                    dict(kind='vehicle_noa_fixture_validation', status='PASS', same_adapter=True, report_errors=[],
                         actual={'adas_type': 1, 'tip_island_active': True}, expected={'adas_type': 1, 'tip_island_active': True}),
                    dict(kind='adas_vehicle_noa_fixture_read', phase='original_execution', receiver_class='Fixture'),
                    dict(kind='getter', schema=schema, method='k.h.e0.b.f.r()', error=None, result={'type': 'boolean', 'value': True}),
                    dict(kind='getter', schema=schema, method='k.h.e0.b.f.h()', error=None, result={'type': 'i32', 'value': 1}),
                    dict(kind='call_enter', schema=schema, method='k.h.a0.c.c.a.e0.I0(int)', args=[{'type': 'i32', 'value': 1}])]

    def check(self, raw=None):
        return rc.audit(self.raw if raw is None else raw, self.metadata, self.artifacts, self.agent)

    def test_declared_quiet_policy_rejects_due_overdue_or_unproved_terminal_time(self):
        metadata = {'terminal_policy': rc.TERMINAL_POLICY}
        ready = dict(kind='ready', schema='denza-stock-sender-phase2-v1', terminal_policy=rc.TERMINAL_POLICY)
        end = dict(kind='capture_end', schema=ready['schema'], terminal_policy=rc.TERMINAL_POLICY,
                   observer_uptime_ms='100', recorder_diagnostics=[], pending_posts=[dict(post_id='future', due_uptime_ms='101')])
        self.assertEqual(rc.terminal_policy_audit([ready, end], metadata)['gaps'], [])
        for value in ('100', '99', '-1', None, 101, '0101'):
            changed = copy.deepcopy(end)
            changed['pending_posts'][0]['due_uptime_ms'] = value
            self.assertTrue(rc.terminal_policy_audit([ready, changed], metadata)['gaps'], value)
        for value in (None, 100, '-1', '0100'):
            changed = copy.deepcopy(end); changed['observer_uptime_ms'] = value
            self.assertTrue(rc.terminal_policy_audit([ready, changed], metadata)['gaps'])
        for diagnostics in (None, [{'error': 'fallback diagnostic'}]):
            changed = copy.deepcopy(end); changed['recorder_diagnostics'] = diagnostics
            self.assertTrue(rc.terminal_policy_audit([ready, changed], metadata)['gaps'])
        self.assertTrue(rc.terminal_policy_audit([ready, end], {})['gaps'])
        changed = copy.deepcopy(end); del changed['terminal_policy']
        self.assertTrue(rc.terminal_policy_audit([ready, changed], metadata)['gaps'])
        historical = dict(kind='capture_end', schema=ready['schema'], pending_posts=[dict(due_uptime_ms='0')])
        self.assertIsNone(rc.terminal_policy_audit([historical], {}))

    def test_native_recorder_diagnostic_cannot_hide_outside_ledger_or_without_metadata(self):
        for diagnostic in ({'kind': 'sender_phase2_recorder_diagnostic', 'error': 'native capture failed'},
                           {'kind': 'fallback', 'schema': 'denza-stock-sender-phase2-diagnostic-v1', 'error': 'no seq reserved'}):
            self.assertTrue(rc.audit([diagnostic], {}, [], None)['gaps'])
            self.assertTrue(self.check([diagnostic] + self.raw)['gaps'])

    def test_helpers_are_reverified_and_legacy_scope_is_preserved(self):
        result = self.check(); self.assertEqual(result['gaps'], [])
        self.assertEqual(result['verified_artifacts'], self.artifacts)
        self.assertEqual(rc.audit([], {}, [], None)['gaps'], [])
        self.assertTrue(rc.audit(self.raw, {}, self.artifacts, self.agent)['gaps'])
        for artifact in self.artifacts:
            path = Path(artifact['path']); original = path.read_bytes(); original_sha = artifact['sha256']
            path.write_bytes(b'changed helper')
            self.assertTrue(self.check()['gaps'])
            artifact['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertTrue(self.check()['gaps'])  # rehashed source still differs from actual agent.
            path.write_bytes(original); artifact['sha256'] = original_sha

    def test_backend_terminal_errors_frames_or_unaccounted_exclusions_fail(self):
        for field, value in [('callback_errors', 1), ('callbacks_in_flight', 1), ('unclosed_frames', ['unclosed']),
                             ('running', False), ('fresh_environment', False), ('sdk_read_boundary', 'all callbacks hidden'),
                             ('suppressed_sdk_descendant_reads', 2)]:
            with self.subTest(field=field):
                raw = copy.deepcopy(self.raw); raw[1]['observer'][field] = value
                self.assertTrue(self.check(raw)['gaps'])

    def test_virtual_validation_must_be_actual_original_execution_and_type1(self):
        for mutation in ('validation', 'install', 'only_selftest', 'r', 'h', 'I0', 'error'):
            with self.subTest(mutation=mutation):
                raw = copy.deepcopy(self.raw)
                if mutation == 'validation': raw[3]['status'] = 'FAIL'
                elif mutation == 'install': raw[2]['original_sender_methods_replaced'] = True
                elif mutation == 'only_selftest': raw[4]['phase'] = 'validation'
                elif mutation == 'r': raw[5]['result']['value'] = False
                elif mutation == 'h': raw[6]['result']['value'] = 0
                elif mutation == 'I0': raw[7]['args'][0]['value'] = 6
                else: raw.append(dict(kind='adas_vehicle_noa_fixture_error', error='failed install'))
                self.assertTrue(self.check(raw)['gaps'])

    def test_only_proved_current_balanced_methodexit_can_close_with_one_callback(self):
        raw = copy.deepcopy(self.raw)
        context = dict(native_thread_id=123, kind='MethodExit', method='android.os.Handler.dispatchMessage(android.os.Message)',
                       phase='on_exit_observer', selected_frame_popped=True)
        observer = raw[1]['observer']
        observer.update(callbacks_in_flight=1, current_native_thread_id=123,
                        current_callback=context, active_callbacks=[copy.deepcopy(context)])
        report = self.check(raw)
        self.assertEqual(report['gaps'], [])
        self.assertEqual(report['observer_terminal_closure'], 'closing_selected_MethodExit')
        for mutation in ('entry', 'cleanup', 'not_popped', 'wrong_thread', 'other_active', 'two_callbacks', 'frames', 'errors'):
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(raw); o = changed[1]['observer']
                if mutation == 'entry': o['current_callback']['kind'] = 'MethodEntry'
                elif mutation == 'cleanup': o['current_callback']['phase'] = 'cleanup'
                elif mutation == 'not_popped': o['current_callback']['selected_frame_popped'] = False
                elif mutation == 'wrong_thread': o['current_native_thread_id'] = 999
                elif mutation == 'other_active': o['active_callbacks'].append(copy.deepcopy(context))
                elif mutation == 'two_callbacks': o['callbacks_in_flight'] = 2
                elif mutation == 'frames': o['unclosed_frames'] = ['unclosed']
                else: o['callback_errors'] = 1
                self.assertTrue(self.check(changed)['gaps'])
