"""Synthetic protocol/edge cases plus retained829-heading regressions; no vehicle I/O."""
import copy
import json
from pathlib import Path
import unittest

from decode_supported_dtc import (assemble, crosswalk, decode, parse_count,
                                  parse_probe, parse_supported, standard_display)

INDEX = json.loads(Path(__file__).with_name('oem-dtc-public-index-20261020.json').read_text())
BMS = dict(name='BMS', requestIdHex='7E7', responseIdHex='7EF',
           expectedSoftwareCode='4A520011E00D00047B',requests=['22F194','190109','190A'])


def fragments(data, response_id='7EF', chunk_size=255, result=1):
    chunks = [data[i:i+chunk_size] for i in range(0,len(data),chunk_size)]
    return [(bytes([10])+int(response_id,16).to_bytes(2,'big')+
             bytes([len(chunk),len(chunks),i])+ (bytes([result]) if i==1 else b'')+chunk).hex().upper()
            for i,chunk in enumerate(chunks,1)]


def fixture(*, target=None, fmt=0, availability=9, count=0,
            supported=bytes.fromhex('590A091A392200'), pending=False,
            negative_phase=None, nrc=0x12, timeout_phase=None):
    """One synthetic fixed-plan run. No fixture is claimed as a car response."""
    target = copy.deepcopy(target or BMS)
    plan = dict(schema=1,plans={'synthetic':dict(name='synthetic',maxRequests=3,overallSoftMs=35000,targets=[target])})
    lines=['REGISTERED','START plan=synthetic targets=1 max_requests=3 silence_ms=2000 request_ms=4000 overall_ms=35000']
    t = 1_000_000_000
    callbacks=0
    responses=[bytes.fromhex('62F194'+target['expectedSoftwareCode']),
               bytes([0x59,1,availability,fmt])+count.to_bytes(2,'big'),supported]
    for seq,(request,response) in enumerate(zip(target['requests'],responses),1):
        raw=bytes([10])+int(target['requestIdHex'],16).to_bytes(2,'big')+bytes([len(bytes.fromhex(request))])+bytes.fromhex(request)
        lines.append(f"REQUEST seq={seq} target={target['requestIdHex']} ecu={target['name']} request={request} t_ns={t} raw={raw.hex().upper()}")
        lines.append(f'TRANSPORT_RESULT seq={seq} result=0')
        if timeout_phase == seq:
            state='REQUEST_TIMEOUT'
        else:
            if negative_phase == seq:
                response=bytes([0x7F,int(request[:2],16),nrc])
            if pending and seq==3:
                raw_pending=fragments(bytes.fromhex('7F1978'),target['responseIdHex'],result=2)[0]
                lines.append(f"FRAME seq={seq} target={target['requestIdHex']} t_ns={t+1_000_000} raw={raw_pending}")
                lines.append(f"PENDING seq={seq} target={target['requestIdHex']} uds=7F1978")
                callbacks+=1
            chunks=fragments(response,target['responseIdHex'])
            for i,fragment in enumerate(chunks,1):
                at=t+(i+int(pending and seq==3))*1_000_000
                lines.append(f"FRAME seq={seq} target={target['requestIdHex']} t_ns={at} raw={fragment}")
                callbacks+=1
            latency=(at-t)//1_000_000
            lines.append(f"RESPONSE seq={seq} target={target['requestIdHex']} request={request} frames={len(chunks)} latency_ms={latency} t_ns={at} uds={response.hex().upper()}")
            state=['IDENTITY_MATCH','FORMAT_READ','SUPPORTED_READ'][seq-1]
            if negative_phase == seq:
                state=f'UDS_NEGATIVE_{nrc:02X}'
            elif seq==2:
                lines.append(f"FORMAT seq={seq} target={target['requestIdHex']} format={fmt:02X} status_available={availability:02X} mask09_count={count}")
            elif seq==3:
                lines.append(f"SUPPORTED seq={seq} target={target['requestIdHex']} format={fmt:02X} status_available={response[2]:02X} supported_count={(len(response)-3)//4} active_fault_count=not_measured")
        lines.append(f'STEP_END seq={seq} state={state}')
        if negative_phase == seq or timeout_phase == seq or seq==3:
            lines.append(f"TARGET_END target={target['requestIdHex']} state={state}")
            break
        t+=600_000_000
    lines+=['UNREGISTERED',f'END reason=PLAN_DONE requests={seq} completed={seq} targets=1 cleanup=true callbacks={callbacks} queued=0']
    return '\n'.join(lines)+'\n',plan


class SupportedPayloadTests(unittest.TestCase):
    def test_count_is_not_registry_size_and_byte2_is_not_format(self):
        text,plan=fixture(fmt=0,count=0,supported=bytes.fromhex('590A091A3922001A3A22082BAE0000'))
        module=decode(text,plan,INDEX)['modules'][0]
        self.assertEqual(module['formatRead']['memoryCountForMask09'],0)
        self.assertEqual(module['supportedRegistry']['recordCount'],3)
        self.assertEqual(module['supportedRegistry']['statusAvailabilityHex'],'09')
        self.assertEqual(module['catalogComparison']['matchedDistinctSupportedCodes'],3)
        self.assertNotIn('activeCount',module)
        self.assertEqual(module['supportedRegistry']['records'][1]['statusHex'],'08')

    def test_complete_zero_supported_answer_is_not_timeout(self):
        text,plan=fixture(supported=bytes.fromhex('590A09'))
        zero=decode(text,plan,INDEX)['modules'][0]
        self.assertEqual(zero['supportedRegistry']['recordCount'],0)
        self.assertEqual(len(zero['catalogComparison']['headingsNotInSupportedList']),206)
        text,plan=fixture(timeout_phase=3)
        missing=decode(text,plan,INDEX)['modules'][0]
        self.assertIsNone(missing['supportedRegistry'])
        self.assertEqual(missing['catalogComparison']['state'],'supported_list_not_read')
        self.assertIsNone(missing['catalogComparison']['headingsNotInSupportedList'])

    def test_format01_never_converts_even_matching_numeric_bytes(self):
        text,plan=fixture(fmt=1)
        module=decode(text,plan,INDEX)['modules'][0]
        self.assertEqual(module['supportedRegistry']['records'][0]['raw24Hex'],'1A3922')
        self.assertEqual(module['catalogComparison']['state'],'format01_raw_only')
        self.assertIsNone(module['catalogComparison']['headingMatches'])

    def test_format04_and_unread_format_are_raw_only(self):
        registry=parse_supported(bytes.fromhex('590A091A392200'))
        for fmt,state in [(4,'format_raw_only'),(None,'format_not_read')]:
            result=crosswalk(registry,identity_matches=True,format_id=fmt,sections=['battery_management'],index=INDEX)
            self.assertEqual(result['state'],state)
            self.assertIsNone(result['headingMatches'])

    def test_identity_is_required_for_heading_crosswalk(self):
        registry=parse_supported(bytes.fromhex('590A091A392200'))
        result=crosswalk(registry,identity_matches=False,format_id=0,sections=['battery_management'],index=INDEX)
        self.assertEqual(result['state'],'identity_not_matched')
        self.assertIsNone(result['headingMatches'])

    def test_conflicting_and_duplicate_titles_are_preserved(self):
        registry=parse_supported(bytes.fromhex('590A092BAE00001A200000'))
        result=crosswalk(registry,identity_matches=True,format_id=0,sections=['battery_management'],index=INDEX)
        conflict,duplicate=result['headingMatches']
        self.assertEqual([r['outlineId'] for r in conflict['headings']],['outline_921_a','outline_925_a'])
        self.assertTrue(conflict['conflictingTitlesInSameSection'])
        self.assertEqual(len(duplicate['headings']),2)
        self.assertFalse(duplicate['conflictingTitlesInSameSection'])
        self.assertEqual(result['conflictingMatchedCodes'],['P2BAE00'])

    def test_complements_and_exact_subsystem_match(self):
        registry=parse_supported(bytes.fromhex('590A091A392200FFFFFF00'))
        result=crosswalk(registry,identity_matches=True,format_id=0,sections=['battery_management'],index=INDEX)
        self.assertEqual(result['supportedCodesWithoutHeading'],[dict(raw24Hex='FFFFFF',standardDisplay='U3FFFFF')])
        self.assertEqual(result['matchedDistinctSupportedCodes'],1)
        self.assertEqual(len(result['headingsNotInSupportedList']),205)
        engine=crosswalk(registry,identity_matches=True,format_id=0,sections=['engine_ecm'],index=INDEX)
        self.assertEqual(engine['matchedDistinctSupportedCodes'],0)

    def test_status_bits_are_availability_scoped(self):
        record=parse_supported(bytes.fromhex('590A091A392208'))['records'][0]
        self.assertEqual(record['statusBitStates']['confirmedDTC'],'set')
        self.assertEqual(record['statusBitStates']['testFailed'],'clear')
        self.assertEqual(record['statusBitStates']['pendingDTC'],'unsupported')

    def test_unavailable_status_bits_retained_but_comparison_blocked(self):
        for data in ['590A091A392220','590A091A3922001A392220']:
            registry=parse_supported(bytes.fromhex(data))
            self.assertFalse(registry['integrityPass'])
            self.assertGreater(len(registry['records']),0)
            result=crosswalk(registry,identity_matches=True,format_id=0,sections=['battery_management'],index=INDEX)
            self.assertEqual(result['state'],'supported_list_integrity_failed')

    def test_duplicate_records_preserve_order_and_conflicting_statuses(self):
        payload=bytes.fromhex('590A091A3922002BAE00081A392209')
        registry=parse_supported(payload)
        self.assertTrue(registry['integrityPass'])
        self.assertEqual(registry['duplicateRawCodes'],['1A3922'])
        self.assertEqual([(r['raw24Hex'],r['statusHex']) for r in registry['records']],
                         [('1A3922','00'),('2BAE00','08'),('1A3922','09')])
        text,plan=fixture(supported=payload)
        with self.assertRaisesRegex(ValueError,'duplicate counts'):
            decode(text,plan,INDEX)
        text=text.replace('active_fault_count=not_measured',
                          'active_fault_count=not_measured unique_code_count=2 duplicate_record_count=1')
        result=decode(text,plan,INDEX)
        self.assertTrue(result['completion']['complete'])
        self.assertEqual(result['modules'][0]['supportedRegistry'],registry)
        for bad in ['unique_code_count=3 duplicate_record_count=0','unique_code_count=2',
                    'duplicate_record_count=1','unique_code_count=2 duplicate_record_count=2']:
            with self.subTest(bad=bad),self.assertRaisesRegex(ValueError,'duplicate counts'):
                decode(text.replace('unique_code_count=2 duplicate_record_count=1',bad),plan,INDEX)

    def test_malformed_lengths_do_not_become_zero(self):
        for value in ['','590A','590A091A','590A091A3922','590B09']:
            with self.subTest(value=value),self.assertRaises(ValueError):
                parse_supported(bytes.fromhex(value))
        for value in ['59010900','59010900000000','590209000000']:
            with self.assertRaises(ValueError):parse_count(bytes.fromhex(value))

    def test_standard_notation_regressions(self):
        expected={'516006':'C116006','C29D87':'U029D87','C12187':'U012187','A14802':'B214802','1B0313':'P1B0313'}
        for raw,display in expected.items():self.assertEqual(standard_display(raw),display)


class FixedProbeTests(unittest.TestCase):
    def test_aborted_partial_mode_preserves_prefix_without_fake_target_end(self):
        text,plan=fixture()
        lines=text.splitlines()
        start=next(i for i,line in enumerate(lines) if line.startswith('FRAME seq=3 '))
        text='\n'.join(lines[:start]+['UNREGISTERED','END reason=GATEWAY_SHAPE requests=3 completed=2 targets=0 cleanup=false callbacks=3 queued=1'])+'\n'
        with self.assertRaises(ValueError):decode(text,plan,INDEX)
        result=decode(text,plan,INDEX,allow_partial=True)
        module=result['modules'][0]
        self.assertTrue(module['exactSoftwareIdentityMatched'])
        self.assertEqual(module['formatRead']['formatId'],0)
        self.assertIsNone(module['supportedRegistry'])
        self.assertIsNone(module['terminalState'])
        self.assertFalse(module['targetCompleted'])
        self.assertFalse(result['completion']['complete'])
        self.assertEqual(result['completion']['uncompletedSequences'],[3])
        self.assertEqual(result['completion']['targetsWithoutEnd'],['7E7'])
        self.assertFalse(result['completion']['callbackAccountingComplete'])

    def test_missing_end_is_explicitly_incomplete_not_fabricated(self):
        text,plan=fixture()
        text='\n'.join(text.splitlines()[:-1])+'\n'
        result=decode(text,plan,INDEX,allow_partial=True)
        self.assertIsNone(result['probeEnd'])
        self.assertFalse(result['completion']['complete'])
        self.assertFalse(result['completion']['endPresent'])
        self.assertTrue(result['modules'][0]['targetCompleted'])

    def test_rejected_frame_is_retained_but_never_assembled(self):
        text,plan=fixture()
        lines=text.splitlines()
        stop=next(i for i,line in enumerate(lines) if line.startswith('FRAME seq=3 '))
        # Invalid gateway declaration retained with source tail, never passed to assemble.
        rejected='REJECTED_FRAME seq=3 target=7E7 t_ns=2201000000 reason=GATEWAY_SHAPE raw_bytes=10 logged_bytes=10 truncated=false raw=0A07EF03010103590A09'
        text='\n'.join(lines[:stop]+[rejected,'UNREGISTERED','END reason=GATEWAY_SHAPE requests=3 completed=2 targets=0 cleanup=true callbacks=3 queued=0'])+'\n'
        result=decode(text,plan,INDEX,allow_partial=True)
        self.assertEqual(result['rejectedFrames'][0]['raw'],'0A07EF03010103590A09')
        self.assertFalse(result['rejectedFrames'][0]['acceptedAsResponse'])
        self.assertIsNone(result['modules'][0]['supportedRegistry'])
        self.assertTrue(result['completion']['callbackAccountingComplete'])
        self.assertFalse(result['completion']['complete'])
        bad=text.replace('logged_bytes=10','logged_bytes=9')
        with self.assertRaisesRegex(ValueError,'raw length'):decode(bad,plan,INDEX,allow_partial=True)

    def test_rejected_null_empty_and_capped_buffers_are_retained(self):
        original,plan=fixture()
        lines=original.splitlines()
        stop=next(i for i,line in enumerate(lines) if line.startswith('FRAME seq=3 '))
        for suffix in ['raw_bytes=-1 logged_bytes=0 truncated=false raw=NULL',
                       'raw_bytes=0 logged_bytes=0 truncated=false raw=',
                       'raw_bytes=1025 logged_bytes=1024 truncated=true raw='+'FF'*1024]:
            rejected='REJECTED_FRAME seq=3 target=7E7 t_ns=2201000000 reason=FRAME_SHAPE '+suffix
            text='\n'.join(lines[:stop]+[rejected,'UNREGISTERED','END reason=FRAME_SHAPE requests=3 completed=2 targets=0 cleanup=true callbacks=3 queued=0'])+'\n'
            result=decode(text,plan,INDEX,allow_partial=True)
            self.assertIsNone(result['modules'][0]['supportedRegistry'])
            self.assertEqual(len(result['rejectedFrames']),1)

    def test_rejected_source_view_does_not_count_callback_twice(self):
        text,plan=fixture()
        lines=text.splitlines()
        stop=next(i for i,line in enumerate(lines) if line.startswith('SUPPORTED seq=3 '))
        frame=next(line for line in lines if line.startswith('FRAME seq=3 ')).split('raw=')[1]
        rejected=f'REJECTED_FRAME seq=3 target=7E7 t_ns=2201000000 reason=SUPPORTED_RESPONSE_SHAPE raw_bytes={len(frame)//2+3} logged_bytes={len(frame)//2+3} truncated=false raw={frame}AABBCC'
        text='\n'.join(lines[:stop]+[rejected,'UNREGISTERED','END reason=SUPPORTED_RESPONSE_SHAPE requests=3 completed=2 targets=0 cleanup=true callbacks=3 queued=0'])+'\n'
        result=decode(text,plan,INDEX,allow_partial=True)
        self.assertTrue(result['completion']['callbackAccountingComplete'])
        self.assertIsNone(result['modules'][0]['supportedRegistry'])

    def test_quiet_period_rejection_has_no_invented_module(self):
        text,plan=fixture()
        lines=text.splitlines()[:2]
        lines+=['REJECTED_FRAME seq=0 target=7E7 t_ns=1 reason=PREEXISTING_DIAGNOSTIC_TRAFFIC raw_bytes=0 logged_bytes=0 truncated=false raw=',
                'UNREGISTERED','END reason=PREEXISTING_DIAGNOSTIC_TRAFFIC requests=0 completed=0 targets=0 cleanup=true callbacks=1 queued=0']
        result=decode('\n'.join(lines)+'\n',plan,INDEX,allow_partial=True)
        self.assertEqual(result['modules'],[])
        self.assertEqual(result['completion']['targetsNotStarted'],['7E7'])

    def test_unvisited_targets_are_listed_without_modules_or_end_records(self):
        text,plan=fixture()
        lines=text.splitlines()
        stop=next(i for i,line in enumerate(lines) if line.startswith('FRAME seq=3 '))
        plan['plans']['synthetic']['targets'].append(dict(BMS,name='SMCU',requestIdHex='744',responseIdHex='74C',expectedSoftwareCode='48370011E086001100'))
        plan['plans']['synthetic']['maxRequests']=6
        lines[1]=lines[1].replace('targets=1 max_requests=3','targets=2 max_requests=6')
        text='\n'.join(lines[:stop]+['UNREGISTERED','END reason=GATEWAY_SHAPE requests=3 completed=2 targets=0 cleanup=false callbacks=3 queued=1'])+'\n'
        result=decode(text,plan,INDEX,allow_partial=True)
        self.assertEqual(len(result['modules']),1)
        self.assertEqual(result['completion']['targetsNotStarted'],['744'])
        self.assertEqual(result['completion']['targetsWithoutEnd'],['7E7','744'])

    def test_partial_does_not_accept_forged_completed_counts(self):
        text,plan=fixture()
        text=text.replace('reason=PLAN_DONE','reason=GATEWAY_SHAPE').replace('completed=3','completed=2')
        with self.assertRaisesRegex(ValueError,'completed prefix'):decode(text,plan,INDEX,allow_partial=True)

    def test_incomplete_step_response_is_raw_evidence_only(self):
        text,plan=fixture()
        lines=text.splitlines()
        stop=next(i for i,line in enumerate(lines) if line.startswith('SUPPORTED seq=3 '))
        text='\n'.join(lines[:stop]+['UNREGISTERED','END reason=SUPPORTED_RESPONSE_SHAPE requests=3 completed=2 targets=0 cleanup=true callbacks=3 queued=0'])+'\n'
        result=decode(text,plan,INDEX,allow_partial=True)
        self.assertIsNone(result['modules'][0]['supportedRegistry'])
        self.assertIsNotNone(result['modules'][0]['wireEvents'][2]['response'])
        self.assertFalse(result['modules'][0]['wireEvents'][2]['exchangeComplete'])

    def test_multi_fragment_registry_is_independently_assembled(self):
        records=b''.join(i.to_bytes(3,'big')+b'\x00' for i in range(100))
        text,plan=fixture(supported=b'\x59\x0A\x09'+records)
        module=decode(text,plan,INDEX)['modules'][0]
        self.assertEqual(module['supportedRegistry']['recordCount'],100)
        self.assertEqual(len(module['wireEvents'][2]['frames']),2)

    def test_pending_does_not_resend_or_rearm(self):
        text,plan=fixture(pending=True)
        result=decode(text,plan,INDEX)
        self.assertEqual(result['wireRequestCount'],3)
        self.assertEqual(result['modules'][0]['wireEvents'][2]['pendingResponses'],1)
        changed=text.replace('latency_ms=2','latency_ms=1')
        with self.assertRaisesRegex(ValueError,'timestamp/latency'):decode(changed,plan,INDEX)

    def test_nrc12_and_nrc31_remain_distinct(self):
        for nrc,unsupported in [(0x11,True),(0x12,True),(0x22,False),(0x31,False)]:
            text,plan=fixture(negative_phase=3,nrc=nrc)
            module=decode(text,plan,INDEX)['modules'][0]
            self.assertIsNone(module['supportedRegistry'])
            self.assertEqual(module['negativeResponses'][0]['unsupportedServiceOrSubfunction'],unsupported)
            self.assertEqual(module['negativeResponses'][0]['nrcHex'],f'{nrc:02X}')

    def test_early_nrc_cannot_supply_format_from_historical_identity(self):
        text,plan=fixture(negative_phase=2,nrc=0x12)
        module=decode(text,plan,INDEX)['modules'][0]
        self.assertIsNone(module['formatRead'])
        self.assertEqual(module['catalogComparison']['state'],'format_not_read')

    def test_f194_mismatch_is_rejected_even_if_projection_says_match(self):
        text,plan=fixture()
        changed=text.replace(BMS['expectedSoftwareCode'], '4A520011E00D00047A')
        with self.assertRaisesRegex(ValueError,'identity mismatch'):decode(changed,plan,INDEX)

    def test_altered_response_cannot_override_raw_frames(self):
        text,plan=fixture()
        changed=text.replace('uds=590A091A392200','uds=590A091A3A2200')
        with self.assertRaisesRegex(ValueError,'raw FRAME'):decode(changed,plan,INDEX)

    def test_wrong_subfunction_echo_is_rejected(self):
        text,plan=fixture()
        changed=text.replace('590A091A392200','5902091A392200')
        with self.assertRaisesRegex(ValueError,'registry shape'):decode(changed,plan,INDEX)

    def test_projection_count_and_memory_scope_are_checked(self):
        text,plan=fixture()
        for before,after in [('supported_count=1','supported_count=0'),('mask09_count=0','mask09_count=1'),('active_fault_count=not_measured','active_fault_count=0')]:
            with self.subTest(before=before),self.assertRaises(ValueError):decode(text.replace(before,after),plan,INDEX)

    def test_frame_caps_order_tail_and_incomplete_are_rejected(self):
        frame=fragments(bytes.fromhex('590A091A392200'))[0]
        for bad in [frame+'00',frame[:8]+'11'+frame[10:],frame.replace('0A07EF','0A07ED'),frame[:-2]]:
            with self.subTest(bad=bad),self.assertRaises(ValueError):assemble([dict(raw=bad)],'190A',0x7EF)
        multi=fragments(bytes.fromhex('590A091A392200'),chunk_size=4)
        with self.assertRaisesRegex(ValueError,'incomplete'):assemble([dict(raw=multi[0])],'190A',0x7EF)
        with self.assertRaises(ValueError):assemble([dict(raw=x) for x in reversed(multi)],'190A',0x7EF)

    def test_truncated_lifecycle_foreign_target_and_wrong_sequence(self):
        text,plan=fixture()
        cases=['\n'.join(text.splitlines()[:-1]),text.replace('REQUEST seq=2','REQUEST seq=1'),text.replace('target=7E7 ecu=BMS','target=7E0 ecu=BMS',1),text.replace('REGISTERED\n','',1)]
        for changed in cases:
            with self.subTest(changed=changed[:70]),self.assertRaises(ValueError):parse_probe(changed,plan)

    def test_late_frame_does_not_extend_original_deadline(self):
        text,plan=fixture()
        text=text.replace('t_ns=2201000000','t_ns=6200000000')
        with self.assertRaisesRegex(ValueError,'deadline'):decode(text,plan,INDEX)

    def test_rear_sections_are_both_kept_with_own_side_first(self):
        for route,code,own,cross in [('744','48370011E086001100','rear_left','rear_right'),('745','48370011E088001100','rear_right','rear_left')]:
            target=dict(BMS,name='SMCU' if route=='744' else 'SMCU3',requestIdHex=route,responseIdHex=f'{int(route,16)+8:03X}',expectedSoftwareCode=code)
            text,plan=fixture(target=target,supported=bytes.fromhex('590A091B0313001B041300'))
            result=decode(text,plan,INDEX)['modules'][0]['catalogComparison']
            self.assertEqual(result['candidateSections'],[own,cross])
            rows=result['headingMatches']
            own_matches=[r for r in rows if r['ownSideHeadings']]
            cross_matches=[r for r in rows if r['crossSideHeadings']]
            self.assertEqual(len(own_matches),1)
            self.assertEqual(len(cross_matches),1)
            self.assertFalse(result['qualifiedFactoryMeaning'])


if __name__=='__main__':
    unittest.main()
