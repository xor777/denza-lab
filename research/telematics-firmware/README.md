# Offline Denza firmware reader

Research scripts, verified on 2026-09-23 against the owner's
`Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip`. These stay outside product
builds. They read a local archive and write selected evidence to a separate
directory; no car, updater, router or cloud connection is involved.

**Product scope clarified on 2026-09-24:** firmware code must implement vehicle
data structures, telemetry construction/parsing and incoming-command checks.
The adapter may bridge identity, raw data, completed messages or transport, and
may call original native routines. It must not recreate those schemas itself.
The local instruction tests establish potential boundaries, not an integrated
replacement client. See the
[owner's adapter boundary](../../docs/telematics-findings.md#owners-adapter-boundary-clarified-2026-09-24).

The 2026-09-25 persistent-runtime work is separate from the historical bounded
on-car probes below. `build_persistent_runtime.py`, `persistent_runtime.c`,
`persistent_timer.h` and `verify_persistent_engine.py` exercise original native
instructions behind a typed primitive interface. Fifteen offline scenarios and
the timer test pass, including explicit failures at incomplete dependencies.
The output status packet stays opaque. Control/wake/post-login/heartbeat
capabilities are still disabled; passing these research tests does not qualify
an unattended product session. Exact source/build/test evidence is preserved
under `captures/telematics-20260924/cloud-quality-review/persistent-native/`;
the [resident adapter findings](../../docs/telematics-findings.md#resident-adapter-implementation-2026-09-25)
record the remaining original-code paths and Java integration boundaries.

The 2026-09-24 follow-up `verify_identity_override_native.py` extends the
identity-cache emulator with a synthetic process-local property source. Six
cases verify the actual 211/220 body builders with an original pair different
from the simulated modem pair, including reapplication after process creation
and the zero-first-byte MD5 cache edge. It requires the same pinned cloudmanager,
pyelftools and Unicorn 2.1.4. It performs no live patching, signing or networking
and does not establish privileges to deploy such an adapter. See the
[identity adapter findings](../../docs/telematics-findings.md#original-sim-pair-and-a-thin-native-adapter-2026-09-24).

The next offline pass adds two bounded native checks:

- `verify_cloud_startup_native.py <cloudmanager>` executes the original service
  factory and main control flow with synthetic Binder objects. Three cases show
  initialization and the thread pool are still requested after publication
  failure; constructors, service effects and downstream initialization are
  stubbed. This is a reason not to start an unchanged second daemon as a probe.
- `verify_mqtt_identity_native.py <mqttserv>` executes only its ICCID getter.
  Eight cases establish current-property precedence, the 20-byte length check,
  fallback to `persist.radio.iccid`, and missing/invalid results. It does not
  execute authentication or any caller's cache.

Both require pyelftools and Unicorn 2.1.4, verify the respective firmware hash,
bound execution, and reject unreviewed calls and syscalls. They have no car or
cloud connection. Evidence and the next vehicle checks are under
[alternative integration levels](../../docs/telematics-findings.md#alternative-integration-levels-offline-2026-09-24).

## Reproduce the extraction

### Isolated on-car instruction execution (2026-09-24)

`build_identity_runtime.py` builds `identity_runtime_probe.c` using installed
Clang and an ELF-capable LLD. It verifies the archived cloudmanager hash and
copies only reviewed instruction ranges into a private image with unused text
replaced by traps. No ELF initialization, native cloud daemon or stock worker
thread is launched. Android lifetime/property calls and the serializer boundary
are stubbed; real preparation/MD5/body instructions execute with synthetic data.

The resulting standalone executable passed seven cases on both the ARM64
Android emulator and the owner's car as shell UID 2000 / SELinux Enforcing.
It permits no network/Binder/file-open syscalls while running those routines,
has a five-second wall timer, and was removed from both targets after testing.
This establishes local code reuse, not deployment inside the privileged stock
process, a live cloud login, command execution or an ordinary-app permission.
See [the native runtime result](../../docs/telematics-findings.md#isolated-native-identity-routines-executed-on-the-car-2026-09-24).

Example build (does not contact a vehicle):

```sh
python3 research/telematics-firmware/build_identity_runtime.py \
  captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager \
  --out captures/telematics-20260924/native-identity-runtime \
  --linker /absolute/path/to/ld.lld
```

Requires pyelftools for extraction; execution uses no downloaded NDK, Android
libraries or real subscriber identifiers. Preserve the result manifest beside
the executable. The live plan and before/after evidence are in the output
directory, which is ignored by Git.

### Opaque data through original routines (2026-09-24)

`verify_opaque_native.py` replays exact SDK callback buffers into the original
native cache and report builder, without an independent vehicle-field encoder.
It also checks the native message constructor's deep copy and SSL-wrapper I/O
semantics. Nine cases pass in bounded Unicorn execution. The companion
`build_opaque_runtime.py` / `opaque_runtime_probe.c` execute the same reviewed
routines on hardware; nine cases also passed on the ARM64 emulator and owner
car under shell/Enforcing, with network/Binder/file/thread syscalls blocked.

```sh
python3 research/telematics-firmware/verify_opaque_native.py \
  captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager \
  captures/telematics-20260924/opaque-native-adapter/callback-opaque.log
python3 research/telematics-firmware/build_opaque_runtime.py \
  captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager \
  captures/telematics-20260924/opaque-native-adapter/callback-opaque.log \
  --out captures/telematics-20260924/opaque-native-adapter/runtime \
  --linker /absolute/path/to/ld.lld
```

Both commands are offline. They need pyelftools and Unicorn; the second also
needs Clang/ELF LLD. Generated fixtures and binary embed private callback bytes
and must remain ignored, with restricted permissions. The native report is not
uploaded. The surrounding context and external effects are stubbed; full
initialization, live subscriptions, framing/TLS and incoming dispatch remain
unproved. The firmware implements the field mapping. See
[the complete scope and result](../../docs/telematics-findings.md#opaque-buffer-execution-verified-on-the-car-2026-09-24).

The executable's optional `--stream` mode accepts the bounded opaque output of
`CloudCanSnapshotProbe` on stdin, passes bytes unchanged to the native consumer,
then invokes the original builder at clean stream completion. It requires the
producer's unregister and clean completion markers. Empty/truncated/oversized
streams fail. On the owner car a ten-second **local pipe** delivered 975 SDK
buffers without sending them through the Mac. The helper emitted only a count
and result, not the report itself; its send hook remained disabled. Both remote
files were removed and stock TCP=1/step=6 remained unchanged. Stream seccomp
allows stdin reads in addition to stdout/stderr writes and exit; it permits no
network, Binder, file-open or process creation. The stream limit is 25 seconds,
10,000 buffers and 2 MB of IPC data. This is still an isolated partial-context
probe, not a resident client.

### Original status request/reply (2026-09-24)

`verify_native_roundtrip.py` extends that isolated cache with the original
encrypted packet constructor, decoder and checked 511 status dispatcher. It
creates local synthetic session material, forwards complete packets to native
code and verifies a native request/report/reply roundtrip. Fourteen cases pass,
including invalid identity/key/frame inputs and not-ready/not-logged-in states.
The decoder's AES/CRC/VIN/UUID checks execute; no success return is substituted.
There is no Python/C telemetry schema or independent packet encoder.

```sh
python3 research/telematics-firmware/verify_native_roundtrip.py \
  captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager \
  captures/telematics-20260924/opaque-native-adapter/callback-opaque.log
python3 research/telematics-firmware/build_native_roundtrip.py \
  captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager \
  captures/telematics-20260924/opaque-native-adapter/callback-opaque.log \
  --out captures/telematics-20260924/native-status-roundtrip/runtime \
  --linker /absolute/path/to/ld.lld
```

Both commands are offline. Dependencies are pyelftools, Unicorn and Capstone;
the builder also uses installed Clang/ELF LLD. The companion
`native_roundtrip_probe.c` passed the same fourteen local checks on the ARM64
emulator and car with seccomp/W^X, and was removed. Only the status handler from
the large native switch is retained; there are no actuator handlers or network
calls. Private generated fixtures contain vehicle callback bytes.

The first hardware test exposed a missing auxiliary native vector, masked by
the first emulator's zero-based ELF mapping. Both harnesses now supply an explicit
empty vector, and the offline test unmaps the null page. This remains partial
initialization, not a complete cloud runtime. The current result proves local
native status processing, not real cloud authentication or remote control.
See [the exact results and limitations](../../docs/telematics-findings.md#native-status-request-and-reply-verified-on-the-car-2026-09-24).

### Native registration against the cloud (2026-09-24)

`native_registration.py <cloudmanager>` runs six offline checks of the original
helper constructor, identity preparation, 211 builder/codec and response handler.
It preserves native success/rejection status and original decoder checks; the
post-reply continuation is captured before effects. It imports the bounded
native harness above, not an independent packet codec. Actual identity/frame
data remain in memory when used by the live wrapper.

The preview-first `tools/telematics/native_registration_probe.py` completed one
owner-authorized real 211 exchange through car Wi-Fi at 17:50 UTC: original
native request/response code, one factory TLS signature, 101 bytes sent,
69 received, native status 1. No working-server login, telemetry, control command
or second cloud attempt occurred. The stock session's sampled state stayed
TCP=1/step=6. Native execution and TLS orchestration were on the Mac; this does
not establish autonomous deployment. The archived executable is hash-pinned;
shell cannot read/hash the installed executable, and that limitation is explicit.
See [the live result](../../docs/telematics-findings.md#one-real-registration-exchange-through-original-native-code-2026-09-24).

### Archive extraction

Python 3 with `cryptography` is required. Static disassembly additionally used
`pyelftools` and `capstone`; neither is needed for the commands below.

```sh
export DENZA_FIRMWARE_ARCHIVE="$HOME/Downloads/Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip"
export DENZA_FIRMWARE_OUTPUT="/absolute/path/to/a/separate/output-directory"
python3 research/telematics-firmware/check_config.py
python3 research/telematics-firmware/read_android.py
python3 research/telematics-firmware/current_payload.py
python3 research/telematics-firmware/extract_cloud.py
```

The first script must report `valid_pkcs7: true` and `valid_xml: true`.
The second indexes the decrypted nested ZIP without writing its 10 GB content.
The last reads only the OTA operations needed for ext4 metadata and selected
files. It validates SHA-256 of each fetched operation before decoding it.
The expected `system/bin/cloudmanager` SHA-256 is
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`.
Scripts are bounded research readers, not general-purpose ZIP/ext4 tools:
they require full replacement OTA operations, support only the observed ext4
extent layout, cap individual reads/files, and do not follow symlinks.
Do not use Python's `-O` flag: structural checks currently use assertions.
Whole payload/partition hashes and vendor authenticity are not verified by
this selective extraction. Input contents are never modified.

## How the opaque layer was identified

The public [DiLink 5.0 package](https://modhub24.com/firmware/firmware_3c4bf282)
`Di5.0_23.1.22.2505209.1_0.zip` has a plaintext nested `update.zip` and
`payload.bin`. The normal catalogue download link issued a temporary signed
URL supporting HTTP ranges. Direct raw-file requests had returned 403;
the ordinary download flow worked. No 7 GB copy was downloaded.

Selected boot operations supplied a gzip ramdisk containing
`system/bin/recovery`, 2,524,920 bytes, SHA-256
`5ec7ae12ea06ec17e89c874630d83cbd4598e269321df3ca9021750fc2df2bac`.
Only its code was inspected; it was never executed. Function labels below are
inferred from logs/callers in this stripped ELF, not exported symbol names.

| Recovery address | Observed behavior |
| --- | --- |
| `0x11e6cc → 0x11fa58` | Uses the adjacent `metadata` file to derive the Config input seed |
| `0x1f8990`, mode 0 → `0x1f9794` | MD5 of all factor-file bytes, producing a 16-byte seed |
| `0x1f8348` | SHA-256 of the seed; sums mirrored byte pairs into 16 bytes, then replaces byte `(sum >> 4) & 15` with `sum & 255` |
| `0x1f8640` | Uses that AES key and IV `MD5(seed)` with each byte shifted left by three; calls AES-128-CBC decryption with padding |
| `0x11d530 → 0x1f759c` | Decodes the 38-character package value in Config into the Android input seed |
| `0x1f759c` | Restores one substituted byte of a 16-byte wrapped seed from bytes 16/17; derives a wrapping key from SHA-256 of the final two ASCII characters using pairs `h[2*i] + h[31-2*i]`; AES-128-CBC with MD5 of those characters as IV, no padding |

The algorithms are implemented in `check_config.derive`, `check_config.decrypt`
and `read_android.package_seed`. No private vehicle identifiers, account
tokens or service credentials enter this archive decoding path.

Validation was concrete: Config has valid PKCS7 and XML syntax; the Android
member has valid final padding, ZIP headers and a coherent central directory;
its OTA metadata has valid ZIP CRCs; selected payload operation hashes match;
the reconstructed cloud files parse as ARM64 ELF. This establishes compatibility
for this package, not every DiLink version.

## Saved evidence and continuation

Local binaries, readable Config, hashes, source indexes, and annotated assembly
are in ignored `captures/telematics-20260923/readable-firmware/`.
They are local artifacts, not committed or uploaded. The original archive in
Downloads is needed to reproduce or extract additional files. Temporary signed
download URLs and large intermediate images are not retained.

Start with [the telematics findings](../../docs/telematics-findings.md) for the
current cloud-client gate and the distinction between static proof and vehicle
observations. Do not resume the old search for a decoder: this package is now
readable. Follow-up bootstrap evidence is retained in ignored
`captures/telematics-20260923/network-bootstrap/`, with its own README and
SHA-256 manifest. It traces initialization/writers of the cellular gate,
APN-specific routes, hardware-backed TLS and the current 211 → 200 → 220 → 507
registration/login/token sequence. The saved private-profile log contains the
gate value zero; the public-profile value is inferred, not directly logged.

At this archive-decoding stage the remaining targets were hardware-provider
access and whether the report supplies the official phone status feed.
Static analysis found that even
`SDF_OpenDevice` initialization can write a vendor cache, so do not invoke it as
a read-only probe. A working official-app status feed was subsequently demonstrated below; a
continuous on-car sidecar remains unimplemented.

The follow-up SOC trace is retained in ignored
`captures/telematics-20260923/battery-upload/`. It connects the already-known
`0x4A505038` getter through the MCU descriptor and HAL scale to stock
cloudmanager message **512**, CAN-FD body bytes **84/85** (12 bits, ×0.1 percent).
The original vehicle read is historical; the later upload below establishes
delivery of a fresh measured SOC to the official phone status feed.

To inspect an existing **unframed 104-byte binary body** offline:

```sh
python3 research/telematics-firmware/inspect_status512.py /path/to/status512-body.bin
```

This requires only the standard library. It rejects other lengths/branch markers,
masks the adjacent upper nibble, and reports values over 100 % as invalid.
It cannot decrypt a capture, validate its freshness, or send anything.

Factory identity follow-up is in ignored
`captures/telematics-20260923/identity-access/`: five crypto/Binder library hashes
match the live vehicle, and one explicitly approved certificate/signature test
verified a factory RSA-2048 signature locally through the shell Binder path.
The later `tls-wifi/` corpus establishes mutual TLS through vehicle Wi-Fi;
`registration-inputs/`, `status-passive/` and `phone-validation/` then establish
application login and official phone-feed delivery.
The certificate/signature methods can wake and reinitialize the chip, including
conditional factory PIN recovery; the exact internal branch taken was not
observed. The
[local identity probe](../../tools/telematics/local_identity_probe.py) defaults
to preview only. The owner-approved one-time test is complete and was not
retried; that approval is not an open-ended permission to run it again.
See the findings and retained result before any further live experiment.

The subsequently authorized [TLS probe](../../tools/telematics/tls_identity_probe.py)
and [native adapter](../../tools/telematics/tls_identity_client.c) completed mutual
TLS to `dilinkreg-cn.denzacloud.com:6001` using one factory signature. It sends no
application packet. Keep the `adb shell -T` duplex correction and intermediate
CA chain-building requirement from the current findings; neither certificate
verification nor hostname verification should be disabled.

Format references: [AOSP payload schema](https://android.googlesource.com/platform/system/update_engine/+/refs/heads/main/update_metadata.proto),
[boot unpacker](https://android.googlesource.com/platform/system/tools/mkbootimg/+/refs/heads/main/unpack_bootimg.py),
[ext4 superblock](https://www.kernel.org/doc/html/latest/filesystems/ext4/super.html),
[inodes](https://www.kernel.org/doc/html/latest/filesystems/ext4/inodes.html),
[extent trees](https://www.kernel.org/doc/html/latest/filesystems/ext4/ifork.html).

## Wi-Fi bootstrap and official SOC delivery verified (2026-09-23 follow-up)

The helper successfully completed factory mutual TLS, registration 211, discovery
200 and login 220. Denza returned `dilinknat0-cn.denzacloud.com:6041`; the official
phone app displayed online status. Two subsequent, explicitly approved message
512 uploads delivered **74% SOC**, independently measured by passive stock CAN
and the getter, to the official phone app. The phone also displayed 472 km,
35% fuel, and charging progress. This supersedes the older bootstrap/feed unknowns.

`registration_packet.py` is a network-free encoder/strict reply decoder.
`verify_registration_native.py` runs only bounded serializer/AES/MD5 firmware
functions against synthetic data using Unicorn 2.1.4. Seven fixtures match byte
for byte, including the 512 body-copy routine and both charging branches.
The temporary Unicorn installation is removed after verification.
Reproduction requires the existing current cloudmanager binary and separately
supplied Unicorn/pyelftools/cryptography; no additional firmware copy is needed.

`verify_identity_native.py` additionally executes the native constructor,
identity preparation, MD5 and 211 body builder with synthetic Android property
values, stopping before serialization. Fifteen scenarios / twenty calls cover
short, long, hexadecimal and absent inputs; cached values after property
changes; partial initialization mixing old ICCID and new IMSI; and the binary
MD5 first-byte cache check. It requires pyelftools and Unicorn 2.1.4, rejects
unknown firmware hashes/calls/syscalls, and bounds each call. Run:

```sh
python3 research/telematics-firmware/verify_identity_native.py \
  captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager
```

Results and the separately inspected older Dolphin comparison are retained in
`captures/telematics-20260924/iccid-offline/`. Native construction of a synthetic
body says nothing about its acceptance by the real server; no online probes or
identifier changes are part of this harness.

`inspect_identity_cache.py` inventories direct constructor/preparation calls,
singleton references, static initialization and candidate identity-field
accesses in the same hash-checked native binary. Pass the firmware path and
`captures/telematics-20260923/readable-firmware/cloud-function-ranges.json`.
The output is a static review aid, not complete pointer-alias analysis. The
reviewed lifecycle/reset paths and Binder interface did not expose a narrow
cache-refresh operation; evidence lives in `captures/telematics-20260924/cache-refresh/`
and the corresponding findings section. No reset command is run by this script.

`status512_body.py` assembles 25 cache entries into the native 104-byte body.
Live tools are in `tools/telematics/registration_probe.py` (preview by default),
`tls_identity_probe.py`, `tls_identity_client.c`, `CloudCanSnapshotProbe.java`,
and `status_upload_probe.py` (preview by default).
Do not replay these as a read-only probe: bootstrap registers/logs in at the
server; crypto calls can take stock chip initialization/recovery paths.
The status uploader requires a fresh capture, independent SOC agreement, verified
TLS and accepted login before one report; it has no remote vehicle-control executor.
Eleven local TLS/application scenarios passed, including no report on failed login.

Resume from `docs/telematics-findings.md` and the evidence READMEs for
`registration-inputs/`, `tls-wifi/`, `phone-validation/`, and `status-passive/`.
The charging override is resolved: device 1009 / FID 0x34400018, live value 1.
Both diagnostic uploads used the owner's explicit permission for one unmeasured
byte 102 (missing CAN 0x417), retaining the stock initial zero. The SDK names
presence-getter result 2 as DEVICE_OFFLINE_ALWAYS. That does not prove zero
is the correct production value. Continuous operation, ordinary APK access and
production handling of this auxiliary field remain open. Test sessions close
five seconds after the report; a later offline icon is expected even though
readings persist.

The subsequent stock-client reuse investigation is in
`captures/telematics-20260923/stock-client-wifi/`.
`verify_network_gate_native.py` runs only the native network-notification method
with synthetic profiles/state and explicit downstream stubs. Forty-two cases
confirm the eligibility/disconnection branches; they do not themselves establish
live stock DNS/TLS/login. The subsequently authorized held test at 11:40 UTC
did establish native DNS/TLS and stock TCP=1, registration status=2, token flag=1
over Wi-Fi. Evidence and stop instructions are in `stock-client-wifi/live-hold-1/`.
The runner defaults to preview; stock sessions also persist tokens and handle
incoming commands. Native telemetry content and long-term behavior remain open.
The owner observed current data in the official app during the stock session.
The host observer/guard subsequently exited on ADB timeouts without restoring
the profile. A 15:15 Moscow read-only check confirmed stock TCP=1 with no host
test running; the owner explicitly requested leaving this car session active.

`native_session.py` extends the original-code adapter with native discovery200,
login220 and status511. Original code owns body construction, endpoint parsing,
login acceptance, checked decode, correlation and report framing. Only external
I/O/runtime dependencies and post-login side effects are captured. Incoming
command allowlisting occurs after native decode and before dispatch. Offline:

```sh
PYTHONPATH=/tmp/denza-hud-analysis-deps PYTHONDONTWRITEBYTECODE=1 \
  python3 research/telematics-firmware/verify_native_session.py \
  captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager \
  captures/telematics-20260924/opaque-native-adapter/callback-opaque.log
```

Ten native cases passed. The owner-authorized 2026-09-24 18:01 UTC car-origin
cloud session then passed discovery/login, received an actual511 request and
sent a181-byte native reply built from1,894 fresh SDK buffers. Native SOC82%
matched the independent getter. Stock TCP was paused only during the adapter
session and restored afterward; Wi-Fi/profile/APK unchanged. Exact source and
runtime evidence: `captures/telematics-20260924/native-session-status/`.
The host runner is `tools/telematics/native_session_probe.py`, preview by default.
No repeat is needed for an already-proved exchange. Native post-login ancillary
work was captured, token_flag was already1, whole-field cache completeness is
unproved, and this does not establish actuator execution, autonomous on-car
operation, custom identity with a replacement SIM, or reconnect/sleep behavior.

`session_runtime.c` and `build_session_runtime.py` move the reviewed original
211/200/220/511 routines to a native ARM64 pipe worker. The builder pins the
archived firmware hash, replaces unselected text with traps, and rejects native
syscalls in retained ranges. The worker separates executable/writable pages,
freezes the supplied identity after START and restricts its syscalls to pipe I/O
and exit. No daemon startup, network/Binder operation or actuator dispatch is
allowed inside this worker. `verify_session_runtime.py` compares synthetic
results byte-for-byte with the previous emulated native oracle and checks
rejected/corrupt login, unexpected command, pre-login request and input handling.

Build (requires pyelftools, Capstone, clang and an ARM64-capable ELF linker):

```sh
python3 research/telematics-firmware/build_session_runtime.py \
  captures/telematics-20260923/readable-firmware/current-files/system/bin/cloudmanager \
  --out captures/telematics-20260924/oncar-native-session/runtime \
  --linker /Users/dmitry/.rustup/toolchains/stable-aarch64-apple-darwin/lib/rustlib/aarch64-apple-darwin/bin/gcc-ld/ld.lld
```

The network-free verifier takes an explicit Android serial, firmware path and
opaque fixture capture, and expects the compiled worker at
`/data/local/tmp/denza-session-worker`. Seven cases passed on emulator and car.
Do not confuse that synthetic test with the stateful live controller in
`tools/telematics/OncarNativeSession.java`.

The authorized on-car test at18:14–18:17 UTC used Android TLS and the factory
signer, explicit current ICCID/IMSI input, native211/200/220 and two real511
request/replies across a new worker/TLS session. Both responses reported81% SOC,
matching the independent getter. All runtime work was local to the car; the
Mac only deployed and observed. Stock TCP was restored and test files/processes
removed. The first25s request window timed out cleanly before the owner refresh;
its accepted registration/discovery were reused for the successful second run.
Evidence: `captures/telematics-20260924/oncar-native-session/`. This is a bounded
separate helper using archived routines, not modification of the installed
daemon. Product integration, unexpected disconnect/sleep recovery, replacement
SIM transport, provisioning/MQTT and actuator commands remain unproved. See the
[on-car findings](../../docs/telematics-findings.md#on-car-native-adapter-with-an-explicitly-supplied-sim-pair-2026-09-24).

### Quality gate after the generated-pair command experiment

`audit_control_lifecycle.py <pinned-cloudmanager>` runs only host Unicorn with
synthetic opaque requests. It covers MCU result paths as well as requests and
records unresolved dependencies, allocator growth and the asleep wake path.
See `docs/telematics-findings.md`, "Generated pair: real command and offline
promotion review" and "Cloud product recovery contract under preparation".
The short worker is not a persistent custom backend; removing its climate
allowlist or its time limits does not qualify it for product use.

The C wrapper now treats terminal completion as a per-exchange event. Its
`terminal_is_per_exchange_not_sticky`, two-distinct-command and interleaved-wake
regressions passed on the local emulator as part of twelve synthetic IPC cases.
The test worker was temporary; no APK or car deployment was made.

`bounded_arena.h` replaces the bump-only allocator/no-op deletes while preserving
the worker's command and time limits. `test_bounded_arena.py` exercises the actual
header with host ASan/UBSan. `audit_bounded_arena.py` uses that compiled allocator
with original firmware instructions and requires per-iteration forwarding and
completion with distinct correlations. The100-control/100-status replay used
256 bytes at peak and left no live allocations. This does not prove every other
firmware allocation path or qualify an unlimited process.

`verify_generic_control_closure.py` separately extends offline callback coverage
to all256 synthetic532 subcommands, including sub5's secondary object and native
709 side effects, sub17's32-second timer, and sub39's special CAN/timeout path.
It does not widen the target worker's allowlist. Its separate `--timer-semantics`
run executes sub5's later5-second callback (stop plus another native709) and
the original16/32-second generic timeout body. The latter clears busy without
a reply and still allows a later MCU terminal result. Forced duplicate expiry
is distinct from real duplicate timer delivery. Actual timer scheduling,
TimerEvent lifetime and SDK listeners remain unqualified.

`verify_wake_wait_native.py` executes the original wake helper and power-state
getter with explicit SDK/clock/condition fixtures. Eight cases cover awake,
delayed external state, timeout, setter failure, spurious wake and wait error.
The helper issues `setInt(1005, 0xaa00004a, 1)` and waits up to three one-second
attempts. A timeout does not become success. The delayed-state fixture is **not**
proof of the native event callback or of a sleeping car waking; the real callback
also reports502, wakes the condition and invokes further lifecycle work. Those
dependencies cannot be replaced with an assumed awake state.

`verify_wake_callback_native.py` then exercises the original MCU state callback,
502 construction and opaque queue drain in five offline cases. The sleeping536
dispatcher itself creates its queue item; direct awake callback invocation
cancels the two-second timer and forwards that item once, byte-for-byte.
Duplicate state is a no-op. Sleeping532 instead returns native reason0x25 after
three wake timeouts without queuing an actuator command. SDK getters/properties,
timer scheduling, listener delivery, Binder writes and cloud sends remain
external fixtures, so this is still not sleeping-car acceptance.

`verify_sleep_transition_native.py` adds an original1→0→1 MCU callback pass with
the original secondary constructor and explicit empty listener/scheduled-work
fixtures. Both native502 messages, sleep-side property effects and the returning
wake notification/getter/drain execute. Populated lists, SDK delivery and timer
scheduling remain outside that proof. It does not qualify an unattended service.

The research Android TLS helper now scopes peer verification, identity and the
signing budget to one handshake. `tools/telematics/test_oncar_tls_isolation.py`
tests its actual Java provider on the host using synthetic keys. Twelve host
cases and a separate five-case Android/Conscrypt integration suite passed.
Neither uses the car or its key.
