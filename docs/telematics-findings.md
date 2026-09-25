# Telematics findings — companion app, cloud, and vehicle clients

Why the phone app shows the car as offline. Phone access to the cloud and
vehicle telemetry ingestion must be established separately. The head unit
itself runs several cloud clients; ownership of the phone's main status feed
by a separate T-Box has not been established. Open 2026-09-22.

## Custom adapter implementation contract, 2026-09-25

The current application/runtime boundary is specified in
[cloud-custom-runtime-contract.md](cloud-custom-runtime-contract.md). The
current controlled alpha uses management protocol 3 with a foreground-service
permission renewed every five seconds and expiring after 30 seconds. Native IPC
remains 2. Installation generations, ATTACH and local OFF remain independent of
factory registration. The package builder binds versions, hashes and the explicit
`awake-alpha-v1` capabilities. Build 61 enables the awake pilot after offline
native and Java integration checks. The full parked product remains unqualified;
sleep, QuickBoot and ordinary-SIM internet have not passed vehicle acceptance.

The offline candidate uses worker SHA256
`9b874c2df22d1248c226c7ff760ee6c85f100a8fb03d3bae0c23582025351cf3`
and Java component SHA256
`99299c55655f8cf158fd8b76fa9254ad8be4e42242426b5836dbc37bc6ae0588`.
Nine Java/ARM64 replays pass against this exact worker: bootstrap, registration
status0 continuation, both paths with the real positive SDK-buffer shape,
empty observed debug serial, awake536, sub5, heartbeat ACK and timeout. The original sender is shared with
CloudControl; successful opaque writes notify its original send-complete
callback. REG0 schedules the original 70-second continuation rather than being
treated as a rejection. Native snapshots and final replays are retained under
`captures/telematics-20260925/awake-alpha/`. These checks are offline evidence,
not proof of an installed APK's cloud exchange or vehicle commands.

The first installed build-61 attempt did not complete CUSTOM registration: it
stopped during REGISTERING with `native_unavailable` before cloud frame TX.
Android integration fixes cover the supported local-socket connect API,
main-thread SDK initialization and a legitimately empty debug serial. Following
the owner's request to disconnect, FACTORY was returned to TCP=1, workers stopped,
and the remaining idle guardian was terminated after its cleanup debt was checked.
No further vehicle calls were made during that offline-work interval.

After the owner reconnected the car, the offline candidate was installed. Its
CUSTOM attempt still failed before application-frame TX. A production TLS-only
probe isolated a local incompatibility: Conscrypt supplied RSA-PSS to the opaque
key provider, which admitted only PKCS#1. Strict TLS PSS encoding support fixed
the on-car handshake (TLSv1.2, one chip signature, no application frames sent).
This is not a registration rejection or proof of a SIM restriction. The APK's
end-to-end acceptance remains separate; see the resumed acceptance section of
the runtime contract and `awake-alpha/resumed-acceptance/` evidence.

Further offline review corrected guardian accept-loop exit and DNS responses
containing mixed IPv6/IPv4 or many A records; the latter had incorrectly caused
a permanent native failure. The cause of the recorded vehicle failure is still
unproved. The obsolete separate TLS transport is now test-only. In addition to
the nine native IPC replays, two tests execute production `establish()` with the
real ARM64 child through REG1 and REG0, post-login and keepalive, using explicit
synthetic SDK/DNS/TLS boundaries. Sixteen host groups, fourteen signer-isolation
tests, seven Android-emulator mutual-TLS cases and an Android abstract-socket
STOP/exit test pass. Evidence is under `awake-alpha/final-native-integration/`
and `awake-alpha/offline-review/` within the capture directory above. These checks
do not establish vehicle command delivery or make the APK ready for distribution.

### Awake-alpha power preflight, 2026-09-25

Through the existing shell-UID ADB connection, six reads returned ACC=1 from
POWER device1005/FID`0x99000037` and MCU=1 from FID`0x99000003`. A bounded
`CloudPowerReadProbe` registered the exact two FIDs with the stock power SDK and
unregistered successfully. It received zero events while the state remained
unchanged: getter access and registration are proven, but delivery of an actual
power transition is not. The production guard must retain fresh polling.

The helper performed no setters, cloud calls or power transitions. Before and
after, boot ID and stock cloudmanager PID112 were unchanged, the stock Binder
reported TCP=1, and `sys.tcp_step` was6. Its temporary remote JAR was removed.
Evidence: `captures/telematics-20260925/awake-alpha/power-preflight/`, including
source/JAR hashes, bounded subscription output and before/after snapshots.

After resuming work, the same read-only connection again returned ACC=1, MCU=1
and stock TCP=1. The original network initialization reads device1027,
FID`0x9900021a` through `autoservice` transaction13. This returned SDK status0
and a complete 17-byte alphanumeric VIN; its leading byte satisfied the
original nonzero/non-`'0'` check. Identifying bytes were discarded, not written
to the evidence file (`power-preflight/resumed-readonly.json`).

The offline error fixture had instead taken the original fallback at
`0x4a170`: when `0x5c5f4` returns nonzero, it calls
`SET_INT(1027,0xaa000026,0)`. The matching SDK names `0x9900021a`
`BODYWORK_REAL_AUTO_VIN` and `0xaa000026` `BODYWORK_AUTO_VIN_SET`; its Java
`queryAutoVIN()` is a different device/value call and does not by itself prove
the native fallback's effects. No setter was issued in this investigation.
The error branch remains denied; a valid synthetic VIN must exercise the
normal original validation path independently of the error fixture.

A later read-only snapshot at 07:18 UTC measured the eight post-login integer
getters and eighteen property inputs used by the focused native replay. All
eight getters returned SDK status0; seven were nonzero (six ones and one two),
so the earlier all-zero fixture did not represent this car. Several feature and
record properties also differed from empty values. `ro.build.car.series` was
`denza`. These measured values are inputs for additional offline branch tests,
not proof that their ensuing setters or cloud effects are supported. ACC/MCU
remained1, stock TCP remained1, and boot ID/cloudmanager PID did not change.
Evidence: `awake-alpha/power-preflight/post-login-getters.json` under the same
capture directory. No vehicle setter or cloud request was issued.

## Owner-car network incident and recovery after reboot, 2026-09-24

The owner requested a live check because cloud recovery had stalled, then
confirmed that local-LAN ADB was also unreachable and that they rebooted the
head unit. This investigation performed read-only queries through the existing
`127.0.0.1:5555` transport; it did not toggle Wi-Fi, send ready/gone, restart a
service or install an APK.

**The first investigator read already showed TCP=1 and step=6.** The retained
app report records recovery at **16:41:06 UTC / 19:41:06 Moscow** after an
automatic ready at 16:41:00. Rechecks at 16:46 and 16:47 UTC remained connected.
Live uptime places this boot at approximately 16:38:34 UTC; boot reasons are
`KPDPWR` / `reboot,longkey`, consistent with the owner's account. The repeated
cloudmanager PID 112 across the report is **not** proof that the native process
survived: that PID was reused after the reboot.

The preserved report shows several different events, not one uninterrupted
failure: a 15:34 network loss recovered at 15:35; a 15:57 TCP reconnect completed
within seconds; at 16:20:51 TCP was down again, and ready/off/on attempts through
16:35 did not restore it. During these later attempts DNS addresses were
returned and native `registered_state=1` was logged, but those facts do not prove
a fresh successful server registration or a working data socket. There is no
retained registration-rejection response in this episode. Combined cloud and
LAN-ADB loss is consistent with a broader vehicle network problem, but neither
its initiating cause nor the specific failing component is established by this
bounded report.

A separate transport defect is visible: the app records **one** AnnounceReady
at 16:35:27.578 and a SocketTimeoutException at 16:35:35.231, while native logs
record **three** ready notifications at 16:35:27.712, 16:35:30.229 and
16:35:32.735. `LocalAdbClient.shell(command, timeout)` catches an IOException
around the entire connect-and-execute operation and tries the next address of
the same head unit; its default read timeout is 2500 ms. Thus a command already
delivered can be executed again when its reply is delayed. This matches the
observed repeated Binder notifications. It happened after the original loss,
so it is not evidence that replay initiated the network incident. Future
hardening should separate pre-dispatch host fallback from an unknown command
outcome and avoid replaying state-changing commands. No product fix or live
reproduction of this failure was performed during the read-only investigation.

The installed APK was original build 60, SHA-256
`e24af64de68ff55ca278f2ef8de42790cec70a3b71bc1a32a62a9a1eed709f98`;
it was not the later same-version transient-red-status hotfix. That UI-only
distinction is not a demonstrated cause of this network incident.
Evidence: `captures/telematics-20260924/cloud-reconnect-incident/`.

## Identity versus network: host-origin registration accepted, 2026-09-24

The owner requested a test separating the car's cloud identity from its path
to the internet. The bounded probe now supports a host-origin TCP socket while
still reading this car's real VIN, IMSI, ICCID and cloud parameters, and using
its factory signing service over the existing ADB connection. It changes
neither the SIM identifiers nor the modem/profile. This tests route independence
with constant identity; it does not test altered identity with a constant route.

**Live success at 14:55:07–14:55:10 UTC (17:55 Moscow).** One command **211**
at `dilinkreg-cn.denzacloud.com:6001` returned **registration body status=1**.
The response passed CRC, VIN and UUID checks. Its separate header `reply_flag`
was 2; the accepted registration status is the body byte, not that header field.
Factory mTLS and the one stock signature were verified; the helper sent one
101-byte application frame. No 220 login, 507 token or 512 status was sent.
The actual TCP peer was `139.159.228.68`; the Mac's route before and after the
exchange used **utun4**. No public-egress country was measured.

Before and after: stock TCP=1, cloudmanager PID **112**, safekeyservice PID
**603**, `double_apn`, Wi-Fi sleep retention=1, both APN1/APN3 disconnected,
factory SIM operator **46013**, SIM LOADED, SELinux Enforcing, and ADB ready.
An in-memory comparison confirmed unchanged IMSI/ICCID. No identities, keys or
UUID were stored in the artifacts. These endpoint snapshots do not exclude a
brief unobserved stock reconnect. The native helper exited with code 0, stderr
was empty and no native probe process remained.

An earlier preflight in this run stopped before any crypto/cloud call because
ADB and the local tunnel were absent. The owner restored the connection; only
then did the single real registration run. Four local host-transport cases and
eleven local TLS/application cases passed. Plan, source/binary hashes, test
output, live response metadata and before/after checks are retained in
`captures/telematics-20260924/identity-network-separation/`.

This proves acceptance of this identity through the tested Mac route, without
a working factory cellular bearer. It does not prove that registration is needed
only once: the matching 220 login body also contains an IMSI digest. Nor does
it prove native
cloudmanager adopts the helper's state, operation with a replacement SIM inside
the car, or persistence across sleep/reboot. Earlier Wi-Fi uploads already
established that a working factory cellular bearer is unnecessary for that
tested flow; they retained the real factory SIM identity.

## Owner's adapter boundary, clarified 2026-09-24

The owner explicitly excludes implementing our own vehicle-data structures and
protocol codecs. This is **not limited to identity injection or the original
process**: integration at an existing data/message boundary, forwarding opaque
buffers, or calling stock construction/parsing routines is within scope.
Vehicle field mapping, report/command formats and command validation/execution
must remain implemented by the firmware. Our adapter may manage the bridge,
identity source and transport without duplicating those schemas. Isolated
native-code reuse is a candidate if it satisfies that boundary end to end,
not merely because one packet-building routine can execute.

Consequently, the earlier suggestion to implement a separate response to request
511 ourselves is withdrawn. The observed request is useful for validating a
round trip through the stock receiver and stock response builder after
adaptation. Our code must not construct its vehicle status body, duplicate its
field mapping, or implement a replacement command executor. Existing independent
upload tools remain historical research only.

The next integration search covers identity input, opaque vehicle-data
callbacks, completed cloud-message buffers, stock codec/dispatcher calls and
transport callbacks. Every candidate needs a callable/deployable boundary plus
correct context ownership, buffer lifetime, session state and original checks;
an internal function address alone is insufficient. A raw encrypted TCP relay
does not change the SIM identity inside TLS, and a separate authenticated
socket cannot simply be handed to the native client. Preserve the functioning
stock session and Wi-Fi while testing concrete candidates.

### Concrete data boundaries inspected after this clarification

All addresses refer to archived cloudmanager `9e36cdbf…82eb9`. These are static
internal boundaries, not newly available Binder methods or live relay results.

| Boundary | What the original code already supplies | Remaining integration question |
| --- | --- | --- |
| Raw YUN callback → native data ingest | The existing shell probe receives `0x99000021` buffers. Native dispatch `0x68934` calls `0x627d4`, which updates the stock CAN cache; `0x55350` builds reports from that cache. | Pass buffers unchanged into a properly initialized native consumer, preserving its lifetime/subscriptions, instead of extracting fields in our code. Access to the callback is established; access to the running daemon's cache is not. |
| Native report body → stock packet codec | `0x6f520` passes its input buffer to `0x6e3b0` and then `0x6e858`, which handles the native framing/checksum/encryption path. | Reuse the complete stock producer/codec/context; do not replace only the final encoder while rebuilding the body ourselves. |
| Completed outgoing packet → send queue | At `0x74634–0x74640`, the sender passes the packet pointer in x3 and length in w4 to `0x74abc`; the latter creates a queued message via `0x75bdc`. | Establish a callable boundary and ownership for the same native session, send completion and reconnects. No external export of these buffers has been found. |
| Incoming complete frame → native dispatcher | Callback `0x57058` invokes `0x573ec` with the buffer/length; native dispatch retains decoding, state and command guards. | Keep the original receiver/session context and the full checked entry point; do not relay directly to post-check actuator branches. No generic external forwarding entry is established. |
| SSL plaintext I/O → transport | Wrappers `0x7f760` / `0x7f81c` pass buffer/length unchanged to `SSL_read` / `SSL_write` and return its result, logging errors. | Candidate byte-transport seam only. Interposition/deployment, TLS state and higher-level reconnect ownership remain unproved; a transport swap alone does not replace identity. |

Selected disassembly and the direct-call index are retained in
`captures/telematics-20260924/downlink-routing/`. This follow-up made no vehicle
or cloud calls and added no product or telemetry-codec implementation.

### Opaque-buffer execution verified on the car, 2026-09-24

After the owner said to check this approach, the existing bounded YUN probe
gained an explicit `--opaque` output mode. It preserves the SDK callback buffer
byte-for-byte instead of reconstructing its header from decoded log fields.
A ten-second passive run retained **979** allowlisted buffers, with zero dropped
callbacks/errors, successful unregister and remote-JAR removal. It did not
change the collection table, Wi-Fi, SIM properties or cloud session.

Those same buffers were replayed unchanged through original ARM64 functions:
`0x61cf0` initializes the native table/cache, `0x627d4` ingests each buffer,
`0x55350` builds the report and `0x6f520` selects its native packet variant.
Capture occurs at the original serializer boundary; no Python/C vehicle-field
mapping is used. The original builder produces a **104-byte** status body and,
given an opaque synthetic correlation token, its **120-byte** reply variant.
No such body was sent to the cloud. This does not establish complete/fresh
coverage of every field or correct charging semantics in the partial context.

**Nine checks passed offline, on the ARM64 emulator, and on the owner car:**

1. An empty native cache suppresses the send attempt.
2. Null and all input lengths 0–17 leave the isolated context unchanged.
3. The native mutex-busy path drops input without changing the context.
4. Unmodified callback replay reaches the original report builder.
5. Overwriting the source buffer after each ingest does not corrupt the report.
6. A new isolated context and the same replay produce the same native bytes.
7. The native reply builder preserves the opaque correlation token.
8. Original message constructor `0x75bdc` owns a deep copy of 1/18/128-byte
   opaque inputs; source reuse does not change the queued content.
9. Original SSL wrappers preserve pointer/length and full, partial, zero and
   negative I/O results. SSL itself is mocked, so this is not a TLS test.

The on-car run started **17:25:15 UTC**, exited 0 with empty stderr under shell
UID 2000 / SELinux Enforcing. The 707,456-byte executable hashes to
`13f15345430e2755db1f52f46802da2c32877def1e7aabd28e49b62b636b0e4c`.
Before/after reads match: stock TCP=1, native PIDs `112 223 530`, same boot,
`double_apn`, Wi-Fi retention=1, and the same installed Denza Apps APK hash
`ad724b60…9c365f6`. The temporary executable was removed and absence checked.
The snapshot's `sys.cloud_step` query was a wrong property name and returned
empty; it is not evidence of the real `sys.tcp_step` value.

The local runtime permits only stdout/stderr writes and exit after setup;
socket/ioctl/openat/clone probes all receive EPERM. CPU/wall limits are 2/5
seconds, unused native code is replaced by traps and pages are never RWX.
**Its context is deliberately partial:** allocator/RefBase/locks are stubbed,
the subscription write is captured without dispatch, other consumers are
absent/no-op, the packet singleton is supplied, and framing, sends and update
notifications are captured rather than executed. The unavailable charging
override fixture is `0xff`; no substitute signal value is uploaded. The full
service constructor, Binder registration, timers, TLS, incoming dispatcher and
live reconnection are not covered. Queue-copy proof is not destructor/refcount
or concurrent-lifetime proof. Expected on-device bytes come from offline
execution of the original routine, not an independent protocol implementation.

**Consequence:** a wrapper can demonstrably hand unchanged vehicle buffers to
the original report code on this hardware. The next missing integration is the
complete native context and original receive/session lifecycle. This is stronger
than an address-only hypothesis, but does not yet provide a custom-SIM toggle or
a bidirectional production adapter.

Reproducers: [verify_opaque_native.py](../research/telematics-firmware/verify_opaque_native.py),
[build_opaque_runtime.py](../research/telematics-firmware/build_opaque_runtime.py),
[opaque_runtime_probe.c](../research/telematics-firmware/opaque_runtime_probe.c).
Plan, private callback fixtures, source snapshots, hashes, offline/emulator/car
results and cleanup are in `captures/telematics-20260924/opaque-native-adapter/`.
No product implementation, APK installation, release or Git commit was made.

#### Live SDK-to-native pipe on the car

The next bounded run connected the passive Java producer directly to the native
consumer with a **pipe entirely inside the car**. In ten seconds, **975** intact
SDK buffers / **17,550 bytes** reached the original ingest routine, and the
original builder produced one 104-byte body at stream completion. The Mac only
launched the command and read the result; callback transport and processing
occurred locally. The bridge parses its own bounded text/hex IPC envelope, not
vehicle fields or cloud-message structures. The current stream's raw bytes were
not exported to the Mac or uploaded.

Before that live run, the updated binary passed the nine default local cases
again on the emulator and car. Emulator stream checks accepted the complete
979-buffer recorded input and rejected an empty input, a missing producer
completion/footer and an oversized declared callback. Successful stream exit
requires both `UNREGISTERED` and a clean `DONE` from the producer; a truncated
pipe cannot silently count as a completed capture.

The stream executable SHA-256 is
`0dce150cb2f48737a917a8767ffe59f6e50ddf059973da418e48c0e4fcacfd16`.
Stream mode adds seccomp permission to read stdin only, with at most 10,000
buffers, 2 MB of IPC input, 512 bytes per line, 2 CPU seconds and 25 wall seconds.
All other read descriptors, network/Binder/file-open/thread calls remain
forbidden. Native transmission is still captured rather than executed.
The producer stopped after ten seconds and unregistered. The result exited 0,
stderr was empty, and both remote files were removed with absence verified.
Before/after stock state and APK hash matched; the **correct** `sys.tcp_step`
getter returned 6 and TCP remained 1 at both endpoints.

This proves a live local **data-to-original-builder bridge**, not a full cloud
session or complete telemetry coverage. It does not establish framing/auth with
an entered identity, command dispatch or reconnect/sleep lifecycle. Those must
continue through firmware routines and their original validation. Evidence:
`opaque-native-adapter/emulator-stream.json`, `live-local-pipe.json` and
`stream-runtime/`; all paths are under `captures/telematics-20260924/`.

### Native status request and reply verified on the car, 2026-09-24

The next local experiment executed both directions of command **511** using
the original codec, decoder and checked dispatcher. No cloud session was opened
and no actuator command was issued. Synthetic VIN/UUID/key/session flags exist
only in the helper's private memory; the saved vehicle callback buffers are
passed unchanged to the original cache ingest.

The executed chain is `0x6e3b0 → 0x6e858` (request construction/encryption),
`0x573ec → 0x727bc` (incoming frame, UUID/key validity, AES, checksum and VIN),
the original 511 branch at `0x57f60`, and `0x55350 → 0x6f520 →` the original
encoder (native report/reply). A second local receiver runs the original decoder
over that response. The generated **85-byte request** results in a **181-byte
response** whose decoded content preserves the opaque 16-byte correlation and
the native 104-byte report. No independent vehicle schema or packet codec was
added. Native send `0x4aedc` remains captured, not executed.

**Fourteen cases pass offline, on the Android ARM64 emulator and on the car:**
the complete native request/reply chain, response decoding, and twelve rejection
cases (wrong UUID, key or VIN; zero UUID/key; sentinel UUID; identical key/UUID;
GUID not ready; not logged in; damaged ciphertext; bad frame magic; short frame).
The not-logged-in fixture decrypts successfully but does not dispatch 511;
the other invalid fixtures produce no successfully decoded message. These are
specific fixture results, not a security audit of the entire native protocol.
Source argument zero selects the UUID-checking native receive path. The test
uses synthetic UUIDs without embedded NUL and preserves native `strncmp`
semantics; it does not claim a constant-time binary identity comparison.

The on-car run at **17:43:04 UTC** exited 0 under SELinux Enforcing. Executable:
`330eb7f8954a301de394c0b27c66e7a5aa34dd52faa9a81f8bf9d68e9d5df1b6`
(705,728 bytes). TCP=1, step=6, cloud PIDs `112 223 530`, boot, `double_apn`,
Wi-Fi retention=1 and the installed APK hash `ad724b60…9c365f6` matched before
and after. The executable was removed and absence verified. The program wrote
only phase labels and a result summary. It cannot open sockets/files, call Binder
or create threads after its seccomp boundary; CPU/wall limits are 2/5 seconds.
Code pages are RX, never RWX, and unselected instructions are replaced with
traps. Only the 511 handler is retained from the large command switch.

**A real missing-context failure was found before the car run.** The first
emulator binary exited 139 while ingesting callbacks with the local logged-in
flag set. A bounded replay localized it to `0x62bc8`: GOT `0x8ed18` must refer
to an auxiliary subscription vector. The standalone test had left it null.
The initial zero-based instruction emulator had hidden this by mapping the
null page. The corrected tests unmap that page and explicitly supply an empty
vector in both runtimes. The failure, its binary and diagnosis are retained.
This is additional evidence that complete context initialization matters; the
fixture is not a substitute for the real subscription/lifecycle owner.

Other remaining substitutions are allocation/RefBase/single-thread locks,
captured subscription/send effects, deterministic time, absent other consumers,
and RTT observation hooks `0x7e934/0x7e97c` (which delegate to RTTService's
on-send/on-receive hooks). Entry wrappers only retain outputs; the original
decoder return and validation branches are unchanged. Full service startup,
real login/session establishment, TCP reassembly, sleep/reconnection and command
execution are still outside this result. In particular, this establishes a
**local native status roundtrip**, not a cloud-to-car control test or proof that
every command uses this TCP channel; the MQTT path remains separate.

Next: connect the native identity builders to the native registration/login
response handling so the context is established by an actual session, then
exercise a real status request through that same session. Keep telemetry and
command schemas inside firmware and preserve the current working stock link
while developing the bounded runtime. A custom-SIM production toggle is not
implemented by these probes.

Reproducers: [verify_native_roundtrip.py](../research/telematics-firmware/verify_native_roundtrip.py),
[build_native_roundtrip.py](../research/telematics-firmware/build_native_roundtrip.py),
[native_roundtrip_probe.c](../research/telematics-firmware/native_roundtrip_probe.c).
Plan, source snapshots, original-code fixtures, build hashes, failed/corrected
emulator evidence, car results and cleanup are under
`captures/telematics-20260924/native-status-roundtrip/`. No product APK,
release, commit, stock daemon, vehicle setting or real identifier was changed.

### One real registration exchange through original native code, 2026-09-24

After the owner authorized one careful test, a bounded **211-only** exchange
completed at **17:50:26–17:50:29 UTC**. The server returned registration status
**1**, accepted by the original native decoder and 211 handler. This advances
the local-code result to actual cloud interoperability without implementing a
replacement registration schema. It is the bootstrap step before a full
status/downlink session, not the initially considered phone-status test.

The hash-pinned archive code ran under bounded host ARM64 emulation: original
helper constructor `0x6d9f4`, identity preparation `0x6dcf0`, 211 body builder
`0x6e00c`, packet encoder `0x6e3b0/0x6e858`, checked decoder `0x727bc`, and
incoming dispatcher `0x573ec` / 211 branch `0x57710`. The latter stored **1**
at private context `+0x35c`. The subsequent `reset211Variable` call `0x54964`
was captured, not allowed to change real properties or start another exchange.
The packet builder stops after producing the complete frame, before its hex-log
postamble. Vehicle identifiers, provisioned key/UUID and packets stayed in
memory; evidence contains only status/length/verification metadata.

Actual TCP originated through the car's Wi-Fi (`wlan0`). The previously tested
TLS transport retained server-chain/hostname verification and used exactly one
factory-backed signature. It sent **101 application bytes**, received a
**69-byte response**, then closed. There was one cloud exchange, no automatic
retry, no discovery, no working-server login, no telemetry or actuator command.
Identity came from this owner's current factory modem pair; this test did not
substitute another pair or test a replacement SIM. Firmware code owns the
registration body, framing, cryptography and response interpretation; the older
independent packet builders were not called. Native execution and TLS still
run on the Mac in this experiment, so it is not autonomous on-car deployment.

Six preceding offline cases cover the native producer, success status 1,
preserved rejection status 3, and rejection of wrong key/VIN/damaged ciphertext.
Before/after car reads matched: TCP=1, step=6, PIDs `112 223 530`, same boot,
`double_apn`, Wi-Fi retention=1 and installed APK `ad724b60…9c365f6`. One
additional TCP sample during the short exchange was connected; these samples
do not prove uninterrupted long-term health. No files were installed on the
car, the transport process was gone after completion, and temporary public
certificate files were removed by the existing transport's cleanup.

**Provenance caveat:** a preceding preflight at 17:49:35 stopped before signing
or cloud traffic because shell cannot read `/system/bin/cloudmanager`. Its
failure and source are retained separately. The corrected test verifies the
archived executable's hash, not an unavailable live executable hash; it records
the installed build fingerprint and verifies the two autoservice reader
libraries plus safekeyservice against the archived hashes. No process-memory
patching or installed instruction addresses are used by this host-emulated
exchange. Do not describe the installed executable's SHA as live-verified.

Result: original packet construction **and** checked reply handling work with
the real registration server. Still unproved: an integrated 200/220/507 session,
actual status-request routing through the adapter, full command/MQTT handling,
reconnect/sleep lifetime and a custom-pair product option. The single live test
is complete; do not repeat it just to reconfirm an already accepted response.

### Real discovery, login and status request/reply through native code, 2026-09-24

The owner's authorized next step completed at **18:00:53–18:01:24 UTC**.
This advances the previous registration-only result: the adapter discovered
the working server, logged in, received a real **511** status request and sent
the original firmware's response over that same authenticated TLS connection.
The owner confirmed refreshing the official phone app during the test window.
The phone's display of this particular response was not separately recorded.

- Original discovery producer `0x6edac` sent **69 bytes**; original decoder and
  200 branch parsed the **117-byte response**. The adapter captured the native
  resolver call `0x52ea0` and port setter `0x75b70`, yielding the previously
  reviewed `dilinknat0-cn.denzacloud.com:6041`. No replacement discovery parser.
- Original login producer `0x6f01c` sent **117 bytes**. Its **85-byte reply**
  passed the native decoder; native 220 branch set private context `+0x2d0=1`.
  OS randomness supplies the native builder's nonce; the original code still
  constructs the body and performs digest/framing/cryptography.
- At **18:01:16.874 UTC**, a real **85-byte 511 request** arrived through the
  adapter socket. Original UUID/key/VIN/padding/CRC checks and dispatch ran.
  Fresh **1,894 opaque SDK callback buffers** entered the native cache through
  `0x627d4`; original `0x55350` built the 104-byte vehicle body and preserved
  request correlation. Original framing produced a **181-byte reply**, and
  TLS reported all 181 bytes written. **SOC 82.0%** agreed with independent
  `autoservice` transaction7/device1014/FID`0x4A505038`. Current charging
  getter transaction5/device1009/FID`0x34400018` returned15 and supplied the
  original scalar callback destination; the adapter did not encode that field.

No hand-built telemetry, replayed incoming cloud packet, independent vehicle
command executor or actuator call was used. The fixture builders only run in
offline tests. Live identifiers/keys/packets remained in memory. This is still
archived firmware under bounded **host** ARM64 emulation with car-origin Wi-Fi
TCP, not a complete on-car native daemon or an installed product feature.

**Session ownership and recovery:** discovery ran with stock TCP active. Before
login, a disposable **device-local 90-second guard** was armed, then only
`notify_nw(-5)` paused the stock cloud gate. Exact TCP0 was required both before
adapter readiness and before replying. Neither Wi-Fi nor APN profile changed.
After the reply, the adapter closed and `notify_nw(4)` restored stock TCP1/step6.
Before/after snapshots matched boot, PIDs `112 223 530`, `double_apn`, Wi-Fi
retention1 and installed APK `ad724b60…9c365f6`. SDK reader unregistered with
zero drops/errors; helper files and the guard marker were removed. The guard
did not issue a recovery call. Its shell waited for the original sleep before
exiting; follow-up process absence is recorded separately.

**Lifecycle limits:** native post-login `0x55b30` also launches unrelated
configuration/reporting work. That continuation and external notifications were
captured rather than run. Static tracing shows **507 is conditional**, skipped
when `persist.sys.cloud.token_flag=1`; that flag was live1 before this test.
This does not prove token provisioning/renewal in a fresh context. Full cache
field completeness, all command families, MQTT, actuator execution, sleep and
reconnection are not established. The current original SIM pair was used;
replacement-SIM/custom-pair operation is still untested.

Ten native offline cases and seventeen independent TLS loopback cases passed,
including malformed/fragmented/coalesced traffic, rejected login and refusal
to send a status reply on decoder failure. Four host-transport regressions also
passed. Evidence, exact executed source snapshots and hashes:
`captures/telematics-20260924/native-session-status/`. The historical TLS report's
`telemetry_sent=false` field belonged only to the older **512** uploader; it
does **not** contradict the recorded `session_write=181` / `status_reply_sent=true`
for **511**. Subsequent code reports opaque session bytes separately. Callback
age 2.703s in the live report was measured **after native replay**; the source
was required to be under2s old before replay, not continuously under2s during it.

Maintained research: [native_session.py](../research/telematics-firmware/native_session.py),
[verify_native_session.py](../research/telematics-firmware/verify_native_session.py),
[native_session_probe.py](../tools/telematics/native_session_probe.py).
The subsequent on-car experiment below advances runtime placement and explicit
identity-source behavior; do not repeat this host exchange merely to reconfirm it.

Reproducers: [native_registration.py](../research/telematics-firmware/native_registration.py)
and [native_registration_probe.py](../tools/telematics/native_registration_probe.py).
The latter defaults to preview and requires a new report path as an attempt
marker. Evidence, exact source snapshots, plan, offline cases, preflight abort,
cloud result and cleanup are in
`captures/telematics-20260924/native-registration-live/`.

### On-car native adapter with an explicitly supplied SIM pair, 2026-09-24

The owner's authorized transfer to the car succeeded at **18:14:34–18:17:32
UTC**. Android TLS, factory signing, original ARM64 packet construction/decoding,
fresh SDK callback ingestion and both status replies all ran **on the car**.
The Mac deployed and observed the experiment; it relayed no TLS, signatures,
cloud packets or vehicle data. This is a separate bounded research helper using
archived firmware routines, not an input override inside the installed
`cloudmanager` daemon and not yet a Denza Apps feature.

**Identity input:** before each run, the launcher copied the owner's current
original ICCID/IMSI into a mode600 file in a mode700 test directory. The Java
controller read and immediately deleted it, then supplied that same pair to
each new native context. Neither controller nor worker read modem identity at
runtime or wrote modem properties. Values, keys and raw live packets were not
retained in the reports. This proves explicit input and reapplication after
worker reconstruction using the valid current pair; the installed SIM and
internet transport were deliberately unchanged.

Two bounded runs are recorded, including the unsuccessful request window:

| Run | Observed result |
| --- | --- |
| 18:14:34–18:15:05 | Native211 accepted (`REG 1`), native200 discovered the reviewed server, native220 login accepted. No511 arrived within25s; the owner refreshed after the window. Timeout closed the helper and restored stock TCP1. Three factory TLS signatures, no status upload. |
| 18:16:55–18:17:32 | Reused that successful discovery; two new TLS/native220 sessions accepted. Real511 requests arrived in each session. Native181-byte replies sent at18:17:15.629 and18:17:29.306, both with81% SOC matching the independent getter. Two factory TLS signatures. |

Between successful replies the controller closed the socket and native worker,
then started a **new worker and authenticated TLS connection** with the same
provided pair. The owner confirmed refreshing the phone app twice. The first
reply used294 fresh opaque SDK buffers, the second291; last-buffer age after
native ingestion was64ms/87ms. Both readers unregistered with zero drops/errors.
TLS accepted all181 response bytes each time. The phone's display of these
particular replies was not independently captured. The incoming requests were
real cloud requests, not fixtures or replayed packets.

**Implementation boundary:** `session_runtime.c` executes the reviewed native
211/200/220/511 routines directly on ARM64 in a private mapping. Unused native
text/PLT entries trap; code and writable data are separated. Its seccomp policy
allows pipe I/O and exit, not sockets/Binder/files/process creation. No stock
daemon startup or actuator handler runs. Android's platform TLS uses an opaque
RSA key whose signing callback calls the factory `safekeyservice`; the private
key remains in the car. Root and hostname verification finish before signing,
including completion of the server's omitted intermediate. Original code owns
the vehicle body, request correlation, checked decoding and reply framing.

**Restoration and evidence:** only the stock cloud gate was temporarily paused
with-5, then restored with4. A device-local105s deadline and independent110s
guard bounded the experiment; the guard did not fire. Before/after boot, stock
PIDs `112 223 530`, TCP1/step6, `double_apn`, Wi-Fi retention1 and installed APK
SHA `ad724b60…9c365f6` matched. Helper/reader/guard processes were absent, the
private identity file was absent and the exact temporary car directory was
removed. Wi-Fi, modem identity, APK and stock processes were not changed.

Seven synthetic native cases passed on both emulator and car, including invalid
login/command/ciphertext and frozen/missing input rejection. Four independent
Android TLS cases passed on the emulator: valid mTLS with omitted intermediate,
wrong hostname, wrong root and bad signature. The two peer-validation failures
made zero signer calls. Exact executed versions, logs, baseline/result,
SHA256SUMS and build provenance are retained in
`captures/telematics-20260924/oncar-native-session/`. Native worker SHA256:
`cd43b45d29609215dcb9b4b9de562f84201c15c0a1a8f75a3692b17432dd4e05`;
successful Java adapter SHA256:
`7b70641827119f0040d70bf92211b9550060f68bc069b831197a919e16dedd27`.
The archived firmware remains hash-pinned; the installed cloudmanager file is
unreadable to shell, so its live executable hash is still **not** established.

**Remaining scope:** deliberate reconnect is proved; unexpected network loss,
long-lived heartbeat/sleep recovery, product lifecycle and ordinary-app access
are not. Token_flag was already1; native ancillary post-login work remained
captured, so fresh provisioning/renewal and MQTT are not established. Downlink
status request/reply is proved; actuator commands and complete cache field
coverage are not. Replacement-SIM internet and a provided pair different from
the installed SIM remain untested. Next work should address these lifecycle
and transport boundaries, not reimplement vehicle schemas or repeat the same
successful two-refresh test.

### Official-phone climate gesture: stock downlink observed, 2026-09-24

The owner requested a climate test, confirmed the car was outdoors, and chose
to operate the official phone app while this session observed. The experimental
adapter was **not running**; the stock connection started at TCP1/step6, profile
`double_apn`, PIDs `112 223 530`, unchanged boot. The only live host operation
during the gesture was passive logcat. No control packet, Binder setter, APK,
network change or cloud-gate change was sent by the researcher.

The stream from18:24:03.283 to18:24:29.617 UTC retained:

- 18:24:09.547: actual stock decoded511 request,85 bytes.
- 18:24:28.005: actual stock decoded536 request,69 bytes.
- 18:24:28.059: actual stock decoded532 request,85 bytes.

Both536/532 carried request flag254/version0. The observer then ended with
`stream_closed` after26.33s, before its180s deadline. ADB was subsequently
offline, then absent, and the local5555 listener was absent. This establishes
a diagnostic transport interruption, **not its cause**. An attempted bounded
log read timed out. No complete reply/result, climate subcommand or physical
HVAC state was retained. The owner replied “check” without a success/failure
result. At that point only request delivery during the gesture window was
proved. The recovery check below subsequently found the stored MCU result.
Do not attribute the tunnel loss to climate.

Offline inspection of the pinned firmware identifies the actual532 branch at
`0x589dc`. It preserves correlation/duplicate, busy, repair-mode and selected
vehicle-state checks. Its downstream `0x5fe04` builds the native opaque MCU
envelope and either queues it or calls `0x4ab90`, which forwards through stock
`BYDAutoManager.setBuffer` (device1034/FID`0xAA000004` on this branch). These
are internal code boundaries, **not permission or execution proof**. Reusing
them requires their real state, wake/queue lifecycle and response handling;
calling the final writer alone would skip the contract. No such write was
attempted. The existing adapter still allows status511 only.

The passive observer now also retains bounded532 subcommand/result metadata,
536 MCU status and selected rejection/timeout labels, while discarding raw
payloads and identifiers. Eight synthetic privacy/parsing/preview tests pass.
This cannot recover the fields discarded by the earlier capture. Evidence and
exact source versions: `captures/telematics-20260924/climate-native-path/`.
**Recovery at18:28 UTC:** the same boot and stock PIDs were present, TCP1/step6
and `sys.cloud.remote_controling=0`. The main/system journal had already rotated
past the test (oldest retained cloud line18:27:53), but the stock property
`sys.cloud_532_reply` preserved:
`2026-09-24 21:24:39:-->532_cmd:3-> reply reult:sucess !`.
Offline tracing finds its writer at`0x54e20`, called from the MCU callback
`0x6039c` at`0x60a94`. For532 it records the MCU reply flag:1 selects success,
2 selects failure (or the specific key-authentication failure), and intermediate
flag3 does not write a terminal result. This is a completed MCU response, not
merely TCP receipt. It does not independently prove delivery of the response
back to the phone.

At18:29:37 UTC, independent read-only `autoservice` getters confirmed climate
online1, power1, compressor1 and fan1. Device1000 and CAN-FD FIDs
`0x40400000`, `0x40400010`, `0x40400009`, `0x4040001c` come from the stock
`BYDAutoAcDevice`/`BYDAutoFeatureIds`; `sys.car.protocol=CANFD` was checked first.
This confirms climate was on after the owner's gesture, without replaying it.
The owner then switched it off in the phone app under a new passive capture.
At18:30:10.513 UTC the stock client received532/subcommand9. The diagnostic
stream closed at18:30:10.911. After recovery at18:34:46, the stored result was
`2026-09-24 21:30:10:-->532_cmd:9-> reply reult:sucess !`, with busy0/TCP1/step6.
Boot ID and stock PIDs remained identical; Android uptime was6971.89s. Thus
both owner on/off gestures have successful **MCU result** evidence, but the
later climate getters were still1 with current ACC2/MCU_WAKE1. These later
values do not establish an uninterrupted off state or whether the recovery
gesture reactivated climate. These are stock-client observations; the
experimental adapter still has no enabled climate handler.

**Diagnostic disconnect cause found:** the actual installed local SSH bridge
package is `dev.denza.adbbridge`, reached through port2222 on the car. Android
`dumpsys activity exit-info dev.denza.adbbridge` records two exact matching
process exits:21:24:29.630 (PID1536) and21:30:10.967 (PID8944), both reason10 /
subreason21, description **`stop dev.denza.adbbridge due to quickboot 0`**.
These are firmware QuickBoot force-stops of the bridge, not Android reboots.
The system category “USER REQUESTED” does not identify a manual force-stop by
the owner or researcher; the recorded description identifies QuickBoot. Prior
entries show the same description earlier that day and on previous days.
The exact trigger of the QuickBoot transition still needs tracing. Neither
the presence of a reply nor elapsed uptime proves every vehicle ECU stayed
awake. Raw exit-info, both MCU result strings and boot/getter snapshots are
retained with the experiment.

**Temperature test passed:** the owner changed the official phone setting from
25°C to24°C while climate remained on. A bounded **read-only** recorder ran
locally on the car, filtering complete known numeric cloud-log messages and
sampling climate getters and the stored result. The retained exchange is:

- 18:37:29.495 UTC: cloud532/subcommand3, request flag254.
- 18:37:29.529: intermediate MCU532 reply flag3.
- 18:37:29.998: cloud532 reply flag4, again subcommand3.
- 18:37:30.460: terminal MCU532 reply flag1, success.

The stored result became `2026-09-24 21:37:30:-->532_cmd:3-> reply reult:sucess !`.
Independent stock getters for both front target temperatures changed25→24°C
by18:37:34 and remained24 through the final sample at18:39:05. Climate power
was1 in all24 samples. These are target setpoints, not measured cabin air
temperature. Android boot ID remained identical, bridge PID16024 survived, and
no diagnostic disconnect was observed. The recorder completed normally with
`READY`/`DONE`; its logcat error file was empty. All eight diagnostic files were
pulled and their SHA256 hashes verified before removal of the exact temporary
directory. The recorder process was confirmed absent. The owner-set24°C and
climate-on state were left in place.

The script is [cloud_control_capture.sh](../tools/telematics/cloud_control_capture.sh).
The complete evidence is under `climate-native-path/temperature-live/`, with
`temperature-result.json` and a refreshed `SHA256SUMS`. This proves the **stock**
temperature-control path, not climate through the experimental identity adapter.
The next adapter step must preserve the original532 handler's checks, MCU
intermediate replies, subsequent cloud messages and final MCU result. The
observed3→4→1 exchange must not be reduced to a single final buffer write;
the exact meaning of every intermediate flag is not yet established. No new
actuator handler was enabled by this observation.

## Original SIM pair and a thin native adapter, 2026-09-24

The owner proposed storing the original Chinese SIM's ICCID/IMSI in Denza Apps
while using a replacement SIM for internet, and explicitly allowed reapplying
the pair whenever the native process restarts. Preserving stock downlink command
handling is part of the requested design. This pass analysed that boundary;
it made only bounded metadata reads on the car, with no identifier writes,
crypto/cloud calls, native restart or APK installation.

**A precise process-local input boundary is identified and verified offline.**
In the reference cloudmanager (`9e36cdbf…82eb9`), `prepare` calls `property_get`
for `ril.csim.iccid` at **0x6dd8c** and `ril.imsi` at **0x6deb0**. Returning the
owner-supplied pair only to those reads lets the original constructor,
preparation, MD5 and packet builders produce consistent **211 and 220** inputs
while the modem's properties remain different. This is a point for a native
adapter/interposition implementation, not a discovered Binder setter.

[verify_identity_override_native.py](../research/telematics-firmware/verify_identity_override_native.py)
executes those actual ARM64 routines with synthetic properties under Unicorn.
Six cases passed: cold initialization, same-process retry, reapplication to a
new process, disabling an override in a warm process, a fresh process without
override, and IMSI whose MD5 begins with zero. The last case matters because
stock `prepare` then rereads IMSI. **Changing/disabling the source alone does not
replace a warm cache**; applying a new pair or switching back requires a safe
cache update or reconstruction as well. The experiment supplies an emulated
read hook and synthetic nonce and stops at serializer entry. It proves body
construction, not on-car injection, permissions, TLS or server acceptance.

### Available interfaces and deployment boundary

| Candidate | Evidence and consequence |
| --- | --- |
| Native Binder setter | The 39-method `ICloudRemoteControlService` has no identity setter. `systemInfoInd` at 0x51c30 logs/returns. `setControlConfigure` at 0x4f5d8 parses specific control/server/reset fields, not ICCID/IMSI; it is not a generic property writer. No suitable setter found in these reviewed paths. |
| Java cloud message bridge | Registration stores callback 0x4dcbc via GOT relocation 0x8ed08 and setter 0x7c228. The callback accepts id=1 and sends JSON to 0x64a7c, which parses APPID/cmd/data and dispatches typed handlers. It is not a generic entry for a decrypted cloud frame or arbitrary 211/220 body. Full indirect-handler coverage is not claimed. |
| Global SIM property writes | Both live properties are labelled `radio_prop`. Selected platform/system_ext/vendor CIL membership tracing found no shell/untrusted-app set grant. The same ICCID property is used by retained `BydDcTracker.getSimType()` to classify the carrier; IMSI also feeds radio-recovery decisions. Periodic global writes would not isolate cloud identity even with permission. |
| Process-local adapter | This is the narrowest design for retaining stock protocol and command handling, but deployment needs rights to interpose inside/start the privileged native process. Current ADB is uid=2000, SELinux Enforcing; native executable metadata is denied and `/proc/<pid>/mem` is root-only. Selected CIL also has no shell ptrace grant to cloudmanager. Root alone is not a proof of access under SELinux; no privileged deployment path has been established. |
| Separate client | Can explicitly select the original pair without touching modem properties, but needs its own session and incoming-command integration. The owner's later adapter boundary excludes it as a product fallback; existing short experiments remain research evidence. |

The CIL trace is a selected-source allow/membership inspection, not a full
compiled-policy decision. Actual side effects of property writes were not
tested. Saved wrapper `cloudmanager.sh` merely sets LD_LIBRARY_PATH and launches
the native executable under its init service; no identity argument or input
file is provided by that wrapper.

### Why periodic registration is not a two-way adapter

The hardware signature authenticates a TLS handshake; it is not a reusable
signature that can be attached to each registration packet. Existing helpers
register/login/send fresh telemetry, then close their sockets. Repeating 211
does not itself supply an online working session or relay received commands.
The firmware has command-201 heartbeat acknowledgement/timeout handling
(`CMD201Handler`, e.g. 0x38a24); two sends without the expected response reach
its disconnect branch. A replacement client needs the session lifecycle.

Native incoming-frame processing is internal at **0x573ec**, with native state
and downstream checks (including repair-mode control refusal). The reviewed
Binder/Java interfaces do not establish a way to pass it an arbitrary received
cloud frame. An outside TLS proxy cannot rewrite an authenticated encrypted
stream just by knowing ICCID/IMSI. A process-local identity-source adapter would
leave the stock receiver and checks in place; preservation of their behavior
still requires live validation after any such integration.

The research helpers now support an explicit **`--identity-file`** containing
the owner's original pair for registration and login/upload. Nine synthetic
tests verify that 211 and 220 use the same supplied identity even with a
different modem pair, default modem behavior remains, malformed/partial input
fails and the report does not contain identifiers. The source choice is not a
product toggle or persistent service and has not been sent live. It retains
the existing owner-car/library/certificate checks. See
[the tool README](../tools/telematics/README.md#original-sim-identity-input-research-only).

**Decision:** prefer a process-local adapter if a supported privileged launch
path can be established; do not present repeated property writes or repeated
registration as equivalent. A separate telemetry client is excluded by the
owner's subsequently clarified adapter boundary above. Evidence,
selected assembly, policy trace and test results:
`captures/telematics-20260924/custom-cloud-identity/`.

## Isolated native identity routines executed on the car, 2026-09-24

After the owner explicitly authorized checking viable hypotheses on the car,
the alternative process-local route passed its first **real on-car execution**
at **16:59:27 UTC**. A temporary standalone ARM64 executable ran selected bytes
from the archived cloudmanager (`9e36cdbf…82eb9`) as shell UID 2000 with SELinux
Enforcing. It did not execute the installed daemon or modify its address space.
The same executable first passed on the ARM64 Android emulator.

Seven cases passed on both targets: cold overridden pair, repeated preparation,
disabling the source while a warm helper keeps its old pair, reconstructing the
helper without an override, two preparations with an IMSI whose MD5 starts with
zero, and reconstructing the helper with the override again. The actual native
packet-helper constructor, preparation, MD5, and 211/220 body-building
instructions ran. Checks compare the registration pair and login digest with
independent synthetic fixtures. **These are local body checks, not cloud
registration/login or a replacement-SIM acceptance test.** No real subscriber
values enter the executable.

The test maps a private copy, permits execution only on selected pages and
replaces unused text with traps. It runs no ELF initializers, main, cloud-service
constructor, worker threads or service publication. Reviewed call boundaries
use local stubs for Android object lifetime, allocation, logging, property
reads, memory helpers, nonce and body capture. Two local branch patches return
through the original epilogues immediately after body capture, before framing,
signing or sending. The installed code and global telephony properties are
untouched. This proves that **isolated reuse is executable with current shell
rights**, not that interposition into the running privileged daemon is available.

Before entering those routines the test installs seccomp permitting only exit
and writes to stdout/stderr; test socket, ioctl, openat and clone calls all
return EPERM. CPU time is limited to two seconds and wall time to five seconds;
core dumps are disabled. Exit was 0 with empty stderr on both targets. Before/
after car reads matched: TCP=1, step=6, same boot and cloud service PIDs,
`double_apn`, Wi-Fi retention=1, unchanged real ICCID/IMSI (compared only in host
memory). These endpoint checks do not establish uninterrupted long-term health.
The 630,704-byte temporary executable was removed and absence checked.

Reproducible source:
[build_identity_runtime.py](../research/telematics-firmware/build_identity_runtime.py)
and [identity_runtime_probe.c](../research/telematics-firmware/identity_runtime_probe.c).
The build uses already installed Clang and Rust's ELF LLD, without an NDK
download. Executable SHA-256:
`c14a5b281c1533bec650f5081a1e7765f635b15eb579af3a024ec436a79a6b3b`.
Plan, build inputs/ranges, emulator/car results and cleanup proof are retained in
`captures/telematics-20260924/native-identity-runtime/`. It remains research,
outside the product APK. Transport, authentication, lifecycle and downlink
ownership still need integration; this result does not replace those checks.

### Downlink and the separate MQTT service

The current `libmqttserv.so` on the car hashes to
`7b1b92894cd8bf13aaf9d008fe89beba0171ce890deceb6aa34dd5a6442611e6`,
matching the inspected archive library. Its Binder dispatcher maps transaction
**8** to a no-argument getter (case target 0xad38, virtual slot +0x58).
The archived mqttserv vtable at 0x92118 maps that slot to 0x2edb0, which obtains
the current state reference and returns whether state+0x14 equals 2; it starts
no connection. Two live reads returned Parcel(0,0), while cloudmanager's TCP
getter returned Parcel(0,1). `sys.mqtt_status` was empty, so reading that property
alone would not provide a reliable connection verdict. This is one current-car
snapshot, not a claim about all firmware or which channel every phone action uses.

The inspected cloudctrlserv Binder onTransact implementations (0xb0f0/0xb160)
only log and delegate to BBinder: they expose no arbitrary-message injection
method in these paths. Its internal MQTT listener at 0xdb10 calls 0xc460, which
parses with `handleMqttRcvParse` and handles selected command families 211/7002.
Topic names such as CONTROL/VEH are not evidence that this listener can accept
an arbitrary TCP frame or execute the same command set. TCP has its own stateful
dispatcher at 0x573ec, with session/format checks and command-specific guards.
Copying a post-check actuator call would lose that contract and is not the
adapter design.

The next observable discriminator is one official-phone **status refresh** with
passive, bounded logging. The observer preserves only event labels and bounded
numeric command metadata, discarding payloads, topics and raw lines. Missing
events are inconclusive: the phone can read cached backend data and relevant
logs may be absent. No actuator command is substituted for a missing refresh.
Evidence and selected assembly: `captures/telematics-20260924/downlink-routing/`.

**Passive live result:** the 180-second stream ending 17:06:04 UTC captured
TCP heartbeat reply 201 at 17:03:56.419, then **two received command-511 frames**
at 17:05:46.309 and 17:05:52.383 (85 bytes, reply flag 254, version 0). The owner
confirmed refreshing the official phone app; the acknowledgement was processed
at 17:07:14, after the stream ended, and the exact gesture time was not recorded.
Thus the capture proves actual TCP downlink receipt, but not a precisely timed
one-gesture/one-request relationship. The following 60-second stream captured
only a 201 reply. MQTT remained disconnected in the adjacent getter snapshots.
Outgoing 511 completion was not captured by that initial observer revision;
support for the native `send_complete` label was added afterward, without
retroactively claiming a sent reply.

The matching TCP switch table maps 511 to **0x57f60**. That branch copies 16
bytes from decoded body+1 to `this+0x7d8` and calls **0x55350 with argument 1**.
The report builder prepends those 16 request-correlation bytes to the ordinary
104-byte CAN-FD status body, producing 120 bytes; 0x6f520 selects message 511
for this variant. The eventual send path is 0x556f0 → 0x4aedc(511), retaining
the stock network/cloud/login checks. This gives a concrete non-actuating
acceptance observation for a thin adapter: the same stock handler must still
receive the request and build/send its response. The earlier proposal to
implement this response in a separate client is withdrawn under the owner's
clarified scope. Sending unsolicited 512 is not equivalent, and these findings
do not prove that lock/climate/other commands are integrated.

## Alternative integration levels, offline, 2026-09-24

The owner disconnected the car and requested exploration of other integration
levels before reconnecting it. **This pass made no ADB, crypto, cloud or vehicle
calls.** It used the retained matching ARM64 binaries, selected policy sources,
and decompiled Java corpus. No product APK, stock file or release was changed.

### New result: a separate process is a different permission question

Lack of permission to interpose inside the running privileged daemon does **not**
by itself rule out reusing parts of its code in a process launched by shell.
Selected platform/system_ext/vendor CIL contains allows for shell execution of
`shell_data_file` without transition, read/execute of `system_lib_file`, finding
the auto/state/cloud services and calling autoservice/stateservice Binder.
It contains no matching shell grant to publish `cloudmanager_service` or execute
the protected `cloudmanager_exec` file. These are selected-source policy facts,
not a complete live DAC, linker-namespace, Binder-method or vehicle-control
permission decision. A copied executable would have a different label and
would remain shell; copying it would not confer the stock daemon's privileges.

This keeps **an isolated process reusing stock protocol/command routines** on
the candidate list. It would supply identity locally and retain the original
command validation logic, without touching the radio properties or another
process's memory. It is substantially more work than two property hooks:
subscriptions, wake/network events, file locations, command-result routing and
the rule that only one client owns the cloud session must also be integrated.
Availability of each required method under shell remains unproved.

**Do not test this by launching an unchanged second cloudmanager.** Native
factory `0x666e4` constructs an object, publishes the fixed name `cloudmanager`,
then calls `0x5b744` regardless of publication status. Its post-publication
lookup checks for any object at that name, not equality with the new object.
Main `0x67114` ignores the factory's return status and proceeds to the Binder
thread pool. The constructor creates a worker thread (`0x48db8`), and the later
initialization registers vehicle observations and the Java listener. The
retained `CloudServiceImp.registerListener()` keeps the first native listener
and refuses another while it is present. Thus a second process can continue
while framework events/getters still reach the first process.

[verify_cloud_startup_native.py](../research/telematics-firmware/verify_cloud_startup_native.py)
executes the original factory and main instructions with synthetic Binder
objects. **Three cases pass:** publication succeeds; publication fails but the
name already exists; publication fails and lookup is empty. All request the
downstream initialization and thread pool. The test does not execute the
constructor, downstream initialization, Android services or any vehicle action.

### New result: TCP and MQTT do not select ICCID identically

In matching `mqttserv` (`31d31961…0db4fb`), getter **0x4c300** first reads
`ril.csim.iccid`. It uses that string only when its length is **exactly 20**;
otherwise it tries `persist.radio.iccid`, also requiring length 20. This is a
length check, not decimal-digit validation. The getter itself reads properties
on each call; its callers can cache values separately. Username construction
`0x351b0` calls this getter and uses a nonempty cached username on later calls.

TCP's reviewed `prepare` instead copies a nonempty `ril.csim.iccid` into its
own cache, truncating/padding its 20-byte field; it has no persistent-property
fallback at that input boundary. An invalid-length current value with a valid
persistent value can therefore give the two protocols different identities.
Updating properties also does not guarantee either client discarded a prior
cache. This is a concrete diagnostic hypothesis, **not an established cause of
any user's failure**.

[verify_mqtt_identity_native.py](../research/telematics-firmware/verify_mqtt_identity_native.py)
executes this actual getter with synthetic data. **Eight cases pass**, including
19/20/21-character inputs, missing properties, fallback, nondecimal final `F`,
and changed properties in one emulated process. It does not attempt broker
authentication or establish which protocol handles a particular phone button.

### Candidate matrix after this pass

| Level / candidate | Result | Next discriminator |
| --- | --- | --- |
| Original SIM identity plus independent data bearer | Earlier owner-identity registration through the Mac route proves separation for that transaction. Keeping the original SIM in the IVI and using a separate modem/hotspot is a transport workaround, not the requested replacement-SIM software feature. | No reason to retest registration alone; a replacement-SIM scenario must keep identity consistent through 220 and reconnects too. |
| Telephony property producer / second slot | Retained `BydGsmCdmaPhone.initRadioProp/obtainRadioProp` clears/populates current and persistent ICCID from SIM records. Secondary phone IDs use suffixed properties; TCP reads only the unsuffixed source. No arbitrary original-pair input found in these paths. | Read presence/length/equality of relevant slots; do not change slots, radio state or SIM records as a discovery probe. |
| Warm native cache | Earlier native tests show an already cached pair can survive changed/empty properties, with the zero-leading MD5 edge. This could explain some hot-removal anecdotes. | It cannot restore a user-entered pair after a cold process start and is not a reliable product design. |
| Existing Binder/configuration/Java bridge | Reviewed TCP methods still provide no generic identity setter or incoming-frame entry. The native Java listener is a singleton. | Trace a specific handler if new evidence points to one; do not send speculative configuration/reset commands. |
| Stock launch settings / app hotfix | The native init wrapper has no identity argument. Retained `BydHotFixAssistantPlatform` hooks `LoadedApk`/class-loader native libraries; that is not evidence of a hook for this standalone init daemon. | A real native service launch extension would be needed; an Android app library-loading hook alone does not supply it. |
| Isolated reuse of stock native routines | Startup emulation proves an unchanged duplicate is unsuitable. The later authorized local runtime test above passed seven native identity-body cases on the car under shell/Enforcing. | Still eligible if the native implementation owns data structures/codecs and all command checks; test opaque-buffer integration and context ownership, not our own report builder. |
| TCP socket/TLS relay or handoff | Native `0x7f9e4` creates its own SSL object and binds its own fd (`0x7fa5c`); no fd/session-import API found in the reviewed interfaces. A raw TCP relay retains the original identity inside TLS; an authenticated helper's socket is not the stock SSL/session state. | Do not disable TLS verification or treat one accepted 211 as a transferable login. A process-local boundary or full session owner is still required. |
| MQTT as an alternate stock command bridge | Matching topics contain `CONTROL/VEH`, `CONTROL/MCU` and result topics; `cloudctrlserv` depends on `libmqttserv`/`libprotocol`. This keeps a typed bridge worth tracing, but does not establish that the official phone uses it, or that its commands equal TCP commands. MQTT itself also derives identity from ICCID. Broker-address configuration and the packaged `FEATURE_PROXY_MODE` name are not identity setters or proof of a usable proxy mode. | Observe which stock receiver handles one non-actuating phone refresh; trace that exact listener and result path before any executor. |
| Fully separate authenticated client | Registration/login/telemetry inputs are understood. Receiving bytes is feasible within its own session; continuity, sleep and command integration remain work. | Excluded from the product direction by the owner's clarified adapter boundary; retain existing experiments as evidence only. |

### Ready for the next connection

[cloud_path_snapshot.py](../tools/telematics/cloud_path_snapshot.py) is a **preview
by default** collector prepared for the next connection. Execution reads caller
identity/SELinux, service presence, exact cloud-state properties and path
metadata. It compares SIM strings only in host memory and writes lengths and
equality, not values, digests, VIN, tokens or certificates. It explicitly labels
the native cached pair **not observed** and notes that sequential SIM-property
reads are not atomic. Permission denial and failed reads remain unknown rather
than empty values. Five synthetic tests cover privacy, source selection, failure
handling and preview without ADB. The first live read-only follow-up is recorded
below.

```sh
python3 tools/telematics/cloud_path_snapshot.py
# Once the owner reconnects the existing transport:
python3 tools/telematics/cloud_path_snapshot.py --execute --serial 127.0.0.1:5555
```

**Live follow-up, 16:47 UTC:** after the separately documented network incident
and owner reboot, the read-only collector found shell UID 2000, SELinux
Enforcing, cloudmanager/mqttserv/cloudctrlserv running, and stock TCP connected.
Current and persistent ICCID are both 20 decimal characters and equal; IMSI
has 15 decimal characters. Second-slot properties are empty. Values were not
saved. Protected executable/data-path metadata reads failed and remain unknown;
the report is deliberately marked incomplete. The exact Binder service name
is `mqttserv` (`android.os.IBYDCloudMqttServer`), confirmed both by live service
inventory and native publication code. The collector's initial `mqttservice`
spelling was corrected; its original false entry must not be interpreted as
absence of MQTT. This snapshot does not expose native cached identity or prove
an identity-override deployment mechanism.

After that snapshot, the most informative vehicle experiment is a **passive
trace of one official-app status refresh while the existing stock connection
is working**, preserving Wi-Fi. It should identify TCP versus MQTT ingress and
reply ownership before selecting the command bridge. If refresh produces no
downlink event, record that negative result; do not substitute an actuator
command without a separately agreed experiment. The isolated native-runtime
candidate then needs its own controlled local test, not an unchanged-daemon
launch and not a product toggle yet.

All new evidence, source hashes, selected assembly, policy queries and native
test results are in `captures/telematics-20260924/alternative-identity-paths/`.
Only three small files were additionally extracted from the existing archive;
no full partition, second archive or firmware installation was produced.

## Real SOC reached the official phone app over Wi-Fi, 2026-09-23

**The official Denza app displayed 74% after the helper uploaded this car's
fresh stock status report.** The passive CAN SOC and independent stock getter
both measured **74.0%** immediately before transmission. The phone also displayed
**472 km**, **35% fuel**, and charging progress. Those additional displayed values
were observed on the phone; only SOC was independently checked against a getter.
This establishes the working path: stock vehicle data → authenticated DiLink
message **512** → official Denza cloud → official Denza phone app.

The owner explicitly approved sending the available partial report, then handed
over the phone screen. Two bounded uploads ran; neither installed a persistent
service or executed any remote vehicle-control request.

| UTC | Test | Result |
| --- | --- | --- |
| 10:49:02–10:49:09 | Fresh 30-second capture, verified TLS, accepted 220 login, one 512 | 24 measured cache entries; independent SOC **74%**; 165 wire bytes sent; phone subsequently showed **74%** |
| 10:53:10–10:53:17 | Fresh capture and one further 220 → 512, with resolved charging override | SOC **74%**, stock charging getter **1**; phone at 10:57 UTC showed **74% / 472 km / 35%**, with a changed charging-time estimate |

Each 512 has a 104-byte body and flag FF. It has no explicit acknowledgement in
this test. The uploader's `telemetry_sent` records the successful TLS write;
phone confirmation is separate evidence, not an inference from that flag.
The phone screenshot after the first upload is
`captures/telematics-20260923/phone-validation/denza-74-before-corrected-charge.png`;
after the second it is `denza-after-corrected-charge.png` in the same directory.
The application continued to show the readings after both test sessions closed.
Its offline icon at that point is expected; continuous online presence has not
been implemented.

### Report completeness and the resolved charging field

Of the stock 25 cache entries, **24 were measured**. The oldest used entry was
3.602 seconds old for the first upload and 3.263 for the second, at assembly
before authentication. The missing `0x417 / group 0` entry contributes **byte 102**.
Both uploads used the stock cache's initial zero for that byte, under the owner's
explicit partial-report permission. It is **unmeasured**, not a measured zero.
No SOC, range or fuel value was invented.

The SDK's `BYDAutoBodyworkDevice.hasMessage(26)` reads device 1001 / FID
`0x41700000`. Its live result was **2**, named **DEVICE_OFFLINE_ALWAYS** in the
same SDK (`0` temporary offline, `1` online). This supports treating the absent
stream as a model/configuration issue to investigate, but does not prove a
physical component is absent or that zero is the correct production report value.
Related SDK fields describe indirect tyre-pressure state and warnings.

The stock integer callback at `0x6822C` checks **0x34400018** at `0x682E0`, then
passes the value at `0x68344` to `0x5DF50`, which writes **mChargingStatus** at
`this+0x294`. The matching native getInt is transaction **5**, device **1009**,
FID **0x34400018** (`CHARGING_BATTERRY_DEVICE_STATE`). It returned **1**, charging.
The first upload used the actual CAN fallback, body byte 98 = 145; the second
used the resolved stock override, body byte 98 = **1**. The current uploader
requires the getter and reproduces the native override/fallback branch.

### Reproduction, verification and cleanup

- [CloudCanSnapshotProbe.java](../tools/telematics/CloudCanSnapshotProbe.java)
  passively reads the stock YUN callback for 30 seconds, then unregisters.
- [status512_body.py](../research/telematics-firmware/status512_body.py) builds
  the native 104-byte body. Seven bounded ARM64 fixtures compare the native
  serializer, AES/MD5 and body-copy routine byte for byte, including both charging
  branches. This caught and corrected a draft cache-index-13 offset before upload.
- [status_upload_probe.py](../tools/telematics/status_upload_probe.py) defaults
  to preview. Execution requires fresh data, zero callback errors/drops, agreement
  with the independent SOC getter, verified TLS and accepted login. A missing
  0x417 entry requires the explicit diagnostic flag; all other omissions stop it.
  It sends one report, holds five seconds and closes, without automatic retries.
- The native adapter passed **11 local TLS/application scenarios**, including
  withholding 512 after rejected login and rejection of wrong hostname, chain or
  signature. Artifact READMEs record the build commands and SHA-256 manifests.

Across the TLS/bootstrap/upload continuation, there were **eight stock signature
calls and thirty public-certificate reads**, excluding the earlier one-off local
signature test. No private key was exported, no 507 token request or token-file
write was made, and VIN/SIM/key/UUID values were not retained in these reports.

At **10:58 UTC**, the existing ADB connection was ready, cloudmanager PID **113**
and safekeyservice PID **646** were unchanged, and SELinux was **Enforcing**.
The passive probe had unregistered; its temporary JAR and owned TCP helpers were
gone. Phone settings were restored to `stay_on_while_plugged_in=0` and a
30-second screen timeout. The router, ADB keys and existing tunnel were untouched.
The original downloaded firmware archive remains in place. Temporary compiler
outputs and the separate Unicorn installation are removed after verification.

**Next:** evaluate the stock-client adaptation below before deciding whether a
separate uploader is needed. Ordinary APK access, sleep/wake
behavior, continuous connection and the production handling of byte 102 remain
unproved. A working cellular attachment was not required for these Wi-Fi uploads;
they did use the car's existing factory SIM identity and cryptographic identity.

The sections below preserve the earlier stages; their outstanding items describe
what was unknown at that time and are superseded by the live result above.

## Stock-client Wi-Fi adaptation, 2026-09-23

**Live activation succeeded at 11:40 UTC (14:40 Moscow).** With the owner's
explicit approval, the stock profile was switched once to `double_apn`, followed
by one `notify_nw(4)`. Stock cloudmanager PID 113 resolved its registration,
discovery and session endpoints and established TLS. At 14:40:34 and subsequent
30-second observations, its TCP getter was **1**, `app_reg_status` was **2** and
`token_flag` was **1**; validated Wi-Fi and ADB remained available. Both cellular
APN interfaces stayed empty. MQTT's separate getter remained 0. One initial
socket disconnect/reconnect was visible; long-term stability is not established.
The native binary was not replaced and the manual uploader was not used.

The owner initially requested **no timed stop**, to observe the official phone
app. The run is `captures/telematics-20260923/stock-client-wifi/live-hold-1/`;
`timing.json` records its host owner/guard PIDs and `latest.json` its current
observation. Explicit stop uses the same runner with `--stop --out` that path.
The owner process then pairs event -5 with restoration of `triple_apn`; its
independent guard restores if the owner process exits. Actual restoration must
be checked in `restored.json` and the restoration-check report before claiming
completion. Restoration did not complete in this run (see the outcome below). The owner
then confirmed: **the official app shows data**. Exact displayed values were
not specified. A subsequent metadata-only log read at 14:43:23 recorded the
native CAN-FD 512 serializer (`dataLength=120`, `offset=120`); this is not a
decoded report or an independent proof of its complete contents. The earlier
exact 74% proof belongs to the separate manual helper.

At 14:49 Moscow, while navigating the owner's phone to account settings, the
official vehicle page visibly showed **80%**, **485 km**, **35% fuel**, a connected
indicator, and charging **1.9 kW / 4 h 12 min remaining**. These are observed app
values, not independently sampled vehicle getters. The temporary navigation
screenshots were deleted after use. At 14:58:20 the held run still reported
stock TCP=1, validated Wi-Fi, registration=2 and token_flag=1, roughly 18 minutes
after activation; MQTT remained 0. The 30-second samples do not exclude brief
disconnects between observations.

**Host test ended; stock car session intentionally retained.** The final periodic
sample was at **15:05:58 Moscow**, still TCP=1. The next ADB property read timed
out; both the main process's restoration attempt and the independent guard
also timed out at their initial property read, before sending a disconnect or
profile change. The main process exited with status 1. This demonstrates that
the host rollback guard cannot guarantee restoration when ADB is unavailable.
No `restored.json` marker was written.

The owner subsequently instructed: stop the Mac test and leave the car running.
At **15:15:24** a fresh read-only check found no owner/guard/log-reader host
process, cloudmanager PID **113**, stock TCP **1**, profile **double_apn**,
APN1-disable **1**, registration **2**, token flag **1**, and validated Wi-Fi
network **105** (previously 104). No further ready/disconnect/profile event was
sent. The retained connection proves operation without the host observer at
that moment; it does not establish the complete network-change timeline,
sleep/wake recovery or cold-boot persistence. Evidence:
`host-ended-car-active.json`, `host-ended-car-still-active.json` and
`guard-output.txt` in the run directory. Do not describe this as a verified
rollback or leave the old run labelled active.

### Denza Apps «Облако» tile: implemented, not yet on the car (2026-09-23)

The on-vehicle adapter this experiment pointed to is built as the twelfth
dashboard tile, «Облако», in `apps/denza-apps/.../feature/cloud/`. It is
unit-tested and matched against the Luminofor board. **It has not been
installed on the car or live-tested.** Factory `cloudmanager` still owns
identity, telemetry, timers and the cloud protocol; the tile only translates
Wi-Fi for it and holds the car's Wi-Fi-in-sleep setting.

The short press toggles the link. The long press opens a panel with two
independent switches:

- **«Поддерживать связь с облаком»** — the app's own wish, stored in
  SharedPreferences `cloud_link`, absent meaning off.
- **«Держать Wi-Fi включенным»** — the car's `Settings.Global`
  `byd_off_wifi_switch`, always read back from the car. On is
  `settings put global byd_off_wifi_switch 1`. Off is
  `settings delete global byd_off_wifi_switch`, which restores the absent
  stock default rather than writing a zero. The panel prints its cost under
  it: «На стоянке аккумулятор может разряжаться быстрее».
  The 12V draw has not been measured.

Every car call goes through the app's passive local ADB shell
(`DenzaLocalAdb`) and uses the commands the held run used:

- the `RADIO_CONFIG` profile broadcast;
- `service call cloudmanager 1 i32 4|-5`;
- one tagged read of `persist.sys.byd.apn_type`, `ro.build.byd.apn_type`,
  `persist.radio.net.lte.apn1.disable`, `pidof cloudmanager`,
  `service call cloudmanager 7` and the retention key.

The app UID itself only gains `ACCESS_NETWORK_STATE`, a normal permission.

Contract (`CloudLinkCore`, held by `CloudLinkCoreTest`):

- **Off never touches the car on its own.** A new install, or a switch that
  was never on, sends no `-5` and leaves an existing connection up. Taking
  over is the driver's explicit «on». Switching on over a client that already
  reads TCP=1 sends nothing.
- **Usable internet** (`CloudNetwork`) is a default network with
  `NET_CAPABILITY_VALIDATED` that is either Wi-Fi or mobile data. Since build
  59, MCC 460 no longer excludes mobile internet: operator identity does not
  prove a private BYD APN. Actual APN1/APN3 state is guarded independently;
  connected or transitioning stock APNs defer public-profile activation.
  **Only Wi-Fi is proven on a car.** Mobile data from a local SIM was added on
  2026-09-23 for owners with such a SIM to test (see below).
- **On**: when the car is not on `double_apn` with APN1 disabled, send the
  profile broadcast, wait 3 s and read the profile back. Then, with usable
  internet and TCP≠1, send one `4`.
- **Paired loss**: when usable internet has been gone for 30 s, send `-5`, but
  only under `double_apn` and only if the gate is not already known closed.
  When internet returns after that, send `4` at once. Wi-Fi giving way to
  mobile data is not a loss: the client's socket drops with the old network,
  and its own reconnect, or the repeat after the 90 s settle, carries it
  over.
- **Repeats**: the stock `BYDMultiApnConnReceiver` sends `-5` on any
  `CONNECTIVITY_CHANGE_FUNCTION` whose APN3 is not CONNECTED. So a car on
  usable internet that reads TCP≠1 is told `4` again, once the disconnection
  has lasted 90 s, with a backoff of 5, 10, 20, 40 and then 60 minutes. The
  backoff resets on TCP=1. A new `cloudmanager` PID gets `4` at once, because
  the framework replays only recorded APN states.
- **Refusals wait too**: a `4` that did not happen counts against the same
  backoff. That covers a profile the car did not write back, a Binder that
  refused, and a shell that failed. Without this, a car that keeps refusing
  would get the profile broadcast once a minute. Only the driver's own press
  skips the wait.
- **A car with its own cellular link is left alone**: when
  `net.lte.apn1.state` or `net.lte.apn3.state` reads `connect`, which is the
  stock receiver's own test, the adapter sends no profile, no `4` and no `-5`.
  Explicit off still restores the build profile. This matters for other cars
  with a working SIM, not this one.
- **Explicit off**: `-5` if on `double_apn`, 1 s, then the build profile
  (`ro.build.byd.apn_type`, `triple_apn` here), read back. This is the only
  path that restores the profile.
- `com.byd.tcp.cloud.server.status` is registered as a hint to re-read, never
  as a trigger. Its delivery to an ordinary app is still unproven.
- **Readings**: on every event; 5, 15, 30 and 60 s after a `4`; then every 60 s
  while on usable internet without TCP and every 5 min otherwise. Readings run only while
  the foreground `CloudLinkService` runs, and it runs only while the switch is
  on.

The tile says «Выключено», «На связи» (TCP=1), «Нет интернета» (on and
waiting, not a fault), «Подключается» (working), or the press the car refused: «Не
включилось» / «Не выключилось». Pressing a refused tile asks for the same
thing again rather than reversing it. A refusal clears as soon as the link is
seen up by any path.

Lifecycle: ACC-off terminates the app. While parked, the link belongs to the
stock client, and the Wi-Fi switch decides whether it stays reachable. On wake
or boot, `RuntimeRecoveryReceiver` → `startAdbRuntime` restarts the service,
which reconciles; with the gate unknown it waits the 90 s settle before a
`4`.

Diagnose with the «Облако=» line of the «Сервис» report, which a screenshot
can carry from a car nobody here can reach. It gives the switch, the network
kind, the SIM's operator code (never IMSI or ICCID), the profile, TCP, whether
a BYD cellular APN is up, Wi-Fi in sleep, the adapter's gate and attempts, and
any refusal. With ADB:

- `adb logcat -s DenzaCloudLink`
- `adb shell service call cloudmanager 7`
- `adb shell getprop persist.sys.byd.apn_type`

Stop with the panel switch, or by stopping the service. Stopping the service
leaves the gate as it is.

Edge cases the code does not close, known and accepted for the first live run:

- **Link on, Wi-Fi retention off.** At ACC-off the radio policy turns Wi-Fi off
  and the app is terminated within seconds, before the 30 s grace can send
  `-5`. The gate stays open while the car is parked with no network. The stock
  client retries, and the keepalive counts ticks without TCP towards the
  reboot described below. This is the same state the car was in before the
  feature. On wake the adapter recovers within its 90 s settle.
- **Wi-Fi retention on, parked out of range.** Wi-Fi stays on and scans with
  nothing to join. That costs charge and brings no link, and the reboot
  counter runs.
- **BYD's self-start switch.** «Disable background Apps» is reset by every APK
  install. While it blocks the app, nothing restarts the adapter after a
  wake, and the link rests on the stock client and Wi-Fi retention alone.
- **Remote commands.** A connected stock client also receives the official
  app's commands and processes them through its own checks. That is the stock
  design; the adapter neither adds nor filters anything.
- **Other builds.** Off restores `ro.build.byd.apn_type` when it is
  `triple_apn` or `double_apn`, and otherwise `triple_apn`, this car's
  profile. Only this car's build is proven.

#### Mobile data from a local SIM: built, not tested (2026-09-23)

The initial implementation assumed that the public profile could also ride a
local operator's default mobile network: `addIPRoute` attempts an APN3 route
and ignores its own failure. This is a hypothesis, not established arbitrary
transport support. The 2026-09-24 review below adds APN guard, DNS-interface and
SIM-identity prerequisites. The initial unverified questions were:

- whether this car's modem takes a non-Chinese SIM and brings up data;
- whether registration and the phone's data behave as they did over Wi-Fi.

The owner has no such car; owners on the forum who have one will test it. A
test report should include:

- the «Облако=» line before switching on, and one minute after;
- «На связи» or not after driving away from Wi-Fi, and how long it took;
- whether the phone app shows fresh charge and range while the car is on
  mobile data.

Still open:

- live acceptance of an identified adapter build (later owner reports below
  establish usage/symptoms but do not identify the deployed APK);
- broadcast delivery to an ordinary app;
- recovery across a cold boot and across a native restart;
- long-term stability;
- 12V draw with Wi-Fi retained.

### Implementation review, 2026-09-24: recovery defects before live acceptance

Reviewed committed implementation at
`abd903896bcde078668ccc936573cf204684fca0` (build 54), including local-SIM change
`88ebad760f82e2c7e326cadfde4249e2d586c0e2`. Product source was unchanged during
review; the pre-existing uncommitted cellular-research addition in this document
was preserved. No device command, install, profile change or cloud operation was
performed. These findings qualify the implementation description above; they
are not fixes or a live-car result.

The main architecture matches the research: native cloudmanager owns the
protocol, profile changes are read back, commands are serialized, fresh installs
do not automatically close the retained connection, and repeated ready events
have settling/backoff rules. The selected 38 cloud/tile/recovery-manifest JVM
tests passed. Additional fault scenarios executed the **actual compiled**
`CloudLinkController`, `CloudLinkCore`, `CloudLinkProtocol` and status classes
with isolated Context/clock/network/preferences/ADB/report-refresh stubs:

1. **Failed explicit disable is not durable.** Repository `setCloudLinkEnabled`
   persists false and stops the service before the queued disable is confirmed.
   An ADB failure is remembered only in `CloudLinkRuntime.failure`. On a later
   process start the false preference suppresses all automatic work, losing the
   distinction between a fresh off install and an unfinished disable. A scenario
   with unavailable ADB, loss of volatile status and a later TCP=1 reading showed
   the tile as "Выключено" with no restoration command. Keep a durable pending
   disable until -5/profile restoration is verified; do not auto-disable a new
   install that never acquired ownership.
2. **Binder exception parcels are accepted as success.**
   `CloudLinkProtocol.notifyAccepted` checks only for `Result: Parcel(`. An
   exception parcel beginning with `ffffffff` was accepted by the actual parser;
   the controller marked its gate OPENED and cleared the error. Validate the
   known successful reply and reject exception/malformed replies; the TCP
   getter's separate exception check does not protect the notification path.
3. **Missing or failed network-loss processing is not repaired.** The service
   schedules `networkGone` only on a usable→unusable edge. A start already
   offline schedules no loss check. Periodic reconciliation returns immediately
   for `!network`, so neither that case nor a failed -5 is retried. The isolated
   scenarios retained UNKNOWN/OPENED respectively, with zero successful close
   commands even after a later simulated ten-minute reconciliation. Track
   pending network loss and reconcile it with a grace period and bounded retries.
   A further case shows that `automatic` skips even a computed close plan while
   the getter still reports TCP=1: its `car.connected == true || attempt(...)`
   short-circuits execution. After TCP later becomes 0, offline reconciliation
   still does not close it. The controller harness reproduced both stages with
   no writes; this needs independent handling of close actions and TCP success.
4. **A queued network-return event ignores the current network reading.**
   `CloudLinkController.networkReturned` discards the `network` argument read by
   `automatic`; `CloudLinkCore.networkReturned` hardcodes true. A queued return
   processed after the network is gone can send 4. The actual controller did so
   with the injected current network continuously NONE. Recheck current
   eligibility when processing the event and before the ready write.
5. **Read failure leaves an indefinitely live-looking status.** `refresh` and
   `automatic` log a thrown shell read but retain the old `CloudLinkRuntime.car`.
   `CloudLinkStatus.snapshot` prioritizes its cached TCP=true without checking
   reading age, even when internet is absent. The fault scenario showed
   "На связи" with a ten-minute-old reading and an ADB error. Represent failed
   or expired observations as unknown/stale rather than current connection proof.
6. **A settings-read error becomes Wi-Fi retention off.** `parseRead` maps every
   nonempty Wi-Fi answer except `1` to false, including a textual
   `SecurityException`. An off operation can therefore accept a failed read as
   successful verification. Recognize `0`/`null` explicitly and leave unexpected
   output unknown.

The controller harness replaces all device/Android boundaries and never opens
a socket. Its restart scenario simulates loss of volatile UI fields, not a
real Android process restart; the durable false-preference/startup behavior was
also traced in the repository. The offline-start scenario exercises controller
reconciliation; absence of the service's initial loss timer is established by
source inspection. Evidence and runnable harness sources are under
`captures/telematics-20260924/implementation-review/`.

**Local-SIM support remains a hypothesis, with more prerequisites than transport
validation.** An awake vehicle with a working public mobile-data bearer may be
able to use the same cloud path, but the Wi-Fi proof retained the factory SIM
identity. Acceptance with another SIM's IMSI/ICCID has not been tested. The
public resolver also has an APN3-interface binding branch; the established
Wi-Fi case had an empty APN3 interface. Ignoring an `addIPRoute` error alone
does not prove arbitrary cellular routing.

For parking there is a concrete missing part: ordinary mobile data is APN2,
which `BydDcTracker.handleAccOff` can clean up after its default 3-second delay
when user data is enabled, GB is inactive and no DATA_CONTROL hold exists. The
adapter sends no such hold and its ordinary app is itself terminated by
QuickBoot. Keeping Wi-Fi on does not retain APN2. Thus a successful driving
test on a local SIM must not be described as a parked-SIM/cloud result.

The original SIM operator MCC rule was only a heuristic: `460` identifies a mainland
operator, not provisioning for BYD's private APN. Conversely, null/empty operator
codes were accepted as local mobile data. Build 59 removes that eligibility
heuristic and retains actual stock APN protection. Keep the public-mobile
path explicitly experimental until SIM identity, routing, handover, startup
and parking have been measured on the target configuration.

### Owner-reported off/on stall and local-SIM failures, 2026-09-24

The owner reports that on an awake vehicle, switching cloud off then on left
the spinner running. Switching Wi-Fi off, closing the app, switching Wi-Fi on
and reopening the app restored it. The owner cannot recall whether the settings
switches were grey or still interactive. Other owners reportedly have no
successful local-SIM cloud result, whereas some Wi-Fi attempts succeed. These
are owner-reported observations; the deployed APK identity, logs and the forum
vehicles' firmware/network states have not been captured. The car will be
connected later. No live commands were issued for this investigation, and
disconnecting Wi-Fi is explicitly excluded without a separately agreed test.

**What the spinner establishes.** `CloudLinkStatus.snapshot` and the dashboard
intentionally leave an enabled, internet-eligible, not-connected client in
STARTING indefinitely. There is no elapsed-time/error transition for failed
native registration. Core retry intervals grow from 5 to 60 minutes. This is
distinct from `CloudLinkRuntime.busy`, which greys both settings switches while
an explicit command runs. The reported spinner does not distinguish the two.

**Off/on remains a hypothesis to capture, not a diagnosed native fault.** Off
sends -5, waits one second and restores the build profile; on re-reads TCP and
skips its entire activation plan if it is still 1. Neither path waits for a
confirmed transition to TCP=0. The native -5 handler clears the gate and queues
a disconnect event; receipt of the Binder reply is not a completed TCP teardown.
Thus delayed teardown and a later framework APN3-disconnected event are concrete
conditions to inspect. The latter can close the synthetic gate after a ready,
then the adapter's backoff delays the next attempt. None of this proves which
event occurred yesterday. The recovery changed network events and app lifecycle
together, so it does not isolate a cause. Merely closing the UI also need not
kill the foreground service or reset its core.

**An additional mobile-data prerequisite is visible in the adapter itself.**
`CloudLinkCore.reconcile` returns immediately on `car.cellular`, defined as
APN1 **or** APN3 reporting connected; this does not require cloud TCP success
and ignores the operator classification used by `CloudNetwork`. An isolated
run supplied an eligible MOBILE default network, `triple_apn`, APN1 down, APN3
connected and TCP=0. An explicit on and reconciliation at a simulated one hour
produced no profile/ready command, zero attempts and "Подключается". The native
gate under `triple_apn` requires APN1/event 1, so APN3 connectivity alone does not
establish the cloud path. This proves a conditional adapter limitation, not the
actual state of the forum cars. Do not simply remove the cellular guard: the
replacement policy must distinguish a working stock cloud session from a bearer
that cannot reach it, and preserve genuine stock connectivity.

For a failing local-SIM case the first read-only evidence should distinguish:
default network validation/transport and operator classification; APN1/APN3
states, selected profile and actual adapter attempts; native DNS/route binding;
then TCP/TLS/registration error stage. SIM identity acceptance is untested, not
a confirmed server rejection. APN2 cleanup after ACC-off is a separate parking
limitation and cannot explain failure while the car is awake.

The existing service report already provides network, profile, aggregate BYD
cellular state, TCP, attempts, busy state and reading age. It does not provide
native DNS/registration stage or separate APN interface states. Those require a
bounded, redacted log/property snapshot. Take it in the stalled state before any
restart. A controlled off/on reproduction can be planned after baseline and
deployed APK identity are read; it is not authorized by the offer to connect.
Additional offline evidence: `implementation-review/followup-scenarios.json`.

### Forum photos: OPENED with triple_apn on both transports, 2026-09-24

The owner supplied two photographs of another Z9GT's service report and
confirmed the model; its firmware version is unknown. Both identify Denza Apps
0.7.0-alpha build 54, but no APK hash has been obtained. Common visible fields:
enabled / "Подключается", refusal none, SIM operator 25901, current and build
profile `triple_apn`, APN1 enabled, BYD cellular no, cloudmanager PID 16783,
TCP=0, Wi-Fi retention yes, adapter OPENED, attempts=1. One view reports
validated Wi-Fi with last ready 25 seconds ago; the other validated MOBILE with
last ready 48 seconds ago. Both defer retry by approximately four minutes.
The bottom "Прочитано" age row is outside the photographs. Native properties
and adapter state are cached while network data is read at report creation;
these are not a simultaneous live property dump. File names do not establish
the timing/order of the photographed events.

This shifts the first diagnostic target to the profile/ready invariant. In the
reviewed native build, ready=4 requires `double_apn`; in `triple_apn` it does not
open a closed gate. `OPENED` in the report is the application's own record of
an accepted notification, not a read of that native gate and not TCP success.
The known product source checks `double_apn` + APN1 disabled before sending 4
when a profile switch was required. An unchanged triple profile would fail
that check and should not create this ready record. A profile returning to
triple after a valid read/ready is therefore a concrete candidate, but the
photos cannot identify when it changed or who wrote it. A Binder exception
accepted as success would not by itself explain why the profile is triple.

The earlier cellular-guard hypothesis is not supported by these snapshots:
BYD cellular is reported false, while both transports pass the adapter's
network eligibility rule. Native DNS, cloud reachability and acceptance of the
SIM identity remain unmeasured. The photos do not establish a SIM-specific
failure; they expose the same profile mismatch on both transports. They also
show only the first minute of an attempt, not how long the failure persisted.

An additional offline controller scenario first supplied double profile and
accepted ready, then injected triple profile at 25 seconds and changed the
eligible transport at 48 seconds. The actual controller kept OPENED/one
attempt/"Подключается", with no new write at either time. Reconciliation at
300 seconds restored double and attempted ready again. This reproduces the
displayed combination and verifies that profile drift waits out ready backoff;
it does not prove that firmware restored the forum car's profile. Useful next
evidence is a timestamped profile/readback/ready history and native logs around
`onMultiApnTypeChange`, `SIM_AND_APN_TYPE_CHANGE`, `notify_nw` and TCP changes,
including current reading age and the target firmware version. No Wi-Fi change
is required for that capture.

Transcription, original file paths and SHA-256 values are retained in
`captures/telematics-20260924/implementation-review/forum-photo-observations.json`;
the originals remain in Downloads. The isolated reproduction is
`implementation-review/profile-drift-scenario.json`. Product code and vehicles
were not changed.

### Build 55 recovery fixes and owner-car timing checks, 2026-09-24

The owner requested fixes for the confirmed implementation defects and fewer
round trips with forum testers, then explicitly authorized toggling the cloud
in the installed app with varied pauses. Wi-Fi changes remained excluded.

**Code changes, not yet installed on the car:**

- `CloudLinkRequest` persists desired state, pending disable and an outstanding
  TCP teardown obligation together, on the worker, before vehicle writes. An
  interrupted off is resumed before a new on. The foreground service stays up
  for cleanup even when the desired state is off. A new install with no prior
  enable has no automatic cleanup obligation; an untouched working stock link
  is not required to disconnect merely to stop this adapter.
- The explicit off path waits for an observed TCP=0 before restoring the
  profile. Waiting is bounded; a missing confirmation leaves a durable pending
  operation and a visible failure, retried with a 30-second interval. Active
  real BYD cellular remains protected by the existing guard.
- Native notifications accept the exact recorded `Result: Parcel(NULL)` reply;
  exceptions and unknown replies are failures. Profile checks occur immediately
  before ready and again after its reply. A later profile mismatch invalidates
  the adapter's OPENED claim, without resetting the retry budget or starting a
  loop of profile writes against another firmware owner.
- Initial/failed network-loss handling is reconciled while offline, including
  when TCP still reports 1. Queued return events use the current network value;
  network eligibility is checked again before ready. A late loss action does
  not close a network that has returned; explicit owner off remains effective.
- Failed/expired readings no longer show cached TCP as a current connection.
  Unknown Wi-Fi setting output stays unknown. Polling is once a minute while
  online, every 30 seconds for offline/pending cleanup; readings expire after
  90 seconds. An unconnected client stops displaying an indefinite spinner
  after the 90-second settling period and shows a failure while bounded recovery
  continues. The main tile, like the settings switches, ignores another press
  while the current explicit write is executing.
- `CloudLinkDiagnostics` retains the last 96 timestamped controlled events
  across process restarts and exports one text report to
  `Download/Denza Apps/denza-cloud-report.txt`. It includes firmware/app build,
  operator code (not IMSI/ICCID), network classification, APN states/interfaces,
  profile, native step/registration code, TCP, requests and command outcomes.
  It never exports native payloads, VIN, tokens, key material or arbitrary shell
  output. Export failures are isolated from cloud operation and shown in the
  technical page. A never-used cloud feature does not create a Downloads report.

The ordinary-SIM eligibility policy and native cellular guard were **not**
relaxed. The forum photos instead exposed a common profile mismatch. These
changes do not establish that every local SIM works, retain APN2 during parking,
or identify the firmware component that may restore triple profile. Rewriting
native DNS routes or repeatedly overriding a competing profile owner is not part
of this fix.

**Live timing checks exercised the previously installed APK only.** Its version
was `0.6.2`, build 53, SHA-256
`df83c46d235aedc0af4f7b67b8ec7c24a4e57cf509d97477e11a6aa427656600`, unchanged
before/after. Its feature source identity is not established by that version
number, and must not be assumed identical to reviewed build 54. Three UI-driven
off/on cycles used approximately 28 seconds, 1 second and 5 seconds between
presses. First connected samples were at 5, 15 and 5 seconds after on, with TCP=1
and double profile still observed at 90 seconds in each case. No endless spinner
or unexpected profile reversal was reproduced. These are three timing smoke
checks, not exhaustive fuzz coverage or acceptance of build 55.

Final read at 09:39:47 local: enabled true, double profile, APN1 disabled,
APN1/APN3 disconnected, cloudmanager PID112, TCP=1, Wi-Fi retention 1. Cloud was
left enabled; the owned Mac log reader was stopped. No Wi-Fi/radio/router/ADB-key
change, direct native notification or APK installation occurred. Captures,
permission scope, per-case timing and UI-capture limitations are retained under
`captures/telematics-20260924/toggle-acceptance/`. Raw native logs and the gateway
screenshot are private local evidence and must not be forwarded unredacted.

**Build 55 validation:** all 1,550 `denza-apps` JVM tests passed, `assembleDebug`
and `lintDebug` passed (lint warnings remain). Regression tests inject failed
reads, Binder exceptions, delayed teardown, profile changes, stale network
events and persisted request recovery. They test our response to those inputs;
they do not assert that an injected transition happened on a forum vehicle.
The pinned APK is under `captures/telematics-20260924/build55/`, SHA-256
`52e8aed4ca816eae5c981a0ae9f20b7d4ee283d2cee8a805ba4c78304f33ee1d`.
The MediaStore export and new recovery lifecycle still require on-device
acceptance. Next: identify/install that APK on the owner's car for the same
bounded cycles, then let a local-SIM owner make one attempt and return the single
report file if it fails. No Wi-Fi interruption is needed on the owner's car.

**Later owner recollection: the failure may have followed sleep.** The owner
recalled that automatic cloud recovery failed after the car slept, then manual
opening and an off/on attempt hung; the owner explicitly marked that ordering
as uncertain. Treat this as a reproduction lead, not an observed root cause.
The three awake toggle checks above do not cover it.

The current source already routes process start, `BOOT_COMPLETED` and a
live-process `SCREEN_ON` through `DenzaRuntimeCoordinator`. Passive ADB checks
are bounded to 0/4/8/16/32 seconds; a trusted result reaches the cloud service
through `DenzaAppRepository.startAdbRuntime`. A failure to obtain trusted access
within that window leaves cloud startup unperformed. This is distinct from a
running cloud service failing to reconnect. The cold cloud core also gives the
native client 90 seconds to reconnect before automatic ready; with 60-second
polling the first attempt can be around 120 seconds after a readable,
eligible disconnected state, absent earlier hints. Neither delay alone proves
an indefinite hang. Manual on bypasses that initial settling period.

On this firmware, the self-start setting is a deny-list, and an APK update
reinstates the deny bit; see `split-screen-findings.md`, "The self-start switch,
and a registrar that never registered". Being present/enabled in that list is
not proof of allowed recovery. The setting cannot be read from shell through
the protected provider/Binder. No setting was changed or fresh sleep induced.
A read-only log snapshot at 09:53:27 on September 24 contained only recent
buffer contents and no relevant earlier recovery events; it cannot establish
the setting at the reported failure. Evidence is retained privately in
`captures/telematics-20260924/wake-followup/`.

Build 55 therefore does not claim to repair blocked process autostart or ADB
readiness beyond the existing window. Its acceptance needs an additional
owner-coordinated natural sleep/wake: after installing, verify the system's
self-start deny switch is off, record the boot/recovery chain, and check cloud
recovery **before opening Denza Apps by hand**. Wi-Fi must remain untouched.

**Build 55 installed on the owner's car, 2026-09-24.** The owner then explicitly
requested installation and a downloadable APK. `adb install -r` succeeded;
the installed package reads `0.7.0-alpha` / versionCode 55, and its `base.apk`
SHA-256 matches the pinned build above. The old preferences and working stock
cloud session survived the update. `CloudLinkService` was observed foreground,
and the new MediaStore report was created and read successfully at
`/sdcard/Download/Denza Apps/denza-cloud-report.txt`.

The system self-start deny switch was visibly **false before installation**.
The already-open settings page misleadingly kept showing false after install;
closing/reopening that page showed **true**. It was restored to false through
that exact Denza Apps row, with UI readback. This is a fresh demonstration of
the firmware's update-time reset and the need to refresh that settings page.

One authorized UI off/on cycle used about 5.5 seconds between presses. The
report records `-5`, observed TCP=0, stock-profile restoration and durable
cleanup completion before the subsequent enable, then double profile and `4`.
TCP=1 was observed again by the report about 5.3 seconds after the on request;
the host's 15-second sample also confirmed it. This is a passing awake recovery
check on build 55, not proof of sleep/wake recovery or ordinary-SIM operation.
Captures and the installed identity are under
`captures/telematics-20260924/build55-install/`. A user-facing APK copy is at
`/Users/dmitry/Downloads/denza-apps-0.7.0-alpha-b55.apk`.

For remote diagnostics, enable cloud and allow the failed connection attempt
and first retry to run for about six minutes, then send the one file
`Download/Denza Apps/denza-cloud-report.txt` from the car's file manager.
`Сервис` → `Технические сведения` → `Облако` contains the export status in
`Отчёт в Загрузках`. No special diagnostic APK, ADB access or screenshots are
required when that file is saved successfully. Do not forward the separate
private raw host captures as the diagnostic file.

The owner clarified that forum users have neither messengers nor mail on the
car. Their support paths must be USB-file transfer or a photograph of the
technical screen. The full report can be copied from Downloads to a USB drive
with a file manager; a photograph of the current technical page captures only
the visible state, not the persisted event history. A USB destination picker
and a compact photo-oriented summary are product options, not implemented in
build 55. Requiring another transfer app is not the chosen support flow.

Build 55 has no in-app share action yet: the technical row only reports the
saved file's path/status. Read-only package resolution on the owner's car
confirmed LocalSend (`org.localsend.localsend_app`) is installed and handles
`ACTION_SEND` with `text/plain`; Android DocumentsUI handles opening a text
document. An existing transfer route is to choose the report in LocalSend,
send it to the owner's phone on the same local network, then forward it from
the phone. The receiver discovery and actual file delivery were not exercised;
no report was sent to another device or person during this check. A future
in-app share action should hand the report URI to the system chooser with a
temporary read grant, leaving destination selection and sending to the owner.

### Build 56: larger bounded diagnostic history, 2026-09-24

At the owner's request, the history limit grows from 96 to **512 events**, with
a second limit of **256 KiB in UTF-8 bytes** for the serialized history. Oldest
whole entries are removed when either limit is reached. Both limits apply
when loading saved history and when recording new events. Build 55's existing
history is retained; no new filename or log rotation is introduced. The same
Downloads report is overwritten and contains this history plus its small
current-state header. The 256 KiB cap is on history, not the combined report
including that header.

Only history retention and the internal build counter changed in the product.
The 0.7.0-alpha version name, cloud connection behavior and report path stay
the same. Focused tests cover oldest-event eviction, multibyte byte limits,
reloading and continued writing, and keeping the previous 96-event history.
The owner authorized replacing the APK asset in the existing GitHub alpha
release while preserving its description. This update does not include a
vehicle install or a sleep/SIM experiment.

Validation passed: 1,552 JVM tests, `assembleDebug` and `lintDebug` (existing
warnings retained). The APK asset in `denza-apps-v0.7.0-alpha` was replaced
with build 56 and independently downloaded; SHA-256
`c11e158177fcc6b0a03429fc073e77d231b1e644a0b5e4c9ea089c8bae434be9` matched.
The release description, title, tag and prerelease flag were verified unchanged.
Evidence and the pinned APK are in `captures/telematics-20260924/build56/`.
The owner's car remains on the previously installed build 55.

### Forum report: incomplete cloud-service response, 2026-09-24

The owner relayed another user's wording "неполный ответ сервера". The product's
matching message is `Неполный ответ облачного сервиса`, emitted locally by
`CloudLinkController.read` in builds 55/56. It is not an HTTP response or a
cloud rejection: the guard requires readable TCP, APN1-disable flag, a known
double/triple profile and recognized APN1/APN3 state strings. Any unknown field
produces the same message and prevents that operation from proceeding.

A concrete compatibility concern introduced by that guard is **absent APN
state properties**. A successful `getprop` can return an empty value; the parser
discards it and produces null, which the new guard refuses. In the matching
firmware `BYDMultiApnConnReceiver.java:156` reads APN3 with a `disconnected`
default, and retained `BydDcTracker.java:535` likewise reads APN1 with that
default. This difference is established by code; it is not proof that this
forum car has missing properties. Other possible failing fields are an absent
APN1-disable flag, another profile spelling, or an unavailable/unrecognized
native TCP getter. No model, firmware or report from this affected car has yet
been provided for this particular report.

The exported report stores the partial parsed state **before** the guard
throws: `profile`, `apn1Disabled`, `apn1`, `apn3` and `tcp` identify which
prerequisite is unknown, alongside firmware/build. It cannot distinguish an
empty property from a nonempty unsupported spelling; that needs an improved
typed read diagnostic. A fix must distinguish successful absent-property reads
with a proved firmware default from incomplete/failed shell reads. No guard
relaxation or new release was made in this diagnosis.

**Follow-up photographs localize the guard failure to APN1.** The two owner-
supplied photos named `photo_2026-09-24 11.12.37.jpeg` and `11.12.39.jpeg` show
build 56, validated Wi-Fi, trusted ADB, double profile / build triple, APN1
disabled, native PID 120 and TCP=0. The state row is **APN1 `?` / APN3
`disconnected`**. These readings satisfy every other field of the guard;
`apn1State == null` is its failing prerequisite. Adapter UNKNOWN / attempts 0 /
no last ready means no ready attempt recorded by this process, not a claim
about the vehicle's lifetime cloud activity. The optional step/registration
`? / ?` does not trigger this guard.

The pictured fingerprint is
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260424.003947:user/release-keys`,
different from the owner's July-build reference. SIM operator 46001 is Chinese,
but Wi-Fi passes `CloudNetwork` before its mobile-SIM rule. The photos therefore
do not implicate that rule. APN1's raw property may be empty or contain a value
the parser does not recognize; `?` does not distinguish those cases. No raw
read from this car was supplied, and the photographed data cannot establish
that fixing the read alone will make cloud login succeed. Transcription and
source-image hashes are retained in
`captures/telematics-20260924/forum-apn1-unknown/observations.json`; originals
remain in Downloads. No vehicle operation or product change was performed
while assessing these photos.

The later text export supplied for this same photo case matches all identifying
diagnostic fields: build 56, April `20260424.003947` fingerprint, operator 46001,
PID 120 and APN1 unknown / APN3 disconnected. Its history spans 08:00:14–08:18:11
UTC on September 24 and repeatedly fails the local read guard before any
recorded `AnnounceReady`; adapter attempts remain 0. The optional unknown step
and registration code are not the guard's cause. This confirms a local
adapter compatibility failure in that build, not a demonstrated server
rejection. The export still cannot distinguish an empty APN1 property from an
unsupported or failed read. It predates the reported build-57/58 behavior and
is not evidence that the fix failed. The existing build-58 candidate is the
next applicable build; no new product edit follows from this older report.
The exact export (SHA-256
`8b09a1e43f4cc2b481e1fe99495aa267d6816c2751278f5661b45933c36fc4da`)
and comparison are saved under the same `forum-apn1-unknown/` evidence folder.

### Build 57: completed empty APN reads use the firmware default, 2026-09-24

At the owner's request, the adapter now handles a successfully read empty
APN1/APN3 property as `disconnected`, matching the retained stock readers'
default. Each APN command has its own completion marker containing the shell
exit code. The default is accepted only after the matching command completed
with exit 0; missing, truncated, conflicting, failed or unsupported replies
still prevent the adapter from proceeding. TCP, profile and APN1-disable flag
requirements remain unchanged.

The generic cloud-service error is replaced with a local field-specific
message, for example `Не прочитано с машины: APN1 (неизвестное значение)`.
The report records controlled `apn1Read` / `apn3Read` labels, including
`DEFAULT_EMPTY`, without retaining arbitrary shell output. Its existing
Downloads path and 512-event / 256-KiB history limits remain unchanged.

The regression cases cover successful empty properties, interrupted and
nonzero-exit reads, malformed/mismatched completion markers, unsupported and
conflicting values, the photographed field combination with an injected empty
APN1 hypothesis, and preservation of the native-cellular guard. The original
implementation failed four focused regression cases before the change;
afterward **1,560 JVM tests**, `assembleDebug` and `lintDebug` passed.

Candidate APK: `denza-apps-0.7.0-alpha-b57.apk`, version code 57, SHA-256
`ea470b5857aa3d584e7d41b719f5a397a617428aabc18fda0d07c0917ed7806d`.
Build logs, source hashes and the APK are retained under
`captures/telematics-20260924/build57/`; a copy is in the owner's Downloads.
This candidate was **not installed on a vehicle or published to GitHub**.
The forum photos establish APN1 as the blocking unknown field, but do not
establish that its raw value is empty. Connection on that car therefore
remains unverified; an unsupported value will now produce a precise error.

### Forum Wi-Fi / ordinary-SIM reports: native registration stage, 2026-09-24

The owner supplied Wi-Fi and SIM exports, then corrected this user's symptom
to `Нет связи с облаком`. Both files identify **build 56**, fingerprint
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys`,
operator **25901** and cloudmanager PID **16783**. They must not be conflated
with the earlier April-firmware / operator-46001 photographs. They do not
establish whether build 57 was subsequently installed.

Both exports have `failure=null`, `readFailure=null`, validated Internet,
`double_apn`, APN1 disabled, readable disconnected APN1/APN3, empty interface
names, TCP=false, step=1 and regError=3. Each recorded off/on sequence completed:
gone, confirmed TCP=0, restored triple profile, switched back to double, ready.
Wi-Fi ready finished at 08:09:27.434 UTC; the next changed state was mobile at
08:13:28.705. A mobile off/on finished ready at 08:14:05.398, followed by the
last history sample at 08:14:10.552. History suppresses unchanged readings;
these samples do not exclude a brief unobserved transition. `OPENED` records
the adapter's estimate, not a native gate getter. The build-57 empty-APN fix
does not address this case: these APN reads already succeed.

Additional offline analysis of reference cloudmanager SHA-256
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`:

- `send_complete` at `0x47d54` uses jump table `0x1e0dc`; key **211** selects
  `0x47f60`, which writes **step=1**. This branch does not test the completion
  status before writing the step, so it is not proof of successful delivery.
- The 211 receive branch at `0x57708` copies the reply status to object offset
  `0x35c`; `reset211Variable` copies it to `sys.tcp_reg_errcode` at `0x54c50`.
  Values >=2 take its failure/retry branch. The exact vendor meaning of **3**
  has not been established. In these reports it predates both attempts and
  survives their off/on cycles, so it cannot be called a fresh rejection.

This narrows the investigation to native registration/send behavior shared
by both network paths. It does not prove DNS, TLS, server registration or SIM
identity as the root cause. In particular, the recorded APN3 interface is
empty; a stale nonempty interface binding is not supported by these samples.
SIM identity remains a specific hypothesis even for Wi-Fi: the retained
registration codec/native comparison establishes that message 211 includes
IMSI and ICCID, independently of the transport. A replacement SIM could
therefore affect both paths. These exports do not contain those inputs, and
neither a replacement-identity rejection nor that meaning for code 3 is proved.
The useful next export needs bounded, redacted native events (send completion
status/key, 211 reply status and DNS/socket/TLS error categories), plus only
presence/status flags for registration and token, without identifiers,
credentials or packet bodies. More copies of the same adapter-state fields
will not resolve this gap.

The SIM report includes the Wi-Fi history prefix and is preserved with its
hash, comparison and selected reference assembly in
`captures/telematics-20260924/forum-25901-reports/`. The original Wi-Fi file
was read but disappeared from Downloads before archival; its observed header
is transcribed in `analysis.json`. No vehicle action or product edit occurred
in this comparison.

### Another ordinary-SIM report: code 3 appears during the Wi-Fi attempt, 2026-09-24

An additional export identifies build **56**, July `20260705.011226` firmware,
operator **25002** and native PID **114**. Unlike the earlier 25901 report,
`regError` is initially **null**: mobile at 09:08:54 UTC, Wi-Fi at 09:09:22,
and the verified double-profile read at 09:09:39 (step=0). `AnnounceReady`
completes at **09:09:40.304**; the next sample at **09:09:45.492** has step=1,
regError=3 and TCP=false. The later mobile sample at 09:11:57 has the same
state, followed by additional successful ready commands at 09:11:59 and
09:14:11. Current failure/readFailure are null, APN1/APN3 are readable and
disconnected, and both interface names are empty. The initial isolated
incomplete-read event at 09:07:12 is no longer the blocking failure.

This gives stronger temporal evidence for a registration failure shared by
Wi-Fi and mobile than a code that already existed before the attempt. In the
reference native code, a 211 reply status is copied to `sys.tcp_reg_errcode`,
and 3 takes the failure/retry branch. A fresh registration rejection is thus
the leading interpretation, with two limits: this forum binary has not been
hashed, and a parsed null does not prove the raw property was empty. The exact
vendor meaning of 3, and rejection specifically due to replacement SIM
identity, remain unproved. No TCP=true observation or APN3-interface binding
failure is established by this report. It does not show a build-58 result.

The original export (SHA-256
`b2dfecac92b3f6ba79fe61ba60e6ed3b826f90ccbe54e5121da2acebc6b3a8cd`)
and timeline are retained in
`captures/telematics-20260924/forum-sim-reports/b2dfecac92b3/`.
The already-published build 58 can add the native send/reply events; this
report alone does not justify a new control-path change or another build.

### Build-58 follow-up: fresh native registration replies with code 3, 2026-09-24

Two new exports from the operator-25901 / PID-16783 / July-firmware case
identify **build 58** and exactly match the published APK SHA-256
`35ce2a58ad7c9398c5fdfcdb075583e0cb9f8d7a361d6d5d3b02209870346cfe`.
These are new evidence, unlike the earlier build-56 status-only reports.
Both have no local read/control failure, recognized APN states, double profile,
APN1 disabled and native TCP=false. The second export contains the first
export's adapter and native histories as exact prefixes.

File names do not identify the transport at export time:

| File / export UTC | Latest observed transport | New native evidence |
| --- | --- | --- |
| `denza-cloud-report-sim.txt`, 09:42:11 | Wi-Fi, wlan0, IPv4+IPv6 | Socket errors 110 and 107 at 09:42:10; no fresh registration reply yet |
| `denza-cloud-report-wifi.txt`, 09:46:18 | Mobile, ccmni1, IPv4 | Successful send of 211 followed by fresh reply code 3, twice |

At 09:42:58 the native client receives ready=4 and reports public registration
and discovery DNS addresses. At **09:43:22.088** it reports connected=1;
**09:43:23.000** records TLS completion; **09:43:23.105–.131** records three
successful 211 send completions; **09:43:23.374** records
`registration_reply code=3` and `registration_failed code=3 retry=7`.
The adapter first observes mobile at **09:43:24.070**, only 0.696 seconds after
that reply. Thus the first reply is near the network transition: the last
earlier sample is Wi-Fi, but no per-socket interface binding was captured.
Do not claim it conclusively proves rejection separately on Wi-Fi.

After the recorded mobile transition, the client connects again at
09:45:02.628, sends 211 successfully at **09:45:13.820**, and receives another
**code-3 reply at 09:45:14.104**, followed by the failure branch (retry=4).
This establishes a fresh application-protocol registration rejection, not
merely a stale `sys.tcp_reg_errcode`, unreadable APN or permanent inability
to reach the server. The earlier socket timeout can coexist with the later
application rejection; it is not a sufficient explanation for the full trace.

Reference-firmware cross-check: the log mapped to `registration_reply` is
`211 reg_status %d`, emitted by the received-command-211 branch at
`0x57708–0x57734`; its status byte is copied from the decoded response.
`reset211Variable` at `0x54964` sends value 3 into the failure/retry branch
at `0x54b88`. The exact vendor-specific meaning of 3 remains unknown. These
logs do not identify a SIM/VIN mismatch, account restriction or other precise
server-side reason.

Both exports have SIM property shapes VALID, `app_reg_status=2` and
`token_flag=1`. Shape checks establish only numeric lengths, not that the
identities are accepted or match the native packet cache. The registration
summary field reads a persistent app-registration property; it is not the
current 211 result. In the reference code that property is updated by the
separate 505 response path (`0x57e30–0x57e98`). A stored token flag likewise
does not establish a working current session. The earlier
`tls_identity_check result=0` does not by itself explain this failure: the
subsequent trace reaches successful sends and decoded server replies. In
the inspected TLS setup branch (`0x7f62c–0x7f66c`), that check is logged and
not used as an immediate rejection of setup.

Evidence is pinned under
`captures/telematics-20260924/forum-paired-reports/2f6aa3530730/`, with source
hashes and timeline analysis. No car settings, service restarts, new cloud
requests or APK changes were made to analyze these exports.

**Next discriminating investigation (not executed):** `tcpReconnect` at
`0x4c96c–0x4ca50` checks the native 211 result at object `+0x35c`; values >=2
lead back to `send211`. It does not use the persistent app-registration/token
flags as a substitute for that result. Repeated adapter ready notifications
therefore do not resolve a stable registration rejection by themselves.
The reference 211 serializer logs the packet body's IMSI/ICCID bytes under
`[BYDCLOUD]pack` (`0x6e124–0x6e38c`). One supervised affected-car session could
compare those actual sent inputs with current SIM properties locally, returning
only equality/shape results. Existing build-58 reports intentionally omit that
tag and cannot make this comparison. A difference would justify testing cache
refresh with the same SIM; a match would make stale SIM cache an insufficient
explanation. Any service/head-unit restart is a separate controlled mutation,
with before/after PID, transport and a fresh 211 reply required as evidence.
Do not clear registration/token data for this test. A possible authenticated
discovery/login-only experiment remains unproved: the existing host probe is
pinned to the owner's VIN and discovery result and cannot be reused unchanged
on a forum user's car.

### Additional build-58 report: repeated code 3, changed PID and ICCID shape, 2026-09-24

The new export at **09:55:59 UTC**, SHA-256
`d7f0053927bcec519eb356fb6784bee4863f0a8d0c5107e266ce779b9ae58fd9`,
is build 58 with the published APK hash and July firmware. Its adapter history
contains the exact earlier operator-25002 / PID-114 report prefix. It is a
follow-up to that case, distinct from the paired operator-25901 reports.
There are **eight explicit native 211 replies with code 3** from 09:29:07.838
through 09:54:27.238, each followed by the failure branch. Successful TLS/send
events precede multiple replies. This confirms the same registration-failure
stage as the 25901 case, without establishing the same underlying cause.

New discriminators:

- Seven replies are from PID 114. By 09:54:06 the observed process is PID
  **9304**, whose native registration state starts at 10 before another
  successful 211 send and fresh code-3 reply at **09:54:27.238**. This makes
  one old process's stale memory an insufficient explanation of the entire
  trace. The reason for the process change and any head-unit reboot remain
  unknown. The brief TCP read failure during the transition has cleared.
- The current native summary at 09:55:46 has `imsiShape=VALID` and
  **`iccidShape=INVALID`**, unlike both VALID fields in the 25901 case.
  Build 58's own test means the nonempty ICCID property is not exactly twenty
  decimal characters; it does not record the length or offending characters
  and is not a vendor validity verdict. This late snapshot does not establish
  the property's earlier contents or the exact bytes sent in any 211 request.
  A subsequent API cross-check strengthens this limitation: AOSP explicitly
  defines `getFullIccSerialNumber()` as retaining hexadecimal characters,
  whereas `getIccSerialNumber()` stops at the first non-decimal hex character
  ([Phone.java, pinned source](https://android.googlesource.com/platform/frameworks/opt/telephony/+/15d844360c99d93c0925c1f4825351f6a5e8202b/src/java/com/android/internal/telephony/Phone.java)).
  The retained BYD writer uses the full getter. Therefore build 58's decimal-
  only INVALID label is not sufficient to call the property malformed even
  at the Android API level. Record length, decimal/hex shape and any trailing
  padding separately before proposing a format correction; the affected
  car's actual character pattern and server acceptance rules remain unknown.
- Current network metadata is **tun0 / vpn=true**, with MOBILE classification.
  VPN presence is established only at export, not across every earlier attempt
  and not as the cause of rejection. Native per-socket transport is not recorded.

Reference-firmware refinement: the 211 body copies twenty bytes from its ICCID
cache, while the helper `0x699f0` used by `0x6e00c` checks null/zero input rather
than enforcing decimal characters and length. `prepare` accepts a nonempty
property before copying into the fixed-size field. Thus our diagnostic's
INVALID value can coexist with a native packet being sent. The telephony writer
uses `getFullIccSerialNumber` (`BydGsmCdmaPhone.java:1218–1233`). The next
specific check is the property's shape and the actual sent field, not another
unqualified restart or a conclusion that the SIM itself is invalid. No changed
identifier or guessed padding/truncation rule is justified by this report.

Report, hashes, comparison and selected reference assembly are retained in
`captures/telematics-20260924/forum-additional-reports/d7f0053927bc/`.
No vehicle or product changes were made.

### Build 60 follow-up: code 3 with an 18-digit ICCID property, 2026-09-24

Export **13:56:11 UTC**, source SHA-256
`0f5b5d8f3a6116c948c65375366afc3d419f3d19b459af75b2d0c9d7927b5904`,
is the original build-60 APK (`e24af64d...`), before the display-only hotfix
(`4c45342e...`). The operator-25002 history continues under cloudmanager PID 9304.
There are fresh successful command-211 sends and received code-3 replies at
**13:55:28.424** and **13:55:52.278**, each followed by the failure branch.
This is a repeated current registration rejection, not just an old property.
The first reply is near the Wi-Fi/mobile transition; the adapter observes mobile
at 13:55:37.509, before the second send/reply. Per-socket transport is not recorded.

New diagnostic detail: the current ICCID property is **18 decimal characters,
with no trailing F**; IMSI is 15 decimal characters. This narrows the previous
INVALID shape observation, but does not establish the native cached/sent bytes
or that the length caused the rejection. No guessed padding or identity rewrite
is justified. At export the default network is validated mobile, `ccmni0`, IPv4,
without VPN; local read/control failures are null, profile is double and TCP is
false. The successful property reads do not mean cloud registration succeeded.

The display-only hotfix cannot change this registration outcome; another report
solely to confirm its installation would not distinguish the cause. Source and
selected events are retained under
`captures/telematics-20260924/forum-additional-reports/0f5b5d8f3a61/`.

### Build 60 hotfix: stale-reading flash on re-enable, 2026-09-24

The owner saw a brief red «Нет свежих данных» after off/on. The retained trace
`captures/telematics-20260924/fresh-reading-135119/report.txt` shows off confirmed
at 13:48:48 UTC, on requested at 13:50:22 (94 seconds later), and TCP true at
13:50:28, with no read/operation error. Off stops polling, so the cached reading
was older than the 90-second freshness limit when the enabled preference became
visible, before the operation's first fresh read.

`CloudLinkRuntime.snapshot` now distinguishes an in-flight operation awaiting a
fresh read from an actual read failure. While that operation is busy and no
read failure is recorded, an expired reading renders «Подключается». It cannot
render an expired TCP=1 as «На связи». Actual read/operation failures retain their
priority, and an idle stale reading still renders an error at the same limit.
No polling, network, retry or vehicle-write behavior changed.

Four regression cases exercise the recorded pause, expired TCP success during
refresh, genuine failures while busy, and the unchanged 90-second boundary.
The two transition cases failed before the fix. At the owner's explicit request,
the replacement APK keeps version `0.7.0-alpha.1`, code `60`, the existing release
description and tag; distinguish it by APK SHA-256 in the diagnostic report.
Release/build evidence lives under
`captures/releases/denza-apps-v0.7.0-alpha.1/fresh-reading-fix/`.

### Build 59: confirmed application fixes and cache-refresh investigation, 2026-09-24

The owner requested fixing supported application defects and continuing cache
analysis in the retained firmware. Local changes in `feature/cloud`:

- Validated mobile internet is eligible regardless of the reported operator
  MCC. `460xx` remains descriptive report metadata only (`MCC 460`), not a
  statement about physical SIM hardware or private APN provisioning.
- Actual connected/transitioning APN1/APN3 state defers activation. Before
  changing the profile, operations reread state and internet; if a stock APN
  or TCP connection appeared since planning, both profile and ready writes
  are skipped. Existing read failures still prevent activation.
- A fresh stock TCP=1 reading displays connected even when Android's default
  network lacks validation. Failed/expired observations, disabled state and
  pending teardown still take precedence. A successful current TCP observation
  also clears an old automatic-operation failure without requiring default
  network validation.
- The tile can show a **fresh native 211 rejection and its code**. Evidence must
  be from a completed capture of the same process, within 90 seconds and no
  earlier than the latest explicit on/off request or successful TCP observation. A later registration start
  or success supersedes it; repeated captures cannot extend its lifetime.
  TCP success, process change, incomplete captures and expiry prevent stale
  rejection display. `sys.tcp_reg_errcode` alone is never used for this message.
  This evidence changes display only, not retry/profile/identity decisions.
- Report format 3 describes SIM-property length, decimal/hex/other alphabet
  and trailing-F count. It does not call a non-decimal ICCID invalid. Only
  bounded metadata leaves the shell; full IMSI/ICCID values are not exported.
  The report still overwrites the same Downloads file and retains existing
  bounded histories.

Five regression tests first failed against the old behavior. The cloud suite
then passed, followed by the full app unit suite (**1595 tests, zero failures**),
debug assembly and Android lint. Shell classification tests execute the actual
generated POSIX script with stubbed Android commands and synthetic identities;
they verify both format and absence of full identifiers in output. No car
installation or GitHub asset replacement was performed in this follow-up.

Prepared APK: `/Users/dmitry/Downloads/denza-apps-0.7.0-alpha-b59.apk`, SHA-256
`aa048693d13283de684a1e94659832b61098c70b7d3db9b2268a43909a4368e9`.
Build/test logs and artifact manifest: `captures/telematics-20260924/build59/`.

**Cache refresh:** static inspection of reference SHA-256
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`
now follows lifetime and reset paths, not just the packet format:

| Path | What the inspected code does |
| --- | --- |
| Global initialization `0x73208` | Sets the packet singleton pointer at `0x91a88` to zero and registers an exit destructor. Its address is in `.init_array` at `0x8a880`; this is process initialization, not a Binder cache-reset command |
| Singleton accessor `0x6dac4` | Returns the existing object; calls constructor `0x6d9f4` only when the pointer is null. Whole-text direct-call scan found that constructor call only here |
| Sender construction `0x732c4 → 0x7340c` | Obtains the shared object and calls `prepare`; it does not create a separate fresh identity cache |
| Registration retry `0x4c818 → 0x4b150` | Uses the existing singleton and `prepare`, whose per-field skip rules were reproduced offline |
| `reset211Variable` `0x54964` | Handles registration outcome, queues/rebuilds protocol packets and retry state; the inspected packet builders retain the identity cache |
| `shutdown` / `bootcomplete` `0x4c6f0` / `0x4c73c` | Shutdown changes a main-object flag and connection state; bootcomplete enters reconnect/boot handling. Neither is a direct packet-object reconstruction |
| `systemInfoInd` `0x51c30` | Logs and returns; it is not a generic SIM-refresh hook in this binary |
| Environment reset `0x4ece0` | Clears cloud/upload flags and stored state, and calls `clearUnlockInfo` (`0x4f410`), which removes NFC/Bluetooth identity files. No identity-cache refresh was found in these reviewed paths; this broad reset is unsuitable as an automatic SIM recovery |
| Retained telephony `onUpdateIccAvailability` / `handleImsiReady` | Clears or repopulates `ril.csim.iccid` and `ril.imsi`; the inspected property writer itself does not invalidate the native packet singleton |

The inspected Java Binder interface lists 39 transactions and exposes no named
SIM-identity/cache-refresh operation. A whole-text scan of explicit cache-field
offsets and singleton references corroborates the lifetime picture. The scan is
**not complete pointer-alias or indirect-call proof** and does not establish
behavior of unmatched forum binaries. Therefore the result is “no narrow refresh
entry point found in the examined interface and paths”, not “none can exist”.

A new native process reconstructs this cache, but that is not evidence that an
automatic restart would solve registration: one retained report already has a
fresh code-3 rejection under a new PID. No restart, property/identifier changes,
environment reset or new vehicle/cloud traffic were performed. Future recovery
work must first distinguish current property values from the actual cached/sent
pair, rather than treating every code 3 as a stale-cache failure.

Reproducer: [inspect_identity_cache.py](../research/telematics-firmware/inspect_identity_cache.py).
Source hashes, scans and selected lifecycle/reset assembly are retained in
`captures/telematics-20260924/cache-refresh/`. The prior synthetic identity-body
reproduction remains under `iccid-offline/` below.

### Offline identity preparation and command 211 reproduction, 2026-09-24

The owner asked to exhaust offline analysis before requiring ADB on an affected
car. A bounded Unicorn harness now executes the **actual reference ARM64
constructor, identity preparation, MD5 and registration-body assembly** with
synthetic property values. Fifteen scenarios / twenty preparation attempts pass.
The firmware SHA-256 is
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`.
It stops at serializer entry `0x6e3b0`, capturing the 35-byte body passed by
`0x6e00c`; it does not execute networking or simulate a server verdict.

Reproducer: [verify_identity_native.py](../research/telematics-firmware/verify_identity_native.py).
Results, source hashes and selected older-client assembly:
`captures/telematics-20260924/iccid-offline/`.
Android object lifetime, allocation, logging, properties and libc copy/clear
operations are explicit stubs; native instructions and MD5 are executed.
Unknown call targets and syscalls are rejected, with per-call instruction/time
bounds. Object memory is initially poisoned and cleared by the actual constructor.

Confirmed reference behavior:

| Synthetic input / sequence | Actual native result |
| --- | --- |
| Twenty decimal ICCID characters | Copies all twenty into 211 after IMSI's fifteen bytes |
| Nineteen decimal characters | Copies nineteen plus a NUL byte; no decimal zero or `F` is appended |
| Nineteen characters plus `F` / `f` | Copies the suffix unchanged; no stripping or case conversion |
| Twenty-two characters | Copies only the first twenty |
| One digit, twenty `F`s, or other non-decimal text | Still builds 211; local acceptance is not server acceptance |
| Empty ICCID or empty IMSI on first preparation | Returns false; no 211 body is assembled by the send path |
| Both fields cached, then properties change or become empty | Reuses both cached fields in subsequent preparation calls |
| First call reads ICCID A but finds IMSI empty; next call sees ICCID B / IMSI B | Second call emits **ICCID A / IMSI B**; the failed first call does not roll back the ICCID cache |

The last row establishes a concrete mixed-identity mechanism, not evidence that
it occurred on a forum car. It requires identity availability/change between
preparation calls, which the sanitized reports do not record. A second narrower
branch also reproduces: when the first byte of cached **binary MD5(IMSI)** is
zero, `prepare` rereads IMSI even though its text is populated, while keeping
ICCID. This is checked with a synthetic IMSI whose MD5 starts with zero; its
presence in a user's trace remains unknown.

The local decompiled framework's `IccUtils.java` corroborates the format issue:
`bchToString` (`:87`) preserves hexadecimal nibbles, including `F`, while
`bcdToString` (`:24`) treats them differently. `stripTrailingFs` (`:565`) is a
separate operation, not an implicit property-read behavior. The previously
retained BYD property writer uses the full ICCID getter. Consequently build 58's
decimal-only `iccidShape=INVALID` cannot justify stripping `F`, padding a number,
or calling the SIM defective. The exact producer path on each affected firmware
and the server's accepted representation remain to be established.

Static comparison with the retained older Dolphin executable (SHA-256
`25fcfbcf4b81346ac5d5861308fc0577b7dfa484d8c9ae9227e65e27c2b5648d`,
rechecked) finds the same separate cache checks (`0x39ed4`, `0x39ffc`), fixed
15+20-byte registration body (`0x3a20c–0x3a24c`), and generic status-above-one
failure branch (`0x2d4f8–0x2d500`). That comparison does not supply a specific
meaning for server code 3 or prove matching offsets on other Denza versions.

Practical next offline work is to trace cache invalidation and SIM-event ordering
in the retained firmware, looking for an existing refresh path and whether the
identity pair can change across calls. Do not implement a guessed identifier
replacement or unconditional restart from this result. In particular the
operator-25002 report still rejects registration after a PID change, and the
operator-25901 report has both properties classified as decimal/expected length:
neither a single stale process nor an `F` suffix explains all available cases.
No vehicle, account, identifiers, installed APK or release was changed here.

### R-SIM terminology and configurable ICCID, 2026-09-24

The owner recalls installers configuring ICCID together with an rSIM. This is
technically plausible and programmable ICCID is a documented adapter capability:
the [R-SIM manufacturer's instructions](https://m.rsim5.com/instruction/183.html)
describe an Edit ICCID input; its
[product description](https://www.rsim5.com/newsview.php?id=81) also documents
entering ICCID. These primary sources establish that capability, not successful
operation on Z9GT or the meaning of BYD's registration error 3.

The local firmware path makes that distinction relevant: `BydGsmCdmaPhone`
obtains `getFullIccSerialNumber()` and writes it to both `persist.radio.iccid`
and `ril.csim.iccid` for phone 0 (`:1218–1233`). The native client reads the
latter property through its cache and places twenty bytes in command 211.
Consequently, an adapter-provided identity can reach cloud registration without
a separate editable ICCID setting in cloudmanager. No installer modification,
privileged property override or particular adapter configuration has yet been
established on any affected forum car. The inspected stock writer also refreshes
or clears the properties on SIM events; a persistent property alone must not
be assumed to control what cloudmanager sends.

`BydDcTracker.getSimType` (`:1439–1459`) additionally classifies the ICCID prefix
for cellular APN policy, including several China Mobile and China Unicom
prefixes. An effect on APN selection is therefore a separate firmware mechanism
from an application-level 211 rejection. No client-side evidence establishes
a universal server requirement for one ICCID prefix or original-SIM binding.

Historical terminology correction: earlier notes used "rSIM" to mean a Chinese
roaming replacement SIM. A programmable **R-SIM adapter** and a Chinese-issued
SIM are different possibilities; the user's word alone does not establish the
installation's hardware or the identity currently presented. Configurable ICCID
strengthens the reason to compare actual sent inputs with the intended identity
of that same vehicle. It does not justify assigning code 3 to a specific field,
inventing identifiers or changing the affected cars based only on the reports.

### The forum's R-SIM recipe, read against the 211 evidence, 2026-09-24

The owner reports that forum users get the official app working with an
"R-SIM" plus further steps, and that the R-SIM "falls off" afterwards. Public
Russian-language guides describe the method. This section records the earlier
guide-based hypotheses; the later complete-login experiments below independently
test one changed IMSI suffix and one changed ICCID, without examining an R-SIM.

**What an R-SIM is.** A programmable interposer, originally sold to bypass
iPhone carrier locks: a thin chip glued to an ordinary SIM that intercepts the
modem's reads of the card's identifiers and answers with programmed values,
while the real SIM still authenticates to the local network. It is configured
from a phone app. Older variants (Wellsim Pro, USIM, "RSIM Club") replace the
ICCID only; **RSIM 18 has a "MIX mode" that replaces ICCID and IMSI together**.
Sources: the community
[BYD FAQ](https://gist.github.com/GraphTechJS/b9ea28ff15b746d839b8a843d6936fee)
and general R-SIM descriptions
([icoola](https://icoola.ua/ru/blog/sho-take-r-sim),
[soydemac](https://en.soydemac.com/what-is-rsim/)).

**The recipe the FAQ gives** for BYD's "chipset 34" family, which it says
includes Denza and requires MIX mode:

1. In an iPhone, program the R-SIM in MIX mode with ICCID `898607` + fourteen
   random digits and IMSI `46001` + ten random digits. The digits are random;
   the guide does not copy the factory SIM.
2. Solder a SIM holder to the head unit's board and fit the local SIM with the
   R-SIM on it.
3. In the car's network settings: APN1 off, APN3 on, "Double APN" on, VoLTE
   on (may need two reboots).
4. Bind the master account: the app scans the QR code on the car's screen.
   The master account itself is obtained through the exporter, who uploads
   the exporter's ID, the Chinese registration certificate with the VIN and
   the owner's Chinese phone number (a virtual +86 number is used); BYD
   confirms it in about ten days, after which the car's QR code becomes
   active ([drive2](https://www.drive2.ru/l/722184801842379878/)).

The FAQ's own trouble table lists "network drops after minutes" with the
remedy "reconfigure the ICCID in the phone app", which matches the owner's
"the R-SIM falls off".

**How it maps onto this repository's evidence.**

- Step 3 is the profile this adapter writes: `double_apn` with APN1 disabled.
  The guide reaches it through the car's settings; the tile reaches it through
  the `RADIO_CONFIG` broadcast. If the modem actually establishes APN3, the
  framework's APN3 event can open the native gate without our `notify_nw(4)`.
  The presence of a local SIM alone does not establish that APN3 is connected.
- Step 1 is the identity that message 211 carries: IMSI (15) + ICCID (20),
  read from `ril.imsi` / `ril.csim.iccid`, which the telephony framework
  fills from whatever the modem reads, i.e. from the interposer. The FAQ's
  statement that ICCID-only adapters do not work on this family, while MIX
  mode does, suggests that IMSI matters somewhere along the path. It does not
  isolate a server check: IMSI can also influence local APN/roaming behavior.
- The FAQ's random digits, if the recipe works as written, are consistent with
  **format or prefix checks**, rather than requiring both exact factory values.
  The recipe alone cannot identify a server rule. The April-firmware /
  operator-46001 report is compatible with that recipe; its operator prefix
  does not prove that the adapter or random values were used. The later
  controlled owner-car results below confirm two narrower acceptance cases;
  they still do not test46001 or both changed fields together.
- The two code-3 cars present operators 25002 and 25901, i.e. the local SIM's
  own IMSI. That can be consistent with an absent, reverted or ICCID-only
  interposer, but the reports do not identify the hardware. The eighteen-digit
  ICCID on the25002 car also needs a source/length check; its shape alone does
  not prove manual entry or the exact reason for server reply3.
- "Falls off" and the native identity cache combine: `prepare` rereads the
  ICCID only while its cached first byte is zero, so a running client keeps
  the pair it read at start. An interposer that reverts to the real SIM shows
  up in 211 only after the client restarts. Until then the session it already
  holds may stay up. After a restart it can send the newly visible identity;
  reply3 is observed in the cited local-operator reports, not established as a
  universal result of every such transition. IMSI also has a digest-byte cache
  condition, described in the more precise native-cache section below.

**What this changes for the adapter.** Nothing in the control path: the tile
still only translates Wi-Fi into the gate, and the identity in 211 is not the
app's to choose. It does explain the forum population: every code-3 report so
far is a car whose modem currently reads a non-Chinese identity, and the
tile's «Облако отклонило регистрацию (код 3)» together with the report's
operator field already distinguishes that from a network fault. A hint that
names the operator on that line is a display-only idea, not implemented.

**Limits.** No R-SIM has been examined here; the full recipe and its chipset
labels come from community guides, not BYD. The controlled tests below now
establish one-field acceptance with factory credentials, but the exact server
rule behind reply3 remains unproved. They do not validate every step of the
guide or explain every affected user's failure.

### Independent R-SIM forum follow-up, 2026-09-24

At the owner's request, a separate researcher searched public community sources
without touching the car. These are reported practices, not a firmware/server
contract; the strongest new sources were also opened directly in this session.

- [BYD Wiki: R-SIM](https://byd-wiki.github.io/docs/internet/rsim/) permits an
  original ICCID or a generated898609-prefixed value in its general instructions.
  Its separate34-family section specifies original ICCID plus46001 IMSI using
  R-SIM18/MIX, or a sequential USIM setup. It reports APN2 stopping, APN3 working
  and roaming being reset at startup. No specific Denza build or packet trace
  is supplied. This differs from the FAQ's898607+14 recipe and simultaneous
  APN2/APN3 claim; do not merge them into one validated instruction.
- [BYD Wiki: APN](https://byd-wiki.github.io/docs/internet/apn/) distinguishes
  application and cloud APNs, and reports operator/tariff-dependent multiple-APN
  support. This supports investigating local network policy separately from
  cloud identity acceptance; it does not prove a particular operator failure.
- [DRIVER.TOP owner report](https://driver.top/exp/706686/) describes a Song
  Plus EV with a Ukrainian SIM and purchased preconfigured R-SIM, working QR
  account binding and remote features. The author permits original/generated
  ICCID in the general procedure but does not disclose which his adapter uses,
  his IMSI, firmware or sleep/restart results. The visible date is February13,
  19:51 without a year; a2026 inference from context is not a verified date.
- [GitHub metadata for the FAQ](https://api.github.com/gists/b9ea28ff15b746d839b8a843d6936fee)
  dates publication to2026-04-11 and update to2026-04-13. Its internal April2025
  label is not the publication date of that public Gist.
- Primary negative SIM-swap reports exist for
  [Atto3, June2023](https://www.reddit.com/r/atto3/comments/13yyacy/) and
  [Atto1/Seagull, May2026](https://www.reddit.com/r/BYD/comments/1t982rm/byd_app_with_3rd_party_sim_in_currently/).
  They report cloud/app or QR/OTA problems after local SIM replacement, without
  R-SIM or isolated identity controls. Their proposed binding explanations are
  hypotheses. An older
  [Tang hardware report, September2019](https://club.autohome.com.cn/bbs/thread/78d92824923f7507/83620661-1.html)
  likewise distinguishes a4G icon from usable internet; its generation is not
  comparable to Z9GT. A
  [Seal U sleep discussion, March2026](https://www.bolidenforum.de/forum/threads/byd-seal-u-dm-i-app-verbindungsprobleme-nach-update.4892156/)
  concerns Wi-Fi after shutdown, not R-SIM and not Denza.

No independent exact89860+15 recipe, controlled Denza test with both generated
fields, or reliable R-SIM sleep/cold-start/cache proof was found. DRIVE2 blocked
page access and the Wiki-linked Telegram source was not readable in web preview.
These limits apply to this search, not to all possible community experience.
Our own independent one-field acceptance results are documented below.

### Changed IMSI suffix: short live comparison was inconclusive, 2026-09-24

**Follow-up:** the complete A/B/A login experiment below resolved the missing
continuation and accepted a changed IMSI suffix. This subsection preserves the
earlier211-only result and its diagnostic interruption.

At the owner's explicit request, one bounded test at **18:45:36–18:45:41 UTC**
kept the current original ICCID, factory certificate/VIN/provisioning and first
five IMSI digits, but changed all remaining ten IMSI digits in memory. The
original native211 routines produced the packet. A second and final211 request
then supplied the original pair again, because registration may update a
server-side record. No modem property, profile, stock gate, APK or vehicle
control was changed; no200/220/login/telemetry followed.

| Request | Validated native211 body status |
| --- | --- |
| Original ICCID, changed IMSI suffix, original operator prefix | 0 |
| Original ICCID and original IMSI, restoration attempt | 0 |

Each request used one factory-backed TLS signature, verified the peer, sent101
application bytes and received69 bytes. The original native decoder and211
handler accepted the response framing and stored0 at object+0x35c. Neither
request returned the previously observed success value1. Therefore this run
**does not prove or disprove format-only IMSI acceptance**: the original-pair
control was also different from the earlier successful baseline. It also does
not test the forum recipe's particular46001 prefix or a changed ICCID.

Offline inspection distinguishes0 from both1 and the >=2 failure branch:
`reset211Variable` at0x54b24 routes0 through socket destruction at0x75904 and
`startAlarm(3)` at0x48280. The matching framework's
`BYDTCPConnectService.alarm_start(3)` schedules `LOGIN_DELAY_ACITON`; this is a
login-delay timer, not a door-lock command. Value1 takes the discovery/queue path; values>=2
take the labelled registration-failure/retry branch. This identifies client
behavior, **not the server's meaning for0**. The probe captures that continuation
without running its property/socket/MCU effects. A synthetic native fixture now
also verifies that0 is preserved and never labelled accepted.

Before/after stock TCP1, step6, boot, native PIDs, profile, Wi-Fi-retention setting
and installed APK hash matched. Both intervening stock TCP samples were1. An
in-memory comparison confirmed modem IMSI/ICCID unchanged. The later read still
showed stock TCP1/step6 and its own registration property1. The test processes
exited; no remote file was installed. The original-pair request was sent, but
**server-side restoration was not confirmed by a success response**; continuing
stock connectivity is a separate observation, not proof of that record.

**Later diagnostic interruption:** by18:47:46 UTC, ADB showed the car offline
and the host's local5555 tunnel listener was absent. The probe had already
completed at18:45:41, and an intervening read still showed stock TCP1/step6.
No helper retry or cloud/vehicle command ran between those observations. The
precise disconnect time and cause are unknown. Earlier QuickBoot bridge exits
do not prove this is another such event. The owner was asked to restore only
the existing diagnostic connection for an exit-info/current-state read; current
cloud state and a final remote process check remain unavailable until then.

**Recovery resolved that diagnostic gap:** the owner restored the bridge;
exit-info records PID16024 stopped at **18:47:30.052 UTC** with reason
`stop dev.denza.adbbridge due to quickboot 0`. The persistent event buffer also
retained `power_sleep_requested` at18:47:29.792, followed by the bridge's
`am_kill` at18:47:30.052. Android boot ID and native PIDs remained identical;
the recovered bridge PID was28516. Stock TCP1/step6/registration-property1 and
busy0 were confirmed, with no registration transport process left. Thus this
was another QuickBoot app stop, not an Android reboot. Main/system messages
retained only18:49:23 onward, so the underlying ACC transition is not traced.

The bridge stop was **600.054s after the temperature command's cloud flag4**
and599.592s after its MCU success, versus108.100s after the registration probe
finished. Expiry of a ten-minute remote-climate session is therefore a concrete
hypothesis; the timing is not proof of causation. The stored532 result still
described the21:37:30 temperature command. This does not exclude every other
unretained message or establish door-lock state. No further cloud test ran;
the original-pair server-record restoration remains unconfirmed by status1.

Offline QuickBoot tracing identifies the concrete app-termination path in
`AccModeManagerService.updateAccState`: ACC-off, or releasing the last ACC
power hold while ACC is off, can call `Utils.startAccOff/startAccOffSilent`.
Those clear recent apps; `Utils.killApplications` passes the reason
`quickboot <userId>` to `killApplicationEx` for non-exempt apps. This matches
the earlier bridge exit descriptions, but needs this incident's exit/state
evidence before attribution. An ACC power hold is distinct from door locking.
Exact source hashes/references are in `quickboot-source-notes.json`.

The two-request limit was respected, with no further cloud retry. The next
discriminator is the meaning/precondition of0 for an already-connected vehicle,
followed by a controlled comparison with a successful original-pair control;
trying more arbitrary IMSIs now would not isolate the cause. Exact executed
sources, redacted report, native branch disassembly, offline checks and cleanup
are in `captures/telematics-20260924/imsi-suffix-registration/`. The source
[native_registration_probe.py](../tools/telematics/native_registration_probe.py)
adds preview-first `--test-imsi-suffix`; its five offline checks cover identity
isolation and restoration after acceptance, rejection and ambiguous failure.

### Changed IMSI suffix: complete login accepted, 2026-09-24

The owner renewed permission to test the IMSI hypothesis. The experiment at
**18:58:24–19:01:07 UTC** compared original pair → original ICCID with ten
changed IMSI suffix digits → original pair. Its operator prefix was **46013**,
read from the owner's original SIM; **46001 was not tested**. VIN, factory
certificate/signing, cloud key/UUID and Wi-Fi route remained those of the same
owner's car. No modem identity was written.

The missing native continuation was established offline before the run:

- `reset211Variable` status0 closes the registration socket and starts alarm3.
- `startAlarm` byte table0x1e0f1, index3, selects0x483d8, whose `mov w2,#0x46`
  means **70 seconds**. The framework maps alarm3 to `LOGIN_DELAY_ACITON`.
- The native timer handler's halfword table0x1e11a, index3, selects0x53c5c.
  When its private context is not already logged in, it stops alarm3 and
  dispatches **200**, followed by native220. It does not require another211.
- Value1 permits immediate discovery. Values>=2 take the failure branch.

These addresses are from archived cloudmanager SHA-256
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`.
The executable itself remains unreadable to shell on the car; its installed
hash is not claimed. The probe executes the pinned original routines in a
bounded host emulator, with TCP and factory signing on the car. Packet bodies,
discovery and login response interpretation belong to native code. Post-login
side effects are captured, not executed.

| Leg | Native211 status | Delay before200 | Native220 accepted |
| --- | --- | --- | --- |
| Original ICCID + original IMSI | 1 | 0s | Yes |
| Original ICCID + changed ten-digit IMSI suffix | 0 | 70s | Yes |
| Original ICCID + original IMSI restored | 0 | 70s | Yes |

All nine bounded TLS exchanges verified the server and used one factory
signature each. No telemetry or actuator command was sent. Before the first220,
the probe armed a device-local480s guard and paused only the stock cloud gate
with-5, preventing competing logins. It reopened that gate with4 at completion.
The guard did not expire; its exact directory was removed. Stock TCP1/step6,
boot ID, native PIDs, profile, Wi-Fi-retention setting and product APK hash
matched before/after. In-memory comparison confirmed original modem inputs
unchanged, and the original-pair220 login succeeded before stock restoration.
A final read found TCP1 and no test transport/guard left.

**Conclusion:** exact original subscriber digits were not required for this
one full211→200→220 login, when the original ICCID and46013 operator prefix
were retained. Registration0 alone must not be described as rejection or final
success; here both changed and original pairs proceeded to accepted220 after
the firmware delay. This does not establish arbitrary prefixes, arbitrary
ICCIDs, replacement-SIM connectivity, long-lived sessions, or downlink commands
under the changed IMSI. Existing original-pair511 proof is a separate result.

Exact executed sources, six offline tests, redacted stage reports, native
continuation evidence and cleanup are retained in
`captures/telematics-20260924/imsi-complete-login/`. The research tool is
[imsi_login_compare.py](../tools/telematics/imsi_login_compare.py); it is
preview-first and keeps one controlled variant between two original-pair
controls. Later additions to its options have separate source snapshots.
The next, separately isolated ICCID experiment follows below.

### Changed ICCID: complete login accepted, 2026-09-24

After the IMSI test and verified restoration, the owner requested the ICCID
experiment next and supplied the forum template `89860` followed by fifteen
random digits. At **19:03:32–19:03:55 UTC**, a second A/B/A run kept the entire
original IMSI, including prefix46013, and changed only the supplied ICCID in
the middle leg. Exactly one20-digit value was generated in memory; there was
no candidate search or checksum adjustment. Factory VIN, key/UUID, certificate
and opaque signer were retained. No modem property was changed.

| Leg | Native211 status | Native220 accepted |
| --- | --- | --- |
| Original ICCID + original IMSI | 1 | Yes |
| One `89860` + fifteen-digit ICCID + original IMSI | 1 | Yes |
| Original ICCID + original IMSI restored | 1 | Yes |

Each leg also completed native200 discovery. All nine TLS exchanges verified
the peer and each used one factory-backed signature. The previous experiment's
gate/guard procedure was reused, with no telemetry or actuator traffic.
Original-pair login, stock TCP1/step6, unchanged modem pair and before/after
baseline equality were confirmed; the temporary directory and helper were
removed. A19:04:36 UTC postflight again found stock TCP1/step6 and no helper.
Seven offline tests passed before this run, including native bodies/login
digest isolation and original-pair restoration after an ambiguous failure.

**Conclusion:** for this car and its existing factory credentials, login did
not require the exact original ICCID. Independently, the prior run showed that
login did not require the exact original IMSI suffix. These are two separate
one-field experiments; their combination has not been tested. In particular,
neither run tested `46001`, arbitrary prefixes, a replaced SIM's mobile route,
other cars, long-lived sessions or commands with a changed identity. The
results do not remove the need for the vehicle's factory credentials.

The non-original pair existed only in the private native-code emulator on the
Mac; TCP/signing ran on the car. This demonstrates an input path for a thin
adapter using native codecs, not an identity injection into the running stock
daemon or a shipped APK feature. The earlier on-car original-pair511 test is
separate runtime evidence. Next integration validation should combine the
chosen inputs in that adapter, check real511 in both directions/reconnection,
then evaluate a replacement-SIM route without interrupting the owner's Wi-Fi
without separate agreement.

Exact sources, plan, redacted reports and cleanup are in
`captures/telematics-20260924/iccid-complete-login/` with SHA256SUMS.
The same preview-first research tool now has explicit `--test-field iccid`;
each invocation remains one variant with original controls before/after.

### Generated ICCID and IMSI together: on-car reconnect and status, 2026-09-24

At **19:17:25–19:21:07 UTC**, the owner-authorized adapter generated one ICCID
(`898607` + fourteen digits) and one IMSI (`46001` + ten digits) in car-local
memory. Original factory VIN, key/UUID, certificate and hardware-backed signing
were retained. Modem properties were neither changed nor used to supply the
variant at runtime. The original native firmware routines handled all protocol
structures; the Mac only launched and observed the experiment.

Generated-pair211 returned0, followed by the original70-second continuation,
accepted200 discovery and220 login. Two real phone requests511 were answered
with native181-byte frames and79% SOC, matching independent fresh SDK readings.
Between requests the adapter replaced both its worker and TLS session, retained
that same generated pair and logged in again. The owner confirmed two refreshes.

Restoration ran original211 (status0),70-second continuation,200 and220 before
reopening the stock gate. StockTCP1/step6 and unchanged boot ID, PIDs, APK hash,
profile and Wi-Fi-retention setting were verified. In-memory comparison found
unchanged modem identifiers. Temporary helpers, guard and exact remote directory
were removed. Ten bounded TLS handshakes used factory signatures and verified
the peer. Source snapshots, build hashes, redacted log and restoration result:
`captures/telematics-20260924/generated-pair-session/`.

This supersedes the earlier statement that the two changed fields had not been
tested together. It proves a car-local generated-pair status session and immediate
reconnect on this car over Wi-Fi. **It does not prove actuator commands through
this adapter**, a replacement SIM/mobile route, sleep recovery or an unattended
service. Earlier climate commands ran through the stock service. The experimental
worker still deliberately excludes532, persistent heartbeat/lifecycle and
actuator-result handling; product integration must preserve those native paths
before it can replace the stock service for remote control.

### Generated pair: real command and offline promotion review, 2026-09-24–25

The later control experiment supersedes the status-only limit of the preceding
19:17–19:21 run, **for one climate command on the awake owner's car only**.
At approximately19:54 UTC, the adapter used a generated pair, factory vehicle
credentials and the original native codecs. The actual phone sequence was:

- Empty536 wake request, original native reply, unchanged SDK wake/envelope
  buffers relayed to the vehicle.
- Cloud532/subcommand3/flag254, native acknowledgement and unchanged MCU envelope.
- MCU intermediate3, native cloud reply, cloud continuation4, unchanged MCU
  envelope, then MCU terminal1 and its native cloud reply.
- Independent driver target getter changed24→23°C; it remained23 afterwards.

A second worker/TLS session with the same pair accepted login and serviced511,
but no second actuator command arrived before the test window ended. Accordingly
`pass=false` in the overall experiment means its two-command acceptance was not
completed; it does not negate the first command's observed result. The phone
showed24 and transient `Connection failed`; that did not prove a physical rollback.
After original211/70-second continuation/200/220 and stock gate4 restoration,
stockTCP1/step6 and ordinary app refresh were confirmed by the owner. No vehicle
reboot, Wi-Fi change or installed-APK change was observed in this run.

Evidence is preserved separately in
`captures/telematics-20260924/native-control-adapter/attempt-4/`, including exact
sources, worker/APK identities, original logs and restoration result. Do not
replace it with later edited research sources. The earlier TLS failure was not
attributed conclusively to resumption: fresh SSL contexts fixed the subsequent
experiment, but the old failed attempt did not record enough counters to prove
that cause.

The owner subsequently stopped APK preparation and disconnected the car for a
quality pass. No new vehicle acceptance is implied by the following offline work.
An independent subagent reviewed the research on a clean context. Host Unicorn
replay on firmware SHA256
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`
confirmed the following **promotion blockers**, not failures of a shipped custom
runtime (there is no shipped custom runtime):

1. Request-only532 sweeping is insufficient. Sub5 forwards a request but its
   successful MCU callback needs unselected native routines0x451f0/0x44c94 and
   an uninitialised secondary object. Sub17 arms32 seconds, not16. Sub39 takes
   another timer path. These must be closed through callbacks, continuation,
   timeout and cancellation before widening the command boundary.
2. `timer_capture` records no real timer. Post-login, heartbeat, alarm and
   reconnect routines are deliberately stubbed in the isolated experiment.
   Retaining these stubs in a service would leave missing terminal replies and
   session loss unrecovered. Repeatedly forcing `ready` is not a substitute.
3. The asleep536 path reaches0x5c5f4, a wake setter and a condition wait. A
   synchronous single-worker IPC cannot simply admit MCU0 and expect those
   callbacks to arrive. Real power state must be delivered; never substitute
   awake values to avoid this path.
4. The initial partial allocator gained memory after repeated native result
   handling (100 results,9808 bytes including startup). It used a bump pointer
   and no-op deletes. This defect was corrected and checked separately below;
   the other lifetime gaps still prevent removing the experiment's limits.
5. Observing stockTCP0 does not grant exclusive ownership. Stock service recovery
   and APN broadcasts must be reconciled with custom ownership; app_process
   ownership alone cannot lock the vendor daemon.

`audit_control_lifecycle.py` reproduces representative request/result paths,
100 sequential transactions and the unresolved sleep path without ADB, sockets
or vehicle writes. Its ledger explicitly says `qualified_for_product=false`.
Results and source hashes are under
`captures/telematics-20260924/cloud-quality-review/`.

One actual test-wrapper defect was corrected: `terminal` is now reset for each
RX or MCU exchange. Previously a completed command could leave terminal1 for a
later command or unrelated wake reply. The MCU result label also comes from its bounded MCU envelope rather than the
last unrelated network message. Original native busy/correlation memory
is unchanged. `verify_control_runtime.py` includes repeated-command and
interleaved-message regressions. A later emulator-only test of a temporary ARM64
worker passed twelve cases, including two **distinct** correlations in one
worker, intermediate and terminal replies, interleaved wake, corrupt input and
wrong correlation. Synthetic AUTO output was captured as text; no Binder or
vehicle effects were executed. This built a temporary test executable, not an
application APK. Its source snapshot, build manifest and results are in
`cloud-quality-review/emulator-control/`.

The allocator now handles original new/new[] and delete/delete[] entries with
a fixed64KiB pool, aligned blocks, reuse/coalescing and invalid-free/bounds checks.
Host ASan/UBSan checks and both ARM64 syntax variants passed. A stricter replay
uses unique correlations and verifies a new write, result and response on every
iteration:100 forwarded/completed532 controls plus100 answered511 requests made
301 allocations and301 releases, with256 bytes of high water and zero live bytes
at the end. It runs original firmware in Unicorn with the exact C allocator
compiled for the host. Repeating one correlation would be deduplicated by the
firmware, so stale write/result counts are not accepted as lifecycle evidence.
The normal time/operation bounds and climate-only worker boundary remain intact.

`verify_generic_control_closure.py` separately executes all256 synthetic532
subcommands in the original instructions. The final-source sweep passed255
terminal1 paths,255 terminal2 paths and the special sub39 path. Representative
full intermediate3 → continuation4 → terminal1/2 paths also passed. Sub5 needs
the real secondary constructor/context and emits native709 plus property/timer
effects. A later focused run executes its original5-second callback0x45ca4:
it calls external `TimerEvent::stop()`, invokes the secondary object's original
sender and emits another709. Forced repeated callback delivery repeats709;
this does not prove that the real TimerManager delivers the callback twice.
Sub39 is **not** the
generic MCU-envelope path: original code emits five14-byte CAN writes, four100ms
pauses and a10-second timer whose native callback emits417. All these effects
were only captured offline. Empty listener fixtures, timer scheduling and
platform property effects remain limits; this does not widen the C worker's
allowed commands. Final artifacts are under `cloud-quality-review/generic-control/`.

The focused timer run also executes the original generic timeout thread body
0x61230 for the16-second and32-second branches. It deletes the POSIX timer and
clears busy, without a cloud reply. A later MCU terminal result is still accepted
by the original handler. Therefore a host timeout must not invent a terminal
reply or automatically replay an actuator command. Thread scheduling and
TimerEvent lifetime remain external fixtures. See `timer-semantics.json` and
`timer-semantics.asm`; the full256-case sweep was not rerun for this separate
timer-only extension.

The research Java transport was also hardened offline. Peer verification,
certificate/key selection and the one-signature budget now belong to one TLS
handshake, rather than mutable process-wide fields. Each opaque key retains its
identity snapshot; a different socket cannot borrow another attempt's verified
peer or signing budget. Finished attempts reject additional signing. Invalid
raw signing blocks, failed cipher reinitialization and insufficient output
buffers are rejected before invoking the signer. The earlier owner-certificate
pin remains an experiment constraint, not a multi-car product identity policy.

`tools/telematics/test_oncar_tls_isolation.py` passed twelve host cases using the
actual Java provider and freshly generated synthetic keys. This covers separate
attempts, concurrent budget use, identity replacement, failed signatures and
late signing. A separate Android/Conscrypt suite then passed on the confirmed
local emulator using synthetic certificates and a local server: mutualTLS,
two fresh connections, wrong hostname, wrongCA and bad signature. Invalid peers
used zero signatures; bad signing used one and failed verification. No factory
key or cloud connection was involved. Evidence is in
`cloud-quality-review/android-tls-integration.json`.
`test_oncar_session_lifecycle.py` also passed eighteen host cases: failed constructors
close their socket/process, and worker cleanup does not wait for a blocked
QUIT/readLine response or terminate another worker. Java source compilation
against the Android API succeeded. No APK or vehicle operation was involved.
The SDK source now also terminates its owned child on readiness/thread-start
failure, preserves interrupted status and keeps the original error if cleanup
fails. Normal close requests STOP and waits for unregister/clean completion;
forced termination, dirty DONE or a missing unregister cannot result in an
overall PASS. These are research-helper fixes, not product supervisor completion.

`verify_wake_wait_native.py` adds eight original-code wake/getter cases with
explicit external fixtures. The native helper issues
`setInt(1005,0xaa00004a,1)` and waits at most three one-second attempts in these
cases. Timeout, spurious wake, setter failure and a non-awake event do not become
success. Its injected MCU state does not prove native SDK callback delivery:
the real0x5d87c callback additionally emits502, notifies the condition and enters
0x53818/0x5c940 lifecycle work.

`verify_wake_callback_native.py` subsequently executes that original callback,
its502 builder, lifecycle getter/property path and opaque queue drain. Five
offline cases passed. An actual sleeping536 dispatcher builds and queues its
own seven-byte MCU buffer after the condition-wait timeouts and arms a two-second
timer. Directly invoking the original awake callback cancels that timer and
forwards the queued bytes exactly once; repeating the same state event does
nothing. A separate two-item fixture checks original FIFO ordering. A sleeping532
takes a different path: three unsuccessful wake waits return native reason0x25
and do not queue the actuator command. The real listener/event source, timer
thread, SDK values, Binder effects and cloud delivery remain unproved. No
assumed-awake state is a substitute for those dependencies.

Static inspection identifies the callback's source in the native observer:
0x6822c handles integer SDK events. For FID0x99000003 it admits only values0/1
and calls0x5d87c at0x683ac; initial state comes from `getInt(1005,0x99000003)`
at0x5bafc. Charging FID0x34400018=1 and a nonzero ACC FID0x12d0002a also infer
awake when the cached MCU state is0 (calls0x68338 and0x683f8). These are original
firmware rules, not a reason for the adapter to fabricate MCU state. A future
opaque bridge must supply the relevant real events, in order, to the original
observer path. The present climate probe only subscribes to YUN buffers and
reads these integers synchronously; it has not qualified those subscriptions
or races during sleep.
An initial direct1→0 callback replay stopped at the unselected listener routine
0x480c4; the following disconnect/timer and secondary-object paths are not closed
by the five wake cases. Duplicate0 is a no-op. This is an explicit remaining
sleep-transition boundary, recorded in `wake-sleep-boundary.json` and
`wake-observer-path.asm`, not a passed sleep cycle.

The separate `verify_sleep_transition_native.py` then executes original1→0→1
callback instructions with an original secondary constructor, an explicitly empty
primary listener list and empty scheduled-work vector. Sleep executes0x480c4,
0x546f8 and0x451f0, emits native502 and records native unlock-property changes;
awake emits another502, notifies the condition and reaches the lifecycle getter
and queue drain. No vehicle writes were performed. The focused result passes in
`sleep-transition-native/transition.json`, but does not close the populated
listener/work-vector branches, real scheduling or SDK delivery. The persistent
adapter still needs those paths and the original post-login/heartbeat effects;
their static inventory is retained in `post-login-dependencies.asm/.json`.

An unchanged private copy of the complete cloudmanager is not an isolated
shortcut. The original constructor0x48954 starts a thread at0x48db8, before
fixed-name Binder publication. The service factory0x666e4 proceeds into
initialization0x5b744 even if publication fails. That initialization registers
BYDAuto observers, enables devices and subscribes to Java cloud service callbacks;
the retained `CloudServiceImp.java` accepts only its first native listener.
The copy also uses shared token/registration properties and
`/data/system/cloud/cloudToken.dat`. Running it as shell does not confer the
stock init service's UID, SELinux domain or permissions. The selected policy
files and static paths establish interference risks, not a complete effective
policy proof. A usable private daemon would require isolation of these resources
and startup effects in addition to supplying the chosen SIM pair; none was
launched in this quality pass.

### Resident adapter implementation, 2026-09-25

The resident Java runtime now exists under `tools/telematics/runtime/`.
`CloudNativeMain` supplies the app-process main Looper and the independent
watchdog; `CloudRuntimeProtocol` implements the versioned resident exchange
expected by `CloudCustomBackend`. The backend entry name now matches this
runtime. No runtime assets or APK have been built for distribution, and
`cloudNativePilot=false` remains in force.

The native code is a **private copy of selected functions from the pinned ELF**,
not a patch to `/system/bin/cloudmanager` or injection into its running process.
ICCID/IMSI are immutable inputs to that copy. Actual modem identity and radio
configuration are not written. `CloudPrimitiveBridge` exposes typed SDK
getters/setters, properties and event waits. Vehicle command interpretation and
packet construction remain in firmware instructions; the Java layer does not
add climate/seat/lock command implementations. Its narrow primitive allowlists
are compatibility boundaries, not replacements for native permission checks.
The owner explicitly reaffirmed this boundary on 2026-09-25: extending support
must connect an original dependency to the platform, rather than introduce a
Java/C handler for each vehicle feature. Integer/float reads, integer/buffer
writes, property access, timers and event waits form the adapter interface.
The Java forwarding interface now accepts the original getter/setter arguments;
the matching-firmware allowlists live at `CloudPlatform`. Renaming an interface
does not qualify a new setter or remove a native dependency.
The copy's diagnostic `sys.tcp_step` belongs to private state, not a misleading
update to the stock daemon's public status. Shared unlock-property semantics
are still unqualified and are not acknowledged as successful writes.

This is not yet a completely generic native runtime. The isolated engine still
restricts admitted cloud message families and retains an experimental result
journal replacement. Inspection of original `0x54e20` found formatted property
writes, so that replacement cannot be promoted merely by accepting result
codes other than climate. Original ACC observation also enters an asynchronous
callback path whose lifecycle is not yet connected. These are missing native
dependencies to restore, not reasons to implement climate/lock/ACC policy in
the adapter. `CONTROL532`, `MCU_STATE`, wake and post-login capability gates
remain false until their real dependency paths are exercised end to end.

The implementation adds these lifecycle guarantees, checked locally with
injected faults:

- A version-independent kernel lock spans PROBE through resource teardown.
  Subscriptions and the native process enter an ownership scope **before**
  subscription/READY setup can fail. Ambiguous cleanup retains the lock until
  the owning process terminates; it cannot permit a second owner.
- The owner lease is30 seconds, independent of control/session I/O. The serial
  session loop must also report progress within20 seconds; repeated app STATUS
  queries cannot hide a stalled Binder call. Unfinished teardown has a5-second
  watchdog budget. EOF waits for cleanup or terminates that process.
- Each connection uses a new native process, TLS socket and epoch. A saved pair
  survives reconnects within the owner; old commands and SDK buffers do not.
  Failed cleanup prevents replacement. A durable registration-uncertainty marker
  contains no SIM values and is not cleared by local STOP.
- TLS publishes its raw/layered sockets before connect/handshake, reaps the
  connector on close, retains close failure as debt, and uses a whole-frame
  deadline. Partial writes and reads poison the stream; a later frame cannot
  reuse it. Existing per-handshake trust/signature isolation remains intact.
- Native IPC is bounded and tagged with operation/epoch. A failed reader stops
  admission of further effects. An admitted call may finish before teardown;
  its completion is not grounds to replay it on a new connection. Primitive
  errors receive ERR rather than a guessed success/value.

`CloudNativeConnection` wires the original registration/discovery/login
producers, opaque DATA/MCU callbacks and network frames. It checks the pinned
firmware hash and all required native capabilities **before opening the SDK or
cloud**. The native engine has not closed every post-login/heartbeat/wake path,
so this check deliberately refuses current incomplete engines. Host tests prove
that refusal has no platform/TLS side effects. Factory return remains separate
from local shutdown: a free lock is not proof of BYD registration restoration.
The current preflight also requests the installed ELF hash. Earlier car evidence
established that shell cannot read this protected file; this is a known
deployment blocker, not a verified live hash. Missing/unreadable/mismatched
firmware now ends the resident connection attempt as unavailable instead of
retrying it as a transient network fault (the supervisor currently reports
`session_failed`). An alternative build/interface compatibility proof still needs
qualification before this runtime can be shipped. The orchestration also still
reproduces the observed registration continuation (`REG 0`, 70-second delay,
discovery); it is not the original daemon's complete startup state machine.

Reproduce the Java checks without APK, device or network access:

```sh
python3 tools/telematics/runtime/test_runtime.py \
  --result captures/telematics-20260924/cloud-quality-review/persistent-java-runtime.json
```

This compiles against Android35 and executes six host suites: supervisor,
platform/TLS boundaries, control protocol, native IPC, reconnect ordering, and
primitive forwarding/capability rejection. Ten supervisor cases and seven IPC
cases include hung journal/lock, close uncertainty, late resources, wrong
operation/epoch, child failure and cancellation. The source hashes accompany
the results. The existing twelve host TLS-isolation tests and focused Kotlin
cloud/tile tests also pass. This is offline evidence; no car, SIM, certificate
signing operation or cloud endpoint was used in this implementation pass.

Two GPT-6 Sol review passes identified and then rechecked startup ownership,
EOF cleanup, queued IPC effects, stale stock-gate calls and stream deadlines.
Those corrected boundaries have regression coverage. The full new orchestration
still needs native closure and integrated acceptance; these tests do not claim
that unattended sleep/reconnect or all controls are already qualified.

The accompanying persistent ARM64 engine passed 15 offline scenarios, including
expected rejection at unconnected original functions, plus the portable timer
test. Evidence and the exact source snapshot are retained in
`captures/telematics-20260924/cloud-quality-review/persistent-native/`.
The engine now returns a ready status frame without extracting SOC from its
body; Java forwards that frame unchanged. Original generic control timers,
cancellation, sequential requests, MCU observer callbacks and a nested observer
inside a native condition wait execute in the emulator. The sleeping post-login
536 fixture still differs from the earlier partial-object queue test: wake
cancels its timer, but no queued AUTO output appears. Timer expiry, ACC/charge
continuations, the original result property journal, sub5, post-login and
heartbeat remain unconnected. Shared `remote_controling=1` is acknowledged
only by the offline fixture, with no qualified crash cleanup on the car.
Consequently the control/lifecycle capability gates remain false. These are
passing research scenarios, not 15 successful product acceptance cases.

### Cloud product recovery contract under preparation, 2026-09-24

The product custom transport remains disabled by a literal build flag. A build
property must not silently promote the bounded research worker. Existing factory
mode and the custom configuration scaffold are distinct from a qualified custom
service. No APK/release/install was made for this quality pass.

Required invariants for the integration:

- Fresh install/data clear has cloud off and no implicit SIM selection. Upgrade
  preserves an existing explicit factory setting. A syntactically valid ICCID/
  IMSI pair is not a guarantee that a particular car will register.
- The mode and the two identity fields are an atomic configuration. They cannot
  change while enabled, while a transition is queued/running, while stop is
  unconfirmed or while an earlier native owner is unresolved. Repeated requests
  are serialized; automatic recovery never generates another identity.
- Factory policy uses validated Wi-Fi. Custom policy may use validated default
  Wi-Fi or mobile connectivity; it must not disable physical APNs or the modem
  to simulate a factory SIM. Wrong mode must produce an actionable state without
  changing identifiers, repeatedly registering or claiming a connection.
- An ambiguous START, STOP or broken ADB stream retains a durable teardown
  obligation. Absence of an app-side object is not proof of process/socket exit.
  Before native START, persist a random owner nonce without SIM values.
- Every native version shares one kernel lock, independent of its staged asset
  directory. The proposed supervisor's PROBE can report absence only after
  obtaining that lock; it holds the lock across START. A PID alone is not a
  process identity and must never be used to terminate an arbitrary process.
- The resident supervisor implements an independent monotonic30-second owner
  lease, EOF handling and bounded teardown. Its parent owns sockets; a native child owns
  only pipes, with no network privilege. STOP must finish cleanup before its
  acknowledgement. A stalled operation cannot renew its own lease indefinitely.
- The supervisor protocol is implemented; native capability closure remains
  incomplete. PROBE uses the full versioned status envelope. Free lock means
  `stage=stopped/code=owner_absent_confirmed/session_live=false`; occupied means
  `stage=failed/code=owner_present`. Neither stockTCP nor IPC READY means custom
  cloud login succeeded. New sessions invalidate all older status/result events.
- Reinstall can erase the app journal while a prior shell process still lives.
  A native-capable build must therefore prove native absence before factory
  activation even without that journal. Merely checking a saved owner nonce is
  insufficient. The current factory-only build predates any shipped native owner.
- A successful STOP/PROBE proves local runtime absence, not restoration of the
  server-side factory registration. The successful car experiments explicitly
  restored the original pair through211, its70-second continuation,200 and220
  before opening the stock gate. Merely stopping the custom process and sending
  gate4 has not been qualified as a return-to-factory procedure. Product mode
  switching must keep that registration obligation separate from process
  ownership and cannot claim factory readiness from a free lock.
  This tested restoration sequence does not establish a server rule requiring
  the original pair after every STOP. A replacement-SIM owner may not know that
  pair. Local OFF must remain possible; the next connection must qualify its
  chosen identity separately, with factory readiness left unconfirmed until
  the stock connection actually succeeds. Do not invent an original identity
  or make local shutdown depend on obtaining one.
- Reconnect uses the same saved identity, bounded backoff and fresh callbacks.
  Missing/old data is never relabelled fresh. Commands are never automatically
  retried after an ambiguous send; a timeout is not success. Native terminal
  events must be correlated to the current request/session and counted once.
- Detailed Downloads export is separately opt-in, defaults off, and is not a
  prerequisite for recovery. Turning it off prevents pending publication and
  preserves the last completed report. One fixed report replaces the previous
  file; identities, keys, certificates and raw command/telemetry buffers do not
  belong in it.

The prepared Kotlin integration uses a single lifecycle coordinator with an
injected durable journal and resident backend. Its stateful failure tests cover
ambiguous START/STOP, process loss, durable commit failures, pending teardown and
same-resident PROBE-before-START ordering. The Downloads switch is independent
of whether cloud connection is enabled: opting in also exports an OFF snapshot.
Disk publication and custom event-history persistence use a bounded, coalescing
writer rather than the cloud-control/UI executor. Turning export off revokes
queued generations; completion updates the service menu. A positively identified
renamed report is preserved and a new fixed-name report can be created, whereas
missing/permission-ambiguous URI ownership is not guessed. Current host checks
do not prove Android MediaStore behavior after reinstall or on vendor firmware.

A final clean-context review found two additional factory-path boundary cases
and both were corrected. A modem APN in `connecting`/`disconnecting` now defers
OFF profile/gate writes while the durable OFF remains pending; a pre-write
read checks again if the transition begins after planning. Once it settles
connected, OFF gives the profile back without waiting for the stock-owned TCP
to disappear. Once disconnected, ordinary gate teardown resumes. Network-loss
handling likewise never sends synthetic-5 during that transition. Unsupported
TCP words (anything except0/1) now remain unknown and stop mutation, rather than
being treated as TCP0. All135 focused cloud/tile checks passed, and the independent
review covered these cases; the read/write race inside an already-started firmware
command is not made atomic by this adapter.

Before offering a custom APK to owners: complete native dependency/lifetime
closure; exercise deterministic failure injection for I/O, cancellation, clock,
network and process death; re-run independent review; then obtain live acceptance
for actual commands, a natural sleep/wake cycle, reconnect and return to factory
mode. Successful synthetic tests alone cannot certify these vehicle behaviors.

The remaining integrated acceptance cases are concrete:

| Trigger | Required observable result |
| --- | --- |
| OFF/ON twice, including while START is waiting for registration | One owner and one chosen pair; no second START before the prior owner is absent; no replay of an actuator command. |
| App/service death before and after custom211 or STOP acknowledgement | The durable ownership and identity-transition state survives; a free native lock alone does not produce a factory-ready state. |
| Loss of the ADB stream or a hung child | Independent lease expiry closes only owned resources; restart does not trust the old socket/PID or stale status. |
| Natural sleep, then phone refresh/command | Real SDK state drives the original wake/queue/timeout paths; no unconditional awake override or fabricated success. |
| TLS/network loss during a command, followed by reconnect | A fresh connection keeps the saved pair and fresh event generation; ambiguous commands are not automatically resent. |
| Custom OFF, then factory mode with an unknown/unavailable original pair | Local custom shutdown completes; factory connection is verified separately, and the UI does not claim success from shutdown alone. |
| Report enabled, renamed externally, then disabled during an update | Exactly the intended report is written; the archive is preserved, queued publication stops and the existing completed file remains. |

The Kotlin fault-injection tests cover app-side ordering and report queue rules;
the firmware replay and emulator tests cover the bounded native helper. These
columns are acceptance requirements for their future integration, not completed
vehicle results.

### Reported Wi-Fi success after SIM removal; native identity cache, 2026-09-24

The owner relays that a forum user's cloud connection worked over Wi-Fi after
removing an rSIM, and describes the installation as soldered. What physical
part was removed is not established; do not infer desoldering, a second SIM,
a retained factory SIM or a particular rSIM design. This is a reported later
success, not a captured before/after experiment. The reattached export is
byte-identical to the April-firmware / build-56 APN1-unknown report above
(SHA-256 `8b09a1e43f4cc2b481e1fe99495aa267d6816c2751278f5661b45933c36fc4da`).
It has attempts=0, TCP=false and no readable registration error, so it must
not be classified with the separate code-3 registration cases. Its operator
46001 does not establish which physical SIM remains after the reported change.

Offline inspection of reference cloudmanager SHA-256
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`
establishes a distinction between SIM presence and the client's cached inputs:

- The packet object's singleton accessor at `0x6dac4` reuses an existing
  object. Its constructor at `0x6d9f4` initializes ICCID (`+0x64`), IMSI
  (`+0x79`) and the IMSI digest (`+0xa7`) to zero.
- `prepare` at `0x6dcf0` reads `ril.csim.iccid` only when the first cached
  ICCID byte is zero (`0x6dd74–0x6dd94`). It skips reading `ril.imsi` when
  both the cached IMSI and its digest have a nonzero first byte
  (`0x6de74–0x6deb8`). Thus these are conditional per-field reads, not a
  fresh SIM read on every connection. The digest's first-byte check also
  means IMSI is not unconditionally cached forever.
- With empty cache and an absent ICCID or IMSI property, `prepare` returns
  failure (`0x6df40–0x6df64`). `send211` at `0x4b150` checks that result
  before constructing or sending registration. The inspected path has no
  fallback that omits SIM identity simply because the transport is Wi-Fi.
- The retained telephony source `public-path-evidence/BydGsmCdmaPhone.java`
  calls `initRadioProp` on `CARDSTATE_ABSENT` (`:1140–1161`), clearing
  `ril.csim.iccid` and `ril.imsi` for phone 0 (`:1203–1215`). Presence / IMSI
  ready repopulates them from `getFullIccSerialNumber` / `getSubscriberId`
  (`:1218–1238`). Other phone IDs use suffixed properties, whereas the
  inspected native client reads the unsuffixed pair.

This provides a concrete mechanism for a running client to retain SIM inputs
after the system properties change or clear. It does not establish that this
forum car used that mechanism, that its cached identities were Chinese, or
that a SIM-free cold start works. Constructor clearing is established; a
complete alias/callback proof of every possible invalidation is not. The
examined environment-reset function `0x4ece0` does not directly call the
packet constructor or `prepare`; its name alone is not evidence that it
refreshes SIM identity. The forum car's April native binary has not been
compared with this reference. No new car operation was performed.

Consequently, a physically installed original Chinese SIM is **not an
established universal prerequisite**. Message 211 carrying IMSI/ICCID remains
established, but a server requirement that those values match the factory SIM
is still only a hypothesis. The supplied success account cannot be used to
assign the code-3 failures to that cause. Retained report, user wording,
assembly and source hashes: `captures/telematics-20260924/forum-rsim-removal/`.

### Build 58: automatic native-cloud diagnostics in the same export, 2026-09-24

To reduce repeated requests to forum users, the owner requested automatic
native diagnostics. `CloudNativeLog` now takes a finite read-only snapshot of
the current cloudmanager PID's main/system log buffers: `timeout 3 logcat -d
-t 1200 -v epoch --pid=...`, selecting the retained main/socket/SSLUtils and
c-ares tags. It does not clear buffers, change logging flags, restart services
or initiate a network request. Native collection failures are separate from
cloud-control failures and never determine a control action.

The export contains only classified events and bounded protocol/status
integers: registration start, send completion (including success/failure),
registration reply/failure code, native network notification/gate, DNS stage,
socket status/error, TLS outcome and missing/invalid cloud identity. Free-text
messages, addresses, certificate subjects and payloads are discarded. Event
timestamps are preserved in UTC, and the report explicitly says retained
events can predate the latest attempt. It does not translate registration code
3 into an unproved vendor-specific cause. Unknown messages are counted;
unsupported formats and unavailable/partial/timed-out collection are visible.

Additional fields are registration/token **flags**, SIM identity **shape**
(`VALID`, `INVALID`, `MISSING` or `UNKNOWN`, never the actual IMSI/ICCID), the
installed APK's SHA-256, report format/export time, and the default network's
interface/MTU/IP-family/DNS-count/private-DNS/proxy/VPN shape without addresses.
While connecting with Internet available, the controller reads every 15 seconds
instead of 60 to retain a useful diagnostic window. Existing notification
backoff/write budgets remain unchanged. Offline/pending-disable polling stays
30 seconds and connected polling 60 seconds; native collection is itself
rate-limited to at most once per 15 seconds.

Native events have their own persisted 512-event / 256-KiB bounded history so
they cannot evict the adapter's existing 512-event / 256-KiB history. Overlapping
snapshots are deduplicated. Both histories go into the **same overwritten**
`Download/Denza Apps/denza-cloud-report.txt`; there is no additional public
file, report rotation or upload. Worst-case combined history is 512 KiB plus
the small report header. The finite log window is not a guarantee of complete
coverage on a busy or differently logging firmware.

Validation: **1,571 JVM tests**, `assembleDebug` and `lintDebug` passed. Tests
cover the recorded successful Wi-Fi trace (16 selected lines with timestamps
converted to epoch), literal firmware error formats, identity/payload exclusion,
wrong PID/tag, empty versus failed/partial capture, process change, bounded
history and overlapping-window deduplication. The actual command and the
compiled parser were also checked read-only against the owner's existing ADB
connection: exit 0, **0.124 seconds**, PID 112, registration=2, token=1, both
SIM shapes valid; 17 current lines were unclassified, with no new connection
attempt induced. Raw output existed only in memory; only controlled results
were saved. This is command/parser proof, not on-car acceptance of the new APK.

Candidate `denza-apps-0.7.0-alpha-b58.apk`, SHA-256
`35ce2a58ad7c9398c5fdfcdb075583e0cb9f8d7a361d6d5d3b02209870346cfe`, is in
Downloads and pinned with source hashes/logs in
`captures/telematics-20260924/build58/`. It was not installed on the car.
At the owner's later request, its APK replaced the existing
`denza-apps-v0.7.0-alpha.apk` release asset at 09:09 UTC on September 24.
GitHub asset **585571706** and an independently downloaded copy match the
candidate SHA-256 above. Release description, title, tag/target and flags were
verified unchanged; publication evidence is in `build58/publication/`.
For the next forum attempt: install build 58, keep Denza Apps open on Wi-Fi,
switch cloud off/on once, wait 2–3 minutes and copy the same report file.
One Wi-Fi attempt is sufficient for the next comparison; repeating SIM setup
is not a prerequisite for collecting the missing native evidence.

### Stock keepalive reboots a parked head unit after ~3.5 h without TCP (firmware, 2026-09-23)

Read from the matching `services.jar`; not observed live. Paths are under
`captures/split-firmware-20260923/jadx/services/sources/com/byd/connectmanager/`.

`BYDConnectManager.initConfigure` starts the 250-second keepalive alarm at
boot unconditionally (`BYDConnectManager.java:139-160`). Each tick runs
`BYDTCPConnectService.do_keep_alive` → `network_repair()`
(`BYDTCPConnectService.java:2616-2638`):

- While `tcp_status != 1`, `tcp_disconnect_times` counts up. `tcp_status == 1`
  resets it, and resets `persist.sys.cloud_reboot_num`.
- At 50 counts (11 with `persist.sys.cloudtest=1`), the service reboots the
  head unit when all of the following hold:
  - ACC is off;
  - `getAutoSystemState() == 2`;
  - the car is not charging;
  - `sys.tcp_reg_errcode` is 0 or 1;
  - fewer than three such reboots are recorded in `cloud_reboot_num`;
  - the VIN is real (17 characters starting `L`).
- `setDeviceReboot()` then broadcasts
  `android.cloudmanager.action_cloud_reboot_action` (reason 50) and calls
  `IPowerManager.reboot(false, "bydcloud", false)`. It skips the reboot while
  a vehicle, pad or OTA update flag is set, or while `sys.gb.connect_type != 0`.

So a stock client without TCP, which is this car on its unregistered SIM, is
eligible for up to three reboots in a row, each after about 50 × 250 s of
keepalive ticks while parked. Whether the alarm fires in deep sleep is
unmeasured. A held cloud link keeps the counter at zero; a parked car that
loses Wi-Fi resumes the count.

The Mac observer only collected logs, sampled state and coordinated
restoration. It sent one ready event; it did not repeatedly publish telemetry
or provide the car's cloud TLS transport. Its independent guard attempts
restoration on owner-process exit; successful restoration still requires a
working ADB connection. Its failure and the owner's final leave-active request
are recorded above. No host test or restoration guard remains running.

### Official app operation-PIN reset, observed 2026-09-23

The current phone UI differs from the path in the manufacturer's published
privacy policy. The live navigation was: **我的 → 设置 → 远程控制安全 → 操作密码
→ 忘记操作密码** (Profile → Settings → Remote-control security → Operation
password → Forgot operation password). The resulting **重置操作密码** form asks
for **身份证号** (identity-card number), shows the bound phone, and provides
**获取验证码** (request SMS code), **验证码** (code), **新密码** (new PIN),
**确认密码** (confirm PIN), and **保存** (save). Its notice says the operation
password change applies to vehicles across BYD-group apps. The form was opened
for the owner; no identity data, SMS code or new PIN was entered by the agent,
and completion of the reset has not been verified. No PIN/identity information
or screenshots of this flow were retained in the research artifacts.

### Phone app English-language investigation, 2026-09-23

Read-only analysis of the previously saved `com.byd.aeri.caranywhere` **9.16.1 /
561** APK, SHA-256
`7bb1f3792837a266668d647e8a4eb349b8a90d7b57b639bcc114c66ad4b95341`.
The phone was disconnected, so no language setting was changed or tested live.
`aapt2 dump badging` reports only default, `zh` and `zh-CN` native locales.
The resource table has 14,744 default string entries, 173 `zh-rCN` and 41 `zh`;
no English-specific string configuration exists. Core entries for Settings,
door unlock and operation password contain only Chinese default text. The
manifest does not declare `android:localeConfig`.

There is nevertheless partial English content: Flutter assets for common,
discovery, e4, magspace, mall and smart_iot contain **1,049** English JSON entries;
the app-level English JSON is empty. Those assets do not establish an English
version of the native vehicle/PIN/settings screens, or a reachable language
selector. The indexed DEX exposes only 487 classes (no aeri business classes in
the earlier index), so absence of a hidden runtime switch is not proven.
Forcing English through Android cannot supply missing native translations;
Android falls back to default resources for untranslated strings, as described
in [Android localization guidance](https://developer.android.com/guide/topics/resources/localization).
A per-app locale experiment could at most establish which existing translated
sections respond. Compact evidence is in ignored
`captures/telematics-20260923/phone-language/`; the temporary 5.7 MB resource dump
was removed after extracting counts and representative strings.

**Live attempt at 15:31–15:34 Moscow:** the owner reconnected and unlocked the
phone. Android API level was 36, system locale `en-RU`, app locales initially
`[]`, and override LocaleConfig null. The supported shell command
`cmd locale set-app-locales com.byd.aeri.caranywhere --user 0 --locales en-US`
succeeded; the getter returned `[en-US]`. After a verified cold start through
the resolved launcher, the vehicle page, discovery and profile still displayed
Chinese. This establishes failure of this particular locale override to switch
those screens, not absence of every possible runtime translation path.
App locales were restored to `[]` by the same command without `--locales`,
and the unchanged system locale was verified. Evidence is under
`phone-language/live-locale-test/`.

At the owner's separate request to keep the screen active,
`stay_on_while_plugged_in` was changed **0 → 2 (USB)** and left at 2; the
30-second `screen_off_timeout` was unchanged. `keep-awake.json` retains the
original values for a later requested restoration. Temporary navigation
screenshots were removed. Packages `com.coloros.translate` and
`com.google.android.apps.translate` are installed; a screen-translation workflow
is a candidate, not yet exercised or configured in this test.

A small network-event adapter is therefore a live-supported path without
replacing the stock executable. Native execution
in a bounded local emulator confirms that `notify_nw(4)` under `double_apn`
opens the stock connectivity gate and reaches endpoint resolution/connection
dispatch. Code 4 is the APN3-ready event, so using it for Wi-Fi would be an
intentional translation, not a report of actual cellular attachment.

[verify_network_gate_native.py](../research/telematics-firmware/verify_network_gate_native.py)
ran **42 cases** against the pinned cloudmanager ELF: both profiles, states -5
through 4, initial gate 0/1, plus endpoint-failure and missing-GUID cases. Only
`notify_nw` executed; calls beyond it were recorded stubs. This independently
checks the branch/state behavior, not complete DNS/TLS or live rollback.

| Public profile event | Native gate behavior |
| --- | --- |
| 2, actual default-network/Wi-Fi connected | Keeps previous value; cannot open a zero gate |
| 4, stock APN3-ready | Sets gate and network-ok to 1, proceeds toward connection |
| -3, actual default-network/Wi-Fi disconnected | Keeps previous value; does not clear a gate opened by 4 |
| -5, stock APN3-disconnected | Clears the gate, resets tcp_step, requests disconnection when previously open |

Thus an adapter needs both connect **and disconnect** handling. Repeatedly
sending 4 or merely leaving a synthetic connected state behind is not a complete
design. The native event recorder also publishes internal network events; the
notification is not a side-effect-free one-byte setter.

Live **read-only** checks at **11:21 UTC**: cloudmanager PID 113, safekeyservice
646, root domain `cloudmanager`, SELinux Enforcing; stock TCP getter remains 0.
APN1/APN3 interfaces are empty, profile is triple_apn, and table 10 is empty.
Kernel route queries for registration IP with both UID 0 and 2000 select wlan0
via 192.168.88.1. This proves the unmarked route lookup, not the mark or route
of a future stock socket. `/system` is mounted read-only; verified boot is green
and shell access to the protected executable is denied. No modification or
privilege change was attempted.

Two possible extra obstacles were narrowed offline:

- `persist.radio.net.lte.apn3.onwifi` is a cellular/Wi-Fi coexistence policy in
  `BydDcTracker`, not a found cloudmanager Wi-Fi-enable switch. Its current value
  is 0. The actual Wi-Fi notification is still mapped to native state 2.
- Public native DNS calls `android_gethostbynamefor_fun_dns` in `libbyddns.so`
  at `0x7E80`. Empty APN3 ifname skips `ares_set_local_dev` (`0x7F1C`). Empty
  APN DNS data reaches the `libcares.so` success return at `0xFF78`, without
  replacing the resolver settings initialized by `ares_init`. Hence these empty
  fields do not by themselves establish a second hard stop. The live activation
  above subsequently confirmed native DNS beyond this gate.

Evidence and the concrete live-test plan are in ignored
`captures/telematics-20260923/stock-client-wifi/`.
[stock_wifi_gate_test.py](../tools/telematics/stock_wifi_gate_test.py) defaults to
preview and normally prepares a 75-second observation with an independent
restoration guard. `--hold` overrides the deadline for the explicitly requested
until-stop run. It pairs state 4 with -5 before restoring the original profile.
Unlike the prior single-report helper, the stock client may automatically
persist a token and process incoming cloud commands. The owner accepted this
full-session scope before activation. Operational restoration does not erase
a legitimate token or undo server registration.

## Stock-client reuse, sleep and incoming commands: current boundaries

Initial offline follow-up after the successful manual uploads; the subsequent
live stock-client result above supersedes the original connectivity uncertainty.

**Reusing cloudmanager:** its current extracted code already assembles report
512, performs factory TLS and implements cloud reception. The shared pre-DNS
connectivity gate still accepts cellular states 1/triple_apn or 4/double_apn,
not actual Wi-Fi state 2. The earlier explicit Wi-Fi notification test did not
restore registration. A stock setting that admits Wi-Fi has not been found.
The live event-adaptation test above demonstrates stock DNS/TLS, connected TCP
and subsequent official-phone data after changing network eligibility. A durable
adapter, sleep/wake behavior and ordinary APK access still need to be established.
The firmware-first sleep investigation below identifies the relevant lifecycle
and Wi-Fi policy; it does not establish parked reachability.

**Existing power-aware connection work:** the retained current framework code
`captures/telematics-20260922/registration-evidence/framework-extra/` shows:

- `BYDConnectManager.java:125–149`: schedules
  `setExactAndAllowWhileIdle(2, elapsedRealtime() + 250000, ...)`, rearming the
  **250-second** wakeup alarm. Its receiver calls both TCP and MQTT keepalive.
- `BYDConnectService.java:73–115`: acquires a partial `KeepAlive` wake lock for
  the work and releases it afterward; checks `sys.cloud.201_send_status` in a
  bounded wait. This is not a permanent screen-on or no-sleep design.
- The saved native cloudmanager strings include MCU sleep/wake transitions
  that stop/start report-544 timers. This is a code lead, not a measured vehicle
  sleep timeline or proof of every wake source.

Do not equate display-off, Android suspend, MCU sleep and complete head-unit
power-off. The alarm path does not prove it runs when the head unit is unpowered,
nor that Wi-Fi stays associated or can wake this vehicle. A normal foreground
service alone does not ensure execution during CPU suspend; see the
[Android power guidance](https://developer.android.com/develop/background-work/background-tasks/awake).
Actual parked sleep/wake behavior, network availability and 12V consumption
remain unmeasured. A low-power cellular/MCU wake path is a candidate; the exact
hardware topology (including whether a separate T-Box owns it) is not established.

**Incoming control exists in firmware:** native receiver `0x573EC` dispatches
downlink messages and includes a repair-mode refusal for vehicle control near
`0x59BCC`. The framework `ICloudRemoteControlService` exposes specific request/
result paths. The separate `cloudctrlserv` binary depends on `libmqttserv`,
`libprotocol`, `libbydauto` and `libpowermanager`; saved strings identify
`sendIndtoMcu`, a wake-sub-device message and MQTT wake locks. These support a
TCP/MQTT → stock dispatch → MCU control architecture, not a completed command
execution trace or proof that every phone button uses our tested TCP session.

The helper has only proved login and upload. Incoming command delivery,
command-specific authorization/preconditions, execution and result reporting
remain untested. If pursued, first establish a non-actuating status-request
round trip and the stock destination; preserve stock checks and command expiry/
deduplication behavior rather than blindly forwarding network payloads to CAN.
Sleep reachability is a separate prerequisite for commands while parked.

### QuickBoot, Wi-Fi retention and recovery, 2026-09-23

**There is a stock policy for retaining client Wi-Fi during ACC-off, and the
stock TCP client deliberately ignores QuickBoot as a full shutdown.** This is a
concrete path to test before designing another heartbeat service. It is not yet
proof of Wi-Fi association, cloud reachability or acceptable 12V consumption in
deep sleep. This investigation made bounded read-only snapshots and analysed
matching firmware; it did not change power settings, send network-ready events,
restart services, install an app or deliberately wake/suspend the vehicle.

#### What the firmware actually does

The matching `services.jar` implements `AccModeManagerService` and its
`Utils.startAccOff/startAccOn` helpers. Source paths below are relative to
`captures/split-firmware-20260923/jadx/`; identities and compact evidence are
indexed in `captures/telematics-20260923/sleep-wake/README.md`.

| Stage | Confirmed implementation | Consequence for an adapter |
| --- | --- | --- |
| ACC-off | `services/sources/com/android/server/accmodemanager/Utils.java:792–821`: notify ACC listeners, request display sleep, set QuickBoot properties, save radio state, send `ACTION_SHUTDOWN` with `from_quickboot=true`, apply radio policy, remove tasks and terminate non-exempt apps | A normal app cannot assume its service, callbacks or alarms survive parking |
| Radio policy | `Utils.java:124–136,189–191`: disable Wi-Fi unless `Settings.Global.byd_off_wifi_switch == 1`; missing value defaults to 0. Hotspot shutdown is separate | Retaining client Wi-Fi has a stock policy input; keeping the hotspot is not implied |
| Warm wake | `Utils.java:723–778`: wake, restore saved radios, clear QuickBoot state, send `BOOT_COMPLETED` with `from_quickboot=true`, notify ACC listeners | The existing boot recovery receiver is a usable restart path even without a complete Android reboot |
| Cloud lifecycle | `services/sources/com/byd/connectmanager/BYDTCPConnectService.java`, shutdown/boot receiver: both QuickBoot-tagged broadcasts return without invoking native shutdown/boot handling | The stock client is designed to treat this as a distinct power transition, but its socket still needs a working network |

`killApplications` / `clearAppAlarms` and the `org.accmode.appkilled` receiver in
`AlarmManagerService` remove the ordinary app's scheduling fallback as well.
The separate live QuickBoot investigation recorded Denza Apps being terminated
at **15:44:50 Moscow**, then started for `RuntimeRecoveryReceiver` at **15:45:14**
and recovered by **15:45:18.2**, after the owner disabled BYD's self-start deny
switch. That is evidence recorded by the other investigation, not a sleep cycle
reproduced here. See [the self-start findings](split-screen-findings.md#the-self-start-switch-and-a-registrar-that-never-registered-live-2026-09-23)
and [the existing recovery owner](adb-authorization-recovery.md#product-behaviour).
On that BYD page a checked switch means **blocked**, and an APK update resets
the block. `setPkg2AccWhiteList` requires `DEVICE_ACC`, held by neither Denza Apps
nor the shell; a second foreground service does not provide that exemption.

The cloud framework already schedules an **ELAPSED_REALTIME_WAKEUP** alarm with
a nominal **250-second** interval and a temporary partial wake lock for keepalive
work. Actual delivery under deep power states remains unmeasured; an alarm
counter is not evidence of that many physical suspend/resume cycles. There is
no need demonstrated yet for an independent perpetual heartbeat or ACC lock.

Native MCU handling is now traced beyond strings: `cloudmanager` function
`0x5d87c` stores MCU state at `this+0x284`; its sleep branch calls `stopAlarm(17)`
at `0x5dac4`. The helper at `0x480c4` logs `stopAlarm = %d`, and framework timer
17 cancels `mAlarmSend544Intent`. A connected/wake branch in `0x4dfac` calls
`startAlarm(17)` at `0x4e838` when MCU state is 1. **Report 544 is not SOC report
512.** This proves a sleep-aware timer path, not that every telemetry report
stops, nor that the MCU or sleeping Wi-Fi can be woken from the cloud.

#### Read-only baseline on this car

At **15:47–15:50 Moscow**, `cloudmanager` was still PID **113**, stock TCP was
**1**, and the retained profile was `double_apn`. The power snapshot was
**Awake**; `accmodemanager` reported `padOn=true`, `mSuspending=0`, and one ACC
lock owned by **`com.byd.sentrymode`**. The code lets an ACC lock hold the head
unit on. This observation therefore cannot demonstrate normal parked sleep.

`settings get global byd_off_wifi_switch` returned **`null`**, and `wifi_on`
was **1**. Under the confirmed stock default, normal QuickBoot's radio-cleanup
path will request Wi-Fi off. The setting was **not changed**. Its visible UI
owner has not been found; the selectively extracted `BydWlan.apk` is a companion
connection service and its examined Java sources do not reference this key.
A bounded recent-log read contained no selected power-transition markers; that
is a coverage gap, not proof that no prior transition occurred.

#### Events worth subscribing to

| Event | Intended use | Access / limitation |
| --- | --- | --- |
| `BOOT_COMPLETED`, including `from_quickboot=true` | Reconcile enabled cloud adaptation after cold boot or warm wake | Use the existing `RuntimeRecoveryReceiver` and bounded recovery cycle; depends on BYD's self-start permission and trusted local ADB if needed |
| Validated Wi-Fi availability/loss from `ConnectivityManager` | Apply the stock client's paired network eligibility transitions once per actual transition | Proposed adapter contract; ordinary-APK access to the native cloud control path remains unproved |
| `com.byd.tcp.cloud.server.status`, extra `tcp_status` | Observe the stock client's connection state and request reconciliation | Confirmed sender in firmware; ordinary-app reception untested. Treat a broadcast as a hint and verify the service state |
| Native cloud Binder death/reappearance | Rebind and reapply eligibility if validated Wi-Fi still exists | Stock restart handling only replays recorded APN states; the one experimental ready event does not establish a real APN3 connection |
| `IAccModeListener` on `accmodemanager` | Observe ACC-off before app termination and ACC-on afterward | Java registration has no permission check, but the documented SELinux service lookup excludes the ordinary APK; shell-side access is the candidate |
| `BYDAutoPowerDevice.getMcuStatus` and a filtered power listener | Distinguish MCU sleep (0) from wake (1) | Device 1005, FID `0x99000003`, SDK read permission `BYDAUTO_POWER_GET`; no live listener registration or app-UID proof in this run |

Do not use `wakeUpMcu`, an ACC lock or repeated screen-on events to turn a
recovery feature into a permanent no-sleep mode. `SCREEN_ON` cannot recreate a
process that QuickBoot already terminated. The existing recovery coordinator
should remain the sole boot owner; native cloudmanager should remain the owner
of identity, telemetry, timers and cloud protocol handling.

#### Next discriminating test

The next useful experiment is a **natural park → sleep → wake cycle**, separately
from Wi-Fi disconnect/reconnect and native service restart. First record the
baseline and confirm no Sentry ACC lock is masking the transition. A controlled
trial of `byd_off_wifi_switch=1` tests whether the stock client can remain
reachable with Wi-Fi retention; its later owner-approved activation is recorded
below. For a
temporary trial the original absent value must be restored by deleting the key,
not by assuming a stored zero is identical configuration.

Use before/after car snapshots and read-only router association observations;
avoid continuous ADB polling during the sleep interval, which can perturb the
measurement. Record cloud online state and telemetry freshness separately:
an intact cloud session does not mean sleeping vehicle buses produce fresh SOC.
Measure actual 12V draw before treating overnight retention as a product result.
If Wi-Fi still loses power or association, the next boundary is the actual
modem/MCU/SoC wake path. An Android subscription cannot execute while its head
unit is completely unpowered; Wake-on-WLAN support and remote wake over this
Wi-Fi path remain unknown.

#### Owner-enabled Wi-Fi retention trial, 16:06 Moscow

The owner explicitly requested enabling the setting, then will park/turn off
the car and inspect the official phone app. At **16:06:38**, a fresh baseline
confirmed an absent retention key (`null`), `wifi_on=1`, stock TCP=1,
`double_apn`, and cloudmanager PID 113. Android was Awake; Sentry still held
one ACC lock. Ordinary parked sleep is therefore not demonstrated by this
baseline.

At **16:06:56**, one command was executed successfully:

```sh
adb -s 127.0.0.1:5555 shell settings put global byd_off_wifi_switch 1
```

Immediate readback returned **1**; Wi-Fi remained on and stock TCP remained 1
with PID 113. Only this setting was written. No service restart, network event,
MCU wake, app installation or Sentry change was made. No continuous observer or
automatic restoration timer was started, so the owner can carry out the parked
test without this task polling ADB. The setting was left enabled for the trial;
the subsequent owner-reported result is recorded below.

The recorded original value was absent. Restore that baseline, when requested,
with `adb -s 127.0.0.1:5555 shell settings delete global byd_off_wifi_switch`
and verify `settings get` returns `null`. Do not restore the separately retained
cloud APN profile as part of this Wi-Fi-setting experiment.

Evidence, the expected behavior/falsifiers and the exact reversal command are
in `captures/telematics-20260923/sleep-wake/wifi-retention-trial-160638/`:
`plan.json`, `before.json`, `activation.json`, `after.json`. A continuing Sentry
ACC lock would make a positive online result insufficient to prove deep-sleep
connectivity. Phone connection state and the displayed update age must be
recorded separately from cached SOC values.

**Owner-reported result, 2026-09-23:** after enabling Wi-Fi retention, the owner
reported that the car remained connected for **about 30 minutes while asleep**,
in the agreed official-phone-app experiment, and considered the trial promising.
This is the first positive parked-connectivity observation for
`byd_off_wifi_switch=1` together with the previously enabled stock cloud client
over Wi-Fi. No continuous host observer was running for this trial.

Evidence is the owner's report, not an independently captured sleep trace or
phone screenshot. Exact interval endpoints, Android/MCU sleep depth, Sentry's
ACC-lock state during the interval, telemetry update age and 12V consumption
were not recorded. The result supports keeping this approach for further
testing; it does not yet establish deep-sleep or overnight connectivity,
fresh SOC throughout the interval, or recovery after a full reboot. No vehicle
command was sent while recording this result, and the setting was not reverted.
The original report is retained as `owner-observation.json` in the trial folder.

#### Adapter handoff questions: read-only verification at 17:39 Moscow

The owner requested answers for the adapter implementer. No handoff, new ready
event, profile/setting write or installation was performed in this check.

**Stock APN3-down events can undo eligibility.** In
`BYDMultiApnConnReceiver.getExtraApnStat`, APN type 2 returns 4 only for
`NetworkInfo.State.CONNECTED` and -5 for every other state. The
`CONNECTIVITY_CHANGE_FUNCTION` branch in `onReceive` requires non-null
`networkInfo`, stores the result and invokes the callback without comparing
the previous state. `BYDConnectManager.networkCallBack` forwards the event to
the TCP service. Both its worker-handler path and its no-handler path call
`stop_work()` for -5 and forward the event to native cloudmanager. The retained
offline native matrix confirms `double_apn`, initial gate 1, event -5 changes
the gate to 0 (downstream operations were stubbed in that offline test).

Whether this occurred in the actual session after 14:40 is **unknown**. The
held run captured only native PID 113, not the system-server receiver/callback
tags named in the question. Its retained logs have no matching selected
markers. The new bounded main/system log window covers only
**17:32:46–17:39:12**, with no selected network-transition markers. These gaps
cannot establish absence of earlier -5 events. A durable adapter should
reconcile only while explicitly enabled and validated Wi-Fi is available,
with serialized, bounded recovery attempts; TCP=0 alone does not diagnose the
cause or justify an uncontrolled stream of ready events.

**Fresh state at 17:39:11:** `persist.sys.byd.apn_type=double_apn`, stock TCP
getter **1**, `byd_off_wifi_switch=1`, `wifi_on=1`, validated default Wi-Fi
network 107, cloudmanager PID **113**, registration status **2**, token flag
**1**, APN1-disable **1**. No matching host Python research runner was present.
This task sent no additional ready event after the single 14:40 activation.
The owner's approximately 30-minute parked result therefore required no
repeat 4 from this task, but no independent TCP getter was sampled at the end
of that exact interval. There is no continuous TCP=1 proof and no claim that
stock code never delivered a network event. At this later snapshot Android
was **Awake**, and Sentry again held an ACC lock; this does not establish the
power state during the earlier interval.

**Ordinary-APK status-broadcast delivery remains untested.** The confirmed
sender uses `sendBroadcast(intent)` with action
`com.byd.tcp.cloud.server.status`, integer extra `tcp_status`, no explicit
package/receiver permission, and `FLAG_RECEIVER_INCLUDE_BACKGROUND`
(16777216). This supports using reception as a hint to read the real status,
but does not prove reception under the target app's registration/lifecycle
conditions. Startup, Wi-Fi and Binder-recovery reconciliation must not depend
solely on receipt of this broadcast.

**Ownership before product handoff:** stock cloudmanager owns the live session;
the retained profile/event and Wi-Fi setting came from the explicitly approved
research steps. There is no research-side resident adapter or active host
restoration guard. The owner asked to leave the connection running. A newly
installed product's missing/false local preference must not silently be treated
as a request to send -5 and restore `triple_apn`; adopt ownership on an explicit
enable action. A subsequent explicit disable can implement the agreed
`-5`/`triple_apn` behavior, which will end the retained connection rather than
restore the handoff-time `double_apn` state. Wi-Fi-retention ownership is
separate: its original value was absent, its last verified value is 1, and it
has not been restored. Installation still awaits the owner's coordination.

Evidence: `captures/telematics-20260923/stock-client-wifi/handoff-20260923T173911/`
contains the current snapshot, bounded network-event scan, historical scan,
source identities and checksums. No raw log buffer or credential was retained.

### Cellular data retention during ACC-off, 2026-09-23

The cellular path has a different policy from the Wi-Fi setting. The inspected
`BydDcTracker` separates default/user data (APN2) from service APNs (APN1/spec,
APN3/function); data-bearer cleanup must not be described as powering off the
whole modem. No persistent cellular equivalent of `byd_off_wifi_switch` was
identified in this inspected path.

The retained source is
`captures/telematics-20260922/registration-evidence/framework-extra/byd-telephony-common.jar-src/sources/com/byd/internal/telephony/dataconnection/BydDcTracker.java`.
At **20:08:57 Moscow**, a read-only hash of the installed
`/system/framework/byd-telephony-common.jar` still matched the previously
verified corpus identity, SHA-256
`76af28cee42ca293d4ad403d1f65832a4a965c8ccfb8db15972570e4af65693a`.

Confirmed code paths:

- The display-0 listener (`:746–765`) calls `handleAccOff(false)` when its state
  leaves ON (2). This telephony policy uses display state as its ACC proxy;
  that callback alone is not proof of deep CPU/MCU sleep.
- `handleAccOff` (`:2455–2465`) schedules APN2 cleanup when
  `sys.gb.connect_type == 0`, user data is enabled, and `b_data_switch <= 0`.
  The delay comes from **`persist.radio.net.accoff_apn2off_delay`**, default
  **3000 ms**. This is a cleanup delay, not a demonstrated modem keep-on switch.
  The delayed handler (`:2369–2377`) checks those conditions again before cleanup.
- `cleanUpConnectionInternal` (`:3144–3151`) explicitly skips the spec/function
  multi-APN contexts. Their setup paths (`:1461–1490`, `:1757–1791`) separately
  require radio ON and data registration IN_SERVICE. Thus ordinary APN2
  cleanup does not establish that the service connections or modem are turned
  off; their actual survival through deeper power states remains unmeasured.
- **`android.intent.action.DATA_CONTROL`** (`:611–669`) is a runtime request to
  retain user data. It accepts `switch`, `package` and optional positive
  `time_out` in milliseconds, maintains `dataSwitchMap` / `b_data_switch`, and
  can schedule a matching release. While display-off, a held request can call
  `activePdfForApn`, but only if user data is enabled, radio is ON and packet
  registration is IN_SERVICE. It does not grant cellular registration or roaming.
- `trySetupData` (`:1210–1221`) blocks ordinary data setup while display-off
  unless that hold exists. `handleAccOn` (`:2410–2419`) cancels pending cleanup
  and clears all holds. This is not a persistent global setting like the Wi-Fi
  retention key. The receiver is registered for phone 0 / WWAN; ordinary-app
  access and caller behavior were not tested, and no DATA_CONTROL was sent.
- The remaining ACC-off handler can attempt radio recovery when out of service
  under its signal/timing/SIM/VIN conditions. Its inspected behavior is not an
  unconditional radio-power-off operation.

**Current read-only observation:** APN1 and APN3 both reported `disconnected`;
voice/data service both `OUT_OF_SERVICE`, WWAN registration `NOT_REG_SEARCHING`.
The current snapshot's reject causes were **0**; do not present the historical
September 22 cause 13 as a current rejection. `mobile_data=0`, `data_roaming=0`,
`sys.gb.connect_type=0`, APN2 cleanup-delay property absent (code default 3 s),
profile `double_apn`, and Wi-Fi retention 1. These are prerequisites/policy
observations, not proof that toggling user data or roaming would yield service.
A data-retention request cannot substitute for successful SIM/network
registration. Continuous cellular registration and modem power during an actual
parked interval have not been measured.

Evidence: `captures/telematics-20260923/sleep-wake/cellular-retention/` retains
the filtered live snapshot, source identities and checksums. No modem, data,
roaming, APN-profile or power setting was changed during this investigation.

## Official-cloud registration and login over Wi-Fi verified, 2026-09-23

**The owner's vehicle completed DiLink registration (211), server discovery
(200), and application login (220) over its Wi-Fi connection. The owner then
observed the car appear online in the official Denza app.** A direct phone
screenshot at 10:38 UTC shows the blue vehicle/signal icon and “updated 1 minute
ago”. It shows **0%** charge, while a fresh stock SOC getter at 10:36:42 UTC
returned **73.0%**. A real SOC upload is **not** demonstrated. No 511/512 report
or other telemetry packet was transmitted by these probes.

This supersedes the unverified registration/login statements in the older,
time-stamped sections below. The initial theory that operation outside China
necessarily requires a working cellular attachment is too strong: Wi-Fi carried
this authenticated session. The helper still used this car's real VIN, factory
SIM identity and factory cryptographic identity; arbitrary identities or another
SIM were not tested. A production on-car sidecar and ordinary APK access remain
unproved. The experiment used Mac-side TLS/protocol code and a shell-UID TCP
socket on the vehicle through the existing ADB connection.

### Live evidence

| UTC | Operation | Confirmed result |
| --- | --- | --- |
| 10:28:14–10:28:16 | 211 to `dilinkreg-cn.denzacloud.com:6001` | 101 bytes sent, 69-byte reply; command 211, reply flag 2, one-byte status **1** |
| 10:30:37–10:30:39 | 200 to `dilinkaddr-cn.denzacloud.com:6021` | 69 bytes sent, 117-byte reply; flag 1; returned **`dilinknat0-cn.denzacloud.com:6041`** |
| 10:33:12–10:33:14 | First 220 attempt | 117 bytes sent, 85-byte reply; local decoder rejected it; application outcome not classified |
| 10:34:35–10:34:38 | Diagnostic 220 attempt | Same reply size; UUID, padding and VIN matched; decoder incorrectly required a constant first two plaintext bytes |
| 10:35:22–10:35:24 | 220 after decoder correction | 117 bytes sent, 85-byte reply; command **220**, reply/login flag **1**, 18-byte body; CRC, VIN and UUID verified |

Each connection verified the server's certificate chain and hostname and used
one factory signature, locally verified before return to TLS. Five application
sessions used five signatures and fifteen public-certificate reads. Combined
with the preceding TLS-only experiments: **six signatures and twenty-four
certificate reads** during this continuation (the earlier local test is separate).
No retries ran automatically. The two extra login attempts diagnosed and fixed
our decoder; they are not evidence that Denza rejected the vehicle. Raw replies
from those first two attempts were deliberately not retained, so their exact
application status cannot be retrospectively proved.

The `220` receiver in firmware uses the reply flag, not the first body byte, as
login status (`0x577EC–0x57854`). The receiving codec (`0x727BC`) checks padding,
CRC and VIN but does **not** require the outgoing `8c09` prefix in a reply.
That unnecessary prototype check was removed; certificate, UUID, CRC and VIN
checks remain. Corrected synthetic tests accept a changed reply header with a
valid CRC and reject an invalid CRC. Connections close after their first reply;
this proves a short authenticated session, not continuous online presence.

### Real inputs and packet construction

- VIN: reviewed `autoservice` native getBuffer transaction **13**, device **1001**,
  FID **0x9900021A**, 17 bytes. Current `libbydauto.so` and `libbydautoservice.so`
  hashes match the extracted libraries used to review this ABI.
- SIM fields: `ril.imsi` (15 decimal characters) and `ril.csim.iccid` (20).
  Registration sends these actual values; login includes the binary MD5 of IMSI.
- Cloud parameters: getBuffer transaction 13, device **1034**, FID **0x99000005**,
  33 bytes: nonzero index, 16-byte AES key, 16-byte UUID/IV. Matching callback
  `guid_ind` at `0x4A498` assigns these to packet-object offsets F2 and E2.
  No key/UUID bytes, VIN or SIM strings are logged or written to artifacts.
- `debug.ro.serialno` is absent. Stock constructor `0x6D9F4` initializes its
  16-byte digest to zero and `prepare` at `0x6DE2C–0x6DE74` skips hashing when
  that property is empty. The login reproduces this stock branch, not MD5 of an
  empty string or an invented serial. Login adds a fresh 16-byte random nonce.

Protocol 3 uses `FE FE 03`, a big-endian two-byte payload length, UUID16, and
AES-128-CBC ciphertext (UUID as IV). Plaintext contains a 33-byte header, body,
CRC16-CCITT (initial FFFF, big endian), then PKCS7 padding. The 211 body is
IMSI15+ICCID20; the public 200 body is `01 01`; the 220 body is nonce16,
serial-digest16, IMSI-digest16. Exact offline codec and bounded ARM64 comparison:
[registration_packet.py](../research/telematics-firmware/registration_packet.py)
and [verify_registration_native.py](../research/telematics-firmware/verify_registration_native.py).
Five synthetic packet fixtures match the actual firmware serializer/AES byte
for byte, including the native IMSI digest; no artificial fixture was sent.

[registration_probe.py](../tools/telematics/registration_probe.py) defaults to a
preview. Its CLI selects a single registration, discovery or login; the later
status uploader also uses its login API. It has no vehicle command path. The native adapter bounds
its packet sizes, one signature, one request, one reply and session duration.
Nine local TLS scenarios cover certificate/hostname failures, wrong signatures,
missing intermediates and fragmented replies of all three request sizes.

Evidence: ignored `captures/telematics-20260923/registration-inputs/`,
`tls-wifi/` and `phone-validation/`. Phone observation is saved separately from
server acceptance. The active-phone setting was temporarily changed with the
owner's authorization; its previous value is recorded for restoration.

Remaining: passively capture a fresh complete stock status report, compare its
SOC to the independent getter, send a measured report during an authenticated
session, and observe **73% (or the then-current reading)** in the official app.
Continuous operation also needs a bounded heartbeat/reconnection design and
handling that never executes unsolicited vehicle-control commands.

### Passive stock state capture after login

The new bounded [CloudCanSnapshotProbe](../tools/telematics/CloudCanSnapshotProbe.java)
reuses the repository's passive shell-UID listener approach. It registers only
YUN device 1034 / **0x99000021**, the exact callback dispatched by cloudmanager
at `0x68BF0` into the status cache (`0x627D4`). It never writes the collection
table or issues a CAN transmission. A 30-second run received **1687 callbacks**,
with **zero drops/errors**, then unregistered normally. The 7.5 KB temporary
on-car JAR was removed after completion.

The retained allowlist covers **24 of the stock 25 CAN-ID/group cache entries**.
All 22 observed `0x4A5 / group 5` frames decode to **73.0% SOC**, matching the
independent getter. The missing entry is `0x417 / group 0`, contributing byte 102
of body 512. SDK names associate it with indirect tyre-pressure warnings. A
read-only bodywork presence getter (`0x41700000`, device 1001) returned raw **2**;
its absent/unsupported meaning has not yet been proven. This is not license to
fill an unmeasured byte with zero. The other non-cache input, `this+0x294`, is
**mChargingStatus**: writer `0x5DF50`, used as a possible override at body byte 98.
Its exact callback/getter and current value must still be matched.

No partial status body was constructed for transmission. Next: resolve the
missing-group semantics and charging override, assemble a complete measured
body, verify it against the native `0x55350` copy routine, then perform one
controlled status upload while directly observing the phone. Evidence and
coverage metadata are in `captures/telematics-20260923/status-passive/`.

## Factory mutual TLS over vehicle Wi-Fi verified, 2026-09-23

**At 10:10:41–10:10:42 UTC, a helper completed a TLS 1.2 handshake with
`dilinkreg-cn.denzacloud.com:6001`, using the vehicle's public certificate and
one stock Binder signature.** The TCP socket originated from shell UID on the
vehicle over Wi-Fi. OpenSSL verified the server certificate chain and hostname;
the server requested a client certificate and sent Finished after the client's
CertificateVerify and Finished. No DiLink registration/login or telemetry packet
was sent. This establishes transport and mutual TLS, not cloud application login
or ownership of the official phone status feed.

The owner authorized further tests after reviewing the TLS plan. The earlier
single local-signature permission was not treated as continuing authorization.
The experiment is implemented outside product apps in
[the host wrapper](../tools/telematics/tls_identity_probe.py) and
[the native OpenSSL adapter](../tools/telematics/tls_identity_client.c).
Nothing was installed on the car; the existing ADB/tunnel/router setup remained
in place. The stock certificate/signature APIs can still take their internal
chip-readiness/recovery paths; those internal branches were not observed.

### Evidence and two corrected prototype assumptions

- Public-profile registration port is **6001**, not the initial fallback 6004.
  `cloudmanager` at `0x4C22C–0x4C238` overwrites the registration-port variable
  in the successful `double_apn` branch. `0x74248` consumes that variable.
  Address discovery uses **6021**. The production hostname was already observed
  in the stock client's `double_apn` logs.
- First prototype used `adb exec-out`, which only copies remote output to the
  host. Its outgoing ClientHello never reached `nc`; that timeout was not a
  Denza TLS rejection. It was replaced with **`adb shell -T toybox nc`**.
  A LAN server then received and echoed all 512 synthetic bytes, observing the
  vehicle Wi-Fi source `192.168.88.109`.
- The next attempt reached ServerHello/Certificate but stopped with verify code
  **20**, before any vehicle signature. The server sends only its leaf.
  A public-certificate-only inspection identified issuer **BYD Identity SubCA
  RSA** and a matching DNS SAN. Supplying the vehicle's reviewed intermediate
  for chain construction resolved the missing issuer. No verification failure
  was overridden; partial-chain trust was not enabled.

Public selectors 5/6/7 returned a root, intermediate and client leaf. Their
signatures link correctly, the two CAs have CA constraints, and all three are
within their validity intervals. The client fingerprint matches the earlier
local test. Three attempts made **nine public-certificate requests and one
signature request** in total. The separate server-certificate inspection and
LAN duplex check made no stock crypto calls. Certificate material from the car
was held in memory or temporary private files removed at the end; private keys
were never exported.

The successful handshake negotiated **TLSv1.2 /
ECDHE-RSA-AES128-GCM-SHA256** with `verify_result=0`. Its server certificate
SHA-256 is `7c190757c2fe122584faf00b88232b5106f2f6630985c68bb19a70886d9c427e`.
The signature was also verified locally before being returned to TLS.
Application bytes sent: **0**. At 10:11 UTC, ADB remained ready, SELinux enforcing,
cloudmanager PID 113 and safekeyservice PID 646 unchanged; no test `nc` remained.

Five host-only scenarios passed against an independent local TLS server:
mutual authentication, wrong hostname rejected before signing, untrusted CA
rejected before signing, bad client signature rejected, and successful chain
building when the server omits its intermediate. The adapter permits one
signature per handshake and has a 35-second deadline. The deprecated OpenSSL
RSA_METHOD API is isolated research code, not a production integration.

Evidence is retained in ignored `captures/telematics-20260923/tls-wifi/`, especially
`live-attempt-3.json`, `adb-duplex-check.json`, `server-public-chain.json` and
`self-test-with-intermediate.json`. The server's public certificate is retained;
vehicle certificate subjects and bytes are not part of the saved report.

Remaining: current registration fields and exact packet construction, accepted
DiLink registration/discovery/login, a fresh measured status report, and an
observed official phone update. Cellular network registration was not needed
for this helper's TLS connection; that result does not remove SIM identity
requirements in the application protocol.

## Factory identity access: local signature verified, 2026-09-23

**An owner-authorized shell helper obtained the stock public client certificate,
requested one signature, and verified it locally on the Mac.** The successful
test ran at **09:50:03 UTC**, after explicit owner approval covering possible
stock initialization/PIN-recovery effects. It used the existing ADB transport
and `safekeyservice`; no private key was exported. This proves local signing
access, not TLS authentication, cloud registration, telemetry acceptance or
an update in the official phone app. Ordinary APK access remains untested.

Offline analysis and selected live reads at **09:36–09:44 UTC** preceded the
approved test. No cloud request, router change, tunnel change, installation or
manual service restart was made. The service's own readiness path can change
chip state as described below; the experiment is not classified as read-only.

### Approved local test and result

[The host probe](../tools/telematics/local_identity_probe.py) requested public
certificate selector **7** once, then signed one labelled random local challenge
using **RSA-2048 / PKCS1v1.5 / SHA-256**. Verification against the returned
certificate succeeded. The signature contained **256 bytes**; the service's
reported length was **512**, matching its hex representation. Certificate and
signature bytes stayed in host process memory and were not retained. The saved
result contains hashes, lengths and verification status only.

The certificate SHA-256 fingerprint was
`8a220bf91c7d9eefba6b942665413dfc5d3c0a2ac8b6e4a45bb89cd899fad832`.
This fingerprint identifies the tested certificate; it does not establish
certificate-chain trust or server acceptance. See
`captures/telematics-20260923/identity-access/authorized-local-crypto-test.json`.
The one-test authorization has been consumed; the test was not retried.

At **09:54 UTC**, ADB remained authorized, SELinux was enforcing and both daemon
PIDs were unchanged. A bounded log query returned no matching lines, so whether
the service took an initialization/recovery branch is **unknown**, not ruled
out by the successful signature. No further cryptographic request was made.

### Current vehicle evidence and access boundary

The current ADB connection is authorized as **UID 2000 / `u:r:shell:s0`**, with
SELinux enforcing. `cloudmanager` runs as root in domain `cloudmanager` (PID 113),
and `safekeyservice` runs as root in domain `safekeyservice` (PID 646). Both PIDs
were unchanged at the end. The latter advertises descriptor
**`android.safekeyservice.ISafeKeyManagerService`** under service name
**`safekeyservice`**.

Five installed library hashes exactly match the recovered current-build files:

| Library | SHA-256 |
| --- | --- |
| `libsafekeyservice.so` | `d6d10902b189b8cb7e8b42b2cf445f01d24407cea3a0c0e44666d1f9f9419f79` |
| `liblodersdf.so` | `8cf35d4feadaae50127465f22eb801ea6fe0e8878633f4dc0ae6e5acd8ff1f97` |
| `libxdjasdf.so` | `338d1ad57a665e7ec91d53c03a0e67f5e4576b5fb3873f7b64698eaf8b63bc8e` |
| `libsdf_crypto.so` | `ef277e341f13a8c2218e11b5bbb9dcc5b9977e0fd72186d62667814944f4ec88` |
| `libssl.so` | `1ad8af56443719957835a5369eab5d1b85718382d6de326b8782a5e76f4cfa8c` |

The daemon executables themselves remain protected and unhashable from shell.
The recovered `safekeyservice.rc` specifies root/system, consistent with the
live process. `/dev/xdjausb0` exists with mode **0660 root:system**, label
`xdja_usb_device`. Shell is not in group system. Direct device access is not
the candidate path. No attempt to open the device was made.

Installed policy files map `safekeyservice` to `safekey_service`. A bounded CIL
attribute/allow trace finds shell service lookup and Binder-call permissions,
and an explicit platform-app lookup grant. It finds **no ordinary-untrusted-app
lookup grant** in the inspected platform/system_ext/vendor files. This is a
source-policy trace, not an exhaustive effective-policy decision or an app-UID
runtime test. The platform CIL hash matches the installed file; system_ext and
vendor CIL were read directly from the vehicle.

One live command exercised the reviewed status entry:

```sh
adb -s 127.0.0.1:5555 shell service call safekeyservice 16
```

It returned `Parcel(ffffffff)`. The matching-build dispatcher maps transaction
16 to `getChipStatus` (`0x16AB0`), whose callee **`0x1AF20` is a constant -1
stub**. That result is expected and proves access to this Binder transaction;
it says nothing about chip health, key provisioning or signing success. Do not
use it as a readiness gate. No arbitrary transaction sweep was performed.

### The Binder signing operation can express the stock TLS primitive

`libsafekeyservice.so` dispatches transaction **6** to `asymmetricSign`
(parcel arguments: function ID, parameter word, String16 input, byte length),
and transaction **14** to `getDataFromChip`. The native reply begins with a
result integer, followed by String16 data and its length; this is not a Java
AIDL `readException()` envelope.

The matching-build daemon's `asymmetricSign` at **`0x14AA0`** accepts raw-RSA
function **10106 (`0x277A`)**. It converts hex text to bytes and calls
`BydSafeKey::RSASignRaw` at **`0x196A0`**. That routine selects the key slot,
length and padding from the parameter word and delegates to
`SDF_InternalPrivateKeyOperation_RSA` at **`0x198A0 → 0x30490`**.
The installed TLS library's callbacks call the same primitive using key slot
**`0x100`**, length **256 bytes**. Thus a Binder-backed TLS signing adapter is a
specific implementation candidate; no private-key export is needed by this
traced operation. The approved test now proves access and compatibility for a
local SHA-256 PKCS1v1.5 signature, but not a complete TLS handshake.

The stock cloud certificate loader uses `getDataFromChip` function **10102**
and certificate selectors **5/6/7**. Do not confuse these with other data/key
selectors exposed by the broader interface. Only public client selector **7**
was invoked in the approved test; issuer/root retrieval remains untested.

### A crypto test is not read-only: initialization has additional effects

Both signing and the certificate getter call `chipIsReady` (**`0x13D60`**).
It begins with **`trywakeUpDriver` (`0x13150`)**, which opens
`/sys/devices/platform/soc/soc:usbcommon/security_op` for writing and writes **2**.
If a session probe fails, it may call `initBydSafeKey` (**`0x17C10`**).
That initializer opens the provider/session and verifies the stock PIN; on a
verification failure its provider-1 branch can call **`SDF_ChangePIN`**, and
another branch calls a PIN-recovery helper. The approved test entered the
certificate/signature APIs; the exact internal branch taken was not observed.
The observed code does not provide a caller flag guaranteeing that a signing
or certificate request will avoid reinitialization/recovery.

Consequently neither "read a public certificate" nor "sign a local nonce"
may be presented as a purely passive probe. The constant status stub cannot
exclude those branches. A crypto experiment must explicitly account for these
stock side effects before execution. The owner explicitly approved one such
experiment, which is now complete. The exact active provider remains unknown:
vendor sysfs/cache and process-map reads were denied, and a bounded retained-log
query had no provider-selection lines. The existing XDJA device name alone is
not proof that its library is the selected provider.

### Transport and remaining proof

The live policy routing table **10 is empty**. Table **1040** has a Wi-Fi default
route via `192.168.88.1`; a kernel route query for UID 2000 selects `wlan0` and
table 1040. This query sends no packet. It supports Wi-Fi transport for a shell
helper, but is not a Denza connection, TLS handshake or server-acceptance test.

The concrete candidate is now:

```text
owner-authorized local-ADB helper
  → stock safekeyservice for certificate/signature operations
  → helper TLS socket over Wi-Fi
  → current DiLink registration/login protocol
  → measured vehicle-status report 512
```

The local-signing step is complete. The next implementation step is a TLS
adapter that delegates client signing to this reviewed Binder operation while
using Wi-Fi for its socket. It must establish the stock certificate chain and
server validation before attempting the current DiLink registration/login
sequence. The registration builder also requires IMSI/ICCID; those current
values and their server-side acceptance remain unverified. None of these
remaining gates is resolved by the local signature result alone.

Only a subsequent measured report and observed official phone update can
establish the desired SOC delivery. No charge was uploaded in this experiment.
The narrow local-test approval does not authorize additional crypto calls or
cloud traffic on its own.

Local files, disassembly, current reads, policy trace, the successful experiment
and hashes are in ignored `captures/telematics-20260923/identity-access/`.

## SOC traced into the stock vehicle-status report, 2026-09-23

**Reading SOC was already solved.** The [vehicle-data allowlist](vehicle-data-findings.md#widget-allowlist-2026-08-22)
records device **1014**, FID **`0x4A505038`**, float getter transaction **7**,
historically observed **43 %**. That getter returns percent already. Do not
apply the underlying CAN scale a second time. This historical read does not
mean the current product catalog polls SOC: `VehicleSignals.kt` currently has
no entry for this FID.

The new result is a matching-build code path from this field to stock
`cloudmanager` reports **512/511**. This is offline evidence, not a successful
upload or proof that the official phone status card consumes those reports.
No car, router or cloud operation was performed. Analysis used saved files and
a bounded isolated emulation of a pure HAL constant-table constructor; the
firmware services and hardware identity provider were not executed.

### SOC field and scale are independently connected

The already-extracted MCU AppBlock from the speaker research has SHA-256
`6c277d1eb819b765d997554fc727835314b9477cae414aa806ea7cf78eda4d02`.
Its receive table at **`0xD7E5C`**, key **`0xE004A505`**, describes CAN
**`0x4A5`, group 5**. Descriptor **`0xEBF14`** points to property record
**`0xA00E0`**, whose FID is exactly **`0x4A505038`**. Coordinates are
**`[7, 0, 8, 3]`**: byte 7 bits 0–7 and byte 8 bits 0–3, using one-based bytes.

This byte convention is established by generic handler **`0x18A09C`**:
it subtracts one from both byte indices before calling extractor **`0x149E90`**.
That extractor assembles the first byte as the low bits and the next byte's
masked bits above it. Therefore, for an eight-byte CAN payload:

```text
raw_soc = can[6] | ((can[7] & 0x0f) << 8)
soc_percent = raw_soc * 0.1
```

The matching ARM64 HAL `auto.default.so`, SHA-256
`5d65fd3b430aec7f2e06496ba427e652613fef995c48c2d4066126dec9f446c7`,
registers this exact FID at **`0x185C70–0x185CAC`**, device **1014**,
value type **4**, converter **`0xE0EC0`**. The converter changes the underlying
integer to float and multiplies by **0.1**. The SDK name
`STATISTIC_ELEC_PERCENTAGE` and the previously observed getter give the semantic
anchor; the MCU and HAL establish the byte layout and scale.

### The stock client copies this field into message 512

All cloudmanager addresses below refer to SHA-256
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`.

| Stage | Matching-build evidence |
| --- | --- |
| Subscription table | `0x61CF0` selects 25 CAN-FD cache entries; index 11 is `0x4A5`, group 5. Its payload cache begins at object offset `0xDF5`. |
| MCU delivery | `0x627D4` reads the CAN ID and group, matches the table, and at `0x62AE0–0x62AF0` copies eight payload bytes from input +10 into the cache. |
| Report assembly | `0x55350`, CAN-FD branch: `0x5544C` reads the two bytes at `this+0xDFB`, i.e. group-5 payload bytes 6/7; `0x55470` copies them unchanged to status-body offsets **`0x54/0x55`**. |
| Body variants | Message **512** has a **104-byte** body beginning with marker **3**. The message-511 variant prepends 16 bytes and uses a 120-byte body. |
| Packet preparation | `0x6F520` chooses message ID 512 for body length 104, otherwise 511, and invokes the common packet builder. |
| Sending | `0x55700–0x55708` calls `sendToCloud(512)` (`0x4AEDC`). It requires `mNetworkOk`, `mCloudEnable`, and the connected/login state at object offset `0x2D0`. |

For the **unframed CAN-FD message-512 body** specifically:

```text
soc_percent = (body[84] | ((body[85] & 0x0f) << 8)) / 10
```

The upper nibble of byte 85 belongs to adjacent information. Values over 100 %
must not be presented as a valid charge. The report contains many other vehicle
fields: this is not a standalone "set battery percent" endpoint, and constructing
the rest of a report from zeros would not represent a measured vehicle state.

Saved September 22 native logs contain `can/canfd offset = 176`, consistent with
this CAN-FD subscription branch (22 transmitted records of eight bytes, after
three entries are skipped). This supports branch selection only; there is no
captured 512 body proving the current SOC, freshness, or server acceptance.
The supplied archive has not been hash-matched to installed protected binaries.

### What this changes for an official-cloud helper

The head unit contains a stock producer whose report includes the known SOC.
An on-car helper can use the already-established local getter as a comparison
source. The unresolved work is the authenticated delivery path and whether
this report updates the official phone feed. The cellular-state bootstrap gate
documented below still stops the historical connection before DNS; knowing the
payload does not remove that gate or establish access to hardware signing.

The inspected `ICloudRemoteControlService` interface has no direct SOC-upload
method. Ordinary JADX also could not retrieve the named phone status classes
from the saved APK: their raw strings are present, but the classes are absent
from its indexed DEX class definitions. Those strings do not establish a phone
API contract. Neither observation rules out other interfaces.

Evidence, assembly and a hash manifest are retained in ignored
`captures/telematics-20260923/battery-upload/`. The offline
[message-512 inspector](../research/telematics-firmware/inspect_status512.py)
accepts only a saved 104-byte body; it has no encoder or network access. Synthetic
checks validate masking/range handling, not vehicle or cloud behavior.
Next: establish which existing on-car identity interface can serve the helper,
then compare a real status body with the read-only getter and the phone feed
when an authenticated session is available. No successful upload is claimed.

## Bootstrap traced: cellular gate, routes and hardware TLS, 2026-09-23

Offline follow-up traced the recovered **current-build** client through network
initialization, route selection, TLS, registration and token receipt. No vehicle,
router or cloud operation was performed in this follow-up. The executable's
installed hash remains unverified; the package/version alignment described below
is the basis for comparing its code with the saved vehicle logs.

### The recorded private-profile failure stops before DNS

There is stronger historical runtime evidence than the preceding analysis
recognized. `captures/telematics-20260922/token-cause/registration-failure-excerpt.log`
records, at **09-22 14:30:06.755**, `triple_apn`, the private registration domain,
`mApn1Connected IS 0`, then immediate `domain prasr faile` and empty addresses.
The recovered private resolver `0x52ce4` prints precisely the byte at object
offset `0x35e` and returns before its DNS call when that byte is zero. Thus the
recorded private-profile failure is supported by both the logged value and the
matching-build branch, not merely the wording of a DNS error.

For the **public** profile the evidence is less direct: `0x52ea0` checks the same
byte but does not print it. The saved Wi-Fi experiment records `notify_nw state=2`
at **16:46:09.680** while `double_apn` is selected, followed by another immediate
public resolver failure. After restoring `triple_apn`, the same log prints
`mApn1Connected IS 0` at **16:49:15.495**. This supports the gate explanation for
the public failure, but is not a direct measurement of the byte during that
public attempt. These are September 22 observations, not a fresh live check.

Initialization and writer analysis now establish:

| Location in current `cloudmanager` | Result |
| --- | --- |
| Constructor `0x48954`, store `0x48aec` | Initializes the connectivity byte `0x35e` to zero. The distinct network-ok byte `0x35d` starts at one; do not conflate them. |
| `notify_nw`, store `0x4b9f4` | Sets `0x35d/0x35e` to one only for state **1 + triple_apn** or **4 + double_apn**. |
| `notify_nw`, store `0x4b810` | Clears `0x35e` for state **-2 + triple_apn** or **-5 + double_apn**. |
| Wi-Fi state **2**, jump table `0x1e10a` | Reaches the epilogue without setting the gate, after profile bookkeeping. |

A scan of direct stores and locally tracked object-pointer aliases found no
additional gate writer or Wi-Fi/configuration branch that enables it. Two bulk
memory-write candidates belong to a separate socket buffer, not this object.
This is bounded static evidence, not a formal proof against all indirect aliases.
A router DNS/proxy change cannot affect this observed pre-DNS return.

### The client also selects cellular routes, but their failure is not fatal here

`addIPRoute` (`0x74db4`) reads `net.lte.apn1.ifname` for `triple_apn`, otherwise
`net.lte.apn3.ifname`. Its helper constructs
`ip route add <address> dev <interface> table 10 proto kernel scope link`.
The send dispatcher calls it for registration, address discovery and the working
server. **All three inspected callers continue without checking its result.**
Consequently this proves the intended routing policy, not a second demonstrated
hard stop. The inspected socket creation/connect path sets keepalive and has no
`SO_BINDTODEVICE` call; process/UID routing rules and actual route selection have
not been established. Wi-Fi transport after the gate is therefore an open
question, not something this route string alone rules out.

### Public TLS uses standard encryption and a hardware-backed client identity

The public-profile socket creates a TLS helper (`0x80598`). `ssl_init`
(`0x7ed2c`) restricts its protocol to **TLS 1.2** and requests
`BYD_TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`. The newly extracted stock `libssl.so`
explains that prefix: exported `SSL_CTX_set_cipher_list` (`0x44860`) installs
client-certificate callback `0x448f0`, then substitutes the ordinary
`TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` cipher name. The prefix selects local
certificate integration; it is not evidence of a proprietary wire cipher.

Two complementary certificate paths were traced:

- Cloudmanager's certificate loader (`0x7ef74 → 0x471bc → 0x47768 → 0x478f8`)
  obtains CA/client-certificate material through `getDataFromChip` and
  `libsafekeyservice`.
- The TLS client-certificate callback opens an SDF device/session, reads its
  certificate and attaches RSA method table `0x5f3c8`. Its signing callbacks
  `0x46c80/0x46f70` call `SDF_InternalPrivateKeyOperation_RSA`. This path delegates
  signing to the provider; it does not require exporting the private key.

These are static call paths. No device key, live certificate or signing operation
was accessed. They make an on-car helper that retains the factory identity a
specific candidate, but do not establish that an ordinary APK can use it.
The extracted `cloudmanager.rc` runs the stock service as **root**, with groups
`root system`. Its launcher adds `/system/lib64/edge` to the library search path;
the extracted directory index has no `libssl.so` override there. Runtime loaded
libraries and access permissions still need separate evidence.

`liblodersdf.so` selects a provider from `libxdjasdf.so`, `libsdf_crypto.so` or
`libhuada.so`; the actual vehicle selection has not been established.
**Do not use `SDF_OpenDevice` as a read-only probe:** its initialization can call
`attempt_to_get_vendor_id`, which creates/writes `/collect2/vendorName.txt` when
the cache is absent. The next access check should inspect existing code,
permissions and saved observations without invoking this initialization.

### Token is downstream of successful login

The current client, rather than the older Dolphin comparison binary, gives this
sequence:

```text
211 registration → successful reply → 200 server discovery
  → resolve returned working-server domain → 220 login
  → successful login → request 507 if token_flag != 1
  → receive 507 token → write cloudToken.dat → set token_flag=1
```

Relevant current-build functions are `send211` (`0x4b150`), registration-result
handling (`0x54964`), message dispatch (`0x573ec`) and post-login work (`0x55b30`).
The registration packet builder (`0x6e00c`) also requires nonempty IMSI/ICCID
inputs; this does not establish that those inputs are missing on this vehicle.
No actual identifiers were read in this analysis. Current login is **220**;
the earlier comparison client's 221 must not be carried over to this build.

The 507 receiver validates token length before saving
`/data/system/cloud/cloudToken.dat` and setting the property. An absent token can
be explained by never reaching this stage; manually creating a token file is not
a demonstrated bootstrap solution. The historical reason for an earlier token's
absence/deletion remains unknown.

### Consequence for the requested official-cloud sidecar

The first observed obstacle is the stock client's cellular-state gate. Beyond
it lie route selection, hardware-backed authentication and the actual telemetry
producer. A separate helper on the car using an authorized stock interface is
worth investigating; a working implementation has **not** been established.
Successful DiLink login would still not prove ownership of the official phone
app's SOC, range or online feed.

Next bounded work: trace the SDF provider/access interface offline, and identify
which current component produces the official vehicle-status data. If the saved
corpus cannot establish the selected provider, a later read-only inventory can
check existing service/device permissions and logs. Do not invoke SDF, send
cellular notifications, register a replacement client or upload test telemetry
as part of that inventory.

Additional binaries, annotated call sites, selected historical log lines and a
hash manifest are retained locally in ignored
`captures/telematics-20260923/network-bootstrap/`. The extracted TLS library's
SHA-256 is `1ad8af56443719957835a5369eab5d1b85718382d6de326b8782a5e76f4cfa8c`.
Only selected files were read from the original archive; no full image or second
archive was created. The existing car/SSH/router state was not touched.

## Matching archive decoded and cloud client recovered, 2026-09-23

**The archive-readability blocker is resolved.** A readable recovery from public
DiLink 5.0 supplied an algorithm that successfully decoded this owner's Di5.1
package. Earlier sections below preserve the investigation stages; their
statements that no decoder/current cloud binary was available are superseded
by this result. No firmware installation or live vehicle operation was involved.

### Proven extraction chain

1. The normal download link on the [public DiLink 5.0 catalogue page](https://modhub24.com/firmware/firmware_3c4bf282)
   issued a range-readable signed URL for `Di5.0_23.1.22.2505209.1_0.zip`.
   Earlier raw-file 403 responses did not apply to this normal download flow.
2. Range reads located the plaintext OTA manifest. Selected boot operations
   yielded recovery from its ramdisk; compressed-operation SHA-256 checks
   passed. Recovery SHA-256 is
   `5ec7ae12ea06ec17e89c874630d83cbd4598e269321df3ca9021750fc2df2bac`.
   The full boot partition was not read or hash-verified.
3. Static recovery analysis showed that Config's seed comes from MD5 of the
   complete adjacent `metadata` file. The derived AES-128-CBC key and IV opened
   the owner's 7,504-byte `Config.xml`: valid PKCS7, 7,497 plaintext bytes,
   valid XML, SHA-256
   `f6f7cfc212bbf5b60f4a3159e1e132c85b587267fdfa25e97c22c319a1111179`.
4. The Android `Package` value in that XML supplies a wrapped seed. Recovery's
   `0x1f759c` decoder recovered it, and the same file cipher opened
   `Android/Target/android.zip`. Plaintext size is **10,632,835,603 bytes**,
   with valid ZIP structures and 13 final padding bytes. Initial indexing and
   small metadata CRC checks read only 69,456 ciphertext bytes.
5. A read-only, seekable AES view and an on-demand OTA/ext4 reader extracted
   selected files without creating another 10 GB archive or a 16.8 GB system
   image. Every fetched data operation passed its manifest SHA-256 check.

| File from the supplied package | Size, bytes | SHA-256 |
| --- | ---: | --- |
| `system/bin/cloudmanager` | 583,632 | `9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9` |
| `system/bin/cloudctrlserv` | 125,832 | `5f4ed1e19f3ff430e5b40a1f3dc2f001b2d50bf942db08bd9789a6fc36eab33f` |
| `system/bin/mqttserv` | 633,400 | `31d3196145b7ab48e179bf2c9bdcb3032a217b9507e2f29b6cceecf3c10db4fb` |

The relevant `libbyddns`, `libcares`, `libstateservice`, launcher script and
build properties were retained too. Cloudmanager contains the version stem
`di5.1_cloudmanager_V0.1.0_MP_20260626_184216_054ae27`, consistent with the
previous live log. Matching package metadata and version strings are evidence
of build alignment; the protected installed executable has **not** been hashed
against this extract. Whole inner payload/partition hashes and vendor
authenticity have not been verified by this selective read.

Reproducible local scripts and the decoding details are in
[research/telematics-firmware](../research/telematics-firmware/README.md).
Compact binaries, source indexes, readable Config and annotated disassembly
are preserved in ignored `captures/telematics-20260923/readable-firmware/`.
No identity/account secret is required for the demonstrated decoding path.

The saved scripts were rerun into a fresh temporary directory: all **eight**
selected files matched the retained SHA-256 values. That reproduction read
20,022,752 compressed OTA bytes and checked 27 operation hashes. Source archive
size, modification time and inode were unchanged. The retained artifact set is
about **4.8 MB**; **205,827,781 bytes** of acquisition/analysis scratch were
removed, including large images, CPIOs, decoded-operation caches and the signed
download URL. The separate reproduction scratch was removed too. Artifacts
are local and Git-ignored; nothing was committed or uploaded.

### Current-build network gate, now established in code

Addresses below refer to the recovered Denza `cloudmanager` hash above.

| Evidence | Exact behavior |
| --- | --- |
| `getSSLIPByDomainName`, inferred function `0x52ea0` | `0x52ef4–0x52ef8` reads byte `this + 0x35e` and returns false when it is zero. The resolver `android_gethostbynamefor_fun_dns` is called only afterwards at `0x52f24`. |
| Private resolver path `0x52ce4` | Logs the same byte as `mApn1Connected`; checks it at `0x52d68–0x52d6c` before calling its resolver. |
| `notify_nw`, inferred function `0x4b694` | Jump table `0x1e10a` sends state **2** directly to the epilogue `0x4ba4c`, after profile bookkeeping. It does not set the connectivity byte. |
| Connected branch `0x4b940` | Sets bytes `0x35d/0x35e` at `0x4b9f4` only for state **1 + triple_apn**, or state **4 + double_apn**. |
| Disconnected branch `0x4b75c` | Clears `0x35e` at `0x4b810` for state **-2 + triple_apn**, or state **-5 + double_apn**. |

The saved current Java `MultiApnConnReceiver` identifies state 4/-5 as APN3
connected/disconnected. The previously traced ordinary Wi-Fi callback uses
state 2. Therefore selecting the public profile changes the selected cellular
notification to APN3; it does not make the native gate accept Wi-Fi state 2.
This is a concrete explanation for why a working DNS service and a delivered
Wi-Fi notification can coexist with an immediate domain/IP failure.

**Follow-up:** initialization, writer candidates, routing and token handling are
now traced in the bootstrap section above. That follow-up also links the saved
private-profile `mApn1Connected IS 0` log to this exact field; the earlier claim
that no runtime value was available was incomplete. No process-memory read or
fresh vehicle observation was performed. The public-profile gate value remains
inferred. No successful official-cloud sidecar or status update has yet been
demonstrated.

## Stock updater handoff traced, 2026-09-23 (earlier stage)

### Where a readable component could come from

A public-source follow-up identified candidates, **not a matching decoder**:

- The [DiLink 5.0 internals analysis](https://byd-wiki.github.io/docs/internals/)
  documents unpacking `Di5.0_23.1.22.2505209.1_0.zip`, including `boot.img`
  and `vendor_boot.img`. The exact package is listed on
  [ModHub24](https://modhub24.com/firmware/firmware_3c4bf282). Its catalogue is
  readable, but bounded direct-file and prescribed download-endpoint requests
  returned HTTP 403 in this session; no image was downloaded or inspected.
- The public [BYDcar Di5 archive](https://github.com/BYDcar/BYDPackagesByChip3/tree/main/Di5)
  has GitHub tree objects for two Denza D9-era Di5.0 packages from September
  2022, stored as multipart `.zip.7z.*` files. These are older comparison
  candidates, not verified sources for the 2026 Denza Di5.1 decoder.
- [Magisk issue 6717](https://github.com/topjohnwu/Magisk/issues/6717)
  names DiLink 5.0 boot/recovery images, but its linked Drive folder returned
  HTTP 404. The link is not a usable source at this check.
- [i99dash/dilink5-sim](https://github.com/i99dash/dilink5-sim) explicitly
  excludes firmware files from the repository. Its firmware findings are
  useful pointers, not an available binary dump.

The concrete next check is whether a readable candidate's recovery installation
code handles the supplied `Config.xml` / `Android/Target/android.zip` layout.
Only a matching handler and supported key source justify a fragment-decode
test on the owner's archive. Being an Android recovery image or bearing a
DiLink label alone does not establish compatibility. No matching current
binary or validated decoder was found in this source check.

An alternative input from an existing authorized dump would be the native
`cloudmanager` for `Di5.1_USER_SIGN_SX166_202607050112_Q0414`, its ELF dependencies
if needed, and build metadata; this avoids the archive decoder entirely.
An existing service/donor dump may supply it, but no provider's possession or
ability to export it has been verified. Ordinary current ADB access has already
been measured as insufficient for the protected files; repeating the same
pull is not the next step. No contact was made with third parties.

### Verified installer path

The USB installer was traced through the **current vehicle's framework**:
the saved `framework.jar` and `services.jar` hashes match live read-only
`sha256sum` results. The relevant BYD five-argument `RecoverySystem.installPackage`
overload decompiled successfully; JADX failed on the separate standard
three-argument overload, which is not the call used in this traced USB path.

```text
IviUpdatePresenter.requestRebootIntoRecovery()
  → OTGUpdateModel.rebootIntoRecovery(path, "udisk", downgrade)
  → RecoverySystem.installPackage(context, file, type, reset, backlight)
  → RecoverySystemService.setupOrClearBcb(true, command)
  → uncrypt --setup-bcb
  → reboot into recovery-update
```

This is a **static call chain, not an executed update**. The Java overload
constructs a boot command containing the package path, update type, ICCID,
IMEI, VIN, optional reset flag, locale and backlight argument. It does not
decode the package or pass an explicit key/factor argument. Real identifier
values were not read. Their presence in a command does not establish that
they are used as decryption keys.

`setup-bcb` writes the recovery boot command through the `uncrypt` socket.
Despite the executable's name, this branch is not evidence of BYD archive
decryption. The actual reader of the opaque Android member remains beyond
the recovered handoff.

Other candidates were narrowed without running them:

| Candidate | Static result | Limit |
| --- | --- | --- |
| `UpgradeUtil.AESDecrypt()` in the saved UpgradeServer APK | Reads `ecu.secretKey` from update strategy metadata for `_Media_BYD_00200001`, Base64-decodes it and delegates to `Cipher.getInstance("AES")` | No caller found in recovered UpgradeServer sources; not established as the supplied USB archive's decoder. File mode uses provider defaults, unlike the explicit transformation in the byte-array helpers. No strategy/key file was read. |
| Current `libupgradeserver_jni.so` | JNI calls lead to `UpdateInNormal::UpdateMcu` and `UpdateDspRes`; `updateVehicle` returns zero immediately | No Android package-opening path found in this wrapper |
| Current `libupdateserv_aidl-cpp.so` | Binder interface exposes `hotfixInstall` / `hotfixUninstall` | Interface plumbing, not a recovered firmware decoder |
| Saved `libbydupgrade.so` | Confirmed direct decryption calls belong to `DecryptMcu` / `DecryptEcu`; `FileZip::ParseConfigFile` loads XML through TinyXML | No demonstrated connection to the opaque Android member |

The recovery executable is not exposed at the checked `/system/bin/recovery`
or `/vendor/bin/recovery` paths. Metadata checks on `uncrypt`, `update_engine`,
`byd_updated` and vendor `libdecrypt.so` return `Permission denied` for the
current shell. `boot_a` and `vendor_boot_a` resolve to root-only block nodes;
their contents were not read. No recovery-named by-name partition was seen,
and recovery's exact placement has not been established. An init entry for
`/vendor/bin/install-recovery.sh` exists, but that script and the checked
recovery-image patch/copy paths are absent. The init entry is not a usable
recovery image.

The public [BYD internals unpacking example](https://byd-wiki.github.io/docs/internals/)
starts from readable `update.zip` / `payload.bin` in an older Di5.0 package.
It does not demonstrate removing this Di5.1 package's opaque layer; its script
was not downloaded or executed.

**Result:** the handoff is established, but the Android member's algorithm and
key source are not. A small-fragment decoding test therefore still lacks a
verified implementation to test. The next useful input is readable matching
recovery installation code (or a verified decoder for this package format),
or directly the current native `cloudmanager` and required libraries. These
are alternatives; obtaining recovery is unnecessary if the current cloud
client becomes readable independently. The firmware work does not yet explain
the current client's immediate `getSSLIPByDomainName` failure or establish a
working official-cloud sidecar.

Compact excerpts, library hashes and access observations are retained in ignored
`captures/telematics-20260923/updater-chain/inspection.json` (under 15 KB).
The two small copied libraries and single-class decompilation scratch directory
were removed after retaining this evidence. The original download and earlier
research corpus remain in place. No updater, BCB operation, reboot, settings
change, registration request or cloud upload was performed.

## Downloaded firmware inspection, 2026-09-23

The owner supplied
`~/Downloads/Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip`.
It was examined in place, without extracting a second large archive or
contacting the vehicle. Its size is **10,856,386,829 bytes** and SHA-256 is
`bfb4e57834f5b15f30b2d3d94009d0bf756e95225c7afea34145e949ab023014`.
All **38 outer ZIP members passed a complete CRC check**. This verifies
consistency with the archive's stored checksums, not vendor authenticity;
the whole-package signing certificate was not independently authenticated.
The source file's size, modification time and inode remained unchanged.

Plaintext metadata matches the previously measured installed build:
`post-outswver=34.1.33.2605218.1`, Android 13 fingerprint
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys`,
and `post-inswver=Di5.1_USER_SIGN_SX166_202607050112_Q0414`.
This is a matching-build candidate; metadata alone does not establish the
identity of every packaged file with the installed system.

The outer inventory consists of the Android package, `Config.xml`, ANC/DSP/MCU
and screen update files, plaintext `metadata`, and `META-INF/com/android/otacert`.
It contains no separately exposed `cloudmanager` or recovery executable.
The two members relevant to opening the Android filesystem remain opaque:

| Member | Result |
| --- | --- |
| `Android/Target/android.zip` | Stored without outer ZIP compression/encryption at byte offset 86; length 10,632,835,616 bytes; nested ZIP parser returns `BadZipFile: File is not a zip file` |
| `Config.xml` | Length 7,504 bytes; XML parser rejects the binary content |

The **entire** Android member was scanned, rather than just its beginning.
No `CrAU` Android payload header, ELF64 little-endian header, XZ stream header,
`cloudmanager` or `getSSLIPByDomainName` string was found. Four short ZIP-marker
matches were checked against their surrounding fields and none formed a
plausible ZIP header. Nineteen 64 KiB samples, including the payload offset
advertised by metadata and the end of the member, have entropy
7.99676–7.99738 bits per byte. These observations are consistent with a
whole-content encryption layer, but do not identify its algorithm or establish
that decoding is impossible. The member SHA-256 is
`f66acb711c7fdad2b61c606aa5a524abf4e52c92b7eee4e9a1bebe6ae8a496e2`.

The full `Config.xml` and first 4,096 Android bytes exactly match the earlier
HTTP range samples. Obtaining the full archive therefore allowed integrity
checks and a complete content scan, but **did not expose the current native
cloud client**. Ordinary ZIP extraction cannot yet provide its code.

A final static cross-check of the already saved `libbydupgrade.so` confirmed
that `UpdateToolKit::DecryptMcu` (`0x51dd8`) tail-calls `byd_decrypt_md5`
at `0x51e40`. That routine opens a caller-supplied factor file before using
AES-128-ECB. Its connection to this Android member remains unestablished;
finding an MCU decryption routine is not a demonstrated Android unpacker.
No factor/key material was read and no updater/decryption routine was run.

Further exact-code analysis needs a verified decoder for this package layer
or an already readable native file from the matching build. This inspection
does not establish a working sidecar or change the earlier measured
registration failure. Evidence is the compact JSON report in ignored
`captures/telematics-20260923/archive-inspection/inspection.json`.
No temporary files, extracted images or archive copies were created; the
retained report is under 18 KB, alongside this documentation update. The
owner's original download remains in place.

## Latest findings: vehicle investigation and authorized APN test, 2026-09-22

The requested outcome is **sidecar → Denza cloud → official Denza phone app**.
An independent dashboard is not the target. The owner confirms a master account
and successful sign-in on the vehicle; repeat account-login checks are not a
remaining task.

| Channel | Measured result | Limit of the result |
| --- | --- | --- |
| IVI Internet | Validated default Wi-Fi network; TCP to `emqx-cn.denzacloud.com:8884` succeeds | Does not prove MQTT authentication or ingestion |
| Native `cloudmanager` | `getTCPStatus()` returns 0. `triple_apn` selects `dilinkterminalreg-cn.iov.denza.cloud` with APN1 unavailable. An authorized `double_apn` test selects public `dilinkreg-cn.denzacloud.com`, but still fails obtaining its IP | The public branch exists; neither profile established registration in the observed cycles |
| Separate native `mqttserv` | `isConnected()` returns 0 | Does not identify its selected broker or failure reason |
| Android `CloudServiceApp` | Repeated failure obtaining `cloudToken.dat`, followed by rejection of an empty/short token before MQTT connect | This app suppresses the original exception; independent `AutoiotService` evidence below identifies a missing-file error, but not its cause or the phone feed's owner |

The two native reads used Binder transactions 7 and 8 respectively, after
checking both Stub and Proxy in this vehicle's `framework.jar`. No register,
publish, network-notification or configuration transaction was called.
`persist.sys.cloud.app_reg_status=0` and `persist.sys.cloud.token_flag=0`;
the Android app's `sys.cloudserviceapp.mqtt` property is empty. Empty properties
are not zero-valued error codes.

### Registration failure located in a full keepalive cycle

At **14:30:06 on 2026-09-22**, a passive 265-second capture covered the stock
250-second keepalive timer. Unlike the earlier short captures, it caught the
current native `cloudmanager` (PID 113) explaining its reconnect failure:

```text
registDomain is dilinkreg-cn.denzacloud.com
addrDomain is dilinkaddr-cn.denzacloud.com
apn_type is triple_apn
domain is dilinkterminalreg-cn.iov.denza.cloud
mApn1Connected IS 0
domain prasr faile
addr = 211: _200:
tcpReconnect,but can not parse domian ip,mParseCount:0;
```

This is direct evidence from the installed Denza build, not the older Dolphin
comparison. **The observed reconnect stops at endpoint resolution in the
`triple_apn` branch, with APN1 unavailable.** It does not reach a registration
response in this cycle. The missing token is a separate confirmed downstream
prerequisite failure; this trace makes unsuccessful registration a likely
explanation for it, but does not prove when or why the token file disappeared.

Independent read-only checks immediately afterwards:

| Domain | Car Wi-Fi | Google and Cloudflare public DNS |
| --- | --- | --- |
| `dilinkterminalreg-cn.iov.denza.cloud` | `unknown host` | NXDOMAIN, DNS status 3 |
| `dilinkreg-cn.denzacloud.com` | Resolves to `139.159.228.68`; ICMP reply in 321 ms | Same A record |
| `dilinkaddr-cn.denzacloud.com` | Resolves to `113.45.216.69`; ICMP reply in 249 ms | Same A record |

Thus general Internet access is present, but the observed native registration
branch selects an address unavailable in the tested public DNS views and
reports no APN1 connection. The internal-looking domain and APN selection are
consistent with a carrier-private path; reachability from the intended carrier
network was not measured. The native log does not reveal whether it actually
issued a DNS query or returned early because APN1 was absent.

The two public names are real candidates, not interchangeable replacements
established by this test. ICMP proves host reachability only; service ports,
protocol compatibility, authentication and the phone's main feed remain
unverified. The subsequent authorized profile test below confirms selection
of the public registration name, but not a working connection over Wi-Fi.
No hostname mapping was changed. A generic geographic proxy is not a
demonstrated repair.

The installed `ClientConfigurationService` additionally confirms that
`cloud_server` and `cloudservice_enable` configuration messages are forwarded
to native `setControlConfigure()`. Its `sys.cfg_client_ready=1` is assigned
during local initialization, so it is **not** evidence of downloaded cloud
configuration or completed registration. Its diagnostic `sys.tcp_step` is
currently empty and supplies no extra error code. None of these configuration
methods was invoked.

Selected capture and DNS evidence, the installed configuration APK hash and
source pointers are in ignored `captures/telematics-20260922/token-cause/`.
The detailed report is `token-cause-findings.md` beside that folder.

### Authorized public-path test: endpoint selection works, connection does not

The installed BYD wireless diagnostic tool has real `double_apn` and
`triple_apn` radio buttons (`Tab_repair2`, `tab_repair2.xml`). Its
`sendSwitchApnPolicy()` sends the stock `RADIO_CONFIG` action with operation
`set_default_data` and the chosen `apn_type`. Current hashes of both the tool
APK and `byd-telephony-common.jar` match the analyzed local copies.

The current implementation traces the complete Java-side transition:

1. `BydGsmCdmaPhone.handleDefaultDataConfig()` calls
   `BydDcTracker.onMultiApnTypeChange()`.
2. Selecting `double_apn` sets `persist.sys.byd.apn_type`, deactivates the
   dedicated APN1 connection if present, sets its disable flag to 1, clears
   APN1 network properties, and marks APN1 unsupported. The current APN1 CID
   and interface are already empty. The examined handler does not disable
   Wi-Fi or toggle general mobile data.
3. For the default-data phone, it sends `SIM_AND_APN_TYPE_CHANGE`.
   `CloudServiceApp.AppsReceiver` converts `double_apn` to an internal native
   notification with `APPID=0`, `cmd=4`, `data.apn_type=0`; `triple_apn` maps to
   1. It forwards this through the registered native listener.
4. The phone implementation also switches marker files in `/collect2/vsim/`.
   `BydNetworkUtils.initApnPolicy()` reads those markers before the build
   default at startup. This is a persistent policy change, not a temporary
   UI selection. The stock reverse transition to `triple_apn` exists.

The owner explicitly approved one bounded profile test, including possible
automatic registration by the stock client and return to `triple_apn`.
At **15:23:10 MSK**, the stock `RADIO_CONFIG` operation was delivered once
to `com.android.phone`. Radio logs and properties confirmed `double_apn` and
APN1 disable=1. No active APN1 bearer existed (`cid=-1`). Wi-Fi routes remained
unchanged immediately after the switch.

During 270 seconds of passive observation, the **15:24:16** stock keepalive
cycle logged:

```text
apn_type is double_apn
getSSLIPByDomainName is dilinkreg-cn.denzacloud.com
tcpReconnect,but can not parse domian ip,mParseCount:0;
```

Thus this exact installed native client **does select the public registration
branch**. It still failed obtaining an address in the observed cycle. At
15:25:28–29, ordinary car-shell DNS and one ICMP request per hostname succeeded:
`dilinkreg-cn.denzacloud.com` → `139.159.228.68` (291 ms), and
`dilinkaddr-cn.denzacloud.com` → `113.45.216.69` (227 ms). Default network 100
remained validated Wi-Fi. This narrows the unresolved problem to the native
client's resolution/network-selection path; it does not identify a specific
resolver fault, prove a DNS packet was sent, or establish protocol compatibility.

Both native connection getters remained 0 at each 45-second sample through
270 seconds; registration and token flags stayed 0, and the Android MQTT
property stayed empty. No successful registration was observed. This test
does not establish a usable sidecar-to-Denza upload path or an updated phone
status card.

At **15:27:44**, the same stock operation returned `triple_apn`. Radio,
CloudServiceApp and native logs confirm receipt and return to the original
private-domain/APN1 branch. At 15:27:49, every sampled property matched its
baseline, APN1 disable=0 and ADB was available. The Wi-Fi route was preserved;
network 100 was still validated at 15:28:18. Incidental multicast route entries
on the internal Ethernet interface differed, so the full routing dump is not
byte-identical. No service restart, APK install, manual token operation or
hostname override was used. As disclosed before approval, marker-file contents
were inaccessible and their exact original state cannot be certified.

One host log reader stopped on invalid UTF-8 from native output. A replacement
reader decoded invalid bytes safely without repeating the profile switch; it
captured the decisive public keepalive cycle and rollback. The immediate
native reaction to the first switch was not fully recovered. Consequently,
absence of other traffic from this selected log is not evidence that no
server exchange occurred anywhere in the interval.

The plan, result and selected evidence are in ignored
`captures/telematics-20260922/public-path-test-plan.md`,
`public-path-test-findings.md`, and `public-path-test/`.

**Next read-only lead:** the readable current `/system/lib64/libbyddns.so`
(94,040 bytes, SHA-256
`360d8351d2efa0b2896028941f8dd8151a21780d3b444187dd06327f7d862050`)
exports separate APN DNS routines. Its `android_gethostbynamefor_spec_dns()`
selects APN1 DNS, optionally binds c-ares to `net.lte.apn1.ifname`, and uses
`persist.radio.dns.query.mode` (default `1`). That proves an APN-specific
resolver implementation exists in this firmware, **not** that the observed
`getSSLIPByDomainName` calls it. The exact current caller and failed return
condition remain to be located before proposing any DNS or routing change.

### Resolver follow-up: fast local failure, still no identified DNS cause

After the owner asked to continue, a second bounded test used the same stock
profile transition and restoration, with a complete redacted text stream from
native PID 113 across all logcat tags and an additional `c_ares_dns` filter.
The readers handled invalid UTF-8 without stopping. No log-level property,
resolver setting or routing rule was changed.

At **15:41:40**, this captured the immediate Java-to-native notification and
the next steps: `send211`, preparation of FuncID 211 (101 bytes), public
`getSSLIPByDomainName`, empty address fields (`211: _200:`), then
`send211,but can not parse domian ip`. Packet contents were redacted before
saving. A prepared packet is not proof of transmission. The subsequent
`clear542Cfg clear542inFile fail!` is recorded without assigning a cause or
claiming the inaccessible configuration file's state was restored.

The normal keepalive at **15:45:06.898** again printed the public hostname;
the failure followed at **15:45:06.899**. This approximately 1 ms log interval
is consistent with an early local check, cached result or asynchronous resolver;
it is not a measured DNS round-trip or proof that no query was issued. No
`c_ares_dns` explanation appeared. The exact resolver/caller remains unknown.

Independent checks narrowed, but did not solve, that gap:

- Route lookup for the public registration IP with UID 0 and UID 2000 both
  selected Wi-Fi table 1040. A process-specific socket bind/mark is not ruled
  out by ordinary route lookup.
- The current `libbydpolicydns.so` implements source/domain/APN and fallback
  rules. It contains none of the three exact registration-domain literals;
  generic, wildcard and dynamic rules remain possible. Its involvement in
  this failure was not established.
- Readable `libmosssl.so`, `libmoscurl.so` and `libnetd_client.so` did not
  identify `getSSLIPByDomainName`. The name alone does not establish a TLS
  handshake failure. A selected recent audit/crash window showed no matching
  denial, but this is not complete historical or permission proof.
- `netd` diagnostics returned `FAILED_TRANSACTION`; `NetMonitorService`
  returned no dump. No `tcpdump`/`tshark` is available in the shell PATH.

The second 270-second observation again showed connection getters and
registration/token flags at 0. At **15:46:13** the profile was restored; the
15:46:19 snapshot matched every sampled baseline property, with ADB available.
A subsequent check confirmed validated default Wi-Fi network 100. Both host
log readers finished. No restart, installation or manual token operation was
performed.

The next useful independent measurement is a bounded DNS/connection trace at
the owner's Wi-Fi access point, correlated with native timestamps. Availability
of an administered router versus phone hotspot has been asked of the owner;
no router access or packet capture was attempted pending that information.
Changing DNS or constructing a generic proxy is not yet an evidence-backed fix.
Detailed evidence and limits are in ignored
`captures/telematics-20260922/resolver-followup/findings.md` and
`resolver-trace-test/`.

### MikroTik observation: SSH works, packet capture is disabled by device-mode

At 15:52–16:01 MSK the owner's RB5009UG+S+ (`192.168.88.1`, RouterOS
7.19.5 stable) accepted SSH as `admin` using an existing key. DHCP and ARP
confirmed the car at `192.168.88.109`. Car-only connection tracking showed
DNS request/reply pairs with the router, multiple established internet TCP
sessions, and an unanswered `SYN_SENT` flow to `10.167.206.17:9105`.
This confirms the private-address attempt reaches the home router; it does
not identify the sending process or establish why the remote path fails.
The shared DNS cache contained several Denza service names, but no selected
registration names in the snapshots. A shared cache snapshot cannot prove
that a particular client or process did not issue a query.

The proposed DNS-only capture did **not** run. Although sniffer configuration
was accepted, `start` left `running=false`; the router's log records
`script error: not allowed by device-mode` at 15:57:54. The effective
`/system device-mode print` is `mode=home`, `sniffer=no`, `flagged=no`.
The PCAP contains only its 24-byte header, zero packets. Absence of a native
DNS query cannot be inferred from it. The host observer was stopped at
15:59:15 before its APN-switch step, so there was no third `double_apn` test.

All five temporary sniffer fields were restored exactly at 15:59:17; the
one empty capture file was retrieved and subsequently removed from the
router. At 16:01, its absence, stopped sniffer and continuing router uptime
were checked. All 16 sampled car properties match baseline, including
`triple_apn` and APN1 disable `0`; ADB remains `device`. No router DNS,
firewall, routing, device-mode, or offload setting was changed. The host
harness now checks both device-mode and `running` before any car test.

The next capture requires `/system device-mode update sniffer=yes`, changing
only that capability. Official MikroTik documentation requires physical
confirmation and says the router reboots after confirmation. This would
interrupt the existing transport, so the update has **not** been issued;
the owner subsequently ruled out a router reboot because it would interrupt
other work. That constraint remains in force; the device-mode update is not
a pending action. A DNS-service log alternative was subsequently verified without
reboot (see the next section). Evidence and the bounded test plan are in
`captures/telematics-20260922/mikrotik/`. Reference:
[MikroTik device-mode](https://help.mikrotik.com/docs/spaces/ROS/pages/93749258/Device-mode).

### No-reboot DNS observation: controls pass, native lookups do not enter the router resolver

The owner ruled out a router reboot because it would interrupt other work.
At 16:07–16:15 MSK a temporary, separate 1000-entry memory logger successfully
recorded only selected registration/control DNS names. The effective topic
filter is `dns,!packet,!raw`, not `dns,debug`: this RouterOS build emits its
query/done events without the debug topic. A unique control lookup from
`192.168.88.109` was visible before the native test began. No device-mode,
DNS, firewall, route, offload, sniffer or tunnel change was needed.

The existing bounded stock test observed a triple_apn failure at 16:10:06,
then double_apn from 16:10:12 through 16:14:46. Both its immediate native
attempt and its 16:14:16 keepalive selected `dilinkreg-cn.denzacloud.com` but
failed obtaining its address; TCP/MQTT and registration/token flags remained
0. None of the selected registration names entered the router's DNS-service
journal during those attempts. The journal's seven entries did not fill its
buffer. All 16 sampled properties matched baseline after stock rollback.

Separate ordinary lookups **after rollback**, at 16:14:52–53, were recorded
as queries from the car. The router returned `139.159.228.68` for the public
registration name and `113.45.216.69` for the public address name; ICMP to
both answered. The private registration lookup was recorded but returned
unknown host to the caller; the selected text log does not establish rcode.
Thus the home router's ordinary DNS can resolve the current public names,
while the native service is taking a different failing path.

This is not a full packet capture. Thirty-five car-only connection snapshots
also show direct DNS to `114.114.114.114`; queries sent there are outside the
router resolver journal. Early local failure, another resolver, cached state
or another bound network are still possible. Absence of all native DNS
traffic, a specific failed resolver function, and a proxy/DNS fix are not
established. The observed `10.167.206.17:9105` flow still does not identify a
process.

A supporting static check of current `libcares.so` (version string 1.17.1)
found that `ares_set_servers_csv` returns success immediately for an empty
string, without replacing its initialized server list. Empty APN DNS
properties therefore do not alone prove that no resolver is configured.
Its actual use by cloudmanager's failing function remains unproven.

The temporary logger and action were removed at 16:14:55; original logging
configuration matched exactly, router uptime continued, and car profile/
ADB were restored. Evidence: `captures/telematics-20260922/mikrotik/`
`dns-log-test-3/findings.md` and neighboring logs. The network investigation
can continue without reboot using this validated DNS journal, sampled
connections and the local firmware corpus. Full packet visibility would
require a separately available capture point or a later maintenance window.

### Direct resolver controls and Wi-Fi notification path, 16:29–16:38 MSK

After the owner confirmed that SSH worked again, the investigation resumed
without changing router configuration or repeating the APN transition.

The current framework provides a Wi-Fi notification path: ordinary
`CONNECTIVITY_CHANGE` reads the supplied `NetworkInfo`, falling back to
`getActiveNetworkInfo()`, and maps CONNECTED to state **2**. The callback in
`BYDConnectManager` forwards it through `BYDTCPConnectService.notify_nw()` to
native `cloudmanager`. Dedicated APN1 uses state **1**. These are distinct
states; their presence in code does not prove which state the current native
process remembered. The selected recent logs and service dumps did not expose
that internal value. No network notification was manually sent.

Current `libcares.so` disassembly confirms Android resolver-list initialization,
fallback to `net.dns1` through `net.dns8`, and ultimately `127.0.0.1` when no
server is configured. All eight properties were empty in the read-only sample.
However, **loopback DNS is working on this car**: root-owned UDP/53 listeners
exist on IPv4/IPv6 loopback and internal interfaces, and `dnsmasq` PID 2314 is
running. Its command line includes `--no-resolv` and `--listen-mark 0xf0063`.
Socket UID and process presence alone do not prove socket-to-PID ownership.

Direct UDP DNS requests from the existing ADB shell returned matching query
IDs, rcode 0 and `139.159.228.68` for `dilinkreg-cn.denzacloud.com` through
each of `127.0.0.1`, `192.168.88.1` and `114.114.114.114`. Observed elapsed
times were approximately 45, 50 and 260 ms, including ADB/command overhead;
these are not isolated wire latencies. Consequently, neither empty global DNS
properties nor a missing loopback resolver explains the failure on its own.
This does not establish the resolver, socket mark, UID policy or early return
condition used by the native public-registration function.

Two additional direct loopback controls at 16:38 returned
`113.45.216.69` for the public address name and rcode **3 / NXDOMAIN** for
`dilinkterminalreg-cn.iov.denza.cloud`. The local resolver therefore answers
both successful and negative queries; it is not simply an unresponsive port.

The first raw-query harness produced no output even for the router control;
it is invalid evidence of DNS failure. Holding stdin open with `adb shell -T`
produced the validated responses. The bounded host command was then ended;
its cleanup exit code is not a DNS result. No diagnostic `nc` process remained
in the subsequent process check.

The older Dolphin comparison has an APN1-connected check before its resolver
call, but lacks the current `getSSLIPByDomainName` implementation. Its state
layout or acceptance of network notifications must not be assumed for this
Denza build. Readable current IPC libraries did not supply the missing
implementation. The next precise static target remains the current native
function and its caller; the installed executable is unreadable to this shell,
and a matching extractable firmware image is not yet available.

The Android app also contains stock energy-statistics upload code
(`EnergyUploadService`, `NOTIFY_ENERGY_RANKING`), gated on MQTT connection.
Its fields cover consumption and mileage statistics; this does not establish
ownership of the phone's main SOC/range/online card. The previously identified
`CloudControllerManager.publishMqttMessage()` still requires an existing MQTT
connection and restricts this implementation to the supported watch channel;
it is not an established generic vehicle-status upload API.

At 16:37:14 the profile remained `triple_apn`, APN1 disable=0, both connection
getters and registration/token flags were 0, and ADB was `device`. No car
setting, service, installed app, token, route or router setting changed in
this continuation. Evidence is in ignored
`captures/telematics-20260922/resolver-search/`.

The subsequent owner-approved test below replayed only the true Wi-Fi-connected
state **2** to the native service, during a bounded public-profile window with
stock rollback. The exact plan and limits are recorded in that evidence
directory as `wifi-notification-test-plan.md`. State **1** was not sent to
represent an APN1 connection that does not exist.

### Wi-Fi notification replay delivered, registration still fails, 16:46–16:49 MSK

The owner approved proceeding after the explicit state-changing test request.
Before the test, Wi-Fi network 100 was the validated default, profile was
`triple_apn`, APN1 disable=0, and both native connection getters were 0.
The existing ADB transport and tunnel were retained.

At **16:46:06.225**, the stock profile operation reached native cloudmanager
and selected `double_apn`. Its immediate public registration attempt again
failed to obtain an address. At **16:46:09.382** the host sent exactly one
`service call cloudmanager 1 i32 2`, verified against the current framework's
Stub and Proxy. The CLI returned `Parcel(NULL)` without an error; delivery is
established independently by native PID 113 logging at **16:46:09.680**:

```text
notify_nw()  state = 2
persist.sys.byd.apn_type is double_apn
```

At **16:47:37.010**, the framework's normal 250-second keepalive timer fired.
The native attempt at **16:47:37.014** again logged the public registration
name, `getSSLIPByDomainName`, empty `211`/`200` address fields, and
`tcpReconnect,but can not parse domian ip,mParseCount:0;`. All four observation
samples (15, 60, 105 and 150 seconds from test start) showed TCP/MQTT getters
and registration/token flags at 0, with Wi-Fi still validated.

**Result:** simply replaying the framework's actual Wi-Fi-connected state does
not repair the observed public-registration failure. This rules out a missing
single notification as a sufficient explanation/remedy in this test. It does
not prove how the native implementation handled state 2 internally, rule out
all initialization/state-ordering problems, or identify the exact failing
condition in `getSSLIPByDomainName`. No successful registration or phone-card
update was observed. The prior direct DNS controls remain separate evidence;
they do not establish this process's resolver path.

Once the keepalive failure and subsequent status samples were captured, the
host harness was intentionally ended through its SIGTERM/finally path, before
the maximum test duration. Stock rollback began at **16:49:15.166**, and native
logs confirm `triple_apn` at **16:49:15.494**. At **16:49:18.641**, all **16**
sampled properties matched baseline; APN1 disable=0, validated Wi-Fi network
100 and ADB `device` were confirmed. The public-profile window was about
189 seconds. The harness's exit 130 records deliberate host interruption;
rollback completion is established by its separate final snapshot. The
independent deadline guard exited after verified restoration and did not
need to issue a command. No test log reader or guard remained afterwards.

No Wi-Fi toggle, router change, restart, install, manual telemetry publication
or false APN1 notification was used. As before, inaccessible marker-file
contents and the prior internal cached network state are not byte-for-byte
verified. The evidence is in ignored
`captures/telematics-20260922/wifi-notification-test/`, including the exact
executed host-script hash and restoration comparison. The reusable host
diagnostic is [wifi_notification_test.py](../tools/telematics/wifi_notification_test.py);
its guard subsequently changed to a relative delay for portability across
macOS Python versions, and payload filtering was tightened without another
vehicle run.

### What the older Dolphin binary can establish

The available comparison remains useful without waiting for a full Denza OTA.
It is the ARM64 `cloudmanager` from
[the Dolphin research corpus](https://github.com/wheregoes/byd-dolphin-hacking),
pinned at commit `d2677796660cc685faaa3ba2d078e6846ce4c1b1`, version string
`di3_6125f_mp0712_dev.202505070`, SHA-256
`25fcfbcf4b81346ac5d5861308fc0577b7dfa484d8c9ae9227e65e27c2b5648d`.
The artifact hash was rechecked. Static analysis of this binary establishes:

- **Notification handling:** the routine beginning at `0x284e0`, identified
  by its `notify_nw()` log, branches only for state `1` and state `-2`.
  State `2` reaches the epilogue at `0x286b8` without setting its connectivity
  fields. State `1` stores 1 in the adjacent fields at object offsets `0x19d`
  and `0x19e`; the APN1-disconnected branch clears `0x19e`. This is a real
  example of a delivered ordinary-network notification doing no useful work
  for this native client.
- **Resolution gate:** the function at `0x2bdc0` checks the byte at `0x19e`
  and returns false if it is zero (`0x2bde8–0x2bdec`). Only after passing that
  check can it call `android_gethostbynamefor_spec_dns()` at `0x2be20`.
  Thus a caller can report address-resolution failure without issuing DNS.
- **Error propagation:** the endpoint routine at `0x28810` resolves the
  registration and address-service names through this gated function and
  returns a readiness flag. The `send211` path tests that result at
  `0x28078–0x2807c` and prints its generic domain/IP error at `0x28170` on
  failure. The endpoint routine uses the old global BYD domains; these are
  not the Denza public names measured on the current car.
- **Token provisioning:** the previously traced incoming-message branch 507
  writes `cloudToken.dat` and then sets the token flag. This supports examining
  the registration-to-token sequence as well as network selection.

These are code facts about the comparison artifact. The current Denza logs
show `getSSLIPByDomainName` and public/private profile selection that are not
the same recovered implementation. The old binary therefore supplies a
concrete hypothesis and a function map, not proof of the current native gate,
field offsets or a working replacement client. Sending state `1` merely to
make a flag true is not a justified next step: the APN1 branch also invokes
other initialization/vehicle-side work, while this car has no connected APN1.
No comparative binary was executed and no new car operation was performed
during this static follow-up.

A readable copy of the current Denza executable is needed to verify its
code-specific behavior; a full OTA is one possible source, not a prerequisite
for comparative analysis. Evidence pointers are saved in ignored
`captures/telematics-20260922/dolphin-comparison/`.

### Dolphin comparison: registration, session selection and token delivery

The subsequent static pass recovered the following bootstrap sequence in the
same pinned Dolphin binary. Function names below are inferred from strings,
callers and behavior; the executable is stripped. Addresses belong only to
that artifact.

```text
VIN and SIM identifiers available
  → resolve registration/address-service endpoints
  → 211: register terminal
  → 200: obtain working server IP and port
  → 221: log in to that server
  → 507: request token if token_flag != 1
  → save cloudToken.dat and set token_flag = 1
```

| Stage | Static evidence | Scope of the conclusion |
| --- | --- | --- |
| Registration inputs | `send211` checks VIN; packet builder `0x3a164` checks cached IMSI and ICCID and returns on empty values (`0x3a1f8`) | Available SIM identity and cellular network registration are separate prerequisites. This does not establish missing identifiers on the Denza |
| Registration response | Dispatcher stores the 211 response status at `0x2f588`; handler at `0x2d48c` treats status 1 as success and advances to function 200 (`0x2d688`), or an internal queue branch | The token file is not a prerequisite for constructing this registration request |
| Server selection | Response 200 supplies IPv4 address and port (`0x2fb48–0x2fba8`); `0x2fd6c` passes them to `setServerInfo`, then `0x2fd7c–0x2fd88` queues function 221 | The initial registration hostname is not necessarily the destination of the working session |
| Login success | Function discriminator is 221 at `0x2f560`; status 1 sets the native connected field (`0x2f888`) and calls post-login work (`0x2f908 → 0x2e538`) | Some nearby text logs say 220; the recovered discriminator is 221 |
| Token request | Post-login work reads `persist.sys.cloud.token_flag` at `0x2e634`; unless its value is 1, it prepares and queues function 507 (`0x2e688–0x2e6b8`) | In this implementation token acquisition follows successful native login |
| Token storage | Incoming 507 branch validates a nonzero length below 129, passes the returned bytes to the file writer (`0x30684`) and sets the flag only if the writer succeeds (`0x306f8`) | Merely creating a file or setting a flag would not establish receipt of a server-issued token |

The current Denza Android `CloudServiceApp` independently reads that same
file through `CloudServiceApp.lambda$mqttInit$0`, using
`ConfigConstants.SECURITY_KEY_FILE`. Its MQTT connection code rejects an
empty/short token before connecting. The missing file was already confirmed
by the current `Autoiot` exception. Together these observations support
**failed native bootstrap as an explanation for the absent MQTT token**;
they do not prove that the current native executable retains the complete
Dolphin sequence or explain the file's historical disappearance. There is no
demonstrated need to supply a token merely to begin terminal registration.

The old native sender also performs APN-specific routing outside the low-level
socket routine. `addIPRoute` at `0x3e534` reads
`net.lte.apn1.ifname`, builds a host-route request, and calls `0x3f690`.
That helper assembles an `ip route add` operation using the APN1 interface and
table 10 (`0x3f71c–0x3f784`); an empty interface prevents that operation. Three
call sites in the send dispatcher are at `0x3e24c`, `0x3e27c` and `0x3e2b0`.
**Those callers do not check its return value before continuing**, so this is
evidence of intended APN routing, not proof that a missing route alone makes
the old client's TCP connection impossible. The inspected socket-creation
sequence (`0x49328–0x494a0`) contains no explicit interface bind before
`connect`; that does not rule out routing policy or process-wide selection.
The current Denza public branch may use different routing code.

One initially promising telemetry string was narrowed rather than promoted
to a result: `send state of charge` at `0x33b18` belongs to a routine receiving
`mChargingStatus`. It maps input 1 to status 5 and other values to 6, and queues
function 502 only when the native session is connected and its configuration
flag is enabled. Its packet builder (`0x3af80`) includes a status and timestamp.
This is a charging-state event, **not evidence of a battery-percentage upload
or ownership of the official app's main vehicle card**.

For the requested sidecar, this distinguishes two designs. A network helper
would need the stock client to reach endpoint resolution, use a working route
and complete server-selected login/token delivery. A replacement uploader
would additionally need the current protocol and the actual vehicle-card
feed; the old packet map does not establish either. A router proxy cannot
change an internal client gate that prevents a request from being issued.
Whether such a gate explains the Denza public-path failure remains a
hypothesis: its captured error still occurs at `getSSLIPByDomainName`, before
the later stages mapped here.

This follow-up used existing files only. No vehicle or router operation,
registration request, token access or telemetry publication was performed.
Selected instruction ranges and the stage map are saved in
`bootstrap-routing.asm` and `bootstrap-routing.json` alongside the earlier
comparison evidence.

### Follow-up without the full firmware archive

The following records the preceding investigation; the full-cycle capture
above resolves its previously unknown native endpoint and immediate failure.

**A specific missing-file error is now established.** At 14:13:27 and 14:13:32
on 2026-09-22, running `com.byd.autoiot.service` reported
`java.nio.file.NoSuchFileException: /data/system/cloud/cloudToken.dat`.
Its installed APK's `VehicleUtil.getAskToken()` (`y3.p.a()` in the decompilation)
opens that exact path using `Files.newInputStream`, catches the real exception,
logs it and returns an empty string. Thus this is a missing-path error as seen
by that process, rather than an inferred token-format error. Combined with
`CloudServiceApp`'s empty-token failure, it narrows the immediate prerequisite
failure to availability of the shared vehicle token. It does not establish why
the file is absent, when it disappeared, or which current component must create
it. Owner/master-account sign-in is a separate, already confirmed fact.

`Autoiot` also contains a platform-dependent token request by broadcast
(`APPID=15`, `cmd=2`). Current `CloudServiceApp.AppsReceiver` forwards these
requests through `CloudServiceImp` to its native listener. This is not a
demonstrated independent provisioning path; no broadcast was sent.

**The failure has history.** The installed `BydWirelessTools` exposes a readable
diagnostic provider at `content://com.byd.wirelesstools/net_status`. A projection
limited to network fields and an aggregate returned 2632 records, spanning
2026-08-11 17:25:44 to 2026-09-22 14:05:33, with no `mqtt_status=1` or
`apn1_status='connected'` record. In its current code, `mqtt_status` is 1 only
when `mqttserv.isConnected()` returns 1; a missing service or IPC failure also
leaves it at 0. This establishes no recorded success, not continuous outage
between samples or absence of earlier reports. **The same table's `tcp_status`
is a latency-test classification (`tcpAvgDelay`), not `cloudmanager` status.**
Do not use its zero values as historical native-cloud TCP measurements.

**Profile selection remains partly opaque.** The current configuration is
`triple_apn`; APN1's disable flag is 0, but APN1 and APN3 are disconnected.
`persist.radio.net.lte.apn3.onwifi=0` and `net.apn3.wifi=wifienable` coexist.
In current `BydDcTracker`, that flag value routes a Wi-Fi event to
`setActiveStateForApn((byte) 0, true)`; it is not proof that Wi-Fi is forbidden.
The APN1 routing table 10 is empty. Packaged MQTT files contain public and
private addresses plus environment flags, but no observed selector establishes
which one the native client currently uses. A short passive log capture did
not reveal a registration response or identify the selected endpoint.

**A separate HTTPS telemetry path exists, but is not a verified replacement.**
Installed `com.byd.CanDataCollect` has Denza production defaults under
`https://newcan-cn.denzacloud.com:9999/can-api/api/dataCollected/` for collection
configuration and file uploads. Its ordinary connected-network callback has
no mobile-only gate, and the examined configuration request reads vehicle/SIM
identity without reading `cloudToken.dat`. It uploads configured CAN batches;
no link to the official phone's SOC/online card was established. The current
test-mode property is empty, but a private dynamic configuration can override
the default URLs, so the actual active destination remains unverified.

The default host resolved to `113.45.51.1` on two public resolvers. Bounded TCP
connects to port 9999 timed out from both the car and Mac; the shell route from
the car was through `wlan0`. No application request or telemetry was sent.
These observations identify another channel to study, not a working ingestion
route or proof of a geographic restriction.

At that stage the current native registration endpoint was unknown; the
full-cycle capture above subsequently identified it. The token producer's
current implementation and the public-path selection rules remain unresolved.
The full firmware archive was not needed for these measurements, and the
opaque archive by itself does not guarantee readable implementation code.

### Wi-Fi and registration

In the current `services.jar`, `BYDMultiApnConnReceiver` forwards an ordinary
connected network as code 2, without a mobile-only gate in that branch.
`BYDConnectManager` and `BYDTCPConnectService.notify_nw()` pass this to the
native client. Therefore the earlier assertion that the stock client cannot
receive Wi-Fi connectivity events is unsupported. The later full-cycle trace
shows that its observed registration branch still selects `triple_apn` and
fails to obtain its selected endpoint while APN1 is disconnected.

Dedicated APN1/spec has separate modem-registration gates in `BydDcTracker`.
The Android modem is OUT_OF_SERVICE and APN1/2/3 were disconnected. This does
not prove every Denza cloud operation requires a carrier-private APN.

`CloudServiceApp` names the production broker
`ssl://emqx-cn.denzacloud.com:8884`. Car TCP reachability and Mac-side verified
TLS were measured separately; car-side MQTT authentication was not attempted.
Other IVI domains (`apr-cn.denzacloud.com`, `idilink-cn.denzacloud.com`,
`dilink-mqtt-cn.denzacloud.com`) resolved to `1.1.1.1` on the car and independent
public resolvers. Firmware contains public and private MQTT profiles, but
the active choice has not been established. A general proxy is not yet an
evidence-backed repair.

### Sidecar/API and firmware limits

The current APK's `CloudControllerManager.publishMqttMessage()` requires an
already connected MQTT client, accepts only AppID DMS 101, and implements the
`V/1/V2W/` watch-message branch. It is not a verified SOC/status upload API.
The APK also has an energy-ranking uploader; its relation to the phone's
main status card is unknown. Phone APK strings name status queries such as
`getCurrentStateByVin` and `latestVehicleCondition`, but no supported upload
contract was recovered from their names alone.

The installed build is `34.1.33.2605218.1`, fingerprint
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys`.
A [public firmware package](https://modhub24.com/firmware/firmware_41d3df51)
contains matching plaintext metadata. Only byte ranges were fetched; the
whole-package signature was not verified. The outer ZIP is readable, but its
`Android/Target/android.zip` and `Config.xml` are opaque binary data. No
readable copy of the current native `cloudmanager` was obtained. The installed
updater's Java IVI path delegates installation to recovery, not an accessible
Java unpacker; it was inspected statically and never invoked.

An older public Dolphin `cloudmanager` links a received token message with
writing `cloudToken.dat`. That is comparative evidence only: it is not the
current Denza protocol. The native trace now identifies the selected endpoint
and immediate resolution failure. The public-profile selection and current
token-provisioning implementation still need evidence. No working sidecar
ingestion path, hardware-replacement requirement, or need to visit China has
been established.

Local evidence and detailed reports are under ignored
`captures/telematics-20260922/`: `read-only-findings.md`,
`registration-findings.md`, `alternative-paths-findings.md`,
`server-selection-findings.md`, `token-cause-findings.md`, and their selected
evidence folders.
No tokens, VINs, raw captures or extracted binaries belong
in tracked documentation. The investigation described in this section did
not change vehicle settings or services and preserved the existing ADB tunnel.

## Earlier phone and vehicle observations

The following retains the earlier session's observations. Broad connectivity
inferences are qualified against the direct client measurements above.

## Subject

| Item | Value |
| --- | --- |
| Phone | OPPO Find X9 Ultra (`CPH2841`), Android 16 |
| App | `com.byd.aeri.caranywhere` 9.16.1 (`versionCode` 561), installed 2026-09-20 |
| Flavor | Denza — launch activity is `…splash.activity.DenzaLaunchActivity` |
| Bound vehicle | 腾势Z9GT 易三方 |

## Phone link: healthy (measured 2026-09-22)

Nothing on the phone explains the offline state.

- Default network is Wi-Fi, `VALIDATED`. No VPN, `private_dns_mode` is
  `null`, Data Saver off, and the app has no background or network
  restriction (`RUN_IN_BACKGROUND` allow, standby bucket 10,
  `Restrict background: false`).
- The app reached the cloud at least once: it was installed two days before
  this session, is signed in, and renders the bound vehicle's model name and a
  server-side seasonal skin.
- The selected phone-app endpoints below resolve and complete TLS from the same
  network. `403` on a bare root path is the server answering; it does not prove
  a successful authenticated API request.

  | Host | DNS | `https://host/` |
  | --- | --- | --- |
  | `cache.denzacloud.com` | 221.194.141.166 | 403 |
  | `service8.denzacloud.com` | 116.205.162.231 | 200 |
  | `i.tengshiauto.com` | 221.194.141.166 | 200 |
  | `cache.bydauto.com.cn` | 36.41.168.166 | 403 |
  | `profilesys.bydauto.com.cn` | 221.194.141.166 | 200 |

  Endpoints were read out of `classes.dex` (403 distinct hosts; the Denza set
  is `denzacloud.com` / `tengshiauto.com`, the shared BYD set is
  `bydauto.com.cn` / `bydoceanauto.com`).

## What the app reports about the vehicle

Home screen, 2026-09-22 12:41:

- Range, charge and the second gauge all read `--`; `已熄火` (powered down);
  the vehicle-signal chip carries an `×`; `更新于: --`.
- Bluetooth key `未激活` (not activated); NFC and UWB greyed out.
- A card states `车机端"远程位置"未开` — *"remote location" is not enabled on
  the head unit*.
- Pulling to refresh raises the toast `获取车况信息失败` — *failed to obtain
  vehicle condition information*. Its wording is about vehicle status, not
  about the network, which the app words differently (`网络连接失败`).

**Correction.** The home screen's `更新于: --` was read in this session as
"the cloud has never held a status report". That is wrong. The vehicle detail
page, one swipe down, shows

> `更新于: 07/22 18:33`

so the cloud does hold a last report, dated **22 July**, two months before
this session. The car reported and then stopped; it is not a car the cloud
has never met. The empty home-screen field is a blank summary, not a
never-seen marker. Everything below that depended on "never reported" is
corrected accordingly.

The account, the binding and the vehicle identity are fine: the cloud knows
the car and holds a two-month-old report for it.

## Vehicle link: the onboard SIM has no service (measured 2026-09-22)

Read from the head unit over ADB (`127.0.0.1:5555`, `DiLink5_1`), read-only.

- The car carries a **China Mobile** SIM: `gsm.sim.operator.numeric` `46013`,
  `CMCC`, `iso-country` `cn`, IMSI `4601389963…`, ICCID `898608…`. The owner
  confirms it is the stock SIM.
- Android reports `isEmbedded=false`, `simSlotIndex=0`. This alone does not
  establish physical accessibility, replaceability or compatibility with a
  different SIM.
- It is registered nowhere. `mVoiceRegState=1(OUT_OF_SERVICE)`,
  `mDataRegState=1(OUT_OF_SERVICE)`, `mCellIdentity=null`, signal level 0,
  `gsm.operator.alpha` empty, `carrierName=Emergency` — constant across the
  whole log window. `gsm.operator.iso-country` reads `ru`.
- Both switches are off as well: `mobile_data=0`, `data_roaming=0`
  (`airplane_mode_on=0`). Registration fails before the data toggle matters,
  but a roaming attempt cannot succeed while these stay off.

## Internet sockets do not identify a working telematics channel

An earlier reading concluded that stock telematics cannot use the head unit's
Wi-Fi. That conclusion is withdrawn. The sockets below prove Internet activity,
but their endpoint provider and UID do not prove successful Denza telemetry.

- The head unit's default network is Wi-Fi, validated, and from the car
  `ping service8.denzacloud.com` answers in 217 ms.
- `/proc/net/tcp` on the car shows **established** connections to Huawei
  Cloud China (`HWCSNET`, the same provider that hosts `service8.denzacloud.com`):

  | Peer | Port | Socket UID |
  | --- | --- | --- |
  | `122.9.108.196` (`ecs-…hwclouds-dns.com`) | 443 | 1000 (`system`) |
  | `110.41.149.112` (`ecs-…hwclouds-dns.com`) | 30023 | 0 (`root`) |

- Running daemons include `cloudmanager`, `cloudctrlserv` and `mqttserv`.
  Attribution of the listed sockets to one of them was not established.
  `netDCL` provides conntrack observations. The later native log sample did
  contain periodic CAN-status messages, but no registration exchange.

The head unit has Internet access. The direct status getters in the latest
section are stronger evidence for the specific cloud clients than these
unattributed sockets.

## Live test: data + roaming on, no change (2026-09-22, owner-authorised)

`svc data enable` and `data_roaming=1` on the head unit, polled for two
minutes: `OUT_OF_SERVICE` throughout, operator still empty. The radio log
gives the reason it cannot be a roaming-permission question alone —
`regState = NOT_REG_MT_NOT_SEARCHING_OP`, `reasonForDenial = NONE`,
`cellIdentity` empty: the modem is not searching, not being refused.
`isManualNetworkSelection=false`, `cell_on=1`, `preferred_network_mode=32`.
Both switches were returned to `0` afterwards. The owner states the SIM does
not work in Russia, which closes this line.

## Where the car's remote switches live (found 2026-09-22)

The app's complaint `车机端"远程位置"未开` points at a stock setting. It is not
in any Android settings namespace; it belongs to `com.byd.carsettings`
(`/system/priv-app/CarSettingPlatform/CarSettingPlatform.apk`, which carries
the strings `Remote Location` ×11 and the key `remote_location` ×8). The
head unit's UI is in English.

Reached through the settings app's own search (`com.byd.search.SearchActivity`
— type a term, press Enter), which maps the three remote entries:

| Entry | Path |
| --- | --- |
| Remote Unlock | Vehicle → Locks |
| **Remote Location** | **System → Connect** |
| Remote Monitor | System → Connect |

State of **System → Connect** as found:

- **Remote Location — OFF.** Its own description scopes it narrowly: *"When
  turned on, you can view the vehicle's location information on the mobile
  app"*. On that wording it governs the location card, not the vehicle-status
  report, so enabling it is not expected to end the offline state.
- **Remote Monitor — OFF** (*"view real-time vehicle footage through the
  mobile app"*).
- **Smartphone and Vehicle Connectivity — Not connected.**
- **Cellular Data — OFF**; Wi-Fi ON (`sweet-home`), Bluetooth ON, Hotspot OFF.

`com.byd.systemsettings.privacy.PrivacyModeActivity` starts and immediately
finishes; `PrivacyManagerActivity` opens a dialog holding only Microphone,
Interior Camera, Location Service (all `12 months`) and Personalized
Recommendation — none of them the remote switch.

## Remote Location refuses to turn on: owner-account gate (2026-09-22)

Owner-authorised attempt to enable **System → Connect → Remote Location**.
Four taps on the switch (2560×1600 panel, `input tap 1762 930`); the switch
stayed `OFF` every time. Injected taps do reach this app — the same method
navigated from the settings search into this page — so the refusal is the
app's, not a missed coordinate.

The reason is a toast from `com.android.systemui` (uid 10067,
`appop=TOAST_WINDOW`), too short-lived for a host-side `screencap` to catch.
Taking the shot **on the car** (`input tap …; sleep 0.6; screencap -p
/sdcard/…`) caught it:

> **This setting can only be modified by the owner's account**

The head unit is signed in: Account Center → Personal Information shows a
profile (`Dmitry`, with photo, gender and birthday filled in). So the gate is
not "nobody is logged in". Either this account is not the vehicle's **owner**
in BYD's cloud — the usual state for an imported car whose original account
still holds ownership — or the ownership check needs a cloud round-trip that
does not complete here.

Nothing on the car was changed: the switch never moved.

**Useful technique.** For any short-lived toast on this head unit, screenshot
from inside the device in the same shell command as the tap. A host-side
`exec-out screencap` loses the race; `dumpsys window windows | grep Toast`
confirms a toast exists but never carries its text.

## Owner in the cloud, not on the car (2026-09-22)

The owner states the car was imported from China and that ownership was
transferred to their account through a representative. The app agrees.

`我的` → the vehicle card → **`车辆授权`** (vehicle authorisation) opens a page
offering **`新建授权`** — *create a new authorisation* — over the bound
`腾势Z9GT` (VIN ending `147747`), with `暂无有效授权` ("no valid
authorisations") below. Granting authorisations to other people is an owner's
function; a secondary user is not offered it.

So the cloud holds this account as the vehicle's owner, while the head unit
refuses owner-only settings for the same account. The two sides disagree, and
the head unit is the stale one. This supersedes the earlier guess that the
account was merely a secondary user.

## The car's SIM is not real-name registered (2026-09-22)

`我的` → **`SIM卡实名`** (carrying a red action badge) opens `我的车联网卡`
("my connected-car SIM"):

- the bound `腾势Z9GT易三方插混`, VIN ending `147747`
- a China Mobile connected-car number `1489963****` (the `148` range is
  China Mobile's IoT block)
- status **`未实名认证`** — *not real-name verified* — with `前往认证`
  ("go and verify")

Mainland carriers suspend a SIM that is not real-name registered. This is a
sufficient, independent cause for the vehicle's cellular link dying, and it
sits well with a last cloud report on 22 July rather than at the moment the
car left China.

Identifiers are masked here on purpose: this repository is published on
GitHub. The full VIN and SIM number are visible in the app.

**How the two findings join.** Ownership moved in the cloud. The head unit
learns such things from the cloud over the telematics link. That link runs on
a SIM the carrier has suspended, so the car never received the change and
still gates owner-only settings against its last known owner. This is the
leading explanation, not a proven chain: the car does hold Wi-Fi sockets to
the same cloud, and nothing yet shows that ownership sync refuses to ride
them.

## Why Wi-Fi cannot carry the telematics link (settled 2026-09-22)

The car names its own cloud in system properties:

| Property | Value |
| --- | --- |
| `persist.service.host.name` | `apr-cn.denzacloud.com` |
| `persist.service.apn3.host.name` | `idilink-cn.denzacloud.com` |
| `persist.sys.cloud.app_reg_status` | `0` |
| `persist.sys.cloud.token_flag` | `0` |

The second name is bound to **APN3** — a dedicated cellular access point, and
`dumpsys netstats` on the car keeps separate `APN1`/`APN3` interface groups.

Both telematics names resolve to **`1.1.1.1`**, a placeholder — from this
network, and from the car itself:

```
apr-cn.denzacloud.com.      300 IN A 1.1.1.1
idilink-cn.denzacloud.com.  300 IN A 1.1.1.1
```

The answer comes from the zone's own authority (`ns1.huaweicloud-dns.*`), so
it is deliberate, not a local resolver artefact. An ordinary app host such as
`service8.denzacloud.com` resolves normally (`116.205.162.231`) from the same
car at the same moment.

**Conclusion.** The vehicle's telematics servers have no public address. They
are served inside the carrier's private APN (split-horizon DNS), so the link
cannot ride the head unit's Wi-Fi: there is nothing on the public internet to
connect to. `app_reg_status=0` and `token_flag=0` say the car's cloud client
never completed registration, which is what one expects when its endpoint has
been unreachable since the SIM lost service.

This also dims the "put a local SIM in slot 0" idea: a local carrier gives
general internet, not China Mobile's private APN, so the telematics endpoint
stays unreachable. A working route back is the original SIM with service **and**
roaming — home-routed roaming keeps APN traffic tunnelled to the home network
— or a BYD-side public endpoint, which nothing here suggests exists.

The established Wi-Fi sockets to Huawei Cloud (`122.9.108.196:443`,
`110.41.149.112:30023`) therefore belong to other services; `CloudServiceApp`
itself ships no hardcoded endpoints (its dex carries none), taking them from
these properties instead.

## The private APN, and why a Chinese roaming SIM restores everything (2026-09-22)

The "no way back" reading of the previous section was too strong. The owner
reports that importers in Russia restore the master account, the app and the
Bluetooth key with a Chinese roaming SIM ("rSIM") in place of the factory one.
The car's own APN table explains exactly why that works.

`/system/etc/apns-conf.xml` is readable by shell (the `telephony` provider is
not: `SecurityException: No permission to access APN settings`). It carries
BYD-specific entries:

```
<apn carrier="CMCC SPEC" apn="CMIOTBYDNSA.GD" mcc="460" mnc="…"
     type="spec" protocol="IPV4V6" roaming_protocol="IPV4V6" />
<apn carrier="CMIOTBYDNSA.GD" apn="" mcc="460" mnc="…"
     type="ia"   protocol="IPV4V6" roaming_protocol="IPV4V6" />
<apn carrier="CMCC FUN"  apn="CMMTMBYDNSA.GD" mcc="460" mnc="…" type="fun" />
```

`CMIOT` is China Mobile's IoT arm and `BYDNSA.GD` is a private access point
provisioned for BYD in Guangdong. Both the initial-attach (`ia`) and the
special (`spec`) entries exist for **every** China Mobile MNC the car may see
— `00`, `02`, `04`, `07`, `08` and `13` — and `13` is the factory SIM's own
network (IMSI `46013…`). `roaming_protocol` is set, so roaming on these APNs
is anticipated by the configuration, not an afterthought.

**Mechanism.** A China Mobile SIM provisioned for `CMIOTBYDNSA.GD` attaches
that APN abroad through home-routed roaming: the bearer is tunnelled back to
the Chinese core, so the car sits inside the carrier's private network while
parked in Russia. `idilink-cn.denzacloud.com` then resolves to a real address
instead of the public `1.1.1.1` placeholder, the cloud client can register
(`app_reg_status`, `token_flag`), the pending ownership change reaches the head
unit, and the owner-only gates — Remote Location, the Bluetooth key's
`未激活` — clear on their own.

**What this does and does not change.** A *local* (Russian) SIM still cannot
help: it offers general internet, not this private APN. What is needed is a
*Chinese* SIM carrying this APN with international roaming — either the
factory SIM restored to service, or a replacement M2M SIM from the same
provisioning. The earlier conclusion stands only for the Wi-Fi path.

## Historical private-endpoint investigation (2026-09-22)

**Superseded conclusion:** the early claims below that every software route
was closed or that the private SIM was necessarily required are not established.
Later current-build evidence above found a selectable public registration
branch. That branch still fails locally, and no working upload path has been
demonstrated; neither success nor impossibility follows from these older DNS
observations alone.

Tested, because a placeholder answer is often just split-horizon DNS keyed on
the asking resolver. It is not.

Queried over DNS-over-HTTPS from resolvers inside China — AliDNS
(`dns.alidns.com`) and Tencent DNSPod (`doh.pub`) — both return the same
record for `idilink-cn.denzacloud.com` and `apr-cn.denzacloud.com`:

```
{"Answer":[{"name":"idilink-cn.denzacloud.com.","TTL":300,"type":1,"data":"1.1.1.1"}]}
```

So the public zone publishes the placeholder to everyone, Chinese resolvers
included. The real address is served by the DNS the carrier hands out *inside*
the APN, and is almost certainly carrier-internal. Consequences:

- A VPN or VPS in China does not help: the name does not resolve there either.
- There is no address to point a relay at, so no DNS override on the owner's
  router can reach the stock telematics.
- Nothing hardcoded on the car fills the gap. The only IP-shaped values in its
  properties are APN health-check targets
  (`persist.radio.byd.ping_apn2_ip1` `120.232.145.185`,
  `ping_apn2_ip2` `59.82.121.200`), not service endpoints.

Reaching the stock cloud therefore requires a SIM on the private APN. Nothing
software-side substitutes for it.

The next section narrows that sentence. It was right about registration and
wrong about there being no public address at all.

## Public MQTT broker is reachable; registration is not (2026-09-22, later pass)

Read-only. No settings, routes, or services were changed. The head unit was
still `127.0.0.1:5555` on Wi-Fi `192.168.88.109`.

`/system/etc/mqttserv/` ships two Denza profiles. The public one is not a
domain that falls back to `1.1.1.1`:

| File | Denza broker |
| --- | --- |
| `broker_pub_8883.json` | `ssl://121.37.227.120:8883`, name `dilink-mqtt-cn.denzacloud.com` |
| `broker_pub_8890.json` | same host, port `8890` |
| `broker_priv_8883.json` | `ssl://10.167.206.92:8883` |
| `broker_priv_1883.json` | `tcp://10.167.206.92:1883` |

`dilink-mqtt-cn.denzacloud.com` is still `1.1.1.1` on Google DNS and on
AliDNS. The IP in the public file is a real server. From this Mac, TCP to
`121.37.227.120:8883` and `:8890` completed in about 0.6 s and negotiated
TLS 1.2. The peer certificate is `CN=dilink-mqtt-cn.denzacloud.com`,
`O=BYD INDUSTRY`. No MQTT connect was sent. The same check on
`113.46.140.234:8883` (the BYD public profile in the same files) also
completed TLS. Ports `5002`, `9102`, and `9105` on both addresses timed out.

That public broker is not where this car is connecting. At the same time,
`/proc/net/tcp` showed root (`uid 0`) in `SYN_SENT` to `10.167.206.17:9105`
from `192.168.88.109`, and an earlier sample in this investigation showed
`10.167.206.17:9102`. `10.167.206.17` is not in the readable mqtt JSON (that
subnet's published broker is `.92`) and `grep` of `/system/etc` does not find
it. The packets leave on the Wi-Fi default route. The home router has no
route to that carrier address, so the handshake never finishes.

`persist.sys.cloud.token_flag` is still `0`, and `CloudServiceApp` still logs
`token is empty or token length less 32` before opening
`emqx-cn.denzacloud.com:8884`. The Dolphin-era names
`dilinkserviceterminalreg-global.iov.byd.auto` and
`dilinkserviceterminaladdr-global.iov.byd.auto` are NXDOMAIN on both
resolvers; this build's host properties remain `apr-cn.denzacloud.com` and
`idilink-cn.denzacloud.com`.

**What this separates.** The MQTT broker the firmware lists as public is on
the internet, and a DNS override still cannot name it because the zone
publishes `1.1.1.1`. The client that has to run before that broker is useful
is aimed at `10.167.206.17:9105`, which does not answer on the public broker
IPs. Redirecting that flow at `121.37.227.120` would hit a closed port.
A VPS, in China or elsewhere, is not inside `10.167.206.0/24`.

The official phone app renders the card from the cloud record
(`getCurrentStateByVin`, `latestVehicleCondition`). Public clients of that
same cloud (pyBYD and the apps it credits) read that record and send remote
commands. They do not upload SOC, range, or the "updated at" timestamp.
`CloudServiceApp` still refuses to open its broker until
`/data/system/cloud/cloudToken.dat` exists, and shell cannot create that
file. A token minted anywhere except the registration server on
`10.167.206.17` is not a path this investigation can complete: the public
broker is what checks it. Phone-to-car casting (ICCOA Carlink / HiCar) mirrors
the phone onto the head unit and does not write this card.

## Stock virtual SIM and the roaming reject (2026-09-22, later pass)

The head unit already has China Mobile's virtual-SIM writer,
`com.xinsheng.videntityserver` (`CmccVidentityserver.apk`), and
`com.byd.vsimservice` is running. `persist.sys.byd.vsim.config` is empty,
and the service treats anything other than `0` as enabled.
`persist.sys.byd.vsim.state` is `0` because no profile is loaded.

The writer downloads a profile over plain HTTP from
`esim.yunbaitech.cn:8085` (`/bydwrite/v1/writeApi/bydToWriteCard`), falling
back to `121.37.10.2:8085`. Both ports answered TCP from this Mac. It does
not get that far on this car. It takes an ICCID from
`persist.radio.byd.vsim.iccid` (empty) or else `ril.csim.iccid`, and
continues only when character 13 of that ICCID is `D`. The factory SIM does
not have that marker, so the download thread exits with "iccid is not
vaild" and never calls the writer. This car is not enrolled in the stock
virtual-SIM path.

The physical modem is not sitting idle. `dumpsys telephony.registry` shows
WWAN voice and packet service alternating through `NOT_REG_SEARCHING`, and
on 2026-09-22 at 09:38 and again at 12:42 the registration info carries
`rejectCause=13` (roaming not allowed in this location area).
`persist.radio.byd.last_mcc=250` and `last_mnc=02` record a Russian network
seen earlier. `persist.sys.byd.apn_type` is `triple_apn` and
`persist.radio.net.lte.apn1.disable` is `0`, so the private APN policy is
on. Android `mobile_data` and `data_roaming` are still `0`; the reject is
at registration, before those switches attach a data bearer.

The owner closed this line the same day: the fitted SIM is the Chinese
one and does not work in Russia. Wi-Fi calling is not a substitute.
`persist.vendor.mtk_wfc_support=1` and the carrier config marks
`carrier_wfc_ims_available_bool` true, but
`carrier_default_wfc_ims_enabled_bool` and
`carrier_default_wfc_ims_roaming_enabled_bool` are false,
`carrier_wfc_supports_wifi_only_bool` is false, and both ePDG address
strings are empty. That tunnel would still authenticate this same SIM
to China Mobile, and it carries IMS, not the `CMIOTBYDNSA.GD` telematics
APN.

## Not established yet

- **Whether the established sockets carry this car's telematics.** A root UID
  and port 30023 do not establish the process, protocol or purpose.
- **Whether the cloud reaches the car when the app asks.** The planned test —
  watch the car's conntrack while the app refreshes — did not run: the phone
  locked before the app reached the foreground.
- **Which module owns the report.** Everything measured is the head unit's own
  MTK modem (`MOLY.NR16.R1.MP3.MP.V2.P5`). A separate T-Box ECU, if it holds
  its own radio, is not visible from the IVI's shell.
- **Why native registration/token delivery fails.** The current executable is
  unreadable to shell and the matching archive's inner image is not yet decoded.
- The remote-location key is now found in the BYD settings provider;
  `remote_location=0` and `remote_screenage=0` were read. Current Java code
  packs these as privacy flags, without an observed general registration
  switch in that path. Broader native effects remain unknown.
- Whether a local SIM in slot 0 would be usable, given that the APN and the
  vehicle's data plan are provisioned for a mainland carrier.

## Method notes

- The app is a release build and writes nothing to `logcat`; grepping the
  buffer for `byd` returns only `byDevice` / `byDefault` from system
  components. Screen state and static endpoint extraction carried the
  diagnosis instead.
- `adb shell dumpsys netstats detail` was not usable for per-UID traffic here;
  the app's UID is 10502 if a later session wants to retry.
