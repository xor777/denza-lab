# Vehicle Data Availability Findings

This page records which vehicle and journey signals a normal Denza Apps APK can
actually use. It distinguishes product-usable sources from values that are
visible only to system processes, shell diagnostics, or vendor API surfaces
identified through static inspection.

## Current state

Updated 2026-10-21. Which vehicle signals Denza Apps can read on this head unit, from which identity, and how each one decodes.

| Claim | Status | Since | Section |
|---|---|---|---|
| A normal app UID gets standard GNSS (~1 Hz), the standard IMU and `persist.sys.AutoType`; the useful DiCar getters answer `20004` (missing `BYDAUTO_*_GET`); Shell UID reads the native Binder: `service call autoservice 5` (`getInt`) or `7` (`getFloat`) `i32 <dev> i32 <fid>`, fid as signed decimal, payload = second parcel word | live | 2026-08-22 | [High-level DiCar service](#high-level-dicar-service); [autoservice FID protocol](#autoservice-fid-protocol) |
| Sentinels: `−10013` wrong transact (retry the other one), `−10011` no data on this generation, `0xbf800000` invalid float; max-range placeholders are dropped by a per-unit range gate | live | 2026-08-22 | [Sentinels — do not display](#sentinels--do-not-display) |
| Transacts `10`/`12` with `i32`-only parcels SIGSEGV'd `/system/bin/autoservice`; native scalar reads use only `5`/`7`, never `6` (`VehicleSignals.kt`, `VehicleTransact`); the separate DTC transport uses the qualified SDK buffer API | code | 2026-08-22 | [Binder](#binder) |
| Device ids: `1000` AC, `1001` bodywork, `1006` energy, `1009` charging, `1011` gearbox, `1012` engine, `1013` speed, `1014` statistic/BMS, `1039` GB (motors, bus V), `1061` big data | live | 2026-08-22 | [Binder](#binder) |
| Every `BYDAutoFeatureIds` constant, resolved for this car: untracked `captures/ambient-light-20260923/data/fids-canfd.tsv` (and `fids-can-classic.tsv`), tab-separated `<class or TOP>`, `<NAME>`, `<signed decimal>`, built by `resolve_clinit.py` because jadx cannot evaluate the static initializer; look ids up there, not by hand | firmware | 2026-09-23 | [autoservice FID protocol](#autoservice-fid-protocol) |
| The product polls 8 hot ids (pack power, pack V, odometer, park, speed, rpm, engine running, generation) every 100 ms while the cluster, strip's car page or diagnostic section is up and every 1 s otherwise, plus 12 cold ids every 10 s; readings expire after 2 s / 25 s. That sweep is the only reader of the park switch: the strip's trip clock takes it from there under `VehicleWatcher.TRIP` (`VehicleSignals.kt`, `VehicleTelemetryHub.kt`, `TripParkFeed.kt`) | code | 2026-10-09 | [Vehicle telemetry wiring](#vehicle-telemetry-wiring-2026-08-22-to-2026-08-27) |
| Pack power `0x14400020` (dev 1012, catalog `ENGINE_POWER`) is positive out of the pack, on a charger and in motion (`VehicleConvention.POWER_POSITIVE_IS_DISCHARGE`) | live | 2026-09-22 | [Energy contract §8](energy-display-contract.md#8-open-and-what-closes-each) |
| `GENERATION_KW` `0x2610001F` (dev 1006) is the engine's charge into the pack: 8–10 kW generating in P, `0` while the engine drives the wheels; `0x2ED00010` (catalog `ENGINE_CHARGE_POWER`) is pack power, not generation | live | 2026-09-24 | [Energy contract §8](energy-display-contract.md#8-open-and-what-closes-each) |
| `ENGINE_RUNNING` `0x10D00038`: `0` stopped, `1` cranking, `3` running; the product tests `≥ 1` (`VehicleTelemetry.kt`); `ENGINE_RPM` `0x14400012` answers `0x1FFF` (13-bit "not available") with the engine off, parked or moving; `VehicleSignal.invalid` refuses it before decoding | live | 2026-09-22 | [Second parked cycle](#second-parked-cycle-2026-09-18-recorded-the-contour-on-the-cluster); [A resting reading is not the same as a resting ECU](#a-resting-reading-is-not-the-same-as-a-resting-ecu) |
| Scales: pack temp = raw − 40 °C; cells in mV; odometer = raw / 10 km; tyres = raw / 100 bar; 12 V and SOC are floats | live | 2026-08-22 | [Scales proven or likely on this car](#scales-proven-or-likely-on-this-car) |
| `CHARGE_GUN` `0x34400032`: `2` AC connected, `1` on every moving row of both recorded drives, `3` on a DC stop; "charging" needs `2`, a charger reading and a pack that is not discharging (`VehicleTelemetry.kt`) | live | 2026-09-24 | [The gun is not a charger](#the-gun-is-not-a-charger-2026-09-06-falsified-on-the-road) |
| Historical scalar GETs remain silent for coolant, torque, boost, knock and fuel in `0x324` / `0x387` / `0x30D`; the 2026-10-21 MCU trace nevertheless finds seven field parsers. API silence does not establish absence from the MCU/raw stream | live API limit; offline MCU candidates | 2026-10-21 | [Did not answer](#did-not-answer--every-candidate-at-every-device-tried); [Signal origins](#signal-origins-and-passive-qualification-2026-10-21) |
| A floored accelerator in P starts the engine, which generates 8–10 kW into the pack while held; `0x34200008` is the pedal in percent | live | 2026-09-18 | [Second parked cycle](#second-parked-cycle-2026-09-18-recorded-the-contour-on-the-cluster) |
| Consumption is the last 10 km of recorded road: a 100-point chart of 100 m buckets, each point a 1 km trailing mean; the journal `filesDir/consumption.log` keeps the newest 300 buckets as `odometer,kwh,km,knownKm` (`ConsumptionWindow.kt`, `ConsumptionChart.kt`, `ConsumptionJournal.kt`) | code | 2026-09-18 | [Energy contract §2.2](energy-display-contract.md#22-consumption-over-the-last-ten-kilometres) |
| Which car: `persist.sys.AutoType` (readable from the app UID through `getprop`) or FID `0x40D00010`: `168` N9, `170` our Z9GT; an unrecognised id is an unknown car (Z9GT `AutoType=170` also read live on 2026-10-18) | live | 2026-08-30 | [Which car is this](#which-car-is-this-2026-08-30) |
| Raw CAN-FD frames: shell-UID `BYDAutoBigDataDevice` (`1061`) listener on `0x99000020`, about 137 frames/s, delivered only while the main `Looper` runs; app-UID registration unproven | live | 2026-09-03 | [Raw CAN-FD callback](#raw-can-fd-callback-2026-09-03) |
| Turn signals: shell-UID listener on `0x1330002C` (raw lever phase, never names a side) and `0x38A0002C` (lamps: `1` off, `2` left, `4` right, `6` hazard) behind `VehicleSignalHub` (`TurnSignalEventProtocol.kt`) | live | 2026-09-04 | [Targeted turn-signal events](#targeted-turn-signal-events-2026-09-04) |
| Refuted: the stock AVC window alone opens a Denza camera (window-only contract). Since 2026-09-23 Show needs AVC's card **and** the lamps on that side, and the lamps leaving close it | refuted | 2026-09-23 | [Firmware-model contract](instrument-display-findings.md#the-firmware-model-contract-2026-09-23) |
| Refuted: `0x44700028` / 10 is remaining energy (a ~100 kWh pack). It is state of charge ×10; nothing on the Binder gives the pack size; Refuted: one of `ENGINE_RPM` / `GENERATION_KW` reads above zero on an electric drive. Engine off in motion, rpm sends only `0x1FFF` and generation reads `0` | refuted | 2026-09-22 | [Scales proven or likely on this car](#scales-proven-or-likely-on-this-car); [What stood the engine box up](#what-stood-the-engine-box-up-on-an-electric-drive-2026-09-05-superseded) |
| Washer fluid level: no FID, name or CAN bit identified; `INSTRUMENT_DD_FAULT_WASH_ELEC_GATE` (`0x3D90201E`) is probably throttle cleaning, and its `0` proves nothing | open | 2026-09-23 | [Read path verified](#read-path-verified-sensor-identity-still-unproved-2026-09-23) |
| N9 washer experiment: four broad captures and passive controls saved. The promising `0x324` byte-24 mapping `92=empty / 93=OK` is refuted: it changed `93 → 92` during an unchanged-level watch, with the owner confirming no washer use or fluid changes. Actual field meaning and washer-level signal remain unidentified | mapping refuted live, signal open | 2026-10-16 | [N9 paired-state capture](#n9-paired-state-capture-2026-10-16) |
| N9 CAN coverage: GB/YUN add 72 address tuples without table writes. Expanded present/empty/refilled cycle saved: 68,432 / 68,296 / 66,613 messages, all 199 common tuples covered without loss. Of 223 separated bits, 49 reverse; 43 contradict older controls, and 6 extra-tuple bits overlap numeric range/consumption/insulation/SOC descriptors. No washer mapping is established; 3 intermittent refill tuples also occurred in older normal/empty captures | live three-state cycle, signal open | 2026-10-16 | [N9 paired-state capture](#n9-paired-state-capture-2026-10-16) |
| DTC: dev1033 TX `AA00010A` / RX `9900010A`, profile170; exact packaged helper verified `19 01 FF` + `19 02 FF`: `720→728` eight records/status28 (stock tester and its 96-case function map: left body domain `LA_BCM`; OTA map's CDC/ZCU rows unexplained; BYD labels status28 «历史故障»); 2026-10-20 the same transport reaches the engine ECM `7E0` (OBD Mode 01/03/07/0A, UDS 19; MIL off, 0 codes) and `7E2`/`7E3`/`7E5`/`7E6`/`7E7`/`722`/`726`/`747`; full catalog sweep 2026-10-20: SoH99, cells 3333–3337 mV, service life oil75/brake fluid99/coolant50, GB alarms answer at the N9 baseline; LBMS `762→76A` zero/support09. Further chassis/ADAS addresses were qualified on 2026-10-20, below. Lamp0 means invalid, not off; captured engine lamp on. The first native window that showed these (readable DTC summaries, all eight status bits, 15 lamp GETs) never reached the car and was removed on 2026-10-20; the scanner in [vehicle-diagnostics-architecture.md](vehicle-diagnostics-architecture.md) replaced it and keeps the 43 scalar GETs (`DiagnosticExtras`) | live | 2026-10-18 | [Diagnostics and DTC](#diagnostics-and-dtc-2026-10-18); [Native window](#native-diagnostic-window-and-packaged-reader-2026-10-18); [Readable DTC](#readable-dtc-codes-and-native-detail-view-2026-10-18); [Plain-language inputs](#plain-language-inputs-reporting-module-status-labels-insulation-2026-10-18); [Deeper sources](#deeper-sources-for-a-diagnostic-screen-2026-10-20); [Z9GT catalog sweep](#z9gt-catalog-sweep-2026-10-20); [OBD and more modules](#obd-and-more-modules-through-the-same-transport-2026-10-20) |
| Remaining-ECU research: 70 F194 addresses checked, 33 positive, 32 exact cache matches, and `741` is the head unit's own MCU by the stock media software mapping; ABS/ESP782, EPS783, SRS7F1, ADC712 and MRR7F2 each return explicit zero DTC for mask09. OBD7E5 names itself BSCM-BrakeSystem. The later factory-map pass below resolves the rear-drive/DCOBC routes | live, research | 2026-10-20 | [Remaining ECU identities and chassis DTC](#remaining-ecu-identities-and-chassis-dtc-2026-10-20) |
| Complete factory metadata: 225 routing candidates; SMCU744/74C, SMCU3 745/74D and DCOBC7E4/7EC answered matching F194 and empty mask09 lists. DCOBC format remains unknown. A public Z9GT PHEV manual index now supplies engine/battery/drive DTC heading candidates, but has conflicting definitions and no ECU-software binding; no OEM dictionary is qualified. The diagnostic section shows those headings, translated, as candidate descriptions of format00 codes | live routes; dictionary open | 2026-10-20 | [Complete map](#complete-factory-map-and-remaining-powertrain-dtc-2026-10-20); [Manual candidates](#oem-dtc-manual-candidates-2026-10-20) |
| Factory-map candidates: of the 37 CAN routes outside the scanner catalog, 35 got one `22 F194` (`715`/`785` already had result 3); 22 answered. All 21 with a real code in `ECUVersion.json` returned it byte for byte, all 13 with only a zero placeholder got gateway result 3, and `5E7` returned the passenger computer's MCU code `4B450011DF990042E2` from its own OTA container. All 22 are in `EcuCatalog`; the 13 `5xx` responders are listed one by one in `DiagnosticWhitelist`. Their DTC memories were first read by the scanner's autoscan on 2026-10-20: IDC `597` has four records, the rest none | live | 2026-10-20 | [Factory-map candidates](#factory-map-candidates-on-this-car-2026-10-20); [First autoscan](#the-scanner-on-the-car-first-autoscan-2026-10-20) |
| Scanner on the car: 68/68 answered and 68 memories read in 148 exchanges, 19.9 s, exchange 18 ms median, step 120 ms at gap 100; history in LA_BCM 8, R_ZCU 2, INS 2, Media 1, CCS/SCPA 1, IDC 4; a lock race after the first idle minute stopped the section (fixed; three restarts on the car without it, 19:46–20:11) | live | 2026-10-20 | [First autoscan](#the-scanner-on-the-car-first-autoscan-2026-10-20) |
| Exact-model Xtool catalog: 172 Z9GT DM system/variant rows name all 25 catalog entries whose role was unknown; SMCU744 is left rear, SMCU3 745 right rear by cross-source token/factory/F194 join. Eight child software identities were subsequently read through four parents and matched cache8/8; 30 stock FIDs have source evidence. Nine INS/FRL/CDC subroles remain unbound; a newly resolved description-cache path is shell-denied at its ancestor. Child DTC ownership and OEM descriptions remain open | model-specific roles + live child identity | 2026-10-21 | [ECU roles and reverse crosswalk](#ecu-roles-and-reverse-catalog-crosswalk-2026-10-21); [Unattended qualification](#unattended-child-and-buffer-qualification-2026-10-21) |
| Diagnostic research: 81 CAN + 5 DoIP routes, 9,799 readable declarations and 58 MCU field descriptors. The later Z9GT passive pass received 6,949 records / 186 tuples, then 913 GB records with scalar correlation. Stock native decoders establish paged arrays for 166 cells and 14 probes; probe min/max/mean agree with qualified GETs. Cell sweeps span about5.5s and are not atomic. No new ECU addresses or applicable OEM DTC dictionary | live passive availability + firmware-backed arrays; physical placement and full validity open | 2026-10-21 | [Deep inventory](#deep-inventory-and-telemetry-beyond-identification-2026-10-21); [Signal origins](#signal-origins-and-passive-qualification-2026-10-21); [Live battery pages](#live-passive-streams-and-paged-battery-telemetry-2026-10-21) |
| Deeper source consumers: saved drive configuration7 selects18 fields for three drives; stock engine packing substitutes zero unless state3. Battery native completion is a sticky element-count latch, not page coverage or freshness. Wheel producer gate is bound to panorama command AA000048; the later typed GET returned MCU_OFFLINE despite20 matching source frames. Steering99000198 GET matched20 raw angle frames; its change stamp shares the MCU receive counter, not a qualified acquisition clock. FD words3/5 equal preceding complete cell extrema in7/7 pairs; insulation collector clamps u16 to60000, losing invalid-like bit patterns. No applicable OEM DTC definitions or record schemas | firmware + live finite buffer/source checks | 2026-10-21 | [Consumer semantics](#consumer-semantics-and-remaining-tests-2026-10-21); [Unattended qualification](#unattended-child-and-buffer-qualification-2026-10-21) |
| Head-unit cache: 225 candidate/version records, 49 with defaults/negative markers. The original truncated route prefix is superseded by the complete 2026-10-20 map. `720/728` has multiple CDC/ZCU candidates with different DIDs. Legacy fault GET/SET are unregistered; DiDiag diagnostic is a stub; MCU requests carry external addresses and `7DF` cannot collect OBD replies. OEM dictionaries remain missing | live inventory and full candidate map; dictionary open | 2026-10-20 | [Vehicle-specific inventory and dictionary acquisition](#vehicle-specific-inventory-and-dictionary-acquisition-2026-10-18); [Complete map](#complete-factory-map-and-remaining-powertrain-dtc-2026-10-20) |
| Stock Update remains unsuitable for diagnosis-only use. The historical third isolated session refused HTTP headers; a separately authorized corrected session obtained all 225 map records on 2026-10-20. Cleanup passed and that permission is consumed. Cross-reader ownership remains required; the host refuses reused working directories | live | 2026-10-20 | [Bounded observation](#bounded-observation-and-isolated-metadata-2026-10-18); [Successful metadata session](#complete-factory-map-and-remaining-powertrain-dtc-2026-10-20) |
| Ambient light colour: shell `service call autoservice 5 i32 1023 i32 283586` should return a 1-based palette index (RGB only on table `7`), the table chosen by `SET_IAL_COLOR_CONFIG` (`1072693258`); not yet read on the car, no app-UID path known | firmware | 2026-09-23 | [Interior ambient light colour](#interior-ambient-light-colour-2026-09-23-in-progress) |

**Open questions**
- What `STATISTIC_INSTANTANEOUS_CURRENT` (raw `35721`) and the third-party "rear motor 40 °C" card are: a drive recorded with the current and rear-motor ids, which `tools/vehicle_log.py` does not record yet.
- Whether a DC stop (`CHARGE_GUN` = `3`) should read as charging: a recorded stop at a charger known to be DC ([energy contract §8](energy-display-contract.md#8-open-and-what-closes-each)).
- The pack's real capacity: the owner's paperwork or one full charge session.
- Traction-voltage sag under load: `pack_volt` read across a full-throttle pull (the 2026-09-24 recording has the column; not analysed here).
- Which physical signal is the washer level: an authoritative sensor-to-ECU definition, or a controlled two-state capture with `tools/raw_can_turn_probe.sh`.
- Which palette this car uses: one read of `SET_IAL_COLOR_CONFIG`, or the owner naming the cabin colour.
- Whether a normal APK can register the raw CAN-FD listener, DiCar's ambient-light listener or the vendor SCP IMU sensors: one isolated probe each.
- The remaining Z9GT `persist.sys.*` vehicle-id properties: a qualified dump; `persist.sys.AutoType=170` is confirmed on 2026-10-18.

## Contents
- [Executive result](#executive-result) — what a normal APK can and cannot read, in one screen
- [Test environment](#test-environment) — cars, firmware, ADB target, and the dated history of this page
- [Which car is this (2026-08-30)](#which-car-is-this-2026-08-30) — telling the N9 from the Z9GT by the vendor vehicle id
- [Evidence labels](#evidence-labels) — the five labels the matrix uses
- [Availability and frequency matrix](#availability-and-frequency-matrix) — every source, its rate, permission and product status
- [Android inertial sensors](#android-inertial-sensors) — standard and vendor IMU sensors and their rates
- [GNSS](#gnss) — the Android GPS provider and what can be derived from it
- [Exported car-status provider](#exported-car-status-provider) — `com.byd.carStatusProvider`, readable but unqualified
- [High-level DiCar service](#high-level-dicar-service) — reachable Binder, blocked getters
- [autoservice FID protocol](#autoservice-fid-protocol) — the shell-UID read recipe, device ids, sentinels and scales
- [Widget allowlist (2026-08-22)](#widget-allowlist-2026-08-22) — the measured FIDs with dev, transact and decoding
- [Vehicle telemetry wiring (2026-08-22 to 2026-08-27)](#vehicle-telemetry-wiring-2026-08-22-to-2026-08-27) — the decisions behind `feature.vehicle`, its consumers and cadence
- [Combustion side of the hybrid (2026-08-23)](#combustion-side-of-the-hybrid-2026-08-23) — engine, generation, lamps, fuel, and the recorded start/stop cycles
- [Diagnostics and DTC (2026-10-18)](#diagnostics-and-dtc-2026-10-18) — proven read transport, real records, status semantics and scan coverage
- [Additional diagnostic readings and LBMS](#additional-diagnostic-readings-and-lbms-2026-10-18) — 43 GETs, window polling and the second qualified ECU
- [Remaining ECU identities and chassis DTC](#remaining-ecu-identities-and-chassis-dtc-2026-10-20) — ABS/ESP, EPS, SRS, ADAS, radar, 32 cache matches and the brake OBD interface
- [Complete factory map and remaining powertrain DTC](#complete-factory-map-and-remaining-powertrain-dtc-2026-10-20) — all 225 server routes, live rear drives/DCOBC and the OEM dictionary gap
- [Factory-map candidates on this car](#factory-map-candidates-on-this-car-2026-10-20) — F194 at the 37 CAN routes outside the catalog: who is installed, the cache as an install marker, the passenger computer's MCU
- [ECU roles and reverse catalog crosswalk](#ecu-roles-and-reverse-catalog-crosswalk-2026-10-21) — 25 role names, rear-drive sides, eight child identities and 30 stock signal candidates
- [Deep inventory and telemetry beyond identification](#deep-inventory-and-telemetry-beyond-identification-2026-10-21) — complete route accounting, readable property profiles, battery/drive/engine and chassis data paths, local-HU versus remote UDS DIDs
- [Signal origins and passive qualification](#signal-origins-and-passive-qualification-2026-10-21) — MCU field layouts, native conversion/validity, wheel packet, factory scenario limits and the bounded next-car plan
- [Live passive streams and paged battery telemetry](#live-passive-streams-and-paged-battery-telemetry-2026-10-21) — Z9GT GB/BigData/YUN, stock cell/probe array decoders, timed scalar correlation and rolling-sweep limits
- [Consumer semantics and remaining tests](#consumer-semantics-and-remaining-tests-2026-10-21) — three-drive selection, engine conditional zeros, battery completion, wheel gate, timestamped steering and bounded dictionary search
- [Unattended child and buffer qualification](#unattended-child-and-buffer-qualification-2026-10-21) — eight parent-reported identities, typed steering read, unavailable wheel buffer, FD extrema and insulation packing
- [OEM DTC manual candidates](#oem-dtc-manual-candidates-2026-10-20) — model-specific public headings, conflicts, code-format and software-applicability gaps
- [The scanner on the car: first autoscan](#the-scanner-on-the-car-first-autoscan-2026-10-20) — the product's 68 blocks in 20 s, the history found, two defects fixed
- [Consumption journal](#consumption-journal) — the on-disk bucket log and why it is append-only lines
- [Raw CAN-FD callback (2026-09-03)](#raw-can-fd-callback-2026-09-03) — the passive shell-UID frame stream and its envelope
- [Washer fluid level (2026-09-18, in progress)](#washer-fluid-level-2026-09-18-in-progress) — no name, no bit yet; firmware and raw-stream paths
- [Targeted turn-signal events (2026-09-04)](#targeted-turn-signal-events-2026-09-04) — the two-FID light listener, its hub, and the camera contracts it fed
- [Interior ambient light colour (2026-09-23, in progress)](#interior-ambient-light-colour-2026-09-23-in-progress) — where the cabin lamp colour lives and how it could be read
- [Legacy BYDAuto events and system logs](#legacy-bydauto-events-and-system-logs) — the 2026-06-27 probe and system-log observations
- [Existing navigation-derived data](#existing-navigation-derived-data) — the Yandex guidance read through accessibility
- [Product implications](#product-implications) — which product concepts the available inputs support
- [Commands and inspected inputs](#commands-and-inspected-inputs) — the read-only commands and vendor APKs used
- [Next useful validation](#next-useful-validation) — the remaining GNSS/IMU, `autoservice` and raw-CAN work

## Executive result

A normal `/data/app` APK cannot read most BYD CAN-backed values. The exported
high-level DiCar service can be reached, but its useful getters enforce
signature/privileged `BYDAUTO_*_GET` permissions at call time.

The practical product inputs are:

- standard Android GNSS: position, speed, bearing, altitude, and accuracy;
- standard Android accelerometer, gyroscope, gravity, linear acceleration, and
  game rotation vector sensors;
- possibly the vendor inertial sensors advertised without a permission, after a
  dedicated normal-APK registration test;
- an exported car-status content provider containing maintenance fields and raw
  stored trip series, whose units and update semantics are not yet understood;
- Denza Apps' existing accessibility-derived Yandex guidance while a valid
  navigation scene is visible.

The following must not be treated as product-available to a normal `/data/app`
identity (no `BYDAUTO_*_GET`, no Binder from the app process):

- accelerator/brake position, steering angle, or gear via DiCar getters;
- tire / climate / PM2.5 / BMS via DiCar getters;
- raw BYD event traffic seen in system `logcat`;
- regeneration power inferred from the unsigned/raw trip arrays.

The same BMS, HV, 12V, motor, tyre, climate, and air values **do** exist on
the native `autoservice` Binder (`android.gui.BYDAutoServer`). A trusted
local-ADB `shell` UID can read them with `service call autoservice`. That is
how third-party dashboards on this head unit get the numbers. Protocol,
scales, and the widget allowlist: [autoservice FID protocol](#autoservice-fid-protocol).

The same local shell identity can also receive the raw CAN-FD frames already
selected by the stock `CanDataCollect` service. The confirmed callback is
`BYDAutoBigDataDevice` FID `0x99000020`; a 15-second passive observation
received 2,054 callbacks. This is a diagnostic stream, not evidence that a
normal Denza Apps process can register for it. Details:
[raw CAN-FD callback](#raw-can-fd-callback-2026-09-03).

## Test environment

Status: live-car availability investigation on 2026-07-24, with the earlier
vehicle-event probe results from 2026-06-27 retained where relevant, a shell-UID
`autoservice` FID read on 2026-08-22, and the former head-unit vehicle panels
wired to that allowlist the same day (built and unit-tested then, reviewed on
the car on 2026-08-23). The cluster dashboard first ran on the car on
2026-08-25. Product wiring was checked again on 2026-08-27: the head-unit panels
are deleted, and the cluster dashboard is the only active UI consumer of the
telemetry backend. A second car, a Denza N9, was attached on 2026-08-30, and the
vehicle-id check that tells it apart from the Z9GT is recorded here. On
2026-09-03, passive raw CAN-FD callbacks were also confirmed from an
owner-controlled local ADB shell on the Denza Z9. Targeted shell-UID turn-signal
events were live-proven on 2026-09-04. They feed bounded diagnostics and an
early-teardown guard for an active Mirrors camera; the stock AVC window is the
only camera-eligibility authority, and the listener can never open a camera.

> **Superseded 2026-09-18:** the cluster is not the only consumer of the telemetry backend: the strip's car page claims it since 2026-09-05 and an always-on ledger claim since 2026-09-18 (`VehicleWatcher`) — see [Vehicle telemetry wiring](#vehicle-telemetry-wiring-2026-08-22-to-2026-08-27).

> **Superseded 2026-09-23:** the stock AVC window is not the only camera authority any more: Show needs AVC's card and the lamps (`0x38A0002C`) on that side, and the lamps leaving close the camera — see [the firmware-model contract](instrument-display-findings.md#the-firmware-model-contract-2026-09-23).

| Item | Value |
| --- | --- |
| Vehicle | Denza Z9GT (everything before 2026-08-30); Denza N9 from 2026-08-30 |
| Head unit | DiLink 5.1 |
| Android | 13 |
| Build fingerprint | `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20251214.220229:user/release-keys` |
| ADB target | `127.0.0.1:5555` |
| Product package | `dev.denza.apps` |
| Temporary probe package | `dev.denza.tools.vehicledatareadprobe` |
| Probe identity | normal app UID; no BYD/system permissions |

> **Superseded 2026-09-03:** "Denza N9 from 2026-08-30" does not mean every later reading is the N9's: later sections name their car, and the raw CAN-FD (2026-09-03) and washer (2026-09-18) runs were on the Z9GT (`AutoType=170`) with firmware `eng.build20260705.011226`, not the `eng.build20251214` above — see [Raw CAN-FD callback](#raw-can-fd-callback-2026-09-03).

The temporary probe was uninstalled after the 2026-07-24 run. The 2026-08-22
`autoservice` reads used only `service list` and `service call`; Denza Apps was
not modified. No `com.byd.avc` crash was observed.

## Which car is this (2026-08-30)

Update 2026-10-18: the Z9GT now also directly answers `persist.sys.AutoType=170`;
the earlier FID-only qualification below is historical. This does not qualify
all the other vehicle-id properties.

Every measurement on this page before 2026-08-30 was taken on a Denza Z9GT. A
Denza N9 was attached over local ADB on 2026-08-30. The two cars run the same
DiLink 5.1 build and are not distinguishable by any standard Android identity,
so a reliable check has to use the vendor's own vehicle id.

That id is `AutoType` in BYD code, carried on the wire as FID
`BODYWORK_AUTO_TYPE` (`0x40D00010`, hence the `40d` in one property name):

| Car | vehicle id |
| --- | --- |
| Denza N9 | `168` |
| Denza Z9 / Z9GT | `170`, `171`, `211`, `212` |

`DiCarServer` maps the id to a model name through its own asset table,
`/system/priv-app/DiCarServer/DiCarServer.apk!assets/VehicleCarType.json` —
100 ids over 21 names, the same names that `com.byd.car.VehicleCarType` holds as
constants. A second table in the same APK, `assets/vehicleType.json`, derives the
body class from the same id: `168` is `SUV`, all four Z9 ids are `CAR`. Neither
table separates Z9 from Z9GT — our Z9GT reports `170`, and `171` / `211` / `212`
have never been observed.

### Properties measured on the N9

All six are `u:object_r:system_prop:s0` and read from an app domain (checked
through `run-as dev.denza.apps`):

| Property | N9 value |
| --- | --- |
| `persist.sys.AutoType` | `168` |
| `persist.sys.car.type` | `168` |
| `persist.sys.vehicle_40d_code` | `168` |
| `persist.sys.model_variant.model` | `n9` |
| `persist.sys.byd.bluetooth_name` | `腾势N9` |
| `persist.sys.byd.default_name` | `腾势N9` |

`CarInfoServiceImpl.updateCarModel()` is what produces `model_variant.model`: it
reads the id, looks it up in `VehicleCarType.json`, and stores the name
lower-cased.

### What does not discriminate

Identical on both cars, so `Build.MODEL` and the rest of the standard Android
identity say nothing about which car the code is running in:

| Property | Value on both |
| --- | --- |
| `ro.product.model` | `DiLink5.1` |
| `ro.product.device`, `ro.product.name` | `IVI` |
| `ro.product.brand` | `BYD-AUTO` |
| `ro.vehicle.type` | `DiLink150_7.0UI` |
| `ro.build.car.series` | `denza` |

### Evidence, and the gap in it

The N9 values above are a direct read on 2026-08-30. For the Z9GT the only
archived evidence is the live FID value, logged three times across two months:

- `captures/drag-after-copycurrent.log:6659` — `getAutoType type is: 170` (06-28)
- `captures/turn-signal/turnsignal-20260626-154308.log:1702` — `获取车型ID:170` (06-26)
- `captures/split-live-acceptance/evidence/diag-v16/full-logcat.txt:20734` —
  `getAutoType type is: 170` (08-23)

The Z9GT *properties* were never captured. That `persist.sys.model_variant.model`
reads `z9` there follows from the vendor code path, not from a measurement.
Code that tells the cars apart should therefore key on the numeric id against the
table above, and treat an unrecognised id as unknown rather than as "the other
car". A full `getprop` dump from a Z9GT is the missing artefact.

One reliability caveat: `CarInfoServiceImpl.updateVehicleId()` compares the
stored property against the live FID and, on a mismatch, writes the **old** value
back instead of the new one. The property is `persist.` and survives a data wipe,
so a head unit moved between cars could keep a stale id. The live FID is
authoritative but reachable only from shell — see
[autoservice FID protocol](#autoservice-fid-protocol).

### Reading it

From shell or a host script:

```bash
adb shell "getprop persist.sys.AutoType; getprop persist.sys.model_variant.model; getprop persist.sys.byd.default_name"
```

From a normal APK, the way `RescueRunner.systemProperties()` already does it: run
`/system/bin/getprop` through `ProcessBuilder` and parse the `[name]: [value]`
lines. Reflection into `android.os.SystemProperties` is neither needed nor
allowed under hidden-API restrictions.

## Evidence labels

- **Confirmed normal app** — available through a standard Android API to a
  normal app identity, or already used by a normal third-party app on this head
  unit.
- **Advertised; probe next** — the system reports no permission requirement, but
  Denza Apps has not yet registered and interpreted the signal itself.
- **Readable but unqualified** — the transport is open, but meaning, units,
  cadence, or stability are not established.
- **Blocked** — a normal app reached the API and received a permission or
  capability failure.
- **Shell/system only** — useful for diagnosis, not a product data source.

## Availability and frequency matrix

| Source | Data | Frequency observed or advertised | Permission | Evidence | Product status |
| --- | --- | --- | --- | --- | --- |
| Android `TYPE_ACCELEROMETER` (`TDK icm42670`) | 3-axis acceleration including gravity | 15–100 Hz advertised; stock clients observed at 15 and 100 Hz | none | Confirmed normal app ecosystem | Usable after car-axis calibration |
| Android `TYPE_GYROSCOPE` (`TDK icm42670`) | 3-axis angular velocity | 15–100 Hz advertised; stock normal app observed at 100 Hz | none | Confirmed normal app ecosystem | Usable |
| Android virtual sensors | gravity, linear acceleration, game rotation vector | up to 100 Hz advertised | none | Confirmed Android surface | Usable; validate orientation and drift |
| Vendor `icm4n607-a-iner`, type `65613` | vendor inertial acceleration payload | 12.5–400 Hz advertised; stock AutoSDK observed at 12.5–15 Hz | none advertised | Advertised; probe next | Promising, but payload and normal-APK registration are unverified |
| Vendor `icm4n607-g-iner`, type `65614` | vendor inertial gyroscope payload | 12.5–400 Hz advertised; stock AutoSDK observed at 15 Hz | none advertised | Advertised; probe next | Promising, but payload and normal-APK registration are unverified |
| `als-can` light sensor | ambient/CAN light level | on-change, up to 100 Hz advertised | none advertised | Advertised; probe next | Do not use until values and meaning are verified |
| Android GNSS provider | position, speed, bearing, altitude, horizontal/vertical/speed/bearing accuracy | approximately 1 Hz observed on the car | standard runtime location permission | Confirmed normal app | Usable |
| GNSS status/extras | satellite and signal-quality information | event-driven with GNSS fixes/status | standard runtime location permission | Confirmed Android surface | Optional diagnostics, not a primary dashboard metric |
| `com.byd.carStatusProvider` | maintenance fields, issue fields, raw fuel/electric trip arrays | update cadence unknown; provider supports change notifications | provider exported with no read permission | Readable but unqualified | Query/observe only; do not label raw arrays as consumption or regeneration |
| Yandex guidance through Denza Apps accessibility | maneuver, next road, remaining route distance/time, optional road text | event-driven; only while validated guidance is visible and fresh | enabled Denza Apps accessibility service | Existing product path | Usable with the current fail-closed/staleness rules |
| High-level DiCar Binder APIs | battery, energy flow, range, charging, pedals, steering, tires, air quality | getter surface exists; useful calls blocked | signature/privileged BYD permissions | Blocked | Not a product source from app UID |
| `autoservice` (`android.gui.BYDAutoServer`) | SOC, SoH, pack temps, cell mV, 12V, HV, charge, motors, tyres, climate, PM2.5 | on-demand `service call` from shell | shell UID via local ADB; app UID blocked | Shell/system only | Poll a short allowlist through `DenzaLocalAdb`; never from the app process |
| Interior ambient light colour (`SET_IAL_FRONT_COLOR` and the palette type, dev `1023`) | palette index 1..127 plus the palette type; RGB only on type `7` | changes only when the driver picks a colour; DiCar pushes changes to listeners | shell UID via `autoservice`; DiCar change listener has no permission check but is untried from app UID | Corpus-read; one live snapshot from a stock log | Not yet a product source; see [Interior ambient light colour](#interior-ambient-light-colour-2026-09-23-in-progress) |
| Vehicle-id system properties (`persist.sys.AutoType` and mirrors) | which car the head unit is in | static; written at boot | none | Confirmed normal app | Usable; see [Which car is this](#which-car-is-this-2026-08-30) |
| Raw CAN-FD callback | frames selected by stock `CanDataCollect`; observed on channels 0, 1, and 2 | 2,054 callbacks in 15 s (about 136.9/s) | local ADB shell or system identity; normal-app access unproven | Shell/system only | Confirmed passive diagnostic stream through `0x99000020` |
| BYDAuto events/system logs | speed logs, bodywork/settings/safety-belt/PM2.5 events, other CAN-derived events | speed log about 1 Hz; other events vary; some logs are high-rate | system log access / protected BYD permissions | Shell/system only | Diagnostics only |

The current Denza Apps trip/spectrum panel reads standard GNSS at approximately
`1 Hz` and reuses the validated Yandex guidance runtime. Its earlier `30 Hz`
gravity/gyroscope/accelerometer pipeline fed only a permanently hidden
presentation and was removed from product code on 2026-08-26. The sensor
findings below remain useful research evidence, not current product wiring.

## Android inertial sensors

`dumpsys sensorservice` reported these relevant sensors without a sensor
permission:

| Sensor | Android type | Mode | Reported rate |
| --- | --- | --- | --- |
| `TDK icm42670` | `android.sensor.accelerometer` (`1`) | continuous | 15–100 Hz |
| `TDK icm42670` | `android.sensor.gyroscope` (`4`) | continuous | 15–100 Hz |
| Game Rotation Vector | `android.sensor.game_rotation_vector` (`15`) | continuous | up to 100 Hz |
| Gravity | `android.sensor.gravity` (`9`) | continuous | up to 100 Hz |
| Linear Acceleration | `android.sensor.linear_acceleration` (`10`) | continuous | up to 100 Hz |
| `icm4n607-a-iner` | vendor `android.sensor.accelerometer_scp` (`65613`) | continuous | 12.5–400 Hz |
| `icm4n607-g-iner` | vendor `android.sensor.gyroscope_scp` (`65614`) | continuous | 12.5–400 Hz |
| `als-can` | `android.sensor.light` (`5`) | on-change | up to 100 Hz |

The stock Yandex process, running as a normal app UID, has registered standard
accelerometer, gyroscope, and game-rotation sensors. This is direct evidence
that the standard IMU path is not limited to privileged packages.

Stationary samples showed a fixed head-unit mounting orientation rather than
ready-to-use vehicle axes. Product code must calibrate gravity and map sensor
axes to longitudinal, lateral, and vertical vehicle motion before interpreting
events.

The vendor SCP sensors expose vendor-specific payloads and were active in the
stock AutoSDK process. Their manifest-level sensor permission is `n/a`, but
normal Denza Apps registration and payload interpretation remain untested.
Start with standard sensors; treat the vendor sensors as a later accuracy
experiment.

Temperature sensors were also present, but they appear to be IMU/chip
temperature sensors. They are not evidence of cabin or outside temperature and
must not be shown as such.

## GNSS

The Android GPS provider is active and reports:

- latitude/longitude;
- speed;
- bearing;
- altitude;
- horizontal, vertical, speed, and bearing accuracy;
- satellite/signal metadata.

Stock location consumers request approximately one-second updates. A normal app
can use the same provider after the standard location permission flow.

Useful derived journey values include:

- the travelled trace and distance;
- elevation profile and accumulated climb/descent;
- moving and stopped segments;
- direction and turn geometry;
- local sunrise/sunset calculations from time and position;
- fusion with IMU samples for a road-thread or body-motion visualization.

Current speed is useful internally for segmentation and distance checks, but it
need not be displayed because the cluster, HUD, and navigator already show it.

## Exported car-status provider

The system package `com.byd.providers.carstatus` declares:

```text
content://com.byd.carStatusProvider/car_status
content://com.byd.carStatusProvider/dicare_record
```

Reverse inspection of `CarStatusProvider.apk` showed:

- `android:exported="true"`;
- no provider read or write permission;
- SQLite-backed `query`, `insert`, `update`, and `delete`;
- `notifyChange(...)` after updates.

Only read/query and observation are acceptable for research. Denza Apps must
never write to this stock database.

Rows observed in `car_status` included:

- `travel_points_fuel`;
- `travel_points_elec`;
- the `_one` and `_two` variants of those arrays;
- `car_status_issue` and `car_status_issue_num`;
- maintenance time, total-mileage, and HEV-mileage fields;
- maintenance reminder/switch fields.

The raw electric series contains both positive and negative integers, but that
does not prove units, sign convention, sample interval, power, consumption, or
regeneration. The series names say “travel points,” not “power.” Do not build an
energy or regeneration UI from these values until the producer and units are
identified and a moving capture is correlated with known vehicle behavior.

`dicare_record` returned no rows in this test.

## High-level DiCar service

Reverse inspection of:

```text
/system/priv-app/DiCarServer/DiCarServer.apk
/system/priv-app/BydClusterApp/BydClusterApp.apk
```

found exported Binder-provider infrastructure and high-level service interfaces.
A temporary normal-UID APK successfully obtained the Binder through:

```text
content://com.byd.car.server.provider.CarServiceProvider
```

Transport access did not imply data access. Representative getter results:

| Getter/data family | Result |
| --- | --- |
| battery level | `20004`, missing `android.permission.BYDAUTO_STATISTIC_GET` |
| energy flow | `20004`, missing `android.permission.BYDAUTO_ENERGY_GET` |
| pure-electric range | `20004`, missing `android.permission.BYDAUTO_SETTING_GET` |
| stationary charging power/SOC/state | `20004`, missing `android.permission.BYDAUTO_CHARGING_GET` |
| accelerator/brake position | `20004`, missing `android.permission.BYDAUTO_SPEED_GET` |
| steering angle/motor torque | `20004`, missing `android.permission.BYDAUTO_SETTING_GET` |
| heading/tire values | `20004`, missing `android.permission.BYDAUTO_INSTRUMENT_GET` |
| PM2.5 | `20004`, missing `android.permission.BYDAUTO_PM2P5_GET` |
| CO2/air quality | `20004`, missing `android.permission.BYDAUTO_AC_GET` |
| pitch/roll | `10004`, CAN signal unsupported on this device/API path |
| mileage families | failed to get mileage |
| current trip info | `20001`, invalid property key format |
| energy-consumption API version/type | success with value `1`; capability metadata only |

The conclusion for a **normal app UID** is still fail-closed: do not add
`BYDAUTO_*` permissions to the product manifest, and do not call these
high-level getters from `dev.denza.apps` process identity.

The 2026-07-24 probe also returned `20001` invalid property key format for
trip info. The keys it needed were not names like `battery_level`; they are
the hex CAN feature IDs from the DiLink catalog (`com.byd.feature.*`), for
example `"0x44700038"`. That catalog gap, plus never calling the native
`autoservice` Binder from shell, is why BMS/cell/12V looked unavailable.

## autoservice FID protocol

Live-verified 2026-08-22 on this DiLink 5.1 IVI over `adb -s 127.0.0.1:5555`.
The car was parked and on AC charge (gun=2, ~2.4 kW, SOC rose 42→43 % during
the session). Read-only `service call` only: no APK install, no `app_process`,
no writes, `dumpsys autoservice` returns empty.

**Result: working** for shell UID. **Blocked** for a normal app UID. Same
Binder third-party dashboards use (an `AutoserviceClient` client, a local-ADB
layer, a privileged `app_process` proxy). Such a proxy's `app_process` +
`BydContextWrapper.checkPermission()==0` is unnecessary for reads: a one-shot
`service call` from shell is enough.

### Binder

```text
autoservice: [android.gui.BYDAutoServer]
```

| Transact | Meaning | Do not use |
| --- | --- | --- |
| `5` | `getInt(dev, fid)` | |
| `7` | `getFloat(dev, fid)` — IEEE-754 bits in the parcel int | |
| `6` | `setInt(dev, fid, value, binder)` — pass a `null` binder | speaker-cover SET is documented in [speaker-lift-findings.md](speaker-lift-findings.md); do not probe other write FIDs |
| `10` / `12` (and similar array/float methods) | | **Do not** call with `i32`-only parcels. Wrong arity SIGSEGV’d `/system/bin/autoservice` on 2026-08-22 (recovered; not `com.byd.avc`). |

Device types from `android.hardware.bydauto.BYDAutoConstants` (MapHelper stub
JAR; live `dev` values match):

| Family | `dev` | Used for |
| --- | --- | --- |
| AC | `1000` | cabin/out temps, CO2 |
| bodywork | `1001` | 12V, cell count, doors, hood |
| audio | `1002` | speaker flip / RLSA / amp config — [speaker-lift-findings.md](speaker-lift-findings.md) |
| power | `1005` | LV / HV state flags |
| energy | `1006` | 12V twin, 50 km split |
| instrument | `1007` | tyre temps, some trip figures |
| PM2.5 | `1008` | in/out µg/m³ |
| charging | `1009` | pack V, current, gun, charge power |
| gearbox | `1011` | EPB, park switch |
| engine | `1012` | pack power kW, motor rpm |
| speed | `1013` | speed / pedal (parked = 0) |
| statistic / BMS | `1014` | SOC, SoH, cells, pack temp, odo |
| tyre | `1016` | pressures |
| OTA | `1032` | 12V alias |
| GB | `1039` | motor temps, bus V, insulation |
| sensor | `1043` | slope, windshield humidity |

Hex feature IDs (`fid`) come from the MapHelper-bundled catalog
`reverse/maphelper-jadx/sources/com/byd/feature/{statistics,energy,gb,charging,…}`.
The catalog is ~8000 constants; most are SET/CONFIG/FAULT. Motor temp IDs are
generation-specific (`GB_FRONT_MOTOR_TEMP` vs `_DM40` vs `_DM40_464`); this
car answers on the `_DM40_464` set.

**The full resolved table (2026-09-23, local and untracked).** The framework's
`BYDAutoFeatureIds` sets many values in its static initializer as
`isCanFD ? A : B`, which jadx does not evaluate (it overflows on `Setting`), so
ids kept being looked up by hand. `captures/ambient-light-20260923/data/resolve_clinit.py`
interprets the jadx fallback-mode `<clinit>` of the 2026-09-23 OTA framework for a
fixed branch and wrote `fids-canfd.tsv` (`isCanFD=1`, this car's
`sys.car.protocol=CANFD`) and `fids-can-classic.tsv` (`isCanFD=0`) beside it:
10,348 lines each, tab-separated `<nested class or TOP>`, `<NAME>`,
`<value as signed decimal>`. Search the decimal the shell takes
(`rg -w 1138753546 fids-canfd.tsv`) or the name. Its cross-check against the
2,961 ternaries jadx did render found no mismatch
(`captures/ambient-light-20260923/reports/framework-fids.md`, section 0), and the
product's ids resolve to their catalog names in it (`0x14400020`
`ENGINE_POWER`, `0x94400008` `SPEED_AUTO_SPEED`, `0x05500030`
`GEARBOX_PARK_BRAKE_SWITCH`).

### How to read

```bash
# getInt — pass fid as a signed 32-bit decimal (high bit set → negative)
adb -s 127.0.0.1:5555 shell service call autoservice 5 i32 <dev> i32 <fid>
# getFloat
adb -s 127.0.0.1:5555 shell service call autoservice 7 i32 <dev> i32 <fid>
```

Stdout: `Result: Parcel(00000000 XXXXXXXX   '....')`. The second 32-bit word
is the payload.

```python
import struct
word = int("XXXXXXXX", 16)
sint = word - 2**32 if word >= 2**31 else word
flt  = struct.unpack(">f", bytes.fromhex("XXXXXXXX"))[0]
```

From Denza Apps, the same command goes through the existing local-ADB client
(`DenzaLocalAdb.client(context).shell(...)`), not through
`BYDAutoStatisticDevice.get()` in the app process. Do not add `BYDAUTO_*`
permissions to the product manifest. Do not spawn a long-lived `app_process`
proxy.

If transact 5 returns −10013, retry 7 (and vice versa).

### Sentinels — do not display

| Parcel / value | Meaning |
| --- | --- |
| `0xffffd8e3` (−10013) | wrong transact or direction |
| `0xffffd8e5` (−10011) | no data / not on this generation |
| `0xbf800000` (−1.0f) | invalid float |
| `0xffffffd8` (−40) | often “no sensor” (also a real −40 °C offset-zero) |
| `255`, `4095`, `1023`, `205`, `40.95`, `6153.5` | max-range placeholders, common on V2L/VTOV |

Cell delta has no FID: compute max − min locally.

### Scales proven or likely on this car

| Rule | Evidence |
| --- | --- |
| Pack temp °C = int − 40 | avg raw 68 → 28 °C vs third-party 29.0 °C |
| Motor / IPM / tyre / climate °C = int as-is | front motor 31 matched the screenshot card |
| Cell voltage V = int / 1000 | 3313 → 3.313 V |
| 12V = float volts | `0x415ccccd` → 13.80 |
| SOC % = float (also int twin) | 43.0 |
| Odometer km = int / 10 | 118927 → 11892.7 km (a third-party dashboard's convention, plausible) |
| ~~Remaining energy kWh = int / 10~~ **falsified 2026-08-22** | `0x44700028` is the BMS state of charge in tenths of a percent: `432` against a 43 % display, `616` against a 62 % display. The "43.2 / 0.43 ≈ 100 kWh pack" inference was circular — a state of charge divided by itself always lands near 100. The owner puts this pack under 40 kWh, and the stock home widget reports 23.2 kWh/100 km over 50 km, which fits a ~38 kWh plug-in hybrid, not a 100 kWh EV |
| Tyre pressure bar = int / 100 | 287 → 2.87 bar |
| Charge gun 2 = AC connected | a third-party dashboard's table; SOC rose during the session |
| Pack V cross-check | 166 cells × 3.315 V ≈ 550 V = `CHARGING_CHARGE_BATTERY_VOLT` |

Unproven — do not label in a UI until a moving capture: `STATISTIC_INSTANTANEOUS_CURRENT`
(raw 35721), available-power 72, LV-side current 28 vs 2.8 A, driving-time 56.3
hours vs minutes, max chg/dchg 759/3641.

## Widget allowlist (2026-08-22)

Filtered scan: 1156 telemetry-named FIDs (skipped SET/CONFIG/FAULT and
ADAS/settings/audio/lights) → 676 non-sentinel parcels. Most are door-actuator
flags or placeholders. A product widget polls only the rows below.

| Group | Signal | FID | dev | tx | Session value | Decode |
| --- | --- | --- | --- | --- | --- | --- |
| BMS | SOC | `0x4A505038` | 1014 | 7 | 43 | % |
| BMS | SoH | `0x44400028` | 1014 | 5 | 99 | %; removed from regular instruments on 2026-09-04; restored to diagnostics only on 2026-10-18 |
| BMS | Pack temp avg/min/max | `0x44700038` / `0x44700010` / `0x44700020` | 1014 | 5 | 68 / 67 / 69 | °C = raw − 40 |
| BMS | Cell min/max | `0x44600010` / `0x44600030` | 1014 | 5 | 3313 / 3317 | mV; Δ local |
| BMS | Series cell count | `0x43A00008` | 1001 | 5 | 166 | cells |
| BMS | BMS state of charge | `0x44700028` | 1014 | 5 | 432 / 616 | percent ×10, **not** energy — see the scales table |
| HV | Pack voltage | `0x44400008` | 1009 | 5 | 550 | V |
| HV | Front/rear bus | `0x46407020` / `0x46407010` | 1039 | 5 | 551 / 551 | V |
| HV | Charge power | `0x32300018` | 1009 | 7 | 2.4 | kW |
| HV | Charge current | `0x44400018` | 1009 | 7 | −4.4 | A; sign not proven |
| HV | Engine/pack power | `0x14400020` | 1012 | 5 | −2 | kW |
| HV | Insulation | `0x43A00018` | 1039 | 5 | 13051 | unit unproved; diagnostics retain **raw**. The FID is `GB_BMC_INSULATION_VALUE`; the stock collector clamps its low16 word to60000, without checking getter status. This does not establish kΩ or validity — see [Unattended qualification](#unattended-child-and-buffer-qualification-2026-10-21) |
| 12V | Voltage | `0x43400028` | 1001 | 7 | 13.8 | V (energy twin `0x36D00020` / 1006) |
| Range | Remaining EV range | `0x4A50203E` | 1014 | 5 | 67 | km |
| Trip | Odometer | `0x4A502010` | 1014 | 5 | 118927 | km ×10 |
| Trip | Lifetime kWh / kWh/100 | `0x3D906030` / `0x4A501030` | 1014 | 7 | 997.9 / 6.5 | |
| Trip | Last 50 km equivalent | `0x4A507032` | 1014 | 7 | 6.6 | **not kWh/100 km** — the stock home widget showed 23.2 kWh/100 km for the same 50 km window on 2026-08-22 |
| Trip | 50 km split drive/AC/aux | `0x35903831` / `0x35903838` / `0x35903841` | 1006 | 5 | 91 / 4 / 5 | % |
| Charge | Gun | `0x34400032` | 1009 | 5 | 2 | 2 = AC connected — **and not the only value it takes**, see below |
| Charge | SOC on charger | `0x32300010` | 1009 | 5 | 43 | % |
| Charge | Time remaining h/min | `0x32300028` / `0x32300030` | 1009 | 5 | 9 / 17 | |
| Motor | Front temp / IPM | `0x46406018` / `0x46406010` | 1039 | 5 | 31 / 26 | °C raw |
| Motor | Rear L/R temp | `0x285001A8` / `0x285001B0` | 1039 | 5 | 29 / 31 | °C raw; not the third-party 40 °C card |
| Motor | rpm front/rear | `0x44100008` / `0x25100008` | 1012 | 5 | 0 / 0 | |
| Tyre | Pressure LF/RF/LR/RR | `0x99000124` / `28` / `2c` / `30` | 1016 | 5 | 287 / 287 / 285 / 287 | kPa → bar /100 |
| Tyre | Temp LF/RF/LR/RR | `0x4A50A018` / `24` / `30` / `3c` | 1007 | 5 | 25 / 24 / 23 / 23 | °C raw |
| Climate | Inside / filtered / out | `0x3D800030` / `0x4EB06010` / `0x40400038` | 1000 | 5 | 23 / 26 / 21 | °C |
| Climate | Main / passenger set | `0x40400028` / `0x40400030` | 1000 | 5 | 23 / 24 | °C |
| Air | PM2.5 in / out | `0x4F600010` / `0x4F60001C` | 1008 | 5 | 9 / 19 | µg/m³ |
| Air | CO2 outside | `0x35B00018` | 1000 | 5 | 300 | ppm-ish |
| Cabin | Windshield humidity / surface | `0x3B400008` / `0x3B400010` | 1043 | 7 | 19.5 / 28 | % / °C |
| Cabin | Slope | `0x2230002C` | 1043 | 5 | −4 | deg-ish |
| Body | EPB / park switch | `0x21800011` / `0x05500030` | 1011 | 5 | 3 / 1 | parked |

On 2026-08-28 a stationary, brake-held `P -> D -> P` check read each state five times. The park
switch was `1 -> 0 -> 1`, EPB was `3 -> 1 -> 3`, and vehicle speed remained `0` throughout. Denza
Apps uses only the proven park-switch fact to end and suppress its GNSS-derived trip while in P;
the readings do not claim values for N or R.

The cluster reads the same id in its own hot batch, and **two things read it
there**: `TripEnergyLedger`, which is bounded by it — a trip runs from the first
movement after P to the next one — and the panel itself through
`VehicleTelemetry.parked`, which is what puts a third cell on the right shelf and
a decimal place on the petal's figure while the car is standing.

> **Superseded 2026-10-09:** the hub's sweep is the only reader of this id. The strip's trip clock polled it once a second over a shell session of its own (`TripParkSignal`, `TripParkStateSource`, both deleted) beside a hub that read it anyway; it now takes `VehicleTelemetry.parked` from that sweep through `TripParkFeed`, under the claim `VehicleWatcher.TRIP` — see [Vehicle telemetry wiring](#vehicle-telemetry-wiring-2026-08-22-to-2026-08-27).

Open: no FID on this firmware reproduced the third-party “rear motor 40 °C”
card; `STATISTIC_INSTANTANEOUS_CURRENT` scale unknown. Next action: one
moving-drive capture of current, pack power, and rear-motor FIDs, then stop
scanning the catalog.

## Vehicle telemetry wiring (2026-08-22 to 2026-08-27)

The `feature.vehicle` backend originally fed the second and third pages of the
head-unit's swipeable bottom panel. `BottomPanelPager`, `VehiclePanelView`, and
`EnginePanelView` were retired and deleted on 2026-08-27. The table below
preserves the decisions behind that live-measured implementation; names of
deleted views and page-specific gates are historical, not current entry points.

| Historical decision | Former location | Why |
| --- | --- | --- |
| Read transacts only (`5`, `7`) | `VehicleSignals.kt` | `setInt` (`6`) must never appear in a product package; a unit test asserts the built command never contains it |
| Shell identity, PASSIVE policy | `VehicleTelemetryHub` via `DenzaLocalAdb` | Proves existing trust, never enqueues an authorization prompt; an untrusted key leaves the page empty with the reason |
| One batched command per sweep | `AutoserviceShell.command` | Every id would otherwise be a separate ADB round trip; the current cluster retains the same batching rule |
| `echo @@<index>` before each call | `AutoserviceShell.parse` | A feature id that prints nothing on this generation cannot shift the following answers onto the wrong signals |
| Plausibility gate per unit | `VehicleKind.accepts` | Sentinels and max-range placeholders are dropped by what the unit can physically be, not by a blacklist — `255` stays a legal 2.55 bar and an illegal 215 °C |
| Hot values never carried over | `VehicleTelemetryHub` | A stale kilowatt figure is worse than a dash. Cold values are reused across *hot* sweeps, but **a cold sweep rebuilds the cold map from what that sweep answered** (`VehicleColdSweep.rebuild`), so a temperature that stopped answering leaves the snapshot the same way a power reading does. That is what lets the cluster hold one staleness rule over both cadences — with a horizon sized to each: two seconds hot, twenty-five cold, which is two of the ten-second cold intervals with room for the second to be late |
| Odometer, not GNSS, for distance | `ConsumptionLog` | The page needs no location permission and keeps its histogram with the trip page closed |
| Pack-power sign in one constant | `VehicleConvention` | The convention is inferred from the parked charging session, not proven; the drive capture flips one line if it is wrong |
| No shell until the page is opened | `VehiclePanelView.syncHub` | A session that never swipes to the page costs the car nothing |
| One big figure per block | `VehiclePanelRenderer` | Chosen for movement, not abstract importance: charge, traction voltage, the hottest drivetrain reading, consumption. A number that never moves is a supporting line whose colour, not size, raises a hand |
| Live consumption is a rolling window, not the open bar | `ConsumptionLog` | kWh per 100 km has no value at zero speed; folding standstill energy into it made a parked car's reading crawl. The window stops at a standstill, the bars keep the energy |
| Two type scales, never one | `VehiclePanelRenderer` | A virtual unit is about 0.6 dp at full width and exactly 1 dp in the narrow pane, so a shared constant renders at two different sizes |
| No block headings in the narrow pane | `VehiclePanelRenderer.drawNarrow` | The hairlines already separate the blocks, and the four headings were what pushed the consumption chart off the bottom of the pane |
| Combustion signals poll only on the engine page | `VehicleSignal.engineOnly`, `VehicleTelemetryHub.setEngineActive` | The engine set is 21 of 44 signals and appears on no other page. Measured on the car: a full sweep costs 266–315 ms without it and 468–587 ms with it, so the electrical page would have paid double for lamps nobody is looking at |
| One lamp folded from several feature ids | `EngineLamp` (deleted 2026-09-04) | Four ids report low oil pressure and four report low coolant level; they are generation variants, and reading all of them is cheaper than betting on one |
| A lamp that never answered is not "healthy" | `LampState.UNKNOWN` (deleted 2026-09-04) | Every lamp read `0` on a healthy car, which proves they are readable, not that they light. A hollow dot makes a weaker claim than a green one |

> **Superseded 2026-09-22:** the pack-power sign is proven, not inferred: accelerating in motion read +10…61 kW and decelerating mostly negative, so `POWER_POSITIVE_IS_DISCHARGE = true` holds — see [energy contract §8](energy-display-contract.md#8-open-and-what-closes-each).

There are **two** product consumers since 2026-09-05, and the hub polls while
either is up: `feature.cluster.dashboard`, whose view claims it as long as the
driver's display shows our panel, and the head unit's trip strip, whose view
claims it only while its second page is the one on screen
(`VehicleWatcher.CLUSTER` and `VehicleWatcher.STRIP`). The claim was a single
boolean until then, which two consumers cannot share: whichever went away last
would have stopped the poll under the one still drawing. A newly arriving
consumer sets `forceCold`, so a page just swiped to arrives with its
temperatures rather than dashing them for ten seconds. There are still no
page-dependent signal filters: while polling, the hub reads the same seven hot
and thirty cold signals for whoever is watching. The consumption history is
always rendered over the latest **10 km**; no saved head-unit selector is
consulted.

> **Superseded 2026-09-18:** there are three watchers, not two: `VehicleWatcher.LEDGER` is claimed at application start and never released, so the hub always polls - every 100 ms while the cluster or the strip is on screen, every 1 s otherwise (`VehicleSweepCadence`). It reads 8 hot signals (`VEHICLE_SPEED` joined on 2026-09-18) and 12 cold ones, not seven and thirty (`VehicleSignals.kt`) — see [energy contract §2.7](energy-display-contract.md#27-the-road-is-recorded-whether-or-not-anyone-looks).

> **Superseded 2026-10-09:** there are four watchers. `VehicleWatcher.TRIP` is held while the strip runs, on either page, because its trip clock reads the park switch from this sweep instead of a shell of its own. It is not a screen: it leaves the cadence at the ledger's second, and unlike a screen it is not owed a cold sweep when it appears (`VehicleSweepCadence.coldAtOnce`); like any new claim it ends a backoff it finds — see [energy contract §2.7](energy-display-contract.md#27-the-road-is-recorded-whether-or-not-anyone-looks).

Both screens draw one chart from that window and no extra signals:
`ConsumptionChart` bins the closed buckets the hub already keeps into twenty
steps of 500 m, anchored to the odometer's own half kilometre. The strip's own
two-minute `PowerTrace` was deleted with it - it was a second history of the
quantity the page's headline already prints, and the reason the two screens'
graphs could not be compared (`docs/energy-display-contract.md`, §2.3).

> **Superseded 2026-09-18:** the chart is no longer twenty 500 m steps on the odometer's grid: it is up to 100 points, one per recorded 100 m bucket, each the mean over the last kilometre of readings (`ConsumptionChart.POINTS`, `SMOOTH_STEPS`) — see [energy contract §2.3](energy-display-contract.md#23-the-history-behind-that-figure).

Unit tests cover the command shape, the marker alignment, the proven scales, the
sentinel and plausibility rules, and the consumption accumulator including the
standstill rule.

### What the historical head-unit panel did not show, and why

Removed after the owner reviewed it on the car on 2026-08-23:

- **tyre pressures and temperatures.** The car has a native tyre-pressure
  display; the panel was repeating it. Eight feature ids left the allowlist, and
  with them the only ones past `0x7fffffff` — the signed-decimal rule still has
  a test, now over the whole allowlist rather than one tyre id.
- **cabin and outside temperature.** Both are already on the instrument cluster.
- **remaining range, BMS state of charge, and pack health in the narrow pane.**
  At that time they stayed in the full-width page. Range and duplicate BMS state
  later left the allowlist with the retired pages; pack health remains on the
  cluster because the stock display does not show it.

The final head-unit panel reported the three drive motors separately (front,
rear left, rear right) rather than as a front/rear pair, with the inverter on
its own row: this car has three motors, and one of them running away from the
others is what the row exists to show.

`CHARGE_KW` has its own plausibility gate (`-1..160 kW`) rather than sharing the
pack-power one (`±600 kW`). The wide gate let a spike through and the panel
showed a three-hundred-kilowatt charge on a car parked on a household socket.
Pack power keeps the wide gate: this car really can pull hundreds of kilowatts.

### The gun is not a charger (2026-09-06, falsified on the road)

`0x34400032` answered `2` in the parked AC session above, and the app read
"charging" as `gun >= 1` on the strength of it. On the first drive with the head
unit's second page installed, that page printed **«В БАТАРЕЮ ОТ ЗАРЯДКИ» over a
pack that was plainly discharging**: the id sits at or above 1 on the road, with
no cable anywhere near the car.

So one session with a gun in it proves what `2` means and nothing about what the
other values mean. `VehicleTelemetry.charging` now needs three things at once —
the value the catalog stands behind, the charger saying something of its own
(kilowatts or a time estimate), and a pack that is not being emptied — and
`ChargingGateTest` holds them. Both screens read that property; the cluster's
countdown hung off the same wrong gate.

Open, for the next charge: read the gun while driving and write down what it
actually says, and read it on a DC charger. If a DC session answers neither
`CHARGE_KW` nor a time estimate, this gate will miss it — which is the trade
taken deliberately, because naming the wrong scene costs the panel more than
missing one.

> **Superseded 2026-09-24:** both questions have answers. In both recorded drives the gun read `1` on every moving row (748 rows on 2026-09-22, 1,152 on 2026-09-24, `captures/vehicle-log/vehicle-20260922-172447-car.csv` and `vehicle-20260924-182412-car.csv`), and through the 2026-09-22 DC stop it read `3` while `CHARGE_KW` still said about 25 kW against a 46–57 kW charge. The gate still requires `2`, so a DC stop reads «В БАТАРЕЮ» without «ОТ ЗАРЯДКИ» — see [energy contract §8](energy-display-contract.md#8-open-and-what-closes-each).

### Measured on the car (2026-08-22, second session, parked on AC charge)

All 33 allowlist signals answered, none returned a sentinel, and none was
dropped by the plausibility gate — the catalog is correct for this firmware. The
head-unit allowlist then held 23 signals: tyres and cabin/outside climate were
removed from the panel, and polling them was the only reason to read them.

| Batch | Calls | Wall time on the head unit |
| --- | --- | --- |
| Shell baseline (two `date` calls, no reads) | 0 | 7–11 ms |
| One `service call` | 1 | 14–22 ms |
| Hot batch | 5 | 129–195 ms |
| Hot + cold batch | 33 | 270–287 ms |
| Hot batch, electrical page (2026-08-23) | 5 | 56–81 ms |
| Hot batch, engine page | 8 | 86–104 ms |
| Hot + cold, electrical page | 23 | 266–315 ms |
| Hot + cold, engine page | 44 | 468–587 ms |

The cost is almost all fixed: about 4–5 ms per additional call against roughly
130 ms of shell and first-process overhead. Batching is therefore what makes the
panel affordable, and widening the hot set is nearly free, while shortening the
interval is what actually costs the car.

That measurement set a `300 ms` hot and `10 s` cold cadence. **The hot interval
is `100 ms` since the owner asked for the live figures to answer about twice as
fast**: the cycle is then about 250 ms — the interval plus the batch itself —
which is four readings a second with the shell busy some sixty per cent of the
time, and only while the cluster dashboard is visible. Cold stayed at ten
seconds.

The hot set is **seven signals**: pack power, pack voltage, the odometer, the
park switch, engine revolutions, engine running, and generation. Every due sweep
includes the full hot or cold set the cluster needs; there is no longer an
electrical-page/engine-page split. Splitting the hot set any finer would buy
nothing: a one-call batch costs almost what a five-call batch costs.

> **Superseded 2026-09-18:** the hot set is eight signals - `VEHICLE_SPEED` (`0x94400008`) joined it for the standing-energy rule - and the hot cadence runs while the cluster or the strip's car page is on screen, with a 1 s sweep for the always-on ledger otherwise (`VehicleSignals.kt`, `VehicleSweepCadence`) — see [energy contract §2.2](energy-display-contract.md#22-consumption-over-the-last-ten-kilometres).

Two readings from the same session are worth keeping:

- the cell window moved from 3313–3317 mV at 43 % to 3352–3358 mV at 72 %, and
  the pack from 550 V to 557 V. The LFP curve is flat, not frozen; the knee
  starts showing above roughly 70 %.
- vendor range read 128 km at 72 % SOC. Against the stock home widget's
  23.2 kWh/100 km over 50 km that implies a pack in the low forties of kWh,
  which agrees with the owner's "under 40" and rules out the falsified 100 kWh
  figure. It is an estimate from two vendor-derived numbers, not a measurement,
  and the panel does not display it.

## Combustion side of the hybrid (2026-08-23)

This car is a PHEV and the catalog carries a full combustion set. Candidates
were taken from `reverse/maphelper-jadx/sources/com/byd/feature/` and then read
on the car in two read-only sweeps, **engine stopped, car parked**. Roughly half
the set answers; the interesting half does not.

### Answered on this car, engine stopped

| Reading | FID | dev | tx | Value | Note |
| --- | --- | --- | --- | --- | --- |
| Fuel level | `0x4A507040` | 1014 | 5 | `53` | percent, `STATISTIC_FUEL_PERCENTAGE` |
| Range on fuel | `0x4A504038` | 1014 / 1007 | 5 | `491` | km; consistent with 53 % of the tank |
| Engine speed | `0x14400012` | 1012 | 5 | `0` / `0x1FFF` | rpm; `_20D` `0x20D00008` and `_GB` `0x10D00008` agree. **Two different answers on a stopped engine** — see below |
| Engine running | `0x10D00038` | 1012 | 5 | `0` | stopped |
| Displacement | `0x40D00008` | 1012 | **7** | `2.0` | litres — matches this car's 2.0 T |
| Engine code | `0x40D00028` | 1012 | 5 | `26` | unknown enum |
| Catalyst heating | `0x2330002D` | 1012 | 5 | `1` | |
| Coolant level flag | `0x05500031` | 1012 | 5 | `0` | |
| Oil level | `0x05500038` | 1012 | 5 | `27` | not a 0/1 flag; scale unknown |
| Oil / transmission / blade-coolant service life | `0x4BB000D0` / `D8` / `E8` | 1001 | 5 | `155` each | identical across all three, so probably not per-item |
| High water temperature warning | `0x3D911018` | 1007 | 5 | `0` | flag only, not a temperature |
| **Vehicle speed** | `0x94400008` | 1013 | **7** | `0.0` | reaches the shell as `-1807745016` |
| Accelerator / brake position | `0x34200008` / `0x34200010` | 1013 | 5 | `0` / `0` | |

### A resting reading is not the same as a resting ECU

Both sweeps above were taken with the engine stopped, and engine speed answered
`0` in both. On 2026-08-25, with the dashboard live on the cluster and the car
parked longer, the same id answered **`0x1FFF`** — thirteen bits, all ones — and
the panel printed **8191 об/мин** under a stopped engine. `ENGINE_RUNNING` read
`0` throughout.

That is the CAN convention for "signal not available": a signal says it has
nothing by setting every bit of its own field. So the difference between the two
sweeps is not the engine, it is the engine ECU — awake it reports a real zero,
asleep the gateway hands back the invalid pattern.

The consequence for the allowlist is a general one. `8191` is inside any
plausible rpm range, so `VehicleKind.accepts` cannot catch it: a range gate
checks whether a number is *possible*, and this number is possible. Only the bit
pattern says otherwise, and the pattern is the width of that signal's field —
`0x1FFF` is nothing at all in thirteen bits and an ordinary number in sixteen.
`VehicleSignal` therefore carries an optional `invalid` word, checked in
`AutoserviceShell.decode` against the raw parcel word before any scale or offset.
`ENGINE_RPM` is the only entry that declares one so far. **Any id whose sweep
value was a resting `0` is owed the same suspicion**: a resting zero may only
mean the ECU was awake that day.

### What stood the engine box up on an electric drive (2026-09-05, superseded)

> **Superseded 2026-09-22:** the owed log exists and neither id reads above zero with the engine off in motion: over 748 moving samples the primary rpm id answered only `0x1FFF`, which the decode refuses, and its `_20D` twin, `GENERATION_KW` and `GENERATION_STATE` read `0`. The cluster stays keyed on `ENGINE_RUNNING` — see [energy contract §8](energy-display-contract.md#8-open-and-what-closes-each).

On the first drive with the Contour panel (2026-09-05) the engine's history box
stood on the cluster's right shelf for half the trip with the engine off, flat at
zero, appearing and disappearing with the car's speed. The box was up for any
second in which `ENGINE_RPM` (`0x14400012`) or `GENERATION_KW` (`0x2610001F`)
read above zero, so one of the two reads above zero while the car moves on the
battery alone - the rpm following a motor rather than the engine, or the
generation figure carrying regeneration, are both plausible and neither is
established. `ENGINE_RUNNING` (`0x10D00038`) stayed at `0` throughout, which
matches the full start/stop cycle it was read through, and the cluster is keyed
on it alone since that drive. What is still owed: one log of both ids against
`VEHICLE_SPEED` with the engine off, which says which id it is and whether the
other one means anything at all.

### Warning flags — all sixteen answer, all read `0` on a healthy car

**Correction, 2026-10-18:** the heading describes the historical sample, not a
health rule. It does not establish that `0` means healthy. The framework's
common instrument-light enum explicitly uses `0=invalid`, `1=on`, `2=off`;
the sixteen DD/oil fields below need their own qualification and must not be
OR-ed into a health verdict. Other instrument getters now returned both `1`
and `2` on the Z9GT. See [Diagnostics and DTC](#diagnostics-and-dtc-2026-10-18).

**Known and not polled since 2026-09-04: the panel has no reader for them and
there is no exception channel.** The ids and their decoding stay written down
here, so bringing one back is a decision with the work already done; they left
`VehicleSignal` and `EngineLamps.kt` went with them. Two things settled it. The
Contour draws no lamp grid — a row of dots that are green every second of every
drive is an inventory, and a driver's display shows exceptions — and the owner's
own answer was that these lamps do not work on this car. A poll with no reader is
a shell round trip on a bus the vehicle is using for itself, every ten seconds,
forever.

These are the "lamp" signals: no number, just a state the cluster would light.
Every one of them answered on device `1007` unless noted, and every one read
`0`. That proves they are *readable*, not that they *change* — until one of
them trips, `0` = healthy is the constant names' claim, not a measurement.

| Lamp | FID |
| --- | --- |
| Coolant level low | `0x3D911028`, `0x3D901030`, `0x3D95D015`, and `0x05500031` on dev 1012 / 1001 |
| Coolant temperature high | `0x3D901016` |
| Motor coolant temperature over high | `0x3D91102A` |
| Oil level lamp, and its colour | `0x4A508040` / `0x4A508043` |
| Oil level low / high | `0x3D901032` / `0x3D901033` |
| Oil pressure low | `0x3D911011`, `0x3D901017`, `0x29600008`, `0x3D95D017` |
| Oil monitoring system | `0x3D911029` |
| Oil life indicator | `0x24800014` |
| Transmission oil temperature high | `0x3D90102A` |

Four separate ids for oil pressure and three for coolant level are generation
variants of the same lamp, the way the motor temperatures were. The historical proposal to OR these variants is withdrawn: select and qualify
the applicable getter and its enum before deriving a warning.

### Generation and charge power

| Reading | FID | dev | tx | Value | Note |
| --- | --- | --- | --- | --- | --- |
| Power generation value | `0x2610001F` | 1006 | 5 | `0` | engine stopped; the figure the series hybrid generates into the pack |
| Power generation state | `0x34F0000A` | 1006 | 5 | `0` | |
| AC charge power | `0x32300018` | 1009 | 7 | `2.5` | already shipped as `CHARGE_KW` |
| AC charge power, DM twin | `0x2ED0001C` | 1009 | 7 | `2.5` | agrees with the above |
| Pack power | `0x14400020` | 1012 | 5 | `-2` | negative while charging — confirms `POWER_POSITIVE_IS_DISCHARGE` |
| `ENGINE_CHARGE_POWER` | `0x2ED00010` | 1009 / 1012 | 5 | `-2` | **not** engine generation: it tracks pack power, and is the int twin of the float above |
| DC-DC work mode | `0x36D00030` | 1006 | 5 | `2` | |

Two figures that answer but do not mean what their names suggest:
`INSTRUMENT_DD_EXTERNAL_CHARGE_POWER` (`0x4A508010`) reads `1522.6` and
`INSTRUMENT_DISCHARGE_POWER` (`0x15100012`) reads `51.1` — lifetime totals or
percentages, not live kilowatts. Transmission oil temperature
(`0x34F00030`) is silent on devices 1005 and 1011.

The `155` seen on `BODY_OIL_LIFE` and its neighbours also appears on
`SETTING_POWER_GENERATION_TRANSMISSION_OIL_LIFE` (`0x38D00060`, dev 1005). Four
different service-life ids reading the same number is a shared default, not
four measurements.

### Did not answer — every candidate, at every device tried

Coolant temperature is the notable miss. `ENGINE_ENGINE_COOLANT_TEMPERATURE`
(`0x4EB06040`) returned the sentinel at devices 1000, 1006, 1007, 1012, 1014 and
1039; so did `ENERGY_ENGINE_THERMOSTAT_WATER_TEMPERATURE` (`0x32400048`, and its
`_CAN` twin `0x30D00008`) and `STATISTIC_WATER_TEMPERATURE` (`0x3D956038`).
`INSTRUMENT_WATER_TEMP_METER_PERCENT` (`0x4A509018`) answers as a float but
reads `409.5`, a placeholder rather than a gauge position.

Also silent: indicated torque (`0x32400058`), boost actual and target
(`0x387000C0` / `B8`), charge-air temperature (`0x387000C8`), per-cylinder knock
retard (`0x32400180`…), pre-ignition counters (`0x324001C8`…), instantaneous
fuel consumption (`0x32400098`, and `_ML` `0x30D00010`), and engine water-pump
speed (`0x46C00040`).

The silence clusters by feature-id prefix rather than by subject: the whole
`0x324`, `0x387` and `0x30D` families are quiet, including `ENGINE_SPEED_324`
while `ENGINE_SPEED` on `0x144` answers.

**Scalar result confirmed 2026-08-23; broader inference corrected 2026-10-21.** Two sampling runs across full
start/stop cycles re-read every silent id at about 1.4 Hz: not one of them ever
answered. Coolant temperature, thermostat water temperature, indicated torque,
boost, per-cylinder knock, instantaneous fuel consumption and water-pump speed
stay sentinel with the engine turning through those readers. The former claim
that the firmware does not carry them and there is nothing further to try is
superseded: seven of these fields have exact MCU descriptors despite lacking
GET registration in the inspected CAN-FD HAL. This provides a passive-frame
research path, not a newly working API. See [signal origins](#signal-origins-and-passive-qualification-2026-10-21)
for channel, length and installed-firmware limits.

`INSTRUMENT_WATER_TEMP_METER_PERCENT` (`0x4A509018`) held `409.5` through both
runs, engine hot or cold, which confirms it as a placeholder rather than a gauge.

### Measured start/stop cycle (2026-08-23, parked, gun disconnected)

| t | rpm | state | generation | pack power | inverter |
| --- | --- | --- | --- | --- | --- |
| 0.0 s | 0 | 0 | 0, state 0 | +1 kW | 26 °C |
| 6.7 s | 1619 | 3 | 0, state 0 | −6 kW | 26 °C |
| 7.4 s | 1572 | 3 | 8 kW, state 1 | −8 kW | 26 °C |
| 9–60 s | 1321 | 3 | 8–10 kW, state 1 | −8…−10 kW | 27 → 32 °C |
| 62.7 s | 1321 | 3 | 0, **state 2** | −8 kW | 32 °C |
| 64.8 s | 242 | 3 | 0, state 2 | +1 kW | 32 °C |
| 65.5 s | 0 | 0 | 0, state 2 | +1 kW | 32 °C |
| 68 s + | 0 | 0 | 0, state 0 | +1 kW | 32 → 28 °C |

What that pins down:

- **Revolutions are real rpm.** A 1619 start peak, a 1321 generation set-point
  held for a minute, 242 spinning down. `ENGINE_SPEED_20D` and `_GB` track it
  within about 40 rpm, so any of the three will do.
- **`ENGINE_STATE` is `0` stopped and `3` running**, including through the
  spin-down. No other value appeared that day; the recorded cycle of 2026-09-18
  below added `1` for the cranking second.
- **Generation is in kilowatts.** It mirrored pack power exactly at every
  sample — `8` against `-8`, `10` against `-10` — which is the cross-check that
  settles the unit, and it independently re-confirms the discharge-positive sign
  convention.
- **`GENERATION_STATE` is `0` idle, `1` generating, `2` shutting down.** State
  `2` arrives a second and a half before the engine stops, with the kilowatt
  figure already at zero, so only `1` may count as generating.
- **The inverter is the one thermal reading the generation path has.** It rose
  26 → 32 °C over a minute of generation and fell back afterwards. With no
  engine coolant temperature on this firmware, the retired engine page showed
  it and the cluster dashboard still does — real feedback, not a stand-in.
- Fuel level held `53 %` and the coolant and oil-pressure lamps held `0` across
  the whole cycle.

**What this cycle does not settle, and the recorder that will.** Every line above
was read with the car **parked**. `GENERATION_KW` mirroring pack power exactly
while standing is consistent with it being the pack's charge from the engine
*and* with it being the generator's output, because on a parked car those are the
same number; the two drives in September saw the engine run at speed with this id
flat, which is consistent with the first reading and not with the second. The sign
of `POWER_KW` under acceleration and whether `ENGINE_RPM` reports anything with the
engine off in motion are open for the same reason.

> **Superseded 2026-09-24:** all three are closed by recorded drives: `POWER_KW` is positive out of the pack in motion and `ENGINE_RPM` sends only `0x1FFF` with the engine off (2026-09-22), and `GENERATION_KW` stayed `0` through three engine runs at speed, so it is the pack's charge from the engine, not the generator's output (2026-09-24) — see [energy contract §8](energy-display-contract.md#8-open-and-what-closes-each).

`tools/vehicle_log.py` is what closes them. It asks this same Binder through the
same `service call` chain, from the host over ADB, one CSV row a second into
`captures/vehicle-log/` (git-ignored), reconnecting when the car sleeps; the
decoding is the app's - the second parcel word, the Binder sentinels, the rpm id's
own `0x1FFF`, floats by bit pattern - and the **raw words are written beside the
decoded values**, so a decoding argument can be settled from the file afterwards.
The drive worth recording is one with **the engine running at speed**: twenty
minutes of motorway with at least one engine cycle in it, some braking hard enough
to be unambiguous regeneration, and a full-throttle pull. It also settles what a
500 m consumption bin looks like on a real road, which is what the chart's
ceilings are chosen against. `VehicleLogReplayTest` reads whatever the directory
holds and asserts the invariants that do not depend on what a signal means; it
passed with the directory empty until 2026-09-18, when the parked cycle below
became its first file.

### Second parked cycle (2026-09-18, recorded, the Contour on the cluster)

The first file `tools/vehicle_log.py` ever filled:
`captures/vehicle-log/vehicle-20260918-183009.csv`, one row a second, the owner
starting the engine standing in P with the dashboard already on the cluster
(`SHOW_DASHBOARD` at 18:31:32, the start at 18:31:43, no cable on the gun). It
repeats the 2026-08-23 cycle above on a recorder instead of a hand log, and adds
five things.

| t | `ENGINE_RUNNING` | rpm `0x14400012` | rpm `_20D` | `GENERATION_KW`, state | `POWER_KW` | `0x2ED00010` | pack V |
| --- | --- | --- | --- | --- | --- | --- | --- |
| before | 0 | 0 | 0 | 0, state 0 | +1 | +1 | 550 |
| 0 s | **1** | 286 | 975 | 0, state 0 | +1 | +1 | 546 |
| 1 s | 3 | 1643 | 1623 | 8, state 1 | −8 | −8 | 554 |
| 2 s | 3 | 1410 | 1400 | 8, state 1 | −8 | −8 | 554 |
| 3 s … 2:24 | 3 | **1360, flat** | 1323 … 1378 | 8, then 10 for 13 s, then 8; state 1 | −G | −G | 552 … 556 |
| 2:25 | 3 | 1360 | 1345 | 0, **state 2** | −8 | −7 | 556 |
| 2:26 | 3 | 1360 | 1339 | 0, state 2 | −4 | −4 | 554 |
| 2:27 | 3 | 419 | 0 | 0, state 2 | +1 | +1 | 554 |
| 2:28 | 0 | 0 | 0 | 0, state 2 for two more seconds | +1 | +1 | 554 |
| 2:30 + | 0 | 0 | 0 | 0, state 0 | +1 | +1 | 553 |

- **`ENGINE_RUNNING` has a third value.** It read `1` for the cranking second
  (286 rpm on the primary id) before `3`, so «0 stopped and 3 running» above was
  incomplete and `VehicleTelemetry.engineRunning`'s `≥ 1` is the right test, not
  `== 3`. The flag fell three seconds after the kilowatts did; the trace's
  ten-second hold covers that with room.
- **The primary rpm id is stepped; the `_20D` twin is the measured one.**
  `0x14400012` held exactly 1360 for 144 consecutive rows while `0x20D00008`
  moved between 1323 and 1378 on the same rows; on the way in the primary
  stepped 286 → 1643 → 1410 → 1360, on the way out 419 → 0. That is a set-point,
  or a coarsely quantised read, and it is what the Contour prints: the calmer
  figure, and the one that says what the engine is asked for. "Within about
  40 rpm" above is the width of that difference.
- **Generation mirrored pack power again**, `8`/`10` against `−8`/`−10`, at every
  row but the two transitions where the two ids were sampled a second apart.
  `0x2ED00010` mirrored `POWER_KW` on the same rows, transitions included: it is
  pack power, twice proven, whatever the catalog calls it. The engine put
  0.33 kWh into the pack in 2 min 24 s and the pack rose 550 → 556 V under it.
- **`GENERATION_STATE` outlived the flag.** `2` arrived with the kilowatts at
  zero, three seconds before the flag fell, and stayed two seconds after it.
  Only `1` counts as generating - the rule above - and the flag, not the state,
  says the engine is turning.
- **`0x34200008`, catalogued as accelerator position, read `100` for the whole
  run.** It ramped 0 → 75 → 100 in the two seconds before the flag rose and fell
  to `0` in the same second as the kilowatts, the brake id at `0` throughout.
  The owner's answer: he started the engine with the pedal, held to the floor.
  So the id is the accelerator pedal in percent, and **on this car a floored
  pedal in P starts the engine**: the flag rose one second after the pedal
  reached `100`, the engine generated 8-10 kW into the pack for as long as it
  was held, and stopped the second it was released. The app does not read the
  id; the trick is a way to record a generation cycle without a drive.

What the cycle does *not* add is anything about motion: the car stood in P, so
`GENERATION_KW` and pack power were one number for the same reason as on
2026-08-23. `VehicleLogReplayTest` runs against this file and passes; the drive
that closes the open items is still owed.

> **Superseded 2026-09-24:** the drives were recorded on 2026-09-22 and 2026-09-24, and they close the motion questions above — see [energy contract §8](energy-display-contract.md#8-open-and-what-closes-each).

### Engine itself — `com/byd/feature/engine/Engine.java`, dev `1012`

| Reading | Constant | FID |
| --- | --- | --- |
| Engine speed | `ENGINE_SPEED` | `0x14400012` |
| Engine speed, other generations | `ENGINE_SPEED_20D` / `_324` / `_GB` | `0x20D00008` / `0x32400038` / `0x10D00008` |
| Running or stopped | `ENGINE_STATE` | `0x10D00038` |
| Coolant temperature | `ENGINE_ENGINE_COOLANT_TEMPERATURE` | `0x4EB06040` |
| Thermostat water temperature | `ENERGY_ENGINE_THERMOSTAT_WATER_TEMPERATURE` (+ `_CAN`) | `0x32400048` / `0x30D00008` |
| Indicated torque | `ENGINE_INDICATED_TORQUE_NM` | `0x32400058` |
| Boost, actual and target | `ENGINE_ACTUAL_BOOST_PRESSURE` / `_TARGET_` | `0x387000C0` / `0x387000B8` |
| Charge air temperature | `ENGINE_BOOST_GAS_TEMPERATURE` | `0x387000C8` |
| Power sent to the pack by the engine | `ENGINE_CHARGE_POWER` | `0x2ED00010` |
| Knock retard angle, cylinders 1–4 | `ENGINE_CYLINDER_n_KNOCK_RETARD_ANGLE` | `0x32400180` + `0x10` each |
| Pre-ignition counters A–D | `ENGINE_x_CYLINDER_PRE_IGNITION_COUNTER` | `0x324001C8` + `0x8` each |
| Catalyst heating | `ENGINE_CATALYST_HEATING_FLAG` | `0x2330002D` |
| Coolant / oil level flags | `ENGINE_COOLANT_LEVEL` / `ENGINE_OIL_LEVEL` | `0x05500031` / `0x05500038` |
| Oil, transmission oil, coolant service life | `BODY_OIL_LIFE` and neighbours | `0x4BB000D0` … |

### Fuel

| Reading | Constant | FID | dev |
| --- | --- | --- | --- |
| Instantaneous consumption | `ENERGY_INSTANTANEOUS_FUEL_CONSUMPTION` | `0x32400098` | 1006 |
| Instantaneous consumption, ml | `..._ML` | `0x30D00010` | 1006 |
| Trip average | `ENERGY_CURRENT_ITINERARY_AVERAGE_FUEL_CONSUMPTION` | `0x3590481F` | 1006 |
| Average over the last 200 m | `STATISTICS_AVERAGE_INSTANT_FUEL_LAST_200M` (+ `_HEV`) | `0x3D90601C` / `0x46100030` | 1014 |
| Range on fuel | `STATISTIC_FUEL_DRIVING_RANGE` | `0x4A504038` | 1014 |
| Low fuel alarm | `INSTRUMENT_FUEL_LOW_ALARM` | `0x4A507027` | 1007 |

This catalog listing turned up no plain "tank level, percent" constant, and an
earlier version of this paragraph concluded a fuel gauge would have to be built
out of range. **That conclusion is withdrawn.** The read-only sweep above found
`STATISTIC_FUEL_PERCENTAGE` at `0x4A507040` on device `1014`, and it answered
`53` — consistent with the `491` km the range id reported in the same sweep, and
steady across a full engine start/stop cycle. The level is a real reading; the
catalog listing was simply incomplete.

Two of them entered the historical panel allowlist as `FUEL_PERCENT` and
`FUEL_RANGE_KM`, on the cold poll and gated to the engine set. Both left the
product allowlist with the retired pages on 2026-08-27: the current cluster does
not render them, and the stock cluster already owns both facts.

**`INSTRUMENT_FUEL_LOW_ALARM` was the third and is now out.** It was read here as
the vehicle's own line for "low" — better than a threshold of ours, because a
second opinion beside the car's would put two answers on one cluster. The live
run of 2026-08-25 killed that: it answered `0` on 2026-08-23 and `1` two days
later against an unchanged `53 %` tank (`488` km of range), so whatever the
constant names, it is not this tank's alarm. On the cluster it was painting a
half-full tank in the alert colour. Nothing of ours replaces it; the stock
cluster keeps the fuel level, range and low-fuel lamp a few centimetres away.

## Diagnostics and DTC (2026-10-18)

### Three separate diagnostic sources

1. **Instrument warnings:** individual FID getters report a lamp/notice. The
   framework `BYDAutoInstrumentDevice` common lamp enum is `0=invalid`, `1=on`,
   `2=off`, `3..7=blinking at 0.25/0.5/1/2/4 Hz`; this is not a universal enum
   for every DD notice or fault field. `getModuleState` instead uses 0=normal,
   1=fault. The native cluster's `WarningLightStatus` confirms the lamp enum.
2. **IVI health journal:** BydHealthDiagnostic reads `diag_socket_channel` with
   `latest_diag_data` or `all_diag_data`. The 213 definitions in
   `diagnostic_config.json` cover head-unit peripherals/software, not all car
   ECUs. The one bounded `latest_diag_data` socket read returned EOF with zero
   bytes; access/empty/protocol interpretation is unresolved. Do not render
   this as a successful “no faults” scan.
3. **Vehicle ECU DTC memory:** UDS `ReadDTCInformation` (`0x19`). This now has a
   working read path through the stock EOL transport, independently of either
   source above. It is a requested diagnostic exchange, not passive CAN capture.

The live vehicle reports `persist.sys.AutoType=170` (Z9GT), unlike the N9
`168` capture of 2026-10-16. At 09:33:45 UTC the instrument getters on dev1007
returned: SRS `0x27500008`, ABS `0x27500014`, brake `0x27500020`, ESP
`0x2750002C`, steering `0x2870000E`, TPMS `0x28700014`: all `2`; engine
`0x28700026`: `1`; low fuel `0x28700032` and low pack `0x2A600026`: `0`.
EPB `0x2750001A=1` is an operational indication, not evidence of a fault.
Charge-lid notice `0x3D902022=0` remains raw/unqualified. No physical lamp
observation or fault-causing experiment was performed; the engine lamp does
not identify a cause and the body ECU result below cannot explain it.

### Proven OEM transport

Sources: the installed `SpotInspection.apk` matches SHA-256
`0677ac9473557dcd760da047c2639ec9336709f1e7c12b0f9980de1988093d2b`;
framework matches `aa3acd7738e1fa22c4ec68f69b271fd6938cf7756239c1ac6b30051c0d4fda8c`.
The existing decompile is under
`captures/firmware/pulled-from-car-20261018/jadx/SpotInspection/`:
`eol/uds/UdsAutoApiSender.java`, `EolUdsListener.java`, `EolUdsResult.java`,
`eol/executor/constant/UdsSidEnum.java`, `eol/executor/util/MatchUtils.java`.

- `BYDAutoMqttDevice`, device `1033`; buffer TX `0xAA00010A`, RX `0x9900010A`.
- Owner byte `0x0A`, request CAN id big-endian, one-byte UDS payload length,
  then UDS bytes. Count: **`0A 07 20 03 19 01 FF`**; list:
  **`0A 07 20 03 19 02 FF`**. SDK `set(..., BYDAutoEventValue)` is the
  request transport; these two payloads only read diagnostic information.
- Response: owner, response CAN id, UDS fragment length, total frames,
  one-based frame index; first frame adds diagnostic result (`1=success`,
  `2=negative`, `3=response timeout`, `4=diagnostic timeout`, `5=busy`).
  First payload begins at byte7, continuation payload at byte6.
- This capture delivered **240-byte buffers with unrelated trailing bytes**.
  Honor the declared length: count is 6 UDS bytes, list is 35. Never parse
  the tail as DTCs. The generic listener received the identical packet twice;
  these are duplicate deliveries, not 16 faults or two independent reads.
- Multi-frame assembly exists in stock code but was not exercised here.
  Validate owner, address, lengths, positive SID/subfunction, consistent total
  and contiguous indices before declaring a complete scan.

The fixed shell helper is parked at
[`research/vehicle-diagnostics/DtcReadProbe.java`](../research/vehicle-diagnostics/DtcReadProbe.java).
It accepts only `count` or `list`, fixes address `0x720`, listens for 2 seconds
before sending once, aborts on preexisting traffic or changed manager state,
collects for 12 seconds with an 18-second watchdog, and unregisters. It uses
normal shell UID2000 with the SDK; no permission spoof, root, APK installation,
service restart, controller reset or diagnostic unlock. Normal Denza Apps UID
direct-UID support is unproved. This was the initial research-only state; the
reader has since been promoted behind local ADB as described below.

The recorded preflight was park switch `1`, speed `0`, autoservice PID135,
SpotInspection PID3622, `sys.diagmgrsrv.task_bk=r@-1_0&w@-1_0`. No experimental
session or persisted state was created to reset. After both reads these PIDs
and the property were unchanged, and the temporary dex was removed. Neither
read called `0x10`, `0x11`, `0x14`, `0x27`, `0x28`, `0x2F`, `0x85`, nor a
periodic tester-present. This is not a claim of a system-wide exclusive lock:
the property and passive interval only provided an observed quiet baseline.

### Actual DTC records and their limits

Count response: `59 01 FF 00 00 08` (availability mask `FF`, format id `00`,
eight records). List response: `59 02 FF`, followed by:

| Raw 24-bit DTC | Status | Interpretation |
| --- | --- | --- |
| `516006` | `28` | confirmed, failed since last clear |
| `C29D87` | `28` | confirmed, failed since last clear |
| `C12187` | `28` | confirmed, failed since last clear |
| `A14802` | `28` | confirmed, failed since last clear |
| `A25000` | `28` | confirmed, failed since last clear |
| `A25B00` | `28` | confirmed, failed since last clear |
| `C22987` | `28` | confirmed, failed since last clear |
| `C22A87` | `28` | confirmed, failed since last clear |

`0x28 = bit3 confirmedDTC + bit5 testFailedSinceLastClear`. All eight have
`testFailed=0`, `pendingDTC=0`, `warningIndicatorRequested=0` in this snapshot.
They are stored confirmed records with no current failure bit set; this does
not prove the car healthy or explain the original event. “Confirmed” must not
be translated as “active”. Mask `FF` selects records with any supported status
bit, including test-completion bits; a count alone is not a fault count.
Status bit definitions are independently corroborated by
[AUTOSAR Diagnostics](https://www.autosar.org/fileadmin/standards/R21-11/AP/AUTOSAR_SWS_Diagnostics.pdf)
and the [ReadDTCInformation implementation](https://github.com/pylessard/python-udsoncan/blob/master/udsoncan/services/ReadDTCInformation.py).

The ECU request/reply is `0x720 → 0x728`, used by stock body/window/lock/light
workflows. Its precise hardware identity was not read. No generic code website
or unqualified mapping was used to invent BYD descriptions. Stock `log1902`
gets descriptions from script `Attach.dtcList` and status names from
`Attach.dtcStatusList`; these have not been obtained for this ECU/vehicle.
Raw codes remain the durable representation. The later format-00 work below
adds a readable code and separately attributed general references; it does
not establish the missing BYD dictionary.

Other stock assets name `0x726 → 0x72E` (tailgate scenarios), `0x747 → 0x74F`
(body scenarios), `0x770 → 0x778` and `0x771 → 0x779` (seat lighting scenarios).
These are static leads, not an authoritative Z9GT ECU topology or tested DTC
coverage. No address sweep was made. Engine, BMS, inverter, ABS/ESP, SRS, ADAS
DTC reads and freeze-frame/extended records remain unproved.

> **Superseded 2026-10-20:** `747`, `726` and `722` answer DTC reads, and so do the engine ECM `7E0` and `7E2`/`7E3`/`7E6`/`7E7` (`7E5` answers OBD) through the same transport — see [OBD and more modules](#obd-and-more-modules-through-the-same-transport-2026-10-20). The later [remaining-ECU run](#remaining-ecu-identities-and-chassis-dtc-2026-10-20) also identified and read DTC from ABS/ESP `782`, EPS `783`, SRS `7F1`, ADAS `712` and front radar `7F2`.

### Rejected shortcuts and engineering-menu contract

- `BYDAutoDtcDevice` dev1045 `sendFaultCodeData()` **writes** a fault-code buffer;
  it is not a DTC getter. It was not called.
- `diagmgrsrv.getDiagingId()` returned `-1`. Firmware disassembly has a
  calling-UID gate (`uid >= 1002`) returning `-1` on denied reads, so this
  value does not establish that diagnostics are idle. No manager start/end
  or UID bypass was attempted. Use the verified OEM EOL route above.
- Separate data-quality states from vehicle states: “not polled”, “no reply”,
  “access denied”, “unsupported”, “stale”, “complete response, zero matches”.
  Only the last one permits “no matching DTC in this ECU”.
- Every DTC screen needs ECU scope, request mask, snapshot time, response
  completeness, raw code/status and description provenance. Store status bits
  independently; saved, pending, current and warning-requested can overlap.
- Overview should separate **current failure**, **stored records**, **instrument
  warnings** and **unread ECUs**. No all-green vehicle silhouette after one
  successful body-ECU query. Links in the first board are logical associations,
  not claimed physical CAN wiring.
- Board feedback, 2026-10-18: the owner rejected the first web-dashboard
  layout. The next draft is a single head-unit screen in the Luminofor palette:
  large functional schematic, values at nodes, compact ECU/DTC panel, top
  subsystem tabs; distinct battery, drive, thermal, chassis and DTC views.
  This draft is now implemented as a native Android window (below). The new
  product APK has not been installed on the car.
- Implemented navigation: overview; HV battery/charging; drive/engine; thermal;
  chassis; body/cabin; DTC/alerts; data sources. Present only qualified signals;
  no fabricated cell heatmap (we have min/max, not every cell), actuator state,
  coolant readings or ECU-wide coverage. Existing telemetry uses its own
  staleness and energy-format contract.

### Native diagnostic window and packaged reader (2026-10-18)

> **Superseded 2026-10-20:** the window, its renderer, `VehicleDiagnostics`, `DtcProtocol`, the lamp batch, the `main-diagnostics*` boards and the packaged `VehicleDtcProxyMain` were removed; «Сервис → Диагностика» opens the scanner, whose resident helper and engine are described in [vehicle-diagnostics-architecture.md](vehicle-diagnostics-architecture.md). The car's answers recorded here stand.

`Сервис → Диагностика автомобиля` opens `VehicleDiagnosticsWindow` as a native
Compose dialog with an Android Canvas schematic. Closing returns to Service.
Eight tabs change the diagram; selecting a node changes its readings panel.
There is no WebView or production fixture data. `DiagnosticRenderer` and the
Luminofor board share `diagnostics.json`; `DiagnosticBoardContractTest` holds
the asset to the board. Debug-only fixtures show a frozen DTC capture and
illustrative telemetry values; they are not a live measurement.

`VehicleWatcher.DIAGNOSTICS` claims the existing `VehicleSession` hub while the
window is started. It does not duplicate those 20 scalar reads. Their figures
expire after the existing 2 s / 25 s horizons; dropped snapshots do not renew
them. Pack power and voltage text use `EnergyReadouts`. A separate window-owned batch reads 43 additional scalars and fifteen warning
lamps on opening, every five seconds, or «Обновить показания». These expire
after 15 s of monotonic time, including during a long DTC read. Their age
means age of the getter response, not age of the underlying CAN packet.
Missing/invalid lamp values stay unknown; no temperature or cell-delta alarm
threshold has been invented.

«Читать DTC» runs `VehicleDiagnostics` through the app's passive local ADB
identity. Profile 170, P=1 and speed=0 are required before each fixed request.
One serialized window worker, a non-overwriting DTC request queue and a
shell file lock prevent competing app reads. Extra polling pauses during DTC;
manual requests queued during a scalar batch are not lost. The helper checks the manager baseline and observes RX for two seconds
before sending; this is not a universal lock on all OEM diagnostic clients.
No session entry, unlock, clear, actuator operation or address scan is offered.
Closing/backgrounding releases the telemetry claim, cancels the UI job and
closes its shell; an already launched helper is bounded by an 18 s watchdog.
Snapshots live in memory and retain their original time across failed retries.

`DtcProtocol` accepts only the qualified single-frame envelope, owner 10,
the selected response ID 0x728 or 0x76A, successful transport result, UDS 0x59 with matching
subfunction and complete helper cleanup. It checks the count against the list,
status support mask and duplicate records; exact callback duplicates and
unused buffer tails are handled. Timeout, negative response, unsupported
multipart response or count disagreement never publish an empty success.
Raw 24-bit codes, the status byte, independent current/pending/confirmed/lamp
bits, failed-since-clear, support mask, format and snapshot time remain visible.
Only a complete explicit zero response permits zero matches, scoped to this ECU.

The **initial one-target packaged helper** was read-tested on the car after the promotion:
SHA-256 `06b50e79f2eb93ed6d0b2cde29656f17af2807c3ce4fd96bb8419c70b67d0db0`.
Both requests returned transport result 0 and unregister completion; the same
eight codes/status 0x28 were returned. Profile 170 and the manager baseline
were unchanged, `autoservice` PID 135 and SpotInspection PID 3622 remained.
The temporary jar was removed; the lock inode is retained so future readers
share the same lock. This verifies the helper, not the new APK's complete
local-ADB/UI path on the car.

Further corpus evidence: `res/raw/low_power_script.json` names request 0x720
as `LA_BCM` / 左车身域控制器 (left body domain controller). Its only DTC entry
is `0x123456`, described as a radar fault; it supplies no descriptions for the
eight observed codes and is not accepted as their dictionary. `ScriptDao`
resolves downloaded dictionaries under the app's external `files/Scripts`,
falling back to internal `files/Scripts`. The external directory was empty
in this session. No account, script download or diagnostic session was started.

Validation: Android unit suite and debug assembly passed (2,226 tests,
including 22 diagnostic checks). Native emulator screenshots of overview,
battery, no data, retained DTC after failure, and Service were compared with
Luminofor: 0.81–1.35% pixels above the 24-level threshold, mean 0.58–0.89/255;
remaining differences are text rasterisation. The actual ServicePanel row,
subsystem switch, unsupported-profile rejection and return were exercised.
APK installation and full window acceptance on the vehicle remain pending.

Evidence stays under `captures/diagnostics-menu-20261018/`: `dtc-count-live.txt`,
`dtc-list-live.txt`, `reports/dtc-live-decoded.json`, `reports/dtc-run-plan.txt`,
`warnings-live.txt`, `live-summary.json`, `reports/diagmgrsrv.asm`,
`product-count-live.txt`, `product-list-live.txt`, `product-postflight.txt`,
`reports/product-read-plan.json`, `reports/product-live-decoded.json`,
native screenshots and `apk/denza-apps.apk`, the original
probe source/dex and hashes. The small decoded snapshot is also retained in
[`research/vehicle-diagnostics/dtc-20261018.json`](../research/vehicle-diagnostics/dtc-20261018.json).
Offline replay is retained in `research/vehicle-diagnostics/decode_snapshot.py`;
`python3 -m unittest discover -s research/vehicle-diagnostics` passes 9 checks
covering the real snapshot, duplicate deliveries, tail bytes, empty/truncated/
foreign/negative responses, explicit zero and count disagreement. The decoder
intentionally rejects multi-frame input until a real trace qualifies assembly.
The probe compiled with Java 8 bytecode and Android d8 before both live reads.

### Additional diagnostic readings and LBMS (2026-10-18)

> **Superseded 2026-10-20:** the window that owned this batch is removed; the 43 GETs of `DiagnosticExtra` now feed the scanner's system screens ([vehicle-diagnostics-architecture.md](vehicle-diagnostics-architecture.md), 6.1).

The 43-entry catalogue is `DiagnosticExtra` in `DiagnosticExtras.kt`. A single
qualified GET batch completed in 0.420 s at 10:19:36 UTC, profile 170/CANFD,
P=1, speed=0. All 43 returned values. No setter, sync or initializer was called.
These extend the existing shared telemetry; they are not added to the normal
instrument poller. The worker exists only while the diagnostic window is started.

| Area | Added readings | This parked snapshot |
| --- | --- | --- |
| HV battery | SOC float percent, SoH estimate, min/max pack temperature, cell count, insulation raw | 61%, 99%, 13/14 °C, 166, 14128 raw |
| Power | 12 V supply, front/rear bus voltage, charge-current SDK value, fuel percent | 13.8 V, 556/555 V, −5 A, 99% |
| Wheels LF/RF/LR/RR | Four pressures and four tyre temperatures | 2.77/2.65/2.72/2.67 bar; 23/13/20/12 °C |
| Four doors | Position, lock, window opening percent, initialization, thermal protection | All positions 2 closed, locks 1 unlocked, windows 0%, initialized 1, protection 0 |
| Climate | Cabin/outside temperature, inside/outside PM2.5 | 19/19 °C; 17/30 µg/m³ |

The small retained `research/vehicle-diagnostics/readings-20261018.json` holds
raw words, exact device/FID/getter and decoded values; unit tests replay all
43. SOC is already a float percent. Window 0 is closed and protection 0 is
inactive; door/lock/init zero is unknown. The shared native no-data words,
invalid floats and out-of-range values are rejected. Missing or stale values
remain unknown. SoH does not certify health; insulation units and charge-current
sign remain unqualified. No fault threshold is inferred from these samples.
See [window-control-findings.md](window-control-findings.md) for the window
getter and initialization semantics. No window or door control is exposed here.

`SpotInspection/res/raw/low_power_script.json` Attach names LBMS as
低压电池管理系统 (low-voltage battery management), request 1890 / `0x762`,
response 1898 / `0x76A`. `LbmsDtcReadProbe.java` qualified this exact address:
count `590109000000`, list `590209`. Thus zero matching codes in this ECU,
format `00`, supported status mask **09**: current and confirmed only. Pending,
lamp request and failed-since-clear are unsupported, not false. The product
keeps a distinct timestamped snapshot per ECU; selecting LBMS or 12 V changes
the read target. Every response is checked against that target, so a 728 reply
cannot populate 76A. The ECU name comes from the stock script; no identity DID
or full ECU inventory was read.

The two-target **packaged** `VehicleDtcProxyMain` was then separately read-tested
at 10:29 UTC, SHA-256
`33ce6a7242ad8f62b677af3f91f5e8491076e2cdaca63e13daabdb55038ecf0c`.
Only the enum arguments `body` and `lbms`, each with `count` or `list`, are
accepted. One pair per ECU returned 8×status28 for 728 and explicit zero/mask09
for 76A. All four operations returned transport result 0 and unregistered.
The manager baseline and autoservice PID135 / SpotInspection PID3622 remained;
the temporary jar was removed. There were no sessions, unlocks or clears.

Evidence: ignored `captures/diagnostics-fill-20261018/` contains scalar raw
responses, the read plan, standalone LBMS proof, exact APK/helper, both product
count/list pairs and postflight, native screenshots and board comparisons.
Small durable results are under `research/vehicle-diagnostics/`. New debug
boards `main-diagnostics-chassis`, `-body`, `-lbms`, `-battery-data` use the
captured extra readings, with absent shared telemetry left unknown. They are
explicit frozen scenes, never a production fallback. Wheel/door diagrams place
the vehicle's front at the top.

Validation: the standard full `:denza-apps:testDebugUnitTest
:denza-apps:assembleDebug` passed without exclusions: **2,242 tests**, including
32 diagnostic tests, zero failures/errors. The temporary unrelated HUD test
compile blocker was resolved in the concurrent HUD change before this final run.
Seven native scenes matched their boards within text rasterisation (0.93–1.36%
above the 24-level threshold, mean 0.58–0.85/255). Actual Service → diagnostics →
DTC → LBMS target selection, unsupported-profile rejection and return passed
on the emulator. Full window acceptance on the car is still pending.
Offline decoder checks include LBMS (10 tests).

Next acceptance: vehicle-specific ECU addresses and OEM dictionaries, then
individual bounded read validation for each ECU. Snapshot/extended-data
subfunctions need separate support checks and layouts before implementation.

### EMS, HV BMS, drives and ABS research (2026-10-18)

Three independent corpus investigations covered combustion EMS, traction
BMS/VCU/motors and ABS/ESP/IPB. They inspected installed SpotInspection,
OTAUpdate2, ProductLineOTA, the exact framework and native cluster symbols.
The bundled encrypted SpotInspection `response.json` was decoded offline
with the shipped algorithm: 21 modules, 329 cases, 750 AutoAPI steps, without
an EMS/BMS/ABS address table or applicable DTC dictionary. `UDS_CFG.json`
describes the IVI's own DoIP endpoints, not the vehicle's CAN ECU inventory.
This is a bounded negative finding about the inspected corpus, not proof
that these ECUs cannot be read.

| Block | Proven DTC route | What diagnostics can show now |
| --- | --- | --- |
| Combustion EMS | Not established | Existing running/RPM/generation/fuel, engine lamp, additional warning availability |
| Traction HV BMS | Not established | Existing SOC/SoH/voltage/temperatures/cell extrema, warning availability |
| Front/rear traction drives and VCU | Not established | Existing motor/inverter temperatures and bus voltage; general power warning availability |
| ABS / ESP | Not established | ABS/ESP/brake lamps and their flashing states; tyre readings separately |
| Low-voltage LBMS | `762 → 76A`, explicit zero/mask09 | Its own timestamped result, never substituted for traction BMS |
| Body domain | `720 → 728`, eight stored records/maskFF | Raw and readable code, subtype, status bits and description provenance |

OTA's `EcuListInfo` includes `ecuId`, `ecuDiaId`, `logicAddress`, DIDs and
versions. `CanDiagnostic.java:240–257` splits `ecuDiaId` into hexadecimal
request/response addresses. `VehicleOTAUtil.java:841–858` stores the list
under key `ecuDiagnosisResultListInfo` in `share_data_prefs`. The initial direct
shell read attempts all returned **Permission denied**:

- `/data/user/0/com.byd.otaupdate2/shared_prefs/share_data_prefs.xml`;
- `/data/ota_package/ota_ghy/result/upgradeInfo.json`;
- `/data/ota_package/needCheckEcuList.json`;
- `/collect2/VehicleVersion2`;
- `/collect2/diagnosticsrv/ecu_config.json` (may be CAN logging configuration,
  not necessarily UDS topology).

The later separately authorized exports below supersede the unknown-content
part of this initial finding: `ECUVersion.json` and the version files were
recovered, but the full-map key was absent from both OTA preference files and
the inspected strategy/upgrade files were missing. Direct shell denial did not
establish whether a file existed or held the needed data.

No OTA diagnostic workflow was launched: it can send `10 01`. No OEM backend
download, session, unlock, address sweep or new ECU request was performed.
The existing logcat buffers also contained no messages under the exact
`OTA_GHY_UPGRADE_CanDiagnostic` / `OTA_GHY_UPGRADE_VehicleOTAUtil` tags
(nor the unprefixed variants). Logging was not enabled or altered; this does
not establish whether older inventories exist outside the current log buffers.
Generic BYD scan maps have conflicting BMS/VCU/motor pairs across variants;
none was promoted into the profile170 allowlist. The next missing artifact is
the vehicle-specific ECU inventory plus the matching EOL `Attach.dtcList` /
ECU software dictionary, obtained through an authorized export/read path.

At **10:52:28 UTC**, one bounded scalar GET sweep on profile170/CANFD, P=1,
speed=0 reconfirmed engine lamp1 and six safety lamps2. These six additional
dev1007/tx5 fields all returned **0, invalid/unavailable**:

| Indicator | FID | Meaning of this capture |
| --- | --- | --- |
| Coolant overtemperature | `2870002C` | Unknown, not off |
| Low oil pressure | `29600008` | Unknown, not off |
| GPF | `2870001A` | Unknown; no inference about regeneration |
| HV battery heat warning | `2A600032` | Unknown, not off |
| HV battery fault warning | `2B000008` | Unknown, not off |
| Power-system fault warning | `2A60001A` | Unknown, not off |

`autoservice` stayed PID135. Native cluster identifies ESP OFF as `27500026`
(not guessed `27500038`); its one dev1007/tx5 GET returned **−10011** and is
excluded from product polling. Coolant temperature candidates lack a proven
°C scale; existing engine RPM keeps its `1FFF` invalid sentinel. No temperature,
oil-pressure measurement or ECU fault cause is inferred from an indicator.
The window now polls 15 known lamp getters, rejects 0/errors and preserves
3–7 flashing modes. Tests replay the actual capture and all five blink values.

### Vehicle-specific inventory and dictionary acquisition (2026-10-18)

The owner chose to continue through the head unit, without a VDS/TIS account or
external diagnostic adapter. Three further offline investigations separated
actual saved objects from getters that initiate workflows. Reports and source
SHA-256 manifests are in `captures/diagnostics-discovery-20261018/`:
`ota-export.md`, `eol-dictionary.md`, `native-discovery.md`.

**Two different artifacts are needed.** An ECU inventory identifies installed
blocks, their software and diagnostic routes. A DTC dictionary maps a raw code
within that ECU/software scope to its manufacturer description. One does not
replace the other; profile170 alone does not establish software applicability.

| Existing artifact to recover | Source-backed contents | Limit |
| --- | --- | --- |
| `/data/ota_package/ota_ghy/result/strategy.json` (OTAUpdate2) or `/data/ota_package/ota/result/strategy.json` (OTA controller) | `VehicleVersionInfo.strategy.sequence`: ECU IDs, transport-specific `ecuDiaId`, DIDs, software/hardware/config metadata | Both paths reported missing by the first exporter; an existing strategy could be stale or cover only an update subset |
| OTA controller / OTAUpdate2 `share_data_prefs` key `ecuDiagnosisResultListInfo` | Full `EcuListInfo` records, including `ecuDiaId` and `logicAddress` | Both preference files read in the second authorized export; key absent from both. Other keys were discarded in memory |
| `/data/ota_package/ECUVersion.json` | `ecuInfo[]`: ECU ID, software code/version, hardware version, configuration code/version | Inventory only; no diagnostic request/reply addresses |
| `/data/ota_package/needCheckEcuList.json` | ECU IDs/software codes and check metadata | Inventory only; no diagnostic addresses |
| SpotInspection `Scripts/<station>/<code>-<version>-script.txt`, metadata sibling `<code>-<version>.json` | Plaintext `data.attach[]`: `ecuAbbrName`, `ecuName`, bus, CAN request/response IDs, frame size, `dtcList[{code,desc}]`, `dtcStatusList[{mask,status}]` | Potential exact routing + dictionary; a matching script and applicability evidence must actually be recovered |

EOL's upstream command supplies the exact script code/version/station, URL and
task token. Command 2 (download) is separate from command 4 (execute), but its
validation immediately creates a persisted EOL task, acquires the inspection
lock and schedules a timeout. It is **not** a read-only dictionary getter.
`systemIds` in download metadata has IDs/priorities/function types, not addresses
or descriptions. Metadata is saved before checksum/decryption succeeds. Stop or
timeout can clear `Scripts`; an empty cache therefore does not prove the vehicle
has no OEM dictionary. No OEM task or backend request was initiated.

`research/vehicle-diagnostics/import_oem_eol.py` now imports already obtained
plaintext plus metadata offline. It retains source hashes and ECU/script/station
identity, rejects conflicting/ambiguous routing and invalid numbers, and omits
VINs, tokens, security attachments and command steps. Every output remains
`profile:null`, `applicability:unverified`, `eligibleForProductAllowlist:false`.
It is a research extraction tool, not an automatic promotion into the app.

**Cached SDK reads, live:** `OtaCachedVersionsProbe.java` called only the three
public cached getters on dev1032, through the exact SDK buffer API. It did not
call `query`, a setter, listener, OTA workflow or UDS service. The guard observed
profile170, P=1, speed=0 and manager `r@-1_0&w@-1_0`.

| SDK method | FID (signed decimal) | Actual response |
| --- | --- | --- |
| `getECUVersion()` | 157571 | Empty byte array |
| `getECUSoftcode()` | 256763 | Empty byte array |
| `getECUVersionAndSoftcode()` | -1728053165 | 11 bytes `4D43555F4F46464C494E45`, ASCII `MCU_OFFLINE` |

These responses contain no inventory. The marker has no qualified vehicle-health
interpretation; it does not establish that the car's MCU or every ECU is offline.
`autoservice` PID135, BydLogTool PID4822 and the manager baseline were unchanged.
The 8-second-watchdog helper exited normally and its staged dex was removed;
raw results, before/after state and identities are under `ota-cache-probe/`.

A subsequent separate `OtaFaultCacheProbe.java` called the SDK's nominal
`getFaultCodeInfo()` cache getter exactly once (dev1032, FID831291,
`OTA_CMD_ECU_GET_FAULTCODE`). It returned an **empty byte array**. No consumer
establishing a vehicle-wide DTC layout was found, and empty cannot be interpreted
as zero faults. **Correction from the later native analysis below:** the legacy
fault GET is not registered in this HAL, and `getBuffer` suppresses its error.
The normal SDK exit therefore does not establish a successful cache read.
This helper also uses an 8-second watchdog and no query, setter,
listener or workflow. Its staged dex was removed; P/speed, service PIDs, manager
state and crash buffer were unchanged. Source/dex identities and the result are
under `ota-fault-cache-probe/`; no new UDS request was sent. Native
`FeatureMapper::getStringValue` is the generic cached buffer path, but requires a
registered FID. The paired
`sendECUIds(3, ids)` is a separate setter (FID809554); its ID layout and a
vehicle-specific ID enum are unknown; the current HAL rejects this SET before
transport. It was not called. The detailed scope
and next native analysis locations are in `ota-faultcode-cache-scope.md`.

**Export paths excluded by source inspection:**

- OTA SDK is protected by a signature permission; `getDisplayData()` returns a
  UI bundle, and LockSync drops diagnostic addresses. Restarting OTA to print
  prefs also clears reboot/recovery state and starts workflows.
- ADB backup rejects the inspected system-UID APKs without a backup agent.
  `allowBackup=true` and targetSdk23 do not bypass the firmware's core-UID gate.
  No backup or compatibility override was attempted.
- `HttpCommon.getEcuConfig()` performs HTTP and writes its cache. Diagnosticsrv
  socket data and the 213 descriptions in `diagnostic_config.json` are IVI
  health errors, not the powertrain ECU inventory. Its 131 CAN logging routes
  must not be used as UDS request/response pairs.
- BydLogTool's common zip request unconditionally proceeds to upload and can
  delete older archives. Its bug-report collector changes logging switches and
  can remove source logs; the OTA selector deletes `/data/ota_package/cfg`.
  None was invoked.

BydLogTool's separate socket file export was **live-proven in one explicitly
authorized, bounded session** after the offline investigation above.
Its installed APK matches the OTA SHA-256
`589ec5b9555a0b64354aeee9393eb18ab9ca984ef3f0c6148d7a110eacbd97d5`.
The exported receiver starts three wildcard listeners, including a file protocol
with download/list/delete. ADB forwarding does not isolate these listeners.
The path filter checks a prefix rather than the canonical path. The fixed client
used that behavior to read only eight named cache candidates through an owned ADB
forward; it sent list/download only. The owner explicitly approved one session
of at most 35 seconds. A device-side 30-second STOP watchdog was armed before
START; host cleanup sent STOP after **0.561 seconds**. All temporary listeners
were absent afterward, the forward and newly created empty staging directory
were removed, the watchdog STOP was observed, and process/manager state and the
crash buffer were unchanged. This first approval was consumed; the second session
below received separate explicit permission. The stock server exposes write
operations to the network while running and is not a product data source.

Four files passed size/MD5 checks and were saved with mode0600 under
`approved-export-1/files/`: `needCheckEcuList.json`, `ECUVersion.json`,
`VehicleVersion2` (`V1.0`) and `SourceVehicleVersion2` (`V1.7.0`). The two strategy
paths, `SourceVehicleVersion` and diagnosticsrv `ecu_config.json` were reported
missing by the exporter. `needCheckEcuList` contained an empty `ecuList`; CDN
hosts there are not a dictionary catalog. No backend requests were made.

`ECUVersion.json` (39,890 bytes, SHA-256
`8e31658bc0387da5ea905595da8bb0d3df859bf7b172d3f80d9ce77f9ebd1d53`)
contains **225 distinct ECU IDs**, but the OTA writer retains all server candidates.
26 records have only stock negative marker `7F22`; 23 have only default-zero
values. Different supplier/variant candidates can have identical versions;
physical equivalence is not established without their address/DID tuples. Neither
the count nor a nonzero value alone proves installation or a fresh ECU reply.
Cache modification time was not retained, so freshness is unknown.

| Focused cached ECU identity (prefix `HTH-2021_`) | Software version | Software code |
| --- | --- | --- |
| `ECM_UAES_01200001` | `4.00.10` | `32440011E004000000` |
| `BMS_BYD_03300001` | `3.48.07` | `4A520011E00D00047B` |
| `FMCU_BYD_00100001` | `4.03.12` | `48370011E075000000` |
| `SMCU_BYD_00200001` | `4.00.06` | `48370011E086001100` |
| `SMCU3_BYD_00100001` | `4.00.06` | `48370011E088001100` |
| `ISG_BYD_00100001` | `4.03.13` | `48370011E0EA000000` |
| `VCU_BYD_00100001` / `VCU_UAES_00100001` | `4.08.22` | `32440011E07E007841` |
| `ESP_BOSCH_00100001` / `IPB_BOSCH_00100001` / `BSC_BYD_00100001` | `4.00.08` | `32330011DF0B000101` |

These are cached identities, not an address map or decoded DTCs. In particular,
the physical positions of SMCU/SMCU3 are not assigned from abbreviations alone;
the later [model-catalog crosswalk](#ecu-roles-and-reverse-catalog-crosswalk-2026-10-21)
provides explicit left/right names. The compact
sanitized projection is `research/vehicle-diagnostics/ecu-inventory-cache-20261018.json`.
The full-map candidate was controller prefs
`/data/user/0/com.byd.otaupdate/shared_prefs/share_data_prefs.xml`, specifically
`ecuDiagnosisResultListInfo`: the writer saves full `EcuListInfo` after a
successful check-version response, whereas `ECUVersion.json` drops addresses.
The second separately authorized session tested this candidate and three
related sources with `read_ota_inventory_export.py`. Its frozen SHA-256 was
`dff6abc828923873d00fe360e5081c092be2d3b7b527b151985f2c03f0f4c1c2`.
Raw preferences stayed in memory; only an explicit ECU/script-field projection
could be saved. The client had no arbitrary-path, upload, delete, ADB or ECU API.

| Second export source | Verified result |
| --- | --- |
| `/data/user/0/com.byd.otaupdate/shared_prefs/share_data_prefs.xml` | Read 705 bytes; `ecuDiagnosisResultListInfo` absent |
| `/data/user/0/com.byd.otaupdate2/shared_prefs/share_data_prefs.xml` | Read 510 bytes; `ecuDiagnosisResultListInfo` absent |
| `/data/ota_package/ota/result/upgradeInfo.json` | Missing according to exact parent listing |
| `/data/user_de/0/com.byd.spotinspection/shared_prefs/com.byd.spotinspection.xml` | Read 65 bytes; `RUNNING_TASK` absent |

The exporter completed normally, with zero projections saved; absent keys mean
missing evidence, not zero installed ECUs or zero DTCs. The source writer saves
the full-map preference later than `ECUVersion.json`, and `upgradeInfo.json`
requires a legal update strategy and successful download preparation. The
version-only cache therefore does not imply that either full-map cache exists.
No EOL script identifier was recovered from `RUNNING_TASK`.

Host STOP completed at **0.413 seconds** after watchdog arming. The device-side
30-second STOP was observed; all six default/custom export ports were absent,
the owned forward and new empty directory were removed, and process/manager
state and crash buffer matched baseline. `approved-export-2/session.json` and
`files/report.json` retain cleanup proof, source sizes and SHA-256 without the
discarded preference contents. Both single-session approvals are now consumed.
The complete offline research test suite passed **60 tests**. Product DTC
coverage remains BODY and low-voltage LBMS only.

**Remaining source-backed paths, not recovered data:** the OTA controller logs
the server's `getEcuList` response and shorter ECU-id/address/DID lines under
`OTA_GCY_UPGRADE_HttpApiService`, `DiagnosePresenter` and `CanDiagnostic`.
The 5,000-line passive logcat window filtered to these tags and the corresponding
GHY/unprefixed variants was empty; `/data/logs` was not readable by shell.
No logging switch or collector was activated. A whole JSON response is emitted
as one log call and may be truncated; individual ECU/route lines are a stronger
historical correlation source. On final diagnostic failure, the controller can
save per-candidate transcripts under `/data/ota_package/diag/diagnose/`; their
existence and contents on this car are unverified. See the passive capture in
`passive-route-logs.json` and source analysis in the discovery reports.

The exact origin of the full candidate map is the stock controller's metadata
POST `ota/otaService/getEcuList`. Its response model has route/DID fields but no
DTC dictionary. The request uses the stored vehicle version and upgrade UUID,
cached authorization and the head unit's TLS client identity. This does not
require a VDS/TIS account, but it is not an exposed local cache getter.
`UpdateOTAModel.loadEcuListInfo` is not safe to reuse as an isolated request:
shared retry/authentication handlers can log out, register/login, update fault
state and retry; the base observer can clear OTA caches and send reports on
specific API errors. The presenter additionally starts diagnosis on success.
Transport initialization can write a credential backup. No such workflow,
credential export or backend request was run. The precise separation needed
for one bounded metadata request is documented in
`captures/diagnostics-discovery-20261018/ota-get-ecu-list-isolation.md`;
a callable isolated implementation and its identity access are not established.

Route interpretation is also transport-specific: for `can`/`canfd`,
`ecuDiaId` represents hexadecimal request/response IDs; for `doip`/`ethernet`,
it is target/client unless `logicAddress` overrides it. `ecuDId` is the
software-version/software-code DID pair. The stock CAN decoder's extra leading
zero means already-padded IDs can be misparsed; a future importer must validate
canonical input instead of copying that quirk. The source review is retained in
`ota-route-semantics.md`. None of these schema findings qualifies a new address
for production DTC reads.

The bounded native follow-up (`native-next-local-route/report.md`) found no
additional matching ECM/BMS/drive/ABS address table. It confirmed the existing
213-entry IVI dictionary in the OTA's `diagnostic_config.json`; these descriptions
cover the head unit and its peripherals/software and must not be attached to
powertrain codes. `/data/ota_package/ota/ecuTmpInfo.json` appeared in static native
string initialization only; its reader/writer, schema and lifecycle were not
established. A path string alone did not justify another export session.

**Legacy fault API ruled out in this HAL.** The follow-up interpreted the exact
`auto.default.so` SHA `5d65fd3b430aec7f2e06496ba427e652613fef995c48c2d4066126dec9f446c7`
offline, with a bounded Python ARM64 interpreter and no native firmware execution.
`AutoID` still stores fault GET `0xCAF3B` and SET `0xC5A52`, but none of the three
complete registration tables registers them: CAN 2,775 calls, Toyota 2,287,
CAN-FD 11,604. Five independent park/OTA control registrations were recovered.
The full `setString` handler returns **−10011 before encoding or writing** for
this SET in all three variants. The generic codec merely copies `ids` unchanged;
it does not reveal an ECU selector table.

For GET, the mapper returns −10011 on a missing entry and leaves output
pointer/length untouched. `BYDAutoService::getBuffer` calls the HAL at `0x2d918`
then sets its own return to 0 at `0x2d928`, discarding the HAL error. This explains
why the earlier empty SDK response cannot establish even a successful cache
lookup. The exact intermediate JNI values were not recorded live, so the
error-suppression explanation remains a source-backed inference. The stronger
static fact is that **neither legacy fault FID has a route in these tables**.
Research script `research/vehicle-diagnostics/trace_fault_native.py` checks the
binary hash, full registration counts, control entries, setter return and codec
boundaries. Source/disassembly/JSON evidence is in `fault-protocol-followup/`.

**MCU requests carry their routes in the payload.** Independent extraction of
the owner's MCU application matched the existing September image exactly:
1,933,312 bytes, base `0x40000`, SHA
`6c277d1eb819b765d997554fc727835314b9477cae414aa806ea7cf78eda4d02`.
Its declared byte-sum checksum also matches. Correct extended RH850 decoding
(`V850:LE:32:v850e3v5`) allowed tracing past indirect-call instructions that the
older default decoder could not handle. These are offline OTA findings; the
installed MCU image was not read or compared in this pass.

- Version/code input handler `0x151F68` takes big-endian request ID from bytes 0–1,
  response ID from 2–3 and DID from 4–5. It has a special case for this MCU's own
  `741/749` and F194/F195; the general branch starts with `10 01` session control.
  It is not a passive enumeration getter. Completion publishes FID `99000053`.
- Generic EOL TX `AA00010A` dispatches through `0x16C840` to `0x15147E`, with the
  normal send path at `0x150888`. Request ID is bytes 1–2, payload starts at 4.
  The request supplies the address; no named-ECU lookup is established here.
- The separate VHDS path `AA000167 → 0x16D85A → 0x14C468` likewise takes the
  request ID from bytes 1–2. Its receiver at `0x14CBC6` matches request+8 before
  calling the ISO-TP parser `0x14CC0C`; that parser is not an address catalog.
- Functional ID `7DF` is explicitly treated as send-without-wait: helper
  `0x150528` returns 0, and the sender clears the active flag without arming a
  reply timeout. Receiver `0x1528C8` requires the active flag and matches exactly
  **saved request ID +8**, not the OBD responder range. This path therefore
  does not supply collect-all functional OBD discovery. No `7DF` request was sent.

Standard OBD Mode 03 could in principle provide emissions-related codes without
an OEM description dictionary; its CAN response also has a code-count byte
([ELM327 datasheet, p35](https://www.elmelectronics.com/wp-content/uploads/2017/01/ELM327DS.pdf)).
That protocol fact does not prove reachability through the head unit's broker,
and a standard-looking physical address is not an established ECM identity.
The generic transport limitation above prevents treating a functional scan as
the missing map. Existing BODY/LBMS reads are unaffected.

The MCU's own transport triple `7DF/741/749`, 14-service UDS table and 13-DID
table are preserved in `ecu-map-followup/mcu-structural-tables.json`; they are
self-diagnostics/update configuration, not the requested engine/BMS/ABS map.
Clear-text target identities/software codes and the known BODY/LBMS address
anchors did not identify such a map. This bounded result does not rule out
every encoded table in the image. The extended disassembly and route evidence
are retained in `ecu-map-followup/`.
`research/vehicle-diagnostics/trace_mcu_diagnostics.py` independently repeated
21 bounded ranges / 1,262 instructions plus three FID dispatch and three output
descriptor checks. The HAL interpreter repeat and all 73 host research/corpus
tests passed. No product code or car installation changed in this follow-up.

**Isolated metadata HTTP still has identity preconditions.** The deeper
`getEcuList` review confirmed OTA SafeKey function 10102, certificate selector 11
and private-key selector 12. Both native getters call `chipIsReady`, which can
wake the chip, initialize it and conditionally enter PIN change/recovery.
Avoiding Java's credential backup does not remove these lower-level effects.
The existing auth token/upgrade UUID are app-private; `LoginManager`'s memory
getter is not IPC and gives a new process no access to the running OTA process's
identity. No mutation-free way to obtain all inputs for a separate request was
established. No credential getter, recovery, backend request or new export
session was run. `ecu-http-followup/contract.md` and its 31-source hash manifest
retain the exact contract and unresolved gates.

Observation of an independently initiated stock check can supply short
ECU/route/DID logs. **Correction from the later scope audit:** the stock button
can automatically download and schedule forced installation; it is not a
diagnosis-only trigger. Do not start it merely to supply this observer with
data. **Correction from the later capture below:** app-level
logging is enabled in source, but global `log.tag=M` suppresses these messages
on the car; individual tags need a bounded enable/restore window. The plan is
`ecu-map-followup/passive-stock-observation.md`: correlate explicit identities
and response records, reject truncated/conflicting data and interleaved worker
threads, and retain the distinction between server candidates and responders.
A same-PID association is insufficient because the stock diagnostic pool has
three threads. The later saved HTTP prefix yielded partial candidate routes,
but none for the requested powertrain/ABS targets. New target DTC coverage and
factory descriptions remain unqualified; the diagnostic window must continue
to show those blocks as unread.

### Stock response prefix and alternate paths (2026-10-18)

A separate owner-authorized stock OTA check had already been captured in
`captures/ota-status-20261018/`; see [OTA findings](ota-update-findings.md).
This follow-up only reads its saved evidence. At14:38:38, `getEcuList` returned
status100 and a nonempty list. Android truncated the single log entry during
the ninth object. `extract_ota_ecu_prefix.py` recovers only the eight complete
objects with valid delimiters; it never repairs the tail or merges candidates
sharing an address. The sanitized projection is
`research/vehicle-diagnostics/ota-ecu-prefix-20261018.json`, with source SHA,
line33, timestamp/PID/TID and raw routing/DID fields. It remains candidate-only,
profile-unassigned, and ineligible for automatic product promotion.

| Candidate suffix after `HTH-2021_` | Transport | Exact address pair | Version/code DIDs |
| --- | --- | --- | --- |
| `Media_BYD_00200001` | CAN-FD | `741/749` | `F195/F194` |
| `ASSY_BYD_00100001` | CAN-FD | `5E7/5EF` | `F295/F294` |
| `F_ZCU_BYD_00100001` | CAN-FD | `722/72A` | `F195/F194` |
| `CDC_SOC_BYD_00100001` | Ethernet | `010/001`, logical target0010 | `F195/F194` |
| `CDC_BYD_00100001` | CAN-FD | `720/728` | `F195/F194` |
| `CDC_BYD_00200001` | CAN-FD | `720/728` | `C295/C294` |
| `R_ZCU_BYD_00100001` | CAN-FD | `747/74F` | `F195/F194` |
| `R_ZCU_BYD_00200001` | CAN-FD | `720/728` | `C495/C494` |

`ecuChannel` is preserved as server metadata, not assigned a physical CAN bus.
An Ethernet pair must not be interpreted as CAN IDs. These are server
candidates, not eight proven installed modules. The repeated `720/728` with
different DID namespaces reinforces why the existing product reports its DTC
source as **ECU0x728** rather than asserting an exact CDC/ZCU identity. None of
the complete entries is ECM, traction BMS, a drive controller, or ABS/ESP.
The partial ninth row is deliberately excluded.

The saved cycle ended with `versionSync104` and `checkVersion111`, not a
successful final strategy response. Source confirms `getEcuList.onSuccess`
passes its list to diagnosis in memory; the full preference map is written
only by `doCheckVersionSuccessData`. Therefore a successful list fetch alone
does not imply a new full-map cache. The error restore routine does not itself
erase an already existing `ecuDiagnosisResultListInfo` preference; that must
not be conflated with the separate delete-all-cache routine.

Failure transcripts can survive111 under
`/data/ota_package/diag/diagnose/<ecuId>.txt`, outside the cleared OTA directory.
They are written only for a nonempty final failed attempt with sufficient
storage, not successful candidates; successful frame text stays in memory.
A new check removes the previous diagnostic directory. One direct metadata-only
ADB `ls -ld` of the three fixed directory paths returned **Permission denied**
for each, without starting a check or changing the car. This neither proves
existence nor justifies another private-file export as a complete-map solution.

Three additional local paths were narrowed with exact source/binary evidence:

- **DiDiag:** transaction5 reaches `diagmgrsrv:0xC820`, a no-op that ignores
  diagnostic data and returns0 for UID<1002,10 otherwise. Earlier interface-only
  wording that it transmits data is corrected in `native-discovery.md`.
  `getDiagingId` and `DiagServArbStrategy` describe task arbitration. The separate
  modern UDS SDK has actual DTC/snapshot reads, but requires a target supplied by
  the caller; its client handle is not a discovered ECU. Proof:
  `didiag-map-next/README.md` and six verified Binder/vtable mappings.
- **Cloud:** MCU message532's recovered39 selectors/71 records/42 CAN IDs are
  status-bit checks after remote controls, not diagnostic routes. Separate
  `cloudctrlserv` command7002 carries up to two request/reply pairs and command
  payloads supplied by an incoming job; its worker sends dev1033/FID`AA00010A`.
  This is an executor, not a local inventory getter. No job was submitted.
  Proof: `cloud-map-next/mcu-selector-report.md` and `cloudctrl-report.md`.
- **EOL:** all40 JSON assets match the inspected APK. Its built-in low-power
  scenario has fixed code/version/station, but no requested subsystem map.
  The lone `0x123456` radar entry is not a qualified DTC definition. The40-method
  MQTT interface exposes no saved-task/catalog getter. Exact diagnostic script
  selection still requires upstream task metadata; no such task was invoked.
  Proof: `eol-map-next/report.md`.

No new DTC address was added to the product, and no ECU command, stock check,
BydLog session, credential getter or OEM request was initiated in this follow-up.
The offline prefix extractor, short-event parser and bounded stream filter are
in `research/vehicle-diagnostics/`. The parser keeps index/item/reset/route
anchors and PID/TID, preserves unresolved cross-thread associations, and breaks
correlation on malformed records, capture gaps or process changes. It never
turns initial item metadata into a positive ECU response. The filter retains
only accepted events, within 1024-byte lines / 4 MiB input / 20000 events, and
marks dropped structural data rather than silently joining across it.
The saved progress log has 43 candidate-index contexts but **zero ECU-identity
anchors and zero route lines**; it cannot fill the missing part of the HTTP
response. The whole 102-test host research suite passed, including malformed
JSON, transport separation, cross-thread ambiguity and privacy/gap handling.
An independent review reproduced the eight-object projection byte-for-byte.

The later bounded observer implementation and emulator acceptance are recorded
below. It has not collected a new cycle on the vehicle. This collection covers
GCY CAN routes, not Ethernet/DoIP or manufacturer DTC descriptions.

### Bounded observation and isolated metadata (2026-10-18)

**The stock Check/Update button is unsuitable for a diagnosis-only run.** In
the matched GCY source, the Settings callback returns false for download
confirmation (`CarSettingBroadcastPresenter:80–84`); a successful strategy can
start package downloading directly (`CheckVersionDownloadPresenter:1369–1379`).
All ten implementations across GCY/GHY return false. The earlier response111
only describes that completed cycle; it does not constrain a future response.

After download, the forced-strategy branch runs before the ordinary mode switch
(`UpdateOTAPresenter:677–680`). `VehicleOTAUtil:1155–1171` schedules an alarm for
`isForce="1"` when the packages are present, without checking night-auto or
asking for a new confirmation. The alarm dispatches appointment mode2, whose
countdown continues after30 seconds (`BaseVehicleOTAProcess:3628–3645`). Vehicle
conditions may prevent installation, but this is not a guaranteed no-install
boundary. No accessible fetch-and-diagnose-only stock entrypoint was found;
the internal `startDiagnose` requires an already obtained ECU map. Full source
anchors and hashes: `ota-route-observer-plan/stock-check-scope.md` and its two
JSON evidence files. A bounded logger does not bound the OTA workflow.

`tools/vehicle-diagnostics/OtaRouteObserver.java` and
`tools/observe_ota_routes.py` implement only observation. The helper owns one
FileLock, saves the two original log properties before changing them, streams
only GCY DiagnosePresenter/CanDiagnostic, and restores/verifies both originals
on its device deadline or nonce-matched stop. The host saves only sanitized
events and checks terminal state plus property readback. Maximum observation
is350 seconds, with a separate10-second emergency deadline; a hung native call,
SIGKILL or crash can leave unresolved restoration and blocks a fresh helper
session. Global logging, vehicle/OTA APIs and product APKs are untouched.

On `emulator-5580`, a6-second normal run restored empty originals. A second run
restored distinct `I`/`W` values after an explicit stop and excluded synthetic
private-looking fields from every saved output. Killing both the host runner
and its owned ADB client during an8-second run still produced device
`STOPPED/deadline`, both property readbacks and an exited owned logcat. Global
logging stayed unchanged; emulator originals were restored after testing.
These are Android lifecycle/filtering checks, not vehicle or ECU acceptance.
Evidence: `ota-route-observer-emulator-*`, with independent host-loss cleanup
proof;18 Java lifecycle tests and16 host-runner tests also pass. The existing
102-test offline research suite remains green. No stock check, new BydLog
session, credential getter or ECU request was run for these checks.

> **Superseded 2026-10-20:** the isolated metadata route subsequently succeeded
> with separate one-session authorization and returned all 225 candidates — see
> [Complete factory map](#complete-factory-map-and-remaining-powertrain-dtc-2026-10-20).
> The source analysis and earlier failed attempts below remain historical
> evidence; this does not make the stock Check/Update workflow suitable.

At this stage the separate metadata POST remained a possible route. Existing GCY backups are
`/data/user/0/com.byd.otaupdate/files/dcrt.crt` and `files/dkey.key`; the cached
token and UUID are in that package's `share_data_prefs.xml`. **Backups are
first-write-only:** `SecurityKeyUtil:187–189` leaves an existing file unchanged,
while the normal client uses newly returned chip data. Thus today's successful
stock connection does not prove the backups are current, paired or usable.
Reading existing bytes and using a fresh in-memory TLS client can avoid the
chip getter's side effects. Missing/invalid identity, token or version must stop
the operation. UUID is an exception: the stock getter uses an empty string when
absent, as confirmed in the second-session follow-up below. There is no exported
stock API for this isolated request. The second session proved that the two
existing backup files form a valid RSA pair with a currently valid client
certificate; it did not establish backend acceptance or equality to current chip
material.

The minimal request is one fixed `getEcuList` POST with existing version/UUID,
proper TLS identity/trust and cached token. It must omit stock retry, login,
registration, diagnosis, versionSync and checkVersion code. Any resulting list
is server candidates only, not proof of installed variants or OEM DTC text.
`ecu-http-followup/backup-only-feasibility.md` records28 source hashes and the
remaining acquisition boundary. BydLogTool's file server binds all interfaces
and exposes deletion; using a loopback client does not remove that exposure.
The two completed cache-export approvals do not cover a new credential session.

An isolated client is now implemented in
`tools/vehicle-diagnostics/EcuMapMetadataProbe.java`, with the separate host
orchestrator `run_ecu_map_metadata.py` beside it. It reads exactly the existing
GCY cert/key/prefs and SourceVehicleVersion2 through the owned local export
session, keeps them in head-unit memory, validates RSA pair/certificate/XML,
and emits only `INPUTS_READY`. The host must stop the export, verify all six
known ports closed and receive terminal watchdog acknowledgement before
sending the nonce-bound `GO`. Only then can the client make one fixed-host
TLS1.2/1.3 metadata POST. No vendor credential/OTA API, auth recovery, redirect,
retry or ECU request is included. The strict complete-response projection
retains ten routing fields, preserves CAN/ethernet semantics and remains
ineligible for automatic product promotion. Unknown response fields and known
token/UUID/version values are excluded from output.

The first authorized attempt used production client source SHA
`8aaebd2ec636e5220f4df892d78beb8947f27632a1c77e0dabdf4c81c25a844a`;
the production dex is
`53eb647d8c64f9801a800dae684c5eff7e50856c8d75db34585f6eb5231c1444`.
Both JVM and Android emulator passed84 synthetic assertions. On Android, the
actual client main also read four synthetic transfer responses, reached READY,
and rejected stdin EOF as `go_wait/go_eof` before HTTPS. This used an owned
emulator-only reverse and local fixture server, both cleaned up. Real files,
credentials, BydLogTool and the OEM backend were not used. Evidence:
`ecu-map-metadata-android-final.json`, the final production/test build manifests,
and `ecu-http-followup/metadata-probe-preparation.md`.

The orchestration tests cover early GO refusal, protocol/privacy failures,
ownership and delayed START. A review found and fixed a late-start race: the
watchdog now remains responsible for a pending START until its compensating
STOP settles; it cannot retire and leave a later listener unowned. Ownership
uses nonce, PID and process birth time. At30 seconds it begins stopping; this
is **not a guaranteed35-second closure if Android hangs**. Unverified closure
retains the lease and blocks GO; a changed process owner is not stopped. The
client itself bounds inputs to10 seconds, GO wait to10 seconds, post-GO work
to25 seconds and total lifetime to less than45 seconds.
Final independent review and all19 host boundary tests passed. A read-only
car preflight also passed: existing transport/UID/profile, exact installed GCY
and BydLog APK identities, idle export lifecycle and closed known export ports.
It did not stage the client, read credentials or send a backend request.
`ecu-map-metadata-prepared-session.json` preserves the historical preparation
snapshot, linked to the consumed approval in
`ecu-map-metadata-authorization-1.json` and actual outcome below. It is not an
unused authorization or an instruction to retry.

The separately approved session ran once and stopped with
`helper_output_invalid` **before GO and before any HTTPS request**. The host
verified export closure, terminal watchdog, lease removal and unchanged stock
APK identities; an independent read-only postcheck confirmed closed known
ports, no helper and idle diagnostic ownership. No ECU map was obtained. The
first rejected stdout line was deliberately not retained, so the exact progress
through the four private inputs is unknown; this is not proof that no input was
read. Evidence: `approved-ecu-map-metadata-1/session.json` and
`independent-post-check.json`. This one-session approval is consumed.

Same-car tests then isolated a reproducible firmware incompatibility without
BydLog, private files or an OEM request. Invalid-argument launch emitted valid
JSON, and all84 synthetic preparation assertions passed. A loopback-only
server serving four synthetic inputs reproduced four extra lines before READY:
`[com.mediatek.cta.CtaAdapter]:check permission begin!`. The matched OTA's
`core-oj` source confirms an unconditional `System.out.println` in
`CtaAdapter.isSendingPermitted` called by `Socket.connect`, including loopback.
The same mechanism also applies to the later HTTPS socket. Source and synthetic
evidence confirm this failure mechanism; the discarded original line prevents
byte-for-byte attribution of the first session's refusal. The host parser also
passed203 pipe-fragmentation cases, including197 guaranteed split reads.

The revised Java client captures its protocol stream before silencing Java
`System.out` and `System.err`, then emits only its existing three protocol
messages through that captured stream. It does not relax the host parser or
skip arbitrary lines. Native file-descriptor output is not suppressed by this
Java boundary and would still cause a closed-gate refusal. The second session
used production source SHA `f7d8cb4e334aab2d3ab14daf17cf67b9dad414e6c3d33501464704985b0fadde`;
production dex SHA is
`eb84ae8e0b93bec4bdf152e82c62d15ad25610b24225beca0264267a7e1d966c`.
All86 synthetic assertions pass on JVM and the car, including stdout/stderr
privacy canaries. On the car, the unchanged strict host parser now accepts
READY from the full four-input loopback fixture, then accepts only the expected
`go_wait/go_eof` error. No GO is possible in that fixture. It leaves no helper,
staged dex or open listener; all19 host boundary tests remain green. Evidence:
`approved-ecu-map-metadata-1/car-synthetic-transport.json`,
`car-output-isolation-qualification.json`, the two `*-output-isolated/build.json`
manifests and `ecu-http-followup/stdout-failure-followup.md`. Original source
snapshots and the first production dex remain preserved. This correction was
used in the separately approved second session described below.

**Second isolated metadata session: valid pair, missing preference.** It ran
once and stopped at `input_validate/prefs_missing` before READY/GO/HTTPS. The
frozen source control flow establishes that all four fixed transfers passed
integrity checks, the certificate passed parsing/date/client-usage checks, and
local RSA signing/verification proved the certificate/key pair. XML parsing
also completed. The old `found.size() == 2` requirement then failed: at least
one of `user_token` and `upgradeUuid` was absent. It does not identify which,
and token/version validation and TLS context construction were not reached.
No ECU map was acquired; no private input values were retained on the host.
The host verified export closure, terminal watchdog, released lease and
unchanged APK identities. An independent read-only check confirmed closed
ports, no helper/lease, and `sys.diagmgrsrv.task_bk = r@-1_0&w@-1_0`.
Evidence: `approved-ecu-map-metadata-2/session.json`, `outcome.json`,
`independent-post-check.json` and the exact `executed-sources/` snapshots.
The second one-session approval is consumed even though HTTPS was not reached.

**Source-backed UUID correction, qualified with synthetic data only.** The
matched GCY client's `VehicleOTAUtil.getCacheUpgradeUuid()` uses `""` as its
SharedPreferences default. Its request JSON builder preserves this value as
`"uuid":""`. Several normal lifecycle paths explicitly remove that preference;
the ordinary Settings `triggerQuery → forceClearUnsafe` path itself does not
prove a deletion. The exact selected key names and preferences path were
correct. `prefs-source-followup.md` in the second-session evidence directory
records the call paths, 15 source hashes and the distinction between supported
client behavior and unproved backend acceptance.

The new helper defaults only absent/empty UUID to the same empty string,
without generating an identifier or writing a preference. Missing token now
stops as `token_missing`; empty/invalid/case-insensitive literal `null` token
stops as `token_invalid`, consistent with the stock token getter. Token,
identity and real version remain mandatory. Malformed, duplicate, wrong-type,
whitespace-only or oversized UUID still refuses. Privacy checks exclude the
empty default from secret matching while retaining nonempty token/UUID guards.
The four input paths, export lifecycle, GO gate, TLS, endpoint and one-attempt
limit are unchanged. No login, registration or anonymous fallback is added.

Current source SHA is
`538953eef5da82e29b51b0b57037c8b5686d37148bed9702bec622ffed057231`;
production dex SHA is
`bae34ffa745e643111267882435aae9418ac188742c7088a2d9061d135167afb`.
All102 synthetic assertions pass on JVM and the car; all19 host tests pass.
The car's four-input loopback fixture deliberately omits UUID, reaches READY
through the strict host parser, and rejects forced EOF before GO/HTTPS. It
uses no BydLog/private files/OEM request and leaves no helper, test dex or
listener. Independent static review found no actionable issue. Evidence:
`approved-ecu-map-metadata-2/car-absent-uuid-qualification.json`,
`final-review.json`, and the two `*-optional-uuid/manifest.json` builds.
`ecu-map-metadata-prepared-session-3.json` now preserves the historical
preparation, linked to its consumed authorization and actual outcome below.

**Third session: HTTP response reached, headers refused.** The owner approved
one more session. It ran once at13:04:53–13:04:59 UTC and reached `GO` only after
export closure and terminal watchdog acknowledgement. The exact frozen control
flow establishes local token presence/syntax and version syntax, TLS context
construction, successful TLS handshake with hostname verification, one POST
written/flushed, and an HTTP/1.0 or HTTP/1.1 status200 response. It then stopped
with `https/http_headers`. That code covers an oversized header block, a
missing/invalid header name delimiter, or a duplicate case-insensitive header
name. Raw headers were deliberately not retained, so the exact branch is
unknown; do not label a particular header as the cause. The body/application
status was not parsed, and HTTP200 does not prove application token acceptance,
ECU-map availability or that TLS requested a client certificate.

All four inputs stayed in head-unit RAM. No map or private values were saved.
The host verified closed export, terminal watchdog, removed lease and unchanged
stock APK identities. The independent13:12:58 UTC read-only postcheck found all
six known export ports closed, no helper/lease, unchanged APK hashes and idle
`sys.diagmgrsrv.task_bk = r@-1_0&w@-1_0`. Evidence is
`approved-ecu-map-metadata-3/session.json`, `outcome.json`,
`independent-post-check.json`, and `executed-sources/`. This third one-session
approval is consumed; no retry or new export is authorized.

**Cross-reader overlap found during the owner's coordination check.** The
other active chat, `Найти снятие лимита 130 км/ч`, reads existing IDC/ADS logs
with `tools/read_idc_log_export.py`. It does not implement this DTC window or
getEcuList request, but both tools control the same BydLogTool process and its
global START/STOP broadcasts, despite using different port sets. Its host
lifecycle ran approximately13:04:50–13:05:23 UTC, overlapping this session. Its
report records early host STOP, a later watchdog STOP, closed ports and
unchanged diagnostic/crash state. This establishes overlapping host lifetimes,
not simultaneously open listeners or a cause for `http_headers`.

The neighbor claims `/data/local/tmp/logtool` with plain `mkdir`. Our old
stage accepted that directory when it already existed, so merely checking
closed ports missed another reader's pending watchdog. Our host now refuses
an occupied shared directory at preflight and uses atomic `mkdir` again at
stage, before arming/START. A late conflict does not STOP the other reader or
remove its files; our own lease remains for investigation. Successful ownership
is held until our watchdog is terminal. All21 host tests pass, including real
local-shell contention and refusal while another reader has closed ports.
This correction has not been run on the car. Its scope is our host only.

> **Superseded 2026-10-20:** the shared-directory correction was used in the separately approved successful metadata session described in [Complete factory map](#complete-factory-map-and-remaining-powertrain-dtc-2026-10-20). The earlier approvals remain consumed; this does not establish universal mutual exclusion with other exporters.

The directory gate is not a universal proof of another reader's cleanup:
the IDC reader's error path may remove its staging directory even when
watchdog verification fails. Therefore no further live export is scheduled;
future use still needs cross-reader coordination and confirmed retirement of
any earlier owner. The other chat's files were not changed. Evidence:
`approved-ecu-map-metadata-3/coordination.json`, `coordination-validation.json`
and `coordination-review.json`.

### Readable DTC codes and native detail view (2026-10-18)

> **Superseded 2026-10-20:** the native detail view and `DtcDescriptions` were removed with the first window; the scanner decodes formats in `protocol/` and takes code descriptions from the public catalog ([vehicle-diagnostics-architecture.md](vehicle-diagnostics-architecture.md), 4). The decoding findings below stand.

The count response's format identifier **00** selects ISO15031-6 / SAE J2012-DA,
per [AUTOSAR SWS_Dem_00936](https://www.autosar.org/fileadmin/standards/R23-11/CP/AUTOSAR_CP_SWS_DiagnosticEventManager.pdf).
The first two bytes encode the base code; the last byte is the failure type,
not the UDS status byte. Prefix conversion is independently documented in
[ELM327 datasheet, p34](https://www.elmelectronics.com/wp-content/uploads/2016/07/ELM327DS.pdf).
The product applies this only to format00; other formats retain raw codes.

| Raw | Readable | General meaning available |
| --- | --- | --- |
| `516006` | `C1160-06` | BYD base meaning unknown; algorithm-based failure |
| `C29D87` | `U029D-87` | General reference: lost communication with NOx sensor A; missing message |
| `C12187` | `U0121-87` | General reference: lost communication with ABS control module; missing message |
| `A14802` | `B2148-02` | BYD base meaning unknown; general signal failure |
| `A25000` | `B2250-00` | BYD base meaning unknown; no subtype information |
| `A25B00` | `B225B-00` | BYD base meaning unknown; no subtype information |
| `C22987` | `U0229-87` | General reference: lost communication with heated steering wheel module; missing message |
| `C22A87` | `U022A-87` | Base meaning unverified; missing message |

Sources: U0121 and U029D appear in
[Ford OBD system description, p176](https://www.fordservicecontent.com/ford_content/catalog/motorcraft/DOBDSM1701-2017.pdf).
U0229 appears in the scanner manufacturer's own
[Autel DTC reference, printed p101 / PDF p102](https://www.autel.com/br/u/cms/www/201910/19050511czji.pdf);
this is a general reference, not a Denza service definition. U022A is absent
from that reference and remains unknown.
Failure types00/02/06/87 appear in [GM's 2025 Colorado electrical manual,
pp2-249, 2-250, 2-255](https://www.gmupfitter.com/wp-content/uploads/2025/02/MY25-Colorado-Electrical-BBM.pdf).
These are general references, **not an obtained BYD software-specific dictionary**.
In particular U029D does not establish an installed NOx sensor, U0229 does not
establish an installed or faulty heated steering wheel module, and U0121
reported by 728 is not an ABS ECU scan. All eight retain their original status28
and snapshot time; no code was reclassified as a current fault.

The native DTC list shows five records per page, each with the readable code,
short meaning and state. Selecting a record opens its original/raw identity,
failure type, qualified source, reporting ECU and timestamp. A separate view
explains all eight independent status bits; unsupported means «Не поддержан»,
not «Нет». Unknown base meanings remain unknown. Warning details distinguish
an unavailable raw0, no reply and a valid off value. Engine and HV/drive panels
show this quality for every indicator.

«Каналы» exposes the dated225-record version inventory (49 default/negative),
the existing eight partial server candidates, and the unresolved CDC/ZCU
identity of720→728. These are display-only evidence, not executable diagnostic
targets. The UI no longer names720→728 a proven body controller. «Охват DTC»
still lists EMS, HV BMS, ABS/ESP and front/rear drives as unread. Selecting these
nodes cannot silently send the720 request: only that already qualified address
and LBMS/12V nodes offer a read. U0121 in the ABS notes remains conditional on
that actual format00 record. Neither generic meanings nor candidate addresses
expand the two-target allowlist.

The enriched debug APK builds and all43 diagnostic tests pass (2270 tests in
the full Denza Apps suite, no failures or errors). Fourteen native
emulator fixtures compare with the corresponding Luminofor boards at
1.20–1.47% differing pixels, mean0.76–0.91/255, consistent with font rendering
and antialiasing. The actual Android detail/status screenshots were visually
inspected; text and controls fit. Evidence:
`captures/diagnostics-ui-enriched-20261018/board-comparisons.txt` and the sibling
screenshots. Emulator interaction checks cover returning from a meaning/status
card to the same DTC page, candidate-map pagination without a read target, and
HV warning selection opening unread coverage without a720 fallback. The final
inventory caption says «Версия не получена» for the49 unavailable version
records; these are not49 faults. This is emulator UI validation; the new APK is
not installed or accepted on the car. Final APK SHA:
`285005ef39ac5fbbd990159147e66d0dd0c9958657906813b8bc23646a415ed2`.

Evidence: compact source/hash matrices and live warning capture under
`research/vehicle-diagnostics/{engine-evidence-20261018,powertrain-evidence-20261018,chassis-research-20261018,lamps-20261018,dtc-reference-20261018}.json`.
Ignored `captures/diagnostics-ecus-20261018/` retains agent reports, decoded
sample, permission-denied results, raw GET replies, pinned APK, native images
and comparisons. No new diagnostic APK was installed on the vehicle.

Validation: full standard unit suite and debug assembly passed: **2,248 tests,
38 diagnostic tests**, zero failures/errors, plus ten offline decoder checks.
Eight native boards compare within text antialiasing: 1.12–1.40% of pixels
above the 24-level difference threshold, mean 0.71–0.88/255. Emulator UI checks
exercised code detail/back, subsystem → coverage without an unintended body
read, LBMS target selection and actual Service → diagnostic window → Service.
Independent review found and fixed the unconditional ABS-code reference;
regressions cover unread, empty, other-code and other-format snapshots.
Final pinned debug APK SHA-256:
`3a063fa375d106c9806ba70de3bcebe921d8f4c33a1fb2ae654577ce03c57d8d`.

### Plain-language inputs: reporting module, status labels, insulation (2026-10-18)

Collected for a plain-Russian diagnostic screen. Read-only corpus and web work;
nothing was sent to the car.

**What `720 → 728` does.** The installed SpotInspection's
`res/raw/low_power_script.json` attaches `reqCanId` 1824 / `rspCanId` 1832
(`0x720` / `0x728`, CAN-FD 500k/2M) as `LA_BCM`, 左车身域控制器, the left body
domain controller. The stock spot-check script
`assets/pl_gcy_uds_-script-20240821.json` (vehicle not stated) sends 96 cases to
`0x720`, grouped by its own system names: windows 8, locks/anti-theft 4,
handles 4, child lock 2, flaps 2, seat adjustment 16, seat heating/ventilation 8,
seat lights 4, charge-port light 6, lights 6, ambient 4, backlight 12, mirrors 6,
steering column 8, wipers/washers 4, horn 2. `0x747` gets 47 cases of the same
kinds plus the sunshade/sunroof (the right domain), `0x726` the tailgate,
`0x770`/`0x771` seat lights. The OTA route map's `CDC_BYD_*` and
`R_ZCU_BYD_00200001` rows at `720/728` stand as listed and «CDC» remains
unexpanded; the function map makes «левый блок кузова» the working name for
the screen. U0229 (heated steering wheel) fits a module that owns the steering
column, but that is still not a BYD definition. Status: firmware.

**BYD's own status labels.** The same script's `dtcStatusList` gives mask 8 =
历史故障 (historical) and mask 9 = 当前故障 (current), for `LA_BCM` and LBMS
alike. `MatchUtils` takes the largest mask fully contained in the status byte,
so `0x28` reads «历史故障». The stock tool prints codes without a dash
(`C116006`, `MatchUtils.java:430`), the same conversion as ours. With mask FF,
bits 0, 1 and 6 at zero mean the test ran this operation cycle and passed:
«сейчас в норме». LBMS's mask `0x09` supports only bits 0 and 3, so its zero
records cannot say whether its tests ran this cycle.

**Code ownership.** SAE J2012 leaves B1/B2 and C1/C2 to each manufacturer:
another make's B2148 or C1160 says nothing about BYD. No public SAE meaning was
found for U022A; older generic lists mark U022A–U022F reserved. No BYD
dictionary for the eight codes exists in `captures/` or `reverse/`; the only
`dtcList` is the `0x123456` radar placeholder already noted above.

**Insulation.** `0x43A00018` on device 1039 is `GB_BMC_INSULATION_VALUE`
(CAN-FD branch, `BYDAutoFeatureIds.java:27189`). «GB» is the GB/T 32960
national telemetry set, which encodes insulation resistance as 0–60000 kΩ in
1 kΩ steps, `0xFFFE` abnormal and `0xFFFF` invalid
([GB/T 32960.3-2016](https://openstd.samr.gov.cn/bzgk/std/newGbInfo?hcno=674DE45C0AD3DE2CD75B9C4CD8ED57C1)).
If this field follows it, 13051 / 14128 are 13.1 / 14.1 MΩ. Likely, not
proven on this car. The product's accepted range 0..65534 would pass `0xFFFE`
as a value. A second field, `GB_INSULATION_VALUE` `0x26200010`, is unread.
Status: firmware; the engineering unit remains unqualified.

> **Updated 2026-10-21:** the cited2016 edition was withdrawn on2025-11-01;
> its public record does not bind this firmware to a wire edition. Native tracing
> now proves `min(raw & FFFF,60000)`, including FFFE/FFFF →60000 and an unchecked
> getter status. That collector cannot validate the sensor or preserve those
> sentinels — see [new evidence](#unattended-child-and-buffer-qualification-2026-10-21).

### Deeper sources for a diagnostic screen (2026-10-20)

The question was whether the car offers more than lamps, doors, tyres and one
body module's memory. Corpus and the N9 sweep only; nothing was read on the
Z9GT.

**GB/T 32960 set.** `com.byd.feature.gb.Gb` in this car's SceneModesPlatform
(SHA matches the car, [window-control-findings.md](window-control-findings.md))
names about 200 fields. Among them:
- about 40 alarm flags: cell over/under-voltage, cell inconsistency, cell
  temperature difference, class-A over-temperature, thermal runaway, SOC jump,
  pack mismatch, overcharge, insulation/leakage (three levels), HVIL, DC-DC
  temperature and state, motor-controller over-temperature, over-current and
  over-speed per controller, brake system, charging system;
- per motor: speed, actual torque, DC current, IGBT and winding temperature,
  operating mode;
- probe numbers of the hottest and coldest pack points, cell and probe counts,
  SOC correction factors;
- lifetime discharged and regenerated energy
  (`GB_ACTUAL_DISCHARGE_ENERGY_TOTAL` `0x2D000818`,
  `GB_ACTUAL_RENEWABLE_ENERGY_TOTAL` `0x2D000828`);
- chassis, energy and IA CAN status.

On the N9's 2026-10-16 full sweep (`captures/washer-n9-20261016/*/fids.tsv`,
device 1039) the nine `0x438000xx` flags all returned `1`, SOC-high and
SOC-low alike, so `1` is not «alarm» there; the `0x244000xx` and
`0x262000xx` flags returned `0`; thermal runaway returned `0xFFFF`. Per-field
encoding is unproven and the HAL table has not been traced. Status: firmware;
N9 live; Z9GT unread.

**Service-life fields are not life.** On the N9 every fluid-life field (oil,
transmission oil, brake fluid, both coolants, shock-absorber and differential
oil; device 1001 `0x4BB000D0`–`0x4BB000F0`, device 1023 `0x38D000xx`) returned
`0x9B` = 155. One value above 100 for every fluid reads as a default, not a
remaining life. Engine oil level answered `0x26`, unit unknown.

> **Superseded 2026-10-20:** only the `BODY_*_LIFE` fields and the `0x38D00050`–`0x38D00070` group return 155. `SETTING_REMAINING_LIFE_OF_*` (1023 `0x38D00008`–`0x38D00028`) are real and differ per car: Z9GT oil 75, transmission oil 50, brake fluid 99, long-life coolant 50 — see [Z9GT catalog sweep](#z9gt-catalog-sweep-2026-10-20).

**Absent.** The catalog has no refrigerant pressures or temperatures, valve
positions or pump speeds: no thermal-loop picture is possible from it.
Compressor power (1005 `0x3D800038`) answered 672 on the N9.

**Error memory beyond two modules.** Stock evidence gives three more read-only
candidates: `0x747/0x74F` (right body domain: 47 stock script cases and the
OTA map's `R_ZCU`), `0x726` (tailgate) and `0x722/0x72A` (`F_ZCU` in the OTA
map). Engine, traction BMS, drives and ABS/ESP still have no address.

> **Superseded 2026-10-20:** all three answer, and the engine ECM answers at `7E0` with five more modules at `7E2`–`7E7`; see [OBD and more modules](#obd-and-more-modules-through-the-same-transport-2026-10-20). The later [remaining-ECU run](#remaining-ecu-identities-and-chassis-dtc-2026-10-20) also identified ABS/ESP, SRS, EPS and ADAS and read their DTC memories.

What settles it for the Z9GT: one `tools/washer_capture.py` sweep, parked:
scalar GETs only, about four minutes for two passes over every catalogued pair.

### Z9GT catalog sweep (2026-10-20)

On 2026-10-20, 12:04:45–12:08:25 UTC, `tools/VehicleStateSnapshot.java` (scalar
GETs 5/7 only) read all 10,003 catalogued pairs from 48 devices twice: 20,006
replies, profile 170, CAN-FD, P, 0 km/h. The firmware hashes match the corpus.
`autoservice` (PID 128) and `com.byd.avc` (PID 4634) were unchanged, the crash
buffer was empty before and after, and the staged dex was removed. Of the
10,003 pairs, 4,956 answered an integer, 656 a float, and 4,391 a no-data value.
Evidence: `captures/diagnostics-sweep-20261020/`.

What answered and goes beyond lamps, doors and tyres:

| Group | Fields | Z9GT | N9 | Reading |
| --- | --- | --- | --- | --- |
| Pack balance | 1014 `0x44600010` / `0x44600030` | 3333 / 3337 mV | — | spread 4 mV |
| Pack temperature | 1014 `0x44700010` / `0x44700020` / `0x44700038` | 53 / 58 / 54 | — | 13 / 18 / 14 °C after −40 |
| Pack health | 1014 `0x44400028` `STATISTIC_BATTERY_HEALTHY_INDEX` | 99 | 98 | BMS estimate |
| Insulation | 1039 `0x43A00018` / `0x26200010` | 14429 / 19675 | 13798 / 18649 | two channels, likely kΩ |
| Sensors | 1039 `0x43A00010` probes; cells (1001) | 14 probes; 166 cells | 17 | — |
| Service life | 1023 `0x38D00008` oil, `…10` transmission oil, `…18` brake fluid, `…28` long-life coolant | 75 / 50 / 99 / 50 | 55 / 46 / 18 / 65 | percent; their `_FUNC_FLAG` = 1 |
| Blade-pack coolant | 1023 `0x38D00020` | 100, `_FUNC_FLAG` 0 | 7 | not offered on this car |
| Drive units | 1039 `0x46406018/10`, `0x285001A8/B0`, `0x2D000180/88` | 8 / 9 / 7 / 7 / 5 / 5 | — | °C, cold car |

The GB/T 32960 alarm set answers, but carries no news on a healthy car. All
nine `0x438000xx` flags read `1`, SOC-high and SOC-low alike. The `0x244000xx`,
`0x262000xx` and `0x26C000xx` flags read `0`, except `GB_DC_SYSTEM_FAILURE`,
which reads `1`. Thermal runaway is `0xFFFF`. Every one of these equals the
N9's value. Its encoding is still not established, so a tripped flag can be
seen only as a change from this baseline. Per-motor torque, speed and current
are `0` when parked. The newer `0x230008xx` alarm IDs named by SceneModesPlatform
are not in this framework's catalog and were not read.

Of 126 nonzero fault-, alarm- and warning-named fields, almost all are enums
equal to the N9's; none is a known active fault. Status: live.

### OBD and more modules through the same transport (2026-10-20)

The owner approved one read-only probe: OBD and UDS to `0x7E0`–`0x7E7`, plus
DTC reads from `0x747`, `0x726` and `0x722`. The aim is to find out whether the
head unit's transport reaches the engine and other powertrain modules.
Engine, VCU, BMS, motor controllers, ISG and ESP/IPB are present in the
cached version records, but their addresses are not.

Plan: `research/vehicle-diagnostics/ObdAddressReadProbe.java`, shell UID, the
qualified device-1033 envelope (`0A`, request ID, length, UDS bytes), a fixed
address list and four allowed payloads only: OBD `01 00` (supported PIDs),
OBD `03` (stored emission codes), UDS `19 01 FF` and `19 02 FF`. `03` and
`19 02 FF` go only to an address whose `01 00` or `19 01 FF` answered
positively. No `10`, `11`, `14`, `27`, `28`, `2E`, `2F`, `31` or `3E`, no
functional `7DF`. The probe takes the shared `denza-dtc-read.lock`, checks the
manager baseline before every request, aborts on preexisting traffic and
stops on a 150-second watchdog. Car in P at 0 km/h.

Expected, and what falsifies it:
- The transport forwards any request ID, as the MCU trace says, so every
  request gets `TRANSPORT_RESULT 0`. A non-zero result means the MCU
  allowlists IDs.
- If the gateway routes `0x7E0` to the engine, `0x7E8` answers `41 00 …` and
  `59 01 …`. If not routed or vacant, the first frame carries result 3 or 4
  (no reply). Result 2 (`7F …`) means a module is there but refuses that
  service.
- `0x747`, `0x726` and `0x722` answer on `+8` like `0x720`, if they are on the
  same reachable bus.

**Run 1, 2026-10-20 12:4x UTC: the transport reaches the powertrain.** All 25
requests got `TRANSPORT_RESULT 0`. Every reply came from request + 8, single
frame. PIDs and `task_bk` were unchanged, the crash buffer was empty and the
dex was removed. Evidence: `captures/diagnostics-obd-20261020/`.

| Request → reply | OBD `01 00` | UDS `19 01 FF` | Follow-up |
| --- | --- | --- | --- |
| `7E0 → 7E8` | `41 00 BE3EA813`: PIDs 01 03 04 05 06 07 0B 0C 0D 0E 0F 11 13 15 1C 1F 20 | `59 01 FF 00 0102` (258 with any status bit) | `03` → `43 00`, no stored emission codes; `19 02 FF` → NRC `13` |
| `7E1 → 7E9` | result 3, no reply | result 3 | — |
| `7E2 → 7EA` | `41 00 88180001`: PIDs 01 05 0C 0D 20 | `59 01 FF 00 034A` (842) | `03` → `43 00`; `19 02 FF` → NRC `13` |
| `7E3 → 7EB` | NRC `11` | `59 01 09 01 0000`: 0, mask 09, format 01 | — |
| `7E4 → 7EC` | result 3 | result 3 | — |
| `7E5 → 7ED` | `41 00 98180001`: PIDs 01 04 05 0C 0D 20 | NRC `11` | `03` → `43 00` |
| `7E6 → 7EE` | NRC `11` | `59 01 09 01 0000`: 0 | — |
| `7E7 → 7EF` | NRC `11` | `59 01 09 00 0000`: 0 | — |
| `747 → 74F` | — | `59 01 FF 00 0002` | `19 02 FF` → `517009/28`, `930E01/28` = C1170-09, B130E-01, both historical |
| `726 → 72E` | — | `59 01 FF 00 0000`: 0 | — |
| `722 → 72A` | — | `59 01 FF 00 0000`: 0 | — |

What this shows:
- The MCU forwards any request ID, and the gateway passes the head unit's
  requests to the engine's OBD address and to six more modules.
- `7E0` is the engine OBD responder by its PID set. `7E2` and `7E5` are two more
  OBD responders, unnamed. `7E3`, `7E6` and `7E7` are UDS-only and unnamed.
- A mask-FF count is not a fault count: it includes codes whose only set bits
  mean «not yet tested».

Status: live. Not yet read: confirmed-mask lists, live PIDs, module names.

**Run 2 plan (`detail`), same guards.** 69 fixed read-only requests, each to a
responder that run 1 found:
- single OBD PIDs from each module's own `01 00` bitmap, plus `01 20/40/60`,
  `07` (pending) and `0A` (permanent) for `7E0`, `7E2` and `7E5`;
- UDS `19 01/02` with masks `08` (confirmed) and `04` (pending) for `7E0` and
  `7E2`;
- identification DIDs `22 F197` (system name), `F18A` (supplier) and `F195`
  (software version) for the six `7Ex` modules, and `22 F197` for `720`, `722`,
  `726`, `747` and `762`.

`allowed()` in the probe is the whitelist: OBD `01`/`03`/`07`/`0A`, UDS `19 01`,
`19 02`, `22 F197/F18A/F195`, and nothing else. Expected: confirmed lists far
smaller than 258/842; F197 names the modules, which settles the `720/728`
CDC-or-BCM question as well. VIN (`F190`, OBD `09 02`) is deliberately not read.

**Run 2 result, 2026-10-20: real engine diagnostics are reachable.** All 69
requests got `TRANSPORT_RESULT 0`, all replies were single-frame, and pre/post
checks were unchanged (PIDs 128/3578/4634, `task_bk` baseline, empty crash
buffer, dex removed). Decoded by `research/vehicle-diagnostics/decode_obd_probe.py`
into `research/vehicle-diagnostics/obd-20261020.json`. Engine stopped, parked:

| Module | What it answered |
| --- | --- |
| `7E0` engine ECM, supplier `UAES` (`F18A`) | PID 01: MIL off, 0 codes; readiness raw `07 F5 F4` (misfire, fuel, components and catalyst complete; evap, O2 sensor, O2 heater, EGR/VVT and the gasoline particulate filter incomplete; C4/D4 is GPF in SAE J1979, once listed as reserved). Coolant 9 °C, intake air 12 °C, MAP 101 kPa, throttle 16.9 %, load 0, trims 0, rpm 0, O2 sensors bank 1 S1+S2. Further PIDs 21–80 listed by `01 20/40/60`. Mode 07 and 0A: 0. UDS confirmed (`19 02 08`): none. F195 `9C4A19031900` |
| `7E2` OBD responder (VCU by PIDs 49/4A pedal) | PID 01: MIL off, 0; PID 05 28 °C; 07/0A: 0; UDS confirmed: none; F195 `9F76190B1401` |
| `7E5` OBD responder | PID 01: MIL off, 0; PID 05 8 °C; 07/0A: 0; no UDS 19 or 22 |
| `7E3`, `7E6` UDS, mask 09 | 0 codes; F195 `9D78190A190C` and `9D79190A1E0D`: one family, consecutive |
| `7E7` UDS, mask 09, format 00 | 0 codes; F195 `87F71A011402` |
| `720`, `722`, `726`, `747`, `762` | `F197` not supported (NRC 31) |

- `19 01/02 04` (pending) got no reply within 4 s from `7E0` and `7E2`. The
  confirmed-mask (`08`) reads work, so a mask-FF count of 258 or 842 is
  untested codes, not faults.
- No module answers `F197`, so names stay inferred. `7E3`/`7E6` behave as a
  pair, matching the cached SMCU/SMCU3 with identical versions. `7E7` matches
  a BMS by behaviour (mask 09). None of this is proven.

  > **Superseded 2026-10-20:** `F194` names them. `7E3` is FMCU (front motor controller) and `7E6` is ISG, not the rear pair. `7E7` is BMS and `7E2` is VCU — see run 4 below. The later [remaining-ECU run](#remaining-ecu-identities-and-chassis-dtc-2026-10-20) names `7E5` through OBD `09 0A`: `BSCM-BrakeSystem`.
- The instrument's engine lamp FID `0x28700026` read `0` (invalid) in today's
  sweep; on 2026-10-18 it read `1`. The ECM's own MIL bit is off.
- Still without an address: ABS/ESP/IPB, SRS, ADAS. Their IDs are not in
  `7E0`–`7E7`.

  > **Superseded later on 2026-10-20:** `782` is the ESP/IPB/BSC identity,
  > `7F1` SRS and `712` ADC/ADAS. All three answer DTC reads. `7E5` calls
  > itself `BSCM-BrakeSystem`; the physical relationship between its OBD
  > interface and UDS `782` has not been established.

**Run 3 plan (`ident`), owner-requested, same guards.** Ten fixed read-only
requests: `22 F187` (spare part number) and `22 F191` (hardware number) to
`7E2`, `7E3`, `7E5`, `7E6` and `7E7`. These are the modules whose role is so far
inferred. Expected: ASCII part numbers that name or group the modules. The
same numbers on `7E3` and `7E6` would confirm a pair. NRC `31` means the DID is
not offered. VIN and serial (`F18C`) are not read.

**Run 3 result: F187 and F191 identify nothing.** All five modules rejected
`F187`: NRC `10` from `7E2`, `11` from `7E5`, `31` from `7E3`, `7E6` and `7E7`.
None answered `F191` within 4 s. Checks before and after were unchanged.

**Run 4 plan (`ident2`).** `22 F194` (supplier software number) to `7E0` as the
control and to `7E2`, `7E3`, `7E5`, `7E6` and `7E7`. BYD's OTA route map reads
F195/F194 for identification. The OTA cache keys software codes by name: ECM
`32440011E004000000`, BMS `4A520011E00D00047B`, FMCU `48370011E075000000`,
SMCU `48370011E086001100`, SMCU3 `48370011E088001100`, ISG
`48370011E0EA000000`, VCU `32440011E07E007841`. If F194 returns these codes,
it names each module, and `7E0` must return the ECM's code.

**Run 4 result: `F194` names five modules exactly.** Each returned the
software code that the OTA cache stores under one name, and the control
matched:

| Request → reply | F194 | Cached identity | Module |
| --- | --- | --- | --- |
| `7E0 → 7E8` | `32440011E004000000` | `ECM_UAES_01200001` | engine ECM (control) |
| `7E2 → 7EA` | `32440011E07E007841` | `VCU_BYD/UAES_00100001` | vehicle control unit |
| `7E3 → 7EB` | `48370011E075000000` | `FMCU_BYD_00100001` | front motor controller |
| `7E6 → 7EE` | `48370011E0EA000000` | `ISG_BYD_00100001` | starter-generator controller |
| `7E7 → 7EF` | `4A520011E00D00047B` | `BMS_BYD_03300001` | traction battery BMS |
| `7E5 → 7ED` | NRC `11` (no `0x22`) | — | OBD only; later `09 0A` names it `BSCM-BrakeSystem`, below |

This run did not identify the rear motor controllers (SMCU `…E086…`,
SMCU3 `…E088…`) or ESP/IPB (`32330011DF0B000101`). Later reads found
ESP/IPB at `782`, below. Lack of an answer does not establish where the rear
controllers are routed.

`726/72E` is `B_ZCU_BYD_00100001`, the rear zone controller, not only the
tailgate. The ninth row of the stock `getEcuList` log names it and retains
`ecuDiaId=726/72E`, `ecuReprogramType=canfd`, in
`captures/ota-status-20261018/phase1-live-check-sanitized.txt` line 33.
`ota-ecu-prefix-20261018.json` left that row out as truncated. These specific
fields are complete; a software DID pair is not retained in that partial row. The
corpus gives no static address for the rear motor controllers, ESP/IPB, SRS,
EPS or `7E5`. Checks before and
after were unchanged, and the crash buffer stayed empty.

Status at the end of run 4: live. Ten addresses answer DTC reads through this transport:
`720`, `722`, `726`, `747`, `762`, `7E0`, `7E2`, `7E3`, `7E6`, `7E7`; `7E5`
answers OBD.

### Remaining ECU identities and chassis DTC (2026-10-20)

The continuation recovered the Claude session «Диагностика» and resumed after
its four OBD/UDS runs. The missing static table was not treated as evidence
that an ECU was unreachable. Addresses from the diagnostic app developer's
[BYD scan map](https://carcoding.app/en/ecu/brand/byd/) were only candidates.
Discovery used one `22 F194` per fixed candidate. The five DTC targets were
then reidentified before reading faults, and ECM was the control in every run:
81 F194 requests in total. Returned bytes were matched exactly against the
existing stock `ECUVersion.json`. Generic names were not imported.
Aliases in the cache remain aliases, not extra installed modules.

Seven bounded runs, **94 requests**, checked **70 distinct F194 addresses**
(including the known ECM control). **33 returned a software code; 32 matched
the cache**, including ECM and ADS. The unmatched `741` returned
`4B450011E0930034E2`; it remains outside the cache match set.

> **Identified 2026-10-20:** `741/749` is the head unit itself, through its MCU. The stock route
> list names it `HTH-2021_Media_BYD_00200001` (`captures/ota-status-20261018/phase1-live-check-sanitized.txt`
> line 33), and ProductLineOTA's `res/raw/media_softcode_mapping.json` (pulled from this car) lists
> `4B450011E0930034E2` as the `mcu` module of the «denza» media software whose combined code
> `4B450011E093003401` is the one `ECUVersion.json` keeps. The cache misses it because it stores the
> combined code, not the MCU's.

The full qualified
address/code/alias list and per-request results are in
[`remaining-ecus-20261020.json`](../research/vehicle-diagnostics/remaining-ecus-20261020.json).
This is an observed route set, not a complete count of physical ECUs.

#### Chassis and ADAS: identities and DTC read

| Request → reply | Module / retained cache aliases | F194 | DTC support mask / format | Records matching `09` |
| --- | --- | --- | --- | --- |
| `782 → 78A` | ABS/ESP: `ESP_BOSCH`, `IPB_BOSCH`, `BSC_BYD` | `32330011DF0B000101` | `FF` / `00` | **0** |
| `783 → 78B` | electric steering: `REPS_BYD`, `DPEPS_TRW` | `43430011DE6F000000` | `FB` / `00` | **0** |
| `7F1 → 7F9` | airbags: `SRS_VEONEER` | `54500011E011000012` | `FB` / `00` | **0** |
| `712 → 71A` | driver-assistance controller: `ADC_BYD` | `48450011DF83000000` | `09` / `01` | **0** |
| `7F2 → 7FA` | front radar: `MRR_BYD/WHST/chengtech` | `474E0011DF70000000` | `09` / `00` | **0** |

Each DTC read first rechecked the exact software code, then issued `19 01 09`
and `19 02 09`. Count and list both explicitly returned zero. Mask `09`
selects **testFailed OR confirmedDTC**: these results say nothing about
pending-only or not-tested-only records. There is no claim that every monitor
has run. ADS reports DTC format `01`, which must not be decoded as generic
SAE format `00`. No manufacturer fault dictionary was recovered.

ADS `712/71A` had already been identified in the separate
[ADS investigation](adas-operating-limits.md#идентификация-конфигурации-ads)
using owner byte `05`; this run independently reached it with owner `0A`.
Its saved server route is **DoIP** target `071A`, client `001`. That is not a
CAN request/reply pair. A future full factory map must interpret its transport
before using an address.

#### Other matched addresses

| Request address | Factory cache identity, without guessing a physical role from the address |
| --- | --- |
| `701` | `IPA_BOSCH` |
| `711` | `SWS_BYD/JOYSON` |
| `713`, `742` | `MSRF_BYD`, `MSRZ_BYD` |
| `723` | `INS_BYD` |
| `724` | `PDCMB_BYD` |
| `725` | `CS_BYD` |
| `733` | `WCT_BJYG/TEME/ADAYO` |
| `743` | `PDC-VCU_FDP/BYD`, code `48370011E002066441` — distinct from VCU `7E2` |
| `750`, `7B5`, `7D2` | `DUCU_BYD`, `PUCU_BYD`, `MLUCU_BYD` |
| `751` | `AMP_BYD` |
| `754` | `HFRM_BYD` |
| `760`, `761`, `7C2`, `7D4` | corner-radar family, shared code `474E0011DF82000000` |
| `766` | `PTC_HGTECH` |
| `770`, `771` | `FL_L_BYD`, `FL_R_BYD` |
| `7A7` | `CCS_BYD/SCPA_BYD` |
| `7B3` | `ACC_BYD` |
| `7B4` | `TMIM_TUOPU` |
| `7C6` | `RRCP_BYD` |
| `7D6` | `SEC_BYD` |

All response IDs in this run were request +8, matching the MCU receiver.
The stock DevTools `EcuVersionManager` labels the four radar routes LFCR/RFCR/
RRCR/LRCR respectively; F194 alone cannot distinguish their physical positions
because all four have the same code. These additional modules were identified,
but their DTC memories were not read in this pass.

#### `7E5` identifies itself through standard OBD

After UDS `22` had returned NRC `11`, the fixed OBD plan read `09 00`,
`09 04` and `09 0A` (supported information, calibration identifier, ECU name).
Mode `09` ECU-name semantics are documented by
[ToyotaInfoTech RAMN](https://github.com/ToyotaInfoTech/RAMN/blob/main/docs/userguide/diag_tutorial.rst)
and [py-obdii](https://py-obdii.readthedocs.io/en/latest/api/modes/mode_09.html).
No VIN or serial was requested.

- Supported information bitmap: `14400000` (`04`, `06`, `0A`).
- Calibration ID: ASCII `4000825042253153`.
- ECU name: ASCII **`BSCM-BrakeSystem`**, with trailing zero padding.

Thus `7E5` is a brake-system OBD interface, not an unidentified traction
controller. Whether it and UDS `782` are two interfaces of one physical unit
is **unproved**; do not count them as two physical brake controllers.

#### Guards, evidence and remaining gap

`tools/vehicle-diagnostics/RemainingEcuReadProbe.java` runs only as shell,
holds the shared `denza-dtc-read.lock`, observes the RX channel for two seconds,
and checks profile170, P, zero speed and the idle manager **before each request**.
The native Binder getter layouts (tx5 integer, tx7 float) were independently
verified in `libbydauto.so` before the run. Every plan starts with the known
ECM F194 control; DTC targets have additional identity gates. Callbacks are
copied, serialized, bounded and checked for sequence, ID, DID/subfunction and
count/list agreement. The independent deadline is 90 seconds (70 in the first
identification build), with no retry.

All seven runs retained P/zero speed, manager baseline and PIDs
`autoservice=128`, `SpotInspection=3578`, `com.byd.avc=4634`.
Crash buffers stayed empty; listeners unregistered, helper processes exited
and their own dex files were removed. The installed app hash was unchanged
before/after each run. No APK was installed, no session/clear/unlock/write/
routine was issued, and neither BydLogTool nor a cloud request was used.
Raw traces, exact executed source/dex hashes, plans, pre/post checks and
independent offline reports remain under
`captures/diagnostics-remaining-20261020/`.

**Missing at the end of this pass:** routes for SMCU `48370011E086001100`, SMCU3
`48370011E088001100` and DCOBC `48350011E04D000100`.

> **Superseded later 2026-10-20:** the complete factory map and subsequent live reads identify these as `744/74C`, `745/74D` and `7E4/7EC`; all three returned DTC lists — see [Complete factory map](#complete-factory-map-and-remaining-powertrain-dtc-2026-10-20).

None of those codes appeared among the 70 checked F194 addresses.
Timeouts at candidates such as `780/785/797` do not prove absence from the car,
and no address outside a source-backed candidate list was swept.
The separate ADS metadata runs received 225 server records but retained only
the ADC projection; the other routes cannot be reconstructed from its response
hash. The remaining path is a transport-aware complete factory route projection
or an exact local route source, rather than another assumption from a generic
address label. Earlier one-session identity/export approvals are consumed.

Research now demonstrates DTC reads at **15 addresses** in total, plus the
OBD-only brake interface `7E5`. Product coverage is unchanged: the packaged
reader still allows only `720` and `762`, and the service window is parked
behind `-Pdiagnostics`. These findings have not been promoted into the UI.

> **Superseded 2026-10-20:** the packaged two-address reader and its window are removed; the scanner's
> catalog covers 68 addresses behind the same `-Pdiagnostics` row ([vehicle-diagnostics-architecture.md](vehicle-diagnostics-architecture.md)).

### Complete factory map and remaining powertrain DTC (2026-10-20)

**The three remaining routes and their DTC list access are now live-proven.**
[`factory-ecu-map-20261020.json`](../research/vehicle-diagnostics/factory-ecu-map-20261020.json)
retains the complete sanitized server projection;
[`factory-powertrain-dtc-20261020.json`](../research/vehicle-diagnostics/factory-powertrain-dtc-20261020.json)
records the subsequent on-car requests, replies, source hashes and cleanup.
The 225 server records include variants, not 225 installed ECUs. Neither file
automatically expands the product's allowlist.

#### One successful metadata session

The owner separately authorized one session of the corrected RAM-only client.
Exactly four existing inputs were used on the head unit; its single
`getEcuList` followed confirmed export closure and a terminal watchdog.
All 225 rows passed the Java projection and independent host validation.
No token, UUID, private key or raw HTTP headers were retained on the computer.
No retry, OTA workflow or ECU command was part of this metadata session.
Its one-session approval is consumed.

The independent postcheck found all six export ports closed, no helper or lease,
an idle diagnostic manager, matching stock APK identities and no crash records.
The known old IDC watchdog artifact and active START/STOP processes were absent
before execution. This describes this session, not a universal exporter lock.
Exact pins, authorization, result and postcheck:
`captures/diagnostics-next-20261020/metadata-*` and `approved-map-session/`.

Preparation fixed three source-backed compatibility problems:

- Repeated unused HTTP fields, including `Set-Cookie`, are discarded; duplicate
  body-framing/type/encoding fields still refuse. Errors now distinguish header
  syntax, size and duplicate critical fields without exposing values.
  Repeated fields are legal under [RFC9110 §5.3](https://www.rfc-editor.org/rfc/rfc9110.html#section-5.3).
  This fixes a client defect; discarded headers from the earlier failed session
  prevent claiming that it was that session's exact cause.
- Five real ECU identifiers contain ASCII spaces (`rear CPD radar`,
  `front CPD radar`, `FULIN PM`). Both validators preserve them verbatim.
- Stock DoIP accepts `logicAddress` without `ecuDiaId`; these rows retain null
  pair fields instead of an invented CAN pair.

133 assertions passed on the JVM and head unit with synthetic data, 23 host
tests passed, and independent replay checked nine retained real routes plus
all 225 cached identifiers before execution. The subsequent complete response
verified all 225 actual server records. Server `ecuChannel` values remain raw
metadata; the MQTT envelope has no physical-bus selector.

#### Live identities and DTC lists

| Module | Request → reply | Actual F194 | Server transport/channel | DTC result, mask09 |
| --- | --- | --- | --- | --- |
| SMCU `HTH-2021_SMCU_BYD_00200001` | `744 → 74C` | `48370011E086001100` | canfd / 1 | `59 01 09 00 00 00`, then `59 02 09`: count0, empty list, format00 |
| SMCU3 `HTH-2021_SMCU3_BYD_00100001` | `745 → 74D` | `48370011E088001100` | canfd / 1 | Same explicit count0 and empty list, format00 |
| DCOBC `HTH-2021_DCOBC_BYD_00100001` | `7E4 → 7EC` | `48350011E04D000100` | can / 2 | Count got gateway result3; separate list returned `59 02 09`, empty; format unknown |

Each identity matches the exact ECU ID's entry in the stock version cache.
SMCU/SMCU3 are the two rear-drive controllers; their physical left/right
assignment is not established by these replies. The later
[model catalog](#ecu-roles-and-reverse-catalog-crosswalk-2026-10-21) names SMCU
left rear and SMCU3 right rear through the matched factory identities.
All three server records give
software DIDs `F195/F194` and hardware DID `F193`. This run read F194 only;
cached software versions are not newly measured F195 results.

The first fixed plan made nine requests. After the rear-drive reads and DCOBC
identification, DCOBC's count timeout stopped the dependent list. A separate
three-request plan repeated ECM control and DCOBC identity, then read only the
DCOBC list with an independent 64-record/16-frame bound. Its actual `59 02 09`
proves an empty list for mask09; `PLAN_DONE` alone would not prove a DTC read.
Since `19 02` does not return DTCFormatIdentifier, DCOBC's format remains null.
A future nonempty list must not be decoded as SAE format00 by analogy.

Both runs held the shared lock, observed the passive window and checked
profile170/P/zero speed/idle manager before every request. Service PIDs remained
`autoservice=128`, `SpotInspection=3578`, `com.byd.avc=4634`; app hash, guards
and empty crash buffers were unchanged. Both helpers unregistered and exited,
and their own staged dex files were removed. No APK was installed or product
file modified. Mask09 means testFailed OR confirmedDTC, not every monitor
status or an overall health assessment.

#### Factory descriptions remain a separate missing input

The route metadata does not supply an applicable OEM DTC dictionary. The
readable external SpotInspection `files/Scripts` directory was checked again
and had no script files. Private OTA transcript paths were permission denied;
their existence was not determined.

The matched SpotInspection `MatchUtils.java:299–399` compares complete 24-bit
codes against `data.attach[].dtcList[{code,desc}]`. Lines369–376 suppress codes
in `MATCH_WHITES`: an empty inspection report can hide stored DTCs. Our offline
importer reads the full attachment list without that display filter. The
script/metadata schemas have no software-applicability matrix; even a recovered
pair needs evidence connecting it to the ECU software. Its 16 tests passed.

The bounded corpus/public pass found no applicable factory dictionary.
The missing input is an OEM diagnostic manual/DTC index with revision and
applicability, or an existing matching EOL plaintext script, metadata and
selection evidence. Generic SAE meanings, cluster warnings, EV-only manuals
and route metadata do not fill this gap. The seven-target software-code
specification, original source anchors and hashes are retained in
`captures/diagnostics-next-20261020/dictionaries/`; this specification was not
sent to anyone. No unsupported factory descriptions were added.

### Factory-map candidates on this car (2026-10-20)

**22 of the 37 candidate CAN routes are installed; the cache predicted every one.**
The complete factory map lists 37 CAN routes the scanner's catalog did not
hold. They were server variants, not proven modules. Two owner-approved
runs, `factory-candidates1` and `factory-candidates2`, read them with
`RemainingEcuReadProbe`: one `22 F194` per address, ECM `7E0` as the control,
18 and 17 addresses, 37 requests, no retries. `715` (INFC) and `785` (RMCU)
were not asked again: each had already got result 3 that morning. The five
DoIP routes are outside the transport.

The written expectation was that a real code in `ECUVersion.json` means the
module is fitted, and a zero placeholder means it is not. It held for every
address:

| Request → reply | Server route name(s) | F194 | Cache |
| --- | --- | --- | --- |
| `582 → 58A`, `796 → 79E` | RWS-L, RWS-R | `484B0011DF02030100`, `484B0011DF02020100` | match |
| `590 → 598` | AQDM | `4A440011DE014A0101` | match |
| `595 → 59D` | R-NCM | `48490011A401162104` | match |
| `597 → 59F` | IDC (two variants) | `4B4D0011DE01140600` | match |
| `5A0 → 5A8` | VR | `4A500011DE01130000` | match |
| `5A7`, `5B0`, `5B1`, `5B2` | RR/RL/FR/FL SDR (BYD, WHST) | `48490011DF01050000`, `…01040000`, `…01030000`, `…01020000` | match |
| `5E0 → 5E8` | rear CPD radar (Valeo, Mindcruise) | `52420011DF9E000000` | match |
| `5E4 → 5EC` | MCPA (KOSTAL) | `514A0011DE7B000000` | match |
| `5E7 → 5EF` | ASSY | `4B450011DF990042E2` | not in cache: the passenger computer's MCU, below |
| `5F4 → 5FC` | RCP | `48580011DF01750000` | match |
| `772 → 77A` | FRL | `48330011DEE3000000` | match |
| `790 → 798` | ETC (Genvict, Vanjee, Julitech) | `4B580011AD01990000` | match |
| `7B0 → 7B8` | HUD | `48330011DE80000103` | match |
| `7F3 → 7FB` | UWBMC (KOSTAL, UAES) | `3244001182C7000000` | match |
| `7F4`–`7F7` | UWB anchors LF/RF/LR/RR | `3244001183C8002000`, one code for all four | match |

Gateway result 3 (no reply) at all 13 placeholder routes: `591` PLR, `592` PLL,
`596` ERP, `5A6` Mid-CPD-R, `5B7` DPAC1, `5C0` DPAC2, `5C4`/`5C5` RL lamps,
`5C6`/`5C7` TL lamps, `5D7` front CPD radar, `5F5` WCM_RM, `716` BWA.
As before, result 3 means not reachable through this envelope, not proof that
nothing is fitted. Every reply came from request + 8 in one frame. The
gateway's second word in a result-3 frame varies (`01FA`–`01FD`, `0200`,
`05E3`–`05E9`); its meaning is not established.

`5E7` answers F194 with `4B450011DF990042E2`, which is not the `ASSY_BYD`
cache code `4B450011DF99004201`. The server lists ASSY's software DID as
`F294`, so a refusal of F194 was the expected outcome. Instead the code is the
`EcuSoftwareCode` of the MCU container `mcu_42_2_3.xcd` in the passenger
computer's own OTA, whose `CanReqID 1511` / `CanRecID 1519` are exactly
`5E7`/`5EF` ([HUD findings 14.10.4](hud-projection-findings.md)). This is the
head unit's `741` pattern: the cache keeps the combined software code (`…01`),
and the MCU answers with its own code (`…E2`).

Roles are not established by the replies. HUD, ETC, the child-presence radar
and the UWB key follow from their route names. RWS, AQDM, R-NCM, IDC, VR, SDR,
MCPA, RCP and FRL remained unnamed in this identification pass; the
[2026-10-21 model-catalog crosswalk](#ecu-roles-and-reverse-catalog-crosswalk-2026-10-21)
now supplies their roles. The four UWB anchors share one code, like the
corner radars, so only the route names separate their positions.

Both runs held the lock, observed the passive window and checked profile 170,
P, zero speed and the idle manager before every request. Before and after:
`autoservice=128`, `SpotInspection=3578`, `com.byd.avc=4634`, crash buffers
empty, app hash unchanged, own dex removed. Runs took 22 s and 17 s. No APK
was installed. No session, clear, DTC, VIN or other DID was requested.

Product consequence: all 22 joined `EcuCatalog`. The 13 `5xx` responders are
listed one by one in `DiagnosticWhitelist`, beside the `700`–`7F7` range. These
identification runs did not read their DTC memories; the later
[first autoscan](#the-scanner-on-the-car-first-autoscan-2026-10-20) did. Evidence:
[`factory-candidates-20261020.json`](../research/vehicle-diagnostics/factory-candidates-20261020.json),
with plan, builds, traces, pre/post checks and the decoder in
`captures/diagnostics-candidates-20261020/`.

### ECU roles and reverse catalog crosswalk (2026-10-21)

**A model-specific diagnostic-tool catalog supplies the missing role names and
new search targets; it does not supply an OEM fault dictionary.** Xtool's
[official Function Lists](https://en.xtooltech.com/function-lists) links its
[Denza catalog](https://www.xtooltech.com/funcquery/index.html?#/Diagnosis?lang=CN&configName=E2PRO_CN_V01&packId=PS_TENGSHIEV&packVer=6.61).
The public query was scoped to brand `腾势汽车`, model `Z9GT/Z9GT DM`, package
`PS_TENGSHIEV`, version `6.61`. Two pages contain 100 + 72 unique rows, all
with that exact model and `V6.61`; their system-name set equals the separately
scoped selector's set. Earlier unfiltered selector lists are not model evidence.
Version 6.61 identifies the scanner catalog, not the car's software.

[`xtool-z9gt-dm-20261021.json`](../research/vehicle-diagnostics/xtool-z9gt-dm-20261021.json)
retains all 172 system names, source row/pointer, capability flags and an exact-token
crosswalk to the owner's factory map. Supplier alternatives, LIN children and
optional configurations are separate rows: **172 is not an installed-ECU count**.
Xtool gives no CAN addresses or F194. The role evidence is a cross-source join:
observed F194 → owner cache identity and factory route → matching catalog token
and supplier name. It does not qualify a particular software revision's behavior.

The following resolves all 25 entries currently built with `unknown(...)` in
`EcuCatalog`, plus the two rear-drive sides. Product labels have not been changed
in this research pass (updated 2026-10-21: the product took them the same day — the
catalog in `99235dc9`, the diagnostic systems in `d991bf99`; see
[architecture 5](vehicle-diagnostics-architecture.md#diag-arch-catalog)). Exact codes, factory IDs and source row IDs are in
[`ecu-role-evidence-20261021.json`](../research/vehicle-diagnostics/ecu-role-evidence-20261021.json).

| Address / token | Model-specific role |
| --- | --- |
| `744` SMCU; `745` SMCU3 | Left rear drive; right rear drive |
| `743` PDC-VCU | Power-domain controller / VCU; distinct route from VCU `7E2` |
| `724` PDCMB; `725` CS | Tailgate AR projection sensing controller; combination switch |
| `713` MSRF; `742` MSRZ | Front-passenger; driver electric belt pretensioner |
| `750` DUCU; `7B5` PUCU; `7D2` MLUCU | Driver; front-passenger; middle-row-left universal MINI controller |
| `754` HFRM; `7D6` SEC | Key RF receiver; smart-entry controller |
| `7A7` CCS/SCPA | Shift-mechanism control panel (source names SCPA; both cache aliases retained) |
| `7C6` RRCP; `5F4` RCP | Rear refrigerator control panel; refrigerator control panel |
| `582` RWS-L; `796` RWS-R | Left; right rear-wheel steering |
| `590` AQDM; `595` R-NCM | Air-quality detection module; **right** NFC module |
| `597` IDC; `5A0` VR | Vehicle motion controller; vehicle refrigerator |
| `5A7` RR_SDR; `5B0` RL_SDR; `5B1` FR_SDR; `5B2` FL_SDR | Right rear, left rear, right front, left front **door radars** |
| `5E4` MCPA; `772` FRL | Middle-row control-panel assembly (KOSTAL); front interior light |

“Middle row” is the source wording, not proof of a third seat row or a mounting
position in this car. `IDC_D2` is a separate catalog token, not an automatic match
for the owner's `IDC_BYD_00200001`. RCP here is refrigerator control; an older
BYD Han's similarly abbreviated rear control panel is not transferable.

**Reverse lookup finds child identities behind existing routes.** Four massage
controllers and four rear lamps have nonzero cached software versions and a
separate code DID on an already answered parent. These are proposed identification
reads in the original inventory, not independently established DTC memories.

> **Superseded 2026-10-21:** all eight code reads now match the cached identities
> through their four parent routes — [live result](#unattended-child-and-buffer-qualification-2026-10-21).
> This establishes neither eight new CAN addresses nor separate child DTC memories.

| Child family | Parent request → response | Code DID | Cached version |
| --- | --- | --- | --- |
| SMF_L, front-left massage | `750 → 758` | `F8C4` | 4.00.04 |
| SMF_R, front-right massage | `7B5 → 7BD` | `F8D4` | 2.00.11 |
| SMR_L, rear-left massage | `7D2 → 7DA` | `F8E4` | 2.00.12 |
| SMR_R, rear-right massage | `7D2 → 7DA` | `F8F4` | 2.00.12 |
| RL_L, left combination rear lamp | `726 → 72E` | `F204` | 2.00.05 |
| RL_R, right combination rear lamp | `726 → 72E` | `F214` | 2.00.05 |
| TL_L, left tail lamp | `726 → 72E` | `F064` | 2.00.05 |
| TL_R, right tail lamp | `726 → 72E` | `F234` | 2.00.05 |

The lamp `*_00100001` routes `5C4`–`5C7` had zero cache placeholders and returned
result 3; these `*_00200001` routes instead go through B_ZCU `726` and have real
cached versions. Retrying the same absent 5xx route misses this alternative.
[`ecu-child-identification-candidates-20261021.json`](../research/vehicle-diagnostics/ecu-child-identification-candidates-20261021.json)
preserves the exact eight factory IDs, expected cache codes, request/prefix bytes
and source hashes. Seven further INS/FRL/CDC subidentities remain unbound: do not
assign their DID order to the numbered catalog names. The CDC/LA_BCM conflict at
`720` is not resolved. DoIP tuples remain DoIP, never CAN request/response pairs.
The historical product whitelist rejects these child DIDs, and
`RemainingEcuReadProbe` assumes F194. The separate allowlisted
`ChildIdentityReadProbe` now implements exactly these eight reads; the original
manifest stays immutable as its build input.

**Reverse lookup also supplies stock signal search terms.**
[`stock-signal-read-candidates-20261021.json`](../research/vehicle-diagnostics/stock-signal-read-candidates-20261021.json)
retains 30 property/native-FID pairs with source path, line, SHA-256, mapper and
read evidence. Four have a concrete stock getter; 25 matched the older resolved
CAN-FD TSV. The deeper pass below resolves the remaining five in the actual
framework and adds type/access declarations for all 30. None was queried in
these passes, and no physical ECU-to-FID attribution is inferred.

- Rear steering: actual left/right angles `0x0FE004B8` / `0x0FE004C4`, validity
  `0x0FE004B7`, status `0x1EA01548`, native dysfunction **`0x0FE00448`**.
  Correction from the deeper pass: `0x0FE00048` is the DiCar **property key**, not
  the CAN-FD native FID. All five names resolve through `AdasMapper` and the
  actual framework initializer, despite being absent from the older TSV.
  The profiles declare double for the angles and int for validity/status/fault;
  native reply types, units, scale, status enums and live availability remain open.
- Suspension: four actual heights `0x19500008/14/20/2C`, four alternative `_109`
  heights and four lift heights; adjustment process `0x2580002C`, actual level
  `0x25800044`, fault `0x1A70000A`, adjustable status `0x25800114`, actual mode
  `0x37F00008`, and three DISUS availability/limit states. Do not assume millimetres
  or select the height family without runtime evidence. Stock mode getter maps
  1/2/3 to comfort/sport/race when configured for three modes. Stock drift UI treats
  process 2 as lowering, and process 3 **with level 1** as completed; this is not a
  complete general enum. Its fallback level 2 is not a measured value.
- Four electric-door obstacle signals `0x39100088/8A/8C/8E` and dual-NFC operation
  result `0x19C33030` are contextual leads. They do not independently identify a
  particular SDR or R-NCM fault, nor decode its DTC memory.

The generic stock path is `ICarPropertyManager.getProperty(propertyKey)` returning
`Response<CarPropertyValue>`; its status and raw type must survive parsing. A
registered name does not establish app permission, an `int` value, a valid zero
or a usable native `autoservice` device/transact combination.

**What this public source cannot decode.** Xtool flags 167 rows for ECU information,
91 for DTC reading and 81 for live data; all 172 mark freeze frame unavailable.
All 61 LIN-labelled rows are identification-only in this catalog. These are
scanner-support flags, not hardware capability limits; they must not disable an
already proved head-unit read or override DCOBC's observed `19 01` timeout.

The inspected frontend schema defines DTC/live/freeze fields as `SupportEnum`.
Only actuator/special-function fields link named details. A public metadata lookup
of IDC row 1377 returned its 67 action titles: height and damping valves, pressure,
spoiler and posture, supporting the broad motion-controller role. They contain no
request bytes, passive-getter definition, PID/DID, scaling or fault descriptions.
[`xtool-public-capabilities-20261021.json`](../research/vehicle-diagnostics/xtool-public-capabilities-20261021.json)
retains the schema, counts and titles for corpus searches. No actuation was performed.

The remaining dictionary path still needs a software-applicable original DTC
document and, for FMCU/ISG format01, a display-code-to-raw-code binding. Role names
and support flags do not close those gaps. Raw public responses, requests, exact
filters, independent review and source audit are under
`captures/diagnostics-ecu-roles-20261021/{cabin,chassis,manual,root}/` (ignored).
This pass made zero vehicle requests and changed no product code or allowlist.

### Deep inventory and telemetry beyond identification (2026-10-21)

This is an offline research pass over the owner's existing firmware and retained
profile170 captures, with three independent research agents. It made no vehicle,
ADB, BydLog, DoIP, private-API or credential requests and changed no product code.
The scope is the evidence behind future diagnostic parameters, not a UI redesign.

**Why most module pages mainly show identification.** The current direct UDS
whitelist permits `22` only for `F194/F195/F18A/F197`; `ModuleIdent` reads those
identity fields. The per-ECU live stream in `DiagnosticsSession.setLiveVisible`
accepts only the three `obd` catalog entries: ECM `7E0`, VCU `7E2`, BSCM `7E5`.
Their Mode01 stream is separate from the shared `VehicleSignals` and 57
`DiagnosticExtra` scalar channels and their parameter pages. Thus the current
screen is not an inventory of everything each controller can measure. There are
two distinct acquisition paths: addressed diagnostic requests with ECU-specific
DID/PID definitions, and telemetry already exposed by the head unit's vendor API.
A logical API device such as `1039` does not establish a physical ECU address.

**The factory route inventory is now accounted for.**
[`ecu-inventory-coverage-20261021.json`](../research/vehicle-diagnostics/ecu-inventory-coverage-20261021.json)
retains every one of the 225 factory records, its identity and historical evidence:

| Inventory layer | Count | Limit |
| --- | ---: | --- |
| Factory records | 225 | Variants, containers and children; not installed-ECU count |
| Logical routes | 86 | 81 CAN pairs, 5 DoIP target/client tuples |
| CAN routes with historical outcome | 81 | 66 in the retained answering scan; 15 gateway result3, not proof of absence |
| Route + software-code DID namespaces | 184 | Several identities can share a route |
| Non-F194 namespaces | 99 | 69 contain a valid cached code; still not a fresh child response |
| Cache records | 176 / 23 / 26 | Valid nine-byte code / zero placeholder / literal failure marker `7F22` |

No previously untested CAN route was discovered in this factory map. `7E5` and
`712` are already answered routes outside its CAN set; ADC `712` has a factory
DoIP entry instead. The five DoIP tuples are `0010/0001` (CDC SOC/FPGA with distinct
DIDs), `071A/0001` (ADC), `073E/0001` and `078C/0001` (right/left LiDAR alternatives),
and `075F/0001` (middle LiDAR, zero cache). None has a retained DoIP observation.

The important unresolved identities are INS `723/F394,F494`, three FRL children
behind `747/F644,F654,FB24`, numbered DAL/IAL/FMPL heads, and CDC/ZCU subidentities.
The four `720/C294,C394,C494,C594` families resemble Xtool's CDC/F_ZCU/R_ZCU/B_ZCU
switch submodules, but no source binds those names to those DIDs. Keep this as a
hypothesis. CDC versus LA_BCM at `720` remains unresolved. The existing eight
seat-massage/rear-lamp child-read candidates were subsequently executed8/8 with
cache matches; see [live result](#unattended-child-and-buffer-qualification-2026-10-21).
[`ecu-inventory-gaps-20261021.json`](../research/vehicle-diagnostics/ecu-inventory-gaps-20261021.json)
keeps all 172 vendor rows, 47 tokens without a literal factory-family match and
the candidate child namespaces, without inventing new CAN addresses.

The exact stock parser writes `7F22` on diagnostic failure and can conditionally
substitute repair metadata for default software versions. A nonzero cache is
therefore not sufficient installation proof. The stock evidence is retained in
[`ecu-inventory-firmware-evidence-20261021.json`](../research/vehicle-diagnostics/ecu-inventory-firmware-evidence-20261021.json).

**Live-data support is broader than our current direct stream.**
[`ecu-live-data-support-20261021.json`](../research/vehicle-diagnostics/ecu-live-data-support-20261021.json)
joins each of the 68 observed addresses to exact-model Xtool rows by exact token
or same-route F194-to-cache-to-family evidence. It finds 56 with live-data support,
10 without it in this scanner package, and 2 unbound (`7E5`, `720`). No supplier is
selected merely because alternative rows agree. These flags supply neither data
identifiers nor scales and cannot establish hardware limitations. For example,
Xtool lists no live stream for refrigerator VR `5A0`, yet the stock head unit has
refrigerator status and fault-reason interfaces; their exact hardware variant and
physical-ECU binding remain to be established.

**A decoded property registry, rather than names alone.** The stock DiCarServer
assets `config_1.bin`, `config_2.bin`, `config_3.bin` are protobuf `FeatureList`
profiles. `HalFeatureProvider` interprets them as read, write and read/write. A
feature supplies property key, mapper interface, data flow and Java type. The
provider resolves the property through its mapper before using a native FID.

The offline decoder
[`decode_dicar_config.py`](../research/vehicle-diagnostics/decode_dicar_config.py)
produces
[`dicar-readable-properties-20261021.json`](../research/vehicle-diagnostics/dicar-readable-properties-20261021.json).
It strictly checks the wire schema, applies last-key-wins within each profile,
preserves source hashes and does not access Android or the network.

- Read: 9,098 records / 9,085 keys; read/write: 714 keys, with no overlap.
  **9,799 unique readable declarations** remain. Write-only metadata is counted
  separately (3,664 records / 3,649 keys), not emitted as read candidates.
- 9,780 declarations have an exact mapper symbol. 7,125 have an independently
  resolved native ID, primarily from the retained current-car reflection capture,
  with the older CAN-FD table as an explicitly labelled fallback.
- **393 property/native pairs differ.** The rear-steering correction above is one
  example; sensor yaw-rate property `0x22200008` maps to native `0x22200408`.
  Never pass the displayed property-key hex directly to a native read.
- Declared Java types are 8,927 int, 595 double, 277 byte arrays. Type-code4 becomes
  double in this provider. These are API declarations, not native transaction
  numbers or proof of a valid response. Observed scalar int/float types remain
  separate. `interfaceIndex` is also not `nativeDevice`.
- Mapper insertion order matters: interface2 ends as BodyMapper, interface42 as
  VehicleMapper. Unknown native IDs remain null rather than using a numeric-key
  guess. The selected 30 chassis and 50 body/chassis records all have declarations.

The registry includes configuration, status, control and compatibility surfaces;
it is not 9,799 sensors, a list of installed hardware or an execution allowlist.
App-UID permissions still apply. It gives a reproducible way to select a small
semantically understood read, preserve its status/type, and qualify it later.

**Powertrain and battery: what the corpus actually adds.**
[`powertrain-telemetry-20261021.json`](../research/vehicle-diagnostics/powertrain-telemetry-20261021.json)
retains 294 stock names, their concrete framework readers and existing captures:
33 already have product readers; 123 further names have scalar replies in the
retained two-pass sweep; 4 are captured no-data; 134 were not read. These are
names/variants, not 294 distinct valid sensors. Twenty reply channels match
placeholder/invalid patterns and 64 are zero-only. New type declarations are
joined separately from observed native types.

| Area | Source-backed addition | Remaining limit |
| --- | --- | --- |
| Main discharge contactor | Device1032/native `34400010`: framework explicitly names 0 disconnected, 1 connected; retained read1 | Does not decode the negative contactor or charge relay |
| Charger | Device1009 `27C00008`: 1 normal, 2 minor, 3 major fault; `27C0000A`: 1 ready, 2 start, 3 finish, 4 terminate; retained1/1 | Domain states, not a DTC dictionary or current-car recheck |
| Battery-management state | Device1009 `34400018` has explicit framework states0–13; retained1 means charging in that enum | Keep separate from gun state and energy-flow sign |
| Battery limits | Device1014 `34500008/18/28` reply3102→3246 /277 /7 for named discharge-power/charge-power/charge-current limits | No qualified kW/A scale |
| Engine indicated torque | Device1012 `10E00008` gives float0 in both saved passes | Different from absent `32400058`; unit and nonzero response unproved |
| Stationary power | `23000838` raw3 means FORBIDDEN_OPEN; `2300083A` raw1 means LOW_POWER in stock wrappers | Power level is not measured kW |
| Low-voltage DC side | `36D00020` returned float13.8; `36D00028` raw42; `36D00030` raw2 | Current scale and mode decoding remain open |

Already known battery data remain useful: SOC/SOH, 166-cell and 14-probe counts,
voltage extrema, temperature min/max/mean, insulation readings and selected drive
values. **At this offline stage no full array was recovered; the later
[live GB pass](#live-passive-streams-and-paged-battery-telemetry-2026-10-21)
supersedes that gap with factory-decoded cell/probe pages.** An extrema/count API
alone is not that array. The legacy GB alarms all
reading1, thermal-runaway `FFFF`, maximum cell voltage40.95 and cell index1023
must not become healthy values or active-fault claims without their own validity
rules. The existing insulation readings still lack a vehicle-qualified unit.

There is a real passive `GB_DYNAMIC_DATA_CALLBACK` (`1039/9900001F`) forwarding a
byte array to `onWholeFrameDataChanged`. Its Java event parser is empty, but the
ten-byte raw-CAN envelope was already checked on the N9 and is now traced through
the native fanout: ID, sub-ID, channel, counter, then payload. The earlier claim
that no layout was recovered was too broad. This is a raw-frame callback, not a
buffer getter or a decoded cell-array source. Owner-Z9 GB availability and inner
signal semantics remain separate. No dedicated ISG RPM/torque/temperature
interface was found in the bounded search. Historical scalar no-data results
remain valid; seven `324/30D` fields now have a distinct MCU/raw-frame research
path in [signal origins](#signal-origins-and-passive-qualification-2026-10-21).

**Chassis, body and sensors.**
[`body-chassis-telemetry-20261021.json`](../research/vehicle-diagnostics/body-chassis-telemetry-20261021.json)
keeps 50 selected paths with source lines/hashes and separate key/native/type fields:

- Wheel speed is a byte array at device1049/native `99000059`. One real consumer,
  GLAvmAPI, accepts four zero status bytes, decodes four signed16 big-endian samples
  and multiplies by `0.06875 × 1000 / 3.6`. The arithmetic suggests km/h-to-mm/s
  conversion. The later MCU trace below establishes FL/FR/RL/RR order and unsigned
  12-bit producer fields in the inspected image; engineering units, installed MCU
  selection and this panorama implementation's selection on our car remain
  unproved. The direction consumer is not accepted as a
  reliable decoder. Use the actual SDK buffer/listener shape, never an int-only
  parcel to native transact10/12.
- Parking-radar SDK device1025 exposes distance and probe-state for nine named
  areas. Its separate bulk buffer has eight two-byte records; those layouts must
  not be silently unified. Units are unproved. These are not automatically door
  SDR or front-MRR signals.
- Device1013 has brake-pedal depth and its validity flag; device1043 has acceleration,
  yaw-rate and steering-related fields. No pressure/percent/angle scale is inferred
  just from the names.
- AQC/AQS/CO2-level getters return ordinal levels; CO2-level is not ppm. PM2.5
  in/out values are already read; their state/online fields are additional context.
- Refrigerator fault reason `36B00010` has enum3 `TC_FAILURE`,4 `SENSOR_FAILURE`,
  gated by a particular fridge variant/state. Temperature **feedback** keys are
  distinct from the cold/hot/fresh **regulation targets** returned by some methods
  named `getFridgeTemperature`. No target is promoted as an actual temperature.

**Direct UDS measurement definitions remain the missing piece.**
[`uds-measurement-evidence-20261021.json`](../research/vehicle-diagnostics/uds-measurement-evidence-20261021.json)
records the complete bounded investigation. It recovered the head unit's own
native UDS-server tables: 221 read-support initializer entries (218 unique DIDs)
and 220 record lengths, retained in
[`hu-uds-server-dids-20261021.tsv`](../research/vehicle-diagnostics/hu-uds-server-dids-20261021.tsv)
and [`hu-uds-server-tables-20261021.json`](../research/vehicle-diagnostics/hu-uds-server-tables-20261021.json).
Exact server-init calls and the relocated library vtable establish their scope.
They do **not** describe remote ECM/BMS/drive/ABS measurements, and provide no
sensor names or engineering units. Numeric DID010D is not OBD mode01 PID0D.

EOL scripts support bit offsets, endian, signedness and physical formulas, but
bundled inputs supply no qualified target-ECU sensor records or physical formulas.
Thirteen numeric DIDs appear in legacy window/seat fixtures following session,
security and IO-control steps; they are not qualified passive measurements for
this car. SDK snapshot/extended DTC DTOs preserve raw bytes and still need
ECU-specific layouts. Scaling DTOs belong to the local server callback, not an
automatic client-side discovery API. No software-qualified OEM DTC dictionary was
recovered; EOL's exact24-bit `Attach.dtcList` lookup confirms where a matching
factory script could carry it, without supplying that missing script.

**Next evidence to pursue:** bind INS/FRL/Cx94 child identities and the five DoIP
routes; trace concrete read consumers for high-value declared parameters; recover
per-ECU DID/record definitions and software-applicable DTC text. Prioritize exact
namespaces and schemas over another broad address scan. A future measurement must
retain raw value/status, identity, source, type, unit/scale evidence and validity.
Missing or invalid data must remain unknown. Raw analysis, scripts, searches and
independent checks are retained under `captures/diagnostics-deep-20261021/`.

### Signal origins and passive qualification (2026-10-21)

This continuation traced concrete fields through the local HAL and MCU, with
three independent research agents. It made zero vehicle, ADB, IPC, network or
credential requests. Firmware evidence is pinned to the owner's OTA; the current
installed MCU image and runtime table selection have not been matched.

**Wire fields and API values are different layers.**
[`powertrain-signal-origins-20261021.json`](../research/vehicle-diagnostics/powertrain-signal-origins-20261021.json)
retains 152 selected records, 73 with HAL registration patterns and 58 with
byte-verified MCU descriptors. These are field/name variants, not new sensors.
The generic MCU extractor uses unsigned little-endian bitfields and serializes
scalars big-endian to the HAL. Native converters then update API caches:

| Path | Proved arithmetic / layout | Still unqualified |
| --- | --- | --- |
| Battery limits `34500008/18/28` | MCU ID345/channel0: unsigned16 at payload offsets0 and2, unsigned8 at4; HAL leaves integers unchanged | kW/A scale and invalid codes |
| Battery current `44A00020` | ID44A/channel0: unsigned16 at offset3; HAL leaves integer unchanged | Signed current, offset and A unit |
| Engine torque `10E00008` | ID10E/channel0 byte0; native converter multiplies by float32≈0.392 | Nm/percent, nonzero response and validity |
| Four drive torque/current fields in `285` | Native converter ×0.1; attached MCU fields are unsigned16, zero-extended into the signed32 converter input | Physical sign/unit and fault sentinels; no signed16 reinterpretation |
| Drive/IGBT temperatures `28500198…` / `2D000170…` | Native input−40; MCU unsigned8 | No reserved-byte filter in converter; raw255 would produce215 |
| DC-side current `36D00028` | Native `2×wire−250` | Retained API42 is already converted; unit remains unknown |

These are **wire-to-API** formulas, never another transform to apply to existing
API outputs. Some other motor variants have checked offsets/ranges, retained
per field in the JSON. Insulation units remain unknown. Generic cache getters
do not establish acquisition age; zero, successful conversion and a returned
cache entry are insufficient evidence of a fresh sensor reading.

All 25 historically silent engine/temperature candidates lack GET registration
patterns in the inspected CAN-FD initializer. **Seven still have MCU parsers**:
engine speed, coolant, indicated torque, intake temperature, fuel and lambda in
ID324/channel0, plus fuel in ID30D/channel0. Their minimum payload lengths are
respectively 8,9,11,13,19,37 and3 bytes. Units cannot be inferred from names.
The MCU lookup and raw exporter use the same receive record, establishing this
ID/channel association independently of hexadecimal resemblance. A base lookup
key does not force the exported sub-ID to zero. Crucially, retained owner-Z9
ID324 frames are **channel1**, so the channel0 descriptors do not decode them.

**The wheel packet now has a producer-backed layout.**
[`packet-origin-20261021.json`](../research/vehicle-diagnostics/packet-origin-20261021.json)
traces MCU producer `18AA36` from CAN122/channel1 to `1049/99000059`: exactly12
bytes, four unsigned12 values stored in big-endian16 words, then four one-bit
status bytes. Order is FL, FR, RL, RR by descriptor/name crosswalk. The source
status bits are payload byte1 bits5,4,6,7 in that order. A firmware gate can
suppress production. The later [consumer follow-up](#consumer-semantics-and-remaining-tests-2026-10-21)
binds that gate's setter to panorama command `AA000048`; its current value and
installed-image applicability remain unknown. HAL forwards the packet unchanged. Its initial `MCU_OFFLINE` cache
value has11 bytes and is not a measurement.

The offline [`decode_wheel_packet.py`](../research/vehicle-diagnostics/decode_wheel_packet.py)
preserves raw/status, rejects wrong lengths and upper four speed bits, and emits
the GL consumer's float arithmetic as a **candidate with no physical unit**.
Its structural acceptance is stricter than GL's status-only gate. The synthetic
tests establish parser behavior, not wheel calibration or live availability.
The old owner capture contains CAN122/sub0/channel1/8-byte payloads; no retained
99000059 packet was found. Four stationary zeros cannot validate scale or sides.

GB9900001F is now traced as owner-mask fanout of raw MCU event9900001D. Its
ten-byte envelope is ID/sub/channel/counter; it does not deliver an already
decoded array of traction-cell voltages. The N9 GB/YUN observations remain
other-car evidence, not owner-Z9 availability proof.

**ECU topology and factory dictionaries remain separate gaps.**
[`ecu-route-topology-20261021.json`](../research/vehicle-diagnostics/ecu-route-topology-20261021.json)
retains 32 vendor model-network labels, unresolved child namespaces and five
DoIP logical targets. Factory `ecuChannel` is not a proved physical-bus index:
the stock CAN reader does not use it and the model join maps each value to
multiple networks. The authorized map projection excluded `ecuIp`, so absence
of IPs in our saved map says nothing about the server's original response.
The stock DoIP path sends session03 before each identity DID and relies on
native retry/timeout settings; it is not ready to copy into a passive reader.
The local SDK `UdsId` is a Binder-assigned handle, not logical client address0001.

[`decode_child_identity.py`](../research/vehicle-diagnostics/decode_child_identity.py)
prepares validation of the eight already listed child identities. It checks
owner, response address, complete single-frame envelope, echoed DID, exact
nine-byte code and cache agreement. A negative response, zero placeholder or
cache mismatch does not establish an installed child; none was read this pass.

[`factory-scenario-origin-20261021.json`](../research/vehicle-diagnostics/factory-scenario-origin-20261021.json)
adds no software-qualified OEM DTC definitions. `afterRunRes` cannot recover
script selection because `runCommandData` is transient; station preferences
are not a script catalog. Bounded local-file/archive checks and four historical
logs found no new matching script. Their 194 empty outgoing-queue observations
are neither an incoming-scenario history nor current state. A legacy debug-gated
plaintext path `/sdcard/SpotIns` was a metadata-only lead; the later live presence
check found that directory absent. A real scenario
still needs selection provenance and ECU/software applicability, and may cover
only the faults relevant to a factory inspection rather than a complete ECU.

**The prepared plan was subsequently executed below.**
[`next-car-validation-20261021.json`](../research/vehicle-diagnostics/next-car-validation-20261021.json)
prepares three sequential20-second receive-only GB/BigData/YUN windows while
parked, after checking identity, single-session ownership and firmware. The
existing collector compiled on the host; no device run occurred during this
offline phase. The later live results are separate below. Acceptance
requires unregister/exit, no callback errors or drops, `queued=0`, and complete
local sequence/count reconciliation. A DONE line alone is insufficient.
Only matching ID/channel/sub/length observations can advance a decoder; no
collection-table write, wake, UDS scan, factory-app launch or state change is
part of this first availability pass. Wheel-buffer correlation and changing
operating states remain subsequent, separately prepared checks.

Native disassembly, source snapshots, reproducible scripts, hashes and independent
reviews are retained under `captures/diagnostics-origin-20261021/`. The research
JSONs declare their `captureBase` for relative evidence names. No product reader,
diagnostic allowlist or UI was changed in this pass.

### Live passive streams and paged battery telemetry (2026-10-21)

After the owner connected the car, the prepared availability pass ran on the
Z9GT: profile170, CANFD, P1, speed0 and idle diagnostic manager before/after.
HAL and framework SHA-256 matched the researched corpus before and after the
three planned windows. MCU reports `34.2.3.2605202.2`; this is a version match,
not a read/hash of the installed MCU image. The installed Denza Apps APK was not
changed. No ECU diagnostic request, collection-table change, factory-app launch,
OTA operation, restart or vehicle-state setter was used.

[`passive-telemetry-live-20261021.json`](../research/vehicle-diagnostics/passive-telemetry-live-20261021.json)
retains source hashes, exact tuple inventory, identity and health evidence:

| Sequential window | Duration | Records | ID/sub/channel/length tuples |
| --- | ---: | ---: | ---: |
| GB `1039/9900001F` | 20s | 911 | 30 |
| BigData `1061/99000020` | 20s | 2,909 | 103 |
| YUN `1034/99000021` | 20s | 3,129 | 71 |
| Separate GB battery correlation | 20s | 913 | 30 |

The first three windows contain 6,949 records and a union of186 tuples. These
are frame routes, **not186 ECUs or sensors**, and the windows are not simultaneous.
All four logs have exact local sequences, zero dropped/callback-error/queued
counts, unregister and process exit. The host also checked the temporary dex
absent after each window. `autoservice`, AVC, CanDataCollect and SpotInspection
PIDs stayed unchanged; every saved crash buffer was empty. The existing tunnel
was preserved. `/proc/locks` and hashing the autoservice executable were denied;
ownership evidence is the idle stock manager and process snapshots, not a proved
global lock. The app thread-inventory call timed out. Execution checks retain
these limits rather than claiming stronger coordination or hash coverage.

Of the original exact target tuples, CAN122/sub0/channel1/8 bytes appeared20
times. CAN324 appeared19 times on channel1 with64-byte payloads, which does not
qualify the channel0 engine descriptors. None of the seven planned channel0
powertrain IDs appeared in these windows. This is a selected-stream availability
result, not absence from the car. One YUN embedded counter went backwards by1
between different tuples while device monotonic time increased; preserve both
time domains and do not treat that counter as a universal timestamp/sequence.

**The new useful path is the GB battery pages.** Existing-corpus lookup found
the native consumers listed but not yet extracted; only `gbacqservice`,
`gbdataservice` and `libgbdataservice.so` were extracted from the owner's local
OTA, with hashes and provenance. Two independent source inspections confirm:

- `gbacqservice::recvSingleCellCandata` at `1ABD0` accepts device1039/event9900001F.
  The `43C` branch stores six raw probe codes per page; `43D` stores three
  little-endian16 cell words, reversing byte order for its outgoing representation.
- The actual `gbdataservice` jump table at `4AE4` maps `43A→E9A0`, `43B→EBF8`,
  `43C→EAA8`, `43D→EBAC`. Named count fields and count-limited buffers establish
  cell/probe array semantics, not just a numerical resemblance.
- Observed tuples use sub0/channel2. `43A` gives166 cells and14 probes. Three
  `43C` pages assemble14 values plus four `FF` fill slots. Fifty-six `43D` pages
  assemble166 words plus two `FFFF` fill slots. Page`FD` has a separate stock
  policy/metadata branch and must not enter the cell array.
- Every observed `43C` page satisfies the byte-sum rule255; this remains an
  observed integrity rule, not a proved native checksum gate. `43D` does **not**
  follow it. Physical positions of cells/probes and the complete invalid-code
  vocabulary remain unknown. Array position means stock page-assembly order.

[`battery-page-telemetry-20261021.json`](../research/vehicle-diagnostics/battery-page-telemetry-20261021.json)
retains the native proof, raw reconstructed cycles, source hashes and independent
review. The offline [`decode_battery_pages.py`](../research/vehicle-diagnostics/decode_battery_pages.py)
keeps raw codes, page timestamps, fill values and incomplete sequences. A complete
array is structurally reconstructed, not a healthy-battery verdict. The decoder
does not manufacture engineering units or physical sensor locations.

**The follow-up compared the same window with established GETs.** Two batches
of nine qualified scalar readers returned166 cells,14 probes, insulation raw14465
and SOC74%. Nearby `43A` records match the counts and insulation. This adds a
live cross-check for the `43A` insulation producer, but still no ohm/kOhm scale.
The field at `43A` payload offset4 changes through the window despite constant
qualified SOC; its generic `ESTIMATE_SOC_V1` name cannot justify calling it SOC.

All66 complete temperature cycles in the correlation window have raw min54,
max56 and mean55, exactly matching the three native aggregate readers. With
their already-qualified offset−40, these correspond to **14/16/15°C**. This is
a same-window scale/aggregate cross-check; no per-probe physical location or
fault threshold has been established. The earlier window's temperatures were
different and must not be compared to the later GETs.

The166-cell sweep takes about**5.5 seconds**. Three complete correlation sweeps
have raw ranges3310–3313,3304–3308 and3309–3312. Qualified scalar voltage GETs
returned3309/3313mV and3304/3307mV at their two short sampling intervals. The
second maximum therefore differs from the containing sweep's3308. Sequential
acquisition makes an asynchronous comparison necessary; the precise cause of
that difference is not proved. Keep per-page age, raw units and sweep duration;
do not present these as one simultaneous cell-voltage snapshot or apply a balance
diagnosis to the combined extrema. The first window similarly has two complete
cell sweeps with raw ranges3280–3285 and3276–3281.

The optional metadata-only check found `/sdcard/SpotIns` absent. No contents,
private cache, factory task or cloud endpoint was accessed. This closes that
external-path lead at this time only. No additional ECU identity or applicable
OEM DTC dictionary was obtained.

Raw recordings and reviews are under
`captures/diagnostics-live-20261021/073428-passive/`; the follow-up is separate
in `gb-correlation/`. Further work can now proceed offline: resolve remaining
units/invalid markers and physical ordering, design freshness-aware array reads,
then qualify a diagnostic display. No array reader was added to the product in
this research pass.

### Consumer semantics and remaining tests (2026-10-21)

This continuation read only the existing owner firmware and completed captures.
It made no vehicle, ADB, network, factory-app or credential request and changed no
product reader or UI. Three Sol6.1/xhigh workers investigated engine/drives,
battery semantics and DTC definitions; independent source reviews checked the
battery and chassis conclusions. The reusable artifacts below retain source
hashes and exact instruction addresses. This is a source-level advance, not a
new live ECU or telemetry qualification.

**The stock collector selects concrete fields for three drives.**
[`engine-drive-consumers-20261021.json`](../research/vehicle-diagnostics/engine-drive-consumers-20261021.json)
traces `gbacqservice:130A0`, called by `gbDataPack:18880`. Device1023/FID
`34100038=7` selects the three-drive branch. The owner sweep returned7 in both
passes on20October; that is retained configuration evidence, not a current read.

| Stock field | Front | Rear left | Rear right |
| --- | --- | --- | --- |
| State | 1023/2D0001D1 | 1023/2D0001D5 | 1023/2D0001D7 |
| Rotation speed | 1012/44100008 | 1039/0FC000D0 | 1039/0FC000E0 |
| Torque | 1012/44100018 | 1039/2850002C | 1039/2850003C |
| IGBT temperature | 1039/46406010 | 1039/2D000180 | 1039/2D000188 |
| Motor temperature | 1039/46406018 | 1039/285001A8 | 1039/285001B0 |
| DC-side current | 1009/46C00008 | 1039/28500178 | 1039/28500188 |

These are logical native devices/FIDs and stock field labels, not newly bound
UDS addresses or measured physical positions. The artifact distinguishes GET
type, MCU/HAL conversion and subsequent GB transport encoding. Do not apply the
GB offset/scale to an already decoded API output. The collector ignores getter
return statuses in these paths: saved rear-left speed `FFFF` would become
`19999` after its `u16(API+20000)` packing. That is a source-derived counterexample,
not an observed packet. Preserve unavailable/error separately from ordinary zero.

The engine branch reads1012/`10D00008` only when1012/`10D00038` equals3;
otherwise it serializes speed0, including during state1 cranking. Historical
owner rows have nonzero RPM while state1. This does not change the product's
independently qualified `engineRunning >= 1` rule. Both10D/20D raw speed fields
pass a HAL quarter-scale converter; primary144 speed is a13-bit field whose
known unavailable code is1FFF. API outputs have already passed those converters.
No working coolant/boost/lambda/ISG-specific source was recovered. Default GB
collection tables are retained as bytes, but service configuration takes priority;
neither a table's existence nor a write to it is an availability result.

**Battery assembly needs its own completeness and age rules.**
[`battery-stock-semantics-20261021.json`](../research/vehicle-diagnostics/battery-stock-semantics-20261021.json)
closes the native path from43C/43D buffers through serialization, queue and disk.
Both services sum individual cell words using the exact literal0.001, while
individual array words themselves are copied unchanged. For166cells the nonzero
aggregate is multiplied by10 before truncation; `gbdataservice` substitutesFFFE
when the truncated aggregate is zero. These are proved arithmetic operations,
kept separate from a human-readable engineering-unit/validity contract.

Native "complete" counts received elements and leaves readiness latched. It
does not require every page position, reject repeats or retain per-page age.
Repeated page0 alone could satisfy the element counter; this is a source-level
counterexample, not a claim of loss or duplicates in the completed owner logs.
Later packets can therefore contain a rolling mixture of page ages. The existing
offline decoder's sequential-page checks remain stricter, and even an accepted
5.5-second sweep is not simultaneous. Queue time and the later filename timestamp
cannot establish individual measurement freshness.

The specialFD branch serializes `83 <payload7> <payload2> <payload1>` outside the
cell/probe arrays. Seven retained FD records show byte7 advancing by1 within
each window, but no counter/current/voltage meaning is assigned to either field.
Native copies retainFE/FF/FFFE/FFFF; skipping zero in a temperature aggregate is
not a complete sensor-validity rule. Extrema indices are one-based logical array
positions. Hardcoded subsystem counts and a200-cell packet split do not locate
physical battery modules or probes.

**The wheel gate and a separate steering path are now explicit.**
[`chassis-source-followup-20261021.json`](../research/vehicle-diagnostics/chassis-source-followup-20261021.json)
binds MCU gate getter16B6B4 and setter16B6BC to the same RAM byte; command table
CD984 assigns the setter `AA000048`. The actual SDK names it
`PANORAMA_TRANSPARENCE_SWITCH_SET`, called by stock `CarTransparent`. Current gate
value and the selected AVC branch remain unknown; its separate getter is not
proved to read back this MCU byte. No panorama setter or camera activation was
used. The new offline [`decode_wheel_source.py`](../research/vehicle-diagnostics/decode_wheel_source.py)
reconstructs candidate12-byte packets from exact CAN122/sub0/channel1/8-byte
records. All20 saved source frames are zero. That proves neither nonzero speed
scale nor receipt of the resulting99000059 event.

CAN11F/channel1 custom handler18AD04 emits `1023/99000198`, six bytes: signed-angle
word in big-endian order plus an opaque32-bit value. It explicitly extracts source
payload bytes0..1, overriding its generic descriptor's coordinates. It emits only
when the angle changes. The stock AVC typed-buffer GET/listener uses
`SETTING_11F_CONTENT_TIMESTAMP`; `SteerAngleUtil` applies signed16 × float32(0.1).
The retained GB stream contains20 exact11F/sub0/channel1/5-byte frames, all raw264,
giving that consumer's candidate26.3999996. No99000198 event was captured in
that earlier pass. A later typed GET returned a matching six-byte snapshot; see
[buffer qualification](#unattended-child-and-buffer-qualification-2026-10-21).
The later MCU trace also binds the trailing word to the raw receive clock;
physical time units, live epoch, angle validity and physical unit remain
unqualified. Old scalar
11F00008 cannot substitute for this custom channel1 route; lack of a new callback
at a fixed angle alone does not prove stale reception.

Brake342 fields use MCU channel2 and HAL normalization, but the retained342
frames are channel0/1. Yaw222/channel1 was absent. Neither supports a new decoder
from a matching hexadecimal ID alone, and neither binds a new ABS/EPS ECU address.

**The next dictionary sources were checked without inventing definitions.**
[`dtc-source-followup-20261021.json`](../research/vehicle-diagnostics/dtc-source-followup-20261021.json)
records a bounded MyCar GPAK audit:7,146 indexed entries and6,012 inspected text
resources, including4,361 nonempty parsed JSONs,56 empty JSONs and75 scripts.
No diagnostic definition was found in that scope; binary graphics were not
semantically decoded. Its property profiles differ from DiCarServer, but the
exact runtime loader/mapper remains unbound, so the new numeric keys were not
promoted as named sensors.

The native `libuds` snapshot/extended tasks copy response bytes2..5 as raw
DTC/status and bytes6..end verbatim. They supply no ECU-specific record names,
DID units, scales or code descriptions. Thus those APIs cannot supply a readable
freeze frame without the matching record schema. No new applicable OEM DTC
dictionary, format01 display/raw mapping or remote sensor DID was obtained.

**The next live work has finite questions.**
[`next-car-source-validation-20261021.json`](../research/vehicle-diagnostics/next-car-source-validation-20261021.json)
keeps the completed earlier plan immutable. Existing scalar reads can check the
drive selector and compare the three engine-speed paths during an owner-controlled
normal engine event. Typed wheel/steering correlation needs a bounded isolated
SDK reader first; batteryFD correlation needs exact known scalar reads and common
device timestamps. Neither warrants another identical broad availability scan.
The later [unattended qualification](#unattended-child-and-buffer-qualification-2026-10-21)
completed all eight child-code reads with a dedicated runner and both typed-buffer
observations. The manifest remains the historical pre-run plan. DoIP remains
blocked on endpoint binding and an understood ownership/timeout path. None of
these reads alone can recover missing OEM descriptions or physical battery layout.

The offline parser suite passes154 tests, including six new wheel-source boundary
cases. Raw source excerpts, full resource index, reproducers and independent reviews
remain under `captures/diagnostics-next-depth-20261021/`; binaries stay untracked.

### Unattended child and buffer qualification (2026-10-21)

The owner authorized the remaining research without being in the vehicle; the
engine could not be started. This pass used the retained ADB transport for eight
fixed software-code reads and two bounded passive buffer/source observations.
It did not install an APK, start an engine, activate a camera, change a vehicle
setting, clear a DTC, enter a diagnostic session or contact the factory server.

**Eight child identities now have live answers.**
[`child-identities-live-20261021.json`](../research/vehicle-diagnostics/child-identities-live-20261021.json)
retains all request/response bytes, names, timestamps, source hashes and health
checks. The four massage identities use `750/F8C4`, `7B5/F8D4` and
`7D2/F8E4,F8F4`; the four rear-lamp identities use `726/F204,F214,F064,F234`.
All eight returned positive `62 <DID>` and the exact nonzero nine-byte software
code in the factory cache. Latency was 441–899 ms, with no retry or extra request.
The SDK delivered 16 callbacks: eight logged frames and eight duplicate markers.
Duplicate bytes/timestamps were not separately logged; the independent review
does not invent them. The listener was removed and the process exited normally.

These are eight **parent-reported software identities through four existing
routes**, not eight additional CAN addresses, proof of present child wiring or
separate child DTC memories. The old candidate manifest stays unchanged because
the new `ChildIdentityReadProbe` pins it as a build input. INS/FRL/CDC naming and
DoIP transport gaps remain separate questions.

[`child-role-gaps-20261021.json`](../research/vehicle-diagnostics/child-role-gaps-20261021.json)
records a separate finite search for nine unresolved names: `723/F394,F494`,
`747/F644,F654,FB24` and `720/C294,C394,C494,C594`. A reproducible search of
14,821 existing firmware/config text files found no direct named-subrole join.
The last four DIDs do bind exactly to factory families CDC/F_ZCU/R_ZCU/B_ZCU;
calling them switches is still a hypothesis. Xtool's named rows have no factory
ID, DID, route or software code to close the join. ProductLineOTA's
`EcuVersionInfo.ecuDescription` belongs to package/strategy metadata, while
`EcuListBean` has no description field. The retained `needCheck.ecuList` is empty.
This identifies a missing source, not a reason to start an OTA workflow.

The subsequent source-path trace found a new exact existing-file candidate:
`/data/ota_package/ota_productline/result/strategy.json`. It is distinct from the
older exported `ota_ghy` and `ota` paths. Current `ro.vehicle.type=DiLink150_7.0UI`
selects the generic ProductLineOTA path; the writer attempts a readable leaf
mode but does not change ancestor permissions. Root's metadata-only check got
`Permission denied` starting at `/data/ota_package`; `test -r` failed. Existence
and contents remain unknown. No file contents, BydLog session, permission change,
factory request or OTA activation followed. The source anchors and exact result
are in [`ecu-description-cache-lead-20261021.json`](../research/vehicle-diagnostics/ecu-description-cache-lead-20261021.json).

**The typed steering buffer is readable; the wheel buffer is unavailable in
this state.**
[`chassis-buffers-live-20261021.json`](../research/vehicle-diagnostics/chassis-buffers-live-20261021.json)
records one initial typed GET and one exact listener per target. The raw source
was observed concurrently for 20 seconds; target setup/observation had an
18-second soft deadline with a separate 20-second hard limit including cleanup.

| Target | Typed snapshot | Concurrent source | Result and limit |
| --- | --- | --- | --- |
| Wheels, `1049/99000059` | 11 ASCII bytes `MCU_OFFLINE` | 2,953 BigData records; 20 exact `122/sub0/ch1/8B` frames, all zero | No usable 12-byte wheel packet and no callback. Zero source words do not establish physical speed scale; `MCU_OFFLINE` does not diagnose ABS failure. The gate state remains unknown. |
| Steering, `1023/99000198` | `0108043d1ea5`, six bytes | 916 GB records; 20 exact `11F/sub0/ch1/5B` frames, all angle words `0801` | Snapshot BE16 `0108` equals source LE16 `0801` = 264. The stock float32 × 0.1 conversion gives about 26.4. No callback at the unchanged angle; units and acquisition age remain unqualified. The trailing word is explained by the later clock trace below. |

The steering DTO's integer field was `-999999999`; the SDK uses that slot as a
typed value field, not a validated return status. Preserve it as `sdkIntField`,
alongside the actual buffer. A successful typed snapshot is not proof of a
fresh sample or continuous delivery. Both runs ended with `failure=null`, no
loss/errors/queued data, listener removal and ownership release.

**Steering carries a change-time clock shared with raw reception.**
[`steering-clock-20261021.json`](../research/vehicle-diagnostics/steering-clock-20261021.json)
traces MCU getter `1A619A` to the word at `FEF0B63C`. The timer callback increments
it modulo 2³²; the reset-entry RAM clear includes it. The raw receive path stores
this same clock at record+48, then exports it BE in envelope bytes 6–9. The
steering handler reads it after accepting a changed angle and exports it BE in
packet bytes 2–5. This is a handler/change-time stamp, not an ECU acquisition time.

The saved 916-record window advances 19,927 ticks over 19.926 arrival seconds,
approximately 1,000 ticks per second. The cached stamp precedes the first raw
counter by 5,454,850 ticks modulo 2³². Do not convert that difference into sensor
age: timer stop/restart paths exist, hardware input frequency/prescaler and live
reset/wrap history remain unresolved, and a constant angle continues arriving.
Thirty instruction anchors and the timer table are reproducible; the installed
MCU image has not been byte-hash matched to this owner-OTA image. A pypcode4
`ST.W disp23` store-width discrepancy is documented; its incorrect pcode store
emulation was not used to establish the word write.

**The battery FD bytes now have a reproducible extrema correlation.**
[`battery-fd-extrema-20261021.json`](../research/vehicle-diagnostics/battery-fd-extrema-20261021.json)
joins three completed GB logs, reusing the steering capture rather than making
another request. Of ten exact `43D/sub0/ch2` FD records, seven have a complete
166-cell sequential sweep since the previous FD. In all seven, little-endian
payload bytes 3–4 equal that sweep's maximum raw word and bytes 5–6 its minimum.
Three leading FD records have no complete preceding sweep and are excluded.
The new window adds distinct pairs 3366/3360 and 3369/3363; gaps from the last
cell page are 98–105 ms across all seven pairs.

[`check_battery_fd_extrema.py`](../research/vehicle-diagnostics/check_battery_fd_extrema.py)
reproduces the exact records from pinned logs, using the stricter page decoder.
An independent parser also reproduced all seven pairs. This is a finite
correlation, not an OEM field definition, physical-unit qualification or a
simultaneous battery snapshot. Sweeps still span about 5.5 seconds. FD bytes
1–2 and 7 remain unnamed; the stock FD serializer ignores bytes 3–6.

The bounded downstream follow-up also found no name for FD word1: parallel
`gbacqservice` rejects FD as an out-of-range page, while `gbdataservice` uploads
the archive as multipart `files`. The recovered MCU descriptor table has neither
the channel2/43D broad key nor its FD-specific key. A nearby scalar path adds
5000 but stores a different field; this does not justify subtracting 5000 from
FD word1 and calling it current. Exact source hashes, 44 instruction/string
anchors and the two missing table lookups are retained under
`captures/diagnostics-unattended-20261021/fd-word1/`.

**The factory insulation collector loses error-like patterns.**
[`insulation-consumer-20261021.json`](../research/vehicle-diagnostics/insulation-consumer-20261021.json)
traces the selected `1039/43A00018` or `43A00818` through `gbacqservice` to
type-01 bytes 17–18, big endian. The exact expression is
`min(getter_raw & 0xFFFF, 60000)`; the getter status is not checked. Both FFFE and
FFFF become 60000, and a failed getter can leave the initialized zero. These are
source-derived counterexamples, not observed faults. Copying this clamp into a
diagnostic validity rule would hide missing/error data. Unit, freshness and the
separate `26200010` field remain unresolved. The standalone
[`checker`](../research/vehicle-diagnostics/check_insulation_consumer.py) verifies
28 exact instruction anchors and all 65,536 low16 arithmetic cases without
executing firmware. Official standard version metadata supplies no additional
vehicle-specific unit or DTC definition.

**Run integrity and remaining limits.** Profile 170, CANFD, P=1, speed=0 and an
idle diagnostic manager were checked. Before/after process identities for
autoservice, both GB services and AVC stayed unchanged; crash buffers stayed
empty. Installed Denza Apps SHA-256 remained
`e02262e8a992c91228bb8222c2604e2a6b7eee84ff5d61aa1088a529e78cba54`;
three inspected firmware-file hashes also matched. Helpers exited and only their
nonce-named staged files were removed. No product availability claim follows
from these isolated shell probes.

The 154 existing parser tests, four new host-runner tests, 35 child transport
assertions and 18 chassis lifecycle cases pass. Raw logs, preparation, health
snapshots and independent reviews are retained under
`captures/diagnostics-unattended-20261021/`; APKs and firmware stay untracked.
The engine-running comparison still needs an owner-controlled engine event.
No applicable OEM DTC dictionary, format01 code mapping or ECU-specific
snapshot/extended-record schema was obtained in this pass.

### OEM DTC manual candidates (2026-10-20)

**A model-specific public index has been found; an applicable OEM dictionary
has not yet been obtained.** The public contents of
[2024 Z9GT 易三方 PHEV diagnostic manual](https://dongcheyun.com/p/23710)
separate engine control, battery management, front drive, generator and the
two rear drives. These are third-party catalog headings, not retrieved primary
manual pages. Neither the original document revision nor applicability to the
measured ECU software has been established. They are retained for acquisition
and comparison, not installed as fault descriptions. The initial browser
capture had automatic translation enabled. A second capture selected Chinese
through Chrome's native translation UI and recovered all 829 original public
headings; every subsystem/code/outline identity matched the first capture.
Original publisher text still does not establish OEM authorship or applicability.

| Public section | Observed heading coverage | What prevents qualification |
| --- | --- | --- |
| Engine electronic control | 496 DTC headings, 494 distinct seven-character display codes | No UAES/BYD supplier split or software-code binding; P222800 and P222900 each have two different titles |
| Battery management | 206 DTC headings, 204 distinct display codes | P2BAE00 has two different titles in this same section; BMS033/software applicability unproved |
| Front drive, generator, right rear drive, left rear drive | Respectively 31, 32, 32, 32 headings; 126 distinct codes across these four sections | Raw-code encoding and software applicability remain unproved; the [2026-10-21 model catalog](#ecu-roles-and-reverse-catalog-crosswalk-2026-10-21) now assigns SMCU left / SMCU3 right |

The index itself is not consistent enough for an automatic import. In the BMS
branch, `P2BAE00` appears both for a boost contactor fault and an extreme pack
undervoltage condition. In the engine branch, the duplicate `P222800`/`P222900`
titles distinguish electrical circuit faults from internal pressure-sensor
diagnostics. These are **observed catalog conflicts**, not alternative verified
meanings for this car. Do not resolve them by choosing the first row, by majority
vote or by a generic SAE description. All three differences persist in the
original Chinese capture. `P1A2000` is a separate duplicate whose Chinese titles
differ only by a leading zero-width space; it is not counted as an
incompatible-meaning conflict.

Opening the engine introduction/list (`DTA1343324`, `DTA1343330`) and the BMS
fault list (`DTA1248942`) returned a login requirement. The drive fault-list
locators are `DTA1415144` (front), `DTA1415099` (generator), `DTA1414996`
(right rear) and `DTA1414952` (left rear). Some front-drive/generator headings
name Fudi (`弗迪方案`) and BorgWarner (`博格华纳方案`) variants; the initial
browser translation rendered Fudi as BYD. A subsystem name alone cannot select the
definition for our installed software. No login, purchase,
protected-page retrieval or vehicle request was made. The public index supplies
precise sections for a future authorized document acquisition; it does not
establish the content or correctness of the protected tables.

There is also a code-format gap. The actual `59 01` replies in
[`obd-20261020.json`](../research/vehicle-diagnostics/obd-20261020.json)
report format01 for FMCU7E3 and ISG7E6, while the public headings use P-prefixed
display codes. Their conversion to the six-hex-digit raw UDS code needs an
explicit OEM mapping. A J2012 conversion must not be assumed for format01.
The two rear controllers report format00. Side evidence was missing on 2026-10-20;
the [2026-10-21 Xtool model catalog](#ecu-roles-and-reverse-catalog-crosswalk-2026-10-21)
now names SMCU744 left rear and SMCU3 745 right rear. This supports selecting the
corresponding candidate section, without proving its software applicability.
Preserve the complete source display code, subsystem, outline locator and
provenance; leave the raw-code mapping unknown until qualified.
[`oem-dtc-acquisition-20261020.json`](../research/vehicle-diagnostics/oem-dtc-acquisition-20261020.json)
pins the seven target identities, actual DTC formats, public document locators
and missing evidence. The companion
[`oem-dtc-public-index-20261020.json`](../research/vehicle-diagnostics/oem-dtc-public-index-20261020.json)
retains all 829 observed subsystem/code/outline locators and short original
Chinese headings in Git. Both retain source hashes; neither contains qualified
fault definitions or protected manual procedures.

The two rear sections use disjoint code ranges: left rear `P1B0301`–`P1B0320`,
right rear `P1B0401`–`P1B0420`, each heading naming its side. So a format00
record matching one of them was a proposed way to investigate the side binding.
The model catalog now supplies independent side names, but these DTC ranges remain
unqualified for the installed software. Neither controller has reported a code yet.

Product use (2026-10-20, owner's decision): the diagnostic section shows these
headings, machine-translated into Russian
([`oem-dtc-public-index-ru-20261020.json`](../research/vehicle-diagnostics/oem-dtc-public-index-ru-20261020.json)),
as a record's description, naming the catalog once per screen. Only format00
codes are matched; the conflicting headings are all shown, none chosen. Rules and
code: [architecture §4](vehicle-diagnostics-architecture.md#diag-arch-codec).
This does not qualify the index as a dictionary.

A second public catalog,
[Z9GT DMi diagnostic manual 2025](http://agame.vip/wendan/21331264), exposes
50 filenames, 44 containing DTC-like titles. Its preview covers refrigerators
and streaming mirrors only. It supplies neither engine/battery/drive tables
nor OEM provenance. HTTPS certificate validation failed; the public HTTP
catalog was read without credentials, and no protected document was requested.
Its presence is an acquisition lead, not independent validation of the first
catalog.

The [manufacturer declaration published by MIIT](https://www.miit.gov.cn/datainfo/dljdclscqyjcpgg/xcpgs390ssew/art/2024/art_e01d997f2e9f4e12879a75c5c4d0c68c.html)
provides a primary engine search anchor: model `QCJ7200AEHEV3`, whose linked
rear photograph carries the Z9GT badge, uses `BYD479ZQA`, 1995 cc, 152 kW
declared power / 150 kW net power. This identifies that published vehicle
variant. It neither matches the owner's individual car nor binds
`ECM_UAES_01200001` / `32440011E004000000` to any DTC table.

The additional head-unit corpus pass examined 54 extracted factory APKs,
1,930 parsed JSON resources, four nested ZIP inventories and the schema of an
eight-table voice SQLite database. Three new decompiles distinguish misleading
names: BydDataCheckTool writes shipping identifiers, BydSoftwareService manages
service activation/purchase/training, and BydAutoManual caches user-manual
pages/media without a DTC or ECU-software schema. `dtc_vendor` is the Device
Tree Compiler. No additional applicable dictionary or passive getter emerged.
This is bounded evidence: MyCar `app.gpak`/configuration binaries remain opaque,
45 JSON files were unparsed, 21 large animations were checked by headers only,
and two decompiles have documented gaps. It is not proof that every byte of
the head unit lacks diagnostic definitions, and supplies no new reason to
export private caches.

Evidence for this pass is retained under
`captures/diagnostics-oem-dtc-20261020/`, separated into `engine/`, `powertrain/`,
`local/` and `public-catalog/`. The next useful input is the actual OEM fault
list/diagnostic introduction with its revision and ECU applicability, plus the
display-to-raw mapping for format01. A matching, already downloaded EOL script
and its selection metadata remain another possible source on the head unit.

### The scanner on the car: first autoscan (2026-10-20)

The product section («Сервис → Диагностика», build `c2664f17` with `-Pdiagnostics`, APK
SHA-256 `bdc6ffbc…63da5`) was installed over the 0.7.1 release at 19:23 and opened by the
owner at 19:31, parked, 0 km/h. Steps 1–4 of the acceptance order
([architecture, 9](vehicle-diagnostics-architecture.md#diag-arch-acceptance)); the saved
scan is `captures/diagnostics-scanner-20261020/last-scan-1931.json` (untracked).

- **Helper.** The staged `denza-diag-bus-<sha256>.jar` (14 010 bytes) went from
  `app_process` to `READY` in 3.1 s, the 2 s of silence included. `autoservice` (PID 128)
  and `com.byd.avc` (4634) were the same processes before and after; the crash buffer
  stayed empty.
- **Autoscan.** 68 of 68 routes answered: 62 were rechecked by `22 F194`, five used
  their qualified stock routes and `7E5` used OBD identity; 68 memories were read
  (UDS mask `09`, or the OBD DTC services for `7E5`).
  148 exchanges in 19.9 s, no gateway non-answer, no negative response, no `7F xx 78`.
  An exchange (`set()` to the last frame) took 18 ms at the median, 48 ms at p90 and
  503 ms at most; request to request was 120 ms at the median (p90 158, max 670) at the
  default 100 ms gap.
- **Memories with records.** 62 blocks report none. The reported status is masked by each
  block's availability (`09`: `0x28` reads `0x08`; IDC's availability is `0x29`).

  | Block | Records | Read before |
  | --- | --- | --- |
  | `720` LA_BCM | `C1160-06`, `U029D-87`, `U0121-87`, `B2148-02`, `B2250-00`, `B225B-00`, `U0229-87`, `U022A-87` | the same eight, `19 02 FF` 2026-10-18 ([Readable DTC](#readable-dtc-codes-and-native-detail-view-2026-10-18)) |
  | `747` R_ZCU | `C1170-09`, `B130E-01` | the same two ([OBD and more modules](#obd-and-more-modules-through-the-same-transport-2026-10-20)) |
  | `723` INS | `B23D9-02`, `U0266-87` | first read |
  | `741` Media (head-unit MCU) | `B1AC8-00` | first read |
  | `7A7` CCS/SCPA | `P230E-07` | first read |
  | `597` IDC | `C1E20-49`, `C12D0-1C`, `C12D0-1D`, `C3200-7B`, status `0x28` | first read |

- **Defect: a busy channel after the first idle minute.** At 19:33:20 the app dropped the
  idle helper at its 50 s reuse limit and started the next one 0.6 s before the outgoing
  helper had let go of the reader lock: `LOCK_BUSY`, and the section stood «Канал занят»
  under every screen until «Опросить снова», live data included. Fixed in `50001ac8`: the
  helper waits up to 3 s for the lock, and a busy or lost channel is tried once more by the
  next request made by hand. On the car from 19:41: the helper restarted at 19:46, 19:49
  (after its idle minute) and 20:11, ready each time in 2.2–2.8 s, no `LOCK_BUSY`.
- **Defect: a codes table longer than two rows** drew its rows under the status
  explanation (LA_BCM, eight). Fixed in `f316d5f4`: the explanation stands under the last
  code and the table scrolls.
- Open: the stream (step 5) and the screens with the owner (step 6).

## Consumption journal

Added 2026-08-25, on the owner's decision. Not yet run on the car.

> **Superseded 2026-09-24:** three things below are no longer true. Both screens show the last **10 km** of recorded road, not 3 km (`ConsumptionWindow.KM`, since 2026-09-05); a line is `odometer,kwh,km,knownKm`, and two-column files are refused and wiped (`ConsumptionJournal.COLUMNS`, since 2026-09-07); and the journal has run on the car - the 2026-09-24 drive left 122 buckets over 12.2 km with no screen watching — see [energy contract §2.2](energy-display-contract.md#22-consumption-over-the-last-ten-kilometres) and [§8](energy-display-contract.md#8-open-and-what-closes-each).

The consumption chart used to hold twenty-four bars of 200 m - one window of
4.8 km - and it lived in the app's process, so it started empty after every
restart. The journal now keeps **300 bars of 100 m**, while the active cluster
dashboard always displays the latest **3 km**. The retired head-unit panel had
offered **3, 10 or 30 km**; its selector and saved setting were deleted on
2026-08-27, so an old installation cannot silently choose the cluster window.

**One hundred metres is one odometer tick**, which is as fine as this vehicle can
be asked; the odometer arrives in tenths of a kilometre. The log stores that one
resolution. The 30 km span is only the journal's retention horizon; the active
3 km view takes the newest thirty raw buckets, one per bar. The historical
10/30 km folding code was removed with the selector rather than preserved as a
test-only implementation.

**The journal is append-only lines, not JSON**, and that is a durability
decision rather than a taste one. A bar closes every hundred metres - three or
four seconds at highway speed - and the power goes away without warning when the
car is switched off. A JSON document must be rewritten whole to add one entry, so
an interrupted write costs the entire history; a line appended to the end costs
the tail. Format is `odometer,value`, `%.1f` and `%.3f`, in
`filesDir/consumption.log`.

**The odometer is stored with each bar**, and that is what makes a restart safe.
Time cannot say whether a journal describes the last thirty kilometres - the
entries could be five minutes old and cover a different road - but an odometer
can. On the first sweep that reports one, anything further back than the 30 km
retention horizon is dropped, and a journal whose entries sit *ahead* of the car is refused
outright: the reading went backwards, so it is not this car's journal.

**Any other kind of wrong wipes the file.** Unreadable, unparseable anywhere but
the last line, larger than the format allows, a write that throws - all end in an
empty journal, which costs at most the drive so far. A single bad *last* line is
a write the ignition interrupted and is dropped in silence. `ConsumptionJournal`
takes no Android dependency so all of this is covered by ordinary unit tests.

Writes are batched ten bars to a flush and each flush is `fsync`ed, so a sudden
power cut loses at most a kilometre. The file is bounded at 500 lines and trimmed
back to 300 through a temporary file and a rename; the slack is there so the trim
runs about once every fifty kilometres instead of on every append.

The active window is deliberately not a setting. The cluster has no touchscreen
and is now the only consumer, so it uses the fixed 3 km default every time. The
removed preference is not read and no hidden selector state survives the
head-unit panel's retirement.

### Two corrections this reading forced

- **`0x14400020` is `ENGINE_POWER` in the catalog**, and it is the id the cluster
  currently labels pack power. Parked on AC charge it read `-2` while the
  charging device reported `+2.4` kW with the engine stopped, so it is not the
  engine's own output — but the label is an assumption, and the drive capture
  that settles the sign should settle the meaning at the same time. If it turns
  out to be combustion power, the whole consumption block is mislabelled.

  > **Superseded 2026-09-22:** the drive capture settled both: with the engine never started the id read +10…61 kW accelerating, so it is the pack's power, positive out of the pack, not combustion power — see [energy contract §8](energy-display-contract.md#8-open-and-what-closes-each).

- **Vehicle speed exists and reads**: `SPEED_AUTO_SPEED` = `0x94400008`
  (dev `1013`), on transact **7** as a float, `0.0` parked. `SPEED_AUTO_SPEED_121`
  = `0x12100008` agrees. An earlier note in this session said speed was absent;
  that was a search of this document, not of the catalog. With speed the live
  consumption figure can be instantaneous (power over speed) instead of a
  rolling odometer window, and a standstill is detectable immediately rather
  than after five seconds of a still odometer. Note `0x94400008` sets the high
  bit, so it reaches the shell as `-1807745016` — the signed-decimal rule the
  tyre ids used to cover.

## Raw CAN-FD callback (2026-09-03)

An owner-controlled local ADB shell can passively receive the raw frames already
selected by the stock `CanDataCollect` service. This is a confirmed diagnostic
path. It is not a normal-APK product API and it does not expand the product
availability claims elsewhere on this page.

### Live result

The observation ran on the Denza Z9 (`AutoType=170`) with DiLink 5.1 and this
firmware fingerprint:

```text
BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys
```

The vehicle reports `sys.car.protocol=CANFD`. There is no Linux SocketCAN
interface such as `can0` and no `candump` or `cansniffer` binary; the data path
is the vendor vehicle service rather than a SocketCAN device.

The passive probe ran as shell UID 2000, registered only for
`BIGDATA_DYNAMIC_DATA_CALLBACK` (`0x99000020`, signed `-1728053216`), and kept
the main Android `Looper` running. During 15 seconds it received 2,054 callbacks,
about 136.9 callbacks per second. It printed only the first 200 frames while
continuing to count the rest, then unregistered the listener and exited. The
temporary device-side files were removed after the run. The power-state getters
reported `ACC=1` and `MCU=1` during this observation.

The probe did not call `sendRegisterTable`, any setter, MCU wake, or service
restart. It observed the table already maintained by the active stock
`com.byd.CanDataCollect` system process.

### Callback and frame envelope

The confirmed device and event are:

| Field | Value |
| --- | --- |
| BYDAuto device | `BYDAutoBigDataDevice` (`1061`) |
| Event FID | `BIGDATA_DYNAMIC_DATA_CALLBACK` (`0x99000020`) |
| Stock receiver | `CanDataCollectService.recv_can(byte[])` |
| Delivery model | streaming callback; not a retained latest-value getter |

The observed byte envelope is:

| Bytes | Meaning |
| --- | --- |
| `0..3` | CAN identifier, big-endian |
| `4` | sub-ID |
| `5` | channel |
| `6..9` | rolling counter or timestamp, big-endian |
| `10..` | payload |

Observed payload sizes were 8, 16, 32, and 64 bytes, on channels 0, 1, and 2.
Recurring identifiers included `0x08C`, `0x223`, `0x343`, `0x12D`, `0x302`,
and `0x495`. Raw payloads are intentionally not stored in this document.
Static inspection also found that the stock service always includes `0x08C`
on channel 0, `0x223` on channel 1, and `0x343` on channel 2 at a nominal
100 ms interval. The observed callback stream includes more identifiers because
the service's active table contains its full current configuration.

### Important corrections and boundaries

- An initial run reported zero callbacks because its helper slept on Android's
  main thread. `BYDAutoDeviceManager` posts listener delivery through a
  `Handler` bound to the main `Looper`; the corrected helper called
  `Looper.loop()` and received the stream. The zero-frame result is withdrawn.
- `getBuffer(1061, 0x99000020)` returned an empty byte array. This is consistent
  with a streaming event and rules out treating the FID as a latest-frame
  getter.
- The shell process's Android `Context.checkPermission` result was denied for
  `BYDAUTO_BIGDATA_GET` and `BYDAUTO_POWER_GET`, while the native vehicle service
  accepted the listener because its own read check permits UIDs up to 9999.
  A normal installed app has a different UID range, so this result does not
  prove direct registration from Denza Apps.
- The current Denza Apps debug certificate is different from the stock
  `CanDataCollect` certificate, and Denza Apps is not system UID 1000. Direct
  app-process delivery therefore remains unproven and must not be assumed.
- The alternative BYDCross transport did not expose a general raw-CAN mirror:
  a 12-second passive observation of its vehicle-transport events received no
  frames.

`com.byd.eventcenter` is a static integration candidate, not a live-confirmed
path. The persistent system app scans component metadata named `can_msg_event`;
the value `Bigdata/BIGDATA_DYNAMIC_DATA_CALLBACK` asks it to register the
big-data listener and deliver `byd.intent.action.GET_EVENT_CENTER_MESSAGE` with
`event_center_data` containing `event_center_type=1`, `eventType`, and
`bufferDataValue`. No package-change observer was found, so a newly installed
manifest may not be noticed until EventCenter next initializes. It also invokes
the target component for every event, which needs performance validation at the
measured callback rate before any product use is considered.

### Minimal passive API shape

The working diagnostic harness followed this lifecycle:

```java
BYDAutoBigDataDevice device = BYDAutoBigDataDevice.getInstance(context);
device.registerListener(listener, new int[] { 0x99000020 });
new Handler(Looper.getMainLooper()).postDelayed(() -> {
    device.unregisterListener(listener);
    System.exit(0);
}, durationMillis);
Looper.loop();
```

The essential detail is processing the main `Looper`; sleeping on that thread
prevents callback delivery. The harness does not replace or extend the stock
collection table.

## Washer fluid level (2026-09-18, in progress)

### N9 paired-state capture (2026-10-16)

This experiment is on **N9, AutoType 168**, not the earlier Z9GT. The owner
reported liquid in the reservoir for the baseline, then used the stock washer
pump until empty, without touching the sensor connector, and refilled with
**1 litre**. The owner explicitly states that this volume should return the
sensor to **OK**; use it as the expected normal control state, not an assumed
still-low state. The physical switch was not independently measured.
The label is `liquid-present`, not a measured volume or proof that a level
switch is above its threshold. The car reports `sys.car.protocol=CANFD`, model
`n9`, and fingerprint
`BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260705.011226:user/release-keys`.
Live SHA-256 checks match the existing framework, HAL and native vehicle-service
corpus; the installed framework supplies the device/FID catalog itself.

`tools/washer_capture.py` and `tools/VehicleStateSnapshot.java` make the
experiment repeatable. Each state records two sequential passes of every
device/FID pair in the installed `BYDAutoDeviceFeaturesMap`, using only scalar
GET transactions 5 and 7 (float fallback on `-10013`), at one pair per 10 ms or
slower. Read errors, unsupported types, `65535` and other placeholders remain
raw values. This is a timestamped sweep, not an atomic snapshot. No SET, array
transaction, UDS request, ECU wake or stock collection-table change is issued.
The temporary shell helper has an eight-minute watchdog and is removed after
completion; it installs no APK and changes no Denza Apps code.

The same capture includes 180 seconds of stock-selected raw CAN, service and
app-service dumps, read-only `car_status` / `dicare_record` provider queries,
logs, process IDs, firmware hashes, command results and source hashes. Data
stays in ignored `captures/washer-n9-20261016/`. Buffer-only properties,
unselected CAN frames and inaccessible service internals are not covered.
An empty dump is not evidence that an ECU has no state or faults.

The initial three-minute passive inventory saved 29,680 callbacks across
130 `(CAN ID, sub-ID, channel, payload length)` addresses on channels 0, 1 and
2, with zero drops or callback errors. This N9 selection contains `0x055` and
`0x3D9` sub-IDs 3, 4 and 5; it does not include every BCM frame. The broader
paired baseline lives under `liquid-present/`, with `manifest.json`,
`fids.tsv`, `raw-can.log`, `summary.json` and file checksums.

**Baseline completed 11:26:18–11:30:01 Moscow time.** All 10,003 catalog pairs
from 48 device groups were attempted twice (20,006 rows); 9,983 pairs had
identical replies across the two passes. This counts unavailable values too:
the first pass has 3,021 `-10011` replies, 1,337 `65535` placeholders and
656 type-mismatch replies that triggered float GETs. The accompanying
three-minute raw capture has 29,362 frames / 127 addresses, zero sequence gaps,
zero drops and zero callback errors. Sixty-four addresses remained byte-for-byte
constant. The small address-set difference from the first inventory is retained,
not filled with invented samples. Both scalar passes read parking-brake switch
`0x05500030=1` and zero speed (`0x94400008`, float). The catalog names the former
`GEARBOX_PARK_BRAKE_SWITCH`; it is not independent proof of selector position P.
The subsequent empty and refill comparisons are recorded below.

All recorded commands exited successfully, although eight native service dumps
were empty, including `autoservice`, `diagnosticsrv` and the UDS service;
`car_status` returned rows and `dicare_record` had none. The stock CAN-config
directory remains permission-denied to shell. `autoservice` and `CanDataCollect`
retained PIDs 128 and 3584, the crash buffer was unchanged, both temporary
helpers exited, and all saved-file checksums verified. Self-comparison produced
zero scalar changes and zero separated bits.

**Empty capture completed 11:47:37–11:51:24 Moscow time.** The owner's state
and drain-method confirmations are retained in `physical-state-notes.json`.
The identical tool/framework/HAL captured all 20,006 scalar rows and 29,817
raw frames / 130 addresses, again with zero gaps, drops or callback errors.
Both passes still read parking-brake switch 1 and zero speed. All commands completed, file hashes
verified, both service PIDs were unchanged, the crash buffer was unchanged,
and the temporary helpers exited.

`compare-present-empty.json` and `empty-analysis.txt` preserve the comparison:

- 147 device/FID pairs differ between captures; 60 have a stable but different
  reply in each state. The named changes include doors, hood, windows, seat
  positions/occupancy, suspension, temperature, energy and time. These do not
  establish a washer-level signal. The rear-wiper speed setting
  `0x47500030` also changed `0 -> 1` after using the pump; that setting is not
  evidence of fluid level. All six device-1046 Wiper-map replies were unchanged.
- `INSTRUMENT_DD_FAULT_WASH_ELEC_GATE` (`0x3D90201E`) stayed zero; the known
  brake/coolant-level GETs stayed zero too. The first seven bytes of raw
  `0x055` are identical in all 147 preflight, 150 liquid-present and 154 empty
  samples. No payload bit in that address meets the state-separation test.
  This does not exclude a signal whose low threshold was already reached in
  the unmeasured liquid-present baseline.
- 215 raw payload bits across 37 addresses have a between-state one-fraction
  difference of at least 0.9. Requiring perfect separation against **both**
  earlier liquid-present raw captures leaves 89 bits, still including known
  environmental/vehicle-state changes. No bit is labelled as washer level.
  All baseline raw addresses recur; the three extra empty-state addresses
  already occurred in the earlier preflight, so their appearance is not itself
  a reservoir signal. `car_status` is unchanged, and neither `dicare_record`
  query returned rows.

**Refilled capture completed 11:57:58–12:01:43 Moscow time.** All 20,006 scalar
rows were captured, with 9,890 pairs stable across both passes; the raw capture
contains 29,600 frames / 130 addresses, again with zero drops, callback errors
or sequence gaps. All command statuses and file checksums pass. Vehicle/build,
firmware and acquisition-source hashes match across all three captures. Service
PIDs and the crash buffer remain unchanged, and both temporary helpers exited.
Both scalar passes still read parking-brake switch 1 and zero speed. All six
Wiper-map replies, the known brake/coolant-level GETs, the `WASH_ELEC_GATE`
property and both provider-query results remain unchanged across all three
states. The first seven bytes of `0x055` also remain identical in all 162 refill
samples; its remaining byte varies within captures and was not identified as
a level signal.

`compare-empty-refilled.json`, `compare-present-refilled.json` and
`cycle-analysis.json` preserve the full-cycle comparison:

- Empty → refilled changes 161 scalar pairs (27 stable in each capture);
  baseline → refilled changes 178 (63 stable in each). Only **nine** pairs
  are stable within all three captures and exactly return to their baseline
  value: the two hood properties, low-voltage-side voltage, key-battery level,
  right electric step position, and four ADAS target-5 properties. None is a
  washer-level mapping. In particular, the hood follows `0 → 1 → 0`, so a
  perfect reversal alone is insufficient.
- **51 bits across 18 raw addresses** reverse with at least 0.9 separation on
  both transitions and at most 0.1 difference between the baseline and refill
  one-fractions (minimum 20 samples in each phase). Thirty-five reverse
  perfectly; 27 also agree exactly with the earlier preflight. These are
  correlated candidates, not 51 independent sensor signals.
- Whole-byte histories reject a binary-sensor interpretation for several
  apparent bit reversals: for example, raw `0x10D` byte 3 rises through
  `166/167 → 170/171 → 173/174`, while bit 2 nevertheless returns `1 → 0 → 1`.
  Similar effects occur in battery and other changing numeric fields. Raw
  `0x294` byte 2 bits 4–5 correspond to the hood, and `0x4C9` byte 20 to the
  right electric step position. Raw byte indices here are **zero-based**;
  recovered MCU descriptor byte coordinates are one-based.
- Two still-unmapped byte-wide fields in `0x324`, sub-ID 0, channel 1,
  deserve retaining rather than assigning a guessed meaning: byte 9 is
  `91 → 90 → 91`, byte 24 is `93 → 92 → 93`, constant within each raw capture.
  The recovered MCU table describes these as eight-bit fields
  (`0x32400050`, `0x324000C8`), not single-bit flags; adjacent named descriptors
  concern engine/energy data. This suggests numeric confounding but does not
  prove their semantics. The MCU image is not live-hash-matched, and its
  descriptors alone do not establish the active N9 mapping. Other unmapped
  reversals remain in the report as well.

**Later same-refill control:** a further 60-second passive capture saved
9,823 frames / 127 addresses, with zero drops, callback errors or sequence gaps,
as `refilled-late-raw.log`. Its source log and checksums are retained in
`refilled-late-analysis.json`. In `0x324`, byte 9 advances to **92** in all 59
samples, so its low bit returns to the empty-state value without another
reported drain; this rejects that bit as a simple stable binary level flag.
Byte 24 stays 93. This observation weakens the earlier two-byte coincidence;
it does not identify either field. Other apparent reversals also drift while
the reservoir is unchanged. The raw helper exited and both service PIDs remain
128 / 3584. No second scalar sweep was made for this short control.

The refill did not hold every other vehicle state fixed: the hood returned to
baseline, and door/step, steering and other readings changed. Before promoting
any candidate, separate these effects from level. No product warning, setter,
installation, connector manipulation or arbitrary FID probe is justified by
these correlations. No identified signal in this selected stream is **not**
proof that the physical sensor, a BCM signal or an unselected CAN frame is
absent.

**Same-fluid hood-open control completed 12:09:14–12:13:10 Moscow time.** The
owner confirmed opening the hood without another drain or refill. Acquisition
used the same unmodified tools, vehicle and firmware as the earlier states:
20,006 scalar rows / 10,003 pairs, 9,981 stable pairs, and 29,208 raw frames /
127 addresses, with zero drops, callback errors or sequence gaps. All command
statuses and saved-file checksums pass; service PIDs and the crash buffer are
unchanged, and both temporary helpers exited. Speed is zero in both scalar
passes and the parking-brake-switch value remains 1. Wiper-map values,
`WASH_ELEC_GATE`, known brake/coolant-level getters and both provider queries
remain unchanged. Raw `0x055` first-seven-byte contents also remain unchanged.

The evidence lives in `refilled-hood-open/`, with pairwise reports
`compare-refilled-hood-open.json` and `compare-empty-hood-open.json`. Reproduce
the multi-control classification with
`python3 captures/washer-n9-20261016/analyze_hood_control.py`; its saved result
is `hood-control-analysis.json`. It validates complete, loss-free captures,
catalogs, source/firmware/vehicle identity and checksums before comparison.

- Both hood FIDs (`0x2940001C`, `0x26100019`) now read 1 twice, giving
  `0 → 1 → 0 → 1`. Raw `0x294` byte 2 returns from 85 to 101 in all 179
  hood-open samples. The physical open-hood action confirms this positive
  control; its bits 4–5 are not washer-level evidence.
- Of the original 51 reversing bits, **34 across 10 addresses** remain within
  0.1 of their refilled one-fraction in **both** the later unchanged-fluid
  capture and the hood-open capture. Twelve instead resemble their empty-state
  value in the hood-open control; only the two known hood bits are established
  as hood signals. Numeric carries/drift explain several other such matches;
  the control does not make all twelve causally hood-related. Five fail the
  other stability conditions. The retained 34 still include the known right
  electric step, battery-related numeric bits and unmapped changing bytes;
  they are not 34 plausible independent level switches.
- The eight-bit field at **`0x324`, sub-ID 0, channel 1, byte 24 (zero-based)**
  remains **93 in all 178 hood-open samples**. Its six observed histories are
  preflight 93, baseline 93, empty 92, refill 93, later refill 93, hood-open
  refill 93. Thus it survives this control, unlike the hood fields. Its
  recovered descriptor is `0x324000C8`, but its meaning is still unknown:
  a one-count change of an engine-adjacent byte could be unrelated numerical
  variation. Do not implement a `92 = empty` rule or invent a sensor polarity.
  **Subsequent unchanged-level watch refutes that simple rule:** the field
  returns to 92 without any washer use or fluid change, confirmed by the owner;
  see the watch result below. Passing the hood control did not establish a
  level signal.
  Byte 9, previously rejected as a stable binary flag, changes `92 → 91`
  within this hood-open capture despite unchanged fluid.
- A separate empty-versus-two-refilled-states filter, without requiring the
  original baseline to match, leaves 97 raw bits and 13 scalar pairs stable
  within all four broad sweeps. All 13 scalar pairs have other named meanings
  (climate/audio, wireless charging, voltage/battery, step and ADAS data);
  this also does not identify washer level. The full pairwise comparisons
  retain every other change instead of assuming the hood was the only one.

This control removes the known hood ambiguity but does not establish the
sensor mapping. A surviving field still needs repeatable level correlation
with other states controlled, or source-side sensor/ECU evidence, before use
in Denza Apps. The acquisition is finished; no additional reservoir or
connector manipulation was requested for this control.

**Unchanged-level watch, approximately 12:26–12:29 Moscow time:** at the
owner's request, another 180-second passive window recorded 28,896 frames,
zero drops/callback errors/sequence gaps, and a complete DONE footer. Of the
178 target `0x324` frames, the first **47 contain 93**, followed by **131
containing 92**, with a single transition about 47.8 seconds after the first
target sample. This is a sustained return to the former empty-state value,
not a lone sample or parser error. Raw `0x294` hood bits remain in the known
open state for all 178 samples. Known scalar GETs at 12:28:25 read speed 0,
front-wiper level 1, wiper relay 0 and hood 1; those isolated reads alone do
not prove the entire pump history.

After being told about the transition, the owner explicitly confirmed:
**no changes, no washer use**, and no fluid addition/removal. The same 1 litre
was still present. Thus **`92 = empty`, `93 = OK` is not a valid warning rule**
under the owner-confirmed control conditions, nor is its changing low bit a
confirmed binary level flag. This does not identify the true numeric quantity
or rule out every possible composite encoding, and says nothing about whether
the physical level sensor exists.

Evidence: `watch-01-raw.log`, `watch-01-run.txt`, `watch-01-context.txt`,
`watch-01-analysis.json`, `physical-state-notes.json` and `watch-plan.json`.
`analyze_watch.py` validates completion/loss and reports exact target-address
histograms and timestamped value runs, with a raw-file SHA-256. The initially
planned limited follow-up watch was stopped early after this falsification:
thread automation `n9` is paused, not left running against the car. No product
logic or sensor warning was added.

**Offline candidate recheck after the watch:**
`captures/washer-n9-20261016/analyze_remaining.py` validates all seven saved
raw windows (DONE, counts, no loss/gaps), the four full capture manifests and
their hashes, then re-evaluates the original 51 bit correlations. The saved
`remaining-analysis.json` reports **51 → 34 → 14 → 13** after late/hood control,
unchanged-level watch, and finally requiring the initial liquid-present
preflight to agree too. `0x391` byte 39 bit 2 fails that last control: it had
the empty-associated bit value while fluid was present in the preflight.

At that stage, the remaining 13 bits occupy only seven byte locations, all sub-ID 0,
channel 0, 64-byte payloads. Coordinates below are zero-based/LSB0:
**These are historical survivors, not current candidates:** the cycle-02
normal baseline rejects the two `0x391` bits below, and its subsequent empty
window leaves all other 11 bits unchanged. None survives as a simple repeatable
level flag across both owner-labelled cycles.

| CAN ID | Byte | Bits surviving the correlation filter | Why this is not yet a level flag |
| --- | --- | --- | --- |
| `0x35A` | 3 | 5 | Whole byte spans 72–105 across the windows and changes within unchanged-level controls. |
| `0x391` | 43 | 3 | Byte history 87 → 119 → 125 → 162/163 → 196 → 196 → 196/197 is compatible with an unrelated changing number. **Subsequent cycle-02 liquid-present baseline rejects the simple flag: bit 1 in every sample, previously the empty-associated value.** |
| `0x391` | 45 | 2 | Low nibble advances through 2/3 → 3 → 4 → 8/9 → 9 → 9 → 11; other bits also vary. **Subsequent cycle-02 liquid-present baseline likewise returns bit 1 throughout and rejects the simple flag.** |
| `0x4A0` | 15 | 5 | Whole byte spans 72–102; behaves similarly to `0x35A` byte 3, without an established field mapping. |
| `0x4A0` | 16 | 3–7 | Five adjacent bits flip together in 132 → 126 → 128/130 → 130 → 130/132 → 132, compatible with a numeric carry. |
| `0x4CB` | 39 | 1–3 | Three adjacent bits arise from values 41 → 38/39 → 40 → 40 → 40/41 → 41. |
| `0x4CB` | 56 | 1 | Empty window itself includes both bit states: byte values 57, 58, 63 and 64. This passes the 90% filter, not an exact switch test. |

Whole-byte variation does **not** by itself disprove a packed independent
flag; field widths and meanings remain unknown. No surviving bit overlaps a
descriptor in the recovered MCU tables, even when matching CAN ID/sub-ID
across all table-channel keys. That is a corpus limitation, not evidence that
the sensor is absent; the installed MCU image was not hash-matched.

**Collection-table boundary and additional passive streams:** the installed
N9 `CanDataCollect.apk` was read without installing anything. Its remote and
local SHA-256 is
`df3d7efac4f3892b0df6c7528256343ed64070daf8038b07ec4b6533ba76c7e9`.
Its signed manifest/signature metadata match `reverse/candatacollect-jadx`;
all manifest-listed member digests match, including `classes.dex`. The copy
and provenance are under `captures/firmware/n9-eng.build20260705.011226/`.

The matched code establishes that the cloud/local collection configuration
is serialized into 8-byte CAN-ID/sub-ID/channel/mode/operation entries and
sent through `BYDAutoVehicleDataDevice.sendRegisterTable(3, …)`: **device 1048,
SET `0xAA000022`**, not a local listener filter. The stock service has remove
and resend paths and can wake the MCU before sending. We have not established
the exact MCU persistence/restore semantics or obtained the current table:
`/cancollect/dataCollect/canConfigureJson` is inaccessible to shell, and the
checked BigData/VehicleData APIs expose no table getter. In particular,
`DYNAMIC_REQUEST_MONITOR_TABLE` asks the client to send a table; it does not
return one. A listener's exit is not a proven rollback for a table write.
No collection-table command, cloud configuration refresh or MCU wake was sent.
The source trace and limitations are retained in
`captures/washer-n9-20261016/reports/can-filter-static.md`.

There is nevertheless a read-only expansion path: the framework has separate
existing whole-frame receive callbacks for GB, YUN and OTA. The bounded host
probe now accepts those fixed stream names; non-BigData buffers are preserved
verbatim as `BUFFER` records, not silently treated as turn-parser frames.
Four **sequential 20-second** availability windows produced:

| Stream | Device / callback | Received buffers/frames | Distinct address tuples | Absent from all earlier BigData windows |
| --- | --- | ---: | ---: | ---: |
| GB | 1039 / `0x9900001F` | 1,881 | 43 | 25 |
| YUN | 1034 / `0x99000021` | 2,485 | 69 | 51 |
| OTA | 1032 / `0x9900001E` | 0 | 0 | 0 |
| BigData control | 1061 / `0x99000020` | 3,294 | 127 | 0 |

GB/YUN buffers fit the BigData 10-byte envelope: four-byte BE CAN ID, sub-ID,
channel, four-byte millisecond counter, payload. All observed packets have
valid standard IDs/channels and legal CAN/CAN-FD payload lengths; counter
spans agree with host elapsed time to a few milliseconds with no backsteps.
This is a structurally checked envelope interpretation, not decoded signal
semantics. Address tuples include ID, sub-ID, channel **and payload length**.
GB/YUN share four new tuples, so their union adds **72**, not 76, to the old
130-tuple set: **202 historically observed tuples**. The four new windows
alone contain 199. Neither number is a full-bus coverage percentage, and the
sequential windows are not a simultaneous bus snapshot. OTA silence means
only no callbacks during its 20-second window, not permanent unavailability.

All windows completed with zero drops/callback errors/queued records and no
sequence gaps. `autoservice` PID 128 and `CanDataCollect` PID 3584 remained
unchanged; the crash buffer was identical before/after. Listeners exited and
their temporary dex was removed. No APK, table, service or physical sensor
state was changed. See `streams-*.log`, `streams-analysis.json`, reproducible
`analyze_streams.py`, and `reports/passive-stream-plan.md` plus health captures
in the same evidence directory.

**Expanded initial state, cycle 02, 13:57:06–14:06:45 Moscow time:** at the
owner's request, a new liquid-present baseline recorded three sequential
180-second windows using the already-verified collector, without table writes.
This label is not a fresh measurement of the reservoir volume or sensor voltage;
the earlier reported refill was 1 litre, expected OK by the owner. The user was
asked to keep the fluid level unchanged and not operate the washer throughout.

| Stream | Messages | Address tuples |
| --- | ---: | ---: |
| BigData | 29,533 | 127 |
| GB | 16,517 | 43 |
| YUN | 22,382 | 69 |
| Total / union | **68,432** | **199** |

All 72 extra tuples from the availability check are covered. Each window has
a complete DONE/unregister, contiguous host sequence numbers, zero drops,
callback errors and queued remainder, and almost exactly 180 seconds of frame
timestamps. YUN's embedded counter steps backwards twice, each by **1 ms**
between different channels; host callback sequence/time remains monotonic.
These observations are retained, not reordered or counted as lost callbacks.

Before/after known scalar GETs agree: speed float 0, hood 1, front-wiper level 1,
wiper relay 0. Raw hood bits 4–5 of byte 2 in `0x294` are 2 throughout the
BigData window (178 samples, channel 0) and YUN window (3,600 samples, channel 2).
Four firmware/APK hashes and service PIDs match before/after; the crash buffer
is unchanged. The temporary listener process exited and its dex was removed.
No APK installation, ECU wake, UDS or vehicle SET was performed.

Evidence is in `captures/washer-n9-20261016/cycle-02/liquid-present/`, including
verbatim per-stream logs, start/end/exit records, pre/postflight/context,
`state-notes.json`, source hashes and `summary.json` with per-byte histograms.
`cycle-02/analyze_state.py liquid-present` reproduces the validated summary.
`cycle-02/compare_prior_candidates.py` reproduces `prior-candidate-control.json`:
the new normal-labelled BigData window rejects `0x391` byte 43 bit 3 and
byte 45 bit 2. **11 old correlations in five byte locations remain**, still
not identified sensors at this intermediate stage. **The next empty window
rejects all 11 as simple repeatable level flags; see below.** Empty and refilled captures must use this same
three-stream order and duration; the old empty capture cannot supply the
missing GB/YUN data.

**Expanded empty state, cycle 02, 14:18:42–14:28:19 Moscow time:** after the
owner reported the reservoir empty, the same collector, source hashes, order,
queue capacity and 180-second windows recorded BigData **29,717** frames / 127
tuples, GB **16,631** / 43, and YUN **21,948** / 69. Total **68,296 messages**,
union **199 tuples**, with exactly the same per-stream address sets as the
liquid-present baseline; no missing/new or undersampled (<20) addresses.
All DONE/unregister and sequence/queue/error checks pass. Firmware/APK hashes,
service PIDs and crash buffer match both the earlier state and end of this
capture. Probe exited; its temporary dex was removed. No configuration writes.

Known GETs again read speed 0, hood 1, front-wiper level 1 and wiper relay 0
before/after. Raw hood field remains 2 throughout BigData (179 samples) and
YUN (3,600 samples). Thus this pair does not repeat the earlier hood-state
confound, although door/phone/energy/elapsed-time changes are still present.
The owner's empty label is not an independent electrical sensor measurement.

`cycle-02/compare_states.py liquid-present empty` revalidates both captures,
identity, source hashes, firmware/PIDs, stream order and duration before
comparing per-stream distributions. At the same ≥0.9 one-bit-rate separation
and ≥20 samples per state, it finds **223 unique bit locations**, 182 exact in
at least one stream. **37 are in the 72 extra tuples, across 12 addresses**;
the complete report retains all byte distributions and missing-address checks.
These are correlations, not 223 independent sensors or warning candidates.

`cycle-02/recheck_old_bits.py` finds **all 11 old survivors exactly unchanged**
between this new present/empty pair: `0x35A` byte 3 bit 5; `0x4A0` byte 15 bit 5
and byte 16 bits 3–7; `0x4CB` byte 39 bits 1–3 and byte 56 bit 1. Under the
owner-labelled states these fail the required repeatability, without denying
the physical sensor or every possible composite encoding.

`cycle-02/annotate_candidates.py` adds recovered MCU overlaps as **hints**, not
installed-MCU proof. It matches the sub-ID against the **low byte of the packed
table key**, not the scanner's unrelated `field` word. Of the 37 extra-tuple
bits, 27 overlap descriptors for doors, wireless charging, insulation, battery
percentage, electric range/consumption, driving time or PM2.5. Names requiring
normalization remain explicitly marked as hints. Unmapped changes include
`0x3DA` byte 2, `0x41E` byte 2, `0x447` byte 7 and `0x46C` byte 7. In the 335
combined `0x447` samples, all eight payload bytes sum to 255 modulo 256;
byte 7 changes inversely with the SOC-containing byte. This is checksum-like
behavior in these samples, not evidence of an independent level flag.
At this intermediate stage no washer-level mapping was established. All
candidates were retained for the refill reversal below, rather than promoting
a numeric carry to a warning.

Evidence: `cycle-02/empty/` (including `summary.json`),
`compare-present-empty.json`, `compare-present-empty-mcu-hints.json`,
`old-bits-second-cycle.json`, the scripts above and per-state checksums.
The comparator's synthetic flip/missing/undersampled/identical/unequal-count
checks pass; comparing the complete baseline to itself gives zero changes.

**Expanded refilled state, cycle 02, 14:36:30–14:46:15 Moscow time:** the owner
reported adding **1 litre**, expected to put the sensor in its OK state. That
expectation is the experiment label, not an independent electrical measurement.
The same sequential 180-second BigData/GB/YUN windows recorded **29,082 / 15,760 /
21,771 messages** (total **66,613**), with 130 / 43 / 69 tuples (union **202**).
All 199 tuples from both earlier states remain covered, including all 72 extra
GB/YUN tuples, with ≥20 samples per common address. BigData additionally sees
`0x1CD` (2 samples), `0x236` (94) and `0x3E8` (2), all sub 0/channel 0. These
also occurred in older normal **and empty** captures, so their mere presence
is not a repeatable level indication. `0x236`'s historical payload layout changes
remain unresolved; absent windows are not filled with zeros.

All three DONE/unregister, frame sequence, zero-loss/error/queue and duration
checks pass. No embedded-counter backsteps occur in this refill capture.
Firmware/APK hashes, service PIDs and crash buffer are unchanged. Known GETs
remain speed 0, hood 1, front-wiper level 1, relay 0 before/after; raw hood stays
2 for all 177 BigData and 3,600 YUN observations. The temporary process exited
and its dex was removed. No vehicle configuration, table or service was changed.

`cycle-02/compare_three_states.py` revalidates all three captures and requires a
candidate to return in **every stream that separated the present/empty pair**:
≥0.9 bit-rate separation from empty, ≤0.1 difference from the initial state,
and ≥20 observations per state. Of **223** original bit locations, **49** return
(40 exactly constant within all three windows); none is missing/undersampled.
This is a correlation screen, not 49 independent sensor candidates.

`cycle-02/check_historical_controls.py` tests those 49 against seven earlier
BigData windows, retaining both polarity and missing-data distinctions.
**43 contradict at least one earlier labelled control; none passes all seven.**
The remaining **6** are in extra GB/YUN tuples unavailable to those historical
BigData captures. They all overlap recovered multi-bit MCU fields:

| Stream / tuple | Byte / returning bits (zero-based, LSB0) | Three-window byte values | Descriptor hint, not installed-MCU proof |
| --- | --- | --- | --- |
| YUN `0x3D9`, sub 4, ch 2 | byte 1, bit 1 | 34 → 32 → 30 | electric driving range |
| YUN `0x3D9`, sub 6, ch 2 | byte 5, bit 2 | 86 → 90 → 93 | total electric consumption |
| GB `0x43A`, sub 0, ch 2 | byte 2, bit 6 | 202–231 → 147–183 → 99–154 | insulation value; refill bit is 1 in only 91.7% of samples |
| YUN `0x447`, sub 0, ch 0 | byte 4, bit 3 | 90–91 → 81–82 → 74–75 | battery SOC |
| YUN `0x4A5`, sub 5, ch 2 | byte 6, bits 1 and 3 | 94 → 84 → 74 | battery SOC |

None of these whole bytes returns to the initial value; a numerical change
can return one constituent bit. Descriptor names, especially masked aliases,
remain corroborating hints rather than a hash-matched N9 MCU mapping.

The previously unmapped bytes also do not establish a flag: `0x41E` byte 2
is 145 → 81 → mostly 81 (177/178 refill samples); `0x46C` byte 7 is 99 → 125 →
mostly 33 (115/135), with **no 99** in refill. `0x3DA` byte 2 changes from 8–11
to 5–6 to 8–22 and closely follows the PM2.5-containing `0x4F6` byte 1 in the
same YUN stream: nearest-time values agree in 139/144, 148/149 and 162/163 pairs.
Pair gaps reach 0.75/0.75/1.38 seconds; this supports a duplicate air-quality
value, not a proven alias or washer signal. The earlier checksum-like `0x447`
byte-7 observation remains limited to its stated samples.

Evidence: `cycle-02/refilled/`, `compare-empty-refilled.json`,
`compare-present-refilled.json`, `compare-three-states.json`,
`three-state-mcu-hints.json`, `three-state-historical-controls.json`, and
`refill-field-checks.json`. The new reversal comparator passes seven synthetic
tests, including missing data, undersampling, cross-stream disagreement and
bit-return without whole-byte return. Prior-state checksums were reverified;
the refill evidence and analysis sources have their own checksum manifest.

**Next useful step:** this expanded three-state cycle is complete, but a
physical washer-level input is **still unidentified**. Do not repeat draining
solely to re-test `0x324`, or turn the remaining numerical correlations into a
Denza Apps warning. Obtain the sensor part number, wiring/controller destination
and, if available, its named diagnostic live-data item from the service. That
would target the next read-only search and determine whether the signal reaches
the head unit's currently exposed streams. This result does not prove that the
physical sensor is absent or that no other CAN encoding exists. Earlier short
stream-availability windows still lack fresh physical-state labels and must not
be promoted to unchanged-level controls.

Expected observation: a physical level input, if exposed through either read
path, changes after emptying and returns after refill. Unchanged data or a
change that does not reverse is not enough to identify it. Keep the same
ignition/gear state and let the pump/wipers settle before comparing; separately
record door/hood changes. No sensor unplugging or assumed contact polarity is
part of this test.

```bash
python3 tools/washer_capture.py capture \
  --output captures/washer-n9-20261016/empty --state empty
python3 tools/washer_capture.py compare \
  captures/washer-n9-20261016/liquid-present captures/washer-n9-20261016/empty
python3 tools/washer_capture.py compare \
  captures/washer-n9-20261016/empty captures/washer-n9-20261016/refilled
```

The comparison checks car/build identity, firmware hashes, catalog consistency
and capture completeness/loss before listing changed scalar values and CAN
bits with at least a 0.9 difference in the fraction of samples containing a 1.
The completed refill reversal narrows the candidates but does not resolve the
unrelated vehicle-state changes. The host analyzer's synthetic tests cover a bit reversal,
missing completion, raw-frame loss, mismatched cars/builds and preservation of
the no-data sentinel.

### Offline firmware follow-up (2026-09-23)

**The newly readable archive opens another investigation path, but no washer
level getter or CAN bit has been identified.** The September 18 absence of a
Java name does not prove that the IVI never receives the sensor signal. The
older statements below that it was "never given the signal" and that the raw
stream is the "only" remaining path are stronger than the evidence supports.

Inspected the existing offline extraction of
`Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip`: `autoservice`,
`libbydauto.so`, `libbydautoservice.so`, `auto.default.so`, and the decoded
IVI MCU AppBlock. Their hashes were recomputed and agree with the extraction
evidence in [the HAL/MCU trace](speaker-lift-findings.md#offline-hal-and-mcu-trace-2026-09-23).
They have not been hash-matched to the installed vehicle binaries. No vehicle
connection, installation, service call or state change was performed.

> **Superseded 2026-09-23:** the installed `/system/lib64/hw/auto.default.so` and `/system/lib64/libbydautoservice.so` were hash-matched live to these archive files later the same day; the installed MCU image is still unmatched — see [Read path verified](#read-path-verified-sensor-identity-still-unproved-2026-09-23).

An English/Chinese name search in UTF-8 and both UTF-16 byte orders found no
washer-fluid-level label in these five files. More usefully, a bounded
structural scan recovered 890 candidate receive-table records, with field
coordinates, handlers and property identifiers. This is a structural inventory,
not proof that all records are active for this car. The archive covers multiple
vehicle/protocol variants; SDK names from different platform branches are
annotations, not verified meanings for the selected runtime branch.

Selected records demonstrate the deeper visibility:

| Receive record | Table address | Result of matching descriptors to the existing catalogs |
| --- | --- | --- |
| `0x055` | `0xD468C`, 16 descriptors | Includes `0x0550001C` (brake-fluid level) and `0x05500031` (coolant level); neither is washer fluid. |
| `0x133` | `0xD48BC`, 19 descriptors | Known light/stalk/wiper fields; no descriptor identified as washer level. |
| `0x233` | `0xD620C`, 6 descriptors | No descriptor identified as washer level. |

The literal identifiers `0x13300014`, `0x23300015`, `0x1330003C`,
`0x3D90201E`, `0x3D95D012` and `0x3D95D015` were absent from this MCU image.
That is only a literal-search result: some Java properties may be converted,
computed, belong to another build or use another protocol branch. It does not
establish that a sensor or a runtime API is absent.

Reproducible local evidence is in ignored
`captures/washer-firmware-20260923/`: `scan_washer.py`, `summary.json`,
`rx-table-candidates.json` and `selected-rx-tables.json`. The script only reads
local binaries/catalogs and writes reports; run it with
`python3 captures/washer-firmware-20260923/scan_washer.py`.

Next useful work is to identify the active receive-table/transport branch and
trace any semantically supported candidate through the HAL, or correlate a
passive raw capture with independently confirmed full/low reservoir states.
No current candidate justifies trying arbitrary FIDs or labelling an unknown
bit as washer level. A low/normal switch and a continuous volume measurement
are separate possibilities; neither the sensor type nor its polarity is proven.
The physical-state procedure below therefore needs a confirmed sensor/pinout,
not an assumption that an open connector means low. The recovered MCU is the
IVI controller, not a demonstrated body-controller or instrument-cluster ECU
firmware, so it may not contain the source-side sensor logic.

### Work without changing reservoir level (2026-09-23, second pass)

The owner cannot currently change the reservoir state. Offline investigation
can still make progress; a two-state capture is a semantic validation step,
not a prerequisite for reading the remaining firmware.

The saved September 22 vehicle dump
`captures/telematics-20260922/registration-evidence/diagnostic-flags.json`
records `sys.car.protocol=CANFD`. In the archive's HAL,
`AutoHardwareInterface::init_maps` (`0xB3460`) selects `FeatureListCanFD`
for type 1 and publishes that same property. Its vtable at `0x1D2220`
points to initializer `0x103020`. `initFromProperty` (`0xB3AC0`) also
selects type 1 for persisted `CANFD`. This identifies the relevant HAL
protocol family using historical runtime evidence, not a fresh car check;
it does not prove every MCU record is enabled for this vehicle.

A static registration-pattern scan of that initializer finds the familiar
GETs, including `WIPER_REAR_WIPER_WASH_GEAR` at `0x1081A8`,
`INSTRUMENT_DD_FAULT_WASH_ELEC_GATE` at `0x165B40`, and the brake/coolant
warning GETs at `0x169AD8` / `0x169B8C`. The latter three use converter
`0xE0C50`, which accepts unsigned values below 2 and otherwise returns
`-10014`. Registration proves an interface entry, not a sensor's presence
or the meaning of the ambiguous WASH label. It remains unconfirmed and
must not be presented as washer level.

The first structural MCU scan accepted only receive keys beginning `0xE0`.
Extending it to the observed `0xE1` and `0xE2` families yields **1,012**
candidate records, including 122 previously omitted ones. The additional
records include these one-bit fields:

| Receive key / table | MCU property | Descriptor coordinates | Possible SDK correspondence |
| --- | --- | --- | --- |
| `0xE203D902` / `0xD8A3C` | `0x3D90281E` | `[3,6,3,6]`, descriptor `0xF1044` | `0x3D90201E`, ambiguous WASH fault |
| `0xE203D95D` / `0xD8B3C` | `0x3D95D812` | `[2,2,2,2]`, descriptor `0xF16EC` | `0x3D95D012`, brake-fluid warning |
| `0xE203D95D` / `0xD8B3C` | `0x3D95D815` | `[2,5,2,5]`, descriptor `0xF1710` | `0x3D95D015`, coolant warning |

All three MCU identifiers differ from the SDK identifier by `0x800`.
This is a structural correspondence, **not yet a traced transport mapping**.
The report's `masked_name_hints` deliberately distinguishes candidate names
obtained by clearing `0xC00` from exact catalog matches. Do not send these
MCU identifiers to Android as guessed FIDs. None identifies washer level.

Local evidence now also includes `hal_registrations.py`,
`canfd-hal-registration-candidates.json`, `hal-selected.asm` and the expanded
receive-table reports. The linear registration scanner is an inventory of
instruction patterns, not full control-flow verification. Next offline work
can trace the identifier translation and stock consumers of the ambiguous
fault, and establish which corresponding raw records occur in saved captures.
No change to fluid level, car configuration or product code was made.

### Read path verified; sensor identity still unproved (2026-09-23)

The owner's requirement is a proven physical washer-level input that can then
be displayed in Denza Apps. There is no stock warning/display to use as ground
truth. The owner has requested the sensor part/connector/controller details
from the service shop; changing reservoir level is currently unavailable.
An API name, a readable zero, or presence in a generic catalog is insufficient
to identify this sensor.

This pass used the existing authorized ADB connection for bounded reads and a
20-second passive stock-BigData subscription. **The installed**
`/system/lib64/hw/auto.default.so` and `/system/lib64/libbydautoservice.so`
now have live SHA-256 matches to the archive files listed above.
`sys.car.protocol=CANFD` was also confirmed live. The installed MCU image
has not been hash-matched. `autoservice` remained PID 128 before/after;
the probe reported `UNREGISTERED` and exited. No APK, setter, stock collection
table change, restart or physical intervention was used. The temporary probe
dex was removed by the host runner.

The earlier `0x800` correspondence is now traced for this receive path:

- MCU `0x18A09C` extracts the descriptor field, updates its cache and calls
  `0x196EFA`; emitter `0x15CB58` serializes the full property ID and value.
- HAL `AutoHardwareInterface::updateParameters` (`0xB0A70`) parses the ID.
  For a fixed list of 18 CAN IDs, including `0x3D9`, instruction `0xB0D3C`
  clears bits `0xC00` with `0xFFFFF3FF` before lookup/update. Its own log
  calls this conversion to CAN0. There is a specific exception `0x44900818`;
  this is not a universal rule for arbitrary FIDs.
- Thus incoming `0x3D90281E` maps to SDK `0x3D90201E` on this path.
  Both identifiers are separately registered, but that does not mean both
  caches are updated. Integer initialization at `0x83FA8` sets the cache
  value to `65535`; that number must not be displayed as a sensor reading.

Eight narrow `getInt` reads returned zero for SDK WASH, brake/coolant warnings
and the two `0x055` fluid-level properties. The three corresponding `+0x800`
warning IDs returned `65535`, consistent with untouched caches. The WASH
label's physical meaning remains unproved; its zero is **not** evidence of a
nonempty washer reservoir.

The passive capture received **2,700 frames / 102 distinct addresses**, with
consecutive sequence numbers, zero drops and zero callback errors. Frame
`0x055` appeared 17 times: `(payload[2] >> 4) & 3` and
`(payload[5] >> 1) & 3` were both zero, agreeing with the immediately preceding
GETs for `0x0550001C` and `0x05500031`. This is a static-path and fixed-state
cross-check on known catalog signals, not a physical sensor-state experiment.
For `0x3D9`, only sub-ID `0x05` appeared (13 samples); sub-IDs `0x02` and
`0x5D` were absent from this 20-second selected stream. Absence here does not
prove absence on the vehicle bus or from the separate property transport.

Further archive inspection found no package explicitly identified as a body
controller firmware: the package list is Android, IVI MCU, DSP, ANC and Screen.
The recovered `diagnostic_config.json` contains 213 diagnostic definitions;
it and `BydHealthDiagnostic` did not reveal a washer-level definition. The
latter presents IVI diagnostic records from `diag_socket_channel`, including
system/camera/driver/MCU/audio categories; it has not supplied a named washer
sensor readout. No diagnostic app or socket request was invoked on the car.

Evidence is retained under `captures/washer-firmware-20260923/`:
`live-narrow-reads.json`, `transport-evidence.json`, `hal-updateParameters.asm`,
`hal-cache.asm`, `passive-can.log`, `passive-summary.json`, and diagnostic
extraction/decompilation artifacts. **Current result:** the generic read path
is supported by firmware and live observations; the physical washer signal
has not been identified. Product integration is premature.

The missing identity proof can come from an authoritative sensor-to-ECU signal
definition corroborated by an independent diagnostic readout, or a repeatable
controlled sensor-state change with independent confirmation of the physical
state. In either case retain the raw address/FID, value/validity encoding and
freshness behavior. Only then implement the supported low/normal or continuous
measurement in Denza Apps; unavailable data must remain unknown. Part number
alone helps locate the circuit but does not establish a CAN bit.

The owner reports that the car never shows a low-washer-fluid warning anywhere,
and that the dealer says the reservoir sensor is physically present. This
section records what the head unit can and cannot know about that sensor. It
is written as the investigation runs; each subsection says what was checked
and what it proved.

### The head unit has no name for it

The vehicle service's whole signal vocabulary is `BYDAutoFeatureIds` (about
30,000 lines in the framework). Three copies were searched: the MapHelper
catalog in `reverse/maphelper-jadx`, the framework table in
`reverse/speaker-lift/fw-fids`, and the copy bundled in the cluster APK pulled
from this car today (`eng.build20260705`, the newest build we hold). Searched
for `WASH`, `WASHER`, `WIPER`, `FLUID`, `LIQUID`, `WATER`, `GLASS`, `SPRAY`,
`FLUSH`, `CLEAN`, `KETTLE`, `POT`, `RESERVOIR`, `BOTTLE`, `LEVEL`, `LACK`,
`SHORTAGE`, `INSUFFICIENT`, and the Chinese 玻璃水 / 洗涤液 / 清洗液.

Nothing names a washer-fluid level. What exists near it:

| Name | FID | What it is |
| --- | --- | --- |
| `WIPER_FRONT_WASH_SWITCH_SINGAL_STATUS` | `0x13300014` | stalk pull, front washer pump command (new in the 2026-07 build) |
| `WIPER_WINDSHIELD_WASH_SWITCH_STATUS` | `0x23300015` | stalk pull, same family (new in the 2026-07 build) |
| `WIPER_REAR_WIPER_WASH_GEAR` | `0x1330003C` | rear wash stalk position |
| `BODYWORK_FRONT_WASHER_MEDIA_CMD_EXEC` / `_REAR_` | `0x20E09040` / `0x20E09044` | voice-command washer pulse |
| `INSTRUMENT_DD_FAULT_WASH_ELEC_GATE` | `0x3D90201E` | see below |
| `INSTRUMENT_B_M_BRAKE_FLUID_LEVEL_LOW`, `INSTRUMENT_B_M_COOLANT_LEVEL_LOW` | `0x3D95D012`, `0x3D95D015` | the two fluid-level lamps the vocabulary does have |

`INSTRUMENT_DD_FAULT_WASH_ELEC_GATE` is the only constant with `WASH` in an
instrument fault. It is almost certainly not the washer: in BYD's English,
`ELEC_GATE` renders 电子油门, the electronic throttle, and "wash electronic
throttle" is the throttle-body cleaning reminder that the older DD fault list
(index `30` in `BYDAutoInstrumentDevice`, next to `ADAPT_GEARBOX`) carried on
combustion cars. It answers `0` on devices `1007` and `1001` today, so it is a
mapped signal, just not this one. Its Chinese label has not been found in the
corpus; treat the reading as probable, not proven.

The 2026-07 build added 65 instrument names over the MapHelper catalog
(door/hatch-not-closed prompts, gear-switch prompts, EV-system faults, theme
mode, HUD arrow style). None concerns washer fluid.

`SpotInspection`, `EngineerMode`, and `VehicleConfig` resources contain no
washer-fluid string either, so the dealer-facing inspection app on the head
unit does not list it.

### The lamps are not drawn by Android

`/system/priv-app/BydClusterApp/BydClusterApp.apk` on this car (21.1 MB,
different from the 15.7 MB copy in `reverse/hud/apks`, now decompiled to
`reverse/cluster-jadx/`) is a thin shell: four `SimpleCluster*Activity`
classes, 100 strings, no telltale drawables. It reads `INSTRUMENT_SIZE` and
two dark/light-mode ids, and hosts `com.byd.cluster.projectionmanager`. The
head unit has exactly one physical display (`ivi`, 2560x1600); every
"cluster" surface is a 2560x720 virtual display owned by
`com.xdja.containerservice` (`fission_bg_XDJAScreenProjection` and the two
`shared_` variants) that is streamed to the instrument cluster.

So the cluster is its own ECU. It draws the telltales from CAN directly and
receives only a projected content region from Android. Whether a washer lamp
exists is decided in the cluster ECU firmware and by whether the body
controller transmits the level at all. The head-unit software cannot be "not
finished" for this lamp; it was never given the signal.

> **Superseded 2026-09-23:** "never given the signal" is stronger than the evidence: no Java name proves nothing about the IVI's receive tables, which the MCU firmware read now inventories — see [Offline firmware follow-up](#offline-firmware-follow-up-2026-09-23).

### The raw stream as a path (2026-09-18, not the only one)

> **Superseded 2026-09-23:** the raw stream is not the only path: the HAL and IVI MCU receive tables (1,012 candidate records, the `0xC00` CAN0 conversion traced through `updateParameters`) are a second, offline one — see [Work without changing reservoir level](#work-without-changing-reservoir-level-2026-09-23-second-pass) and [Read path verified](#read-path-verified-sensor-identity-still-unproved-2026-09-23).

The passive `BIGDATA_DYNAMIC_DATA_CALLBACK` stream described above is the only
place on the head unit where a body-controller bit could be seen without a
name. `CanDataCollect` (pulled today, decompiled to
`reverse/candatacollect-jadx/`) registers three ids unconditionally
(`addUrgencyId`: `0x08C` ch 0, `0x343` ch 2, `0x223` ch 1, 100 ms) and the rest
from a cloud JSON (`canCollectConfig` with `cycleCanConfigInfoList` and
`canCollectEventConfig`). The JSON carries ids, sub-ids, `netWorkIndex` and
byte sections (`canIdKeySectionStart/End`), not signal names. The table is
therefore whatever BYD's fleet telemetry wants, and it may or may not include
the frame that carries the reservoir switch.

A first 20-second inventory run today (Z9, `AutoType=170`, parked) was
truncated by the host pipeline and discarded; a clean 30-second run received
4,052 frames with zero drops. Summary of that run:

| Item | Value |
| --- | --- |
| Distinct (id, sub-id, channel, length) addresses | 102 |
| Channel 0 / 1 / 2 | 61 / 19 / 22 addresses |
| Payload lengths | 8, 16, 32, 64 bytes |
| Per-address rate | about 1 Hz for almost every address; `0x08C`, `0x223`, `0x343` at 10 Hz and `0x12D` at 20 Hz |
| Addresses whose payload never changed in 30 s | 44 |
| Addresses whose only change was a rolling counter or checksum in the last two bytes | about 25 |

The 1 Hz cadence is the table's `collectCycle`, not the bus rate, and it is
more than enough for a level switch. `0x38A` ch 0 (the turn-lamp frame from
the 2026-09-04 capture) is still in the table, so at least one body-controller
status frame is delivered. The stream includes many 64-byte CAN-FD status
frames on channels 0 and 1 (`0x2CB`, `0x302` sub 32..40, `0x324`, `0x3BD`,
`0x3FA`, `0x4A0`, `0x495`) and a block of 8-byte channel-2 frames that read as
body/comfort status (`0x36D`, `0x444`, `0x446`, `0x449`, `0x464`).

A reservoir switch is one bit that stays constant until the fluid state
changes. A single parked capture therefore cannot find it: on a full
reservoir it is one of thousands of constant bits. It can only be found by
difference, and only if the frame that carries it is in the table.

### What is owed, and the procedure

> **Updated 2026-10-16:** use the arbitrary-state paired capture above for the
> N9 experiment. No change in the selected stream cannot prove that the IVI or
> other ECUs never receive the signal. Do not unplug a sensor based on the
> historical normally-closed assumption below; its wiring/polarity is unproved.

To investigate the bit in the selected stream, one labelled
capture is needed with the sensor in both states:

1. Car parked, ignition on, doors closed, nothing else changing. Run
   `tools/raw_can_turn_probe.sh run 180` and type `MARK neutral_1` after the
   `READY` line.
2. Put the sensor into its "low" state and hold it: drain below the float, or
   have the dealer lift the float / open the sensor connector (a float switch
   that is normally closed reads "low" when open). Type `MARK left_1`. The
   analyzer's four labels are fixed to the turn-signal vocabulary; `neutral` and
   `left` stand for "ok" and "low" here until a state-diff analyzer with free
   labels exists.
3. Return the sensor to "ok", `MARK neutral_2`, then `STOP`.
4. `tools/analyze_can_turn_capture.py <capture>` ranks every payload bit by how
   cleanly it separates the labelled phases.

Outcomes:

- One bit flips with the label: the head unit can read washer level from the
  raw stream as shell UID, and the finding goes into this section with the
  address and bit. A product reader would still need the raw-stream lane that
  the turn-signal work deliberately did not build (see the boundary above).
- No bit flips: either the body controller does not transmit the level, or
  its frame is not in BYD's collection table. The head unit cannot tell these
  apart, and the question moves to the cluster ECU and the dealer's diagnostic
  tool.

Not done today: the labelled two-state capture (needs someone at the car who
can change the sensor state), and the Chinese label of `DD_FAULT_WASH_ELEC_GATE`.

## Targeted turn-signal events (2026-09-04)

An owner-controlled, read-only `app_process` probe registered a vendor light
listener for exactly two device-`1004` FIDs:

| Semantic event | FID | Live values |
| --- | --- | --- |
| Lever/switch phase | `LIGHT_TURN_SIGNAL_LIGHT_SWITCH_STATE` (`0x1330002C`) | Raw values `1..5` were observed; they are deliberately not named as intent |
| Confirmed flash mode | `LIGHT_TURN_SIGNAL_LIGHT` (`0x38A0002C`) | Live-confirmed: `1` off, `2` left, `4` right, `6` hazard; any other value remains vendor-defined raw data |

The controlled run covered neutral, left, right, manual cancellation, and
hazard. It delivered 16 events with zero callback errors. The confirmed flash
mode followed the initial switch event by 63 ms for both left and right. This is
the interval between the two vendor events, not an estimate of physical
lever-to-Binder latency.

The switch FID is retained as a raw typed phase only. Cancelling left briefly
produced `4 -> 1`, and cancelling right produced `2 -> 1`: the spring-loaded
lever crosses the opposite short position. Acting on that event could therefore
show the wrong camera. The confirmed flash event returned cleanly to `1` in
both cases.
It is authoritative for the lamps, but not for camera eligibility: the stock
`com.byd.avc` window still carries the vehicle's P/D/speed and camera policy.

A separate labelled raw-CAN capture found the same state in channel `0`, CAN ID
`0x38A`, sub-ID `0`, eight-byte payload, byte `4`: `0x15` neutral, `0x25` left,
`0x45` right, and `0x65` hazard. That capture received 21,146 callbacks with no
reported drop or sequence gap, about 137 callbacks/s; the selected raw stream
carried this particular frame only every one to two seconds. It is both noisier
and slower for this feature than the targeted semantic listener and is not a
product source.

### Product adapter and safety boundary

> **Superseded 2026-09-23** for the camera policy only (the hub, helper, two-FID allowlist and backoff below are unchanged): the window observer is no longer the only Show authority. Mirrors follows AVC's own mode and card; Show needs the card **and** the lamps flashing that side, the lamps leaving close the camera, and the quarantine and five-poll rules below are replaced — see [the firmware-model contract](instrument-display-findings.md#the-firmware-model-contract-2026-09-23).

The product uses a process-local typed `VehicleSignalHub` with
demand leases and a dedicated `MIRROR_EVENTS`-style source lane. It starts only
while at least one module owns a lease and registers only the union of requested
signals from the closed two-FID allowlist through the already trusted passive
local-ADB identity. Mirrors requests the confirmed flash mode and transient raw
lever phase while its foreground service is active. The source
publishes typed state with monotonic timestamps, source epochs, sequence
numbers, TTL, explicit missing reasons, and bounded per-subscriber event
mailboxes. A channel heartbeat never refreshes
data TTL: after five quiet seconds the helper explicitly re-reads only the
demanded getter(s) and publishes fresh
snapshots. There is no raw-frame API, arbitrary FID API, setter, new
`BYDAUTO_*` manifest permission, or exported EventCenter component.

Passive ADB authorization failure is terminal for the current lease activation;
it cannot create a prompt or a retry loop. Transient transport failures back off
from 500 ms to 60 s. A quick initial snapshot cannot reset that history; reset
requires a healthy sampled connection lasting at least 15 seconds.

The listener is packed as a dedicated thin jar, addressed and verified by its
SHA-256 rather than by APK version or file size. Since 2026-10-09 it is staged by
`ShellProxyStager` (`platform/shell/`), the same code as the split's task proxy,
which also deletes the listener's other copies (`denza-vehicle-signal-*` under
any other hash) in the round trip that checks the current one; until then every
build that changed the listener left its old copy in `/data/local/tmp`. The jar
now travels in pieces of at most 8 KiB of command line (the single line of
2026-09-04 below, about 10.2 KB with its frame, is the longest this shell has
been proven to carry), and the copy is checked by its hash before it is moved
into place. A staging failure still keeps the listener off; it never loads the
APK, and the next start checks the hash again. The
earlier 6,181-byte revision
was run passively from its exact built asset: it returned two initial `OFF`
snapshots, measured `0.0%` idle CPU and `54,738 KB` total PSS, and had accumulated
`0.49 s` of process CPU after startup. Closing its ADB stream removed the helper
immediately, and no orphan remained. The current asset is 7,449 bytes with
SHA-256
`1ca3d39b4f16f68f92404a31dfb5832ada8f3a41e6c3be94f34992a279d1195c`.
That exact asset was then live-smoked with the mode-only demand mask: it returned
an initial mode snapshot and a second getter snapshot 5.504 s later, both value
`1`, and exited without an orphan. One event lane may serve future approved
event signals; modules must not spawn one helper per signal.

> **Superseded 2026-10-09:** the 7,449-byte asset is no longer the current one.
> The jar now also carries `ShellProxyBootstrap` (`platform/shell/`), the looper,
> system-context and hidden-API lines every shell-UID helper used to repeat, and
> was 7,546 bytes in the build that moved them; its bytes and SHA-256 change with
> any build that touches either class, and the product checks the hash on the
> car rather than a recorded value. The live smoke above was of the 7,449-byte
> asset; the moved lines do the same calls in the same order.

The raw phase never selects a camera side. Known onset phases `2` and `4` can
only close an active Denza camera of the other side, immediately and before
stock AVC rebuilds its own surface. An onset for the side already starting or
showing is ignored, whether or not the lever passed neutral in between: the FID
emits several pulses per lever movement, and a same-side pulse can never be a
side switch. Follow-through `3`/`5`, neutral `1`, and unknown values are not
onsets. The 10 Hz stock-window observer is the only Show authority and
`MirrorTransitionReducer` the only producer of `Show`; nothing the listener
does on its own can open a camera. An unavailable, reconnecting or overflowing
source never hides an active camera or blocks an ordinary start/new stock window.
Every quarantine the stock caused (a lever preempt, a closed window, a direct side
change, an ambiguous window set) ends on a clean stock window: two consecutive
polls of one unambiguous side with the Denza runtime idle, the old local surface
detached, vendor `freeDisplay` complete, and no preempt in flight. The side that
was torn down cannot reuse a continuously surviving old window based on poll
count alone: the 2026-09-04 baseline proved that cancellation leaves that window
visible for 2.18–3.06 seconds and the old five-poll rule reopened it every time.
The cancellation-fix candidate requires a fresh matching confirmed-mode
observation strictly newer than the preempt, then retains the five settling
polls. A verification timestamp alone is not renewed intent. OFF, HAZARD,
unknown, missing, old and future-dated observations cannot rearm that path.
Alternatively, one unambiguous stock-window absence ends the old window cycle;
the next clean window uses the normal two-poll recovery without CAN confirmation.
This is a narrow disambiguator for a surviving preempted-side window, not a
global mode gate: ordinary starts and opposite/new stock windows stay window-led.
If no fresh same-side evidence arrives while the old window persists, it remains
stock-only until a new window cycle; actual quick same-side re-engagement still
needs live acceptance of this candidate.
Quarantines caused by our own failures (an AVC failure, a start or session
timeout, a lost or side-changed runtime, a failed dispatch) still wait for three
neutral polls, so a broken Show cannot loop.
Ambiguous stock windows while the reducer is idle are waited through only while
the retained raw phase still reports an engaged lever (`2`..`5`). The two
earlier contracts that gated Show on the listener are kept below as history;
their live acceptance does not transfer to this contract.

### Hub reliability boundary (2026-09-04, host-only)

This is a reliability change to the existing typed hub, not a new camera policy
or a telemetry migration. The helper's two-FID allowlist, registration, getter
cadence, reconnect/backoff and passive-authorization behavior are unchanged.

- Source observation and verification timestamps use Android elapsed realtime,
  including suspend; reception/publication is separate. The light decoder no
  longer re-verifies an old queued observation at its arrival time. Negative or
  future protocol timestamps force reconnect; an invalid retained-state clock
  is `Missing(INVALID)`. `maxVerificationAgeMs` bounds reads, not acquisition
  frequency or event-delivery age.
- Each event subscription has an owned asynchronous handoff worker, even when
  its supplied executor runs inline. A slow inline subscriber cannot occupy the
  source lane or another subscriber's worker. At most 16 handoff lanes are
  admitted per hub; each has at most one queued handoff and a 16-notice mailbox.
  Idle worker threads expire after 30 seconds. Closing shuts the worker down;
  an uncooperative running inline callback keeps its admission slot until the
  worker actually terminates. This bounds hub-owned resources, not an external
  executor's own resources. Admission failure is explicit, never silent.
- Overflow and executor rejection retain an explicit `Unavailable(AMBIGUOUS)`.
  Rejection retries only when there is new source traffic, including traffic
  concurrent with the failed submission; no idle retry timer is introduced.
  A consumer callback's ordinary exception cannot stop subsequent delivery.
  Closing suppresses queued callbacks, but cannot revoke one already running.
- A changed union of demanded keys still restarts this source. The hub clears
  affected state and queues a discontinuity for existing subscriptions before
  invoking stop/start, even when the replacement never connects. Equal key
  unions share the current activation. Old activations remain fenced even if
  a source reuses an epoch number. A source cannot invalidate another source's
  key. Hot registration changes are not claimed or implemented.
- Sources must publish serially in their own source order. Delivery is FIFO per
  subscription while healthy, not a global order across keys or sources. A
  retained snapshot is never manufactured into a transient event. The API does
  not yet offer atomic snapshot-plus-subscribe or observable polled state.

Verification is local, with no installation, car requests or live acceptance.
The initial RED run had seven assertion failures in eight new tests on the old
implementation; the same-key sharing control passed. Later independent
source-ownership and listener-exception tests also failed before their fixes.
Existing asynchronous tests now use explicit delivery barriers rather than
assuming callbacks are inline. An idle rejected-executor check protects against
a CPU-consuming retry loop.
The reproducible host check compiles actual production sources, replays RED
against the committed base, and tests individual semantic mutations in temporary
directories; compilation errors and timeouts are not counted as killed mutants.
A diagnostic-text-only control must survive:

```bash
python3 tools/check_vehicle_signal_mutations.py --red-ref 90821f0 --repeat 3
./gradlew :denza-apps:testDebugUnitTest :denza-apps:assembleDebug :denza-apps:lintDebug
```

Per-mutant output and a source-hash manifest go to the ignored
`build/reports/vehicle-signal-mutations/`. On the reliability delta based on
`90821f0`, all 30 focused tests passed in three clean host runs, all 17 semantic
mutants were killed by test failures, and the diagnostic-only control passed.
The committed-base replay had 10 failing tests. The complete app suite passed
1,209 tests with no failures, errors or skips; debug assembly passed and lint
reported 0 errors / 44 warnings (none in the changed signal files). This APK was
built only, not installed. These checks do not prove vehicle load,
sleep/wake behavior or end-to-end camera latency. Before adding polled inputs,
the next stage must separate freshness tolerance from supported sampling cadence
and make verification independent of other keys' traffic: the current helper
only re-reads after the whole event queue has been quiet. Then share one Park
read between Trip and the cluster without activating the cluster's entire hot
batch for a Trip-only consumer. No universal raw-CAN API or vehicle-action API
is part of this change.

### Installed adapter acceptance

The initial pre-sync debug APK with SHA-256
`0329c08b786556fa905060dc86ee1ce1fda0fb1889a904341e8ef6d3edec88df`
was installed on the same car on 2026-09-04. The staged jar matched the asset
hash above, Mirrors requested the mode-only mask, and exactly one
`denza_vehicle_signals` process remained resident.

The first controlled run held the car in P and applied left for about three
seconds, right for about three seconds, and hazard for about three seconds. The
diagnostic delta contained 60 directional-signal samples without an AVC window,
zero side mismatches, and no new unavailable samples. Hazard was not classified
as a directional request, the final state was `off`, and the camera runtime
remained idle.

The second run used stationary D with the brake held, then left and right for
about three seconds each before returning to P. Relative to the P-run snapshot,
the bounded counters advanced by 989 observations: 955 matches, two
signal-without-window samples, 32 window-without-directional-signal samples,
zero side mismatches, and zero unavailable samples. At the 10 Hz Mirrors
observation rate this is about 0.2 seconds of combined signal lead and 3.2
seconds of combined stock-window tail. The camera runtime reached generation 8
and returned to `IDLE`, `side=NONE`, `camera hidden`.

The Denza Apps main process, the one helper, and `com.byd.avc` remained alive;
the crash buffer stayed empty. The helper measured `0.0%` CPU while idle,
`62,371 KB` total PSS after the first run, and only `0.69 s` cumulative process
CPU after both runs and the diagnostic capture. This accepts installed staging,
passive event delivery, P gating, stationary-D agreement, and teardown of both
camera cycles.

After synchronizing the later chooser and spectrum work through commit
`fdb9b8272e348059c0185581b3985343cabb7626`, the combined debug APK was rebuilt
and installed with SHA-256
`ef168a36aa00a9dab4db26b52b683b2d3456a0d92b6dcfcbcaef4e64cb4ab94c`.
The installed `base.apk` matched that hash byte for byte, the staged helper
still matched the 7,449-byte asset above, and one helper remained resident. A
short final run applied left for about two seconds in P, followed by left and
right for about two seconds each in stationary D with the brake held. The final
bounded snapshot was
`samples=2107/2050/20/33/0/4`: observations, matches,
signal-without-window, window-without-directional-signal, side mismatch, and
unavailable respectively. Thus the P signal was observed without opening the
stock window, the stationary-D sides agreed with zero mismatch, and the stock
window retained its own trailing policy. AVC returned to `IDLE`, `side=NONE`,
generation 8, `camera hidden`.

No new crash-buffer entry appeared during that final ordinary shadow run. Across the
run and diagnostic capture the helper's cumulative process CPU advanced by
about `0.05 s`; its final total PSS was `53,989 KB`. This does not qualify
sleep/wake, lane-change, automatic cancellation, a safe rapid-side
implementation, R, moving speed thresholds, or another vehicle/firmware
profile. That candidate therefore remained shadow-only; the later guarded rapid
transition is qualified separately below.

### Rapid left-to-right failure capture

One later stationary-D run with the brake held deliberately switched directly
from left to right. The same installed APK produced this sequence:

- `20:04:12.024`: the 10 Hz shadow observer first saw CAN mode `left` with no
  AVC window;
- `20:04:12.149`: stock AVC started `PIP2MeterActivity`;
- `20:04:12.317`: Mirrors commanded its own left view, and at `20:04:12.505`
  CAN and the observed stock window agreed on left;
- `20:04:14.215`: stock AVC hid and destroyed its PIP surface during the side
  change;
- `20:04:14.335`: the existing `PIP2MeterActivity` received another intent;
- `20:04:14.345`: the shadow observer saw CAN mode `right` while the stock
  window still classified as left;
- `20:04:14.364`: `com.byd.avc` received `SIGSEGV`, only 29 ms after the new
  intent. The tombstone reports a null-pointer dereference at address `0x90` in
  `ANativeWindow_getWidth`, called by
  `libvideocatsdk_jni.so` `native_setSurface`, then
  `VideocatManagerImpl.setSurface`, `AvmController.setDisplaySurface`,
  `TSAPI.createDisplay`, and `PIPViewAlertController.modeChange`;
- `20:04:14.908`: ActivityManager observed the stock process death and removed
  its window; the persistent AVC process restarted as a new PID at
  `20:04:14.976`;
- `20:04:15.038`: Denza Apps, which never crashed, observed its camera runtime
  failure, entered `QUARANTINED`, hid its view, and later returned to idle. The
  shared CAN helper also remained alive.

The native fault occurred in stock AVC, not in the CAN adapter or the Denza
Apps process. The capture alone did not prove what made stock AVC enter that
surface race, so a control run changed only one condition: Mirrors was stopped
through its own service action while the rest of Denza Apps remained running.
That also stopped the demand-driven CAN helper. The same stationary-D,
brake-held, direct left-to-right manoeuvre then showed both stock camera sides
correctly. Stock AVC kept PID `17977`, produced no new `SIGSEGV` or exit-info
record, and completed normal PIP teardown. Mirrors was then restarted; its
service returned and exactly one new helper process became resident.

This paired result is strong evidence that the concurrently active Denza
Mirrors AVC display surface is a necessary condition for the reproduced stock
native surface race. One successful control is not enough to identify the
precise ordering bug or to prove that it can never occur without Mirrors. CAN
cannot repair the native AVC code or prevent stock AVC from receiving the
right-turn event. It may nevertheless let Mirrors release its own surface
*before* stock AVC rebuilds the opposite-side surface. Such a guard must react
to an actual switch edge rather than a 10 Hz snapshot, remain side-agnostic for
the raw switch phases, and keep stock AVC as the eligibility authority.
Directional mismatch remains useful as a fail-safe veto for a stale old-side
view, but the captured 29 ms post-intent crash window makes that late check
unlikely to prevent the native failure by itself.

Showing or retrying a camera without a stock window would additionally require
a separately proven eligibility policy (at minimum gear and speed, or a short
lease on recently confirmed AVC eligibility), so these runs do not justify
making CAN the camera owner.

### Guarded rapid-transition acceptance

The first active-guard canary APK, SHA-256
`00707767053650eace85b786220e66fc2ac6c0cc21bdcca13092d45e8d659197`,
was installed byte-for-byte on the same car. In stationary D with the brake
held, direct left-to-right showed the Denza left camera, then deliberately left
the right side to stock AVC for the rest of that turn. Stock AVC retained PID
`17977` and produced no new crash or exit-info record. This accepted early
preemption but also exposed that the general quarantine policy prevented a safe
same-cycle Denza reopen.

The narrowed recovery candidate, SHA-256
`0588de7d41f4059c25a6eb17e12c38e7ceffceff7ccf8a2e85c76826113e7864`,
was built from local `main` `75ded26f53195f111d41e17731cb04feffaa48db`,
passed 1,084 unit tests and Android lint, and was installed byte-for-byte. Its
single stationary-D, brake-held left-to-right canary produced this ordering:

- `20:51:44.108`: raw onset was observed; preemption was accepted 2 ms later;
- `20:51:44.112`: the old Denza local surface was detached, 4 ms after the edge;
- `20:51:44.224`: vendor release completed in 103 ms, and the preemption callback
  completed at `20:51:44.229`, 121 ms after the edge;
- `20:51:44.485`: stock AVC began creating the right display;
- `20:51:44.810`: confirmed right mode and the observed right stock window
  matched;
- `20:51:45.078`: after the stable-match gate, Denza issued exactly one
  `show RIGHT`; it reached `READY` at `20:51:45.371`.

The Denza right command was therefore issued 970 ms after the raw edge, 849 ms
after vendor teardown completion, and 268 ms after the first logged matching
right window. The user saw both Denza sides correctly and noticed the intended
short handoff pause. Turning the signal off detached the right surface in 3 ms,
completed vendor teardown in 120 ms, and returned through neutral to idle.

Stock AVC kept PID `17977`; Denza Apps and exactly one
`denza_vehicle_signals` helper also remained alive. No new AVC `SIGSEGV` or
exit-info entry appeared; the newest recorded AVC failure remained the earlier
`20:04:14` reproduction. This accepts one instrumented stationary left-to-right
guarded transition on this car/firmware. It does not yet qualify right-to-left,
lane-change or automatic cancellation, hazard during a session, sleep/wake,
moving speed thresholds, repeated stress, or another vehicle/firmware profile.

### Separated-cycle ambiguity regression

The later byte-matched APK
`e4b26fadc28082baa97c306ce429ee8ffdd640f455a6086df29fbb004fc1fae6`
exposed a separate fail-closed false negative during an ordinary
left-off-pause-right sequence. The left Denza view closed normally and the
reducer returned to `IDLE` at `20:58:24.837`. Stock AVC began its right display
at `20:58:24.992`; 40 ms later the monitor saw confirmed mode `right` but a
short-lived partial/overlapping stock-window set, classified it as ambiguous,
and moved `IDLE` to neutral-only quarantine. The stock window was unambiguously
right by `20:58:25.486`, but the quarantine correctly refused a late Show. The
user therefore saw only the stock right camera. No process crashed.

That transition is not itself authority to open a camera. The corrected reducer
only keeps waiting when all of these are true: a live raw switch edge is pending,
no side is yet eligible, the Denza camera runtime is idle, and the ambiguity is
in the stock-window observation. It issues no command in that state. Ambiguity
without a pending edge, or while a Denza runtime is active, still quarantines;
the eventual Show still requires the same-epoch higher-sequence confirmed mode
and the first exact matching window observation.

The corrected APK
`baa2635f32e030cddf4b8310a98559aeeaf6ae9abbca1af84e2b58675140b8e9`
passed 1,087 unit tests and Android lint and was installed byte-for-byte. One
stationary-D, brake-held left-off-two-second-pause-right run showed both Denza
cameras correctly. The left vendor release took 121 ms and the reducer returned
to neutral `IDLE` before the next turn. On right, signal and stock window first
matched at `21:03:01.767`; Denza issued exactly one `show RIGHT` 269 ms later and
reached `READY` at `21:03:02.385`. Stock AVC retained PID `17977`, and Denza Apps
and its one helper remained alive with no new crash or exit-info entry.

The transient ambiguous-window state did not recur in that successful final
run. Thus the separated-cycle behavior is live-accepted, while the exact new
ambiguity-wait branch has deterministic unit coverage but still lacks a second
live occurrence. This distinction must be preserved in future acceptance claims.

The post-acceptance hardening also reads the adapter's already-retained raw phase
inside the poll transition, so a short executor delay between hub publication and
the event callback cannot turn an active phase `2`..`5` into a false neutral-only
quarantine. This is an in-memory snapshot read and adds no vehicle request. A
pending onset is discarded fail-closed after 2 seconds (or an elapsed-clock
rollback), preventing a lost follow-up event from leaving recovery pending
indefinitely. These two paths are deterministically unit-tested; they were added
after the live run above and therefore are not claimed as separately live-proven.

The resulting hardening APK, SHA-256
`97a56057989788eb2a53a95e23e50f6cf1ddbf713d5539b64a8affa3691d6e04`,
passed 1,090 unit tests with zero failures/errors and Android lint. It was
installed byte-for-byte after the user rebooted the car; package-replacement
recovery restored Denza Apps, Mirrors, and exactly one signal helper. Stock AVC
remained alive as PID `5050`, and exit-info still ended at the deliberate
`20:04:14` failure. No physical turn cycle was run after this last hardening
build, so the exact hash is install-verified but not separately manoeuvre-tested.

### Same-side onset regression and final live acceptance

The first ordinary right turn with the hardening build exposed a deterministic
regression rather than an AVC or window race. Denza issued `show RIGHT` at
`21:30:51.969`; while the camera was still `STARTING`, a trailing raw phase `4`
arrived at `21:30:52.262`. The preemption path treated it as a new switch,
detached the just-created surface, and ignored the now-stale AVC-ready callback.
The user therefore saw only a momentary canvas. This proves that phase `4` can
also be part of the ongoing right-lever movement; phase `2` has the symmetric
meaning for an already active left side.

The corrected policy compares an onset with the side already `STARTING` or
`READY` and with the gesture that started that camera. A same-side onset keeps
the camera only until raw neutral or confirmed mode `OFF`; after either boundary
it is a new gesture and preempts the old surface. An opposite-side onset always
preempts immediately. It does not use the raw phase to choose a camera and does
not weaken the later confirmed-mode and stock-window Show gate. The
three-sample/200 ms window delay was also removed: once a post-onset confirmed
mode exists, the first exact matching stock-window observation is sufficient.
The old-surface detach and completed vendor `freeDisplay` barriers remain.

The live-tested intermediate APK, SHA-256
`4811ec32c8a8cf1ad5fa66b96482951ef03a7b278fa443e360c71dfc6b5e5268`,
passed 1,092 unit tests with zero failures/errors and Android lint and was
installed byte-for-byte. The user visually accepted three ordinary right turns.
The latter two reproduced a same-side phase `4` 206 ms and 262 ms after their
Show commands; both were ignored as intended, and AVC became ready after 257 ms
and 273 ms. The first cycle reached AVC ready in 281 ms.

One final stationary, brake-held direct left-to-right canary retained opposite-
side preemption: the left local surface detached 3 ms after right onset, vendor
release completed in 105 ms, and the right camera subsequently reached AVC ready
in 244 ms. The user saw both Denza cameras correctly. Stock AVC retained PID
`5050`, while Denza Apps and exactly one `denza_vehicle_signals` helper remained
alive. This accepts the corrected same-side handling and one direct
left-to-right handoff on this car/firmware; it does not broaden acceptance to
the other untested matrix entries listed above.

Post-acceptance hardening records the gesture boundary explicitly. Raw neutral
or a confirmed `OFF` observed after the camera Show prevents a subsequent
same-side onset from being mistaken for the earlier trailing duplicate; a
delayed neutral event timestamped before that Show cannot close the new gesture.
The resulting APK, SHA-256
`7462350e1a5588c1ffe6a36685a03f869ec947ec1e5ac718c498769424e98aea`,
passed 1,095 unit tests with zero failures/errors and Android lint. This final
boundary logic is deterministic-test accepted but was not reinstalled or
physically exercised before commit; the live acceptance above belongs exactly
to the preceding byte-matched APK.

### Window-only Show, onset-only teardown (2026-09-04, late)

> **Superseded 2026-09-23:** this contract and its quarantine recovery were replaced by the firmware-model contract, live-run on 2026-09-23; the raw-onset teardown is kept unchanged — see [the firmware-model contract](instrument-display-findings.md#the-firmware-model-contract-2026-09-23).

An independent review of the gated contracts against a passive filtered
`logcat -s` capture of two ordinary right cycles found that the randomness the
owner reported was designed in. (The main ring buffer holds under a second of
history on this car because of camera-HAL spam, so a filtered live capture is
the only way to keep a run.) The raw lever FID emits several pulses per
movement: `4` at 0 ms, `5` at about +100 ms, and a second `4 -> 1` pair at
about +300 ms; the stock right window was observed 186 ms and 194 ms after the
first pulse, and in the 20:04 run above the left view was commanded about
290 ms after the mode was first observed. With Show gated on a confirmed mode
event that had to be newer than the latest onset, a second same-side pulse that
landed before the window re-armed the pending switch after the only mode event
had already passed; the 2 s timeout then failed closed, and the turn stayed
dark until the next `OFF` event. Left turns were close to a coin toss on those
timings. The same fail-closed reset ran on every hub `Unavailable` notice (each
helper reconnect, sequence gap, or mailbox overflow, with reconnect backoff up
to 60 s), so a transport hiccup could hide an active camera and keep it hidden
for the rest of the turn. The gesture-boundary rule of the last gated build
re-created the same dark turn for a same-side pulse after the lever had passed
neutral.

The replacement contract is the one described under "Product adapter and safety
boundary" above: the window observer alone opens cameras, the onset alone
closes them, and reopening after a preempt waits for the other side's window.
`MirrorSignalSafety` and the gesture-boundary state were deleted. The reducer
and the preemption policy are unit-tested with a mutation ledger. The live
acceptance in the sections above belongs to the retired gated builds; the
early-teardown timings (3 to 4 ms detach, 105 to 121 ms vendor release) were
the only part carried over unchanged, and the first drive below confirmed them
on this contract.

#### First drive of the window-only contract (2026-09-04, 22:13 to 22:20)

Build 42 (`bf5f06cb…`) went on the car at 22:13:27 with the owner watching and a
filtered `logcat -s` capture running. Between 22:17:05 and 22:17:14 the owner
switched right-to-left, left-to-right and right-to-left again in quick
succession: each opposite onset detached the local surface 3 to 4 ms after the
pulse, vendor `freeDisplay` completed in 109 to 118 ms, the new side opened at
the second clean poll of its window (about 125 ms after the window appeared),
two same-side pulses were ignored while that side was showing, `com.byd.avc`
kept its PID and the crash buffer stayed empty. That is the left-to-right AVC
canary the earlier sections had left open, and it passed on this contract. A
three-blink comfort tap at 22:14 showed both cameras for the three blinks and
closed them 0.6 s after the stock closed its window, which is the stock's own
behavior.

One dark signal remained. At 22:17:16 the mode went off, at 22:17:19.2 the
stock closed the left window and the reducer hid the camera, and 125 ms later
the owner engaged left again; the stock left window was back after two empty
polls. The quarantine that follows a closed window only knew how to wait for
three neutral polls, so the Denza camera stayed dark for that whole 2.3 s
signal while the stock camera showed. The recovery rule above is the fix: a
stock-caused quarantine now ends on a clean stock window, and only our own
failures still wait for neutral. The adversarial review of the same build found
two more holes that the same change closes: the preempted side was blocked
permanently rather than for the stale window's lifetime (a lever knocked toward
the other side and returned lost the turn), and the status publication that
refreshes the whole app repository ran under the gate that the onset needs to
detach a surface, so it now runs after the gate is released. Two review items
stay open for lack of evidence: the ambiguity rule never fired tonight (none of
about thirty transitions), and the reopen counter still demands adjacent clean
polls. The revised build has not been driven yet.

## Interior ambient light colour (2026-09-23, in progress)

Question from the owner: can Denza Apps read the colour of the cabin ambient
light (BYD calls it the interior atmosphere lamp, `IAL`) and tint the spectrum
and the blue backdrop to match it. Read from the OTA corpus first
(`captures/split-firmware-20260923/jadx/framework`, reports under
`captures/ambient-light-20260923/reports/`); nothing has been read from the car
yet.

What the framework says (all values are the CAN-FD branch; `isCanFD` follows
`sys.car.protocol`, which this car reports as `CANFD`):

- The current colour lives in the MCU, not in Android. The legacy getter is
  `BYDAutoSettingDevice.getIALColor()` on device `1023` (Setting), FID
  `SET_INTERIOR_ATMOSPHERE_LAMP_COLOR = 283586` (`0x000453C2`). Per zone,
  `getIALColor(area)` reads `SET_IAL_FRONT_COLOR = 1121976336` (`0x42E00010`),
  `SET_IAL_BACK_COLOR = 1121976343` and `SET_IAL_ALL_COLOR = 175642`.
  Brightness is `SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS = 522414`; the zoned
  getters report it one higher than the 0..5 level.
- Whether that number is a colour or an index depends on the car. DiCar's
  `CarAmbientLightServiceImpl.getLightColor(zone)`
  (`reverse/speaker-lift/dicar-jadx/.../feature/light/CarAmbientLightServiceImpl.java:602`)
  first reads `SET_IAL_COLOR_CONFIG = 1072693258`. Type `1` means true RGB: it
  reads `LIGHT_FRONT/REAR/STAR_RING_AMBIENT_LIGHT_COLOR_R/G/B` (device `1004`,
  Light) and packs them with `rgbToInt`. Types `2..6` mean a palette: it returns
  the raw `SET_IAL_FRONT/BACK_COLOR` value, and the table that turns it into a
  colour belongs to the app that draws the picker. The legacy setter rejects
  anything outside `0..127`, and the listener guards `1..127`.
- Themes sit beside the colour: `LIGHTS_AMBIENT_LIGHT_THEME_STATUS =
  551575595`, `LIGHT_ATMOSPHERE_CUSTOM_COLOR = 657457168` and the main switch
  `LIGHT_ATMOSPHERE_MAIN_SWITCH = 1060110406`. DiCar's own listener also watches
  `SETTING_THEME_COLOR_FEEDBACK` and a lamp-linkage switch.
- No app-UID path is known. `libbydautoservice.so::checkGetPermission` waves
  through callers with UID ≤ 9999 (`0x270F` at `0x299E4`). Any normal app,
  UID ≥ 10000, falls through to the `BYDAUTO_SETTING_GET` / `BYDAUTO_LIGHT_GET`
  check, which is the 20004 recorded in the permission matrix above. Listener
  registration goes through the same gate. `content://carsettings/config` carries
  `atmosphere_lamp`, `atmosphere_lamp_multi_color` and
  `atmosphere_lamp_music_mode`, which are capability switches with no colour
  among them. The one untested lead is DiCar's `ICarAmbientLightService` from app
  UID, but it sits on the same `ICarPropertyService` binder that is recorded as
  blocked.
- The shell route works the same way as every other `autoservice` read the
  product makes: `service call autoservice 5 i32 1023 i32 283586`, where the
  second word of the reply is the value. `VehicleSignals.kt` has no device-1023
  or device-1004 entry yet.
- The reverse direction exists and is separate: stock `AmpVisualizerEffect`
  pushes the FFT to `AUDIO_MCU_AMBIENT_LIGHT_SPECTRUM_DATA` (`0x99000228`,
  device `1002`) to drive the music-rhythm lights; writing it needs
  `BYDAUTO_AUDIO_SET`.

What the settings app says (`com.byd.carsettings`, `AmbientLightFragment`;
report `captures/ambient-light-20260923/reports/settings-ui.md`):

- It never keeps the colour itself. It reads the colour back from the MCU through
  DiCar's `getLightColor` and `BYDAutoSettingDevice.getIALColor(area)`, and
  writes nothing to `Settings.*`, `content://carsettings`, broadcasts, system
  properties or shared files. Widgets are gated on MCU capability flags, not on
  the `carsettings` config keys.
- The value is a 1-based index. `SET_IAL_COLOR_CONFIG` picks the table: `3` is
  seven named presets, `4` is 31 colours, `5` is 64, `6` is 127, and `7` is
  raw RGB. The colours are hard-coded in `AmbientCarBody.java:36-38` and agree
  with DiCar's copy; all three tables are dumped to
  `captures/ambient-light-20260923/reports/palette-settings-ui.{csv,json}`.
- In the dynamic, flowing and music-rhythm modes only the mode number is
  readable, not the colour the lamps show at that moment.
- The one unguarded app-UID surface is DiCar's
  `registerAmbientLightingListener` (`ICarAmbientLightService` transaction 26,
  no permission check). On each change the service re-reads the colour under
  its own identity and pushes `onColorChanged(ColorEventData{mode, colorValue,
  area})` to every listener. It sends only changes, never the current value.
  Any client's unregister clears the colour subscriptions for every client
  until someone registers again. A client that is first to instantiate the
  service after DiCarServer starts leaves the service's own property
  subscription running under that client's UID, where it fails silently. Not
  tried on the car; it would be a probe, not product code.
- The wallpaper-to-lamp link (`ArtThemeTaskManager`, writes `0x4050003D`) runs
  one way and is off on this car, so no launcher theme colour mirrors the lamp.

Already read from the car without asking: the account app logs a settings
snapshot, and at 17:47:38 on 2026-09-23 it showed `SET_IAL_FRONT_COLOR = 5`,
`SET_IAL_BACK_COLOR = 5`, `SET_INTERIOR_ATMOSPHERE_LAMP_AREA = 3` (all), both
brightnesses `5`, `LIGHT_DYNAMIC_COLORFUL_SWITHCH_STATE = 2`, and
`hasThemeAtmosphere = 0` (`captures/hud-live-20260923/video-start.log`, line
280545). Index 5 is `#02A7FF` in the 31-colour table, violet `#7544D8` in the
64 and purple `#B123DD` in the 127.

Not yet known: the value of `SET_IAL_COLOR_CONFIG` on this car, which picks the
table. One shell read settles it (`service call autoservice 5 i32 1023 i32
1072693258`), or the owner saying which of those three colours the cabin
shows.

## Legacy BYDAuto events and system logs

The archived 2026-06-27 probe under
[`research/vehicle-events/`](../research/vehicle-events/) established that:

- `BYDAUTO_*_COMMON` permissions could be granted;
- `BYDAUTO_*_GET` permissions were not granted;
- listener registration succeeded but produced no useful app callbacks;
- direct getters failed with `SecurityException`;
- system logs still showed BYDAuto events and camera activity.

The 2026-07-24 passive shell inspection additionally saw:

- `BYDAutoStatisticDevice getSpeedSignalVDisValue` at roughly 1 Hz;
- high-rate `BydDms CanDriver updateSteeringSpeed` logs with uncertain
  semantics;
- PM2.5 and safety-belt events;
- frequent bodywork, settings, special, and sensor events.

These logs prove that stock system processes receive live vehicle state. They do
not provide a product path: Denza Apps has no `READ_LOGS`, and a normal APK must
not depend on shell log collection.

## Existing navigation-derived data

Denza Apps already validates visible Yandex guidance through its accessibility
service and can derive:

- maneuver;
- next road;
- remaining route distance and time;
- optional current/road text.

This is event-driven UI-derived data, not a stable navigation SDK. It is usable
only while the expected scene is visible and current. The existing staleness,
package allowlist, and fail-closed clearing rules must remain in force.

No generic route geometry, destination weather, or “road ahead” feed was proven
in this vehicle-data investigation.

## Product implications

| Concept | Inputs available now | Important caveat |
| --- | --- | --- |
| Road thread / journey trace | GNSS at about 1 Hz, altitude, bearing, calibrated IMU | Needs a real moving capture and axis calibration |
| Body-motion field | accelerometer + gyroscope at a proposed 30 Hz | Represents head-unit/body motion, not suspension travel or wheel impact |
| Trip event ribbon | GNSS stops/elevation plus IMU turns/vertical impulses | Event labels need deterministic, documented thresholds |
| Local daylight indicator | time + GNSS position | Useful but not vehicle telemetry |
| Route remaining time/distance | existing validated Yandex accessibility guidance | Only while guidance is visible and fresh |
| Road-surface memory | GNSS + calibrated vertical motion stored locally | Deferred; needs repeat-drive validation and false-positive analysis |
| Maintenance summary | exported car-status rows | Units/state semantics need confirmation |
| Regeneration/energy display | no qualified instantaneous current yet | Do not label `35721` as amps |
| Technical BMS / HV cluster dashboard | `autoservice` allowlist via `DenzaLocalAdb` | Cluster-only subset in `feature.vehicle`; shell-only, short allowlist, no `BYDAUTO_*` in the manifest. The old head-unit page and its 12V reading were retired 2026-08-27 |
| Tyre / climate / PM2.5 | same Binder, different `dev` | Same privilege path; still blocked from app UID |
| Raw CAN diagnostics | `BYDAutoBigDataDevice` callback `0x99000020` from local ADB shell | Confirmed passive stream; not proven from the Denza Apps UID and not yet a product input |
| Turn-signal guard | targeted shell-UID BYDAutoLight listener | Event-driven and low-CPU; a raw onset only tears down an active opposite-side Denza surface; the stock AVC window alone opens and reopens |

> **Superseded 2026-09-23:** the stock AVC window no longer opens alone: Show needs AVC's card and the lamps (`0x38A0002C`) on that side, and the lamps leaving close the camera — see [the firmware-model contract](instrument-display-findings.md#the-firmware-model-contract-2026-09-23).

> **Superseded 2026-09-05:** the dashboard row is not cluster-only: the strip's car page reads the same allowlist (`VehicleWatcher.STRIP`) — see [Vehicle telemetry wiring](#vehicle-telemetry-wiring-2026-08-22-to-2026-08-27).

The current road-thread/body-field prototypes use simulated values. They show a
candidate visual mapping only; they are not live-car evidence.

## Commands and inspected inputs

Core read-only commands:

```bash
adb -s 127.0.0.1:5555 shell dumpsys sensorservice
adb -s 127.0.0.1:5555 shell dumpsys location
adb -s 127.0.0.1:5555 shell service list
adb -s 127.0.0.1:5555 shell dumpsys package providers
adb -s 127.0.0.1:5555 shell content query \
  --uri content://com.byd.carStatusProvider/car_status
adb -s 127.0.0.1:5555 shell content query \
  --uri content://com.byd.carStatusProvider/dicare_record
adb -s 127.0.0.1:5555 shell service call autoservice 5 i32 1014 i32 1147142160
adb -s 127.0.0.1:5555 shell service call autoservice 7 i32 1001 i32 1128267816
```

Vendor inputs inspected locally and intentionally not committed:

```text
/system/priv-app/DiCarServer/DiCarServer.apk
/system/priv-app/BydClusterApp/BydClusterApp.apk
/system/priv-app/CarStatusProvider/CarStatusProvider.apk
MapHelper.apk  (bundled com.byd.feature.* FID catalog + BYDAutoConstants)
```

The first shell-only `app_process` harness was killed and was abandoned. An
initial no-display Activity harness then failed because Android requires a
`Theme.NoDisplay` Activity to finish before resume. A transparent temporary
Activity corrected the harness and produced the normal-UID permission matrix
above. These were probe-process failures, not vehicle-process failures.

## Next useful validation

GNSS/IMU research (unchanged from 2026-07-24; use an isolated probe rather than
restoring this pipeline to the product):

1. Isolated normal-APK recorder for standard accelerometer, gyroscope,
   gravity/linear acceleration, and GNSS.
2. Short drive with the head unit untouched; calibrate vehicle axes.
3. Profile the 30 Hz sensor loop for CPU/thermal.

`autoservice` widget (2026-08-22; stop catalog scanning):

1. One moving-drive capture of pack power, `STATISTIC_INSTANTANEOUS_CURRENT`,
   motor rpm/temp, and the rear-motor FID candidates.
2. Establish the real pack size from the owner's paperwork or one full charge
   session. Nothing on this Binder gives it: `0x44700028` turned out to be a
   second state of charge, and the panel no longer pretends otherwise.
3. Confirm the pack-power sign in motion: acceleration must read positive with
   `VehicleConvention.POWER_POSITIVE_IS_DISCHARGE = true`. If it reads negative,
   flip that one constant.
4. Measure traction-voltage sag under load. On this LFP pack the resting voltage
   is flat across the charge window — 550 V at 43 %, 551 V at 62 % — so current
   is the only thing that moves it. The cluster dashboard shows pack voltage as context, not as a
   gauge, until that sag figure exists.
5. Cross-check the odometer against the trip panel's GNSS distance over the same
   stretch; the consumption histogram's axis rests on the ×10 scale, and the
   stock home widget's 23.2 kWh/100 km over 50 km is the number it should land
   near.
6. ~~Record `sweepMillis`~~ **done**: the hot batch costs 129–195 ms and the full
   sweep 270–287 ms. The hot interval was set to 300 ms on that evidence; what
   remains is to confirm on a drive that the car keeps up with it under load.

> **Superseded 2026-09-24:** item 3 is done - acceleration reads positive, the constant stays (2026-09-22); item 1 is done for pack power and engine rpm (2026-09-22 and 2026-09-24 drives) but not for `STATISTIC_INSTANTANEOUS_CURRENT` or the motor temperatures, which the recorder does not read; and the hot interval in item 6 is 100 ms on screen and 1 s otherwise, not 300 ms — see [energy contract §8](energy-display-contract.md#8-open-and-what-closes-each) and [Vehicle telemetry wiring](#vehicle-telemetry-wiring-2026-08-22-to-2026-08-27).

Raw CAN-FD diagnostics (2026-09-03):

1. Keep using the stock `CanDataCollect` selection table; do not replace or
   extend it for observation-only work.
2. Add host-side capture timestamps, CAN-ID/channel filters, and loss counters
   before attempting a longer moving observation.
3. Correlate one known vehicle action at a time with one filtered identifier;
   keep unfiltered payloads out of repository documents.
4. If product integration is pursued, measure EventCenter delivery overhead at
   the observed callback rate before deciding whether that static candidate is
   viable.

Until the drive capture, the honest **app-UID** boundary remains GNSS plus
standard IMU plus fail-closed Yandex guidance. The honest **shell-UID**
boundary is the widget allowlist plus the passive raw CAN-FD callback above.
