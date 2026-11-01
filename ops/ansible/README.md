# Relay Server Ansible

Ansible configuration for the Car ADB Gateway VPS.

The active relay is VDSina service `3291062`, Moscow, `212.193.11.249`
(Ubuntu 26.04, 1 vCPU, 2 GiB RAM, 50 GB, 20 RUB/day), migrated from Vultr on
2026-10-29. Administrative SSH is `dmitry` with the existing operator key and
passwordless sudo; root, password and keyboard-interactive administration are
disabled. SSH runs as a persistent service, with socket activation disabled.
The relay role explicitly installs its time-synchronization service rather
than depending on what the provider image includes.
The existing resolver pair is managed in inventory with Cloudflare first and
Google second. The Google resolver seen from this VPS retained the old A record
after cutover, while Cloudflare already served the new authoritative address.

## Local Setup

```bash
cd ops/ansible
python3 -m venv .venv-ansible
.venv-ansible/bin/pip install -r requirements.txt
.venv-ansible/bin/ansible-galaxy collection install -r requirements.yml
```

## Configure the Server

```bash
.venv-ansible/bin/ansible-playbook playbooks/relay-host.yml
.venv-ansible/bin/ansible-playbook playbooks/verify-relay-host.yml
```

Repeated runs are safe and converge the server to the configuration in this
repository.

Ansible owns the reproducible server boundary: packages, accounts, control-plane
files, PAM, SSH, firewall, and verification. It only initializes
`/opt/cag/state/state.json` when the file is absent; invite codes, enrolled
vehicles, pairing requests, and grants remain mutable runtime state managed by
`cag-state` and are never replaced by a playbook rerun.

After configuration, create a labeled single-use invite code. Its default
lifetime is 60 minutes:

```bash
ssh adbgw.ru sudo cag-admin invite --label "Ivan - Denza N9"
```

Enrollment, pairing, and grant state is stored atomically in schema v3 at
`/opt/cag/state/state.json`. Read-only authorization uses a shared lock; changes
use an exclusive lock. `cag-authkeys` has ACL read access but is not in the
writable state group. OpenSSH per-source penalties and fail2ban (`5` attempts in
`10m`, ban for `15m`) handle source throttling.

## Operator field guide

Routine lifecycle work is automatic. An enabled vehicle renews its 14-day lease
daily, and `cag-gc.timer` removes expired registrations and unreferenced client
keys hourly. A disabled vehicle is also forgotten after 14 days. Runtime devices,
codes, and grants never belong in Ansible variables.

For routine administration, open an SSH local-forward and visit
`http://127.0.0.1:8787`:

```bash
ssh -N -L 8787:127.0.0.1:8787 adbgw.ru
```

The panel is bound only to relay loopback and uses the administrator SSH key as
its access boundary. No firewall rule or separate web password is required.
`Online` means that the assigned reverse-forward listener currently exists;
`enabled` is the persisted access switch, and the lease is the automatic
removal deadline.

```bash
# one-time enrollment code
ssh adbgw.ru sudo cag-admin invite --label "Ivan - Denza N9"

# inspect current state
ssh adbgw.ru sudo cag-admin list
ssh adbgw.ru sudo cag-admin status DEVICE_ID
ssh adbgw.ru sudo cag-admin doctor

# update the human-readable relay label
ssh adbgw.ru sudo cag-admin rename DEVICE_ID "Ivan - N9, black"

# emergency revoke; this vehicle's tunnel and its computer's sessions are closed,
# other vehicles stay connected
ssh adbgw.ru sudo cag-admin remove DEVICE_ID
```

The hourly timer handles routine cleanup. Rotate keys for re-enrollment,
computer replacement, an incident, or a planned relay host-key change. Relay
host-key rotation is additive: ship the new pin to Android and the CLI before
switching the server key.

The CLI remains the recovery and automation interface. Never install or edit
the web service, sudoers policy, or systemd unit manually; rerun
`playbooks/relay-host.yml` instead.

## Download page

The relay serves no web page. The download page and the APKs live on GitHub
([CAG-015](../../docs/car-adb-gateway-decision-log.md#cag-015)); see
[platform/downloads](../../platform/downloads/README.md). Until 2026-10-20 a
transparent sslh shared port 443 with a page on this host
([CAG-014](../../docs/car-adb-gateway-decision-log.md#cag-014)). The `relay_host`
role still removes what that left: sslh, its return path, Caddy and port 80.

## Provision a New VPS

```bash
.venv-ansible/bin/ansible-playbook -u root --ask-pass playbooks/bootstrap.yml
ssh dmitry@212.193.11.249 'sudo -n true'
.venv-ansible/bin/ansible-playbook \
  -e confirm_ssh_lockdown=true \
  playbooks/lockdown.yml
```

If package updates require a reboot:

```bash
.venv-ansible/bin/ansible-playbook -u root --ask-pass \
  -e confirm_reboot=true \
  playbooks/reboot.yml
```

Never store passwords or private keys in inventory or Ansible variables.

## DNS

```text
A  @  212.193.11.249
```

The relay currently uses only the IPv4 record above. Port 443 carries SSH. The
verification playbook checks both DNS resolution and the pinned Ed25519 host-key
fingerprint.

## Relay migration and temporary source bridge

The 2026-10-29 move preserves all three SSH host keys and the schema-v3 runtime
state, including the existing device and trusted computer. Android and the CLI
keep their existing endpoint and pin; no APK or pairing change is needed.
Private backups, host keys and deployment logs are under the ignored,
owner-only `captures/cag-migration-2026-10-29/`. They must never enter Git.
VDSina automatic backups are disabled; this snapshot is a manual migration
backup, not a recurring backup policy.

The source `95.179.132.238` no longer runs a writable relay: its application
SSH listener, web UI and garbage collection are stopped. A systemd TCP proxy
on its public port 443 forwards clients whose DNS cache still holds that IP
to `212.193.11.249:443`. The old admin port 22 still belongs to the source;
use the new IP explicitly for administration during DNS convergence.

The old A record had TTL 86400. Keep the source for at least 24 hours after
both authoritative nameservers publish the new IP, then drain/stop the bridge
before retiring the old VPS. For this cutover, do not begin before
2026-10-30 12:10 MSK. No automatic retirement is scheduled. Do not run `relay-host.yml` against the retired
source: that would enable its old state writers again.

The bridge combines clients under the source IP. The replacement has a
temporary, source-specific OpenSSH penalty exemption and fail2ban ignore entry
for `95.179.132.238`, managed by `relay-migration-proxy.yml`. Key/code
authentication and global connection limits still apply. See
[OpenSSH's source-penalty policy](https://man.openbsd.org/sshd_config#PerSourcePenaltyExemptList).
Remove those two exceptions once the bridge is stopped:

```bash
.venv-ansible/bin/ansible gateways -b -e ansible_host=95.179.132.238 \
  -m ansible.builtin.systemd_service \
  -a 'name=cag-migration-forward.socket enabled=false state=stopped'
.venv-ansible/bin/ansible gateways -b -e ansible_host=95.179.132.238 \
  -m ansible.builtin.systemd_service \
  -a 'name=cag-migration-forward.service state=stopped'

.venv-ansible/bin/ansible-playbook \
  -e relay_migration_proxy_ipv4=95.179.132.238 \
  -e relay_migration_proxy_enabled=false playbooks/relay-migration-proxy.yml
```

Acceptance on 2026-10-29: baseline `14 OK`, relay `64 OK`, final rollout
`59 OK / 0 changed`, no failures, and production-profile lint passed. The final
snapshot and destination state had the same SHA-256. The car was not connected,
so this is server acceptance; live-car ADB and the Russian mobile path remain.

For a future migration, first prepare, lock down and reboot an empty replacement
using the normal playbooks. Save the source's six `ssh_host_*_key[.pub]` files
in a private local directory as flat filenames, and update inventory to the
replacement. The migration playbooks then run in this order (the source IP
override is deliberate):

```bash
.venv-ansible/bin/ansible-playbook \
  -e ansible_host=95.179.132.238 \
  -e confirm_relay_source_migration=true -e relay_migration_phase=freeze \
  -e relay_migration_target_ipv4=212.193.11.249 \
  -e relay_identity_directory=/absolute/private/snapshot \
  playbooks/relay-migration-source.yml

.venv-ansible/bin/ansible-playbook \
  -e confirm_relay_identity_restore=true \
  -e relay_identity_directory=/absolute/private/snapshot \
  playbooks/relay-migration-identity.yml

.venv-ansible/bin/ansible-playbook \
  -e relay_migration_proxy_ipv4=95.179.132.238 \
  playbooks/relay-migration-proxy.yml

.venv-ansible/bin/ansible-playbook \
  -e ansible_host=95.179.132.238 \
  -e confirm_relay_source_migration=true -e relay_migration_phase=forward \
  -e relay_migration_target_ipv4=212.193.11.249 \
  playbooks/relay-migration-source.yml
```

`freeze` disables source writers and application sessions, then fetches the final
state under its exclusive lock. Identity restore checks the application pin and
refuses to overwrite a populated replacement with different runtime state.
Verify the preserved host identity before replacing the new IP's bootstrap
known-hosts entry. After verifying both public IPs, change only the root A
record in REG.RU, leaving `bt` and `www` unchanged, and run both verification
playbooks against the replacement.

If restoring fails before the replacement accepts any state changes, the
source's original state and backed-up SSH configuration remain available for
rollback through Ansible. Once the replacement accepts mutations, its current
state must be transferred back before any rollback; the frozen source snapshot
is no longer authoritative.
