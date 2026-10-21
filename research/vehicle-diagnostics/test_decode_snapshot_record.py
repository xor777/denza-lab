"""One fixed BMS snapshot04 record; all test wire fixtures are synthetic."""
import copy
import unittest

from decode_snapshot_index import parse_snapshot_record, parse_snapshot_record_empty
from decode_snapshot_record import decode
from test_decode_supported_dtc import BMS, fragments


def fixture(*, fmt=0, availability=9, memory_count=0, records='1A3A2201',
            status=0, did_count=1, payload='F1900102', echo='1A3A22', record=1,
            nrc_phase=None, nrc=0x31, timeout_phase=None, pending=False, empty=False):
    target = copy.deepcopy(BMS)
    target['requests'] = ['22F194','190109','1903','19041A3A2201']
    target['expectedFormatIdHex'] = '00'
    target['requiredSnapshotPair'] = dict(rawDtcHex='1A3A22',recordNumberHex='01')
    plan = dict(schema=1,plans={'bms-snapshot-record':dict(name='bms-snapshot-record',
        readKind='snapshot-record',maxRequests=4,overallSoftMs=25000,overallHardMs=27000,
        targets=[target])})
    responses = [bytes.fromhex('62F194'+target['expectedSoftwareCode']),
        bytes([0x59,1,availability,fmt])+memory_count.to_bytes(2,'big'),
        bytes.fromhex('5903'+records),
        bytes.fromhex('5904'+echo)+bytes([status,record,did_count])+bytes.fromhex(payload)]
    if empty:
        responses[-1] = bytes.fromhex('5904'+echo)+bytes([status])
    lines = ['REGISTERED','START plan=bms-snapshot-record targets=1 max_requests=4 silence_ms=2000 request_ms=4000 overall_ms=25000']
    callbacks = 0
    for seq,(request,response) in enumerate(zip(target['requests'],responses),1):
        t=seq*600_000_000
        request_raw=bytes.fromhex('0A07E7')+bytes([len(bytes.fromhex(request))])+bytes.fromhex(request)
        lines += [f'REQUEST seq={seq} target=7E7 ecu=BMS request={request} t_ns={t} raw={request_raw.hex().upper()}',
                  f'TRANSPORT_RESULT seq={seq} result=0']
        state = ['IDENTITY_MATCH','FORMAT_READ','SNAPSHOT_INDEX_READ','SNAPSHOT_RECORD_READ'][seq-1]
        stop = False
        if seq == timeout_phase:
            state = 'REQUEST_TIMEOUT'
            stop = True
        else:
            if seq == nrc_phase:
                response = bytes([0x7F,0x19,nrc])
            if seq == 4 and pending:
                raw = fragments(bytes.fromhex('7F1978'),result=2)[0]
                lines += [f'FRAME seq={seq} target=7E7 t_ns={t+1_000_000} raw={raw}',
                          f'PENDING seq={seq} target=7E7 uds=7F1978']
                callbacks += 1
            chunks = fragments(response)
            for i,chunk in enumerate(chunks,1):
                at=t+(i+int(seq==4 and pending))*1_000_000
                lines.append(f'FRAME seq={seq} target=7E7 t_ns={at} raw={chunk}')
                callbacks += 1
            lines.append(f'RESPONSE seq={seq} target=7E7 request={request} frames={len(chunks)} latency_ms={(at-t)//1_000_000} t_ns={at} uds={response.hex().upper()}')
            if seq == nrc_phase:
                state=f'UDS_NEGATIVE_{nrc:02X}'
                stop=True
            elif seq == 2:
                lines.append(f'FORMAT seq=2 target=7E7 format={fmt:02X} status_available={availability:02X} mask09_count={memory_count}')
                if fmt != 0:
                    state='FORMAT_MISMATCH'
                    stop=True
            elif seq == 3:
                lines.append(f'SNAPSHOT_INDEX seq=3 target=7E7 format={fmt:02X} record_count={len(bytes.fromhex(records))//4} active_fault_count=not_measured')
                pairs = [records[i:i+8].upper() for i in range(0,len(records),8)]
                if '1A3A2201' not in pairs:
                    state='SNAPSHOT_PAIR_UNAVAILABLE'
                    stop=True
            elif seq == 4:
                if empty:
                    lines.append(f'SNAPSHOT_RECORD_EMPTY seq=4 target=7E7 format={fmt:02X} dtc={echo} status={status:02X}')
                    state='SNAPSHOT_RECORD_EMPTY'
                else:
                    lines.append(f'SNAPSHOT_RECORD seq=4 target=7E7 format={fmt:02X} dtc={echo} status={status:02X} record={record:02X} did_count={did_count} payload_bytes={len(bytes.fromhex(payload))}')
        lines.append(f'STEP_END seq={seq} state={state}')
        if stop:
            break
    lines += [f'TARGET_END target=7E7 state={state}','UNREGISTERED',
              f'END reason=PLAN_DONE requests={seq} completed={seq} targets=1 cleanup=true callbacks={callbacks} queued=0']
    return '\n'.join(lines)+'\n',plan


def header_only_abort_fixture():
    text,plan=fixture()
    lines=text.splitlines()
    stop=next(i for i,l in enumerate(lines) if l.startswith('FRAME seq=4 '))
    normalized='0A07EF0601010159041A3A2200'
    source_buffer=normalized+'DEADBEEF'*10
    lines=lines[:stop]+[
        f'FRAME seq=4 target=7E7 t_ns=2401000000 raw={normalized}',
        'RESPONSE seq=4 target=7E7 request=19041A3A2201 frames=1 latency_ms=1 t_ns=2401000000 uds=59041A3A2200',
        f'REJECTED_FRAME seq=4 target=7E7 t_ns=2401000000 reason=SNAPSHOT_RECORD_RESPONSE_SHAPE raw_bytes={len(bytes.fromhex(source_buffer))} logged_bytes={len(bytes.fromhex(source_buffer))} truncated=false raw={source_buffer}',
        'UNREGISTERED',
        'END reason=SNAPSHOT_RECORD_RESPONSE_SHAPE requests=4 completed=3 targets=0 cleanup=false callbacks=5 queued=1']
    return '\n'.join(lines)+'\n',plan


class SnapshotRecordPayloadTests(unittest.TestCase):
    def test_exact_six_byte_empty_response_has_no_record_fields(self):
        result=parse_snapshot_record_empty(bytes.fromhex('59041A3A2208'),9)
        self.assertEqual(result['snapshotRecordsReturned'],0)
        self.assertEqual(result['statusHex'],'08')
        self.assertFalse(result['recordNumberPresent'])
        self.assertFalse(result['numberOfIdentifiersPresent'])
        self.assertNotIn('snapshotRecordNumberHex',result)
        self.assertNotIn('numberOfIdentifiers',result)
        self.assertNotIn('rawIdentifierDataHex',result)
        self.assertTrue(result['integrityPass'])

    def test_empty_response_rejects_wrong_echo_partial_or_full_record_header(self):
        for raw in ['59041A392200','59041A3A22','59041A3A220001','59041A3A22000100']:
            with self.subTest(raw=raw),self.assertRaises(ValueError):
                parse_snapshot_record_empty(bytes.fromhex(raw),9)

    def test_empty_response_flags_unavailable_status_bits(self):
        result=parse_snapshot_record_empty(bytes.fromhex('59041A3A2202'),9)
        self.assertEqual(result['unavailableStatusBitsHex'],'02')
        self.assertFalse(result['integrityPass'])

    def test_header_keeps_status_record_count_and_opaque_bytes(self):
        result = parse_snapshot_record(bytes.fromhex('59041A3A22080102F1901234'),9)
        self.assertEqual(result['raw24Hex'],'1A3A22')
        self.assertEqual(result['statusHex'],'08')
        self.assertEqual(result['snapshotRecordNumberHex'],'01')
        self.assertEqual(result['numberOfIdentifiers'],2)
        self.assertEqual(result['rawIdentifierDataHex'],'F1901234')
        self.assertEqual(result['decodedDids'],[])
        self.assertTrue(result['integrityPass'])

    def test_no_relationship_between_did_count_and_opaque_tail_invented(self):
        for tail in ['00','00AABB','FF','FFAABB']:
            with self.subTest(tail=tail):
                result=parse_snapshot_record(bytes.fromhex('59041A3A220001'+tail),9)
                self.assertEqual(result['rawIdentifierDataHex'],tail[2:])
                self.assertTrue(result['integrityPass'])

    def test_status_outside_availability_is_preserved_and_flagged(self):
        result=parse_snapshot_record(bytes.fromhex('59041A3A22020100'),9)
        self.assertEqual(result['statusHex'],'02')
        self.assertEqual(result['unavailableStatusBitsHex'],'02')
        self.assertFalse(result['integrityPass'])

    def test_wrong_echo_record_and_short_header_rejected(self):
        for raw in ['','59','5904','59041A3A220001','59041A3A22000100'[:-2],
                    '59051A3A22000100','59041A3922000100','59041A3A22000200']:
            with self.subTest(raw=raw),self.assertRaises(ValueError):
                parse_snapshot_record(bytes.fromhex(raw),9)


class SnapshotRecordProbeTests(unittest.TestCase):
    def test_future_explicit_empty_projection_completes_without_returned_record(self):
        text,plan=fixture(empty=True,status=8)
        result=decode(text,plan)
        module=result['modules'][0]
        evidence=module['snapshotRecordResponseEvidence'][0]
        self.assertTrue(result['completion']['complete'])
        self.assertEqual(module['terminalState']['state'],'SNAPSHOT_RECORD_EMPTY')
        self.assertIsNone(module['snapshotRecord'])
        self.assertEqual(module['emptySnapshotRecordResponse']['snapshotRecordsReturned'],0)
        self.assertEqual(module['emptySnapshotRecordResponse']['statusHex'],'08')
        self.assertTrue(evidence['exchangeComplete'])
        self.assertTrue(evidence['acceptedAsEmptyRecordResponse'])
        self.assertFalse(evidence['acceptedAsSnapshotRecord'])
        self.assertFalse(evidence['recordNumberPresent'])
        self.assertIsNone(evidence['observedSnapshotRecordNumberHex'])
        self.assertIsNone(evidence['observedNumberOfIdentifiers'])
        self.assertEqual(result['automaticFollowupRequests'],[])

    def test_empty_projection_fields_cannot_invent_record_or_count(self):
        text,plan=fixture(empty=True)
        for injected in [' record=01',' did_count=0',' payload_bytes=0']:
            with self.subTest(injected=injected),self.assertRaisesRegex(ValueError,'SNAPSHOT_RECORD_EMPTY fields'):
                decode(text.replace('status=00\nSTEP_END seq=4','status=00'+injected+'\nSTEP_END seq=4'),plan)

    def test_empty_projection_and_terminal_state_must_match(self):
        text,plan=fixture(empty=True)
        with self.assertRaises(ValueError):
            decode(text.replace('state=SNAPSHOT_RECORD_EMPTY','state=SNAPSHOT_RECORD_READ'),plan)
        with self.assertRaises(ValueError):
            decode(text.replace('SNAPSHOT_RECORD_EMPTY seq=4','SNAPSHOT_RECORD seq=4'),plan)

    def test_seven_byte_positive_response_cannot_claim_empty(self):
        text,plan=fixture(empty=True)
        text=text.replace('0A07EF0601010159041A3A2200','0A07EF0701010159041A3A220001')
        text=text.replace('uds=59041A3A2200','uds=59041A3A220001')
        with self.assertRaisesRegex(ValueError,'empty snapshot response shape'):decode(text,plan)

    def test_empty_projection_echo_status_format_must_match_wire(self):
        text,plan=fixture(empty=True)
        for old,new in [('format=00 dtc=1A3A22','format=01 dtc=1A3A22'),
                        ('dtc=1A3A22 status=00','dtc=1A3922 status=00'),
                        ('dtc=1A3A22 status=00','dtc=1A3A22 status=08')]:
            with self.subTest(new=new),self.assertRaises(ValueError):decode(text.replace(old,new),plan)

    def test_empty_response_unavailable_status_rejected(self):
        text,plan=fixture(empty=True,status=2)
        with self.assertRaisesRegex(ValueError,'SNAPSHOT_RECORD_EMPTY differs from wire'):decode(text,plan)

    def test_fixed_four_phase_success(self):
        text,plan=fixture()
        result=decode(text,plan)
        module=result['modules'][0]
        self.assertTrue(result['completion']['complete'])
        self.assertEqual(result['wireRequestCount'],4)
        self.assertTrue(module['exactSoftwareIdentityMatched'])
        self.assertTrue(module['exactFormat00PrerequisiteMatched'])
        self.assertTrue(module['requiredPairObserved'])
        self.assertEqual(module['snapshotRecord']['standardDisplay'],'P1A3A22')
        self.assertEqual(module['snapshotRecord']['rawIdentifierDataHex'],'F1900102')
        self.assertEqual(result['automaticFollowupRequests'],[])
        self.assertEqual(result['engineeringValues'],[])
        self.assertEqual(result['qualifiedFactoryDefinitions'],[])
        self.assertEqual(result['qualifiedWarningLinks'],[])
        self.assertTrue(module['snapshotRecordResponseEvidence'][0]['acceptedAsSnapshotRecord'])

    def test_nonzero_available_status_is_valid_without_active_fault_summary(self):
        text,plan=fixture(status=8)
        result=decode(text,plan)
        self.assertEqual(result['modules'][0]['snapshotRecord']['statusHex'],'08')
        self.assertNotIn('activeFaultCount',result)

    def test_unavailable_status_bit_rejected(self):
        text,plan=fixture(status=2)
        with self.assertRaisesRegex(ValueError,'SNAPSHOT_RECORD differs from wire'):decode(text,plan)

    def test_format_mismatch_stops_before_index_or_record(self):
        text,plan=fixture(fmt=1)
        result=decode(text,plan)
        self.assertEqual(result['wireRequestCount'],2)
        self.assertFalse(result['modules'][0]['exactFormat00PrerequisiteMatched'])
        self.assertIsNone(result['modules'][0]['snapshotIndex'])
        self.assertIsNone(result['modules'][0]['snapshotRecord'])
        self.assertEqual(result['modules'][0]['terminalState']['state'],'FORMAT_MISMATCH')

    def test_missing_exact_pair_stops_after_index(self):
        for records in ['', '1A392201', '1A3A2202']:
            with self.subTest(records=records):
                text,plan=fixture(records=records)
                result=decode(text,plan)
                self.assertEqual(result['wireRequestCount'],3)
                self.assertFalse(result['modules'][0]['requiredPairObserved'])
                self.assertIsNone(result['modules'][0]['snapshotRecord'])
                self.assertEqual(result['modules'][0]['terminalState']['state'],'SNAPSHOT_PAIR_UNAVAILABLE')

    def test_same_dtc_different_record_allowed_and_exact_pair_used(self):
        text,plan=fixture(records='1A3A22021A3A2201')
        self.assertTrue(decode(text,plan)['modules'][0]['requiredPairObserved'])

    def test_duplicate_exact_index_pair_rejected(self):
        text,plan=fixture(records='1A3A22011A3A2201')
        with self.assertRaises(ValueError):decode(text,plan)

    def test_mask_count_never_filters_record(self):
        text,plan=fixture(memory_count=0,status=8)
        module=decode(text,plan)['modules'][0]
        self.assertEqual(module['formatRead']['memoryCountForMask09'],0)
        self.assertIsNotNone(module['snapshotRecord'])

    def test_nrc13_and31_do_not_mean_unsupported(self):
        for nrc in [0x13,0x31]:
            with self.subTest(nrc=nrc):
                text,plan=fixture(nrc_phase=4,nrc=nrc)
                module=decode(text,plan)['modules'][0]
                self.assertFalse(module['negativeResponses'][0]['unsupportedServiceOrSubfunction'])
                self.assertIsNone(module['snapshotRecord'])

    def test_index_nrc_stops_without_record_request(self):
        text,plan=fixture(nrc_phase=3,nrc=0x12)
        result=decode(text,plan)
        self.assertEqual(result['wireRequestCount'],3)
        self.assertTrue(result['modules'][0]['negativeResponses'][0]['unsupportedServiceOrSubfunction'])

    def test_timeout_keeps_no_record_response(self):
        text,plan=fixture(timeout_phase=4)
        result=decode(text,plan)
        self.assertEqual(result['modules'][0]['terminalState']['state'],'REQUEST_TIMEOUT')
        self.assertIsNone(result['modules'][0]['snapshotRecord'])

    def test_pending_does_not_add_request_or_extend_deadline(self):
        text,plan=fixture(pending=True)
        result=decode(text,plan)
        self.assertEqual(result['wireRequestCount'],4)
        self.assertEqual(result['modules'][0]['wireEvents'][3]['pendingResponses'],1)
        with self.assertRaisesRegex(ValueError,'deadline'):
            decode(text.replace('t_ns=2402000000','t_ns=6400000000'),plan)

    def test_multifragment_opaque_payload(self):
        text,plan=fixture(payload='AB'*500,did_count=255)
        module=decode(text,plan)['modules'][0]
        self.assertEqual(module['snapshotRecord']['rawIdentifierDataByteCount'],500)
        self.assertEqual(len(module['wireEvents'][3]['frames']),2)
        self.assertEqual(module['snapshotRecord']['numberOfIdentifiers'],255)

    def test_projection_cannot_forge_echo_status_count_or_tail_size(self):
        text,plan=fixture()
        for old,new in [('dtc=1A3A22','dtc=1A3922'),('status=00','status=01'),
                        ('record=01','record=02'),('did_count=1','did_count=2'),
                        ('payload_bytes=4','payload_bytes=3'),('format=00 dtc','format=01 dtc')]:
            with self.subTest(old=old),self.assertRaises(ValueError):decode(text.replace(old,new),plan)
        with self.assertRaisesRegex(ValueError,'SNAPSHOT_RECORD fields'):
            decode(text.replace('payload_bytes=4','payload_bytes=4 physical_temperature=100'),plan)

    def test_record_wrong_echo_or_record_number_rejected(self):
        for kwargs in [dict(echo='1A3922'),dict(record=2)]:
            with self.subTest(kwargs=kwargs):
                text,plan=fixture(**kwargs)
                with self.assertRaises(ValueError):decode(text,plan)

    def test_altered_response_without_altering_wire_rejected(self):
        text,plan=fixture()
        with self.assertRaisesRegex(ValueError,'raw FRAME'):
            decode(text.replace('uds=59041A3A22000101F1900102','uds=59041A3A22000101F1900103'),plan)

    def test_exact_fixed_plan_route_bounds_and_request_required(self):
        text,plan=fixture()
        for field,value in [('readKind','snapshot-index'),('maxRequests',5),('overallHardMs',28000)]:
            changed=copy.deepcopy(plan);changed['plans']['bms-snapshot-record'][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):decode(text,changed)
        for field,value in [('requestIdHex','7E6'),('expectedSoftwareCode','4A520011E00D00047A')]:
            changed=copy.deepcopy(plan);changed['plans']['bms-snapshot-record']['targets'][0][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):decode(text,changed)
        for field,value in [('expectedFormatIdHex','01'),('requiredSnapshotPair',dict(rawDtcHex='1A3922',recordNumberHex='01'))]:
            changed=copy.deepcopy(plan);changed['plans']['bms-snapshot-record']['targets'][0][field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):decode(text,changed)
        changed=copy.deepcopy(plan);changed['plans']['bms-snapshot-record']['targets'][0]['requests'][3]='19041A392201'
        with self.assertRaises(ValueError):decode(text,changed)

    def test_identity_mismatch_is_rejected(self):
        text,plan=fixture()
        with self.assertRaisesRegex(ValueError,'identity mismatch'):
            decode(text.replace(BMS['expectedSoftwareCode'],'4A520011E00D00047A'),plan)

    def test_successful_index_cannot_end_without_fourth_request(self):
        text,plan=fixture()
        lines=text.splitlines();stop=next(i for i,l in enumerate(lines) if l.startswith('REQUEST seq=4 '))
        text='\n'.join(lines[:stop]+['TARGET_END target=7E7 state=SNAPSHOT_INDEX_READ','UNREGISTERED',
            'END reason=PLAN_DONE requests=3 completed=3 targets=1 cleanup=true callbacks=3 queued=0'])+'\n'
        with self.assertRaisesRegex(ValueError,'request missing'):decode(text,plan)

    def test_partial_abort_does_not_promote_incomplete_record_response(self):
        text,plan=fixture()
        lines=text.splitlines();stop=next(i for i,l in enumerate(lines) if l.startswith('SNAPSHOT_RECORD '))
        text='\n'.join(lines[:stop]+['UNREGISTERED',
            'END reason=BAD_RECORD requests=4 completed=3 targets=0 cleanup=true callbacks=4 queued=0'])+'\n'
        with self.assertRaises(ValueError):decode(text,plan)
        result=decode(text,plan,allow_partial=True)
        self.assertFalse(result['completion']['complete'])
        self.assertEqual(result['completion']['uncompletedSequences'],[4])
        self.assertIsNone(result['modules'][0]['snapshotRecord'])
        self.assertIsNotNone(result['modules'][0]['wireEvents'][3]['response'])
        self.assertIsNone(result['modules'][0]['terminalState'])
        self.assertEqual(result['modules'][0]['snapshotRecordResponseEvidence'][0]['rawResponseHex'],'59041A3A22000101F1900102')
        self.assertFalse(result['modules'][0]['snapshotRecordResponseEvidence'][0]['acceptedAsSnapshotRecord'])

    def test_header_only_positive_response_is_explicit_without_fabricated_record(self):
        text,plan=header_only_abort_fixture()
        with self.assertRaises(ValueError):decode(text,plan)
        result=decode(text,plan,allow_partial=True)
        module=result['modules'][0]
        evidence=module['snapshotRecordResponseEvidence'][0]
        self.assertEqual(evidence['rawResponseHex'],'59041A3A2200')
        self.assertEqual(evidence['declaredUdsByteCount'],6)
        self.assertEqual(evidence['observedDtcHex'],'1A3A22')
        self.assertEqual(evidence['observedStatusHex'],'00')
        self.assertFalse(evidence['recordNumberPresent'])
        self.assertIsNone(evidence['observedSnapshotRecordNumberHex'])
        self.assertFalse(evidence['numberOfIdentifiersPresent'])
        self.assertIsNone(evidence['observedNumberOfIdentifiers'])
        self.assertIsNone(evidence['opaqueRecordDataHex'])
        self.assertFalse(evidence['exchangeComplete'])
        self.assertFalse(evidence['acceptedAsSnapshotRecord'])
        self.assertIsNone(module['snapshotRecord'])
        self.assertIsNone(module['emptySnapshotRecordResponse'])
        self.assertIsNone(module['terminalState'])
        self.assertFalse(result['completion']['complete'])
        self.assertEqual(result['completion']['uncompletedSequences'],[4])
        self.assertFalse(result['probeEnd']['cleanup']=='true')
        self.assertEqual(result['probeEnd']['queued'],'1')
        self.assertEqual(result['engineeringValues'],[])

    def test_rejected_callback_tail_never_becomes_record_payload(self):
        text,plan=header_only_abort_fixture()
        result=decode(text,plan,allow_partial=True)
        self.assertIn('DEADBEEF',result['rejectedFrames'][0]['raw'])
        evidence=result['modules'][0]['snapshotRecordResponseEvidence'][0]
        self.assertNotIn('DEADBEEF',evidence['rawResponseHex'])
        self.assertIsNone(evidence['opaqueRecordDataHex'])
        self.assertFalse(result['completion']['callbackAccountingComplete'])


if __name__ == '__main__':
    unittest.main()
