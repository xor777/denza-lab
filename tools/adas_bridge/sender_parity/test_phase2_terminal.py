"""Host checks for terminal observation; never repair a damaged capture."""
from pathlib import Path
import shutil
import subprocess
import unittest


SOURCE = Path(__file__).with_name('phase2_inputs.js')
HARNESS = r'''
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const sent=[],reported=[],timers=[];
class I64{constructor(v){this.v=BigInt(String(v));}
compare(other){return this.v<other.v?-1:this.v>other.v?1:0;}toString(){return String(this.v);}}
const context={Int64:I64,Process:{getCurrentThreadId:()=>123},
Module:{getExportByName:()=>0},NativeFunction:function(){return()=>0;},
send:r=>sent.push(r),report:(name,row)=>reported.push({name,row}),
setTimeout:(callback,ms)=>{timers.push({callback,ms});},Java:{perform:callback=>callback()},
assert,sent,reported,timers};
vm.createContext(context);
const source=fs.readFileSync(process.argv[1],'utf8').split('function p2Ref(value)')[0];
vm.runInContext(source,context);
vm.runInContext(`p2Nano=()=>"1000000000";p2Thread=()=>({id:"main",name:"main"});
p2GuardEnter=()=>"main";p2GuardLeave=()=>{};`,context);
'''


class Phase2TerminalTest(unittest.TestCase):
    def run_js(self, body):
        subprocess.run([shutil.which('node'), '-e', HARNESS +
                        '\nvm.runInContext(' + repr(body) + ',context);', str(SOURCE)],
                       check=True, capture_output=True, text=True, timeout=10)

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_exact_uptime_conversion_without_int64_division(self):
        self.run_js(r'''
assert.equal(Int64.prototype.div,undefined);
for(const [ns,ms] of [['0','0'],['0000000000','0'],['999999','0'],
 ['1000000','1'],['1000001','1'],['1000999999','1000'],
 ['9007199254740991999','9007199254740'],['9223372036854775807','9223372036854']]){
 p2Nano=()=>ns;assert.equal(p2ObserverUptimeMs(),ms);
}
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_due_or_overdue_posts_prevent_end_but_future_posts_do_not(self):
        self.run_js(r'''
p2StopRequested=true;p2Pending.set('one',{post_id:'one',due_uptime_ms:'1000'});
p2MaybeClose();assert.equal(p2Ended,false);assert.equal(reported.length,0);
assert.equal(timers.length,1);assert.equal(timers[0].ms,50);
p2Pending.get('one').due_uptime_ms='999';p2MaybeClose();assert.equal(reported.length,0);
p2Pending.get('one').due_uptime_ms='1001';p2MaybeClose();
assert.equal(p2Ended,true);assert.equal(reported.length,1);
assert.equal(reported[0].row.terminal_policy,'quiet_no_due_or_overdue_pending_v1');
assert.equal(reported[0].row.observer_uptime_ms,'1000');
assert.equal(reported[0].row.pending_posts[0].due_uptime_ms,'1001');
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_reserved_sequence_failure_is_native_visible_and_never_repaired(self):
        self.run_js(r'''
assert.throws(()=>p2Emit('call_exit',()=>{throw Error('args_after failed');}),/args_after/);
assert.equal(reported.length,0);assert.equal(sent.length,1);
assert.equal(sent[0].source_kind,'call_exit');assert.equal(sent[0].reserved_seq,0);
assert.equal(sent[0].stage,'emit.metadata');
p2Error('source.exit',Error('args_after failed'));
assert.equal(reported[0].row.kind,'recorder_error');assert.equal(reported[0].row.seq,1);
assert.equal(reported.some(r=>r.row.seq===0),false);
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_failed_end_does_not_mark_ended_and_error_survives_java_failure(self):
        self.run_js(r'''
p2StopRequested=true;p2Thread=()=>{throw Error('Java metadata unavailable');};
p2MaybeClose();assert.equal(p2Ended,false);assert.equal(p2Closing,false);
assert.equal(p2CloseFailed,true);assert.equal(reported.length,0);
assert(sent.some(r=>r.stage==='close.capture_end'&&r.error.includes('Java metadata')));
assert(sent.some(r=>r.source_kind==='capture_end'&&r.reserved_seq===0));
assert(sent.some(r=>r.stage==='recorder_error'));
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_active_backend_frames_prevent_false_quiet_end(self):
        self.run_js(r'''
p2StopRequested=true;
const current={native_thread_id:123,kind:'MethodExit',selected_frame_popped:true};
const metadata={current_callback:current,active_callbacks:[current,{native_thread_id:999}],unclosed_frames:[]};
p2Jvmti={metadata:()=>metadata};p2MaybeClose();assert.equal(p2Ended,false);
metadata.active_callbacks=[current];current.selected_frame_popped=false;
p2MaybeClose();assert.equal(p2Ended,false);
current.selected_frame_popped=true;metadata.unclosed_frames=[{methods:['source']}];
p2MaybeClose();assert.equal(p2Ended,false);
metadata.unclosed_frames=[];p2MaybeClose();assert.equal(p2Ended,true);
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_post_report_call_collector_receives_exact_plain_row_under_guard(self):
        self.run_js(r'''
let guarded=false,observed=[];p2GuardEnter=()=>{guarded=true;return 'main';};
p2GuardLeave=()=>{guarded=false;};p2ObservedCall=row=>{assert(guarded);observed.push(row);};
p2Emit('call_enter',{method:'source.method',args:[]});p2Emit('clock_enter',{read_id:'1'});
assert.equal(observed.length,1);assert.equal(observed[0],reported[0].row);
p2Ended=true;p2Error('late.cleanup',Error('cleanup failure'));
assert.equal(reported[2].row.kind,'recorder_error');assert(sent.some(r=>r.ended));
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_diagnostic_itself_survives_missing_native_delivery_and_bad_error(self):
        self.run_js(r'''
delete globalThis.Process;delete globalThis.send;
const error={toString(){throw Error('bad toString');},get stack(){throw Error('bad stack');}};
const diagnostic=p2NativeDiagnostic('test',error);
assert.equal(diagnostic.error,'<unprintable recorder error>');
assert.equal(diagnostic.stack,null);assert.equal(diagnostic.native_thread_id,null);
assert.equal(p2RecorderDiagnostics[0],diagnostic);
''')


if __name__ == '__main__':
    unittest.main()
