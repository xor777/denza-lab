#!/usr/bin/env python3
"""Offline inventory of the owner's DiCarServer property profiles.

No Android, vehicle, network or subprocess access. This is an API declaration
index, not a probe or a list of installed sensors. Run from the repository root.
The compact rows retain the original property key; native IDs are a separate,
conservative join to the previously resolved CAN-FD framework table.
"""

import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import re


def varint(data, pos):
    value = 0
    for shift in range(0, 70, 7):
        if pos >= len(data):
            raise ValueError("truncated varint")
        byte = data[pos]
        pos += 1
        if shift == 63 and byte > 1:
            raise ValueError("uint64 overflow")
        value |= (byte & 127) << shift
        if byte < 128:
            return value, pos
    raise ValueError("overlong varint")


def decode(data):
    """FeatureList.field1 messages; four proto3 varints, omitted fields are 0.

    Fail on unknown fields instead of silently projecting a changed schema.
    Duplicate scalar fields use protobuf's last-value semantics. Retain every
    message in order; HalFeatureProvider then puts by featureID (last wins).
    """
    rows = []
    pos = 0
    while pos < len(data):
        tag, pos = varint(data, pos)
        if tag != 10:
            raise ValueError(f"unexpected FeatureList tag {tag}")
        length, pos = varint(data, pos)
        end = pos + length
        if end > len(data):
            raise ValueError("truncated Feature")
        fields = [0, 0, 0, 0]
        message = data[pos:end]
        inner = 0
        while inner < len(message):
            tag, inner = varint(message, inner)
            if tag not in (8, 16, 24, 32):
                raise ValueError(f"unexpected Feature tag {tag}")
            value, inner = varint(message, inner)
            fields[tag // 8 - 1] = value
        rows.append(fields)
        pos = end
    return rows


def java_type(value):
    # HalFeatureProvider.getDataType; 4 is NOT float in that method.
    return {0: "int", 1: "byte[]", 2: "float"}.get(value, "double")


def build(root):
    base = root / 'captures/ambient-light-20260923/jadx/DiCarServer'
    sources = base / 'sources'
    features = sources / 'com/byd/feature'
    framework = root / 'captures/split-firmware-20260923/jadx/framework/sources'
    table = root / 'captures/ambient-light-20260923/data/fids-canfd.tsv'
    reflection = root / 'captures/diagnostics-sweep-20261020/fids.tsv'
    used = set()

    def read(path):
        used.add(path)
        return path.read_text()

    provider = sources / 'com/byd/auto/provider/HalFeatureProvider.java'
    read(provider)
    read(sources / 'com/byd/auto/provider/DataType.java')
    read(sources / 'com/byd/auto/provider/FeatureLists.java')
    mapping_path = features / 'bydauto/BydAutoMapping.java'
    mapping = read(mapping_path)
    imports = dict((name.rsplit('.', 1)[-1], sources / (name.replace('.', '/') + '.java'))
                   for name in re.findall(r'import (com\.byd\.feature\.[\w.]+);', mapping))
    mapper_names = {}
    mapper_assignments = defaultdict(list)
    pattern = r'if \((?:(\d+) == i|i == (\d+))\) \{\s*this\.mMappers\.put\(i, new (\w+)\(\)\);'
    for first, second, name in re.findall(pattern, mapping):
        index = int(first or second)
        mapper_names[index] = name
        mapper_assignments[index].append(name)

    native_table = defaultdict(list)
    for line in read(table).splitlines():
        category, name, number = line.split('\t')
        native_table[(category, name)].append(int(number))

    runtime_ids = defaultdict(set)
    for line in read(reflection).splitlines():
        if line.startswith('CAT\t'):
            _, device, number, aliases = line.split('\t')
            for qualified_name in aliases.split('|'):
                runtime_ids[(int(device), qualified_name)].add(int(number, 16))

    mappers = {}
    for index, name in sorted(mapper_names.items()):
        path = imports[name]
        text = read(path)
        literals = {}
        for cls in re.findall(r'map\.put\((\w+)\.\w+,', text):
            if cls not in literals:
                literal_path = path.parent / (cls + '.java')
                if literal_path.exists():
                    literals[cls] = dict(re.findall(r'public static final String (\w+) = "(0x[\da-fA-F]+)";', read(literal_path)))
                else:
                    literals[cls] = {}
        names = {}
        for cls, field, literal, native_name in re.findall(
                r'map\.put\((?:(\w+)\.(\w+)|"(0x[\da-fA-F]+)"), "(\w+)"\);', text):
            key = literal or literals[cls].get(field)
            if key:
                names[int(key, 16)] = native_name
        # Only the exact nested-table class used by reflection, then TOP.
        nested = re.findall(r'BYDAutoFeatureIds\.(\w+)\.class\.getField', text)
        device_classes = set(re.findall(r'return (\w+)\.getInstance\(context\);', text))
        device_imports = [item for item in re.findall(r'import (android\.hardware\.bydauto\.[\w.]+Device);', text)
                          if item.rsplit('.', 1)[-1] in device_classes]
        device = None
        device_source = None
        if len(device_imports) == 1:
            device_source = framework / (device_imports[0].replace('.', '/') + '.java')
            if device_source.exists():
                values = set(re.findall(r'\bmDeviceType = (\d+);', read(device_source)))
                if len(values) == 1:
                    device = int(next(iter(values)))
        mappers[index] = dict(name=name, names=names, nested=nested, nativeDevice=device)

    profiles = {}
    summary = {}
    for number, access, expected_flow in [(1, 'read', 0), (2, 'write', 1), (3, 'read_write', 2)]:
        path = base / f'resources/assets/config_{number}.bin'
        used.add(path)
        rows = decode(path.read_bytes())
        if any(row[2] != expected_flow for row in rows):
            raise ValueError(f"unexpected flow in {path}")
        effective = {}
        duplicates = []
        for row in rows:
            if row[0] in effective:
                duplicates.append(dict(propertyKey=f'0x{row[0]:08X}', previous=effective[row[0]], replacement=row))
            effective[row[0]] = row
        profiles[access] = effective
        summary[access] = dict(records=len(rows), uniqueKeys=len(effective),
                               rawTypeCounts=dict(sorted(Counter(row[3] for row in rows).items())),
                               duplicateReplacements=duplicates)

    output_rows = []
    for access in ['read', 'read_write']:
        for key, interface, flow, dtype in sorted(profiles[access].values()):
            mapper = mappers.get(interface)
            symbol = mapper['names'].get(key) if mapper else None
            native = None
            resolution = 'unresolved'
            if mapper and symbol:
                for category in mapper['nested'] + ['BYDAutoFeatureIds']:
                    values = runtime_ids[(mapper['nativeDevice'], category + '.' + symbol)]
                    if len(values) == 1:
                        native = next(iter(values)) & 0xffffffff
                        resolution = 'profile170_reflection:' + category
                        break
                    if len(values) > 1:
                        resolution = 'ambiguous_reflection:' + category
                        break
            if mapper and symbol and resolution == 'unresolved':
                for category in mapper['nested'] + ['TOP']:
                    values = set(native_table[(category, symbol)])
                    if len(values) == 1:
                        native = next(iter(values)) & 0xffffffff
                        resolution = 'canfd_table:' + category
                        break
                    if len(values) > 1:
                        resolution = 'ambiguous_table:' + category
                        break
            output_rows.append([f'0x{key:08X}', access, interface,
                                mapper['name'] if mapper else None,
                                mapper['nativeDevice'] if mapper else None,
                                symbol, dtype, java_type(dtype),
                                f'0x{native:08X}' if native is not None else None,
                                resolution])
    read_keys = set(profiles['read']) | set(profiles['read_write'])
    overlaps = set(profiles['read']) & set(profiles['read_write'])
    summary['readable'] = dict(uniqueKeys=len(read_keys), rows=len(output_rows),
                              readRwOverlaps=[f'0x{x:08X}' for x in sorted(overlaps)],
                              namedRows=sum(row[5] is not None for row in output_rows),
                              resolvedNativeRows=sum(row[8] is not None for row in output_rows),
                              propertyNativeMismatchRows=sum(row[8] is not None and row[0] != row[8] for row in output_rows),
                              declaredJavaTypes=dict(Counter(row[7] for row in output_rows)))
    return dict(
        schema='denza-lab.dicar-readable-property-catalog.v1', date='2026-10-21',
        scope='Offline stock firmware API declarations; no runtime or installed-hardware assertion.',
        sources=[dict(path=str(p.relative_to(root)), sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sorted(used)],
        summary=summary,
        mapperAssignments={str(k): v for k, v in sorted(mapper_assignments.items())},
        columns=['propertyKey', 'access', 'interfaceIndex', 'mapper', 'nativeDevice', 'symbol',
                 'declaredTypeCode', 'declaredJavaType', 'nativeCanFdHex', 'nativeResolution'],
        rows=output_rows,
        limits=[
            'Contains configuration/status/command surfaces as well as measurements; not an execution allowlist.',
            'A readable declaration is not a proven response, installed ECU, sensor, unit or scaling.',
            'DiCar interfaceIndex, nativeDevice, propertyKey, native FID, CAN route and UDS DID are different namespaces.',
            'Native IDs use existing profile170 reflection by exact device and qualified mapper symbol; nested class before top level. Then the resolved CAN-FD table, with the same class precedence. No numeric-key fallback is qualified here.',
            'Unresolved rows may be resolvable from concrete framework initializers; null does not imply unsupported.',
            'Duplicate keys within a profile use last-value semantics; both declarations are retained across read/RW overlap.',
            'Declared Java type follows HalFeatureProvider, not raw Binder transact numbers or historical scalar-read type.',
            'Mapper selection follows source insertion order; interface2 ends as BodyMapper and42 as VehicleMapper.',
            'No ECU ownership inferred from a category/name. No writes, live reads or dictionary acquisition performed.',
        ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    result = build(args.root.resolve())
    # One compact row per line keeps the ~10k declarations diffable without a
    # multi-megabyte pretty-printed artifact.
    rows = result.pop('rows')
    prefix = json.dumps(result, ensure_ascii=False, indent=2)
    args.out.write_text(prefix[:-2] + ',\n  "rows": [\n' + ',\n'.join(
        '    ' + json.dumps(row, ensure_ascii=False, separators=(',', ':')) for row in rows) + '\n  ]\n}\n')
    print(json.dumps(result['summary']['readable'], ensure_ascii=False))


if __name__ == '__main__':
    main()
