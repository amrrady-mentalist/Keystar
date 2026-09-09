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

    // Built-in next-word predictions (Bigrams and Multi-Word Trigrams / Phrases)
    private val builtInNextWords = mapOf(
        // Multi-word trigram / 4-gram heads
        "i am going" to listOf("to", "home", "there", "out", "back"),
        "am going" to listOf("to", "home", "there", "out", "back", "in"),
        "can you please" to listOf("help", "send", "check", "call", "let", "give"),
        "you please" to listOf("help", "send", "check", "call", "let", "give"),
        "how are you" to listOf("doing", "today", "feeling"),
        "are you" to listOf("ready", "sure", "okay", "going", "there", "doing", "free"),
        "good morning" to listOf("everyone", "to", "all", "how", "have"),
        "good night" to listOf("and", "sleep", "sweet", "everyone"),
        "thank you for" to listOf("the", "your", "everything", "all", "being"),
        "thank you so" to listOf("much"),
        "thank you" to listOf("so", "very", "for", "all"),
        "let me know" to listOf("if", "when", "what", "how"),
        "me know" to listOf("if", "when", "what", "how"),
        "i want to" to listOf("know", "see", "go", "be", "do", "get", "thank", "tell"),
        "want to" to listOf("know", "see", "go", "be", "do", "get", "thank"),
        "looking forward to" to listOf("seeing", "hearing", "meeting", "working"),
        "forward to" to listOf("seeing", "hearing", "meeting", "working"),
        "one of the" to listOf("best", "most", "biggest", "first", "main"),
        "of the" to listOf("best", "most", "day", "world", "year", "time"),
        "as soon as" to listOf("possible", "you", "we", "I"),
        "have a good" to listOf("day", "time", "night", "weekend", "one"),
        "a good" to listOf("day", "time", "idea", "friend", "job", "one"),
        "nice to meet" to listOf("you"),
        "to meet" to listOf("you"),
        "what do you" to listOf("think", "mean", "want", "do", "say"),
        "do you think" to listOf("that", "it", "so", "we", "you"),
        "do you" to listOf("know", "want", "have", "think", "need", "like", "see"),
        "i will be" to listOf("there", "back", "happy", "able"),
        "will be" to listOf("there", "back", "happy", "able", "great"),
        "it is a" to listOf("good", "great", "very", "nice", "pleasure"),
        "is a" to listOf("good", "great", "very", "little", "big", "new"),
        "would like to" to listOf("thank", "know", "see", "say", "invite"),
        "i would like" to listOf("to", "a"),
        "give me a" to listOf("call", "minute", "hand", "chance", "break"),

        // Arabic multi-word trigrams
        "السلام عليكم" to listOf("ورحمة", "ورحمة الله", "يا"),
        "ورحمة الله" to listOf("وبركاته"),
        "إن شاء الله" to listOf("خير", "تمام", "تكون", "أشوفك", "قريبا"),
        "ان شاء الله" to listOf("خير", "تمام", "تكون", "أشوفك", "قريبا"),
        "شاء الله" to listOf("خير", "تمام", "تكون", "أشوفك"),
        "الحمد لله" to listOf("على", "دائما", "كثيرا", "رب", "تمام"),
        "صباح الخير" to listOf("يا", "عليك", "حبيبي", "يا غالي"),
        "مساء الخير" to listOf("يا", "عليك", "حبيبي"),
        "كل سنة وانت" to listOf("طيب", "بخير", "سالم"),
        "سنة وانت" to listOf("طيب", "بخير"),
        "كل عام وانتم" to listOf("بخير", "بصحة"),
        "عام وانتم" to listOf("بخير"),
        "جزاك الله" to listOf("خيرا", "كل", "ألف"),
        "شكرا جزيلا" to listOf("لك", "يا", "على", "أخي"),
        "من فضلك" to listOf("ممكن", "عايز", "لو", "أحتاج"),
        "عامل ايه" to listOf("يا", "النهاردة", "في", "أخبارك"),
        "ألف مبروك" to listOf("يا", "حبيبي", "عليك"),
        "وحشتني جدا" to listOf("يا", "والله"),
        "بحبك جدا" to listOf("يا", "وربنا"),

        // Standard Bigrams
        "foot" to listOf("ball", "prints", "step", "wear", "and", "it", "traffic", "note"),
        "feet" to listOf("tall", "away", "above", "below", "long", "wide", "and", "off", "high"),
        "food" to listOf("and", "is", "delivery", "store", "court", "truck", "safety"),
        "football" to listOf("game", "match", "player", "club", "team", "season"),
        "test" to listOf("results", "flight", "case", "drive", "tube", "run", "it", "out"),
        "testing" to listOf("and", "the", "is", "phase", "process", "framework", "new", "methods"),
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
        "please" to listOf("help", "send", "check", "call", "let", "give", "tell"),

        // Arabic Bigrams
        "السلام" to listOf("عليكم", "ورحمة", "والأمان"),
        "صباح" to listOf("الخير", "الورد", "النور", "الفل", "الجمال"),
        "مساء" to listOf("الخير", "النور", "الورد", "الفل", "الجمال"),
        "شكرا" to listOf("جزيلا", "لك", "يا", "جدا", "كتير", "حبيبي"),
        "الحمد" to listOf("لله", "والشكر لله"),
        "ان" to listOf("شاء الله", "كنت", "لم", "كان"),
        "إن" to listOf("شاء الله", "كنت", "لم", "كان"),
        "عامل" to listOf("ايه", "اي", "تمام", "شغل"),
        "ازيك" to listOf("يا", "عامل ايه", "اخبارك"),
        "تمام" to listOf("جدا", "الحمد لله", "يا باشا", "كده"),
        "انا" to listOf("تمام", "بخير", "في", "رايح", "بحبك", "عايز", "مش"),
        "انت" to listOf("فين", "عامل ايه", "وحشني", "صح", "جميل"),
        "هو" to listOf("فين", "كان", "قال", "رايح"),
        "هي" to listOf("فين", "كانت", "قالت", "رايحة"),
        "كل" to listOf("سنة", "عام", "يوم", "حاجة", "مرة", "واحد"),
        "في" to listOf("البيت", "الشغل", "الطريق", "مصر", "كل مكان"),
        "مع" to listOf("السلامة", "ألف سلامة", "بعض", "حبيبي")
    )

    /**
     * Keyboard-aware distance & proximity evaluator (Layer 2 - Real Spelling Correction).
     * Calculates Euclidean distance on physical keyboard layouts so fat-fingered neighbors
     * (e.g., 'i' and 'o' in "giod" -> "good") score drastically higher than unrelated letters.
     */
    object KeyboardDistance {
        private val qwertyCoords = mapOf(
            'q' to Pair(0f, 0f), 'w' to Pair(1f, 0f), 'e' to Pair(2f, 0f), 'r' to Pair(3f, 0f), 't' to Pair(4f, 0f),
            'y' to Pair(5f, 0f), 'u' to Pair(6f, 0f), 'i' to Pair(7f, 0f), 'o' to Pair(8f, 0f), 'p' to Pair(9f, 0f),
            'a' to Pair(0.5f, 1f), 's' to Pair(1.5f, 1f), 'd' to Pair(2.5f, 1f), 'f' to Pair(3.5f, 1f), 'g' to Pair(4.5f, 1f),
            'h' to Pair(5.5f, 1f), 'j' to Pair(6.5f, 1f), 'k' to Pair(7.5f, 1f), 'l' to Pair(8.5f, 1f),
            'z' to Pair(1f, 2f), 'x' to Pair(2f, 2f), 'c' to Pair(3f, 2f), 'v' to Pair(4f, 2f), 'b' to Pair(5f, 2f),
            'n' to Pair(6f, 2f), 'm' to Pair(7f, 2f)
        )

        private val arabicCoords = mapOf(
            'ض' to Pair(0f, 0f), 'ص' to Pair(1f, 0f), 'ث' to Pair(2f, 0f), 'ق' to Pair(3f, 0f), 'ف' to Pair(4f, 0f),
            'غ' to Pair(5f, 0f), 'ع' to Pair(6f, 0f), 'ه' to Pair(7f, 0f), 'خ' to Pair(8f, 0f), 'ح' to Pair(9f, 0f), 'ج' to Pair(10f, 0f), 'د' to Pair(11f, 0f),
            'ش' to Pair(0.5f, 1f), 'س' to Pair(1.5f, 1f), 'ي' to Pair(2.5f, 1f), 'ب' to Pair(3.5f, 1f), 'ل' to Pair(4.5f, 1f),
            'ا' to Pair(5.5f, 1f), 'ت' to Pair(6.5f, 1f), 'ن' to Pair(7.5f, 1f), 'م' to Pair(8.5f, 1f), 'ك' to Pair(9.5f, 1f), 'ط' to Pair(10.5f, 1f),
            'ئ' to Pair(1f, 2f), 'ء' to Pair(2f, 2f), 'ؤ' to Pair(3f, 2f), 'ر' to Pair(4f, 2f),
            'ى' to Pair(5.5f, 2f), 'ة' to Pair(6.5f, 2f), 'و' to Pair(7.5f, 2f), 'ز' to Pair(8.5f, 2f), 'ظ' to Pair(9.5f, 2f)
        )

        private val vowels = setOf('a', 'e', 'i', 'o', 'u')

        fun substitutionCost(c1: Char, c2: Char, isArabic: Boolean): Float {
            if (c1 == c2) return 0.0f

            if (isArabic) {
                if ((c1 in "أإآا" && c2 in "أإآا") || (c1 in "يى" && c2 in "يى") || (c1 in "ةه" && c2 in "ةه")) {
                    return 0.15f
                }
                val p1 = arabicCoords[c1]
                val p2 = arabicCoords[c2]
                if (p1 != null && p2 != null) {
                    val dx = p1.first - p2.first
                    val dy = p1.second - p2.second
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist <= 1.25f) return 0.5f
                    if (dist <= 2.2f) return 0.85f
                }
                return 1.35f
            }

            // English QWERTY
            val p1 = qwertyCoords[c1]
            val p2 = qwertyCoords[c2]
            if (p1 != null && p2 != null) {
                val dx = p1.first - p2.first
                val dy = p1.second - p2.second
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                if (dist <= 1.25f) return 0.45f // Direct adjacent neighbor key (e.g. i and o in giod -> good)
                if (dist <= 2.2f) return 0.85f // Near neighbor key
            }

            // Vowel swap bonus (e.g. definately <-> definitely)
            if (c1 in vowels && c2 in vowels) return 0.60f

            return 1.35f
        }

        /**
         * Damerau-Levenshtein distance with keyboard proximity weights and adjacent character transposition.
         */
        fun distance(s1: String, s2: String, isArabic: Boolean): Float {
            val l1 = s1.length
            val l2 = s2.length
            if (l1 == 0) return l2 * 0.85f
            if (l2 == 0) return l1 * 0.85f

            val dp = Array(l1 + 1) { FloatArray(l2 + 1) }
            for (i in 0..l1) dp[i][0] = i * 0.85f
            for (j in 0..l2) dp[0][j] = j * 0.85f

            for (i in 1..l1) {
                val c1 = s1[i - 1]
                for (j in 1..l2) {
                    val c2 = s2[j - 1]
                    val subCost = substitutionCost(c1, c2, isArabic)
                    var minCost = minOf(
                        dp[i - 1][j] + 0.85f, // deletion
                        dp[i][j - 1] + 0.85f, // insertion
                        dp[i - 1][j - 1] + subCost // match or substitution
                    )

                    // Transposition (e.g. becuase -> because, recieve -> receive, teh -> the)
                    if (i > 1 && j > 1 && s1[i - 1] == s2[j - 2] && s1[i - 2] == s2[j - 1]) {
                        minCost = minOf(minCost, dp[i - 2][j - 2] + 0.65f)
                    }

                    dp[i][j] = minCost
                }
            }
            return dp[l1][l2]
        }
    }

    /**
     * Next-word prediction utilizing the last 2-3 words (Layer 3 - Much Smarter Next-Word Prediction).
     */
    fun getNextWords(previousWords: List<String>, isArabic: Boolean, limit: Int = 8): List<String> {
        val words = previousWords.map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val results = LinkedHashSet<String>()

        val p1 = words.getOrNull(0)?.lowercase() ?: ""
        val p2 = words.getOrNull(1)?.lowercase() ?: ""
        val p3 = words.getOrNull(2)?.lowercase() ?: ""

        // 1. Check User Habits learned trigrams and bigrams first (Layer 4)
        val userLearned = UserHabitsManager.getLearnedNextWords(p1, p2, limit = 6)
        results.addAll(userLearned)

        // 2. Check 3-word phrase context (e.g. "i am going" -> "to", "home", "there")
        if (p3.isNotEmpty() && p2.isNotEmpty() && p1.isNotEmpty()) {
            val phrase3 = "$p3 $p2 $p1"
            val norm3 = if (isArabic) normalizeArabic(phrase3) else phrase3
            builtInNextWords[phrase3]?.let { results.addAll(it) }
            builtInNextWords[norm3]?.let { results.addAll(it) }
            nextWordsMap[phrase3]?.let { results.addAll(it) }
            nextWordsMap[norm3]?.let { results.addAll(it) }
        }

        // 3. Check 2-word phrase context (e.g. "am going" -> "to", "good morning" -> "everyone")
        if (p2.isNotEmpty() && p1.isNotEmpty()) {
            val phrase2 = "$p2 $p1"
            val norm2 = if (isArabic) normalizeArabic(phrase2) else phrase2
            builtInNextWords[phrase2]?.let { results.addAll(it) }
            builtInNextWords[norm2]?.let { results.addAll(it) }
            nextWordsMap[phrase2]?.let { results.addAll(it) }
            nextWordsMap[norm2]?.let { results.addAll(it) }
        }

        // 4. Single preceding word bigrams
        if (p1.isNotEmpty()) {
            val norm1 = if (isArabic) normalizeArabic(p1) else p1
            builtInNextWords[p1]?.let { results.addAll(it) }
            builtInNextWords[norm1]?.let { results.addAll(it) }
            nextWordsMap[p1]?.let { results.addAll(it) }
            nextWordsMap[norm1]?.let { results.addAll(it) }
        }

        // Fallback defaults
        if (results.isEmpty()) {
            if (isArabic) {
                results.addAll(listOf("في", "من", "على", "يا", "تمام", "جدا", "كتير", "معاك", "إن شاء الله", "الحمد لله"))
            } else {
                results.addAll(listOf("to", "the", "and", "it", "is", "for", "you", "in", "with", "that"))
            }
        }

        return results.take(limit).toList()
    }

    fun getNextWords(previousWord: String, isArabic: Boolean, limit: Int = 8): List<String> {
        val list = if (previousWord.isNotBlank()) listOf(previousWord.trim()) else emptyList()
        return getNextWords(list, isArabic, limit)
    }

    /**
     * Real spelling correction with keyboard-aware distance (Layer 2 - Real Spelling Correction).
     * Evaluates candidates using KeyboardDistance so fat-finger substitutions (e.g. "giod" -> "good"),
     * transpositions ("becuase" -> "because", "recieve" -> "receive", "teh" -> "the"),
     * vowel swaps ("definately" -> "definitely"), and missing characters ("goverment" -> "government")
     * are accurately corrected even when not explicitly listed in overrides.
     */
    fun getTypoCorrections(word: String, isArabic: Boolean, limit: Int = 5): List<String> {
        val query = if (isArabic) normalizeArabic(word) else word.trim().lowercase()
        if (query.length < 2) return emptyList()

        // 1. Instant check for common manual overrides / contractions
        val override = commonTypoOverrides[query]
        if (override != null) return override.take(limit).map { matchCasing(word, it) }

        val contraction = contractionsMap[query]
        if (contraction != null) {
            return listOf(matchCasing(word, contraction))
        }

        if (!isLoaded) return emptyList()

        val candidateEntries = mutableListOf<Entry>()
        val seenCandidateKeys = HashSet<String>()

        // 2. Scan user learned words (Layer 4 - Personal Learning)
        val topUserWords = UserHabitsManager.getTopLearnedWords(limit = 100)
        for (uWord in topUserWords) {
            val uKey = if (isArabic) normalizeArabic(uWord) else uWord.lowercase()
            if (seenCandidateKeys.add(uKey)) {
                candidateEntries.add(Entry(uKey, uWord, rank = 100))
            }
        }

        val letterMap = if (isArabic) arLetterMap else enLetterMap
        val frequentEntries = if (isArabic) arFrequentEntries else enFrequentEntries
        val firstChar = query.firstOrNull() ?: ' '

        // A. Candidates starting with same initial char
        letterMap[firstChar]?.let { sameLetterList ->
            val count = min(sameLetterList.size, 1500)
            for (i in 0 until count) {
                val entry = sameLetterList[i]
                if (seenCandidateKeys.add(entry.key)) {
                    candidateEntries.add(entry)
                }
            }
        }

        // B. Candidates starting with adjacent keyboard neighbor keys (in case 1st char was fat-fingered)
        if (!isArabic) {
            qwertyNeighbors[firstChar]?.forEach { neighborChar ->
                letterMap[neighborChar]?.let { neighborList ->
                    val count = min(neighborList.size, 200)
                    for (i in 0 until count) {
                        val entry = neighborList[i]
                        if (seenCandidateKeys.add(entry.key)) {
                            candidateEntries.add(entry)
                        }
                    }
                }
            }

            // Also check transposition of first two characters (e.g. "teh" -> "the", "wodr" -> "word")
            if (query.length >= 2) {
                val secondChar = query[1]
                letterMap[secondChar]?.let { secondList ->
                    val count = min(secondList.size, 200)
                    for (i in 0 until count) {
                        val entry = secondList[i]
                        if (seenCandidateKeys.add(entry.key)) {
                            candidateEntries.add(entry)
                        }
                    }
                }
            }
        }

        // C. Candidates from top high-frequency dictionary words
        val freqScanCount = min(frequentEntries.size, 2500)
        for (i in 0 until freqScanCount) {
            val entry = frequentEntries[i]
            if (seenCandidateKeys.add(entry.key)) {
                candidateEntries.add(entry)
            }
        }

        val maxAllowedDist = if (query.length <= 4) 1.55f else 2.25f
        val maxLenDiff = if (query.length <= 4) 1 else 2

        val scoredCandidates = mutableListOf<Pair<String, Float>>()

        for (entry in candidateEntries) {
            val key = entry.key
            if (abs(key.length - query.length) > maxLenDiff) continue

            val dist = KeyboardDistance.distance(query, key, isArabic)
            if (dist <= maxAllowedDist) {
                // Scoring formula: distance penalty + dictionary frequency + user habit bonus + start letter bonus
                var score = - (dist * 2200f) + maxOf(0f, (7000f - entry.rank) * 1.4f)

                if (key.isNotEmpty() && key[0] == firstChar) {
                    score += 400f
                }

                val userFreq = UserHabitsManager.getWordFrequency(entry.word)
                if (userFreq > 0) {
                    score += (userFreq * 900f + 500f)
                }

                scoredCandidates.add(entry.word to score)
            }
        }

        scoredCandidates.sortByDescending { it.second }

        val results = mutableListOf<String>()
        val seenResultKeys = HashSet<String>()
        for (cand in scoredCandidates) {
            val cKey = if (isArabic) normalizeArabic(cand.first) else cand.first.lowercase()
            if (seenResultKeys.add(cKey)) {
                results.add(matchCasing(word, cand.first))
                if (results.size >= limit) break
            }
        }

        return results
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
        "government", "govern", "governing", "governor", "because", "receive", "receiving", "received", "definitely",
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
     * Primary Suggestion Pipeline implementing the 4 layers:
     *
     * Layer 1: Much better word suggestions
     *   Candidates scored with: frequency + prefix match + user history + keyboard proximity + edit distance
     *   Typing "gover" prioritizes: "government", "govern", "governing"
     *
     * Layer 2: Real spelling correction
     *   Keyboard-aware distance fuzzy search: "giod" -> "good", "becuase" -> "because",
     *   "recieve" -> "receive", "goverment" -> "government", "definately" -> "definitely", "teh" -> "the"
     *
     * Layer 3: Much smarter next-word prediction
     *   Uses last 2-3 words when available (e.g. "I am going" -> "to", "home", "there")
     *
     * Layer 4: Personal learning
     *   UserHabitsManager dynamic learning of unigrams, bigrams, and trigrams.
     */
    fun getContextualSuggestions(
        currentWord: String,
        previousWords: List<String>,
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

            // 1. Contractions check (e.g. "lets" -> "let's", "dont" -> "don't", "im" -> "I'm")
            val contractionMatch = contractionsMap[query]
            if (contractionMatch != null) {
                val cased = matchCasing(prefix, contractionMatch)
                if (seenWords.add(cased.lowercase())) {
                    wordCompletions.add(SuggestionItem(text = cased, isEmoji = false, isPrimary = true, isCorrection = true))
                }
            }

            // 2. Explicit direct typo override
            val explicitTypo = commonTypoOverrides[query]?.firstOrNull()
            if (explicitTypo != null) {
                val cased = matchCasing(prefix, explicitTypo)
                if (seenWords.add(cased.lowercase())) {
                    wordCompletions.add(SuggestionItem(text = cased, isEmoji = false, isPrimary = true, isCorrection = true))
                }
            }

            // 3. Layer 2: Spelling correction if word is NOT known in dictionary or user habits
            val isKnown = isKnownWord(prefix, isArabic)
            if (!isKnown && prefix.length >= 2) {
                val typoCorrections = getTypoCorrections(prefix, isArabic, limit = 3)
                for (fix in typoCorrections) {
                    val fixKey = if (isArabic) normalizeArabic(fix) else fix.lowercase()
                    if (seenWords.add(fixKey)) {
                        wordCompletions.add(SuggestionItem(text = fix, isEmoji = false, isPrimary = true, isCorrection = true))
                    }
                }
            }

            // 4. Candidate gathering for completions
            class Candidate(val word: String, val key: String, val rank: Int)
            val candidatePool = mutableListOf<Candidate>()
            val poolKeys = HashSet<String>()

            // A. User habits matching prefix (Layer 4 - Personal Learning)
            val learnedWords = UserHabitsManager.getLearnedCompletions(prefix, limit = 6)
            for (lw in learnedWords) {
                val lKey = if (isArabic) normalizeArabic(lw) else lw.lowercase()
                if (poolKeys.add(lKey)) {
                    candidatePool.add(Candidate(lw, lKey, rank = 10))
                }
            }

            // B. Dictionary prefix search (binary search start)
            if (isLoaded) {
                val keys = if (isArabic) arKeys else enKeys
                val entries = if (isArabic) arEntries else enEntries
                val startIdx = binarySearchStart(keys, query)
                val maxScan = 3500
                var scanned = 0
                var idx = startIdx

                while (idx < keys.size && scanned < maxScan) {
                    val k = keys[idx]
                    if (!k.startsWith(query)) break
                    val entry = entries[idx]
                    if (poolKeys.add(entry.key)) {
                        candidatePool.add(Candidate(entry.word, entry.key, entry.rank))
                    }
                    idx++
                    scanned++
                }
            }

            // C. Built-in core words
            val builtInList = if (isArabic) builtInArabicWords else builtInEnglishWords
            for (bw in builtInList) {
                val bKey = if (isArabic) normalizeArabic(bw) else bw.lowercase()
                if (bKey.startsWith(query) && poolKeys.add(bKey)) {
                    candidatePool.add(Candidate(bw, bKey, rank = 150))
                }
            }

            // 5. Layer 1: Unified scoring formula:
            // score = frequency + prefix match + user history + context (bigram / trigram) - length penalty
            val scoredList = mutableListOf<Pair<Candidate, Float>>()
            val p1 = previousWords.getOrNull(0)?.trim()?.lowercase() ?: ""
            val p2 = previousWords.getOrNull(1)?.trim()?.lowercase() ?: ""

            for (cand in candidatePool) {
                val candKey = cand.key
                val candWord = cand.word
                val rank = cand.rank

                // Frequency score (0 to ~10500)
                val freqScore = maxOf(0f, (7000f - rank) * 1.5f)

                // Prefix match score
                var prefixScore = 3000f
                if (candKey == query) {
                    prefixScore += 5000f // Exact match gets huge boost
                } else {
                    val lenDiff = candKey.length - query.length
                    prefixScore -= (lenDiff * 32f) // Mild penalty for longer extensions
                }

                // User habits score (Layer 4)
                val userFreq = UserHabitsManager.getWordFrequency(candWord)
                val userScore = userFreq * 850f

                // Context score (Layer 3 - Bigram & Trigram transitions)
                var contextScore = 0f
                if (p1.isNotEmpty()) {
                    val biCount = UserHabitsManager.getBigramScore(p1, candWord)
                    if (biCount > 0) contextScore += (biCount * 900f + 1500f)

                    if (p2.isNotEmpty()) {
                        val triCount = UserHabitsManager.getTrigramScore(p2, p1, candWord)
                        if (triCount > 0) contextScore += (triCount * 1400f + 3000f)
                    }

                    if (nextWordsMap[p1]?.contains(candKey) == true || builtInNextWords[p1]?.contains(candKey) == true) {
                        contextScore += 900f
                    }
                }

                val totalScore = prefixScore + freqScore + userScore + contextScore
                scoredList.add(cand to totalScore)
            }

            scoredList.sortByDescending { it.second }

            for ((cand, _) in scoredList) {
                if (seenWords.add(cand.key)) {
                    val isFirstMatch = wordCompletions.isEmpty()
                    val cased = if (!isArabic) matchCasing(prefix, cand.word) else cand.word
                    wordCompletions.add(SuggestionItem(text = cased, isEmoji = false, isPrimary = isFirstMatch))
                }
            }

            // 6. Morphological extensions if prefix is a known word
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

            // 7. Contextual Emojis for prefix and top candidates
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

            // Preserve typed raw token
            if (result.none { it.text.equals(prefix, ignoreCase = true) }) {
                result.add(SuggestionItem(text = prefix, isEmoji = false))
            }

        } else if (previousWords.isNotEmpty()) {
            // Layer 3: Much smarter next-word prediction using 2-3 previous words
            val nextWords = getNextWords(previousWords, isArabic, limit = 10)
            for (nw in nextWords) {
                val nwKey = if (isArabic) normalizeArabic(nw) else nw.lowercase()
                if (seenWords.add(nwKey)) {
                    result.add(SuggestionItem(text = nw, isEmoji = false, isNextWord = true, isPrimary = result.isEmpty()))
                }
            }

            // Emojis for immediate preceding phrase or word
            val p1 = previousWords.getOrNull(0) ?: ""
            val p2 = previousWords.getOrNull(1) ?: ""
            val phrase2 = if (p2.isNotEmpty()) "$p2 $p1" else ""
            val emojis = if (phrase2.isNotEmpty()) {
                val pEmojis = getEmojisForWord(phrase2, limit = 2)
                if (pEmojis.isNotEmpty()) pEmojis else getEmojisForWord(p1, limit = 2)
            } else {
                getEmojisForWord(p1, limit = 2)
            }
            for (em in emojis) {
                if (seenEmojis.add(em)) {
                    result.add(SuggestionItem(text = em, isEmoji = true))
                }
            }

        } else {
            // Default top words when input is completely empty: user's top words first
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

    fun getContextualSuggestions(
        currentWord: String,
        previousWord: String,
        isArabic: Boolean,
        limit: Int = 16
    ): List<SuggestionItem> {
        val list = if (previousWord.isNotBlank()) listOf(previousWord.trim()) else emptyList()
        return getContextualSuggestions(currentWord, list, isArabic, limit)
    }

    /**
     * Classic suggestions helper.
     */
    fun suggestions(prefix: String, isArabic: Boolean, limit: Int = 10): List<String> {
        val list = getContextualSuggestions(prefix, emptyList(), isArabic, limit)
        return list.filter { !it.isEmoji }.map { it.text }
    }

    /**
     * Records a word typed or chosen by the user for personalized learning (Layer 4).
     */
    fun recordUsedWord(word: String, prevWord: String? = null, prevPrevWord: String? = null) {
        val trimmed = word.trim()
        if (trimmed.length >= 2) {
            UserHabitsManager.recordWord(trimmed, prevWord, prevPrevWord)
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
