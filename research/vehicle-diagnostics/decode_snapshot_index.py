#!/usr/bin/env python3
"""Offline decoder for fixed snapshot-index discovery plans; never sends requests.

Usage: python3 decode_snapshot_index.py CAPTURE/probe.txt --plan BUILD/plan.json
       [--allow-partial] [--output result.json]

Request1903 has no status mask. Response5903 consists only of its two-byte SID/
subfunction prefix followed by(raw DTC3,snapshot record number1)tuples. The last
byte is a record identifier, NOT a DTC status. Format comes only from the same
exact-F194 target's separate190109 response. Empty5903 is an explicitly empty
index, not a declaration of health or lack of DTC support. Record IDs never
trigger speculative1904/1906 reads, and do not define DID/extended-data codecs.
"""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

from decode_supported_dtc import (assemble, hex_bytes, observed_callback_count,
                                  pairs, parse_count, require, standard_display)

SNAPSHOT_REQUESTS = ['22F194','190109','1903']
SNAPSHOT_RECORD_REQUEST = '19041A3A2201'
SNAPSHOT_RECORD_REQUESTS = SNAPSHOT_REQUESTS + [SNAPSHOT_RECORD_REQUEST]
SNAPSHOT_RECORD_PAIR = dict(raw24Hex='1A3A22',snapshotRecordNumberHex='01')
DISCOVERY_CONSTRAINTS = {
    'implementedSends': [],
    'requestHex': '1903',
    'positiveResponsePrefixHex': '5903',
    'positiveByteLength': '2 + 4*n',
    'tupleFields': ['raw24bitDtcBigEndian3Bytes','snapshotRecordNumber1Byte'],
    'responseHasStatusMask': False,
    'responseHasFormatIdentifier': False,
    'tupleHasDtcStatusByte': False,
    'sameDtcDifferentRecordNumbersAllowed': True,
    'exactDuplicatePairsAccepted': False,
    'emptyIndexProvesHealth': False,
    'snapshotIndexDiscoversExtended06RecordNumbers': False,
    'snapshot04RawResearchRequires': 'A separately authorized fixed read of an observed exact DTC/record ID, bound to exact ECU/software/format; no automatic requests.',
    'snapshot04InterpretationRequires': 'An ECU/software-specific DID-length/value schema. Without it the record header and remaining opaque bytes can be retained, but no DID values or engineering units are decoded.',
    'extended06Requires': 'Independent discovery/applicable layout for extended-data record IDs;1903 supplies none.',
    'sourceReferences': ['https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html',
        'research/vehicle-diagnostics/dtc-source-followup-20261021.json'],
    'nativeBoundary': 'The retained native snapshot/extended consumers copy an opaque byte vector and do not provide remote ECU-specific names/lengths/units.',
}


def parse_snapshot_index(uds):
    require(len(uds) >= 2 and uds[:2] == b'\x59\x03' and (len(uds)-2)%4 == 0,
            '19 03 snapshot-index shape')
    records = [dict(raw24Hex=uds[i:i+3].hex().upper(),snapshotRecordNumberHex=f'{uds[i+3]:02X}')
               for i in range(2,len(uds),4)]
    counts = Counter((r['raw24Hex'],r['snapshotRecordNumberHex']) for r in records)
    duplicates = [dict(raw24Hex=raw,snapshotRecordNumberHex=number)
                  for (raw,number),n in sorted(counts.items()) if n > 1]
    return dict(records=records,recordCount=len(records),duplicatePairs=duplicates,
                integrityPass=not duplicates,
                meaning='returned snapshot-record identifiers, not DTC statuses or a supported-code registry')


def parse_snapshot_record(uds, status_availability):
    """Read only the fixed 1A3A22/01 header; the DID/value tail is opaque."""
    require(0 <= status_availability <= 255, 'snapshot record status availability')
    require(len(uds) >= 8 and uds[:2] == b'\x59\x04', '19 04 snapshot-record header')
    require(uds[2:5] == bytes.fromhex(SNAPSHOT_RECORD_PAIR['raw24Hex']), 'snapshot record DTC echo')
    require(uds[6] == int(SNAPSHOT_RECORD_PAIR['snapshotRecordNumberHex'],16), 'snapshot record number echo')
    outside = uds[5] & ~status_availability
    return dict(raw24Hex=uds[2:5].hex().upper(),statusHex=f'{uds[5]:02X}',
        statusAvailabilityHex=f'{status_availability:02X}',unavailableStatusBitsHex=f'{outside:02X}',
        snapshotRecordNumberHex=f'{uds[6]:02X}',numberOfIdentifiers=uds[7],
        rawIdentifierDataHex=uds[8:].hex().upper(),rawIdentifierDataByteCount=len(uds)-8,
        integrityPass=outside == 0,decodedDids=[],engineeringValues=[],
        meaning='Echoed fixed snapshot record header plus opaque DID/value bytes; no DID layout or physical values inferred')


def parse_snapshot_record_empty(uds, status_availability):
    """Exact positive04 DTC/status prefix with no snapshot-record fields."""
    require(0 <= status_availability <= 255, 'empty snapshot response status availability')
    require(len(uds) == 6 and uds[:2] == b'\x59\x04', 'empty snapshot response shape')
    require(uds[2:5] == bytes.fromhex(SNAPSHOT_RECORD_PAIR['raw24Hex']), 'empty snapshot response DTC echo')
    outside = uds[5] & ~status_availability
    return dict(raw24Hex=uds[2:5].hex().upper(),statusHex=f'{uds[5]:02X}',
        statusAvailabilityHex=f'{status_availability:02X}',unavailableStatusBitsHex=f'{outside:02X}',
        snapshotRecordsReturned=0,recordNumberPresent=False,numberOfIdentifiersPresent=False,
        integrityPass=outside == 0,decodedDids=[],engineeringValues=[],
        meaning='Positive DTC/status prefix with no snapshot-record number, identifier count or value bytes returned')


def parse_snapshot_probe(text, plan_document, *, allow_partial=False, snapshot_record=False):
    """Read one fixed snapshot-index plan run, validating normalized FRAME data and lifecycle."""
    lines = text.splitlines()
    require(len(lines) >= 4 and lines[0] == 'REGISTERED', 'REGISTERED lifecycle')
    kind, start = pairs(lines[1])
    require(kind == 'START' and start.get('plan') in plan_document['plans'], 'START/plan')
    plan = plan_document['plans'][start['plan']]
    targets = plan['targets']
    requests = SNAPSHOT_RECORD_REQUESTS if snapshot_record else SNAPSHOT_REQUESTS
    if snapshot_record:
        require(start['plan'] == 'bms-snapshot-record' and plan.get('readKind') == 'snapshot-record', 'fixed snapshot-record plan')
        require(len(targets) == 1 and targets[0]['name'] == 'BMS' and targets[0]['requestIdHex'] == '7E7'
                and targets[0]['responseIdHex'] == '7EF' and targets[0]['expectedSoftwareCode'] == '4A520011E00D00047B',
                'fixed snapshot-record BMS identity/route')
        require(targets[0].get('expectedFormatIdHex') == '00'
                and targets[0].get('requiredSnapshotPair') == dict(rawDtcHex='1A3A22',recordNumberHex='01'),
                'fixed snapshot-record format/pair metadata')
        require(plan['maxRequests'] == 4 and plan['overallSoftMs'] == 25000
                and plan['overallHardMs'] == 27000, 'fixed snapshot-record bounds')
    require(len({t['requestIdHex'] for t in targets}) == len(targets), 'duplicate plan target')
    require(int(start['targets']) == len(targets) and int(start['max_requests']) == plan['maxRequests'], 'START bounds')
    require(start['silence_ms'] == '2000' and start['request_ms'] == '4000', 'START request/silence bounds')
    require(int(start['overall_ms']) == plan['overallSoftMs'], 'START overall bound')
    expected = {t['requestIdHex']: t for t in targets}
    for t in targets:
        require(t['requests'] == requests, 'fixed request list')
        require(int(t['responseIdHex'], 16) == int(t['requestIdHex'], 16)+8 <= 0x7FF, 'plan response route')
        require(len(hex_bytes(t['expectedSoftwareCode'])) == 9 and any(hex_bytes(t['expectedSoftwareCode'])), 'expected software code')
    events = []
    by_seq = {}
    target_ends = {}
    active = None
    previous = None
    end = None
    unregistered = False
    rejected_frames = []
    started_targets = []
    for number, line in enumerate(lines[2:], 3):
        kind, fields = pairs(line)
        require(end is None, 'data after END')
        if kind == 'UNREGISTERED':
            require(not fields and not unregistered and (active is None or allow_partial), 'UNREGISTERED lifecycle')
            unregistered = True
        elif kind == 'REQUEST':
            require(not unregistered, 'REQUEST after unregister')
            require(active is None, 'overlapping REQUEST')
            seq = int(fields['seq'])
            target = fields['target']
            require(seq == len(events)+1 and len(events) < plan['maxRequests'], 'REQUEST sequence/bound')
            require(target in expected and target not in target_ends, 'REQUEST target')
            if target not in started_targets:
                require(started_targets == [t['requestIdHex'] for t in targets[:len(started_targets)]], 'target order')
                require(target == targets[len(started_targets)]['requestIdHex'], 'target order')
                if started_targets:
                    require(started_targets[-1] in target_ends, 'target boundary missing')
                started_targets.append(target)
            prior = [r for r in events if r['target'] == target]
            require(len(prior) < len(requests) and fields['request'] == requests[len(prior)] and fields['ecu'] == expected[target]['name'], 'REQUEST fixed order/name')
            if prior:
                expected_state = ['IDENTITY_MATCH','FORMAT_READ','SNAPSHOT_INDEX_READ'][len(prior)-1]
                require(prior[-1]['stepEnd']['state'] == expected_state, 'REQUEST continued after failed prerequisite')
            if snapshot_record and len(prior) >= 2:
                format_read = parse_count(hex_bytes(prior[1]['response']['uds']))
                require(format_read['formatId'] == 0, 'snapshot record requires exact format00')
            if snapshot_record and len(prior) == 3:
                index = parse_snapshot_index(hex_bytes(prior[2]['response']['uds']))
                require(SNAPSHOT_RECORD_PAIR in index['records'], 'snapshot record pair absent from observed index')
            request = hex_bytes(fields['request'])
            require(hex_bytes(fields['raw']) == bytes([10])+int(target,16).to_bytes(2,'big')+bytes([len(request)])+request, 'REQUEST wire envelope')
            active = dict(seq=seq, target=target, request=fields['request'], requestNanos=int(fields['t_ns']),
                          line=number, frames=[], response=None, stepEnd=None, duplicates=[],
                          transportResult=None, projections={})
            events.append(active)
            by_seq[seq] = active
        elif kind == 'TRANSPORT_RESULT':
            require(active is not None and int(fields['seq']) == active['seq'] and fields['result'] == '0', 'TRANSPORT_RESULT')
            require(active['transportResult'] is None, 'repeated TRANSPORT_RESULT')
            active['transportResult'] = dict(fields, line=number)
        elif kind == 'REJECTED_FRAME':
            require(allow_partial, 'REJECTED_FRAME requires explicit partial mode')
            seq = int(fields['seq'])
            require(0 <= seq <= len(events) and (fields['target'] in expected or fields['target'] == 'NONE'), 'REJECTED_FRAME context')
            raw_size, logged = int(fields['raw_bytes']), int(fields['logged_bytes'])
            require(fields['truncated'] in ['true','false'] and 0 <= logged <= 1024, 'REJECTED_FRAME bounds')
            if fields['raw'] == 'NULL':
                require(raw_size == -1 and logged == 0, 'REJECTED_FRAME null shape')
            elif not fields['raw']:
                require(raw_size == logged == 0 and fields['truncated'] == 'false', 'REJECTED_FRAME empty shape')
            else:
                require(len(hex_bytes(fields['raw'])) == logged and raw_size >= logged, 'REJECTED_FRAME raw length')
                require((raw_size > logged) == (fields['truncated'] == 'true'), 'REJECTED_FRAME truncation')
            rejected_frames.append(dict(fields,line=number,acceptedAsResponse=False))
        elif kind in ['FRAME', 'DUPLICATE']:
            require(not unregistered, 'callback after unregister')
            seq = int(fields['seq'])
            require(seq in by_seq and fields['target'] == by_seq[seq]['target'], 'FRAME sequence/target')
            origin = by_seq[seq]
            require(int(fields['t_ns']) >= origin['requestNanos'], 'FRAME time')
            hex_bytes(fields['raw'])
            if active is not None:
                active['callbackCount'] = active.get('callbackCount',0)+1
                require(active['callbackCount'] <= 64, 'callback bound')
            if kind == 'FRAME':
                require(origin is active, 'FRAME outside current exchange')
                require(int(fields['t_ns']) < origin['requestNanos'] + 4_000_000_000, 'FRAME outside original request deadline')
                origin['frames'].append(dict(fields, line=number))
            else:
                require(origin is active or origin is previous, 'DUPLICATE outside current/previous exchange')
                require(fields['raw'] in [r['raw'] for r in origin['frames']], 'altered/unknown DUPLICATE')
                origin['duplicates'].append(dict(fields, line=number))
        elif kind == 'PENDING':
            require(active is not None and int(fields['seq']) == active['seq'] and fields['target'] == active['target'], 'PENDING context')
            terminal, pending = assemble(active['frames'],active['request'],int(expected[active['target']]['responseIdHex'],16))
            require(terminal is None and pending > 0 and fields['uds'] == '7F'+active['request'][:2]+'78', 'PENDING response')
            require(pending == active.get('pendingLines',0)+1, 'PENDING count')
            active['pendingLines'] = pending
        elif kind == 'RESPONSE':
            require(active is not None and int(fields['seq']) == active['seq'] and fields['target'] == active['target'] and fields['request'] == active['request'], 'RESPONSE context')
            require(active['response'] is None, 'repeated RESPONSE')
            terminal, pending = assemble(active['frames'], active['request'], int(expected[active['target']]['responseIdHex'],16))
            require(terminal is not None and terminal['uds'] == fields['uds'] and terminal['frames'] == int(fields['frames']), 'RESPONSE differs from raw FRAME assembly')
            require(0 <= int(fields['latency_ms']) <= 4000 and int(fields['t_ns']) >= active['requestNanos'], 'RESPONSE time/bound')
            require(int(fields['t_ns']) == int(active['frames'][-1]['t_ns']) and int(fields['latency_ms']) == (int(fields['t_ns'])-active['requestNanos'])//1_000_000, 'RESPONSE timestamp/latency differs from FRAME')
            active['response'] = dict(fields, line=number)
            active['pendingResponses'] = pending
        elif kind in ['FORMAT', 'SNAPSHOT_INDEX', 'SNAPSHOT_RECORD', 'SNAPSHOT_RECORD_EMPTY']:
            require(active is not None and int(fields['seq']) == active['seq'] and fields['target'] == active['target'] and active['response'] is not None, 'projection context')
            require(kind not in active['projections'], 'repeated projection')
            raw = hex_bytes(active['response']['uds'])
            if kind == 'FORMAT':
                require(active['request'] == '190109', 'FORMAT request')
                parsed = parse_count(raw)
                require(fields['format'] == f"{parsed['formatId']:02X}" and fields['status_available'] == parsed['statusAvailabilityHex'] and int(fields['mask09_count']) == parsed['memoryCountForMask09'], 'FORMAT differs from wire')
            elif kind == 'SNAPSHOT_INDEX':
                require(active['request'] == '1903', 'SNAPSHOT_INDEX request')
                parsed = parse_snapshot_index(raw)
                previous_count = next(r for r in reversed(events[:-1]) if r['target'] == active['target'] and r['request'] == '190109')
                fmt = parse_count(hex_bytes(previous_count['response']['uds']))
                require(parsed['integrityPass'] and fields['format'] == f"{fmt['formatId']:02X}" and 'status_available' not in fields and int(fields['record_count']) == parsed['recordCount'], 'SNAPSHOT_INDEX differs from wire')
                require(fields['active_fault_count'] == 'not_measured', 'snapshot index is not active fault count')
            else:
                require(snapshot_record and active['request'] == SNAPSHOT_RECORD_REQUEST, kind+' request')
                previous_count = next(r for r in reversed(events[:-1]) if r['target'] == active['target'] and r['request'] == '190109')
                fmt = parse_count(hex_bytes(previous_count['response']['uds']))
                require(fmt['formatId'] == 0, 'snapshot record requires exact format00')
                if kind == 'SNAPSHOT_RECORD_EMPTY':
                    require(set(fields) == {'seq','target','format','dtc','status'}, 'SNAPSHOT_RECORD_EMPTY fields')
                    parsed = parse_snapshot_record_empty(raw,int(fmt['statusAvailabilityHex'],16))
                    require(parsed['integrityPass'] and fields['format'] == '00'
                            and fields['dtc'] == parsed['raw24Hex'] and fields['status'] == parsed['statusHex'], 'SNAPSHOT_RECORD_EMPTY differs from wire')
                else:
                    require(set(fields) == {'seq','target','format','dtc','status','record','did_count','payload_bytes'}, 'SNAPSHOT_RECORD fields')
                    parsed = parse_snapshot_record(raw,int(fmt['statusAvailabilityHex'],16))
                    require(parsed['integrityPass'] and fields['format'] == '00'
                            and fields['dtc'] == parsed['raw24Hex'] and fields['status'] == parsed['statusHex']
                            and fields['record'] == parsed['snapshotRecordNumberHex']
                            and int(fields['did_count']) == parsed['numberOfIdentifiers']
                            and int(fields['payload_bytes']) == parsed['rawIdentifierDataByteCount'], 'SNAPSHOT_RECORD differs from wire')
            active['projections'][kind] = dict(fields, line=number)
        elif kind == 'STEP_END':
            require(active is not None and int(fields['seq']) == active['seq'], 'STEP_END context')
            terminal, pending = assemble(active['frames'], active['request'], int(expected[active['target']]['responseIdHex'],16))
            if active['response'] is None:
                require(terminal is None or terminal['uds'] is None, 'terminal UDS without RESPONSE')
                allowed = 'REQUEST_TIMEOUT' if terminal is None else {3:'NO_RESPONSE',4:'GATEWAY_TIMEOUT'}.get(terminal['gatewayResult'])
                require(fields['state'] == allowed, 'STEP_END no-response state')
            else:
                raw = hex_bytes(active['response']['uds'])
                if raw[:1] == b'\x7F':
                    require(fields['state'] == 'UDS_NEGATIVE_' + f'{raw[2]:02X}', 'STEP_END NRC state')
                else:
                    expected_state = dict(zip(requests,['IDENTITY_MATCH','FORMAT_READ','SNAPSHOT_INDEX_READ','SNAPSHOT_RECORD_READ']))[active['request']]
                    if snapshot_record and active['request'] == '190109' and parse_count(raw)['formatId'] != 0:
                        expected_state = 'FORMAT_MISMATCH'
                    if snapshot_record and active['request'] == '1903' and SNAPSHOT_RECORD_PAIR not in parse_snapshot_index(raw)['records']:
                        expected_state = 'SNAPSHOT_PAIR_UNAVAILABLE'
                    if snapshot_record and active['request'] == SNAPSHOT_RECORD_REQUEST and len(raw) == 6:
                        expected_state = 'SNAPSHOT_RECORD_EMPTY'
                    require(fields['state'] == expected_state, 'STEP_END positive state')
                    if active['request'] == '22F194':
                        require(active['response']['uds'] == '62F194'+expected[active['target']]['expectedSoftwareCode'], 'software identity mismatch')
                    else:
                        projection = {'190109':'FORMAT','1903':'SNAPSHOT_INDEX',SNAPSHOT_RECORD_REQUEST:'SNAPSHOT_RECORD'}[active['request']]
                        if snapshot_record and active['request'] == SNAPSHOT_RECORD_REQUEST and len(raw) == 6:
                            projection = 'SNAPSHOT_RECORD_EMPTY'
                        require(projection in active['projections'], 'required projection absent')
            require(active['transportResult'] is not None, 'TRANSPORT_RESULT absent')
            require(pending == active.get('pendingLines',0), 'PENDING projection absent')
            active['pendingResponses'] = pending
            active['stepEnd'] = dict(fields, line=number)
            previous, active = active, None
        elif kind == 'TARGET_END':
            target = fields['target']
            require(active is None and target in started_targets and target not in target_ends, 'TARGET_END context')
            last = next(r for r in reversed(events) if r['target'] == target)
            require(last['stepEnd']['state'] == fields['state'] and fields['state'] not in ['IDENTITY_MATCH','FORMAT_READ'], 'TARGET_END state')
            if snapshot_record:
                require(fields['state'] != 'SNAPSHOT_INDEX_READ', 'fixed snapshot record request missing after successful index gate')
            target_ends[target] = dict(fields, line=number)
        elif kind == 'END':
            require(int(fields['requests']) == len(events), 'END request count')
            require(fields['cleanup'] in ['true','false'] and int(fields['queued']) >= 0, 'END cleanup fields')
            require(unregistered and int(fields['completed']) == sum(r['stepEnd'] is not None for r in events) and int(fields['targets']) == len(target_ends), 'END actual completed prefix')
            observed = observed_callback_count(events,rejected_frames)
            require(observed <= int(fields['callbacks']) <= 1024, 'END callback count/bound')
            if fields['reason'] == 'PLAN_DONE':
                require(active is None and fields['cleanup'] == 'true' and int(fields['completed']) == len(events) and int(fields['targets']) == len(targets) and fields['queued'] == '0' and int(fields['callbacks']) == observed and not rejected_frames, 'END lifecycle/coverage')
            else:
                require(allow_partial, 'aborted plan requires explicit partial mode')
            end = dict(fields, line=number)
        else:
            raise ValueError('unknown protocol line: ' + kind)
    complete = end is not None and end['reason'] == 'PLAN_DONE'
    require(allow_partial or complete, 'incomplete END/target coverage')
    if complete:
        require(set(target_ends) == set(expected), 'incomplete END/target coverage')
    for row in events:
        row['exchangeComplete'] = row['stepEnd'] is not None
    completion = dict(complete=complete, explicitPartialMode=allow_partial,
        endPresent=end is not None, unregisteredObserved=unregistered,
        abortedOrIncomplete=not complete,
        uncompletedSequences=[r['seq'] for r in events if not r['exchangeComplete']],
        targetsWithoutEnd=[t['requestIdHex'] for t in targets if t['requestIdHex'] not in target_ends],
        targetsNotStarted=[t['requestIdHex'] for t in targets if t['requestIdHex'] not in started_targets],
        callbackAccountingComplete=(end is not None and int(end['callbacks']) == observed_callback_count(events,rejected_frames)))
    return dict(plan=plan, start=start, end=end, events=events, targetEnds=target_ends,
                completion=completion,rejectedFrames=rejected_frames)


def decode(text, plan_document, *, allow_partial=False):
    capture = parse_snapshot_probe(text,plan_document,allow_partial=allow_partial)
    modules = []
    for target in capture['plan']['targets']:
        route = target['requestIdHex']
        rows = [r for r in capture['events'] if r['target'] == route]
        if not rows:
            continue
        responses = {r['request']: r['response']['uds'] for r in rows
                     if r['response'] is not None and r['exchangeComplete']}
        identity = responses.get('22F194')
        matched = identity == '62F194'+target['expectedSoftwareCode']
        actual = identity[6:] if identity and identity.startswith('62F194') and len(identity) == 24 else None
        count = None
        snapshot = None
        negatives = []
        for request, response in responses.items():
            data = hex_bytes(response)
            if data[:1] == b'\x7F':
                require(len(data) == 3 and data[1] == int(request[:2],16), 'negative response shape/service')
                negatives.append(dict(request=request,responseHex=response,nrcHex=f'{data[2]:02X}',
                                      unsupportedServiceOrSubfunction=data[2] in [0x11,0x12]))
            elif request == '22F194':
                require(len(data) == 12 and data[:3] == b'\x62\xF1\x94','F194 response shape')
            elif request == '190109':
                count = parse_count(data)
            elif request == '1903':
                snapshot = parse_snapshot_index(data)
        if snapshot is not None:
            for record in snapshot['records']:
                record['standardDisplay'] = standard_display(record['raw24Hex']) if matched and count is not None and count['formatId'] == 0 else None
        modules.append(dict(name=target['name'],requestIdHex=route,responseIdHex=target['responseIdHex'],
            expectedSoftwareCode=target['expectedSoftwareCode'],observedSoftwareCode=actual,
            exactSoftwareIdentityMatched=matched,formatRead=count,snapshotIndex=snapshot,
            negativeResponses=negatives,targetCompleted=route in capture['targetEnds'],
            terminalState=capture['targetEnds'].get(route),
            rejectedFrames=[r for r in capture['rejectedFrames'] if r['target'] == route],wireEvents=rows))
    return dict(schema='denza-lab.snapshot-index-offline.v1',plan=capture['start']['plan'],
        meaning='returned snapshot-record identifiers, not DTC status bytes or a supported-code list',
        modules=modules,wireRequestCount=len(capture['events']),probeEnd=capture['end'],
        completion=capture['completion'],rejectedFrames=capture['rejectedFrames'],
        discoveryConstraints=DISCOVERY_CONSTRAINTS,qualifiedFactoryDefinitions=[],qualifiedWarningLinks=[],
        decodedSnapshotValues=[],discoveredExtendedDataRecordNumbers=[],automaticFollowupRequests=[],vehicleOperations=0,
        limitations=['Empty5903 means the response returned no snapshot-index tuples; it does not establish ECU health or unsupported service.',
                     'One raw DTC can have multiple snapshot record IDs. The record ID is not a DTC status, a DID or a physical value.',
                     'Format and mask09 count come from190109; neither is present in5903. The mask09 count is not the snapshot tuple count.',
                     'Only exact-F194-bound format00 receives standard notation. Format01 remains raw.',
                     '1903 supplies no extended-data record IDs and no ECU-specific1904/1906 schemas. No followup sends are implemented.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('probe',type=Path)
    parser.add_argument('--plan',type=Path,required=True)
    parser.add_argument('--allow-partial',action='store_true')
    parser.add_argument('--output',type=Path)
    args = parser.parse_args()
    result = decode(args.probe.read_text(),json.loads(args.plan.read_text()),allow_partial=args.allow_partial)
    result['sources'] = [dict(path=str(p.resolve()),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in [args.probe,args.plan]]
    content = json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.output:
        args.output.write_text(content)
    else:
        print(content,end='')


if __name__=='__main__':
    main()
