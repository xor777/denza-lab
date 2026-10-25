"""Host API checks; original virtual dispatch still needs the owned emulator."""
from pathlib import Path
import shutil
import subprocess
import unittest


SOURCE = Path(__file__).with_name('vehicle-fixture.js')
HARNESS = r'''
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const calls = [], reports = [], classes = new Map();
const RNAME = 'k.h.e0.a.r', FNAME = 'k.h.e0.b.f';
const SNAME = 'com.autosdk.bussiness.vehicle.BydAutoSpeed';
const LNAME = 'com.autosdk.bussiness.vehicle.proxy.BydAutoSpeedProxy$OnRealSpeedChangeListener';
function klass(name, fields=[], parent=null) {
  return {getName:()=>name, getDeclaredFields:()=>fields, getSuperclass:()=>parent,
    getDeclaredMethod:()=>({getModifiers:()=>1})};
}
function field(name, type) {
  return {getType:()=>klass(type), getName:()=>name, getModifiers:()=>17,
    setAccessible(){}, get:o=>o[name], set:(o,v)=>{o[name]=v;}};
}
function method(fn, type) {
  const overload = {returnType:{className:type}, call:(self,...args)=>fn.apply(self,args)};
  Object.defineProperty(overload,'implementation',{get:()=>null,
    set(){throw Error('Original .implementation changed');}});
  const call = (...args)=>fn(...args); call.overload=()=>overload; return call;
}
const parentClass = klass('com.autosdk.bussiness.vehicle.proxy.AbsAutoProxy',
  [field('listener','android.hardware.IBYDAutoListener')],klass('java.lang.Object'));
const proxy = {$h:'old', a:'preserved', listener:null, isReady:()=>false};
const R = {class:klass(RNAME,[field('a','java.lang.Object')],parentClass)};
const baseline={A:true,E:true,H:true,w:true,x:true,z:true,C:true,s:0,t:1,G:2};
for (const [name,value] of Object.entries(baseline)) R[name]=method(()=>value,typeof value==='boolean'?'boolean':'int');
const manager = {$h:'manager', rfield:proxy,
  i(){return this.rfield;}, h(){calls.push('original h');return this.rfield.t();},
  r(){calls.push('original r');return this.rfield.G()===2;}};
const managerClass = klass(FNAME,[field('rfield',RNAME)]);
const Noa={class:managerClass,m:()=>manager,h:method(()=>manager.h(),'int'),r:method(()=>manager.r(),'boolean')};
let real=0;
const listener={onRealSpeedChange(v){calls.push(['original listener',v]);real=v;}};
const speed={realListener:listener,realSpeed:()=>real,currentSpeed:()=>777};
const Speed={class:klass(SNAME,[field('realListener',LNAME)]),
  realSpeed:method(()=>real,'double'),currentSpeed:method(()=>777,'double')};
classes.set(RNAME,R);classes.set(FNAME,Noa);classes.set(SNAME,Speed);classes.set(LNAME,{});
classes.set('com.autosdk.bussiness.vehicle.BydAutoHub',{getInstance:()=>({getBydAutoSpeed:()=>speed})});
classes.set('java.lang.reflect.Modifier',{isStatic:n=>(n&8)!==0});
const context={Java:{
  use:n=>{if(!classes.has(n))classes.set(n,{});return classes.get(n);},
  retain:o=>o,cast:o=>o,array:(t,v)=>v,vm:{getEnv:()=>({isSameObject:(a,b)=>a===b})},
  registerClass(spec){
    assert.equal(spec.superClass,R);
    assert.deepEqual(Array.from(spec.methods.$init[0].argumentTypes),['k.h.e0.b.e','k.h.e0.f.a.a']);
    return {$new(a,b){
      const object={$h:'new',listener:null,isReady:()=>false,
        $super:{$init(x,y){assert.equal(x,manager);assert.equal(y,manager);calls.push('original super ctor');}}};
      spec.methods.$init[0].implementation.call(object,a,b);
      for(const [name,methods] of Object.entries(spec.methods))if(name!=='$init')
        object[name]=(...args)=>methods[0].implementation.apply(object,args);
      return object;
    }};
  }
}};
vm.createContext(context);
vm.runInContext(fs.readFileSync(process.argv[1],'utf8'),context);
const fixture=context.DenzaVehicleFixture;
const report=(kind,data)=>reports.push({kind,data});
'''


class VehicleFixtureTest(unittest.TestCase):
    def run_js(self, body):
        subprocess.run([shutil.which('node'), '-e', HARNESS + body, str(SOURCE)],
                       check=True, capture_output=True, text=True, timeout=10)

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_virtual_adapter_preserves_original_manager_and_values(self):
        self.run_js(r'''
const api=fixture.installNoa({report});
assert.equal(api.getterMethods.length,10);
assert.equal(manager.i().a,'preserved');
assert.notEqual(manager.i(),proxy);
assert.equal(api.validate().status,'PASS');
assert.deepEqual(calls,['original super ctor','original h','original r']);
assert.equal(reports.filter(r=>r.kind==='vehicle_noa_fixture_read').length,2);
assert(reports.filter(r=>r.kind==='vehicle_noa_fixture_read').every(r=>r.data.phase==='validation'));
const next=api.values();next.t=2;next.G=1;api.setValues(next);
assert.equal(manager.h(),2);assert.equal(manager.r(),false);
assert.equal(api.validate().status,'PASS');
assert.throws(()=>fixture.installNoa(),/already installed/);
const wrong=api.values();wrong.t=1.5;assert.throws(()=>api.setValues(wrong),/Invalid/);
const missing=api.values();delete missing.A;assert.throws(()=>api.setValues(missing),/ten/);
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_attached_proxy_is_rejected_before_replacement(self):
        self.run_js(r'''
proxy.isReady=()=>true;
assert.throws(()=>fixture.installNoa(),/already attached/);
assert.equal(manager.i(),proxy);
''')

    @unittest.skipUnless(shutil.which('node'), 'Node.js required')
    def test_speed_delivers_original_listener_and_leaves_current_speed(self):
        self.run_js(r'''
const proof=fixture.deliverVehicleSpeed(36,{report});
assert.equal(proof.status,'PASS');assert.equal(speed.realSpeed(),36);
assert.equal(speed.currentSpeed(),777);
assert.deepEqual(calls,[['original listener',36]]);
for(const invalid of [-1,282,NaN,Infinity])
  assert.throws(()=>fixture.deliverVehicleSpeed(invalid),/original/);
assert.equal(calls.length,1);
''')


if __name__ == '__main__':
    unittest.main()
