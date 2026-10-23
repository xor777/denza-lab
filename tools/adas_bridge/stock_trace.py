#!/usr/bin/env python3
"""Stock reference trace: the original map's map->ADAS feed for a recorded drive, on an owned emulator.

Installs a build_map.py package the way map_build_emulator.py does (and restores the
emulator afterwards), replays the drive case's positions (build_drive_replay_case.py)
through the original app with stock_feed_observer.js, and records every fireEvent at
SomeIpServerProxyImpl. `decode` turns the run into a feed trace (feed.py).

    python3 -m tools.adas_bridge.stock_trace run --build captures/stock-map-review-20261023/car/package-e \
        --case captures/adas-bench/cases/arrival-d02.json --out captures/adas-bench/arrival-d02/stock-answers \
        [--no-answers] [--online-wait-ms 15000] [--serial emulator-5600 --frida-port 27069]
    python3 -m tools.adas_bridge.stock_trace decode --run captures/adas-bench/arrival-d02/stock-answers

Never the car: Device() refuses anything but an emulator serial, and the app has no
SOME/IP endpoint on the emulator; recorded fires never leave the process.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys

from .feed import RouteAssembler, b64, decode_event, write_trace

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def map_build_emulator():
    spec = importlib.util.spec_from_file_location('map_build_emulator', ROOT / 'tools/stock-map/map_build_emulator.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def observer_text(case, *, answers, answer_delay_ms, online_wait_ms, seconds, arrive):
    config = dict(route_start_sdk=case['route_start_sdk'], route_end_sdk=case['route_end_sdk'],
                  mode='stock-fallback', vehicleSpeed=True, onlineWaitMs=online_wait_ms,
                  guideMs=(seconds - 10) * 1000,
                  samples=[dict(sdk=[round(v, 7) for v in s['sdk']], heading_degrees_true=round(s['heading_degrees_true'], 1),
                                speed_mps=round(s['speed_mps'], 2)) for s in case['samples']])
    bench = dict(answers=answers, answerDelayMs=answer_delay_ms, arrive=arrive)
    return ('const REROUTE_CASE=' + json.dumps(config, separators=(',', ':')) + ';\n'
            + 'const ADAS_BENCH=' + json.dumps(bench) + ';\n'
            + (HERE / 'stock_feed_observer.js').read_text())


def install_run(build, observer, view, out, *, seconds, serial, frida_port):
    """map_build_emulator.install_and_run with factory aN packages added to its install set."""
    os.environ['STOCK_MAP_EMULATOR'] = serial
    os.environ['STOCK_MAP_FRIDA_PORT'] = str(frida_port)
    module = map_build_emulator()
    # A factory offline package (e.g. Beijing a1, downloaded by the car's own map) is laid out
    # like ours; install and restore it the same way when DIR has one.
    for package in sorted(p.name for p in build.iterdir() if p.is_dir() and p.name.startswith('a')):
        for name in ('m1.ans', 'm2.ans', 'm3.ans', 'm4_pro.ans', 'm5a.ans', 'm5b.ans'):
            module.TARGETS.setdefault(package + '/' + name, (module.OFFLINE + package + '/' + name, False))
    runner = argparse.Namespace(build=build, seconds=seconds)
    return module.install_and_run(runner, observer, view, out)


def cmd_run(args):
    case = json.loads(args.case.read_text())
    seconds = args.seconds or len(case['samples']) + 40
    if seconds < len(case['samples']) + 30:
        raise SystemExit('--seconds must cover every sample plus 30 s of start-up')
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / 'case.json').write_text(json.dumps(case) + '\n')
    observer = observer_text(case, answers=not args.no_answers, answer_delay_ms=args.answer_delay_ms,
                             online_wait_ms=args.online_wait_ms, seconds=seconds, arrive=not args.no_arrive)
    view = dict(lon=case['route_start_sdk'][0], lat=case['route_start_sdk'][1], zoom=16)
    receipt = install_run(args.build, observer, view, args.out / 'run', seconds=seconds,
                          serial=args.serial, frida_port=args.frida_port)
    print(json.dumps(dict(status=receipt['status'], restored=receipt['baseline_restored'])), flush=True)
    cmd_decode(argparse.Namespace(run=args.out))


CONTEXT = {'adas_fake_answer', 'adas_map_state', 'adas_navi_arrive', 'adas_navi_stop', 'adas_presenter_stop',
           'adas_presenter_stop_again', 'adas_navi_on_destroy', 'adas_navi_on_create_start', 'adas_guidance_start',
           'adas_route_data_update', 'adas_car_state', 'adas_start_service', 'adas_stop_service',
           'reroute_guide_offroute', 'reroute_offline_fallback', 'reroute_handover', 'reroute_observed_success',
           'reroute_stock_callback_success', 'reroute_initial_request', 'reroute_feed_done',
           'reroute_handover_error', 'reroute_guidance_error', 'reroute_setup_error'}


def cmd_decode(args):
    run = args.run / 'run' if (args.run / 'run').exists() else args.run
    lines = (run / 'app/events.jsonl').read_text().splitlines()
    events, assembler, ticks, errors = [], RouteAssembler(), [], []
    for order, line in enumerate(lines):
        payload = json.loads(line).get('payload')
        if not isinstance(payload, dict):
            continue
        kind = payload.get('kind')
        if kind in ('adas_fire', 'adas_client_send'):
            record = decode_event(int(payload['topic']), b64(payload.get('payload')), assembler)
            record.update(t_ms=payload['mono_ms'], kind='fire' if kind == 'adas_fire' else 'client_send',
                          thread=payload.get('thread'), order=order)
            if 'decodeError' in record:
                errors.append(record['decodeError'])
            events.append(record)
        elif kind == 'reroute_gnss':
            ticks.append(dict(t_ms=payload['mono_ms'], tick=payload['tick'], sdk=payload['sdk'],
                              speed_mps=payload['speed_mps'], course=payload.get('course')))
        elif kind in CONTEXT:
            fields = {k: v for k, v in payload.items() if k not in ('kind', 'mono_ms', 'stack')}
            if kind == 'adas_fake_answer':
                record = decode_event(int(payload['topic']), b64(payload['payload']))
                record.update(t_ms=payload['mono_ms'], kind='answer', fake=True, order=order)
                events.append(record)
            else:
                events.append(dict(t_ms=payload['mono_ms'], kind='context', name=kind, fields=fields, order=order))
        elif kind and kind.endswith('_error'):
            errors.append(payload)
    origin = min(e['t_ms'] for e in events + ticks) if events or ticks else 0
    for tick in ticks:
        tick['t_ms'] = round(tick['t_ms'] - origin, 3)
    case = json.loads((args.run / 'case.json').read_text()) if (args.run / 'case.json').exists() else {}
    receipt = json.loads((run / 'receipt.json').read_text()) if (run / 'receipt.json').exists() else {}
    provenance = {'source': 'original com.byd.launchermap on an emulator, SomeIpServerProxyImpl.fireEvent recorder',
                  'build': receipt.get('build'), 'installed': receipt.get('installed'),
                  'baselineRestored': receipt.get('baseline_restored'), 'case': case.get('source'),
                  'window': case.get('window'), 'errors': errors[:50], 'gnssTicks': ticks,
                  'contentReference': False,
                  'note': 'stock code on our OSM-derived package: reference for dynamics/protocol only, not content'}
    trace = write_trace(args.run / 'stock-trace.json', 'stock', args.run.name, events, provenance)
    counts = {}
    for event in trace['events']:
        counts[event['name']] = counts.get(event['name'], 0) + 1
    print(json.dumps(dict(trace=str(args.run / 'stock-trace.json'), events=len(trace['events']), errors=len(errors),
                          counts=counts), ensure_ascii=False))


def cmd_batch(args):
    """Run a JSON list of {build, case, out, noAnswers?, onlineWaitMs?, seconds?} one after another."""
    plan = json.loads(args.plan.read_text())
    for item in plan:
        if Path(item['out']).exists():
            print(json.dumps(dict(skip=item['out'])), flush=True)
            continue
        run_args = argparse.Namespace(build=Path(item['build']), case=Path(item['case']), out=Path(item['out']),
                                      seconds=item.get('seconds'), no_answers=item.get('noAnswers', False),
                                      answer_delay_ms=item.get('answerDelayMs', 80),
                                      online_wait_ms=item.get('onlineWaitMs', 15000), no_arrive=False,
                                      serial=args.serial, frida_port=args.frida_port, extra=item.get('extra'))
        try:
            cmd_run(run_args)
        except (SystemExit, RuntimeError, OSError) as error:
            print(json.dumps(dict(failed=item['out'], error=str(error))), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    batch = sub.add_parser('batch')
    batch.add_argument('--plan', type=Path, required=True)
    batch.add_argument('--serial', default='emulator-5600')
    batch.add_argument('--frida-port', type=int, default=27069)
    batch.set_defaults(func=cmd_batch)
    run = sub.add_parser('run')
    run.add_argument('--build', type=Path, required=True, help='build_map.py --out directory')
    run.add_argument('--case', type=Path, required=True, help='build_drive_replay_case.py output')
    run.add_argument('--out', type=Path, required=True)
    run.add_argument('--seconds', type=int)
    run.add_argument('--no-answers', action='store_true', help='no scripted MMTPathSelected')
    run.add_argument('--answer-delay-ms', type=int, default=80)
    run.add_argument('--online-wait-ms', type=int, default=15000,
                     help="the car's failing online attempt before the offline reroute (15000 on the car)")
    run.add_argument('--no-arrive', action='store_true', help='do not play the presenter on onNaviArrive')
    run.add_argument('--serial', default='emulator-5600')
    run.add_argument('--frida-port', type=int, default=27069)
    run.set_defaults(func=cmd_run)
    decode = sub.add_parser('decode')
    decode.add_argument('--run', type=Path, required=True)
    decode.set_defaults(func=cmd_decode)
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    sys.exit(main())
