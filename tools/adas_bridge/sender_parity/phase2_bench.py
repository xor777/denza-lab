"""Strict full sender acceptance against ORIGINAL phase2 getter/queue capture.

No packet alignment, timestamp exception, startup clipping or unsupported-event
filter can make this bench equal. Actual getter/clock reads are transcript inputs;
original outputs are references only and never fed to the source-neutral model.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
from typing import Any

from . import bench, capture, phase2_capture as p2

ROOT = Path(__file__).resolve().parents[3]
JAVA = Path(__file__).with_name('phase2') / 'NativePhase2Replay.java'
PORT_SCHEMA = 'denza-stock-sender-phase2-port-v1'
TIMER_KINDS = {'timer_post_enter', 'timer_post_result', 'timer_cancel', 'timer_execute_enter', 'timer_execute_exit'}
EFFECT_KINDS = TIMER_KINDS | {'output', 'environment_operation'}
# Exact source-owned processing boundary contract shared with NativePhase2Replay.
MUTATION_BARRIERS = {
    'k.h.a0.c.c.a.e0.' + name for name in
    'B0 D0 F H J L N P R T V X Z b0 d0 f0 h0 i0 k0 l0 n0 p0 r0 t0 v0 x0 z0 z F0'.split()
} | {'k.h.e0.c.g.' + name for name in 'C E G I K'.split()} | {
    'k.h.e0.c.f.e', 'k.h.e0.d.f.k.f', 'k.h.e0.d.f.k.g', 'k.h.e0.d.f.k.i'
} | {'k.h.a0.c.c.a.f0.' + name for name in 'cdefghijklmnopqrs'}
TIMER_FIELDS = {
    'timer_post_enter': ('post_id', 'handler', 'runnable', 'token', 'message', 'queue_id', 'due_uptime_ms', 'insertion_seq'),
    'timer_post_result': ('post_id', 'result'),
    'timer_cancel': ('handler', 'method', 'args', 'matched_post_ids'),
    'timer_execute_enter': ('post_id', 'handler', 'runnable', 'message', 'queue_id'),
    'timer_execute_exit': ('post_id', 'handler', 'runnable', 'message', 'queue_id'),
}


def validate_capture(manifest_path: Path) -> dict:
    """Rehash original artifacts AND prove normalized rows equal immutable raw."""
    manifest = json.loads(manifest_path.read_text())
    if manifest.get('schema') != 'denza-stock-sender-phase2-capture-v1':
        raise ValueError('phase2 capture manifest required')
    if manifest.get('source', {}).get('apk_sha256') != p2.APK_SHA256:
        raise ValueError('original APK corpus pin differs')
    verified = bench.validate_artifacts(manifest_path, manifest['source'])
    roles = {artifact['role']: artifact for artifact in verified}
    if not {'runtime_report', 'runtime_agent'} <= set(roles):
        raise ValueError('verified actual runtime report and agent required')
    if Path(roles['recorder']['path']).read_bytes() not in Path(roles['runtime_agent']['path']).read_bytes():
        raise ValueError('immutable recorder snapshot not present in actual loaded agent')
    raw = Path(manifest['raw_path'])
    if not raw.is_absolute():
        raw = manifest_path.parent / raw
    if bench.digest(raw) != manifest.get('raw_sha256'):
        raise ValueError('original raw capture bytes differ')
    ledger_path, reference_path = manifest_path.parent / 'ledger.jsonl', manifest_path.parent / 'reference.jsonl'
    ledger_sha = bench.digest(ledger_path)
    if ledger_sha != manifest.get('ledger_sha256') or bench.digest(reference_path) != manifest.get('reference_sha256'):
        raise ValueError('normalized ledger/reference bytes differ')
    ledger, reference = bench.read_jsonl(ledger_path), bench.read_jsonl(reference_path)
    lh, rh = ledger[0], reference[0]
    if lh.get('schema') != p2.LEDGER_SCHEMA or rh.get('schema') != p2.REFERENCE_SCHEMA:
        raise ValueError('phase2 ledger/reference headers required')
    if any(h.get('kind') != 'header' or h.get('origin') != 'original-stock-dex' or
           h.get('source') != manifest['source'] or h.get('startup_clipping') is not False for h in (lh, rh)):
        raise ValueError('original full-capture provenance/header mismatch')
    if lh.get('runtime_identity') != manifest.get('runtime_identity'):
        raise ValueError('ledger actual runtime identity differs')
    if rh.get('ledger_sha256') != ledger_sha:
        raise ValueError('reference belongs to another ledger')
    if lh.get('raw_capture', {}).get('sha256') != manifest['raw_sha256']:
        raise ValueError('ledger belongs to another raw capture')
    raw_rows, parse_gaps = capture.read_raw(raw)
    canonical = [dict(payload, source_line=line) for line, payload in raw_rows if payload.get('schema') == p2.SCHEMA]
    seqs = [row.get('seq') for row in canonical]
    if any(type(seq) is not int for seq in seqs) or sorted(seqs) != list(range(len(seqs))):
        raise ValueError('original global sequence lost/duplicated')
    canonical.sort(key=lambda row: row['seq'])
    if ledger[1:] != canonical:
        raise ValueError('normalized transcript does not exactly preserve every original event')
    outputs = [row for row in canonical if row.get('kind') == 'output']
    reference_effects = [row for row in canonical if row.get('kind') in ('output', 'environment_operation')]
    if [row for row in reference[1:] if row.get('kind') in ('output', 'environment_operation')] != reference_effects:
        raise ValueError('reference does not exactly preserve all original output/SDK operation effects')
    unknown_reference = [row for row in reference[1:] if row.get('kind') not in ('output', 'environment_operation', 'coverage_gap')]
    if unknown_reference:
        raise ValueError('unknown reference row cannot be ignored')
    audit = p2.audit_ledger(canonical, manifest.get('required_hooks', []))
    gaps = list(manifest.get('gaps', [])) + parse_gaps + audit['gaps']
    from . import scenario_gate
    scenario_audit = scenario_gate.declared_audit(canonical, manifest.get('metadata') or {},
        manifest.get('artifacts', []), Path(roles['runtime_agent']['path']))
    if scenario_audit is not None:
        gaps.extend(scenario_audit['gaps'])
        if not any('scenario_declaration_invalid' in gap for gap in scenario_audit['gaps']):
            verified += [a for a in manifest.get('artifacts', []) if a.get('role') == 'scenario_contract']
        if lh.get('scenario_contract') != (manifest.get('metadata') or {}).get('scenario_contract'):
            gaps.append(dict(scenario_ledger_contract_differs=True))
    elif 'scenario_contract' in lh:
        gaps.append(dict(scenario_ledger_contract_undeclared=True))
    from . import callback_audit as callback_gate
    callback_report = callback_gate.declared_audit([dict(payload, source_line=line) for line, payload in raw_rows],
        manifest.get('metadata') or {}, manifest.get('artifacts', []), Path(roles['runtime_agent']['path']))
    if callback_report is not None:
        gaps.extend(callback_report['gaps'])
        verified += callback_report['verified_artifacts']
        if lh.get('callback_contract') != (manifest.get('metadata') or {}).get('callback_contract'):
            gaps.append(dict(callback_ledger_contract_differs=True))
    elif 'callback_contract' in lh:
        gaps.append(dict(callback_ledger_contract_undeclared=True))
    from . import runtime_contract
    runtime_report = runtime_contract.audit([dict(payload, source_line=line) for line, payload in raw_rows],
        manifest.get('metadata') or {}, manifest.get('artifacts', []), Path(roles['runtime_agent']['path']))
    gaps.extend(runtime_report['gaps'])
    verified += runtime_report['verified_artifacts']
    if runtime_report.get('input_boundary_audit') is not None:
        if lh.get('input_boundary_policy') != (manifest.get('metadata') or {}).get('input_boundary_policy'):
            gaps.append(dict(input_boundary_ledger_policy_differs=True))
    elif 'input_boundary_policy' in lh:
        gaps.append(dict(input_boundary_ledger_policy_undeclared=True))
    if runtime_report.get('terminal_policy_audit') is not None:
        if lh.get('terminal_policy') != (manifest.get('metadata') or {}).get('terminal_policy'):
            gaps.append(dict(terminal_ledger_policy_differs=True))
    elif 'terminal_policy' in lh:
        gaps.append(dict(terminal_ledger_policy_undeclared=True))
    gaps += [row['data'] for row in reference[1:] if row.get('kind') == 'coverage_gap']
    if manifest.get('status') != 'CAPTURED':
        gaps.append(dict(capture_status=manifest.get('status')))
    receipt = manifest.get('receipt') or {}
    if receipt.get('status') != 'observed' or receipt.get('baseline_restored') is not True:
        gaps.append(dict(original_run_receipt_incomplete=receipt))
    if not any(row.get('kind') == 'call_enter' and row.get('role') == 'external_input' for row in canonical):
        gaps.append(dict(external_input_receipts_missing=True))
    if not outputs:
        gaps.append(dict(original_reference_outputs_missing=True))
    return dict(manifest=manifest, ledger_path=ledger_path, ledger=ledger,
                ledger_sha256=ledger_sha, verified_artifacts=verified, gaps=gaps,
                scenario_audit=scenario_audit, callback_audit=callback_report, runtime_contract_audit=runtime_report)


def effect(row: dict) -> dict:
    """Compare semantic effects; recorder observation latency is not a model clock."""
    kind = row.get('kind')
    if kind == 'environment_operation':
        p2.environment_argument_objects(row)
        return {key: row[key] for key in ('kind', 'operation', 'method', 'read_id', 'args')}
    if kind == 'output':
        operation, topic, payload = row.get('operation'), row.get('topic'), row.get('payload_b64')
        if operation not in ('fire', 'client_send', 'start_service', 'stop_service'):
            raise ValueError('unknown computed transport operation')
        if 'topic' not in row or (topic is not None and (not isinstance(topic, str) or not topic.isdecimal())):
            raise ValueError('exact topic decimal string/null required')
        if 'payload_b64' not in row or (payload is not None and not isinstance(payload, str)):
            raise ValueError('exact payload base64/null required')
        if operation in ('fire', 'client_send') and payload is None:
            raise ValueError('complete original packet/request bytes required')
        # Base64 spelling is not a wire value. Exact decoded bytes, including
        # field presence and route wall time, are the comparison boundary.
        return dict(kind=kind, operation=operation, topic=topic,
                    payload_hex=base64.b64decode(payload, validate=True).hex() if payload is not None else None)
    if kind not in TIMER_KINDS:
        raise ValueError('unknown effect kind')
    missing = [name for name in TIMER_FIELDS[kind] if name not in row]
    if missing:
        raise ValueError('computed timer ledger missing ' + ','.join(missing))
    result = dict(kind=kind, **{key: row[key] for key in TIMER_FIELDS[kind]})
    # Message.what is consumed by removeMessages; do not discard it when the
    # original recorder provides it. Port must independently compute it too.
    if kind == 'timer_post_enter' and 'what' in row:
        result['what'] = row['what']
    return result


def validate_environment_bindings(bindings: list[dict], ledger: list[dict]) -> list[dict]:
    """Bind SDK argument identity/class only; actual computed enumeration is exact."""
    gaps, seen, forward, reverse = [], set(), {}, {}
    operations = {r['seq']: r for r in ledger if r.get('kind') == 'environment_operation'}
    expected = {}
    for seq, row in operations.items():
        try:
            expected.update({(seq, path): item for path, item in p2.environment_argument_objects(row).items()})
        except (ValueError, TypeError, KeyError) as exc:
            gaps.append(dict(invalid_original_environment_arguments=str(exc), source_seq=seq))
    for binding in bindings:
        try:
            seq, path = binding.get('source_seq'), binding.get('argument_path')
            if type(seq) is not int or not isinstance(path, str) or (seq, path) not in expected:
                raise ValueError('exact original SDK operation seq and argument path required')
            original, operation = expected[(seq, path)], operations[seq]
            if (binding.get('read_id') != operation['read_id'] or binding.get('original_id') != original['id']
                    or binding.get('original_class') != original['class']):
                raise ValueError('SDK argument binding original identity/class/read differs')
            if binding.get('reason') != 'SDK API argument identity/class after complete content verification':
                raise ValueError('only verified SDK argument identity/class binding permitted; enumeration cannot be borrowed')
            port_id, port_class = binding.get('port_id'), binding.get('port_class')
            if not isinstance(port_id, str) or not port_id or not isinstance(port_class, str) or not port_class:
                raise ValueError('computed stable SDK argument identity/class required')
            if (seq, path) in seen:
                raise ValueError('duplicate SDK argument identity binding')
            key, reverse_key = (original['class'], original['id']), (port_class, port_id)
            if key in forward and forward[key] != reverse_key or reverse_key in reverse and reverse[reverse_key] != key:
                raise ValueError('SDK argument mapping is not stable and one-to-one')
            seen.add((seq, path)); forward[key], reverse[reverse_key] = reverse_key, key
        except (ValueError, TypeError, KeyError) as exc:
            gaps.append(dict(invalid_environment_argument_binding=str(exc), binding=binding))
    for seq, path in expected.keys() - seen:
        gaps.append(dict(missing_environment_argument_binding=dict(source_seq=seq, argument_path=path)))
    return gaps


def validate_bindings(bindings: list[dict], ledger: list[dict]) -> list[dict]:
    """Require explicit stable timer identity mappings; never hide remapping."""
    gaps, forward, reverse = [], {}, {}
    by_seq = {row['seq']: row for row in ledger}
    reasons = {'handler': 'first handler queue binding',
               'runnable': 'first originalClass-matched runnable post',
               'token': 'literal token value binding',
               'message': 'environment Message allocation identity'}
    def references(item):
        if isinstance(item, dict):
            if item.get('type') == 'object':
                yield item
            for child in item.values():
                yield from references(child)
        elif isinstance(item, list):
            for child in item:
                yield from references(child)
    for binding in bindings:
        try:
            kind, queue = binding.get('identity_kind'), binding.get('queue_id')
            original, port_id = binding.get('original'), binding.get('port_id')
            if kind not in reasons or queue not in ('MMT', 'MAIN', 'SOMEIP'):
                raise ValueError('known identity kind/queue required')
            if p2.typed_errors(original) or original.get('type') != 'object':
                raise ValueError('binding requires exact original object reference')
            if not isinstance(port_id, str) or not port_id:
                raise ValueError('computed stable object identity required')
            if binding.get('reason') != reasons[kind]:
                raise ValueError('identity mapping reason missing/unrecognized')
            source_seq = binding.get('source_seq')
            source = by_seq.get(source_seq) if type(source_seq) is int else None
            if source is None or source['kind'] not in ('timer_post_enter', 'timer_cancel'):
                raise ValueError('first post/cancel provenance required')
            if original not in list(references(source)):
                raise ValueError('binding original object not present in declared source occurrence')
            if source.get('queue_id', queue) != queue:
                raise ValueError('binding queue differs from original occurrence')
            key, other = (kind, queue, original['id']), (kind, queue, port_id)
            if key in forward and forward[key] != port_id:
                raise ValueError('original identity remapped to another computed object')
            if other in reverse and reverse[other] != original['id']:
                raise ValueError('computed identity aliases distinct original objects')
            forward[key], reverse[other] = port_id, original['id']
        except (ValueError, TypeError, KeyError) as exc:
            gaps.append(dict(invalid_identity_binding=str(exc), binding=binding))
    for row in ledger:
        if row.get('kind') not in ('timer_post_enter', 'timer_cancel'):
            continue
        for kind in ('handler', 'runnable', 'token', 'message'):
            original = row.get(kind)
            if not isinstance(original, dict) or original.get('type') != 'object':
                continue
            queue = row.get('queue_id')
            if queue:
                present = (kind, queue, original['id']) in forward
            else:
                present = any(key[0] == kind and key[2] == original['id'] for key in forward)
            if not present:
                gaps.append(dict(missing_identity_binding=dict(identity_kind=kind, original=original, source_seq=row['seq'])))
    return gaps


def validate_state_bindings(receipts: list[dict], ledger: list[dict]) -> list[dict]:
    """Check assertion identities without importing original bean state values."""
    gaps, forward, reverse = [], {}, {}
    calls = {r['call_id']: r for r in ledger if r.get('kind') == 'call_enter'}
    by_seq = {r['seq']: r for r in ledger}

    def identities(value, path, found):
        if not isinstance(value, dict):
            return
        if 'id' in value:
            found[path] = value
        for name, child in value.get('fields', {}).items():
            identities(child, path + '.fields.' + name, found)
        for index, child in enumerate(value.get('items', [])):
            identities(child, path + f'.items[{index}]', found)

    for receipt in receipts:
        if receipt.get('operation') not in ('state entry', 'state exit'):
            continue
        try:
            source = by_seq[receipt['source_seq']]
            entry = calls[source['call_id']]
            expected = {}
            identities(entry['receiver'], 'receiver', expected)
            key = 'args' if source['kind'] == 'call_enter' else 'args_after'
            for index, value in enumerate(source[key]):
                identities(value, f'{key}[{index}]', expected)
            if source['kind'] == 'call_exit':
                identities(source['result'], 'result', expected)
            bindings = receipt.get('state_bindings')
            if not isinstance(bindings, list):
                raise ValueError('explicit state identity binding array required')
            seen = set()
            for binding in bindings:
                path = binding.get('argument_path')
                if path not in expected or path in seen:
                    raise ValueError('exact unique original state identity path required')
                original = expected[path]
                if binding.get('original_id') != original['id'] or binding.get('original_class') != original['class']:
                    raise ValueError('state identity/class differs from original assertion')
                port_id, port_class = binding.get('port_id'), binding.get('port_class')
                if not isinstance(port_id, str) or not port_id or not isinstance(port_class, str) or not port_class:
                    raise ValueError('computed stable state identity/class required')
                before, after = (original['class'], original['id']), (port_class, port_id)
                if ((before in forward and forward[before] != after)
                        or (after in reverse and reverse[after] != before)):
                    raise ValueError('state identity mapping is not stable and one-to-one')
                forward[before], reverse[after] = after, before
                seen.add(path)
            if seen != set(expected):
                raise ValueError('state assertion identity bindings incomplete')
        except (ValueError, TypeError, KeyError) as exc:
            gaps.append(dict(invalid_state_binding=str(exc), receipt=receipt))
    return gaps


def validate_diagnostics(rows: list[dict], ledger: list[dict]) -> list[dict]:
    """Bound declared environment seams to exact original read spans/call sites."""
    gaps, by_seq, starts, completions, parent_reads, stacks = [], {}, {}, {}, {}, {}
    calls = {row['call_id']: row for row in ledger if row.get('kind') == 'call_enter'}
    for row in ledger:
        by_seq[row['seq']] = row
        if row.get('kind') not in ('getter_enter', 'clock_enter', 'getter', 'clock'):
            continue
        tid, read_id = row['thread']['id'], row['read_id']
        stack = stacks.setdefault(tid, [])
        if row['kind'].endswith('_enter'):
            parent_reads[read_id] = stack[-1] if stack else None
            starts[read_id] = row
            stack.append(read_id)
        else:
            completions[read_id] = row
            if stack and stack[-1] == read_id:
                stack.pop()
    tags = {
        'external callback receipt': {'call_enter'},
        'processing entry': {'call_enter'}, 'processing exit': {'call_exit'},
        'external callback exit': {'call_exit'},
        'state entry': {'call_enter'}, 'state exit': {'call_exit'},
        'getter initiation': {'getter_enter'}, 'clock initiation': {'clock_enter'},
        'read completion': {'getter', 'clock'},
        'independently computed enqueue; original occurrence identity bound after validation': {'timer_post_enter'},
    }
    nested = 'nested external native/platform read; delegated original implementation'
    diagnostic = 'source-proven diagnostic Calendar/Java logger clock; value never enters sender state'
    finish_diagnostic = 'source-proven diagnostic clock completion'
    finish_nested = 'external delegated read completion'
    accepted = {}
    classifications = {}
    for record in rows:
        try:
            seq = record.get('source_seq')
            source = by_seq.get(seq) if type(seq) is int else None
            if source is None or record.get('method') != source.get('method'):
                raise ValueError('exact existing source_seq and full original method required')
            if seq in accepted:
                raise ValueError('duplicate consumption/environment receipt for original event')
            if record['kind'] == 'consumption_receipt':
                tag = record.get('operation')
                if tag not in tags or source['kind'] not in tags[tag]:
                    raise ValueError('receipt operation does not match original event kind')
                is_state = str(source.get('method', '')).split('(')[0] in p2.STATE_METHODS
                if tag in ('state entry', 'state exit'):
                    if not is_state or (source if source['kind'] == 'call_enter' else calls.get(source.get('call_id'), {})).get('role') != 'internal_processing':
                        raise ValueError('state receipt must assert source-owned internal processing')
                    flags = ('args_verified', 'receiver_verified') if tag == 'state entry' else ('args_after_verified', 'result_verified', 'receiver_verified')
                    if any(record.get(flag) is not True for flag in flags):
                        raise ValueError('computed state arguments/receiver/result were not independently verified')
                elif is_state:
                    raise ValueError('source-owned state cannot be consumed through an input/environment seam')
                if tag == 'external callback receipt' and source.get('role') != 'external_input':
                    raise ValueError('external receipt cannot promote internal processing to an input')
                if (tag == 'read completion' and source.get('method') in p2.ENVIRONMENT_METHODS.values()
                        and record.get('sdk_args_verified') is not True):
                    raise ValueError('SDK public call completion arguments were not independently verified')
                if tag in ('processing exit', 'external callback exit'):
                    if record.get('args_after_verified') is not True:
                        raise ValueError('actual input argument mutation assertion was not verified')
                    if ('retained_inputs_after' in source
                            and record.get('retained_lightbar_verified') is not True):
                        raise ValueError('retained original lightbar mutation assertion was not verified')
                    if tag == 'external callback exit' and record.get('result_verified') is not True:
                        raise ValueError('actual original external callback result was not verified')
                if tag in ('getter initiation', 'clock initiation'):
                    classifications[source['read_id']] = 'consumed'
            else:
                reason = record.get('reason')
                read_id = source.get('read_id')
                if reason == nested:
                    if source['kind'] not in ('getter_enter', 'clock_enter') or parent_reads.get(read_id) is None:
                        raise ValueError('delegated observation must be nested inside an actual external read')
                    classifications[read_id] = 'delegated'
                elif reason == diagnostic:
                    if source['kind'] != 'clock_enter' or source.get('method') != 'java.lang.System.currentTimeMillis()':
                        raise ValueError('diagnostic seam restricted to proven wall-clock reads')
                    end = completions.get(read_id, {})
                    sites = end.get('sites', [])
                    logger = any(isinstance(site, str) and site.startswith('java.util.logging.') for site in sites)
                    calendar = any(isinstance(site, str) and site.startswith(('java.util.Calendar.', 'java.util.GregorianCalendar.')) for site in sites)
                    owner = calls.get(source.get('call_id'), {})
                    # Pinned original f0.l: Calendar value is used only in the
                    # throttled Logger.e argument at original line159.
                    light_logger = calendar and str(owner.get('method', '')).startswith('k.h.a0.c.c.a.f0.l(')
                    if not (logger or light_logger):
                        raise ValueError('diagnostic wall clock lacks source-proven logger callsite')
                    classifications[read_id] = 'diagnostic'
                elif reason in (finish_diagnostic, finish_nested):
                    if source['kind'] not in ('getter', 'clock'):
                        raise ValueError('delegated completion is not a read completion')
                else:
                    raise ValueError('unknown environment observation seam')
            accepted[seq] = record
        except (ValueError, TypeError, KeyError) as exc:
            gaps.append(dict(invalid_consumption_diagnostic=str(exc), record=record))
    gaps.extend(validate_state_bindings(list(accepted.values()), ledger))
    for row in ledger:
        if row.get('kind') not in ('call_enter', 'call_exit'):
            continue
        entry = row if row['kind'] == 'call_enter' else calls.get(row.get('call_id'), {})
        if entry.get('method', '').split('(')[0] in p2.STATE_METHODS:
            required = 'state entry' if row['kind'] == 'call_enter' else 'state exit'
            if accepted.get(row['seq'], {}).get('operation') != required:
                gaps.append(dict(state_assertion_receipt_missing=dict(source_seq=row['seq'], operation=required)))
    def root_category(read_id):
        seen = set()
        while read_id is not None and read_id not in seen:
            seen.add(read_id)
            category = classifications.get(read_id)
            if category != 'delegated':
                return category
            read_id = parent_reads.get(read_id)
        return None
    external_roots = set()
    lifecycle = {'k.h.e0.b.d.i', 'k.h.e0.b.d.k', 'k.h.e0.b.d.l'}
    for row in ledger:
        if row.get('kind') != 'call_enter' or row.get('role') != 'external_input':
            continue
        parent, owned = row.get('parent_call_id'), False
        while parent is not None:
            ancestor = calls.get(parent, {})
            if (parent in external_roots
                    and ancestor.get('method', '').split('(')[0] not in lifecycle):
                owned = True
                break
            parent = ancestor.get('parent_call_id')
        if not owned:
            external_roots.add(row['call_id'])
            receipt = accepted.get(row['seq'], {})
            if receipt.get('operation') != 'external callback receipt':
                gaps.append(dict(external_input_consumption_receipt_missing=row['seq']))
    for row in ledger:
        if row.get('kind') != 'call_exit':
            continue
        entry = calls.get(row.get('call_id'), {})
        base = entry.get('method', '').split('(')[0]
        component_root = base == 'k.h.e0.d.f.k.f' and row.get('call_id') in external_roots
        required = ('processing exit' if base in MUTATION_BARRIERS and not component_root else
                    'external callback exit' if row.get('call_id') in external_roots else None)
        if required is not None and accepted.get(row['seq'], {}).get('operation') != required:
            gaps.append(dict(input_mutation_verification_receipt_missing=dict(source_seq=row['seq'], operation=required)))
    for read_id, start in starts.items():
        end = completions.get(read_id)
        first, last = accepted.get(start['seq']), accepted.get(end['seq']) if end else None
        category = classifications.get(read_id)
        if start.get('method') in p2.ENVIRONMENT_METHODS.values() and category != 'consumed':
            gaps.append(dict(sdk_public_operation_read_cannot_be_delegated=read_id))
        if first is None or last is None:
            gaps.append(dict(native_read_consumption_receipt_missing=dict(read_id=read_id, enter_seq=start['seq'], completion_seq=end.get('seq') if end else None)))
            continue
        if category == 'consumed':
            if last.get('kind') != 'consumption_receipt' or last.get('operation') != 'read completion':
                gaps.append(dict(native_read_completion_kind_mismatch=read_id))
        elif category in ('delegated', 'diagnostic'):
            root = root_category(read_id)
            expected = finish_diagnostic if root == 'diagnostic' else finish_nested
            if root not in ('consumed', 'diagnostic') or last.get('kind') != 'environment_observation' or last.get('reason') != expected:
                gaps.append(dict(unbounded_external_read_delegation=read_id))
        else:
            gaps.append(dict(unclassified_native_read_consumption=read_id))
    return gaps


def compare(manifest_path: Path, port_path: Path) -> dict:
    report = dict(schema='denza-stock-sender-phase2-comparison-v1', verdict='INCOMPLETE',
                  equivalenceEstablished=False, capture_gaps=[], driver_gaps=[], mismatches=[],
                  normalization=[], observation_metadata_policy='Only recorder envelope/latency metadata excluded; no source clock, due time, payload or semantic field masking')
    try:
        valid = validate_capture(manifest_path)
        report.update(ledger_sha256=valid['ledger_sha256'], verified_artifacts=valid['verified_artifacts'],
                      capture_manifest_sha256=bench.digest(manifest_path), capture_gaps=valid['gaps'],
                      scenario_audit=valid['scenario_audit'], callback_audit=valid['callback_audit'],
                      runtime_contract_audit=valid['runtime_contract_audit'])
    except (OSError, ValueError, KeyError, TypeError) as exc:
        report['capture_gaps'].append(dict(capture_provenance_or_integrity_failure=str(exc)))
        return report
    try:
        port = bench.read_jsonl(port_path)
    except (OSError, ValueError) as exc:
        report['driver_gaps'].append(dict(port_ledger_unavailable=str(exc)))
        return report
    header = port[0]
    if (header.get('kind') != 'header' or header.get('schema') != PORT_SCHEMA or
        header.get('ledger_sha256') != valid['ledger_sha256'] or
        header.get('source') != valid['manifest']['source']):
        report['driver_gaps'].append(dict(port_header_does_not_bind_exact_original_ledger=True))
        return report
    original = [row for row in valid['ledger'][1:] if row.get('kind') in EFFECT_KINDS]
    actual = []
    completions, bindings, diagnostics, environment_bindings = [], [], [], []
    driver_rows = port[1:]
    for index, row in enumerate(driver_rows):
        kind = row.get('kind')
        if completions:
            report['driver_gaps'].append(dict(event_after_replay_complete=index + 1, row=row))
        if kind in EFFECT_KINDS:
            actual.append(row)
        elif kind == 'replay_complete':
            completions.append(row)
            if type(row.get('unconsumed')) is not int or row['unconsumed'] != 0:
                report['driver_gaps'].append(dict(unconsumed_original_observations=row))
        elif kind in ('coverage_gap', 'recorder_error', 'replay_error'):
            report['driver_gaps'].append(row)
        elif kind == 'replay_mismatch':
            report['mismatches'].append(dict(driver_mismatch=row))
        elif kind == 'identity_binding':
            bindings.append(row)
        elif kind == 'environment_argument_binding':
            environment_bindings.append(row)
        elif kind in ('environment_observation', 'consumption_receipt'):
            diagnostics.append(row)
        else:
            report['driver_gaps'].append(dict(unknown_driver_event=row))
    if len(completions) != 1:
        report['driver_gaps'].append(dict(replay_complete_count=len(completions)))
    for index in range(max(len(original), len(actual))):
        left = original[index] if index < len(original) else None
        right = actual[index] if index < len(actual) else None
        if left is None or right is None:
            report['mismatches'].append(dict(effect_index=index, reason='extra_port_effect' if left is None else 'missing_port_effect',
                                             original=left, port=right))
            continue
        try:
            before, after = effect(left), effect(right)
        except (ValueError, TypeError) as exc:
            report['driver_gaps'].append(dict(effect_index=index, invalid_effect=str(exc), original=left, port=right))
            continue
        if before != after:
            keys = sorted(key for key in set(before) | set(after) if before.get(key) != after.get(key))
            report['mismatches'].append(dict(effect_index=index, original_source_seq=left['seq'],
                                             reason='effect_fields', differing_fields=keys, original=before, port=after))
    report['counts'] = dict(original=dict(Counter(row['kind'] for row in original)),
                            port=dict(Counter(row['kind'] for row in actual)), original_effects=len(original), port_effects=len(actual))
    report['identity_bindings'] = bindings
    report['driver_gaps'].extend(validate_bindings(bindings, valid['ledger'][1:]))
    report['environment_argument_bindings'] = environment_bindings
    report['driver_gaps'].extend(validate_environment_bindings(environment_bindings, valid['ledger'][1:]))
    report['consumption_diagnostics'] = diagnostics
    report['driver_gaps'].extend(validate_diagnostics(diagnostics, valid['ledger'][1:]))
    report['mismatch_count'] = len(report['mismatches'])
    report['first_divergences'] = report['mismatches'][:20]
    report['port_ledger_sha256'] = bench.digest(port_path)
    if report['mismatches']:
        report['verdict'] = 'DIFFERENT'
    elif not report['capture_gaps'] and not report['driver_gaps']:
        report['verdict'] = 'EQUAL'
        report['equivalenceEstablished'] = True
    return report


def replay(manifest_path: Path, port_path: Path, build: Path) -> dict:
    # Fail before executing anything if actual original provenance was modified.
    valid = validate_capture(manifest_path)
    build.mkdir(parents=True, exist_ok=True)
    port_path.parent.mkdir(parents=True, exist_ok=True)
    sources = [path for path in sorted((ROOT / 'libraries/adas-link/src/main/java').rglob('*.java'))
               if 'import android.' not in path.read_text()]
    driver_sources = sorted(JAVA.parent.glob('*.java'))
    if JAVA not in driver_sources:
        raise ValueError('NativePhase2Replay Java driver is unavailable')
    runtime = bench.jdk()
    subprocess.run([str(runtime / 'javac'), '--release', '8', '-encoding', 'UTF-8', '-nowarn', '-d', str(build)] +
                   [str(path) for path in sources] + [str(path) for path in driver_sources], check=True, capture_output=True, text=True,
                   cwd=ROOT, timeout=120)
    command = [str(runtime / 'java'), '-cp', str(build), 'dev.denza.adaslink.stocknative.NativePhase2Replay',
               str(valid['ledger_path']), str(port_path)]
    process = subprocess.run(command, check=False, capture_output=True, text=True, cwd=ROOT, timeout=120)
    report = compare(manifest_path, port_path)
    if process.returncode:
        report['driver_gaps'].append(dict(driver_exit=process.returncode, stderr=process.stderr, stdout=process.stdout))
        if report['verdict'] == 'EQUAL':
            report['verdict'], report['equivalenceEstablished'] = 'INCOMPLETE', False
    report['replay'] = dict(exit_code=process.returncode, driver_sha256=bench.digest(JAVA),
                            driver_sources={str(path.relative_to(ROOT)): bench.digest(path) for path in driver_sources},
                            library_sources_sha256=hashlib.sha256(b''.join(
                                str(path.relative_to(ROOT)).encode() + b'\0' + path.read_bytes() for path in sources)).hexdigest(),
                            java_version=subprocess.run([str(runtime / 'java'), '-version'], check=True,
                                capture_output=True, text=True).stderr.strip(), stderr=process.stderr)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('compare', 'run'):
        p = sub.add_parser(command)
        for name in ('capture-manifest', 'port', 'report'):
            p.add_argument('--' + name, type=Path, required=True)
        if command == 'run':
            p.add_argument('--build', type=Path, required=True)
    args = parser.parse_args()
    try:
        report = (replay(args.capture_manifest, args.port, args.build) if args.command == 'run'
                  else compare(args.capture_manifest, args.port))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        report = dict(schema='denza-stock-sender-phase2-comparison-v1', verdict='INCOMPLETE',
                      equivalenceEstablished=False, capture_gaps=[], driver_gaps=[dict(replay_failed=str(exc))])
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(verdict=report['verdict'], equivalenceEstablished=report['equivalenceEstablished'],
                         report=str(args.report.resolve()))))
    return 0 if report['verdict'] == 'EQUAL' else 2


if __name__ == '__main__':
    raise SystemExit(main())
