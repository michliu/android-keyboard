package org.futo.inputmethod.engine.general

/** One Zhuyin syllable: its symbols without the tone mark, and its tone (1–4, 5 = neutral). */
data class ZhuyinSyllable(val base: String, val tone: Int)

/**
 * Reads Rime's Zhuyin preedit, where syllables are separated by spaces (e.g. "ㄍㄨㄥˉ ㄩㄢˊ").
 * Rime always shows a tone mark, including ˉ for tone 1, so a syllable without one means the
 * tone was not typed (abbreviated input).
 */
object ZhuyinReading {
    private val TONES = mapOf('ˉ' to 1, 'ˊ' to 2, 'ˇ' to 3, 'ˋ' to 4, '˙' to 5)

    /** Zhuyin letters ㄅ (U+3105) to ㄩ (U+3129). */
    private fun isZhuyinLetter(c: Char) = c in 'ㄅ'..'ㄩ'

    /**
     * The syllables of [preedit], or null if it is empty, holds anything but Zhuyin, or has a
     * syllable without a tone mark.
     */
    fun parse(preedit: String): List<ZhuyinSyllable>? {
        val tokens = preedit.split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.map { token ->
            val tone = TONES[token.last()] ?: return null
            val base = token.dropLast(1)
            if (base.isEmpty() || !base.all(::isZhuyinLetter)) return null
            ZhuyinSyllable(base, tone)
        }
    }

    /**
     * Splits a preedit whose first words were already chosen (e.g. "請到ㄉㄥˉ ㄐㄧˋ") into the
     * chosen text and the syllables still to type; null if no toned syllables follow.
     */
    fun splitSelected(preedit: String): Pair<String, List<ZhuyinSyllable>>? {
        val start = preedit.indexOfFirst(::isZhuyinLetter)
        if (start < 0) return null
        val reading = parse(preedit.substring(start)) ?: return null
        return preedit.substring(0, start).trim() to reading
    }
}
