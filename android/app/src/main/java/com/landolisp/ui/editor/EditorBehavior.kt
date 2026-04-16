package com.landolisp.ui.editor

/**
 * Feature toggles for [CodeEditor]'s smart text-handling behaviors. All default to `on` —
 * disable individual ones for tests, accessibility scenarios, or when the user is pasting
 * pre-formatted code that should not be rewritten.
 *
 * Lives in its own file (with no Compose dependency) so the pure auto-balance core can be
 * exercised from plain JVM unit tests.
 */
data class EditorBehavior(
    /** Auto-insert `)` when the user types `(` and place the caret between. */
    val autoCloseParen: Boolean = true,
    /** Auto-insert closing `"` when the user types `"` outside of an existing string. */
    val autoCloseString: Boolean = true,
    /** When the next char is already `)`, swallow a typed `)`. */
    val skipOverCloseParen: Boolean = true,
    /** Backspace on an empty `()` or `""` deletes both characters. */
    val deletePairOnBackspace: Boolean = true,
    /** Show the floating completion popup at the caret. */
    val completionPopup: Boolean = true,
    /** Show the depth gutter on the left. */
    val showDepthGutter: Boolean = true,
) {
    companion object {
        /** Convenience: every smart behavior off. Useful for tests / a11y mode. */
        val Off = EditorBehavior(
            autoCloseParen = false,
            autoCloseString = false,
            skipOverCloseParen = false,
            deletePairOnBackspace = false,
            completionPopup = false,
            showDepthGutter = false,
        )
    }
}
