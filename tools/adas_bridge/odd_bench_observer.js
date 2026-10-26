/* Emulator-only ODD oracle. Appended to stock_plan_observer.js by odd_bench.py.
 * Calls the original DnpOddDistanceController and records its protobuf output.
 * Native URIDs/lengths stay untouched in factory cases. Deliberate boundary
 * controls are labelled synthetic and scoped to a synchronous calculator call.
 */
Java.perform(() => {
  if (String(Java.use('android.os.SystemProperties').get('ro.kernel.qemu')) !== '1')
    throw new Error('ODD bench requires an emulator');
  const Link = Java.use('com.autonavi.gbl.common.path.option.LinkInfo');
  const Path = Java.use('com.autonavi.gbl.common.path.option.PathInfo');
  const Calc = Java.use('k.h.e0.c.f'), Odd = Java.use('k.h.e0.d.f.m');
  const Flags = Java.use('k.h.e0.c.j.i0.b.a');
  const getUrid = Link.getURID.overload();
  let active = null, done = false;
  getUrid.implementation = function () {
    const key = this.getRelatedSegmentIndex() + ':' + this.getLinkIndex();
    return active && Object.prototype.hasOwnProperty.call(active.overrides, key)
      ? active.overrides[key] : getUrid.call(this);
  };
  const Server = Java.use('com.autosdk.bussiness.pilot.someip.SomeIpServer');
  Server.sendSomeIpEvent.implementation = function (d) {
    if (active && String(d.getTopic()) === '1125929972105220') active.packets.push({topic: String(d.getTopic()),
      payload: String(Java.use('android.util.Base64').encodeToString(d.getPayload(), 2))});
    return 0; // The IVI endpoint is replaced; no output can leave this emulator.
  };
  function cities(values) {
    const out = Java.use('java.util.ArrayList').$new(), I = Java.use('java.lang.Integer');
    values.forEach(v => out.add(I.valueOf(v))); Odd.a(out);
  }
  function run(path) {
    const segments = [], flat = [];
    for (let s = 0; s < Number(path.getSegmentCount()); s++) {
      const seg = path.getSegmentInfo(s), links = [];
      for (let l = 0; l < Number(seg.getLinkCount()); l++) {
        const link = seg.getLinkInfo(l), key = link.getRelatedSegmentIndex() + ':' + link.getLinkIndex();
        const row = {segment: s, link: l, key, urid: link.getURID(), length: link.getLength(),
          adcode: String(link.getAdcode()), roadClass: link.getRoadClass()};
        links.push(row); flat.push(row);
      }
      segments.push(links);
    }
    if (!flat.length || flat.length > 2000) throw new Error('route size outside bench bounds');
    const native = Array.from(new Set(flat.map(x => x.urid))), foreign = 1000000;
    const cases = [
      {id: 'factory-observed-city-list', cities: ODD_BENCH.openCities, overrides: {}, synthetic: false},
      {id: 'factory-native-cities', cities: native, overrides: {}, synthetic: false},
      {id: 'factory-empty-city-list', cities: [], overrides: {}, synthetic: false},
      {id: 'factory-excluded-cities', cities: [foreign], overrides: {}, synthetic: false}
    ];
    if (flat.length > 1) {
      const base = {}; flat.forEach(x => { base[x.key] = native[0]; });
      const exit = Object.assign({}, base); exit[flat[1].key] = foreign;
      const entry = Object.assign({}, base); entry[flat[0].key] = foreign;
      cases.push({id: 'synthetic-covered-to-excluded', cities: [native[0]], overrides: exit, synthetic: true});
      cases.push({id: 'synthetic-excluded-to-covered', cities: [native[0]], overrides: entry, synthetic: true});
    }
    report('odd_factory_route', {pathId: String(path.getPathID()), segments, nativeUrids: native});
    const oldFlag = Flags.a.value;
    Flags.a.value = true;
    try {
      for (const c of cases) {
        cities(c.cities);
        report('odd_case', c);
        for (const row of flat) for (const remain of Array.from(new Set([row.length, Math.floor(row.length / 2), 0]))) {
          active = {overrides: c.overrides, packets: []};
          const ni = Java.use('com.autonavi.gbl.guide.model.NaviInfo').$new();
          ni.curSegIdx.value = row.segment; ni.curLinkIdx.value = row.link; ni.linkRemainDist.value = remain;
          const calc = Calc.$new(Java.use('android.os.Handler').$new(Java.use('android.os.Looper').getMainLooper()));
          try {
            calc.e(ni, path); // Original APK algorithm and original protobuf builder.
            report('odd_sample', {caseId: c.id, segment: row.segment, link: row.link,
              remain, packets: active.packets, lastDistance: Calc.class.getDeclaredField('c').getInt(calc)});
          } finally { active = null; }
        }
      }
    } finally { active = null; Flags.a.value = oldFlag; }
    report('odd_done', {links: flat.length, cases: cases.length});
  }
  const start = setInterval(() => {
    if (phase !== 'rendering') return;
    clearInterval(start);
    Java.scheduleOnMainThread(() => {
      try {
        const service = Java.cast(Java.use('com.autonavi.gbl.servicemanager.ServiceMgr')
          .getServiceMgrInstance().getBLService(17), Java.use('com.autonavi.gbl.route.RouteService'));
        const observer = Java.registerClass({name: 'dev.denza.adasbench.OddRouteObserver',
          implements: [Java.use('com.autonavi.gbl.route.observer.IRouteResultObserver')], methods: {
            onNewRouteError: {returnType: 'void', argumentTypes: ['com.autonavi.gbl.route.model.PathResultData',
              'com.autonavi.gbl.common.path.model.RouteLimitInfo'], implementation: function () {}},
            onNewRoute: {returnType: 'void', argumentTypes: ['com.autonavi.gbl.route.model.PathResultData',
              'java.util.ArrayList', 'com.autonavi.gbl.common.path.model.RouteLimitInfo'],
              implementation: function (d, paths) {
                if (done || paths.size() === 0) return;
                done = true;
                try { run(Java.cast(paths.get(0), Path)); }
                catch (e) { active = null; report('odd_error', {error: String(e), stack: e.stack}); }
              }}
          }}).$new();
        service.addRouteResultObserver(observer);
        Java.retain(service); Java.retain(observer);
        report('odd_ready', {emulatorOnly: true, vehicleOperations: 0});
      } catch (e) { report('odd_error', {error: String(e), stack: e.stack}); }
    });
  }, 100);
});
