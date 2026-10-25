// Emulator fixture input boundary. Call poll() inside Java.perform after the
// declared input producer has finished. This disconnects one SDK observer; it
// does not stop sender callbacks, discard queued work, or cancel Handler tasks.
globalThis.DenzaInputBoundary = (() => {
  const POLICY = 'remove_position_observer_after_fixture_inputs_v1';
  const SCHEMA = 'denza-stock-sender-input-boundary-v1';
  const SERVICE = 'com.autonavi.gbl.pos.PosService';
  const OBSERVER = 'com.byd.map.signal.GaoDeSignalManager';
  const INTERFACE = 'com.autonavi.gbl.pos.observer.IPosLocInfoObserver';
  const METHOD = SERVICE + '.removeLocInfoObserver(' + INTERFACE + ')';
  let installed = null;

  function immutable(value) {
    if (value !== null && typeof value === 'object') {
      for (const key of Object.keys(value)) immutable(value[key]);
      Object.freeze(value);
    }
    return value;
  }
  function copy(value) { return JSON.parse(JSON.stringify(value)); }

  function install(options) {
    if (installed !== null) throw Error('Input boundary already installed');
    if (!options || !Number.isSafeInteger(options.sampleCount) || options.sampleCount < 1)
      throw Error('Positive declared sampleCount required');
    if (options.callbackCase !== null &&
        (typeof options.callbackCase !== 'string' || options.callbackCase.length === 0))
      throw Error('Explicit callbackCase string or null required');
    if (typeof options.report !== 'function' || typeof options.reference !== 'function')
      throw Error('Input boundary requires report and original-object reference functions');
    const declaration = immutable({schema: SCHEMA, policy: POLICY,
      sample_count: options.sampleCount, callback_case: options.callbackCase,
      service_id: 13, service_class: SERVICE, observer_class: OBSERVER, method: METHOD});
    const errors = [], reportErrors = [];
    let state = 'installed', attempted = false, result = null;

    function note(kind, fields) {
      const row = immutable(Object.assign({}, declaration, fields || {}));
      try { options.report(kind, row); }
      catch (error) { reportErrors.push(String(error)); throw error; }
      return row;
    }
    function ref(value, expected) {
      const out = copy(options.reference(value));
      if (!out || out.type !== 'object' || typeof out.id !== 'string' ||
          !out.id.length || out.class !== expected)
        throw Error('Original-object reference differs for ' + expected);
      return immutable(out);
    }
    const api = {
      policy: POLICY,
      declaration,
      poll(input) {
        if (attempted || state === 'failed') return false;
        try {
          if (!input || !Number.isSafeInteger(input.deliveredSamples) || input.deliveredSamples < 0 ||
              typeof input.started !== 'boolean' || typeof input.callbackFixtureComplete !== 'boolean')
            throw Error('Typed deliveredSamples, started and callbackFixtureComplete required');
          if (input.deliveredSamples < declaration.sample_count || !input.started ||
              declaration.callback_case !== null && !input.callbackFixtureComplete) return false;
          // Resolve SDK singletons only after all producer prerequisites. The
          // original controller owns the actual guidance state; no fixture
          // declaration substitutes for this original isNaving() read.
          const navigationActive = Java.use('com.autosdk.bussiness.navi.NaviController')
            .getInstance().isNaving();
          if (typeof navigationActive !== 'boolean') throw Error('Original isNaving result is not boolean');
          if (navigationActive) return false;
          const service = Java.cast(Java.use('com.autonavi.gbl.servicemanager.ServiceMgr')
            .getServiceMgrInstance().getBLService(13), Java.use(SERVICE));
          if (service === null) throw Error('Original position service 13 is absent');
          const observer = Java.use(OBSERVER).getInstance();
          if (observer === null) throw Error('Original GaoDe singleton is absent');
          const receipt = immutable({attempt: 1, delivered_samples: input.deliveredSamples,
            started: true, callback_fixture_complete: true,
            callback_completion_basis: declaration.callback_case === null ?
              'baseline-no-callback-fixture' : 'observed-fixture-end',
            navigation_active: false, service_ref: ref(service, SERVICE),
            observer_ref: ref(observer, OBSERVER)});
          note('input_boundary_disconnect_enter', receipt);
          // Set before the call: an exceptional/failed attempt must never be
          // retried, and no second poll can remove this observer twice.
          attempted = true; state = 'calling';
          service.removeLocInfoObserver(Java.cast(observer, Java.use(INTERFACE)));
          state = 'call-returned';
          result = note('input_boundary_disconnect_exit', Object.assign({}, receipt,
            {status: 'call-returned'}));
          return true;
        } catch (error) {
          state = 'failed'; errors.push(String(error));
          try { note('input_boundary_error', {attempt: attempted ? 1 : 0, error: String(error)}); }
          catch (_) { /* metadata retains reporting failures; no action retry. */ }
          throw error;
        }
      },
      metadata() {
        return immutable(Object.assign({}, declaration, {state, attempted, result: result && copy(result),
          errors: errors.slice(), report_errors: reportErrors.slice()}));
      }
    };
    installed = api;
    try { note('input_boundary_installed', {status: 'installed'}); }
    catch (error) { state = 'failed'; errors.push(String(error)); throw error; }
    return api;
  }
  return {install, policy: POLICY};
})();
