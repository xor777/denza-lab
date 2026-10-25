// Optional read-only ART DoCall observation for the pinned Frida16/17 bridges.
// register() retains ALREADY-hooked overloads; start() freezes the native table.
// Native onEnter only reads arguments and writes this probe's atomic counters /
// bounded buffer. It NEVER enters JavaScript, calls Java, or writes an ArtMethod.
// snapshot()/stop() drain and report outside ART callbacks. Loading does nothing.
globalThis.DenzaInterpreterCallProbe = (() => {
  const legacy = /^_ZN3art11interpreter6DoCallILb[0-1]ELb[0-1]EEEbPNS_9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtPNS_6JValueE$/;
  const api34 = /^_ZN3art11interpreter6DoCallILb[0-1]EEEbPNS_9ArtMethodEPNS_6ThreadERNS_11ShadowFrameEPKNS_11InstructionEtbPNS_6JValueE$/;
  const shouldStay = '_ZN3art11interpreter29ShouldStayInSwitchInterpreterEPNS_9ArtMethodE';
  const methods = new Map(), listeners = [], symbolsByIndex = [];
  const scopes = [];
  const capacity = 64, methodCapacity = 128;
  let discovered = null, running = false, started = false, native = null, module = null, nativeStorage = null;
  let maxEvents = 64, maxPerMethod = 8, nextFlush = 0, nextScopeFlush = 0, reportErrors = 0;
  let scopeActive = false;

  // Gum's public getters supply method / caller / thread metadata. The listener
  // data is just a stable native integer symbol index. Atomic ready publishes
  // all preceding event fields before a snapshot may read that slot.
  const source = String.raw`
#include <gum/guminterceptor.h>
#include <glib.h>
#define METHOD_CAPACITY 128
#define EVENT_CAPACITY 64

typedef struct {
  gpointer original;
  gpointer replacement;
  volatile gint original_hits[2];
  volatile gint replacement_hits[2];
  volatile gint matched[2];
  volatile gint candidates[2];
} ProbeMethod;
typedef struct {
  volatile gint ready;
  guint method_index;
  guint which;
  guint thread_id;
  guint symbol_index;
  guint matched_ordinal;
  guint entry_kind;
  guint scope_id;
  gpointer method;
  gpointer caller;
} ProbeEvent;
typedef char require_method_storage[(sizeof(ProbeMethod) <= 64) ? 1 : -1];
typedef char require_event_storage[(sizeof(ProbeEvent) <= 64) ? 1 : -1];
extern ProbeMethod methods[METHOD_CAPACITY];
extern ProbeEvent events[EVENT_CAPACITY * 2];
extern guint method_count;
extern gint event_limit;
extern gint method_limit;
extern volatile gint enabled;
extern volatile gint total_matched[2];
extern volatile gint claimed;
extern volatile gint scope_claimed;
extern volatile gint scope_enabled;
extern guint scope_thread;
extern guint scope_id;

// Pinned CModule glib.h exposes atomic add, but not atomic get/set.
static gint probe_atomic_read(volatile gint *value) { return g_atomic_int_add(value, 0); }
void probe_register(guint index, gpointer original, gpointer replacement) {
  if (index >= METHOD_CAPACITY) return;
  methods[index].original = original;
  methods[index].replacement = replacement;
  if (index >= method_count) method_count = index + 1;
}
void probe_configure(gint events_limit, gint per_method) {
  event_limit = events_limit;
  method_limit = per_method;
  g_atomic_int_add(&enabled, 1);
}
void probe_disable(void) { if (probe_atomic_read(&enabled)) g_atomic_int_add(&enabled, -1); }
void probe_scope_begin(guint thread_id, guint id) {
  scope_thread = thread_id;
  scope_id = id;
  g_atomic_int_add(&scope_enabled, 1);
}
void probe_scope_end(void) { if (probe_atomic_read(&scope_enabled)) g_atomic_int_add(&scope_enabled, -1); }
static void probe_record(ProbeEvent *event, GumInvocationContext *ic,
    gpointer method, guint index, guint which, guint ordinal,
    guint packed, guint scope) {
  event->method_index = index;
  event->which = which;
  event->thread_id = gum_invocation_context_get_thread_id(ic);
  event->symbol_index = packed & 65535;
  event->entry_kind = packed >> 16;
  event->scope_id = scope;
  event->matched_ordinal = ordinal;
  event->method = method;
  event->caller = gum_invocation_context_get_return_address(ic);
  g_atomic_int_add(&event->ready, 1);
}
void probe_on_enter(GumInvocationContext *ic) {
  gpointer method;
  guint i, packed, entry_kind;
  if (!probe_atomic_read(&enabled)) return;
  method = gum_invocation_context_get_nth_argument(ic, 0);
  packed = GPOINTER_TO_UINT(gum_invocation_context_get_listener_function_data(ic));
  entry_kind = packed >> 16;
  if (entry_kind > 1) return;
  // Scope mode intentionally records unknown DoCall method pointers, only on
  // the selected original native thread. It is separate from target matches.
  if (entry_kind == 0 && probe_atomic_read(&scope_enabled) &&
      gum_invocation_context_get_thread_id(ic) == scope_thread) {
    gint slot = g_atomic_int_add(&scope_claimed, 1);
    if (slot < EVENT_CAPACITY)
      probe_record(&events[EVENT_CAPACITY + slot], ic, method,
          METHOD_CAPACITY, 2, slot + 1, packed, scope_id);
  }
  for (i = 0; i < method_count; i++) {
    ProbeMethod *entry = &methods[i];
    guint which;
    gint ordinal, candidate, slot;
    if (method == entry->original) which = 0;
    else if (method == entry->replacement) which = 1;
    else continue;
    g_atomic_int_add(&total_matched[entry_kind], 1);
    ordinal = g_atomic_int_add(&entry->matched[entry_kind], 1) + 1;
    if (which == 0) g_atomic_int_add(&entry->original_hits[entry_kind], 1);
    else g_atomic_int_add(&entry->replacement_hits[entry_kind], 1);
    candidate = g_atomic_int_add(&entry->candidates[entry_kind], 1);
    if (candidate >= method_limit) continue;
    slot = g_atomic_int_add(&claimed, 1);
    if (slot >= event_limit || slot >= EVENT_CAPACITY) continue;
    probe_record(&events[slot], ic, method, i, which, ordinal, packed, 0);
  }
}
gint probe_counter(guint kind, guint index, guint entry_kind) {
  if (entry_kind > 1) return -1;
  if (kind == 0) return probe_atomic_read(&total_matched[entry_kind]);
  if (kind == 1) return probe_atomic_read(&claimed);
  if (kind == 5) return probe_atomic_read(&scope_claimed);
  if (index >= method_count) return -1;
  if (kind == 2) return probe_atomic_read(&methods[index].original_hits[entry_kind]);
  if (kind == 3) return probe_atomic_read(&methods[index].replacement_hits[entry_kind]);
  if (kind == 4) return probe_atomic_read(&methods[index].matched[entry_kind]);
  return -1;
}
guint probe_event_number(guint index, guint field) {
  ProbeEvent *event;
  if (index >= EVENT_CAPACITY * 2) return 0;
  event = &events[index];
  if (!probe_atomic_read(&event->ready)) return 0;
  if (field == 0) return 1;
  if (field == 1) return event->method_index;
  if (field == 2) return event->which;
  if (field == 3) return event->thread_id;
  if (field == 4) return event->symbol_index;
  if (field == 5) return event->matched_ordinal;
  if (field == 6) return event->entry_kind;
  if (field == 7) return event->scope_id;
  return 0;
}
gpointer probe_event_pointer(guint index, guint field) {
  ProbeEvent *event;
  if (index >= EVENT_CAPACITY * 2) return NULL;
  event = &events[index];
  if (!probe_atomic_read(&event->ready)) return NULL;
  return field == 0 ? event->method : event->caller;
}
`;
  function discover() {
    // Inventory/start may consult Java.api; the native callback never does.
    const api = Java.api, art = api.module, entries = new Map();
    for (const entry of [...art.enumerateExports(), ...art.enumerateSymbols()]) {
      if (!entry.name.includes('interpreter6DoCall') && !entry.name.includes('ShouldStayInSwitchInterpreter')) continue;
      entries.set(entry.name + ':' + entry.address, {name: entry.name, address: String(entry.address),
        matches_legacy: legacy.test(entry.name), matches_api34: api34.test(entry.name),
        matches_should_stay: entry.name === shouldStay});
    }
    return {module: {name: art.name, path: art.path, base: String(art.base), size: art.size},
      architecture: Process.arch, pointer_size: Process.pointerSize,
      api34_apex_heuristic: api.isApiLevel34OrApexEquivalent,
      do_call: Array.from(entries.values()).filter(entry => entry.name.includes('interpreter6DoCall')),
      should_stay: Array.from(entries.values()).filter(entry => entry.name.includes('ShouldStayInSwitchInterpreter')),
      native_buffer_capacity: capacity, native_scope_buffer_capacity: capacity,
      native_method_capacity: methodCapacity,
      argument_observation: 'Interceptor-visible arg0; another listener may translate before or after this observer',
      mutation_policy: 'CModule native filter/counters/buffer only; no JavaScript or Java in onEnter; arguments remain unchanged'};
  }
  function bounded(value, fallback, limit, name) {
    if (value === undefined) return fallback;
    if (!Number.isInteger(value) || value < 0 || value > limit)
      throw new Error(name + ' must be an integer from 0 through ' + limit);
    return value;
  }
  function emit(fields) {
    try { report('bridge_interpreter_call', fields); }
    catch (_) { reportErrors++; }
  }
  function flush() {
    if (native === null) return;
    const claimed = Math.min(native.counter(1, 0, 0), maxEvents, capacity);
    // A concurrently claimed but unpublished slot stops this flush. The next
    // snapshot retries it, preserving native slot order without waiting on ART.
    while (nextFlush < claimed && native.eventNumber(nextFlush, 0) === 1) {
      flushEvent(nextFlush++, false);
    }
    const scopeClaimed = Math.min(native.counter(5, 0, 0), capacity);
    while (nextScopeFlush < scopeClaimed && native.eventNumber(capacity + nextScopeFlush, 0) === 1)
      flushEvent(capacity + nextScopeFlush++, true);
  }
  function flushEvent(index, scoped) {
    const ordered = Array.from(methods.values()), pointer = String(native.eventPointer(index, 0));
    const method = scoped ? ordered.find(value => value.original === pointer || value.replacement === pointer) :
      ordered[native.eventNumber(index, 1)];
    const caller = native.eventPointer(index, 1), entryKind = native.eventNumber(index, 6);
    let callerSymbol;
    try { callerSymbol = String(DebugSymbol.fromAddress(caller)); }
    catch (error) { callerSymbol = 'unavailable: ' + String(error); }
    if (!scoped) method.emitted++;
    const scopeId = native.eventNumber(index, 7);
    emit({native_thread_id: native.eventNumber(index, 3), label: method ? method.label : null,
      original_or_replacement: method ? (pointer === method.original ? 'original' : 'replacement') : 'unknown',
      method: pointer, original_method: method ? method.original : null,
      replacement_method: method ? method.replacement : null,
      do_call_symbols: symbolsByIndex[native.eventNumber(index, 4)],
      event_path: entryKind === 0 ? 'DoCall' : 'ShouldStayInSwitchInterpreter',
      recording_mode: scoped ? 'scope-all-methods' : 'registered-targets',
      scope_id: scoped ? scopeId : null, scope_label: scoped ? scopes[scopeId - 1].label : null,
      caller_return_address: String(caller), caller_return_symbol: callerSymbol,
      matched_ordinal: native.eventNumber(index, 5),
      emitted_ordinal: scoped ? index - capacity + 1 : index + 1,
      reporting_context: 'native buffer drained by JavaScript outside ART callback'});
  }
  return {
    register(label, overload) {
      if (started) throw new Error('native method table is frozen after start');
      if (typeof label !== 'string' || label.length === 0) throw new Error('nonempty method label required');
      const mangler = overload && overload._r && overload._r._m;
      if (!mangler || !mangler.hookedMethodId || !mangler.replacementMethodId)
        throw new Error('already-hooked ART overload required for ' + label);
      const original = String(mangler.hookedMethodId), replacement = String(mangler.replacementMethodId);
      if (mangler.hookedMethodId.isNull() || mangler.replacementMethodId.isNull() || original === replacement)
        throw new Error('distinct nonnull original/replacement ArtMethod pointers required');
      const previous = methods.get(label);
      if (previous !== undefined) {
        if (previous.original !== original || previous.replacement !== replacement)
          throw new Error('registered method identity changed for ' + label);
        return;
      }
      if (methods.size >= methodCapacity) throw new Error('native method capacity exceeded');
      methods.set(label, {label, overload, original, replacement, emitted: 0});
    },
    inventory() { if (discovered === null) discovered = discover(); return discovered; },
    start(options = {}) {
      if (started) throw new Error('DoCall observer already started; snapshot or stop it');
      maxEvents = bounded(options.maxEvents, 64, capacity, 'maxEvents');
      maxPerMethod = bounded(options.maxPerMethod, 8, capacity, 'maxPerMethod');
      if (discovered === null) discovered = discover();
      const addresses = new Map();
      for (const entry of discovered.do_call) {
        if (!entry.matches_legacy && !entry.matches_api34) continue;
        if (!addresses.has(entry.address)) addresses.set(entry.address, {kind: 0, symbols: []});
        addresses.get(entry.address).symbols.push(entry.name);
      }
      if (addresses.size === 0) throw new Error('no exact legacy/API34 ART DoCall symbols found');
      if (options.observeShouldStay !== false) for (const entry of discovered.should_stay) {
        if (!entry.matches_should_stay) continue;
        if (addresses.has(entry.address)) throw new Error('DoCall/ShouldStay symbol address alias');
        addresses.set(entry.address, {kind: 1, symbols: [entry.name]});
      }
      // CModule data are read-only: all mutable storage is external and pinned.
      const alloc=n=>{const p=Memory.alloc(n);p.writeByteArray(new Uint8Array(n));return p;};
      nativeStorage={methods:alloc(methodCapacity*64),events:alloc(capacity*2*64),
        method_count:alloc(4),event_limit:alloc(4),method_limit:alloc(4),enabled:alloc(4),
        total_matched:alloc(8),claimed:alloc(4),scope_claimed:alloc(4),scope_enabled:alloc(4),
        scope_thread:alloc(4),scope_id:alloc(4)};
      module = new CModule(source,nativeStorage);
      this._keepalive={module,nativeStorage};
      const callOptions = {exceptions: 'propagate', scheduling: 'exclusive'};
      native = {
        register: new NativeFunction(module.probe_register, 'void', ['uint','pointer','pointer'], callOptions),
        configure: new NativeFunction(module.probe_configure, 'void', ['int','int'], callOptions),
        disable: new NativeFunction(module.probe_disable, 'void', [], callOptions),
        counter: new NativeFunction(module.probe_counter, 'int', ['uint','uint','uint'], callOptions),
        scopeBegin: new NativeFunction(module.probe_scope_begin, 'void', ['uint','uint'], callOptions),
        scopeEnd: new NativeFunction(module.probe_scope_end, 'void', [], callOptions),
        eventNumber: new NativeFunction(module.probe_event_number, 'uint', ['uint','uint'], callOptions),
        eventPointer: new NativeFunction(module.probe_event_pointer, 'pointer', ['uint','uint'], callOptions)
      };
      let index = 0;
      for (const method of methods.values()) native.register(index++, ptr(method.original), ptr(method.replacement));
      try {
        for (const [address, entry] of addresses) {
          const symbolIndex = symbolsByIndex.length; symbolsByIndex.push(entry.symbols);
          // A CModule NativePointer callback, never a JS closure / NativeCallback.
          listeners.push(Interceptor.attach(ptr(address), {onEnter: module.probe_on_enter}, ptr((entry.kind << 16) | symbolIndex)));
        }
      } catch (error) {
        for (const listener of listeners.splice(0)) listener.detach();
        module.dispose(); module = null; native = null;
        throw error;
      }
      native.configure(maxEvents, maxPerMethod);
      started = true; running = true;
      return this.snapshot('started');
    },
    snapshot(stage) {
      flush();
      return {stage, running, started, native_hook_count: listeners.length,
        implementation: 'CModule native atomic filter and fixed buffer; JavaScript drains only',
        max_events: maxEvents, max_per_method: maxPerMethod,
        total_matched: native === null ? 0 : native.counter(0, 0, 0), total_emitted: nextFlush,
        should_stay_matched: native === null ? 0 : native.counter(0, 0, 1),
        buffer_claimed: native === null ? 0 : native.counter(1, 0, 0),
        scope_active: scopeActive, scopes: scopes.map(value => ({...value})),
        scope_buffer_claimed: native === null ? 0 : native.counter(5, 0, 0), scope_emitted: nextScopeFlush,
        report_errors: reportErrors, callback_errors: 0,
        methods: Array.from(methods.values(), (method, index) => ({label: method.label,
          original_method: method.original, replacement_method: method.replacement,
          original: native === null ? 0 : native.counter(2, index, 0),
          replacement: native === null ? 0 : native.counter(3, index, 0),
          matched: native === null ? 0 : native.counter(4, index, 0), emitted: method.emitted,
          should_stay_original: native === null ? 0 : native.counter(2, index, 1),
          should_stay_replacement: native === null ? 0 : native.counter(3, index, 1)}))};
    },
    enterScope(label, nativeThreadId) {
      if (!running || native === null) throw new Error('start observer before entering a native scope');
      if (scopeActive) throw new Error('only one native thread scope may be active');
      if (typeof label !== 'string' || !label) throw new Error('nonempty scope label required');
      const tid = nativeThreadId === undefined ? Process.getCurrentThreadId() : nativeThreadId;
      if (!Number.isInteger(tid) || tid <= 0 || tid > 0xffffffff) throw new Error('actual native thread id required');
      const id = scopes.length + 1; scopes.push({id, label, native_thread_id: tid});
      native.scopeBegin(tid, id); scopeActive = true;
      return id;
    },
    beginScope(label, nativeThreadId) { return this.enterScope(label, nativeThreadId); },
    endScope() {
      if (!scopeActive) throw new Error('no active native thread scope');
      native.scopeEnd(); scopeActive = false;
      return this.snapshot('scope-ended');
    },
    stop() {
      if (scopeActive) { native.scopeEnd(); scopeActive = false; }
      if (native !== null) native.disable();
      running = false;
      for (const listener of listeners.splice(0)) listener.detach();
      return this.snapshot('stopped');
    }
  };
})();
