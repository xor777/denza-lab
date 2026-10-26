# Car ADB Gateway — Relay-Only Architecture

Status: current design of the implementation in this repository, last checked
2026-10-26. The lease and garbage-collection lifecycle passed local acceptance,
an idempotent Ansible rollout, and live checks on `adbgw.ru`. From 2026-10-18 to
2026-10-20 the public port sat behind a transparent sslh that also served a
download page ([CAG-014](car-adb-gateway-decision-log.md#cag-014)); the page moved
to GitHub and sshd owns port 443 again
([CAG-015](car-adb-gateway-decision-log.md#cag-015)). On 2026-10-26 the whole
chain - enrollment, tunnel, pairing, `cag connect`, `adb shell` - first worked
end to end, on an Android 15 emulator and then on the owner's DiLink 5.1 head
unit, through the production relay ([Verification](#8-verification-status)); a
wake from the car's sleep and the soak test are still ahead.

Car ADB Gateway is a standalone, vehicle-agnostic Android app for head units
where ADB is available locally. It can be installed alongside the existing
LAN-only Denza Gateway and does not depend on the vehicle brand.

The current design uses one fixed relay and exposes no LAN listener or LAN setup
flow
([CAG-001](car-adb-gateway-decision-log.md#cag-001),
[CAG-002](car-adb-gateway-decision-log.md#cag-002),
[CAG-003](car-adb-gateway-decision-log.md#cag-003)). The
[decision log](car-adb-gateway-decision-log.md) records the reasoning and revision
history.

## 1. Roles and Access Grants

Port 443 is public, but every useful operation still requires one of the grants
below.

| Role | How authority is obtained | Allowed actions |
| --- | --- | --- |
| Vehicle | A relay administrator creates a 60-minute enrollment code. The APK creates a device key and consumes the code once. | Publish only its assigned loopback port, create a pairing code, confirm replacement, or disable its own grant. |
| Relay administrator | A regular administrative SSH key on port 22. | Deploy and verify the host, create a vehicle invite, and inspect or revoke server-side state. The administrator is not involved for each developer. |
| Developer | A person at an enrolled vehicle accepts a warning and displays a ten-minute pairing code. `cag pair CODE` registers that computer's public key for the vehicle. | Open only the assigned port for that vehicle and complete end-to-end authentication to it. Relay shell access and other vehicles are unavailable. |

An APK without an enrollment code cannot register with the relay. A pairing code
displayed by an already enrolled vehicle cannot enroll another vehicle.

## 2. Topology and Trust Boundaries

```text
Android head unit                         relay: adbgw.ru                 developer computer

ADB 5037/5555 <- sshd 127.0.0.1:2222 <- SSH -R -> 127.0.0.1:device-port <- SSH -W -> cag CLI
                    end-to-end SSH              OpenSSH/PAM, port 443       macOS or Linux
```

- The vehicle always initiates the outbound connection.
- The only Android listener is `127.0.0.1:2222`; Wi-Fi, cellular, and wildcard
  interfaces are never bound.
- Vehicle ports on the VPS are also reachable only through relay loopback.
- `adbgw.ru:443` and its Ed25519 fingerprint are embedded in Android and the
  CLI. A mismatch is a permanent, fail-closed error, not a reason to retry
  forever ([CAG-003](car-adb-gateway-decision-log.md#cag-003)).
- The relay sees outer identities and key-to-port assignments. Inner SSH between
  the computer and the vehicle remains end-to-end encrypted.
- During the first one-code bootstrap, the fixed relay is trusted as the source
  of the inner host key. The CLI pins that key afterward
  ([CAG-006](car-adb-gateway-decision-log.md#cag-006)).

## 3. Relay

`ops/ansible` configures stock OpenSSH and the commands in `platform/relay/`.
Port 22 remains the administrative endpoint; port 443 serves the application.
The relay serves nothing else: the download page and the APKs live on GitHub
([CAG-015](car-adb-gateway-decision-log.md#cag-015)), and the sslh multiplexer of
[CAG-014](car-adb-gateway-decision-log.md#cag-014) is gone.
The administrator web UI listens only on relay loopback port `8787` and is
reachable through an SSH local-forward on port 22. It has no public listener or
second login prompt.

### 3.1 Restricted Accounts

| Account | Authentication | Restriction |
| --- | --- | --- |
| `cag-device` | A dedicated vehicle public key | Remote forwarding only, with `permitlisten=127.0.0.1:<assigned-port>`; no commands. |
| `cag-client` | The active or pending developer public key | Local forwarding only, with `permitopen=127.0.0.1:<assigned-port>`; no commands. |
| `cag-enroll` | A temporary administrator invite through PAM | Forwarding disabled; forced enrollment command only. |
| `cag-pair` | A vehicle pairing code through PAM | Forwarding disabled; forced staging command only. |
| `cag-control` | A vehicle public key | Forwarding disabled; the per-key forced command is restricted to its device ID. |
| `cag-authkeys` | No SSH login | Reads state for `AuthorizedKeysCommand`. |
| `cag-web` | No SSH login | Calls only the allow-listed `cag-admin` operations through sudo; it cannot read or write state directly. |

TTY, shell, X11, agent forwarding, tunnel devices, user RC files, and Unix
socket forwarding are disabled. The control key stays registered while access
is disabled so the user can enable it manually again; that key cannot forward
traffic. A vehicle session that stops answering is dropped after about 45
seconds (`ClientAliveInterval 15`, `ClientAliveCountMax 3` for `cag-device`), so
a reconnecting vehicle can bind its port again within the recovery target.

### 3.2 State, Codes, and Rate Limiting

`/opt/cag/state/state.json` is the only mutable state file. Every operation that
can delete an expired record or change data holds
`/opt/cag/state/locks/state.lock` through `flock`; writes use fsync and an atomic
rename.

State schema v3 includes vehicles, separate tunnel and control keys, 14-day
leases, client public keys, active grants, pending replacements, and codes. An
enrollment code may carry one administrator-supplied vehicle label; existing
v1/v2 state migrates automatically.
`AuthorizedKeysCommand` takes a shared read lock and never runs cleanup or
writes state. Mutations take an exclusive lock, validate the full schema and
cross-record invariants, fsync the new file, atomically rename it, and fsync the
state directory.

`cag-authkeys` is not a member of the writable state group. It receives only
explicit POSIX ACL read/traverse access to the state and lock files. OpenSSH
and fail2ban own source authentication throttling, so rejected passwords do not
rewrite the JSON state.

The lock file is `root:cag-state 0660` and the forced commands run as
`cag-enroll`, `cag-pair` and `cag-control`, so only the lock's owner repairs its
mode. `cag-state lock-check` takes the exclusive lock and writes a private file
in the state directory exactly as a mutation does, without replacing the state;
`verify-relay-host.yml` runs it as each of those accounts.

- Enrollment code: `XXXX-XXXX`, default TTL 60 minutes, one vehicle. A retry
  with the same device key after a lost response is idempotent; a different
  device key is rejected.
- Pairing code: `XXXX-XXXX`, TTL 10 minutes. Only one pairing window can be open
  for a vehicle; retrying its request ID returns the same code.
- The code alphabet excludes `0/O` and `1/I`.
- Expired pending state is removed without changing the active grant.

Every registration is a 14-day lease. The enabled app renews it before opening
the tunnel and once per day; manually enabling access renews it too. A disabled
vehicle sends no heartbeat and is forgotten after the same 14 days. An hourly
systemd timer removes expired devices and their grants, pending records, codes,
and unreferenced client keys. Ports from `20000..65535` are then reusable.

An administrator creates the initial invite with:

```bash
sudo cag-admin invite --label "Ivan - Denza N9"
sudo cag-admin list
sudo cag-admin status DEVICE_ID
sudo cag-admin rename DEVICE_ID "Ivan - N9, black"
sudo cag-admin remove DEVICE_ID
sudo cag-admin doctor
```

`remove` and the hourly GC end only the sessions of the removed vehicles. All
vehicles share `cag-device` and all computers `cag-client`, so sessions are found
by relay port: the vehicle's session owns the loopback listener on it and a
computer's session holds a loopback connection to it.

### 3.3 Administrator Web UI

The panel is a convenience layer over the same commands. It is installed and
verified only through Ansible. The administrator opens a local tunnel and then
visits `http://127.0.0.1:8787`:

```bash
ssh -N -L 8787:127.0.0.1:8787 adbgw.ru
```

The page shows the editable vehicle label, current reverse-tunnel listener,
enabled state, lease deadline, and trusted computer. `Online` means only that
the assigned loopback listener currently exists; it is not persisted and does
not add a heartbeat. Removal requires typing the current label and still uses
the normal cascading `cag-admin remove` path.

### 3.4 Two-Phase Computer Replacement

A vehicle has one trusted computer and one active inner SSH session
([CAG-004](car-adb-gateway-decision-log.md#cag-004)). Replacement preserves the old
access until the new computer is confirmed
([CAG-005](car-adb-gateway-decision-log.md#cag-005)):

1. The authorized vehicle creates a pairing request and displays the code.
2. `cag pair CODE` submits the new public key. The relay stores it as pending
   and temporarily allows both active and pending keys to reach only that
   vehicle's port.
3. The CLI starts end-to-end SSH to the vehicle and presents the public key,
   then the same code.
4. The vehicle verifies the code and key, then runs the authorized, idempotent
   `pair-commit <fingerprint>` command.
5. The relay atomically replaces the active grant.
6. The vehicle stores the new trusted key and closes all previous inner
   sessions. Any surviving old outer forward can no longer pass inner
   authentication.

Failure or expiry before commit leaves the old grant and session unchanged.
Android persists the candidate key before relay commit, and the CLI persists a
temporary bundle plus only a hash of the code. Retries after a lost enrollment,
pair-submission, commit, local save, or confirmation response resume safely.

## 4. Android Application

Gradle module: `apps/car-adb-gateway/`; application ID: `ru.adbgw.gateway`;
minSdk 26.
ADB, tunnel, control, and inner host keys live in app-private storage. Android
backup is disabled ([CAG-010](car-adb-gateway-decision-log.md#cag-010)).

### 4.1 First-Time Setup

1. The app creates its own ADB RSA key when it first attempts a local shell.
2. If raw adbd requires authorization, the app offers its key once, Android
   shows the standard system dialog, and the app moves on by itself a few
   seconds after the person taps Allow. Background checks never offer the key;
   only opening the app without access and the «Запросить снова» button do
   ([CAG-007](car-adb-gateway-decision-log.md#cag-007),
   [CAG-017](car-adb-gateway-decision-log.md#cag-017)).
3. The app verifies shell access and makes a best-effort attempt to apply
   available device-idle and background app-op settings through the authorized
   local ADB connection.
4. The user enters the administrator's eight-character enrollment code.
5. A full-remote-access warning appears before enrollment.
6. The APK registers the device and host public keys, stores the device ID and
   port, and enables relay-only access automatically.

### 4.2 ADB Endpoint

Detection order is smart socket `5037`, then raw adbd `5555`; loopback is tested
first, followed by every local non-loopback IPv4 address on the head unit. The
endpoint type and actual host are sent to the relay and included in the pairing
bundle.

Inner SSH accepts a `direct-tcpip` channel only to port 5555 or 5037 and always
connects it to the endpoint the car holds now, whatever host the computer asks
for, because the bundle's host is frozen at pairing. Any other port is refused.
No listener is opened for this: the channel itself (`AdbProxyChannel`) carries
the bytes.

- Port 5037, the smart socket: a plain pass-through.
- Port 5555, raw adbd: the channel ends the ADB handshake itself
  ([CAG-016](car-adb-gateway-decision-log.md#cag-016)). It passes the computer's
  CNXN to adbd unchanged, signs adbd's AUTH token with the app's approved key
  (`LocalAdbClient.proxyAdbConnect`), passes adbd's CNXN back and then pipes
  bytes. The public key is never offered on this path, so the car never asks
  about the computer's key, including after the week that expires an unused
  "always allow" key. A refused signature or an STLS request closes the channel
  with a support event. Data checksums are not verified on this path: adb and
  adbd at protocol `0x01000001` send zero.

### 4.3 State and Visible Activity

Onboarding, ADB, relay, client, enabled state, pairing, and permanent failure are
independent state-machine dimensions. The main screen translates them into
simple statuses and never parses or records ADB commands
([CAG-008](car-adb-gateway-decision-log.md#cag-008)).

Activity is based on reliable SSH events:

- waiting for a computer;
- computer connected, with session duration;
- remote computer active, with a pulse while at least one ADB forwarding
  channel is open;
- computer connected but idle;
- time of last activity.

Endpoint details, relay state, device ID, and a bounded in-memory log are hidden
under Support.

### 4.4 Background Operation and Recovery

The foreground service uses `specialUse`, `START_STICKY`, boot and package-update
receivers, and a default network callback. It does not use `dataSync` or a
permanent WakeLock ([CAG-011](car-adb-gateway-decision-log.md#cag-011)).

ADB/inner SSH and the relay tunnel are supervised independently:

- ADB is checked every 30 seconds, including adbd restarts and endpoint changes.
  Authorization is rechecked, while background app-ops are not rewritten for a
  stable endpoint.
- Relay reconnect uses jittered exponential backoff from 1 to 60 seconds. Each
  attempt connects by hostname and performs DNS resolution again.
- A network change closes the old tunnel and immediately resets backoff.
- SSH heartbeat interval is 30 seconds, with at most three missed replies.
- The watchdog restores a missing loopback server without restarting healthy
  components.
- DNS failures, no network, relay restart, sleep/wake, and temporary ADB loss
  are recoverable. If the lease expired or the device was removed, the app
  clears stale registration/trust and stages a fresh tunnel/control/inner-host
  key set for enrollment. Host-key mismatch and corrupt keys remain visible,
  fail-closed errors.

Force Stop is an Android platform boundary: an ordinary APK cannot restart
itself afterward until it is opened manually
([CAG-012](car-adb-gateway-decision-log.md#cag-012)).

### 4.5 Manual Disconnect

The disconnect action closes inner sessions and the tunnel, persists
`enabled=false`, and makes a best-effort request to disable forwarding on the
relay. The service does not start after reboot. Re-enabling requires an explicit
user action and a successful short control connection
([CAG-009](car-adb-gateway-decision-log.md#cag-009)).

## 5. User Interface

The app is landscape-first for a 15–16 inch display and is drawn in the car's
own dark kit, the language of Denza Apps' Luminofor: black ground, flat plates,
live graphics in the dock's blue with a glow, the car's orange for "waiting on
the person" and red for "stopped". The centre of every screen is the connection
chain «Машина — Сервер — Компьютер»: each node says its state in a word or two
(ADB, relay, the trusted computer), a link lights up when that hop works, and
pulses run along both links while the computer holds an ADB channel
([CAG-008](car-adb-gateway-decision-log.md#cag-008)). Under it are one headline
and at most one line of detail, and at the bottom one connect-or-replace-computer
action and one switch-off action. SSH, relay ports, endpoints and the event log
appear only in the «Сведения» sheet. The screens are stateless
(`GatewayScreens`, `ConnectionChain`); the debug build's `ScreenFixtureActivity`
renders each scene from a fixed state.

Two separate full-access warnings
([CAG-013](car-adb-gateway-decision-log.md#cag-013)) appear:

1. before first vehicle enrollment;
2. before every developer pairing code is displayed.

The pairing warning says that the code must be shared only with a trusted
person; the code card that follows shows the code, the time left and
`cag pair XXXX-XXXX`. There is no computer list: the user works with
one current computer and an explicit replacement action.

## 6. Developer CLI

`platform/cli/` builds one Go binary for macOS and Linux and does not modify
`~/.ssh/config`; every `ssh` call runs with `-F none`, so that file cannot change
it either. Paths are quoted for OpenSSH, because the macOS directory name
contains a space. Runtime dependencies are system OpenSSH and Android Platform
Tools when an ADB command is executed.

```text
cag pair <CODE>
cag pair --new-key <CODE>
cag connect
cag connect -- adb ...
cag status
cag disconnect
```

State lives in the platform user configuration directory: `~/.config/cag` on
Linux/XDG and `~/Library/Application Support/cag` on macOS. It contains the
client key, relay host pin list, pinned current vehicle host key, active bundle,
and SSH control socket. An interrupted pair resumes from temporary state without
submitting the code again. `--new-key` keeps the replacement key staged until the
vehicle confirms it; successful replacement leaves only relay pins and the
current vehicle key in `known_hosts`.

`cag connect` keeps a ControlMaster tunnel in the background with a 30-second
keepalive. The master's messages go to `tunnel.log` and the relay leg of each
inner connection to `proxy.log`, both in the state directory, and their tails
appear in `cag` errors. Smart-socket mode uses `ADB_SERVER_SOCKET`; raw-adbd mode allocates a
free local port and runs `adb connect`.

## 7. Residual Risks

- Anyone can perform the public TCP/SSH handshake on port 443, but shell and
  forwarding remain unavailable without a temporary code or registered private
  key.
- A developer key is restricted to one vehicle's loopback port; a device key
  can publish only its assigned port; a control key cannot forward.
- The relay is trusted during the first one-code bootstrap. A compromised relay
  at that moment can substitute the inner host key. CAG-006 accepts this UX
  tradeoff; later key changes fail closed.
- A leaked, still-valid pairing code is sufficient to replace the trusted
  computer. The warning, ten-minute TTL, attempt limit, and single-vehicle model
  reduce this risk.
- Uninstalling the APK removes private keys and state. A fresh install requires
  a new enrollment invite from an administrator.
- There is no application-level backup workflow; the accepted recovery boundary
  is the existing full-VM backup.

## 8. Verification Status

Completed locally and on `adbgw.ru` for the lifecycle revision on 2026-07-18:

- 18 relay unit tests covering schema migration, leases, GC cascades, port
  wrap/reuse, idempotent submit/commit, shared reads, directory fsync, emergency
  removal, and the 40-device/12-month bounded-state model;
- Ansible syntax check and production-profile lint;
- CLI unit tests, `go vet`, Darwin build, and Linux cross-build;
- Android unit tests for the state reducer, backoff, endpoint plan, and relay
  protocol;
- debug APK build with minSdk 26 and targetSdk 36, plus Android lint with no
  errors;
- local Ansible syntax checks and production-profile lint for ACLs, GC timer,
  schema doctor, SSH source limits, and fail2ban policy;
- Ansible migrated the empty production registration set from schema v1 to v2,
  removed the three confirmed-empty legacy TSV files, and converged to
  `changed=0` on the second run without creating a test device;
- live verification proved doctor `ok=true` with no orphan clients or expired
  devices, read-only `cag-authkeys` ACLs, active/enabled hourly GC, effective
  OpenSSH limits and penalties, fail2ban `5/10m/15m`, listener separation, UFW,
  NTP, DNS, and the pinned relay host key.

On 2026-10-18 the sslh rollout (CAG-014) applied and converged, and
`verify-relay-host.yml` passed 68 checks: the pinned host key through public
port 443 (including a banner-waiting `ssh-keyscan`), the controller's own address
on the sshd backend, sslh and the return path active, loopback-only backends,
the Let's Encrypt HTTPS page, its release list and the port-80 redirect.
`sslh-ev` 2.1.4 failed the banner-waiting check (its probe timeout never fires)
and was replaced by `sslh-select`.

On 2026-10-20 CAG-015 retired it: sslh, the return path, Caddy and port 80 were
removed, sshd listens on 443 again, and `verify-relay-host.yml` passed 59 checks,
including the pinned key through public 443 and nothing of sslh left behind.

### 2026-10-26: Why remote access never came up, and the first end-to-end run

Until 2026-10-26 no vehicle ever finished enrollment: on the relay `cag-admin
list` was empty and no port had ever been handed out. Seven independent defects
each stopped the chain on its own, and every one of them got past the unit
tests, because those ran as the file owner, on a desktop JVM, with fakes that
computed ADB checksums:

| Where | Defect | Fix |
| --- | --- | --- |
| Relay | `cag-state` chmod-ed the root-owned lock before every mutation; as `cag-enroll`, `cag-pair` or `cag-control` that is `EPERM`, so enroll, renew, pairing and endpoint updates all failed (the journal shows a `cag-enroll` login on 2026-07-25 and no registration) | only the owner repairs the mode; `lock-check` runs as each service account in `verify-relay-host.yml` |
| Android | the inner SSH server was a bare `SshServer()`, whose `start()` throws "KeyExchangeFactories not set", so the supervisor waited forever and never opened the tunnel | `SshServer.setUpDefaultServer()` |
| Android | MINA asked Android's stripped Bouncy Castle (registered as "BC") for EC parameters; `ECCurves` failed in its static initializer ("No EC params for nistp384") on the first key load | `SshdRuntime` disables MINA's BC registrar; EC comes from Conscrypt, Ed25519 from EdDSA |
| Android | the tunnel client kept MINA's reject-all forwarding filter, so every connection the relay forwarded back was refused | a filter that admits only forwarded channels to `127.0.0.1:2222` |
| Android | the ADB proxy verified data checksums, which adb and adbd at `0x01000001` send as zero | no checksum check on the proxy path |
| CLI | on macOS the config path contains a space: the known-hosts path and the ControlPath split, so every relay call failed host-key verification | values quoted, the control socket only through `-S`, with a short fallback when the path is too long for a Unix socket |
| CLI | the pairing code leaked into `cag __proxy` (askpass mode is inherited by the ProxyCommand), and `cag connect` hung on the ssh master's inherited stdio | `__proxy` ignores askpass; the master writes to `tunnel.log` |

The relay was rebooted onto kernel 7.0.0-38 the same day, the fixes were rolled
out with `relay-host.yml`, and `verify-relay-host.yml` passed 64 checks
(including `lock-check` as the three service accounts and the `cag-device`
keepalive).

Tests: `GatewayChainIntegrationTest` runs the real `RelayClient` tunnel and the
real `InnerGatewayServer` against a MINA relay, a fake adbd that verifies the RSA
signature, and a MINA "computer" (reach adbd with the app key, pair with a code,
refuse another port, close on a refused signature without offering a key);
`AdbProxyConnectTest` covers the handshake, including zero checksums; relay 59
unit tests; CLI tests run the real `ssh -G` on a config dir with spaces.

Live, on an Android 15 emulator (2560x1600, the head unit's size) through
`adbgw.ru`, with a fresh `cag` and a config directory containing a space:
invite → enrollment in the app → tunnel (port 20000 listening on the relay) →
pairing code on the screen → `cag pair` → `cag connect` → `adb shell` answered
as uid 2000, and the screen showed «Компьютер работает» with pulses on both
links. Updating the APK without opening it brought the tunnel back in about
8 seconds (`MY_PACKAGE_REPLACED`); a cold reboot without opening the app brought
it back 16 seconds after `boot_completed`, and `cag connect -- adb shell`
worked again with no code. The emulator's adbd needs no authorization, so the
signing half of the proxy is proven by the tests, not yet live.

The same day on the owner's head unit (DiLink 5.1, Android 13, firmware
`TP1A.220624.014`), parked, driven over the existing LAN ADB: the first launch
offered the app's key once and the stock *Allow USB debugging?* dialog showed
its fingerprint, which matched the app's key computed from its public key
(`E9:02:C2:43:…:6D:CE`). With *Always allow* ticked and Allow tapped, adbd logged
`adb client authorized` and the app moved to the code by itself, with no tap
(CAG-017). Enrollment with an invite, the tunnel (relay port 20000), the pairing
code and `cag pair` on the owner's Mac, from `~/Library/Application Support/cag`,
followed. `cag connect` put the car on the Mac's adb as `device` at once, and
`adb shell` answered as uid 2000 through `adbgw.ru`. No second dialog appeared:
adbd authorized the app's key for the computer's session (CAG-016). The car's
«Disable background Apps» switch for the app, set by the install, was turned
off in the same session; that is what lets the wake's `BOOT_COMPLETED` start
it.

Required before production:

- a wake from the car's sleep (quickboot) without opening the app;
- verify API levels 26, 31, 34 and 36 (33 on the head unit and 35 on the emulator are done);
- test installation and ADB approval, reboot, sleep/wake, network switching,
  adbd and relay restart, and Mac/Linux computer replacement on a real head
  unit;
- inspect head-unit sockets to prove there is no external listener;
- run a 72-hour fault-injection soak and measure recovery: at most 90 seconds
  for relay/network and at most 60 seconds for ADB.
