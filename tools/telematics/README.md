# Telematics probes

Research tools, outside product APKs. Start with
[the findings](../../docs/telematics-findings.md) for the exact authorization,
firmware, live results and unresolved boundaries. On 2026-09-23 the bounded
helper delivered real **74% SOC** to the official Denza app through vehicle Wi-Fi.
It is not a persistent service.

## Components

| File | Purpose |
| --- | --- |
| `local_identity_probe.py` | Explicitly approved stock certificate/signature test; defaults to preview |
| `tls_identity_client.c` | Isolated OpenSSL 3 TLS adapter; remote factory signing, strict peer/hostname verification, bounded application frames |
| `tls_identity_probe.py` | Shell-UID vehicle TCP transport, stock signing wrapper and host-only TLS scenarios; defaults to preview |
| `registration_probe.py` | One 211 registration, 200 discovery or 220 login; defaults to preview |
| `CloudCanSnapshotProbe.java` | Passive, bounded stock YUN callback capture; unregisters at exit |
| `status_upload_probe.py` | Fresh captured status plus independent SOC/charging getters; accepted login then one 512; defaults to preview |
| `stock_wifi_gate_test.py` | Live-tested stock-client activation: temporary public profile and paired native network events; preview by default, `--hold` until explicit `--stop` |
| `wifi_notification_test.py` | Earlier separate Wi-Fi notification experiment; see its own CLI and findings |

The manual TLS/status helpers do not execute cloud-delivered vehicle commands.
The stock-client test activates the stock client's own inbound handlers; the
owner explicitly accepted this full-session scope for the 2026-09-23 held run.
That run reached stock TCP=1, registration status=2 and token flag=1 over Wi-Fi.
Its live state and stop procedure are in
`captures/telematics-20260923/stock-client-wifi/live-hold-1/README.md`.
The observer and guard later exited after ADB read timeouts; the guard did not
restore the profile. At the owner's subsequent request the stock car session
was left active. At 15:15 Moscow, no host test processes remained and a fresh
read still showed stock TCP=1. Host rollback is best-effort and depends on ADB;
do not assume either host process exit or `--stop` means restoration succeeded.
The manual helper's TLS socket is created
on the car with `adb shell -T toybox nc`; `adb exec-out` is output-only and cannot
provide this duplex transport. The prototype runs TLS/protocol code on the Mac.
Only the stock cryptographic service signs; no vehicle private key is exported.
Cryptographic calls can wake/reinitialize the chip and take its recovery path.
Cloud registration, login and status upload are stateful tests, not read-only
operations. A normal application UID has not been validated.

## Native adapter build and host-only validation

From the repository root, with Homebrew OpenSSL 3 installed:

```sh
cc -std=c11 -Wall -Wextra -Werror -O2 \
  -I/opt/homebrew/opt/openssl@3/include tools/telematics/tls_identity_client.c \
  -L/opt/homebrew/opt/openssl@3/lib \
  -Wl,-rpath,/opt/homebrew/opt/openssl@3/lib -lssl -lcrypto \
  -o captures/telematics-20260923/tls-wifi/tls_registration_client
PYTHONDONTWRITEBYTECODE=1 python3 tools/telematics/tls_identity_probe.py --self-test
```

The eleven local cases use an independent loopback TLS server and synthetic
identities; no ADB or official cloud is contacted. The legacy RSA_METHOD API is
isolated here and requires redesign before production integration. TLS verifies
the server root and hostname; supplying a missing intermediate must not disable
verification or enable partial-chain trust.

Offline packet/body comparison is in
[`verify_registration_native.py`](../../research/telematics-firmware/verify_registration_native.py).
It requires Unicorn 2.1.4, pyelftools and cryptography, and the existing extracted
cloudmanager ELF. Seven synthetic fixtures compare native bytes; no firmware
function outside the bounded serializer/body/crypto allowlist runs.

## Passive capture build

The retained 7.5 KB DEX JAR was built with JDK 17, Android SDK 35 and d8 35.0.0.
Reproduction uses a task-specific temporary directory:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
denza_probe_build=$(mktemp -d /tmp/denza-cloud-probe.XXXXXX)
mkdir -p "$denza_probe_build/classes" "$denza_probe_build/dex"
"$JAVA_HOME/bin/javac" -source 8 -target 8 \
  -cp "$ANDROID_HOME/platforms/android-35/android.jar" \
  -d "$denza_probe_build/classes" tools/telematics/CloudCanSnapshotProbe.java
"$ANDROID_HOME/build-tools/35.0.0/d8" --min-api 33 \
  --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
  --output "$denza_probe_build/dex" \
  "$denza_probe_build"/classes/dev/denza/tools/*.class
"$JAVA_HOME/bin/jar" --create \
  --file captures/telematics-20260923/status-passive/cloud-can-snapshot.jar \
  -C "$denza_probe_build/dex" classes.dex
rm -r "$denza_probe_build"
```

The completed live run used an explicitly selected car serial, not the connected
phone: `adb -s 127.0.0.1:5555`. Its command was
`CLASSPATH=/data/local/tmp/denza-telematics-passive-20260923.jar app_process /system/bin dev.denza.tools.CloudCanSnapshotProbe 30 512`.
Require `UNREGISTERED` and `DONE ... dropped=0 callback_errors=0 queued=0` before
using a capture. The temporary on-car JAR has been removed. No APK was installed.

## Diagnostic upload boundary

A default preview does not read a capture or contact a device/cloud:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 tools/telematics/status_upload_probe.py /path/to/fresh-capture.log
```

Execution requires the separate `--execute` option and currently pinned vehicle
identity/libraries. It rejects stale entries, missing data and disagreement
with an independent SOC getter. The single exception, `--allow-missing-0417`,
represents the owner's explicit diagnostic permission to use stock initial zero
for unmeasured body byte 102. Do not enable it as a production default.
The first successful report used measured CAN charging fallback; the second and
current implementation use stock FID 0x34400018 (value 1 on this car).

Saved captures are historical evidence and will fail the freshness gate. Each
upload has one factory signature, one accepted login and one 165-byte report,
then closes five seconds later. It has no automatic retry, token request, remote
command executor or continuous heartbeat. `telemetry_sent` means the TLS write
succeeded; phone acceptance requires separate observation.

Artifacts and SHA-256 manifests are under ignored
`captures/telematics-20260923/{tls-wifi,registration-inputs,status-passive,phone-validation}`.
They omit VIN, SIM identity, session AES key/UUID, full client certificate and
private key. Raw status bodies/callback logs and phone images still contain
owner vehicle data; keep them local. See `source-artifacts.json` at the capture
root for source and binary identities.
