package com.example.customkeyboard

/**
 * A starter English word list for basic autocorrect and suggestions.
 * This is NOT a neural predictive-text engine like Gboard's - it's a straightforward
 * dictionary lookup + edit-distance correction, which is realistic to run entirely
 * on-device with no ML model. It covers common everyday words; extend the list below
 * any time to teach it more vocabulary (e.g. names, slang, industry terms).
 */
object Dictionary {

    private val words = listOf(
        "a","about","above","after","again","all","also","always","am","an","and","any","are","around",
        "as","ask","at","away","back","bad","be","because","been","before","being","best","better","between",
        "big","both","but","buy","by","call","came","can","car","care","case","change","children","city",
        "come","company","could","country","course","day","did","different","do","does","done","down","during",
        "each","early","end","even","every","example","eye","fact","family","far","feel","few","find","first",
        "follow","for","found","from","get","give","go","good","got","great","group","grow","had","hand",
        "happen","has","have","he","head","hear","help","her","here","high","him","his","home","hope",
        "house","how","however","i","idea","if","important","in","into","is","it","its","job","just",
        "keep","know","large","last","late","later","learn","leave","less","let","life","like","line","list",
        "little","live","long","look","made","make","man","many","may","me","mean","might","mind","more",
        "most","move","much","must","my","name","need","never","new","next","no","not","note","now",
        "number","of","off","often","ok","okay","old","on","once","one","only","open","or","other",
        "our","out","over","own","part","people","place","play","point","possible","present","problem","program","put",
        "question","quite","rather","read","real","really","right","room","run","said","same","saw","say","school",
        "see","seem","seen","service","set","several","she","should","show","side","since","small","so","some",
        "someone","something","sometimes","soon","sound","start","state","still","story","student","study","such","sure","system",
        "take","talk","tell","than","that","the","their","them","then","there","these","they","thing","think",
        "this","those","thought","three","through","time","to","today","together","too","try","turn","two","under",
        "understand","until","up","us","use","used","very","want","was","water","way","we","week","well",
        "went","were","what","when","where","which","while","who","why","will","with","without","word","work",
        "world","would","write","year","years","yes","yet","you","your","yours",
        "please","thanks","thank","sorry","love","hello","hi","hey","bye","yeah","yep","nope","maybe",
        "tomorrow","yesterday","morning","night","afternoon","evening","weekend","phone","message",
        "email","meeting","project","team","working","free","busy","later","ready",
        "great","awesome","cool","nice","perfect","amazing","excited","happy","sad","tired","fine",
        "show","magic","trick","stage","performance","audience","card","cards","number","word","letter","secret"
    )

    private val wordSet: Set<String> = words.toHashSet()

    fun contains(word: String): Boolean = wordSet.contains(word.lowercase())

    /** Prefix-based suggestions for the word currently being typed. */
    fun suggestions(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        return words.filter { it.startsWith(lower) && it != lower }
            .sortedBy { it.length }
            .take(limit)
    }

    /**
     * Finds the closest dictionary word to a (possibly misspelled) word using edit distance.
     * Only used at word boundaries (space/punctuation/enter), and only returns a correction
     * if it's close enough to be a confident fix rather than a totally different word.
     */
    fun closestMatch(word: String): String? {
        val lower = word.lowercase()
        if (lower.length < 2) return null
        val maxDistance = if (lower.length <= 4) 1 else 2
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in words) {
            if (kotlin.math.abs(candidate.length - lower.length) > maxDistance) continue
            val distance = levenshtein(lower, candidate)
            if (distance < bestDistance) {
                bestDistance = distance
                best = candidate
            }
            if (bestDistance == 0) break
        }
        return if (best != null && bestDistance in 1..maxDistance) best else null
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[a.length][b.length]
    }
}
