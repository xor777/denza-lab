#!/usr/bin/env python3
"""Atomic state and authorization policy for the Car ADB Gateway relay."""

from __future__ import annotations

import argparse
import base64
import binascii
import contextlib
import fcntl
import hashlib
import ipaddress
import json
import os
import re
import secrets
import sys
import tempfile
import time
from pathlib import Path
from typing import Any, Callable, Iterator


DEFAULT_ROOT = Path("/opt/cag/state")
CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
CODE_PATTERN = re.compile(r"^[2-9A-HJ-NP-Z]{4}-[2-9A-HJ-NP-Z]{4}$")
DEVICE_ID_PATTERN = re.compile(r"^[a-f0-9]{16}$")
REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{8,80}$")
FINGERPRINT_PATTERN = re.compile(r"^SHA256:[A-Za-z0-9+/]{20,60}$")
ALLOWED_KEY_TYPES = {
    "ssh-ed25519",
    "ssh-rsa",
    "ecdsa-sha2-nistp256",
    "ecdsa-sha2-nistp384",
    "ecdsa-sha2-nistp521",
}
DEFAULT_RELAY_PORT = 20_000
DEFAULT_PAIR_TTL = 600
DEFAULT_INVITE_TTL = 3_600
MAX_AUTH_FAILURES = 5
AUTH_LOCK_SECONDS = 300


class StateError(RuntimeError):
    pass


def _now() -> int:
    return int(time.time())


def normalize_code(value: str) -> str:
    compact = re.sub(r"[^A-Za-z0-9]", "", value).upper()
    if len(compact) != 8:
        raise StateError("invalid code format")
    normalized = f"{compact[:4]}-{compact[4:]}"
    if not CODE_PATTERN.fullmatch(normalized):
        raise StateError("invalid code format")
    return normalized


def generate_code() -> str:
    compact = "".join(secrets.choice(CODE_ALPHABET) for _ in range(8))
    return f"{compact[:4]}-{compact[4:]}"


def sanitize_label(value: str, fallback: str) -> str:
    cleaned = " ".join(value.replace("\x00", "").split()).strip()
    return (cleaned or fallback)[:80]


def normalize_endpoint_host(value: str | None, endpoint_mode: str) -> str | None:
    if endpoint_mode == "unknown":
        return None
    try:
        address = ipaddress.ip_address(value or "")
    except ValueError as exc:
        raise StateError("invalid ADB endpoint host") from exc
    if address.version != 4 or address.is_multicast or address.is_unspecified:
        raise StateError("invalid ADB endpoint host")
    return str(address)


def parse_public_key(value: str) -> tuple[str, str]:
    fields = value.strip().split()
    if len(fields) < 2 or fields[0] not in ALLOWED_KEY_TYPES:
        raise StateError("unsupported SSH public key")
    try:
        decoded = base64.b64decode(fields[1], validate=True)
    except (ValueError, binascii.Error) as exc:
        raise StateError("invalid SSH public key") from exc
    if len(decoded) < 32:
        raise StateError("invalid SSH public key")
    return fields[0], fields[1]


def canonical_public_key(value: str) -> str:
    key_type, encoded = parse_public_key(value)
    return f"{key_type} {encoded}"


def public_key_fingerprint(value: str) -> str:
    _, encoded = parse_public_key(value)
    blob = base64.b64decode(encoded)
    digest = base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip("=")
    return f"SHA256:{digest}"


def encode_result(payload: dict[str, Any]) -> str:
    raw = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def decode_payload(value: str) -> dict[str, Any]:
    try:
        padded = value + "=" * (-len(value) % 4)
        decoded = base64.urlsafe_b64decode(padded)
        payload = json.loads(decoded)
    except (ValueError, binascii.Error, json.JSONDecodeError) as exc:
        raise StateError("invalid request payload") from exc
    if not isinstance(payload, dict):
        raise StateError("invalid request payload")
    return payload


class RelayState:
    def __init__(
        self,
        root: Path = DEFAULT_ROOT,
        clock: Callable[[], int] = _now,
        relay_host: str = "adbgw.ru",
        relay_ssh_port: int = 443,
    ) -> None:
        self.root = root
        self.state_path = root / "state.json"
        self.lock_path = root / "locks" / "state.lock"
        self.clock = clock
        self.relay_host = relay_host
        self.relay_ssh_port = relay_ssh_port

    @staticmethod
    def empty() -> dict[str, Any]:
        return {
            "version": 1,
            "next_port": DEFAULT_RELAY_PORT,
            "devices": {},
            "clients": {},
            "grants": {},
            "pending": {},
            "codes": {},
            "auth_limits": {},
        }

    @contextlib.contextmanager
    def _locked(self) -> Iterator[dict[str, Any]]:
        self.lock_path.parent.mkdir(parents=True, exist_ok=True)
        self.root.mkdir(parents=True, exist_ok=True)
        with self.lock_path.open("a+", encoding="utf-8") as lock_file:
            os.chmod(self.lock_path, 0o660)
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            state = self._read()
            self._clean_expired(state)
            try:
                yield state
            except Exception:
                raise
            else:
                self._write(state)

    def _read(self) -> dict[str, Any]:
        if not self.state_path.exists():
            return self.empty()
        try:
            state = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise StateError("relay state is corrupt") from exc
        if not isinstance(state, dict) or state.get("version") != 1:
            raise StateError("unsupported relay state version")
        for field in ("devices", "clients", "grants", "pending", "codes", "auth_limits"):
            if not isinstance(state.get(field), dict):
                raise StateError("relay state is corrupt")
        return state

    def _write(self, state: dict[str, Any]) -> None:
        self.root.mkdir(parents=True, exist_ok=True)
        fd, temporary_name = tempfile.mkstemp(prefix=".state.", dir=self.root)
        temporary = Path(temporary_name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as output:
                json.dump(state, output, separators=(",", ":"), sort_keys=True)
                output.write("\n")
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, 0o660)
            os.replace(temporary, self.state_path)
        finally:
            if temporary.exists():
                temporary.unlink()

    def _clean_expired(self, state: dict[str, Any]) -> None:
        now = self.clock()
        expired_codes = [key for key, value in state["codes"].items() if value["expires_at"] <= now]
        for key in expired_codes:
            state["codes"].pop(key, None)

        expired_pending = [
            device_id
            for device_id, value in state["pending"].items()
            if value["expires_at"] <= now
        ]
        for device_id in expired_pending:
            state["pending"].pop(device_id, None)

        expired_limits = [
            key for key, value in state["auth_limits"].items() if value["reset_at"] <= now
        ]
        for key in expired_limits:
            state["auth_limits"].pop(key, None)

    def _new_code(
        self,
        state: dict[str, Any],
        kind: str,
        ttl_seconds: int,
        device_id: str | None = None,
        request_id: str | None = None,
    ) -> tuple[str, str]:
        for _ in range(100):
            code = generate_code()
            if not self._find_code(state, kind, code):
                break
        else:
            raise StateError("could not allocate a unique code")
        salt = secrets.token_hex(16)
        code_id = secrets.token_hex(12)
        state["codes"][code_id] = {
            "kind": kind,
            "salt": salt,
            "digest": self._code_digest(salt, code),
            "expires_at": self.clock() + ttl_seconds,
            "device_id": device_id,
            "request_id": request_id,
            "consumed_by": None,
            "recoverable_code": code if kind == "pair" else None,
        }
        return code_id, code

    @staticmethod
    def _code_digest(salt: str, code: str) -> str:
        return hashlib.sha256(f"{salt}:{code}".encode()).hexdigest()

    def _find_code(
        self, state: dict[str, Any], kind: str, code: str
    ) -> tuple[str, dict[str, Any]] | None:
        try:
            normalized = normalize_code(code)
        except StateError:
            return None
        for code_id, value in state["codes"].items():
            if value["kind"] != kind or value["expires_at"] <= self.clock():
                continue
            candidate = self._code_digest(value["salt"], normalized)
            if secrets.compare_digest(candidate, value["digest"]):
                return code_id, value
        return None

    def create_invite(self, ttl_seconds: int = DEFAULT_INVITE_TTL) -> dict[str, Any]:
        if ttl_seconds < 60 or ttl_seconds > 86_400:
            raise StateError("invite TTL must be between 60 and 86400 seconds")
        with self._locked() as state:
            _, code = self._new_code(state, "enroll", ttl_seconds)
            return {"code": code, "expires_at": self.clock() + ttl_seconds}

    def auth_check(self, username: str, password: str, source: str) -> bool:
        kind = {"cag-enroll": "enroll", "cag-pair": "pair"}.get(username)
        if kind is None:
            return False
        source_key = f"{username}:{source or 'unknown'}"
        with self._locked() as state:
            limit = state["auth_limits"].get(source_key)
            now = self.clock()
            if limit and limit["locked_until"] > now:
                return False
            match = self._find_code(state, kind, password)
            if match:
                state["auth_limits"].pop(source_key, None)
                return True
            attempts = (limit or {}).get("attempts", 0) + 1
            locked_until = now + AUTH_LOCK_SECONDS if attempts >= MAX_AUTH_FAILURES else 0
            state["auth_limits"][source_key] = {
                "attempts": attempts,
                "locked_until": locked_until,
                "reset_at": now + AUTH_LOCK_SECONDS,
            }
            return False

    def enroll(self, code: str, payload: dict[str, Any]) -> dict[str, Any]:
        tunnel_key = canonical_public_key(str(payload.get("tunnel_public_key", "")))
        inner_host_key = canonical_public_key(str(payload.get("inner_host_key", "")))
        tunnel_fingerprint = public_key_fingerprint(tunnel_key)
        label = sanitize_label(str(payload.get("label", "")), "Android head unit")
        endpoint_mode = str(payload.get("endpoint_mode", "unknown"))
        if endpoint_mode not in {"unknown", "smart", "raw"}:
            raise StateError("invalid ADB endpoint mode")
        endpoint_host = normalize_endpoint_host(payload.get("endpoint_host"), endpoint_mode)

        with self._locked() as state:
            match = self._find_code(state, "enroll", code)
            if not match:
                raise StateError("invite code is invalid or expired")
            _, code_record = match
            existing = next(
                (
                    device
                    for device in state["devices"].values()
                    if device["tunnel_fingerprint"] == tunnel_fingerprint
                ),
                None,
            )
            if code_record["consumed_by"]:
                if not existing or code_record["consumed_by"] != existing["id"]:
                    raise StateError("invite code has already been used")
                return self._device_bundle(existing)
            if existing:
                code_record["consumed_by"] = existing["id"]
                return self._device_bundle(existing)

            device_id = secrets.token_hex(8)
            relay_port = int(state["next_port"])
            state["next_port"] = relay_port + 1
            device = {
                "id": device_id,
                "label": label,
                "relay_port": relay_port,
                "tunnel_public_key": tunnel_key,
                "tunnel_fingerprint": tunnel_fingerprint,
                "inner_host_key": inner_host_key,
                "endpoint_mode": endpoint_mode,
                "endpoint_host": endpoint_host,
                "enabled": True,
                "created_at": self.clock(),
            }
            state["devices"][device_id] = device
            code_record["consumed_by"] = device_id
            return self._device_bundle(device)

    def _device_bundle(self, device: dict[str, Any]) -> dict[str, Any]:
        return {
            "device_id": device["id"],
            "device_label": device["label"],
            "relay_host": self.relay_host,
            "relay_ssh_port": self.relay_ssh_port,
            "relay_device_port": device["relay_port"],
            "inner_host_key": device["inner_host_key"],
            "endpoint_mode": device["endpoint_mode"],
            "endpoint_host": device.get("endpoint_host"),
            "enabled": device["enabled"],
        }

    def pair_open(
        self,
        device_id: str,
        request_id: str,
        ttl_seconds: int = DEFAULT_PAIR_TTL,
    ) -> dict[str, Any]:
        self._require_device_id(device_id)
        if not REQUEST_ID_PATTERN.fullmatch(request_id):
            raise StateError("invalid pairing request ID")
        if ttl_seconds < 60 or ttl_seconds > 1_800:
            raise StateError("pairing TTL must be between 60 and 1800 seconds")
        with self._locked() as state:
            device = self._require_device(state, device_id)
            if not device["enabled"]:
                raise StateError("remote access is disabled")
            pending = state["pending"].get(device_id)
            if pending and pending["request_id"] != request_id:
                raise StateError("another pairing is already pending")
            for code_id, record in state["codes"].items():
                if (
                    record["kind"] == "pair"
                    and record["device_id"] == device_id
                    and record["request_id"] == request_id
                ):
                    return {
                        "code": record["recoverable_code"],
                        "expires_at": record["expires_at"],
                        "request_id": request_id,
                    }
            _, code = self._new_code(state, "pair", ttl_seconds, device_id, request_id)
            return {
                "code": code,
                "expires_at": self.clock() + ttl_seconds,
                "request_id": request_id,
            }

    def pair_submit(self, code: str, payload: dict[str, Any]) -> dict[str, Any]:
        public_key = canonical_public_key(str(payload.get("public_key", "")))
        fingerprint = public_key_fingerprint(public_key)
        label = sanitize_label(str(payload.get("label", "")), "Mac")
        with self._locked() as state:
            match = self._find_code(state, "pair", code)
            if not match:
                raise StateError("pairing code is invalid or expired")
            _, code_record = match
            device_id = code_record["device_id"]
            device = self._require_device(state, device_id)
            if not device["enabled"]:
                raise StateError("remote access is disabled")

            pending = state["pending"].get(device_id)
            if pending:
                if (
                    pending["client_fingerprint"] != fingerprint
                    or pending["request_id"] != code_record["request_id"]
                ):
                    raise StateError("another computer is already pending")
            else:
                pending = {
                    "device_id": device_id,
                    "request_id": code_record["request_id"],
                    "client_fingerprint": fingerprint,
                    "expires_at": code_record["expires_at"],
                    "created_at": self.clock(),
                }
                state["pending"][device_id] = pending
            state["clients"][fingerprint] = {
                "fingerprint": fingerprint,
                "public_key": public_key,
                "label": label,
                "created_at": state["clients"].get(fingerprint, {}).get(
                    "created_at", self.clock()
                ),
            }
            bundle = self._device_bundle(device)
            bundle.update(
                {
                    "client_fingerprint": fingerprint,
                    "client_label": label,
                    "pairing_expires_at": pending["expires_at"],
                }
            )
            return bundle

    def pair_commit(self, device_id: str, fingerprint: str) -> dict[str, Any]:
        self._require_device_id(device_id)
        if not FINGERPRINT_PATTERN.fullmatch(fingerprint):
            raise StateError("invalid client fingerprint")
        with self._locked() as state:
            self._require_device(state, device_id)
            pending = state["pending"].get(device_id)
            active = state["grants"].get(device_id)
            if not pending:
                if active and active["client_fingerprint"] == fingerprint:
                    client = state["clients"].get(fingerprint, {})
                    return {
                        "client_fingerprint": fingerprint,
                        "client_label": client.get("label", "Computer"),
                        "replaced_fingerprint": active.get("replaced_fingerprint"),
                        "committed": True,
                    }
                raise StateError("no matching pairing is pending")
            if pending["client_fingerprint"] != fingerprint:
                raise StateError("pending computer does not match")
            old_fingerprint = active["client_fingerprint"] if active else None
            client = state["clients"].get(fingerprint)
            if not client:
                raise StateError("pending computer identity is missing")
            state["grants"][device_id] = {
                "device_id": device_id,
                "client_fingerprint": fingerprint,
                "created_at": self.clock(),
                "replaced_fingerprint": old_fingerprint,
            }
            state["pending"].pop(device_id, None)
            self._remove_pair_codes(state, device_id)
            return {
                "client_fingerprint": fingerprint,
                "client_label": client["label"],
                "replaced_fingerprint": old_fingerprint,
                "committed": True,
            }

    def pair_abort(self, device_id: str, request_id: str) -> dict[str, Any]:
        self._require_device_id(device_id)
        with self._locked() as state:
            pending = state["pending"].get(device_id)
            if pending and pending["request_id"] == request_id:
                state["pending"].pop(device_id, None)
            self._remove_pair_codes(state, device_id, request_id)
            return {"aborted": True, "request_id": request_id}

    def set_endpoint(
        self, device_id: str, endpoint_mode: str, endpoint_host: str | None
    ) -> dict[str, Any]:
        if endpoint_mode not in {"unknown", "smart", "raw"}:
            raise StateError("invalid ADB endpoint mode")
        normalized_host = normalize_endpoint_host(endpoint_host, endpoint_mode)
        with self._locked() as state:
            device = self._require_device(state, device_id)
            device["endpoint_mode"] = endpoint_mode
            device["endpoint_host"] = normalized_host
            return self._device_bundle(device)

    def set_enabled(self, device_id: str, enabled: bool) -> dict[str, Any]:
        with self._locked() as state:
            device = self._require_device(state, device_id)
            device["enabled"] = enabled
            if not enabled:
                state["pending"].pop(device_id, None)
                self._remove_pair_codes(state, device_id)
            return self._device_bundle(device)

    def device_status(self, device_id: str) -> dict[str, Any]:
        with self._locked() as state:
            device = self._require_device(state, device_id)
            active = state["grants"].get(device_id)
            pending = state["pending"].get(device_id)
            active_client = state["clients"].get(active["client_fingerprint"]) if active else None
            pending_client = state["clients"].get(pending["client_fingerprint"]) if pending else None
            return {
                **self._device_bundle(device),
                "active_client_fingerprint": active["client_fingerprint"] if active else None,
                "active_client_label": active_client["label"] if active_client else None,
                "pending_client_fingerprint": pending["client_fingerprint"] if pending else None,
                "pending_client_label": pending_client["label"] if pending_client else None,
                "pending_expires_at": pending["expires_at"] if pending else None,
            }

    def authorized_keys(self, username: str) -> list[str]:
        with self._locked() as state:
            if username == "cag-device":
                return [
                    self._device_key_line(device)
                    for device in state["devices"].values()
                    if device["enabled"]
                ]
            if username == "cag-control":
                return [
                    self._control_key_line(device)
                    for device in state["devices"].values()
                ]
            if username == "cag-client":
                lines: list[str] = []
                for device_id, device in state["devices"].items():
                    if not device["enabled"]:
                        continue
                    fingerprints: set[str] = set()
                    active = state["grants"].get(device_id)
                    pending = state["pending"].get(device_id)
                    if active:
                        fingerprints.add(active["client_fingerprint"])
                    if pending:
                        fingerprints.add(pending["client_fingerprint"])
                    for fingerprint in sorted(fingerprints):
                        client = state["clients"].get(fingerprint)
                        if client:
                            lines.append(self._client_key_line(device, client))
                return lines
            return []

    @staticmethod
    def _device_key_line(device: dict[str, Any]) -> str:
        return (
            'restrict,port-forwarding,command="/bin/false",'
            f'permitlisten="127.0.0.1:{device["relay_port"]}" '
            f'{device["tunnel_public_key"]} cag-device:{device["id"]}'
        )

    @staticmethod
    def _control_key_line(device: dict[str, Any]) -> str:
        return (
            f'restrict,command="/opt/cag/device-control.sh {device["id"]}" '
            f'{device["tunnel_public_key"]} cag-control:{device["id"]}'
        )

    @staticmethod
    def _client_key_line(device: dict[str, Any], client: dict[str, Any]) -> str:
        return (
            'restrict,port-forwarding,command="/bin/false",'
            f'permitopen="127.0.0.1:{device["relay_port"]}" '
            f'{client["public_key"]} cag-client:{client["fingerprint"]}'
        )

    @staticmethod
    def _require_device_id(device_id: str) -> None:
        if not DEVICE_ID_PATTERN.fullmatch(device_id):
            raise StateError("invalid device ID")

    @classmethod
    def _require_device(cls, state: dict[str, Any], device_id: str) -> dict[str, Any]:
        cls._require_device_id(device_id)
        device = state["devices"].get(device_id)
        if not device:
            raise StateError("unknown device")
        return device

    @staticmethod
    def _remove_pair_codes(
        state: dict[str, Any], device_id: str, request_id: str | None = None
    ) -> None:
        matches = [
            code_id
            for code_id, value in state["codes"].items()
            if value["kind"] == "pair"
            and value["device_id"] == device_id
            and (request_id is None or value["request_id"] == request_id)
        ]
        for code_id in matches:
            state["codes"].pop(code_id, None)


def print_ok(payload: dict[str, Any]) -> None:
    print(f"OK {encode_result(payload)}")


def parse_bool(value: str) -> bool:
    if value == "true":
        return True
    if value == "false":
        return False
    raise StateError("expected true or false")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", type=Path, default=Path(os.environ.get("CAG_STATE_ROOT", DEFAULT_ROOT))
    )
    parser.add_argument(
        "--relay-host", default=os.environ.get("CAG_RELAY_HOST", "adbgw.ru")
    )
    parser.add_argument(
        "--relay-ssh-port",
        type=int,
        default=int(os.environ.get("CAG_RELAY_SSH_PORT", "443")),
    )
    subparsers = parser.add_subparsers(dest="action", required=True)

    invite = subparsers.add_parser("create-invite")
    invite.add_argument("--ttl", type=int, default=DEFAULT_INVITE_TTL)

    auth = subparsers.add_parser("auth-check")
    auth.add_argument("--user", required=True)
    auth.add_argument("--source", default="unknown")
    auth.add_argument("--password-stdin", action="store_true", required=True)

    enroll = subparsers.add_parser("enroll")
    enroll.add_argument("code")
    enroll.add_argument("payload")

    pair_submit = subparsers.add_parser("pair-submit")
    pair_submit.add_argument("code")
    pair_submit.add_argument("payload")

    pair_open = subparsers.add_parser("pair-open")
    pair_open.add_argument("device_id")
    pair_open.add_argument("request_id")
    pair_open.add_argument("--ttl", type=int, default=DEFAULT_PAIR_TTL)

    pair_commit = subparsers.add_parser("pair-commit")
    pair_commit.add_argument("device_id")
    pair_commit.add_argument("fingerprint")

    pair_abort = subparsers.add_parser("pair-abort")
    pair_abort.add_argument("device_id")
    pair_abort.add_argument("request_id")

    endpoint = subparsers.add_parser("set-endpoint")
    endpoint.add_argument("device_id")
    endpoint.add_argument("endpoint_mode")
    endpoint.add_argument("endpoint_host", nargs="?")

    enabled = subparsers.add_parser("set-enabled")
    enabled.add_argument("device_id")
    enabled.add_argument("enabled")

    status = subparsers.add_parser("device-status")
    status.add_argument("device_id")

    authorized = subparsers.add_parser("authorized-keys")
    authorized.add_argument("username")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    state = RelayState(args.root, relay_host=args.relay_host, relay_ssh_port=args.relay_ssh_port)
    try:
        if args.action == "create-invite":
            print_ok(state.create_invite(args.ttl))
        elif args.action == "auth-check":
            password = sys.stdin.readline().rstrip("\r\n")
            return 0 if state.auth_check(args.user, password, args.source) else 1
        elif args.action == "enroll":
            print_ok(state.enroll(args.code, decode_payload(args.payload)))
        elif args.action == "pair-submit":
            print_ok(state.pair_submit(args.code, decode_payload(args.payload)))
        elif args.action == "pair-open":
            print_ok(state.pair_open(args.device_id, args.request_id, args.ttl))
        elif args.action == "pair-commit":
            print_ok(state.pair_commit(args.device_id, args.fingerprint))
        elif args.action == "pair-abort":
            print_ok(state.pair_abort(args.device_id, args.request_id))
        elif args.action == "set-endpoint":
            print_ok(state.set_endpoint(args.device_id, args.endpoint_mode, args.endpoint_host))
        elif args.action == "set-enabled":
            print_ok(state.set_enabled(args.device_id, parse_bool(args.enabled)))
        elif args.action == "device-status":
            print_ok(state.device_status(args.device_id))
        elif args.action == "authorized-keys":
            for line in state.authorized_keys(args.username):
                print(line)
        else:
            raise StateError("unsupported action")
        return 0
    except StateError as exc:
        print(f"ERROR {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
