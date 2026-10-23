package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class SentenceNBestTest {
    private val lexicon = SentenceLexicon.build(
        StringReader("---\n...\n請\tqing\n青\tqing\n到\tdao\n島\tdao\n登\tdeng\n記\tji"),
        StringReader("請到\t100\n青島\t60\n登記\t200\n請\t50\n到\t50\n青\t20\n島\t20\n登\t10\n記\t10"),
        emptyList(),
    )
    private val reading = listOf("qing", "dao", "deng", "ji")

    @Test
    fun bestPathFirstWithoutDuplicates() {
        val best = SentenceNBest.best(lexicon, reading)
        assertEquals("請到登記", best.first().text)
        assertTrue(best.any { it.text == "青島登記" })
        assertEquals(best.size, best.map { it.text }.toSet().size)
        assertEquals(best.sortedByDescending { it.score }.map { it.score }, best.map { it.score })
    }

    @Test
    fun keepsAtMostTwiceK() {
        assertTrue(SentenceNBest.best(lexicon, reading, k = 1).size <= 2)
    }

    @Test
    fun unknownSyllableGivesNothing() {
        assertEquals(emptyList<SentenceNBest.Sentence>(), SentenceNBest.best(lexicon, listOf("qing", "zzz")))
    }

    @Test
    fun candidatesStartWithRimesSentence() {
        val list = SentenceNBest.candidates(lexicon, reading, "青島登記", { true })
        assertEquals("青島登記", list[0])
        assertEquals("請到登記", list[1])
        assertEquals(list.size, list.toSet().size)
        assertTrue(list.size <= SentenceNBest.K)
    }

    @Test
    fun candidatesSkipWordsTheModelDoesNotKnow() {
        val list = SentenceNBest.candidates(lexicon, reading, "請到登記", { !it.contains('島') })
        assertTrue(list.none { it.contains('島') })
        assertEquals("請到登記", list[0])
    }

    @Test
    fun promotedNeedsTheMargin() {
        assertEquals(1, SentenceNBest.promoted(floatArrayOf(-5f, -2f, -6f)))
        assertEquals(1, SentenceNBest.promoted(floatArrayOf(-5f, -3f)))
        assertNull(SentenceNBest.promoted(floatArrayOf(-5f, -3.5f)))
        assertNull(SentenceNBest.promoted(floatArrayOf(-1f, -5f)))
        assertNull(SentenceNBest.promoted(floatArrayOf(-5f, Float.NaN)))
        assertNull(SentenceNBest.promoted(floatArrayOf(-5f)))
    }
}
