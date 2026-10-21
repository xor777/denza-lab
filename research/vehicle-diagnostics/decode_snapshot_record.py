#!/usr/bin/env python3
"""Offline replay of the one fixed BMS1A3A22/01 snapshot-record research read.

Usage: python3 decode_snapshot_record.py CAPTURE/probe.txt --plan BUILD/plan.json
       [--allow-partial] [--output result.json]

Validates the exact F194, format00 and observed1903 pair before the one1904
request. Only the echoed DTC/status/record/count header is decoded. All following
bytes stay opaque; a DID count supplies no DID lengths, meanings or units.
"""
import argparse
import hashlib
import json
from pathlib import Path

from decode_supported_dtc import hex_bytes, parse_count, require, standard_display
from decode_snapshot_index import (DISCOVERY_CONSTRAINTS, SNAPSHOT_RECORD_PAIR,
    SNAPSHOT_RECORD_REQUEST, parse_snapshot_index, parse_snapshot_probe,
    parse_snapshot_record, parse_snapshot_record_empty)


def snapshot_record_response_evidence(rows):
    """Expose observed positive04 fields even when the probe did not complete it.

    RESPONSE is already verified against normalized FRAMEs by the shared parser.
    Never read the unnormalised REJECTED_FRAME source-buffer tail as UDS data.
    """
    evidence = []
    for row in rows:
        if row['request'] != SNAPSHOT_RECORD_REQUEST or row['response'] is None:
            continue
        raw = hex_bytes(row['response']['uds'])
        if raw[:2] != b'\x59\x04':
            continue
        evidence.append(dict(seq=row['seq'],rawResponseHex=raw.hex().upper(),
            declaredUdsByteCount=len(raw),exchangeComplete=row['exchangeComplete'],
            acceptedAsSnapshotRecord=row['exchangeComplete'] and row['stepEnd']['state'] == 'SNAPSHOT_RECORD_READ',
            acceptedAsEmptyRecordResponse=row['exchangeComplete'] and row['stepEnd']['state'] == 'SNAPSHOT_RECORD_EMPTY',
            positiveSidAndSubfunctionEcho=True,
            observedDtcHex=raw[2:5].hex().upper() if len(raw) >= 5 else None,
            observedStatusHex=f'{raw[5]:02X}' if len(raw) >= 6 else None,
            recordNumberPresent=len(raw) >= 7,
            observedSnapshotRecordNumberHex=f'{raw[6]:02X}' if len(raw) >= 7 else None,
            numberOfIdentifiersPresent=len(raw) >= 8,
            observedNumberOfIdentifiers=raw[7] if len(raw) >= 8 else None,
            opaqueRecordDataHex=raw[8:].hex().upper() if len(raw) >= 8 else None,
            source='Declared normalized FRAME payload only; source-buffer bytes beyond declared length are excluded',
            protocolValidityQualification='not assessed by this structural evidence projection'))
    return evidence


def decode(text, plan_document, *, allow_partial=False):
    capture = parse_snapshot_probe(text,plan_document,allow_partial=allow_partial,snapshot_record=True)
    target = capture['plan']['targets'][0]
    rows = capture['events']
    responses = {r['request']:r['response']['uds'] for r in rows
                 if r['exchangeComplete'] and r['response'] is not None}
    identity = responses.get('22F194')
    matched = identity == '62F194'+target['expectedSoftwareCode']
    actual = identity[6:] if identity and identity.startswith('62F194') and len(identity) == 24 else None
    count = index = record = empty_record_response = None
    negatives = []
    for request,response in responses.items():
        raw = hex_bytes(response)
        if raw[:1] == b'\x7F':
            require(len(raw) == 3 and raw[1] == int(request[:2],16), 'negative response shape/service')
            negatives.append(dict(request=request,responseHex=response,nrcHex=f'{raw[2]:02X}',
                                  unsupportedServiceOrSubfunction=raw[2] in [0x11,0x12]))
        elif request == '22F194':
            require(len(raw) == 12 and raw[:3] == b'\x62\xF1\x94', 'F194 response shape')
        elif request == '190109':
            count = parse_count(raw)
        elif request == '1903':
            index = parse_snapshot_index(raw)
        elif request == SNAPSHOT_RECORD_REQUEST:
            require(matched and count is not None and count['formatId'] == 0, 'snapshot record identity/format prerequisite')
            require(index is not None and SNAPSHOT_RECORD_PAIR in index['records'], 'snapshot record index prerequisite')
            if len(raw) == 6:
                empty_record_response = parse_snapshot_record_empty(raw,int(count['statusAvailabilityHex'],16))
                require(empty_record_response['integrityPass'], 'empty snapshot response unavailable status bits')
                empty_record_response['standardDisplay'] = standard_display(empty_record_response['raw24Hex'])
            else:
                record = parse_snapshot_record(raw,int(count['statusAvailabilityHex'],16))
                require(record['integrityPass'], 'snapshot record unavailable status bits')
                record['standardDisplay'] = standard_display(record['raw24Hex'])
    module = dict(name=target['name'],requestIdHex=target['requestIdHex'],responseIdHex=target['responseIdHex'],
        expectedSoftwareCode=target['expectedSoftwareCode'],observedSoftwareCode=actual,
        exactSoftwareIdentityMatched=matched,formatRead=count,
        exactFormat00PrerequisiteMatched=count is not None and count['formatId'] == 0,
        snapshotIndex=index,requiredPair=SNAPSHOT_RECORD_PAIR,
        requiredPairObserved=index is not None and SNAPSHOT_RECORD_PAIR in index['records'],
        snapshotRecord=record,emptySnapshotRecordResponse=empty_record_response,
        snapshotRecordResponseEvidence=snapshot_record_response_evidence(rows),negativeResponses=negatives,
        targetCompleted=target['requestIdHex'] in capture['targetEnds'],
        terminalState=capture['targetEnds'].get(target['requestIdHex']),wireEvents=rows)
    return dict(schema='denza-lab.snapshot-record-offline.v1',plan=capture['start']['plan'],
        meaning='One fixed snapshot-record response header and opaque DID/value bytes; no factory meanings or engineering values',
        modules=[module],wireRequestCount=len(rows),probeEnd=capture['end'],
        completion=capture['completion'],rejectedFrames=capture['rejectedFrames'],
        discoveryConstraints=DISCOVERY_CONSTRAINTS,qualifiedFactoryDefinitions=[],qualifiedWarningLinks=[],
        decodedDids=[],engineeringValues=[],discoveredExtendedDataRecordNumbers=[],automaticFollowupRequests=[],vehicleOperations=0,
        limitations=['The1903 index is an observed identifier-pair gate, not a count of active faults or proof of record content.',
            'A positive1904 response containing record fields supplies the echoed DTC, status byte, record number, numberOfIdentifiers and remaining opaque bytes.',
            'A completed six-byte positive1904 DTC/status prefix returns no snapshot records; its absent record number and identifier count are never supplied from the request.',
            'Status need not be00; only bits outside the separately observed availability are rejected. No active/history summary is inferred.',
            'The DID count does not define boundaries in the opaque tail. No DID labels, lengths, values, units or payload/count relationship are invented.',
            'An incomplete probe can still retain an independently assembled positive04 response. Observed absent record/count fields stay null, and request values are never substituted into the response.',
            'No further DTC, snapshot record or1906 request is generated.'])


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


if __name__ == '__main__':
    main()
