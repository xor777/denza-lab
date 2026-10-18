"""Isolate the pinned Navigator's Android account type without rebuilding resources.

The Passport SDK, authenticator and sync adapter share passport_account_type.
Only its global string-pool value changes. Resource IDs and package chunks stay
byte-identical; build.py independently checks the complete aapt2 resource dump.
"""
import struct

from manifest import U32, chunks, length

ORIGINAL_ACCOUNT_TYPE = 'com.yandex.passport'
ACCOUNT_TYPE = 'com.yandex.passport.denza'


def isolate_account_type(data):
    kind, header, size = struct.unpack_from('<HHI', data)
    if (kind, header, size) != (2, 12, len(data)):
        raise ValueError('Expected compiled resource table')
    parts = list(chunks(data, start=header))
    pools = [chunk for kind, chunk in parts if kind == 1]
    if len(pools) != 1:
        raise ValueError('Expected one global resource string pool')
    pool = pools[0]
    pool_header = struct.unpack_from('<H', pool, 2)[0]
    count, styles, flags, start, style_start = struct.unpack_from('<5I', pool, 8)
    utf8 = bool(flags & 0x100)
    offsets = [U32(pool, pool_header + i * 4) for i in range(count)]
    matches = []
    for index, offset in enumerate(offsets):
        pos = start + offset
        chars, pos = length(pool, pos, utf8)
        size, pos = length(pool, pos, True) if utf8 else (chars * 2, pos)
        # Android pools may contain CESU-8/surrogates in unrelated strings.
        # Match only our ASCII identifier; preserve every other encoded byte.
        text = bytes(pool[pos:pos + size])
        if text == ORIGINAL_ACCOUNT_TYPE.encode('utf-8' if utf8 else 'utf-16le'):
            matches.append((index, start + offset, pos + size + (1 if utf8 else 2)))
    if len(matches) != 1:
        raise ValueError('Expected one original account-type string')
    index, begin, end = matches[0]
    if index < styles and U32(pool, pool_header + count * 4 + index * 4) != 0xffffffff:
        raise ValueError('Account type must not have styled spans')
    encoded = ACCOUNT_TYPE.encode('utf-8' if utf8 else 'utf-16le')
    replacement = (bytes([len(ACCOUNT_TYPE), len(encoded)]) + encoded + b'\0' if utf8
                   else struct.pack('<H', len(ACCOUNT_TYPE)) + encoded + b'\0\0')
    delta = len(replacement) - (end - begin)
    text_end = style_start or len(pool)
    result = pool[:begin] + replacement + pool[end:text_end]
    result += b'\0' * (-len(result) % 4)
    if style_start:
        struct.pack_into('<I', result, 24, len(result))
        result += pool[style_start:]
    for i, offset in enumerate(offsets):
        if begin < start + offset < end:
            raise ValueError('Overlapping resource strings')
        if start + offset >= end:
            struct.pack_into('<I', result, pool_header + i * 4, offset + delta)
    struct.pack_into('<I', result, 4, len(result))
    struct.pack_into('<I', result, 16, flags & ~1)  # Changed text need not remain sorted.
    output = bytearray(data[:header])
    for kind, chunk in parts:
        output.extend(result if kind == 1 else chunk)
    struct.pack_into('<I', output, 4, len(output))
    return bytes(output)
