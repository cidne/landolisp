;;;; landolisp-sandbox.asd
;;;;
;;;; ASDF system definition for the Landolisp sandbox HTTP server.
;;;; See docs/ARCHITECTURE.md and docs/API.md for the contract.

(defsystem "landolisp-sandbox"
  :description "HTTP API that runs user-submitted Common Lisp code in supervised SBCL processes."
  :author "Landolisp contributors"
  :license "MIT"
  :version "0.1.0"
  :depends-on ("hunchentoot"
               "cl-json"
               "bordeaux-threads"
               "alexandria"
               "ironclad"
               "local-time"
               "cl-ppcre"
               "flexi-streams"
               "uiop")
  :pathname "src/"
  :serial t
  :components ((:file "package")
               (:file "config")
               (:file "json")
               (:file "util")
               ;; subprocess.lisp must precede sessions.lisp because the
               ;; session struct's runner-related slots are populated via
               ;; helpers defined in subprocess.lisp (spawn-runner,
               ;; kill-runner, runner-alive-p, request-runner). runner.lisp
               ;; is NOT a component here — it lives in the child core.
               (:file "subprocess")
               (:file "sessions")
               (:file "eval")
               (:file "quicklisp")
               (:file "files")
               (:file "routes")
               (:file "server"))
  :in-order-to ((test-op (test-op "landolisp-sandbox/tests"))))

(defsystem "landolisp-sandbox/tests"
  :description "FiveAM smoke tests for the Landolisp sandbox."
  :depends-on ("landolisp-sandbox" "fiveam" "drakma")
  :pathname "tests/"
  :serial t
  :components ((:file "test-smoke")
               (:file "test-curriculum")
               (:file "test-api-contract"))
  :perform (test-op (op c)
                    (uiop:symbol-call :fiveam :run!
                                      (uiop:find-symbol* :landolisp-sandbox-suite
                                                         :landolisp-sandbox.tests))))
