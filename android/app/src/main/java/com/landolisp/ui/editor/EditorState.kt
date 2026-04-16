package com.landolisp.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.landolisp.lisp.CompletionEngine

/**
 * Hoistable state for [CodeEditor]. The shape is intentionally an immutable value — the
 * editor never mutates it; instead, callers update [text] via [onTextChange] and rebuild a
 * new state on the next recomposition.
 *
 * @property text               Current text + selection + composition.
 * @property onTextChange       Callback invoked when the editor wants to commit a change.
 * @property completionSymbols  Lesson-supplied symbols that should rank first in the
 *                              completion popup.
 * @property engine             Bundled CL completion engine. `null` disables completion
 *                              entirely (handy in tests).
 * @property keywords           Additional symbols to highlight as keywords beyond the
 *                              built-in CL set.
 * @property behavior           Per-feature toggles for auto-balance, skip-over, etc.
 */
@Immutable
data class CodeEditorState(
    val text: TextFieldValue,
    val onTextChange: (TextFieldValue) -> Unit,
    val completionSymbols: List<String> = emptyList(),
    val engine: CompletionEngine? = null,
    val keywords: Set<String> = emptySet(),
    val behavior: EditorBehavior = EditorBehavior(),
)

/**
 * Convenience factory: returns a [CodeEditorState] whose `text` is held in a
 * [rememberSaveable]-backed slot so it survives configuration changes.
 *
 * Callers that already have their own text holder (e.g. a ViewModel) should construct
 * [CodeEditorState] directly instead.
 */
@Composable
fun rememberCodeEditorState(
    initial: String,
    engine: CompletionEngine?,
    completionSymbols: List<String> = emptyList(),
    keywords: Set<String> = emptySet(),
    behavior: EditorBehavior = EditorBehavior(),
): CodeEditorState {
    var value by rememberSaveable(stateSaver = TextFieldValueSaver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    return remember(value, engine, completionSymbols, keywords, behavior) {
        CodeEditorState(
            text = value,
            onTextChange = { value = it },
            completionSymbols = completionSymbols,
            engine = engine,
            keywords = keywords,
            behavior = behavior,
        )
    }
}

/**
 * Saver for [TextFieldValue] — Compose ships one in newer versions but not in our BOM
 * (2024.09.03). We persist text + selection only; the IME composition window is transient
 * and never worth round-tripping through [rememberSaveable].
 */
private val TextFieldValueSaver: Saver<TextFieldValue, Any> = Saver(
    save = { listOf(it.text, it.selection.start, it.selection.end) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        val list = it as List<Any>
        TextFieldValue(
            text = list[0] as String,
            selection = TextRange((list[1] as Int), (list[2] as Int)),
        )
    },
)
