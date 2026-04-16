;;;; landolisp-sandbox-tests.asd
;;;;
;;;; Standalone test system. The main landolisp-sandbox.asd also defines an
;;;; inline test system (landolisp-sandbox/tests) wired into ASDF:TEST-SYSTEM.
;;;; This file exists for the developer workflow described in the task brief
;;;; and lets you run the legacy split files (test-evaluator + test-handlers)
;;;; independently of the smoke suite.

(defsystem "landolisp-sandbox-tests"
  :description "FiveAM unit + integration tests for landolisp-sandbox."
  :depends-on ("landolisp-sandbox" "fiveam" "drakma")
  :pathname "."
  :serial t
  :components ((:file "test-evaluator")
               (:file "test-handlers"))
  :perform (test-op (op c)
                    (declare (ignore op c))
                    (uiop:symbol-call :fiveam :run!
                                      (uiop:find-symbol* :landolisp-sandbox-suite
                                                         :landolisp-sandbox.tests))))
