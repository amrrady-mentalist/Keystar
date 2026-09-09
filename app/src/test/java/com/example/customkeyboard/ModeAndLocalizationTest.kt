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
}
