package org.futo.inputmethod.engine.general

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The reranker's vocabulary (`vocab.json`). IDs 0–7 are special tokens, characters start at 8 and
 * Zhuyin bases (syllables without tone) at 2, in file order.
 */
class RerankVocab private constructor(
    private val chars: Map<String, Int>,
    private val bases: Map<String, Int>,
) {
    /** ID of the single character [ch] (one code point), or null if the model does not know it. */
    fun charId(ch: String): Int? = chars[ch]

    fun baseId(base: String): Int? = bases[base]

    /** True if every character of [word] is in the vocabulary. */
    fun covers(word: String): Boolean = codePoints(word).all { it in chars }

    @Serializable
    private class VocabFile(val characters: List<String>, val bases: List<String>)

    companion object {
        const val PAD = 0
        const val BOS = 1
        const val SEP = 2
        const val OUT = 3
        const val UNK = 4
        private const val FIRST_CHAR = 8
        private const val FIRST_BASE = 2

        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(json: String): RerankVocab {
            val file = JSON.decodeFromString(VocabFile.serializer(), json)
            return RerankVocab(
                file.characters.withIndex().associate { (i, c) -> c to i + FIRST_CHAR },
                file.bases.withIndex().associate { (i, b) -> b to i + FIRST_BASE },
            )
        }

        /** [text] split into one string per code point. */
        fun codePoints(text: String): List<String> {
            val out = ArrayList<String>(text.length)
            var i = 0
            while (i < text.length) {
                val next = text.offsetByCodePoints(i, 1)
                out.add(text.substring(i, next))
                i = next
            }
            return out
        }
    }
}
