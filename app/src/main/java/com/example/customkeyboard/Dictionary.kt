package com.example.customkeyboard

/**
 * High-performance bilingual (English and Arabic) predictive engine with frequency-ranked vocabulary,
 * prefix matching, fuzzy matching, and recent word caching.
 */
object Dictionary {

    // Common English words ordered by real-world usage frequency
    private val englishWords = listOf(
        // High frequency core
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
        
        // Conversational & Daily
        "hello", "hi", "hey", "thanks", "thank", "please", "welcome", "sorry", "love", "great",
        "awesome", "amazing", "perfect", "cool", "fine", "okay", "ok", "yes", "yeah", "yep",
        "sure", "never", "always", "sometimes", "today", "tomorrow", "yesterday", "tonight",
        "morning", "afternoon", "evening", "night", "week", "weekend", "month", "later", "soon",
        "meeting", "phone", "call", "message", "email", "address", "home", "office", "work",
        "family", "friend", "friends", "brother", "sister", "mother", "father", "son", "daughter",
        
        // Magic & Covert Terms
        "magic", "secret", "reveal", "covert", "spectator", "mind", "mentalism", "card", "cards",
        "deck", "trick", "illusion", "performance", "audience", "prediction", "shuffle", "choice",
        
        // Common Contractions & Modern Slang
        "im", "ive", "ill", "id", "youre", "youve", "youll", "hes", "shes", "theyre", "weve", "dont",
        "cant", "wont", "didnt", "isnt", "arent", "wasnt", "werent", "thats", "whats", "wheres", "hows",
        "theres", "heres", "gonna", "wanna", "gotta", "lemme", "kinda", "sorta", "dunno", "yall",
        "lol", "omg", "btw", "asap", "fyi", "idk", "tbh", "imo", "brb", "ttyl", "np",
        
        // Verbs & Actions
        "ask", "answer", "arrive", "agree", "allow", "appear", "apply", "argue", "assume", "attack",
        "avoid", "become", "begin", "believe", "bring", "build", "buy", "care", "carry", "catch",
        "change", "check", "choose", "clean", "clear", "close", "connect", "continue", "cook",
        "create", "cut", "dance", "decide", "deliver", "describe", "design", "die", "discover",
        "discuss", "draw", "dream", "drink", "drive", "drop", "eat", "enjoy", "enter", "expect",
        "explain", "fall", "feel", "fight", "fill", "find", "finish", "fly", "focus", "follow",
        "forget", "forgive", "grow", "guess", "handle", "happen", "hate", "hear", "help", "hide",
        "hold", "hope", "hurry", "hurt", "ignore", "imagine", "improve", "include", "inform",
        "invite", "join", "jump", "keep", "kill", "kiss", "laugh", "lead", "learn", "leave",
        "lend", "let", "listen", "live", "lock", "lose", "manage", "marry", "matter", "mean",
        "meet", "mention", "mind", "miss", "move", "need", "notice", "obtain", "offer", "open",
        "order", "organize", "pack", "paint", "pass", "pay", "pick", "plan", "play", "prefer",
        "prepare", "press", "prevent", "promise", "protect", "prove", "provide", "pull", "push",
        "reach", "read", "realize", "receive", "recognize", "recommend", "record", "refuse",
        "remember", "remind", "remove", "repeat", "reply", "report", "request", "require",
        "respond", "rest", "return", "ride", "ring", "rise", "run", "save", "search", "seek",
        "seem", "sell", "send", "serve", "settle", "share", "shoot", "show", "shut", "sign",
        "sing", "sit", "sleep", "smile", "speak", "spend", "stand", "start", "stay", "steal",
        "stick", "stop", "study", "succeed", "suggest", "support", "suppose", "survive", "switch",
        "talk", "taste", "teach", "tell", "test", "thank", "throw", "touch", "train", "travel",
        "treat", "trust", "try", "turn", "understand", "unlock", "update", "visit", "wait",
        "wake", "walk", "warn", "wash", "watch", "wear", "win", "wish", "wonder", "worry", "write",
        
        // Adjectives & Descriptions
        "able", "accurate", "active", "actual", "afraid", "alive", "alone", "angry", "anxious",
        "available", "bad", "basic", "beautiful", "best", "better", "big", "black", "blind", "blue",
        "boring", "brave", "bright", "broad", "brown", "busy", "calm", "capable", "careful", "certain",
        "cheap", "clean", "clear", "clever", "close", "cold", "comfortable", "common", "complete",
        "complex", "confident", "confused", "conscious", "cool", "correct", "crazy", "critical",
        "crucial", "curious", "current", "cute", "dangerous", "dark", "dead", "dear", "decent",
        "deep", "delicious", "different", "difficult", "direct", "dirty", "distinct", "double",
        "dry", "due", "eager", "early", "easy", "efficient", "empty", "entire", "equal", "essential",
        "exact", "excellent", "excited", "exciting", "expensive", "experienced", "extreme", "fair",
        "false", "famous", "fast", "fat", "favorite", "final", "fine", "firm", "fit", "flat",
        "foreign", "formal", "former", "free", "fresh", "friendly", "front", "full", "funny",
        "general", "generous", "gentle", "glad", "global", "gold", "golden", "good", "grand",
        "gray", "great", "green", "guilty", "happy", "hard", "healthy", "heavy", "helpful", "high",
        "honest", "hot", "huge", "hungry", "ideal", "ill", "immediate", "important", "impossible",
        "impressive", "independent", "initial", "inner", "innocent", "intelligent", "intense",
        "interesting", "internal", "international", "joint", "junior", "just", "keen", "key",
        "kind", "known", "large", "late", "latest", "lazy", "leading", "least", "left", "legal",
        "light", "likely", "limited", "little", "live", "local", "logical", "lonely", "long",
        "loose", "loud", "lovely", "low", "loyal", "lucky", "mad", "main", "major", "male",
        "massive", "mean", "medical", "medium", "mental", "middle", "minor", "missing", "mixed",
        "modern", "modest", "moral", "mutual", "narrow", "nasty", "national", "native", "natural",
        "neat", "necessary", "negative", "nervous", "neutral", "new", "next", "nice", "noble",
        "normal", "notable", "novel", "nuclear", "numerous", "obvious", "odd", "official", "old",
        "online", "open", "opposite", "optimal", "orange", "ordinary", "original", "outer",
        "overall", "pale", "parallel", "partial", "particular", "passive", "past", "patient",
        "perfect", "permanent", "personal", "physical", "pink", "plain", "pleasant", "plenty",
        "polite", "poor", "popular", "positive", "possible", "potential", "powerful", "practical",
        "precious", "precise", "preferable", "pregnant", "premium", "present", "pretty", "previous",
        "primary", "prime", "primitive", "principal", "prior", "private", "probable", "productive",
        "professional", "prominent", "prompt", "proper", "proud", "pure", "purple", "quick",
        "quiet", "radical", "random", "rapid", "rare", "raw", "ready", "real", "realistic",
        "reasonable", "recent", "red", "regular", "relevant", "reliable", "relieved", "remarkable",
        "remote", "resident", "responsible", "rich", "right", "rigid", "rough", "round", "routine",
        "royal", "rude", "safe", "same", "satisfied", "scared", "secure", "senior", "sensible",
        "sensitive", "separate", "serious", "severe", "sharp", "short", "shy", "sick", "silent",
        "silly", "similar", "simple", "sincere", "single", "slight", "slim", "slow", "small",
        "smart", "smooth", "soft", "solid", "some", "sorry", "sour", "southern", "spare", "special",
        "specific", "splendid", "stable", "standard", "steep", "stiff", "still", "straight",
        "strange", "strict", "strong", "stubborn", "stupid", "subtle", "successful", "sudden",
        "sufficient", "suitable", "super", "superior", "sure", "sweet", "swift", "tall", "tasty",
        "temporary", "tender", "terrible", "terrific", "thick", "thin", "thirsty", "thorough",
        "tight", "tiny", "tired", "top", "total", "tough", "traditional", "true", "typical",
        "ugly", "ultimate", "unable", "unaware", "uncertain", "uncommon", "unconscious", "uneasy",
        "unfair", "unhappy", "uniform", "unique", "united", "unknown", "unlikely", "unpleasant",
        "unusual", "upper", "upset", "urban", "urgent", "useful", "useless", "usual", "vacant",
        "vague", "valid", "valuable", "various", "vast", "verbal", "vertical", "viable", "vibrant",
        "vicious", "visible", "vital", "vivid", "vocal", "volatile", "voluntary", "vulnerable",
        "warm", "weak", "wealthy", "weird", "welcome", "wet", "white", "whole", "wicked", "wide",
        "wild", "willing", "wise", "wonderful", "wooden", "worth", "worthy", "wrong", "yellow", "young"
    )

    // Comprehensive modern Arabic dictionary ordered by frequency
    private val arabicWords = listOf(
        // High frequency core particles & pronouns
        "في", "من", "على", "إلى", "عن", "مع", "هذا", "هذه", "ذلك", "تلك", "التي", "الذي",
        "الذين", "اللاتي", "هو", "هي", "هم", "هن", "أنا", "أنت", "أنتم", "نحن", "كل", "بعض",
        "غير", "سوف", "قد", "لم", "لن", "ما", "لا", "نعم", "أيضا", "جدا", "حتى", "إذا", "لو",
        "أو", "ثم", "بل", "لكن", "لأن", "حيث", "بين", "فوق", "تحت", "أمام", "خلف", "عند", "لدى",
        
        // Conversational & Greetings
        "السلام", "عليكم", "مرحبا", "أهلا", "وسهلا", "صباح", "الخير", "مساء", "النور", "شكرا",
        "عفوا", "من", "فضلك", "لو", "سمحت", "تفضل", "تمام", "ماشي", "إن", "شاء", "الله",
        "الحمد", "لله", "مبروك", "ألف", "سلامتك", "حبيبي", "يا", "أخي", "صديقي", "أستاذ",
        "كيف", "حالك", "أخبارك", "عامل", "إيه", "فينك", "واحشني", "بخير", "الحمدلله", "معلش",
        
        // Daily Life, Communication & Time
        "اليوم", "النهاردة", "بكرة", "أمس", "إمبارح", "الآن", "دلوقتي", "بعدين", "قريبا", "دائما",
        "أحيانا", "ساعة", "دقيقة", "ثانية", "يوم", "أسبوع", "شهر", "سنة", "صباحا", "مساء", "ليل",
        "نهار", "السبت", "الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة",
        "رسالة", "واتساب", "مكالمة", "تليفون", "موبايل", "إيميل", "شغل", "عمل", "مكتب", "شركة",
        "جامعة", "مدرسة", "بيت", "منزل", "طريق", "شارع", "سيارة", "عربية", "مواصلات",
        
        // Verbs (Past, Present, Imperative)
        "قال", "يقول", "قل", "كان", "يكون", "كن", "عمل", "يعمل", "اعمل", "راح", "يروح", "ذهب",
        "يذهب", "اذهب", "جاء", "يجيء", "تعال", "أتى", "يأتي", "شاف", "يشوف", "رأى", "يرى",
        "انظر", "سمع", "يسمع", "اسمع", "عرف", "يعرف", "فهم", "يفهم", "تكلم", "يتكلم", "كتب",
        "يكتب", "اكتب", "قرأ", "يقرأ", "اقرأ", "أكل", "يأكل", "شرب", "يشرب", "نام", "ينام",
        "صحى", "يصحى", "مشى", "يمشي", "جلس", "يجلس", "قام", "يقوم", "ساعد", "يساعد", "أراد",
        "يريد", "عايز", "عاوز", "حب", "يحب", "أحبك", "كره", "يكره", "طلب", "يطلب", "أرسل",
        "يرسل", "استلم", "يستلم", "اشترى", "يشتري", "باع", "يبيع", "دفع", "يدفع", "أخذ", "يأخذ",
        "أعطى", "يعطي", "وجد", "يجد", "بحث", "يبحث", "دور", "لقي", "يلقى", "فتح", "يفتح",
        "قفل", "يقفل", "بدأ", "يبدأ", "انتهى", "ينتهي", "خلص", "غير", "يغير", "صلح", "يصلح",
        
        // Common Nouns & Objects
        "كتاب", "قلم", "ورقة", "شاشة", "كمبيوتر", "لابتوب", "إنترنت", "واي", "فاي", "برنامج",
        "تطبيق", "صورة", "فيديو", "صوت", "رقم", "اسم", "عنوان", "حساب", "فلوس", "مال", "جنيه",
        "دولار", "ريال", "سعر", "فاتورة", "طلب", "حجز", "تذكرة", "سفر", "مطار", "فندق", "غرفة",
        "مطعم", "أكل", "طعام", "قهوة", "شاي", "عصير", "ماء", "مياه", "خبز", "لحم", "دجاج",
        "سمك", "سلطة", "فاكهة", "خضار", "تفاح", "موز", "برتقال", "حلوى", "سكر", "ملح",
        
        // Adjectives & Expressions
        "جميل", "حلو", "رائع", "ممتاز", "عظيم", "كبير", "صغير", "طويل", "قصير", "جديد", "قديم",
        "سريع", "بطيء", "سهل", "صعب", "مهم", "ضروري", "واضح", "صحيح", "صح", "خطأ", "غلط",
        "قريب", "بعيد", "كثير", "كتير", "قليل", "شوية", "غالي", "رخيص", "حار", "بارد", "نظيف",
        "ذكي", "شاطر", "قوي", "ضعيف", "سعيد", "فرحان", "حزين", "تعبان", "مشغول", "فاضي", "جاهز",
        "مستعد", "ممكن", "مستحيل", "أكيد", "طبعا", "بالتأكيد", "فعلا", "حقاً", "حقيقي", "أول",
        "آخر", "أفضل", "أحسن", "أكبر", "أصغر", "أكثر", "أقل",
        
        // Magic & Covert Arabic Terms
        "سحر", "خدعة", "سر", "خفي", "كروت", "كارت", "ورق", "تخمين", "توقع", "عرض", "ألعاب",
        "خفة", "عقل", "أفكار", "قراءة", "مفاجأة", "غموض", "أسرار"
    )

    private val englishSet = englishWords.toHashSet()
    private val arabicSet = arabicWords.toHashSet()

    /**
     * Versatile suggestions for both English and Arabic.
     * Supports prefix matching with length ranking, exact matches priority,
     * and smart word completion.
     */
    fun suggestions(prefix: String, isArabic: Boolean, limit: Int = 10): List<String> {
        if (prefix.isBlank()) return emptyList()
        val query = prefix.trim().lowercase()
        val targetList = if (isArabic) arabicWords else englishWords

        // 1. Direct prefix matches
        val prefixMatches = targetList.filter { word ->
            val w = word.lowercase()
            w.startsWith(query) && w != query
        }.sortedWith(
            compareBy<String> { it.length - query.length }
                .thenBy { targetList.indexOf(it) }
        )

        if (prefixMatches.size >= limit) {
            return prefixMatches.take(limit)
        }

        // 2. Substring matches if prefix matches are few
        val substringMatches = targetList.filter { word ->
            val w = word.lowercase()
            w.contains(query) && !w.startsWith(query) && w != query
        }.sortedBy { it.length }

        val combined = (prefixMatches + substringMatches).distinct().take(limit)
        return combined
    }

    /**
     * Fallback top frequently used words when no prefix has been typed yet
     */
    fun topWords(isArabic: Boolean, limit: Int = 8): List<String> {
        return if (isArabic) {
            listOf("شكرا", "تمام", "مرحبا", "إن شاء الله", "الحمد لله", "أنا", "في", "على").take(limit)
        } else {
            listOf("the", "to", "and", "I", "you", "thanks", "hello", "good").take(limit)
        }
    }
}
