#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root on Ubuntu 24.04." >&2
  exit 1
fi

public_key_file="${1:-}"
ssh_port="${SSH_PORT:-22}"
current_ssh_port="${SSH_CONNECTION##* }"
deploy_user="codeclass-deploy"

if [[ ! -f "${public_key_file}" ]]; then
  echo "Usage: $0 /root/codeclass-deploy.pub" >&2
  exit 1
fi
if ! grep -Eq '^(ssh-ed25519|sk-ssh-ed25519@openssh.com) [A-Za-z0-9+/=]+' "${public_key_file}"; then
  echo "Only a valid Ed25519 public SSH key is accepted." >&2
  exit 1
fi
if [[ ! "${ssh_port}" =~ ^[0-9]+$ ]] || (( ssh_port < 1 || ssh_port > 65535 )); then
  echo "SSH_PORT must be an integer from 1 to 65535." >&2
  exit 1
fi
if [[ ! "${current_ssh_port}" =~ ^[0-9]+$ ]]; then
  current_ssh_port=22
fi

# shellcheck disable=SC1091
source /etc/os-release
if [[ "${ID:-}" != "ubuntu" || "${VERSION_ID:-}" != "24.04" ]]; then
  echo "This provisioning script supports Ubuntu 24.04 only." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y ca-certificates curl fail2ban restic ufw unattended-upgrades

install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: ${VERSION_CODENAME}
Components: stable
Signed-By: /etc/apt/keyrings/docker.asc
EOF
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

if ! id "${deploy_user}" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "${deploy_user}"
fi
usermod -aG docker,sudo "${deploy_user}"
install -d -m 0700 -o "${deploy_user}" -g "${deploy_user}" "/home/${deploy_user}/.ssh"
install -m 0600 -o "${deploy_user}" -g "${deploy_user}" \
  "${public_key_file}" "/home/${deploy_user}/.ssh/authorized_keys"
printf '%s ALL=(ALL) NOPASSWD: ALL\n' "${deploy_user}" > "/etc/sudoers.d/${deploy_user}"
chmod 0440 "/etc/sudoers.d/${deploy_user}"
visudo -cf "/etc/sudoers.d/${deploy_user}"

if ! swapon --show=NAME --noheadings | grep -qx '/swapfile'; then
  if [[ ! -f /swapfile ]]; then
    fallocate -l 2G /swapfile
    chmod 0600 /swapfile
    mkswap /swapfile
  fi
  swapon /swapfile
fi
grep -q '^/swapfile ' /etc/fstab || printf '/swapfile none swap sw 0 0\n' >> /etc/fstab

ufw default deny incoming
ufw default allow outgoing
ufw allow "${current_ssh_port}/tcp"
ufw allow "${ssh_port}/tcp"
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

install -d -m 0755 -o "${deploy_user}" -g "${deploy_user}" /opt/codeclass
systemctl enable --now docker fail2ban unattended-upgrades

echo "Base server provisioned. Keep this root session open and verify a new SSH session as ${deploy_user}."
echo "Only after that succeeds, run ops/harden-ssh.sh --confirmed-key-login."
