#!/usr/bin/env python3
"""Read one existing VHDS response through an already-open export port.

The caller owns authorization, forwarding and server lifecycle. This module
never invokes ADB, starts a service, sends a diagnostic request or saves files.
Only one fixed parent listing and, if present and bounded, one fixed download
are attempted. Raw response bytes stay in memory until the caller discards them.

Projection follows the source schema in vhds-diagnostic-trigger-path-20261021
and vhds-production-route-20261021. It does not invoke the effectful native
parser, copy its coercions, decode units or classify parameter strings as DIDs.
A stored response is not proof of a currently active or applicable vehicle task.
"""
import hashlib
import json
import math
import re
import socket
import struct

import read_ecu_cache_export as wire


CONFIG_DIRECTORY = "/data/vhds/trigger_list_cfg"
CONFIG_FILE = CONFIG_DIRECTORY + "/allone.txt"
ALLOWED_FILES = (CONFIG_FILE,)
ALLOWED_DIRECTORIES = frozenset((CONFIG_DIRECTORY,))
MAX_FILE = 1024 * 1024
ExportError = wire.ExportError
MissingFile = wire.MissingFile

# These six native fields use cJSON valuestring -> strtoll, rather than
# valueint. Preserve the spelling and type instead of silently normalizing it.
_HEX_FIELDS = {"signal": 0xFFFFFFFF, "childrenId": 0xFF,
               "checkId": 0xFFFF, "checkType": 0xFF}
_DECIMAL_FIELDS = {"values": 0xFF, "logic": 2}
_BIT_FIELDS = ("startBit", "endBit")
_REQUIRED_FIELDS = tuple(_HEX_FIELDS) + tuple(_DECIMAL_FIELDS) + _BIT_FIELDS
_HEX = re.compile(r"(?:0[xX])?[0-9a-fA-F]{1,16}\Z")
_DECIMAL = re.compile(r"[0-9]{1,19}\Z")
_RESULT_CODE = re.compile(r"-?[0-9]{1,10}\Z")
_PARAMETER = re.compile(r"[0-9a-fA-F]{0,8}\Z")


def _endpoint(host, port):
    if type(host) is not str:
        raise ExportError("invalid_loopback_endpoint")
    return wire._endpoint(host, port)


def _request(action, path):
    """Our allowlist is separate from the other reader's cache allowlist."""
    if type(action) is not int or type(path) is not str:
        raise ExportError("request_not_allowed")
    allowed = ALLOWED_FILES if action == 1 else ALLOWED_DIRECTORIES if action == 2 else ()
    if path not in allowed:
        raise ExportError("request_not_allowed")
    key = "filePath" if action == 1 else "directory"
    payload = json.dumps({key: wire.EXPORT_PREFIX + path.lstrip("/")},
                         separators=(",", ":")).encode("utf-8")
    return struct.pack(">III", wire.MAGIC, action, len(payload)) + payload


def _finite_number(value):
    number = float(value)
    if not math.isfinite(number):
        raise ExportError("invalid_json_number")
    return number


def _json(raw):
    """Strict UTF-8/JSON, including duplicate keys and overflowed floats."""
    try:
        result = json.loads(raw.decode("utf-8"), object_pairs_hook=wire._pairs,
                            parse_constant=wire._constant, parse_float=_finite_number)
        # JSON escapes can contain unpaired surrogates even with valid source
        # UTF-8. Reject those everywhere, including fields that would be dropped.
        pending = [result]
        while pending:
            value = pending.pop()
            if type(value) is str:
                value.encode("utf-8")
            elif type(value) is dict:
                pending.extend(value)
                pending.extend(value.values())
            elif type(value) is list:
                pending.extend(value)
        return result
    except (UnicodeError, ValueError, RecursionError):
        raise ExportError("invalid_json") from None


def _envelope(sock, deadline):
    # Retain the tested framing helpers, with strict JSON for this reader's
    # envelopes as well as the downloaded source response.
    magic, status, length = struct.unpack(">III", wire._receive(sock, 12, deadline))
    if magic != wire.MAGIC or status not in range(6):
        raise ExportError("invalid_response_header")
    if not 1 <= length <= wire.MAX_JSON:
        raise ExportError("json_length_out_of_bounds")
    doc = _json(wire._receive(sock, length, deadline))
    if type(doc) is not dict or type(doc.get("code")) is not int or doc["code"] != status:
        raise ExportError("response_status_conflict")
    if status:
        wire._eof(sock, deadline)
        if status == 1:
            raise MissingFile("missing")
        raise ExportError("remote_error_" + str(status))
    return doc


def _exchange(endpoint, action, path, deadline, listed_size=None):
    request = _request(action, path)  # Reject every other action/path before connect.
    if type(endpoint) is not tuple or len(endpoint) != 2:
        raise ExportError("invalid_loopback_endpoint")
    endpoint = _endpoint(*endpoint)
    try:
        with socket.create_connection(endpoint, timeout=deadline.timeout()) as sock:
            sock.settimeout(deadline.timeout())
            sock.sendall(request)
            deadline.timeout()
            doc = _envelope(sock, deadline)
            if action == 2:
                result = wire._list(doc)
                wire._eof(sock, deadline)
                return result
            return wire._download(doc, "allone.txt", listed_size, sock, deadline)
    except socket.timeout:
        raise ExportError("socket_timeout") from None
    except OSError:
        raise ExportError("socket_error") from None


def read_config(host, port) -> bytes:
    """Return verified raw bytes; at most one listing and one download, no retry.

    The loopback endpoint must already exist. The shared wire Deadline bounds
    both requests together to 20 seconds and every socket operation to 2 seconds.
    MissingFile/ExportError expose fixed codes without remote/OS error text.
    """
    endpoint = _endpoint(host, port)
    deadline = wire.Deadline()
    rows = _exchange(endpoint, 2, CONFIG_DIRECTORY, deadline)
    deadline.timeout()
    if "allone.txt" not in rows:
        raise MissingFile("missing")
    size = rows["allone.txt"]
    if size > MAX_FILE:
        raise ExportError("file_size_out_of_bounds")
    raw = _exchange(endpoint, 1, CONFIG_FILE, deadline, size)
    deadline.timeout()
    return raw


def _numeric_string(value, base, maximum):
    if type(value) is not str:
        # Valid JSON numbers do not establish the native string schema. Do not
        # transform e.g. integer 700 into hexadecimal text "700".
        if type(value) is int:
            raise ExportError("unsupported_config_field_type")
        raise ExportError("invalid_config_field_type")
    pattern = _HEX if base == 16 else _DECIMAL
    if not pattern.fullmatch(value):
        raise ExportError("invalid_config_number")
    number = int(value, base)
    if number > maximum:
        raise ExportError("unsupported_config_value")
    return number


def _result_code(value):
    if type(value) is str:
        if not _RESULT_CODE.fullmatch(value):
            raise ExportError("invalid_result_code")
        number = int(value, 10)
    elif type(value) is int:
        number = value
    else:
        raise ExportError("invalid_result_code")
    if not -(2 ** 31) <= number < 2 ** 31:
        raise ExportError("invalid_result_code")
    return number


def _project_row(row):
    if type(row) is not dict or any(key not in row for key in _REQUIRED_FIELDS):
        raise ExportError("invalid_config_schema")
    for key, maximum in _HEX_FIELDS.items():
        _numeric_string(row[key], 16, maximum)
    for key, maximum in _DECIMAL_FIELDS.items():
        _numeric_string(row[key], 10, maximum)
    for key in _BIT_FIELDS:
        value = row[key]
        if type(value) is not int:
            if type(value) is str and _DECIMAL.fullmatch(value):
                raise ExportError("unsupported_config_field_type")
            raise ExportError("invalid_config_field_type")
        if not 0 <= value <= 63:
            raise ExportError("unsupported_config_value")
    if row["startBit"] > row["endBit"]:
        raise ExportError("unsupported_config_bit_range")
    projected = {key: row[key] for key in _REQUIRED_FIELDS}
    if "checkDidList" in row:
        parameters = row["checkDidList"]
        if type(parameters) is not list:
            raise ExportError("invalid_config_parameter_list")
        # Native allocation requires a positive count. An absent list is a
        # separate source branch, so do not manufacture [] for that branch.
        if not parameters:
            raise ExportError("unsupported_config_parameter_list")
        if any(type(value) is not str or not _PARAMETER.fullmatch(value)
               for value in parameters):
            raise ExportError("invalid_config_parameter")
        # Empty and odd-length strings have native meanings; retain them as
        # source strings only, with no byte/DID interpretation or padding.
        projected["checkDidList"] = parameters[:]
    return projected


def project_config(raw) -> dict:
    """Return an allowlisted, non-partial projection with raw byte count/hash.

    Only numeric resultCode and the nine known resultData fields can survive.
    Unknown/private fields and server messages are dropped. A nonzero code
    retains the source code but never rows. Malformed or unsupported content
    returns fixed status/error codes and no rows, even after an earlier good row.
    No result establishes live use, units, diagnostic safety or applicability.
    """
    if type(raw) is not bytes:
        raise ExportError("invalid_config_bytes")
    report = {"schemaVersion": 1, "status": "invalid_config", "error": None,
              "rawSizeBytes": len(raw), "rawSha256": hashlib.sha256(raw).hexdigest(),
              "resultCode": None, "resultData": []}
    if len(raw) > MAX_FILE:
        report["error"] = "file_size_out_of_bounds"
        return report
    try:
        doc = _json(raw)
        if type(doc) is not dict or "resultCode" not in doc:
            raise ExportError("invalid_config_schema")
        code = _result_code(doc["resultCode"])
        report["resultCode"] = doc["resultCode"]
        if code != 0:
            report["status"] = "remote_result"
            return report
        if type(doc.get("resultData")) is not list:
            raise ExportError("invalid_config_schema")
        rows = [_project_row(row) for row in doc["resultData"]]
        report.update(status="complete", resultData=rows)
    except ExportError as error:
        code = str(error)
        report["status"] = "unsupported_config" if code.startswith("unsupported_") else "invalid_config"
        report["error"] = code
    return report
