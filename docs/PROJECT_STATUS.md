# Landolisp — Project Status Board

> Live status of the Android Common-Lisp learning app.
> Update this file at the end of every working session.

**Branch:** `claude/android-lisp-learning-app-35r3M`
**Last updated:** 2026-04-16 (Phase 3 integration complete)

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
| M4 | Code editor with highlight + parens + completion  | scaffolded    | Agent B1    |
| M5 | Mid curriculum (control flow, macros, CLOS)       | scaffolded    | Agent B2    |
| M6 | Advanced curriculum (ASDF, Quicklisp, libraries)  | scaffolded    | Agent B3    |
| M7 | Server hardening (sandbox, sessions, limits)      | scaffolded    | Agent B4    |
| M8 | Test scaffolds (JUnit + FiveAM + contract tests)  | scaffolded    | Agent B5    |
| M9 | End-to-end smoke: emulator → docker server → eval | not started   | -           |

Status legend: `not started` · `scaffolded` (code present, untested in target env) · `working` (verified in env) · `done`.

---

## Task board

### In flight
*(none — Phase 3 integration complete; awaiting M9 E2E on a real dev box)*

### Done (Phase 0–3, all on `claude/android-lisp-learning-app-35r3M`)
- M0 Repo skeleton, ARCHITECTURE.md, API.md, CURRICULUM.md, PROJECT_STATUS.md.
- M1 Android skeleton (47 files): Gradle catalog, Material3 theme, NavHost, Lessons/Lesson/REPL screens, sealed `Section` data model, Retrofit sandbox client, hand-rolled Markdown renderer, `cl-symbols.json` (150+ entries), `CodeEditor` stub awaiting B1, JUnit serialization tests.
- M2 Sandbox server: ASDF system + Hunchentoot acceptor + every `/v1/*` endpoint, in-process eval with `bordeaux-threads:with-timeout`, allow-listed Quicklisp loader, idle-session reaper, FiveAM tests, Dockerfile pre-quickloading deps.
- M3 Foundational curriculum (25 lessons across `fundamentals` / `functions` / `data`), all validate against `schema.json`. `index.json` generated.
- M4 Compose code editor: tokenizer, paren matcher, rainbow highlighter (keyword + string + comment + paren-by-depth), completion engine over `cl-symbols.json`, auto-balance + skip-over + delete-pair, depth gutter, `EditorBehavior` toggles.
- M5 Mid curriculum: 30 lessons (control flow, macros, CLOS).
- M6 Advanced curriculum: 35 lessons (conditions, packages, ASDF, 13 Quicklisp libraries).
- M7 Server hardening: subprocess runner with stdio framing, firejail flags + seccomp profile, SIGTERM→SIGKILL lifecycle, capacity/handshake/crash error mappings, multi-stage Dockerfile with a saved core.
- M8 Tests: Android Retrofit contract tests, lesson serialization, curriculum integrity, tokenizer snapshot, two instrumented Compose tests; server FiveAM curriculum runner + Drakma-driven API contract test; `scripts/e2e.sh` and `scripts/verify-curriculum.sh`; `e2e.yml` workflow.
- Curriculum tooling: `scripts/build-curriculum-index.sh`, `validate-curriculum.sh`, `sync-curriculum.sh`.
- CI tightened: PR + workflow_dispatch only (no push-to-claude/** noise), python jsonschema validation, auto-installs Android SDK + regenerates wrapper jar, server job gated on Dockerfile + 25 min timeout.
- Phase 3 integration:
  - **Bug fix:** `LessonRepository.readIndex()` was decoding into a `LessonIndex { lessons: [...] }` wrapper, but `index.json` is a flat array per spec. Switched to `ListSerializer(LessonSummary.serializer())` and removed the dead `LessonIndex` data class. The lesson list now actually populates.
  - Wired B1's `CompletionEngine` into both screens via a new `rememberCompletionEngine()` Compose helper. The engine is loaded once on `Dispatchers.IO` and shared.
  - Implemented the `TODO(B4)` exercise submit flow: `LessonViewModel.submitExercise(code, tests)` evals the user's code, then evals each `test.call` in the same session, comparing the printed value to `test.equals` and rendering a PASS/FAIL transcript.
  - Added a `viewModelOverride` parameter to `ReplScreen` so B5's instrumented test can drive it with a `MockWebServer`-backed VM.

### Up next (M9, requires a real dev box)
- Open `/android/` in Android Studio Hedgehog+, run `./gradlew assembleDebug` (the wrapper jar will be regenerated on first build).
- `cd server && docker build -t landolisp-sandbox . && docker run --rm -p 8080:8080 landolisp-sandbox`.
- Run the app on an emulator; the lesson list should show 90 entries; tap one, run an example, run an exercise.
- Optionally run `bash scripts/e2e.sh` for the canned Docker smoke and `bash scripts/verify-curriculum.sh` for the full curriculum walk.

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
- 2026-04-16 — M4 complete: full Compose code editor with rainbow parens + completion (Agent B1).
- 2026-04-16 — M5 + M6 complete: 65 more lessons across control flow, macros, CLOS, conditions, packages, ASDF, 13 Quicklisp libraries (Agents B2 + B3); index regenerated to 90 lessons.
- 2026-04-16 — M7 complete: subprocess + firejail + seccomp hardening (Agent B4).
- 2026-04-16 — M8 complete: Android + server contract tests, instrumented Compose tests, E2E harness, `e2e.yml` workflow (Agent B5).
- 2026-04-16 — Phase 3 integration: fixed `index.json` decoding shape mismatch; wired completion engine into both screens; implemented exercise submit flow; added `viewModelOverride` to `ReplScreen` so B5's instrumented test can run without touching the singleton.
