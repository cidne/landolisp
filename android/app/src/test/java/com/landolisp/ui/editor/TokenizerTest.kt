package com.landolisp.ui.editor

import com.landolisp.lisp.Token
import com.landolisp.lisp.TokenKind
import com.landolisp.lisp.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenizerTest {

    private fun nonWs(src: String): List<Token> =
        Tokenizer.tokenize(src).filter { it.kind != TokenKind.WHITESPACE }

    @Test
    fun emptyInputProducesNoTokens() {
        assertEquals(emptyList<Token>(), Tokenizer.tokenize(""))
    }

    @Test
    fun simpleForm() {
        val toks = nonWs("(+ 1 2)")
        assertEquals(
            listOf(TokenKind.LPAREN, TokenKind.SYMBOL, TokenKind.NUMBER, TokenKind.NUMBER, TokenKind.RPAREN),
            toks.map { it.kind },
        )
        assertEquals("+", toks[1].lexeme)
        assertEquals("1", toks[2].lexeme)
    }

    @Test
    fun nestedForms() {
        val toks = nonWs("(let ((x 1)) (+ x x))")
        assertEquals(7, toks.count { it.kind == TokenKind.LPAREN || it.kind == TokenKind.RPAREN })
        // outer paren count balances
        val opens = toks.count { it.kind == TokenKind.LPAREN }
        val closes = toks.count { it.kind == TokenKind.RPAREN }
        assertEquals(opens, closes)
    }

    @Test
    fun stringWithEscapes() {
        val src = """ "hello \"world\" \\n" """
        val toks = Tokenizer.tokenize(src).filter { it.kind == TokenKind.STRING }
        assertEquals(1, toks.size)
        // The lexeme should include the surrounding quotes and the escapes literally.
        assertTrue(toks[0].lexeme.startsWith("\""))
        assertTrue(toks[0].lexeme.endsWith("\""))
        assertTrue(toks[0].lexeme.contains("\\\""))
    }

    @Test
    fun unterminatedStringRunsToEnd() {
        val src = "(print \"oops"
        val toks = Tokenizer.tokenize(src)
        val last = toks.last()
        assertEquals(TokenKind.STRING, last.kind)
        assertEquals(src.length, last.end)
        assertTrue(last.lexeme.startsWith("\""))
    }

    @Test
    fun lineComment() {
        val src = "(foo) ; this is a comment\n(bar)"
        val toks = nonWs(src)
        val comment = toks.first { it.kind == TokenKind.COMMENT }
        assertEquals("; this is a comment", comment.lexeme)
        // No newline included in the comment lexeme.
        assertTrue(!comment.lexeme.contains('\n'))
    }

    @Test
    fun blockComment() {
        val src = "(a #| inner ((stuff)) |# b)"
        val toks = nonWs(src)
        val comments = toks.filter { it.kind == TokenKind.COMMENT }
        assertEquals(1, comments.size)
        assertTrue(comments[0].lexeme.startsWith("#|"))
        assertTrue(comments[0].lexeme.endsWith("|#"))
        // Symbols outside the comment still tokenize.
        val syms = toks.filter { it.kind == TokenKind.SYMBOL }.map { it.lexeme }
        assertTrue(syms.contains("a"))
        assertTrue(syms.contains("b"))
    }

    @Test
    fun unterminatedBlockComment() {
        val src = "#| forever"
        val toks = Tokenizer.tokenize(src)
        assertEquals(1, toks.size)
        assertEquals(TokenKind.COMMENT, toks[0].kind)
        assertEquals(src.length, toks[0].end)
    }

    @Test
    fun allQuoteSugars() {
        val src = "'a `b ,c ,@d #'e"
        val toks = nonWs(src)
        val quotes = toks.filter { it.kind == TokenKind.QUOTE }
        assertEquals(5, quotes.size)
        assertEquals("'", quotes[0].lexeme)
        assertEquals("`", quotes[1].lexeme)
        assertEquals(",", quotes[2].lexeme)
        assertEquals(",@", quotes[3].lexeme)
        assertEquals("#'", quotes[4].lexeme)
    }

    @Test
    fun numbersIncludingSignsAndFloats() {
        val src = "1 -2 +3.14 .5 1e10 -2.5e-3"
        val toks = nonWs(src)
        // All six should be NUMBER.
        assertTrue(toks.all { it.kind == TokenKind.NUMBER })
        assertEquals(6, toks.size)
    }

    @Test
    fun loneSignIsSymbol() {
        val src = "(+ - foo)"
        val toks = nonWs(src)
        assertEquals(TokenKind.SYMBOL, toks[1].kind)
        assertEquals("+", toks[1].lexeme)
        assertEquals(TokenKind.SYMBOL, toks[2].kind)
        assertEquals("-", toks[2].lexeme)
    }

    @Test
    fun offsetsAreContiguousAndMatchSource() {
        val src = "(defun greet (x)\n  (format t \"hi ~a\" x))"
        val toks = Tokenizer.tokenize(src)
        // Offsets cover the whole source with no gaps.
        var pos = 0
        for (t in toks) {
            assertEquals(pos, t.start)
            assertEquals(t.lexeme, src.substring(t.start, t.end))
            pos = t.end
        }
        assertEquals(src.length, pos)
    }
}
