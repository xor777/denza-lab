"""Offline observer runner tests: all ADB/process/network effects are replaced."""
from __future__ import annotations

from collections import deque
from contextlib import ExitStack, redirect_stdout
import inspect
import io
import json
from pathlib import Path
import shlex
import stat
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import observe_ota_routes as subject

NONCE = "0123456789abcdef0123456789abcdef"
OTHER = "abcdef0123456789abcdef0123456789"
PRIVATE = "PRIVATE_TEST_TOKEN_LC012345678901234"
P = "10-18 14:38:38.100  4228  4301 D OTA_GCY_UPGRADE_DiagnosePresenter: "
EVENT = (P + "loopRequestEcuItemInfo item.ecuId: ESP_BOSCH_00100001\n").encode()


def state(nonce=NONCE, status="OBSERVING", **changes):
    result = {
        "schemaVersion": 1, "nonce": nonce, "pid": 1234, "durationSeconds": 3,
        "status": status, "reason": "deadline" if status == "STOPPED" else "observing",
        "elapsedMs": 1, "originals": dict(zip(subject.KEYS, ("", "W"))),
        "restoredVerified": status == "STOPPED", "childExited": status == "STOPPED",
    }
    result.update(changes)
    return result


class FakeClock:
    now = 0.0

    def monotonic(self):
        self.now += .05
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class FakePipe:
    closed = False

    def fileno(self):
        return 76543

    def close(self):
        self.closed = True


class FakeProcess:
    def __init__(self, wire):
        self.stdout = FakePipe()
        self.wire = wire
        self.terminated = self.killed = False

    def poll(self):
        return 0 if self.wire.eof or self.terminated or self.killed else None

    def wait(self, timeout):
        return 0 if self.wire.eof else -15

    def terminate(self):
        self.terminated = True

    def kill(self):
        self.killed = True


class FakeSelector:
    def __init__(self):
        self.key = None
        self.closed = False

    def register(self, file, events):
        self.key = SimpleNamespace(fileobj=file, fd=file.fileno())

    def unregister(self, file):
        self.key = None

    def select(self, timeout):
        return [(self.key, 1)] if self.key else []

    def close(self):
        self.closed = True


class FakeWire:
    def __init__(self, chunks):
        self.chunks = deque(chunks)
        self.eof = False

    def read(self, fd, size):
        value = self.chunks.popleft() if self.chunks else b""
        if isinstance(value, BaseException):
            raise value
        if not value:
            self.eof = True
        return value


class FakeAdb:
    def __init__(self, serial, dex_sha):
        self.command = ["MOCK_ADB", "-s", serial]
        self.commands = []
        self.dex_sha = dex_sha
        self.stopped = False
        self.stop_error = False
        self.property_mismatch = False
        self.push_error = False

    def call(self, *args, input_data=None):
        self.commands.append((args, input_data))
        if args[0] == "push" and self.push_error:
            raise subject.ObserverError("command_failed")
        return b""

    def shell(self, *args):
        self.commands.append((args, None))
        if args[0] == "sha256sum":
            return (self.dex_sha + "  staged.dex\n").encode()
        if args[0] == "getprop":
            return ("V\n" if self.property_mismatch else state()["originals"][args[1]] + "\n").encode()
        if args[:2] == ("rm", "-f"):
            return b""
        raise AssertionError("unmocked shell operation")

    def state(self, nonce):
        return state(nonce, "STOPPED" if self.stopped else "OBSERVING")

    def stop(self, nonce):
        self.commands.append((("stop", nonce), None))
        if self.stop_error:
            raise subject.ObserverError("command_failed")
        self.stopped = True


class ObserverTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.build_dir = self.root / "build"
        self.build_dir.mkdir()
        self.source = self.root / "source.java"
        self.source.write_text("// synthetic source fixture\n")
        (self.build_dir / "classes.dex").write_bytes(b"synthetic-dex")
        self.manifest = {
            "schemaVersion": 1, "sourceSha256": subject.digest(self.source),
            "dexSha256": subject.digest(self.build_dir / "classes.dex"), "mainClass": subject.CLASS,
        }
        (self.build_dir / "build.json").write_text(json.dumps(self.manifest))
        self.patch = ExitStack()
        self.addCleanup(self.patch.close)
        self.patch.enter_context(patch.object(subject, "SOURCE", self.source))
        # Accidental device, network or child execution fails even if a mock is omitted.
        self.patch.enter_context(patch.object(subject.socket, "create_connection", side_effect=AssertionError("network forbidden")))
        self.patch.enter_context(patch.object(subject.subprocess, "run", side_effect=AssertionError("process forbidden")))
        self.patch.enter_context(patch.object(subject.subprocess, "Popen", side_effect=AssertionError("process forbidden")))

    def test_build_validation_rejects_changed_source_dex_and_extra_manifest(self):
        self.assertEqual(self.manifest, subject.validate_build(self.build_dir))
        for field, value in (("sourceSha256", "0" * 64), ("dexSha256", "0" * 64), ("unexpected", True)):
            bad = dict(self.manifest, **{field: value})
            (self.build_dir / "build.json").write_text(json.dumps(bad))
            with self.assertRaisesRegex(subject.ObserverError, "stale_or_mismatched_build"):
                subject.validate_build(self.build_dir)

    def test_build_rejects_source_changed_during_compilation(self):
        def compiler(command, **kwargs):
            if command[0].endswith("javac"):
                self.source.write_text("// source changed after compilation\n")
            else:
                (self.root / "new-build" / "classes.dex").write_bytes(b"dex-from-earlier-source")
            return b""
        with patch.object(subject, "run", side_effect=compiler):
            with self.assertRaisesRegex(subject.ObserverError, "source.*changed|changed.*source|mismatched"):
                subject.build(self.root / "new-build")

    def test_state_rejects_invalid_bounds_types_fields_and_false_terminal(self):
        self.assertEqual(state(), subject.validate_state(json.dumps(state()), NONCE))
        self.assertIsNone(subject.validate_state(json.dumps(state(nonce=OTHER)), NONCE))
        cases = [state(schemaVersion=True), state(pid=True), state(pid=0), state(durationSeconds=351), state(elapsedMs=-1),
                 state(reason=PRIVATE), state(originals={}), state(unknown=1),
                 state(status="STOPPED", restoredVerified=False), state(status="STOPPED", childExited=False)]
        for invalid in cases:
            with self.subTest(invalid=invalid):
                with self.assertRaisesRegex(subject.ObserverError, "invalid_state"):
                    subject.validate_state(json.dumps(invalid), NONCE)
        with self.assertRaisesRegex(subject.ObserverError, "invalid_state"):
            subject.validate_state(b"x" * 8193, NONCE)

    def test_state_validation_also_applies_under_python_optimization(self):
        # Compile only this function with -O semantics; no subprocess or ADB involved.
        namespace = dict(vars(subject))
        exec(compile(inspect.getsource(subject.validate_state), "state-validation", "exec", optimize=2), namespace)
        with self.assertRaisesRegex(subject.ObserverError, "invalid_state"):
            namespace["validate_state"](json.dumps(state(status="STOPPED", restoredVerified=False)), NONCE)

    def test_launch_command_quotes_remote_and_contains_no_ota_or_redirected_log_file(self):
        remote = "/data/local/tmp/a b;$(PRIVATE).dex"
        command = subject.launch_command(remote, 3, NONCE)
        tokens = shlex.split(command)
        self.assertEqual("CLASSPATH=" + remote, tokens[0])
        self.assertEqual(["/system/bin/nohup", "/system/bin/app_process", "/system/bin", subject.CLASS,
                          "--duration-seconds", "3", "--nonce", NONCE, "</dev/null"], tokens[1:])
        self.assertNotIn("nohup.out", command)
        for duration, nonce in ((0, NONCE), (351, NONCE), (2, "bad;nonce")):
            with self.assertRaises(subject.ObserverError):
                subject.launch_command(remote, duration, nonce)

    def test_adb_refuses_without_existing_server_without_running_adb(self):
        with patch.object(subject.socket, "create_connection", side_effect=OSError), patch.object(subject, "run") as run:
            with self.assertRaisesRegex(subject.ObserverError, "existing_adb_server_required"):
                subject.Adb("127.0.0.1:5555")
            run.assert_not_called()

    def test_command_failures_do_not_expose_output_or_exception_details(self):
        failed = subprocess.CompletedProcess(["fake"], 1, PRIVATE.encode(), PRIVATE.encode())
        with patch.object(subject.subprocess, "run", return_value=failed):
            with self.assertRaisesRegex(subject.ObserverError, "^command_failed$"):
                subject.run(["fake"])
        failure = subprocess.TimeoutExpired([PRIVATE], 1, output=PRIVATE.encode())
        with patch.object(subject.subprocess, "run", side_effect=failure):
            with self.assertRaisesRegex(subject.ObserverError, "^command_unavailable_or_timed_out$"):
                subject.run(["fake"])

    def test_invalid_observation_bounds_are_rejected_before_adb(self):
        for duration, serial, stop_after in ((351, "emulator-5580", None), (3, "127.0.0.1:5555", 1)):
            args = SimpleNamespace(build_dir=self.build_dir, output_dir=self.root / "invalid", duration=duration,
                                   stop_after=stop_after, serial=serial)
            with patch.object(subject, "Adb") as adb:
                with self.assertRaisesRegex(subject.ObserverError, "invalid_observation_bounds"):
                    subject.observe(args)
                adb.assert_not_called()
            self.assertFalse(args.output_dir.exists())

    def test_adb_uses_existing_serial_and_only_nonce_stop_for_active_state(self):
        connection = Mock()
        connection.__enter__ = Mock(return_value=connection)
        connection.__exit__ = Mock(return_value=None)
        with patch.object(subject.socket, "create_connection", return_value=connection), \
                patch.object(subject, "run", side_effect=[b"device\n", b"2000\n"]) as run:
            adb = subject.Adb("emulator-5580")
        self.assertEqual("get-state", run.call_args_list[0].args[0][-1])
        self.assertEqual(["shell", "-T", "id -u"], run.call_args_list[1].args[0][-3:])
        with patch.object(adb, "state", return_value=state()), patch.object(adb, "call") as call:
            adb.stop(NONCE)
            self.assertEqual((NONCE + "\n").encode(), call.call_args.kwargs["input_data"])
            self.assertIn(subject.BASE + "/stop.request", call.call_args.args[-1])
        with patch.object(adb, "state", return_value=state(status="STOPPED")), patch.object(adb, "call") as call:
            adb.stop(NONCE)
            call.assert_not_called()

    def observe_fixture(self, chunks, *, mismatch=False, stop_error=False, property_mismatch=False, popen_error=False, push_error=False):
        args = SimpleNamespace(build_dir=self.build_dir, output_dir=self.root / "result", duration=3,
                               stop_after=None, serial="emulator-5580")
        wire = FakeWire(chunks)
        process = FakeProcess(wire)
        adb = FakeAdb(args.serial, "f" * 64 if mismatch else self.manifest["dexSha256"])
        adb.stop_error, adb.property_mismatch = stop_error, property_mismatch
        adb.push_error = push_error
        selector, clock = FakeSelector(), FakeClock()
        with ExitStack() as stack:
            stack.enter_context(patch.object(subject, "Adb", return_value=adb))
            popen = stack.enter_context(patch.object(subject.subprocess, "Popen",
                side_effect=OSError if popen_error else None, return_value=process))
            stack.enter_context(patch.object(subject.secrets, "token_hex", return_value=NONCE))
            stack.enter_context(patch.object(subject.selectors, "DefaultSelector", return_value=selector))
            stack.enter_context(patch.object(subject.os, "set_blocking"))
            stack.enter_context(patch.object(subject.os, "read", side_effect=wire.read))
            stack.enter_context(patch.object(subject.time, "monotonic", side_effect=clock.monotonic))
            stack.enter_context(patch.object(subject.time, "sleep", side_effect=clock.sleep))
            stdout = stack.enter_context(redirect_stdout(io.StringIO()))
            result = subject.observe(args)
        record = json.loads((args.output_dir / "run.json").read_text())
        return SimpleNamespace(result=result, record=record, args=args, process=process, adb=adb,
                               selector=selector, stdout=stdout.getvalue(), popen=popen)

    def test_observe_persists_only_sanitized_events_and_private_modes(self):
        private = (P + PRIVATE + "\n" + P.replace("DiagnosePresenter", "HttpApiService") + PRIVATE + "\n").encode()
        result = self.observe_fixture([private + EVENT[:31], EVENT[31:], b""])
        self.assertEqual(0, result.result)
        self.assertTrue(result.record["cleanupVerified"])
        self.assertTrue(result.record["observerStarted"])
        self.assertTrue(result.record["stagedDexRemoved"])
        self.assertEqual("removed", result.record["stagedDexStatus"])
        self.assertFalse(result.record["otaTriggered"])
        self.assertTrue(result.process.stdout.closed and result.selector.closed)
        for file in result.args.output_dir.iterdir():
            self.assertNotIn(PRIVATE, file.read_text())
            self.assertEqual(0o600, stat.S_IMODE(file.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(result.args.output_dir.stat().st_mode))
        self.assertIn("ESP_BOSCH_00100001", (result.args.output_dir / "events.log").read_text())
        self.assertNotIn(PRIVATE, result.stdout)
        self.assertTrue(any(command[:2] == ("rm", "-f") for command, _ in result.adb.commands))
        self.assertFalse(any(any(word in command for word in ("connect", "reconnect", "kill-server", "am", "install")) for command, _ in result.adb.commands))

    def test_host_stream_error_requests_stop_and_preserves_sanitized_report(self):
        result = self.observe_fixture([OSError(PRIVATE)])
        self.assertEqual(2, result.result)
        self.assertEqual("host_io_failed", result.record["error"])
        self.assertTrue(result.record["cleanupVerified"])
        self.assertTrue(result.process.terminated)
        self.assertTrue(any(command == ("stop", NONCE) for command, _ in result.adb.commands))
        self.assertNotIn(PRIVATE, (result.args.output_dir / "run.json").read_text())

    def test_lost_transport_never_claims_cleanup_or_removes_active_dex(self):
        result = self.observe_fixture([OSError(PRIVATE)], stop_error=True)
        self.assertEqual(2, result.result)
        self.assertFalse(result.record["cleanupVerified"])
        self.assertEqual("pending_cleanup", result.record["stagedDexStatus"])
        self.assertEqual("device_cleanup_not_verified", result.record["cleanupReadbackError"])
        self.assertTrue(result.process.terminated)
        self.assertFalse(any(command[:2] == ("rm", "-f") for command, _ in result.adb.commands))

    def test_property_readback_mismatch_prevents_success(self):
        result = self.observe_fixture([EVENT, b""], property_mismatch=True)
        self.assertEqual(2, result.result)
        self.assertFalse(result.record["cleanupVerified"])

    def test_staged_hash_mismatch_never_launches_helper(self):
        result = self.observe_fixture([], mismatch=True)
        result.popen.assert_not_called()
        self.assertEqual("staged_dex_mismatch", result.record["error"])
        self.assertFalse(result.record["observerStarted"])

    def test_prelaunch_failure_removes_only_its_unique_staged_dex(self):
        result = self.observe_fixture([], popen_error=True)
        self.assertEqual("host_io_failed", result.record["error"])
        self.assertTrue(any(command == ("rm", "-f", "/data/local/tmp/denza-ota-observer-" + NONCE + ".dex")
                            for command, _ in result.adb.commands))
        self.assertEqual("removed", result.record["stagedDexStatus"])

    def test_uncertain_push_preserves_staged_file_and_reports_pending_cleanup(self):
        result = self.observe_fixture([], push_error=True)
        result.popen.assert_not_called()
        self.assertEqual("command_failed", result.record["error"])
        self.assertEqual("pending_cleanup", result.record["stagedDexStatus"])
        self.assertFalse(result.record["stagedDexRemoved"])
        self.assertFalse(any(command[:2] == ("rm", "-f") for command, _ in result.adb.commands))


if __name__ == "__main__":
    unittest.main()
