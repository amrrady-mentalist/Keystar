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
        val suggestions = Dictionary.getContextualSuggestions("gover", listOf(), isArabic = false, limit = 5).map { it.text.lowercase() }
        assertTrue("Suggestions for 'gover' should include 'government' or 'govern'", suggestions.any { it.startsWith("govern") })
    }
}
