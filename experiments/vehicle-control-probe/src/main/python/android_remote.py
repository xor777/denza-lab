"""Private on-device adapter. No HTTP listener and no arbitrary-shell JS API."""
import json
import os
import threading
from pathlib import Path
import vehicle_control as control
import vehicle_windows as windows

class AndroidRemote(control.Remote):
    def __init__(self, transport, directory, identity=""):
        self.transport = transport
        self.binding_identity = identity
        self.requested_binding = (identity, transport)
        super().__init__("local", log_dir=Path(directory))
        self.verified = False

    def shell(self, command, timeout=5):
        try:
            return str(self.transport.shell(command, int(timeout * 1000))).strip()
        except Exception as error:
            raise windows.ControlError("Нет локального доступа ADB; команда не повторялась") from error

    def record(self, item):
        # A successful fsync precedes START; latch replay survives process death.
        self.log_dir.mkdir(parents=True, exist_ok=True)
        with (self.log_dir / "actions.jsonl").open("a") as stream:
            stream.write(json.dumps(item, ensure_ascii=False) + "\n")
            stream.flush()
            os.fsync(stream.fileno())

    def request(self, payload):
        if not self.lock.acquire(blocking=False):
            return json.dumps({"error": "ADB занят; действие не поставлено в очередь"})
        try:
            # Only the operation owner may swap transport, never Activity creation.
            # An in-flight read/START/STOP keeps the exact client it began with.
            identity, transport = self.requested_binding
            if identity != self.binding_identity:
                self.transport = transport
                self.binding_identity = identity
                self.verified = False
            if not self.verified:
                self.verify()
                self.verified = True
            if payload:
                data = json.loads(payload)
                if not isinstance(data, dict) or set(data) != {"window", "action"} or not all(isinstance(v, str) for v in data.values()):
                    raise windows.ControlError("Неизвестный контрол или действие")
                return json.dumps(self.act(data["window"], data["action"]), ensure_ascii=False)
            return json.dumps(self.snapshot(), ensure_ascii=False)
        except Exception as error:
            return json.dumps({"error": str(error)}, ensure_ascii=False)
        finally:
            self.lock.release()

_instance = None

def create(transport, directory, identity=""):
    global _instance
    if _instance is None:
        _instance = AndroidRemote(transport, directory, identity)
    elif _instance.log_dir != Path(directory):
        raise windows.ControlError("Каталог журнала пульта изменился")
    elif identity != _instance.requested_binding[0]:
        # Atomic tuple publication only: no waiting and no network on the UI thread.
        # Applied by the next request under the shared operation lock, preserving STOP.
        _instance.requested_binding = (identity, transport)
    return _instance

def page():
    html = Path(control.__file__).with_suffix(".html").read_text()
    start = html.index("async function api(data)")
    end = html.index("\nfunction refresh()", start)
    html = html[:start] + """async function api(data){const body=JSON.parse(AndroidRemote.request(data?JSON.stringify(data):''));if(body.error&&!('accepted' in body)){throw new Error(body.error)}return body}""" + html[end:]
    return html.replace("const token=location.hash.slice(1)", "const token='android-local'")
