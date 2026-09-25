"""Offline recovery flow checks. No ADB or cloud traffic."""
import unittest

import factory_registration_restore as restore


class RecoveryFlowTest(unittest.TestCase):
    def scenario(self,fail=None):
        calls=[]
        class Fake(restore.Recovery):
            def __init__(self):
                self.iccid=b'factory_iccid';self.imsi=b'factory_imsi';self.report={}
            def prepare_leg(self,name,pair):
                calls.append(('prepare',name,pair))
                if fail=='prepare':raise TimeoutError()
                return object(),{'identity_source':name}
            def login(self,machine,leg):
                calls.append(('login',leg['identity_source']))
                if fail=='login':raise TimeoutError()
                return True
            def cleanup(self):calls.append(('cleanup',))
        candidate=Fake()
        try:
            try:candidate.restore_once()
            finally:candidate.cleanup()
        except TimeoutError:pass
        return calls,candidate.report

    def test_one_original_pair_without_variant(self):
        calls,report=self.scenario()
        self.assertEqual(calls,[('prepare','factory_pair',(b'factory_iccid',b'factory_imsi')),
                                ('login','factory_pair'),('cleanup',)])
        self.assertTrue(report['factory_login_accepted'])

    def test_cleanup_even_when_native_or_tls_step_fails(self):
        for phase in ('prepare','login'):
            calls,report=self.scenario(phase)
            self.assertEqual(calls[-1],('cleanup',))
            self.assertNotIn('factory_login_accepted',report)


if __name__=='__main__':unittest.main()
