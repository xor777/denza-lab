"""Offline boundary tests: fake device/helper only, never ADB or a network."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("ecu_map_host", Path(__file__).with_name("run_ecu_map_metadata.py"))
host = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(host)
NONCE = "1" * 32


def result():
    return {"state": "RESULT", "profile": None, "evidence": "server_candidate_only",
            "eligibleForProductAllowlist": False, "ecus": [{
                "ecuId": "ECM_TEST", "ecuDiaId": "720/728", "ecuReprogramType": "canfd",
                "ecuDId": None, "route": {"kind": "can", "requestId": 0x720,
                "responseId": 0x728, "physicalBus": None}, "evidence": "server_candidate_only",
                "eligibleForProductAllowlist": False}]}


class FakeHelper:
    def __init__(self, device):
        self.device = device
        self.count = 0

    def message(self, timeout):
        self.count += 1
        self.device.event("ready" if self.count == 1 else "result")
        return self.device.ready if self.count == 1 else result()

    def go(self, nonce):
        self.device.event("GO")
        if self.device.events.index("terminal") > self.device.events.index("GO"):
            raise AssertionError("GO before terminal watchdog")

    def finish(self): self.device.event("helper_exit")
    def close(self): self.device.event("helper_close")


class FakeDevice:
    def __init__(self, fail=None):
        self.events = []
        self.fail = fail
        self.ready = {"state": "INPUTS_READY", "nonce": NONCE}

    def event(self, name):
        self.events.append(name)
        if name == self.fail:
            raise host.Refused("injected_failure")

    def preflight(self): self.event("preflight"); return "1234"
    def claim(self, nonce, pid): self.event("claim")
    def stage(self, *args): self.event("stage"); return False
    def arm(self): self.event("armed")
    def start(self): self.event("START")
    def launch_helper(self, nonce): self.event("helper"); return FakeHelper(self)

    def close_export(self, pid):
        for event in ("STOP", "ports_closed", "threads_idle", "terminal"):
            self.event(event)

    def identity(self): self.event("post_identity")
    def release(self, *args): self.event("release")


class BoundaryTests(unittest.TestCase):
    def execute(self, device):
        return host.run(device, Path("unused.dex"), "a" * 64, NONCE)

    def test_ordering_has_no_early_go_and_one_start(self):
        device = FakeDevice()
        output = self.execute(device)
        self.assertEqual(output["status"], "complete")
        self.assertEqual(device.events, ["preflight", "claim", "stage", "armed", "START",
                         "helper", "ready", "STOP", "ports_closed", "threads_idle", "terminal",
                         "GO", "result", "helper_exit", "helper_close", "post_identity", "release"])

    def test_preflight_failure_does_not_stop_unowned_bydlog(self):
        device = FakeDevice("preflight")
        output = self.execute(device)
        self.assertEqual(device.events, ["preflight"])
        self.assertFalse(output["goSent"])

    def test_closed_ports_do_not_release_other_readers_directory(self):
        device = host.Device()
        def shell(command, timeout=4):
            if command == "id -u": return "2000"
            if command.startswith("getprop"): return "170\nCANFD"
            if command.startswith("test ! -e " + host.LEASE): return ""
            if command.startswith("if [ -e " + device.EXPORT_DIRECTORY): return "occupied"
            raise AssertionError("Unexpected device operation: " + command)
        with patch.object(device, "command", return_value="device"), \
                patch.object(device, "identity"), patch.object(device, "shell", side_effect=shell), \
                patch.object(device, "claim", side_effect=AssertionError("Must not claim")), \
                patch.object(device, "close_export", side_effect=AssertionError("Must not STOP")):
            output = self.execute(device)
        self.assertEqual(output["error"], "shared_export_directory_occupied")
        self.assertFalse(output["goSent"])

    def test_atomic_directory_claim_rejects_reader_starting_after_preflight(self):
        # Execute only the real final mkdir fragment in a local temporary
        # directory. The other reader keeps ownership while ports are closed.
        with tempfile.TemporaryDirectory() as directory:
            shared = Path(directory) / "logtool"
            device = host.Device()
            device.EXPORT_DIRECTORY = str(shared)
            digest = "a" * 64
            def shell(command, timeout=4):
                if command.startswith("sha256sum "):
                    return digest + " " + host.LEASE + "/classes.dex"
                if command.startswith("umask 077; printf %s "): return ""
                if command == "mkdir " + str(shared) + " && echo created":
                    completed = subprocess.run(["sh", "-c", command], capture_output=True, text=True)
                    if completed.returncode: raise host.Refused("adb_command_failed")
                    return completed.stdout.strip()
                raise AssertionError("Unexpected device operation: " + command)
            with patch.object(device, "command"), patch.object(device, "shell", side_effect=shell):
                shared.mkdir()  # Another reader acquired it after preflight.
                marker = shared / "other-owner"
                marker.write_text("watchdog still running")
                with self.assertRaises(host.Refused):
                    device.stage(Path("unused.dex"), digest, NONCE, "1234")
                self.assertEqual(marker.read_text(), "watchdog still running")
                marker.unlink(); shared.rmdir()  # The fixture's owner retires.
                self.assertTrue(device.stage(Path("unused.dex"), digest, NONCE, "1234"))
                self.assertTrue(shared.is_dir())

    def test_wrong_nonce_never_go_but_closes_owned_export(self):
        device = FakeDevice()
        device.ready["nonce"] = "2" * 32
        output = self.execute(device)
        self.assertNotIn("GO", device.events)
        self.assertIn("STOP", device.events)
        self.assertTrue(output["exportClosed"])

    def test_each_missing_gate_refuses_go_retains_lease(self):
        for failure in ("STOP", "ports_closed", "threads_idle", "terminal"):
            with self.subTest(failure=failure):
                device = FakeDevice(failure)
                output = self.execute(device)
                self.assertNotIn("GO", device.events)
                self.assertNotIn("release", device.events)
                self.assertFalse(output["exportClosed"])

    def test_ambiguous_start_is_cleaned_but_unowned_stage_is_not_stopped(self):
        device = FakeDevice("START")
        self.execute(device)
        self.assertIn("STOP", device.events)
        self.assertNotIn("GO", device.events)
        device = FakeDevice("stage")
        self.execute(device)
        self.assertNotIn("STOP", device.events)

    def test_no_retries_after_metadata_failure(self):
        device = FakeDevice("result")
        output = self.execute(device)
        self.assertEqual(device.events.count("GO"), 1)
        self.assertEqual(device.events.count("helper"), 1)
        self.assertNotIn("result", output)
        self.assertTrue(output["exportClosed"])

    def test_changed_apk_or_unreleased_lease_precludes_complete(self):
        for failure in ("post_identity", "release"):
            device = FakeDevice(failure)
            self.assertEqual(self.execute(device)["status"], "incomplete")

    def test_output_rejects_private_extra_and_fabricated_route(self):
        for mutation in (lambda r: r.update(token="PRIVATE"),
                         lambda r: r["ecus"][0].update(ecuSoftVer="PRIVATE"),
                         lambda r: r["ecus"][0]["route"].update(requestId=0x7E0),
                         lambda r: r.update(eligibleForProductAllowlist=True)):
            value = result()
            mutation(value)
            with self.assertRaises(host.Refused): host.validate_result(value)
        self.assertEqual(host.validate_result(result()), result())

    def test_ethernet_is_not_can_and_null_fields_are_supported(self):
        value = result()
        value["ecus"][0].update(ecuReprogramType="ethernet", ecuDiaId="1002/0E80", logicAddress="1003",
                                 route={"kind": "ethernet", "pairTarget": 0x1002,
                                        "pairClient": 0xE80, "target": 0x1003})
        self.assertEqual(host.validate_result(value), value)

    def test_stock_doip_logical_address_does_not_invent_a_pair(self):
        for pair in (None, ""):
            value = result()
            value["ecus"][0].update(ecuReprogramType="ethernet", ecuDiaId=pair, logicAddress="071A",
                                   route={"kind": "ethernet", "pairTarget": None,
                                          "pairClient": None, "target": 0x71A})
            self.assertEqual(host.validate_result(value), value)
            value["ecus"][0]["logicAddress"] = ""
            with self.assertRaises(host.Refused):
                host.validate_result(value)
        value = result()
        value["ecus"][0]["ecuDiaId"] = ""
        with self.assertRaises(host.Refused):
            host.validate_result(value)

    def test_duplicate_json_error_text_and_non_json_never_propagate(self):
        for raw in (b'{"state":"RESULT","state":"PRIVATE"}',
                    b'{"state":"ERROR","code":"PRIVATE TOKEN"}', b'PRIVATE TOKEN'):
            with self.assertRaises(host.Refused) as caught: host.parse_message(raw)
            self.assertNotIn("PRIVATE", str(caught.exception))

    def test_only_allowlisted_error_code_and_stage_are_retained(self):
        for stage, code in (("input_cert", "missing_input0"), ("https", "http_auth"),
                            ("projection", "application_registration"), ("input_validate", "token_missing"),
                            ("https", "http_headers_limit"), ("https", "http_header_syntax"),
                            ("https", "http_header_duplicate")):
            raw = json.dumps({"state": "ERROR", "stage": stage, "code": code}).encode()
            with self.assertRaises(host.HelperFailure) as caught:
                host.parse_message(raw)
            self.assertEqual((caught.exception.stage, caught.exception.code), (stage, code))
        for extra in ({"stage": "PRIVATE", "code": "http_auth"},
                      {"stage": "https", "code": "PRIVATE"},
                      {"stage": ["https"], "code": "http_auth"}):
            raw = json.dumps(dict(state="ERROR", **extra)).encode()
            with self.assertRaises(host.Refused) as caught:
                host.parse_message(raw)
            self.assertNotIsInstance(caught.exception, host.HelperFailure)

    def test_stock_ecu_identifiers_preserve_ascii_spaces(self):
        for ecu_id in ("HTH-2021_rear CPD radar_Valeo_00100001",
                       "HTH-2021_rear CPD radar_Mindcruise_00100001",
                       "HTH-2021_front CPD radar_Valeo_00200001",
                       "HTH-2021_front CPD radar_Mindcruise_00100001",
                       "HTH-2021_ECP_FULIN PM_00100001"):
            value = result()
            value["ecus"][0]["ecuId"] = ecu_id
            self.assertEqual(host.validate_result(value)["ecus"][0]["ecuId"], ecu_id)
        for ecu_id in (" ", "BAD\tID", "BAD\nID", "BAD\u00a0ID", "BAD/ID"):
            value = result()
            value["ecus"][0]["ecuId"] = ecu_id
            with self.assertRaises(host.Refused):
                host.validate_result(value)

    def test_nan_and_excessive_json_nesting_are_fixed_errors(self):
        for raw in (b'{"value":NaN}', b'[' * 2000 + b'0' + b']' * 2000):
            with self.assertRaises(host.Refused): host.parse_message(raw)

    def test_no_adb_process_when_existing_server_is_absent(self):
        with patch.object(host.socket, "create_connection", side_effect=OSError("PRIVATE")), \
                patch.object(host.subprocess, "run", side_effect=AssertionError("No adb")):
            with self.assertRaises(host.Refused) as caught:
                host.Device().command(["get-state"])
            self.assertEqual(str(caught.exception), "existing_adb_server_unavailable")

    def test_ports_require_both_tables_and_include_v6(self):
        raw = "sl local_address rem_address st\n0: 00000000:B4F1 0:0 0A\nsl local_address rem_address st\n"
        self.assertEqual(host.listener_ports(raw), {46321})
        with self.assertRaises(host.Refused): host.listener_ports(raw.split("sl local_address", 1)[1])

    def test_scripts_parse_and_watchdog_cannot_stop_after_terminal(self):
        transition, watchdog = host.scripts(NONCE, "1234")
        for source in (transition, watchdog):
            subprocess.run(["sh", "-n"], input=source, text=True, check=True, capture_output=True)
        self.assertIn('mkdir transition.lock', transition)
        self.assertIn('"$(pidof com.byd.bydlogtool)" = "1234"', transition)
        self.assertIn('"$(cat nonce 2>/dev/null)" = "' + NONCE + '"', transition)
        self.assertNotIn(host.STOP, watchdog)
        tail = watchdog[watchdog.index('echo cancelled > watchdog'):].split("done", 1)[0]
        self.assertIn("exit 0", tail)
        self.assertIn('"$n" -lt 30', watchdog)
        self.assertIn('touch deadline', watchdog)
        self.assertIn('while true', watchdog)

    def test_delayed_start_cannot_outlive_watchdog_compensation(self):
        # Execute the generated shell state machine with a fake am/pidof and
        # accelerated clock, on the host. Neither adb nor real am is invoked.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lease = root / "lease"
            lease.mkdir()
            for name, value in (("nonce", NONCE), ("phase", "prepared"), ("bydlog.start", "111")):
                (lease / name).write_text(value + "\n")
            stat = root / "procstat"
            stat.write_text("1234 (fake app) S " + "0 " * 18 + "111\n")
            transition, watchdog = host.scripts(NONCE, "1234")
            # Functions avoid cold executable-script startup delays on macOS.
            # Only fixed Android paths in the state machine are substituted.
            # Begin the accelerated clock only after START enters its am call.
            commands = '''
pidof() { echo 1234; }
sleep() {
  while [ ! -e "$FIXTURE/start_entered" ] && [ ! -e "$FIXTURE/allow_start" ]; do /bin/sleep 0.01; done
  /bin/sleep 0.01
}
am() {
  case "$*" in
    *START_SOCKET_SERVER*)
      echo started > "$FIXTURE/start_entered"
      while [ ! -e "$FIXTURE/allow_start" ]; do /bin/sleep 0.01; done
      echo START >> "$FIXTURE/events"; touch "$FIXTURE/listener";;
    *STOP_SOCKET_SERVER*)
      echo STOP >> "$FIXTURE/events"; rm -f "$FIXTURE/listener";;
  esac
}
'''
            for name, text in (("transition.sh", transition), ("watchdog.sh", watchdog)):
                (lease / name).write_text(commands + text.replace(host.LEASE, str(lease)).replace("/proc/1234/stat", str(stat)))
            env = dict(os.environ, PATH="/usr/bin:/bin", FIXTURE=str(root))
            def wait_for(predicate):
                until = time.monotonic() + 3
                while not predicate():
                    if time.monotonic() > until: self.fail("fixture deadline")
                    time.sleep(0.01)
            watch = subprocess.Popen(["sh", str(lease / "watchdog.sh")], env=env,
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            start = None
            try:
                wait_for(lambda: (lease / "watchdog").exists() and (lease / "watchdog").read_text().strip() == "armed")
                start = subprocess.Popen(["sh", str(lease / "transition.sh"), "start"], env=env,
                                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                wait_for(lambda: (root / "start_entered").exists())
                wait_for(lambda: (lease / "deadline").exists())
                self.assertIsNone(watch.poll(), "watchdog must stay alive during pending START")
                self.assertTrue((lease / "transition.lock").exists())
                self.assertNotEqual((lease / "watchdog").read_text().strip(), "deadline_stopped")
                (root / "allow_start").touch()
                self.assertEqual(start.wait(timeout=3), 19)
                self.assertEqual(watch.wait(timeout=3), 0)
                self.assertEqual((root / "events").read_text().splitlines(), ["START", "STOP"])
                self.assertFalse((root / "listener").exists())
                self.assertEqual((lease / "watchdog").read_text().strip(), "deadline_stopped")
                self.assertEqual((lease / "phase").read_text().strip(), "stopped")
            finally:
                (root / "allow_start").touch()
                for proc in (start, watch):
                    if proc is not None and proc.poll() is None:
                        try:
                            proc.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            proc.terminate(); proc.wait(timeout=3)

    def test_default_plan_is_offline(self):
        output = io.StringIO()
        with patch.object(host.subprocess, "run", side_effect=AssertionError("No processes")), \
                patch.object(host.subprocess, "Popen", side_effect=AssertionError("No processes")), \
                contextlib.redirect_stdout(output):
            self.assertEqual(host.main([]), 0)
        self.assertFalse(json.loads(output.getvalue())["adbInvoked"])

    def test_reused_pid_cannot_receive_stop(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "nonce").write_text(NONCE + "\n")
            (root / "phase").write_text("active\n")
            (root / "bydlog.start").write_text("111\n")
            stat = root / "procstat"
            stat.write_text("1234 (same pid new process) S " + "0 " * 18 + "222\n")
            transition, _ = host.scripts(NONCE, "1234")
            transition = transition.replace(host.LEASE, str(root)).replace("/proc/1234/stat", str(stat))
            # Functions override only fixture commands; no real app or ADB.
            transition = transition.replace("umask 077", "pidof() { echo 1234; }\nam() { touch unexpected; }\numask 077")
            completed = subprocess.run(["sh", "-s", "stop"], input=transition, text=True, capture_output=True)
            self.assertEqual(completed.returncode, 13)
            self.assertFalse((root / "unexpected").exists())
            self.assertEqual((root / "phase").read_text().strip(), "active")

    def test_wrong_hash_stops_before_device_or_output_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "classes.dex"
            artifact.write_bytes(b"fixture")
            output = Path(directory) / "out"
            with patch.object(host, "Device", side_effect=AssertionError("No device")), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                host.main(["--run-acknowledged", "--artifact", str(artifact),
                           "--artifact-sha256", "0" * 64, "--output", str(output)])
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
