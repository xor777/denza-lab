#!/usr/bin/env python3
"""Offline supported-DTC registry decoder and unqualified catalog crosswalk.

Usage: python3 decode_supported_dtc.py CAPTURE/probe.txt --plan BUILD/plan.json
       [--index oem-dtc-public-index-20261020.json] [--output result.json]

The fixed probe emits REQUEST/FRAME/RESPONSE records. Responses are independently
reassembled from declared raw fragments, never trusted as an alternate source.
19 0A is a supported-code registry, not a read of active/historical fault memory.
Its byte2 is status availability, not format. Format comes only from that same
target's separate 19 01 09 response, following an exact F194 software identity.
Only format00 receives standard J2012 notation and exact catalog-heading matches.
Format01 and other formats stay raw; catalog matches never qualify OEM meanings.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INDEX = Path(__file__).with_name('oem-dtc-public-index-20261020.json')
REQUESTS = ['22F194', '190109', '190A']
# A retained role/identity crosswalk, not manual/software applicability. Both rear
# sections remain candidates, matching current OemDtcIndex's conservative rule.
SECTIONS = {
    ('7E0', '32440011E004000000'): ['engine_ecm'],
    ('7E7', '4A520011E00D00047B'): ['battery_management'],
    ('7E3', '48370011E075000000'): ['front_drive'],
    ('7E6', '48370011E0EA000000'): ['generator'],
    ('744', '48370011E086001100'): ['rear_left', 'rear_right'],
    ('745', '48370011E088001100'): ['rear_right', 'rear_left'],
}
STATUS_BITS = ['testFailed', 'testFailedThisOperationCycle', 'pendingDTC',
               'confirmedDTC', 'testNotCompletedSinceLastClear',
               'testFailedSinceLastClear', 'testNotCompletedThisOperationCycle',
               'warningIndicatorRequested']

DISCOVERY_CONSTRAINTS = {
    'implementationSendsRequests': False,
    'supportedRegistryDoesNotSupplySnapshotOrExtendedRecordNumbers': True,
    'snapshot03': 'Record identification is separate discovery; no such request is implemented here.',
    'snapshot04': 'Needs an exact raw DTC and snapshot record number plus ECU/software-specific DID/length/unit schema.',
    'extended06': 'Needs an exact raw DTC and extended record number plus ECU/software-specific layout.',
    'nativeBoundary': 'The traced stock snapshot/extended tasks copy DTC/status and opaque following bytes; they do not supply target record schemas or OEM meanings.',
    'retainedSource': 'research/vehicle-diagnostics/dtc-source-followup-20261021.json',
    'primarySnapshotDTO': 'captures/firmware/ivi-34.1.33.2605218.1/jadx/OTAUpdatePlatformizedUI/sources/ts/car/uds/sdk/DTCSnapshotEntity.java',
    'primaryExtendedDTO': 'captures/firmware/ivi-34.1.33.2605218.1/jadx/OTAUpdatePlatformizedUI/sources/ts/car/uds/sdk/DTCExtendedEntity.java',
    'noRecordNumberGuessing': True,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def hex_bytes(value, label='hex'):
    require(isinstance(value, str) and bool(re.fullmatch(r'(?:[0-9A-Fa-f]{2})+', value)), label)
    return bytes.fromhex(value)


def parse_count(uds):
    require(len(uds) == 6 and uds[:2] == b'\x59\x01', '19 01 count shape')
    return dict(statusAvailabilityHex=f'{uds[2]:02X}', formatId=uds[3],
                memoryCountForMask09=int.from_bytes(uds[4:6], 'big'),
                countScope='DTCs matching request mask09; not the supported-registry length')


def parse_supported(uds):
    require(len(uds) >= 3 and uds[:2] == b'\x59\x0A' and (len(uds)-3) % 4 == 0, '19 0A registry shape')
    availability = uds[2]
    records = []
    for offset in range(3, len(uds), 4):
        raw, status = uds[offset:offset+3], uds[offset+3]
        records.append(dict(raw24Hex=raw.hex().upper(), statusHex=f'{status:02X}',
            statusBitStates={name: ('unsupported' if not availability & (1 << bit)
                                   else 'set' if status & (1 << bit) else 'clear')
                             for bit, name in enumerate(STATUS_BITS)},
            unsupportedStatusBitsHex=f'{status & ~availability:02X}'))
    duplicates = sorted(raw for raw, n in Counter(r['raw24Hex'] for r in records).items() if n > 1)
    outside = [i for i, r in enumerate(records) if r['unsupportedStatusBitsHex'] != '00']
    return dict(statusAvailabilityHex=f'{availability:02X}', records=records,
                recordCount=len(records), duplicateRawCodes=duplicates,
                recordsWithUnsupportedStatusBits=outside,
                # Duplicates are a reported property of the ordered registry,
                # not a malformed transport response. Never merge their statuses.
                integrityPass=not outside,
                meaning='supported DTC registry; not an active/history fault list')


def standard_display(raw24):
    """General notation only. The caller must first gate on format00 and identity."""
    raw = hex_bytes(raw24, 'raw DTC')
    require(len(raw) == 3, 'raw DTC length')
    value = int.from_bytes(raw[:2], 'big')
    base = 'PCBU'[value >> 14] + str((value >> 12) & 3) + f'{value & 0xFFF:03X}'
    return base + f'{raw[2]:02X}'


def bare_title(code, title):
    zero_width = '\u200b\u200c\u200d\u2060\ufeff'
    title = title.strip().strip(zero_width).strip()
    if title.startswith(code):
        title = title[len(code):]
    return title.strip().strip(zero_width).strip()


def crosswalk(registry, *, identity_matches, format_id, sections, index):
    """Return intersections and both complements; preserve every original row."""
    selected = []
    seen = set()
    for row in index['records']:
        require(isinstance(row, list) and len(row) == 4 and all(isinstance(v, str) for v in row), 'catalog row shape')
        key = tuple(row[:3])
        require(key not in seen, 'duplicate catalog locator')
        seen.add(key)
        require(re.fullmatch(r'[PCBU][0-3][0-9A-F]{5}', row[1]) is not None, 'catalog code notation')
        if row[0] in sections:
            selected.append(dict(subsystem=row[0], displayCode=row[1], outlineId=row[2], publicTitleZh=row[3]))
    require(index.get('recordCount', len(index['records'])) == len(index['records']), 'catalog count')
    selected.sort(key=lambda row: sections.index(row['subsystem']))
    skip = ('identity_not_matched' if not identity_matches else
            'format_not_read' if format_id is None else
            'supported_list_not_read' if registry is None else
            'supported_list_integrity_failed' if not registry['integrityPass'] else
            'format01_raw_only' if format_id == 1 else
            'format_raw_only' if format_id != 0 else
            'no_retained_section_binding' if not sections else None)
    out = dict(state=skip or 'compared_unqualified_headings', candidateSections=sections,
        catalogHeadingRows=len(selected), catalogDistinctCodes=len({r['displayCode'] for r in selected}),
        headingMatches=None, supportedCodesWithoutHeading=None, headingsNotInSupportedList=None,
        conflictingMatchedCodes=None, qualifiedFactoryMeaning=False, warningLinks=[])
    if skip:
        return out
    records = registry['records']
    codes = {standard_display(r['raw24Hex']) for r in records}
    matching = [r for r in selected if r['displayCode'] in codes]
    matches = []
    for i, record in enumerate(records):
        code = standard_display(record['raw24Hex'])
        rows = [r for r in matching if r['displayCode'] == code]
        conflict = any(len({bare_title(code, r['publicTitleZh']) for r in rows if r['subsystem'] == s}) > 1 for s in sections)
        rear = sections and sections[0] in ['rear_left','rear_right']
        matches.append(dict(recordIndex=i, raw24Hex=record['raw24Hex'], statusHex=record['statusHex'],
                            standardDisplay=code, headings=rows, conflictingTitlesInSameSection=conflict,
                            ownSideHeadings=[r for r in rows if r['subsystem'] == sections[0]] if rear else None,
                            crossSideHeadings=[r for r in rows if r['subsystem'] != sections[0]] if rear else None))
    out.update(headingMatches=matches,
        matchedDistinctSupportedCodes=len({r['standardDisplay'] for r in matches if r['headings']}),
        matchedHeadingRows=len(matching),
        supportedCodesWithoutHeading=[dict(raw24Hex=r['raw24Hex'], standardDisplay=r['standardDisplay']) for r in matches if not r['headings']],
        headingsNotInSupportedList=[r for r in selected if r['displayCode'] not in codes],
        conflictingMatchedCodes=sorted({r['standardDisplay'] for r in matches if r['conflictingTitlesInSameSection']}))
    return out


def assemble(frames, request, response_id):
    """Reassemble declared fragments with the retained transport bounds."""
    request = hex_bytes(request, 'request')
    current = bytearray()
    count = 0
    next_index = 1
    terminal = None
    pending = 0
    known = set()
    require(len(frames) <= 64, 'too many FRAME callbacks')
    for row in frames:
        raw = hex_bytes(row['raw'], 'FRAME raw')
        require(6 <= len(raw) <= 1024, 'FRAME buffer length')
        require(raw not in known, 'FRAME repeated without DUPLICATE marker')
        known.add(raw)
        require(raw[0] == 10 and int.from_bytes(raw[1:3], 'big') == response_id, 'foreign FRAME owner/address')
        length, total, number = raw[3:6]
        offset = 7 if number == 1 else 6
        require(1 <= total <= 16 and 1 <= number <= total, 'fragment number/count')
        require(len(raw) == offset + length, 'FRAME must contain declared bytes only')
        require(terminal is None, 'FRAME after terminal answer')
        if number == 1:
            require(next_index == 1, 'first fragment inside answer')
            result = raw[6]
            require(result in [1, 2, 3, 4, 5], 'gateway result')
            if result in [3, 4, 5]:
                terminal = dict(uds=None, frames=1, gatewayResult=result)
                continue
            require(length > 0, 'empty answer')
            count = total
        else:
            require(total == count and number == next_index, 'fragment order/count changed')
        current.extend(raw[offset:])
        require(len(current) <= 4095, 'assembled payload bound')
        next_index += 1
        if number == total:
            uds = bytes(current)
            require(uds[0] == request[0]+0x40 or (len(uds) == 3 and uds[:2] == bytes([0x7F, request[0]])), 'response SID')
            if uds == bytes([0x7F, request[0], 0x78]):
                pending += 1
            else:
                terminal = dict(uds=uds.hex().upper(), frames=total, gatewayResult=result)
            current.clear()
            count, next_index = 0, 1
    require(next_index == 1, 'incomplete fragmented answer')
    return terminal, pending


def pairs(line):
    words = line.split()
    require(bool(words), 'empty protocol line')
    fields = {}
    for word in words[1:]:
        require('=' in word, 'protocol field without =')
        key, value = word.split('=', 1)
        require(key not in fields and (value or (words[0] == 'REJECTED_FRAME' and key == 'raw')), 'duplicate/empty protocol field')
        fields[key] = value
    return words[0], fields


def observed_callback_count(events, rejected):
    normal = [(row['seq'], frame) for row in events for frame in row['frames']+row['duplicates']]
    # A malformed UDS payload can be logged as FRAME and then again as the
    # original source buffer in REJECTED_FRAME. That is one callback, two views.
    extra = sum(not any(seq == int(r['seq']) and f['t_ns'] == r['t_ns']
                        and r['raw'].startswith(f['raw']) for seq, f in normal)
                for r in rejected)
    return len(normal)+extra


def parse_probe(text, plan_document, *, allow_partial=False):
    """Read one fixed-plan run, validating normalized FRAME data and lifecycle."""
    lines = text.splitlines()
    require(len(lines) >= 4 and lines[0] == 'REGISTERED', 'REGISTERED lifecycle')
    kind, start = pairs(lines[1])
    require(kind == 'START' and start.get('plan') in plan_document['plans'], 'START/plan')
    plan = plan_document['plans'][start['plan']]
    targets = plan['targets']
    require(len({t['requestIdHex'] for t in targets}) == len(targets), 'duplicate plan target')
    require(int(start['targets']) == len(targets) and int(start['max_requests']) == plan['maxRequests'], 'START bounds')
    require(start['silence_ms'] == '2000' and start['request_ms'] == '4000', 'START request/silence bounds')
    require(int(start['overall_ms']) == plan['overallSoftMs'], 'START overall bound')
    expected = {t['requestIdHex']: t for t in targets}
    for t in targets:
        require(t['requests'] == REQUESTS, 'fixed request list')
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
            require(len(prior) < len(REQUESTS) and fields['request'] == REQUESTS[len(prior)] and fields['ecu'] == expected[target]['name'], 'REQUEST fixed order/name')
            if prior:
                expected_state = 'IDENTITY_MATCH' if len(prior) == 1 else 'FORMAT_READ'
                require(prior[-1]['stepEnd']['state'] == expected_state, 'REQUEST continued after failed prerequisite')
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
        elif kind in ['FORMAT', 'SUPPORTED']:
            require(active is not None and int(fields['seq']) == active['seq'] and fields['target'] == active['target'] and active['response'] is not None, 'projection context')
            require(kind not in active['projections'], 'repeated projection')
            raw = hex_bytes(active['response']['uds'])
            if kind == 'FORMAT':
                require(active['request'] == '190109', 'FORMAT request')
                parsed = parse_count(raw)
                require(fields['format'] == f"{parsed['formatId']:02X}" and fields['status_available'] == parsed['statusAvailabilityHex'] and int(fields['mask09_count']) == parsed['memoryCountForMask09'], 'FORMAT differs from wire')
            else:
                require(active['request'] == '190A', 'SUPPORTED request')
                parsed = parse_supported(raw)
                previous_count = next(r for r in reversed(events[:-1]) if r['target'] == active['target'] and r['request'] == '190109')
                fmt = parse_count(hex_bytes(previous_count['response']['uds']))
                require(parsed['integrityPass'] and fields['format'] == f"{fmt['formatId']:02X}" and fields['status_available'] == parsed['statusAvailabilityHex'] == fmt['statusAvailabilityHex'] and int(fields['supported_count']) == parsed['recordCount'], 'SUPPORTED differs from wire')
                require(fields['active_fault_count'] == 'not_measured', 'supported registry is not active fault count')
                unique_count = len({r['raw24Hex'] for r in parsed['records']})
                if parsed['duplicateRawCodes'] or 'unique_code_count' in fields or 'duplicate_record_count' in fields:
                    require(fields.get('unique_code_count') == str(unique_count)
                            and fields.get('duplicate_record_count') == str(parsed['recordCount']-unique_count),
                            'SUPPORTED duplicate counts differ from wire')
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
                    expected_state = dict(zip(REQUESTS,['IDENTITY_MATCH','FORMAT_READ','SUPPORTED_READ']))[active['request']]
                    require(fields['state'] == expected_state, 'STEP_END positive state')
                    if active['request'] == '22F194':
                        require(active['response']['uds'] == '62F194'+expected[active['target']]['expectedSoftwareCode'], 'software identity mismatch')
                    else:
                        require(('FORMAT' if active['request'] == '190109' else 'SUPPORTED') in active['projections'], 'required projection absent')
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


def decode(text, plan_document, index, *, allow_partial=False):
    capture = parse_probe(text, plan_document,allow_partial=allow_partial)
    modules = []
    for target in capture['plan']['targets']:
        route = target['requestIdHex']
        rows = [r for r in capture['events'] if r['target'] == route]
        if not rows:
            continue
        responses = {r['request']: r['response']['uds'] for r in rows
                     if r['response'] is not None and r['exchangeComplete']}
        identity = responses.get('22F194')
        identity_matches = identity == '62F194' + target['expectedSoftwareCode']
        actual_code = identity[6:] if identity and identity.startswith('62F194') and len(identity) == 24 else None
        count = None
        supported = None
        negative = []
        for request, response in responses.items():
            data = hex_bytes(response)
            if data[:1] == b'\x7F':
                require(len(data) == 3 and data[1] == int(request[:2],16), 'negative response shape/service')
                negative.append(dict(request=request,responseHex=response,nrcHex=f'{data[2]:02X}',
                                     unsupportedServiceOrSubfunction=data[2] in [0x11,0x12]))
            elif request == '190109':
                count = parse_count(data)
            elif request == '190A':
                supported = parse_supported(data)
            elif request == '22F194':
                require(len(data) == 12 and data[:3] == b'\x62\xF1\x94', 'F194 response shape')
        if supported is not None and count is not None and supported['statusAvailabilityHex'] != count['statusAvailabilityHex']:
            supported['integrityPass'] = False
            supported['availabilityChangedFromCount'] = True
        sections = SECTIONS.get((route,target['expectedSoftwareCode']), [])
        coverage = crosswalk(supported, identity_matches=identity_matches,
                             format_id=None if count is None else count['formatId'], sections=sections, index=index)
        modules.append(dict(name=target['name'],requestIdHex=route,responseIdHex=target['responseIdHex'],
            expectedSoftwareCode=target['expectedSoftwareCode'],observedSoftwareCode=actual_code,
            exactSoftwareIdentityMatched=identity_matches,formatRead=count,supportedRegistry=supported,
            negativeResponses=negative,catalogComparison=coverage,
            terminalState=capture['targetEnds'].get(route),
            targetCompleted=route in capture['targetEnds'],
            rejectedFrames=[r for r in capture['rejectedFrames'] if r['target'] == route],wireEvents=rows))
    return dict(schema='denza-lab.supported-dtc-offline.v1',plan=capture['start']['plan'],
        meaning='supported-code registry, separate from current/historical DTC memory',
        modules=modules,wireRequestCount=len(capture['events']),probeEnd=capture['end'],
        completion=capture['completion'],rejectedFrames=capture['rejectedFrames'],
        qualifiedFactoryDefinitions=[],qualifiedWarningLinks=[],vehicleOperations=0,
        limitations=['Catalog matches preserve unqualified original headings and conflicts; they do not establish factory meanings or software applicability.',
                     'An absent supported-code heading is an intersection result, not proof a catalog page is wrong or an ECU monitor is missing.',
                     'The count in19 01 09 applies to mask09 and is never compared with the19 0A registry length.',
                     'F194 must match the exact expected software before any catalog comparison; no format is inferred from an address or from19 0A.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('probe', type=Path)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--index', type=Path, default=DEFAULT_INDEX)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--allow-partial', action='store_true',help='Retain a validated completed prefix of an aborted/incomplete plan; never mark the full plan complete')
    args = parser.parse_args()
    result = decode(args.probe.read_text(),json.loads(args.plan.read_text()),json.loads(args.index.read_text()),allow_partial=args.allow_partial)
    result['sources'] = [dict(path=str(p.resolve()),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in [args.probe,args.plan,args.index]]
    content = json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    if args.output:
        args.output.write_text(content)
    else:
        print(content,end='')


if __name__ == '__main__':
    main()
