"""Execution-ledger unit tests with synthetic inputs, never ORIGINAL output oracles."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from . import bench

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


class NativeReplayProtocolTest(unittest.TestCase):
    def test_overlapping_native_reads_and_enqueue_dispatch_race(self):
        jdk = bench.jdk()
        sources = [p for p in (ROOT/'libraries/adas-link/src/main/java').rglob('*.java')
                   if 'import android.' not in p.read_text()]
        sources.extend((HERE/'phase2').glob('*.java'))
        sources.append(HERE/'tests/NativeReplayChecks.java')
        with tempfile.TemporaryDirectory(prefix='stock-native-replay-') as directory:
            result = subprocess.run([str(jdk/'javac'), '--release', '8', '-nowarn', '-d', directory,
                                     *map(str, sources)], capture_output=True, text=True, timeout=60)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([str(jdk/'java'), '-cp', directory,
                                     'dev.denza.adaslink.stocknative.NativeReplayChecks'],
                                    capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('native replay protocol checks passed', result.stdout)
