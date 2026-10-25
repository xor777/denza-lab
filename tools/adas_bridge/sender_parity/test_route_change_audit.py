"""Original processing exits, not fixture intentions, prove route changes."""
import copy
import hashlib
import json
import unittest
from . import callback_audit as ca, route_change_audit as audit


def fixture(route_packets=1):
    raw, steps = [], []
    ref = lambda name, cls='Original': dict(type='object', id=name, **{'class': cls}, native_pointer=None)
    long = lambda n: dict(type='i64', decimal=str(n))
    drive = ref('drive')
    def add(kind, **kw):
        row = dict(kind=kind, schema='denza-stock-sender-phase2-v1', seq=len(raw), source_line=10+len(raw), **kw)
        raw.append(row); return row
    def enter(cid, name, args, parent=None):
        return add('call_enter', call_id=cid, parent_call_id=parent, method=audit.G+name+'()', receiver=drive, args=args)
    def finish(entry):
        return add('call_exit', call_id=entry['call_id'], method=entry['method'], args_after=copy.deepcopy(entry['args']), error=None)
    def observed_g(cid, route, request):
        entry = enter(cid, 'G', [route])
        add('getter', method='com.autosdk.bussiness.navi.route.model.RouteCarResultData.getRequestId()', receiver=route, result=long(request), error=None)
        return finish(entry)
    def progress(cid, path, path_id, ni, post=None):
        entry = enter(cid, 'l', [ni, path])
        if post: add('timer_post_enter', post_id=post, call_id=cid)
        add('getter', method='com.autonavi.gbl.common.path.option.PathInfo.getPathID()', receiver=path, result=long(path_id), error=None)
        return entry, finish(entry)
    def evidence(row): return dict(source_seq=row['seq'], call_id=row['call_id'], drive_ref=drive)
    old_path = ref('old-source', 'PathInfo'); old_path['native_pointer']='111'
    old_copy = ref('old-copy', 'PathInfo'); old_copy['native_pointer']='222'
    current_path = ref('current', 'PathInfo'); current_path['native_pointer']='333'
    navi = lambda name, pid: dict(type='struct', id=name, **{'class': 'NaviInfo'}, fields={'pathID':long(pid)})
    old_ni, current_ni = navi('old-ni', 10), navi('new-ni', 20)
    old_route, current_route = ref('old-route'), ref('new-route')
    old_g = observed_g('oldG', old_route, 100)
    old_input, _ = progress('old-sdk', old_path, 10, old_ni)
    new_g = observed_g('newG', current_route, 200)
    new_input, _ = progress('new-sdk', current_path, 20, current_ni)
    ack = finish(enter('ack', 'g', [long(20)]))
    _, delivered = progress('fixture-progress', old_copy, 10, old_ni, 'postK')
    def dispatch(cid, post, method, args):
        add('timer_execute_enter', call_id=cid, post_id=post, queue_id='SOMEIP')
        return finish(enter(cid+'-core', method, args, cid))
    k = dispatch('timerK', 'postK', 'K', [long(10), old_copy])
    select = enter('select', 'onChangeNaviPath', [long(20), long(10)])
    add('timer_post_enter', post_id='postCold', call_id='select')
    selected = finish(select); cold = dispatch('timerCold', 'postCold', 'C', [long(10)])
    restore = enter('restore', 'onChangeNaviPath', [long(10), long(20)])
    add('timer_post_enter', post_id='postCnew', call_id='restore')
    restored = finish(restore); cnew = dispatch('timerCnew', 'postCnew', 'C', [long(20)])
    positive = []
    for number, name, args in ((1, 'g', [long(10)]), (2, 'w', [])):
        trigger = enter('positive-trigger'+str(number), name, args)
        selector = enter('selector'+str(number), 'onSelectMainPathStatus', [long(20),dict(type='i32',value=1)], trigger['call_id'])
        selector['method']=audit.G+'onSelectMainPathStatus(long,int)'
        add('timer_post_enter',post_id='postI'+str(number),call_id=selector['call_id'])
        finish(selector); trigger_end=finish(trigger)
        timer='timerI'+str(number)
        add('timer_execute_enter',call_id=timer,post_id='postI'+str(number),queue_id='SOMEIP',runnable={'class':'k.h.e0.c.a'})
        processing=enter('I'+str(number),'I',[long(20)],timer)
        send=enter('send'+str(number),'unused',[current_route,long(20),dict(type='boolean',value=True)],processing['call_id'])
        send['method']='k.h.e0.d.f.k.i(com.autosdk.bussiness.navi.route.model.RouteCarResultData,long,boolean)'
        for part,topic in enumerate(['1126084593287170']*route_packets+['1126093183352833']):
            packet={'type':'struct','class':'ts.car.someip.sdk.common.SomeIpData','fields':{
                'mTopic':long(topic),'mPayload':dict(type='bytes',base64='AQ=='),'mTimeStamp':long(5)}}
            read='sdk'+str(number)+topic+('' if part==0 or topic=='1126093183352833' else '-'+str(part))
            add('getter_enter',call_id=send['call_id'],read_id=read,method=audit.SDK_SEND,args=[packet])
            add('getter',call_id=send['call_id'],read_id=read,method=audit.SDK_SEND,args=[copy.deepcopy(packet)],result=dict(type='i32',value=-1),error=None)
        send_end=finish(send);send_end['result']=dict(type='boolean',value=True)
        positive += [trigger_end,finish(processing)]
    context = lambda route,path,source,ni,pid,req,incoming: dict(route_ref=route,path_ref=path,source_path_ref=source,
        navi_ref=ref(ni['id'],'NaviInfo'),path_id=str(pid),request_id=str(req),input_evidence=evidence(incoming))
    old = context(old_route,old_copy,old_path,old_ni,10,100,old_input)
    current = context(current_route,current_path,current_path,current_ni,20,200,new_input)
    old['sdk_copy'] = dict(constructor='com.autonavi.gbl.common.path.option.PathInfo(PathInfo)',
        source_ref=old_path,input_ref=old_copy,source_path_id='10',input_path_id='10',source_length='3000',input_length='3000')
    for index,row in enumerate((old_g,new_g,delivered,k,selected,cold,restored,cnew,*positive)):
        step = dict(kind=ca.PREFIX+'step',case=audit.CASE,name=audit.NAMES[index],status='delivered',
                    source_line=1000+index,evidence=evidence(row))
        if index==0: step['old']=old
        if index==1: step.update(current=current,ack_evidence=evidence(ack))
        steps.append(step)
    destroyed=add('call_enter',call_id='destroyed',method='k.h.e0.b.d.j()',receiver=ref('link'),args=[])
    destroyed['source_line']=1120
    destroyed_exit=finish(destroyed);destroyed_exit['source_line']=1121
    raw += [dict(kind='reroute_gnss_input_return',count=1,source_line=500),dict(kind='reroute_gnss_input_return',count=2,source_line=501),
        dict(kind=ca.PREFIX+'end',case=audit.CASE,status='scheduled-inputs-complete',source_line=1100),
        dict(kind=ca.PREFIX+'stop_gate',case=audit.CASE,policy=audit.STOP_POLICY,status='conditions-satisfied',
             reason='route-processing-and-gnss-inputs-complete',sample_count=2,delivered_samples=2,callback_fixture_complete=True,source_line=1110),
        dict(kind='adas_presenter_stop',reason='route-processing-and-gnss-inputs-complete',result=True,source_line=1111),
        dict(kind='capture_end',schema='denza-stock-sender-phase2-v1',seq=2000,source_line=1200)]
    raw += [dict(kind=ca.PREFIX+'attached',case=audit.CASE,source_line=1,original_path_id='10'),
            dict(kind=ca.PREFIX+'input',case=audit.CASE,input_kind='sdk-path-copy',source_line=2,**old['sdk_copy'])]
    return raw, steps, dict(schema=ca.SCHEMA,case=audit.CASE,route_change_policy=audit.POLICY,
                           guidance_stop_policy=audit.STOP_POLICY,guidance_sample_count=2)


class RouteChangeAuditTests(unittest.TestCase):
    def test_actual_sdk_copy_may_have_different_native_pointer_but_processing_is_mandatory(self):
        raw, steps, contract = fixture()
        self.assertEqual(audit.audit(raw,steps,contract), [])
        for mutation in ('copy_length','native_id','ni_path','ack','dispatch','target','post_parent','previous_operand','order','error'):
            rows, delivered, declared = copy.deepcopy((raw,steps,contract))
            if mutation=='copy_length': delivered[0]['old']['sdk_copy']['input_length']='1'
            elif mutation=='native_id': next(r for r in rows if r.get('kind')=='getter' and '.PathInfo.' in r['method'])['result']['decimal']='999'
            elif mutation=='ni_path': next(r for r in rows if r.get('call_id')=='old-sdk' and r['kind']=='call_enter')['args'][0]['fields']['pathID']['decimal']='999'
            elif mutation=='ack': next(r for r in rows if r.get('call_id')=='ack' and r['kind']=='call_enter')['args'][0]['decimal']='999'
            elif mutation=='dispatch': next(r for r in rows if r.get('call_id')=='timerK')['queue_id']='MAIN'
            elif mutation=='target': next(r for r in rows if r.get('call_id')=='timerCold-core' and r['kind']=='call_enter')['args'][0]['decimal']='999'
            elif mutation=='post_parent': next(r for r in rows if r.get('post_id')=='postK' and r['kind']=='timer_post_enter')['call_id']='another'
            elif mutation=='previous_operand': next(r for r in rows if r.get('call_id')=='select' and r['kind']=='call_enter')['args'][0]['decimal']='10'
            elif mutation=='order': delivered[3]['evidence']=delivered[1]['evidence']
            else: next(r for r in rows if r.get('call_id')=='timerCnew-core' and r['kind']=='call_exit')['error']='failed'
            self.assertTrue(audit.audit(rows,delivered,declared),mutation)

    def test_original_split_route_packets_are_all_verified_not_restricted_to_one(self):
        raw,steps,contract=fixture(route_packets=2)
        self.assertEqual(audit.audit(raw,steps,contract),[])
        next(r for r in raw if r.get('read_id','').endswith('-1') and r['kind']=='getter')['error']='second route packet failed'
        self.assertTrue(audit.audit(raw,steps,contract))

    def test_positive_I_requires_distinct_selector_dispatch_true_lookup_and_both_sdk_attempts(self):
        for mutation in ('missing_I','selector_parent','target','lookup_false','missing_route','missing_selection','sdk_error','packet_changed','early_stop'):
            raw,steps,contract=fixture()
            if mutation=='missing_I': raw.remove(next(r for r in raw if r.get('call_id')=='I2' and r['kind']=='call_exit'))
            elif mutation=='selector_parent': next(r for r in raw if r.get('post_id')=='postI2' and r['kind']=='timer_post_enter')['call_id']='positive-trigger2'
            elif mutation=='target': next(r for r in raw if r.get('call_id')=='I1' and r['kind']=='call_enter')['args'][0]['decimal']='-1'
            elif mutation=='lookup_false': next(r for r in raw if r.get('call_id')=='send1' and r['kind']=='call_exit')['result']['value']=False
            elif mutation in ('missing_route','missing_selection'):
                topic='1126084593287170' if mutation=='missing_route' else '1126093183352833'
                raw[:]=[r for r in raw if r.get('read_id')!='sdk1'+topic]
            elif mutation=='sdk_error': next(r for r in raw if r.get('kind')=='getter' and r.get('method')==audit.SDK_SEND)['error']='failed'
            elif mutation=='packet_changed': next(r for r in raw if r.get('kind')=='getter' and r.get('method')==audit.SDK_SEND)['args'][0]['fields']['mPayload']['base64']='Ag=='
            else: next(r for r in raw if r.get('call_id')=='destroyed' and r['kind']=='call_enter')['seq']=next(r['seq'] for r in raw if r.get('call_id')=='I2' and r['kind']=='call_enter')
            self.assertTrue(audit.audit(raw,steps,contract),mutation)

    def test_guidance_stop_requires_exact_returns_fixture_completion_and_no_early_destruction(self):
        for mutation in ('missing_gate','pending_fixture','missing_return','extra_return','early_destroy','wrong_stop','policy','missing_destroy_exit'):
            raw,steps,contract=fixture()
            if mutation=='missing_gate': raw.remove(next(r for r in raw if r.get('kind')==ca.PREFIX+'stop_gate'))
            elif mutation=='pending_fixture': raw.remove(next(r for r in raw if r.get('kind')==ca.PREFIX+'end'))
            elif mutation=='missing_return': raw.remove(next(r for r in raw if r.get('kind')=='reroute_gnss_input_return'))
            elif mutation=='extra_return': raw.append(dict(kind='reroute_gnss_input_return',count=3,source_line=502))
            elif mutation=='early_destroy': next(r for r in raw if r.get('call_id')=='destroyed' and r['kind']=='call_enter')['seq']=1
            elif mutation=='wrong_stop': next(r for r in raw if r.get('kind')=='adas_presenter_stop')['reason']='timer'
            elif mutation=='policy': del contract['guidance_stop_policy']
            else: raw.remove(next(r for r in raw if r.get('call_id')=='destroyed' and r['kind']=='call_exit'))
            self.assertTrue(audit.audit(raw,steps,contract),mutation)

    def test_immutable_event_driven_plan_integrates_generic_fixture_audit(self):
        raw, steps, contract = fixture()
        plan = [dict(name=name,trigger='actual original event') for name in audit.NAMES]
        source = 'const P2_ROUTE_CHANGE_STEPS='+json.dumps(plan,separators=(',',':'))+';\n'
        contract.update(fixture_sha256=hashlib.sha256(source.encode()).hexdigest(),location=None)
        raw += steps+[dict(kind=ca.PREFIX+'plan',schema='denza-native-callback-case-v1',case=audit.CASE,
                          steps=plan,location=None,source_line=0,route_change_policy=audit.POLICY)]
        next(r for r in raw if r.get('kind') == ca.PREFIX+'attached').pop('original_path_id')
        self.assertEqual(ca.audit(raw,contract,fixture_source=source)['gaps'], [])
        waits=[dict(kind=ca.PREFIX+'wait',case=audit.CASE,stage=audit.NAMES[0],reason='actual input pending',wait_seq=i+1,at_ms=10000+i*5000,source_line=3+i) for i in range(2)]
        self.assertEqual(ca.audit(raw+waits,contract,fixture_source=source)['gaps'],[])
        for mutation in ('interval','duplicate','stage','total'):
            changed=copy.deepcopy(waits)
            if mutation=='interval': changed[1]['at_ms']=10001
            elif mutation=='duplicate': changed[1]['wait_seq']=1
            elif mutation=='stage': changed[0]['stage']='invented stage'
            else: changed=changed*7
            self.assertTrue(ca.audit(raw+changed,contract,fixture_source=source)['gaps'],mutation)
        raw.remove(next(r for r in raw if r.get('kind')==ca.PREFIX+'input'))
        self.assertTrue(ca.audit(raw,contract,fixture_source=source)['gaps'])
