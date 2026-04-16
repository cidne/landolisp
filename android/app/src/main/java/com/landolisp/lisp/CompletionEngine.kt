package com.landolisp.lisp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * One completion candidate. Mirrors the on-disk shape of `assets/cl-symbols.json`.
 */
@Serializable
data class CompletionEntry(
    val name: String,
    val arity: String = "",
    val doc: String = "",
)

/**
 * Prefix-search-backed completion engine.
 *
 * Strategy: keep entries sorted by lower-cased name. Suggestion = binary-search the lower
 * bound of the prefix, then linearly walk while names still start with the prefix. This is
 * tighter on memory than a trie and plenty fast for the ~200-entry CL symbol set we ship.
 *
 * The engine is immutable; lessons inject lesson-specific symbols at query time so we don't
 * have to rebuild the index per lesson. Lesson hints get an automatic "boost" — they appear
 * first in the result list regardless of how the prefix matches.
 */
class CompletionEngine(entries: List<CompletionEntry>) {

    /** Public so tests can verify ordering. */
    val sorted: List<CompletionEntry> = entries.sortedBy { it.name.lowercase() }
    private val keys: List<String> = sorted.map { it.name.lowercase() }

    /**
     * Suggest completions for the symbol at [cursorOffset] in [text].
     *
     * - Identifies the partial token under the cursor; returns empty if the cursor is not
     *   inside (or immediately after) a SYMBOL.
     * - Performs a case-insensitive prefix search against the merged
     *   `lessonHints + bundled` symbol set.
     * - Results are deduplicated by name; lesson hints appear first regardless of where
     *   they sort alphabetically.
     * - Capped at [max] entries.
     */
    fun suggest(
        text: String,
        cursorOffset: Int,
        lessonHints: Set<String> = emptySet(),
        max: Int = 8,
    ): List<CompletionEntry> {
        if (text.isEmpty() || max <= 0) return emptyList()
        val partial = partialAtCursor(text, cursorOffset) ?: return emptyList()
        if (partial.isEmpty()) return emptyList()

        val needle = partial.lowercase()
        val results = ArrayList<CompletionEntry>(max)
        val seen = HashSet<String>(max * 2)

        // Lesson hints first — boost.
        for (hint in lessonHints) {
            if (results.size >= max) break
            val lower = hint.lowercase()
            if (lower.startsWith(needle) && seen.add(lower)) {
                // Pull the rich entry from the bundled set if we have one; otherwise
                // fabricate a minimal entry so the popup still has something to show.
                val rich = findExact(lower)
                results.add(rich ?: CompletionEntry(name = hint, arity = "lesson", doc = ""))
            }
        }

        // Then the bundled set in alphabetical order.
        if (results.size < max) {
            val start = lowerBound(needle)
            var i = start
            while (i < keys.size && keys[i].startsWith(needle) && results.size < max) {
                if (seen.add(keys[i])) results.add(sorted[i])
                i++
            }
        }
        return results
    }

    /**
     * Locate the symbol-shaped run that the cursor is in/just after.
     *
     * Returns `null` when the cursor is in a context where completion shouldn't fire
     * (whitespace, inside a string, inside a comment, on a paren, etc.).
     */
    private fun partialAtCursor(text: String, cursorOffset: Int): String? {
        val cursor = cursorOffset.coerceIn(0, text.length)
        if (cursor == 0) return ""
        // Walk back from the cursor while characters look symbol-y.
        var start = cursor
        while (start > 0 && isSymbolChar(text[start - 1])) start--
        if (start == cursor) return ""
        // Disqualify: if a `"` precedes our run unbalanced on this line, we're inside a
        // string. Cheap-and-correct check: count `"` on the current line up to start, mod 2.
        val lineStart = text.lastIndexOf('\n', start - 1) + 1 // 0 if not found
        var inString = false
        var i = lineStart
        var inComment = false
        while (i < start) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\' && i + 1 < start) { i += 2; continue }
                if (ch == '"') inString = false
            } else {
                if (ch == ';') { inComment = true; break }
                if (ch == '"') inString = true
            }
            i++
        }
        if (inString || inComment) return null
        return text.substring(start, cursor)
    }

    private fun findExact(lowerName: String): CompletionEntry? {
        val idx = lowerBound(lowerName)
        return if (idx < keys.size && keys[idx] == lowerName) sorted[idx] else null
    }

    /** Lowest index `i` such that `keys[i] >= needle`. */
    private fun lowerBound(needle: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < needle) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Symbol character class for completion purposes. Slightly stricter than the tokenizer
     * (we don't allow `(` or `'` to be part of a partial); the goal is "what would the user
     * consider the word they're typing".
     */
    private fun isSymbolChar(c: Char): Boolean {
        if (c.isLetterOrDigit()) return true
        return when (c) {
            '-', '_', '*', '+', '/', '<', '>', '=', '?', '!', ':', '&', '.', '%', '$' -> true
            else -> false
        }
    }

    companion object {
        private const val ASSET = "cl-symbols.json"

        /**
         * One process-wide engine, lazily built from `assets/cl-symbols.json`. Subsequent
         * calls return the cached instance. Safe to call from any thread.
         */
        @Volatile private var cached: CompletionEngine? = null

        suspend fun loadFromAssets(context: Context): CompletionEngine {
            cached?.let { return it }
            return withContext(Dispatchers.IO) {
                cached ?: synchronized(this) {
                    cached ?: buildFromAssets(context).also { cached = it }
                }
            }
        }

        private fun buildFromAssets(context: Context): CompletionEngine {
            val raw = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            val entries: List<CompletionEntry> = json.decodeFromString(raw)
            return CompletionEngine(entries)
        }

        /**
         * Test-only synchronous factory. Lets unit tests build an engine without touching
         * AssetManager.
         */
        fun fromEntries(entries: List<CompletionEntry>): CompletionEngine = CompletionEngine(entries)
    }
}
