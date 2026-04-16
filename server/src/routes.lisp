;;;; routes.lisp
;;;;
;;;; Hunchentoot dispatchers for every endpoint listed in docs/API.md.
;;;; Every handler returns a JSON body with Content-Type application/json,
;;;; translates known SANDBOX-ERRORs into structured error JSON, and maps
;;;; any other condition to a 500 'internal_error'.

(in-package #:landolisp.sandbox)

;;; ---------------------------------------------- response shape helpers ---

(defun set-json-content-type ()
  (setf (hunchentoot:content-type*) "application/json; charset=utf-8"))

(defun apply-cors-headers ()
  "Add the permissive CORS headers required by the Android app."
  (setf (hunchentoot:header-out :access-control-allow-origin) "*")
  (setf (hunchentoot:header-out :access-control-allow-methods)
        "POST,GET,PUT,DELETE,OPTIONS")
  (setf (hunchentoot:header-out :access-control-allow-headers) "Content-Type"))

(defun respond-json (plist &optional (status 200))
  "Send PLIST as JSON with the given HTTP STATUS."
  (setf (hunchentoot:return-code*) status)
  (set-json-content-type)
  (apply-cors-headers)
  (json-encode plist))

(defun respond-error (code message &optional (status 400))
  (respond-json (error-plist code message) status))

(defmacro with-json-response (&body body)
  "Run BODY, render its return value as JSON if it is a plist/alist, and
attach the standard Content-Type and CORS headers. Errors signalled inside
BODY are translated by WITH-JSON-ERROR-HANDLING."
  (let ((value (gensym "VALUE")))
    `(with-json-error-handling
       (let ((,value (progn ,@body)))
         (cond
           ((stringp ,value)
            (apply-cors-headers)
            (set-json-content-type)
            ,value)
           ((null ,value)
            (apply-cors-headers)
            (set-json-content-type)
            "null")
           (t (respond-json ,value)))))))

(defun sandbox-error-status (c)
  "Pick an appropriate HTTP status for a SANDBOX-ERROR subtype."
  (cond
    ((typep c 'session-not-found) 404)
    ((typep c 'eval-timeout) 408)
    ((typep c 'system-not-allowed) 403)
    ((string= (sandbox-error-code c) "invalid_path") 400)
    ((string= (sandbox-error-code c) "not_found") 404)
    ((string= (sandbox-error-code c) "too_many_sessions") 503)
    ((string= (sandbox-error-code c) "eval_too_large") 413)
    ((string= (sandbox-error-code c) "read_error") 400)
    (t 400)))

(defmacro with-json-error-handling (&body body)
  "Run BODY; translate conditions into JSON error responses."
  `(handler-case (progn ,@body)
     (sandbox-error (c)
       (respond-error (sandbox-error-code c)
                      (sandbox-error-message c)
                      (sandbox-error-status c)))
     (error (c)
       (respond-error "internal_error" (princ-to-string c) 500))))

(defun request-body-string ()
  "Read the current Hunchentoot request's body as a UTF-8 string."
  (let ((raw (hunchentoot:raw-post-data :force-binary t)))
    (when raw
      (flexi-streams:octets-to-string raw :external-format :utf-8))))

(defun json-body ()
  "Parse the current request's body as JSON, returning an alist or NIL."
  (let ((s (request-body-string)))
    (when (and s (plusp (length s)))
      (handler-case (json-decode s)
        (error (c)
          (error 'sandbox-error :code "bad_json"
                                :message (princ-to-string c)))))))

(defun body-field (body key)
  "Look up KEY in a parsed JSON BODY (alist). Delegates to JSON-FIELD which
accepts both camelCase and snake_case spellings."
  (json-field body key))

;;; ------------------------------------------------------------ health ---

(defun handle-health ()
  (with-json-error-handling
    (respond-json
     (list :status "ok"
           :sbcl-version (or (lisp-implementation-version) "unknown")
           :ql-dist (or (ignore-errors
                         (let ((pkg (find-package :ql-dist)))
                           (when pkg
                             (let ((sym (find-symbol "ALL-DISTS" pkg)))
                               (when sym
                                 (format nil "~{~a~^,~}"
                                         (mapcar (lambda (d)
                                                   (funcall
                                                    (find-symbol "NAME" :ql-dist) d))
                                                 (funcall sym))))))))
                        "unknown")
           :uptime-seconds (- (get-universal-time) *server-start-time*)
           :session-count (hash-table-count *sessions*)))))

;;; ------------------------------------------------------------ sessions ---

(defun handle-create-session ()
  (with-json-error-handling
    (let ((s (make-session)))
      (respond-json
       (list :session-id (session-id s)
             :expires-at (iso-from-universal (session-expires-at s)))
       201))))

(defun handle-delete-session (id)
  (with-json-error-handling
    (unless (delete-session id)
      (error 'session-not-found :message (format nil "no session ~a" id)))
    (apply-cors-headers)
    (setf (hunchentoot:return-code*) 204)
    ""))

;;; ------------------------------------------------------------ eval ---

(defun handle-eval (id)
  (with-json-error-handling
    (let* ((session (find-session id))
           (body (json-body))
           (code (body-field body :code)))
      (unless (stringp code)
        (error 'sandbox-error :code "bad_request"
                              :message "missing 'code' string in body"))
      (when (> (length code) (config :max-code-bytes))
        (error 'sandbox-error :code "eval_too_large"
                              :message "code exceeds max-code-bytes"))
      (let ((result (eval-in-session session code)))
        ;; Map timeout conditions to 408 per API.md.
        (let ((c (getf result :condition)))
          (if (and c (equal (getf c :type) "EVAL-TIMEOUT"))
              (respond-json result 408)
              (respond-json result 200)))))))

;;; ------------------------------------------------------------ quickload ---

(defun handle-quickload (id)
  (with-json-error-handling
    (let* ((session (find-session id))
           (body (json-body))
           (system (body-field body :system)))
      (unless (stringp system)
        (error 'sandbox-error :code "bad_request"
                              :message "missing 'system' string in body"))
      (respond-json (quickload-in-session session system)))))

;;; ------------------------------------------------------------ files ---

(defun handle-list-files (id)
  (with-json-error-handling
    (let ((session (find-session id)))
      (respond-json (list-session-files session)))))

(defun handle-read-file (id path)
  (with-json-error-handling
    (let* ((session (find-session id))
           (content (read-session-file session path)))
      (apply-cors-headers)
      (setf (hunchentoot:content-type*) "text/plain; charset=utf-8")
      content)))

(defun handle-write-file (id path)
  (with-json-error-handling
    (let* ((session (find-session id))
           (body (request-body-string)))
      (write-session-file session path (or body ""))
      (apply-cors-headers)
      (setf (hunchentoot:return-code*) 204)
      "")))

(defun handle-delete-file (id path)
  (with-json-error-handling
    (let ((session (find-session id)))
      (delete-session-file session path)
      (apply-cors-headers)
      (setf (hunchentoot:return-code*) 204)
      "")))

;;; ------------------------------------------------ dispatcher installer ---

(defvar *server-start-time* 0
  "Universal-time the acceptor started; used by /v1/health for uptime.")

(defun path-starts-with (prefix script-name)
  "Does SCRIPT-NAME start with PREFIX?"
  (and (>= (length script-name) (length prefix))
       (string= script-name prefix :end1 (length prefix))))

(defun split-path (script-name)
  "Split a URL path into segments, dropping leading/trailing slashes."
  (remove-if (lambda (s) (zerop (length s)))
             (cl-ppcre:split "/" script-name)))

(defun match-files-path (segments)
  "If SEGMENTS looks like (v1 sessions <id> files . rest), return
(values ID PATH-STRING). Otherwise NIL."
  (when (and (>= (length segments) 4)
             (string= (first segments) "v1")
             (string= (second segments) "sessions")
             (string= (fourth segments) "files"))
    (let ((id (third segments))
          (rest (subseq segments 4)))
      (values id (if rest
                     (format nil "~{~a~^/~}" rest)
                     nil)))))

(defun handle-options ()
  "CORS preflight: just return the allow-* headers and a 204."
  (apply-cors-headers)
  (setf (hunchentoot:return-code*) 204)
  "")

(defun main-dispatcher (request)
  "Entry-point dispatcher that every Hunchentoot request flows through."
  (let* ((script (hunchentoot:script-name request))
         (method (hunchentoot:request-method request))
         (segments (split-path script)))
    (cond
      ;; CORS preflight for any path.
      ((eq method :options)
       (lambda () (handle-options)))
      ;; /v1/health
      ((and (eq method :get) (equal script "/v1/health"))
       (lambda () (handle-health)))
      ;; /v1/sessions
      ((and (eq method :post) (equal script "/v1/sessions"))
       (lambda () (handle-create-session)))
      ;; /v1/sessions/{id}
      ((and (eq method :delete)
            (= (length segments) 3)
            (string= (first segments) "v1")
            (string= (second segments) "sessions"))
       (let ((id (third segments)))
         (lambda () (handle-delete-session id))))
      ;; /v1/sessions/{id}/eval
      ((and (eq method :post)
            (= (length segments) 4)
            (string= (first segments) "v1")
            (string= (second segments) "sessions")
            (string= (fourth segments) "eval"))
       (let ((id (third segments)))
         (lambda () (handle-eval id))))
      ;; /v1/sessions/{id}/quickload
      ((and (eq method :post)
            (= (length segments) 4)
            (string= (first segments) "v1")
            (string= (second segments) "sessions")
            (string= (fourth segments) "quickload"))
       (let ((id (third segments)))
         (lambda () (handle-quickload id))))
      ;; /v1/sessions/{id}/files (GET = list, plus per-file CRUD)
      (t
       (multiple-value-bind (id path) (match-files-path segments)
         (cond
           ((and id (null path) (eq method :get))
            (lambda () (handle-list-files id)))
           ((and id path (eq method :get))
            (lambda () (handle-read-file id path)))
           ((and id path (eq method :put))
            (lambda () (handle-write-file id path)))
           ((and id path (eq method :delete))
            (lambda () (handle-delete-file id path)))
           (t
            (lambda ()
              (respond-error "not_found"
                             (format nil "no route for ~a ~a" method script)
                             404)))))))))
