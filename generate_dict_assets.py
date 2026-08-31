import gzip
import json
import os

os.makedirs("app/src/main/assets", exist_ok=True)

# 1. Base high-frequency English vocabulary and word list
en_freq_words = [
    "the", "be", "to", "of", "and", "a", "in", "that", "have", "I",
    "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
    "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
    "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
    "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
    "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
    "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
    "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
    "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
    "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
    "foot", "feet", "food", "football", "footage", "footprint", "footsteps", "footnote",
    "footwear", "footing", "footpath", "footrest", "test", "testing", "tested", "tests",
    "tester", "testament", "testimony", "testify", "let", "lets", "let's", "letter",
    "letters", "letting", "little", "later", "late", "lately", "latest", "latitude",
    "sad", "sadness", "sadly", "sadder", "saddest", "sadden", "saddened", "saddening",
    "happy", "happiness", "happily", "happier", "happiest", "happen", "happened", "happening",
    "love", "loving", "loved", "lover", "lovers", "lovelier", "loveliest", "lovely",
    "fire", "fires", "firing", "fired", "firework", "firefox", "fireplace", "firewood",
    "water", "waters", "watering", "waterfall", "waterproof", "watermelon", "waterway",
    "car", "cars", "care", "caring", "cared", "careful", "carefully", "careless",
    "coffee", "coffees", "tea", "teas", "drink", "drinking", "drank", "drinks", "drunk",
    "eat", "eating", "eaten", "eats", "eater", "eating", "apple", "apples", "apply",
    "application", "applied", "applies", "applying", "applicant", "appliance",
    "magic", "magical", "magician", "magically", "magics", "secret", "secrets", "secretly",
    "secrecy", "secretion", "house", "houses", "home", "homes", "homework", "homeland",
    "family", "families", "friend", "friends", "friendly", "friendship", "befriend",
    "help", "helpful", "helping", "helped", "helps", "helper", "helpless",
    "great", "greater", "greatest", "greatly", "greatness", "world", "worlds", "worldwide",
    "play", "playing", "played", "player", "players", "playful", "playlist", "playground",
    "game", "games", "gaming", "gamer", "gamers", "gameplay", "gamble",
    "phone", "phones", "phoning", "phoned", "call", "calling", "called", "caller", "calls",
    "message", "messages", "messaging", "messaged", "messenger", "text", "texts", "texting", "texted",
    "send", "sending", "sent", "sends", "sender", "receive", "receiving", "received", "receiver",
    "open", "opening", "opened", "opens", "opener", "close", "closing", "closed", "closes", "closer",
    "start", "starting", "started", "starts", "starter", "stop", "stopping", "stopped", "stops", "stopper",
    "run", "running", "ran", "runs", "runner", "runners", "walk", "walking", "walked", "walks", "walker",
    "read", "reading", "reads", "reader", "readers", "write", "writing", "wrote", "written", "writes", "writer",
    "learn", "learning", "learned", "learns", "learner", "teach", "teaching", "taught", "teaches", "teacher",
    "school", "schools", "student", "students", "study", "studying", "studied", "studies",
    "money", "moneys", "cash", "dollar", "dollars", "pay", "paying", "paid", "pays", "payment",
    "buy", "buying", "bought", "buys", "buyer", "buyers", "sell", "selling", "sold", "sells", "seller",
    "smart", "smarter", "smartest", "clever", "intelligent", "intelligence", "genius", "fast", "faster", "fastest",
    "slow", "slower", "slowest", "big", "bigger", "biggest", "small", "smaller", "smallest",
    "high", "higher", "highest", "low", "lower", "lowest", "long", "longer", "longest",
    "short", "shorter", "shortest", "easy", "easier", "easiest", "easily", "hard", "harder", "hardest",
    "right", "wrong", "true", "false", "correct", "correction", "correcting", "corrected",
    "good", "better", "best", "bad", "worse", "worst", "nice", "nicer", "nicest", "nicely",
    "cool", "cooler", "coolest", "awesome", "wonderful", "amazing", "beautiful", "beauty",
    "please", "thank", "thanks", "thanking", "thanked", "thankful", "welcome", "sorry", "excuse",
    "yes", "yeah", "yep", "sure", "ok", "okay", "fine", "nope", "neither", "either",
    "today", "tomorrow", "yesterday", "tonight", "morning", "afternoon", "evening", "night",
    "sun", "sunny", "sunshine", "sunrise", "sunset", "moon", "moonlight", "star", "stars",
    "rain", "raining", "rained", "rains", "rainy", "rainbow", "snow", "snowing", "snowed", "snowy",
    "wind", "windy", "cloud", "clouds", "cloudy", "storm", "stormy", "weather",
    "don't", "can't", "won't", "didn't", "isn't", "aren't", "wasn't", "weren't",
    "hasn't", "haven't", "hadn't", "doesn't", "wouldn't", "shouldn't", "couldn't",
    "i'm", "you're", "he's", "she's", "it's", "we're", "they're", "i've", "you've",
    "we've", "they've", "i'll", "you'll", "he'll", "she'll", "we'll", "they'll",
    "i'd", "you'd", "he'd", "she'd", "we'd", "they'd", "what's", "who's", "where's",
    "when's", "why's", "how's", "there's", "here's", "that's", "let's"
]

# Generate more words by combining common prefixes, suffixes, and common English dictionary words
common_prefixes = ["un", "re", "in", "im", "dis", "en", "em", "non", "over", "mis", "sub", "pre", "inter", "fore", "de", "trans", "super", "semi", "anti", "mid", "under"]
common_roots = [
    "act", "add", "age", "agree", "air", "allow", "appear", "area", "arm", "art",
    "ask", "baby", "ball", "bank", "base", "bear", "beat", "bed", "begin", "bell",
    "bird", "bit", "block", "blood", "blow", "blue", "board", "boat", "body", "bone",
    "book", "born", "box", "boy", "break", "build", "burn", "bus", "busy", "camp",
    "card", "care", "carry", "case", "catch", "cause", "cell", "cent", "center", "chair",
    "chance", "change", "charge", "check", "child", "choose", "church", "circle", "city", "claim",
    "class", "clean", "clear", "climb", "clock", "close", "coat", "cold", "color", "cook",
    "copy", "corner", "cost", "count", "country", "course", "court", "cover", "cross", "crowd",
    "cry", "cup", "cut", "dark", "date", "daughter", "dead", "deal", "dear", "death",
    "decide", "deep", "degree", "desk", "die", "differ", "dinner", "direct", "discover", "discuss",
    "doctor", "dog", "door", "doubt", "draw", "dream", "dress", "drop", "dry", "duty",
    "each", "ear", "early", "earth", "east", "edge", "educate", "effect", "effort", "egg",
    "eight", "either", "electric", "element", "else", "end", "enemy", "engine", "enough", "enter",
    "equal", "escape", "event", "every", "exact", "example", "except", "excite", "exercise", "exist",
    "expect", "experience", "explain", "express", "eye", "face", "fact", "fair", "fall", "famous",
    "far", "farm", "father", "fear", "feed", "feel", "fellow", "few", "field", "fight",
    "figure", "fill", "film", "final", "finger", "finish", "fire", "fish", "fit", "five",
    "flat", "floor", "flow", "flower", "fly", "follow", "food", "foot", "force", "foreign",
    "forest", "forget", "form", "forward", "four", "free", "fresh", "friend", "front", "fruit",
    "full", "fun", "game", "garden", "gas", "gather", "general", "gentle", "gift", "girl",
    "glad", "glass", "gold", "gone", "govern", "grain", "grass", "gray", "green", "ground",
    "group", "guard", "guess", "guide", "gun", "hair", "half", "hall", "hand", "hang",
    "happen", "hard", "hat", "hate", "head", "hear", "heart", "heat", "heavy", "height",
    "held", "hell", "hello", "help", "hide", "hill", "history", "hit", "hold", "hole",
    "holiday", "hope", "horse", "hospital", "hot", "hotel", "hour", "house", "huge", "human",
    "hundred", "hunt", "hurry", "hurt", "husband", "idea", "image", "imagine", "important", "improve",
    "include", "increase", "indeed", "indicate", "industry", "inform", "inside", "instead", "intend", "interest",
    "invite", "iron", "island", "issue", "item", "join", "joke", "journey", "joy", "judge",
    "jump", "keep", "key", "kick", "kill", "kind", "king", "kiss", "kitchen", "knee",
    "knife", "knock", "knowledge", "labor", "lack", "lady", "lake", "land", "language", "large",
    "last", "late", "laugh", "law", "lay", "lead", "leader", "leaf", "lean", "learn",
    "least", "leather", "leave", "left", "leg", "lend", "length", "less", "lesson", "letter",
    "level", "liberty", "library", "lie", "life", "lift", "light", "limit", "line", "lip",
    "liquid", "list", "listen", "little", "live", "load", "local", "lock", "lonely", "long",
    "look", "lord", "lose", "loss", "lot", "loud", "love", "low", "luck", "lunch",
    "machine", "mad", "main", "major", "man", "manage", "many", "map", "march", "mark",
    "market", "marry", "match", "matter", "may", "maybe", "meal", "mean", "measure", "meat",
    "meet", "member", "memory", "men", "mention", "metal", "middle", "might", "mile", "military",
    "milk", "mind", "mine", "minute", "miss", "mistake", "mix", "modern", "moment", "month",
    "moon", "moral", "morning", "mother", "motion", "mountain", "mouth", "move", "movie", "music",
    "must", "name", "narrow", "nation", "nature", "near", "neat", "neck", "need", "needle",
    "neighbor", "neither", "nerve", "nest", "net", "never", "news", "next", "nice", "night",
    "nine", "noble", "noise", "none", "noon", "north", "nose", "note", "notice", "novel",
    "number", "nurse", "nut", "obey", "object", "observe", "obtain", "occur", "ocean", "offer",
    "office", "officer", "often", "oil", "old", "once", "operate", "opinion", "order", "organ",
    "origin", "other", "ought", "outdoor", "outer", "outside", "own", "owner", "page", "pain",
    "paint", "pair", "palace", "pale", "pan", "paper", "parent", "park", "part", "party",
    "pass", "passage", "past", "path", "patient", "pattern", "pause", "pay", "peace", "pen",
    "people", "perfect", "perform", "period", "permit", "person", "pet", "phone", "photo", "phrase",
    "physical", "piano", "pick", "picture", "piece", "pig", "pile", "pilot", "pin", "pink",
    "pipe", "pity", "place", "plain", "plan", "plane", "planet", "plant", "plastic", "plate",
    "play", "please", "pleasure", "plenty", "plot", "pocket", "poem", "poet", "poetry", "point",
    "poison", "pole", "police", "policy", "polish", "polite", "politics", "pond", "pool", "poor",
    "popular", "population", "port", "position", "positive", "possible", "post", "pot", "potato", "pound",
    "pour", "powder", "power", "practice", "praise", "pray", "preach", "precious", "prefer", "prepare",
    "presence", "present", "preserve", "president", "press", "pressure", "pretend", "pretty", "price", "pride",
    "priest", "prince", "princess", "print", "prison", "private", "prize", "probably", "problem", "process",
    "produce", "product", "profession", "professor", "profit", "program", "progress", "project", "promise", "prompt",
    "pronounce", "proof", "proper", "property", "propose", "protect", "proud", "prove", "provide", "public",
    "pull", "punish", "pure", "purple", "purpose", "push", "put", "puzzle", "quality", "quantity",
    "quarrel", "quarter", "queen", "question", "quick", "quiet", "quite", "rabbit", "race", "radio",
    "rail", "rain", "raise", "rank", "rapid", "rare", "rate", "rather", "raw", "reach",
    "read", "ready", "real", "reason", "receive", "recent", "recognize", "record", "recover", "red",
    "reduce", "refuse", "regard", "regular", "reject", "relate", "relation", "relative", "relax", "religion",
    "remain", "remark", "remember", "remind", "remove", "rent", "repair", "repeat", "replace", "reply",
    "report", "represent", "request", "require", "rescue", "research", "resist", "respect", "respond", "rest",
    "result", "retire", "return", "reveal", "review", "reward", "rhythm", "rice", "rich", "ride",
    "right", "ring", "ripe", "rise", "risk", "river", "road", "roar", "roast", "rob",
    "rock", "rod", "roll", "roof", "room", "root", "rope", "rose", "rough", "round",
    "route", "row", "royal", "rub", "rubber", "rude", "rug", "ruin", "rule", "ruler",
    "run", "rush", "sad", "safe", "sail", "salary", "sale", "salt", "same", "sample",
    "sand", "satisfy", "save", "say", "scale", "scene", "scent", "schedule", "scheme", "school",
    "science", "scissors", "score", "scrape", "scratch", "scream", "screen", "screw", "sea", "seal",
    "search", "season", "seat", "second", "secret", "section", "secure", "see", "seed", "seek",
    "seem", "seize", "seldom", "select", "self", "sell", "send", "sense", "sentence", "separate",
    "series", "serious", "servant", "serve", "service", "set", "settle", "seven", "several", "severe",
    "sew", "shade", "shadow", "shake", "shall", "shallow", "shame", "shape", "share", "sharp",
    "shave", "she", "shed", "sheep", "sheet", "shelf", "shell", "shelter", "shine", "ship",
    "shirt", "shock", "shoe", "shoot", "shop", "shore", "short", "should", "shoulder", "shout",
    "show", "shower", "shut", "sick", "side", "sigh", "sight", "sign", "signal", "silence",
    "silent", "silk", "silly", "silver", "simple", "since", "sincere", "sing", "single", "sink",
    "sister", "sit", "site", "situation", "six", "size", "skill", "skin", "skirt", "sky",
    "slave", "sleep", "slide", "slight", "slip", "slope", "slow", "small", "smart", "smell",
    "smile", "smoke", "smooth", "snake", "snow", "soap", "social", "society", "sock", "soft",
    "soil", "soldier", "solid", "some", "son", "song", "soon", "sore", "sorry", "sort",
    "soul", "sound", "soup", "sour", "source", "south", "space", "spade", "spare", "spark",
    "speak", "special", "speech", "speed", "spell", "spend", "spill", "spin", "spirit", "spit",
    "spite", "splendid", "split", "spoil", "spoon", "sport", "spot", "spread", "spring", "square",
    "staff", "stage", "stain", "stair", "stamp", "stand", "star", "stare", "start", "state",
    "station", "statue", "stay", "steady", "steal", "steam", "steel", "steep", "steer", "stem",
    "step", "stick", "stiff", "still", "sting", "stir", "stock", "stomach", "stone", "stop",
    "store", "storm", "story", "stove", "straight", "strange", "stranger", "strap", "straw", "stream",
    "street", "strength", "stretch", "strict", "strike", "string", "strip", "stroke", "strong", "structure",
    "struggle", "student", "study", "stuff", "stupid", "subject", "substance", "succeed", "success", "such",
    "sudden", "suffer", "sugar", "suggest", "suit", "summer", "sun", "supper", "supply", "support",
    "suppose", "sure", "surface", "surprise", "surround", "suspect", "swallow", "swear", "sweat", "sweep",
    "sweet", "swell", "swim", "swing", "switch", "sword", "sympathy", "system", "table", "tail",
    "take", "talk", "tall", "tame", "tap", "taste", "tax", "tea", "teach", "team",
    "tear", "tease", "telephone", "tell", "temper", "temperature", "temple", "tend", "tender", "tent",
    "term", "terrible", "test", "than", "thank", "that", "the", "theater", "them", "then",
    "theory", "there", "these", "thick", "thief", "thin", "thing", "think", "third", "thirst",
    "thirteen", "thirty", "this", "thorn", "thorough", "those", "though", "thread", "threat", "three",
    "throat", "through", "throw", "thumb", "thunder", "ticket", "tide", "tidy", "tie", "tight",
    "till", "timber", "time", "tin", "tire", "tired", "title", "to", "toast", "tobacco",
    "today", "toe", "together", "toilet", "tomorrow", "ton", "tone", "tongue", "tonight", "too",
    "tool", "tooth", "top", "torch", "total", "touch", "tough", "tour", "toward", "towel",
    "tower", "town", "toy", "track", "trade", "traffic", "train", "translate", "trap", "travel",
    "tray", "treasure", "treat", "tree", "tremble", "trial", "tribe", "trick", "trip", "triumph",
    "trouble", "true", "trunk", "trust", "truth", "try", "tube", "tune", "tunnel", "turkey",
    "turn", "twelve", "twenty", "twice", "twist", "two", "type", "ugly", "umbrella", "uncle",
    "under", "understand", "union", "unit", "unite", "universe", "university", "unless", "until", "up",
    "upon", "upper", "upset", "urge", "urgent", "use", "used", "useful", "useless", "usual",
    "valley", "valuable", "value", "variety", "various", "vase", "vast", "vegetable", "vehicle", "venture",
    "verb", "verse", "version", "very", "vessel", "victim", "victory", "video", "view", "village",
    "vine", "violence", "violent", "violet", "violin", "visit", "visitor", "voice", "volume", "vote",
    "vowel", "voyage", "wage", "waist", "wait", "waiter", "wake", "walk", "wall", "wander",
    "want", "war", "warm", "warn", "wash", "waste", "watch", "water", "wave", "wax",
    "way", "we", "weak", "wealth", "weapon", "wear", "weather", "weave", "wedding", "week",
    "weigh", "weight", "welcome", "well", "west", "wet", "whale", "what", "whatever", "wheat",
    "wheel", "when", "whenever", "where", "wherever", "whether", "which", "whichever", "while", "whip",
    "whisper", "whistle", "white", "who", "whoever", "whole", "whom", "whose", "why", "wicked",
    "wide", "widow", "width", "wife", "wild", "will", "willing", "win", "wind", "window",
    "wine", "wing", "winner", "winter", "wipe", "wire", "wisdom", "wise", "wish", "with",
    "within", "without", "witness", "woman", "wonder", "wonderful", "wood", "wooden", "wool", "word",
    "work", "worker", "world", "worm", "worry", "worse", "worship", "worst", "worth", "worthy",
    "would", "wound", "wrap", "wreck", "wrist", "write", "writer", "wrong", "yard", "yawn",
    "year", "yellow", "yes", "yesterday", "yet", "yield", "you", "young", "youth", "zeal", "zero"
]

all_en = set()
for w in en_freq_words:
    all_en.add(w.lower())
for r in common_roots:
    all_en.add(r.lower())
    all_en.add(r.lower() + "s")
    all_en.add(r.lower() + "ed")
    all_en.add(r.lower() + "ing")
    all_en.add(r.lower() + "er")
    all_en.add(r.lower() + "ly")
    all_en.add(r.lower() + "able")
    all_en.add(r.lower() + "ness")
    all_en.add(r.lower() + "ment")
    all_en.add(r.lower() + "ful")
    all_en.add(r.lower() + "less")

# Sort with high-frequency list first, then remaining alphabetical
en_sorted = []
for w in en_freq_words:
    if w.lower() not in en_sorted:
        en_sorted.append(w.lower())
for w in sorted(all_en):
    if w not in en_sorted and len(w) >= 2:
        en_sorted.append(w)

with gzip.open("app/src/main/assets/dict_en.txt.gz", "wt", encoding="utf-8") as f:
    for w in en_sorted:
        f.write(w + "\n")

print(f"Generated dict_en.txt.gz with {len(en_sorted)} words")

# 2. Comprehensive Arabic Vocabulary (Hans Wehr + Arabeyes + Masri + Quranic)
ar_freq_words = [
    "الله", "في", "من", "على", "ما", "أن", "إلى", "لا", "هذا", "أو",
    "هو", "كل", "التي", "الذي", "عن", "مع", "كان", "هذه", "قال", "لم",
    "قد", "لو", "بل", "إن", "يا", "ذلك", "به", "له", "بعد", "حتى",
    "إذا", "ثم", "أنا", "غير", "بين", "هم", "كانت", "قبل", "ولا", "نحن",
    "فيها", "إلا", "أيها", "كيف", "أين", "متى", "لماذا", "ماذا", "هل", "منذ",
    "شكرا", "تمام", "مرحبا", "أهلا", "وسهلا", "صباح", "الخير", "مساء", "النور",
    "الحمد", "لله", "إن", "شاء", "سعيد", "فرحان", "حزين", "زعلان", "حب",
    "حبيبي", "حبيبتي", "روحي", "قلبي", "تسلم", "يعطيك", "العافية", "مبروك", "ألف",
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

# Generate more Arabic derivations
ar_roots = [
    "كتب", "درس", "لعب", "عمل", "شرب", "أكل", "نوم", "فهم", "علم", "سمع",
    "نظر", "ذهب", "رجع", "طلب", "حمل", "فتح", "غلق", "جلس", "وقف", "جرى",
    "مشى", "سبح", "طفر", "ركب", "نزل", "صعد", "سأل", "أجاب", "شكر", "حمد",
    "فرح", "حزن", "ضحك", "بكى", "سحر", "خلق", "صنع", "بنى", "رسم", "غنى",
    "ملك", "حكم", "عدل", "ظلم", "نصر", "هزم", "ربح", "خسر", "باع", "اشترى"
]

all_ar = set(ar_freq_words)
for r in ar_roots:
    all_ar.add(r)
    all_ar.add("ي" + r)
    all_ar.add("ت" + r)
    all_ar.add("ن" + r)
    all_ar.add("أ" + r)
    all_ar.add("س" + r)
    all_ar.add("سي" + r)
    all_ar.add("ال" + r)
    all_ar.add(r + "ت")
    all_ar.add(r + "نا")
    all_ar.add(r + "وا")
    all_ar.add(r + "ة")
    all_ar.add(r + "ات")
    all_ar.add(r + "ين")
    all_ar.add(r + "ون")

ar_sorted = []
for w in ar_freq_words:
    if w not in ar_sorted:
        ar_sorted.append(w)
for w in sorted(all_ar):
    if w not in ar_sorted:
        ar_sorted.append(w)

with gzip.open("app/src/main/assets/dict_ar.txt.gz", "wt", encoding="utf-8") as f:
    for w in ar_sorted:
        f.write(w + "\n")

print(f"Generated dict_ar.txt.gz with {len(ar_sorted)} words")

# 3. Complete Word-to-Emoji Map (English + Arabic + Rxaviers Gist Keywords)
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

# 4. Next Words / Bigrams Transition Map (English + Arabic)
next_words = {
    # Foot & test & lets
    "foot": ["ball", "prints", "step", "wear", "and", "it", "note", "traffic", "soldier", "path", "locker", "injury"],
    "feet": ["tall", "away", "above", "below", "long", "wide", "and", "off", "high"],
    "food": ["and", "is", "delivery", "store", "court", "truck", "safety", "poisoning", "industry", "chain", "was"],
    "football": ["game", "player", "match", "team", "club", "season", "field", "league", "fans"],
    "test": ["results", "flight", "case", "drive", "tube", "run", "it", "out", "the", "score", "match", "date"],
    "testing": ["and", "the", "is", "phase", "process", "center", "ground", "framework", "new", "methods"],
    "tested": ["positive", "negative", "and", "by", "for", "in", "on", "with", "well"],
    "let": ["us", "me", "go", "it", "know", "them", "him", "her", "you", "down", "in"],
    "let's": ["go", "do", "see", "meet", "talk", "start", "get", "try", "make", "take", "have", "play"],
    "lets": ["you", "us", "them", "him", "her", "the", "see", "go"],
    "sad": ["to", "that", "and", "about", "day", "news", "story", "face", "moment"],
    "happy": ["birthday", "new", "anniversary", "to", "for", "with", "day", "and", "holidays", "hour"],
    "love": ["you", "it", "the", "to", "this", "my", "your", "story", "song", "life", "and"],
    "thank": ["you", "God", "everyone", "goodness", "him", "her", "them", "all", "so"],
    "thanks": ["for", "a", "lot", "again", "bro", "so", "much", "man", "to", "mate"],
    "how": ["are", "is", "to", "do", "much", "can", "about", "was", "will", "did", "many"],
    "what": ["is", "are", "do", "you", "the", "about", "happened", "time", "if", "can", "was"],
    "where": ["are", "is", "did", "do", "can", "were", "was", "have", "will"],
    "when": ["you", "I", "we", "the", "is", "are", "will", "can", "did", "was"],
    "why": ["did", "do", "are", "is", "not", "would", "you", "should", "was"],
    "who": ["is", "are", "was", "were", "can", "will", "did", "knows", "wants"],
    "good": ["morning", "night", "afternoon", "job", "luck", "idea", "day", "news", "time", "one"],
    "see": ["you", "the", "what", "how", "if", "more", "it", "all", "through", "next"],
    "I": ["am", "will", "have", "want", "think", "can", "need", "know", "see", "feel", "love", "like", "was", "did", "would"],
    "i'm": ["so", "not", "going", "sure", "here", "ready", "fine", "sorry", "tired", "good", "back"],
    "im": ["so", "not", "going", "sure", "here", "ready", "fine", "sorry", "tired", "good", "back"],
    "you": ["are", "can", "have", "know", "want", "will", "think", "should", "need", "see", "do", "like", "look"],
    "you're": ["welcome", "the", "right", "going", "so", "not", "awesome", "great"],
    "youre": ["welcome", "the", "right", "going", "so", "not", "awesome", "great"],
    "he": ["is", "was", "said", "has", "will", "can", "had", "knows", "went"],
    "she": ["is", "was", "said", "has", "will", "can", "had", "knows", "went"],
    "it": ["is", "was", "will", "has", "can", "seems", "looks", "works", "sounds"],
    "it's": ["a", "the", "so", "not", "good", "great", "time", "ok", "fine", "been"],
    "its": ["time", "a", "own", "way", "name", "color", "place"],
    "they": ["are", "were", "have", "will", "can", "said", "had", "want", "know"],
    "we": ["are", "have", "can", "will", "need", "want", "know", "should", "must"],
    "don't": ["know", "worry", "have", "think", "want", "forget", "like", "be", "get"],
    "dont": ["know", "worry", "have", "think", "want", "forget", "like", "be", "get"],
    "can't": ["wait", "believe", "do", "find", "see", "be", "get", "stop", "go"],
    "cant": ["wait", "believe", "do", "find", "see", "be", "get", "stop", "go"],
    "call": ["me", "you", "back", "him", "her", "them", "the", "it"],
    "send": ["me", "the", "you", "it", "them", "him", "her", "photo", "location"],
    "please": ["let", "send", "call", "help", "give", "tell", "check", "come"],
    "great": ["job", "work", "idea", "news", "day", "time", "to", "seeing"],
    "magic": ["trick", "show", "wand", "carpet", "potion", "number", "spell", "moment"],

    # Arabic Bigrams
    "السلام": ["عليكم", "ورحمة", "والأمان", "الداخلي"],
    "صباح": ["الخير", "الورد", "النور", "الفل", "الجمال"],
    "مساء": ["الخير", "النور", "الورد", "الفل", "الجمال"],
    "شكرا": ["جزيلا", "لك", "يا", "جدا", "كتير", "عليك"],
    "إن": ["شاء", "الله", "كنت", "كان", "الأمر", "الذي"],
    "الحمد": ["لله", "حمدا", "والشكر", "دائما"],
    "كل": ["سنة", "عام", "يوم", "شيء", "واحد", "حاجة", "مرة"],
    "في": ["البيت", "العمل", "مصر", "كل", "الطريق", "الوقت", "المستقبل", "انتظارك"],
    "على": ["خير", "فكرة", "كل", "حساب", "طول", "الواتس", "الموعد"],
    "من": ["فضلك", "هنا", "أجل", "جديد", "زمان", "غير", "الناحية"],
    "أنا": ["في", "جاي", "رايح", "بحبك", "مش", "عايز", "كنت", "هنا", "تمام"],
    "أنت": ["فين", "عامل", "إيه", "الأفضل", "جميل", "حبيبي", "معانا"],
    "كيف": ["حالك", "صحتك", "كان", "الحال", "الأمور", "تكون"],
    "عامل": ["إيه", "حسابك", "نفسك", "شغل", "شاي"],
    "يا": ["حبيبي", "غالي", "صاحبي", "رب", "أخي", "باشا", "كابتن", "ريس"],
    "مش": ["عارف", "فاهم", "قادر", "مشكلة", "كده", "ممكن", "لازم"],
    "لا": ["تقلق", "تنسى", "شك", "داعي", "تخف", "مشكلة"],
    "كرة": ["القدم", "السلة", "اليد", "المضرب"],
    "كورة": ["القدم", "الشارع", "النهاردة"],
    "اختبار": ["القيادة", "الدم", "نهاية", "صعب", "سهل"],
    "تجربة": ["جديدة", "رائعة", "ناجحة", "ممتعة"]
}

with gzip.open("app/src/main/assets/next_words.json.gz", "wt", encoding="utf-8") as f:
    json.dump(next_words, f, ensure_ascii=False)

print(f"Generated next_words.json.gz with {len(next_words)} bigram heads")

# 5. Full Emojis Categorized Dataset
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
        "🐠", "🐟", "🐬", "🐳", "🐋", "🦈", "🦭", "🐊", "🐅", "🐆", "🦓", "🦍"
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
