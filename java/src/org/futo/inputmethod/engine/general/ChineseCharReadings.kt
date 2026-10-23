package org.futo.inputmethod.engine.general

import java.io.File
import java.io.Reader

/**
 * Readings (luna_pinyin spellings, without tone) of single characters, from the dictionary's
 * `luna_pinyin.dict.yaml`. The reranker uses it so that, for one syllable, only characters
 * with the typed reading are scored; Rime also offers characters with other readings.
 * Stored compactly: interned spellings, sorted code points and offsets into one id array.
 */
class ChineseCharReadings private constructor(
    private val spellingIds: Map<String, Int>,
    /** Sorted code points of the characters. */
    private val chars: IntArray,
    /** Character i owns ids[starts[i] until starts[i + 1]]; one extra trailing entry. */
    private val starts: IntArray,
    private val ids: IntArray,
) {
    /** Number of characters with at least one reading. */
    val size: Int get() = chars.size

    /** True if [ch] (one character) can be read as [spelling]. */
    fun has(ch: String, spelling: String): Boolean {
        if (ch.isEmpty() || ch.codePointCount(0, ch.length) != 1) return false
        val id = spellingIds[spelling] ?: return false
        val i = chars.binarySearch(ch.codePointAt(0))
        if (i < 0) return false
        for (k in starts[i] until starts[i + 1]) if (ids[k] == id) return true
        return false
    }

    companion object {
        const val FILE = "luna_pinyin.dict.yaml"

        /** The readings in [sharedDir], or null if the dictionary has no [FILE]. */
        fun load(sharedDir: File): ChineseCharReadings? {
            val file = File(sharedDir, FILE)
            if (!file.exists()) return null
            return file.bufferedReader().use { parse(it) }
        }

        fun parse(reader: Reader): ChineseCharReadings {
            val spellingIds = HashMap<String, Int>()
            // (code point shl 32) or spelling id, one entry per line read.
            var pairs = LongArray(1024)
            var n = 0
            var inBody = false
            reader.forEachLine { line ->
                if (!inBody) {
                    if (line.trimEnd() == "...") inBody = true
                    return@forEachLine
                }
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val fields = line.split('\t')
                if (fields.size < 2) return@forEachLine
                val ch = fields[0]
                if (ch.isEmpty() || ch.codePointCount(0, ch.length) != 1) return@forEachLine
                val spelling = fields[1].trim()
                if (spelling.isEmpty()) return@forEachLine
                val id = spellingIds.getOrPut(spelling) { spellingIds.size }
                if (n == pairs.size) pairs = pairs.copyOf(n * 2)
                pairs[n++] = (ch.codePointAt(0).toLong() shl 32) or id.toLong()
            }
            java.util.Arrays.sort(pairs, 0, n)

            val chars = IntArray(n)
            val starts = IntArray(n + 1)
            val ids = IntArray(n)
            var nChars = 0
            var nIds = 0
            var prev = -1L
            for (j in 0 until n) {
                val p = pairs[j]
                if (p == prev) continue
                val cp = (p ushr 32).toInt()
                if (nChars == 0 || chars[nChars - 1] != cp) {
                    chars[nChars] = cp
                    starts[nChars] = nIds
                    nChars++
                }
                ids[nIds++] = (p and 0xFFFFFFFFL).toInt()
                prev = p
            }
            starts[nChars] = nIds
            return ChineseCharReadings(
                spellingIds,
                chars.copyOf(nChars),
                starts.copyOf(nChars + 1),
                ids.copyOf(nIds),
            )
        }
    }
}
