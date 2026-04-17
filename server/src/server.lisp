;;;; server.lisp
;;;;
;;;; Hunchentoot acceptor lifecycle plus the toplevel entry-point used by
;;;; the Docker image. The reaper loop itself lives in sessions.lisp; we only
;;;; own the thread that drives it.

(in-package #:landolisp.sandbox)

(defvar *acceptor* nil
  "The live HUNCHENTOOT acceptor, if the server is running.")

(defvar *reaper-thread* nil
  "Background bordeaux-threads thread running REAP-LOOP.")

(defclass landolisp-acceptor (hunchentoot:easy-acceptor) ()
  (:documentation "Trivial easy-acceptor subclass so we can override the
status-message renderer to emit JSON instead of HTML."))

(defmethod hunchentoot:acceptor-status-message ((acceptor landolisp-acceptor)
                                                http-status-code
                                                &key &allow-other-keys)
  "Render Hunchentoot's default error pages as our standard JSON shape."
  (apply-cors-headers)
  (setf (hunchentoot:content-type*) "application/json; charset=utf-8")
  (json-encode (list :error
                     (cond ((= http-status-code 404) "not_found")
                           ((>= http-status-code 500) "internal_error")
                           (t "request_error"))
                     :message (format nil "HTTP ~a" http-status-code))))

(defun configure-error-handling ()
  "Make sure Hunchentoot does not render its built-in HTML debug pages."
  (setf hunchentoot:*catch-errors-p* t
        hunchentoot:*show-lisp-errors-p* nil
        hunchentoot:*show-lisp-backtraces-p* nil))

(defun start-server (&key (port 8080) (address "0.0.0.0") host)
  "Start the sandbox HTTP server on ADDRESS:PORT. Returns the acceptor.
HOST is accepted as a deprecated alias for ADDRESS so older callers keep
compiling. Idempotent: calling while already running is a no-op."
  (when *acceptor*
    (return-from start-server *acceptor*))
  (let ((bind-address (or host address)))
    (ensure-session-root)
    (configure-error-handling)
    (setf *server-start-time* (get-universal-time))
    (setf *reaper-stop* nil)
    (let ((acc (make-instance 'landolisp-acceptor
                              :address bind-address
                              :port port
                              :access-log-destination nil
                              :message-log-destination *error-output*)))
      ;; Single dispatcher entry that delegates to MAIN-DISPATCHER.
      (setf hunchentoot:*dispatch-table*
            (list (lambda (request) (main-dispatcher request))))
      (hunchentoot:start acc)
      (setf *acceptor* acc)
      (setf *reaper-thread*
            (bordeaux-threads:make-thread #'reap-loop
                                          :name "landolisp-reaper"))
      (format t "~&landolisp sandbox listening on ~a:~a~%" bind-address port)
      (force-output)
      acc)))

(defun stop-server ()
  "Shut down the acceptor and reaper. Safe to call when already stopped."
  (setf *reaper-stop* t)
  (when *reaper-thread*
    (ignore-errors
     (bordeaux-threads:join-thread *reaper-thread*))
    (setf *reaper-thread* nil))
  (when *acceptor*
    (ignore-errors (hunchentoot:stop *acceptor* :soft nil))
    (setf *acceptor* nil))
  t)

;; Backwards-compatible aliases used by older docs / scripts / Makefile.
(defun start (&key (port (config :port)) (host (config :host)))
  (start-server :port port :address host))

(defun stop ()
  (stop-server))

(defun main ()
  "Toplevel entry-point used by the saved core image: start the acceptor and
block forever. Trapping SIGINT cleanly is left to whoever wires the runner."
  (start-server)
  (loop (sleep 3600)))
