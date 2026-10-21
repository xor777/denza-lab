import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_vhds_config_export as runner


class FakeDevice:
    def __init__(self, fail=None):
        self.events = []
        self.fail = fail

    def call(self, name):
        self.events.append(name)
        if name == self.fail:
            raise RuntimeError("private_remote_detail")

    def preflight(self):
        self.call("preflight")
        return "4743"

    def claim(self, *_): self.call("claim")
    def stage_export(self, *_):
        self.call("stage")
        return True
    def forward(self):
        self.call("forward")
        return 12345
    def arm(self): self.call("arm")
    def start(self): self.call("start")
    def close_export(self, *_): self.call("close")
    def remove_forward(self, *_): self.call("remove_forward")
    def identity(self): self.call("identity")
    def release(self, *_): self.call("release")


class FakeReader:
    def __init__(self, device, fail=None):
        self.device = device
        self.fail = fail

    def read_config(self, host, port):
        assert (host, port) == ("127.0.0.1", 12345)
        self.device.events.append("read")
        if self.fail == "read":
            raise RuntimeError("private_response_body")
        return b"sensitive raw response"

    def project_config(self, raw):
        assert raw == b"sensitive raw response"
        self.device.events.append("project")
        if self.fail == "project":
            raise RuntimeError("private_server_detail")
        return {"status": "valid_config", "resultData": []}


class LifecycleTests(unittest.TestCase):
    def execute(self, fail=None, reader_fail=None):
        device = FakeDevice(fail)
        state = runner.run(device, FakeReader(device, reader_fail), "a" * 32)
        self.assertNotIn("private", json.dumps(state))
        return state, device.events

    def test_projection_only_after_closure_retirement_cleanup(self):
        state, events = self.execute()
        self.assertEqual(state["status"], "complete")
        self.assertEqual(events, ["preflight", "claim", "stage", "forward", "arm", "start",
                                  "read", "close", "remove_forward", "identity", "release", "project"])

    def test_content_failure_still_closes(self):
        state, events = self.execute(reader_fail="read")
        self.assertEqual(state["status"], "refused")
        self.assertTrue(state["exportClosed"] and state["leaseReleased"])
        self.assertEqual(state["failureStage"], "read")
        self.assertNotIn("project", events)

    def test_ambiguous_arm_or_start_is_closed(self):
        for fail in ("arm", "start"):
            with self.subTest(fail=fail):
                state, events = self.execute(fail)
                self.assertTrue(state["exportClosed"] and state["leaseReleased"])
                self.assertEqual(events.count("close"), 1)
                self.assertNotIn("read", events)

    def test_preflight_and_claim_refusal_never_stop(self):
        for fail in ("preflight", "claim"):
            state, events = self.execute(fail)
            self.assertEqual(state["status"], "refused")
            for event in ("close", "release", "identity", "project"):
                self.assertNotIn(event, events)

    def test_partial_stage_keeps_lease_and_does_not_stop(self):
        for fail in ("stage", "forward"):
            state, events = self.execute(fail)
            self.assertEqual(state["status"], "incomplete")
            self.assertNotIn("close", events)
            self.assertNotIn("release", events)
        self.assertEqual(state["forwardError"], "forward_allocation_unverified")

    def test_unconfirmed_close_keeps_watchdog_and_lease(self):
        state, events = self.execute("close")
        self.assertEqual(state["status"], "incomplete")
        self.assertFalse(state["exportClosed"])
        self.assertNotIn("release", events)
        self.assertNotIn("project", events)
        self.assertIn("remove_forward", events)

    def test_other_postflight_failure_prevents_projection(self):
        for fail in ("remove_forward", "identity", "release"):
            state, events = self.execute(fail)
            self.assertEqual(state["status"], "incomplete")
            self.assertNotIn("project", events)

    def test_projection_failure_after_verified_cleanup(self):
        state, events = self.execute(reader_fail="project")
        self.assertEqual(state["error"], "cache_projection_rejected")
        self.assertTrue(state["leaseReleased"])

    def test_default_plan_does_not_construct_device(self):
        with patch.object(runner, "plan", return_value={"plan": True}), \
                patch.object(runner, "Device", side_effect=AssertionError("unexpected ADB")), \
                contextlib.redirect_stdout(io.StringIO()) as out:
            self.assertEqual(runner.main([]), 0)
        self.assertEqual(json.loads(out.getvalue()), {"plan": True})

    def test_changed_plan_refuses_before_device(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "plan.json"
            path.write_text("{}")
            with patch.object(runner, "plan", return_value={"changed": True}), \
                    patch.object(runner, "Device", side_effect=AssertionError("unexpected ADB")), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                runner.main(["--run-acknowledged", "--reviewed-plan", str(path),
                             "--output", str(Path(temp) / "output")])

    def test_consumed_plan_refuses_before_device(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "plan.json"
            path.write_text("{}")
            Path(str(path) + ".consumed").touch()
            with patch.object(runner, "plan", return_value={}), \
                    patch.object(runner, "Device", side_effect=AssertionError("unexpected ADB")), \
                    self.assertRaises(FileExistsError):
                runner.main(["--run-acknowledged", "--reviewed-plan", str(path),
                             "--output", str(Path(temp) / "output")])

    def test_forward_removal_requires_exact_owner(self):
        device = runner.Device()
        for rows in ("other-device tcp:12345 tcp:46321", "127.0.0.1:5555 tcp:12345 tcp:46322", ""):
            with patch.object(device, "command", return_value=rows) as command, \
                    self.assertRaises(runner.lifecycle.Refused):
                device.remove_forward(12345)
            self.assertEqual(command.call_count, 1)
        with patch.object(device, "command", side_effect=[
                "127.0.0.1:5555 tcp:12345 tcp:46321", "", ""]) as command:
            device.remove_forward(12345)
        self.assertEqual(command.call_args_list[1].args[0], ["forward", "--remove", "tcp:12345"])

    def test_identity_checks_only_the_export_apk(self):
        device = runner.Device()
        path, digest = runner.lifecycle.APKS["com.byd.bydlogtool"]
        with patch.object(device, "shell", side_effect=["package:" + path, digest + "  " + path]) as shell:
            device.identity()
        self.assertEqual(shell.call_count, 2)
        self.assertNotIn("ota", str(shell.call_args_list).lower())

    def test_staging_does_not_start_export_or_upload_helper(self):
        device = runner.Device()
        with patch.object(device, "shell", side_effect=["", "", "created"]) as shell:
            self.assertTrue(device.stage_export("a" * 32, "4743"))
        calls = [item.args[0] for item in shell.call_args_list]
        self.assertTrue(all(item.startswith("umask 077; printf") for item in calls[:2]))
        self.assertEqual(calls[2], "mkdir /data/local/tmp/logtool && echo created")
        self.assertNotIn("classes.dex", "".join(calls))


if __name__ == "__main__":
    unittest.main()
