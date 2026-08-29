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
    fun toneKeys() {
        " 6347".forEach { assertTrue("$it", ZhuyinKeymap.isToneKey(it)) }
        assertFalse(ZhuyinKeymap.isToneKey('1'))
        assertFalse(ZhuyinKeymap.isToneKey('u'))
        assertFalse(ZhuyinKeymap.isToneKey(null))
    }
}
