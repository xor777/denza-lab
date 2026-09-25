import json
from pathlib import Path
import subprocess
import sys
import unittest
import cloud_downlink_observer as observer

class ObserverTest(unittest.TestCase):
    def test_decoded_frame_keeps_only_controlled_numbers(self):
        row=observer.classify('1790269621.235  112  1078 E [BYDCLOUD]main: server_data_to_mcu mFuncNum is 301,replyFlag is 254,mFuncVision 1 VIN=PRIVATE_VIN ICCID=89010000000000000001')
        self.assertEqual(('tcp_decoded_frame',301,254,1),(row['event'],row['command'],row['reply_flag'],row['version']))
        self.assertNotIn('PRIVATE',json.dumps(row))
        self.assertNotIn('89010000000000000001',json.dumps(row))
    def test_mqtt_topic_and_payload_are_discarded(self):
        row=observer.classify('1790269621.235 223 99 I mqttserv: [onMessageArrivedV2] topic: CR/project/SECRET_ID/IVI/CONTROL/VEH payload=SECRET_BODY')
        self.assertEqual('mqtt_message_arrived',row['event'])
        self.assertNotIn('SECRET',json.dumps(row))
    def test_unknown_and_other_tags_are_not_copied(self):
        self.assertIsNone(observer.classify('1790269621.235 223 99 I mqttserv: private=SECRET'))
        self.assertIsNone(observer.classify('1790269621.235 223 99 I unrelated: [onMessageArrivedV2] topic: SECRET'))
        self.assertIsNone(observer.classify('payload without a log header SECRET'))
    def test_native_send_completion_retains_command_and_result(self):
        row=observer.classify('1790269621.235 112 99 I [BYDCLOUD]main: send_complete status :1, key_id :511')
        self.assertEqual(('tcp_send_complete',511,1),(row['event'],row['command'],row['success']))
    def test_control_subcommand_is_hex_and_raw_payload_is_discarded(self):
        row=observer.classify('1790274268.059 112 99 E [BYDCLOUD]main: recv 532 cmd is 0x11 PRIVATE_BODY')
        self.assertEqual(('tcp_control_subcommand',17),(row['event'],row['subcommand']))
        self.assertNotIn('PRIVATE',json.dumps(row))
        self.assertIsNone(observer.classify('1790274268.059 112 99 E [BYDCLOUD]main: recv 532 cmd is 0x1234'))
    def test_control_result_does_not_confuse_success_and_authentication_failure(self):
        for native,expected in [('sucess','success'),('fail','failure'),('ikey fail','authentication_failed')]:
            row=observer.classify(f'1790274268.059 112 99 E [BYDCLOUD]main: PRIVATE-->532_cmd:3-> reply reult:{native} !!! PRIVATE_BODY')
            self.assertEqual((3,expected),(row['subcommand'],row['result']))
            self.assertNotIn('PRIVATE',json.dumps(row))
    def test_mcu_reply_and_timeout_are_distinct_from_execution_success(self):
        row=observer.classify('1790274268.059 112 99 E [BYDCLOUD]main: rsp 536 mMcuStatus :1, m536Type :3')
        self.assertEqual(('tcp_mcu_status_reply',1,3),(row['event'],row['mcu_status'],row['request_type']))
        row=observer.classify('1790274268.059 112 99 E [BYDCLOUD]main: 532 timeout clear busy')
        self.assertEqual('tcp_control_timeout',row['event'])
    def test_preview_contacts_no_device(self):
        p=subprocess.run([sys.executable,str(Path(observer.__file__).resolve())],env={'PATH':'/nonexistent'},capture_output=True,text=True,check=True)
        self.assertEqual('preview',json.loads(p.stdout)['mode'])
if __name__=='__main__':unittest.main()
