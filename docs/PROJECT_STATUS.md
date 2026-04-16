# Landolisp — Project Status Board

> Live status of the Android Common-Lisp learning app.
> Update this file at the end of every working session.

**Branch:** `claude/android-lisp-learning-app-35r3M`
**Last updated:** 2026-04-16 (Phase 1 complete)

---

## Architecture at a glance

```
+-----------------------+        HTTPS         +--------------------------+
|  Android app (Compose)| <------------------> |  Sandbox API (SBCL+QL)   |
|  - Lessons            |   /v1/sessions/...   |  - Hunchentoot           |
|  - Code editor        |                      |  - Per-session SBCL proc |
|  - REPL UI            |                      |  - firejail / seccomp    |
+-----------------------+                      +--------------------------+
         |                                                |
         | bundled at build time                          | docker image
         v                                                v
   /curriculum/*.json                              ghcr.io/.../landolisp-sandbox
```

See `ARCHITECTURE.md` for the binding spec.

---

## Milestones

| #  | Milestone                                        | Status        | Owner       |
|----|--------------------------------------------------|---------------|-------------|
| M0 | Repo skeleton, architecture + status docs         | done          | main agent  |
| M1 | Android app skeleton compiles in Android Studio   | scaffolded    | Agent A1    |
| M2 | Sandbox API answers `/v1/health` via Docker        | scaffolded    | Agent A2    |
| M3 | Foundational lessons (atoms → functions → data)   | scaffolded    | Agent A3    |
| M4 | Code editor with highlight + parens + completion  | not started   | Agent B1    |
| M5 | Mid curriculum (control flow, macros, CLOS)       | not started   | Agent B2    |
| M6 | Advanced curriculum (ASDF, Quicklisp, libraries)  | not started   | Agent B3    |
| M7 | Server hardening (sandbox, sessions, limits)      | not started   | Agent B4    |
| M8 | Test scaffolds (JUnit + FiveAM + contract tests)  | not started   | Agent B5    |
| M9 | End-to-end smoke: emulator → docker server → eval | not started   | -           |

Status legend: `not started` · `scaffolded` (code present, untested in target env) · `working` (verified in env) · `done`.

---

## Task board

### In flight
*(none — Phase 2 fan-out next)*

### Done (Phase 0 + Phase 1)
- M0 Repo skeleton, ARCHITECTURE.md, API.md, CURRICULUM.md, PROJECT_STATUS.md.
- M1 Android skeleton (47 files): Gradle catalog, Material3 theme, NavHost, Lessons/Lesson/REPL screens, sealed `Section` data model, Retrofit sandbox client, hand-rolled Markdown renderer, `cl-symbols.json` (150+ entries), `CodeEditor` stub awaiting B1, JUnit serialization tests.
- M2 Sandbox server: ASDF system + Hunchentoot acceptor + every `/v1/*` endpoint, in-process eval with `bordeaux-threads:with-timeout`, allow-listed Quicklisp loader, idle-session reaper, FiveAM tests, Dockerfile pre-quickloading deps.
- M3 Foundational curriculum (25 lessons across `fundamentals` / `functions` / `data`), all validate against `schema.json`. `index.json` generated.
- Curriculum tooling: `scripts/build-curriculum-index.sh`, `validate-curriculum.sh`, `sync-curriculum.sh`.
- CI tightened: PR + workflow_dispatch only (no more push-to-claude/** noise), python jsonschema validation, auto-installs Android SDK + regenerates wrapper jar, server job gated on Dockerfile + 25 min timeout.

### Up next (Phase 2, parallel)
- B1 — Compose code editor (highlight, paren matching, completion).
- B2 — Mid curriculum: control flow, macros, CLOS (orders 26–55).
- B3 — Advanced curriculum: conditions, packages, ASDF, Quicklisp libraries (orders 56–90).
- B4 — Server hardening: subprocess pool, firejail/seccomp, rlimits.
- B5 — Tests: expand JUnit + FiveAM + REST contract tests, plus end-to-end harness.

### Phase 3 (after Phase 2 lands)
- Main-thread integration pass: regenerate `curriculum/index.json`, resolve cross-agent TODOs (`TODO(B1)`, `TODO(B4)`), sanity-check Kotlin/Lisp source, run validators.

---

## Known limitations of the build environment

This repo was bootstrapped in an environment **without an Android SDK and without SBCL**.
That means agents are producing source that is **structurally valid but not yet runtime-verified**:

* The Android module needs Android Studio (or `sdkmanager` + `gradle assembleDebug`) to actually compile.
* The sandbox server needs `docker build` then `docker run -p 8080:8080` on a host with Docker.
* End-to-end smoke (M9) cannot run here; it must be done on a developer machine.

The architecture, source, and tests are written so that the first build on a real dev box should succeed without major rework. Track first-build issues here under "Known limitations" so they don't get lost.

---

## Open questions / decisions to revisit

- [ ] Should completion suggestions include Quicklisp library symbols? (Currently: only standard CL + lesson-scoped hints.)
- [ ] Hosting plan for the production sandbox? (Currently: Docker image, deploy target TBD.)
- [ ] Auth on the sandbox API for public deploy? (Currently: none — fine for local/dev only.)

---

## Change log

- 2026-04-16 — M0 complete: foundation, architecture, status board.
- 2026-04-16 — M1 complete: Android Compose skeleton (Agent A1).
- 2026-04-16 — M2 complete: sandbox server scaffolded (Agent A2).
- 2026-04-16 — M3 complete: 25 foundational lessons (Agent A3); `curriculum/index.json` assembled.
- 2026-04-16 — CI scoped to PR-only to silence in-flight scaffolding noise.
