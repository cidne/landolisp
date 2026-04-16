;;;; subprocess.lisp
;;;;
;;;; Per-session SBCL subprocess supervisor. Replaces the in-process v1 eval
;;;; baseline with a fresh child SBCL per session wrapped (on Linux) with
;;;; firejail / seccomp / setrlimit.
;;;;
;;;; Protocol (length-prefixed S-expression frames, stdio, both directions):
;;;;
;;;;   <length-decimal>\n
;;;;   <readable s-expression>\n
;;;;
;;;; Parent -> child forms:
;;;;   (:eval "user-code-string")
;;;;   (:quickload "system-name")
;;;;   (:read-file "path")
;;;;   (:write-file "path" "content")
;;;;   (:shutdown)
;;;;
;;;; Child -> parent forms:
;;;;   (:ok stdout stderr value elapsed-ms)    ; for :eval
;;;;   (:ok-quickload log)                     ; for :quickload
;;;;   (:ok-file content)                      ; for :read-file
;;;;   (:ok-written size)                      ; for :write-file
;;;;   (:error type message)                   ; any handled condition
;;;;
;;;; Both sides bind WITH-STANDARD-IO-SYNTAX while reading and writing so
;;;; print settings (base, case, circle) cannot surprise the peer.
;;;;
;;;; Platform assumption: Linux. We rely on POSIX signals (SIGTERM/SIGKILL),
;;;; /proc, and optionally firejail. The Docker image is Linux so this is
;;;; fine for production; pure-Windows hosts are not supported.

(in-package #:landolisp.sandbox)

;;; ---------------------------------------------------------------- tuning ---

(defparameter *runner-core-path* "/opt/sandbox/landolisp-runner.core"
  "Where the runner core image is baked in the Docker image. Absence of this
file triggers the `sbcl` fallback (plain sbcl, no preloaded core) which is
slower but lets developers experiment outside Docker.")

(defparameter *runner-sbcl-binary* "sbcl"
  "Path to the SBCL binary to launch for the runner. Override with a full
path if sbcl is not on PATH in the parent's environment.")

(defparameter *runner-default-rlimit-as* 536870912
  "Address space rlimit (bytes) passed to firejail. 512 MiB.")

(defparameter *runner-default-rlimit-cpu* 30
  "CPU-seconds rlimit passed to firejail; matches hard-timeout-seconds.")

(defparameter *runner-default-rlimit-fsize* 10485760
  "File-size rlimit (bytes) passed to firejail. 10 MiB per file.")

(defparameter *runner-handshake-timeout* 10
  "How long (seconds) to wait for the child's initial readiness before we
assume the spawn failed and kill it.")

(defparameter *runner-shutdown-grace* 2
  "Seconds between SIGTERM and the follow-up SIGKILL during KILL-RUNNER.")

;;; ---------------------------------------------------------------- structs --

(defstruct runner
  "Parent-side handle for a live runner subprocess.

Slots:

  PROCESS-INFO   Whatever uiop:launch-program / sb-ext:run-program returned.
  INPUT-STREAM   Writable stream connected to the child's stdin.
  OUTPUT-STREAM  Readable stream connected to the child's stdout.
  STDERR-STREAM  Readable stream connected to the child's stderr (or NIL).
  PID            Numeric OS pid. Used for signal-based termination.
  LOCK           Serialises concurrent RPCs against a single child.
  DEAD-P         T once we noticed the child exited or was killed.
  STARTED-AT     Universal-time when this runner was spawned.
  ISOLATION      :FIREJAIL or :PLAIN — which launcher we actually used."
  process-info
  input-stream
  output-stream
  stderr-stream
  pid
  (lock (bordeaux-threads:make-lock "runner-lock"))
  (dead-p nil)
  (started-at (get-universal-time))
  isolation)

;;; ------------------------------------------------------ framing helpers ---

(defun write-frame (stream form)
  "Write FORM to STREAM as a length-prefixed frame. Flushes on return.

Wire format: a decimal byte-length (of the serialised form itself, not
counting the trailing newline) followed by LF, then the serialised form,
then LF. We use WITH-STANDARD-IO-SYNTAX so both sides agree on printer
settings (base=10, no circle, upcase, etc.)."
  (let ((body (with-standard-io-syntax
                (let ((*print-readably* nil)
                      (*print-pretty* nil)
                      (*print-circle* t))
                  (prin1-to-string form)))))
    (format stream "~d~%~a~%" (length body) body)
    (finish-output stream)
    (length body)))

(defun read-frame (stream)
  "Read one length-prefixed frame from STREAM and return the parsed form.
Signals END-OF-FILE (propagated from READ-LINE) if the peer closed cleanly
between frames. A malformed length line is reported as SANDBOX-ERROR with
code 'frame_error'."
  (let* ((len-line (read-line stream))
         (len (handler-case (parse-integer (string-trim '(#\Space #\Tab #\Return) len-line))
                (error ()
                  (error 'sandbox-error
                         :code "frame_error"
                         :message (format nil "bad length line ~s" len-line))))))
    (when (or (< len 0) (> len (* 16 1024 1024)))
      (error 'sandbox-error
             :code "frame_error"
             :message (format nil "frame length ~a out of range" len)))
    (let ((buf (make-string len)))
      (read-sequence buf stream)
      ;; Consume the trailing newline after the payload.
      (read-char stream nil nil)
      (with-standard-io-syntax
        (let ((*read-eval* nil))
          (read-from-string buf))))))

;;; ------------------------------------------------- firejail detection -----

(defun firejail-present-p ()
  "Is the firejail binary available on PATH? Returns a pathname or NIL.
We shell out to `command -v` because UIOP doesn't ship a portable
which-style helper."
  (handler-case
      (let ((out (with-output-to-string (s)
                   (uiop:run-program '("sh" "-c" "command -v firejail")
                                     :output s
                                     :ignore-error-status t))))
        (let ((trimmed (string-trim '(#\Newline #\Space #\Tab) out)))
          (when (and (plusp (length trimmed))
                     (probe-file trimmed))
            (pathname trimmed))))
    (error () nil)))

(defun %core-available-p ()
  (and (probe-file *runner-core-path*) t))

(defun %build-argv (&key use-firejail use-core extra-env)
  "Compose the argv list we'll hand to launch-program.

When USE-FIREJAIL is true, prepend the firejail command and its flags.
When USE-CORE is true, add --core <path>. EXTRA-ENV, if non-nil, is a
list of 'KEY=VAL' pairs that will be prefixed via `env` (used rarely —
most env work happens in launch-program itself)."
  (declare (ignore extra-env))
  (let ((sbcl-argv (append
                    (list *runner-sbcl-binary*
                          "--non-interactive"
                          "--no-sysinit"
                          "--no-userinit"
                          "--disable-debugger")
                    (when use-core
                      (list "--core" *runner-core-path*)))))
    (if use-firejail
        (append
         (list "firejail"
               "--quiet"
               "--net=none"
               (format nil "--rlimit-as=~d" *runner-default-rlimit-as*)
               (format nil "--rlimit-cpu=~d" *runner-default-rlimit-cpu*)
               (format nil "--rlimit-fsize=~d" *runner-default-rlimit-fsize*)
               "--private"
               "--seccomp"
               "--caps.drop=all"
               "--nonewprivs"
               "--noroot"
               "--shell=none"
               "--")
         sbcl-argv)
        sbcl-argv)))

;;; -------------------------------------------------- spawn + kill helpers --

(defun %launch (argv)
  "Low-level launch helper around uiop:launch-program. We pick uiop (not
sb-ext:run-program) because uiop's launch-program is portable across CL
implementations and returns a process-info we can query for pid / streams
without SBCL-specific knowledge. sb-ext:run-program is viable but less
portable; if we ever need raw fd control we will add a #+sbcl branch
here that calls (sb-ext:run-program ... :wait nil) directly."
  (uiop:launch-program argv
                       :input :stream
                       :output :stream
                       :error-output :stream
                       :element-type 'character))

(defun %pid-of (process-info)
  "Best-effort pid extraction from a uiop process-info. Falls back to NIL
when no implementation-specific helper is available."
  (or (ignore-errors (uiop:process-info-pid process-info))
      #+sbcl (ignore-errors
              (let ((raw (slot-value process-info 'uiop/launch-program::process)))
                (when raw (sb-ext:process-pid raw))))
      nil))

(defun %signal-pid (pid signum)
  "Send SIGNUM (an integer) to PID. Best-effort; errors are swallowed.

We shell out to /bin/kill rather than pulling in CFFI so this module has
zero new dep surface. The fallback posix-kill path uses sb-posix when
available."
  (when (and pid (plusp pid))
    (or #+sbcl
        (ignore-errors
         (let ((sym (find-symbol "KILL" (find-package :sb-posix))))
           (when sym (funcall sym pid signum) t)))
        (ignore-errors
         (uiop:run-program (list "/bin/kill"
                                 (format nil "-~d" signum)
                                 (princ-to-string pid))
                           :ignore-error-status t)
         t))))

(defun kill-runner (runner &key (grace *runner-shutdown-grace*))
  "Politely terminate RUNNER: SIGTERM, wait GRACE seconds, then SIGKILL.
Always closes the streams and marks DEAD-P. Safe to call on an already
dead runner."
  (when (and runner (not (runner-dead-p runner)))
    (let ((pid (runner-pid runner)))
      (when pid
        ;; Try graceful shutdown first: if the child still responds,
        ;; it will honour (:shutdown) and exit 0 on its own.
        (ignore-errors
         (write-frame (runner-input-stream runner) '(:shutdown)))
        (%signal-pid pid 15)  ; SIGTERM
        (loop repeat grace
              while (%pid-alive-p pid)
              do (sleep 1))
        (when (%pid-alive-p pid)
          (%signal-pid pid 9))))  ; SIGKILL
    (ignore-errors (close (runner-input-stream runner)))
    (ignore-errors (close (runner-output-stream runner)))
    (when (runner-stderr-stream runner)
      (ignore-errors (close (runner-stderr-stream runner))))
    (ignore-errors (uiop:wait-process (runner-process-info runner)))
    (setf (runner-dead-p runner) t))
  runner)

(defun %pid-alive-p (pid)
  "Is PID still a running process we own? Uses signal 0 which tests
existence without actually delivering anything."
  (when (and pid (plusp pid))
    (or #+sbcl
        (ignore-errors
         (let ((sym (find-symbol "KILL" (find-package :sb-posix))))
           (when sym (funcall sym pid 0) t)))
        (let ((rc (nth-value 2 (uiop:run-program
                                (list "/bin/kill" "-0" (princ-to-string pid))
                                :ignore-error-status t))))
          (and (integerp rc) (zerop rc))))))

;;; --------------------------------------------------- spawn-runner entry ---

(defun spawn-runner ()
  "Start a fresh runner subprocess. Returns a RUNNER struct whose streams
are primed and ready for the first frame. The child has NOT yet evaluated
anything — first call to REQUEST-RUNNER does that.

The launcher is chosen in this order:

  1. firejail + preloaded core, if both are available.
  2. firejail + plain sbcl, if firejail is available but the runner core
     was not built into this image.
  3. Plain sbcl with --load runner.lisp, falling back so local
     non-Docker dev still works. A warning is logged so nobody deploys
     this configuration to production by accident."
  (let* ((firejail (firejail-present-p))
         (core? (%core-available-p))
         (argv (%build-argv :use-firejail (and firejail t)
                            :use-core core?))
         (isolation (if firejail :firejail :plain)))
    (unless firejail
      (format *error-output*
              "~&;; WARNING: firejail not found on PATH — runner will launch~
               ~%;;          without OS-level isolation. This is acceptable~
               ~%;;          for local dev but NOT for production.~%"))
    (unless core?
      ;; No saved core — fall back to loading runner.lisp directly. This
      ;; requires $LANDOLISP_HOME/src/runner.lisp to exist in the image.
      (let ((runner-src (format nil "~a/src/runner.lisp"
                                (or (uiop:getenv "LANDOLISP_HOME")
                                    "/opt/landolisp-sandbox"))))
        (setf argv
              (append argv
                      (list "--load" runner-src
                            "--eval" "(landolisp.runner:main)")))))
    (let ((proc (%launch argv)))
      (let ((runner (make-runner
                     :process-info proc
                     :input-stream (uiop:process-info-input proc)
                     :output-stream (uiop:process-info-output proc)
                     :stderr-stream (uiop:process-info-error-output proc)
                     :pid (%pid-of proc)
                     :isolation isolation)))
        runner))))

;;; ------------------------------------------- request / response plumbing --

(defun %drain-stderr (runner max-chars)
  "Non-blocking best-effort drain of the child's stderr. Returns a string,
possibly empty. We use LISTEN so this never blocks the caller."
  (let ((s (runner-stderr-stream runner))
        (buf (make-string-output-stream))
        (n 0))
    (when s
      (loop while (and (listen s) (< n max-chars))
            for c = (read-char-no-hang s nil nil)
            while c do
              (write-char c buf)
              (incf n)))
    (get-output-stream-string buf)))

(defun request-runner (runner form &key (timeout 10))
  "Send FORM to RUNNER and wait up to TIMEOUT seconds for one response
frame. Returns the raw response form (an :OK / :OK-* / :ERROR list).

On TIMEOUT we signal EVAL-TIMEOUT — the caller is responsible for then
calling KILL-RUNNER and scheduling a restart. We DO NOT attempt to
salvage the child: once we've given up on a response we have no way to
know whether it's mid-eval with partial state.

On EOF (child died) we signal SANDBOX-ERROR with code 'session_crashed'."
  (when (runner-dead-p runner)
    (error 'sandbox-error :code "session_crashed"
                          :message "runner is not alive"))
  (bordeaux-threads:with-lock-held ((runner-lock runner))
    (handler-case
        (progn
          (write-frame (runner-input-stream runner) form)
          (bordeaux-threads:with-timeout (timeout)
            (read-frame (runner-output-stream runner))))
      (bordeaux-threads:timeout ()
        (error 'eval-timeout
               :message (format nil "runner did not reply within ~as" timeout)))
      (end-of-file ()
        (setf (runner-dead-p runner) t)
        (error 'sandbox-error
               :code "session_crashed"
               :message "runner closed its stdout (child exited)"))
      (stream-error ()
        (setf (runner-dead-p runner) t)
        (error 'sandbox-error
               :code "session_crashed"
               :message "runner stream error (child likely exited)")))))

(defun runner-alive-p (runner)
  "T iff RUNNER looks alive: not marked dead and its pid still exists."
  (and runner
       (not (runner-dead-p runner))
       (let ((pid (runner-pid runner)))
         (or (null pid) (%pid-alive-p pid)))))
