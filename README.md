# Landolisp

An Android app that teaches you Common Lisp end-to-end, with an in-app sandbox
backed by a real SBCL + Quicklisp server.

* `android/` — Kotlin + Jetpack Compose app (open in Android Studio).
* `server/` — Sandbox API: SBCL + Hunchentoot, packaged as a Docker image.
* `curriculum/` — JSON lesson files (single source of truth, bundled into the app).
* `docs/` — Architecture, project status, API contract.

See `docs/ARCHITECTURE.md` for the binding spec and `docs/PROJECT_STATUS.md` for the live status board.

## Quick start (developer machine)

```bash
# 1. Run the sandbox server locally
cd server
docker build -t landolisp-sandbox .
docker run --rm -p 8080:8080 landolisp-sandbox

# 2. Open android/ in Android Studio Hedgehog or newer.
#    Run the app on an emulator (BuildConfig.SANDBOX_BASE_URL defaults to http://10.0.2.2:8080).
```
