#!/usr/bin/env python3
"""Loopback-only administrator web UI for the Car ADB Gateway relay."""

from __future__ import annotations

import argparse
import base64
import binascii
import html
import json
import logging
import re
import secrets
import subprocess
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Mapping
from urllib.parse import urlsplit


ADMIN_ACTIONS = {"invite", "list", "status", "rename", "remove", "doctor"}
MAX_ADMIN_OUTPUT = 65_536
MAX_REQUEST_BODY = 4_096
DEVICE_ID_PATTERN = re.compile(r"^[a-f0-9]{16}$")
INVITE_TTLS = {900, 3_600, 86_400}
LOGGER = logging.getLogger("cag-web")


def local_access_policy(port: int) -> tuple[set[str], set[str]]:
    if type(port) is not int or not 1 <= port <= 65_535:
        raise ValueError("invalid web port")
    hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
    origins = {f"http://{host}" for host in hosts}
    return hosts, origins


DEFAULT_HOSTS, DEFAULT_ORIGINS = local_access_policy(8787)


class AdminCommandError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        expected: bool = False,
        unavailable: bool = False,
    ) -> None:
        super().__init__(message)
        self.expected = expected
        self.unavailable = unavailable


def decode_admin_result(output: bytes) -> dict[str, Any]:
    if len(output) > MAX_ADMIN_OUTPUT:
        raise AdminCommandError("relay admin response is too large", unavailable=True)
    try:
        line = output.decode("utf-8").strip()
        if not line.startswith("OK "):
            raise ValueError("missing OK response")
        encoded = line.removeprefix("OK ").strip()
        decoded = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        payload = json.loads(decoded)
    except (UnicodeDecodeError, ValueError, binascii.Error, json.JSONDecodeError) as exc:
        raise AdminCommandError("relay admin returned an invalid response", unavailable=True) from exc
    if not isinstance(payload, dict):
        raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
    return payload


class AdminClient:
    def __init__(
        self,
        run: Callable[..., Any] = subprocess.run,
        sudo_path: str = "/usr/bin/sudo",
        admin_path: str = "/usr/local/sbin/cag-admin",
    ) -> None:
        self.run = run
        self.sudo_path = sudo_path
        self.admin_path = admin_path

    def call(self, action: str, *arguments: str) -> dict[str, Any]:
        if action not in ADMIN_ACTIONS:
            raise AdminCommandError("unsupported relay admin action")
        command = [self.sudo_path, "-n", self.admin_path, action, *arguments]
        try:
            result = self.run(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=5,
                env={
                    "PATH": "/usr/sbin:/usr/bin:/sbin:/bin",
                    "LANG": "C.UTF-8",
                },
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise AdminCommandError("relay admin command timed out", unavailable=True) from exc
        if len(result.stdout) > MAX_ADMIN_OUTPUT or len(result.stderr) > MAX_ADMIN_OUTPUT:
            raise AdminCommandError("relay admin response is too large", unavailable=True)
        if result.returncode != 0:
            message = result.stderr.decode("utf-8", errors="replace").strip()
            if result.returncode == 2 and message.startswith("ERROR "):
                raise AdminCommandError(message.removeprefix("ERROR "), expected=True)
            raise AdminCommandError("relay admin command failed", unavailable=True)
        return decode_admin_result(result.stdout)


def parse_listener_ports(table: str) -> set[int]:
    ports: set[int] = set()
    for line in table.splitlines():
        fields = line.split()
        if len(fields) < 4 or fields[3] != "0A":
            continue
        try:
            address, encoded_port = fields[1].split(":", 1)
            port = int(encoded_port, 16)
        except (ValueError, IndexError):
            continue
        if address == "0100007F" and 1 <= port <= 65_535:
            ports.add(port)
    return ports


class ListenerProbe:
    def __init__(self, path: Path = Path("/proc/net/tcp")) -> None:
        self.path = path

    def ports(self) -> set[int] | None:
        try:
            table = self.path.read_text(encoding="ascii")
        except (OSError, UnicodeDecodeError):
            return None
        return parse_listener_ports(table)


class WebError(RuntimeError):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status


def normalize_web_label(value: Any) -> str:
    if not isinstance(value, str):
        raise WebError(400, "Название машины должно быть строкой")
    label = " ".join(value.replace("\x00", "").split()).strip()[:80]
    if not label:
        raise WebError(400, "Укажите название машины")
    return label


def require_device_id(value: str) -> str:
    if not DEVICE_ID_PATTERN.fullmatch(value):
        raise WebError(400, "Некорректный идентификатор машины")
    return value


def require_mapping(value: Any) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
    return value


class DashboardService:
    def __init__(
        self,
        admin: AdminClient,
        listeners: ListenerProbe,
        clock: Callable[[], float] = time.time,
    ) -> None:
        self.admin = admin
        self.listeners = listeners
        self.clock = clock

    def dashboard(self) -> dict[str, Any]:
        listed = require_mapping(self.admin.call("list"))
        doctor = require_mapping(self.admin.call("doctor"))
        raw_devices = listed.get("devices")
        if not isinstance(raw_devices, list):
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        ports = self.listeners.ports()
        devices = [self._summary(item, ports) for item in raw_devices]
        devices.sort(key=lambda item: (item["label"].casefold(), item["device_id"]))
        return {
            "doctor": self._doctor(doctor),
            "devices": devices,
            "updated_at": int(self.clock()),
        }

    def device(self, device_id: str) -> dict[str, Any]:
        require_device_id(device_id)
        status = require_mapping(self.admin.call("status", device_id))
        relay_port = status.get("relay_device_port")
        if type(relay_port) is not int:
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        ports = self.listeners.ports()
        return {
            "device_id": self._string(status, "device_id"),
            "label": self._string(status, "device_label"),
            "relay_port": relay_port,
            "endpoint_mode": self._optional_string(status, "endpoint_mode"),
            "endpoint_host": self._optional_string(status, "endpoint_host"),
            "enabled": self._boolean(status, "enabled"),
            "lease_expires_at": self._integer(status, "lease_expires_at"),
            "active_client_label": self._optional_string(status, "active_client_label"),
            "active_client_fingerprint": self._optional_string(
                status, "active_client_fingerprint"
            ),
            "pending_client_label": self._optional_string(status, "pending_client_label"),
            "pending_client_fingerprint": self._optional_string(
                status, "pending_client_fingerprint"
            ),
            "pending_expires_at": self._optional_integer(status, "pending_expires_at"),
            "online_status": self._online_status(ports, relay_port),
        }

    def invite(self, label: Any, ttl_seconds: Any) -> dict[str, Any]:
        normalized = normalize_web_label(label)
        if type(ttl_seconds) is not int or ttl_seconds not in INVITE_TTLS:
            raise WebError(400, "Выберите допустимый срок действия кода")
        result = require_mapping(
            self.admin.call(
                "invite",
                f"--label={normalized}",
                "--ttl",
                str(ttl_seconds),
            )
        )
        code = self._string(result, "code")
        expires_at = self._integer(result, "expires_at")
        return {"code": code, "expires_at": expires_at, "label": normalized}

    def rename(self, device_id: str, label: Any) -> dict[str, Any]:
        require_device_id(device_id)
        normalized = normalize_web_label(label)
        result = require_mapping(self.admin.call("rename", device_id, normalized))
        return {
            "device_id": self._string(result, "device_id"),
            "label": self._string(result, "label"),
        }

    def remove(self, device_id: str, confirm_label: Any) -> dict[str, Any]:
        require_device_id(device_id)
        if not isinstance(confirm_label, str):
            raise WebError(400, "Введите текущее название машины")
        status = require_mapping(self.admin.call("status", device_id))
        current_label = self._string(status, "device_label")
        if not secrets.compare_digest(confirm_label, current_label):
            raise WebError(409, "Введённое название не совпадает с текущим")
        result = require_mapping(self.admin.call("remove", device_id))
        if result.get("removed") is not True:
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        return {"device_id": device_id, "removed": True}

    def _summary(
        self,
        value: Any,
        ports: set[int] | None,
    ) -> dict[str, Any]:
        item = require_mapping(value)
        relay_port = self._integer(item, "relay_port")
        return {
            "device_id": self._string(item, "device_id"),
            "label": self._string(item, "label"),
            "enabled": self._boolean(item, "enabled"),
            "relay_port": relay_port,
            "lease_expires_at": self._integer(item, "lease_expires_at"),
            "client_label": self._optional_string(item, "client_label"),
            "online_status": self._online_status(ports, relay_port),
        }

    @staticmethod
    def _online_status(ports: set[int] | None, relay_port: int) -> str:
        if ports is None:
            return "unknown"
        return "online" if relay_port in ports else "offline"

    @staticmethod
    def _doctor(value: Mapping[str, Any]) -> dict[str, Any]:
        fields = (
            "ok",
            "version",
            "devices",
            "clients",
            "grants",
            "pending",
            "codes",
            "orphaned_clients",
            "expired_devices",
            "next_port",
        )
        result = {field: value.get(field) for field in fields}
        if type(result["ok"]) is not bool or any(
            type(result[field]) is not int for field in fields if field != "ok"
        ):
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        return result

    @staticmethod
    def _string(value: Mapping[str, Any], field: str) -> str:
        result = value.get(field)
        if not isinstance(result, str):
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        return result

    @staticmethod
    def _optional_string(value: Mapping[str, Any], field: str) -> str | None:
        result = value.get(field)
        if result is not None and not isinstance(result, str):
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        return result

    @staticmethod
    def _integer(value: Mapping[str, Any], field: str) -> int:
        result = value.get(field)
        if type(result) is not int:
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        return result

    @staticmethod
    def _optional_integer(value: Mapping[str, Any], field: str) -> int | None:
        result = value.get(field)
        if result is not None and type(result) is not int:
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        return result

    @staticmethod
    def _boolean(value: Mapping[str, Any], field: str) -> bool:
        result = value.get(field)
        if type(result) is not bool:
            raise AdminCommandError("relay admin returned an invalid response", unavailable=True)
        return result


@dataclass(frozen=True)
class Response:
    status: int
    body: bytes
    headers: dict[str, str]


class CagWebApp:
    def __init__(
        self,
        service: DashboardService,
        *,
        csrf_token: str | None = None,
        hosts: set[str] | None = None,
        origins: set[str] | None = None,
        logger: logging.Logger = LOGGER,
    ) -> None:
        self.service = service
        self.csrf_token = csrf_token or secrets.token_urlsafe(32)
        self.hosts = hosts or set(DEFAULT_HOSTS)
        self.origins = origins or set(DEFAULT_ORIGINS)
        self.logger = logger

    def handle(
        self,
        method: str,
        path: str,
        headers: Mapping[str, str],
        body: bytes,
    ) -> Response:
        normalized_headers = {key.lower(): value for key, value in headers.items()}
        nonce = secrets.token_urlsafe(18)
        try:
            if normalized_headers.get("host") not in self.hosts:
                raise WebError(403, "Недопустимый Host")
            route = urlsplit(path).path
            if method == "GET":
                return self._get(route, nonce)
            if method == "POST":
                self._authorize_mutation(normalized_headers)
                if len(body) > MAX_REQUEST_BODY:
                    raise WebError(413, "Запрос слишком большой")
                if normalized_headers.get("content-type", "").split(";", 1)[0].strip() != "application/json":
                    raise WebError(400, "Ожидается JSON")
                payload = self._payload(body)
                return self._post(route, payload, nonce)
            raise WebError(405, "Метод не поддерживается")
        except WebError as exc:
            return self._json(exc.status, {"error": str(exc)}, nonce)
        except AdminCommandError as exc:
            status, message = self._admin_error(exc)
            self.logger.warning("action=request result=error status=%s", status)
            return self._json(status, {"error": message}, nonce)
        except Exception:
            self.logger.exception("action=request result=error status=500")
            return self._json(500, {"error": "Внутренняя ошибка панели"}, nonce)

    def _get(self, route: str, nonce: str) -> Response:
        if route == "/":
            return self._html(200, render_dashboard(self.service.dashboard(), self.csrf_token, nonce), nonce)
        if route == "/healthz":
            return self._json(200, {"ok": True}, nonce)
        if route == "/api/dashboard":
            return self._json(200, self.service.dashboard(), nonce)
        match = re.fullmatch(r"/api/devices/([a-f0-9]{16})", route)
        if match:
            return self._json(200, self.service.device(match.group(1)), nonce)
        if route == "/api/invites" or re.fullmatch(
            r"/api/devices/[a-f0-9]{16}/(?:rename|remove)", route
        ):
            raise WebError(405, "Для изменения состояния используйте POST")
        raise WebError(404, "Страница не найдена")

    def _post(self, route: str, payload: Mapping[str, Any], nonce: str) -> Response:
        if route == "/api/invites":
            self._require_fields(payload, {"label", "ttl_seconds"})
            result = self.service.invite(payload["label"], payload["ttl_seconds"])
            self.logger.info("action=invite result=ok")
            return self._json(201, result, nonce)
        match = re.fullmatch(r"/api/devices/([a-f0-9]{16})/(rename|remove)", route)
        if not match:
            raise WebError(404, "Страница не найдена")
        device_id, action = match.groups()
        if action == "rename":
            self._require_fields(payload, {"label"})
            result = self.service.rename(device_id, payload["label"])
        else:
            self._require_fields(payload, {"confirm_label"})
            result = self.service.remove(device_id, payload["confirm_label"])
        self.logger.info("action=%s result=ok device_id=%s", action, device_id)
        return self._json(200, result, nonce)

    def _authorize_mutation(self, headers: Mapping[str, str]) -> None:
        if headers.get("origin") not in self.origins:
            raise WebError(403, "Недопустимый Origin")
        supplied = headers.get("x-cag-csrf", "")
        if not secrets.compare_digest(supplied, self.csrf_token):
            raise WebError(403, "Недопустимый CSRF-токен")

    @staticmethod
    def _payload(body: bytes) -> Mapping[str, Any]:
        try:
            value = json.loads(body)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise WebError(400, "Некорректный JSON") from exc
        if not isinstance(value, dict):
            raise WebError(400, "JSON должен быть объектом")
        return value

    @staticmethod
    def _require_fields(payload: Mapping[str, Any], expected: set[str]) -> None:
        if set(payload) != expected:
            raise WebError(400, "Некорректный набор полей")

    @staticmethod
    def _admin_error(error: AdminCommandError) -> tuple[int, str]:
        if error.unavailable:
            return 503, "Relay временно недоступен"
        if "unknown device" in str(error):
            return 404, "Машина не найдена"
        if error.expected:
            return 409, "Состояние relay изменилось; обновите страницу"
        return 400, "Некорректная административная команда"

    def _json(self, status: int, payload: Mapping[str, Any], nonce: str) -> Response:
        return self._response(
            status,
            json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
            "application/json; charset=utf-8",
            nonce,
        )

    def _html(self, status: int, content: str, nonce: str) -> Response:
        return self._response(status, content.encode("utf-8"), "text/html; charset=utf-8", nonce)

    @staticmethod
    def _response(
        status: int,
        body: bytes,
        content_type: str,
        nonce: str,
    ) -> Response:
        return Response(
            status,
            body,
            {
                "Content-Type": content_type,
                "Content-Length": str(len(body)),
                "Cache-Control": "no-store",
                "Content-Security-Policy": (
                    "default-src 'none'; "
                    f"style-src 'nonce-{nonce}'; script-src 'nonce-{nonce}'; "
                    "connect-src 'self'; form-action 'self'; frame-ancestors 'none'; "
                    "base-uri 'none'"
                ),
                "X-Frame-Options": "DENY",
                "X-Content-Type-Options": "nosniff",
                "Referrer-Policy": "no-referrer",
            },
        )


def render_dashboard(data: Mapping[str, Any], csrf_token: str, nonce: str) -> str:
    doctor = require_mapping(data["doctor"])
    devices = data["devices"]
    rows = "".join(render_device_row(device) for device in devices)
    health_class = "ok" if doctor["ok"] else "bad"
    health_text = "Relay исправен" if doctor["ok"] else "Требуется проверка"
    initial_json = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    initial_json = (
        initial_json.replace("&", "\\u0026")
        .replace("<", "\\u003c")
        .replace(">", "\\u003e")
    )
    return f"""<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <meta name="cag-csrf" content="{html.escape(csrf_token, quote=True)}">
  <title>CAG Relay Admin</title>
  <style nonce="{nonce}">
    :root {{ color-scheme: light; font-family: Inter, ui-sans-serif, system-ui, sans-serif; background:#f4f6f8; color:#17202a; }}
    * {{ box-sizing:border-box; }} body {{ margin:0; }}
    main {{ max-width:1180px; margin:0 auto; padding:32px 20px 64px; }}
    header {{ display:flex; gap:20px; justify-content:space-between; align-items:center; margin-bottom:24px; }}
    h1 {{ margin:0; font-size:28px; }} .muted {{ color:#667085; }}
    button, select, input {{ font:inherit; }} button {{ border:0; border-radius:9px; padding:10px 14px; cursor:pointer; }}
    .primary {{ background:#1667d9; color:white; }} .secondary {{ background:#e8eef7; color:#173a64; }} .danger {{ background:#b42318; color:white; }}
    .summary {{ display:flex; gap:12px; align-items:center; margin-bottom:16px; }}
    .badge {{ display:inline-flex; align-items:center; border-radius:999px; padding:6px 10px; font-size:13px; font-weight:650; }}
    .badge.ok,.status-online {{ background:#dff7e7; color:#116329; }} .badge.bad {{ background:#fee4e2; color:#912018; }}
    .status-offline {{ background:#eef1f5; color:#4b5563; }} .status-unknown {{ background:#fff2cc; color:#7a4d00; }}
    .panel {{ background:white; border:1px solid #e2e7ee; border-radius:14px; overflow:hidden; box-shadow:0 8px 24px rgba(16,24,40,.05); }}
    table {{ width:100%; border-collapse:collapse; }} th,td {{ padding:14px 16px; text-align:left; border-bottom:1px solid #edf0f4; vertical-align:middle; }}
    th {{ color:#667085; font-size:12px; text-transform:uppercase; letter-spacing:.04em; }} tr:last-child td {{ border-bottom:0; }}
    .label {{ font-weight:700; }} .id {{ font-family:ui-monospace, SFMono-Regular, monospace; font-size:12px; color:#667085; }}
    .actions {{ display:flex; gap:7px; flex-wrap:wrap; }} .empty {{ padding:40px; text-align:center; color:#667085; }}
    .alert {{ display:none; margin:0 0 16px; padding:12px 14px; border-radius:9px; background:#fee4e2; color:#912018; }}
    dialog {{ border:0; border-radius:14px; padding:0; width:min(520px,calc(100vw - 32px)); box-shadow:0 24px 80px rgba(0,0,0,.3); }}
    dialog::backdrop {{ background:rgba(16,24,40,.52); }} .dialog-body {{ padding:24px; }} .dialog-actions {{ display:flex; justify-content:flex-end; gap:9px; margin-top:22px; }}
    label.field {{ display:grid; gap:7px; margin:14px 0; font-weight:650; }} input,select {{ width:100%; border:1px solid #cfd6df; border-radius:9px; padding:10px 12px; background:white; }}
    code.code {{ display:block; font-size:28px; letter-spacing:.08em; text-align:center; padding:18px; background:#f2f5f9; border-radius:10px; margin:16px 0; }}
    dl {{ display:grid; grid-template-columns:170px 1fr; gap:9px 16px; }} dt {{ color:#667085; }} dd {{ margin:0; overflow-wrap:anywhere; }}
    @media (max-width:800px) {{ main {{ padding:20px 12px 40px; }} header {{ align-items:flex-start; }} table,thead,tbody,tr,th,td {{ display:block; }} thead {{ display:none; }} tr {{ padding:12px; border-bottom:1px solid #edf0f4; }} td {{ border:0; padding:5px 4px; }} td::before {{ content:attr(data-title); color:#667085; display:inline-block; min-width:120px; }} .actions {{ margin-top:8px; }} }}
  </style>
</head>
<body>
<main>
  <header><h1>Relay admin</h1><button id="add" class="primary">Добавить машину</button></header>
  <div id="alert" class="alert" role="alert"></div>
  <section class="summary"><span id="health" class="badge {health_class}">{health_text}</span><span id="counts" class="muted">Машин: {doctor['devices']}</span><span id="updated" class="muted"></span></section>
  <section class="panel"><table><thead><tr><th>Машина</th><th>Связь</th><th>Доступ</th><th>Lease</th><th>Компьютер</th><th></th></tr></thead><tbody id="devices">{rows}</tbody></table><div id="empty" class="empty" {'hidden' if devices else ''}>Зарегистрированных машин пока нет</div></section>
</main>
<dialog id="invite"><form id="invite-form" method="dialog" class="dialog-body"><h2>Добавить машину</h2><div id="invite-error" class="alert" role="alert"></div><label class="field">Название<input id="invite-label" maxlength="80" required placeholder="Например, основная машина"></label><label class="field">Код действует<select id="invite-ttl"><option value="900">15 минут</option><option value="3600" selected>60 минут</option><option value="86400">24 часа</option></select></label><div class="dialog-actions"><button id="cancel-invite" type="button" class="secondary">Отмена</button><button id="create-invite" type="submit" class="primary">Создать код</button></div></form></dialog>
<dialog id="result"><div class="dialog-body"><h2>Код подключения</h2><div id="result-label" class="muted"></div><code id="result-code" class="code"></code><div id="result-expiry" class="muted"></div><div id="result-message" class="muted" role="status"></div><div class="dialog-actions"><button id="copy-code" type="button" class="secondary">Копировать</button><button id="close-result" type="button" class="primary">Закрыть</button></div></div></dialog>
<dialog id="details"><div class="dialog-body"><h2 id="details-title">Детали машины</h2><dl id="details-list"></dl><div class="dialog-actions"><button id="close-details" class="primary">Закрыть</button></div></div></dialog>
<script nonce="{nonce}">
const csrf=document.querySelector('meta[name="cag-csrf"]').content;
const tbody=document.getElementById('devices'), alertBox=document.getElementById('alert');
const inviteDialog=document.getElementById('invite'), inviteForm=document.getElementById('invite-form'), inviteError=document.getElementById('invite-error');
const createInvite=document.getElementById('create-invite'), cancelInvite=document.getElementById('cancel-invite');
const resultDialog=document.getElementById('result'), resultMessage=document.getElementById('result-message');
let invitePending=false;
function el(tag,text,cls){{const node=document.createElement(tag);if(text!==undefined)node.textContent=text;if(cls)node.className=cls;return node;}}
function leaseText(epoch){{const ms=epoch*1000-Date.now();if(ms<=0)return 'истёк';const hours=Math.ceil(ms/3600000);return hours<48?`${{hours}} ч`:`${{Math.ceil(hours/24)}} д`;}}
function statusText(value){{return value==='online'?'На связи':value==='offline'?'Не подключена':'Неизвестно';}}
function row(device){{const tr=document.createElement('tr');tr.dataset.deviceId=device.device_id;tr.dataset.label=device.label;
  const name=document.createElement('td');name.dataset.title='Машина';name.append(el('div',device.label,'label'),el('div',device.device_id.slice(0,8),'id'));tr.append(name);
  const online=el('span',statusText(device.online_status),`badge status-${{device.online_status}}`);const c1=document.createElement('td');c1.dataset.title='Связь';c1.append(online);tr.append(c1);
  const c2=el('td',device.enabled?'Разрешён':'Выключен');c2.dataset.title='Доступ';tr.append(c2);
  const c3=el('td',leaseText(device.lease_expires_at));c3.dataset.title='Lease';tr.append(c3);
  const c4=el('td',device.client_label||'Не назначен');c4.dataset.title='Компьютер';tr.append(c4);
  const actions=document.createElement('td');actions.className='actions';actions.append(action('details','Детали'),action('rename','Переименовать'),action('remove','Удалить','danger'));tr.append(actions);return tr;}}
function action(kind,text,cls='secondary'){{const b=el('button',text,cls);b.type='button';b.dataset.action=kind;return b;}}
function render(data){{tbody.replaceChildren(...data.devices.map(row));document.getElementById('empty').hidden=data.devices.length>0;const health=document.getElementById('health');health.textContent=data.doctor.ok?'Relay исправен':'Требуется проверка';health.className=`badge ${{data.doctor.ok?'ok':'bad'}}`;document.getElementById('counts').textContent=`Машин: ${{data.doctor.devices}}`;document.getElementById('updated').textContent=`Обновлено: ${{new Date(data.updated_at*1000).toLocaleTimeString('ru-RU')}}`;}}
async function api(path,options={{}}){{const response=await fetch(path,options);const data=await response.json();if(!response.ok)throw new Error(data.error||'Ошибка relay');return data;}}
async function mutate(path,body){{return api(path,{{method:'POST',headers:{{'Content-Type':'application/json','X-CAG-CSRF':csrf}},body:JSON.stringify(body)}});}}
function showError(error){{alertBox.textContent=error.message;alertBox.style.display='block';}} function clearError(){{alertBox.style.display='none';}}
function clearInviteError(){{inviteError.textContent='';inviteError.style.display='none';}}
function showInviteError(error){{inviteError.textContent=error.message;inviteError.style.display='block';}}
function setInvitePending(value){{invitePending=value;createInvite.disabled=value;cancelInvite.disabled=value;}}
async function refresh(){{if(document.hidden||document.querySelector('dialog[open]'))return;try{{render(await api('/api/dashboard'));clearError();}}catch(error){{showError(error);}}}}
document.getElementById('add').onclick=()=>{{inviteForm.reset();clearInviteError();inviteDialog.showModal();}};
cancelInvite.onclick=()=>{{if(!invitePending)inviteDialog.close('cancel');}};
inviteDialog.oncancel=event=>{{if(invitePending)event.preventDefault();}};
inviteForm.onsubmit=async event=>{{event.preventDefault();if(invitePending)return;const label=document.getElementById('invite-label').value;const ttl=Number(document.getElementById('invite-ttl').value);clearInviteError();setInvitePending(true);try{{const data=await mutate('/api/invites',{{label,ttl_seconds:ttl}});inviteDialog.close('created');document.getElementById('result-label').textContent=data.label;document.getElementById('result-code').textContent=data.code;document.getElementById('result-expiry').textContent=`Действует до ${{new Date(data.expires_at*1000).toLocaleString('ru-RU')}}`;resultMessage.textContent='';resultDialog.showModal();clearError();}}catch(error){{showInviteError(error);}}finally{{setInvitePending(false);}}}};
async function copyInviteCode(){{try{{await navigator.clipboard.writeText(document.getElementById('result-code').textContent);resultMessage.textContent='Код скопирован';}}catch(error){{resultMessage.textContent='Код не удалось скопировать';}}}}
document.getElementById('copy-code').onclick=copyInviteCode;
document.getElementById('close-result').onclick=()=>resultDialog.close();document.getElementById('close-details').onclick=()=>document.getElementById('details').close();
resultDialog.addEventListener('close',()=>{{document.getElementById('result-code').textContent='';resultMessage.textContent='';}});
tbody.onclick=async event=>{{const button=event.target.closest('button[data-action]');if(!button||button.disabled)return;const tr=button.closest('tr'),id=tr.dataset.deviceId,label=tr.dataset.label;button.disabled=true;try{{if(button.dataset.action==='rename'){{const next=prompt('Новое название машины',label);if(next!==null){{await mutate(`/api/devices/${{id}}/rename`,{{label:next}});await refresh();}}}}else if(button.dataset.action==='remove'){{const confirmLabel=prompt(`Для удаления введите название: ${{label}}`,'');if(confirmLabel!==null){{await mutate(`/api/devices/${{id}}/remove`,{{confirm_label:confirmLabel}});await refresh();}}}}else{{const data=await api(`/api/devices/${{id}}`);document.getElementById('details-title').textContent=data.label;const fields=[['Device ID',data.device_id],['Relay port',data.relay_port],['Endpoint',`${{data.endpoint_mode||'unknown'}} ${{data.endpoint_host||''}}`],['Online',statusText(data.online_status)],['Enabled',data.enabled?'Да':'Нет'],['Lease',new Date(data.lease_expires_at*1000).toLocaleString('ru-RU')],['Активный компьютер',data.active_client_label||'—'],['Fingerprint',data.active_client_fingerprint||'—'],['Pending',data.pending_client_label||'—']];const dl=document.getElementById('details-list');dl.replaceChildren(...fields.flatMap(([k,v])=>[el('dt',k),el('dd',String(v))]));document.getElementById('details').showModal();}}clearError();}}catch(error){{showError(error);}}finally{{button.disabled=false;}}}};
render({initial_json});setInterval(refresh,15000);document.addEventListener('visibilitychange',refresh);
</script>
</body></html>"""


def render_device_row(device: Mapping[str, Any]) -> str:
    label = html.escape(str(device["label"]), quote=True)
    device_id = html.escape(str(device["device_id"]), quote=True)
    online = str(device["online_status"])
    status = {"online": "На связи", "offline": "Не подключена", "unknown": "Неизвестно"}[online]
    enabled = "Разрешён" if device["enabled"] else "Выключен"
    client = html.escape(str(device["client_label"] or "Не назначен"))
    return (
        f'<tr data-device-id="{device_id}" data-label="{label}">'
        f'<td data-title="Машина"><div class="label">{label}</div><div class="id">{device_id[:8]}</div></td>'
        f'<td data-title="Связь"><span class="badge status-{online}">{status}</span></td>'
        f'<td data-title="Доступ">{enabled}</td>'
        f'<td data-title="Lease" data-lease="{int(device["lease_expires_at"])}">—</td>'
        f'<td data-title="Компьютер">{client}</td>'
        '<td class="actions"><button type="button" data-action="details" class="secondary">Детали</button>'
        '<button type="button" data-action="rename" class="secondary">Переименовать</button>'
        '<button type="button" data-action="remove" class="danger">Удалить</button></td></tr>'
    )


class CagThreadingHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def create_server(app: CagWebApp, host: str, port: int) -> ThreadingHTTPServer:
    if host != "127.0.0.1":
        raise ValueError("cag-web must bind IPv4 loopback")
    if type(port) is not int or not 0 <= port <= 65_535:
        raise ValueError("invalid web port")

    class Handler(BaseHTTPRequestHandler):
        server_version = "cag-web"
        sys_version = ""

        def do_GET(self) -> None:  # noqa: N802
            self._dispatch()

        def do_POST(self) -> None:  # noqa: N802
            self._dispatch()

        def do_PUT(self) -> None:  # noqa: N802
            self._dispatch()

        def do_DELETE(self) -> None:  # noqa: N802
            self._dispatch()

        def _dispatch(self) -> None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = MAX_REQUEST_BODY + 1
            if length < 0 or length > MAX_REQUEST_BODY:
                body = b"x" * (MAX_REQUEST_BODY + 1)
                self.close_connection = True
            else:
                body = self.rfile.read(length)
            response = app.handle(self.command, self.path, dict(self.headers.items()), body)
            self.send_response(response.status)
            for key, value in response.headers.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(response.body)

        def log_message(self, _format: str, *_arguments: Any) -> None:
            return

    return CagThreadingHTTPServer((host, port), Handler)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    hosts, origins = local_access_policy(args.port)
    app = CagWebApp(
        DashboardService(AdminClient(), ListenerProbe()),
        hosts=hosts,
        origins=origins,
    )
    server = create_server(app, args.listen, args.port)
    LOGGER.info("action=start result=ok listen=%s port=%s", args.listen, args.port)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
