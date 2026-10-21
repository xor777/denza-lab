"""Fake-device tests of authorization/closure order and output privacy. No ADB."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("check_host", Path(__file__).with_name("run_ota_check_metadata.py"))
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)


def plan():
    return {"sha256": "a" * 64, "causeKind": "ecu_strategy_mismatch", "strategy": {},
            **{key: [] for key in host.PACKAGES}}


def snapshot():
    return {"cache": {"version": "V1.7.0", "sha256": "b" * 64,
            "ecuInfo": [{"ecuId": "TEST", "ecuSoftCode": "000000000000000000",
                         "ecuSoftVer": "0.00.00", "ecuHardVer": "0.0.0",
                         "ecuConfigCode": "FFFFFFFFFFFFFFFFFF", "ecuConfigVer": "6.55.35"}]},
            "plans": {p: None for p in host.PLAN_PATHS}}


class FakeHelper:
    def __init__(self, device): self.d, self.n = device, 0
    def message(self, timeout):
        self.n += 1
        self.d.event("ready" if self.n == 1 else "result")
        if self.n == 1: return {"state": "INPUTS_READY", "nonce": "n", "snapshot": snapshot()}
        return {"state": "RESULT", "response": {**plan(), "status": "111"}}
    def go(self, nonce): self.d.event("GO")
    def finish(self): self.d.event("finish")
    def close(self): self.d.event("close_helper")


class FakeDevice:
    def __init__(self, fail=None): self.events, self.fail = [], fail
    def event(self, name):
        self.events.append(name)
        if name == self.fail: raise host.base.Refused("injected")
    def preflight(self): self.event("preflight"); return "123"
    def claim(self, *args): self.event("claim")
    def stage(self, *args): self.event("stage"); return True
    def arm(self): self.event("arm")
    def start(self): self.event("start")
    def launch_helper(self, *args): self.event("helper"); return FakeHelper(self)
    def close_export(self, *args): self.event("closed_and_terminal")
    def identity(self): self.event("identity")
    def release(self, *args): self.event("release")


class Tests(unittest.TestCase):
    def run_device(self, device):
        return host.run(device, Path("unused"), "a" * 64, "n", lambda _: device.event("snapshot_saved"))

    def test_snapshot_and_closed_export_precede_only_request(self):
        d = FakeDevice(); result = self.run_device(d)
        self.assertEqual(result["status"], "complete")
        self.assertEqual(result["response"]["status"], "111")
        self.assertEqual(d.events.count("GO"), 1)
        self.assertLess(d.events.index("closed_and_terminal"), d.events.index("snapshot_saved"))
        self.assertLess(d.events.index("snapshot_saved"), d.events.index("GO"))

    def test_failures_before_go_never_send(self):
        for phase in ("preflight", "claim", "stage", "arm", "start", "ready", "closed_and_terminal", "snapshot_saved"):
            with self.subTest(phase=phase):
                d = FakeDevice(phase); result = self.run_device(d)
                self.assertNotIn("GO", d.events)
                self.assertFalse(result["goSent"])

    def test_failure_after_go_never_retries(self):
        d = FakeDevice("result"); result = self.run_device(d)
        self.assertEqual(d.events.count("GO"), 1)
        self.assertIn("snapshot", result)
        self.assertTrue(result["exportClosed"])
        self.assertTrue(result["leaseReleased"])

    def test_forced_strategy_is_only_data(self):
        p = plan(); p["strategy"] = {"isForce": "1", "sequence": []}; host.plan(p)

    def test_output_cannot_include_credentials_or_download_url(self):
        for key in ("token", "uuid", "secretKey", "versionPath"):
            p = plan(); p[key] = "sensitive"
            with self.assertRaises(host.base.Refused): host.plan(p)

    def test_cache_rows_cannot_change_shape(self):
        s = snapshot(); s["cache"]["ecuInfo"][0]["uuid"] = "private"
        with self.assertRaises(host.base.Refused): host.snapshot(s)

    def test_private_errors_are_not_echoed(self):
        with self.assertRaises(host.base.Refused) as caught:
            host.parse_message(b'{"state":"ERROR","stage":"https","code":"PRIVATE_TOKEN"}')
        self.assertNotIn("PRIVATE_TOKEN", str(caught.exception))

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaises(host.base.Refused): host.parse_message(b'{"state":"a","state":"b"}')


if __name__ == "__main__": unittest.main()
