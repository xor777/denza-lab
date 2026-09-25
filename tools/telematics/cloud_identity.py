"""Explicit application-protocol identity; never writes modem properties.

Only the owner's original SIM pair belongs here. This does not replace the
vehicle certificate, private key, VIN, or cloud provisioning parameters.
"""
from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import stat


@dataclass(frozen=True, repr=False)
class CloudIdentity:
    iccid: bytes
    imsi: bytes

    def __post_init__(self):
        if (not isinstance(self.iccid, bytes) or not isinstance(self.imsi, bytes)
                or not re.fullmatch(rb"[0-9]{20}", self.iccid)
                or not re.fullmatch(rb"[0-9]{15}", self.imsi)):
            raise ValueError("Original SIM pair must contain 20 and 15 ASCII digits")

    def __repr__(self):
        return "CloudIdentity(<redacted>)"


def load_identity(path: Path) -> CloudIdentity:
    """Read a small owner-only regular JSON file; no values in error messages."""
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
    try:
        info = os.fstat(fd)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or info.st_mode & 0o077 or info.st_size > 1024):
            raise ValueError("Identity input must be an owner-only regular file, at most 1024 bytes")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            raw = stream.read(1025)
        if len(raw) > 1024:
            raise ValueError("Identity input exceeds size limit")
    finally:
        os.close(fd)

    def unique_pairs(pairs):
        values = {}
        for key, value in pairs:
            if key in values:
                raise ValueError("Duplicate identity field")
            values[key] = value
        return values

    try:
        value = json.loads(raw, object_pairs_hook=unique_pairs)
        if not isinstance(value, dict) or set(value) != {"iccid", "imsi"}:
            raise ValueError("Identity fields")
        return CloudIdentity(value["iccid"].encode("ascii"), value["imsi"].encode("ascii"))
    except (ValueError, AttributeError, TypeError):
        raise ValueError("Invalid original SIM identity file") from None
