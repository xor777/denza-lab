#!/usr/bin/env python3
"""Repeatable passive washer-state captures; evidence stays in ignored captures/.

capture --output <new directory> --state liquid-present|empty|refilled
summarize <directory>
compare <before-directory> <after-directory>

The Java helper reads only installed-catalog scalar FIDs (Binder 5/7), twice.
Raw CAN uses the existing passive subscriber, without changing its selection.
Commands, timing, hashes, failures, unavailable values and capture loss are saved.
An empty reservoir is an owner label, not proof of a sensor's state or polarity.
"""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
from datetime import datetime, timezone

from analyze_can_turn_capture import DONE_RE, parse_capture, sequence_gaps

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "tools/VehicleStateSnapshot.java"
PROPS = ("persist.sys.AutoType", "sys.car.protocol", "ro.build.fingerprint",
         "persist.sys.model_variant.model", "ro.build.display.id", "ro.build.version.incremental")
SERVICES = ("autoservice", "diagnosticsrv", "diagmgrsrv", "stateservice", "gbdataservice",
            "acquisitionsrv", "carpadinfosrv", "crossservice", "di_core_service",
            "ts.car.uds.sdk.IUdsService/default", "power", "battery", "sensorservice")
PACKAGES = ("com.byd.CanDataCollect", "com.byd.vehicledialog", "com.byd.providers.carstatus",
            "com.byd.diagnosticinfo", "dev.denza.apps")


def now():
    return datetime.now(timezone.utc).isoformat()


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def command(argv, path, manifest, timeout=25):
    started = now()
    with path.open("w") as output:
        try:
            result = subprocess.run(argv, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
            status = result.returncode
        except subprocess.TimeoutExpired:
            status = "timeout"
    manifest["commands"].append(dict(argv=argv, file=path.name, started=started,
                                     ended=now(), status=status))
    save_json(path.parent / "manifest.json", manifest)
    return status


def shell(adb, args):
    return adb + ["shell", shlex.join(args)]


def capture(args):
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    adb = ["adb", "-s", args.serial]
    identity = {key: subprocess.check_output(shell(adb, ["getprop", key]), text=True,
                                               timeout=15).strip() for key in PROPS}
    if identity["persist.sys.AutoType"] != str(args.auto_type):
        raise RuntimeError(f"Wrong car: {identity['persist.sys.AutoType']}")
    if identity["sys.car.protocol"] != "CANFD":
        raise RuntimeError("Expected CANFD; do not reuse a mismatched catalog")
    manifest = dict(schema=1, state=args.state, label_source="owner", started=now(),
                    identity=identity, serial=args.serial, commands=[], seconds=args.seconds,
                    scalar_passes=2, repo_head=subprocess.check_output(
                        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    source_sha256={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                        for p in (JAVA, Path(__file__), ROOT / "tools/RawCanTurnProbe.java",
                                  ROOT / "tools/raw_can_turn_probe.sh")})
    save_json(out / "manifest.json", manifest)
    command(shell(adb, ["pidof", "autoservice", "com.byd.CanDataCollect"]),
            out / "pids-before.txt", manifest)
    command(shell(adb, ["sha256sum", "/system/framework/framework.jar",
                       "/system/lib64/hw/auto.default.so", "/system/lib64/libbydautoservice.so"]),
            out / "firmware-hashes.txt", manifest)
    command(shell(adb, ["logcat", "-b", "crash", "-d", "-v", "threadtime"]),
            out / "crash-before.txt", manifest)
    env = dict(os.environ, ADB_SERIAL=args.serial)
    raw_argv = ["bash", str(ROOT / "tools/raw_can_turn_probe.sh"), "run", str(args.seconds),
                "8192", str(out / "raw-can.log")]
    raw_start = now()
    with (out / "raw-can-run.txt").open("w") as log:
        raw = subprocess.Popen(raw_argv, cwd=ROOT, env=env, stdin=subprocess.DEVNULL,
                               stdout=log, stderr=subprocess.STDOUT)
        try:
            # No app install. The bounded helper exits on completion or its own watchdog.
            with tempfile.TemporaryDirectory(prefix="denza-washer-snapshot-") as temp:
                temp = Path(temp)
                classes, dex = temp / "classes", temp / "dex"
                classes.mkdir()
                dex.mkdir()
                sdk = Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"))
                jdk = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"))
                android = sdk / "platforms/android-35/android.jar"
                subprocess.run([str(jdk / "bin/javac"), "-Xlint:-options", "-source", "8", "-target", "8",
                                "-cp", str(android), "-d", str(classes), str(JAVA)], check=True)
                subprocess.run([str(sdk / "build-tools/36.0.0/d8"), "--lib", str(android), "--output", str(dex),
                                *map(str, classes.rglob("*.class"))], check=True)
                remote = "/data/local/tmp/denza-washer-snapshot-" + temp.name + ".dex"
                subprocess.run(adb + ["push", str(dex / "classes.dex"), remote], check=True,
                               stdout=subprocess.DEVNULL, timeout=20)
                try:
                    status = command(adb + ["shell", f"CLASSPATH={shlex.quote(remote)} exec app_process "
                                     "/system/bin --nice-name=denza_washer_snapshot "
                                     "dev.denza.tools.VehicleStateSnapshot 2"],
                                     out / "fids.tsv", manifest, timeout=495)
                    print(f"Scalar catalog capture: {status}", flush=True)
                finally:
                    subprocess.run(shell(adb, ["rm", "-f", remote]), timeout=15, check=False)
            for service in SERVICES:
                command(shell(adb, ["dumpsys", "-t", "8", service]),
                        out / ("service-" + service.replace("/", "_") + ".txt"), manifest, timeout=12)
            for package in PACKAGES:
                command(shell(adb, ["dumpsys", "activity", "services", package]),
                        out / ("app-" + package + ".txt"), manifest)
            for table in ("car_status", "dicare_record"):
                command(shell(adb, ["content", "query", "--uri", "content://com.byd.carStatusProvider/" + table]),
                        out / ("provider-" + table + ".txt"), manifest)
            command(shell(adb, ["logcat", "-d", "-v", "threadtime", "-t", "20000"]),
                    out / "logcat.txt", manifest)
            raw_status = raw.wait(timeout=args.seconds + 30)
            manifest["commands"].append(dict(argv=raw_argv, file="raw-can.log", status=raw_status,
                                               started=raw_start, ended=now()))
        finally:
            # The raw helper has an independent on-device 180-second limit.
            if raw.poll() is None:
                raw.wait(timeout=args.seconds + 30)
    command(shell(adb, ["pidof", "autoservice", "com.byd.CanDataCollect"]),
            out / "pids-after.txt", manifest)
    command(shell(adb, ["logcat", "-b", "crash", "-d", "-v", "threadtime"]),
            out / "crash-after.txt", manifest)
    manifest["ended"] = now()
    save_json(out / "manifest.json", manifest)
    summary = summarize(out)
    save_json(out / "summary.json", summary)
    save_json(out / "sha256.json", {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                   for p in sorted(out.iterdir()) if p.is_file() and p.name != "sha256.json"})
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    if not summary["complete"]:
        raise RuntimeError("Incomplete capture; inspect manifest and loss counters")


def read_fids(path):
    names, values, expected, done = {}, collections.defaultdict(list), None, None
    for line in path.read_text().splitlines():
        cells = line.split("\t")
        if cells[0] == "CAT":
            names[(int(cells[1]), cells[2])] = cells[3]
        elif cells[0] == "BEGIN":
            expected = int(cells[2]) * int(cells[3])
        elif cells[0] == "DONE":
            done = int(cells[1])
        elif cells[0] == "FID":
            values[(int(cells[3]), cells[4])].append((cells[5], cells[6]))
    return names, dict(values), expected, done


def raw_stats(path):
    with path.open() as stream:
        parsed = parse_capture(stream)
    frames = collections.defaultdict(list)
    for frame in parsed.frames:
        frames[frame.address.display()].append(frame.payload)
    return parsed, dict(frames)


def summarize(out):
    names, values, expected, done = read_fids(out / "fids.tsv")
    parsed, frames = raw_stats(out / "raw-can.log")
    actual = sum(map(len, values.values()))
    int_counts = collections.Counter(pair[0] for reads in values.values() for pair in reads)
    # Completion requires the parser's DONE footer as well as loss-free frames.
    footers = list(DONE_RE.finditer((out / "raw-can.log").read_text()))
    raw_done = ({key: int(value) for key, value in footers[-1].groupdict().items()}
                if footers else None)
    complete = (expected is not None and actual == expected == done and raw_done is not None
                and raw_done["received"] == len(parsed.frames) and raw_done["dropped"] == 0
                and raw_done["errors"] == 0 and raw_done["queued"] == 0
                and sequence_gaps(parsed.frames) == 0)
    return dict(complete=complete, catalog_pairs=len(names), devices=len({x[0] for x in names}),
                scalar_reads=actual, expected_reads=expected, done_reads=done,
                stable_scalar_pairs=sum(len(set(v)) == 1 for v in values.values()),
                integer_result_counts=dict(int_counts.most_common(20)),
                raw_frames=len(parsed.frames), raw_addresses=len(frames), raw_done=raw_done,
                raw_sequence_gaps=sequence_gaps(parsed.frames),
                stable_raw_addresses=sum(len(set(v)) == 1 for v in frames.values()),
                addresses={key: len(value) for key, value in sorted(frames.items())})


def compare(before, after):
    a_meta = json.loads((before / "manifest.json").read_text())
    b_meta = json.loads((after / "manifest.json").read_text())
    if a_meta["identity"] != b_meta["identity"]:
        raise ValueError("Vehicle/build mismatch")
    if (before / "firmware-hashes.txt").read_text() != (after / "firmware-hashes.txt").read_text():
        raise ValueError("Firmware hashes differ")
    a_summary, b_summary = summarize(before), summarize(after)
    if not a_summary["complete"] or not b_summary["complete"]:
        raise ValueError("Comparison requires complete loss-free captures")
    a_names, a_values, _, _ = read_fids(before / "fids.tsv")
    b_names, b_values, _, _ = read_fids(after / "fids.tsv")
    if a_names != b_names:
        raise ValueError("Scalar catalogs differ")
    fids = []
    for key in sorted(a_values.keys() & b_values.keys()):
        a, b = set(a_values[key]), set(b_values[key])
        if a != b:
            fids.append(dict(device=key[0], fid=key[1], names=a_names[key], before=sorted(a),
                             after=sorted(b), stable_each=len(a) == len(b) == 1))
    _, a_frames = raw_stats(before / "raw-can.log")
    _, b_frames = raw_stats(after / "raw-can.log")
    bits = []
    for address in sorted(a_frames.keys() & b_frames.keys()):
        a, b = a_frames[address], b_frames[address]
        for byte in range(len(a[0])):
            for bit in range(8):
                a_fraction = sum(bool(p[byte] & (1 << bit)) for p in a) / len(a)
                b_fraction = sum(bool(p[byte] & (1 << bit)) for p in b) / len(b)
                if abs(b_fraction - a_fraction) >= 0.9:
                    bits.append(dict(address=address, byte=byte, bit_lsb0=bit,
                                     before_one_fraction=a_fraction, after_one_fraction=b_fraction,
                                     before_samples=len(a), after_samples=len(b)))
    return dict(before_state=a_meta["state"], after_state=b_meta["state"],
                meaning="Candidates only; require refill reversal and control for other vehicle changes",
                changed_fids=fids, separated_bits=bits,
                missing_after=sorted(a_frames.keys() - b_frames.keys()),
                new_after=sorted(b_frames.keys() - a_frames.keys()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    cap = commands.add_parser("capture")
    cap.add_argument("--output", type=Path, required=True)
    cap.add_argument("--state", choices=("liquid-present", "empty", "refilled"), required=True)
    cap.add_argument("--serial", default="127.0.0.1:5555")
    cap.add_argument("--auto-type", type=int, default=168)
    cap.add_argument("--seconds", type=int, choices=range(10, 181), default=180)
    sub = commands.add_parser("summarize")
    sub.add_argument("directory", type=Path)
    sub = commands.add_parser("compare")
    sub.add_argument("before", type=Path)
    sub.add_argument("after", type=Path)
    args = parser.parse_args()
    if args.command == "capture":
        capture(args)
    elif args.command == "summarize":
        print(json.dumps(summarize(args.directory), indent=2, ensure_ascii=False))
    else:
        print(json.dumps(compare(args.before, args.after), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
