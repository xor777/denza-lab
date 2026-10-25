"""Audit explicitly selected raw callback fixtures, without claiming timer races.

A delivered step proves its original public callbacks returned. Only the actual
phase2 getter/timer/effect ledger establishes sender branches and scheduling.
"""
from __future__ import annotations

import hashlib
import base64
import json
from pathlib import Path
import re

PREFIX = 'native_callback_fixture_'
KINDS = {PREFIX + name for name in ('plan', 'step', 'error', 'attached', 'end', 'input', 'wait', 'stop_gate', 'lane_attempt')}
SCHEMA = 'denza-stock-sender-callback-fixture-v1'
CASES = {'rare-active', 'arrival-idle', 'reset-offcourse', 'lane-query', 'city-odd', 'route-change'}


def canonical(contract: dict) -> str:
    return json.dumps(contract, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def planned_steps(source: str, selected: str) -> list[dict]:
    """Read literal fixture instructions from the immutable selected source branch."""
    if selected not in CASES:
        raise ValueError('unknown callback case')
    if selected == 'route-change':
        from . import route_change_audit
        return route_change_audit.plan(source)
    start = source.index("  if (kind === 'arrival-idle') {")
    markers = [(start, 'arrival-idle')]
    markers += [(m.start(), m.group(1)) for m in re.finditer(r"^  } else if \(kind === '([^']+)'\) \{", source, re.MULTILINE) if m.start() > start]
    split = source.index('  } else {', markers[-1][0])
    end = source.index("  note('native_callback_fixture_plan'", split)
    markers += [(split, 'rare-active'), (end, None)]
    branches = {name: source[at:markers[index + 1][0]] for index, (at, name) in enumerate(markers[:-1])}
    if selected not in branches:
        raise ValueError('selected profile absent from immutable fixture')
    branch = branches[selected]
    calls = re.findall(r"(?<![\w.])add\(\s*([0-9]+)\s*,\s*'([^']+)'", branch)
    if len(calls) != len(re.findall(r'(?<![\w.])add\(', branch)) or not calls:
        raise ValueError('callback fixture contains an unrepresented or absent step')
    steps = [dict(at_ms=int(at), name=name) for at, name in calls]
    if len({s['name'] for s in steps}) != len(steps) or [s['at_ms'] for s in steps] != sorted(s['at_ms'] for s in steps):
        raise ValueError('fixture step names must be unique and planned order explicit')
    return steps


def audit(raw_events: list[dict], contract: dict, *, fixture_source: str | None = None) -> dict:
    """Compare immutable instructions with actual complete, ordered fixture receipts."""
    events = [r for r in raw_events if str(r.get('kind', '')).startswith(PREFIX)]
    gaps = []
    report = dict(case=contract.get('case') if isinstance(contract, dict) else None,
                  gaps=gaps, fixture_receipts=events, expected_steps=[],
                  fixtureInputsDelivered=False, raceEstablished=False,
                  timing_policy='Plan at_ms values are scheduling instructions only; actual phase2 ledger proves timing and branches')
    try:
        if not isinstance(contract, dict) or contract.get('schema') != SCHEMA or contract.get('case') not in CASES:
            raise ValueError('known immutable callback fixture contract required')
        if not isinstance(fixture_source, str) or hashlib.sha256(fixture_source.encode()).hexdigest() != contract.get('fixture_sha256'):
            raise ValueError('exact immutable fixture source hash required')
        steps = planned_steps(fixture_source, contract['case'])
        report['expected_steps'] = steps
        if any(r['kind'] not in KINDS for r in events):
            gaps.append(dict(unknown_callback_fixture_receipt=True))
        if any(r.get('case') != contract['case'] for r in events):
            gaps.append(dict(callback_fixture_selected_case_differs=True))
        errors = [r for r in events if r['kind'] == PREFIX+'error']
        gaps += [dict(callback_fixture_error=r) for r in errors]
        by_kind = {kind: [r for r in events if r['kind'] == kind] for kind in KINDS}
        for kind in ('plan', 'attached', 'end'):
            if len(by_kind[PREFIX+kind]) != 1:
                gaps.append(dict(callback_fixture_receipt_count=dict(kind=kind, count=len(by_kind[PREFIX+kind]))))
        plans, attached, ends = (by_kind[PREFIX+k] for k in ('plan', 'attached', 'end'))
        delivered = by_kind[PREFIX+'step']
        if plans:
            plan = plans[0]
            if (plan.get('schema') != 'denza-native-callback-case-v1' or plan.get('steps') != steps
                    or plan.get('location') != contract.get('location')):
                gaps.append(dict(callback_fixture_plan_differs_from_immutable_instructions=True))
        if [r.get('name') for r in delivered] != [s['name'] for s in steps] or any(r.get('status') != 'delivered' for r in delivered):
            gaps.append(dict(callback_fixture_steps_missing_extra_failed_or_out_of_order=True))
        if ends and ends[0].get('status') != 'scheduled-inputs-complete':
            gaps.append(dict(callback_fixture_not_completed=ends[0]))
        if contract['case'] != 'route-change' and attached and (not isinstance(attached[0].get('original_path_id'), str) or not attached[0]['original_path_id']):
            gaps.append(dict(callback_fixture_original_path_identity_missing=True))
        if contract['case'] == 'arrival-idle' and plans and attached and plans[0].get('path_id') != attached[0].get('original_path_id'):
            gaps.append(dict(callback_fixture_idle_path_identity_differs=True))
        if len(plans) == len(attached) == len(ends) == 1:
            expected_order = [plans[0], attached[0]] + delivered + [ends[0]]
            lines = [r.get('source_line') for r in expected_order]
            if any(type(line) is not int for line in lines) or lines != sorted(set(lines)):
                gaps.append(dict(callback_fixture_receipt_order_or_provenance_invalid=True))
            boundaries = [r for r in raw_events if r.get('schema') == 'denza-stock-sender-phase2-v1' and r.get('kind') == 'capture_end']
            if (len(boundaries) != 1 or type(boundaries[0].get('source_line')) is not int
                    or type(ends[0].get('source_line')) is not int or ends[0]['source_line'] >= boundaries[0]['source_line']):
                gaps.append(dict(callback_fixture_completion_not_proved_before_capture_end=True))
        if contract['case'] == 'route-change':
            if plans and plans[0].get('route_change_policy') != contract.get('route_change_policy'):
                gaps.append(dict(callback_route_change_plan_policy_differs=True))
            from . import route_change_audit
            gaps += route_change_audit.audit(raw_events, delivered, contract)
            gaps += audit_route_waits(raw_events, by_kind[PREFIX+'wait'], attached, ends)
        elif by_kind[PREFIX+'input'] or by_kind[PREFIX+'wait'] or (by_kind[PREFIX+'stop_gate'] and contract['case'] != 'lane-query'):
            gaps.append(dict(unexpected_callback_fixture_input_receipt=True))
        if by_kind[PREFIX+'lane_attempt'] and contract['case'] != 'lane-query':
            gaps.append(dict(unexpected_lane_attempt_receipt=True))
        if contract['case'] == 'city-odd':
            gaps += audit_city_bindings(raw_events, delivered, contract)
        if contract['case'] == 'arrival-idle':
            gaps += audit_arrival_processing(raw_events, delivered, plans[0].get('path_id') if plans else None)
        if contract['case'] == 'lane-query':
            bindings, lane_gaps = audit_lane_bindings(raw_events, delivered)
            report['observed_lane_bindings'] = bindings
            gaps += lane_gaps
            if by_kind[PREFIX+'stop_gate'] and contract.get('guidance_stop_policy') is None:
                gaps.append(dict(callback_lane_guidance_stop_policy_missing=True))
            if contract.get('guidance_stop_policy') is not None:
                try:
                    if lane_gaps: raise ValueError('actual selected F processing is required before SDK stop')
                    from . import route_change_audit
                    phase2 = [r for r in raw_events if r.get('schema') == 'denza-stock-sender-phase2-v1']
                    calls = {r['call_id']:r for r in phase2 if r.get('kind')=='call_enter'}
                    exits = {r['call_id']:r for r in phase2 if r.get('kind')=='call_exit'}
                    selected = [b['processing'] for b in bindings if 'f_call_id' in b['processing']]
                    if not selected: raise ValueError('completed selected original F required')
                    final = exits[max(selected,key=lambda b:b['f_exit_seq'])['f_call_id']]
                    route_change_audit.audit_guidance_stop(raw_events,delivered,contract,calls,exits,final,
                        case='lane-query',policy='after_lane_processing_and_gnss_inputs_v1',
                        reason='lane-processing-and-gnss-inputs-complete')
                except (ValueError,KeyError,TypeError,IndexError) as exc:
                    gaps.append(dict(callback_lane_guidance_stop_invalid=str(exc)))
    except (ValueError, KeyError, TypeError) as exc:
        gaps.append(dict(callback_fixture_contract_invalid=str(exc)))
    report['fixtureInputsDelivered'] = not gaps
    return report


def audit_route_waits(raw, waits, attached, ends):
    """Bound optional wait metadata; it cannot fulfill any processing obligation."""
    try:
        if len(waits) > 12: raise ValueError('route wait diagnostics exceed bounded total')
        if any(type(r.get('wait_seq')) is not int for r in waits): raise ValueError('integer wait sequence required')
        ordered = sorted(waits, key=lambda r: r['wait_seq'])
        if [r['wait_seq'] for r in ordered] != list(range(1,len(waits)+1)):
            raise ValueError('unique contiguous route wait sequence required')
        from .route_change_audit import NAMES
        native = {r.get('seq'):r for r in raw if r.get('schema')=='denza-stock-sender-phase2-v1'}
        previous = None
        for row in ordered:
            if (row.get('stage') not in NAMES or not isinstance(row.get('reason'),str) or not row['reason']
                    or type(row.get('at_ms')) is not int or not 0 <= row['at_ms'] <= (1 << 53)-1
                    or previous is not None and row['at_ms']-previous < 5000):
                raise ValueError('explicit bounded route wait stage/reason/time required')
            previous = row['at_ms']
            if (len(attached)!=1 or len(ends)!=1 or type(row.get('source_line')) is not int
                    or not attached[0]['source_line'] < row['source_line'] < ends[0]['source_line']):
                raise ValueError('route wait metadata must remain inside actual attached fixture lifetime')
            for key in ('drive_ref','source_path_ref','recorded_navi_ref','provider_navi_ref'):
                ref=row.get(key)
                if ref is not None and (not isinstance(ref,dict) or ref.get('type')!='object'
                    or not isinstance(ref.get('id'),str) or not ref['id'] or not isinstance(ref.get('class'),str)):
                    raise ValueError('route wait optional references must retain typed original identity')
            evidence=row.get('selected_input_evidence')
            if evidence is not None:
                source=native.get(evidence.get('source_seq'))
                if (source is None or source.get('kind') not in ('call_enter','call_exit')
                        or source.get('call_id')!=evidence.get('call_id')
                        or not source.get('method','').startswith('k.h.e0.c.g.l(')):
                    raise ValueError('route wait selected input must identify an actual original progress receipt')
    except (ValueError,KeyError,TypeError) as exc:
        return [dict(callback_route_wait_diagnostic_invalid=str(exc))]
    return []


def audit_city_bindings(raw_events: list[dict], delivered: list[dict], contract: dict) -> list[dict]:
    """City fixtures control the cache list; synthetic zero is never a road fact."""
    try:
        policy = 'actual-current-urid-synthetic-zero-clear-v1'
        if contract.get('city_policy') != policy or contract.get('city_exclude_ids') != [0]:
            raise ValueError('exact declared city policy and synthetic control list required')
        native = [r for r in raw_events if r.get('schema') == 'denza-stock-sender-phase2-v1']
        used = set()
        for index, step in enumerate(delivered):
            data = step['city_input']
            stage = ('include', 'exclude', 'clear')[index]
            city = data['current_urid']
            expected = [city] if stage == 'include' else [0] if stage == 'exclude' else []
            origin = {'include': 'actual_current_link_urid', 'exclude': 'declared_synthetic_control', 'clear': 'explicit_empty_list'}[stage]
            if (type(city) is not int or city == 0 or data.get('policy') != policy or data.get('stage') != stage
                    or data.get('city_ids') != expected or data.get('origin') != origin
                    or any(type(data.get(k)) is not int or data[k] < 0 for k in ('segment_idx', 'link_idx'))):
                raise ValueError('city input policy/stage/origin/native indices differ')
            for key in ('path_ref', 'navi_ref', 'segment_ref', 'link_ref', 'input_ref'):
                if not isinstance(data.get(key), dict) or data[key].get('type') != 'object' or not data[key].get('id'):
                    raise ValueError('actual city fixture native/input identities missing')
            candidates = [r for r in native if r.get('kind') == 'call_enter'
                          and r.get('method', '').startswith('k.h.e0.d.f.m.a(')
                          and r.get('args') and r['args'][0].get('id') == data['input_ref']['id']]
            if len(candidates) != 1:
                raise ValueError('unique actual original city-list callback required')
            call = candidates[0]
            if call['call_id'] in used or call.get('role') != 'external_input':
                raise ValueError('city stages must use distinct original external callback receipts')
            used.add(call['call_id'])
            items = call['args'][0].get('items')
            if (not isinstance(items, list) or len(items) != len(expected)
                    or any(item.get('type') != 'i32' or type(item.get('value')) is not int
                           or item['value'] != value or item.get('boxed_class', 'java.lang.Integer') != 'java.lang.Integer'
                           for item, value in zip(items, expected))):
                raise ValueError('original city-list callback values differ from declared input')
            exits = [r for r in native if r.get('kind') == 'call_exit' and r.get('call_id') == call['call_id']]
            if len(exits) != 1 or exits[0].get('error') is not None:
                raise ValueError('city-list callback did not return successfully')
            pulses = [r for r in native if r.get('kind') == 'call_enter' and r.get('method', '').startswith('k.h.e0.c.g.l(')
                      and len(r.get('args', [])) == 2 and r['args'][0].get('id') == data['navi_ref']['id']
                      and r['args'][1].get('id') == data['path_ref']['id'] and exits[0]['seq'] < r['seq']
                      and r.get('source_line', 0) < step['source_line']]
            if not pulses:
                raise ValueError('actual original ODD progress callback after city update missing')
        if len(delivered) != 3:
            raise ValueError('all three city control stages required')
    except (ValueError, TypeError, KeyError, IndexError) as exc:
        return [dict(callback_city_input_binding_invalid=str(exc))]
    return []


def declared_audit(raw_events: list[dict], metadata: dict, artifacts: list[dict], agent: Path | None) -> dict | None:
    """Revalidate opted-in fixture/contract against the actual immutable loaded agent."""
    contract = metadata.get('callback_contract')
    loaded = agent.read_bytes() if agent is not None else b''
    selected = contract is not None or b'const P2_CALLBACK_CASE=' in loaded or any(
        a.get('role') in ('callback_fixture', 'callback_contract') for a in artifacts) or any(
        str(r.get('kind', '')).startswith(PREFIX) for r in raw_events)
    if not selected:
        return None
    fixture_source, provenance_gaps, verified = None, [], []
    try:
        if not isinstance(contract, dict):
            raise ValueError('declared callback metadata cannot be omitted')
        if contract.get('case') == 'route-change' or (contract.get('case') == 'lane-query' and contract.get('guidance_stop_policy') is not None):
            cases=re.findall(rb'^const REROUTE_CASE=(.*);$',loaded,re.MULTILINE)
            if len(cases)!=1 or len(json.loads(cases[0])['samples'])!=contract.get('guidance_sample_count'):
                raise ValueError('immutable route guidance count differs from actual loaded source case')
        for role in ('callback_fixture', 'callback_contract'):
            matches = [a for a in artifacts if a.get('role') == role]
            if len(matches) != 1:
                raise ValueError('exactly one '+role+' artifact required')
            artifact = matches[0]
            path = Path(artifact['path'])
            if not path.is_absolute():
                raise ValueError('callback artifact path must be absolute')
            content = path.read_bytes()
            if hashlib.sha256(content).hexdigest() != artifact.get('sha256'):
                raise ValueError(role+' artifact hash differs')
            if role == 'callback_fixture':
                if hashlib.sha256(content).hexdigest() != contract.get('fixture_sha256') or content not in loaded:
                    raise ValueError('actual loaded fixture bytes differ from selected contract')
                fixture_source = content.decode('utf-8')
            elif json.loads(content) != contract:
                raise ValueError('callback contract artifact table differs')
            verified.append(artifact)
        if loaded.count(('const P2_CALLBACK_CASE='+canonical(contract)+';').encode()) != 1:
            raise ValueError('selected callback contract declaration missing/duplicated in immutable agent')
    except (ValueError, KeyError, TypeError, OSError) as exc:
        provenance_gaps.append(dict(callback_fixture_provenance_invalid=str(exc)))
    result = audit(raw_events, contract, fixture_source=fixture_source)
    result['gaps'] += provenance_gaps
    result['verified_artifacts'] = verified if not provenance_gaps else []
    result['fixtureInputsDelivered'] = not result['gaps']
    return result


def _processing_index(raw_events):
    rows = sorted((r for r in raw_events if r.get('schema') == 'denza-stock-sender-phase2-v1'), key=lambda r: r.get('seq', -1))
    calls = {r['call_id']: r for r in rows if r.get('kind') == 'call_enter' and 'call_id' in r}
    ends = {r['call_id']: r for r in rows if r.get('kind') == 'call_exit' and 'call_id' in r}
    timers = {r['call_id']: r for r in rows if r.get('kind') == 'timer_execute_enter' and 'call_id' in r}
    posts = {r['post_id']: r for r in rows if r.get('kind') == 'timer_post_enter' and 'post_id' in r}
    boundaries = [r for r in rows if r.get('kind') == 'capture_end']
    if len(boundaries) != 1 or type(boundaries[0].get('seq')) is not int:
        raise ValueError('unique actual terminal sequence required for processing proof')
    cutoff = boundaries[0]['seq']
    def end(entry):
        result = ends.get(entry.get('call_id'))
        if (result is None or result.get('error', 'missing') is not None or result['seq'] <= entry['seq'] or result['seq'] >= cutoff):
            raise ValueError('actual successful matching processing exit required')
        return result
    def descendant(row, owner):
        cid, seen = row.get('call_id'), set()
        while cid is not None and cid not in seen:
            if cid == owner: return True
            seen.add(cid); cid = calls.get(cid, {}).get('parent_call_id')
        return False
    def dispatched(entry, klass, callback=None):
        cid, seen = entry.get('parent_call_id'), set()
        while cid in calls and cid not in seen:
            seen.add(cid); cid = calls[cid].get('parent_call_id')
        timer = timers.get(cid)
        if (timer is None or timer.get('queue_id') != 'MMT'
                or timer.get('runnable', {}).get('class') != klass): return False
        post = posts.get(timer.get('post_id'))
        return post is not None and (callback is None or post.get('call_id') == callback)
    return rows, calls, end, descendant, dispatched


def audit_arrival_processing(raw_events, delivered, path_id):
    """onNaviArrive queues R; a pending b0 runnable is not arrival processing."""
    try:
        rows, calls, end, _, dispatched = _processing_index(raw_events)
        step = next(s for s in delivered if s.get('name') == 'arrival-counter')
        prior = next(s for s in delivered if s.get('name') == 'idle-precondition-and-status-restart')
        callbacks = [r for r in calls.values() if r.get('method') == 'k.h.a0.c.c.a.e0.onNaviArrive(long,int)'
                     and r.get('args') == [{'type': 'i64', 'decimal': path_id}, {'type': 'i32', 'value': 0}]
                     and prior['source_line'] < r.get('source_line', -1) < step['source_line']]
        if len(callbacks) != 1: raise ValueError('unique actual fixture arrival callback required')
        callback = callbacks[0]; end(callback)
        actual = [r for r in calls.values() if r.get('method') == 'k.h.a0.c.c.a.e0.R()'
                  and r.get('receiver') == callback.get('receiver') and r['seq'] > callback['seq']
                  and dispatched(r, 'k.h.a0.c.c.a.b0', callback['call_id'])]
        if len(actual) != 1: raise ValueError('actual queued original R entry and exit required; pending work is insufficient')
        end(actual[0])
    except (ValueError, KeyError, TypeError, StopIteration) as exc:
        return [dict(callback_arrival_processing_missing=str(exc))]
    return []


def _lane_json(bean):
    if bean.get('class') != 'com.byd.map.signal.someip.pilot.bean.QueryAppointLanesInfoBean':
        raise ValueError('actual computed original lane bean required')
    f = bean['fields']; result = {}
    for key in ('linkIndex', 'segmentIndex'):
        if f[key].get('type') != 'i32' or type(f[key].get('value')) is not int: raise ValueError('lane bean integer index required')
        result[key] = f[key]['value']
    for key in ('pathId', 'timestamp'):
        if f[key].get('type') != 'i64' or not re.fullmatch(r'-?(?:0|[1-9][0-9]*)', f[key].get('decimal', '')):
            raise ValueError('lane bean exact long required')
        result[key] = int(f[key]['decimal'])
    for key in ('backLane', 'frontLane', 'extendLane', 'recommendLane', 'backLaneType', 'frontLaneType'):
        if f[key].get('type') != 'list': raise ValueError('actual lane arrays required')
        result[key] = []
        for item in f[key]['items']:
            if item.get('type') == 'null':
                result[key].append(None)
            elif item.get('type') == 'i32' and type(item.get('value')) is int:
                result[key].append(item['value'])
            else:
                raise ValueError('lane array original Integer/null required')
    return result


def _lane_payload(row):
    data = base64.b64decode(row['payload_b64'], validate=True)
    if not data or data[0] != 10: raise ValueError('original lane notify field1 STRING required')
    size, shift, offset = 0, 0, 1
    while offset < len(data) and shift < 64:
        byte = data[offset]; offset += 1; size |= (byte & 127) << shift
        if byte < 128: break
        shift += 7
    else: raise ValueError('truncated lane notify length')
    if offset + size != len(data): raise ValueError('lane notify field length/trailing bytes differ')
    result = json.loads(data[offset:].decode('utf-8'))
    if not isinstance(result, list) or not result: raise ValueError('actual nonempty lane JSON array required')
    return result


def audit_lane_processing(raw_events, callback, request, path_id, segment, link, *, require_flush=True):
    """Source Z caches response beans; source F alone selects and sends f0.i."""
    rows, calls, end, descendant, dispatched = _processing_index(raw_events)
    end(callback)
    responses = [r for r in calls.values() if r.get('method') == 'k.h.a0.c.c.a.e0.Z(java.util.ArrayList,long)'
                 and r.get('receiver') == callback.get('receiver') and len(r.get('args', [])) == 2
                 and r['args'][0].get('id') == callback['args'][1].get('id')
                 and r['args'][1] == {'type': 'i64', 'decimal': request}
                 and dispatched(r, 'k.h.a0.c.c.a.k', callback['call_id'])]
    if len(responses) != 1: raise ValueError('actual queued controlled Z processing entry and successful exit required')
    z_end = end(responses[0])
    if not require_flush:
        return dict(z_seq=responses[0]['seq'], z_exit_seq=z_end['seq'], z_call_id=responses[0]['call_id'])
    for flush in calls.values():
        if (flush.get('method') != 'k.h.a0.c.c.a.e0.F(int,int,java.util.Map)'
                or flush.get('receiver') != callback.get('receiver') or flush['seq'] <= z_end['seq']
                or not dispatched(flush, 'k.h.a0.c.c.a.l')): continue
        end(flush)
        entries = flush['args'][2].get('fields', {}).get('entries', {}).get('items', [])
        if not any(pair.get('items') == [{'type': 'i64', 'decimal': request}, {'type': 'i64', 'decimal': path_id}] for pair in entries):
            continue
        for wire in calls.values():
            if (wire.get('method') != 'k.h.a0.c.c.a.f0.i(java.util.List)' or not descendant(wire, flush['call_id'])
                    or not wire.get('args') or not wire['args'][0].get('items')): continue
            wire_end = end(wire)
            beans = wire['args'][0]['items']; serialized = [_lane_json(bean) for bean in beans]
            if not any(bean.get('fields', {}).get('requestId') == {'type': 'i64', 'decimal': request}
                       and item['pathId'] == int(path_id) and item['segmentIndex'] == segment and item['linkIndex'] == link
                       for bean, item in zip(beans, serialized)): continue
            method = 'com.autosdk.bussiness.pilot.someip.SomeIpServer.sendSomeIpEvent(ts.car.someip.sdk.common.SomeIpData)'
            for attempt in rows:
                if (attempt.get('kind') != 'getter_enter' or attempt.get('method') != method
                        or not wire['seq'] < attempt['seq'] < wire_end['seq']
                        or not descendant(attempt, wire['call_id'])): continue
                completions = [r for r in rows if r.get('kind') == 'getter'
                               and r.get('read_id') == attempt.get('read_id') and r.get('method') == method
                               and r.get('call_id') == attempt.get('call_id')]
                if len(completions) != 1: raise ValueError('actual SDK send completion required')
                completion = completions[0]
                if (completion.get('error', 'missing') is not None
                        or not attempt['seq'] < completion['seq'] < wire_end['seq']
                        or completion.get('result') not in ({'type': 'i32', 'value': 0}, {'type': 'i32', 'value': -1})):
                    raise ValueError('actual SDK send return/error required')
                for span in (attempt, completion):
                    packet = span['args'][0]
                    if packet.get('type') != 'struct' or packet.get('class') != 'ts.car.someip.sdk.common.SomeIpData':
                        raise ValueError('actual SDK SomeIpData required')
                    fields = packet['fields']
                    if (fields.get('mTopic') != {'type': 'i64', 'decimal': '1268847189590030'}
                            or fields.get('mPayload', {}).get('type') != 'bytes'
                            or fields.get('mTimeStamp', {}).get('type') != 'i64'
                            or not re.fullmatch(r'-?(?:0|[1-9][0-9]*)', fields['mTimeStamp'].get('decimal', ''))
                            or _lane_payload({'payload_b64': fields['mPayload']['base64']}) != serialized):
                        raise ValueError('computed lane SDK packet must match original selected beans')
                if completion['args'] != attempt['args']:
                    raise ValueError('SDK lane send arguments changed across actual call')
                payload = attempt['args'][0]['fields']['mPayload']['base64']
                fired = [r for r in rows if r.get('kind') == 'output' and r.get('operation') == 'fire'
                         and attempt['seq'] < r['seq'] < completion['seq'] and descendant(r, wire['call_id'])]
                if any(r.get('topic') != '1268847189590030' or r.get('payload_b64') != payload for r in fired):
                    raise ValueError('actual SDK descendant fire differs from attempted lane packet')
                if completion['result']['value'] == 0 and len(fired) != 1:
                    raise ValueError('SDK success requires its actual matching fire')
                if len(fired) > 1: raise ValueError('ambiguous SDK descendant lane fires')
                return dict(z_seq=responses[0]['seq'], z_exit_seq=z_end['seq'], z_call_id=responses[0]['call_id'],
                            f_seq=flush['seq'], f_exit_seq=end(flush)['seq'], f_call_id=flush['call_id'],
                            lane_send_seq=wire['seq'], lane_exit_seq=wire_end['seq'], lane_call_id=wire['call_id'],
                            sdk_attempt_seq=attempt['seq'], sdk_completion_seq=completion['seq'],
                            sdk_return=completion['result']['value'],
                            transport_status=('fired' if completion['result']['value'] == 0 else 'fired_with_failed_return')
                            if fired else 'attempted_not_fired',
                            output_seq=fired[0]['seq'] if fired else None)
    raise ValueError('actual F completion with selected controlled-request beans and nonempty f0.i SDK attempt required')


def audit_lane_bindings(raw_events: list[dict], delivered: list[dict]) -> tuple[list[dict], list[dict]]:
    """Bind fixture request IDs to actual native reads and public SDK input args.

    The path ID comes from the original J0 runnable's subsequent native getter,
    never from a provider getter or from a sender packet. Actual controlled Z,
    matching F and a nonempty f0.i SDK send attempt are mandatory below.
    SDK failure may prevent a Binder fire; the report keeps that distinction.
    """
    bindings, gaps = [], []
    response = [r for r in delivered if r.get('name') == 'deliver-observed-native-lane-responses']
    trigger = [r for r in delivered if r.get('name') == 'reset-and-pulse-original-lane-query']
    try:
        if len(response) != 1 or len(trigger) != 1:
            raise ValueError('completed query trigger and response step required')
        records = response[0].get('observed_lane_requests')
        aggregate = 'response_inputs' in response[0]
        if not isinstance(records, list) or not 1 <= len(records) <= 64 or (not aggregate and len(records) != 1):
            raise ValueError('bounded actual completed native query metadata required')
        if len({r.get('read_id') for r in records}) != len(records):
            raise ValueError('duplicate attempted native query identity')
        attempts = [r for r in raw_events if r.get('kind') == PREFIX+'lane_attempt']
        selected = response[0].get('selected_processing') if aggregate else None
        if aggregate:
            inputs = response[0]['response_inputs']
            if not isinstance(inputs, list) or len(inputs) != len(records) or len(attempts) != len(records):
                raise ValueError('every bounded lane response requires its actual attempt receipt')
            if [a.get('attempt_seq') for a in attempts] != list(range(1,len(records)+1)):
                raise ValueError('lane attempt receipt order/uniqueness differs')
            if not isinstance(selected, dict) or not isinstance(selected.get('request_ids'), list) or not selected['request_ids']:
                raise ValueError('actual selected original lane processing evidence required')
            if len(set(selected['request_ids'])) != len(selected['request_ids']) or not set(selected['request_ids']) <= {r['requestId'] for r in records}:
                raise ValueError('selected lane requests must originate from actual controlled attempts')
        elif attempts:
            raise ValueError('unrepresented lane attempt receipts')
        original = [r for r in raw_events if r.get('schema') == 'denza-stock-sender-phase2-v1']
        for index, record in enumerate(records):
            input_ref = inputs[index].get('response_input_ref') if aggregate else response[0].get('response_input_ref')
            if (not isinstance(input_ref, dict) or input_ref.get('type') != 'object'
                    or not isinstance(input_ref.get('id'), str) or not input_ref['id']
                    or not isinstance(input_ref.get('class'), str)):
                raise ValueError('actual controlled response input list identity required')
            if aggregate:
                attempt=attempts[index]
                if (inputs[index].get('requestId') != record.get('requestId') or inputs[index].get('read_id') != record.get('read_id')
                        or attempt.get('case') != 'lane-query' or attempt.get('observed_lane_request') != record
                        or attempt.get('response_input_ref') != input_ref
                        or attempt.get('lane_variants') != ['backExtenLane','extensionLane']
                        or not trigger[0]['source_line'] < attempt['source_line'] < response[0]['source_line']):
                    raise ValueError('lane attempt source query/input/variant provenance differs')
            if (not isinstance(record.get('read_id'), str) or not record['read_id']
                    or type(record.get('source_seq')) is not int
                    or any(type(record.get(key)) is not int for key in ('segmentIdx', 'linkIdx', 'num'))):
                raise ValueError('complete actual query metadata identity and integer indices required')
            request = record.get('requestId')
            if not isinstance(request, str) or not re.fullmatch('[1-9][0-9]*', request) or int(request) > 2147483647:
                raise ValueError('original positive int request ID required; zero is invalid')
            matches = [r for r in original if r.get('kind') == 'getter'
                       and r.get('method') == 'com.autonavi.gbl.guide.GuideService.queryAppointLanesInfo(com.autonavi.gbl.guide.model.QueryLanesInfo)'
                       and r.get('read_id') == record.get('read_id') and r.get('seq') == record.get('source_seq')]
            if len(matches) != 1:
                raise ValueError('selected query does not match exact original completion metadata')
            query = matches[0]
            if query.get('error') is not None or query.get('result') != {'type': 'i32', 'value': int(request)}:
                raise ValueError('selected query ID differs from actual original return')
            fields = query['args'][0]['fields']
            for key in ('segmentIdx', 'linkIdx', 'num'):
                if fields.get(key) != {'type': 'i32', 'value': record.get(key)}:
                    raise ValueError('selected query args differ from actual original input')
            if record.get('num') != 6:
                raise ValueError('original J0 lane query must request six')
            dispatch = [r for r in original if r.get('kind') == 'timer_execute_enter' and r.get('call_id') == query.get('call_id')
                        and r.get('queue_id') == 'MAIN' and r.get('runnable', {}).get('class') == 'k.h.a0.c.c.a.e0$b'
                        and r['seq'] < query['seq']]
            if len(dispatch) != 1:
                raise ValueError('query must belong to actual original MAIN J0 dispatch')
            callbacks = [r for r in original if r.get('kind') == 'call_enter'
                         and r.get('method') == 'k.h.a0.c.c.a.e0.onQueryAppointLanesInfo(long,java.util.ArrayList)'
                         and r['seq'] > query['seq'] and query['source_line'] > trigger[0]['source_line']
                         and r['source_line'] < response[0]['source_line']
                         and r.get('args') and len(r['args']) == 2 and r['args'][0] == {'type': 'i64', 'decimal': request}
                         and r['args'][1].get('id') == input_ref['id'] and r['args'][1].get('class') == input_ref['class']]
            if len(callbacks) != 1:
                raise ValueError('exact original public response receipt with observed request ID required')
            callback = callbacks[0]
            paths = [r for r in original if r.get('kind') == 'getter'
                     and r.get('method') == 'com.autonavi.gbl.common.path.option.PathInfo.getPathID()'
                     and r.get('call_id') == query.get('call_id') and r.get('thread') == query.get('thread')
                     and query['seq'] < r['seq'] < callback['seq'] and r.get('error') is None]
            paths.sort(key=lambda row: row['seq'])
            if not paths or paths[0].get('result', {}).get('type') != 'i64':
                raise ValueError('original query-to-path map pairing lacks actual native path ID return')
            # Source J0 consumes this first getPathID immediately after each
            # query return, before issuing another query on the same MAIN task.
            intervening = [r for r in original if r.get('kind') == 'getter' and r.get('call_id') == query.get('call_id')
                           and r.get('method') == query['method'] and query['seq'] < r['seq'] < paths[0]['seq']]
            if intervening:
                raise ValueError('ambiguous original query-to-path pairing')
            lanes = callback['args'][1]['items']
            if len(lanes) != 2:
                raise ValueError('both declared SDK lane extension variants required')
            for lane_index, lane in enumerate(lanes):
                f = lane['fields']
                if lane.get('class') != 'com.autonavi.gbl.guide.model.LaneInfo':
                    raise ValueError('actual SDK LaneInfo input required')
                for key in ('segmentIdx', 'linkIdx'):
                    if f.get(key) != {'type': 'i32', 'value': record[key]}:
                        raise ValueError('SDK response lane index differs from observed original query')
                if not f['backExtenLane']['items'] or bool(f['extensionLane']['items']) != bool(lane_index):
                    raise ValueError('declared empty/nonempty SDK extension variants missing')
            if aggregate and callback['source_line'] >= attempts[index]['source_line']:
                raise ValueError('attempt receipt must follow actual controlled callback return')
            chosen = not aggregate or request in selected['request_ids']
            processing = audit_lane_processing(raw_events, callback, request, paths[0]['result']['decimal'], record['segmentIdx'], record['linkIdx'], require_flush=chosen)
            if aggregate and chosen:
                for key, seq_key, cid_key in (('F','f_exit_seq','f_call_id'),('wire','lane_exit_seq','lane_call_id')):
                    if (selected.get(key) != {'source_seq':processing[seq_key],'call_id':processing[cid_key],'error':None}
                            or next(r for r in original if r.get('seq') == processing[seq_key])['source_line'] >= response[0]['source_line']):
                        raise ValueError('selected lane processing differs from actual successful original exit')
            bindings.append(dict(query_read_id=query['read_id'], query_seq=query['seq'],
                                 request_id=request, path_read_id=paths[0]['read_id'], path_seq=paths[0]['seq'],
                                 path_id=paths[0]['result']['decimal'], callback_seq=callback['seq'], processing=processing))
        if aggregate and not any(selected.get('Z') == {'source_seq':b['processing']['z_exit_seq'],'call_id':b['processing']['z_call_id'],'error':None}
                                 for b in bindings if b['request_id'] in selected['request_ids']):
            raise ValueError('selected controlled Z successful exit is missing')
    except (ValueError, TypeError, KeyError, IndexError, UnicodeError) as exc:
        gaps.append(dict(callback_lane_query_binding_invalid=str(exc)))
    return bindings, gaps
