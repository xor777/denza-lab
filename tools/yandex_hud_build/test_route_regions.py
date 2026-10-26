"""Compile pinned typed SDK callbacks and test bounded route discovery without an Android runtime."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RouteRegions(unittest.TestCase):
    def test_callbacks_manual_downloads_and_route_fences(self):
        java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home')) / 'bin'
        sdk = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools'))
        android = sdk / 'platforms/android-35/android.jar'
        source = ROOT / 'experiments/yandex-hud-module/src'
        package = source / 'main/java/dev/denza/hud/probe'
        with tempfile.TemporaryDirectory() as out:
            stubs = list((ROOT / 'tools/yandex_hud_build/compile_only').rglob('*.java'))
            subprocess.run([str(java/'javac'), '--release', '8', '-encoding', 'UTF-8', '-cp', str(android),
                            '-d', out, *map(str, stubs),
                            str(package/'Reflect.java'), str(package/'YandexOfflineInventory.java'),
                            str(package/'YandexRouteRegions.java'),
                            str(source/'test/java/dev/denza/hud/probe/YandexOfflineInventoryTest.java'),
                            str(source/'test/route-regions/dev/denza/hud/probe/YandexRouteRegionsTest.java')], check=True)
            for name in ('YandexOfflineInventoryTest', 'YandexRouteRegionsTest'):
                subprocess.run([str(java/'java'), '-cp', out, 'dev.denza.hud.probe.'+name], check=True)


if __name__ == '__main__':
    unittest.main()
