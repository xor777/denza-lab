/* Raw SDK INPUT fixtures for the unchanged ORIGINAL supplier1/SOMEIPv1 sender.
 * This library defines p2RunCallbackCase(config); loading it does nothing.
 *
 * Trigger rare-active ONLY after the original factory GuideService has produced
 * a live native route and NaviInfo. Pass the ORIGINAL e0/g/NoaState objects and
 * providers path()/navi() retaining those SDK objects, plus explicit native-frame
 * location fields. Run arrival-idle after actual SDK guidance has stopped.
 * Parent capture code owns installation, trigger, immutable fixture provenance,
 * normal driving, arrival/stop, and emulator restoration. This file never reads
 * sender outputs, installs hooks, creates route geometry, or replaces transport.
 *
 * Every step calls public original receipts on MAIN. Nominal JS delays are input
 * fixture scheduling only: actual read/queue/clock/effect times in the original
 * ledger decide which branch ran. No claimed 300ms race is inferred from a plan.
 * rare-active lasts35s; lane-query waits at most120s for actual controlled Z/F completion.
 * arrival-idle lasts6s. cancel() cancels fixture timers only,
 * reset-offcourse lasts20s during actual guidance, with declared SDK DTO seeds
 * and SDK-created RouteOption inputs; same-id e0 reset is not a changed g path.
 * leaving original sender queues intact for the normal capture_end pending audit.
 */
// This optional fixture observer reads only already-emitted original call DTOs.
// Original processing determines completion; none of its bean fields seed inputs.
var p2ObservedCall;
function p2LaneProcessingObserver(){
  const previous=p2ObservedCall,entries=new Map(),active=new Map(),completed=[],responses=[];
  p2ObservedCall=function(row){
    if(typeof previous==='function')previous(row);
    const method=String(row.method||'').split('(')[0],thread=String(row.thread&&row.thread.id);
    if(!['k.h.a0.c.c.a.e0.Z','k.h.a0.c.c.a.e0.F','k.h.a0.c.c.a.f0.i'].includes(method))return;
    if(row.kind==='call_enter'){
      const state={source_seq:row.seq,call_id:row.call_id,parent_call_id:row.parent_call_id,args:row.args||[]};
      entries.set(row.call_id,state);
      if(method==='k.h.a0.c.c.a.e0.F'){state.wires=[];active.set(thread,state);}
      else if(method==='k.h.a0.c.c.a.f0.i'){state.flush=active.get(thread)||null;}
    }else if(row.kind==='call_exit'){
      const state=entries.get(row.call_id);if(!state)throw new Error('lane processing exit without actual entry');
      entries.delete(row.call_id);state.exit={source_seq:row.seq,call_id:row.call_id,error:row.error};
      if(method==='k.h.a0.c.c.a.e0.Z'){responses.push(state);while(responses.length>128)responses.shift();}
      else if(method==='k.h.a0.c.c.a.f0.i'){if(state.flush)state.flush.wires.push(state);}
      else{active.delete(thread);completed.push(state);while(completed.length>64)completed.shift();}
    }
  };
  return {selected(attempts){
    for(const flush of completed){
      if(flush.exit.error!==null)continue;
      for(const wire of flush.wires){
        if(wire.exit.error!==null)continue;
        const beans=wire.args[0]&&wire.args[0].items||[],ids=[];let selectedZ=null;
        for(const bean of beans){
          const fields=bean.fields||{},request=fields.requestId;
          if(!request||request.type!=='i64')continue;
          for(const attempt of attempts){
            if(attempt.observed_lane_request.requestId!==request.decimal||!fields.segmentIndex||fields.segmentIndex.value!==attempt.observed_lane_request.segmentIdx||
                !fields.linkIndex||fields.linkIndex.value!==attempt.observed_lane_request.linkIdx)continue;
            const processed=responses.find(response=>response.exit.error===null&&response.exit.source_seq<flush.source_seq&&
              response.args[0]&&response.args[0].id===attempt.response_input_ref.id&&response.args[1]&&response.args[1].decimal===request.decimal);
            if(processed){ids.push(request.decimal);selectedZ=processed.exit;break;}
          }
        }
        if(ids.length)return {Z:selectedZ,F:flush.exit,wire:wire.exit,request_ids:Array.from(new Set(ids))};
      }
    }
    return null;
  }};
}
function p2RunCallbackCase(config) {
  if (!config || !config.mmt) throw new Error('actual original e0 object required');
  const kind = config.case || 'rare-active';
  if (!['rare-active', 'arrival-idle', 'reset-offcourse', 'lane-query', 'city-odd'].includes(kind)) throw new Error('unknown native callback case');
  const J = name => Java.use(name), List = J('java.util.ArrayList'), Integer = J('java.lang.Integer'), Short = J('java.lang.Short');
  const mmt = config.mmt, timers = [], retained = [], names = [], steps = [];
  let cancelled = false, started = false, failed = false, endRequested = false, endEmitted = false, finishedSteps = 0;
  const deferredStep = {};
  const note = config.report || ((event, data) => { if (typeof report === 'function') report(event, data); });
  const keep = value => { retained.push(value); return value; };
  const value = (object, name, data) => {
    const f = object['_' + name] !== undefined ? object['_' + name] : object[name];
    if (!f || f.value === undefined) throw new Error('missing original DTO field ' + name);
    f.value = data;
  };
  const dto = (klass, fields) => {
    const out = keep(J(klass).$new());
    for (const name of Object.keys(fields || {})) value(out, name, fields[name]);
    return out;
  };
  const array = values => { const out = keep(List.$new()); for (const item of values) out.add(item); return out; };
  const ints = values => array(values.map(n => Integer.valueOf.overload('int').call(Integer, n)));
  const shorts = values => array(values.map(n => Short.valueOf.overload('short').call(Short, n)));
  const lng = n => int64(String(n));
  const c2 = (lon, lat) => dto('com.autonavi.gbl.common.model.Coord2DDouble', {lon, lat});
  const c3 = (lon, lat) => dto('com.autonavi.gbl.common.model.Coord3DDouble', {lon, lat, z: 0});
  const add = (at, name, invoke) => { names.push({at_ms: at, name}); steps.push({at, name, invoke}); };
  const maybeVehicle = speed => { if (config.vehicleSpeed) config.vehicleSpeed(speed); };
  const id = () => {
    if (config.pathId !== undefined) return lng(config.pathId);
    const path = config.path();
    if (!path) throw new Error('live ORIGINAL native path required');
    return path.getPathID();
  };
  if (kind === 'arrival-idle') {
    add(0, 'idle-precondition-and-status-restart', () => {
      if (J('com.autosdk.bussiness.navi.NaviController').getInstance().isNaving())
        throw new Error('arrival-idle requires actual stopped SDK guidance');
      mmt.p(100);
    });
    add(200, 'arrival-counter', () => mmt.onNaviArrive(id(), 0));
    add(5000, 'stop-and-empty-idle-reset', () => mmt.onNavStop());
  } else if (kind === 'reset-offcourse') {
    if (typeof config.path !== 'function' || typeof config.navi !== 'function')
      throw new Error('actual original path()/navi() providers required');
    const loc = config.location;
    if (!loc || loc.matchedLon === undefined || loc.matchedLat === undefined)
      throw new Error('explicit native location fixture required');
    const point = () => c2(loc.matchedLon, loc.matchedLat);
    const pulse = () => {
      const path = config.path(), navi = config.navi();
      if (!path || !navi || !path.isValid()) throw new Error('actual current PathInfo/NaviInfo required at reset pulse');
      mmt.l(navi, path);
    };
    const seed = () => {
      mmt.onNearEnterTunnel(1500); mmt.onEnterTunnel(loc.matchedLon, loc.matchedLat);
      mmt.b(true, false, lng(900), lng(120), array([dto('com.autonavi.gbl.guide.model.NaviCongestionDetailInfo',
        {scopeFlag: 0, status: 3})]));
      mmt.m(true, 0, array([dto('com.autonavi.gbl.guide.model.TrafficEventInfo',
        {type: 201, layer: 1055, laneId: 2, coord2D: point()})]));
      mmt.q(array([dto('com.autonavi.gbl.common.path.model.LightBarItem', {status: 2, length: 120})]), null, true);
      mmt.f(true, ints([0, 1]));
      const now = Math.floor(Date.now() / 1000), path = id();
      mmt.u(true, path, path, lng(0), lng(0), 0, point(), 1, 4,
        dto('com.byd.map.signal.someip.pilot.bean.TrafficLightState', {type: 3, stime: lng(now), etime: lng(now + 25)}));
    };
    const reroute = type => {
      // This is a real SDK-owned native RouteOption, never a getter replacement
      // or a route/path reconstructed from the sender's output.
      const option = keep(J('com.autonavi.gbl.common.path.option.RouteOption').$new());
      if (!option.setRouteType(type)) throw new Error('original RouteOption.setRouteType failed');
      mmt.onReroute(option); pulse();
    };
    add(0, 'seed-before-offcourse', seed);
    add(1000, 'native-route-option-offcourse-type2', () => reroute(2));
    add(2500, 'seed-before-ordinary-reroute', seed);
    add(3500, 'native-route-option-ordinary-type1', () => reroute(1));
    add(5000, 'seed-before-selection-reset', seed);
    add(6000, 'e0-selected-path-reset', () => { mmt.onSelectMainPathStatus(id(), 1); pulse(); });
    add(7500, 'seed-before-path-reset', seed);
    add(8500, 'e0-path-reset-same-actual-id', () => { const path = id(); mmt.onChangeNaviPath(path, path); pulse(); });
    add(10000, 'seed-before-cruise-start', seed);
    add(11000, 'cruise-start-reset', () => { mmt.onCruiseStart(); pulse(); });
    add(12500, 'seed-before-cruise-stop', seed);
    add(13500, 'cruise-stop-reset', () => { mmt.onCruiseStop(); pulse(); });
    add(15000, 'seed-before-simulation-start', seed);
    add(16000, 'simulation-start-reset', () => { mmt.onNaviSimStart(); pulse(); });
  } else if (kind === 'city-odd') {
    const policy = 'actual-current-urid-synthetic-zero-clear-v1';
    if (config.cityPolicy !== policy || JSON.stringify(config.cityExcludeIds) !== '[0]')
      throw new Error('explicit immutable city input policy and synthetic [0] required');
    if (!config.drive || typeof config.path !== 'function' || typeof config.navi !== 'function' || typeof p2Ref !== 'function')
      throw new Error('original g/path/NaviInfo and recorded input identities required');
    const field = (object, name) => {
      const f = object['_' + name] !== undefined ? object['_' + name] : object[name];
      if (!f || !Number.isInteger(f.value) || f.value < 0) throw new Error('actual native index missing: ' + name);
      return f.value;
    };
    const update = stage => {
      const path = config.path(), navi = config.navi();
      if (!path || !navi || !path.isValid()) throw new Error('actual active city path/NaviInfo required');
      const segmentIdx = field(navi, 'curSegIdx'), linkIdx = field(navi, 'curLinkIdx');
      const segment = path.getSegmentInfo(segmentIdx), link = segment && segment.getLinkInfo(linkIdx);
      if (!link) throw new Error('actual current native city link unavailable');
      const city = link.getURID();
      if (!Number.isInteger(city) || city === 0) throw new Error('nonzero actual current URID required for disjoint synthetic control');
      const ids = stage === 'include' ? [city] : stage === 'exclude' ? config.cityExcludeIds.slice() : [];
      const input = ints(ids);
      J('k.h.e0.d.f.m').a(input);
      config.drive.l(navi, path); mmt.l(navi, path);
      return {city_input: {policy, stage,
        origin: stage === 'include' ? 'actual_current_link_urid' : stage === 'exclude' ? 'declared_synthetic_control' : 'explicit_empty_list',
        city_ids: ids, current_urid: city, segment_idx: segmentIdx, link_idx: linkIdx,
        path_ref: p2Ref(path), navi_ref: p2Ref(navi), segment_ref: p2Ref(segment), link_ref: p2Ref(link), input_ref: p2Ref(input)}};
    };
    add(0, 'include-actual-current-city', done => { done(update('include')); });
    add(6000, 'exclude-declared-synthetic-city-zero', done => { done(update('exclude')); });
    add(12000, 'clear-open-city-input', done => { done(update('clear')); });
  } else if (kind === 'lane-query') {
    if (typeof config.path !== 'function' || typeof config.navi !== 'function' || typeof config.laneRequests !== 'function')
      throw new Error('actual path()/navi() and completed laneRequests() providers required');
    const previous = config.laneRequests();
    if (!Array.isArray(previous)) throw new Error('completed lane request array required');
    const oldReads = new Set(previous.map(r => r.read_id));
    const pulse = () => {
      const path = config.path(), navi = config.navi();
      if (!path || !navi || !path.isValid()) throw new Error('actual current PathInfo/NaviInfo required for lane query');
      mmt.l(navi, path);
    };
    add(0, 'reset-and-pulse-original-lane-query', () => { mmt.d(); pulse(); });
    add(250, 'deliver-observed-native-lane-responses', done => {
      const deadline=Date.now()+120000,seenReads=new Set(oldReads),attempts=[],processing=p2LaneProcessingObserver();
      const loc=config.location;
      if(!loc||loc.matchedLon===undefined||loc.matchedLat===undefined)throw new Error('explicit lane fixture point required');
      function poll(){
        if(cancelled||failed)return;
        Java.scheduleOnMainThread(()=>{
          if(cancelled||failed)return;
          try{
            if(Date.now()>=deadline)throw new Error('no actual F completion with a controlled nonempty f0.i lane list before bounded deadline');
            const selected=processing.selected(attempts);
            if(selected){
              done({observed_lane_requests:attempts.map(a=>a.observed_lane_request),
                response_inputs:attempts.map(a=>({requestId:a.observed_lane_request.requestId,read_id:a.observed_lane_request.read_id,response_input_ref:a.response_input_ref})),
                lane_variants:['backExtenLane','extensionLane'],selected_processing:selected});return;
            }
            const requests=config.laneRequests();if(!Array.isArray(requests))throw new Error('completed lane request array required');
            const fresh=requests.filter(r=>!seenReads.has(r.read_id)&&/^[1-9][0-9]*$/.test(String(r.requestId))&&Number(r.requestId)<=2147483647)
              .sort((a,b)=>a.source_seq-b.source_seq);
            for(const request of fresh){
              if(attempts.length>=64)throw new Error('controlled lane response attempt bound exceeded');
              if(typeof request.read_id!=='string'||!request.read_id||!Number.isInteger(request.source_seq)||request.source_seq<0||
                  !Number.isInteger(request.segmentIdx)||request.segmentIdx<0||!Number.isInteger(request.linkIdx)||request.linkIdx<0||request.num!==6)
                throw new Error('complete metadata from actual original lane query required');
              const lane=extension=>dto('com.autonavi.gbl.guide.model.LaneInfo',{
                segmentIdx:request.segmentIdx,linkIdx:request.linkIdx,
                backLane:ints([25,1]),frontLane:ints([1,1]),optimalLane:ints([1]),
                backExtenLane:ints([2,1]),frontExtenLane:ints([]),extensionLane:ints(extension),
                backLaneType:ints([1,0]),frontLaneType:ints([2,2]),point:c2(loc.matchedLon,loc.matchedLat)});
              if(typeof p2Ref!=='function')throw new Error('original recorder input identity helper required');
              const response=array([lane([]),lane([3,1])]);
              const attempt={case:kind,attempt_seq:attempts.length+1,
                observed_lane_request:{read_id:request.read_id,source_seq:request.source_seq,requestId:String(request.requestId),segmentIdx:request.segmentIdx,linkIdx:request.linkIdx,num:request.num},
                response_input_ref:p2Ref(response),lane_variants:['backExtenLane','extensionLane']};
              mmt.onQueryAppointLanesInfo(lng(request.requestId),response);
              seenReads.add(request.read_id);attempts.push(attempt);note('native_callback_fixture_lane_attempt',attempt);
            }
            timers.push(setTimeout(poll,100));
          }catch(error){
            failed=true;note('native_callback_fixture_error',{case:kind,name:'deliver-observed-native-lane-responses',error:String(error),stack:error.stack||null});
          }
        });
      }
      poll();return deferredStep;
    });
  } else {
    if (!config.drive || !config.noa || typeof config.path !== 'function' || typeof config.navi !== 'function' || typeof config.route !== 'function')
      throw new Error('actual g/NoaState objects and original route()/path()/navi() providers required');
    const drive = config.drive, noa = config.noa, loc = config.location;
    const required = ['rawLon','rawLat','matchedLon','matchedLat','heading','roadClass','ownership','linkId'];
    if (!loc || required.some(name => loc[name] === undefined)) throw new Error('complete explicit native location fixture required');
    const point = () => c2(loc.matchedLon, loc.matchedLat);
    const location = (speed, simulated, matchCount, tunnel) => {
      const match = dto('com.autonavi.gbl.pos.model.LocMatchInfo', {
        stPos: c3(loc.matchedLon, loc.matchedLat), course: loc.heading,
        roadCourse: loc.heading, roadLen: 500, roadClass: loc.roadClass,
        formway: 1, linkType: tunnel ? 2 : 0, ownership: loc.ownership, linkId: lng(loc.linkId)
      });
      return dto('com.autonavi.gbl.pos.model.LocInfo', {
        speed, alt: 0, sourType: 0, isSimulate: simulated ? 1 : 0,
        gpsPos: c3(loc.rawLon, loc.rawLat), matchInfo: array(matchCount ? [match] : [])
      });
    };
    const speed = n => drive.onSpeedChange(J('com.autosdk.bussiness.vehicle.BydAutoSpeed$Speed').$new(n, 0, 0, 0));
    const pulse = () => {
      const path = config.path(), navi = config.navi();
      if (!path || !navi || !path.isValid()) throw new Error('actual current PathInfo/NaviInfo required at fixture pulse');
      mmt.l(navi, path); drive.l(navi, path);
    };
    const sub = (type, limit, priority) => dto('com.autonavi.gbl.guide.model.NaviSubCameraExt',
      {subType: type, priority, isMatch: true, speed: shorts(limit)});
    const camera = (type, limits, distance, withLowerLimit) => dto('com.autonavi.gbl.guide.model.NaviCameraExt', {
      cameraId: lng(7001 + type), coord2D: point(), coord3D: c3(loc.matchedLon, loc.matchedLat),
      distance, segmentIndex: 0, linkIndex: 0, distanceToEnd: 100, roadClass: loc.roadClass, isHidden: false,
      subCameras: array(withLowerLimit ? [sub(type, limits, 5), sub(0, [60], 1)] : [sub(type, limits, 5)])
    });
    add(0, 'stationary-gps-and-odd', () => {
      maybeVehicle(0); speed(0); mmt.onCurrentRoadSpeed(80); mmt.onLocInfoUpdate(location(0, false, true, false));
    });
    add(2600, 'positive-speed-cancels-stationary', () => {
      maybeVehicle(36); speed(36); mmt.onLocInfoUpdate(location(36, false, true, false));
    });
    add(3500, 'simulated-and-no-match-return-branches', () => {
      mmt.onLocInfoUpdate(location(36, true, true, false)); mmt.onLocInfoUpdate(location(36, false, false, false));
    });
    add(4300, 'camera25-speed-list-mutation', () => { mmt.c(true, array([camera(25, [90], 300, true)])); });
    add(5200, 'interval-camera26', () => { mmt.c(true, array([camera(26, [80], 200, true)])); });
    add(6000, 'interval-end-and-toll-lane-state', () => { mmt.r(true, 0, 0, 700, shorts([70, 60]), 0); mmt.f(true, ints([0, 1, 3])); });
    add(6800, 'voice-solid-line-and-progress', () => {
      mmt.onPlayTTS(dto('com.autonavi.gbl.guide.model.SoundInfo', {text: '一百米长实线'})); pulse();
    });
    add(7600, 'native-lane-arrays', () => {
      mmt.x(true, false, 0, 0, ints([25, 1]), ints([1, 1]), ints([2, 1]), ints([]),
        ints([]), ints([1, 0]), ints([2, 2]), ints([3, 3]), 0, 0); pulse();
    });
    add(8400, 'lightbar-and-weather', () => {
      mmt.q(array([dto('com.autonavi.gbl.common.path.model.LightBarItem', {status: 2, length: 120}),
        dto('com.autonavi.gbl.common.path.model.LightBarItem', {status: 3, length: 250})]), null, true);
      mmt.onShowNaviWeather(array([dto('com.autonavi.gbl.guide.model.WeatherInfo', {weatherID: 8})]));
    });
    add(9200, 'traffic-light-raw-times-and-type', () => {
      const now = Math.floor(Date.now() / 1000), state = dto('com.byd.map.signal.someip.pilot.bean.TrafficLightState',
        {type: 3, stime: lng(now), etime: lng(now + 25)});
      const path = id();
      mmt.u(true, path, path, lng(0), lng(0), 0, point(), 1, 4, state);
      mmt.onLocInfoUpdate(location(36, false, true, false));
    });
    add(10000, 'shown-construction-and-congestion', () => {
      mmt.m(true, 0, array([dto('com.autonavi.gbl.guide.model.TrafficEventInfo',
        {type: 201, layer: 1055, laneId: 2, coord2D: point()})]));
      mmt.b(true, false, lng(900), lng(120), array([dto('com.autonavi.gbl.guide.model.NaviCongestionDetailInfo',
        {scopeFlag: 0, status: 3})]));
    });
    add(10800, 'road-facilities-and-service-area', () => {
      mmt.a(true, array([dto('com.autonavi.gbl.guide.model.NaviRoadFacility', {type: 5, distance: 400, coord2D: point()})]));
      mmt.s(true, array([dto('com.autonavi.gbl.guide.model.NaviFacility', {type: 0, remainDist: 500, name: '原始服务区'}),
        dto('com.autonavi.gbl.guide.model.NaviFacility', {type: 0, remainDist: 1200, name: '下一服务区'}),
        dto('com.autonavi.gbl.guide.model.NaviFacility', {type: 1, remainDist: 800, name: '收费站'})]));
    });
    add(11600, 'mix-fork-and-cruise-speed', () => {
      mmt.onShowSameDirectionMixForkInfo(array([dto('com.autonavi.gbl.guide.model.MixForkInfo',
        {dist: 400, segmentIndex: 0, roadclass: 5, pos: point()})]));
      mmt.onUpdateCruiseRoadSpeed(dto('com.autonavi.gbl.guide.model.CruiseRoadSpeed', {speed: 55}));
    });
    add(12400, 'tunnel-near-and-enter', () => {
      mmt.onNearEnterTunnel(1500); mmt.onEnterTunnel(loc.matchedLon, loc.matchedLat);
      mmt.onLocInfoUpdate(location(36, false, true, true));
    });
    add(13200, 'tunnel-leave', () => {
      mmt.onNearLeaveTunnel(300); mmt.onLeaveTunnel(loc.matchedLon, loc.matchedLat);
      mmt.onLocInfoUpdate(location(36, false, true, false));
    });
    add(14000, 'cruise-tunnel-state', () => mmt.onUpdateCruisePredictiveTunnelInfo(
      dto('com.autonavi.gbl.guide.model.CruisePredictiveTunnelInfo', {state: 3, entranceDistance: 0, exitDistance: 600})));
    add(14800, 'sleep-with-state-update', () => {
      J('k.h.a0.c.c.a.f0').b().t(false);
      mmt.onShowNaviWeather(array([dto('com.autonavi.gbl.guide.model.WeatherInfo', {weatherID: 9})]));
      mmt.onLocInfoUpdate(location(36, false, true, false)); pulse();
    });
    add(15500, 'native-multipath-component-entry-during-sleep', () => {
      const route = config.route();
      if (!route) throw new Error('original RouteCarResultData required for MULTIPATH fixture');
      Java.use('k.h.e0.d.f.k').d().f(route);
    });
    add(16400, 'wake-and-consumed-weather', () => { J('k.h.a0.c.c.a.f0').b().t(true); mmt.onLocInfoUpdate(location(36, false, true, false)); });
    add(17200, 'actual-switch300ms-then-off', () => {
      noa.onDnpConfigState(true); noa.onDnpSwitchState(true, true, true); noa.onDnpSwitchState(false, true, true);
    });
    add(18400, 'restore-switch', () => noa.onDnpSwitchState(true, true, true));
    add(19200, 'stale-selection-and-error-receipts', () => {
      // Original PathSelectedProxy public callback boundary; no ACK generator.
      // Distinct valid raw g.g/w receipts exercise resend; full network ACK parsing
      // remains the parent's separately captured scripted emulator reply input.
      drive.g(lng(1)); drive.w();
    });
    add(20800, 'empty-clear-branches', () => {
      mmt.c(false, array([])); mmt.m(false, 0, array([])); mmt.a(false, array([]));
      mmt.x(false, false, 0, 0, null, null, null, null, null, null, null, null, -1, -1);
      mmt.b(false, false, lng(0), lng(0), array([])); mmt.f(false, null);
      mmt.q(null, null, false);
      mmt.s(false, null); mmt.r(false, 0, 0, 0, shorts([]), 0);
      mmt.onShowNaviWeather(null); mmt.onShowSameDirectionMixForkInfo(null);
      mmt.u(false, lng(0), lng(0), lng(0), lng(0), 0, point(), 0, 0, null);
    });
    add(22000, 'retained-lightbar-reset', () => mmt.d());
    add(23500, 'final-progress-and-location', () => { pulse(); mmt.onLocInfoUpdate(location(36, false, true, false)); });
  }
  note('native_callback_fixture_plan', {schema: 'denza-native-callback-case-v1', case: kind,
    origin: 'source-controlled raw SDK DTOs delivered to ORIGINAL public receipts', steps: names,
    location: config.location || null, path_id: config.pathId === undefined ? null : String(config.pathId)});
  function maybeFinish() {
    if (!endRequested || endEmitted || cancelled || failed || finishedSteps !== steps.length) return;
    endEmitted = true;
    note('native_callback_fixture_end', {case: kind, status: 'scheduled-inputs-complete'});
    if (typeof p2CallbackInputCompleted !== 'undefined') p2CallbackInputCompleted = true;
  }
  function start() {
    if (started || cancelled) return;
    started = true;
    for (const step of steps) timers.push(setTimeout(() => {
      if (cancelled) return;
      Java.scheduleOnMainThread(() => {
        if (cancelled) return;
        let completed = false, returned = false, doneRequested = false, details = null;
        const commit = () => {
          if (completed || cancelled || !returned || !doneRequested) return;
          completed = true;
          note('native_callback_fixture_step', Object.assign({case: kind, name: step.name, status: 'delivered'}, details || {}));
          finishedSteps++;
          maybeFinish();
        };
        const done = extra => {
          if (completed || cancelled || doneRequested) return;
          doneRequested = true; details = extra; commit();
        };
        try {
          const result = step.invoke(done);
          returned = true;
          if (result !== deferredStep) done();
          commit();
        } catch (error) {
          failed = true;
          note('native_callback_fixture_error', {case: kind, name: step.name, error: String(error), stack: error.stack || null});
        }
      });
    }, step.at));
    timers.push(setTimeout(() => { endRequested = true; maybeFinish(); }, kind === 'arrival-idle' ? 6000 : (kind === 'reset-offcourse' || kind === 'city-odd') ? 20000 : 35000));
  }
  const handle = {case: kind, steps: names, start, cancel() { cancelled = true; for (const timer of timers) clearTimeout(timer); }};
  if (!config.deferStart) start();
  return handle;
}

/* Minimal parent setup adapter. Call once after the base fixture is installed:
 * p2AttachCallbackCase({case:selected, seed:()=>rtSeed, navi:()=>p2LastNavi,
 *   location:explicitFixtureLocation, vehicleSpeed:kmh=>{p2FixtureVehicleSpeedKmh=kmh;}})
 * Parent replaces the base observer's exact `const n=Java.cast(items.get(i),...);`
 * site with the same statement followed by `p2LastNavi=Java.retain(n);` and adds
 * an optional provider-stub override. Those are observation/fixture inputs only.
 * No p2LastNavi can be built from a sender packet or from the port.
 */
function p2AttachCallbackCase(config) {
  if (!config || typeof config.seed !== 'function' || typeof config.navi !== 'function')
    throw new Error('original SDK seed()/navi() providers required');
  const selected = config.case || 'rare-active', timeout = config.waitMs || 60000;
  if (!['rare-active', 'arrival-idle', 'reset-offcourse', 'lane-query', 'city-odd'].includes(selected) || timeout < 1000 || timeout > 120000)
    throw new Error('bounded callback-case configuration required');
  const note = config.report || ((event, data) => { if (typeof report === 'function') report(event, data); });
  let cancelled = false, active = false, handle = null, lastPathId = null, deadline = Date.now() + timeout, timer = null;
  function poll() {
    if (cancelled || handle) return;
    Java.perform(() => {
      try {
        const naving = Java.use('com.autosdk.bussiness.navi.NaviController').getInstance().isNaving();
        const seed = config.seed(), navi = config.navi();
        if (naving) {
          active = true;
          if (seed) { const path = seed.getCurrentFocusPath(); if (path) lastPathId = String(path.getPathID()); }
        }
        if (!seed || !navi || !active || (selected !== 'arrival-idle' && !naving) ||
            (selected === 'arrival-idle' && (naving || lastPathId === null))) {
          if (Date.now() >= deadline) throw new Error('original guidance/case precondition not observed before timeout');
          timer = setTimeout(poll, 250); return;
        }
        const link = Java.use('k.h.e0.b.d').e();
        let drive = null;
        // Resolve the actual field by Java type, never a JADX-renamed field alias.
        for (const field of link.getClass().getDeclaredFields()) {
          if (String(field.getType().getName()) === 'k.h.e0.c.g') {
            field.setAccessible(true);
            const original = field.get(link);
            if (original !== null) drive = Java.cast(original, Java.use('k.h.e0.c.g'));
          }
        }
        if ((selected === 'rare-active' || selected === 'city-odd') && drive === null) {
          if (Date.now() >= deadline) throw new Error('original navigation-owned g listener absent');
          timer = setTimeout(poll, 250); return;
        }
        handle = p2RunCallbackCase({case: selected, deferStart: true, mmt: Java.use('k.h.a0.c.c.a.e0').A(),
          drive, noa: Java.use('k.h.e0.b.f').m(), route: config.seed, path: () => config.seed().getCurrentFocusPath(),
          navi: config.navi, pathId: selected === 'arrival-idle' ? lastPathId : undefined,
          location: config.location, vehicleSpeed: config.vehicleSpeed, laneRequests: config.laneRequests,
          cityPolicy: config.cityPolicy, cityExcludeIds: config.cityExcludeIds, report: note});
        note('native_callback_fixture_attached', {case: selected,
          origin: 'live original SDK route/NaviInfo and navigation-owned g receiver', original_path_id: lastPathId});
        handle.start();
      } catch (error) {
        cancelled = true;
        note('native_callback_fixture_error', {case: selected, stage: 'setup', error: String(error), stack: error.stack || null});
      }
    });
  }
  timer = setTimeout(poll, 250);
  return {cancel() { cancelled = true; if (timer !== null) clearTimeout(timer); if (handle) handle.cancel(); }};
}
