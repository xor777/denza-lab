#!/usr/bin/env python3
"""Four-window ADB remote. See docs/window-control-findings.md.

    python3 tools/vehicle_windows.py status
    python3 tools/vehicle_windows.py serve

Only a button POST writes; startup, status and polling are read-only. No APK,
ADB reconnect, authorization changes, background retries or arbitrary FIDs.
"""
import argparse
import datetime as dt
import hmac
import json
from pathlib import Path
import re
import secrets
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


# CAN-FD constants, resolved from this framework, NOT the *_hal_only aliases.
# name, SET, percent GET, initialization GET, thermal-protection GET
WINDOWS = {
    "fl": ("Переднее левое", 1125122104, 947912728, 1222639632, 1222639646),
    "fr": ("Переднее правое", 1125122107, 1267728400, 1267728392, 947912768),
    "rl": ("Заднее левое", 1125122112, 947912736, 1222639634, 1222639647),
    "rr": ("Заднее правое", 1125122115, 947912752, 1222639638, 947912769),
}
ACTIONS = {"open": 1, "close": 2, "stop": 3}
HASHES = {
    "/system/framework/framework.jar": "aa3acd7738e1fa22c4ec68f69b271fd6938cf7756239c1ac6b30051c0d4fda8c",
    "/system/app/BydMyCar/BydMyCar.apk": "ebd1f91d77c76f03da09b2d7ae087ab72afd9443df6f187f09c4c64efa706751",
}
IDENTITY = "getprop sys.car.protocol; getprop persist.sys.AutoType; cat /proc/sys/kernel/random/boot_id"
PARCEL = re.compile(r"^Result: Parcel\(([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})\s+'[^\r\n]{8}'\)\s*$")
SET_PARCEL = re.compile(r"^Result: Parcel\(([0-9a-fA-F]{8})\s+'[^\r\n]{4}'\)\s*$")
ROOT = Path(__file__).resolve().parent.parent


class ControlError(Exception):
    pass


def parcel(text):
    """Require exactly an exception word and one int, never accept partial output."""
    match = PARCEL.fullmatch(text.strip())
    if not match or int(match[1], 16) != 0:
        raise ControlError("Некорректный ответ autoservice: " + text.strip()[:160])
    value = int(match[2], 16)
    return value - 2**32 if value >= 2**31 else value


def get_command(device, fid):
    return f"service call autoservice 5 i32 {device} i32 {fid}"


def set_result(text):
    # Native SET returns one raw HAL word; GET has an exception word + value.
    match = SET_PARCEL.fullmatch(text.strip())
    if not match:
        raise ControlError("Некорректный ответ записи autoservice: " + text.strip()[:160])
    value = int(match[1], 16)
    return value - 2**32 if value >= 2**31 else value


def set_command(window, action):
    if window not in WINDOWS or action not in ACTIONS:
        raise ControlError("Неизвестное окно или действие")
    return f"service call autoservice 6 i32 1001 i32 {WINDOWS[window][1]} i32 {ACTIONS[action]} null"


def blocked_reason(state, window):
    if state["park"] != 1:
        return "Управление доступно только в P"
    if state["permit"] != 0:
        return "Штатное разрешение управления отсутствует или неизвестно"
    if window["initialized"] != 1:
        return "Окно не инициализировано или состояние неизвестно"
    if window["thermal"] != 0:
        return "Термозащита активна или состояние неизвестно"
    if window["percent"] is None:
        return "Положение окна неизвестно"
    return None


class Remote:
    def __init__(self, serial, adb="adb", log_dir=None):
        self.serial, self.adb = serial, adb
        self.lock = threading.Lock()
        self.identity = None
        self.log_dir = log_dir or ROOT / "captures/window-control"

    def shell(self, command, timeout=5):
        try:
            result = subprocess.run([self.adb, "-s", self.serial, "shell", command],
                                    capture_output=True, text=True, timeout=timeout)
        except (subprocess.TimeoutExpired, OSError) as exc:
            raise ControlError("ADB недоступен; команда не повторялась") from exc
        if result.returncode:
            raise ControlError("ADB: " + (result.stderr or result.stdout).strip()[:200])
        return result.stdout.strip()

    def verify(self):
        lines = self.shell(IDENTITY + "; sha256sum " + " ".join(HASHES), timeout=30).splitlines()
        if len(lines) != 5 or lines[0] != "CANFD" or lines[1] not in ("168", "170"):
            raise ControlError("Профиль машины не совпадает с исследованным CAN-FD N9/Z9GT")
        if not re.fullmatch(r"[0-9a-f-]{36}", lines[2]):
            raise ControlError("Не удалось определить текущую загрузку машины")
        for line, (path, digest) in zip(lines[3:], HASHES.items()):
            if line.split() != [digest, path]:
                raise ControlError("Версия файла отличается от исследованной: " + path)
        self.identity = lines[:3]

    def check_identity(self, lines):
        if self.identity is None or lines != self.identity:
            raise ControlError("Машина или загрузка изменились. Перезапустите пульт для проверки версии")

    def snapshot(self):
        commands = {"park": get_command(1011, 89129008),
                    "permit": get_command(1001, 1222639670)}
        for key, (_, _, percent, initialized, thermal) in WINDOWS.items():
            for suffix, fid in (("percent", percent), ("initialized", initialized), ("thermal", thermal)):
                commands[f"{key}_{suffix}"] = get_command(1001, fid)
        script = IDENTITY + "; " + "; ".join(f"echo @{key}; {cmd}" for key, cmd in commands.items())
        output = self.shell(script)
        parts = re.split(r"(?m)^@([a-z_]+)\s*\n", output)
        self.check_identity(parts[0].strip().splitlines())
        if parts[1::2] != list(commands):
            raise ControlError("Неполный ответ чтения окон")
        values = {}
        for key, raw in zip(parts[1::2], parts[2::2]):
            try:
                values[key] = parcel(raw)
            except ControlError:
                values[key] = None
        state = {"model": "N9" if self.identity[1] == "168" else "Z9GT",
                 "park": values["park"], "permit": values["permit"],
                 "time": dt.datetime.now().astimezone().isoformat(timespec="seconds"), "windows": {}}
        for key, spec in WINDOWS.items():
            percent = values[f"{key}_percent"]
            window = {"name": spec[0], "percent": percent if percent is not None and 0 <= percent <= 100 else None,
                      "initialized": values[f"{key}_initialized"], "thermal": values[f"{key}_thermal"]}
            window["blocked"] = blocked_reason(state, window)
            state["windows"][key] = window
        return state

    def record(self, item):
        self.log_dir.mkdir(parents=True, exist_ok=True)
        with (self.log_dir / "actions.jsonl").open("a") as stream:
            stream.write(json.dumps(item, ensure_ascii=False) + "\n")
            stream.flush()

    def act(self, window, action):
        command = set_command(window, action)
        # STOP does not depend on P, calibration, thermal state or a position read.
        if action == "stop":
            self.check_identity(self.shell(IDENTITY).splitlines())
        else:
            state = self.snapshot()  # fresh guards, never trust browser state
            reason = state["windows"][window]["blocked"]
            if reason:
                raise ControlError(reason)
        entry = {"time": dt.datetime.now(dt.timezone.utc).isoformat(), "window": window,
                 "action": action, "command": command, "phase": "attempt"}
        self.record(entry)  # no send if the evidence cannot be retained
        try:
            raw = self.shell(command)
            code = set_result(raw)
            result = {"code": code, "raw": raw, "accepted": code == 1}
        except ControlError as exc:
            # A timed-out write may already have moved the window. Never retry it.
            result = {"accepted": False, "uncertain": True,
                      "error": str(exc) + ". Результат движения неизвестен; проверьте окно"}
        self.record({**entry, "phase": "result", **result})
        return result

    def validate(self, window, action):
        set_command(window, action)


def handler(remote, token, port, page=None):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def respond(self, status, data, mime="application/json; charset=utf-8"):
            body = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            origin = f"http://127.0.0.1:{port}"
            return (self.headers.get("Host") == f"127.0.0.1:{port}"
                    and self.headers.get("Origin", origin) == origin
                    and hmac.compare_digest(self.headers.get("X-Window-Token", ""), token))

        def api(self, action=None):
            if not self.authorized():
                self.respond(403, {"error": "Нет доступа к этому пульту"})
                return
            if not remote.lock.acquire(blocking=False):
                self.respond(409, {"error": "ADB занят текущим запросом; действие не поставлено в очередь"})
                return
            try:
                self.respond(200, remote.snapshot() if action is None else remote.act(*action))
            except (ControlError, OSError) as exc:
                self.respond(503, {"error": str(exc)})
            finally:
                remote.lock.release()

        def do_GET(self):
            if self.path == "/":
                self.respond(200, (page or Path(__file__).with_suffix(".html")).read_bytes(), "text/html; charset=utf-8")
            elif self.path == "/api/state":
                self.api()
            else:
                self.respond(404, {"error": "Не найдено"})

        def do_POST(self):
            if not self.authorized():
                self.respond(403, {"error": "Нет доступа к этому пульту"})
                return
            if self.path != "/api/action":
                self.respond(404, {"error": "Не найдено"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 256 or self.headers.get("Content-Type") != "application/json":
                    raise ValueError()
                data = json.loads(self.rfile.read(size))
                if not isinstance(data, dict) or set(data) != {"window", "action"}:
                    raise ValueError()
                if not all(isinstance(value, str) for value in data.values()):
                    raise ValueError()
                remote.validate(data["window"], data["action"])
            except (ValueError, ControlError):
                self.respond(400, {"error": "Неизвестный контрол или действие"})
                return
            self.api((data["window"], data["action"]))
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("status", "serve"))
    parser.add_argument("--serial", default="127.0.0.1:5555")
    parser.add_argument("--port", type=int, default=8769)
    args = parser.parse_args()
    remote = Remote(args.serial)
    try:
        remote.verify()
        state = remote.snapshot()
        if args.mode == "status":
            print(json.dumps(state, ensure_ascii=False, indent=2))
            return
        token = secrets.token_urlsafe(32)
        server = ThreadingHTTPServer(("127.0.0.1", args.port), handler(remote, token, args.port))
        print(f"Окна {state['model']}: http://127.0.0.1:{args.port}/#{token}", flush=True)
        print("Команды отправляются только по нажатию. Ctrl-C завершает пульт.", flush=True)
        try:
            server.serve_forever()
        finally:
            server.server_close()
    except KeyboardInterrupt:
        pass
    except (ControlError, OSError) as exc:
        parser.exit(1, str(exc) + "\n")


if __name__ == "__main__":
    main()
