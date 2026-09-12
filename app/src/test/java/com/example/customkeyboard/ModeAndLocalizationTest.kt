package com.example.customkeyboard

import org.junit.Assert.*
import org.junit.Test

class ModeAndLocalizationTest {

    enum class TestMode { LETTERS, NUMBERS, SYMBOLS }

    @Test
    fun testArabicQuestionMarkCharacter() {
        val arabicQuestionMark = "؟"
        val englishQuestionMark = "?"
        assertNotEquals(arabicQuestionMark, englishQuestionMark)
        assertEquals('\u061F', arabicQuestionMark[0])
    }

    @Test
    fun testPunctuationOrientationMapping() {
        fun getDisplaySymbol(rawKey: String, isArabic: Boolean): String {
            return if (rawKey == "?" && isArabic) "؟" else rawKey
        }

        assertEquals("؟", getDisplaySymbol("?", isArabic = true))
        assertEquals("?", getDisplaySymbol("?", isArabic = false))
        assertEquals("@", getDisplaySymbol("@", isArabic = true))
    }

    @Test
    fun testAltButtonLabelMapping() {
        fun getAltButtonLabel(isArabic: Boolean): String {
            return if (isArabic) "؟123" else "?123"
        }

        assertEquals("؟123", getAltButtonLabel(isArabic = true))
        assertEquals("?123", getAltButtonLabel(isArabic = false))
    }

    @Test
    fun testNumberPadSymbolToggleLabel() {
        fun getSymbolToggleLabel(isArabic: Boolean): String {
            return if (isArabic) "!؟#" else "!?#"
        }

        assertEquals("!؟#", getSymbolToggleLabel(isArabic = true))
        assertEquals("!?#", getSymbolToggleLabel(isArabic = false))
    }

    @Test
    fun testModePersistenceCycle() {
        var currentMode = TestMode.LETTERS
        var lastAltMode = TestMode.NUMBERS

        fun switchMode(mode: TestMode) {
            if (mode == TestMode.NUMBERS || mode == TestMode.SYMBOLS) {
                lastAltMode = mode
            }
            currentMode = mode
        }

        fun onAltKeyClick() {
            switchMode(lastAltMode)
        }

        // 1. Initial state: in LETTERS, default lastAltMode is NUMBERS
        assertEquals(TestMode.LETTERS, currentMode)
        assertEquals(TestMode.NUMBERS, lastAltMode)

        // 2. Click ?123 -> goes to NUMBERS
        onAltKeyClick()
        assertEquals(TestMode.NUMBERS, currentMode)
        assertEquals(TestMode.NUMBERS, lastAltMode)

        // 3. From NUMBERS, click !?# -> goes to SYMBOLS
        switchMode(TestMode.SYMBOLS)
        assertEquals(TestMode.SYMBOLS, currentMode)
        assertEquals(TestMode.SYMBOLS, lastAltMode)

        // 4. From SYMBOLS, click ABC -> returns to LETTERS
        switchMode(TestMode.LETTERS)
        assertEquals(TestMode.LETTERS, currentMode)
        // lastAltMode is remembered as SYMBOLS!
        assertEquals(TestMode.SYMBOLS, lastAltMode)

        // 5. Next time user clicks ?123 -> goes directly to SYMBOLS!
        onAltKeyClick()
        assertEquals(TestMode.SYMBOLS, currentMode)

        // 6. From SYMBOLS, click 123 -> goes to NUMBERS
        switchMode(TestMode.NUMBERS)
        assertEquals(TestMode.NUMBERS, currentMode)
        assertEquals(TestMode.NUMBERS, lastAltMode)

        // 7. From NUMBERS, click ABC -> returns to LETTERS
        switchMode(TestMode.LETTERS)
        assertEquals(TestMode.LETTERS, currentMode)
        // lastAltMode is remembered as NUMBERS!
        assertEquals(TestMode.NUMBERS, lastAltMode)

        // 8. Next time user clicks ?123 -> goes directly to NUMBERS!
        onAltKeyClick()
        assertEquals(TestMode.NUMBERS, currentMode)
    }

    @Test
    fun testArabicAlphabetLayoutExactMatch() {
        // Number row: 10 Arabic-Indic digits
        assertEquals(
            listOf("١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩", "٠"),
            KeyboardLayoutData.arabicNumberRow
        )

        // Row 1: 11 letters
        assertEquals(
            listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"),
            KeyboardLayoutData.arabicRows[0]
        )

        // Row 2: 11 letters
        assertEquals(
            listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
            KeyboardLayoutData.arabicRows[1]
        )

        // Row 3: 10 letters (completed to 11 with backspace)
        assertEquals(
            listOf("ذ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ", "د"),
            KeyboardLayoutData.arabicRows[2]
        )

        // Hint symbols exact match
        assertEquals(
            listOf("%", "\\", "|", "=", "]", "[", ">", "<", "}", "{", "°"),
            KeyboardLayoutData.arabicHints[0]
        )
        assertEquals(
            listOf("@", "#", "$", "_", "&", "-", "+", ")", "(", "/", "~"),
            KeyboardLayoutData.arabicHints[1]
        )
        assertEquals(
            listOf("`", "*", "\"", "'", ":", "؛", "!", "؟", "\\", "%"),
            KeyboardLayoutData.arabicHints[2]
        )
    }

    @Test
    fun testEnglishHintsLayoutExactMatch() {
        assertEquals(3, KeyboardLayoutData.englishRows.size)
        assertEquals(3, KeyboardLayoutData.englishHints.size)

        // Row 0: 10 keys matching Q-P
        assertEquals(10, KeyboardLayoutData.englishRows[0].size)
        assertEquals(10, KeyboardLayoutData.englishHints[0].size)
        assertEquals(
            listOf("%", "\\", "|", "=", "[", "]", "<", ">", "{", "}"),
            KeyboardLayoutData.englishHints[0]
        )

        // Row 1: 9 keys matching A-L
        assertEquals(9, KeyboardLayoutData.englishRows[1].size)
        assertEquals(9, KeyboardLayoutData.englishHints[1].size)
        assertEquals(
            listOf("@", "#", "$", "_", "&", "-", "+", "(", ")"),
            KeyboardLayoutData.englishHints[1]
        )

        // Row 2: 7 keys matching Z-M
        assertEquals(7, KeyboardLayoutData.englishRows[2].size)
        assertEquals(7, KeyboardLayoutData.englishHints[2].size)
        assertEquals(
            listOf("*", "\"", "'", ":", ";", "!", "?"),
            KeyboardLayoutData.englishHints[2]
        )
    }

    @Test
    fun testCovertTypingSimulationExact() {
        val coverSentence = "Hey there, read my mind !!"
        var coverSentenceIndex = 0
        var hasFinalizedPeriod = false
        val rawSecretInputBuffer = StringBuilder()
        var consecutiveSpaceCount = 0
        var capturedSecretWord = ""
        var isImmediateDispatched = false
        val covertSendImmediately = true

        fun simulateKey(originalText: String, isLetter: Boolean, textBeforeCursor: String): String {
            val normalized = textBeforeCursor.replace("\r\n", "\n").replace("\r", "\n")
            val rawLines = normalized.split('\n')
            val currentLineRaw = rawLines.lastOrNull() ?: ""

            if (currentLineRaw.isEmpty()) {
                coverSentenceIndex = 0
                hasFinalizedPeriod = false
                rawSecretInputBuffer.clear()
                consecutiveSpaceCount = 0
            } else if (currentLineRaw.length < coverSentenceIndex) {
                coverSentenceIndex = currentLineRaw.length
            }

            if (originalText == " ") {
                consecutiveSpaceCount++
                if (consecutiveSpaceCount >= 2) {
                    val secretPhrase = rawSecretInputBuffer.toString().trim()
                    rawSecretInputBuffer.clear()
                    consecutiveSpaceCount = 0
                    if (secretPhrase.isNotEmpty()) {
                        capturedSecretWord = secretPhrase
                        if (covertSendImmediately) {
                            isImmediateDispatched = true
                        }
                    }
                } else {
                    rawSecretInputBuffer.append(" ")
                }
            } else {
                consecutiveSpaceCount = 0
                if (isLetter || originalText.isNotEmpty()) {
                    rawSecretInputBuffer.append(originalText)
                }
            }

            val idx = coverSentenceIndex
            return if (idx < coverSentence.length) {
                coverSentenceIndex = idx + 1
                coverSentence[idx].toString()
            } else if (!hasFinalizedPeriod) {
                hasFinalizedPeriod = true
                coverSentenceIndex = idx + 1
                "."
            } else {
                coverSentenceIndex = idx + 1
                if (originalText == " ") {
                    " "
                } else {
                    val loopIdx = (idx - coverSentence.length - 1) % coverSentence.length
                    val safeIdx = if (loopIdx >= 0) loopIdx else 0
                    coverSentence[safeIdx].toString()
                }
            }
        }

        // Simulate typing secret word "Covert" followed by double space
        val secretInput = listOf(
            "C" to true,
            "o" to true,
            "v" to true,
            "e" to true,
            "r" to true,
            "t" to true,
            " " to false,
            " " to false
        )

        var committedText = ""
        for ((char, isLetter) in secretInput) {
            val output = simulateKey(char, isLetter, committedText)
            committedText += output
        }

        // Keystrokes 1..8:
        // 'C' -> 'H'
        // 'o' -> 'e'
        // 'v' -> 'y'
        // 'e' -> ' '
        // 'r' -> 't'
        // 't' -> 'h'
        // ' ' -> 'e'
        // ' ' -> 'r'
        assertEquals("Hey ther", committedText)
        assertEquals("Covert", capturedSecretWord)
        assertTrue("Covert word should be immediately dispatched after double-space", isImmediateDispatched)

        // Now simulate typing past the end of the sentence to verify NO LEAKS occur (e.g. "typ" must not appear!)
        val leakCheckInput = listOf("t" to true, "y" to true, "p" to true)
        for ((char, isLetter) in leakCheckInput) {
            val output = simulateKey(char, isLetter, committedText)
            committedText += output
            assertNotEquals("Raw secret character must NEVER leak into committed text", char, output)
        }
        assertFalse("Committed text must not contain raw 'typ'", committedText.contains("typ"))
    }

    @Test
    fun testWordBoundaryCalculationForSwipeDelete() {
        fun computeWordBoundaries(text: String): List<Int> {
            val boundaries = mutableListOf<Int>()
            var i = text.length
            while (i > 0) {
                while (i > 0 && text[i - 1].isWhitespace()) {
                    i--
                }
                if (i == 0) break
                while (i > 0 && !text[i - 1].isWhitespace()) {
                    i--
                }
                var wordStart = i
                while (wordStart > 0 && text[wordStart - 1] == ' ') {
                    wordStart--
                    break
                }
                val charCount = text.length - wordStart
                boundaries.add(charCount)
                i = wordStart
            }
            return boundaries
        }

        val text = "The quick brown fox"
        val boundaries = computeWordBoundaries(text)
        // 1st word from right is " fox" (4 chars)
        assertEquals(4, boundaries[0])
        // 2nd word from right is " brown fox" (10 chars)
        assertEquals(10, boundaries[1])
        // 3rd word from right is " quick brown fox" (16 chars)
        assertEquals(16, boundaries[2])
        // 4th word from right is "The quick brown fox" (19 chars)
        assertEquals(19, boundaries[3])
    }

    @Test
    fun testThemeKeyRadiusMapping() {
        fun getKeyRadius(theme: String): Int {
            return when (theme) {
                "liquid_glass" -> 6
                "material_you" -> 9
                else -> 8
            }
        }

        assertEquals(6, getKeyRadius("liquid_glass"))
        assertEquals(9, getKeyRadius("material_you"))
        assertEquals(8, getKeyRadius("pitch_black"))
        assertEquals(8, getKeyRadius("dark"))
        assertEquals(8, getKeyRadius("system"))
    }

    @Test
    fun testButtonWidthInsetMapping() {
        fun getKeyInsetH(widthSetting: String): Int {
            return when (widthSetting) {
                "standard" -> 2
                else -> 1 // "wide" default provides larger touch target
            }
        }

        assertEquals(1, getKeyInsetH("wide"))
        assertEquals(2, getKeyInsetH("standard"))
    }
}
