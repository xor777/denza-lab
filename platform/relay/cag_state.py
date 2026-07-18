#!/usr/bin/env python3
"""Atomic state and authorization policy for the Car ADB Gateway relay."""

from __future__ import annotations

import argparse
import base64
import binascii
import copy
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
MAX_RELAY_PORT = 65_535
DEFAULT_PAIR_TTL = 600
DEFAULT_INVITE_TTL = 3_600
DEVICE_LEASE_SECONDS = 14 * 24 * 60 * 60
STATE_VERSION = 2


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
            "version": STATE_VERSION,
            "next_port": DEFAULT_RELAY_PORT,
            "devices": {},
            "clients": {},
            "grants": {},
            "pending": {},
            "codes": {},
        }

    @contextlib.contextmanager
    def _read_locked(self) -> Iterator[dict[str, Any]]:
        try:
            with self.lock_path.open("r", encoding="utf-8") as lock_file:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_SH)
                yield self._read()
        except FileNotFoundError as exc:
            raise StateError("relay state is not initialized") from exc

    @contextlib.contextmanager
    def _locked(self, clean_transient: bool = True) -> Iterator[dict[str, Any]]:
        self.lock_path.parent.mkdir(parents=True, exist_ok=True)
        self.root.mkdir(parents=True, exist_ok=True)
        with self.lock_path.open("a+", encoding="utf-8") as lock_file:
            os.chmod(self.lock_path, 0o660)
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            state = self._read()
            before = self._serialize(state)
            if clean_transient:
                self._clean_transient(state)
            try:
                yield state
            except Exception:
                raise
            else:
                if self._serialize(state) != before:
                    self._write(state)

    def _read(self) -> dict[str, Any]:
        if not self.state_path.exists():
            return self.empty()
        try:
            state = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise StateError("relay state is corrupt") from exc
        if not isinstance(state, dict) or state.get("version") not in {1, STATE_VERSION}:
            raise StateError("unsupported relay state version")
        state = self._migrate(state)
        self._validate_state(state)
        return state

    def _migrate(self, state: dict[str, Any]) -> dict[str, Any]:
        if state.get("version") == STATE_VERSION:
            return state
        migrated = copy.deepcopy(state)
        migrated["version"] = STATE_VERSION
        migrated.pop("auth_limits", None)
        grace_expires_at = self.clock() + DEVICE_LEASE_SECONDS
        for device in migrated.get("devices", {}).values():
            device.setdefault("control_public_key", device.get("tunnel_public_key"))
            device.setdefault("lease_expires_at", grace_expires_at)
        return migrated

    def _validate_state(self, state: dict[str, Any]) -> None:
        if state.get("version") != STATE_VERSION:
            raise StateError("unsupported relay state version")
        if set(state) != {
            "version",
            "next_port",
            "devices",
            "clients",
            "grants",
            "pending",
            "codes",
        }:
            raise StateError("relay state is corrupt")
        for field in ("devices", "clients", "grants", "pending", "codes"):
            if not isinstance(state.get(field), dict):
                raise StateError("relay state is corrupt")
        next_port = state.get("next_port")
        if type(next_port) is not int or not DEFAULT_RELAY_PORT <= next_port <= MAX_RELAY_PORT:
            raise StateError("relay state is corrupt")
        used_ports: set[int] = set()
        tunnel_fingerprints: set[str] = set()
        for device_id, device in state["devices"].items():
            if (
                not isinstance(device, dict)
                or set(device)
                != {
                    "id",
                    "label",
                    "relay_port",
                    "tunnel_public_key",
                    "tunnel_fingerprint",
                    "control_public_key",
                    "inner_host_key",
                    "endpoint_mode",
                    "endpoint_host",
                    "enabled",
                    "created_at",
                    "lease_expires_at",
                }
                or device.get("id") != device_id
            ):
                raise StateError("relay state is corrupt")
            self._require_device_id(device_id)
            for field in ("tunnel_public_key", "control_public_key", "inner_host_key"):
                canonical_public_key(str(device.get(field, "")))
            tunnel_fingerprint = public_key_fingerprint(device["tunnel_public_key"])
            endpoint_mode = device.get("endpoint_mode")
            if endpoint_mode not in {"unknown", "smart", "raw"}:
                raise StateError("relay state is corrupt")
            relay_port = device.get("relay_port")
            if (
                type(relay_port) is not int
                or not DEFAULT_RELAY_PORT <= relay_port <= MAX_RELAY_PORT
                or relay_port in used_ports
                or tunnel_fingerprint != device.get("tunnel_fingerprint")
                or tunnel_fingerprint in tunnel_fingerprints
                or type(device.get("created_at")) is not int
                or type(device.get("lease_expires_at")) is not int
                or type(device.get("enabled")) is not bool
                or not isinstance(device.get("label"), str)
                or not 1 <= len(device["label"]) <= 80
                or normalize_endpoint_host(device.get("endpoint_host"), endpoint_mode)
                != device.get("endpoint_host")
            ):
                raise StateError("relay state is corrupt")
            used_ports.add(relay_port)
            tunnel_fingerprints.add(tunnel_fingerprint)
        for fingerprint, client in state["clients"].items():
            if (
                not isinstance(client, dict)
                or set(client) != {"fingerprint", "public_key", "label", "created_at"}
                or client.get("fingerprint") != fingerprint
                or not FINGERPRINT_PATTERN.fullmatch(fingerprint)
                or not isinstance(client.get("label"), str)
                or not 1 <= len(client["label"]) <= 80
                or type(client.get("created_at")) is not int
            ):
                raise StateError("relay state is corrupt")
            if public_key_fingerprint(str(client.get("public_key", ""))) != fingerprint:
                raise StateError("relay state is corrupt")
        client_owners: dict[str, str] = {}
        for device_id, grant in state["grants"].items():
            if (
                device_id not in state["devices"]
                or not isinstance(grant, dict)
                or set(grant)
                != {
                    "device_id",
                    "client_fingerprint",
                    "created_at",
                    "replaced_fingerprint",
                }
                or grant.get("device_id") != device_id
                or grant.get("client_fingerprint") not in state["clients"]
                or type(grant.get("created_at")) is not int
                or (
                    grant.get("replaced_fingerprint") is not None
                    and not FINGERPRINT_PATTERN.fullmatch(grant["replaced_fingerprint"])
                )
            ):
                raise StateError("relay state is corrupt")
            owner = client_owners.get(grant["client_fingerprint"])
            if owner is not None and owner != device_id:
                raise StateError("relay state is corrupt")
            client_owners[grant["client_fingerprint"]] = device_id
        for device_id, pending in state["pending"].items():
            if (
                device_id not in state["devices"]
                or not isinstance(pending, dict)
                or set(pending)
                != {
                    "device_id",
                    "request_id",
                    "client_fingerprint",
                    "expires_at",
                    "created_at",
                }
                or pending.get("device_id") != device_id
                or pending.get("client_fingerprint") not in state["clients"]
                or not REQUEST_ID_PATTERN.fullmatch(str(pending.get("request_id", "")))
                or type(pending.get("expires_at")) is not int
                or type(pending.get("created_at")) is not int
            ):
                raise StateError("relay state is corrupt")
            owner = client_owners.get(pending["client_fingerprint"])
            if owner is not None and owner != device_id:
                raise StateError("relay state is corrupt")
            client_owners[pending["client_fingerprint"]] = device_id
        for code_id, record in state["codes"].items():
            if (
                not re.fullmatch(r"[a-f0-9]{24}", code_id)
                or not isinstance(record, dict)
                or set(record)
                != {
                    "kind",
                    "salt",
                    "digest",
                    "expires_at",
                    "device_id",
                    "request_id",
                    "consumed_by",
                    "recoverable_code",
                }
                or record.get("kind") not in {"enroll", "pair"}
                or not re.fullmatch(r"[a-f0-9]{32}", str(record.get("salt", "")))
                or not re.fullmatch(r"[a-f0-9]{64}", str(record.get("digest", "")))
                or type(record.get("expires_at")) is not int
            ):
                raise StateError("relay state is corrupt")
            if record["kind"] == "enroll":
                if (
                    record.get("device_id") is not None
                    or record.get("request_id") is not None
                    or record.get("recoverable_code") is not None
                    or (
                        record.get("consumed_by") is not None
                        and record["consumed_by"] not in state["devices"]
                    )
                ):
                    raise StateError("relay state is corrupt")
            else:
                code = record.get("recoverable_code")
                if (
                    record.get("device_id") not in state["devices"]
                    or not REQUEST_ID_PATTERN.fullmatch(str(record.get("request_id", "")))
                    or record.get("consumed_by") is not None
                    or not isinstance(code, str)
                    or not CODE_PATTERN.fullmatch(code)
                    or self._code_digest(record["salt"], code) != record["digest"]
                ):
                    raise StateError("relay state is corrupt")

    @staticmethod
    def _serialize(state: dict[str, Any]) -> str:
        return json.dumps(state, separators=(",", ":"), sort_keys=True)

    def _write(self, state: dict[str, Any]) -> None:
        self._validate_state(state)
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
            directory_fd = os.open(self.root, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if temporary.exists():
                temporary.unlink()

    def _clean_transient(self, state: dict[str, Any]) -> None:
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
        self._prune_clients(state)

    @staticmethod
    def _prune_clients(state: dict[str, Any]) -> int:
        referenced = {
            value["client_fingerprint"]
            for collection in (state["grants"], state["pending"])
            for value in collection.values()
        }
        orphaned = [fingerprint for fingerprint in state["clients"] if fingerprint not in referenced]
        for fingerprint in orphaned:
            state["clients"].pop(fingerprint, None)
        return len(orphaned)

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
        del source
        with self._read_locked() as state:
            return self._find_code(state, kind, password) is not None

    def enroll(self, code: str, payload: dict[str, Any]) -> dict[str, Any]:
        tunnel_key = canonical_public_key(str(payload.get("tunnel_public_key", "")))
        control_key = canonical_public_key(str(payload.get("control_public_key", "")))
        if control_key == tunnel_key:
            raise StateError("control and tunnel keys must be different")
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
            relay_port = self._allocate_port(state)
            device = {
                "id": device_id,
                "label": label,
                "relay_port": relay_port,
                "tunnel_public_key": tunnel_key,
                "tunnel_fingerprint": tunnel_fingerprint,
                "control_public_key": control_key,
                "inner_host_key": inner_host_key,
                "endpoint_mode": endpoint_mode,
                "endpoint_host": endpoint_host,
                "enabled": True,
                "created_at": self.clock(),
                "lease_expires_at": self.clock() + DEVICE_LEASE_SECONDS,
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
            "lease_expires_at": device["lease_expires_at"],
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
            active_pair_codes = [
                record
                for record in state["codes"].values()
                if record["kind"] == "pair" and record["device_id"] == device_id
            ]
            if active_pair_codes and all(
                record["request_id"] != request_id for record in active_pair_codes
            ):
                raise StateError("another pairing window is already open")
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
            for collection in (state["grants"], state["pending"]):
                for owner, record in collection.items():
                    if owner != device_id and record["client_fingerprint"] == fingerprint:
                        raise StateError("computer key is already paired to another device")
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
            self._prune_clients(state)
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
            self._prune_clients(state)
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
            if enabled:
                device["lease_expires_at"] = self.clock() + DEVICE_LEASE_SECONDS
            if not enabled:
                state["pending"].pop(device_id, None)
                self._remove_pair_codes(state, device_id)
                self._prune_clients(state)
            return self._device_bundle(device)

    def renew_lease(self, device_id: str) -> dict[str, Any]:
        with self._locked() as state:
            device = self._require_device(state, device_id)
            device["lease_expires_at"] = self.clock() + DEVICE_LEASE_SECONDS
            return {
                "device_id": device_id,
                "lease_expires_at": device["lease_expires_at"],
            }

    def device_status(self, device_id: str) -> dict[str, Any]:
        with self._read_locked() as state:
            device = self._require_device(state, device_id)
            active = state["grants"].get(device_id)
            pending = state["pending"].get(device_id)
            if pending and pending["expires_at"] <= self.clock():
                pending = None
            active_client = state["clients"].get(active["client_fingerprint"]) if active else None
            pending_client = state["clients"].get(pending["client_fingerprint"]) if pending else None
            return {
                **self._device_bundle(device),
                "active_client_fingerprint": active["client_fingerprint"] if active else None,
                "active_client_label": active_client["label"] if active_client else None,
                "pending_client_fingerprint": pending["client_fingerprint"] if pending else None,
                "pending_client_label": pending_client["label"] if pending_client else None,
                "pending_expires_at": pending["expires_at"] if pending else None,
                "lease_expires_at": device["lease_expires_at"],
            }

    def list_devices(self) -> dict[str, Any]:
        with self._read_locked() as state:
            devices = []
            for device_id in sorted(state["devices"]):
                device = state["devices"][device_id]
                active = state["grants"].get(device_id)
                client = state["clients"].get(active["client_fingerprint"]) if active else None
                devices.append(
                    {
                        "device_id": device_id,
                        "label": device["label"],
                        "enabled": device["enabled"],
                        "relay_port": device["relay_port"],
                        "lease_expires_at": device["lease_expires_at"],
                        "client_label": client["label"] if client else None,
                    }
                )
            return {"devices": devices}

    def remove_device(self, device_id: str) -> dict[str, Any]:
        with self._locked() as state:
            self._require_device(state, device_id, allow_expired=True)
            self._remove_device(state, device_id)
            removed_clients = self._prune_clients(state)
            return {
                "device_id": device_id,
                "removed": True,
                "removed_clients": removed_clients,
                "recycle_sessions": True,
            }

    def gc(self) -> dict[str, Any]:
        with self._locked(clean_transient=False) as state:
            now = self.clock()
            clients_before = len(state["clients"])
            expired_devices = sorted(
                device_id
                for device_id, device in state["devices"].items()
                if device["lease_expires_at"] <= now
            )
            for device_id in expired_devices:
                self._remove_device(state, device_id)
            self._clean_transient(state)
            self._prune_clients(state)
            return {
                "removed_devices": len(expired_devices),
                "removed_clients": clients_before - len(state["clients"]),
                "recycle_sessions": bool(expired_devices),
            }

    def migrate(self) -> dict[str, Any]:
        self.lock_path.parent.mkdir(parents=True, exist_ok=True)
        self.root.mkdir(parents=True, exist_ok=True)
        with self.lock_path.open("a+", encoding="utf-8") as lock_file:
            os.chmod(self.lock_path, 0o660)
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            raw_version = None
            if self.state_path.exists():
                try:
                    raw_version = json.loads(
                        self.state_path.read_text(encoding="utf-8")
                    ).get("version")
                except (OSError, json.JSONDecodeError, AttributeError) as exc:
                    raise StateError("relay state is corrupt") from exc
            state = self._read()
            migrated = raw_version != STATE_VERSION
            if migrated:
                self._write(state)
            return {
                "version": state["version"],
                "devices": len(state["devices"]),
                "migrated": migrated,
            }

    def doctor(self) -> dict[str, Any]:
        with self._read_locked() as state:
            now = self.clock()
            referenced = {
                value["client_fingerprint"]
                for collection in (state["grants"], state["pending"])
                for value in collection.values()
            }
            orphaned = len(set(state["clients"]) - referenced)
            expired_devices = sum(
                device["lease_expires_at"] <= now for device in state["devices"].values()
            )
            return {
                "ok": orphaned == 0 and expired_devices == 0,
                "version": state["version"],
                "devices": len(state["devices"]),
                "clients": len(state["clients"]),
                "grants": len(state["grants"]),
                "pending": len(state["pending"]),
                "codes": len(state["codes"]),
                "orphaned_clients": orphaned,
                "expired_devices": expired_devices,
                "next_port": state["next_port"],
            }

    def authorized_keys(self, username: str) -> list[str]:
        with self._read_locked() as state:
            if username == "cag-device":
                return [
                    self._device_key_line(device)
                    for device in state["devices"].values()
                    if device["enabled"] and self._device_active(device)
                ]
            if username == "cag-control":
                return [
                    self._control_key_line(device)
                    for device in state["devices"].values()
                    if self._device_active(device)
                ]
            if username == "cag-client":
                lines: list[str] = []
                for device_id, device in state["devices"].items():
                    if not device["enabled"] or not self._device_active(device):
                        continue
                    fingerprints: set[str] = set()
                    active = state["grants"].get(device_id)
                    pending = state["pending"].get(device_id)
                    if pending and pending["expires_at"] <= self.clock():
                        pending = None
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
            f'{device["control_public_key"]} cag-control:{device["id"]}'
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

    def _require_device(
        self, state: dict[str, Any], device_id: str, allow_expired: bool = False
    ) -> dict[str, Any]:
        self._require_device_id(device_id)
        device = state["devices"].get(device_id)
        if not device:
            raise StateError("unknown device")
        if not allow_expired and not self._device_active(device):
            raise StateError("device lease expired")
        return device

    def _device_active(self, device: dict[str, Any]) -> bool:
        return device["lease_expires_at"] > self.clock()

    def _allocate_port(self, state: dict[str, Any]) -> int:
        used = {device["relay_port"] for device in state["devices"].values()}
        pool_size = MAX_RELAY_PORT - DEFAULT_RELAY_PORT + 1
        start = state["next_port"]
        for offset in range(pool_size):
            relay_port = DEFAULT_RELAY_PORT + (
                (start - DEFAULT_RELAY_PORT + offset) % pool_size
            )
            if relay_port not in used:
                state["next_port"] = (
                    DEFAULT_RELAY_PORT if relay_port == MAX_RELAY_PORT else relay_port + 1
                )
                return relay_port
        raise StateError("relay port pool is exhausted")

    @staticmethod
    def _remove_device(state: dict[str, Any], device_id: str) -> None:
        state["devices"].pop(device_id, None)
        state["grants"].pop(device_id, None)
        state["pending"].pop(device_id, None)
        code_ids = [
            code_id
            for code_id, record in state["codes"].items()
            if record.get("device_id") == device_id
            or record.get("consumed_by") == device_id
        ]
        for code_id in code_ids:
            state["codes"].pop(code_id, None)
        if not state["devices"]:
            state["next_port"] = DEFAULT_RELAY_PORT

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

    renew = subparsers.add_parser("renew-lease")
    renew.add_argument("device_id")

    remove = subparsers.add_parser("remove-device")
    remove.add_argument("device_id")

    subparsers.add_parser("list-devices")
    subparsers.add_parser("doctor")
    subparsers.add_parser("migrate")
    gc = subparsers.add_parser("gc")
    gc.add_argument("--signal-exit", action="store_true")

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
        elif args.action == "renew-lease":
            print_ok(state.renew_lease(args.device_id))
        elif args.action == "device-status":
            print_ok(state.device_status(args.device_id))
        elif args.action == "list-devices":
            print_ok(state.list_devices())
        elif args.action == "remove-device":
            print_ok(state.remove_device(args.device_id))
        elif args.action == "doctor":
            result = state.doctor()
            print_ok(result)
            return 0 if result["ok"] else 3
        elif args.action == "migrate":
            print_ok(state.migrate())
        elif args.action == "gc":
            result = state.gc()
            print_ok(result)
            if args.signal_exit and result["recycle_sessions"]:
                return 10
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
