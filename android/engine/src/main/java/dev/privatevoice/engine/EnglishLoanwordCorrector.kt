package dev.privatevoice.engine

/**
 * Fixes common English loanwords that get mangled when whisper.cpp decodes
 * as Hindi (whether HI was forced explicitly, or AUTO's language-ID pass
 * detected Hindi — either way `forcedLanguage == "hi"` in the caller).
 *
 * Forcing `language="hi"` (needed for reliable Hindi transcription — see
 * docs/STATUS.md) commits the *entire* decode to Hindi phonetics, so an
 * English loanword mid-sentence ("vacation") isn't recognised as English —
 * it gets transcribed as Devanagari approximating the sound (something like
 * वकेशन), which [DevanagariTransliterator] then faithfully romanizes back
 * into a mangled spelling ("vakeshin"). The transliterator isn't wrong; the
 * Devanagari it was given already was.
 *
 * This is a curated, exact-match dictionary, not a general phonetic
 * matcher — real code-switch detection (recognising *which* word in a
 * Hindi sentence is actually English) is a hard, open ASR problem, and a
 * fuzzy matcher risks "correcting" a real Hindi word into an unrelated
 * English one. Both the dictionary entries and incoming words are reduced
 * to the same simplified phonetic key (vowels dropped, a handful of common
 * consonant/spelling confusions merged) and only replaced on an exact key
 * match — this absorbs the vowel-quality and c/k, s/sh-style variation that
 * accounts for most of the mangling, without guessing at edit distance.
 *
 * Deliberately does NOT include personal names (celebrities, historical
 * figures) or movie titles: those categories are unbounded and much higher
 * collision risk for short proper nouns matching real Hindi words by
 * accident. The right lever for names is [KeyboardSettings]'s "Your name"
 * and contacts hints, which bias the decoder via `initial_prompt` instead
 * of rewriting its output after the fact — a fundamentally safer mechanism
 * for that category. Also does not attempt exhaustive scientific/technical
 * vocabulary, only the common words plausible in casual speech.
 *
 * Expected to need new entries as real mangled spellings show up in use;
 * add them to [LOANWORDS] rather than to any per-word regex.
 */
object EnglishLoanwordCorrector {

    private val LOANWORDS = listOf(
        // Original starter set
        "vacation", "holiday", "trip", "flight", "hotel", "taxi", "driver", "license",
        "doctor", "hospital", "medicine", "mobile", "computer", "laptop", "internet",
        "wifi", "network", "signal", "battery", "charger", "printer", "camera", "photo",
        "video", "movie", "message", "email", "password", "number", "address",
        "office", "meeting", "project", "manager", "company", "business", "customer",
        "service", "salary", "account", "bank", "insurance",
        "school", "college", "exam", "teacher", "student",
        "station", "ticket", "market", "shop", "restaurant", "party", "function",
        "minute", "second", "hour", "weekend",
        "family", "friend", "problem", "tension", "matter",

        // General everyday nouns
        "time", "table", "dictionary", "class", "scope", "range", "chapter", "page",
        "paragraph", "sentence", "subject", "topic", "question", "answer", "result",
        "report", "record", "file", "folder", "document", "letter", "envelope",
        "parcel", "package", "box", "bag", "wallet", "purse", "key", "lock", "door",
        "window", "wall", "floor", "roof", "room", "kitchen", "bathroom", "garden",
        "park", "road", "street", "bridge", "building", "tower", "clinic", "pharmacy",
        "chemist", "calendar", "date", "schedule", "appointment", "deadline", "budget",
        "discount", "percent", "percentage", "average", "total", "balance", "cash",
        "cheque", "receipt", "invoice", "order", "delivery", "courier", "coupon",

        // Mathematical / scientific terms (common, not exhaustive)
        "mathematics", "science", "chemistry", "physics", "biology", "geography",
        "history", "technology", "engineer", "engineering", "temperature", "pressure",
        "energy", "oxygen", "gravity", "electricity", "atom", "molecule", "experiment",
        "laboratory", "theory", "formula", "software", "hardware", "program",
        "programming", "data", "database", "server", "website", "application",
        "browser", "algorithm", "diagram", "graph", "statistics", "calculator",

        // Vehicles / transport
        "car", "bus", "truck", "bike", "motorcycle", "scooter", "cycle", "train",
        "metro", "flight", "airport", "airplane", "helicopter", "ship", "boat",
        "platform", "engine", "petrol", "diesel", "garage", "parking", "traffic",
        "highway", "signal", "vehicle",

        // Common brand names (tech, retail, food)
        "google", "facebook", "instagram", "youtube", "amazon", "flipkart", "netflix",
        "zomato", "swiggy", "uber", "ola", "paytm", "samsung", "apple", "nokia",
        "sony", "nike", "adidas", "pepsi", "starbucks", "mcdonalds", "dominos", "kfc",

        // Countries (common ones likely to come up in casual speech)
        "america", "england", "britain", "china", "japan", "germany", "france",
        "italy", "spain", "russia", "canada", "australia", "brazil", "pakistan",
        "bangladesh", "nepal", "bhutan", "afghanistan", "iran", "iraq", "israel",
        "egypt", "nigeria", "kenya", "mexico", "argentina", "indonesia", "malaysia",
        "singapore", "thailand", "vietnam", "philippines", "turkey", "greece",
        "portugal", "netherlands", "belgium", "switzerland", "austria", "sweden",
        "norway", "denmark", "finland", "poland", "ukraine", "ireland", "scotland",
        "wales", "chile", "peru", "colombia", "cuba", "morocco", "ethiopia", "ghana",
        "dubai", "qatar", "kuwait",
    )

    private val BY_KEY: Map<String, String> = LOANWORDS.associateBy { phoneticKey(it) }

    /**
     * Reduces a word to a rough consonant-and-shape skeleton: normalises a
     * few common English spelling/pronunciation gaps ("-tion"/"-sion"
     * sound like "-shun", "ph" sounds like "f"), merges consonants that
     * frequently get confused between English spelling and Hindi-phonetic
     * transcription (c/k/q/x -> k, z -> s), then drops vowels and collapses
     * doubled letters entirely — vowel *quality* is exactly what's least
     * reliable across this round-trip, so it's discarded rather than
     * matched.
     */
    private fun phoneticKey(word: String): String {
        var w = word.lowercase()
        w = w.replace("tion", "shun").replace("sion", "shun")
        w = w.replace("ph", "f").replace("wh", "w")
        w = w.replace('c', 'k').replace('q', 'k').replace('x', 'k').replace('z', 's')
        w = w.replace(Regex("[aeiou]+"), "")
        w = w.replace(Regex("(.)\\1+"), "$1")
        return w
    }

    fun correct(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (text[i].isLetter()) {
                val start = i
                while (i < text.length && text[i].isLetter()) i++
                val word = text.substring(start, i)
                sb.append(BY_KEY[phoneticKey(word)] ?: word)
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString()
    }
}
