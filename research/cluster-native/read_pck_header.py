#!/usr/bin/env python3
"""Index an offline, unencrypted Godot PCK v2 directory, including partial captures.

--source-size is the size of the complete original package, not the header copy.
This reader never connects to a device or executes content from the package.
"""
import argparse
import csv
import hashlib
import json
import struct
from pathlib import Path, PurePosixPath


def read_directory(data, source_size):
    position = 0

    def take(size):
        nonlocal position
        if size < 0 or position + size > len(data):
            raise ValueError("Truncated PCK directory")
        value = data[position:position + size]
        position += size
        return value

    def u32():
        return struct.unpack("<I", take(4))[0]

    if take(4) != b"GDPC":
        raise ValueError("Not a Godot PCK")
    version = u32()
    engine = [u32(), u32(), u32()]
    flags = u32()
    file_base = struct.unpack("<Q", take(8))[0]
    take(64)  # Reserved header words.
    count = u32()
    if version != 2 or flags != 0:
        raise ValueError("Only unencrypted, zero-flag PCK v2 is supported")
    if count > len(data) // 40 or not 100 <= file_base <= source_size:
        raise ValueError("Invalid directory size or file base")
    entries = []
    names = set()
    for _ in range(count):
        name = take(u32()).rstrip(b"\0").decode("utf-8")
        relative, size = struct.unpack("<QQ", take(16))
        md5 = take(16).hex()
        entry_flags = u32()
        offset = file_base + relative
        rel_path = PurePosixPath(name.removeprefix("res://"))
        if (not name.startswith("res://") or rel_path.is_absolute()
                or ".." in rel_path.parts or name in names):
            raise ValueError(f"Invalid or repeated resource path: {name}")
        if entry_flags or offset + size > source_size:
            raise ValueError(f"Unsupported flags or invalid extent: {name}")
        names.add(name)
        entries.append({"path": name, "offset": offset, "size": size,
                        "md5": md5, "flags": entry_flags})
    if position > file_base:
        raise ValueError("Directory overlaps file data")
    return {"engine": engine, "format": version, "file_base": file_base,
            "flags": flags, "entries": entries}, position


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("header", type=Path)
    parser.add_argument("--source-size", type=int, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    raw = args.header.read_bytes()
    catalog, end = read_directory(raw, args.source_size)
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "directory.json").write_text(json.dumps(catalog, indent=2) + "\n")
    with (args.out / "directory.tsv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, ["path", "offset", "size", "md5", "flags"],
                                delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(catalog["entries"])
    proof = {"header_sha256": hashlib.sha256(raw).hexdigest(),
             "header_bytes": len(raw), "source_bytes": args.source_size,
             "directory_end": end, "entries": len(catalog["entries"]),
             "note": "Header hash is not the hash of the complete package."}
    (args.out / "directory-verification.json").write_text(json.dumps(proof, indent=2) + "\n")
    print(json.dumps(proof))


if __name__ == "__main__":
    main()
