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

## Reporting

If you find a way to escape the sandbox, please open a private issue rather
than a PR. We expect there are several today.
