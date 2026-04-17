# Landolisp — Architecture Spec

This is the **canonical contract** every agent and contributor must follow.
If something here is ambiguous, raise it — do not silently diverge.

## 1. Product

An Android app that teaches Common Lisp end-to-end:

* Structured lessons covering the full language (atoms → reader macros → CLOS → conditions → packages → ASDF → Quicklisp).
* In-app **sandbox REPL** that talks to a remote SBCL server preloaded with ASDF + Quicklisp so users can run real CL with real libraries.
* First-class editor: syntax highlighting, **rainbow / matched parentheses** with auto-balance, and symbol completion.

## 2. Repo layout

```
/android/                  Kotlin + Jetpack Compose app (Gradle)
  app/
    src/main/java/com/landolisp/
      ui/                  Screens (LessonScreen, ReplScreen, LessonListScreen)
      ui/editor/           CodeEditor composable (highlight, parens, completion)
      ui/theme/            Material3 theme
      data/                Repositories (lessons, sandbox)
      data/api/            Retrofit client for sandbox
      data/model/          Lesson / EvalRequest / EvalResponse
      lisp/                On-device tokenizer, paren matcher, completion DB
      MainActivity.kt
      LandolispApp.kt
    src/main/assets/curriculum/   <-- copied from /curriculum at build time
  build.gradle.kts, settings.gradle.kts, gradle.properties, etc.
/server/                   Common Lisp sandbox API (Hunchentoot)
  src/                     CL source
  Dockerfile               SBCL + Quicklisp + Hunchentoot
  landolisp-sandbox.asd
/curriculum/               JSON lesson files (single source of truth)
  schema.json
  001-atoms.json … etc.
  index.json               manifest of all lessons in order
/docs/
  PROJECT_STATUS.md        live status board
  ARCHITECTURE.md          this file
  API.md                   sandbox HTTP contract
  CURRICULUM.md            authoring guide
/scripts/                  helper scripts (sync curriculum into android assets, etc.)
/.github/workflows/        CI
main.lisp                  legacy Land-of-Lisp example, kept for nostalgia + as a sample lesson
```

## 3. HTTP API contract (sandbox)

Base URL configurable via `BuildConfig.SANDBOX_BASE_URL`. Defaults to
`http://10.0.2.2:8080` (Android emulator → host).

```
POST /v1/sessions                      -> { "sessionId": "uuid", "expiresAt": "..." }
POST /v1/sessions/{id}/eval            <- { "code": "(+ 1 2)" }
                                       -> { "stdout": "...", "stderr": "...", "value": "3", "elapsedMs": 12 }
POST /v1/sessions/{id}/quickload       <- { "system": "alexandria" }
                                       -> { "loaded": true, "log": "..." }
GET  /v1/sessions/{id}/files           -> [{ "path": "foo.lisp", "size": 123 }, ...]
PUT  /v1/sessions/{id}/files/{path}    <- raw text  -> 204
GET  /v1/health                        -> { "status": "ok", "sbclVersion": "2.4.x", "qlDist": "..." }
```

Errors: JSON `{ "error": "code", "message": "human" }` with appropriate HTTP status.
Eval timeout: 10 s default, 30 s hard cap. Memory cap: 512 MB per session.

## 4. Sandboxing rules (server)

* One **fresh SBCL process per session**, supervised by the Hunchentoot app.
* Process started via `firejail --net=none --quiet --rlimit-as=536870912 --rlimit-cpu=30 --private` (or seccomp profile in Docker).
* Eval is delegated through a thin wrapper that captures `*standard-output*`, `*error-output*`, and the primary value.
* Quicklisp is preinstalled at image build time. `(ql:quickload …)` is allowed; arbitrary network IO from user code is blocked at firewall/seccomp level.
* Sessions idle for > 30 min are reaped.

## 5. Curriculum schema

A lesson is a JSON file:

```jsonc
{
  "id": "001-atoms",
  "title": "Atoms and S-expressions",
  "track": "fundamentals",            // fundamentals | functions | macros | clos | conditions | system | libraries
  "order": 1,
  "estimatedMinutes": 8,
  "prerequisites": [],
  "sections": [
    { "kind": "prose",   "markdown": "..." },
    { "kind": "example", "code": "(+ 1 2)", "expected": "3", "explain": "..." },
    { "kind": "exercise","prompt": "...", "starter": "(defun greet ...)", "tests": [{"call": "(greet \"x\")", "equals": "\"hello x\""}] }
  ],
  "completionSymbols": ["defun", "let", "lambda"]    // hints for the editor's completer while in this lesson
}
```

`/curriculum/index.json` is the ordered manifest used by the app for navigation.

## 6. Editor (Compose)

* `CodeEditor(state: CodeEditorState, modifier: Modifier)` — pure Compose, no third-party editor lib.
* Highlights via `AnnotatedString` rebuilt on every text change (lessons are short; debounce 50 ms).
* Paren management:
  * Auto-insert matching `)` when `(` typed.
  * Skip-over of `)` when next char is already `)`.
  * Highlight matching pair around cursor.
  * Rainbow nesting colors (6 cycle).
* Completion: trie over current lesson's `completionSymbols` ∪ Common Lisp standard symbol table (bundled JSON: `assets/cl-symbols.json`).
* Tokenizer lives in `lisp/Tokenizer.kt` (shared with paren matcher and highlighter).

## 7. Conventions

* **Kotlin:** ktlint defaults, package `com.landolisp`, `minSdk = 26`, `targetSdk = 34`, `compileSdk = 34`, Compose BOM.
* **Common Lisp:** lowercase, 2-space indent, `package landolisp.sandbox`, every file starts with `(in-package …)`.
* **JSON lessons:** kebab-case ids, no trailing commas, validate against `schema.json`.
* **Commits:** conventional commit prefixes (`feat:`, `fix:`, `docs:`, `chore:`). Sign with the standard Claude Code trailer.
* **Branch:** all work lands on `claude/android-lisp-learning-app-35r3M`.
* **No emojis** in source files unless explicitly requested.

## 8. Out of scope (v1)

* User accounts / cloud sync.
* IDE-grade features (refactoring, jump-to-definition across user files, debugger UI).
* iOS.
