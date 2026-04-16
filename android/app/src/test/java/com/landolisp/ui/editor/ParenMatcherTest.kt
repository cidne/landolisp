package com.landolisp.ui.editor

import com.landolisp.lisp.ParenMatcher
import com.landolisp.lisp.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParenMatcherTest {

    private fun analyze(src: String, cursor: Int) =
        ParenMatcher.analyze(Tokenizer.tokenize(src), cursor)

    @Test
    fun emptyInputIsEmpty() {
        val a = analyze("", 0)
        assertEquals(0, a.parens.size)
        assertEquals(0, a.currentDepth)
        assertNull(a.matchingPair)
        assertTrue(a.unbalanced.isEmpty())
    }

    @Test
    fun depthZeroOutsideAnyParen() {
        // cursor before the first `(`
        val a = analyze("(foo)", 0)
        assertEquals(0, a.currentDepth)
    }

    @Test
    fun depthOneInsideOneLevel() {
        val src = "(foo)"
        // cursor between `(` and `f`
        val a = analyze(src, 1)
        assertEquals(1, a.currentDepth)
    }

    @Test
    fun depthTwoInsideNestedForm() {
        val src = "(foo (bar))"
        // cursor inside the inner form: between `(` and `b`
        val cursor = src.indexOf('b')
        val a = analyze(src, cursor)
        assertEquals(2, a.currentDepth)
    }

    @Test
    fun matchingPairReturnedWhenCaretAdjacent() {
        val src = "(+ 1 2)"
        // caret immediately after `(` (offset 1)
        val a = analyze(src, 1)
        assertNotNull(a.matchingPair)
        assertEquals(0, a.matchingPair!!.first)
        assertEquals(src.length - 1, a.matchingPair!!.last)

        // caret immediately before the closing `)` (offset src.length - 1)
        val b = analyze(src, src.length - 1)
        assertNotNull(b.matchingPair)

        // caret in the middle, not adjacent to any paren
        val c = analyze(src, 3)
        assertNull(c.matchingPair)
    }

    @Test
    fun unmatchedExtraCloseIsReported() {
        val src = "(foo))"
        val a = analyze(src, 0)
        assertEquals(1, a.unbalanced.size)
        assertEquals(src.length - 1, a.unbalanced.first())
    }

    @Test
    fun unmatchedOpenIsReported() {
        val src = "(foo (bar)"
        val a = analyze(src, 0)
        assertEquals(1, a.unbalanced.size)
        assertEquals(0, a.unbalanced.first())
    }

    @Test
    fun rainbowColorIsStableAcrossCycles() {
        val a = analyze("()", 0)
        assertEquals(a.colorOfDepth(0), a.colorOfDepth(6))
        assertEquals(a.colorOfDepth(1), a.colorOfDepth(7))
        // Negative depths land on a valid index too (no crash).
        a.colorOfDepth(-1)
    }

    @Test
    fun pairMapIsConsistent() {
        val src = "(let ((x 1)) (+ x x))"
        val a = analyze(src, 0)
        // Every entry in pairs should have its inverse in reverse.
        for ((open, close) in a.pairs) {
            assertEquals(open, a.reverse[close])
        }
        assertTrue(a.unbalanced.isEmpty())
    }
}
