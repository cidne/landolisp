# Sandbox Security — v1 baseline

This document captures the **known weaknesses** of the v1 sandbox so that no
one is surprised. Agent B4 will replace the in-process baseline described
below with a hardened implementation; until then, do not expose this server
to anything you do not trust.

## Threat model

* **Trust boundary:** the server is reached over HTTP from the Android app.
  We assume the network is hostile and the requesting client is untrusted.
* **Asset:** the host running the server (CPU, memory, file system, network)
  and any neighbouring sessions.
* **Adversary capabilities:** can submit arbitrary Common Lisp source
  through `/v1/sessions/{id}/eval` and arbitrary writes through
  `/v1/sessions/{id}/files/{path}`.
* **Out of scope for v1:** authentication, multi-tenant isolation against a
  motivated attacker, denial of service from CPU pressure across sessions.

## What the v1 baseline gives you

* All eval happens **in-process** inside one SBCL Hunchentoot worker. There
  is no privilege drop, no namespace, and no CPU/RSS rlimit applied to the
  user's code.
* Per-call wall-clock timeout via `bordeaux-threads:with-timeout`
  (default 10 s, hard 30 s — see `config.lisp`). A determined attacker can
  defeat this with code that disables interrupts or saturates the heap.
* Per-session file CRUD is scoped to `/var/sandbox/<session-id>/`. Path
  traversal (`..`, absolute paths, NUL, backslash) is rejected at the
  `safe-relative-path-p` gate in `files.lisp`.
* Quicklisp loads are gated by the `:allowed-systems` whitelist in
  `config.lisp`. Anything else is refused with HTTP 403.
* Sessions idle for more than 30 minutes are reaped by a background thread
  (`reap-loop` in `sessions.lisp`), which best-effort deletes their scratch
  directories.

## What the v1 baseline does **not** give you

* **No process isolation.** User code shares the heap with the HTTP server.
  `(sb-ext:quit)` will kill the whole instance. So will most `unwind-protect`
  abuses around `sb-thread:terminate-thread`.
* **No memory or CPU rlimit.** `(make-array (expt 10 9))` will OOM the host.
* **No syscall filter.** User code can `sb-bsd-sockets:socket-connect`
  anywhere the host can reach.
* **No filesystem confinement** beyond the path whitelist — `with-open-file`
  to absolute paths is uninhibited because user code calls it directly.
* **No fairness across sessions.** A long compile in one session blocks the
  GVL of the worker thread.

## Migration path (B4)

The struct in `sessions.lisp` already carries a `lisp-process` slot reserved
for the per-session SBCL child. The intended trajectory:

1. **Subprocess pool.** Replace `eval-in-session` with a request that goes
   over a pipe to a child SBCL launched at session creation. Keep the
   plist-shaped return so `routes.lisp` doesn't change.
2. **`firejail` wrapper** for each child:
   `firejail --net=none --quiet --rlimit-as=536870912 --rlimit-cpu=30 --private`.
   When `firejail` is unavailable (e.g. unprivileged container), fall back
   to a `seccomp` profile baked into the Docker image.
3. **rlimit / cgroup** caps applied to the child PID for memory (512 MiB),
   CPU time (30 s wall, 10 s default), and process count.
4. **Network namespace** (`--net=none`) for everything except an outbound
   path to the configured Quicklisp mirror, mediated by a sidecar proxy.
5. **Crash recovery.** The supervisor restarts dead children, but the next
   eval against a dead session returns `session_crashed` rather than
   transparently rebooting (clients lose their bindings).

## Hardening (M7) — B4 implementation notes

This section describes what landed with the M7 hardening pass. When in
doubt, the code in `src/subprocess.lisp`, `src/runner.lisp`, and the
`Dockerfile` are the source of truth; this document is the narrative.

### What changed vs the v1 baseline

* **One SBCL child per session.** `eval-in-session` no longer calls
  `cl:eval` in the Hunchentoot worker. It serialises `(:eval CODE)` into
  a framed S-expression and ships it to a supervised subprocess owned by
  the session.
* **Dedicated runner core.** The child loads
  `/opt/sandbox/landolisp-runner.core`, built by a Dockerfile stage from
  `src/runner.lisp`. The runner core does NOT contain Hunchentoot, the
  session table, the JSON encoder, or the file-CRUD code — just the
  framing loop, an eval capture wrapper, and Quicklisp.
* **Parent never evals user input.** The parent's core continues to run
  `landolisp-sandbox`; it never calls `cl:eval`, `compile`, or
  `read-from-string` on bytes sourced from the HTTP body.
* **Crash recovery.** When the child dies (SIGKILL, OOM, framing
  desync), the next `eval-in-session` call transparently spawns a fresh
  runner. The prior crash is surfaced as `:condition
  {:type "session_crashed" ...}` so clients know their bindings were
  reset.
* **Timeouts are defence-in-depth.** The parent still enforces a wall
  clock (`bordeaux-threads:with-timeout` around `read-frame`), AND the
  runner itself wraps the user form in its own
  `bordeaux-threads:with-timeout` with `*runner-hard-timeout-seconds*`
  (30 s). On parent timeout we SIGKILL the child, mark the session
  "needs-restart", and return `EVAL-TIMEOUT`.
* **Capacity cap is HTTP 503.** `POST /v1/sessions` returns
  `{"error":"capacity", ...}` with status 503 when the in-memory table
  has `*config-max-sessions*` (default 32) live sessions.
* **Quicklisp load is inside the child.** The parent only does
  allow-list enforcement (`system-allowed-p`); the actual
  `ql:quickload` runs inside the runner via `(:quickload "name")`. The
  runner's QL state persists across evals within a session.

### firejail flags (exact)

We wrap the child with firejail when the binary is present. The argv is
assembled in `src/subprocess.lisp::%build-argv` and is identical to:

```
firejail --quiet \
         --net=none \
         --rlimit-as=536870912 \
         --rlimit-cpu=30 \
         --rlimit-fsize=10485760 \
         --private \
         --seccomp \
         --caps.drop=all \
         --nonewprivs \
         --noroot \
         --shell=none \
         -- \
         sbcl --non-interactive --no-sysinit --no-userinit \
              --disable-debugger --core /opt/sandbox/landolisp-runner.core
```

Flag rationale:

* `--net=none` — private network namespace with only a loopback dead
  interface. User code cannot reach 1.1.1.1, the Quicklisp mirror, or
  sibling sessions.
* `--rlimit-as=536870912` — 512 MiB address space cap. SBCL's
  dynamic-space-size is sized below this.
* `--rlimit-cpu=30` — 30 CPU-seconds per process; matches the hard
  timeout in `docs/ARCHITECTURE.md`.
* `--rlimit-fsize=10485760` — 10 MiB per file. Stops user code from
  filling the disk with one giant write.
* `--private` — private `/home` and `/tmp`. User code cannot see the
  parent's filesystem or sibling sessions' scratch dirs.
* `--seccomp` — firejail's default deny-list (augmented by our JSON
  profile when the deployment uses runc seccomp directly).
* `--caps.drop=all`, `--nonewprivs`, `--noroot`, `--shell=none` — drop
  Linux capabilities, block `setuid`-binary escalation, prevent a
  user-namespace trick, and keep a shell out of the jail.

When firejail is NOT present (local dev on a laptop), `spawn-runner`
logs a warning and falls back to plain `sbcl`. This is only acceptable
for developer smoke tests and is never the configuration we deploy.

### seccomp policy

Shipped at `/server/seccomp/landolisp-runner.json`, copied into the
image at `/opt/sandbox/seccomp/landolisp-runner.json`. The default
action is `SCMP_ACT_ERRNO` (EPERM); the allow-list covers the SBCL
runtime minimum (mmap, mprotect, futex, clone, read/write, openat,
etc.). Socket family syscalls (`socket`, `connect`, `bind`, `listen`,
`accept*`, `sendto`, `sendmsg`, `recvfrom`, `recvmsg`) are explicitly
denied. Kernel-surface syscalls (`init_module`, `kexec_*`, `reboot`,
`ptrace`, `bpf`, `perf_event_open`, `mount`, `umount`, `pivot_root`,
`chroot`, `unshare`, `setns`) are denied.

This file is primarily consumed when a deployment uses docker/runc
seccomp directly (`--security-opt seccomp=...`); when firejail is in
play, `--seccomp` uses firejail's own list and our JSON is informational.

### rlimit caps applied

| Limit       | Value        | Enforced by      | Rationale                  |
|-------------|--------------|------------------|----------------------------|
| AS (mem)    | 512 MiB      | firejail rlimit  | Bounds RSS + swap.         |
| CPU         | 30 s         | firejail rlimit  | Matches hard timeout.      |
| FSIZE       | 10 MiB       | firejail rlimit  | Per-file write cap.        |
| Wall clock  | 10 s default | parent + child   | User's eval budget.        |
| Max sessions| 32           | parent (config)  | Concurrent-runner cap.     |

### Child lifecycle

* **Spawn**: lazy. `make-session` only allocates a struct; the first
  `eval-in-session` calls `ensure-runner`, which spawns via
  `uiop:launch-program` and waits for the child's `(:hello "runner" ...)`
  readiness frame (10 s handshake budget).
* **Reap**: `delete-session` and the 30-minute idle reaper both call
  `kill-runner`: (a) send `(:shutdown)` frame, (b) `SIGTERM`, (c) wait
  2 s, (d) `SIGKILL` if still alive, (e) close streams, (f)
  `uiop:wait-process` so no zombie.
* **tini**: PID 1 in the container is `tini`, which reaps any children
  SBCL itself forgets about. `STOPSIGNAL SIGINT` so `docker stop`
  flows through `hunchentoot:stop` rather than cutting everything at
  SIGTERM.

### Residual risks

1. **Rapid session churn DoS.** A client that POSTs `/v1/sessions` and
   `DELETE`s in a tight loop will force us to spawn-and-reap an SBCL
   child every cycle; SBCL startup is ~200 ms. The `capacity` cap
   bounds concurrent sessions but not churn rate. TODO: per-IP rate
   limit at the acceptor layer.
2. **Memory pressure across sessions.** 32 × 512 MiB = 16 GiB worst
   case. The host must be provisioned for this; we do not enforce a
   total-memory cap in the parent. Operators should run under cgroups.
3. **No isolation fallback on non-Linux hosts.** The fallback to plain
   sbcl has NO isolation. The warning log is the only guardrail; a
   developer who copy-pastes the server into prod without Docker will
   silently run v1-equivalent security.
4. **Child-to-child information disclosure via shared pages.**
   `--private` isolates filesystem but kernel page cache (and any
   shared cores purified by `save-lisp-and-die`) can, in theory,
   leak state across sessions. The runner core does not contain any
   secrets so this is mostly academic.
5. **Parent compromise is game over.** We do not jail the parent; only
   its children. A vulnerability in Hunchentoot, cl-json, or our
   routing code would defeat all of this.
6. **Quicklisp allow-list is broad.** `cffi`, `drakma`, and similar
   systems load substantial code surface. TODO: audit the list.

### Verifying isolation locally

Inside the Docker image (`make build && make run`):

```sh
# Memory-cap test: allocate ~700 MB, expect :condition with storage error
SID=$(curl -s -X POST http://localhost:8080/v1/sessions | jq -r .sessionId)
curl -s -X POST http://localhost:8080/v1/sessions/$SID/eval \
  -H 'content-type: application/json' \
  -d '{"code":"(make-array 734003200 :element-type (quote character))"}' | jq
# Expected: condition.type is "session_crashed" or a STORAGE-CONDITION variant.

# Network-isolation test: try to connect to 1.1.1.1, expect failure
curl -s -X POST http://localhost:8080/v1/sessions/$SID/eval \
  -H 'content-type: application/json' \
  -d '{"code":"(handler-case (let ((s (sb-bsd-sockets:inet-socket-class)))
                                  (declare (ignore s)) :ok)
                                (error (c) (princ-to-string c)))"}' | jq
# Expected: .value is an error string OR .condition is populated.
# .value MUST NOT be ":OK" with a live connection.

# Timeout test: infinite loop should be killed within 12 seconds
time curl -s -X POST http://localhost:8080/v1/sessions/$SID/eval \
  -H 'content-type: application/json' \
  -d '{"code":"(loop)"}' | jq
# Expected: HTTP 408, .condition.type == "EVAL-TIMEOUT", real-time < 12s.
```

## Reporting

If you find a way to escape the sandbox, please open a private issue rather
than a PR. We expect there are several today.
