"""Host-runner tests with a fake adb: preconditions gate, the per-scenario push/launch/pull/table
cycle, answer decoding, and restore (uninstall). No real device is ever contacted."""
import base64
import json
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

    def __init__(self, *, stock_running=False, stock_enabled=3, answers_jsonl=b""):
        self.stock_running = stock_running
        self.stock_enabled = stock_enabled
        self.answers_jsonl = answers_jsonl
        self.commands = []

    def __call__(self, args, *, input=None, capture_output=True, timeout=None, check=False):
        self.commands.append(list(args))
        joined = " ".join(args)
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
        if "run-as" in args and ps.NAVIGATOR in args:
            return CompletedLike(b"")  # no arm file
        if "run-as" in args and ps.PACKAGE in args:
            # read of player.jsonl vs writes/removes
            if "player.jsonl" in joined and "head -c" in joined:
                return CompletedLike(self.answers_jsonl)
            return CompletedLike(b"")
        if "am start" in joined:
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

    def test_decode_answers_empty(self):
        self.assertEqual(ps.decode_answers([])["pilot"], [])


if __name__ == "__main__":
    unittest.main()
