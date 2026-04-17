# Agent notes — sandbox server baseline

Branch: `claude/android-lisp-learning-app-35r3M`. Touched only `/server/`.
Did not commit, did not run Docker/SBCL/tests (none available here).

## Files created

```
server/
  landolisp-sandbox.asd          (linter-rewritten; deps now use ironclad)
  Dockerfile                     SBCL + Quicklisp + saved core
  .dockerignore
  Makefile                       build / run / shell / clean / test
  README.md                      curl smoke test
  SECURITY.md                    threat model + B4 migration plan
  AGENT_NOTES.md                 this file
  src/
    package.lisp                 #:landolisp.sandbox + condition types
    config.lisp                  *config* plist (host/port/timeouts/...)
    json.lisp                    cl-json wrapper, camelCase encoder
    util.lisp                    iso-now, iso-from-universal, make-uuid
    sessions.lisp                session struct, table, reap-loop
    eval.lisp                    eval-in-session (in-process for v1)
    quicklisp.lisp               quickload-in-session, allow-list gate
    files.lisp                   per-session file CRUD with traversal guard
    routes.lisp                  Hunchentoot main-dispatcher + handlers
    server.lisp                  start-server / stop-server / main
  tests/
    landolisp-sandbox-tests.asd  standalone test system
    test-evaluator.lisp          unit tests for eval-in-session
    test-handlers.lisp           drakma-driven HTTP integration tests
    test-smoke.lisp              combined suite (referenced from main asd)
```

## Reconciliation notes

* The .asd was rewritten by the in-repo linter mid-session: deps are now
  `hunchentoot cl-json bordeaux-threads alexandria ironclad local-time
  cl-ppcre uiop`, components are `package config json util sessions eval
  quicklisp files routes server` (note plural `sessions`, no `main.lisp`,
  no `usocket`/`trivial-shell`). Source files were renamed and reshaped to
  match. Code now uses `ironclad` for UUID entropy with a `random` fallback.
* Exports are `start-server`, `stop-server`, `*acceptor*`, `*config*`, plus
  the four condition types — the spec asked for `start`/`stop` but the
  linter chose explicit names; Dockerfile and tests were updated to match.
* `safe-eval` was renamed to `eval-in-session` by the linter; routes.lisp
  and the tests reference the new name.
* Two test systems exist on purpose:
  - `landolisp-sandbox/tests` (inline in main .asd) loads only
    `tests/test-smoke.lisp` and is what `asdf:test-system` triggers.
  - `landolisp-sandbox-tests` (standalone `tests/landolisp-sandbox-tests.asd`)
    loads the spec-required split files (`test-evaluator`, `test-handlers`).
  Both populate the same `landolisp-sandbox.tests:landolisp-sandbox-suite`.

## TODOs left for B4

* Replace `eval-in-session` body with a subprocess RPC; keep the plist
  shape stable so `routes.lisp` stays untouched.
* Wire `firejail`/`seccomp`/`rlimit` per `SECURITY.md`.
* Add `session_crashed` error code path.
* Consider streaming eval output (chunked transfer) for long compiles.
* Tighten the Quicklisp allow-list once the curriculum stabilises.

## B4 status (M7 hardening)

The first three TODOs above are done. See `AGENT_NOTES_B4.md` for the
list of files added/modified, exit codes/signals relied on, and the
remaining integration-pass TODOs (rate limiter, /v1/health metrics,
LANDOLISP_RUNNER_CORE env wiring, allow-list audit). The streaming-eval
and allow-list-tightening items are deferred.

## Things a reviewer should double-check

1. The Dockerfile pre-quickloads many systems eagerly; if any are missing
   from the chosen Quicklisp dist, the image build will fail loud — that's
   intentional but disruptive on a slow mirror.
2. `bordeaux-threads:with-timeout` needs SBCL's interrupt support; on a
   different Lisp this would silently no-op.
3. CORS headers added by the linter are wide-open (`*`). Fine for the
   Android emulator on `10.0.2.2`, but tighten before any public deploy.
4. The fallback random source in `make-uuid` is `cl:random` — fine for
   identifiers, **not** OK as a security primitive. B4 should switch to
   ironclad's CSPRNG unconditionally once the production image guarantees
   it.
5. `find-session` deletes-and-signals on TTL expiry; the reaper also runs
   periodically. Both paths are intentional but produce two different log
   lines for the same logical event.
