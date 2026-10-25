// Invoke once inside Java.perform, before SDK source hooks. This observes only
// three put() calls on a fresh HashMap. It does not replace any Java method.
globalThis.DenzaJvmtiEventSmoke = function () {
  if (Process.arch !== 'arm64' || Process.pointerSize !== 8)
    return {kind: 'jvmti_event_smoke', status: 'UNSUPPORTED_ABI', architecture: Process.arch};
  const expected = {put: 3, putVal: 3, resize: 1, newNode: 2};
  const result = {kind: 'jvmti_event_smoke', status: 'ERROR', expected,
    native_thread_id: Process.getCurrentThreadId(), setup: {}, cleanup: [],
    methods: [], events: [], callback_errors: [], bodies_replaced: false};
  const P = Process.pointerSize, zero = ptr(0), env = Java.vm.getEnv();
  const C = Java.use('java.util.HashMap'), S = Java.use('java.lang.String');
  const T = Java.use('java.lang.Thread'), target = Java.retain(C.$new());
  const thread = T.currentThread();
  const keys = [S.$new('denza-jvmti-key-one'), S.$new('denza-jvmti-key-two')];
  const values = [S.$new('one'), S.$new('replacement'), S.$new('two')];
  const selected = [
    {name: 'put', types: ['java.lang.Object', 'java.lang.Object'], args: ['L', 'L']},
    {name: 'putVal', types: ['int', 'java.lang.Object', 'java.lang.Object', 'boolean', 'boolean'],
      args: ['I', 'L', 'L', 'Z', 'Z']},
    {name: 'resize', types: [], args: []},
    {name: 'newNode', types: ['int', 'java.lang.Object', 'java.lang.Object', 'java.util.HashMap$Node'],
      args: ['I', 'L', 'L', 'L']}
  ];
  const refs = [], stack = [], counts = {}, exitCounts = {};
  let fresh = null, cm = null, callback = null, enabledEntry = false, enabledExit = false;
  let active = null, api = null, capturing = false, nextId = 0;
  function zeroed(n) { const p = Memory.alloc(n); p.writeByteArray(new Uint8Array(n)); return p; }
  function bits(p) { return Array.from(new Uint8Array(p.readByteArray(16))); }
  function caps(p) { const word = p.readU32(); return {raw: bits(p),
    local_variables: !!(word & 0x00004000), method_entry: !!(word & 0x01000000),
    method_exit: !!(word & 0x02000000)}; }
  function check(label, code) {
    result.setup[label] = code;
    if (code !== 0) throw new Error(label + ' returned JVMTI/JNI error ' + code);
  }
  function pin(local, label) {
    if (local.isNull()) return null;
    for (const item of refs) if (env.isSameObject(item.handle, local)) return item.label;
    const item = {label: label || 'object-' + (refs.length + 1), handle: env.newGlobalRef(local)};
    refs.push(item); return item.label;
  }
  function local(method, javaThread, slot, type) {
    const out = zeroed(8), object = type === 'L';
    const code = (object ? api.localObject : api.localInt)(fresh, javaThread, 0, slot, out);
    if (code !== 0) return {slot, type, error: code};
    if (!object) return {slot, type, value: out.readS32()};
    const ref = out.readPointer();
    try { return {slot, type, identity: pin(ref)}; }
    finally { if (!ref.isNull()) env.deleteLocalRef(ref); }
  }
  try {
    // This getter uses the pinned bridge's native EnsurePluginLoaded path.
    const cached = Java.api.jvmti;
    result.setup.bridge_jvmti_present = cached !== null;
    if (cached === null) throw new Error('ART JVMTI plugin environment unavailable');
    fresh = Java.vm.tryGetEnvHandle(0x70010200);
    if (fresh === null) throw new Error('fresh private kArtTiVersion GetEnv unavailable');
    result.setup.cached_environment = String(cached.handle);
    result.setup.environment = String(fresh);
    result.setup.fresh_environment = !fresh.equals(cached.handle);
    if (!result.setup.fresh_environment) throw new Error('GetEnv reused cached environment');
    const table = fresh.readPointer();
    function fn(index, ret, args) { return new NativeFunction(table.add((index - 1) * P).readPointer(),
      ret, args, {exceptions: 'propagate'}); }
    api = {
      mode: fn(2, 'int', ['pointer', 'int', 'int', 'pointer']),
      localObject: fn(21, 'int', ['pointer', 'pointer', 'int', 'int', 'pointer']),
      localInt: fn(22, 'int', ['pointer', 'pointer', 'int', 'int', 'pointer']),
      maxLocals: fn(68, 'int', ['pointer', 'pointer', 'pointer']),
      argsSize: fn(69, 'int', ['pointer', 'pointer', 'pointer']),
      capabilities: fn(89, 'int', ['pointer', 'pointer']),
      callbacks: fn(122, 'int', ['pointer', 'pointer', 'int']),
      dispose: fn(127, 'int', ['pointer']),
      potential: fn(140, 'int', ['pointer', 'pointer']),
      add: fn(142, 'int', ['pointer', 'pointer']),
      instance: fn(155, 'int', ['pointer', 'pointer', 'int', 'pointer'])
    };
    const potential = zeroed(16), requested = zeroed(16), acquired = zeroed(16);
    check('GetPotentialCapabilities', api.potential(fresh, potential));
    result.potential_capabilities = caps(potential);
    requested.writeU32(0x03004000);
    result.requested_capabilities = caps(requested);
    check('AddCapabilities', api.add(fresh, requested));
    check('GetCapabilities', api.capabilities(fresh, acquired));
    result.acquired_capabilities = caps(acquired);
    if (!result.acquired_capabilities.local_variables || !result.acquired_capabilities.method_entry ||
        !result.acquired_capabilities.method_exit) throw new Error('requested capabilities absent after AddCapabilities');
    pin(target.$h, 'target');
    keys.forEach((v, i) => pin(v.$h, 'key-' + i));
    values.forEach((v, i) => pin(v.$h, 'value-' + i));
    const methodPointers = zeroed(selected.length * P);
    selected.forEach((m, i) => {
      m.overload = C[m.name].overload.apply(C[m.name], m.types);
      if (m.overload.implementation !== null) throw new Error('preexisting Java hook HashMap.' + m.name);
      m.id = m.overload.handle;
      methodPointers.add(i * P).writePointer(m.id);
      const max = zeroed(4), args = zeroed(4);
      check('GetMaxLocals.' + m.name, api.maxLocals(fresh, m.id, max));
      check('GetArgumentsSize.' + m.name, api.argsSize(fresh, m.id, args));
      m.first = max.readS32() - args.readS32();
      if (m.first < 0 || args.readS32() !== m.args.length + 1)
        throw new Error('unexpected DEX input register layout HashMap.' + m.name);
      result.methods.push({name: m.name, method: String(m.id), max_locals: max.readS32(),
        argument_registers: args.readS32(), first_input_register: m.first});
      counts[m.name] = 0; exitCounts[m.name] = 0;
    });
    callback = new NativeCallback(function (kind, index, jni, javaThread, popped, raw) {
      if (capturing) return;
      capturing = true;
      try {
        if (Process.getCurrentThreadId() !== result.native_thread_id)
          throw new Error('callback on unexpected native thread');
        if (!env.handle.equals(jni)) throw new Error('callback JNIEnv differs from current thread');
        const m = selected[index];
        if (kind === 0) {
          const instance = zeroed(P), code = api.instance(fresh, javaThread, 0, instance);
          if (code !== 0) throw new Error('GetLocalInstance.' + m.name + ': ' + code);
          const receiver = instance.readPointer();
          let isTarget;
          try { isTarget = env.isSameObject(receiver, target.$h); }
          finally { if (!receiver.isNull()) env.deleteLocalRef(receiver); }
          if (!isTarget) { stack.push(null); return; }
          const id = ++nextId, parent = stack.length ? stack[stack.length - 1] : null;
          const args = m.args.map((type, a) => local(m, javaThread, m.first + 1 + a, type));
          const event = {event: 'enter', id, parent, method: m.name, receiver: 'target', args};
          result.events.push(event); counts[m.name]++; stack.push(id);
        } else {
          const id = stack.pop();
          if (id === null) return;
          if (id === undefined) throw new Error('unbalanced MethodExit.' + m.name);
          const ref = raw.readPointer();
          result.events.push({event: 'exit', id, method: m.name, popped_by_exception: !!popped,
            raw_jvalue_le: Array.from(new Uint8Array(raw.readByteArray(8))),
            raw_jvalue_u64: raw.readU64().toString(), identity: popped ? null : pin(ref)});
          exitCounts[m.name]++;
        }
      } catch (error) { result.callback_errors.push(String(error)); }
      finally { capturing = false; }
    }, 'void', ['uint', 'uint', 'pointer', 'pointer', 'uint', 'pointer']);
    active = zeroed(4);
    // The C ABI, rather than a libffi float/integer guess, receives jvalue by
    // value. The JS callback receives a pointer to its exact eight bytes.
    const code = `
#include <glib.h>
#include <stdint.h>
typedef union { uint8_t z; int8_t b; uint16_t c; int16_t s; int32_t i;
  int64_t j; float f; double d; void * l; } DenzaJValue;
typedef char require_eight_byte_jvalue[(sizeof(DenzaJValue) == 8) ? 1 : -1];
extern gint active;
extern gpointer selected_methods[4];
extern void selected_event(guint kind, guint index, gpointer jni,
  gpointer thread, guint popped, const void * raw);
static int selected(gpointer method) {
  int i;
  if (g_atomic_int_add(&active, 0) != 1) return -1;
  for (i = 0; i != 4; i++) if (selected_methods[i] == method) return i;
  return -1;
}
void method_entry(gpointer ti, gpointer jni, gpointer thread, gpointer method) {
  int i = selected(method);
  if (i >= 0) selected_event(0, i, jni, thread, 0, NULL);
}
void method_exit(gpointer ti, gpointer jni, gpointer thread, gpointer method,
                 uint8_t popped, DenzaJValue value) {
  int i = selected(method);
  if (i >= 0) selected_event(1, i, jni, thread, popped, &value);
}
`;
    cm = new CModule(code, {active, selected_methods: methodPointers, selected_event: callback});
    const callbacks = zeroed(17 * P);
    callbacks.add(15 * P).writePointer(cm.method_entry);
    callbacks.add(16 * P).writePointer(cm.method_exit);
    check('SetEventCallbacks', api.callbacks(fresh, callbacks, 17 * P));
    check('EnableMethodEntry', api.mode(fresh, 1, 65, thread.$h)); enabledEntry = true;
    check('EnableMethodExit', api.mode(fresh, 1, 66, thread.$h)); enabledExit = true;
    active.writeS32(1);
    const returned = [];
    try {
      returned.push(target.put(keys[0], values[0]));
      returned.push(target.put(keys[0], values[1]));
      returned.push(target.put(keys[1], values[2]));
    } finally { active.writeS32(0); }
    result.returned = returned.map(v => v === null ? null : String(v));
    result.size = target.size();
    result.final_values = keys.map(k => String(target.get(k)));
    result.counts = counts; result.exit_counts = exitCounts; result.unclosed_frames = stack.length;
    const expectedCounts = Object.keys(expected).every(name => counts[name] === expected[name] &&
      exitCounts[name] === expected[name]);
    const allArgsRead = result.events.filter(e => e.event === 'enter')
      .every(e => e.args.every(a => a.error === undefined));
    const putEntries = result.events.filter(e => e.event === 'enter' && e.method === 'put');
    const inputs = putEntries.map(e => e.args.map(a => a.identity).join(','));
    const sameInputs = inputs.join('|') === 'key-0,value-0|key-0,value-1|key-1,value-2';
    const putExits = result.events.filter(e => e.event === 'exit' && e.method === 'put');
    const sameReturns = putExits.length === 3 && putExits[0].identity === null &&
      putExits[1].identity === 'value-0' && putExits[2].identity === null;
    const semantic = result.size === 2 && result.returned.join('|') === '|one|' &&
      result.final_values.join(',') === 'replacement,two';
    const nested = result.events.filter(e => e.event === 'enter' && e.method !== 'put')
      .every(e => e.parent !== null);
    result.checks = {expected_counts: expectedCounts, all_arguments_read: allArgsRead,
      original_inputs: sameInputs, raw_exit_identities: sameReturns, semantics: semantic,
      nested, balanced: stack.length === 0, callbacks_error_free: result.callback_errors.length === 0};
    result.status = Object.values(result.checks).every(Boolean) ? 'PASS' : 'INCOMPLETE';
  } catch (error) { result.error = String(error); }
  finally {
    if (active !== null) active.writeS32(0);
    if (fresh !== null && api !== null) {
      function cleanup(operation, action) {
        try { result.cleanup.push({operation, error: action()}); }
        catch (error) { result.cleanup.push({operation, exception: String(error)}); }
      }
      if (enabledEntry) cleanup('DisableMethodEntry', () => api.mode(fresh, 0, 65, thread.$h));
      if (enabledExit) cleanup('DisableMethodExit', () => api.mode(fresh, 0, 66, thread.$h));
      cleanup('ClearEventCallbacks', () => api.callbacks(fresh, zeroed(17 * P), 17 * P));
      cleanup('DisposeEnvironment', () => api.dispose(fresh));
    }
    for (const item of refs) env.deleteGlobalRef(item.handle);
    target.$dispose();
    if (result.cleanup.some(e => e.error !== 0 || e.exception !== undefined)) result.status = 'ERROR';
    // Keep CModule/NativeCallback live through callback removal and env disposal.
    void cm; void callback;
  }
  return result;
};
