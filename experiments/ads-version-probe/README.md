# Bounded ADS version read

## Current state

| Claim | Status | Evidence |
| --- | --- | --- |
| OEM engineering screen reads ADS versions at request 0x712, expects reply 0x71A, owner 5, SID 0x22 | firmware | `EcuVersionManager.v/Y`, `ADSVersionActivity.a` |
| Shell-UID transport, shared RX/TX and response envelope have live proof for another ECU | supporting evidence only | `research/vehicle-diagnostics/DtcReadProbe.java`, diagnostics section of `docs/vehicle-data-findings.md` |
| This ADS reader returned six positive version responses and one NRC31 on the owner's profile170 car | live, 2026-10-18 | `captures/adas-speed-20261018/reports/ads-version-probe/` |
| Manager state, service PIDs and crash buffer unchanged across the run; staged dex removed | live, 2026-10-18 | preflight reports, `after-software.txt`, `postflight.txt` |
| Authenticated OTA supplied F194/FF95/FF94; five further reads confirmed software code and configuration 0.00.04 / 4845FF11E083000319 | live, 2026-10-18 | `captures/adas-speed-20261018/reports/ads-config-identity/` |
| Separate DoIP reader reached ADS 192.168.195.3:13400 and confirmed 4.14.07; directory read returned NRC7F, F186 returned NRC31 and prevented session entry | live, 2026-10-18 | `captures/adas-speed-20261018/reports/ads-direct-read/` |
| A speed-ceiling parameter, ADS firmware image or configuration content | not found | `docs/adas-operating-limits.md` |

## Contents

- [Sources and scope](#sources-and-scope)
- [Live plan, written before first execution](#live-plan-written-before-first-execution)
- [Observed results](#observed-results)
- [Configuration identity](#configuration-identity)
- [Direct DoIP reader](#direct-doip-reader)
- [Build](#build)

## Sources and scope

Sources from the matching installed framework and engineering APK:

- `captures/adb-firmware-20261006/jadx-devtools/sources/c/a/a/i0/a.java`
  (`EcuVersionManager`): `v(int)` forms owner 05 + 0712 + 03 22 + DID;
  `Y(byte[])` routes response address 1818 (0x71A) to the ADS screen.
- Same tree, `com/byd/byddevelopmenttools/activity/ADSVersionActivity.java`
  maps DIDs to version fields; `c/a/a/m0/a.java` decodes their values.
- Framework `BYDAutoMqttDevice`, `AbsBYDAutoDevice`: dev1033, buffer
  TX=0xAA00010A, callback RX=0x9900010A.
- Firmware `BYDAutoGearboxDevice.getGearboxAutoModeType`: dev1011,
  FID555745336, P=1; park-brake switch FID89129008 is checked separately.
- Previous same-day shell DTC reads established the response envelope and
  duplicate generic callbacks. This helper uses only onDataEventChanged,
  checks declared length and excludes unrelated tail bytes.

The CAN reader `AdsVersionReadProbe.java` makes one request per process, no retry. Fixed ECU, owner, SID and ten explicitly
named identity DIDs only: software F195, hardware F193, soc-app 01A0, soc 0178,
mcu 0170, mcu-app 0172, bootloader F180; plus software-code F194,
config-version FF95 and config-code FF94 from the authenticated OTA response.
No generic payload or address option.
It does not call 0x10/0x11/0x14/0x27/0x2E/0x2F/0x31, send tester-present,
start OTA, unlock the engineering UI, modify ADAS settings or request firmware.
SDK method `set` is solely the transport for the fixed UDS **read** request.

## Live plan, written before first execution

Hypothesis: the fixed OEM request `05 07 12 03 22 F1 95` returns a complete
`62 F1 95 ...` version response with owner5/address071A through the existing
SDK under shell UID2000. Negative, busy, mismatched or absent response falsifies
this single-target hypothesis; do not scan addresses, alter owner or unlock.

Initial reset: no prior ADS probe installed/running, manager property
`sys.diagmgrsrv.task_bk=r@-1_0&w@-1_0`, SDK gear P=1, park-brake1, speed0;
autoservice PID135, SpotInspection PID3622. Existing diagnostic helpers absent
from process listing. Acquire the same `/data/local/tmp/denza-dtc-read.lock`
used by the product reader and observe RX for two seconds before transmitting.
This is cooperative exclusion and an observed quiet interval, not a global lock
against every OEM process. Abort if traffic or baseline changes.

Run only `software` first. Other named version reads require inspecting the
first result and unchanged manager/process state. The request window is ten
seconds, hard watchdog fifteen. Unregister and exit after one complete response;
reject multi-frame responses instead of guessing. Save request/result, before/
after manager state, PIDs and crash buffer under
`captures/adas-speed-20261018/reports/ads-version-probe/`.
Delete only this probe's staged dex afterward; leave shared lock inode intact.

## Observed results

Seven individual runs on 2026-10-18, each with one fixed request and no retry:

| Name | DID | Raw value bytes | OEM display |
| --- | --- | --- | --- |
| software | F195 | A1BF1A061D00 | 4.14.07 |
| hardware | F193 | 03170C0C00 | 0.0.3 |
| soc | 0178 | 29171A0303 | 10519 |
| mcu | 0170 | 290F1A0106 | 10511 |
| mcu-app | 0172 | 003EA6841D | 1051100189 |
| bootloader | F180 | 172A01030000170A09 | 2.3 |
| soc-app | 01A0 | negative payload 7F2231 | unavailable in this request |

Values use the matching OEM decoder, not guessed semantic version formatting.
Unused tail bytes remain uninterpreted. NRC31 means request out of range /
identifier not supported, as defined by the local SpotInspection `NrcDict`;
it does not prove the component is absent.

All runs recorded selector P=1, park brake=1, speed=0 twice, transport result0,
one response and `UNREGISTERED`. After software and after the entire sequence:
manager `r@-1_0&w@-1_0`, autoservice PID135, SpotInspection PID3622 unchanged.
Crash buffer contained only the same two older libc messages from October17.
The temporary `/data/local/tmp/denza-ads-version-90abddb9.dex` was removed;
the shared diagnostic lock inode was left intact. No APK was installed.

Historical seven-DID source SHA-256: `b210be3a8406e11852d75241754409a25ae4dd30cd706f406068bce87f93915e`.
DEX SHA-256: `90abddb9c5401bed79d987b7182f3b30e127f0a92fca0d4262fc50abe2d04829`.
Both are recorded in the ignored capture `identity.json`, along with the build.
The original source is retained there as `AdsVersionReadProbe.original.java`.
`validation.json` records hashes of the source, decoder and captures, and the
offline comparison of manager state, PIDs and crash buffer.

Reproduce the result table offline:

```bash
python3 experiments/ads-version-probe/decode_results.py \
  captures/adas-speed-20261018/reports/ads-version-probe \
  --output captures/adas-speed-20261018/reports/ads-version-probe/decoded.json
```

The decoder checks the single request/response, transport result, stationary
preflight, cleanup and declared value consistency. It preserves the complete
raw payload and capture SHA-256. Identification does not establish the speed
ceiling's location, hardware manufacturer or equivalence to an export ADS.

## Configuration identity

The owner's authenticated GCY `getEcuList` returned ADS
`HTH-2021_ADC_BYD_00100001`, software DIDs F195/F194, hardware F193 and
configuration FF95/FF94. The source allowlist was extended only for the three
new identifiers. The pre-run plan is in `reports/ads-config-identity/plan.json`.
Transport, one-request policy, stationary checks and cleanup are unchanged.

| Name | DID | Raw value bytes | OEM value |
| --- | --- | --- | --- |
| software-code | F194 | 48450011DF83000000 | 48450011DF83000000 |
| config-version | FF95 | 000418081604 | 0.00.04 |
| config-code | FF94 | 4845FF11E083000319 | 4845FF11E083000319 |
| hardware | F193 | 03170C0C00 | 0.0.3 |
| software | F195 | A1BF1A061D00 | 4.14.07 |

F194 matched the cached OTA software code before the remaining reads ran.
The first two bytes of FF95 use the OEM five-digit decimal version parser;
its remaining four bytes are uninterpreted. F194/FF94 use the first nine bytes
as uppercase hex, matching `CanDiagnostic.parseEcuSoftCodeInfo`.
These reads identify the configuration; they do not retrieve its content.

All five reads returned one positive response, transport0 and UNREGISTERED.
Manager idle, autoservice135, BydLogTool4822 and crash buffer were unchanged
after each read. The staged dex was removed in the host runner's finally block.
Source SHA-256: `f8a286b50b5364bd309ce930be3307b3ce202d15c1273ddb0c743bc21548bbec`.
DEX SHA-256: `8b158b7ac14011a15ee435f16e9ab35f1abea973427e31fd8d703cb48d9537ae`.
Build: JDK17 --release8, Android33 stubs, D8 build-tools34 --min-api33.

```bash
python3 experiments/ads-version-probe/decode_results.py \
  captures/adas-speed-20261018/reports/ads-config-identity \
  --fields software-code config-version config-code hardware software \
  --output /tmp/ads-config-identity-decoded.json
```

The decoder defaults to the original seven names to preserve historical replay.
The cloud request using these measured hardware/config values still received
status111 and no package URLs; see `docs/adas-operating-limits.md`.

## Direct DoIP reader

`AdsDoipReadProbe.java` is a separate shell-UID reader using the actual Ethernet
route found in OEM `DoipDiagnostic` and the installed `libdoip.so`. It binds
192.168.195.2 and connects only to 192.168.195.3:13400, tester1/target071A.
The cloud field `ecuPort=0x0301` is not this TCP port. Sources, disassembly,
pre-run plans and per-run cleanup are in `reports/ads-direct-read/`.

Fixed modes:

- No argument: routing activation, then `22F195`; exact expected response
  `62F195A1BF1A061D00`. This was confirmed live.
- `root-directory`: same identity, then `380500012F` (root directory metadata).
  Live response was NRC7F, service unsupported in the active session. No
  transfer was accepted and no `37` was sent; file contents were not read.
- `root-directory-extended`: identity, then `22F186`, requiring exact
  `62F18601` before any `1003`. The live result was `7F2231`, so the guard
  stopped **before either session-control command**. Extended-session access
  was not tested. The code's unexecuted cleanup branch requires `5001` and a
  separate `62F18601` to claim restoration; an offline Java stream fixture
  exercised both success and failure cases without network access.

All modes share the diagnostic lock and refuse a busy manager or existing ADS
TCP session. Checks require profile170, selectorP, park brake1 and speed0.
Connect/read/window/hard deadlines are 1.8/2.5/7/15 seconds; socket closure
precedes lock release. No discovery scans, security access, tester-present,
programming, flashing or vehicle configuration writes are implemented.

All three live runs confirmed routing acceptance0x10 and software4.14.07.
After each run the staged dex was removed, manager idle, autoservice135,
BydLogTool4822 and the crash buffer were unchanged. The original identity and
directory source revisions are preserved beside their plans. The guarded
revision's DEX SHA-256 is
`92143062566a9f6bbaf9f7c63069482eae0b041b5a700a7235b50bfa2b4e085e`.
These findings establish a working transport, not a firmware-dump method.

## Build

Compile Java against local `platforms/android-33/android.jar`, then D8 with
`--min-api 33`, using Homebrew JDK17. Generated classes/dex belong under this
module's ignored `build/`. Run dex through app_process as shell, not an APK.
This research helper is not configured in product Gradle dependencies.
