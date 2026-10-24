#!/usr/bin/env python3
"""Independent Python-vs-Java replay on every road component of the eight pinned native-success tiles.

Run from the repository root: python3 libraries/adas-link/src/test/python/vector_road_oracle.py
Only temporary javac classes are created; all capture inputs remain unchanged.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[5])
    args = parser.parse_args()
    root = args.root.resolve()
    sys.path.insert(0, str(root))
    from tools.adas_bridge.yandex_vector_geometry import decode_capture

    prefix = "libraries/adas-link/src/"
    sources = [prefix + "main/java/dev/denza/adaslink/" + n + ".java"
               for n in ["Json", "VectorRoadTile", "VectorRoadTileCache"]]
    sources += [prefix + "test/java/dev/denza/adaslink/" + n + ".java"
                for n in ["VectorRoadTileTest", "VectorRoadTileCacheTest"]]
    oracle_sources = ["tools/adas_bridge/yandex_vector_tile.py", "tools/adas_bridge/yandex_vector_geometry.py"]
    pins = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sources + oracle_sources}
    capture = decode_capture(root / "captures/yandex-vector-world-20261024/runtime")
    keep = {"class", "fc", "oneway", "struct", "struct:toll", "toll", "zlev", "min_zlev", "max_zlev", "z_level"}
    expected = []
    for tile in capture["tiles"]:
        dictionary = tile["dictionaries"]
        for feature in tile["features"]:
            if feature["layer"] != "road" or feature["geometry"]["type"] != "MultiLineString":
                continue
            values, types = {}, {}
            attrs = feature["attributes"]
            if attrs is not None:
                if attrs["issues"]:
                    raise AssertionError("oracle corpus attribute-reference issue")
                keys = dictionary["keySets"][attrs["keySetIndex"]]["keys"]
                for key, index in zip(keys, attrs["valueIndices"]):
                    if key in keep:
                        value = dictionary["values"][index]
                        values[key], types[key] = value["value"], value["type"]
            for part, points in enumerate(feature["geometry"]["coordinates"]):
                expected.append({"sha256": tile["source"]["sha256"], "featureId": feature["field5Uint64"],
                                 "featureIndex": feature["featureIndex"], "partIndex": part,
                                 "attributes": values, "attributeTypes": types, "points": points})
    jdk = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")) / "bin"
    javac = str(jdk / "javac") if (jdk / "javac").exists() else shutil.which("javac")
    java = str(jdk / "java") if (jdk / "java").exists() else shutil.which("java")
    if not javac or not java:
        raise RuntimeError("JDK with javac --release8 required")
    test_outputs = []
    with tempfile.TemporaryDirectory(prefix="denza-vector-oracle-") as tmp:
        subprocess.run([javac, "--release", "8", "-d", tmp] + [str(root / s) for s in sources], check=True, timeout=60)
        for name in ["VectorRoadTileTest", "VectorRoadTileCacheTest"]:
            run = subprocess.run([java, "-Xmx256m", "-cp", tmp, "dev.denza.adaslink." + name, str(root)],
                                 check=True, stdout=subprocess.PIPE, text=True, timeout=60)
            test_outputs.append(run.stdout.strip())
        run = subprocess.run([java, "-Xmx256m", "-cp", tmp, "dev.denza.adaslink.VectorRoadTileTest", str(root), "--dump-corpus"],
                             check=True, stdout=subprocess.PIPE, text=True, timeout=60)
    actual = [json.loads(line) for line in run.stdout.splitlines()]
    if len(actual) != len(expected):
        raise AssertionError(("road part count mismatch", len(actual), len(expected)))
    coordinate_count, attributes_count, maximum_error = 0, 0, 0.0
    for number, (got, want) in enumerate(zip(actual, expected)):
        for key in ["sha256", "featureId", "featureIndex", "partIndex", "attributes", "attributeTypes"]:
            if got[key] != want[key]:
                raise AssertionError(("row mismatch", number, key, got[key], want[key]))
        if len(got["points"]) != len(want["points"]):
            raise AssertionError(("point count mismatch", number))
        for p, q in zip(got["points"], want["points"]):
            for a, b in zip(p, q):
                if not math.isfinite(a) or not math.isfinite(b):
                    raise AssertionError("nonfinite coordinate")
                error = abs(a - b)
                if error > 2e-12:
                    raise AssertionError(("projection mismatch", number, error))
                maximum_error = max(maximum_error, error)
            coordinate_count += 1
        attributes_count += len(want["attributes"])
    if any(hashlib.sha256((root / name).read_bytes()).hexdigest() != pin for name, pin in pins.items()):
        raise AssertionError("source changed during independent oracle run")
    print(json.dumps({"schema": "denza-vector-java-python-oracle-v1", "status": "PASS", "captureSummary": capture["summary"],
                      "roadParts": len(actual), "pointPairsCompared": coordinate_count, "typedAttributesCompared": attributes_count,
                      "maximumCoordinateErrorDegrees": maximum_error, "absoluteCoordinateToleranceDegrees": 2e-12,
                      "sourcePins": pins, "javaTests": test_outputs,
                      "limits": ["All eight supplied captures, not arbitrary future tile schemas or complete route coverage",
                                 "Literal renderer class/fc/oneway/z fields only; no stock enum or traffic orientation mapping"]}, indent=2))


if __name__ == "__main__":
    main()
