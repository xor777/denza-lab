# Relay Server Ansible

Ansible configuration for the Car ADB Gateway VPS.

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

After configuration, create a single-use invite code. Its default lifetime is
60 minutes:

```bash
ssh adbgw.ru sudo cag-admin invite
```

Enrollment, pairing, and grant state is stored atomically in schema v2 at
`/opt/cag/state/state.json`. Read-only authorization uses a shared lock; changes
use an exclusive lock. `cag-authkeys` has ACL read access but is not in the
writable state group. OpenSSH per-source penalties and fail2ban (`5` attempts in
`10m`, ban for `15m`) handle source throttling.

## Operator field guide

Routine lifecycle work is automatic. An enabled vehicle renews its 14-day lease
daily, and `cag-gc.timer` removes expired registrations and unreferenced client
keys hourly. A disabled vehicle is also forgotten after 14 days. Runtime devices,
codes, and grants never belong in Ansible variables.

```bash
# one-time enrollment code
ssh adbgw.ru sudo cag-admin invite

# inspect current state
ssh adbgw.ru sudo cag-admin list
ssh adbgw.ru sudo cag-admin status DEVICE_ID
ssh adbgw.ru sudo cag-admin doctor

# emergency revoke; active device/client SSH sessions are recycled automatically
ssh adbgw.ru sudo cag-admin remove DEVICE_ID
```

The hourly timer handles routine cleanup. Rotate keys for re-enrollment,
computer replacement, an incident, or a planned relay host-key change. Relay
host-key rotation is additive: ship the new pin to Android and the CLI before
switching the server key.

## Provision a New VPS

```bash
.venv-ansible/bin/ansible-playbook -u root --ask-pass playbooks/bootstrap.yml
ssh dmitry@95.179.132.238 'sudo -n true'
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
A  @  95.179.132.238
```

The relay currently uses only the IPv4 record above. Port 443 carries SSH. The
verification playbook checks both DNS resolution and the pinned Ed25519 host-key
fingerprint.
