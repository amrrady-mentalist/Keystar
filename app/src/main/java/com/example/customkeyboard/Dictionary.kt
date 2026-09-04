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
    private var enFrequentEntries: Array<Entry> = emptyArray()
    private var enLetterMap: Map<Char, List<Entry>> = emptyMap()
    private var enWordSet: HashSet<String> = HashSet()

    private var arKeys: Array<String> = emptyArray()
    private var arEntries: Array<Entry> = emptyArray()
    private var arFrequentEntries: Array<Entry> = emptyArray()
    private var arLetterMap: Map<Char, List<Entry>> = emptyMap()
    private var arWordSet: HashSet<String> = HashSet()

    private var emojiMap: Map<String, List<String>> = emptyMap()
    private var nextWordsMap: Map<String, List<String>> = emptyMap()

    // QWERTY keyboard neighbor keys for typo detection
    private val qwertyNeighbors = mapOf(
        'a' to charArrayOf('q', 'w', 's', 'z'),
        'b' to charArrayOf('v', 'g', 'h', 'n'),
        'c' to charArrayOf('x', 'd', 'f', 'v'),
        'd' to charArrayOf('s', 'e', 'r', 'f', 'c', 'x'),
        'e' to charArrayOf('w', 'r', 's', 'd'),
        'f' to charArrayOf('d', 'r', 't', 'g', 'v', 'c'),
        'g' to charArrayOf('f', 't', 'y', 'h', 'b', 'v'),
        'h' to charArrayOf('g', 'y', 'u', 'j', 'n', 'b'),
        'i' to charArrayOf('u', 'o', 'j', 'k'),
        'j' to charArrayOf('h', 'u', 'i', 'k', 'm', 'n'),
        'k' to charArrayOf('j', 'i', 'o', 'l', 'm'),
        'l' to charArrayOf('k', 'o', 'p'),
        'm' to charArrayOf('n', 'j', 'k'),
        'n' to charArrayOf('b', 'h', 'j', 'm'),
        'o' to charArrayOf('i', 'p', 'k', 'l'),
        'p' to charArrayOf('o', 'l'),
        'q' to charArrayOf('w', 'a'),
        'r' to charArrayOf('e', 't', 'd', 'f'),
        's' to charArrayOf('a', 'w', 'e', 'd', 'x', 'z'),
        't' to charArrayOf('r', 'y', 'f', 'g'),
        'u' to charArrayOf('y', 'i', 'h', 'j'),
        'v' to charArrayOf('c', 'f', 'g', 'b'),
        'w' to charArrayOf('q', 'e', 'a', 's'),
        'x' to charArrayOf('z', 's', 'd', 'c'),
        'y' to charArrayOf('t', 'u', 'g', 'h'),
        'z' to charArrayOf('a', 's', 'x')
    )

    // Recent user words
    private val recentUserWords = Collections.synchronizedSet(LinkedHashSet<String>())

    private val executor = Executors.newSingleThreadExecutor()

    // Common Contractions mapping (Priority 4)
    private val contractionsMap = mapOf(
        "its" to "it's",
        "hadnt" to "hadn't",
        "dont" to "don't",
        "cant" to "can't",
        "wont" to "won't",
        "didnt" to "didn't",
        "isnt" to "isn't",
        "arent" to "aren't",
        "wasnt" to "wasn't",
        "werent" to "weren't",
        "hasnt" to "hasn't",
        "havent" to "haven't",
        "doesnt" to "doesn't",
        "wouldnt" to "wouldn't",
        "shouldnt" to "shouldn't",
        "couldnt" to "couldn't",
        "mustnt" to "mustn't",
        "neednt" to "needn't",
        "darent" to "daren't",
        "shant" to "shan't",
        "mightnt" to "mightn't",
        "oughtnt" to "oughtn't",
        "im" to "I'm",
        "youre" to "you're",
        "hes" to "he's",
        "shes" to "she's",
        "theyre" to "they're",
        "were" to "we're",
        "ive" to "I've",
        "youve" to "you've",
        "weve" to "we've",
        "theyve" to "they've",
        "ill" to "I'll",
        "youll" to "you'll",
        "hell" to "he'll",
        "shell" to "she'll",
        "theyll" to "they'll",
        "well" to "we'll",
        "id" to "I'd",
        "youd" to "you'd",
        "hed" to "he'd",
        "shed" to "she'd",
        "theyd" to "they'd",
        "wed" to "we'd",
        "thats" to "that's",
        "whats" to "what's",
        "whos" to "who's",
        "wheres" to "where's",
        "whens" to "when's",
        "whys" to "why's",
        "hows" to "how's",
        "theres" to "there's",
        "heres" to "here's",
        "lets" to "let's",
        "whove" to "who've",
        "whatll" to "what'll",
        "whatve" to "what've",
        "thereve" to "there've",
        "therell" to "there'll",
        "couldve" to "could've",
        "shouldve" to "should've",
        "wouldve" to "would've",
        "mightve" to "might've",
        "mustve" to "must've",
        "itll" to "it'll",
        "thatll" to "that'll",
        "howd" to "how'd",
        "howll" to "how'll",
        "whered" to "where'd",
        "whod" to "who'd",
        "wholl" to "who'll",
        "whyd" to "why'd",
        "cmon" to "c'mon",
        "maam" to "ma'am",
        "oclock" to "o'clock",
        "yall" to "y'all",
        "aint" to "ain't",
        "gonna" to "going to",
        "wanna" to "want to",
        "gotta" to "got to",
        "kinda" to "kind of",
        "sorta" to "sort of",
        "dunno" to "don't know",
        "lemme" to "let me",
        "gimme" to "give me"
    )

    fun matchCasing(source: String, target: String): String {
        if (source.isEmpty() || target.isEmpty()) return target
        if (source.all { it.isUpperCase() }) return target.uppercase()
        if (source[0].isUpperCase()) {
            return target.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        return target
    }

    // Common typos / misspelled word overrides (Priority 3)
    private val commonTypoOverrides = mapOf(
        "habet" to listOf("habit"),
        "habets" to listOf("habits"),
        "inhancment" to listOf("enhancement"),
        "inhancments" to listOf("enhancements"),
        "inhance" to listOf("enhance"),
        "inhancing" to listOf("enhancing"),
        "writting" to listOf("writing"),
        "relevent" to listOf("relevant"),
        "relevently" to listOf("relevantly"),
        "goverment" to listOf("government"),
        "neccessary" to listOf("necessary"),
        "necesary" to listOf("necessary"),
        "seperate" to listOf("separate"),
        "definately" to listOf("definitely"),
        "definitly" to listOf("definitely"),
        "untill" to listOf("until"),
        "occured" to listOf("occurred"),
        "wierd" to listOf("weird"),
        "recieve" to listOf("receive"),
        "recieved" to listOf("received"),
        "recieving" to listOf("receiving"),
        "tommorow" to listOf("tomorrow"),
        "tommorrow" to listOf("tomorrow"),
        "thier" to listOf("their", "there"),
        "beleive" to listOf("believe"),
        "truely" to listOf("truly"),
        "freind" to listOf("friend"),
        "peice" to listOf("piece"),
        "calender" to listOf("calendar"),
        "begining" to listOf("beginning"),
        "alot" to listOf("a lot"),
        "teh" to listOf("the"),
        "fot" to listOf("foot", "for", "fit", "dot", "got", "fat"),
        "agian" to listOf("again"),
        "becuase" to listOf("because"),
        "wich" to listOf("which"),
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
     * Attempts to open an asset reader for both compressed (.gz) and uncompressed asset variants.
     */
    private fun openAssetReader(appContext: Context, baseName: String): BufferedReader? {
        // 1. Try exact name (e.g. dict_en.txt, emoji_map.json)
        try {
            val inStream = appContext.assets.open(baseName)
            if (baseName.endsWith(".gz")) {
                return BufferedReader(InputStreamReader(GZIPInputStream(inStream), Charsets.UTF_8))
            }
            return BufferedReader(InputStreamReader(inStream, Charsets.UTF_8))
        } catch (_: Exception) {}

        // 2. If name had .gz, try without .gz
        if (baseName.endsWith(".gz")) {
            val plain = baseName.removeSuffix(".gz")
            try {
                val inStream = appContext.assets.open(plain)
                return BufferedReader(InputStreamReader(inStream, Charsets.UTF_8))
            } catch (_: Exception) {}
        }

        // 3. If name didn't have .gz, try with .gz
        if (!baseName.endsWith(".gz")) {
            try {
                val inStream = appContext.assets.open("$baseName.gz")
                return BufferedReader(InputStreamReader(GZIPInputStream(inStream), Charsets.UTF_8))
            } catch (_: Exception) {}
        }

        return null
    }

    /**
     * Initializes all bilingual dictionaries, transitions, and emoji mapping.
     */
    fun init(context: Context) {
        if (isLoaded || isLoading) return
        isLoading = true
        val appContext = context.applicationContext
        UserHabitsManager.init(appContext)

        executor.execute {
            try {
                // 1. Load English dictionary
                val enList = mutableListOf<String>()
                try {
                    openAssetReader(appContext, "dict_en.txt")?.use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val w = line!!.trim()
                            if (w.isNotEmpty()) enList.add(w)
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
                        "fire", "water", "car", "coffee", "tea", "magic", "magical", "secret",
                        "habit", "habits", "enhance", "enhancement", "enhancements", "write", "writing"
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
                val tempEnFrequent = tempEnEntries.take(min(tempEnEntries.size, 10000)).toTypedArray()
                val tempEnLetterMap = tempEnEntries.groupBy { it.key.firstOrNull() ?: ' ' }

                tempEnEntries.sortBy { it.key }
                val tempEnKeys = Array(tempEnEntries.size) { tempEnEntries[it].key }
                val tempEnArray = tempEnEntries.toTypedArray()

                // 2. Load Arabic dictionary
                val arList = mutableListOf<String>()
                try {
                    openAssetReader(appContext, "dict_ar.txt")?.use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val w = line!!.trim()
                            if (w.isNotEmpty()) arList.add(w)
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
                val tempArFrequent = tempArEntries.take(min(tempArEntries.size, 5000)).toTypedArray()
                val tempArLetterMap = tempArEntries.groupBy { it.key.firstOrNull() ?: ' ' }

                tempArEntries.sortBy { it.key }
                val tempArKeys = Array(tempArEntries.size) { tempArEntries[it].key }
                val tempArArray = tempArEntries.toTypedArray()

                // 3. Load Word-to-Emoji offline map
                val tempEmojiMap = mutableMapOf<String, List<String>>()
                try {
                    openAssetReader(appContext, "emoji_map.json")?.use { reader ->
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
                } catch (e: Exception) {
                    Log.w(TAG, "Using fallback emoji map", e)
                }

                if (tempEmojiMap.isEmpty()) {
                    tempEmojiMap["love"] = listOf("❤️", "😍", "💕", "🥰")
                    tempEmojiMap["happy"] = listOf("😊", "😃", "🎉", "🥳")
                    tempEmojiMap["sad"] = listOf("😢", "😭", "😞", "💔")
                    tempEmojiMap["fire"] = listOf("🔥", "⚡", "💥")
                    tempEmojiMap["magic"] = listOf("🪄", "🔮", "✨", "🎩")
                    tempEmojiMap["test"] = listOf("🧪", "📝", "🔬")
                    tempEmojiMap["foot"] = listOf("🦶", "👣", "👟", "⚽")
                    tempEmojiMap["food"] = listOf("🍕", "🍔", "🍟", "🍲")
                    tempEmojiMap["coffee"] = listOf("☕", "🍵", "🧋")
                    tempEmojiMap["حب"] = listOf("❤️", "😍", "🥰")
                    tempEmojiMap["سعيد"] = listOf("😃", "😊", "🎉")
                    tempEmojiMap["حزين"] = listOf("😢", "😭", "😞")
                    tempEmojiMap["شكرا"] = listOf("🙏", "🌹", "❤️")
                }

                // 4. Load Next-Word transitions map
                val tempNextWordsMap = mutableMapOf<String, List<String>>()
                try {
                    openAssetReader(appContext, "next_words.json")?.use { reader ->
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
                } catch (e: Exception) {
                    Log.w(TAG, "Using fallback next words map", e)
                }

                if (tempNextWordsMap.isEmpty()) {
                    tempNextWordsMap["let's"] = listOf("go", "do", "see", "meet", "try")
                    tempNextWordsMap["how"] = listOf("are", "is", "to", "do", "much")
                    tempNextWordsMap["what"] = listOf("is", "are", "do", "you", "time")
                    tempNextWordsMap["thank"] = listOf("you", "God", "everyone")
                    tempNextWordsMap["thanks"] = listOf("for", "a", "lot", "bro")
                    tempNextWordsMap["good"] = listOf("morning", "night", "job", "luck")
                    tempNextWordsMap["صباح"] = listOf("الخير", "الورد", "النور")
                    tempNextWordsMap["مساء"] = listOf("الخير", "النور", "الورد")
                    tempNextWordsMap["شكرا"] = listOf("جزيلا", "لك", "يا")
                    tempNextWordsMap["إن"] = listOf("شاء", "الله")
                    tempNextWordsMap["الحمد"] = listOf("لله")
                }

                enKeys = tempEnKeys
                enEntries = tempEnArray
                enFrequentEntries = tempEnFrequent
                enLetterMap = tempEnLetterMap
                enWordSet = tempEnSet
                arKeys = tempArKeys
                arEntries = tempArArray
                arFrequentEntries = tempArFrequent
                arLetterMap = tempArLetterMap
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
        return set.contains(clean) ||
               contractionsMap.containsKey(clean) ||
               contractionsMap.containsValue(clean) ||
               UserHabitsManager.isLearnedWord(clean)
    }

    /**
     * Finds related emojis for a given word offline using full semantic mappings and keyword matches.
     */
    fun getEmojisForWord(word: String, limit: Int = 4): List<String> {
        val clean = word.trim().lowercase()
        if (clean.isEmpty()) return emptyList()
        val norm = normalizeArabic(clean)

        val results = LinkedHashSet<String>()

        // 1. Direct emojiMap matches from ingested JSON
        emojiMap[clean]?.let { results.addAll(it) }
        emojiMap[norm]?.let { results.addAll(it) }

        // 2. High-precision semantic offline mapping (English & Arabic)
        val semanticMatches = lookupSemanticEmojis(clean, norm)
        results.addAll(semanticMatches)

        // 3. Keyword / prefix search in emojiMap for partial words (e.g., "foo" -> matches "food" -> 🍕, 🍔)
        if (results.size < limit && clean.length >= 3) {
            for ((k, list) in emojiMap) {
                if (k.startsWith(clean) || clean.startsWith(k)) {
                    results.addAll(list)
                    if (results.size >= limit * 2) break
                }
            }
        }

        return results.take(limit).toList()
    }

    private fun lookupSemanticEmojis(clean: String, norm: String): List<String> {
        return when {
            // Foot / Feet / Steps / Shoes
            clean in listOf("foot", "feet", "toe", "step", "walk", "walking", "runner", "shoe", "shoes") ->
                listOf("🦶", "👣", "👟", "🧦")
            norm in listOf("قدم", "رجل", "خطوة", "ارجل", "كعب", "حذاء", "مشى", "يمشي") ->
                listOf("🦶", "👣", "👟", "🧦")

            // Food / Eating / Snacks / Cooking
            clean in listOf("foo", "food", "eat", "eating", "eaten", "cook", "cooking", "snack", "dinner", "lunch", "meal", "pizza", "burger") ->
                listOf("🍕", "🍔", "🍟", "🍲", "🥗")
            norm in listOf("طعام", "اكل", "ياكل", "وجبة", "بيتزا", "برجر", "غداء", "عشاء", "طبخ") ->
                listOf("🍕", "🍔", "🍟", "🍲", "🥗")

            // Football / Soccer / Sports
            clean in listOf("football", "soccer", "ball", "match", "game", "goal", "fifa") ->
                listOf("⚽", "🏈", "🏟️", "🏆")
            norm in listOf("كورة", "كرة", "قدم", "مباراة", "ملعب", "هدف", "كاس") ->
                listOf("⚽", "🏟️", "🏆")

            // Sad / Crying / Tears / Heartbreak
            clean in listOf("sad", "sadness", "sadly", "cry", "crying", "tears", "depressed", "unhappy", "sorrow", "grief") ->
                listOf("😢", "😭", "💔", "😞", "🥺")
            norm in listOf("حزن", "حزين", "زعلان", "دموع", "بكى", "تعبان", "مقهور", "قلبي") ->
                listOf("😢", "😭", "💔", "😞", "🥺")

            // Happy / Joy / Smile / Laugh
            clean in listOf("happy", "happiness", "joy", "smile", "smiling", "glad", "cheerful", "excited") ->
                listOf("😊", "😄", "😃", "🎉", "✨")
            norm in listOf("فرح", "سعيد", "مبسوط", "فرحان", "ضحك", "روعة", "مبتسم") ->
                listOf("😃", "😊", "🎉", "✨")

            // Love / Heart / Romantic
            clean in listOf("love", "loving", "loved", "heart", "crush", "sweetheart", "kiss", "kisses", "romance") ->
                listOf("❤️", "😍", "🥰", "💕", "💖", "😘")
            norm in listOf("حب", "بحبك", "قلبي", "حبيبي", "حبيبتي", "عشقي", "غرام", "بوسة") ->
                listOf("❤️", "😍", "🥰", "💕", "😘")

            // Fire / Flame / Lit / Hot
            clean in listOf("fire", "flame", "lit", "hot", "burn", "burning", "spicy") ->
                listOf("🔥", "⚡", "💥")
            norm in listOf("نار", "ولعة", "حريقة", "مولع", "شعلة") ->
                listOf("🔥", "⚡", "💥")

            // Test / Chemistry / Science / Quiz
            clean in listOf("test", "testing", "tested", "tests", "exam", "quiz", "check", "lab") ->
                listOf("🧪", "📝", "🔬", "✅")
            norm in listOf("تست", "اختبار", "امتحان", "فحص", "تجربة", "معمل") ->
                listOf("🧪", "📝", "🔬", "✅")

            // Thank / Thanks / Gratitude
            clean in listOf("thank", "thanks", "grateful", "appreciate", "blessed") ->
                listOf("🙏", "🌹", "❤️", "✨")
            norm in listOf("شكرا", "تسلم", "مشكور", "يسلمو", "الف شكر", "بارك الله") ->
                listOf("🙏", "🌹", "❤️", "✨")

            // Good morning / Good night
            clean in listOf("morning", "sun", "sunrise") ->
                listOf("☀️", "🌅", "☕")
            clean in listOf("night", "sleep", "dream", "moon") ->
                listOf("🌙", "⭐", "😴", "✨")
            norm in listOf("صباح", "شمس") ->
                listOf("☀️", "🌸", "☕")
            norm in listOf("مساء", "ليل", "نوم", "قمر") ->
                listOf("🌙", "✨", "🌹")

            // Money / Cash / Rich
            clean in listOf("money", "cash", "dollar", "rich", "wealth", "pay", "payment") ->
                listOf("💰", "💵", "🤑", "💳")
            norm in listOf("فلوس", "مصاري", "مال", "دولار", "غني") ->
                listOf("💰", "💵", "🤑")

            // Car / Driving / Vehicle
            clean in listOf("car", "drive", "driving", "auto", "vehicle", "ride") ->
                listOf("🚗", "🚘", "🏎️")
            norm in listOf("عربية", "سيارة", "سواقة", "عربيات") ->
                listOf("🚗", "🚘")

            // Coffee / Tea / Drinks
            clean in listOf("coffee", "tea", "drink", "cafe", "espresso", "latte", "cup") ->
                listOf("☕", "🍵", "🧋", "🥤")
            norm in listOf("قهوة", "شاي", "كافيه", "مشروب", "عصير") ->
                listOf("☕", "🍵", "🧋")

            // Party / Celebration / Birthday
            clean in listOf("party", "celebrate", "birthday", "cheers", "festival", "dance") ->
                listOf("🎉", "🥳", "🍾", "🎂", "🎈")
            norm in listOf("حفلة", "عيد ميلاد", "مبروك", "تهانينا", "احتفال") ->
                listOf("🎉", "🥳", "🎂", "🎈")

            // Magic / Mystery / Trick
            clean in listOf("magic", "magical", "trick", "wizard", "illusion", "secret") ->
                listOf("🪄", "🔮", "✨", "🎩", "🤫")
            norm in listOf("سحر", "خدعة", "ساحر", "سري", "خفي") ->
                listOf("🪄", "🔮", "✨", "🎩", "🤫")

            // Affirmation / OK / Yes / Done
            clean in listOf("ok", "okay", "yes", "done", "good", "great", "nice", "perfect", "cool") ->
                listOf("👍", "👌", "✅", "😎", "💯")
            norm in listOf("تمام", "صح", "ماشي", "اوكي", "مضبوط", "حلو", "جميل") ->
                listOf("👍", "👌", "✅", "💯")

            // Laugh / LOL / Funny
            clean in listOf("lol", "haha", "hahaha", "laugh", "funny", "hilarious", "joke") ->
                listOf("😂", "🤣", "😆")
            norm in listOf("هههه", "ههههه", "ضحك", "نكته", "مسخرة") ->
                listOf("😂", "🤣", "😆")

            else -> emptyList()
        }
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

    // Built-in next-word predictions (Bigrams)
    private val builtInNextWords = mapOf(
        "foot" to listOf("ball", "prints", "step", "wear", "and", "it", "traffic", "note"),
        "feet" to listOf("and", "on", "off", "high", "deep", "tall"),
        "food" to listOf("and", "is", "delivery", "store", "court", "truck", "safety"),
        "football" to listOf("game", "match", "player", "club", "team", "season"),
        "test" to listOf("results", "flight", "case", "drive", "tube", "run", "it", "out"),
        "let" to listOf("us", "me", "go", "it", "know", "them", "him", "her"),
        "let's" to listOf("go", "do", "see", "meet", "talk", "start", "get", "try", "make"),
        "sad" to listOf("to", "that", "and", "about", "day", "news", "story"),
        "happy" to listOf("birthday", "new", "to", "for", "day", "anniversary", "with"),
        "love" to listOf("you", "it", "this", "my", "to", "so", "forever"),
        "thank" to listOf("you", "God", "so", "very", "everyone", "him", "her"),
        "thanks" to listOf("for", "a", "lot", "again", "bro", "so", "much", "man"),
        "how" to listOf("are", "is", "about", "to", "do", "can", "was", "did"),
        "what" to listOf("is", "are", "do", "about", "did", "happened", "time", "can"),
        "where" to listOf("are", "is", "do", "did", "were", "can"),
        "who" to listOf("is", "are", "was", "were", "knows", "can"),
        "why" to listOf("not", "did", "do", "is", "are", "would"),
        "i" to listOf("am", "have", "will", "would", "want", "think", "love", "can", "know", "need", "feel"),
        "you" to listOf("are", "have", "can", "will", "want", "know", "think", "need", "look"),
        "he" to listOf("is", "was", "has", "said", "will", "wants", "can"),
        "she" to listOf("is", "was", "has", "said", "will", "wants", "can"),
        "we" to listOf("are", "have", "can", "will", "need", "want", "should"),
        "they" to listOf("are", "were", "have", "will", "can", "said"),
        "it" to listOf("is", "was", "will", "would", "has", "can", "looks", "seems"),
        "good" to listOf("morning", "night", "job", "luck", "idea", "day", "news", "time", "one", "thing"),
        "great" to listOf("job", "work", "idea", "news", "day", "time", "to"),
        "see" to listOf("you", "it", "what", "how", "if", "that"),
        "have" to listOf("a", "been", "to", "you", "fun", "time", "done"),
        "can" to listOf("you", "I", "we", "be", "do", "see", "help"),
        "will" to listOf("be", "have", "do", "see", "call", "come"),
        "do" to listOf("you", "not", "it", "that", "this"),
        "my" to listOf("friend", "love", "phone", "car", "name", "life", "dear"),
        "your" to listOf("name", "phone", "time", "help", "order", "place"),
        "fire" to listOf("alarm", "department", "truck", "station", "hazard"),
        // Arabic Bigrams
        "صباح" to listOf("الخير", "الورد", "النور", "الفل", "الجمال"),
        "مساء" to listOf("الخير", "النور", "الورد", "الفل", "الجمال"),
        "شكرا" to listOf("جزيلا", "لك", "يا", "جدا", "كتير", "حبيبي"),
        "الحمد" to listOf("لله", "والشكر لله"),
        "ان" to listOf("شاء الله", "كنت", "لم", "كان"),
        "عامل" to listOf("ايه", "اي", "تمام", "شغل"),
        "ازيك" to listOf("يا", "عامل ايه", "اخبارك"),
        "تمام" to listOf("جدا", "الحمد لله", "يا باشا", "كده"),
        "انا" to listOf("تمام", "بخير", "في", "رايح", "بحبك", "عايز", "مش"),
        "انت" to listOf("فين", "عامل ايه", "وحشني", "صح", "جميل"),
        "هو" to listOf("فين", "كان", "قال", "رايح"),
        "هي" to listOf("فين", "كانت", "قالت", "رايحة"),
        "كل" to listOf("سنة وانت طيب", "يوم", "حاجة", "مرة", "واحد"),
        "في" to listOf("البيت", "الشغل", "الطريق", "مصر", "كل مكان"),
        "مع" to listOf("السلامة", "ألف سلامة", "بعض", "حبيبي")
    )

    /**
     * Next-word prediction (Bigrams) for the given preceding word (Priority 2).
     */
    fun getNextWords(previousWord: String, isArabic: Boolean, limit: Int = 8): List<String> {
        val clean = previousWord.trim().lowercase()
        if (clean.isEmpty()) return emptyList()
        val norm = if (isArabic) normalizeArabic(clean) else clean

        val results = LinkedHashSet<String>()

        // 0. User's personal learned next words
        val userLearned = UserHabitsManager.getLearnedNextWords(clean, limit = 4)
        results.addAll(userLearned)

        // 1. High-precision contextual bigrams
        builtInNextWords[clean]?.let { results.addAll(it) }
        builtInNextWords[norm]?.let { results.addAll(it) }

        // 2. Ingested bigram map from assets
        nextWordsMap[clean]?.let { results.addAll(it) }
        nextWordsMap[norm]?.let { results.addAll(it) }

        // 3. Defaults
        if (results.isEmpty()) {
            if (isArabic) {
                results.addAll(listOf("في", "من", "على", "يا", "تمام", "جدا", "كتير", "معاك", "إن شاء الله", "الحمد لله"))
            } else {
                results.addAll(listOf("and", "it", "the", "to", "is", "for", "you", "in", "with", "that"))
            }
        }

        return results.take(limit).toList()
    }

    /**
     * Computes typo spell corrections using Damerau-Levenshtein distance, phonetic & vowel heuristics (Priority 3).
     */
    fun getTypoCorrections(word: String, isArabic: Boolean, limit: Int = 5): List<String> {
        val query = if (isArabic) normalizeArabic(word) else word.trim().lowercase()
        if (query.length < 2) return emptyList()

        // 1. Check direct override table (instant 0ms resolution)
        val override = commonTypoOverrides[query]
        if (override != null) return override.take(limit).map { matchCasing(word, it) }

        // 2. Check contractions (e.g., "lets" -> "let's", "hadnt" -> "hadn't", "its" -> "it's")
        val contraction = contractionsMap[query]
        if (contraction != null) {
            return listOf(matchCasing(word, contraction))
        }

        if (!isLoaded) return emptyList()

        val results = mutableListOf<String>()
        val seenKeys = HashSet<String>()

        // 3. Check learned user habits for words close to this query
        val topUserWords = UserHabitsManager.getTopLearnedWords(limit = 60)
        for (uWord in topUserWords) {
            val uKey = if (isArabic) normalizeArabic(uWord) else uWord.lowercase()
            if (abs(uKey.length - query.length) <= 2) {
                val dist = editDistance(query, uKey)
                if (dist in 1..2) {
                    if (seenKeys.add(uKey)) {
                        results.add(matchCasing(word, uWord))
                        if (results.size >= limit) return results
                    }
                }
            }
        }

        val letterMap = if (isArabic) arLetterMap else enLetterMap
        val frequentEntries = if (isArabic) arFrequentEntries else enFrequentEntries
        val maxAllowedDist = if (query.length <= 4) 1 else 2
        val maxLenDiff = if (query.length <= 4) 1 else 2

        val candidates = mutableListOf<Pair<String, Int>>()
        val candidateEntries = mutableListOf<Entry>()

        // A. Scan words starting with same initial character (most common typo preserve first char)
        val firstChar = query.firstOrNull() ?: ' '
        letterMap[firstChar]?.let { sameLetterList ->
            val count = min(sameLetterList.size, 1500)
            for (i in 0 until count) {
                candidateEntries.add(sameLetterList[i])
            }
        }

        // B. Scan words starting with QWERTY neighbor keys (in case the very first key was fat-fingered)
        if (!isArabic) {
            qwertyNeighbors[firstChar]?.forEach { neighborChar ->
                letterMap[neighborChar]?.let { neighborList ->
                    val count = min(neighborList.size, 150)
                    for (i in 0 until count) {
                        candidateEntries.add(neighborList[i])
                    }
                }
            }
        }

        // C. Scan top frequent words in the language
        val freqScanCount = min(frequentEntries.size, 2500)
        for (i in 0 until freqScanCount) {
            candidateEntries.add(frequentEntries[i])
        }

        val scannedInPass = HashSet<String>()
        for (entry in candidateEntries) {
            val key = entry.key
            if (!scannedInPass.add(key)) continue
            if (abs(key.length - query.length) > maxLenDiff) continue

            val dist = editDistance(query, key)
            if (dist in 1..maxAllowedDist) {
                var score = dist * 1000 + entry.rank
                // Bonus for matching starting letter
                if (key.isNotEmpty() && key[0] == firstChar) {
                    score -= 350
                }
                // Bonus if user frequently types this word
                val userFreq = UserHabitsManager.getWordFrequency(entry.word)
                if (userFreq > 0) {
                    score -= min(1500, userFreq * 350)
                }
                // Bonus for vowel swaps (e.g. habet <-> habit)
                if (isVowelSwap(query, key)) {
                    score -= 300
                }
                candidates.add(entry.word to score)
            }
        }

        candidates.sortBy { it.second }
        for (cand in candidates) {
            val cKey = if (isArabic) normalizeArabic(cand.first) else cand.first.lowercase()
            if (seenKeys.add(cKey)) {
                results.add(matchCasing(word, cand.first))
                if (results.size >= limit) break
            }
        }

        return results
    }

    private fun isVowelSwap(s1: String, s2: String): Boolean {
        if (s1.length != s2.length) return false
        val vowels = setOf('a', 'e', 'i', 'o', 'u')
        var diffCount = 0
        for (i in s1.indices) {
            if (s1[i] != s2[i]) {
                diffCount++
                if (diffCount > 1 || s1[i] !in vowels || s2[i] !in vowels) return false
            }
        }
        return diffCount == 1
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
     * Built-in fallback word completions for essential terms so suggestions work instantaneously.
     */
    private val builtInEnglishWords = listOf(
        "food", "football", "foot", "footage", "footprint", "footwear", "fool", "foolish",
        "help", "helpful", "helping", "helped", "hello", "helicopter", "helmet",
        "happy", "happiness", "happily", "happened", "happening",
        "love", "lovely", "loving", "loved", "lover",
        "sad", "sadness", "sadly",
        "test", "testing", "tested", "tests", "tester", "testimony",
        "let", "let's", "lets", "letter", "letters", "letting",
        "good", "goodbye", "goodness", "goods",
        "thank", "thanks", "thankful", "thanking", "thanked",
        "fire", "firewall", "fireman", "fireworks", "firefox",
        "magic", "magical", "magician",
        "secret", "secretary", "secrets", "secretly",
        "apple", "apply", "application", "applied", "app",
        "water", "watch", "watching", "watched",
        "people", "person", "personal", "personality",
        "time", "timer", "times", "timeline"
    )

    private val builtInArabicWords = listOf(
        "كتاب", "كتابة", "كتابي", "كتب", "كاتب", "مكتوب", "كتائب",
        "طعام", "اكل", "وجبة", "مطعم", "اطعمة",
        "كورة", "كرة", "قدم", "مباراة", "ملعب", "اهداف",
        "قدم", "اقدام", "قديم", "قدام",
        "فرح", "فرحان", "فرحانة", "افراح", "سعيد", "سعادة",
        "حزن", "حزين", "حزينة", "احزان", "زعلان",
        "حب", "حبيبي", "حبيبتي", "بحبك", "محبة",
        "شكرا", "شاكر", "مشكور", "تسلم", "يسلمو",
        "صباح", "صباح الخير", "صباح الورد", "صباح النور",
        "مساء", "مساء الخير", "مساء النور",
        "الحمد", "الحمد لله", "إن", "إن شاء الله", "تمام", "مرحبا"
    )

    /**
     * Primary Suggestion Pipeline implementing the 4 prioritized requirements:
     *
     * 1. Priority 1 (While typing word):
     *    - Word completions starting with typed prefix (e.g. "foo" -> "food", "football", "foot", "footage")
     *    - Direct contraction replacement (e.g. "lets" -> "let's", "dont" -> "don't")
     *    - Morphological extensions (e.g. "test" -> "testing", "tested", "tests")
     *    - Contextual emoji chips interleaved prominently (e.g. "foo" -> 🍕, 🦶; "sad" -> 😢, 😭)
     *
     * 2. Priority 2 (After space):
     *    - Next-word predictions for previous word (e.g. "foot " -> "ball", "prints", "step", "wear")
     *    - Contextual emojis for previous word (e.g. "foot " -> 🦶, 👣, 👟)
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

            val wordCompletions = mutableListOf<SuggestionItem>()
            val emojiCompletions = mutableListOf<String>()

            // 1. Check Contractions ("lets" -> "let's", "hadnt" -> "hadn't", "its" -> "it's", "dont" -> "don't", "im" -> "I'm")
            val contractionMatch = contractionsMap[query]
            if (contractionMatch != null) {
                val casedContraction = matchCasing(prefix, contractionMatch)
                if (seenWords.add(casedContraction.lowercase())) {
                    wordCompletions.add(SuggestionItem(text = casedContraction, isEmoji = false, isPrimary = true, isCorrection = true))
                }
            }

            // 2. Explicit direct typo override (e.g. "habet" -> "habit", "teh" -> "the", "recieve" -> "receive")
            val explicitTypo = commonTypoOverrides[query]?.firstOrNull()
            if (explicitTypo != null) {
                val casedTypo = matchCasing(prefix, explicitTypo)
                if (seenWords.add(casedTypo.lowercase())) {
                    wordCompletions.add(SuggestionItem(text = casedTypo, isEmoji = false, isPrimary = true, isCorrection = true))
                }
            }

            // 3. User's learned writing habits matching prefix (highest priority personalized suggestions)
            val learnedCompletions = UserHabitsManager.getLearnedCompletions(prefix, limit = 4)
            for (lw in learnedCompletions) {
                val lwKey = if (isArabic) normalizeArabic(lw) else lw.lowercase()
                if (seenWords.add(lwKey)) {
                    val isFirst = wordCompletions.isEmpty()
                    wordCompletions.add(SuggestionItem(text = lw, isEmoji = false, isPrimary = isFirst))
                }
            }

            // 4. Spelling correction if word is NOT known in dictionary or user habits
            val isKnown = isKnownWord(prefix, isArabic)
            if (!isKnown && prefix.length >= 2) {
                val typoCorrections = getTypoCorrections(prefix, isArabic, limit = 3)
                for (fix in typoCorrections) {
                    val fixKey = if (isArabic) normalizeArabic(fix) else fix.lowercase()
                    if (seenWords.add(fixKey)) {
                        // Place primary correction right at front
                        wordCompletions.add(0, SuggestionItem(text = fix, isEmoji = false, isPrimary = true, isCorrection = true))
                    }
                }
            }

            // 5. Dictionary prefix completions ("foo" -> "food", "football"; "edi" -> "edit", "editing")
            if (isLoaded) {
                val keys = if (isArabic) arKeys else enKeys
                val entries = if (isArabic) arEntries else enEntries

                val startIdx = binarySearchStart(keys, query)
                val candidates = mutableListOf<Entry>()
                val maxScan = 3000
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

                // Rank by exact match first, then user habits frequency, then dictionary rank, then length
                candidates.sortWith(
                    compareBy<Entry> {
                        if (it.key == query) 0 else 1
                    }.thenByDescending {
                        UserHabitsManager.getWordFrequency(it.word)
                    }.thenBy {
                        it.rank
                    }.thenBy {
                        it.word.length
                    }
                )

                for (cand in candidates) {
                    if (seenWords.add(cand.key)) {
                        val isFirstMatch = wordCompletions.isEmpty()
                        val casedWord = if (!isArabic) matchCasing(prefix, cand.word) else cand.word
                        wordCompletions.add(SuggestionItem(text = casedWord, isEmoji = false, isPrimary = isFirstMatch))
                    }
                }
            }

            // 6. Built-in core terms completion
            val builtInList = if (isArabic) builtInArabicWords else builtInEnglishWords
            for (w in builtInList) {
                val normW = if (isArabic) normalizeArabic(w) else w.lowercase()
                if (normW.startsWith(query) && seenWords.add(normW)) {
                    val isFirstMatch = wordCompletions.isEmpty()
                    val casedWord = if (!isArabic) matchCasing(prefix, w) else w
                    wordCompletions.add(SuggestionItem(text = casedWord, isEmoji = false, isPrimary = isFirstMatch))
                }
            }

            // 7. Morphological extensions ONLY if prefix is a known word AND derived form exists in dictionary
            if (isKnown) {
                val morphForms = getMorphologicalForms(prefix, isArabic)
                for (form in morphForms) {
                    if (isKnownWord(form, isArabic)) {
                        val formKey = if (isArabic) normalizeArabic(form) else form.lowercase()
                        if (seenWords.add(formKey)) {
                            wordCompletions.add(SuggestionItem(text = form, isEmoji = false))
                        }
                    }
                }
            }

            // 8. Contextual Emojis for prefix AND top completion candidate
            val prefixEmojis = getEmojisForWord(prefix, limit = 2)
            emojiCompletions.addAll(prefixEmojis)
            if (wordCompletions.isNotEmpty() && emojiCompletions.size < 2) {
                val topWord = wordCompletions.first().text
                val topEmojis = getEmojisForWord(topWord, limit = 2 - emojiCompletions.size)
                emojiCompletions.addAll(topEmojis)
            }

            // Assemble suggestions:
            // Top words first
            wordCompletions.take(3).forEach { result.add(it) }
            // Interleaved emoji chips
            emojiCompletions.take(2).forEach { em ->
                if (seenEmojis.add(em)) {
                    result.add(SuggestionItem(text = em, isEmoji = true))
                }
            }
            // Additional words
            wordCompletions.drop(3).take(limit - result.size).forEach { cand ->
                if (result.none { it.text == cand.text }) {
                    result.add(cand)
                }
            }

            // Allow preserving exactly typed raw token
            if (result.none { it.text.equals(prefix, ignoreCase = true) }) {
                result.add(SuggestionItem(text = prefix, isEmoji = false))
            }

        } else if (previousWord.isNotBlank()) {
            // Next-Word Predictions & Emojis after space (incorporating user's habits!)
            val prevClean = previousWord.trim()

            // 1. Learned personal next words
            val userNext = UserHabitsManager.getLearnedNextWords(prevClean, limit = 4)
            for (unw in userNext) {
                val unwKey = if (isArabic) normalizeArabic(unw) else unw.lowercase()
                if (seenWords.add(unwKey)) {
                    result.add(SuggestionItem(text = unw, isEmoji = false, isNextWord = true, isPrimary = result.isEmpty()))
                }
            }

            // 2. Generic bigrams from assets and dictionary
            val nextWords = getNextWords(prevClean, isArabic, limit = 8)
            for (nw in nextWords) {
                val nwKey = if (isArabic) normalizeArabic(nw) else nw.lowercase()
                if (seenWords.add(nwKey)) {
                    result.add(SuggestionItem(text = nw, isEmoji = false, isNextWord = true, isPrimary = result.isEmpty()))
                }
            }

            // 3. Emojis for previous word
            val emojis = getEmojisForWord(prevClean, limit = 2)
            for (em in emojis) {
                if (seenEmojis.add(em)) {
                    result.add(SuggestionItem(text = em, isEmoji = true))
                }
            }

        } else {
            // Default top words when input is empty: user's top words first!
            val userTop = UserHabitsManager.getTopLearnedWords(limit = 4)
            for (ut in userTop) {
                val utKey = if (isArabic) normalizeArabic(ut) else ut.lowercase()
                if (seenWords.add(utKey)) {
                    result.add(SuggestionItem(text = ut, isEmoji = false, isNextWord = true))
                }
            }

            val fallback = topWords(isArabic, limit = 4)
            for (fw in fallback) {
                val fwKey = if (isArabic) normalizeArabic(fw) else fw.lowercase()
                if (seenWords.add(fwKey)) {
                    result.add(SuggestionItem(text = fw, isEmoji = false, isNextWord = true))
                }
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
     * Records a word typed or tapped by the user for personalized ranking and habit learning.
     */
    fun recordUsedWord(word: String, prevWord: String? = null) {
        val trimmed = word.trim()
        if (trimmed.length >= 2) {
            UserHabitsManager.recordWord(trimmed, prevWord)
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
