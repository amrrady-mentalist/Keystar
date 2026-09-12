package com.example.customkeyboard

import org.junit.Assert.*
import org.junit.Test

class PredictionEngineTest {

    @Test
    fun testKeyboardDistanceCalculations() {
        // 'g' and 'i' vs 'g' and 'o'
        val distGO = Dictionary.KeyboardDistance.distance("giod", "good", isArabic = false)
        val distGA = Dictionary.KeyboardDistance.distance("giod", "glad", isArabic = false)
        // "giod" to "good": substitute 'i' with 'o' (adjacent on QWERTY: i is row 0 col 7, o is row 0 col 8, dist 1.0)
        assertTrue("giod ($distGO) should be closer to good than to glad ($distGA)", distGO < distGA)
    }

    @Test
    fun testTypoCorrectionsWithoutFullAssets() {
        // Even before asset loading, common typo overrides and keyboard distance fuzzy matching should be available
        val becuaseCorr = Dictionary.getTypoCorrections("becuase", isArabic = false)
        assertTrue("becuase should suggest because", becuaseCorr.contains("because"))

        val tehCorr = Dictionary.getTypoCorrections("teh", isArabic = false)
        assertTrue("teh should suggest the", tehCorr.contains("the"))

        val defCorr = Dictionary.getTypoCorrections("definately", isArabic = false)
        assertTrue("definately should suggest definitely", defCorr.contains("definitely"))

        val recCorr = Dictionary.getTypoCorrections("recieve", isArabic = false)
        assertTrue("recieve should suggest receive", recCorr.contains("receive"))

        val govCorr = Dictionary.getTypoCorrections("goverment", isArabic = false)
        assertTrue("goverment should suggest government", govCorr.contains("government"))
    }

    @Test
    fun testTrigramNextWordPrediction() {
        val nextWords = Dictionary.getNextWords(listOf("going", "am", "I"), isArabic = false)
        assertTrue("Next words for 'I am going' should suggest 'to'", nextWords.contains("to"))
    }

    @Test
    fun testPrefixScoring() {
        val suggestions = Dictionary.getContextualSuggestions("gover", listOf(), isArabic = false, limit = 3).map { it.text.lowercase() }
        assertTrue("Suggestions for 'gover' should include 'government' or 'govern'", suggestions.any { it.startsWith("govern") })
    }

    @Test
    fun testLanguageIsolationEnglishAndArabic() {
        // In English keyboard mode: suggestions must be English only, never Arabic
        val enSuggestions = Dictionary.getContextualSuggestions("", listOf(), isArabic = false, limit = 3)
        assertEquals(3, enSuggestions.size)
        assertTrue(enSuggestions.all { Dictionary.isMatchingLanguage(it.text, isArabic = false) })
        assertFalse(enSuggestions.any { Dictionary.isMatchingLanguage(it.text, isArabic = true) })

        // In Arabic keyboard mode: suggestions must be Arabic only, never English
        val arSuggestions = Dictionary.getContextualSuggestions("", listOf(), isArabic = true, limit = 3)
        assertEquals(3, arSuggestions.size)
        assertTrue(arSuggestions.all { Dictionary.isMatchingLanguage(it.text, isArabic = true) })
        assertFalse(arSuggestions.any { Dictionary.isMatchingLanguage(it.text, isArabic = false) })
    }

    @Test
    fun testSuggestionLimitAndNoHighlighting() {
        val suggestions = Dictionary.getContextualSuggestions("th", listOf(), isArabic = false, limit = 3)
        assertTrue("Suggestions count must be at most 3", suggestions.size <= 3)
        // No highlighting on any suggested words
        for (item in suggestions) {
            assertFalse("Suggested word '${item.text}' should not have isPrimary highlight", item.isPrimary)
            assertFalse("Suggested word '${item.text}' should not have isCorrection highlight", item.isCorrection)
        }
    }
}
