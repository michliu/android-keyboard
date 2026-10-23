package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZhuyinReadingTest {
    @Test
    fun splitsOnSpacesAndReadsTones() {
        assertEquals(
            listOf(ZhuyinSyllable("ㄍㄨㄥ", 1), ZhuyinSyllable("ㄩㄢ", 2)),
            ZhuyinReading.parse("ㄍㄨㄥˉ ㄩㄢˊ")
        )
    }

    @Test
    fun rejectsASyllableWithoutAToneMark() {
        assertNull(ZhuyinReading.parse("ㄍㄨㄥ ㄩㄢˊ"))
        assertNull(ZhuyinReading.parse("ㄍㄨㄥˉ ㄩㄢ"))
    }

    @Test
    fun readsEveryToneMark() {
        assertEquals(
            listOf(
                ZhuyinSyllable("ㄇㄚ", 1), ZhuyinSyllable("ㄇㄚ", 2), ZhuyinSyllable("ㄇㄚ", 3),
                ZhuyinSyllable("ㄇㄚ", 4), ZhuyinSyllable("ㄇㄚ", 5),
            ),
            ZhuyinReading.parse("ㄇㄚˉ ㄇㄚˊ ㄇㄚˇ ㄇㄚˋ ㄇㄚ˙")
        )
    }

    @Test
    fun ignoresExtraWhitespace() {
        assertEquals(listOf(ZhuyinSyllable("ㄗㄞ", 4)), ZhuyinReading.parse("  ㄗㄞˋ "))
    }

    @Test
    fun rejectsTextThatIsNotZhuyin() {
        assertNull(ZhuyinReading.parse("公園ㄌㄧˇ"))
        assertNull(ZhuyinReading.parse("ab"))
    }

    @Test
    fun rejectsAToneWithoutASyllable() {
        assertNull(ZhuyinReading.parse("ˊ"))
    }

    @Test
    fun rejectsEmptyText() {
        assertNull(ZhuyinReading.parse(""))
        assertNull(ZhuyinReading.parse("   "))
    }
}
