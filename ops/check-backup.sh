#!/usr/bin/env bash

set -Eeuo pipefail
# shellcheck source=ops/lib.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

require_command restic
load_environment
require_variable RESTIC_REPOSITORY
require_variable RESTIC_PASSWORD
require_variable AWS_ACCESS_KEY_ID
require_variable AWS_SECRET_ACCESS_KEY
require_secret_variable RESTIC_PASSWORD 24
require_secret_variable AWS_SECRET_ACCESS_KEY 16

restic check --read-data-subset=5%
