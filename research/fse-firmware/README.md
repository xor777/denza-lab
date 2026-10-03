# FSE firmware research

Host-only readers and bounded emulation for the owner's FSE OTA. These tools
are outside the Android product builds. They do not install packages, connect
to the vehicle, change automotive properties or send bus messages.

The engineering findings, firmware identities, live observations and remaining
unknowns belong to [HUD projection findings](../../docs/hud-projection-findings.md).
In particular, successful emulation does not prove physical HUD output or
operation in D. The separate FSE and IVI Android systems have different access
contexts.

[`bydhud-direct-drive.md`](bydhud-direct-drive.md) holds the BydHud internals
(scene automaton, SZ/HT/SN/EZ profiles, map crop, LVDS P-gate, warp, evidence
index), moved from sections 2–13 of the findings on 2026-10-03. BydHud is not
registered on the owner's car (`sys.hud.direct.config = 0`), so that page
describes direct-drive variants, not this car's HUD.

- `read_fse_ota.py`: list partitions/files and extract selected files from the
  nested Android A/B OTA without extracting the whole filesystem. Reuses the
  local telematics/split firmware readers; see its module docstring for commands.
- `read_xcd_container.py`: inspect/decrypt the outer MCU/DSP XCD container as
  documented in section 14.10.4. This does not imply decryption of the internal
  MCU application or a usable firmware modification workflow.
- `inspect_hud_hal.py`: execute selected ARM64 initialization, conversion,
  encoding and filtering code from the SHA-pinned FSE HAL. This is an offline
  verification tool, not an Android HAL replacement or a bus transmitter.
- `inspect_cross_hud_route.py`: execute the pinned FSE CrossService's feature
  registration, buffer publication and receive dispatch, or the pinned IVI
  service's outgoing buffer path. Sockets and callbacks are synthetic; this
  is not a live Cross client.

## Reproduce the HAL inspection

Python 3.9+ with `pyelftools` and `unicorn==2.1.4` is required. For example, use
an isolated host environment:

```sh
python3 -m venv /tmp/denza-hud-hal-venv
/tmp/denza-hud-hal-venv/bin/pip install pyelftools unicorn==2.1.4
/tmp/denza-hud-hal-venv/bin/python research/fse-firmware/inspect_hud_hal.py \
  captures/fse-hud-route-20260924/system/system/lib64/hw/auto.default.so \
  > /tmp/denza-hud-hal-report.json
```

The accepted HAL SHA-256 is
`29e712e309a9bf547a2c4ad9cfe38d34f56c71acf7721630c3c4ec33d3977868`.
Other inputs fail before emulation. VA constants and data layout are specific
to that file and must be re-reviewed for another firmware.

The output records 11,604 request/query registrations, selected parameter
records, four AutoID constructor variants, 16 `arhudshift` cases, two encoded
start/stop messages, the FSE message-prefix table, three routing cases, six
firmware-version cases and six incoming Cross callback cases. Eight verification
groups must pass before any JSON report is printed.

Allocation, container insertions, registration vector copies, logging and the
configuration getter are synthetic hooks. Unrecognized calls fail closed;
each emulated call has instruction and time limits. There are no emulated
system calls, hardware accesses or direct native execution of the firmware.
The prefix-set hook uses a one-bucket libc++ layout, so routing results are
also checked with a prefix outside the local set. The code executes actual
registration, conversion, codec, splitter, role initializers and Cross observer
instructions. Role cases supply a firmware version while leaving other queried
properties empty. Cross subscription success and the `writeDeviceOriginal`
boundary are hooked. The complete service, kernel driver, Cross network and
MCU are not emulated.

The reviewed successful output and annotated disassembly are retained under
the ignored `captures/fse-hud-input-20260924/`. `manifest.json` pins the report,
disassembly, extracted APKs and analysis source. Java entry points for the
official diagnostic UI are documented in section 14.12.3; their existence in
the OTA is not proof that they are currently available or authorized on-car.

## Reproduce the Cross dispatch inspection

With the same Python environment:

```sh
/tmp/denza-hud-hal-venv/bin/python research/fse-firmware/inspect_cross_hud_route.py \
  captures/fse-hud-access-20260924/system/system/lib64/libbydcrossservice.so \
  > /tmp/denza-cross-dispatch.json
```

Accepted library SHA-256:
`50073cc57f42f5e885a3d160db257342ab5b5bd8166541ee6d94b69598b03949`.
The actual feature initializer and Parameter factory produce 350 request/query
registrations. The tool checks target types/devices, then executes `setBuffer`
and one `threadLoop` receive for each of three FIDs. It checks the published
bytes, absence of a local setter callback and the receiver callback payload.

The receive connection is synthetic and ready. `pubData` is hooked as a
successful publication; `zmq_recv` copies a fixed in-memory packet. Allocation,
mutexes, registration insertion, cache update and final callback are hooks.
The real `getDevice` searches a synthetic one-bucket map. The two control FIDs
have synthetic registrations: they test dispatch masks, not catalogue validity.
Rear loopback flags are explicitly clear. No Binder permission, network delivery,
full subscriber queue, hardware input selection or in-motion behavior is proved.

The same command accepts the separately pinned IVI library at
`captures/fse-hud-access-20260924/ivi/system/lib64/libbydcrossservice.so`, SHA-256
`eeb188911f9d346a8f53d91b4cba8ed237941024c9e5fa50a7aed7e8096143a9`.
For this input it executes only `BYDCrossService::setBuffer` and
`PubSubManager::setBuffer` for start/stop. The service object, IVI role and
publication success are synthetic; JNI, Binder, permission enforcement and
network delivery are not executed. Unknown file hashes fail before emulation.

Current reviewed reports: `captures/fse-hud-access-20260924/hal-cross-roles.json`
(872,255 instructions, eight verification groups), `cross-dispatch-replay.json`
(18,657 instructions), `ivi-cross-sender-replay.json` (434 instructions),
annotated disassembly and negative hash checks. The new
`manifest.json` pins the current sources and artifacts; the prior input capture
retains its historical source hash. The candidate route and remaining gates
are documented in section 14.13 of the findings.
