#!/usr/bin/env python3
"""Offline Godot tokenizer-v100 reader; never executes scripts.

Implements the token buffer format from Godot 4.3 source. Scalar constant
types only; unknown variants and malformed extents fail closed. Output retains
token order, source line numbers and indentation, but not comments or spacing.
Pass the captured official gdscript_tokenizer.cpp to obtain token spellings.
"""
import argparse
import csv
import hashlib
import json
import re
import struct
import subprocess
from collections import defaultdict
from pathlib import Path


def decode(path, spellings):
    raw = path.read_bytes()
    if raw[:4] != b"GDSC" or len(raw) < 12:
        raise ValueError("Not a GDSC tokenizer buffer")
    version, size = struct.unpack_from("<II", raw, 4)
    if version != 100 or size > 32 * 1024 * 1024:
        raise ValueError("Unsupported tokenizer version or expanded size")
    data = (subprocess.run(["zstd", "-d", "-c"], input=raw[12:], capture_output=True,
                           timeout=15, check=True).stdout if size else raw[12:])
    if size and len(data) != size:
        raise ValueError("Expanded size differs from header")
    position = 0

    def take(length):
        nonlocal position
        if length < 0 or position + length > len(data):
            raise ValueError("Truncated tokenizer buffer")
        value = data[position:position + length]
        position += length
        return value

    def u32():
        return struct.unpack("<I", take(4))[0]

    identifier_count, constant_count, line_count, reserved, token_count = struct.unpack("<5I", take(20))
    if max(identifier_count, constant_count, line_count, token_count) > len(data):
        raise ValueError("Invalid table count")
    identifiers = []
    for _ in range(identifier_count):
        identifiers.append(bytes(b ^ 0xB6 for b in take(u32() * 4)).decode("utf-32-le"))
    constants = []
    for _ in range(constant_count):
        word = u32()
        kind, wide = word & 0xFFFF, bool(word & 0x10000)
        if word & ~0x1FFFF:
            raise ValueError(f"Unsupported Variant flags: {word:#x}")
        if kind == 0:
            value = "null"
        elif kind == 1:
            value = "true" if u32() else "false"
        elif kind == 2:
            value = str(struct.unpack("<q" if wide else "<i", take(8 if wide else 4))[0])
        elif kind == 3:
            value = repr(struct.unpack("<d" if wide else "<f", take(8 if wide else 4))[0])
        elif kind in (4, 21):  # STRING, STRING_NAME
            length = u32()
            value = json.dumps(take(length).decode("utf-8"), ensure_ascii=False)
            take((-length) % 4)
            if kind == 21:
                value = "&" + value
        else:
            raise ValueError(f"Unsupported Variant type {kind} at {position - 4:#x}")
        constants.append(value)

    def read_map():
        result = {}
        for _ in range(line_count):
            token_index, value = u32(), u32()
            if token_index in result or token_index >= token_count:
                raise ValueError("Invalid source-map token index")
            result[token_index] = value
        return result

    lines, columns = read_map(), read_map()
    if lines.keys() != columns.keys():
        raise ValueError("Line and column map keys differ")
    tokens = []
    grouped = defaultdict(list)
    indents = {}
    for index in range(token_count):
        if position >= len(data):
            raise ValueError("Truncated token stream")
        word = u32() if data[position] & 0x80 else take(1)[0]
        kind, table_index = word & 0x7F, word >> 8
        line = u32()
        if kind >= len(spellings) or not 0 < line < 1_000_000:
            raise ValueError("Invalid token kind or line")
        if kind in (1, 2):
            spelling = identifiers[table_index]
            if kind == 1 and not spelling.startswith("@"):
                spelling = "@" + spelling
        elif kind in (3, 97):  # LITERAL, ERROR (error tokens must not become code)
            if kind == 97:
                raise ValueError("Error token in exported script")
            spelling = constants[table_index]
        else:
            spelling = spellings[kind]
        if index in lines and lines[index] != line:
            raise ValueError("Token and source-map line differ")
        column = columns.get(index)
        tokens.append({"index": index, "kind": kind, "line": line,
                       "column": column if column is not None else "", "text": spelling})
        if spelling not in ("Newline", "Indent", "Dedent", "EOF", "Empty"):
            if line not in indents:
                indents[line] = max(0, (column or 1) - 1)
            grouped[line].append(spelling)
    if position != len(data):
        raise ValueError(f"Unconsumed bytes: {len(data) - position}")
    source = "\n".join(" " * indents.get(line, 0) + " ".join(grouped.get(line, []))
                       for line in range(1, max(grouped) + 1)) + "\n"
    return source, tokens, {"sha256": hashlib.sha256(raw).hexdigest(), "version": version,
                           "expanded_bytes": len(data), "identifiers": identifier_count,
                           "constants": constant_count, "tokens": token_count,
                           "source_map_entries": line_count, "reserved_header_word": reserved,
                           "consumed_all_bytes": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--token-names-source", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    cpp = args.token_names_source.read_text()
    block = cpp.split("static const char *token_names[] = {", 1)[1].split("};", 1)[0]
    spellings = [json.loads(s) for s in re.findall(r'^\s*("(?:[^"\\]|\\.)*")\s*,', block, re.M)]
    if len(spellings) != 99 or spellings[56] != "class_name":
        raise ValueError("Unexpected token vocabulary")
    paths = sorted(args.input.rglob("*.gdc")) if args.input.is_dir() else [args.input]
    manifest = []
    for path in paths:
        source, tokens, record = decode(path, spellings)
        relative = path.relative_to(args.input) if args.input.is_dir() else Path(path.name)
        target = (args.out / relative).with_suffix(".gd")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source)
        with target.with_suffix(".tokens.tsv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, list(tokens[0]), delimiter="\t", lineterminator="\n")
            writer.writeheader()
            writer.writerows(tokens)
        manifest.append({"input": str(path), "output": str(target), **record})
        print(path.name, record["tokens"], "tokens", flush=True)
    (args.out / "manifest.json").write_text(json.dumps({
        "token_names_source_sha256": hashlib.sha256(args.token_names_source.read_bytes()).hexdigest(),
        "scope": "Offline token reconstruction, not original commented source; scripts not executed.",
        "files": manifest}, indent=2) + "\n")


if __name__ == "__main__":
    main()
