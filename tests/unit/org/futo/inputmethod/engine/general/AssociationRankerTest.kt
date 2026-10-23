package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class AssociationRankerTest {
    private val all: (String) -> Boolean = { true }

    @Test
    fun firstCharactersAreDistinctInFirstSeenOrder() {
        val words = listOf("天氣", "今天", "天空", "明白", "今年")
        assertEquals(listOf("天", "今", "明"), AssociationRanker.firstCharacters(words, all))
    }

    @Test
    fun firstCharactersSkipThoseTheModelDoesNotKnow() {
        assertEquals(listOf("天"), AssociationRanker.firstCharacters(listOf("𠀀字", "天氣", "𠀀好"), { it != "𠀀" }))
    }

    @Test
    fun firstCharactersHandleSupplementaryCodePointsAndEmptyWords() {
        assertEquals(listOf("𠀀", "天"), AssociationRanker.firstCharacters(listOf("", "𠀀字", "天"), all))
    }

    @Test
    fun rankOrdersByFirstCharacterProbability() {
        val words = listOf("天氣", "今天", "明白", "天空")
        val logProbs = mapOf("天" to ln(0.02f), "今" to ln(0.3f), "明" to ln(0.1f))
        assertEquals(listOf("今天", "明白", "天氣", "天空"), AssociationRanker.rank(words, logProbs))
    }

    @Test
    fun rankKeepsTheOriginalOrderOnTies() {
        val words = listOf("甲一", "乙一", "甲二", "乙二")
        val logProbs = mapOf("甲" to ln(0.1f), "乙" to ln(0.1f))
        assertEquals(words, AssociationRanker.rank(words, logProbs))
    }

    @Test
    fun rankPutsUnscoredWordsLastInTheirOrder() {
        val words = listOf("丙一", "甲一", "丁一", "乙一")
        val logProbs = mapOf("甲" to ln(0.05f), "乙" to ln(0.2f))
        assertEquals(listOf("乙一", "甲一", "丙一", "丁一"), AssociationRanker.rank(words, logProbs))
    }

    @Test
    fun rankIsNullJustBelowTheThreshold() {
        val words = listOf("甲一", "乙一")
        val logProbs = mapOf("甲" to ln(0.03f), "乙" to ln(0.0199f))
        assertNull(AssociationRanker.rank(words, logProbs))
    }

    @Test
    fun rankShowsAtTheThreshold() {
        val words = listOf("甲一", "乙一")
        val logProbs = mapOf("甲" to ln(0.02f), "乙" to ln(0.0301f))
        assertEquals(listOf("乙一", "甲一"), AssociationRanker.rank(words, logProbs))
    }

    @Test
    fun rankCountsEachFirstCharacterOnce() {
        // 甲 starts three words but its probability counts once: 0.03 < 0.05.
        val words = listOf("甲一", "甲二", "甲三")
        assertNull(AssociationRanker.rank(words, mapOf("甲" to ln(0.03f))))
    }

    @Test
    fun rankIgnoresScoresForCharactersNoWordStartsWith() {
        assertNull(AssociationRanker.rank(listOf("甲一"), mapOf("甲" to ln(0.01f), "乙" to ln(0.9f))))
    }

    @Test
    fun rankTreatsNaNAsMissing() {
        val words = listOf("甲一", "乙一")
        val logProbs = mapOf("甲" to Float.NaN, "乙" to ln(0.2f))
        assertEquals(listOf("乙一", "甲一"), AssociationRanker.rank(words, logProbs))
    }

    @Test
    fun rankIsNullWhenNoScoreIsUsable() {
        val words = listOf("甲一", "乙一")
        assertNull(AssociationRanker.rank(words, emptyMap()))
        assertNull(AssociationRanker.rank(words, mapOf("甲" to Float.NaN, "乙" to Float.NaN)))
    }

    @Test
    fun rankIsNullForNoWords() {
        assertNull(AssociationRanker.rank(emptyList(), mapOf("甲" to 0f)))
    }
}
