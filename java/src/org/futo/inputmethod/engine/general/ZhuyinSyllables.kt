package org.futo.inputmethod.engine.general

/**
 * Splits a Zhuyin (Bopomofo) string into syllables and spells each one the way the
 * luna_pinyin dictionary does: no tones, ü written as `v` after n/l and as `u` after j/q/x,
 * ㄩㄥ with an initial written `iong`, standalone ㄝ written `eh`.
 *
 * A syllable is, in order, at most one initial, one medial (ㄧㄨㄩ), one final and one tone
 * mark. A symbol that cannot continue the current syllable starts a new one; a tone mark
 * always ends the current syllable and is not part of the output.
 */
object ZhuyinSyllables {
    private val INITIALS = mapOf(
        'ㄅ' to "b", 'ㄆ' to "p", 'ㄇ' to "m", 'ㄈ' to "f",
        'ㄉ' to "d", 'ㄊ' to "t", 'ㄋ' to "n", 'ㄌ' to "l",
        'ㄍ' to "g", 'ㄎ' to "k", 'ㄏ' to "h",
        'ㄐ' to "j", 'ㄑ' to "q", 'ㄒ' to "x",
        'ㄓ' to "zh", 'ㄔ' to "ch", 'ㄕ' to "sh", 'ㄖ' to "r",
        'ㄗ' to "z", 'ㄘ' to "c", 'ㄙ' to "s",
    )

    /** Initials that form a syllable on their own (spelled with a trailing `i`). */
    private const val SYLLABIC_INITIALS = "ㄓㄔㄕㄖㄗㄘㄙ"

    private const val MEDIALS = "ㄧㄨㄩ"

    private val FINALS = mapOf(
        'ㄚ' to "a", 'ㄛ' to "o", 'ㄜ' to "e", 'ㄝ' to "eh",
        'ㄞ' to "ai", 'ㄟ' to "ei", 'ㄠ' to "ao", 'ㄡ' to "ou",
        'ㄢ' to "an", 'ㄣ' to "en", 'ㄤ' to "ang", 'ㄥ' to "eng", 'ㄦ' to "er",
    )

    private const val TONES = "ˉˊˇˋ˙"

    /** Medial + final (null = no final) without an initial. */
    private val STANDALONE_MEDIAL: Map<Char, Map<Char?, String>> = mapOf(
        'ㄧ' to mapOf(
            null to "yi", 'ㄚ' to "ya", 'ㄛ' to "yo", 'ㄝ' to "ye", 'ㄞ' to "yai", 'ㄠ' to "yao",
            'ㄡ' to "you", 'ㄢ' to "yan", 'ㄣ' to "yin", 'ㄤ' to "yang", 'ㄥ' to "ying",
        ),
        'ㄨ' to mapOf(
            null to "wu", 'ㄚ' to "wa", 'ㄛ' to "wo", 'ㄞ' to "wai", 'ㄟ' to "wei",
            'ㄢ' to "wan", 'ㄣ' to "wen", 'ㄤ' to "wang", 'ㄥ' to "weng",
        ),
        'ㄩ' to mapOf(
            null to "yu", 'ㄝ' to "yue", 'ㄢ' to "yuan", 'ㄣ' to "yun", 'ㄥ' to "yong",
        ),
    )

    /** Medial + final (null = no final) after an initial. ㄩ is spelled `v` here; see [spell]. */
    private val MEDIAL_AFTER_INITIAL: Map<Char, Map<Char?, String>> = mapOf(
        'ㄧ' to mapOf(
            null to "i", 'ㄚ' to "ia", 'ㄛ' to "io", 'ㄝ' to "ie", 'ㄞ' to "iai", 'ㄠ' to "iao",
            'ㄡ' to "iu", 'ㄢ' to "ian", 'ㄣ' to "in", 'ㄤ' to "iang", 'ㄥ' to "ing",
        ),
        'ㄨ' to mapOf(
            null to "u", 'ㄚ' to "ua", 'ㄛ' to "uo", 'ㄞ' to "uai", 'ㄟ' to "ui",
            'ㄢ' to "uan", 'ㄣ' to "un", 'ㄤ' to "uang", 'ㄥ' to "ong",
        ),
        'ㄩ' to mapOf(
            null to "v", 'ㄝ' to "ve", 'ㄢ' to "van", 'ㄣ' to "vn", 'ㄥ' to "iong",
        ),
    )

    private const val YU_AS_U_INITIALS = "ㄐㄑㄒ"
    private const val YU_AS_V_INITIALS = "ㄋㄌ"

    private class Syllable {
        var initial: Char? = null
        var medial: Char? = null
        var final: Char? = null
        val isEmpty get() = initial == null && medial == null && final == null
    }

    /**
     * @return the luna_pinyin spelling of every syllable in [zhuyin], separated by single
     * spaces, or null if [zhuyin] is empty, contains a non-Zhuyin character, or any
     * syllable is not a valid Mandarin syllable.
     */
    fun toLunaPinyin(zhuyin: String): String? {
        val syllables = mutableListOf<Syllable>()
        var current = Syllable()

        fun push() {
            syllables.add(current)
            current = Syllable()
        }

        for (c in zhuyin) {
            when {
                c in INITIALS -> {
                    if (!current.isEmpty) push()
                    current.initial = c
                }
                c in MEDIALS -> {
                    if (current.medial != null || current.final != null) push()
                    current.medial = c
                }
                c in FINALS -> {
                    if (current.final != null) push()
                    current.final = c
                }
                c in TONES -> {
                    if (current.isEmpty) return null
                    push()
                }
                else -> return null
            }
        }
        if (!current.isEmpty) push()
        if (syllables.isEmpty()) return null

        // map is inline, so the non-local return works; joinToString's lambda is not.
        val spelled = syllables.map { spell(it) ?: return null }
        return spelled.joinToString(" ")
    }

    private fun spell(s: Syllable): String? {
        val initial = s.initial
        val medial = s.medial
        val final = s.final

        if (final == 'ㄦ' && (initial != null || medial != null)) return null

        if (initial == null) {
            if (medial == null) return FINALS[final ?: return null]
            return STANDALONE_MEDIAL.getValue(medial)[final]
        }

        val initialPinyin = INITIALS.getValue(initial)
        if (medial == null) {
            if (final == null) {
                return if (initial in SYLLABIC_INITIALS) initialPinyin + "i" else null
            }
            return initialPinyin + FINALS.getValue(final)
        }

        val rime = MEDIAL_AFTER_INITIAL.getValue(medial)[final] ?: return null
        if (medial == 'ㄩ') {
            return when (initial) {
                in YU_AS_U_INITIALS -> initialPinyin + rime.replaceFirst('v', 'u')
                in YU_AS_V_INITIALS -> initialPinyin + rime
                else -> null
            }
        }
        return initialPinyin + rime
    }
}
