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
 * Learns user's writing habits and vocabulary dynamically (Layer 4 - Personal Learning):
 * 1. Word frequency (how often words are typed or chosen from suggestions, preserving display casing)
 * 2. Bigram transitions (which words the user typically writes next, e.g. "good" -> "morning", "Amr" -> "Rady")
 * 3. Trigram transitions (3-word patterns, e.g. "I am" -> "going", "am going" -> "to")
 * 4. Recent words & user-added custom words
 * 5. Asynchronously persists habits to local SharedPreferences
 */
object UserHabitsManager {
    private const val TAG = "UserHabitsManager"
    private const val PREFS_NAME = "user_writing_habits"
    private const val KEY_WORD_FREQS = "word_freqs_v1"
    private const val KEY_BIGRAMS = "bigrams_v1"
    private const val KEY_TRIGRAMS = "trigrams_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null

    // Word frequencies: lowercased word -> count
    private val wordFrequencies = ConcurrentHashMap<String, Int>()
    // Preserved display casing: lowercased word -> original display casing (e.g. "amr" -> "Amr")
    private val wordDisplayCasing = ConcurrentHashMap<String, String>()
    // Bigram transitions: previousWord.lowercase() -> Map<nextWord.lowercase(), count>
    private val bigramTransitions = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    // Trigram transitions: "${prevPrev.lowercase()} ${prev.lowercase()}" -> Map<nextWord.lowercase(), count>
    private val trigramTransitions = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

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

            val trigramsJson = p.getString(KEY_TRIGRAMS, null)
            if (!trigramsJson.isNullOrEmpty()) {
                val json = JSONObject(trigramsJson)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val contextKey = keys.next()
                    val innerObj = json.optJSONObject(contextKey) ?: continue
                    val map = ConcurrentHashMap<String, Int>()
                    val innerKeys = innerObj.keys()
                    while (innerKeys.hasNext()) {
                        val nextWord = innerKeys.next()
                        map[nextWord] = innerObj.optInt(nextWord, 1)
                    }
                    trigramTransitions[contextKey.lowercase()] = map
                }
            }

            Log.d(TAG, "Loaded user habits: ${wordFrequencies.size} words, ${bigramTransitions.size} bigrams, ${trigramTransitions.size} trigrams.")
        } catch (e: Exception) {
            Log.w(TAG, "Error loading user habits", e)
        }
    }

    /**
     * Records a word typed by the user, along with the immediate previous word (prevWord)
     * and the two-words-back word (prevPrevWord) for personal unigram, bigram, and trigram learning.
     */
    fun recordWord(word: String, prevWord: String? = null, prevPrevWord: String? = null) {
        val trimmed = word.trim()
        if (trimmed.length < 2) return
        val lower = trimmed.lowercase()

        // 1. Increment frequency & preserve casing
        val currentCount = wordFrequencies[lower] ?: 0
        wordFrequencies[lower] = currentCount + 1

        if (trimmed != lower || !wordDisplayCasing.containsKey(lower)) {
            wordDisplayCasing[lower] = trimmed
        }

        // 2. Track bigram transition: prevWord -> word
        val p1 = prevWord?.trim()?.lowercase()
        if (!p1.isNullOrEmpty() && p1.length >= 2 && p1 != lower) {
            val nextMap = bigramTransitions.getOrPut(p1) { ConcurrentHashMap() }
            val transCount = nextMap[lower] ?: 0
            nextMap[lower] = transCount + 1

            // 3. Track trigram transition: (prevPrevWord + " " + prevWord) -> word
            val p2 = prevPrevWord?.trim()?.lowercase()
            if (!p2.isNullOrEmpty() && p2.length >= 2) {
                val trigramKey = "$p2 $p1"
                val triMap = trigramTransitions.getOrPut(trigramKey) { ConcurrentHashMap() }
                val triCount = triMap[lower] ?: 0
                triMap[lower] = triCount + 1
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

            // 1. Word Frequencies (top 1500)
            val freqsJson = JSONObject()
            val topWords = wordFrequencies.entries
                .sortedByDescending { it.value }
                .take(1500)

            for (entry in topWords) {
                val item = JSONObject()
                item.put("c", entry.value)
                item.put("d", wordDisplayCasing[entry.key] ?: entry.key)
                freqsJson.put(entry.key, item)
            }

            // 2. Bigrams (top 400)
            val bigramsJson = JSONObject()
            val topBigrams = bigramTransitions.entries
                .sortedByDescending { it.value.values.sum() }
                .take(400)

            for (entry in topBigrams) {
                val inner = JSONObject()
                entry.value.entries.sortedByDescending { it.value }.take(15).forEach {
                    inner.put(it.key, it.value)
                }
                bigramsJson.put(entry.key, inner)
            }

            // 3. Trigrams (top 300)
            val trigramsJson = JSONObject()
            val topTrigrams = trigramTransitions.entries
                .sortedByDescending { it.value.values.sum() }
                .take(300)

            for (entry in topTrigrams) {
                val inner = JSONObject()
                entry.value.entries.sortedByDescending { it.value }.take(10).forEach {
                    inner.put(it.key, it.value)
                }
                trigramsJson.put(entry.key, inner)
            }

            p.edit()
                .putString(KEY_WORD_FREQS, freqsJson.toString())
                .putString(KEY_BIGRAMS, bigramsJson.toString())
                .putString(KEY_TRIGRAMS, trigramsJson.toString())
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
     * Learned next words following prevWord and prevPrevWord, prioritizing trigrams over bigrams.
     */
    fun getLearnedNextWords(prevWord: String, prevPrevWord: String? = null, limit: Int = 6): List<String> {
        val p1 = prevWord.trim().lowercase()
        if (p1.isEmpty()) return emptyList()

        val results = LinkedHashSet<String>()

        // 1. Trigram matches first (e.g. "I am" -> "going", "good" + "morning" -> "everyone")
        if (!prevPrevWord.isNullOrBlank()) {
            val p2 = prevPrevWord.trim().lowercase()
            val triKey = "$p2 $p1"
            trigramTransitions[triKey]?.let { triMap ->
                triMap.entries.sortedByDescending { it.value }.forEach {
                    val display = wordDisplayCasing[it.key] ?: it.key
                    results.add(display)
                }
            }
        }

        // 2. Bigram matches second (e.g. "good" -> "morning", "Amr" -> "Rady")
        bigramTransitions[p1]?.let { biMap ->
            biMap.entries.sortedByDescending { it.value }.forEach {
                val display = wordDisplayCasing[it.key] ?: it.key
                results.add(display)
            }
        }

        return results.take(limit).toList()
    }

    fun getBigramScore(prevWord: String, nextWord: String): Int {
        val p1 = prevWord.trim().lowercase()
        val nw = nextWord.trim().lowercase()
        if (p1.isEmpty() || nw.isEmpty()) return 0
        return bigramTransitions[p1]?.get(nw) ?: 0
    }

    fun getTrigramScore(prevPrevWord: String, prevWord: String, nextWord: String): Int {
        val p2 = prevPrevWord.trim().lowercase()
        val p1 = prevWord.trim().lowercase()
        val nw = nextWord.trim().lowercase()
        if (p2.isEmpty() || p1.isEmpty() || nw.isEmpty()) return 0
        return trigramTransitions["$p2 $p1"]?.get(nw) ?: 0
    }

    /**
     * Top most frequently used words overall.
     */
    fun getTopLearnedWords(limit: Int = 8): List<String> {
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
