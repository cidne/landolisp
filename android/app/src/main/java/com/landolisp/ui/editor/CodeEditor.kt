package com.landolisp.ui.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import com.landolisp.ui.theme.CodeTextStyle

/**
 * State holder for [CodeEditor].
 *
 * @property text Current [TextFieldValue] (text + selection + composition).
 * @property onTextChange Invoked on every edit. Implementations should hoist into a ViewModel
 *           or `remember`'d state in the caller.
 * @property completionSymbols Hint set the editor's completer should rank first. Typically the
 *           current lesson's `completionSymbols` unioned with `assets/cl-symbols.json` which
 *           [agent B1] will load.
 */
@Immutable
data class CodeEditorState(
    val text: TextFieldValue,
    val onTextChange: (TextFieldValue) -> Unit,
    val completionSymbols: List<String> = emptyList(),
)

/**
 * Common-Lisp aware code editor.
 *
 * **Contract for the full implementation (see ARCHITECTURE.md §6).** Agent B1 owns this file
 * and is expected to:
 *
 *  1. Tokenize via `com.landolisp.lisp.Tokenizer` and rebuild an [androidx.compose.ui.text.AnnotatedString]
 *     on every text change (debounced ~50 ms). Apply syntax colors for symbols, numbers,
 *     strings, comments, and keywords.
 *  2. Rainbow-color matched parens using [com.landolisp.ui.theme.ParenColors] (6-cycle).
 *  3. Auto-insert `)` when `(` is typed; skip-over `)` when the next char is already `)`.
 *  4. Highlight the matched paren under the caret.
 *  5. Drive completion from [CodeEditorState.completionSymbols] plus the bundled CL symbol
 *     table at `assets/cl-symbols.json`. Trie-backed.
 *  6. Surface a software-keyboard "submit" affordance (IME action Send) wired to [onSubmit].
 *
 * Until B1 lands, this stub renders a [BasicTextField] in the monospace style so the rest of
 * the UI can already mount and function end-to-end.
 */
// TODO(B1): replace this stub with the full editor described above.
@Composable
fun CodeEditor(
    state: CodeEditorState,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    val style = LocalTextStyle.current.merge(CodeTextStyle).copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    BasicTextField(
        value = state.text,
        onValueChange = state.onTextChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = style,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )

    // onSubmit is intentionally unused in the stub — the caller still passes it so B1 can
    // wire IME actions / keyboard shortcuts without changing call sites.
    @Suppress("UNUSED_EXPRESSION")
    onSubmit
}
