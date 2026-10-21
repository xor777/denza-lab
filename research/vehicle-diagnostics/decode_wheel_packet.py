"""Decode a local 12-byte 99000059 packet using the retained MCU/GL evidence.

No Android, transport or network access. The producer is firmware evidence;
installed-MCU applicability, freshness and physical units remain unqualified.
See packet-origin-20261021.json for the exact source images and code locations.
"""
import argparse
import json
import struct


WHEEL_ORDER = ('FL', 'FR', 'RL', 'RR')


def float32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


def gl_scaled_candidate(raw):
    """Mirror GLAvmAPI's Java float operations without asserting km/h or mm/s."""
    value = float32(float32(raw) * float32(0.06875))
    value = float32(value * float32(1000.0))
    return float32(value / float32(3.6))


def decode(raw):
    result = dict(schema=1, fid='0x99000059', length=len(raw), rawHex=raw.hex().upper(),
                  structurallyValid=False, errors=[], wheels=[], acceptedByRecoveredContract=False,
                  glStatusGatePasses=None, qualifiedPhysicalUnit=None, freshness='unknown',
                  scope='offline firmware candidate; installed-MCU applicability unverified')
    if len(raw) != 12:
        result['errors'].append('expected exactly 12 bytes')
        return result
    for index, wheel in enumerate(WHEEL_ORDER):
        value = int.from_bytes(raw[index * 2:index * 2 + 2], 'big')
        status = raw[8 + index]
        if value > 0xFFF:
            result['errors'].append(f'{wheel}: unexpected upper four bits in speed word')
        if status > 1:
            result['errors'].append(f'{wheel}: status outside producer bit domain 0..1')
        candidate = None if value > 0xFFF else gl_scaled_candidate(value)
        result['wheels'].append(dict(wheel=wheel, raw16=value,
                                     raw12=value if value <= 0xFFF else None,
                                     statusRaw=status, glStatusAccepted=status == 0,
                                     glScaledCandidate=candidate))
    result['structurallyValid'] = not result['errors']
    result['glStatusGatePasses'] = all(wheel['glStatusAccepted'] for wheel in result['wheels'])
    result['acceptedByRecoveredContract'] = (
        result['structurallyValid'] and result['glStatusGatePasses'])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('hex', help='One local 12-byte payload, as hex')
    args = parser.parse_args()
    result = decode(bytes.fromhex(args.hex))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['structurallyValid'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
