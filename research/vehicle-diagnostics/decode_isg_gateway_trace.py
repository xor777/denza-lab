#!/usr/bin/env python3
"""Replay the fixed ISG failure-report capture offline; no vehicle operations.

This narrow decoder requires a complete published report. A complete report is
not a complete CAN capture or a successful UDS response. Format01 stays raw.
"""
import argparse
import json
from pathlib import Path

from decode_supported_dtc import hex_bytes, pairs, parse_count, require

REQUESTS = ['22F194', '190109', '190A']
SOFTWARE = '48370011E0EA000000'


def normalized_frame(raw_hex):
    raw = hex_bytes(raw_hex)
    require(7 <= len(raw) <= 1024 and raw[:3] == bytes.fromhex('0A07EE'), 'frame owner/route/size')
    length, count, index = raw[3:6]
    require(1 <= index <= count <= 16, 'fragment count/index')
    offset = 7 if index == 1 else 6
    require(len(raw) == offset + length, 'undeclared frame tail or truncated payload')
    return raw, length, count, index, offset


def join_report(frames, *, complete):
    data = bytearray()
    total = None
    for position, row in enumerate(frames, 1):
        raw, length, count, index, offset = normalized_frame(row['raw'])
        require(index == position and (total is None or count == total), 'trace sequence/count changed')
        total = count
        limit = 233 if index == 1 else 230
        require(length <= limit and (index == count or length == limit), 'trace chunk length')
        if index == 1:
            require(length >= 3 and raw[6] == 3 and raw[7:9] == bytes.fromhex('190A'), 'failure result/request prefix')
        else:
            require(length > 0, 'empty continuation')
        data.extend(raw[offset:])
        require(len(data) <= 4095, 'trace payload bound')
    if complete:
        require(bool(frames) and len(frames) == total, 'incomplete published report')
    return bytes(data)


def decode_can_trace(data, availability):
    require(len(data) % 13 == 0, 'partial native record')
    rows = []
    uds = bytearray()
    declared = None
    expected_sequence = 1
    for offset in range(0, len(data), 13):
        record = data[offset:offset+13]
        address = int.from_bytes(record[3:5], 'big')
        payload = record[5:]
        require(record[2] == 0, 'unqualified native selector')
        row = dict(offset=offset, rawHex=record.hex().upper(),
            counterDelta=int.from_bytes(record[:2], 'big'), internalSelector=record[2],
            canIdHex=f'{address:03X}', canBytes=payload.hex().upper())
        rows.append(row)
        if offset < 26:
            expected_tx = bytes.fromhex('02190A5555555555' if offset == 0 else '3000005555555555')
            require(address == 0x7E6 and payload == expected_tx, 'observed request/flow-control record differs')
            continue
        require(address == 0x7EE, 'unqualified CAN route or extra TX record')
        if declared is None:
            require(payload[0] >> 4 == 1, 'RX does not start with ISO-TP first frame')
            declared = ((payload[0] & 15) << 8) | payload[1]
            require(6 < declared <= 4095, 'ISO-TP declared length')
            require((declared-3) % 4 == 0, 'declared supported-DTC record grammar')
            uds.extend(payload[2:])
        else:
            require(len(uds) < declared, 'RX after declared response end')
            require(payload[0] == 0x20 | (expected_sequence & 15), 'RX sequence gap')
            expected_sequence += 1
            uds.extend(payload[1:1+min(7, declared-len(uds))])
    require(declared is not None and uds[:3] == bytes([0x59, 0x0A, availability]), 'supported response prefix')
    full_bytes = ((len(uds)-3)//4)*4
    records = []
    for offset in range(3, 3+full_bytes, 4):
        code, status = uds[offset:offset+3], uds[offset+3]
        require(status & ~availability == 0, 'unavailable DTC status bit')
        records.append(dict(raw24Hex=code.hex().upper(), statusHex=f'{status:02X}'))
    require(len({r['raw24Hex'] for r in records}) == len(records), 'duplicate raw DTC')
    return dict(nativeRecords=rows, nativeRecordCount=len(rows),
        declaredUdsBytes=declared, recoveredUdsBytes=len(uds),
        udsPrefixHex=uds.hex().upper(), unrecoveredByteCount=declared-len(uds),
        reconstructedUdsComplete=len(uds) == declared,
        completeRawRecordCount=len(records), records=records,
        partialDtcTailHex=uds[3+full_bytes:].hex().upper(),
        consecutiveFrameCount=expected_sequence-1, sequenceWrapObserved=expected_sequence > 16,
        acceptedSuccessfulRegistry=False, displayCodeConversionApplied=False)


def decode_capture(text, plan_document):
    plan = plan_document['plans']['isg-trace']
    require(plan['maxRequests'] == 3 and plan['overallSoftMs'] == 15000
            and plan['overallHardMs'] == 17000, 'plan bounds')
    require(plan['targets'] == [dict(name='ISG', requestIdHex='7E6', responseIdHex='7EE',
            expectedSoftwareCode=SOFTWARE, requests=REQUESTS, expectedFormatIdHex='01')], 'fixed ISG plan')
    lines = text.splitlines()
    require(len(lines) >= 4 and lines[0] == 'REGISTERED', 'registered lifecycle')
    kind, start = pairs(lines[1])
    require(kind == 'START' and start == dict(plan='isg-trace', targets='1', max_requests='3',
            silence_ms='2000', request_ms='4000', overall_ms='15000'), 'START bounds')
    events, callbacks = [], 0
    active = previous = None
    count = report = end = None
    target_end = unregistered = False
    for line in lines[2:]:
        kind, fields = pairs(line)
        require(end is None, 'data after END')
        if kind == 'REQUEST':
            seq = len(events)+1
            require(not unregistered and not target_end and active is None and seq <= 3, 'request lifecycle/bound')
            require(fields['seq'] == str(seq) and fields['target'] == '7E6' and fields['ecu'] == 'ISG'
                    and fields['request'] == REQUESTS[seq-1], 'fixed request order')
            payload = hex_bytes(fields['request'])
            require(hex_bytes(fields['raw']) == bytes.fromhex('0A07E6')+bytes([len(payload)])+payload, 'request envelope')
            if previous:
                require(previous['state'] == ['IDENTITY_MATCH', 'FORMAT_READ'][seq-2], 'request prerequisite')
                require(int(fields['t_ns']) >= previous['replyNanos']+500_000_000, 'request gap')
                require(int(fields['t_ns']) < events[0]['requestNanos']+13_000_000_000, 'request overall bound')
            active = dict(seq=seq, requestHex=fields['request'], requestNanos=int(fields['t_ns']),
                frames=[], duplicates=[], responseHex=None, transport=False, state=None, projection=False)
            events.append(active)
        elif kind == 'TRANSPORT_RESULT':
            require(active is not None and not active['transport'] and fields == dict(seq=str(active['seq']), result='0'), 'transport result')
            active['transport'] = True
        elif kind in ['FRAME', 'GATEWAY_TRACE_FRAME', 'DUPLICATE']:
            require(not unregistered and fields['target'] == '7E6', 'callback lifecycle/target')
            seq = int(fields['seq'])
            require(1 <= seq <= len(events), 'callback sequence')
            row = events[seq-1]
            require(row['transport'], 'callback precedes transport result')
            at = int(fields['t_ns'])
            raw, length, total, index, offset = normalized_frame(fields['raw'])
            callbacks += 1
            require(callbacks <= 1024 and at >= row['requestNanos'], 'callback time/bound')
            require(at < events[0]['requestNanos']+13_000_000_000, 'callback overall bound')
            if kind == 'DUPLICATE':
                require(row is active or row is previous, 'duplicate outside current/previous')
                require(fields['raw'] in [r['raw'] for r in row['frames']], 'altered duplicate')
                row['duplicates'].append(fields)
            else:
                require(row is active and at < row['requestNanos']+4_000_000_000, 'original request deadline')
                require(fields['raw'] not in [r['raw'] for r in row['frames']], 'unlabelled duplicate')
                require((kind == 'FRAME') == (seq < 3), 'mixed normal/trace response')
                row['frames'].append(fields)
                row['replyNanos'] = at
                if seq < 3:
                    require(len(row['frames']) == 1 and total == index == 1 and raw[6] == 1, 'prerequisite response shape')
                else:
                    join_report(row['frames'], complete=False)
            if active:
                active['callbackCount'] = active.get('callbackCount', 0)+1
                require(active['callbackCount'] <= 64, 'exchange callback bound')
        elif kind == 'RESPONSE':
            require(active is not None and active['transport'] and active['seq'] < 3 and active['responseHex'] is None, 'response context')
            require(fields['seq'] == str(active['seq']) and fields['target'] == '7E6'
                    and fields['request'] == active['requestHex'] and fields['frames'] == '1', 'response metadata')
            require(len(active['frames']) == 1, 'response frame missing')
            raw, _, _, _, offset = normalized_frame(active['frames'][0]['raw'])
            require(fields['uds'] == raw[offset:].hex().upper(), 'response differs from declared payload')
            require(int(fields['t_ns']) == active['replyNanos'] and int(fields['latency_ms']) ==
                    (active['replyNanos']-active['requestNanos'])//1_000_000, 'response timestamp')
            active['responseHex'] = fields['uds']
            if active['seq'] == 1:
                require(fields['uds'] == '62F194'+SOFTWARE, 'software identity')
            else:
                count = parse_count(raw[offset:])
                require(count['formatId'] == 1, 'exact format01')
        elif kind == 'FORMAT':
            require(active is not None and active['seq'] == 2 and count is not None and not active['projection'], 'format context')
            require(fields == dict(seq='2', target='7E6', format='01',
                    status_available=count['statusAvailabilityHex'], mask09_count=str(count['memoryCountForMask09'])), 'format differs from wire')
            active['projection'] = True
        elif kind == 'GATEWAY_TRACE':
            require(active is not None and active['seq'] == 3 and report is None, 'report context')
            payload = join_report(active['frames'], complete=True)
            require(fields == dict(seq='3', target='7E6', result='03', frames=str(len(active['frames'])),
                    payload_bytes=str(len(payload)), trace_bytes=str(len(payload)-3),
                    request_prefix=payload[:3].hex().upper(), raw=payload.hex().upper()), 'report differs from frames')
            report = dict(gatewayResult=3, publishedReportComplete=True,
                payloadHex=payload.hex().upper(), retainedRequestPrefixHex=payload[:3].hex().upper(),
                traceByteCount=len(payload)-3,
                can=decode_can_trace(payload[3:], int(count['statusAvailabilityHex'], 16)))
        elif kind == 'STEP_END':
            require(active is not None and active['transport'], 'step context')
            expected = ['IDENTITY_MATCH', 'FORMAT_READ', 'GATEWAY_TRACE_CAPTURED'][active['seq']-1]
            require(fields == dict(seq=str(active['seq']), state=expected), 'step state')
            require((active['seq'] == 1 and active['responseHex'] is not None)
                    or (active['seq'] == 2 and active['projection']) or (active['seq'] == 3 and report is not None), 'step evidence missing')
            active['state'] = expected
            previous, active = active, None
        elif kind == 'TARGET_END':
            require(not target_end and active is None and len(events) == 3 and events[-1]['state'] == 'GATEWAY_TRACE_CAPTURED', 'target lifecycle')
            require(fields == dict(target='7E6', state='GATEWAY_TRACE_CAPTURED'), 'target outcome')
            target_end = True
        elif kind == 'UNREGISTERED':
            require(not fields and target_end and not unregistered, 'unregister lifecycle')
            unregistered = True
        elif kind == 'END':
            require(unregistered and active is None and fields == dict(reason='PLAN_DONE', requests='3', completed='3',
                    targets='1', cleanup='true', callbacks=str(callbacks), queued='0'), 'END lifecycle/counts')
            end = fields
        else:
            raise ValueError('Unsupported or failed capture line: '+kind)
    require(end is not None and report is not None, 'incomplete capture')
    return dict(schema='denza-lab.isg-gateway-trace-replay.v1', vehicleOperations=0,
        softwareCode=SOFTWARE, formatRead=count, events=events, probeEnd=end, report=report,
        qualifiedFactoryDefinitions=[], qualifiedWarningDtcLinks=[],
        limitations=['A complete published MCU failure report is not a complete CAN-wire observation.',
            'Native code omits mismatched-sequence frames from this report; no such mismatch is established by the capture.',
            'The retained third request-prefix byte is not a byte sent in the two-byte190A request.',
            'Counter units and flow-control versus first-frame wire chronology remain unqualified.',
            'This decoder qualifies the observed selector0 and initial TX/flow-control records only; other report variants require separate qualification.',
            'START has no timestamp; the observable upper bound is13s after the first request (15s plan minus2s required quiet).',
            'Format01 is raw; reconstructed partial records are not a successful supported registry.'])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('probe', type=Path)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    value = decode_capture(a.probe.read_text(), json.loads(a.plan.read_text()))
    a.output.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')


if __name__ == '__main__':
    main()
