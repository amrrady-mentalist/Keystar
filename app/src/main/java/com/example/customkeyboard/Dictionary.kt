package com.example.customkeyboard

/**
 * A common-word list for suggestions (and available for future autocorrect use).
 * This is NOT a neural predictive-text engine like Gboard's - it's a straightforward
 * dictionary lookup + edit-distance matcher, which is realistic to run entirely
 * on-device with no ML model. It covers roughly 1200 everyday English words across
 * everyday vocabulary, common contractions/texting shorthand, numbers, days, months,
 * colors, and food/tech terms; extend the list below any time to teach it more
 * vocabulary (e.g. names, slang, industry terms).
 */
object Dictionary {

    private val words = listOf(
        "a","about","above","after","again","all","also","always","am","an",
        "and","any","are","around","as","ask","at","away","back","bad",
        "be","because","been","before","being","best","better","between","big","both",
        "but","buy","by","call","came","can","car","care","case","change",
        "children","city","come","company","could","country","course","day","did","different",
        "do","does","done","down","during","each","early","end","even","every",
        "example","eye","fact","family","far","feel","few","find","first","follow",
        "for","found","from","get","give","go","good","got","great","group",
        "grow","had","hand","happen","has","have","he","head","hear","help",
        "her","here","high","him","his","home","hope","house","how","however",
        "i","idea","if","important","in","into","is","it","its","job",
        "just","keep","know","large","last","late","later","learn","leave","less",
        "let","life","like","line","list","little","live","long","look","made",
        "make","man","many","may","me","mean","might","mind","more","most",
        "move","much","must","my","name","need","never","new","next","no",
        "not","note","now","number","of","off","often","ok","okay","old",
        "on","once","one","only","open","or","other","our","out","over",
        "own","part","people","place","play","point","possible","present","problem","program",
        "put","question","quite","rather","read","real","really","right","room","run",
        "said","same","saw","say","school","see","seem","seen","service","set",
        "several","she","should","show","side","since","small","so","some","someone",
        "something","sometimes","soon","sound","start","state","still","story","student","study",
        "such","sure","system","take","talk","tell","than","that","the","their",
        "them","then","there","these","they","thing","think","this","those","thought",
        "three","through","time","to","today","together","too","try","turn","two",
        "under","understand","until","up","us","use","used","very","want","was",
        "water","way","we","week","well","went","were","what","when","where",
        "which","while","who","why","will","with","without","word","work","world",
        "would","write","year","years","yes","yet","you","your","yours","please",
        "thanks","thank","sorry","love","hello","hi","hey","bye","yeah","yep",
        "nope","maybe","tomorrow","yesterday","morning","night","afternoon","evening","weekend","phone",
        "message","email","meeting","project","team","working","free","busy","ready","awesome",
        "cool","nice","perfect","amazing","excited","happy","sad","tired","fine","magic",
        "trick","stage","performance","audience","card","cards","letter","secret","able","absolutely",
        "accept","account","across","act","action","activity","actually","add","address","admit",
        "adult","advice","afford","afraid","age","agency","agent","ago","agree","ahead",
        "air","airport","alive","allow","almost","alone","along","already","alright","although",
        "amount","angry","animal","answer","anymore","anyone","anything","anyway","anywhere","apartment",
        "appear","apple","apply","approach","area","argue","arm","army","arrive","art",
        "article","artist","assume","attack","attention","attorney","author","available","avoid","award",
        "aware","baby","background","bag","ball","bank","base","baseball","basic","basically",
        "basis","bathroom","battle","beat","beautiful","bed","bedroom","beer","begin","behavior",
        "behind","believe","benefit","beyond","bike","bill","billion","bit","black","blood",
        "blue","board","boat","body","book","born","boss","bother","bottle","bottom",
        "box","boy","boyfriend","brain","break","breakfast","bring","brother","budget","build",
        "building","business","button","camera","campaign","cancer","candidate","capital","career","carry",
        "catch","cause","cell","center","central","century","certain","certainly","chair","challenge",
        "chance","character","charge","chart","check","chicken","chief","child","choice","choose",
        "church","citizen","claim","class","clear","clearly","close","clothes","coach","cold",
        "collection","college","color","commercial","community","compare","computer","concern","condition","conference",
        "congress","consider","consumer","contain","continue","control","cost","couch","couple","court",
        "cover","create","crime","cross","culture","cup","current","customer","cut","dad",
        "dance","dark","data","daughter","deal","death","debate","decade","decide","decision",
        "deep","defense","degree","democrat","democratic","describe","design","despite","detail","determine",
        "develop","development","die","difference","difficult","dinner","direction","director","discover","discuss",
        "discussion","disease","doctor","dog","door","double","draw","dream","drink","drive",
        "driver","drop","drug","east","easy","eat","economic","economy","edge","education",
        "effect","effort","eight","either","election","else","employee","energy","enjoy","enough",
        "enter","entire","environment","environmental","especially","establish","event","eventually","ever","everybody",
        "everyone","everything","evidence","exactly","executive","exercise","exist","expect","experience","expert",
        "explain","face","factor","fail","fall","fast","father","fear","federal","feeling",
        "field","fight","figure","fill","film","final","finally","financial","finger","finish",
        "fire","firm","fish","floor","fly","focus","food","foot","force","foreign",
        "forget","form","former","forward","four","friend","front","fruit","full","fund",
        "future","game","garden","gas","gender","general","generation","girl","girlfriend","glass",
        "goal","government","green","ground","growth","guess","gun","guy","hair","half",
        "hall","hard","health","heart","heat","heavy","herself","history","hit","hold",
        "hospital","hotel","hour","huge","human","hundred","husband","ice","identify","image",
        "imagine","impact","improve","include","including","increase","indeed","indicate","individual","industry",
        "information","inside","instead","institution","interest","interesting","international","interview","investment","involve",
        "issue","item","itself","join","key","kid","kill","kind","kitchen","knowledge",
        "land","language","laugh","law","lawyer","lay","lead","leader","least","left",
        "leg","legal","lesson","level","lie","light","likely","listen","local","lose",
        "loss","lot","low","machine","magazine","main","maintain","major","majority","manage",
        "manager","market","marriage","material","matter","measure","media","medical","meet","member",
        "memory","mention","method","middle","military","million","minute","miss","mission","model",
        "modern","moment","money","month","mother","mouth","movement","movie","music","myself",
        "nation","national","natural","nature","near","nearly","necessary","network","news","newspaper",
        "none","north","nothing","notice","occur","offer","office","officer","official","oil",
        "onto","operation","opportunity","option","order","organization","others","outside","owner","page",
        "pain","paint","painting","paper","parent","particular","particularly","partner","party","pass",
        "past","patient","pattern","pay","peace","perhaps","period","person","personal","physical",
        "pick","picture","piece","plan","plant","player","police","policy","political","politics",
        "poor","popular","population","position","positive","power","practice","prepare","president","pressure",
        "pretty","prevent","previous","price","private","probably","process","produce","product","professional",
        "professor","property","protect","prove","provide","public","pull","purpose","push","quality",
        "quickly","race","radio","raise","range","rate","reach","reality","realize","reason",
        "receive","recent","recently","recognize","record","red","reduce","reflect","region","relate",
        "relationship","religious","remain","remember","remove","report","represent","republican","require","research",
        "resource","respond","response","responsibility","rest","result","return","reveal","rich","rise",
        "risk","road","rock","role","rule","safe","sale","save","scene","science",
        "scientist","score","sea","season","seat","second","section","security","seek","sell",
        "send","senior","sense","series","serious","serve","seven","sex","sexual","shake",
        "share","shoot","short","shot","shoulder","sign","significant","similar","simple","simply",
        "sing","single","sister","sit","site","situation","six","size","skill","skin",
        "smile","social","society","soldier","somebody","son","song","sort","source","south",
        "space","speak","special","specific","speech","spend","sport","spring","staff","stand",
        "standard","star","statement","station","stay","step","stock","stop","store","strategy",
        "street","strong","structure","stuff","style","subject","success","successful","suddenly","suffer",
        "suggest","summer","support","surface","table","task","tax","teach","teacher","technology",
        "television","tend","term","test","themselves","theory","third","though","thousand","threat",
        "throughout","throw","thus","tonight","top","total","tough","toward","town","trade",
        "traditional","training","travel","treat","treatment","tree","trial","trip","trouble","true",
        "truth","type","unit","upon","usually","value","various","victim","view","violence",
        "visit","voice","vote","wait","walk","wall","war","watch","weapon","wear",
        "weight","west","whatever","whether","white","whole","whom","whose","wide","wife",
        "win","wind","window","wish","within","woman","wonder","worker","worry","wrong",
        "yard","young","yourself","zero","five","nine","ten","eleven","twelve","thirteen",
        "fourteen","fifteen","sixteen","seventeen","eighteen","nineteen","twenty","thirty","forty","fifty",
        "sixty","seventy","eighty","ninety","monday","tuesday","wednesday","thursday","friday","saturday",
        "sunday","january","february","march","april","june","july","august","september","october",
        "november","december","orange","yellow","purple","pink","brown","gray","grey","gold",
        "silver","mom","mum","grandma","grandpa","aunt","uncle","cousin","teen","lunch",
        "snack","coffee","tea","juice","pizza","burger","fries","soup","salad","rice",
        "pasta","bread","cheese","milk","sugar","butter","laptop","tablet","internet","wifi",
        "app","software","update","download","upload","password","login","username","settings","notification",
        "battery","charger","photo","video","kinda","sorta","gonna","wanna","gotta","lemme",
        "dunno","yall","excuse","yo","sup","goodbye","cya","farewell","welcome","congrats",
        "congratulations","hate","prefer","trust","im","ive","ill","youre","youve","youll",
        "theyre","theyve","theyll","hes","shes","wont","cant","dont","doesnt","didnt",
        "isnt","arent","wasnt","werent","whats","thats","theres","heres","wheres","whos",
        "hows","lol","omg","btw","asap","fyi","imo","tbh","idk","ttyl",
        "brb"
    )
    private val wordSet: Set<String> = words.toHashSet()

    fun contains(word: String): Boolean = wordSet.contains(word.lowercase())

    /** Prefix-based suggestions for the word currently being typed. */
    fun suggestions(prefix: String, limit: Int = 8): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        return words.filter { it.startsWith(lower) && it != lower }
            .sortedBy { it.length }
            .take(limit)
    }

    /**
     * Finds the closest dictionary word to a (possibly misspelled) word using edit distance.
     * Not currently wired into autocorrect (the keyboard never rewrites what you typed), but
     * kept available for showing a "did you mean" style suggestion chip in the future.
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
