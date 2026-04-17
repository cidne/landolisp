#!/usr/bin/env bash
# Generate /curriculum/index.json from every NNN-*.json lesson in /curriculum/.
# The manifest is a flat array sorted by `order`, with one entry per lesson:
#   { "id": "...", "title": "...", "track": "...", "order": N, "estimatedMinutes": N }
#
# This is the file the Android app loads first to render the lesson list.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CURR_DIR="${REPO_ROOT}/curriculum"
OUT="${CURR_DIR}/index.json"

if ! command -v jq >/dev/null 2>&1; then
  echo "error: jq is required to build the curriculum index" >&2
  exit 1
fi

shopt -s nullglob
files=( "${CURR_DIR}"/[0-9][0-9][0-9]-*.json )
if [ ${#files[@]} -eq 0 ]; then
  echo "warning: no lesson files found in ${CURR_DIR}" >&2
  echo "[]" > "${OUT}"
  exit 0
fi

jq -s '
  map({
    id: .id,
    title: .title,
    track: .track,
    order: .order,
    estimatedMinutes: (.estimatedMinutes // 10),
    prerequisites: (.prerequisites // [])
  })
  | sort_by(.order)
' "${files[@]}" > "${OUT}"

echo "wrote ${OUT} (${#files[@]} lessons)"
