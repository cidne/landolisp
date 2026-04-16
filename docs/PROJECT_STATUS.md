# Landolisp — Project Status Board

> Live status of the Android Common-Lisp learning app.
> Update this file at the end of every working session.

**Branch:** `claude/android-lisp-learning-app-35r3M`
**Last updated:** 2026-04-16

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
| M3 | Foundational lessons (atoms → functions)          | scaffolded    | Agent A3    |
| M4 | Code editor with highlight + parens + completion  | scaffolded    | Agent B1    |
| M5 | Mid curriculum (macros, CLOS, conditions)         | scaffolded    | Agent B2    |
| M6 | Advanced curriculum (ASDF, Quicklisp, libraries)  | scaffolded    | Agent B3    |
| M7 | Server hardening (sandbox, sessions, limits)      | scaffolded    | Agent B4    |
| M8 | Test scaffolds (JUnit + FiveAM + contract tests)  | scaffolded    | Agent B5    |
| M9 | End-to-end smoke: emulator → docker server → eval | not started   | -           |

Status legend: `not started` · `scaffolded` (code present, untested in target env) · `working` (verified in env) · `done`.

---

## Task board

### In flight (Phase 1, parallel)
- A1 — Android skeleton (`/android/`)
- A2 — Sandbox server (`/server/`)
- A3 — Foundational curriculum (`/curriculum/001-…025-*.json`)

### Done
- M0 Repo skeleton, ARCHITECTURE.md, API.md, CURRICULUM.md, PROJECT_STATUS.md.
- Curriculum tooling: `scripts/build-curriculum-index.sh`, `scripts/validate-curriculum.sh`, `scripts/sync-curriculum.sh`.
- CI scaffolding: `.github/workflows/ci.yml` (curriculum schema check + Android build + server docker smoke).

### Up next (Phase 2, kicks off when Phase 1 lands)
- B1 — Compose code editor (highlight, paren matching, completion).
- B2 — Mid curriculum: control flow, macros, CLOS.
- B3 — Advanced curriculum: conditions, packages, ASDF, Quicklisp libraries.
- B4 — Server hardening: subprocess pool, firejail/seccomp, rlimits.
- B5 — Tests: JUnit + FiveAM + REST contract tests.

### Phase 3
- Main-thread integration pass: assemble `curriculum/index.json`, resolve cross-agent TODOs, sanity-check Kotlin/Lisp source, run validators.

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
