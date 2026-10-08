#!/usr/bin/env python3
"""The firmware already extracted and decompiled on this Mac, indexed, and a lookup over it.

Between 2026-09-23 and 2026-10-06 seven sessions copied parts of the same two OTAs into seven
topic directories under captures/, decompiled the same APKs again (DiCarServer three times,
AutoVideo at least four) and diffed old decompiles against new ones to learn their version.
Before extracting or decompiling anything, look it up:

    python3 tools/firmware_corpus.py find DiShare     # every copy, tree and listing entry
    python3 tools/firmware_corpus.py index            # rebuild captures/firmware-index.tsv

The index covers every captures/*/extraction.json (files copied out of an OTA, with SHA-256 and
the local path they landed at), every jadx tree under captures/ and reverse/ (package, version,
whether resources were decoded, and the copied APK it came from when one was recorded), loose
APKs and JARs (hashed once, then cached), and the partition listings captures/*/files-*.txt.
docs/firmware-corpus.md says what the archives are and where new work goes.
"""
import argparse
import hashlib
import json
import os
import re
import sys
from dataclasses import astuple, dataclass, fields
from pathlib import Path

IVI_BUILD = 'ivi-34.1.33.2605218.1'
FSE_BUILD = 'fse-42.1.8.2605219.1'
ARCHIVE_BUILDS = {
    'Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip': IVI_BUILD,
    'Di5.1_FSE_42.1.8.2605219.1.42.2.3.2605250.2.zip': FSE_BUILD,
}
# Decompiles and APKs under reverse/ predate the OTAs: they were pulled from the car, so only
# their own manifest or BuildConfig says which build they are.
PULLED = 'pulled-from-car'
OTA_ARRIVED = '20260923'
INDEX = 'firmware-index.tsv'
CACHE = '.firmware-index-cache.json'
BINARY = ('.apk', '.jar')
MAX_DEPTH = 4


@dataclass
class Row:
    kind: str          # file | jadx | binary | listing
    build: str
    name: str
    package: str = ''
    version: str = ''
    resources: str = ''
    image_path: str = ''
    sha256: str = ''
    size: str = ''
    local_path: str = ''


def build_of(capture: str, record: dict = None) -> str:
    """Which OTA a capture's file came from: recorded since 2026-10-08, by directory before.

    The OTAs arrived on 2026-09-23; a capture dated earlier holds what was pulled from the car.
    A hash that matches a file copied out of an OTA overrides all of this (see index).
    """
    archive = (record or {}).get('archive')
    if archive:
        return ARCHIVE_BUILDS.get(archive, archive)
    if capture.startswith('fse-'):
        return FSE_BUILD
    dated = re.search(r'(20\d{6})', capture)
    if dated and dated.group(1) < OTA_ARRIVED:
        return PULLED
    return IVI_BUILD


def locate(capture: Path, record: dict, by_name: dict) -> str:
    """Where an extracted file sits: the readers put it at <capture>/<partition>/<path>, and the
    IVI system partition is system-as-root, so /system/x lands at <capture>/system/system/x."""
    path = record['path'].lstrip('/')
    candidates = [capture / record.get('partition', 'system') / path, capture / 'system' / path,
                  capture / path]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    for candidate in by_name.get(Path(path).name, []):
        if candidate.stat().st_size == record.get('size'):
            return str(candidate)
    return ''


def walk_files(root: Path, skip=()):
    for dirpath, dirnames, filenames in os.walk(root):
        here = Path(dirpath)
        dirnames[:] = [d for d in dirnames if here / d not in skip and not is_tree(here / d)]
        for name in filenames:
            yield here / name


def is_tree(path: Path) -> bool:
    """A jadx output directory: a decoded manifest, or decompiled Java under sources/.

    Evidence directories have a sources/ of their own too - scripts, or the Java of our own
    experiments (`package dev.denza...`) - and neither is firmware.
    """
    if (path / 'resources' / 'AndroidManifest.xml').is_file():
        return True
    sources = path / 'sources'
    if not sources.is_dir():
        return False
    seen = 0
    for dirpath, _, filenames in os.walk(sources):
        for name in filenames:
            if not name.endswith('.java'):
                continue
            with open(Path(dirpath) / name, errors='replace') as java:
                head = java.read(400)
            if not re.search(r'^package dev\.denza\b', head, re.MULTILINE):
                return True
            seen += 1
            if seen >= 5:
                return False
    return False


def trees_under(root: Path, depth: int = 0):
    """jadx output directories: anything holding sources/ or a decoded manifest."""
    if depth > MAX_DEPTH or not root.is_dir():
        return
    for child in sorted(root.iterdir()):
        if not child.is_dir() or child.name.startswith('.'):
            continue
        if is_tree(child):
            yield child
        else:
            yield from trees_under(child, depth + 1)


def manifest_of(tree: Path, cache: dict):
    """(package, version, resources) from the decoded manifest, else from the app's BuildConfig."""
    key = str(tree)
    stamp = tree.stat().st_mtime_ns
    cached = cache.get(key)
    if cached and cached[0] == stamp:
        return tuple(cached[1:])
    manifest = tree / 'resources' / 'AndroidManifest.xml'
    if manifest.is_file():
        text = manifest.read_text(errors='replace')
        package = first(r'\bpackage="([^"]+)"', text)
        version = join_version(first(r'android:versionName="([^"]*)"', text),
                               first(r'android:versionCode="([^"]*)"', text))
        result = (package, version, 'y')
    else:
        result = ('', '', 'n')
        # Without a manifest, a BuildConfig is only the app's own when it names BYD or the tree:
        # the bundled libraries carry theirs too (io.reactivex, ijkplayer, android.cross).
        own = re.sub(r'[^a-z]', '', tree.name.lower().replace('jadx', ''))
        for config in walk_build_configs(tree / 'sources'):
            text = config.read_text(errors='replace')
            package = first(r'APPLICATION_ID\s*=\s*"([^"]+)"', text)
            if package and ('byd' in package or (own and own in package.replace('.', ''))):
                version = join_version(first(r'VERSION_NAME\s*=\s*"([^"]*)"', text),
                                       first(r'VERSION_CODE\s*=\s*(\d+)', text))
                result = (package, version, 'n')
                break
    cache[key] = [stamp, *result]
    return result


def walk_build_configs(sources: Path):
    for dirpath, _, filenames in os.walk(sources):
        if 'BuildConfig.java' in filenames:
            yield Path(dirpath) / 'BuildConfig.java'


def first(pattern: str, text: str) -> str:
    match = re.search(pattern, text)
    return match.group(1) if match else ''


def join_version(name: str, code: str) -> str:
    return f'{name} ({code})' if name and code else name or code


def sha256_of(path: Path, cache: dict) -> str:
    key = str(path)
    info = path.stat()
    cached = cache.get(key)
    if cached and cached[0] == info.st_size and cached[1] == info.st_mtime_ns:
        return cached[2]
    digest = hashlib.sha256()
    with open(path, 'rb') as handle:
        for block in iter(lambda: handle.read(8 << 20), b''):
            digest.update(block)
    cache[key] = [info.st_size, info.st_mtime_ns, digest.hexdigest()]
    return digest.hexdigest()


def index(root: Path):
    captures, reverse = root / 'captures', root / 'reverse'
    cache_path = captures / CACHE
    cache = json.loads(cache_path.read_text()) if cache_path.is_file() else {}
    hashes, metas = cache.setdefault('sha256', {}), cache.setdefault('trees', {})
    rows = []
    for capture in sorted(p for p in captures.glob('*/') if p.is_dir()):
        records = []
        if (capture / 'extraction.json').is_file():
            records = json.loads((capture / 'extraction.json').read_text())
        trees = list(trees_under(capture))
        if not records and not trees:
            continue  # a live capture or our own builds, not firmware
        by_name = {}
        loose = []
        for path in walk_files(capture, skip=set(trees)):
            by_name.setdefault(path.name, []).append(path)
            if path.suffix in BINARY:
                loose.append(path)
        recorded = set()
        for record in records:
            local = locate(capture, record, by_name)
            recorded.add(local)
            rows.append(Row('file', build_of(capture.name, record), Path(record['path']).name,
                            image_path=record['path'], sha256=record.get('sha256', ''),
                            size=str(record.get('size', '')), local_path=local))
        sources = {Path(r['path']).stem.lower(): r for r in records}
        for tree in trees:
            package, version, resources = manifest_of(tree, metas)
            source = sources.get(tree.name.lower(), {})
            rows.append(Row('jadx', build_of(capture.name, source), tree.name, package, version,
                            resources, source.get('path', ''), source.get('sha256', ''),
                            str(source.get('size', '')), str(tree)))
        for path in loose:
            if str(path) not in recorded:
                rows.append(Row('binary', build_of(capture.name), path.name,
                                sha256=sha256_of(path, hashes), size=str(path.stat().st_size),
                                local_path=str(path)))
        for listing in sorted(capture.glob('files-*.txt')):
            rows.append(Row('listing', build_of(capture.name), listing.name,
                            local_path=str(listing)))
    if reverse.is_dir():
        trees = list(trees_under(reverse))
        for tree in trees:
            package, version, resources = manifest_of(tree, metas)
            rows.append(Row('jadx', PULLED, tree.name, package, version, resources,
                            local_path=str(tree)))
        for path in walk_files(reverse, skip=set(trees)):
            if path.suffix in BINARY:
                rows.append(Row('binary', PULLED, path.name, sha256=sha256_of(path, hashes),
                                size=str(path.stat().st_size), local_path=str(path)))
    # Identical bytes are the same build, wherever they were found: a jar pulled from the car
    # that hashes like the OTA's copy is the OTA's.
    ota = {r.sha256: r.build for r in rows if r.kind == 'file' and r.sha256 and r.build != PULLED}
    for row in rows:
        if row.kind in ('binary', 'jadx') and row.sha256 in ota:
            row.build = ota[row.sha256]
    rows.sort(key=lambda r: (r.name.lower(), r.build, r.kind, r.local_path))
    with open(captures / INDEX, 'w') as out:
        out.write('\t'.join(f.name for f in fields(Row)) + '\n')
        for row in rows:
            out.write('\t'.join(astuple(row)) + '\n')
    cache_path.write_text(json.dumps(cache))
    return rows


def load(root: Path):
    path = root / 'captures' / INDEX
    if not path.is_file():
        return index(root)
    lines = path.read_text().splitlines()[1:]
    return [Row(*line.split('\t')) for line in lines if line]


def find(root: Path, terms, listing_limit: int = 20):
    """Rows whose name, package or paths contain every term, and listing lines that do."""
    terms = [t.lower() for t in terms]
    rows = load(root)
    hits = [r for r in rows if r.kind != 'listing'
            and all(any(t in v.lower() for v in (r.name, r.package, r.image_path, r.local_path))
                    for t in terms)]
    listed = []
    for row in rows:
        if row.kind != 'listing' or not Path(row.local_path).is_file():
            continue
        found = 0
        with open(row.local_path, errors='replace') as listing:
            for line in listing:
                if all(t in line.lower() for t in terms):
                    listed.append((row, line.rstrip()))
                    found += 1
                    if found >= listing_limit:
                        break
    return hits, listed


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('index', help='rebuild captures/firmware-index.tsv')
    look = commands.add_parser('find', help='every copy, tree and listing entry matching all terms')
    look.add_argument('terms', nargs='+')
    args = parser.parse_args(argv)
    if args.command == 'index':
        rows = index(args.root)
        kinds = {}
        for row in rows:
            kinds[row.kind] = kinds.get(row.kind, 0) + 1
        print(f'{len(rows)} rows in captures/{INDEX}:',
              ', '.join(f'{n} {k}' for k, n in sorted(kinds.items())))
        return
    hits, listed = find(args.root, args.terms)
    root = str(args.root) + os.sep
    for r in hits:
        version = f' {r.version}' if r.version else ''
        package = f' {r.package}' if r.package else ''
        res = ' +res' if r.resources == 'y' else ' no-res' if r.resources == 'n' else ''
        digest = f' sha256:{r.sha256[:12]}' if r.sha256 else ''
        where = r.local_path.replace(root, '') or '(recorded, not on disk)'
        print(f'{r.kind:7} {r.build:22} {r.name}{package}{version}{res}{digest}  {where}')
    for row, line in listed:
        print(f'listed  {row.build:22} {line}  ({row.local_path.replace(root, "")})')
    if not hits and not listed:
        print('nothing: not extracted, not decompiled, not in any partition listing')


if __name__ == '__main__':
    sys.exit(main())
