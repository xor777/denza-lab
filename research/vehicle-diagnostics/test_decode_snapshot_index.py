"""Snapshot03 grammar and fixed-probe replay; all wire fixtures are synthetic."""
import copy
import unittest

from decode_snapshot_index import decode, parse_snapshot_index
from test_decode_supported_dtc import BMS, fragments


def fixture(*, records='1A3922011A392202', fmt=0, memory_count=0,
            availability=9, pending=False, nrc=None, timeout=False):
    target=copy.deepcopy(BMS)
    target['requests']=['22F194','190109','1903']
    plan=dict(schema=1,plans={'snapshot-test':dict(name='snapshot-test',maxRequests=3,
              overallSoftMs=35000,targets=[target])})
    lines=['REGISTERED','START plan=snapshot-test targets=1 max_requests=3 silence_ms=2000 request_ms=4000 overall_ms=35000']
    responses=[bytes.fromhex('62F194'+target['expectedSoftwareCode']),
               bytes([0x59,1,availability,fmt])+memory_count.to_bytes(2,'big'),
               bytes.fromhex('5903'+records)]
    callbacks=0
    for seq,(request,response) in enumerate(zip(target['requests'],responses),1):
        t=seq*600_000_000
        raw=bytes.fromhex('0A07E7')+bytes([len(bytes.fromhex(request))])+bytes.fromhex(request)
        lines += [f'REQUEST seq={seq} target=7E7 ecu=BMS request={request} t_ns={t} raw={raw.hex().upper()}',
                  f'TRANSPORT_RESULT seq={seq} result=0']
        if seq==3 and timeout:
            state='REQUEST_TIMEOUT'
        else:
            if seq==3 and nrc is not None:
                response=bytes([0x7F,0x19,nrc])
            if seq==3 and pending:
                raw_pending=fragments(bytes.fromhex('7F1978'),result=2)[0]
                lines += [f'FRAME seq={seq} target=7E7 t_ns={t+1_000_000} raw={raw_pending}',
                          f'PENDING seq={seq} target=7E7 uds=7F1978']
                callbacks+=1
            chunks=fragments(response)
            for i,chunk in enumerate(chunks,1):
                at=t+(i+int(seq==3 and pending))*1_000_000
                lines.append(f'FRAME seq={seq} target=7E7 t_ns={at} raw={chunk}')
                callbacks+=1
            lines.append(f'RESPONSE seq={seq} target=7E7 request={request} frames={len(chunks)} latency_ms={(at-t)//1_000_000} t_ns={at} uds={response.hex().upper()}')
            state=['IDENTITY_MATCH','FORMAT_READ','SNAPSHOT_INDEX_READ'][seq-1]
            if seq==2:
                lines.append(f'FORMAT seq={seq} target=7E7 format={fmt:02X} status_available={availability:02X} mask09_count={memory_count}')
            if seq==3:
                if nrc is not None:
                    state=f'UDS_NEGATIVE_{nrc:02X}'
                else:
                    lines.append(f'SNAPSHOT_INDEX seq={seq} target=7E7 format={fmt:02X} record_count={len(bytes.fromhex(records))//4} active_fault_count=not_measured')
        lines.append(f'STEP_END seq={seq} state={state}')
    lines += [f'TARGET_END target=7E7 state={state}','UNREGISTERED',
              f'END reason=PLAN_DONE requests=3 completed=3 targets=1 cleanup=true callbacks={callbacks} queued=0']
    return '\n'.join(lines)+'\n',plan


class SnapshotIndexPayloadTests(unittest.TestCase):
    def test_tuple_last_byte_is_record_number_not_status(self):
        result=parse_snapshot_index(bytes.fromhex('59031A392280'))
        self.assertEqual(result['records'],[dict(raw24Hex='1A3922',snapshotRecordNumberHex='80')])
        self.assertNotIn('statusAvailabilityHex',result)
        self.assertNotIn('statusHex',result['records'][0])

    def test_empty_explicit_index(self):
        result=parse_snapshot_index(bytes.fromhex('5903'))
        self.assertEqual(result['recordCount'],0)
        self.assertEqual(result['records'],[])
        self.assertTrue(result['integrityPass'])

    def test_same_dtc_can_have_multiple_record_numbers(self):
        result=parse_snapshot_index(bytes.fromhex('59031A3922011A392202'))
        self.assertEqual(result['recordCount'],2)
        self.assertTrue(result['integrityPass'])
        self.assertEqual([r['snapshotRecordNumberHex'] for r in result['records']],['01','02'])

    def test_exact_duplicate_pairs_are_preserved_and_flagged(self):
        result=parse_snapshot_index(bytes.fromhex('59031A3922011A392201'))
        self.assertEqual(result['recordCount'],2)
        self.assertFalse(result['integrityPass'])
        self.assertEqual(result['duplicatePairs'],[dict(raw24Hex='1A3922',snapshotRecordNumberHex='01')])

    def test_old_status_availability_header_is_not_accepted(self):
        with self.assertRaises(ValueError):parse_snapshot_index(bytes.fromhex('5903091A392201'))

    def test_wrong_echo_and_incomplete_tuples_rejected(self):
        for raw in ['', '59','590A','5902','59031A','59031A39','59031A3922','59031A39220100']:
            with self.subTest(raw=raw),self.assertRaises(ValueError):parse_snapshot_index(bytes.fromhex(raw))


class SnapshotProbeTests(unittest.TestCase):
    def test_mask09_count_is_separate_from_snapshot_tuple_count(self):
        text,plan=fixture(memory_count=19)
        result=decode(text,plan)
        module=result['modules'][0]
        self.assertEqual(module['formatRead']['memoryCountForMask09'],19)
        self.assertEqual(module['snapshotIndex']['recordCount'],2)
        self.assertTrue(result['completion']['complete'])
        self.assertEqual(result['automaticFollowupRequests'],[])
        self.assertEqual(result['discoveredExtendedDataRecordNumbers'],[])

    def test_format01_stays_raw_with_record_ids_preserved(self):
        text,plan=fixture(fmt=1)
        module=decode(text,plan)['modules'][0]
        self.assertEqual(module['formatRead']['formatId'],1)
        self.assertEqual(module['snapshotIndex']['records'][0],dict(raw24Hex='1A3922',snapshotRecordNumberHex='01',standardDisplay=None))

    def test_format00_notation_does_not_become_factory_definition(self):
        text,plan=fixture()
        result=decode(text,plan)
        self.assertEqual(result['modules'][0]['snapshotIndex']['records'][0]['standardDisplay'],'P1A3922')
        self.assertEqual(result['qualifiedFactoryDefinitions'],[])
        self.assertEqual(result['qualifiedWarningLinks'],[])

    def test_empty_index_and_no_response_are_different(self):
        text,plan=fixture(records='')
        self.assertEqual(decode(text,plan)['modules'][0]['snapshotIndex']['recordCount'],0)
        text,plan=fixture(timeout=True)
        self.assertIsNone(decode(text,plan)['modules'][0]['snapshotIndex'])

    def test_nrc13_is_not_unsupported(self):
        text,plan=fixture(nrc=0x13)
        module=decode(text,plan)['modules'][0]
        self.assertIsNone(module['snapshotIndex'])
        self.assertFalse(module['negativeResponses'][0]['unsupportedServiceOrSubfunction'])
        self.assertEqual(module['negativeResponses'][0]['nrcHex'],'13')

    def test_pending_keeps_one_request_and_original_deadline(self):
        text,plan=fixture(pending=True)
        result=decode(text,plan)
        self.assertEqual(result['wireRequestCount'],3)
        self.assertEqual(result['modules'][0]['wireEvents'][2]['pendingResponses'],1)
        with self.assertRaisesRegex(ValueError,'deadline'):
            decode(text.replace('t_ns=1802000000','t_ns=5800000000'),plan)

    def test_multifragment_snapshot_index(self):
        records=''.join(f'{i:06X}01' for i in range(100))
        text,plan=fixture(records=records)
        module=decode(text,plan)['modules'][0]
        self.assertEqual(module['snapshotIndex']['recordCount'],100)
        self.assertEqual(len(module['wireEvents'][2]['frames']),2)

    def test_projection_cannot_inject_status_mask_or_forge_count(self):
        text,plan=fixture()
        for bad in [text.replace('record_count=2','record_count=1'),text.replace('record_count=2','status_available=09 record_count=2')]:
            with self.assertRaises(ValueError):decode(bad,plan)

    def test_identity_mismatch_and_wrong_request_plan_rejected(self):
        text,plan=fixture()
        with self.assertRaisesRegex(ValueError,'identity mismatch'):
            decode(text.replace(BMS['expectedSoftwareCode'],'4A520011E00D00047A'),plan)
        changed=copy.deepcopy(plan)
        changed['plans']['snapshot-test']['targets'][0]['requests'][-1]='1904'
        with self.assertRaisesRegex(ValueError,'fixed request list'):decode(text,changed)

    def test_altered_response_and_wrong_echo_rejected(self):
        text,plan=fixture()
        with self.assertRaisesRegex(ValueError,'raw FRAME'):
            decode(text.replace('uds=59031A3922011A392202','uds=59031A3922011A392203'),plan)
        with self.assertRaises(ValueError):decode(text.replace('59031A3922011A392202','59041A3922011A392202'),plan)

    def test_partial_abort_keeps_completed_identity_and_format_only(self):
        text,plan=fixture()
        lines=text.splitlines();stop=next(i for i,l in enumerate(lines) if l.startswith('FRAME seq=3 '))
        rejected='REJECTED_FRAME seq=3 target=7E7 t_ns=1801000000 reason=FRAME_SHAPE raw_bytes=0 logged_bytes=0 truncated=false raw='
        text='\n'.join(lines[:stop]+[rejected,'UNREGISTERED','END reason=FRAME_SHAPE requests=3 completed=2 targets=0 cleanup=true callbacks=3 queued=0'])+'\n'
        with self.assertRaises(ValueError):decode(text,plan)
        result=decode(text,plan,allow_partial=True)
        self.assertFalse(result['completion']['complete'])
        self.assertIsNone(result['modules'][0]['snapshotIndex'])
        self.assertEqual(result['modules'][0]['formatRead']['formatId'],0)
        self.assertIsNone(result['modules'][0]['terminalState'])
        self.assertFalse(result['rejectedFrames'][0]['acceptedAsResponse'])

    def test_missing_end_not_fabricated_in_partial_mode(self):
        text,plan=fixture()
        text='\n'.join(text.splitlines()[:-1])+'\n'
        result=decode(text,plan,allow_partial=True)
        self.assertIsNone(result['probeEnd'])
        self.assertFalse(result['completion']['complete'])


if __name__=='__main__':
    unittest.main()
