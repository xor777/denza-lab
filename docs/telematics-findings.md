# Telematics findings — companion app, cloud, and vehicle clients

Why the phone app shows the car as offline. Phone access to the cloud and
vehicle telemetry ingestion must be established separately. The head unit
itself runs several cloud clients; ownership of the phone's main status feed
by a separate T-Box has not been established. Open 2026-09-22.

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
  it: «Если машина долго стоит, может разрядиться аккумулятор».
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
- **On**: when the car is not on `double_apn` with APN1 disabled, send the
  profile broadcast, wait 3 s and read the profile back. Then, with validated
  Wi-Fi (the default network with `NET_CAPABILITY_VALIDATED`) and TCP≠1, send
  one `4`.
- **Paired loss**: when validated Wi-Fi has been gone for 30 s, send `-5`, but
  only under `double_apn` and only if the gate is not already known closed.
  When Wi-Fi returns after that, send `4` at once.
- **Repeats**: the stock `BYDMultiApnConnReceiver` sends `-5` on any
  `CONNECTIVITY_CHANGE_FUNCTION` whose APN3 is not CONNECTED. So a car on
  validated Wi-Fi that reads TCP≠1 is told `4` again, once the disconnection
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
  while on Wi-Fi without TCP and every 5 min otherwise. Readings run only while
  the foreground `CloudLinkService` runs, and it runs only while the switch is
  on.

The tile says «Выключено», «На связи» (TCP=1), «Нет Wi-Fi» (on and waiting,
not a fault), «Подключается» (working), or the press the car refused: «Не
включилось» / «Не выключилось». Pressing a refused tile asks for the same
thing again rather than reversing it. A refusal clears as soon as the link is
seen up by any path.

Lifecycle: ACC-off terminates the app. While parked, the link belongs to the
stock client, and the Wi-Fi switch decides whether it stays reachable. On wake
or boot, `RuntimeRecoveryReceiver` → `startAdbRuntime` restarts the service,
which reconciles; with the gate unknown it waits the 90 s settle before a
`4`.

Diagnose with:

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

Still open:

- the first live run of the adapter;
- broadcast delivery to an ordinary app;
- recovery across a cold boot and across a native restart;
- long-term stability;
- 12V draw with Wi-Fi retained.

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
The live event-adaptation test above demonstrates stock DNS/TLS and connected
TCP after changing network eligibility. A durable adapter, stock report delivery,
sleep/wake behavior and ordinary APK access still need to be established.

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
