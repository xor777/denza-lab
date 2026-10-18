#!/usr/bin/env python3
"""Extract static Cells data tables using DWARF-checked layouts. Offline only.

Values are ELF initializers, not current vehicle state. Pointer fields used for
names must point into a file-backed section at the ELF's zero load base.
No setters, device connection, firmware execution, or vehicle changes.
"""
import argparse
import csv
import json
import struct
from pathlib import Path

from inspect_native import Native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf", type=Path)
    parser.add_argument("--index", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    native = Native(args.elf)
    metadata = json.loads((args.index / "metadata.json").read_text())
    if native.sha256 != metadata["sha256"]:
        raise ValueError("ELF and DWARF index hashes differ")
    layouts = [json.loads(line) for line in (args.index / "dwarf-layouts.jsonl").open()]
    enums = [json.loads(line) for line in (args.index / "dwarf-enums.jsonl").open()]
    symbols = {item["name"]: item for item in native.symbols.values()
               if item["section"] != "SHN_UNDEF"}
    sections = [(s["sh_addr"], s.data()) for s in native.elf.iter_sections()
                if s.name in (".rodata", ".data", ".data.rel.ro")]

    def layout(name, size, expected):
        matches = [l for l in layouts if l["name"] == name]
        if not matches:
            raise ValueError(f"Missing DWARF type {name}")
        for item in matches:
            actual = {m["name"]: m["location"] for m in item["members"]}
            if item["size"] != size or any(actual.get(k) != v for k, v in expected.items()):
                raise ValueError(f"Unexpected DWARF layout {name}")
        return matches[0]

    def enum_map(name=None, contains=None):
        candidates = [e for e in enums if e["name"] == name or
                      contains and any(v["name"] == contains for v in e["values"])]
        if not candidates:
            raise ValueError(f"Missing enum {name or contains}")
        def names_by_value(item):
            names = {}
            for value in item["values"]:
                names.setdefault(value["value"], []).append(value["name"])
            return {key: "|".join(sorted(set(value))) for key, value in names.items()}

        result = names_by_value(candidates[0])
        for e in candidates[1:]:
            if names_by_value(e) != result:
                raise ValueError(f"Conflicting enum definitions {name or contains}")
        return result

    def cstring(address):
        if address == 0:
            return ""
        for base, data in sections:
            if base <= address < base + len(data):
                raw = data[address - base:address - base + 4096]
                if b"\0" not in raw:
                    raise ValueError(f"Unterminated string at {address:#x}")
                return raw.split(b"\0", 1)[0].decode("utf-8")
        raise ValueError(f"Initializer string pointer is not file-backed: {address:#x}")

    def table(name, row_size):
        symbol, size_symbol = symbols[name], symbols[name + "Size"]
        count = struct.unpack("<I", native.bytes_at(size_symbol["address"], 4))[0]
        if symbol["size"] != count * row_size:
            raise ValueError(f"Table count and symbol extent differ: {name}")
        raw = native.bytes_at(symbol["address"], symbol["size"])
        return symbol["address"], [raw[i * row_size:(i + 1) * row_size] for i in range(count)]

    def save(name, rows):
        with (args.out / name).open("w", newline="") as stream:
            output = csv.DictWriter(stream, fieldnames=list(rows[0]), delimiter="\t", lineterminator="\n")
            output.writeheader()
            output.writerows(rows)

    checked_layouts = [
        layout("can_data_array_item_type_", 56,
               {"m_nFunctionId": 0, "m_nRealFunctionId": 4, "m_nDataLen": 8,
                "m_nDataType": 12, "m_bRepeated": 16, "m_bSync": 17,
                "m_u32NoNeedPlatform": 20, "m_Data": 24}),
        layout("config_data_array_item_type_", 40,
               {"m_nConfigId": 0, "m_pConfigName": 8, "m_nDataType": 16,
                "m_DefaultValue": 24, "m_Value": 32}),
        layout("data_source_item_type_", 48,
               {"m_nDataItemId": 0, "m_pDataItemName": 8, "m_nDataType": 16,
                "m_Value": 24, "m_pWrapper": 32, "m_bNeedSecret": 40}),
        layout("plugin_msg_item_type_", 32,
               {"m_nMsgId": 0, "m_pMsgName": 8, "m_nMsgType": 16, "m_pHandler": 24}),
    ]
    can_types = enum_map(contains="CAN_DATA_TYPE_UINT8")
    config_types = enum_map(contains="CONFIG_DATA_TYPE_BOOL")
    item_types = enum_map(contains="DATA_ITEM_TYPE_BOOL")
    config_ids = enum_map(name="_type_configure_id_enum")
    item_ids = enum_map(name="_type_data_item_id_enum")
    plugin_ids = enum_map(name="_type_plugin_msg_item_id_enum")
    plugin_types = enum_map(name="_plugin_msg_item_type_")
    args.out.mkdir(parents=True, exist_ok=True)
    all_counts = {}
    address, raw_rows = table("g_CanDataArray", 56)
    rows = []
    for i, raw in enumerate(raw_rows):
        fid, real = struct.unpack_from("<II", raw)
        dtype, excluded = struct.unpack_from("<I", raw, 12)[0], struct.unpack_from("<I", raw, 20)[0]
        rows.append({"index": i, "address": hex(address + 56 * i), "fid": f"0x{fid:08x}",
                     "real_fid": f"0x{real:08x}", "length": raw[8], "type": can_types[dtype],
                     "repeated": raw[16], "sync": raw[17], "no_need_platform": hex(excluded),
                     "static_data_hex": raw[24:40].hex()})
    save("can-signals.tsv", rows)
    all_counts["can_signals"] = len(rows)
    for symbol, row_size, ids, types, output in [
            ("g_ConfigDataArray", 40, config_ids, config_types, "configuration.tsv"),
            ("g_DataSourceArray", 48, item_ids, item_types, "data-items.tsv"),
            ("g_PluginMsgArray", 32, plugin_ids, plugin_types, "plugin-messages.tsv")]:
        address, raw_rows = table(symbol, row_size)
        rows = []
        for i, raw in enumerate(raw_rows):
            item_id, = struct.unpack_from("<I", raw)
            pointer, = struct.unpack_from("<Q", raw, 8)
            dtype, = struct.unpack_from("<I", raw, 16)
            row = {"index": i, "address": hex(address + row_size * i), "id": item_id,
                   "id_hex": hex(item_id), "enum": ids[item_id], "name": cstring(pointer),
                   "type": types[dtype]}
            if symbol == "g_ConfigDataArray":
                row["static_default_hex"] = raw[24:32].hex()
                if types[dtype].endswith("STRING"):
                    row["static_default"] = cstring(struct.unpack_from("<Q", raw, 24)[0])
                elif types[dtype].endswith("FLOAT"):
                    row["static_default"] = struct.unpack_from("<f", raw, 24)[0]
                else:
                    fmt = {"BOOL": "?", "UINT8": "B", "INT8": "b", "UINT16": "H",
                           "INT16": "h", "UINT32": "I", "INT32": "i"}
                    row["static_default"] = struct.unpack_from("<" + fmt[types[dtype].rsplit("_", 1)[-1]], raw, 24)[0]
            elif symbol == "g_DataSourceArray":
                row["static_value_hex"] = raw[24:32].hex()
                row["need_secret"] = struct.unpack_from("<i", raw, 40)[0]
            else:
                row["static_handler_pointer"] = hex(struct.unpack_from("<Q", raw, 24)[0])
            rows.append(row)
        if len(set(row["id"] for row in rows)) != len(rows):
            raise ValueError(f"Duplicate table IDs: {symbol}")
        save(output, rows)
        all_counts[output] = len(rows)
    (args.out / "checked-layouts.json").write_text(json.dumps(checked_layouts, indent=2) + "\n")
    (args.out / "table-manifest.json").write_text(json.dumps({
        "sha256": native.sha256, "counts": all_counts,
        "validation": ["DWARF member offsets and sizes", "symbol extent = count * row size",
                       "IDs and types resolve to DWARF enums", "names point to UTF-8 ELF strings"],
        "scope": "Static initializers. Does not read live values or resolve runtime overrides.",
    }, indent=2) + "\n")
    print(json.dumps(all_counts))
    native.close()


if __name__ == "__main__":
    main()
