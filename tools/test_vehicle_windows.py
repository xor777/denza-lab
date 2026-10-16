"""Protocol and write-boundary tests; never contact ADB or the car."""
import http.client
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

import vehicle_windows as w


def reply(value):
    return f"Result: Parcel(00000000 {value & 0xffffffff:08x}   '........')"


class FakeRemote(w.Remote):
    def __init__(self, directory):
        super().__init__("fake-device", log_dir=Path(directory))
        self.identity = ["CANFD", "168", "11111111-1111-1111-1111-111111111111"]
        self.live_identity = list(self.identity)
        self.park, self.permit, self.initialized, self.thermal, self.percent = 1, 0, 1, 0, 42
        self.writes = []
        self.write_result = "Result: Parcel(00000001    '....')"

    def shell(self, command, timeout=5):
        if command.startswith("service call autoservice 6"):
            self.writes.append(command)
            if isinstance(self.write_result, Exception):
                raise self.write_result
            return self.write_result
        if command == w.IDENTITY:
            return "\n".join(self.live_identity)
        output = "\n".join(self.live_identity) + "\n"
        for part in command.split("; "):
            if not part.startswith("echo @"):
                continue
            key = part[6:]
            value = getattr(self, key.rsplit("_", 1)[-1])
            output += f"@{key}\n{reply(value)}\n"
        return output


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.remote = FakeRemote(self.tmp.name)

    def test_parcel_rejects_bad_exception_truncation_and_extra_payload(self):
        self.assertEqual(w.parcel(reply(-10011)), -10011)
        for text in ("", "service not found", "Result: Parcel(00000000)",
                     reply(0).replace("00000000", "ffffffff", 1),
                     reply(0).replace("   '", " 00000000   '"), reply(0) + reply(0)):
            with self.subTest(text=text), self.assertRaises(w.ControlError):
                w.parcel(text)

    def test_native_set_reply_is_a_single_raw_hal_word(self):
        self.assertEqual(w.set_result("Result: Parcel(00000001    '....')"),1)
        self.assertEqual(w.set_result("Result: Parcel(ffffd8e5    '....')"),-10011)
        with self.assertRaises(w.ControlError):
            w.set_result(reply(0))

    def test_only_four_window_fids_and_three_commands_with_null_binder(self):
        for key, fid in (("fl",1125122104),("fr",1125122107),("rl",1125122112),("rr",1125122115)):
            for action, value in (("open",1),("close",2),("stop",3)):
                self.assertEqual(w.set_command(key,action), f"service call autoservice 6 i32 1001 i32 {fid} i32 {value} null")
        for key, action in (("fl; reboot", "open"), ("fl", "4"), ("all", "close")):
            with self.assertRaises(w.ControlError):
                w.set_command(key,action)

    def test_snapshot_never_writes_and_keeps_window_order(self):
        state = self.remote.snapshot()
        self.assertEqual(list(state["windows"]), ["fl","fr","rl","rr"])
        self.assertEqual(state["windows"]["fr"]["percent"],42)
        self.assertEqual(self.remote.writes,[])

    def test_moving_unknown_uncalibrated_or_protected_blocks_write(self):
        for field, value in (("park",0),("park",-10011),("permit",1),("permit",-10011),
                             ("initialized",2),("thermal",1),("thermal",-10011),("percent",255)):
            with self.subTest(field=field):
                remote = FakeRemote(self.tmp.name)
                setattr(remote,field,value)
                with self.assertRaises(w.ControlError):
                    remote.act("fr","close")
                self.assertEqual(remote.writes,[])

    def test_guard_is_fresh_at_click_and_stop_ignores_movement_gates(self):
        self.remote.snapshot()
        self.remote.park = 0
        with self.assertRaises(w.ControlError):
            self.remote.act("fl","open")
        self.remote.thermal = 1
        self.remote.percent = -10011
        self.assertTrue(self.remote.act("fl","stop")["accepted"])
        self.assertEqual(len(self.remote.writes),1)

    def test_different_boot_blocks_all_writes_including_stop(self):
        self.remote.live_identity[2] = "new-boot"
        for action in w.ACTIONS:
            with self.assertRaises(w.ControlError):
                self.remote.act("fl",action)
        self.assertEqual(self.remote.writes,[])

    def test_rejected_and_uncertain_results_never_retry_or_report_success(self):
        for response in (reply(-10011),"garbled",w.ControlError("timeout")):
            self.remote.write_result = response
            self.remote.writes.clear()
            result = self.remote.act("fr","open")
            self.assertFalse(result["accepted"])
            self.assertEqual(len(self.remote.writes),1)
        records = [json.loads(line) for line in (Path(self.tmp.name)/"actions.jsonl").read_text().splitlines()]
        self.assertEqual([entry["phase"] for entry in records],["attempt","result"]*3)

    def test_verify_requires_both_matching_files(self):
        good = "\n".join(self.remote.identity + [f"{value}  {key}" for key,value in w.HASHES.items()])
        remote = w.Remote("fake")
        with patch.object(remote,"shell",return_value=good):
            remote.verify()
        with patch.object(remote,"shell",return_value=good.replace("aa3acd", "000000")):
            with self.assertRaises(w.ControlError):
                remote.verify()

    def test_adb_timeout_is_one_attempt_with_explicit_serial(self):
        remote = w.Remote("127.0.0.1:5555")
        with patch.object(subprocess,"run",side_effect=subprocess.TimeoutExpired("adb",5)) as run:
            with self.assertRaises(w.ControlError):
                remote.shell(w.set_command("fl","stop"))
        self.assertEqual(run.call_count,1)
        self.assertEqual(run.call_args[0][0][:4],["adb","-s","127.0.0.1:5555","shell"])

    def test_http_get_auth_origin_and_busy_cannot_trigger_a_write(self):
        server = w.ThreadingHTTPServer(("127.0.0.1",0), w.handler(self.remote,"test-token",0))
        port = server.server_address[1]
        server.RequestHandlerClass = w.handler(self.remote,"test-token",port)
        thread = threading.Thread(target=server.serve_forever,daemon=True)
        thread.start()
        try:
            def request(method,path,body=None,headers=None):
                conn = http.client.HTTPConnection("127.0.0.1",port,timeout=3)
                conn.request(method,path,body,headers or {})
                response = conn.getresponse()
                response.read()
                conn.close()
                return response.status
            body = json.dumps({"window":"fl","action":"close"})
            headers = {"X-Window-Token":"test-token","Content-Type":"application/json"}
            self.assertEqual(request("GET","/api/action"),404)
            self.assertEqual(request("POST","/api/action",body),403)
            self.assertEqual(request("POST","/api/action",body,{**headers,"Origin":"https://other.example"}),403)
            self.assertEqual(request("POST","/api/action",'{"window":[],"action":"open"}',headers),400)
            self.remote.lock.acquire()
            try:
                self.assertEqual(request("POST","/api/action",body,headers),409)
            finally:
                self.remote.lock.release()
            self.assertEqual(request("GET","/api/state",headers=headers),200)
            self.assertEqual(self.remote.writes,[])
            self.assertEqual(request("POST","/api/action",body,headers),200)
            self.assertEqual(self.remote.writes,[w.set_command("fl","close")])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
