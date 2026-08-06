package dev.privatevoice.engine

/**
 * Latin -> Devanagari, the reverse of [DevanagariTransliterator]. Used by
 * the keyboard's Devanagari-output mode, which renders everything —
 * including English words — in Devanagari script.
 *
 * This is a **grapheme-based phonetic approximation**, not a dictionary
 * lookup: it reads Latin letters as if they were a phonetic transcription
 * (roughly ITRANS-style) and maps them onto the nearest Devanagari
 * consonant/vowel. English spelling is not a reliable guide to English
 * pronunciation ("what" is not "wa" + "t" the way it looks), so this does
 * noticeably better on words that were already romanized Hindi/Hinglish
 * than on native English words with irregular spelling — expect a
 * reasonable, readable approximation, not an authoritative rendering.
 *
 * Dental consonants (त/द/न) are used for the plain t/d/n letters rather than
 * their retroflex counterparts (ट/ड/ण), since dental sounds are far more
 * common in everyday text and nothing in plain Latin spelling distinguishes
 * them (ITRANS-style schemes usually do this with capitalization, which
 * isn't a reliable signal on whisper's natural sentence-casing output).
 */
object LatinToDevanagariTransliterator {

    private val CONSONANTS = listOf(
        "chh" to "छ",
        "kh" to "ख", "gh" to "घ", "ch" to "च", "jh" to "झ",
        "th" to "थ", "dh" to "ध", "ph" to "फ", "sh" to "श",
        "ng" to "ङ", "ny" to "ञ",
        "k" to "क", "g" to "ग", "j" to "ज", "t" to "त", "d" to "द",
        "n" to "न", "p" to "प", "b" to "ब", "m" to "म", "y" to "य",
        "r" to "र", "l" to "ल", "v" to "व", "w" to "व", "s" to "स",
        "h" to "ह", "c" to "क", "q" to "क़", "z" to "ज़", "f" to "फ़",
    ).sortedByDescending { it.first.length }

    private val INDEPENDENT_VOWELS = listOf(
        "aa" to "आ", "ee" to "ई", "ii" to "ई", "oo" to "ऊ", "uu" to "ऊ",
        "ai" to "ऐ", "au" to "औ",
        "a" to "अ", "i" to "इ", "u" to "उ", "e" to "ए", "o" to "ओ",
    ).sortedByDescending { it.first.length }

    // "a" is deliberately absent: a bare consonant's inherent vowel already
    // is "a" — no matra at all is what spells that, so it's handled as its
    // own case rather than as a lookup entry.
    private val MATRAS = mapOf(
        "aa" to "ा", "ee" to "ी", "ii" to "ी", "oo" to "ू", "uu" to "ू",
        "ai" to "ै", "au" to "ौ",
        "i" to "ि", "u" to "ु", "e" to "े", "o" to "ो",
    )
    private val MATRA_KEYS = MATRAS.keys.sortedByDescending { it.length }

    private const val VIRAMA = "्"

    fun toDevanagari(text: String): String {
        val out = StringBuilder(text.length * 2)
        var i = 0
        while (i < text.length) {
            if (text[i].isLetter() && text[i].code < 128) {
                val start = i
                while (i < text.length && text[i].isLetter() && text[i].code < 128) i++
                out.append(convertWord(text.substring(start, i).lowercase()))
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun convertWord(word: String): String {
        val out = StringBuilder()
        var pending: String? = null // Devanagari consonant awaiting a vowel/virama decision
        var i = 0

        fun flush(withVirama: Boolean) {
            pending?.let {
                out.append(it)
                if (withVirama) out.append(VIRAMA)
            }
            pending = null
        }

        while (i < word.length) {
            val consonant = CONSONANTS.firstOrNull { word.startsWith(it.first, i) }
            if (consonant != null) {
                // Nothing but another consonant followed the pending one —
                // no vowel appeared, so they cluster via virama.
                flush(withVirama = true)
                pending = consonant.second
                i += consonant.first.length
                continue
            }

            if (pending != null) {
                val matra = MATRA_KEYS.firstOrNull { word.startsWith(it, i) }
                if (matra != null) {
                    out.append(pending).append(MATRAS.getValue(matra))
                    pending = null
                    i += matra.length
                    continue
                }
                if (word.startsWith("a", i)) {
                    // Bare "a": exactly the consonant's inherent vowel.
                    out.append(pending)
                    pending = null
                    i += 1
                    continue
                }
            }

            val independent = INDEPENDENT_VOWELS.firstOrNull { word.startsWith(it.first, i) }
            if (independent != null) {
                flush(withVirama = false)
                out.append(independent.second)
                i += independent.first.length
                continue
            }

            // Unreachable for pure a-z input (every letter is covered above
            // as at least a single-char consonant or vowel) — kept as a
            // safety net rather than an assumption.
            flush(withVirama = false)
            out.append(word[i])
            i++
        }
        flush(withVirama = false)
        return out.toString()
    }
}
