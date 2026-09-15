package com.example.customkeyboard

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentSkipListSet

/**
 * Self / Personal Dictionary Manager
 *
 * Allows users to define custom words, names, abbreviations, and terminology in normal settings.
 * When typing the first 2 or 3 letters of a word, matching entries from this dictionary
 * are prioritized and placed immediately at the front of the suggestion bar.
 */
object SelfDictionaryManager {

    private const val PREFS_NAME = "personal_self_dictionary_prefs"
    private const val KEY_WORDS = "user_custom_words_set"
    private const val KEY_ENABLED = "self_dictionary_enabled"

    private var prefs: SharedPreferences? = null
    private val customWords = ConcurrentSkipListSet<String>(String.CASE_INSENSITIVE_ORDER)
    private var isInitialized = false

    private val defaultStarterWords = listOf(
        "Ahmed",
        "Mohammed",
        "CustomKeyboard",
        "Android",
        "Welcome",
        "أحمد",
        "محمد",
        "مرحبا",
        "شكرا",
        "السلام عليكم"
    )

    fun init(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val savedSet = prefs?.getStringSet(KEY_WORDS, null)
        if (savedSet != null) {
            customWords.addAll(savedSet)
        } else {
            // First time setup: seed with starter words
            customWords.addAll(defaultStarterWords)
            saveToStorage()
        }
        isInitialized = true
    }

    fun isEnabled(context: Context): Boolean {
        val p = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return p.getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val p = prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        p.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getAllWords(): List<String> {
        return customWords.toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun addWord(word: String): Boolean {
        val trimmed = word.trim()
        if (trimmed.length < 2) return false
        val added = customWords.add(trimmed)
        if (added) {
            saveToStorage()
        }
        return added
    }

    fun removeWord(word: String): Boolean {
        val removed = customWords.remove(word.trim())
        if (removed) {
            saveToStorage()
        }
        return removed
    }

    fun clearAll() {
        customWords.clear()
        saveToStorage()
    }

    fun containsWord(word: String): Boolean {
        return customWords.contains(word.trim())
    }

    /**
     * Finds matching words from the personal dictionary starting with the given prefix.
     * Evaluates when prefix.length >= 2 (e.g. typing first 2 or 3 letters).
     */
    fun getMatchingWords(prefix: String, isArabic: Boolean, limit: Int = 4): List<String> {
        val trimmed = prefix.trim()
        if (trimmed.length < 2) return emptyList()

        val normPrefix = if (isArabic) normalizeArabic(trimmed) else trimmed.lowercase()
        val results = mutableListOf<String>()

        for (word in customWords) {
            val normWord = if (isArabic) normalizeArabic(word) else word.lowercase()
            if (normWord.startsWith(normPrefix)) {
                results.add(word)
                if (results.size >= limit) break
            }
        }
        return results
    }

    private fun normalizeArabic(text: String): String {
        return text
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ى', 'ي')
            .replace('ة', 'ه')
            .replace("\u064B", "") // fathatan
            .replace("\u064C", "") // dammatan
            .replace("\u064D", "") // kasratan
            .replace("\u064E", "") // fatha
            .replace("\u064F", "") // damma
            .replace("\u0650", "") // kasra
            .replace("\u0651", "") // shadda
            .replace("\u0652", "") // sukun
    }

    private fun saveToStorage() {
        prefs?.edit()?.putStringSet(KEY_WORDS, HashSet(customWords))?.apply()
    }
}
