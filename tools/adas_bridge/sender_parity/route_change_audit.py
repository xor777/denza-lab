"""Prove route-change fixture stages against actual original processing receipts."""
from __future__ import annotations
import json
import base64
import re

CASE = 'route-change'
POLICY = 'actual-native-two-route-v1'
STOP_POLICY = 'after_route_processing_and_gnss_inputs_v1'
G = 'k.h.e0.c.g.'
NAMES = ['retain-first-original-route', 'observe-second-original-G', 'deliver-old-native-progress',
         'observe-original-K', 'select-retained-old-path', 'observe-original-C-old',
         'restore-current-native-path', 'observe-original-C-current',
         'deliver-stale-native-selection-ack', 'observe-original-I-after-stale-ack',
         'deliver-native-selection-error', 'observe-original-I-after-error']


def plan(source: str) -> list[dict]:
    found = re.findall(r'^const P2_ROUTE_CHANGE_STEPS=(\[.*\]);$', source, re.MULTILINE)
    if len(found) != 1:
        raise ValueError('unique immutable route-change JSON step table required')
    steps = json.loads(found[0])
    if ([s.get('name') for s in steps] != NAMES
            or any(not isinstance(s.get('trigger'), str) or not s['trigger'] for s in steps)):
        raise ValueError('exact event-driven route-change plan required')
    return steps


def same(a, b, *, pointer_alias=False):
    if not isinstance(a, dict) or not isinstance(b, dict) or a.get('type') != 'object' or b.get('type') != 'object':
        return False
    return a.get('class') == b.get('class') and (
        bool(a.get('id')) and a.get('id') == b.get('id') or
        pointer_alias and a.get('native_pointer') not in (None, '0') and a.get('native_pointer') == b.get('native_pointer'))


def audit(raw: list[dict], steps: list[dict], contract: dict) -> list[dict]:
    try:
        if contract.get('route_change_policy') != POLICY or [s.get('name') for s in steps] != NAMES:
            raise ValueError('declared two-native-route policy and all twelve steps required')
        rows = sorted((r for r in raw if r.get('schema') == 'denza-stock-sender-phase2-v1'), key=lambda r: r['seq'])
        by_seq = {r['seq']: r for r in rows}
        calls = {r['call_id']: r for r in rows if r.get('kind') == 'call_enter'}
        exits = {r['call_id']: r for r in rows if r.get('kind') == 'call_exit'}
        timers = {r['call_id']: r for r in rows if r.get('kind') == 'timer_execute_enter'}
        posts = {r['post_id']: r for r in rows if r.get('kind') == 'timer_post_enter'}

        def evidence(data, method):
            if type(data.get('source_seq')) is not int or not isinstance(data.get('call_id'), str):
                raise ValueError('exact integer source sequence and call identity required')
            end = by_seq[data['source_seq']]
            entry = calls[data['call_id']]
            if (end.get('kind') != 'call_exit' or end.get('call_id') != data['call_id']
                    or entry.get('method', '').split('(')[0] != G+method
                    or end.get('error') is not None or not same(entry['receiver'], data['drive_ref'])):
                raise ValueError('exact successful original '+method+' exit and drive identity required')
            return entry, end

        def native_id(entry, path, expected):
            end = exits[entry['call_id']]
            return any(r.get('kind') == 'getter' and '.PathInfo.getPathID(' in r.get('method', '')
                       and same(r.get('receiver'), path, pointer_alias=True)
                       and r.get('result') == {'type': 'i64', 'decimal': expected} and r.get('error') is None
                       and entry['seq'] < r['seq'] < end['seq'] for r in rows)

        def context(data):
            if not all(isinstance(data.get(k), str) and re.fullmatch(r'-?(?:0|[1-9][0-9]*)', data[k])
                       for k in ('path_id', 'request_id')):
                raise ValueError('actual native path/request decimal identities required')
            if type(data['input_evidence'].get('source_seq')) is not int:
                raise ValueError('exact source progress sequence required')
            incoming = by_seq[data['input_evidence']['source_seq']]
            if (incoming.get('kind') != 'call_enter' or incoming.get('call_id') != data['input_evidence']['call_id']
                    or incoming.get('method', '').split('(')[0] != G+'l' or len(incoming.get('args', [])) != 2
                    or incoming['args'][0].get('id') != data['navi_ref'].get('id')
                    or not same(incoming['args'][1], data['source_path_ref'], pointer_alias=True)
                    or incoming['args'][0].get('fields', {}).get('pathID') != {'type': 'i64', 'decimal': data['path_id']}
                    or not native_id(incoming, data['source_path_ref'], data['path_id'])):
                raise ValueError('actual original progress NaviInfo/path/native ID receipt required')
            return incoming

        old, current = steps[0]['old'], steps[1]['current']
        old_input, current_input = context(old), context(current)
        if old['path_id'] == current['path_id'] or old['request_id'] == current['request_id']:
            raise ValueError('second actual native route must have a distinct native path ID')
        if not same(current['path_ref'], current['source_path_ref'], pointer_alias=True):
            raise ValueError('current native path differs from original SDK progress source')
        copy = old['sdk_copy']
        copies = [r for r in raw if r.get('kind') == 'native_callback_fixture_input']
        if (len(copies) != 1 or copies[0].get('case') != CASE or copies[0].get('input_kind') != 'sdk-path-copy'
                or any(copies[0].get(k) != v for k, v in copy.items())
                or copy.get('constructor') != 'com.autonavi.gbl.common.path.option.PathInfo(PathInfo)'
                or not same(copy.get('source_ref'), old['source_path_ref']) or not same(copy.get('input_ref'), old['path_ref'])
                or copy.get('source_path_id') != old['path_id'] or copy.get('input_path_id') != old['path_id']
                or not isinstance(copy.get('source_length'), str) or not re.fullmatch(r'0|[1-9][0-9]*', copy['source_length'])
                or copy.get('source_length') != copy.get('input_length')):
            raise ValueError('actual SDK path copy constructor/ID/length provenance required; pointer equality is not assumed')
        attached = [r for r in raw if r.get('kind') == 'native_callback_fixture_attached']
        if len(attached) != 1 or not attached[0]['source_line'] < copies[0]['source_line'] < steps[0]['source_line']:
            raise ValueError('SDK input copy must be declared after attachment and before first delivered stage')
        methods = ['G', 'G', 'l', 'K', 'onChangeNaviPath', 'C', 'onChangeNaviPath', 'C', 'g', 'I', 'w', 'I']
        evidence_rows = [evidence(step['evidence'], method) for step, method in zip(steps, methods)]
        terminal_seqs = [end['seq'] for _, end in evidence_rows]
        if terminal_seqs != sorted(set(terminal_seqs)):
            raise ValueError('actual G/progress/K/change/C processing exits must occur in declared order')
        if old_input['seq'] >= evidence_rows[2][0]['seq'] or current_input['seq'] >= evidence_rows[2][0]['seq']:
            raise ValueError('retained/current contexts must originate from prior actual SDK progress')
        for step in steps:
            if ('old' in step and step['old'] != old) or ('current' in step and step['current'] != current):
                raise ValueError('retained/current native context changed across fixture stages')
        drive = evidence_rows[0][0]['receiver']
        if any(not same(entry['receiver'], drive) for entry, _ in evidence_rows) or not same(old_input['receiver'], drive) or not same(current_input['receiver'], drive):
            raise ValueError('all route stages must use the same actual original navigation-owned drive')
        for index, ctx in ((0, old), (1, current)):
            entry, end = evidence_rows[index]
            if not same(entry['args'][0], ctx['route_ref']):
                raise ValueError('observed original G route input identity differs')
            if not any(r.get('kind') == 'getter' and '.RouteCarResultData.getRequestId(' in r.get('method', '')
                       and same(r.get('receiver'), ctx['route_ref']) and r.get('result') == {'type': 'i64', 'decimal': ctx['request_id']}
                       and r.get('error') is None and entry['seq'] < r['seq'] < end['seq'] for r in rows):
                raise ValueError('actual original route request ID getter evidence missing')
        ack_entry, ack_exit = evidence(steps[1]['ack_evidence'], 'g')
        if (ack_entry.get('args') != [{'type': 'i64', 'decimal': current['path_id']}]
                or not same(ack_entry['receiver'], drive) or not terminal_seqs[1] < ack_exit['seq'] < evidence_rows[2][0]['seq']):
            raise ValueError('actual decoded ACK for current native path before old progress required')
        entry, _ = evidence_rows[2]
        if (entry['args'][0].get('id') != old['navi_ref']['id'] or not same(entry['args'][1], old['path_ref'])
                or entry['args'][0].get('fields', {}).get('pathID') != {'type': 'i64', 'decimal': old['path_id']}):
            raise ValueError('old progress must deliver retained original NaviInfo and actual SDK path copy')
        for index in (3, 5, 7):
            entry, _ = evidence_rows[index]
            parent = entry.get('parent_call_id')
            seen = set()
            while parent in calls:
                if parent in seen:
                    raise ValueError('cyclic processing ancestry')
                seen.add(parent)
                parent = calls[parent].get('parent_call_id')
            dispatch = timers.get(parent)
            if dispatch is None or dispatch.get('queue_id') != 'SOMEIP':
                raise ValueError('K/C processing must execute through an actual original SOMEIP dispatch')
            post = posts[dispatch['post_id']]
            expected_parent = evidence_rows[{3: 2, 5: 4, 7: 6}[index]][0]['call_id']
            if post.get('call_id') != expected_parent:
                raise ValueError('K/C dispatch must originate from the matching public fixture callback')
            target = old['path_id'] if index in (3, 5) else current['path_id']
            if entry['args'][0] != {'type': 'i64', 'decimal': target}:
                raise ValueError('actual K/C target differs from retained/current native path ID')
            if index == 3 and not same(entry['args'][1], old['path_ref']):
                raise ValueError('actual K must consume the retained old native SDK path copy')
        # These are the immutable fixture's declared actual-ID operands, not
        # inferred values of the sender's hidden current-path field.
        for index, before, after in ((4, current, old), (6, old, current)):
            if evidence_rows[index][0]['args'] != [{'type': 'i64', 'decimal': before['path_id']},
                                                  {'type': 'i64', 'decimal': after['path_id']}]:
                raise ValueError('public original path-change operands differ from the declared actual-ID order')

        def descendant(row, owner):
            cid, seen = row.get('call_id'), set()
            while cid in calls and cid not in seen:
                if cid == owner: return True
                seen.add(cid); cid = calls[cid].get('parent_call_id')
            return False
        for trigger_index, processing_index in ((8, 9), (10, 11)):
            trigger, trigger_end = evidence_rows[trigger_index]
            actual, actual_end = evidence_rows[processing_index]
            expected = [{'type': 'i64', 'decimal': old['path_id']}] if trigger_index == 8 else []
            if trigger['args'] != expected or actual['args'] != [{'type': 'i64', 'decimal': current['path_id']}]:
                raise ValueError('positive I public trigger/current native ID operands differ')
            selectors = [r for r in calls.values() if r.get('method') == G+'onSelectMainPathStatus(long,int)'
                         and descendant(r, trigger['call_id']) and same(r.get('receiver'), drive)
                         and r.get('args') == [{'type': 'i64', 'decimal': current['path_id']}, {'type': 'i32', 'value': 1}]]
            if len(selectors) != 1: raise ValueError('distinct actual nested positive selector required')
            selector = selectors[0]
            selector_end = exits.get(selector['call_id'])
            if selector_end is None or selector_end.get('error', 'missing') is not None:
                raise ValueError('actual positive selector completion required')
            parent = actual.get('parent_call_id')
            while parent in calls: parent = calls[parent].get('parent_call_id')
            dispatch = timers.get(parent)
            if (dispatch is None or dispatch.get('queue_id') != 'SOMEIP'
                    or dispatch.get('runnable', {}).get('class') != 'k.h.e0.c.a'
                    or posts[dispatch['post_id']].get('call_id') != selector['call_id']):
                raise ValueError('positive I must dispatch the actual nested selector post')
            sends = [r for r in calls.values() if r.get('method') == 'k.h.e0.d.f.k.i(com.autosdk.bussiness.navi.route.model.RouteCarResultData,long,boolean)'
                     and descendant(r, actual['call_id']) and r.get('args') == [current['route_ref'],
                         {'type': 'i64', 'decimal': current['path_id']}, {'type': 'boolean', 'value': True}]]
            if len(sends) != 1 or exits.get(sends[0]['call_id'], {}).get('result') != {'type': 'boolean', 'value': True}:
                raise ValueError('actual positive I route/path lookup must return true')
            send_end = exits[sends[0]['call_id']]
            if (send_end.get('error', 'missing') is not None or not actual['seq'] < sends[0]['seq'] < send_end['seq'] < actual_end['seq']):
                raise ValueError('actual successful nested route lookup completion required')
            for topic in ('1126084593287170', '1126093183352833'):
                matches = []
                for row in rows:
                    if (row.get('kind') != 'getter_enter' or row.get('method') != SDK_SEND
                            or not descendant(row, sends[0]['call_id'])): continue
                    fields = row.get('args', [{}])[0].get('fields', {})
                    if fields.get('mTopic') == {'type': 'i64', 'decimal': topic}:
                        verify_sdk_attempt(rows, row, send_end['seq']); matches.append(row)
                if not matches or (topic == '1126093183352833' and len(matches) != 1):
                    raise ValueError('positive I requires actual route-data packet(s) AND exactly one selection SDK attempt')

        audit_guidance_stop(raw, steps, contract, calls, exits, evidence_rows[-1][1])
    except (ValueError, KeyError, TypeError, IndexError, AttributeError) as exc:
        return [dict(callback_route_change_binding_invalid=str(exc))]
    return []


def audit_guidance_stop(raw, steps, contract, calls, exits, final_c, *, case=CASE, policy=STOP_POLICY, reason='route-processing-and-gnss-inputs-complete'):
    if contract.get('guidance_stop_policy') != policy:
        raise ValueError('explicit immutable route guidance stop policy required')
    count=contract.get('guidance_sample_count')
    if type(count) is not int or count < 1: raise ValueError('positive immutable guidance sample count required')
    gates=[r for r in raw if r.get('kind')=='native_callback_fixture_stop_gate']
    ends=[r for r in raw if r.get('kind')=='native_callback_fixture_end' and r.get('case')==case]
    if len(gates)!=1 or len(ends)!=1: raise ValueError('unique successful route fixture end and stop gate required')
    if (type(final_c.get('source_line')) is not int or type(ends[0].get('source_line')) is not int
            or final_c['source_line'] >= ends[0]['source_line']):
        raise ValueError('actual processing completion must precede successful fixture end')
    gate=gates[0]
    if (gate.get('case')!=case or gate.get('policy')!=policy or gate.get('status')!='conditions-satisfied'
            or gate.get('reason')!=reason
            or gate.get('callback_fixture_complete') is not True or type(gate.get('sample_count')) is not int
            or gate['sample_count']!=count or type(gate.get('delivered_samples')) is not int or gate['delivered_samples']!=count):
        raise ValueError('route stop gate prerequisites or declared sample count differ')
    returns=[r for r in raw if r.get('kind')=='reroute_gnss_input_return']
    if (len(returns)!=count or any(type(r.get('count')) is not int for r in returns)
            or sorted(r['count'] for r in returns)!=list(range(1,count+1))
            or any(type(r.get('source_line')) is not int or r['source_line']>=gate['source_line'] for r in returns)):
        raise ValueError('exact N actual GNSS returns before route stop gate required')
    destroyed=[r for r in calls.values() if r.get('method')=='k.h.e0.b.d.j()']
    if len(destroyed)!=1: raise ValueError('one actual original navigation destruction required; early destruction forbidden')
    entry=destroyed[0];end=exits.get(entry['call_id'])
    if (end is None or end.get('error','missing') is not None or entry['seq']<=final_c['seq'] or end['seq']<=entry['seq']):
        raise ValueError('original destruction must complete successfully after final actual processing')
    stops=[r for r in raw if r.get('kind')=='adas_presenter_stop']
    if (len(stops)!=1 or stops[0].get('reason')!=reason
            or type(stops[0].get('result')) is not bool):
        raise ValueError('original abStop SDK stop receipt with declared reason required')
    terminal=[r for r in raw if r.get('schema')=='denza-stock-sender-phase2-v1' and r.get('kind')=='capture_end']
    if len(terminal)!=1 or end['seq']>=terminal[0]['seq']: raise ValueError('original destruction must finish before capture end')
    ordered=[steps[-1],ends[0],gate,stops[0],entry,end,terminal[0]]
    lines=[r.get('source_line') for r in ordered]
    if any(type(line) is not int for line in lines) or lines!=sorted(set(lines)):
        raise ValueError('final processing/fixture end/stop gate/original stop/destruction/terminal provenance order differs')


SDK_SEND = 'com.autosdk.bussiness.pilot.someip.SomeIpServer.sendSomeIpEvent(ts.car.someip.sdk.common.SomeIpData)'


def verify_sdk_attempt(rows, entry, cutoff):
    """Verify the original SDK operation; a failed return does not invent a fire."""
    packet = entry['args'][0]
    fields = packet['fields']
    if (packet.get('type') != 'struct' or packet.get('class') != 'ts.car.someip.sdk.common.SomeIpData'
            or fields.get('mPayload', {}).get('type') != 'bytes'
            or not base64.b64decode(fields['mPayload']['base64'], validate=True)
            or fields.get('mTimeStamp', {}).get('type') != 'i64'
            or not re.fullmatch(r'-?(?:0|[1-9][0-9]*)', fields['mTimeStamp'].get('decimal', ''))):
        raise ValueError('actual nonempty SDK packet and exact timestamp required')
    completed = [r for r in rows if r.get('kind') == 'getter' and r.get('method') == SDK_SEND
                 and r.get('read_id') == entry.get('read_id') and r.get('call_id') == entry.get('call_id')]
    if (len(completed) != 1 or completed[0].get('args') != entry['args']
            or completed[0].get('error', 'missing') is not None
            or completed[0].get('result') not in ({'type': 'i32', 'value': -1}, {'type': 'i32', 'value': 0})
            or not entry['seq'] < completed[0]['seq'] < cutoff):
        raise ValueError('actual matching SDK packet completion and return required')
    fires = [r for r in rows if r.get('kind') == 'output' and r.get('operation') == 'fire'
             and r.get('call_id') == entry.get('call_id') and entry['seq'] < r['seq'] < completed[0]['seq']]
    if (len(fires) > 1 or any(r.get('topic') != fields['mTopic']['decimal']
                             or r.get('payload_b64') != fields['mPayload']['base64'] for r in fires)
            or completed[0]['result']['value'] == 0 and len(fires) != 1):
        raise ValueError('actual SDK fire/return contradicts attempted packet')
