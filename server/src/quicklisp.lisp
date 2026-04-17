;;;; quicklisp.lisp
;;;;
;;;; Whitelisted (ql:quickload …) wrapper. Allow-list enforcement happens
;;;; here in the parent; the actual ql:quickload runs in the per-session
;;;; runner subprocess so the loaded fasls stay isolated to that child's
;;;; image (and disappear when the runner is reaped).

(in-package #:landolisp.sandbox)

(defun system-allowed-p (name)
  "Return T iff NAME (a string) is on the configured allow-list."
  (let ((needle (string-downcase (string-trim '(#\Space #\Tab) name))))
    (find needle (config :allowed-systems) :test #'string=)))

(defun quickload-in-session (session system-name)
  "Attempt to (ql:quickload SYSTEM-NAME) on behalf of SESSION.

Allow-list rejection is decided in the parent (this function); the actual
load happens inside the runner. The runner's QL state IS preserved across
subsequent evals within the same session — that's the whole point of
having one persistent child per session.

Signals SYSTEM-NOT-ALLOWED when SYSTEM-NAME is not whitelisted.
Returns plist (:loaded BOOL :system NAME :log STRING :condition PLIST/NIL)."
  (declare (type session session))
  (touch-session session)
  (unless (system-allowed-p system-name)
    (error 'system-not-allowed
           :message (format nil "system ~s is not on the allow-list" system-name)))
  (let* ((rpc-budget (config :hard-timeout-seconds))
         (start (get-internal-real-time)))
    (labels ((elapsed ()
               (round (* 1000 (/ (- (get-internal-real-time) start)
                                 internal-time-units-per-second)))))
      (handler-case
          (let* ((runner (ensure-runner session))
                 (frame (request-runner runner
                                        (list :quickload system-name)
                                        :timeout rpc-budget)))
            (case (and (consp frame) (first frame))
              (:ok-quickload
               (list :loaded t
                     :system system-name
                     :log (or (second frame) "")
                     :condition nil))
              (:error
               (list :loaded nil
                     :system system-name
                     :log ""
                     :condition (list :type (or (second frame) "error")
                                      :message (or (third frame) ""))))
              (otherwise
               (list :loaded nil
                     :system system-name
                     :log ""
                     :condition (list :type "bad_response"
                                      :message
                                      (format nil "unexpected reply ~s"
                                              frame))))))
        (eval-timeout ()
          (mark-needs-restart session)
          (list :loaded nil
                :system system-name
                :log ""
                :condition (list :type "EVAL-TIMEOUT"
                                 :message
                                 (format nil "quickload exceeded ~a seconds"
                                         rpc-budget))))
        (sandbox-error (c)
          (when (string= (sandbox-error-code c) "session_crashed")
            (mark-needs-restart session))
          (list :loaded nil
                :system system-name
                :log ""
                :condition (list :type (sandbox-error-code c)
                                 :message (sandbox-error-message c)
                                 :elapsed-ms (elapsed))))))))
