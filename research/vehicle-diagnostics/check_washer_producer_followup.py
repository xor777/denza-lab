#!/usr/bin/env python3
"""Check retained producer-search evidence offline, without firmware execution.

Requires the local owner corpus. Checks source identity, native byte/line
anchors and the old cluster APK's property profiles. No car or network I/O.
Neither a successful check nor an API category establishes a CAN transmitter.
"""
import csv
import hashlib
import io
import json
from pathlib import Path
import struct

from decode_dicar_config import decode


ROOT = Path(__file__).resolve().parents[2]
REPORTS = (
    "washer-producer-cluster-20261021.json",
    "washer-producer-source-map-20261021.json",
)
ASSETS = (
    ("config_1.bin", 8790, "9b9b1e8259015e6a5d15a0ba6a4b539be6c7e7bc750331904697ea31064b3337"),
    ("config_2.bin", 3454, "06d0a859f94119fa9c48e9837e0bbb66024db7800092e11d408aaa7473e50fc1"),
    ("config_3.bin", 700, "566ad2cdfd96c43c01228cc50056fe6f3e3d8b4634a7b60d503fd0499c585d70"),
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def elf_bytes(data, address, length):
    require(data[:6] == b"\x7fELF\x02\x01", "expected ELF64 little endian")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    for index in range(count):
        kind, _, offset, va, _, size, _, _ = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + index * entry_size)
        if kind == 1 and va <= address and address + length <= va + size:
            return data[offset + address - va:offset + address - va + length]
    raise ValueError(f"unmapped ELF address {address:#x}")


def main():
    blobs = {}

    def read(path):
        if path not in blobs:
            blobs[path] = (ROOT / path).read_bytes()
        return blobs[path]

    reports = []
    source_count = byte_count = line_count = 0
    for name in REPORTS:
        report = json.loads(read("research/vehicle-diagnostics/" + name))
        reports.append(report)
        for source in report["sources"]:
            require(hashlib.sha256(read(source["path"])).hexdigest() == source["sha256"], source["path"])
            source_count += 1
        for anchor in report.get("nativeByteAnchors", []):
            expected = bytes.fromhex(anchor["bytes"])
            actual = elf_bytes(read(anchor["path"]), int(anchor["va"], 16), len(expected))
            require(actual == expected, anchor["label"])
            byte_count += 1
        for anchor in report.get("sourceLineAnchors", []):
            actual = read(anchor["path"]).decode().splitlines()[anchor["line"] - 1]
            require(actual == anchor["text"], str(anchor))
            line_count += 1

    cluster, source_map = reports
    require(cluster["result"]["physicalProducerIdentified"] is False, "unexpected producer claim")
    require(source_map["qualifiedSender"] is None and source_map["qualifiedSemanticProducer"] is None,
            "unexpected sender/composer assignment")
    enum_path = "captures/cluster-native-20261018/libCellsClusterDs/dwarf-enums.jsonl"
    domain = {value["name"]: value["value"]
              for line in read(enum_path).decode().splitlines()
              for row in [json.loads(line)] if row["name"] == "vehicle_data_appid_enum"
              for value in row["values"] if value["name"].startswith("DOMAIN_261")}
    require(len(domain) == 24 and domain["DOMAIN_261_10_21_TEXT_INFO"] == 0x26100008,
            "DOMAIN enum changed")
    native = source_map["confirmedLocalObjects"]["nativeClusterConsumer"]
    table = list(csv.DictReader(io.StringIO(read(native["signalTable"]).decode()), delimiter="\t"))
    warning = next(row for row in table if row["index"] == "474")
    require(warning["fid"] == "0x26100008" and warning["length"] == "2", "warning table changed")
    require(list(warning) == native["tableColumns"], "signal table schema changed")

    assets = []
    for name, count, digest in ASSETS:
        path = "reverse/cluster-jadx/resources/assets/" + name
        data = read(path)
        require(hashlib.sha256(data).hexdigest() == digest, path)
        rows = decode(data)
        require(len(rows) == count, "profile count changed")
        fields = [row for row in rows if row[0] >> 20 == 0x261]
        exact = [row for row in rows if row[0] == 0x26100008]
        if name == "config_1.bin":
            require(len(fields) == 25 and exact == [[0x26100008, 3, 0, 0]], "read profile changed")
        else:
            require(not fields, "261 property appeared in another profile")
        assets.append(dict(path=path, sha256=digest, entries=count, family261Entries=len(fields),
                           exactWarningRows=exact))
    print(json.dumps(dict(offlineOnly=True, vehicleCalls=0, networkCalls=0,
        sourceHashes=source_count, nativeByteAnchors=byte_count, sourceLineAnchors=line_count,
        nativeDomain261Names=len(domain), assets=assets,
        assetLimit="FeatureList API metadata, not ECU firmware or a CAN transmitter map. The old cluster APK's runtime use of these assets is unproved.",
        qualifiedSender=None, qualifiedSemanticProducer=None), indent=2))


if __name__ == "__main__":
    main()
