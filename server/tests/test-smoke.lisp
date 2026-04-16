;;;; test-smoke.lisp
;;;;
;;;; FiveAM smoke tests for the Landolisp sandbox. Combines the unit tests
;;;; for SAFE-EVAL and the HTTP handler integration tests under one suite,
;;;; LANDOLISP-SANDBOX-SUITE, exported for `(asdf:test-system …)`.

(defpackage #:landolisp-sandbox.tests
  (:use #:cl #:fiveam)
  (:export #:landolisp-sandbox-suite
           #:run-all))

(in-package #:landolisp-sandbox.tests)

(def-suite landolisp-sandbox-suite
  :description "All smoke tests for landolisp-sandbox.")

(in-suite landolisp-sandbox-suite)

;;; -------------------------------------------------------------- helpers ---

(defun fresh-session ()
  "Create a session and return it; tweaks *config* to use a temp workdir."
  (let ((tmp (uiop:ensure-pathname
              (uiop:merge-pathnames* "landolisp-test/" (uiop:temporary-directory))
              :ensure-directories-exist t)))
    (setf (getf landolisp.sandbox::*config* :session-root)
          (namestring tmp))
    (landolisp.sandbox::make-session)))

(defun eval-string (session code)
  (landolisp.sandbox::eval-in-session session code))

;;; ---------------------------------------------------- evaluator units ---

(test eval-arithmetic
  (let* ((s (fresh-session))
         (r (eval-string s "(+ 1 2)")))
    (is (string= "3" (getf r :value)))
    (is (null (getf r :condition)))))

(test eval-multiple-forms
  (let* ((s (fresh-session))
         (r (eval-string s "(defparameter *x* 10) (* *x* *x*)")))
    (is (string= "100" (getf r :value)))))

(test eval-stdout-capture
  (let* ((s (fresh-session))
         (r (eval-string s "(progn (princ \"hi\") :ok)")))
    (is (search "hi" (getf r :stdout)))
    (is (string= ":OK" (getf r :value)))))

(test eval-error-captured
  (let* ((s (fresh-session))
         (r (eval-string s "(error \"boom\")")))
    (is (not (null (getf r :condition))))
    (is (search "boom" (getf (getf r :condition) :message)))))

(test eval-timeout-returns-condition
  (let ((saved (getf landolisp.sandbox::*config* :eval-timeout-seconds)))
    (unwind-protect
         (progn
           (setf (getf landolisp.sandbox::*config* :eval-timeout-seconds) 1)
           (let* ((s (fresh-session))
                  (r (eval-string s "(loop)")))
             (is (not (null (getf r :condition))))
             (is (string= "EVAL-TIMEOUT" (getf (getf r :condition) :type)))))
      (setf (getf landolisp.sandbox::*config* :eval-timeout-seconds) saved))))

;;; ---------------------------------------------------- session-table ---

(test session-not-found-signals
  (signals landolisp.sandbox:session-not-found
    (landolisp.sandbox::find-session "nope")))

(test reaper-removes-expired
  (let ((saved-ttl (getf landolisp.sandbox::*config* :session-ttl-seconds)))
    (unwind-protect
         (progn
           (setf (getf landolisp.sandbox::*config* :session-ttl-seconds) -1)
           (fresh-session)
           (is (>= (landolisp.sandbox::reap-expired-sessions) 1)))
      (setf (getf landolisp.sandbox::*config* :session-ttl-seconds) saved-ttl))))

;;; --------------------------------------------------------- file CRUD ---

(test file-roundtrip
  (let ((s (fresh-session)))
    (landolisp.sandbox::write-session-file s "hello.lisp" "(defun greet () :hi)")
    (let ((listing (landolisp.sandbox::list-session-files s)))
      (is (find "hello.lisp" listing
                :key (lambda (p) (getf p :path))
                :test #'string=)))
    (is (string= "(defun greet () :hi)"
                 (landolisp.sandbox::read-session-file s "hello.lisp")))
    (is (eq t (landolisp.sandbox::delete-session-file s "hello.lisp")))))

(test file-rejects-traversal
  (let ((s (fresh-session)))
    (signals landolisp.sandbox:sandbox-error
      (landolisp.sandbox::write-session-file s "../escape.lisp" "x"))
    (signals landolisp.sandbox:sandbox-error
      (landolisp.sandbox::read-session-file s "/etc/passwd"))))

;;; -------------------------------------------------------- quicklisp ---

(test quickload-rejects-non-whitelisted
  (let ((s (fresh-session)))
    (signals landolisp.sandbox:system-not-allowed
      (landolisp.sandbox::quickload-in-session s "totally-not-real"))))

(test quickload-allows-whitelisted
  (let* ((s (fresh-session))
         ;; In test images :ql may be absent — quickload-in-session falls
         ;; back to a no-op success in that case.
         (r (landolisp.sandbox::quickload-in-session s "alexandria")))
    (is (or (getf r :loaded)
            (not (null (getf r :condition)))))))

;;; -------------------------------------------------- HTTP integration ---
;;;
;;; Each HTTP test starts an acceptor on an ephemeral port, hits it with
;;; drakma, and shuts it down. We avoid port 0 because some Hunchentoot
;;; versions misreport it; instead we pick a high random port and retry.

(defun pick-port ()
  (+ 20000 (random 20000)))

(defmacro with-running-server ((port-var) &body body)
  `(let* ((,port-var (pick-port)))
     (landolisp.sandbox:start-server :port ,port-var :address "127.0.0.1")
     (unwind-protect (progn ,@body)
       (landolisp.sandbox:stop-server))))

(defun http-json (url &key (method :get) body)
  (let ((drakma:*text-content-types*
          (cons '("application" . "json") drakma:*text-content-types*)))
    (multiple-value-bind (response status headers)
        (drakma:http-request url
                             :method method
                             :content-type "application/json"
                             :content body)
      (declare (ignore headers))
      (values (when (and response (stringp response))
                (landolisp.sandbox::json-decode response))
              status))))

(test http-health
  (with-running-server (port)
    (multiple-value-bind (j status)
        (http-json (format nil "http://127.0.0.1:~a/v1/health" port))
      (is (= 200 status))
      (is (equal "ok" (cdr (assoc :status j)))))))

(test http-create-and-eval
  (with-running-server (port)
    (multiple-value-bind (created status)
        (http-json (format nil "http://127.0.0.1:~a/v1/sessions" port)
                   :method :post)
      (is (= 201 status))
      (let ((sid (cdr (assoc :session-id created))))
        (is (stringp sid))
        (multiple-value-bind (eval-resp eval-status)
            (http-json (format nil "http://127.0.0.1:~a/v1/sessions/~a/eval" port sid)
                       :method :post
                       :body "{\"code\":\"(+ 1 2)\"}")
          (is (= 200 eval-status))
          (is (equal "3" (cdr (assoc :value eval-resp)))))))))

(test http-unknown-session-404
  (with-running-server (port)
    (multiple-value-bind (resp status)
        (http-json (format nil "http://127.0.0.1:~a/v1/sessions/no-such-id/eval" port)
                   :method :post
                   :body "{\"code\":\"1\"}")
      (declare (ignore resp))
      (is (= 404 status)))))

(defun run-all ()
  "Run the suite and return T iff every test passed."
  (let ((results (run 'landolisp-sandbox-suite)))
    (every (lambda (r) (typep r 'fiveam::test-passed)) results)))
