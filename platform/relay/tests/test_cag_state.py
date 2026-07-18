import base64
import json
import os
import stat
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

from relay.cag_state import (
    DEVICE_LEASE_SECONDS,
    RelayState,
    StateError,
    public_key_fingerprint,
)


def public_key(seed: bytes) -> str:
    blob = b"\x00\x00\x00\x0bssh-ed25519\x00\x00\x00 " + seed.ljust(32, b"0")[:32]
    return "ssh-ed25519 " + base64.b64encode(blob).decode()


class MutableClock:
    def __init__(self, value: int = 1_700_000_000) -> None:
        self.value = value

    def __call__(self) -> int:
        return self.value


class RelayStateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.clock = MutableClock()
        self.state = RelayState(Path(self.temporary.name), self.clock)
        self.device_key = public_key(b"device")
        self.control_key = public_key(b"control")
        self.host_key = public_key(b"host")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def enroll_device(self, suffix: str = ""):
        invite = self.state.create_invite()
        payload = {
            "label": "Test car" + suffix,
            "tunnel_public_key": public_key(("device" + suffix).encode()),
            "control_public_key": public_key(("control" + suffix).encode()),
            "inner_host_key": public_key(("host" + suffix).encode()),
            "endpoint_mode": "smart",
            "endpoint_host": "127.0.0.1",
        }
        return invite, payload, self.state.enroll(invite["code"], payload)

    def pair_and_commit(self, device_id: str, seed: bytes):
        key = public_key(seed)
        fingerprint = public_key_fingerprint(key)
        code = self.state.pair_open(device_id, "request-" + seed.hex())[
            "code"
        ]
        self.state.pair_submit(code, {"public_key": key, "label": seed.decode()})
        self.state.pair_commit(device_id, fingerprint)
        return key, fingerprint

    def raw_state(self):
        return json.loads((Path(self.temporary.name) / "state.json").read_text())

    def test_enrollment_is_idempotent_and_uses_separate_control_key(self):
        invite, payload, first = self.enroll_device()
        second = self.state.enroll(invite["code"], payload)

        self.assertEqual(first, second)
        self.assertEqual(20_000, first["relay_device_port"])
        self.assertEqual(self.clock.value + DEVICE_LEASE_SECONDS, first["lease_expires_at"])
        self.assertIn(payload["tunnel_public_key"], self.state.authorized_keys("cag-device")[0])
        self.assertIn(payload["control_public_key"], self.state.authorized_keys("cag-control")[0])

    def test_invite_cannot_enroll_a_different_device(self):
        invite, _, _ = self.enroll_device()
        with self.assertRaisesRegex(StateError, "already been used"):
            self.state.enroll(
                invite["code"],
                {
                    "tunnel_public_key": public_key(b"other"),
                    "control_public_key": self.control_key,
                    "inner_host_key": self.host_key,
                },
            )

    def test_authentication_is_read_only(self):
        invite = self.state.create_invite()
        state_path = Path(self.temporary.name) / "state.json"
        before = state_path.stat().st_mtime_ns
        self.assertFalse(self.state.auth_check("cag-enroll", "AAAA-AAAA", "client"))
        self.assertTrue(self.state.auth_check("cag-enroll", invite["code"], "client"))
        self.assertEqual(before, state_path.stat().st_mtime_ns)

    def test_pair_open_reuses_request_and_rejects_another_window(self):
        _, _, device = self.enroll_device()
        first = self.state.pair_open(device["device_id"], "request-1234")
        self.assertEqual(first, self.state.pair_open(device["device_id"], "request-1234"))
        with self.assertRaisesRegex(StateError, "window is already open"):
            self.state.pair_open(device["device_id"], "request-5678")

    def test_replacement_prunes_old_client_after_commit(self):
        _, _, device = self.enroll_device()
        device_id = device["device_id"]
        first_key, first_fp = self.pair_and_commit(device_id, b"first-client")

        second_key = public_key(b"second-client")
        second_fp = public_key_fingerprint(second_key)
        second_code = self.state.pair_open(device_id, "second-request")["code"]
        self.state.pair_submit(second_code, {"public_key": second_key, "label": "Second"})
        pending_keys = "\n".join(self.state.authorized_keys("cag-client"))
        self.assertIn(first_key, pending_keys)
        self.assertIn(second_key, pending_keys)

        self.state.pair_commit(device_id, second_fp)
        current = self.raw_state()
        self.assertNotIn(first_fp, current["clients"])
        self.assertEqual([second_fp], list(current["clients"]))

    def test_expired_pending_is_denied_then_gc_prunes_client(self):
        _, _, device = self.enroll_device()
        device_id = device["device_id"]
        old_key, _ = self.pair_and_commit(device_id, b"old-client")
        new_key = public_key(b"new-client")
        code = self.state.pair_open(device_id, "new-request", 60)["code"]
        self.state.pair_submit(code, {"public_key": new_key})
        self.clock.value += 61

        keys = "\n".join(self.state.authorized_keys("cag-client"))
        self.assertIn(old_key, keys)
        self.assertNotIn(new_key, keys)
        result = self.state.gc()
        self.assertEqual(1, result["removed_clients"])
        self.assertEqual(1, len(self.raw_state()["clients"]))

    def test_lease_expiry_cascades_and_reuses_port(self):
        _, _, first = self.enroll_device("-one")
        self.pair_and_commit(first["device_id"], b"client-one")
        self.clock.value += DEVICE_LEASE_SECONDS + 1
        self.assertEqual([], self.state.authorized_keys("cag-device"))

        result = self.state.gc()
        self.assertEqual(1, result["removed_devices"])
        self.assertTrue(result["recycle_sessions"])
        current = self.raw_state()
        self.assertEqual({}, current["devices"])
        self.assertEqual({}, current["clients"])
        self.assertEqual({}, current["grants"])

        _, _, second = self.enroll_device("-two")
        self.assertEqual(20_000, second["relay_device_port"])

    def test_disabled_device_expires_but_keeps_control_until_then(self):
        _, _, device = self.enroll_device()
        self.state.set_enabled(device["device_id"], False)
        self.assertEqual([], self.state.authorized_keys("cag-device"))
        self.assertEqual(1, len(self.state.authorized_keys("cag-control")))
        self.clock.value += DEVICE_LEASE_SECONDS + 1
        self.assertEqual([], self.state.authorized_keys("cag-control"))

    def test_renew_lease_extends_expiry(self):
        _, _, device = self.enroll_device()
        self.clock.value += 100
        renewed = self.state.renew_lease(device["device_id"])
        self.assertEqual(self.clock.value + DEVICE_LEASE_SECONDS, renewed["lease_expires_at"])

    def test_remove_device_cascades_immediately(self):
        _, _, device = self.enroll_device()
        self.pair_and_commit(device["device_id"], b"client")
        result = self.state.remove_device(device["device_id"])
        self.assertTrue(result["recycle_sessions"])
        self.assertEqual(0, self.state.doctor()["devices"])

    def test_pair_commit_is_idempotent_after_lost_response(self):
        _, _, device = self.enroll_device()
        key = public_key(b"candidate")
        fingerprint = public_key_fingerprint(key)
        code = self.state.pair_open(device["device_id"], "lost-response")["code"]
        self.state.pair_submit(code, {"public_key": key, "label": "Candidate"})
        first = self.state.pair_commit(device["device_id"], fingerprint)
        second = self.state.pair_commit(device["device_id"], fingerprint)
        self.assertEqual(first, second)

    def test_pair_submit_is_idempotent_after_lost_response(self):
        _, _, device = self.enroll_device()
        key = public_key(b"submit-candidate")
        code = self.state.pair_open(device["device_id"], "submit-response")["code"]
        payload = {"public_key": key, "label": "Candidate"}
        self.assertEqual(
            self.state.pair_submit(code, payload),
            self.state.pair_submit(code, payload),
        )

    def test_one_computer_key_cannot_reference_two_devices(self):
        _, _, first = self.enroll_device("-first")
        _, _, second = self.enroll_device("-second")
        key, _ = self.pair_and_commit(first["device_id"], b"one-computer")
        code = self.state.pair_open(second["device_id"], "second-device")["code"]
        with self.assertRaisesRegex(StateError, "another device"):
            self.state.pair_submit(code, {"public_key": key})

    def test_port_allocator_wraps_at_65535(self):
        self.state.create_invite()
        state_path = Path(self.temporary.name) / "state.json"
        current = self.raw_state()
        current["next_port"] = 65_535
        state_path.write_text(json.dumps(current))

        _, _, last = self.enroll_device("-last")
        _, _, wrapped = self.enroll_device("-wrapped")
        self.assertEqual(65_535, last["relay_device_port"])
        self.assertEqual(20_000, wrapped["relay_device_port"])

    def test_v1_migration_gives_devices_a_grace_lease(self):
        root = Path(self.temporary.name)
        (root / "locks").mkdir(exist_ok=True)
        (root / "locks" / "state.lock").touch()
        device_id = "0123456789abcdef"
        legacy = {
            "version": 1,
            "next_port": 20001,
            "devices": {
                device_id: {
                    "id": device_id,
                    "label": "Legacy",
                    "relay_port": 20000,
                    "tunnel_public_key": self.device_key,
                    "tunnel_fingerprint": public_key_fingerprint(self.device_key),
                    "inner_host_key": self.host_key,
                    "endpoint_mode": "smart",
                    "endpoint_host": "127.0.0.1",
                    "enabled": True,
                    "created_at": self.clock.value,
                }
            },
            "clients": {},
            "grants": {},
            "pending": {},
            "codes": {},
            "auth_limits": {"old": {"attempts": 1}},
        }
        (root / "state.json").write_text(json.dumps(legacy))

        self.state.migrate()
        migrated = self.raw_state()
        self.assertEqual(2, migrated["version"])
        self.assertNotIn("auth_limits", migrated)
        self.assertEqual(self.device_key, migrated["devices"][device_id]["control_public_key"])
        self.assertEqual(
            self.clock.value + DEVICE_LEASE_SECONDS,
            migrated["devices"][device_id]["lease_expires_at"],
        )
        before = (root / "state.json").stat().st_mtime_ns
        self.state.migrate()
        self.assertEqual(before, (root / "state.json").stat().st_mtime_ns)

    def test_40_devices_over_12_months_keep_only_current_clients(self):
        devices = [self.enroll_device(f"-{index:02d}")[2] for index in range(40)]
        stable_size = None
        for month in range(12):
            for _ in range(3):
                self.clock.value += 10 * 24 * 60 * 60
                for device in devices:
                    self.state.renew_lease(device["device_id"])
            for index, device in enumerate(devices):
                self.pair_and_commit(
                    device["device_id"],
                    f"m{month:02d}-d{index:02d}".encode(),
                )
            self.state.gc()
            current = self.raw_state()
            self.assertEqual(40, len(current["devices"]))
            self.assertEqual(40, len(current["grants"]))
            self.assertEqual(40, len(current["clients"]))
            self.assertEqual({}, current["pending"])
            if month == 1:
                stable_size = len(json.dumps(current, sort_keys=True))
            elif month > 1:
                self.assertLessEqual(
                    len(json.dumps(current, sort_keys=True)),
                    stable_size + 200,
                )

    def test_authorized_key_reads_can_run_concurrently_without_writes(self):
        self.enroll_device()
        state_path = Path(self.temporary.name) / "state.json"
        before = state_path.stat().st_mtime_ns
        failures = []

        def read_keys():
            try:
                for _ in range(20):
                    self.state.authorized_keys("cag-device")
            except Exception as error:  # pragma: no cover - assertion reports the error
                failures.append(error)

        threads = [threading.Thread(target=read_keys) for _ in range(8)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual([], failures)
        self.assertEqual(before, state_path.stat().st_mtime_ns)

    def test_atomic_write_fsyncs_file_and_directory(self):
        real_fsync = os.fsync
        fsynced_directory = False

        def tracking_fsync(fd):
            nonlocal fsynced_directory
            if stat.S_ISDIR(os.fstat(fd).st_mode):
                fsynced_directory = True
            return real_fsync(fd)

        with mock.patch("relay.cag_state.os.fsync", side_effect=tracking_fsync):
            self.state.create_invite()
        self.assertTrue(fsynced_directory)


if __name__ == "__main__":
    unittest.main()
