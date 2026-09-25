#!/usr/bin/env python3
"""One owner-authorized DiLink bootstrap exchange over factory mutual TLS.

Default is preview. Vehicle crypto inputs remain in memory. The optional
owner-only input file supplies the owner's original SIM pair, without modem
writes. This can change the server's registration record for the owner's car.
CLI supports registration/discovery/login. status_upload_probe may supply one
reviewed status body after validated login. No heartbeat or vehicle control.
"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/telematics-firmware"))
from registration_packet import build_registration, build_discovery, build_login, build_status, decode_response
from tls_identity_probe import adb_read, execute, DEFAULT_BINARY, HOST, PORT
from cloud_identity import CloudIdentity, load_identity

OWNER_VIN_SHA256 = "102d46beb9421745e31923bdceb59c3170869bfbe9ff0c2cca69c64728664938"


def read_buffer(serial, device, fid, expected_length):
    signed = fid - 0x100000000 if fid >= 0x80000000 else fid
    text = adb_read(serial, ["shell", "service", "call", "autoservice", "13", "i32", str(device), "i32", str(signed)])
    if len(text) > 4096 or "Result: Parcel(" not in text:
        raise ValueError("Native buffer reply shape")
    words = []
    for line in text.split("Result: Parcel(", 1)[1].splitlines():
        line = line.split("'", 1)[0]
        line = re.sub(r"^\s*0x[0-9a-fA-F]+:\s*", "", line)
        words.extend(re.findall(r"\b[0-9a-fA-F]{8}\b", line))
    data = b"".join(int(word, 16).to_bytes(4, "little") for word in words)
    if len(data) < 8:
        raise ValueError("Native buffer header")
    status, length = struct.unpack_from("<iI", data)
    if status != 0 or length != expected_length or len(data) != 8 + ((length + 3) & ~3):
        raise ValueError("Native buffer status or length")
    return data[8:8 + length]


def run(serial, binary, report, discovery=False, login=False, status_body=None, transport_source="car", identity_override=None):
    if transport_source not in ("car", "host"):
        raise ValueError("Unknown transport source")
    if identity_override is not None and not isinstance(identity_override, CloudIdentity):
        raise ValueError("Invalid identity source")
    if discovery and identity_override is not None:
        raise ValueError("Discovery does not use SIM identity")
    report["identity_source"] = "provided_original_pair" if identity_override is not None else "modem"
    if status_body is not None and not login:
        raise ValueError("Status requires validated application login")
    report["phase"] = "source_identity"
    baseline = ROOT / "captures/telematics-20260923/battery-upload/extra-files/system/lib64"
    for name in ("libbydauto.so", "libbydautoservice.so"):
        expected = hashlib.sha256((baseline/name).read_bytes()).hexdigest()
        actual = adb_read(serial, ["shell", "sha256sum", "/system/lib64/" + name]).split()[0]
        if actual != expected:
            raise ValueError("Reviewed autoservice library mismatch")
    vin = read_buffer(serial, 1001, 0x9900021a, 17)
    if hashlib.sha256(vin).hexdigest() != OWNER_VIN_SHA256:
        raise ValueError("Different vehicle")
    params = read_buffer(serial, 1034, 0x99000005, 33)
    if params[0] == 0:
        raise ValueError("Cloud parameters not ready")
    key, uuid = params[1:17], params[17:33]
    timestamp = int(time.time())
    command, host, port = (200, "dilinkaddr-cn.denzacloud.com", 6021) if discovery else (211, HOST, PORT)
    if login:
        # Exact endpoint from the verified 200 response, not a guessed server.
        proof = json.loads((ROOT/"captures/telematics-20260923/registration-inputs/live-discovery-1.json").read_text())
        received = proof["native"]["application_response"]
        if not proof["mutual_tls_verified"] or not received["vin_matches"] or not received["crc_valid"]:
            raise ValueError("Missing authenticated discovery evidence")
        host, port = received["working_hostname"], received["working_port"]
        if (host, port) != ("dilinknat0-cn.denzacloud.com", 6041):
            raise ValueError("Unreviewed working endpoint")
        command = 220
        imsi = identity_override.imsi if identity_override is not None else adb_read(
            serial, ["shell", "getprop", "ril.imsi"]).strip().encode("ascii")
        device_serial = adb_read(serial, ["shell", "getprop", "debug.ro.serialno"]).strip().encode("ascii")
        packet = build_login(vin, imsi, device_serial, os.urandom(16), key, uuid, timestamp)
        report["serial_property_present"] = bool(device_serial)
    elif discovery:
        packet = build_discovery(vin, key, uuid, timestamp)
    else:
        imsi = identity_override.imsi if identity_override is not None else adb_read(
            serial, ["shell", "getprop", "ril.imsi"]).strip().encode("ascii")
        iccid = identity_override.iccid if identity_override is not None else adb_read(
            serial, ["shell", "getprop", "ril.csim.iccid"]).strip().encode("ascii")
        packet = build_registration(vin, imsi, iccid, key, uuid, timestamp)
    report["inputs"] = {"reviewed_libraries_match": True, "vin_matches_owner_car": True,
                        "cloud_parameters_valid": True, "packet_length": len(packet),
                        "timestamp": timestamp, "command": command}
    def receive(data):
        diagnostic = {}
        try:
            metadata, body = decode_response(data, vin, key, uuid, diagnostic)
        except ValueError as error:
            return {"validation_failed": True, "stage": str(error), "diagnostics": diagnostic}
        if metadata["command"] != command:
            return {**metadata, "unexpected_command": True, "expected_command": command}
        if login:
            metadata["login_status"] = metadata["reply_flag"]
            metadata["login_accepted"] = metadata["reply_flag"] == 1
        elif discovery:
            if len(body) < 24 or len(body) < 23 + body[22]:
                raise ValueError("Discovery body shape")
            domain = body[23:23+body[22]].decode("ascii")
            if not re.fullmatch(r"[A-Za-z0-9.-]{1,253}", domain):
                raise ValueError("Discovery hostname")
            metadata.update({"working_hostname": domain, "working_port": int.from_bytes(body[:2], "big"), "discovery_received": True})
        else:
            if len(body) != 1:
                raise ValueError("Registration status shape")
            metadata["registration_status"] = body[0]
            metadata["registration_accepted"] = body[0] == 1
        return metadata
    application = (packet, receive)
    if status_body is not None:
        application += (lambda: build_status(vin, key, uuid, int(time.time()), status_body),)
    execute(binary, serial, report, application, host, port, transport_source=transport_source)
    report["registration_accepted"] = bool((report["native"].get("application_response") or {}).get("registration_accepted"))
    report["discovery_received"] = bool((report["native"].get("application_response") or {}).get("discovery_received"))
    report["login_accepted"] = bool((report["native"].get("application_response") or {}).get("login_accepted"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    operation = parser.add_mutually_exclusive_group()
    operation.add_argument("--discover", action="store_true", help="One command 200 server lookup, after successful registration")
    operation.add_argument("--login", action="store_true", help="One command 220 login at the verified working endpoint")
    parser.add_argument("--serial", default="127.0.0.1:5555")
    parser.add_argument("--binary", type=Path, default=DEFAULT_BINARY)
    parser.add_argument("--transport", choices=("car", "host"), default="car",
                        help="TCP origin only; vehicle crypto inputs/signing still come from the pinned car")
    parser.add_argument("--identity-file", type=Path,
                        help="Owner-only JSON with the owner's original iccid/imsi; no modem writes")
    args = parser.parse_args()
    if args.discover and args.identity_file:
        parser.error("Discovery does not use --identity-file")
    endpoint = "dilinknat0-cn.denzacloud.com:6041" if args.login else "dilinkaddr-cn.denzacloud.com:6021" if args.discover else f"{HOST}:{PORT}"
    report = {"mode": "execute" if args.execute else "preview", "endpoint": endpoint,
              "transport_source": args.transport,
              "identity_source": "provided_original_pair" if args.identity_file else "modem",
              "cloud_contacted": False, "stock_signature_calls": 0, "application_bytes_sent": 0,
              "registration_sent": False, "telemetry_sent": False, "official_phone_update_verified": False}
    if args.execute:
        report["started_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        try:
            report["phase"] = "identity_selection"
            identity = load_identity(args.identity_file) if args.identity_file else None
            run(args.serial, args.binary, report, args.discover, args.login,
                transport_source=args.transport, identity_override=identity)
        except Exception as error:
            report.update({"failed": True, "error_type": type(error).__name__, "instruction": "Stop; no automatic retry"})
        report["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
    else:
        report["proposal"] = "Use the selected SIM identity source and real vehicle crypto inputs; one authenticated TLS session and one packet (220 with --login, 200 with --discover, otherwise 211); validate one reply; close"
        report["stock_side_effects"] = "Chip wake and possible stock initialization/PIN recovery; server registration record may change"
    print(json.dumps(report, indent=2))
    if args.execute and not report.get("login_accepted" if args.login else "discovery_received" if args.discover else "registration_accepted"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
