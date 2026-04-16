;;;; quicklisp.lisp
;;;;
;;;; Whitelisted (ql:quickload …) wrapper. Uses the live :ql package when
;;;; present (production image) and degrades to a no-op success when absent
;;;; (so unit tests don't need a Quicklisp install).

(in-package #:landolisp.sandbox)

(defun system-allowed-p (name)
  "Return T iff NAME (a string) is on the configured allow-list."
  (let ((needle (string-downcase (string-trim '(#\Space #\Tab) name))))
    (find needle (config :allowed-systems) :test #'string=)))

(defun %ql-quickload (system-name log-stream)
  "Internal: call ql:quickload, redirecting its chatter into LOG-STREAM.
Returns T on success. When :QL is not present (no Quicklisp in this image),
emits a notice into the log and returns T anyway, so the caller can keep
working in degraded environments such as unit tests."
  (let ((ql-pkg (find-package :ql)))
    (cond
      ((null ql-pkg)
       (format log-stream ";; quicklisp not present in this image; pretending.~%")
       t)
      (t
       (let ((*standard-output* log-stream))
         (funcall (find-symbol "QUICKLOAD" ql-pkg)
                  (intern (string-upcase system-name) :keyword)
                  :silent nil))
       t))))

(defun quickload-in-session (session system-name)
  "Attempt to (ql:quickload SYSTEM-NAME) on behalf of SESSION.
Signals SYSTEM-NOT-ALLOWED when SYSTEM-NAME is not whitelisted.
Returns plist (:loaded BOOL :system NAME :log STRING :condition PLIST/NIL)."
  (declare (type session session))
  (touch-session session)
  (unless (system-allowed-p system-name)
    (error 'system-not-allowed
           :message (format nil "system ~s is not on the allow-list" system-name)))
  (let ((log (make-string-output-stream))
        (ok nil)
        (cond-plist nil))
    (bordeaux-threads:with-lock-held ((session-lock session))
      (handler-case
          (setf ok (%ql-quickload system-name log))
        (error (c)
          (setf cond-plist (condition->plist c)))))
    (list :loaded (and ok (not cond-plist))
          :system system-name
          :log (get-output-stream-string log)
          :condition cond-plist)))
