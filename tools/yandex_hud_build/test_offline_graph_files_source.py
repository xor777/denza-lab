"""Source/corpus checks only: never compiles Java, invokes JNI, builds APKs or uses ADB.

The independent Python reader corroborates the two native package identity layouts.
It does not claim execution coverage of the Java state machine or Android filesystem APIs.
"""
from pathlib import Path
import hashlib
import json
import mmap
import os
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe'
CORPUS = ROOT / 'captures/yandex-offline-region-20261024/region-43'
LIB = ROOT / 'captures/firmware/yandexnavi-29.8.1-3b0dec32/jadx/Navigator/resources/lib/arm64-v8a/libmaps-mobile.so'


def identity(data, vector=False):
    """Independent bounded FlatBuffer root identity, excluding all graph/tile payloads."""
    def value(at, fmt):
        if at < 0 or at + struct.calcsize(fmt) > len(data):
            raise ValueError('bounds')
        return struct.unpack_from(fmt, data, at)[0]
    root = value(0, '<I')
    vtable = root - value(root, '<i')
    length, size = value(vtable, '<H'), value(vtable + 2, '<H')
    if root < 4 or vtable < 4 or not 20 <= length <= 256 or not 4 <= size <= 256:
        raise ValueError('root')
    def field(index):
        if 4 + index * 2 + 2 > length:
            raise ValueError('missing field')
        off = value(vtable + 4 + index * 2, '<H')
        if not 4 <= off <= size - 4:
            raise ValueError('object')
        at = root + off
        delta = value(at, '<I')
        if delta < 4:
            raise ValueError('offset')
        return at + delta
    def text(at):
        count = value(at, '<I')
        if not 1 <= count <= 128 or at + count + 5 > len(data):
            raise ValueError('text bounds')
        raw = bytes(data[at + 4:at + 4 + count])
        if value(at + 4 + count, '<B') != 0 or not all(33 <= c <= 126 for c in raw):
            raise ValueError('text')
        return raw.decode('ascii')
    if vector:
        if text(field(2)) != 'vmap3':
            raise ValueError('format')
        return text(field(3)), []
    at = field(7)
    count = value(at, '<I')
    if not 1 <= count <= 1024:
        raise ValueError('region budget')
    ids = [value(at + 4 + 4 * i, '<I') for i in range(count)]
    if len(set(ids)) != len(ids) or any(i > 0x7fffffff for i in ids):
        raise ValueError('region identity')
    return text(field(6)), ids


def file_identity(path):
    s = os.lstat(path)
    return s.st_dev, s.st_ino, s.st_size, int(s.st_mtime), int(s.st_ctime)


class SourceChecks(unittest.TestCase):
    def test_typed_callback_source_contract(self):
        source = (JAVA / 'YandexOfflineCachePath.java').read_text()
        self.assertIn('implements OfflineCacheManager.PathGetterListener', source)
        self.assertIn('sdk != OfflineCacheManager.PathGetterListener.class', source)
        self.assertIn('requestTicket != ticket || pending == null', source)
        self.assertIn('now - lastRequestAt >= TIMEOUT_MS', source)
        self.assertIn('main.removeCallbacks(timeout)', source)
        self.assertIn('main.post(() -> finish(requestTicket, path, null))', source)
        self.assertNotIn('java.lang.reflect.Proxy', source)
        self.assertNotIn('import java.io.', source)
        self.assertNotIn('setCachePath', source)
        stub = ROOT / 'tools/yandex_hud_build/compile_only/com/yandex/mapkit/offline_cache/OfflineCacheManager.java'
        self.assertIn('void onPathReceived(String path);', stub.read_text())

    def test_discovery_source_contract(self):
        source = (JAVA / 'YandexOfflineGraphFiles.java').read_text()
        self.assertIn('Boolean.FALSE.equals(row.get("isLegacyPath"))', source)
        self.assertIn('new File(root, "offline_caches")', source)
        self.assertIn('Integer.toString(id)', source)
        self.assertNotIn('listFiles(', source)
        self.assertNotIn('/storage/', source)
        self.assertNotIn('Kazan', source)
        self.assertIn('new Identity(Os.fstat(input.getFD()))', source)
        self.assertIn('FileChannel.MapMode.READ_ONLY', source)
        self.assertIn('MUST finish all access inside this call', source)
        self.assertIn('MAX_VECTOR_FILE_BYTES = 512 * 1024 * 1024', source)
        self.assertIn('return new Header(version, new int[0])', source)
        self.assertIn('source.metadataSha.equals(hashFile(', source)

    @unittest.skipUnless((CORPUS / 'driving.fb').is_file(), 'local firmware corpus absent')
    def test_actual_driving_header_and_malformed_offset(self):
        data = (CORPUS / 'driving.fb').read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), '58747160de74c00d508231a49f20b8bb94144e267fe926653d5fbe853cc5a2b4')
        self.assertEqual(identity(data), ('26.10.06-1', [43]))
        broken = bytearray(data[:128]); struct.pack_into('<I', broken, 0, 0xffffffff)
        with self.assertRaises(ValueError): identity(broken)
        with self.assertRaises(ValueError): identity(data[:64])

    @unittest.skipUnless((CORPUS / 'vmap3fb.fb').is_file(), 'local firmware corpus absent')
    def test_actual_vector_scoped_mmap_identity_has_no_embedded_region(self):
        with (CORPUS / 'vmap3fb.fb').open('rb') as f:
            with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as data:
                version, ids = identity(data, True)
                self.assertTrue(version)
                self.assertEqual(ids, [])
                self.assertEqual(len(data), 66793664)

    def test_atomic_replacement_rejects_equal_size_equal_mtime(self):
        with tempfile.TemporaryDirectory() as taskdir:
            path = Path(taskdir) / 'driving.fb'
            path.write_bytes(b'old bytes')
            before = file_identity(path)
            replacement = Path(taskdir) / 'driving.fb.unused'
            replacement.write_bytes(b'new bytes')
            os.utime(replacement, (before[3], before[3]))
            os.replace(replacement, path)
            after = file_identity(path)
            self.assertEqual(before[2:4], after[2:4])
            self.assertNotEqual(before, after)

    def test_metadata_content_fence_even_with_equal_stat(self):
        with tempfile.TemporaryDirectory() as taskdir:
            path = Path(taskdir) / 'region.pb'
            path.write_bytes(b'release0')
            before = file_identity(path)
            digest = hashlib.sha256(path.read_bytes()).digest()
            path.write_bytes(b'release1')
            os.utime(path, (before[3], before[3]))
            self.assertEqual(before[:4], file_identity(path)[:4])
            self.assertNotEqual(digest, hashlib.sha256(path.read_bytes()).digest())

    @unittest.skipUnless(LIB.is_file(), 'local firmware corpus absent')
    def test_pinned_native_layout_and_atomic_rename_evidence(self):
        data = LIB.read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), 'c2842633cc3db8301f252aff52df86aab4c64a8b10b826f80f4f922a327d340f')
        for at, text in [(0x433820, b'offline_caches\0'), (0x514e31, b'region.pb\0'), (0x4c609a, b'.fb\0')]:
            self.assertEqual(data[at:at + len(text)], text)
        self.assertEqual(data[0x1af6dbc:0x1af6dc4].hex(), 'ffc302d1fd7b08a9')
        evidence = ROOT / 'captures/yandex-vector-enrichment-20261024/offline-files/rename-import.json'
        self.assertEqual(json.loads(evidence.read_text())['symbol'], 'rename')


if __name__ == '__main__':
    unittest.main()
