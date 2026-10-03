# Official-cloud protocol, factory identity and SOC upload

Part of [Telematics findings](README.md). Moved verbatim from `docs/telematics-findings.md` on 2026-10-03; "above" and "below" in the text refer to that file's order (see [Pages](README.md#pages)).

## Contents

- [Real SOC reached the official phone app over Wi-Fi, 2026-09-23](#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23)
  - [Report completeness and the resolved charging field](#report-completeness-and-the-resolved-charging-field)
  - [Reproduction, verification and cleanup](#reproduction-verification-and-cleanup)
- [Official-cloud registration and login over Wi-Fi verified, 2026-09-23](#official-cloud-registration-and-login-over-wi-fi-verified-2026-09-23)
  - [Live evidence](#live-evidence)
  - [Real inputs and packet construction](#real-inputs-and-packet-construction)
  - [Passive stock state capture after login](#passive-stock-state-capture-after-login)
- [Factory mutual TLS over vehicle Wi-Fi verified, 2026-09-23](#factory-mutual-tls-over-vehicle-wi-fi-verified-2026-09-23)
  - [Evidence and two corrected prototype assumptions](#evidence-and-two-corrected-prototype-assumptions)
- [Factory identity access: local signature verified, 2026-09-23](#factory-identity-access-local-signature-verified-2026-09-23)
  - [Approved local test and result](#approved-local-test-and-result)
  - [Current vehicle evidence and access boundary](#current-vehicle-evidence-and-access-boundary)
  - [The Binder signing operation can express the stock TLS primitive](#the-binder-signing-operation-can-express-the-stock-tls-primitive)
  - [A crypto test is not read-only: initialization has additional effects](#a-crypto-test-is-not-read-only-initialization-has-additional-effects)
  - [Transport and remaining proof](#transport-and-remaining-proof)
- [SOC traced into the stock vehicle-status report, 2026-09-23](#soc-traced-into-the-stock-vehicle-status-report-2026-09-23)
  - [SOC field and scale are independently connected](#soc-field-and-scale-are-independently-connected)
  - [The stock client copies this field into message 512](#the-stock-client-copies-this-field-into-message-512)
  - [What this changes for an official-cloud helper](#what-this-changes-for-an-official-cloud-helper)
- [Bootstrap traced: cellular gate, routes and hardware TLS, 2026-09-23](#bootstrap-traced-cellular-gate-routes-and-hardware-tls-2026-09-23)
  - [The recorded private-profile failure stops before DNS](#the-recorded-private-profile-failure-stops-before-dns)
  - [The client also selects cellular routes, but their failure is not fatal here](#the-client-also-selects-cellular-routes-but-their-failure-is-not-fatal-here)
  - [Public TLS uses standard encryption and a hardware-backed client identity](#public-tls-uses-standard-encryption-and-a-hardware-backed-client-identity)
  - [Token is downstream of successful login](#token-is-downstream-of-successful-login)
  - [Consequence for the requested official-cloud sidecar](#consequence-for-the-requested-official-cloud-sidecar)

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

- [CloudCanSnapshotProbe.java](../../tools/telematics/CloudCanSnapshotProbe.java)
  passively reads the stock YUN callback for 30 seconds, then unregisters.
- [status512_body.py](../../research/telematics-firmware/status512_body.py) builds
  the native 104-byte body. Seven bounded ARM64 fixtures compare the native
  serializer, AES/MD5 and body-copy routine byte for byte, including both charging
  branches. This caught and corrected a draft cache-index-13 offset before upload.
- [status_upload_probe.py](../../tools/telematics/status_upload_probe.py) defaults
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

> **Superseded 2026-09-23:** the stock-client adaptation went live the same day: stock `cloudmanager` holds its TCP connection over Wi-Fi and the phone shows a connected car with live values, so continuous presence comes from the stock client, not a separate uploader — see [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23).

The sections below preserve the earlier stages; their outstanding items describe
what was unknown at that time and are superseded by the live result above.

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
[registration_packet.py](../../research/telematics-firmware/registration_packet.py)
and [verify_registration_native.py](../../research/telematics-firmware/verify_registration_native.py).
Five synthetic packet fixtures match the actual firmware serializer/AES byte
for byte, including the native IMSI digest; no artificial fixture was sent.

[registration_probe.py](../../tools/telematics/registration_probe.py) defaults to a
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

> **Superseded 2026-09-23:** done the same day: a fresh capture, SOC 74% matching the getter, one report 512 and 74% on the phone — see [Real SOC reached the official phone app over Wi-Fi, 2026-09-23](#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23).

### Passive stock state capture after login

The new bounded [CloudCanSnapshotProbe](../../tools/telematics/CloudCanSnapshotProbe.java)
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

> **Superseded 2026-09-23:** resolved: the charging override is device 1009 / FID `0x34400018` (live 1), the `0x417` stream reads DEVICE_OFFLINE_ALWAYS, and the upload ran — see [Report completeness and the resolved charging field](#report-completeness-and-the-resolved-charging-field).

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
[the host wrapper](../../tools/telematics/tls_identity_probe.py) and
[the native OpenSSL adapter](../../tools/telematics/tls_identity_client.c).
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

> **Superseded 2026-09-23:** all of it followed the same day: registration, discovery and login, then a measured report shown by the phone — see [Official-cloud registration and login over Wi-Fi verified, 2026-09-23](#official-cloud-registration-and-login-over-wi-fi-verified-2026-09-23) and [Real SOC reached the official phone app over Wi-Fi, 2026-09-23](#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23).

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

[The host probe](../../tools/telematics/local_identity_probe.py) requested public
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

> **Superseded 2026-09-23:** the TLS adapter, registration/login and a measured report 512 all followed the same day — see [Factory mutual TLS over vehicle Wi-Fi verified, 2026-09-23](#factory-mutual-tls-over-vehicle-wi-fi-verified-2026-09-23) and [Real SOC reached the official phone app over Wi-Fi, 2026-09-23](#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23).

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

**Reading SOC was already solved.** The [vehicle-data allowlist](../vehicle-data-findings.md#widget-allowlist-2026-08-22)
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

> **Superseded 2026-09-23:** the authenticated path and the phone feed are both proven: a helper upload of message 512 showed 74% in the phone app, and one `notify_nw(4)` under `double_apn` opens the stock gate — see [Real SOC reached the official phone app over Wi-Fi, 2026-09-23](#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23) and [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23).

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
[message-512 inspector](../../research/telematics-firmware/inspect_status512.py)
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

> **Superseded 2026-09-23:** Wi-Fi transport after the gate is proven: with empty APN interfaces the stock client resolved, connected and completed TLS over Wi-Fi — see [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23).

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

> **Superseded 2026-09-23:** both are established: a helper login and upload showed 74% in the phone app, and the stock client itself runs over Wi-Fi after `notify_nw(4)` — see [Real SOC reached the official phone app over Wi-Fi, 2026-09-23](#real-soc-reached-the-official-phone-app-over-wi-fi-2026-09-23) and [cloud-tile.md, Stock-client Wi-Fi adaptation, 2026-09-23](cloud-tile.md#stock-client-wi-fi-adaptation-2026-09-23).

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
