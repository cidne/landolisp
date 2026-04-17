package com.landolisp.ui.editor

/**
 * Pure-text result returned by [computeBalancedEdit] / [completePartial]: the rewritten
 * text and the caret position to set after the rewrite.
 */
data class BalancedEdit(val text: String, val cursor: Int)

/**
 * Pure-text core of the editor's auto-balance algorithm. Returns the rewritten text +
 * caret if any smart behavior fired, or `null` to mean "pass the proposed edit through
 * untouched".
 *
 * Contract:
 *  - Single-char insert of `(` at the caret → append `)` and back the cursor up.
 *  - Single-char insert of `"` at the caret (and not inside an existing string) → append
 *    matching `"`.
 *  - Single-char insert of `)` when the next char is already `)` → swallow input; caret
 *    advances over the existing `)`.
 *  - Single-char delete of `(` or `"` whose closer immediately follows → also delete the
 *    closer.
 *
 * No Compose dependency — JVM-testable in the unit-test source set.
 */
fun computeBalancedEdit(
    prevText: String,
    newText: String,
    newCursor: Int,
    behavior: EditorBehavior,
): BalancedEdit? {
    val delta = newText.length - prevText.length

    // ---- single-char insert at the caret -------------------------------
    if (delta == 1 && newCursor in 1..newText.length) {
        val ch = newText[newCursor - 1]
        // Verify the rest of the buffer is unchanged: prefix and suffix must match.
        val prefixOk = prevText.regionMatches(0, newText, 0, newCursor - 1)
        val suffixOk = prevText.regionMatches(
            newCursor - 1, newText, newCursor, prevText.length - (newCursor - 1),
        )
        if (prefixOk && suffixOk) {
            when (ch) {
                '(' -> if (behavior.autoCloseParen) {
                    return BalancedEdit(
                        text = newText.substring(0, newCursor) + ")" + newText.substring(newCursor),
                        cursor = newCursor,
                    )
                }
                '"' -> if (behavior.autoCloseString && !insideString(prevText, newCursor - 1)) {
                    return BalancedEdit(
                        text = newText.substring(0, newCursor) + "\"" + newText.substring(newCursor),
                        cursor = newCursor,
                    )
                }
                ')' -> if (behavior.skipOverCloseParen) {
                    if (newCursor < newText.length && newText[newCursor] == ')') {
                        return BalancedEdit(
                            text = newText.substring(0, newCursor - 1) + newText.substring(newCursor),
                            cursor = newCursor,
                        )
                    }
                }
            }
        }
    }

    // ---- single-char delete (backspace) --------------------------------
    if (delta == -1 && behavior.deletePairOnBackspace) {
        val deletedAt = newCursor
        if (deletedAt in 0 until prevText.length) {
            val deletedChar = prevText[deletedAt]
            val opening = deletedChar == '(' || deletedChar == '"'
            val prefixOk = prevText.regionMatches(0, newText, 0, deletedAt)
            val suffixOk = prevText.regionMatches(
                deletedAt + 1, newText, deletedAt, prevText.length - (deletedAt + 1),
            )
            if (opening && prefixOk && suffixOk && deletedAt < newText.length) {
                val nextChar = newText[deletedAt]
                val isClosingPair = (deletedChar == '(' && nextChar == ')') ||
                    (deletedChar == '"' && nextChar == '"')
                if (isClosingPair) {
                    return BalancedEdit(
                        text = newText.substring(0, deletedAt) + newText.substring(deletedAt + 1),
                        cursor = deletedAt,
                    )
                }
            }
        }
    }
    return null
}

/**
 * Pure-text completion-splice helper: strips the partial symbol immediately before
 * [cursor] and replaces it with [name]. Caret lands just after the inserted name.
 */
fun completePartial(text: String, cursor: Int, name: String): BalancedEdit {
    val safeCursor = cursor.coerceIn(0, text.length)
    var start = safeCursor
    while (start > 0 && isSymbolChar(text[start - 1])) start--
    val newText = text.substring(0, start) + name + text.substring(safeCursor)
    return BalancedEdit(newText, start + name.length)
}

/**
 * Cheap "are we inside a string literal at this offset?" check. Counts unescaped `"` from
 * the start of the line up to (but not including) [offset]; an odd count means we're
 * inside. Good enough for the autoinsert decision; a fuller answer would need the
 * tokenizer but this is a hot path on every keystroke.
 */
internal fun insideString(text: String, offset: Int): Boolean {
    val safe = offset.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', (safe - 1).coerceAtLeast(0)) + 1
    var inStr = false
    var i = lineStart
    while (i < safe) {
        val ch = text[i]
        if (inStr) {
            if (ch == '\\' && i + 1 < safe) { i += 2; continue }
            if (ch == '"') inStr = false
        } else {
            if (ch == ';') return false // rest of line is comment
            if (ch == '"') inStr = true
        }
        i++
    }
    return inStr
}

/**
 * Symbol character class for completion-splice purposes. Slightly stricter than the
 * tokenizer (we don't allow `(` or `'` to be part of a partial); the goal is "what would
 * the user consider the word they're typing".
 */
internal fun isSymbolChar(c: Char): Boolean {
    if (c.isLetterOrDigit()) return true
    return when (c) {
        '-', '_', '*', '+', '/', '<', '>', '=', '?', '!', ':', '&', '.', '%', '$' -> true
        else -> false
    }
}
