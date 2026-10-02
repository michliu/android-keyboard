package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.nio.file.Files

class ChineseAssociationTest {
    private fun parse(vararg lines: String) =
        ChineseAssociation.parse(StringReader(lines.joinToString("\n")))

    @Test
    fun keepsTheEightHighestWeightsInOrder() {
        val association = parse(
            "你一\t1", "你二\t2", "你三\t3", "你四\t4", "你五\t5",
            "你六\t6", "你七\t7", "你八\t8", "你九\t9", "你十\t10",
        )
        assertEquals(listOf("十", "九", "八", "七", "六", "五", "四", "三"), association.lookup("你"))
    }

    @Test
    fun equalWeightsKeepFileOrder() {
        val association = parse("你甲\t5", "你乙\t5", "你丙\t9")
        assertEquals(listOf("丙", "甲", "乙"), association.lookup("你"))
    }

    @Test
    fun duplicateContinuationKeepsFirstOccurrence() {
        val association = parse("你們\t5", "你們\t9", "你好\t7")
        assertEquals(listOf("好", "們"), association.lookup("你"))
    }

    @Test
    fun trailingTwoCharactersWinOverTheLastOne() {
        val association = parse("你們這些\t600", "們的\t100")
        assertEquals(listOf("這些"), association.lookup("你們"))
        assertEquals(listOf("的"), association.lookup("他們"))
        assertEquals(listOf("的"), association.lookup("們"))
    }

    @Test
    fun usesOnlyTheEndOfLongerText() {
        val association = parse("你們這些\t600", "們的\t100")
        assertEquals(listOf("這些"), association.lookup("我說你們"))
    }

    @Test
    fun twoCharacterKeysNeedTheMinimumWeight() {
        val association = parse("你們這些\t499", "們的\t100")
        // Below the threshold: no 你們 key, so lookup falls back to 們…
        assertEquals(listOf("的"), association.lookup("你們"))
        // …but the word still counts for its one-character key.
        assertEquals(listOf("們這些"), association.lookup("你"))
    }

    @Test
    fun skipsNonHanSingleCharacterAndMalformedLines() {
        val association = parse(
            "", "AB\t100", "你a\t100", "你\t100", "你好", "你好\tabc", "\t5", "你們\t50",
        )
        assertEquals(listOf("們"), association.lookup("你"))
        assertEquals(1, association.size)
    }

    @Test
    fun unknownTextHasNoAssociations() {
        val association = parse("你們\t50")
        assertEquals(emptyList<String>(), association.lookup(""))
        assertEquals(emptyList<String>(), association.lookup("，"))
        assertEquals(emptyList<String>(), association.lookup("abc"))
    }

    @Test
    fun handlesSupplementaryCharacters() {
        val association = parse("𠮷野家\t600")
        assertEquals(listOf("野家"), association.lookup("𠮷"))
        assertEquals(listOf("家"), association.lookup("𠮷野"))
    }

    @Test
    fun loadReturnsNullWithoutTheFile() {
        val dir = Files.createTempDirectory("assoc").toFile()
        try {
            assertNull(ChineseAssociation.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadReadsTheFile() {
        val dir = Files.createTempDirectory("assoc").toFile()
        try {
            File(dir, ChineseAssociation.FILE_NAME).writeText("你們\t96181\n你的\t159646\n")
            assertEquals(listOf("的", "們"), ChineseAssociation.load(dir)!!.lookup("你"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
