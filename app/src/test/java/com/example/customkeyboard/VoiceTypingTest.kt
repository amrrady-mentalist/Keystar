package com.example.customkeyboard

import org.junit.Assert.*
import org.junit.Test

class VoiceTypingTest {

    private class VoiceTypingStateMachine {
        var isVoiceListening = false
        var voiceDisplayText = ""
        var voiceIsStatusPrompt = true
        var uncommittedVoiceText = ""
        val committedTextList = mutableListOf<String>()

        fun startListening(isArabic: Boolean) {
            isVoiceListening = true
            voiceDisplayText = ""
            voiceIsStatusPrompt = true
            uncommittedVoiceText = ""
        }

        fun onErrorTimeout(isArabic: Boolean) {
            voiceIsStatusPrompt = true
            voiceDisplayText = if (isArabic) "تكلّم الآن..." else "Listening..."
            uncommittedVoiceText = ""
        }

        fun onErrorTapMic(isArabic: Boolean) {
            voiceIsStatusPrompt = true
            voiceDisplayText = if (isArabic) "اضغط على الميكروفون للتحدث" else "Tap mic to speak"
            uncommittedVoiceText = ""
        }

        fun onPartialSpeech(partial: String) {
            voiceIsStatusPrompt = false
            voiceDisplayText = partial
            uncommittedVoiceText = partial
        }

        fun onFinalSpeech(finalText: String) {
            committedTextList.add("$finalText ")
            voiceIsStatusPrompt = false
            voiceDisplayText = finalText
            uncommittedVoiceText = ""
        }

        fun commitVoicePreview() {
            if (!voiceIsStatusPrompt && uncommittedVoiceText.isNotBlank()) {
                committedTextList.add("${uncommittedVoiceText.trim()} ")
                uncommittedVoiceText = ""
            }
        }

        fun clickCheckmark() {
            commitVoicePreview()
            isVoiceListening = false
            voiceDisplayText = ""
            voiceIsStatusPrompt = true
            uncommittedVoiceText = ""
        }
    }

    @Test
    fun testCheckmarkDoesNotCommitStatusPrompts() {
        val machine = VoiceTypingStateMachine()
        machine.startListening(isArabic = true)

        // Case 1: Timeout error occurred, displaying "تكلّم الآن..."
        machine.onErrorTimeout(isArabic = true)
        assertEquals("تكلّم الآن...", machine.voiceDisplayText)
        assertTrue(machine.voiceIsStatusPrompt)
        assertEquals("", machine.uncommittedVoiceText)

        // User clicks the checkmark ✓
        machine.clickCheckmark()

        // Verify nothing was committed into the editor text
        assertTrue(machine.committedTextList.isEmpty())

        // Case 2: Error occurred, displaying "Tap mic to speak"
        machine.startListening(isArabic = false)
        machine.onErrorTapMic(isArabic = false)
        assertEquals("Tap mic to speak", machine.voiceDisplayText)
        assertTrue(machine.voiceIsStatusPrompt)

        // User clicks the checkmark ✓
        machine.clickCheckmark()

        // Verify still nothing committed
        assertTrue(machine.committedTextList.isEmpty())
    }

    @Test
    fun testFinalSpeechAlreadyCommittedIsNotDuplicatedOnCheckmark() {
        val machine = VoiceTypingStateMachine()
        machine.startListening(isArabic = true)

        // Speech arrives and is finalized
        machine.onFinalSpeech("السلام عليكم")
        assertEquals(listOf("السلام عليكم "), machine.committedTextList)

        // Followed by silence / timeout before user clicks checkmark
        machine.onErrorTimeout(isArabic = true)

        // User clicks the checkmark ✓
        machine.clickCheckmark()

        // Verify the spoken text was committed exactly once, not duplicated, and prompt was not added
        assertEquals(listOf("السلام عليكم "), machine.committedTextList)
    }

    @Test
    fun testPartialSpeechIsCommittedOnCheckmark() {
        val machine = VoiceTypingStateMachine()
        machine.startListening(isArabic = false)

        // User spoke partial sentence but tapped checkmark before final onResults
        machine.onPartialSpeech("Quick brown fox")
        assertFalse(machine.voiceIsStatusPrompt)
        assertEquals("Quick brown fox", machine.uncommittedVoiceText)

        machine.clickCheckmark()

        // Partial speech is safely committed
        assertEquals(listOf("Quick brown fox "), machine.committedTextList)
    }
}
