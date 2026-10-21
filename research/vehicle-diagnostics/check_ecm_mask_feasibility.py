#!/usr/bin/env python3
"""Verify stock mask construction and an offline size/coverage counterexample.

No vehicle calls or runnable probe plan. The counterexample is set arithmetic,
not a reconstruction of ECM statuses or an execution of firmware.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from check_ecm_request_analysis import read_va

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BASE = ROOT / 'captures/ecm-mask-feasibility-20261021'
LIB = ROOT / 'captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/libuds.so'
OUT = HERE / 'ecm-mask-feasibility-20261021.json'
EXTENTS = [(0x9F0E0, 628, 'constructor'), (0x9EECC, 532, 'count-wrapper'),
           (0x9F354, 532, 'list-wrapper'), (0xADEE8, 2980, 'list-client'),
           (0xAD49C, 2636, 'count-client')]


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def source(path):
    raw = path.read_bytes()
    return dict(path=str(path.relative_to(ROOT)), bytes=len(raw), sha256=digest(raw))


def relocation_target(elf, got):
    """Resolve an ELF64 RELA symbol, not the initially unbound GOT contents."""
    shoff = struct.unpack_from('<Q', elf, 0x28)[0]
    step, count = struct.unpack_from('<HH', elf, 0x3A)
    sections = [struct.unpack_from('<IIQQQQIIQQ', elf, shoff+i*step) for i in range(count)]
    targets = []
    for section in sections:
        if section[1] != 4:
            continue
        symbols = sections[section[6]]
        for pos in range(section[4], section[4]+section[5], section[9]):
            offset, info, _ = struct.unpack_from('<QQq', elf, pos)
            if offset == got:
                assert info & 0xFFFFFFFF == 1026, 'AArch64 JUMP_SLOT'
                symbol_pos = symbols[4]+(info >> 32)*symbols[9]
                targets.append(struct.unpack_from('<IBBHQQ', elf, symbol_pos)[4])
    assert len(targets) == 1
    return targets[0]


def derive():
    elf = LIB.read_bytes()
    assert digest(elf) == '86416b6e127dffac1102ef8dde16cd297a96620d78c6dd4b5fc4d87e1171d161'
    paths = [LIB, Path(__file__), HERE / 'check_ecm_request_analysis.py',
             HERE / 'ecm-request-analysis-20261021.json']
    functions = []
    for va, size, name in EXTENTS:
        raw = read_va(elf, va, size)
        assert raw == elf[va:va+size], 'this ELF VA/file-offset equality must be checked'
        assembly_path = BASE / (name + '-instructions.json')
        rows = json.loads(assembly_path.read_text())
        assert [int(r['va'], 16) for r in rows] == list(range(va, va+size, 4))
        assert b''.join(bytes.fromhex(r['bytes']) for r in rows) == raw
        paths.append(assembly_path)
        functions.append(dict(name=name, sourceVA=hex(va), fileOffset=hex(va),
            size=size, sha256=digest(raw), instructions=str(assembly_path.relative_to(ROOT))))
    # Reviewed instruction anchors: fixed subfunctions and three byte-appends.
    anchors = [(0x9F074, '23008052'), (0x9F4FC, '43008052'),
               (0x9F2B0, '29038052'), (0x9F2C8, '00013fd6'),
               (0x9F2DC, '00013fd6'), (0x9F2F0, '00013fd6')]
    for va, hx in anchors:
        assert read_va(elf, va, len(bytes.fromhex(hx))) == bytes.fromhex(hx)
    # The ELF relocation symbol, not the unresolved GOT value, binds each PLT call.
    for got, target in [(0x104B50, 0x9EECC), (0x104B68, 0x9F354)]:
        assert relocation_target(elf, got) == target
    rea = []
    for filename, va in [('rea-mask-constructor.json', 0x9F0E0),
                         ('rea-status-client.json', 0xADEE8), ('rea-list-wrapper.json', 0x9F354)]:
        path = BASE / filename
        value = json.loads(path.read_text())
        assert value['subject']['digest']['sha256'] == digest(elf)
        selection = value['raw_result']['selection']
        assert selection['file_offset'] == va and int(selection['mapped_address'], 16) == va+0x100000
        assert value['normalized_result']
        paths.append(path)
        rea.append(dict(path=str(path.relative_to(ROOT)), sourceVA=hex(va),
            analysisVA=selection['mapped_address'], limitations=value['limitations']))
    generic = json.loads((BASE/'rea-mask-constructor.json').read_text())['normalized_result']
    wrapper = json.loads((BASE/'rea-list-wrapper.json').read_text())['normalized_result']
    assert 'local_168 = 0x19;' in generic and '(param_1,param_2,param_3,param_4,2,param_5)' in wrapper
    captures = [ROOT/'captures/diagnostics-obd-20261020/probe.txt',
                ROOT/'captures/diagnostics-obd-20261020/run2/probe.txt']
    first, second = [p.read_text() for p in captures]
    assert 'REQUEST step=1 0A07E0031901FF' in first and 'raw=0A07E8060101015901FF000102' in first
    for text in ['REQUEST step=38 0A07E003190108', 'raw=0A07E8060101015901FF000000',
                 'REQUEST step=39 0A07E003190104', 'STEP_END step=39 req=7e0 why=TIMEOUT_4S',
                 'REQUEST step=40 0A07E003190208', 'raw=0A07E8030101015902FF',
                 'REQUEST step=41 0A07E003190204', 'STEP_END step=41 req=7e0 why=TIMEOUT_4S']:
        assert text in second
    paths += captures
    # A possible status distribution demonstrates why splitting bits need not fit.
    # It is deliberately not labelled as the measured distribution in the vehicle.
    model_statuses = [0x50]*258
    masks = [1 << bit for bit in range(8)]
    slices = [dict(maskHex=f'{mask:02X}', count=sum(bool(status & mask) for status in model_statuses)) for mask in masks]
    assert [r['count'] for r in slices] == [0, 0, 0, 0, 258, 0, 258, 0]
    fits = [n for n in range(65536) if 3+4*n <= 920]
    assert max(fits) == 229 and 3+4*258 == 1035
    return dict(schema='denza-lab.ecm-mask-feasibility.v1', date='2026-10-21',
        vehicleOperations=0, firmwareExecutions=0, runnableProbeAdded=False,
        state='stock_mask_route_confirmed_not_a_general_registry_pagination',
        identity=dict(route='7E0', softwareCode='32440011E004000000', calibrationId='Z31L07AB01'),
        native=dict(librarySha256=digest(elf), functions=functions, rea=rea,
            addressMapping='Original ELF .text VA equals file offset for these ranges; REA maps each at +0x100000. Byte slices verified independently.',
            construction='Count wrapper fixes subfunction01; list wrapper fixes02. Both pass the caller byte mask to the generic helper, which appends19,subfunction,mask. There is no offset or page argument in this path.',
            scope='Stock library construction; neither ECM firmware filtering behavior nor another controller-specific pagination service is established.'),
        retainedObservations=dict(maskFFCount=258, mask08Count=0, mask08ListHex='5902FF',
            mask04CountOutcome='no Android response within4s', mask04ListOutcome='no Android response within4s',
            boundary='Different retained requests/times; timeout does not establish unsupported service or no CAN response.'),
        sizeModel=dict(udsBytesFormula='3 + 4 * record_count', qualifiedSourceMcuMaxUdsBytes=920,
            maxFourByteRecords=229, bytesAt229=919, bytesAt230=923, bytesAt258=1035,
            boundary='Original OTA MCU size gate; installed MCU hash and first ECM CAN frame still missing. Not proof of NRC13 origin.'),
        counterexample=dict(synthetic=True, statusByteHex='50', recordCount=258, singleBitQueries=slices,
            result='Two nonempty slices each still contain258 records. No collection of status masks makes them smaller.',
            zeroStatusBoundary='Under status-mask intersection semantics a supported code with status00 appears in no mask slice. A union of memory slices cannot prove the complete19 0A registry.'),
        nextEvidence=dict(conditionalOnly=True,
            memoryReading='A future exact-identity count-first investigation could read an individually supported mask only if the fresh count fits and response validation succeeds; retain each time/status separately. No such pass is implemented or run here.',
            doNotRepeat='Do not repeat mask04 timeouts or large FF/0A requests without a new hypothesis. A count before a list is not an atomic size guarantee.',
            registry='Need source-qualified controller-specific registry selection or first7E8 CAN reply with matching installed MCU identity; owner03/readback dead ends remain closed.'),
        qualifiedFactoryDefinitions=[], sources=[source(p) for p in paths])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    value = derive()
    if args.write:
        OUT.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')
    else:
        assert json.loads(OUT.read_text()) == value
    print('PASS native_functions=5 REA_mappings=3 size_cases=65536 max_records=229 mask_pagination_guarantee=false vehicle_operations=0')


if __name__ == '__main__':
    main()
