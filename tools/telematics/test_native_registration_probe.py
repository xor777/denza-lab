"""Offline IMSI experiment checks; transport is mocked, native codecs are real."""
import json
import unittest
from unittest.mock import patch

import native_registration_probe as probe
from native_registration import NativeRegistration
from verify_native_roundtrip import VIN_VALUE, KEY, UUID


class ImsiExperimentTest(unittest.TestCase):
    def setUp(self):
        self.original=b'001010123456789'
        self.iccid=b'89010000000000000001'
        self.variant=probe.test_imsi(self.original)
        def native(imsi):
            return NativeRegistration(probe.FIRMWARE,VIN_VALUE,KEY,UUID,
                self.iccid,imsi,b'',1700000000)
        self.restore=native(self.original)
        self.restore.packet()
        self.changed=native(self.variant)
        self.packet=self.changed.packet()

    def test_native_bodies_change_only_selected_imsi(self):
        self.assertEqual(self.variant[:5],self.original[:5])
        self.assertEqual(len(self.variant),15)
        self.assertTrue(self.variant.isdigit())
        self.assertTrue(all(a!=b for a,b in zip(self.variant[5:],self.original[5:])))
        self.assertEqual(self.changed.bodies[-1],self.variant+self.iccid)
        self.assertEqual(self.restore.bodies[-1],self.original+self.iccid)
        with self.assertRaises(ValueError):probe.test_imsi(b'invalid')

    def scenario(self,first_error=False,restore_error=False,first_accepted=True):
        seen=[]
        def transport(binary,serial,report,application,host,port,**kwargs):
            machine=application[1].__self__
            seen.append(machine.bodies[-1])
            if (len(seen)==1 and first_error) or (len(seen)==2 and restore_error):
                raise TimeoutError('synthetic ambiguous transport failure')
            report.update(mutual_tls_verified=True,native={'application_response':{
                'native_handler_executed':True,
                'registration_accepted':first_accepted if len(seen)==1 else True}})
        report={}
        with patch.object(probe,'execute',transport):
            if first_error:
                with self.assertRaises(TimeoutError):
                    probe.exchange('synthetic',report,self.packet,self.changed,self.restore)
            else:
                probe.exchange('synthetic',report,self.packet,self.changed,self.restore)
        self.assertEqual(seen,[self.variant+self.iccid,self.original+self.iccid])
        encoded=json.dumps(report)
        for private in (self.variant,self.original,self.iccid):
            self.assertNotIn(private.decode(),encoded)
        self.assertEqual(report['original_pair_restoration']['accepted'],not restore_error)
        return report

    def test_accepted_attempt_restores_original(self):self.scenario()

    def test_rejection_still_restores_original(self):self.scenario(first_accepted=False)

    def test_ambiguous_transport_still_restores_original(self):self.scenario(first_error=True)

    def test_failed_restoration_is_reported_without_retry(self):
        report=self.scenario(restore_error=True)
        self.assertFalse(report['original_pair_restoration']['retry'])
        self.assertTrue(report['original_pair_restoration']['failed'])


if __name__=='__main__':unittest.main()
