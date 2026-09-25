# Telematics probes

Research probes, outside product APKs. The `runtime/` Java component is the
explicit exception used by the bounded awake alpha described below. Start with
[the findings](../../docs/telematics-findings.md) for the exact authorization,
firmware, live results and unresolved boundaries. On 2026-09-23 the bounded
helper delivered real **74% SOC** to the official Denza app through vehicle Wi-Fi.
It is not a persistent service.

Build 61 packages `runtime/` and the qualified original ARM64 instruction subset
as `awake-alpha-v1`. It requires an awake vehicle and Denza Apps foreground
service. Original firmware handles telemetry and commands; Java forwards
opaque frames and SDK calls. After a successful frame write it returns the
original command number as SENT, allowing the original completion handler to
run. Registration status0 uses the original 70-second timer; heartbeat uses
the same authenticated TLS session. Offline qualification is saved under
`captures/telematics-20260925/awake-alpha/`. Sleep, QuickBoot and full autonomous
operation remain unsupported, and ordinary-SIM internet requires separate live
acceptance. See [the runtime contract](../../docs/cloud-custom-runtime-contract.md).

The owner's 2026-09-24 design boundary excludes our own telemetry/command
schemas: future adaptation may forward opaque data/messages or call stock
codecs, in addition to supplying identity and transport. The manual encoder/
upload helpers below remain research evidence, not the product direction.
See [the adapter boundary](../../docs/telematics-findings.md#owners-adapter-boundary-clarified-2026-09-24).

## Components

| File | Purpose |
| --- | --- |
| `local_identity_probe.py` | Explicitly approved stock certificate/signature test; defaults to preview |
| `tls_identity_client.c` | Isolated OpenSSL 3 TLS adapter; remote factory signing, strict peer/hostname verification, bounded application frames |
| `tls_identity_probe.py` | Vehicle or host TCP transport, stock signing wrapper and loopback TLS scenarios; defaults to preview |
| `registration_probe.py` | One 211 registration, 200 discovery or 220 login; defaults to preview, car TCP unless `--transport host` |
| `native_registration_probe.py` | One 211-only exchange using the original firmware producer/decoder/handler; preview by default, explicit one-attempt report; optional `--test-imsi-suffix` adds one original-pair restoration after the variant; no login/telemetry/control |
| `test_native_registration_probe.py` | Offline native-body and restoration checks for the bounded IMSI experiment; no real signer/network |
| `imsi_login_compare.py` | Preview-first native211/200/220 A/B/A comparison; follows status0's70s delay, temporarily pauses the stock gate with a device-local guard and restores the original pair; changes only IMSI suffix by default, or only ICCID with `--test-field iccid` |
| `test_imsi_login_compare.py` | Pinned firmware continuation, native identity/login consistency and fail/restore checks; no car/cloud traffic |
| `native_session_probe.py` | Preview-first native 200/220 plus one real 511 request/reply; fresh opaque SDK callbacks, car Wi-Fi TLS, temporary stock cloud gate pause and device-local restoration guard; no actuator branch or product install |
| `OncarTls.java` | Android platform TLS with an opaque factory-key signing callback, root/hostname checks and one signature per connection; separate emulator-only synthetic TLS entry point |
| `OncarNativeSession.java` | Bounded on-car controller: supplied original SIM pair, native pipe worker, fresh opaque SDK callbacks, two real511 responses across a new worker/TLS session; stock gate restored afterward |
| `test_oncar_tls.py` | Emulator-only synthetic mTLS, omitted intermediate, wrong hostname/root and bad-signature checks; no factory calls or cloud traffic |
| `test_host_transport.py` | Loopback-only duplex, byte-cap, deadline and cleanup checks for the host transport |
| `cloud_identity.py` | Explicit original ICCID/IMSI input, isolated from modem properties; owner-only file and redacted representation |
| `test_cloud_identity.py` | Synthetic registration/login pair consistency, default behavior, input and preview checks |
| `cloud_path_snapshot.py` | Preview-first read-only capability/source snapshot; compares SIM values in memory and retains only lengths/equality |
| `test_cloud_path_snapshot.py` | Synthetic privacy, unknown-vs-empty, transport failure and offline-preview checks |
| `cloud_downlink_observer.py` | Preview-first bounded passive logcat; retains fixed labels and numeric metadata only |
| `cloud_control_capture.sh` | Bounded on-car passive climate observation, full-message log allowlist and read-only getter snapshots; survives SSH-bridge loss if its shell process remains alive; no setters |
| `test_cloud_downlink_observer.py` | Five controlled extraction/privacy/preview checks |
| `CloudCanSnapshotProbe.java` | Passive, bounded stock YUN callback capture; unregisters at exit |
| `CloudPowerReadProbe.java` | Bounded shell-UID ACC/MCU getters and exact power-listener registration; no setters or cloud calls; unregisters and exits within 20 seconds |
| `status_upload_probe.py` | Fresh captured status plus independent SOC/charging getters; accepted login then one 512; defaults to preview |
| `stock_wifi_gate_test.py` | Live-tested stock-client activation: temporary public profile and paired native network events; preview by default, `--hold` until explicit `--stop` |
| `wifi_notification_test.py` | Earlier separate Wi-Fi notification experiment; see its own CLI and findings |

The manual TLS/status helpers do not execute cloud-delivered vehicle commands.
The owner-authorized on-car controller test at18:14–18:17 UTC on2026-09-24
accepted native registration/discovery/login and sent two native511 responses
with independently verified81% SOC across new worker/TLS sessions. The Mac
was absent from the runtime data path. The explicit pair was copied from the
current original SIM for this experiment; replacement-SIM internet was not
tested. This is a shell-UID research helper with vehicle/certificate pins and
bounded lifetime, not an APK feature or a general-purpose background service.
No actuator command was attempted. The exact sources, builds, guard, logs and
cleanup proof are in `captures/telematics-20260924/oncar-native-session/`;
see the [on-car findings](../../docs/telematics-findings.md#on-car-native-adapter-with-an-explicitly-supplied-sim-pair-2026-09-24).

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
By default the manual helper's TCP socket is created
on the car with `adb shell -T toybox nc`; `adb exec-out` is output-only and cannot
provide this duplex transport. The prototype runs TLS/protocol code on the Mac.
`registration_probe.py --transport host` instead creates the TCP socket on the
Mac. It still reads the same pinned car's crypto inputs and uses its stock
signer over ADB. Without the separate `--identity-file` option it reads the
current modem's SIM pair. Both transports have byte/time limits and no automatic retry. Record
the host route to the actual peer IP separately: `car_reference_route` is only
the car's reference route, not the host socket's route.
Only the stock cryptographic service signs; no vehicle private key is exported.
Cryptographic calls can wake/reinitialize the chip and take its recovery path.
Cloud registration, login and status upload are stateful tests, not read-only
operations. A normal application UID has not been validated.

The owner-authorized identity/network separation experiment is retained under
`captures/telematics-20260924/identity-network-separation/`. Its scope is one
host-origin 211 registration, preserving the working stock connection, followed
by state checks. It excludes 220 login, telemetry and modem/profile changes.
The first preflight stopped because the car's ADB device and local tunnel were
absent; no crypto or registration request ran at that point. After the owner
restored the connection, the single registration at 14:55 UTC on 2026-09-24
succeeded: verified mTLS, one signature, 101 application bytes, validated 211
body status=1, actual host peer 139.159.228.68 via utun4. Stock TCP=1 and vehicle
state were unchanged in the before/after reads. This establishes acceptance via
that alternative route, not SIM-swap persistence or automatic transfer of the
helper's registration into stock cloudmanager.

The separately authorized IMSI-suffix comparison at18:45 UTC on2026-09-24 is
retained under `captures/telematics-20260924/imsi-suffix-registration/`. Both the
changed-suffix request and original-pair restoration request returned native211
status0, not the earlier success1. Stock TCP remained1 and modem inputs were
unchanged, but server-side restoration was not confirmed. This is inconclusive
for the format-only hypothesis when considered alone.
See [the result and native zero-status branch](../../docs/telematics-findings.md#changed-imsi-suffix-short-live-comparison-was-inconclusive-2026-09-24).

The separately authorized complete A/B/A test at18:58–19:01 UTC resolved the
continuation: native0 waits70s, then sends200 and220. All three logins passed,
including a changed ten-digit IMSI suffix with original ICCID and46013 prefix.
Original-pair login and stock TCP1/step6 were restored; modem values and baseline
state were unchanged, with no helper left. This proves that one suffix variant,
not the particular46001 forum prefix or arbitrary pairs. Artifacts are under
`captures/telematics-20260924/imsi-complete-login/`; see
[the full result](../../docs/telematics-findings.md#changed-imsi-suffix-complete-login-accepted-2026-09-24).
The subsequent `--test-field iccid` run at19:03 UTC independently accepted one
89860+15-digit ICCID with the entire original IMSI; both controls and stock
restoration passed. Seven offline tests passed. Exact sources/results are in
`captures/telematics-20260924/iccid-complete-login/`; see
[the ICCID result](../../docs/telematics-findings.md#changed-iccid-complete-login-accepted-2026-09-24).
Neither run changed both fields together or tested the46001 prefix. Both were
bounded logins without telemetry/actuators; mobile transport and a normal APK
remain unproved.

## Read-only follow-up snapshot

`CloudCanSnapshotProbe` also accepts `--opaque` as its third argument, after
duration and queue capacity (for example `10 512 --opaque`). It preserves each
allowlisted SDK callback as a complete byte buffer; the default historical
output is unchanged. Opaque output is private replay evidence, not a cloud
report, and should be saved only under ignored captures with mode 0600.
A ten-second run on 2026-09-24 retained 979 buffers, unregistered successfully
and left the before/after stock state unchanged. The original firmware routines
subsequently consumed those buffers in an isolated on-car execution test; see
[the native replay result](../../docs/telematics-findings.md#opaque-buffer-execution-verified-on-the-car-2026-09-24).

`cloud_path_snapshot.py` prints a plan by default. Once the owner has restored
the existing transport, `--execute --serial 127.0.0.1:5555` reads identity-source
shapes, relevant service presence, cloud-state properties, caller/SELinux and
fixed path metadata. It performs no service transactions, property writes,
crypto or cloud request. SIM values and hashes are not included; the native
identity cache remains explicitly unknown. A denied read is not an empty SIM.
The collector has a 60-second total deadline, eight seconds per command, and
no retries. Five synthetic tests pass. The 2026-09-24 read-only car snapshot
confirmed source equality and service presence; protected metadata reads remain
unknown. The exact MQTT service name is `mqttserv`, corrected after that check.

`cloud_downlink_observer.py` also previews by default. With `--execute --serial
127.0.0.1:5555 --seconds 90` it reads the existing logcat stream for at most 180
seconds, without changing log levels or clearing buffers. At most 512 events
survive as fixed labels and bounded command/status integers. Raw messages,
topics and payloads are discarded. Five tests cover controlled extraction,
privacy and preview without ADB. Two bounded car observations captured native
TCP command 201 and incoming request 511; the owner confirmed a phone refresh,
but exact gesture timing was not recorded. No actuator command was sent.

For the separately authorized on-car native-body experiment, see
[isolated native identity execution](../../docs/telematics-findings.md#isolated-native-identity-routines-executed-on-the-car-2026-09-24).
That test passed seven local cases under shell/Enforcing with synthetic inputs;
it does not establish a cloud session using an entered original pair.

See [alternative integration levels](../../docs/telematics-findings.md#alternative-integration-levels-offline-2026-09-24)
for why TCP and MQTT can select different ICCIDs and why a second unmodified
cloudmanager process is not an appropriate next experiment.

## Original SIM identity input: research only

`registration_probe.py` and `status_upload_probe.py` accept `--identity-file`
pointing to a local JSON object with exactly the string fields `iccid` (20 ASCII
digits) and `imsi` (15 ASCII digits). The file must be a regular file owned by
the invoking user, with no group/other permissions (for example mode 0600), no
symlink, and at most 1024 bytes. Use the owner's original pair; the loader can
validate its form, not its provenance or server acceptance. Do not put values
on the command line, in Git, or in reports. Preview does not open the file or
contact ADB/cloud.

The supplied ICCID/IMSI enter registration 211; that same IMSI enters login
220's digest. VIN, certificate/signing and key/UUID still come from the pinned
owner's car. This path neither reads nor writes the modem's identity properties
when an override is supplied. There is no fallback to the current SIM on a
malformed input. Without the option the previous modem-source behavior remains.
Discovery 200 does not use SIM identity and rejects this unrelated option.

Nine synthetic input/protocol tests passed. Supplied-pair live native exchanges
now have the separately recorded results above; product APK integration has
not run. These helpers still close after a bounded
exchange; they do not maintain continuous online presence, execute received
vehicle commands or transfer a session into native cloudmanager. See the
findings' process-local adapter analysis before choosing a production design.

## Native adapter build and host-only validation

From the repository root, with Homebrew OpenSSL 3 installed:

```sh
cc -std=c11 -Wall -Wextra -Werror -O2 \
  -I/opt/homebrew/opt/openssl@3/include tools/telematics/tls_identity_client.c \
  -L/opt/homebrew/opt/openssl@3/lib \
  -Wl,-rpath,/opt/homebrew/opt/openssl@3/lib -lssl -lcrypto \
  -o captures/telematics-20260923/tls-wifi/tls_registration_client
PYTHONDONTWRITEBYTECODE=1 python3 tools/telematics/tls_identity_probe.py --self-test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/telematics -p test_host_transport.py
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


## Custom-identity runtime and awake alpha

`runtime/` contains the resident process owner, app control protocol, fresh
connection loop, Android SDK/TLS bindings and typed primitive bridge for the
isolated firmware functions. The APK packages the `awake-alpha-v1` profile for
an enabled foreground service and a powered-on car. Sleep, wake-up and QuickBoot
are not qualified. `CloudNativeConnection` checks this profile's capabilities
before SDK/cloud effects; full resident-product qualification remains false.

`build_runtime_package.py` compiles production Java against Android API 33,
including its standard Java classes, with Java 8 language syntax. Install
`platforms;android-33` in the configured Android SDK. Compiling with the desktop
JDK's standard library is insufficient: it accepted `Path.of`, which is absent
on the car's Android 13. The packaged manifest records API level, component and
toolchain hashes. The app's own compile SDK is independent of this runtime API.

Run `python3 tools/telematics/runtime/test_runtime.py` for local Java compilation
and the fault-oriented host suites. They use fake SDKs/sockets, synthetic child
processes and temporary output directories; no APK, ADB or cloud access. Native
firmware execution has its separate build/replay under
`research/telematics-firmware/`. See the resident implementation section of
[the findings](../../docs/telematics-findings.md#resident-adapter-implementation-2026-09-25)
for ownership, cancellation, registration debt and remaining qualification work.
