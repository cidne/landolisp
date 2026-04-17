package com.landolisp.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.landolisp.lisp.CompletionEngine

/**
 * Loads the [CompletionEngine] from `assets/cl-symbols.json` once per process and exposes
 * it as Compose state so screens can drop it into [CodeEditorState] without each one
 * re-parsing the JSON.
 *
 * Usage:
 * ```
 * val engine by rememberCompletionEngine()
 * CodeEditor(state = CodeEditorState(..., engine = engine))
 * ```
 *
 * Returns `null` until the asset has finished parsing on `Dispatchers.IO`. Callers should
 * tolerate the null and the editor will simply behave as if completion is disabled.
 */
@Composable
fun rememberCompletionEngine(): State<CompletionEngine?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<CompletionEngine?>(null) }
    LaunchedEffect(Unit) {
        if (state.value == null) {
            state.value = CompletionEngine.loadFromAssets(context.applicationContext)
        }
    }
    return state
}
