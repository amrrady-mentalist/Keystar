package com.notesapp.offline.data

import kotlinx.serialization.Serializable

/**
 * A styled character range within a Note's body text — [start, end) in
 * plain-text offsets. Replaces the earlier markdown-marker approach
 * entirely: the body text itself never contains ** _ ~ characters: style
 * is tracked as metadata alongside the text, the way a real rich-text
 * editor does it.
 */
@Serializable
data class StyleRun(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false
)
