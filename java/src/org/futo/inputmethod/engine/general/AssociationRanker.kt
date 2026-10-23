package org.futo.inputmethod.engine.general

import kotlin.math.exp

/**
 * Orders associated phrases (聯想詞) by how likely the context model finds their first character
 * after the text before the cursor, and decides whether they are worth showing at all.
 * Offline, most associations shown after a commit were useless; showing them only when the
 * model gives their first characters at least [MIN_CONFIDENCE] in total kept nine in ten of the
 * useful ones while showing them at six in ten commits.
 */
object AssociationRanker {
    const val MIN_CONFIDENCE = 0.05f

    private fun firstCharacter(word: String): String? =
        if(word.isEmpty()) null else word.substring(0, word.offsetByCodePoints(0, 1))

    /** Distinct first characters of [words] that [covers] accepts, in first-seen order. */
    fun firstCharacters(words: List<String>, covers: (String) -> Boolean): List<String> =
        words.mapNotNull { firstCharacter(it) }.distinct().filter(covers)

    /**
     * [words] ordered by their first character's probability (from [logProbs]), highest first,
     * keeping the original order on ties and putting words whose first character has no score
     * (missing or NaN) last; or null if the first characters' summed probability is below
     * [MIN_CONFIDENCE], meaning the associations should not be shown.
     */
    fun rank(words: List<String>, logProbs: Map<String, Float>): List<String>? {
        val probs = HashMap<String, Float>()
        for(word in words) {
            val first = firstCharacter(word) ?: continue
            val logProb = logProbs[first] ?: continue
            if(logProb.isNaN()) continue
            probs[first] = exp(logProb)
        }
        if(probs.isEmpty() || probs.values.sum() < MIN_CONFIDENCE) return null
        // sortedByDescending is stable: ties and unscored words keep their order.
        return words.sortedByDescending { word -> firstCharacter(word)?.let { probs[it] } ?: -1f }
    }
}
