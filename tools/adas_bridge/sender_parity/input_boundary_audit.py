"""Audit the declared producer-stop fixture without claiming SDK removal success."""
from __future__ import annotations
import json
import re

POLICY = 'remove_position_observer_after_fixture_inputs_v1'
SCHEMA = 'denza-stock-sender-input-boundary-v1'
PREFIX = 'input_boundary_'
METHOD = 'com.autonavi.gbl.pos.PosService.removeLocInfoObserver(com.autonavi.gbl.pos.observer.IPosLocInfoObserver)'
SERVICE = 'com.autonavi.gbl.pos.PosService'
OBSERVER = 'com.byd.map.signal.GaoDeSignalManager'


def audit(raw: list[dict], metadata: dict, loaded: bytes) -> dict:
    originals = [r for r in raw if str(r.get('kind', '')).removeprefix('adas_').startswith(PREFIX) or r.get('schema') == SCHEMA]
    events = [dict(r, kind=str(r.get('kind', '')).removeprefix('adas_')) for r in originals]
    report = dict(policy=metadata.get('input_boundary_policy'), gaps=[], receipts=originals,
                  interpretation='Actual SDK removeLocInfoObserver call-returned; the SDK catches exceptions internally, so this is not independent removal verification')
    try:
        if metadata.get('input_boundary_policy') != POLICY:
            raise ValueError('exact declared producer input boundary policy required')
        if any(r.get('kind') not in (PREFIX+'installed', PREFIX+'disconnect_enter', PREFIX+'disconnect_exit')
               or r.get('schema') != SCHEMA or r.get('policy') != POLICY
               or r.get('errors') not in (None, []) or r.get('report_errors') not in (None, []) for r in events):
            raise ValueError('unknown/failed input boundary receipt or policy/schema/error inventory')
        by_kind = {kind: [r for r in events if r.get('kind') == PREFIX+kind]
                   for kind in ('installed', 'disconnect_enter', 'disconnect_exit')}
        if any(len(v) != 1 for v in by_kind.values()):
            raise ValueError('exactly one install/enter/exit input boundary receipt required')
        installed, entered, exited = (by_kind[k][0] for k in ('installed', 'disconnect_enter', 'disconnect_exit'))
        cases = re.findall(rb'^const REROUTE_CASE=(.*);$', loaded, re.MULTILINE)
        if len(cases) != 1:
            raise ValueError('unique actual loaded GNSS fixture declaration required')
        samples = json.loads(cases[0])['samples']
        if not isinstance(samples, list) or not samples:
            raise ValueError('actual loaded GNSS sample plan required')
        count = installed.get('sample_count')
        callback = (metadata.get('callback_contract') or {}).get('case')
        if (type(count) is not int or count != len(samples) or installed.get('callback_case') != callback
                or installed.get('status') != 'installed'):
            raise ValueError('installed sample count/callback case differs from immutable loaded input plan')
        for receipt in (entered, exited):
            if (receipt.get('sample_count') != count or receipt.get('callback_case') != callback
                    or type(receipt.get('delivered_samples')) is not int or receipt['delivered_samples'] != count
                    or receipt.get('started') is not True or receipt.get('navigation_active') is not False
                    or receipt.get('callback_fixture_complete') is not True or receipt.get('attempt') != 1
                    or type(receipt.get('attempt')) is not int or receipt.get('service_id') != 13
                    or type(receipt.get('service_id')) is not int or receipt.get('service_class') != SERVICE
                    or receipt.get('observer_class') != OBSERVER or receipt.get('method') != METHOD):
                raise ValueError('actual producer-boundary prerequisites/service/method/attempt invalid')
            basis = 'baseline-no-callback-fixture' if callback is None else 'observed-fixture-end'
            if receipt.get('callback_completion_basis') != basis:
                raise ValueError('input boundary callback completion basis differs')
            for key, klass in (('service_ref', SERVICE), ('observer_ref', OBSERVER)):
                ref = receipt.get(key)
                if (not isinstance(ref, dict) or ref.get('type') != 'object' or ref.get('class') != klass
                        or not isinstance(ref.get('id'), str) or not ref['id']):
                    raise ValueError('actual SDK service/observer identity required')
        returns = [r for r in raw if r.get('kind') == 'reroute_gnss_input_return']
        counts = [r.get('count') for r in returns]
        delivered = entered['delivered_samples']
        if (exited['delivered_samples'] != delivered or len(counts) != delivered
                or any(type(value) is not int for value in counts)
                or sorted(counts) != list(range(1, delivered+1))
                or any(type(r.get('source_line')) is not int or r['source_line'] >= entered['source_line'] for r in returns)):
            raise ValueError('all planned GNSS inputs require exact unique contiguous successful returns before disconnect')
        report['gnss_return_count'] = delivered
        report['gnss_delivery_reordering'] = counts != sorted(counts)
        if exited.get('status') != 'call-returned':
            raise ValueError('actual removeLocInfoObserver call did not return')
        if any(entered.get(k) != exited.get(k) for k in ('service_ref', 'observer_ref')):
            raise ValueError('SDK service/observer identity changed across disconnect call')
        boundaries = [r for r in raw if r.get('schema') == 'denza-stock-sender-phase2-v1' and r.get('kind') == 'capture_end']
        if len(boundaries) != 1:
            raise ValueError('unique original terminal boundary required')
        order = [installed, entered, exited, boundaries[0]]
        if callback is not None:
            ends = [r for r in raw if r.get('kind') == 'native_callback_fixture_end' and r.get('case') == callback]
            if len(ends) != 1 or ends[0].get('status') != 'scheduled-inputs-complete':
                raise ValueError('actual successful callback fixture end before disconnect required')
            order.insert(1, ends[0])
        lines = [r.get('source_line') for r in order]
        if any(type(line) is not int for line in lines) or lines != sorted(set(lines)):
            raise ValueError('input completion/disconnect/terminal raw receipt order invalid')
        report.update(sample_count=count, callback_case=callback, call_returned=True)
    except (ValueError, KeyError, TypeError, AttributeError, UnicodeError) as exc:
        report['gaps'].append(dict(input_boundary_contract_invalid=str(exc)))
    return report
