;;;; test-runner-isolation.lisp
;;;;
;;;; OS-level isolation tests added by B4. These exercise the firejail /
;;;; rlimit / network-namespace path and therefore only make sense inside
;;;; the production-style image. They are SKIPPED outside of Docker —
;;;; gated on the env var INSIDE_DOCKER=1 which the Dockerfile sets in
;;;; the runtime stage.

(in-package #:landolisp-sandbox.tests)

(in-suite landolisp-sandbox-suite)

(defun %inside-docker-p ()
  "T iff the env var INSIDE_DOCKER is set to a truthy value. The Dockerfile
sets it to '1' in the runtime stage; bare-metal dev environments leave it
unset and the tests below short-circuit to a trivial pass."
  (let ((v (uiop:getenv "INSIDE_DOCKER")))
    (and v (member v '("1" "true" "yes" "TRUE" "YES")
                   :test #'string=))))

(defmacro skip-unless-in-docker (&body body)
  `(if (not (%inside-docker-p))
       (progn
         (format *error-output*
                 "~&;; skipped (INSIDE_DOCKER unset; isolation tests require firejail)~%")
         (is t))
       (progn ,@body)))

(defun %fresh-isolated-session ()
  (let ((tmp (uiop:ensure-pathname
              (uiop:merge-pathnames* "landolisp-test-isolation/"
                                     (uiop:temporary-directory))
              :ensure-directories-exist t)))
    (setf (getf landolisp.sandbox::*config* :session-root) (namestring tmp))
    (landolisp.sandbox::make-session)))

(test runner-cannot-exceed-address-space
  "Allocate a string larger than the rlimit and expect the runner to
report a memory-allocation failure rather than swallowing the host."
  (skip-unless-in-docker
    (let* ((s (%fresh-isolated-session))
           ;; ~700 MB > 512 MB rlimit-as. The runner's allocation should
           ;; fail before this string materialises.
           (code "(make-array 734003200 :element-type 'character)")
           (r (landolisp.sandbox::eval-in-session s code)))
      ;; Either the runner reports a STORAGE-CONDITION-flavoured error,
      ;; or the runner crashes outright (SIGKILL'd by the kernel) and we
      ;; surface session_crashed. Both are correct outcomes.
      (let ((c (getf r :condition)))
        (is (not (null c)))
        (is (or (search "STORAGE" (getf c :type))
                (search "HEAP-EXHAUSTED" (getf c :type))
                (search "MEMORY" (getf c :message))
                (string= "session_crashed" (getf c :type))))))))

(test runner-has-no-network
  "(:net=none) means usocket / sb-bsd-sockets cannot reach 1.1.1.1.
We expect either an unreachable error or a denied-syscall error."
  (skip-unless-in-docker
    (let* ((s (%fresh-isolated-session))
           (code "(handler-case
                    (progn (require :sb-bsd-sockets)
                           (let ((sock (make-instance
                                        'sb-bsd-sockets:inet-socket
                                        :type :stream :protocol :tcp)))
                             (sb-bsd-sockets:socket-connect
                              sock #(1 1 1 1) 80)
                             (sb-bsd-sockets:socket-close sock)
                             :connected))
                    (error (c) (princ-to-string c)))")
           (r (landolisp.sandbox::eval-in-session s code)))
      ;; Either a clean error string came back as :value, or the eval
      ;; threw outright. Either way, we MUST NOT see :connected.
      (let ((value (getf r :value))
            (c (getf r :condition)))
        (is (not (and value (search "CONNECTED" (string-upcase value)))))
        ;; The successful denial path returns a printable value containing
        ;; a network-error string ("Network is unreachable", "Operation
        ;; not permitted", etc.). The unsuccessful denial path is when
        ;; eval comes back :ok with :value "CONNECTED" — which the assert
        ;; above already catches.
        (is (or c
                (search "UNREACHABLE" (string-upcase (or value "")))
                (search "PERMITTED" (string-upcase (or value "")))
                (search "DENIED" (string-upcase (or value "")))
                (search "REFUSED" (string-upcase (or value "")))
                (search "ERROR" (string-upcase (or value "")))))))))

(test runner-fsize-rlimit
  "rlimit-fsize=10 MiB: writing a 100 MiB file from inside user code
must fail before the kernel is willing to materialise it."
  (skip-unless-in-docker
    (let* ((s (%fresh-isolated-session))
           (code "(handler-case
                    (with-open-file (o \"big.dat\"
                                       :direction :output
                                       :if-exists :supersede
                                       :element-type '(unsigned-byte 8))
                      (let ((buf (make-array 1048576
                                             :element-type '(unsigned-byte 8))))
                        (dotimes (i 200) (write-sequence buf o)))
                      :wrote-it-all)
                    (error (c) (princ-to-string c)))")
           (r (landolisp.sandbox::eval-in-session s code)))
      (let ((value (getf r :value))
            (c (getf r :condition)))
        (is (not (and value (search "WROTE-IT-ALL" (string-upcase value)))))
        (is (or c
                (search "FILE" (string-upcase (or value "")))
                (search "LIMIT" (string-upcase (or value "")))
                (search "SIZE" (string-upcase (or value "")))))))))
