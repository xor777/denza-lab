# Custom SIM cloud runtime

This document fixes the application/runtime contract for the custom ICCID/IMSI
adapter. The current target is the explicitly bounded `awake-alpha-v1` profile:
an awake vehicle and a live Denza Apps foreground service. The implementation is
under qualification. It is **not** a claim that the finished runtime survives
QuickBoot, works over an ordinary SIM, or has passed live vehicle acceptance.
Build 61 enables `CLOUD_NATIVE_PILOT` for this bounded profile after offline
qualification; vehicle acceptance and distribution are separate steps.
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

A clean installation defaults to OFF with factory SIM selected. Custom identity
fields start empty; selecting CUSTOM never generates a pair. Generation is an
explicit action with confirmation, and its text does not promise that BYD accepts
every correctly formatted pair. Prefer the vehicle's original values when known.
Updates keep preferences and marker.
Data clearing/uninstall must delete the app-owned marker; an old guardian must
stop after confirmed marker loss or generation/install change. Unavailable
storage is an error requiring effects to stop, not proof that cleanup succeeded.
Android shell access, FUSE atomic publication and removal behavior still need
one controlled vehicle acceptance session.

UI order is mode, pair fields and then enable. Changing the SIM mode turns the
main switch off, stops the existing owner, confirms its absence and saves the
selected mode; only a later explicit main-switch ON starts it. A saved-pair
change keeps its serialized handoff behavior. A pending request never mutates
the running owner's pair. Repeated mode selections coalesce to the latest
requested selection and remain OFF, including when an earlier ON was queued or
the choice returns to the original mode during teardown. Selecting the already
settled mode again is a no-op.
Reconnect never regenerates the pair. Pending teardown precedes a fast OFF/ON. Failure to persist
the journal prevents START; failure to persist cleanup prevents clearing pending
OFF. Permanent registration/compatibility errors are not endless START retries.
Network retries remain transitional instead of a brief red error.

Factory-only installations do not deploy or probe the custom runtime on every
poll. Custom ownership is checked when a custom session or unresolved start is
known, particularly during handoff to factory mode. An explicit Activity launch
or launcher reopen can restore saved ON after confirmed cleanup, as requested
by the owner. Same-process configuration recreation, status refresh and network
callbacks are not restart requests. Saved OFF never starts CUSTOM. A new
permitted restart uses a fresh configuration generation; an expired or fenced
generation cannot enter an endless `config_changed` retry loop.

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

Native NET effects carry the original command number beside the opaque frame.
Only after a successful TLS write does Java return `SENT <command>`; the engine
checks its pending FIFO and invokes the original sender's completion callback.
It does not decode the payload. An ambiguous write receives no SENT and ends
that connection. This completion clears original registration bookkeeping and
allows the subsequent server reply to take its normal firmware path.

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
SDK. The checked local Python environment has `capstone==5.0.9`,
`unicorn==2.1.4`, `pyelftools==0.32` and `cryptography==50.0.0` (TLS tests).
Set `DENZA_CLOUD_PYTHON` to that environment's Python; setting `JAVA_HOME`
alone does not supply these Python modules. Tool paths are configurable;
qualification is not. Native pilot remains a
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
process's original sender state. Registration result zero is pending, not rejection:
the original alarm3 waits 70 seconds and then produces 200. Java continues bounded
TICK, power and ownership checks until that frame appears; it does not call D or
construct a discovery request after its own delay. Values at least two take the
rejection path. This distinction was already proved by the original-pair/changed-
pair/restored-pair vehicle experiment and must survive the runtime integration.

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

That checkpoint did not unlock native capabilities or `CLOUD_NATIVE_PILOT`.
The pause was recorded in commit `9cc0247e`. The owner subsequently requested a
practical awake alpha incorporating the external review, including a local APK
and basic vehicle validation. It does not include sleep or QuickBoot. The size
measurements above belong to their earlier executable, not to a later build.

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

The original binding is now identified: `0x36b78` obtains the global sender via
`0x36edc` and stores that same instance at `CloudControl+0x2b0`; R200 configures
its endpoint. The earlier detached setup created a different manual sender.
The Java bootstrap already owns the authenticated final TLS stream, so the
original sender's socket primitives must attach to that stream. A second TLS
connection would have no corresponding 220 login. The historical
`SECONDARY_CONNECT/WRITE` IPC names now refer to this attachment; they do not
create another cloud connection. Complete inbound envelopes use the existing
original decoder (`RX` to `0x573ec`), including 201 acknowledgements.

`LINK_STATE` is a synchronous notice from the original private TCP-state
setter. State zero ends the session before subsequent native effects; it never
writes a stock TCP property or acknowledges unimplemented cleanup. Java closes
its actual resources and a new session may reconnect with the same saved pair.

The external review's ADB replay finding was reproduced structurally: shell
dispatch and response reading used to share the host-fallback catch block.
Fallback is now limited to connection/authentication before shell OPEN. Lost
responses, partial dispatch and close errors cannot execute the command again
at another address. Focused `LocalAdbClientTest` cases cover pre-dispatch
fallback, lost response and close failure.

## Practical alpha review follow-up, 2026-09-25

The product defaults to FACTORY with the switch off. CUSTOM fields start empty;
generation and replacement are explicit, with a short confirmation. Reconnection
retains the saved pair. A selected configuration is applied only after confirmed
teardown; a newer selection supersedes one waiting for teardown. The owner
subsequently requested startup recovery: an explicit Activity launch may restore
a saved CUSTOM ON after old-owner cleanup. Saved OFF, including a mode change,
never starts a session. This does not make the foreground service sticky or
authorize a boot/network callback to revive it. The report switch remains off by default and owns one replaceable
Downloads file without SIM identities, keys or command payloads.

Explicit factory activation and handoff prove global CUSTOM-owner absence even
after app data was cleared. Ordinary factory polling uses the saved cleanup hint,
so the legacy path does not continually probe the custom backend. A terminal
guardian generation is not retried forever; an explicit new OFF/ON produces a new
generation. An ambiguous START response is fenced. A clean failure before child
creation remains retryable. The START response has a 40-second app deadline and
a 35-second bridge socket deadline; neither extends the guardian's 30-second
uptime lease. Other bridge operations keep their shorter deadlines.

Local boundary checks cover the real Java pipe against ARM64 instructions for
bootstrap, awake 536, sub5 and heartbeat ACK/timeout. Java tests also cover the
pending REG0 wait, STOP, queued power loss, suspend and missing continuation.
These checks use explicit synthetic SDK/TLS boundaries, not a live vehicle.
The app suite at the reviewed checkpoint passed 1,684 tests. Independent source
review closed the factory-owner gap; vehicle validation and the final APK identity
must be recorded separately before distribution.

## Offline follow-up after the first installed alpha attempt

The first build-61 vehicle attempt did **not** complete CUSTOM registration.
It stopped during REGISTERING with `native_unavailable` and no transmitted
cloud frame. A separate handshake-only probe verified the local certificate/
hardware identity and then failed with `SSLProtocolException`; its conditions
differed from the earlier successful experiment. That exception alone does not
establish the cause of the product's terminal failure. Do not report either probe
as a successful APK cloud session.

Three Android integration defects were reproduced and corrected: Android's
`LocalSocket.connect(address, timeout)` overload is unsupported; SDK context and
device initialization require the main Looper before worker threads use them;
and `debug.ro.serialno` may legitimately be empty. The same original serial
source is retained. Main-thread SDK initialization followed by background power
reads/subscription cleanup was confirmed on the car; socket connection and native
READY/CAPS also succeeded. Before the owner disconnected the car, FACTORY was
restored with TCP=1 and CUSTOM workers stopped. A lingering idle guardian was
terminated after checking that it had no cleanup debt.

The subsequent offline review fixes that guardian exit: a local abstract-socket
connection wakes blocked `accept()`, the wake peer is not served, and already
accepted requests finish flushing before the owner lock is released. Both a host
Unix-socket test and an Android emulator test cover this sequence; the latter
does not use the vehicle or its guardian socket.

The current TLS provider also passes seven Android-emulator mutual-TLS cases
against an independent loopback server with synthetic certificates: fresh and
repeated connections, selected-IP routing, wrong hostname/CA and bad signature.
The successful cases verify the client certificate and exchange opaque bytes;
untrusted peers are rejected before signing. Fourteen separate host tests cover
signer isolation. These tests make no factory-service or BYD-cloud calls and do
not settle the different vehicle handshake result.

Normal DNS replies containing AAAA or more than four addresses previously became
permanent native-protocol errors even when valid A records were present. The
resolver now keeps up to four distinct IPv4 answers in their original order.
No supported address is a transient network failure. This is a confirmed code
defect, not a confirmed explanation of the recorded car failure.

`CloudNativeEstablishIntegrationTest` covers the production connection's complete
registration/discovery/login/post-login/keepalive orchestration with the actual
ARM64 child. SDK, DNS and TLS responses are explicit fixtures; both REG1 and the
original REG0 timer continuation are tested, including an empty serial and a
mixed IPv6/IPv4 DNS answer. It complements the lower-level native IPC replays.
It does not exercise Android process launch, live SDK effects or mutual TLS.
The obsolete separate `CloudSecondaryTransport` implementation now exists only
in a `*Test.java` fixture and is excluded from the runtime JAR. Production uses
the single authenticated stream throughout.

The second app review closes the stopped-service UI dead end: a saved CUSTOM ON
without a live foreground service shows a stopped state, and a tile press requests
an explicit new session only after old-owner cleanup. A real explicit FGS launch
gets a bounded ten-second UI pending interval, not a permission to run cloud code.
It prevents a red flash before Android delivers `onStartCommand` and expires if
the service never arrives. A mode selection during a pending ON finishes OFF
after serial teardown; ON during that transition cannot start the new mode.
Renewal cancellation/replacement is
serialized in one small task slot so STATUS and RENEW cannot create two successor
loops for the same owner.

The final clean-context app review also found that the no-implicit-CUSTOM-start
guard blocked a pending OFF after process restart. Both service request and
`onStartCommand` now use the same existing start policy: pending teardown may
start a cleanup-only foreground service, while saved ON cannot resume itself.
The cleanup service retries transient failures and stops after confirmed teardown.

The final local APK is build 61 (`0.7.0-alpha.1`), SHA256
`876db962edb99e1f76e698ebea841a38894cfb444364252016f57348058ce9ce`.
It contains runtime `99299c55655f-9b874c2df22d`; component hashes and source bindings
were checked inside the signed APK. The final app suite passes 1,689 tests and
the shared ADB library passes 24, with no failures or skips. Detailed evidence is
`captures/telematics-20260925/awake-alpha/offline-review/alpha-candidate.json`.
At the end of that offline interval this candidate was not yet installed or
accepted. The subsequent on-car attempts are recorded below; it was not published.

Disposition of the external review and its UI follow-up:

| Topic | Alpha decision |
| --- | --- |
| Repeated CUSTOM probing in FACTORY | Removed from routine polling; retained at explicit handoff/activation and for real cleanup debt. |
| Lease fencing and startup | Terminal generation is not replayed; CUSTOM is non-sticky. The later owner request permits an explicit Activity launch to restore saved ON after cleanup; saved OFF stays OFF. |
| Ambiguous ADB dispatch / START timeout | No cross-host command replay after dispatch; START deadlines aligned; lost acknowledgement resolves ownership. |
| Mode and pair changes | Serialized teardown and save; latest requested configuration wins. Mode changes finish OFF and need a separate explicit ON. |
| Initial selection / generation | FACTORY + OFF; CUSTOM fields blank, explicit generation/replacement confirmation. No guarantee of server acceptance. |
| Panel order and text | Mode and pair precede the main switch per owner request. Wi-Fi sleep switch is FACTORY-only; manual pair entry keeps explicit Save. Boards updated. |
| Stop and factory return | Local OFF stops CUSTOM; explicit FACTORY ON restores stock only after confirmed cleanup. Server-side return still needs acceptance. |
| Original bootstrap/timers | Original NETSTATE, REG0 continuation and original sender completion retained; Java supplies transport and clocks. |
| Shared properties | No stock recovery-counter rollback; a no-op 0-over-0 obligation cannot overwrite a later foreign 1. |
| Architecture deletion proposals | Guardian, installation marker, cleanup journals and legacy STOP support retained because they enforce owner cleanup. Separate unused transport removed. No broad rewrite for alpha. |
| Sleep / QuickBoot / local SIM transport | Outside this alpha's proved scope; Wi-Fi success must not stand in for ordinary-SIM testing. |

No further vehicle operations were made during the owner's offline-work interval.
The local candidate remained pending owner acceptance, not forum distribution.

## Resumed vehicle acceptance, 2026-09-25

The owner reconnected the awake car. APK `876db962…` was installed over the
previous build, preserving FACTORY ON and the existing saved pair. Power getters
returned ACC=1 and MCU_STATE=1. Switching to CUSTOM completed stock teardown but
then reached REGISTERING → retry_wait without transmitting an application frame.
The test was stopped; FACTORY returned to actual TCP=1.

A bounded TLS-only probe reproduced the transport failure with the production
connector. Factory identity verification succeeded, but the TLS signature count
was zero. An instrumented copy narrowed this to Conscrypt's raw RSA operation:
the 256-byte encoded message used PSS, while our provider only admitted PKCS#1
SHA-256. It was a local format rejection before the chip, not a BYD registration
refusal and not evidence about the saved SIM pair. Supporting strict TLS PSS
encoding (MGF1 SHA-2, digest-sized salt, valid padding and trailer) resolved the
same on-car handshake: TLSv1.2, exactly one chip signature. Peer/hostname trust,
socket-bound signing budget and exact signature verification remain required.
Nineteen host signer tests cover accepted and malformed encodings. The probe
sent no application frame, so it does not prove the APK's full cloud session.

Successful guardian shutdown also needed an explicit process exit: SDK-created
threads could survive after cleanup, socket closure and owner-lock release.
The entry point now exits after successful return, retaining the failure halt.
A child-JVM regression leaves a non-daemon thread alive and verifies exit after
the control channel closes. Evidence for the resumed session is under
`captures/telematics-20260925/awake-alpha/resumed-acceptance/`.

The owner then requested a more conservative mode switch: selecting a different
SIM mode must finish OFF, without inheriting an earlier ON request. This replaces
the former stop/save/restart policy for mode changes. Repeated selection of the
already settled mode is a no-op; rapid changes during teardown keep OFF latched.
The next ON is accepted only after the transition completes. Build-61 candidate
`0e02187c…` confirmed FACTORY ON → CUSTOM OFF on the car, and CUSTOM OFF → FACTORY
OFF. A separate switch press started CUSTOM. The full app suite passes 1,692 tests.

The subsequent complete connection attempt still failed inside native NETSTATE4,
with the original wrapper's explicit `control_property_boundary` refusal before
cloud application-frame TX. Thus the TLS fix alone does not qualify this APK.
The bounded private diagnostic captures code locations and the fixed failure
stage only, never the native frame or exception message. That trace is retained
as `resumed-acceptance/diagnostic-native-stage.txt` for reproducing the native path.
After that attempt, explicit FACTORY ON restored actual TCP=1, with no remaining
custom processes or gate/property debt and the same boot ID. A passive observer
then recorded the original client's successful command-201 write and decrypted
201 acknowledgement (`reply_flag=1`), retained in
`resumed-acceptance/factory-keepalive-after-mode-test.json`.

The NETSTATE4 refusal was reproduced offline with a synthetic 17-character VIN.
The earlier fixture had 16 characters and skipped the original valid-VIN branch.
That branch reads `sys.vin_valid_record_time` and, for a zero value, calls `sysinfo`
to record boot elapsed time. The native bridge now forwards the read to the
existing session-local counter and provides uptime only at this original
`sysinfo` callsite. It does not write the stock counter. The replay separates
uptime and elapsed realtime: the old worker fails at the same property boundary,
the new worker completes both zero and nonzero counter cases. The full awake
replay and production Java establish replay with the ARM64 child also pass.

Build-61 APK `9e99e0fde7ea1bd7bb3ce7d20ec72346071b63893514c8ab66ab881d5fca5870`,
runtime `a2d9a45d95de-616996d552dc`, includes this fix. Its installed hash was
verified on the car while FACTORY remained TCP=1. A subsequent mode selection
again reached CUSTOM with `enabled=false` and no pending teardown; only the
separate ON press started the new owner. This confirms the UI policy, not yet
the full adapter's cloud exchange. The final host runtime suite passes all
sixteen groups. Evidence: `resumed-acceptance/real-vin-native-proof.json`,
`real-vin-production-result.json`, `valid-vin-installed.json`,
`valid-vin-mode-off.txt` and `final-runtime-host.json`.

The next APK attempt returned a START snapshot but the guardian disappeared
before the first usable session status; there was no fresh native failure
record. FACTORY was explicitly restored to TCP=1, with no custom process or
cleanup journal. This attempt is inconclusive for the new native path. Bounded
code-location diagnostics were added to unexpected guardian failure paths;
no raw worker output, identifiers or commands are recorded.

Independent review of mode switching found no automatic start of the new mode.
It did find that an unresolved STOP kept the configuration busy indicator active
during every retry delay. The controller now releases that busy claim between
attempts: pending teardown is visible then, while an ordinary in-flight STOP
does not flash a red error. Durable OFF and the mode-transition ON fence remain.
The app suite passes 1,693 tests with no failures or skips.

Final mode-policy candidate `5912d9afda1123465cc8dffbee8e654f0d4fbeebff636b2d06430dbd67132bd8`
was installed and its runtime/source bindings checked (`2baa6fb86003-616996d552dc`).
FACTORY ON → CUSTOM OFF was confirmed again before a separate ON. That ON
returned START/starting, then the guardian exited. The bounded diagnostic and
exact release-DEX mapping identify `java_worker_begin` while polling STATUS:
the first returned line was nonempty but was not the worker's private BEGIN
marker. This establishes an internal framing failure; the origin of that line
is not recorded. It is not proof of a cloud rejection or of the new native VIN
branch's on-car outcome. Explicit OFF followed by FACTORY ON restored stock
TCP=1 with no custom processes or cleanup debt and the original boot ID.
The temporary TLS-handshake probe JAR was removed from the car.

The subsequent passive stock observation also confirmed a real command-201
keepalive write and decoded 201 acknowledgement (`reply_flag=1`), not only
the local TCP flag: `resumed-acceptance/mode-final-factory-keepalive.json`.

The worker now separates protocol output from public stdout before SDK
initialization: a duplicated descriptor carries only the framed protocol, while
ordinary Java and direct fd1 output go to a sink. The strict guardian reader is
unchanged. A host child-process regression and an Android-emulator test inject
both kinds of incidental output after START and confirm STATUS framing remains
intact. The Android test invokes the production isolation method; temporary
emulator files were removed. Evidence: `resumed-acceptance/emulator-stdout-isolation/`.
This check does not establish which writer produced the original unexpected line.

The owner also requested correct app-start restoration. The application preserves
a saved ON wish across interrupted restart cleanup using one durable resume
intent; the old owner is stopped before ON is restored with a new generation.
An explicit OFF or mode selection cancels that intent. An existing foreground
service is left alone, and permanent registration/compatibility failures do not
become an app-open retry loop. The explicit launch is held until trusted local
ADB initialization finishes; ordinary recovery broadcasts do not create one.
Same-process Activity configuration recreation does not count as a new launch.

On-car APK `fb54dc4aae5d41a5b4825d188611597863f62fd37906077d3c0264a1383f3dfd`
(`3ce1fe3acf26-616996d552dc`) confirmed saved CUSTOM OFF survives force-stop and
explicit reopening with no custom process or cleanup journal. The subsequent
explicit ON survived the former worker-framing failure, completed the original
REG0 delay, and reached discovery and the final login-reply path. It then failed
at the real `SystemProperties.set` for `sys.cloud.remote_controling=0`, whose
observed value was already `0`. This is a local property-permission failure, not
a registration rejection; no successful complete session is claimed. The owner
was stopped through the app switch. Evidence: `startup-saved-off-proof.json`,
`startup-registration-window.json`, and `startup-register-failure.txt` under
`resumed-acceptance/`.

A fresh independent review also found an interrupted OFF/ON boot path:
`enabled=true,pendingDisable=true` could start a cleanup FGS which, after STOP,
recursively started CUSTOM. Implicit cleanup now durably saves OFF with the
explicit-app-open resume intent before launching that FGS. Failed persistence
prevents launch. The reviewer confirmed the closure and lock ordering; the full
app suite passes 1,701 tests. Boot was not exercised on the vehicle.

The exact post-login property operation now checks the real current value and
accepts only `sys.cloud.remote_controling=0` already observed as `0`. It performs
no shared write or new journal entry. Nonzero, empty, unknown or unreadable
values still fail; this is not an unconditional setter-success substitute.
The pinned `CloudReboot` source reads the value for reboot eligibility, and the
captured original caller has no result-dependent continuation. Independent
review found no required 0-to-0 write event in the available corpus; coverage
does not include every partition consumer. All sixteen host runtime groups pass.

After this first successful custom registration, simple FACTORY ON did not
restore cloud login. The 180-second passive observation retained decoded 220
responses with `reply_flag=2` and no accepted 201, while stock TCP remained zero.
This makes the documented distinction between local STOP and server registration
restoration material on this car. It is not a Wi-Fi failure. See
`startup-register-factory-keepalive.json`; restoration is checked separately.

The owner subsequently narrowed this selected-tester alpha: repeated mode
handoffs and automatic factory re-registration are not release gates for that
trial. The immediate question is whether CUSTOM establishes a usable connection
on replacement-SIM vehicles. Keep local OFF/worker cleanup, firmware/power checks,
the saved identity, TLS validation and opt-in redacted reporting intact. The known
factory-return limitation must remain explicit; this scope change is not evidence
that it is fixed. Sleep/QuickBoot and broad field qualification remain excluded.

The next installed APK (`9648f032…`, runtime `84db3e10d252-616996d552dc`) passed
the idempotent-property point but lost its native output during R220. The
corresponding host replay was reproduced by changing only the synthetic fixture's
`persist.sys.record_610_upload` from `0` to the car's observed `1`.
Changing only `persist.sys.cloud.token_flag` to the car's `1` still passes.
This isolates the 610 continuation as an unqualified branch; it is not a reason
to alter the vehicle flag. `record610-only.log`, `token-only-result.json` and
`current-postlogin-flags.json` retain the regression evidence. The native worker
must handle this original branch before this APK can claim a working session.

The red replay identifies `timer_guard_boundary`, with the original caller at
`0x5648c` acquiring the static guard reached via GOT `0x8ed48` (BSS `0x916c8`).
When the 610 record is already current, `0x56094..0x560ac` initializes the
original CMD531 handler and invokes `request_sre_config` at `0x39178`.
Its constructor and timer continuation have not been qualified in this runtime;
do not remove the guard or change the car's record flag to hide this gap.
`record610-red.log` retains the exact local failure. The native follow-up agent
was stopped by an automatic safety check and produced no confirmed C fix.
The field-test APK is therefore not yet qualified for a successful connection.

### CMD531 continuation and executable-page correction (2026-09-25)

The local follow-up completed the original CMD531 constructor, guard, request,
JSON parser and five-second retry/response chain. A separate timer slot keeps
this continuation independent of the registration alarm and heartbeat. The
adapter forwards opaque frames; Java does not interpret configuration fields or
vehicle commands. The parser's standard `pow` dependency forwards raw IEEE-754
values to `StrictMath.pow`, with strict argument validation.

The original configuration publication calls `persist.sys.edge.enable.sre` but
ignores the setter result. The adapter explicitly refuses this optional shared
write with `-1`; it neither attempts the Android setter nor claims success.
Other properties and invalid values remain rejected. Original continuation with
this refusal is covered by replay, avoiding new persistent state to clean up.

Enforcing real `mprotect` permissions in the emulator exposed a missing executable
page for the secondary-write trampoline at `0x82b98`; page `0x82000` is now mapped
executable. A separate regression found that any 432-byte allocation was mistaken
for the sender singleton. The singleton now requires both that size and the exact
original caller at `0x36ef4`; JSON allocations remain ordinary arena allocations.
String lengths 430, 431 and 432, malformed replies, independent heartbeat/config
timers, retries and cancellation pass with actual memory permissions enforced.

Selected original text grew by 6,624 bytes, from 110,400 to 117,024 bytes; the
embedded image remains 622,592 bytes. There is no new command-specific vehicle
implementation. Scoped independent review found the shared-write, executable-page
and allocator issues above; all three were corrected and rechecked.

Candidate APK SHA-256:
`84d4c45659c0e5fe6f3cffab8ac158cbb81b49d64f2a03cf7794f1be7444e8fc`,
runtime `367771093ebe-14f31731b046`. The full app suite passes 1,701 tests;
all sixteen Java runtime test groups, the full awake native replay, CMD531
regression and production Java establishment with a real ARM64 worker and
`record_610_upload=1` pass locally. Evidence under `resumed-acceptance/`:
`config-timer-proof.json`, `config-timer-establish.json`,
`config-timer-awake-proof.json`, `config-timer-host-runtime.json`, and
`config-timer-app-build.log`. These local results alone do not establish the
on-car session or ordinary-SIM transport.

The installed `84d4c456…` candidate still lost its native output during R220;
it did not establish a lasting session. A synthetic complete stdin transcript
then matched the emulator output byte-for-byte both on Android ARM64 and the car,
without SDK calls or cloud access. All captured nonprivate post-login flags
together also pass offline. This narrowed the discrepancy to live input/state.

Diagnostic candidate `40307bca…` (`c83d35b0ffaa-14f31731b046`) recorded raw child
exit 133 immediately after reading `persist.sys.gpsinfo`. A synthetic property
with the same format, but every digit replaced, reproduced the failure at
`0x89050`, LR `0x71fbc`: the original `strsep` PLT was still a trap. The adjacent
`atof` import and original coordinate conversion helper `0x725b4..0x72710`
also need completing. Empty-GPS fixtures had skipped this branch. No actual
coordinates were saved or printed. Both live attempts were stopped through the
app switch, with `enabled=false`, `pendingDisable=false` and no custom processes.
The car retained its boot ID and Wi-Fi; stock TCP remains zero, as before this run.

The GPS path now calls bounded generic `strsep` and C-locale `atof` interfaces,
executes the original coordinate helper, and reads the original `persist.gnss.pga`
property (empty on this car). No new GPS field parser or packet builder was added.
`C_ATOF` returns raw IEEE-754 bits; it supports numeric prefixes, decimal and hex
exponents, Inf/NaN and signed underflow. It does not model libc errno/fenv or NaN
payload bits. All sixteen host groups pass. The new `verify_gps_native.py` covers
empty, partial, nonnumeric, positive, negative and exponent-form synthetic GPS;
the original coordinate helper executes twice for nonempty cases. Production
establishment with the nonempty fixture, the previous configuration regression,
and the complete awake native replay pass with worker `11c297821c06…`.
Evidence: `nonempty-gps-red.log`, `nonempty-gps-green.json`, `gps-native-proof.json`,
`gps-config-proof.json` and `gps-awake-proof.json` under `resumed-acceptance/`.
The follow-up review agent identified the missing dependencies but its final
turn was stopped by an automatic check; that final review is not counted as complete.

GPS candidate `964cf5b9…` (`a4eb3c55a54f-11c297821c06`) passed the live GPS
continuation, then stopped after `persist.sys.mcu_version`. Populated synthetic
version properties reproduced the next missing libc boundary: `__strncpy_chk2`
at `0x88c70`, LR `0x56828`. Its bounded ABI implementation now enforces both
destination and source bounds. The original continuation also reads the SDK
version buffer `1027/0x99000402`; that read is now admitted without interpreting
its bytes. Production replay passes with both an explicit SDK error and a
synthetic successful version buffer. The current local candidate is
`60f63418e9a50813df9762dfc67b5b191b4db0f7bd109048cfda7a489f5f44f4`,
runtime `38e975b70ed0-7a2a77b948c0`; it has not been installed on the car.

The owner requested aggregate qualification before further installs. The new
`verify_postlogin_matrix.py` varies properties and SDK buffer results before
going back to the vehicle. On worker `7a2a77b948c0…`, its 76 native-only cases
produced 67 completed logins, five boundary cases and four traps. This is an
inventory, not a green release gate: unknown synthetic reads are explicitly
reported and are not counted as passes. Findings include:

- A changed/absent saved head-unit version enters original `0x4e9d8`, which is
  not selected. Inspection shows it resets eight shared report/state properties;
  do not add that function while silently acknowledging these writes.
- First upload of the capability list (`cloud_fid_uploaded` absent/zero) needs
  five additional SDK integer reads: `1009/0x47002011`, `1009/0x47002012`,
  `1023/0x2940002a`, `1023/0x4540000c`, `1025/0x4f401038`.
- `cloudtest=1`, repair-mode flags and explicit stock cloud disable select
  unsupported paths; they require deliberate preflight handling or complete
  original-path qualification, not fabricated success.

Evidence: `postlogin-matrix.json`, `postlogin-matrix.log`,
`selected-direct-dependencies.json`, `nonempty-versions-red.log`,
`nonempty-versions-green.json`, `versions-buffer-success-result.json`.
The raw direct-dependency inventory includes dormant error/exception paths;
it is not a list of proven live defects. An exploratory CFG dump
`postlogin-cfg-audit.json` is not qualification evidence (conditional-branch
immediate parsing needs correction). No further car install was made after the
owner's request. The installed GPS candidate was stopped through its switch;
the vehicle remains CUSTOM OFF. Aggregate Java/SDK qualification and closing
the supported startup-state gaps remain before the next live attempt.

### Complete original code and explicit runtime boundaries (2026-09-25)

The runtime builder now preserves the complete `.text` of the pinned firmware
(338,892 bytes), rather than copying hand-maintained fragments. The embedded
image is still 622,592 bytes. This does not invoke `main`, the full service
constructor, or ELF initialization arrays. Every PLT entry is still a refusal
unless `persistent_runtime.c` provides that interface; firmware identity,
read-only executable pages and process syscall restrictions remain enforced.
The complete text contains no `svc`, `hvc`, or `smc`. Static review found all
5,406 direct branches leaving it target the masked PLT. Indirect calls still
require valid original objects; including code alone does not establish that.

The DATA consumers now use their original BSS objects and lazy constructors.
The CMD709 object constructed during START uses the same guard, object pointer,
and constructor/atexit/release sequence as its later callback path. Removed the
fake initialized guards, empty virtual callback and unconditional false filter
that previously suppressed two DATA consumers. Standard NDK string operations
now have a bounded ABI bridge, including ownership and overlapping arguments;
`memmove` handles overlap correctly. These operations contain no vehicle-field
or cloud-command interpretation.

A separate local empty SRE sink remains intentional: it has no stock event-bus
publisher, and publication is unsupported. It is distinct from the real SDK
subscription, whose opaque table is now forwarded and acknowledged only after
the actual SDK call returns success. Reading startup cache files uses bounded
read-only host I/O with real error results. Original optional cache writes and
removal are refused; they are never acknowledged as successful stock changes.
The 301/415/505/610 and 542/552 responses and their original continuations are
available, including the original GPS/configuration timers. Registration and
report flags owned by the isolated client stay private to that client.

The builder imports no replay/test modules. Production source hashing and
Gradle inputs exclude fixtures and verification scripts. Local replay is
supporting evidence only: `complete-code-data-result.json` exercises production
Java with original ARM64 startup and explicit synthetic DATA inputs; it does
not establish live telemetry, command success, sleep, or ordinary-SIM transport.
The independently reviewed candidate is `a76ef68f84e6-2a8ff17527e5`, APK
SHA-256 `3982898abe0075486e13b258633efa9554e2482846208bfbd654bdc6cf96b352`.
Live outcome is recorded separately below; this paragraph is not a release gate.


The following live attempt exposed a packaging mismatch: desktop JDK compilation
had accepted `Path.of`, which is absent from the car's API 33. Production now
uses the real Android-33 boot classpath (Java 8 syntax plus SDK lambda compiler
stubs); `Paths.get` is used and a linkage failure terminates the owner once.
The native getter at `0x48698` is no longer replaced by a three-key shortcut:
all 17 original call sites use the original default-format/property-get/atoi
chain. Physical reads accept bounded property names; this grants no writes.

Property setters now return their native 0/-1 status. Unsupported optional
shared publications receive -1 and the original caller determines whether to
continue. Client-owned report latches and the string `cloud_412_data` stay in
this connection's namespace. An unpublished private property is absent (empty),
so the original caller applies its fallback; it does not cause a protocol fault.
No stock recovery counter, TCP state, privacy or authentication flag is changed.
Additional dedicated replies 316/421/499/599 execute their original handlers;
316 requires at least 20 body bytes. Its status-2 onboarding branches that
invoke privacy/sales broadcasts remain unsupported; the adapter does not send
those broadcasts. Queue checks prevent processing a response while a matching
write is unfinished; they do not establish that an unsolicited response has a
prior matching request. Original handlers retain their correlation decisions.

The live DATA stream reached `0x54320`, previously replaced by an unconditional
refusal. Full inspection shows it reads BODYWORK/`0x12d0002a`, updates its own
object, and optionally accesses the already isolated `tcp_512.dat` cache on zero.
It now executes unchanged. The awake lifetime guard still reads POWER/MCU;
BODYWORK is not used as a substitute. A passive 10-second capture on 2026-09-25
recorded 556 admitted opaque callbacks, with no drops or callback errors and
confirmed unsubscribe. All 556 pass through production Java and the original
ARM64 code in offline replay. The callbacks are real; bootstrap responses,
identity and network in that replay remain synthetic. Private payloads are only
in ignored `resumed-acceptance/live-data-private.log` and
`live-data-replay-private.json`; do not publish these files.

The candidate for live verification is runtime `84a12833150b-6352c5fb9020`, APK
SHA-256 `d659897666470955d68675499e0c52d34e68b5310ad1eb8c4b696ed7dc977467`.
See `resumed-acceptance/live-data-candidate.json` and `live-data-replay-result.json`.
Its live result is recorded below; local replay is not ordinary-SIM qualification.


Live continuation fixes (2026-09-25): the original `0x4dfac` config parser
allocates `140 * count` bytes with an unsigned-byte count (up to 35,700), plus
an index array and growing vectors. A frame-sized 4 KiB allocator/`memset`
limit was incorrect. The worker now has a fixed 1 MiB arena and a 64 KiB
individual allocation/memory-operation limit. Both scalar and array `new(0)`
return a distinct live block. Allocation exhaustion still terminates the
isolated worker; it does not grow memory without a limit. The exact allocator's
host check covers the maximum record/index/vector shape under ASan/UBSan.

Generic string imports now distinguish `strlen` from `__strlen_chk`; the latter
retains the compiler's actual object bound. The original variadic formatter is
`__vsprintf_chk`, not `snprintf`: its bridge supports the original formatting,
checked destination size, and bounded unknown-size heap destinations. No
vehicle field or command is interpreted in these libc bridges. Config nested
decode returning to `0x4e218` needs at least 27 bytes; the outer 542/552 handler
still needs only 23. Original optional `fopen("ab+")` receives a real negative
read-only-policy result instead of a wire-shape error. Independent static audit
of the normal config/DATA direct dependency closure found no further missing
normal PLT import; that is not live qualification.


The config parser also re-decodes stored frames internally. Those calls must
not increment the outer RX count, overwrite its command, or repeat response
queue bookkeeping. Only the top-level decode at return address `0x57560` is a
received frame; nested success at `0x4e218` validates its own bound and returns
to original code. Counting both had stopped the live session with
`native_decode_rejected` immediately after successfully processing configuration.
