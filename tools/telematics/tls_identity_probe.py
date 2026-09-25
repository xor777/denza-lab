#!/usr/bin/env python3
"""Bounded TLS adapter; CLI defaults to a TLS-only preview.

The live mode reads three public certificates and permits at most one stock
signature. The stock APIs may wake/initialize/recover the chip. It sends no
DiLink data from its own CLI. Explicit host callers can request bounded
bootstrap/status exchanges. OpenSSL checks
the server chain and hostname before the external signing callback can run.
"""
import argparse
import base64
import datetime as dt
import json
import os
from pathlib import Path
import re
import select
import socket
import ssl
import subprocess
import tempfile
import threading
import time

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa, utils
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

from local_identity_probe import adb_read, decode_parcel, LIBRARY_HASH, RAW_RSA_PARAMETER, SHA256_DIGEST_INFO

HOST = "dilinkreg-cn.denzacloud.com"
PORT = 6001  # public double_apn success branch: cloudmanager 0x4c22c-0x4c238
CLIENT_FINGERPRINT = "8a220bf91c7d9eefba6b942665413dfc5d3c0a2ac8b6e4a45bb89cd899fad832"
DEFAULT_BINARY = Path(__file__).resolve().parents[2] / "captures/telematics-20260923/tls-wifi/tls_registration_client"


def pem(cert):
    return cert.public_bytes(serialization.Encoding.PEM)


def check_block(block):
    prefix = b"\x00\x01" + b"\xff" * 202 + b"\x00" + SHA256_DIGEST_INFO
    if len(block) != 256 or not block.startswith(prefix):
        raise ValueError("Unexpected signing block; no stock call")
    return block[-32:]


def run_native(binary, transport, hostname, root, leaf, issuer, signer, application=None):
    """Certificates are temporary 0600 files in a 0700 directory, then removed."""
    with tempfile.TemporaryDirectory(prefix="denza-tls-public-") as directory:
        files = []
        for name, cert in (("root", root), ("leaf", leaf), ("issuer", issuer)):
            if cert is None:
                files.append("-")
                continue
            path = Path(directory) / (name + ".pem")
            # The server sends its leaf only. Supply the known intermediate to
            # chain building; PARTIAL_CHAIN is not enabled, root remains required.
            path.write_bytes(pem(cert) + (pem(issuer) if name == "root" and issuer is not None else b""))
            path.chmod(0o600)
            files.append(str(path))
        command = [str(binary), str(transport.fileno()), hostname, *files]
        if application is not None:
            packet = application[0]
            if len(packet) not in (69, 101, 117) or packet[:4] != bytes.fromhex("fefe0300") or packet[4] != len(packet)-5:
                raise ValueError("Bootstrap packet shape")
            command.append("--status-once" if len(application) == 3 else "--bootstrap-once")
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, pass_fds=(transport.fileno(),))
        calls = 0
        sent = received = False
        decoded = None
        status_requested = False
        try:
            # Child has a 35-second alarm; bounded fixed-size signing protocol.
            while True:
                line = process.stdout.readline(4096)
                if not line:
                    break
                if line == b"READY\n" and application is not None and calls == 1 and not sent:
                    process.stdin.write(application[0].hex().encode() + b"\n")
                    process.stdin.flush()
                    sent = True
                    continue
                if line == b"READY_STATUS\n" and application is not None and len(application) == 3 and received and not status_requested:
                    status_requested = True
                    if not decoded or not decoded.get("login_accepted"):
                        process.stdin.write(b"STOP\n")
                    else:
                        packet = application[2]()
                        if len(packet) != 165 or packet[:5] != bytes.fromhex("fefe0300a0"):
                            raise ValueError("Status packet shape")
                        process.stdin.write(packet.hex().encode() + b"\n")
                    process.stdin.flush()
                    continue
                if line.startswith(b"DATA ") and sent and not received and re.fullmatch(rb"DATA [0-9a-f]{106,2048}\n", line):
                    received = True
                    try:
                        decoded = application[1](bytes.fromhex(line[5:-1].decode()))
                    except Exception as error:
                        decoded = {"validation_failed": True, "error_type": type(error).__name__}
                    continue
                if calls or not re.fullmatch(rb"SIGN [0-9a-f]{512}\n", line):
                    raise ValueError("Unexpected signer request; no retry")
                block = bytes.fromhex(line[5:-1].decode())
                check_block(block)
                calls += 1
                signature = signer(block)
                if len(signature) != 256:
                    raise ValueError("Unexpected signature length")
                process.stdin.write(signature.hex().encode() + b"\n")
                process.stdin.flush()
            process.wait(timeout=5)
            metadata = process.stderr.read(32768).decode()
            return {"returncode": process.returncode, "signer_calls": calls,
                    "application_requested": sent, "application_response": decoded, "status_requested": status_requested,
                    "events": [json.loads(line) for line in metadata.splitlines()]}
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()


def make_cert(key, issuer_key, issuer_name, name, *, ca=False, server=False):
    now = dt.datetime.now(dt.timezone.utc)
    builder = (x509.CertificateBuilder().subject_name(name).issuer_name(issuer_name)
               .public_key(key.public_key()).serial_number(x509.random_serial_number())
               .not_valid_before(now-dt.timedelta(minutes=1)).not_valid_after(now+dt.timedelta(days=1))
               .add_extension(x509.BasicConstraints(ca=ca, path_length=None), critical=True)
               .add_extension(x509.KeyUsage(True, False, not ca, False, False, ca, ca, False, False), critical=True))
    if not ca:
        builder = builder.add_extension(x509.ExtendedKeyUsage([
            ExtendedKeyUsageOID.SERVER_AUTH if server else ExtendedKeyUsageOID.CLIENT_AUTH]), critical=False)
    if server:
        builder = builder.add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False)
    return builder.sign(issuer_key, hashes.SHA256())


def self_test(binary):
    """Independent local TLS server, ephemeral generated keys; no ADB/cloud."""
    ca_key, server_key, client_key = [rsa.generate_private_key(public_exponent=65537, key_size=2048) for _ in range(3)]
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Ephemeral test CA")])
    ca = make_cert(ca_key, ca_key, ca_name, ca_name, ca=True)
    server = make_cert(server_key, ca_key, ca_name, x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "localhost")]), server=True)
    client = make_cert(client_key, ca_key, ca_name, x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test client")]))
    cases = []
    for name in ("mutual_tls", "wrong_hostname", "wrong_ca", "bad_signature", "omitted_server_intermediate", "registration_fragmented", "registration_wrong_hostname", "discovery_fragmented", "login_fragmented", "status_after_login", "status_rejected_login"):
        status_case = name.startswith("status_")
        packet_length = 69 if name == "discovery_fragmented" else 117 if name == "login_fragmented" or status_case else 101
        exchange = name.endswith("_fragmented") or status_case
        with tempfile.TemporaryDirectory(prefix="denza-tls-selftest-") as directory:
            root = Path(directory)
            chain_issuer, case_server, case_client = None, server, client
            if name == "omitted_server_intermediate":
                intermediate_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
                intermediate_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Ephemeral subCA")])
                chain_issuer = make_cert(intermediate_key, ca_key, ca_name, intermediate_name, ca=True)
                case_server = make_cert(server_key, intermediate_key, intermediate_name, server.subject, server=True)
                case_client = make_cert(client_key, intermediate_key, intermediate_name, client.subject)
            for filename, data in (("server.pem", pem(case_server)), ("ca.pem", pem(ca)),
                                   ("key.pem", server_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))):
                path = root/filename
                path.write_bytes(data)
                path.chmod(0o600)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_2
            context.set_ciphers("ECDHE-RSA-AES128-GCM-SHA256")
            context.load_cert_chain(str(root/"server.pem"), str(root/"key.pem"))
            context.load_verify_locations(str(root/"ca.pem"))
            context.verify_mode = ssl.CERT_REQUIRED
            listener = socket.socket()
            listener.bind(("127.0.0.1", 0))
            listener.listen(1)
            listener.settimeout(8)
            outcome = {}
            def serve():
                try:
                    conn, _ = listener.accept()
                    conn.settimeout(8)
                    with context.wrap_socket(conn, server_side=True) as secure:
                        outcome["client_verified"] = bool(secure.getpeercert())
                        if exchange:
                            data = b""
                            while len(data) < packet_length:
                                part = secure.recv(packet_length-len(data))
                                if not part:
                                    break
                                data += part
                            outcome["application_bytes_received"] = len(data)
                            secure.sendall(data[:3])
                            secure.sendall(data[3:19])
                            secure.sendall(data[19:])
                            if status_case:
                                status_data = b""
                                while len(status_data) < 165:
                                    part = secure.recv(165-len(status_data))
                                    if not part:
                                        break
                                    status_data += part
                                outcome["status_bytes_received"] = len(status_data)
                        else:
                            outcome["application_bytes_received"] = len(secure.recv(1))
                except Exception as error:
                    outcome["error_type"] = type(error).__name__
            worker = threading.Thread(target=serve, daemon=True)
            worker.start()
            trust = ca
            if name == "wrong_ca":
                other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
                trust = make_cert(other_key, other_key, ca_name, ca_name, ca=True)
            def sign(block):
                digest = check_block(block)
                if name == "bad_signature":
                    return b"\x00" * 256
                return client_key.sign(digest, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
            with socket.create_connection(listener.getsockname(), timeout=8) as transport:
                # OpenSSL receives a blocking fd; the native alarm bounds it.
                transport.settimeout(None)
                payload = bytes.fromhex("fefe0300") + bytes([packet_length-5]) + bytes(packet_length-5)
                application = (payload, lambda data: {"echo_matches": data == payload}) if exchange or name == "registration_wrong_hostname" else None
                if status_case:
                    application = (payload, lambda data: {"echo_matches": data == payload, "login_accepted": name == "status_after_login"},
                                   lambda: bytes.fromhex("fefe0300a0") + bytes(160))
                result = run_native(binary, transport, "wrong.invalid" if "wrong_hostname" in name else "localhost", trust, case_client, chain_issuer, sign, application)
            worker.join(10)
            listener.close()
            passed = (result["returncode"] == 0 and outcome.get("client_verified") and outcome.get("application_bytes_received") == 0) if name in ("mutual_tls", "omitted_server_intermediate") else result["returncode"] != 0
            if name in ("wrong_hostname", "wrong_ca"):
                passed = passed and result["signer_calls"] == 0
            if name == "registration_wrong_hostname":
                passed = passed and result["signer_calls"] == 0 and not result["application_requested"]
            if exchange:
                passed = result["returncode"] == 0 and outcome.get("application_bytes_received") == packet_length and result["application_response"] == {"echo_matches": True}
            if status_case:
                expected_status_bytes = 165 if name == "status_after_login" else 0
                passed = outcome.get("status_bytes_received") == expected_status_bytes and result["application_response"]["echo_matches"]
                passed = passed and ((result["returncode"] == 0) == (name == "status_after_login"))
            if name == "bad_signature":
                passed = passed and result["signer_calls"] == 1
            cases.append({"case": name, "passed": bool(passed), "native": result, "server": outcome})
    return {"mode": "self_test", "cloud_contacted": False, "adb_used": False,
            "all_passed": all(item["passed"] for item in cases), "cases": cases}


class AdbTransport:
    """Single bounded raw TCP stream from shell UID on the car, no installation."""
    def __init__(self, serial, host=HOST, port=PORT):
        self.local, self.bridge = socket.socketpair()
        self.stats = {"to_car_bytes": 0, "from_car_bytes": 0}
        # exec-out copies only remote stdout; shell -T forwards stdin as well.
        self.process = subprocess.Popen(["adb", "-s", serial, "shell", "-T", "toybox", "nc",
                                         "-w", "10", "-W", "30", host, str(port)],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.thread = threading.Thread(target=self.pump, daemon=True)
        self.thread.start()

    def pump(self):
        try:
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                ready, _, _ = select.select([self.bridge, self.process.stdout], [], [], 1)
                for source in ready:
                    data = os.read(source.fileno(), 16384)
                    if not data:
                        return
                    if source is self.bridge:
                        self.stats["to_car_bytes"] += len(data)
                        if self.stats["to_car_bytes"] > 131072:
                            raise ValueError("Transport byte cap")
                        self.process.stdin.write(data)
                        self.process.stdin.flush()
                    else:
                        self.stats["from_car_bytes"] += len(data)
                        if self.stats["from_car_bytes"] > 131072:
                            raise ValueError("Transport byte cap")
                        self.bridge.sendall(data)
        except Exception as error:
            self.stats["error_type"] = type(error).__name__
        finally:
            self.bridge.close()

    def close(self):
        self.local.close()
        self.thread.join(2)
        if self.process.poll() is None:
            self.process.terminate()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=2)
        self.stats["adb_returncode"] = self.process.returncode
        # netcat diagnostics contain only endpoint/connectivity errors, not TLS data.
        self.stats["transport_stderr"] = self.process.stderr.read(2048).decode(errors="replace")
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()


def execute(binary, serial, report, application=None, host=HOST, port=PORT):
    report["phase"] = "transport_and_identity_check"
    if adb_read(serial, ["get-state"]).strip() != "device":
        raise RuntimeError("Existing transport not ready")
    if adb_read(serial, ["shell", "sha256sum", "/system/lib64/libsafekeyservice.so"]).split()[0] != LIBRARY_HASH:
        raise RuntimeError("Reviewed library mismatch")
    report["route"] = adb_read(serial, ["shell", "ip", "route", "get", "1.1.1.1"]).strip()
    report["phase"] = "public_certificate_chain"
    certs = []
    report["public_certificates"] = []
    for selector in (5, 6, 7):
        reply = adb_read(serial, ["shell", "service", "call", "safekeyservice", "14", "i32", "10102", "i32", str(selector)])
        _, encoded, length = decode_parcel(reply)
        cert = x509.load_der_x509_certificate(base64.b64decode("".join(encoded.split()), validate=True))
        certs.append(cert)
        try:
            is_ca = cert.extensions.get_extension_for_class(x509.BasicConstraints).value.ca
        except x509.ExtensionNotFound:
            is_ca = None
        report["public_certificates"].append({"selector": selector, "sha256": cert.fingerprint(hashes.SHA256()).hex(),
                                             "reported_length": length, "is_ca": is_ca,
                                             "not_after": cert.not_valid_after_utc.isoformat()})
    root, issuer, leaf = certs
    if leaf.fingerprint(hashes.SHA256()).hex() != CLIENT_FINGERPRINT:
        raise RuntimeError("Client certificate changed since approved local test")
    root.verify_directly_issued_by(root)
    issuer.verify_directly_issued_by(root)
    leaf.verify_directly_issued_by(issuer)
    now = dt.datetime.now(dt.timezone.utc)
    if any(not (cert.not_valid_before_utc <= now <= cert.not_valid_after_utc) for cert in certs):
        raise ValueError("Client certificate chain outside validity interval")
    if not all(item["is_ca"] for item in report["public_certificates"][:2]):
        raise ValueError("Root/issuer lacks CA constraint")
    public = leaf.public_key()
    if not isinstance(public, rsa.RSAPublicKey) or public.key_size != 2048:
        raise ValueError("Unsupported client public key")
    report["client_chain_signatures_verified"] = True
    signatures = 0
    def sign(block):
        nonlocal signatures
        digest = check_block(block)
        if signatures:
            raise RuntimeError("One-signature budget exhausted")
        signatures += 1
        report["stock_signature_calls"] = signatures
        reply = adb_read(serial, ["shell", "service", "call", "safekeyservice", "6", "i32", "10106", "i32", str(RAW_RSA_PARAMETER), "s16", block.hex(), "i32", "256"])
        _, encoded, _ = decode_parcel(reply)
        if not re.fullmatch(r"[0-9a-fA-F]{512}", encoded):
            raise ValueError("Unexpected signature reply")
        signature = bytes.fromhex(encoded)
        public.verify(signature, digest, padding.PKCS1v15(), utils.Prehashed(hashes.SHA256()))
        report["tls_signature_locally_verified"] = True
        return signature
    report["phase"] = "single_tls_handshake"
    report["cloud_contacted"] = True
    transport = AdbTransport(serial, host, port)
    try:
        report["native"] = run_native(binary, transport.local, host, root, leaf, issuer, sign, application)
    finally:
        transport.close()
        report["transport"] = transport.stats
    report["phase"] = "complete"
    events = report["native"]["events"]
    report["mutual_tls_verified"] = any(e.get("handshake_complete") and e.get("verify_result") == 0 for e in events)
    report["application_bytes_sent"] = sum(max(0, e["bytes"]) for e in events if e.get("event") == "application_write")
    report["registration_sent"] = report["application_bytes_sent"] == 101
    report["status_bytes_sent"] = sum(max(0, e["bytes"]) for e in events if e.get("event") == "status_write")
    report["telemetry_sent"] = report["status_bytes_sent"] == 165


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--self-test", action="store_true")
    group.add_argument("--execute", action="store_true")
    parser.add_argument("--binary", type=Path, default=DEFAULT_BINARY)
    parser.add_argument("--serial", default="127.0.0.1:5555")
    args = parser.parse_args()
    if args.self_test:
        result = self_test(args.binary)
        print(json.dumps(result, indent=2))
        raise SystemExit(0 if result["all_passed"] else 1)
    report = {"mode": "execute" if args.execute else "preview", "endpoint": f"{HOST}:{PORT}",
              "cloud_contacted": False, "stock_signature_calls": 0, "application_bytes_sent": 0,
              "registration_sent": False, "telemetry_sent": False, "official_phone_update_verified": False}
    if args.execute:
        report["started_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        try:
            execute(args.binary, args.serial, report)
        except Exception as error:
            report.update({"failed": True, "error_type": type(error).__name__, "instruction": "Stop; no automatic retry"})
        report["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
    else:
        report["proposal"] = "Read public certificates 5/6/7, one TLS handshake over car Wi-Fi, at most one stock signature; no application data or retry"
        report["stock_side_effects"] = "Chip wake and possible stock initialization/PIN recovery"
    print(json.dumps(report, indent=2))
    if args.execute and not report.get("mutual_tls_verified"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
