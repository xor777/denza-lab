"""No ADB: metadata validation and closure/GO order through the shared runner."""
from pathlib import Path
import unittest
from unittest import mock
import run_ghy_check_metadata as host
import test_run_ota_check_metadata as old


def snapshot():
    return {"cache": old.snapshot()["cache"], "ghyVersion": "v1.2.3"}


def response():
    return {"getEcuList": {"status": "100", "sha256": "a" * 64, "ecuIds": ["TEST"]},
            "missingCacheIds": [], "checkVersion": {**old.plan(), "status": "111"},
            "checkFailure": None, "outcome": "check_returned"}


class Helper(old.FakeHelper):
    def message(self, timeout):
        self.n += 1
        self.d.event("ready" if self.n == 1 else "result")
        if self.n == 1: return {"state": "INPUTS_READY", "nonce": "n", "snapshot": snapshot()}
        return {"state": "RESULT", "response": response()}


class Device(old.FakeDevice):
    def launch_helper(self, *args): self.event("helper"); return Helper(self)


class Tests(unittest.TestCase):
    def execute(self, d):
        return host.run(d, Path("unused"), "a" * 64, "n", lambda _: d.event("saved"))

    def test_close_and_save_before_go(self):
        d = Device(); r = self.execute(d)
        self.assertEqual(r["status"], "complete")
        self.assertEqual(d.events.count("GO"), 1)
        self.assertLess(d.events.index("closed_and_terminal"), d.events.index("saved"))
        self.assertLess(d.events.index("saved"), d.events.index("GO"))

    def test_failures_before_go(self):
        for phase in ("preflight", "claim", "stage", "arm", "start", "ready", "closed_and_terminal", "saved"):
            d = Device(phase); self.execute(d); self.assertNotIn("GO", d.events)

    def test_no_retry_after_go(self):
        d = Device("result"); r = self.execute(d)
        self.assertEqual(d.events.count("GO"), 1)
        self.assertTrue(r["leaseReleased"])

    def test_precise_shapes_and_no_secrets(self):
        host.snapshot(snapshot()); host.response(response())
        for key in ("token", "uuid", "secretKey", "privateKey", "downloadUrl"):
            v = response(); v[key] = "private"
            with self.assertRaises(host.base.Refused): host.response(v)

    def test_preserved_catalogue_on_second_failure(self):
        v = response(); v.update(outcome="check_failed", checkVersion=None, checkFailure="operation_failed")
        host.response(v)
        v["checkFailure"] = "PRIVATE_TOKEN"
        with self.assertRaises(host.base.Refused): host.response(v)

    def test_incomplete_cache_must_not_have_plan(self):
        v = response(); v.update(outcome="cache_incomplete", missingCacheIds=["TEST"])
        with self.assertRaises(host.base.Refused): host.response(v)
        v["checkVersion"] = None; host.response(v)

    def test_rejected_catalogue_has_no_ids_or_check(self):
        v = response(); v.update(outcome="catalogue_rejected", checkVersion=None)
        v["getEcuList"]["status"] = "104"
        with self.assertRaises(host.base.Refused): host.response(v)
        v["getEcuList"]["ecuIds"] = []; host.response(v)

    def test_versions_remain_separate(self):
        v = snapshot(); host.snapshot(v)
        self.assertEqual(v["ghyVersion"], "v1.2.3")
        self.assertEqual(v["cache"]["version"], "V1.7.0")
        v["ghyVersion"] = ""
        with self.assertRaises(host.base.Refused): host.snapshot(v)

    def test_peer_restart_is_rejected_even_with_same_pid(self):
        d = host.Device(); d.peers = {"safekeyservice": {"pid": "10", "start": "100"}}
        d.peer_processes = lambda: {"safekeyservice": {"pid": "10", "start": "200"}}
        path, digest = host.GHY_APK
        d.shell = lambda command: "package:" + path if command.startswith("pm path") else digest + "  " + path
        with mock.patch.object(host.gcy.Device, "identity"):
            with self.assertRaises(host.base.Refused): d.identity()


if __name__ == "__main__": unittest.main()
