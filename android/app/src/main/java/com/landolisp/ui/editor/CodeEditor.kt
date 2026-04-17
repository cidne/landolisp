package com.landolisp.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.landolisp.lisp.CompletionEntry
import com.landolisp.lisp.SyntaxPalette
import com.landolisp.lisp.TokenKind
import com.landolisp.lisp.Tokenizer
import com.landolisp.ui.theme.CodeTextStyle

/**
 * Common-Lisp aware code editor.
 *
 * Public contract is preserved from the original A1 stub: takes a [CodeEditorState] and an
 * optional [onSubmit]. State carries opt-in feature toggles via [EditorBehavior]; pass
 * [EditorBehavior.Off] to disable every smart behavior at once (useful in tests / a11y).
 *
 * Implementation notes:
 *  - Highlighting goes through [HighlightTransformation] so coloring rebuilds without
 *    perturbing the cursor / selection.
 *  - Auto-balance is implemented by intercepting [BasicTextField]'s `onValueChange`,
 *    diffing against the prior [TextFieldValue], and rewriting the proposed value before
 *    forwarding it to the host.
 *  - Completion fires whenever the partial token under the caret is non-empty and the
 *    engine returns at least one suggestion.
 */
@Composable
fun CodeEditor(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    val palette = if (isSystemInDarkTheme()) SyntaxPalette.Dark else SyntaxPalette.Light
    val baseStyle = LocalTextStyle.current.merge(CodeTextStyle).copy(
        color = palette.foreground,
    )

    // Track the prior text snapshot in a single-cell `Ref` so we can diff to detect
    // single-char inserts / backspaces. We deliberately do NOT use `mutableStateOf` here:
    // the ref is private bookkeeping, never observed for recomposition, and writing it
    // mid-composition is safe.
    val previousRef = remember { Ref(state.text) }
    // If the host pushes brand-new text we didn't produce (e.g. lesson reset, programmatic
    // undo), resync our snapshot so the next edit diffs against the right baseline.
    if (previousRef.value.text != state.text.text) {
        previousRef.value = state.text
    }

    val transformation = remember(state.text.selection.start, palette, state.keywords) {
        HighlightTransformation(
            palette = palette,
            cursorOffset = state.text.selection.start,
            extraKeywords = state.keywords,
        )
    }

    val onValueChange: (TextFieldValue) -> Unit = { proposed ->
        val rewritten = applyAutoBalance(previousRef.value, proposed, state.behavior)
        previousRef.value = rewritten
        state.onTextChange(rewritten)
    }

    Row(modifier = modifier.fillMaxWidth()) {
        if (state.behavior.showDepthGutter) {
            DepthGutter(
                text = state.text.text,
                cursorOffset = state.text.selection.start,
                color = palette.keyword,
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            BasicTextField(
                value = state.text,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = baseStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (onSubmit != null) ImeAction.Send else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSubmit?.invoke() },
                ),
            )

            if (state.behavior.completionPopup) {
                CompletionPopup(state = state, onAccept = { entry ->
                    val updated = insertCompletion(state.text, entry.name)
                    previousRef.value = updated
                    state.onTextChange(updated)
                })
            }
        }
    }
}

/**
 * TextFieldValue adapter around [computeBalancedEdit]. The pure-text core lives in
 * [AutoBalance.kt] so it's testable without Compose; this thin wrapper keeps the
 * Compose-typed call site readable.
 */
internal fun applyAutoBalance(
    previous: TextFieldValue,
    proposed: TextFieldValue,
    behavior: EditorBehavior,
): TextFieldValue {
    if (proposed.selection.start != proposed.selection.end) return proposed
    val edit = computeBalancedEdit(
        prevText = previous.text,
        newText = proposed.text,
        newCursor = proposed.selection.start,
        behavior = behavior,
    ) ?: return proposed
    return TextFieldValue(edit.text, TextRange(edit.cursor))
}

/** TextFieldValue wrapper around [completePartial]. */
internal fun insertCompletion(value: TextFieldValue, name: String): TextFieldValue {
    val edit = completePartial(value.text, value.selection.start, name)
    return TextFieldValue(edit.text, TextRange(edit.cursor))
}

/**
 * Thin gutter on the left rendering the current paren depth. Single-character display so it
 * never visually competes with the code itself.
 */
@Composable
private fun DepthGutter(text: String, cursorOffset: Int, color: Color) {
    val depth = remember(text, cursorOffset) {
        val toks = Tokenizer.tokenize(text)
        var d = 0
        for (t in toks) {
            if (t.start >= cursorOffset) break
            if (t.kind == TokenKind.LPAREN) d++
            else if (t.kind == TokenKind.RPAREN) d--
        }
        d.coerceAtLeast(0)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier
            .width(28.dp)
            .heightIn(min = 24.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = depth.toString(),
                color = color,
                fontWeight = FontWeight.Bold,
                style = CodeTextStyle.copy(fontSize = 12.sp),
            )
        }
    }
    Spacer(modifier = Modifier.width(6.dp))
}

/**
 * Floating popup that lists completion suggestions. Tap-to-insert. Positioned below the
 * caret as best we can — Compose's `Popup` anchors to the parent, which is the editor
 * surface; that's good enough for touch.
 */
@Composable
private fun CompletionPopup(
    state: CodeEditorState,
    onAccept: (CompletionEntry) -> Unit,
) {
    val engine = state.engine ?: return
    val cursor = state.text.selection.start
    val suggestions = remember(state.text.text, cursor, state.completionSymbols, engine) {
        engine.suggest(
            text = state.text.text,
            cursorOffset = cursor,
            lessonHints = state.completionSymbols.toHashSet(),
            max = 8,
        )
    }
    if (suggestions.isEmpty()) return

    val density = LocalDensity.current
    // Anchor below the editor row. Compose doesn't expose caret pixel coords from
    // BasicTextField easily; touch-first UX makes "below the field" the natural place.
    val popupOffset = with(density) { IntOffset(0, 24.dp.roundToPx()) }
    Popup(
        properties = PopupProperties(focusable = false),
        offset = popupOffset,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 4.dp,
            modifier = Modifier.widthIn(min = 180.dp, max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                suggestions.forEach { entry ->
                    SuggestionRow(entry, onClick = { onAccept(entry) })
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(entry: CompletionEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = entry.name,
            style = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            fontWeight = FontWeight.Medium,
        )
        if (entry.arity.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = entry.arity,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * Single-cell mutable holder used by the editor to retain the prior [TextFieldValue]
 * across recompositions without going through Compose's snapshot system. The value is
 * pure bookkeeping for the diff algorithm and never participates in observability.
 */
private class Ref<T>(var value: T)

// ----------------------------------------------------------------------------
// TODO(B1-stretch): long-press on an LPAREN should highlight the entire form
// (i.e. background-color the run from the LPAREN through its matching RPAREN).
// Compose's BasicTextField doesn't expose hit-testing into transformed text out
// of the box; the cleanest implementation likely overlays a Canvas that uses
// the text layout coordinates. Left for the integration pass to wire up; the
// data we need is already produced by ParenMatcher (pairs map).
// ----------------------------------------------------------------------------
