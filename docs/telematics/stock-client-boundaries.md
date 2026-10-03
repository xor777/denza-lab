# Stock-client boundaries: sleep, Wi-Fi retention, incoming commands

Part of [Telematics findings](README.md). Moved verbatim from `docs/telematics-findings.md` on 2026-10-03; "above" and "below" in the text refer to that file's order (see [Pages](README.md#pages)).

## Contents

- [Stock-client reuse, sleep and incoming commands: current boundaries](#stock-client-reuse-sleep-and-incoming-commands-current-boundaries)
  - [QuickBoot, Wi-Fi retention and recovery, 2026-09-23](#quickboot-wi-fi-retention-and-recovery-2026-09-23)
  - [Cellular data retention during ACC-off, 2026-09-23](#cellular-data-retention-during-acc-off-2026-09-23)

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

> **Superseded 2026-09-24:** the durable adapter exists: the «Облако» tile, on the owner's car since build 55, drives the stock client through the local ADB shell (not from the app UID) — see [cloud-tile.md, Denza Apps «Облако» tile (2026-09-23; on the owner's car since build 55)](cloud-tile.md#denza-apps-облако-tile-2026-09-23-on-the-owners-car-since-build-55).

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
reproduced here. See [the self-start findings](../split-screen-findings.md#the-self-start-switch-and-a-registrar-that-never-registered-live-2026-09-23)
and [the existing recovery owner](../adb-authorization-recovery.md#product-behaviour).
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

> **Superseded 2026-09-24:** build 55 was installed on the owner's car — see [cloud-tile.md, Build 55 recovery fixes and owner-car timing checks, 2026-09-24](cloud-tile.md#build-55-recovery-fixes-and-owner-car-timing-checks-2026-09-24).

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
