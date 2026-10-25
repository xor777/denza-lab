"""Native buffer/API checks; synthetic ART contexts are never original evidence."""
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


PROBE = Path(__file__).with_name('interpreter-call-probe.js')
HOST = r'''
const fs=require('fs'), vm=require('vm'), assert=require('assert');
const nativePointer = value => ({toString:()=>String(value),isNull:()=>value==='0x0'});
global.ptr=nativePointer;
global.Memory={alloc:n=>({writeByteArray(){}})};
const old='_ZN3art11interpreter6DoCallILb0ELb1EEEbPNS_9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtPNS_6JValueE';
const modern='_ZN3art11interpreter6DoCallILb1EEEbPNS_9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtbPNS_6JValueE';
const stay='_ZN3art11interpreter29ShouldStayInSwitchInterpreterEPNS_9ArtMethodE';
const entries=[{name:old,address:ptr('0xa')},{name:modern,address:ptr('0xb')},
 {name:stay,address:ptr('0xd')},{name:'unmatched_interpreter6DoCall_suffix',address:ptr('0xc')}];
let attached=[], reports=[], detached=0, javaReads=0, buffer={}, counters={}, nativeRegistrations=[], scopeCalls=[];
global.Java={get api(){javaReads++;return {module:{name:'libart.so',path:'/system/lib64/libart.so',
 base:ptr('0x1000'),size:4096,enumerateExports:()=>entries,enumerateSymbols:()=>entries},
 isApiLevel34OrApexEquivalent:true};}};
global.CModule=function(source){assert(!source.includes('NativeCallback'));this.probe_on_enter=ptr('native-callback');
 for(const name of ['probe_register','probe_configure','probe_disable','probe_counter','probe_scope_begin','probe_scope_end','probe_event_number','probe_event_pointer'])this[name]=name;};
global.NativeFunction=function(symbol){return (...args)=>{
 if(symbol==='probe_register')nativeRegistrations.push(args);
 if(symbol==='probe_scope_begin'||symbol==='probe_scope_end')scopeCalls.push([symbol,...args]);
 if(symbol==='probe_counter')return counters[args.join(',')]||0;
 if(symbol==='probe_event_number')return (buffer[args[0]]||{}).numbers?.[args[1]]||0;
 if(symbol==='probe_event_pointer')return ptr((buffer[args[0]]||{}).pointers?.[args[1]]||'0x0');
};};
global.Interceptor={attach:(address,callbacks,data)=>{assert.notStrictEqual(typeof callbacks.onEnter,'function');
 attached.push({address:String(address),callbacks,data:String(data)});return {detach(){detached++;}};}};
global.Process={arch:'arm64',pointerSize:8,getCurrentThreadId:()=>123};
global.DebugSymbol={fromAddress:address=>'caller@'+address};
global.report=(kind,data)=>reports.push({kind,data});
vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
const probe=DenzaInterpreterCallProbe;
const overload=(a,b)=>({_r:{_m:{hookedMethodId:ptr(a),replacementMethodId:ptr(b)}}});
'''


class InterpreterCallProbeTest(unittest.TestCase):
    def run_node(self, body):
        result = subprocess.run(['node', '-e', HOST + body, str(PROBE)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_only_native_pointer_hook_and_snapshot_drains_published_slots(self):
        result = self.run_node(r'''
probe.register('caller',overload('0x1','0x2'));
probe.start({maxEvents:3,maxPerMethod:2});
assert.deepStrictEqual(attached.map(x=>x.address),['0xa','0xb','0xd']);
assert.strictEqual(nativeRegistrations.length,1);
assert.strictEqual(reports.length,0);
global.Java=new Proxy({}, {get(){throw Error('Java after native observer start');}});
probe.enterScope('original d.i');
assert.deepStrictEqual(scopeCalls[0],['probe_scope_begin',123,1]);
counters={'0,0,0':20,'1,0,0':4,'2,0,0':10,'3,0,0':10,'4,0,0':20,'5,0,0':1};
buffer[0]={numbers:{0:1,1:0,2:0,3:123,4:0,5:1,6:0},pointers:{0:'0x1',1:'0x999'}};
buffer[1]={numbers:{0:0},pointers:{0:'0x2',1:'0x999'}};
buffer[2]={numbers:{0:1,1:0,2:1,3:123,4:1,5:3,6:0},pointers:{0:'0x2',1:'0x999'}};
buffer[64]={numbers:{0:1,1:128,2:2,3:123,4:0,5:1,6:0,7:1},pointers:{0:'0x777',1:'0x999'}};
let snapshot=probe.snapshot('partial');
assert.strictEqual(snapshot.total_emitted,1);
assert.strictEqual(snapshot.scope_emitted,1);
assert.strictEqual(reports[1].data.original_or_replacement,'unknown');
assert.strictEqual(reports[1].data.scope_label,'original d.i');
buffer[1]={numbers:{0:1,1:0,2:1,3:123,4:1,5:2,6:0},pointers:{0:'0x2',1:'0x999'}};
snapshot=probe.endScope();
assert.strictEqual(snapshot.total_emitted,3);
assert.strictEqual(snapshot.total_matched,20);
assert.strictEqual(snapshot.methods[0].original,10);
assert.deepStrictEqual(reports.filter(x=>x.data.recording_mode==='registered-targets').map(x=>x.data.original_or_replacement),['original','replacement','replacement']);
assert.strictEqual(reports[0].data.caller_return_symbol,'caller@0x999');
assert.throws(()=>probe.register('late',overload('0x3','0x4')),/frozen/);
probe.stop();
assert.strictEqual(reports.length,4);
console.log(JSON.stringify({detached,javaReads}));
''')
        self.assertEqual(result, dict(detached=3, javaReads=1))

    def test_registration_and_start_fail_closed(self):
        result = self.run_node(r'''
assert.throws(()=>probe.register('missing',{}),/already-hooked/);
assert.throws(()=>probe.register('null',overload('0x0','0x2')),/nonnull/);
probe.register('method',overload('0x1','0x2'));
probe.register('method',overload('0x1','0x2'));
assert.throws(()=>probe.register('method',overload('0x1','0x3')),/identity changed/);
assert.throws(()=>probe.start({maxEvents:65}),/64/);
entries.splice(0,entries.length,{name:'wrong_interpreter6DoCall',address:ptr('0xc')});
assert.throws(()=>probe.start(),/no exact/);
assert.strictEqual(attached.length,0);
console.log(JSON.stringify({registered:probe.snapshot('refused').methods.length}));
''')
        self.assertEqual(result['registered'], 1)

    def test_actual_c_buffer_counts_threads_without_mutating_context(self):
        source = re.search(r'const source = String.raw`(.*?)`;', PROBE.read_text(), re.S).group(1)
        # This test compiles the actual CModule body; the host shim supplies only
        # Gum metadata getters and the same atomic-add primitive, with no Java/JS.
        glib = r'''
#ifndef TEST_GLIB_H
#define TEST_GLIB_H
#include <stdint.h>
#include <stddef.h>
typedef void *gpointer;typedef unsigned int guint;typedef int gint;
#define GPOINTER_TO_UINT(x) ((guint)(uintptr_t)(x))
static inline gint g_atomic_int_add(volatile gint *p,gint v){return __atomic_fetch_add(p,v,__ATOMIC_SEQ_CST);}
#endif
'''
        gum = r'''
#include <glib.h>
typedef struct {gpointer arg,caller,data;guint tid;} GumInvocationContext;
static inline gpointer gum_invocation_context_get_nth_argument(GumInvocationContext *c,guint n){return c->arg;}
static inline gpointer gum_invocation_context_get_return_address(GumInvocationContext *c){return c->caller;}
static inline gpointer gum_invocation_context_get_listener_function_data(GumInvocationContext *c){return c->data;}
static inline guint gum_invocation_context_get_thread_id(GumInvocationContext *c){return c->tid;}
'''
        driver = r'''
ProbeMethod methods[METHOD_CAPACITY];
ProbeEvent events[EVENT_CAPACITY * 2];
guint method_count,scope_thread,scope_id;
gint event_limit=EVENT_CAPACITY,method_limit=8;
volatile gint enabled,total_matched[2],claimed,scope_claimed,scope_enabled;
#include <assert.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
static void enter(uintptr_t method,guint tid,guint packed){
 GumInvocationContext c={(void*)method,(void*)0x999,(void*)(uintptr_t)packed,tid},before=c;
 probe_on_enter(&c);assert(memcmp(&before,&c,sizeof(c))==0);
}
static void *worker(void *arg){for(int i=0;i<50000;i++)enter(1,123+(guint)(uintptr_t)arg,0);return NULL;}
int main(void){
 probe_register(0,(void*)1,(void*)2);probe_register(1,(void*)3,(void*)4);probe_configure(3,2);
 for(int i=0;i<20;i++)enter(i%2?2:1,123,0);
 enter(3,123,1);enter(4,123,1);enter(0x777,123,0);
 assert(probe_counter(0,0,0)==22);assert(probe_counter(2,0,0)==10);assert(probe_counter(3,0,0)==10);
 assert(probe_counter(1,0,0)==4);assert(probe_event_number(0,0)==1);assert(probe_event_number(2,0)==1);assert(probe_event_number(3,0)==0);
 probe_scope_begin(123,1);enter(0x777,123,0);enter(0x888,124,0);enter(1,123,0);
 enter(1,123,(1<<16)|2);probe_scope_end();
 assert(probe_counter(5,0,0)==2);assert(probe_event_pointer(64,0)==(void*)0x777);assert(probe_event_number(64,7)==1);
 assert(probe_counter(0,0,1)==1);assert(probe_counter(5,0,0)==2);
 pthread_t threads[4];for(uintptr_t i=0;i<4;i++)assert(pthread_create(&threads[i],NULL,worker,(void*)i)==0);
 for(int i=0;i<4;i++)pthread_join(threads[i],NULL);
 assert(probe_counter(0,0,0)==200023);assert(probe_counter(2,0,0)==200011);
 probe_disable();enter(1,123,0);assert(probe_counter(0,0,0)==200023);
 puts("actual native buffer checks passed");return 0;
}
'''
        with tempfile.TemporaryDirectory(prefix='native-interpreter-probe-') as name:
            directory = Path(name)
            (directory/'gum').mkdir()
            (directory/'glib.h').write_text(glib)
            (directory/'gum/guminterceptor.h').write_text(gum)
            (directory/'probe.c').write_text(source + driver)
            build = subprocess.run(['cc', '-std=c11', '-Wall', '-Werror', '-Wno-unused-parameter',
                                    '-pthread', '-I', str(directory), str(directory/'probe.c'),
                                    '-o', str(directory/'check')], capture_output=True, text=True, timeout=20)
            self.assertEqual(build.returncode, 0, build.stderr)
            result = subprocess.run([str(directory/'check')], capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('actual native buffer checks passed', result.stdout)


if __name__ == '__main__':
    unittest.main()
