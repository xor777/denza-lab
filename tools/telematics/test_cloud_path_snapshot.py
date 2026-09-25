import json
from pathlib import Path
import subprocess
import sys
import unittest
from types import SimpleNamespace

import cloud_path_snapshot as snapshot


class SnapshotTest(unittest.TestCase):
    def test_selection_and_unknown_are_distinct_from_missing(self):
        for current, persistent, expected in (
            ('8' * 20, '9' * 20, 'ril.csim.iccid'),
            ('8' * 19, '9' * 20, 'persist.radio.iccid'),
            ('', '', 'no_valid_length'),
            (None, '9' * 20, 'unknown'),
            ('8' * 19, None, 'unknown'),
        ):
            result = snapshot.summarize_identity({'ril.csim.iccid': current, 'persist.radio.iccid': persistent})
            self.assertEqual(expected, result['mqtt_getter_source_if_called_now'])
            self.assertEqual('not_observed', result['native_cached_identity'])

    def test_collection_retains_no_identifiers_or_error_output(self):
        calls = []
        iccid, persistent, imsi = '89010000000000000001', '89010000000000000002', '001010123456789'

        def run(command, **kwargs):
            calls.append(command[3:])
            args = command[3:]
            value = ''
            if args == ['get-state']:
                value = 'device'
            elif args == ['shell', 'id']:
                value = 'uid=2000(shell)'
            elif args == ['shell', 'getenforce']:
                value = 'Enforcing'
            elif args[:2] == ['shell', 'getprop']:
                value = {'ril.csim.iccid': iccid, 'persist.radio.iccid': persistent,
                         'ril.imsi': imsi}.get(args[2], '')
            elif args == ['shell', 'service', 'list']:
                # Names/descriptors confirmed by the 2026-09-24 read-only service inventory.
                value = '90\tcloudmanager: [android.os.ICloudRemoteControlService]\n192\tmqttserv: [android.os.IBYDCloudMqttServer]'
            elif args[:3] == ['shell', 'ls', '-ldZ']:
                return SimpleNamespace(returncode=1, stdout='', stderr=iccid + imsi)
            else:
                self.fail('Unexpected operation: ' + repr(args))
            return SimpleNamespace(returncode=0, stdout=value, stderr='')

        report = snapshot.collect('test:5555', runner=run)
        encoded = json.dumps(report)
        for secret in (iccid, persistent, imsi):
            self.assertNotIn(secret, encoded)
        self.assertTrue(report['services']['cloudmanager'])
        self.assertTrue(report['services']['mqttserv'])
        self.assertFalse(report['identity_sources']['current_persistent_iccid_equal'])
        self.assertFalse(report['complete'])
        self.assertEqual(20, len(calls))
        self.assertFalse(any('call' in c or 'connect' in c or 'setprop' in c for c in calls))

    def test_transport_failure_stops_immediately(self):
        calls = []

        def run(command, **kwargs):
            calls.append(command)
            raise subprocess.TimeoutExpired(command, 1, output='private-data')

        report = snapshot.collect('test:5555', runner=run)
        self.assertFalse(report['transport_ready'])
        self.assertEqual(1, len(calls))
        self.assertNotIn('private-data', json.dumps(report))

    def test_property_access_warning_is_unknown_not_empty(self):
        def run(command, **kwargs):
            args = command[3:]
            if args == ['get-state']:
                return SimpleNamespace(returncode=0, stdout='device', stderr='')
            if args == ['shell', 'getprop', 'ril.csim.iccid']:
                return SimpleNamespace(returncode=0, stdout='', stderr='Access denied finding property')
            return SimpleNamespace(returncode=0, stdout='', stderr='')

        report = snapshot.collect('test:5555', runner=run)
        identity = report['identity_sources']
        self.assertFalse(identity['properties']['ril.csim.iccid']['readable'])
        self.assertEqual('unknown', identity['mqtt_getter_source_if_called_now'])

    def test_preview_does_not_require_adb_or_serial(self):
        run = subprocess.run([sys.executable, str(Path(snapshot.__file__).resolve())],
                             capture_output=True, text=True, check=True, env={'PATH': '/nonexistent'})
        self.assertFalse(json.loads(run.stdout)['vehicle_contacted'])


if __name__ == '__main__':
    unittest.main()
