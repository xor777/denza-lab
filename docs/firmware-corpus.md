# Firmware corpus

The owner's two OTA images, everything already extracted from them and decompiled on this Mac,
how to read an archive, and where new work goes. The archives and the extracted files are local
and git-ignored (`captures/`, `reverse/`); this page and the tools are tracked. The method that
uses the corpus — corpus first, then a live hypothesis test — is `docs/governance.md`, "Firmware
Behavior Method".

## Current state

Updated 2026-10-08. What a session needs before it opens either archive.

| Claim | Status | Since | Section |
| --- | --- | --- | --- |
| The IVI OTA `Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip` is the image of the owner's car, which reports `34.1.33.2605218.1` | live | 2026-09-23 | [Archives](#archives) |
| The FSE OTA `Di5.1_FSE_42.1.8.2605219.1.42.2.3.2605250.2.zip` is the passenger computer's image; installed DiShare matches it by hash | live | 2026-09-24 | [Archives](#archives) |
| Both archives sit in `~/Dev/denza/firmware/`, not `~/Downloads` (moved 2026-10-07) | live | 2026-10-07 | [Archives](#archives) |
| `python3 tools/firmware_corpus.py find <name>` lists every copy, decompile and partition-listing line of a file; the index has 345 entries: 205 copied files, 68 jadx trees, 64 loose APKs/JARs, 8 listings | code | 2026-10-08 | [Looking before extracting](#looking-before-extracting) |
| `reverse/dishare-jadx` is DiShare `1.5.1.1.23102ef` pulled from the car before the OTA; the OTA's own DiShare is a different APK (SHA-256 `ffc548…` IVI, `ef1d35…` FSE) | firmware | 2026-10-08 | [What is already there](#what-is-already-there) |
| Already duplicated: 21 copies of identical files (1.97 GB) and repeated decompiles — DiCarServer 3.2.0-beta.1 three times, MapHelper 1.0.6 and AutoVideo twice | code | 2026-10-08 | [What is already there](#what-is-already-there) |
| The IVI system partition is system-as-root: `/system/framework/services.jar` lands at `<out>/system/system/framework/services.jar` | code | 2026-09-23 | [Reading an archive](#reading-an-archive) |
| "The output directory must contain `Config-readable.xml`" (CLAUDE.md until 2026-10-08): the readers now decrypt it from the archive when it is missing | refuted | 2026-10-08 | [Reading an archive](#reading-an-archive) |
| Copies record their partition and archive in `extraction.json` since 2026-10-08; older copies are attributed by directory name | code | 2026-10-08 | [Reading an archive](#reading-an-archive) |
| jadx cannot evaluate the `BYDAutoFeatureIds` initializer; the resolved Feature-ID tables are `captures/ambient-light-20260923/data/fids-canfd.tsv` and `fids-can-classic.tsv` | firmware | 2026-09-23 | [Decompiling](#decompiling) |
| Trees decompiled with `--no-res` have no `AndroidManifest.xml`; three reads of one failed | code | 2026-09-23 | [Decompiling](#decompiling) |
| One Python environment, `.venv-firmware/` from `research/requirements-firmware.txt`, replaces three temporary ones under `/tmp` | code | 2026-10-08 | [Python environment](#python-environment) |

**Open questions**
- Delete the duplicate copies (1.97 GB) and the repeated decompiles? Docs cite some of their
  paths, so deleting needs a citation check first; the owner's call.
- Other IVI partitions (vendor, product) have no tracked copy script: `extract_system_files.py`
  reads only `system`. Add a partition argument when one is next needed.

## Contents

- [Archives](#archives) — the two OTAs, their builds and hashes.
- [Looking before extracting](#looking-before-extracting) — the index and the lookup.
- [Reading an archive](#reading-an-archive) — the IVI and FSE readers, the layout they write.
- [Where new work goes](#where-new-work-goes) — one directory per build, reports beside evidence.
- [Decompiling](#decompiling) — the jadx command and its traps.
- [Python environment](#python-environment) — one venv for every reader.
- [What is already there](#what-is-already-there) — the topic directories of September.

## Archives

| Build | File in `~/Dev/denza/firmware/` | Size | SHA-256 |
| --- | --- | --- | --- |
| `ivi-34.1.33.2605218.1` — the head unit, DiLink 5.1, Android 13 | `Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip` | 10.9 GB | `bfb4e57834f5b15f30b2d3d94009d0bf756e95225c7afea34145e949ab023014` |
| `fse-42.1.8.2605219.1` — the passenger computer, Rockchip, Android 12 | `Di5.1_FSE_42.1.8.2605219.1.42.2.3.2605250.2.zip` | 2.7 GB | `1283f97fdcb9f511d5443e0a9cc7c5cd318dfc6152e413b99fd5052598aae5fb` |

The IVI archive is encrypted: `Config.xml` is decrypted with a key derived from the archive's own
`metadata` member, and it carries the key of the `Android/Target/android.zip` member, whose
`payload.bin` holds the partitions (`docs/telematics/firmware-reading.md`). The FSE archive is a
plain A/B OTA: its `update.zip` member is stored and carries an ordinary `payload.bin`. The same
folder holds `BydDipilot7.32.apk`, downloaded separately and part of neither image.

## Looking before extracting

```bash
python3 tools/firmware_corpus.py find DiCarServer     # every term must match
python3 tools/firmware_corpus.py find system/framework services
python3 tools/firmware_corpus.py index                # after extracting or decompiling; ~7 s
```

`find` prints one line per hit: `file` (copied out of an OTA, from an `extraction.json`), `jadx`
(a decompiled tree, with package, version and whether resources were decoded), `binary` (a loose
APK or JAR, hashed), and `listed` (a line of a full partition walk, `captures/*/files-*.txt`), so
"is it in the image at all" is answered without opening an archive. Each hit carries its build:
`ivi-…`, `fse-…`, or `pulled-from-car` for what came off the car before the OTAs; bytes that hash
like a file copied out of an OTA take that OTA's build wherever they were found.

The index is `captures/firmware-index.tsv` (kind, build, name, package, version, resources,
image path, SHA-256, size, local path), rebuilt by `index`; hashes and tree metadata are cached in
`captures/.firmware-index-cache.json`. `rg` skips the git-ignored trees; search inside them with
`rg --no-ignore`.

## Reading an archive

IVI, system partition:

```bash
export DENZA_FIRMWARE_ARCHIVE=~/Dev/denza/firmware/Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip
export DENZA_FIRMWARE_OUTPUT=captures/firmware/ivi-34.1.33.2605218.1
python3 research/split-firmware/extract_system_files.py --list /system/framework
python3 research/split-firmware/extract_system_files.py "$DENZA_FIRMWARE_OUTPUT" /system/framework/services.jar
```

The building blocks are in `research/telematics-firmware/`: `check_config.py` (the archive's
`Config.xml`), `read_android.py` (`AndroidReader`, the decrypted `android.zip`),
`current_payload.py` (`Payload`, `Partition`) and `extract_cloud.py` (`Ext4`). A partition other
than `system` is `Partition(Payload(), 'vendor')` with the same classes. Until 2026-10-08 the
reader looked for `Config-readable.xml` in `DENZA_FIRMWARE_OUTPUT` and failed anywhere but
`captures/telematics-20260923/readable-firmware/` (six failures in four sessions); it now
decrypts `Config.xml` in memory when that file is missing.

FSE, any partition:

```bash
F=~/Dev/denza/firmware/Di5.1_FSE_42.1.8.2605219.1.42.2.3.2605250.2.zip
python3 research/fse-firmware/read_fse_ota.py "$F" partitions
python3 research/fse-firmware/read_fse_ota.py "$F" walk system captures/firmware/fse-42.1.8.2605219.1/files-system.txt
python3 research/fse-firmware/read_fse_ota.py "$F" copy system captures/firmware/fse-42.1.8.2605219.1 /system/app/BydHud/BydHud.apk
```

Both readers are read-only, check each operation's SHA-256 before decoding it, and append every
copy to `<out>/extraction.json` with size, SHA-256, partition and archive. A copy lands at
`<out>/<partition>/<path in partition>`; the system partitions are system-as-root, hence
`<out>/system/system/…`.

## Where new work goes

- **Copies:** `captures/firmware/<build>/`, one directory per build, never a topic directory or a
  session scratchpad. The September topic directories stay where they are, because docs cite
  their paths; the index finds them.
- **Decompiles:** `captures/firmware/<build>/jadx/<Name>/`, with resources.
- **Reports** an agent writes about what it read: `captures/<topic>-<date>/reports/`, beside the
  evidence. A scratchpad does not outlive its session; one turn-signal session's three reports
  and its AVC decompile were lost that way, and a memory note still pointed at them.
- **Then** run `python3 tools/firmware_corpus.py index`.

## Decompiling

```bash
jadx -d captures/firmware/ivi-34.1.33.2605218.1/jadx/DiShare \
  captures/firmware/ivi-34.1.33.2605218.1/system/system/app/DiShare/DiShare.apk
```

`jadx` 1.5.5 is `/opt/homebrew/bin/jadx`. "finished with errors, count: N" is normal for these
APKs: a few methods fail and the rest is usable.

- **Resources:** keep them (no `--no-res`). Without them there is no `AndroidManifest.xml` to read
  components, permissions or the version from.
- **Feature IDs:** jadx cannot evaluate the static initializer of `BYDAutoFeatureIds` (a
  `StackOverflowError` on the CAN-FD table), so its constants read as unresolved. The resolved
  tables are `captures/ambient-light-20260923/data/fids-canfd.tsv` and `fids-can-classic.tsv`,
  10,348 rows each, tab-separated `class or TOP`, `NAME`, signed decimal, built by
  `resolve_clinit.py` beside them. Look a Feature ID up there; about 130 were looked up by hand
  before the table existed (`docs/vehicle-data-findings.md`).
- **Old decompiles:** a tree under `reverse/` predates the OTAs; `find` shows its version before
  anything is built on it.

## Python environment

```bash
python3 -m venv .venv-firmware
.venv-firmware/bin/pip install -r research/requirements-firmware.txt
.venv-firmware/bin/python research/fse-firmware/inspect_hud_hal.py …
```

`cryptography`, `pyelftools`, `capstone`, `unicorn==2.1.4` and `pypcode==2.0.0`; installs on the
system Python 3.9. `.venv-firmware/` is git-ignored. It replaces `/tmp/denza-hud-hal-venv`,
`PYTHONPATH=/tmp/denza-hud-analysis-deps` (both gone after a reboot, then reinstalled mid-session)
and `captures/speaker-firmware-20260923/analyzer-deps/`.

## What is already there

As of 2026-10-08, by directory; `find` is the authority for any single file.

| Directory | Build | What it holds |
| --- | --- | --- |
| `captures/split-firmware-20260923/` | IVI | framework, services, SystemUI, Launcher3, recents, mycar and the BYD `dilink-*` jars, copied and decompiled for the split |
| `captures/hud-firmware-20260923/` | IVI | DiShare, AutoVideo, the cluster, map and camera apps, CrossControl, SomeIpService; listings of system, vendor and product |
| `captures/ambient-light-20260923/` | IVI | the car settings apps and DiCarServer (with resources); the resolved Feature-ID tables in `data/` |
| `captures/speaker-firmware-20260923/`, `captures/washer-firmware-20260923/` | IVI | `auto.default.so` disassembly by function, the decrypted `Config-readable.xml` |
| `captures/adb-firmware-20261006/` | IVI | init, adbd and USB scripts; BYD's developer tools decompiled |
| `captures/fse-firmware-20260924/` | FSE | BydHud (unpacked), DiShare, DiCarServer, CrossControl, SystemUI, framework, services; listings of five partitions |
| `captures/fse-hud-*-20260924/` | FSE | HAL, Cross and access evidence for the HUD; the FSE settings and developer tools decompiled |
| `captures/telematics-2026092*/` | IVI | `cloudmanager` and its readable firmware; a telephony jar decompiled from a car pull of 2026-09-22 |
| `reverse/` | pulled from the car | APKs and decompiles from before the OTAs; `speaker-lift/` holds jars byte-identical to the IVI image |
