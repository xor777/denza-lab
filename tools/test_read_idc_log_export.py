"""Offline scope and lifecycle checks; every ADB/network/sleep action is mocked."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import read_idc_log_export as probe


class IdcExportTests(unittest.TestCase):
    name = 'IDC_2026-10-18_1792278800589.log'

    def test_wire_scope(self):
        path = probe.DIRECTORY + '/' + self.name
        self.assertTrue(probe.packet(1, path))
        self.assertTrue(probe.packet(2, probe.DIRECTORY))
        for action, target in ((3, path), (0, path), (True, path),
                (1, path + '/..'), (1, probe.DIRECTORY + '/../' + self.name),
                (1, path.replace('2026-10-18', '2026-10-19')),
                (2, '/data/user/0'), (1, '/data/user/0/com.byd.otaupdate/files/dkey.key')):
            with self.subTest(action=action, path=target), self.assertRaises(probe.wire.ExportError):
                probe.packet(action, target)

    def test_selection_bounds_and_no_fallback(self):
        self.assertEqual(probe.select_log({self.name: 100}), (probe.DIRECTORY + '/' + self.name, 100))
        for listing in ({}, {self.name: True}, {self.name: 0},
                {self.name: probe.wire.MAX_FILE + 1, 'IDC_2026-10-17_1792192400589.log': 1}):
            with self.subTest(listing=listing), self.assertRaises(probe.wire.ExportError):
                probe.select_log(listing)

    def test_rejected_size_retained_without_download(self):
        report = {}
        with self.assertRaisesRegex(probe.wire.ExportError, 'file_size_out_of_bounds'):
            probe.select_log({self.name: probe.wire.MAX_FILE + 1}, report)
        self.assertEqual(report['listedBytes'], probe.wire.MAX_FILE + 1)
        self.assertEqual(report['file'], probe.DIRECTORY + '/' + self.name)

    def test_authorization_before_any_io(self):
        with patch.object(probe.common, 'shell') as shell, tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / 'not-created'
            with self.assertRaisesRegex(probe.wire.ExportError, 'fresh_owner_authorization_required'):
                probe.export(target)
            shell.assert_not_called()
            self.assertFalse(target.exists())

    def run_session(self, fail_read=False):
        calls = []
        def shell(command, timeout=8):
            calls.append(command)
            if command == probe.common.STATE:
                return '\n'.join(probe.EXPECTED_STATE) + '\n'
            if command.startswith('sha256sum '):
                return '\n'.join(value + '  fixture.apk' for value in probe.APK_HASHES)
            if command.startswith('nohup sh -c '):
                return '1234\n'
            if command == 'cat ' + probe.WATCHLOG:
                return 'Broadcast completed: result=0\n'
            return ''
        responses = [{self.name: 3}, probe.wire.ExportError('synthetic_read_failure') if fail_read else b'abc']
        with tempfile.TemporaryDirectory() as tmp, patch.object(probe.common, 'shell', side_effect=shell), \
             patch.object(probe.common, 'adb', return_value='12345\n') as adb, \
             patch.object(probe, 'exchange', side_effect=responses) as exchange, \
             patch.object(probe.time, 'sleep'):
            target = Path(tmp) / 'new'
            if fail_read:
                with self.assertRaisesRegex(probe.wire.ExportError, 'synthetic_read_failure'):
                    probe.export(target, True)
                self.assertFalse((target / 'idc-existing.log').exists())
            else:
                probe.export(target, True)
                self.assertEqual((target / 'idc-existing.log').read_bytes(), b'abc')
                self.assertEqual((target / 'idc-existing.log').stat().st_mode & 0o777, 0o600)
            report = json.loads((target / 'export.json').read_text())
            self.assertTrue(report['cleanup']['watchdogStopObserved'])
            self.assertTrue(report['cleanup']['stateUnchanged'])
            self.assertEqual(report['cleanup']['remainingPorts'], [])
            self.assertEqual(exchange.call_count, 2)
            self.assertEqual([c.args[1] for c in exchange.call_args_list], [2, 1])
            self.assertEqual(adb.call_args_list[-1].args[0], ['forward', '--remove', 'tcp:12345'])
        self.assertLess(calls.index('kill -0 1234'), calls.index(probe.common.START))
        self.assertLess(calls.index(probe.common.START), calls.index(probe.common.STOP))
        self.assertEqual(calls.count(probe.common.START), 1)
        self.assertEqual(calls.count(probe.common.STOP), 1)

    def test_one_file_and_cleanup(self):
        self.run_session()

    def test_stop_and_discard_after_read_error(self):
        self.run_session(fail_read=True)


if __name__ == '__main__':
    unittest.main()
