package com.landolisp.lisp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot / round-trip tests for [Tokenizer]. For every canned snippet:
 *
 *   1. Tokenize.
 *   2. Concatenate every `lexeme` in order.
 *   3. Assert the result is byte-identical to the input.
 *
 * That single property catches whole classes of regressions (lost characters,
 * miscounted offsets, unbalanced WHITESPACE/SYMBOL boundaries) without
 * pinning to a brittle exact-token-stream fixture.
 *
 * If `lisp/Tokenizer.kt` is ever absent (B1 not yet merged), the test will
 * fail to compile rather than silently pass — that's intentional.
 */
class TokenizerSnapshotTest {

    private val corpus: List<String> = listOf(
        // bare atoms / numbers / symbols
        "1",
        "+1.5",
        "-3.14e10",
        "foo-bar",
        "+",
        "*global*",

        // simple lists
        "(+ 1 2)",
        "(defun greet (name) (format nil \"hello, ~a\" name))",
        "(let ((x 1) (y 2)) (* x y))",

        // strings with escapes
        "\"hello \\\"world\\\"\"",
        "\"line1\\nline2\"",
        // unterminated string at EOF (tokenizer should still return one STRING token)
        "\"oops",

        // comments
        "; line comment to EOL\n(+ 1 2)",
        "#| block\ncomment |#\n(foo)",

        // quote sugars
        "'(a b c)",
        "`(1 ,x ,@xs)",
        "#'+",

        // nested forms with weird whitespace
        "(  (a   b)\n   (c\t\td) )",

        // deeply nested
        "(((((+ 1 2)))))",

        // mixed
        "(loop for i from 1 below 10 collect (* i i)) ; squares",
    )

    @Test
    fun tokenStreamRoundTripsToOriginal() {
        for (snippet in corpus) {
            val tokens = Tokenizer.tokenize(snippet)
            val rebuilt = tokens.joinToString("") { it.lexeme }
            assertEquals("round-trip mismatch for: $snippet", snippet, rebuilt)
        }
    }

    @Test
    fun tokenStartEndCoverEveryByteInSequence() {
        for (snippet in corpus) {
            val tokens = Tokenizer.tokenize(snippet)
            // Every token must be contiguous with its neighbours and span [0..source.length).
            var cursor = 0
            for (t in tokens) {
                assertEquals(
                    "non-contiguous token in: $snippet",
                    cursor, t.start,
                )
                assertTrue(t.end >= t.start)
                cursor = t.end
            }
            assertEquals("tokens did not cover source for: $snippet", snippet.length, cursor)
        }
    }

    @Test
    fun parensAreTaggedAsLparenAndRparen() {
        val toks = Tokenizer.tokenize("(a (b c))")
        val parens = toks.filter { it.kind == TokenKind.LPAREN || it.kind == TokenKind.RPAREN }
        // 3 LPAREN + 3 RPAREN
        assertEquals(6, parens.size)
        assertEquals(3, parens.count { it.kind == TokenKind.LPAREN })
        assertEquals(3, parens.count { it.kind == TokenKind.RPAREN })
    }

    @Test
    fun stringLiteralIsSingleStringToken() {
        val toks = Tokenizer.tokenize("\"hello \\\"world\\\"\"")
        val strs = toks.filter { it.kind == TokenKind.STRING }
        assertEquals(1, strs.size)
        assertEquals("\"hello \\\"world\\\"\"", strs.first().lexeme)
    }

    @Test
    fun lineCommentRunsToEndOfLine() {
        val toks = Tokenizer.tokenize("; this is a comment\n(+ 1 2)")
        val firstNonWs = toks.first { it.kind != TokenKind.WHITESPACE }
        assertEquals(TokenKind.COMMENT, firstNonWs.kind)
        assertTrue(firstNonWs.lexeme.startsWith(";"))
    }
}
