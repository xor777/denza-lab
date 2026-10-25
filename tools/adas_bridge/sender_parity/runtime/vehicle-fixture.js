// Emulator-only platform inputs. Load the source without running it, then call
// installNoa() inside Java.perform before original NOA initialization. No sender
// method or original platform getter implementation is replaced.
globalThis.DenzaVehicleFixture = (() => {
  const R_NAME = 'k.h.e0.a.r';
  const NOA_NAME = 'k.h.e0.b.f';
  const SPEED_NAME = 'com.autosdk.bussiness.vehicle.BydAutoSpeed';
  const SPEED_LISTENER = 'com.autosdk.bussiness.vehicle.proxy.BydAutoSpeedProxy$OnRealSpeedChangeListener';
  const BASELINE = Object.freeze({A: true, E: true, H: true, w: true, x: true,
    z: true, C: true, s: 0, t: 1, G: 2});
  const TYPES = Object.freeze({A: 'boolean', E: 'boolean', H: 'boolean', w: 'boolean',
    x: 'boolean', z: 'boolean', C: 'boolean', s: 'int', t: 'int', G: 'int'});
  let installed = null;

  function checkedValues(value) {
    if (value === null || typeof value !== 'object' || Array.isArray(value))
      throw Error('NOA fixture values must be an object');
    const actual = Object.keys(value).sort(), expected = Object.keys(TYPES).sort();
    if (JSON.stringify(actual) !== JSON.stringify(expected))
      throw Error('NOA fixture requires exactly the ten declared adapter getters');
    const result = {};
    for (const name of Object.keys(TYPES)) {
      const v = value[name];
      if (TYPES[name] === 'boolean' ? typeof v !== 'boolean' :
          !Number.isInteger(v) || v < -2147483648 || v > 2147483647)
        throw Error('Invalid fixture value for ' + R_NAME + '.' + name);
      result[name] = v;
    }
    return Object.freeze(result);
  }

  function fieldByType(klass, name) {
    const fields = Array.from(klass.getDeclaredFields()).filter(f =>
      String(f.getType().getName()) === name && (Number(f.getModifiers()) & 8) === 0);
    if (fields.length !== 1)
      throw Error('Expected one instance field of type ' + name + ', found ' + fields.length);
    fields[0].setAccessible(true);
    return fields[0];
  }

  // The proxy must be replaced before a real platform device/listener is
  // attached: an attached listener can retain the old proxy as its outer this.
  function preserveUnattachedProxy(R, oldProxy, replacement) {
    if (Boolean(oldProxy.isReady())) throw Error('NOA adapter is already attached');
    const fields = [], Modifier = Java.use('java.lang.reflect.Modifier');
    let cls = R.class;
    while (cls !== null && String(cls.getName()) !== 'java.lang.Object') {
      for (const f of Array.from(cls.getDeclaredFields())) {
        if (Modifier.isStatic(f.getModifiers())) continue;
        f.setAccessible(true);
        const value = f.get(oldProxy), type = String(f.getType().getName());
        if (type === 'android.hardware.IBYDAutoListener' && value !== null)
          throw Error('NOA adapter already owns a device listener');
        f.set(replacement, value);
        fields.push({owner: String(cls.getName()), name: String(f.getName()), type});
      }
      cls = cls.getSuperclass();
    }
    return fields;
  }

  function installNoa(options) {
    options = options || {};
    if (installed !== null) throw Error('NOA fixture already installed');
    let values = checkedValues(options.values || BASELINE), phase = 'installation';
    const reportErrors = [], reads = {};
    const report = (kind, value) => {
      if (typeof options.report !== 'function') return;
      try { options.report(kind, value); }
      catch (e) { reportErrors.push(String(e)); }
    };
    const R = Java.use(R_NAME), Noa = Java.use(NOA_NAME);
    const className = options.className || 'dev.denza.adasbench.StockParityNoaFixture';
    if (!/^(?:[A-Za-z_$][\w$]*\.)+[A-Za-z_$][\w$]*$/.test(className))
      throw Error('Invalid fixture class name');
    const methods = {$init: [{returnType: 'void',
      argumentTypes: ['k.h.e0.b.e', 'k.h.e0.f.a.a'],
      implementation: function (listener, otherListener) {
        this.$super.$init(listener, otherListener);
      }}]};
    const getterMethods = Object.keys(TYPES).map(name => ({name,
      returnType: TYPES[name], argumentTypes: [], originalClass: R_NAME,
      fixtureClass: className, signature: '()' + (TYPES[name] === 'boolean' ? 'Z' : 'I')}));
    for (const spec of getterMethods) {
      const original = R[spec.name].overload();
      if (original.returnType.className !== spec.returnType || original.implementation !== null)
        throw Error('Unexpected or already replaced adapter method: ' + spec.name);
      const reflected = R.class.getDeclaredMethod(spec.name, Java.array('java.lang.Class', []));
      if ((Number(reflected.getModifiers()) & (8 | 16)) !== 0)
        throw Error('Adapter method is static/final: ' + spec.name);
      methods[spec.name] = [{returnType: spec.returnType, argumentTypes: [],
        implementation: function () {
          const value = values[spec.name];
          reads[spec.name] = (reads[spec.name] || 0) + 1;
          report('vehicle_noa_fixture_read', {method: spec.name, value, phase,
            receiver_class: className, original_class: R_NAME});
          return value;
        }}];
    }
    for (const name of ['h', 'r']) {
      if (Noa[name].overload().implementation !== null)
        throw Error('Original NoaStateManager.' + name + ' is replaced');
    }
    const manager = Java.retain(Noa.m());
    const field = fieldByType(Noa.class, R_NAME);
    const oldProxy = Java.retain(Java.cast(field.get(manager), R));
    const Fixture = Java.registerClass({name: className, superClass: R, methods});
    const replacement = Java.retain(Fixture.$new(
      Java.cast(manager, Java.use('k.h.e0.b.e')),
      Java.cast(manager, Java.use('k.h.e0.f.a.a'))));
    const copied = preserveUnattachedProxy(R, oldProxy, replacement);
    field.set(manager, replacement);
    const proof = {kind: 'vehicle_noa_fixture_installed', class_name: className,
      original_class: R_NAME, manager_class: NOA_NAME, field_name: String(field.getName()),
      values: Object.assign({}, values), getter_count: getterMethods.length,
      copied_fields: copied, platform_device_attached: false,
      mechanism: 'registered_virtual_subclass', original_sender_methods_replaced: false};
    const api = {
      className, getterMethods, proof,
      values: () => Object.assign({}, values),
      setValues: input => {
        values = checkedValues(input);
        report('vehicle_noa_fixture_values', {values: Object.assign({}, values)});
      },
      metadata: () => ({class_name: className, reads: Object.assign({}, reads),
        report_errors: reportErrors.slice(), values: Object.assign({}, values)}),
      validate: () => {
        const before = phase;
        phase = 'validation';
        try {
          const env = Java.vm.getEnv();
          const same = Boolean(env.isSameObject(manager.i().$h, replacement.$h));
          const actual = {adas_type: Number(manager.h()), tip_island_active: Boolean(manager.r())};
          const expected = {adas_type: values.t, tip_island_active: values.G === 2};
          const result = {kind: 'vehicle_noa_fixture_validation', same_adapter: same,
            actual, expected, status: same && actual.adas_type === expected.adas_type &&
              actual.tip_island_active === expected.tip_island_active ? 'PASS' : 'FAIL',
            report_errors: reportErrors.slice()};
          report(result.kind, result);
          if (result.status !== 'PASS') throw Error(JSON.stringify(result));
          return result;
        } finally { phase = before; }
      }
    };
    installed = {api, manager, replacement, oldProxy, Fixture};
    phase = 'original_execution';
    report(proof.kind, proof);
    return api;
  }

  // Calls the exact original listener object constructed by BydAutoSpeed. The
  // listener updates speedFromReal through the original c(double) body, applies
  // its original bounds, schedules notifications and calls original listeners.
  // currentSpeed(), all cached values and all original getter bodies remain.
  function deliverVehicleSpeed(kmh, options) {
    options = options || {};
    if (!Number.isFinite(kmh) || kmh < 0 || kmh >= 282)
      throw Error('Vehicle real speed must satisfy original 0 <= km/h < 282');
    const Hub = Java.use('com.autosdk.bussiness.vehicle.BydAutoHub');
    const Speed = Java.use(SPEED_NAME), Listener = Java.use(SPEED_LISTENER);
    const speed = Hub.getInstance().getBydAutoSpeed();
    if (Speed.realSpeed.overload().implementation !== null ||
        Speed.currentSpeed.overload().implementation !== null)
      throw Error('Original vehicle speed getters are replaced');
    const listener = Java.cast(fieldByType(Speed.class, SPEED_LISTENER).get(speed), Listener);
    listener.onRealSpeedChange(kmh);
    const actual = Number(speed.realSpeed());
    const result = {kind: 'vehicle_speed_fixture_delivery', input_kmh: kmh,
      actual_real_speed_kmh: actual, status: actual === kmh ? 'PASS' : 'FAIL',
      receiver_class: SPEED_NAME, input_interface: SPEED_LISTENER + '.onRealSpeedChange(double)',
      original_getters_replaced: false, current_speed_modified: false};
    if (typeof options.report === 'function') options.report(result.kind, result);
    if (result.status !== 'PASS') throw Error(JSON.stringify(result));
    return result;
  }

  return {installNoa, deliverVehicleSpeed, baselineValues: () => Object.assign({}, BASELINE)};
})();
