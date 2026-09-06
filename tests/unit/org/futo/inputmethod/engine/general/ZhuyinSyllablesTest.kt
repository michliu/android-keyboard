package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhuyinSyllablesTest {
    private fun pinyin(zhuyin: String) = ZhuyinSyllables.toLunaPinyin(zhuyin)

    @Test
    fun initialWithFinal() {
        assertEquals("ba", pinyin("ㄅㄚ"))
        assertEquals("zhang", pinyin("ㄓㄤ"))
        assertEquals("hao", pinyin("ㄏㄠ"))
    }

    @Test
    fun retroflexAndSibilantAlone() {
        assertEquals("zhi", pinyin("ㄓ"))
        assertEquals("chi", pinyin("ㄔ"))
        assertEquals("shi", pinyin("ㄕ"))
        assertEquals("ri", pinyin("ㄖ"))
        assertEquals("zi", pinyin("ㄗ"))
        assertEquals("ci", pinyin("ㄘ"))
        assertEquals("si", pinyin("ㄙ"))
    }

    @Test
    fun standaloneFinals() {
        assertEquals("a", pinyin("ㄚ"))
        assertEquals("o", pinyin("ㄛ"))
        assertEquals("e", pinyin("ㄜ"))
        assertEquals("eh", pinyin("ㄝ"))
        assertEquals("ai", pinyin("ㄞ"))
        assertEquals("ou", pinyin("ㄡ"))
        assertEquals("ang", pinyin("ㄤ"))
        assertEquals("er", pinyin("ㄦ"))
    }

    @Test
    fun medialsWithoutInitial() {
        assertEquals("yi", pinyin("ㄧ"))
        assertEquals("ya", pinyin("ㄧㄚ"))
        assertEquals("ye", pinyin("ㄧㄝ"))
        assertEquals("you", pinyin("ㄧㄡ"))
        assertEquals("yin", pinyin("ㄧㄣ"))
        assertEquals("ying", pinyin("ㄧㄥ"))
        assertEquals("wu", pinyin("ㄨ"))
        assertEquals("wo", pinyin("ㄨㄛ"))
        assertEquals("wei", pinyin("ㄨㄟ"))
        assertEquals("wen", pinyin("ㄨㄣ"))
        assertEquals("weng", pinyin("ㄨㄥ"))
        assertEquals("yu", pinyin("ㄩ"))
        assertEquals("yue", pinyin("ㄩㄝ"))
        assertEquals("yuan", pinyin("ㄩㄢ"))
        assertEquals("yun", pinyin("ㄩㄣ"))
        assertEquals("yong", pinyin("ㄩㄥ"))
    }

    @Test
    fun medialsWithInitial() {
        assertEquals("ni", pinyin("ㄋㄧ"))
        assertEquals("jia", pinyin("ㄐㄧㄚ"))
        assertEquals("liu", pinyin("ㄌㄧㄡ"))
        assertEquals("lin", pinyin("ㄌㄧㄣ"))
        assertEquals("qing", pinyin("ㄑㄧㄥ"))
        assertEquals("gu", pinyin("ㄍㄨ"))
        assertEquals("guo", pinyin("ㄍㄨㄛ"))
        assertEquals("hui", pinyin("ㄏㄨㄟ"))
        assertEquals("lun", pinyin("ㄌㄨㄣ"))
        assertEquals("dong", pinyin("ㄉㄨㄥ"))
        assertEquals("shuang", pinyin("ㄕㄨㄤ"))
    }

    @Test
    fun yuAfterJqxIsUAndAfterNlIsV() {
        assertEquals("ju", pinyin("ㄐㄩ"))
        assertEquals("que", pinyin("ㄑㄩㄝ"))
        assertEquals("xuan", pinyin("ㄒㄩㄢ"))
        assertEquals("jun", pinyin("ㄐㄩㄣ"))
        assertEquals("xiong", pinyin("ㄒㄩㄥ"))
        assertEquals("nv", pinyin("ㄋㄩ"))
        assertEquals("lv", pinyin("ㄌㄩ"))
        assertEquals("lve", pinyin("ㄌㄩㄝ"))
    }

    @Test
    fun tonesSeparateSyllablesAndAreDropped() {
        assertEquals("ni hao", pinyin("ㄋㄧˇㄏㄠˇ"))
        assertEquals("tian kong", pinyin("ㄊㄧㄢˉㄎㄨㄥˉ"))
        assertEquals("ma ma", pinyin("ㄇㄚˉㄇㄚ˙"))
        assertEquals("yi", pinyin("ㄧˊ"))
    }

    @Test
    fun syllablesSplitWithoutTones() {
        assertEquals("ni hao", pinyin("ㄋㄧㄏㄠ"))
        assertEquals("ya ya", pinyin("ㄧㄚㄧㄚ"))
        assertEquals("yi wu", pinyin("ㄧㄨ"))
        assertEquals("wo de", pinyin("ㄨㄛㄉㄜ"))
        assertEquals("hua er", pinyin("ㄏㄨㄚㄦ"))
    }

    @Test
    fun invalidInputReturnsNull() {
        assertNull(pinyin(""))
        assertNull(pinyin("ˇ"))
        assertNull(pinyin("ㄋㄧˇˇ"))
        assertNull(pinyin("ㄅㄩ"))
        assertNull(pinyin("ㄋㄩㄥ"))
        assertNull(pinyin("ㄌㄩㄥ"))
        assertNull(pinyin("ㄅ"))
        assertNull(pinyin("ㄅㄦ"))
        assertNull(pinyin("ㄧㄦ"))
        assertNull(pinyin("ㄧㄜ"))
        assertNull(pinyin("ㄨㄡ"))
        assertNull(pinyin("ㄩㄚ"))
        assertNull(pinyin("ni"))
        assertNull(pinyin("ㄋㄧ hao"))
    }
}
