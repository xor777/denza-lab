"""Host source-contract regressions; original reference bytes still come only from ART captures."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from . import bench

ROOT = Path(__file__).resolve().parents[3]


class NativeSenderCoreTest(unittest.TestCase):
    def test_native_sender_edge_contracts(self):
        runtime = bench.jdk()
        sources = [p for p in (ROOT / 'libraries/adas-link/src/main/java').rglob('*.java')
                   if 'import android.' not in p.read_text()]
        test = ROOT / 'libraries/adas-link/src/test/java/dev/denza/adaslink/stocknative/NativeSenderCoreChecks.java'
        with tempfile.TemporaryDirectory(prefix='stock-native-core-') as directory:
            compiled = subprocess.run([str(runtime/'javac'), '--release', '8', '-nowarn', '-d', directory,
                                      *map(str, sources), str(test)], capture_output=True, text=True, timeout=60)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run([str(runtime/'java'), '-cp', directory,
                                     'dev.denza.adaslink.stocknative.NativeSenderCoreChecks'],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('native sender core checks passed', result.stdout)
