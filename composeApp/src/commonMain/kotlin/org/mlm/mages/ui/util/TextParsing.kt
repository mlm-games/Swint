package org.mlm.mages.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

fun parseMarkdown(input: String): AnnotatedString {
    val boldStyle = SpanStyle(fontWeight = FontWeight.Bold)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    val codeStyle = SpanStyle(background = Color(0x33FFFFFF))
    val linkStyle = SpanStyle(color = Color(0xFF7E57C2), textDecoration = TextDecoration.Underline)

    val out = StringBuilder(input.length)
    data class Range(val start: Int, val end: Int, val style: SpanStyle)
    val spans = mutableListOf<Range>()

    var i = 0
    var boldStart = -1
    var italicStart = -1
    var codeStart = -1

    fun startsWith(token: String) = input.regionMatches(i, token, 0, token.length)
    fun isLinkChar(c: Char) = !c.isWhitespace()

    while (i < input.length) {
        when {
            input[i] == '`' -> {
                if (codeStart >= 0) {
                    if (out.length > codeStart) spans += Range(codeStart, out.length, codeStyle)
                    codeStart = -1
                } else {
                    codeStart = out.length
                }
                i += 1
            }
            startsWith("**") -> {
                if (boldStart >= 0) {
                    if (out.length > boldStart) spans += Range(boldStart, out.length, boldStyle)
                    boldStart = -1
                } else {
                    boldStart = out.length
                }
                i += 2
            }
            input[i] == '*' -> {
                if (italicStart >= 0) {
                    if (out.length > italicStart) spans += Range(italicStart, out.length, italicStyle)
                    italicStart = -1
                } else {
                    italicStart = out.length
                }
                i += 1
            }
            startsWith("http://") || startsWith("https://") -> {
                val start = out.length
                var j = i
                while (j < input.length && isLinkChar(input[j])) j++
                out.append(input, i, j)
                if (out.length > start) spans += Range(start, out.length, linkStyle)
                i = j
            }
            else -> {
                out.append(input[i])
                i += 1
            }
        }
    }

    return buildAnnotatedString {
        append(out.toString())
        val textLen = out.length
        for (r in spans) {
            val s = r.start.coerceIn(0, textLen)
            val e = r.end.coerceIn(s, textLen)
            if (e > s) addStyle(r.style, s, e)
        }
    }
}