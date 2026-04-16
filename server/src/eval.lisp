;;;; eval.lisp
;;;;
;;;; EVAL-IN-SESSION: read forms from a string, evaluate them in this image
;;;; with output capture and a per-call timeout. Returns a plist suitable
;;;; for JSON serialisation. This is the "in-process" baseline — B4 will
;;;; replace the body with a request to a per-session SBCL child.
;;;;
;;;; The old name SAFE-EVAL is kept as an alias so existing call-sites
;;;; compile unchanged.

(in-package #:landolisp.sandbox)

(defun read-all-forms (string)
  "Read every top-level form in STRING and return them as a list. End-of-file
inside a form bubbles up as a SANDBOX-ERROR with code 'read_error'."
  (let ((eof (gensym "EOF"))
        (forms '()))
    (with-input-from-string (s string)
      (handler-case
          (loop for form = (read s nil eof)
                until (eq form eof)
                do (push form forms))
        (error (c)
          (error 'sandbox-error
                 :code "read_error"
                 :message (princ-to-string c)))))
    (nreverse forms)))

(defun %eval-forms (forms)
  "Evaluate FORMS in order and return the primary value of the last one."
  (let ((last-value nil))
    (dolist (f forms)
      (setf last-value (eval f)))
    last-value))

(defun eval-in-session (session code)
  "Evaluate CODE (a string) inside SESSION's logical context.
Returns a plist (:stdout :stderr :value :elapsed-ms :condition) where each
slot is a string except :ELAPSED-MS (integer) and :CONDITION (plist or NIL).

A timeout produces (:condition (:type 'EVAL-TIMEOUT' ...)). All other Lisp
conditions are caught and returned in :CONDITION; the HTTP layer still
responds 200 for those, but maps timeouts to 408 per docs/API.md.

NOTE: this is the v1 in-process implementation. B4 will swap the body
for a fresh-subprocess-per-session model. The function signature and
return shape MUST stay stable so routes.lisp does not need to change."
  (declare (type session session))
  (touch-session session)
  (let* ((stdout (make-string-output-stream))
         (stderr (make-string-output-stream))
         (start-real (get-internal-real-time))
         (value-string nil)
         (cond-plist nil)
         (timed-out nil)
         (timeout (config :eval-timeout-seconds))
         (forms (handler-case (read-all-forms code)
                  (sandbox-error (c)
                    (return-from eval-in-session
                      (list :stdout ""
                            :stderr ""
                            :value nil
                            :elapsed-ms 0
                            :condition (list :type (sandbox-error-code c)
                                             :message (sandbox-error-message c))))))))
    (bordeaux-threads:with-lock-held ((session-lock session))
      (let ((*standard-output* stdout)
            (*error-output* stderr)
            (*default-pathname-defaults* (session-working-directory session)))
        (handler-case
            (bordeaux-threads:with-timeout (timeout)
              (let ((v (%eval-forms forms)))
                (setf value-string
                      (with-output-to-string (s) (prin1 v s)))))
          (bordeaux-threads:timeout (c)
            (declare (ignore c))
            (setf timed-out t)
            (setf cond-plist (list :type "EVAL-TIMEOUT"
                                   :message (format nil "evaluation exceeded ~a seconds"
                                                    timeout))))
          (error (c)
            (setf cond-plist (condition->plist c))))))
    (let ((elapsed-ms (round (* 1000
                                (/ (- (get-internal-real-time) start-real)
                                   internal-time-units-per-second)))))
      (list :stdout (get-output-stream-string stdout)
            :stderr (get-output-stream-string stderr)
            :value (if timed-out nil value-string)
            :elapsed-ms elapsed-ms
            :condition cond-plist))))

(defun safe-eval (session code)
  "Deprecated alias for EVAL-IN-SESSION. Kept so call-sites written against
the earlier name keep working while we converge on the spec's naming."
  (eval-in-session session code))
