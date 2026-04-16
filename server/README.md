# Landolisp Sandbox Server

HTTP API in front of supervised SBCL processes. The Android app POSTs user
code here and renders the result. See `docs/API.md` for the wire contract
and `docs/ARCHITECTURE.md` for the broader design.

## Quick start

```sh
make build
make run     # listens on http://localhost:8080
```

`make shell` drops you into an interactive container shell using the same
image (handy for poking at Quicklisp).

## Smoke test

In another terminal, with the container running:

```sh
# 1. Health
curl -s http://localhost:8080/v1/health | jq

# 2. Create a session, capture the id
SID=$(curl -s -X POST http://localhost:8080/v1/sessions | jq -r .sessionId)
echo "session: $SID"

# 3. Evaluate (+ 1 2)
curl -s -X POST http://localhost:8080/v1/sessions/$SID/eval \
  -H 'content-type: application/json' \
  -d '{"code":"(+ 1 2)"}' | jq

# 4. Quickload alexandria
curl -s -X POST http://localhost:8080/v1/sessions/$SID/quickload \
  -H 'content-type: application/json' \
  -d '{"system":"alexandria"}' | jq

# 5. Tear it down
curl -s -X DELETE http://localhost:8080/v1/sessions/$SID -i
```

Expected `eval` response shape:

```json
{ "stdout": "", "stderr": "", "value": "3", "elapsedMs": 4, "condition": null }
```

## Layout

```
landolisp-sandbox.asd
src/
  package.lisp    conditions + exports
  config.lisp     runtime knobs (port, timeouts, allow-list, ...)
  json.lisp       cl-json wrapper, camelCase encoder
  util.lisp       ISO timestamps, UUID, error helpers
  sessions.lisp   in-memory session table + scratch dirs + reap loop
  eval.lisp       eval-in-session (in-process for v1; subprocess for B4)
  quicklisp.lisp  quickload-in-session, allow-list gate
  files.lisp      per-session file CRUD with traversal guard
  routes.lisp     Hunchentoot dispatcher + handlers
  server.lisp     start-server / stop-server / main
tests/            FiveAM unit + integration tests
Dockerfile        SBCL + Quicklisp + saved core
Makefile          build / run / shell / clean / test
SECURITY.md       threat model + B4 migration plan
```

## Running tests outside Docker

If you have SBCL + Quicklisp on the host:

```sh
sbcl --non-interactive \
     --eval "(asdf:load-asd \"$PWD/landolisp-sandbox.asd\")" \
     --eval "(asdf:load-asd \"$PWD/tests/landolisp-sandbox-tests.asd\")" \
     --eval "(ql:quickload :landolisp-sandbox-tests)" \
     --eval "(uiop:quit (if (fiveam:run-all-tests) 0 1))"
```

## Security

This baseline runs user code in-process. Read `SECURITY.md` before
exposing the server to anything you do not control.
