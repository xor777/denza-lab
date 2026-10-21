import unittest
from decode_battery_pages import Frame,reconstruct,parse_log,analyze
from pathlib import Path
from tempfile import TemporaryDirectory
import json
import shutil
import subprocess
import sys

def record(f, stream="gb"):
 data = f.can_id.to_bytes(4,"big") + bytes([f.sub,f.channel]) + f.counter.to_bytes(4,"big") + f.payload
 return f"[RawCanTurnProbe] BUFFER seq={f.seq} t_ns={f.t_ns} stream={stream} len={len(data)} raw={data.hex()}"

def collector_log(records=None, stream="gb", received=None, dropped=0, errors=0, queued=0, drain_after_unregister=False):
 records = [record(frame(0,[54]))] if records is None else records
 callback = {"gb":"9900001F","bigdata":"99000020","yun":"99000021"}[stream]
 lines = [f"[RawCanTurnProbe] START uid=2000 duration_s=20 queue_capacity=4096 stream={stream}", f"[RawCanTurnProbe] READY callback=0x{callback} t_ns=1"]
 lines += ["[RawCanTurnProbe] UNREGISTERED"] + records if drain_after_unregister else records + ["[RawCanTurnProbe] UNREGISTERED"]
 lines.append(f"[RawCanTurnProbe] DONE received={len(records) if received is None else received} dropped={dropped} callback_errors={errors} queued={queued}")
 return "\n".join(lines) + "\n"

def frame(page,values,kind=0x43c,seq=1,channel=2):
 width=1 if kind==0x43c else 2;slots=6 if width==1 else 3;invalid=(1<<(8*width))-1
 b=bytes([page])+b''.join(int(v).to_bytes(width,'little') for v in values+[invalid]*(slots-len(values)))
 b+=bytes([(255-sum(b))%256]) if width==1 else bytes([0x47])
 return Frame(seq,seq*100_000_000,'gb',kind,0,channel,seq,b)
class PageTests(unittest.TestCase):
 def test_probe_complete_and_padding(self):
  r=reconstruct([frame(0,[50,51,52,53,54,55]),frame(1,[56,57],seq=2)],0x43c,8)
  self.assertEqual(r['cycles'][0]['rawCodes'],list(range(50,58)));self.assertTrue(r['cycles'][0]['structurallyAcceptedArray'])
 def test_u16_little_endian_and_fd_separate(self):
  fs=[frame(0,[0x1234,0x4567,0x89ab],0x43d),frame(1,[0xabcd],0x43d,2),frame(253,[1,2,3],0x43d,3)]
  r=reconstruct(fs,0x43d,4);self.assertEqual(r['cycles'][0]['rawCodes'],[0x1234,0x4567,0x89ab,0xabcd]);self.assertEqual(len(r['specialPages']),1)
 def test_prefix_not_merged_with_later_cycle(self):
  fs=[frame(1,[90],seq=1),frame(0,[1,2,3,4,5,6],seq=2),frame(1,[7],seq=3)]
  self.assertEqual(reconstruct(fs,0x43c,7)['cycles'][0]['rawCodes'],[1,2,3,4,5,6,7])
 def test_missing_page_not_completed(self):
  r=reconstruct([frame(0,[1]*6),frame(2,[1]*6,seq=2)],0x43c,14);self.assertEqual(r['cycles'],[])
 def test_duplicate_page_resets(self):
  r=reconstruct([frame(0,[1]*6),frame(1,[1]*6,seq=2),frame(1,[1]*6,seq=3),frame(2,[1,1],seq=4)],0x43c,14);self.assertEqual(r['cycles'],[])
 def test_wrong_channel_not_decoded(self):
  self.assertEqual(reconstruct([frame(0,[1],channel=1)],0x43c,1)['cycles'],[])
 def test_invalid_marker_becomes_null(self):
  r=reconstruct([frame(0,[50,255])],0x43c,2)['cycles'][0];self.assertFalse(r['structurallyAcceptedArray']);self.assertEqual(r['valuesOrNull'],[50,None])
 def test_nonpadding_rejected_as_valid_array(self):
  r=reconstruct([frame(0,[1,2])],0x43c,1)['cycles'][0];self.assertFalse(r['paddingValid']);self.assertFalse(r['structurallyAcceptedArray'])
 def test_bad_probe_checksum_rejected(self):
  f=frame(0,[1]);f.payload=f.payload[:-1]+bytes([0]);self.assertEqual(reconstruct([f],0x43c,1)['cycles'],[])
 def test_probe_checksum_not_used_for_cells(self):
  f=frame(0,[3300],0x43d);self.assertNotEqual(sum(f.payload)%256,255);self.assertEqual(len(reconstruct([f],0x43d,1)['cycles']),1)
 def test_bad_length_rejected(self):
  f=frame(0,[1]);f.payload=f.payload[:-1];self.assertEqual(reconstruct([f],0x43c,1)['cycles'],[])
 def test_invalid_count(self):
  for n in [0,301]:
   with self.assertRaises(ValueError):reconstruct([],0x43c,n)
class CaptureValidationTests(unittest.TestCase):
 def make(self,text):
  with TemporaryDirectory() as d:
   p=Path(d)/'saved.log';p.write_text(text);return parse_log(p)
 def test_missing_sequence(self):
  text=collector_log([record(frame(0,[54],seq=2))])
  with self.assertRaises(ValueError):self.make(text)
 def test_capture_loss(self):
  with self.assertRaises(ValueError):self.make(collector_log(dropped=1))
 def test_incomplete_capture(self):
  with self.assertRaises(ValueError):self.make(collector_log().replace('[RawCanTurnProbe] UNREGISTERED\n',''))
 def test_unknown_page_does_not_bridge(self):
  fs=[frame(0,[1]*6),frame(10,[2]*6,seq=2),frame(1,[3]*6,seq=3),frame(2,[4,4],seq=4)]
  self.assertEqual(reconstruct(fs,0x43c,14)['cycles'],[])
class StrictCaptureRegressionTests(unittest.TestCase):
 make = CaptureValidationTests.make
 def test_valid_drain_after_unregister_and_early_callback(self):
  f=frame(0,[54]);f.t_ns=0
  parsed=self.make(collector_log([record(f)],drain_after_unregister=True))
  self.assertEqual(len(parsed),1);self.assertEqual(parsed[0].t_ns,0)
 def test_terminal_done_and_newline_required(self):
  for text in (collector_log()[:-1],collector_log()+record(frame(0,[54],seq=2))+"\n"):
   with self.subTest(text=text):
    with self.assertRaises(ValueError):self.make(text)
 def test_duplicate_lifecycle_or_failure_rejected(self):
  text=collector_log()
  for broken in (text+text.splitlines()[-1]+"\n",text.replace("[RawCanTurnProbe] UNREGISTERED","[RawCanTurnProbe] UNREGISTERED\n[RawCanTurnProbe] FAIL synthetic"),text.replace("[RawCanTurnProbe] UNREGISTERED\n", "")):
   with self.assertRaises(ValueError):self.make(broken)
 def test_lifecycle_order_matches_collector(self):
  lines=collector_log().splitlines()
  # Callbacks may precede READY, and queued data may follow UNREGISTERED,
  # but START precedes the subscription and READY precedes teardown.
  for order in ([1,0,2,3,4],[0,2,3,1,4],[2,0,1,3,4]):
   with self.subTest(order=order):
    with self.assertRaises(ValueError):self.make("\n".join(lines[i] for i in order)+"\n")
 def test_stream_callback_and_record_type_consistency(self):
  text=collector_log()
  for broken in (text.replace("callback=0x9900001F","callback=0x99000021"),text.replace(" stream=gb len="," stream=yun len="),text.replace("START uid=2000 duration_s=20 queue_capacity=4096 stream=gb","START uid=2000 duration_s=20 queue_capacity=4096 stream=bigdata").replace("callback=0x9900001F","callback=0x99000020")):
   with self.assertRaises(ValueError):self.make(broken)
 def test_device_time_backstep_rejected_counter_backstep_preserved(self):
  first=frame(0,[54]);second=frame(0,[55],seq=2)
  first.counter=100;second.counter=99
  parsed=self.make(collector_log([record(first),record(second)]))
  self.assertEqual([f.counter for f in parsed],[100,99])
  second.t_ns=first.t_ns-1
  with self.assertRaises(ValueError):self.make(collector_log([record(first),record(second)]))
 def test_frame_counter_u32_and_domain_bounds(self):
  def text(cid="43C",sub="00",ch=2,counter=1,length=8,payload="00010203040506ea"):
   r=f"[RawCanTurnProbe] FRAME seq=1 t_ns=2 id=0x{cid} sub=0x{sub} ch={ch} cnt={counter} len={length} payload={payload}"
   return collector_log([r],stream="bigdata")
  for broken in (text(counter=4294967296),text(cid="800"),text(sub="100"),text(ch=3),text(length=9,payload="00"*9),text(payload="0")):
   with self.subTest(text=broken):
    with self.assertRaises(ValueError):self.make(broken)
 def test_frame_zero_length_is_legal_and_not_battery_data(self):
  r="[RawCanTurnProbe] FRAME seq=1 t_ns=2 id=0x55 sub=0x00 ch=0 cnt=1 len=0 payload="
  self.assertEqual(self.make(collector_log([r],stream="bigdata"))[0].payload,b"")
 def test_array_fd_only_43d_and_missing_summary_null(self):
  for cid,expected in ((0x43c,"unknown_out_of_range_page"),(0x43d,"FD_policy_metadata")):
   special=reconstruct([frame(253,[1,2,3],kind=cid)],cid,14)["specialPages"][0]
   self.assertEqual(special["classification"],expected)
  c=reconstruct([frame(0,[255])],0x43c,1)["cycles"][0]
  self.assertIsNone(c["meanRaw"]);self.assertIsNone(c["minRaw"]);self.assertIsNone(c["maxRaw"])
 def test_non_gb_stream_never_reconstructed_and_pages_keep_timestamps(self):
  f=frame(0,[54]);f.stream="yun"
  self.assertEqual(reconstruct([f],0x43c,1)["cycles"],[])
  f.stream="gb";c=reconstruct([f],0x43c,1)["cycles"][0]
  self.assertEqual(c["pageRecords"],[dict(page=0,seq=f.seq,tNs=f.t_ns,embeddedCounter=f.counter)])
  self.assertFalse(c["atomicSnapshot"]);self.assertIsNone(c["engineeringUnit"])
 def test_standalone_cli_from_temporary_directory(self):
  # Copy only the decoder. Successful execution from another cwd proves that
  # explicit-input CLI mode has no sibling/capture/repository runtime dependency.
  source=Path(__file__).with_name("decode_battery_pages.py")
  with TemporaryDirectory(prefix="battery-standalone-") as d:
   folder=Path(d);script=folder/"standalone.py";shutil.copyfile(source,script)
   meta=Frame(1,100_000_000,"gb",0x43a,0,2,1,bytes.fromhex("01013412000000b7"))
   probe=frame(0,[54],seq=2);cell=frame(0,[3300],kind=0x43d,seq=3)
   saved=folder/"saved.log";saved.write_text(collector_log([record(meta),record(probe),record(cell)]))
   result=subprocess.run([sys.executable,str(script),str(saved)],cwd=folder,capture_output=True,text=True,check=True)
   parsed=json.loads(result.stdout)["inputs"][0]
   self.assertEqual(parsed["metadata43A"]["cellCount"],1)
   self.assertEqual(parsed["arrays"]["0x43d"]["cycles"][0]["rawCodes"],[3300])
   self.assertIsNone(parsed["arrays"]["0x43c"]["cycles"][0]["engineeringUnit"])
   bad=subprocess.run([sys.executable,str(script)],cwd=folder,capture_output=True,text=True)
   self.assertNotEqual(bad.returncode,0)
if __name__=='__main__':unittest.main()

