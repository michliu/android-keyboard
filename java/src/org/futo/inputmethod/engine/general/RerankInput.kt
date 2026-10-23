package org.futo.inputmethod.engine.general

/** Model inputs for scoring several candidates in one run; see [RerankInput.build]. */
class RerankTensors(
    val ids: LongArray,
    val bases: LongArray,
    val tones: LongArray,
    val types: LongArray,
    val positions: LongArray,
    /** Attention mask, [query][key]. */
    val mask: Array<BooleanArray>,
    val projPos: LongArray,
    val targetNode: Array<LongArray>,
    val targetIds: Array<LongArray>,
)

/**
 * Builds the reranker's input, following the model card's format:
 * `[BOS] context [SEP] reading×n [OUT] first n−1 characters of the candidate`.
 * Candidates sharing a prefix share its tokens (a tree), so one run scores them all.
 */
object RerankInput {
    const val MAX_CONTEXT = 64

    private const val TYPE_TEXT = 0L
    private const val TYPE_READING = 1L
    private const val TYPE_CANDIDATE = 2L
    private const val TARGET_OFFSET = 6

    /**
     * Inputs to score [candidates] after [context] for [reading], or null if the model cannot
     * score them: no syllables or candidates, a candidate whose length differs from the syllable
     * count, an unknown candidate character or Zhuyin base, or one syllable without context.
     * A single syllable, or a reading with an untyped tone (tone 0), is scored from the text
     * alone: offline, giving the model a guessed tone did worse than Rime, the text alone better.
     */
    fun build(vocab: RerankVocab, context: String, reading: List<ZhuyinSyllable>, candidates: List<String>): RerankTensors? {
        val n = reading.size
        if (n == 0 || candidates.isEmpty()) return null
        val textOnly = n == 1 || reading.any { it.tone == 0 }
        if (n == 1 && context.isEmpty()) return null

        val encoded = candidates.map { word ->
            val chars = RerankVocab.codePoints(word)
            if (chars.size != n) return null
            chars.map { vocab.charId(it) ?: return null }
        }
        val sounds = if (textOnly) emptyList() else reading.map { (vocab.baseId(it.base) ?: return null) to it.tone }

        val ids = ArrayList<Long>(); val bases = ArrayList<Long>(); val tones = ArrayList<Long>()
        val types = ArrayList<Long>(); val positions = ArrayList<Long>()
        fun add(id: Int, base: Int, tone: Int, type: Long, position: Int) {
            ids.add(id.toLong()); bases.add(base.toLong()); tones.add(tone.toLong())
            types.add(type); positions.add(position.toLong())
        }

        add(RerankVocab.BOS, 0, 0, TYPE_TEXT, ids.size)
        RerankVocab.codePoints(context).takeLast(MAX_CONTEXT).forEach {
            add(vocab.charId(it) ?: RerankVocab.UNK, 0, 0, TYPE_TEXT, ids.size)
        }
        add(RerankVocab.SEP, 0, 0, TYPE_TEXT, ids.size)
        sounds.forEach { (base, tone) -> add(RerankVocab.PAD, base, tone, TYPE_READING, ids.size) }
        val out = ids.size
        add(RerankVocab.OUT, 0, 0, TYPE_TEXT, out)
        val shared = ids.size

        // Tree node for each distinct candidate prefix of length 1..n-1; the empty prefix is OUT.
        val nodeOf = HashMap<List<Int>, Int>().apply { put(emptyList(), out) }
        val parent = HashMap<Int, Int>()
        for (chars in encoded) {
            for (i in 1 until n) {
                val prefix = chars.subList(0, i)
                if (prefix in nodeOf) continue
                val node = ids.size
                parent[node] = nodeOf.getValue(chars.subList(0, i - 1))
                nodeOf[prefix] = node
                add(chars[i - 1], 0, 0, TYPE_CANDIDATE, out + i)
            }
        }

        val length = ids.size
        val mask = Array(length) { q ->
            BooleanArray(length).also { row ->
                if (q < shared) {
                    for (k in 0..q) row[k] = true
                } else {
                    for (k in 0 until shared) row[k] = true
                    var a: Int? = q
                    while (a != null) { row[a] = true; a = parent[a] }
                }
            }
        }

        val proj = nodeOf.values.sorted()
        val projIndex = proj.withIndex().associate { (i, p) -> p to i.toLong() }
        val targetNode = Array(encoded.size) { c ->
            LongArray(n) { i -> projIndex.getValue(nodeOf.getValue(encoded[c].subList(0, i))) }
        }
        val targetIds = Array(encoded.size) { c ->
            LongArray(n) { i -> (encoded[c][i] - TARGET_OFFSET).toLong() }
        }

        return RerankTensors(
            ids.toLongArray(), bases.toLongArray(), tones.toLongArray(), types.toLongArray(),
            positions.toLongArray(), mask, proj.map { it.toLong() }.toLongArray(), targetNode, targetIds,
        )
    }
}
