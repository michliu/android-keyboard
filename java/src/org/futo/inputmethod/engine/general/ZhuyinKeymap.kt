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
     * is left to Rime's punctuator, exactly as on the pinyin layout.
     */
    fun bypassesRime(codePoint: Int): Boolean {
        if (codePoint < 0 || codePoint > Char.MAX_VALUE.code) return false
        val c = codePoint.toChar()
        return c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || (c != ' ' && c in alphabet)
    }

    /** Whether [c] is one of the tone keys (space, 6, 3, 4, 7). */
    fun isToneKey(c: Char?): Boolean = c != null && c in TONE_KEYS

    /** Physical key grid of the standard (大千式) keyboard, matching zh_bopomofo.yaml's rows. */
    private val KEY_GRID = listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./")

    /**
     * Unordered, de-duplicated pairs of physically adjacent keys: same-row neighbours (i, i+1)
     * plus the next row's keys at index i-1 and i (rows are half-key staggered).
     */
    fun adjacentKeyPairs(): List<Pair<Char, Char>> {
        val pairs = LinkedHashSet<Set<Char>>()
        KEY_GRID.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { i, key ->
                if (i + 1 < row.length) pairs.add(setOf(key, row[i + 1]))
                KEY_GRID.getOrNull(rowIndex + 1)?.let { below ->
                    for (j in intArrayOf(i - 1, i)) {
                        below.getOrNull(j)?.let { pairs.add(setOf(key, it)) }
                    }
                }
            }
        }
        return pairs.map { it.first() to it.last() }
    }

    private val REGEX_SPECIALS = ".\\^$[](){}*+?|".toSet()

    private fun escapeForPattern(c: Char): String =
        if (c in REGEX_SPECIALS) "\\$c" else c.toString()

    /**
     * Rime spelling-algebra fuzz rules accepting a press on a neighbouring key, ranked below
     * exact spellings. `|` is used as the delimiter because `/` is itself a Zhuyin key.
     */
    fun toleranceRules(): List<String> = adjacentKeyPairs().flatMap { (a, b) ->
        listOf(
            "fuzz|${escapeForPattern(a)}|$b|",
            "fuzz|${escapeForPattern(b)}|$a|",
        )
    }
}
