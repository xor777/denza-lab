#!/usr/bin/env python3
"""Offline extraction from provided EOL 3.0.0 plaintext and download metadata.

No network, decryption, credentials, subprocesses or vehicle commands. The output
is research evidence, never a product routing allowlist or a compatibility claim.
Source schema: stock SpotInspection eol.executor.bean.script.Attach / ScriptContent
and eol.communication.http.bean.DownloadResponseVO (2026-10-18 local corpus).
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


class ImportError(ValueError):
    """The input cannot be imported without inventing or resolving information."""


def _object(value, where):
    if not isinstance(value, dict):
        raise ImportError(where + " must be an object")
    return value


def _array(value, where, nonempty=False):
    if not isinstance(value, list) or (nonempty and not value):
        raise ImportError(where + " must be " + ("a nonempty array" if nonempty else "an array"))
    return value


def _text(value, where):
    if not isinstance(value, str) or not value.strip():
        raise ImportError(where + " must be a nonempty string")
    return value


def _identifier(value, where):
    value = _text(value, where)
    if value != value.strip() or any(ord(char) < 32 for char in value):
        raise ImportError(where + " must have no outer whitespace or control characters")
    return value


def _number(value, where, minimum, maximum):
    # JSON booleans are Python ints; reject them and never coerce strings/floats.
    if type(value) is not int or not minimum <= value <= maximum:
        raise ImportError(where + " must be an integer in the supported range")
    return value


def _unique(values, where):
    if len(values) != len(set(values)):
        raise ImportError(where + " contains duplicate identities")
    return values


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ImportError("JSON contains a duplicate key")
        result[key] = value
    return result


def _constant(_value):
    raise ImportError("JSON contains a nonfinite number")


def _read(path):
    path = Path(path)
    raw = path.read_bytes()
    try:
        doc = json.loads(raw.decode("utf-8"), object_pairs_hook=_pairs,
                         parse_constant=_constant)
    except (UnicodeError, json.JSONDecodeError) as error:
        # Do not echo input snippets, which may contain VINs or credentials.
        raise ImportError("Input is not valid UTF-8 JSON") from error
    return _object(doc, "document"), {
        "path": str(path.resolve()), "sha256": hashlib.sha256(raw).hexdigest(),
        "sizeBytes": len(raw),
    }


def import_files(script_path, metadata_path, station=None):
    """Return an evidence projection; reject conflicts before producing output.

    Optional information remains null/unknown. Unknown property names are listed
    without copying their values. A station choice disambiguates metadata only;
    it does not establish model or ECU software applicability.
    """
    script, script_source = _read(script_path)
    metadata, metadata_source = _read(metadata_path)
    unknown_fields, unknowns = [], ["vehicleProfile", "ecuSoftwareApplicability",
                                  "dictionaryCompleteness", "ciphertextIntegrity",
                                  "plaintextMetadataBinding"]

    def fields(obj, known, where):
        unknown_fields.extend(where + "." + key for key in obj if key not in known)

    def optional_text(obj, key, where):
        if obj.get(key) is None:
            unknowns.append(where + "." + key)
            return None
        return _text(obj[key], where + "." + key)

    fields(script, {"business", "formatVer", "protocol", "sender", "receiver",
                    "scriptCode", "scriptName", "scriptVer", "vin", "uuid", "data"}, "script")
    if script.get("business") != "MOTA" or script.get("formatVer") != "3.0.0":
        raise ImportError("Only MOTA EOL script format 3.0.0 is supported")
    code = _identifier(script.get("scriptCode"), "script.scriptCode")
    version = _identifier(script.get("scriptVer"), "script.scriptVer")
    name = _text(script.get("scriptName"), "script.scriptName")
    protocol = _text(script.get("protocol"), "script.protocol")
    fields(metadata, {"code", "message", "scriptSize", "scriptName", "scriptVersion",
                      "scriptCode", "forStations", "systemIds", "MD5Code", "encryPolicyId",
                      "data"}, "metadata")
    if _number(metadata.get("code"), "metadata.code", 0, 2147483647) != 200:
        raise ImportError("Metadata is not a successful download response")
    if _identifier(metadata.get("scriptVersion"), "metadata.scriptVersion") != version:
        raise ImportError("Script and metadata versions conflict")
    for key, expected in [("scriptName", name), ("scriptCode", code)]:
        if metadata.get(key) is not None and _text(metadata[key], "metadata." + key) != expected:
            raise ImportError("Script and metadata " + key + " conflict")

    stations = None
    if metadata.get("forStations") is not None:
        stations = _unique([_identifier(x, "metadata.forStations[]") for x in
                            _array(metadata["forStations"], "metadata.forStations")],
                           "metadata.forStations")
    if station is not None:
        station = _identifier(station, "station")
        if stations is not None and station not in stations:
            raise ImportError("Selected station is not declared in metadata")
        station_source = "explicit_selection" if stations else "user_supplied_unverified"
    elif stations and len(stations) == 1:
        station, station_source = stations[0], "metadata_single_station"
    elif stations:
        raise ImportError("Multiple stations: select one explicitly with --station")
    else:
        station_source = "unknown"
        unknowns.append("station")

    md5 = metadata.get("MD5Code")
    if md5 is not None and (not isinstance(md5, str) or not re.fullmatch(r"[0-9a-fA-F]{32}", md5)):
        raise ImportError("metadata.MD5Code must be a 32-character hexadecimal digest")
    policy = metadata.get("encryPolicyId")
    if policy is not None:
        _number(policy, "metadata.encryPolicyId", 0, 255)
    if metadata.get("scriptSize") is not None:
        _number(metadata["scriptSize"], "metadata.scriptSize", 0, 2147483647)

    data = _object(script.get("data"), "script.data")
    fields(data, {"attach", "systems"}, "script.data")
    attachments = _array(data.get("attach"), "script.data.attach", nonempty=True)
    ecus, by_name, routes = [], {}, {}
    for index, raw_attach in enumerate(attachments):
        source = "script.data.attach[" + str(index) + "]"
        target = "ecus[" + str(index) + "]"
        attach = _object(raw_attach, source)
        fields(attach, {"ecuAbbrName", "ecuName", "bus", "can", "frameSize", "security",
                        "dtcList", "dtcStatusList"}, source)
        abbr = _identifier(attach.get("ecuAbbrName"), source + ".ecuAbbrName")
        if abbr in by_name:
            raise ImportError("Duplicate ECU abbreviation in attachments")
        bus = None
        if attach.get("bus") is not None:
            raw_bus = _object(attach["bus"], source + ".bus")
            fields(raw_bus, {"net", "type"}, source + ".bus")
            bus = {"net": _number(raw_bus.get("net"), source + ".bus.net", 0, 2147483647),
                   "type": _identifier(raw_bus.get("type"), source + ".bus.type")}
        else:
            unknowns.append(target + ".bus")
        raw_can = _object(attach.get("can"), source + ".can")
        fields(raw_can, {"arbBaud", "datBaud", "reqCanId", "rspCanId"}, source + ".can")
        request = _number(raw_can.get("reqCanId"), source + ".can.reqCanId", 1, 0x1FFFFFFF)
        responses = _unique([_number(x, source + ".can.rspCanId[]", 1, 0x1FFFFFFF) for x in
                             _array(raw_can.get("rspCanId"), source + ".can.rspCanId")],
                            source + ".can.rspCanId")
        if not responses:
            # Stock ScriptCheckUtil.java:58 exempts decimal 2015 (0x7DF).
            # Preserve that exception without inventing any responder IDs.
            if request != 0x7DF:
                raise ImportError("Physical request has no response CAN ID")
            unknowns.append(target + ".responseRouting")
        if request in responses:
            raise ImportError("Request and response CAN IDs overlap")
        bus_key = (bus["net"], bus["type"]) if bus else (None, None)
        for address in [request] + responses:
            route = bus_key + (address,)
            # An unspecified bus cannot disambiguate a reused address on any bus.
            if any(old[2] == address and (old[:2] == bus_key or
                   old[:2] == (None, None) or bus_key == (None, None)) for old in routes):
                raise ImportError("Multiple ECU attachments share a CAN route")
            routes[route] = abbr
        can = {"reqCanId": request, "rspCanId": responses}
        for key in ["arbBaud", "datBaud"]:
            can[key] = None
            if raw_can.get(key) is not None:
                can[key] = _number(raw_can[key], source + ".can." + key, 1, 2147483647)
            else:
                unknowns.append(target + ".can." + key)
        frame_size = None
        if attach.get("frameSize") is None:
            unknowns.append(target + ".frameSize")
        else:
            raw_frame = _object(attach["frameSize"], source + ".frameSize")
            fields(raw_frame, {"size", "spanMs"}, source + ".frameSize")
            frame_size = {}
            # Attach.FrameSize (281–321) supplies only Java int fields/setters.
            # No enum/range or consumer establishes transport semantics here.
            # Preserve raw int32 values; do not infer CAN type/DLC from them.
            unknowns.append(target + ".frameSizeSemantics")
            for key in ["size", "spanMs"]:
                frame_size[key] = None
                if raw_frame.get(key) is None:
                    unknowns.append(target + ".frameSize." + key)
                else:
                    frame_size[key] = _number(raw_frame[key], source + ".frameSize." + key,
                                              -(2 ** 31), (2 ** 31) - 1)
        ecu = {"ecuAbbrName": abbr, "ecuName": optional_text(attach, "ecuName", target),
               "bus": bus, "can": can, "frameSize": frame_size,
               "systemIds": [], "sourcePointer": "/data/attach/" + str(index)}
        for key, number_key, text_key, maximum in [
                ("dtcList", "code", "desc", 0xFFFFFF),
                ("dtcStatusList", "mask", "status", 255)]:
            ecu[key] = None
            if attach.get(key) is None:
                unknowns.append(target + "." + key)
                continue
            records, seen = [], set()
            for n, item in enumerate(_array(attach[key], source + "." + key)):
                where = source + "." + key + "[" + str(n) + "]"
                item = _object(item, where)
                fields(item, {number_key, text_key}, where)
                number = _number(item.get(number_key), where + "." + number_key, 0, maximum)
                if number in seen:
                    raise ImportError("Duplicate DTC code or status mask within one ECU")
                seen.add(number)
                record = {number_key: number, text_key: _text(item.get(text_key), where + "." + text_key)}
                if key == "dtcList":
                    record["rawHex"] = format(number, "06X")
                records.append(record)
            ecu[key] = records
        ecus.append(ecu)
        by_name[abbr] = ecu

    systems, seen_systems = [], set()
    for n, raw_system in enumerate(_array(data.get("systems"), "script.data.systems", nonempty=True)):
        where = "script.data.systems[" + str(n) + "]"
        system = _object(raw_system, where)
        fields(system, {"systemId", "name", "desc", "priority", "cases"}, where)
        system_id = _identifier(system.get("systemId"), where + ".systemId")
        if system_id in seen_systems:
            raise ImportError("Duplicate system ID")
        seen_systems.add(system_id)
        case_ids = []
        for m, raw_case in enumerate(_array(system.get("cases"), where + ".cases", nonempty=True)):
            case_where = where + ".cases[" + str(m) + "]"
            case = _object(raw_case, case_where)
            fields(case, {"caseId", "caseName", "ecuAbbrName", "priority", "steps"}, case_where)
            case_ids.append(_identifier(case.get("caseId"), case_where + ".caseId"))
            abbr = _identifier(case.get("ecuAbbrName"), case_where + ".ecuAbbrName")
            if abbr not in by_name:
                raise ImportError("Case references an unknown ECU attachment")
            if system_id not in by_name[abbr]["systemIds"]:
                by_name[abbr]["systemIds"].append(system_id)
        _unique(case_ids, where + ".cases")
        systems.append(system_id)
    if metadata.get("systemIds") is not None:
        declared = []
        for n, item in enumerate(_array(metadata["systemIds"], "metadata.systemIds")):
            where = "metadata.systemIds[" + str(n) + "]"
            item = _object(item, where)
            fields(item, {"systemId", "priority", "funcType"}, where)
            declared.append(_identifier(item.get("systemId"), where + ".systemId"))
        _unique(declared, "metadata.systemIds")
        if set(declared) != set(systems):
            raise ImportError("Script and metadata system inventories conflict")
    else:
        unknowns.append("metadataSystemInventory")
    for n, ecu in enumerate(ecus):
        if not ecu["systemIds"]:
            unknowns.append("ecus[" + str(n) + "].systemLink")

    return {
        "schema": "denza-lab.oem-eol-dictionary-evidence.v1",
        "eligibleForProductAllowlist": False,
        "applicability": {"status": "unverified", "profile": None,
                          "ecuHardware": None, "ecuSoftware": None},
        "provenance": {"plaintext": script_source, "metadata": metadata_source},
        "script": {"code": code, "version": version, "name": name,
                   "format": script["formatVer"], "protocol": protocol,
                   "station": station, "stationSource": station_source,
                   "declaredStations": stations},
        "integrity": {"metadataCode": 200, "declaredCiphertextMd5": md5,
                      "ciphertextMd5Verified": False, "encryptionPolicyId": policy,
                      "note": "Source SHA256 identifies supplied files; metadata MD5 refers to ciphertext, not plaintext."},
        "systemIds": systems, "ecus": ecus,
        "unknowns": sorted(set(unknowns)), "unknownFields": sorted(set(unknown_fields)),
        "omitted": ["VIN/UUID", "tokens", "security attachments", "executable steps", "encrypted data"],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plaintext", type=Path)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("--station", help="Explicit choice if metadata lists multiple stations")
    parser.add_argument("--output", type=Path, help="New JSON file; existing files are never overwritten")
    args = parser.parse_args(argv)
    try:
        result = import_files(args.plaintext, args.metadata, station=args.station)
        output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.output is None:
            sys.stdout.write(output)
        else:
            with args.output.open("x", encoding="utf-8") as stream:
                stream.write(output)
    except (ImportError, OSError) as error:
        print("Import rejected: " + str(error), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
