;;;; package.lisp
;;;;
;;;; Package definition for the Landolisp sandbox.
;;;; Conditions live here so every other file can signal them.

(defpackage #:landolisp.sandbox
  (:use #:cl)
  (:nicknames #:landolisp/sandbox)
  (:export
   ;; lifecycle
   #:start-server
   #:stop-server
   #:start
   #:stop
   #:*acceptor*
   #:*config*
   ;; eval / sessions (exported so B4 can swap implementations)
   #:make-session
   #:find-session
   #:get-session
   #:delete-session
   #:eval-in-session
   #:safe-eval
   #:quickload-in-session
   ;; conditions
   #:sandbox-error
   #:session-not-found
   #:eval-timeout
   #:system-not-allowed))

(in-package #:landolisp.sandbox)

(define-condition sandbox-error (error)
  ((code :initarg :code :reader sandbox-error-code :initform "internal_error")
   (message :initarg :message :reader sandbox-error-message :initform ""))
  (:report (lambda (c s)
             (format s "sandbox-error[~a]: ~a"
                     (sandbox-error-code c)
                     (sandbox-error-message c))))
  (:documentation "Base condition for all sandbox failures. The CODE slot is
the wire-level error code that handlers will surface in JSON responses."))

(define-condition session-not-found (sandbox-error)
  ()
  (:default-initargs :code "session_not_found" :message "session not found")
  (:documentation "Signalled when a request references an unknown session id."))

(define-condition eval-timeout (sandbox-error)
  ()
  (:default-initargs :code "eval_timeout" :message "evaluation timed out")
  (:documentation "Signalled when user code exceeds the configured eval timeout."))

(define-condition system-not-allowed (sandbox-error)
  ()
  (:default-initargs :code "system_not_allowed"
                     :message "requested Quicklisp system is not on the allow-list")
  (:documentation "Signalled when a Quicklisp system requested by a client is
not on the server-side whitelist."))
