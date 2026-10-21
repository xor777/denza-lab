# Vehicle DTC evidence

Current findings and scope are in [vehicle-data-findings.md](../../docs/vehicle-data-findings.md#diagnostics-and-dtc-2026-10-18).

- `supported-dtc-20261021.json` retains the exact-software supported-code
  registries: BMS 115, FMCU 32 and each rear drive 32. The ECM NRC13 and the
  initial ISG transport-shape abort remain explicit; the completed FMCU prefix
  does not turn that aborted plan into a successful scan. Candidate Russian
  headings and a separately labelled FMCU reverse-notation hypothesis are
  acquisition evidence, not qualified factory definitions. Run
  `python3 research/vehicle-diagnostics/check_supported_dtc.py` to replay wire,
  source/build hashes, health and cleanup. `decode_supported_dtc.py` supports
  complete runs by default; `--allow-partial` explicitly retains a validated
  completed prefix without inventing missing target results.
- `isg-gateway-trace-20261021.json` retains the separate ISG failure callback:
  gateway fragment 1/2 contains a partial CAN transcript. The owner-package MCU
  source explains timeout-transcript publication and record order; the retained
  prefix contains 26 complete tuples in 107 of 231 declared UDS bytes, not a
  successful registry. Run `python3 research/vehicle-diagnostics/check_isg_gateway_trace.py`.
- `isg-failure-report-20261021.json` retains the later completed `isg-trace`
  plan: both published gateway fragments, 20 native records, 125/231 UDS bytes,
  30 complete raw tuples and partial tail `183F`. The report is complete but
  the supported registry is not. Sequence wrap succeeds; the source recorder
  capacity is 106 records, and its filter can omit mismatched CAN frames.
  `python3 research/vehicle-diagnostics/check_isg_failure_report.py` verifies
  wire, frozen source/build, native anchors, health and cleanup offline. The
  committed `fixtures/isg-failure-report-20261021.txt` also runs without the
  firmware corpus through `python3 -m unittest discover -s
  research/vehicle-diagnostics -p 'test_decode_isg_gateway_trace.py'`.
- `ecm-request-analysis-20261021.json` binds the ECM evidence to software
  `32440011E004000000` and CALID `Z31L07AB01`. Stock code sends exactly `190A`;
  the MCU can synthesize NRC13 for a positive response above 920 bytes. The
  earlier count of 258 would require 1035 bytes as one list, a source-backed
  hypothesis rather than a proven cause: the ECM first CAN frame is missing.
  MCU also consumes NRC78, so Android callback silence does not establish CAN
  silence. Run `python3 research/vehicle-diagnostics/check_ecm_request_analysis.py`.
- `exact-dtc-definitions-followup-20261021.json` retains Denza ECU-version/manual
  acquisition leads and BYDTIS field-schema evidence, with zero qualified
  definitions. The six BMS misses and P2BAE00 conflict remain unresolved; the
  other-model contactor headings must not fill that gap. Run
  `python3 research/vehicle-diagnostics/check_exact_dtc_definitions_followup.py`.
- The supported-DTC evidence also retains `19 03` snapshot-index qualification:
  ECM empty, BMS 41 DTC/record pairs, front/ISG NRC12 and rear NRC31. Use
  `decode_snapshot_index.py` with the corresponding fixed plan. Returned record
  identifiers are neither status bytes nor proof of stored fault payloads.
- The exact BMS `19041A3A2201` read returned `59041A3A2200`: DTC/status only,
  without a snapshot record. The original build06 aborted on the short response;
  `decode_snapshot_record.py --allow-partial` preserves that lifecycle and the
  observed response. Callback bytes beyond the declared payload length are not
  snapshot data. No DID or engineering value was obtained. The subsequent
  build07 handles this as `SNAPSHOT_RECORD_EMPTY`, covered by offline tests;
  that newer reader has not been run on the car.
- `diagnostic-event-journal-plan-20261021.json` is a prepared collection
  contract for natural warning/alarm/DTC events on one monotonic clock. No
  recorder or background automation was started, and no warning/DTC causal
  relationship is qualified by this plan.

The supported-code reader stays outside product builds. Build offline with
`python3 tools/vehicle-diagnostics/build_supported_dtc_probe.py build --output
/absolute/fresh/build-directory`; the builder records immutable source copies
and runs synthetic protocol tests. The separate host runner
`tools/vehicle-diagnostics/run_supported_dtc_probe.py` has explicit `health`
and `run` actions, uses an existing ADB serial, verifies the pinned build and
owns only its nonce-named remote JAR. A run requires `--build`, a fixed `--plan`,
`--serial` and fresh `--output`. It never installs an APK or restarts ADB. Full
acceptance requires both host lifecycle/health proof and wire decoder success;
an incomplete run stays incomplete even if its process and file were removed.
The `isg-trace` plan is fixed to identity/format01/190A on ISG, with 15/17-second
soft/hard bounds and the original four-second request deadline. It separately
collects bounded result03 report fragments; `GATEWAY_TRACE_CAPTURED` is never
accepted as `SUPPORTED_READ`. Other plans retain their original failure policy.

- `DtcReadProbe.java` is the original bounded shell-UID research probe for the
  qualified 0x720 → 0x728 transport, retained as evidence rather than built.
- `dtc-20261018.json` is the small decoded real snapshot: eight raw codes, all
  status 0x28. It is never a production fallback.
- `decode_snapshot.py` and `test_decode_snapshot.py` replay the qualified
  single-frame envelope offline. Run `python3 -m unittest discover -s
  research/vehicle-diagnostics` from the repo root.

- `LbmsDtcReadProbe.java` qualified the second fixed target 0x762 → 0x76A;
  `lbms-dtc-20261018.json` retains its explicit zero response and support mask09.
- `readings-20261018.json` retains 43 qualified scalar GETs with raw words and
  device/FID/type/scale, replayed by `DiagnosticDataTest`. No vehicle identifiers.

- `ObdAddressReadProbe.java` (2026-10-20, owner-approved, unbuilt) reads through
  the same envelope with a fixed address list and a whitelist of read-only
  services: OBD `01`/`03`/`07`/`0A`, UDS `19 01/02` and `22 F197/F18A/F195`.
  It found the engine ECM at `7E0`, five more modules at `7E2`–`7E7` and
  answers from `747`, `726` and `722`. `F194` names `7E2` VCU, `7E3` FMCU, `7E6` ISG
  and `7E7` BMS against the OTA cache. `decode_obd_probe.py` turns its output into
  `obd-20261020.json`. Raw runs are under the ignored
  `captures/diagnostics-obd-20261020/`.

- `ecm-obd-telemetry-20261021.json` adds23 timed read-only ECM responses with
  the engine stopped: fuel pressure, catalyst/ambient temperatures, ECU voltage,
  two coolant sensors and the advertised80→A0→A6 chain. A6 matches the existing
  autoservice odometer raw value. PID44/6B boundary/format values stay raw; the
  full9-byte7A reply is one vendor fragment, not multi-fragment acceptance.
  `check_ecm_obd_telemetry.py` validates54 source hashes, request/response pairing,
  advertised support, arithmetic and four health snapshots without vehicle I/O.
  Product polling/decoders were not changed by this research.
- `ecm-completion-20261021.json` adds29 wire replies:14 remaining Mode01
  records of the expected shape, single-bank `415680`, and ECM CALID
  `Z31L07AB01` with its self-description. The collector stopped on PID56 without
  retry; its conditional bank3 byte was later resolved from J1979 and the local
  codec corrected, along with PID54's unsigned offset. Two supported Mode09
  passes completed. All43 advertised
  non-bitmap PIDs have now replied across retained passes, not43 qualified
  sensors. `check_ecm_completion.py` checks source hashes, exact stop/cleanup,
  scalar arithmetic, the new pre-existing APK baseline and nine damaged traces.
- `brake-vcu-obd-completion-20261021.json` adds23 bounded replies and8 new
  advertised parameters: all8 BSCM and9 VCU non-bitmap PIDs have now replied.
  Sequential coolant PID05 replies differ by ECU (ECM10°C, VCU30°C, BSCM10°C),
  so the source must remain attached to the value. This provides no new
  brake-specific sensors or physical relationship between BSCM7E5 and ESP782.
  `check_brake_vcu_completion.py` checks33 sources, coverage/arithmetic/health
  and six damaged transcripts. No product changes or APK installation.
- `powertrain-scalar-quality-20261021.json` traces13 drive/BMS scalar fields:
  a rejected front-current update preserves the old float cache, rear converters
  use a different range/offset, and initial cache values can narrow into ordinary
  GB words. These are source-derived quality limits, not observed invalid updates
  or newly qualified engineering units. `check_powertrain_scalar_quality.py`
  checks49 instruction anchors,12 MCU records,26 saved observations and full
  bounded16-bit/8-bit arithmetic domains offline.
- `bms-current-consumers-20261021.json` closes the adjacent charging-current
  chain: CAN444/channel2, selective FID canonicalization, two float caches and
  the stock GB current-source selector. Two historical GET pairs agree with
  the converter; the20 raw frames are a different time window. The18-ID
  canonicalization allowlist excludes345/44A, whose units remain unknown.
  `isg-source-boundary-20261021.json` pins the generation-state enum
  (0 INVALID,1 GENERATING,2 END,3 ERROR) and three broadcast candidates, none
  bound to ISG ECU telemetry. `bms-current-checker.py`, run with
  `.venv-firmware/bin/python`, checks15 sources,8 native anchors,3 AutoID
  variants,65536 inputs and59 retained frames without vehicle I/O.
- `battery-validity-followup-20261021.json` separates count validity, native
  array cache/completion and collection-policy status from battery health.
  It proves the count255 cache/callback divergence and models duplicate pages
  setting completion while slots retain initial zeros. Six native reason
  strings describe collection gates;14 charging-state and2 contactor labels
  belong to separate APIs, not cell fault/balancing codes.
  `.venv-firmware/bin/python research/vehicle-diagnostics/battery-validity-checker.py`
  checks16 sources,15 native anchors,4 text anchors,65536 count inputs,
  24576 policy combinations and40 retained metadata frames. No vehicle changes.
- `oem-warning-mappings-20261021.json` traces8 priority warning IDs to original
  Chinese messages and colors in the628-entry car-matched popup dictionary,
  with separately labelled Russian translations. MCU CAN261/channel0 low10bits
  feed1007/26100008, then the native/Godot popup chain. Raw0 and hidden-popup0
  are not ECU-health results. Active cluster loading, fault ownership and
  warning-to-DTC mappings remain unproved.
  `.venv-firmware/bin/python research/vehicle-diagnostics/check_oem_warning_mappings.py`
  checks43 sources,9 original token buffers,27 native anchors,25 line anchors,
  8 mappings,19 retained frames and2 historical GETs without vehicle I/O.

- `warning-dtc-linkage-20261021.json` follows the warning cache/consumer paths
  and separates native DataItem IDs from popup warning IDs. In particular,
  DataItem290 `checkEngineOverheat` is not popup122 or an ECU DTC. No direct
  warning-to-DTC/sending-ECU relation was recovered. Run
  `.venv-firmware/bin/python research/vehicle-diagnostics/check_warning_dtc_linkage.py`.
- `gb-alarm-dtc-boundary-20261021.json` recovers14 native GB-field-to-alarm-bit
  predicates for profile170/HEV. The inspected type07 serializer always writes
  four zero code counts even with a nonzero alarm mask; this is no substitute
  for reading ECU memory. `check_gb_alarm_dtc_boundary.py`, run with the firmware
  venv, replays14,336 synthetic callback cases and4,096 packet cases using a
  bounded integer-instruction interpreter; it never executes firmware.
- `oem-dtc-exact-source-search-20261021.json` records the finite exact-software
  source search and unproved acquisition targets: BMS cell-overtemperature
  headings, drive-to-VCU communication headings, and rejected engine sensor-
  circuit/SOC shortcuts. No exact applicable OEM dictionary was recovered.
  Run `python3 research/vehicle-diagnostics/check_oem_dtc_exact_source_search.py`.
- `remaining-role-fingerprints-20261021.json` tests nine child-code fingerprints
  against13 existing raw firmware files, with two own-MCU positive controls.
  Twenty-three short DID coincidences are images/compressed API/name-pointer
  data, not role assignments. No new role or BSCM7E5/ESP782 physical join.
  Run `.venv-firmware/bin/python research/vehicle-diagnostics/check_remaining_role_fingerprints.py`.

- `remaining-ecus-20261020.json` continues those runs: 70 distinct F194 targets,
  33 positive responses and 32 exact stock-cache matches. ABS/ESP `782`, EPS
  `783`, SRS `7F1`, ADC `712` and front radar `7F2` each returned explicit zero
  count and list for DTC mask `09` (testFailed OR confirmedDTC).
  OBD `09 0A` identifies `7E5` as `BSCM-BrakeSystem`; its physical relationship
  to `782` is unknown. The later factory-map pass below resolves SMCU/SMCU3/DCOBC. The fixed
  shell reader is [`RemainingEcuReadProbe.java`](../../tools/vehicle-diagnostics/RemainingEcuReadProbe.java);
  its seven executed plans, source/dex hashes and cleanup evidence are in
  `captures/diagnostics-remaining-20261020/`. No product allowlist was changed.
  ADS reports DTC format `01`; the other four newly read modules report `00`.

- `factory-ecu-map-20261020.json` retains all 225 sanitized factory `getEcuList`
  records. They are server variants/candidates; transport, channel and DoIP
  logical addresses remain distinct.
- `factory-powertrain-dtc-20261020.json` records subsequent live identities
  and DTC reads at SMCU `744/74C`, SMCU3 `745/74D` and DCOBC `7E4/7EC`.
  All three returned empty lists for mask09. The drives also returned explicit
  count0/format00. DCOBC's count timed out; its separately bounded list succeeded
  but cannot establish the DTC format. Executed sources, raw traces, independent
  replay and cleanup are in `captures/diagnostics-next-20261020/`.
  No OEM dictionary was obtained and no product allowlist was changed.
- `factory-candidates-20261020.json` records one `22 F194` at 35 of the 37 CAN
  routes of that map outside the scanner catalog (`715`/`785` had result 3
  already). 22 answered: 21 with their exact cache code, and `5E7` with the
  passenger computer's MCU code from its own OTA container. The 13 cache
  placeholders got result 3. All 22 joined `EcuCatalog`, and the 13 `5xx`
  responders are listed one by one in `DiagnosticWhitelist`. Plan, builds, traces,
  pre/post checks and the decoder are in `captures/diagnostics-candidates-20261020/`.

- `oem-dtc-acquisition-20261020.json` pins the seven target ECU/software
  identities and the newly located public Z9GT PHEV manual index. It records
  engine/BMS/drive heading counts, conflicting codes and exact introduction/list
  document IDs where available. This is an acquisition index, not a fault
  dictionary: original manual pages, software applicability and FMCU/ISG
  format01 display-to-raw mapping are still missing. SMCU/SMCU3 rear-side
  assignment was open in this snapshot; the 2026-10-21 Xtool model catalog below
  now names SMCU left and SMCU3 right. Public catalog captures and the additional
  offline stock-app audit are in `captures/diagnostics-oem-dtc-20261020/`.
  `oem-dtc-public-index-20261020.json` preserves all 829 observed
  subsystem/code/outline locators and original Chinese public headings in Git,
  with source projection hashes. All identities match the initial mixed-language
  capture; original text was recovered through Chrome's native language UI.
  These are unqualified publisher titles, not verified OEM definitions or
  inferred raw codes. Protected manual pages and repair procedures are absent.
  `oem-dtc-public-index-ru-20261020.json` is a machine translation of those
  829 headings into Russian, keyed by subsystem/code/outline and pinned to the
  index by `sourceSha256`; its header says it is translated headings, not OEM
  text. The app ships both files unchanged as candidate descriptions
  (docs/vehicle-diagnostics-architecture.md, §4); a new index needs a new
  translation, or `OemDtcReferenceTest` fails.

- `dtc-format-applicability-20261021.json` pins the stock native count/list
  response tasks: they preserve the format byte and raw24-bit code/status without
  supplying the missing format01 display conversion. Six target revisions remain
  unbound to the829 public headings. The public BYD TIS landing is a concrete
  login-gated acquisition lead; no protected dictionary or matching revision was
  retrieved. Exact native anchors, public-source hashes and a bounded offline
  reproducer remain under its `captureBase`.
- `oem-dtc-public-source-followup-20261021.json` adds the official BYD
  environmental-document channel and one publicly served overseas TIS workshop
  page; neither supplies a software-applicable Z9GT DTC table. Exact CALID
  searches found no public binding. Cross-model catalog overlap (63/64 rear-drive
  codes also in U8) rules out treating those ranges as a unique model fingerprint.
  `dtc_check_public_source_followup.py` checks11 sources and the retained
  comparisons. No new OEM definitions or format01 mapping were qualified.
- `ota-strategy-mismatch-20261021.json` and
  `check_ota_strategy_mismatch.py` preserve the separate OTA investigation:
  exact104/111 replies, request-version provenance and225 cached ECU tuples,
  repair-history substitution and cached-plan/lockSync limits. Neither a new
  update offer nor a cause-specific ECU was established. The owner document is
  [OTA findings](../../docs/ota-update-findings.md#стратегия-ota-и-кэш-productlineota-2026-10-21).
- `ota-strategy-followup-20261021.json` traces status111 to the stock strategy
  cache cleanup, resolves the normal Media software-code mapping and isolates
  the only all-FF config tuple (F_ZCU) as a comparison candidate, not a proven
  mismatch cause. It also corrects the old needCheck timestamp ordering in
  `ota-strategy-mismatch-20261021.json`; cache absence after the failed check
  was not established. The follow-up also records the owner's diagnostic-computer
  update history, the client's acceptance of all-FF config, and the automatic
  download/forced-appointment boundary of a stock check.
  `check_ota_strategy_followup.py` checks53 pinned references. This was offline
  research with no OTA request or update action.

- `xtool-z9gt-dm-20261021.json` preserves 172 exact-model Xtool system/variant
  rows (`PS_TENGSHIEV` 6.61, Z9GT/Z9GT DM), source pointers/hashes and an exact-token
  crosswalk to the factory map and previous live inventory. Rows include supplier
  alternatives and LIN children; they are not 172 installed ECUs. This is a
  diagnostic-tool manufacturer's catalog, not an OEM DTC dictionary.
- `remaining-role-followup-20261021.json` audits the nine unresolved INS/FRL/
  CDC-ZCU child namespaces. Full codes are unique across225 cache rows; nine
  raw catalog rows and named stock function/version readers supply no exact
  role join. It explains why repeated identity/hardware reads cannot close
  that gap and proposes no guessed child-name DID.
  `check_remaining_roles_followup.py` reproduces the source checks offline.
- `ecu-role-evidence-20261021.json` joins the model names to factory identities
  and observed F194 for all 25 previously unnamed catalog entries and the two
  rear drives. It retains Russian labels, source row IDs and limits. Product
  labels and DTC-description selection have not been changed by this pass.
- `ecu-child-identification-candidates-20261021.json` retains the original eight
  software-code reads for seat-massage controllers and rear lamps through known
  parents `750`, `7B5`, `7D2`, `726`. Separate INS/FRL/CDC subidentities remain
  unbound. The later `child-identities-live-20261021.json` records all eight
  positive cache matches through four parent routes. The candidate manifest
  stays immutable as the dedicated `ChildIdentityReadProbe` build input; the
  older F194-only reader and product whitelist were not broadened by that probe.
- `stock-signal-read-candidates-20261021.json` preserves 30 property/native-FID
  pairs for rear steering, suspension, door obstacles and dual NFC: source hashes,
  four concrete stock getters, 25 older-TSV matches and five additional actual-
  framework resolutions. Schema v2 corrects rear-steering dysfunction: property
  `0x0FE00048` resolves to native CAN-FD `0x0FE00448`. All 30 now have separate
  type/access/device declarations from the decoded stock profiles. Runtime
  availability, native read type, units and many enums remain unproved. No signal
  is assigned to a physical diagnostic ECU merely because its function is related.
- `xtool-public-capabilities-20261021.json` preserves the public field schema,
  support counts and 67 IDC action **titles** for offline corpus searches. Its
  DTC/live/freeze fields contain flags, not fault definitions, parameter names,
  request bytes or scaling. No action was executed. Raw responses and independent
  reviews are in `captures/diagnostics-ecu-roles-20261021/`; findings and the next
  exact identification targets are in
  [the reverse crosswalk](../../docs/vehicle-data-findings.md#ecu-roles-and-reverse-catalog-crosswalk-2026-10-21).

The deeper offline pass is described in
[inventory and telemetry beyond identification](../../docs/vehicle-data-findings.md#deep-inventory-and-telemetry-beyond-identification-2026-10-21):

- `ecu-inventory-coverage-20261021.json` accounts for all 225 factory records,
  81 CAN + 5 DoIP routes and 184 route/software-DID namespaces. All 81 CAN routes
  already have a historical outcome. `ecu-inventory-gaps-20261021.json` retains
  unresolved child names/variants, not invented addresses. The stock parser and
  failure/cache semantics are in `ecu-inventory-firmware-evidence-20261021.json`.
- `ecu-live-data-support-20261021.json` conservatively joins each of 68 observed
  addresses to the exact-model scanner catalog: 56 advertise live data, 10 do not,
  2 remain unmatched. These flags are neither measurement definitions nor proof
  of an ECU's limitations.
- `decode_dicar_config.py` decodes the three local stock protobuf profiles without
  network/device access. `dicar-readable-properties-20261021.json` retains 9,799
  readable declarations with compact, named columns, exact mapper symbols and
  separately resolved native IDs. It contains settings/status/compatibility
  surfaces as well as measurements; it is not an execution allowlist. Source
  hashes, duplicate-key semantics and unresolved mappings are explicit. A separate
  Google protobuf decoder independently verified every row.
- `powertrain-telemetry-20261021.json` retains 294 names: 33 existing product
  readers, 123 additional names with replies in the old two-pass capture, 4 no-data
  and 134 unread candidates. Placeholders and zero-only replies are marked.
  Main discharge contactor and charger states now have source-backed enums;
  battery limits and engine indicated torque still need unit/dynamic qualification.
  Mutable source docs/product files are pinned to snapshots under the ignored
  capture directory. Full cell arrays were still missing at this offline stage;
  the later live GB pass below recovered them. Dedicated ISG telemetry remains
  unresolved.
- `body-chassis-telemetry-20261021.json` retains 50 selected stock read paths:
  wheel-speed packet, parking radar, brake/sensor fields, air quality and fridge.
  It separates property/native/type, actual measurement versus regulation target,
  candidate buffer layout and unproved units/physical ECU binding.
- `uds-measurement-evidence-20261021.json`, `hu-uds-server-tables-20261021.json`
  and `hu-uds-server-dids-20261021.tsv` retain the head unit's **own UDS server**
  tables (218 unique read DIDs, 220 record lengths) and the EOL/SDK investigation.
  They supply **zero qualified remote ECM/BMS/drive/ABS sensor DIDs**. Raw
  snapshot/extended DTC records still need per-ECU schemas; no manufacturer DTC
  dictionary was recovered. Internal relative evidence names are resolved from
  the JSON's `captureBase`, not treated as adjacent product assets.

Rebuild the readable registry from the retained owner corpus:

```bash
python3 research/vehicle-diagnostics/decode_dicar_config.py \
  --out captures/diagnostics-deep-20261021/root/dicar-readable-properties-replay.json
python3 -m unittest discover -s research/vehicle-diagnostics -p test_decode_dicar_config.py
```

Raw analyses, bounded searches, source snapshots and independent validation are
under `captures/diagnostics-deep-20261021/{inventory,powertrain,uds,root}/`.
This pass does not install, poll or control anything on the car. The registry's
declared Java type must never be converted directly into a Binder transaction.

The [signal-origin continuation](../../docs/vehicle-data-findings.md#signal-origins-and-passive-qualification-2026-10-21)
retains the next layer of offline evidence:

- `powertrain-signal-origins-20261021.json`: 152 focused records, 73 native
  registration matches and 58 byte-verified MCU descriptors. Wire extraction,
  native conversion and already-converted API output are separate. Seven silent
  engine GET candidates still have MCU parsers; installed selection is unproved.
- `packet-origin-20261021.json`: raw GB fanout/envelope and the MCU wheel packet
  producer, FL/FR/RL/RR order, unsigned12 values, raw flags and GL arithmetic.
  `decode_wheel_packet.py` rejects malformed packets and keeps units/freshness
  unknown. Its tests are synthetic; the retained owner CAN122 frames are not a
  captured wheel packet. The owner CAN324/channel1 cannot use channel0 layouts.
- `ecu-route-topology-20261021.json`: model network labels, ambiguous children and
  DoIP prerequisites. The saved map omitted `ecuIp`; `ecuChannel` is not proved
  physical wiring. `decode_child_identity.py` validates only complete single-frame
  replies for the existing child manifest, without issuing any request.
- `factory-scenario-origin-20261021.json`: selection/cache applicability limits
  and the negative local scenario search. Result preferences do not retain the
  script tuple; no new software-qualified DTC dictionary was found.
- `next-car-validation-20261021.json`: the immutable pre-execution plan for three
  sequential20-second existing-stream windows with identity, health and
  capture-completeness checks. The later live pass below executed these windows;
  the plan's original status/hash is retained as provenance. Exact source channels,
  lengths and field offsets are retained. No collection-table changes or ECU
  commands are included.

The JSON `captureBase` locates relative evidence files under
`captures/diagnostics-origin-20261021/{powertrain,packets,scenarios,topology}/`.
Source disassembly, hashes and reproducer scripts remain there; binaries are not
copied into research. Run all offline parser checks with:

```bash
python3 -m unittest discover -s research/vehicle-diagnostics
```

The [live passive pass](../../docs/vehicle-data-findings.md#live-passive-streams-and-paged-battery-telemetry-2026-10-21)
then qualified the available streams and a new battery-data path:

- `passive-telemetry-live-20261021.json` retains four completed20-second windows:
  GB911, BigData2909, YUN3129, then GB913 with18 established scalar GETs. The
  first three contain186 distinct ID/sub/channel/length tuples, not186 ECUs.
  Sequence/counter checks, identities, health evidence and their limitations are
  explicit. No ECU diagnostic commands, vehicle setters or product install were
  used. `/sdcard/SpotIns` was absent in a metadata-only check.
- `battery-page-telemetry-20261021.json` retains native-decoder evidence and
  complete reconstructed166-cell/14-probe arrays from exact GB channel2 tuples.
  The follow-up probe min/max/mean54/56/55 agree with the established scalar
  readers, corresponding to14/16/15°C. Cell codes remain raw: the166-cell sweep
  takes about5.5 seconds, and one rolling maximum3308 differs from its nearby
  instantaneous3307mV GET. Physical sensor positions and complete validity rules
  remain unproved. No applicable OEM DTC dictionary was recovered.
- `decode_battery_pages.py` is an offline-only parser of completed saved GB logs.
  It validates capture completion and page sequence, keeps per-page observation
  timestamps, and excludes43D's specialFD page from the cell array. The observed
  43C integrity rule is never applied to43D. Its structural acceptance flag is not
  a measurement-validity or battery-health verdict. `test_decode_battery_pages.py`
  exercises damaged/incomplete captures and page assembly without a car.

Reconstruct the retained arrays without any vehicle connection:

```bash
python3 research/vehicle-diagnostics/decode_battery_pages.py \
  captures/diagnostics-live-20261021/073428-passive/gb.log \
  captures/diagnostics-live-20261021/073428-passive/gb-correlation/gb.log \
  --output captures/diagnostics-live-20261021/073428-passive/battery-replay.json
python3 -m unittest discover -s research/vehicle-diagnostics -p test_decode_battery_pages.py
```

Raw recordings, bounded disassembly and independent reviews stay in the ignored
`captures/diagnostics-live-20261021/073428-passive/` directory. The plan and older
offline JSON snapshots remain historical evidence; the live artifacts supersede
their earlier lack of complete battery arrays.

The [consumer-semantics follow-up](../../docs/vehicle-data-findings.md#consumer-semantics-and-remaining-tests-2026-10-21)
keeps the next source layer separate from live acceptance:

- `engine-drive-consumers-20261021.json`: saved configuration7 selects18 stock
  fields for three drives; engine speed is sampled only at state3. Raw/HAL/GB
  transforms and ignored getter statuses are explicit. No new coolant or
  ISG-specific live source is claimed.
- `battery-stock-semantics-20261021.json`: untouched arrays, literal0.001 cell
  summation, FD extension83, sticky element-count completion and packaging-time
  limits. Native readiness is not page coverage or freshness; physical module
  layout and complete invalid-code vocabulary remain open.
- `chassis-source-followup-20261021.json`: panorama commandAA000048 writes the
  MCU wheel-event gate; custom CAN11F/channel1 emits six-byte99000198 only on
  angle change. Exact saved source frames are replayed, without claiming receipt
  of either ready-made Android buffer. `decode_wheel_source.py` and its six tests
  reconstruct wheel candidates offline; units/freshness remain unknown.
- `dtc-source-followup-20261021.json`: MyCar GPAK directory/text coverage and
  exact native snapshot/extended tasks. No applicable OEM DTC definition or
  record schema was found. The profile diff has no qualified new named sensors.
- `next-car-source-validation-20261021.json`: historical finite follow-up
  candidates, with scalar-ready versus buffer/child-runner preparation separated.
  The old completed `next-car-validation-20261021.json` remains immutable.
  Child identities and typed-buffer observations were subsequently executed in
  the unattended pass below; the engine-running comparison remains open.

Every promoted JSON points to its capture evidence, source hashes and reproducers
under `captures/diagnostics-next-depth-20261021/`. This pass makes no vehicle,
network, factory-app, credential or product request. All154 offline parser tests
pass; source reviews do not imply on-car acceptance.

The [unattended qualification](../../docs/vehicle-data-findings.md#unattended-child-and-buffer-qualification-2026-10-21)
adds isolated live observations and their reproducible limits:

- `child-identities-live-20261021.json`: eight parent-reported seat-massage and
  rear-lamp codes match the factory cache. Four existing routes, zero new CAN
  addresses, no child DTC reads. Exact bytes, timestamps and before/after health
  are retained; the original candidate manifest is unchanged.
- `child-role-gaps-20261021.json`: nine INS/FRL/CDC-ZCU names remain unbound after
  a finite existing-corpus search. Exact factory-family/DID links are distinct
  from physical subrole hypotheses. Package metadata's `ecuDescription` is the
  precise source gap; the retained ECU list and empty needCheck do not supply it.
- `ecu-description-cache-lead-20261021.json`: resolves ProductLineOTA's different
  strategy path and records a metadata-only shell denial at its ancestor.
  File existence/content remain unknown; no factory/BydLog/OTA action followed.
- `chassis-buffers-live-20261021.json`: wheel GET returned `MCU_OFFLINE` despite
  20 raw source frames; steering GET returned `0108043d1ea5`, matching the angle
  word in 20 raw frames. Both listeners were silent at rest. No wheel scale,
  steering physical unit or acquisition age is added. The separate clock trace
  below supersedes the initial absence of a trailing-word domain binding.
- `steering-clock-20261021.json`: steering change stamps and raw receive stamps
  use the same MCU counter. Reset-entry clear, modulo2³² increment and explicit
  timer stop/restart are traced; approximately1,000 ticks/second is observed in
  saved data. Hardware time unit, continuous uptime and sensor age stay open.
- `battery-fd-extrema-20261021.json`: FD words at bytes 3/5 match the immediately
  preceding complete 166-cell sweep's raw maximum/minimum in 7/7 pairs across
  three logs. Three leading FD records are excluded. A new parser-independent
  review agrees; this finite correlation does not supply an OEM field name.
- `insulation-consumer-20261021.json`: native selection, GET and serialization
  prove `min(raw & FFFF,60000)`, unchecked getter status and loss of FFFE/FFFF.
  Units and sensor validity stay unresolved; this is not a recommended clamp.

Reproduce from the retained corpus, without connecting to the vehicle:

```sh
python3 research/vehicle-diagnostics/check_battery_fd_extrema.py research/vehicle-diagnostics/battery-fd-extrema-20261021.json
python3 research/vehicle-diagnostics/check_insulation_consumer.py
```

The second checker uses only the Python standard library and verifies 28 exact
instruction anchors plus 65,536 low16 cases against the pinned owner binary;
`--binary PATH` accepts the same bytes at another local path. Raw captures and
independent reviews stay in `captures/diagnostics-unattended-20261021/`.

The promoted two-target allowlisted reader was `VehicleDtcProxyMain` in Denza Apps,
with `VehicleDiagnostics` supplying profile/P/speed checks, ownership and UI lifecycle.
The exact packed helper was separately verified live on 2026-10-18; the native
window was tested on the emulator only. Both were removed on 2026-10-20: the
scanner's resident helper replaces them (docs/vehicle-diagnostics-architecture.md).
Raw traces, artifact hashes and native screenshots are kept under the ignored
`captures/diagnostics-menu-20261018/` and `captures/diagnostics-fill-20261018/`
directories. The two-target helper SHA-256 is
`33ce6a7242ad8f62b677af3f91f5e8491076e2cdaca63e13daabdb55038ecf0c`.
Pass optional `lbms` as the decoder CLI third argument for its two trace files.
Multi-frame replies, other ECUs, freeze frames and manufacturer code
descriptions remain unqualified for the packaged two-target reader. The later
research probes above establish additional routes without promoting them.

The next investigation is retained in `engine-evidence-20261018.json`,
`powertrain-evidence-20261018.json` and `chassis-research-20261018.json`.
They distinguish exact firmware evidence, external leads and missing
vehicle-specific address/dictionary proof. `lamps-20261018.json` retains the
15-getter warning capture: six new fields returned invalid0, never off.
`dtc-reference-20261018.json` records primary-source format00 conversion,
four failure subtypes and three general base-code descriptions, explicitly
not a BYD dictionary. Native details and subsystem warning panels are
implemented; all eight raw/status records retain their original identities.
Raw evidence and emulator checks are under `captures/diagnostics-ecus-20261018/`.

`import_oem_eol.py` is an offline research importer for an already provided
SpotInspection EOL 3.0.0 plaintext script and its `DownloadResponseVO` metadata.
It extracts ECU-scoped CAN routing, raw DTC descriptions and status-mask labels,
retains source SHA-256 and script/station identity, and rejects conflicting or
ambiguous data. It never fetches, decrypts or executes a script; tokens, VINs,
security attachments and command steps are omitted from its output.

```bash
python3 research/vehicle-diagnostics/import_oem_eol.py \
  /path/to/code-version-script.txt /path/to/code-version.json \
  --output /path/to/new-dictionary-evidence.json
python3 -m unittest discover -s research/vehicle-diagnostics
```

If metadata lists several stations, supply `--station` explicitly. Missing
optional fields remain unknown; source property names outside the understood
schema are listed without their values. `frameSize.size/spanMs` retain the source
bean's raw signed int32 values; missing values remain unknown. The inspected bean
has no transport/DLC validation, so `frameSizeSemantics` stays unknown and neither
CAN type nor responder IDs are inferred. Metadata's MD5 describes encrypted data,
so it cannot authenticate plaintext by itself. Every result remains research
evidence with `profile: null` and `eligibleForProductAllowlist: false`; it needs
vehicle/ECU software applicability review before any product use. Synthetic tests
are in `test_import_oem_eol.py`. The source/authorization/cache/export investigation
and source hash manifest are retained under
`captures/diagnostics-discovery-20261018/eol-dictionary*`.

`OtaCachedVersionsProbe.java` is a separate, fixed shell research reader of three
cached `BYDAutoOtaDevice` buffers. It has no query/set/listener calls, accepts no
target arguments, checks UID/profile/manager and exits within eight seconds.
The live result on 2026-10-18 was empty / empty / ASCII `MCU_OFFLINE`; no ECU
inventory was recovered and the marker has no qualified health interpretation.
Source/dex identities and before/after PID/manager evidence are in
`captures/diagnostics-discovery-20261018/ota-cache-probe/`. It is not part of the
product and must not be turned into polling or an OTA query workflow.

`read_ecu_cache_export.py` and `read_ota_inventory_export.py` are fixed-target
research clients for two separately authorized stock BydLogTool export sessions.
They never start the service themselves. The server opens wildcard listeners
with write capabilities, so the proven session requires a device-side STOP
watchdog, a bounded host runner and verification that all ports close. It is
not a product data source or standing authorization to reopen those ports.

The first session recovered a version-only cache with 225 candidate ECU IDs;
`ecu-inventory-cache-20261018.json` retains a sanitized focused projection.
Candidates, variants, defaults and negative markers are not an installed-ECU
count. The second client reads four fixed sources and saves only explicitly
allowed ECU/script fields; raw preference XML is never persisted. On this car,
the full-map key was absent from both OTA preference files, `upgradeInfo.json`
was missing and EOL's `RUNNING_TASK` key was absent. Both sessions completed and
closed all temporary listeners. Their one-session permissions are consumed;
reports and cleanup evidence are in
`captures/diagnostics-discovery-20261018/approved-export-{1,2}/`.

`OtaFaultCacheProbe.java` separately read the fixed `getFaultCodeInfo()` cached
buffer once (dev1032/FID831291), returning zero bytes. ECU scope, record layout
and freshness remain unknown; this is not proof that the vehicle has no faults.
Later native analysis found that this legacy GET is not registered and the
service suppresses the HAL error. A normal SDK exit is therefore not proof of a
successful cache lookup. The paired legacy SET is also unregistered and returns
−10011 before transport; it was never called on the car.
Its source/dex identities, unchanged PID/manager/crash checks and staged-dex
cleanup are under `captures/diagnostics-discovery-20261018/ota-fault-cache-probe/`.

`trace_fault_native.py` reproduces that finding offline against the exact-hash
owner HAL. A bounded Python instruction interpreter walks all three registration
tables (2,775 / 2,287 / 11,604 entries), checks five independent control entries,
and evaluates the complete legacy setter and generic string codec. It imports
the repository's `research/cluster-native/inspect_native.py` ELF reader and needs
the existing firmware environment's pyelftools/Capstone. It does not execute
firmware natively or access a device/network. Changed binaries, unsupported
instructions/calls and mismatched controls abort.

```bash
.venv-firmware/bin/python research/vehicle-diagnostics/trace_fault_native.py \
  --binary captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/hw/auto.default.so \
  --output captures/diagnostics-discovery-20261018/fault-protocol-followup
```

The report is `fault-protocol-followup/README.md` under that discovery directory.
`ecu-http-followup/contract.md` records why an isolated `getEcuList` metadata
request still lacks safe identity/token acquisition: native getters can initialize
or recover the security chip, and the token has no external getter. Those getters,
credentials and the backend were not accessed in this follow-up.

MCU analysis in `ecu-map-followup/` traces addresses arriving in request payloads.
The generic EOL path treats functional `7DF` as send-without-wait and its receiver
requires an active request plus an exact request-ID+8 match. It cannot be used as
an OBD collect-all discovery path. The MCU's own `741/749` configuration and
service/DID tables must not be assigned to the engine, traction BMS or ABS.
See the [MCU corpus notes](../../docs/firmware-corpus.md#mcu-application).
No target addresses or factory DTC descriptions were qualified by this offline
pass; the product still reads only BODY and low-voltage LBMS.

`trace_mcu_diagnostics.py` reproduces the MCU evidence with Python >=3.12 and
pypcode4.0.1 using `V850:LE:32:v850e3v5`. It checks the exact image hash, three
FID dispatch rows, three output descriptors and complete decoding of 21 bounded
ranges (1,262 instructions). It neither installs dependencies nor executes MCU
code. The temporary decoder environment used for this pass was:

```bash
PYTHONPATH=/private/tmp/denza-pypcode-20261018 /opt/homebrew/bin/python3.13 \
  research/vehicle-diagnostics/trace_mcu_diagnostics.py
```

That temporary path may expire; the existing `.venv-firmware` with pypcode2.0.0
does not satisfy this decoder requirement. Results and source hashes are
`ecu-map-followup/mcu-route-input-trace.{md,json,asm}` and
`mcu-route-input-sources.json`. The original MCU code was read from the owner's
OTA, not from the installed MCU; this is static applicability evidence only.

`extract_ota_ecu_prefix.py` reads a saved threadtime GCY HTTP log offline and
retains only complete ECU objects from the exact successful `getEcuList`
response prefix. It never repairs truncated JSON or combines shared addresses,
and separates CAN pairs from Ethernet logical addresses. The eight complete
objects in `ota-ecu-prefix-20261018.json` came from an already captured, separately
authorized stock check; none supplies an ECM, traction-BMS, drive or ABS route.
Candidate `720/728` occurs with several CDC/ZCU names and different DID namespaces.
All outputs remain ineligible for automatic product promotion.

```bash
python3 research/vehicle-diagnostics/extract_ota_ecu_prefix.py \
  captures/ota-status-20261018/phase1-live-check-sanitized.txt \
  --output /private/tmp/new-ota-prefix.json
```

Additional bounded investigations under
`captures/diagnostics-discovery-20261018/` are:

- `stock-cache-followup/report.md`: getEcuList100 followed by checkVersion111
  does not save the full route map. Failure transcripts are partial and direct
  ADB metadata reads returned Permission denied; another export is not justified
  as a source for successfully read targets.
- `didiag-map-next/README.md`: exact Binder/vtable proof that DiDiag's diagnostic
  method is a no-op on this build. Modern UDS has target-configured reads, but
  no inventory getter in the inspected interfaces.
- `cloud-map-next/`: recovered MCU command-status table and the separate
  cloudctrlserv incoming diagnostic-job parser. Neither contains a named ECU map.
- `eol-map-next/report.md`: the built-in low-power script is not a diagnostic
  catalog; the matching OEM dictionary still needs upstream script selection.

The old passive-observation plan has been corrected: source-level `isDebug=true`
does not defeat the car's global `log.tag=M`. Short individual route logs need
their own bounded per-tag enable/restore window; a whole HTTP response is
truncated. No further stock check, logger session or ECU request was started
by this follow-up.

`parse_ota_route_logs.py` parses saved short stock records; it retains explicit
index/item/reset/route boundaries and refuses automatic ECU association across
worker threads, malformed records or capture gaps. `filter_ota_route_stream.py`
is its offline bounded input filter, with a shared content-free gap marker and
fresh private output files. Neither tool invokes ADB, changes logging, starts
OTA or sends a vehicle request. Initial item-version fields remain metadata,
and route observations retain `responseStatus=not_observed`.

```bash
python3 research/vehicle-diagnostics/filter_ota_route_stream.py \
  --input /path/to/saved-short-tag.log --output-dir /path/to/new-private-directory
python3 research/vehicle-diagnostics/parse_ota_route_logs.py \
  /path/to/new-private-directory/events.log --output /path/to/new-route-evidence.json
```

Real saved progress replay contains 43 candidate-index contexts and no identity
or route anchors. The eight-object HTTP prefix is the only recovered routing
evidence from that capture. All102 host research tests pass. The bounded helper
and host runner now live in `tools/vehicle-diagnostics/OtaRouteObserver.java`
and `tools/observe_ota_routes.py`; see `tools/vehicle-diagnostics/README.md`.
Normal deadline, explicit stop, filtering and loss of the host/owned ADB client
passed on the existing emulator. No observer has run on the vehicle.

The subsequent stock workflow audit rules out using Check/Update as a
diagnosis-only trigger: it can download automatically and schedule a forced
installation without a new affirmative click. The logger is not an OTA
interlock. Evidence and source hashes are in
`ota-route-observer-plan/stock-check-scope.md` under the discovery capture root.
