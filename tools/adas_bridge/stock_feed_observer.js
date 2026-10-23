/* Emulator-only: the stock map's own map->ADAS feed at the SOME/IP client boundary.
 * Derived from research/stock-map-native/drive_replay_observer.js (kept unchanged for
 * its pinned evidence) with the car's guidance flow and a recorder at
 * ts.car.someip.sdk.SomeIpServerProxyImpl. Prepend
 *   const REROUTE_CASE={...};   (as map_build_emulator.py reroute builds it)
 *   const ADAS_BENCH={answers:true|false, answerDelayMs:80, arrive:true};
 * and run through tools/adas_bridge/stock_trace.py.
 *
 * What is NOT original, and why:
 *  - SomeIpServerProxyImpl.isServiceReady/startSomeIpService/fireEvent: the emulator
 *    has no com.ts.car.someip.service; these play the bound IVI Binder (ready, start=0,
 *    fire=0) and record every fire: topic, payload (base64), monotonic ms, thread.
 *    Nothing leaves the process; no SOME/IP exists on the emulator.
 *  - NOA flags (k.h.e0.c.j.i0.b.a a,b,d,e,p; f0.t(true)) are set to the values the
 *    car's own NoaStateManager logs at start-up ("init noaSwitch:true cNoaSwitch:true
 *    hNoaSwitch:true noSleepStatus:true", DnpService:true) because BYDAuto is absent.
 *  - The presenter's part, in the car's order (drive logs 2026-10-23): Navi_onCreate
 *    (k.h.e0.b.d.i) and Navi_onStart (.k), NaviController.setNaviPath + startNavi, then
 *    GaoDeSignalManager.setRouteCarResultData (NaviService.onUpdateRouteCarResultData);
 *    on a reroute result setRouteCarResultData BEFORE setNaviPath; on arrival
 *    stopNaviOnArrive, stopNavi, Navi_onDestroy (.j).
 *  - ADAS_BENCH.answers: a scripted MMTPathSelected (44/0x8002, statusCode 200, the
 *    requested pathId) is delivered through SomeIpClient.onSomeIpEvent, the client
 *    callback the car's Binder calls, answerDelayMs after each sendPathSelected
 *    (the car answered in 40-120 ms). Labelled fake; never car evidence.
 */
const rtHeld = [];
let rtSeed=null, rtApplicationCallback=null, rtLastLoc=null, rtOffrouteAt=0, abStopped=false;
const rtClock = new NativeFunction(Module.getExportByName('libc.so', 'clock_gettime'), 'int', ['int', 'pointer']);
function rtNow() {
  const p = Memory.alloc(16);
  if (rtClock(1, p) !== 0) throw new Error('clock_gettime failed');
  return p.readS64().toNumber() * 1000 + p.add(8).readS64().toNumber() / 1e6;
}
function rtReport(kind, fields) { report('reroute_' + kind, Object.assign({mono_ms: rtNow()}, fields)); }
function abReport(kind, fields) { report('adas_' + kind, Object.assign({mono_ms: rtNow()}, fields)); }
function abThread() { try { return String(Java.use('java.lang.Thread').currentThread().getName()); } catch (e) { return null; } }
function abB64(bytes) { return bytes === null ? null : String(Java.use('android.util.Base64').encodeToString(bytes, 2)); }
function abField(cls, name) { return cls['_' + name] !== undefined ? cls['_' + name] : cls[name]; }
function rtOption(o) {
  const out={constraints:o.getConstrainCode(), type:o.getRouteType(), requestId:String(o.getRouteReqId()),
          strategy:o.getRouteStrategy(), responseType:o.getCalcRouteRespType()};
  try {
    const pois=o.getPOIForRequest(),start=pois.getPointSize(0)>0?pois.getPoint(0,0):null;
    out.start={pos:start?[start.realPos.value.lon.value,start.realPos.value.lat.value]:null,carNow:rtLastLoc,tick:rtTick};
  } catch(e) { out.startError=String(e); }
  return out;
}
function rtResult(d) {
  return {requestId:String(d.requestId.value), type:d.type.value, error:d.errorCode.value,
          local:d.isLocal.value, category:d.routeRespCategory.value, done:d.respDone.value};
}
function rtWrap(cls, method, args, before, after) {
  try {
    const c = Java.use(cls), f = c[method].overload.apply(c[method], args);
    f.implementation = function () {
      const values = Array.from(arguments), started = rtNow();
      try { if (before) before.apply(this, values); } catch(e) { rtReport('hook_error', {method:cls+'.'+method,error:String(e)}); }
      const result = f.call.apply(f, [this].concat(values));
      try { if (after) after.call(this, result, values, rtNow()-started); } catch(e) { rtReport('hook_error', {method:cls+'.'+method,error:String(e)}); }
      return result;
    };
    rtReport('hook_installed', {method:cls+'.'+method});
  } catch(e) { rtReport('hook_install_error', {method:cls+'.'+method,error:String(e)}); }
}

/* --- The SOME/IP client boundary: the bound IVI Binder, recorded. --- */
const AB_SELECT_TOPIC = 1126093183352833, AB_CONFIRM_TOPIC = 1126088888320002;
let abFires = 0;
function abVarint(a, i) { let v = 0, m = 1; for (;;) { const c = a[i++] & 255; v += (c & 127) * m; if (c < 128) return [v, i]; m *= 128; } }
function abPutVarint(out, v) { while (v > 127) { out.push((v % 128) | 128); v = Math.floor(v / 128); } out.push(v); }
function abAnswer(bytes) {
  // NaviPathUserSelectStsNotify{1:{3:pathIDReq}} -> NaviPathUserSelectStsConfirmNotify{1:{3:pathIDResp,4:200}};
  // plain protobuf in JS (stock pathIds are small positive integers).
  if (!ADAS_BENCH.answers) return;
  let id = null;
  try {
    const a = Array.from(bytes, b => b & 255);
    let [tag, i] = abVarint(a, 0), size;
    if (tag !== 10) throw new Error('outer tag ' + tag);
    [size, i] = abVarint(a, i);
    const end = i + size;
    while (i < end) {
      let key, value; [key, i] = abVarint(a, i);
      if ((key & 7) !== 0) throw new Error('unexpected wire type in selection');
      [value, i] = abVarint(a, i);
      if (key >> 3 === 3) id = value;
    }
    if (id === null) throw new Error('no pathIDReq');
  } catch (e) { abReport('fake_answer_error', {stage: 'parse', error: String(e)}); return; }
  setTimeout(() => Java.perform(() => {
    try {
      const inner = [0x18]; abPutVarint(inner, id); inner.push(0x20); abPutVarint(inner, 200);
      const msg = [0x0a]; abPutVarint(msg, inner.length); for (const b of inner) msg.push(b);
      const signed = msg.map(b => b > 127 ? b - 256 : b);
      const D = Java.use('ts.car.someip.sdk.common.SomeIpData');
      const data = D.$new.overload('long', '[B').call(D, AB_CONFIRM_TOPIC, Java.array('byte', signed));
      abReport('fake_answer', {topic: String(AB_CONFIRM_TOPIC), pathIDResp: String(id), statusCode: 200, payload: abB64(data.getPayload()), fake: true});
      Java.use('com.autosdk.bussiness.pilot.someip.SomeIpClient').getInstance().onSomeIpEvent(data);
    } catch (e) { abReport('fake_answer_error', {stage: 'deliver', error: String(e), stack: e.stack}); }
  }), ADAS_BENCH.answerDelayMs || 80);
}
Java.perform(() => {
  try {
    const P = Java.use('ts.car.someip.sdk.SomeIpServerProxyImpl');
    P.isServiceReady.implementation = function () { return true; };
    P.startSomeIpService.implementation = function (t) { abReport('start_service', {topic: String(t), thread: abThread()}); return 0; };
    P.stopSomeIpService.implementation = function (t) { abReport('stop_service', {topic: String(t), thread: abThread()}); return 0; };
    P.fireEvent.implementation = function (d) {
      const topic = d.getTopic(), bytes = d.getPayload();
      abReport('fire', {seq: abFires++, topic: String(topic), payload: abB64(bytes), bytes: bytes === null ? 0 : bytes.length,
                        thread: abThread(), wall_ms: Date.now()});
      if (String(topic) === String(AB_SELECT_TOPIC)) abAnswer(bytes);
      return 0;
    };
    abReport('boundary_installed', {cls: 'ts.car.someip.sdk.SomeIpServerProxyImpl', answers: !!ADAS_BENCH.answers});
  } catch (e) { abReport('boundary_install_error', {error: String(e)}); }
  // Client-side requests (13/method 0x0001 DNP switch, 0x0002 ODD) are recorded, original behaviour kept.
  rtWrap('com.autosdk.bussiness.pilot.someip.SomeIpClient', 'sendSomeIpNoResponseData', ['ts.car.someip.sdk.common.SomeIpData'], function (d) {
    abReport('client_send', {topic: String(d.getTopic()), payload: abB64(d.getPayload()), thread: abThread(), response: false});
  });
  rtWrap('com.autosdk.bussiness.pilot.someip.SomeIpClient', 'sendSomeIpWithResponseData', ['ts.car.someip.sdk.common.SomeIpData', 'ts.car.someip.sdk.common.SomeIpData'], function (d) {
    abReport('client_send', {topic: String(d.getTopic()), payload: abB64(d.getPayload()), thread: abThread(), response: true});
  });
  rtWrap('com.byd.map.signal.GaoDeSignalManager', 'setMapState', ['int'], function (s) { abReport('map_state', {state: s, thread: abThread()}); });
  // BydAutoNoaProxy (k.h.e0.a.r) reads BYDAuto, absent here: the values the car's own
  // log reports (drive-02): NOA/HNOA/city config and switches true, memory config true,
  // sleep switch 0, ADAS supplier (getAdasType) 1, tip island (G) 2.
  try {
    const R = Java.use('k.h.e0.a.r'), values = {A: true, E: true, H: true, w: true, x: true, z: true, C: true, s: 0, t: 1, G: 2};
    for (const name of Object.keys(values)) R[name].overload().implementation = function () { return values[name]; };
    abReport('vehicle_noa_fixture', {values});
  } catch (e) { abReport('vehicle_noa_fixture_error', {error: String(e)}); }
});

Java.perform(() => {
  if (REROUTE_CASE.vehicleSpeed) {
    const speed=Java.use('com.autosdk.bussiness.vehicle.BydAutoSpeed');
    for(const method of ['realSpeed','currentSpeed']) {
      speed[method].overload().implementation=function() {
        return rtTick ? REROUTE_CASE.samples[Math.min(rtTick-1,REROUTE_CASE.samples.length-1)].speed_mps*3.6 : 0;
      };
    }
    rtReport('vehicle_provider_fixture',{units:'km/h',sources:['realSpeed','currentSpeed']});
  }
  const nc = 'com.autosdk.bussiness.navi.NaviController';
  const ro = 'com.autonavi.gbl.common.path.option.RouteOption';
  const pd = 'com.autonavi.gbl.route.model.PathResultData';
  const li = 'com.autonavi.gbl.common.path.model.RouteLimitInfo';
  rtWrap('com.autonavi.gbl.route.RouteService','init',['com.autonavi.gbl.route.model.RouteInitParam'],function(p) {
    rtReport('route_init', {auto:p.rerouteParam.value.enableAutoReroute.value});
  });
  rtWrap(nc,'requestTbtRoute',[ro,'boolean','boolean'],function(o) {rtReport('request_tbt_enter',rtOption(o));},function(r) {rtReport('request_tbt_leave',{requestId:String(r)});});
  rtWrap(nc,'onReroute',[ro],function(o) {rtOffrouteAt=rtNow();rtReport('guide_offroute',rtOption(o));});
  rtWrap(nc,'onRerouteInfo',['com.autonavi.gbl.route.model.BLRerouteRequestInfo'],function(i) {
    rtReport('automatic_request',Object.assign(rtOption(i.option.value),{requestId:String(i.requestId.value)}));
    if(rtSeed && rtApplicationCallback) {
      Java.use('com.autosdk.bussiness.navi.route.RouteRequestController').getInstance().addReRouteCallBack(rtSeed,i,rtApplicationCallback);
      rtReport('stock_callback_bound',{requestId:String(i.requestId.value)});
    }
  });
  for (const cls of [nc,'com.autosdk.bussiness.navi.route.BydRouteResultObserver']) {
    rtWrap(cls,'onNewRouteError',[pd,li],function(d) {rtReport('result_error',Object.assign({receiver:cls},rtResult(d)));});
    rtWrap(cls,'onNewRoute',[pd,'java.util.ArrayList',li],function(d,p) {rtReport('result_success',Object.assign({receiver:cls,paths:p.size()},rtResult(d)));});
  }
  if (REROUTE_CASE.onlineWaitMs) {
    // The car's online attempt fails only after 15000 ms (control key 373); run the
    // original offline fallback that long after the native off-route signal.
    const B=Java.use('com.autosdk.bussiness.navi.route.BydRouteResultObserver');
    const f=B.calculateRouteOffline.overload(pd,'com.autosdk.bussiness.navi.route.callback.RouteResultCallBackProxy');
    let pass=false;
    f.implementation=function(d,p) {
      if(pass)return f.call(this,d,p);
      const self=Java.retain(this),dd=Java.retain(d),pp=Java.retain(p),wait=Math.max(0,rtOffrouteAt+REROUTE_CASE.onlineWaitMs-rtNow());
      rtHeld.push(self,dd,pp);rtReport('offline_fallback_delayed',Object.assign({wait_ms:wait},rtResult(d)));
      setTimeout(()=>Java.perform(()=>Java.scheduleOnMainThread(()=>{
        rtReport('offline_fallback',Object.assign({delayed:true,tick:rtTick},rtResult(dd)));
        pass=true;try{self.calculateRouteOffline(dd,pp);}finally{pass=false;}
      })),wait);
    };
  }
  rtWrap('com.autonavi.gbl.guide.GuideService','setNaviPath',['com.autonavi.gbl.guide.model.NaviPath'],function(p) {rtReport('guide_set_path',{type:p.type.value,paths:p.vecPaths.value.size()});});
});

function abSeed(paths, d) {
  const seed=Java.use('com.autosdk.bussiness.navi.route.model.RouteCarResultData').$new();
  try {
    const info=Java.use('com.autosdk.bussiness.navi.route.model.PathResultDataInfo').$new();
    info.requestId.value=d.requestId.value;info.type.value=d.type.value;info.isLocal.value=d.isLocal.value;
    seed.setPathResultDataInfo(info);
  } catch(e) { abReport('seed_request_id_error',{error:String(e)}); }
  const factory=Java.use('com.autosdk.bussiness.common.POIFactory'),Geo=Java.use('com.autosdk.bussiness.common.GeoPoint');
  const poi=(n,p)=>factory.createPOI.overload('java.lang.String','com.autosdk.bussiness.common.GeoPoint').call(factory,n,Geo.$new(p[0],p[1]));
  seed.setFromPOI(poi('Emulator start',REROUTE_CASE.route_start_sdk));
  seed.setToPOI(poi('Emulator destination',REROUTE_CASE.route_end_sdk));
  seed.setMidPois(Java.use('java.util.ArrayList').$new());seed.setRouteStrategy(32);seed.setRouteConstrainCode(260);
  seed.setSplitRequest(false);seed.setRouteSource(1);seed.setPathInfos(paths);seed.setFocusIndex(0);
  return Java.retain(seed);
}
function abStop(reason) {
  if(abStopped)return; abStopped=true;
  Java.perform(()=>Java.scheduleOnMainThread(()=>{
    try {
      const ctl=Java.use('com.autosdk.bussiness.navi.NaviController').getInstance();
      const arrive=reason==='arrive';
      abReport('presenter_stop',{reason,result:arrive?ctl.stopNaviOnArrive():ctl.stopNavi()});
      setTimeout(()=>Java.perform(()=>Java.scheduleOnMainThread(()=>{
        try {
          if(arrive)abReport('presenter_stop_again',{result:ctl.stopNavi()});
          Java.use('k.h.e0.b.d').e().j();abReport('navi_on_destroy',{});
        } catch(e){abReport('presenter_stop_error',{error:String(e)});}
      })),arrive?180:20);
    } catch(e){abReport('presenter_stop_error',{error:String(e),stack:e.stack});}
  }));
}
let rtReady=false, rtStarted=false, rtPoi=null, rtTick=0;
function rtStartGuidance(paths, d) {
  if(rtStarted)return; rtStarted=true;
  const copy=Java.use('java.util.ArrayList').$new(), Path=Java.use('com.autonavi.gbl.common.path.option.PathInfo');
  for(let i=0;i<paths.size();i++)copy.add(Path.$new(Java.cast(paths.get(i),Path)));
  const held=Java.retain(copy);rtHeld.push(held);
  const seed=abSeed(held,d);rtHeld.push(seed);
  const Callback=Java.registerClass({name:'dev.denza.adasbench.RerouteApplicationCallback',implements:[Java.use('com.autosdk.bussiness.navi.route.callback.IRouteResultCallBack')],methods:{
    callback:{returnType:'void',argumentTypes:['com.autosdk.bussiness.navi.route.model.IRouteResultData','boolean'],implementation:function(result,local) {
      const car=Java.cast(result,Java.use('com.autosdk.bussiness.navi.route.model.RouteCarResultData'));
      rtReport('stock_callback_success',{local,paths:car.getPathInfos().size(),length:String(car.getCurrentFocusPath().getLength())});
      const saved=Java.retain(car);rtHeld.push(saved);
      Java.scheduleOnMainThread(()=>{
        try {
          if(abStopped)return;
          const loc=rtLastLoc;if(!loc)throw new Error('No accepted position');
          const factory=Java.use('com.autosdk.bussiness.common.POIFactory'),Geo=Java.use('com.autosdk.bussiness.common.GeoPoint');
          saved.setFromPOI(factory.createPOI.overload('java.lang.String','com.autosdk.bussiness.common.GeoPoint').call(factory,'Emulator current position',Geo.$new(loc[0],loc[1])));
          const points=Java.use('com.autonavi.gbl.common.path.option.POIForRequest').$new();
          for(const entry of [[0,loc],[2,REROUTE_CASE.route_end_sdk]]) {
            const poi=Java.use('com.autonavi.gbl.common.path.model.POIInfo').$new();poi.realPos.value=Java.use('com.autonavi.gbl.common.model.Coord2DDouble').$new(entry[1][0],entry[1][1]);poi.type.value=entry[0]===0?0:1;points.addPoint(entry[0],poi);
          }
          const selected=Java.use('java.util.ArrayList').$new(),Path=Java.use('com.autonavi.gbl.common.path.option.PathInfo');
          selected.add(Path.$new(saved.getCurrentFocusPath()));
          const np=Java.use('com.autonavi.gbl.guide.model.NaviPath').$new();np.vecPaths.value=selected;np.point.value=points;np.mainIdx.value=0;np.type.value=saved.getPathResultDataInfo().type.value;np.strategy.value=saved.getRouteStrategy();
          rtHeld.push(Java.retain(selected),Java.retain(points),Java.retain(np));
          // Car order (16:52:07.799/.800): onRouteDataUpdate before setNaviPath.
          Java.use('com.byd.map.signal.GaoDeSignalManager').getInstance().setRouteCarResultData(saved);
          Java.use('com.autosdk.bussiness.navi.NaviController').getInstance().setNaviPath(np,0);
          rtSeed=saved;
          rtReport('handover',{type:np.type.value,path:String(Java.use('com.autosdk.bussiness.navi.NaviController').getPathId(saved.getCurrentFocusPath())),length:String(saved.getCurrentFocusPath().getLength()),point:loc});
        }catch(e){rtReport('handover_error',{error:String(e),stack:e.stack});}
      });
    }},
    errorCallback:{returnType:'void',argumentTypes:['int','java.lang.String','boolean'],implementation:function(error,message,local) {rtReport('stock_callback_error',{error,message:String(message),local});}},
    errorCallback2:{returnType:'void',argumentTypes:['long','int','int','java.lang.String','boolean'],implementation:function(id,type,error,message,local) {rtReport('stock_callback_error2',{requestId:String(id),type,error,message:String(message),local});}}
  }}).$new();
  rtSeed=seed;rtApplicationCallback=Java.retain(Callback);rtHeld.push(rtApplicationCallback);
  Java.scheduleOnMainThread(() => {
    try {
      const link=Java.use('k.h.e0.b.d').e();
      link.i();link.k();abReport('navi_on_create_start',{});
      const ctl=Java.use('com.autosdk.bussiness.navi.NaviController').getInstance();
      const guide=Java.cast(Java.use('com.autonavi.gbl.servicemanager.ServiceMgr').getServiceMgrInstance().getBLService(16),Java.use('com.autonavi.gbl.guide.GuideService'));
      ctl.initGuideNaviParam();
      const p=Java.use('com.autonavi.gbl.guide.model.NaviPath').$new();p.vecPaths.value=held;p.point.value=rtPoi;p.type.value=1;p.mainIdx.value=0;
      rtHeld.push(Java.retain(guide),Java.retain(p));
      const Obs=Java.registerClass({name:'dev.denza.adasbench.GuideObserver',superClass:Java.use('com.autosdk.drive.navi.NaviObserverAdapter'),methods:{
        onUpdateNaviInfo:{returnType:'void',argumentTypes:['java.util.ArrayList'],implementation:function(items) {
          const out=[];
          for(let i=0;i<items.size();i++){const n=Java.cast(items.get(i),Java.use('com.autonavi.gbl.guide.model.NaviInfo'));out.push({path:String(n.pathID.value),segment:n.curSegIdx.value,link:n.curLinkIdx.value,remain:n.routeRemain.value.dist.value,remainTime:n.routeRemain.value.time.value});}
          rtReport('guidance_update',{items:out,tick:rtTick});
        }},
        onNaviArrive:{returnType:'void',argumentTypes:['long','int'],implementation:function(id,type) {
          abReport('navi_arrive',{id:String(id),type,tick:rtTick});if(ADAS_BENCH.arrive!==false)abStop('arrive');
        }},
        onNaviStop:{returnType:'void',argumentTypes:['long','int'],implementation:function(id,type) {abReport('navi_stop',{id:String(id),type,tick:rtTick});}}
      }}).$new();guide.addNaviObserver(Obs);rtHeld.push(Java.retain(Obs));
      ctl.setNaviPath(p,0);
      abReport('guidance_start',{result:ctl.startNavi(0,0)});
      // NaviService.onUpdateRouteCarResultData, after startNavi as on the car (16:43:46.965).
      Java.use('com.byd.map.signal.GaoDeSignalManager').getInstance().setRouteCarResultData(seed);
      abReport('route_data_update',{requestId:String(seed.getRequestId())});
      setTimeout(()=>abStop('timer'),REROUTE_CASE.guideMs||125000);
    } catch(e) {rtReport('guidance_error',{error:String(e),stack:e.stack});}
  });
}
const rtStartTimer=setInterval(()=>{
  if(phase!=='rendering'||rtReady)return;rtReady=true;clearInterval(rtStartTimer);
  Java.perform(()=>Java.scheduleOnMainThread(()=>{
    try {
      const flags=Java.use('k.h.e0.c.j.i0.b.a');
      for(const n of ['a','b','d','e','p'])abField(flags,n).value=true;
      abField(flags,'o').value=false;
      Java.use('k.h.a0.c.c.a.f0').b().t(true);
      const server=Java.use('com.autosdk.bussiness.pilot.someip.SomeIpServer').getInstance();
      const listeners=server.serverInitListeners.value.size();
      if(!listeners){Java.use('k.h.e0.b.d').e().f();}
      const ids=[3096254809047040,3096409430228992,3096418020294656,3239172026531840];
      if(!ids.every(t=>server.getSomeIpServiceState(t)))server.onChangeServiceStatus(true);
      if(!ids.every(t=>server.getSomeIpServiceState(t)))Java.use('k.h.e0.b.d').e().o();
      abReport('car_state',{flags:'a,b,d,e,p=true o=false; f0.t(true)',listenersBefore:listeners,
                            services:ids.map(t=>[String(t),server.getSomeIpServiceState(t)])});
      const mgr=Java.use('com.autonavi.gbl.servicemanager.ServiceMgr').getServiceMgrInstance();
      const service=Java.cast(mgr.getBLService(17),Java.use('com.autonavi.gbl.route.RouteService'));
      const pos=Java.cast(mgr.getBLService(13),Java.use('com.autonavi.gbl.pos.PosService'));
      const first=REROUTE_CASE.samples[0];
      pos.setContextPos(Java.use('com.autonavi.gbl.common.model.Coord3DDouble').$new(first.sdk[0],first.sdk[1],0),first.heading_degrees_true);
      const LocObs=Java.registerClass({name:'dev.denza.adasbench.LocObserver',implements:[Java.use('com.autonavi.gbl.pos.observer.IPosLocInfoObserver')],methods:{onLocInfoUpdate(info) {
        try {
          rtLastLoc=[info.gpsPos.value.lon.value,info.gpsPos.value.lat.value];const list=info.matchInfo.value;
          const m=list.size()?Java.cast(list.get(0),Java.use('com.autonavi.gbl.pos.model.LocMatchInfo')):null;
          rtReport('position',{tick:rtTick,bind:info.bindFlag.value,speed:info.speed.value,
            match:m?{on:m.isOnGuideRoad.value,segment:m.segmCur.value,link:m.linkCur.value,path:String(m.pathID.value),roadClass:m.roadClass.value,formway:m.formway.value}:null});
        } catch(e) {rtReport('position_error',{error:String(e)});}
      }}}).$new();pos.addLocInfoObserver(LocObs,0);
      const RouteObs=Java.registerClass({name:'dev.denza.adasbench.RouteObserver',implements:[Java.use('com.autonavi.gbl.route.observer.IRouteResultObserver')],methods:{
        onNewRouteError:{returnType:'void',argumentTypes:['com.autonavi.gbl.route.model.PathResultData','com.autonavi.gbl.common.path.model.RouteLimitInfo'],implementation:function(d,l) {rtReport('observed_error',rtResult(d));}},
        onNewRoute:{returnType:'void',argumentTypes:['com.autonavi.gbl.route.model.PathResultData','java.util.ArrayList','com.autonavi.gbl.common.path.model.RouteLimitInfo'],implementation:function(d,p,l) {
          rtReport('observed_success',rtResult(d));rtStartGuidance(p,d);
        }}
      }}).$new();service.addRouteResultObserver(RouteObs);rtHeld.push(Java.retain(pos),Java.retain(LocObs),Java.retain(service),Java.retain(RouteObs));
      const feed=setInterval(()=>Java.perform(()=>{
        if(rtTick>=REROUTE_CASE.samples.length){clearInterval(feed);rtReport('feed_done',{ticks:rtTick});return;}
        const sample=REROUTE_CASE.samples[rtTick++],data=Java.use('com.autonavi.gbl.pos.model.LocSignData').$new();data.dataType.value=16;
        const gnss=data.gnss.value;gnss.point.value=Java.use('com.autonavi.gbl.common.model.Coord2DDouble').$new(sample.sdk[0],sample.sdk[1]);gnss.isEncrypted.value=1;gnss.status.value=65;gnss.num.value=20;gnss.hdop.value=1;gnss.accuracy.value=3;gnss.course.value=sample.heading_degrees_true;gnss.speed.value=sample.speed_mps*3.6;
        const now=new Date();gnss.year.value=now.getUTCFullYear();gnss.month.value=now.getUTCMonth()+1;gnss.day.value=now.getUTCDate();gnss.hour.value=now.getUTCHours();gnss.minute.value=now.getUTCMinutes();gnss.second.value=now.getUTCSeconds();gnss.millisecond.value=now.getUTCMilliseconds();gnss.tickTime.value=Java.use('java.math.BigInteger').$new(String(Java.use('android.os.SystemClock').elapsedRealtime()));data.gnss.value=gnss;
        rtReport('gnss',{tick:rtTick,sdk:sample.sdk,speed_mps:sample.speed_mps,course:sample.heading_degrees_true});pos.setSignInfo(data);
      }),1000);
      setTimeout(()=>Java.perform(()=>Java.scheduleOnMainThread(()=>{
        try {
          const pois=Java.use('com.autonavi.gbl.common.path.option.POIForRequest').$new();
          for(const entry of [[0,REROUTE_CASE.route_start_sdk],[2,REROUTE_CASE.route_end_sdk]]) {
            const p=Java.use('com.autonavi.gbl.common.path.model.POIInfo').$new();p.realPos.value=Java.use('com.autonavi.gbl.common.model.Coord2DDouble').$new(entry[1][0],entry[1][1]);p.type.value=entry[0]===0?0:1;p.name.value='ADAS bench';pois.addPoint(entry[0],p);
          }
          rtPoi=Java.retain(pois);const o=Java.use('com.autonavi.gbl.common.path.option.RouteOption').$new();o.setPOIForRequest(pois);o.setConstrainCode(260);o.setRouteStrategy(32);o.setCalcRouteRespType(1);rtHeld.push(Java.retain(o),rtPoi);
          rtReport('initial_request',{requestId:String(Java.use('com.autosdk.bussiness.navi.NaviController').getInstance().requestTbtRoute(o,false,false))});
        } catch(e) {rtReport('request_error',{error:String(e),stack:e.stack});}
      })),3000);
      rtReport('ready',{samples:REROUTE_CASE.samples.length});
    } catch(e) {rtReport('setup_error',{error:String(e),stack:e.stack});}
  }));
},250);
