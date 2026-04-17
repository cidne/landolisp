;;;; sessions.lisp
;;;;
;;;; In-memory session table plus working-directory bookkeeping. Each session
;;;; carries a handle on its supervised SBCL child (see subprocess.lisp).
;;;;
;;;; Lifecycle:
;;;;   make-session  -> allocate id + scratch dir, lazy-spawn the runner.
;;;;   eval-in-session / quickload-in-session -> RPC to the runner.
;;;;   reap / delete-session -> SIGTERM, grace, SIGKILL, then drop the dir.

(in-package #:landolisp.sandbox)

(defstruct (session (:constructor %make-session))
  "In-memory record describing a sandbox session.

  ID                 uuid string identifying the session.
  CREATED-AT         universal-time of session creation.
  LAST-USED          universal-time of most recent activity.
  LISP-PROCESS       legacy slot — points to the same RUNNER struct as
                     RUNNER-PROCESS for backwards compatibility with
                     any caller that still asks for SESSION-LISP-PROCESS.
  RUNNER-PROCESS     RUNNER struct (see subprocess.lisp), or NIL if not
                     yet spawned. Lazy: first eval will call ensure-runner.
  RUNNER-INPUT-STREAM
  RUNNER-OUTPUT-STREAM
                     Cached stream handles, copied from the runner so
                     callers don't have to chase the indirection. They
                     are reset to NIL when the runner is rebooted.
  RUNNER-PID         OS pid of the supervised child, or NIL.
  RESTART-COUNT      How many times we have respawned this session's
                     runner due to a crash or timeout-driven kill.
  SCRATCH-DIR        per-session working directory (pathname).
  STDOUT-BUFFER      legacy accumulator from the in-process baseline;
                     unused after the subprocess switch but kept so the
                     struct layout stays stable.
  LOCK               guards mutable session state."
  id
  created-at
  last-used
  lisp-process
  runner-process
  runner-input-stream
  runner-output-stream
  runner-pid
  (restart-count 0)
  scratch-dir
  (stdout-buffer (make-string-output-stream))
  (lock (bordeaux-threads:make-recursive-lock "session-lock")))

;; Backwards-compatible accessor aliases. Earlier drafts of this file used
;; LAST-USED-AT / WORKING-DIRECTORY / SUBPROCESS; keep thin wrappers so
;; nothing in other files needs renaming.
(declaim (inline session-last-used-at session-working-directory session-subprocess))
(defun session-last-used-at (s) (session-last-used s))
(defun (setf session-last-used-at) (v s) (setf (session-last-used s) v))
(defun session-working-directory (s) (session-scratch-dir s))
(defun (setf session-working-directory) (v s) (setf (session-scratch-dir s) v))
(defun session-subprocess (s) (session-runner-process s))
(defun (setf session-subprocess) (v s) (setf (session-runner-process s) v))

(defvar *sessions* (make-hash-table :test 'equal)
  "Map of session-id (string) -> SESSION struct.")

(defvar *sessions-lock* (bordeaux-threads:make-lock "sessions-table-lock")
  "Guards structural modifications to *SESSIONS*.")

(defun ensure-session-root ()
  "Make sure the parent directory for per-session work dirs exists.
Returns the pathname."
  (let ((root (config :session-root)))
    (ensure-directories-exist root)
    root))

(defun session-working-dir (id)
  "Return a pathname for ID's scratch directory (with trailing slash)."
  (merge-pathnames (concatenate 'string id "/") (config :session-root)))

(defun make-session ()
  "Create, register, and return a new SESSION. Signals SANDBOX-ERROR with
code 'capacity' (HTTP 503) when the configured cap is reached.

We do NOT spawn the runner here — that's deferred to ENSURE-RUNNER on the
first eval, so creating-but-not-using a session is cheap and idempotent
under load. (The Android editor opens a session up front.)"
  (ensure-session-root)
  (bordeaux-threads:with-lock-held (*sessions-lock*)
    (when (>= (hash-table-count *sessions*) (config :max-sessions))
      (error 'sandbox-error
             :code "capacity"
             :message
             (format nil "session cap (~a) reached; try again later"
                     (config :max-sessions))))
    (let* ((id (make-uuid))
           (now (get-universal-time))
           (wd (session-working-dir id))
           (s (%make-session :id id
                             :created-at now
                             :last-used now
                             :lisp-process nil
                             :runner-process nil
                             :scratch-dir wd)))
      (ensure-directories-exist wd)
      (setf (gethash id *sessions*) s)
      s)))

(defun find-session (id)
  "Return the SESSION named ID. Signals SESSION-NOT-FOUND if absent or if
its idle TTL has elapsed."
  (let ((s (bordeaux-threads:with-lock-held (*sessions-lock*)
             (gethash id *sessions*))))
    (unless s
      (error 'session-not-found
             :message (format nil "no session with id ~a" id)))
    (when (< (session-expires-at s) (get-universal-time))
      (delete-session id)
      (error 'session-not-found
             :message (format nil "session ~a expired" id)))
    s))

(defun get-session (id)
  "Alias of FIND-SESSION; matches the symbol named in the task brief."
  (find-session id))

(defun touch-session (session)
  "Refresh SESSION's last-used timestamp to now."
  (setf (session-last-used session) (get-universal-time))
  session)

(defun session-expires-at (session)
  "Return the universal-time at which SESSION will expire."
  (+ (session-last-used session) (config :session-ttl-seconds)))

;;; -------------------------------------- runner attach / detach helpers ---

(defun attach-runner (session runner)
  "Wire RUNNER's streams and pid into SESSION's slots. Idempotent."
  (setf (session-runner-process session) runner
        (session-lisp-process session) runner
        (session-runner-input-stream session) (runner-input-stream runner)
        (session-runner-output-stream session) (runner-output-stream runner)
        (session-runner-pid session) (runner-pid runner))
  session)

(defun detach-runner (session)
  "Clear runner-related slots without killing the child. Use after the
caller has already invoked KILL-RUNNER."
  (setf (session-runner-process session) nil
        (session-lisp-process session) nil
        (session-runner-input-stream session) nil
        (session-runner-output-stream session) nil
        (session-runner-pid session) nil)
  session)

(defun ensure-runner (session)
  "Lazy-spawn the runner for SESSION the first time it's needed, or
respawn it if the previous one died or was killed. Returns the live
RUNNER struct."
  (bordeaux-threads:with-lock-held ((session-lock session))
    (let ((r (session-runner-process session)))
      (cond
        ((and r (runner-alive-p r)) r)
        (t
         (when r
           (kill-runner r)
           (incf (session-restart-count session)))
         (let ((fresh (spawn-runner)))
           ;; Drain the readiness banner the runner emits at startup; if
           ;; it doesn't show up within the handshake budget, treat the
           ;; spawn as failed and surface a clean error.
           (handler-case
               (bordeaux-threads:with-timeout (*runner-handshake-timeout*)
                 (read-frame (runner-output-stream fresh)))
             (bordeaux-threads:timeout ()
               (kill-runner fresh)
               (error 'sandbox-error
                      :code "runner_handshake_timeout"
                      :message "runner did not announce readiness"))
             (end-of-file ()
               (kill-runner fresh)
               (error 'sandbox-error
                      :code "runner_spawn_failed"
                      :message "runner exited before handshake")))
           (attach-runner session fresh)
           fresh))))))

(defun mark-needs-restart (session)
  "Tear down SESSION's runner and clear slots so the next ENSURE-RUNNER
call will spawn a fresh one. Bumps RESTART-COUNT.

Takes the session lock (recursive) so concurrent eval requests on the
same session — though uncommon — race-cleanly: at worst one of them
will kill an already-dead pid, which KILL-RUNNER tolerates."
  (bordeaux-threads:with-lock-held ((session-lock session))
    (let ((r (session-runner-process session)))
      (when r
        (kill-runner r)
        (incf (session-restart-count session))
        (detach-runner session)))))

;;; --------------------------------------------------- session table ops ---

(defun delete-session (id)
  "Remove session ID from the table, kill its runner, and best-effort
delete its scratch dir. Returns T when ID was present, NIL otherwise."
  (let ((victim
          (bordeaux-threads:with-lock-held (*sessions-lock*)
            (let ((s (gethash id *sessions*)))
              (when s
                (remhash id *sessions*)
                s)))))
    (when victim
      (let ((r (session-runner-process victim)))
        (when r (kill-runner r)))
      (ignore-errors
       (uiop:delete-directory-tree (session-scratch-dir victim)
                                   :validate t
                                   :if-does-not-exist :ignore))
      t)))

(defun reap (session-or-id)
  "Force-drop a session given a SESSION struct or its id string."
  (delete-session
   (if (stringp session-or-id) session-or-id (session-id session-or-id))))

(defun reap-expired-sessions ()
  "Drop sessions whose last-used + ttl is in the past. Returns the count
of sessions reaped."
  (let ((now (get-universal-time))
        (victims '()))
    (bordeaux-threads:with-lock-held (*sessions-lock*)
      (maphash (lambda (id s)
                 (when (< (session-expires-at s) now)
                   (push id victims)))
               *sessions*))
    (dolist (id victims)
      (delete-session id))
    (length victims)))

(defvar *reaper-stop* nil
  "Flag set by STOP-SERVER to ask the reaper thread to exit.")

(defun reap-loop ()
  "Sweep expired sessions every :REAPER-INTERVAL-SECONDS until *REAPER-STOP*
becomes true. Started in a bordeaux-threads thread by START-SERVER."
  (loop until *reaper-stop* do
    (handler-case (reap-expired-sessions)
      (error (c) (format *error-output* "~&reaper: ~a~%" c)))
    (loop repeat (config :reaper-interval-seconds)
          while (not *reaper-stop*)
          do (sleep 1))))

(defun session->plist (session)
  "Render SESSION as a JSON-friendly plist."
  (list :session-id (session-id session)
        :created-at (iso-from-universal (session-created-at session))
        :last-used (iso-from-universal (session-last-used session))
        :expires-at (iso-from-universal (session-expires-at session))
        :runner-pid (session-runner-pid session)
        :restart-count (session-restart-count session)))
