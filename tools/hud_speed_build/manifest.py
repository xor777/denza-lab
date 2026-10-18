"""Minimal binary XML edit for the SHA-pinned HUD Speed 79.0 APK.
Only version, application label and visibility of the Denza Apps host change.
"""
import struct
from identity import LABEL, VERSION_CODE, VERSION_NAME
U32 = lambda data, offset: struct.unpack_from('<I', data, offset)[0]
HOST_PACKAGE = 'dev.denza.apps'

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
    # Keep every upstream string index intact; append the three new strings.
    additions = [VERSION_NAME, LABEL, HOST_PACKAGE]
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
    version = new_strings.index(VERSION_NAME)
    label = new_strings.index(LABEL)
    def package_query(value):
        """<package android:name=value/>: ResXMLTree_node + ResXMLTree_attrExt + one ResXMLTree_attribute, then its end."""
        query = new_strings.index(value)
        start_chunk = struct.pack('<HHIII', 0x102, 16, 56, 0, 0xffffffff)
        start_chunk += struct.pack('<II6H', 0xffffffff, package, 20, 20, 1, 0, 0, 0)
        start_chunk += struct.pack('<IIIHBBI', ns, name, query, 8, 0, 3, query)
        end_chunk = struct.pack('<HHIIIII', 0x103, 16, 24, 0, 0xffffffff, 0xffffffff, package)
        return start_chunk + end_chunk
    queries = b''.join(package_query(value) for value in (HOST_PACKAGE,))
    output = bytearray(data[:8]); inserted = marked = numbered = labelled = 0
    for kind, chunk in parts:
        if kind == 1:
            output.extend(new_pool); continue
        if kind == 0x102 and strings[U32(chunk, 20)] == 'application':
            labelled += relabel(chunk, strings, ns, label)
        if kind == 0x102 and strings[U32(chunk, 20)] == 'manifest':
            attr_start, attr_size, attr_count = struct.unpack_from('<HHH', chunk, 24)
            for i in range(attr_count):
                offset = 16 + attr_start + i * attr_size
                if strings[U32(chunk, offset + 4)] == 'versionName':
                    struct.pack_into('<I', chunk, offset + 8, version)
                    struct.pack_into('<HBBI', chunk, offset + 12, 8, 0, 3, version)
                    marked += 1
                elif strings[U32(chunk, offset + 4)] == 'versionCode':
                    struct.pack_into('<I', chunk, offset + 8, 0xffffffff)
                    struct.pack_into('<HBBI', chunk, offset + 12, 8, 0, 0x10, VERSION_CODE)
                    numbered += 1
        if kind == 0x103 and strings[U32(chunk, 20)] == 'queries':
            output.extend(queries); inserted += 1
        output.extend(chunk)
    if (inserted, marked, numbered, labelled) != (1, 1, 1, 1):
        raise ValueError('Unexpected HUD Speed manifest shape')
    struct.pack_into('<I', output, 4, len(output))
    return bytes(output)
