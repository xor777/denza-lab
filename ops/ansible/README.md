# Gateway host bootstrap

This directory prepares the Ubuntu VPS without installing the relay project.
The workflow intentionally separates safe baseline setup from the SSH lockout-risk step.

## Local setup

```bash
cd ops/ansible
/opt/homebrew/bin/python3.14 -m venv .venv-ansible
.venv-ansible/bin/pip install -r requirements.txt
.venv-ansible/bin/ansible-galaxy collection install -r requirements.yml
brew install sshpass
```

Do not store the root password in inventory or variables.

## First bootstrap

Confirm the VPS host key through the provider console, then run:

```bash
.venv-ansible/bin/ansible-playbook -u root --ask-pass playbooks/bootstrap.yml
```

This installs all available updates (including phased candidates) and baseline tools,
creates `dmitry`, installs the public key,
configures passwordless sudo, UFW, unattended security updates, fail2ban, and safe
SSHD settings. It deliberately keeps root/password SSH available.

If a kernel update requires a reboot, perform it explicitly:

```bash
.venv-ansible/bin/ansible-playbook -u root --ask-pass \
  -e confirm_reboot=true \
  playbooks/reboot.yml
```

## Verify and finish SSH hardening

First verify the key manually in a second terminal:

```bash
ssh dmitry@95.179.132.238
sudo -n true
```

Only after that succeeds, disable remote root and password authentication:

```bash
.venv-ansible/bin/ansible-playbook \
  -e confirm_ssh_lockdown=true \
  playbooks/lockdown.yml
```

The lockdown playbook refuses to run from a root session, validates `sudo -n`, checks
the SSH configuration before reload, reconnects as `dmitry`, and verifies the effective
settings. General verification can be repeated with:

```bash
.venv-ansible/bin/ansible-playbook \
  -e expect_ssh_lockdown=true \
  playbooks/verify.yml
```
