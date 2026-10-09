# «Облако» tile and stock-client Wi-Fi adaptation

Part of [Telematics findings](README.md). Moved verbatim from `docs/telematics-findings.md` on 2026-10-03; "above" and "below" in the text refer to that file's order (see [Pages](README.md#pages)).

## Contents

- [Stock-client Wi-Fi adaptation, 2026-09-23](#stock-client-wi-fi-adaptation-2026-09-23)
  - [Denza Apps «Облако» tile (2026-09-23; on the owner's car since build 55)](#denza-apps-облако-tile-2026-09-23-on-the-owners-car-since-build-55)
  - [Implementation review, 2026-09-24: recovery defects before live acceptance](#implementation-review-2026-09-24-recovery-defects-before-live-acceptance)
  - [Owner-reported off/on stall and local-SIM failures, 2026-09-24](#owner-reported-offon-stall-and-local-sim-failures-2026-09-24)
  - [Forum photos: OPENED with triple_apn on both transports, 2026-09-24](#forum-photos-opened-with-triple_apn-on-both-transports-2026-09-24)
  - [Build 55 recovery fixes and owner-car timing checks, 2026-09-24](#build-55-recovery-fixes-and-owner-car-timing-checks-2026-09-24)
  - [Build 56: larger bounded diagnostic history, 2026-09-24](#build-56-larger-bounded-diagnostic-history-2026-09-24)
  - [Forum report: incomplete cloud-service response, 2026-09-24](#forum-report-incomplete-cloud-service-response-2026-09-24)
  - [Build 57: completed empty APN reads use the firmware default, 2026-09-24](#build-57-completed-empty-apn-reads-use-the-firmware-default-2026-09-24)
  - [Forum Wi-Fi / ordinary-SIM reports: native registration stage, 2026-09-24](#forum-wi-fi--ordinary-sim-reports-native-registration-stage-2026-09-24)
  - [Another ordinary-SIM report: code 3 appears during the Wi-Fi attempt, 2026-09-24](#another-ordinary-sim-report-code-3-appears-during-the-wi-fi-attempt-2026-09-24)
  - [Build-58 follow-up: fresh native registration replies with code 3, 2026-09-24](#build-58-follow-up-fresh-native-registration-replies-with-code-3-2026-09-24)
  - [Additional build-58 report: repeated code 3, changed PID and ICCID shape, 2026-09-24](#additional-build-58-report-repeated-code-3-changed-pid-and-iccid-shape-2026-09-24)
  - [Build 60 hotfix: stale-reading flash on re-enable, 2026-09-24](#build-60-hotfix-stale-reading-flash-on-re-enable-2026-09-24)
  - [Automatic failures clear with the next working pass, 2026-10-08](#automatic-failures-clear-with-the-next-working-pass-2026-10-08)
  - [The tile's fixed vocabulary, 2026-10-09](#the-tiles-fixed-vocabulary-2026-10-09)
  - [Build 59: confirmed application fixes and cache-refresh investigation, 2026-09-24](#build-59-confirmed-application-fixes-and-cache-refresh-investigation-2026-09-24)
  - [Offline identity preparation and command 211 reproduction, 2026-09-24](#offline-identity-preparation-and-command-211-reproduction-2026-09-24)
  - [R-SIM terminology and configurable ICCID, 2026-09-24](#r-sim-terminology-and-configurable-iccid-2026-09-24)
  - [Reported Wi-Fi success after SIM removal; native identity cache, 2026-09-24](#reported-wi-fi-success-after-sim-removal-native-identity-cache-2026-09-24)
  - [Build 58: automatic native-cloud diagnostics in the same export, 2026-09-24](#build-58-automatic-native-cloud-diagnostics-in-the-same-export-2026-09-24)
  - [Stock keepalive reboots a parked head unit after ~3.5 h without TCP (firmware, 2026-09-23)](#stock-keepalive-reboots-a-parked-head-unit-after-35-h-without-tcp-firmware-2026-09-23)
  - [Official app operation-PIN reset, observed 2026-09-23](#official-app-operation-pin-reset-observed-2026-09-23)
  - [Phone app English-language investigation, 2026-09-23](#phone-app-english-language-investigation-2026-09-23)
  - [Pre-activation evidence: native gate emulation and read-only checks, 2026-09-23](#pre-activation-evidence-native-gate-emulation-and-read-only-checks-2026-09-23)

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

### Denza Apps «Облако» tile (2026-09-23; on the owner's car since build 55)

> **Superseded 2026-09-24:** build 55 was installed on the owner's car and passed an awake off/on check; builds 55–60 changed recovery, polling, freshness and diagnostics — see [Build 55 recovery fixes and owner-car timing checks, 2026-09-24](#build-55-recovery-fixes-and-owner-car-timing-checks-2026-09-24) and the build sections after it.

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
  > **Checked 2026-10-03:** still true on main. `CloudNetwork.kindOf` (`feature/cloud/CloudLinkStatus.kt`) counts a validated Wi-Fi or cellular default network as usable. A Wi-Fi-only factory rule existed only in WIP commit 9cc0247e, which eed3a411 reverted (kept on branch `archive/cloudmanager-runtime`).
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
  > **Superseded 2026-09-24:** after the 5/15/30/60 s follow-ups, polling is now every 15 s while connecting, 60 s while connected and 30 s while offline or finishing a disable, and a reading older than 90 s is stale (`CloudLinkController.kt`, `CloudLinkStatus.kt`) — see [Build 55 recovery fixes and owner-car timing checks, 2026-09-24](#build-55-recovery-fixes-and-owner-car-timing-checks-2026-09-24) and [Build 58: automatic native-cloud diagnostics in the same export, 2026-09-24](#build-58-automatic-native-cloud-diagnostics-in-the-same-export-2026-09-24).

The tile says «Выключено», «На связи» (TCP=1), «Нет интернета» (on and
waiting, not a fault), «Подключается» (working), or the press the car refused: «Не
включилось» / «Не выключилось». Pressing a refused tile asks for the same
thing again rather than reversing it. A refusal clears as soon as the link is
seen up by any path.

> **Superseded 2026-10-09:** the code did not say «Не включилось» / «Не выключилось»; it printed the controller's own reason. The tile now has a fixed vocabulary of eight words — see [The tile's fixed vocabulary, 2026-10-09](#the-tiles-fixed-vocabulary-2026-10-09).

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

#### Mobile data from a local SIM (built 2026-09-23; forum attempts 2026-09-24)

> **Superseded 2026-09-24:** forum owners tried it. Over mobile data (`ccmni1`) the stock client connected, finished TLS and sent 211, and the server answered code 3; no TCP=1 on mobile data has been recorded — see [Build-58 follow-up: fresh native registration replies with code 3, 2026-09-24](#build-58-follow-up-fresh-native-registration-replies-with-code-3-2026-09-24).

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

> **Superseded 2026-09-24:** all six defects below were fixed in build 55 — see [Build 55 recovery fixes and owner-car timing checks, 2026-09-24](#build-55-recovery-fixes-and-owner-car-timing-checks-2026-09-24).

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

> **Superseded 2026-09-24:** build 55 ends the indefinite spinner: after the 90-second settle an unconnected client shows «Нет связи с облаком» (`CloudLinkStatus.kt`) while bounded recovery continues — see [Build 55 recovery fixes and owner-car timing checks, 2026-09-24](#build-55-recovery-fixes-and-owner-car-timing-checks-2026-09-24).

> **Superseded 2026-10-09:** that state reads «Нет связи» — see [The tile's fixed vocabulary, 2026-10-09](#the-tiles-fixed-vocabulary-2026-10-09).

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

> **Superseded 2026-09-24:** build 57 reads a completed empty APN property as `disconnected` and names the unread field instead of the generic message («Не прочитано с машины: …», `CloudLinkProtocol.readFailure`) — see [Build 57: completed empty APN reads use the firmware default, 2026-09-24](#build-57-completed-empty-apn-reads-use-the-firmware-default-2026-09-24).

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

> **Superseded 2026-09-24:** build-58 exports from the same car recorded fresh 211 replies with code 3 after successful TLS and sends, so code 3 is a current rejection, not only a stale value — see [Build-58 follow-up: fresh native registration replies with code 3, 2026-09-24](#build-58-follow-up-fresh-native-registration-replies-with-code-3-2026-09-24).

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

### Automatic failures clear with the next working pass, 2026-10-08

Found by a read-only code review; code change, not yet on any car.

The controller kept one failure field for two different things. A press the
car did not take and a failed automatic pass (the periodic reading, a status
broadcast, a follow-up after `4`, a network edge) both wrote it, and an
automatic pass cleared it only when it sent something or read TCP=1. A car on
Wi-Fi waiting out the 5–60 minute backoff without a connection - the usual
state of a car whose 211 is answered with code 3 - therefore kept a coral tile
with a transport class name («Нет ответа: IOException») after a single ADB
timeout, although the next reading 15 s later succeeded. The tile stayed so
until the next `4` or TCP=1: up to an hour.

`CloudLinkFailures` now keeps them apart:

- **Press failure:** written by an explicit on, off or Wi-Fi-retention press
  that did not complete. It stays until a press is taken, an automatic pass's
  own write goes through, TCP reads 1, or an off is confirmed. It is shown with
  the switch on or off, as before.
- **Automatic failure:** written by an automatic pass that threw. It clears
  with the next automatic pass that reads the car and carries out its plan,
  including a plan with nothing to send. A new on/off press clears it, and a
  confirmed off clears both. It is shown only while the switch is on and ranks
  below a press failure.

Lasting trouble keeps its own lower-priority states: «Нет свежих данных»,
«Профиль изменился», a fresh 211 rejection, «Нет связи с облаком». No polling,
retry, backoff or vehicle write changed, and the tile's wording is unchanged.

> **Superseded 2026-10-09:** the failures keep their reasons for the report and the tile says one of a fixed set of words; «Профиль изменился», the 211 rejection and «Нет связи с облаком» all read «Нет связи», a failed pass reads «Нет свежих данных» or «Нет связи» — see [The tile's fixed vocabulary, 2026-10-09](#the-tiles-fixed-vocabulary-2026-10-09).
The «Отказ» row of «Сервис» shows both, the automatic one prefixed
«автоматика:», and the exported report adds `automaticFailure=` beside
`failure=`. `CloudLinkFailuresTest` drives the transitions with the real
`CloudLinkCore` backoff.

### The tile's fixed vocabulary, 2026-10-09

Code change, not yet on any car.

The tile printed whatever the controller caught. A refused press showed the
exception's own words - an `IllegalStateException`'s message or «Нет ответа:
<class>» - and so did a failed automatic pass: «Нет ответа:
SocketTimeoutException», «Не прочитано с машины: TCP, профиль, флаг APN1, APN1
(чтение не завершено)», «Команда облачному сервису отклонена (4)», and from the
bare `check` in `CloudLinkOperations.setProfile` the English «Check failed.».
«Облако отклонило регистрацию (код 3)» (36 characters) and «Нет связи с
облаком» (19) ran past the tile's 17. The boards and this page said «Не
включилось»; the tests handed that string in by hand.

Now each failure is a `CloudFailure`: a kind, which is the tile's word, and
the reason, which only the «Отказ» row of «Сервис» and the exported report
carry (`CloudFailure.report`, «<word>: <reason>»). The whole vocabulary:

| Tile | When |
| --- | --- |
| «Выключено» | switched off |
| «На связи» | TCP 1 - also when the adapter's last pass read the car and then failed at something else |
| «Нет интернета» | on, no usable internet |
| «Подключается» | on, on internet, not connected yet |
| «Не включилось» / «Не выключилось» | a press the car did not take, by its direction; a pending disable is «Не выключилось» |
| «Нет свежих данных» | the car could not be read (a failed pass that never got its reading, or a reading older than 90 s) |
| «Нет связи» | on and on internet with no link: a pass that read the car and could not finish, the profile changed by somebody else, a fresh 211 rejection, or no TCP after the 90 s settle |

A refused «Держать Wi-Fi включенным» is no longer the link's refused press: the
panel's switch reads the car's value back, which is the answer, and the reason
goes to the «Отказ» row as «Wi-Fi во сне: …». The 211 rejection's code goes
there too. `CloudLinkFailuresTest` holds that no reason reaches the tile, and
`TileCaptionContractTest` holds every word to the tile's budget.

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

Reproducer: [inspect_identity_cache.py](../../research/telematics-firmware/inspect_identity_cache.py).
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

Reproducer: [verify_identity_native.py](../../research/telematics-firmware/verify_identity_native.py).
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

### Pre-activation evidence: native gate emulation and read-only checks, 2026-09-23

A small network-event adapter is therefore a live-supported path without
replacing the stock executable. Native execution
in a bounded local emulator confirms that `notify_nw(4)` under `double_apn`
opens the stock connectivity gate and reaches endpoint resolution/connection
dispatch. Code 4 is the APN3-ready event, so using it for Wi-Fi would be an
intentional translation, not a report of actual cellular attachment.

[verify_network_gate_native.py](../../research/telematics-firmware/verify_network_gate_native.py)
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
[stock_wifi_gate_test.py](../../tools/telematics/stock_wifi_gate_test.py) defaults to
preview and normally prepares a 75-second observation with an independent
restoration guard. `--hold` overrides the deadline for the explicitly requested
until-stop run. It pairs state 4 with -5 before restoring the original profile.
Unlike the prior single-report helper, the stock client may automatically
persist a token and process incoming cloud commands. The owner accepted this
full-session scope before activation. Operational restoration does not erase
a legitimate token or undo server registration.
