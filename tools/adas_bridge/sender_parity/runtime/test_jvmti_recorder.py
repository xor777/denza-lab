"""Host protocol/runtime-contract checks; actual ART acceptance stays separate."""
from pathlib import Path
import json
import re
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
BACKEND = HERE/'jvmti-recorder.js'
INPUTS = HERE.parent/'phase2_inputs.js'


class JvmtiRecorderTest(unittest.TestCase):
    def test_selected_methods_are_not_replaced_and_live_exit_values_are_retained(self):
        program = r'''
const fs=require('fs'),vm=require('vm'),assert=require('assert');
let specs=[],rows=[],stack=[],observed=[];
global.Module={getExportByName:()=>({})};global.NativeFunction=function(){return()=>0;};
global.Java={perform(){},use:name=>classes[name]};global.abReport=()=>{};global.report=()=>{};
vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
const fake=(types,result)=>{const f={argumentTypes:types.map(className=>({className})),returnType:{className:result}};
 Object.defineProperty(f,'implementation',{set(){throw Error('selected method replacement attempted');}});return f;};
const classes={};
function klass(name,method,f){return classes[name]={class:{getDeclaredMethods:()=>[{getName:()=>method}]},[method]:{overloads:[f]}};}
const C=klass('k.h.a0.c.c.a.e0','f0',fake(['java.util.List','boolean'],'void'));
const G=klass('com.autosdk.bussiness.vehicle.PlatformUtils','realSpeed',fake([],'double'));
global.fixtureBackend={register:spec=>specs.push(spec)};
vm.runInThisContext(`p2Jvmti=fixtureBackend;p2J={Exception:{$new:()=>({getStackTrace:()=>[]})}};
 p2Stack=()=>stack;p2GuardDepth=()=>0;p2MaybeClose=()=>{};
 p2Ref=value=>({type:'object',id:'held',value});
 p2SafeValue=(value,type)=>type==='void'?{type:'null'}:
  type==='double'?{type:'f64',bits:'canonical',value:null}:JSON.parse(JSON.stringify({value}));
 p2Emit=(kind,fields)=>{const seq=rows.length;rows.push({kind,...(typeof fields==='function'?fields():fields)});return seq;};
 p2ObservedRead=(...args)=>observed.push(args);`);
p2HookClass('k.h.a0.c.c.a.e0','source');p2HookClass('com.autosdk.bussiness.vehicle.PlatformUtils','getter');
assert.strictEqual(specs.length,2);
const raw=[80],source={args:[raw,true],argBits:[null,null],receiver:{},error:null};
specs[0].onEnter(source);assert.strictEqual(stack.length,1);
const getter={args:[],argBits:[],receiver:{},error:null,result:NaN,resultBits:'7ff0000000000001'};
specs[1].onEnter(getter);specs[1].onExit(getter);
raw[0]=60;raw.push(40);specs[0].onExit(source);assert.strictEqual(stack.length,0);
const exit=rows.find(x=>x.kind==='call_exit'),read=rows.find(x=>x.kind==='getter');
assert.deepStrictEqual(exit.args_after[0].value,[60,40]);
assert.strictEqual(read.result.bits,'7ff0000000000001');
assert.strictEqual(observed.length,1);assert.strictEqual(observed[0][3].source_seq,rows.indexOf(read));
assert.strictEqual(observed[0][3].read_id,read.read_id);
console.log(JSON.stringify({selected:specs.length,mutated:exit.args_after[0].value,raw_bits:read.result.bits}));
'''
        result = subprocess.run(['node','-e',program,str(INPUTS)],capture_output=True,text=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertEqual(json.loads(result.stdout)['mutated'],[60,40])

    def test_closing_context_exists_only_during_actual_balanced_exit_observer(self):
        program = r'''const fs=require('fs'),assert=require('assert');
const text=fs.readFileSync(process.argv[1],'utf8'),start=text.indexOf('new NativeCallback(function(kind,index,jniHandle,javaThread,popped,raw)');
const begin=start+'new NativeCallback('.length,end=text.indexOf("},'void'",begin);
const zero={isNull:()=>true},handle={equals:other=>other===handle,toString:()=> 'jni'};
let callbacksInFlight=0,observed=null;const errors=[],activeCallbacks=new Map();
const Process={getCurrentThreadId:()=>42},Java={vm:{getEnv:()=>({handle})}},jni={occurred:()=>zero};
function reportError(method,error){errors.push(String(error));}function isObject(){return false;}function primitive(){return null;}function floatBits(){return null;}
const spec={method:'sender.stop()',resultType:'void',onExit(){
 assert.strictEqual(callbacksInFlight,1);assert.strictEqual(stacks.get('jni').length,0);
 observed={...activeCallbacks.get(42)};
 assert.strictEqual(observed.kind,'MethodExit');assert.strictEqual(observed.phase,'on_exit_observer');
 assert.strictEqual(observed.selected_frame_popped,true);
}};
const specs=[spec],frame={spec,refs:[],ignored:false},stacks=new Map([['jni',[frame]]]);
const callback=eval('('+text.slice(begin,end+1)+')');callback(1,0,handle,zero,0,zero);
assert.deepStrictEqual(errors,[]);assert.strictEqual(callbacksInFlight,0);assert.strictEqual(activeCallbacks.size,0);
assert.strictEqual(observed.phase,'on_exit_observer');console.log('balanced closing context proven');'''
        result = subprocess.run(['node','-e',program,str(BACKEND)],capture_output=True,text=True,timeout=10)
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn('balanced closing context proven',result.stdout)

    def test_all_mutable_cmodule_data_are_external_and_kept_alive(self):
        source = BACKEND.read_text()
        code = re.search(r'const code=String.raw`(.*?)`;',source,re.S).group(1)
        self.assertNotRegex(code,r'static\s+(?:GMutex|GHashTable|gint)\s')
        for declaration in ('extern GMutex lock;', 'extern GHashTable *methods;',
                            'extern GHashTable *guards;', 'extern gint enabled;'):
            self.assertIn(declaration,code)
        self.assertIn('new CModule(code,{...nativeStorage,selected_event:callback})',source)
        self.assertIn('_keepalive:{cm,callback,nativeStorage,callbacks}',source)
        self.assertNotIn('.implementation =',source)
        # The previous optional observer suffered the same read-only-data mistake.
        observer=(HERE/'interpreter-call-probe.js').read_text()
        self.assertNotRegex(observer,r'static\s+(?:volatile\s+)?(?:gint|guint|ProbeMethod|ProbeEvent)\s+\w+\s*(?:;|\[|=)')
        self.assertIn('new CModule(source,nativeStorage)',observer)
        self.assertIn('this._keepalive={module,nativeStorage}',observer)

    def test_actual_native_id_filter_guards_same_jni_before_javascript(self):
        source=re.search(r'const code=String.raw`(.*?)`;',BACKEND.read_text(),re.S).group(1)
        header=r'''
#ifndef TEST_GLIB
#define TEST_GLIB
#include <stdint.h>
#include <stdlib.h>
#include <pthread.h>
typedef void*gpointer;typedef const void*gconstpointer;typedef unsigned int guint;typedef int gint;
typedef pthread_mutex_t GMutex;
typedef struct {gpointer keys[4096],values[4096];int count;} GHashTable;
#define NULL ((void*)0)
#define GUINT_TO_POINTER(n) ((gpointer)(uintptr_t)(n))
#define GPOINTER_TO_UINT(p) ((guint)(uintptr_t)(p))
static void g_mutex_init(GMutex*m){pthread_mutex_init(m,NULL);}
static void g_mutex_lock(GMutex*m){pthread_mutex_lock(m);}
static void g_mutex_unlock(GMutex*m){pthread_mutex_unlock(m);}
static guint g_direct_hash(gconstpointer p){return (guint)(uintptr_t)p;}
static int g_direct_equal(gconstpointer a,gconstpointer b){return a==b;}
static GHashTable*g_hash_table_new_full(void*a,void*b,void*c,void*d){return calloc(1,sizeof(GHashTable));}
static gpointer g_hash_table_lookup(GHashTable*t,gpointer key){for(int i=0;i<t->count;i++)if(t->keys[i]==key)return t->values[i];return NULL;}
static void g_hash_table_insert(GHashTable*t,gpointer key,gpointer value){for(int i=0;i<t->count;i++)if(t->keys[i]==key){t->values[i]=value;return;}t->keys[t->count]=key;t->values[t->count++]=value;}
static void g_hash_table_remove(GHashTable*t,gpointer key){for(int i=0;i<t->count;i++)if(t->keys[i]==key){t->keys[i]=t->keys[--t->count];t->values[i]=t->values[t->count];return;}}
#endif
'''
        driver=r'''
GMutex lock;GHashTable*methods;GHashTable*guards;gint enabled;
#include <assert.h>
#include <stdio.h>
static int count,depth,exits;
void selected_event(guint kind,guint index,gpointer jni,gpointer thread,guint popped,const void*raw){
 count++;assert(index<2048);
 if(kind==1){const DenzaJValue*v=raw;assert(v->j==123456789);exits++;return;}
 if(!depth){depth=1;method_entry(NULL,jni,thread,(void*)2);method_entry(NULL,(void*)0x222,thread,(void*)2);depth=0;}
}
int main(void){probe_initialize();for(guint i=0;i<2048;i++)register_method((void*)(uintptr_t)(i+1),i);
 set_enabled(1);method_entry(NULL,(void*)0x111,NULL,(void*)1);assert(count==2);
 method_entry(NULL,(void*)0x111,NULL,(void*)99999);assert(count==2);
 DenzaJValue v;v.j=123456789;method_exit(NULL,(void*)0x111,NULL,(void*)1,0,v);assert(exits==1);
 set_enabled(0);method_entry(NULL,(void*)0x111,NULL,(void*)1);assert(count==3);
 puts("native ID/guard checks passed");return 0;}
'''
        with tempfile.TemporaryDirectory(prefix='jvmti-native-guard-') as name:
            root=Path(name);(root/'glib.h').write_text(header);(root/'probe.c').write_text(source+driver)
            build=subprocess.run(['cc','-std=c11','-pthread','-Wno-macro-redefined','-I',str(root),
                                  str(root/'probe.c'),'-o',str(root/'check')],capture_output=True,text=True,timeout=20)
            self.assertEqual(build.returncode,0,build.stderr)
            result=subprocess.run([str(root/'check')],capture_output=True,text=True,timeout=10)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertIn('native ID/guard checks passed',result.stdout)


if __name__=='__main__':
    unittest.main()
