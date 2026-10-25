"""Fixture delivery is tested separately from actual sender timing/behavior."""
import copy
import base64
import json
import hashlib
from pathlib import Path
import tempfile
import subprocess
import unittest

from . import callback_audit as ca

SOURCE = Path(__file__).with_name('phase2_cases.js').read_text()


def fixture(selected='rare-active'):
    contract = dict(schema=ca.SCHEMA, case=selected, location={'rawLon': 116.0},
                    fixture_sha256=hashlib.sha256(SOURCE.encode()).hexdigest())
    steps = ca.planned_steps(SOURCE, selected)
    rows = [dict(kind=ca.PREFIX+'plan', schema='denza-native-callback-case-v1', case=selected,
                 steps=copy.deepcopy(steps), location=contract['location'], path_id='123' if selected == 'arrival-idle' else None),
            dict(kind=ca.PREFIX+'attached', case=selected, original_path_id='123')]
    rows += [dict(kind=ca.PREFIX+'step', case=selected, name=s['name'], status='delivered') for s in steps]
    rows += [dict(kind=ca.PREFIX+'end', case=selected, status='scheduled-inputs-complete'),
             dict(kind='capture_end', schema='denza-stock-sender-phase2-v1')]
    if selected == 'lane-query':
        record = dict(read_id='read81', source_seq=100, requestId='81', segmentIdx=1, linkIdx=0, num=6)
        rows[3]['observed_lane_requests'] = [record]
        rows[3]['response_input_ref'] = dict(type='object', id='fixture-lanes', **{'class': 'java.util.ArrayList'})
        integer = lambda n: dict(type='i32', value=n)
        fields = {key: integer(record[key]) for key in ('segmentIdx', 'linkIdx', 'num')}
        native = [dict(kind='timer_execute_enter', seq=90, call_id='timer:post1', queue_id='MAIN',
                       runnable={'class': 'k.h.a0.c.c.a.e0$b'}),
                  dict(kind='getter', seq=100, read_id='read81', call_id='timer:post1', thread={'id': 'main'},
                       method='com.autonavi.gbl.guide.GuideService.queryAppointLanesInfo(com.autonavi.gbl.guide.model.QueryLanesInfo)',
                       args=[{'type': 'struct', 'fields': fields}], result=integer(81), error=None),
                  dict(kind='getter', seq=101, read_id='path1', call_id='timer:post1', thread={'id': 'main'},
                       method='com.autonavi.gbl.common.path.option.PathInfo.getPathID()', result={'type': 'i64', 'decimal': '123'}, error=None)]
        lanes = []
        for extension in ([], [3, 1]):
            f = {key: integer(record[key]) for key in ('segmentIdx', 'linkIdx')}
            f.update(backExtenLane={'type': 'list', 'items': [integer(2), integer(1)]},
                     extensionLane={'type': 'list', 'items': [integer(n) for n in extension]})
            lanes.append({'type': 'struct', 'class': 'com.autonavi.gbl.guide.model.LaneInfo', 'fields': f})
        native.append(dict(kind='call_enter', seq=102, call_id='response',
                           method='k.h.a0.c.c.a.e0.onQueryAppointLanesInfo(long,java.util.ArrayList)',
                           args=[{'type': 'i64', 'decimal': '81'}, {'type': 'list', 'id': 'fixture-lanes', 'class': 'java.util.ArrayList', 'items': lanes}]))
        for row in native:
            row['schema'] = 'denza-stock-sender-phase2-v1'
        rows[3:3] = native
    if selected == 'city-odd':
        contract.update(city_policy='actual-current-urid-synthetic-zero-clear-v1', city_exclude_ids=[0])
        expanded = []
        seq = 0
        for row in rows:
            if row['kind'] == ca.PREFIX+'step':
                index = len([r for r in expanded if r['kind'] == ca.PREFIX+'step'])
                stage = ('include', 'exclude', 'clear')[index]
                ids = [110000] if index == 0 else [0] if index == 1 else []
                reference = lambda name: dict(type='object', id=name, **{'class': 'OriginalSDK'})
                data = dict(policy=contract['city_policy'], stage=stage, city_ids=ids, current_urid=110000,
                            origin=('actual_current_link_urid', 'declared_synthetic_control', 'explicit_empty_list')[index],
                            segment_idx=1, link_idx=2, **{k: reference(k) for k in ('path_ref', 'navi_ref', 'segment_ref', 'link_ref')},
                            input_ref=reference('cities'+str(index)))
                row['city_input'] = data
                native = [dict(kind='call_enter', call_id='city'+str(index), role='external_input',
                               method='k.h.e0.d.f.m.a(java.util.List)', args=[dict(type='list', id=data['input_ref']['id'],
                               items=[dict(type='i32', value=v) for v in ids])]),
                          dict(kind='call_exit', call_id='city'+str(index), error=None),
                          dict(kind='call_enter', method='k.h.e0.c.g.l(NaviInfo,PathInfo)',
                               args=[data['navi_ref'], data['path_ref']])]
                for event in native:
                    event.update(schema='denza-stock-sender-phase2-v1', seq=seq)
                    seq += 1
                expanded.extend(native)
            expanded.append(row)
        rows = expanded
    if selected in ('arrival-idle', 'lane-query'):
        receiver = dict(type='object',id='original-mmt', **{'class':'k.h.a0.c.c.a.e0'})
        def event(seq,kind,**data):
            return dict(schema='denza-stock-sender-phase2-v1',seq=seq,kind=kind,**data)
        if selected == 'arrival-idle':
            callback = event(300,'call_enter',call_id='arrival',method='k.h.a0.c.c.a.e0.onNaviArrive(long,int)',receiver=receiver,
                args=[dict(type='i64',decimal='123'),dict(type='i32',value=0)])
            actual = [callback,event(301,'timer_post_enter',post_id='arrival-post',call_id='arrival'),
                event(302,'call_exit',call_id='arrival',error=None),
                event(303,'timer_execute_enter',call_id='arrival-timer',post_id='arrival-post',queue_id='MMT',runnable={'class':'k.h.a0.c.c.a.b0'}),
                event(304,'call_enter',call_id='R',parent_call_id='arrival-timer',method='k.h.a0.c.c.a.e0.R()',receiver=receiver,args=[]),
                event(305,'call_exit',call_id='R',error=None)]
            before = next(i for i,r in enumerate(rows) if r.get('name')=='arrival-counter')
            rows[before:before] = actual
        else:
            callback=next(r for r in rows if r.get('call_id')=='response')
            callback['receiver']=receiver
            actual=[event(103,'timer_post_enter',post_id='z-post',call_id='response'),event(104,'call_exit',call_id='response',error=None),
                event(105,'timer_execute_enter',call_id='z-timer',post_id='z-post',queue_id='MMT',runnable={'class':'k.h.a0.c.c.a.k'}),
                event(106,'call_enter',call_id='Z',parent_call_id='z-timer',method='k.h.a0.c.c.a.e0.Z(java.util.ArrayList,long)',receiver=receiver,
                    args=[copy.deepcopy(callback['args'][1]),dict(type='i64',decimal='81')]),event(107,'call_exit',call_id='Z',error=None)]
            before=next(i for i,r in enumerate(rows) if r.get('name')=='deliver-observed-native-lane-responses')
            rows[before:before]=actual
            fields={key:dict(type='i32',value=value) for key,value in [('segmentIndex',1),('linkIndex',0)]}
            fields.update({key:dict(type='i64',decimal=value) for key,value in [('pathId','123'),('timestamp','5'),('requestId','81')]})
            fields.update({key:dict(type='list',items=[dict(type='i32',value=2)]) for key in
                           ('backLane','frontLane','extendLane','recommendLane','backLaneType','frontLaneType')})
            bean=dict(type='struct', **{'class':'com.byd.map.signal.someip.pilot.bean.QueryAppointLanesInfoBean'},fields=fields)
            serialized = dict(linkIndex=0,segmentIndex=1,pathId=123,timestamp=5,backLane=[2],frontLane=[2],
                              extendLane=[2],recommendLane=[2],backLaneType=[2],frontLaneType=[2])
            content=json.dumps([serialized],separators=(',',':')).encode()
            size=len(content);length=[]
            while size>127: length.append((size&127)|128);size>>=7
            length.append(size)
            payload=base64.b64encode(bytes([10]+length)+content).decode()
            mapping=dict(type='struct',fields={'entries':dict(type='list',items=[dict(type='list',items=[dict(type='i64',decimal='81'),dict(type='i64',decimal='123')])])})
            actual=[event(200,'timer_post_enter',post_id='f-post',call_id='B'),
                event(201,'timer_execute_enter',call_id='f-timer',post_id='f-post',queue_id='MMT',runnable={'class':'k.h.a0.c.c.a.l'}),
                event(202,'call_enter',call_id='F',parent_call_id='f-timer',method='k.h.a0.c.c.a.e0.F(int,int,java.util.Map)',receiver=receiver,
                    args=[dict(type='i32',value=1),dict(type='i32',value=0),mapping]),
                event(203,'call_enter',call_id='lane-wire',parent_call_id='F',method='k.h.a0.c.c.a.f0.i(java.util.List)',
                    args=[dict(type='list',items=[bean])]),
                event(204,'getter_enter',call_id='lane-wire',read_id='lane-send',method='com.autosdk.bussiness.pilot.someip.SomeIpServer.sendSomeIpEvent(ts.car.someip.sdk.common.SomeIpData)',
                    args=[dict(type='struct',class_='unused')]),
                event(205,'output',call_id='lane-wire',operation='fire',topic='1268847189590030',payload_b64=payload),
                event(206,'getter',call_id='lane-wire',read_id='lane-send',method='com.autosdk.bussiness.pilot.someip.SomeIpServer.sendSomeIpEvent(ts.car.someip.sdk.common.SomeIpData)',error=None,result=dict(type='i32',value=0)),
                event(207,'call_exit',call_id='lane-wire',error=None),event(208,'call_exit',call_id='F',error=None)]
            packet={'type':'struct','class':'ts.car.someip.sdk.common.SomeIpData','fields':{
                'mTopic':dict(type='i64',decimal='1268847189590030'),
                'mPayload':dict(type='bytes',base64=payload),'mTimeStamp':dict(type='i64',decimal='5')}}
            actual[4]['args']=[packet]; actual[6]['args']=copy.deepcopy(actual[4]['args'])
            before=next(i for i,r in enumerate(rows) if r.get('kind')==ca.PREFIX+'end')
            rows[before:before]=actual
    for line, row in enumerate(rows, 1):
        if row.get('kind') == 'capture_end': row.setdefault('seq',10000)
        row['source_line'] = line
    return contract, rows


class CallbackAuditTests(unittest.TestCase):
    def test_actual_source_cases_have_complete_ordered_steps_without_race_claim(self):
        for selected, count in [('arrival-idle', 3), ('rare-active', 25), ('reset-offcourse', 14), ('lane-query', 2), ('city-odd', 3)]:
            contract, rows = fixture(selected)
            report = ca.audit(rows, contract, fixture_source=SOURCE)
            self.assertEqual(report['gaps'], [])
            self.assertEqual(len(report['expected_steps']), count)
            self.assertTrue(report['fixtureInputsDelivered'])
            self.assertFalse(report['raceEstablished'])

    def test_arrival_requires_actual_R_entry_and_successful_return_not_pending_task(self):
        for mutation in ('delivered_only', 'future_pending', 'error', 'foreign_dispatch', 'late_exit'):
            contract, rows = fixture('arrival-idle')
            if mutation in ('delivered_only', 'future_pending'):
                rows[:] = [r for r in rows if r.get('call_id') not in ('R','arrival-timer')]
                if mutation == 'delivered_only': rows[:] = [r for r in rows if r.get('post_id') != 'arrival-post']
            elif mutation == 'error': next(r for r in rows if r.get('call_id')=='R' and r['kind']=='call_exit')['error']='failed'
            elif mutation == 'foreign_dispatch': next(r for r in rows if r.get('post_id')=='arrival-post' and r['kind']=='timer_post_enter')['call_id']='other-callback'
            else: next(r for r in rows if r.get('call_id')=='R' and r['kind']=='call_exit')['seq']=10001
            report=ca.audit(rows,contract,fixture_source=SOURCE)
            self.assertTrue(any('callback_arrival_processing_missing' in gap for gap in report['gaps']),mutation)

    def test_lane_requires_actual_Z_F_and_nonempty_matching_wire_not_delivery_or_future_work(self):
        contract, rows = fixture('lane-query')
        self.assertEqual(ca.audit(rows,contract,fixture_source=SOURCE)['gaps'],[])
        # One selected controlled bean is sufficient; two independently sent
        # extension variants are not required by source F's filtering contract.
        for mutation in ('delivered_only','future_F','missing_Z_exit','missing_F_exit','empty_wire','request_map','wrong_topic','wrong_bytes','late_F_exit'):
            declared, raw = copy.deepcopy((contract,rows))
            if mutation=='delivered_only': raw[:]=[r for r in raw if r.get('call_id') not in ('Z','z-timer','F','f-timer','lane-wire')]
            elif mutation=='future_F': raw[:]=[r for r in raw if r.get('call_id') not in ('F','f-timer','lane-wire')]
            elif mutation=='missing_Z_exit': raw.remove(next(r for r in raw if r.get('call_id')=='Z' and r['kind']=='call_exit'))
            elif mutation=='missing_F_exit': raw.remove(next(r for r in raw if r.get('call_id')=='F' and r['kind']=='call_exit'))
            elif mutation=='empty_wire': next(r for r in raw if r.get('call_id')=='lane-wire' and r['kind']=='call_enter')['args'][0]['items']=[]
            elif mutation=='request_map': next(r for r in raw if r.get('call_id')=='F' and r['kind']=='call_enter')['args'][2]['fields']['entries']['items']=[]
            elif mutation=='wrong_topic': next(r for r in raw if r['kind']=='output')['topic']='1'
            elif mutation=='wrong_bytes': next(r for r in raw if r['kind']=='output')['payload_b64']=base64.b64encode(b'\x0a\x02[]').decode()
            else: next(r for r in raw if r.get('call_id')=='F' and r['kind']=='call_exit')['seq']=10001
            self.assertTrue(ca.audit(raw,declared,fixture_source=SOURCE)['gaps'],mutation)

    def test_lane_attempt_failed_sdk_return_is_processing_proof_not_wire_acceptance(self):
        for fired in (False, True):
            contract, rows = fixture('lane-query')
            next(r for r in rows if r.get('kind')=='getter' and r.get('read_id')=='lane-send')['result']['value']=-1
            if not fired: rows[:]=[r for r in rows if r.get('kind')!='output']
            report=ca.audit(rows,contract,fixture_source=SOURCE)
            self.assertEqual(report['gaps'],[])
            proof=report['observed_lane_bindings'][0]['processing']
            self.assertEqual(proof['transport_status'],'fired_with_failed_return' if fired else 'attempted_not_fired')
            self.assertEqual(proof['output_seq'] is not None,fired)
        for mutation in ('missing_completion','error','payload','success_without_fire'):
            contract, rows=fixture('lane-query')
            completion=next(r for r in rows if r.get('kind')=='getter' and r.get('read_id')=='lane-send')
            if mutation=='missing_completion': rows.remove(completion)
            elif mutation=='error': completion['error']='failed'
            elif mutation=='payload': completion['args'][0]['fields']['mPayload']['base64']='CgJbXQ=='
            else: rows[:]=[r for r in rows if r.get('kind')!='output']
            self.assertTrue(ca.audit(rows,contract,fixture_source=SOURCE)['gaps'],mutation)

    def test_lane_aggregate_attempts_bind_actual_successful_processing_exits(self):
        contract, rows=fixture('lane-query')
        step=next(r for r in rows if r.get('name')=='deliver-observed-native-lane-responses')
        record=step['observed_lane_requests'][0]; ref=step.pop('response_input_ref')
        step['response_inputs']=[dict(requestId=record['requestId'],read_id=record['read_id'],response_input_ref=ref)]
        step['selected_processing']={'Z':dict(source_seq=107,call_id='Z',error=None),
            'F':dict(source_seq=208,call_id='F',error=None),'wire':dict(source_seq=207,call_id='lane-wire',error=None),'request_ids':['81']}
        rows.remove(step); rows.insert(next(i for i,r in enumerate(rows) if r.get('kind')==ca.PREFIX+'end'),step)
        attempt=dict(kind=ca.PREFIX+'lane_attempt',case='lane-query',attempt_seq=1,
            observed_lane_request=copy.deepcopy(record),response_input_ref=ref,lane_variants=['backExtenLane','extensionLane'])
        rows.insert(next(i for i,r in enumerate(rows) if r.get('call_id')=='Z'),attempt)
        for index,row in enumerate(rows,1):row['source_line']=index
        self.assertEqual(ca.audit(rows,contract,fixture_source=SOURCE)['gaps'],[])
        for mutation in ('missing_attempt','sequence','request','identity','selected_Z','selected_F','selected_wire','selected_request','early_step'):
            declared,raw=copy.deepcopy((contract,rows))
            receipt=next(r for r in raw if r.get('kind')==ca.PREFIX+'lane_attempt')
            delivered=next(r for r in raw if r.get('name')=='deliver-observed-native-lane-responses')
            if mutation=='missing_attempt':raw.remove(receipt)
            elif mutation=='sequence':receipt['attempt_seq']=2
            elif mutation=='request':receipt['observed_lane_request']['requestId']='999'
            elif mutation=='identity':receipt['response_input_ref']['id']='unknown'
            elif mutation.startswith('selected_'):
                key=mutation.removeprefix('selected_')
                if key=='request':delivered['selected_processing']['request_ids']=['999']
                else:delivered['selected_processing'][key]['source_seq']=999
            else:delivered['source_line']=1
            self.assertTrue(ca.audit(raw,declared,fixture_source=SOURCE)['gaps'],mutation)

    def test_lane_declared_stop_gate_requires_F_exit_complete_inputs_and_original_destroy(self):
        contract,rows=fixture('lane-query')
        contract.update(guidance_stop_policy='after_lane_processing_and_gnss_inputs_v1',guidance_sample_count=2)
        terminal=next(r for r in rows if r.get('kind')=='capture_end');rows.remove(terminal)
        rows += [dict(kind='reroute_gnss_input_return',count=1),dict(kind='reroute_gnss_input_return',count=2),
            dict(kind=ca.PREFIX+'stop_gate',case='lane-query',policy=contract['guidance_stop_policy'],status='conditions-satisfied',
                 reason='lane-processing-and-gnss-inputs-complete',sample_count=2,delivered_samples=2,callback_fixture_complete=True),
            dict(kind='adas_presenter_stop',reason='lane-processing-and-gnss-inputs-complete',result=True),
            dict(kind='call_enter',schema='denza-stock-sender-phase2-v1',seq=300,call_id='destroyed',method='k.h.e0.b.d.j()',args=[]),
            dict(kind='call_exit',schema='denza-stock-sender-phase2-v1',seq=301,call_id='destroyed',error=None),terminal]
        for i,row in enumerate(rows,1):row['source_line']=i
        self.assertEqual(ca.audit(rows,contract,fixture_source=SOURCE)['gaps'],[])
        for mutation in ('no_gate','missing_return','extra_return','early_end','early_destroy','policy','missing_policy','wrong_stop'):
            declared,raw=copy.deepcopy((contract,rows))
            if mutation=='no_gate':raw.remove(next(r for r in raw if r.get('kind')==ca.PREFIX+'stop_gate'))
            elif mutation=='missing_return':raw.remove(next(r for r in raw if r.get('kind')=='reroute_gnss_input_return'))
            elif mutation=='extra_return':raw.append(dict(kind='reroute_gnss_input_return',count=3,source_line=1))
            elif mutation=='early_end':next(r for r in raw if r.get('kind')==ca.PREFIX+'end')['source_line']=1
            elif mutation=='early_destroy':next(r for r in raw if r.get('call_id')=='destroyed' and r['kind']=='call_enter')['seq']=1
            elif mutation=='policy':declared['guidance_stop_policy']='unrecognized'
            elif mutation=='missing_policy':declared.pop('guidance_stop_policy')
            else:next(r for r in raw if r.get('kind')=='adas_presenter_stop')['reason']='timer'
            self.assertTrue(ca.audit(raw,declared,fixture_source=SOURCE)['gaps'],mutation)

    def test_lane_audit_consumes_actual_recorder_querybean_dto_including_transient_request(self):
        from . import test_phase2_recorder as recorder_tests
        recorded = recorder_tests.Phase2RecorderTest().dto_cache_js(r"""
function integer(n){return {getClass(){return {getName(){return 'java.lang.Integer';}};},intValue(){return n;}};}
function list(){return {fixtureList:true,size(){return 1;},get(){return integer(2);},getClass(){return {getName(){return 'java.util.ArrayList';}};}};}
p2J.List={class:{isInstance:x=>!!(x&&x.fixtureList)}};
const fields=[field('serialVersionUID','long',()=>({toString:()=> '9'}),8),
 field('linkIndex','int',()=>0),field('segmentIndex','int',()=>1),
 field('pathId','long',()=>({toString:()=> '123'})),field('timestamp','long',()=>({toString:()=> '5'})),
 field('requestId','long',()=>({toString:()=> '81'}),128)];
for(const name of ['backLane','frontLane','extendLane','recommendLane','backLaneType','frontLaneType'])
 fields.push(field(name,'java.util.ArrayList',()=>list()));
const type=cls(fields);type.getName=()=> 'com.byd.map.signal.someip.pilot.bean.QueryAppointLanesInfoBean';
result=p2Value(dto(type),null);
""")['result']
        self.assertEqual(recorded['type'],'struct','production recorder must expose the original bean, not an opaque reference')
        self.assertEqual(recorded['fields']['requestId'],{'type':'i64','decimal':'81'})
        self.assertNotIn('serialVersionUID',recorded['fields'])
        contract, rows = fixture('lane-query')
        wire=next(r for r in rows if r.get('call_id')=='lane-wire' and r['kind']=='call_enter')
        wire['args'][0]['items']=[recorded]
        self.assertEqual(ca.audit(rows,contract,fixture_source=SOURCE)['gaps'],[])
        wire['args'][0]['items']=[{k:v for k,v in recorded.items() if k!='fields'}]
        self.assertTrue(ca.audit(rows,contract,fixture_source=SOURCE)['gaps'])

    def test_reset_profile_uses_sdk_native_options_and_actual_path_callbacks(self):
        script = r'''
const fs=require('fs'), vm=require('vm'), assert=require('assert');
const timers=[], reports=[], calls=[], optionTypes=[];
global.int64=x=>'long:'+x;
global.setTimeout=(f,ms)=>{timers.push({f,ms});return timers.length;};
global.clearTimeout=()=>{};
global.Java={scheduleOnMainThread:f=>f(),use:function(name){
  assert.strictEqual(this,Java,'Java.use must retain its Frida bridge receiver');
  if(name==='java.util.ArrayList')return {$new:()=>({items:[],add(x){this.items.push(x);}})};
  if(name==='java.lang.Integer'||name==='java.lang.Short')return {valueOf:{overload:()=>({call:(owner,x)=>x})}};
  if(name==='com.autonavi.gbl.common.path.option.RouteOption')return {$new:()=>({nativeClass:name,
    setRouteType(type){this.routeType=type;optionTypes.push(type);return process.argv[2]==='true';}})};
  return {$new:()=>new Proxy({nativeClass:name},{get:(target,key)=>{
    if(!(key in target))target[key]={value:0};return target[key];}})};
}};
vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
const path={isValid:()=>true,getPathID:()=>'long:9007199254740993'};
const mmt=new Proxy({}, {get:(target,method)=>(...args)=>{
  calls.push({method,args});
  if(method==='onReroute')assert.strictEqual(args[0].nativeClass,'com.autonavi.gbl.common.path.option.RouteOption');
}});
p2RunCallbackCase({case:'reset-offcourse',mmt,path:()=>path,navi:()=>({nativeNavi:true}),
  location:{matchedLon:116,matchedLat:40},report:(kind,data)=>reports.push({kind,...data})});
for(const timer of [...timers].sort((a,b)=>a.ms-b.ms))timer.f();
assert.deepStrictEqual(optionTypes,[2,1]);
const change=calls.find(c=>c.method==='onChangeNaviPath');
assert.deepStrictEqual(change.args,['long:9007199254740993','long:9007199254740993']);
for(const name of ['onCruiseStart','onCruiseStop','onNaviSimStart','onSelectMainPathStatus'])
  assert(calls.some(c=>c.method===name));
assert.strictEqual(timers[timers.length-1].ms,20000);
const errors=reports.filter(r=>r.kind==='native_callback_fixture_error');
assert.strictEqual(errors.length,process.argv[2]==='true'?0:2);
console.log(JSON.stringify({steps:reports.filter(r=>r.kind==='native_callback_fixture_step').length,errors:errors.length}));
'''
        for succeeds in ('true', 'false'):
            with self.subTest(native_setter_succeeds=succeeds):
                result = subprocess.run(['node', '-e', script, str(Path(__file__).with_name('phase2_cases.js')), succeeds],
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_attach_records_plan_and_attachment_before_racing_zero_delay_callback(self):
        script = r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const queued=[],reports=[],calls=[];
global.int64=x=>String(x);
// Model a MAIN thread that runs the first zero-delay callback before setTimeout returns.
global.setTimeout=(f,ms)=>{if(ms===0)f();else queued.push({f,ms});return queued.length;};
global.clearTimeout=()=>{};
const mmt=new Proxy({}, {get:(target,method)=>(...args)=>{
  assert(reports.some(r=>r.kind==='native_callback_fixture_attached'));
  calls.push(String(method));
}});
const path={getPathID:()=>123};
global.Java={perform:f=>f(),scheduleOnMainThread:f=>f(),use:function(name){
  assert.strictEqual(this,Java);
  if(name==='com.autosdk.bussiness.navi.NaviController')return {getInstance:()=>({isNaving:()=>true})};
  if(name==='k.h.e0.b.d')return {e:()=>({getClass:()=>({getDeclaredFields:()=>[]})})};
  if(name==='k.h.a0.c.c.a.e0')return {A:()=>mmt};
  if(name==='k.h.e0.b.f')return {m:()=>({})};
  if(name==='java.util.ArrayList')return {$new:()=>({items:[],add(x){this.items.push(x);}})};
  if(name==='java.lang.Integer'||name==='java.lang.Short')return {valueOf:{overload:()=>({call:(o,x)=>x})}};
  return {$new:()=>new Proxy({}, {get:(t,k)=>{if(!(k in t))t[k]={value:0};return t[k];}})};
}};
vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
p2AttachCallbackCase({case:'reset-offcourse',seed:()=>({getCurrentFocusPath:()=>path}),
 navi:()=>({}),location:{matchedLon:116,matchedLat:40},report:(kind,data)=>reports.push({kind,...data})});
queued.shift().f();
assert.deepStrictEqual(reports.slice(0,3).map(r=>r.kind),[
 'native_callback_fixture_plan','native_callback_fixture_attached','native_callback_fixture_step']);
assert(calls.length>0);
assert(!reports.some(r=>r.kind==='native_callback_fixture_error'));
// Explicit preparation itself creates no timers or callbacks; start is idempotent.
const before=queued.length, count=calls.length;
const handle=p2RunCallbackCase({case:'arrival-idle',mmt,deferStart:true,report:()=>{}});
assert.strictEqual(queued.length,before);assert.strictEqual(calls.length,count);
handle.cancel();handle.start();assert.strictEqual(queued.length,before);
'''
        result = subprocess.run(['node', '-e', script, str(Path(__file__).with_name('phase2_cases.js'))],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_fixture_end_waits_for_real_main_returns_and_never_completes_failed_or_cancelled_steps(self):
        script = r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const timers=[],main=[],reports=[];
global.p2CallbackInputCompleted=false;
global.int64=x=>x;global.setTimeout=(f,ms)=>{timers.push({f,ms});return timers.length;};global.clearTimeout=()=>{};
global.Java={scheduleOnMainThread:f=>main.push(f),use:function(name){assert.strictEqual(this,Java);
 if(name==='com.autosdk.bussiness.navi.NaviController')return {getInstance:()=>({isNaving:()=>false})};
 if(name==='java.util.ArrayList')return {$new:()=>({add(){}})};
 if(name==='java.lang.Integer'||name==='java.lang.Short')return {valueOf:{overload:()=>({call:(o,x)=>x})}};
 throw Error(name);
}};
vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
const handle=p2RunCallbackCase({case:'arrival-idle',pathId:'123',mmt:{p(){},onNaviArrive(){
 if(process.argv[2]==='failed')throw Error('actual original callback failed');},onNavStop(){}},
 report:(kind,data)=>reports.push({kind,...data})});
for(const timer of timers.slice().sort((a,b)=>a.ms-b.ms))timer.f();
assert.equal(main.length,3);
assert(!reports.some(r=>r.kind==='native_callback_fixture_end'),'nominal deadline must not declare queued callbacks complete');
assert.strictEqual(p2CallbackInputCompleted,false);
if(process.argv[2]==='cancelled')handle.cancel();
main.shift()();main.shift()();
assert(!reports.some(r=>r.kind==='native_callback_fixture_end'),'pending last callback must block completion');
main.shift()();
const ends=reports.filter(r=>r.kind==='native_callback_fixture_end');
if(process.argv[2]==='success'){
 assert.equal(ends.length,1);assert.equal(reports[reports.length-1].kind,'native_callback_fixture_end');
 assert.strictEqual(p2CallbackInputCompleted,true);
 assert.equal(reports.filter(r=>r.kind==='native_callback_fixture_step').length,3);
}else{assert.equal(ends.length,0);assert.strictEqual(p2CallbackInputCompleted,false);
 if(process.argv[2]==='failed')assert.equal(reports.filter(r=>r.kind==='native_callback_fixture_error').length,1);
 else assert.equal(reports.filter(r=>r.kind==='native_callback_fixture_step').length,0);
}
'''
        for scenario in ('success', 'failed', 'cancelled'):
            with self.subTest(scenario=scenario):
                result = subprocess.run(['node', '-e', script, str(Path(__file__).with_name('phase2_cases.js')), scenario],
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_city_fixture_reads_actual_urid_and_distinguishes_synthetic_control(self):
        script = r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const timers=[],reports=[],lists=[],calls=[];let reads=0;
global.setTimeout=(f,ms)=>{timers.push({f,ms});return timers.length;};global.clearTimeout=()=>{};
global.p2Ref=o=>({type:'object',id:o.id||'actual',class:'OriginalSDK'});
global.Java={use:function(name){assert.strictEqual(this,Java);
 if(name==='java.util.ArrayList')return {$new:()=>({id:'list'+lists.length,items:[],add(x){this.items.push(x);}})};
 if(name==='java.lang.Integer'||name==='java.lang.Short')return {valueOf:{overload:()=>({call:(o,x)=>x})}};
 if(name==='k.h.e0.d.f.m')return {a:input=>lists.push(input.items.slice())};throw Error(name);
},scheduleOnMainThread:f=>f()};
vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
const link={getURID:()=>{reads++;return Number(process.argv[2]);}},segment={getLinkInfo:i=>{assert.equal(i,2);return link;}};
const path={isValid:()=>true,getSegmentInfo:i=>{assert.equal(i,1);return segment;}};
const navi={curSegIdx:{value:1},curLinkIdx:{value:2}};
p2RunCallbackCase({case:'city-odd',cityPolicy:'actual-current-urid-synthetic-zero-clear-v1',cityExcludeIds:[0],
 mmt:{l:(n,p)=>calls.push('mmt')},drive:{l:(n,p)=>{assert.strictEqual(n,navi);assert.strictEqual(p,path);calls.push('g');}},
 path:()=>path,navi:()=>navi,report:(kind,data)=>reports.push({kind,...data})});
for(const timer of timers.slice())timer.f();
assert.equal(reads,3);assert.equal(timers[timers.length-1].ms,20000);
if(process.argv[2]==='0'){assert.equal(lists.length,0);assert.equal(reports.filter(r=>r.kind==='native_callback_fixture_error').length,3);}
else{assert.deepStrictEqual(lists,[[110000],[0],[]]);assert.equal(calls.length,6);
 assert.deepStrictEqual(reports.filter(r=>r.kind==='native_callback_fixture_step').map(r=>r.city_input.origin),
 ['actual_current_link_urid','declared_synthetic_control','explicit_empty_list']);}
'''
        for city in ('110000', '0'):
            result = subprocess.run(['node', '-e', script, str(Path(__file__).with_name('phase2_cases.js')), city],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_city_audit_rejects_missing_callback_values_and_synthetic_road_claim(self):
        for mutation in ('missing', 'values', 'origin', 'zero', 'policy'):
            contract, rows = fixture('city-odd')
            if mutation == 'missing': rows.remove(next(r for r in rows if r.get('kind') == 'call_exit'))
            elif mutation == 'values': next(r for r in rows if r.get('method', '').startswith('k.h.e0.d.f.m.a'))['args'][0]['items'] = []
            elif mutation == 'policy': contract['city_exclude_ids'] = [1]
            else:
                step = next(r for r in rows if r.get('name') == 'exclude-declared-synthetic-city-zero')
                if mutation == 'origin': step['city_input']['origin'] = 'actual_current_link_urid'
                else: step['city_input']['current_urid'] = 0
            self.assertTrue(ca.audit(rows, contract, fixture_source=SOURCE)['gaps'], mutation)

    def test_previous_immutable_two_profile_sources_keep_their_plan(self):
        begin = SOURCE.index("  } else if (kind === 'reset-offcourse') {")
        end = SOURCE.index('  } else {', begin)
        old_source = SOURCE[:begin] + SOURCE[end:]
        for selected in ('rare-active', 'arrival-idle'):
            self.assertEqual(ca.planned_steps(old_source, selected), ca.planned_steps(SOURCE, selected))
        begin = SOURCE.index("  } else if (kind === 'lane-query') {")
        end = SOURCE.index('  } else {', begin)
        three_profile_source = SOURCE[:begin] + SOURCE[end:]
        for selected in ('rare-active', 'arrival-idle', 'reset-offcourse'):
            self.assertEqual(ca.planned_steps(three_profile_source, selected), ca.planned_steps(SOURCE, selected))

    def test_lane_query_metadata_requires_actual_native_read_and_path_pair(self):
        for changed in ('zero', 'metadata', 'return', 'path', 'callback', 'index', 'dispatch', 'identity'):
            with self.subTest(changed=changed):
                contract, rows = fixture('lane-query')
                step = next(r for r in rows if r.get('name') == 'deliver-observed-native-lane-responses')
                query = next(r for r in rows if r.get('read_id') == 'read81')
                callback = next(r for r in rows if r.get('call_id') == 'response')
                if changed == 'zero': step['observed_lane_requests'][0]['requestId'] = '0'
                elif changed == 'metadata': step['observed_lane_requests'][0]['read_id'] = 'unrecorded'
                elif changed == 'return': query['result']['value'] = 82
                elif changed == 'path': rows.remove(next(r for r in rows if r.get('read_id') == 'path1'))
                elif changed == 'callback': callback['args'][0]['decimal'] = '82'
                elif changed == 'index': callback['args'][1]['items'][0]['fields']['segmentIdx']['value'] = 9
                elif changed == 'dispatch': next(r for r in rows if r['kind'] == 'timer_execute_enter')['queue_id'] = 'OTHER'
                else: step['response_input_ref']['id'] = 'another-list'
                self.assertTrue(ca.audit(rows, contract, fixture_source=SOURCE)['gaps'])

    def test_real_sdk_response_for_same_query_does_not_replace_controlled_input(self):
        contract, rows = fixture('lane-query')
        at = next(i for i, r in enumerate(rows) if r.get('call_id') == 'response')
        natural = copy.deepcopy(rows[at])
        natural.update(call_id='natural-sdk-response', seq=102)
        natural['args'][1]['id'] = 'natural-sdk-list'
        rows[at]['seq'] = 103
        rows.insert(at, natural)
        for line, row in enumerate(rows, 1):
            row['source_line'] = line
        report = ca.audit(rows, contract, fixture_source=SOURCE)
        self.assertEqual(report['gaps'], [])
        self.assertEqual(report['observed_lane_bindings'][0]['callback_seq'], 103)

    def test_lane_fixture_waits_for_completed_query_and_never_requeries(self):
        script = r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
let now=0, pathIdReads=0;const timers=[],reports=[],responses=[];
Date.now=()=>now;global.int64=x=>'long:'+x;
global.p2Ref=x=>({type:'object',id:'fixture-lanes',class:'java.util.ArrayList',native_pointer:null});
global.setTimeout=(f,ms)=>{timers.push({f,at:now+ms});return timers.length;};global.clearTimeout=()=>{};
global.Java={scheduleOnMainThread:f=>f(),use:function(name){
 assert.strictEqual(this,Java,'Java.use must retain its Frida bridge receiver');
 assert(!name.includes('GuideService'));
 if(name==='java.util.ArrayList')return {$new:()=>({items:[],add(x){this.items.push(x);}})};
 if(name==='java.lang.Integer'||name==='java.lang.Short')return {valueOf:{overload:()=>({call:(owner,x)=>x})}};
 return {$new:()=>new Proxy({},{get:(target,key)=>{if(!(key in target))target[key]={value:0};return target[key];}})};
}};
vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
const path={isValid:()=>true,getPathID:()=>{pathIdReads++;return '123';}};
const mmt={d(){},l(){},onQueryAppointLanesInfo(id,lanes){responses.push({id,count:lanes.items.length});}};
const request={read_id:'read81',source_seq:100,requestId:'81',segmentIdx:1,linkIdx:0,num:6};
p2RunCallbackCase({case:'lane-query',mmt,path:()=>path,navi:()=>({}),location:{matchedLon:116,matchedLat:40},
 laneRequests:()=>now<500?[]:[{...request,requestId:process.argv[2]==='true'?'81':'0'}],
 report:(kind,data)=>reports.push({kind,...data})});
while(timers.length){timers.sort((a,b)=>a.at-b.at);const t=timers.shift();now=t.at;t.f();assert(now<=121000);}
assert.strictEqual(pathIdReads,0);
if(process.argv[2]==='true'){
 assert.deepStrictEqual(responses,[{id:'long:81',count:2}]);
 assert.strictEqual(reports.filter(r=>r.kind==='native_callback_fixture_step').length,1);
 assert.strictEqual(reports.filter(r=>r.kind==='native_callback_fixture_error').length,1);
 assert(!reports.some(r=>r.kind==='native_callback_fixture_end'),'delivered input without actual F processing cannot complete');
}else{
 assert.strictEqual(responses.length,0);
 assert.strictEqual(reports.filter(r=>r.kind==='native_callback_fixture_error').length,1);
 assert.strictEqual(reports.filter(r=>r.kind==='native_callback_fixture_step').length,1);
}
'''
        for available in ('true', 'false'):
            with self.subTest(actual_nonzero_query=available):
                result = subprocess.run(['node', '-e', script, str(Path(__file__).with_name('phase2_cases.js')), available],
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_nominal_end_cannot_replace_missing_delivered_step(self):
        contract, rows = fixture()
        del rows[4]
        report = ca.audit(rows, contract, fixture_source=SOURCE)
        self.assertFalse(report['fixtureInputsDelivered'])
        self.assertIn(dict(callback_fixture_steps_missing_extra_failed_or_out_of_order=True), report['gaps'])

    def test_case_plan_step_error_and_order_changes_fail(self):
        for changed in ('case', 'plan', 'duplicate', 'order', 'error', 'status', 'cancelled', 'unknown'):
            with self.subTest(changed=changed):
                contract, rows = fixture()
                if changed == 'case': rows[0]['case'] = 'arrival-idle'
                elif changed == 'plan': rows[0]['steps'][0]['at_ms'] = 100
                elif changed == 'duplicate': rows.insert(3, copy.deepcopy(rows[2]))
                elif changed == 'order': rows[2]['name'], rows[3]['name'] = rows[3]['name'], rows[2]['name']
                elif changed == 'error': rows.insert(-2, dict(kind=ca.PREFIX+'error', case=contract['case'], name='partial-step', error='failure'))
                elif changed == 'status': rows[2]['status'] = 'scheduled'
                elif changed == 'cancelled': rows[-2]['status'] = 'cancelled'
                else: rows.insert(-2, dict(kind=ca.PREFIX+'unknown', case=contract['case']))
                self.assertTrue(ca.audit(rows, contract, fixture_source=SOURCE)['gaps'])

    def test_fixture_completion_must_precede_capture_end_and_have_attachment(self):
        for changed in ('boundary', 'attached', 'path'):
            contract, rows = fixture('arrival-idle')
            if changed == 'boundary': rows[-1]['source_line'] = rows[-2]['source_line'] - 1
            elif changed == 'attached': del rows[1]
            else: rows[1]['original_path_id'] = 'another-path'
            self.assertTrue(ca.audit(rows, contract, fixture_source=SOURCE)['gaps'])

    def test_contract_and_loaded_fixture_provenance_are_reverified(self):
        contract, rows = fixture()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            js, table, agent = [root / name for name in ('fixture.js', 'contract.json', 'agent.js')]
            js.write_text(SOURCE); table.write_text(ca.canonical(contract))
            agent.write_text('const P2_CALLBACK_CASE='+ca.canonical(contract)+';\n'+SOURCE)
            artifacts = [dict(role=role, path=str(path), sha256=hashlib.sha256(path.read_bytes()).hexdigest())
                         for role, path in [('callback_fixture', js), ('callback_contract', table)]]
            report = ca.declared_audit(rows, {'callback_contract': contract}, artifacts, agent)
            self.assertTrue(report['fixtureInputsDelivered'], report['gaps'])
            self.assertEqual(report['verified_artifacts'], artifacts)
            self.assertTrue(ca.declared_audit(rows, {}, artifacts, agent)['gaps'])
            for path in (js, table, agent):
                original = path.read_bytes(); path.write_bytes(b'tampered')
                self.assertTrue(ca.declared_audit(rows, {'callback_contract': contract}, artifacts, agent)['gaps'])
                path.write_bytes(original)

    def test_opt_out_only_when_metadata_artifacts_agent_and_events_are_absent(self):
        self.assertIsNone(ca.declared_audit([], {'callback_contract': None}, [], None))
        _, rows = fixture()
        self.assertTrue(ca.declared_audit(rows, {}, [], None)['gaps'])
