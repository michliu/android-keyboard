package org.futo.inputmethod.engine.general

import java.io.File
import java.io.Reader

/**
 * Associated phrases (聯想詞) for the Zhuyin layout: the words that most often follow some
 * committed text, taken from Rime's preset vocabulary `essay.txt` (one `word<TAB>weight` per
 * line), which ships with the luna_pinyin dictionary.
 *
 * Keys are the first one or two characters of a vocabulary word and values the rest of it,
 * highest weight first. Two-character keys only index words of weight [MIN_TWO_CHAR_WEIGHT] or
 * more: without the threshold they alone are ~100k keys, too much for the keyboard process.
 * Lengths are counted in code points so supplementary-plane Han characters work.
 */
class ChineseAssociation private constructor(private val index: Map<String, Array<String>>) {
    /** Number of keys in the index. */
    val size: Int get() = index.size

    /** What follows the trailing two characters of [text], else what follows its last one. */
    fun lookup(text: String): List<String> {
        val count = text.codePointCount(0, text.length)
        for (n in minOf(count, 2) downTo 1) {
            index[text.substring(text.offsetByCodePoints(text.length, -n))]?.let { return it.toList() }
        }
        return emptyList()
    }

    /** The [MAX_RESULTS] highest-weight values of one key; ties and duplicates keep the first seen. */
    private class TopK {
        private val weights = IntArray(MAX_RESULTS)
        private val words = arrayOfNulls<String>(MAX_RESULTS)
        private var count = 0

        fun offer(word: String, weight: Int) {
            if (count == MAX_RESULTS && weight <= weights[count - 1]) return
            for (i in 0 until count) if (words[i] == word) return

            var i = if (count < MAX_RESULTS) count++ else MAX_RESULTS - 1
            while (i > 0 && weights[i - 1] < weight) {
                weights[i] = weights[i - 1]
                words[i] = words[i - 1]
                i--
            }
            weights[i] = weight
            words[i] = word
        }

        fun toArray(): Array<String> = Array(count) { words[it]!! }
    }

    companion object {
        const val FILE_NAME = "essay.txt"
        const val MAX_RESULTS = 8
        const val MIN_TWO_CHAR_WEIGHT = 500

        /** The index for `essay.txt` in Rime's shared directory, or null if the file is missing. */
        fun load(sharedDir: File): ChineseAssociation? {
            val file = File(sharedDir, FILE_NAME)
            if (!file.isFile) return null
            return file.bufferedReader().use { parse(it) }
        }

        fun parse(reader: Reader): ChineseAssociation {
            val top = HashMap<String, TopK>()
            reader.buffered().forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val word = line.substring(0, tab)
                val weight = line.substring(tab + 1).trim().toIntOrNull() ?: return@forEachLine
                if (!isHan(word)) return@forEachLine
                val length = word.codePointCount(0, word.length)
                if (length < 2) return@forEachLine

                val one = word.offsetByCodePoints(0, 1)
                top.getOrPut(word.substring(0, one)) { TopK() }.offer(word.substring(one), weight)
                if (length >= 3 && weight >= MIN_TWO_CHAR_WEIGHT) {
                    val two = word.offsetByCodePoints(0, 2)
                    top.getOrPut(word.substring(0, two)) { TopK() }.offer(word.substring(two), weight)
                }
            }
            return ChineseAssociation(top.mapValues { it.value.toArray() })
        }

        private fun isHan(text: String): Boolean {
            if (text.isEmpty()) return false
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return false
                i += Character.charCount(codePoint)
            }
            return true
        }
    }
}
