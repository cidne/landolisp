package com.landolisp.lisp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Color palette consumed by the [highlight] function. Lives here rather than in `ui/theme`
 * because it has no Material3 dependency and keeps the highlighter pure-Kotlin testable.
 *
 * Use [light] for light mode, [dark] for dark. The default in production is [dark]; the
 * editor adapts at runtime via the calling composable.
 */
data class SyntaxPalette(
    val foreground: Color,
    val comment: Color,
    val string: Color,
    val number: Color,
    val quote: Color,
    val keyword: Color,
    val unbalanced: Color,
    val pairBackground: Color,
) {
    companion object {
        val Dark = SyntaxPalette(
            foreground = Color(0xFFE9E7F2),
            comment = Color(0xFF7A7990),
            string = Color(0xFF81C784),
            number = Color(0xFF4DD0E1),
            quote = Color(0xFFFFB74D),
            keyword = Color(0xFFB39DDB),
            unbalanced = Color(0xFFE57373),
            pairBackground = Color(0x447C5CFF),
        )

        val Light = SyntaxPalette(
            foreground = Color(0xFF1B1A24),
            comment = Color(0xFF8E8E9A),
            string = Color(0xFF2E7D32),
            number = Color(0xFF00838F),
            quote = Color(0xFFE65100),
            keyword = Color(0xFF5E35B1),
            unbalanced = Color(0xFFC62828),
            pairBackground = Color(0x337C5CFF),
        )
    }
}

/**
 * The hard-coded list of "keyword-like" Common Lisp operators that get bold + accent color.
 * Lessons may extend this set via [highlight]'s `extraKeywords` parameter.
 */
val DefaultKeywords: Set<String> = setOf(
    "defun", "defmacro", "defclass", "defmethod", "defgeneric",
    "defparameter", "defvar", "defconstant", "defpackage", "in-package",
    "let", "let*", "lambda", "if", "cond", "case", "when", "unless",
    "and", "or", "not", "loop", "do", "dolist", "dotimes",
    "return", "return-from", "block", "tagbody", "go",
    "handler-case", "handler-bind", "restart-case",
    "with-slots", "with-open-file", "multiple-value-bind",
)

/**
 * Build a styled [AnnotatedString] for [text] honoring CL syntax. Cheap enough to call on
 * every keystroke for buffers up to ~10 KB; allocations are limited to the token list and
 * the AnnotatedString builder.
 *
 * @param text          The source string to color.
 * @param palette       Color choices.
 * @param cursorOffset  Where the caret is — used to background-highlight the matched
 *                      paren pair around it. Pass `-1` to disable.
 * @param extraKeywords Lesson-specific symbols that should also render as keywords.
 * @param tokens        Pre-computed token list. Pass `null` to let this function tokenize.
 * @param paren         Pre-computed paren analysis. Pass `null` to compute it inline.
 */
fun highlight(
    text: String,
    palette: SyntaxPalette = SyntaxPalette.Dark,
    cursorOffset: Int = -1,
    extraKeywords: Set<String> = emptySet(),
    tokens: List<Token>? = null,
    paren: ParenAnalysis? = null,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")

    val toks = tokens ?: Tokenizer.tokenize(text)
    val analysis = paren ?: ParenMatcher.analyze(toks, cursorOffset)
    val unbalancedSet: Set<Int> = if (analysis.unbalanced.isEmpty()) emptySet()
                                  else analysis.unbalanced.toHashSet()

    val builder = AnnotatedString.Builder(text)

    for (t in toks) {
        when (t.kind) {
            TokenKind.WHITESPACE -> Unit // no styling

            TokenKind.COMMENT -> builder.addStyle(
                SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic),
                t.start, t.end,
            )

            TokenKind.STRING -> builder.addStyle(
                SpanStyle(color = palette.string),
                t.start, t.end,
            )

            TokenKind.NUMBER -> builder.addStyle(
                SpanStyle(color = palette.number),
                t.start, t.end,
            )

            TokenKind.QUOTE -> builder.addStyle(
                SpanStyle(color = palette.quote),
                t.start, t.end,
            )

            TokenKind.SYMBOL -> {
                val lower = t.lexeme.lowercase()
                if (lower in DefaultKeywords || lower in extraKeywords) {
                    builder.addStyle(
                        SpanStyle(color = palette.keyword, fontWeight = FontWeight.Bold),
                        t.start, t.end,
                    )
                }
                // else fall through — default text color is supplied by the editor.
            }

            TokenKind.LPAREN, TokenKind.RPAREN -> {
                if (t.start in unbalancedSet) {
                    builder.addStyle(
                        SpanStyle(
                            color = palette.unbalanced,
                            textDecoration = TextDecoration.Underline,
                        ),
                        t.start, t.end,
                    )
                } else {
                    val depth = depthOfParen(analysis, t.start)
                    builder.addStyle(
                        SpanStyle(color = analysis.colorOfDepth(depth)),
                        t.start, t.end,
                    )
                }
            }
        }
    }

    // Subtle background on the matched-pair around the cursor.
    analysis.matchingPair?.let { range ->
        val openOffset = range.first
        val closeOffset = range.last
        if (openOffset >= 0 && openOffset + 1 <= text.length) {
            builder.addStyle(
                SpanStyle(background = palette.pairBackground),
                openOffset, openOffset + 1,
            )
        }
        if (closeOffset >= 0 && closeOffset + 1 <= text.length) {
            builder.addStyle(
                SpanStyle(background = palette.pairBackground),
                closeOffset, closeOffset + 1,
            )
        }
    }

    return builder.toAnnotatedString()
}

/**
 * Find the [ParenInfo.depth] for a paren at [offset]. Linear search — paren counts in a
 * lesson buffer are small (usually < 200).
 */
private fun depthOfParen(analysis: ParenAnalysis, offset: Int): Int {
    for (p in analysis.parens) {
        if (p.offset == offset) return p.depth.coerceAtLeast(0)
        if (p.offset > offset) break
    }
    return 0
}
