#!/usr/bin/env python3
"""Publish an APK to the adbgw.ru download page (CAG-014).

    platform/downloads/publish.py denza-apps            # the latest GitHub release
    platform/downloads/publish.py denza-apps APK
    platform/downloads/publish.py yandex-hud APK
    platform/downloads/publish.py hud-speed APK

The APK's manifest must name the app's package. The file is uploaded under a
hidden name; releases.py, run on the host, checks its hash, moves it into place
and rewrites releases.json. The page then offers it as the app's latest version.
"""

from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import releases  # noqa: E402

HOST = "adbgw.ru"
# downloads_root/apk in ops/ansible/roles/downloads; owned by the admin account.
REMOTE_ROOT = "/var/www/adbgw/apk"
REPOSITORY = "xor777/denza-lab"
TAG_PREFIX = "denza-apps-v"
SDK_FALLBACK = "/opt/homebrew/share/android-commandlinetools"
_BADGING = re.compile(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'")


class PublishError(Exception):
    pass


def run(argv: list, **kwargs) -> str:
    return subprocess.run(argv, check=True, text=True, stdout=subprocess.PIPE, **kwargs).stdout


def aapt2() -> Path:
    sdk = Path(os.environ.get("ANDROID_HOME") or SDK_FALLBACK)
    tools = sorted((sdk / "build-tools").glob("*/aapt2"),
                   key=lambda path: [int(part) for part in re.findall(r"\d+", path.parent.name)])
    if not tools:
        raise PublishError(f"no aapt2 under {sdk}/build-tools; set ANDROID_HOME")
    return tools[-1]


def parse_badging(text: str) -> tuple:
    """(package, version_code, version_name) from `aapt2 dump badging`."""
    match = _BADGING.match(text)
    if not match:
        raise PublishError("aapt2 badging has no package line")
    return match.group(1), int(match.group(2)), match.group(3)


def file_name(app: str, version_name: str) -> str:
    if not releases.SAFE_VERSION_NAME.match(version_name):
        raise PublishError(f"versionName {version_name!r} is not file-name safe")
    return f"{app}-{version_name}.apk"


def entry_for(app: str, apk: Path, date: str) -> dict:
    package, version_code, version_name = parse_badging(run([str(aapt2()), "dump", "badging", str(apk)]))
    entry = {
        "version_name": version_name,
        "version_code": version_code,
        "package": package,
        "date": date,
        "bytes": apk.stat().st_size,
        "sha256": releases.sha256(apk),
        "file": file_name(app, version_name),
    }
    try:
        releases.validate(app, entry)
    except releases.ReleaseError as error:
        raise PublishError(str(error)) from error
    return entry


def latest_github_release(directory: Path) -> tuple:
    """Download the latest stable Denza Apps release; (apk, publication date)."""
    listed = json.loads(run(["gh", "release", "list", "--repo", REPOSITORY, "--exclude-pre-releases",
                             "--exclude-drafts", "--limit", "50", "--json", "tagName,publishedAt"]))
    tags = [item for item in listed if item["tagName"].startswith(TAG_PREFIX)]
    if not tags:
        raise PublishError(f"no {TAG_PREFIX}* release in {REPOSITORY}")
    latest = max(tags, key=lambda item: item["publishedAt"])
    run(["gh", "release", "download", latest["tagName"], "--repo", REPOSITORY,
         "--pattern", "*.apk", "--dir", str(directory)])
    apks = sorted(directory.glob("*.apk"))
    if len(apks) != 1:
        raise PublishError(f"{latest['tagName']} has {len(apks)} APK assets, expected one")
    return apks[0], latest["publishedAt"][:10]


def local_date(apk: Path) -> str:
    return datetime.date.fromtimestamp(apk.stat().st_mtime).isoformat()


def publish(app: str, apk: Path, date: str, host: str, root: str, dry_run: bool) -> dict:
    entry = entry_for(app, apk, date)
    print(json.dumps(entry, indent=2))
    if dry_run:
        return entry
    staged = f".{entry['file']}.part"
    subprocess.run(["scp", str(apk), f"{host}:{root}/{staged}"], check=True)
    # ssh hands the remote shell one string, so every argument is quoted.
    remote = ["python3", "-", root, app, staged, json.dumps(entry, separators=(",", ":"))]
    subprocess.run(["ssh", host, " ".join(shlex.quote(part) for part in remote)],
                   input=(HERE / "releases.py").read_text(), text=True, check=True)
    print(f"https://{host}/apk/{entry['file']}")
    return entry


def main(argv: list) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("app", choices=sorted(releases.APPS))
    parser.add_argument("apk", nargs="?", type=Path, help="the APK; Denza Apps defaults to the latest GitHub release")
    parser.add_argument("--host", default=HOST)
    parser.add_argument("--root", default=REMOTE_ROOT)
    parser.add_argument("--dry-run", action="store_true", help="print the release entry, upload nothing")
    args = parser.parse_args(argv)
    try:
        if args.apk:
            if not args.apk.is_file():
                raise PublishError(f"{args.apk} is not a file")
            publish(args.app, args.apk, local_date(args.apk), args.host, args.root, args.dry_run)
        elif args.app == "denza-apps":
            with tempfile.TemporaryDirectory() as directory:
                apk, date = latest_github_release(Path(directory))
                publish(args.app, apk, date, args.host, args.root, args.dry_run)
        else:
            raise PublishError(f"{args.app} is built locally; give the APK path")
    except (PublishError, subprocess.CalledProcessError) as error:
        print(f"publish: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
