package org.futo.inputmethod.engine.general

/**
 * Maps Zhuyin (Bopomofo) symbols to the ASCII keys of the standard (大千式) keyboard layout
 * that the Rime bopomofo schema expects, and back.
 *
 * The key strings below must stay in sync with `speller/alphabet` in
 * `assets/rime/futo_bopomofo.schema.yaml`.
 */
object ZhuyinKeymap {
    private const val ZHUYIN_SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙"

    /** Rime `speller/alphabet`. Space is tone 1, `6347` are tones 2, 3, 4 and neutral. */
    const val RIME_ALPHABET = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347"

    private const val TONE_KEYS = " 6347"

    /** Zhuyin symbol (or tone mark) to the ASCII key sent to Rime. */
    val toRimeKey: Map<Char, Char> = ZHUYIN_SYMBOLS.zip(RIME_ALPHABET).toMap()

    /** ASCII Rime key to the Zhuyin symbol shown to the user. */
    val toZhuyin: Map<Char, Char> = RIME_ALPHABET.zip(ZHUYIN_SYMBOLS).toMap()

    /** Every character Rime accepts as part of a Zhuyin spelling. */
    val alphabet: Set<Char> = RIME_ALPHABET.toSet()

    /** The Rime key for a Zhuyin code point, or null if the code point is not a Zhuyin symbol. */
    fun rimeKeyFor(codePoint: Int): Char? {
        if (codePoint < 0 || codePoint > Char.MAX_VALUE.code) return null
        return toRimeKey[codePoint.toChar()]
    }

    /**
     * Whether a code point typed on the Zhuyin page (or its symbols page) must be committed
     * directly instead of being sent to Rime: the bopomofo schema would read ASCII letters,
     * digits and its speller punctuation (`, . ; / -`) as Zhuyin keys. Other ASCII punctuation
     * is left to Rime so its punctuator can produce full-width Chinese punctuation.
     */
    fun bypassesRime(codePoint: Int): Boolean {
        if (codePoint < 0 || codePoint > Char.MAX_VALUE.code) return false
        val c = codePoint.toChar()
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || (c != ' ' && c in alphabet)
    }

    /** Whether [c] is one of the tone keys (space, 6, 3, 4, 7). */
    fun isToneKey(c: Char?): Boolean = c != null && c in TONE_KEYS
}
