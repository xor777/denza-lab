/* ORIGINAL ART observation ledger. Prepend to stock_feed_observer.js.
 * Getters are called ONCE by their original caller, never to build a snapshot.
 * Handler enqueue/dispatch records distinct occurrences, including post(0).
 * The existing emulator transport stub remains the only fireEvent replacement.
 */
const P2_REQUIRED_HOOKS = [
  'k.h.a0.c.c.a.e0.onLocInfoUpdate', 'k.h.a0.c.c.a.e0.P',
  'k.h.a0.c.c.a.e0.l', 'k.h.a0.c.c.a.e0.v0', 'k.h.e0.c.g.G',
  'com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurSegIdx',
  'com.byd.map.signal.someip.pilot.bean.NaviInfoParam.getCurLinkIdx',
  'com.byd.map.signal.someip.pilot.bean.NaviInfoParam.setNaviInfoParam',
  'com.byd.map.signal.someip.pilot.bean.NaviInfoParam.reSetNaviInfo',
  'com.byd.map.signal.someip.pilot.bean.FontRoundBean.getFont3kmIdx',
  'com.byd.map.signal.someip.pilot.bean.FontRoundBean.setParam',
  'k.h.e0.b.d.o','k.h.e0.b.f.q','k.h.e0.b.f.r','k.h.e0.g.n.b',
  'k.e.c.c0.k','k.h.e0.c.j.f0.l','com.autosdk.bussiness.pilot.someip.SomeIpClient.startClientOrSubscribe',
  'com.autosdk.bussiness.pilot.someip.SomeIpClient.stopClient','com.autosdk.bussiness.pilot.someip.SomeIpClient.unSubscribe',
  'com.autonavi.gbl.common.path.option.PathInfo.getSegmentInfo',
  'com.autonavi.gbl.common.path.option.SegmentInfo.getLinkInfo',
  'com.autonavi.gbl.common.path.option.LinkInfo.getAdcode',
  'com.autonavi.gbl.common.path.option.LinkInfo.getLaneNum',
  'android.os.Handler.enqueueMessage', 'android.os.Handler.dispatchMessage',
  'android.os.Handler.removeCallbacksAndMessages',
  'android.os.SystemClock.elapsedRealtime', 'android.os.SystemClock.uptimeMillis',
  'java.lang.System.currentTimeMillis'
];
let p2Seq = 0, p2Call = 0, p2Post = 0, p2Object = 0, p2Read = 0;
const P2_TERMINAL_POLICY='quiet_no_due_or_overdue_pending_v1';
let p2Ended=false,p2Closing=false,p2CloseFailed=false,p2CloseRetryScheduled=false,p2StopRequested=false,p2EnqueueInFlight=0;
const p2RecorderDiagnostics=[];
const p2Guards = new Map();
const p2Stacks = new Map(), p2Pending = new Map(), p2ByMessage = new Map();
const p2Identities = new Map(), p2Hooked = new Set();
const p2ResourceNames = new Map();
const p2RetainedLightBars = new Map();
let p2J = null;
let p2Jvmti = null;
let p2FirstStatusProbe = false;
function p2EntryProbe(stage) {
  if (typeof DenzaQuickCodeProbe !== 'undefined')
    try { report('bridge_quick_code',DenzaQuickCodeProbe.snapshot(stage)); }
    catch(e) { report('bridge_quick_code_error',{stage,error:String(e)}); }
  if(typeof DenzaInterpreterCallProbe!=='undefined')
    try { report('bridge_interpreter_snapshot',DenzaInterpreterCallProbe.snapshot(stage)); }
    catch(e) { report('bridge_interpreter_error',{stage,error:String(e)}); }
}
function p2IdentityProbe(stage,cls,object,observer) {
  if(typeof DenzaClassIdentityProbe!=='undefined')try {
    const row=observer?DenzaClassIdentityProbe.observer(stage,object):DenzaClassIdentityProbe.sample(stage,cls,object);
    if(row!==null)report('bridge_class_identity',row);
  }catch(e){report('bridge_class_identity_error',{stage,error:String(e)});}
}
const p2Clock = new NativeFunction(Module.getExportByName('libc.so', 'clock_gettime'), 'int', ['int', 'pointer']);
function p2Nano() {
  const p = Memory.alloc(16);
  if (p2Clock(1, p) !== 0) throw new Error('clock_gettime');
  return p.readS64().toString() + p.add(8).readS64().toString().padStart(9, '0');
}
function p2ObserverUptimeMs() {
  // Frida Int64 has comparison but no division. Decimal truncation is exact
  // beyond Number's safe integer range and needs no optional BigInt runtime.
  const ns=p2Nano();
  return (ns.length>6?ns.slice(0,-6):'0').replace(/^0+(?=\d)/,'');
}
function p2Thread() {
  const t = p2J.Thread.currentThread();
  return {id: String(t.getId()), name: String(t.getName())};
}
function p2Stack() {
  const id = p2Thread().id;
  if (!p2Stacks.has(id)) p2Stacks.set(id, []);
  return p2Stacks.get(id);
}
function p2GuardDepth(){return p2Guards.get(p2Thread().id)||0;}
function p2GuardEnter(){const id=p2Thread().id;p2Guards.set(id,(p2Guards.get(id)||0)+1);return id;}
function p2GuardLeave(id){const depth=p2Guards.get(id)-1;if(depth)p2Guards.set(id,depth);else p2Guards.delete(id);}
// Error visibility must not depend on Java metadata, the regular reporter, or
// whether the terminal emit succeeded. A reserved sequence is never repaired.
function p2NativeDiagnostic(stage,error,fields){
  let message='<unprintable recorder error>',stack=null,nativeThread=null;
  try{message=String(error);}catch(_){}
  try{stack=error&&error.stack?String(error.stack):null;}catch(_){}
  try{nativeThread=Process.getCurrentThreadId();}catch(_){}
  const diagnostic=Object.assign({kind:'sender_phase2_recorder_diagnostic',
    schema:'denza-stock-sender-phase2-diagnostic-v1',stage,error:message,
    stack,next_seq:p2Seq,native_thread_id:nativeThread,
    ended:p2Ended,closing:p2Closing},fields||{});
  p2RecorderDiagnostics.push(diagnostic);
  try{send(diagnostic);}catch(_){/* Terminal inventory still retains the failure. */}
  return diagnostic;
}
function p2Emit(kind, fields) {
  if((p2Ended||p2Closing)&&kind!=='capture_end'&&kind!=='recorder_error')return;
  let seq=null,guard=null,stage='reserve';
  try {
    // Assign before ANY reporting/serialization. Frida delivery may interleave.
    seq=p2Seq++;stage='observer_clock';const mono_ns=p2Nano();
    stage='metadata_guard';guard=p2GuardEnter();
    try{
      stage='metadata';const stack=p2Stack(),top=stack[stack.length-1];
      const row=Object.assign({schema:'denza-stock-sender-phase2-v1',seq,mono_ns,
        thread:p2Thread(),kind,call_id:top?top.id:null,
        parent_call_id:stack.length>1?stack[stack.length-2].id:null},
        typeof fields==='function'?fields():fields||{});
      stage='report';report('sender_phase2',row);
      // Optional fixture collector consumes only this already-emitted plain DTO.
      // It must not call Java or change source arguments/results.
      if((kind==='call_enter'||kind==='call_exit')&&typeof p2ObservedCall==='function'){
        stage='observed_call';p2ObservedCall(row);
      }
    }finally{p2GuardLeave(guard);guard=null;}
    return seq;
  }catch(error){p2NativeDiagnostic('emit.'+stage,error,{source_kind:kind,reserved_seq:seq});throw error;}
}
function p2Error(method, error) {
  p2NativeDiagnostic('recorder_error',error,{method});
  try {p2Emit('recorder_error', {method, error: String(error), stack: error.stack || null});}
  catch (_) { /* A missing reserved sequence still makes the capture incomplete. */ }
}
function p2Observe(method,body){try{return body();}catch(e){try{p2Error(method,e);}catch(_){}return undefined;}}
function p2RetryClose(){
  if(p2Ended||p2CloseFailed||p2CloseRetryScheduled)return;
  p2CloseRetryScheduled=true;
  setTimeout(()=>{p2CloseRetryScheduled=false;if(p2Ended||p2CloseFailed)return;
    try{Java.perform(()=>p2MaybeClose());}catch(error){p2NativeDiagnostic('close.retry',error);}},50);
}
function p2MaybeClose(){
  if(p2Ended||p2Closing||p2CloseFailed||!p2StopRequested)return;
  if(p2EnqueueInFlight){p2RetryClose();return;}
  for(const stack of p2Stacks.values())if(stack.length){p2RetryClose();return;}
  let stage='observer_metadata';
  try{
    const observer=p2Jvmti===null?null:p2Jvmti.metadata();
    if(observer!==null){
      const current=observer.current_callback;
      if(observer.unclosed_frames.length||observer.active_callbacks.some(value=>
          value!==current&&(!current||value.native_thread_id!==current.native_thread_id))||
          current!==null&&(current.kind!=='MethodExit'||!current.selected_frame_popped)){
        p2RetryClose();return;
      }
    }
    stage='observer_uptime';
    // CLOCK_MONOTONIC is the uptime domain. This OBSERVER-only timestamp never
    // enters the source clock transcript or the replayed sender's state.
    const now=new Int64(p2ObserverUptimeMs()),pending_posts=Array.from(p2Pending.values());
    for(const pending of pending_posts)if(new Int64(pending.due_uptime_ms).compare(now)<=0){p2RetryClose();return;}
    // New callback entries are outside this quiet cutoff while its terminal DTO
    // is emitted. Existing accepted callback frames were checked above.
    p2Closing=true;stage='capture_end';
    p2Emit('capture_end',{pending_posts,terminal_policy:P2_TERMINAL_POLICY,
      observer_uptime_ms:now.toString(),
      reason:'observed quiet boundary with only strictly future original tasks',
      recorder_diagnostics:p2RecorderDiagnostics.slice(),
      ...(observer!==null?{observer}: {})});
    p2Ended=true;
  }catch(error){
    p2CloseFailed=true;p2NativeDiagnostic('close.'+stage,error);
    p2Error('capture_end.'+stage,error);
  }finally{p2Closing=false;}
}
function p2Ref(value) {
  if (value === null || value === undefined) return {type: 'null'};
  let c;
  try {c=Java.cast(value,p2J.Object).getClass();}
  catch(e) {if(!value.class)throw e;value=value.class;c=Java.cast(value,p2J.Object).getClass();}
  const klass = String(c.getName());
  // IdentityHashMap uses reference equality, not a possibly colliding identity hash.
  // Java calls release the JS lock: other original threads may resolve the
  // SAME object while this call is in flight. Reserve first, then atomically
  // install under synchronizedMap's monitor; IdentityHashMap keeps == keys.
  const candidate=String(++p2Object);
  const boxed=p2J.identities.putIfAbsent(value,p2J.String.$new(candidate));
  const id=boxed===null?candidate:String(boxed);
  let native_pointer = null;
  if (/\.(PathInfo|SegmentInfo|LinkInfo)$/.test(klass)) {
    let c = Java.cast(value,p2J.Object).getClass();
    while (c !== null) {
      try { const f = c.getDeclaredField('swigCPtr'); f.setAccessible(true); native_pointer = String(f.getLong(value)); break; }
      catch (_) { c = c.getSuperclass(); }
    }
  }
  return {type: 'object', id, class: klass, native_pointer};
}
function p2Bits(value, width) {
  const b = new ArrayBuffer(width), d = new DataView(b);
  if (width === 4) d.setFloat32(0, Number(value), false); else d.setFloat64(0, Number(value), false);
  return Array.from(new Uint8Array(b), x => x.toString(16).padStart(2, '0')).join('');
}
// Cache reflection descriptors by actual Class identity. Values are always read
// from the current original DTO; no field data enters this metadata cache.
const p2DtoFieldCache = new Map();
function p2DtoFields(actualClass, klass) {
  const key = p2Ref(actualClass).id, cached = p2DtoFieldCache.get(key);
  if (cached !== undefined) return cached.fields;
  const fields = [], names = new Set(); let owner = actualClass;
  const getters = {boolean:'getBoolean',byte:'getByte',short:'getShort',char:'getChar',
    int:'getInt',long:'getLong',float:'getFloat',double:'getDouble'};
  while (owner !== null && String(owner.getName()) !== 'java.lang.Object') {
    for (const field of owner.getDeclaredFields()) {
      if (p2J.Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
      const name = String(field.getName()), type = String(field.getType().getName());
      if (names.has(name)) throw new Error('duplicate field ' + klass + '.' + name);
      names.add(name); field.setAccessible(true);
      fields.push(Object.freeze({field:Java.retain(field), name, type, getter:getters[type] || 'get'}));
    }
    owner = owner.getSuperclass();
  }
  // Metadata JNI calls can release the JS lock; another original thread may
  // have published the same Class first. Never replace its retained descriptors.
  const plan = Object.freeze({actualClass:Java.retain(actualClass), fields:Object.freeze(fields)});
  const installed = p2DtoFieldCache.get(key);
  if (installed !== undefined) {
    for (const descriptor of fields) descriptor.field.$dispose();
    plan.actualClass.$dispose();
    return installed.fields;
  }
  p2DtoFieldCache.set(key, plan);
  return plan.fields;
}
function p2Value(value, declared, seen) {
  if (value === null || value === undefined) return {type: 'null'};
  if (declared === 'void') return {type: 'null'};
  if (declared === 'long' || (typeof Int64 !== 'undefined' && value instanceof Int64) ||
      (typeof UInt64 !== 'undefined' && value instanceof UInt64)) return {type: 'i64', decimal: String(value)};
  if (declared === 'float' || declared === 'double') {
    const numeric=declared==='float'?Math.fround(Number(value)):Number(value);
    return {type: declared === 'float' ? 'f32' : 'f64',bits: p2Bits(numeric, declared === 'float' ? 4 : 8),
      value:Number.isFinite(numeric)?numeric:null};
  }
  if (declared === 'boolean' || typeof value === 'boolean') return {type: 'boolean',
    value: typeof value === 'boolean' ? value : Java.cast(value,p2J.Boolean).booleanValue()};
  if (['byte', 'short', 'int', 'char'].includes(declared) || typeof value === 'number')
    return {type: 'i32', value: typeof value === 'string' ? value.charCodeAt(0) : Number(value)};
  if (typeof value === 'string') return {type: 'string', value};
  const object=Java.cast(value,p2J.Object), actualClass=object.getClass(), klass = String(actualClass.getName());
  if (klass === 'java.lang.String') return {type: 'string', value: String(value)};
  if (klass === 'java.lang.Boolean') return {type: 'boolean', value: Java.cast(value, p2J.Boolean).booleanValue()};
  if (['java.lang.Integer', 'java.lang.Short', 'java.lang.Byte'].includes(klass))
    return {type: 'i32', value: Java.cast(value, p2J.Number).intValue(), boxed_class: klass};
  if (klass === 'java.lang.Long') return {type: 'i64', decimal: String(value)};
  if (klass === 'java.math.BigInteger') return {type:'struct',class:klass,fields:{decimal:{type:'string',value:String(value)}}};
  if (klass === 'java.lang.Float' || klass === 'java.lang.Double')
    return p2Value(Java.cast(value, p2J.Number).doubleValue(), klass.endsWith('Float') ? 'float' : 'double');
  const ref = p2Ref(value);
  seen = seen || new Set();
  if (seen.has(ref.id)) return ref;
  seen.add(ref.id);
  if (klass === '[B') {
    const bytes=[];for(let i=0;i<p2J.Array.getLength(value);i++)bytes.push(p2J.Array.getByte(value,i));
    return {type:'bytes',base64:String(p2J.Base64.encodeToString.overload('[B','int').call(p2J.Base64,Java.array('byte',bytes),2))};
  }
  if (p2J.List.class.isInstance(value)) {
    const list = Java.cast(value, p2J.List), items = [];
    for (let i = 0; i < list.size(); i++) items.push(p2Value(list.get(i), null, seen));
    return {type: 'list', class: klass, id: ref.id, items};
  }
  if (p2J.Collection.class.isInstance(value)) {
    const it=Java.cast(value,p2J.Collection).iterator(),items=[];
    while(it.hasNext())items.push(p2Value(it.next(),null,seen));
    return {type:'list',class:klass,id:ref.id,items};
  }
  if (p2J.Map.class.isInstance(value)) {
    const it = Java.cast(value, p2J.Map).entrySet().iterator(), entries = [];
    while (it.hasNext()) { const e = Java.cast(it.next(), p2J.Entry); entries.push([p2Value(e.getKey(), null, seen), p2Value(e.getValue(), null, seen)]); }
    // Preserve Java map iteration order explicitly as DTO entries.
    return {type: 'struct', class: klass, id: ref.id, fields: {entries: {type: 'list', class: 'entries', items:
      entries.map(e => ({type: 'list', class: 'entry', items: e}))}}};
  }
  if (klass.startsWith('[')) {
    const items = [], component = String(object.getClass().getComponentType().getName());
    for (let i = 0; i < p2J.Array.getLength(value); i++) items.push(p2Value(p2J.Array.get(value, i), component, seen));
    return {type: 'list', class: klass, id: ref.id, items};
  }
  // Native views stay references. Their actual virtual returns appear as getters.
  if (/\.(PathInfo|SegmentInfo|LinkInfo|RouteCarResultData|POI|AlongWaySearchPoi|AlongWayPoiDeepInfo)$/.test(klass)) return ref;
  // SDK callback models are field DTOs: no getter is invoked to observe them.
  if (/^com\.autonavi\.gbl\..*\.model\./.test(klass) ||
      /^com\.autosdk\.bussiness\.(navi\.route\.model|common)\./.test(klass) ||
      klass === 'ts.car.someip.sdk.common.SomeIpData' || klass === 'com.autosdk.bussiness.vehicle.BydAutoSpeed$Speed' ||
      klass === 'com.byd.map.signal.someip.pilot.bean.TrafficLightState' ||
      klass === 'com.byd.map.signal.someip.pilot.bean.QueryAppointLanesInfoBean') {
    const fields = {};
    for (const descriptor of p2DtoFields(actualClass, klass))
      fields[descriptor.name] = p2Value(descriptor.field[descriptor.getter](value), descriptor.type, seen);
    return {type: 'struct', class: klass, id: ref.id, fields};
  }
  return ref;
}
function p2SafeValue(v, type) {
  const guard=p2GuardEnter();
  try { return p2Value(v, type); } finally { p2GuardLeave(guard); }
}
const p2ExternalE0 = new Set(['a','b','c','d','f','l','m','q','r','s','t','u','x','p','I0',
  'onChangeNaviPath','onCruiseStart','onCruiseStop','onCurrentRoadSpeed','onEnterTunnel',
  'onLeaveTunnel','onLocInfoUpdate','onNavStop','onNaviArrive','onNaviSimStart',
  'onNearEnterTunnel','onNearLeaveTunnel','onPlayTTS','onQueryAppointLanesInfo','onReroute',
  'onSelectMainPathStatus','onShowNaviWeather','onShowSameDirectionMixForkInfo',
  'onUpdateCruisePredictiveTunnelInfo','onUpdateCruiseRoadSpeed']);
function p2Role(cls, name) {
  if (cls === 'k.h.a0.c.c.a.e0' && p2ExternalE0.has(name)) return 'external_input';
  if (cls === 'k.h.e0.c.g' && (name.startsWith('on') || ['t','l','g','w'].includes(name))) return 'external_input';
  if (cls === 'k.h.a0.c.c.a.f0' && name === 't') return 'external_input';
  if (cls === 'k.h.e0.b.d$a'&&['onDnpSwitchState','onSupportTipIsland'].includes(name))return 'external_input';
  if (cls === 'k.h.e0.b.d$c'&&name==='onInitBindResult')return 'external_input';
  if (cls === 'k.h.e0.b.d' && ['i','j','k','l','m','o','q'].includes(name)) return 'external_input';
  if (cls === 'k.h.e0.d.f.m' && name==='a') return 'external_input';
  if (cls === 'k.h.e0.d.f.k' && name==='f') return 'external_input';
  return 'internal_processing';
}
function p2Gates(){
  const F=Java.use('k.h.e0.c.j.i0.b.a'),out={};
  for(const n of ['a','b','d','e','o','p']){const f=F['_'+n]!==undefined?F['_'+n]:F[n];out[n]=!!f.value;}
  return out;
}
// Shared phase2 payloads for the JVMTI backend; values remain entry-held
// original objects through MethodExit, so args_after observes actual mutations.
function p2JvmtiValue(value,type,bits) {
  const out=p2SafeValue(value,type);
  if(bits!==null&&bits!==undefined&&(type==='float'||type==='double'))out.bits=bits;
  return out;
}
function p2EnvironmentOperation(cls,name){
  if(cls==='k.e.c.c0'&&name==='k')return 'avp_support';
  if(cls==='com.autosdk.bussiness.pilot.someip.SomeIpClient')return {startClientOrSubscribe:'client_start',stopClient:'client_stop',unSubscribe:'client_unsubscribe'}[name]||null;
  return cls==='k.h.e0.c.j.f0'&&name==='l'?'route_signal_reset':null;
}
function p2RegisterJvmti(C,cls,name,f,mode) {
  const types=f.argumentTypes.map(t=>t.className),resultType=f.returnType.className;
  const base=cls+'.'+name,method=base+'('+types.join(',')+')';
  const operation=p2EnvironmentOperation(cls,name);
  p2Jvmti.register({method,overload:f,owner:C,types,resultType,mode,
    accept(){
      if(p2Ended||p2Closing)return false;
      if(p2GuardDepth()){if(mode==='source')p2Error(method,new Error('source invoked inside recorder metadata guard'));return false;}
      return mode==='source'||p2Stack().length>0;
    },
    onEnter(event){
      const stack=p2Stack(),args=event.args,receiver=event.receiver;
      if(mode==='getter'||mode==='clock'){
        event.p2read=String(++p2Read);
        p2Emit(mode+'_enter',()=>({read_id:event.p2read,method,
          receiver:mode==='clock'?{type:'null'}:p2Ref(receiver),
          args:args.map((a,i)=>p2JvmtiValue(a,types[i],event.argBits[i]))}));
        if(operation)p2Emit('environment_operation',()=>({operation,method,read_id:event.p2read,args:args.map((a,i)=>p2JvmtiValue(a,types[i],event.argBits[i]))}));return;
      }
      event.p2frame={id:String(++p2Call)};stack.push(event.p2frame);
      const role=p2Role(cls,name);
      if(cls==='k.h.a0.c.c.a.e0'&&name==='B0'&&args[0]!==null)
        p2Observe('retain.lightbar.input',()=>p2RetainedLightBars.set(p2Ref(receiver).id,Java.retain(args[0])));
      p2Emit('call_enter',()=>({method,role,receiver:p2Ref(receiver),
        args:args.map((a,i)=>p2JvmtiValue(a,types[i],event.argBits[i])),
        ...(role==='external_input'?{gate_snapshot:p2Gates()}:{}),
        ...(stack.length===1?{sites:Array.from(p2J.Exception.$new().getStackTrace()).slice(0,24).map(x=>String(x))}:{})}));
    },
    onExit(event){
      const args=event.args,receiver=event.receiver;
      if(mode==='getter'||mode==='clock'){
        const observedSeq=p2Emit(mode,()=>({read_id:event.p2read,method,error:event.error,
          receiver:mode==='clock'?{type:'null'}:p2Ref(receiver),
          args:args.map((a,i)=>p2JvmtiValue(a,types[i],event.argBits[i])),
          result:p2JvmtiValue(event.result,resultType,event.resultBits),
          ...(cls==='android.content.Context'&&name==='getString'?{resource_name:p2ResourceNames.get(args[0])||null}:{}),
          ...(mode==='clock'?{sites:Array.from(p2J.Exception.$new().getStackTrace()).slice(0,16).map(x=>String(x))}:{})}));
        if(mode==='getter'&&event.error===null&&typeof p2ObservedRead==='function')
          p2Observe('observedRead.'+method,()=>p2ObservedRead(method,args,event.result,{read_id:event.p2read,seq:observedSeq,source_seq:observedSeq}));
        return;
      }
      const stack=p2Stack(),role=p2Role(cls,name);
      try{p2Emit('call_exit',()=>({method,role,error:event.error,
        result:p2JvmtiValue(event.result,resultType,event.resultBits),
        args_after:args.map((a,i)=>p2JvmtiValue(a,types[i],event.argBits[i])),
        ...(cls==='k.h.a0.c.c.a.e0'&&name==='z'&&p2RetainedLightBars.has(p2Ref(receiver).id)?
          {retained_inputs_after:{lightbar:p2SafeValue(p2RetainedLightBars.get(p2Ref(receiver).id),null)}}:{})}));}
      finally{if(stack.pop()!==event.p2frame)p2Error(method,new Error('JVMTI source frame stack differs'));p2MaybeClose();}
    }
  });
}
function p2HookClass(cls, mode, filter) {
  const C = Java.use(cls), names = new Set();
  for (const m of C.class.getDeclaredMethods()) names.add(String(m.getName()));
  if (mode === 'source') names.add('$init');
  for (const name of names) {
    if (filter && !filter(name)) continue;
    if (!C[name] || !C[name].overloads) continue;
    for (const f of C[name].overloads) {
      const types = f.argumentTypes.map(x => x.className), resultType = f.returnType.className;
      const base = cls + '.' + name, method = base + '(' + types.join(',') + ')';
      if (p2Hooked.has(method)) continue;
      p2Hooked.add(method);
      try {
        if(p2Jvmti!==null){
          p2RegisterJvmti(C,cls,name,f,mode);
          p2Emit('hook',{method:base,overload:method,status:'installed',required:P2_REQUIRED_HOOKS.includes(base),backend:'jvmti-original-entry-exit'});
          continue;
        }
        f.implementation = function () {
          const args = Array.from(arguments), stack = p2Stack();
          if(p2Ended||p2Closing)return f.call.apply(f,[this].concat(args));
          const guardDepth=p2GuardDepth();
          if(cls==='k.h.a0.c.c.a.f0'&&name==='e'&&!p2FirstStatusProbe){p2FirstStatusProbe=true;p2EntryProbe('first original f0.e');}
          if(guardDepth || (mode!=='source'&&stack.length===0)) {
            if(mode==='source'&&guardDepth)p2Error(method,new Error('original sender invoked inside recorder guard depth '+guardDepth));
            return f.call.apply(f,[this].concat(args));
          }
          if (mode === 'getter' || mode === 'clock') {
            // The original read is executed once, then its actual return is observed.
            const read_id=String(++p2Read);
            try{p2Emit(mode+'_enter',()=>({read_id,method,receiver:mode==='clock'?{type:'null'}:p2Ref(this),
              args:args.map((a,i)=>p2SafeValue(a,types[i]))}));}catch(e){p2Error(method+'.enter',e);}
            const operation=p2EnvironmentOperation(cls,name);
            if(operation)p2Emit('environment_operation',()=>({operation,method,read_id,args:args.map((a,i)=>p2SafeValue(a,types[i]))}));
            let result, failure=null, observedSeq=null;
            try{result=f.call.apply(f,[this].concat(args));}catch(e){failure=e;}
            try { observedSeq=p2Emit(mode, () => ({read_id,method,error:failure===null?null:String(failure),receiver: mode === 'clock' ? {type:'null'} : p2Ref(this),
              args: args.map((a,i) => p2SafeValue(a, types[i])), result: p2SafeValue(result, resultType),
              ...(cls==='android.content.Context' && name==='getString'?{resource_name:p2ResourceNames.get(args[0])||null}:{}),
              ...(mode==='clock'?{sites:Array.from(p2J.Exception.$new().getStackTrace()).slice(0,16).map(x=>String(x))}:{})})); }
            catch(e) { p2Error(method, e); }
            if(failure!==null)throw failure;
            if(mode==='getter'&&observedSeq!==null&&typeof p2ObservedRead==='function')p2Observe('observedRead.'+method,()=>p2ObservedRead(method,args,result,{read_id,seq:observedSeq,source_seq:observedSeq}));
            return result;
          }
          const frame = {id: String(++p2Call)}, role = p2Role(cls, name);
          stack.push(frame);
          if(cls==='k.h.a0.c.c.a.e0'&&name==='B0'&&args[0]!==null)
            p2Observe('retain.lightbar.input',()=>p2RetainedLightBars.set(p2Ref(this).id,Java.retain(args[0])));
          let result, failure = null;
          try {
            try { p2Emit('call_enter', () => ({method, role, receiver: p2Ref(this),
              args: args.map((a,i) => p2SafeValue(a, types[i])),...(role==='external_input'?{gate_snapshot:p2Gates()}:{} ),
              ...(stack.length===1?{sites:Array.from(p2J.Exception.$new().getStackTrace()).slice(0,24).map(x=>String(x))}:{})})); } catch(e) { p2Error(method, e); }
            try {
              let diagnosticScope=false;
              if(cls==='k.h.e0.b.d'&&name==='i'&&typeof DenzaInterpreterCallProbe!=='undefined')
                p2Observe('diagnostic.scope.enter',()=>{DenzaInterpreterCallProbe.enterScope('original d.i body');diagnosticScope=true;});
              try {result = f.call.apply(f, [this].concat(args));}
              finally {if(diagnosticScope)p2Observe('diagnostic.scope.exit',()=>DenzaInterpreterCallProbe.endScope());}
              if(cls==='k.h.a0.c.c.a.e0'&&name==='A')p2IdentityProbe('original e0.A returned object',cls,result,false);
              if(cls==='k.h.e0.b.d'&&name==='i'){p2IdentityProbe('original d.i observer',cls,this,true);p2EntryProbe('original d.i completed');}
              if(cls==='k.h.e0.c.g'&&name==='l'){p2IdentityProbe('original g.l receiver',cls,this,false);p2EntryProbe('original g.l completed');}
              return result; }
            catch(e) { failure = String(e); throw e; }
          } finally {
            try { p2Emit('call_exit', () => ({method, role, result: p2SafeValue(result, resultType), error: failure,
              args_after:args.map((a,i)=>p2SafeValue(a,types[i])),
              ...(cls==='k.h.a0.c.c.a.e0'&&name==='z'&&p2RetainedLightBars.has(p2Ref(this).id)?
                {retained_inputs_after:{lightbar:p2SafeValue(p2RetainedLightBars.get(p2Ref(this).id),null)}}:{})})); }
            catch(e) { p2Error(method, e); }
            stack.pop();
            p2MaybeClose();
          }
        };
        if(typeof DenzaQuickCodeProbe!=='undefined'&&
          ['k.h.a0.c.c.a.e0.p','k.h.e0.c.g.$init','k.h.e0.c.g.t','k.h.e0.b.f.r',
           'k.h.e0.b.d.i','com.byd.map.signal.GaoDeSignalManager.setMapState',
           'com.byd.map.signal.GaoDeSignalManager.setRouteCarResultData'].includes(base))
          DenzaQuickCodeProbe.retain(method,f);
        if(typeof DenzaClassIdentityProbe!=='undefined'&&
          ['k.h.a0.c.c.a.e0.p','k.h.e0.c.g.t','k.h.e0.c.g.l'].includes(base))
          DenzaClassIdentityProbe.retain(cls,C,[[name,f]]);
        if(typeof DenzaInterpreterCallProbe!=='undefined'&&
          ['k.h.a0.c.c.a.e0.p','k.h.a0.c.c.a.e0.P','k.h.a0.c.c.a.e0.v0',
           'k.h.e0.c.g.$init','k.h.e0.c.g.t','k.h.e0.c.g.l','k.h.e0.c.g.G','k.h.e0.b.f.r',
           'k.h.e0.b.d.i','com.byd.map.signal.GaoDeSignalManager.setMapState',
           'com.byd.map.signal.GaoDeSignalManager.setRouteCarResultData',
           'com.autonavi.gbl.common.path.option.LinkInfo.getAdcode'].includes(base))
          DenzaInterpreterCallProbe.register(method,f);
        p2Emit('hook', {method: base, overload: method, status: 'installed', required: P2_REQUIRED_HOOKS.includes(base)});
      } catch(e) { p2Emit('hook', {method: base, overload: method, status: 'failed', required: true, error: String(e)}); }
    }
  }
}
function p2Queue(handler) {
  const name = String(handler.getLooper().getThread().getName());
  if (name === 'MmtSingle') return 'MMT';
  if (name === 'main') return 'MAIN';
  if (/some.?ip/i.test(name)) return 'SOMEIP';
  return 'OTHER';
}
function p2Message(handler, msg) {
  const callback = msg.getCallback(), token = msg.obj.value;
  return {handler: p2Ref(handler), runnable: p2Ref(callback), token: p2Ref(token), token_value: p2SafeValue(token,null),
    message: p2Ref(msg), queue_id: p2Queue(handler), what: msg.what.value};
}
function p2SenderRunnable(callback) {
  return callback !== null && /^(k\.h\.a0\.c\.c\.a\.|k\.h\.e0\.c\.|k\.h\.e0\.d\.f\.)/.test(String(Java.cast(callback,p2J.Object).getClass().getName()));
}
function p2JvmtiHandlers(H) {
  function register(name,types,enter,exit){
    const f=H[name].overload.apply(H[name],types),method='android.os.Handler.'+name+'('+types.join(',')+')';
    p2Jvmti.register({method,overload:f,owner:H,types,resultType:f.returnType.className,mode:'handler',
      sdkInterior:name!=='dispatchMessage',
      accept(){return !p2Ended&&!p2Closing&&!p2GuardDepth();},
      onEnter(event){enter(event);},onExit(event){if(exit)exit(event);}});
  }
  const Message=Java.use('android.os.Message');
  register('enqueueMessage',['android.os.MessageQueue','android.os.Message','long'],event=>{
    const msg=Java.cast(event.args[1],Message),selected=p2Stack().length>0||p2SenderRunnable(msg.getCallback());
    if(!selected)return;
    event.p2selected=true;p2EnqueueInFlight++;
    const post_id=String(++p2Post),data=p2Message(event.receiver,msg);
    event.p2post=post_id;event.p2message=data.message.id;
    const occurrence=Object.assign({post_id,due_uptime_ms:String(event.args[2]),insertion_seq:p2Seq},data);
    p2Pending.set(post_id,occurrence);p2ByMessage.set(data.message.id,post_id);
    p2Emit('timer_post_enter',occurrence);
  },event=>{
    if(!event.p2selected)return;
    try{if(!event.result){p2Pending.delete(event.p2post);p2ByMessage.delete(event.p2message);}
      p2Emit('timer_post_result',{post_id:event.p2post,result:!!event.result});}
    finally{p2EnqueueInFlight--;p2MaybeClose();}
  });
  p2Emit('hook',{method:'android.os.Handler.enqueueMessage',status:'installed',required:true,backend:'jvmti-original-entry-exit'});
  register('dispatchMessage',['android.os.Message'],event=>{
    const id=p2ByMessage.get(p2Ref(event.args[0]).id),data=id?p2Pending.get(id):null;
    if(!data)return;
    event.p2dispatch=data;p2Pending.delete(id);p2ByMessage.delete(data.message.id);
    p2Stack().push(event.p2frame={id:'timer:'+id});p2Emit('timer_execute_enter',data);
  },event=>{
    if(!event.p2dispatch)return;
    try{p2Emit('timer_execute_exit',event.p2dispatch);}
    finally{if(p2Stack().pop()!==event.p2frame)p2Error('Handler.dispatchMessage',new Error('JVMTI timer frame stack differs'));p2MaybeClose();}
  });
  p2Emit('hook',{method:'android.os.Handler.dispatchMessage',status:'installed',required:true,backend:'jvmti-original-entry-exit'});
  for(const name of ['removeCallbacks','removeCallbacksAndMessages','removeMessages'])for(const f of H[name].overloads){
    const types=f.argumentTypes.map(x=>x.className);
    register(name,types,event=>{
      const args=event.args,hid=p2Ref(event.receiver).id,matched=[];
      for(const [id,post]of p2Pending){
        if(post.handler.id!==hid)continue;let match=false;
        if(name==='removeCallbacks')match=args[0]!==null&&post.runnable.id===p2Ref(args[0]).id&&(args.length<2||args[1]===null||post.token.id===p2Ref(args[1]).id);
        if(name==='removeCallbacksAndMessages')match=args[0]===null||post.token.id===p2Ref(args[0]).id;
        if(name==='removeMessages')match=post.what===args[0]&&(args.length<2||args[1]===null||post.token.id===p2Ref(args[1]).id);
        if(match)matched.push(id);
      }
      if(p2Stack().length||matched.length)p2Emit('timer_cancel',{handler:p2Ref(event.receiver),method:'android.os.Handler.'+name+'('+types.join(',')+')',
        args:args.map((a,i)=>['int','long'].includes(types[i])?p2SafeValue(a,types[i]):p2Ref(a)),
        arg_values:args.map((a,i)=>p2SafeValue(a,types[i])),matched_post_ids:matched});
      for(const id of matched){const post=p2Pending.get(id);p2ByMessage.delete(post.message.id);p2Pending.delete(id);}
    });
    p2Emit('hook',{method:'android.os.Handler.'+name,status:'installed',required:true,backend:'jvmti-original-entry-exit'});
  }
}
function p2Handlers() {
  const H = Java.use('android.os.Handler');
  if(p2Jvmti!==null){p2JvmtiHandlers(H);return;}
  const enqueue = H.enqueueMessage.overload('android.os.MessageQueue','android.os.Message','long');
  enqueue.implementation = function(queue, msg, when) {
    const selected=p2Observe('enqueue.select',()=>!p2Ended && !p2GuardDepth() && (p2Stack().length>0 || p2SenderRunnable(msg.getCallback())));
    if(!selected)return enqueue.call(this,queue,msg,when);
    let data, post_id;p2EnqueueInFlight++;
    try {
      post_id = String(++p2Post); data = p2Message(this,msg);
      const occurrence = Object.assign({post_id,due_uptime_ms:String(when),insertion_seq:p2Seq},data);
      p2Pending.set(post_id,occurrence); p2ByMessage.set(data.message.id,post_id);
      p2Emit('timer_post_enter',occurrence);
    } catch(e) { p2Error('android.os.Handler.enqueueMessage',e); }
    let result;
    try { result = enqueue.call(this,queue,msg,when); return result; }
    finally {
      if (post_id) p2Observe('enqueue.result',()=>{if (!result) {p2Pending.delete(post_id);if(data)p2ByMessage.delete(data.message.id);}p2Emit('timer_post_result',{post_id,result:!!result});});
      p2EnqueueInFlight--;p2MaybeClose();
    }
  };
  p2Emit('hook',{method:'android.os.Handler.enqueueMessage',status:'installed',required:true});
  const dispatch = H.dispatchMessage.overload('android.os.Message');
  dispatch.implementation = function(msg) {
    if(p2Ended)return dispatch.call(this,msg);
    let id = null, data = null;
    try { id = p2ByMessage.get(p2Ref(msg).id); data = id ? p2Pending.get(id) : null; }
    catch(e) { p2Error('android.os.Handler.dispatchMessage',e); }
    if (!data) return dispatch.call(this,msg);
    p2Pending.delete(id); p2ByMessage.delete(data.message.id);
    const stack=p2Stack(); stack.push({id:'timer:'+id});
    p2Observe('dispatch.enter',()=>p2Emit('timer_execute_enter',data));
    try { return dispatch.call(this,msg); }
    finally { p2Observe('dispatch.exit',()=>p2Emit('timer_execute_exit',data)); stack.pop();p2MaybeClose(); }
  };
  p2Emit('hook',{method:'android.os.Handler.dispatchMessage',status:'installed',required:true});
  for (const name of ['removeCallbacks','removeCallbacksAndMessages','removeMessages']) for (const f of H[name].overloads) {
    const types=f.argumentTypes.map(x=>x.className);
    f.implementation=function() {
      const args=Array.from(arguments);
      if(p2Ended||p2GuardDepth()) return f.call.apply(f,[this].concat(args));
      p2Observe('android.os.Handler.'+name,()=>{
      const hid=p2Ref(this).id, matched=[];
      for (const [id,p] of p2Pending) {
        if(p.handler.id!==hid)continue;
        let match=false;
        if(name==='removeCallbacks') match=args[0]!==null && p.runnable.id===p2Ref(args[0]).id && (args.length<2 || args[1]===null || p.token.id===p2Ref(args[1]).id);
        if(name==='removeCallbacksAndMessages') match=args[0]===null || p.token.id===p2Ref(args[0]).id;
        if(name==='removeMessages') match=p.what===args[0] && (args.length<2 || args[1]===null || p.token.id===p2Ref(args[1]).id);
        if(match)matched.push(id);
      }
      if(p2Stack().length || matched.length) p2Emit('timer_cancel',{handler:p2Ref(this),method:'android.os.Handler.'+name+'('+types.join(',')+')',
        args:args.map((a,i)=>['int','long'].includes(types[i])?p2SafeValue(a,types[i]):p2Ref(a)),
        arg_values:args.map((a,i)=>p2SafeValue(a,types[i])),matched_post_ids:matched});
      for(const id of matched){const p=p2Pending.get(id);p2ByMessage.delete(p.message.id);p2Pending.delete(id);}
      });
      return f.call.apply(f,[this].concat(args));
    };
    p2Emit('hook',{method:'android.os.Handler.'+name,status:'installed',required:true});
  }
}
// The base fixture calls this before report(); no second transport implementation.
const p2OriginalAbReport = abReport;
abReport = function(kind, fields) {
  if (p2J && ['fire','client_send','start_service','stop_service'].includes(kind)) {
    try { p2Emit('output',{operation:kind,topic:String(fields.topic),payload_b64:fields.payload === undefined ? null : fields.payload}); }
    catch(e){p2Error('abReport.'+kind,e);}
  }
  return p2OriginalAbReport(kind,fields);
};
function p2InstallSources() {
  for(const cls of ['k.h.a0.c.c.a.e0','k.h.e0.c.g','k.h.e0.d.f.k','k.h.e0.d.f.l','k.h.e0.d.f.n',
    'k.h.e0.c.f','k.h.a0.c.c.a.f0']) {
    try{p2HookClass(cls,'source');}catch(e){p2Error(cls,e);}
  }
  for(const [cls,methods]of [
    ['com.byd.map.signal.someip.pilot.bean.NaviInfoParam',['getCurSegIdx','getCurLinkIdx','setNaviInfoParam','reSetNaviInfo']],
    ['com.byd.map.signal.someip.pilot.bean.FontRoundBean',['getFont3kmIdx','setParam']]]){
    try{p2HookClass(cls,'state',n=>methods.includes(n));}catch(e){p2Error('senderState.'+cls,e);}
  }
  try{p2HookClass('k.h.e0.b.d','source',n=>['i','j','k','l','m','o','q'].includes(n));}catch(e){p2Error('lifecycle',e);}
  try{p2HookClass('k.h.e0.b.d$a','source',n=>['onDnpSwitchState','onSupportTipIsland'].includes(n));p2HookClass('k.h.e0.b.d$c','source',n=>n==='onInitBindResult');}catch(e){p2Error('lifecycleListeners',e);}
  for(const cls of ['com.autonavi.gbl.common.path.option.PathInfo','com.autonavi.gbl.common.path.option.SegmentInfo',
    'com.autonavi.gbl.common.path.option.LinkInfo','com.autonavi.gbl.common.path.option.RouteOption',
    'com.autosdk.bussiness.data.MapDataController','com.autosdk.bussiness.navi.route.model.RouteCarResultData',
    'com.autonavi.gbl.data.MapDataService','com.autosdk.bussiness.vehicle.PlatformUtils','com.autosdk.bussiness.navi.NaviController',
    'com.autonavi.gbl.guide.GuideService','com.autosdk.bussiness.location.utils.LocationUtil',
    'com.autosdk.bussiness.location.LocationController','android.location.Location','com.autosdk.bussiness.noa.avp.AvpManager',
    'com.autosdk.bussiness.common.POI']) {
    try{p2HookClass(cls,'getter',n=>/^(get|has|is|realSpeed|queryAppoint|calcDistanceBetweenPoints)/.test(n));}catch(e){p2Error(cls,e);}
  }
  try{p2HookClass('k.h.e0.d.f.m','source',n=>['a','b','i'].includes(n));
    p2HookClass('k.h.c.z.t1','getter',n=>['a','c'].includes(n));
    p2HookClass('k.h.e0.b.f','getter',n=>['g','h','o','q','r'].includes(n));
    p2HookClass('k.h.e0.g.n','getter',n=>n==='b');
    p2HookClass('k.h.e0.c.j.f0','getter',n=>n==='l');
    p2HookClass('k.e.c.c0','getter',n=>n==='k');
    p2HookClass('com.autosdk.bussiness.pilot.someip.SomeIpClient','getter',n=>['startClientOrSubscribe','stopClient','unSubscribe'].includes(n));
  }catch(e){p2Error('environment',e);}
  try{
    const R=Java.use('com.byd.noa.R$string');
    for(const f of R.class.getDeclaredFields())if(p2J.Modifier.isStatic(f.getModifiers())&&String(f.getType().getName())==='int')
      p2ResourceNames.set(f.getInt(null),String(f.getName()));
    p2HookClass('android.content.Context','getter',n=>n==='getString');
  }catch(e){p2Error('resources',e);}
  try{p2HookClass('com.autosdk.bussiness.location.instrument.TunnelDetector','getter',n=>['setEnterTunnelDist','setLeaveTunnelDist'].includes(n));
    p2HookClass('com.autosdk.bussiness.pilot.someip.SomeIpServer','getter',n=>n==='sendSomeIpEvent');
  }catch(e){p2Error('environmentWrites',e);}
  // Observe the outer class without creating its singleton. The original SDK
  // must initialize the holder only after its native service library is ready.
  try{p2HookClass('com.byd.map.signal.GaoDeSignalManager','source',n=>['onNavStart','onNavStop','onNaviArrive','setMapState','setRouteCarResultData'].includes(n));}catch(e){p2Error('upstreamLifecycle',e);}
  p2Emit('ready',{required:P2_REQUIRED_HOOKS,observer_backend:p2Jvmti===null?'frida':'jvmti',terminal_policy:P2_TERMINAL_POLICY});
  if(p2Jvmti!==null)report('jvmti_recorder_registered',p2Jvmti.metadata());
  if(typeof DenzaBridgeSelection!=='undefined')report('bridge_selection',DenzaBridgeSelection);
  if(typeof DenzaInterpreterCallProbe!=='undefined')try{
    report('bridge_interpreter_inventory',DenzaInterpreterCallProbe.inventory());
    DenzaInterpreterCallProbe.start({maxEvents:64,maxPerMethod:8});
  }catch(e){report('bridge_interpreter_error',{stage:'start',error:String(e)});}
  p2EntryProbe('source hooks ready');
}
Java.perform(() => {
  // ART inlines enqueueMessage, simple SDK accessors, and callback bridges.
  // Deoptimization changes execution speed, never their code or return values;
  // without it the declared getter/queue hooks would silently be bypassed.
  Java.deoptimizeEverything();
  if(typeof DenzaJvmtiEventSmoke==='function')
    report('jvmti_event_smoke',DenzaJvmtiEventSmoke());
  if(typeof DenzaNestedHookCalibration==='function')
    report('bridge_nested_hook_calibration',DenzaNestedHookCalibration());
    p2J={Object:Java.use('java.lang.Object'),Thread:Java.use('java.lang.Thread'),String:Java.use('java.lang.String'),Number:Java.use('java.lang.Number'),
      Boolean:Java.use('java.lang.Boolean'),List:Java.use('java.util.List'),Collection:Java.use('java.util.Collection'),Map:Java.use('java.util.Map'),Entry:Java.use('java.util.Map$Entry'),
      Array:Java.use('java.lang.reflect.Array'),Modifier:Java.use('java.lang.reflect.Modifier'),Base64:Java.use('android.util.Base64'),
      Exception:Java.use('java.lang.Exception'),identities:Java.use('java.util.Collections').synchronizedMap(Java.use('java.util.IdentityHashMap').$new())};
  if(typeof P2_OBSERVER_BACKEND!=='undefined'&&P2_OBSERVER_BACKEND==='jvmti'){
    if(typeof DenzaJvmtiRecorder==='undefined')throw new Error('JVMTI recorder prefix missing');
    p2Jvmti=DenzaJvmtiRecorder.create({onError:p2Error});
  }
  try{p2HookClass('android.os.SystemClock','clock',n=>['uptimeMillis','elapsedRealtime','elapsedRealtimeNanos'].includes(n));
    p2HookClass('java.lang.System','clock',n=>n==='currentTimeMillis');p2Handlers();}catch(e){p2Error('runtime',e);}
  if(p2Jvmti!==null)report('jvmti_recorder_started',p2Jvmti.start());
  // PlatformUtils.<clinit> reads the application context. Installing its hooks
  // before this setter would initialize it early and poison the original app.
  // The setter is before the original sender is constructed; clocks/queues are
  // already observed. Do not replace the SDK context or its return values.
  let installed=false;
  const S=Java.use('com.autosdk.bussiness.manager.SDKManager'), set=S.setApplication.overload('android.app.Application');
  set.implementation=function(app) {
    const result=set.call(this,app);
    if(!installed && app!==null) {installed=true;try{p2InstallSources();}catch(e){p2Error('installSources',e);}}
    return result;
  };
  const A=Java.use('com.byd.automap.application.AutoApplication'), attach=A.attachBaseContext.overload('android.content.Context');
  attach.implementation=function(context) {
    const result=attach.call(this,context);
    if(!installed && S.getApplication()!==null){installed=true;try{p2InstallSources();}catch(e){p2Error('installSources.attach',e);}}
    return result;
  };
  if(!installed && S.getApplication()!==null){installed=true;try{p2InstallSources();}catch(e){p2Error('installSources.current',e);}}
});
