"""Run the real lane fixture against changing native-query/processing receipts."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest


@unittest.skipUnless(shutil.which('node'), 'Node required for fixture execution')
class LaneQueryFixtureTest(unittest.TestCase):
    def run_case(self, mode='superseded'):
        source=Path(__file__).with_name('phase2_cases.js').read_text()
        script=r'''
const vm=require('vm'),fs=require('fs'),input=JSON.parse(fs.readFileSync(0,'utf8'));
let now=0,uid=0,seq=0,call=0,collecting=false;const jobs=[],notes=[],requests=[],delivered=[],ids=new Map();
function later(fn,delay){const job={id:++uid,time:now+delay,fn};jobs.push(job);return job.id;}
function ref(value){if(!ids.has(value))ids.set(value,String(++uid));return {type:'object',id:ids.get(value),class:'fixture'};}
class List{constructor(){this.items=[];}add(v){this.items.push(v);}size(){return this.items.length;}get(n){return this.items[n];}}
function dto(){const fields={};return new Proxy({}, {get(obj,name){if(name==='fields')return fields;if(!fields[name])fields[name]={value:0};return fields[name];}});}
function scalar(value){return {type:'i64',decimal:String(value)};}
function observed(kind,method,id,args,error=null){collecting=true;try{context.p2ObservedCall({kind,method:method+'()',seq:++seq,call_id:id,parent_call_id:null,thread:{id:'MMT'},args,args_after:args,error});}finally{collecting=false;}}
function processing(method,args,body,error=null){const id='c'+(++call);observed('call_enter',method,id,args);if(body)body();observed('call_exit',method,id,args,error);return id;}
function flush(id,nonempty){processing('k.h.a0.c.c.a.e0.F',[],()=>{
 const bean={type:'struct',fields:{requestId:scalar(id),segmentIndex:{type:'i32',value:1},linkIndex:{type:'i32',value:0}}};
 processing('k.h.a0.c.c.a.f0.i',[{type:'list',items:nonempty?[bean]:[]}]);});}
const mmt={d(){},l(){},onQueryAppointLanesInfo(id,response){
 delivered.push({id:String(id),ref:ref(response),at:now,variants:response.items.map(v=>v._extensionLane.value.items)});
 const delay=String(id)==='1005'?10000:500;
 later(()=>processing('k.h.a0.c.c.a.e0.Z',[{type:'list',...ref(response)},scalar(id)]),delay);
}};
const context={Map,Set,Array,Object,String,Number,Math,Error,Date:{now:()=>now},
 report(kind,data){notes.push({kind,...data,at:now});},int64:String,p2Ref:ref,
 setTimeout:later,clearTimeout(id){const at=jobs.findIndex(j=>j.id===id);if(at>=0)jobs.splice(at,1);},
 Java:{scheduleOnMainThread(fn){if(collecting)throw Error('Java in observer');fn();},
 use(name){if(collecting)throw Error('Java in observer');
 if(name==='java.util.ArrayList')return {$new:()=>new List()};
 if(name==='java.lang.Integer'||name==='java.lang.Short')return {valueOf:{overload(){return {call(owner,v){return v;}};}}};
 return {$new:()=>dto()};}}
};
vm.createContext(context);vm.runInContext('let p2CallbackInputCompleted=false;',context);vm.runInContext(input.source,context);
context.config={case:'lane-query',mmt,path:()=>({isValid:()=>true}),navi:()=>({actualSDKInput:true}),
 location:{matchedLon:116,matchedLat:40},laneRequests:()=>requests,report:context.report};
vm.runInContext('p2RunCallbackCase(config)',context);
const request=(id,source_seq)=>({read_id:'r'+id,source_seq,requestId:id,segmentIdx:1,linkIdx:0,num:6});
later(()=>{if(input.mode==='attempt-bound'){for(let i=0;i<65;i++)requests.push(request(String(2000+i),30+i));}else requests.push(request('1003',30));},200);later(()=>requests.push(request('1005',40)),4000);
if(input.mode!=='no-processing'){
 later(()=>flush('1005',input.mode==='natural-before-controlled-Z'),8000);
 later(()=>flush('1005',true),16000);
}
let turns=0;while(jobs.length&&now<122000){jobs.sort((a,b)=>a.time-b.time||a.id-b.id);const job=jobs.shift();now=job.time;job.fn();if(++turns>10000)throw Error('unbounded lane fixture');}
process.stdout.write(JSON.stringify({notes,delivered,completed:vm.runInContext('p2CallbackInputCompleted',context)}));
'''
        result=subprocess.run(['node','-e',script],input=json.dumps({'source':source,'mode':mode}),text=True,capture_output=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stderr)
        return json.loads(result.stdout)

    def test_superseded_query_receives_new_actual_response_and_nonempty_F(self):
        r=self.run_case()
        self.assertTrue(r['completed'])
        self.assertEqual([x['id'] for x in r['delivered']],['1003','1005'])
        attempts=[x for x in r['notes'] if x['kind']=='native_callback_fixture_lane_attempt']
        self.assertEqual([x['attempt_seq'] for x in attempts],[1,2])
        self.assertEqual(r['delivered'][1]['variants'],[[],[3,1]])
        step=[x for x in r['notes'] if x['kind']=='native_callback_fixture_step' and x['name']=='deliver-observed-native-lane-responses'][0]
        self.assertGreaterEqual(step['at'],16000)
        self.assertEqual(step['selected_processing']['request_ids'],['1005'])
        self.assertEqual(len(step['response_inputs']),2)
        self.assertLess(step['selected_processing']['Z']['source_seq'],step['selected_processing']['F']['source_seq'])
        self.assertEqual(r['notes'][-1]['kind'],'native_callback_fixture_end')

    def test_natural_response_before_controlled_Z_cannot_complete_fixture(self):
        r=self.run_case('natural-before-controlled-Z')
        step=[x for x in r['notes'] if x['kind']=='native_callback_fixture_step' and x['name']=='deliver-observed-native-lane-responses'][0]
        self.assertGreaterEqual(step['at'],16000)
        self.assertTrue(r['completed'])

    def test_actual_query_attempt_bound_fails_without_fixture_completion(self):
        r=self.run_case('attempt-bound')
        self.assertEqual(len(r['delivered']),64)
        self.assertFalse(r['completed'])
        errors=[x for x in r['notes'] if x['kind']=='native_callback_fixture_error']
        self.assertEqual(len(errors),1)
        self.assertIn('attempt bound exceeded',errors[0]['error'])

    def test_public_delivery_without_actual_processing_times_out(self):
        r=self.run_case('no-processing')
        self.assertEqual(len(r['delivered']),2)
        self.assertFalse(r['completed'])
        self.assertFalse(any(x['kind']=='native_callback_fixture_end' for x in r['notes']))
        errors=[x for x in r['notes'] if x['kind']=='native_callback_fixture_error']
        self.assertEqual(len(errors),1)
        self.assertIn('no actual F completion',errors[0]['error'])


if __name__=='__main__':
    unittest.main()
