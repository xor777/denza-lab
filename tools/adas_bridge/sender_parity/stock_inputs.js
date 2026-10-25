/* Original ART sender input recorder, appended to stock_feed_observer.js.
 * Observation only: original methods are called exactly once with original arguments.
 * The enclosing emulator fixture owns the transport stubs; this file never installs
 * a second fireEvent implementation or obtains a vehicle connection.
 * Native getters are read BEFORE original conversion. No output JSON/protobuf is
 * consulted to construct any sender_input event.
 */
const SP_REQUIRED_HOOKS = [
  'k.h.e0.c.g.G', 'k.h.a0.c.c.a.e0.P', 'k.h.a0.c.c.a.e0.v0',
  'com.byd.map.signal.GaoDeSignalManager.onNavStart',
  'com.byd.map.signal.GaoDeSignalManager.onNavStop',
  'com.byd.map.signal.GaoDeSignalManager.onNaviArrive',
  'com.autosdk.bussiness.pilot.someip.SomeIpClient.onSomeIpEvent',
  'k.h.a0.c.c.a.e0.b0', 'k.h.a0.c.c.a.e0.J'
];
let spSeq = 0, spCall = 0;
const spStacks = new Map();
const spAttemptedRequired = [];
function spThread() { return String(Java.use('java.lang.Thread').currentThread().getId()); }
function spStack() {
  const thread = spThread();
  if (!spStacks.has(thread)) spStacks.set(thread, []);
  return spStacks.get(thread);
}
function spField(o, name) {
  // jadx renames colliding field/method names; ART retains the DEX name.
  name = ({f13986b: 'b', f13314b: 'b'})[name] || name;
  const f = o['_' + name] !== undefined ? o['_' + name] : o[name];
  if (f === undefined) throw new Error('Missing ART field ' + name + ' on ' + (o.$className || '<class>'));
  return f.value;
}
function spNum(o, name) { return Number(spField(o, name)); }
function spText(o) { return o === null ? null : String(o); }
function spPoint(o) { return [spNum(o, 'lon'), spNum(o, 'lat')]; }
function spList(list, cast, fn) {
  if (list === null) return null;
  const out = [];
  for (let i = 0; i < list.size(); i++) {
    const item = list.get(i);
    out.push(fn(item === null ? null : Java.cast(item, Java.use(cast)), i));
  }
  return out;
}
function spJson(o) {
  if (o === null) return null;
  if (typeof o !== 'object') return o;
  // Frida passes Java long arguments as Int64 wrappers. They are not Java
  // object handles, and routing them through Gson would drop the callback.
  if ((typeof Int64 !== 'undefined' && o instanceof Int64) ||
      (typeof UInt64 !== 'undefined' && o instanceof UInt64))
    return {type: 'int64', decimal: o.toString()};
  return JSON.parse(String(Java.use('com.google.gson.Gson').$new().toJson.overload('java.lang.Object').call(
    Java.use('com.google.gson.Gson').$new(), o)));
}
function spClocks() {
  const C = Java.use('android.os.SystemClock');
  return {uptime_ms: Number(C.uptimeMillis()), elapsed_ms: Number(C.elapsedRealtime()), mono_ms: rtNow()};
}
function spInput(event, data, method) {
  const seq = spSeq++, stack = spStack(), current = stack.length ? stack[stack.length - 1] : null;
  report('sender_input', {schema: 'denza-stock-sender-input-v1', seq,
    mono_ms: rtNow(), wall_ms: Date.now(), clocks: spClocks(), event, data, method,
    thread: spThread(), call_id: current ? current.id : null,
    parent_call_id: stack.length > 1 ? stack[stack.length - 2].id : null});
  if (current && event !== 'internal_observation') current.input_seq = seq;
}
function spPath(path) {
  if (path === null) throw new Error('null PathInfo');
  const segments = [];
  for (let si = 0; si < Number(path.getSegmentCount()); si++) {
    const s = path.getSegmentInfo(si);
    if (s === null) throw new Error('null SegmentInfo ' + si);
    const links = [];
    for (let li = 0; li < Number(s.getLinkCount()); li++) {
      const l = s.getLinkInfo(li);
      if (l === null) throw new Error('null LinkInfo ' + si + '/' + li);
      const points = spList(l.getPoints(), 'com.autonavi.gbl.common.model.Coord2DInt32', spPoint);
      links.push({topoId: String(l.get64TopoID()), roadClass: l.getRoadClass(), roadName: spText(l.getRoadName()),
        length: Number(l.getLength()), linkType: l.getLinkType(), formway: l.getFormway(),
        hasTrafficLight: l.hasTrafficLight(), laneNum: l.getLaneNum(), hasMultiOut: l.hasMultiOut(),
        mainAction: l.getMainAction(), assistantAction: l.getAssistantAction(), adcode: String(l.getAdcode()),
        ownership: l.getOwnership(), hasParallel: l.hasParallelRoad(), direction: l.getRoadDirection(),
        hasMixFork: l.hasMixFork(), urid: l.getURID(),
        lon: points === null ? null : points.map(p => p[0]), lat: points === null ? null : points.map(p => p[1])});
    }
    segments.push({segmentIndex: Number(s.getSegmentIndex()), length: Number(s.getLength()), travelTime: Number(s.getTravelTime()),
      mainAction: s.getMainAction(), assistantAction: s.getAssistantAction(),
      trafficLightNum: Number(s.getTrafficLightNum()), links});
  }
  const groups = [];
  for (let i = 0; i < Number(path.getGroupSegmentCount()); i++) {
    const g = path.getGroupSegment(i);
    groups.push([spNum(g, 'startSegmentIndex'), spNum(g, 'segmentCount'), spText(spField(g, 'roadName'))]);
  }
  return {pathId: String(path.getPathID()), segments, groups, viaRoadInfo: spJson(path.getPathViaRoadInfo()),
    trafficLights: spList(path.getAllTrafficLights(), 'com.autonavi.gbl.common.model.Coord2DDouble', spPoint)};
}
function spHook(cls, method, types, event, read, required) {
  const id = cls + '.' + method;
  if (required) spAttemptedRequired.push(id);
  try {
    const f = Java.use(cls)[method].overload.apply(Java.use(cls)[method], types);
    f.implementation = function () {
      const args = Array.from(arguments), call = ++spCall, stack = spStack();
      stack.push({id: call, input_seq: stack.length ? stack[stack.length - 1].input_seq : null});
      try {
        try { spInput(event, read.apply(this, args), id); }
        catch (error) { report('sender_error', {method: id, error: String(error), stack: error.stack, seq: spSeq, call_id: call}); }
        return f.call.apply(f, [this].concat(args));
      } finally { stack.pop(); }
    };
    report('sender_hook', {method: id, status: 'installed', required: !!required});
  } catch (error) { report('sender_hook', {method: id, status: 'failed', required: !!required, error: String(error)}); }
}

// Sample recorder clocks without modifying original clock return values. The
// route envelope wall timestamp remains a separately declared nondeterminism.
Java.perform(() => {
  const inputManager = 'k.h.a0.c.c.a.e0';
  spHook('k.h.e0.c.g', 'G', ['com.autosdk.bussiness.navi.route.model.RouteCarResultData'], 'route', function (r) {
    if (r === null) return {nullRoute: true};
    const paths = r.getPathInfos(), focus = r.getFocusIndex();
    const p = Java.cast(paths.get(focus), Java.use('com.autonavi.gbl.common.path.option.PathInfo'));
    return {path: spPath(p), amapRouteType: r.getRouteType(), requestId: String(r.getRequestId()),
      focusIndex: focus, pathCount: paths.size(), previousPathId: String(spField(this, 'f13986b')),
      lastMultipathRequestId: String(Java.use('k.h.e0.d.f.k').d().e()),
      someIpV2: !!Java.use('com.autosdk.bussiness.vehicle.PlatformUtils').isSomeIpV2()};
  }, true);
  spHook(inputManager, 'P', ['com.autonavi.gbl.pos.model.LocInfo'], 'location', function (loc) {
    if (loc === null) return {nullLocation: true};
    const matches = spField(loc, 'matchInfo'), raw = spPoint(spField(loc, 'gpsPos'));
    const m = matches.size() ? Java.cast(matches.get(0), Java.use('com.autonavi.gbl.pos.model.LocMatchInfo')) : null;
    const throttle = Java.use('k.h.a0.d.c');
    const region = spField(this, 'c');
    const common = {rawLon: raw[0], rawLat: raw[1], speedKmh: spNum(loc, 'speed'),
      simulated: spNum(loc, 'isSimulate') === 1, matchCount: matches.size(),
      vehicleSpeedKmh: Number(Java.use('com.autosdk.bussiness.vehicle.PlatformUtils').getInstance().realSpeed()),
      throttleLastMs: String(spField(throttle, 'a')), throttlePeriodMs: String(spField(throttle, 'f13314b')),
      regionState: {regionId: region.getRegionId(), countryCode: String(region.getCountryCode()),
        provinceCode: region.getProvinceCode(), cityCode: region.getCityCode(), weather: region.getWeatherType()},
      rawLocInfo: spJson(loc)};
    if (m === null) return common;
    const matched = spPoint(spField(m, 'stPos'));
    return Object.assign(common, {matchedLon: matched[0], matchedLat: matched[1], heading: spNum(m, 'course'),
      roadClass: spNum(m, 'roadClass'), ownership: spNum(m, 'ownership'),
      linkType: spNum(m, 'linkType'), pathId: String(spField(m, 'pathID'))});
  }, true);
  spHook(inputManager, 'v0', ['com.autonavi.gbl.guide.model.NaviInfo', 'com.autonavi.gbl.common.path.option.PathInfo'], 'progress', function (n, p) {
    if (n === null || p === null) return {nullProgress: true};
    const remain = spField(n, 'routeRemain'), panels = spField(n, 'NaviInfoData'), flag = spNum(n, 'NaviInfoFlag');
    const panel = panels.size() ? Java.cast(panels.get(flag), Java.use('com.autonavi.gbl.guide.model.NaviInfoPanel')) : null;
    return {pathId: String(spField(n, 'pathID')), segIdx: spNum(n, 'curSegIdx'), linkIdx: spNum(n, 'curLinkIdx'),
      curPointIdx: spNum(n, 'curPointIdx'), crossManeuverID: spNum(n, 'crossManeuverID'),
      linkRemain: spNum(n, 'linkRemainDist'), segRemain: panel === null ? 0 : spNum(spField(panel, 'segmentRemain'), 'dist'),
      routeRemain: spNum(remain, 'dist'), timeToFinishS: spNum(remain, 'time'),
      remainLights: spNum(n, 'routeRemainLightCount'), curLinkSpeed: spNum(n, 'curLinkSpeed'),
      naviInfoFlag: flag, rawNaviInfo: spJson(n)};
  }, true);
  const signal = 'com.byd.map.signal.GaoDeSignalManager';
  spHook(signal, 'onNavStart', [], 'nav_start', () => ({}), true);
  spHook(signal, 'onNavStop', [], 'nav_stop', () => ({}), true);
  spHook(signal, 'onNaviArrive', ['long', 'int'], 'arrival', (path, type) => ({pathId: String(path), type}), true);
  spHook(inputManager, 'b0', ['com.autonavi.gbl.common.path.option.RouteOption'], 'reroute', r => ({amapRouteType: r.getRouteType()}), true);
  spHook(inputManager, 'J', ['int'], 'road_speed', value => ({kmh: value}), true);
  spHook('com.autosdk.bussiness.pilot.someip.SomeIpClient', 'onSomeIpEvent', ['ts.car.someip.sdk.common.SomeIpData'], 'ack', d => ({topic: String(d.getTopic()), payloadB64: abB64(d.getPayload())}), true);
  spHook('k.h.e0.c.g', 'onSpeedChange', ['com.autosdk.bussiness.vehicle.BydAutoSpeed$Speed'], 'vehicle_speed', s => ({kmh: spNum(s, 'currentSpeed')}), false);
  spHook('k.h.e0.c.g', 'onDnpSwitchState', ['boolean', 'boolean', 'boolean'], 'gates', function (on, cnoa, hnoa) {
    const flags = Java.use('k.h.e0.c.j.i0.b.a');
    return {service: !!spField(flags, 'b'), switch: !!on, no_sleep: !!spField(flags, 'p'),
      rawCnoa: cnoa, rawHnoa: hnoa};
  }, true);
  spHook('k.h.a0.c.c.a.f0', 't', ['boolean'], 'gates', function (awake) {
    const flags = Java.use('k.h.e0.c.j.i0.b.a');
    return {service: !!spField(flags, 'b'), switch: !!spField(flags, 'a'), no_sleep: !!awake};
  }, true);

  // Preserve every other processed SDK callback encountered by this fixture. The
  // port replay must mark unsupported ones as gaps, never silently drop them.
  const optional = {
    B0: ['java.util.List'], D0: ['boolean', 'java.util.ArrayList'],
    F: ['int', 'int', 'java.util.Map'],
    F0: ['boolean', 'long', 'long', 'com.byd.map.signal.someip.pilot.bean.TrafficLightState', 'com.autonavi.gbl.common.model.Coord2DDouble', 'int', 'int'],
    L: ['double', 'double'], N: ['double', 'double'], T: ['int'], V: ['int'],
    X: ['com.autonavi.gbl.guide.model.SoundInfo'], Z: ['java.util.ArrayList', 'long'],
    f0: ['java.util.List', 'boolean'], k0: ['java.util.ArrayList'],
    h0: ['boolean', 'java.util.ArrayList', 'java.util.ArrayList', 'java.util.ArrayList', 'java.util.ArrayList',
      'java.util.ArrayList', 'java.util.ArrayList', 'int', 'int', 'java.util.ArrayList'],
    n0: ['boolean', 'java.util.List'],
    p0: ['com.autonavi.gbl.guide.model.CruisePredictiveTunnelInfo'],
    r0: ['com.autonavi.gbl.guide.model.CruiseRoadSpeed'],
    t0: ['boolean', 'java.util.ArrayList', 'int'],
    x0: ['boolean', 'java.util.ArrayList'], z0: ['boolean', 'java.util.List', 'long', 'long']
  };
  for (const name of Object.keys(optional)) {
    spHook(inputManager, name, optional[name], 'unsupported_callback', function () {
      return {method: inputManager + '.' + name, rawArgs: Array.from(arguments, spJson)};
    }, true);
  }
  for (const name of ['i0', 'l0']) {
    const types = name === 'i0' ? ['boolean', 'java.util.ArrayList'] : ['java.util.ArrayList'];
    spHook(inputManager, name, types, 'unsupported_callback', function () {
      return {method: inputManager + '.' + name, rawArgs: Array.from(arguments, spJson)};
    }, true);
  }
  // Reset helpers are observations of internally triggered work, not a second
  // replay input: their parent nav_start/reroute/stop is already recorded above.
  for (const name of ['z', 'H', 'd0', 'R']) {
    spHook(inputManager, name, [], 'internal_observation', () => ({method: inputManager + '.' + name}), true);
  }
  spHook(inputManager, 'p', ['int'], 'internal_observation', state => ({method: inputManager + '.p', state}), true);
  report('sender_hooks_ready', {required: spAttemptedRequired, schema: 'denza-stock-sender-input-v1'});
});

// Original fixture already reports these facts after its explicit BYDAuto shims.
// Preserve them as INPUT provenance; do not infer gates from output packets.
const spOriginalAbReport = abReport;
abReport = function (kind, fields) {
  const stack = spStack(), current = stack.length ? stack[stack.length - 1] : null;
  fields = Object.assign({}, fields, {sender_call_id: current ? current.id : null,
    sender_input_seq: current ? current.input_seq : null, sender_thread: spThread()});
  if (kind === 'car_state') {
    const flags = Java.use('k.h.e0.c.j.i0.b.a');
    const locale = String(Java.use('java.util.Locale').getDefault().getLanguage());
    report('sender_start', {mono_ms: rtNow(), clocks: spClocks(),
      gates: {service: !!spField(flags, 'b'), switch: !!spField(flags, 'a'), no_sleep: !!spField(flags, 'p')},
      locale: locale === 'en' ? 'EN' : 'DEFAULT', fixture: fields});
  }
  spOriginalAbReport(kind, fields);
};
