"""Actual Frida wrapper logic: no duplicate reads, exact primitives, per-thread scopes."""
import json
from pathlib import Path
import shutil
import subprocess
import unittest


@unittest.skipUnless(shutil.which('node'), 'Node needed for the recorder wrapper')
class Phase2RecorderTest(unittest.TestCase):
    def run_js(self, assertion):
        source = Path(__file__).with_name('phase2_inputs.js').read_text()
        harness = r'''
const vm=require('vm'),fs=require('fs'), input=JSON.parse(fs.readFileSync(0,'utf8'));
const messages=[];let calls=0,tid=1;
const klass={getName(){return 'fixture.View';}};
const receiver={getClass(){return klass;}};
const f={argumentTypes:[],returnType:{className:'int'},call(){calls++;return 77;}};
const cls={class:{getDeclaredMethods(){return [{getName(){return 'getLaneNum';}}];}},getLaneNum:{overloads:[f]}};
const handlerCalls={};
function handlerMethod(name,types,answer){const m={argumentTypes:types.map(className=>({className})),call(){handlerCalls[name]=(handlerCalls[name]||0)+1;return answer;}};m.overload=()=>m;m.overloads=[m];return m;}
const H={enqueueMessage:handlerMethod('enqueue',[],true),dispatchMessage:handlerMethod('dispatch',[],undefined),
  removeCallbacks:handlerMethod('cancel',['java.lang.Runnable'],undefined),
  removeCallbacksAndMessages:handlerMethod('cancelToken',['java.lang.Object'],undefined),
  removeMessages:handlerMethod('cancelMessage',['int'],undefined)};
const handler={getClass(){return klass;},getLooper(){return {getThread(){return {getName(){return 'main';}};}};}};
const msg={getClass(){return klass;},getCallback(){return null;},obj:{value:null},what:{value:0}};
const context={
  NativeFunction:function(){return ()=>0;},Module:{getExportByName(){return 0;}},
  Memory:{alloc(){return {readS64(){return {toString(){return '1';}};},add(){return this;}};}},
  report(kind,fields){messages.push(fields);},abReport(){},
  Java:{perform(){},use(name){if(name==='fixture.View')return cls;if(name==='android.os.Handler')return H;throw Error(name);},cast(x){return x;}}
};
vm.createContext(context);vm.runInContext(input.source,context);
context.f=f;context.receiver=receiver;context.setTid=x=>{tid=x;};
context.H=H;context.handler=handler;context.msg=msg;context.handlerCalls=handlerCalls;
context.ids=new Map();
context.thread={currentThread(){return {getId(){return tid;},getName(){return 'fixture';}};}};
vm.runInContext(`p2J={Thread:thread,String:{$new(x){return x;}},Boolean:{},identities:{
  putIfAbsent(x,y){if(ids.has(x))return ids.get(x);ids.set(x,y);return null;}}};`,context);
vm.runInContext(input.assertion,context);
process.stdout.write(JSON.stringify({calls,messages,result:context.result}));
'''
        r = subprocess.run(['node','-e',harness],input=json.dumps(dict(source=source,assertion=assertion)),
                           text=True,capture_output=True,check=True,timeout=10)
        return json.loads(r.stdout)

    def test_getter_records_actual_return_once(self):
        r=self.run_js("p2Stack().push({id:'input'});p2HookClass('fixture.View','getter');result=f.implementation.call(receiver);")
        self.assertEqual((r['calls'],r['result']),(1,77))
        reads=[x for x in r['messages'] if x['kind']=='getter']
        self.assertEqual(len(reads),1)
        self.assertEqual(reads[0]['result'],{'type':'i32','value':77})
        self.assertEqual(reads[0]['call_id'],'input')

    def test_observer_failure_preserves_original_return(self):
        r=self.run_js("p2Stack().push({id:'input'});p2HookClass('fixture.View','getter');p2J.identities.putIfAbsent=()=>{throw Error('broken snapshot');};result=f.implementation.call(receiver);")
        self.assertEqual((r['calls'],r['result']),(1,77))
        self.assertTrue(any(x['kind']=='recorder_error' for x in r['messages']))

    def test_getters_outside_sender_are_not_consumed(self):
        r=self.run_js("p2HookClass('fixture.View','getter');result=f.implementation.call(receiver);")
        self.assertEqual((r['calls'],r['result']),(1,77))
        self.assertFalse(any(x['kind']=='getter' for x in r['messages']))

    def test_thread_contexts_do_not_cross(self):
        r=self.run_js("p2Stack().push({id:'first'});setTid(2);p2Stack().push({id:'second'});p2Emit('fixture',{});setTid(1);p2Emit('fixture',{});result=true;")
        self.assertEqual([x['call_id'] for x in r['messages']],['second','first'])
        self.assertEqual([x['seq'] for x in r['messages']],[0,1])

    def test_boxed_false_and_signed_zero_preserve_values(self):
        r=self.run_js("result=[p2Value({booleanValue(){return false;}},'boolean'),p2Value(-0,'float')];")
        self.assertEqual(r['result'][0],{'type':'boolean','value':False})
        self.assertEqual(r['result'][1]['bits'],'80000000')

    def test_boxed_integer_classes_are_input_metadata(self):
        r=self.run_js("result=['java.lang.Short','java.lang.Byte','java.lang.Integer'].map(name=>p2Value({getClass(){return {getName(){return name;}};},intValue(){return 7;}},null));result.push(p2Value(7,'int'));")
        self.assertEqual(r['result'],[
            {'type':'i32','value':7,'boxed_class':name}
            for name in ['java.lang.Short','java.lang.Byte','java.lang.Integer']
        ]+[{'type':'i32','value':7}])

    def dto_cache_js(self, assertion):
        return self.run_js(r"""
const refs=new Map();let nextRef=0,scans=0,metadata=0,reads=0,disposals=0;
p2Ref=x=>{if(!refs.has(x))refs.set(x,String(++nextRef));return {type:'object',id:refs.get(x),class:'fixture'};};
Java.retain=x=>({getName:(...a)=>x.getName(...a),getInt:(...a)=>x.getInt(...a),get:(...a)=>x.get(...a),
 getLong:(...a)=>x.getLong(...a),getFloat:(...a)=>x.getFloat(...a),getChar:(...a)=>x.getChar(...a),
 $dispose(){disposals++;}});
p2J.List=p2J.Collection=p2J.Map={class:{isInstance(){return false;}}};p2J.Modifier={isStatic:n=>n===8};
function field(name,type,source,mod=0,synthetic=false){return {
 getModifiers(){metadata++;return mod;},isSynthetic(){return synthetic;},setAccessible(){metadata++;},
 getName(){metadata++;return name;},getType(){metadata++;return {getName:()=>type};},
 get(){reads++;return source();},getInt(){reads++;return source();},getLong(){reads++;return source();},
 getFloat(){reads++;return source();},getChar(){reads++;return source();}};}
function cls(fields,parent=null){return {getName(){return 'com.autonavi.gbl.guide.model.Fixture';},
 getDeclaredFields(){scans++;return fields;},getSuperclass(){return parent;}};}
function dto(type){return {getClass(){return type;}};}
"""+assertion)

    def test_dto_metadata_cached_values_and_primitives_always_reread(self):
        r=self.dto_cache_js(r"""
let current=3;const type=cls([field('static','int',()=>99,8),field('synthetic','int',()=>99,0,true),
 field('number','int',()=>current),field('long','long',()=>({toString:()=> '9007199254740993'})),
 field('float','float',()=>-0),field('letter','char',()=> 'Ж'),
 field('boxed','java.lang.Object',()=>({getClass(){return {getName(){return 'java.lang.Short';}};},intValue(){return 7;}}))]);
const value=dto(type),first=p2Value(value,null);current=8;const second=p2Value(value,null);
result={first,second,scans,reads,fields:Object.keys(second.fields)};
""")
        self.assertEqual((r['result']['scans'],r['result']['reads']),(1,10))
        a,b=r['result']['first']['fields'],r['result']['second']['fields']
        self.assertEqual((a['number']['value'],b['number']['value']),(3,8))
        self.assertEqual(b['long'],{'type':'i64','decimal':'9007199254740993'})
        self.assertEqual(b['float']['bits'],'80000000')
        self.assertEqual(b['letter']['value'],ord('Ж'))
        self.assertEqual(b['boxed'],{'type':'i32','value':7,'boxed_class':'java.lang.Short'})
        self.assertEqual(r['result']['fields'],['number','long','float','letter','boxed'])

    def test_same_named_distinct_classes_have_distinct_cached_fields(self):
        r=self.dto_cache_js("const one=cls([field('a','int',()=>1)]),two=cls([field('b','int',()=>2)]);result=[p2Value(dto(one),null),p2Value(dto(two),null),scans];")
        self.assertEqual(list(r['result'][0]['fields']),['a'])
        self.assertEqual(list(r['result'][1]['fields']),['b'])
        self.assertEqual(r['result'][2],2)

    def test_reentrant_metadata_build_only_publishes_complete_first_plan(self):
        r=self.dto_cache_js(r"""
let nested=false;const f=field('a','int',()=>3),type=cls([f]);
type.getDeclaredFields=()=>{scans++;if(!nested){nested=true;p2DtoFields(type,'fixture');}return [f];};
const plan=p2DtoFields(type,'fixture');result={length:plan.length,scans,disposals,same:plan===p2DtoFields(type,'fixture'),frozen:Object.isFrozen(plan)};
""")
        self.assertEqual(r['result'],{'length':1,'scans':2,'disposals':2,'same':True,'frozen':True})

    def test_inherited_duplicate_field_still_fails_before_value_reads(self):
        r=self.dto_cache_js("const parent=cls([field('same','int',()=>1)]),child=cls([field('same','int',()=>2)],parent);try{p2Value(dto(child),null);}catch(e){result={error:String(e),reads};}")
        self.assertIn('duplicate field',r['result']['error'])
        self.assertEqual(r['result']['reads'],0)

    def test_guard_on_one_thread_does_not_skip_another(self):
        r=self.run_js("p2GuardEnter();setTid(2);p2Stack().push({id:'second'});p2HookClass('fixture.View','getter');result=f.implementation.call(receiver);")
        self.assertEqual((r['calls'],r['result']),(1,77))
        self.assertTrue(any(x['kind']=='getter' for x in r['messages']))

    def test_handler_observation_failure_never_replaces_original_operations(self):
        r=self.run_js("p2Handlers();p2Stack().push({id:'input'});p2Emit=()=>{throw Error('observer unavailable');};H.enqueueMessage.implementation.call(handler,null,msg,1000);H.dispatchMessage.implementation.call(handler,msg);H.removeCallbacks.implementation.call(handler,null);result=handlerCalls;")
        self.assertEqual(r['result'],{'enqueue':1,'dispatch':1,'cancel':1})

    def test_remove_null_callback_keeps_pending_plain_message(self):
        r=self.run_js("p2Handlers();p2Stack().push({id:'input'});H.enqueueMessage.implementation.call(handler,null,msg,1000);H.removeCallbacks.implementation.call(handler,null);result={pending:p2Pending.size,calls:handlerCalls};")
        self.assertEqual(r['result'],{'pending':1,'calls':{'enqueue':1,'cancel':1}})


if __name__=='__main__':
    unittest.main()
