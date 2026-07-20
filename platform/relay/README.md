# Relay control plane

This directory owns the server-side state machine for Car ADB Gateway. Ansible
installs these files under `/opt/cag`; they are not run from the checkout on the
relay host.

The state engine keeps one schema-v2 JSON document. Authorization reads use a
shared `flock`; mutations use an exclusive lock, validate all invariants, and
replace the document atomically with file and directory fsync. Short-lived codes
authenticate the two password-only SSH entry points:

- `cag-enroll` registers a device tunnel key and its inner SSH host key;
- `cag-pair` stages a Mac public key without revoking the current grant.

The device authenticates as `cag-control` to commit a staged replacement. Until
that commit succeeds, both the active and pending keys may reach the device port;
after commit only the new key is emitted by the dynamic authorized-keys command.
Expired pending records are removed without changing the active grant. Enabled
vehicles renew a 14-day lease daily. Hourly GC cascades expired vehicles through
grants, pairing state, codes, and unreferenced clients; freed relay ports are
reused. `cag-authkeys` has read-only ACL access and cannot mutate state.

Run the tests from the repository root:

```bash
PYTHONPATH=platform python3 -m unittest -v relay.tests.test_cag_state
```

Provisioning and verification are defined in `ops/ansible`. Do not copy these
scripts to the server manually, because the SSH, PAM, ownership, and state-file
settings are one security boundary.
