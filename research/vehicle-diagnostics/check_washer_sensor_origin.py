#!/usr/bin/env python3
"""Recheck the retained MCU receiver path for warning 0x1DD, entirely offline.

The default command checks pinned source files and prints a compact result.
--write records JSON evidence in captures/washer-diagnostics-20261021/sensor-origin.
--mcu-assembly uses pypcode4.0.1/v850e3v5 to record bounded instruction ranges.
--hal-assembly uses capstone to record bounded AArch64 ranges.
Firmware bytes are never loaded as host code. No network, ADB, IPC or vehicle I/O.
"""
import argparse
import collections
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'captures/washer-diagnostics-20261021/sensor-origin'
REPORT = ROOT / 'research/vehicle-diagnostics/washer-sensor-origin-20261021.json'
BASE = 0x40000
TP = 0x487E4
FID = 0x26100008
SOURCES = {
    'mcu': ('captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin', '6c277d1eb819b765d997554fc727835314b9477cae414aa806ea7cf78eda4d02'),
    'hal': ('captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/hw/auto.default.so', '5d65fd3b430aec7f2e06496ba427e652613fef995c48c2d4066126dec9f446c7'),
    'catalog': ('captures/ambient-light-20260923/data/fids-canfd.tsv', '2b302b419d4f79e19a12604034cca08d7f9a07d77973a49242bd7e0ccedff885'),
    'framework': ('captures/split-firmware-20260923/jadx/framework/sources/android/hardware/bydauto/instrument/BYDAutoInstrumentDevice.java', '608e55ecb4cb889a5016d182b32a3ee3c0ab633656b3bcb7a0607eb2d6ec604a'),
    'dicar': ('captures/ambient-light-20260923/jadx/DiCarServer/sources/com/byd/feature/instrument/Instrument.java', '56b0b1c8166acae7fb213a239982e4fa243fe3570e789e673515de409873263f'),
    'provider': ('captures/washer-firmware-20260923/diagnostic-apks/CarStatusProvider.apk', '2acb271ff231a9fb91152bb5ed4af0f79eecfb51f8e50016eeb3ed87f19e207c'),
    'registrations': ('captures/diagnostics-origin-20261021/powertrain/hal-registrations.json', '25700ad3c7cb9b489c745eac7082b2d8e80b574e4708b0ea7b2988ba4e5bef4b'),
}
MCU_RANGES = [
    ('bus-fifo-reader', 0x1C5302, 0x1C54F2),
    ('normalize-received-frame', 0x1C54F2, 0x1C55C8),
    ('receive-interface', 0x1A5716, 0x1A5800),
    ('stage-received-frame', 0x1A54A8, 0x1A5578),
    ('enqueue-received-frame', 0x17069C, 0x1707D8),
    ('receive-queue-drain', 0x1707D8, 0x1708DC),
    ('rx-dispatch', 0x1704AC, 0x17055E),
    ('rx-hash-lookup', 0x17055E, 0x170598),
    ('descriptor-loop', 0x17042C, 0x170478),
    ('descriptor-indirect-call', 0x196C22, 0x196C50),
    ('unsigned-extractor-handler', 0x18A09C, 0x18A11C),
    ('unsigned-little-endian-extractor', 0x149E90, 0x149F0C),
    ('compare-and-write-cache', 0x196F94, 0x196FB4),
    ('select-scalar-emitter', 0x196EFA, 0x196F3A),
    ('serialize-scalar', 0x15CB58, 0x15CC32),
    ('online-arrival', 0x176966, 0x1769EC),
    ('online-timeout', 0x1769EC, 0x176A4E),
    ('property-lookup', 0x196EC0, 0x196EFA),
    ('tx-property-lookup', 0x1703C0, 0x1703FA),
]
HAL_RANGES = [
    ('warning-registration', 0x128C74, 0x128CF4),
    ('identity-converter', 0xE0BE0, 0xE0BE8),
    ('wire-integer-input', 0xB0D80, 0xB0E44),
    ('convert-int-cache-callback', 0xB12E8, 0xB13FC),
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def elf_va(data, address, size):
    assert data[:6] == b'\x7fELF\x02\x01'
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    for i in range(count):
        kind, _, off, va, _, length, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + entsize * i)
        if kind == 1 and va <= address and address + size <= va + length:
            return data[off + address - va:off + address - va + size]
    raise ValueError('unmapped ELF address')


def dex_strings(apk):
    data = zipfile.ZipFile(io.BytesIO(apk)).read('classes.dex')
    count, offset = struct.unpack_from('<II', data, 56)
    strings = []
    for i in range(count):
        p = struct.unpack_from('<I', data, offset + i * 4)[0]
        while data[p] & 128:
            p += 1
        p += 1
        strings.append(data[p:data.index(0, p)].decode('utf-8', errors='replace'))
    return data, strings


def main(args):
    data = {}
    sources = []
    for name, (path, expected) in SOURCES.items():
        raw = (ROOT / path).read_bytes()
        assert sha(raw) == expected, 'different source: ' + path
        data[name] = raw
        sources.append(dict(name=name, path=path, sha256=sha(raw), bytes=len(raw)))
    mcu = data['mcu']
    u32 = lambda va: struct.unpack_from('<I', mcu, va - BASE)[0]
    direct_calls = []
    for pc, expected in [(0x1C5494, 0x1C54F2), (0x1C5578, 0x1A5716),
                         (0x1A57A8, 0x1A54A8), (0x1A5516, 0x17069C),
                         (0x17082A, 0x1704AC), (0x1704D0, 0x17055E),
                         (0x17052A, 0x17042C), (0x170452, 0x196C22),
                         (0x18A0E4, 0x149E90), (0x18A0F0, 0x196F94),
                         (0x18A0FC, 0x196EFA), (0x196F22, 0x15CB58)]:
        lo, hi = struct.unpack_from('<HH', mcu, pc-BASE)
        assert lo & 0xFFC0 == 0xFF80, 'not direct JARL-to-LP'
        displacement = ((lo & 63) << 16) | hi
        if displacement & 0x200000:
            displacement -= 0x400000
        assert pc + displacement == expected
        direct_calls.append(dict(pc=hex(pc), target=hex(expected), bytes=mcu[pc-BASE:pc-BASE+4].hex()))

    def lookup(table, size, key):
        bucket = key % size
        steps = []
        for _ in range(size):
            slot = table + bucket * 4
            ptr = u32(slot)
            found = u32(ptr) if ptr else None
            steps.append(dict(bucket=hex(bucket), slot=hex(slot), pointer=hex(ptr), key=hex(found) if found is not None else None))
            if not ptr or found == key:
                return ptr, steps
            bucket = (bucket + 1) % size
        raise ValueError('full lookup table')

    rx, rx_steps = lookup(TP + 0x85500, 0x159E, 0xE0026100)
    assert rx == 0xD637C
    table = struct.unpack_from('<IIII', mcu, rx - BASE)
    assert table == (0xE0026100, 0, 25, 0xDF884)
    prop, prop_steps = lookup(TP + 0xA99A0, 0x6633, FID)
    assert prop == 0x93A5C and prop_steps[-1]['slot'] == '0x108050'
    tx, tx_steps = lookup(TP + 0x73D78, 0x2286, FID)
    assert tx == 0 and len(tx_steps) == 2
    tx_whole_matches = [hex(TP + 0x73D78 + i * 4) for i in range(0x2286)
                        if u32(TP + 0x73D78 + i * 4) and u32(u32(TP + 0x73D78 + i * 4)) == FID]
    assert not tx_whole_matches
    names = collections.defaultdict(set)
    catalog_rows = []
    for line in data['catalog'].decode().splitlines():
        owner, name, value = line.split('\t')
        names[int(value) & 0xFFFFFFFF].add(name)
        catalog_rows.append((owner, name, int(value) & 0xFFFFFFFF))
    fields = []
    for i in range(table[2]):
        va = table[3] + i * 12
        coordinates = list(mcu[va - BASE:va - BASE + 4])
        handler, pointer = struct.unpack_from('<II', mcu, va - BASE + 4)
        fid, size, cache = struct.unpack_from('<III', mcu, pointer - BASE)
        fields.append(dict(va=hex(va), coordinates=coordinates, handler=hex(handler),
                           propertyVA=hex(pointer), fid=hex(fid), scalarBytes=size,
                           cacheRAM=hex(cache), catalogNames=sorted(names[fid])))
    warning = fields[1]
    assert warning['coordinates'] == [1, 0, 2, 1]
    assert warning['handler'] == '0x18a09c' and warning['fid'] == hex(FID)
    assert warning['scalarBytes'] == 2 and warning['cacheRAM'] == '0xfeef5168'
    assert fields[0]['fid'] == '0x26100000' and fields[0]['handler'] == '0x176966'
    assert fields[0]['cacheRAM'] == '0xfeef5164'
    assert data['hal'] and elf_va(data['hal'], 0xE0BE0, 8).hex() == 'e0031f2ac0035fd6'
    registrations = json.loads(data['registrations'])
    warning_regs = [r for r in registrations if r['fid'] == FID]
    assert len(warning_regs) == 1
    assert warning_regs[0] == dict(init='0x128cc8', fid=FID, storage_type=1, converter=0xE0BE0,
                                   device=1007, type=2, registration='0x128cf0', kind='get')

    finite_checks = 0
    for wire in range(65536):
        b0, b1 = wire & 255, wire >> 8
        # Independent per-bit interpretation of the pinned four descriptor bytes.
        bits = sum(((b0 if i < 8 else b1) >> (i % 8) & 1) << i for i in range(10))
        assert bits == wire & 1023
        finite_checks += 1
    # These are synthetic bytes, not observed empty/normal vehicle states.
    assert (0xDD | 0x01 << 8) & 1023 == 0x1DD
    assert (0x3F | 0x01 << 8) & 1023 == 0x13F

    provider_dex, provider_strings = dex_strings(data['provider'])
    direct_pattern = re.compile(r'washer|wash.{0,20}(fluid|liquid|level)|fluid.{0,20}(low|level)|reservoir|玻璃水|洗涤液|清洗液|洗滌液', re.I)
    level_name_pattern = re.compile(r'(WASH|WIPER).*(FLUID|LIQUID|LEVEL|SENSOR|TANK|RESERVOIR)|(FLUID|LIQUID|LEVEL|SENSOR|TANK|RESERVOIR).*(WASH|WIPER)')
    # WIPER_FRONT_WIPER_LEVEL is a speed level, not a reservoir-level definition.
    level_name_matches = [dict(owner=o, name=n, fid=hex(v)) for o,n,v in catalog_rows if level_name_pattern.search(n)]
    wash_names = [dict(owner=o, name=n, fid=hex(v)) for o,n,v in catalog_rows if 'WASH' in n]

    ranges = []
    for binary, selected in [('mcu', MCU_RANGES), ('hal', HAL_RANGES)]:
        for name, start, end in selected:
            raw = mcu[start - BASE:end - BASE] if binary == 'mcu' else elf_va(data['hal'], start, end - start)
            ranges.append(dict(binary=binary, name=name, startVA=hex(start), endExclusiveVA=hex(end), sha256=sha(raw), bytesHex=raw.hex()))
    report = dict(schema='denza-lab.washer-sensor-origin.v1', date='2026-10-21', offlineOnly=True,
        goalCriterion='A reliable factory add-washer-fluid indication is sufficient; a direct level measurement or separate DID is optional.',
        boundaries=dict(vehicleCalls=0, ADBCalls=0, networkCalls=0, firmwareExecuted=False,
                        installedMCUHashMatched=False, sensorOrActuatorManipulations=0),
        sources=sources,
        consumers=dict(android='MCU scalar stream -> auto.default.so -> autoservice integer cache/indicator callback',
            nativeCluster='Native Cells reads its cluster transport directly through spidev_cluster; it is not shown here as a Binder/autoservice consumer.',
            scope='This report traces the MCU ingress and Android branch. The native cluster admission/rendering branch is owned by the separate warning-condition audit.'),
        upstreamIngress=dict(directCallAnchors=direct_calls,
            operations=['1C5302 reads receive-register ID/metadata and payload words through controller base pointers, then calls1C54F2',
                '1C54F2 normalizes ID/FD flags and caps classic payload length8, then calls1A5716',
                '1A5716 copies the received payload into per-channel staging buffers; physical interface0 maps to logical channel0 at1A577E..1A57A8',
                '1A54A8 reads staged ID/length/data and passes them to17069C without selecting an OEM warning ID',
                '17069C copies channel/ID/length/payload/counter into the receive queue; channel0 body at1706DC..170722',
                '1707D8 drains receive queues; channel0 calls1704AC at17082A'],
            receiverRegisterBaseTable=[dict(record=0, controlBase=hex(u32(TP+0x1A5A4C)),
                messageBase=hex(u32(TP+0x1A5A54)), scope='Only table record0 interpreted; number and applicability of additional controller records not established.')],
            conclusion='The traced entry is an ingress/FIFO-to-queue-to-field write path. No reservoir contact is interpreted in these selected functions.'),
        receiver=dict(frameKey='0xe0026100', keyFormula='0xe0000000 | (channel << 24) | (CAN_ID << 8) | sub',
                      rxLookup=rx_steps, frameTableVA=hex(rx), framePreHookVA='0x0', descriptorCount=25,
                      warning=warning, otherFields=fields,
                      operations=['1704AC derives receive key from channel and CAN ID',
                          '17055E resolves the RX record; its pre-descriptor hook is null for this record',
                          '17042C iterates 25 descriptors; 196C22 calls each descriptor handler',
                          '18A09C accepts payload length >=2, calls 149E90 with bytes1..2/bits0..1',
                          '196F94 compares unsigned extracted value with *0xFEEF5168 and stores it if different',
                          '196EFA chooses scalar15CB58 for this FID and size2',
                          '15CB58 emits FID26100008 and two cached value bytes in big endian',
                          'HAL updateParameters reads the scalar; identity converterE0BE0 returns success',
                          'HAL B13C8 stores integer then emits indicator callback with device1007/FID/value'],
                      synthesizes1DD=False, special1DDConditionInSelectedReceiver=False,
                      physicalSenderECU=None,
                      interpretation='The selected MCU receive path transports an already selected warning enum. It does not derive1DD from a separate washer-level input.'),
        propertyLookup=dict(lookupVA='0x196ec0', tableBase='0xf2184', buckets=0x6633, steps=prop_steps,
                            interpretation='The second literal pointer to93A5C is this property hash slot, not another warning producer.'),
        txLookup=dict(lookupVA='0x1703c0', tableBase='0xbc55c', buckets=0x2286, steps=tx_steps,
                      wholeTableMatches=tx_whole_matches,
                      interpretation='The normal property TX table contains no26100008 record. This does not exclude arbitrary raw/diagnostic transmission.'),
        online=dict(property='0x26100000', cache='0xfeef5164', arrivalHandler='0x176966', timeoutHandler='0x1769ec', timerArgument=5000,
                    interpretation='Separate online property: arrival writes1 if appropriate; timer callback writes0. It is not the warning cache FEEF5168. Timer unit is not newly established here.'),
        halRegistrations=warning_regs,
        namedInputSearch=dict(catalogRows=len(catalog_rows), washerNames=wash_names,
            combinedWiperLevelNames=level_name_matches,
            directLevelDefinitionProved=False,
            ambiguity='WASH_ELEC_GATE is module-state selector30. No source definition binds it to washer fluid or popup1DD; WIPER_FRONT_WIPER_LEVEL names wiper speed.'),
        provider=dict(dexSHA256=sha(provider_dex), dexStrings=len(provider_strings),
                      directWasherMatches=[s for s in provider_strings if direct_pattern.search(s)],
                      scope='Exact strings of the7248byte retained CarStatusProvider DEX only; not a proof that all providers or ECUs lack a level input.'),
        sourceByteRanges=ranges, finiteDescriptorChecks=finite_checks,
        missingEvidence=['Required to qualify the add-fluid indication: actual low-fluid delivery of1DD on the applicable car is not established. The path and OEM meaning are statically established.',
                         'For further offline origin research: software-applicable CAN261 producer firmware or source definition that selects1DD, including equipment/configuration gates',
                         'Physical origin remains unbound: CAN ID/channel and actuator747 do not establish the sending ECU or reservoir-input owner',
                         'Optional, not a prerequisite for the requested add-fluid indicator: direct sensor measurement, DID, wiring pin and circuit-DTC definition'],
        limits=['The owner reports a sensor fitted on both N9 and Z9GT. This work does not challenge that premise or infer sensor absence from the API catalog.',
                'This is a bounded call/table/write-path audit, not an exhaustive proof over every instruction or arbitrary RAM writer.',
                'No claim that0 is fluid-OK, that13F masks1DD, or that WASH_ELEC_GATE is the reservoir switch.',
                'No level DID, applicable circuit DTC, sensor pin, wiring owner or upstream suppression config proved.'])

    if args.mcu_assembly:
        from importlib.metadata import version
        from pypcode import Context
        assert version('pypcode') == '4.0.1'
        ctx = Context('V850:LE:32:v850e3v5')
        OUT.mkdir(parents=True, exist_ok=True)
        for name, a, z in MCU_RANGES:
            instructions = ctx.disassemble(mcu[a-BASE:z-BASE], base_address=a).instructions
            assert instructions[0].addr.offset == a
            assert instructions[-1].addr.offset + instructions[-1].length == z
            text = '\n'.join(f'{i.addr.offset:#010x}: {mcu[i.addr.offset-BASE:i.addr.offset-BASE+i.length].hex():<12} {i.mnem:<12} {i.body}' for i in instructions) + '\n'
            (OUT / f'mcu-{a:x}-{name}.asm').write_text(text)
    if args.hal_assembly:
        from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
        ctx = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
        OUT.mkdir(parents=True, exist_ok=True)
        for name, a, z in HAL_RANGES:
            instructions = list(ctx.disasm(elf_va(data['hal'], a, z-a), a))
            assert instructions[-1].address + instructions[-1].size == z
            text = '\n'.join(f'{i.address:#010x}: {i.bytes.hex():<12} {i.mnemonic:<9} {i.op_str}' for i in instructions) + '\n'
            (OUT / f'hal-{a:x}-{name}.asm').write_text(text)
    if args.write:
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / 'origin-evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        (OUT / 'provider-dex-strings.json').write_text(json.dumps(provider_strings, ensure_ascii=False, indent=2) + '\n')
    else:
        assert json.loads(REPORT.read_text()) == report, 'saved origin report differs from source reconstruction'
    return dict(offlineOnly=True, vehicleCalls=0, pinnedSources=len(sources), RXDescriptors=25,
                warningProperty=warning, RXLookupSteps=len(rx_steps), propertyLookupSteps=len(prop_steps),
                TXLookupSteps=len(tx_steps), TXPropertyMatches=len(tx_whole_matches),
                directCallAnchors=len(direct_calls), finiteDescriptorChecks=finite_checks, separateNamedLevelInputProved=False,
                physicalSenderECU=None, providerDirectWasherMatches=report['provider']['directWasherMatches'],
                report=str((OUT / 'origin-evidence.json').relative_to(ROOT)) if args.write else None)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    parser.add_argument('--mcu-assembly', action='store_true')
    parser.add_argument('--hal-assembly', action='store_true')
    print(json.dumps(main(parser.parse_args()), ensure_ascii=False, indent=2))
