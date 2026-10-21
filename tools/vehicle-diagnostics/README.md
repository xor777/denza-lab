# Vehicle diagnostic research tools

## Fixed child identities and typed chassis buffers (2026-10-21)

These isolated readers stay outside the product APK. The completed owner run is
documented in [unattended qualification](../../docs/vehicle-data-findings.md#unattended-child-and-buffer-qualification-2026-10-21).
Neither tool connects/restarts ADB, installs an APK, changes vehicle state, clears
codes, enters a diagnostic session or launches a factory application.

`ChildIdentityReadProbe.java` has no variable target: exactly eight service22
software-code reads from the pinned child-candidate manifest through four known
parent routes. It checks profile170/CANFD/P/zero speed/idle diagnostic manager
before every send, holds the shared diagnostic lock, observes two quiet seconds,
uses four-second request deadlines and no retries. Foreign traffic and changed
guards stop the sequence. EOF cancels; independent hard bounds cover a stuck
request (six seconds) and overall operation (52 seconds). Hard halt is incomplete
cleanup and must not be accepted as a normal run.

`child_identity_probe.py` defaults to no vehicle activity unless `run` is
explicitly selected. Build and plan are offline:

```sh
python3 -B tools/vehicle-diagnostics/child_identity_probe.py plan
python3 -B tools/vehicle-diagnostics/child_identity_probe.py build --output /absolute/fresh/build-directory
python3 -m unittest discover -s tools/vehicle-diagnostics -p test_child_identity_probe.py
```

Build runs 35 synthetic assertions and excludes tests from the device dex. A
live run requires `run --build DIR --serial EXISTING_SERIAL --output FRESH_DIR`;
it refuses changed source/artifact hashes, stages one nonce-named jar and removes
only that file. `cleanupVerified` proves file removal, not remote process health.
An operator still owns pre/post firmware/APK hashes, stock PIDs, crash buffer and
reader coordination. `PLAN_DONE` alone does not mean eight identities matched;
require decoded `CACHE_MATCH` for each. The completed run matched eight codes.

`ChassisBufferReadProbe.java` permits only `wheels` (`1049/99000059`) or
`steering` (`1023/99000198`). It performs one typed SDK GET and registers one
exact ID, using only the DTO callback to avoid SDK double delivery. It holds
the same `/data/local/tmp/denza-dtc-read.lock`, never unlinks it and uses no
hand-built native buffer parcels. Queue128, copy64B and record1024 bounds are
fixed. Duration3–20 seconds includes a two-second cleanup reserve; an independent
hard watchdog does not wait on an SDK lock. EOF or `STOP` ends observation.

```sh
bash tools/vehicle-diagnostics/chassis_buffer_probe.sh test /absolute/fresh/test-directory
bash tools/vehicle-diagnostics/chassis_buffer_probe.sh build /absolute/fresh/build-directory
```

The shell script builds/tests or prints a command; it never calls ADB. Use a
fresh output directory and pin both source and DEX hashes before an operator
stages anything. Profile/P/speed/idle-manager and before/after health checks are
the operator's responsibility for this receive-only helper. Keep stdin open.
Accept lifecycle completion only with process exit0, one `DONE`, `ready=true`,
`failure=null`, no dropped/error/unexpected/queued records, and successful
unregister/ownership release. `sdkIntField` is retained raw, not interpreted as
a status code. A six-byte steering snapshot has shape validity only; an
11-byte `MCU_OFFLINE` wheel snapshot is unavailable, never four zero speeds.
The 18 host lifecycle cases pass; completed live observations are in the linked
findings and `research/vehicle-diagnostics/chassis-buffers-live-20261021.json`.

## Remaining ECU reader (2026-10-20)

`RemainingEcuReadProbe.java` is a shell `app_process` reader. Its fixed plans
qualified additional addresses and DTC access on the owner's profile170 Z9GT.
Results and limits are in
[vehicle-data-findings.md](../../docs/vehicle-data-findings.md#remaining-ecu-identities-and-chassis-dtc-2026-10-20)
and [the decoded evidence](../../research/vehicle-diagnostics/remaining-ecus-20261020.json).

| Argument | Fixed operation |
| --- | --- |
| None | F194 at the ECM control and eleven chassis/ADAS/drive address candidates |
| `dtc` | ECM control, then exact F194 identity and `19 01 09` / `19 02 09` for five qualified modules |
| `catalog1`…`catalog4` | ECM control and up to eighteen remaining published address candidates, F194 only |
| `obd-name` | ECM control; `09 00`, `09 04`, `09 0A` at the OBD-only `7E5` interface |
| `factory-powertrain` | ECM control; exact cached F194 identity, count09 and list09 for factory-map SMCU744, SMCU3 745 and DCOBC7E4 |
| `factory-dcobc-list` | ECM control, exact DCOBC F194, one bounded `19 02 09`; no count/format inference |
| `factory-candidates1`, `factory-candidates2` | ECM control; F194 only at the 35 CAN routes of the complete factory map outside the scanner catalog, 18 then 17 addresses; `715`/`785` left out (result 3 already); codes matched offline |

Every request checks shell/profile170/P/zero speed/idle diagnostic manager;
the process holds the same file lock as the packaged DTC reader. A passive
two-second window precedes the first request. ECM identity mismatch, unexpected
traffic, invalid sequencing, DTC count/list mismatch or a changed guard stops
the plan. Deadline90 s, per-request timeout4 s, no retries or arbitrary targets.
There are no session, clear, security, routine, VIN/serial or OTA requests.

The factory plans subsequently qualified all three targets. Both rear drives
returned count0 and empty lists; DCOBC count timed out, but its separate bounded
list returned an explicit empty `59 02 09`. That exception permits at most64
records and16 frames, only for `factory-dcobc-list` at `7E4/190209`.
DCOBC's format remains unknown. `PLAN_DONE` after a gateway timeout means the
sequence ended, not that DTCs were read: require the validated `DTC_LIST`.
See [decoded results](../../research/vehicle-diagnostics/factory-powertrain-dtc-20261020.json)
and the raw runs under `captures/diagnostics-next-20261020/`.

Compile this source with `ShellProxyBootstrap.java` using the repository's
JDK17, Android35 stubs and d8 (`--min-api 33`). It is outside the app build.
The executed source copies and manifests, build outputs, host pre/post checks
and bounded runners are retained in `captures/diagnostics-remaining-20261020/`.
The vehicle's app was not installed or replaced.

## OTA route observer

`OtaRouteObserver.java` is an isolated `app_process` helper, outside product APKs.
It temporarily sets two logging properties to `D`, streams their `logcat` output,
and restores the exact originals. It does not start OTA or make ECU requests.
Status:18 Java lifecycle and16 host-runner tests pass. On `emulator-5580`, normal
deadline, explicit stop, sanitized output and host/owned-ADB-client SIGKILL
passed with original-property readbacks. Vehicle acceptance remains open.

The stock Update button can automatically download and schedule forced
installation. This logger never launches that workflow and is not its safety
interlock. See `docs/vehicle-data-findings.md`, “Bounded observation and isolated
metadata”, for the source audit and emulator evidence paths.

Host entrypoint: `python3 -B tools/observe_ota_routes.py --help`. `build` compiles
locally into a fresh directory; `observe` requires an explicit existing serial,
matching build manifest and fresh output directory. It changes only the two
tags below, never connects/restarts ADB or starts OTA. Local files are0600 under
a0700 evidence directory. Its `run.json` records independent cleanup readback;
host exit alone is not proof of restoration.

## Invocation and files

Main class: `dev.denza.diagnostics.probe.OtaRouteObserver`.
Exactly four arguments, in this order:

```text
--duration-seconds 1..350 --nonce 32_lowercase_hex_digits
```

Requires Android shell UID 2000. The caller stages a dex and starts `app_process`;
the helper has no ADB/connection/install code. The caller must keep stdout on a
stream (for example an owned FIFO), without a `nohup.out` or raw-log file.

Fixed directory `/data/local/tmp/denza-ota-route-observer` must be mode 0700.
The helper creates it if absent. Files:

- `lease.lock`: held with `FileLock` for the entire session; never unlink while
  a helper may be running. A second owner is refused.
- `state.json`: atomic JSON, mode 0600; `state.tmp` is the atomic-write scratch.
- `stop.request`: caller writes the exact current nonce, with optional LF.
  A different nonce is ignored. No other file selects behavior.

A previous state is reusable only if `STOPPED`, `restoredVerified=true`,
`childExited=true`, and a new nonce is supplied. Incomplete state requires an
explicit caller inspection/recovery; this helper has no takeover switch.

## Output contract

Stdout is only the byte stream from this exact command:

```text
/system/bin/logcat -b main -v threadtime -T 1 OTA_GCY_UPGRADE_DiagnosePresenter:D OTA_GCY_UPGRADE_CanDiagnostic:D *:S
```

The first record may precede startup (`-T 1`). The host must pass bytes through
`research/vehicle-diagnostics/filter_ota_route_stream.py` before persistence and
mark a transport gap/cutoff when appropriate. These tags describe CAN routes;
they do not cover Ethernet/DoIP routes. The helper stops after 4 MiB of output.
No raw logs are written by the helper. Logcat stderr is discarded. Helper stderr
contains only `OTA_OBSERVER_STOPPED`, `OTA_OBSERVER_RESTORE_FAILED`, or
`OTA_OBSERVER_REFUSED_OR_FAILED`.

State schema 1 fields: `nonce`, `pid`, `durationSeconds`, `status`, `reason`,
`elapsedMs`, `originals` (the two exact property keys), `restoredVerified`,
`childExited`. Status transitions:

```text
STARTING -> OBSERVING -> RESTORING -> STOPPED | RESTORE_FAILED
```

`STARTING` contains both originals before any property write. `OBSERVING` means
both readbacks were `D`, logcat started, and the watchdog was armed. Some early
failure/stop paths skip `OBSERVING`. `STOPPED` confirms restoration and child
exit, but `reason` may still report an incomplete capture. Exit 0 is normal stop
or deadline; 2 is refusal/setup/stream failure with no unresolved cleanup; 3 is
unverified restoration, child cleanup, or final state persistence.

Only `log.tag.OTA_GCY_UPGRADE_DiagnosePresenter` and
`log.tag.OTA_GCY_UPGRADE_CanDiagnostic` are written. Global `log.tag` is untouched.
One monitor serializes enable/restore; originals are saved before enabling.
Deadline and stop are polled every 50 ms using Android `elapsedRealtime`.
Output runs on a daemon thread so an unread stdout pipe does not hold cleanup.
Cleanup destroys only the owned logcat `Process`, waits up to 1.5 seconds, and
restores/verifies both properties (up to three immediate attempts each).

The shutdown hook and normal `finally` use the same idempotent restoration.
A separate hard deadline ends the process at duration + 10 seconds (at most
360 seconds) if a native operation hangs. That emergency path, SIGKILL, or a
process crash cannot promise restoration; nonterminal state blocks a fresh
session. The caller must check terminal state and original-property readbacks.

## Host tests

No Android SDK or device is needed. Use a fresh temporary class directory:

```sh
javac --release 8 -d /tmp/ota-observer-classes tools/vehicle-diagnostics/OtaRouteObserver.java tools/vehicle-diagnostics/OtaRouteObserverTest.java
java -cp /tmp/ota-observer-classes dev.denza.diagnostics.probe.OtaRouteObserverTest
```

The tests inject properties/clock/log source/stop and exercise partial writes,
readback errors, lost state persistence, deadline, concurrent stop, failed
restoration, and lease recovery rules. One host-only child process verifies
that a blocked stdout consumer does not block child cleanup. None accesses ADB.

## Isolated GCY checkVersion client (live refusal reproduced, 2026-10-21)

`OtaCheckVersionProbe.java` and `run_ota_check_metadata.py` prepare one GCY
metadata request using the existing cache, never a stock OTA workflow. They
reuse the unchanged reader/parser/TLS and host lease/watchdog code below.
Default execution prints a local plan; it never invokes ADB. The explicit run
requires a pinned `manifest.json`/`dex/classes.dex` build and authorization for
the temporary BydLog export and RAM-only identity use.

It reads the existing four GCY identity/preferences/version inputs plus
`ECUVersion.json` and GCY/GHY/ProductLine strategy files. Before GO, the host
verifies export closure and a terminal watchdog and saves allowed metadata.
All six ECU fields are kept unchanged. The cache's source age remains unknown;
no active ECU diagnosis is performed. Neither the helper nor the host contains
a stock download, install, token-refresh or retry path. Keys, tokens, UUIDs,
package URLs and package secrets never enter host output. Unrecognized cause
text is omitted. A forced strategy is only projected data.

Offline validation: 27 Java assertions, including the 225-row retained cache,
and 8 fake-device host tests. DEX is built in
`captures/ota-isolated-20261021/build/`. One explicitly authorized live session
then returned GCY `111` / `ecu_strategy_mismatch`, the same batch and empty
package arrays. The cache was byte-identical to the October 18 copy; all three
plan paths returned missing. Independent postflight confirmed closed ports,
removed helper/lease/export directory, unchanged package PIDs and matching
GCY/BydLogTool APK hashes. Expected versions and an applicable OTA were not
obtained. The one-session authorization is consumed. Evidence:
`captures/ota-isolated-20261021/approved-session-1/` and
[`ota-isolated-check-20261021.json`](../../research/vehicle-diagnostics/ota-isolated-check-20261021.json).

```bash
python3 -B tools/vehicle-diagnostics/run_ota_check_metadata.py
python3 -B -m unittest discover -s tools/vehicle-diagnostics -p test_run_ota_check_metadata.py
```

The manifest pins both new sources and both reused sources; changing any of
them invalidates the build. The new host reuses the old export lease, so it
cannot run beside another metadata reader. The exposure and watchdog limits
described below still apply. Full findings: [OTA](../../docs/ota-update-findings.md#подготовленный-изолированный-запрос-gcy).

## Isolated GHY metadata client (certificate failure before HTTP)

`GhyCheckVersionProbe.java` reads GHY preferences, its own SourceVehicleVersion
and the common ECU cache. After verified export closure/GO it uses the pinned,
unchanged September `OncarTls.java` adapter for up to two factory-signed HTTPS
requests: getEcuList, then checkVersion only if every requested ECU has a cache
row. Six fields are preserved, reordered/subselected by GHY ecuId; cache age
and cross-catalogue DID equivalence are not established. Unknown ECU, missing
auth failure or transport failure never starts diagnosis or retry. A validated
exporter FILE_NOT_FOUND for SourceVehicleVersion, or an empty file, selects
the stock `v1.0.0` constant and records its origin in `ghyVersionSource`.
Other errors and malformed nonempty versions stop the session.
A second-request failure retains the first response. No stock OTA, package
download or installation path is invoked.

This is a separate authorization scope from the completed GCY experiment:
three public-certificate APIs and at most two signing APIs can enter stock
crypto readiness/recovery. No private-key export. See the precise boundaries
and current authorization/live status in [OTA](../../docs/ota-update-findings.md#отдельный-путь-ghy).
The adapter source remains in the retained local corpus and is pinned to
SHA-256 `95bbb8e871dc306145d298eaa8a70af5611c113a73726b703bf47dac6aecfbc9`.

```bash
python3 -B tools/vehicle-diagnostics/run_ghy_check_metadata.py
python3 -B tools/vehicle-diagnostics/build_ghy_check_metadata.py captures/ota-ghy-new-build
python3 -B -m unittest discover -s tools/vehicle-diagnostics -p 'test_run_*check_metadata.py'
```

The first authorized build-v3 session stopped at `input_version/missing_input1`
before GO, certificate/signing APIs or network. Cleanup and unchanged APK/process
identities were verified. Its one-session authorization is consumed.

The separately authorized build-v4 session selected stock `v1.0.0`, read the
unchanged 225-row cache and completed factory identity setup, then returned
`https/operation_failed`. No GHY response was captured; actual HTTP/signature
counts were not recorded. Cleanup was independently verified; authorization
is consumed. This is not evidence of a GHY application-level rejection.

The default runner and builder are host-only. Completed diagnostic build:
`captures/ota-ghy-20261021/build-v5/`. Offline: 52 GHY Java checks, 27 GCY Java
checks and 19 host tests. It only unwraps adapter exceptions into fixed safe
codes, without changing TLS behavior or exporting free-text errors. Its separately
authorized session returned `https/ghy_certificate` before the first HTTP POST,
after factory identity setup. The error does not distinguish hostname, chain or
certificate constraints. Cleanup and unchanged APK/process identities were
independently verified. All three one-session authorizations are consumed.

Under the owner's subsequent general read-only diagnostic authorization, one
public TLS handshake (no client identity or HTTP) captured the current GHY
certificate. Its CN matches, but SAN is absent. Replaying the unchanged hostname
verifier from the car's hash-verified runtime rejects it. This establishes an
Android adapter incompatibility, not the cause of the stock GCY status 111.
Reproduce offline with `.venv-firmware/bin/python
research/vehicle-diagnostics/check_ghy_hostname.py`. No TLS verification bypass
was implemented; the failed handshake itself did not retain its peer certificate.

Reconciled historical evidence: on October 18, the existing Mac client
`tools/ads_ghy_factory.py` + `tools/ads_ghy_factory_tls.c` completed mutual TLS
with this car's hardware identity and one signature. GHY returned HTTP 200 /
status 100 / an empty ECU catalogue for stock `v1.0.0`; it did not return a plan.
All 33 retained evidence hashes were rechecked on October 21. This proves prior
GHY acceptance, not current catalogue contents or success of this Android adapter.
See [cross-research evidence](../../docs/ota-update-findings.md#сверка-с-исследованием-adas).

The isolated request omits versionSync,
which in the full stock flow may replace the effective version before checking.
The shared host runner's optional validators preserve GCY defaults but change
its source hash; old GCY manifests fail the runner's hash gate intentionally.

## Isolated ECU metadata client

`EcuMapMetadataProbe.java` is a separate prepared `app_process` client, never a
product dependency. `run_ecu_map_metadata.py` defaults to a local plan with no
ADB. Its explicit execution mode requires reviewed artifact/source hashes and
a separately authorized session. The two historical cache-export approvals
and all three dedicated metadata-session approvals have been consumed.

The four fixed inputs are existing GCY `files/dcrt.crt`, `files/dkey.key`,
`shared_prefs/share_data_prefs.xml` (only token/UUID) and
`/collect2/SourceVehicleVersion2`. They stay in device memory. The helper emits
READY; the host stops its export, checks closed ports and terminal watchdog,
then supplies GO. Only after that is one fixed `getEcuList` POST possible.
No chip/BYD API, OTA workflow, credential refresh, redirect or retry is used.
The output is a candidate-only routing projection, not DTC/installation proof.

The backup pair can be stale or missing. Invalid inputs and server errors stop
the operation. BydLog's listener binds all interfaces and has deletion support;
a loopback client does not remove this exposure. The watchdog starts stopping
at30 seconds and retains responsibility for a delayed START, so the code does
not promise a hard35-second closure under a hung Android receiver. Unverified
closure or changed ownership blocks GO and retains the lease.

The first authorized metadata attempt stopped before GO/HTTPS with
`helper_output_invalid`; export closure, watchdog retirement and cleanup were
independently verified. It did not acquire a new map. The rejected line was not
retained, so progress through the real inputs remains unknown.

A same-car synthetic loopback fixture reproduced MediaTek `CtaAdapter` messages
on stdout during every socket connect. The client now saves its protocol stream
and silences Java stdout/stderr before runtime/network work; the host still
rejects every unexpected line. This is not a general native-FD logging filter.
86 synthetic client assertions pass on JVM and the car, including privacy
canaries. The full four-input car fixture passes the unchanged host parser and
rejects EOF before HTTPS, with no real inputs, BydLog or GO. The host's mocked
tests include a real shell delayed-START/compensating-STOP harness. Detailed
evidence, trust provenance, exact bounds and output contract are in
`captures/diagnostics-discovery-20261018/ecu-http-followup/metadata-probe-preparation.md`
and `docs/vehicle-data-findings.md`. The actual first-session outcome and
synthetic regression are under `approved-ecu-map-metadata-1/` in that capture
directory. The second, separately approved session is recorded under
`approved-ecu-map-metadata-2/`: all four transfers and the certificate/RSA pair
checks passed, then `input_validate/prefs_missing` stopped before GO/HTTPS.
At least one of the two selected preferences was absent; the combined error
does not identify which. Token/version validity and server acceptance remain
unknown. Independent cleanup checks passed. Another real session needs new
approval; neither prior session permits an automatic retry.

The matched stock getter permits an absent/empty `upgradeUuid`, using exactly
`"uuid":""` in the request. The prepared correction now does the same, without
creating an identity or writing preferences. Token and version remain required;
missing token is `token_missing`, invalid/empty/literal `null` is `token_invalid`.
Malformed or wrong-type UUID still refuses. This does not identify which key
was absent in the consumed second session or establish server acceptance.

The corrected source/dex and historical third preparation are pinned in
`ecu-map-metadata-prepared-session-3.json`, linked to the consumed outcome below. All102 client assertions pass on JVM
and the car, and all19 host boundary tests pass. A synthetic loopback fixture
with UUID absent reaches READY through the host parser and rejects EOF before
HTTPS; it uses no BydLog or private files and leaves no helper/listener/dex.
Source evidence and independent review are in the second-session directory's
`prefs-source-followup.md`, `final-review.json`, and
`car-absent-uuid-qualification.json`. Historical preparation is not authorization.

The third approved session validated inputs and reached TLS/HTTP200, then
stopped at `https/http_headers`; the exact offending header branch was not
retained. No ECU map/application status was read. Export, watchdog, helper and
lease cleanup passed, independently rechecked. See
`approved-ecu-map-metadata-3/`; its approval is consumed, with no retry.

A coordination check found overlapping host lifetimes with the ADS/IDC reader.
Both control the same BydLog process/global STOP, using different ports. Our
host previously reused `/data/local/tmp/logtool`; it now requires absence and
an atomic `mkdir` claim before arming, and does not stop/remove another reader
on contention. All21 offline host tests pass. It retains its own lease on an
ambiguous staging failure. This guard does not prove a different reader's
cleanup correctness: the IDC error path can release its directory without a
confirmed terminal watchdog. No new vehicle execution is scheduled; coordinate
readers and confirm prior retirement before any future authorized session.

**Follow-up 2026-10-20:** one newly authorized session succeeded, retaining all
225 factory routing candidates in
[`factory-ecu-map-20261020.json`](../../research/vehicle-diagnostics/factory-ecu-map-20261020.json).
This approval is consumed too. No retry or further credential/export operation
is authorized by that result. The independent postcheck confirmed closed ports,
no helper/lease and an idle diagnostic manager.

The current client discards repeated unused HTTP headers while rejecting
duplicate framing/type/encoding fields, preserves the five real ECU names with
ASCII spaces, and accepts the stock DoIP logical-address-only form without
inventing pair fields. It still rejects malformed/ambiguous output.
133 synthetic assertions passed on JVM and the head unit;23 host tests and
independent checks against nine saved routes and all225 cached IDs passed before
execution. The complete225-row projection was validated afterward. Evidence,
reviewed sources, pins, authorization and cleanup are in
`captures/diagnostics-next-20261020/`; no private input bytes are retained there.

## Existing VHDS signal configuration (acquisition negative, 2026-10-21)

`run_vhds_config_export.py` prepares a separate one-file export; it does not
invoke the metadata/OTA helper above. Default execution prints a local plan
with hashes of itself, the shared lifecycle, the narrow reader and the wire
parser. `--preflight` performs only read-only car checks. Neither mode starts
BydLogTool export or claims a device directory.

The sole file is `/data/vhds/trigger_list_cfg/allone.txt`, preceded by one listing
of its parent to check size. No alternate path, retry, credentials, factory
HTTP request, VHDS call, property write or ECU request is implemented. Maximum
file size is1MiB and the wire deadline is20 seconds. Raw JSON remains in RAM;
only result code and validated signal/diagnostic-request fields may reach disk.
These are source candidates, not DTC definitions or a product whitelist.

After fresh owner approval, execution requires `--run-acknowledged`, a
`--reviewed-plan` matching every current source hash, and a new `--output`
directory. The plan is marked consumed before preflight. The runner uses the
existing shared export lease, atomically claims the export directory and arms
the serialized device watchdog before START. It creates one ephemeral ADB
forward and removes only that matching forward. Closure, terminal watchdog,
APK identity and cleanup must all pass before projecting the response.

The server still binds all interfaces and supports deletion; client restrictions
do not remove this exposure. Watchdog STOP begins at30 seconds, without a hard
closure guarantee for a hung Android receiver. Ambiguous closure leaves the
watchdog/lease in place. All previous approvals, including both VHDS sessions
below, are consumed and do not authorize another export.

Two separately approved attempts ran on2026-10-21. First: startup refusal before
reader invocation, with complete cleanup; the precise error was not retained.
Second: required user0 ports46320/46321 opened, the reader returned `missing`,
and all export listeners/owned artifacts closed. Independent postchecks found
unchanged core PIDs, matching APK, idle diagnostic tasks and no crash entries.
No configuration, new ECU route or DTC definition was recovered.

Startup now permits exactly46320/46321 with optional unused46322; all six
preflight/closure checks remain mandatory. This policy had already succeeded in
a separate retained user0 export. Current46322 is occupied by an unrelated
established microG socket. Fixed lifecycle errors and a maximum256 samples of
existing port checks are retained without extra device reads. Both allowed
startup sets and every forbidden known-port subset are covered offline.

`missing` is intentionally not evidence that the cache does not exist: the
server's file checks/nullable listing and the reader collapse several outcomes.
Live VHDS UID0/umask0077 versus BydLog UID1000 further supports an access boundary;
root-created directories/files would be0700/0600 under that mask, but existing
file modes and creation conditions remain unobserved. Do not repeat unchanged.
Evidence is in `research/vehicle-diagnostics/vhds-live-passive-20261021.json` and
local `captures/vhds-live-passive-20261021/`. The74 tests pass (18 runner,33 reader,
23 shared lifecycle).

Offline checks:

```bash
python3 -m unittest discover -s tools/vehicle-diagnostics -p 'test_run_vhds_config_export.py'
python3 -m unittest discover -s research/vehicle-diagnostics -p 'test_read_vhds_config_export.py'
```
