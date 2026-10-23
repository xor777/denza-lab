#!/usr/bin/env python3
"""Build/test only the isolated passive ADAS status observer; no device commands."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("out", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    source = root / "experiments/adas-status-probe"
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=False)
    sdk = Path(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"))
    java = Path(os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"))
    build = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-35/android.jar"
    env = dict(os.environ, JAVA_HOME=str(java))
    sources = sorted(source.rglob("*.java"))
    inputs = sources + sorted((source / "src/test/fixtures").glob("*.txt")) + [source / "src/main/AndroidManifest.xml", Path(__file__).resolve()]
    hashes = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}

    def run(command, timeout=60):
        result = subprocess.run([str(value) for value in command], check=True, capture_output=True, env=env, cwd=root, timeout=timeout)
        return result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace")

    classes, dex = out / "classes", out / "dex"
    classes.mkdir(); dex.mkdir()
    run([java/"bin/javac", "--release", "8", "-cp", android, "-d", classes, *sources])
    tests = run([java/"bin/java", "-cp", classes, "dev.denza.adasstatus.probe.StatusProtocolTest"])
    (out / "tests.txt").write_text(tests)
    # Test class is excluded from the packaged probe.
    (classes / "dev/denza/adasstatus/probe/StatusProtocolTest.class").unlink()
    run([java/"bin/jar", "cf", out/"classes.jar", "-C", classes, "."])
    run([build/"d8", "--min-api", "26", "--lib", android, "--output", dex, out/"classes.jar"])
    run([build/"aapt2", "link", "-I", android, "--manifest", source/"src/main/AndroidManifest.xml",
         "--version-code", "1", "--version-name", "0.1-local", "-o", out/"unsigned.apk"])
    with zipfile.ZipFile(out/"unsigned.apk", "a") as archive:
        archive.write(dex/"classes.dex", "classes.dex")
    run([build/"zipalign", "-f", "4", out/"unsigned.apk", out/"aligned.apk"])
    signing = root / "captures/adas-status-signing"
    signing.mkdir(parents=True, exist_ok=True)
    key = signing / "probe.p12"
    if not key.exists():
        run([java/"bin/keytool", "-genkeypair", "-keystore", key, "-storetype", "PKCS12", "-alias", "local-probe",
             "-storepass", "denza-local-probe", "-keypass", "denza-local-probe", "-dname", "CN=Denza Local ADAS Status Probe",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-noprompt"])
    apk = out / "adas-status-probe.apk"
    run([build/"apksigner", "sign", "--ks", key, "--ks-pass", "pass:denza-local-probe", "--out", apk, out/"aligned.apk"])
    verification = run([build/"apksigner", "verify", "--verbose", apk])
    after = {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs}
    if hashes != after:
        raise RuntimeError("Source changed during build; discard this APK")
    manifest = {"schema": "denza-adas-status-build-v1", "package": "dev.denza.adasstatus.probe",
                "apk": str(apk), "apkSHA256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "tests": tests.strip(), "signatureVerification": verification.strip(), "sourceSHA256": hashes,
                "deviceActions": 0, "passiveClientOnly": True, "fixtureGate": "Build.HARDWARE ranchu/goldfish",
                "serviceSubscriptions": ["13/0x8002", "13/0x8003", "13/0x8005", "44/0x8002", "44/0x8001"]}
    (out/"manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
