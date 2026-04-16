# Landolisp Sandbox Server

HTTP API in front of supervised SBCL processes. The Android app POSTs user
code here and renders the result. See `docs/API.md` for the wire contract
and `docs/ARCHITECTURE.md` for the broader design.

## Quick start (Docker)

From the `server/` directory:

```sh
./scripts/run-local.sh        # `chmod +x scripts/run-local.sh` once after clone
# or, equivalently:
docker build -t landolisp-sandbox .
docker run --rm -p 8080:8080 landolisp-sandbox
```

`make build`, `make run`, and `make shell` are convenience wrappers around
the same image.

## Quick start (no Docker)

If you have SBCL + Quicklisp on the host:

```sh
sbcl --non-interactive \
     --eval "(asdf:load-asd \"$PWD/landolisp-sandbox.asd\")" \
     --eval "(ql:quickload :landolisp-sandbox)" \
     --eval "(landolisp.sandbox:start-server :port 8080)" \
     --eval "(loop (sleep 60))"
```

The default session-root is `/var/sandbox/`; on a developer laptop you will
want to point it elsewhere first:

```cl
(setf (getf landolisp.sandbox::*config* :session-root) "/tmp/landolisp/")
```

## API smoke test

In another terminal, with the server running:

```sh
# 1. Health
curl -s http://localhost:8080/v1/health | jq
# -> { "status":"ok", "sbclVersion":"2.4.0", "qlDist":"quicklisp", ... }

# 2. Create a session, capture the id
SID=$(curl -s -X POST http://localhost:8080/v1/sessions | jq -r .sessionId)
echo "session: $SID"
# -> { "sessionId":"…", "expiresAt":"2026-04-16T14:00:00Z" }

# 3. Evaluate (+ 1 2)
curl -s -X POST http://localhost:8080/v1/sessions/$SID/eval \
  -H 'content-type: application/json' \
  -d '{"code":"(+ 1 2)"}' | jq
# -> { "stdout":"", "stderr":"", "value":"3", "elapsedMs":4, "condition":null }

# 4. Quickload alexandria
curl -s -X POST http://localhost:8080/v1/sessions/$SID/quickload \
  -H 'content-type: application/json' \
  -d '{"system":"alexandria"}' | jq
# -> { "loaded":true, "system":"alexandria", "log":"…", "condition":null }

# 5. PUT a file, list, GET it back, DELETE it
curl -s -X PUT http://localhost:8080/v1/sessions/$SID/files/hello.lisp \
  --data-binary $'(defun greet () :hi)\n' -i | head -1
curl -s http://localhost:8080/v1/sessions/$SID/files | jq
curl -s http://localhost:8080/v1/sessions/$SID/files/hello.lisp
curl -s -X DELETE http://localhost:8080/v1/sessions/$SID/files/hello.lisp -i | head -1

# 6. CORS preflight
curl -s -X OPTIONS http://localhost:8080/v1/sessions -i | head -5

# 7. Tear it down
curl -s -X DELETE http://localhost:8080/v1/sessions/$SID -i | head -1
```

## Layout

```
landolisp-sandbox.asd               ASDF system definition
src/
  package.lisp                      package + condition classes
  config.lisp                       runtime knobs (port, timeouts, allow-list)
  json.lisp                         cl-json wrapper, camelCase output
  util.lisp                         UUID, ISO timestamps, error helpers
  sessions.lisp                     in-memory session table + reaper
  eval.lisp                         eval-in-session (in-process v1)
  quicklisp.lisp                    whitelisted ql:quickload wrapper
  files.lisp                        per-session file CRUD
  routes.lisp                       Hunchentoot dispatchers + CORS
  server.lisp                       start-server / stop-server lifecycle
tests/
  test-smoke.lisp                   self-contained FiveAM smoke suite
  test-evaluator.lisp               unit tests for eval-in-session
  test-handlers.lisp                HTTP integration tests via drakma
  landolisp-sandbox-tests.asd       standalone test system
scripts/
  run-local.sh                      docker build && docker run wrapper
Dockerfile                          SBCL + Quicklisp + ENTRYPOINT
Makefile                            build / run / shell / clean / test
```

## Running tests

Inside Docker:

```sh
make test
```

Outside Docker (host SBCL + Quicklisp):

```sh
sbcl --non-interactive \
     --eval "(asdf:load-asd \"$PWD/landolisp-sandbox.asd\")" \
     --eval "(asdf:test-system :landolisp-sandbox)"
```

## What is real vs stubbed (v1)

- Real: every endpoint in `docs/API.md`, CORS preflight, 30 min idle reaper
  thread, per-session scratch dir, file CRUD with traversal rejection,
  Quicklisp whitelist enforcement, JSON error envelope.
- In-process eval: user code runs in the same SBCL image that serves HTTP.
  Output capture is real; the 10 s timeout uses
  `bordeaux-threads:with-timeout`. Memory cap and network isolation are NOT
  enforced at this layer; that is Agent B4's subprocess-isolated runner work.
- The `lisp-process` slot on the session struct is allocated but unused;
  B4 is expected to populate it.

## Security

This baseline runs user code in-process. The Docker image runs as the
unprivileged `sandbox` user with WORKDIR `/var/sandbox`, which limits the
blast radius but does not replace process isolation.
