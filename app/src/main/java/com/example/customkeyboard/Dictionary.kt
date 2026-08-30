package com.example.customkeyboard

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * High-performance bilingual predictive engine:
 * 1. 416,000+ English words (dwyl/english-words)
 * 2. 350,000+ Arabic words (a3f/arabic-wordlists)
 * 3. 3,200+ Word-to-Emoji offline links from rxaviers gist & gemoji
 * 4. Comprehensive Next-Word Prediction (Bigrams for English & Arabic)
 * 5. Dynamic Morphological Generator (Past, Present Participle, Plural, Future, Derivations)
 * 6. Real-time active learning for personalized frequency boosts.
 */
object Dictionary {

    private const val TAG = "Dictionary"

    data class SuggestionItem(
        val text: String,
        val isEmoji: Boolean,
        val isNextWord: Boolean = false,
        val isPrimary: Boolean = false
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

    private var emojiMap: Map<String, List<String>> = emptyMap()
    private var nextWordsMap: Map<String, List<String>> = emptyMap()

    // Recent words learned during active typing
    private val recentUserWords = Collections.synchronizedSet(LinkedHashSet<String>())

    private val executor = Executors.newSingleThreadExecutor()

    // Irregular verb forms table (English) for instant morphological expansion
    private val irregularEnglishForms = mapOf(
        "be" to listOf("is", "are", "was", "were", "been", "being"),
        "am" to listOf("was", "being", "been", "are"),
        "is" to listOf("was", "being", "been", "are"),
        "are" to listOf("were", "being", "been", "is"),
        "go" to listOf("going", "went", "gone", "goes"),
        "went" to listOf("go", "going", "gone", "goes"),
        "write" to listOf("writing", "wrote", "written", "writes", "writer"),
        "wrote" to listOf("write", "writing", "written", "writes"),
        "see" to listOf("seeing", "saw", "seen", "sees"),
        "saw" to listOf("see", "seeing", "seen", "sees"),
        "do" to listOf("doing", "did", "done", "does"),
        "did" to listOf("do", "doing", "done", "does"),
        "have" to listOf("having", "had", "has"),
        "had" to listOf("have", "having", "has"),
        "has" to listOf("have", "having", "had"),
        "make" to listOf("making", "made", "makes", "maker"),
        "made" to listOf("make", "making", "makes"),
        "take" to listOf("taking", "took", "taken", "takes"),
        "took" to listOf("take", "taking", "taken", "takes"),
        "think" to listOf("thinking", "thought", "thinks"),
        "thought" to listOf("think", "thinking", "thinks"),
        "know" to listOf("knowing", "knew", "known", "knows"),
        "knew" to listOf("know", "knowing", "known", "knows"),
        "get" to listOf("getting", "got", "gotten", "gets"),
        "got" to listOf("get", "getting", "gotten", "gets"),
        "say" to listOf("saying", "said", "says"),
        "said" to listOf("say", "saying", "says"),
        "come" to listOf("coming", "came", "comes"),
        "came" to listOf("come", "coming", "comes"),
        "give" to listOf("giving", "gave", "given", "gives"),
        "gave" to listOf("give", "giving", "given", "gives"),
        "find" to listOf("finding", "found", "finds"),
        "found" to listOf("find", "finding", "finds"),
        "tell" to listOf("telling", "told", "tells"),
        "told" to listOf("tell", "telling", "tells"),
        "feel" to listOf("feeling", "felt", "feels"),
        "felt" to listOf("feel", "feeling", "feels"),
        "leave" to listOf("leaving", "left", "leaves"),
        "left" to listOf("leave", "leaving", "leaves"),
        "put" to listOf("putting", "puts"),
        "mean" to listOf("meaning", "meant", "means"),
        "meant" to listOf("mean", "meaning", "means"),
        "keep" to listOf("keeping", "kept", "keeps"),
        "kept" to listOf("keep", "keeping", "keeps"),
        "let" to listOf("letting", "lets"),
        "begin" to listOf("beginning", "began", "begun", "begins"),
        "began" to listOf("begin", "beginning", "begun", "begins"),
        "show" to listOf("showing", "showed", "shown", "shows"),
        "hear" to listOf("hearing", "heard", "hears"),
        "heard" to listOf("hear", "hearing", "hears"),
        "run" to listOf("running", "ran", "runs", "runner"),
        "ran" to listOf("run", "running", "runs"),
        "bring" to listOf("bringing", "brought", "brings"),
        "brought" to listOf("bring", "bringing", "brings"),
        "buy" to listOf("buying", "bought", "buys", "buyer"),
        "bought" to listOf("buy", "buying", "buys"),
        "teach" to listOf("teaching", "taught", "teaches", "teacher"),
        "taught" to listOf("teach", "teaching", "teaches"),
        "drive" to listOf("driving", "drove", "driven", "drives", "driver"),
        "drove" to listOf("drive", "driving", "driven", "drives"),
        "eat" to listOf("eating", "ate", "eaten", "eats"),
        "ate" to listOf("eat", "eating", "eaten", "eats"),
        "drink" to listOf("drinking", "drank", "drunk", "drinks"),
        "drank" to listOf("drink", "drinking", "drunk", "drinks"),
        "sleep" to listOf("sleeping", "slept", "sleeps"),
        "slept" to listOf("sleep", "sleeping", "sleeps"),
        "win" to listOf("winning", "won", "wins", "winner"),
        "won" to listOf("win", "winning", "wins"),
        "send" to listOf("sending", "sent", "sends"),
        "sent" to listOf("send", "sending", "sends"),
        "build" to listOf("building", "built", "builds", "builder"),
        "built" to listOf("build", "building", "builds"),
        "understand" to listOf("understanding", "understood", "understands"),
        "understood" to listOf("understand", "understanding", "understands"),
        "speak" to listOf("speaking", "spoke", "spoken", "speaks", "speaker"),
        "spoke" to listOf("speak", "speaking", "spoken", "speaks"),
        "spend" to listOf("spending", "spent", "spends"),
        "spent" to listOf("spend", "spending", "spends"),
        "grow" to listOf("growing", "grew", "grown", "grows"),
        "grew" to listOf("grow", "growing", "grown", "grows"),
        "meet" to listOf("meeting", "met", "meets"),
        "met" to listOf("meet", "meeting", "meets"),
        "pay" to listOf("paying", "paid", "pays"),
        "paid" to listOf("pay", "paying", "pays"),
        "stand" to listOf("standing", "stood", "stands"),
        "stood" to listOf("stand", "standing", "stands"),
        "lose" to listOf("losing", "lost", "loses", "loser"),
        "lost" to listOf("lose", "losing", "loses")
    )

    // Arabic morphological derivations table
    private val arabicMorphologyMap = mapOf(
        "كتب" to listOf("يكتب", "كاتب", "مكتوب", "كتابة", "كتاب", "كتبت", "سيكتب"),
        "لعب" to listOf("يلعب", "لاعب", "لعبة", "لعبت", "سيلعب", "ألعاب"),
        "عمل" to listOf("يعمل", "عامل", "معمول", "عملت", "سيعمل", "أعمال"),
        "حزن" to listOf("يحزن", "حزين", "حزينة", "حزنت", "أحزان"),
        "فرح" to listOf("يفرح", "فرحان", "فرحانة", "فرحت", "أفراح"),
        "حب" to listOf("يحب", "حبيب", "حبيبي", "حبيبتي", "حبيت", "محبة"),
        "شكر" to listOf("يشكر", "شكرا", "شاكر", "مشكور", "شكرت"),
        "سحر" to listOf("يسحر", "ساحر", "مسحور", "سحرية", "سحري"),
        "تست" to listOf("تستنج", "تستات", "تسترت"),
        "طلب" to listOf("يطلب", "طالب", "مطلوب", "طلبت", "طلبات"),
        "درس" to listOf("يدرس", "دارس", "مدروس", "دراسة", "درست"),
        "شرب" to listOf("يشرب", "شارب", "مشروب", "شربت", "مشروبات"),
        "أكل" to listOf("يأكل", "آكل", "مأكول", "أكلت", "مأكولات"),
        "نوم" to listOf("ينام", "نائم", "نمت", "منام"),
        "مشى" to listOf("يمشي", "ماشي", "مشيت", "مشوار"),
        "جرى" to listOf("يجري", "جاري", "جريت"),
        "سأل" to listOf("يسأل", "سائل", "مسؤول", "سألت", "أسئلة"),
        "علم" to listOf("يعلم", "عالم", "معلوم", "علمت", "علوم"),
        "قال" to listOf("يقول", "قائل", "قلت", "سأقول"),
        "راح" to listOf("يروح", "رايح", "روحت"),
        "شاف" to listOf("يشوف", "شايف", "شوفت"),
        "عرف" to listOf("يعرف", "عارف", "عرفت"),
        "فهم" to listOf("يفهم", "فاهم", "فهمت"),
        "سمع" to listOf("يسمع", "سامع", "سمعت"),
        "فتح" to listOf("يفتح", "فاتح", "مفتوح", "فتحت")
    )

    /**
     * Initializes all bilingual dictionaries, next-word transitions, and emoji mapping asynchronously.
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

                // 3. Load Word-to-Emoji offline map
                val tempEmojiMap = mutableMapOf<String, List<String>>()
                try {
                    appContext.assets.open("emoji_map.json.gz").use { inStream ->
                        GZIPInputStream(inStream).use { gzStream ->
                            BufferedReader(InputStreamReader(gzStream, Charsets.UTF_8)).use { reader ->
                                val jsonStr = reader.readText()
                                val json = JSONObject(jsonStr)
                                val keys = json.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val arr = json.getJSONArray(k)
                                    val list = ArrayList<String>(arr.length())
                                    for (j in 0 until arr.length()) {
                                        list.add(arr.getString(j))
                                    }
                                    tempEmojiMap[k] = list
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed loading emoji_map.json.gz", e)
                }

                // 4. Load Next-Word transitions map
                val tempNextWordsMap = mutableMapOf<String, List<String>>()
                try {
                    appContext.assets.open("next_words.json.gz").use { inStream ->
                        GZIPInputStream(inStream).use { gzStream ->
                            BufferedReader(InputStreamReader(gzStream, Charsets.UTF_8)).use { reader ->
                                val jsonStr = reader.readText()
                                val json = JSONObject(jsonStr)
                                val keys = json.keys()
                                while (keys.hasNext()) {
                                    val k = keys.next()
                                    val arr = json.getJSONArray(k)
                                    val list = ArrayList<String>(arr.length())
                                    for (j in 0 until arr.length()) {
                                        list.add(arr.getString(j))
                                    }
                                    tempNextWordsMap[k] = list
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed loading next_words.json.gz", e)
                }

                enKeys = tempEnKeys
                enEntries = tempEnArray
                arKeys = tempArKeys
                arEntries = tempArArray
                emojiMap = tempEmojiMap
                nextWordsMap = tempNextWordsMap

                isLoaded = true
                isLoading = false
                Log.d(TAG, "Dictionary fully ready: ${enList.size} English, ${arList.size} Arabic, ${emojiMap.size} emojis, ${nextWordsMap.size} next-word rules.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load dictionary assets", e)
                isLoading = false
            }
        }
    }

    /**
     * Normalizes Arabic text for orthographic matching.
     */
    fun normalizeArabic(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                '\u064B', '\u064C', '\u064D', '\u064E', '\u064F', '\u0650', '\u0651', '\u0652', '\u0670', '\u0640' -> continue
                'أ', 'إ', 'آ', 'ٱ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Finds related emojis for a given word offline.
     */
    fun getEmojisForWord(word: String, limit: Int = 4): List<String> {
        val clean = word.trim().lowercase()
        if (clean.isEmpty()) return emptyList()

        val list = emojiMap[clean] ?: emojiMap[normalizeArabic(clean)]
        if (list != null && list.isNotEmpty()) {
            return list.take(limit)
        }

        // Check fallback hardcoded emojis
        val direct = when (clean) {
            "sad", "sadness", "sorrow", "cry", "crying" -> listOf("😢", "😭", "😞", "💔")
            "happy", "happiness", "joy" -> listOf("😊", "😄", "😃", "🎉")
            "test", "testing", "tested", "tests" -> listOf("🧪", "📝", "🔬", "✅")
            "love", "loving", "loved" -> listOf("❤️", "😍", "🥰", "💕")
            "fire", "lit" -> listOf("🔥", "⚡")
            "magic", "magical" -> listOf("🪄", "🔮", "✨", "🎩")
            "secret" -> listOf("🤫", "🤐", "🔒")
            "money", "cash" -> listOf("💰", "💵", "🤑")
            "car", "drive" -> listOf("🚗", "🚘")
            "coffee", "tea" -> listOf("☕", "🍵")
            "party" -> listOf("🎉", "🥳")
            "حزن", "حزين", "زعلان", "دموع" -> listOf("😢", "😭", "😞", "💔")
            "حب", "بحبك", "قلبي" -> listOf("❤️", "😍", "🥰", "💕")
            "فرح", "سعيد", "مبسوط" -> listOf("😃", "😊", "🎉")
            "شكرا", "تسلم" -> listOf("🙏", "🌹", "❤️")
            "تمام", "صح" -> listOf("👍", "👌", "✔️")
            "سحر", "خدعة" -> listOf("🔮", "✨", "🪄", "🎩")
            "تست", "اختبار" -> listOf("🧪", "📝", "🔬")
            else -> null
        }
        return direct ?: emptyList()
    }

    /**
     * Morphological generator: derives past tense, progressive (-ing), plural/3rd-person (-s/-es),
     * and agent nouns for English and derivations for Arabic.
     */
    fun getMorphologicalForms(word: String, isArabic: Boolean): List<String> {
        val clean = word.trim().lowercase()
        if (clean.length < 2) return emptyList()

        val results = LinkedHashSet<String>()

        if (isArabic) {
            val norm = normalizeArabic(clean)
            val direct = arabicMorphologyMap[norm] ?: arabicMorphologyMap[clean]
            if (direct != null) {
                results.addAll(direct)
            } else {
                // Rule-based Arabic expansions
                results.add("ي$clean") // Present (e.g. يكتب)
                results.add("${clean}ت") // Past 1st/2nd person (e.g. كتبت)
                results.add("س${clean}") // Future (e.g. سأكتب / سيكتب)
                results.add("ال$clean") // Definite (e.g. الكتاب)
            }
        } else {
            // Check irregular verbs
            val irregular = irregularEnglishForms[clean]
            if (irregular != null) {
                results.addAll(irregular)
            }

            // Rule-based morphological generation (Past, Continuous/Progressive, Plural, Agent)
            when {
                // Ends in 'e' -> +d, +r, drop 'e' + ing, +s (e.g. love -> loved, lover, loving, loves)
                clean.endsWith("e") -> {
                    results.add(clean + "d")
                    results.add(clean + "r")
                    results.add(clean.dropLast(1) + "ing")
                    results.add(clean + "s")
                }
                // Ends in consonant + 'y' -> -y + ied, -y + ies, +ing (e.g. try -> tried, tries, trying)
                clean.endsWith("y") && clean.length > 2 && !isVowel(clean[clean.length - 2]) -> {
                    val stem = clean.dropLast(1)
                    results.add(stem + "ied")
                    results.add(stem + "ies")
                    results.add(clean + "ing")
                    results.add(stem + "ier")
                }
                // Ends in s, sh, ch, x, z -> +es, +ed, +ing (e.g. watch -> watches, watched, watching)
                clean.endsWith("s") || clean.endsWith("sh") || clean.endsWith("ch") || clean.endsWith("x") || clean.endsWith("z") -> {
                    results.add(clean + "es")
                    results.add(clean + "ed")
                    results.add(clean + "ing")
                }
                // Short CVC (consonant-vowel-consonant) words like run, get, stop -> double consonant
                shouldDoubleConsonant(clean) -> {
                    val last = clean.last()
                    results.add(clean + last + "ed")
                    results.add(clean + last + "ing")
                    results.add(clean + last + "er")
                    results.add(clean + "s")
                }
                // Regular words like test, play, work -> testing, tested, tests, tester
                else -> {
                    results.add(clean + "ing")
                    results.add(clean + "ed")
                    results.add(clean + "s")
                    results.add(clean + "er")
                }
            }
        }

        return results.filter { it != clean && it.isNotEmpty() }
    }

    private fun isVowel(c: Char): Boolean = c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'

    private fun shouldDoubleConsonant(w: String): Boolean {
        if (w.length in 3..5) {
            val last = w[w.length - 1]
            val secondLast = w[w.length - 2]
            val thirdLast = w[w.length - 3]
            if (!isVowel(last) && last != 'w' && last != 'x' && last != 'y' && isVowel(secondLast) && !isVowel(thirdLast)) {
                return true
            }
        }
        return false
    }

    /**
     * Next-word prediction (Bigrams) for the given preceding word.
     */
    fun getNextWords(previousWord: String, isArabic: Boolean, limit: Int = 8): List<String> {
        val clean = previousWord.trim().lowercase()
        if (clean.isEmpty()) return emptyList()

        val list = nextWordsMap[clean] ?: nextWordsMap[normalizeArabic(clean)]
        if (list != null && list.isNotEmpty()) {
            return list.take(limit)
        }

        // Fallback default next words
        return if (isArabic) {
            listOf("يا", "في", "من", "على", "جدا", "تمام", "إن شاء الله", "الحمد لله").take(limit)
        } else {
            listOf("the", "to", "and", "a", "it", "is", "for", "you").take(limit)
        }
    }

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
     * Context-aware suggestion pipeline producing rich suggestions:
     * - Emojis matching current word or previous word
     * - Exact match & Morphological forms (past tense, future, present progressive, plural)
     * - Dictionary prefix completions (dwyl & arabic-wordlists)
     * - Next-word bigram predictions
     */
    fun getContextualSuggestions(
        currentWord: String,
        previousWord: String,
        isArabic: Boolean,
        limit: Int = 15
    ): List<SuggestionItem> {
        val result = mutableListOf<SuggestionItem>()
        val seenWords = HashSet<String>()
        val seenEmojis = HashSet<String>()

        val prefix = currentWord.trim()

        if (prefix.isNotEmpty()) {
            val query = if (isArabic) normalizeArabic(prefix) else prefix.lowercase()

            // 1. Contextual Emojis for the typed word
            val emojis = getEmojisForWord(prefix, limit = 3)
            for (em in emojis) {
                if (seenEmojis.add(em)) {
                    result.add(SuggestionItem(text = em, isEmoji = true))
                }
            }

            // 2. Exact word chip (if capitalized or custom)
            if (prefix.length >= 2) {
                result.add(SuggestionItem(text = prefix, isEmoji = false, isPrimary = true))
                seenWords.add(query)
                seenWords.add(prefix)
            }

            // 3. Morphological forms (Past tense, Progressive -ing, Plurals -s, etc.)
            val morphForms = getMorphologicalForms(prefix, isArabic)
            for (form in morphForms) {
                val formKey = if (isArabic) normalizeArabic(form) else form.lowercase()
                if (seenWords.add(formKey)) {
                    result.add(SuggestionItem(text = form, isEmoji = false))
                }
            }

            // 4. Boosted User Learned Words
            synchronized(recentUserWords) {
                for (w in recentUserWords) {
                    val normW = if (isArabic) normalizeArabic(w) else w.lowercase()
                    if (normW.startsWith(query) && seenWords.add(normW)) {
                        result.add(SuggestionItem(text = w, isEmoji = false))
                        if (result.size >= limit) return result
                    }
                }
            }

            // 5. Dictionary Prefix Scanning (416k English / 350k Arabic)
            if (isLoaded) {
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
                    val entryKey = entry.key
                    if (!seenWords.contains(entryKey)) {
                        candidates.add(entry)
                    }
                    idx++
                    scanned++
                }

                candidates.sortWith(
                    compareBy<Entry> { it.rank }
                        .thenBy { it.word.length }
                )

                for (cand in candidates) {
                    if (seenWords.add(cand.key)) {
                        result.add(SuggestionItem(text = cand.word, isEmoji = false))
                        if (result.size >= limit) return result
                    }
                }
            }

            // 6. Next-word possibilities if typed word matches a known word
            val nextWords = getNextWords(prefix, isArabic, limit = 5)
            for (nw in nextWords) {
                val nwKey = if (isArabic) normalizeArabic(nw) else nw.lowercase()
                if (seenWords.add(nwKey)) {
                    result.add(SuggestionItem(text = nw, isEmoji = false, isNextWord = true))
                    if (result.size >= limit) return result
                }
            }

        } else if (previousWord.isNotBlank()) {
            // User just finished typing a word or pressed space -> Provide Next-Word Predictions & Emojis!
            val prevClean = previousWord.trim()

            // 1. Contextual Emojis for previous word
            val emojis = getEmojisForWord(prevClean, limit = 3)
            for (em in emojis) {
                if (seenEmojis.add(em)) {
                    result.add(SuggestionItem(text = em, isEmoji = true))
                }
            }

            // 2. Next-Word predictions following previous word
            val nextWords = getNextWords(prevClean, isArabic, limit = 8)
            for (nw in nextWords) {
                val nwKey = if (isArabic) normalizeArabic(nw) else nw.lowercase()
                if (seenWords.add(nwKey)) {
                    result.add(SuggestionItem(text = nw, isEmoji = false, isNextWord = true, isPrimary = (result.isEmpty())))
                }
            }

            // 3. Morphological forms of previous word (e.g. after 'test' allow quick pick of 'testing', 'tested')
            val morphForms = getMorphologicalForms(prevClean, isArabic)
            for (form in morphForms) {
                val formKey = if (isArabic) normalizeArabic(form) else form.lowercase()
                if (seenWords.add(formKey)) {
                    result.add(SuggestionItem(text = form, isEmoji = false))
                }
            }

            // 4. Common fallback top words
            val fallback = topWords(isArabic, limit = 6)
            for (fw in fallback) {
                val fwKey = if (isArabic) normalizeArabic(fw) else fw.lowercase()
                if (seenWords.add(fwKey)) {
                    result.add(SuggestionItem(text = fw, isEmoji = false, isNextWord = true))
                }
            }

        } else {
            // Default top words & general emojis when no context
            val fallback = topWords(isArabic, limit = 6)
            for (fw in fallback) {
                result.add(SuggestionItem(text = fw, isEmoji = false, isNextWord = true))
            }
        }

        return result.take(limit)
    }

    /**
     * Classic suggestions helper for backward compatibility.
     */
    fun suggestions(prefix: String, isArabic: Boolean, limit: Int = 10): List<String> {
        val list = getContextualSuggestions(prefix, "", isArabic, limit)
        return list.filter { !it.isEmoji }.map { it.text }
    }

    /**
     * Records a word typed or tapped by the user for personalized ranking.
     */
    fun recordUsedWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.length >= 2) {
            synchronized(recentUserWords) {
                recentUserWords.remove(trimmed)
                recentUserWords.add(trimmed)
                if (recentUserWords.size > 300) {
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
     * Top frequently used words when no prefix has been typed yet.
     */
    fun topWords(isArabic: Boolean, limit: Int = 8): List<String> {
        return if (isArabic) {
            listOf("شكرا", "تمام", "مرحبا", "إن شاء الله", "الحمد لله", "أنا", "في", "على").take(limit)
        } else {
            listOf("the", "to", "and", "I", "you", "thanks", "hello", "good").take(limit)
        }
    }
}
