#!/usr/bin/env python3
"""Recover the Godot API descriptions embedded in libClusterClient, offline.

The compressed blob contains concatenated XML class documents. These are build
metadata, not proof that a method runs, nor reconstructed C++ implementations.
"""
import argparse
import csv
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

from inspect_native import Native


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    native = Native(args.elf)
    symbol = next(s for s in native.symbols.values()
                  if s["name"] == "_ZL20_doc_data_compressed")
    compressed = native.bytes_at(symbol["address"], symbol["size"])
    decoder = zlib.decompressobj()
    payload = decoder.decompress(compressed, 32 * 1024 * 1024)
    if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
        raise ValueError("Expected one complete zlib stream smaller than 32 MiB")
    source = payload.decode("utf-8")
    classes = []
    for fragment in re.split(r'<\?xml[^?]*\?>', source):
        if fragment.strip():
            element = ET.fromstring(fragment.strip())
            if element.tag != "class" or not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", element.get("name", "")):
                raise ValueError("Unexpected class document or filename")
            classes.append(element)
    names = [c.get("name") for c in classes]
    if len(names) != len(set(names)):
        raise ValueError("Duplicate API class names")
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "embedded-classes.xml").write_bytes(payload)
    xml_dir = args.out / "classes"
    xml_dir.mkdir(exist_ok=True)
    rows = []
    inventory = []
    for element in classes:
        name = element.get("name")
        ET.ElementTree(element).write(xml_dir / f"{name}.xml", encoding="utf-8", xml_declaration=True)
        counts = {}
        for group in ("members", "methods", "signals", "constants"):
            items = element.findall(f"{group}/*")
            counts[group] = len(items)
            for item in items:
                description = item.find("description")
                if description is not None:
                    description = " ".join("".join(description.itertext()).split())
                else:
                    description = " ".join((item.text or "").split())
                ret = item.find("return")
                rows.append({
                    "class": name, "inherits": element.get("inherits", ""), "kind": item.tag,
                    "name": item.get("name", ""), "type": item.get("type", ""),
                    "return_type": ret.get("type", "") if ret is not None else "",
                    "attributes": json.dumps(item.attrib, ensure_ascii=False, sort_keys=True),
                    "parameters": json.dumps([p.attrib for p in item.findall("param")], ensure_ascii=False),
                    "description": description,
                })
        inventory.append({"class": name, "inherits": element.get("inherits", ""), **counts})
    for filename, data in (("api.tsv", rows), ("classes.tsv", inventory)):
        with (args.out / filename).open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, list(data[0]), delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(data)
    manifest = {"elf_sha256": native.sha256, "symbol": symbol["name"],
                "symbol_address": hex(symbol["address"]), "compressed_size": len(compressed),
                "expanded_size": len(payload), "expanded_sha256": hashlib.sha256(payload).hexdigest(),
                "classes": len(classes), "entries": len(rows),
                "scope": "Embedded API metadata; no firmware execution or external documents."}
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest))
    native.close()


if __name__ == "__main__":
    main()
