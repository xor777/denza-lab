#!/usr/bin/env python3
"""Compare retained GHY CA assets and prior certificate metadata, entirely offline.

Run with .venv-firmware/bin/python. Does not read a device, contact a server,
change a trust store or establish what certificate the GHY endpoint uses now.
"""
import base64
import hashlib
import json
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "captures/firmware/pulled-from-car-20261018/jadx/OTAUpdate2/resources/assets"
PRIOR = ROOT / "captures/telematics-20260923/tls-wifi"
REPORT = ROOT / "captures/ota-ghy-20261021/offline-trust-comparison.json"


def verify_signature(cert, issuer):
    issuer.public_key().verify(cert.signature, cert.tbs_certificate_bytes,
                              padding.PKCS1v15(), cert.signature_hash_algorithm)


def main():
    observed = json.loads(REPORT.read_text())
    old = {v["selector"]: v["sha256"] for v in
           json.loads((PRIOR / "live-attempt-3.json").read_text())["public_certificates"]}
    certs = []
    for index, (name, selector) in enumerate((("BYD_Root_CA_RSA.cer", 5),
                                             ("BYD_Identity_SubCA_RSA.cer", 6))):
        raw = (ASSETS / name).read_bytes()
        cert = x509.load_der_x509_certificate(base64.b64decode(b"".join(raw.split()), validate=True))
        certs.append(cert)
        item = observed["assets"][index]
        assert hashlib.sha256(raw).hexdigest() == item["fileSha256"]
        assert cert.fingerprint(hashes.SHA256()).hex() == item["derSha256"] == old[selector]
        assert item["selector"] == selector and item["matchesRetainedChipSelector"]
    root, issuer = certs
    verify_signature(root, root)
    verify_signature(issuer, root)
    server = json.loads((PRIOR / "server-public-chain.json").read_text())["server_certificates"][0]
    leaf = x509.load_der_x509_certificate(base64.b64decode(server["der_base64"]))
    verify_signature(leaf, issuer)
    assert not observed["deviceContacted"] and not observed["cloudContacted"]
    assert not observed["ghyEndpointCertificateKnown"]
    print(json.dumps({"passed": True, "matchingCaCertificates": 2,
                      "verifiedCertificateSignatures": 3,
                      "ghyEndpointCertificateKnown": False,
                      "deviceOrNetworkAccess": False}))


if __name__ == "__main__":
    main()
