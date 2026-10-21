#!/usr/bin/env python3
"""Local-only compiler for the bounded GHY metadata experiment. Never uses ADB."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import run_ghy_check_metadata as host


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("output", type=Path)
    args = p.parse_args()
    if args.output.exists(): p.error("new build directory required")
    args.output.mkdir(parents=True, mode=0o700)
    classes, dex = args.output / "classes", args.output / "dex"
    classes.mkdir(); dex.mkdir()
    jdk = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    sdk = Path("/opt/homebrew/share/android-commandlinetools")
    android = sdk / "platforms/android-35/android.jar"
    sources = [host.HERE / n for n in ("GhyCheckVersionProbe.java", "OtaCheckVersionProbe.java",
                                      "EcuMapMetadataProbe.java")] + [host.ADAPTER]
    env = {**os.environ, "JAVA_HOME": str(jdk)}
    before = host.hashes()
    with (args.output / "compile.txt").open("w") as log:
        subprocess.run([str(jdk / "bin/javac"), "--release", "8", "-cp", str(android),
                        "-d", str(classes), *map(str, sources)], check=True, stdout=log,
                       stderr=subprocess.STDOUT, env=env, timeout=60)
    with (args.output / "dex.txt").open("w") as log:
        subprocess.run([str(sdk / "build-tools/35.0.0/d8"), "--release", "--min-api", "33",
                        "--lib", str(android), "--output", str(dex),
                        *map(str, sorted(classes.rglob("*.class")))], check=True,
                       stdout=log, stderr=subprocess.STDOUT, env=env, timeout=60)
    if before != host.hashes(): raise RuntimeError("source changed during build")
    result = {"sourceHashes": before, "artifactSha256": host.base.sha(dex / "classes.dex"),
              "androidApi": 35, "minApi": 33, "liveValidated": False}
    host.gcy.write_json(args.output / "manifest.json", result)
    print(json.dumps({"build": str(args.output), "artifactSha256": result["artifactSha256"]}))


if __name__ == "__main__": main()
