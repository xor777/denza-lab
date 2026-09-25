# Custom SIM cloud runtime

This document fixes the application/runtime contract for the custom ICCID/IMSI
adapter. The current target is the explicitly bounded `awake-alpha-v1` profile:
an awake vehicle and a live Denza Apps foreground service. The implementation is
under qualification. It is **not** a claim that the finished runtime survives
QuickBoot, works over an ordinary SIM, or has passed live vehicle acceptance.
`CLOUD_NATIVE_PILOT` remains false until the original awake paths qualify.
Historical experiments and original-code evidence remain in
[telematics-findings.md](telematics-findings.md).

## Responsibilities

The original firmware instructions encode registration and telemetry, decode
cloud packets, dispatch commands, generate results, and make vehicle decisions.
The adapter provides bounded allocation, clocks, timers, property/SDK calls,
opaque buffers, TLS and process management. Do not add handlers for individual
climate, seat, door or other vehicle commands. Do not replace an unknown
external dependency with a successful return.

Three process roles exist outside the ordinary APK:

1. The shell guardian owns the common kernel lock, authenticated local socket,
   installation marker, stop fence, cleanup obligations and worker lifetime.
2. A Java session worker owns TLS and SDK subscriptions for one session.
3. An isolated ARM64 child executes the selected original instructions.

The APK opens a shell bridge to the guardian. Closing the bridge or activity
does not mean OFF. The foreground service renews a 30-second permission every
five seconds; its death or expired permission ends custom effects and triggers
cleanup. The guardian must not keep the connection alive independently after
that deadline. CUSTOM does not automatically resume at boot or QuickBoot.
After sleep a new application session and fresh power proof are required. No
artificial ACC or perpetual wakelock is part of this design.

## Management protocol 3

The application starts its bridge as:

```text
CLASSPATH=<workdir>/cloud-native-proxy.jar app_process /system/bin
  dev.denza.tools.runtime.CloudNativeMain bridge <workdir> <marker> <nonce32>
```

Arguments are shell quoted. The workdir is content addressed:
`/data/local/tmp/denza-cloud-native-<jar-sha-prefix12>-<worker-sha-prefix12>`.
Existing component files are never overwritten. Staging a new version does not
replace a running guardian. Its actual `runtime_id` comes from its response.

The bridge retains the local-ADB READY/BEGIN/END framing. It connects over a
local UNIX socket; it opens no TCP listener. The guardian checks shell peer UID
and a challenge HMAC using a 256-bit secret in a shell-only `0600` file under
`/data/local/tmp/denza-cloud-native-state`. Neither the secret nor its contents
travel through application logs, argv, UI or Downloads. An installation ID is
public coordination data, not the authentication secret.

JSON requests are at most 4096 ASCII bytes, have a positive increasing `id`,
and contain integer `protocol:3` and `op`: `PROBE`, `ATTACH`, `START`, `STATUS`,
`RENEW` or `STOP`. Only START includes `iccid` and `imsi`. Requests and
unfiltered replies are never logged.

Every response, including `ok:false`, has the full status envelope:

| Field | Meaning |
| --- | --- |
| `protocol` | Exactly 3; native engine IPC independently remains 2 |
| `id`, `op`, `ok` | Corresponding request and acceptance |
| `owner_id` | Empty or the guardian's 32-digit lowercase hex session owner |
| `runtime_id` | Content-addressed version of the guardian, not its bridge |
| `config_generation` | Positive generation for an owner; zero for absence |
| `profile` | Exactly `awake-alpha-v1` |
| `capabilities` | Verified capabilities of the bounded awake profile; never a claim of sleeping-car wake support |
| `lease_until_uptime_ms`, `lease_active` | Guardian uptime deadline and current service permission |
| `retryable` | Whether this outcome permits an automatic retry |
| `registration_uncertain` | Local teardown does not prove server-side restoration |
| `session_live`, `stage`, `code` | Actual custom session facts; never inferred from stock TCP |
| elapsed clocks/counters/events | Bounded, redacted observation fields |

`PROBE` is a snapshot, not permission to START. `START` must atomically arbitrate
ownership. Exact absence is `stage=stopped`, `code=owner_absent_confirmed`,
`session_live=false`, empty owner and generation zero, backed by the kernel lock.
`owner_present` must identify its owner. The application does not infer absence
from a dead bridge, offline network, missing Java object or timeout.

`START` includes `profile`, a new 32-lowercase-hex `service_instance` created in
FGS `onCreate`, and `renew_seq:1`. `RENEW` carries the owner ID, that same service
instance and a strictly increasing sequence. Bridge reconnection does not reset
the sequence. Neither STATUS nor PROBE renews the permission.

`ATTACH` authenticates the installation/generation and includes the observed
owner ID and service instance. Attaching the same service reconnects IPC without
resetting permission or the worker. A different service instance first stops
and reaps the old worker, checks power, and binds the new instance. Its first
accepted RENEW may resume the same immutable pair in a new worker epoch; the APK
does not resend START. Old renewal or stop messages cannot act on the adopted
service. The application persists a pending owner journal before START, then
atomically adopts the acknowledged guardian owner. A lost START reply is
recovered by PROBE/ATTACH; it is never immediately replayed.

Permission expiry is checked on requests as well as watchdog ticks; a new
service token cannot resurrect an expired lease. STATUS and same-service
ATTACH read the watchdog snapshot (normally at most 500 ms old), without
consuming worker failure or altering retry state. The guardian and session loop
retain elapsed/uptime continuity across retries. A detected suspension fences
the generation, including suspension during worker launch or native preflight;
fresh ON getters after wake do not authorize the old session.

`STOP` is local cleanup. It permits the same installation's generation to have
advanced to OFF. It does not need the original factory ICCID/IMSI, and does not
promise a factory server registration. STOP includes `owner_id` and
`service_instance`. While the current marker still desires CUSTOM and a valid
lease exists, both must match. Empty service identity is accepted only for
confirmed absence, cleanup debt, expired permission, or an authoritative
current-installation OFF marker. Explicit user OFF publishes that marker before
cleanup. A stale FGS cannot stop a newly adopted live FGS. Legacy protocol 2
remains for discovery/status/stop of previous owners; it cannot START and does
not bypass the new guardian's active-service ownership check.
An already authenticated connection may
stop even if marker storage later becomes unavailable. A fresh unauthenticated
bridge must not infer this authority from unavailable storage. The stop fence
prevents the old custom generation from resurrecting itself.

## Installation and settings

The application publishes an atomic regular-file marker at
`getExternalFilesDir(null)/cloud/install.json`:

```json
{"protocol":2,"install_id":"<32 lowercase hex>","generation":1,"desired":"off"}
```

The private preferences retain installation ID, generation and a configuration
stamp. The stamp is never exported and includes the identity pair so a real
configuration change advances the generation. Repeated reads do not rewrite
the marker. It contains no SIM identifiers. Factory mode publishes `off`, which
only disables the custom owner, not the factory client.

A clean installation defaults to OFF. Updates keep preferences and marker.
Data clearing/uninstall must delete the app-owned marker; an old guardian must
stop after confirmed marker loss or generation/install change. Unavailable
storage is an error requiring effects to stop, not proof that cleanup succeeded.
Android shell access, FUSE atomic publication and removal behavior still need
one controlled vehicle acceptance session.

UI order is mode, pair fields and then enable. Mode and pair are immutable while
enabled, stopping, busy or carrying unresolved ownership. Reconnect never
regenerates the pair. Pending teardown precedes a fast OFF/ON. Failure to persist
the journal prevents START; failure to persist cleanup prevents clearing pending
OFF. Permanent registration/compatibility errors are not endless START retries.
Network retries remain transitional instead of a brief red error.

The service-menu report switch is OFF by default. When enabled, one replaceable
`Download/Denza Apps/denza-cloud-report.txt` contains bounded redacted history.
No pair, keys, command body or raw native error is included. This export is an
application function; it is not required to keep the guardian alive.

Pending OFF and a replacement START are separate application phases. The caller
must durably clear `pendingDisable` before the next phase can start a session;
the marker is OFF during cleanup even when a rapid ON is queued. Terminal
refusals are persisted against the configuration generation and survive app
death. A failed SharedPreferences commit is retried even when its in-memory map
already matches. External marker I/O does not hold the settings monitor used by
the UI; a changed snapshot cannot authorize START after publication.

## Native clocks and effects

Before each effect, the awake profile requires a fresh physical POWER read:
device 1005, ACC `0x99000037 == 1` and MCU `0x99000003 == 1`. BODYWORK power level
is not an ACC substitute. Unknown state, an OFF/sleep event, or a detected
Android suspension ends the session without automatic retry. The SDK listener
reduces detection latency; polling remains necessary and listener registration
alone is not proof of actual transition delivery.

Native protocol 2 advertises `PROTO2=1` and receives:

```text
TICK <elapsedRealtime_ms> <uptimeMillis_ms> <currentTimeMillis_ms>
```

Process progress and stop watchdogs use uptime so normal Android suspension
does not count as a hang. UI timestamps use elapsed realtime. Native steady/condition waits use uptime;
wall time supplies the original time/localtime operations. Relative timer clock
semantics are part of native qualification, not inferred from a clock name.
WAIT replies carry all three clocks. Events are bound to the session epoch and
operation ID. A command with unknown outcome is never replayed after a child
restart. Queue limits and unknown dependency calls fail closed.

Shared property writes require per-property ownership rules. A cleanup obligation
must be durable before the write. Private native status stays inside the engine.
Do not implement a universal snapshot-and-restore rule that rolls back counters
or writes made by another owner.

## Compatibility and connection handoff

Only the reviewed firmware profile is eligible. Its build fingerprint, verified
boot state, accessible library hashes and system interfaces must be checked
before stock connection changes. The protected installed cloudmanager ELF is
not readable from shell; library hashes are not represented as that ELF's hash.
The retained source ELF is pinned at build time.

The isolated original engine receives a virtual `double_apn` profile through
both its initialization and property-read boundary. This selects its original
public-internet registration/discovery path, matching the host TLS endpoints.
It does not change the modem or the physical `persist.sys.byd.apn_type` value.
The actual physical profile is read separately for pausing the stock gate and
checking ownership drift. Wi-Fi and an ordinary SIM remain alternative Android
transports for this same public path; ordinary-SIM operation still needs live
acceptance.

Each car's certificate chain is verified against the pinned BYD root. Its own
leaf verifies the hardware signature; the author's leaf is not an allowlist for
other cars. Server trust and hostname validation remain required.

Stock gate return handling must decode the actual Binder reply. Existing TCP=0
alone cannot prove successful suspension. If the stock client returns while the
custom owner is active, custom effects cease. Worker crash recovery reaps the
old worker and resolves cleanup before starting another with the same pair.

`notify_nw` is a void Binder method: the reply reports an exception status, not
a Boolean result. On the pinned firmware, the original function clears its gate
synchronously for `-2` with `triple_apn` or `-5` with `double_apn`. The opposite
notification is ineffective. Check the unchanged profile around the call, parse
the reply, and wait for TCP down separately. The field is not readable through
the public Binder interface; do not label TCP down as a direct gate read.

Local CUSTOM OFF leaves cloud disabled. It does not infer a previous gate from
TCP, send READY, or automatically restore factory registration. Factory mode is
enabled separately by the existing application policy and reports its actual
connection result. If another owner changes the profile or reconnects stock,
stop custom without altering that competing state. The owned pause journal
tracks this explicit intent; it is not a TCP snapshot to restore.

## Build and verification

`tools/telematics/build_runtime_package.py` compiles all platform Java sources
(excluding tests) with JDK 17, creates dex with Android d8, builds the pinned
ARM64 engine and emits a deterministic jar plus manifest. The manifest binds
protocols, component hashes, runtime ID, firmware hash, source inputs and
capabilities. It is published last. Source changes during compilation reject
the candidate.

A Java control helper remains packaged when the native pilot is disabled. It
can PROBE/ATTACH/STATUS/STOP a surviving older owner, but cannot START a session.
Thus an APK update that disables CUSTOM can still finish local cleanup. It
contains no ARM64 engine; its START path is refused by both app and helper.

Gradle task `:denza-apps:buildCloudRuntimeCandidate` is separate from APK tasks.
It needs `DENZA_CLOUD_LINKER` (or `-PcloudRuntimeLinker`) pointing to ARM64-capable
LLD, Python dependencies from the research environment, JDK 17 and the Android
SDK. Tool paths are configurable; qualification is not. Native pilot remains a
reviewed literal in the Gradle source. When enabled, `QualifiedCloudAssets` and
the app independently reject missing capabilities, wrong protocols or mismatched
hashes. The manifest separately binds `profile_qualified` and
`profile_capabilities` for `awake-alpha-v1`: native registration codec, opaque
data ingestion, awake control, awake wake acknowledgment, awake timers,
post-login and heartbeat. All must be Boolean true and native IPC must be 2.
Full `product_qualified` remains false for this alpha. There is no build property
that permits an incomplete profile or upgrades full sleep capabilities.

Before APK acceptance: execute native original paths, guardian failure tests,
app lifecycle tests and the cross-component protocol contract; run an independent
clean-context adversarial review and fix confirmed findings. Host tests cannot
prove Android shell lifetime or actual vehicle sleep/wake.

Awake-alpha acceptance: first verify power getters/listener on the owner's car,
then at least 30 minutes of data and command exchanges while awake, including
UI backgrounding, adapter-only socket interruption, OFF/ON, worker failure and
FGS termination. Finish with genuine factory TCP and its next stock keepalive.
Do not distribute the APK until cleanup and factory return are confirmed.
Ordinary-SIM transport has its own check; Wi-Fi success cannot substitute for
it. Do not disable the owner's Wi-Fi or reboot without a separately agreed
experiment. Publication is a separate step after acceptance.

The deferred autonomous product additionally requires at least one hour of
natural parking, wake, actual QuickBoot survival, update and reinstall. These
checks and unsupported full capabilities are not waived by the awake alpha.

OFF and factory activation always prove global owner absence or completed local
cleanup, including in a nonpilot APK with no saved owner journal. Clearing app
data or downgrading the APK cannot bypass this proof. The always-packaged control
artifact excludes `CloudStartPermit`; alternate bridge/guardian/worker entry
arguments cannot grant START capability to that artifact. Its recovery-only
guardian can settle existing cleanup debt without fencing a newer configuration
that it has never owned.

## Remaining system lifecycle boundary

The matching framework keeps a second connection state in
`BYDTCPConnectService.tcp_status`, updated by its private
`ICloudAlarmListener.notify_tcp_status` callback. Changing a property or
broadcasting the UI status action does not update that field. Its stock
`network_repair` branch can request a parked head-unit reboot after 50 keepalive
ticks (250 seconds each), subject to its other vehicle conditions. An hour-long
parking test alone cannot exclude that later path. The runtime must close the
original lifecycle notification path before product qualification; do not mask
it by changing counters, update flags or fabricating a stock connection.
The explicit `stock_lifecycle_bridge` capability remains false and is required
by full autonomous qualification, independently of command and timer
capabilities. Completing packet delivery alone cannot qualify that product.
The separately named awake alpha requires fresh ACC=1 and MCU=1 and terminates
at their loss; it does not set this capability true or write recovery counters.
Accumulated stock counters are not cleared by stopping CUSTOM. Factory return
therefore requires real stock TCP=1 and a subsequent genuine stock keepalive
before the controlled alpha can be distributed.

`sys.tcp_connect_status` inside the isolated native copy describes that copy's
connection. It may be private to that engine, with original SET/GET transitions
and an initial zero, while stock competition continues to use the physical
stock Binder status. This does not replace the framework callback above.

`sys.cloud.remote_controling` has an external reader and writer in the matching
ClientConfigurationService `CloudReboot`: `isRemoteControl()` prevents its
separate reboot policy while the value is one, and `sync_mcu_sleep()` clears it.
The APK extracted from the retained OTA hashes to the already analysed live APK
(`eba179dc81b5efb21e291f69e8324fea2820112b0e46b0e6cbe0d70b3693c4b9`).
Thus this property cannot be treated as a private native variable. Shared writes
remain gated until their ownership and cleanup policy is qualified. The
`persist.sys.505_req_status` latch is isolated per native session. Original code
resets it to zero, sets it on 505 request and reply paths, and reads it after
login to avoid repeating its own request. The bridge supplies initial zero and
accepts only original zero/one transitions; it neither seeds from nor writes the
stock Android property. A previous stock session's flag cannot suppress the
adapter's request. The bounded OTA/corpus audit found no other literal consumer
in scanned files, but skipped files and computed keys prevent claiming global
reader absence. Evidence: `awake-alpha/property-audit/505-request-status.md`
under the same day's capture directory. This decision does not generalize to
other shared properties.

The exact `sys.virtual.vin` publication is also confined to the session's
property namespace. Original `0x5e6c8` obtains a 19-byte HAL value, stores it in
its own object and writes this property at `0x5e7cc`, ignoring the write result.
The inspected caller and domain setup continue from the object, without reading
the property. This is deliberately suppressed external publication, not full
stock parity. The bounded OTA scan, including framework.jar and services.jar,
found no other literal consumer; computed names and unscanned components remain
outside that evidence. See `awake-alpha/property-audit/virtual-vin.md`.

`sys.cloud_domin_ip` is likewise a private diagnostic publication. The sole
literal writer `0x4c2b0` formats `211:%s _200:%s` from internal address buffers
and ignores the setter result; no direct read or external literal consumer was
found in the bounded scan. The synthetic empty-address fixture is not evidence
of successful DNS. See `awake-alpha/property-audit/cloud-domain-ip.md`.

The secondary transport retains the canonical host/port accepted from the
authenticated R200 reply and the IPv4 address selected by original code from an
actual bounded DNS result. Routing uses that address; TLS verifies the canonical
hostname using the same BYD chain and hardware signer. The bridge never turns
an IP address into the TLS identity. Exact write counts reach the original
sender callback only after completion; a partial or unknown result aborts the
session without replay. Closing cancels published raw sockets before TLS and
keeps the slot owned until bounded cleanup joins its threads. Seven emulator
cases, using only synthetic keys and a local TLS server, pass including selected
IP with correct/wrong hostname, wrong CA and invalid signature. Evidence:
`awake-alpha/tls-emulator-selected-ip.json`; this does not qualify the complete
native secondary receive path or prove a live vehicle command.

Evidence: `captures/telematics-20260925/custom-adapter-implementation/` contains
`remote-property-reader.json`, the unchanged Java source is in
`captures/telematics-20260922/token-cause/CloudReboot.java`, and the matching
framework source is in `captures/split-firmware-20260923/jadx/services/`.
Selected event-bus and timer dependencies were extracted from
`/system/lib64/edge/` into the `eventbus/` evidence subdirectory with OTA operation
hash verification. No full image or second archive was retained.

## Earlier offline implementation checkpoint, 2026-09-25

This remains an unqualified candidate, not a finished CUSTOM release. The native
inventory selects 78,628 bytes of the pinned cloudmanager's 338,892-byte `.text`
(about 23%). Its 100 range entries merge into 74 regions; these are address
ranges, not a reliable count of functions in the stripped binary. The embedded
image is 622,592 bytes because it preserves address layout, data and tables.
The ARM64 executable is 695,592 bytes (about 680 KiB); the Java dex jar is
142,148 bytes (about 139 KiB). Together they are about 818 KiB, before APK
compression. Two independent candidate builds produced identical component
and manifest hashes. Full measurements and hashes belong to
`captures/telematics-20260925/custom-adapter-implementation/native/build.json`.
The complete package and reproduction evidence are in the neighbouring
`candidate/` directory; no APK was built or distributed at this checkpoint.

The original ranges cover registration/session codecs, message dispatch,
command-result handling, queued wake processing and timers. Selected code is
not synonymous with a fully working feature. Twenty-one offline replay cases
exercise specific paths, including nonempty sleeping-command queue handling;
they do not qualify every command, a complete parked lifecycle or actual SDK
effects. DNS startup through the original network callback, sub5, ACC event-bus
dependencies, heartbeat, shared-property ownership and delivery to the stock
system listener still have unresolved dependencies. Capability gates remain
false for those full paths.

The independent native review reproduced the narrow registration/status codec
paths without confirming a new memory or ABI defect. It identified an overly
broad timer capability: the original `CLOCK_REALTIME` relative timers are
scheduled against the parent's `elapsedRealtime`, and the replay clock cases
do not prove actual Android suspend semantics. `native_posix_timers` therefore
remains false despite successful scheduler unit cases. R220 fixture defaults
(zero SDK getters, empty properties and acknowledged property writes) are
simulation assumptions, not verified platform dependencies; green post-login
replay is not qualification of the post-login lifecycle.

The adapter is small on disk but has a substantial compatibility layer: original
machine code plus native libc/SDK/timer boundaries, Java TLS/IPC and autonomous
process ownership. It does not implement individual climate/seat/lock command
schemas. Neither disk size nor offline tests establish RAM, CPU or battery
cost; those require measurements during the later agreed vehicle acceptance.

## Awake component integration, 2026-09-25

The later integration replaces Java's separate G/D/L bootstrap with the original
chain: `NETSTATE 4` produces 211, its actual response produces 200, and the
discovery response produces 220. A fresh native child always consumes fresh
registration and discovery responses, even when Java retains the same saved SIM
pair. An endpoint cached from a previous native process cannot initialize that
process's original sender state. Registration result zero terminates the attempt;
the original rejection path destroys its transport instead of starting discovery.

The bridge now provides fresh randomness through `RANDOM_BYTES`, and maintains
the isolated copy's unlock/VIN counters and command-result properties privately.
Those counters survive a socket retry within one Java owner, and reset with a new
owner. It does not write stock TCP status, registration-error state, recovery
counters or the keepalive wakelock handshake property
`sys.cloud.201_send_status`. Primary and secondary TLS distinguish a rejected
certificate/hostname from a transient handshake EOF.

Offline integration uses the actual Java pipe, primitive bridge and platform
allowlists against the actual ARM64 executable under the emulator. Startup,
registration/discovery/login, an awake 536 request, and the sub5 result/timer
chain pass this boundary. The fixture supplies explicit synthetic external
responses and acknowledges SDK effects; it does not prove those effects on a
vehicle. In particular, the current post-login replay supplies an explicit
failure for `GET_BUFFER 1027/0x99000002`; its success branch is not qualified by
that replay. Captured input hashes, executable hashes and results are under
`captures/telematics-20260925/awake-alpha/java-native-integration/`.

The worker receives the guardian's absolute uptime lease ceiling. STATUS can
observe that ceiling but cannot extend it; only an authenticated RENEW can do
so. This lets the worker expire its own permission even while the guardian is
blocked waiting for an IPC response. A separate fallback bounds cleanup after
an unexpected guardian watchdog failure. The guardian/app lifecycle is being
reviewed independently from the author of these changes.

This checkpoint does not unlock the native capabilities or `CLOUD_NATIVE_PILOT`.
The user requested stopping after component integration and review, before an
APK or vehicle acceptance. Original keepalive completion and final review
results are recorded below when verified; the earlier size measurements above
belong to their earlier executable, not to an unmeasured later build.

Scope boundary: this is a generic vehicle-command adapter, but not a generic
firmware adapter. The copied instructions depend on exact object layout,
constructor state, singleton ownership and callbacks of the pinned firmware.
Disk size understates that maintenance cost. The heartbeat investigation
exposed this directly: constructing a 201 packet is not proof of sending it;
the original heartbeat path can address a different sender from the one
initialized by discovery. A replay wrapper must not convert that discrepancy
into a successful send. Resolve the original binding with evidence, or leave
the path unavailable. Do not grow a new subsystem or fabricate an endpoint
assignment merely to finish the alpha.
