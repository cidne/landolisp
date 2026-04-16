;;;; files.lisp
;;;;
;;;; Per-session file CRUD scoped to SESSION-WORKING-DIRECTORY. All paths are
;;;; relative; absolute paths and any path containing ".." are rejected with
;;;; SANDBOX-ERROR / "invalid_path".

(in-package #:landolisp.sandbox)

(defun safe-relative-path-p (path)
  "Return T iff PATH is a non-empty relative path with no '..' components,
no leading '/', and no NUL or backslashes."
  (and (stringp path)
       (plusp (length path))
       (not (find #\Nul path))
       (not (find #\\ path))
       (not (char= (char path 0) #\/))
       (every (lambda (seg)
                (and (plusp (length seg))
                     (not (string= seg ".."))
                     (not (string= seg "."))))
              (cl-ppcre:split "/" path))))

(defun %resolve (session path)
  "Resolve PATH relative to SESSION's workdir, validating safety first.
Returns an absolute pathname."
  (unless (safe-relative-path-p path)
    (error 'sandbox-error
           :code "invalid_path"
           :message (format nil "rejected path ~s" path)))
  (merge-pathnames path (session-working-directory session)))

(defun list-session-files (session)
  "Return a list of (:path STRING :size INTEGER) plists for every regular
file inside SESSION's workdir (recursive)."
  (touch-session session)
  (ensure-directories-exist (session-working-directory session))
  (let ((root (truename (session-working-directory session)))
        (acc '()))
    (labels ((walk (dir)
               (dolist (entry (uiop:directory-files dir))
                 (let* ((rel (enough-namestring entry root))
                        (size (with-open-file (s entry :direction :input
                                                       :element-type '(unsigned-byte 8)
                                                       :if-does-not-exist nil)
                                (if s (file-length s) 0))))
                   (push (list :path rel :size size) acc)))
               (dolist (sub (uiop:subdirectories dir))
                 (walk sub))))
      (walk root))
    (nreverse acc)))

(defun read-session-file (session path)
  "Return the contents of PATH inside SESSION's workdir as a string.
Signals SANDBOX-ERROR / 'not_found' when missing."
  (touch-session session)
  (let ((p (%resolve session path)))
    (unless (probe-file p)
      (error 'sandbox-error :code "not_found"
                            :message (format nil "no such file ~s" path)))
    (uiop:read-file-string p)))

(defun write-session-file (session path content)
  "Write CONTENT (string) into PATH inside SESSION's workdir, creating any
intermediate directories. Returns a (:path :size) plist."
  (touch-session session)
  (let ((p (%resolve session path)))
    (ensure-directories-exist p)
    (with-open-file (s p :direction :output
                         :if-exists :supersede
                         :if-does-not-exist :create
                         :element-type 'character)
      (write-sequence content s))
    (list :path path :size (length content))))

(defun delete-session-file (session path)
  "Delete PATH inside SESSION's workdir. Signals 'not_found' when missing."
  (touch-session session)
  (let ((p (%resolve session path)))
    (unless (probe-file p)
      (error 'sandbox-error :code "not_found"
                            :message (format nil "no such file ~s" path)))
    (delete-file p)
    t))
