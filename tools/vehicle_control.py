#!/usr/bin/env python3
"""Local ADB remote: windows, doors, seats, roof shades, lights and massage.

python3 tools/vehicle_control.py serve
Protocol and corpus references: docs/window-control-findings.md.
"""
import argparse
import datetime as dt
import json
import math
from pathlib import Path
import re
import secrets
import struct

import vehicle_windows as w

CATALOG = json.loads(Path(__file__).with_name("vehicle_controls.json").read_text())
DICAR_PATH = "/system/priv-app/DiCarServer/DiCarServer.apk"
DICAR_HASH = "ef8dc5e45f555633b4476637a05560522534a605e79d9d5b70df5c55caf142d6"
PULSE_SECONDS = 0.3
MASSAGE_MODES = {"off": 1, "on": 7, "wave": 2, "pulse": 3,
                 "stretch": 4, "waist": 5, "shoulders": 6}


def read_command(name):
    device, fid, kind = CATALOG["properties"][name]
    return f"service call autoservice {7 if kind == 'float' else 5} i32 {device} i32 {fid}"


def write_command(name, value):
    device, fid, _ = CATALOG["properties"][name]
    return f"service call autoservice 6 i32 {device} i32 {fid} i32 {value} null"


def pulse_script(start, stop):
    # This entire bounded sequence runs on Android. Browser/host timers are not
    # responsible for releasing the seat switch. No auto-repeat of start.
    return (f"finish() {{ trap - EXIT HUP INT TERM; echo @stop; {stop}; }}; "
            "trap finish EXIT; trap 'exit 1' HUP INT TERM; "
            f"echo @start; {start}; sleep {PULSE_SECONDS}")


def percentage(value):
    return value if isinstance(value, (int, float)) and math.isfinite(value) and 0 <= value <= 100 else None


class Remote(w.Remote):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.pending_stops = set()
        log = self.log_dir / "actions.jsonl"
        if log.exists():
            for line in log.read_text().splitlines():
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                target = row.get("target", "")
                if not target.startswith("seat."):
                    continue
                if row.get("phase") == "attempt" and row.get("action") != "stop":
                    self.pending_stops.add(target)
                elif row.get("stop_accepted") or (row.get("action") == "stop" and row.get("accepted")):
                    self.pending_stops.discard(target)

    def verify(self):
        super().verify()
        if self.identity[1] != "168":
            raise w.ControlError("Расширенный пульт проверен для профиля N9 (168)")
        if self.shell("sha256sum " + DICAR_PATH, timeout=15).split() != [DICAR_HASH, DICAR_PATH]:
            raise w.ControlError("DiCarServer отличается от исследованного корпуса")

    def read_properties(self):
        names = {CATALOG["locks"][key] for key in ("config", "thermal")}
        for spec in CATALOG["doors"].values():
            names.update(value for key, value in spec.items() if key != "set")
        for seat in CATALOG["seats"].values():
            for axis in seat["axes"].values():
                names.update(axis[key] for key in ("position", "config", "initialized") if axis[key])
            for spec in seat["comfort"].values():
                names.update((spec["config"], spec["state"]))
        for spec in CATALOG["shades"].values():
            names.update(spec[key] for key in ("position", "initialized", "thermal", "config"))
        for spec in CATALOG["lights"].values():
            names.update(spec["states"])
            names.update(name for name, allowed in spec["configs"])
        for spec in CATALOG["massages"].values():
            names.update(spec[key] for key in ("config", "mode", "level"))
        commands = {name: read_command(name) for name in sorted(names)}
        commands["park"] = w.get_command(1011, 89129008)
        output = self.shell(w.IDENTITY + "; " + "; ".join(f"echo @{key}; {cmd}" for key, cmd in commands.items()), timeout=8)
        parts = re.split(r"(?m)^@([A-Za-z0-9_]+)\s*\n", output)
        self.check_identity(parts[0].strip().splitlines())
        if parts[1::2] != list(commands):
            raise w.ControlError("Неполный ответ чтения автомобиля")
        values = {}
        for key, raw in zip(parts[1::2], parts[2::2]):
            try:
                value = w.parcel(raw)
                if key != "park" and CATALOG["properties"][key][2] == "float":
                    value = struct.unpack("!f", struct.pack("!I", value & 0xffffffff))[0]
                    value = percentage(value)
                elif value < 0:
                    value = None
                values[key] = value
            except w.ControlError:
                values[key] = None
        return values

    def snapshot(self):
        state = super().snapshot()
        values = self.read_properties()
        state["park"] = values["park"]
        state["time"] = dt.datetime.now().astimezone().isoformat(timespec="seconds")
        for window in state["windows"].values():
            window["blocked"] = w.blocked_reason(state, window)
        parked = None if state["park"] == 1 else "Управление доступно только в P"
        thermal = values[CATALOG["locks"]["thermal"]]
        config = values[CATALOG["locks"]["config"]]
        state["doors"] = {}
        for key, spec in CATALOG["doors"].items():
            door = {k: values[v] for k, v in spec.items() if k != "set"}
            door["name"] = w.WINDOWS[key][0]
            door["blocked"] = (parked or (None if config == 5 else "Не подтверждены четыре электродвери")
                               or (None if door["initialized"] == 1 else "Дверь не инициализирована")
                               or (None if door["position"] in (1, 2, 3) else "Положение двери неизвестно")
                               or (None if door["obstacle"] == 0 else "Препятствие или неизвестный датчик")
                               or (None if door["protection"] == 0 else "Защита привода или неизвестное состояние")
                               or (None if thermal == 0 else "Термозащита замков или неизвестное состояние"))
            state["doors"][key] = door
        state["locks"] = {"blocked": parked or (None if thermal == 0 else "Термозащита замков или неизвестное состояние")}
        state["seats"] = {}
        state["pending_stops"] = sorted(self.pending_stops)
        for key, spec in CATALOG["seats"].items():
            seat = {"name": spec["name"], "axes": {}, "comfort": {}}
            for axis, control in spec["axes"].items():
                available = values[control["config"]] in control["config_values"]
                position = percentage(values[control["position"]])
                initialized = values.get(control["initialized"])
                reason = (parked or ("Сначала остановите последнее кресло: ответ остановки не подтверждён" if self.pending_stops else None)
                          or (None if available else "Регулировка не подтверждена комплектацией")
                          or (None if position is not None else "Нет достоверного положения")
                          or (None if initialized == 1 else "Инициализация не подтверждена"))
                seat["axes"][axis] = {"name": control["name"], "labels": control["labels"],
                                     "supported": available, "percent": position, "blocked": reason}
            for kind, control in spec["comfort"].items():
                raw = values[control["state"]]
                level = raw - 1 if raw in (1, 2, 3, 4) else None
                available = values[control["config"]] == 3
                seat["comfort"][kind] = {"supported": available, "level": level,
                                         "blocked": parked or (None if available else "Три уровня не подтверждены комплектацией")
                                         or (None if level is not None else "Состояние неизвестно")}
            state["seats"][key] = seat
        state["shades"], state["lights"], state["massages"] = {}, {}, {}
        for key, spec in CATALOG["shades"].items():
            position = percentage(values[spec["position"]])
            reason = (parked or (None if values[spec["config"]] == 8 else "Две шторки не подтверждены комплектацией")
                      or (None if values[spec["initialized"]] == 1 else "Шторка не инициализирована")
                      or (None if values[spec["thermal"]] == 0 else "Термозащита или неизвестное состояние")
                      or (None if position is not None else "Положение шторки неизвестно"))
            state["shades"][key] = {"name": spec["name"], "percent": position, "blocked": reason}
        for key, spec in CATALOG["lights"].items():
            supported = all(values[name] in allowed for name, allowed in spec["configs"])
            decoded = [spec["state_values"].get(str(values[name])) for name in spec["states"]]
            current = decoded[0] if all(value == decoded[0] for value in decoded) else None
            state["lights"][key] = {"name": spec["name"], "supported": supported, "state": current,
                                    "actions": list(spec["values"]), "blocked": parked
                                    or (None if supported else "Функция не подтверждена комплектацией")
                                    or (None if current is not None else "Состояние света неизвестно или различается")}
        for key, spec in CATALOG["massages"].items():
            mode, level = values[spec["mode"]], values[spec["level"]]
            supported = values[spec["config"]] == 1
            # Off reports mode=1 / level=0 on this N9. Unknown values must not
            # become an apparently valid off state, as the stock UI defaults do.
            valid = mode in range(1, 8) and level in range(0, 5)
            enabled = False if mode == 1 or level == 4 else True if mode in range(2, 7) and level in (1, 2, 3) else None
            state["massages"][key] = {"supported": supported, "mode": mode if valid else None,
                                      "level": level if valid else None, "enabled": enabled if valid else None,
                                      "blocked": parked or (None if supported else "Массаж не подтверждён комплектацией")
                                      or (None if valid else "Состояние массажа неизвестно")}
        return state

    def validate(self, target, action):
        if target in w.WINDOWS:
            return super().validate(target, action)
        parts = target.split(".")
        valid = target == "locks" and action in ("lock", "unlock")
        if len(parts) == 2 and parts[0] == "door":
            valid = parts[1] in CATALOG["doors"] and action in w.ACTIONS
        if len(parts) == 2 and parts[0] in ("shade", "light"):
            group = CATALOG["shades" if parts[0] == "shade" else "lights"]
            valid = parts[1] in group and action in group[parts[1]]["values"]
        if len(parts) == 2 and parts[0] == "massage":
            valid = parts[1] in CATALOG["massages"] and action in (*MASSAGE_MODES, "level1", "level2", "level3")
        if len(parts) == 3 and parts[1] in CATALOG["seats"]:
            if parts[0] == "seat":
                valid = parts[2] in CATALOG["seats"][parts[1]]["axes"] and action in ("plus", "minus", "stop")
            elif parts[0] == "comfort":
                valid = parts[2] in CATALOG["seats"][parts[1]]["comfort"] and action in ("0", "1", "2", "3")
        if not valid:
            raise w.ControlError("Неизвестный контрол или действие")

    def act(self, target, action):
        self.validate(target, action)
        if target in w.WINDOWS:
            return super().act(target, action)
        parts = target.split(".")
        if action == "stop":
            self.check_identity(self.shell(w.IDENTITY).splitlines())
        else:
            state = self.snapshot()
            if target == "locks":
                reason = state["locks"]["blocked"]
            elif parts[0] == "door":
                reason = state["doors"][parts[1]]["blocked"]
            elif parts[0] in ("shade", "light", "massage"):
                section = {"shade": "shades", "light": "lights", "massage": "massages"}[parts[0]]
                reason = state[section][parts[1]]["blocked"]
            else:
                section = "axes" if parts[0] == "seat" else "comfort"
                reason = state["seats"][parts[1]][section][parts[2]]["blocked"]
            if reason:
                raise w.ControlError(reason)
        pulse = parts[0] == "seat" and action != "stop"
        if target == "locks":
            prop, value = CATALOG["locks"]["set"], 2 if action == "lock" else 1
        elif parts[0] == "door":
            prop, value = CATALOG["doors"][parts[1]]["set"], w.ACTIONS[action]
        elif parts[0] == "seat":
            prop = CATALOG["seats"][parts[1]]["axes"][parts[2]]["set"]
            value = {"plus": 1, "minus": 2, "stop": 0}[action]
        elif parts[0] in ("shade", "light"):
            control = CATALOG["shades" if parts[0] == "shade" else "lights"][parts[1]]
            prop, value = control["set"], control["values"][action]
        elif parts[0] == "massage":
            control = CATALOG["massages"][parts[1]]
            if action.startswith("level"):
                prop, value = control["set_level"], int(action[-1])
            else:
                prop, value = control["set_mode"], MASSAGE_MODES[action]
        else:
            control = CATALOG["seats"][parts[1]]["comfort"][parts[2]]
            prop, value = control["set"], control["values"][int(action)]
        command = write_command(prop, value)
        if pulse:
            command = pulse_script(command, write_command(prop, 0))
        entry = {"time": dt.datetime.now(dt.timezone.utc).isoformat(), "target": target,
                 "action": action, "command": command, "phase": "attempt"}
        self.record(entry)
        if pulse:
            self.pending_stops.add(target)
        try:
            raw = self.shell(command)
            if pulse:
                parts_out = re.split(r"(?m)^@(start|stop)\s*\n", raw)
                if parts_out[0].strip() or parts_out[1::2] != ["start", "stop"]:
                    raise w.ControlError("Неполный ответ короткого шага")
                start_code, stop_code = (w.set_result(text) for text in parts_out[2::2])
                result = {"code": start_code, "stop_code": stop_code, "stop_accepted": stop_code == 1,
                          "accepted": start_code == stop_code == 1, "raw": raw}
            else:
                code = w.set_result(raw)
                result = {"code": code, "accepted": code == 1, "raw": raw}
        except w.ControlError as exc:
            result = {"accepted": False, "uncertain": True, "error": str(exc) + ". Исход неизвестен; проверьте машину"}
        # Retain the recovery latch until the STOP response is both received and logged.
        self.record({**entry, "phase": "result", **result})
        if result.get("stop_accepted") or (action == "stop" and result["accepted"]):
            self.pending_stops.discard(target)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("status", "serve"))
    parser.add_argument("--serial", default="127.0.0.1:5555")
    parser.add_argument("--port", default=8769, type=int)
    args = parser.parse_args()
    remote = Remote(args.serial)
    try:
        remote.verify()
        state = remote.snapshot()
        if args.mode == "status":
            print(json.dumps(state, ensure_ascii=False, indent=2))
            return
        token = secrets.token_urlsafe(32)
        server = w.ThreadingHTTPServer(("127.0.0.1", args.port), w.handler(remote, token, args.port, Path(__file__).with_suffix(".html")))
        print(f"Пульт {state['model']}: http://127.0.0.1:{args.port}/#{token}", flush=True)
        try:
            server.serve_forever()
        finally:
            server.server_close()
    except KeyboardInterrupt:
        pass
    except (w.ControlError, OSError) as exc:
        parser.exit(1, str(exc) + "\n")


if __name__ == "__main__":
    main()
