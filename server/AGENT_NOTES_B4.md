# Agent notes — B4 (sandbox hardening, M7)

Successor to A2. Did not run sbcl/Docker/tests (none available in the
working environment). All claims about behaviour are textual review of
the resulting code; the test suite is the runtime check.

Branch: `claude/android-lisp-learning-app-35r3M`. Touched only `/server/`.

## Files added

```
server/
  src/subprocess.lisp                 parent-side runner pool + framing + signals
  src/runner.lisp                     child-side eval loop, packaged as :landolisp.runner
  seccomp/landolisp-runner.json       deny-by-default seccomp profile (runc-style)
  tests/test-subprocess.lisp          frame round-trip / SIGKILL / malformed-input recovery
  tests/test-runner-isolation.lisp    rlimit-as / --net=none / rlimit-fsize (gated on INSIDE_DOCKER)
  AGENT_NOTES_B4.md                   this file
```

## Files modified

```
server/
  Dockerfile                          multi-stage; firejail, tini, builds runner core
  SECURITY.md                         "Hardening (M7)" section appended
  landolisp-sandbox.asd               components: subprocess BEFORE sessions
  src/sessions.lisp                   runner-{process,input-stream,output-stream,pid},
                                      restart-count, lazy ensure-runner, capacity cap
  src/eval.lisp                       delegates via request-runner; same return shape
  src/quicklisp.lisp                  delegates via request-runner; allow-list still parent-side
  src/routes.lisp                     status mappings: capacity (503), session_crashed (502),
                                      runner_handshake_timeout (503), runner_spawn_failed (503)
  tests/landolisp-sandbox-tests.asd   added test-subprocess + test-runner-isolation components
```

## Files NOT touched

* `tests/test-evaluator.lisp` and `tests/test-handlers.lisp` — A2's
  files, owned by B5 going forward. They keep working unmodified
  because the public eval-in-session signature and return shape did not
  change. If they break it's a bug in this refactor, not in the tests.
* `tests/test-smoke.lisp` — same reason. Note: its `eval-infinite-loop`
  test expects `EVAL-TIMEOUT` and now gets it via the parent-side
  timeout path; the assertion text is unchanged.
* Anything outside `/server/`.

## Exit codes / signals relied on

| Signal / code | Used for                                                |
|---------------|---------------------------------------------------------|
| `SIGTERM` (15)| First polite kill in `kill-runner` after `(:shutdown)`. |
| `SIGKILL` (9) | Hard kill 2 s after SIGTERM if pid is still alive.      |
| `SIGINT`      | Set as STOPSIGNAL so `docker stop` reaches Hunchentoot. |
| Runner exit 0 | Clean shutdown (got `(:shutdown)` or stdin EOF).        |
| Runner exit 2 | Framing desync — runner gives up rather than guess.     |

The parent treats EOF on the child's stdout as "session crashed"
regardless of the actual exit code; the exit code only matters for
operators reading `docker logs`.

## Environment variables consumed

* `INSIDE_DOCKER` — when `1` / `true`, the isolation test suite runs
  the firejail-required tests. Otherwise they short-circuit.
* `LANDOLISP_HOME` — used by the no-core fallback path to find
  `src/runner.lisp`. Defaults to `/opt/landolisp-sandbox`.
* `LANDOLISP_RUNNER_CORE` — set in the Dockerfile to the canonical
  core path; not yet read by the code (TODO below).

## TODOs left for the integration pass

1. **`LANDOLISP_RUNNER_CORE` env var.** The Dockerfile sets it but
   `*runner-core-path*` is hardcoded to `/opt/sandbox/landolisp-runner.core`.
   Wire `(or (uiop:getenv "LANDOLISP_RUNNER_CORE") "/opt/sandbox/...")`
   so deployments can override.
2. **Health endpoint metrics.** `/v1/health` should include
   `runnerCount`, `restartTotal`, and `firejailAvailable` so operators
   can spot-check the pool state without docker exec.
3. **Per-IP rate limiter.** See "Rapid session churn DoS" in
   SECURITY.md. Recommend a `cl-cache`-backed token bucket in
   `routes.lisp::handle-create-session`.
4. **Handshake banner asymmetry.** The runner emits `(:hello "runner"
   ...)` on startup; the parent ignores its contents. Consider matching
   on the SBCL version string and refusing mismatches if we ever ship
   prebuilt runner cores from CI.
5. **Quicklisp allow-list audit.** `*default-allowed-systems*` was
   inherited from v1; some entries (`drakma`, `cffi`) deserve a second
   look now that the runner has network isolation. This is policy not
   code.
6. **PID extraction reliability.** `%pid-of` falls back through three
   paths (uiop accessor, sb-ext slot, NIL). On a non-SBCL Lisp the
   slot path is dead code. Acceptable today (we only support SBCL) but
   worth a comment if we ever build a CCL runner.
7. **`with-timeout` on non-SBCL.** Same caveat A2 raised:
   `bordeaux-threads:with-timeout` requires interrupt support. We rely
   on it in two places (parent `request-runner`, child runner main loop)
   so a Lisp without interrupts would silently break the timeout.
8. **Runner stdout/stderr separation.** The runner sends frames over
   `*standard-output*` AND captures user code's `*standard-output*` to
   a string before serialising. If user code does `(force-output)` on
   the real terminal stdout BY ACCIDENT (e.g. by binding `*standard-output*`
   then running an inferior process that writes to fd 1), it will
   corrupt the framing. Today the runner does not run inferior
   processes, but if `(:quickload)` ever spawns one this becomes a
   real bug.

## Things a reviewer should double-check

1. The `:serial t` ordering in the .asd: `subprocess` MUST come before
   `sessions`. If someone reorders these alphabetically, sessions.lisp
   will fail to compile because `runner-alive-p` and friends won't
   exist yet.
2. `runner.lisp` is intentionally NOT a component of the parent system.
   It is loaded only when building the runner core (Dockerfile
   `core-builder` stage) or by the no-core fallback path
   (`subprocess.lisp::spawn-runner`). Keep it that way — the parent
   must not contain `landolisp.runner:main` for symbol-collision and
   attack-surface reasons.
3. The framing protocol (`write-frame` / `read-frame`) is duplicated
   between `subprocess.lisp` and `runner.lisp`. This is on purpose so
   the runner core doesn't pull in the parent's source tree. If the
   format changes, BOTH copies must change in lockstep — the wire
   format `<length>\n<sexp>\n` is the canonical contract.
4. `hard-timeout-seconds` (30) is a parent-side cap on the RPC budget
   we pass to `request-runner`; the runner's own `with-timeout`
   defaults to 30 too. They're not the same source of truth but they
   agree numerically. If you raise one, raise the other.
5. The `firejail` fallback warning is logged to `*error-output*` and
   only on spawn. A long-running process with the message scrolled off
   the tty would have no visible reminder. Consider surfacing
   `:firejail false` in `/v1/health` once TODO #2 is done.
6. `make-uuid` — A2's note about ironclad CSPRNG vs `cl:random` still
   applies. The Docker image guarantees ironclad so we get the strong
   path; the fallback is unchanged from A2.
