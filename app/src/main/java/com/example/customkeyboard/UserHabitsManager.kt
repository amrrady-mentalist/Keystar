package com.example.customkeyboard

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Learns user's writing habits and vocabulary dynamically:
 * 1. Word frequency (how often words are typed or chosen from suggestions)
 * 2. Bigram transitions (which words the user typically writes next)
 * 3. Recent words & user-added custom words
 * 4. Asynchronously persists habits to local SharedPreferences
 */
object UserHabitsManager {
    private const val TAG = "UserHabitsManager"
    private const val PREFS_NAME = "user_writing_habits"
    private const val KEY_WORD_FREQS = "word_freqs_v1"
    private const val KEY_BIGRAMS = "bigrams_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    // Word frequencies: lowercased word -> count
    private val wordFrequencies = ConcurrentHashMap<String, Int>()
    // Preserved display casing: lowercased word -> original display casing (e.g. "amr" -> "Amr")
    private val wordDisplayCasing = ConcurrentHashMap<String, String>()
    // Bigram transitions: previousWord.lowercase() -> Map<nextWord.lowercase(), count>
    private val bigramTransitions = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    private var isInitialized = false
    @Volatile
    private var isDirty = false

    fun init(context: Context) {
        if (isInitialized) return
        val appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        scope.launch {
            loadFromStorage()
        }
        isInitialized = true
    }

    private fun loadFromStorage() {
        try {
            val p = prefs ?: return
            val freqsJson = p.getString(KEY_WORD_FREQS, null)
            if (!freqsJson.isNullOrEmpty()) {
                val json = JSONObject(freqsJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = json.optJSONObject(key)
                    if (obj != null) {
                        val count = obj.optInt("c", 1)
                        val display = obj.optString("d", key)
                        wordFrequencies[key.lowercase()] = count
                        wordDisplayCasing[key.lowercase()] = display
                    } else {
                        val count = json.optInt(key, 1)
                        wordFrequencies[key.lowercase()] = count
                        wordDisplayCasing[key.lowercase()] = key
                    }
                }
            }

            val bigramsJson = p.getString(KEY_BIGRAMS, null)
            if (!bigramsJson.isNullOrEmpty()) {
                val json = JSONObject(bigramsJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val prev = keys.next()
                    val innerObj = json.optJSONObject(prev) ?: continue
                    val map = ConcurrentHashMap<String, Int>()
                    val innerKeys = innerObj.keys()
                    while (innerKeys.hasNext()) {
                        val nextWord = innerKeys.next()
                        map[nextWord] = innerObj.optInt(nextWord, 1)
                    }
                    bigramTransitions[prev.lowercase()] = map
                }
            }
            Log.d(TAG, "Loaded user habits: ${wordFrequencies.size} words, ${bigramTransitions.size} transitions.")
        } catch (e: Exception) {
            Log.w(TAG, "Error loading user habits", e)
        }
    }

    fun recordWord(word: String, prevWord: String? = null) {
        val trimmed = word.trim()
        if (trimmed.length < 2) return
        val lower = trimmed.lowercase()

        // 1. Increment frequency
        val currentCount = wordFrequencies[lower] ?: 0
        wordFrequencies[lower] = currentCount + 1

        // Store preferred display casing
        if (trimmed != lower || !wordDisplayCasing.containsKey(lower)) {
            wordDisplayCasing[lower] = trimmed
        }

        // 2. Track bigram transition from previous word
        if (!prevWord.isNullOrBlank()) {
            val prevLower = prevWord.trim().lowercase()
            if (prevLower.length >= 2 && prevLower != lower) {
                val nextMap = bigramTransitions.getOrPut(prevLower) { ConcurrentHashMap() }
                val transCount = nextMap[lower] ?: 0
                nextMap[lower] = transCount + 1
            }
        }

        isDirty = true
        scheduleSave()
    }

    private var saveScheduled = false
    private fun scheduleSave() {
        if (saveScheduled) return
        saveScheduled = true
        scope.launch {
            kotlinx.coroutines.delay(2000)
            saveScheduled = false
            saveToStorage()
        }
    }

    private fun saveToStorage() {
        if (!isDirty) return
        try {
            val p = prefs ?: return
            val freqsJson = JSONObject()
            // Keep top 1200 most used words to avoid unbounded storage
            val topWords = wordFrequencies.entries
                .sortedByDescending { it.value }
                .take(1200)

            for (entry in topWords) {
                val item = JSONObject()
                item.put("c", entry.value)
                item.put("d", wordDisplayCasing[entry.key] ?: entry.key)
                freqsJson.put(entry.key, item)
            }

            val bigramsJson = JSONObject()
            // Keep top 350 transitions
            val topBigrams = bigramTransitions.entries
                .sortedByDescending { it.value.values.sum() }
                .take(350)

            for (entry in topBigrams) {
                val inner = JSONObject()
                entry.value.entries.sortedByDescending { it.value }.take(12).forEach {
                    inner.put(it.key, it.value)
                }
                bigramsJson.put(entry.key, inner)
            }

            p.edit()
                .putString(KEY_WORD_FREQS, freqsJson.toString())
                .putString(KEY_BIGRAMS, bigramsJson.toString())
                .apply()
            isDirty = false
        } catch (e: Exception) {
            Log.w(TAG, "Error saving user habits", e)
        }
    }

    /**
     * Learned completions starting with typed prefix, ranked by user frequency.
     */
    fun getLearnedCompletions(prefix: String, limit: Int = 4): List<String> {
        val query = prefix.trim().lowercase()
        if (query.isEmpty()) return emptyList()

        return wordFrequencies.entries
            .filter { it.key.startsWith(query) }
            .sortedByDescending { it.value }
            .take(limit)
            .map { entry ->
                val display = wordDisplayCasing[entry.key] ?: entry.key
                Dictionary.matchCasing(prefix, display)
            }
    }

    /**
     * Learned next words following prevWord based on user's personal habits.
     */
    fun getLearnedNextWords(prevWord: String, limit: Int = 4): List<String> {
        val prevLower = prevWord.trim().lowercase()
        if (prevLower.isEmpty()) return emptyList()

        val nextMap = bigramTransitions[prevLower] ?: return emptyList()
        return nextMap.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { entry ->
                wordDisplayCasing[entry.key] ?: entry.key
            }
    }

    /**
     * Top most frequently used words overall.
     */
    fun getTopLearnedWords(limit: Int = 6): List<String> {
        return wordFrequencies.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { wordDisplayCasing[it.key] ?: it.key }
    }

    fun isLearnedWord(word: String): Boolean {
        val lower = word.trim().lowercase()
        return (wordFrequencies[lower] ?: 0) >= 1
    }

    fun getWordFrequency(word: String): Int {
        return wordFrequencies[word.trim().lowercase()] ?: 0
    }
}
