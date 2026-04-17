;;;; eval.lisp
;;;;
;;;; EVAL-IN-SESSION: serialise CODE into a (:eval ...) frame, ship it to
;;;; the session's runner subprocess, and translate the response into the
;;;; plist shape that routes.lisp expects.
;;;;
;;;; The public surface is unchanged from the v1 baseline: same name,
;;;; same arglist, same return-shape. All the dangerous bits — eval'ing
;;;; user code, capturing its output, enforcing rlimits — happen in the
;;;; supervised child (see runner.lisp + subprocess.lisp). The parent
;;;; never calls CL:EVAL on user input.

(in-package #:landolisp.sandbox)

;;; -------------------------------------------------- response decoding ---

(defun %ok-frame->plist (frame elapsed-real-ms)
  "Convert a (:ok stdout stderr value elapsed-ms) frame from the runner
into the plist shape advertised in docs/API.md.

ELAPSED-REAL-MS is the parent's wall-clock measurement, used as a fallback
when the runner's reported elapsed-ms is missing or implausibly small (the
parent's number is closer to what the client experiences anyway since it
includes IPC overhead)."
  (destructuring-bind (&optional kind stdout stderr value elapsed) frame
    (declare (ignore kind))
    (list :stdout (or stdout "")
          :stderr (or stderr "")
          :value value
          :elapsed-ms (max (or elapsed 0) elapsed-real-ms 0)
          :condition nil)))

(defun %error-frame->plist (frame elapsed-real-ms)
  "Convert (:error type message) into the standard plist with :CONDITION."
  (destructuring-bind (&optional kind type message) frame
    (declare (ignore kind))
    (list :stdout ""
          :stderr ""
          :value nil
          :elapsed-ms elapsed-real-ms
          :condition (list :type (or type "error")
                           :message (or message "")))))

(defun %timeout->plist (timeout-seconds elapsed-real-ms)
  (list :stdout ""
        :stderr ""
        :value nil
        :elapsed-ms elapsed-real-ms
        :condition (list :type "EVAL-TIMEOUT"
                         :message (format nil "evaluation exceeded ~a seconds"
                                          timeout-seconds))))

(defun %crash->plist (message elapsed-real-ms restart-count)
  (list :stdout ""
        :stderr ""
        :value nil
        :elapsed-ms elapsed-real-ms
        :condition (list :type "session_crashed"
                         :message message
                         :restart-count restart-count)))

;;; -------------------------------------------------------- public entry ---

(defun eval-in-session (session code)
  "Evaluate CODE (a string) in SESSION's supervised SBCL subprocess.

Return value is a plist (:stdout :stderr :value :elapsed-ms :condition)
suitable for JSON encoding. The shape is identical to the v1 in-process
implementation so routes.lisp does not have to change.

Failure modes and how they surface:

  * Wall-clock timeout:  :CONDITION (:TYPE \"EVAL-TIMEOUT\" ...). We
    SIGKILL the runner and mark the session for restart.
  * Child crashed (EOF on stdout, non-zero exit):
                          :CONDITION (:TYPE \"session_crashed\" ...).
                          The next call transparently spawns a fresh
                          runner; the user loses their bindings.
  * Eval signalled a CL condition inside the child:
                          :CONDITION (:TYPE \"...\" :MESSAGE \"...\").
                          The runner stays alive.
  * eval_too_large / read_error:
                          :CONDITION populated, no eval performed.

NOTE: this is no longer the in-process implementation. All user code now
runs in a fresh SBCL launched (when firejail is present) with --net=none,
--seccomp, and rlimit-as=512MB. See SECURITY.md for the precise flags."
  (declare (type session session))
  (touch-session session)
  (let ((max-bytes (config :max-code-bytes)))
    (when (> (length code) max-bytes)
      (return-from eval-in-session
        (list :stdout "" :stderr "" :value nil :elapsed-ms 0
              :condition (list :type "eval_too_large"
                               :message (format nil "code exceeds ~a bytes"
                                                max-bytes))))))
  (let* ((timeout (config :eval-timeout-seconds))
         (hard-cap (config :hard-timeout-seconds))
         ;; The wall-clock budget given to REQUEST-RUNNER is the configured
         ;; eval timeout plus a small slack (1 s) for IPC framing. The
         ;; runner has its own internal hard cap (defence in depth).
         (rpc-budget (max 1 (min hard-cap (+ 1 timeout))))
         (start (get-internal-real-time)))
    (labels ((elapsed ()
               (round (* 1000 (/ (- (get-internal-real-time) start)
                                 internal-time-units-per-second)))))
      (handler-case
          (let* ((runner (ensure-runner session))
                 (frame (request-runner runner (list :eval code)
                                        :timeout rpc-budget)))
            (case (and (consp frame) (first frame))
              (:ok (%ok-frame->plist frame (elapsed)))
              (:error (%error-frame->plist frame (elapsed)))
              (otherwise
               (list :stdout "" :stderr "" :value nil :elapsed-ms (elapsed)
                     :condition (list :type "bad_response"
                                      :message
                                      (format nil "unexpected runner reply ~s"
                                              frame))))))
        (eval-timeout ()
          (mark-needs-restart session)
          (%timeout->plist timeout (elapsed)))
        (sandbox-error (c)
          ;; Crash path: the runner died on us. Schedule a restart so the
          ;; NEXT eval transparently gets a fresh child; this call still
          ;; surfaces the prior crash so the user knows their bindings
          ;; are gone.
          (when (string= (sandbox-error-code c) "session_crashed")
            (mark-needs-restart session))
          (%crash->plist (sandbox-error-message c)
                         (elapsed)
                         (session-restart-count session)))))))

(defun safe-eval (session code)
  "Deprecated alias for EVAL-IN-SESSION. Kept so call-sites written against
the earlier name keep working while we converge on the spec's naming."
  (eval-in-session session code))
