#!/usr/bin/env python3
"""Compile/test the actual passive client with a bounded fake Android Binder boundary; no ADB."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--java-home", type=Path, default=Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"))
    args = parser.parse_args()
    out = args.out.resolve(); out.mkdir(parents=True, exist_ok=True)
    classes = out / "classes"; classes.mkdir(exist_ok=True)
    harness = Path(__file__).resolve().parent
    production = ROOT / "experiments/yandex-hud-module/src/main/java/dev/denza/hud/probe"
    sources = sorted(harness.rglob("*.java")) + [production / (name + ".java") for name in (
        "AdasPassiveClient", "AdasWire", "AdasObservations", "DriveEvidenceStreams",
        "DriveEvidenceLog", "AdasStateJournal", "RouteSnapshot", "Reflect")]
    receipt = {"schema": "denza-passive-client-host-test-v1", "deviceCalls": 0,
               "boundary": "In-memory typed Parcel/Binder fake; native replay/start-order behavior is an explicit fixture, not new device evidence",
               "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
    commands = [("compile", [str(args.java_home / "bin/javac"), "--release", "8", "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)])]
    # Separate JVMs exercise the real process latch; no test-only reset/injection exists in production.
    commands += [("test-" + mode, [str(args.java_home / "bin/java"), "-cp", str(classes),
                  "dev.denza.hud.probe.AdasPassiveClientTest", mode]) for mode in ("off", "on", "off", "directory", "error")]
    results = []
    for index, (name, command) in enumerate(commands):
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=60)
        log = result.stdout + result.stderr; (out / (str(index) + "-" + name + ".log")).write_text(log)
        results.append({"name": name, "exitCode": result.returncode, "output": log})
        print(log, end="")
        if result.returncode: break
    receipt["results"] = results; receipt["passed"] = len(results) == len(commands) and all(r["exitCode"] == 0 for r in results)
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if receipt["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
