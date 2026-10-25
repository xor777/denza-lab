"""Host checks for the emulator-only, declared SDK input disconnection."""
from pathlib import Path
import shutil
import subprocess
import unittest


SOURCE = Path(__file__).with_name('input-boundary.js')
HARNESS = r'''
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const reports=[],calls=[],loaded=[];let naving=true,removeError=null;
const SERVICE='com.autonavi.gbl.pos.PosService',OBSERVER='com.byd.map.signal.GaoDeSignalManager';
const service={removeLocInfoObserver(observer){calls.push(['remove',observer]);if(removeError)throw removeError;}};
const observer={originalSingleton:true};
const classes={
 'com.autosdk.bussiness.navi.NaviController':{getInstance:()=>({isNaving(){calls.push(['isNaving']);return naving;}})},
 'com.autonavi.gbl.servicemanager.ServiceMgr':{getServiceMgrInstance:()=>({getBLService(id){assert.equal(id,13);return service;}})},
 [SERVICE]:{},[OBSERVER]:{getInstance:()=>observer},'com.autonavi.gbl.pos.observer.IPosLocInfoObserver':{}
};
const context={Java:{use(name){loaded.push(name);assert(name in classes);return classes[name];},cast:value=>value}};
vm.createContext(context);vm.runInContext(fs.readFileSync(process.argv[1],'utf8'),context);
const fixture=context.DenzaInputBoundary;
const options={sampleCount:3,callbackCase:'reset-offcourse',report:(kind,row)=>reports.push({kind,row}),
 reference:value=>({type:'object',id:value===service?'service':'observer',class:value===service?SERVICE:OBSERVER,native_pointer:null})};
const ready={deliveredSamples:3,started:true,callbackFixtureComplete:true};
'''


class InputBoundaryTest(unittest.TestCase):
    def run_js(self, body):
        subprocess.run([shutil.which('node'), '-e', HARNESS + body, str(SOURCE)],
                       check=True, capture_output=True, text=True, timeout=10)

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_prerequisites_delay_all_sdk_resolution_and_actual_guidance_gates_once(self):
        self.run_js(r'''
const api=fixture.install(options);assert.equal(loaded.length,0);
for(const input of [{...ready,deliveredSamples:2},{...ready,started:false},
                   {...ready,callbackFixtureComplete:false}])assert.equal(api.poll(input),false);
assert.equal(loaded.length,0);assert.equal(api.poll(ready),false);
assert.equal(calls.filter(c=>c[0]==='remove').length,0);
naving=false;assert.equal(api.poll(ready),true);assert.equal(api.poll(ready),false);
assert.equal(calls.filter(c=>c[0]==='remove').length,1);
assert.equal(calls.find(c=>c[0]==='remove')[1],observer);
assert.deepEqual(reports.map(r=>r.kind),['input_boundary_installed',
 'input_boundary_disconnect_enter','input_boundary_disconnect_exit']);
assert.equal(api.metadata().state,'call-returned');assert.equal(api.metadata().attempted,true);
assert.equal(api.metadata().errors.length,0);assert.equal(api.metadata().report_errors.length,0);
assert.equal(reports[1].row.service_ref.id,reports[2].row.service_ref.id);
assert(Object.isFrozen(reports[1].row));assert(Object.isFrozen(reports[1].row.observer_ref));
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_baseline_explicit_declaration_allows_no_callback_fixture(self):
        self.run_js(r'''
options.callbackCase=null;const api=fixture.install(options);naving=false;
assert.equal(api.poll({...ready,deliveredSamples:4,callbackFixtureComplete:false}),true);
assert.equal(reports[2].row.callback_completion_basis,'baseline-no-callback-fixture');
assert.equal(reports[2].row.callback_fixture_complete,true);
assert.throws(()=>fixture.install(options),/already installed/);
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_exceptional_sdk_call_is_visible_and_never_retried(self):
        self.run_js(r'''
const api=fixture.install(options);naving=false;removeError=Error('original SDK failure');
assert.throws(()=>api.poll(ready),/original SDK failure/);assert.equal(api.poll(ready),false);
assert.equal(calls.filter(c=>c[0]==='remove').length,1);assert.equal(api.metadata().state,'failed');
assert.equal(api.metadata().errors.length,1);assert.equal(reports.at(-1).kind,'input_boundary_error');
assert.equal(reports.some(r=>r.kind==='input_boundary_disconnect_exit'),false);
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_report_failure_cannot_silently_disconnect(self):
        self.run_js(r'''
options.report=(kind,row)=>{reports.push({kind,row});if(kind==='input_boundary_disconnect_enter')throw Error('delivery failed');};
const api=fixture.install(options);naving=false;
assert.throws(()=>api.poll(ready),/delivery failed/);assert.equal(api.poll(ready),false);
assert.equal(calls.filter(c=>c[0]==='remove').length,0);
assert.equal(api.metadata().report_errors.length,1);assert.equal(api.metadata().state,'failed');
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_wrong_original_identity_is_rejected_before_sdk_action(self):
        self.run_js(r'''
options.reference=()=>({type:'object',id:'wrong',class:'different.observer'});
const api=fixture.install(options);naving=false;
assert.throws(()=>api.poll(ready),/Original-object reference differs/);
assert.equal(calls.filter(c=>c[0]==='remove').length,0);
assert.equal(reports.some(r=>r.kind==='input_boundary_disconnect_enter'),false);
assert.equal(api.metadata().state,'failed');
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_completion_report_failure_does_not_repeat_original_call(self):
        self.run_js(r'''
options.report=(kind,row)=>{reports.push({kind,row});if(kind==='input_boundary_disconnect_exit')throw Error('exit delivery failed');};
const api=fixture.install(options);naving=false;
assert.throws(()=>api.poll(ready),/exit delivery failed/);assert.equal(api.poll(ready),false);
assert.equal(calls.filter(c=>c[0]==='remove').length,1);
assert.equal(api.metadata().report_errors.length,1);assert.equal(api.metadata().state,'failed');
''')


if __name__ == '__main__':
    unittest.main()
