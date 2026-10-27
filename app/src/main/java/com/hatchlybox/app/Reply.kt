package com.hatchlybox.nightly

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A reply split into the pieces the chat draws differently.
sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val text: String) : Block
    data class Code(val text: String) : Block
    data class Tool(val text: String) : Block
    data class Error(val text: String) : Block
}

fun parseReply(text: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) blocks += Block.Para(para.toString().trimEnd()); para.clear() }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                blocks += Block.Code(code.toString().trimEnd('\n'))
            }
            line.startsWith("› ") -> { flush(); blocks += Block.Tool(line.removePrefix("› ")) }
            line.startsWith("! ") -> { flush(); blocks += Block.Error(line.removePrefix("! ")) }
            Regex("^#{1,6} ").containsMatchIn(t) -> { flush(); blocks += Block.Heading(t.trimStart('#').trim()) }
            t.isEmpty() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return blocks
}

// **bold** and `code` inside a paragraph.
@Composable
private fun inline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    return remember(text, codeBg) {
        buildAnnotatedString {
            val re = Regex("""\*\*(.+?)\*\*|`([^`]+)`""")
            var at = 0
            for (m in re.findAll(text)) {
                append(text.substring(at, m.range.first))
                m.groups[1]?.let { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(it.value) } }
                m.groups[2]?.let { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 13.sp)) { append(it.value) } }
                at = m.range.last + 1
            }
            append(text.substring(at))
        }
    }
}

@Composable
fun ReplyBody(text: String, mono: Boolean) {
    val blocks = remember(text) { parseReply(text) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Para -> if (mono) MonoText(b.text) else Text(inline(b.text), style = MaterialTheme.typography.bodyMedium)
                is Block.Heading -> Text(inline(b.text), style = MaterialTheme.typography.titleSmall)
                is Block.Code -> CodeBox(b.text)
                is Block.Tool -> Step(Icons.Outlined.Build, b.text, MaterialTheme.colorScheme.onSurfaceVariant)
                is Block.Error -> Step(Icons.Outlined.ErrorOutline, b.text, MaterialTheme.colorScheme.error, wrap = true)
            }
        }
    }
}

@Composable
fun MonoText(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = color)
}

@Composable
private fun CodeBox(text: String) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) { MonoText(text) }
}

@Composable
private fun Step(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color, wrap: Boolean = false) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 1.dp).size(14.dp))
        Text(
            text, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = if (wrap) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
