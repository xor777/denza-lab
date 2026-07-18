# Relay control plane

This directory owns the server-side state machine for Car ADB Gateway. Ansible
installs these files under `/opt/cag`; they are not run from the checkout on the
relay host.

The state engine keeps one JSON document under an exclusive `flock` and replaces
it atomically. Short-lived codes authenticate the two password-only SSH entry
points:

- `cag-enroll` registers a device tunnel key and its inner SSH host key;
- `cag-pair` stages a Mac public key without revoking the current grant.

The device authenticates as `cag-control` to commit a staged replacement. Until
that commit succeeds, both the active and pending keys may reach the device port;
after commit only the new key is emitted by the dynamic authorized-keys command.
Expired pending records are removed without changing the active grant.

Run the tests from the repository root:

```bash
PYTHONPATH=platform python3 -m unittest -v relay.tests.test_cag_state
```

Provisioning and verification are defined in `ops/ansible`. Do not copy these
scripts to the server manually, because the SSH, PAM, ownership, and state-file
settings are one security boundary.
