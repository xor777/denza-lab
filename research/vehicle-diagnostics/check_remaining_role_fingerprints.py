#!/usr/bin/env python3
"""Reproduce the finite native/container fingerprint audit, entirely offline.

Run with .venv-firmware/bin/python; uses pyelftools and Capstone already in that
environment. Does not execute firmware, extract new files, or contact a service.
"""

import collections
import csv
import hashlib
import io
import json
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zlib

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile


ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / "research/vehicle-diagnostics/remaining-role-fingerprints-20261021.json"
DIDS = ["F394", "F494", "F644", "F654", "FB24", "C294", "C394", "C494", "C594"]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_text())


def occurrences(data, needle):
    found, pos = [], 0
    while True:
        pos = data.find(needle, pos)
        if pos < 0:
            return found
        found.append(pos)
        pos += 1


def elf_at(elf, address, size):
    for section in elf.iter_sections():
        if (section["sh_type"] != "SHT_NOBITS" and section["sh_addr"] <= address
                and address + size <= section["sh_addr"] + section["sh_size"]):
            offset = address - section["sh_addr"]
            return section.data()[offset:offset + size]
    raise AssertionError(f"Unmapped ELF range {address:x}+{size:x}")


def symbol(elf, name):
    for table_name in (".symtab", ".dynsym"):
        table = elf.get_section_by_name(table_name)
        if table:
            for item in table.iter_symbols():
                if item.name == name:
                    return item
    raise AssertionError(f"Missing ELF symbol {name}")


def cstring(elf, address):
    return elf_at(elf, address, 256).split(b"\0", 1)[0].decode("utf-8")


def png_containers(section):
    data, base, start = section.data(), section["sh_addr"], 0
    images = []
    while True:
        start = data.find(b"\x89PNG\r\n\x1a\n", start)
        if start < 0:
            return images
        cursor, chunks = start + 8, []
        while True:
            length = struct.unpack_from(">I", data, cursor)[0]
            kind = data[cursor + 4:cursor + 8]
            assert cursor + length + 12 <= len(data)
            crc = struct.unpack_from(">I", data, cursor + 8 + length)[0]
            assert (zlib.crc32(data[cursor + 4:cursor + 8 + length]) & 0xFFFFFFFF) == crc
            chunks.append({"type": kind.decode("ascii"), "address": hex(base + cursor),
                           "dataAddress": hex(base + cursor + 8), "dataBytes": length,
                           "crc32Verified": True})
            cursor += length + 12
            if kind == b"IEND":
                break
        images.append({"address": hex(base + start), "endExclusive": hex(base + cursor),
                       "bytes": cursor - start, "sha256": digest(data[start:cursor]),
                       "chunks": chunks})
        start = cursor


def collect(inputs):
    paths = {key: ROOT / value["path"] for key, value in inputs.items()}
    previous = read_json(paths["previousRoles"])
    codes = [target["cachedSoftwareCode"] for target in previous["targets"]]
    assert len(codes) == len(set(codes)) == 9
    fingerprints = [{"kind": "targetChildCode", "value": code} for code in codes]
    fingerprints += [{"kind": "espCode", "value": "32330011DF0B000101"},
                     {"kind": "positiveControl", "value": "4B450011E0930034E2"},
                     {"kind": "positiveControl", "value": "4B450011DF990042E2"},
                     {"kind": "brakeCalidText", "value": "4000825042253153"},
                     {"kind": "brakeNameText", "value": "BSCM-BrakeSystem"}]
    scan = []
    for key, path in paths.items():
        if not inputs[key].get("rawBinaryScan"):
            continue
        data, hits = path.read_bytes(), []
        for fp in fingerprints:
            value = fp["value"]
            forms = {"ascii": value.encode(), "utf16le": value.encode("utf-16le"),
                     "utf16be": value.encode("utf-16be")}
            if fp["kind"] in ("targetChildCode", "espCode", "positiveControl"):
                forms.update({"asciiLowerHex": value.lower().encode(),
                              "rawNineBytes": bytes.fromhex(value)})
            for encoding, needle in forms.items():
                offsets = occurrences(data, needle)
                if offsets:
                    hits.append({**fp, "encoding": encoding,
                                 "fileOffsets": [hex(x) for x in offsets]})
        scan.append({"inputKey": key, "bytes": len(data), "hits": hits})
    assert len(scan) == 13
    assert all(hit["kind"] == "positiveControl" for row in scan for hit in row["hits"])
    assert sum(len(row["hits"]) for row in scan) == 2

    containers = []
    for key in ("iviMcuContainer", "fseMcuContainer"):
        tree = ET.fromstring(paths[key].read_bytes())
        config = tree.find("Configure")
        chosen = {name: config.findtext(name) for name in
                  ("EcuVersion", "EcuSoftwareCode", "CanFuncID", "CanReqID", "CanRecID", "CanChannel")}
        assert chosen["EcuSoftwareCode"] not in codes
        containers.append({"inputKey": key, "selectedMetadata": chosen,
                           "roleScope": "Existing host MCU package; not an INS/FRL/CDC-ZCU child image"})
    assert containers[0]["selectedMetadata"]["CanReqID"] == "1857"
    assert containers[1]["selectedMetadata"]["CanReqID"] == "1511"

    cells = ELFFile(io.BytesIO(paths["cells"].read_bytes()))
    client = ELFFile(io.BytesIO(paths["client"].read_bytes()))
    images = png_containers(cells.get_section_by_name(".rodata"))
    assert len(images) == 2 and [i["bytes"] for i in images] == [37517, 73901]
    doc_symbol = symbol(client, "_ZL20_doc_data_compressed")
    doc_address, doc_size = doc_symbol["st_value"], doc_symbol["st_size"]
    compressed = elf_at(client, doc_address, doc_size)
    decompressor = zlib.decompressobj()
    expanded = decompressor.decompress(compressed, 1024 * 1024)
    assert decompressor.eof and not decompressor.unused_data and not decompressor.unconsumed_tail
    manifest = read_json(paths["embeddedApiManifest"])
    assert digest(expanded) == manifest["expanded_sha256"]
    assert expanded == paths["embeddedApiXml"].read_bytes()
    assert len(expanded) == 392330 and doc_size == 59919

    data_array = symbol(cells, "g_DataSourceArray")
    record_address = data_array["st_value"] + 1133 * 48
    record = elf_at(cells, record_address, 48)
    name_address = struct.unpack_from("<Q", record, 8)[0]
    assert name_address == 0x5F654 and cstring(cells, name_address) == "target1Direction"
    layout = next(item for item in read_json(paths["checkedLayouts"])
                  if item["name"] == "data_source_item_type_")
    member = next(m for m in layout["members"] if m["name"] == "m_pDataItemName")
    assert member["location"] == 8 and member["type"]["tag"] == "DW_TAG_pointer_type"
    did_hits, word32_hits = [], []
    for key in ("iviHal", "libuds", "udsDaemon", "cells", "client"):
        elf = ELFFile(io.BytesIO(paths[key].read_bytes()))
        for section in elf.iter_sections():
            if section.name not in (".rodata", ".data", ".data.rel.ro"):
                continue
            data, base = section.data(), section["sh_addr"]
            for did in DIDS:
                for offset in occurrences(data, int(did, 16).to_bytes(4, "little")):
                    word32_hits.append({"inputKey": key, "did": did, "address": hex(base + offset)})
                for offset in occurrences(data, int(did, 16).to_bytes(2, "little")):
                    address, owner = base + offset, None
                    if key == "cells":
                        for image in images:
                            for chunk in image["chunks"]:
                                if (chunk["type"] == "IDAT" and int(chunk["dataAddress"], 16) <= address
                                        and address + 2 <= int(chunk["dataAddress"], 16) + chunk["dataBytes"]):
                                    owner = {"kind": "pngCompressedPixelData", "imageAddress": image["address"]}
                        if address == record_address + 8:
                            owner = {"kind": "namePointerLowBytes", "recordIndex": 1133,
                                     "field": "m_pDataItemName", "pointer": hex(name_address),
                                     "pointedText": cstring(cells, name_address)}
                    if key == "client" and doc_address <= address and address + 2 <= doc_address + doc_size:
                        owner = {"kind": "compressedGodotApiXml", "symbol": doc_symbol.name}
                    assert owner is not None, f"Unclassified candidate {key} {address:x} {did}"
                    did_hits.append({"inputKey": key, "section": section.name, "didBytePattern": did,
                                     "address": hex(address), "fileOffset": hex(section["sh_offset"] + offset),
                                     "owner": owner})
    assert len(did_hits) == 23 and not word32_hits

    hal = ELFFile(io.BytesIO(paths["iviHal"].read_bytes()))
    version_symbol = symbol(hal, "_ZN12byd_auto_hal13FunctionTable15versionPropertyERKjPiPc")
    start, size = version_symbol["st_value"], version_symbol["st_size"]
    assert start == 0x655F0 and size == 1376
    body = elf_at(hal, start, size)
    decoder = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    instructions = [{"address": hex(ins.address), "bytes": ins.bytes.hex(),
                     "mnemonic": ins.mnemonic, "operands": ins.op_str}
                    for ins in decoder.disasm(body, start)]
    property_literals = {"mcu_boot_version": 0x3D103, "mcu_version": 0x3F0C1,
                         "dsp_version": 0x3F0CD, "dyna_version": 0x3BDA6,
                         "anc_version": 0x3DC14}
    assert all(cstring(hal, address) == name for name, address in property_literals.items())
    plt, relocations = hal.get_section_by_name(".plt"), hal.get_section_by_name(".rela.plt")
    plt_index = (0x1CFAF0 - plt["sh_addr"] - 32) // 16
    relocation = list(relocations.iter_relocations())[plt_index]
    assert hal.get_section_by_name(".dynsym").get_symbol(relocation["r_info_sym"]).name == "property_set"

    factory, cache = read_json(paths["factory"])["ecus"], read_json(paths["cache"])["ecuInfo"]
    brakes = []
    for index in (191, 192, 193):
        f, c = factory[index], cache[index]
        assert f["ecuId"] == c["ecuId"] and f["ecuDiaId"] == "782/78A"
        assert f["ecuDId"] == "F195/F194" and c["ecuSoftCode"] == "32330011DF0B000101"
        brakes.append({"index": index, "ecuId": f["ecuId"], "route": f["ecuDiaId"],
                       "softwareDids": f["ecuDId"], "cachedSoftwareCode": c["ecuSoftCode"]})
    assert not any(f["route"].get("requestId") == 0x7E5 for f in factory)
    live = read_json(paths["brakeCompletion"])
    brake_responses = [row for row in live["wireResponses"]
                       if row["targetHex"] == "7E5" and row["requestHex"] in ("0904", "090A")]
    assert len(brake_responses) == 2
    calid = bytes.fromhex(next(r["responseHex"] for r in brake_responses if r["requestHex"] == "0904"))[3:].decode()
    name = bytes.fromhex(next(r["responseHex"] for r in brake_responses if r["requestHex"] == "090A"))[3:].rstrip(b"\0").decode()
    assert calid == "4000825042253153" and name == "BSCM-BrakeSystem"
    assert "IDENTITY req=782 F194=32330011DF0B000101" in paths["espRawIdent"].read_text()
    return {"rawFileCount": len(scan), "rawBytesScanned": sum(row["bytes"] for row in scan),
            "fullFingerprintScan": scan, "selectedContainerMetadata": containers,
            "nativeDidPatternScan": {"scope": "LE16 and zero-extended LE32 in .rodata/.data/.data.rel.ro of five exact ELF files",
                                     "littleEndian16Hits": did_hits, "littleEndian32Hits": word32_hits,
                                     "ownerCounts": dict(collections.Counter(hit["owner"]["kind"] for hit in did_hits)),
                                     "qualifiedDiagnosticRecords": 0},
            "pngContainers": images,
            "clientDocBlob": {"symbol": doc_symbol.name, "address": hex(doc_address), "bytes": doc_size,
                              "expandedBytes": len(expanded), "expandedSha256": digest(expanded),
                              "matchesAlreadyExtractedApi": True},
            "cellsPointerRecord": {"symbol": data_array.name, "symbolAddress": hex(data_array["st_value"]),
                                   "recordAddress": hex(record_address), "recordIndex": 1133,
                                   "rowSize": 48, "rawHex": record.hex(), "nameFieldOffset": 8,
                                   "namePointer": hex(name_address), "name": cstring(cells, name_address)},
            "nativeVersionProperty": {"symbol": version_symbol.name, "address": hex(start), "bytes": size,
                                      "bodySha256": digest(body), "instructions": instructions,
                                      "propertyNames": {name: hex(address) for name, address in property_literals.items()},
                                      "pltCall1cfaf0": "property_set", "scope": "Host MCU boot/MCU/DSP/Dyna/ANC version properties; no target-child role map"},
            "brakeLinkage": {"factoryAliasesAt782": brakes, "factory7e5Rows": 0,
                             "observed782F194": "32330011DF0B000101",
                             "observed7e5IdentityReads": brake_responses,
                             "calibrationId": calid, "name": name,
                             "physicalRelationship": "unknown; no common exact identity or source-defined logical-to-physical join"}}


def main():
    report = read_json(REPORT)
    for key, source in report["inputs"].items():
        assert digest((ROOT / source["path"]).read_bytes()) == source["sha256"], key
    facts = collect(report["inputs"])
    raw = ROOT / report["rawEvidence"]["path"]
    assert digest(raw.read_bytes()) == report["rawEvidence"]["sha256"]
    assert facts == read_json(raw)
    assert report["summary"]["newQualifiedChildRoles"] == 0
    assert report["summary"]["newQualifiedPhysicalBrakeRelationship"] is False
    print("PASS: 13 raw files; full child/brake fingerprints absent; two exact host-MCU positive controls")
    print("PASS: 23 LE16 coincidences classified as 11 PNG, 11 compressed XML, one name pointer; no LE32 records")
    print("PASS: native version property bytes/PLT/symbols and retained 782/7E5 evidence; physical relationship unknown")


if __name__ == "__main__":
    main()
