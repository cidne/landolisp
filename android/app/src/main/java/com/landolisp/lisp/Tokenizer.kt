package com.landolisp.lisp

/**
 * Token kinds emitted by the Lisp [Tokenizer].
 */
enum class TokenKind {
    LPAREN,
    RPAREN,
    STRING,
    COMMENT,
    NUMBER,
    SYMBOL,
    QUOTE,
    WHITESPACE,
}

/**
 * One lexed token.
 *
 * @property kind     The token category.
 * @property start    Inclusive start offset into the source string.
 * @property end      Exclusive end offset; always `>= start`.
 * @property lexeme   The substring of the source covered by this token.
 */
data class Token(
    val kind: TokenKind,
    val start: Int,
    val end: Int,
    val lexeme: String,
) {
    val length: Int get() = end - start
}

/**
 * Single-pass, allocation-light Common-Lisp tokenizer.
 *
 * Designed to be robust against incomplete input: an unterminated string at EOF is still
 * emitted as a single STRING token covering the rest of the buffer; a half-open block
 * comment is emitted as a single COMMENT token. This is essential because the editor
 * re-tokenizes on every keystroke, including states where parens / quotes are in flight.
 *
 * Recognised forms:
 *  - `(` and `)`
 *  - Line comments `; ...` to end of line.
 *  - Block comments `#| ... |#` (non-nesting; CL allows nesting but the simple form is
 *    sufficient for highlight purposes).
 *  - String literals `"..."` with `\` escapes (CL only escapes `\` and `"` inside strings,
 *    but we accept `\<any>` as one logical escape).
 *  - Numbers: optional `+`/`-`, digits, optional `.` and fractional part, optional
 *    exponent `e/E[+/-]digits`. Ratios `1/2` and `#x...` etc. fall through to SYMBOL —
 *    sufficient for highlight.
 *  - Quote sugars: `'`, `` ` ``, `,`, `,@`, `#'`.
 *  - SYMBOL: any other run of non-delimiter characters.
 *  - WHITESPACE: runs of space / tab / newline / carriage return.
 */
class Tokenizer(private val source: String) {

    private val length = source.length
    private var pos = 0

    /**
     * Lex the entire source string in one pass and return the token list.
     */
    fun tokenize(): List<Token> {
        val out = ArrayList<Token>(estimateTokenCount(length))
        pos = 0
        while (pos < length) {
            val c = source[pos]
            when {
                isWhitespace(c) -> readWhitespace(out)
                c == '(' -> emitSingle(out, TokenKind.LPAREN)
                c == ')' -> emitSingle(out, TokenKind.RPAREN)
                c == ';' -> readLineComment(out)
                c == '#' && peek(1) == '|' -> readBlockComment(out)
                c == '#' && peek(1) == '\'' -> emitN(out, TokenKind.QUOTE, 2)
                c == '\'' -> emitSingle(out, TokenKind.QUOTE)
                c == '`' -> emitSingle(out, TokenKind.QUOTE)
                c == ',' -> if (peek(1) == '@') emitN(out, TokenKind.QUOTE, 2)
                            else emitSingle(out, TokenKind.QUOTE)
                c == '"' -> readString(out)
                isNumberStart(c) -> readNumberOrSymbol(out)
                else -> readSymbol(out)
            }
        }
        return out
    }

    // ---- helpers --------------------------------------------------------

    private fun peek(offset: Int): Char {
        val i = pos + offset
        return if (i < length) source[i] else '\u0000'
    }

    private fun emitSingle(out: MutableList<Token>, kind: TokenKind) {
        out.add(Token(kind, pos, pos + 1, source.substring(pos, pos + 1)))
        pos++
    }

    private fun emitN(out: MutableList<Token>, kind: TokenKind, n: Int) {
        val end = (pos + n).coerceAtMost(length)
        out.add(Token(kind, pos, end, source.substring(pos, end)))
        pos = end
    }

    private fun readWhitespace(out: MutableList<Token>) {
        val start = pos
        while (pos < length && isWhitespace(source[pos])) pos++
        out.add(Token(TokenKind.WHITESPACE, start, pos, source.substring(start, pos)))
    }

    private fun readLineComment(out: MutableList<Token>) {
        val start = pos
        while (pos < length && source[pos] != '\n') pos++
        out.add(Token(TokenKind.COMMENT, start, pos, source.substring(start, pos)))
    }

    private fun readBlockComment(out: MutableList<Token>) {
        val start = pos
        pos += 2 // consume #|
        while (pos < length) {
            if (source[pos] == '|' && peek(1) == '#') {
                pos += 2
                break
            }
            pos++
        }
        out.add(Token(TokenKind.COMMENT, start, pos, source.substring(start, pos)))
    }

    private fun readString(out: MutableList<Token>) {
        val start = pos
        pos++ // consume opening "
        while (pos < length) {
            val ch = source[pos]
            if (ch == '\\' && pos + 1 < length) {
                pos += 2 // consume escape pair as one unit
                continue
            }
            if (ch == '"') {
                pos++ // consume closing "
                break
            }
            pos++
        }
        // If we ran off the end, the token simply spans to EOF — robust to incomplete input.
        out.add(Token(TokenKind.STRING, start, pos, source.substring(start, pos)))
    }

    /**
     * `+`, `-`, `.`, and digits could begin either a number or a symbol (e.g. `+`, `-list`).
     * Try number first; if the consumed run isn't a syntactically valid number, fall back to
     * SYMBOL.
     */
    private fun readNumberOrSymbol(out: MutableList<Token>) {
        val start = pos
        // Consume the candidate token first using the usual symbol-terminator rules.
        while (pos < length && !isDelimiter(source[pos])) pos++
        val end = pos
        val lexeme = source.substring(start, end)
        val kind = if (looksLikeNumber(lexeme)) TokenKind.NUMBER else TokenKind.SYMBOL
        out.add(Token(kind, start, end, lexeme))
    }

    private fun readSymbol(out: MutableList<Token>) {
        val start = pos
        while (pos < length && !isDelimiter(source[pos])) pos++
        out.add(Token(TokenKind.SYMBOL, start, pos, source.substring(start, pos)))
    }

    // ---- character classes ----------------------------------------------

    private fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun isDelimiter(c: Char): Boolean = isWhitespace(c) ||
        c == '(' || c == ')' || c == '"' || c == ';' ||
        c == '\'' || c == '`' || c == ','

    private fun isNumberStart(c: Char): Boolean =
        (c in '0'..'9') || ((c == '+' || c == '-' || c == '.') && hasDigitSoon())

    /**
     * Lookahead used by [isNumberStart]: distinguish `+1` (number) from `+` (symbol) and
     * `.` from `.5`.
     */
    private fun hasDigitSoon(): Boolean {
        // pos is on a sign or '.'. Peek past it for a digit.
        var i = pos + 1
        if (i >= length) return false
        // Allow another sign skip here? CL doesn't, so no.
        val c = source[i]
        if (c in '0'..'9') return true
        // Cases like ".5" still hit only one extra char ahead.
        if (c == '.' && source[pos] != '.') {
            i++
            return i < length && source[i] in '0'..'9'
        }
        return false
    }

    private fun looksLikeNumber(lex: String): Boolean {
        if (lex.isEmpty()) return false
        var i = 0
        if (lex[i] == '+' || lex[i] == '-') i++
        if (i >= lex.length) return false
        var sawDigit = false
        while (i < lex.length && lex[i] in '0'..'9') { sawDigit = true; i++ }
        if (i < lex.length && lex[i] == '.') {
            i++
            while (i < lex.length && lex[i] in '0'..'9') { sawDigit = true; i++ }
        }
        if (!sawDigit) return false
        if (i < lex.length && (lex[i] == 'e' || lex[i] == 'E')) {
            i++
            if (i < lex.length && (lex[i] == '+' || lex[i] == '-')) i++
            var expDigit = false
            while (i < lex.length && lex[i] in '0'..'9') { expDigit = true; i++ }
            if (!expDigit) return false
        }
        return i == lex.length
    }

    private fun estimateTokenCount(n: Int): Int = (n / 4).coerceAtLeast(8)

    companion object {
        /** Convenience one-shot entry point. */
        fun tokenize(source: String): List<Token> = Tokenizer(source).tokenize()
    }
}
