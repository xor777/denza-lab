"""Capture ORIGINAL stock sender callbacks and egress on an explicitly owned emulator.

Normalization never constructs input from egress. Invalid/incomplete capture is
retained with coverage_gap rows; its report says INCOMPLETE and CLI exits 2.
"""
from __future__ import annotations

import argparse
import ast
import base64
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import zipfile

from .bench import INPUT_SCHEMA, REFERENCE_SCHEMA, digest, write_jsonl

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
APK = ROOT / 'captures/hud-firmware-20260923/system/system/app/BydLaunchermap/BydLaunchermap.apk'
APK_SHA256 = '3a7773d4b41bbbdcb2df7f6f249b91feaf197ff791d67e32e31859bd2425c793'
REF_KINDS = {'adas_fire': 'fire', 'adas_start_service': 'start_service',
             'adas_stop_service': 'stop_service', 'adas_client_send': 'client_send'}


def emulator_serial(value: str) -> str:
    if not re.fullmatch(r'emulator-[0-9]+', value) or value == 'emulator-5580':
        raise argparse.ArgumentTypeError('an explicitly owned emulator serial is required; 5580 is shared')
    return value


def read_raw(path: Path):
    rows, errors = [], []
    for line, text in enumerate(path.read_text().splitlines(), 1):
        if not text.strip():
            continue
        try:
            row = json.loads(text)
            if not isinstance(row, dict):
                raise ValueError('JSON object required')
            if row.get('type') == 'error':
                errors.append({'line': line, 'frida_error': row})
            payload = row.get('payload')
            if isinstance(payload, dict):
                rows.append((line, payload))
        except (ValueError, TypeError) as exc:
            errors.append({'line': line, 'parse_error': str(exc)})
    return rows, errors


def original_artifacts(apk: Path, recorder: Path, out: Path, *, artifact_dir: Path | None = None) -> tuple[dict, list]:
    """Hash DEX streams in the original APK; retain just one shared sender DEX.

    APK bytes pin all other DEX entries. Existing sender bytes are reused only
    after an exact hash check; a mismatch is evidence and must not be overwritten.
    ``out`` remains the normalization destination, not a per-case binary cache.
    """
    apk_sha = digest(apk)
    if apk_sha != APK_SHA256:
        raise ValueError('original APK hash differs from corpus pin')
    dex_dir = artifact_dir or ROOT / 'captures/stock-sender-parity-20261025/original-dex'
    dex_dir.mkdir(parents=True, exist_ok=True)
    recorder_sha = digest(recorder)
    artifacts = [dict(role='apk', path=str(apk.resolve()), sha256=apk_sha),
                 dict(role='recorder', path=str(recorder.resolve()), sha256=recorder_sha)]
    entries = {}
    # The actual class_def for k.h.e0.c.j.f0 is in classes15.dex; references in
    # other DEX do not identify its defining stream. Codec f0 is in classes14.
    sender = 'classes15.dex'
    with zipfile.ZipFile(apk) as archive:
        for name in sorted(archive.namelist()):
            if not re.fullmatch(r'classes(?:[0-9]+)?\.dex', name):
                continue
            checksum = hashlib.sha256()
            with archive.open(name) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    checksum.update(block)
            entries[name] = checksum.hexdigest()
        if sender not in entries:
            raise ValueError('original sender DEX absent')
        path = dex_dir / sender
        if path.exists():
            if digest(path) != entries[sender]:
                raise ValueError('shared original sender DEX differs; refusing overwrite: ' + str(path))
        else:
            with archive.open(sender) as stream, path.open('xb') as output:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    output.write(block)
            if digest(path) != entries[sender]:
                raise ValueError('extracted sender DEX hash differs')
    artifacts.append(dict(role='dex', path=str(path.resolve()), sha256=entries[sender]))
    source = dict(apk_sha256=apk_sha, dex_sha256=entries[sender],
                  dex_entry=sender, dex_entries=entries, recorder_sha256=recorder_sha)
    return source, artifacts


def bind_runtime_identity(raw: Path, source: dict) -> tuple[dict, list, list]:
    """Bind runner-verified installed APK and exact loaded Frida agent bytes."""
    report_path, agent_path = raw.parent / 'report.json', raw.parent / 'agent.js'
    identity, artifacts, gaps = {}, [], []
    try:
        report = json.loads(report_path.read_text())
        agent_sha = digest(agent_path)
        identity = dict(apk_sha256=report.get('apk_sha256'), agent_sha256=report.get('agent_sha256'),
                        report_path=str(report_path.resolve()), report_sha256=digest(report_path),
                        agent_path=str(agent_path.resolve()), actual_agent_sha256=agent_sha,
                        serial=report.get('serial'), started=report.get('started'), finished=report.get('finished'))
        if report.get('apk_sha256') != source.get('apk_sha256'):
            gaps.append({'runtime_apk_identity_mismatch': identity})
        if report.get('agent_sha256') != agent_sha:
            gaps.append({'runtime_agent_identity_mismatch': identity})
        artifacts = [dict(role='runtime_report', path=str(report_path.resolve()), sha256=digest(report_path)),
                     dict(role='runtime_agent', path=str(agent_path.resolve()), sha256=agent_sha)]
    except (OSError, ValueError, TypeError) as exc:
        gaps.append({'runtime_identity_missing': str(exc)})
    return identity, artifacts, gaps


def normalize(raw: Path, out: Path, *, source: dict, required_hooks: list[str],
              artifacts: list | None = None, receipt: dict | None = None,
              metadata: dict | None = None) -> dict:
    """Lossless callback/reference split with explicitly declared start clipping.

    Absolute CLOCK_MONOTONIC timestamps are floored to integer milliseconds for
    ManualScheduler; raw fractions, wall clocks and source lines remain retained.
    Final advance/end only reach the latest actually observed monotonic timestamp.
    """
    out.mkdir(parents=True, exist_ok=True)
    rows, gaps = read_raw(raw)
    starts = [(line, p) for line, p in rows if p.get('kind') == 'sender_start']
    if len(starts) != 1:
        gaps.append({'sender_start_count': len(starts)})
    start_line, start = starts[0] if starts else (0, {})

    def stamp(p):
        v = p.get('mono_ms')
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
            raise ValueError('finite mono_ms required')
        return math.floor(v)

    try:
        start_ms = stamp(start)
    except ValueError as exc:
        gaps.append({'start_clock': str(exc)})
        start_ms = 0
    gates, locale = start.get('gates', {}), start.get('locale')
    if any(type(gates.get(k)) is not bool for k in ('service', 'switch', 'no_sleep')):
        gaps.append({'gate_snapshot_missing': gates})
    if locale not in ('EN', 'DEFAULT'):
        gaps.append({'locale_missing': locale})
    scripted_answers = [dict(source_line=line, raw=p) for line, p in rows if p.get('kind') == 'adas_fake_answer']
    fixture_inputs = dict(scriptedAnswers=scripted_answers, sender_start_fixture=start.get('fixture'),
                          statement='BYDAuto flags are emulator fixtures; matching ACKs are scripted MMTPathSelected responses, never ECU evidence')
    header = dict(kind='header', schema=INPUT_SCHEMA, origin='original-stock-dex', start_ms=start_ms,
                  gates=gates, locale=locale, source=source, fixture_inputs=fixture_inputs,
                  clocks=dict(integer_policy='floor absolute mono_ms; no rebasing', snapshot=start),
                  raw_capture=dict(path=str(raw.resolve()), sha256=digest(raw)),
                  start_scope='sender_start line inclusive; earlier callbacks and egress retained separately')
    inputs, reference, prestart, internal = [header], [], [], []
    required_set = set(required_hooks)
    ready = []
    ready_lines = []
    hooks = {}
    native_counts = Counter()
    last_seq = -1
    last_at = start_ms
    first_seq = None
    all_fire_seq = []
    boundary_line = None
    seen_ref = 0
    end_at = start_ms
    for line, p in rows:
        kind = p.get('kind', '')
        if kind == 'sender_hooks_ready':
            ready.append(p)
            ready_lines.append(line)
            if isinstance(p.get('required'), list):
                required_set.update(p['required'])
        if kind == 'sender_hook':
            if p.get('required') is True:
                required_set.add(p.get('method'))
            hooks[p.get('method')] = p.get('status')
            if p.get('status') != 'installed':
                gaps.append({'hook_failure': p})
        if kind == 'sender_error' or kind.endswith('hook_error') or kind.endswith('hook_install_error') or kind == 'adas_boundary_install_error':
            gaps.append({'source_line': line, 'capture_error': p})
        if kind == 'adas_boundary_installed':
            boundary_line = line
        if isinstance(p.get('mono_ms'), (float, int)) and math.isfinite(p['mono_ms']):
            end_at = max(end_at, math.floor(p['mono_ms']))
        if kind == 'adas_fire':
            all_fire_seq.append(p.get('seq'))
        if kind in REF_KINDS:
            seen_ref += 1
            if boundary_line is None:
                gaps.append({'reference_before_boundary': line})
            try:
                at = stamp(p)
                row = dict(kind=REF_KINDS[kind], at_ms=at, topic=str(p['topic']),
                           source_line=line, raw=p)
                if not re.fullmatch(r'[0-9]+', row['topic']):
                    raise ValueError('decimal topic required')
                if kind in ('adas_fire', 'adas_client_send'):
                    if not isinstance(p.get('payload'), str):
                        raise ValueError('base64 payload required (null is not empty bytes)')
                    payload = base64.b64decode(p['payload'], validate=True)
                    if 'bytes' in p and p['bytes'] != len(payload):
                        raise ValueError('payload byte count mismatch')
                    row['payloadHex'] = payload.hex()
                row['seq'] = p.get('seq', seen_ref - 1)
                (prestart if line < start_line or at < start_ms else reference).append(row)
            except (ValueError, KeyError) as exc:
                gaps.append({'source_line': line, 'invalid_reference': str(exc)})
        if kind != 'sender_input':
            continue
        try:
            if p.get('schema') != INPUT_SCHEMA:
                raise ValueError('callback schema mismatch')
            seq, at = p.get('seq'), stamp(p)
            if type(seq) is not int or seq != last_seq + 1:
                raise ValueError('callback seq must be continuous from zero')
            last_seq = seq
            if first_seq is None:
                first_seq = seq
            if not isinstance(p.get('event'), str) or not isinstance(p.get('data'), dict):
                raise ValueError('event and complete data object required')
            row = dict(kind='event', seq=seq, at_ms=at, event=p['event'], data=p['data'],
                       source_line=line, mono_ms=p['mono_ms'], wall_ms=p.get('wall_ms'),
                       method=p.get('method'), call_id=p.get('call_id'), parent_call_id=p.get('parent_call_id'),
                       thread=p.get('thread'), clocks=p.get('clocks'))
            if p['event'] == 'ack':
                # Origin annotation only: preserve the independently observed
                # callback data; never construct replay inputs from sent output.
                data = p['data']
                matched = None
                for answer in reversed(scripted_answers):
                    fake = answer['raw']
                    if answer['source_line'] >= line or str(fake.get('topic')) != str(data.get('topic')):
                        continue
                    try:
                        original = base64.b64decode(fake['payload'], validate=True)
                        observed = (base64.b64decode(data['payloadB64'], validate=True) if 'payloadB64' in data
                                    else bytes.fromhex(data['payloadHex']))
                    except (ValueError, TypeError, KeyError):
                        continue
                    if original == observed:
                        matched = answer
                        break
                row['provenance'] = (dict(origin='scripted_emulator_answer', ecuEvidence=False,
                                          fixture_source_line=matched['source_line'], fixture_packet=matched['raw'])
                                     if matched else dict(origin='original_callback_origin_unverified', ecuEvidence=False))
            if line < start_line or at < start_ms:
                prestart.append(row)
                gaps.append({'callback_before_start': line, 'event': p['event']})
                continue
            if at < last_at:
                raise ValueError('callback clock regressed')
            last_at = at
            if p['event'] == 'internal_observation':
                internal.append(row)
                # A reset/state callback can be independently scheduled by SDK
                # work. Even a captured parent does not prove that replaying its
                # lifecycle input reproduces this work; require modelling first.
                gaps.append({'unrepresented_internal_processing': row})
                continue
            inputs.append(row)
            native_counts[p['event']] += 1
            if p['event'] == 'unsupported_callback':
                gaps.append({'unsupported_callback': p.get('method'), 'source_line': line, 'data': p['data']})
        except ValueError as exc:
            gaps.append({'source_line': line, 'invalid_callback': str(exc), 'raw': p})
    if ready_lines and start_line and ready_lines[0] > start_line:
        gaps.append({'sender_hooks_ready_after_start': ready_lines[0]})
    if len(ready) != 1:

        gaps.append({'sender_hooks_ready_count': len(ready)})
    if not required_hooks:
        gaps.append({'required_hook_inventory_missing': True})
    missing = [method for method in sorted(required_set) if hooks.get(method) != 'installed']
    if missing:
        gaps.append({'required_hooks_missing': missing})
    valid_fire_seq = bool(all_fire_seq) and all(type(v) is int for v in all_fire_seq)
    if not valid_fire_seq or sorted(all_fire_seq) != list(range(len(all_fire_seq))):
        gaps.append({'fire_sequence_loss': all_fire_seq[:5], 'count': len(all_fire_seq)})
    delivery_reordering = valid_fire_seq and all_fire_seq != sorted(all_fire_seq)
    # Frida send delivery from multiple Java threads is not boundary invocation
    # order. Canonicalize FIRE packets by the original boundary-assigned seq,
    # while retaining every raw source line and the complete mixed delivery file.
    delivery_reference = reference
    fire_rows = [r for r in reference if r['kind'] == 'fire']
    if valid_fire_seq:
        fire_rows = sorted(fire_rows, key=lambda r: r['seq'])
    other_rows = [r for r in reference if r['kind'] != 'fire']
    reference = fire_rows + other_rows
    for kind in sorted({r['kind'] for r in other_rows}):
        gaps.append({'mixed_reference_order_unmodelled': kind,
                     'policy': 'fire ordered by boundary seq; nonfire retained in delivery order sidecar'})
    runtime_identity, identity_artifacts, identity_gaps = bind_runtime_identity(raw, source)
    gaps.extend(identity_gaps)
    artifacts = list(artifacts or []) + identity_artifacts
    for name in ('nav_start', 'route'):
        if not native_counts[name]:
            gaps.append({'required_callback_missing': name})
    if receipt is not None and (receipt.get('status') != 'observed' or receipt.get('baseline_restored') is not True):
        gaps.append({'original_run_receipt_incomplete': receipt})
    if native_counts.get('end') or native_counts.get('advance'):
        gaps.append({'reserved_clock_events_from_recorder': True})
    if end_at < last_at:
        gaps.append({'capture_end_precedes_input': True})
    next_seq = max(last_seq + 1, 0)
    # This does not invent time after observation ends. No unobserved tail is replayed.
    for name in ('advance', 'end'):
        inputs.append(dict(kind='event', seq=next_seq, at_ms=max(end_at, last_at), event=name,
                           data={}, synthetic='capture clock boundary; latest observed event'))
        next_seq += 1
    input_path = out / 'inputs.jsonl'
    write_jsonl(input_path, inputs)
    ref_header = dict(kind='header', schema=REFERENCE_SCHEMA, origin='original-stock-dex',
                      input_sha256=digest(input_path), source=source, start_ms=start_ms, fixture_inputs=fixture_inputs,
                      prestart_records=len(prestart), scope=header['start_scope'],
                      ordering='fire boundary seq, then nonfire delivery order; mixed order retained in reference-delivery.jsonl',
                      frida_delivery_reordering=delivery_reordering)
    write_jsonl(out / 'reference.jsonl', [ref_header] + reference +
                [dict(kind='coverage_gap', data=gap) for gap in gaps])
    write_jsonl(out / 'reference-delivery.jsonl', [ref_header] + delivery_reference)
    write_jsonl(out / 'prestart.jsonl', prestart)
    write_jsonl(out / 'internal-observations.jsonl', internal)
    report = dict(schema='denza-stock-sender-capture-v1', status='INCOMPLETE' if gaps else 'CAPTURED',
                  parityEstablished=False, gaps=gaps, frida_delivery_reordering=delivery_reordering,
                  runtime_identity=runtime_identity, fixture_inputs=fixture_inputs, native_event_counts=dict(native_counts),
                  reference_counts=dict(Counter(r['kind'] for r in reference)),
                  prestart_records=len(prestart), start_ms=start_ms, end_ms=max(end_at, last_at),
                  hooks=hooks, required_hooks=sorted(required_set), internal_observation_count=len(internal), raw_path=str(raw.resolve()),
                  raw_sha256=digest(raw), input_sha256=digest(input_path),
                  reference_sha256=digest(out / 'reference.jsonl'), source=source,
                  artifacts=artifacts or [], receipt=receipt, metadata=metadata or {})
    (out / 'capture-manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return report


def required_hooks(recorder: Path) -> list[str]:
    # Recorder declares a JSON literal array so inventory can be checked without JS execution.
    match = re.search(r'const\s+SP_REQUIRED_HOOKS\s*=\s*(\[[\s\S]*?\])\s*;', recorder.read_text())
    if not match:
        raise ValueError('stock_inputs.js must declare const SP_REQUIRED_HOOKS = JSON array;')
    methods = ast.literal_eval(match.group(1))
    if not isinstance(methods, list) or not methods or any(not isinstance(v, str) for v in methods):
        raise ValueError('invalid required hook inventory')
    return methods


def run(args) -> dict:
    from tools.adas_bridge import stock_trace
    emulator_serial(args.serial)
    if not 1 <= args.frida_port <= 65535:
        raise ValueError('valid explicit Frida port required')
    case = json.loads(args.case.read_text())
    seconds = args.seconds or len(case['samples']) + 40
    if seconds < len(case['samples']) + 30 or seconds > 600:
        raise ValueError('seconds must cover samples + 30 s startup and stay <= 600')
    hooks = required_hooks(args.recorder)
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / 'case.json').write_bytes(args.case.read_bytes())
    observer = stock_trace.observer_text(case, answers=not args.no_answers,
              answer_delay_ms=args.answer_delay_ms, online_wait_ms=args.online_wait_ms,
              seconds=seconds, arrive=not args.no_arrive) + '\n' + args.recorder.read_text()
    receipt = stock_trace.install_run(args.build, observer,
                dict(lon=case['route_start_sdk'][0], lat=case['route_start_sdk'][1], zoom=16),
                args.out / 'run', seconds=seconds, serial=args.serial, frida_port=args.frida_port)
    recorder = args.out / 'run/observer.js'
    provenance, artifacts = original_artifacts(args.apk, recorder, args.out)
    for role, path in [('case', args.case), ('input_recorder', args.recorder),
                       ('base_observer', stock_trace.HERE / 'stock_feed_observer.js'),
                       ('receipt', args.out / 'run/receipt.json')]:
        artifacts.append(dict(role=role, path=str(path.resolve()), sha256=digest(path)))
    return normalize(args.out / 'run/app/events.jsonl', args.out, source=provenance,
                     required_hooks=hooks, artifacts=artifacts, receipt=receipt,
                     metadata=dict(serial=args.serial, frida_port=args.frida_port,
                                   build=str(args.build.resolve()), seconds=seconds))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('run')
    for option in ('build', 'case', 'out'):
        p.add_argument('--' + option, required=True, type=Path)
    p.add_argument('--serial', required=True, type=emulator_serial)
    p.add_argument('--frida-port', required=True, type=int)
    p.add_argument('--recorder', type=Path, default=HERE / 'stock_inputs.js')
    p.add_argument('--apk', type=Path, default=APK)
    p.add_argument('--seconds', type=int)
    p.add_argument('--online-wait-ms', type=int, default=0)
    p.add_argument('--answer-delay-ms', type=int, default=80)
    p.add_argument('--no-answers', action='store_true')
    p.add_argument('--no-arrive', action='store_true')
    p = sub.add_parser('normalize')
    for option in ('raw', 'out', 'recorder'):
        p.add_argument('--' + option, required=True, type=Path)
    p.add_argument('--apk', type=Path, default=APK)
    p.add_argument('--receipt', required=True, type=Path)
    args = parser.parse_args()
    if args.command == 'run':
        report = run(args)
    else:
        hooks = required_hooks(args.recorder)
        args.out.mkdir(parents=True, exist_ok=True)
        provenance, artifacts = original_artifacts(args.apk, args.recorder, args.out)
        artifacts.append(dict(role='receipt', path=str(args.receipt.resolve()), sha256=digest(args.receipt)))
        report = normalize(args.raw, args.out, source=provenance, required_hooks=hooks, artifacts=artifacts,
                           receipt=json.loads(args.receipt.read_text()))
    print(json.dumps(dict(status=report['status'], gaps=len(report['gaps']), parityEstablished=False)))
    return 0 if report['status'] == 'CAPTURED' else 2


if __name__ == '__main__':
    raise SystemExit(main())
