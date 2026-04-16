/**
 * On-device Common-Lisp text utilities that power the editor.
 *
 * Pure-Kotlin (no Android, no Compose imports) so every module here is unit-testable on a
 * plain JVM. Owned by **Agent B1**.
 *
 * Modules:
 *
 *  - [com.landolisp.lisp.Tokenizer] — single-pass lexer producing a stream of [Token]s
 *    (paren, symbol, number, string, comment, quote, whitespace). Robust against partial
 *    input — an unterminated string at EOF still emits a STRING token covering the rest of
 *    the buffer; this is essential because the editor re-tokenizes on every keystroke.
 *
 *  - [com.landolisp.lisp.ParenMatcher] — given a token stream and a cursor offset, returns
 *    a [ParenAnalysis] containing per-paren depth, open→close pairs, the matched pair
 *    around the cursor (if any), the list of unmatched parens (used for the red squiggle),
 *    and the cursor's nesting depth. Single linear pass + a small stack.
 *
 *  - [com.landolisp.lisp.Highlighter] — `highlight(text, palette, ...)` builds an
 *    `AnnotatedString` with per-token coloring: muted-italic comments, green strings, cyan
 *    numbers, orange quote sugars, bold-accent keywords (built-in CL set + lesson-supplied
 *    extensions), rainbow parens via [ParenMatcher.colorOfDepth], red-underline unbalanced
 *    parens, and a subtle background on the matched pair around the cursor. Lives next to
 *    its color-palette type [SyntaxPalette].
 *
 *  - [com.landolisp.lisp.CompletionEngine] — prefix-search completion over the bundled
 *    `assets/cl-symbols.json` set, with a per-call lessonHints "boost" so the current
 *    lesson's `completionSymbols` always rank first. Cached process-wide via
 *    [CompletionEngine.Companion.loadFromAssets] (off the main thread).
 *
 * Nothing here depends on Android or Compose; the editor wires these up in
 * `com.landolisp.ui.editor`.
 */
package com.landolisp.lisp
