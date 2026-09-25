"""Offline flow and original-code fixtures. No car/cloud traffic."""
import hashlib
import unittest
from pathlib import Path
from elftools.elf.elffile import ELFFile
import imsi_login_compare as probe
from native_session import NativeSession
from verify_native_roundtrip import HELPER, KEY, UUID, VIN_VALUE, PACKET, WIRE
from verify_opaque_native import INPUT

class NativeFlowTest(unittest.TestCase):
    def test_zero_delay_and_continuation_are_from_pinned_jump_tables(self):
        with probe.registration.FIRMWARE.open('rb') as f:
            e=ELFFile(f);parts=[(s['p_vaddr'],s.data()) for s in e.iter_segments() if s['p_type']=='PT_LOAD']
        def read(addr,n):
            for base,data in parts:
                if base<=addr and addr+n<=base+len(data):return data[addr-base:addr-base+n]
            self.fail('unmapped')
        self.assertEqual(0x48374+4*read(0x1e0f4,1)[0],0x483d8)
        self.assertEqual(read(0x483d8,4),bytes.fromhex('c2088052')) # mov w2,#70
        self.assertEqual(0x53a60+4*int.from_bytes(read(0x1e120,2),'little'),0x53c5c)
        self.assertEqual(probe.continuation_delay(0),70)
        self.assertEqual(probe.continuation_delay(1),0)
        for code in (2,3,None):
            with self.assertRaises(ValueError):probe.continuation_delay(code)

    def test_native_zero_then_discovery_and_login_preserves_selected_imsi(self):
        original=b'001010123456789';changed=probe.registration.test_imsi(original)
        iccid=b'89010000000000000001';changed_iccid=probe.test_iccid(iccid)
        self.assertEqual(changed_iccid[:5],b'89860')
        self.assertEqual(len(changed_iccid),20)
        self.assertTrue(changed_iccid.isdigit())
        self.assertNotEqual(changed_iccid,iccid)
        with self.assertRaises(ValueError):probe.test_iccid(b'invalid')
        def fresh(pair):return NativeSession(probe.registration.FIRMWARE,VIN_VALUE,KEY,UUID,*pair,b'',1700000000)
        peer=fresh((iccid,original))
        def fixture(command,flag,body):
            peer.u.mem_write(INPUT,body);peer.call(0x6e3b0,HELPER,0,PACKET,command,flag,INPUT,len(body),0)
            peer.call(0x6e858,HELPER,PACKET,WIRE,len(body));return peer.frames[-1]
        for pair in ((iccid,original),(iccid,changed),(changed_iccid,original),
                     (b'898607'+b'12345678901234',b'46001'+b'1234567890'),(iccid,original)):
            selected_iccid,identity=pair
            m=fresh(pair);m.packet();self.assertEqual(m.bodies[-1],identity+selected_iccid)
            self.assertEqual(probe.receive_registration(m,fixture(211,2,b'\0'))['registration_status'],0)
            m.produce(200);host=b'test.denzacloud.com'
            m.accept(fixture(200,1,(6041).to_bytes(2,'big')+bytes(20)+bytes([len(host)])+host),200)
            m.produce(220);self.assertEqual(m.bodies[-1][32:48],hashlib.md5(identity).digest())
            self.assertTrue(m.accept(fixture(220,1,bytes(16)),220)['login_accepted'])

class FlowTest(unittest.TestCase):
    def scenario(self,fail=None,reject=None,field='imsi-suffix'):
        calls=[]
        variant_name='changed_suffix' if field=='imsi-suffix' else 'changed_iccid'
        class Fake(probe.Comparison):
            def __init__(self):
                self.report={};self.iccid=b'original_iccid';self.imsi=b'original_imsi';self.variant_name=variant_name
            def save(self):pass
            def prepare_leg(self,name,identity):
                calls.append((name,identity))
                if name==fail:raise TimeoutError()
                return None,{'identity_source':name}
            def arm_and_pause(self):calls.append('pause')
            def login(self,m,leg):
                calls.append('login:'+leg['identity_source']);return leg['identity_source']!=reject
            def cleanup(self):calls.append('cleanup')
        f=Fake()
        variant=(b'original_iccid',b'changed_imsi') if field=='imsi-suffix' else (b'changed_iccid',b'original_imsi')
        try:f.compare_pairs(variant)
        except (TimeoutError,ValueError):pass
        return calls,f.report
    def test_control_failure_does_not_send_variant(self):
        calls,_=self.scenario(reject='original_before')
        self.assertFalse(any(isinstance(c,tuple) and c[0]=='changed_suffix' for c in calls));self.assertEqual(calls[-1],'cleanup')
    def test_variant_rejection_still_restores_original(self):
        calls,r=self.scenario(reject='changed_suffix')
        self.assertIn(('original_restore',(b'original_iccid',b'original_imsi')),calls);self.assertTrue(r['original_login_restored']);self.assertEqual(calls[-1],'cleanup')
    def test_ambiguous_variant_transport_still_restores_original(self):
        calls,r=self.scenario(fail='changed_suffix')
        self.assertIn(('original_restore',(b'original_iccid',b'original_imsi')),calls);self.assertTrue(r['original_login_restored'])
    def test_restore_failure_still_reopens_stock(self):
        calls,r=self.scenario(fail='original_restore')
        self.assertEqual(r['original_restore_error'],'TimeoutError');self.assertEqual(calls[-1],'cleanup')

    def test_iccid_attempt_changes_no_imsi_and_restores_both_fields(self):
        for fail in (None,'changed_iccid'):
            calls,r=self.scenario(fail=fail,field='iccid')
            self.assertIn(('changed_iccid',(b'changed_iccid',b'original_imsi')),calls)
            self.assertIn(('original_restore',(b'original_iccid',b'original_imsi')),calls)
            self.assertTrue(r['original_login_restored']);self.assertEqual(calls[-1],'cleanup')

if __name__=='__main__':unittest.main()
