#!/usr/bin/env python3
"""Read the two XCD containers in the investigated FSE OTA, entirely offline.

Follows libbydupgrade.so byd_decrypt_md5 (VA 0xb5794): AES-128-ECB,
first 16 literal bytes of the sidecar text file, PKCS7 padding. Extracted
AppBlock bytes are payloads, NOT claimed to be decrypted MCU machine code.
No keys or full payloads are printed. No flashing/vehicle/network operations.
"""

import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.padding import PKCS7


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def extract(archive, output):
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Output directory must be empty; preserve earlier evidence")
    manifest = []
    with ZipFile(archive) as ota:
        for name in ("mcu_42_2_3.xcd", "dspres_e_0.xcd"):
            ciphertext = ota.read(name)
            sidecar = ota.read(name + ".txt")
            if len(sidecar) < 16 or len(ciphertext) % 16:
                raise ValueError("Invalid container/key-source length: " + name)
            cipher = Cipher(algorithms.AES(sidecar[:16]), modes.ECB())
            decryptor = cipher.decryptor()
            padded = decryptor.update(ciphertext) + decryptor.finalize()
            unpadder = PKCS7(128).unpadder()
            xml = unpadder.update(padded) + unpadder.finalize()
            root = ET.fromstring(xml)
            if root.tag != "XCD" or root.find("Configure") is None:
                raise ValueError("Decrypted result is not XCD: " + name)
            # Re-encryption checks the exact source container, not only XML plausibility.
            encryptor = cipher.encryptor()
            if encryptor.update(padded) + encryptor.finalize() != ciphertext:
                raise ValueError("Round-trip mismatch: " + name)
            config = {node.tag: (node.text or "").strip()
                      for node in root.find("Configure")
                      if node.tag not in ("AESKey", "KeyK")}
            xml_name = name + ".xml"
            (output / xml_name).write_bytes(xml)
            record = {"source": name, "source_sha256": sha256(ciphertext),
                      "sidecar_sha256": sha256(sidecar), "xml": xml_name,
                      "xml_sha256": sha256(xml), "xml_bytes": len(xml),
                      "pkcs7_and_round_trip_verified": True,
                      "config": config, "blocks": []}
            blocks = root.findall("Data/AppBlock")
            if not blocks:
                raise ValueError("No AppBlock found: " + name)
            for index, block in enumerate(blocks):
                payload = bytes.fromhex(block.text or "")
                if len(payload) != int(block.attrib["blockSize"]):
                    raise ValueError("AppBlock size mismatch: " + name)
                payload_name = f"{name}.block{index}.payload"
                (output / payload_name).write_bytes(payload)
                record["blocks"].append({"path": payload_name,
                    "attributes": block.attrib, "bytes": len(payload),
                    "sha256": sha256(payload),
                    "status": "container payload; instruction format not established"})
            manifest.append(record)
    (output / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    for record in manifest:
        print(json.dumps({k: record[k] for k in
              ("source", "xml_sha256", "xml_bytes", "pkcs7_and_round_trip_verified")},
              ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    extract(args.archive, args.output)
