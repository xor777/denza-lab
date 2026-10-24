"""Host-runner tests with a fake adb: preconditions gate, the per-scenario push/launch/pull/table
cycle, answer decoding, and restore (uninstall). No real device is ever contacted."""
import base64
import json
import re
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.adas_bridge import parked_session as ps
from tools.adas_bridge import stock


# ---- a tiny protobuf builder for the answer payloads the fake ADAS returns ----

def _varint(n):
    out = bytearray()
    n &= (1 << 64) - 1
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _vfield(num, val):
    return _varint((num << 3) | 0) + _varint(val)


def _mfield(num, inner):
    return _varint((num << 3) | 2) + _varint(len(inner)) + inner


def _pilot(dnp, icc, acc):
    struct = _vfield(3, acc) + _vfield(4, icc) + _vfield(5, dnp)
    return _mfield(1, struct)


def _noa_mode(mode):
    return _mfield(1, _vfield(3, mode))


def _alarm(reason, notice):
    return _mfield(1, _vfield(3, reason) + _vfield(7, notice))


def _path_match(path_id):
    route_info = _vfield(1, path_id)
    struct = _mfield(4, route_info)
    return _mfield(1, struct)


class CompletedLike:
    def __init__(self, stdout=b"", returncode=0, stderr=b""):
        self.stdout, self.returncode, self.stderr = stdout, returncode, stderr


class FakeAdb:
    """Routes fixed adb command shapes to canned outputs; records the commands it saw."""

    def __init__(self, *, stock_running=False, stock_enabled=3, answers_jsonl=b"", navi_value=1,
                 navi_read_reply=None, fail_result_read=False, timeout_result_read=False, arm_bytes=b""):
        self.stock_running = stock_running
        self.stock_enabled = stock_enabled
        self.answers_jsonl = answers_jsonl
        self.navi_value = navi_value
        self.navi_read_reply = navi_read_reply
        self.fail_result_read = fail_result_read
        self.timeout_result_read = timeout_result_read
        self.commands = []
        self.events = []          # ordered: ("navi", shell-command) / ("am", ...) / ("result", ...)
        self.private_files = {}
        self.arm_bytes = arm_bytes

    def __call__(self, args, *, input=None, capture_output=True, timeout=None, check=False):
        self.commands.append(list(args))
        joined = " ".join(args)
        if "service call autoservice" in joined:
            command = args[-1]
            self.events.append(("navi", command))
            if command.startswith("service call autoservice 5 "):
                if self.navi_read_reply is not None:
                    return CompletedLike(self.navi_read_reply)
                return CompletedLike(("Result: Parcel(00000000 %08x   '........')\n" % self.navi_value).encode())
            self.navi_value = int(command.split()[-2])
            return CompletedLike(b"Result: Parcel(00000000 00000001   '........')\n")
        if "player.jsonl" in joined and "head -c" in joined:
            self.events.append(("result", "read"))
            if self.timeout_result_read:
                raise subprocess.TimeoutExpired(args, timeout)
            if self.fail_result_read:
                return CompletedLike(b"", returncode=1, stderr=b"run-as: boom")
        if "install" in args:
            return CompletedLike()
        if "uninstall" in args:
            return CompletedLike()
        if "/proc/uptime" in joined:
            return CompletedLike(b"1000.0 500.0\n")
        if "PID,NAME" in joined:
            body = "PID NAME\n123 system_server\n"
            if self.stock_running:
                body += "456 com.byd.launchermap\n"
            return CompletedLike(body.encode())
        if "dumpsys package com.byd.launchermap" in joined:
            return CompletedLike(("  User 0: ceDataInode=1 installed=true enabled=%d\n" % self.stock_enabled).encode())
        if "run-as" in joined and ps.NAVIGATOR in joined:
            return CompletedLike(self.arm_bytes)
        if "run-as" in joined and ps.PACKAGE in joined:
            remote = shlex.split(args[-1])
            script = remote[-1]
            # read of player.jsonl vs writes/removes
            if "player.jsonl" in joined and "head -c" in joined:
                return CompletedLike(self.answers_jsonl)
            if "head -c" in script:
                path = re.search(r"\[ -f ([^ ]+) \]", script).group(1)
                return CompletedLike(self.private_files.get(path, b""))
            match = re.search(r"printf %s ([^ ]+) \| base64 -d > ([^ ]+)\.tmp", script)
            if match:
                self.private_files[match.group(2)] = base64.b64decode(match.group(1))
            return CompletedLike(b"")
        if "am start" in joined:
            self.events.append(("am", "start"))
            return CompletedLike(b"Status: ok\n")
        return CompletedLike(b"")


def _answers_jsonl():
    rows = []

    def answer(service, ident, payload, t):
        topic = ps._event_topic(service, ident)
        rows.append({"kind": "answer", "topic": topic,
                     "base64": base64.b64encode(payload).decode(), "elapsedMs": t, "wallMs": t})

    answer(13, 0x8002, _pilot(0, 0, 2), 1000)
    answer(13, 0x8002, _pilot(2, 2, 2), 2000)      # DNP 2 = NOA offered
    answer(13, 0x8005, _noa_mode(2), 2000)         # city NOA family
    answer(44, 0x8002, stock.path_select_confirm(1700123, 200), 1500)
    answer(44, 0x8001, _path_match(1700123), 1600)
    answer(13, 0x8003, _alarm(19, 0), 2100)
    return ("\n".join(json.dumps(r) for r in rows) + "\n").encode()


class ParkedSessionTest(unittest.TestCase):
    def _scenario_dir(self):
        tmp = Path(tempfile.mkdtemp())
        for sid in ("H0", "H1"):
            (tmp / f"scenario-{sid}.json").write_text(json.dumps({"id": sid, "durationS": 2}))
        return tmp

    def test_plan_makes_no_device_calls(self):
        tmp = self._scenario_dir()
        out = ps.main(["plan", "--scenario-dir", str(tmp), "--order", "H0", "H1"])
        self.assertEqual(out, 0)

    def test_run_happy_path(self):
        fake = FakeAdb(answers_jsonl=_answers_jsonl())
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        tmp = self._scenario_dir()
        prompts = []
        receipt = ps.run(device, __file__, tmp, ["H0", "H1"], prompts.append, sleep=lambda s: None)
        self.assertTrue(receipt["installed"])
        self.assertTrue(receipt["uninstalled"])
        self.assertEqual(receipt["stockBefore"]["enabledState"], 3)
        self.assertEqual(receipt["stockAfter"]["enabledState"], 3)
        self.assertFalse(receipt["armBefore"])
        ids = [s["id"] for s in receipt["scenarios"]]
        self.assertEqual(ids, ["H0", "H1"])
        h0 = receipt["scenarios"][0]["summary"]
        offered = any((p["dnp"] in (1, 2)) for p in h0["pilot"])
        self.assertTrue(offered, "DNP 2 should be decoded as offered")
        self.assertEqual(h0["selected"][-1]["statusCode"], 200)
        self.assertEqual(h0["selected"][-1]["pathIDResp"], 1700123)
        self.assertTrue(h0["pathMatch"])
        self.assertIn(2, [m["noaMode"] for m in h0["noaModes"]])
        # the owner was prompted once per scenario, in Russian
        self.assertEqual(len(prompts), 2)
        self.assertIn("подрулевой", prompts[0])
        # an install + uninstall happened
        self.assertTrue(any("install" in c for c in fake.commands))
        self.assertTrue(any("uninstall" in c for c in fake.commands))

    def test_comparison_table_renders(self):
        summary = ps.decode_answers(json.loads("[" + ",".join(
            l for l in _answers_jsonl().decode().splitlines() if l.strip()) + "]"))
        table = ps.comparison_table("H0", summary)
        self.assertIn("DNP/ICC/ACC timeline", table)
        self.assertIn("NOA offered (DNP 1/2 seen): True", table)
        self.assertIn("MMTPathSelected: pathIDResp=1700123 statusCode=200", table)
        self.assertIn("noaMode values: [2]", table)

    def test_run_aborts_when_stock_running(self):
        fake = FakeAdb(stock_running=True)
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        tmp = self._scenario_dir()
        receipt = ps.run(device, __file__, tmp, ["H0"], lambda m: None, sleep=lambda s: None)
        self.assertIn("aborted", receipt)
        self.assertNotIn("installed", receipt)
        self.assertFalse(any("install" in c for c in fake.commands))

    def test_run_aborts_when_stock_enabled(self):
        fake = FakeAdb(stock_running=False, stock_enabled=0)
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        tmp = self._scenario_dir()
        receipt = ps.run(device, __file__, tmp, ["H0"], lambda m: None, sleep=lambda s: None)
        self.assertIn("aborted", receipt)

    def test_bad_serial_rejected(self):
        with self.assertRaises(ps.SessionError):
            ps.Device("adb", "-bad serial", runner=FakeAdb())

    def test_remote_shell_receives_one_complete_script_and_write_is_verified(self):
        fake = FakeAdb()
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        data = b'{"scenario":"H0"}\n'
        device.push_private("files/scenario.json", data)
        self.assertEqual(fake.private_files["files/scenario.json"], data)
        self.assertEqual(len(fake.commands), 2, "write must be followed by readback")
        for command in fake.commands:
            self.assertEqual(command[3], "exec-out")
            self.assertEqual(len(command), 5, "ADB receives a single quoted remote command")
            remote = shlex.split(command[4])
            self.assertEqual(remote[:4], ["run-as", ps.PACKAGE, "sh", "-c"])
            self.assertEqual(len(remote), 5, "shell operators must stay within the script argument")

    def test_lost_write_and_unknown_arm_fail_closed(self):
        empty = lambda *args, **kwargs: CompletedLike()
        device = ps.Device("adb", "127.0.0.1:5555", runner=empty)
        with self.assertRaisesRegex(ps.SessionError, "readback mismatch"):
            device.push_private("files/scenario.json", b"{}")
        failed = lambda *args, **kwargs: CompletedLike(returncode=1, stderr=b"run-as failed")
        device = ps.Device("adb", "127.0.0.1:5555", runner=failed)
        with self.assertRaisesRegex(ps.SessionError, "arm state unreadable"):
            device.navigator_arm_valid(1000)
        malformed = lambda *args, **kwargs: CompletedLike(stdout=b"not JSON")
        device = ps.Device("adb", "127.0.0.1:5555", runner=malformed)
        with self.assertRaisesRegex(ps.SessionError, "arm state malformed"):
            device.navigator_arm_valid(1000)

    def test_decode_answers_empty(self):
        self.assertEqual(ps.decode_answers([])["pilot"], [])

    def test_listen_allows_stock_but_still_refuses_a_live_navigator_arm(self):
        tmp = self._scenario_dir()
        (tmp / "scenario-LISTEN.json").write_text(json.dumps({"id":"LISTEN","listenOnly":True,"durationS":2}))
        fake = FakeAdb(stock_running=True, stock_enabled=0, answers_jsonl=_answers_jsonl())
        receipt = ps.run(ps.Device("adb","car",runner=fake), __file__, tmp, ["LISTEN"], lambda m:None, sleep=lambda s:None)
        self.assertNotIn("aborted",receipt)
        self.assertTrue(receipt["stockAfter"]["running"])
        self.assertEqual(receipt["stockAfter"]["enabledState"],0)
        self.assertFalse(any("service call autoservice" in " ".join(c) for c in fake.commands))
        armed = FakeAdb(stock_running=True,stock_enabled=0,arm_bytes=b'{"expiresAtElapsedMs":2000000}')
        receipt = ps.run(ps.Device("adb","car",runner=armed),__file__,tmp,["LISTEN"],lambda m:None,sleep=lambda s:None)
        self.assertIn("aborted",receipt)
        self.assertFalse(any("install" in c for c in armed.commands))

    def test_listen_never_changes_a_side_channel(self):
        for key in ("naviSt", "instrumentNaviStatus"):
            tmp = self._scenario_dir()
            (tmp / "scenario-LISTEN.json").write_text(json.dumps(
                {"id":"LISTEN","listenOnly":True,"durationS":2,key:{"value":2}}))
            fake = FakeAdb(stock_running=True, stock_enabled=0, answers_jsonl=_answers_jsonl())
            receipt = ps.run(ps.Device("adb","car",runner=fake), __file__, tmp, ["LISTEN"], lambda m:None, sleep=lambda s:None)
            self.assertEqual(receipt["stoppedAt"], "LISTEN", key)
            self.assertIn("LISTEN cannot change", receipt["scenarios"][0]["error"], key)
            self.assertFalse(any("service call autoservice" in " ".join(c) for c in fake.commands), key)


READ_CMD = "service call autoservice 5 i32 1023 i32 1285554251"
WRITE2_CMD = "service call autoservice 6 i32 1023 i32 1285554251 i32 2 null"
RESTORE1_CMD = "service call autoservice 6 i32 1023 i32 1285554251 i32 1 null"


class NaviStRunnerTest(unittest.TestCase):
    """H6: the runner owns NaviSt over adb shell, in the stock order, and always restores it."""

    def _h6(self):
        tmp = Path(tempfile.mkdtemp())
        path = tmp / "scenario-H6.json"
        path.write_text(json.dumps({"id": "H6", "durationS": 2, "naviSt": {"value": 2},
                                    "navStatus": {"preRoutePhase": [{"status": 100, "holdS": 2},
                                                                    {"status": 106, "holdS": 3}]}}))
        return tmp, path

    def test_exact_command_strings(self):
        self.assertEqual(ps.navi_st_read_command(), READ_CMD)
        self.assertEqual(ps.navi_st_write_command(2), WRITE2_CMD)

    def test_read_write_play_restore_order(self):
        fake = FakeAdb(answers_jsonl=_answers_jsonl(), navi_value=1)
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        _, path = self._h6()
        sleeps = []
        out = ps.run_scenario(device, path, {}, lambda m: None, sleep=sleeps.append)
        self.assertEqual(fake.events, [("navi", READ_CMD), ("am", "start"), ("navi", READ_CMD), ("navi", WRITE2_CMD), ("navi", READ_CMD),
                                       ("result", "read"), ("navi", RESTORE1_CMD), ("navi", READ_CMD)])
        self.assertEqual(sleeps[0], 5.0)       # write at the end of the 100/106 pre-route phase
        navi = out["naviSt"]
        self.assertEqual((navi["before"], navi["written"], navi["restored"], navi["after"]), (1, 2, 1, 1))
        self.assertTrue(navi["restoreVerified"])
        self.assertIn("NaviSt: before=1 written=2", ps.comparison_table("H6", out["summary"], navi))

    def test_restore_on_error(self):
        fake = FakeAdb(navi_value=0, fail_result_read=True)
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        _, path = self._h6()
        with self.assertRaises(ps.SessionError) as raised:
            ps.run_scenario(device, path, {}, lambda m: None, sleep=lambda s: None)
        restore0 = "service call autoservice 6 i32 1023 i32 1285554251 i32 0 null"
        self.assertEqual(fake.events[-2:], [("navi", restore0), ("navi", READ_CMD)])
        self.assertTrue(raised.exception.parked_navi["restoreVerified"])

    def test_restore_on_timeout(self):
        fake = FakeAdb(navi_value=1, timeout_result_read=True)
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        _, path = self._h6()
        with self.assertRaises(subprocess.TimeoutExpired):
            ps.run_scenario(device, path, {}, lambda m: None, sleep=lambda s: None)
        self.assertEqual(fake.events[-2:], [("navi", RESTORE1_CMD), ("navi", READ_CMD)])
        self.assertEqual(fake.navi_value, 1)

    def test_restore_on_keyboard_interrupt(self):
        fake = FakeAdb(navi_value=1)
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        _, path = self._h6()
        calls = []

        def sleep(seconds):
            calls.append(seconds)
            if len(calls) == 2:              # owner presses Ctrl-C during the play
                raise KeyboardInterrupt
        with self.assertRaises(KeyboardInterrupt):
            ps.run_scenario(device, path, {}, lambda m: None, sleep=sleep)
        self.assertEqual(fake.events[-2:], [("navi", RESTORE1_CMD), ("navi", READ_CMD)])

    def test_skip_when_read_fails(self):
        fake = FakeAdb(navi_read_reply=b"Result: Parcel(ffffd8e5 '....')\n")
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        tmp, path = self._h6()
        out = ps.run_scenario(device, path, {}, lambda m: None, sleep=lambda s: None)
        self.assertTrue(out["notRun"])
        self.assertEqual(fake.events, [("navi", READ_CMD)])   # no write, no play
        self.assertIn("NOT RUN", ps.comparison_table("H6", out["summary"], out["naviSt"]))
        receipt = ps.run(ps.Device("adb", "127.0.0.1:5555", runner=fake), __file__, tmp, ["H6"],
                         lambda m: None, sleep=lambda s: None)
        self.assertTrue(receipt["scenarios"][0]["notRun"])

    def test_skip_when_read_value_unexpected(self):
        fake = FakeAdb(navi_value=7)
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        _, path = self._h6()
        out = ps.run_scenario(device, path, {}, lambda m: None, sleep=lambda s: None)
        self.assertTrue(out["notRun"])
        self.assertNotIn(("am", "start"), fake.events)

    def test_no_navi_commands_without_navi_st(self):
        fake = FakeAdb(answers_jsonl=_answers_jsonl())
        device = ps.Device("adb", "127.0.0.1:5555", runner=fake)
        tmp = Path(tempfile.mkdtemp())
        (tmp / "scenario-H0.json").write_text(json.dumps({"id": "H0", "durationS": 2}))
        ps.run_scenario(device, tmp / "scenario-H0.json", {}, lambda m: None, sleep=lambda s: None)
        self.assertFalse(any(kind == "navi" for kind, _ in fake.events))

    def test_h7_uses_only_instrument_channel_and_restores(self):
        tmp = Path(tempfile.mkdtemp()); self.addCleanup(lambda: __import__("shutil").rmtree(tmp))
        path = tmp / "scenario-H7.json"
        path.write_text(json.dumps({"id": "H7", "durationS": 2, "instrumentNaviStatus": {"value": 2}}))
        read = "service call autoservice 5 i32 1007 i32 1138753594"
        write2 = "service call autoservice 6 i32 1007 i32 1138753594 i32 2 null"
        for failure in (None, "unknown", "readback", "timeout", "interrupt"):
            with self.subTest(failure=failure):
                fake = FakeAdb(navi_value=65535 if failure == "unknown" else 4,
                               timeout_result_read=failure == "timeout")
                if failure == "readback":
                    fake.navi_read_reply = b"Result: Parcel(00000000 00000004 '........')"
                device = ps.Device("adb", "owned:5555", runner=fake)
                def sleep(_):
                    if failure == "interrupt" and any(kind == "navi" and cmd == write2 for kind, cmd in fake.events):
                        raise KeyboardInterrupt
                if failure in ("timeout", "interrupt", "readback"):
                    with self.assertRaises((subprocess.TimeoutExpired, KeyboardInterrupt, ps.SessionError)):
                        ps.run_scenario(device, path, {}, lambda _: None, sleep=sleep)
                    self.assertEqual(fake.navi_value, 4)
                else:
                    out = ps.run_scenario(device, path, {}, lambda _: None, sleep=sleep)
                    if failure == "unknown":
                        self.assertTrue(out["notRun"])
                        self.assertEqual(fake.events, [("navi", read)])
                    else:
                        self.assertEqual(out["instrumentNaviStatus"]["before"], 4)
                        self.assertTrue(out["instrumentNaviStatus"]["restoreVerified"])
                        self.assertEqual(fake.events[0], ("navi", read))
                        self.assertFalse(any("1023" in cmd for kind, cmd in fake.events if kind == "navi"))
        import io, contextlib
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            ps.main(["plan", "--scenario-dir", str(tmp), "--order", "H7"])
        plan = json.loads(buffer.getvalue())
        self.assertEqual(plan["instrumentNaviStatusByHost"]["scenarios"], ["H7"])
        self.assertTrue(plan["instrumentNaviStatusByHost"]["runnable"])
        self.assertEqual(plan["instrumentNaviStatusByHost"]["read"], read)

    def test_changed_original_never_writes_after_player_start(self):
        for key in ("naviSt", "instrumentNaviStatus"):
            with self.subTest(key=key):
                tmp, path = self._h6()
                path.write_text(json.dumps({"id": "changed", "durationS": 1, key: {"value": 2}}))
                fake = FakeAdb(navi_value=1)
                device = ps.Device("adb", "owned:5555", runner=fake)
                def sleep(_):
                    fake.navi_value = 0
                with self.assertRaisesRegex(ps.SessionError, "value changed since original read"):
                    ps.run_scenario(device, path, {}, lambda _: None, sleep=sleep)
                self.assertFalse(any("autoservice 6" in cmd for kind, cmd in fake.events if kind == "navi"))

    def test_failed_restore_stops_chain_with_receipt_for_either_channel(self):
        for key in ("naviSt", "instrumentNaviStatus"):
            with self.subTest(key=key):
                tmp, path = self._h6(); path.write_text(json.dumps({"id": "H6", "durationS": 1, key: {"value": 2}}))
                (tmp / "scenario-next.json").write_text(json.dumps({"id": "next", "durationS": 1}))
                fake = FakeAdb(navi_value=1)
                class RestoreFailDevice(ps.Device):
                    def navi_st_write(self, value):
                        if value == 1:
                            raise ps.SessionError("restore unavailable")
                        return super().navi_st_write(value)
                    def instrument_navi_status_write(self, value):
                        if value == 1:
                            raise ps.SessionError("restore unavailable")
                        return super().instrument_navi_status_write(value)
                device = RestoreFailDevice("adb", "owned:5555", runner=fake)
                receipt = ps.run(device, __file__, tmp, ["H6", "next"], lambda _: None, sleep=lambda _: None)
                self.assertEqual(receipt["stoppedAt"], "H6")
                self.assertEqual(len(receipt["scenarios"]), 1)
                self.assertTrue(receipt["scenarios"][0]["restorationPending"])
                self.assertFalse(receipt["scenarios"][0][key]["restoreVerified"])

    def test_plan_lists_host_navi_st(self):
        tmp, _ = self._h6()
        import io, contextlib
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            ps.main(["plan", "--scenario-dir", str(tmp), "--order", "H6"])
        plan = json.loads(buffer.getvalue())
        self.assertEqual(plan["naviStByHost"]["scenarios"], ["H6"])
        self.assertEqual(plan["naviStByHost"]["read"], READ_CMD)
        self.assertEqual(plan["naviStByHost"]["write"], WRITE2_CMD)


if __name__ == "__main__":
    unittest.main()
