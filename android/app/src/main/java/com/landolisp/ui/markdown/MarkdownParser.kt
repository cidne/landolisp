package com.landolisp.ui.markdown

/**
 * Minimal block-level Markdown parser. Supports:
 *   - ATX headings (`#`, `##`, `###` …)
 *   - Paragraphs (separated by blank lines)
 *   - Bullet lists (`-` or `*` at start of line)
 *   - Fenced code blocks delimited by triple backticks
 *
 * Inline rendering (`**bold**`, `*italic*`, `` `code` ``) is handled by [InlineMarkdown]
 * once the block layer hands plain text to the UI.
 *
 * This is intentionally tiny — not CommonMark. Lessons should stay within this subset.
 */
object MarkdownParser {

    fun parse(input: String): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val lines = input.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    val fenceEnd = (i + 1 until lines.size).firstOrNull { lines[it].startsWith("```") }
                    val end = fenceEnd ?: lines.size
                    val body = lines.subList(i + 1, end).joinToString("\n")
                    blocks += MarkdownBlock.CodeFence(body)
                    i = if (fenceEnd != null) end + 1 else end
                }
                line.trimStart().startsWith("# ") -> {
                    blocks += MarkdownBlock.Heading(1, line.trimStart().removePrefix("# ").trim())
                    i++
                }
                line.trimStart().startsWith("## ") -> {
                    blocks += MarkdownBlock.Heading(2, line.trimStart().removePrefix("## ").trim())
                    i++
                }
                line.trimStart().startsWith("### ") -> {
                    blocks += MarkdownBlock.Heading(3, line.trimStart().removePrefix("### ").trim())
                    i++
                }
                isBullet(line) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size && isBullet(lines[i])) {
                        items += lines[i].trimStart().removePrefix("-").removePrefix("*").trim()
                        i++
                    }
                    blocks += MarkdownBlock.BulletList(items)
                }
                line.isBlank() -> {
                    i++
                }
                else -> {
                    val buf = StringBuilder()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !lines[i].trimStart().startsWith("#") &&
                        !isBullet(lines[i]) && !lines[i].startsWith("```")
                    ) {
                        if (buf.isNotEmpty()) buf.append(' ')
                        buf.append(lines[i].trim())
                        i++
                    }
                    if (buf.isNotEmpty()) {
                        blocks += MarkdownBlock.Paragraph(buf.toString())
                    }
                }
            }
        }
        return blocks
    }

    private fun isBullet(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("- ") || trimmed.startsWith("* ")
    }
}

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class BulletList(val items: List<String>) : MarkdownBlock
    data class CodeFence(val code: String) : MarkdownBlock
    data object Spacer : MarkdownBlock
}
