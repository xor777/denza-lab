"""build.py emits a release only under the pinned signer (review 2026-11-19, round 2, finding 10).

    python3 -m unittest tools/yandex_hud_build/test_signer.py   (from the repository root)
"""
import argparse
import os
from pathlib import Path
import re
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build  # noqa: E402
from identity import SIGNER_SHA256  # noqa: E402

JAVA = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))


class SignerTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.out = Path(self.work.name)

    def tearDown(self):
        self.work.cleanup()

    def args(self, keystore, throwaway=False):
        return argparse.Namespace(keystore=keystore, throwaway_signer=throwaway, out=self.out)

    def test_a_missing_release_key_stops_the_build_instead_of_making_a_new_one(self):
        missing = self.out/'experiment.p12'
        with self.assertRaises(SystemExit) as stopped:
            build.signing_key(self.args(missing), JAVA)
        self.assertIn('No release signer', str(stopped.exception))
        self.assertFalse(missing.exists())

    def test_another_key_is_not_the_release_signer(self):
        other, digest = build.signing_key(self.args(None, throwaway=True), JAVA)
        self.assertNotEqual(digest, SIGNER_SHA256)
        with self.assertRaises(SystemExit) as stopped:
            build.signing_key(self.args(other), JAVA)
        self.assertIn('is not the release signer', str(stopped.exception))

    def test_the_release_key_passes(self):
        key = Path(os.environ.get('YANDEX_HUD_KEYSTORE', build.ROOT/'captures/yandex-hud-signing/experiment.p12'))
        if not key.is_file():
            self.skipTest('the release key is not in this checkout; set YANDEX_HUD_KEYSTORE')
        self.assertEqual(build.signing_key(self.args(key), JAVA), (key, SIGNER_SHA256))

    def test_the_pin_is_the_certificate_denza_apps_hosts(self):
        gate = (build.ROOT/'apps/denza-apps/src/main/java/dev/denza/apps/feature/hud/HudHostGate.kt').read_text()
        self.assertIn(SIGNER_SHA256, re.findall(r'[0-9a-f]{64}', gate))


if __name__ == '__main__':
    unittest.main()
