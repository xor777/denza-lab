#!/usr/bin/env python3
"""Preview a bounded local factory-identity experiment; --execute performs it.

Execution is NOT read-only: the stock certificate/signature methods wake the
chip and may initialize it or enter factory PIN recovery on an error. This
script never requests PIN/key changes, but cannot prevent those internal paths.
It makes no cloud request, installs nothing, and never retries a transaction.
"""

import argparse
import base64
import hashlib
import json
import re
import secrets
import struct
import subprocess

from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa


LIBRARY_HASH = "d6d10902b189b8cb7e8b42b2cf445f01d24407cea3a0c0e44666d1f9f9419f79"
SERVICE = "safekeyservice"
RAW_RSA_PARAMETER = (256 << 16) | (3 << 12) | 0x100
SHA256_DIGEST_INFO = bytes.fromhex("3031300d060960864801650304020105000420")


def decode_parcel(text: str) -> tuple[int, str, int]:
    """Decode this native service's int, String16, int reply, without AIDL exceptions."""
    if len(text) > 131072 or "Result: Parcel(" not in text:
        raise ValueError("Unrecognized or oversized service-call response")
    words = []
    for line in text.split("Result: Parcel(", 1)[1].splitlines():
        line = line.split("'", 1)[0]
        line = re.sub(r"^\s*0x[0-9a-fA-F]+:\s*", "", line)
        words.extend(re.findall(r"\b[0-9a-fA-F]{8}\b", line))
    blob = b"".join(int(word, 16).to_bytes(4, "little") for word in words)
    if len(blob) < 4:
        raise ValueError("Missing service result")
    status = struct.unpack_from("<i", blob)[0]
    if status != 0:
        raise ValueError(f"Service returned {status}; no retry")
    if len(blob) < 8:
        raise ValueError("Missing String16 length")
    count = struct.unpack_from("<i", blob, 4)[0]
    if not 0 < count <= 16384:
        raise ValueError("Invalid String16 length")
    end = 8 + count * 2
    tail = (end + 2 + 3) & ~3
    if len(blob) < tail + 4 or blob[end:end + 2] != b"\0\0":
        raise ValueError("Truncated or unterminated native reply")
    value = blob[8:end].decode("utf-16-le", errors="strict")
    size = struct.unpack_from("<i", blob, tail)[0]
    return status, value, size


def encode_challenge(challenge: bytes) -> bytes:
    digest_info = SHA256_DIGEST_INFO + hashlib.sha256(challenge).digest()
    return b"\0\1" + b"\xff" * (256 - len(digest_info) - 3) + b"\0" + digest_info


def adb_read(serial: str, arguments: list[str], timeout: int = 20) -> str:
    try:
        run = subprocess.run(
            ["adb", "-s", serial, *arguments],
            capture_output=True, text=True, timeout=timeout, check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise RuntimeError("Timed out; device-side outcome is unknown. Stop without retry.") from error
    if run.returncode != 0:
        # Do not include command data, certificate or signature in error output.
        raise RuntimeError(f"ADB command failed with status {run.returncode}; no retry")
    return run.stdout


def preview() -> dict:
    return {
        "mode": "preview_only",
        "calls_when_explicitly_executed": [
            {"transaction": 14, "operation": "getDataFromChip", "function": 10102, "certificate_type": 7},
            {"transaction": 6, "operation": "asymmetricSign", "function": 10106,
             "parameter": hex(RAW_RSA_PARAMETER), "input_bytes": 256},
        ],
        "verification": "Local random labelled challenge; RSA-2048 PKCS1v1.5 SHA256, public certificate",
        "constraints": "No cloud request, install, key export, direct PIN/key update request, or retry",
        "stock_side_effects": "Chip wake; on session/verification failure, initialization or factory PIN recovery may run",
        "limitations": "Daemon executable hash is unverified; only the native Binder library is pinned",
        "authorization": "Execution exceeds the owner's original read-only scope",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="127.0.0.1:5555", help="Existing authorized ADB transport; never connects/restarts it")
    parser.add_argument("--execute", action="store_true", help="Perform the two stock crypto calls, including their possible internal side effects")
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps(preview(), indent=2))
        return

    report = {"mode": "execute", "phase": "transport_and_library_check", "cloud_contacted": False}
    try:
        if adb_read(args.serial, ["get-state"]).strip() != "device":
            raise RuntimeError("Existing ADB transport is not ready")
        checksum = adb_read(args.serial, ["shell", "sha256sum", "/system/lib64/libsafekeyservice.so"])
        if checksum.split()[0] != LIBRARY_HASH:
            raise RuntimeError("Native Binder library does not match the reviewed build")

        report["phase"] = "certificate_request"
        reply = adb_read(args.serial, ["shell", "service", "call", SERVICE, "14", "i32", "10102", "i32", "7"])
        _, certificate_text, certificate_length = decode_parcel(reply)
        certificate = x509.load_der_x509_certificate(base64.b64decode("".join(certificate_text.split()), validate=True))
        public_key = certificate.public_key()
        if not isinstance(public_key, rsa.RSAPublicKey) or public_key.key_size != 2048:
            raise ValueError("Certificate is not the expected RSA-2048 type; signature not requested")
        report["certificate_sha256"] = certificate.fingerprint(hashes.SHA256()).hex()
        report["certificate_reported_length"] = certificate_length

        challenge = b"denza-owner-sidecar-local-signing-check-v1\0" + secrets.token_bytes(32)
        block = encode_challenge(challenge)
        report["challenge_sha256"] = hashlib.sha256(challenge).hexdigest()
        report["phase"] = "single_signature_request"
        reply = adb_read(args.serial, ["shell", "service", "call", SERVICE, "6",
            "i32", "10106", "i32", str(RAW_RSA_PARAMETER), "s16", block.hex(), "i32", "256"])
        _, signature_text, signature_length = decode_parcel(reply)
        if not re.fullmatch(r"[0-9a-fA-F]{512}", signature_text):
            raise ValueError("Signature is not exactly 256 hex-encoded bytes")
        report["phase"] = "local_verification"
        public_key.verify(bytes.fromhex(signature_text), challenge, padding.PKCS1v15(), hashes.SHA256())
        report.update({"phase": "complete", "signature_verified": True,
                       "signature_reported_length": signature_length,
                       "tls_login_verified": False, "official_phone_feed_verified": False})
    except Exception as error:
        report.update({"failed": True, "error_type": type(error).__name__,
                       "instruction": "Stop; do not retry. Raw response and identity material were not saved."})
        print(json.dumps(report, indent=2))
        raise SystemExit(1) from None
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
