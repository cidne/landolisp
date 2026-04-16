package com.landolisp.ui.markdown

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.landolisp.ui.theme.CodeTextStyle

/**
 * Renders a tiny subset of Markdown — enough for lesson prose. NOT a full implementation;
 * see [MarkdownParser] for what is supported.
 *
 * Per the project rules we deliberately avoid pulling in a third-party Markdown library.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block -> Block(block) }
    }
}

@Composable
private fun Block(block: MarkdownBlock) {
    when (block) {
        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            }
            Text(text = renderInline(block.text), style = style)
        }
        is MarkdownBlock.Paragraph -> {
            Text(text = renderInline(block.text), style = MaterialTheme.typography.bodyLarge)
        }
        is MarkdownBlock.BulletList -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.items.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "\u2022",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = renderInline(item),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
        is MarkdownBlock.CodeFence -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = block.code,
                    style = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        is MarkdownBlock.Spacer -> Spacer(Modifier.height(4.dp))
    }
}

private fun renderInline(text: String): AnnotatedString = buildAnnotatedString {
    InlineMarkdown.render(text, this)
}
