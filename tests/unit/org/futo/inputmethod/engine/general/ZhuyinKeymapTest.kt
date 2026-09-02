package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZhuyinKeymapTest {
    private val zhuyin = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙"
    private val alphabet = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347"

    @Test
    fun alphabetMatchesRimeSchema() {
        assertEquals(alphabet, ZhuyinKeymap.RIME_ALPHABET)
        assertEquals(alphabet.toSet(), ZhuyinKeymap.alphabet)
    }

    @Test
    fun mapsAllSymbolsBothWays() {
        assertEquals(42, ZhuyinKeymap.toRimeKey.size)
        assertEquals(42, ZhuyinKeymap.toZhuyin.size)
        zhuyin.forEachIndexed { i, symbol ->
            assertEquals("symbol $symbol", alphabet[i], ZhuyinKeymap.toRimeKey[symbol])
            assertEquals("key ${alphabet[i]}", symbol, ZhuyinKeymap.toZhuyin[alphabet[i]])
        }
    }

    @Test
    fun standardKeyboardSpotChecks() {
        assertEquals('1', ZhuyinKeymap.toRimeKey['ㄅ'])
        assertEquals('u', ZhuyinKeymap.toRimeKey['ㄧ'])
        assertEquals('-', ZhuyinKeymap.toRimeKey['ㄦ'])
        assertEquals(' ', ZhuyinKeymap.toRimeKey['ˉ'])
        assertEquals('6', ZhuyinKeymap.toRimeKey['ˊ'])
        assertEquals('3', ZhuyinKeymap.toRimeKey['ˇ'])
        assertEquals('4', ZhuyinKeymap.toRimeKey['ˋ'])
        assertEquals('7', ZhuyinKeymap.toRimeKey['˙'])
    }

    @Test
    fun rimeKeyForCodePoint() {
        assertEquals('s', ZhuyinKeymap.rimeKeyFor('ㄋ'.code))
        assertNull(ZhuyinKeymap.rimeKeyFor('a'.code))
        assertNull(ZhuyinKeymap.rimeKeyFor('1'.code))
        assertNull(ZhuyinKeymap.rimeKeyFor(0x1F600)) // emoji, outside BMP
    }

    @Test
    fun bypassesRimeOnlyForLettersDigitsAndSpellerPunctuation() {
        assertTrue(ZhuyinKeymap.bypassesRime('a'.code))
        assertTrue(ZhuyinKeymap.bypassesRime('Z'.code))
        assertTrue(ZhuyinKeymap.bypassesRime('5'.code))
        assertTrue(ZhuyinKeymap.bypassesRime(','.code))
        assertTrue(ZhuyinKeymap.bypassesRime('.'.code))
        assertTrue(ZhuyinKeymap.bypassesRime(';'.code))
        assertTrue(ZhuyinKeymap.bypassesRime('/'.code))
        assertTrue(ZhuyinKeymap.bypassesRime('-'.code))

        assertFalse(ZhuyinKeymap.bypassesRime(' '.code))
        assertFalse(ZhuyinKeymap.bypassesRime('!'.code))
        assertFalse(ZhuyinKeymap.bypassesRime('?'.code))
        assertFalse(ZhuyinKeymap.bypassesRime('('.code))
        assertFalse(ZhuyinKeymap.bypassesRime('"'.code))
        assertFalse(ZhuyinKeymap.bypassesRime('ㄅ'.code))
        assertFalse(ZhuyinKeymap.bypassesRime(0x1F600))
    }

    @Test
    fun toneKeys() {
        " 6347".forEach { assertTrue("$it", ZhuyinKeymap.isToneKey(it)) }
        assertFalse(ZhuyinKeymap.isToneKey('1'))
        assertFalse(ZhuyinKeymap.isToneKey('u'))
        assertFalse(ZhuyinKeymap.isToneKey(null))
    }

    @Test
    fun adjacentPairsAreSymmetricUniqueAndSpaceFree() {
        val pairs = ZhuyinKeymap.adjacentKeyPairs()
        val asSets = pairs.map { setOf(it.first, it.second) }
        assertEquals(asSets.size, asSets.toSet().size)          // 去重（無 a-b 與 b-a 並存）
        assertFalse(pairs.any { it.first == ' ' || it.second == ' ' })
        assertFalse(pairs.any { it.first == it.second })
        pairs.forEach {
            assertTrue("${it.first} in alphabet", it.first in ZhuyinKeymap.alphabet)
            assertTrue("${it.second} in alphabet", it.second in ZhuyinKeymap.alphabet)
        }
    }

    @Test
    fun adjacentPairsSpotChecks() {
        val sets = ZhuyinKeymap.adjacentKeyPairs().map { setOf(it.first, it.second) }.toSet()
        // 同行
        assertTrue(setOf('1', '2') in sets)
        assertTrue(setOf('u', 'i') in sets)
        assertTrue(setOf('.', '/') in sets)
        assertTrue(setOf('0', '-') in sets)
        // 下一行（i-1 與 i）
        assertTrue(setOf('u', 'j') in sets)   // row2[6]=u -> row3[6]=j
        assertTrue(setOf('u', 'h') in sets)   // row2[6]=u -> row3[5]=h
        assertTrue(setOf('-', 'p') in sets)   // row1[10]='-' -> row2[9]=p
        assertTrue(setOf('2', 'q') in sets)   // row1[1]='2' -> row2[0]=q
        // 不相鄰
        assertFalse(setOf('1', '0') in sets)
        assertFalse(setOf('q', 'l') in sets)
    }

    @Test
    fun toleranceRulesEscapeAndPairUp() {
        val rules = ZhuyinKeymap.toleranceRules()
        assertEquals(ZhuyinKeymap.adjacentKeyPairs().size * 2, rules.size)
        assertTrue(rules.contains("fuzz|u|i|"))
        assertTrue(rules.contains("fuzz|i|u|"))
        // '.' 在 pattern 端跳脫、在替換端不跳脫
        assertTrue(rules.contains("fuzz|\\.|/|"))
        assertTrue(rules.contains("fuzz|/|.|"))
        assertFalse(rules.any { it.contains(' ') })
    }
}
