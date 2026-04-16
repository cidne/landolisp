;;;; runner.lisp
;;;;
;;;; The child-side runner. This file is loaded into a fresh SBCL when the
;;;; saved core is built, and it owns the top-level loop:
;;;;
;;;;   read frame -> eval inside handler-case -> write frame -> repeat.
;;;;
;;;; It deliberately lives in its own package, LANDOLISP.RUNNER, so the
;;;; saved core does NOT pull in the parent's HTTP server, JSON encoder,
;;;; session table, or any other attack surface. The only export is MAIN.
;;;;
;;;; Build (see Dockerfile):
;;;;
;;;;   sbcl --no-sysinit --no-userinit \
;;;;        --load /opt/quicklisp/setup.lisp \
;;;;        --load src/runner.lisp \
;;;;        --eval "(sb-ext:save-lisp-and-die
;;;;                 \"/opt/sandbox/landolisp-runner.core\"
;;;;                 :toplevel #'landolisp.runner:main
;;;;                 :purify t)"

(defpackage #:landolisp.runner
  (:use #:cl)
  (:export #:main))

(in-package #:landolisp.runner)

;;; ---------------------------------------------------------------- tuning ---

(defparameter *runner-hard-timeout-seconds* 30
  "Defence-in-depth: even when the parent has its own wall clock, the
runner aborts evals that exceed this many seconds. Picked to match
ARCHITECTURE.md's 30 s hard cap.")

(defparameter *runner-max-output-chars* (* 256 1024)
  "Per-frame cap on captured stdout/stderr. Anything beyond this is
truncated and a marker line is appended; this prevents a chatty form
like (loop (princ \"x\")) from forcing the parent to buffer megabytes
between the wall-clock and the timeout firing.")

;;; ------------------------------------------------------ framing helpers ---
;;;
;;; Mirror of subprocess.lisp's framing. Kept in this file (not factored)
;;; so the runner core has zero dependencies on the parent's source tree.

(defun write-frame (stream form)
  (let ((body (with-standard-io-syntax
                (let ((*print-readably* nil)
                      (*print-pretty* nil)
                      (*print-circle* t))
                  (prin1-to-string form)))))
    (format stream "~d~%~a~%" (length body) body)
    (finish-output stream)))

(defun read-frame (stream)
  (let* ((len-line (read-line stream))
         (len (parse-integer (string-trim '(#\Space #\Tab #\Return) len-line)))
         (buf (make-string len)))
    (read-sequence buf stream)
    (read-char stream nil nil)
    (with-standard-io-syntax
      (let ((*read-eval* nil))
        (read-from-string buf)))))

(defun %truncate (s)
  "Truncate string S to *RUNNER-MAX-OUTPUT-CHARS*, appending a marker
when truncation occurred."
  (if (<= (length s) *runner-max-output-chars*)
      s
      (concatenate 'string
                   (subseq s 0 *runner-max-output-chars*)
                   (format nil "~%;;; [output truncated at ~a bytes]~%"
                           *runner-max-output-chars*))))

;;; ---------------------------------------------------- per-op handlers ---

(defun %read-all-forms (string)
  "Read every top-level form in STRING. EOF inside a form bubbles up as
a regular READER-ERROR which the dispatcher will wrap into (:error ...)."
  (let ((eof (gensym "EOF"))
        (acc '()))
    (with-input-from-string (s string)
      (loop for form = (read s nil eof)
            until (eq form eof)
            do (push form acc)))
    (nreverse acc)))

(defun %eval-with-capture (code)
  "Evaluate CODE (a string) in the runner's image, capturing
*standard-output* and *error-output* into strings, plus the primary
value of the last form. Returns (values STDOUT STDERR VALUE-STRING
ELAPSED-MS)."
  (let* ((stdout (make-string-output-stream))
         (stderr (make-string-output-stream))
         (start (get-internal-real-time))
         (forms (%read-all-forms code))
         (last nil))
    (let ((*standard-output* stdout)
          (*error-output* stderr))
      (dolist (f forms)
        (setf last (eval f))))
    (values (%truncate (get-output-stream-string stdout))
            (%truncate (get-output-stream-string stderr))
            (with-output-to-string (s) (prin1 last s))
            (round (* 1000
                      (/ (- (get-internal-real-time) start)
                         internal-time-units-per-second))))))

(defun %eval-with-bt-timeout (code seconds)
  "Wrap %EVAL-WITH-CAPTURE in bordeaux-threads:with-timeout. We resolve
the macro symbol at runtime so a runner core without bordeaux-threads
still loads (it just loses the inner timeout and relies entirely on the
parent's wall-clock). The form is constructed with package-qualified
symbols so EVAL finds %EVAL-WITH-CAPTURE in :LANDOLISP.RUNNER regardless
of the current reader package at call time."
  (let ((bt-pkg (find-package :bordeaux-threads)))
    (if (and bt-pkg (find-symbol "WITH-TIMEOUT" bt-pkg))
        (let* ((wt (find-symbol "WITH-TIMEOUT" bt-pkg))
               (helper (find-symbol "%EVAL-WITH-CAPTURE"
                                    (find-package :landolisp.runner))))
          (eval `(,wt (,seconds) (,helper ,code))))
        (%eval-with-capture code))))

(defun %dispatch-eval (code)
  "Run CODE under the runner's defence-in-depth timeout. Returns the
response frame as a list."
  (handler-case
      (multiple-value-bind (stdout stderr value elapsed)
          (%eval-with-bt-timeout code *runner-hard-timeout-seconds*)
        (list :ok stdout stderr value elapsed))
    (error (c)
      (list :error
            (string (type-of c))
            (princ-to-string c)))))

(defun %dispatch-quickload (system-name)
  "Run (ql:quickload SYSTEM-NAME) inside the runner. Captures any chatter
into a string. The parent has already done allow-list enforcement."
  (handler-case
      (let ((log (make-string-output-stream))
            (ql-pkg (find-package :ql)))
        (cond
          ((null ql-pkg)
           (list :error "ql_unavailable"
                 "Quicklisp is not loaded in this runner image."))
          (t
           (let ((*standard-output* log))
             (funcall (find-symbol "QUICKLOAD" ql-pkg)
                      (intern (string-upcase system-name) :keyword)
                      :silent nil))
           (list :ok-quickload (%truncate (get-output-stream-string log))))))
    (error (c)
      (list :error (string (type-of c)) (princ-to-string c)))))

(defun %dispatch-read-file (path)
  "Read PATH (an absolute pathname string the parent already validated)
and return its contents. Used by /v1/sessions/{id}/files/{path} when we
want the canonical bytes to come from the same uid the eval ran under."
  (handler-case
      (list :ok-file (uiop:read-file-string path))
    (error (c)
      (list :error (string (type-of c)) (princ-to-string c)))))

(defun %dispatch-write-file (path content)
  (handler-case
      (progn
        (ensure-directories-exist path)
        (with-open-file (s path :direction :output
                                :if-exists :supersede
                                :if-does-not-exist :create
                                :element-type 'character)
          (write-sequence content s))
        (list :ok-written (length content)))
    (error (c)
      (list :error (string (type-of c)) (princ-to-string c)))))

;;; ------------------------------------------------------- top-level loop ---

(defun dispatch (form)
  "Route a single inbound FORM to the matching handler. Unknown verbs
yield (:error \"bad_verb\" ...)."
  (cond
    ((not (consp form))
     (list :error "bad_frame" (format nil "expected a cons, got ~s" form)))
    (t
     (case (first form)
       (:eval (%dispatch-eval (second form)))
       (:quickload (%dispatch-quickload (second form)))
       (:read-file (%dispatch-read-file (second form)))
       (:write-file (%dispatch-write-file (second form) (third form)))
       (:ping (list :ok-pong))
       (:shutdown :shutdown)
       (otherwise
        (list :error "bad_verb"
              (format nil "unknown verb ~s" (first form))))))))

(defun main ()
  "Top-level loop. Reads frames from *STANDARD-INPUT* until either a
(:shutdown) frame arrives or stdin closes (EOF). Each request gets
exactly one response frame.

Conditions raised by the dispatcher are caught here and reported as
(:error ...) so a pathological input never crashes the runner. Errors
raised by READ-FRAME itself ARE fatal (we'd lose framing alignment)."
  (let ((in *standard-input*)
        (out *standard-output*))
    ;; Send a one-line readiness banner so the parent can verify the
    ;; child made it past startup. (:hello "runner" ...)
    (write-frame out (list :hello "runner"
                           :sbcl (lisp-implementation-version)))
    (loop
      (let ((req (handler-case (read-frame in)
                   (end-of-file () (sb-ext:exit :code 0))
                   (error (c)
                     (write-frame out (list :error
                                            (string (type-of c))
                                            (princ-to-string c)))
                     ;; Framing may be desynced; bail rather than guess.
                     (sb-ext:exit :code 2)))))
        (let ((resp (handler-case (dispatch req)
                      (error (c)
                        (list :error
                              (string (type-of c))
                              (princ-to-string c))))))
          (cond
            ((eq resp :shutdown)
             (write-frame out '(:ok-shutdown))
             (sb-ext:exit :code 0))
            (t (write-frame out resp))))))))
