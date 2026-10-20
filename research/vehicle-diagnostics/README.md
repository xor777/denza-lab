# Vehicle DTC evidence

Current findings and scope are in [vehicle-data-findings.md](../../docs/vehicle-data-findings.md#diagnostics-and-dtc-2026-10-18).

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
  assignment remains unknown. Public catalog captures and the additional
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

The promoted two-target allowlisted reader is `VehicleDtcProxyMain` in Denza Apps;
`VehicleDiagnostics` supplies profile/P/speed checks, ownership and UI lifecycle.
The exact packed helper was separately verified live on 2026-10-18. The native
window was tested on the emulator; full in-car window acceptance is outstanding.
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
