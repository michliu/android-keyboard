package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.nio.file.Files

class ChineseCharReadingsTest {
    private val sample = listOf(
        "# Rime dictionary",
        "---",
        "name: luna_pinyin",
        "...",
        "",
        "的\tde\t99.97%",
        "的\tdi\t0.03%",
        "不\tbu\t100%",
        "到\tdao",
        "# a comment",
        "我們\two men",
        "𠀀\tqiu",
        "壞行",
    ).joinToString("\n")

    private fun parse(text: String = sample) = ChineseCharReadings.parse(StringReader(text))

    @Test
    fun readsEverySpellingOfACharacter() {
        val r = parse()
        assertTrue(r.has("的", "de"))
        assertTrue(r.has("的", "di"))
        assertTrue(r.has("到", "dao"))
        assertFalse(r.has("不", "de"))
        assertFalse(r.has("到", "de"))
    }

    @Test
    fun skipsTheHeaderPhrasesAndMalformedLines() {
        val r = parse()
        assertFalse(r.has("我們", "wo men"))
        assertFalse(r.has("name: luna_pinyin", ""))
        assertEquals(4, r.size) // 的 不 到 𠀀
    }

    @Test
    fun handlesSupplementaryCharacters() {
        assertTrue(parse().has("𠀀", "qiu"))
    }

    @Test
    fun unknownCharacterHasNoReading() {
        assertFalse(parse().has("好", "hao"))
    }

    @Test
    fun loadReturnsNullWithoutTheFile() {
        val dir = Files.createTempDirectory("readings").toFile()
        try {
            assertNull(ChineseCharReadings.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadReadsTheFile() {
        val dir = Files.createTempDirectory("readings").toFile()
        try {
            File(dir, ChineseCharReadings.FILE).writeText(sample)
            assertTrue(ChineseCharReadings.load(dir)!!.has("的", "de"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
