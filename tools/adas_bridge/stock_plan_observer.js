/* Emulator-only route survey: plan PLAN_CASE.pairs one after another with the original
 * offline router (NaviController.requestTbtRoute, constrain 260, strategy 32, as the
 * replay observers do) and hand every result to the original NOA serializer
 * k.h.e0.d.f.l.f(ArrayList<PathInfo>) - the same call k.h.e0.d.f.k makes before
 * sendPath2Momenta. No guidance, no SOME/IP, nothing replaced. Prepend
 *   const PLAN_CASE={pairs:[{id,from:[lon,lat],to:[lon,lat]}], timeoutMs:25000};   (SDK frame)
 * and run through tools/adas_bridge/beijing.py survey.
 */
const spHeld = [];
let spIndex = -1, spTimer = null, spPending = null;
function spReport(kind, fields) { report('survey_' + kind, Object.assign({at_ms: Date.now()}, fields || {})); }
function spNext() {
  if (spTimer) { clearTimeout(spTimer); spTimer = null; }
  spIndex++;
  if (spIndex >= PLAN_CASE.pairs.length) { spReport('done', {pairs: PLAN_CASE.pairs.length}); return; }
  const pair = PLAN_CASE.pairs[spIndex];
  Java.perform(() => Java.scheduleOnMainThread(() => {
    try {
      const pois = Java.use('com.autonavi.gbl.common.path.option.POIForRequest').$new();
      for (const entry of [[0, pair.from], [2, pair.to]]) {
        const p = Java.use('com.autonavi.gbl.common.path.model.POIInfo').$new();
        p.realPos.value = Java.use('com.autonavi.gbl.common.model.Coord2DDouble').$new(entry[1][0], entry[1][1]);
        p.type.value = entry[0] === 0 ? 0 : 1; p.name.value = 'ADAS bench survey'; pois.addPoint(entry[0], p);
      }
      const o = Java.use('com.autonavi.gbl.common.path.option.RouteOption').$new();
      o.setPOIForRequest(pois); o.setConstrainCode(260); o.setRouteStrategy(32); o.setCalcRouteRespType(1);
      spHeld.push(Java.retain(o), Java.retain(pois));
      spPending = pair.id;
      const id = Java.use('com.autosdk.bussiness.navi.NaviController').getInstance().requestTbtRoute(o, false, false);
      spReport('request', {pair: pair.id, requestId: String(id)});
      spTimer = setTimeout(() => { spReport('timeout', {pair: pair.id}); spNext(); }, PLAN_CASE.timeoutMs || 25000);
    } catch (e) { spReport('request_error', {pair: pair.id, error: String(e)}); setTimeout(spNext, 100); }
  }));
}
const spStart = setInterval(() => {
  if (phase !== 'rendering') return;
  clearInterval(spStart);
  Java.perform(() => Java.scheduleOnMainThread(() => {
    try {
      const service = Java.cast(Java.use('com.autonavi.gbl.servicemanager.ServiceMgr').getServiceMgrInstance().getBLService(17), Java.use('com.autonavi.gbl.route.RouteService'));
      const Obs = Java.registerClass({name: 'dev.denza.adasbench.SurveyRouteObserver', implements: [Java.use('com.autonavi.gbl.route.observer.IRouteResultObserver')], methods: {
        onNewRouteError: {returnType: 'void', argumentTypes: ['com.autonavi.gbl.route.model.PathResultData', 'com.autonavi.gbl.common.path.model.RouteLimitInfo'],
          implementation: function (d) {
            spReport('route_error', {pair: spPending, requestId: String(d.requestId.value), error: d.errorCode.value, local: d.isLocal.value});
            if (d.isLocal.value) setTimeout(spNext, 200);
          }},
        onNewRoute: {returnType: 'void', argumentTypes: ['com.autonavi.gbl.route.model.PathResultData', 'java.util.ArrayList', 'com.autonavi.gbl.common.path.model.RouteLimitInfo'],
          implementation: function (d, paths) {
            const pair = spPending;
            try {
              const Path = Java.use('com.autonavi.gbl.common.path.option.PathInfo');
              const first = Java.use('java.util.ArrayList').$new();
              first.add(Path.$new(Java.cast(paths.get(0), Path)));
              const gson = Java.use('com.google.gson.GsonBuilder').$new().serializeNulls().disableHtmlEscaping().create();
              const json = String(gson.toJson.overload('java.lang.Object').call(gson, Java.use('k.h.e0.d.f.l').f(first)));
              const p0 = Java.cast(paths.get(0), Path);
              spReport('route', {pair, requestId: String(d.requestId.value), local: d.isLocal.value, paths: paths.size(),
                                 length: String(p0.getLength()), time: String(p0.getTravelTime()), export: json});
            } catch (e) { spReport('export_error', {pair, error: String(e), stack: e.stack}); }
            setTimeout(spNext, 300);
          }}
      }}).$new();
      service.addRouteResultObserver(Obs); spHeld.push(Java.retain(service), Java.retain(Obs));
      spReport('ready', {pairs: PLAN_CASE.pairs.length});
      setTimeout(spNext, 3000);
    } catch (e) { spReport('setup_error', {error: String(e), stack: e.stack}); }
  }));
}, 250);
