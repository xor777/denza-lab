"""Fake-device lifecycle only; never starts export or invokes ADB."""
import contextlib
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import run_diagnostic_label_cache as runner
from test_run_vhds_config_export import FakeDevice


class LabelCacheSessionTests(unittest.TestCase):
    def test_default_is_plan_only_and_hashes_every_loaded_reader(self):
        with patch.object(runner, 'Device', side_effect=AssertionError('ADB forbidden')), contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(runner.main([]), 0)
        plan = json.loads(out.getvalue())
        self.assertEqual(plan['files'], list(runner.reader.ALLOWED_FILES))
        self.assertEqual(plan['maximumDownloads'], 2)
        self.assertEqual(plan['directoryListings'], 2)
        self.assertEqual(plan['retries'], 0)
        self.assertEqual(len(plan['sourceSha256']), 7)
        self.assertFalse(plan['manualDatabasesIncluded'])
        self.assertTrue(plan['freshOwnerApprovalRequired'])

    def test_changed_plan_and_consumed_marker_reject_before_device(self):
        for consumed in (False, True):
            with self.subTest(consumed=consumed), tempfile.TemporaryDirectory() as tmp:
                plan = Path(tmp) / 'plan.json'
                plan.write_text(json.dumps(runner.plan()) if consumed else '{}')
                if consumed:
                    Path(str(plan) + '.consumed').touch()
                with patch.object(runner, 'Device', side_effect=AssertionError('ADB forbidden')), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaises(FileExistsError if consumed else SystemExit):
                        runner.main(['--run-acknowledged', '--reviewed-plan', str(plan), '--output', str(Path(tmp)/'output')])

    def test_preflight_failure_consumes_one_session_without_export(self):
        device = FakeDevice('preflight')
        with tempfile.TemporaryDirectory() as tmp:
            plan = Path(tmp)/'plan.json'
            plan.write_text(json.dumps(runner.plan()))
            with patch.object(runner, 'Device', return_value=device), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(runner.main(['--run-acknowledged', '--reviewed-plan', str(plan), '--output', str(Path(tmp)/'out')]), 2)
            self.assertEqual(device.events, ['preflight'])
            self.assertTrue(Path(str(plan)+'.consumed').exists())
            data = json.loads((Path(tmp)/'out/result.json').read_text())
            self.assertNotIn('result', data)

    def test_real_projection_only_after_all_cleanup_and_private_output(self):
        device = FakeDevice()
        data = {p: b'<map><string name="unrelated">PRIVATE</string></map>' for p in runner.reader.ALLOWED_FILES}
        project = runner.reader.project_config
        def check_project(raw):
            self.assertEqual(device.events[-4:], ['close', 'remove_forward', 'identity', 'release'])
            device.events.append('project')
            return project(raw)
        with tempfile.TemporaryDirectory() as tmp:
            plan = Path(tmp)/'plan.json'
            plan.write_text(json.dumps(runner.plan()))
            with patch.object(runner, 'Device', return_value=device), patch.object(runner.reader, 'read_config', return_value=data), \
                    patch.object(runner.reader, 'project_config', side_effect=check_project), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(runner.main(['--run-acknowledged', '--reviewed-plan', str(plan), '--output', str(Path(tmp)/'out')]), 0)
            output = Path(tmp)/'out/result.json'
            self.assertNotIn('PRIVATE', output.read_text())
            self.assertNotIn('<map>', output.read_text())
            self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
            self.assertEqual(stat.S_IMODE(output.parent.stat().st_mode), 0o700)
            self.assertEqual(device.events.count('start'), 1)
            self.assertEqual(device.events.count('close'), 1)

    def test_cleanup_failures_prevent_actual_projection(self):
        for failure in ('close', 'remove_forward', 'identity', 'release'):
            device = FakeDevice(failure)
            with self.subTest(failure=failure), patch.object(runner.reader, 'read_config', return_value={}), \
                    patch.object(runner.reader, 'project_config') as project:
                result = runner.session.run(device, runner.reader, 'a'*32)
            project.assert_not_called()
            self.assertEqual(result['status'], 'incomplete')
            self.assertNotIn('private_remote_detail', json.dumps(result))

    def test_identity_pins_exporter_and_two_source_apks(self):
        device = runner.Device()
        path, digest = runner.session.lifecycle.APKS['com.byd.bydlogtool']
        rows = ['package:'+path, digest+'  '+path]
        for path, digest in runner.TARGET_APKS.values():
            rows.extend(['package:'+path, digest+'  '+path])
        with patch.object(device, 'shell', side_effect=rows) as shell:
            device.identity()
        self.assertEqual(shell.call_count, 6)
        self.assertNotIn('otaupdate2', str(shell.call_args_list))


if __name__ == '__main__':
    unittest.main()
