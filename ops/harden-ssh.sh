#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root after verifying key login for repethelper-deploy." >&2
  exit 1
fi
if [[ "${1:-}" != "--confirmed-key-login" ]]; then
  echo "First verify a separate SSH login, then run: $0 --confirmed-key-login" >&2
  exit 1
fi

ssh_port="${SSH_PORT:-22}"
authorized_keys="/home/repethelper-deploy/.ssh/authorized_keys"

if [[ ! "${ssh_port}" =~ ^[0-9]+$ ]] || (( ssh_port < 1 || ssh_port > 65535 )); then
  echo "SSH_PORT must be an integer from 1 to 65535." >&2
  exit 1
fi
if [[ ! -s "${authorized_keys}" ]]; then
  echo "The repethelper-deploy authorized_keys file is missing or empty." >&2
  exit 1
fi

install -d -m 0755 /etc/ssh/sshd_config.d
rm -f /etc/ssh/sshd_config.d/99-repethelper.conf
cat > /etc/ssh/sshd_config.d/01-repethelper.conf <<EOF
Port ${ssh_port}
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF

sshd -t
ufw allow "${ssh_port}/tcp"
systemctl reload ssh

echo "SSH hardening enabled on port ${ssh_port}: password and direct root login are disabled."
