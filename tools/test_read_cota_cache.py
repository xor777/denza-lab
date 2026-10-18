"""Offline allowlist and no-authorization guards; no car/network operations."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import read_cota_cache as probe


class CotaCacheTests(unittest.TestCase):
    def test_profile_scope(self):
        common = probe.common
        paths, caps, optional = common.export_profile('cota-cache')
        self.assertEqual(len(paths), 6)
        self.assertEqual(max(caps), 256 * 1024)
        self.assertEqual(optional, frozenset(paths))
        for path in paths:
            self.assertTrue(common.packet(1, path, 'cota-cache'))
            self.assertTrue(common.packet(2, str(Path(path).parent), 'cota-cache'))
        for action, path in ((3, paths[0]), (True, paths[0]),
                (1, common.FILES[1]), (2, '/collect2'),
                (1, paths[0] + '/../identity.key')):
            with self.subTest(action=action, path=path), self.assertRaises(common.ERROR):
                common.packet(action, path, 'cota-cache')

    def test_authorization_before_export(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(probe.common, 'export') as export:
            out = Path(tmp) / 'new'
            with self.assertRaisesRegex(probe.common.ERROR, 'fresh_owner_authorization_required'):
                probe.read(out)
            export.assert_not_called()
            self.assertFalse(out.exists())

    def test_missing_and_existing_cache(self):
        data = dict.fromkeys(probe.common.COTA_FILES, b'')
        data[probe.common.COTA_FILES[0]] = b'{"nativePackages":[]}'
        with tempfile.TemporaryDirectory() as tmp, patch.object(probe.common, 'export', return_value=data) as export:
            out = Path(tmp) / 'new'
            probe.read(out, True)
            export.assert_called_once_with(out, profile='cota-cache')
            records = json.loads((out / 'files.json').read_text())
            self.assertEqual(sum(r['status'] == 'saved' for r in records), 1)
            self.assertEqual((out / '0-cloud-config.json').stat().st_mode & 0o777, 0o600)


if __name__ == '__main__':
    unittest.main()
