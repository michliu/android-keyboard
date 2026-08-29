package org.futo.inputmethod.v2keyboard

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
}
