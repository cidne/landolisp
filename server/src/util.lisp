;;;; util.lisp
;;;;
;;;; Small shared helpers that are not JSON-specific: ISO-8601 timestamps,
;;;; UUID generation (RFC 4122 v4 backed by ironclad's CSPRNG when present),
;;;; and a couple of error-shaping helpers used by the HTTP handlers.

(in-package #:landolisp.sandbox)

;;; ---------------------------------------------------------- timestamps ---

(defparameter *iso-format*
  '((:year 4) #\- (:month 2) #\- (:day 2)
    #\T
    (:hour 2) #\: (:min 2) #\: (:sec 2)
    #\Z)
  "LOCAL-TIME format pattern matching the ISO-8601 strings advertised in
docs/API.md (e.g. 2026-04-16T14:00:00Z, always UTC).")

(defun iso-now ()
  "Return the current time as an ISO-8601 UTC string."
  (local-time:format-timestring nil (local-time:now)
                                :format *iso-format*
                                :timezone local-time:+utc-zone+))

(defun iso-from-universal (universal)
  "Format a universal-time integer UNIVERSAL as an ISO-8601 UTC string."
  (local-time:format-timestring
   nil
   (local-time:universal-to-timestamp universal)
   :format *iso-format*
   :timezone local-time:+utc-zone+))

;;; ---------------------------------------------------------------- uuid ---

(defun %random-bytes (n)
  "Return a vector of N pseudo-random bytes. Uses ironclad's CSPRNG when the
package is loaded, otherwise falls back to CL:RANDOM (good enough for tests
but not for security-sensitive use; B4 should re-evaluate)."
  (let* ((bytes (make-array n :element-type '(unsigned-byte 8)))
         (ironclad (find-package :ironclad)))
    (cond
      ((and ironclad (find-symbol "MAKE-PRNG" ironclad))
       (let* ((make (find-symbol "MAKE-PRNG" ironclad))
              (rb (find-symbol "RANDOM-DATA" ironclad))
              (prng (funcall make :fortuna)))
         (replace bytes (funcall rb n prng))))
      (t
       (dotimes (i n) (setf (aref bytes i) (random 256)))))
    bytes))

(defun make-uuid ()
  "Generate a v4-style UUID string per RFC 4122."
  (let ((bytes (%random-bytes 16)))
    ;; version 4
    (setf (aref bytes 6) (logior #x40 (logand (aref bytes 6) #x0f)))
    ;; variant 10xx
    (setf (aref bytes 8) (logior #x80 (logand (aref bytes 8) #x3f)))
    (format nil "~{~2,'0x~}-~{~2,'0x~}-~{~2,'0x~}-~{~2,'0x~}-~{~2,'0x~}"
            (coerce (subseq bytes 0 4) 'list)
            (coerce (subseq bytes 4 6) 'list)
            (coerce (subseq bytes 6 8) 'list)
            (coerce (subseq bytes 8 10) 'list)
            (coerce (subseq bytes 10 16) 'list))))

;;; ------------------------------------------------- error shape helpers ---

(defun error-plist (code message)
  "Build the standard JSON error body."
  (list :error code :message message))

(defun condition->plist (c)
  "Render a Lisp condition C as a small plist suitable for JSON."
  (when c
    (list :type (string (type-of c))
          :message (princ-to-string c))))
