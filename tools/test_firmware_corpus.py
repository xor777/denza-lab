#!/usr/bin/env python3

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import firmware_corpus as corpus


def write(path: Path, data: bytes = b'x') -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return path


def record(path: str, data: bytes, **extra) -> dict:
    return {'path': path, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest(), **extra}


class FirmwareCorpusTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        ivi = self.root / 'captures' / 'hud-firmware-20260923'
        apk = b'dishare-apk'
        # The IVI system partition is system-as-root: /system/x lands at system/system/x.
        write(ivi / 'system' / 'system' / 'app' / 'DiShare' / 'DiShare.apk', apk)
        (ivi / 'extraction.json').write_text(json.dumps([record('/system/app/DiShare/DiShare.apk', apk)]))
        write(ivi / 'jadx' / 'DiShare' / 'resources' / 'AndroidManifest.xml',
              b'<manifest package="com.byd.dishare" android:versionName="1b1f648" '
              b'android:versionCode="12">')
        write(ivi / 'files-system.txt', b'D /system/app/DiShare\nF /system/app/DiShare/DiShare.apk 11\n')

        fse = self.root / 'captures' / 'fse-firmware-20260924'
        hud = b'bydhud'
        write(fse / 'system' / 'system' / 'app' / 'BydHud' / 'BydHud.apk', hud)
        (fse / 'extraction.json').write_text(json.dumps([
            record('/system/app/BydHud/BydHud.apk', hud, partition='system'),
            record('/system/etc/gone.rc', b'gone', partition='system',
                   archive='Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip'),
        ]))

        reverse = self.root / 'reverse'
        write(reverse / 'dishare-jadx' / 'sources' / 'com' / 'byd' / 'dishare' / 'BuildConfig.java',
              b'public final class BuildConfig {\n'
              b'    public static final String APPLICATION_ID = "com.byd.dishare";\n'
              b'    public static final int VERSION_CODE = 9;\n'
              b'    public static final String VERSION_NAME = "23102ef";\n}\n')
        self.pulled = write(reverse / 'apks' / 'DiCarServer.apk', b'dicar')
        write(reverse / 'speaker-lift' / 'DiShare.apk', apk)
        write(self.root / 'captures' / 'releases' / 'denza-apps.apk', b'ours')
        evidence = self.root / 'captures' / 'telematics-20260924' / 'paths'
        write(evidence / 'sources' / 'snapshot.py', b'print()')
        ours = self.root / 'captures' / 'telematics-20260924' / 'adapter'
        write(ours / 'sources' / 'dev' / 'denza' / 'tools' / 'Adapter.java',
              b'package dev.denza.tools;\n\nclass Adapter {}')
        early = self.root / 'captures' / 'telematics-20260922' / 'telephony-src'
        write(early / 'sources' / 'com' / 'byd' / 'Tracker.java', b'package com.byd;\n\nclass Tracker {}')

        self.rows = corpus.index(self.root)

    def rows_named(self, name):
        return [r for r in self.rows if r.name == name]

    def test_an_extracted_file_is_found_where_the_reader_put_it(self):
        dishare = [r for r in self.rows_named('DiShare.apk') if r.kind == 'file'][0]
        self.assertEqual(corpus.IVI_BUILD, dishare.build)
        self.assertTrue(dishare.local_path.endswith('system/system/app/DiShare/DiShare.apk'))
        self.assertEqual(hashlib.sha256(b'dishare-apk').hexdigest(), dishare.sha256)

    def test_the_build_comes_from_the_record_or_else_the_directory(self):
        self.assertEqual(corpus.FSE_BUILD, self.rows_named('BydHud.apk')[0].build)
        gone = self.rows_named('gone.rc')[0]
        self.assertEqual(corpus.IVI_BUILD, gone.build)
        self.assertEqual('', gone.local_path)

    def test_a_tree_says_its_version_and_whether_resources_were_decoded(self):
        ota = [r for r in self.rows if r.kind == 'jadx' and r.build == corpus.IVI_BUILD][0]
        self.assertEqual(('com.byd.dishare', '1b1f648 (12)', 'y'), (ota.package, ota.version, ota.resources))
        self.assertEqual(hashlib.sha256(b'dishare-apk').hexdigest(), ota.sha256)
        pulled = [r for r in self.rows if r.kind == 'jadx' and r.build == corpus.PULLED][0]
        self.assertEqual(('com.byd.dishare', '23102ef (9)', 'n'), (pulled.package, pulled.version, pulled.resources))

    def test_loose_binaries_are_hashed_and_our_own_builds_are_not_firmware(self):
        dicar = self.rows_named('DiCarServer.apk')[0]
        self.assertEqual(('binary', corpus.PULLED), (dicar.kind, dicar.build))
        self.assertEqual(hashlib.sha256(b'dicar').hexdigest(), dicar.sha256)
        self.assertEqual([], self.rows_named('denza-apps.apk'))

    def test_bytes_identical_to_an_ota_file_are_that_build(self):
        copy = [r for r in self.rows_named('DiShare.apk') if 'speaker-lift' in r.local_path][0]
        self.assertEqual(('binary', corpus.IVI_BUILD), (copy.kind, copy.build))

    def test_only_java_makes_a_tree_and_a_capture_before_the_ota_was_pulled(self):
        self.assertEqual([], self.rows_named('paths'))
        self.assertEqual([], self.rows_named('adapter'))
        early = self.rows_named('telephony-src')[0]
        self.assertEqual(('jadx', corpus.PULLED), (early.kind, early.build))

    def test_find_reports_every_copy_tree_and_listing_line(self):
        hits, listed = corpus.find(self.root, ['dishare'])
        self.assertEqual({'file', 'jadx', 'binary'}, {r.kind for r in hits})
        self.assertEqual(4, len(hits))
        self.assertEqual(2, len(listed))
        hits, listed = corpus.find(self.root, ['nothing-like-this'])
        self.assertEqual(([], []), (hits, listed))

    def test_the_index_survives_a_reload(self):
        self.assertEqual(len(self.rows), len(corpus.load(self.root)))


if __name__ == '__main__':
    unittest.main()
