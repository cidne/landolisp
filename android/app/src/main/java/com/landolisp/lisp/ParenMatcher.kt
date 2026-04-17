package com.landolisp.lisp

import androidx.compose.ui.graphics.Color
import com.landolisp.ui.theme.ParenColors

/**
 * One paren occurrence with its nesting depth.
 *
 * @property offset The token's start offset in the source.
 * @property isOpen `true` for `(`, `false` for `)`.
 * @property depth  Nesting depth — 0 for the outermost paren, increasing inward. For an
 *                  unbalanced `)` the depth is reported as the depth at which the matcher
 *                  noticed the mismatch (i.e. -1 below outer level), which the highlighter
 *                  treats as an error.
 */
data class ParenInfo(
    val offset: Int,
    val isOpen: Boolean,
    val depth: Int,
)

/**
 * Result of [ParenMatcher.analyze].
 *
 * @property parens         Every LPAREN/RPAREN with its nesting depth.
 * @property pairs          Map: open-paren offset → close-paren offset (for matched pairs).
 * @property reverse        Inverse of [pairs]: close-paren offset → open-paren offset.
 * @property currentDepth   Nesting depth at the cursor; 0 means top level.
 * @property matchingPair   If the cursor is adjacent to a paren whose partner exists, the
 *                          inclusive offsets of the pair, encoded as `IntRange(open, close)`.
 *                          `null` otherwise.
 * @property unbalanced     Offsets of every paren that has no partner.
 */
data class ParenAnalysis(
    val parens: List<ParenInfo>,
    val pairs: Map<Int, Int>,
    val reverse: Map<Int, Int>,
    val currentDepth: Int,
    val matchingPair: IntRange?,
    val unbalanced: List<Int>,
) {
    /**
     * Rainbow color for a given paren-nesting depth. Cycles through [ParenColors] mod its
     * size so arbitrarily deep nesting still produces a stable color.
     */
    fun colorOfDepth(depth: Int): Color {
        if (ParenColors.isEmpty()) return Color.Unspecified
        val idx = ((depth % ParenColors.size) + ParenColors.size) % ParenColors.size
        return ParenColors[idx]
    }

    companion object {
        val EMPTY = ParenAnalysis(
            parens = emptyList(),
            pairs = emptyMap(),
            reverse = emptyMap(),
            currentDepth = 0,
            matchingPair = null,
            unbalanced = emptyList(),
        )
    }
}

/**
 * Pure-Kotlin paren matcher used by the editor for rainbow nesting, matched-pair
 * highlighting, and the unbalanced-paren squiggle.
 *
 * The algorithm is one linear pass over the token list using an [ArrayDeque] as the open
 * paren stack. O(n) in tokens; allocates two small maps proportional to the paren count.
 */
object ParenMatcher {

    /**
     * Analyze [tokens] and produce a [ParenAnalysis] anchored at [cursorOffset]. Pass any
     * non-negative offset; out-of-range values are clamped silently.
     */
    fun analyze(tokens: List<Token>, cursorOffset: Int): ParenAnalysis {
        if (tokens.isEmpty()) return ParenAnalysis.EMPTY

        val parens = ArrayList<ParenInfo>(tokens.size / 4 + 4)
        val pairs = HashMap<Int, Int>()
        val reverse = HashMap<Int, Int>()
        val unbalanced = ArrayList<Int>()
        val openStack = ArrayDeque<Int>() // stack of indices into `parens`

        for (t in tokens) {
            when (t.kind) {
                TokenKind.LPAREN -> {
                    val info = ParenInfo(t.start, isOpen = true, depth = openStack.size)
                    parens.add(info)
                    openStack.addLast(parens.size - 1)
                }
                TokenKind.RPAREN -> {
                    if (openStack.isEmpty()) {
                        // Unmatched closer; depth -1 marks "below top level".
                        parens.add(ParenInfo(t.start, isOpen = false, depth = -1))
                        unbalanced.add(t.start)
                    } else {
                        val openIdx = openStack.removeLast()
                        val openInfo = parens[openIdx]
                        val closeInfo = ParenInfo(t.start, isOpen = false, depth = openInfo.depth)
                        parens.add(closeInfo)
                        pairs[openInfo.offset] = closeInfo.offset
                        reverse[closeInfo.offset] = openInfo.offset
                    }
                }
                else -> Unit
            }
        }
        // Anything still on the stack never closed.
        while (openStack.isNotEmpty()) {
            val idx = openStack.removeLast()
            unbalanced.add(parens[idx].offset)
        }
        unbalanced.sort()

        val currentDepth = depthAt(parens, cursorOffset)
        val matchingPair = findMatchingPair(parens, pairs, reverse, cursorOffset)

        return ParenAnalysis(
            parens = parens,
            pairs = pairs,
            reverse = reverse,
            currentDepth = currentDepth,
            matchingPair = matchingPair,
            unbalanced = unbalanced,
        )
    }

    /**
     * Compute the nesting depth at [cursor]. We count opens at offset < cursor and closes at
     * offset < cursor; anything that closes before the cursor cancels its open. The depth is
     * the running balance (clamped at 0 for safety).
     */
    private fun depthAt(parens: List<ParenInfo>, cursor: Int): Int {
        var depth = 0
        for (p in parens) {
            if (p.offset >= cursor) break
            depth += if (p.isOpen) 1 else -1
        }
        return depth.coerceAtLeast(0)
    }

    /**
     * Cursor adjacency rules: editors usually highlight the pair when the caret is
     * immediately before `(`, immediately after `)`, immediately after `(`, or immediately
     * before `)`. We pick the closest adjacent paren and return its match.
     */
    private fun findMatchingPair(
        parens: List<ParenInfo>,
        pairs: Map<Int, Int>,
        reverse: Map<Int, Int>,
        cursor: Int,
    ): IntRange? {
        // Adjacent if paren's offset == cursor (caret before it) or offset == cursor - 1
        // (caret immediately after it).
        for (p in parens) {
            if (p.offset != cursor && p.offset != cursor - 1) continue
            if (p.isOpen) {
                val close = pairs[p.offset] ?: continue
                return p.offset..close
            } else {
                val open = reverse[p.offset] ?: continue
                return open..p.offset
            }
        }
        return null
    }
}
