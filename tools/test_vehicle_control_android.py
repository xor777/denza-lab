"""Android adapter lifecycle regressions; fake transport only, no ADB."""
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "experiments/vehicle-control-probe/src/main/python"))
import android_remote as a

class LifecycleTest(unittest.TestCase):
    def setUp(self):
        a._instance = None
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(lambda: setattr(a, "_instance", None))

    def test_recreation_during_read_reuses_client_without_wait_or_verification_reset(self):
        original, recreated = object(), object()
        remote = a.create(original, self.directory.name, "same-key")
        remote.verified = True
        remote.pending_stops.add("seat.fl.backrest")
        started, release = threading.Event(), threading.Event()
        def snapshot():
            started.set()
            self.assertTrue(release.wait(2))
            return {"read": True}
        remote.snapshot = snapshot
        worker = threading.Thread(target=lambda: remote.request(""))
        worker.start()
        self.assertTrue(started.wait(1))
        try:
            self.assertIs(a.create(recreated, self.directory.name, "same-key"), remote)
            self.assertIs(remote.transport, original)
            self.assertTrue(remote.verified)
            self.assertEqual(remote.pending_stops, {"seat.fl.backrest"})
            self.assertIn("error", json.loads(remote.request('{"window":"fl","action":"open"}')))
        finally:
            release.set()
            worker.join(2)
        self.assertFalse(worker.is_alive())

    def test_explicit_identity_change_waits_for_operation_boundary_and_preserves_stop_latch(self):
        original, replacement = object(), object()
        remote = a.create(original, self.directory.name, "old-key")
        remote.verified = True
        remote.pending_stops.add("seat.fl.backrest")
        remote.lock.acquire()
        try:
            self.assertIs(a.create(replacement, self.directory.name, "new-key"), remote)
            self.assertIs(remote.transport, original)
            self.assertEqual(remote.binding_identity, "old-key")
        finally:
            remote.lock.release()
        calls = []
        remote.verify = lambda: calls.append(remote.transport)
        remote.snapshot = lambda: {"state": "read"}
        self.assertEqual(json.loads(remote.request("")), {"state": "read"})
        self.assertEqual(calls, [replacement])
        self.assertEqual(remote.binding_identity, "new-key")
        self.assertEqual(remote.pending_stops, {"seat.fl.backrest"})

if __name__ == "__main__":
    unittest.main()
