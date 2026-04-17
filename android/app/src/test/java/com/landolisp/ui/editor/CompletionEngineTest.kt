package com.landolisp.ui.editor

import com.landolisp.lisp.CompletionEngine
import com.landolisp.lisp.CompletionEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionEngineTest {

    private val sampleEntries = listOf(
        CompletionEntry("defun", "macro", "Define a function."),
        CompletionEntry("defmacro", "macro", "Define a macro."),
        CompletionEntry("defparameter", "macro", "Define a parameter."),
        CompletionEntry("defvar", "macro", "Define a variable."),
        CompletionEntry("let", "special", "Bind in parallel."),
        CompletionEntry("let*", "special", "Bind sequentially."),
        CompletionEntry("lambda", "macro", "Anonymous function."),
        CompletionEntry("car", "function", "First element."),
        CompletionEntry("cdr", "function", "Tail."),
        CompletionEntry("cons", "function", "Construct cons cell."),
    )

    private val engine = CompletionEngine.fromEntries(sampleEntries)

    @Test
    fun prefixLookupReturnsAllMatches() {
        val source = "(de"
        val out = engine.suggest(source, source.length)
        val names = out.map { it.name }
        assertTrue(names.contains("defun"))
        assertTrue(names.contains("defmacro"))
        assertTrue(names.contains("defparameter"))
        assertTrue(names.contains("defvar"))
        // "lambda", "let" must not appear.
        assertTrue(!names.contains("let"))
    }

    @Test
    fun prefixCaseInsensitive() {
        val source = "(LE"
        val out = engine.suggest(source, source.length).map { it.name }
        assertTrue(out.contains("let"))
        assertTrue(out.contains("let*"))
        assertTrue(out.contains("lambda").not()) // lambda doesn't start with "le"
    }

    @Test
    fun emptyPartialReturnsNothing() {
        // cursor right after `(` — no symbol partial yet
        val source = "("
        val out = engine.suggest(source, source.length)
        assertTrue(out.isEmpty())
    }

    @Test
    fun cursorInsideStringDisablesCompletion() {
        // typed: (print "de  with caret right after "de" inside the string
        val source = "(print \"de"
        val out = engine.suggest(source, source.length)
        assertTrue(out.isEmpty())
    }

    @Test
    fun cursorInsideCommentDisablesCompletion() {
        val source = "; de"
        val out = engine.suggest(source, source.length)
        assertTrue(out.isEmpty())
    }

    @Test
    fun lessonHintsAreBoostedFirst() {
        val source = "(de"
        val hints = setOf("defmacro") // would normally come second alphabetically
        val out = engine.suggest(source, source.length, lessonHints = hints)
        assertEquals("defmacro", out.first().name)
    }

    @Test
    fun lessonHintsCanIntroduceNewSymbols() {
        // "destructuring-bind" isn't in the sample bundle, but the hint should still
        // surface it.
        val source = "(des"
        val out = engine.suggest(source, source.length, lessonHints = setOf("destructuring-bind"))
        assertEquals(1, out.size)
        assertEquals("destructuring-bind", out.first().name)
    }

    @Test
    fun resultsCappedAtMax() {
        val source = "(de"
        val out = engine.suggest(source, source.length, max = 2)
        assertEquals(2, out.size)
    }

    @Test
    fun lessonHintsDoNotDuplicateBundledEntries() {
        val source = "(de"
        val out = engine.suggest(source, source.length, lessonHints = setOf("defun"))
        // "defun" appears only once.
        assertEquals(1, out.count { it.name == "defun" })
    }
}
