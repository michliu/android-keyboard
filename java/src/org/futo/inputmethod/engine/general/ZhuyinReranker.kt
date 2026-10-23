package org.futo.inputmethod.engine.general

/** Scores Zhuyin candidates by how well they follow the text before the cursor. */
interface CandidateScorer {
    /** True if the scorer knows every character of [word]. */
    fun covers(word: String): Boolean

    /** Log-probability of each candidate, in order, or null if they cannot be scored. */
    fun score(context: String, reading: List<ZhuyinSyllable>, candidates: List<String>): FloatArray?

    /**
     * Log-probability of each single-character candidate as the next character after [context]
     * (which must not be empty), or null. One syllable is scored from the text alone, so the
     * placeholder syllable's base and tone are never read.
     */
    fun scoreNext(context: String, candidates: List<String>): FloatArray? =
        score(context, listOf(ZhuyinSyllable("", 0)), candidates)
}

/**
 * Decides whether a candidate should move ahead of Rime's first one. Only one candidate moves,
 * and only when the model clearly prefers it; everything else keeps Rime's order.
 */
object ZhuyinReranker {
    const val PROMOTE_MARGIN = 2.0f
    const val MAX_SCORED = 8
    /**
     * One syllable is scored from the context alone, so every candidate costs the same single
     * model position; scoring more of Rime's list (which can hold 128) reaches more targets.
     */
    const val MAX_SCORED_SINGLE = 32
    const val MAX_SYLLABLES = 3

    /**
     * Rime indexes worth scoring: from the start of [candidates], those as long as [syllables]
     * (in code points), up to [MAX_SCORED] ([MAX_SCORED_SINGLE] for one syllable), skipping ones [covers] rejects. Empty when Rime's
     * first candidate cannot be scored, since there would be nothing to compare against.
     */
    fun scorable(candidates: List<String>, syllables: Int, covers: (String) -> Boolean): List<Int> {
        val run = candidates.take(if (syllables == 1) MAX_SCORED_SINGLE else MAX_SCORED)
            .takeWhile { it.codePointCount(0, it.length) == syllables }
        if (run.isEmpty() || !covers(run[0])) return emptyList()
        return run.indices.filter { covers(run[it]) }
    }

    /**
     * Display order (display position → Rime index) for [candidateCount] candidates, or null to
     * keep Rime's order. [scores] are for [scorable], whose first entry is Rime index 0.
     */
    fun order(candidateCount: Int, scorable: List<Int>, scores: FloatArray): IntArray? {
        if (scores.size != scorable.size || scorable.size < 2 || scorable[0] != 0) return null
        if (scores.any { it.isNaN() || it.isInfinite() }) return null
        var best = 0
        for (i in scores.indices) if (scores[i] > scores[best]) best = i
        if (best == 0 || scores[best] - scores[0] < PROMOTE_MARGIN) return null
        val winner = scorable[best]
        if (winner !in 0 until candidateCount) return null
        return (listOf(winner) + (0 until candidateCount).filter { it != winner }).toIntArray()
    }
}
