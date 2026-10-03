# Reading the firmware: archive, updater and cloud client

Part of [Telematics findings](README.md). Moved verbatim from `docs/telematics-findings.md` on 2026-10-03; "above" and "below" in the text refer to that file's order (see [Pages](README.md#pages)).

## Contents

- [Matching archive decoded and cloud client recovered, 2026-09-23](#matching-archive-decoded-and-cloud-client-recovered-2026-09-23)
  - [Proven extraction chain](#proven-extraction-chain)
  - [Current-build network gate, now established in code](#current-build-network-gate-now-established-in-code)
- [Stock updater handoff traced, 2026-09-23 (earlier stage)](#stock-updater-handoff-traced-2026-09-23-earlier-stage)
  - [Where a readable component could come from](#where-a-readable-component-could-come-from)
  - [Verified installer path](#verified-installer-path)
- [Downloaded firmware inspection, 2026-09-23](#downloaded-firmware-inspection-2026-09-23)
- [Method notes](#method-notes)

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
[research/telematics-firmware](../../research/telematics-firmware/README.md).
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

> **Superseded 2026-09-23:** a helper status upload and the live stock-client activation were both demonstrated later that day — see [cloud-protocol.md, Real SOC reached the official phone app over Wi-Fi, 2026-09-23](cloud-protocol.md#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23) and [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23).

## Stock updater handoff traced, 2026-09-23 (earlier stage)

> **Superseded 2026-09-23:** the package was decoded without the updater, and the current `cloudmanager` was recovered — see [Matching archive decoded and cloud client recovered, 2026-09-23](#matching-archive-decoded-and-cloud-client-recovered-2026-09-23).

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

> **Superseded 2026-09-23:** the opaque `Android/Target/android.zip` and `Config.xml` were decoded and `cloudmanager` extracted — see [Matching archive decoded and cloud client recovered, 2026-09-23](#matching-archive-decoded-and-cloud-client-recovered-2026-09-23).

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

## Method notes

- The app is a release build and writes nothing to `logcat`; grepping the
  buffer for `byd` returns only `byDevice` / `byDefault` from system
  components. Screen state and static endpoint extraction carried the
  diagnosis instead.
- `adb shell dumpsys netstats detail` was not usable for per-UID traffic here;
  the app's UID is 10502 if a later session wants to retry.
