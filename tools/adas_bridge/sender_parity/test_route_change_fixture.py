"""Execute the pinned fixture against SDK/input mocks, never packet/state oracles."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest


@unittest.skipUnless(shutil.which('node'), 'Node required for fixture execution')
class RouteChangeFixtureTest(unittest.TestCase):
    def run_case(self, mode='complete'):
        source=Path(__file__).with_name('phase2_route_change.js').read_text()
        harness=r'''
const vm=require('vm'),fs=require('fs'),input=JSON.parse(fs.readFileSync(0,'utf8'));
let now=0,seq=0,call=0,uid=0,collecting=false,completed=false;const tasks=[],reports=[],deliveries=[];
const identities=new Map();const object=(klass,pointer)=>{const out={_ref:{type:'object',id:String(++uid),class:klass,native_pointer:pointer||null}};identities.set(out,out._ref.id);return out;};
const drive=object('k.h.e0.c.g'),mmt=object('k.h.a0.c.c.a.e0');
function path(id,length,pointer){return Object.assign(object('com.autonavi.gbl.common.path.option.PathInfo',pointer),{getPathID(){return id;},getLength(){return length;},isValid(){return true;}});}
function route(id,p){return Object.assign(object('com.autonavi.gbl.route.model.RouteCarResultData'),{getRequestId(){return id;},getCurrentFocusPath(){return p;}});}
function navi(p){return Object.assign(object('com.autonavi.gbl.guide.model.NaviInfo'),{pathID:{value:p.getPathID()}});}
const p1=path('1001','9000','0x1'),p2=path('2002','8000','0x2'),r1=route('3',p1),r2=route('4',p2),n1=navi(p1),n2=navi(p2);
let currentRoute=r1,currentNavi=n1;
function later(fn,ms){const task={fn,time:now+ms,id:++uid};tasks.push(task);return task.id;}
function typed(v){if(v&&v.pathID&&v._ref)return {...v._ref,type:'struct',fields:{pathID:{type:'i64',decimal:String(v.pathID.value)}}};if(v&&v._ref)return v._ref;return {type:'i64',decimal:String(v)};}
function emit(method,args,delay=0){const id='c'+(++call),a=args.map(typed),entry={kind:'call_enter',seq:++seq,call_id:id,method:'k.h.e0.c.g.'+method+'()',receiver:drive._ref,args:a};
 collecting=true;try{context.p2ObservedCall(entry);}finally{collecting=false;}
 const exit=()=>{collecting=true;try{context.p2ObservedCall({kind:'call_exit',seq:++seq,call_id:id,method:entry.method,args_after:a,error:null});}finally{collecting=false;}};if(delay)later(exit,delay);else exit();}
drive.l=(n,p)=>{deliveries.push({method:'l',path:p.getPathID(),copy:p!==p1});emit('l',[n,p]);if(input.mode!=='no-K')later(()=>emit('K',[p.getPathID(),p]),3000);};
drive.onChangeNaviPath=(from,to)=>{deliveries.push({method:'change',from:String(from),to:String(to)});emit('onChangeNaviPath',[from,to]);later(()=>emit('C',[to]),input.mode==='slow-C'?25000:750);};
drive.g=id=>{deliveries.push({method:'ack',path:String(id)});emit('g',[id]);if(input.mode!=='no-I')later(()=>emit('I',[p2.getPathID()]),input.mode==='slow-C'?25000:750);};
drive.w=()=>{deliveries.push({method:'error'});emit('w',[]);if(input.mode!=='no-I-error')later(()=>emit('I',[p2.getPathID()]),750);};
const context={Map,Set,JSON,Number,Math,String,Object,Array,Error,Date:{now:()=>now},
 report(kind,data){if(kind==='native_callback_fixture_end'&&input.mode==='end-report-error')throw Error('fixture reporter unavailable');reports.push({kind,...data,at:now});if(kind==='native_callback_fixture_end')completed=true;},
 setTimeout:later,clearTimeout(id){const at=tasks.findIndex(t=>t.id===id);if(at>=0)tasks.splice(at,1);},
 Int64:function(v){this.toString=()=>String(v);},p2Ref:v=>v._ref,p2J:{identities:{keySet(){return {toArray(){return [...identities.keys()];}};},get(key){return identities.get(key);}}},
 Java:{cast(v){return v;},retain(v){if(collecting)throw Error('Java in collector');return v;},
 perform(fn){if(input.mode==='cold-bootstrap'&&now<2000)later(fn,2000-now);else fn();},
 scheduleOnMainThread(fn){if(collecting)throw Error('Java in collector');if(input.mode==='schedule-error')throw Error('fixture scheduling failed');if(input.mode==='cold-bootstrap'&&now<2500)throw Error('main Looper not prepared');fn();},
 use(name){if(collecting)throw Error('Java in collector');if(name==='com.autonavi.gbl.guide.model.NaviInfo')return {};if(name==='android.os.Looper')return {getMainLooper(){return input.mode==='cold-bootstrap'&&now<2500?null:{};}};if(name==='com.autonavi.gbl.common.path.option.PathInfo')return {$new(source){return path(source.getPathID(),source.getLength(),'0x-copy');}};throw Error(name);}}};
vm.createContext(context);vm.runInContext('let p2CallbackInputCompleted = false;',context);vm.runInContext(input.source,context);
emit('G',[r1],input.mode==='slow-first-G'?6000:0);emit('l',[n1,p1]);
context.seed=()=>currentRoute;context.navi=()=>input.mode==='separate-observer'?navi(currentRoute.getCurrentFocusPath()):currentNavi;context.drive=drive;context.mmt=mmt;
context.waitMs=input.mode==='slow-C'?120000:30000;vm.runInContext('p2AttachRouteChange({seed,navi,drive,mmt,waitMs})',context);
later(()=>{currentRoute=r2;currentNavi=n2;emit('l',[n2,p2]);const next=()=>{emit('G',[r2]);emit('g',[p2.getPathID()]);};if(input.mode==='slow-first-G')later(next,2500);else next();if(input.mode==='mutated-NI')n1.pathID.value=p2.getPathID();},4000);
let turns=0;while(tasks.length&&now<=context.waitMs+1000&&!completed){tasks.sort((a,b)=>a.time-b.time||a.id-b.id);const t=tasks.shift();now=t.time;t.fn();if(++turns>10000)throw Error('unbounded fixture');}
process.stdout.write(JSON.stringify({reports,deliveries,completed,now,inputCompleted:vm.runInContext('p2CallbackInputCompleted',context)}));
'''
        result=subprocess.run(['node','-e',harness],input=json.dumps({'source':source,'mode':mode}),text=True,capture_output=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stderr)
        return json.loads(result.stdout)

    def test_actual_processing_exits_complete_in_order(self):
        r=self.run_case()
        self.assertTrue(r['completed'])
        self.assertTrue(r['inputCompleted'])
        self.assertEqual([x['kind'] for x in r['reports'][:2]],['native_callback_fixture_plan','native_callback_fixture_attached'])
        steps=[x for x in r['reports'] if x['kind']=='native_callback_fixture_step']
        plan=r['reports'][0]['steps']
        self.assertEqual([x['name'] for x in steps],[x['name'] for x in plan])
        self.assertEqual(r['deliveries'],[{'method':'l','path':'1001','copy':True},{'method':'change','from':'2002','to':'1001'},{'method':'change','from':'1001','to':'2002'},{'method':'ack','path':'1001'},{'method':'error'}])
        self.assertGreaterEqual(steps[3]['at']-steps[2]['at'],3000)
        self.assertGreaterEqual(steps[5]['at']-steps[4]['at'],750)
        copied=steps[0]['old']['sdk_copy']
        self.assertNotEqual(copied['source_ref']['native_pointer'],copied['input_ref']['native_pointer'])
        self.assertEqual(copied['source_length'],copied['input_length'])
        self.assertEqual(r['reports'][-1]['kind'],'native_callback_fixture_end')
        self.assertEqual(r['reports'][-1]['at'],steps[-1]['at'])

    def test_actual_C_serialization_over_twenty_seconds_remains_bounded(self):
        r=self.run_case('slow-C')
        self.assertTrue(r['completed'])
        steps=[x for x in r['reports'] if x['kind']=='native_callback_fixture_step']
        self.assertGreaterEqual(steps[5]['at']-steps[4]['at'],25000)
        self.assertGreaterEqual(steps[7]['at']-steps[6]['at'],25000)
        self.assertLess(r['now'],120000)

    def test_separate_sdk_observer_NI_identity_is_not_assumed(self):
        r=self.run_case('separate-observer')
        self.assertTrue(r['completed'])
        steps=[x for x in r['reports'] if x['kind']=='native_callback_fixture_step']
        self.assertEqual(steps[0]['old']['path_id'],'1001')
        self.assertEqual(steps[2]['old']['navi_ref'],steps[0]['old']['navi_ref'])
        waits=[x for x in r['reports'] if x['kind']=='native_callback_fixture_wait']
        self.assertLessEqual(len(waits),12)
        self.assertTrue(all(b['at_ms']-a['at_ms']>=5000 for a,b in zip(waits,waits[1:])))

    def test_deferred_java_bootstrap_and_initial_null_main_looper_are_retried(self):
        r=self.run_case('cold-bootstrap')
        self.assertTrue(r['completed'])
        copied=[x for x in r['reports'] if x['kind']=='native_callback_fixture_input'][0]
        self.assertEqual(copied['at'],2500)
        self.assertFalse(any(x['kind']=='native_callback_fixture_error' for x in r['reports']))

    def test_main_thread_scheduling_failure_is_reported_and_fails_closed(self):
        r=self.run_case('schedule-error')
        self.assertFalse(r['completed'])
        self.assertFalse(r['inputCompleted'])
        self.assertEqual(r['deliveries'],[])
        errors=[x for x in r['reports'] if x['kind']=='native_callback_fixture_error']
        self.assertEqual(len(errors),1)
        self.assertIn('fixture scheduling failed',errors[0]['error'])

    def test_first_route_is_retained_before_slow_G_completes_and_seed_advances(self):
        r=self.run_case('slow-first-G')
        self.assertTrue(r['completed'])
        copied=[x for x in r['reports'] if x['kind']=='native_callback_fixture_input'][0]
        steps=[x for x in r['reports'] if x['kind']=='native_callback_fixture_step']
        self.assertLess(copied['at'],4000)
        self.assertGreaterEqual(steps[0]['at'],6000)
        self.assertEqual(steps[0]['old']['path_id'],'1001')
        self.assertEqual(steps[1]['current']['path_id'],'2002')

    def test_end_report_failure_does_not_complete_input_delivery(self):
        r=self.run_case('end-report-error')
        self.assertFalse(r['completed'])
        self.assertFalse(r['inputCompleted'])
        self.assertFalse(any(x['kind']=='native_callback_fixture_end' for x in r['reports']))

    def test_missing_actual_K_never_fabricates_change_or_end(self):
        r=self.run_case('no-K')
        self.assertFalse(r['completed'])
        self.assertFalse(r['inputCompleted'])
        self.assertEqual([x['method'] for x in r['deliveries']],['l'])
        errors=[x for x in r['reports'] if x['kind']=='native_callback_fixture_error']
        self.assertEqual(len(errors),1)
        self.assertEqual(errors[0]['stage'],'observe-original-K')
        self.assertIn('timed out',errors[0]['error'])

    def test_missing_positive_resend_never_fabricates_error_or_end(self):
        for mode,stage in [('no-I','observe-original-I-after-stale-ack'),('no-I-error','observe-original-I-after-error')]:
            with self.subTest(mode=mode):
                r=self.run_case(mode)
                self.assertFalse(r['completed'])
                self.assertFalse(r['inputCompleted'])
                errors=[x for x in r['reports'] if x['kind']=='native_callback_fixture_error']
                self.assertEqual(len(errors),1)
                self.assertEqual(errors[0]['stage'],stage)
                if mode=='no-I':self.assertNotIn({'method':'error'},r['deliveries'])

    def test_mutated_retained_NI_is_rejected_without_input_repair(self):
        r=self.run_case('mutated-NI')
        self.assertFalse(r['completed'])
        self.assertFalse(r['inputCompleted'])
        self.assertEqual(r['deliveries'],[])
        self.assertIn('no longer valid',[x for x in r['reports'] if x['kind']=='native_callback_fixture_error'][0]['error'])


if __name__=='__main__':
    unittest.main()
