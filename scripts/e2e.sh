#!/usr/bin/env bash
#
# scripts/e2e.sh
#
# End-to-end smoke test for the Landolisp sandbox stack:
#   1. docker build  the server image
#   2. docker run    detached on port 8080
#   3. wait for      /v1/health
#   4. curl flow:    create session -> eval (+ 1 2) -> quickload alexandria
#                    -> write a file -> load it -> eval the loaded fn
#   5. docker stop   teardown (always, even on failure)
#
# Each step prints PASS / FAIL on stdout. Exits non-zero on any failure so
# CI can gate merges.

set -u
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

IMAGE="landolisp-sandbox:e2e"
CONTAINER="landolisp-e2e"
PORT="${LANDOLISP_E2E_PORT:-8080}"
BASE="http://127.0.0.1:${PORT}"

# Track step results.
PASSED=0
FAILED=0

step_pass() {
  echo "  PASS  $1"
  PASSED=$((PASSED + 1))
}

step_fail() {
  echo "  FAIL  $1"
  FAILED=$((FAILED + 1))
}

cleanup() {
  echo
  echo "--- cleanup ---"
  if docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    docker logs "$CONTAINER" >/tmp/landolisp-e2e.log 2>&1 || true
    docker stop "$CONTAINER" >/dev/null 2>&1 || true
    docker rm "$CONTAINER" >/dev/null 2>&1 || true
    echo "  server logs at /tmp/landolisp-e2e.log"
  fi
  echo
  echo "--- summary ---"
  echo "  passed: ${PASSED}"
  echo "  failed: ${FAILED}"
  if [ "$FAILED" -gt 0 ]; then
    exit 1
  fi
  exit 0
}
trap cleanup EXIT

echo "--- step 1: docker build ---"
if docker build -t "$IMAGE" "$REPO_ROOT/server" >/tmp/landolisp-e2e-build.log 2>&1; then
  step_pass "docker build $IMAGE"
else
  step_fail "docker build (see /tmp/landolisp-e2e-build.log)"
  cat /tmp/landolisp-e2e-build.log >&2 || true
  exit 1
fi

echo "--- step 2: docker run ---"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
if docker run -d --name "$CONTAINER" -p "${PORT}:8080" "$IMAGE" >/dev/null; then
  step_pass "docker run -d --name $CONTAINER"
else
  step_fail "docker run"
  exit 1
fi

echo "--- step 3: wait for /v1/health ---"
HEALTH_OK=0
for i in $(seq 1 60); do
  if curl -fsS "${BASE}/v1/health" >/tmp/landolisp-e2e-health.json 2>/dev/null; then
    HEALTH_OK=1
    break
  fi
  sleep 2
done
if [ "$HEALTH_OK" = "1" ]; then
  step_pass "/v1/health responded"
else
  step_fail "/v1/health never responded"
  exit 1
fi

echo "--- step 4: full curl flow ---"

# 4a) create session
SESS_JSON=$(curl -fsS -X POST "${BASE}/v1/sessions" 2>/dev/null) || SESS_JSON=""
SID=$(printf '%s' "$SESS_JSON" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')
if [ -n "$SID" ]; then
  step_pass "POST /v1/sessions -> sessionId=$SID"
else
  step_fail "POST /v1/sessions (got: $SESS_JSON)"
  exit 1
fi

# 4b) eval (+ 1 2) -> 3
EVAL_JSON=$(curl -fsS -X POST "${BASE}/v1/sessions/${SID}/eval" \
                 -H "Content-Type: application/json" \
                 -d '{"code":"(+ 1 2)"}' 2>/dev/null) || EVAL_JSON=""
if printf '%s' "$EVAL_JSON" | grep -q '"value":"3"'; then
  step_pass "eval (+ 1 2) -> 3"
else
  step_fail "eval (+ 1 2) (got: $EVAL_JSON)"
fi

# 4c) quickload alexandria. May legitimately come back loaded:false in test
# images that lack QL; we still consider the round-trip a pass if HTTP 200.
QL_HTTP=$(curl -s -o /tmp/landolisp-e2e-ql.json -w '%{http_code}' \
              -X POST "${BASE}/v1/sessions/${SID}/quickload" \
              -H "Content-Type: application/json" \
              -d '{"system":"alexandria"}')
if [ "$QL_HTTP" = "200" ]; then
  step_pass "quickload alexandria (HTTP $QL_HTTP)"
else
  step_fail "quickload alexandria (HTTP $QL_HTTP)"
fi

# 4d) write a file
PUT_HTTP=$(curl -s -o /dev/null -w '%{http_code}' \
                -X PUT "${BASE}/v1/sessions/${SID}/files/greet.lisp" \
                -H "Content-Type: text/plain" \
                --data-binary '(defun greet () :hi)')
if [ "$PUT_HTTP" = "204" ]; then
  step_pass "PUT files/greet.lisp -> 204"
else
  step_fail "PUT files/greet.lisp (HTTP $PUT_HTTP)"
fi

# 4e) load file
LOAD_JSON=$(curl -fsS -X POST "${BASE}/v1/sessions/${SID}/eval" \
                 -H "Content-Type: application/json" \
                 -d '{"code":"(load \"greet.lisp\")"}' 2>/dev/null) || LOAD_JSON=""
if printf '%s' "$LOAD_JSON" | grep -q '"condition":null\|"condition":{}'; then
  step_pass "eval (load greet.lisp)"
else
  # Some Lisp images print the load result rather than null condition; accept value!=null.
  if printf '%s' "$LOAD_JSON" | grep -q '"value":'; then
    step_pass "eval (load greet.lisp) [value present]"
  else
    step_fail "eval (load greet.lisp) (got: $LOAD_JSON)"
  fi
fi

# 4f) eval the loaded function
GREET_JSON=$(curl -fsS -X POST "${BASE}/v1/sessions/${SID}/eval" \
                  -H "Content-Type: application/json" \
                  -d '{"code":"(greet)"}' 2>/dev/null) || GREET_JSON=""
if printf '%s' "$GREET_JSON" | grep -q '"value":":HI"'; then
  step_pass "eval (greet) -> :HI"
else
  step_fail "eval (greet) (got: $GREET_JSON)"
fi

# trap performs cleanup + exits.
