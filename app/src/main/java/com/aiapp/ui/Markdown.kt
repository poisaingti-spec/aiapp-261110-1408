package com.aiapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import com.aiapp.llm.MediaItem
import com.aiapp.llm.MediaSearch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Renders the Markdown that models usually write: headings, lists, quotes, code blocks and inline styles. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = androidx.compose.runtime.remember(text) { splitCode(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            if (b.isCode) CodeBlock(b.lang, b.text) else Prose(b.text)
        }
    }
}

private data class Block(val isCode: Boolean, val text: String, val lang: String = "")

private fun splitCode(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val buf = StringBuilder()
    var inCode = false
    var lang = ""
    for (line in text.lines()) {
        val t = line.trimStart()
        if (t.startsWith("```")) {
            if (inCode) {
                out += Block(true, buf.toString().trimEnd('\n'), lang)
                buf.clear()
                inCode = false
            } else {
                if (buf.isNotBlank()) out += Block(false, buf.toString().trim('\n'))
                buf.clear()
                lang = t.removePrefix("```").trim()
                inCode = true
            }
        } else {
            buf.appendLine(line)
        }
    }
    if (buf.isNotBlank()) out += Block(inCode, buf.toString().trim('\n'), lang)
    return out
}

@Composable
private fun Prose(text: String) {
    val body = MaterialTheme.typography.bodyLarge
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            val t = line.trimStart()
            when {
                t.isEmpty() -> Spacer(Modifier.height(4.dp))
                Regex("^!\\[[^\\]]*]\\((https?://[^)\\s]+)\\)$").matches(t) -> {
                    val url = Regex("\\((https?://[^)\\s]+)\\)").find(t)!!.groupValues[1]
                    NetImage(url, contentScale = androidx.compose.ui.layout.ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
                }
                MediaSearch.youtubeId(t.trim('<', '>')) != null && t.startsWith("http") && !t.contains(' ') ->
                    VideoCard(MediaItem(true, t, "https://i.ytimg.com/vi/${MediaSearch.youtubeId(t)}/hqdefault.jpg", "Video", "YouTube"))
                t.startsWith("### ") -> Text(styled(t.removePrefix("### ")), style = MaterialTheme.typography.titleSmall)
                t.startsWith("## ") -> Text(styled(t.removePrefix("## ")), style = MaterialTheme.typography.titleMedium)
                t.startsWith("# ") -> Text(styled(t.removePrefix("# ")), style = MaterialTheme.typography.titleLarge)
                t == "---" || t == "***" -> Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxWidth().height(1.dp)) {}
                t.startsWith("> ") -> Row {
                    Surface(color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(3.dp).height(20.dp)) {}
                    Spacer(Modifier.width(8.dp))
                    Text(styled(t.removePrefix("> ")), style = body.copy(fontStyle = FontStyle.Italic))
                }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") -> ListLine("•", t.drop(2), indent(line), body)
                Regex("^\\d+[.)] ").containsMatchIn(t) -> {
                    val num = t.substringBefore(' ')
                    ListLine(num, t.substringAfter(' '), indent(line), body)
                }
                t.startsWith("|") -> Text(t, style = body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                else -> Text(styled(t), style = body)
            }
        }
    }
}

private fun indent(line: String) = (line.length - line.trimStart().length) / 2

@Composable
private fun ListLine(marker: String, content: String, level: Int, style: TextStyle) {
    Row(Modifier.padding(start = (level * 16).dp)) {
        Text(marker, style = style, modifier = Modifier.width(24.dp))
        Text(styled(content), style = style)
    }
}

/** **bold**, *italic*, `code` and [links](url). */
@Composable
private fun styled(s: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val link = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) && s.indexOf("**", i + 2) > i + 2 -> {
                    val end = s.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, end)) }
                    i = end + 2
                }
                s[i] == '`' && s.indexOf('`', i + 1) > i + 1 -> {
                    val end = s.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                }
                (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' && s.indexOf(s[i], i + 1) > i + 1 &&
                    (i == 0 || !s[i - 1].isLetterOrDigit()) -> {
                    val end = s.indexOf(s[i], i + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, end)) }
                    i = end + 1
                }
                s[i] == '[' && s.indexOf("](", i) > i && s.indexOf(')', s.indexOf("](", i)) > 0 -> {
                    val mid = s.indexOf("](", i)
                    val end = s.indexOf(')', mid)
                    withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { append(s.substring(i + 1, mid)) }
                    i = end + 1
                }
                else -> {
                    append(s[i])
                    i++
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
            }
            Text(
                code,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}
