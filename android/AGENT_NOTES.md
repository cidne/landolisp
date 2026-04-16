# Android skeleton — agent notes

## Files created

### Gradle
- `android/settings.gradle.kts` — pluginManagement + dependencyResolutionManagement, includes `:app`.
- `android/build.gradle.kts` — root, plugin aliases only.
- `android/gradle.properties` — JVM args, AndroidX, ktlint code style.
- `android/gradle/wrapper/gradle-wrapper.properties` — Gradle 8.5 (linter override; original was 8.7).
- `android/gradle/libs.versions.toml` — version catalog (AGP 8.5.2, Kotlin 2.0.21, Compose BOM 2024.09.03, etc.).
- `android/app/build.gradle.kts` — applicationId/namespace `com.landolisp`, min/target/compile SDK 26/34/34, BuildConfig `SANDBOX_BASE_URL` (debug + release, `local.properties` override), `syncCurriculum` Exec task → `preBuild`.
- `android/app/proguard-rules.pro` — keep rules for kotlinx-serialization $$serializer.

### Manifest + resources
- `android/app/src/main/AndroidManifest.xml` — INTERNET permission, single MainActivity launcher, theme, custom Application class.
- `android/app/src/main/res/values/strings.xml`, `colors.xml`, `themes.xml` (Material3 DayNight).
- `android/app/src/main/res/values-night/themes.xml`.
- `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` — adaptive icons.
- `android/app/src/main/res/drawable/ic_launcher_foreground.xml` — vector drawable: paired parens on a circle.

### Compose entry + theme
- `com/landolisp/LandolispApplication.kt` — empty `Application`.
- `com/landolisp/MainActivity.kt` — sets content to `LandolispApp()`.
- `com/landolisp/LandolispApp.kt` — NavHost (`lessons`, `lesson/{id}`, `repl`) + bottom nav (Lessons/Sandbox).
- `com/landolisp/ui/theme/Color.kt`, `Type.kt`, `Theme.kt` — Material3 light/dark; exposes `ParenColors` (6-cycle) for B1 + `CodeTextStyle` monospace.

### Screens
- `com/landolisp/ui/LessonListScreen.kt` — VM loads index, groups by track, navigates on tap.
- `com/landolisp/ui/LessonScreen.kt` — VM loads lesson; renders prose/example/exercise; Run/Submit call `SandboxRepository`.
- `com/landolisp/ui/ReplScreen.kt` — full-height code editor, Send button, scrolling output transcript.
- `com/landolisp/ui/editor/CodeEditor.kt` — **stub** `BasicTextField` with the public contract documented in KDoc.
- `com/landolisp/ui/markdown/MarkdownText.kt` + `MarkdownParser.kt` + `InlineMarkdown.kt` — hand-rolled tiny Markdown (headings, paragraphs, bullets, code fences, **bold**, *italic*, `code`).

### Data layer
- `com/landolisp/data/model/Lesson.kt` — `Lesson`, sealed `Section` (`Prose`/`Example`/`Exercise`), `LessonIndex`, `LessonSummary`, `ExerciseTest`, shared `LandolispJson` (`classDiscriminator = "kind"`, `ignoreUnknownKeys`).
- `com/landolisp/data/model/SandboxDtos.kt` — Eval/Quickload/Session/Health/Files/Error DTOs from `docs/API.md`.
- `com/landolisp/data/api/SandboxApi.kt` — Retrofit interface (every endpoint).
- `com/landolisp/data/api/SandboxClient.kt` — singleton Retrofit + OkHttp + kotlinx-serialization converter.
- `com/landolisp/data/SandboxRepository.kt` — owns sessionId, retries once on 404 (session reaped), exposes `eval`/`quickload`/`health`.
- `com/landolisp/data/LessonRepository.kt` — reads `assets/curriculum/index.json` + per-lesson JSON.

### Asset sync + tests
- `scripts/sync-curriculum.sh` — bash, idempotent, prunes stale, skips `schema.json`, reports counts. Executable.
- `android/app/src/main/assets/curriculum/.gitkeep` — placeholder so the dir exists on a fresh checkout before the sync runs.
- `android/app/src/main/assets/cl-symbols.json` — 173-entry CL standard symbol list for the editor's completer.
- `android/app/src/main/java/com/landolisp/lisp/package-info.kt` — empty placeholder for the `lisp` package (B1 fills in Tokenizer / ParenMatcher / CompletionDb).
- `app/src/test/java/com/landolisp/LessonModelTest.kt` — JUnit 4: deserializes a hand-crafted lesson + verifies forward-compat key skipping.
- `app/src/test/java/com/landolisp/data/LessonSerializationTest.kt` — JUnit 4: full round-trip + per-section-kind assertions + minimal-document defaults.

### Network / backup config
- `android/app/src/main/res/xml/network_security_config.xml` — HTTPS-only baseline + cleartext exception for `10.0.2.2`/`localhost`.
- `android/app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` — Auto-Backup + Android-12 device-transfer rules; exclude session caches.
- `android/app/src/debug/AndroidManifest.xml` — overlay that re-enables `usesCleartextTraffic` only for debug builds.

### Gradle wrapper scripts
- `android/gradlew`, `android/gradlew.bat` — standard Gradle 8.5 wrapper scripts (binary jar intentionally NOT committed; developers must run `gradle wrapper --gradle-version 8.5` once or rely on Android Studio to download it).

## TODOs left for other agents
- `TODO(B1)` in `ui/editor/CodeEditor.kt` — replace the `BasicTextField` stub with the full editor (tokenizer, rainbow parens, auto-balance, completion). KDoc enumerates the contract.
- `TODO(B4)` in `ui/LessonScreen.kt::ExerciseSection` — wire Submit to evaluate each `tests[].call` against `tests[].equals` server-side; current behavior just runs the user's code.

## Decisions to sanity-check
- **Gradle wrapper at 8.5** — task asked for 8.7; a linter rewrote the file to 8.5. Left as-is per system note. AGP 8.5.x supports both, but bump back to 8.7 if you want CI parity with Hedgehog defaults.
- **`usesCleartextTraffic="true"`** — needed for `http://10.0.2.2:8080` during emulator dev. Switch to `false` (or a `network_security_config`) before any release build.
- **`SandboxRepository` is constructed per-screen**, so each screen gets its own session. Acceptable for the skeleton; promote to a singleton (or hoist into the Application) once auth/session-sharing matters.
- **`schema.json` excluded from APK assets** — added an exclusion in `sync-curriculum.sh`. Curriculum agent should still keep `schema.json` in `/curriculum/` for validation.
- **`nonTransitiveRClass=true`** in `gradle.properties` — modern default but worth knowing if you add modules.
- Markdown renderer is intentionally tiny (no setext headings, blockquotes, tables, links). Lesson authors must stay in the supported subset.
