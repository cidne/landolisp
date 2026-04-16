;;;; sessions.lisp
;;;;
;;;; In-memory session table plus working-directory bookkeeping.
;;;;
;;;; NOTE: in this v1 baseline a "session" is a struct held inside the single
;;;; SBCL Hunchentoot process. B4 will swap the LISP-PROCESS slot for a real
;;;; supervised SBCL child. The struct layout is meant to absorb that
;;;; transition with minimal churn elsewhere.

(in-package #:landolisp.sandbox)

(defstruct (session (:constructor %make-session))
  "In-memory record describing a sandbox session.

Slot names match the brief in the task spec and ARCHITECTURE.md:

  ID             uuid string identifying the session.
  CREATED-AT     universal-time of session creation.
  LAST-USED      universal-time of most recent activity.
  LISP-PROCESS   reserved for B4's per-session SBCL child; today NIL and
                 EVAL happens in-process.
  SCRATCH-DIR    per-session working directory (pathname, trailing slash).
  STDOUT-BUFFER  string-output-stream accumulating any *standard-output*
                 writes from user code across calls.
  LOCK           guards access to the session's mutable state."
  id
  created-at
  last-used
  lisp-process
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
(defun session-subprocess (s) (session-lisp-process s))
(defun (setf session-subprocess) (v s) (setf (session-lisp-process s) v))

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
code 'too_many_sessions' when the configured cap is reached."
  (ensure-session-root)
  (bordeaux-threads:with-lock-held (*sessions-lock*)
    (when (>= (hash-table-count *sessions*) (config :max-sessions))
      (error 'sandbox-error
             :code "too_many_sessions"
             :message "session cap reached; try again later"))
    (let* ((id (make-uuid))
           (now (get-universal-time))
           (wd (session-working-dir id))
           (s (%make-session :id id
                             :created-at now
                             :last-used now
                             :lisp-process nil
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

(defun delete-session (id)
  "Remove session ID from the table and best-effort delete its scratch dir.
Returns T when ID was present, NIL otherwise."
  (let ((victim
          (bordeaux-threads:with-lock-held (*sessions-lock*)
            (let ((s (gethash id *sessions*)))
              (when s
                (remhash id *sessions*)
                s)))))
    (when victim
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
        :expires-at (iso-from-universal (session-expires-at session))))
