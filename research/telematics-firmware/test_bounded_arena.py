#!/usr/bin/env python3
"""Compile and run the worker's bounded arena on the host; no firmware or APK."""
import argparse
import subprocess
import tempfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cc", default="clang")
    args = parser.parse_args()
    source = Path(__file__).with_suffix(".c")
    with tempfile.TemporaryDirectory(prefix="denza-arena-") as temp:
        binary = Path(temp) / "arena-test"
        subprocess.run([args.cc, "-std=c11", "-O1", "-g", "-Wall", "-Wextra",
                        "-Werror", "-fsanitize=address,undefined", str(source),
                        "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    print("bounded arena: ownership, alignment, invalid/double free, overflow, "
          "exhaustion, fragmentation, coalescing, split, corrupt metadata: PASS")


if __name__ == "__main__":
    main()
