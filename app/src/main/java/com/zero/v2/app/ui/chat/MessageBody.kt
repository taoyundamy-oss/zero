package com.zero.v2.app.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zero.v2.app.ui.theme.ZeroShapes

/** Text-only Markdown blocks: no HTML execution, remote media loading, or embedded scripts. */
@Composable
internal fun MessageBody(
    text: String,
    modifier: Modifier = Modifier,
    paragraph: @Composable (String) -> Unit,
) {
    val blocks = remember(text) { MarkdownBlocks.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> paragraph(block.text)
                is MarkdownBlock.Heading -> androidx.compose.material3.ProvideTextStyle(
                    if (block.level <= 2) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
                ) { paragraph(block.text) }
                is MarkdownBlock.Code -> CodeBlock(block)
                is MarkdownBlock.Table -> TableBlock(block, paragraph)
            }
        }
    }
}

@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    val clipboard = LocalClipboardManager.current
    Surface(shape = ZeroShapes.card, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(block.language.ifBlank { "文本" }, Modifier.weight(1f).padding(top = 12.dp),
                    style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)) }) { Text("复制") }
            }
            Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TableBlock(block: MarkdownBlock.Table, paragraph: @Composable (String) -> Unit) {
    Surface(shape = ZeroShapes.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
            Row {
                block.headers.forEach { header ->
                    Column(Modifier.width(180.dp).padding(8.dp)) {
                        androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)) { paragraph(header) }
                    }
                }
            }
            HorizontalDivider(Modifier.width((180 * block.headers.size).dp))
            block.rows.forEach { row ->
                Row {
                    row.forEach { cell -> Column(Modifier.width(180.dp).padding(8.dp)) { paragraph(cell) } }
                }
            }
        }
    }
}
