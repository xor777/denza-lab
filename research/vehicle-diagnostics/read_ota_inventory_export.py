#!/usr/bin/env python3
"""Project four fixed caches from a separately authorized, already-open export.

Offline preparation is not permission to start the stock all-interface server.
This client never starts services, invokes ADB, uploads, deletes, or queries ECUs.
Only fixed parent listings and four fixed downloads are requested, with the
tested read_ecu_cache_export protocol helpers. No arbitrary-path option exists.
Raw XML/JSON stays in memory; only explicit ECU/script fields and source hashes
are written. The caller owns server shutdown even after a failed export.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import socket
import struct
import sys
from xml.parsers import expat

import read_ecu_cache_export as wire


ALLOWED_FILES = (
    "/data/user/0/com.byd.otaupdate/shared_prefs/share_data_prefs.xml",
    "/data/user/0/com.byd.otaupdate2/shared_prefs/share_data_prefs.xml",
    "/data/ota_package/ota/result/upgradeInfo.json",
    "/data/user_de/0/com.byd.spotinspection/shared_prefs/com.byd.spotinspection.xml",
)
ALLOWED_DIRECTORIES = frozenset(str(PurePosixPath(p).parent) for p in ALLOWED_FILES)
ECU_FIELDS = (
    "ecuId", "ecuDiaId", "logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid",
    "ecuSoftCode", "ecuSoftVer", "ecuHardVer", "ecuConfigCode", "ecuConfigVer",
    "ecuReprogramType", "ecuReprogramWay", "ecuUpdateStyle", "isRepairEcu",
)
SCRIPT_FIELDS = ("scriptCode", "scriptVersion", "scriptFormatVer", "forStation", "scriptName")
PREF_KEY = "ecuDiagnosisResultListInfo"
SOURCE_KEYS = (PREF_KEY, PREF_KEY, "ecuModel", "RUNNING_TASK")
OUTPUT_NAMES = ("01-ota-ecu-inventory.json", "02-ota2-ecu-inventory.json",
                "03-upgrade-ecu-inventory.json", "04-spot-script-manifest.json")
ExportError = wire.ExportError
_NO_DATA = object()


def _request(action, path):
    if type(action) is not int:
        raise ExportError("request_not_allowed")
    allowed = ALLOWED_FILES if action == 1 else ALLOWED_DIRECTORIES if action == 2 else ()
    if path not in allowed:
        raise ExportError("request_not_allowed")
    key = "filePath" if action == 1 else "directory"
    payload = json.dumps({key: wire.EXPORT_PREFIX + path.lstrip("/")},
                         separators=(",", ":")).encode("utf-8")
    return struct.pack(">III", wire.MAGIC, action, len(payload)) + payload


def _exchange(endpoint, action, path, deadline, listed_size=None):
    # Keep our allowlist isolated: do not mutate or broaden the original client.
    request = _request(action, path)
    try:
        with socket.create_connection(endpoint, timeout=deadline.timeout()) as sock:
            sock.settimeout(deadline.timeout())
            sock.sendall(request)
            deadline.timeout()
            doc = wire._envelope(sock, deadline)
            if action == 2:
                result = wire._list(doc)
                wire._eof(sock, deadline)
                return result
            return wire._download(doc, PurePosixPath(path).name, listed_size, sock, deadline)
    except socket.timeout:
        raise ExportError("socket_timeout") from None
    except OSError:
        raise ExportError("socket_error") from None


def _json(raw):
    try:
        return json.loads(raw, object_pairs_hook=wire._pairs, parse_constant=wire._constant)
    except (UnicodeError, ValueError, RecursionError):
        raise ExportError("invalid_source_json") from None


def _preference(raw, wanted):
    """Read one Android string preference; never resolve any DTD or entity."""
    parser = expat.ParserCreate()
    stack, seen, selected = [], set(), []
    found = False

    def forbidden(*_):
        raise ExportError("xml_dtd_or_entity_forbidden")

    def start(name, attrs):
        nonlocal found
        depth = len(stack)
        if depth == 0:
            if name != "map" or attrs:
                raise ExportError("invalid_preferences_xml")
        elif depth == 1:
            key = attrs.get("name")
            if not isinstance(key, str) or not key:
                raise ExportError("invalid_preferences_xml")
            if key in seen:
                raise ExportError("duplicate_preference_key")
            seen.add(key)
            if key == wanted:
                if name != "string" or set(attrs) != {"name"}:
                    raise ExportError("invalid_preference_type")
                found = True
        elif len(stack) >= 2 and stack[1][1].get("name") == wanted:
            raise ExportError("invalid_preference_type")
        stack.append((name, attrs))

    def end(_name):
        stack.pop()

    def text(value):
        if len(stack) == 2 and stack[1][1].get("name") == wanted:
            selected.append(value)
        elif len(stack) == 1 and value.strip():
            raise ExportError("invalid_preferences_xml")

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = text
    parser.StartDoctypeDeclHandler = forbidden
    parser.EntityDeclHandler = forbidden
    parser.ExternalEntityRefHandler = forbidden
    parser.UnparsedEntityDeclHandler = forbidden
    parser.NotationDeclHandler = forbidden
    parser.ProcessingInstructionHandler = forbidden
    try:
        parser.Parse(raw, True)
    except (expat.ExpatError, UnicodeError, ValueError, RecursionError):
        raise ExportError("invalid_preferences_xml") from None
    return "".join(selected) if found else None


def _project_rows(rows, fields, identity):
    if type(rows) is not list:
        raise ExportError("invalid_inventory_type")
    projected = []
    for row in rows:
        if type(row) is not dict or type(row.get(identity)) is not str:
            raise ExportError("invalid_inventory_row")
        result = {}
        for key in fields:
            if key in row:
                value = row[key]
                if value is not None and type(value) is not str:
                    raise ExportError("invalid_inventory_field_type")
                result[key] = value
        projected.append(result)
    return projected


def project_source(path, raw):
    """Project data; missing or empty/null preferences are distinct non-data states."""
    if path not in ALLOWED_FILES:
        raise ExportError("request_not_allowed")
    if type(raw) is not bytes or len(raw) > wire.MAX_FILE:
        raise ExportError("file_size_out_of_bounds")
    index = ALLOWED_FILES.index(path)
    key = SOURCE_KEYS[index]
    if index == 2:
        doc = _json(raw)
        if type(doc) is not dict or "ecuModel" not in doc:
            raise ExportError("invalid_upgrade_schema")
        rows = _project_rows(doc["ecuModel"], ECU_FIELDS, "ecuId")
        selection, collection = "ecuModel", "ecus"
    else:
        value = _preference(raw, key)
        if value is None:
            return None
        if not value.strip():
            return _NO_DATA
        doc = _json(value)
        if doc is None:
            return _NO_DATA
        if index < 2:
            rows = _project_rows(doc, ECU_FIELDS, "ecuId")
            selection, collection = key, "ecus"
        else:
            if type(doc) is not dict or type(doc.get("downloadCommand")) is not dict:
                raise ExportError("invalid_running_task_schema")
            rows = _project_rows(doc["downloadCommand"].get("scriptList"),
                                 SCRIPT_FIELDS, "scriptCode")
            selection, collection = "RUNNING_TASK.downloadCommand.scriptList", "scripts"
    return {"schemaVersion": 1, "source": path, "sourceKey": key, "selection": selection,
            "rawSizeBytes": len(raw), "rawSha256": hashlib.sha256(raw).hexdigest(),
            collection: rows}


def run_export(host, port, output):
    """Read each fixed source once; keep only verified, parsed projections."""
    endpoint = wire._endpoint(host, port)
    output = Path(output)
    try:
        output.mkdir(mode=0o700)
        os.chmod(output, 0o700)
    except FileExistsError:
        raise ExportError("output_already_exists") from None
    except OSError:
        raise ExportError("output_create_failed") from None
    deadline = wire.Deadline()
    report = {"schemaVersion": 1, "status": "complete", "error": None,
              "limits": {"fileBytes": wire.MAX_FILE, "jsonBytes": wire.MAX_JSON,
                         "socketSeconds": wire.IO_TIMEOUT, "totalSeconds": wire.TOTAL_TIMEOUT},
              "files": [{"source": path, "sourceKey": SOURCE_KEYS[i], "status": "not_attempted"}
                        for i, path in enumerate(ALLOWED_FILES)]}
    current = None
    try:
        for index, row in enumerate(report["files"]):
            current = row
            path = row["source"]
            parent, name = str(PurePosixPath(path).parent), PurePosixPath(path).name
            deadline.timeout()
            try:
                listing = _exchange(endpoint, 2, parent, deadline)
            except wire.MissingFile:
                listing = {}
            if name not in listing:
                row["status"] = "missing"
                continue
            if listing[name] > wire.MAX_FILE:
                raise ExportError("file_size_out_of_bounds")
            try:
                raw = _exchange(endpoint, 1, path, deadline, listing[name])
            except wire.MissingFile:
                row["status"] = "missing"
                continue
            row.update(rawSizeBytes=len(raw), rawSha256=hashlib.sha256(raw).hexdigest())
            projection = project_source(path, raw)
            del raw
            deadline.timeout()
            if projection is None:
                row["status"] = "key_missing"
                continue
            if projection is _NO_DATA:
                row["status"] = "key_no_data"
                continue
            serialized = (json.dumps(projection, indent=2, ensure_ascii=True) + "\n").encode("utf-8")
            filename = OUTPUT_NAMES[index]
            wire._save_new(output / filename, serialized)
            row.update(status="saved", savedAs=filename, sizeBytes=len(serialized),
                       sha256=hashlib.sha256(serialized).hexdigest(),
                       recordCount=len(projection.get("ecus", projection.get("scripts", []))))
        deadline.timeout()
    except (ExportError, OSError) as error:
        code = str(error) if isinstance(error, ExportError) else "output_write_failed"
        report.update(status="aborted", error=code)
        if current is not None and current["status"] == "not_attempted":
            current.update(status="failed", error=code)
    try:
        wire._save_new(output / "report.json", (json.dumps(report, indent=2) + "\n").encode("utf-8"))
    except OSError:
        raise ExportError("report_write_failed") from None
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", choices=tuple(wire.LOOPBACK), default="127.0.0.1")
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
                      "missing": sum(row["status"] in ("missing", "key_missing", "key_no_data")
                                     for row in report["files"])}))
    return 0 if report["status"] == "complete" else 1


if __name__ == "__main__":
    sys.exit(main())
