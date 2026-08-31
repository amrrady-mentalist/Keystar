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
import kotlin.math.abs
import kotlin.math.min

/**
 * Intelligent bilingual suggestion & correction engine:
 * Priority 1: Full word completion while writing (e.g., "foo" -> "food", "football", "foot", "footage")
 * Priority 2: Next-word prediction after space (e.g., "foot " -> "and", "it", "prints", "ball", "🦶", "👣")
 * Priority 3: Typo detection & spell-correction with auto-replace (e.g., "fot" -> "foot", "for", "fit")
 * Priority 4: Contraction & punctuation formatting (e.g., "lets" -> "let's", "dont" -> "don't", "cant" -> "can't")
 * Plus: Morphological expansion (past/present/future/plurals) and offline contextual emojis.
 */
object Dictionary {

    private const val TAG = "Dictionary"

    data class SuggestionItem(
        val text: String,
        val isEmoji: Boolean,
        val isNextWord: Boolean = false,
        val isPrimary: Boolean = false,
        val isCorrection: Boolean = false
    )

    private class Entry(val key: String, val word: String, val rank: Int)

    @Volatile
    private var isLoaded = false
    @Volatile
    private var isLoading = false

    private var enKeys: Array<String> = emptyArray()
    private var enEntries: Array<Entry> = emptyArray()
    private var enWordSet: HashSet<String> = HashSet()

    private var arKeys: Array<String> = emptyArray()
    private var arEntries: Array<Entry> = emptyArray()
    private var arWordSet: HashSet<String> = HashSet()

    private var emojiMap: Map<String, List<String>> = emptyMap()
    private var nextWordsMap: Map<String, List<String>> = emptyMap()

    // Recent user words
    private val recentUserWords = Collections.synchronizedSet(LinkedHashSet<String>())

    private val executor = Executors.newSingleThreadExecutor()

    // Common Contractions mapping (Priority 4)
    private val contractionsMap = mapOf(
        "lets" to "let's",
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "im" to "I'm",
        "youre" to "you're",
        "hes" to "he's",
        "shes" to "she's",
        "its" to "it's",
        "theyre" to "they're",
        "were" to "we're",
        "didnt" to "didn't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "hadnt" to "hadn't",
        "doesnt" to "doesn't",
        "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't",
        "couldnt" to "couldn't",
        "ive" to "I've",
        "youve" to "you've",
        "weve" to "we've",
        "theyve" to "they've",
        "ill" to "I'll",
        "youll" to "you'll",
        "hell" to "he'll",
        "shell" to "she'll",
        "theyll" to "they'll",
        "id" to "I'd",
        "youd" to "you'd",
        "hed" to "he'd",
        "shed" to "she'd",
        "theyd" to "they'd",
        "whats" to "what's",
        "whos" to "who's",
        "wheres" to "where's",
        "whens" to "when's",
        "whys" to "why's",
        "hows" to "how's",
        "thats" to "that's",
        "theres" to "there's",
        "heres" to "here's"
    )

    // Common typos / misspelled word overrides (Priority 3)
    private val commonTypoOverrides = mapOf(
        "fot" to listOf("foot", "for", "fit", "dot", "got", "fat"),
        "teh" to listOf("the"),
        "recieve" to listOf("receive"),
        "seperate" to listOf("separate"),
        "definately" to listOf("definitely"),
        "untill" to listOf("until"),
        "occured" to listOf("occurred"),
        "thier" to listOf("their", "there"),
        "beleive" to listOf("believe"),
        "tommorow" to listOf("tomorrow"),
        "agian" to listOf("again"),
        "becuase" to listOf("because"),
        "wich" to listOf("which"),
        "wierd" to listOf("weird"),
        "tset" to listOf("test"),
        "tseting" to listOf("testing"),
        "wodr" to listOf("word"),
        "halp" to listOf("help"),
        "plese" to listOf("please"),
        "thx" to listOf("thanks"),
        "tnx" to listOf("thanks"),
        "plz" to listOf("please"),
        "culd" to listOf("could"),
        "shuld" to listOf("should"),
        "wuld" to listOf("would"),
        "gud" to listOf("good"),
        "lov" to listOf("love"),
        "hapyt" to listOf("happy")
    )

    // Irregular verb forms table (English) for morphological expansion
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
        "let" to listOf("let's", "letting", "lets", "letter"),
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

    // Arabic morphological derivations
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
     * Initializes all bilingual dictionaries, transitions, and emoji mapping.
     */
    fun init(context: Context) {
        if (isLoaded || isLoading) return
        isLoading = true
        val appContext = context.applicationContext

        executor.execute {
            try {
                // 1. Load English dictionary
                val enList = mutableListOf<String>()
                try {
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
                } catch (e: Exception) {
                    Log.w(TAG, "Using fallback English words list", e)
                }

                if (enList.isEmpty()) {
                    enList.addAll(listOf(
                        "the", "be", "to", "of", "and", "a", "in", "that", "have", "I",
                        "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
                        "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
                        "foot", "feet", "food", "football", "footage", "footprint", "footwear",
                        "test", "testing", "tested", "tests", "tester",
                        "let", "lets", "let's", "letter", "letters", "little",
                        "sad", "sadness", "sadly", "happy", "happiness", "love", "loved", "loving",
                        "fire", "water", "car", "coffee", "tea", "magic", "magical", "secret"
                    ))
                }

                val tempEnEntries = ArrayList<Entry>(enList.size)
                val tempEnSet = HashSet<String>(enList.size)
                for (i in enList.indices) {
                    val w = enList[i]
                    val lower = w.lowercase()
                    tempEnEntries.add(Entry(lower, w, i))
                    tempEnSet.add(lower)
                }
                tempEnEntries.sortBy { it.key }
                val tempEnKeys = Array(tempEnEntries.size) { tempEnEntries[it].key }
                val tempEnArray = tempEnEntries.toTypedArray()

                // 2. Load Arabic dictionary
                val arList = mutableListOf<String>()
                try {
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
                } catch (e: Exception) {
                    Log.w(TAG, "Using fallback Arabic words list", e)
                }

                if (arList.isEmpty()) {
                    arList.addAll(listOf(
                        "الله", "في", "من", "على", "ما", "أن", "إلى", "لا", "هذا", "أو",
                        "شكرا", "تمام", "مرحبا", "أهلا", "صباح", "الخير", "مساء", "النور",
                        "الحمد", "لله", "إن", "شاء", "سعيد", "حزين", "حب", "حبيبي", "تسلم",
                        "قدم", "رجل", "طعام", "أكل", "كورة", "كرة", "تست", "اختبار", "سحر"
                    ))
                }

                val tempArEntries = ArrayList<Entry>(arList.size)
                val tempArSet = HashSet<String>(arList.size)
                for (i in arList.indices) {
                    val w = arList[i]
                    val norm = normalizeArabic(w)
                    tempArEntries.add(Entry(norm, w, i))
                    tempArSet.add(norm)
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
                enWordSet = tempEnSet
                arKeys = tempArKeys
                arEntries = tempArArray
                arWordSet = tempArSet
                emojiMap = tempEmojiMap
                nextWordsMap = tempNextWordsMap

                isLoaded = true
                isLoading = false
                Log.d(TAG, "Dictionary loaded: ${enEntries.size} English, ${arEntries.size} Arabic, ${emojiMap.size} emojis, ${nextWordsMap.size} bigrams.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading dictionary assets", e)
                isLoading = false
            }
        }
    }

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
     * Checks if a word exists in the dictionary.
     */
    fun isKnownWord(word: String, isArabic: Boolean): Boolean {
        val clean = if (isArabic) normalizeArabic(word) else word.trim().lowercase()
        if (clean.isEmpty()) return false
        val set = if (isArabic) arWordSet else enWordSet
        return set.contains(clean) || contractionsMap.containsKey(clean) || contractionsMap.containsValue(clean)
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

        // Direct fallback emojis
        val direct = when (clean) {
            "foot", "feet" -> listOf("🦶", "👣", "👟", "🧦")
            "sad", "sadness", "sorrow", "cry", "crying" -> listOf("😢", "😭", "😞", "💔")
            "happy", "happiness", "joy" -> listOf("😊", "😄", "😃", "🎉")
            "test", "testing", "tested", "tests" -> listOf("🧪", "📝", "🔬", "✅")
            "food", "eat", "eating" -> listOf("🍕", "🍔", "🍟", "🍲")
            "football" -> listOf("⚽", "🏈", "🏟️")
            "love", "loving", "loved" -> listOf("❤️", "😍", "🥰", "💕")
            "fire", "lit" -> listOf("🔥", "⚡")
            "magic", "magical" -> listOf("🪄", "🔮", "✨", "🎩")
            "secret" -> listOf("🤫", "🤐", "🔒")
            "money", "cash" -> listOf("💰", "💵", "🤑")
            "car", "drive" -> listOf("🚗", "🚘")
            "coffee", "tea" -> listOf("☕", "🍵")
            "party" -> listOf("🎉", "🥳")
            "قدم", "رجل" -> listOf("🦶", "👣", "👟")
            "حزن", "حزين", "زعلان", "دموع" -> listOf("😢", "😭", "😞", "💔")
            "حب", "بحبك", "قلبي" -> listOf("❤️", "😍", "🥰", "💕")
            "فرح", "سعيد", "مبسوط" -> listOf("😃", "😊", "🎉")
            "طعام", "اكل" -> listOf("🍕", "🍔", "🍲")
            "كورة", "كرة" -> listOf("⚽", "🏟️")
            "شكرا", "تسلم" -> listOf("🙏", "🌹", "❤️")
            "تمام", "صح" -> listOf("👍", "👌", "✔️")
            "سحر", "خدعة" -> listOf("🔮", "✨", "🪄", "🎩")
            "تست", "اختبار" -> listOf("🧪", "📝", "🔬")
            else -> null
        }
        return direct?.take(limit) ?: emptyList()
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
                results.add("ي$clean")
                results.add("${clean}ت")
                results.add("س${clean}")
                results.add("ال$clean")
            }
        } else {
            val irregular = irregularEnglishForms[clean]
            if (irregular != null) {
                results.addAll(irregular)
            }

            when {
                clean.endsWith("e") -> {
                    results.add(clean + "d")
                    results.add(clean + "r")
                    results.add(clean.dropLast(1) + "ing")
                    results.add(clean + "s")
                }
                clean.endsWith("y") && clean.length > 2 && !isVowel(clean[clean.length - 2]) -> {
                    val stem = clean.dropLast(1)
                    results.add(stem + "ied")
                    results.add(stem + "ies")
                    results.add(clean + "ing")
                }
                clean.endsWith("s") || clean.endsWith("sh") || clean.endsWith("ch") || clean.endsWith("x") || clean.endsWith("z") -> {
                    results.add(clean + "es")
                    results.add(clean + "ed")
                    results.add(clean + "ing")
                }
                shouldDoubleConsonant(clean) -> {
                    val last = clean.last()
                    results.add(clean + last + "ed")
                    results.add(clean + last + "ing")
                    results.add(clean + last + "er")
                    results.add(clean + "s")
                }
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
     * Next-word prediction (Bigrams) for the given preceding word (Priority 2).
     */
    fun getNextWords(previousWord: String, isArabic: Boolean, limit: Int = 8): List<String> {
        val clean = previousWord.trim().lowercase()
        if (clean.isEmpty()) return emptyList()

        val list = nextWordsMap[clean] ?: nextWordsMap[normalizeArabic(clean)]
        if (list != null && list.isNotEmpty()) {
            return list.take(limit)
        }

        // Context-aware defaults
        return when (clean) {
            "foot" -> listOf("ball", "prints", "step", "wear", "and", "it", "note", "traffic")
            "test" -> listOf("results", "flight", "case", "drive", "tube", "run", "it", "out")
            "let" -> listOf("us", "me", "go", "it", "know", "them", "him", "her")
            "let's" -> listOf("go", "do", "see", "meet", "talk", "start", "get", "try")
            "sad" -> listOf("to", "that", "and", "about", "day", "news")
            "food" -> listOf("and", "is", "delivery", "store", "truck", "safety")
            "thank" -> listOf("you", "God", "everyone", "him", "her")
            "thanks" -> listOf("for", "a", "lot", "again", "bro", "so", "much")
            else -> {
                if (isArabic) {
                    listOf("يا", "في", "من", "على", "جدا", "تمام", "إن شاء الله", "الحمد لله").take(limit)
                } else {
                    listOf("and", "it", "the", "to", "is", "for", "you", "in").take(limit)
                }
            }
        }.take(limit)
    }

    /**
     * Computes typo spell corrections using Damerau-Levenshtein distance (Priority 3).
     */
    fun getTypoCorrections(word: String, isArabic: Boolean, limit: Int = 5): List<String> {
        val query = if (isArabic) normalizeArabic(word) else word.trim().lowercase()
        if (query.length < 2) return emptyList()

        // 1. Check direct override table
        val override = commonTypoOverrides[query]
        if (override != null) return override.take(limit)

        // 2. Check contractions (e.g., "lets" -> "let's")
        val contraction = contractionsMap[query]
        if (contraction != null) {
            return listOf(contraction)
        }

        if (!isLoaded) return emptyList()

        val entries = if (isArabic) arEntries else enEntries
        val candidates = mutableListOf<Pair<String, Int>>()

        // Scan high frequency entries (top 2500) for distance <= 2
        val maxEntriesToScan = min(entries.size, 2500)
        for (i in 0 until maxEntriesToScan) {
            val entry = entries[i]
            val key = entry.key
            if (abs(key.length - query.length) > 2) continue
            val dist = editDistance(query, key)
            if (dist in 1..2) {
                // Score = distance * 1000 + rank
                val score = dist * 1000 + entry.rank
                candidates.add(entry.word to score)
            }
        }

        candidates.sortBy { it.second }
        return candidates.map { it.first }.distinct().take(limit)
    }

    private fun editDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost)
                // Transposition
                if (i > 1 && j > 1 && s1[i - 1] == s2[j - 2] && s1[i - 2] == s2[j - 1]) {
                    dp[i][j] = min(dp[i][j], dp[i - 2][j - 2] + 1)
                }
            }
        }
        return dp[s1.length][s2.length]
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
     * Primary Suggestion Pipeline implementing the 4 prioritized requirements:
     *
     * 1. Priority 1 (While typing word):
     *    - Word completions starting with typed prefix (e.g. "foo" -> "food", "football", "foot", "footage")
     *    - Direct contraction replacement (e.g. "lets" -> "let's", "dont" -> "don't")
     *    - Morphological extensions (e.g. "test" -> "testing", "tested", "tests")
     *    - Contextual emoji chips for typed word (e.g. "sad" -> 😢, 😭)
     *
     * 2. Priority 2 (After space):
     *    - Next-word predictions for previous word (e.g. "foot " -> "and", "it", "prints", "ball")
     *    - Contextual emojis for previous word (e.g. "foot " -> 🦶, 👣)
     *
     * 3. Priority 3 (Typo & Misspelling correction):
     *    - If typed word is not in dictionary (e.g. "fot"), highlight top correction "foot"
     *    - Tapping replaces word automatically.
     */
    fun getContextualSuggestions(
        currentWord: String,
        previousWord: String,
        isArabic: Boolean,
        limit: Int = 16
    ): List<SuggestionItem> {
        val result = mutableListOf<SuggestionItem>()
        val seenWords = HashSet<String>()
        val seenEmojis = HashSet<String>()

        val prefix = currentWord.trim()

        if (prefix.isNotEmpty()) {
            val query = if (isArabic) normalizeArabic(prefix) else prefix.lowercase()

            // --- PRIORITY 4: Check Contractions ("lets" -> "let's", "dont" -> "don't", "im" -> "I'm") ---
            val contractionMatch = contractionsMap[query]
            if (contractionMatch != null) {
                result.add(SuggestionItem(text = contractionMatch, isEmoji = false, isPrimary = true, isCorrection = true))
                seenWords.add(contractionMatch.lowercase())
            }

            // --- PRIORITY 3: Check Typo / Spelling Correction if word looks misspelled (e.g. "fot" -> "foot") ---
            val isKnown = isKnownWord(prefix, isArabic)
            var typoCorrections = emptyList<String>()
            if (!isKnown && prefix.length >= 2) {
                typoCorrections = getTypoCorrections(prefix, isArabic, limit = 4)
                if (typoCorrections.isNotEmpty()) {
                    val bestFix = typoCorrections.first()
                    if (seenWords.add(bestFix.lowercase())) {
                        result.add(SuggestionItem(text = bestFix, isEmoji = false, isPrimary = true, isCorrection = true))
                    }
                }
            }

            // --- PRIORITY 1: Word Completions starting with prefix ("foo" -> "food", "football", "foot", "footage") ---
            // 1a. Direct prefix matches from dictionary sorted by frequency rank
            if (isLoaded) {
                val keys = if (isArabic) arKeys else enKeys
                val entries = if (isArabic) arEntries else enEntries

                val startIdx = binarySearchStart(keys, query)
                val candidates = mutableListOf<Entry>()
                val maxScan = 2500
                var scanned = 0
                var idx = startIdx

                while (idx < keys.size && scanned < maxScan) {
                    val k = keys[idx]
                    if (!k.startsWith(query)) break
                    val entry = entries[idx]
                    if (!seenWords.contains(entry.key)) {
                        candidates.add(entry)
                    }
                    idx++
                    scanned++
                }

                // Rank by frequency rank first, then length
                candidates.sortWith(
                    compareBy<Entry> { it.rank }
                        .thenBy { it.word.length }
                )

                for (cand in candidates) {
                    if (seenWords.add(cand.key)) {
                        val isFirstMatch = result.isEmpty()
                        result.add(SuggestionItem(text = cand.word, isEmoji = false, isPrimary = isFirstMatch))
                        if (result.size >= limit) return result
                    }
                }
            }

            // 1b. Morphological extensions of typed word (e.g., "test" -> "testing", "tested", "tests", "tester")
            val morphForms = getMorphologicalForms(prefix, isArabic)
            for (form in morphForms) {
                val formKey = if (isArabic) normalizeArabic(form) else form.lowercase()
                if (seenWords.add(formKey)) {
                    result.add(SuggestionItem(text = form, isEmoji = false))
                }
            }

            // 1c. Add other typo candidates if any
            for (fix in typoCorrections) {
                val fixKey = if (isArabic) normalizeArabic(fix) else fix.lowercase()
                if (seenWords.add(fixKey)) {
                    result.add(SuggestionItem(text = fix, isEmoji = false, isCorrection = true))
                }
            }

            // 1d. Contextual Emojis for typed word (e.g., "sad" -> 😢, 😭; "foot" -> 🦶, 👣; "food" -> 🍕, 🍔)
            val emojis = getEmojisForWord(prefix, limit = 3)
            for (em in emojis) {
                if (seenEmojis.add(em)) {
                    result.add(SuggestionItem(text = em, isEmoji = true))
                }
            }

            // 1e. Recent user words boost
            synchronized(recentUserWords) {
                for (w in recentUserWords) {
                    val normW = if (isArabic) normalizeArabic(w) else w.lowercase()
                    if (normW.startsWith(query) && seenWords.add(normW)) {
                        result.add(SuggestionItem(text = w, isEmoji = false))
                    }
                }
            }

            // 1f. Exact typed word chip as fallback if not present
            if (result.none { it.text.equals(prefix, ignoreCase = true) }) {
                result.add(SuggestionItem(text = prefix, isEmoji = false))
            }

        } else if (previousWord.isNotBlank()) {
            // --- PRIORITY 2: Next-Word Predictions & Emojis after space (e.g., "foot " -> "and", "it", "prints", "ball", "🦶", "👣") ---
            val prevClean = previousWord.trim()

            // 2a. Next-Word predictions following the committed word
            val nextWords = getNextWords(prevClean, isArabic, limit = 8)
            for (nw in nextWords) {
                val nwKey = if (isArabic) normalizeArabic(nw) else nw.lowercase()
                if (seenWords.add(nwKey)) {
                    val isFirst = result.isEmpty()
                    result.add(SuggestionItem(text = nw, isEmoji = false, isNextWord = true, isPrimary = isFirst))
                }
            }

            // 2b. Contextual Emojis for the committed word
            val emojis = getEmojisForWord(prevClean, limit = 4)
            for (em in emojis) {
                if (seenEmojis.add(em)) {
                    result.add(SuggestionItem(text = em, isEmoji = true))
                }
            }

            // 2c. Morphological derivations of previous word
            val morphForms = getMorphologicalForms(prevClean, isArabic)
            for (form in morphForms) {
                val formKey = if (isArabic) normalizeArabic(form) else form.lowercase()
                if (seenWords.add(formKey)) {
                    result.add(SuggestionItem(text = form, isEmoji = false))
                }
            }

            // 2d. Common conversational next words
            val fallback = topWords(isArabic, limit = 6)
            for (fw in fallback) {
                val fwKey = if (isArabic) normalizeArabic(fw) else fw.lowercase()
                if (seenWords.add(fwKey)) {
                    result.add(SuggestionItem(text = fw, isEmoji = false, isNextWord = true))
                }
            }

        } else {
            // Default top words when input is empty
            val fallback = topWords(isArabic, limit = 8)
            for (fw in fallback) {
                result.add(SuggestionItem(text = fw, isEmoji = false, isNextWord = true))
            }
        }

        return result.take(limit)
    }

    /**
     * Classic suggestions helper.
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
                if (recentUserWords.size > 400) {
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
