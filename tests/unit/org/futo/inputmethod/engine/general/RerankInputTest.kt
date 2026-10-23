package org.futo.inputmethod.engine.general

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class RerankInputTest {
    private val vocab = RerankVocab.parse(File("java/assets/zhuyin-rerank/vocab.json").readText())

    private fun syl(s: String) = ZhuyinReading.parse(s)!!
    private fun longs(vararg x: Long) = x
    private fun maskOf(t: RerankTensors) =
        t.mask.map { row -> row.joinToString("") { if (it) "1" else "0" } }
    private fun rows(t: Array<LongArray>) = t.map { it.toList() }

    @Test
    fun vocabularyIds() {
        assertEquals(7269, vocab.charId("候"))
        assertEquals(268, vocab.baseId("ㄓㄥ"))
        assertEquals(221, vocab.baseId("ㄐㄧㄢ"))
        assertNull(vocab.charId("𠀀"))
        assertNull(vocab.baseId("ㄅㄅ"))
        assertTrue(vocab.covers("政見"))
        assertFalse(vocab.covers("政𠀀"))
    }

    @Test
    fun twoSyllablesTwoCandidates() {
        val t = RerankInput.build(vocab, "候選人發表", syl("ㄓㄥˋ ㄐㄧㄢˋ"), listOf("證件", "政見"))!!
        assertArrayEquals(longs(1, 7269, 23690, 6921, 17055, 21630, 2, 0, 0, 3, 22364, 12652), t.ids)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0, 0, 268, 221, 0, 0, 0), t.bases)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0, 0, 4, 4, 0, 0, 0), t.tones)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 2, 2), t.types)
        assertArrayEquals(longs(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10), t.positions)
        assertArrayEquals(longs(9, 10, 11), t.projPos)
        assertEquals(listOf(listOf(0L, 1L), listOf(0L, 2L)), rows(t.targetNode))
        assertEquals(listOf(listOf(22358L, 6973L), listOf(12646L, 21912L)), rows(t.targetIds))
        assertEquals(
            listOf(
                "100000000000", "110000000000", "111000000000", "111100000000",
                "111110000000", "111111000000", "111111100000", "111111110000",
                "111111111000", "111111111100", "111111111110", "111111111101",
            ),
            maskOf(t)
        )
    }

    @Test
    fun oneSyllableUsesOnlyTheContext() {
        val t = RerankInput.build(vocab, "我現在", syl("ㄗㄞˋ"), listOf("再", "在"))!!
        assertArrayEquals(longs(1, 11839, 16419, 9055, 2, 3), t.ids)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0), t.bases)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0), t.tones)
        assertArrayEquals(longs(0, 0, 0, 0, 0, 0), t.types)
        assertArrayEquals(longs(0, 1, 2, 3, 4, 5), t.positions)
        assertArrayEquals(longs(5), t.projPos)
        assertEquals(listOf(listOf(0L), listOf(0L)), rows(t.targetNode))
        assertEquals(listOf(listOf(7628L), listOf(9049L)), rows(t.targetIds))
        assertEquals(listOf("100000", "110000", "111000", "111100", "111110", "111111"), maskOf(t))
    }

    @Test
    fun threeSyllablesShareCandidatePrefixes() {
        val t = RerankInput.build(vocab, "", syl("ㄍㄨㄥˉ ㄩㄢˊ ㄌㄧˇ"), listOf("公園裡", "公元裡", "公園禮"))!!
        assertArrayEquals(longs(1, 2, 0, 0, 0, 3, 7602, 9033, 7567), t.ids)
        assertArrayEquals(longs(0, 0, 177, 415, 142, 0, 0, 0, 0), t.bases)
        assertArrayEquals(longs(0, 0, 1, 2, 3, 0, 0, 0, 0), t.tones)
        assertArrayEquals(longs(0, 0, 1, 1, 1, 0, 2, 2, 2), t.types)
        assertArrayEquals(longs(0, 1, 2, 3, 4, 5, 6, 7, 7), t.positions)
        assertArrayEquals(longs(5, 6, 7, 8), t.projPos)
        assertEquals(listOf(listOf(0L, 1L, 2L), listOf(0L, 1L, 3L), listOf(0L, 1L, 2L)), rows(t.targetNode))
        assertEquals(
            listOf(listOf(7596L, 9027L, 21743L), listOf(7596L, 7561L, 21743L), listOf(7596L, 9027L, 17864L)),
            rows(t.targetIds)
        )
        assertEquals(
            listOf(
                "100000000", "110000000", "111000000", "111100000", "111110000",
                "111111000", "111111100", "111111110", "111111101",
            ),
            maskOf(t)
        )
    }

    @Test
    fun keepsTheLast64ContextCharactersAndUsesUnkForUnknownOnes() {
        val context = "𠀀" + "我".repeat(70)
        val t = RerankInput.build(vocab, context, syl("ㄗㄞˋ"), listOf("再", "在"))!!
        // BOS + 64 context + SEP + OUT
        assertEquals(67, t.ids.size)
        assertTrue(t.ids.slice(1..64).all { it == vocab.charId("我")!!.toLong() })

        val unk = RerankInput.build(vocab, "𠀀", syl("ㄗㄞˋ"), listOf("再", "在"))!!
        assertArrayEquals(longs(1, 4, 2, 3), unk.ids)
    }

    @Test
    fun rejectsUnsupportedInput() {
        // One syllable needs context.
        assertNull(RerankInput.build(vocab, "", syl("ㄗㄞˋ"), listOf("再", "在")))
        // Candidate length must match the syllable count.
        assertNull(RerankInput.build(vocab, "我", syl("ㄓㄥˋ ㄐㄧㄢˋ"), listOf("證件", "證")))
        // Unknown candidate character or Zhuyin base.
        assertNull(RerankInput.build(vocab, "我", syl("ㄓㄥˋ ㄐㄧㄢˋ"), listOf("證件", "政𠀀")))
        assertNull(RerankInput.build(vocab, "我", listOf(ZhuyinSyllable("ㄅㄅ", 1), ZhuyinSyllable("ㄐㄧㄢ", 4)), listOf("證件", "政見")))
        // No candidates, or no syllables.
        assertNull(RerankInput.build(vocab, "我", syl("ㄓㄥˋ ㄐㄧㄢˋ"), emptyList()))
        assertNull(RerankInput.build(vocab, "我", emptyList(), listOf("再")))
    }

    @Test
    fun aMissingToneScoresFromTheTextOnly() {
        val toned = RerankInput.build(vocab, "我", syl("ㄓㄥˋ ㄐㄧㄢˋ"), listOf("證件", "政見"))!!
        val t = RerankInput.build(vocab, "我", syl("ㄓㄥ ㄐㄧㄢˋ"), listOf("證件", "政見"))!!
        // No reading tokens: the typed tone is not used either.
        assertTrue(t.types.none { it == 1L })
        assertTrue(t.bases.all { it == 0L } && t.tones.all { it == 0L })
        assertEquals(toned.ids.size - 2, t.ids.size)
        assertEquals(rows(toned.targetIds), rows(t.targetIds))
    }

    @Test
    fun textOnlyNeedsContextOnlyForOneSyllable() {
        assertNotNull(RerankInput.build(vocab, "", syl("ㄓㄥ ㄐㄧㄢ"), listOf("證件", "政見")))
        assertNull(RerankInput.build(vocab, "", syl("ㄗㄞ"), listOf("再", "在")))
    }
}
