package org.futo.inputmethod.engine.general

/**
 * Picks Rime candidates one by one so that the composition becomes a given sentence and Rime
 * learns its words.
 */
object SentenceSelector {
    /**
     * Index of the candidate to select next to reach [remaining]: Rime's first if it is exactly
     * [remaining], else the longest candidate that [remaining] starts with (the first of equal
     * length), or -1 if none.
     */
    fun next(candidates: List<String>, remaining: String): Int {
        if (remaining.isEmpty()) return -1
        if (candidates.firstOrNull() == remaining) return 0
        var best = -1
        candidates.forEachIndexed { i, c ->
            if (c.isNotEmpty() && c.length <= remaining.length && remaining.startsWith(c) &&
                (best < 0 || c.length > candidates[best].length)) best = i
        }
        return best
    }
}
