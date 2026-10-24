#!/usr/bin/env python3
"""Compile and run the parked player's pure core against fake transport/clock/NaviSt; no ADB, no
Android. The core (ScenarioPlan, PlayerEngine, Preconditions, PlayerMessages) has no Android
imports, so it compiles with only the stock adas-link encoders on the classpath. When the stock
route capture is present, real scenario-*.json files are generated and each is played through the
engine too (DENZA_SCENARIO_DIR); otherwise that part is skipped."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
PROBE = ROOT / "experiments/adas-parked-player-probe/src/main/java/dev/denza/adasplayer/probe"
ADASLINK = ROOT / "libraries/adas-link/src/main/java/dev/denza/adaslink"
HARNESS = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--java-home", type=Path,
                        default=Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"))
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    classes = out / "classes"
    classes.mkdir(exist_ok=True)

    core = sorted(PROBE.glob("*.java"))
    # The Android-only wiring (anything importing android.*) is excluded from the host harness.
    core = [p for p in core if "import android." not in p.read_text()]
    encoders = [ADASLINK / name for name in ("StockMessages.java", "Pb.java", "Topics.java", "Json.java")]
    tests = sorted(HARNESS.glob("*.java"))
    sources = core + encoders + tests

    scenario_dir = out / "scenarios"
    generated = False
    try:
        subprocess.run(["python3", "-m", "tools.adas_bridge.scenario", "generate", "--out", str(scenario_dir)],
                       cwd=ROOT, capture_output=True, text=True, timeout=120, check=True)
        generated = True
    except subprocess.CalledProcessError:
        pass  # capture absent: the scenario-file part of the test is skipped

    commands = [[str(args.java_home / "bin/javac"), "--release", "8", "-encoding", "UTF-8",
                 "-d", str(classes), *map(str, sources)],
                [str(args.java_home / "bin/java"), "-cp", str(classes),
                 "dev.denza.adasplayer.probe.ParkedPlayerTest"]]
    env_dir = str(scenario_dir) if generated else ""
    receipt = {"schema": "denza-parked-player-host-test-v1", "deviceCalls": 0,
               "scenariosGenerated": generated,
               "boundary": "Pure player core with fake transport/clock/NaviSt; no Android or ADB.",
               "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
               "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
    results = []
    import os
    for index, command in enumerate(commands):
        env = dict(os.environ)
        if index == 1:
            env["DENZA_SCENARIO_DIR"] = env_dir
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=120, env=env)
        log = result.stdout + result.stderr
        (out / ("compile.log" if index == 0 else "test.log")).write_text(log)
        results.append({"exitCode": result.returncode, "output": log})
        print(log, end="")
        if result.returncode:
            break
    receipt["results"] = results
    receipt["passed"] = len(results) == 2 and all(r["exitCode"] == 0 for r in results)
    (out / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n")
    return 0 if receipt["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
