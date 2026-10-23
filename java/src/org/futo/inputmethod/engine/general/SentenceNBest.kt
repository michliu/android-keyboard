package org.futo.inputmethod.engine.general

/**
 * Whole-sentence candidates (整句) from [SentenceLexicon]: a beam search over the word lattice of
 * a reading, scored by the words' log shares of the essay weights.
 */
object SentenceNBest {
    /** Sentences scored (offline: 16 → 69.9%, 32 → 72.6% exact sentences). */
    const val K = 32
    /** Most candidates the model scores in one run. */
    const val BATCH = 16
    /** Syllable counts handled as sentences; fewer are left to [ZhuyinReranker]. */
    val SYLLABLES = 4..12

    data class Sentence(val text: String, val score: Double)

    /**
     * The best sentences for [reading] (luna_pinyin syllables), best first; each position keeps
     * the 2 × [k] best paths and one path per text. Empty if no path covers the reading.
     */
    fun best(lexicon: SentenceLexicon, reading: List<String>, k: Int = K): List<Sentence> {
        val n = reading.size
        if (n == 0) return emptyList()
        val beams = Array<List<Sentence>>(n + 1) { emptyList() }
        beams[0] = listOf(Sentence("", 0.0))
        for (i in 0 until n) {
            if (beams[i].isEmpty()) continue
            for (j in i + 1..minOf(n, i + SentenceLexicon.MAX_WORD)) {
                val words = lexicon.lookup(reading.subList(i, j))
                if (words.isEmpty()) continue
                val next = ArrayList<Sentence>(beams[j])
                for (path in beams[i]) for (w in words) next.add(Sentence(path.text + w.word, path.score + w.score))
                beams[j] = prune(next, 2 * k)
            }
        }
        return beams[n]
    }

    private fun prune(paths: List<Sentence>, limit: Int): List<Sentence> {
        val best = HashMap<String, Sentence>()
        for (p in paths) {
            val seen = best[p.text]
            if (seen == null || p.score > seen.score) best[p.text] = p
        }
        return best.values.sortedWith(compareByDescending<Sentence> { it.score }.thenByDescending { it.text }).take(limit)
    }

    /**
     * The sentences to score: Rime's own first, then the best others whose characters the model
     * knows ([covers]), [k] in all.
     */
    fun candidates(
        lexicon: SentenceLexicon, reading: List<String>, rimeSentence: String,
        covers: (String) -> Boolean, k: Int = K,
    ): List<String> = (listOf(rimeSentence) + best(lexicon, reading, k).map { it.text }
        .filter { it != rimeSentence && covers(it) }).take(k)

    /**
     * Index of the sentence to show ahead of Rime's (index 0), or null to keep Rime's: the model's
     * favourite, if it beats Rime's by at least [ZhuyinReranker.PROMOTE_MARGIN].
     */
    fun promoted(scores: FloatArray): Int? {
        if (scores.size < 2 || scores.any { it.isNaN() || it.isInfinite() }) return null
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        if (best == 0 || scores[best] - scores[0] < ZhuyinReranker.PROMOTE_MARGIN) return null
        return best
    }

    /**
     * Scores [sentences] in runs of at most [BATCH] with [score] (each sentence's score does not
     * depend on the others in its run); null if any run fails. A last run of one sentence is
     * scored together with the first, since the model needs at least two.
     */
    fun scoreInBatches(sentences: List<String>, score: (List<String>) -> FloatArray?): FloatArray? {
        val out = FloatArray(sentences.size)
        var start = 0
        while (start < sentences.size) {
            val run = sentences.subList(start, minOf(start + BATCH, sentences.size))
            val scored = if (run.size == 1) listOf(run[0], sentences[0]) else run
            val scores = score(scored) ?: return null
            if (scores.size != scored.size) return null
            for (i in run.indices) out[start + i] = scores[i]
            start += run.size
        }
        return out
    }
}
