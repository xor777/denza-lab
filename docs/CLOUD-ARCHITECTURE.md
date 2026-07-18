# Car ADB Gateway — Relay-Only Architecture v3

Status: normative description of the implementation in this repository. Updated
2026-07-18. This relay version has not been deployed yet; live end-to-end and
soak verification remain mandatory before production use.

Car ADB Gateway is a standalone, vehicle-agnostic Android app for head units
where ADB is available locally. It can be installed alongside the existing
LAN-only Denza Gateway and does not depend on the vehicle brand.

Version 3 has no LAN listener, LAN workflow, or configurable self-hosted relay
([CAG-001](CAR-ADB-GATEWAY-DECISIONS.md#cag-001),
[CAG-002](CAR-ADB-GATEWAY-DECISIONS.md#cag-002),
[CAG-003](CAR-ADB-GATEWAY-DECISIONS.md#cag-003)). The
[decision log](CAR-ADB-GATEWAY-DECISIONS.md) records rationale and revision
history; this document defines current behavior.

## 1. Roles and Access Grants

Public reachability of TCP port 443 does not grant any role access to a vehicle.

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
  forever ([CAG-003](CAR-ADB-GATEWAY-DECISIONS.md#cag-003)).
- The relay sees outer identities and key-to-port assignments. Inner SSH between
  the computer and the vehicle remains end-to-end encrypted.
- During the first one-code bootstrap, the fixed relay is trusted as the source
  of the inner host key. The CLI pins that key afterward
  ([CAG-006](CAR-ADB-GATEWAY-DECISIONS.md#cag-006)).

## 3. Relay

`ops/ansible` configures stock OpenSSH and the commands in `platform/relay/`.
Port 22 remains the administrative endpoint; port 443 serves the application.

### 3.1 Restricted Accounts

| Account | Authentication | Restriction |
| --- | --- | --- |
| `cag-device` | A dedicated vehicle public key | Remote forwarding only, with `permitlisten=127.0.0.1:<assigned-port>`; no commands. |
| `cag-client` | The active or pending developer public key | Local forwarding only, with `permitopen=127.0.0.1:<assigned-port>`; no commands. |
| `cag-enroll` | A temporary administrator invite through PAM | Forwarding disabled; forced enrollment command only. |
| `cag-pair` | A vehicle pairing code through PAM | Forwarding disabled; forced staging command only. |
| `cag-control` | A vehicle public key | Forwarding disabled; the per-key forced command is restricted to its device ID. |
| `cag-authkeys` | No SSH login | Reads state for `AuthorizedKeysCommand`. |

TTY, shell, X11, agent forwarding, tunnel devices, user RC files, and Unix
socket forwarding are disabled. The control key stays registered while access
is disabled so the user can enable it manually again; that key cannot forward
traffic.

### 3.2 State, Codes, and Rate Limiting

`/opt/cag/state/state.json` is the only mutable state file. Every operation that
can delete an expired record or change data holds
`/opt/cag/state/locks/state.lock` through `flock`; writes use fsync and an atomic
rename.

State includes vehicles, client public keys, active grants, pending
replacements, codes, and per-source rate limits. `AuthorizedKeysCommand`
dynamically renders restricted key entries from it.

- Enrollment code: `XXXX-XXXX`, default TTL 60 minutes, one vehicle. A retry
  with the same device key after a lost response is idempotent; a different
  device key is rejected.
- Pairing code: `XXXX-XXXX`, TTL 10 minutes. Five invalid SSH password attempts
  from one source trigger a five-minute lockout.
- The code alphabet excludes `0/O` and `1/I`.
- Expired pending state is removed without changing the active grant.

An administrator creates the initial invite with:

```bash
sudo cag-admin invite
```

### 3.3 Two-Phase Computer Replacement

A vehicle has one trusted computer and one active inner SSH session
([CAG-004](CAR-ADB-GATEWAY-DECISIONS.md#cag-004)). Replacement preserves the old
access until the new computer is confirmed
([CAG-005](CAR-ADB-GATEWAY-DECISIONS.md#cag-005)):

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
Retries after a lost enrollment, pair-submission, or commit response are safe.

## 4. Android Application

Gradle module: `apps/car-adb-gateway/`; application ID: `ru.adbgw.gateway`;
minSdk 26.
ADB, device, and inner host keys live in app-private storage, and Android backup
is disabled ([CAG-010](CAR-ADB-GATEWAY-DECISIONS.md#cag-010)).

### 4.1 First-Time Setup

1. The app creates its own ADB RSA key when it first attempts a local shell.
2. If raw adbd requires authorization, Android shows the standard system
   dialog. The user approves it and taps the retry action
   ([CAG-007](CAR-ADB-GATEWAY-DECISIONS.md#cag-007)).
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
bundle. Inner SSH allows forwarding only to the exact detected host and port.

With raw port `5555`, the computer's standard ADB key can trigger a second
Android authorization dialog. This is expected.

### 4.3 State and Visible Activity

Onboarding, ADB, relay, client, enabled state, pairing, and permanent failure are
independent state-machine dimensions. The main screen translates them into
simple statuses and never parses or records ADB commands
([CAG-008](CAR-ADB-GATEWAY-DECISIONS.md#cag-008)).

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
permanent WakeLock ([CAG-011](CAR-ADB-GATEWAY-DECISIONS.md#cag-011)).

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
  are recoverable. Host-key mismatch, a revoked device key, and corrupt keys
  stop automatic retry in a visible fail-closed state.

Force Stop is an Android platform boundary: an ordinary APK cannot restart
itself afterward until it is opened manually
([CAG-012](CAR-ADB-GATEWAY-DECISIONS.md#cag-012)).

### 4.5 Manual Disconnect

The disconnect action closes inner sessions and the tunnel, persists
`enabled=false`, and makes a best-effort request to disable forwarding on the
relay. The service does not start after reboot. Re-enabling requires an explicit
user action and a successful short control connection
([CAG-009](CAR-ADB-GATEWAY-DECISIONS.md#cag-009)).

## 5. User Interface

The app is landscape-first for a 15–16 inch display. The main screen has a large
status card, vehicle name, live activity, one connect-or-replace-computer action,
and one disconnect-remote-access action. SSH, PAM, forwarding, smart sockets,
and fingerprints appear only under Support.

Two separate full-access warnings
([CAG-013](CAR-ADB-GATEWAY-DECISIONS.md#cag-013)) appear:

1. before first vehicle enrollment;
2. before every developer pairing code is displayed.

The pairing screen says that the code must be shared only with a trusted person
and shows `cag pair XXXX-XXXX`. There is no computer list: the user works with
one current computer and an explicit replacement action.

## 6. Developer CLI

`platform/cli/` builds one Go binary for macOS and Linux and does not modify
`~/.ssh/config`. Runtime dependencies are system OpenSSH and Android Platform
Tools when an ADB command is executed.

```text
cag pair <CODE>
cag connect
cag connect -- adb ...
cag status
cag disconnect
```

State lives in the platform user configuration directory: `~/.config/cag` on
Linux/XDG and `~/Library/Application Support/cag` on macOS. It contains the
client key, fixed relay host key, pinned vehicle host key, active bundle, and
SSH control socket.

`cag connect` keeps a ControlMaster tunnel in the background with a 30-second
keepalive. Smart-socket mode uses `ADB_SERVER_SOCKET`; raw-adbd mode allocates a
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

## 8. Verification Status

Completed locally on 2026-07-18:

- relay unit tests for TTL, source lockout, idempotent enrollment, pending
  rollback, two-phase replacement, and persistent disable;
- Ansible syntax check and production-profile lint;
- CLI unit tests, `go vet`, Darwin build, and Linux cross-build;
- Android unit tests for the state reducer, backoff, endpoint plan, and relay
  protocol;
- debug APK build with minSdk 26 and targetSdk 36, plus Android lint with no
  errors.

Required before production:

- deploy the new Ansible role and run negative SSH integration tests on the
  relay;
- complete relay → CLI → inner SSH → real ADB end-to-end verification;
- verify API levels 26, 31, 34, 35, and 36;
- test installation and ADB approval, reboot, sleep/wake, network switching,
  adbd and relay restart, and Mac/Linux computer replacement on a real head
  unit;
- inspect head-unit sockets to prove there is no external listener;
- run a 72-hour fault-injection soak and measure recovery: at most 90 seconds
  for relay/network and at most 60 seconds for ADB.
