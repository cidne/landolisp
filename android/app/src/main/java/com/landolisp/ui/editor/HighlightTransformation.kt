package com.landolisp.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.landolisp.lisp.SyntaxPalette
import com.landolisp.lisp.highlight

/**
 * [VisualTransformation] that applies CL syntax highlighting on the fly.
 *
 * Because we never reshape the text (only color it), the [OffsetMapping] is identity, which
 * is what keeps the cursor / selection from drifting after edits.
 *
 * Constructed fresh per recomposition — the upstream composable is responsible for
 * remembering it across keystrokes if profiling shows it matters; for the buffer sizes the
 * editor handles, the cost is dominated by the tokenize pass anyway.
 *
 * @param palette       Foreground / accent / etc. colors.
 * @param cursorOffset  Pass through from the editor so the matched-pair background is
 *                      anchored at the caret.
 * @param extraKeywords Lesson-specific symbols to render bold + accent.
 */
class HighlightTransformation(
    private val palette: SyntaxPalette,
    private val cursorOffset: Int,
    private val extraKeywords: Set<String> = emptySet(),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val styled = highlight(
            text = text.text,
            palette = palette,
            cursorOffset = cursorOffset,
            extraKeywords = extraKeywords,
        )
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
