package com.notesapp.offline.ui.theme

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.notesapp.offline.data.StyleRun

/**
 * True toggle-based rich text: style lives as metadata (StyleRun ranges)
 * alongside the plain body text, never as inline markers. Bold/Italic/
 * Underline toolbar buttons flip a toggle; whatever gets typed next is
 * tagged with that style. Bullet/numbered lines are still literal visible
 * characters ("• " / "1. ") since that's how every note app does lists —
 * only inline character styling needed the marker-free treatment.
 */

/** Finds the common-prefix/common-suffix edit between two text states. */
data class TextEdit(val start: Int, val oldEnd: Int, val newEnd: Int) {
    val deletedLength: Int get() = oldEnd - start
    val insertedLength: Int get() = newEnd - start
}

fun computeTextEdit(oldText: String, newText: String): TextEdit {
    var prefix = 0
    val maxPrefix = minOf(oldText.length, newText.length)
    while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++

    var oldEnd = oldText.length
    var newEnd = newText.length
    while (oldEnd > prefix && newEnd > prefix && oldText[oldEnd - 1] == newText[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    return TextEdit(prefix, oldEnd, newEnd)
}

private fun deleteRange(runs: List<StyleRun>, delStart: Int, delEnd: Int): List<StyleRun> {
    if (delStart >= delEnd) return runs
    val len = delEnd - delStart
    val result = mutableListOf<StyleRun>()
    for (r in runs) {
        when {
            r.end <= delStart -> result.add(r)
            r.start >= delEnd -> result.add(r.copy(start = r.start - len, end = r.end - len))
            else -> {
                if (r.start < delStart) result.add(r.copy(start = r.start, end = delStart))
                if (r.end > delEnd) result.add(r.copy(start = delEnd - len, end = r.end - len))
            }
        }
    }
    return result.filter { it.end > it.start }
}

private fun insertGap(runs: List<StyleRun>, at: Int, len: Int): List<StyleRun> {
    if (len <= 0) return runs
    return runs.map { r ->
        when {
            r.end <= at -> r
            r.start >= at -> r.copy(start = r.start + len, end = r.end + len)
            else -> r.copy(end = r.end + len) // insertion lands inside this run — extend it
        }
    }
}

/**
 * Applies a text edit (oldText -> newText) to a set of style runs, keeping
 * their ranges correct, and tags the newly-inserted span (if any) with
 * whichever styles are currently toggled on.
 */
fun applyEditToRuns(
    runs: List<StyleRun>,
    oldText: String,
    newText: String,
    activeBold: Boolean,
    activeItalic: Boolean,
    activeUnderline: Boolean
): List<StyleRun> {
    val edit = computeTextEdit(oldText, newText)
    if (edit.deletedLength == 0 && edit.insertedLength == 0) return runs

    var result = runs
    if (edit.deletedLength > 0) result = deleteRange(result, edit.start, edit.oldEnd)
    if (edit.insertedLength > 0) {
        result = insertGap(result, edit.start, edit.insertedLength)
        if (activeBold || activeItalic || activeUnderline) {
            result = result + StyleRun(edit.start, edit.start + edit.insertedLength, activeBold, activeItalic, activeUnderline)
        }
    }
    return result.sortedBy { it.start }
}

fun annotateWithRuns(text: String, runs: List<StyleRun>): AnnotatedString {
    return AnnotatedString.Builder(text).apply {
        for (r in runs) {
            val start = r.start.coerceIn(0, text.length)
            val end = r.end.coerceIn(0, text.length)
            if (start >= end) continue
            addStyle(
                SpanStyle(
                    fontWeight = if (r.bold) FontWeight.Bold else null,
                    fontStyle = if (r.italic) FontStyle.Italic else null,
                    textDecoration = if (r.underline) TextDecoration.Underline else null
                ),
                start,
                end
            )
        }
    }.toAnnotatedString()
}

/** For the note editor's own TextField — same length, so identity offset mapping is safe. */
class RunsVisualTransformation(private val runs: List<StyleRun>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(annotateWithRuns(text.text, runs), OffsetMapping.Identity)
    }
}

/** Read-only rendering for list-card previews. */
fun richTextPreview(body: String, runs: List<StyleRun>): AnnotatedString = annotateWithRuns(body, runs)
