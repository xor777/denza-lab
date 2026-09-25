"""Qualification of an explicit alpha profile cannot reuse broad experimental flags."""
import copy
import unittest
from build_runtime_package import PROFILE, PROFILE_REQUIRED, profile_qualification


class ProfileQualificationTest(unittest.TestCase):
    def native(self):
        return {'protocol': 2, 'product_qualified': False,
                'profiles': {PROFILE: {'qualified': True,
                                      'capabilities': {key: True for key in PROFILE_REQUIRED}}}}

    def test_full_runtime_flags_do_not_qualify_alpha(self):
        self.assertFalse(profile_qualification({'protocol': 2, 'product_qualified': True,
                                                'capabilities': {key: True for key in PROFILE_REQUIRED}})[0])

    def test_explicit_complete_profile_is_required(self):
        self.assertTrue(profile_qualification(self.native())[0])
        for key in PROFILE_REQUIRED:
            for invalid in (None, False, 'true', 1):
                value = copy.deepcopy(self.native())
                value['profiles'][PROFILE]['capabilities'][key] = invalid
                self.assertFalse(profile_qualification(value)[0], (key, invalid))
        for field, invalid in [('qualified', False), ('qualified', 'true')]:
            value = self.native()
            value['profiles'][PROFILE][field] = invalid
            self.assertFalse(profile_qualification(value)[0])

    def test_profile_and_native_abi_are_bound(self):
        value = self.native()
        value['protocol'] = 3
        self.assertFalse(profile_qualification(value)[0])
        value = self.native()
        value['profiles']['another-profile'] = value['profiles'].pop(PROFILE)
        self.assertFalse(profile_qualification(value)[0])


if __name__ == '__main__':
    unittest.main()
