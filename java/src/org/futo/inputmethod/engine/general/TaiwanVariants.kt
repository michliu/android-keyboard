package org.futo.inputmethod.engine.general

/**
 * Taiwan standard character forms. The luna_pinyin dictionary and essay.txt use OpenCC's
 * traditional forms (爲, 喫, 衆, 裏); the Zhuyin schema shows Taiwan forms (為, 吃, 眾, 裡)
 * through its t2tw filter, so text built from those files is converted the same way.
 *
 * The pairs are the one-to-one entries of OpenCC's TWVariants.txt
 * (https://github.com/BYVoid/OpenCC, Apache License 2.0).
 */
object TaiwanVariants {
    private const val PAIRS =
        "僞偽 啓啟 喫吃 嫺嫻 嬀媯 峯峰 幺么 棱稜 樑梁 檐簷 污汙 泄洩 潙溈 潨潀 爲為 牀床 痹痺 癡痴 皁皂 着著 " +
        "睾睪 竈灶 糉粽 繮韁 纔才 羣群 脣唇 蔘參 蔿蒍 衆眾 裏裡 覈核 踊踴 鉢缽 鍼針 鮎鯰 麪麵 齶顎"

    private val map: Map<Int, Int> = PAIRS.split(' ').associate { pair ->
        pair.codePointAt(0) to pair.codePointAt(pair.offsetByCodePoints(0, 1))
    }

    /** [text] with every character in Taiwan form; [text] itself when nothing changes. */
    fun toTaiwan(text: String): String {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (cp in map) break
            i += Character.charCount(cp)
        }
        if (i == text.length) return text
        val out = StringBuilder(text.length).append(text, 0, i)
        while (i < text.length) {
            val cp = text.codePointAt(i)
            out.appendCodePoint(map[cp] ?: cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
