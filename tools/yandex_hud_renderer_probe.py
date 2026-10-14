#!/usr/bin/env python3
"""Bounded in-process rendering experiment; disposable emulator only."""
import argparse
import base64
import hashlib
import json
import pathlib
import statistics
import subprocess
import threading
import time

PACKAGE = "ru.yandex.yandexnavi"
APK_SHA256 = "3b0dec3277f261fd2a2e8b6c0ccb287b1c2bf629b5333bff213fb2f6425024b3"
ROOT = pathlib.Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--port", type=int, default=27043)
    parser.add_argument("--seconds", type=int, default=10, choices=range(1, 61))
    parser.add_argument("--fps", type=int, default=2, choices=range(1, 11))
    parser.add_argument("--simulate", action="store_true", help="Simulate the already selected route in the emulator")
    parser.add_argument("--test-native-alpha", action="store_true",
                        help="Reproduce the failed native-alpha trial; use HUD black by default")
    parser.add_argument("--cancel-route-after", type=int,
                        help="Cancel our simulated route after N seconds to check stale-route removal")
    parser.add_argument("--out", type=pathlib.Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith("emulator-"):
        parser.error("This probe is restricted to a disposable emulator, not the car")
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
    source = (ROOT / "research/yandex-hud-renderer/renderer.js").read_text()
    report = {"apk_sha256": apk_hash, "agent_sha256": hashlib.sha256(source.encode()).hexdigest(),
              "serial": args.serial, "pid": pid, "requested_fps": args.fps, "frames": [],
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

    device = frida.get_device_manager().add_remote_device(f"127.0.0.1:{args.port}")
    session = bounded(device.attach, pid)
    script = bounded(session.create_script, source)

    def message(item, data):
        if item["type"] == "send" and item["payload"].get("kind") == "png":
            info = item["payload"]["frame"]
            png = base64.b64decode(item["payload"]["base64"], validate=True)
            (args.out / f"frame-{info['index']:04d}.png").write_bytes(png)
            report["frames"].append(info)
        else:
            report.setdefault("messages", []).append(item)

    script.on("message", message)
    bounded(script.load)
    try:
        report["renderer"] = bounded(script.exports_sync.start, args.simulate, args.test_native_alpha)
        print(json.dumps(report["renderer"]), flush=True)
        begin = time.monotonic()
        next_tick = begin
        while time.monotonic() - begin < args.seconds:
            if (args.cancel_route_after is not None and "cancelled_at_seconds" not in report
                    and time.monotonic() - begin >= args.cancel_route_after):
                bounded(script.exports_sync.cancel_route_for_test)
                report["cancelled_at_seconds"] = time.monotonic() - begin
            bounded(script.exports_sync.frame)
            next_tick += 1 / args.fps
            now = time.monotonic()
            time.sleep(max(0, next_tick - now))
            next_tick = max(next_tick, now)  # no unbounded catch-up burst
        report["elapsed_seconds"] = time.monotonic() - begin
        report["actual_fps"] = len(report["frames"]) / report["elapsed_seconds"]
        timings = sorted(frame["elapsedMs"] for frame in report["frames"])
        report["frame_ms_median"] = statistics.median(timings)
        report["frame_ms_p95"] = timings[min(len(timings) - 1, int(len(timings) * .95))]
        print(json.dumps({"frames": len(timings), "actual_fps": report["actual_fps"],
                          "frame_ms_median": report["frame_ms_median"]}))
    except BaseException as exc:
        report["error"] = str(exc)
        raise
    finally:
        try:
            bounded(script.exports_sync.stop)
        except Exception as exc:
            report["cleanup_error"] = str(exc)
        finally:
            try:
                bounded(session.detach)
            finally:
                (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    if report.get("cleanup_error"):
        raise RuntimeError("Renderer cleanup failed; see report.json and discard the emulator")


if __name__ == "__main__":
    main()
