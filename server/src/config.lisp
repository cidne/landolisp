;;;; config.lisp
;;;;
;;;; Runtime configuration for the sandbox. All knobs live in *config* so
;;;; tests and Docker entry-points can mutate them in one place.

(in-package #:landolisp.sandbox)

(defparameter *default-allowed-systems*
  '("alexandria"
    "serapeum"
    "cl-ppcre"
    "drakma"
    "hunchentoot"
    "bordeaux-threads"
    "usocket"
    "local-time"
    "ironclad"
    "cl-json"
    "trivia"
    "fset"
    "cffi"
    "iterate"
    "cl-who"
    "parenscript"
    "cl-csv"
    "postmodern"
    "fiveam"
    "str")
  "Quicklisp systems clients are permitted to load via /quickload.
B4 will likely tighten this list when subprocess isolation lands.")

(defparameter *config*
  (list :host "0.0.0.0"
        :port 8080
        :eval-timeout-seconds 10
        :hard-timeout-seconds 30
        :session-ttl-seconds (* 30 60)
        :max-sessions 32
        :max-code-bytes (* 64 1024)
        :session-root "/var/sandbox/"
        :reaper-interval-seconds 60
        :allowed-systems *default-allowed-systems*)
  "Mutable plist holding all server tunables. Use CONFIG and (SETF CONFIG)
to read/write individual keys.")

(defun config (key &optional default)
  "Return the value of KEY in *CONFIG*, or DEFAULT when absent."
  (getf *config* key default))

(defun (setf config) (new-value key)
  "Update KEY in *CONFIG* to NEW-VALUE."
  (setf (getf *config* key) new-value)
  new-value)
