#!/usr/bin/env python3
"""Bounded research reader for an already-open, separately authorized export port.

Does not start/stop services, invoke ADB, upload, delete, or send ECU requests.
The stock server binds all interfaces and exposes deletion: localhost forwarding
does NOT make server startup safe. Approval and lifecycle belong to the caller.
Only the eight fixed cache paths below are requested. No arbitrary-path option.
The client bounds its received bytes; the stock server hashes the whole file
before replying. Parent-directory size preflight reduces, but cannot eliminate,
that server-side race. Every mismatch aborts without a retry.
Protocol source: matched BydLogTool FileTransferHandler/FileTransferProtocol in
captures/firmware/ivi-34.1.33.2605218.1/jadx/BydLogTool (2026-10-18).
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import socket
import struct
import sys
import time


ALLOWED_FILES = (
    "/data/ota_package/ota_ghy/result/strategy.json",
    "/data/ota_package/ota/result/strategy.json",
    "/data/ota_package/needCheckEcuList.json",
    "/data/ota_package/ECUVersion.json",
    "/collect2/VehicleVersion2",
    "/collect2/SourceVehicleVersion2",
    "/collect2/SourceVehicleVersion",
    "/collect2/diagnosticsrv/ecu_config.json",
)
ALLOWED_DIRECTORIES = frozenset(str(PurePosixPath(p).parent) for p in ALLOWED_FILES)
EXPORT_PREFIX = "/data/local/tmp/logtool/../../../../"
MAGIC = 0x42594446
MAX_JSON = 64 * 1024
MAX_FILE = 1024 * 1024
IO_TIMEOUT = 2.0
TOTAL_TIMEOUT = 20.0
LOOPBACK = {"localhost": "127.0.0.1", "127.0.0.1": "127.0.0.1", "::1": "::1"}


class ExportError(Exception):
    """Fixed error code only: never include remote data or operating-system text."""


class MissingFile(ExportError):
    pass


class Deadline:
    def __init__(self):
        self.started = time.monotonic()
        self.end = self.started + TOTAL_TIMEOUT

    def timeout(self):
        remaining = self.end - time.monotonic()
        if remaining <= 0:
            raise ExportError("total_timeout")
        return min(IO_TIMEOUT, remaining)


def _endpoint(host, port):
    if host not in LOOPBACK or type(port) is not int or not 1 <= port <= 65535:
        raise ExportError("invalid_loopback_endpoint")
    # Never resolve caller-controlled DNS, including the literal 'localhost'.
    return LOOPBACK[host], port


def _request(action, path):
    if type(action) is not int:
        raise ExportError("request_not_allowed")
    allowed = ALLOWED_FILES if action == 1 else ALLOWED_DIRECTORIES if action == 2 else ()
    if path not in allowed:
        raise ExportError("request_not_allowed")
    key = "filePath" if action == 1 else "directory"
    payload = json.dumps({key: EXPORT_PREFIX + path.lstrip("/")},
                         separators=(",", ":")).encode("utf-8")
    return struct.pack(">III", MAGIC, action, len(payload)) + payload


def _receive(sock, count, deadline):
    parts = bytearray()
    while len(parts) < count:
        sock.settimeout(deadline.timeout())
        chunk = sock.recv(min(8192, count - len(parts)))
        deadline.timeout()
        if not chunk:
            raise ExportError("truncated_response")
        parts.extend(chunk)
    return bytes(parts)


def _eof(sock, deadline):
    sock.settimeout(deadline.timeout())
    extra = sock.recv(1)
    deadline.timeout()
    if extra:
        raise ExportError("unexpected_trailing_bytes")


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ExportError("duplicate_json_key")
        result[key] = value
    return result


def _constant(_value):
    raise ExportError("invalid_json_number")


def _envelope(sock, deadline):
    magic, status, length = struct.unpack(">III", _receive(sock, 12, deadline))
    if magic != MAGIC or status not in range(6):
        raise ExportError("invalid_response_header")
    if not 1 <= length <= MAX_JSON:
        raise ExportError("json_length_out_of_bounds")
    try:
        doc = json.loads(_receive(sock, length, deadline).decode("utf-8"),
                         object_pairs_hook=_pairs, parse_constant=_constant)
    except (UnicodeError, ValueError, RecursionError):
        raise ExportError("invalid_json") from None
    if not isinstance(doc, dict) or type(doc.get("code")) is not int or doc["code"] != status:
        raise ExportError("response_status_conflict")
    if status:
        _eof(sock, deadline)
        if status == 1:
            raise MissingFile("missing")
        raise ExportError("remote_error_" + str(status))
    return doc


def _size(value):
    if type(value) is not int or value < 0:
        raise ExportError("invalid_file_size")
    return value


def _list(doc):
    rows = doc.get("files")
    if not isinstance(rows, list):
        raise ExportError("invalid_directory_listing")
    result = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ExportError("invalid_directory_listing")
        name = row.get("name")
        if (not isinstance(name, str) or not name or name in (".", "..")
                or "/" in name or "\x00" in name):
            raise ExportError("invalid_listing_name")
        if name in result:
            raise ExportError("duplicate_listing_name")
        result[name] = _size(row.get("size"))
    return result


def _download(doc, expected_name, listed_size, sock, deadline):
    if doc.get("fileName") != expected_name:
        raise ExportError("file_name_conflict")
    size = _size(doc.get("fileSize"))
    if size > MAX_FILE:
        raise ExportError("file_size_out_of_bounds")
    if size != listed_size:
        raise ExportError("file_size_conflict")
    md5 = doc.get("md5")
    if not isinstance(md5, str) or not re.fullmatch(r"[0-9a-fA-F]{32}", md5):
        raise ExportError("invalid_md5")
    raw = _receive(sock, size, deadline)
    _eof(sock, deadline)
    if hashlib.md5(raw).hexdigest() != md5.lower():
        raise ExportError("md5_mismatch")
    return raw


def _exchange(endpoint, action, path, deadline, listed_size=None):
    request = _request(action, path)  # Fail closed before opening any socket.
    try:
        with socket.create_connection(endpoint, timeout=deadline.timeout()) as sock:
            sock.settimeout(deadline.timeout())
            sock.sendall(request)
            deadline.timeout()
            doc = _envelope(sock, deadline)
            if action == 2:
                result = _list(doc)
                _eof(sock, deadline)
                return result
            return _download(doc, PurePosixPath(path).name, listed_size, sock, deadline)
    except socket.timeout:
        raise ExportError("socket_timeout") from None
    except OSError:
        raise ExportError("socket_error") from None


def _save_new(path, raw):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    try:
        # Explicit mode also covers an unusual process umask.
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "wb", closefd=False) as stream:
            stream.write(raw)
            stream.flush()
    finally:
        os.close(descriptor)


def run_export(host, port, output):
    """Read fixed caches once; return sanitized report, including bounded failures.

    Output must be a new directory. Verified files from before an abort are kept;
    unverified bytes and remote error messages are never saved. A non-complete
    report requires the caller's finally cleanup of the separately owned server.
    """
    endpoint = _endpoint(host, port)
    output = Path(output)
    try:
        output.mkdir(mode=0o700)
    except FileExistsError:
        raise ExportError("output_already_exists") from None
    except OSError:
        raise ExportError("output_create_failed") from None
    deadline = Deadline()
    report = {"schemaVersion": 1, "status": "complete", "error": None,
              "limits": {"fileBytes": MAX_FILE, "jsonBytes": MAX_JSON,
                         "socketSeconds": IO_TIMEOUT, "totalSeconds": TOTAL_TIMEOUT},
              "files": [{"path": path, "status": "not_attempted"} for path in ALLOWED_FILES]}
    listings = {}
    current = None
    try:
        for index, row in enumerate(report["files"], 1):
            current = row
            path = row["path"]
            parent = str(PurePosixPath(path).parent)
            name = PurePosixPath(path).name
            deadline.timeout()
            if parent not in listings:
                try:
                    listings[parent] = _exchange(endpoint, 2, parent, deadline)
                except MissingFile:
                    listings[parent] = {}
            if name not in listings[parent]:
                row["status"] = "missing"
                continue
            size = listings[parent][name]
            if size > MAX_FILE:
                raise ExportError("file_size_out_of_bounds")
            try:
                raw = _exchange(endpoint, 1, path, deadline, size)
            except MissingFile:
                row["status"] = "missing"
                continue
            deadline.timeout()
            filename = str(index).zfill(2) + "-" + name
            _save_new(output / filename, raw)
            row.update(status="saved", savedAs=filename, sizeBytes=len(raw),
                       md5=hashlib.md5(raw).hexdigest(), sha256=hashlib.sha256(raw).hexdigest())
        deadline.timeout()
    except (ExportError, OSError) as error:
        code = str(error) if isinstance(error, ExportError) else "output_write_failed"
        report.update(status="aborted", error=code)
        if current is not None and current["status"] == "not_attempted":
            current.update(status="failed", error=code)
    try:
        _save_new(output / "report.json", (json.dumps(report, indent=2) + "\n").encode("utf-8"))
    except OSError:
        raise ExportError("report_write_failed") from None
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", choices=tuple(LOOPBACK), default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True, help="New private output directory")
    args = parser.parse_args(argv)
    try:
        report = run_export(args.host, args.port, args.output)
    except ExportError as error:
        print(json.dumps({"status": "aborted", "error": str(error)}))
        return 1
    print(json.dumps({"status": report["status"], "error": report["error"],
                      "saved": sum(row["status"] == "saved" for row in report["files"]),
                      "missing": sum(row["status"] == "missing" for row in report["files"])}))
    return 0 if report["status"] == "complete" else 1


if __name__ == "__main__":
    sys.exit(main())
