#!/usr/bin/env python3
"""Bounded in-process rendering experiment; disposable emulator only."""
import argparse
import base64
import hashlib
import json
import math
import pathlib
import statistics
import subprocess
import threading
import time

PACKAGE = "ru.yandex.yandexnavi"
APK_SHA256 = "3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3"
ROOT = pathlib.Path(__file__).resolve().parents[1]


def distribution(values):
    values = sorted(values)
    if not values:
        return None
    return {"median": statistics.median(values), "p95": values[math.ceil(len(values) * .95) - 1],
            "max": values[-1]}


def summarize(frames):
    """Separate active work from cached blank frames; report UI and transport cost."""
    summary = {}
    for mode in sorted({frame["mode"] for frame in frames}):
        selected = [frame for frame in frames if frame["mode"] == mode]
        summary[mode] = {
            "frames": len(selected),
            **{field: distribution([f[field] for f in selected])
               for field in ("elapsedMs", "mainThreadMs", "rpcMs")},
            "stages": {stage: distribution([f["stages"].get(stage, 0) for f in selected])
                       for stage in ("read", "route", "camera", "capture", "overlay", "encode")}}
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=27043)
    parser.add_argument("--seconds", type=int, default=10, choices=range(1, 61))
    parser.add_argument("--fps", type=int, default=2, choices=range(1, 11))
    parser.add_argument("--cycles", type=int, default=1, choices=range(1, 6),
                        help="Start/stop cycles in the same script; total duration at most 120 s")
    parser.add_argument("--simulate", action="store_true", help="Simulate the already selected route in the emulator")
    parser.add_argument("--test-native-alpha", action="store_true",
                        help="Reproduce the failed native-alpha trial; use HUD black by default")
    parser.add_argument("--cancel-route-after", type=int,
                        help="Cancel our simulation after N seconds in the final cycle")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This probe is restricted to a disposable emulator, not the car")
    if args.cycles * args.seconds > 120:
        parser.error("Total rendering duration must not exceed 120 s")
    if args.cancel_route_after is not None and (
            not args.simulate or not 0 < args.cancel_route_after < args.seconds):
        parser.error("--cancel-route-after must be inside --seconds and needs --simulate")

    def adb(*command):
        return subprocess.check_output(["adb", "-s", args.serial, *command], timeout=20, text=True).strip()

    if adb("shell", "getprop", "ro.kernel.qemu") != "1":
        parser.error("Target is not an emulator")
    paths = adb("shell", "pm", "path", PACKAGE).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        parser.error("Need the pinned, single-APK Navigator installed")
    apk_hash = adb("shell", "sha256sum", paths[0][8:]).split()[0]
    if apk_hash != APK_SHA256:
        parser.error("Installed Navigator does not match the reviewed APK")
    expected_forward = f"{args.serial} tcp:{args.port} tcp:27042"
    if expected_forward not in adb("forward", "--list").splitlines():
        parser.error("Forward the chosen emulator's tcp:27042 to --port first")
    pid = int(adb("shell", "pidof", PACKAGE))
    args.out.mkdir(parents=True, exist_ok=False)
    source_files = ["hud-scene.js", "navigator-adapter.js", "android-painter.js", "renderer.js"]
    source = "\n".join((ROOT / "research/yandex-hud-renderer" / name).read_text() for name in source_files)
    (args.out / "agent.js").write_text(source)
    report = {"apk_sha256": apk_hash, "agent_sha256": hashlib.sha256(source.encode()).hexdigest(),
              "serial": args.serial, "pid": pid, "requested_fps": args.fps, "frames": [], "cycles": [],
              "simulation_requested": args.simulate,
              "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}

    import frida  # Pinned research environment: frida==16.7.19

    def bounded(call, *values):
        cancellation = frida.Cancellable()
        timer = threading.Timer(10, cancellation.cancel)
        timer.daemon = True
        timer.start()
        try:
            with cancellation:
                return call(*values)
        finally:
            timer.cancel()

    received = threading.Condition()
    current_cycle = 0

    def message(item, data):
        with received:
            try:
                if item["type"] == "send" and item["payload"].get("kind") == "png":
                    info = item["payload"]["frame"]
                    png = base64.b64decode(item["payload"]["base64"], validate=True)
                    if not png.startswith(b"\x89PNG\r\n\x1a\n") or len(png) != info["bytes"]:
                        raise ValueError("Invalid PNG payload")
                    sequence = len(report["frames"])
                    (args.out / f"frame-{sequence:04d}.png").write_bytes(png)
                    report["frames"].append({**info, "sequence": sequence, "cycle": current_cycle})
                else:
                    report.setdefault("messages", []).append(item)
            except Exception as exc:
                report.setdefault("capture_errors", []).append(str(exc))
            finally:
                received.notify_all()

    session = script = None
    loaded = False
    cleanup_errors = []
    try:
        device = frida.get_device_manager().add_remote_device(f"127.0.0.1:{args.port}")
        session = bounded(device.attach, pid)
        script = bounded(session.create_script, source)
        script.on("message", message)
        bounded(script.load)
        loaded = True
        for current_cycle in range(args.cycles):
            cycle = {"index": current_cycle, "first_frame": len(report["frames"])}
            report["cycles"].append(cycle)
            cycle["renderer"] = bounded(script.exports_sync.start, args.simulate, args.test_native_alpha)
            print(json.dumps(cycle["renderer"]), flush=True)
            begin = time.monotonic()
            next_tick = begin
            while time.monotonic() - begin < args.seconds:
                if (args.cancel_route_after is not None and current_cycle == args.cycles - 1
                        and "cancelled_at_seconds" not in cycle and time.monotonic() - begin >= args.cancel_route_after):
                    bounded(script.exports_sync.cancel_route_for_test)
                    cycle["cancelled_at_seconds"] = time.monotonic() - begin
                before = len(report["frames"])
                rpc_begin = time.monotonic()
                result = bounded(script.exports_sync.frame)
                rpc_ms = (time.monotonic() - rpc_begin) * 1000
                with received:
                    acknowledged = received.wait_for(
                        lambda: len(report["frames"]) > before or report.get("capture_errors"), timeout=2)
                    if not acknowledged or report.get("capture_errors"):
                        raise RuntimeError("Frame delivery failed; see capture_errors")
                    if len(report["frames"]) != before + 1 or report["frames"][-1]["index"] != result["index"]:
                        raise RuntimeError("Frame acknowledgement out of sequence")
                    report["frames"][-1]["rpcMs"] = rpc_ms
                next_tick += 1 / args.fps
                now = time.monotonic()
                # Drop missed deadlines. Never queue catch-up frames behind Navigator.
                if next_tick < now:
                    next_tick += math.ceil((now - next_tick) * args.fps) / args.fps
                time.sleep(max(0, next_tick - time.monotonic()))
            cycle["elapsed_seconds"] = time.monotonic() - begin
            cycle_frames = report["frames"][cycle["first_frame"]:]
            cycle["actual_fps"] = len(cycle_frames) / cycle["elapsed_seconds"]
            cycle["modes"] = summarize(cycle_frames)
            cycle["stop"] = bounded(script.exports_sync.stop)
            print(json.dumps({"cycle": current_cycle, "frames": len(cycle_frames),
                              "actual_fps": cycle["actual_fps"], "modes": cycle["modes"]}), flush=True)
        report["elapsed_seconds"] = sum(c["elapsed_seconds"] for c in report["cycles"])
        report["actual_fps"] = len(report["frames"]) / report["elapsed_seconds"]
        report["modes"] = summarize(report["frames"])
    except BaseException as exc:
        report["error"] = str(exc)
        raise
    finally:
        if loaded:
            try:
                bounded(script.exports_sync.stop)  # Idempotence is also exercised after normal completion.
            except Exception as exc:
                cleanup_errors.append("stop: " + str(exc))
        if script is not None:
            try:
                bounded(script.unload)
            except Exception as exc:
                cleanup_errors.append("unload: " + str(exc))
        if session is not None:
            try:
                bounded(session.detach)
            except Exception as exc:
                cleanup_errors.append("detach: " + str(exc))
        if cleanup_errors:
            report["cleanup_errors"] = cleanup_errors
        (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    if cleanup_errors:
        raise RuntimeError("Renderer cleanup failed; see report.json and discard the emulator")


if __name__ == "__main__":
    main()
