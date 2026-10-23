package org.futo.inputmethod.engine.general

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhuyinRerankerTest {
    private val all: (String) -> Boolean = { true }

    @Test
    fun scorableTakesTheLeadingRunOfTheSyllableLength() {
        val words = listOf("證件", "整件", "正見", "政見", "整", "證件夾")
        assertEquals(listOf(0, 1, 2, 3), ZhuyinReranker.scorable(words, 2, all))
    }

    @Test
    fun scorableStopsAtEight() {
        assertEquals((0 until 8).toList(), ZhuyinReranker.scorable(List(12) { "甲乙" }, 2, all))
    }

    @Test
    fun scorableSkipsUncoveredCandidatesButNeedsTheFirst() {
        val covers: (String) -> Boolean = { !it.contains("𠀀") }
        assertEquals(listOf(0, 2), ZhuyinReranker.scorable(listOf("證件", "政𠀀", "政見"), 2, covers))
        assertEquals(emptyList<Int>(), ZhuyinReranker.scorable(listOf("證𠀀", "政見"), 2, covers))
    }

    @Test
    fun scorableCountsCodePoints() {
        assertEquals(listOf(0, 1), ZhuyinReranker.scorable(listOf("𠀀字", "政見"), 2, all))
    }

    @Test
    fun scorableIsEmptyWhenTheFirstCandidateIsShorter() {
        assertEquals(emptyList<Int>(), ZhuyinReranker.scorable(listOf("證", "政見"), 2, all))
    }

    @Test
    fun promotesTheBestWhenItWinsByTheMargin() {
        // Rime order: 證件 整件 正見 政見 整 ; scored: first four.
        val order = ZhuyinReranker.order(5, listOf(0, 1, 2, 3), floatArrayOf(-6.1f, -18.3f, -7.3f, -0.004f))
        assertArrayEquals(intArrayOf(3, 0, 1, 2, 4), order)
    }

    @Test
    fun keepsRimeOrderBelowTheMargin() {
        assertNull(ZhuyinReranker.order(2, listOf(0, 1), floatArrayOf(-3.0f, -1.01f)))
    }

    @Test
    fun promotesAtExactlyTheMargin() {
        assertArrayEquals(intArrayOf(1, 0), ZhuyinReranker.order(2, listOf(0, 1), floatArrayOf(-3.0f, -1.0f)))
    }

    @Test
    fun keepsRimeOrderWhenRimeFirstIsBest() {
        assertNull(ZhuyinReranker.order(3, listOf(0, 1, 2), floatArrayOf(-0.1f, -5f, -9f)))
    }

    @Test
    fun promotesAcrossAnUnscoredCandidate() {
        // Rime index 1 was not scorable; the winner (Rime 2) moves to the front, the rest keep order.
        assertArrayEquals(intArrayOf(2, 0, 1, 3), ZhuyinReranker.order(4, listOf(0, 2), floatArrayOf(-7f, -0.5f)))
    }

    @Test
    fun rejectsMismatchedScores() {
        assertNull(ZhuyinReranker.order(3, listOf(0, 1, 2), floatArrayOf(-1f, 0f)))
        assertNull(ZhuyinReranker.order(3, listOf(0, 1), floatArrayOf(Float.NaN, 0f)))
    }
}
