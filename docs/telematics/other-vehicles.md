# Telematics on other vehicles

Part of [Telematics findings](README.md). Moved verbatim from `docs/telematics-findings.md` on 2026-10-03; "above" and "below" in the text refer to that file's order (see [Pages](README.md#pages)).

## Current state

Updated 2026-10-16.

| Claim | Status | Section |
|---|---|---|
| N9, September Android 13 firmware, owner-reported rSIM: native and manual ready=4 reach DNS/TLS and fresh registration replies with code 9; TCP remains 0 | live, 2026-10-16 | [N9 registration refusal](#denza-n9-registration-reply-9-2026-10-16) |
| U9: ready=4 reaches the client, but its downstream behavior is unverified with the retained filtered reports | owner-supplied reports, 2026-09-28 | [U9](#yangwang-u9--android-12-ready-notification-arrives-without-connection-2026-09-28) |

## Contents

- [Denza N9: registration reply 9, 2026-10-16](#denza-n9-registration-reply-9-2026-10-16)
- [Yangwang U9 / Android 12: ready notification arrives without connection, 2026-09-28](#yangwang-u9--android-12-ready-notification-arrives-without-connection-2026-09-28)

## Denza N9: registration reply 9, 2026-10-16

The owner identified the connected vehicle as N9, with rSIM and Wi-Fi, and
authorized diagnosis and stock-cloud startup. The existing trusted ADB transport
was used. Firmware: `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260902.221210:user/release-keys`.
This is not the retained May Z9GT binary; the installed executable was unreadable.
Denza Apps (`dev.denza.apps`) was not installed; no APK was installed during this run.

Before the owner's pause, SIM was ABSENT and both identity properties were empty.
After the owner resumed work, uptime had reset, PID changed 121 -> 113, SIM was
LOADED with operator 46001, and APN3 was `connect` on `ccmni0`. The profile was
already `double_apn` with APN1 disabled before either observation. Default Wi-Fi
was validated. No reboot, profile, Wi-Fi, SIM or identity change was performed
by this session.

The bounded native/framework capture establishes the following, in **Moscow time**:

1. At **16:22:36.790**, the stock framework received
   `CONNECTIVITY_CHANGE_FUNCTION`, reported `netStatus is:4,apnType:2` and
   `APN3_CONNECTED start_work`. Native `notify_nw(4)` followed at **16:22:36.807**,
   before the researcher's manual signal.
2. Registration/discovery DNS succeeded. At **16:22:39.482**, native TLS reported
   successful certificate verification and negotiated
   `ECDHE-RSA-AES128-GCM-SHA256`. Command 211 was sent at **16:22:39.585**.
3. At **16:22:39.863**, a received reply produced `211 reg_status 9`, followed by
   `211 register fail! fail code = 9`.
4. The researcher's single manual ready=4 at **16:22:43.631** returned
   `Result: Parcel(NULL)`. It produced successful TLS and another fresh code-9
   reply at **16:22:46.325**. Subsequent native retries also received 9 at
   **16:22:56.691** and **16:23:32.837**; no further manual ready was sent.
5. At **16:24:03**, the getter still returned TCP 0, `sys.tcp_step=1` and
   `sys.tcp_reg_errcode=9`. Wi-Fi and ADB remained available, PID remained 113.

This establishes an application-protocol registration refusal, not a missing
activation event or inability to reach the registration server. The vendor's
meaning of code 9 has not been recovered. It does not prove a specific SIM,
account or vehicle-identity mismatch. SafeKey emitted `SAR_INVALIDHANDLEERR`
messages within a TLS attempt that ultimately succeeded; those messages alone
are not the observed blocker.

Identity shape only: IMSI is 15 decimal digits with Chinese MCC; ICCID is 20
characters, starts with the Chinese prefix, contains hexadecimal letters, and
has no trailing F padding. This warrants comparison with the intended rSIM
configuration, but is not proof of invalid identity or the meaning of code 9.
The identities actually packed into 211 were not compared with current properties.
No identifiers, credential contents or command payloads were exported.

Both cellular APN3 and Wi-Fi were active. The client logged a route command via
`ccmni0`; the default network being Wi-Fi is not sufficient to attribute the TLS
socket to Wi-Fi. No transport switch was tested. Native stock retries were left
under the firmware's control; the initial profile was retained.

Evidence (local, ignored): `captures/n9-cloud-20261016/` contains the plan,
read-only collector, before/after/final snapshots and redacted native/framework
logs. The bounded host log observers were stopped; the ADB transport was retained.

## Yangwang U9 / Android 12: ready notification arrives without connection, 2026-09-28

Two owner-supplied reports, exported at **11:40:39** and **14:29:32 UTC**, come
from the same build-60 APK (SHA-256
`f9a72cdd42a9c48448201f9adfc2a6169da3a3104d47bfd7075af4b36d27ae01`).
The user identifies the vehicle as U9. Its reported firmware is
`BYD-AUTO/DiLink6.0/DiLink6.0:12/SKQ1.220702.001/eng.build.20260416.233403:user/release-keys`.
This is not the researched Z9GT firmware. Running the APK and exporting these
reports establishes that the installation barrier was passed on this build;
it does not establish feature compatibility.

Both exports show validated Wi-Fi, `double_apn`, APN1 disabled, disconnected
APN1/APN3 and TCP=false. The native log independently records **eight** ready=4
notifications in the earlier report and **six** in the later one. Thus the
Binder notification reaches cloudmanager; this is stronger than a successful
shell reply. Profile changes and restoration read back successfully. No
sample records TCP=true. Earlier PIDs change 706 → 773 → 750; the reports do
not distinguish process restart from head-unit reboot or explain the cause.
The later report retains PID 750 throughout its approximately 39-minute history.

The reports contain no classified DNS, socket, TLS or registration-reply
events. `step` and `regError` are absent, not zero. `registration=1` reads the
persistent `persist.sys.cloud.app_reg_status`, not a fresh 211 response;
`token=0` is not a demonstrated cause. Both SIM properties have numeric shapes
of 15/20 characters, which does not establish server acceptance. `gate=OPENED`
is the app's estimate after sending ready, not a read of the native gate.
The finite log capture restricts PID, tags and recognized messages to the
researched Denza patterns. Missing events cannot establish that U9 attempted
no network traffic or that BYD rejected registration.

**Concrete hypothesis, not U9 proof:** the previously retained Dolphin
comparison implements notify_nw for only 1 and -2. At `0x28524–0x28530`, any
other value, including 4, goes to the epilogue after the notification log.
The researched Z9GT instead handles 4 under double_apn. A U9 variant of that
earlier handler would explain the observed notification-only trace. Its native
binary/framework must be inspected before choosing a different event and its
matching disconnect behavior. Endpoint/profile selection and the TCP getter
also need verification on that build; no addresses or transaction semantics
from another firmware constitute U9 proof.

Four cross-checks made later the same day, from existing files only:

- **Platform.** Every Denza reference in this repository (the owner's Z9GT,
  the forum cars) is DiLink 5.1 / Android 13 / `TP1A.220624.014`, product
  `IVI`. The U9 is DiLink 6.0 / Android 12 / `SKQ1.220702.001`. Cloudmanager
  is built per DiLink generation: Dolphin carries `di3_6125f_…`, the Z9GT
  `di5.1_cloudmanager_V0.1.0_MP_20260626…`. The 4 + `double_apn` branch the
  product relies on belongs to the latter; the U9 executable has not been seen.
- **Baseline contrast.** The same native capture on Denza forum cars (build
  58) recorded public DNS, connect, TLS and three 211 sends within about 25 s
  of ready=4 (09:42:58 → 09:43:23 on 2026-09-24). On the U9, fourteen ready=4
  deliveries across both histories produced no classified line of any kind,
  and `sys.tcp_step` / `sys.tcp_reg_errcode` stayed empty.
- **Startup order is not the explanation.** The adapter's `elapsedRealtime`
  places a head-unit boot at ≈11:39:07 UTC (PID 773 → 750); the 11:27:16 read
  (PID 773) already shows `double_apn` before any new profile write. Both
  processes therefore started with the Wi-Fi profile in place, and later 4s
  were still silent — provided the U9 client reads the same property.
- **Dolphin handler rechecked** in
  `captures/telematics-20260922/dolphin-comparison/selected-comparison.asm`:
  the `notify_nw()  state = %d` log call at `0x28520` precedes the -2/1
  comparisons at `0x28524–0x28530`, so "log line, then silence" is exactly
  what that handler produces for 4.

Next useful evidence is one read-only U9 collection: native startup/connection
logs without the Denza-specific tag restriction, relevant framework/Binder
definitions and the matching cloudmanager executable if readable (otherwise
from matching firmware). This permits static selection of a bounded stock
activation test rather than repeated blind toggles. No APK, service, router,
vehicle or cloud mutation was made in this analysis.

Saved reports and source hashes are in ignored
`captures/telematics-20260928/u9-cloud-reports/`. Report SHA-256 values are
`b5298af26ce3b4ccf6b2e5a9351c0e820eba1fae8bd68005b9b726009b9bceea`
and `3cd8308fbc3c19f7e543b5bdf8b987f3837d5d4ce8524d1bba2c579aab9bd74b`.
