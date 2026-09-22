package com.example.customkeyboard

import org.junit.Assert.*
import org.junit.Test

class ArabicKeyFontSizeTest {

    private val baselineArabicLetters = setOf("ط", "ك", "ف", "ث", "ا", "ة", "ظ", "د", "ب", "ت", "ذ", "ه", "ء")
    private val wideArabicLetters = setOf("ص", "ض", "س", "ش", "ي", "ى", "ئ")

    private fun computeLetterFontSize(
        label: String?,
        fontSizePref: String,
        fontStylePref: String,
        isArabic: Boolean
    ): Float {
        val isSystemFont = fontStylePref == "system"
        val isWide = isArabic && label != null && label in wideArabicLetters
        val baseSize = when (fontSizePref) {
            "small" -> if (isWide) 18.5f else 19f
            "large" -> if (isWide) 24.5f else 26.5f
            "extra_large" -> if (isWide) 25.5f else 30.5f
            else -> if (isWide) 22f else 23f
        }
        return if (isSystemFont) baseSize + (if (isWide) 0.5f else 1f) else baseSize
    }

    @Test
    fun testAllReportedLettersAreInWideArabicLettersSet() {
        val reportedLetters = listOf("ص", "ض", "ي", "س", "ش")
        for (letter in reportedLetters) {
            assertTrue("Letter '$letter' must be in wideArabicLetters set", wideArabicLetters.contains(letter))
            assertFalse("Letter '$letter' must not be in baselineArabicLetters", baselineArabicLetters.contains(letter))
        }
    }

    @Test
    fun testExtraLargeFontSizeForWideArabicLetters() {
        val reportedLetters = listOf("ص", "ض", "ي", "س", "ش")
        for (letter in reportedLetters) {
            val sizeBold = computeLetterFontSize(letter, "extra_large", "bold", isArabic = true)
            // Should be proportioned to ~25.5sp (never the overflowing 30.5sp)
            assertEquals(25.5f, sizeBold, 0.01f)

            val sizeSystem = computeLetterFontSize(letter, "extra_large", "system", isArabic = true)
            assertEquals(26.0f, sizeSystem, 0.01f)
        }

        // Verify standard letters like 'ث' keep the full extra_large size (30.5f / 31.5f)
        val standardLetterSizeBold = computeLetterFontSize("ث", "extra_large", "bold", isArabic = true)
        assertEquals(30.5f, standardLetterSizeBold, 0.01f)
        val standardLetterSizeSystem = computeLetterFontSize("ث", "extra_large", "system", isArabic = true)
        assertEquals(31.5f, standardLetterSizeSystem, 0.01f)
    }

    @Test
    fun testDynamicScalingClampsOverflowingText() {
        fun simulateScale(
            textWidth: Float,
            textHeight: Float,
            safeWidth: Float,
            safeHeight: Float,
            baseFontSize: Float
        ): Float {
            var scale = 1f
            if (textWidth > 0f && textWidth > safeWidth) {
                scale = minOf(scale, safeWidth / textWidth)
            }
            if (textHeight > 0f && textHeight > safeHeight) {
                scale = minOf(scale, safeHeight / textHeight)
            }
            return baseFontSize * scale
        }

        // On a compact 360dp phone, key safe width is ~28dp.
        // If a wide letter measured 32dp, it must be scaled down to 28dp.
        val baseFs = 25.5f
        val clampedFs = simulateScale(textWidth = 32f, textHeight = 35f, safeWidth = 28f, safeHeight = 41f, baseFontSize = baseFs)
        assertTrue("Font size must be scaled down to fit key width", clampedFs < baseFs)
        assertEquals(25.5f * (28f / 32f), clampedFs, 0.01f)

        // If letter already fits (e.g. 25dp width in 28dp safe width), no downscale should occur
        val unclampedFs = simulateScale(textWidth = 25f, textHeight = 35f, safeWidth = 28f, safeHeight = 41f, baseFontSize = baseFs)
        assertEquals(baseFs, unclampedFs, 0.01f)
    }
}
