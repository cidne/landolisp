;;;; test-subprocess.lisp
;;;;
;;;; Subprocess-runner unit tests added by B4. These tests do NOT touch the
;;;; existing FiveAM suites (test-evaluator, test-handlers, test-smoke);
;;;; they install themselves into the same LANDOLISP-SANDBOX-SUITE so a
;;;; single (asdf:test-system :landolisp-sandbox) run picks them up.
;;;;
;;;; The tests run anywhere SBCL can spawn an `sbcl` child — they do NOT
;;;; require firejail. They DO require that subprocess.lisp's fallback
;;;; path (plain sbcl, no isolation) works, which is why a warning is
;;;; logged when firejail is absent rather than failing hard.

(in-package #:landolisp-sandbox.tests)

(in-suite landolisp-sandbox-suite)

(defun %make-runner-and-handshake ()
  "Spawn a runner, wait for its readiness banner, return the RUNNER struct.
The caller is responsible for KILL-RUNNER on the way out."
  (let ((r (landolisp.sandbox::spawn-runner)))
    (handler-case
        (bordeaux-threads:with-timeout
            (landolisp.sandbox::*runner-handshake-timeout*)
          (landolisp.sandbox::read-frame
           (landolisp.sandbox::runner-output-stream r)))
      (error (c)
        (landolisp.sandbox::kill-runner r)
        (error c)))
    r))

(test runner-frame-roundtrip
  "Send (:eval \"(+ 1 2)\") to a fresh runner, expect :ok with value \"3\"."
  (let ((r (%make-runner-and-handshake)))
    (unwind-protect
         (let ((resp (landolisp.sandbox::request-runner
                      r (list :eval "(+ 1 2)")
                      :timeout 15)))
           (is (consp resp))
           (is (eq :ok (first resp)))
           (is (string= "3" (fourth resp))))
      (landolisp.sandbox::kill-runner r))))

(test runner-ping-roundtrip
  "(:ping) should round-trip without invoking the evaluator."
  (let ((r (%make-runner-and-handshake)))
    (unwind-protect
         (let ((resp (landolisp.sandbox::request-runner
                      r '(:ping) :timeout 5)))
           (is (consp resp))
           (is (eq :ok-pong (first resp))))
      (landolisp.sandbox::kill-runner r))))

(test runner-infinite-loop-killed
  "An infinite (loop) is killed within 12 s; the test asserts EVAL-TIMEOUT."
  (let ((r (%make-runner-and-handshake))
        (saved-timeout (landolisp.sandbox::config :eval-timeout-seconds)))
    (unwind-protect
         (progn
           (setf (landolisp.sandbox::config :eval-timeout-seconds) 2)
           (let ((start (get-universal-time))
                 (signalled nil))
             (handler-case
                 (landolisp.sandbox::request-runner
                  r (list :eval "(loop)") :timeout 5)
               (landolisp.sandbox:eval-timeout ()
                 (setf signalled t)))
             (let ((elapsed (- (get-universal-time) start)))
               (is signalled)
               (is (< elapsed 12))
               ;; Confirm the SIGKILL actually landed.
               (landolisp.sandbox::kill-runner r)
               (is (not (landolisp.sandbox::runner-alive-p r))))))
      (setf (landolisp.sandbox::config :eval-timeout-seconds) saved-timeout)
      (ignore-errors (landolisp.sandbox::kill-runner r)))))

(test runner-recovers-from-malformed-input
  "After we drop a bad frame on the runner's stdin, the runner should
exit cleanly enough that the parent can spawn a NEW runner and keep
working. (We do not attempt to salvage the same child — losing framing
means the only safe move is restart.)"
  (let ((r (%make-runner-and-handshake)))
    (unwind-protect
         (progn
           ;; Send something that does not parse as our framed protocol.
           (ignore-errors
            (write-string "this is not a frame at all"
                          (landolisp.sandbox::runner-input-stream r))
            (terpri (landolisp.sandbox::runner-input-stream r))
            (finish-output (landolisp.sandbox::runner-input-stream r)))
           ;; Give it a moment to die.
           (sleep 1)
           (landolisp.sandbox::kill-runner r))
      (ignore-errors (landolisp.sandbox::kill-runner r)))
    ;; Now spawn a fresh one and verify it works.
    (let ((r2 (%make-runner-and-handshake)))
      (unwind-protect
           (let ((resp (landolisp.sandbox::request-runner
                        r2 (list :eval "(* 6 7)") :timeout 10)))
             (is (eq :ok (first resp)))
             (is (string= "42" (fourth resp))))
        (landolisp.sandbox::kill-runner r2)))))

(test runner-survives-user-error
  "An (error \"boom\") inside user code should NOT kill the runner;
the response is (:error TYPE MESSAGE) and the next request still works."
  (let ((r (%make-runner-and-handshake)))
    (unwind-protect
         (progn
           (let ((resp (landolisp.sandbox::request-runner
                        r (list :eval "(error \"boom\")") :timeout 10)))
             (is (eq :error (first resp)))
             (is (search "boom" (third resp))))
           (let ((resp (landolisp.sandbox::request-runner
                        r (list :eval "(+ 100 1)") :timeout 10)))
             (is (eq :ok (first resp)))
             (is (string= "101" (fourth resp)))))
      (landolisp.sandbox::kill-runner r))))
