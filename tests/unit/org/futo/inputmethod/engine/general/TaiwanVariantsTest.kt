package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TaiwanVariantsTest {
    @Test
    fun convertsToTaiwanForms() {
        assertEquals("為了吃飯，眾人在這裡", TaiwanVariants.toTaiwan("爲了喫飯，衆人在這裏"))
    }

    @Test
    fun keepsOtherCharacters() {
        assertEquals("你好 abc 𠀀", TaiwanVariants.toTaiwan("你好 abc 𠀀"))
    }

    @Test
    fun returnsTheSameStringWhenNothingChanges() {
        val text = "台灣"
        assertSame(text, TaiwanVariants.toTaiwan(text))
    }

    @Test
    fun mapsEveryPair() {
        assertEquals(
            "偽啟吃嫻媯峰么稜梁簷汙洩溈潀為床痺痴皂著睪灶粽韁才群唇參蒍眾裡核踴缽針鯰麵顎",
            TaiwanVariants.toTaiwan("僞啓喫嫺嬀峯幺棱樑檐污泄潙潨爲牀痹癡皁着睾竈糉繮纔羣脣蔘蔿衆裏覈踊鉢鍼鮎麪齶")
        )
    }
}
