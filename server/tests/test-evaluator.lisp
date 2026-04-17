;;;; test-evaluator.lisp
;;;;
;;;; Direct unit tests of EVAL-IN-SESSION (no HTTP). Defines / extends the
;;;; LANDOLISP-SANDBOX-SUITE so the standalone test system shares a suite
;;;; with the bundled smoke tests.

(defpackage #:landolisp-sandbox.tests
  (:use #:cl #:fiveam)
  (:export #:landolisp-sandbox-suite))

(in-package #:landolisp-sandbox.tests)

(unless (fiveam:get-test 'landolisp-sandbox-suite)
  (def-suite landolisp-sandbox-suite
    :description "All smoke tests for landolisp-sandbox."))

(in-suite landolisp-sandbox-suite)

(defun %fresh-session-for-eval ()
  (let ((tmp (uiop:ensure-pathname
              (uiop:merge-pathnames* "landolisp-test-eval/"
                                     (uiop:temporary-directory))
              :ensure-directories-exist t)))
    (setf (getf landolisp.sandbox::*config* :session-root) (namestring tmp))
    (landolisp.sandbox::make-session)))

(test eval-plus
  "(+ 1 2) prints as \"3\"."
  (let* ((s (%fresh-session-for-eval))
         (r (landolisp.sandbox::eval-in-session s "(+ 1 2)")))
    (is (string= "3" (getf r :value)))
    (is (null (getf r :condition)))))

(test eval-error-non-nil-condition
  "(error \"boom\") returns a non-nil :condition plist."
  (let* ((s (%fresh-session-for-eval))
         (r (landolisp.sandbox::eval-in-session s "(error \"boom\")")))
    (is (not (null (getf r :condition))))))

(test eval-infinite-loop-times-out
  "(loop) hits the configured timeout and returns an EVAL-TIMEOUT condition."
  (let ((saved (getf landolisp.sandbox::*config* :eval-timeout-seconds)))
    (unwind-protect
         (progn
           (setf (getf landolisp.sandbox::*config* :eval-timeout-seconds) 1)
           (let* ((s (%fresh-session-for-eval))
                  (r (landolisp.sandbox::eval-in-session s "(loop)")))
             (is (not (null (getf r :condition))))
             (is (string= "EVAL-TIMEOUT"
                          (getf (getf r :condition) :type)))))
      (setf (getf landolisp.sandbox::*config* :eval-timeout-seconds) saved))))
