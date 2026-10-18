#!/usr/bin/env python3
"""Inspect uncompressed Godot 4.3 RSRC scenes without loading their code.

Implements the subset present in drive_map.scn; unsupported types fail closed.
Format reference: Godot 4.3 core/io/resource_format_binary.cpp and
scene/resources/packed_scene.cpp. External resources remain references.
"""
import argparse
import json
import struct
from pathlib import Path


class Reader:
    def __init__(self, data):
        self.data, self.pos, self.strings = data, 0, []

    def take(self, size):
        end = self.pos + size
        if end > len(self.data):
            raise ValueError("Truncated resource")
        result, self.pos = self.data[self.pos:end], end
        return result

    def number(self, fmt="I"):
        return struct.unpack("<" + fmt, self.take(struct.calcsize("<" + fmt)))[0]

    def string(self):
        return self.take(self.number()).rstrip(b"\0").decode("utf-8")

    def name(self):
        index = self.number()
        if index & 0x80000000:
            return self.take(index & 0x7fffffff).rstrip(b"\0").decode("utf-8")
        return self.strings[index]

    def variant(self):
        kind = self.number()
        if kind == 1:
            return None
        if kind == 2:
            return bool(self.number())
        if kind in (3, 4, 40, 41):
            return self.number({3: "i", 4: "f", 40: "q", 41: "d"}[kind])
        if kind in (5, 44):
            return self.string()
        if kind in (10, 11, 12, 20):
            return {"type": {10: "Vector2", 11: "Rect2", 12: "Vector3", 20: "Color"}[kind],
                    "value": [self.number("f") for _ in range({10: 2, 11: 4, 12: 3, 20: 4}[kind])]}
        if kind == 22:
            names, subnames = self.number("H"), self.number("H")
            return {"type": "NodePath", "absolute": bool(subnames & 0x8000),
                    "names": [self.name() for _ in range(names)],
                    "subnames": [self.name() for _ in range(subnames & 0x7fff)]}
        if kind == 24:
            objtype = self.number()
            if objtype == 0:
                return None
            if objtype in (2, 3):
                return {"resource": "internal" if objtype == 2 else "external", "index": self.number()}
            raise ValueError("Unsupported object type %d" % objtype)
        if kind == 26:
            result = {}
            for _ in range(self.number() & 0x7fffffff):
                key = self.variant()
                result[key] = self.variant()
            return result
        if kind == 30:
            return [self.variant() for _ in range(self.number() & 0x7fffffff)]
        if kind == 31:
            size = self.number()
            values = list(self.take(size))
            self.take((-size) % 4)
            return values
        if kind in (32, 33):
            return [self.number("i" if kind == 32 else "f") for _ in range(self.number())]
        if kind == 34:
            return [self.string() for _ in range(self.number())]
        raise ValueError("Unsupported variant %d at %d" % (kind, self.pos - 4))


def read_scene(path):
    r = Reader(Path(path).read_bytes())
    if r.take(4) != b"RSRC":
        raise ValueError("Only uncompressed RSRC is supported")
    if (r.number(), r.number(), r.number(), r.number(), r.number()) != (0, 0, 4, 3, 6):
        raise ValueError("Expected Godot 4.3, format 6, little endian, float32")
    root_type = r.string()
    r.number("Q")  # Import metadata offset.
    flags = r.number()
    r.number("Q")  # UID.
    if flags != 3:
        raise ValueError("Expected named scene IDs and UIDs only")
    if r.take(44) != bytes(44):
        raise ValueError("Unexpected reserved fields")
    r.strings = [r.string() for _ in range(r.number())]
    external = []
    for _ in range(r.number()):
        external.append({"type": r.string(), "path": r.string(), "uid": r.number("Q")})
    internal = [{"path": r.string(), "offset": r.number("Q")} for _ in range(r.number())]
    for entry in internal:
        r.pos = entry["offset"]
        entry["type"] = r.string()
        entry["properties"] = {}
        for _ in range(r.number()):
            name = r.name()
            entry["properties"][name] = r.variant()
    if r.take(4) != b"RSRC" or r.pos != len(r.data):
        raise ValueError("Unexpected trailing data")
    bundle = internal[-1]["properties"]["_bundled"]
    names, variants, values = bundle["names"], bundle["variants"], iter(bundle["nodes"])
    nodes = []
    for _ in range(bundle["node_count"]):
        parent, owner, kind, name, instance = [next(values) for _ in range(5)]
        # These small scenes do not contain packed sibling indices or instanced properties.
        if name < 0 or name >= len(names):
            raise ValueError("Packed sibling index is unsupported")
        props = {}
        for _ in range(next(values)):
            key, value = next(values), next(values)
            props[names[key]] = variants[value]
        groups = [names[next(values)] for _ in range(next(values))]
        nodes.append({"name": names[name], "parent": parent, "owner": owner,
                      "type": names[kind] if 0 <= kind < len(names) else kind,
                      "instance": instance, "properties": props, "groups": groups})
    if list(values):
        raise ValueError("Unconsumed node data")
    return {"type": root_type, "external": external, "internal": internal, "nodes": nodes}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scene", type=Path)
    args = parser.parse_args()
    print(json.dumps(read_scene(args.scene), ensure_ascii=False, indent=2))
