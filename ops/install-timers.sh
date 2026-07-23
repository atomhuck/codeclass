#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script with sudo after cloning the project to /opt/codeclass." >&2
  exit 1
fi

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${project_dir}" != "/opt/codeclass" ]]; then
  echo "Timers expect the project at /opt/codeclass, current path is ${project_dir}." >&2
  exit 1
fi

install -m 0644 "${project_dir}/ops/systemd/"*.service "${project_dir}/ops/systemd/"*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now codeclass-backup.timer codeclass-backup-check.timer
systemctl list-timers 'codeclass-backup*'
