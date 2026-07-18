# Server Ansible

Ansible-конфигурация VPS для Car ADB Gateway.

## Локальная установка

```bash
cd ops/ansible
python3 -m venv .venv-ansible
.venv-ansible/bin/pip install -r requirements.txt
.venv-ansible/bin/ansible-galaxy collection install -r requirements.yml
```

## Настройка сервера

```bash
.venv-ansible/bin/ansible-playbook playbooks/relay-host.yml
.venv-ansible/bin/ansible-playbook playbooks/verify-relay-host.yml
```

Повторный запуск безопасен и приводит сервер к конфигурации из репозитория.

## Развёртывание нового VPS

```bash
.venv-ansible/bin/ansible-playbook -u root --ask-pass playbooks/bootstrap.yml
ssh dmitry@95.179.132.238 'sudo -n true'
.venv-ansible/bin/ansible-playbook \
  -e confirm_ssh_lockdown=true \
  playbooks/lockdown.yml
```

Если после обновлений требуется перезагрузка:

```bash
.venv-ansible/bin/ansible-playbook -u root --ask-pass \
  -e confirm_reboot=true \
  playbooks/reboot.yml
```

Пароли и приватные ключи нельзя хранить в inventory или переменных Ansible.

## DNS

```text
A  @  95.179.132.238  TTL 300
```

Запись `AAAA` пока не нужна. Порт 443 используется для SSH, а не HTTPS.
