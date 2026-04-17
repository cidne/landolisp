;;;; json.lisp
;;;;
;;;; Thin wrapper around cl-json. Output uses camelCase keys derived from
;;;; the kebab-case keyword keys we use throughout the server. Input parses
;;;; permissively and then we expose helpers for either spelling.

(in-package #:landolisp.sandbox)

(defun lisp->camel-case (name)
  "Map a Lisp identifier NAME (a string or symbol) like SESSION-ID to the
camelCase string 'sessionId'."
  (let* ((lower (string-downcase (string name)))
         (parts (cl-ppcre:split "-" lower)))
    (if (null (rest parts))
        (first parts)
        (with-output-to-string (s)
          (write-string (first parts) s)
          (dolist (p (rest parts))
            (when (plusp (length p))
              (write-char (char-upcase (char p 0)) s)
              (write-string (subseq p 1) s)))))))

(defun plist->alist (object)
  "Recursively convert plists (cons-with-keyword-car) to alists for cl-json.
Lists, vectors, hash-tables, and scalars pass through untouched (lists are
checked for plist-shape first)."
  (cond
    ((null object) nil)
    ((hash-table-p object)
     (let ((acc nil))
       (maphash (lambda (k v)
                  (push (cons (if (symbolp k)
                                  k
                                  (intern (string-upcase (princ-to-string k))
                                          :keyword))
                              (plist->alist v))
                        acc))
                object)
       (nreverse acc)))
    ((and (consp object) (keywordp (car object)) (evenp (length object)))
     (loop for (k v) on object by #'cddr
           collect (cons k (plist->alist v))))
    ((consp object)
     (mapcar #'plist->alist object))
    (t object)))

(defun json-encode (object)
  "Encode OBJECT to a JSON string. Plists in the form (:key value ...) are
encoded as JSON objects with camelCased keys (e.g. :SESSION-ID -> sessionId)."
  (let ((json:*lisp-identifier-name-to-json* #'lisp->camel-case))
    (with-output-to-string (s)
      (json:encode-json (plist->alist object) s))))

(defun json-decode (string)
  "Parse a JSON STRING into a Lisp value (alist for objects). Returns NIL
for empty input."
  (when (and string (plusp (length string)))
    (let ((json:*json-identifier-name-to-lisp* #'identity))
      (json:decode-json-from-string string))))

(defun json-field (object key)
  "Look up KEY in a parsed JSON OBJECT (alist). KEY may be the camelCase
string we send on the wire (\"sessionId\"), the snake_case alternative
(\"session_id\"), or a Lisp symbol :SESSION-ID. Returns NIL when missing."
  (when (consp object)
    (let* ((wanted (string-downcase (string key)))
           (candidates (list wanted
                             (cl-ppcre:regex-replace-all "-" wanted "")
                             (cl-ppcre:regex-replace-all "_" wanted "")
                             (cl-ppcre:regex-replace-all "-" wanted "_")
                             (cl-ppcre:regex-replace-all "_" wanted "-"))))
      (loop for (k . v) in object
            for k-name = (string-downcase (string k))
            when (or (member k-name candidates :test #'string=)
                     (string= (cl-ppcre:regex-replace-all "[-_]" k-name "")
                              (cl-ppcre:regex-replace-all "[-_]" wanted "")))
              return v))))
