"""Product compilation boundary, independent of historical sender goldens or private corpus."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from build import ROOT, LEGACY_ADAS_CLASSES, product_adas_sources, verify_product_classes


class ProductSourceClosure(unittest.TestCase):
    def test_dead_sender_is_absent_and_native_adapter_is_present(self):
        names = {p.stem for p in product_adas_sources()}
        self.assertFalse(names & LEGACY_ADAS_CLASSES)
        self.assertTrue({'StockSender', 'YandexNativeAdapter', 'OfflineVectorPackage', 'YandexRoute'} <= names)

    def test_packaged_nested_legacy_class_is_rejected(self):
        verify_product_classes(['dev/denza/adaslink/stocknative/StockSender.class'])
        with self.assertRaises(RuntimeError):
            verify_product_classes(['dev/denza/adaslink/StockFeed$Config.class'])

    def test_only_active_adas_sources_compile_with_java8(self):
        java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
        with tempfile.TemporaryDirectory() as out:
            subprocess.run([str(java / 'bin/javac'), '--release', '8', '-encoding', 'UTF-8', '-d', out,
                            *map(str, product_adas_sources())], check=True, capture_output=True, text=True)
            entries = [str(p.relative_to(out)) for p in Path(out).rglob('*.class')]
            verify_product_classes(entries)
            self.assertTrue(entries)

    def test_observed_raw_vector_fallback_product_input(self):
        java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
        module = ROOT / 'experiments/yandex-hud-module/src'
        with tempfile.TemporaryDirectory() as out:
            subprocess.run([str(java / 'bin/javac'), '--release', '8', '-encoding', 'UTF-8', '-d', out,
                            *map(str, product_adas_sources()),
                            str(module/'main/java/dev/denza/hud/probe/ObservedVectorRouteAttributes.java'),
                            str(module/'test/java/dev/denza/hud/probe/ObservedVectorRouteAttributesTest.java')],
                           check=True, capture_output=True, text=True)
            subprocess.run([str(java/'bin/java'), '-cp', out, 'dev.denza.hud.probe.ObservedVectorRouteAttributesTest'],
                           check=True, capture_output=True, text=True)


if __name__ == '__main__':
    unittest.main()
