"""An isolated runtime replaces only the original app runner command."""
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import subprocess
import unittest
from unittest.mock import patch

from . import phase2_run as run


class RuntimeRunnerTest(unittest.TestCase):
    def fixture(self, root):
        python, launcher = root / 'python', root / 'launch.py'
        python.write_text('isolated client')
        launcher.write_text('isolated launcher')
        config = root / 'runtime.json'
        config.write_text(json.dumps(dict(python=str(python), launcher=str(launcher),
            artifacts=[dict(role='launcher', path=str(launcher), sha256=run.digest(launcher))])))
        calls = []
        process = SimpleNamespace(run=lambda *a, **kw: calls.append((a, kw)))
        module = SimpleNamespace(ROOT=root / 'private', NATIVE=root / 'native', subprocess=process)
        return config, module, calls

    def test_only_exact_runner_changes_and_loader_is_restored_on_error(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            config, module, calls = self.fixture(root)
            loader = lambda: module
            with patch.object(run.stock_trace, 'map_build_emulator', loader):
                with self.assertRaisesRegex(RuntimeError, 'original failure'):
                    with run.runtime_runner(config) as artifacts:
                        loaded = run.stock_trace.map_build_emulator()
                        original = [str(module.ROOT / '.venv-firmware/bin/python'),
                                    str(module.NATIVE / 'online_cache_emulator.py'), '--out', 'evidence']
                        loaded.subprocess.run(original, timeout=12)
                        loaded.subprocess.run(['adb', 'shell', 'getprop'])
                        self.assertEqual(len(artifacts), 2)
                        raise RuntimeError('original failure')
                self.assertIs(run.stock_trace.map_build_emulator, loader)
            expected = [str(root / 'python'), str(root / 'launch.py'), '--runtime-config',
                        str(config), '--runner', original[1], '--', '--out', 'evidence']
            self.assertEqual(calls, [((expected,), dict(timeout=12)),
                                     ((['adb', 'shell', 'getprop'],), {})])

    def test_changed_launcher_rejected_before_runner_loading(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            config, _, _ = self.fixture(root)
            (root / 'launch.py').write_text('changed')
            with patch.object(run.stock_trace, 'map_build_emulator') as loader:
                with self.assertRaisesRegex(ValueError, 'artifact differs'):
                    with run.runtime_runner(config):
                        self.fail('changed runtime was accepted')
                loader.assert_not_called()

    def test_generated_city_profile_pins_contract_and_providers_before_any_device_call(self):
        class Prepared(Exception):
            pass
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = root / 'case.json'
            sample = dict(sdk=[116.3, 39.9], heading_degrees_true=90, speed_mps=10)
            case.write_text(json.dumps(dict(route_start_sdk=sample['sdk'], route_end_sdk=[116.4, 39.9],
                                           samples=[sample] * 30)))
            args = SimpleNamespace(serial='emulator-5998', case=case, seconds=None, out=root/'prepared',
                                   callback_case='city-odd', build=root/'build', frida_port=29998, no_arrive=False)
            def prepared(build, observer, view, out, **options):
                self.assertIn('cityPolicy:P2_CALLBACK_CASE.city_policy,cityExcludeIds:P2_CALLBACK_CASE.city_exclude_ids', observer)
                contract = json.loads((args.out/'callback-contract.json').read_text())
                self.assertEqual(contract['city_policy'], 'actual-current-urid-synthetic-zero-clear-v1')
                self.assertEqual(contract['city_exclude_ids'], [0])
                self.assertNotIn('setup_wait_ms',contract)
                script = args.out/'generated.js'
                script.write_text(observer)
                result = subprocess.run(['node', '--check', str(script)], capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                raise Prepared()
            with patch.object(run.stock_trace, 'install_run', side_effect=prepared) as installer:
                with self.assertRaises(Prepared):
                    run._run_locked(args)
                installer.assert_called_once()
            args.out = root/'too-short'
            data = json.loads(case.read_text())
            data['samples'].pop()
            case.write_text(json.dumps(data))
            with patch.object(run.stock_trace, 'install_run') as installer:
                with self.assertRaisesRegex(ValueError, 'at least 30'):
                    run._run_locked(args)
                installer.assert_not_called()

    def test_generated_route_change_helper_pins_native_contract_and_minimum(self):
        from . import route_change_audit as gate
        class Prepared(Exception):
            pass
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); case = root/'case.json'
            sample = dict(sdk=[116.3,39.9],heading_degrees_true=90,speed_mps=10)
            case.write_text(json.dumps(dict(route_start_sdk=sample['sdk'],route_end_sdk=[116.4,39.9],samples=[sample]*160)))
            args = SimpleNamespace(serial='emulator-5998',case=case,seconds=None,out=root/'prepared',
                callback_case='route-change',build=root/'build',frida_port=29998,no_arrive=False)
            def prepared(build, observer, view, out, **options):
                contract = json.loads((args.out/'callback-contract.json').read_text())
                self.assertEqual(contract['route_change_policy'],gate.POLICY)
                self.assertEqual(contract['guidance_stop_policy'],gate.STOP_POLICY)
                self.assertEqual(contract['guidance_sample_count'],160)
                self.assertNotIn("setTimeout(()=>abStop('timer'),REROUTE_CASE.guideMs||125000);",observer)
                self.assertIn('const P2_ROUTE_STOP_DEADLINE_MS=Date.now()+535000;',observer)
                self.assertIsNone(contract['location'])
                self.assertEqual(gate.plan((args.out/'callback-fixture.js').read_text())[0]['name'],gate.NAMES[0])
                self.assertIn('p2AttachRouteChange({seed:()=>rtSeed,navi:()=>p2LastNavi,waitMs:360000})',observer)
                self.assertEqual(options['seconds'],540)
                script=args.out/'generated.js';script.write_text(observer)
                checked=subprocess.run(['node','--check',str(script)],capture_output=True,text=True,timeout=10)
                self.assertEqual(checked.returncode,0,checked.stderr)
                raise Prepared()
            with patch.object(run.stock_trace,'install_run',side_effect=prepared) as installer:
                with self.assertRaises(Prepared): run._run_locked(args)
                installer.assert_called_once()
            data=json.loads(case.read_text());data['samples'].pop();case.write_text(json.dumps(data));args.out=root/'too-short'
            with patch.object(run.stock_trace,'install_run') as installer:
                with self.assertRaisesRegex(ValueError,'at least 160'): run._run_locked(args)
                installer.assert_not_called()

    def test_generated_lane_profile_declares_processing_stop_gate_and_bounded_default(self):
        class Prepared(Exception): pass
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);case=root/'case.json'
            sample=dict(sdk=[116.3,39.9],heading_degrees_true=90,speed_mps=10)
            case.write_text(json.dumps(dict(route_start_sdk=sample['sdk'],route_end_sdk=[116.4,39.9],samples=[sample]*45)))
            args=SimpleNamespace(serial='emulator-5998',case=case,seconds=None,out=root/'prepared',
                callback_case='lane-query',build=root/'build',frida_port=29998,no_arrive=True)
            def prepared(build,observer,view,out,**options):
                contract=json.loads((args.out/'callback-contract.json').read_text())
                self.assertEqual(contract['guidance_stop_policy'],'after_lane_processing_and_gnss_inputs_v1')
                self.assertEqual(contract['guidance_sample_count'],45)
                self.assertEqual(options['seconds'],240)
                self.assertIn('const P2_LANE_STOP_DEADLINE_MS=Date.now()+235000;',observer)
                self.assertIn('p2LaneGuidanceStopPoll',observer)
                self.assertNotIn("setTimeout(()=>abStop('timer'),REROUTE_CASE.guideMs||125000);",observer)
                path=args.out/'generated.js';path.write_text(observer)
                checked=subprocess.run(['node','--check',str(path)],capture_output=True,text=True,timeout=10)
                self.assertEqual(checked.returncode,0,checked.stderr)
                raise Prepared()
            with patch.object(run.stock_trace,'install_run',side_effect=prepared):
                with self.assertRaises(Prepared):run._run_locked(args)

    def test_arrival_only_declares_longer_bootstrap_wait_without_changing_capture_default(self):
        class Prepared(Exception): pass
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);case=root/'case.json'
            sample=dict(sdk=[116.3,39.9],heading_degrees_true=90,speed_mps=10)
            case.write_text(json.dumps(dict(route_start_sdk=sample['sdk'],route_end_sdk=[116.4,39.9],samples=[sample]*74)))
            args=SimpleNamespace(serial='emulator-5998',case=case,seconds=None,out=root/'prepared',
                callback_case='arrival-idle',build=root/'build',frida_port=29998,no_arrive=False)
            def prepared(build,observer,view,out,**options):
                contract=json.loads((args.out/'callback-contract.json').read_text())
                self.assertEqual(contract['setup_wait_ms'],120000)
                self.assertEqual(options['seconds'],194)
                self.assertIn('waitMs:P2_CALLBACK_CASE.setup_wait_ms||60000',observer)
                self.assertIn("setTimeout(()=>abStop('timer'),REROUTE_CASE.guideMs||125000);",observer)
                path=args.out/'generated.js';path.write_text(observer)
                checked=subprocess.run(['node','--check',str(path)],capture_output=True,text=True,timeout=10)
                self.assertEqual(checked.returncode,0,checked.stderr)
                raise Prepared()
            with patch.object(run.stock_trace,'install_run',side_effect=prepared):
                with self.assertRaises(Prepared):run._run_locked(args)

    def test_actual_route_stop_gate_waits_and_never_stops_on_deadline_or_early_stop(self):
        script=r'''
const vm=require('vm'),fs=require('fs'),assert=require('assert'),source=JSON.parse(fs.readFileSync(0,'utf8'));
for(const scenario of ['success','deadline','early_stop','extra_returns']){
 const timers=[],reports=[],stops=[],errors=[];
 const context={now:0,Date:{now:()=>context.now},REROUTE_CASE:{samples:[{},{}]},P2_CALLBACK_CASE:{guidance_stop_policy:'after_route_processing_and_gnss_inputs_v1'},
 P2_ROUTE_STOP_DEADLINE_MS:10000,p2CallbackInputCompleted:false,p2DeliveredGnssSamples:0,abStopped:false,
 setTimeout:f=>timers.push(f),report:(kind,data)=>reports.push({kind,...data}),p2Error:(method,error)=>errors.push(String(error)),
 abStop:reason=>{assert.equal(reports[reports.length-1].kind,'native_callback_fixture_stop_gate');stops.push(reason);}};
 vm.createContext(context);vm.runInContext(source,context);
 timers.shift()();assert.equal(stops.length,0);
 context.p2DeliveredGnssSamples=2;timers.shift()();assert.equal(stops.length,0);
 context.p2CallbackInputCompleted=true;context.p2DeliveredGnssSamples=1;timers.shift()();assert.equal(stops.length,0);
 context.p2DeliveredGnssSamples=2;
 if(scenario==='deadline')context.now=10000;
 if(scenario==='early_stop')context.abStopped=true;
 if(scenario==='extra_returns')context.p2DeliveredGnssSamples=3;
 timers.shift()();vm.runInContext('p2RouteGuidanceStopPoll()',context);
 if(scenario==='success'){assert.equal(stops.length,1);assert.equal(reports.filter(r=>r.kind==='native_callback_fixture_stop_gate').length,1);}
 else{assert.equal(stops.length,0);assert.equal(reports.filter(r=>r.kind==='native_callback_fixture_stop_gate').length,0);assert.equal(reports.filter(r=>r.kind==='native_callback_fixture_error').length,1);}
 assert.equal(errors.length,0);
}
'''
        for source, lane in ((run.route_stop_gate_source(),False),(run.lane_stop_gate_source(),True)):
            selected=script.replace('Route','Lane').replace('ROUTE','LANE').replace('route_processing','lane_processing') if lane else script
            result=subprocess.run(['node','-e',selected],input=json.dumps(source),capture_output=True,text=True,timeout=10)
            self.assertEqual(result.returncode,0,result.stderr)

    def test_new_capture_declares_stronger_terminal_policy_without_running_device(self):
        from . import runtime_contract as rc
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            case = root/'case.json'
            sample = dict(sdk=[116.3, 39.9], heading_degrees_true=90, speed_mps=10)
            case.write_text(json.dumps(dict(route_start_sdk=sample['sdk'], route_end_sdk=[116.4, 39.9], samples=[sample])))
            args = SimpleNamespace(serial='emulator-5998', case=case, seconds=None, out=root/'prepared',
                                   build=root/'build', frida_port=29998, no_arrive=False)
            receipt = dict(status='observed', baseline_restored=True)
            def installed(build, observer, view, out, **options):
                self.assertEqual(options['seconds'],args.seconds or 121)
                out.mkdir(parents=True)
                (out/'receipt.json').write_text(json.dumps(receipt))
                self.assertIn("pos.setSignInfo(data);rtReport('gnss_input_return',{count:++p2DeliveredGnssSamples});",observer)
                self.assertIn("setTimeout(()=>abStop('timer'),REROUTE_CASE.guideMs||125000);",observer)
                self.assertNotIn('p2RouteGuidanceStopPoll',observer)
                self.assertIn('deliveredSamples:p2DeliveredGnssSamples,started:rtStarted',observer)
                self.assertIn('reference:value=>p2Ref(value)',observer)
                script=root/'generated-baseline.js';script.write_text(observer)
                checked=subprocess.run(['node','--check',str(script)],capture_output=True,text=True,timeout=10)
                self.assertEqual(checked.returncode,0,checked.stderr)
                return receipt
            with patch.object(run.stock_trace, 'install_run', side_effect=installed), \
                 patch.object(run, 'original_artifacts', return_value=({}, [])), \
                 patch('tools.adas_bridge.sender_parity.phase2_capture.normalize', side_effect=lambda *a, **kw: kw):
                result = run._run_locked(args)
                args.seconds = 140
                args.out = root/'explicit-duration'
                explicit = run._run_locked(args)
            self.assertEqual(result['metadata']['seconds'],121)
            self.assertEqual(explicit['metadata']['seconds'],140)
            self.assertEqual(result['metadata']['terminal_policy'], rc.TERMINAL_POLICY)
            from . import input_boundary_audit as boundary
            self.assertEqual(result['metadata']['input_boundary_policy'],boundary.POLICY)
            self.assertIn('input_boundary_fixture',{a['role'] for a in result['artifacts']})

    def test_venv_python_symlink_keeps_its_environment_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            config, module, calls = self.fixture(root)
            python = root / 'python'
            python.rename(root / 'base-python')
            python.symlink_to(root / 'base-python')
            with patch.object(run.stock_trace, 'map_build_emulator', lambda: module):
                with run.runtime_runner(config):
                    loaded = run.stock_trace.map_build_emulator()
                    loaded.subprocess.run([str(module.ROOT / '.venv-firmware/bin/python'),
                        str(module.NATIVE / 'online_cache_emulator.py')])
            self.assertEqual(calls[0][0][0][0], str(python))


if __name__ == '__main__':
    unittest.main()
