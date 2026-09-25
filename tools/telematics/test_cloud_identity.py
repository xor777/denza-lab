"""Synthetic-only tests. No vehicle, signing service or external network."""
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from cloud_identity import CloudIdentity, load_identity
import registration_probe as probe
from registration_packet import decode_response


ORIGINAL = CloudIdentity(b"89010000000000000001", b"001010123456789")
MODEM = CloudIdentity(b"89010000000000000002", b"001010987654321")


class IdentityFileTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "identity.json"

    def write(self, text):
        self.path.write_text(text)
        self.path.chmod(0o600)

    def test_valid_pair_and_redacted_representation(self):
        self.write(json.dumps({"iccid": ORIGINAL.iccid.decode(), "imsi": ORIGINAL.imsi.decode()}))
        identity = load_identity(self.path)
        self.assertEqual(identity, ORIGINAL)
        self.assertEqual(repr(identity), "CloudIdentity(<redacted>)")

    def test_rejects_partial_duplicate_extra_and_non_ascii_fields(self):
        for text in (
            '{}', '[]', '{"iccid":"123"}',
            '{"iccid":"89010000000000000001","imsi":"001010123456789","imsi":"001010987654321"}',
            '{"iccid":"89010000000000000001","imsi":101}',
            '{"iccid":"89010000000000000001","imsi":"00101012345678１"}',
            '{"iccid":"89010000000000000001","imsi":"001010123456789","other":true}',
        ):
            with self.subTest(text=text):
                self.write(text)
                with self.assertRaisesRegex(ValueError, '^Invalid original SIM identity file$'):
                    load_identity(self.path)

    def test_rejects_shared_permissions_and_large_file(self):
        self.write('{}')
        self.path.chmod(0o644)
        with self.assertRaises(ValueError):
            load_identity(self.path)
        self.write(' ' * 1025)
        with self.assertRaises(ValueError):
            load_identity(self.path)

    def test_rejects_symlink(self):
        self.write('{}')
        link = self.path.with_name('link.json')
        link.symlink_to(self.path)
        with self.assertRaises(OSError):
            load_identity(link)

    def test_preview_does_not_open_file_or_use_adb(self):
        result = subprocess.run(
            [sys.executable, str(Path(probe.__file__)), '--identity-file', str(self.path)],
            capture_output=True, text=True, timeout=5, check=True)
        report = json.loads(result.stdout)
        self.assertEqual(report['mode'], 'preview')
        self.assertEqual(report['identity_source'], 'provided_original_pair')
        self.assertFalse(report['cloud_contacted'])
        self.assertEqual(report['stock_signature_calls'], 0)


class SelectedIdentityProtocolTest(unittest.TestCase):
    def setUp(self):
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        folder = self.stack.enter_context(tempfile.TemporaryDirectory())
        root = Path(folder)
        self.vin = b"TEST123456789ABCD"
        self.key, self.uuid = bytes(range(16)), bytes(range(16, 32))
        libs = root/'captures/telematics-20260923/battery-upload/extra-files/system/lib64'
        libs.mkdir(parents=True)
        for name in ('libbydauto.so', 'libbydautoservice.so'):
            (libs/name).write_bytes(b'synthetic-library')
        discovery = root/'captures/telematics-20260923/registration-inputs/live-discovery-1.json'
        discovery.parent.mkdir(parents=True)
        discovery.write_text(json.dumps({'mutual_tls_verified': True, 'native': {'application_response': {
            'vin_matches': True, 'crc_valid': True,
            'working_hostname': 'dilinknat0-cn.denzacloud.com', 'working_port': 6041}}}))
        self.calls, self.frames = [], []
        def adb(serial, args):
            self.calls.append(args)
            if args[:2] == ['shell', 'sha256sum']:
                return hashlib.sha256(b'synthetic-library').hexdigest()
            if args[:2] == ['shell', 'getprop']:
                return {'ril.imsi': MODEM.imsi.decode(), 'ril.csim.iccid': MODEM.iccid.decode(),
                        'debug.ro.serialno': ''}[args[2]]
            raise AssertionError('Unexpected device operation')
        def buffer(serial, device, fid, length):
            return self.vin if device == 1001 else b'\x01' + self.key + self.uuid
        def execute(binary, serial, report, application, host, port, **kwargs):
            metadata, body = decode_response(application[0], self.vin, self.key, self.uuid)
            self.frames.append((metadata['command'], body))
            report['native'] = {'application_response': {'registration_accepted': metadata['command'] == 211,
                                                          'login_accepted': metadata['command'] == 220}}
        for name, value in (('ROOT', root), ('OWNER_VIN_SHA256', hashlib.sha256(self.vin).hexdigest()),
                            ('adb_read', adb), ('read_buffer', buffer), ('execute', execute)):
            self.stack.enter_context(patch.object(probe, name, value))

    def test_supplied_pair_is_used_for_both_registration_and_login(self):
        reports = [{}, {}]
        for login, report in zip((False, True), reports):
            probe.run('synthetic', Path('unused'), report, login=login, identity_override=ORIGINAL)
        self.assertEqual(self.frames[0], (211, ORIGINAL.imsi + ORIGINAL.iccid))
        self.assertEqual(self.frames[1][0], 220)
        self.assertEqual(self.frames[1][1][32:48], hashlib.md5(ORIGINAL.imsi).digest())
        self.assertNotEqual(self.frames[1][1][32:48], hashlib.md5(MODEM.imsi).digest())
        self.assertFalse(any(c[-1] in ('ril.imsi', 'ril.csim.iccid') for c in self.calls))
        self.assertTrue(all(r['identity_source'] == 'provided_original_pair' for r in reports))
        self.assertNotIn(ORIGINAL.imsi.decode(), json.dumps(reports))
        self.assertNotIn(ORIGINAL.iccid.decode(), json.dumps(reports))

    def test_default_still_uses_modem_pair(self):
        for login in (False, True):
            probe.run('synthetic', Path('unused'), {}, login=login)
        self.assertEqual(self.frames[0], (211, MODEM.imsi + MODEM.iccid))
        self.assertEqual(self.frames[1][1][32:48], hashlib.md5(MODEM.imsi).digest())

    def test_no_identity_override_for_discovery(self):
        with self.assertRaises(ValueError):
            probe.run('synthetic', Path('unused'), {}, discovery=True, identity_override=ORIGINAL)
        self.assertEqual(self.calls, [])

    def test_invalid_source_cannot_start_device_work(self):
        with self.assertRaises(ValueError):
            probe.run('synthetic', Path('unused'), {}, identity_override={})
        self.assertEqual(self.calls, [])


if __name__ == '__main__':
    unittest.main()
