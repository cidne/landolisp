package com.landolisp.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests the pure-text auto-balance core ([computeBalancedEdit]) and the completion-splice
 * helper ([completePartial]). Lives in this module rather than `lisp/` because the editor
 * behavior toggles ([EditorBehavior]) live with the Compose code.
 *
 * The pure-text functions intentionally have no Compose dependency so these tests run in
 * the plain JVM unit-test source set.
 */
class AutoBalanceTest {

    private val behavior = EditorBehavior()

    @Test
    fun typingOpenParenInsertsClose() {
        val edit = computeBalancedEdit(prevText = "", newText = "(", newCursor = 1, behavior = behavior)
        assertEquals("()", edit?.text)
        assertEquals(1, edit?.cursor)
    }

    @Test
    fun typingDoubleQuoteInsertsClose() {
        val edit = computeBalancedEdit(prevText = "", newText = "\"", newCursor = 1, behavior = behavior)
        assertEquals("\"\"", edit?.text)
        assertEquals(1, edit?.cursor)
    }

    @Test
    fun typingCloseParenWhenNextIsCloseSwallowsInput() {
        // Buffer "()" with caret between, user types `)` → naive new buffer "())".
        // Skip-over rewrites to "()" with the caret advanced past the existing `)`.
        val edit = computeBalancedEdit(
            prevText = "()",
            newText = "())",
            newCursor = 2,
            behavior = behavior,
        )
        assertEquals("()", edit?.text)
        assertEquals(2, edit?.cursor)
    }

    @Test
    fun backspaceOnEmptyParenPairDeletesBoth() {
        val edit = computeBalancedEdit(
            prevText = "()",
            newText = ")",
            newCursor = 0,
            behavior = behavior,
        )
        assertEquals("", edit?.text)
        assertEquals(0, edit?.cursor)
    }

    @Test
    fun backspaceOnEmptyStringPairDeletesBoth() {
        val edit = computeBalancedEdit(
            prevText = "\"\"",
            newText = "\"",
            newCursor = 0,
            behavior = behavior,
        )
        assertEquals("", edit?.text)
    }

    @Test
    fun typingQuoteInsideStringDoesNotInsertSecond() {
        // Buffer: `"hello`  caret at end. User types `"` so newText = `"hello"`. Inside
        // the string already, so we should NOT auto-close — the typed `"` closes it.
        val edit = computeBalancedEdit(
            prevText = "\"hello",
            newText = "\"hello\"",
            newCursor = 7,
            behavior = behavior,
        )
        assertNull(edit)
    }

    @Test
    fun togglingOffAutoCloseSkipsInsertion() {
        val off = EditorBehavior(autoCloseParen = false)
        val edit = computeBalancedEdit(prevText = "", newText = "(", newCursor = 1, behavior = off)
        assertNull(edit)
    }

    @Test
    fun otherEditsArePassedThrough() {
        // Multi-char paste — must not be touched.
        val edit = computeBalancedEdit(
            prevText = "",
            newText = "(defun foo () 42)",
            newCursor = 17,
            behavior = behavior,
        )
        assertNull(edit)
    }

    @Test
    fun typingOpenParenInTheMiddleOfTextStillBalances() {
        val edit = computeBalancedEdit(
            prevText = "ab",
            newText = "a(b",
            newCursor = 2,
            behavior = behavior,
        )
        assertEquals("a()b", edit?.text)
        assertEquals(2, edit?.cursor)
    }

    @Test
    fun completePartialReplacesPartialAtCaret() {
        val edit = completePartial(text = "(de", cursor = 3, name = "defun")
        assertEquals("(defun", edit.text)
        assertEquals(6, edit.cursor)
    }

    @Test
    fun completePartialPreservesTrailingText() {
        val edit = completePartial(text = "(de x)", cursor = 3, name = "defun")
        assertEquals("(defun x)", edit.text)
        assertEquals(6, edit.cursor)
    }

    @Test
    fun deletingPlainCharDoesNotPairDelete() {
        val edit = computeBalancedEdit(
            prevText = "abc",
            newText = "ab",
            newCursor = 2,
            behavior = behavior,
        )
        assertNull(edit)
    }
}
