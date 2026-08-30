package com.notesapp.offline.data

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Tracks the in-memory state of an active covert typing session inside a note.
 */
data class CovertSessionState(
    var isArmed: Boolean = false,
    var secretBuffer: String = "",
    var capturedSecretWord: String = "",
    var hasCapturedWord: Boolean = false,
    var covertSentenceIndex: Int = 0,
    var consecutiveSpaces: Int = 0,
    var covertLineIndex: Int = -1
)

/**
 * Engine that powers the Covert Typing effect:
 * 1. Covert Sentence Line (1st non-blank line typed): Converts keyboard input keystroke-by-keystroke
 *    into the pre-saved sentence, capturing the actual typed letters into a hidden secret buffer.
 *    Double-space ("  ") or Enter ("\n") locks in the secret word and triggers the Inject API send.
 *    Leading blank/empty lines are ignored, so typing can start on any line.
 * 2. Subsequent Lines: Disregards empty/whitespace lines. When spectators type words on new lines,
 *    forces the corresponding letter of the secret word at the performer's configured position
 *    (1st, 2nd, 3rd, or last letter).
 */
object CovertTypingEngine {

    private fun getLineIndex(text: String, pos: Int): Int {
        var lineIdx = 0
        val clamped = pos.coerceIn(0, text.length)
        for (i in 0 until clamped) {
            if (text[i] == '\n') lineIdx++
        }
        return lineIdx
    }

    fun processEdit(
        oldValue: TextFieldValue,
        newValue: TextFieldValue,
        config: CovertTypingConfig,
        state: CovertSessionState,
        onWordCaptured: (String) -> Unit
    ): TextFieldValue {
        if (!state.isArmed && !state.hasCapturedWord) {
            return newValue
        }

        val oldText = oldValue.text
        val newText = newValue.text

        // Deletion / Backspace handling
        if (newText.length < oldText.length) {
            val deletedCount = oldText.length - newText.length
            val oldCursor = oldValue.selection.min
            val oldLineIndex = getLineIndex(oldText, oldCursor)
            val oldLines = oldText.split('\n')

            val isEditingCovertLine = (state.covertLineIndex != -1 && oldLineIndex == state.covertLineIndex) ||
                (state.covertLineIndex == -1 && oldLines.take(oldLineIndex).all { it.trim().isEmpty() })

            if (isEditingCovertLine && state.covertSentenceIndex > 0) {
                state.covertSentenceIndex = (state.covertSentenceIndex - deletedCount).coerceAtLeast(0)
                if (!state.hasCapturedWord && state.secretBuffer.isNotEmpty()) {
                    val dropAmount = deletedCount.coerceAtMost(state.secretBuffer.length)
                    state.secretBuffer = state.secretBuffer.dropLast(dropAmount)
                }
                state.consecutiveSpaces = 0
            }
            return newValue
        }

        // Insertion / Typing handling
        if (newText.length > oldText.length) {
            val cursor = newValue.selection.min
            val insertLength = newText.length - oldText.length
            val startInsert = (cursor - insertLength).coerceAtLeast(0)
            val insertedChars = newText.substring(startInsert, cursor)

            val oldLines = oldText.split('\n')
            val currentLineIndex = getLineIndex(oldText, startInsert)

            val isCovertInputLine = if (state.covertLineIndex != -1) {
                currentLineIndex == state.covertLineIndex
            } else {
                // If not locked yet, check if all lines prior to current line are blank / whitespace-only
                oldLines.take(currentLineIndex).all { it.trim().isEmpty() }
            }

            if (isCovertInputLine) {
                state.covertLineIndex = currentLineIndex

                if (!insertedChars.contains('\n')) {
                    // Keystroke-by-keystroke sentence masking & secret capture
                    val sentence = config.preSavedSentence.ifBlank { "Notes" }
                    val replacementBuilder = StringBuilder()

                    for (char in insertedChars) {
                        if (!state.hasCapturedWord) {
                            if (char == ' ') {
                                state.consecutiveSpaces++
                                if (state.consecutiveSpaces >= 2) {
                                    val extracted = state.secretBuffer.trim()
                                    if (extracted.isNotBlank()) {
                                        state.capturedSecretWord = extracted
                                        state.hasCapturedWord = true
                                        onWordCaptured(extracted)
                                    }
                                }
                            } else {
                                state.consecutiveSpaces = 0
                                state.secretBuffer += char
                            }
                        }

                        val displayChar = if (state.covertSentenceIndex < sentence.length) {
                            val c = sentence[state.covertSentenceIndex]
                            state.covertSentenceIndex++
                            c
                        } else {
                            '.'
                        }
                        replacementBuilder.append(displayChar)
                    }

                    val replacedText = oldText.substring(0, startInsert) + replacementBuilder.toString() + oldText.substring(startInsert)
                    val newCursor = startInsert + replacementBuilder.length
                    return TextFieldValue(replacedText, TextRange(newCursor))
                } else {
                    // Enter pressed on the covert line
                    if (!state.hasCapturedWord && state.secretBuffer.isNotBlank()) {
                        val extracted = state.secretBuffer.trim()
                        if (extracted.isNotBlank()) {
                            state.capturedSecretWord = extracted
                            state.hasCapturedWord = true
                            onWordCaptured(extracted)
                        }
                    }
                    return newValue
                }
            }

            // Spectator reveal phase on subsequent lines (disregarding empty/whitespace lines)
            if (config.revealOnSubsequentLines && state.capturedSecretWord.isNotBlank() && state.covertLineIndex != -1) {
                val newLines = newText.split('\n')
                val editLineIndex = getLineIndex(newText, startInsert)

                if (editLineIndex > state.covertLineIndex) {
                    // Count how many non-blank spectator lines exist strictly before this edit line
                    var nonBlankSpectatorLinesBefore = 0
                    for (i in (state.covertLineIndex + 1) until editLineIndex) {
                        if (i < newLines.size && newLines[i].trim().isNotEmpty()) {
                            nonBlankSpectatorLinesBefore++
                        }
                    }

                    val spectatorIndex = nonBlankSpectatorLinesBefore
                    if (spectatorIndex in 0 until state.capturedSecretWord.length) {
                        val targetChar = state.capturedSecretWord[spectatorIndex]
                        val lineStartPos = newText.lastIndexOf('\n', (startInsert - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
                        val linePrefix = newText.substring(lineStartPos, startInsert)
                        val trimmedPrefix = linePrefix.trimStart()

                        when (config.targetLetterPosition) {
                            CovertLetterPosition.FIRST -> {
                                if (linePrefix.trim().isEmpty() && insertedChars.trim().isNotEmpty() && !insertedChars.contains('\n')) {
                                    val leadingSpaces = insertedChars.takeWhile { it == ' ' || it == '\t' }
                                    val afterSpaces = insertedChars.substring(leadingSpaces.length)
                                    if (afterSpaces.isNotEmpty()) {
                                        val forcedChar = targetChar.uppercaseChar()
                                        val newInserted = leadingSpaces + forcedChar + afterSpaces.drop(1)
                                        val replacedText = oldText.substring(0, startInsert) + newInserted + oldText.substring(startInsert)
                                        return TextFieldValue(replacedText, TextRange(startInsert + newInserted.length))
                                    }
                                }
                            }
                            CovertLetterPosition.SECOND -> {
                                if (trimmedPrefix.length == 1 && insertedChars.trim().isNotEmpty() && !insertedChars.contains('\n')) {
                                    val forcedChar = targetChar.lowercaseChar()
                                    val newInserted = forcedChar + insertedChars.drop(1)
                                    val replacedText = oldText.substring(0, startInsert) + newInserted + oldText.substring(startInsert)
                                    return TextFieldValue(replacedText, TextRange(startInsert + newInserted.length))
                                }
                            }
                            CovertLetterPosition.THIRD -> {
                                if (trimmedPrefix.length == 2 && insertedChars.trim().isNotEmpty() && !insertedChars.contains('\n')) {
                                    val forcedChar = targetChar.lowercaseChar()
                                    val newInserted = forcedChar + insertedChars.drop(1)
                                    val replacedText = oldText.substring(0, startInsert) + newInserted + oldText.substring(startInsert)
                                    return TextFieldValue(replacedText, TextRange(startInsert + newInserted.length))
                                }
                            }
                            CovertLetterPosition.LAST -> {
                                if (insertedChars.contains('\n')) {
                                    val currentLineText = oldText.substring(lineStartPos, startInsert)
                                    if (currentLineText.trim().isNotEmpty()) {
                                        val lastNonSpaceCol = currentLineText.indexOfLast { !it.isWhitespace() }
                                        if (lastNonSpaceCol != -1) {
                                            val lastCharGlobalPos = lineStartPos + lastNonSpaceCol
                                            val forcedChar = targetChar.lowercaseChar()
                                            val modifiedOldText = oldText.substring(0, lastCharGlobalPos) + forcedChar + oldText.substring(lastCharGlobalPos + 1)
                                            val replacedText = modifiedOldText.substring(0, startInsert) + insertedChars + modifiedOldText.substring(startInsert)
                                            return TextFieldValue(replacedText, TextRange(startInsert + insertedChars.length))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return newValue
    }
}
