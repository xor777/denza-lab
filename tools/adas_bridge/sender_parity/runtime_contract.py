"""Verify declared observer/provider helpers against the actually loaded agent."""
from __future__ import annotations
import hashlib
import re
from pathlib import Path

SDK_POLICY = 'outermost environment getter/clock per nearest nonignored selected frame; SDK-interior Handler operations remain behind that seam; original source/observed Handler dispatch interposition reopens reads; direct opaque native arguments fail closed'


TERMINAL_POLICY = 'quiet_no_due_or_overdue_pending_v1'


def terminal_policy_audit(raw: list[dict], metadata: dict) -> dict | None:
    """Stronger declared closure observes uptime; it never supplies source time."""
    source = [r for r in raw if r.get('schema') == 'denza-stock-sender-phase2-v1']
    if metadata.get('terminal_policy') is None and not any('terminal_policy' in r for r in source):
        return None
    report = dict(policy=metadata.get('terminal_policy'), gaps=[])
    try:
        if metadata.get('terminal_policy') != TERMINAL_POLICY:
            raise ValueError('exact declared quiet terminal policy required')
        ready = [r for r in source if r.get('kind') == 'ready']
        ends = [r for r in source if r.get('kind') == 'capture_end']
        if len(ready) != 1 or len(ends) != 1 or any(r.get('terminal_policy') != TERMINAL_POLICY for r in (ready[0], ends[0])):
            raise ValueError('matching unique ready and terminal policy receipts required')
        if ends[0].get('recorder_diagnostics') != []:
            raise ValueError('explicit empty terminal recorder diagnostics inventory required')
        uptime = ends[0].get('observer_uptime_ms')
        if not isinstance(uptime, str) or not re.fullmatch(r'0|[1-9][0-9]*', uptime):
            raise ValueError('actual observer uptime exact nonnegative decimal string required')
        pending = ends[0].get('pending_posts')
        if not isinstance(pending, list):
            raise ValueError('complete pending occurrence snapshot required')
        for post in pending:
            due = post.get('due_uptime_ms')
            if not isinstance(due, str) or not re.fullmatch(r'-?(?:0|[1-9][0-9]*)', due):
                raise ValueError('pending occurrence exact due uptime required')
            if int(due) <= int(uptime):
                raise ValueError('due or overdue pending occurrence at terminal boundary: ' + str(post.get('post_id')))
        report.update(observer_uptime_ms=uptime, future_pending_count=len(pending))
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        report['gaps'].append(dict(terminal_policy_invalid=str(exc)))
    return report


def terminal_callback_context(observer: dict) -> str:
    """Permit only the recorder's balanced, sole current MethodExit closure."""
    count = observer.get('callbacks_in_flight')
    if type(count) is not int or count < 0:
        raise ValueError('terminal callback count must be an explicit nonnegative integer')
    if count == 0:
        if observer.get('active_callbacks', []) != [] or observer.get('current_callback') is not None:
            raise ValueError('terminal zero callback count conflicts with active context')
        return 'outside_callback'
    current, active, tid = observer.get('current_callback'), observer.get('active_callbacks'), observer.get('current_native_thread_id')
    if (count != 1 or not isinstance(current, dict) or not isinstance(active, list)
            or len(active) != 1 or active[0] != current or type(tid) is not int or tid <= 0
            or current.get('native_thread_id') != tid or type(current.get('native_thread_id')) is not int
            or current.get('kind') != 'MethodExit' or current.get('phase') != 'on_exit_observer'
            or current.get('selected_frame_popped') is not True
            or not isinstance(current.get('method'), str) or not current['method']):
        raise ValueError('terminal in-flight callback lacks sole balanced current MethodExit closure proof')
    return 'closing_selected_MethodExit'


def audit(raw: list[dict], metadata: dict, artifacts: list[dict], agent: Path | None) -> dict:
    gaps, verified, closure = [], [], None
    for row in raw:
        if (row.get('kind') == 'sender_phase2_recorder_diagnostic'
                or row.get('schema') == 'denza-stock-sender-phase2-diagnostic-v1'):
            gaps.append(dict(native_recorder_diagnostic=row))
    loaded = agent.read_bytes() if agent is not None else b''
    def helper(role):
        matches = [a for a in artifacts if a.get('role') == role]
        if len(matches) != 1:
            raise ValueError('exactly one '+role+' snapshot artifact required')
        a = matches[0]; path = Path(a['path'])
        if not path.is_absolute(): raise ValueError('absolute helper artifact path required')
        content = path.read_bytes()
        if not content or hashlib.sha256(content).hexdigest() != a.get('sha256') or content not in loaded:
            raise ValueError(role+' helper hash/actual loaded source differs')
        verified.append(a)
    backend = (metadata.get('observer_backend') == 'jvmti' or b"const P2_OBSERVER_BACKEND='jvmti';" in loaded
               or any(a.get('role') == 'jvmti_recorder' for a in artifacts))
    virtual = (metadata.get('vehicle_provider') == 'virtual' or any(a.get('role') == 'vehicle_fixture' for a in artifacts)
               or any(str(r.get('kind', '')).removeprefix('adas_') == 'vehicle_noa_fixture_installed' for r in raw))
    if backend:
        try:
            if metadata.get('observer_backend') != 'jvmti': raise ValueError('JVMTI metadata declaration missing/differs')
            helper('jvmti_recorder')
            if loaded.count(b"const P2_OBSERVER_BACKEND='jvmti';") != 1: raise ValueError('exact actual backend declaration required')
            ready = [r for r in raw if r.get('schema') == 'denza-stock-sender-phase2-v1' and r.get('kind') == 'ready']
            ends = [r for r in raw if r.get('schema') == 'denza-stock-sender-phase2-v1' and r.get('kind') == 'capture_end']
            if len(ready) != 1 or ready[0].get('observer_backend') != 'jvmti' or len(ends) != 1:
                raise ValueError('actual JVMTI ready/terminal receipts required')
            observer = ends[0].get('observer', {})
            if (observer.get('backend') != 'JVMTI original MethodEntry/Exit' or observer.get('fresh_environment') is not True
                    or observer.get('running') is not True or observer.get('stopped') is not False
                    or type(observer.get('callback_errors')) is not int or observer['callback_errors'] != 0
                    or observer.get('unclosed_frames') != []
                    or observer.get('sdk_read_boundary') != SDK_POLICY):
                raise ValueError('terminal JVMTI policy/errors/frame closure invalid')
            closure = terminal_callback_context(observer)
            methods, count = observer.get('suppressed_sdk_methods'), observer.get('suppressed_sdk_descendant_reads')
            if not isinstance(methods, list) or type(count) is not int or count < 0:
                raise ValueError('explicit counted SDK descendant boundary inventory required')
            if any(not isinstance(m, dict) or not isinstance(m.get('method'), str) or not m['method']
                   or not isinstance(m.get('outer_method'), str) or not m['outer_method']
                   or type(m.get('count')) is not int or m['count'] < 0 for m in methods):
                raise ValueError('invalid counted SDK descendant boundary entry')
            if sum(m['count'] for m in methods) != count:
                raise ValueError('SDK descendant boundary total differs from inventory')
        except (ValueError, KeyError, TypeError, OSError) as exc:
            gaps.append(dict(observer_backend_contract_invalid=str(exc)))
    if virtual:
        try:
            if metadata.get('vehicle_provider') != 'virtual': raise ValueError('virtual provider declaration missing/differs')
            helper('vehicle_fixture')
            def events(kind): return [r for r in raw if str(r.get('kind', '')).removeprefix('adas_') == kind]
            installed, validations = events('vehicle_noa_fixture_installed'), events('vehicle_noa_fixture_validation')
            if len(installed) != 1 or len(validations) != 1: raise ValueError('unique actual provider install/validation required')
            proof, validation = installed[0], validations[0]
            if (proof.get('mechanism') != 'registered_virtual_subclass' or proof.get('original_sender_methods_replaced') is not False
                    or proof.get('platform_device_attached') is not False):
                raise ValueError('provider must preserve original sender and unattached platform')
            expected = {'adas_type': 1, 'tip_island_active': True}
            if (validation.get('status') != 'PASS' or validation.get('same_adapter') is not True
                    or validation.get('actual') != expected or validation.get('expected') != expected
                    or validation.get('report_errors') != []):
                raise ValueError('original provider validation did not prove type1/tip-island true')
            reads = events('vehicle_noa_fixture_read')
            if not any(r.get('phase') == 'original_execution' and r.get('receiver_class') == proof.get('class_name') for r in reads):
                raise ValueError('provider used only for self-validation; original execution receipt absent')
            if events('vehicle_noa_fixture_error') or any(r.get('report_errors') for r in raw):
                raise ValueError('provider installation/report errors present')
            for speed in events('vehicle_speed_fixture_delivery'):
                if (speed.get('status') != 'PASS' or speed.get('input_kmh') != speed.get('actual_real_speed_kmh')
                        or speed.get('original_getters_replaced') is not False or speed.get('current_speed_modified') is not False):
                    raise ValueError('actual original speed-listener delivery invalid')
            source = [r for r in raw if r.get('schema') == 'denza-stock-sender-phase2-v1']
            if not any(r.get('kind') == 'getter' and r.get('method') == 'k.h.e0.b.f.r()'
                       and r.get('error') is None and r.get('result') == {'type': 'boolean', 'value': True} for r in source):
                raise ValueError('actual original NoaState.r true consumption missing')
            if not any(r.get('kind') == 'call_enter' and r.get('method') == 'k.h.a0.c.c.a.e0.I0(int)'
                       and r.get('args') == [{'type': 'i32', 'value': 1}] for r in source):
                raise ValueError('actual original I0 type1 callback missing')
            if not any(r.get('kind') == 'getter' and r.get('method') == 'k.h.e0.b.f.h()'
                       and r.get('error') is None and r.get('result') == {'type': 'i32', 'value': 1} for r in source):
                raise ValueError('actual original NoaState.h type1 consumption missing')
        except (ValueError, KeyError, TypeError, OSError) as exc:
            gaps.append(dict(virtual_provider_contract_invalid=str(exc)))
    from . import input_boundary_audit
    boundary = None
    if (metadata.get('input_boundary_policy') is not None
            or any(a.get('role') == 'input_boundary_fixture' for a in artifacts)
            or input_boundary_audit.POLICY.encode() in loaded
            or any(str(r.get('kind', '')).removeprefix('adas_').startswith(input_boundary_audit.PREFIX)
                   or r.get('schema') == input_boundary_audit.SCHEMA for r in raw)):
        boundary = input_boundary_audit.audit(raw, metadata, loaded)
        try:
            helper('input_boundary_fixture')
        except (ValueError, KeyError, TypeError, OSError) as exc:
            boundary['gaps'].append(dict(input_boundary_artifact_invalid=str(exc)))
        gaps.extend(boundary['gaps'])
    terminal = terminal_policy_audit(raw, metadata)
    if terminal is not None:
        gaps.extend(terminal['gaps'])
    return dict(gaps=gaps, verified_artifacts=verified, observer_backend_declared=backend,
                virtual_provider_declared=virtual, observer_terminal_closure=closure, terminal_policy_audit=terminal, input_boundary_audit=boundary)
