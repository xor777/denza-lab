"""Capture the original sender's full causal ledger on an explicitly owned emulator."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import json
from pathlib import Path
import tempfile

from tools.adas_bridge import stock_trace
from .capture import APK, digest, emulator_serial, original_artifacts
from . import scenario_gate
from .route_change_audit import STOP_POLICY as ROUTE_STOP_POLICY

HERE = Path(__file__).resolve().parent
TERMINAL_POLICY = 'quiet_no_due_or_overdue_pending_v1'
INPUT_BOUNDARY_POLICY = 'remove_position_observer_after_fixture_inputs_v1'


@contextmanager
def runtime_runner(config_path):
    """Select an isolated client/bridge without changing the shared runner/env.

    The private installer invokes its own fixed Python. Replace only that exact
    subprocess command, retaining its installation/restore transaction unchanged.
    The launcher records the exact final source loaded by create_script.
    """
    if config_path is None:
        yield []
        return
    config_path = config_path.resolve(strict=True)
    config = json.loads(config_path.read_text())
    # Keep the venv executable path: resolving its symlink would silently run
    # the base interpreter without this runtime's isolated site-packages.
    runtime_python = Path(config['python']).absolute()
    if not runtime_python.is_file():
        raise ValueError('isolated runtime Python missing: ' + str(runtime_python))
    launcher = Path(config['launcher']).resolve(strict=True)
    artifacts = config['artifacts']
    for artifact in artifacts:
        path = Path(artifact['path'])
        if not path.is_absolute() or digest(path) != artifact['sha256']:
            raise ValueError('isolated runtime artifact differs: ' + str(path))
    original_loader = stock_trace.map_build_emulator

    def load():
        module = original_loader()
        original_subprocess = module.subprocess
        class Commands:
            def __getattr__(self, name):
                return getattr(original_subprocess, name)
            def run(self, command, *values, **options):
                if (isinstance(command, (list, tuple)) and len(command) > 1 and
                        command[0] == str(module.ROOT / '.venv-firmware/bin/python') and
                        command[1] == str(module.NATIVE / 'online_cache_emulator.py')):
                    command = [str(runtime_python), str(launcher), '--runtime-config',
                               str(config_path), '--runner', command[1], '--', *command[2:]]
                return original_subprocess.run(command, *values, **options)
        module.subprocess = Commands()
        return module
    stock_trace.map_build_emulator = load
    try:
        yield [*artifacts, dict(role='isolated_runtime_config', path=str(config_path),
                               sha256=digest(config_path))]
    finally:
        stock_trace.map_build_emulator = original_loader


def route_stop_gate_source():
    """Route fixture only: retain the original SDK stop path after real inputs."""
    return """
let p2RouteStopRequested=false;
function p2RouteGuidanceStopPoll(){
  if(p2RouteStopRequested)return;
  try{
    const expected=REROUTE_CASE.samples.length;
    if(abStopped||Date.now()>=P2_ROUTE_STOP_DEADLINE_MS||p2DeliveredGnssSamples>expected){
      p2RouteStopRequested=true;
      report('native_callback_fixture_error',{case:'route-change',stage:'guidance-stop-gate',
        error:abStopped?'original SDK stop preceded fixture completion':'route stop gate deadline/count failed'});
      return;
    }
    if(p2CallbackInputCompleted&&p2DeliveredGnssSamples===expected){
      p2RouteStopRequested=true;
      report('native_callback_fixture_stop_gate',{case:'route-change',policy:P2_CALLBACK_CASE.guidance_stop_policy,
        sample_count:expected,delivered_samples:p2DeliveredGnssSamples,callback_fixture_complete:true,
        status:'conditions-satisfied',reason:'route-processing-and-gnss-inputs-complete'});
      // Do not put abStop behind a recorder guard: its original SDK callbacks
      // and destruction must remain recorded exactly as for the base timer.
      abStop('route-processing-and-gnss-inputs-complete');
      return;
    }
    setTimeout(p2RouteGuidanceStopPoll,500);
  }catch(error){p2RouteStopRequested=true;p2Error('routeStopGate',error);}
}
setTimeout(p2RouteGuidanceStopPoll,500);
"""


def lane_stop_gate_source():
    """Lane fixture has its own declared policy and actual processing end."""
    return (route_stop_gate_source().replace('Route', 'Lane').replace('ROUTE', 'LANE')
            .replace('route-change', 'lane-query').replace('route-processing', 'lane-processing')
            .replace('route stop gate', 'lane stop gate').replace('routeStopGate', 'laneStopGate'))


def _run_locked(args):
    emulator_serial(args.serial)
    case = json.loads(args.case.read_text())
    route_change = getattr(args, 'callback_case', None) == 'route-change'
    lane_query = getattr(args, 'callback_case', None) == 'lane-query'
    # Native GPos inputs stop at the explicit SDK boundary, then the heavily
    # observed sender must finish its existing queue. Keep a real drain tail.
    seconds = args.seconds or max(len(case['samples']) + 120, 540 if route_change else 240 if lane_query else 0)
    if not len(case['samples']) + 30 <= seconds <= 600:
        raise ValueError('capture must cover samples +30s startup and remain <=600s')
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / 'case.json').write_bytes(args.case.read_bytes())
    recorder = args.out / 'recorder.js'
    recorder.write_bytes((HERE / 'phase2_inputs.js').read_bytes())
    scenario_contract = scenario_gate.factory_drive_contract()
    contract_path = args.out / 'scenario-contract.json'
    contract_path.write_text(scenario_gate.canonical(scenario_contract))
    diagnostic_artifacts, diagnostic_source = [], ''
    original = HERE / 'runtime' / 'input-boundary.js'
    snapshot = args.out / original.name
    snapshot.write_bytes(original.read_bytes())
    diagnostic_source += snapshot.read_text() + '\nlet p2DeliveredGnssSamples=0;\n'
    diagnostic_artifacts.append(dict(role='input_boundary_fixture',
        path=str(snapshot.resolve()), sha256=digest(snapshot)))
    vehicle_provider = getattr(args, 'vehicle_provider', 'legacy')
    if vehicle_provider not in ('legacy', 'virtual'):
        raise ValueError('unknown vehicle fixture provider: ' + str(vehicle_provider))
    if vehicle_provider == 'virtual':
        original = HERE / 'runtime' / 'vehicle-fixture.js'
        snapshot = args.out / original.name
        snapshot.write_bytes(original.read_bytes())
        diagnostic_source += snapshot.read_text() + '\n'
        diagnostic_artifacts.append(dict(role='vehicle_fixture',
            path=str(snapshot.resolve()), sha256=digest(snapshot)))
    backend = getattr(args, 'observer_backend', 'frida')
    if backend not in ('frida', 'jvmti'):
        raise ValueError('unknown original observer backend: ' + str(backend))
    if backend == 'jvmti':
        original = HERE / 'runtime' / 'jvmti-recorder.js'
        snapshot = args.out / original.name
        snapshot.write_bytes(original.read_bytes())
        diagnostic_source += ("const P2_OBSERVER_BACKEND='jvmti';\n" + snapshot.read_text() + '\n')
        diagnostic_artifacts.append(dict(role='jvmti_recorder',
            path=str(snapshot.resolve()), sha256=digest(snapshot)))
    if getattr(args, 'jvmti_smoke', False):
        original = HERE / 'runtime' / 'jvmti-event-smoke.js'
        snapshot = args.out / original.name
        snapshot.write_bytes(original.read_bytes())
        diagnostic_source += snapshot.read_text() + '\n'
        diagnostic_artifacts.append(dict(role='jvmti_event_smoke',
            path=str(snapshot.resolve()), sha256=digest(snapshot)))
    if getattr(args, 'hook_diagnostics', False):
        for name in ('nested-hook-calibration.js', 'quick-code-probe.js', 'class-identity-probe.js',
                     'interpreter-call-probe.js'):
            original = HERE / 'runtime' / name
            snapshot = args.out / name
            snapshot.write_bytes(original.read_bytes())
            diagnostic_source += snapshot.read_text() + '\n'
            diagnostic_artifacts.append(dict(role='hook_diagnostic_'+name,
                path=str(snapshot.resolve()), sha256=digest(snapshot)))
    # Install before the base fixture constructs sender singletons or posts tasks.
    base = stock_trace.observer_text(
        case, answers=True, answer_delay_ms=80, online_wait_ms=15000,
        seconds=seconds, arrive=not args.no_arrive)
    config_line, body = base.split('\n', 1)
    config = json.loads(config_line[len('const REROUTE_CASE='):-1])
    # The base trace's stop timer was near the process deadline. End guidance
    # after the actual GNSS scenario and retain a real idle/destruction tail.
    config['guideMs'] = (len(case['samples']) + 5) * 1000
    if route_change or lane_query:
        nominal_stop = "setTimeout(()=>abStop('timer'),REROUTE_CASE.guideMs||125000);"
        if body.count(nominal_stop) != 1:
            raise ValueError('original route guidance stop timer site changed')
        body = body.replace(nominal_stop, route_stop_gate_source() if route_change else lane_stop_gate_source())
    gnss_return = 'pos.setSignInfo(data);'
    if body.count(gnss_return) != 1:
        raise ValueError('original GNSS input return boundary changed')
    body = body.replace(gnss_return, gnss_return +
        "rtReport('gnss_input_return',{count:++p2DeliveredGnssSamples});")
    marker = "const flags=Java.use('k.h.e0.c.j.i0.b.a');"
    if body.count(marker) != 1:
        raise ValueError('stock fixture flag assignment site changed')
    # Initialize the original NOA-state object before assigning the emulator
    # service/switch fixture. Its first constructor otherwise overwrites b=false
    # inside navigationCreated, because the emulator has no physical DNP service.
    body = body.replace(marker, "Java.use('k.h.e0.b.f').m();\n      " + marker)
    map_state_hook = "  rtWrap('com.byd.map.signal.GaoDeSignalManager', 'setMapState', ['int'], function (s) { abReport('map_state', {state: s, thread: abThread()}); });"
    if body.count(map_state_hook) != 1:
        raise ValueError('stock fixture upstream map-state observation changed')
    body = body.replace(map_state_hook, '// Upstream observation owned by phase2 after safe original class initialization.')
    if body.count('link.i();link.k();') != 1:
        raise ValueError('stock fixture lifecycle site changed')
    body = body.replace('link.i();link.k();', "link.i();const noaFixture=Java.use('k.h.e0.b.f').m();noaFixture.onDnpConfigState(true);noaFixture.onDnpSwitchState(true,true,true);link.k();")
    if vehicle_provider == 'virtual':
        fixture_begin = "    const R = Java.use('k.h.e0.a.r'), values ="
        fixture_end = "    abReport('vehicle_noa_fixture', {values});"
        if body.count(fixture_begin) != 1 or body.count(fixture_end) != 1:
            raise ValueError('stock NOA provider fixture changed')
        begin, end = body.index(fixture_begin), body.index(fixture_end) + len(fixture_end)
        body = body[:begin] + """    const fixture=DenzaVehicleFixture.installNoa({report:(kind,data)=>abReport(kind,data)});
    abReport('vehicle_noa_fixture',{values:fixture.values(),provider:'registered_virtual_subclass'});
    fixture.validate();""" + body[end:]
        error_marker = "abReport('vehicle_noa_fixture_error', {error: String(e)});"
        if body.count(error_marker) != 1:
            raise ValueError('stock NOA fixture failure boundary changed')
        body = body.replace(error_marker, error_marker + "p2Error('vehicleFixture.install',e);")
        speed_begin = "  if (REROUTE_CASE.vehicleSpeed) {"
        speed_end = "    rtReport('vehicle_provider_fixture',{units:'km/h',sources:['realSpeed','currentSpeed']});\n  }"
        if body.count(speed_begin) != 1 or body.count(speed_end) != 1:
            raise ValueError('stock vehicle speed fixture changed')
        begin, end = body.index(speed_begin), body.index(speed_end) + len(speed_end)
        body = body[:begin] + """  if(REROUTE_CASE.vehicleSpeed)
    rtReport('vehicle_provider_fixture',{units:'km/h',sources:['original OnRealSpeedChangeListener'],currentSpeed:'unmodified'});""" + body[end:]
        sample_marker = "const sample=REROUTE_CASE.samples[rtTick++],data="
        if body.count(sample_marker) != 1:
            raise ValueError('original GNSS fixture delivery boundary changed')
        body = body.replace(sample_marker, "const sample=REROUTE_CASE.samples[rtTick++];" +
            "if(REROUTE_CASE.vehicleSpeed)p2Observe('vehicleFixture.speed',()=>DenzaVehicleFixture.deliverVehicleSpeed(" +
            "typeof p2FixtureVehicleSpeedKmh!=='undefined'&&p2FixtureVehicleSpeedKmh!==null?p2FixtureVehicleSpeedKmh:sample.speed_mps*3.6," +
            "{report:(kind,data)=>abReport(kind,data)}));const data=")
    else:
        fixture_return = 'function () { return values[name]; }'
        if body.count(fixture_return) != 1:
            raise ValueError('stock NOA getter fixture changed')
        body = body.replace(fixture_return, "function () { abReport('vehicle_noa_fixture_hit',{method:name,value:values[name]});return values[name]; }")
    callback_artifacts, callback_source, callback_setup = [], '', ''
    callback_case = getattr(args, 'callback_case', None)
    callback_contract = None
    if callback_case:
        if callback_case == 'rare-active' and len(case['samples']) < 45:
            raise ValueError('rare-active needs at least 45 guidance samples for its actual 35s input plan')
        if callback_case == 'reset-offcourse' and len(case['samples']) < 30:
            raise ValueError('reset-offcourse needs at least 30 guidance samples for its input plan')
        if callback_case == 'lane-query' and len(case['samples']) < 45:
            raise ValueError('lane-query needs at least 45 guidance samples for its bounded request/response plan')
        if callback_case == 'city-odd' and len(case['samples']) < 30:
            raise ValueError('city-odd needs at least 30 guidance samples for its 20s input plan')
        if callback_case == 'route-change' and len(case['samples']) < 160:
            raise ValueError('route-change needs at least 160 actual guidance samples to observe two native routes')
        fixture = args.out / 'callback-fixture.js'
        fixture.write_bytes((HERE / ('phase2_route_change.js' if route_change else 'phase2_cases.js')).read_bytes())
        location = dict(rawLon=case['samples'][0]['sdk'][0], rawLat=case['samples'][0]['sdk'][1],
            matchedLon=case['route_start_sdk'][0], matchedLat=case['route_start_sdk'][1],
            heading=case['samples'][0]['heading_degrees_true'], roadClass=3, ownership=0, linkId='0')
        callback_contract = dict(schema='denza-stock-sender-callback-fixture-v1', case=callback_case,
            location=location, road_attributes='declared synthetic SDK input; no road-content assertion',
            fixture_sha256=digest(fixture))
        if callback_case == 'arrival-idle':
            callback_contract.update(setup_wait_ms=120000)
        if callback_case == 'city-odd':
            callback_contract.update(city_policy='actual-current-urid-synthetic-zero-clear-v1',
                                     city_exclude_ids=[0])
        if lane_query:
            callback_contract.update(guidance_stop_policy='after_lane_processing_and_gnss_inputs_v1',
                                     guidance_sample_count=len(case['samples']))
        if route_change:
            callback_contract.update(route_change_policy='actual-native-two-route-v1',
                guidance_stop_policy=ROUTE_STOP_POLICY,guidance_sample_count=len(case['samples']),location=None,
                road_attributes='actual native route and progress inputs; no synthesized road attributes')
        callback_path = args.out / 'callback-contract.json'
        callback_path.write_text(json.dumps(callback_contract, sort_keys=True, separators=(',', ':')))
        for role, path in [('callback_fixture', fixture), ('callback_contract', callback_path)]:
            callback_artifacts.append(dict(role=role, path=str(path.resolve()), sha256=digest(path)))
        callback_source = ('const P2_CALLBACK_CASE=' + callback_path.read_text() + ';\n' +
            'let p2LastNavi=null,p2FixtureVehicleSpeedKmh=null,p2CallbackInputCompleted=false;\n' + fixture.read_text() + '\n')
        if callback_case == 'lane-query':
            callback_source += '''
const p2LaneRequests=[];
function p2ObservedRead(method,args,result,metadata) {
  if(method!=='com.autonavi.gbl.guide.GuideService.queryAppointLanesInfo(com.autonavi.gbl.guide.model.QueryLanesInfo)')return;
  if(result===0)return;
  if(!metadata||typeof metadata.read_id!=='string'||!Number.isInteger(metadata.source_seq))
    throw new Error('completed original lane query metadata required');
  const query=Java.cast(args[0],Java.use('com.autonavi.gbl.guide.model.QueryLanesInfo'));
  p2LaneRequests.push({read_id:metadata.read_id,source_seq:metadata.source_seq,
    requestId:String(result),segmentIdx:query.segmentIdx.value,linkIdx:query.linkIdx.value,num:query.num.value});
}
'''
        navi_marker = "const n=Java.cast(items.get(i),Java.use('com.autonavi.gbl.guide.model.NaviInfo'));"
        speed_marker = "return rtTick ? REROUTE_CASE.samples[Math.min(rtTick-1,REROUTE_CASE.samples.length-1)].speed_mps*3.6 : 0;"
        if body.count(navi_marker) != 1 or (vehicle_provider == 'legacy' and body.count(speed_marker) != 1):
            raise ValueError('original callback fixture splice sites changed')
        body = body.replace(navi_marker, navi_marker + 'p2LastNavi=Java.retain(n);')
        if vehicle_provider == 'legacy':
            body = body.replace(speed_marker, 'if(p2FixtureVehicleSpeedKmh!==null)return p2FixtureVehicleSpeedKmh;' + speed_marker)
        callback_setup = ('\np2AttachCallbackCase({case:P2_CALLBACK_CASE.case,seed:()=>rtSeed,' +
            'navi:()=>p2LastNavi,location:P2_CALLBACK_CASE.location,waitMs:P2_CALLBACK_CASE.setup_wait_ms||60000,' +
            ('laneRequests:()=>p2LaneRequests.slice(),' if callback_case == 'lane-query' else '') +
            ('cityPolicy:P2_CALLBACK_CASE.city_policy,cityExcludeIds:P2_CALLBACK_CASE.city_exclude_ids,' if callback_case == 'city-odd' else '') +
            'vehicleSpeed:kmh=>{p2FixtureVehicleSpeedKmh=kmh;' +
            ("p2Observe('vehicleFixture.speed',()=>DenzaVehicleFixture.deliverVehicleSpeed(kmh,{report:(kind,data)=>abReport(kind,data)}));" if vehicle_provider == 'virtual' else '') +
            '}});\n')
        if route_change:
            callback_setup = '\np2AttachRouteChange({seed:()=>rtSeed,navi:()=>p2LastNavi,waitMs:360000});\n'
    input_boundary_setup = '''
const p2InputBoundary=p2Observe('inputBoundary.install',()=>DenzaInputBoundary.install({
  sampleCount:REROUTE_CASE.samples.length,callbackCase:''' + json.dumps(callback_case) + ''',
  report:(kind,data)=>abReport(kind,data),reference:value=>p2Ref(value)}));
function p2PollInputBoundary(){
  if(!p2InputBoundary)return;
  Java.perform(()=>Java.scheduleOnMainThread(()=>{
    p2Observe('inputBoundary.poll',()=>p2InputBoundary.poll({
      deliveredSamples:p2DeliveredGnssSamples,started:rtStarted,
      callbackFixtureComplete:''' + ('p2CallbackInputCompleted' if callback_case else 'true') + '''}));
    const state=p2InputBoundary.metadata();
    if(!state.attempted&&state.state!=='failed')setTimeout(p2PollInputBoundary,500);
  }));
}
setTimeout(p2PollInputBoundary,500);
'''
    observer = (scenario_gate.declaration(scenario_contract) + '\n' + diagnostic_source + recorder.read_text() +
        '\n' + callback_source + (('const P2_'+('ROUTE' if route_change else 'LANE')+'_STOP_DEADLINE_MS=Date.now()+'+str((seconds-5)*1000)+';\n') if route_change or lane_query else '') + 'const REROUTE_CASE=' + json.dumps(config) + ';\n' + body + callback_setup +
        input_boundary_setup +
        '\nsetTimeout(()=>Java.perform(()=>{p2StopRequested=true;p2MaybeClose();}),' + str((seconds-4)*1000) + ');\n')
    with runtime_runner(getattr(args, 'runtime_config', None)) as runtime_artifacts:
        receipt = stock_trace.install_run(args.build, observer,
            dict(lon=case['route_start_sdk'][0], lat=case['route_start_sdk'][1], zoom=16),
            args.out / 'run', seconds=seconds, serial=args.serial, frida_port=args.frida_port)
    source, artifacts = original_artifacts(APK, args.out / 'run/observer.js', args.out)
    artifacts.extend(runtime_artifacts)
    artifacts.extend(diagnostic_artifacts)
    artifacts.extend(callback_artifacts)
    artifacts.append(dict(role='scenario_contract', path=str(contract_path.resolve()),
                          sha256=digest(contract_path)))
    for role, path in [('case', args.case), ('input_recorder', recorder),
                       ('base_observer', stock_trace.HERE / 'stock_feed_observer.js'),
                       ('receipt', args.out / 'run/receipt.json')]:
        artifacts.append(dict(role=role, path=str(path.resolve()), sha256=digest(path)))
    # Import after the original run: raw evidence and restoration survive any
    # subsequent normalization failure.
    from .phase2_capture import normalize
    import ast
    import re
    hooks = ast.literal_eval(re.search(r'const P2_REQUIRED_HOOKS = (\[[\s\S]*?\]);', recorder.read_text()).group(1))
    return normalize(args.out / 'run/app/events.jsonl', args.out, source=source,
        required_hooks=hooks, artifacts=artifacts, receipt=receipt,
        metadata=dict(serial=args.serial, frida_port=args.frida_port, seconds=seconds,
                      build=str(args.build.resolve()), scenario_contract=scenario_contract,
                      callback_contract=callback_contract, observer_backend=backend,
                      vehicle_provider=vehicle_provider, terminal_policy=TERMINAL_POLICY,
                      input_boundary_policy=INPUT_BOUNDARY_POLICY))


def run(args):
    emulator_serial(args.serial)
    if not 1 <= args.frida_port <= 65535:
        raise ValueError('valid explicit Frida port required')
    # The private runner locks only its attached app. Cover the complete map
    # installation/baseline restoration transaction as well.
    lock_path = Path(tempfile.gettempdir()) / ('denza-sender-parity-' + args.serial + '.lock')
    with lock_path.open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return _run_locked(args)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('case','build','out'):
        parser.add_argument('--' + name, required=True, type=Path)
    parser.add_argument('--serial', required=True, type=emulator_serial)
    parser.add_argument('--frida-port', required=True, type=int)
    parser.add_argument('--seconds', type=int)
    parser.add_argument('--no-arrive', action='store_true')
    parser.add_argument('--runtime-config', type=Path,
                        help='Hashed isolated Frida client/bridge launcher configuration')
    parser.add_argument('--hook-diagnostics', action='store_true',
                        help='Observe nested hook calibration and original ArtMethod entrypoints')
    parser.add_argument('--jvmti-smoke', action='store_true',
                        help='Calibrate original nested Java entry/exit events on a fresh HashMap')
    parser.add_argument('--observer-backend', choices=('frida', 'jvmti'), default='frida',
                        help='Original method observation mechanism (JVMTI preserves Java method bodies)')
    parser.add_argument('--vehicle-provider', choices=('legacy', 'virtual'), default='legacy',
                        help='Emulator vehicle inputs: legacy hooks or a real virtual adapter and original speed listener')
    parser.add_argument('--callback-case', choices=('rare-active','arrival-idle','reset-offcourse','lane-query','city-odd','route-change'),
                        help='Deliver declared synthetic raw SDK callback inputs to the original sender')
    args = parser.parse_args()
    result = run(args)
    print(json.dumps(dict(status=result['status'],parityEstablished=result['parityEstablished'],
                          gap_count=len(result['gaps']),out=str(args.out.resolve())),ensure_ascii=False))
    raise SystemExit(0 if result['status'] == 'CAPTURED' else 2)


if __name__ == '__main__':
    main()
