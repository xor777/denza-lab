#!/usr/bin/env python3
"""The release list behind the adbgw.ru download page (CAG-014).

publish.py uploads an APK under a hidden staging name and then runs this file on
the relay host with ``python3 -``. It checks the staged bytes against the entry
the publisher computed, moves the APK into place, rewrites releases.json
atomically and removes the app's previous APK. Standard library only: the same
file runs on the host and in the unit tests.
"""

from __future__ import annotations

import contextlib
import fcntl
import hashlib
import json
import os
import re
import sys
from pathlib import Path

APPS = {
    "denza-apps": "dev.denza.apps",
    "yandex-hud": "ru.yandex.yandexnavi",
    "hud-speed": "air.StrelkaHUDFREE",
}

RELEASES = "releases.json"
LOCK = ".lock"
SCHEMA = 1
FIELDS = {"version_name", "version_code", "package", "date", "bytes", "sha256", "file"}
SAFE_VERSION_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_STAGED = re.compile(r"^\.[A-Za-z0-9._-]+\.part$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_SHA = re.compile(r"^[0-9a-f]{64}$")


class ReleaseError(Exception):
    pass


def empty() -> dict:
    return {"version": SCHEMA, "apps": {}}


def validate(app: str, entry: dict) -> None:
    if app not in APPS:
        raise ReleaseError(f"unknown app {app!r}")
    if set(entry) != FIELDS:
        raise ReleaseError(f"entry fields {sorted(entry)} != {sorted(FIELDS)}")
    if entry["package"] != APPS[app]:
        raise ReleaseError(f"{app} must be {APPS[app]}, not {entry['package']}")
    if not isinstance(entry["version_name"], str) or not SAFE_VERSION_NAME.match(entry["version_name"]):
        raise ReleaseError(f"versionName {entry['version_name']!r} is not file-name safe")
    if entry["file"] != f"{app}-{entry['version_name']}.apk":
        raise ReleaseError(f"bad file name {entry['file']!r}")
    if not _DATE.match(entry["date"]) or not _SHA.match(entry["sha256"]):
        raise ReleaseError("bad date or sha256")
    if not isinstance(entry["bytes"], int) or entry["bytes"] <= 0:
        raise ReleaseError("bad size")
    if not isinstance(entry["version_code"], int):
        raise ReleaseError("bad versionCode")


def read(root: Path) -> dict:
    try:
        releases = json.loads((root / RELEASES).read_text())
    except FileNotFoundError:
        return empty()
    if releases.get("version") != SCHEMA or not isinstance(releases.get("apps"), dict):
        raise ReleaseError(f"unexpected {RELEASES} schema")
    return releases


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write(root: Path, releases: dict) -> None:
    temporary = root / f".{RELEASES}.tmp"
    with temporary.open("w") as stream:
        json.dump(releases, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o644)
    os.replace(temporary, root / RELEASES)


@contextlib.contextmanager
def locked(root: Path):
    with (root / LOCK).open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def install(root: Path, app: str, staged_name: str, entry: dict) -> dict:
    """Move a staged APK into place and record it as the app's latest release."""
    validate(app, entry)
    if not _STAGED.match(staged_name):
        raise ReleaseError(f"bad staging name {staged_name!r}")
    staged = root / staged_name
    with locked(root):
        if staged.stat().st_size != entry["bytes"] or sha256(staged) != entry["sha256"]:
            staged.unlink()
            raise ReleaseError("uploaded bytes differ from the publisher's APK")
        releases = read(root)
        previous = releases["apps"].get(app)
        staged.chmod(0o644)
        os.replace(staged, root / entry["file"])
        releases["apps"][app] = entry
        write(root, releases)
        if previous and previous["file"] != entry["file"]:
            (root / previous["file"]).unlink(missing_ok=True)
    return releases


def main(argv: list[str]) -> int:
    if len(argv) != 5:
        print("usage: releases.py ROOT APP STAGED_NAME ENTRY_JSON", file=sys.stderr)
        return 2
    try:
        releases = install(Path(argv[1]), argv[2], argv[3], json.loads(argv[4]))
    except (ReleaseError, OSError, ValueError) as error:
        print(f"releases: {error}", file=sys.stderr)
        return 1
    json.dump(releases["apps"][argv[2]], sys.stdout, sort_keys=True)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
