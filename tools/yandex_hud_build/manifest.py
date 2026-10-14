"""Minimal binary-XML edit: query the HUD service and mark an experimental debug build.

The application and MapActivity labels become LABEL: the launcher shortcut is an alias without a
label of its own, which Android resolves through its target activity, so both are needed for the
shortcut, the app list and recents to tell this build from the owner's original Navigator.

Preserves resource IDs, original string indices, attributes, namespaces and all
other elements except the declaration of Yandex's shared signature permission:
our differently signed fork must not claim ownership of it. Permission uses and
component guards stay unchanged. The input is independently SHA-pinned.
"""
import struct

U32 = lambda data, offset: struct.unpack_from('<I', data, offset)[0]
LABEL = 'Яндекс HUD'
LAUNCH_TARGET = 'ru.yandex.yandexmaps.app.MapActivity'
SHARED_PERMISSION = 'com.yandex.permission.READ_CREDENTIALS'


def chunks(data, start=8):
    offset = start
    while offset < len(data):
        kind, header, size = struct.unpack_from('<HHI', data, offset)
        if size < header or offset + size > len(data):
            raise ValueError('Invalid XML chunk')
        yield kind, bytearray(data[offset:offset + size])
        offset += size
    if offset != len(data):
        raise ValueError('Truncated XML')


def length(data, offset, utf8):
    if utf8:
        value = data[offset]
        return (((value & 127) << 8) | data[offset + 1], offset + 2) if value & 128 else (value, offset + 1)
    value = struct.unpack_from('<H', data, offset)[0]
    return (((value & 32767) << 16) | struct.unpack_from('<H', data, offset + 2)[0], offset + 4) if value & 32768 else (value, offset + 2)


def attribute_text(chunk, strings, ns, name):
    attr_start, attr_size, attr_count = struct.unpack_from('<HHH', chunk, 24)
    for i in range(attr_count):
        offset = 16 + attr_start + i * attr_size
        if U32(chunk, offset) == ns and strings[U32(chunk, offset + 4)] == name and U32(chunk, offset + 8) != 0xffffffff:
            return strings[U32(chunk, offset + 8)]
    return None


def relabel(chunk, strings, ns, label):
    """android:label @string reference → raw string: same attribute, same position."""
    attr_start, attr_size, attr_count = struct.unpack_from('<HHH', chunk, 24)
    for i in range(attr_count):
        offset = 16 + attr_start + i * attr_size
        if U32(chunk, offset) == ns and strings[U32(chunk, offset + 4)] == 'label':
            struct.pack_into('<I', chunk, offset + 8, label)
            struct.pack_into('<HBBI', chunk, offset + 12, 8, 0, 3, label)
            return 1
    return 0


def edit(data):
    if struct.unpack_from('<H', data)[0] != 3:
        raise ValueError('Expected compiled Android manifest')
    parts = list(chunks(data))
    pool = next(chunk for kind, chunk in parts if kind == 1)
    header = struct.unpack_from('<H', pool, 2)[0]
    count, styles, flags, start, style_start = struct.unpack_from('<5I', pool, 8)
    utf8 = bool(flags & 0x100)
    strings = []
    for i in range(count):
        pos = start + U32(pool, header + i * 4)
        size, pos = length(pool, pos, utf8)
        if utf8:
            size, pos = length(pool, pos, True)
        strings.append(bytes(pool[pos:pos + size * (1 if utf8 else 2)]).decode('utf-8' if utf8 else 'utf-16le'))
    additions = ['com.ts.car.someip.service', '29.8.1-denza-hud-exp.1', 'debuggable', LABEL]
    if 'package' not in strings:
        additions.append('package')
    new_strings = strings + additions
    extra = bytearray()
    offsets = []
    original_end = style_start or len(pool)
    original_text = pool[start:original_end]
    for text in additions:
        offsets.append(len(original_text) + len(extra))
        encoded = text.encode('utf-8' if utf8 else 'utf-16le')
        if utf8:
            if len(encoded) >= 128 or len(text) >= 128: raise ValueError('String too long')
            extra.extend(bytes([len(text), len(encoded)]) + encoded + b'\0')
        else:
            extra.extend(struct.pack('<H', len(text)) + encoded + b'\0\0')
    text_data = original_text + extra
    text_data += b'\0' * (-len(text_data) % 4)
    new_start = start + len(additions) * 4
    new_style_start = new_start + len(text_data) if style_start else 0
    new_pool = pool[:header] + pool[header:header + count * 4]
    new_pool += b''.join(struct.pack('<I', value) for value in offsets)
    new_pool += pool[header + count * 4:start] + text_data
    if style_start: new_pool += pool[style_start:]
    struct.pack_into('<I', new_pool, 4, len(new_pool))
    struct.pack_into('<5I', new_pool, 8, count + len(additions), styles, flags & ~1, new_start, new_style_start)
    ns = strings.index('http://schemas.android.com/apk/res/android')
    name = strings.index('name')
    package = new_strings.index('package')
    query = new_strings.index(additions[0])
    version = new_strings.index(additions[1])
    debug = new_strings.index('debuggable')
    label = new_strings.index(LABEL)
    resources = next(chunk for kind, chunk in parts if kind == 0x180)
    resource_ids = list(struct.unpack('<' + 'I' * ((len(resources) - 8) // 4), resources[8:]))
    if debug < len(resource_ids): raise ValueError('Unexpected existing debug resource')
    resource_ids.extend([0] * (debug + 1 - len(resource_ids)))
    resource_ids[debug] = 0x0101000f  # android.R.attr.debuggable
    new_resources = struct.pack('<HHI', 0x180, 8, 8 + len(resource_ids) * 4)
    new_resources += struct.pack('<' + 'I' * len(resource_ids), *resource_ids)
    # ResXMLTree_node + ResXMLTree_attrExt + one ResXMLTree_attribute.
    package_start = struct.pack('<HHIII', 0x102, 16, 56, 0, 0xffffffff)
    package_start += struct.pack('<II6H', 0xffffffff, package, 20, 20, 1, 0, 0, 0)
    package_start += struct.pack('<IIIHBBI', ns, name, query, 8, 0, 3, query)
    package_end = struct.pack('<HHIIIII', 0x103, 16, 24, 0, 0xffffffff, 0xffffffff, package)
    output = bytearray(data[:8]); inserted = marked = debugged = labelled = removed = 0
    skipped_depth = 0
    for kind, chunk in parts:
        if skipped_depth:
            if kind == 0x102: skipped_depth += 1
            elif kind == 0x103: skipped_depth -= 1
            continue
        if (kind == 0x102 and strings[U32(chunk, 20)] == 'permission'
                and attribute_text(chunk, strings, ns, 'name') == SHARED_PERMISSION):
            skipped_depth = 1
            removed += 1
            continue
        if kind == 1:
            output.extend(new_pool); continue
        if kind == 0x180:
            output.extend(new_resources); continue
        if kind == 0x102 and strings[U32(chunk, 20)] == 'application':
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', chunk, 24)
            if attr_size != 20: raise ValueError('Unexpected attribute size')
            labelled += relabel(chunk, strings, ns, label)
            # Android attributes are ordered by resource ID, not string-pool position.
            index = next((i for i in range(attr_count)
                          if resource_ids[U32(chunk, 16 + attr_start + i * 20 + 4)] > 0x0101000f), attr_count)
            offset = 16 + attr_start + index * 20
            attribute = struct.pack('<IIIHBBI', ns, debug, 0xffffffff, 8, 0, 0x12, 0xffffffff)
            chunk[offset:offset] = attribute
            struct.pack_into('<I', chunk, 4, len(chunk))
            struct.pack_into('<H', chunk, 28, attr_count + 1)
            for position in (30, 32, 34):
                old = struct.unpack_from('<H', chunk, position)[0]
                if old > index: struct.pack_into('<H', chunk, position, old + 1)
            debugged += 1
        if kind == 0x102 and strings[U32(chunk, 20)] == 'activity' and attribute_text(chunk, strings, ns, 'name') == LAUNCH_TARGET:
            labelled += relabel(chunk, strings, ns, label)
        if kind == 0x102 and strings[U32(chunk, 20)] == 'manifest':
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', chunk, 24)
            for i in range(attr_count):
                offset = 16 + attr_start + i * attr_size
                if strings[U32(chunk, offset + 4)] == 'versionName':
                    struct.pack_into('<I', chunk, offset + 8, version)
                    struct.pack_into('<HBBI', chunk, offset + 12, 8, 0, 3, version)
                    marked += 1
        if kind == 0x103 and strings[U32(chunk, 20)] == 'queries':
            output.extend(package_start + package_end); inserted += 1
        output.extend(chunk)
    if (inserted, marked, debugged, labelled, removed, skipped_depth) != (1, 1, 1, 2, 1, 0):
        raise ValueError(f'Unexpected manifest shape: query={inserted} version={marked} debug={debugged}'
                         f' label={labelled} shared_permission={removed} depth={skipped_depth}')
    struct.pack_into('<I', output, 4, len(output))
    return bytes(output)
