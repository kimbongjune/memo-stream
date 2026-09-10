package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.*

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

private val INLINE = Regex(
    """(\*\*|__)(.+?)\1|(\*|_)(.+?)\3|~~(.+?)~~|`([^`]+)`|\[([^\]]+)]\(([^)]+)\)|(https?://\S+)"""
)

fun markdownToAnnotated(
    source: String,
    linkColor: androidx.compose.ui.graphics.Color,
    codeColor: androidx.compose.ui.graphics.Color,
    highlight: String = "",
    highlightBackground: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    source.lines().forEachIndexed { index, rawLine ->
        if (index > 0) {
            append('\n')
        }
        var line = rawLine
        var prefix = ""
        var headingLevel = 0

        val heading = Regex("""^(#{1,6})\s+(.*)""").find(line)
        if (heading != null) {
            headingLevel = heading.groupValues[1].length
            line = heading.groupValues[2]
        }
        val bullet = Regex("""^(\s*)([-*+])\s+(.*)""").find(line)
        if (bullet != null) {
            prefix = bullet.groupValues[1] + "• "
            line = bullet.groupValues[3]
        }
        val ordered = Regex("""^(\s*)(\d+[.)])\s+(.*)""").find(line)
        if (ordered != null) {
            prefix = ordered.groupValues[1] + ordered.groupValues[2] + " "
            line = ordered.groupValues[3]
        }
        val quote = Regex("""^>\s?(.*)""").find(line)
        if (quote != null) {
            prefix = "│ "
            line = quote.groupValues[1]
        }

        val start = length
        if (prefix.isNotEmpty()) {
            append(prefix)
        }
        appendInline(line, linkColor, codeColor)
        if (headingLevel > 0) {
            addStyle(
                SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = when (headingLevel) {
                        1 -> 20.sp
                        2 -> 18.sp
                        else -> 16.sp
                    }
                ),
                start, length
            )
        }
        if (quote != null) {
            addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
        }
    }

    if (highlight.isNotBlank() && highlightBackground != androidx.compose.ui.graphics.Color.Unspecified) {
        val text = toAnnotatedString().text
        var from = text.indexOf(highlight, ignoreCase = true)
        while (from >= 0) {
            addStyle(SpanStyle(background = highlightBackground), from, from + highlight.length)
            from = text.indexOf(highlight, from + highlight.length, ignoreCase = true)
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInline(
    line: String,
    linkColor: androidx.compose.ui.graphics.Color,
    codeColor: androidx.compose.ui.graphics.Color,
) {
    var cursor = 0
    for (match in INLINE.findAll(line)) {
        if (match.range.first > cursor) {
            append(line.substring(cursor, match.range.first))
        }
        val groups = match.groupValues
        when {
            groups[2].isNotEmpty() -> {
                val start = length
                append(groups[2])
                addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
            }
            groups[4].isNotEmpty() -> {
                val start = length
                append(groups[4])
                addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
            }
            groups[5].isNotEmpty() -> {
                val start = length
                append(groups[5])
                addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, length)
            }
            groups[6].isNotEmpty() -> {
                val start = length
                append(groups[6])
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor), start, length)
            }
            groups[7].isNotEmpty() -> {
                val start = length
                append(groups[7])
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    start, length
                )
                addStringAnnotation("url", groups[8], start, length)
            }
            groups[9].isNotEmpty() -> {
                val start = length
                append(groups[9])
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    start, length
                )
                addStringAnnotation("url", groups[9], start, length)
            }
            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < line.length) {
        append(line.substring(cursor))
    }
}

@Composable
fun MarkdownBody(
    markdown: String,
    highlight: String,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit,
) {
    val linkColor = memo.accent
    val codeColor = memo.fgSoft
    val highlightBackground = memo.markBg
    val annotated = remember(markdown, highlight, linkColor) {
        markdownToAnnotated(markdown, linkColor, codeColor, highlight, highlightBackground)
    }
    var layout by remember(annotated) {
        mutableStateOf<TextLayoutResult?>(null)
    }

    SelectionContainer {
        Text(
            text = annotated,
            modifier = modifier.pointerInput(annotated) {
                detectTapGestures { offset ->
                    val result = layout ?: return@detectTapGestures
                    val position = result.getOffsetForPosition(offset)
                    val target = annotated
                        .getStringAnnotations("url", position, position)
                        .firstOrNull()
                    if (target != null) {
                        onLinkClick(target.item)
                    }
                }
            },
            onTextLayout = {
                layout = it
            },
            style = LocalTextStyle.current.copy(lineHeight = 20.sp, color = memo.fg),
        )
    }
}
