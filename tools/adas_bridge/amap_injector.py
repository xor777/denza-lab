"""Where the stock-map tools live since they left this repository (2026-10-24).

The factory-map readers, the field-coverage gate, the map emulator and the Beijing profile
moved, with their history, to the private amap-injector repository under the same paths
(`tools/stock-map/`, `research/stock-map-native/`). The bench tools that read factory or
OSM-built packages take them from a local checkout of it: `DENZA_AMAP_INJECTOR`, or the
checkout next to this one (`../amap-injector`). The bridge itself and its tests need none
of it.
"""
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def root():
    """The amap-injector checkout; exits with the reason when there is none."""
    path = Path(os.environ.get('DENZA_AMAP_INJECTOR') or ROOT.parent / 'amap-injector')
    if not (path / 'tools/stock-map').is_dir():
        raise SystemExit('this tool reads the stock-map tools from a checkout of the private '
                         'amap-injector repository; none at %s (set DENZA_AMAP_INJECTOR)' % path)
    return path


def path(relative):
    """A file or directory of the checkout, e.g. 'research/stock-map-native/x.json'."""
    return root() / relative


def importable(*relatives):
    """Puts the checkout's directories on sys.path, e.g. importable('tools/stock-map')."""
    for relative in relatives:
        directory = str(path(relative))
        if directory not in sys.path:
            sys.path.insert(0, directory)
