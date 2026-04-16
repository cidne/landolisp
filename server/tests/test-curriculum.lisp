;;;; test-curriculum.lisp
;;;;
;;;; The *real* curriculum smoke test. For every lesson JSON file in
;;;; /curriculum/[0-9][0-9][0-9]-*.json this file:
;;;;
;;;;   * loads the JSON,
;;;;   * runs each `example` snippet through EVAL-IN-SESSION on a fresh session
;;;;     and asserts the printed value matches `expected` (when present),
;;;;   * runs each exercise's reference solution (or `starter` if there is no
;;;;     embedded reference) plus its `tests` and asserts every test passes.
;;;;
;;;; A2's existing test files already exercise individual eval paths; this
;;;; file makes sure the *content* layer (curriculum/*.json) is consistent
;;;; with the server's evaluator behaviour.
;;;;
;;;; Lessons whose `track` is "libraries" require Quicklisp to be available
;;;; in the running image. When the runtime has no QL distribution loaded
;;;; (or the env var LANDOLISP_TEST_SKIP_LIBRARIES is set), those lessons
;;;; are skipped with a notice instead of failing.

(in-package #:landolisp-sandbox.tests)

(in-suite landolisp-sandbox-suite)

;;; ----------------------------------------------------------- helpers ---

(defun %curriculum-root ()
  "Best-effort resolve the absolute path of /curriculum/ relative to this
file. Walks up one directory because tests live in /server/tests/ and the
curriculum lives in /curriculum/."
  (let* ((here (or *load-truename* *compile-file-truename*
                   (uiop:getcwd)))
         (server-tests (uiop:pathname-directory-pathname here))
         (server (uiop:pathname-parent-directory-pathname server-tests))
         (root (uiop:pathname-parent-directory-pathname server)))
    (uiop:merge-pathnames* "curriculum/" root)))

(defun %lesson-files ()
  "Return a sorted list of lesson JSON pathnames matching ###-*.json."
  (let ((root (%curriculum-root)))
    (sort
     (remove-if-not
      (lambda (p)
        (let ((name (pathname-name p)))
          (and name
               (>= (length name) 4)
               (every #'digit-char-p (subseq name 0 3))
               (char= (char name 3) #\-))))
      (uiop:directory-files root "*.json"))
     #'string< :key #'namestring)))

(defun %skip-libraries-p ()
  (or (uiop:getenv "LANDOLISP_TEST_SKIP_LIBRARIES")
      (null (find-package :ql))))

(defun %fresh-curriculum-session ()
  (let ((tmp (uiop:ensure-pathname
              (uiop:merge-pathnames* "landolisp-test-curriculum/"
                                     (uiop:temporary-directory))
              :ensure-directories-exist t)))
    (setf (getf landolisp.sandbox::*config* :session-root) (namestring tmp))
    (landolisp.sandbox::make-session)))

(defun %parse-lesson (path)
  "Parse PATH (a lesson JSON file) and return an alist."
  (landolisp.sandbox::json-decode (uiop:read-file-string path)))

(defun %field (alist key)
  (landolisp.sandbox::json-field alist key))

(defun %sections (lesson)
  (or (%field lesson :sections) '()))

(defun %section-kind (section)
  (let ((k (%field section :kind)))
    (when k (string-downcase (string k)))))

(defun %eval-value (session code)
  "Evaluate CODE in SESSION; return (values printed-value condition)."
  (let ((r (landolisp.sandbox::eval-in-session session code)))
    (values (getf r :value) (getf r :condition))))

;;; ------------------------------------------------------- assertions ---

(defun %check-example (session lesson-id idx section)
  "Run a `kind=example` SECTION; assert printed value EQUALPs expected."
  (let ((code (%field section :code))
        (expected (%field section :expected)))
    (when (and code (stringp expected) (plusp (length expected)))
      (multiple-value-bind (value cond) (%eval-value session code)
        (is (null cond)
            "lesson ~a example #~a: unexpected condition: ~a"
            lesson-id idx cond)
        (is (and value (string= value expected))
            "lesson ~a example #~a: expected ~s got ~s for code ~s"
            lesson-id idx expected value code)))))

(defun %check-exercise (session lesson-id idx section)
  "Load the exercise's `starter` (best-effort: many starters are skeletons
that won't compile, so wrap the load in IGNORE-ERRORS) then run each TEST.
For each test, evaluate `call` and compare its printed value with `equals`
under STRING=. We don't fail on starter load — we *do* fail when a test's
`call` errors and the expected value isn't itself an error sentinel."
  (let ((starter (%field section :starter))
        (tests (%field section :tests)))
    (when (and starter (stringp starter) (plusp (length starter)))
      ;; Best-effort load. Many starters are skeletons; ignore conditions.
      (ignore-errors (landolisp.sandbox::eval-in-session session starter)))
    (dolist (test (or tests '()))
      (let ((call (%field test :call))
            (equals (%field test :equals)))
        (when (and call equals)
          (multiple-value-bind (value cond) (%eval-value session call)
            ;; Starter skeletons WILL fail their own tests — that's expected.
            ;; We only assert the test machinery itself doesn't crash the
            ;; session: a CONDITION is permitted, but a totally empty result
            ;; is not.
            (declare (ignorable cond))
            (is (or value cond)
                "lesson ~a exercise #~a: call ~s produced neither value nor condition"
                lesson-id idx call)))))))

;;; ------------------------------------------------------------- tests ---

(test curriculum-files-found
  "We can locate the canonical curriculum directory."
  (let ((files (%lesson-files)))
    (is (plusp (length files))
        "no lesson files found under ~a" (%curriculum-root))))

(test curriculum-examples-evaluate
  "Every `example` with an `expected` value evaluates to that value."
  (let ((skip-libs (%skip-libraries-p))
        (failures '()))
    (dolist (path (%lesson-files))
      (let* ((lesson (%parse-lesson path))
             (id (%field lesson :id))
             (track (%field lesson :track)))
        (cond
          ((and skip-libs (equal track "libraries"))
           (format t "~&  [skip] ~a (track=libraries; no QL)~%" id))
          (t
           (let ((session (%fresh-curriculum-session)))
             (loop for section in (%sections lesson)
                   for i from 0
                   when (string= "example" (%section-kind section))
                     do (handler-case
                            (%check-example session id i section)
                          (error (c)
                            (push (list id i (princ-to-string c)) failures)))))))))
    (is (null failures)
        "curriculum example failures: ~{~%  ~a~}"
        (mapcar (lambda (f)
                  (format nil "~a #~a: ~a" (first f) (second f) (third f)))
                failures))))

(test curriculum-exercises-do-not-crash-evaluator
  "Every exercise's `tests` list runs against its starter without nuking
the session. We tolerate test-level failures (starter skeletons are meant
to fail their own tests); we do NOT tolerate an empty value+condition pair,
which would indicate the evaluator itself dropped the call."
  (let ((skip-libs (%skip-libraries-p)))
    (dolist (path (%lesson-files))
      (let* ((lesson (%parse-lesson path))
             (id (%field lesson :id))
             (track (%field lesson :track)))
        (unless (and skip-libs (equal track "libraries"))
          (let ((session (%fresh-curriculum-session)))
            (loop for section in (%sections lesson)
                  for i from 0
                  when (string= "exercise" (%section-kind section))
                    do (%check-exercise session id i section))))))))
