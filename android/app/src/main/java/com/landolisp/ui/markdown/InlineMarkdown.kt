package com.landolisp.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Inline Markdown renderer. Handles **bold**, *italic*, and `code`.
 *
 * Implemented as a tiny scanner — not a full parser — so it tolerates malformed input
 * by falling back to literal characters when no closing delimiter is found.
 */
object InlineMarkdown {

    private val Bold = SpanStyle(fontWeight = FontWeight.Bold)
    private val Italic = SpanStyle(fontStyle = FontStyle.Italic)
    private val Code = SpanStyle(fontFamily = FontFamily.Monospace)

    fun render(text: String, builder: AnnotatedString.Builder) {
        var i = 0
        while (i < text.length) {
            val rest = text.length - i
            when {
                rest >= 4 && text.startsWith("**", i) -> {
                    val close = text.indexOf("**", i + 2)
                    if (close >= 0) {
                        builder.pushStyle(Bold)
                        builder.append(text, i + 2, close)
                        builder.pop()
                        i = close + 2
                    } else {
                        builder.append(text[i]); i++
                    }
                }
                text[i] == '*' -> {
                    val close = text.indexOf('*', i + 1)
                    if (close > i + 1) {
                        builder.pushStyle(Italic)
                        builder.append(text, i + 1, close)
                        builder.pop()
                        i = close + 1
                    } else {
                        builder.append(text[i]); i++
                    }
                }
                text[i] == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i) {
                        builder.pushStyle(Code)
                        builder.append(text, i + 1, close)
                        builder.pop()
                        i = close + 1
                    } else {
                        builder.append(text[i]); i++
                    }
                }
                else -> {
                    builder.append(text[i]); i++
                }
            }
        }
    }
}
