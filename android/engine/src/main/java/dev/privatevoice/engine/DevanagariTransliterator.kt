package dev.privatevoice.engine

/**
 * Devanagari -> Latin romanization, so Hindi (and the Hindi half of
 * code-switched Hinglish) shows up as Latin letters in the text field
 * instead of Devanagari script — this is a display-script choice, not a
 * translation: the words themselves are unchanged, just re-spelled.
 *
 * [toLatin] is a no-op on any character it doesn't recognise, which makes it
 * safe to run unconditionally on every transcription result rather than
 * gating it on the backend's (unreliable, whole-utterance) detected
 * language — a pure-English utterance passes through untouched, and a
 * code-switched one gets exactly its Devanagari runs converted, which is
 * what "Hinglish" actually looks like typed out.
 *
 * Includes Hindi's schwa deletion ("कमल" is pronounced/spelled "kamal", not
 * "kamala" — the inherent vowel on a bare consonant is silent in that
 * position even though nothing in the script marks it). An earlier version
 * of this deleted based on a syllable's position *among schwa-bearing
 * consonants only*, which broke on words where the first schwa candidate
 * isn't the word's first syllable (e.g. "देश" has द+े, an explicit-vowel
 * syllable, before श — the old code still "protected" श as if it were
 * word-initial, producing "desha" instead of "desh"). This version scans
 * every syllable of the word right-to-left, alternating deletion off a
 * running state that any explicit-vowel syllable resets — so protection
 * only ever applies to an actual word-initial schwa, and a syllable that
 * already has its own vowel correctly breaks the alternation for whatever
 * comes before it. Verified against "कमल"->kamal, "देश"->desh, and
 * "समझना"->samajhnaa (where बीच का "म" survives because झ, immediately
 * to its right, gets deleted). Still a heuristic, not a full phonological
 * model — Sanskrit tatsama loanwords that resist regular deletion (नमस्ते
 * stays close to "namaste" in real usage, this predicts "namste") are a
 * known, accepted miss; published versions of this algorithm report
 * ~85-90% accuracy, not 100%.
 */
object DevanagariTransliterator {

    private val INDEPENDENT_VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ऋ' to "ri", 'ॠ' to "ree", 'ऌ' to "lri", 'ॡ' to "lree",
        'ऎ' to "e", 'ए' to "e", 'ऐ' to "ai", 'ऒ' to "o", 'ओ' to "o", 'औ' to "au",
        'ॲ' to "a", 'ऍ' to "ae", 'ऑ' to "aw",
    )

    private val MATRAS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
        'ृ' to "ri", 'ॄ' to "ree", 'ॢ' to "lri", 'ॣ' to "lree",
        'ॆ' to "e", 'े' to "e", 'ै' to "ai", 'ॊ' to "o", 'ो' to "o", 'ौ' to "au",
        'ॅ' to "ae", 'ॉ' to "aw",
    )

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'ळ' to "l",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )

    // Nukta consonants (क़, ख़, ग़, ज़, ड़, ढ़, फ़, य़) aren't single Unicode
    // codepoints — they're the base consonant above plus a combining nukta
    // sign (U+093C), always two chars. Keyed by the base consonant; applied
    // when NUKTA immediately follows it. Informal Hinglish typing almost
    // always collapses these to a plain Latin spelling rather than a
    // diacritic, which is what the values below reflect.
    private val NUKTA_CONSONANTS = mapOf(
        'क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z",
        'ड' to "r", 'ढ' to "rh", 'फ' to "f", 'य' to "y",
    )

    private const val NUKTA = '़'
    private const val VIRAMA = '्'
    private const val ANUSVARA = 'ं'
    private const val CHANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'
    private const val AVAGRAHA = 'ऽ'
    private const val ZWJ = '‍'
    private const val ZWNJ = '‌'
    private const val DANDA = '।'
    private const val DOUBLE_DANDA = '॥'

    private val DIGITS = mapOf(
        '०' to '0', '१' to '1', '२' to '2', '३' to '3', '४' to '4',
        '५' to '5', '६' to '6', '७' to '7', '८' to '8', '९' to '9',
    )

    /** One syllable-ish chunk of a word: fixed text, or a bare consonant whose trailing schwa is still undecided. */
    private class Segment(val text: String, val isSchwaCandidate: Boolean)

    fun toLatin(text: String): String {
        val out = StringBuilder(text.length + text.length / 2)
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                val start = i
                while (i < text.length && text[i].isWhitespace()) i++
                out.append(text, start, i)
            } else {
                val start = i
                while (i < text.length && !text[i].isWhitespace()) i++
                out.append(transliterateWord(text, start, i))
            }
        }
        return out.toString()
    }

    private fun transliterateWord(text: String, from: Int, to: Int): String {
        val segments = mutableListOf<Segment>()
        var i = from
        while (i < to) {
            val c = text[i]

            if (c == ZWJ || c == ZWNJ) {
                i++
                continue
            }

            val consonant = CONSONANTS[c]
            if (consonant != null) {
                var j = i + 1
                val hasNukta = j < to && text[j] == NUKTA
                val base = if (hasNukta) (NUKTA_CONSONANTS[c] ?: consonant) else consonant
                if (hasNukta) j++

                val next = if (j < to) text[j] else null
                when {
                    next == VIRAMA -> {
                        segments += Segment(base, isSchwaCandidate = false)
                        j++
                    }
                    next != null && MATRAS.containsKey(next) -> {
                        segments += Segment(base + MATRAS.getValue(next), isSchwaCandidate = false)
                        j++
                    }
                    else -> segments += Segment(base, isSchwaCandidate = true)
                }
                i = j
                continue
            }

            val vowel = INDEPENDENT_VOWELS[c]
            val digit = DIGITS[c]
            val fixed = when {
                vowel != null -> vowel
                digit != null -> digit.toString()
                c == ANUSVARA || c == CHANDRABINDU -> "n"
                c == VISARGA -> "h"
                c == AVAGRAHA -> "'"
                c == DANDA || c == DOUBLE_DANDA -> "."
                else -> c.toString()
            }
            segments += Segment(fixed, isSchwaCandidate = false)
            i++
        }

        // Right-to-left pass, alternating deletion. A virtual "kept" state
        // just past the end of the word makes the last schwa candidate
        // delete by default (word-final schwa is the base case); any
        // segment that already carries its own vowel resets the state to
        // "kept" for whatever's to its left, which is what makes this
        // syllable-scoped rather than candidate-scoped. Word-initial always
        // wins regardless of what the alternation would otherwise say.
        val keepSchwa = BooleanArray(segments.size) { true }
        var rightKept = true
        for (idx in segments.indices.reversed()) {
            val seg = segments[idx]
            if (!seg.isSchwaCandidate) {
                rightKept = true
                continue
            }
            val keep = if (idx == 0) true else !rightKept
            keepSchwa[idx] = keep
            rightKept = keep
        }

        val sb = StringBuilder()
        for ((idx, seg) in segments.withIndex()) {
            sb.append(seg.text)
            if (seg.isSchwaCandidate && keepSchwa[idx]) sb.append('a')
        }
        return sb.toString()
    }
}
