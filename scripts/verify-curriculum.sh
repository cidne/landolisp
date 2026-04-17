#!/usr/bin/env bash
#
# scripts/verify-curriculum.sh
#
# Local-dev curriculum smoke. Spins up the docker server, then walks every
# lesson JSON in /curriculum/, running every `example` snippet through the
# live sandbox via curl. Prints a green dot per lesson, an "x" + diagnostic
# per failing example. Exits non-zero if any example fails.
#
# Requires: docker, curl, jq.

set -u
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CURRICULUM_DIR="${REPO_ROOT}/curriculum"

IMAGE="landolisp-sandbox:verify-curriculum"
CONTAINER="landolisp-verify-curriculum"
PORT="${LANDOLISP_VERIFY_PORT:-8088}"
BASE="http://127.0.0.1:${PORT}"

GREEN=""; RED=""; RESET=""
if [ -t 1 ]; then
  GREEN=$'\033[32m'
  RED=$'\033[31m'
  RESET=$'\033[0m'
fi

FAILED=0
TOTAL_EXAMPLES=0
FAILED_EXAMPLES=()

cleanup() {
  echo
  if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    docker stop "$CONTAINER" >/dev/null 2>&1 || true
    docker rm "$CONTAINER" >/dev/null 2>&1 || true
  fi
  if [ "$FAILED" -gt 0 ]; then
    echo "verify-curriculum: ${FAILED} lesson(s) had failing examples (${#FAILED_EXAMPLES[@]} total)"
    for line in "${FAILED_EXAMPLES[@]}"; do
      echo "  ${RED}x${RESET} $line"
    done
    exit 1
  fi
  echo "verify-curriculum: all ${TOTAL_EXAMPLES} examples passed"
  exit 0
}
trap cleanup EXIT

command -v jq >/dev/null 2>&1 || { echo "jq is required (install jq)" >&2; exit 2; }
command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 2; }
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 2; }

echo "verify-curriculum: building image $IMAGE"
docker build -q -t "$IMAGE" "$REPO_ROOT/server" >/dev/null

echo "verify-curriculum: starting $CONTAINER on port $PORT"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" -p "${PORT}:8080" "$IMAGE" >/dev/null

# wait for health
HEALTH_OK=0
for i in $(seq 1 60); do
  if curl -fsS "${BASE}/v1/health" >/dev/null 2>&1; then
    HEALTH_OK=1
    break
  fi
  sleep 2
done
if [ "$HEALTH_OK" != "1" ]; then
  echo "verify-curriculum: server never became healthy" >&2
  exit 1
fi

# Walk every lesson file.
shopt -s nullglob
for lesson_file in "$CURRICULUM_DIR"/[0-9][0-9][0-9]-*.json; do
  lesson_id=$(basename "$lesson_file" .json)
  # Fresh session per lesson.
  sess_json=$(curl -fsS -X POST "${BASE}/v1/sessions" 2>/dev/null) || sess_json=""
  sid=$(printf '%s' "$sess_json" | jq -r '.sessionId // empty')
  if [ -z "$sid" ]; then
    echo "${RED}x${RESET} ${lesson_id} (could not create session)"
    FAILED=$((FAILED + 1))
    FAILED_EXAMPLES+=("${lesson_id}: session create failed")
    continue
  fi

  # Iterate examples with jq. -c keeps each on one line for read.
  example_count=$(jq '[.sections[] | select(.kind=="example")] | length' "$lesson_file")
  lesson_failed=0
  if [ "$example_count" -gt 0 ]; then
    while IFS= read -r example; do
      TOTAL_EXAMPLES=$((TOTAL_EXAMPLES + 1))
      code=$(printf '%s' "$example" | jq -r '.code')
      expected=$(printf '%s' "$example" | jq -r '.expected // empty')
      if [ -z "$expected" ]; then
        continue
      fi
      payload=$(jq -n --arg c "$code" '{code:$c}')
      eval_resp=$(curl -fsS -X POST "${BASE}/v1/sessions/${sid}/eval" \
                       -H 'Content-Type: application/json' \
                       --data "$payload" 2>/dev/null) || eval_resp=""
      got=$(printf '%s' "$eval_resp" | jq -r '.value // empty')
      if [ "$got" != "$expected" ]; then
        lesson_failed=1
        FAILED=$((FAILED + 1))
        FAILED_EXAMPLES+=("${lesson_id}: expected '${expected}' got '${got}' for ${code}")
      fi
    done < <(jq -c '.sections[] | select(.kind=="example")' "$lesson_file")
  fi

  if [ "$lesson_failed" = "0" ]; then
    printf '%b' "${GREEN}.${RESET}"
  else
    printf '%b' "${RED}x${RESET}"
  fi
done
echo

# trap handles exit + summary.
