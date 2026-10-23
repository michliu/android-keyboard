package org.futo.inputmethod.engine.general

import java.io.File
import java.io.Reader

/**
 * Readings (luna_pinyin spellings, without tone) of single characters, from the dictionary's
 * `luna_pinyin.dict.yaml`. The reranker uses it so that, for one syllable, only characters
 * with the typed reading are scored; Rime also offers characters with other readings.
 */
class ChineseCharReadings private constructor(private val readings: Map<String, Set<String>>) {
    /** Number of characters with at least one reading. */
    val size: Int get() = readings.size

    /** True if [ch] (one character) can be read as [spelling]. */
    fun has(ch: String, spelling: String): Boolean = readings[ch]?.contains(spelling) == true

    companion object {
        const val FILE = "luna_pinyin.dict.yaml"

        /** The readings in [sharedDir], or null if the dictionary has no [FILE]. */
        fun load(sharedDir: File): ChineseCharReadings? {
            val file = File(sharedDir, FILE)
            if (!file.exists()) return null
            return file.bufferedReader().use { parse(it) }
        }

        fun parse(reader: Reader): ChineseCharReadings {
            val readings = HashMap<String, MutableSet<String>>()
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
                readings.getOrPut(ch) { HashSet(2) }.add(spelling)
            }
            return ChineseCharReadings(readings)
        }
    }
}
