#!/usr/bin/env python3
"""Offline REA result, ELF mapping and original-instruction corroboration."""
import gzip
import hashlib
import json
from pathlib import Path
import struct

from check_ota_core_fields import ROOT, references

ARTIFACT = Path(__file__).with_name('ota-rea-doip-20261021.json')


def read(ref):
    path = ROOT/ref['path']; data = path.read_bytes()
    return json.loads(gzip.decompress(data) if path.suffix == '.gz' else data)


def elf_bytes(image, va, length):
    assert image[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', image, 18)[0] == 183
    phoff = struct.unpack_from('<Q', image, 32)[0]
    size, count = struct.unpack_from('<HH', image, 54)
    for i in range(count):
        kind, _, offset, start, _, filesz, _, _ = struct.unpack_from('<IIQQQQQQ', image, phoff+i*size)
        if kind == 1 and start <= va and va+length <= start+filesz:
            return image[offset+va-start:offset+va-start+length]
    raise AssertionError('Unmapped ELF bytes')


def main():
    evidence = json.loads(ARTIFACT.read_text()); count = len(list(references(evidence)))
    artifacts = evidence['artifacts']; raw = (ROOT/evidence['sources']['binary']['path']).read_bytes()
    assert read(artifacts['doctor'])['healthy'] is True
    loaded = read(artifacts['loadImage'])['normalized_result']
    assert loaded['status'] == 'unsupported'  # Do not turn an ELF observation into attestation.
    observed = loaded['observations']
    assert observed['image_base'] == '0x100000' and observed['language_id'] == 'AARCH64:LE:64:v8A'
    text = next(m for m in observed['mappings'] if m['block'] == '.text')
    assert hashlib.sha256(raw[text['file_offset']:text['file_offset']+text['length']]).hexdigest() == text['sha256']
    proof = read(artifacts['addressProof'])
    assert int(proof['textOriginalVa'],16)+int(proof['analysisBias'],16) == int(text['start'],16)
    assert elf_bytes(raw,int(proof['textOriginalVa'],16),text['length']) == raw[text['file_offset']:text['file_offset']+text['length']]
    assert proof['functionOriginalVa'] == '0xac9c0' and proof['functionAnalysisVa'] == '0x1ac9c0'
    result = read(artifacts['decompile']); code = result['normalized_result']
    symbol = '_ZN3com2ts3car4doip4core11CDoIPClient26connectToServerByIPAddressERKNSt3__112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERbm'
    symbols = (ROOT/evidence['sources']['existingSymbols']['path']).read_text().splitlines()
    assert any(line.startswith('0xac9c0\t10932\t') and symbol in line for line in symbols)
    assert result['parameters'] == {'procedure':'0x1ac9c0'} and symbol in code[:500]
    assert 'if (param_4 == 0)' in code and '0x3458' in code
    assert 'aysnc_connect' in code and '(&local_110,&local_98,param_4)' in code
    assert 'getTlsStream' in code and 'handshake' in code and 'CDoIPProtocolHandler5startEv' in code
    ranges = read(artifacts['byteCheck']); instructions = {}
    assert len(ranges) == 6 and sum(r['bytes'] for r in ranges) == 92
    for region in ranges:
        va = int(region['va'],16); data = elf_bytes(raw, va, region['bytes'])
        assert hashlib.sha256(data).hexdigest() == region['sha256']
        pos = va
        for row in region['instructions']:
            assert int(row['va'],16) == pos and bytes.fromhex(row['hex']) == elf_bytes(raw,pos,4)
            instructions[pos] = row; pos += 4
        assert pos == va+region['bytes']
    assert instructions[0xae03c]['hex'] == '028b8652'  # MOV W2,#13400.
    assert instructions[0xadf04]['hex'] == 'e83340f9'  # Reload saved fourth argument.
    for va, target in [(0xae04c,0xc0758),(0xae0e0,0xb0084),(0xae21c,0x104310),(0xae6d0,0x104360)]:
        word = struct.unpack('<I',elf_bytes(raw,va,4))[0]
        assert word >> 26 == 0x25
        immediate = word & 0x3ffffff
        if immediate & 0x2000000: immediate -= 0x4000000
        assert va + immediate*4 == target
    cleanup = read(artifacts['cleanup'])
    assert cleanup['inputSha256Unchanged'] and cleanup['ownedTemporaryWorkspaceAbsent'] and cleanup['sharedLauncherPresent']
    assert not cleanup['targetFirmwareExecuted'] and not cleanup['vehicleOrNetworkAccess']
    print(json.dumps({'passed':True,'referencesVerified':count,'mappedFunction':'0x1ac9c0',
                      'originalBytesCorroborated':92,'elfAttestation':'unsupported',
                      'vehicleOrNetworkAccess':False}))


if __name__ == '__main__':
    main()
