import base64
import tempfile
import unittest
from pathlib import Path

from relay.cag_state import RelayState, StateError, public_key_fingerprint


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
        self.host_key = public_key(b"host")

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def enroll_device(self):
        invite = self.state.create_invite()
        payload = {
            "label": "Test car",
            "tunnel_public_key": self.device_key,
            "inner_host_key": self.host_key,
            "endpoint_mode": "smart",
            "endpoint_host": "127.0.0.1",
        }
        return invite, payload, self.state.enroll(invite["code"], payload)

    def test_enrollment_is_idempotent_after_lost_response(self):
        invite, payload, first = self.enroll_device()
        second = self.state.enroll(invite["code"], payload)

        self.assertEqual(first, second)
        self.assertEqual(20_000, first["relay_device_port"])
        self.assertEqual("127.0.0.1", first["endpoint_host"])
        self.assertEqual(1, len(self.state.authorized_keys("cag-device")))
        self.assertIn(first["device_id"], self.state.authorized_keys("cag-control")[0])

    def test_invite_cannot_enroll_a_different_device(self):
        invite, _, _ = self.enroll_device()
        with self.assertRaisesRegex(StateError, "already been used"):
            self.state.enroll(
                invite["code"],
                {
                    "tunnel_public_key": public_key(b"other"),
                    "inner_host_key": self.host_key,
                },
            )

    def test_authentication_locks_source_after_five_failures(self):
        invite = self.state.create_invite()
        for _ in range(5):
            self.assertFalse(self.state.auth_check("cag-enroll", "AAAA-AAAA", "client"))
        self.assertFalse(self.state.auth_check("cag-enroll", invite["code"], "client"))

        self.clock.value += 301
        self.assertTrue(self.state.auth_check("cag-enroll", invite["code"], "client"))

    def test_pair_open_is_idempotent(self):
        _, _, device = self.enroll_device()
        first = self.state.pair_open(device["device_id"], "request-1234")
        second = self.state.pair_open(device["device_id"], "request-1234")
        self.assertEqual(first, second)

    def test_replacement_keeps_old_grant_until_commit(self):
        _, _, device = self.enroll_device()
        device_id = device["device_id"]
        first_key = public_key(b"first-client")
        first_fp = public_key_fingerprint(first_key)
        first_code = self.state.pair_open(device_id, "first-request")["code"]
        self.state.pair_submit(first_code, {"public_key": first_key, "label": "First Mac"})
        self.state.pair_commit(device_id, first_fp)

        second_key = public_key(b"second-client")
        second_fp = public_key_fingerprint(second_key)
        second_code = self.state.pair_open(device_id, "second-request")["code"]
        self.state.pair_submit(second_code, {"public_key": second_key, "label": "Second Mac"})
        pending_keys = "\n".join(self.state.authorized_keys("cag-client"))
        self.assertIn(first_key, pending_keys)
        self.assertIn(second_key, pending_keys)
        self.assertEqual(first_fp, self.state.device_status(device_id)["active_client_fingerprint"])

        committed = self.state.pair_commit(device_id, second_fp)
        active_keys = "\n".join(self.state.authorized_keys("cag-client"))
        self.assertNotIn(first_key, active_keys)
        self.assertIn(second_key, active_keys)
        self.assertEqual(first_fp, committed["replaced_fingerprint"])

    def test_expired_pending_rolls_back_to_old_client(self):
        _, _, device = self.enroll_device()
        device_id = device["device_id"]
        old_key = public_key(b"old-client")
        old_fp = public_key_fingerprint(old_key)
        code = self.state.pair_open(device_id, "old-request", 60)["code"]
        self.state.pair_submit(code, {"public_key": old_key})
        self.state.pair_commit(device_id, old_fp)

        new_key = public_key(b"new-client")
        code = self.state.pair_open(device_id, "new-request", 60)["code"]
        self.state.pair_submit(code, {"public_key": new_key})
        self.clock.value += 61

        keys = "\n".join(self.state.authorized_keys("cag-client"))
        self.assertIn(old_key, keys)
        self.assertNotIn(new_key, keys)
        self.assertIsNone(self.state.device_status(device_id)["pending_client_fingerprint"])

    def test_manual_disable_removes_tunnel_and_client_keys(self):
        _, _, device = self.enroll_device()
        device_id = device["device_id"]
        self.state.set_enabled(device_id, False)
        self.assertEqual([], self.state.authorized_keys("cag-device"))
        self.assertEqual(1, len(self.state.authorized_keys("cag-control")))
        self.assertEqual([], self.state.authorized_keys("cag-client"))


if __name__ == "__main__":
    unittest.main()
