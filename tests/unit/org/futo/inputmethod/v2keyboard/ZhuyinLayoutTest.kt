package org.futo.inputmethod.v2keyboard

import org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZhuyinLayoutTest {
    private val layoutFile = File("java/assets/layouts/Chinese/zh_bopomofo.yaml")
    private val mappingFile = File("java/assets/layouts/mapping.yaml")

    @Test
    fun layoutParsesWithBopomofoHint() {
        val keyboard = parseKeyboardYamlString(layoutFile.readText())
        assertEquals("bopomofo", keyboard.imeHint)
        assertEquals(false, keyboard.autoShift)
    }

    @Test
    fun letterRowsHaveStandardKeyCounts() {
        val keyboard = parseKeyboardYamlString(layoutFile.readText())
        val letterRows = keyboard.getEffectiveRows(0).filter { it.isLetterRow }
        assertEquals(listOf(11, 10, 10, 11), letterRows.map { it.keys.size })
    }

    @Test
    fun mappingIncludesLayoutForChineseLocales() {
        val mapping = mappingFile.readText()
        val zh = mapping.substringAfter("\n  zh:\n").substringBefore("\n  zh_Hant:")
        val zhHant = mapping.substringAfter("\n  zh_Hant:\n").substringBefore("\n  zu:")
        assertTrue(zh.contains("- zh_bopomofo"))
        assertTrue(zhHant.contains("- zh_bopomofo"))
    }

    @Test
    fun hasEnglishSubKeyboardWithToggleKeys() {
        val keyboard = parseKeyboardYamlString(layoutFile.readText())

        val mainBottom = keyboard.getEffectiveRows(0).first { it.isBottomRow }
        assertTrue(mainBottom.keys.filterIsInstance<BaseKey>().any { it.code == -20 && it.spec.startsWith("英") })

        val english = requireNotNull(keyboard.subKeyboards[KeyboardLayoutKind.Alphabet1])
        val letterRows = english.rows.filter { it.isLetterRow }
        assertEquals(listOf(10, 9, 9), letterRows.map { it.keys.size })

        fun specs(row: Row) = row.keys.filterIsInstance<BaseKey>().map { it.spec }
        assertEquals("q w e r t y u i o p".split(" "), specs(letterRows[0]))
        assertEquals("a s d f g h j k l".split(" "), specs(letterRows[1]))
        // Row 3 also carries the non-BaseKey shift and delete entries.
        assertEquals("z x c v b n m".split(" "), specs(letterRows[2]))
        assertEquals(9, letterRows[2].keys.size)

        val englishBottom = english.rows.first { it.isBottomRow }
        assertTrue(englishBottom.keys.filterIsInstance<BaseKey>().any { it.code == -19 && it.spec.startsWith("中") })
    }
}
