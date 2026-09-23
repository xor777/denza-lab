# Offline Denza firmware reader

Research scripts, verified on 2026-09-23 against the owner's
`Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip`. These stay outside product
builds. They read a local archive and write selected evidence to a separate
directory; no car, updater, router or cloud connection is involved.

## Reproduce the extraction

Python 3 with `cryptography` is required. Static disassembly additionally used
`pyelftools` and `capstone`; neither is needed for the commands below.

```sh
export DENZA_FIRMWARE_ARCHIVE="$HOME/Downloads/Di5.1_34.1.33.2605218.1.34.2.3.2605202.2.zip"
export DENZA_FIRMWARE_OUTPUT="/absolute/path/to/a/separate/output-directory"
python3 research/telematics-firmware/check_config.py
python3 research/telematics-firmware/read_android.py
python3 research/telematics-firmware/current_payload.py
python3 research/telematics-firmware/extract_cloud.py
```

The first script must report `valid_pkcs7: true` and `valid_xml: true`.
The second indexes the decrypted nested ZIP without writing its 10 GB content.
The last reads only the OTA operations needed for ext4 metadata and selected
files. It validates SHA-256 of each fetched operation before decoding it.
The expected `system/bin/cloudmanager` SHA-256 is
`9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9`.
Scripts are bounded research readers, not general-purpose ZIP/ext4 tools:
they require full replacement OTA operations, support only the observed ext4
extent layout, cap individual reads/files, and do not follow symlinks.
Do not use Python's `-O` flag: structural checks currently use assertions.
Whole payload/partition hashes and vendor authenticity are not verified by
this selective extraction. Input contents are never modified.

## How the opaque layer was identified

The public [DiLink 5.0 package](https://modhub24.com/firmware/firmware_3c4bf282)
`Di5.0_23.1.22.2505209.1_0.zip` has a plaintext nested `update.zip` and
`payload.bin`. The normal catalogue download link issued a temporary signed
URL supporting HTTP ranges. Direct raw-file requests had returned 403;
the ordinary download flow worked. No 7 GB copy was downloaded.

Selected boot operations supplied a gzip ramdisk containing
`system/bin/recovery`, 2,524,920 bytes, SHA-256
`5ec7ae12ea06ec17e89c874630d83cbd4598e269321df3ca9021750fc2df2bac`.
Only its code was inspected; it was never executed. Function labels below are
inferred from logs/callers in this stripped ELF, not exported symbol names.

| Recovery address | Observed behavior |
| --- | --- |
| `0x11e6cc → 0x11fa58` | Uses the adjacent `metadata` file to derive the Config input seed |
| `0x1f8990`, mode 0 → `0x1f9794` | MD5 of all factor-file bytes, producing a 16-byte seed |
| `0x1f8348` | SHA-256 of the seed; sums mirrored byte pairs into 16 bytes, then replaces byte `(sum >> 4) & 15` with `sum & 255` |
| `0x1f8640` | Uses that AES key and IV `MD5(seed)` with each byte shifted left by three; calls AES-128-CBC decryption with padding |
| `0x11d530 → 0x1f759c` | Decodes the 38-character package value in Config into the Android input seed |
| `0x1f759c` | Restores one substituted byte of a 16-byte wrapped seed from bytes 16/17; derives a wrapping key from SHA-256 of the final two ASCII characters using pairs `h[2*i] + h[31-2*i]`; AES-128-CBC with MD5 of those characters as IV, no padding |

The algorithms are implemented in `check_config.derive`, `check_config.decrypt`
and `read_android.package_seed`. No private vehicle identifiers, account
tokens or service credentials enter this archive decoding path.

Validation was concrete: Config has valid PKCS7 and XML syntax; the Android
member has valid final padding, ZIP headers and a coherent central directory;
its OTA metadata has valid ZIP CRCs; selected payload operation hashes match;
the reconstructed cloud files parse as ARM64 ELF. This establishes compatibility
for this package, not every DiLink version.

## Saved evidence and continuation

Local binaries, readable Config, hashes, source indexes, and annotated assembly
are in ignored `captures/telematics-20260923/readable-firmware/`.
They are local artifacts, not committed or uploaded. The original archive in
Downloads is needed to reproduce or extract additional files. Temporary signed
download URLs and large intermediate images are not retained.

Start with [the telematics findings](../../docs/telematics-findings.md) for the
current cloud-client gate and the distinction between static proof and vehicle
observations. Do not resume the old search for a decoder: this package is now
readable. Follow-up bootstrap evidence is retained in ignored
`captures/telematics-20260923/network-bootstrap/`, with its own README and
SHA-256 manifest. It traces initialization/writers of the cellular gate,
APN-specific routes, hardware-backed TLS and the current 211 → 200 → 220 → 507
registration/login/token sequence. The saved private-profile log contains the
gate value zero; the public-profile value is inferred, not directly logged.

At this archive-decoding stage the remaining targets were hardware-provider
access and whether the report supplies the official phone status feed.
Static analysis found that even
`SDF_OpenDevice` initialization can write a vendor cache, so do not invoke it as
a read-only probe. A working official-app status feed was subsequently demonstrated below; a
continuous on-car sidecar remains unimplemented.

The follow-up SOC trace is retained in ignored
`captures/telematics-20260923/battery-upload/`. It connects the already-known
`0x4A505038` getter through the MCU descriptor and HAL scale to stock
cloudmanager message **512**, CAN-FD body bytes **84/85** (12 bits, ×0.1 percent).
The original vehicle read is historical; the later upload below establishes
delivery of a fresh measured SOC to the official phone status feed.

To inspect an existing **unframed 104-byte binary body** offline:

```sh
python3 research/telematics-firmware/inspect_status512.py /path/to/status512-body.bin
```

This requires only the standard library. It rejects other lengths/branch markers,
masks the adjacent upper nibble, and reports values over 100 % as invalid.
It cannot decrypt a capture, validate its freshness, or send anything.

Factory identity follow-up is in ignored
`captures/telematics-20260923/identity-access/`: five crypto/Binder library hashes
match the live vehicle, and one explicitly approved certificate/signature test
verified a factory RSA-2048 signature locally through the shell Binder path.
The later `tls-wifi/` corpus establishes mutual TLS through vehicle Wi-Fi;
`registration-inputs/`, `status-passive/` and `phone-validation/` then establish
application login and official phone-feed delivery.
The certificate/signature methods can wake and reinitialize the chip, including
conditional factory PIN recovery; the exact internal branch taken was not
observed. The
[local identity probe](../../tools/telematics/local_identity_probe.py) defaults
to preview only. The owner-approved one-time test is complete and was not
retried; that approval is not an open-ended permission to run it again.
See the findings and retained result before any further live experiment.

The subsequently authorized [TLS probe](../../tools/telematics/tls_identity_probe.py)
and [native adapter](../../tools/telematics/tls_identity_client.c) completed mutual
TLS to `dilinkreg-cn.denzacloud.com:6001` using one factory signature. It sends no
application packet. Keep the `adb shell -T` duplex correction and intermediate
CA chain-building requirement from the current findings; neither certificate
verification nor hostname verification should be disabled.

Format references: [AOSP payload schema](https://android.googlesource.com/platform/system/update_engine/+/refs/heads/main/update_metadata.proto),
[boot unpacker](https://android.googlesource.com/platform/system/tools/mkbootimg/+/refs/heads/main/unpack_bootimg.py),
[ext4 superblock](https://www.kernel.org/doc/html/latest/filesystems/ext4/super.html),
[inodes](https://www.kernel.org/doc/html/latest/filesystems/ext4/inodes.html),
[extent trees](https://www.kernel.org/doc/html/latest/filesystems/ext4/ifork.html).

## Wi-Fi bootstrap and official SOC delivery verified (2026-09-23 follow-up)

The helper successfully completed factory mutual TLS, registration 211, discovery
200 and login 220. Denza returned `dilinknat0-cn.denzacloud.com:6041`; the official
phone app displayed online status. Two subsequent, explicitly approved message
512 uploads delivered **74% SOC**, independently measured by passive stock CAN
and the getter, to the official phone app. The phone also displayed 472 km,
35% fuel, and charging progress. This supersedes the older bootstrap/feed unknowns.

`registration_packet.py` is a network-free encoder/strict reply decoder.
`verify_registration_native.py` runs only bounded serializer/AES/MD5 firmware
functions against synthetic data using Unicorn 2.1.4. Seven fixtures match byte
for byte, including the 512 body-copy routine and both charging branches.
The temporary Unicorn installation is removed after verification.
Reproduction requires the existing current cloudmanager binary and separately
supplied Unicorn/pyelftools/cryptography; no additional firmware copy is needed.

`status512_body.py` assembles 25 cache entries into the native 104-byte body.
Live tools are in `tools/telematics/registration_probe.py` (preview by default),
`tls_identity_probe.py`, `tls_identity_client.c`, `CloudCanSnapshotProbe.java`,
and `status_upload_probe.py` (preview by default).
Do not replay these as a read-only probe: bootstrap registers/logs in at the
server; crypto calls can take stock chip initialization/recovery paths.
The status uploader requires a fresh capture, independent SOC agreement, verified
TLS and accepted login before one report; it has no remote vehicle-control executor.
Eleven local TLS/application scenarios passed, including no report on failed login.

Resume from `docs/telematics-findings.md` and the evidence READMEs for
`registration-inputs/`, `tls-wifi/`, `phone-validation/`, and `status-passive/`.
The charging override is resolved: device 1009 / FID 0x34400018, live value 1.
Both diagnostic uploads used the owner's explicit permission for one unmeasured
byte 102 (missing CAN 0x417), retaining the stock initial zero. The SDK names
presence-getter result 2 as DEVICE_OFFLINE_ALWAYS. That does not prove zero
is the correct production value. Continuous operation, ordinary APK access and
production handling of this auxiliary field remain open. Test sessions close
five seconds after the report; a later offline icon is expected even though
readings persist.

The subsequent stock-client reuse investigation is in
`captures/telematics-20260923/stock-client-wifi/`.
`verify_network_gate_native.py` runs only the native network-notification method
with synthetic profiles/state and explicit downstream stubs. Forty-two cases
confirm the eligibility/disconnection branches; they do not themselves establish
live stock DNS/TLS/login. The subsequently authorized held test at 11:40 UTC
did establish native DNS/TLS and stock TCP=1, registration status=2, token flag=1
over Wi-Fi. Evidence and stop instructions are in `stock-client-wifi/live-hold-1/`.
The runner defaults to preview; stock sessions also persist tokens and handle
incoming commands. Native telemetry content and long-term behavior remain open.
The owner observed current data in the official app during the stock session.
The host observer/guard subsequently exited on ADB timeouts without restoring
the profile. A 15:15 Moscow read-only check confirmed stock TCP=1 with no host
test running; the owner explicitly requested leaving this car session active.
