"""ADAS output localization: presence, original protobuf oracle, sessions and freshness.

Synthetic packets are explicitly test data. No recorded live VehiclePositionInfo
fixture was available when this test was added.
"""
import base64
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

from tools.adas_bridge import drive_review as review, feed, stock
from tools.adas_bridge.tests.test_drive_review import Car, NAVIGATOR, OFFSET, run, _field, _varint, _wrap

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'captures/hud-firmware-20260923/jadx/BydLaunchermap/sources/someip/vehicle/position/info/service/SomeipVehiclePositionInfoService.java'


def double(number, value):
    return _varint(number << 3 | 1) + struct.pack('<d', value)


def packet(t, lon=37.06228, lat=55.518573333333336, extra=b'', stamp=None):
    return _wrap(double(3, lon) + double(4, lat) + double(14, (OFFSET + t) * 1000 if stamp is None else stamp) + extra)


def guidance(car, t, source=NAVIGATOR, fresh=True):
    car.guidance(t, source=source)
    car.progress[-1].update(coordinateFrame='wgs84', freshLocation=fresh,
                           location={'longitude': 37.06228, 'latitude': 55.518573333333336}, lastFineFixAgeMs=20)


class VehiclePositionDecode(unittest.TestCase):
    def test_exact_topic_presence_zero_unsigned_and_repeated_packed_unpacked(self):
        self.assertEqual(review.VEHICLE_POSITION, 1125942857203713)
        self.assertEqual(set(feed.SCHEMA['VehiclePositionInfoNotifyStruct']), {str(i) for i in range(1, 38)})
        self.assertEqual(feed.flatten('VehiclePositionInfoNotify', _wrap(b'')), {})
        payload = _wrap(double(3, 0) + double(4, 0) + _field(25, 0) + _field(15, 0xffffffff)
                        + _varint(35 << 3 | 2) + b'\x06\x00\xff\xff\xff\xff\x0f'
                        + _field(35, 7) + _field(36, 0))
        fields = review.decode_passive(review.VEHICLE_POSITION, payload)
        self.assertEqual(fields, {'longitude': 0.0, 'latitude': 0.0, 'hdStatus': 0, 'hdLinkId': 0xffffffff,
                                  'targetLaneIdArray': [0, 0xffffffff, 7], 'targetLaneIdSegmentArray': [0]})
        self.assertNotIn('fusionStatus', fields)

    def test_malformed_payloads_fail_closed(self):
        for payload in [b'\x0a\x20\x00', _wrap(b'\x19\x00'), _wrap(b'\x18\x01'),
                        _wrap(b'\x00'), _wrap(b'\x9a\x02\x01\x80')]:
            with self.subTest(hex=payload.hex()):
                self.assertIsNone(review.decode_passive(review.VEHICLE_POSITION, payload))

    def test_nonfinite_and_zero_pair_are_retained_but_not_paired(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.passive(1000, review.VEHICLE_POSITION, packet(1000, extra=double(9, float('nan')) + _field(25, 0)))
            guidance(car, 1000)
            car.passive(2000, review.VEHICLE_POSITION, packet(2000, lon=0, lat=0))
            guidance(car, 2000)
            report = run(car.write())['adasVehiclePosition']
        self.assertEqual(report['sampleCount'], 2)
        self.assertEqual(report['pairedGuidanceSamples'], 0)
        first = report['samples'][0]
        self.assertIsNone(first['fields']['vehicleSpeed'])
        self.assertIn('vehicleSpeed', first['presentFields'])
        self.assertEqual(first['nonFiniteFields'], {'vehicleSpeed': 'nan'})
        self.assertEqual(first['fields']['hdStatus'], 0)
        self.assertNotIn('fusionStatus', first['presentFields'])
        self.assertEqual(report['samples'][1]['fields']['longitude'], 0)
        json.dumps(report, allow_nan=False)


class VehiclePositionJoins(unittest.TestCase):
    def test_same_process_fresh_candidate_and_optional_outbound_frame(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            lon, lat = stock.to_sdk(37.06228, 55.518573333333336, 'native-gcj-unrestricted-candidate')
            car.passive(1000, review.VEHICLE_POSITION, packet(1000, lon, lat, extra=double(6, -20) + _field(25, 1)))
            guidance(car, 1050)
            car.send(1020, review.VEHICLE_LOCATION, stock.vehicle_location(lon, lat, lon, lat, 0, 0, 0, 99, 0, 0, 0, 0, -1))
            car.live[-1].update(sourceSessionId=NAVIGATOR, effectiveFrame='STOCK_GCJ')
            report = run(car.write())['adasVehiclePosition']
        (sample,) = report['samples']
        pair = sample['guidanceComparison']
        self.assertTrue(pair['paired'])
        self.assertEqual(pair['deltaMs'], 50)
        self.assertEqual(pair['nativeCandidateDistanceM'], 0)
        self.assertGreater(pair['wgs84DistanceM'], 500)
        self.assertEqual(sample['stockConsumerCourse'], 20)
        self.assertEqual(sample['validity']['fix'], 'not established')
        self.assertEqual(sample['outboundPositionComparison']['effectiveFrame'], 'STOCK_GCJ')
        self.assertEqual(sample['outboundPositionComparison']['rawFieldDistanceM'], 0)
        self.assertEqual(report['guidanceDistances']['nativeCandidateDistanceM']['median'], 0)
        self.assertIn('not a raw GNSS', report['rawAndroidGnss'])

    def test_stale_different_process_nonfresh_and_missing_timestamp_not_paired(self):
        cases = [('stale guidance', 2501, NAVIGATOR, True, None),
                 ('different process', 1000, 'different-process', True, None),
                 ('nonfresh guidance', 1000, NAVIGATOR, False, None),
                 ('stale ADAS time', 1000, NAVIGATOR, True, (OFFSET - 4000) * 1000),
                 ('zero ADAS time', 1000, NAVIGATOR, True, 0)]
        for label, gt, process, fresh, stamp in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as tmp:
                car = Car(tmp)
                car.passive(1000, review.VEHICLE_POSITION, packet(1000, stamp=stamp))
                guidance(car, gt, process, fresh)
                report = run(car.write())['adasVehiclePosition']
                self.assertEqual(report['pairedGuidanceSamples'], 0)
                self.assertFalse(report['samples'][0]['guidanceComparison']['paired'])

    def test_missing_timestamp_or_process_is_not_inferred_and_old_fix_is_not_fresh(self):
        for missing in ['timestamp', 'process', 'fresh-fix']:
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as tmp:
                car = Car(tmp)
                payload = _wrap(double(3, 37.06228) + double(4, 55.518573333333336)) if missing == 'timestamp' else packet(1000)
                car.passive(1000, review.VEHICLE_POSITION, payload)
                if missing == 'process':car.packets[-1].pop('sourceSessionId')
                guidance(car, 1000)
                if missing == 'fresh-fix':car.progress[-1]['lastFineFixAgeMs'] = 5000
                report = run(car.write())['adasVehiclePosition']
                self.assertEqual(report['pairedGuidanceSamples'], 0)

    def test_outbound_without_same_process_or_frame_not_paired(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            car.passive(1000, review.VEHICLE_POSITION, packet(1000))
            guidance(car, 1000)
            car.tick(1000, 99)
            report = run(car.write())['adasVehiclePosition']
        self.assertTrue(report['samples'][0]['guidanceComparison']['paired'])
        self.assertNotIn('outboundPositionComparison', report['samples'][0])

    def test_repeat_receipts_presence_source_session_and_receiver_deduplication(self):
        with tempfile.TemporaryDirectory() as tmp:
            car = Car(tmp)
            payload = packet(1000, extra=_field(25, 0) + _field(35, 0) + _field(35, 4))
            car.passive(1000, review.VEHICLE_POSITION, payload)
            car.passive(1100, review.VEHICLE_POSITION, payload)
            car.passive(1101, review.VEHICLE_POSITION, payload, receiver='other')
            car.passive(4000, review.VEHICLE_POSITION, payload)
            for t in [1000, 1100, 4000]:
                guidance(car, t)
            report = run(car.write())['adasVehiclePosition']
        self.assertEqual(report['decodedReceipts'], 4)
        self.assertEqual(report['sampleCount'], 3)
        self.assertEqual(report['duplicateReceiverReceiptsExcluded'], 1)
        first, repeated, stale = report['samples']
        self.assertTrue(repeated['repeat'])
        self.assertEqual(repeated['t'], 1100)
        self.assertEqual(repeated['sourceSessionId'], NAVIGATOR)
        self.assertEqual(first['presentFields'], repeated['presentFields'])
        self.assertEqual(repeated['fields']['targetLaneIdArray'], [0, 4])
        self.assertEqual(repeated['payloadRef'], first['ref'])
        self.assertTrue(repeated['guidanceComparison']['paired'])
        self.assertFalse(stale['guidanceComparison']['paired'])

    def test_repeat_reset_on_new_process_other_topic_bad_payload_and_time_reset(self):
        def row(kind, t, seq, **extra):
            return dict(kind=kind, receiver='overlay', topic=format(review.VEHICLE_POSITION, 'x'),
                        _t=t, _seq=seq, _ref=f'p:{seq}', timestamp=seq, **extra)
        full = row('adas_passive_packet', 1000, 0, sourceSessionId='a', receiverSession='one',
                   base64=base64.b64encode(packet(1000)).decode(), capturedAtMs=OFFSET + 1000)
        change = row('adas_passive_packet', 1100, 1, sourceSessionId='b', receiverSession='two', base64='')
        change['topic'] = format(review.PILOT, 'x')
        repeat = row('adas_passive_repeat', 1200, 2)
        obs, errors = review._position_packets([full, change, repeat])
        self.assertEqual(len(obs), 1)
        self.assertEqual(len(errors), 1)
        change['sourceSessionId'] = 'a'  # Receiver restart alone also resets its compact payload cache.
        obs, errors = review._position_packets([full, change, repeat])
        self.assertEqual(len(obs), 1)
        self.assertEqual(len(errors), 1)
        reset = row('adas_passive_repeat', 100, 3)
        obs, errors = review._position_packets([full, reset])
        self.assertEqual(len(obs), 1)
        self.assertEqual(len(errors), 1)
        bad = row('adas_passive_packet', 1100, 1, sourceSessionId='a', receiverSession='one', base64='%%%')
        obs, errors = review._position_packets([full, bad, repeat])
        self.assertEqual(len(obs), 1)
        self.assertEqual(len(errors), 2)


class OriginalGeneratedProtobufOracle(unittest.TestCase):
    @unittest.skipUnless(SOURCE.is_file(), 'original generated stock Java not available')
    def test_synthetic_original_generated_serializer(self):
        """Original message/descriptor with only JADX compilation repairs in a temporary copy."""
        home = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
        javac = home / 'bin/javac'
        if not javac.exists():
            found = shutil.which('javac')
            if not found:self.skipTest('local JDK absent')
            javac = Path(found)
        jars = sorted((Path.home() / '.gradle/caches/modules-2/files-2.1/com.google.protobuf/protobuf-java').glob('*/*/protobuf-java-*.jar'))
        if not jars:self.skipTest('local cached protobuf runtime absent; no downloads')
        text = SOURCE.read_text()
        for name in ['VehiclePositionInfoNotify', 'VehiclePositionInfoNotifyStruct']:
            text = text.replace(f'extends GeneratedMessageLite<{name}, Builder>', f'extends GeneratedMessageLite<{name}, {name}.Builder>')
        # JADX emits a break from the synchronized block's enclosing switch,
        # leaving case5 without its parser return; remove only those two breaks.
        pattern = '                            break;\n                        }\n                    }\n                    return defaultInstanceBasedParser;'
        self.assertEqual(text.count(pattern), 2)
        text = text.replace(pattern, pattern.replace('                            break;\n', ''))
        helper = r'''
import someip.vehicle.position.info.service.SomeipVehiclePositionInfoService.*;
public class VehiclePositionOracle {
 public static void main(String[] args) {
  VehiclePositionInfoNotifyStruct s=VehiclePositionInfoNotifyStruct.newBuilder()
   .setChecksum(-1).setCounter(123).setLongitude(37.06228).setLatitude(55.518573333333336)
   .setHeading(-20.25).setVehicleSpeed(7.5).setTimestamp(1792759497318000d)
   .setHdLinkId(-1).setHdStatus(1).setFusionStatus(3).setPositionType(4)
   .setTargetCruiseSpeed(23.5f).addTargetLaneIdArray(0).addTargetLaneIdArray(-1)
   .addTargetLaneIdSegmentArray(9).setLocalizationOutputOffset(-1).build();
  byte[] b=VehiclePositionInfoNotify.newBuilder().setVehiclePositionInfoNotifyStruct(s).build().toByteArray();
  for(byte v:b)System.out.printf("%02x",v&255);
 }
}'''
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp);source = tmp / SOURCE.name;source.write_text(text)
            oracle = tmp / 'VehiclePositionOracle.java';oracle.write_text(helper)
            subprocess.run([str(javac), '--release', '8', '-cp', str(jars[-1]), '-d', str(tmp), str(source), str(oracle)],
                           check=True, capture_output=True, timeout=30)
            result = subprocess.run([str(javac.with_name('java')), '-cp', str(tmp) + os.pathsep + str(jars[-1]),
                                     'VehiclePositionOracle'], check=True, capture_output=True, text=True, timeout=30)
        fields = review.decode_passive(review.VEHICLE_POSITION, bytes.fromhex(result.stdout))
        self.assertEqual(fields, {'checksum': 0xffffffff, 'counter': 123, 'longitude': 37.06228,
                                 'latitude': 55.518573333333336, 'heading': -20.25, 'vehicleSpeed': 7.5,
                                 'timestamp': 1792759497318000.0, 'hdLinkId': 0xffffffff, 'hdStatus': 1,
                                 'fusionStatus': 3, 'positionType': 4, 'targetCruiseSpeed': 23.5,
                                 'targetLaneIdArray': [0, 0xffffffff], 'targetLaneIdSegmentArray': [9],
                                 'localizationOutputOffset': 0xffffffff})


if __name__ == '__main__':
    unittest.main()
