"""Future session procedures tested entirely with memory-only devices and mocked ADB runners."""
import contextlib
import copy
import io
import json
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch

from tools.adas_bridge import live_session as live

BOOT = "11111111-1111-4111-8111-111111111111"
CONSUMER = "22222222-2222-4222-8222-222222222222"
OTHER = "33333333-3333-4333-8333-333333333333"
APK = live.ExpectedApk("a" * 64, 739494309, "29.8.1-YandexHUD.9")


class FakeDevice:
    serial = "owned-test:5555"
    def __init__(self, ledger, *, original=0):
        self.ledger = ledger; self.now = 10000; self.consumer = CONSUMER; self.boot_id = BOOT
        self.enabled = original; self.running = True; self.calls = []; self.files = {}; self.reader_id = None
        self.failure = None; self.closed = True; self.actual_send = -1; self.change_last_send = False
        self.block_arm = False; self.wrong_arm_id = False
        self.changed_consumer = False; self.park = 1; self.speed = 0; self.keep_stock_pid = False
        # NaviSt (device 1023) as the car holds it, and the gate reader's side of it.
        self.navi = 1; self.navi_reader_read_fails = False; self.navi_reader_restores = True
        self.navi_read_fails = False; self.navi_arm = None
        self.instrument = 1; self.instrument_arm = None; self.instrument_read_fails = False; self.instrument_reader_restores = True

    def boot(self):
        self.calls.append("boot"); return self.boot_id

    def elapsed(self):
        self.now += 100; return self.now

    def app(self, package):
        self.calls.append(("app", package))
        return {"installed": True, "enabledState": self.enabled if package == live.STOCK else 0,
                "sha256": "b" * 64 if package == live.STOCK else APK.sha256,
                "versionCode": 100 if package == live.STOCK else APK.version_code,
                "versionName": "stock.1" if package == live.STOCK else APK.version_name, "path": "/data/app/safe/base.apk"}

    def stationary(self):
        self.calls.append("stationary")
        if self.park != 1 or self.speed != 0:
            raise live.SessionError("not stationary")
        return {"legacyParkRaw": self.park, "speedMps": self.speed, "independentSelectorProof": False}

    def private(self, name):
        self.calls.append(("read", name))
        if name == live.HEARTBEAT:
            return {"schema": "denza-adas-consumer-v1", "bootId": self.boot_id,
                    "consumerSession": self.consumer, "elapsedMs": self.now}
        if name == live.STATE:
            if self.change_last_send:
                self.actual_send += 1
            arm = self.files.get(live.ARM)
            closed = self.closed and arm is None
            return {"schema": "denza-adas-live-state-v1", "bootId": self.boot_id, "consumerSession": self.consumer,
                    "armId": (OTHER if self.wrong_arm_id else arm["armId"]) if arm else None,
                    "elapsedMs": self.now, "state": ("BLOCKED" if self.block_arm else "WAITING") if arm else ("DISARMED" if closed else "LIVE"),
                    "quiescent": closed, "serverClosed": closed, "answersClosed": closed, "lastActualSendElapsedMs": self.actual_send}
        if name in (live.GATES, live.ISOLATION):
            if self.reader_id is None:
                return None
            common = {"bootId": self.boot_id, "consumerSession": self.consumer, "readerSession": self.reader_id,
                      "startedAtElapsedMs": self.now - 10, "completedAtElapsedMs": self.now}
            if name == live.ISOLATION:
                return {**common, "schema": "denza-adas-stock-isolation-v1", "stockAbsent": not self.running}
            return {**common, "schema": "denza-adas-gates-v1", "sequence": 1,
                    "values": {str(fid): {"status": "ok", "transact": 5, "value": 0} for fid in live.REQUIRED_FIDS}}
        return copy.deepcopy(self.files.get(name))

    def write_private(self, name, row):
        self.calls.append(("write", name)); self.files[name] = copy.deepcopy(row)
        instrument = self.files.get(live.INSTRUMENT_NAVI_STATUS)
        if name == live.STOP and instrument is not None and self.instrument_reader_restores:
            if instrument["dirty"]:
                self.instrument = instrument["original"]
            instrument.update(state="CLOSED", dirty=False, reason="closed: stop requested")
        ledger = self.files.get(live.NAVI_ST)
        if name == live.STOP and ledger is not None and ledger["state"] != "CLOSED":
            # The reader's own way out: it restores what it wrote, unless it died first.
            if self.navi_reader_restores:
                if ledger["dirty"]:
                    self.navi = ledger["original"]
                ledger.update(state="CLOSED", dirty=False, reason="closed: stop requested")

    def guidance_write(self):
        """The reader's write of 2 at guidance start."""
        ledger = self.files[live.NAVI_ST]
        self.navi = 2; ledger.update(state="WRITTEN", dirty=True, n=ledger["n"] + 3)
        ledger["events"].append({"n": ledger["n"] - 1, "op": "write", "purpose": "guidance start", "ok": True, "value": 2})

    def instrument_navi_status_read(self):
        self.calls.append(("instrument_read",))
        if self.instrument_read_fails:
            raise live.SessionError("autoservice instrument read failed")
        return self.instrument

    def instrument_navi_status_write(self, value):
        self.calls.append(("instrument_write", value)); self.instrument = value

    def navi_st_read(self):
        self.calls.append(("navi_read",))
        if self.navi_read_fails:
            raise live.SessionError("autoservice status/shape not proven")
        return self.navi

    def navi_st_write(self, value):
        self.calls.append(("navi_write", value)); self.navi = value

    def remove_arm(self):
        self.calls.append("disarm"); self.files.pop(live.ARM, None)

    def stock_pids(self):
        self.calls.append("stock_pids"); return [123] if self.running else []

    def stock_enabled(self, value):
        self.calls.append(("stock_enabled", value))
        assert self.ledger.exists(), "rollback must be persisted BEFORE package mutation"
        ledger = json.loads(self.ledger.read_text())
        assert ledger["originalStock"]["enabledState"] in (0, 1, 3)
        self.enabled = value

    def stock_stop(self):
        self.calls.append("stock_stop")
        self.running = self.keep_stock_pid
        if self.running:
            raise live.SessionError("stock PID remains")

    def reader(self, jar, digest, consumer, reader, lifetime, navi_st_arm=None, instrument_navi_status_arm=None):
        self.calls.append("reader_start")
        if self.failure:
            raise live.SessionError(self.failure)
        self.reader_id = reader; self.navi_arm = navi_st_arm
        if navi_st_arm is not None:
            failed = self.navi_reader_read_fails
            self.files[live.NAVI_ST] = {"schema": "denza-adas-navi-st-v1", "bootId": self.boot_id, "consumerSession": self.consumer,
                                        "readerSession": reader, "armId": navi_st_arm, "state": "DISABLED" if failed else "IDLE",
                                        "original": None if failed else self.navi, "dirty": False, "n": 1, "updatedAtElapsedMs": self.now,
                                        "reason": "original read failed: no write" if failed else "original " + str(self.navi),
                                        "events": [{"n": 1, "op": "read", "purpose": "original", "ok": not failed}]}
        self.instrument_arm = instrument_navi_status_arm
        if instrument_navi_status_arm is not None:
            failed = self.instrument_read_fails or self.instrument not in live.INSTRUMENT_NAVI_STATUS_VALUES
            self.files[live.INSTRUMENT_NAVI_STATUS] = {
                "schema": "denza-adas-instrument-navi-status-v1", "bootId": self.boot_id, "consumerSession": self.consumer,
                "readerSession": reader, "armId": instrument_navi_status_arm, "state": "DISABLED" if failed else "IDLE",
                "original": None if failed else self.instrument, "dirty": False, "n": 1, "updatedAtElapsedMs": self.now,
                "reason": "original read failed: no write" if failed else "original " + str(self.instrument),
                "events": [{"n": 1, "op": "read", "ok": not failed}]}
        if self.changed_consumer:
            self.consumer = OTHER
        return {"pid": 456, "jar": "/data/local/tmp/owned.jar", "log": "/data/local/tmp/owned.log"}


class ProcedureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.jar = self.root / "reader.jar"; self.jar.write_bytes(b"local synthetic DEX jar")
        self.digest = live.sha256_file(self.jar); self.ledger = self.root / "rollback.json"
        self.device = FakeDevice(self.ledger)

    def arm(self, **kwargs):
        return live.arm(self.device, APK, self.jar, self.digest, 60000, self.ledger,
                        stationary_confirmed=True, sleep=lambda _: None, **kwargs)

    def test_new_arm_defaults_instrument_on_and_explicit_false_disables(self):
        row = self.arm()
        self.assertTrue(row["variant"]["instrumentNaviStatus"])
        self.assertTrue(self.device.files[live.ARM]["variant"]["instrumentNaviStatus"])
        self.assertEqual(self.device.instrument_arm, row["armId"])
        self.assertEqual(row["instrumentNaviStatus"]["original"], 1)
        live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.ledger.unlink();self.device = FakeDevice(self.ledger)
        row = self.arm(variant={"instrumentNaviStatus": False})
        self.assertIsNone(self.device.instrument_arm)
        self.assertNotIn("instrumentNaviStatus", row)
        self.assertFalse(row["variantEffective"]["instrumentNaviStatus"])

    def test_instrument_arm_records_original_and_restores_independently(self):
        self.device.instrument = 4
        row = self.arm(variant={"id": "H7", "instrumentNaviStatus": True})
        self.assertEqual(row["instrumentNaviStatus"]["original"], 4)
        self.assertEqual(self.device.instrument_arm, row["armId"])
        self.assertIsNone(self.device.navi_arm)
        record = self.device.files[live.INSTRUMENT_NAVI_STATUS]
        self.device.instrument = 2; record.update(dirty=True, state="WRITTEN")
        self.device.instrument_reader_restores = False
        result = live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertEqual(result["phase"], "RESTORED")
        self.assertEqual(result["instrumentNaviStatusRestore"]["restoredBy"], "host")
        self.assertEqual(self.device.instrument, 4)
        self.assertNotIn(("navi_write", 4), self.device.calls)

    def test_instrument_rollback_original_survives_missing_reader_ledger(self):
        self.device.instrument = 4
        row = self.arm(variant={"instrumentNaviStatus": True})
        self.device.files.pop(live.INSTRUMENT_NAVI_STATUS)
        self.device.instrument = 2
        restored = live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertEqual(restored["instrumentNaviStatusRestore"]["original"], 4)
        self.assertEqual(restored["instrumentNaviStatusRestore"]["after"], 4)
        self.assertLess(self.device.calls.index(("instrument_write", 4)), self.device.calls.index(("stock_enabled", 0)))

    def test_instrument_unknown_original_refuses_arm_without_writes(self):
        self.device.instrument = 65535
        with self.assertRaisesRegex(live.SessionError, "original read failed"):
            self.arm(variant={"instrumentNaviStatus": True})
        self.assertNotIn(live.ARM, self.device.files)
        self.assertFalse(any(isinstance(c, tuple) and c[0] == "instrument_write" for c in self.device.calls))
        self.assertEqual(json.loads(self.ledger.read_text())["phase"], "RESTORED")

    def test_both_channel_restore_attempts_survive_independent_failures(self):
        row = self.arm(variant={"naviSt": True, "instrumentNaviStatus": True})
        self.assertEqual(self.device.navi_arm, self.device.instrument_arm)
        self.device.guidance_write(); self.device.navi_reader_restores = False
        self.device.instrument = 2
        self.device.files[live.INSTRUMENT_NAVI_STATUS].update(dirty=True, state="WRITTEN")
        self.device.instrument_reader_restores = False; self.device.navi_read_fails = True
        with self.assertRaisesRegex(live.SessionError, "NaviSt read failed"):
            live.restore(self.device, self.ledger, sleep=lambda _: None)
        restored = json.loads(self.ledger.read_text())
        self.assertEqual(restored["phase"], "ROLLBACK_PENDING")
        self.assertNotIn(("stock_enabled", 0), self.device.calls, "no stock writer while any side restoration is pending")
        self.assertTrue(restored["instrumentNaviStatusRestore"]["verified"])
        self.assertEqual(self.device.instrument, 1)
        self.device.navi_read_fails = False
        self.assertEqual(live.restore(self.device, self.ledger, sleep=lambda _: None)["phase"], "RESTORED")

    def test_first_channel_timeout_does_not_skip_instrument_rollback(self):
        self.arm(variant={"naviSt": True})
        self.device.instrument_reader_restores = False
        self.device.instrument = 2
        self.device.files[live.INSTRUMENT_NAVI_STATUS].update(state="WRITTEN", dirty=True)
        with patch.object(self.device, "navi_st_read", side_effect=subprocess.TimeoutExpired("fixed getter", 1)):
            with self.assertRaises(live.SessionError):
                live.restore(self.device, self.ledger, sleep=lambda _: None)
        row = json.loads(self.ledger.read_text())
        self.assertEqual(row["phase"], "ROLLBACK_PENDING")
        self.assertTrue(row["instrumentNaviStatusRestore"]["verified"])
        self.assertEqual(self.device.instrument, 1)
        self.assertNotIn(("stock_enabled", 0), self.device.calls)

    def test_instrument_ledger_rejects_wrong_identity_and_failed_read(self):
        row = self.arm(variant={"instrumentNaviStatus": True})
        record = self.device.files[live.INSTRUMENT_NAVI_STATUS]
        for key in ("bootId", "consumerSession", "readerSession", "armId"):
            old = record[key]; record[key] = "wrong"
            self.assertIsNone(live.instrument_navi_status_ledger(self.device, row)); record[key] = old
        self.device.instrument_read_fails = True
        with self.assertRaisesRegex(live.SessionError, "instrument navigation status read failed"):
            live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertFalse(any(isinstance(c, tuple) and c[0] == "instrument_write" for c in self.device.calls))

    def test_arm_nine_field_schema_and_rollback_precedes_mutation(self):
        row = self.arm(); self.assertEqual(row["phase"], "ARMED")
        self.assertEqual(set(self.device.files[live.ARM]), {"schema", "bootId", "consumerSession", "readerSession", "armId",
                         "issuedAtElapsedMs", "expiresAtElapsedMs", "stockPackage", "stockDisabled", "variant"})
        arm = self.device.files[live.ARM]
        self.assertEqual(arm["stockPackage"], "com.byd.launchermap"); self.assertIs(arm["stockDisabled"], True)
        self.assertEqual(arm["consumerSession"], CONSUMER); self.assertEqual(arm["bootId"], BOOT)
        self.assertEqual(arm["expiresAtElapsedMs"] - arm["issuedAtElapsedMs"], 60000)
        self.assertEqual(json.loads(self.ledger.read_text())["originalStock"]["enabledState"], 0)
        calls = self.device.calls
        self.assertLess(calls.index(("stock_enabled", 3)), calls.index("stock_stop"))
        self.assertLess(calls.index("stock_stop"), calls.index("reader_start"))
        self.assertLess(calls.index("reader_start"), calls.index(("write", live.ARM)))
        self.assertEqual(calls.count("stationary"), 2)
        self.assertEqual(row["acceptance"]["armId"], row["armId"])
        self.assertEqual(row["acceptance"]["state"], "WAITING")

    def test_restore_disarms_first_proves_closed_then_restores_exact_default(self):
        row = self.arm(); self.device.calls.clear()
        restored = live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertEqual(restored["phase"], "RESTORED"); self.assertEqual(self.device.enabled, 0)
        self.assertNotIn(live.ARM, self.device.files)
        calls = self.device.calls
        self.assertLess(calls.index("disarm"), calls.index(("write", live.STOP)))
        self.assertLess(calls.index(("read", live.STATE)), calls.index(("stock_enabled", 0)))
        self.assertEqual(self.device.files[live.STOP], {"schema": "denza-adas-reader-stop-v1", "bootId": BOOT,
                         "consumerSession": CONSUMER, "readerSession": row["readerSession"]})

    def test_exact_enabled_and_original_disabled_user_states_restore(self):
        for original in (1, 3):
            with self.subTest(original=original):
                self.ledger.unlink(missing_ok=True); self.device = FakeDevice(self.ledger, original=original)
                self.arm(); live.restore(self.device, self.ledger, sleep=lambda _: None)
                self.assertEqual(self.device.enabled, original)

    def test_explicit_human_stationarity_required_before_any_device_calls(self):
        with self.assertRaisesRegex(live.SessionError, "stationary-confirmed"):
            live.arm(self.device, APK, self.jar, self.digest, 60000, self.ledger)
        self.assertEqual(self.device.calls, [])

    def test_unproven_park_or_nonzero_speed_refuses_before_mutation(self):
        for park, speed in ((0, 0), (1, 0.1)):
            self.device.park = park; self.device.speed = speed
            with self.assertRaises(live.SessionError): self.arm()
            self.assertFalse(self.ledger.exists()); self.assertNotIn(("stock_enabled", 3), self.device.calls)

    def test_wrong_apk_hash_and_local_jar_hash_refuse(self):
        with self.assertRaisesRegex(live.SessionError, "identity"):
            live.arm(self.device, live.ExpectedApk("c" * 64, APK.version_code, APK.version_name), self.jar, self.digest,
                     60000, self.ledger, stationary_confirmed=True)
        self.assertFalse(self.ledger.exists())
        self.device.calls.clear()
        with self.assertRaisesRegex(live.SessionError, "JAR hash"):
            live.arm(self.device, APK, self.jar, "d" * 64, 60000, self.ledger, stationary_confirmed=True)
        self.assertEqual(self.device.calls, [])

    def test_existing_arm_or_rollback_is_never_overwritten(self):
        self.device.files[live.ARM] = {"armId": OTHER}
        with self.assertRaisesRegex(live.SessionError, "existing arm"): self.arm()
        self.device.files.clear(); self.ledger.write_text("original")
        with self.assertRaisesRegex(live.SessionError, "ledger already"): self.arm()
        self.assertEqual(self.ledger.read_text(), "original")

    def test_setup_failure_auto_disarms_stops_reader_and_restores(self):
        self.device.failure = "synthetic launch failure"
        with self.assertRaisesRegex(live.SessionError, "original stock enabled state restored"): self.arm()
        self.assertEqual(self.device.enabled, 0)
        self.assertEqual(json.loads(self.ledger.read_text())["phase"], "RESTORED")
        self.assertIn("disarm", self.device.calls); self.assertIn(("write", live.STOP), self.device.calls)

    def test_runtime_arm_block_or_wrong_arm_id_never_report_armed_and_roll_back(self):
        for flag in ("block_arm", "wrong_arm_id"):
            with self.subTest(flag=flag):
                self.ledger.unlink(missing_ok=True); self.device = FakeDevice(self.ledger)
                setattr(self.device, flag, True)
                with self.assertRaisesRegex(live.SessionError, "original stock enabled state restored"): self.arm()
                self.assertEqual(self.device.enabled, 0)
                self.assertEqual(json.loads(self.ledger.read_text())["phase"], "RESTORED")

    def test_pid_absence_failure_never_arms_and_rolls_back(self):
        self.device.keep_stock_pid = True
        with self.assertRaisesRegex(live.SessionError, "restored"): self.arm()
        self.assertNotIn(("write", live.ARM), self.device.calls); self.assertEqual(self.device.enabled, 0)

    def test_process_restart_cannot_be_auto_armed_or_restore_without_proof(self):
        self.device.changed_consumer = True
        with self.assertRaisesRegex(live.SessionError, "restore still pending"): self.arm()
        self.assertNotIn(live.ARM, self.device.files)
        self.assertEqual(self.device.enabled, 3)
        self.assertEqual(json.loads(self.ledger.read_text())["phase"], "ROLLBACK_PENDING")

    def test_restore_never_enables_stock_without_stable_no_egress_proof(self):
        self.arm(); self.device.change_last_send = True
        with self.assertRaisesRegex(live.SessionError, "egress changed"):
            live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertNotIn(live.ARM, self.device.files); self.assertEqual(self.device.enabled, 3)

    def test_restore_refuses_to_disarm_another_current_session(self):
        self.arm(); self.device.files[live.ARM]["armId"] = OTHER
        with self.assertRaisesRegex(live.SessionError, "another session"):
            live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertIn(live.ARM, self.device.files); self.assertNotIn("disarm", self.device.calls)

    def test_unrestorable_original_state_refuses_before_disable(self):
        self.device.enabled = 2
        with self.assertRaisesRegex(live.SessionError, "not proven shell-restorable"): self.arm()
        self.assertNotIn(("stock_enabled", 3), self.device.calls)

    def test_plan_and_dry_run_do_not_construct_device_or_infer_version(self):
        for command in ("plan", "arm"):
            args = [command, "--serial", "owned:5555", "--ledger", str(self.ledger), "--apk-sha256", APK.sha256,
                    "--version-code", str(APK.version_code), "--version-name", APK.version_name,
                    "--reader-jar", str(self.jar), "--reader-sha256", self.digest, "--lifetime-ms", "60000", "--dry-run"]
            with patch.object(live, "Device", side_effect=AssertionError("must not create ADB device")):
                out = io.StringIO()
                with contextlib.redirect_stdout(out): self.assertEqual(live.main(args), 0)
            plan = json.loads(out.getvalue())
            self.assertEqual(plan["deviceCalls"], 0)
            self.assertEqual(plan["readerJar"], str(self.jar))
            self.assertEqual(plan["readerSha256"], self.digest)
            self.assertEqual(plan["lifetimeMs"], 60000)
            self.assertIs(plan["variant"]["instrumentNaviStatus"], True)
            self.assertIs(plan["variantEffective"]["instrumentNaviStatus"], True)
            self.assertFalse(self.ledger.exists())


ROOT = Path(__file__).resolve().parents[3]
VARIANTS = ROOT / "tools/adas_bridge/variants"
VARIANT_JAVA = ROOT / "libraries/adas-link/src/main/java/dev/denza/adaslink/ExperimentVariant.java"
VARIANT_JAVA_TEST = ROOT / "libraries/adas-link/src/test/java/dev/denza/adaslink/ExperimentVariantTest.java"


def java_string(literal):
    return literal.replace('\\"', '"').replace("\\\\", "\\")


class VariantSchemaTests(unittest.TestCase):
    """The host refuses exactly what ExperimentVariant refuses, before any device call."""

    def test_every_example_file_is_valid_and_sets_its_step(self):
        expect = {"baseline.json": [], "H1.json": ["frame"], "H2a.json": ["link.roadclass", "link.formway"],
                  "H2b.json": ["link.ownership"], "H2c.json": ["link.direction"], "H2d.json": ["link.laneNum"],
                  "H2e.json": ["link.adminCode", "region"], "H2f.json": ["position.ownership", "traffic.roadClass"],
                  "H2g.json": ["speedLimit"], "H3.json": ["startProtocol"], "H6.json": ["naviSt"], "H7.json": ["instrumentNaviStatus"],
                  "drive-ab-historyspeed.json": ["historySpeed"]}
        self.assertEqual(set(expect), {p.name for p in VARIANTS.glob("*.json")})
        for name, keys in expect.items():
            with self.subTest(name=name):
                self.assertEqual(live.effective_variant(live.load_variant(VARIANTS / name))["overrides"], keys)
        self.assertIs(live.effective_variant(live.load_variant(VARIANTS / "H6.json"))["naviSt"], True)

    def test_rejects_every_case_the_java_schema_rejects(self):
        text = VARIANT_JAVA_TEST.read_text(encoding="utf-8")
        cases = [java_string(m) for m in re.findall(r'rejects\("((?:[^"\\]|\\.)*)",', text)]
        self.assertGreater(len(cases), 30)
        for case in cases:
            with self.subTest(case=case):
                with self.assertRaises(live.SessionError):
                    live.validate_variant(json.loads(case))
        for case in ({"instrumentNaviStatus": 1}, {"instrumentNaviStatus": None}, {"naviSt": 1}, {"link.laneNum": True}, {"historySpeed": {"mode": "fixed", "value": 25.0}},
                     {"region": 990001.0}, [], "H1", None):
            with self.subTest(case=case):
                with self.assertRaises(live.SessionError):
                    live.validate_variant(case)

    def test_effective_names_every_java_switch_in_order(self):
        keys = re.findall(r'e\.put\("([^"]+)"', VARIANT_JAVA.read_text(encoding="utf-8"))
        self.assertEqual(keys, list(live.effective_variant({})))
        self.assertEqual(list(live.VARIANT_KEYS), re.findall(r'"([^"]+)"', re.search(
            r"KEYS = \{(.*?)\};", VARIANT_JAVA.read_text(encoding="utf-8"), re.S).group(1)))
        none = live.effective_variant({})
        self.assertEqual((none["frame"], none["region"], none["link.roadclass"], none["historySpeed"], none["naviSt"]),
                         ("WGS84", "default", "yandex", {"mode": "yandex"}, False))
        self.assertIn("FRAME = Coordinates.Frame.WGS84", (VARIANT_JAVA.parent / "FieldDefaults.java").read_text(encoding="utf-8"))

    def test_variant_file_is_strict_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "v.json"
            for body in ('{"naviSt":true,"naviSt":false}', '{"historySpeed":{"mode":"fixed","value":NaN}}', "[]", "{"):
                path.write_text(body)
                with self.subTest(body=body), self.assertRaises(live.SessionError):
                    live.load_variant(path)

    def test_instrument_status_is_strict_off_and_independent(self):
        self.assertFalse(live.effective_variant({})["instrumentNaviStatus"])
        self.assertFalse(live.effective_variant({"naviSt": True})["instrumentNaviStatus"])
        self.assertFalse(live.effective_variant({"instrumentNaviStatus": True})["naviSt"])
        for value in (None, 0, 1, "true", [], {}):
            with self.subTest(value=value), self.assertRaises(live.SessionError):
                live.validate_variant({"instrumentNaviStatus": value})
        self.assertEqual(" ".join(live.instrument_navi_status_read_tokens()), "service call autoservice 5 i32 1007 i32 1138753594")
        self.assertEqual(" ".join(live.instrument_navi_status_write_tokens(2)), "service call autoservice 6 i32 1007 i32 1138753594 i32 2 null")
        for value in (-1, 5, 65535, True):
            with self.assertRaises(live.SessionError):
                live.instrument_navi_status_write_tokens(value)

    def test_navi_st_commands_are_the_parked_sessions(self):
        from tools.adas_bridge import parked_session
        self.assertEqual(" ".join(live.navi_st_read_tokens()), parked_session.navi_st_read_command())
        self.assertEqual(" ".join(live.navi_st_write_tokens(2)), parked_session.navi_st_write_command(2))
        self.assertEqual(live.NAVI_ST_VALUES, parked_session.NAVI_ST_VALUES)
        for value in (3, -1, True, "2"):
            with self.assertRaises(live.SessionError):
                live.navi_st_write_tokens(value)


class VariantArmTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.jar = self.root / "reader.jar"; self.jar.write_bytes(b"local synthetic DEX jar")
        self.digest = live.sha256_file(self.jar); self.ledger = self.root / "rollback.json"
        self.device = FakeDevice(self.ledger)

    def arm(self, variant):
        return live.arm(self.device, APK, self.jar, self.digest, 60000, self.ledger,
                        stationary_confirmed=True, sleep=lambda _: None, variant=variant)

    def restore(self):
        return live.restore(self.device, self.ledger, sleep=lambda _: None)

    def test_variant_rides_in_the_arm_and_the_ledger(self):
        variant = {"id": "H1", "frame": "STOCK_GCJ", "instrumentNaviStatus": False}
        row = self.arm(variant)
        arm = self.device.files[live.ARM]
        self.assertEqual(len(arm), 10); self.assertEqual(arm["variant"], variant)
        self.assertEqual(row["variant"], variant); self.assertEqual(row["variantEffective"]["frame"], "STOCK_GCJ")
        self.assertEqual(json.loads(self.ledger.read_text())["variantEffective"]["overrides"], ["frame", "instrumentNaviStatus"])
        self.assertIsNone(self.device.navi_arm, "no naviSt: the reader is the read-only one")
        self.assertNotIn("naviSt", row)
        self.restore()
        self.assertFalse(any(c[0].startswith("navi") for c in self.device.calls if isinstance(c, tuple)))

    def test_invalid_variant_refuses_before_any_device_call(self):
        for variant in ({"frame": "GCJ"}, {"naviSt": True, "extra": 1}, {"link.adminCode": 110101}):
            with self.subTest(variant=variant), self.assertRaises(live.SessionError):
                self.arm(variant)
            self.assertEqual(self.device.calls, []); self.assertFalse(self.ledger.exists())

    def test_navi_st_arm_records_the_readers_original(self):
        self.device.navi = 0
        row = self.arm({"id": "H6", "naviSt": True})
        self.assertEqual(self.device.navi_arm, row["armId"], "the reader drives NaviSt for this arm")
        self.assertEqual(row["naviSt"]["original"], 0)
        self.assertEqual(json.loads(self.ledger.read_text())["naviSt"]["original"], 0)
        self.assertTrue(self.device.files[live.ARM]["variant"]["naviSt"])

    def test_failed_reader_read_refuses_the_navi_st_arm(self):
        self.device.navi_reader_read_fails = True
        with self.assertRaisesRegex(live.SessionError, "NaviSt read at arm time failed"):
            self.arm({"naviSt": True})
        self.assertNotIn(("write", live.ARM), self.device.calls)
        ledger = json.loads(self.ledger.read_text())
        self.assertEqual(ledger["phase"], "RESTORED"); self.assertEqual(ledger["naviStRestore"]["skipped"][:11], "no original")
        self.assertFalse(any(c[0] == "navi_write" for c in self.device.calls if isinstance(c, tuple)), "no write")

    def test_restore_after_the_reader_restored_writes_nothing(self):
        self.arm({"naviSt": True}); self.device.guidance_write()
        self.assertEqual(self.device.navi, 2)
        row = self.restore()
        self.assertEqual(self.device.navi, 1)
        result = row["naviStRestore"]
        self.assertEqual((result["before"], result["after"], result["written"], result["restoredBy"]), (1, 1, False, "reader"))
        self.assertNotIn(("navi_write", 1), self.device.calls)
        self.assertLess(self.device.calls.index("disarm"), self.device.calls.index(("navi_read",)))

    def test_restore_puts_the_original_back_when_the_reader_did_not(self):
        self.arm({"naviSt": True}); self.device.guidance_write(); self.device.navi_reader_restores = False
        row = self.restore()
        self.assertEqual(self.device.navi, 1); self.assertEqual(row["phase"], "RESTORED")
        result = row["naviStRestore"]
        self.assertEqual((result["before"], result["written"], result["after"], result["restoredBy"]), (2, True, 1, "host"))
        calls = [c for c in self.device.calls if isinstance(c, tuple) and c[0].startswith("navi")]
        self.assertEqual(calls, [("navi_read",), ("navi_write", 1), ("navi_read",)])
        self.assertLess(self.device.calls.index(("navi_read",)), self.device.calls.index(("stock_enabled", 0)))

    def test_failed_host_read_writes_nothing_and_leaves_rollback_pending(self):
        self.arm({"naviSt": True}); self.device.guidance_write(); self.device.navi_reader_restores = False
        self.device.navi_read_fails = True
        with self.assertRaisesRegex(live.SessionError, "no write"):
            self.restore()
        self.assertEqual(self.device.navi, 2)
        self.assertFalse(any(c[0] == "navi_write" for c in self.device.calls if isinstance(c, tuple)))
        self.assertEqual(json.loads(self.ledger.read_text())["phase"], "ROLLBACK_PENDING")
        self.device.navi_read_fails = False
        self.assertEqual(self.restore()["naviStRestore"]["after"], 1, "a second restore finishes the job")

    def test_cli_prints_the_effective_variant_and_refuses_an_invalid_one(self):
        base = ["arm", "--serial", "owned:5555", "--ledger", str(self.ledger), "--apk-sha256", APK.sha256,
                "--version-code", str(APK.version_code), "--version-name", APK.version_name,
                "--reader-jar", str(self.jar), "--reader-sha256", self.digest, "--lifetime-ms", "60000", "--dry-run"]
        out, err = io.StringIO(), io.StringIO()
        with patch.object(live, "Device", side_effect=AssertionError("must not create ADB device")):
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(live.main(base + ["--variant", str(VARIANTS / "H6.json")]), 0)
        plan = json.loads(out.getvalue())
        self.assertEqual(plan["variant"], {"id": "H6", "naviSt": True, "instrumentNaviStatus": True}); self.assertIs(plan["variantEffective"]["naviSt"], True)
        self.assertIn('effective variant: {"id":"H6"', err.getvalue())
        bad = self.root / "bad.json"; bad.write_text('{"id":"x","historySpeed":{"mode":"fixed","value":200}}')
        with patch.object(live, "Device", side_effect=AssertionError("must not create ADB device")):
            with contextlib.redirect_stderr(io.StringIO()) as message, self.assertRaises(SystemExit) as exited:
                live.main(base[:-1] + ["--stationary-confirmed", "--variant", str(bad)])
        self.assertEqual(exited.exception.code, 2); self.assertIn("historySpeed", message.getvalue())
        self.assertFalse(self.ledger.exists())

    def test_reader_launch_carries_the_arm_id_only_with_navi_st(self):
        calls = []; digest = "a" * 64
        def runner(args, **kwargs):
            calls.append(args)
            tokens = shlex.split(args[4]) if args[3] == "shell" else []
            output = (digest + "  x\n").encode() if tokens[:1] == ["sha256sum"] else b"456\n" if tokens[:2] == ["sh", "-c"] else b""
            return types.SimpleNamespace(returncode=0, stdout=output, stderr=b"")
        device = live.Device("adb", "owned:5555", runner=runner)
        device.reader(Path("synthetic.jar"), digest, CONSUMER, OTHER, 60000, navi_st_arm=BOOT)
        launch = shlex.split(shlex.split(calls[-1][4])[2])
        self.assertEqual(launch[4:10], ["dev.denza.adasprobe.AdasGateReader", CONSUMER, OTHER, "60000", BOOT, "navi-st"])
        with self.assertRaises(live.SessionError):
            device.reader(Path("synthetic.jar"), digest, CONSUMER, OTHER, 60000, navi_st_arm="not; a uuid")

    def test_device_navi_st_commands_and_unexpected_value(self):
        sent = []
        def runner(args, **kwargs):
            sent.append(args[4])
            value = 7 if len(sent) == 3 else 1
            return types.SimpleNamespace(returncode=0, stdout=("Result: Parcel(00000000 %08x   '........')\n" % value).encode(), stderr=b"")
        device = live.Device("adb", "owned:5555", runner=runner)
        self.assertEqual(device.navi_st_read(), 1); device.navi_st_write(2)
        with self.assertRaisesRegex(live.SessionError, "unexpected value"):
            device.navi_st_read()
        self.assertEqual(sent[:2], ["service call autoservice 5 i32 1023 i32 1285554251",
                                    "service call autoservice 6 i32 1023 i32 1285554251 i32 2 null"])


class ParsingAndCommandTests(unittest.TestCase):
    def test_boot_counter_uuid_matches_java_name_uuid_raw_bytes_not_namespaced_uuid3(self):
        self.assertEqual(live.boot_count_uuid("0"), "f774732e-56d9-344a-bce0-67a0f5c11865")
        self.assertEqual(live.boot_count_uuid("42"), "ecc6a015-850f-3817-85e3-5e60cd0d3d03")
        self.assertEqual(live.boot_count_uuid("2147483647"), "ca1558d4-bd94-3b20-9dbd-cc548accf4ab")
        for value in ("null", "", "-1", "01", "2147483648", "1\n", True):
            with self.subTest(value=value), self.assertRaises(live.SessionError): live.boot_count_uuid(value)

    def test_legacy_park_shape_status_and_domain(self):
        self.assertEqual(live.legacy_park("Result: Parcel(00000000 00000001 '........')\n"), 1)
        self.assertEqual(live.legacy_park("Result: Parcel(00000000 00000000)"), 0)
        for text in ("", "Result: Parcel(00000001 00000001)", "Result: Parcel(00000000 00000002)",
                     "Result: Parcel(00000000 ffffffff)", "Result: Parcel(00000000 00000001 00000000)"):
            with self.subTest(text=text), self.assertRaises(live.SessionError): live.legacy_park(text)

    def test_float_speed_zero_is_not_integer_bits_or_signed_fid(self):
        for raw in (0, 0x80000000):
            self.assertEqual(live.zero_speed(f"Result: Parcel(00000000 {raw:08x})"), 0)
        for value in (1, -1, float("nan"), float("inf"), 8191):
            bits = struct.unpack("<I", struct.pack("<f", value))[0]
            with self.assertRaises(live.SessionError): live.zero_speed(f"Result: Parcel(00000000 {bits:08x})")

    def test_package_metadata_user0_only_and_exact_state(self):
        text = "Package [com.byd.launchermap]:\n versionCode=123 minSdk=26\n versionName=stock.1\n User 10: installed=true enabled=1\n User 0: installed=true hidden=false enabled=3\n"
        self.assertEqual(live.parse_package(text), {"versionCode": 123, "versionName": "stock.1", "installed": True, "enabledState": 3})
        for bad in (text.replace("enabled=3", "enabled=9"), text + " User 0: installed=true enabled=1\n", text.replace(" User 0:", " User 1:")):
            with self.assertRaises(live.SessionError): live.parse_package(bad)

    def test_missing_future_stale_heartbeat_and_bool_timestamps(self):
        row = {"schema": "denza-adas-consumer-v1", "bootId": BOOT, "consumerSession": CONSUMER, "elapsedMs": 1000}
        self.assertIs(live.fresh_tuple(row, row["schema"], BOOT, CONSUMER, 6000), row)
        for now in (999, 6001):
            with self.assertRaises(live.SessionError): live.fresh_tuple(row, row["schema"], BOOT, CONSUMER, now)
        row["elapsedMs"] = True
        with self.assertRaises(live.SessionError): live.fresh_tuple(row, row["schema"], BOOT, CONSUMER, 1000)

    def test_no_generic_device_path_shell_or_serial_injection(self):
        calls = []
        def runner(args, **kwargs):
            calls.append((args, kwargs)); return types.SimpleNamespace(returncode=0, stdout=b"", stderr=b"")
        for serial in ("", "-x", "owned;id", "owned\nother"):
            with self.assertRaises(live.SessionError): live.Device("adb", serial, runner=runner)
        device = live.Device("adb", "owned:5555", runner=runner)
        self.assertIsNone(device.private(live.ARM))
        command = calls[-1][0]
        self.assertEqual(command[:4], ["adb", "-s", "owned:5555", "exec-out"])
        # The entire sh -c script remains one quoted remote argument, not accidental shell syntax.
        tokens = shlex.split(command[4]); self.assertEqual(tokens[:4], ["run-as", live.PACKAGE, "sh", "-c"])
        self.assertIn("if [ -f", tokens[4]); self.assertEqual(len(tokens), 5)
        with self.assertRaises(live.SessionError): device.private("files/arm;id")
        with self.assertRaises(live.SessionError): device.write_private(live.HEARTBEAT, {})
        with self.assertRaises(live.SessionError): device.app("com.autonavi.amapauto")
        self.assertEqual(calls[-1][1]["timeout"], 5)

    def test_process_absence_needs_exact_header_data_and_full_name_witness(self):
        output = [b"PID NAME\n100 ru.yandex.yandexnavi\n200 com.byd.launchermap:remote\n"]
        def runner(args, **kwargs):
            self.assertIn("ps -A -w -o PID,NAME", args[-1])
            return types.SimpleNamespace(returncode=0, stdout=output[0], stderr=b"")
        device = live.Device("adb", "owned:5555", runner=runner)
        self.assertEqual(device.stock_pids(), [200])
        output[0] = b"PID NAME\n100 ru.yandex.yandexnavi\n"
        self.assertEqual(device.stock_pids(), [])
        for bad in (b"", b"PID NAME\n", b"100 ru.yandex.yandexnavi\n", b"PID NAME\n100 ru.yandex.yande\n",
                    b"PID NAME\n100 ru.yandex.yandexnavi\n200 com.byd.launche+\n"):
            output[0] = bad
            with self.subTest(bad=bad), self.assertRaises(live.SessionError): device.stock_pids()

    def test_atomic_private_write_does_not_wait_for_adb_stdin_eof(self):
        # Reproduce the physical adb exec-out boundary: remote stdin stays open.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "files").mkdir()
            row = {"literal": "Карта ' $({}) `command` \\n".format("touch injected"), "value": 7}

            def runner(args, **kwargs):
                self.assertIsNone(kwargs.get("input"))
                tokens = shlex.split(args[-1])
                self.assertEqual(tokens[:4], ["run-as", live.PACKAGE, "sh", "-c"])
                process = subprocess.Popen(["sh", "-c", tokens[4]], cwd=root,
                                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    process.wait(timeout=2)  # Deliberately no stdin close before completion.
                    return types.SimpleNamespace(returncode=process.returncode,
                                                 stdout=process.stdout.read(), stderr=process.stderr.read())
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait()
                    process.stdin.close(); process.stdout.close(); process.stderr.close()

            device = live.Device("adb", "owned:5555", runner=runner)
            device.write_private(live.STOP, row)
            self.assertEqual(json.loads((root / live.STOP).read_text()), row)
            self.assertFalse((root / "injected").exists())
            self.assertFalse((root / (live.STOP + ".tmp")).exists())

    def test_reader_launch_ignores_hup_and_detaches_all_standard_descriptors(self):
        calls = []; digest = "a" * 64
        def runner(args, **kwargs):
            calls.append(args)
            output = b""
            if args[3] == "shell":
                tokens = shlex.split(args[4])
                if tokens[0] == "sha256sum":
                    output = (digest + "  " + tokens[1] + "\n").encode()
                elif tokens[:2] == ["sh", "-c"]:
                    output = b"456\n"
                else:
                    raise AssertionError("unexpected mocked command")
            else:
                self.assertEqual(args[3], "push")
            return types.SimpleNamespace(returncode=0, stdout=output, stderr=b"")
        device = live.Device("adb", "owned:5555", runner=runner)
        result = device.reader(Path("synthetic.jar"), digest, CONSUMER, OTHER, 3_600_000)
        self.assertEqual(result["pid"], 456)
        outer = shlex.split(calls[-1][4]); self.assertEqual(outer[:2], ["sh", "-c"])
        launch = shlex.split(outer[2])
        self.assertEqual(launch[:4], ["CLASSPATH=" + result["jar"], "/system/bin/nohup", "/system/bin/app_process", "/system/bin"])
        self.assertEqual(launch[4:8], ["dev.denza.adasprobe.AdasGateReader", CONSUMER, OTHER, "3600000"])
        self.assertEqual(launch[8:], [">", result["log"], "2>&1", "<", "/dev/null", "&", "echo", "$!"])
        self.assertNotIn("setsid", outer[2])

    def test_reader_independent_isolation_must_match_tuple_and_absence(self):
        with tempfile.TemporaryDirectory() as directory:
            device = FakeDevice(Path(directory) / "ledger"); device.reader_id = OTHER
            row = {"bootId": BOOT, "consumerSession": CONSUMER, "readerSession": OTHER}
            with self.assertRaisesRegex(live.SessionError, "absence"):
                live.reader_ready(device, row, sleep=lambda _: None)

    def test_reader_age_measured_from_batch_start_not_just_completion(self):
        with tempfile.TemporaryDirectory() as directory:
            device = FakeDevice(Path(directory) / "ledger"); device.reader_id = OTHER; device.running = False
            original = device.private
            def stale(name):
                value = original(name)
                if name == live.GATES:
                    value["startedAtElapsedMs"] = device.now - 2501
                return value
            device.private = stale
            with self.assertRaisesRegex(live.SessionError, "batch times"):
                live.reader_ready(device, {"bootId": BOOT, "consumerSession": CONSUMER, "readerSession": OTHER}, sleep=lambda _: None)

    def test_bounds_and_bad_identity_types(self):
        for version in (None, "", "29.8.1\n", "bad version"):
            with self.assertRaises(live.SessionError): live.ExpectedApk("a" * 64, 123, version)
        for code in (0, True, 2**31):
            with self.assertRaises(live.SessionError): live.ExpectedApk("a" * 64, code, "valid")
        with self.assertRaises(live.SessionError): live.decode_json(b'{"bad":NaN}')
        with self.assertRaises(live.SessionError): live.decode_json(b'{"same":1,"same":2}')
        with self.assertRaises(live.SessionError): live.decode_json(b"x" * (live.MAX_JSON + 1))


if __name__ == "__main__":
    unittest.main()
