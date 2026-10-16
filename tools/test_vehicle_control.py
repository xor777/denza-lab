"""Expanded remote tests. All shell and HTTP commands target fakes, never ADB."""
import json
from pathlib import Path
import re
import signal
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import vehicle_control as c
import vehicle_windows as w
from test_vehicle_windows import reply

OK = "Result: Parcel(00000001    '....')"


class FakeRemote(c.Remote):
    def __init__(self, directory):
        super().__init__("fake", log_dir=Path(directory))
        self.identity = ["CANFD", "168", "11111111-1111-1111-1111-111111111111"]
        self.live_identity = self.identity.copy()
        self.writes = []
        self.single_reply = OK
        self.pulse_reply = f"@start\n{OK}\n@stop\n{OK}"
        self.park = 1
        self.values = {name: 42 if kind == "float" else 1 for name, (_, _, kind) in c.CATALOG["properties"].items()}
        self.values[c.CATALOG["locks"]["config"]] = 5
        self.values[c.CATALOG["locks"]["thermal"]] = 0
        for door in c.CATALOG["doors"].values():
            self.values[door["position"]] = 2
            for key in ("obstacle", "protection"):
                self.values[door[key]] = 0
        for seat in c.CATALOG["seats"].values():
            for axis in seat["axes"].values():
                self.values[axis["config"]] = max(axis["config_values"])
            for control in seat["comfort"].values():
                self.values[control["config"]] = 3

    def shell(self, command, timeout=5):
        if command.startswith("finish()") or command.startswith("service call autoservice 6"):
            self.writes.append(command)
            result = self.pulse_reply if command.startswith("finish()") else self.single_reply
            if isinstance(result, Exception):
                raise result
            return result
        output = "\n".join(self.live_identity) + "\n"
        if command == w.IDENTITY:
            return output.strip()
        for key, txn in re.findall(r"echo @(\w+); service call autoservice ([57])", command):
            if key == "park":
                value = self.park
            elif key == "permit" or key.endswith("_thermal"):
                value = 0
            elif key.endswith("_initialized"):
                value = 1
            elif key.endswith("_percent"):
                value = 42
            else:
                value = self.values[key]
                if txn == "7":
                    value = struct.unpack("!I", struct.pack("!f", value))[0]
            output += f"@{key}\n{reply(value)}\n"
        return output.strip()


class ExpandedTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.remote = FakeRemote(self.tmp.name)

    def test_reads_only_and_float_placeholder_never_becomes_position(self):
        prop = c.CATALOG["seats"]["fl"]["axes"]["horizontal"]["position"]
        for invalid in (127.5, -1, float("nan"), float("inf")):
            self.remote.values[prop] = invalid
            state = self.remote.snapshot()
            axis = state["seats"]["fl"]["axes"]["horizontal"]
            self.assertIsNone(axis["percent"])
            self.assertTrue(axis["blocked"])
        self.assertEqual(self.remote.writes, [])

    def test_four_doors_use_distinct_fids_and_stop_value_three(self):
        for key, fid in (("fl", 1276178480), ("fr", 1276178483), ("rl", 1276178488), ("rr", 1276178491)):
            for action, value in (("open", 1), ("close", 2), ("stop", 3)):
                self.assertTrue(self.remote.act("door." + key, action)["accepted"])
                self.assertEqual(self.remote.writes[-1], f"service call autoservice 6 i32 1001 i32 {fid} i32 {value} null")

    def test_central_lock_values_match_stock_unlock_one_lock_two(self):
        for action, value in (("unlock", 1), ("lock", 2)):
            self.remote.act("locks", action)
            self.assertEqual(self.remote.writes[-1], f"service call autoservice 6 i32 1001 i32 1276141590 i32 {value} null")

    def test_door_and_lock_guards_are_fresh_but_stop_still_available(self):
        spec = c.CATALOG["doors"]["fl"]
        checks = [(spec["initialized"], 2), (spec["position"], 0), (spec["obstacle"], 1),
                  (spec["protection"], 1), (c.CATALOG["locks"]["config"], 9), (c.CATALOG["locks"]["thermal"], -10011)]
        for prop, value in checks:
            old = self.remote.values[prop]
            self.remote.values[prop] = value
            before = len(self.remote.writes)
            with self.assertRaises(w.ControlError):
                self.remote.act("door.fl", "open")
            self.assertEqual(len(self.remote.writes), before)
            self.assertTrue(self.remote.act("door.fl", "stop")["accepted"])
            self.remote.values[prop] = old

    def test_not_parked_blocks_every_new_movement_and_comfort_command(self):
        self.remote.snapshot()
        self.remote.park = 0
        for target, action in (("locks", "unlock"), ("door.fr", "open"), ("seat.fl.horizontal", "plus"), ("comfort.fl.heat", "1")):
            with self.assertRaises(w.ControlError):
                self.remote.act(target, action)
        self.assertEqual(self.remote.writes, [])
        self.assertTrue(self.remote.act("seat.fl.horizontal", "stop")["accepted"])

    def test_unknown_motion_config_or_initialization_disables_direction(self):
        axis = c.CATALOG["seats"]["fl"]["axes"]["horizontal"]
        for prop, value in ((axis["config"], 0), (axis["initialized"], 2), (axis["position"], 127.5)):
            old = self.remote.values[prop]
            self.remote.values[prop] = value
            with self.assertRaises(w.ControlError):
                self.remote.act("seat.fl.horizontal", "plus")
            self.remote.values[prop] = old
        self.assertEqual(self.remote.writes, [])

    def test_seat_step_is_one_android_script_with_native_stop_zero(self):
        result = self.remote.act("seat.fl.horizontal", "plus")
        self.assertTrue(result["accepted"])
        self.assertTrue(result["stop_accepted"])
        self.assertEqual(len(self.remote.writes), 1)
        script = self.remote.writes[0]
        self.assertIn("6 i32 1023 i32 1276137488 i32 1 null", script)
        self.assertIn("6 i32 1023 i32 1276137488 i32 0 null", script)
        self.assertIn("sleep 0.3", script)
        self.assertFalse(self.remote.pending_stops)

    def test_failed_or_missing_stop_latches_across_restart_without_auto_retry(self):
        self.remote.pulse_reply = f"@start\n{OK}\n@stop\nResult: Parcel(ffffd8e5    '....')"
        result = self.remote.act("seat.fl.horizontal", "plus")
        self.assertFalse(result["accepted"])
        self.assertEqual(len(self.remote.writes), 1)
        restarted = FakeRemote(self.tmp.name)
        for target in ("seat.fl.horizontal", "seat.fr.backrest"):
            with self.assertRaises(w.ControlError):
                restarted.act(target, "minus")
        self.assertEqual(restarted.writes, [])
        self.assertTrue(restarted.act("seat.fl.horizontal", "stop")["accepted"])
        self.assertFalse(FakeRemote(self.tmp.name).pending_stops)

    def test_start_timeout_is_uncertain_and_is_never_replayed(self):
        self.remote.pulse_reply = w.ControlError("timeout")
        result = self.remote.act("seat.fr.backrest", "minus")
        self.assertTrue(result["uncertain"])
        self.assertEqual(len(self.remote.writes), 1)
        self.assertIn("seat.fr.backrest", self.remote.pending_stops)

    def test_comfort_three_levels_driver_high_heat_uses_six(self):
        for key, seat in c.CATALOG["seats"].items():
            for kind in seat["comfort"]:
                for level in range(4):
                    self.remote.act(f"comfort.{key}.{kind}", str(level))
                    expected = 6 if (key, kind, level) == ("fl", "heat", 3) else level + 1
                    self.assertTrue(self.remote.writes[-1].endswith(f"i32 {expected} null"))

    def test_unavailable_or_unknown_comfort_does_not_write(self):
        spec = c.CATALOG["seats"]["tl"]["comfort"]["vent"]
        for prop, value in ((spec["config"], 0), (spec["state"], 0)):
            old = self.remote.values[prop]
            self.remote.values[prop] = value
            with self.assertRaises(w.ControlError):
                self.remote.act("comfort.tl.vent", "1")
            self.remote.values[prop] = old
        self.assertEqual(self.remote.writes, [])

    def test_allowlist_cannot_set_arbitrary_fid_or_pulse_duration(self):
        for target, action in (("door.all", "open"), ("seat.fl.horizontal", "60"), ("seat.fl;reboot.height", "plus"),
                               ("comfort.fl.heat", "6"), ("comfort.fl.heat", "stop"), ("locks", "open"), ("1276137488", "1")):
            with self.assertRaises(w.ControlError):
                self.remote.act(target, action)
        self.assertEqual(self.remote.writes, [])

    def test_changed_identity_blocks_stop_too(self):
        self.remote.live_identity[2] = "changed"
        for target in ("seat.fl.horizontal", "door.fl"):
            with self.assertRaises(w.ControlError):
                self.remote.act(target, "stop")
        self.assertEqual(self.remote.writes, [])

    def test_dicar_hash_must_match_before_control(self):
        with patch.object(w.Remote, "verify"), patch.object(self.remote, "shell", return_value=f"{c.DICAR_HASH}  {c.DICAR_PATH}"):
            self.remote.verify()
        with patch.object(w.Remote, "verify"), patch.object(self.remote, "shell", return_value="wrong"):
            with self.assertRaises(w.ControlError):
                self.remote.verify()

    def test_shell_cleanup_runs_after_rejected_start_and_signal(self):
        # Real shell, fake commands. Verifies EXIT and TERM, with no service/adb executable.
        script = c.pulse_script("printf 'started\\n'; false", "printf 'stopped\\n'")
        result = subprocess.run(["/bin/sh", "-c", script], capture_output=True, text=True, timeout=3)
        self.assertEqual(result.stdout, "@start\nstarted\n@stop\nstopped\n")
        process = subprocess.Popen(["/bin/sh", "-c", script], stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(process.stdout.readline(), "@start\n")
            process.send_signal(signal.SIGTERM)
            tail, _ = process.communicate(timeout=3)
            self.assertEqual(tail.count("@stop"), 1)
            self.assertIn("stopped", tail)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()


if __name__ == "__main__":
    unittest.main()
