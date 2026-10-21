"""Synthetic streams only. No device, socket server, factory or credentials."""
import json
from pathlib import PurePosixPath
import struct
import unittest
from unittest.mock import patch
from xml.sax.saxutils import escape

import read_diagnostic_label_cache as reader
from test_read_ecu_cache_export import FragmentedSocket, download, listing


def xml(**entries):
    return ('<map>' + ''.join('<string name="' + k + '">' + escape(json.dumps(v)) + '</string>'
                             for k, v in entries.items()) + '</map>').encode()


def samples():
    return {
        reader.SPOT_FILE: xml(
            RUNNING_TASK={'header': 'PRIVATE', 'taskId': 'PRIVATE', 'downloadCommand': {
                'token': 'PRIVATE', 'scriptList': [{'scriptCode': 'SYNTHETIC', 'scriptVersion': '1',
                 'scriptFormatVer': '2', 'forStation': '1', 'downloadBaseUrl': 'PRIVATE'}]}},
            afterRunRes={'commandStatus': 'FAIL', 'finish': 1, 'total': 1, 'showTime': 0,
                         'runCommandData': 'PRIVATE', 'systemResultList': [
                             {'name': 'SYNTHETIC', 'itemDesc': 'fixture description', 'vin': 'PRIVATE'}]},
            unrelated='PRIVATE'),
        reader.OTA_FILE: xml(otaUpdateDownloadFileInfo={
            'ecuId': 'SYNTHETIC', 'ecuDescription': 'fixture component', 'ecuSoftCode': '123',
            'isSourcePackage': False, 'fileName': 'PRIVATE', 'fileDir': 'PRIVATE',
            'token': 'PRIVATE', 'fileUrl': 'PRIVATE', 'encryptMd5': 'PRIVATE'}),
    }


class LabelCacheTests(unittest.TestCase):
    def test_field_projection_drops_private_and_retains_qualification(self):
        result = reader.project_config(samples())
        text = json.dumps(result)
        self.assertNotIn('PRIVATE', text)
        self.assertNotIn('fileName', text)
        self.assertEqual(result['qualifiedDtcDefinitions'], 0)
        self.assertEqual(result['qualifiedChildRoles'], 0)
        spot, ota = result['files']
        self.assertEqual(spot['selected']['afterRunRes']['value']['systemResultList'],
                         [{'name': 'SYNTHETIC', 'itemDesc': 'fixture description'}])
        self.assertIn('historical_display_subset', spot['selected']['afterRunRes']['interpretation'])
        self.assertIn('applicability_unproved', ota['selected']['otaUpdateDownloadFileInfo']['interpretation'])

    def test_four_requests_exact_bytes_and_no_retries(self):
        bodies = samples()
        replies = []
        for path in reader.ALLOWED_FILES:
            name = PurePosixPath(path).name
            replies.extend([listing((name, len(bodies[path]))), download(bodies[path], name=name)])
        sockets = [FragmentedSocket(raw, fragment=13) for raw in replies]
        with patch.object(reader.socket, 'create_connection', side_effect=sockets) as connect:
            raw = reader.read_config('localhost', 42424)
        self.assertEqual(raw, bodies)
        self.assertEqual(connect.call_count, 4)
        for i, sock in enumerate(sockets):
            self.assertTrue(sock.closed)
            magic, action, size = struct.unpack('>III', sock.sent[:12])
            self.assertEqual((magic, action, size), (reader.wire.MAGIC, 2 if i % 2 == 0 else 1, len(sock.sent)-12))
            path = reader.ALLOWED_FILES[i//2]
            if action == 2:
                path = str(PurePosixPath(path).parent)
            self.assertEqual(next(iter(json.loads(sock.sent[12:]).values())), reader.wire.EXPORT_PREFIX + path.lstrip('/'))

    def test_missing_files_do_not_claim_absence(self):
        sockets = [FragmentedSocket(listing()) for _ in range(2)]
        with patch.object(reader.socket, 'create_connection', side_effect=sockets) as connect:
            result = reader.project_config(reader.read_config('127.0.0.1', 42424))
        self.assertEqual(connect.call_count, 2)
        for row in result['files']:
            self.assertEqual(row['status'], 'not_returned')
            self.assertIsNone(row['exists'])

    def test_missing_empty_null_keys_are_distinct(self):
        for val, status in [(b'<map/>', 'key_missing'),
                            (xml(afterRunRes=None), 'key_no_data'),
                            (b'<map><string name="afterRunRes"> </string></map>', 'key_no_data')]:
            result = reader.project_config({reader.SPOT_FILE: val, reader.OTA_FILE: b'<map/>'})
            self.assertEqual(result['files'][0]['selected']['afterRunRes']['status'], status)

    def test_paths_actions_and_remote_endpoints_refused_before_connect(self):
        with patch.object(reader.socket, 'create_connection') as connect:
            for action, path in [(3, reader.SPOT_FILE), (True, reader.SPOT_FILE), (1, '/etc/passwd'),
                                 (2, '/'), (1, '/data/user/0/com.byd.auto_emanual/databases/classify_cache.db')]:
                with self.subTest(action=action, path=path), self.assertRaises(reader.ExportError):
                    reader._exchange(('127.0.0.1', 42424), action, path, reader.wire.Deadline())
            for host, port in [('example.com', 42424), ('127.0.0.1', True), ('127.0.0.1', 65536)]:
                with self.subTest(host=host, port=port), self.assertRaises(reader.ExportError):
                    reader.read_config(host, port)
            connect.assert_not_called()

    def test_no_retry_on_bad_download_or_oversize_listing(self):
        for replies in [[listing(('com.byd.spotinspection.xml', reader.MAX_FILE + 1))],
                        [listing(('com.byd.spotinspection.xml', 1)), b'bad']]:
            sockets = [FragmentedSocket(x) for x in replies]
            with patch.object(reader.socket, 'create_connection', side_effect=sockets) as connect:
                with self.assertRaises(reader.ExportError):
                    reader.read_config('127.0.0.1', 42424)
            self.assertEqual(connect.call_count, len(replies))

    def test_schema_duplicate_keys_entities_nonfinite_and_types_reject(self):
        bad = [b'<!DOCTYPE map [<!ENTITY x "PRIVATE">]><map/>',
               b'<map><string name="afterRunRes">{}</string><string name="afterRunRes">{}</string></map>',
               b'<map><string name="afterRunRes">{"total":1,"total":2}</string></map>',
               b'<map><string name="afterRunRes">{"total":1e999}</string></map>',
               xml(afterRunRes={'finish': True}), xml(afterRunRes={'showTime': -1}),
               xml(afterRunRes={'systemResultList': [{'name': 'X'}]}),
               xml(afterRunRes={'systemResultList': [{'name': 'X', 'itemDesc': ['PRIVATE']}]}),
               xml(afterRunRes={'systemResultList': [{}] * 513}),
               xml(afterRunRes={'commandStatus': 'X\nPRIVATE'}),
               xml(RUNNING_TASK={'downloadCommand': {'scriptList': [{'scriptCode': None}]}})]
        for raw in bad:
            with self.subTest(raw=raw[:30]), self.assertRaises(reader.ExportError) as caught:
                reader.project_config({reader.SPOT_FILE: raw, reader.OTA_FILE: b'<map/>'})
            self.assertNotIn('PRIVATE', str(caught.exception))
        for obj in [{'ecuId': 'X', 'isSourcePackage': 1}, {'ecuId': 1},
                    {'ecuId': 'X', 'ecuDescription': 'x' * 1025}]:
            with self.assertRaises(reader.ExportError):
                reader.project_config({reader.SPOT_FILE: b'<map/>', reader.OTA_FILE: xml(otaUpdateDownloadFileInfo=obj)})

    def test_incomplete_source_set_rejected(self):
        for value in [{}, {reader.SPOT_FILE: b'<map/>'}, dict(samples(), other=b'PRIVATE')]:
            with self.assertRaises(reader.ExportError):
                reader.project_config(value)


if __name__ == '__main__':
    unittest.main()
