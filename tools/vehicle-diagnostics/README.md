# Vehicle diagnostic research tools

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

Every request checks shell/profile170/P/zero speed/idle diagnostic manager;
the process holds the same file lock as the packaged DTC reader. A passive
two-second window precedes the first request. ECM identity mismatch, unexpected
traffic, invalid sequencing, DTC count/list mismatch or a changed guard stops
the plan. Deadline90 s, per-request timeout4 s, no retries or arbitrary targets.
There are no session, clear, security, routine, VIN/serial or OTA requests.

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
