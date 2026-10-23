import unittest
import sys
import json
import subprocess

from tools.adas_bridge.status import StatusTracker, adb_command, live_lines, parse_line


def line(tag,body,clock="19:00:52.672",pid=16346):
    return f"10-23 {clock} {pid} 16430 E {tag}: [SomeIpClient_Thread]{body}"


class StatusTest(unittest.TestCase):
    def test_actual_pilot_available_and_active_are_distinct(self):
        available=parse_line(line("PilotStatusProxy","2 0 2  last:0 0 2 intercept:true"))
        active=parse_line(line("PilotStatusProxy","1 0 2  last:2 0 2 intercept:true"))
        self.assertEqual(available["classified"],dict(dnp="available",icc="off_or_not_offered",acc="available"))
        self.assertEqual(active["classified"]["dnp"],"active")
        self.assertEqual(active["service"],13)
        self.assertEqual(active["eventId"],"0x8002")

    def test_mode_only_never_claims_noa_available(self):
        event=parse_line(line("NewBroadcastInfoProxy","noaMode:2  lastNoaMode:2"))
        view=StatusTracker().accept(event)["status"]
        self.assertEqual(view["noaFamily"]["state"],"city")
        self.assertEqual(view["noa"]["state"],"unknown")

    def test_route_ack_is_not_noa_and_status_code_is_callback_gate(self):
        event=parse_line(line("DriveRouteManager","MMTPathSelected curPathId:100002 selectPathID:100002"))
        view=StatusTracker().accept(event)["status"]
        self.assertEqual(event["fields"]["statusCode"],200)
        self.assertEqual(view["routeSelection"]["state"],"selection_confirmed")
        self.assertEqual(view["noa"]["state"],"unknown")
        self.assertFalse(view["noaEligibilityFromSelection"])

    def test_different_path_and_selection_error_remain_distinct(self):
        tracker=StatusTracker()
        event=parse_line(line("DriveRouteManager","MMTPathSelected curPathId:100002 selectPathID:100003"))
        self.assertEqual(tracker.accept(event)["status"]["routeSelection"]["state"],"different_path_confirmed")
        error=parse_line(line("DriveRouteManager","MMTPathSelectedError:","19:00:53.000"))
        self.assertIsNone(error["fields"]["statusCode"])
        self.assertEqual(tracker.accept(error)["status"]["routeSelection"]["state"],"unknown")

    def test_actual_alarm_field_order(self):
        event=parse_line(line("PilotAlarmAndNoticeInfoProxy","539 2 105 1  last:0 0 0 0"))
        self.assertEqual(event["fields"],dict(pilotAlarmReason=539,pilotNotice=2,noticeDistance=105,alarmStage=1))

    def test_path_match_schema_not_guessed_eligibility(self):
        event=parse_line(line("PathMatchProxy","parseStruct:EHPtoNav{pathID=9223372036854775806, totalNOADistance=123, routeBeginIdx=2, routeEndIdx=8, statusCode=200}"))
        self.assertEqual(event["fields"]["pathID"],9223372036854775806)
        self.assertEqual(event["fields"]["routeBeginIdx"],2)
        self.assertEqual(StatusTracker().accept(event)["status"]["noa"]["state"],"unknown")

    def test_fid_enums_do_not_reuse_pilot_mapping(self):
        for name,value,fid in (("DNP_STATE",6,"0x29C00020"),("ACC_MODE",3,"0x29C0000C")):
            event=parse_line(line("BydAutoNoaProxy",f"onDataEventChanged ADAS_{name} value:{value}"))
            self.assertEqual(event["fields"]["fid"],fid)
            self.assertEqual(event["classified"],"raw_separate_fid_enum")
            self.assertEqual(StatusTracker().accept(event)["status"]["noa"]["state"],"unknown")

    def test_stale_pilot_becomes_unknown_with_last_raw_evidence(self):
        tracker=StatusTracker(30000)
        event=parse_line(line("PilotStatusProxy","2 0 0  last:0 0 0 intercept:false"))
        tracker.accept(event)
        view=tracker.view(event["capturedAtMs"]+30001)
        self.assertEqual(view["noa"]["state"],"unknown")
        self.assertEqual(view["noa"]["freshness"],"stale")
        self.assertEqual(view["noa"]["lastFields"]["dnp"],2)

    def test_new_pid_does_not_inherit_old_pilot(self):
        tracker=StatusTracker()
        tracker.accept(parse_line(line("PilotStatusProxy","1 0 0  last:0 0 0 intercept:true")))
        view=tracker.accept(parse_line(line("NewBroadcastInfoProxy","noaMode:1 lastNoaMode:2","19:00:53.000",pid=20000)))["status"]
        self.assertEqual(view["noa"]["state"],"unknown")
        self.assertEqual(view["noaFamily"]["state"],"highway")

    def test_out_of_order_record_does_not_replace_later_state(self):
        tracker=StatusTracker()
        tracker.accept(parse_line(line("PilotStatusProxy","1 0 0  last:0 0 0 intercept:true","19:00:53.000")))
        result=tracker.accept(parse_line(line("PilotStatusProxy","0 0 0  last:0 0 0 intercept:true","19:00:52.000")))
        self.assertEqual(result["status"]["noa"]["state"],"active")

    def test_delayed_old_pid_cannot_switch_view_back_after_restart(self):
        tracker=StatusTracker()
        tracker.accept(parse_line(line("PilotStatusProxy","1 0 0  last:0 0 0 intercept:true","19:00:52.000",pid=100)))
        tracker.accept(parse_line(line("NewBroadcastInfoProxy","noaMode:1 lastNoaMode:2","19:00:53.000",pid=200)))
        result=tracker.accept(parse_line(line("PilotStatusProxy","1 0 0  last:0 0 0 intercept:true","19:00:52.500",pid=100)))
        self.assertEqual(result["status"]["sourcePid"],200)
        self.assertEqual(result["status"]["noa"]["state"],"unknown")
        self.assertEqual(result["status"]["noaFamily"]["state"],"highway")

    def test_equal_timestamp_old_pid_does_not_change_current_owner(self):
        tracker=StatusTracker()
        tracker.accept(parse_line(line("NewBroadcastInfoProxy","noaMode:1 lastNoaMode:2","19:00:53.000",pid=200)))
        result=tracker.accept(parse_line(line("PilotStatusProxy","1 0 0  last:0 0 0 intercept:true","19:00:53.000",pid=100)))
        self.assertEqual(result["status"]["sourcePid"],200)
        self.assertEqual(result["status"]["noa"]["state"],"unknown")

    def test_unknown_numeric_pilot_stays_unknown(self):
        event=parse_line(line("PilotStatusProxy","255 -1 3  last:0 0 0 intercept:true"))
        self.assertEqual(set(event["classified"].values()),{"unknown"})

    def test_logger_prefix_and_epoch_and_full_year(self):
        self.assertEqual(parse_line(line("D/PilotStatusProxy","1 0 0  last:0 0 0 intercept:true"))["sourceTag"],"PilotStatusProxy")
        event=parse_line("1792778000.123 1 2 E PilotStatusProxy: 1 0 0  last:0 0 0 intercept:true")
        self.assertEqual(event["capturedAtMs"],1792778000123)
        self.assertIsNotNone(parse_line("2026-"+line("PilotStatusProxy","1 0 0  last:0 0 0 intercept:true")))

    def test_notifications_do_not_promote_pilot(self):
        event=parse_line(line("HandJsonNoaUtil","takeoverLevel:0, takeoverId:502, torIdErrorId:0"))
        self.assertEqual(event["classified"],"Available to enable CNOA")
        self.assertEqual(StatusTracker().accept(event)["status"]["noa"]["state"],"unknown")

    def test_unrelated_or_malformed_log_is_ignored(self):
        self.assertIsNone(parse_line(line("PilotStatusProxy","failed to parse message")))
        self.assertIsNone(parse_line(line("SomeIPMatrixManager","sendTrafficInfo roadClass:6")))
        self.assertIsNone(parse_line("not logcat"))

    def test_live_command_is_strictly_explicit_read_only_logcat(self):
        command=adb_command("/path/adb","127.0.0.1:5555")
        self.assertEqual(command[:4],["/path/adb","-s","127.0.0.1:5555","logcat"])
        self.assertNotIn("shell",command)
        self.assertNotIn("-c",command)
        with self.assertRaises(ValueError):adb_command("adb","x;am force-stop app")

    def test_stream_reader_drains_two_lines_from_one_kernel_chunk(self):
        command=[sys.executable,"-u","-c","import sys,time;sys.stdout.write('first\\nsecond\\n');sys.stdout.flush();time.sleep(2)"]
        rows=live_lines(command,.3)
        try:
            self.assertEqual(next(rows)[0],"first")
            self.assertEqual(next(rows)[0],"second")
        finally:rows.close()

    def test_stream_reader_keeps_fragmented_utf8_and_does_not_wait_for_newline(self):
        command=[sys.executable,"-u","-c","import os,time;os.write(1,b'\\xd0');time.sleep(.02);os.write(1,b'\\x94\\n');time.sleep(2)"]
        rows=live_lines(command,.3)
        try:self.assertEqual(next(rows)[0],"Д")
        finally:rows.close()

    def test_empty_input_still_reports_reader_alive_and_unknown(self):
        result=subprocess.run([sys.executable,"-m","tools.adas_bridge.status","--input","-","--jsonl"],
                              input="",text=True,capture_output=True,check=True,timeout=5)
        event=json.loads(result.stdout)
        self.assertEqual(event["event"],"observation_started")
        self.assertEqual(event["status"]["noa"]["state"],"unknown")
        self.assertEqual(event["readerState"],"waiting_for_stock_status_logs")


if __name__=="__main__":
    unittest.main()
