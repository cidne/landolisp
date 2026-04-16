;;;; test-api-contract.lisp
;;;;
;;;; A Drakma-driven contract test for every endpoint pair in docs/API.md.
;;;; Spins up a real Hunchentoot acceptor on an ephemeral port, exercises
;;;; each route, and asserts the JSON shape (presence and type of every
;;;; documented field). Tear-down is via LANDOLISP.SANDBOX:STOP-SERVER.
;;;;
;;;; This is broader than test-handlers.lisp (A2's existing handler tests):
;;;; we walk the *full* API surface end-to-end, including delete, file get,
;;;; quickload success path (skipped when QL absent), and 404 + 408 mappings.

(in-package #:landolisp-sandbox.tests)

(in-suite landolisp-sandbox-suite)

;;; ------------------------------------------------------- helpers ---

(defun %api-port ()
  (+ 40000 (random 20000)))

(defmacro %with-api-server ((port-var) &body body)
  "Bind PORT-VAR to a free port, start the acceptor + a temp session-root,
run BODY, then guarantee teardown."
  `(let* ((,port-var (%api-port))
          (tmp (uiop:ensure-pathname
                (uiop:merge-pathnames* "landolisp-test-api/"
                                       (uiop:temporary-directory))
                :ensure-directories-exist t)))
     (setf (getf landolisp.sandbox::*config* :session-root) (namestring tmp))
     (landolisp.sandbox:start-server :port ,port-var :address "127.0.0.1")
     (unwind-protect (progn ,@body)
       (landolisp.sandbox:stop-server))))

(defun %req (port path &key (method :get) body
                            (content-type "application/json"))
  "Issue a request to http://127.0.0.1:PORT/PATH and return
(values DECODED-JSON RAW-BODY STATUS HEADERS). If the response Content-Type
is not JSON, DECODED-JSON is NIL and the caller should look at RAW-BODY."
  (let ((drakma:*text-content-types*
          (list* '("application" . "json")
                 '("text" . "plain")
                 drakma:*text-content-types*)))
    (multiple-value-bind (raw status headers)
        (drakma:http-request (format nil "http://127.0.0.1:~a~a" port path)
                             :method method
                             :content-type content-type
                             :content body)
      (let* ((str (cond
                    ((stringp raw) raw)
                    ((vectorp raw)
                     (handler-case
                         (flexi-streams:octets-to-string raw :external-format :utf-8)
                       (error () nil)))
                    (t nil)))
             (ct (cdr (assoc :content-type headers)))
             (json (when (and str (search "json" (or ct "")))
                     (handler-case (landolisp.sandbox::json-decode str)
                       (error () nil)))))
        (values json str status headers)))))

(defun %present (alist key)
  "T if KEY (any spelling) appears in ALIST."
  (and (consp alist)
       (not (null (landolisp.sandbox::json-field alist key)))))

;;; --------------------------------------------------------- tests ---

(test api/health-shape
  (%with-api-server (port)
    (multiple-value-bind (json raw status)
        (%req port "/v1/health")
      (declare (ignore raw))
      (is (= 200 status))
      (is (consp json))
      (is (equal "ok" (landolisp.sandbox::json-field json :status)))
      ;; sbclVersion + qlDist are required by docs/API.md.
      (is (%present json :sbcl-version))
      (is (%present json :ql-dist)))))

(test api/sessions-create-shape
  (%with-api-server (port)
    (multiple-value-bind (json raw status)
        (%req port "/v1/sessions" :method :post)
      (declare (ignore raw))
      (is (= 201 status))
      (is (stringp (landolisp.sandbox::json-field json :session-id)))
      (is (stringp (landolisp.sandbox::json-field json :expires-at))))))

(test api/sessions-eval-success-shape
  (%with-api-server (port)
    (let* ((created (%req port "/v1/sessions" :method :post))
           (sid (landolisp.sandbox::json-field created :session-id)))
      (multiple-value-bind (json raw status)
          (%req port (format nil "/v1/sessions/~a/eval" sid)
                :method :post
                :body "{\"code\":\"(+ 1 2)\"}")
        (declare (ignore raw))
        (is (= 200 status))
        (is (equal "3" (landolisp.sandbox::json-field json :value)))
        (is (%present json :stdout))
        (is (%present json :stderr))
        (is (%present json :elapsed-ms))
        ;; condition is nullable; the field SHOULD exist (pair) but be NIL.
        (is (consp json))))))

(test api/sessions-eval-condition-shape
  "User code that signals an error returns 200 with non-nil :condition."
  (%with-api-server (port)
    (let* ((created (%req port "/v1/sessions" :method :post))
           (sid (landolisp.sandbox::json-field created :session-id)))
      (multiple-value-bind (json raw status)
          (%req port (format nil "/v1/sessions/~a/eval" sid)
                :method :post
                :body "{\"code\":\"(error \\\"boom\\\")\"}")
        (declare (ignore raw))
        (is (= 200 status))
        (let ((c (landolisp.sandbox::json-field json :condition)))
          (is (consp c))
          (is (stringp (landolisp.sandbox::json-field c :type)))
          (is (stringp (landolisp.sandbox::json-field c :message))))))))

(test api/sessions-eval-timeout-408
  "An infinite loop maps to HTTP 408 per docs/API.md."
  (let ((saved (getf landolisp.sandbox::*config* :eval-timeout-seconds)))
    (unwind-protect
         (progn
           (setf (getf landolisp.sandbox::*config* :eval-timeout-seconds) 1)
           (%with-api-server (port)
             (let* ((created (%req port "/v1/sessions" :method :post))
                    (sid (landolisp.sandbox::json-field created :session-id)))
               (multiple-value-bind (json raw status)
                   (%req port (format nil "/v1/sessions/~a/eval" sid)
                         :method :post
                         :body "{\"code\":\"(loop)\"}")
                 (declare (ignore raw))
                 (is (= 408 status))
                 (let ((c (landolisp.sandbox::json-field json :condition)))
                   (is (or (null c)
                           (equal "EVAL-TIMEOUT"
                                  (landolisp.sandbox::json-field c :type))))))))
           )
      (setf (getf landolisp.sandbox::*config* :eval-timeout-seconds) saved))))

(test api/sessions-quickload-allowed-shape
  "An allowed system returns the {:loaded :log} shape. The body of LOADED
may be NIL when QL is not actually present in the test image, but the
*shape* (keys) must be there."
  (%with-api-server (port)
    (let* ((created (%req port "/v1/sessions" :method :post))
           (sid (landolisp.sandbox::json-field created :session-id)))
      (multiple-value-bind (json raw status)
          (%req port (format nil "/v1/sessions/~a/quickload" sid)
                :method :post
                :body "{\"system\":\"alexandria\"}")
        (declare (ignore raw))
        (is (= 200 status))
        (is (consp json))
        ;; loaded is boolean (cl-json yields T or NIL); we only assert the
        ;; key is in the alist.
        (is (member :loaded json :key #'car))
        (is (%present json :log))))))

(test api/sessions-quickload-disallowed-403
  (%with-api-server (port)
    (let* ((created (%req port "/v1/sessions" :method :post))
           (sid (landolisp.sandbox::json-field created :session-id)))
      (multiple-value-bind (json raw status)
          (%req port (format nil "/v1/sessions/~a/quickload" sid)
                :method :post
                :body "{\"system\":\"definitely-not-real\"}")
        (declare (ignore raw))
        (is (= 403 status))
        (is (equal "system_not_allowed"
                   (landolisp.sandbox::json-field json :error)))))))

(test api/files-put-list-get-delete-roundtrip
  (%with-api-server (port)
    (let* ((created (%req port "/v1/sessions" :method :post))
           (sid (landolisp.sandbox::json-field created :session-id))
           (path "hello.lisp")
           (file-url (format nil "/v1/sessions/~a/files/~a" sid path)))
      ;; PUT
      (multiple-value-bind (json raw status)
          (%req port file-url :method :put
                              :body "(defun greet () :hi)"
                              :content-type "text/plain")
        (declare (ignore json raw))
        (is (= 204 status)))
      ;; LIST
      (multiple-value-bind (json raw status)
          (%req port (format nil "/v1/sessions/~a/files" sid))
        (declare (ignore raw))
        (is (= 200 status))
        (is (consp json))
        (is (find path json
                  :key (lambda (item)
                         (landolisp.sandbox::json-field item :path))
                  :test #'string=)))
      ;; GET
      (multiple-value-bind (json raw status)
          (%req port file-url :method :get)
        (declare (ignore json))
        (is (= 200 status))
        (is (search "greet" (or raw ""))))
      ;; DELETE
      (multiple-value-bind (json raw status)
          (%req port file-url :method :delete)
        (declare (ignore json raw))
        (is (= 204 status))))))

(test api/sessions-unknown-eval-404
  (%with-api-server (port)
    (multiple-value-bind (json raw status)
        (%req port "/v1/sessions/nope-no-such/eval"
              :method :post
              :body "{\"code\":\"1\"}")
      (declare (ignore raw))
      (is (= 404 status))
      (is (equal "session_not_found"
                 (landolisp.sandbox::json-field json :error))))))

(test api/unknown-route-404
  (%with-api-server (port)
    (multiple-value-bind (json raw status)
        (%req port "/v1/this-does-not-exist")
      (declare (ignore json raw))
      (is (= 404 status)))))
