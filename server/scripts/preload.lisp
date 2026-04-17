;;;; preload.lisp — pre-quickload libraries into the Quicklisp dist at image build time.
;;;;
;;;; Run like:
;;;;   sbcl --non-interactive --dynamic-space-size 4096 \
;;;;        --load /opt/quicklisp/setup.lisp --load /tmp/preload.lisp
;;;;
;;;; Two bands:
;;;;   *REQUIRED* — everything the parent server's ASDF system needs.
;;;;                Failure in this band aborts the build.
;;;;   *OPTIONAL* — curriculum libraries used by lesson exercises.
;;;;                Failure in this band prints a warning and moves on;
;;;;                users can still `(ql:quickload :foo)` at runtime.
;;;;
;;;; We load each library individually so CI surfaces the exact culprit
;;;; instead of a wall of quicklisp chatter.

(defparameter *required*
  '(:alexandria :bordeaux-threads :cl-json :flexi-streams
    :hunchentoot :ironclad :local-time :usocket :fiveam))

(defparameter *optional*
  '(:serapeum :cl-ppcre :trivia :iterate :str :fset
    :cl-who :drakma :cffi :cl-csv :parenscript))

(defun qload-one (system required-p)
  (format t "~&[preload] ~A ~A ..." system (if required-p "(required)" "(optional)"))
  (finish-output)
  (handler-case
      (let ((*standard-output* (make-broadcast-stream)) ; mute quicklisp chatter
            (*trace-output*    (make-broadcast-stream)))
        (ql:quickload system :silent t)
        (format *error-output* " ok~%"))
    (error (c)
      (format *error-output* " FAIL: ~A: ~A~%"
              (type-of c) c)
      (when required-p
        (format *error-output* "~&[preload] required system ~A failed; aborting build~%" system)
        (sb-ext:exit :code 1)))))

(format t "~&[preload] SBCL ~A~%" (lisp-implementation-version))
(format t "~&[preload] Quicklisp dist: ~A~%"
        (ql-dist:name (first (ql-dist:enabled-dists))))

(dolist (s *required*) (qload-one s t))
(dolist (s *optional*) (qload-one s nil))

(format t "~&[preload] done.~%")
