/**
 * On-device Common-Lisp text utilities that power the editor.
 *
 * Placeholder package owned by **Agent B1**. The intended contents (see
 * `docs/ARCHITECTURE.md` §6) are:
 *
 *  - `Tokenizer.kt`  — single-pass lexer producing a stream of tokens
 *                       (paren, symbol, number, string, comment, char).
 *  - `ParenMatcher.kt` — finds the paren matching a given offset and
 *                        returns per-paren nesting depth for rainbow colouring.
 *  - `CompletionDb.kt` — trie over `assets/cl-symbols.json` plus the
 *                        current lesson's `completionSymbols`.
 *
 * Nothing in this package depends on Android or Compose; it's pure Kotlin
 * so the logic can be unit-tested on a plain JVM.
 */
package com.landolisp.lisp
