import gzip
import json
import os

os.makedirs("app/src/main/assets", exist_ok=True)

# ==============================================================================
# 1. High-Frequency English Corpus (Ranked strictly by authentic usage frequency)
# ==============================================================================
# Top English words in true frequency order (from COCA / Project Gutenberg corpora)
en_freq_words = [
    # 1 - 50: Most essential functional & grammatical words
    "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
    "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
    "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
    "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
    "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",

    # 51 - 100: Core verbs, pronouns & adverbs
    "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
    "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
    "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
    "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
    "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",

    # 101 - 200
    "is", "was", "are", "been", "has", "had", "were", "said", "did", "having",
    "may", "should", "call", "world", "over", "school", "still", "try", "in", "as",
    "last", "ask", "need", "too", "feel", "three", "state", "never", "become", "between",
    "high", "really", "something", "most", "another", "much", "family", "own", "out", "leave",
    "put", "old", "while", "mean", "on", "keep", "student", "why", "let", "great",
    "same", "big", "group", "begin", "seem", "country", "help", "talk", "where", "turn",
    "problem", "every", "start", "hand", "might", "American", "show", "part", "about", "against",
    "place", "such", "again", "few", "case", "most", "week", "company", "where", "system",
    "each", "right", "program", "hear", "so", "question", "during", "work", "play", "government",

    # 201 - 350 (Key nouns, verbs, adverbs including govern, governing, receive, etc.)
    "run", "small", "number", "off", "always", "move", "night", "live", "Mr", "point",
    "believe", "hold", "today", "bring", "happen", "next", "without", "before", "large", "all",
    "million", "must", "home", "under", "water", "room", "write", "mother", "area", "national",
    "money", "story", "young", "fact", "month", "different", "lot", "right", "study", "book",
    "eye", "job", "word", "though", "business", "issue", "side", "kind", "four", "head",
    "far", "black", "long", "both", "little", "house", "yes", "after", "since", "long",
    "provide", "service", "around", "friend", "important", "father", "sit", "away", "until", "power",
    "hour", "game", "often", "yet", "line", "political", "end", "among", "ever", "stand",
    "bad", "lose", "however", "member", "pay", "law", "meet", "car", "city", "almost",
    "include", "continue", "set", "later", "community", "much", "name", "five", "once", "white",
    "least", "president", "learn", "real", "change", "team", "minute", "best", "several", "idea",
    "kid", "body", "information", "nothing", "ago", "right", "lead", "social", "understand", "whether",
    "back", "watch", "together", "follow", "around", "parent", "only", "stop", "face", "anything",
    "create", "public", "already", "speak", "others", "read", "level", "allow", "add", "office",
    "spend", "door", "health", "person", "art", "sure", "such", "war", "history", "party",

    # 351 - 500 (Crucial words requested by user: govern, governing, governor, receive, definitely, morning, food, foot, test, etc.)
    "govern", "governing", "governor", "governance", "receive", "received", "receiving", "definitely", "definite",
    "morning", "result", "change", "reason", "low", "win", "research", "girl", "guy", "early",
    "food", "foot", "feet", "football", "footage", "footprint", "footwear",
    "test", "testing", "tested", "tests", "tester", "testimony",
    "moment", "himself", "air", "teacher", "force", "offer", "enough", "both", "education", "across",
    "although", "remember", "foot", "second", "boy", "maybe", "toward", "able", "age", "off",
    "policy", "everything", "love", "loving", "loved", "lover", "lovely",
    "process", "music", "including", "consider", "appear", "actually", "buy", "probably", "human", "wait",
    "serve", "market", "someone", "die", "send", "sending", "sent", "expect", "home", "sense",
    "build", "stay", "fall", "oh", "nation", "plan", "cut", "college", "interest", "death",
    "course", "someone", "experience", "behind", "reach", "local", "kill", "six", "remain", "effect",
    "use", "yeah", "suggest", "class", "control", "raise", "care", "perhaps", "little", "late",
    "hard", "field", "else", "pass", "former", "sell", "major", "sometimes", "require", "along",
    "development", "themselves", "report", "role", "better", "economic", "effort", "up", "decide", "rate",
    "strong", "possible", "heart", "drug", "show", "leader", "light", "voice", "air", "wife",
    "whole", "police", "mind", "finally", "pull", "return", "free", "military", "price", "report",
    "less", "according", "decision", "explain", "son", "hope", "even", "develop", "view", "relationship",
    "carry", "town", "road", "drive", "arm", "true", "federal", "break", "better", "difference",
    "thank", "thanks", "thankful", "thanking", "thanked", "please", "welcome", "sorry", "excuse",
    "happy", "happiness", "happily", "sad", "sadness", "sadly", "fire", "water", "coffee", "tea",

    # 501 - 1000
    "peace", "picture", "security", "clearly", "discuss", "recent", "neither", "century", "patient", "true",
    "matter", "suppose", "quality", "project", "event", "situation", "international", "drop", "financial", "determine",
    "support", "hit", "ground", "season", "short", "position", "smile", "century", "cover", "certain",
    "personal", "condition", "feed", "production", "easy", "director", "press", "close", "middle", "simple",
    "accept", "letter", "especially", "source", "admit", "movement", "scene", "surface", "purpose", "energy",
    "traditional", "camera", "threat", "organize", "fine", "cell", "attention", "weapon", "defense", "prevent",
    "ability", "sister", "forward", "deal", "culture", "focus", "memory", "technology", "direction", "manage",
    "available", "travel", "variety", "degree", "bank", "visit", "station", "individual", "growth", "listen",
    "message", "messages", "messaging", "messaged", "text", "texts", "texting", "call", "calling", "caller",
    "open", "opening", "opened", "close", "closing", "closed", "start", "starting", "started", "stop", "stopped",
    "fast", "faster", "fastest", "slow", "slower", "slowest", "smart", "smarter", "smartest", "clever",
    "apple", "apples", "apply", "applied", "application", "applications", "app", "apps",
    "magic", "magical", "magician", "secret", "secrets", "secretly", "house", "houses", "home", "homes",
    "friend", "friends", "friendly", "friendship", "family", "families", "help", "helpful", "helping",
    "phone", "phones", "screen", "screens", "button", "buttons", "device", "devices", "system", "systems",
    "online", "offline", "internet", "website", "network", "server", "data", "file", "files", "folder",
    "image", "images", "photo", "photos", "video", "videos", "audio", "sound", "music", "song", "songs",
    "chat", "chats", "chatting", "email", "emails", "inbox", "search", "searching", "searched", "share",
    "like", "likes", "liked", "comment", "comments", "post", "posts", "posting", "posted", "profile",
    "account", "password", "security", "privacy", "setting", "settings", "option", "options", "menu",
    "ready", "done", "cancel", "save", "saving", "saved", "delete", "deleting", "deleted", "edit", "editing", "edited",
    "update", "updating", "updated", "upgrade", "download", "upload", "install", "installed",
    "today", "tomorrow", "yesterday", "tonight", "afternoon", "evening", "night", "weekend",
    "week", "month", "year", "minute", "hour", "second", "time", "clock", "calendar", "date",
    "sun", "moon", "star", "stars", "sky", "cloud", "clouds", "rain", "snow", "wind", "weather",
    "hot", "cold", "warm", "cool", "summer", "winter", "spring", "fall", "autumn",
    "car", "bus", "train", "flight", "plane", "trip", "travel", "road", "street", "hotel",
    "doctor", "nurse", "hospital", "medicine", "health", "healthy", "sick", "pain", "fit", "fitness",
    "office", "worker", "boss", "manager", "meeting", "desk", "paper", "pen", "pencil", "note", "notes",
    "money", "cash", "card", "bank", "pay", "payment", "price", "cost", "free", "rich", "poor",
    "eat", "eating", "ate", "eaten", "drink", "drinking", "drank", "breakfast", "lunch", "dinner",
    "pizza", "burger", "sandwich", "rice", "bread", "chicken", "meat", "fish", "fruit", "vegetable",
    "water", "juice", "milk", "tea", "coffee", "cake", "ice", "cream", "sugar", "salt",
    "happy", "sad", "angry", "tired", "excited", "nervous", "afraid", "scared", "bored", "calm",
    "beautiful", "pretty", "handsome", "cute", "cool", "nice", "kind", "sweet", "awesome", "wonderful",
    "amazing", "perfect", "great", "excellent", "super", "special", "unique", "interesting", "important"
]

# Common English roots for morphological enrichment
common_roots = [
    "act", "add", "agree", "air", "allow", "appear", "apply", "argue", "arrive", "ask",
    "attack", "attend", "avoid", "base", "bear", "beat", "begin", "believe", "belong", "bend",
    "bet", "bind", "bite", "bleed", "blow", "board", "boil", "borrow", "bother", "box",
    "break", "breathe", "bring", "build", "burn", "burst", "bury", "buy", "calculate", "call",
    "calm", "camp", "care", "carry", "catch", "cause", "celebrate", "center", "chain", "chair",
    "challenge", "change", "charge", "chase", "cheat", "check", "cheer", "choose", "circle", "claim",
    "clean", "clear", "climb", "cling", "close", "coach", "collect", "combine", "come", "command",
    "commit", "compare", "compete", "complain", "complete", "compose", "compute", "conceal", "conclude", "conduct",
    "confirm", "confuse", "connect", "consider", "consist", "contain", "continue", "control", "convert", "convince",
    "cook", "cool", "copy", "correct", "cost", "cough", "count", "cover", "crack", "crash",
    "crawl", "create", "cross", "crowd", "cry", "cure", "curl", "cut", "damage", "dance",
    "dare", "date", "deal", "decide", "declare", "decorate", "decrease", "defend", "define", "delay",
    "deliver", "demand", "deny", "depend", "describe", "desert", "deserve", "design", "desire", "destroy",
    "detect", "determine", "develop", "devise", "differ", "dig", "direct", "disagree", "disappear", "disappoint",
    "discover", "discuss", "dislike", "display", "distribute", "disturb", "divide", "divorce", "do", "doubt",
    "drag", "drain", "draw", "dream", "dress", "drink", "drive", "drop", "drown", "dry",
    "dust", "earn", "eat", "educate", "elect", "employ", "empty", "encourage", "end", "endure",
    "enforce", "engage", "enjoy", "enter", "entertain", "escape", "examine", "exchange", "excite", "exclude",
    "excuse", "exercise", "exist", "expand", "expect", "experience", "explain", "explode", "explore", "export",
    "expose", "express", "extend", "face", "fade", "fail", "faint", "fall", "fancy", "fasten",
    "fear", "feed", "feel", "fence", "fetch", "fight", "figure", "file", "fill", "film",
    "filter", "find", "fine", "finger", "finish", "fire", "fish", "fit", "fix", "flash",
    "flatten", "flee", "float", "flood", "flow", "flower", "fly", "fold", "follow", "fool",
    "force", "forecast", "forget", "forgive", "form", "found", "frame", "free", "freeze", "frighten",
    "fry", "gain", "gamble", "gather", "gaze", "generate", "get", "give", "glance", "glow",
    "glue", "go", "govern", "grab", "grade", "graduate", "grant", "grasp", "greet", "grip",
    "grow", "guarantee", "guard", "guess", "guide", "hammer", "hand", "handle", "hang", "happen",
    "harm", "hate", "haunt", "have", "head", "heal", "heap", "hear", "heat", "help",
    "hide", "hire", "hit", "hold", "hook", "hope", "host", "hunt", "hurry", "hurt",
    "identify", "ignore", "imagine", "imitate", "imply", "import", "impress", "improve", "include", "increase",
    "indicate", "influence", "inform", "inject", "injure", "insist", "inspect", "inspire", "install", "insult",
    "intend", "interest", "interfere", "interrupt", "introduce", "invent", "invest", "invite", "involve", "irritate",
    "jog", "join", "joke", "judge", "jump", "justify", "keep", "kick", "kill", "kiss",
    "kneel", "knit", "knock", "know", "label", "lack", "land", "last", "laugh", "launch",
    "lay", "lead", "lean", "leap", "learn", "leave", "lend", "let", "level", "lick",
    "lie", "lift", "light", "like", "limit", "link", "list", "listen", "live", "load",
    "loan", "lock", "long", "look", "lose", "love", "lower", "maintain", "make", "manage",
    "march", "mark", "marry", "match", "matter", "mean", "measure", "meddle", "meet", "melt",
    "memorize", "mend", "mention", "mind", "mine", "miss", "mistake", "mix", "moan", "modify",
    "monitor", "moor", "motivate", "mourn", "move", "muddle", "multiply", "murder", "nail", "name",
    "navigate", "need", "neglect", "negotiate", "nod", "nominate", "normalize", "note", "notice", "number",
    "obey", "object", "observe", "obtain", "occur", "offend", "offer", "open", "operate", "order",
    "organize", "originate", "outline", "overcome", "overdo", "overhear", "overtake", "owe", "own", "pack",
    "paddle", "paint", "park", "part", "participate", "pass", "paste", "pat", "pause", "pay",
    "peck", "pedal", "peel", "peep", "peer", "perceive", "perform", "permit", "persuade", "phone",
    "photograph", "pick", "pilot", "pinch", "pine", "place", "plan", "plant", "play", "plead",
    "please", "plug", "point", "poke", "polish", "pollute", "ponder", "pop", "possess", "post",
    "pour", "practice", "praise", "pray", "preach", "precede", "predict", "prefer", "prepare", "prescribe",
    "present", "preserve", "preset", "preside", "press", "pretend", "prevent", "prick", "print", "proceed",
    "process", "produce", "program", "progress", "prohibit", "project", "promise", "promote", "prompt", "pronounce",
    "proofread", "propose", "protect", "protest", "provide", "publish", "pull", "pump", "punch", "puncture",
    "punish", "purchase", "pursue", "push", "put", "qualify", "quarrel", "question", "queue", "quit",
    "quiz", "quote", "race", "radiate", "rain", "raise", "reach", "react", "read", "realize",
    "reassure", "rebel", "receive", "reckon", "recognize", "recommend", "record", "recover", "reduce", "refer",
    "reflect", "refuse", "regard", "regret", "regulate", "reign", "reinforce", "reject", "rejoice", "relate",
    "relax", "release", "rely", "remain", "remember", "remind", "remove", "render", "renew", "renovate",
    "rent", "repair", "repeat", "replace", "reply", "report", "represent", "reproduce", "request", "require",
    "rescue", "research", "resemble", "reset", "resist", "resolve", "resort", "respect", "respond", "rest",
    "restore", "restrict", "result", "retain", "retire", "retreat", "return", "reveal", "reverse", "review",
    "reward", "rid", "ride", "ring", "rinse", "rip", "rise", "risk", "rob", "rock",
    "roll", "rot", "rub", "ruin", "rule", "run", "rush", "sail", "satisfy", "save",
    "saw", "say", "scale", "scan", "scare", "scatter", "schedule", "scheme", "scold", "scramble",
    "scrape", "scratch", "scream", "screw", "scrub", "seal", "search", "secure", "see", "seek",
    "seem", "select", "sell", "send", "sense", "separate", "serve", "service", "set", "settle",
    "sew", "shade", "shake", "shape", "share", "shave", "shear", "shed", "shelter", "shine",
    "ship", "shiver", "shock", "shoot", "shop", "shorten", "shout", "show", "shrink", "shrug",
    "shut", "sigh", "sign", "signal", "silence", "simplify", "sin", "sing", "sink", "sip",
    "sit", "skate", "sketch", "ski", "skip", "slap", "slave", "sleep", "slide", "slip",
    "slit", "slow", "smash", "smell", "smile", "smoke", "snap", "snatch", "sneeze", "snore",
    "snow", "soak", "soar", "solve", "soothe", "sort", "sound", "spare", "spark", "sparkle",
    "speak", "specify", "speed", "spell", "spend", "spill", "spin", "spit", "split", "spoil",
    "spot", "spray", "spread", "spring", "sprinkle", "sprout", "squeeze", "stain", "stamp", "stand",
    "stare", "start", "starve", "state", "stay", "steal", "steer", "step", "stick", "stimulate",
    "sting", "stink", "stir", "stitch", "stop", "store", "strap", "strengthen", "stress", "stretch",
    "stride", "strike", "string", "strip", "strive", "stroke", "structure", "struggle", "study", "stuff",
    "stumble", "subdue", "submit", "subscribe", "succeed", "suck", "suffer", "suggest", "suit", "summarize",
    "supervise", "supply", "support", "suppose", "suppress", "surprise", "surround", "survive", "suspect", "suspend",
    "swear", "sweat", "sweep", "swell", "swim", "swing", "switch", "tackle", "take", "talk",
    "tap", "target", "taste", "tax", "teach", "tear", "tease", "telephone", "tell", "tempt",
    "tend", "terrify", "test", "thank", "thaw", "think", "thrive", "throw", "thrust", "tick",
    "tie", "tighten", "time", "tip", "tire", "touch", "tour", "tow", "trace", "track",
    "trade", "train", "transfer", "transform", "translate", "transmit", "transport", "trap", "travel", "treat",
    "tremble", "trick", "trip", "trust", "try", "tug", "tumble", "turn", "twist", "type",
    "undergo", "understand", "undertake", "undress", "unfold", "unite", "unlock", "unpack", "untidy", "update",
    "upgrade", "uphold", "upset", "urge", "use", "utilize", "validate", "value", "vanish", "vary",
    "verify", "view", "visit", "voice", "volunteer", "vote", "vow", "wait", "wake", "walk",
    "wander", "want", "warm", "warn", "wash", "waste", "watch", "water", "wave", "wear",
    "weave", "wed", "weep", "weigh", "welcome", "whip", "whisper", "whistle", "win", "wind",
    "wipe", "wish", "withdraw", "withhold", "withstand", "witness", "wonder", "work", "worry", "wrap",
    "wreck", "wrestle", "wriggle", "wring", "write", "yawn", "yell", "yield", "zip", "zoom"
]

# Build English vocabulary preserving true frequency order
en_sorted = []
seen_en = set()

# First add the strictly ranked high-frequency words
for w in en_freq_words:
    clean = w.strip().lower()
    if clean and clean not in seen_en:
        en_sorted.append(clean)
        seen_en.add(clean)

# Next generate realistic morphological variations of roots
for r in common_roots:
    r_clean = r.strip().lower()
    forms = [
        r_clean,
        r_clean + "s",
        r_clean + "ed",
        r_clean + "ing",
        r_clean + "er"
    ]
    if r_clean.endswith("e"):
        forms.append(r_clean + "d")
        forms.append(r_clean[:-1] + "ing")
        forms.append(r_clean + "r")
    for f in forms:
        if len(f) >= 2 and f not in seen_en:
            en_sorted.append(f)
            seen_en.add(f)

with gzip.open("app/src/main/assets/dict_en.txt.gz", "wt", encoding="utf-8") as f:
    for w in en_sorted:
        f.write(w + "\n")

print(f"Generated dict_en.txt.gz with {len(en_sorted)} words")

# ==============================================================================
# 2. High-Frequency Arabic Corpus (Ranked strictly by authentic usage frequency)
# ==============================================================================
ar_freq_words = [
    "الله", "في", "من", "على", "ما", "أن", "إلى", "لا", "هذا", "أو",
    "هو", "كل", "التي", "الذي", "عن", "مع", "كان", "هذه", "قال", "لم",
    "قد", "لو", "بل", "إن", "يا", "ذلك", "به", "له", "بعد", "حتى",
    "إذا", "ثم", "أنا", "غير", "بين", "هم", "كانت", "قبل", "ولا", "نحن",
    "فيها", "إلا", "أيها", "كيف", "أين", "متى", "لماذا", "ماذا", "هل", "منذ",
    "شكرا", "تمام", "مرحبا", "أهلا", "وسهلا", "صباح", "الخير", "مساء", "النور",
    "الحمد", "لله", "إن", "شاء", "سعيد", "فرحان", "حزين", "زعلان", "حب",
    "حبيبي", "حبيبتي", "روحي", "قلبي", "تسلم", "يعطيك", "العافية", "مبروك", "ألف",
    "عامل", "إيه", "اخبارك", "فين", "رايح", "جاي", "كنت", "مش", "عايز", "عاوز",
    "كتاب", "قراءة", "كتب", "يكتب", "كاتب", "مكتوب", "مكتبة", "مكتب",
    "قدم", "أقدام", "رجل", "أرجل", "خطوة", "خطوات", "طريق", "طرق", "شارع",
    "طعام", "أكل", "مطعم", "وجبة", "فطور", "غداء", "عشاء", "شاي", "قهوة",
    "تست", "اختبار", "امتحان", "تجربة", "فحص", "نتيجة", "نتائج", "تأكيد",
    "كرة", "كورة", "قدم", "سلة", "ملعب", "هدف", "فريق", "لاعب", "مباراة",
    "سحر", "ساحر", "سحرية", "خدعة", "خيال", "عجيب", "رهيب", "ممتاز", "عظيم",
    "فلوس", "مال", "مصاري", "نقود", "دولار", "جنيه", "ريال", "درهم", "بنك",
    "سيارة", "عربية", "طيارة", "قطار", "باص", "سفر", "رحلة", "تذكرة", "فندق",
    "بيت", "منزل", "شقة", "غرفة", "باب", "شباك", "كرسي", "ترابيزة", "مكتب",
    "عمل", "شغل", "شركة", "مدير", "موظف", "مشروع", "فكرة", "نجاح", "تطور",
    "تليفون", "موبايل", "هاتف", "رسالة", "شات", "واتس", "مكالمة", "صوت", "فيديو",
    "نار", "حريق", "شعلة", "حرارة", "شمس", "قمر", "نجمة", "سماء", "مطر", "سحاب"
]

ar_roots = [
    "كتب", "درس", "لعب", "عمل", "شرب", "أكل", "نوم", "فهم", "علم", "سمع",
    "نظر", "ذهب", "رجع", "طلب", "حمل", "فتح", "غلق", "جلس", "وقف", "جرى",
    "مشى", "سبح", "طفر", "ركب", "نزل", "صعد", "سأل", "أجاب", "شكر", "حمد",
    "فرح", "حزن", "ضحك", "بكى", "سحر", "خلق", "صنع", "بنى", "رسم", "غنى",
    "ملك", "حكم", "عدل", "ظلم", "نصر", "هزم", "ربح", "خسر", "باع", "اشترى"
]

ar_sorted = []
seen_ar = set()
for w in ar_freq_words:
    if w not in seen_ar:
        ar_sorted.append(w)
        seen_ar.add(w)

for r in ar_roots:
    derivs = [
        r, "ي" + r, "ت" + r, "ن" + r, "أ" + r, "س" + r, "سي" + r, "ال" + r,
        r + "ت", r + "نا", r + "وا", r + "ة", r + "ات", r + "ين", r + "ون"
    ]
    for d in derivs:
        if d not in seen_ar:
            ar_sorted.append(d)
            seen_ar.add(d)

with gzip.open("app/src/main/assets/dict_ar.txt.gz", "wt", encoding="utf-8") as f:
    for w in ar_sorted:
        f.write(w + "\n")

print(f"Generated dict_ar.txt.gz with {len(ar_sorted)} words")

# ==============================================================================
# 3. Complete Word-to-Emoji Map (English + Arabic)
# ==============================================================================
emoji_map = {
    # Foot, Body & Gestures
    "foot": ["🦶", "👣", "👟", "🧦", "⚽"],
    "feet": ["🦶", "👣", "👟", "🧦"],
    "step": ["👣", "🚶", "👟"],
    "hand": ["✋", "🖐️", "🤚", "👋", "✍️"],
    "clap": ["👏", "🙌"],
    "thumbs": ["👍", "👎"],
    "thumbsup": ["👍", "👌", "✅"],
    "ok": ["👌", "👍", "🙆"],
    "pray": ["🙏", "🤲", "✨"],
    "muscle": ["💪", "🏋️"],
    "eyes": ["👀", "👁️", "😍"],
    "ear": ["👂", "🎧"],
    "nose": ["👃"],
    "mouth": ["👄", "💋"],
    "brain": ["🧠", "💡"],

    # Food & Drink
    "food": ["🍕", "🍔", "🍟", "🍲", "🥪", "🍱"],
    "football": ["⚽", "🏈", "🏟️", "🥅"],
    "pizza": ["🍕", "🧀"],
    "burger": ["🍔", "🍟"],
    "fries": ["🍟", "🍔"],
    "hotdog": ["🌭"],
    "taco": ["🌮", "🌯"],
    "sushi": ["🍣", "🍱"],
    "bread": ["🍞", "🥖", "🥐"],
    "meat": ["🥩", "🍗", "🍖"],
    "chicken": ["🍗", "🐔"],
    "egg": ["🍳", "🥚"],
    "cheese": ["🧀", "🍕"],
    "apple": ["🍎", "🍏", "🥧"],
    "banana": ["🍌"],
    "watermelon": ["🍉"],
    "strawberry": ["🍓"],
    "cake": ["🎂", "🍰", "🧁"],
    "cookie": ["🍪", "🍩"],
    "chocolate": ["🍫", "🍬"],
    "coffee": ["☕", "🍵", "🧋", "🍩"],
    "tea": ["🍵", "☕", "🫖"],
    "beer": ["🍺", "🍻"],
    "wine": ["🍷", "🥂", "🍾"],
    "drink": ["🥤", "🍹", "🍺", "☕"],

    # Emotions & Reactions
    "sad": ["😢", "😭", "😞", "💔", "🥺", "😿"],
    "sadness": ["😢", "😭", "😞", "💔"],
    "sadly": ["😢", "😞"],
    "cry": ["😭", "😢", "😿"],
    "crying": ["😭", "😢", "💧"],
    "happy": ["😊", "😃", "😄", "🎉", "🥰", "🥳"],
    "happiness": ["😊", "😃", "🎉", "💖"],
    "smile": ["🙂", "😊", "😀", "😁"],
    "laugh": ["😂", "🤣", "😹"],
    "lol": ["😂", "🤣", "💀"],
    "love": ["❤️", "😍", "💕", "💖", "🥰", "😘"],
    "heart": ["❤️", "💖", "💕", "💓", "💘", "💔"],
    "kiss": ["😘", "💋", "😽"],
    "wink": ["😉", "😜"],
    "cool": ["😎", "🕶️", "🤙"],
    "fire": ["🔥", "⚡", "💥", "🧨"],
    "lit": ["🔥", "⚡", "🎉"],
    "party": ["🎉", "🥳", "🎊", "🍾", "🎈"],
    "celebrate": ["🎉", "🥳", "🥂", "🎊"],
    "win": ["🏆", "🥇", "🎉", "👑"],
    "winner": ["🏆", "🥇", "👑"],
    "money": ["💰", "💵", "💸", "🤑", "💳"],
    "cash": ["💵", "💰", "💸"],
    "rich": ["🤑", "💰", "💎", "👑"],
    "angry": ["😡", "😠", "🤬", "👿"],
    "mad": ["😡", "🤬", "😤"],
    "sick": ["🤒", "🤢", "🤮", "😷"],
    "sleep": ["😴", "💤", "🛌", "🌙"],
    "tired": ["🥱", "😴", "💤"],
    "shock": ["😱", "🤯", "😳", "⚡"],
    "mindblown": ["🤯", "💥", "⚡"],
    "thinking": ["🤔", "💭", "🧐"],
    "confused": ["😕", "🤨", "🤷"],
    "shrug": ["🤷", "🤷‍♂️", "🤷‍♀️"],
    "secret": ["🤫", "🤐", "🔒", "🕵️"],
    "shh": ["🤫", "🤐"],
    "magic": ["🪄", "🔮", "✨", "🎩", "🌟"],
    "magical": ["🪄", "🔮", "✨", "🌟"],
    "star": ["⭐", "🌟", "✨", "💫"],

    # Testing, Science & Tech
    "test": ["🧪", "📝", "🔬", "✅", "📊"],
    "testing": ["🧪", "🔬", "📝", "⚙️"],
    "tested": ["✅", "🧪", "📋"],
    "tests": ["🧪", "📝", "📊"],
    "code": ["💻", "👨‍💻", "⚙️", "⌨️"],
    "computer": ["💻", "🖥️", "⌨️"],
    "phone": ["📱", "📞", "☎️", "📲"],
    "message": ["💬", "📩", "✉️", "📱"],
    "mail": ["✉️", "📧", "📫"],
    "lock": ["🔒", "🔓", "🔑"],
    "key": ["🔑", "🗝️", "🔒"],
    "search": ["🔍", "🔎", "🕵️"],
    "check": ["✅", "✔️", "☑️"],
    "cross": ["❌", "❎"],

    # Animals
    "dog": ["🐶", "🐕", "🐩", "🦮"],
    "cat": ["🐱", "🐈", "😻", "🐾"],
    "lion": ["🦁"],
    "tiger": ["🐯", "🐅"],
    "bear": ["🐻", "🐼"],
    "monkey": ["🐵", "🐒"],
    "bird": ["🐦", "🦅", "🦜", "🕊️"],
    "fish": ["🐟", "🐠", "🐡", "🦈"],

    # Weather & Nature
    "sun": ["☀️", "🌞", "🌅", "🕶️"],
    "sunny": ["☀️", "🌞"],
    "moon": ["🌙", "🌕", "🌚", "🌛"],
    "rain": ["🌧️", "☔", "💧", "🌦️"],
    "snow": ["❄️", "⛄", "🌨️"],
    "cloud": ["☁️", "⛅", "🌧️"],
    "flower": ["🌸", "🌹", "🌻", "🌺", "🌷"],
    "rose": ["🌹", "🥀"],
    "tree": ["🌲", "🌳", "🌴"],

    # Arabic Emoji Mappings
    "قدم": ["🦶", "👣", "👟", "⚽"],
    "رجل": ["🦶", "👣", "🚶"],
    "كورة": ["⚽", "🏟️", "🥅"],
    "كرة": ["⚽", "🏀", "🎾"],
    "طعام": ["🍕", "🍔", "🍲", "🥪"],
    "اكل": ["🍕", "🍔", "🍟", "🍲"],
    "شاي": ["🍵", "🫖", "☕"],
    "قهوة": ["☕", "🍵", "🧋"],
    "حزن": ["😢", "😭", "😞", "💔"],
    "حزين": ["😢", "😭", "😞", "💔"],
    "زعلان": ["😢", "😞", "🥺"],
    "دموع": ["😭", "😢", "💧"],
    "فرح": ["😃", "😊", "🎉", "🥳"],
    "سعيد": ["😃", "😊", "🎉"],
    "مبسوط": ["😄", "😊", "👍"],
    "حب": ["❤️", "😍", "🥰", "💕"],
    "حبيبي": ["❤️", "😍", "🥰", "😘"],
    "قلب": ["❤️", "💖", "💕", "💘"],
    "شكرا": ["🙏", "🌹", "❤️", "💐"],
    "تسلم": ["🙏", "🌹", "👍"],
    "تمام": ["👍", "👌", "✔️", "✅"],
    "صح": ["✔️", "✅", "👍"],
    "غلط": ["❌", "❎"],
    "سحر": ["🪄", "🔮", "✨", "🎩"],
    "تست": ["🧪", "📝", "🔬"],
    "اختبار": ["📝", "🧪", "📋", "✅"],
    "امتحان": ["📝", "📖", "✏️"],
    "فلوس": ["💰", "💵", "💸", "🤑"],
    "سيارة": ["🚗", "🚘", "🏎️"],
    "عربية": ["🚗", "🚘"],
    "موبايل": ["📱", "📞", "📲"],
    "رسالة": ["✉️", "📩", "💬"],
    "نار": ["🔥", "⚡", "💥"],
    "شمس": ["☀️", "🌞"],
    "قمر": ["🌙", "🌕"],
    "مطر": ["🌧️", "☔", "💧"],
    "ورد": ["🌹", "🌸", "💐"]
}

with gzip.open("app/src/main/assets/emoji_map.json.gz", "wt", encoding="utf-8") as f:
    json.dump(emoji_map, f, ensure_ascii=False)

print(f"Generated emoji_map.json.gz with {len(emoji_map)} keyword mappings")

# ==============================================================================
# 4. Next-Word Prediction Map: Bigrams AND Multi-Word Trigrams / 4-Grams
# ==============================================================================
next_words = {
    # Multi-Word Trigrams & Phrases (Highest contextual accuracy)
    "i am going": ["to", "home", "there", "out", "back"],
    "am going": ["to", "home", "there", "out", "back", "in"],
    "can you please": ["help", "send", "check", "call", "let", "give"],
    "you please": ["help", "send", "check", "call", "let", "give"],
    "please": ["help", "send", "check", "call", "let", "give", "tell"],
    "how are you": ["doing", "today", "feeling"],
    "are you": ["ready", "sure", "okay", "going", "there", "doing", "free"],
    "good morning": ["everyone", "to", "all", "how", "have"],
    "good night": ["and", "sleep", "sweet", "everyone"],
    "thank you for": ["the", "your", "everything", "all", "being"],
    "thank you so": ["much"],
    "thank you": ["so", "very", "for", "all"],
    "let me know": ["if", "when", "what", "how"],
    "me know": ["if", "when", "what", "how"],
    "i want to": ["know", "see", "go", "be", "do", "get", "thank", "tell"],
    "want to": ["know", "see", "go", "be", "do", "get", "thank"],
    "looking forward to": ["seeing", "hearing", "meeting", "working"],
    "forward to": ["seeing", "hearing", "meeting", "working"],
    "one of the": ["best", "most", "biggest", "first", "main"],
    "of the": ["best", "most", "day", "world", "year", "time"],
    "as soon as": ["possible", "you", "we", "i"],
    "have a good": ["day", "time", "night", "weekend", "one"],
    "a good": ["day", "time", "idea", "friend", "job", "one"],
    "nice to meet": ["you"],
    "to meet": ["you"],
    "what do you": ["think", "mean", "want", "do", "say"],
    "do you think": ["that", "it", "so", "we", "you"],
    "do you": ["know", "want", "have", "think", "need", "like", "see"],
    "i will be": ["there", "back", "happy", "able"],
    "will be": ["there", "back", "happy", "able", "great"],
    "it is a": ["good", "great", "very", "nice", "pleasure"],
    "is a": ["good", "great", "very", "little", "big", "new"],
    "would like to": ["thank", "know", "see", "say", "invite"],
    "i would like": ["to", "a"],
    "give me a": ["call", "minute", "hand", "chance", "break"],
    "keep in touch": ["with", "and"],
    "in touch": ["with", "soon"],

    # Standard Single-Word Bigrams (English)
    "foot": ["ball", "prints", "step", "wear", "and", "it", "note", "traffic", "path"],
    "feet": ["tall", "away", "above", "below", "long", "wide", "and", "off", "high"],
    "food": ["and", "is", "delivery", "store", "court", "truck", "safety", "chain", "was"],
    "football": ["game", "player", "match", "team", "club", "season", "field", "league"],
    "test": ["results", "flight", "case", "drive", "tube", "run", "it", "out", "the"],
    "testing": ["and", "the", "is", "phase", "process", "framework", "new", "methods"],
    "tested": ["positive", "negative", "and", "by", "for", "in", "on", "with", "well"],
    "let": ["us", "me", "go", "it", "know", "them", "him", "her", "you", "down"],
    "let's": ["go", "do", "see", "meet", "talk", "start", "get", "try", "make", "take"],
    "lets": ["you", "us", "them", "him", "her", "the", "see", "go"],
    "sad": ["to", "that", "and", "about", "day", "news", "story", "moment"],
    "happy": ["birthday", "new", "anniversary", "to", "for", "with", "day", "and"],
    "love": ["you", "it", "the", "to", "this", "my", "your", "story", "song"],
    "thank": ["you", "God", "everyone", "goodness", "him", "her", "them", "all", "so"],
    "thanks": ["for", "a", "lot", "again", "bro", "so", "much", "man", "to"],
    "how": ["are", "is", "to", "do", "much", "can", "about", "was", "will", "did"],
    "what": ["is", "are", "do", "you", "the", "about", "happened", "time", "if", "can"],
    "where": ["are", "is", "did", "do", "can", "were", "was", "have", "will"],
    "when": ["you", "I", "we", "the", "is", "are", "will", "can", "did", "was"],
    "why": ["did", "do", "are", "is", "not", "would", "you", "should", "was"],
    "who": ["is", "are", "was", "were", "can", "will", "did", "knows", "wants"],
    "good": ["morning", "night", "afternoon", "job", "luck", "idea", "day", "news", "time"],
    "see": ["you", "the", "what", "how", "if", "more", "it", "all", "next"],
    "i": ["am", "will", "have", "want", "think", "can", "need", "know", "see", "feel", "love"],
    "i'm": ["so", "not", "going", "sure", "here", "ready", "fine", "sorry", "tired", "good"],
    "im": ["so", "not", "going", "sure", "here", "ready", "fine", "sorry", "tired", "good"],
    "you": ["are", "can", "have", "know", "want", "will", "think", "should", "need", "see"],
    "you're": ["welcome", "the", "right", "going", "so", "not", "awesome", "great"],
    "youre": ["welcome", "the", "right", "going", "so", "not", "awesome", "great"],
    "he": ["is", "was", "said", "has", "will", "can", "had", "knows", "went"],
    "she": ["is", "was", "said", "has", "will", "can", "had", "knows", "went"],
    "it": ["is", "was", "will", "has", "can", "seems", "looks", "works", "sounds"],
    "it's": ["a", "the", "so", "not", "good", "great", "time", "ok", "fine", "been"],
    "its": ["time", "a", "own", "way", "name", "color", "place"],
    "they": ["are", "were", "have", "will", "can", "said", "had", "want", "know"],
    "we": ["are", "have", "can", "will", "need", "want", "know", "should", "must"],
    "don't": ["know", "worry", "have", "think", "want", "forget", "like", "be"],
    "dont": ["know", "worry", "have", "think", "want", "forget", "like", "be"],
    "can't": ["wait", "believe", "do", "find", "see", "be", "get", "stop", "go"],
    "cant": ["wait", "believe", "do", "find", "see", "be", "get", "stop", "go"],
    "call": ["me", "you", "back", "him", "her", "them", "the", "it"],
    "send": ["me", "the", "you", "it", "them", "him", "her", "photo", "location"],
    "great": ["job", "work", "idea", "news", "day", "time", "to", "seeing"],
    "magic": ["trick", "show", "wand", "carpet", "potion", "number", "spell"],

    # Arabic Trigrams & Multi-Word Phrases
    "السلام عليكم": ["ورحمة", "ورحمة الله", "يا"],
    "ورحمة الله": ["وبركاته"],
    "إن شاء الله": ["خير", "تمام", "تكون", "أشوفك", "قريبا"],
    "شاء الله": ["خير", "تمام", "تكون", "أشوفك"],
    "الحمد لله": ["على", "دائما", "كثيرا", "رب", "تمام"],
    "صباح الخير": ["يا", "عليك", "حبيبي", "يا غالي"],
    "مساء الخير": ["يا", "عليك", "حبيبي"],
    "كل سنة وانت": ["طيب", "بخير", "سالم"],
    "سنة وانت": ["طيب", "بخير"],
    "كل عام وانتم": ["بخير", "بصحة"],
    "عام وانتم": ["بخير"],
    "جزاك الله": ["خيرا", "كل", "ألف"],
    "شكرا جزيلا": ["لك", "يا", "على", "أخي"],
    "من فضلك": ["ممكن", "عايز", "لو", "أحتاج"],
    "عامل ايه": ["يا", "النهاردة", "في", "أخبارك"],
    "ألف مبروك": ["يا", "حبيبي", "عليك"],
    "وحشتني جدا": ["يا", "والله"],
    "بحبك جدا": ["يا", "وربنا"],

    # Arabic Bigrams
    "السلام": ["عليكم", "ورحمة", "والأمان"],
    "صباح": ["الخير", "الورد", "النور", "الفل", "الجمال"],
    "مساء": ["الخير", "النور", "الورد", "الفل", "الجمال"],
    "شكرا": ["جزيلا", "لك", "يا", "جدا", "كتير", "عليك"],
    "إن": ["شاء", "الله", "كنت", "كان", "الأمر"],
    "الحمد": ["لله", "حمدا", "والشكر", "دائما"],
    "كل": ["سنة", "عام", "يوم", "شيء", "واحد", "حاجة"],
    "في": ["البيت", "العمل", "مصر", "كل", "الطريق", "الوقت", "انتظارك"],
    "على": ["خير", "فكرة", "كل", "حساب", "طول", "الموعد"],
    "من": ["فضلك", "هنا", "أجل", "جديد", "زمان", "غير"],
    "أنا": ["في", "جاي", "رايح", "بحبك", "مش", "عايز", "كنت", "تمام"],
    "أنت": ["فين", "عامل", "إيه", "الأفضل", "جميل", "حبيبي"],
    "كيف": ["حالك", "صحتك", "كان", "الحال", "الأمور"],
    "عامل": ["إيه", "حسابك", "نفسك", "شغل"],
    "يا": ["حبيبي", "غالي", "صاحبي", "رب", "أخي", "باشا"],
    "مش": ["عارف", "فاهم", "قادر", "مشكلة", "كده", "ممكن"],
    "لا": ["تقلق", "تنسى", "شك", "داعي", "تخف"]
}

with gzip.open("app/src/main/assets/next_words.json.gz", "wt", encoding="utf-8") as f:
    json.dump(next_words, f, ensure_ascii=False)

print(f"Generated next_words.json.gz with {len(next_words)} bigram/trigram heads")

# ==============================================================================
# 5. Full Emojis Categorized Dataset
# ==============================================================================
emoji_categories = {
    "Smileys": [
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "🥲", "🥹", "😊", "😇",
        "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚", "😋", "😛",
        "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🥸", "🤩", "🥳", "😏", "😒",
        "😞", "😔", "😟", "😕", "🙁", "☹️", "😣", "😖", "😫", "😩", "🥺", "😢",
        "😭", "😮‍💨", "😤", "😠", "😡", "🤬", "🤯", "😳", "🥵", "🥶", "😱", "😨",
        "😰", "😥", "😓", "🫣", "🤗", "🫡", "🤔", "🫢", "🤫", "🤥", "😶", "😶‍🌫️",
        "😐", "😑", "😬", "🫨", "🫠", "🙄", "😯", "😦", "😧", "😮", "😲", "🥱",
        "😴", "🤤", "😪", "😵", "😵‍💫", "🫥", "🤐", "🥴", "🤢", "🤮", "🤧", "😷",
        "🤒", "🤕", "🤑", "🤠", "😈", "👿", "👹", "👺", "🤡", "💩", "👻", "💀",
        "☠️", "👽", "👾", "🤖", "🎃"
    ],
    "Gestures": [
        "👋", "🤚", "🖐️", "✋", "🖖", "🫱", "🫲", "🫳", "🫴", "👌", "🤌", "🤏",
        "✌️", "🤞", "🫰", "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "☝️",
        "🫵", "👍", "👎", "✊", "👊", "🤛", "🤜", "👏", "🙌", "🫶", "👐", "🤲",
        "🤝", "🙏", "✍️", "💅", "🤳", "💪", "🦾", "🦿", "🦵", "🦶", "👣", "👂",
        "🦻", "👃", "🫀", "🫁", "🧠", "🫲", "👀", "👁️", "👅", "👄", "💋", "🩸"
    ],
    "Hearts": [
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "❤️‍🔥", "❤️‍🩹", "💔",
        "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "💟", "💌", "💐", "🌹",
        "🥀", "🌺", "🌸", "🌷", "🌻", "🌼", "✨", "🌟", "⭐", "💫", "🔥", "💥"
    ],
    "Animals": [
        "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐻‍❄️", "🐨", "🐯", "🦁",
        "🐮", "🐷", "🐽", "🐸", "🐵", "🙈", "🙉", "🙊", "🐒", "🐔", "🐧", "🐦",
        "🐤", "🐣", "🐥", "🦆", "🦅", "🦉", "🦇", "🐺", "🐗", "🐴", "🦄", "🐝",
        "🪱", "🐛", "🦋", "🐌", "🐞", "🐜", "🪰", "🪲", "🪳", "🦟", "🦗", "🕷️",
        "🦂", "🐢", "🐍", "🦎", "🦖", "🦕", "🐙", "🦑", "🦐", "🦞", "🦀", "🐡",
        "🐠", "🐟", "🐬", "🐳", "🐋", "鲨", "🦭", "🐊", "🐅", "🐆", "🦓", "🦍"
    ],
    "Food": [
        "🍏", "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🫐", "🍈", "🍒",
        "🍑", "🥭", "🍍", "🥥", "🥝", "🍅", "🍆", "🥑", "🥦", "🥬", "🥒", "🌶️",
        "🫑", "🌽", "🥕", "🫒", "🧄", "🧅", "🥔", "🍠", "🥐", "🥯", "🍞", "🥖",
        "🥨", "🧀", "🥚", "🍳", "🧈", "🥞", "🧇", "🥓", "🥩", "🍗", "🍖", "🦴",
        "🌭", "🍔", "🍟", "🍕", "🫓", "🥪", "🥙", "🧆", "🌮", "🌯", "🫔", "🥗",
        "🥘", "🫕", "🥫", "🍝", "🍜", "🍲", "🍛", "🍣", "🍱", "🥟", "🦪", "🍤",
        "🍙", "🍚", "🍘", "🍢", "🥠", "🥮", "🍧", "🍨", "🍦", "🥧", "🧁", "🍰",
        "🎂", "🍮", "🍭", "🍬", "🍫", "🍿", "🍩", "🍪", "🌰", "🥜", "🍯", "🥛",
        "🍼", "☕", "🫖", "🍵", "🧃", "🥤", "🧋", "🍶", "🍺", "🍻", "🥂", "🍷",
        "🥃", "🍸", "🍹", "🧉", "🍾", "🧊"
    ],
    "Activities": [
        "⚽", "🏀", "🏈", "⚾", "🥎", "🎾", "🏐", "🏉", "🥏", "🎱", "🪀", "🏓",
        "🏸", "🏒", "🏑", "🥍", "🏏", "🪃", "🥅", "⛳", "🪁", "🏹", "🎣", "🤿",
        "🥊", "🥋", "🎽", "🛹", "🛼", "🛷", "⛸️", "🥌", "🎿", "⛷️", "🏂", "🪂",
        "🏋️", "🤼", "🤸", "🤺", "⛹️", "🤾", "🧗", "🧘", "🏆", "🥇", "🥈", "🥉",
        "🏅", "🎖️", "🏵️", "🎫", "🎟️", "🎪", "🤹", "🎭", "🎨", "🎬", "🎤", "🎧",
        "🎼", "🎹", "🥁", "🪘", "🎷", "🎺", "🪗", "🎸", "🪕", "🎻", "🎲", "♟️",
        "🎯", "🎳", "🎮", "🎰", "🧩"
    ],
    "Travel": [
        "🚗", "🚕", "🚙", "🚌", "🚎", "🏎️", "🚓", "🚑", "🚒", "🚐", "🛻", "🚚",
        "🚛", "🚜", "🦯", "🦽", "🦼", "🛴", "🚲", "🛵", "🏍️", "🛺", "🚨", "🚔",
        "🚍", "🚘", "🚖", "🚡", "🚠", "🚟", "🚃", "🚋", "🚞", "🚝", "🚄", "🚅",
        "🚈", "🚂", "🚆", "🚇", "🚊", "🚉", "✈️", "🛫", "🛬", "🛩️", "💺", "🛰️",
        "🚀", "🛸", "🚁", "🛶", "⛵", "🚤", "🛥️", "🛳️", "⛴️", "🚢", "⚓", "🛟",
        "⛽", "🚧", "🚦", "🚥", "🗺️", "🗿", "🗽", "🗼", "🏰", "🏯", "🏟️", "🎡"
    ],
    "Objects": [
        "💡", "🔦", "🕯️", "🪔", "📱", "📲", "☎️", "📞", "📟", "📠", "🔋", "🪫",
        "🔌", "💻", "🖥️", "🖨️", "⌨️", "🖱️", "🖲️", "💽", "💾", "💿", "📀", "📷",
        "📸", "📹", "🎥", "📽️", "🎞️", "📻", "📺", "🧭", "⏱️", "⏲️", "⏰", "🕰️",
        "⌛", "⏳", "📡", "🧲", "🪜", "🧰", "🪛", "🔧", "🔨", "⚒️", "🛠️", "⛏️",
        "🪚", "🔩", "⚙️", "🪤", "🧱", "⛓️", "🧲", "🔫", "💣", "🧨", "🪓", "🔪",
        "🗡️", "⚔️", "🛡️", "🚬", "⚰️", "🪦", "⚱️", "🏺", "🔮", "🪄", "📿", "🧿",
        "💈", "🔭", "🔬", "🕳️", "🩹", "🩺", "🩻", "🩼", "💊", "💉", "🧬", "🧪",
        "🧫", "🧹", "🪠", "🧺", "🧻", "🚽", "🚰", "🚿", "🛁", "🧼", "🪥", "🧽",
        "🔑", "🗝️", "🚪", "🪑", "🛋️", "🛏️", "🛌", "🧸", "🪆", "🖼️", "🪞", "🪟",
        "🛍️", "🛒", "🎁", "🎈", "🎏", "🎀", "🪄", "✉️", "📦", "🏷️", "📫", "📪",
        "📜", "📄", "📰", "📑", "📊", "📈", "📉", "📁", "📂", "🗂️", "📅", "📆",
        "📋", "📌", "📍", "📎", "🖇️", "📏", "📐", "✂️", "🔒", "🔓", "🔏", "🔐",
        "🖊️", "🖋️", "✒️", "📝", "✏️", "🔍", "🔎"
    ],
    "Symbols": [
        "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕",
        "💞", "💓", "💗", "💖", "💘", "💝", "💟", "☮️", "✝️", "☪️", "🕉️", "☸️",
        "✡️", "🔯", "🕎", "☯️", "☦️", "🛐", "⛎", "♈", "♉", "♊", "♋", "♌",
        "♍", "♎", "♏", "♐", "♑", "♒", "♓", "🆔", "⚛️", "🉑", "☢️", "☣️",
        "📴", "📳", "🈶", "🈚", "🈸", "🈺", "🈷️", "✴️", "VS", "🉐", "㊙️", "㊗️",
        "🈴", "🈵", "🈹", "🈲", "🅰️", "🅱️", "🆎", "🆑", "🅾️", "🆘", "❌", "⭕",
        "🛑", "⛔", "📛", "🚫", "💯", "💢", "♨️", "🚷", "🚯", "🚳", "🚱", "🔞",
        "📵", "🚭", "❗", "❕", "❓", "❔", "‼️", "⁉️", "🔅", "🔆", "〽️", "⚠️",
        "🚸", "🔱", "⚜️", "🔰", "♻️", "✅", "🈯", "💹", "❇️", "✳️", "❎", "🌐",
        "💠", "Ⓜ️", "🌀", "💤", "🏧", "🚾", "♿", "🅿️", "🈳", "🈂️", "🛂", "🛃",
        "🛄", "🛅", "🚹", "🚺", "🚼", "⚧️", "🚻", "🚮", "🎦", "📶", "🈁", "🔣",
        "ℹ️", "🔤", "🔡", "🔠", "🔢", "#️⃣", "*️⃣", "0️⃣", "1️⃣", "2️⃣", "3️⃣",
        "4️⃣", "5️⃣", "6️⃣", "7️⃣", "8️⃣", "9️⃣", "🔟", "▶️", "⏸️", "⏯️", "⏹️",
        "⏺️", "⏭️", "⏮️", "⏩", "⏪", "🔀", "🔁", "🔂", "◀️", "🔼", "🔽", "⏫",
        "⏬", "➡️", "⬅️", "⬆️", "⬇️", "↗️", "↘️", "↙️", "↖️", "↕️", "↔️", "🔄",
        "⤴️", "⤵️", "🔀", "🔁", "🔂", "➕", "➖", "➗", "✖️", "🟰", "♾️", "💲",
        "💱", "™️", "©️", "®️", "👁️‍🗨️", "🔚", "🔙", "🔛", "🔝", "🔜", "✔️", "☑️",
        "🔘", "🔴", "🟠", "🟡", "🟢", "🔵", "🟣", "⚫", "⚪", "🟤", "🔺", "🔻",
        "🔸", "🔹", "🔶", "🔷", "🔳", "🔲", "▪️", "▫️", "◾", "◽", "◼️", "◻️",
        "🟥", "🟧", "🟨", "🟩", "🟦", "🟪", "⬛", "⬜", "🟫"
    ]
}

with gzip.open("app/src/main/assets/emoji_categories.json.gz", "wt", encoding="utf-8") as f:
    json.dump(emoji_categories, f, ensure_ascii=False)

print(f"Generated emoji_categories.json.gz with {len(emoji_categories)} categories")
