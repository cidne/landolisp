;;;; test-handlers.lisp
;;;;
;;;; HTTP integration tests. Spins up the real Hunchentoot acceptor on an
;;;; ephemeral port and pokes it with drakma. Asserts JSON shape for every
;;;; endpoint declared in docs/API.md.

(in-package #:landolisp-sandbox.tests)

(in-suite landolisp-sandbox-suite)

(defun %ephemeral-port ()
  (+ 30000 (random 20000)))

(defmacro with-running-server ((port-var) &body body)
  "Bind PORT-VAR to an ephemeral port, run START-SERVER, evaluate BODY,
then guarantee STOP-SERVER on the way out. Forces :session-root to a temp
directory so the test does not require write access to /var/sandbox/."
  `(let* ((,port-var (%ephemeral-port))
          (tmp (uiop:ensure-pathname
                (uiop:merge-pathnames* "landolisp-test-http/"
                                       (uiop:temporary-directory))
                :ensure-directories-exist t)))
     (setf (getf landolisp.sandbox::*config* :session-root) (namestring tmp))
     (landolisp.sandbox:start-server :port ,port-var :address "127.0.0.1")
     (unwind-protect (progn ,@body)
       (landolisp.sandbox:stop-server))))

(defun %request (url &key (method :get) body)
  "Issue a request and decode the JSON response. Returns (values JSON STATUS)."
  (let ((drakma:*text-content-types*
          (cons '("application" . "json") drakma:*text-content-types*)))
    (multiple-value-bind (response status)
        (drakma:http-request url
                             :method method
                             :content-type "application/json"
                             :content body)
      (values (when (and response (stringp response) (plusp (length response)))
                (landolisp.sandbox::json-decode response))
              status))))

(test http-health-shape
  (with-running-server (port)
    (multiple-value-bind (json status)
        (%request (format nil "http://127.0.0.1:~a/v1/health" port))
      (is (= 200 status))
      (is (equal "ok" (cdr (assoc :status json)))))))

(test http-create-session-shape
  (with-running-server (port)
    (multiple-value-bind (json status)
        (%request (format nil "http://127.0.0.1:~a/v1/sessions" port)
                  :method :post)
      (is (= 201 status))
      (is (stringp (cdr (assoc :session-id json))))
      (is (stringp (cdr (assoc :expires-at json)))))))

(test http-eval-shape
  (with-running-server (port)
    (let* ((created (%request (format nil "http://127.0.0.1:~a/v1/sessions" port)
                              :method :post))
           (sid (cdr (assoc :session-id created))))
      (multiple-value-bind (json status)
          (%request (format nil "http://127.0.0.1:~a/v1/sessions/~a/eval" port sid)
                    :method :post
                    :body "{\"code\":\"(+ 1 2)\"}")
        (is (= 200 status))
        (is (equal "3" (cdr (assoc :value json))))
        (is (assoc :stdout json))
        (is (assoc :stderr json))
        (is (assoc :elapsed-ms json))))))

(test http-quickload-rejects-disallowed
  (with-running-server (port)
    (let* ((created (%request (format nil "http://127.0.0.1:~a/v1/sessions" port)
                              :method :post))
           (sid (cdr (assoc :session-id created))))
      (multiple-value-bind (json status)
          (%request (format nil "http://127.0.0.1:~a/v1/sessions/~a/quickload" port sid)
                    :method :post
                    :body "{\"system\":\"definitely-not-real\"}")
        (declare (ignore json))
        (is (= 403 status))))))

(test http-files-roundtrip
  (with-running-server (port)
    (let* ((created (%request (format nil "http://127.0.0.1:~a/v1/sessions" port)
                              :method :post))
           (sid (cdr (assoc :session-id created)))
           (file-url (format nil "http://127.0.0.1:~a/v1/sessions/~a/files/hi.lisp"
                             port sid)))
      ;; PUT
      (multiple-value-bind (resp status)
          (%request file-url :method :put :body ";; hello")
        (declare (ignore resp))
        (is (= 204 status)))
      ;; GET file
      (multiple-value-bind (resp status)
          (drakma:http-request file-url :method :get)
        (is (= 200 status))
        (is (search "hello" (if (stringp resp) resp (flexi-streams:octets-to-string resp)))))
      ;; LIST files
      (multiple-value-bind (json status)
          (%request (format nil "http://127.0.0.1:~a/v1/sessions/~a/files" port sid))
        (is (= 200 status))
        (is (consp json))))))

(test http-unknown-route-404
  (with-running-server (port)
    (multiple-value-bind (json status)
        (%request (format nil "http://127.0.0.1:~a/v1/nope" port))
      (declare (ignore json))
      (is (= 404 status)))))
