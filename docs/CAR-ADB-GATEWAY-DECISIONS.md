# Car ADB Gateway Decision Log

This is an ADR-lite log of product and architecture decisions. Accepted entries
are never deleted: when direction changes, mark an entry `Superseded` and link
to the replacement. The normative current design lives in
[CLOUD-ARCHITECTURE.md](CLOUD-ARCHITECTURE.md).

<a id="cag-001"></a>
## CAG-001 — Relay Only, No LAN Mode

- **Status / date:** Accepted — 2026-07-18.
- **Context:** The new app is intended for reliable remote diagnostics; the LAN workflow already belongs to Denza Gateway.
- **Decision:** The new APK has no LAN listener, LAN UI, or local connection workflow.
- **Rationale:** A single network path gives the user one mental model and makes recovery and access control testable.
- **Alternatives:** Hybrid LAN/cloud; extend the existing app.
- **Consequences and risks:** Relay availability becomes a product dependency; local emergency access stays outside this APK.
- **Evidence:** `InnerGatewayServer` binds only to `127.0.0.1`; the Wi-Fi permission is absent; the APK builds.
- **Revisit if:** A separately authorized offline workflow can be clearly isolated from the relay path.

<a id="cag-002"></a>
## CAG-002 — Separate Vehicle-Agnostic App

- **Status / date:** Accepted — 2026-07-18.
- **Context:** Denza Gateway is a branded LAN product, while the new mechanism applies to any Android head unit with local ADB.
- **Decision:** Use the `:car-adb-gateway` module and application ID `ru.adbgw.gateway`; the old APK can remain installed alongside it.
- **Rationale:** This avoids a risky migration and keeps Denza-specific legacy code out of the generic product.
- **Alternatives:** Rename or replace `denza-gateway`; add cloud access to it.
- **Consequences and risks:** Two gateway APKs must be distinguished clearly; no compatibility shim connects them.
- **Evidence:** Separate Gradle module, manifest, package, launcher, and `car-adb-gateway.apk`.
- **Revisit if:** The legacy LAN app is formally retired.

<a id="cag-003"></a>
## CAG-003 — Fixed Relay and Pinning

- **Status / date:** Accepted — 2026-07-18.
- **Context:** A configurable or trust-on-first-use relay weakens one-code onboarding for a nontechnical user.
- **Decision:** Use only `adbgw.ru:443` and fingerprint `SHA256:w02E2cvN65HmjeC6h9aLY/6zovde3nvqorQPYtNRp6c` in Android and the CLI.
- **Rationale:** A copied code cannot silently direct the vehicle to an attacker's host.
- **Alternatives:** Self-hosted address in the UI; TOFU; host-certificate CA.
- **Consequences and risks:** Host migration or key rotation requires a coordinated update. The configurable self-hosted relay from v2 is superseded.
- **Evidence:** Constants in Android and Go; Ansible verification checks DNS and the fingerprint.
- **Revisit if:** Signed key rotation or a separate enterprise build becomes available.

<a id="cag-004"></a>
## CAG-004 — One Trusted Computer

- **Status / date:** Accepted — 2026-07-18.
- **Context:** A vehicle user does not need a developer-key manager.
- **Decision:** Allow one trusted computer and one active inner SSH session per vehicle.
- **Rationale:** Connect or replace computer is understandable and revokes access unambiguously.
- **Alternatives:** A computer list; several concurrent developers; administrator grants only.
- **Consequences and risks:** Changing developers requires a new code; concurrent work is not supported.
- **Evidence:** One active grant per device; the inner server stores one key and closes old sessions.
- **Revisit if:** A verified need for concurrency outweighs the UX and security cost.

<a id="cag-005"></a>
## CAG-005 — Two-Phase Replacement

- **Status / date:** Accepted — 2026-07-18.
- **Context:** Revoking the old key before verifying the new one can leave the vehicle unreachable.
- **Decision:** Stage the new key as pending; commit only after inner authentication; expiry does not change the active grant.
- **Rationale:** A failed replacement is nondestructive and operations are idempotent.
- **Alternatives:** Revoke then add; keep two keys indefinitely; replace on the relay only.
- **Consequences and risks:** During the short pending window both keys can reach the vehicle port, but only the old key or the correct code passes inner authentication.
- **Evidence:** Relay replacement and expiry unit tests, plus the CLI failed-replacement test.
- **Revisit if:** The pairing protocol changes or state moves to an external transactional system.

<a id="cag-006"></a>
## CAG-006 — One-Code Bootstrap

- **Status / date:** Accepted — 2026-07-18.
- **Context:** Fingerprint comparison or a second secret makes pairing error-prone.
- **Decision:** One code stages the developer key and authorizes it on the vehicle; the fixed relay supplies the first inner host key, which the CLI then pins.
- **Rationale:** The target UX is one `cag pair CODE` command with no SSH knowledge required.
- **Alternatives:** Out-of-band fingerprint; two codes; QR mutual authentication; administrator bundle.
- **Consequences and risks:** A relay compromised during bootstrap can substitute the inner key; later mismatches fail closed.
- **Evidence:** The CLI uses temporary candidate known-hosts and stores the vehicle key only after `pair-complete`.
- **Revisit if:** A QR camera or hardware-backed vehicle identity becomes available.

<a id="cag-007"></a>
## CAG-007 — Standard ADB Authorization

- **Status / date:** Accepted — 2026-07-18.
- **Context:** An ordinary APK cannot silently authorize its own ADB key.
- **Decision:** An app-private ADB RSA key and the standard Android authorization dialog are mandatory parts of onboarding.
- **Rationale:** This uses the real platform trust boundary without root or a system APK.
- **Alternatives:** Preinstalled keys; system signing; reuse the developer key; smart socket only.
- **Consequences and risks:** A person must be at the head unit once; raw desktop ADB may request a second approval.
- **Evidence:** `AdbProvisioner`, `LocalAdbClient`, and a distinct `AuthorizationRequired` state.
- **Revisit if:** Firmware provides a documented privileged enrollment API.

<a id="cag-008"></a>
## CAG-008 — Activity Without Command Inspection

- **Status / date:** Accepted — 2026-07-18.
- **Context:** Exact shell commands require parsing ADB and create privacy and maintenance risks.
- **Decision:** Observe only SSH session/channel events, duration, an activity pulse, and last-activity time.
- **Rationale:** These signals are reliable and give useful life to the large status display.
- **Alternatives:** Parse ADB shell traffic; keep a raw log; show no activity.
- **Consequences and risks:** The exact command is not visible; active means an ADB channel is open, not that traffic is continuous.
- **Evidence:** Listeners in `InnerGatewayServer` and `StatusCard`; no payload logger exists.
- **Revisit if:** A privacy-reviewed command-audit requirement appears.

<a id="cag-009"></a>
## CAG-009 — Persistent Manual Disconnect

- **Status / date:** Accepted — 2026-07-18.
- **Context:** The user must be able to stop access without fighting self-healing behavior.
- **Decision:** Close tunnels and sessions, persist the disabled state, and do not reconnect after boot until the user enables access manually.
- **Rationale:** Explicit user intent takes precedence over automatic recovery.
- **Alternatives:** Temporary disconnect; stop only the service; administrator revoke.
- **Consequences and risks:** Enabling requires the relay; a non-forwarding control key remains registered for this action.
- **Evidence:** Reducer test, state store, boot guard, and relay disable test.
- **Revisit if:** Timed or administrator-enforced access windows are required.

<a id="cag-010"></a>
## CAG-010 — Platforms and Distribution

- **Status / date:** Accepted — 2026-07-18.
- **Context:** Some target head units run Android 8, the APK is installed from USB, and developers use different desktop operating systems.
- **Decision:** Use minSdk 26, APK distribution, and one Go CLI for macOS/Linux; Windows is outside the first release.
- **Rationale:** This covers the target head unit without privileged installation, and the CLI does not depend on macOS-only tools.
- **Alternatives:** Play Store; Android 12+; shell script; Windows in the MVP.
- **Consequences and risks:** APK updates are manual; OEM background behavior requires live testing.
- **Evidence:** Gradle assembly, Darwin build, and Linux cross-build.
- **Revisit if:** A managed update channel or Windows demand appears.

<a id="cag-011"></a>
## CAG-011 — `specialUse` Foreground Service

- **Status / date:** Accepted — 2026-07-18.
- **Context:** Android 15 limits duration and boot behavior for `dataSync`; this tunnel is long-lived and user-visible.
- **Decision:** Use `specialUse` with a subtype, `START_STICKY`, boot/package receivers, and no permanent WakeLock.
- **Rationale:** The service type matches the function and avoids abusing a time-limited service category.
- **Alternatives:** `dataSync`; no foreground service; WakeLock; WorkManager polling.
- **Consequences and risks:** OEM software can still restrict the app; store review needs separate verification.
- **Evidence:** Manifest and `GatewayService`; `dataSync` and WakeLock permissions are absent.
- **Revisit if:** Android introduces a dedicated remote-diagnostics type or the APK becomes a privileged daemon.

<a id="cag-012"></a>
## CAG-012 — Self-Healing Boundaries

- **Status / date:** Accepted — 2026-07-18.
- **Context:** Network, relay, sleep, and adbd failures are normal, but identity and key errors must not retry forever.
- **Decision:** Supervise ADB/inner SSH independently from the relay; retry transient failures with bounded jitter; fail closed on mismatch, revocation, or corruption. Force Stop requires manual launch.
- **Rationale:** Healthy components remain available and security failures stay visible.
- **Alternatives:** Restart everything; uniform infinite retry; WakeLock; privileged watchdog.
- **Consequences and risks:** An ordinary APK cannot recover from Force Stop; OEM process killers require evidence.
- **Evidence:** Supervisor and backoff tests, heartbeat, network callback, boot receiver, and support text.
- **Revisit if:** The APK becomes system/privileged or soak testing discovers another failure class.

<a id="cag-013"></a>
## CAG-013 — Two Full-Access Warnings

- **Status / date:** Accepted — 2026-07-18.
- **Context:** Enrollment creates a remote capability, while a pairing code delegates that capability to a computer.
- **Decision:** Warn before enrollment and separately before every pairing code; identify the trusted source or person.
- **Rationale:** These are different high-impact actions involving different participants.
- **Alternatives:** One onboarding disclaimer; a footnote; no repeated warning.
- **Consequences and risks:** One additional tap in an infrequent, high-impact workflow.
- **Evidence:** Two independent `AlertDialog` flows in `MainActivity`.
- **Revisit if:** Managed policy provides equivalent informed consent outside the APK.
