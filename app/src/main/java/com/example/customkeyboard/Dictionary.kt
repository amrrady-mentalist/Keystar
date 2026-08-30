package com.example.customkeyboard

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * High-performance bilingual (English and Arabic) predictive engine with comprehensive vocabulary
 * from dwyl/english-words (416,000+ words) and a3f/arabic-wordlists (350,000+ words).
 *
 * Uses asynchronous background loading, binary-search prefix scanning, frequency ranking,
 * and Arabic orthographic normalization.
 */
object Dictionary {

    private const val TAG = "Dictionary"

    // Core fallback words available before background assets loading completes
    private val fallbackEnglish = listOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
        "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
        "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
        "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
        "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
        "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
        "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
        "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
        "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
        "hello", "hi", "hey", "thanks", "thank", "please", "welcome", "sorry", "love", "great",
        "awesome", "amazing", "perfect", "cool", "fine", "okay", "ok", "yes", "yeah", "yep",
        "sure", "never", "always", "sometimes", "today", "tomorrow", "yesterday", "tonight",
        "magic", "secret", "reveal", "covert", "spectator", "mind", "mentalism", "card", "cards",
        "deck", "trick", "illusion", "performance", "audience", "prediction", "shuffle", "choice"
    )

    private val fallbackArabic = listOf(
        "في", "من", "على", "إلى", "عن", "مع", "هذا", "هذه", "ذلك", "تلك", "التي", "الذي",
        "هو", "هي", "هم", "أنا", "أنت", "أنتم", "نحن", "كل", "بعض", "غير", "سوف", "قد",
        "لم", "لن", "ما", "لا", "نعم", "أيضا", "جدا", "حتى", "إذا", "لو", "أو", "ثم", "بل",
        "لكن", "لأن", "حيث", "بين", "فوق", "تحت", "أمام", "خلف", "عند", "لدى",
        "السلام", "عليكم", "مرحبا", "أهلا", "وسهلا", "صباح", "الخير", "مساء", "النور", "شكرا",
        "عفوا", "من", "فضلك", "لو", "سمحت", "تفضل", "تمام", "ماشي", "إن", "شاء", "الله",
        "الحمد", "لله", "مبروك", "ألف", "سلامتك", "حبيبي", "يا", "أخي", "صديقي", "أستاذ",
        "كيف", "حالك", "أخبارك", "عامل", "إيه", "فينك", "واحشني", "بخير", "الحمدلله", "معلش",
        "سحر", "خدعة", "سر", "خفي", "كروت", "كارت", "ورق", "تخمين", "توقع", "عرض", "ألعاب"
    )

    private class Entry(val key: String, val word: String, val rank: Int)

    @Volatile
    private var isLoaded = false
    @Volatile
    private var isLoading = false

    private var enKeys: Array<String> = emptyArray()
    private var enEntries: Array<Entry> = emptyArray()

    private var arKeys: Array<String> = emptyArray()
    private var arEntries: Array<Entry> = emptyArray()

    // Recent words learned during active typing
    private val recentUserWords = Collections.synchronizedSet(LinkedHashSet<String>())

    private val executor = Executors.newSingleThreadExecutor()

    /**
     * Initializes the full bilingual dictionary from compressed assets asynchronously.
     */
    fun init(context: Context) {
        if (isLoaded || isLoading) return
        isLoading = true
        val appContext = context.applicationContext

        executor.execute {
            try {
                // 1. Load English dictionary from dwyl/english-words
                val enList = mutableListOf<String>()
                appContext.assets.open("dict_en.txt.gz").use { inStream ->
                    GZIPInputStream(inStream).use { gzStream ->
                        BufferedReader(InputStreamReader(gzStream, Charsets.UTF_8)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val w = line!!.trim()
                                if (w.isNotEmpty()) enList.add(w)
                            }
                        }
                    }
                }

                val tempEnEntries = ArrayList<Entry>(enList.size)
                for (i in enList.indices) {
                    val w = enList[i]
                    tempEnEntries.add(Entry(w.lowercase(), w, i))
                }
                tempEnEntries.sortBy { it.key }

                val tempEnKeys = Array(tempEnEntries.size) { tempEnEntries[it].key }
                val tempEnArray = tempEnEntries.toTypedArray()

                // 2. Load Arabic dictionary from a3f/arabic-wordlists
                val arList = mutableListOf<String>()
                appContext.assets.open("dict_ar.txt.gz").use { inStream ->
                    GZIPInputStream(inStream).use { gzStream ->
                        BufferedReader(InputStreamReader(gzStream, Charsets.UTF_8)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val w = line!!.trim()
                                if (w.isNotEmpty()) arList.add(w)
                            }
                        }
                    }
                }

                val tempArEntries = ArrayList<Entry>(arList.size)
                for (i in arList.indices) {
                    val w = arList[i]
                    val norm = normalizeArabic(w)
                    tempArEntries.add(Entry(norm, w, i))
                }
                tempArEntries.sortBy { it.key }

                val tempArKeys = Array(tempArEntries.size) { tempArEntries[it].key }
                val tempArArray = tempArEntries.toTypedArray()

                enKeys = tempEnKeys
                enEntries = tempEnArray
                arKeys = tempArKeys
                arEntries = tempArArray

                isLoaded = true
                isLoading = false
                Log.d(TAG, "Loaded full bilingual dictionary: ${enList.size} English words, ${arList.size} Arabic words.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load compressed dictionary assets", e)
                isLoading = false
            }
        }
    }

    /**
     * Normalizes Arabic text for flexible matching across orthographic variations:
     * - Removes tashkeel/diacritics and tatweel (kashida)
     * - Normalizes alef variations (أ, إ, آ, ٱ -> ا)
     * - Normalizes taa marbouta (ة -> ه)
     * - Normalizes alef maksura (ى -> ي)
     */
    fun normalizeArabic(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                // Diacritics to skip
                '\u064B', '\u064C', '\u064D', '\u064E', '\u064F', '\u0650', '\u0651', '\u0652', '\u0670', '\u0640' -> continue
                // Alef normalization
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                // Taa marbouta
                'ة' -> sb.append('ه')
                // Alef maksura
                'ى' -> sb.append('ي')
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Fast binary search to find the start index of the query prefix in sorted keys array.
     */
    private fun binarySearchStart(keys: Array<String>, prefix: String): Int {
        var low = 0
        var high = keys.size - 1
        var ans = keys.size
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (keys[mid] >= prefix) {
                ans = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return ans
    }

    /**
     * Main suggestion engine: produces ranked suggestions for the active prefix.
     */
    fun suggestions(prefix: String, isArabic: Boolean, limit: Int = 10): List<String> {
        if (prefix.isBlank()) return emptyList()

        if (!isLoaded) {
            // Fast fallback matching if assets still loading
            val query = if (isArabic) normalizeArabic(prefix) else prefix.trim().lowercase()
            val list = if (isArabic) fallbackArabic else fallbackEnglish
            return list.filter { word ->
                val w = if (isArabic) normalizeArabic(word) else word.lowercase()
                w.startsWith(query) && w != query
            }.take(limit)
        }

        val query = if (isArabic) normalizeArabic(prefix) else prefix.trim().lowercase()
        if (query.isEmpty()) return emptyList()

        val keys = if (isArabic) arKeys else enKeys
        val entries = if (isArabic) arEntries else enEntries

        val startIdx = binarySearchStart(keys, query)
        val candidates = mutableListOf<Entry>()
        val maxScan = 2000
        var scanned = 0

        var idx = startIdx
        while (idx < keys.size && scanned < maxScan) {
            val k = keys[idx]
            if (!k.startsWith(query)) break
            val entry = entries[idx]
            if (entry.word != prefix && !entry.key.equals(query, ignoreCase = true)) {
                candidates.add(entry)
            }
            idx++
            scanned++
        }

        // Check recent user words for top boost
        val result = mutableListOf<String>()
        val seen = HashSet<String>()

        synchronized(recentUserWords) {
            for (w in recentUserWords) {
                val normW = if (isArabic) normalizeArabic(w) else w.lowercase()
                if (normW.startsWith(query) && normW != query && seen.add(w)) {
                    result.add(w)
                    if (result.size >= limit) return result
                }
            }
        }

        // Sort candidates by frequency rank (lower rank = more frequent), then length
        candidates.sortWith(
            compareBy<Entry> { it.rank }
                .thenBy { it.word.length }
        )

        for (candidate in candidates) {
            if (seen.add(candidate.word)) {
                result.add(candidate.word)
                if (result.size >= limit) break
            }
        }

        return result
    }

    /**
     * Records a word typed or tapped by the user for predictive ranking.
     */
    fun recordUsedWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.length >= 2) {
            synchronized(recentUserWords) {
                recentUserWords.remove(trimmed)
                recentUserWords.add(trimmed)
                if (recentUserWords.size > 200) {
                    val it = recentUserWords.iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
            }
        }
    }

    /**
     * Fallback top frequently used words when no prefix has been typed yet.
     */
    fun topWords(isArabic: Boolean, limit: Int = 8): List<String> {
        return if (isArabic) {
            listOf("شكرا", "تمام", "مرحبا", "إن شاء الله", "الحمد لله", "أنا", "في", "على").take(limit)
        } else {
            listOf("the", "to", "and", "I", "you", "thanks", "hello", "good").take(limit)
        }
    }
}
