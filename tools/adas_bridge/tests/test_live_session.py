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
        self.graceful_stopped = False; self.graceful_enabled = True
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
        return {"legacyParkRaw": self.park, "speedKmh": self.speed, "independentSelectorProof": False}

    def private(self, name):
        self.calls.append(("read", name))
        if name == live.HEARTBEAT:
            return {"schema": "denza-adas-consumer-v1", "bootId": self.boot_id,
                    "consumerSession": self.consumer, "elapsedMs": self.now}
        if name == live.STATE:
            if self.change_last_send:
                self.actual_send += 1
            arm = self.files.get(live.ARM)
            closed = self.closed and (arm is None or self.graceful_stopped)
            return {"schema": "denza-adas-live-state-v1", "bootId": self.boot_id, "consumerSession": self.consumer,
                    "armId": (OTHER if self.wrong_arm_id else arm["armId"]) if arm else None,
                    "sender": "stocknative",
                    "elapsedMs": self.now, "state": "STOPPED" if self.graceful_stopped else ("BLOCKED" if self.block_arm else "WAITING") if arm else ("DISARMED" if closed else "LIVE"),
                    "stopDetail": "original terminal output sent" if self.graceful_stopped else None,
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
        if name == live.LIVE_STOP and self.closed and self.graceful_enabled:
            self.graceful_stopped = True
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

    def historical_side_channel_session(self, variant):
        """In-memory old rollback fixture; the product can no longer create this arm."""
        row = self.arm()
        row["variant"] = dict(variant, instrumentNaviStatus=True)
        self.device.reader(self.jar, self.digest, CONSUMER, row["readerSession"], 60000,
                           navi_st_arm=row["armId"] if variant.get("naviSt") else None,
                           instrument_navi_status_arm=row["armId"])
        row["instrumentNaviStatus"] = {"original": self.device.instrument}
        if variant.get("naviSt"):
            row["naviSt"] = {"original": self.device.navi}
        live.save_ledger(self.ledger, row)
        return row

    def test_factory_arm_has_no_legacy_policy_or_side_channel_writes(self):
        row = self.arm()
        self.assertEqual(row["sender"], "stocknative")
        self.assertEqual(row["coordinateFrame"], live.DEFAULT_COORDINATE_FRAME)
        self.assertEqual(self.device.files[live.ARM]["coordinateFrame"], live.DEFAULT_COORDINATE_FRAME)
        self.assertNotIn("variant", row)
        self.assertNotIn("variant", self.device.files[live.ARM])
        self.assertIsNone(self.device.navi_arm)
        self.assertIsNone(self.device.instrument_arm)

    def test_legacy_variant_is_rejected_before_device_calls(self):
        for variant in ({}, {"id": "H0"}, {"instrumentNaviStatus": False}):
            with self.assertRaisesRegex(live.SessionError, "does not accept"):
                self.arm(variant=variant)
        self.assertEqual(self.device.calls, [])
        self.assertFalse(self.ledger.exists())

    def test_coordinate_modes_are_explicit_and_unknown_mode_never_calls_device(self):
        with self.assertRaisesRegex(live.SessionError, "unknown coordinate frame"):
            self.arm(coordinate_frame="GCJ02")
        self.assertEqual(self.device.calls, [])
        row = self.arm(coordinate_frame="ALTERNATE_ROUTES")
        self.assertEqual(row["coordinateFrame"], "ALTERNATE_ROUTES")
        self.assertEqual(self.device.files[live.ARM]["coordinateFrame"], "ALTERNATE_ROUTES")

    def test_factory_runtime_confirmation_is_required(self):
        original = self.device.private
        def missing_sender(name):
            value = original(name)
            if name == live.STATE and value:
                value.pop("sender", None)
            return value
        self.device.private = missing_sender
        with self.assertRaisesRegex(live.SessionError, "did not confirm factory sender"):
            self.arm()
        self.assertNotIn(live.ARM, self.device.files)
        self.assertEqual(self.device.enabled, 0)


    def test_instrument_arm_records_original_and_restores_independently(self):
        self.device.instrument = 4
        row = self.historical_side_channel_session(variant={"id": "H7", "instrumentNaviStatus": True})
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
        row = self.historical_side_channel_session(variant={"instrumentNaviStatus": True})
        self.device.files.pop(live.INSTRUMENT_NAVI_STATUS)
        self.device.instrument = 2
        restored = live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertEqual(restored["instrumentNaviStatusRestore"]["original"], 4)
        self.assertEqual(restored["instrumentNaviStatusRestore"]["after"], 4)
        self.assertLess(self.device.calls.index(("instrument_write", 4)), self.device.calls.index(("stock_enabled", 0)))


    def test_both_channel_restore_attempts_survive_independent_failures(self):
        row = self.historical_side_channel_session(variant={"naviSt": True, "instrumentNaviStatus": True})
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
        self.historical_side_channel_session(variant={"naviSt": True})
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
        row = self.historical_side_channel_session(variant={"instrumentNaviStatus": True})
        record = self.device.files[live.INSTRUMENT_NAVI_STATUS]
        for key in ("bootId", "consumerSession", "readerSession", "armId"):
            old = record[key]; record[key] = "wrong"
            self.assertIsNone(live.instrument_navi_status_ledger(self.device, row)); record[key] = old
        self.device.instrument_read_fails = True
        with self.assertRaisesRegex(live.SessionError, "instrument navigation status read failed"):
            live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertFalse(any(isinstance(c, tuple) and c[0] == "instrument_write" for c in self.device.calls))

    def test_arm_explicit_frame_schema_and_rollback_precedes_mutation(self):
        row = self.arm(); self.assertEqual(row["phase"], "ARMED")
        self.assertEqual(set(self.device.files[live.ARM]), {"schema", "bootId", "consumerSession", "readerSession", "armId",
                         "issuedAtElapsedMs", "expiresAtElapsedMs", "stockPackage", "stockDisabled", "coordinateFrame"})
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

    def test_restore_drains_original_output_then_disarms_and_restores_exact_default(self):
        row = self.arm(); self.device.calls.clear()
        restored = live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertEqual(restored["phase"], "RESTORED"); self.assertEqual(self.device.enabled, 0)
        self.assertNotIn(live.ARM, self.device.files)
        calls = self.device.calls
        self.assertTrue(restored["gracefulStop"]["completed"])
        self.assertLess(calls.index(("write", live.LIVE_STOP)), calls.index("disarm"))
        self.assertLess(calls.index("disarm"), calls.index(("write", live.STOP)))
        self.assertLess(calls.index(("read", live.STATE)), calls.index(("stock_enabled", 0)))
        self.assertEqual(self.device.files[live.STOP], {"schema": "denza-adas-reader-stop-v1", "bootId": BOOT,
                         "consumerSession": CONSUMER, "readerSession": row["readerSession"]})

    def test_restore_falls_back_to_immediate_disarm_after_bounded_drain(self):
        self.arm();self.device.graceful_enabled = False;self.device.calls.clear()
        restored = live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertFalse(restored["gracefulStop"]["completed"])
        self.assertEqual(restored["phase"], "RESTORED")
        self.assertLess(self.device.calls.index(("write", live.LIVE_STOP)), self.device.calls.index("disarm"))

    def test_expired_authorization_skips_graceful_output_and_disarms(self):
        self.arm();self.device.files[live.ARM]["expiresAtElapsedMs"] = self.device.now;self.device.calls.clear()
        restored = live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertFalse(restored["gracefulStop"]["requested"])
        self.assertNotIn(("write", live.LIVE_STOP), self.device.calls)
        self.assertIn("disarm", self.device.calls)

    def test_restore_never_removes_replacement_arm_after_drain_wait(self):
        self.arm();self.device.calls.clear();write = self.device.write_private
        def replace(name, row):
            write(name, row)
            if name == live.LIVE_STOP:
                self.device.graceful_stopped = False
                self.device.files[live.ARM]["armId"] = OTHER
        self.device.write_private = replace
        with self.assertRaisesRegex(live.SessionError, "replaced the arm"):
            live.restore(self.device, self.ledger, sleep=lambda _: None)
        self.assertNotIn("disarm", self.device.calls)

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
            self.assertEqual(plan["sender"], "stocknative")
            self.assertEqual(plan["coordinateFrame"], live.DEFAULT_COORDINATE_FRAME)
            self.assertNotIn("variant", plan)
            self.assertFalse(self.ledger.exists())

    def test_coordinate_frame_dry_run_choices_make_zero_device_calls(self):
        args = ["arm", "--serial", "owned:5555", "--ledger", str(self.ledger), "--apk-sha256", APK.sha256,
                "--version-code", str(APK.version_code), "--version-name", APK.version_name,
                "--reader-jar", str(self.jar), "--reader-sha256", self.digest, "--lifetime-ms", "60000", "--dry-run"]
        with patch.object(live, "Device", side_effect=AssertionError("must not create ADB device")):
            for mode in live.COORDINATE_FRAMES:
                out = io.StringIO()
                with contextlib.redirect_stdout(out):self.assertEqual(live.main(args + ["--coordinate-frame", mode]), 0)
                plan = json.loads(out.getvalue())
                self.assertEqual(plan["coordinateFrame"], mode);self.assertEqual(plan["deviceCalls"], 0)
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                live.main(args + ["--coordinate-frame", "GCJ02"])
        self.assertFalse(self.ledger.exists())


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

    def test_graceful_stop_uses_real_device_allowlists_atomic_write_and_readback(self):
        # FakeDevice bypasses Device's allowlists. Exercise the actual fixed command
        # builders, running their private-file scripts only in a temporary directory.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / "files").mkdir(); calls = []
            row = {"bootId": BOOT, "consumerSession": CONSUMER, "readerSession": OTHER,
                   "armId": "44444444-4444-4444-8444-444444444444"}
            (root / live.ARM).write_text(json.dumps({**row, "expiresAtElapsedMs": 20000}))

            def runner(args, **kwargs):
                calls.append(args)
                self.assertLessEqual(len(calls), 8)
                self.assertGreater(kwargs["timeout"], 0); self.assertLessEqual(kwargs["timeout"], 5)
                self.assertEqual(args[:3], ["unused-adb", "-s", "owned:5555"])
                tokens = shlex.split(args[4])
                if args[3] == "shell":
                    self.assertEqual(tokens, ["cat", "/proc/uptime"])
                    return types.SimpleNamespace(returncode=0, stdout=b"10.0 0.0\n", stderr=b"")
                self.assertEqual(args[3], "exec-out")
                self.assertEqual(tokens[:4], ["run-as", live.PACKAGE, "sh", "-c"])
                result = subprocess.run(["sh", "-c", tokens[4]], cwd=root, stdin=subprocess.DEVNULL,
                                        capture_output=True, timeout=min(2, kwargs["timeout"]), check=False)
                if (root / live.LIVE_STOP).exists():
                    (root / live.STATE).write_text(json.dumps({
                        **row, "schema": "denza-adas-live-state-v1", "elapsedMs": 10000,
                        "state": "STOPPED", "quiescent": True, "stopDetail": "original terminal output sent"}))
                return result

            device = live.Device("unused-adb", "owned:5555", runner=runner)
            result = live.graceful_stop(device, row, sleep=lambda _: self.fail("completed stop must not poll again"))
            self.assertTrue(result["requested"]); self.assertTrue(result["completed"])
            expected = {**row, "schema": "denza-adas-live-stop-v1", "requestedAtElapsedMs": 10000}
            self.assertEqual(json.loads((root / live.LIVE_STOP).read_text()), expected)
            self.assertFalse((root / (live.LIVE_STOP + ".tmp")).exists())
            self.assertEqual(len(calls), 6)  # elapsed, arm, atomic write, readback, state, elapsed.
            self.assertEqual(live.FILES, frozenset((live.ARM, live.HEARTBEAT, live.STATE, live.GATES,
                             live.STOP, live.ISOLATION, live.NAVI_ST, live.INSTRUMENT_NAVI_STATUS, live.LIVE_STOP)))
            for name in live.FILES - {live.ARM, live.STOP, live.LIVE_STOP}:
                with self.subTest(name=name), self.assertRaisesRegex(live.SessionError, "private-write allowlist"):
                    device.write_private(name, {})
            with self.assertRaisesRegex(live.SessionError, "private-file allowlist"):
                device.private("files/unrelated.json")
            self.assertEqual(len(calls), 6, "denied paths never reach the runner")

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
