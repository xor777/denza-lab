"""Offline codec for current Z9GT cloudmanager public registration (211).

No network/device access. Inputs are supplied by the caller, never logged here.
Source: current cloudmanager 6e00c, 6e3b0, 6e858, 69774 and 3593c.
"""
import binascii
import hashlib
import re
import struct
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes


def build_registration(vin, imsi, iccid, key, uuid, timestamp):
    _validate(vin, key, uuid)
    if not re.fullmatch(rb"[0-9]{15}", imsi) or not re.fullmatch(rb"[0-9]{20}", iccid):
        raise ValueError("SIM identity shape")
    return _frame(vin, key, uuid, timestamp, 211, 255, imsi + iccid)


def _validate(vin, key, uuid):
    if not re.fullmatch(rb"[A-HJ-NPR-Z0-9]{17}", vin):
        raise ValueError("VIN shape")
    if len(key) != 16 or len(uuid) != 16 or key == uuid:
        raise ValueError("Cloud parameters shape")
    if any(v in (bytes(16), b"\xa7" * 16) for v in (key, uuid)):
        raise ValueError("Cloud parameter sentinel")


def build_discovery(vin, key, uuid, timestamp):
    _validate(vin, key, uuid)
    return _frame(vin, key, uuid, timestamp, 200, 254, b"\x01\x01")


def build_login(vin, imsi, serial, nonce, key, uuid, timestamp):
    _validate(vin, key, uuid)
    if not re.fullmatch(rb"[0-9]{15}", imsi) or len(nonce) != 16 or (serial and len(serial) != 12):
        raise ValueError("Login identity shape")
    # Stock constructor leaves the serial digest zero when debug.ro.serialno
    # is absent; prepare skips its digest, rather than hashing an empty string.
    serial_digest = hashlib.md5(serial).digest() if serial else bytes(16)
    body = nonce + serial_digest + hashlib.md5(imsi).digest()
    return _frame(vin, key, uuid, timestamp, 220, 254, body)


def build_status(vin, key, uuid, timestamp, body):
    _validate(vin, key, uuid)
    if len(body) != 104 or body[0] != 3:
        raise ValueError("Status body shape")
    return _frame(vin, key, uuid, timestamp, 512, 255, body, 1)


def _frame(vin, key, uuid, timestamp, command, flag, body, function_version=0):
    plain = b"\x8c\x09" + struct.pack(">HBB", command, flag, function_version) + vin + b"\x10\x03" + bytes(4)
    plain += struct.pack(">I", timestamp) + body
    plain += struct.pack(">H", binascii.crc_hqx(plain, 0xffff))
    pad = 16 - len(plain) % 16
    cipher = Cipher(algorithms.AES(key), modes.CBC(uuid)).encryptor()
    encrypted = cipher.update(plain + bytes([pad]) * pad) + cipher.finalize()
    return b"\xfe\xfe\x03" + struct.pack(">H", 16 + len(encrypted)) + uuid + encrypted


def decode_response(packet, vin, key, uuid, diagnostics=None):
    """Validate one protocol-3 frame; return metadata and the private body.

    cloudmanager 573ec inserts a local message index before calling 727bc;
    that index is not present on the wire. Body byte 0 is the 211 status.
    """
    diag = diagnostics if diagnostics is not None else {}
    diag["wire_length"] = len(packet)
    if not 53 <= len(packet) <= 1024 or packet[:3] != b"\xfe\xfe\x03":
        raise ValueError("Frame shape")
    if int.from_bytes(packet[3:5], "big") != len(packet) - 5:
        raise ValueError("Frame length")
    diag["uuid_matches"] = packet[5:21] == uuid
    if packet[5:21] != uuid or (len(packet) - 21) % 16:
        raise ValueError("Frame UUID or block length")
    cipher = Cipher(algorithms.AES(key), modes.CBC(uuid)).decryptor()
    plain = cipher.update(packet[21:]) + cipher.finalize()
    pad = plain[-1]
    diag["padding_length_in_range"] = 1 <= pad <= 16
    if not 1 <= pad <= 16 or plain[-pad:] != bytes([pad]) * pad:
        raise ValueError("Frame padding")
    plain = plain[:-pad]
    diag.update({"plain_length": len(plain), "expected_header": plain[:2] == b"\x8c\x09", "vin_matches": plain[6:23] == vin})
    # The sender's first two plaintext bytes are not a reply constant.
    # Stock 727bc validates padding, CRC and VIN, not equality to 8c09.
    if len(plain) < 35 or plain[6:23] != vin:
        raise ValueError("Frame length or VIN")
    if binascii.crc_hqx(plain[:-2], 0xffff) != int.from_bytes(plain[-2:], "big"):
        raise ValueError("Frame CRC")
    body = plain[33:-2]
    return {"command": int.from_bytes(plain[2:4], "big"), "reply_flag": plain[4],
            "function_version": plain[5], "body_length": len(body),
            "crc_valid": True, "vin_matches": True, "uuid_matches": True}, body
