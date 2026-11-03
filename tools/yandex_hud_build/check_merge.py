#!/usr/bin/env python3
"""Prove that a standalone APK retained every resource value from its source splits."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

RESOURCE = re.compile(r'^    resource (0x[0-9a-f]{8}) (\S+)$')
PLAY_SPLIT_RESOURCE = 'xml/splits0'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def resource_table(aapt2, apk):
    with zipfile.ZipFile(apk) as archive:
        if 'resources.arsc' not in archive.namelist():
            return {}  # ABI-only splits have no resource table.
    text = subprocess.check_output([str(aapt2), 'dump', 'resources', str(apk)], text=True)
    entries = {}
    current = None
    for line in text.splitlines():
        match = RESOURCE.match(line)
        if match:
            current = match.group(2)
            if current in entries:
                raise RuntimeError(f'Duplicate resource in {apk}: {current}')
            entries[current] = {'id': match.group(1), 'values': []}
        elif current is not None and line.startswith('      '):
            entries[current]['values'].append(line[6:])
        elif line and not line.startswith(' '):
            current = None
    return entries


def canonical_values(entry, ids):
    """aapt2 prints newly resolved references by name; compare their numeric identity."""
    values = []
    for value in entry['values']:
        value = re.sub(r'@([a-z_]+/[A-Za-z0-9_.$]+)',
                       lambda match: '@' + ids.get(match.group(1), match.group(1)), value)
        value = re.sub(r'parent=(?:\S+ \()?(0x[0-9a-f]{8})\)?', r'parent=\1', value)
        value = re.sub(r'\b[\w.]+\((0x[0-9a-f]{8})\)=', r'\1=', value)
        values.append(value)
    return values


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('parts', nargs='+', type=Path, help='Original base and config APKs')
    parser.add_argument('--merged', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--aapt2', type=Path, default=Path(
        os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools')
    ) / 'build-tools/36.0.0/aapt2')
    args = parser.parse_args()
    merged = resource_table(args.aapt2, args.merged)
    ids = {name: entry['id'] for name, entry in merged.items()}
    report = {
        'merged': str(args.merged.resolve()),
        'merged_sha256': sha256(args.merged),
        'merged_resource_entries': len(merged),
        'parts': [],
        'allowed_removed': [PLAY_SPLIT_RESOURCE],
        'missing': [],
        'changed': [],
    }
    checked_values = 0
    for part in args.parts:
        table = resource_table(args.aapt2, part)
        part_report = {'apk': str(part.resolve()), 'sha256': sha256(part),
                       'resource_entries': len(table),
                       'resource_values': sum(len(entry['values']) for entry in table.values())}
        report['parts'].append(part_report)
        for name, source in table.items():
            target = merged.get(name)
            if target is None:
                if name != PLAY_SPLIT_RESOURCE:
                    report['missing'].append({'part': str(part), 'resource': name})
                continue
            if target['id'] != source['id']:
                report['changed'].append({'part': str(part), 'resource': name,
                                          'source_id': source['id'], 'merged_id': target['id']})
            source_values = canonical_values(source, ids)
            target_values = canonical_values(target, ids)
            missing_values = Counter(source_values) - Counter(target_values)
            if missing_values:
                report['changed'].append({'part': str(part), 'resource': name,
                                          'missing_values': list(missing_values.elements())})
            checked_values += len(source['values'])
    report['checked_source_values'] = checked_values
    report['passed'] = not report['missing'] and not report['changed']
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({key: value for key, value in report.items()
                      if key not in ('parts', 'missing', 'changed')}, indent=2))
    if not report['passed']:
        raise RuntimeError(f"Resource merge check failed: {len(report['missing'])} missing, "
                           f"{len(report['changed'])} changed; see {args.out}")


if __name__ == '__main__':
    main()
