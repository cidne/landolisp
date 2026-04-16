# Sandbox API

Authoritative HTTP contract between the Android app and the sandbox server.
Mirrors `ARCHITECTURE.md` §3 with response details.

## `POST /v1/sessions`

Create a fresh SBCL process bound to a session id.

Response `201`:
```json
{ "sessionId": "9f3...e2", "expiresAt": "2026-04-16T14:00:00Z" }
```

## `POST /v1/sessions/{id}/eval`

Evaluate a form (or top-level forms) in the session.

Request:
```json
{ "code": "(+ 1 2)" }
```

Response `200`:
```json
{
  "stdout": "",
  "stderr": "",
  "value": "3",
  "elapsedMs": 4,
  "condition": null
}
```

If user code signals an unhandled condition, `condition` carries
`{ "type": "SIMPLE-ERROR", "message": "..." }` and HTTP status is still `200`
(the eval succeeded in the sense that the server captured the error).

Eval timeout: 10 s default, hard 30 s. Returns `408` on timeout.

## `POST /v1/sessions/{id}/quickload`

Request `{ "system": "alexandria" }`.
Returns `{ "loaded": true, "log": "..." }` or 4xx with error details.
Allowed system list is a whitelist on the server (see `server/src/quicklisp.lisp`).

## File CRUD

```
GET  /v1/sessions/{id}/files            -> [{ "path": "...", "size": 123 }]
GET  /v1/sessions/{id}/files/{path}     -> raw text
PUT  /v1/sessions/{id}/files/{path}     <- raw text                         204
DELETE /v1/sessions/{id}/files/{path}                                       204
```

Used by the editor when the user wants to define their own ASDF system.

## `GET /v1/health`

```json
{ "status": "ok", "sbclVersion": "2.4.0", "qlDist": "quicklisp 2024-06-01" }
```

## Errors

```json
{ "error": "session_not_found", "message": "..." }
```

Codes: `session_not_found`, `eval_timeout`, `eval_too_large`, `system_not_allowed`,
`internal_error`.
