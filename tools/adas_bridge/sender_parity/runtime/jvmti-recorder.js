// Persistent original-method observation. No selected Java method body is replaced.
// The native ID hash and per-JNIEnv guards run BEFORE crossing into JavaScript.
globalThis.DenzaJvmtiRecorder = {
  create(config = {}) {
    function stage(name,fields={}){try{if(config.onStage)config.onStage(name,fields);else if(typeof report==='function')report('jvmti_recorder_stage',{stage:name,...fields});}catch(_){}}
    stage('create.enter');
    if (Process.arch !== 'arm64' || Process.pointerSize !== 8)
      throw new Error('JVMTI recorder requires the proven original ARM64 ABI');
    const P = Process.pointerSize, zero = ptr(0), specs = [], stacks = new Map();
    stage('before-current-env-and-object-class');
    const env = Java.vm.getEnv(), ObjectClass = Java.use('java.lang.Object');
    stage('before-plugin-environment');
    const cached = Java.api.jvmti;stage('after-plugin-environment',{available:cached!==null});
    if (cached === null) throw new Error('original ART TI plugin unavailable');
    stage('before-fresh-environment');
    const ti = Java.vm.tryGetEnvHandle(0x70010200);stage('after-fresh-environment',{environment:ti===null?null:String(ti)});
    if (ti === null || ti.equals(cached.handle)) throw new Error('fresh private ART TI environment required');
    const table = ti.readPointer(), callOptions = {exceptions: 'propagate'};
    function fn(index, ret, args) { return new NativeFunction(table.add((index - 1) * P).readPointer(), ret, args, callOptions); }
    const api = {
      mode: fn(2,'int',['pointer','int','int','pointer']),
      object: fn(21,'int',['pointer','pointer','int','int','pointer']),
      int: fn(22,'int',['pointer','pointer','int','int','pointer']),
      long: fn(23,'int',['pointer','pointer','int','int','pointer']),
      float: fn(24,'int',['pointer','pointer','int','int','pointer']),
      double: fn(25,'int',['pointer','pointer','int','int','pointer']),
      modifiers: fn(66,'int',['pointer','pointer','pointer']),
      maxLocals: fn(68,'int',['pointer','pointer','pointer']),
      argsSize: fn(69,'int',['pointer','pointer','pointer']),
      capabilities: fn(89,'int',['pointer','pointer']),
      callbacks: fn(122,'int',['pointer','pointer','int']),
      dispose: fn(127,'int',['pointer']), potential: fn(140,'int',['pointer','pointer']),
      add: fn(142,'int',['pointer','pointer']),
      instance: fn(155,'int',['pointer','pointer','int','pointer'])
    };
    const jniTable = env.handle.readPointer();
    function jniFn(index, ret, args) { return new NativeFunction(jniTable.add(index * P).readPointer(),ret,args,callOptions); }
    const jni = {occurred:jniFn(15,'pointer',['pointer']),clear:jniFn(17,'void',['pointer']),
      throw:jniFn(13,'int',['pointer','pointer']),delete:jniFn(23,'void',['pointer','pointer'])};
    let running = false, stopped = false, errors = 0, callbacksInFlight = 0;
    let suppressedSdkReads=0;const suppressedSdkMethods=new Map(),activeCallbacks=new Map();
    const setup = {};
    function alloc(n) { const p=Memory.alloc(n);p.writeByteArray(new Uint8Array(n));return p; }
    function check(label, code) { if(code!==0)throw new Error(label+' returned JVMTI/JNI error '+code); }
    function reportError(method,error) { errors++;try {if(config.onError)config.onError(method,error);}catch(_){} }
    stage('before-capabilities');
    const potential=alloc(16), requested=alloc(16), acquired=alloc(16);
    check('GetPotentialCapabilities',api.potential(ti,potential));requested.writeU32(0x03004000);
    check('AddCapabilities',api.add(ti,requested));check('GetCapabilities',api.capabilities(ti,acquired));
    if((acquired.readU32()&0x03004000)!==0x03004000)throw new Error('method entry/exit/local capabilities unavailable');
    stage('after-capabilities',{acquired:acquired.readU32()});
    setup.environment=String(ti);setup.fresh_environment=true;setup.capabilities=acquired.readU32();
    function object(local, refs, localOwned, klass) {
      if(local.isNull())return null;
      const view=Java.cast(local,klass||ObjectClass), kept=Java.retain(view);
      view.$dispose();refs.push(kept);
      if(localOwned)Java.vm.getEnv().deleteLocalRef(local);
      return kept;
    }
    function primitive(type, raw) {
      switch(type) {
        case 'void':return undefined;case 'boolean':return raw.readU8()!==0;
        case 'byte':return raw.readS8();case 'short':return raw.readS16();case 'char':return raw.readU16();
        case 'int':return raw.readS32();case 'long':return raw.readS64();
        case 'float':return raw.readFloat();case 'double':return raw.readDouble();
        default:return null;
      }
    }
    function floatBits(type,raw) {
      if(type!=='float'&&type!=='double')return null;
      const bytes=Array.from(new Uint8Array(raw.readByteArray(type==='float'?4:8))).reverse();
      return bytes.map(b=>b.toString(16).padStart(2,'0')).join('');
    }
    function width(type) { return type==='long'||type==='double'?2:1; }
    function isObject(type) {return !['void','boolean','byte','short','char','int','long','float','double'].includes(type);}
    function input(spec, javaThread, slot, type, refs) {
      const raw=alloc(8), kind=isObject(type)?'object':type==='long'?'long':type==='float'?'float':type==='double'?'double':'int';
      check('GetLocal.'+spec.method+' slot'+slot,api[kind](ti,javaThread,0,slot,raw));
      return {value:kind==='object'?object(raw.readPointer(),refs,true):primitive(type,raw),bits:floatBits(type,raw)};
    }
    const callback = new NativeCallback(function(kind,index,jniHandle,javaThread,popped,raw) {
      // Native C guard is already set for this exact JNIEnv/native thread.
      const key=String(jniHandle), spec=specs[index];
      let stack=stacks.get(key);if(stack===undefined){stack=[];stacks.set(key,stack);}
      let pending=zero, frame=null, currentEnv=null;
      callbacksInFlight++;
      const nativeTid=Process.getCurrentThreadId();
      const callbackContext={native_thread_id:nativeTid,kind:kind===0?'MethodEntry':'MethodExit',method:spec.method,
        phase:kind===0?'entry_observer':'exit_snapshot',selected_frame_popped:false};
      activeCallbacks.set(nativeTid,callbackContext);
      try {
        pending=jni.occurred(jniHandle);if(!pending.isNull())jni.clear(jniHandle);
        currentEnv=Java.vm.getEnv();if(!currentEnv.handle.equals(jniHandle))throw new Error('event JNIEnv differs from current native thread');
        if(kind===0) {
          frame={spec,method:spec.method,args:[],argBits:[],refs:[],ignored:false,receiver:null};
          const parent=Array.from(stack).reverse().find(value=>!value.ignored);
          stack.push(frame);
          // The public SDK wrapper is the environment read consumed by sender
          // source. Its getter/native/clock implementation remains ORIGINAL but
          // is not another sender input. A source callback interposes naturally
          // because it becomes the nearest nonignored selected frame.
          if((spec.mode==='getter'||spec.mode==='clock'||spec.sdkInterior)&&parent&&
              (parent.spec.mode==='getter'||parent.spec.mode==='clock')){
            frame.ignored=true;suppressedSdkReads++;
            const key=spec.method+' under '+parent.method;
            const item=suppressedSdkMethods.get(key)||{method:spec.method,outer_method:parent.method,count:0};
            item.count++;suppressedSdkMethods.set(key,item);return;
          }
          if(spec.accept && !spec.accept()){frame.ignored=true;return;}
          if(!spec.isStatic) {
            const out=alloc(P);check('GetLocalInstance.'+spec.method,api.instance(ti,javaThread,0,out));
            frame.receiver=object(out.readPointer(),frame.refs,true,spec.owner);
            if(frame.receiver===null)throw new Error('nonnull instance receiver unavailable for '+spec.method);
          } else frame.receiver=spec.mode==='clock'?null:spec.owner.class;
          if(spec.isNative&&spec.types.length!==0)throw new Error('opaque native argument frame for '+spec.method);
          let slot=spec.first+(spec.isStatic?0:1);
          for(const type of spec.types){const value=input(spec,javaThread,slot,type,frame.refs);frame.args.push(value.value);frame.argBits.push(value.bits);slot+=width(type);}
          if(spec.onEnter)spec.onEnter(frame);
        } else {
          frame=stack.pop();
          if(frame===undefined||frame.spec!==spec)throw new Error('unbalanced selected MethodExit '+spec.method);
          callbackContext.selected_frame_popped=true;
          if(frame.ignored)return;
          frame.error=popped?'original MethodExit popped_by_exception':null;
          frame.result=undefined;frame.resultBits=null;
          if(!popped) {
            frame.result=isObject(spec.resultType)?object(raw.readPointer(),frame.refs,false):primitive(spec.resultType,raw);
            frame.resultBits=floatBits(spec.resultType,raw);
          }
          if(spec.onExit){callbackContext.phase='on_exit_observer';try{spec.onExit(frame);}finally{callbackContext.phase='cleanup';}}
        }
      } catch(error) {if(frame){frame.failed=true;if(kind===0)frame.ignored=true;}reportError(spec?spec.method:'JVMTI callback',error);}
      finally {
        callbackContext.phase='cleanup';
        // Clear recorder-generated JNI exceptions, then restore the EXACT original
        // pending throwable. No callback error is thrown into the original body.
        try {
          const generated=jni.occurred(jniHandle);
          if(!generated.isNull()){jni.clear(jniHandle);jni.delete(jniHandle,generated);reportError(spec.method,new Error('recorder-generated pending JNI exception cleared'));}
          if(kind===1&&frame)for(const ref of frame.refs)try{ref.$dispose();}catch(error){reportError(spec.method,error);}
          if(!pending.isNull()){check('Restore original throwable',jni.throw(jniHandle,pending));jni.delete(jniHandle,pending);}
        } catch(error){reportError(spec?spec.method:'JVMTI exception restoration',error);}
        activeCallbacks.delete(nativeTid);callbacksInFlight--;
      }
    },'void',['uint','uint','pointer','pointer','uint','pointer']);
    const code=String.raw`
#include <glib.h>
#include <stdint.h>
typedef union {uint8_t z;int8_t b;uint16_t c;int16_t s;int32_t i;int64_t j;float f;double d;void*l;} DenzaJValue;
typedef char require_jvalue_size[(sizeof(DenzaJValue)==8)?1:-1];
extern void selected_event(guint kind,guint index,gpointer jni,gpointer thread,guint popped,const void*raw);
extern GMutex lock;
extern GHashTable *methods;
extern GHashTable *guards;
extern gint enabled;
void probe_initialize(void){g_mutex_init(&lock);methods=g_hash_table_new_full(g_direct_hash,g_direct_equal,NULL,NULL);guards=g_hash_table_new_full(g_direct_hash,g_direct_equal,NULL,NULL);}
void register_method(gpointer method,guint index){g_mutex_lock(&lock);g_hash_table_insert(methods,method,GUINT_TO_POINTER(index+1));g_mutex_unlock(&lock);}
void set_enabled(gint value){g_mutex_lock(&lock);enabled=value;g_mutex_unlock(&lock);}
static int begin(gpointer method,gpointer jni){
 gpointer value;g_mutex_lock(&lock);
 if(!enabled||g_hash_table_lookup(guards,jni)!=NULL){g_mutex_unlock(&lock);return -1;}
 value=g_hash_table_lookup(methods,method);
 if(value!=NULL)g_hash_table_insert(guards,jni,GUINT_TO_POINTER(1));
 g_mutex_unlock(&lock);return value==NULL?-1:(int)GPOINTER_TO_UINT(value)-1;
}
static void end(gpointer jni){g_mutex_lock(&lock);g_hash_table_remove(guards,jni);g_mutex_unlock(&lock);}
void method_entry(gpointer ti,gpointer jni,gpointer thread,gpointer method){int i=begin(method,jni);if(i>=0){selected_event(0,i,jni,thread,0,NULL);end(jni);}}
void method_exit(gpointer ti,gpointer jni,gpointer thread,gpointer method,uint8_t popped,DenzaJValue value){int i=begin(method,jni);if(i>=0){selected_event(1,i,jni,thread,popped,&value);end(jni);}}
`;
    // CModule code/data are read-only. Every mutable C global is imported from
    // separately allocated writable storage, retained for the backend lifetime.
    const nativeStorage={lock:alloc(64),methods:alloc(P),guards:alloc(P),enabled:alloc(4)};
    stage('before-cmodule');
    const cm=new CModule(code,{...nativeStorage,selected_event:callback});stage('after-cmodule');
    const fast={exceptions:'propagate',scheduling:'exclusive'};
    stage('before-native-initialize');
    new NativeFunction(cm.probe_initialize,'void',[],fast)();stage('after-native-initialize');
    const registerNative=new NativeFunction(cm.register_method,'void',['pointer','uint'],fast);
    const setEnabled=new NativeFunction(cm.set_enabled,'void',['int'],fast);
    // Explicit native initialization precedes event enablement and registration.
    const callbacks=alloc(17*P);callbacks.add(15*P).writePointer(cm.method_entry);callbacks.add(16*P).writePointer(cm.method_exit);
    stage('before-set-event-callbacks');
    check('SetEventCallbacks',api.callbacks(ti,callbacks,17*P));stage('after-set-event-callbacks');
    return {
      _keepalive:{cm,callback,nativeStorage,callbacks},
      register(spec) {
        if(stopped)throw new Error('JVMTI environment disposed');
        const id=spec.overload.handle;
        const old=specs.find(value=>value.id.equals(id));if(old){if(old.method!==spec.method)throw new Error('method-ID alias '+spec.method+' / '+old.method);return old;}
        const mods=alloc(4);check('GetMethodModifiers.'+spec.method,api.modifiers(ti,id,mods));
        spec.id=id;spec.isStatic=!!(mods.readS32()&8);spec.isNative=!!(mods.readS32()&256);spec.first=0;
        if(!spec.isNative){const max=alloc(4),args=alloc(4);check('GetMaxLocals.'+spec.method,api.maxLocals(ti,id,max));check('GetArgumentsSize.'+spec.method,api.argsSize(ti,id,args));
          const size=spec.types.reduce((n,type)=>n+width(type),spec.isStatic?0:1);
          if(args.readS32()!==size||max.readS32()<size)throw new Error('unproved DEX argument layout '+spec.method);
          spec.first=max.readS32()-size;spec.maxLocals=max.readS32();spec.argumentRegisters=size;}
        const index=specs.length;specs.push(spec);registerNative(id,index);return spec;
      },
      start() {stage('start.enter',{selected:specs.length});if(running||stopped)throw new Error('JVMTI recorder already started/stopped');setEnabled(1);
        stage('before-global-method-entry');check('Enable global MethodEntry',api.mode(ti,1,65,zero));stage('before-global-method-exit');check('Enable global MethodExit',api.mode(ti,1,66,zero));running=true;stage('after-global-method-events');return this.metadata();},
      metadata() {const nativeTid=Process.getCurrentThreadId(),active=Array.from(activeCallbacks.values(),value=>({...value}));return {current_native_thread_id:nativeTid,
        current_callback:active.find(value=>value.native_thread_id===nativeTid)||null,active_callbacks:active,backend:'JVMTI original MethodEntry/Exit',...setup,running,stopped,
        selected_methods:specs.length,callback_errors:errors,callbacks_in_flight:callbacksInFlight,
        sdk_read_boundary:'outermost environment getter/clock per nearest nonignored selected frame; SDK-interior Handler operations remain behind that seam; original source/observed Handler dispatch interposition reopens reads; direct opaque native arguments fail closed',
        suppressed_sdk_descendant_reads:suppressedSdkReads,
        suppressed_sdk_methods:Array.from(suppressedSdkMethods.values()),
        unclosed_frames:Array.from(stacks.entries()).filter(([,stack])=>stack.length).map(([jni,stack])=>({jni,methods:stack.map(frame=>frame.method)}))};},
      stop() {if(stopped)return this.metadata();check('Disable MethodEntry',api.mode(ti,0,65,zero));check('Disable MethodExit',api.mode(ti,0,66,zero));setEnabled(0);running=false;
        check('Clear callbacks',api.callbacks(ti,alloc(17*P),17*P));check('Dispose private environment',api.dispose(ti));stopped=true;return this.metadata();}
    };
  }
};
