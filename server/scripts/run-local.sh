#!/usr/bin/env bash
# Build the sandbox image and run it locally on :8080.
#
# This script is not executable in the repo by default; after cloning, run:
#   chmod +x server/scripts/run-local.sh
# then ./server/scripts/run-local.sh
#
# Must be run from the server/ directory (or pass a different context).

set -euo pipefail

cd "$(dirname "$0")/.."

docker build -t landolisp-sandbox . && \
  docker run --rm -p 8080:8080 landolisp-sandbox
