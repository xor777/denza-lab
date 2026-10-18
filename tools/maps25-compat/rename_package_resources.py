"""Version-specific Maps package rename in binary XML/resource string pools.

Indices and styles are retained. Use after RenamePackage.java; align and sign last.
"""
import struct
import sys
import zipfile

OLD = 'app.morphe.android.apps.maps'
NEW = 'app.revanced.android.apps.maps'

def u32(data, offset):
    return struct.unpack_from('<I', data, offset)[0]

def patch32(data, offset, value):
    struct.pack_into('<I', data, offset, value)

def length(data, offset, utf8):
    if utf8:
        value = data[offset]
        return (((value & 127) << 8) | data[offset + 1], 2) if value & 128 else (value, 1)
    value = struct.unpack_from('<H', data, offset)[0]
    return (((value & 32767) << 16) | struct.unpack_from('<H', data, offset + 2)[0], 4) if value & 32768 else (value, 2)

def encode_length(value, utf8):
    if utf8:
        assert value < 32768
        return bytes([(value >> 8) | 128, value & 255]) if value >= 128 else bytes([value])
    return struct.pack('<HH', (value >> 16) | 32768, value & 65535) if value >= 32768 else struct.pack('<H', value)

def pool(data):
    count, styles, flags, start, style_start = struct.unpack_from('<IIIII', data, 8)
    utf8 = bool(flags & 256)
    encoded = bytearray()
    offsets = []
    changed = 0
    for i in range(count):
        offset = start + u32(data, 28 + 4 * i)
        units, skip = length(data, offset, utf8)
        offset += skip
        size = units * 2
        if utf8:
            size, skip = length(data, offset, True)
            offset += skip
        # Preserve Android's encoded surrogate pairs without a Unicode round trip.
        value_bytes = data[offset:offset + size]
        encoding = 'utf-8' if utf8 else 'utf-16-le'
        hits = value_bytes.count(OLD.encode(encoding))
        if hits:
            value_bytes = value_bytes.replace(OLD.encode(encoding), NEW.encode(encoding))
            units += hits * (len(NEW) - len(OLD))
            changed += hits
        offsets.append(len(encoded))
        encoded += encode_length(units, utf8)
        if utf8:
            encoded += encode_length(len(value_bytes), True)
        encoded += value_bytes + (b'\0' if utf8 else b'\0\0')
    if not changed:
        return data
    while len(encoded) % 4:
        encoded.append(0)
    result = bytearray(data[:start]) + encoded
    if style_start:
        result += data[style_start:]
        patch32(result, 24, start + len(encoded))
    patch32(result, 4, len(result))
    patch32(result, 16, flags & ~1)
    for i, offset in enumerate(offsets):
        patch32(result, 28 + 4 * i, offset)
    return bytes(result)

def chunks(data):
    kind, header, size = struct.unpack_from('<HHI', data)
    assert size == len(data)
    if kind == 1:
        return pool(data)
    # XML, resource table, and resource-package containers; other chunks are opaque.
    if kind not in (2, 3, 0x200):
        return data
    result = bytearray(data[:header])
    offset = header
    package_offsets = {x: u32(data, x) for x in (268, 276)} if kind == 0x200 else {}
    while offset < size:
        for field, old in package_offsets.items():
            if old == offset:
                patch32(result, field, len(result))
        child_size = u32(data, offset + 4)
        assert child_size >= 8 and offset + child_size <= size
        result += chunks(data[offset:offset + child_size])
        offset += child_size
    patch32(result, 4, len(result))
    return bytes(result)

if __name__ == '__main__':
    with zipfile.ZipFile(sys.argv[1]) as source, zipfile.ZipFile(sys.argv[2], 'w') as target:
        for entry in source.infolist():
            if entry.filename.startswith('META-INF/'):
                continue
            data = source.read(entry)
            if entry.filename == 'resources.arsc' or entry.filename.endswith('.xml'):
                if OLD.encode() in data or OLD.encode('utf-16-le') in data:
                    data = chunks(data)
                    print('renamed resource strings in', entry.filename)
            target.writestr(entry, data)
