#!/usr/bin/env python3
"""Prepare the pinned official RU model as an ignored build input, never on the car."""
import argparse
import hashlib
from pathlib import Path
import shutil
import urllib.request

URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
SHA256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already downloaded official ZIP")
    args = parser.parse_args()
    target = Path(__file__).resolve().parent / "build/voice-input/voice-model.zip"
    if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == SHA256:
        print(target)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".download")
    try:
        if args.archive:
            shutil.copyfile(args.archive, temporary)
        else:
            with urllib.request.urlopen(URL, timeout=60) as source, temporary.open("wb") as output:
                shutil.copyfileobj(source, output)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != SHA256:
            raise ValueError("Unexpected Vosk model SHA-256; build input was not replaced")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)
    print(target)


if __name__ == "__main__":
    main()
