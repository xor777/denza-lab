#!/usr/bin/env python3
"""Convert an explicitly chosen EXISTING PEM ADB identity to Android's private-store format.
Does not generate keys, contact devices, authorize ADB, install anything or modify the input.
"""
import argparse
import os
from pathlib import Path
import struct
import subprocess


def convert(source, destination):
    private = subprocess.run(["openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(source),
                              "-outform", "DER"], check=True, capture_output=True).stdout
    public = subprocess.run(["openssl", "pkey", "-in", str(source), "-pubout", "-outform", "DER"],
                             check=True, capture_output=True).stdout
    if not 0 < len(private) <= 16384 or not 0 < len(public) <= 16384:
        raise ValueError("Unsupported identity")
    data = struct.pack(">II", 0x44414b31, len(private)) + private + struct.pack(">I", len(public)) + public
    # Never overwrite an existing file; do not print key material.
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("existing_pem", type=Path)
    parser.add_argument("private_output", type=Path)
    arguments = parser.parse_args()
    convert(arguments.existing_pem, arguments.private_output)
    print("Existing identity converted; private output must not be committed or shared.")
