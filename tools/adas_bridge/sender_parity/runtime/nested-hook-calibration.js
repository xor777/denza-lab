// Invoke inside Java.perform, after the owner's first deopt and before sender
// hooks. Only fresh java.util.HashMap objects are mutated. Hooks are restored.
globalThis.DenzaNestedHookCalibration = function () {
  const expected = {put: 3, putVal: 3, resize: 1, newNode: 2};
  const observations = [], installed = [], cleanupErrors = [];
  let active = false, nextId = 0, failure = null;
  const stack = [];
  const counts = {put: 0, putVal: 0, resize: 0, newNode: 0};
  const C = Java.use('java.util.HashMap'), S = Java.use('java.lang.String');
  const target = Java.retain(C.$new());
  const keys = [S.$new('denza-calibration-key-one'), S.$new('denza-calibration-key-two')];
  const values = [S.$new('one'), S.$new('replacement'), S.$new('two')];
  const tid = Process.getCurrentThreadId(), env = Java.vm.getEnv();
  const methods = [
    ['putVal', ['int', 'java.lang.Object', 'java.lang.Object', 'boolean', 'boolean']],
    ['resize', []],
    ['newNode', ['int', 'java.lang.Object', 'java.lang.Object', 'java.util.HashMap$Node']],
    ['put', ['java.lang.Object', 'java.lang.Object']]
  ];
  const result = {kind: 'bridge_nested_hook_calibration', expected, counts,
    native_thread_id: tid, observations, status: 'ERROR', cleanup_errors: cleanupErrors};
  try {
    const selected = methods.map(([name, types]) => ({name,
      method: C[name].overload.apply(C[name], types)}));
    for (const entry of selected) {
      if (entry.method.implementation !== null)
        throw new Error('Preexisting hook: HashMap.' + entry.name);
    }
    for (const {name, method} of selected) {
      method.implementation = function () {
        const args = Array.from(arguments);
        if (!active || Process.getCurrentThreadId() !== tid || !env.isSameObject(this.$h, target.$h))
          return method.call.apply(method, [this].concat(args));
        const id = ++nextId, parent = stack.length ? stack[stack.length - 1] : null;
        observations.push({event: 'enter', id, parent, method: name});
        counts[name]++;
        stack.push(id);
        let error = null;
        try { return method.call.apply(method, [this].concat(args)); }
        catch (e) { error = String(e); throw e; }
        finally {
          observations.push({event: 'exit', id, parent, method: name, error});
          stack.pop();
        }
      };
      installed.push(method);
    }
    const returned = [];
    active = true;
    try {
      returned.push(target.put(keys[0], values[0]));
      returned.push(target.put(keys[0], values[1]));
      returned.push(target.put(keys[1], values[2]));
    } finally { active = false; }
    result.returned = returned.map(v => v === null ? null : String(v));
    result.size = target.size();
    result.final_values = keys.map(k => String(target.get(k)));
    const observed = Object.keys(expected).every(n => counts[n] === expected[n]);
    const semantic = result.size === 2 && result.returned[0] === null &&
      result.returned[1] === 'one' && result.returned[2] === null &&
      result.final_values.join(',') === 'replacement,two';
    const nested = observations.filter(x => x.event === 'enter' && x.method !== 'put')
      .every(x => x.parent !== null);
    result.status = observed && semantic && nested && stack.length === 0 ? 'PASS' : 'MISSING_HOOKS';
  } catch (error) { failure = String(error); }
  finally {
    active = false;
    for (const method of installed.reverse()) {
      try { method.implementation = null; } catch (error) { cleanupErrors.push(String(error)); }
    }
    target.$dispose();
  }
  if (failure !== null) result.error = failure;
  if (cleanupErrors.length) result.status = 'ERROR';
  return result;
};
