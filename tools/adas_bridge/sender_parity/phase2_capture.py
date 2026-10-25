"""Lossless original sender getter/clock/queue capture; no output-derived inputs.

The canonical ledger contains EVERY phase2 event in recorder sequence order.
External call entries are receipts; internal calls/getters/timers remain consumed
observations, never extra externally driven callbacks. Capture integrity alone
cannot establish sender parity.
"""
from __future__ import annotations

import argparse
import base64
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import re
import struct
from typing import Any

from . import bench, capture

SCHEMA = 'denza-stock-sender-phase2-v1'
LEDGER_SCHEMA = 'denza-stock-sender-phase2-ledger-v1'
REFERENCE_SCHEMA = 'denza-stock-sender-phase2-reference-v1'
APK_SHA256 = capture.APK_SHA256
# Source-owned mutable bean observations are assertions, never environment inputs.
STATE_METHODS = {
    'com.byd.map.signal.someip.pilot.bean.NaviInfoParam.' + name for name in
    ('getCurSegIdx', 'getCurLinkIdx', 'setNaviInfoParam', 'reSetNaviInfo')
} | {'com.byd.map.signal.someip.pilot.bean.FontRoundBean.' + name
     for name in ('getFont3kmIdx', 'setParam')}

KINDS = {'capture_end', 'ready', 'hook', 'recorder_error', 'call_enter', 'call_exit', 'getter_enter', 'clock_enter', 'getter', 'clock',
         'timer_post_enter', 'timer_post_result', 'timer_cancel',
         'timer_execute_enter', 'timer_execute_exit', 'output', 'environment_operation'}
ENVIRONMENT_METHODS = {
    'client_start': 'com.autosdk.bussiness.pilot.someip.SomeIpClient.startClientOrSubscribe(java.util.Map)',
    'client_stop': 'com.autosdk.bussiness.pilot.someip.SomeIpClient.stopClient(java.util.Map)',
    'client_unsubscribe': 'com.autosdk.bussiness.pilot.someip.SomeIpClient.unSubscribe(java.util.Map)',
    'route_signal_reset': 'k.h.e0.c.j.f0.l()',
    'avp_support': 'k.e.c.c0.k(int)',
}
QUEUES = {'MMT', 'MAIN', 'SOMEIP', 'OTHER'}
DECIMAL = re.compile(r'-?(?:0|[1-9][0-9]*)\Z')


def decimal(value: Any, *, unsigned: bool = False) -> int:
    if not isinstance(value, str) or not DECIMAL.fullmatch(value):
        raise ValueError('exact decimal string required')
    number = int(value)
    if unsigned and number < 0:
        raise ValueError('nonnegative decimal required')
    if not -(1 << 63) <= number < (1 << 64):
        raise ValueError('decimal outside 64-bit range')
    return number


def typed_errors(value: Any, path: str = 'value') -> list[str]:
    """Validate exact wire types without normalizing or reconstructing a value."""
    errors = []
    try:
        if not isinstance(value, dict):
            raise ValueError('typed object required')
        kind = value.get('type')
        if 'boxed_class' in value and kind != 'i32':
            raise ValueError('boxed_class is supported only for exact i32 Java wrappers')
        if kind == 'null':
            return []
        if kind == 'i32':
            number = value.get('value')
            if type(number) is not int or not -(1 << 31) <= number < (1 << 31):
                raise ValueError('signed i32 integer required')
            if 'boxed_class' in value:
                widths = {'java.lang.Byte': 8, 'java.lang.Short': 16, 'java.lang.Integer': 32}
                width = widths.get(value['boxed_class'])
                if width is None or not -(1 << (width-1)) <= number < (1 << (width-1)):
                    raise ValueError('boxed i32 Java class/range differs')
        elif kind == 'i64':
            number = decimal(value.get('decimal'))
            if not -(1 << 63) <= number < (1 << 63):
                raise ValueError('signed i64 range required')
        elif kind in ('f32', 'f64'):
            bits = value.get('bits')
            width, code = (8, '>f') if kind == 'f32' else (16, '>d')
            if not isinstance(bits, str) or not re.fullmatch('[0-9a-fA-F]{' + str(width) + '}', bits):
                raise ValueError('exact IEEE bits required')
            decoded = struct.unpack(code, bytes.fromhex(bits))[0]
            companion = value.get('value')
            if math.isfinite(decoded):
                if isinstance(companion, bool) or not isinstance(companion, (int, float)) or companion != decoded:
                    raise ValueError('numeric companion differs from IEEE bits')
            elif companion is not None:
                # JSON.stringify emits null for JS NaN/Infinity. Bits retain the
                # complete original representation including NaN payload/sign.
                raise ValueError('nonfinite IEEE companion must be JSON null')
        elif kind == 'boolean':
            if type(value.get('value')) is not bool:
                raise ValueError('boolean required')
        elif kind == 'string':
            if not isinstance(value.get('value'), str):
                raise ValueError('string required')
        elif kind == 'bytes':
            if not isinstance(value.get('base64'), str):
                raise ValueError('base64 string required')
            base64.b64decode(value['base64'], validate=True)
        elif kind == 'object':
            if not isinstance(value.get('id'), str) or not value['id']:
                raise ValueError('object identity string required')
            if not isinstance(value.get('class'), str) or not value['class']:
                raise ValueError('original Java class required')
            pointer = value.get('native_pointer')
            if 'native_pointer' not in value:
                raise ValueError('explicit native_pointer (including null) required')
            if pointer is None and re.search(r'(?:^|\.)(?:PathInfo|SegmentInfo|LinkInfo)$', value['class']):
                raise ValueError('native wrapper pointer observation required')
            if pointer is not None:
                if isinstance(pointer, str) and re.fullmatch(r'0x[0-9a-fA-F]+', pointer):
                    if int(pointer, 16) >= 1 << 64:
                        raise ValueError('native pointer outside 64-bit range')
                else:
                    decimal(pointer)
        elif kind == 'struct':
            if not isinstance(value.get('class'), str) or not isinstance(value.get('fields'), dict):
                raise ValueError('typed struct class/fields required')
            if re.search(r'(?:^|\.)(?:PathInfo|SegmentInfo|LinkInfo)$', value['class']):
                raise ValueError('native path views must be identity references with actual getter transcript')
            for name, item in value['fields'].items():
                errors.extend(typed_errors(item, path + '.fields.' + name))
        elif kind == 'list':
            if not isinstance(value.get('class'), str) or not isinstance(value.get('items'), list):
                raise ValueError('typed list class/items required')
            for index, item in enumerate(value['items']):
                errors.extend(typed_errors(item, f'{path}.items[{index}]'))
        else:
            raise ValueError('unknown typed value: ' + str(kind))
    except (ValueError, TypeError, OverflowError) as exc:
        errors.append(path + ': ' + str(exc))
    return errors


def _identity(value: Any) -> str | None:
    if isinstance(value, dict) and value.get('type') == 'object':
        return value.get('id')
    return None


def _reference(value: Any, *, nullable: bool = True) -> None:
    errors = typed_errors(value)
    if errors:
        raise ValueError('; '.join(errors))
    if value.get('type') not in ({'object', 'null'} if nullable else {'object'}):
        raise ValueError('object identity reference required; primitive value is not token identity')


def _nullable_id(row: dict, name: str) -> None:
    if name not in row or (row[name] is not None and (not isinstance(row[name], str) or not row[name])):
        raise ValueError(name + ' must be an explicit string or null')


def environment_argument_objects(row: dict) -> dict[str, dict]:
    """Validate finite SDK API arguments; retain every Map enumeration/value."""
    operation = row.get('operation')
    if operation not in ENVIRONMENT_METHODS or row.get('method') != ENVIRONMENT_METHODS[operation]:
        raise ValueError('unknown SDK operation or full original method descriptor')
    if not isinstance(row.get('read_id'), str) or not row['read_id']:
        raise ValueError('SDK operation actual getter read_id required')
    args = row.get('args')
    if not isinstance(args, list) or len(args) != (0 if operation == 'route_signal_reset' else 1):
        raise ValueError('complete SDK operation typed argument array required')
    if not args:
        return {}
    root = args[0]
    if operation == 'avp_support':
        if root.get('type') != 'i32' or typed_errors(root):
            raise ValueError('AVP support requires exact original i32 argument')
        return {}
    errors = typed_errors(root)
    if errors:
        raise ValueError('; '.join(errors))
    if (root.get('type') != 'struct' or not isinstance(root.get('id'), str)
            or set(root.get('fields', {})) != {'entries'}):
        raise ValueError('actual SDK Map identity and full typed entries required')
    objects = {'args[0]': root}
    entries = root['fields']['entries']
    if entries.get('type') != 'list' or entries.get('class') != 'entries':
        raise ValueError('original SDK Map enumeration required')
    seen = set()
    for index, entry in enumerate(entries['items']):
        if entry.get('type') != 'list' or entry.get('class') != 'entry' or len(entry.get('items', [])) != 2:
            raise ValueError('SDK Map entry must be exact key/value tuple')
        key, array = entry['items']
        if key.get('type') != 'i64' or key.get('decimal') in seen:
            raise ValueError('SDK Map distinct exact Long service keys required')
        seen.add(key['decimal'])
        if array.get('type') == 'null':
            continue
        if (array.get('type') != 'list' or array.get('class') != '[Ljava.lang.Long;'
                or not isinstance(array.get('id'), str)):
            raise ValueError('SDK Map value must retain actual Long[] identity or null')
        if any(item.get('type') not in ('i64', 'null') for item in array['items']):
            raise ValueError('SDK event array must retain ordered Long/null values')
        objects[f'args[0].fields.entries.items[{index}].items[1]'] = array
    return objects


def audit_ledger(events: list[dict], required_hooks: list[str]) -> dict:
    """Validate transcript structure/causality; retain unknowns as explicit gaps."""
    gaps, hooks, required = [], {}, set(required_hooks)
    calls, stacks, timer_stacks = {}, defaultdict(list), defaultdict(list)
    posts, object_classes, pointer_observations = {}, {}, defaultdict(set)
    counts = Counter()
    insertions = []
    ready_rows = []
    end_rows = []
    reads, read_stacks, out_mutations, environment_operations = {}, defaultdict(list), [], {}
    input_mutations, retained_inputs, retained_lightbars = [], [], {}

    def value(v, name):
        errors = typed_errors(v, name)
        if errors:
            raise ValueError('; '.join(errors))
        def identities(item):
            if not isinstance(item, dict):
                return
            if item.get('type') in ('object', 'struct', 'list') and isinstance(item.get('id'), str):
                oid = item['id']
                if oid in object_classes and object_classes[oid] != item['class']:
                    raise ValueError('same object identity has different Java classes: ' + oid)
                object_classes[oid] = item['class']
                if item.get('type') == 'object':
                    pointer_observations[oid].add(item['native_pointer'])
            if item.get('type') == 'struct':
                for child in item['fields'].values():
                    identities(child)
            if item.get('type') == 'list':
                for child in item['items']:
                    identities(child)
        identities(v)

    def arguments(row):
        if not isinstance(row.get('args'), list):
            raise ValueError('complete typed args array required')
        for index, v in enumerate(row['args']):
            value(v, f'args[{index}]')

    def argument_exit(before, after):
        # A Java invocation cannot replace its caller's argument reference or
        # primitive value. It can mutate the contents of an existing object.
        value(after, 'args_after')
        for key in ('id', 'class', 'native_pointer'):
            if before.get(key) != after.get(key):
                raise ValueError('argument identity/class/native pointer changed at exit')
        if before.get('type') != after.get('type'):
            raise ValueError('argument wire type changed at exit')
        if before.get('type') not in ('struct', 'list', 'bytes') and before != after:
            raise ValueError('immutable argument value changed at exit')

    def method(row):
        if not isinstance(row.get('method'), str) or not row['method']:
            raise ValueError('original method/overload required')

    def queue(row):
        if row.get('queue_id') not in QUEUES:
            raise ValueError('queue_id required')
        if row['queue_id'] == 'OTHER':
            gaps.append(dict(seq=row['seq'], unmodelled_queue='OTHER'))

    for row in events:
        kind, seq = row.get('kind'), row.get('seq')
        counts[str(kind)] += 1
        try:
            if end_rows:
                gaps.append(dict(event_after_capture_end=row))
            if row.get('schema') != SCHEMA:
                raise ValueError('phase2 schema missing/mismatched')
            decimal(row.get('mono_ns'), unsigned=True)
            thread = row.get('thread')
            if not isinstance(thread, dict) or not isinstance(thread.get('id'), str) or not isinstance(thread.get('name'), str):
                raise ValueError('original thread identity/name required')
            tid = thread['id']
            # Extra diagnostic members remain lossless, but an unsupported wire
            # type anywhere (e.g. token_value) still makes acceptance incomplete.
            def every_typed(item, path):
                if isinstance(item, dict):
                    if 'type' in item:
                        value(item, path)
                    else:
                        for key, child in item.items():
                            every_typed(child, path + '.' + key)
                elif isinstance(item, list):
                    for index, child in enumerate(item):
                        every_typed(child, f'{path}[{index}]')
            every_typed(row, 'event')
            _nullable_id(row, 'call_id'); _nullable_id(row, 'parent_call_id')
            cid = row['call_id']
            if kind not in KINDS:
                raise ValueError('unknown phase2 kind: ' + str(kind))
            if kind == 'capture_end':
                end_rows.append(row)
                if any(stacks.values()) or any(read_stacks.values()) or any(timer_stacks.values()):
                    raise ValueError('capture_end is not an actually quiet boundary')
                if any(post['result'] is None for post in posts.values()):
                    raise ValueError('capture_end has in-flight enqueue results')
                pending = row.get('pending_posts')
                if not isinstance(pending, list):
                    raise ValueError('capture_end exact pending_posts snapshot required')
                envelope = {'schema', 'kind', 'seq', 'mono_ns', 'thread', 'call_id', 'parent_call_id', 'source_line'}
                expected = [{key: value for key, value in post['enter'].items() if key not in envelope}
                            for post in posts.values() if post['state'] == 'pending']
                if pending != expected:
                    raise ValueError('capture_end pending occurrence snapshot differs from observed queue ledger')
            elif kind == 'ready':
                if not isinstance(row.get('required'), list) or any(not isinstance(method, str) for method in row['required']):
                    raise ValueError('ready requires complete original hook inventory')
                required.update(row['required'])
                ready_rows.append(row)
            elif kind == 'recorder_error':
                gaps.append(dict(seq=seq, recorder_error=row))
            elif kind == 'hook':
                method(row)
                if type(row.get('required')) is not bool:
                    raise ValueError('hook required flag missing')
                if row['required']:
                    required.add(row['method'])
                hooks[row['method']] = row.get('status')
                if row.get('status') != 'installed':
                    raise ValueError('hook not installed: ' + row['method'])
            elif kind == 'call_enter':
                method(row); arguments(row)
                _reference(row.get('receiver'))
                value(row['receiver'], 'receiver')
                if row.get('role') == 'external_input' and not ready_rows:
                    raise ValueError('external callback before recorder ready')
                if row['method'].split('(')[0] in STATE_METHODS and row.get('role') != 'internal_processing':
                    raise ValueError('source-owned state observation cannot be an external input')
                if row.get('role') not in ('external_input', 'internal_processing'):
                    raise ValueError('call external/internal role required')
                if cid is None or cid in calls:
                    raise ValueError('call identity missing/duplicated')
                expected = stacks[tid][-1] if stacks[tid] else None
                if row['parent_call_id'] != expected:
                    raise ValueError('call parent is not active thread parent')
                calls[cid] = row
                stacks[tid].append(cid)
                if row['method'].startswith('k.h.a0.c.c.a.e0.B0(') and row['args'] and row['args'][0].get('type') != 'null':
                    retained_lightbars[_identity(row['receiver'])] = row['args'][0]
            elif kind == 'call_exit':
                if not stacks[tid] or stacks[tid][-1] != cid or calls[cid].get('kind') != 'call_enter':
                    raise ValueError('call exit does not match active thread call')
                if row['parent_call_id'] != calls[cid]['parent_call_id']:
                    raise ValueError('call exit parent differs')
                if 'error' not in row:
                    raise ValueError('explicit original error/null required')
                entered = calls[cid]
                after = row.get('args_after')
                if not isinstance(after, list) or len(after) != len(entered['args']):
                    raise ValueError('complete args_after mutation evidence required')
                for index, (before, actual) in enumerate(zip(entered['args'], after)):
                    argument_exit(before, actual)
                    if before != actual:
                        input_mutations.append(dict(seq=seq, call_id=cid, argument=index, before=before, after=actual))
                if 'retained_inputs_after' in row:
                    retained = row['retained_inputs_after']
                    if (not isinstance(retained, dict) or set(retained) != {'lightbar'}
                            or not entered['method'].startswith('k.h.a0.c.c.a.e0.z(')):
                        raise ValueError('unknown retained input mutation assertion')
                    actual = retained['lightbar']
                    value(actual, 'retained_inputs_after.lightbar')
                    if actual.get('type') != 'list' or not isinstance(actual.get('id'), str):
                        raise ValueError('retained lightbar actual list identity required')
                    original = retained_lightbars.get(_identity(entered['receiver']))
                    if original is None or original.get('id') != actual['id'] or original.get('class') != actual['class']:
                        raise ValueError('retained lightbar lacks matching original B0 input identity')
                    retained_inputs.append(dict(seq=seq, call_id=cid, lightbar=actual))
                elif (entered['method'].startswith('k.h.a0.c.c.a.e0.z(')
                      and _identity(entered['receiver']) in retained_lightbars):
                    raise ValueError('retained lightbar mutation evidence missing')
                value(row.get('result'), 'result')
                stacks[tid].pop()
                if row['error'] is not None:
                    gaps.append(dict(seq=seq, original_call_exception=row['error']))
            elif kind == 'environment_operation':
                environment_argument_objects(row)
                read_id = row['read_id']
                entered = reads.get(read_id)
                if (read_id in environment_operations or entered is None or entered['kind'] != 'getter_enter'
                        or not read_stacks[tid] or read_stacks[tid][-1] != read_id
                        or entered['method'] != row['method'] or entered.get('args') != row['args']
                        or entered['call_id'] != cid or entered['parent_call_id'] != row['parent_call_id']):
                    raise ValueError('SDK operation must match unique active original getter initiation/arguments')
                environment_operations[read_id] = row
            elif kind in ('getter_enter', 'clock_enter', 'getter', 'clock'):
                method(row)
                if cid is not None and (not stacks[tid] or cid not in stacks[tid]):
                    raise ValueError('consumed observation owner call is not active')
                if cid is None and not timer_stacks[tid]:
                    raise ValueError('consumed observation lacks sender call or timer owner')
                read_id = row.get('read_id')
                if not isinstance(read_id, str) or not read_id:
                    raise ValueError('unique actual native read_id required')
                if kind.startswith('getter'):
                    _reference(row.get('receiver'))
                    value(row['receiver'], 'receiver'); arguments(row)
                elif 'args' in row:
                    arguments(row)
                if kind.endswith('_enter'):
                    if read_id in reads:
                        raise ValueError('native read identity duplicated')
                    reads[read_id] = row
                    read_stacks[tid].append(read_id)
                else:
                    entered = reads.get(read_id)
                    if (not read_stacks[tid] or read_stacks[tid][-1] != read_id or entered is None
                            or entered['kind'] != kind + '_enter' or entered['method'] != row['method']
                            or entered['call_id'] != cid or entered['parent_call_id'] != row['parent_call_id']):
                        raise ValueError('native read completion does not match active initiation')
                    if kind == 'getter':
                        if row['method'] in ENVIRONMENT_METHODS.values() and read_id not in environment_operations:
                            raise ValueError('original SDK public call lacks environment operation effect')
                        if _identity(entered['receiver']) != _identity(row['receiver']):
                            raise ValueError('native read receiver identity changed')
                        if len(entered['args']) != len(row['args']):
                            raise ValueError('native read argument arity changed')
                        # Native out-parameters can mutate structs/lists while the
                        # original invocation runs. Keep both actual snapshots;
                        # only argument identity/type is invariant, not contents.
                        for index, (before, after) in enumerate(zip(entered['args'], row['args'])):
                            if before.get('type') != after.get('type'):
                                raise ValueError('native read argument wire type changed')
                            if before.get('id') != after.get('id'):
                                raise ValueError('native read argument object identity changed')
                            if before != after:
                                out_mutations.append(dict(read_id=read_id, argument=index, before=before, after=after))
                    value(row.get('result'), 'result')
                    if 'error' not in row:
                        raise ValueError('explicit actual native error/null required')
                    if row['error'] is not None:
                        gaps.append(dict(seq=seq, original_getter_exception=row['error']))
                    read_stacks[tid].pop()
            elif kind == 'timer_post_enter':
                pid = row.get('post_id')
                if not isinstance(pid, str) or not pid or pid in posts:
                    raise ValueError('distinct timer post occurrence required')
                for name in ('handler', 'runnable', 'token', 'message'):
                    _reference(row.get(name), nullable=name != 'handler')
                    value(row[name], name)
                queue(row)
                decimal(row.get('due_uptime_ms'))
                if type(row.get('insertion_seq')) is not int or row['insertion_seq'] < 0:
                    raise ValueError('timer insertion sequence required')
                insertions.append(row['insertion_seq'])
                posts[pid] = dict(enter=row, result=None, state='pending')
            elif kind == 'timer_post_result':
                post = posts.get(row.get('post_id'))
                if post is None or post['result'] is not None or type(row.get('result')) is not bool:
                    raise ValueError('post result without unique earlier post occurrence')
                post['result'] = row['result']
                if not row['result']:
                    if post['state'] != 'pending':
                        raise ValueError('rejected post occurrence already executed/cancelled')
                    post['state'] = 'rejected'
            elif kind == 'timer_cancel':
                method(row); arguments(row)
                _reference(row.get('handler'), nullable=False); value(row['handler'], 'handler')
                matched = row.get('matched_post_ids')
                if not isinstance(matched, list) or any(not isinstance(v, str) for v in matched) or len(set(matched)) != len(matched):
                    raise ValueError('exact distinct cancellation occurrence list required')
                handler = _identity(row['handler'])
                for pid in matched:
                    post = posts.get(pid)
                    if post is None or post['state'] != 'pending' or _identity(post['enter']['handler']) != handler:
                        raise ValueError('cancelled occurrence is not pending on same handler: ' + pid)
                # Check exact cancellation scope where native Handler signatures
                # expose identity args; unsupported scope cannot disappear.
                candidates = {pid: post for pid, post in posts.items()
                              if post['state'] == 'pending' and _identity(post['enter']['handler']) == handler}
                args = row['args']
                if 'removeCallbacksAndMessages' in row['method'] and len(args) == 1:
                    _reference(args[0])
                    token = _identity(args[0])
                    expected = {pid for pid, p in candidates.items()
                                if token is None or _identity(p['enter']['token']) == token}
                elif 'removeCallbacks' in row['method'] and args and len(args) <= 2:
                    _reference(args[0])
                    if len(args) == 2:
                        _reference(args[1])
                    expected = {pid for pid, p in candidates.items()
                                if args[0]['type'] != 'null' and _identity(p['enter']['runnable']) == _identity(args[0]) and
                                (len(args) == 1 or args[1]['type'] == 'null' or _identity(p['enter']['token']) == _identity(args[1]))}
                elif 'removeMessages' in row['method'] and args and len(args) <= 2 and args[0].get('type') == 'i32':
                    if len(args) == 2:
                        _reference(args[1])
                    if any('what' not in p['enter'] for p in candidates.values()):
                        expected = set(matched)
                        gaps.append(dict(seq=seq, unverifiable_cancel_scope='message.what absent'))
                    else:
                        expected = {pid for pid, p in candidates.items()
                                    if p['enter']['what'] == args[0]['value'] and
                                    (len(args) == 1 or args[1]['type'] == 'null' or
                                     _identity(p['enter']['token']) == _identity(args[1]))}
                else:
                    expected = set(matched)
                    gaps.append(dict(seq=seq, unverifiable_cancel_scope=row['method']))
                if set(matched) != expected:
                    raise ValueError('recorded timer cancellation scope omits/adds pending occurrences')
                for pid in matched:
                    posts[pid]['state'] = 'cancelled'
            elif kind in ('timer_execute_enter', 'timer_execute_exit'):
                pid = row.get('post_id')
                post = posts.get(pid)
                if post is None:
                    raise ValueError('timer dispatch has no captured enqueue occurrence')
                queue(row)
                for name in ('handler', 'runnable', 'message'):
                    _reference(row.get(name), nullable=name != 'handler')
                    value(row[name], name)
                    if row[name] != post['enter'][name]:
                        raise ValueError('timer execution ' + name + ' identity differs from enqueue')
                if row['queue_id'] != post['enter']['queue_id']:
                    raise ValueError('timer execution queue differs')
                if kind == 'timer_execute_enter':
                    expected = stacks[tid][-1] if stacks[tid] else None
                    if cid is None or cid in calls or row['parent_call_id'] != expected:
                        raise ValueError('timer dispatch context identity/parent invalid')
                    if post['state'] != 'pending' or post['result'] is False:
                        raise ValueError('timer executes cancelled/rejected/previously executed occurrence')
                    post['state'] = 'executing'
                    post['execution_context'] = cid
                    calls[cid] = row
                    stacks[tid].append(cid)
                    timer_stacks[tid].append(pid)
                else:
                    if (post['state'] != 'executing' or not timer_stacks[tid] or timer_stacks[tid][-1] != pid
                            or cid != post.get('execution_context') or not stacks[tid] or stacks[tid][-1] != cid):
                        raise ValueError('timer exit does not match active execution')
                    if row['parent_call_id'] != calls[cid]['parent_call_id']:
                        raise ValueError('timer exit parent differs')
                    stacks[tid].pop()
                    timer_stacks[tid].pop()
                    post['state'] = 'executed'
            elif kind == 'output':
                if not ready_rows:
                    raise ValueError('original output before recorder ready')
                operation = row.get('operation')
                if operation not in ('fire', 'client_send', 'start_service', 'stop_service'):
                    raise ValueError('unknown original transport operation')
                if 'topic' not in row or (row['topic'] is not None and (not isinstance(row['topic'], str) or not re.fullmatch(r'[0-9]+', row['topic']))):
                    raise ValueError('original decimal topic or explicit null required')
                payload = row.get('payload_b64')
                if 'payload_b64' not in row or (payload is not None and not isinstance(payload, str)):
                    raise ValueError('complete payload base64 or explicit null required')
                if operation in ('fire', 'client_send') and payload is None:
                    raise ValueError('fire/client request payload cannot be null')
                if payload is not None:
                    base64.b64decode(payload, validate=True)
        except (ValueError, TypeError, KeyError) as exc:
            gaps.append(dict(seq=seq, source_line=row.get('source_line'), invalid_event=str(exc)))
    if len(end_rows) != 1:
        gaps.append(dict(capture_end_count=len(end_rows)))
    if len(ready_rows) != 1:
        gaps.append(dict(ready_event_count=len(ready_rows)))
    if not required_hooks:
        gaps.append(dict(required_hook_inventory_missing=True))
    missing = sorted(method for method in required if hooks.get(method) != 'installed')
    if missing:
        gaps.append(dict(required_hooks_missing=missing))
    if len(set(insertions)) != len(insertions) or insertions != sorted(insertions):
        gaps.append(dict(timer_insertion_order_invalid=insertions))
    for tid, stack in stacks.items():
        if stack:
            gaps.append(dict(unclosed_thread_calls=dict(thread_id=tid, call_ids=stack)))
    for tid, stack in timer_stacks.items():
        if stack:
            gaps.append(dict(unclosed_timer_executions=dict(thread_id=tid, post_ids=stack)))
    for tid, stack in read_stacks.items():
        if stack:
            gaps.append(dict(unclosed_native_reads=dict(thread_id=tid, read_ids=stack)))
    for pid, post in posts.items():
        if post['result'] is None:
            gaps.append(dict(timer_post_result_missing=pid))
    return dict(gaps=gaps, event_counts=dict(counts), hooks=hooks, required_hooks=sorted(required),
                capture_end=end_rows[0] if len(end_rows) == 1 else None,
                pending_timers=[p['enter'] for p in posts.values() if p['state'] == 'pending'],
                timer_states={pid: p['state'] for pid, p in posts.items()},
                object_classes=object_classes, native_out_argument_mutations=out_mutations,
                input_argument_mutations=input_mutations, retained_input_assertions=retained_inputs,
                native_pointer_observations={oid: sorted(values, key=lambda v: '' if v is None else v)
                                             for oid, values in pointer_observations.items()})


def normalize(raw: Path, out: Path, *, source: dict, required_hooks: list[str],
              artifacts: list | None = None, receipt: dict | None = None,
              metadata: dict | None = None) -> dict:
    """Normalize completed capture files only; no ADB or process operations."""
    out.mkdir(parents=True, exist_ok=True)
    try:
        raw_rows, gaps = capture.read_raw(raw)
    except OSError as exc:
        # A restored runner-failed session may have no app/events.jsonl at all.
        # Do not fabricate an empty transcript or lose its recovery receipt.
        report = dict(schema='denza-stock-sender-phase2-capture-v1', status='INCOMPLETE',
                      parityEstablished=False, startup_clipping=False,
                      gaps=[dict(raw_capture_unavailable=dict(path=str(raw.resolve()), error=str(exc)))],
                      source=source, artifacts=artifacts or [], verified_artifacts=[],
                      receipt=receipt, metadata=metadata or {}, raw_path=str(raw.resolve()),
                      raw_sha256=None, ledger_sha256=None, reference_sha256=None,
                      external_input_receipts=0, reference_outputs=0, event_counts={},
                      produced_transcripts=[])
        (out / 'capture-manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        return report
    events = []
    for line, payload in raw_rows:
        if payload.get('schema') == SCHEMA:
            # Preserve every recorder member; source_line is supplemental raw
            # delivery provenance, never an upstream object snapshot substitute.
            events.append(dict(payload, source_line=line))
        elif (str(payload.get('kind', '')).startswith(('phase2_', 'sender_phase2')) or
              payload.get('kind') in KINDS):
            gaps.append(dict(source_line=line, invalid_phase2_schema=payload))
        elif payload.get('kind') in ('adas_boundary_install_error', 'reroute_hook_error', 'reroute_hook_install_error'):
            gaps.append(dict(source_line=line, base_fixture_error=payload))
    seqs = [event.get('seq') for event in events]
    valid_ids = all(type(seq) is int and seq >= 0 for seq in seqs)
    if not events or not valid_ids or sorted(seqs) != list(range(len(events))):
        gaps.append(dict(global_sequence_loss_or_duplicate=dict(count=len(seqs), observed=seqs)))
    canonical = sorted(events, key=lambda row: (type(row.get('seq')) is not int,
                                               row.get('seq') if type(row.get('seq')) is int else row['source_line']))
    audit = audit_ledger(canonical, required_hooks)
    gaps.extend(audit['gaps'])
    if receipt is None or receipt.get('status') != 'observed' or receipt.get('baseline_restored') is not True:
        gaps.append(dict(original_run_receipt_incomplete=receipt))
    if source.get('apk_sha256') != APK_SHA256:
        gaps.append(dict(original_apk_pin_mismatch=source.get('apk_sha256')))
    identity, identity_artifacts, identity_gaps = capture.bind_runtime_identity(raw, source)
    gaps.extend(identity_gaps)
    all_artifacts = list(artifacts or []) + identity_artifacts
    provenance_manifest = dict(schema='denza-stock-sender-phase2-capture-v1', source=source,
                               runtime_identity=identity, artifacts=all_artifacts)
    provenance_path = out / 'provenance.json'
    provenance_path.write_text(json.dumps(provenance_manifest, ensure_ascii=False, indent=2) + '\n')
    try:
        verified = bench.validate_artifacts(provenance_path, source)
        recorder = Path(next(a['path'] for a in verified if a['role'] == 'recorder'))
        agent = Path(next(a['path'] for a in verified if a['role'] == 'runtime_agent'))
        if recorder.read_bytes() not in agent.read_bytes():
            gaps.append(dict(recorder_snapshot_not_in_loaded_agent=str(recorder)))
    except (OSError, ValueError, KeyError, StopIteration) as exc:
        verified = []
        gaps.append(dict(provenance_validation_failed=str(exc)))
    from . import scenario_gate
    scenario_audit = scenario_gate.declared_audit(canonical, metadata or {}, all_artifacts,
        Path(next(a['path'] for a in verified if a['role'] == 'runtime_agent')) if verified else None)
    if scenario_audit is not None:
        gaps.extend(scenario_audit['gaps'])
        if not any('scenario_declaration_invalid' in gap for gap in scenario_audit['gaps']):
            verified += [a for a in all_artifacts if a.get('role') == 'scenario_contract']
    from . import callback_audit as callback_gate
    callback_report = callback_gate.declared_audit([dict(payload, source_line=line) for line, payload in raw_rows],
        metadata or {}, all_artifacts,
        Path(next(a['path'] for a in verified if a['role'] == 'runtime_agent')) if verified else None)
    if callback_report is not None:
        gaps.extend(callback_report['gaps'])
        verified += callback_report['verified_artifacts']
    from . import runtime_contract
    runtime_report = runtime_contract.audit([dict(payload, source_line=line) for line, payload in raw_rows],
        metadata or {}, all_artifacts,
        Path(next(a['path'] for a in verified if a['role'] == 'runtime_agent')) if verified else None)
    gaps.extend(runtime_report['gaps'])
    verified += runtime_report['verified_artifacts']
    fixtures = dict(scriptedAnswers=[dict(source_line=line, raw=p) for line, p in raw_rows
                                    if p.get('kind') == 'adas_fake_answer'],
                    senderFixtures=[dict(source_line=line, raw=p) for line, p in raw_rows
                                    if p.get('kind') in ('sender_start', 'adas_car_state', 'adas_vehicle_noa_fixture')],
                    statement='Fixture flags and scripted responses are emulator inputs, never ECU evidence')
    header = dict(kind='header', schema=LEDGER_SCHEMA, origin='original-stock-dex', source=source,
                  runtime_identity=identity, raw_capture=dict(path=str(raw.resolve()), sha256=bench.digest(raw)),
                  ordering='recorder global seq; raw delivery/source_line retained', startup_clipping=False,
                  fixture_inputs=fixtures, interpretation='external call entries are receipts; getters/clocks/internal processing/timers are consumed observations')
    if runtime_report.get('input_boundary_audit') is not None:
        header['input_boundary_policy'] = (metadata or {}).get('input_boundary_policy')
    if runtime_report.get('terminal_policy_audit') is not None:
        header['terminal_policy'] = (metadata or {}).get('terminal_policy')
    if scenario_audit is not None:
        header['scenario_contract'] = (metadata or {}).get('scenario_contract')
    if callback_report is not None:
        header['callback_contract'] = (metadata or {}).get('callback_contract')
    ledger_path = out / 'ledger.jsonl'
    bench.write_jsonl(ledger_path, [header] + canonical)
    external = [r for r in canonical if r.get('kind') == 'call_enter' and r.get('role') == 'external_input']
    outputs = [r for r in canonical if r.get('kind') == 'output']
    reference_effects = [r for r in canonical if r.get('kind') in ('output', 'environment_operation')]
    if not external:
        gaps.append(dict(external_input_receipts_missing=True))
    if not outputs:
        gaps.append(dict(original_reference_outputs_missing=True))
    reference_header = dict(kind='header', schema=REFERENCE_SCHEMA, origin='original-stock-dex', source=source,
                            ledger_sha256=bench.digest(ledger_path), startup_clipping=False, fixture_inputs=fixtures)
    bench.write_jsonl(out / 'external-inputs.jsonl', [header] + external)
    bench.write_jsonl(out / 'reference.jsonl', [reference_header] + reference_effects +
                      [dict(kind='coverage_gap', data=gap) for gap in gaps])
    # Full raw wrapper delivery is preserved, including non-ledger fixture rows.
    delivery = []
    for line, text in enumerate(raw.read_text().splitlines(), 1):
        if not text.strip():
            continue
        try:
            row = json.loads(text)
        except ValueError:
            row = dict(unparsed_line=text)
        delivery.append(dict(source_line=line, raw=row))
    bench.write_jsonl(out / 'raw-delivery.jsonl', delivery)
    report = dict(schema='denza-stock-sender-phase2-capture-v1', status='INCOMPLETE' if gaps else 'CAPTURED',
                  parityEstablished=False, startup_clipping=False, gaps=gaps,
                  frida_delivery_reordering=valid_ids and seqs != sorted(seqs),
                  source=source, runtime_identity=identity, artifacts=all_artifacts, verified_artifacts=verified,
                  fixture_inputs=fixtures, receipt=receipt, metadata=metadata or {},
                  scenario_audit=scenario_audit,
                  callback_audit=callback_report,
                  runtime_contract_audit=runtime_report,
                  raw_path=str(raw.resolve()), raw_sha256=bench.digest(raw), ledger_sha256=bench.digest(ledger_path),
                  reference_sha256=bench.digest(out / 'reference.jsonl'),
                  external_input_receipts=len(external), reference_outputs=len(outputs),
                  **{key: value for key, value in audit.items() if key != 'gaps'})
    (out / 'capture-manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('raw', 'out', 'recorder', 'receipt', 'required-hooks'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--apk', type=Path, default=capture.APK)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    source, artifacts = capture.original_artifacts(args.apk, args.recorder, args.out)
    required = json.loads(args.required_hooks.read_text())
    if not isinstance(required, list) or any(not isinstance(method, str) for method in required):
        parser.error('--required-hooks must contain a JSON array of original method IDs')
    report = normalize(args.raw, args.out, source=source, required_hooks=required, artifacts=artifacts,
                       receipt=json.loads(args.receipt.read_text()))
    print(json.dumps(dict(status=report['status'], gaps=len(report['gaps']), parityEstablished=False)))
    return 0 if report['status'] == 'CAPTURED' else 2


if __name__ == '__main__':
    raise SystemExit(main())
