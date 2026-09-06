# 注音個人字典 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 讓設定頁「個人字典」新增的「詞 + 注音」在注音布局打出對應注音時出現在候選列首位。

**Architecture:** `ChineseIME` 把個人字典的中文詞（`shortcut` 存注音）轉成無聲調 luna 拼音，寫成小型 Rime 詞典 `rime/user/futo_custom.dict.yaml`；新增依賴方案 `futo_custom_bopomofo` 用注音方案同一套拼寫規則替它編譯 prism；`futo_bopomofo` 以 `table_translator@custom_dict` 讀它。設定頁開放中文並提供帶注音符號鍵盤的對話框，存詞後透過既有 `IMEMessage.ReloadPersonalDict` 觸發重新部署。

**Tech Stack:** Kotlin / Android、Jetpack Compose（material3、`FlowRow`）、librime（`libs/rime-release.aar`，`icu.astronot233.rime.Rime`）、Rime YAML 方案、JUnit4 JVM 單元測試。

Spec：`docs/superpowers/specs/2026-09-06-zhuyin-personal-dictionary-design.md`

## Global Constraints

- 工作分支：`master`（使用者的 fork 為主，不向上游開 PR）。
- 只支援注音布局（`futo_bopomofo`、`futo_bopomofo_terra`）；拼音、筆畫布局行為不得改變。
- 詞典名固定 `futo_custom`，檔名 `futo_custom.dict.yaml`，寫在 Rime user 目錄（`ChineseIME.getUser(context)`）。
- 依賴方案 id 固定 `futo_custom_bopomofo`，prism 名同。translator 段名 `custom_dict`。
- 拼音必須符合 luna_pinyin 音節表：ü 在 ㄋㄌ 後寫 `v`、在 ㄐㄑㄒ 後寫 `u`；ㄩㄥ 有聲母時 `iong`；ㄝ 單獨為 `eh`。
- 對話框內的「注音：」「注音不完整或有誤」直接寫死中文（與 `PersonalDictionaryJa.kt` 的「よみ:」相同做法；`translations` 是子模組，不加資源字串）。
- 純 Kotlin 的新檔（`ZhuyinSyllables`、`ChineseCustomDict`）不得 import `android.*`，否則 JVM 單元測試跑不起來；也不要呼叫 `localeFromString`（在 `Subtypes.kt`，會拉進 Android 類別）。
- 提交訊息結尾需附：
  ```
  Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_014T549UmBbW1T2x56SfLxdH
  ```
- Gradle 任務：JVM 單元測試 `./gradlew testUnstableDebugUnitTest`；組 APK `./gradlew assembleUnstableDebug`。
- 真機（小米）：需開「USB 安裝」與「USB 偵錯（安全設定）」；每次 `adb shell input tap` 前先截圖確認畫面。

## File Structure

| 檔案 | 責任 |
| --- | --- |
| `java/src/org/futo/inputmethod/engine/general/ZhuyinSyllables.kt`（新） | 注音串 → 音節切分 → luna 拼音；純函式 |
| `tests/unit/org/futo/inputmethod/engine/general/ZhuyinSyllablesTest.kt`（新） | 上者的單元測試 |
| `java/src/org/futo/inputmethod/engine/general/ChineseCustomDict.kt`（新） | 從 `PersonalWord` 篩出中文詞、產生 dict.yaml 文字、寫檔 |
| `tests/unit/org/futo/inputmethod/engine/general/ChineseCustomDictTest.kt`（新） | 上者的單元測試 |
| `java/assets/rime/futo_custom_bopomofo.schema.yaml`（新） | 依賴方案：替 `futo_custom` 詞典編譯注音 prism |
| `java/assets/rime/futo_bopomofo.schema.yaml` | 加依賴、`custom_dict` translator |
| `java/assets/rime/futo_bopomofo_terra.schema.yaml` | 加依賴 |
| `java/src/org/futo/inputmethod/engine/general/BundledRimeAssets.kt` | 新方案檔加入 `SHARED_FILES`，`VERSION` 升為 2 |
| `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt` | `updateConfig()` 寫詞典並以內容 hash 觸發部署；處理 `ReloadPersonalDict` |
| `java/src/org/futo/inputmethod/latin/uix/settings/pages/pdict/PersonalDictionaryZh.kt`（新） | 注音詞對話框與注音符號鍵盤 |
| `java/src/org/futo/inputmethod/latin/uix/settings/pages/pdict/PersonalDictionary.kt` | `"zh"` 改為支援並指向新對話框 |

---

### Task 1: `ZhuyinSyllables` — 注音轉 luna 拼音

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/ZhuyinSyllables.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/ZhuyinSyllablesTest.kt`

**Interfaces:**
- Produces: `object ZhuyinSyllables { fun toLunaPinyin(zhuyin: String): String? }`
  — 回傳以單一空格分隔的拼音音節（例 `"ni hao"`）；輸入為空、含非注音字元、或任一音節不合法時回傳 `null`。

- [ ] **Step 1: 寫失敗的測試**

```kotlin
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
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinSyllablesTest'`
Expected: 編譯失敗，`Unresolved reference: ZhuyinSyllables`。

- [ ] **Step 3: 實作**

```kotlin
package org.futo.inputmethod.engine.general

/**
 * Splits a Zhuyin (Bopomofo) string into syllables and spells each one the way the
 * luna_pinyin dictionary does: no tones, ü written as `v` after n/l and as `u` after j/q/x,
 * ㄩㄥ with an initial written `iong`, standalone ㄝ written `eh`.
 *
 * A syllable is, in order, at most one initial, one medial (ㄧㄨㄩ), one final and one tone
 * mark. A symbol that cannot continue the current syllable starts a new one; a tone mark
 * always ends the current syllable and is not part of the output.
 */
object ZhuyinSyllables {
    private val INITIALS = mapOf(
        'ㄅ' to "b", 'ㄆ' to "p", 'ㄇ' to "m", 'ㄈ' to "f",
        'ㄉ' to "d", 'ㄊ' to "t", 'ㄋ' to "n", 'ㄌ' to "l",
        'ㄍ' to "g", 'ㄎ' to "k", 'ㄏ' to "h",
        'ㄐ' to "j", 'ㄑ' to "q", 'ㄒ' to "x",
        'ㄓ' to "zh", 'ㄔ' to "ch", 'ㄕ' to "sh", 'ㄖ' to "r",
        'ㄗ' to "z", 'ㄘ' to "c", 'ㄙ' to "s",
    )

    /** Initials that form a syllable on their own (spelled with a trailing `i`). */
    private const val SYLLABIC_INITIALS = "ㄓㄔㄕㄖㄗㄘㄙ"

    private const val MEDIALS = "ㄧㄨㄩ"

    private val FINALS = mapOf(
        'ㄚ' to "a", 'ㄛ' to "o", 'ㄜ' to "e", 'ㄝ' to "eh",
        'ㄞ' to "ai", 'ㄟ' to "ei", 'ㄠ' to "ao", 'ㄡ' to "ou",
        'ㄢ' to "an", 'ㄣ' to "en", 'ㄤ' to "ang", 'ㄥ' to "eng", 'ㄦ' to "er",
    )

    private const val TONES = "ˉˊˇˋ˙"

    /** Medial + final (null = no final) without an initial. */
    private val STANDALONE_MEDIAL: Map<Char, Map<Char?, String>> = mapOf(
        'ㄧ' to mapOf(
            null to "yi", 'ㄚ' to "ya", 'ㄛ' to "yo", 'ㄝ' to "ye", 'ㄞ' to "yai", 'ㄠ' to "yao",
            'ㄡ' to "you", 'ㄢ' to "yan", 'ㄣ' to "yin", 'ㄤ' to "yang", 'ㄥ' to "ying",
        ),
        'ㄨ' to mapOf(
            null to "wu", 'ㄚ' to "wa", 'ㄛ' to "wo", 'ㄞ' to "wai", 'ㄟ' to "wei",
            'ㄢ' to "wan", 'ㄣ' to "wen", 'ㄤ' to "wang", 'ㄥ' to "weng",
        ),
        'ㄩ' to mapOf(
            null to "yu", 'ㄝ' to "yue", 'ㄢ' to "yuan", 'ㄣ' to "yun", 'ㄥ' to "yong",
        ),
    )

    /** Medial + final (null = no final) after an initial. ㄩ is spelled `v` here; see [spell]. */
    private val MEDIAL_AFTER_INITIAL: Map<Char, Map<Char?, String>> = mapOf(
        'ㄧ' to mapOf(
            null to "i", 'ㄚ' to "ia", 'ㄛ' to "io", 'ㄝ' to "ie", 'ㄞ' to "iai", 'ㄠ' to "iao",
            'ㄡ' to "iu", 'ㄢ' to "ian", 'ㄣ' to "in", 'ㄤ' to "iang", 'ㄥ' to "ing",
        ),
        'ㄨ' to mapOf(
            null to "u", 'ㄚ' to "ua", 'ㄛ' to "uo", 'ㄞ' to "uai", 'ㄟ' to "ui",
            'ㄢ' to "uan", 'ㄣ' to "un", 'ㄤ' to "uang", 'ㄥ' to "ong",
        ),
        'ㄩ' to mapOf(
            null to "v", 'ㄝ' to "ve", 'ㄢ' to "van", 'ㄣ' to "vn", 'ㄥ' to "iong",
        ),
    )

    private const val YU_AS_U_INITIALS = "ㄐㄑㄒ"
    private const val YU_AS_V_INITIALS = "ㄋㄌ"

    private class Syllable {
        var initial: Char? = null
        var medial: Char? = null
        var final: Char? = null
        val isEmpty get() = initial == null && medial == null && final == null
    }

    /**
     * @return the luna_pinyin spelling of every syllable in [zhuyin], separated by single
     * spaces, or null if [zhuyin] is empty, contains a non-Zhuyin character, or any
     * syllable is not a valid Mandarin syllable.
     */
    fun toLunaPinyin(zhuyin: String): String? {
        val syllables = mutableListOf<Syllable>()
        var current = Syllable()

        fun push() {
            syllables.add(current)
            current = Syllable()
        }

        for (c in zhuyin) {
            when {
                c in INITIALS -> {
                    if (!current.isEmpty) push()
                    current.initial = c
                }
                c in MEDIALS -> {
                    if (current.medial != null || current.final != null) push()
                    current.medial = c
                }
                c in FINALS -> {
                    if (current.final != null) push()
                    current.final = c
                }
                c in TONES -> {
                    if (current.isEmpty) return null
                    push()
                }
                else -> return null
            }
        }
        if (!current.isEmpty) push()
        if (syllables.isEmpty()) return null

        // map is inline, so the non-local return works; joinToString's lambda is not.
        val spelled = syllables.map { spell(it) ?: return null }
        return spelled.joinToString(" ")
    }

    private fun spell(s: Syllable): String? {
        val initial = s.initial
        val medial = s.medial
        val final = s.final

        if (final == 'ㄦ' && (initial != null || medial != null)) return null

        if (initial == null) {
            if (medial == null) return FINALS[final ?: return null]
            return STANDALONE_MEDIAL.getValue(medial)[final]
        }

        val initialPinyin = INITIALS.getValue(initial)
        if (medial == null) {
            if (final == null) {
                return if (initial in SYLLABIC_INITIALS) initialPinyin + "i" else null
            }
            return initialPinyin + FINALS.getValue(final)
        }

        val rime = MEDIAL_AFTER_INITIAL.getValue(medial)[final] ?: return null
        if (medial == 'ㄩ') {
            return when (initial) {
                in YU_AS_U_INITIALS -> initialPinyin + rime.replaceFirst('v', 'u')
                in YU_AS_V_INITIALS -> initialPinyin + rime
                else -> null
            }
        }
        return initialPinyin + rime
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinSyllablesTest'`
Expected: `BUILD SUCCESSFUL`，全部測試通過。若 `xiong` 失敗，檢查 `replaceFirst('v', 'u')` 對 `iong` 不做替換（`iong` 沒有 v）。

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ZhuyinSyllables.kt tests/unit/org/futo/inputmethod/engine/general/ZhuyinSyllablesTest.kt
git commit -m "Add ZhuyinSyllables: Zhuyin to luna pinyin

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014T549UmBbW1T2x56SfLxdH"
```

---

### Task 2: `ChineseCustomDict` — 產生 Rime 詞典

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/ChineseCustomDict.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/ChineseCustomDictTest.kt`

**Interfaces:**
- Consumes: `ZhuyinSyllables.toLunaPinyin(String): String?`（Task 1）；
  `org.futo.inputmethod.latin.uix.PersonalWord(word: String, frequency: Int, locale: String?, appId: Int, shortcut: String?)`（既有，`SettingsExporter.kt`）。
- Produces:
  ```kotlin
  object ChineseCustomDict {
      const val DICT_NAME = "futo_custom"
      const val FILE_NAME = "futo_custom.dict.yaml"
      data class Entry(val text: String, val code: String, val weight: Int)
      fun isChinese(locale: String?): Boolean
      fun entries(words: List<PersonalWord>): List<Entry>
      fun render(entries: List<Entry>): String
      fun write(userDir: File, content: String): Boolean
  }
  ```

- [ ] **Step 1: 寫失敗的測試**

```kotlin
package org.futo.inputmethod.engine.general

import org.futo.inputmethod.latin.uix.PersonalWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChineseCustomDictTest {
    private fun word(text: String, zhuyin: String?, locale: String? = "zh_TW", frequency: Int = 250) =
        PersonalWord(word = text, frequency = frequency, locale = locale, appId = 0, shortcut = zhuyin)

    @Test
    fun isChineseMatchesLanguageOnly() {
        assertTrue(ChineseCustomDict.isChinese("zh"))
        assertTrue(ChineseCustomDict.isChinese("zh_TW"))
        assertTrue(ChineseCustomDict.isChinese("zh-Hant-TW"))
        assertFalse(ChineseCustomDict.isChinese("ja"))
        assertFalse(ChineseCustomDict.isChinese(null))
    }

    @Test
    fun entriesKeepOnlyParsableChineseWords() {
        val entries = ChineseCustomDict.entries(listOf(
            word("你好", "ㄋㄧˇㄏㄠˇ"),
            word("英文", "ㄧㄥㄨㄣ", locale = "en"),
            word("無讀音", null),
            word("壞讀音", "ㄅㄩ"),
            word("所有語言", "ㄋㄧˇ", locale = null),
            word("含\t制表", "ㄋㄧˇ"),
            word("含\n換行", "ㄋㄧˇ"),
            word("  ", "ㄋㄧˇ"),
        ))
        assertEquals(listOf(ChineseCustomDict.Entry("你好", "ni hao", 250)), entries)
    }

    @Test
    fun weightIsAtLeastOne() {
        val entries = ChineseCustomDict.entries(listOf(word("你", "ㄋㄧˇ", frequency = 0)))
        assertEquals(1, entries.single().weight)
    }

    @Test
    fun renderEmptyDictionaryIsHeaderOnly() {
        val expected = """
            # Rime dictionary
            # Generated by FUTO Keyboard from the personal dictionary. Do not edit.
            ---
            name: futo_custom
            version: "1"
            sort: by_weight
            use_preset_vocabulary: false
            ...

        """.trimIndent()
        assertEquals(expected, ChineseCustomDict.render(emptyList()))
    }

    @Test
    fun renderWritesTabSeparatedEntries() {
        val content = ChineseCustomDict.render(listOf(
            ChineseCustomDict.Entry("你好", "ni hao", 250),
            ChineseCustomDict.Entry("天空", "tian kong", 1),
        ))
        assertTrue(content.endsWith("...\n你好\tni hao\t250\n天空\ttian kong\t1\n"))
    }

    @Test
    fun renderIsStableForSameInput() {
        val entries = listOf(ChineseCustomDict.Entry("你好", "ni hao", 250))
        assertEquals(ChineseCustomDict.render(entries), ChineseCustomDict.render(entries))
        assertEquals(ChineseCustomDict.render(entries).hashCode(), ChineseCustomDict.render(entries).hashCode())
    }

    @Test
    fun writeCreatesFileAndSkipsRewriteOfSameContent() {
        val dir = Files.createTempDirectory("rime-user").toFile()
        try {
            val file = File(dir, ChineseCustomDict.FILE_NAME)
            assertTrue(ChineseCustomDict.write(dir, "a"))
            assertEquals("a", file.readText())
            val firstModified = file.lastModified()
            file.setLastModified(firstModified - 10_000)
            assertTrue(ChineseCustomDict.write(dir, "a"))
            assertEquals(firstModified - 10_000, file.lastModified())
            assertTrue(ChineseCustomDict.write(dir, "b"))
            assertEquals("b", file.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writeCreatesMissingDirectory() {
        val dir = Files.createTempDirectory("rime-user").toFile()
        try {
            val nested = File(dir, "user")
            assertTrue(ChineseCustomDict.write(nested, "x"))
            assertEquals("x", File(nested, ChineseCustomDict.FILE_NAME).readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writeReturnsFalseWhenDirectoryIsAFile() {
        val dir = Files.createTempDirectory("rime-user").toFile()
        try {
            val notADir = File(dir, "user").also { it.writeText("") }
            assertFalse(ChineseCustomDict.write(notADir, "x"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ChineseCustomDictTest'`
Expected: 編譯失敗，`Unresolved reference: ChineseCustomDict`。

- [ ] **Step 3: 實作**

```kotlin
package org.futo.inputmethod.engine.general

import org.futo.inputmethod.latin.uix.PersonalWord
import java.io.File
import java.io.IOException

/**
 * The personal dictionary's Chinese words as a small Rime dictionary. ChineseIME writes it to
 * the Rime user directory; the bundled `futo_custom_bopomofo` schema compiles it and
 * `futo_bopomofo` reads it through `table_translator@custom_dict`.
 *
 * Pure Kotlin (no Android imports) so it can be unit tested on the JVM.
 */
object ChineseCustomDict {
    const val DICT_NAME = "futo_custom"
    const val FILE_NAME = "$DICT_NAME.dict.yaml"

    data class Entry(val text: String, val code: String, val weight: Int)

    /** Whether a UserDictionary locale string ("zh", "zh_TW", "zh-Hant-TW") is Chinese. */
    fun isChinese(locale: String?): Boolean =
        locale?.substringBefore('_')?.substringBefore('-')?.lowercase() == "zh"

    /**
     * Chinese words whose shortcut holds a parsable Zhuyin reading. Words for "all languages"
     * (null locale) carry no reading and are skipped, as are words that would break the
     * tab-separated dictionary format.
     */
    fun entries(words: List<PersonalWord>): List<Entry> = words.mapNotNull { word ->
        if (!isChinese(word.locale)) return@mapNotNull null
        val text = word.word
        if (text.isBlank() || text.any { it == '\t' || it == '\n' || it == '\r' }) return@mapNotNull null
        val code = ZhuyinSyllables.toLunaPinyin(word.shortcut ?: return@mapNotNull null)
            ?: return@mapNotNull null
        Entry(text, code, word.frequency.coerceAtLeast(1))
    }

    /** The full text of `futo_custom.dict.yaml`; a valid (empty) dictionary when there are no entries. */
    fun render(entries: List<Entry>): String = buildString {
        appendLine("# Rime dictionary")
        appendLine("# Generated by FUTO Keyboard from the personal dictionary. Do not edit.")
        appendLine("---")
        appendLine("name: $DICT_NAME")
        appendLine("version: \"1\"")
        appendLine("sort: by_weight")
        appendLine("use_preset_vocabulary: false")
        appendLine("...")
        entries.forEach { appendLine("${it.text}\t${it.code}\t${it.weight}") }
    }

    /**
     * Writes [content] to `userDir/futo_custom.dict.yaml` unless the file already holds it.
     * @return true if the file now holds [content]; false if it could not be written
     * (the caller logs and retries on the next configuration update).
     */
    fun write(userDir: File, content: String): Boolean {
        val file = File(userDir, FILE_NAME)
        return try {
            if (file.isFile && file.readText() == content) return true
            if (!userDir.isDirectory && !userDir.mkdirs()) return false
            file.writeText(content)
            true
        } catch (e: IOException) {
            false
        }
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ChineseCustomDictTest'`
Expected: `BUILD SUCCESSFUL`，全部通過。若 `PersonalWord` 因 `SettingsExporter.kt` 的其他內容在 JVM 上載入失敗，把測試改為建構 `PersonalWord` 時只用具名參數（已如此）並確認錯誤訊息；`PersonalWord` 本身是純資料類別，應可載入。

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseCustomDict.kt tests/unit/org/futo/inputmethod/engine/general/ChineseCustomDictTest.kt
git commit -m "Add ChineseCustomDict: personal words as a Rime dictionary

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014T549UmBbW1T2x56SfLxdH"
```

---

### Task 3: Rime 方案檔與 `BundledRimeAssets`

**Files:**
- Create: `java/assets/rime/futo_custom_bopomofo.schema.yaml`
- Modify: `java/assets/rime/futo_bopomofo.schema.yaml`（`schema/dependencies`、`engine/translators`、新增 `custom_dict` 段）
- Modify: `java/assets/rime/futo_bopomofo_terra.schema.yaml`（`schema/dependencies`）
- Modify: `java/src/org/futo/inputmethod/engine/general/BundledRimeAssets.kt:23-30`

**Interfaces:**
- Produces: 方案 id `futo_custom_bopomofo`；translator 段 `custom_dict`（讀 `futo_custom` 詞典）。Task 4 依賴這些名稱。

- [ ] **Step 1: 新增依賴方案檔**

寫入 `java/assets/rime/futo_custom_bopomofo.schema.yaml`：

```yaml
# Rime schema
# encoding: utf-8
#
# Dependency schema of futo_bopomofo / futo_bopomofo_terra. It is never selected as an input
# schema; it only exists so that the deployer compiles the personal dictionary
# (futo_custom.dict.yaml, written by FUTO Keyboard into the Rime user directory) together
# with a Zhuyin prism that uses the same spelling rules as the Zhuyin schemas. The tone rules
# are always the luna_tones ones because the personal dictionary carries no tones.

schema:
  schema_id: futo_custom_bopomofo
  name: 注音個人字典
  version: "1.0"
  author:
    - FUTO Keyboard
  description: |
    個人字典的注音詞典（僅供 futo_bopomofo 方案引用）。

engine:
  processors:
    - speller
  segmentors:
    - abc_segmentor
  translators:
    - table_translator

speller:
  alphabet: '1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'
  initials: '1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-'
  finals: " 6347"
  delimiter: "'"
  use_space: true
  algebra:
    __patch:
      - futo_zhuyin:/pinyin_to_zhuyin
      - futo_zhuyin:/luna_tones
      - futo_zhuyin:/free_order
      - futo_zhuyin:/abbreviation
      - futo_zhuyin:/keymap_bopomofo

translator:
  dictionary: futo_custom
  prism: futo_custom_bopomofo
  enable_completion: false
  enable_sentence: false
  enable_user_dict: false
```

- [ ] **Step 2: 修改 `futo_bopomofo.schema.yaml`**

`schema/dependencies` 由

```yaml
  dependencies:
    - stroke
```

改為

```yaml
  dependencies:
    - stroke
    - futo_custom_bopomofo
```

`engine/translators` 由

```yaml
  translators:
    - punct_translator
    - table_translator@custom_phrase
    - reverse_lookup_translator
    - script_translator
```

改為

```yaml
  translators:
    - punct_translator
    - table_translator@custom_dict
    - table_translator@custom_phrase
    - reverse_lookup_translator
    - script_translator
```

在 `custom_phrase:` 段之前插入：

```yaml
# 個人字典（設定 → 個人字典 → 中文）。FUTO Keyboard 依個人字典寫出 futo_custom.dict.yaml，
# 由依賴方案 futo_custom_bopomofo 編譯；此處只讀取。initial_quality 1 讓個人詞排在詞庫候選之前；
# 不開 completion，只有整個音節序列對應到個人詞時才出現，避免打單字時被插隊。
custom_dict:
  dictionary: futo_custom
  prism: futo_custom_bopomofo
  enable_completion: false
  enable_sentence: false
  enable_user_dict: false
  initial_quality: 1

```

- [ ] **Step 3: 修改 `futo_bopomofo_terra.schema.yaml`**

`schema/dependencies` 由

```yaml
  dependencies:
    - stroke
```

改為

```yaml
  dependencies:
    - stroke
    - futo_custom_bopomofo
```

（`engine` 與 `custom_dict` 段經 `__include: futo_bopomofo.schema:/` 繼承，不需重複。）

- [ ] **Step 4: 修改 `BundledRimeAssets.kt`**

```kotlin
    /** Bump whenever any file under assets/rime changes, so existing installs are refreshed. */
    const val VERSION = 2

    /** Copied into the Rime shared directory (recreated whenever the dictionary is re-extracted). */
    val SHARED_FILES = listOf(
        "futo_zhuyin.yaml",
        "futo_bopomofo.schema.yaml",
        "futo_bopomofo_terra.schema.yaml",
        "futo_custom_bopomofo.schema.yaml",
    )
```

- [ ] **Step 5: 驗證 YAML 可解析**

Run:
```bash
python3 - <<'EOF'
import yaml, glob
for f in glob.glob('java/assets/rime/*.yaml'):
    yaml.safe_load(open(f, encoding='utf-8'))
    print('ok', f)
EOF
```
Expected: 每個檔案印出 `ok`。若沒有 PyYAML（`ModuleNotFoundError`），改跑 `python3 -m pip install --user pyyaml` 後重試；仍不行則跳過，改由 Task 6 的裝置部署日誌驗證。

- [ ] **Step 6: Commit**

```bash
git add java/assets/rime/futo_custom_bopomofo.schema.yaml java/assets/rime/futo_bopomofo.schema.yaml java/assets/rime/futo_bopomofo_terra.schema.yaml java/src/org/futo/inputmethod/engine/general/BundledRimeAssets.kt
git commit -m "Add the personal dictionary translator to the Zhuyin schemas

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014T549UmBbW1T2x56SfLxdH"
```

---

### Task 4: `ChineseIME` 寫詞典並回應 `ReloadPersonalDict`

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`
  - import 區（約第 51-69 行）
  - `GlobalIMEMessage.collect`（約第 537-553 行）
  - `data class Configuration`（約第 599-605 行）
  - `updateConfig()`（約第 653-760 行）

**Interfaces:**
- Consumes: `ChineseCustomDict.entries / render / write / FILE_NAME`（Task 2）；
  `org.futo.inputmethod.latin.uix.UserDictionaryIO(context).get(): List<PersonalWord>`（既有）；
  `IMEMessage.ReloadPersonalDict`（既有，`IMEManager.kt`）。

- [ ] **Step 1: 加 import**

在 `import org.futo.inputmethod.latin.uix.SettingsKey` 之後加：

```kotlin
import org.futo.inputmethod.latin.uix.UserDictionaryIO
```

- [ ] **Step 2: `Configuration` 加內容指紋**

```kotlin
    private data class Configuration(
        val schema: String,
        val learning: Boolean,
        val simplification: Boolean,
        val autocorrect: Boolean,
        val fuzzyMode: Set<ChineseIMESettings.FuzzyPinyinModes>,
        /** hashCode of the rendered futo_custom.dict.yaml; a change redeploys so Rime recompiles it. */
        val customDictHash: Int
    )
```

- [ ] **Step 3: `updateConfig()` 讀個人字典、寫詞典**

`val learning = settings.isPersonalizationEnabled` 之後、`val config = Configuration(...)` 改為：

```kotlin
                val learning = settings.isPersonalizationEnabled

                // The personal dictionary's Chinese words as a Rime dictionary (see
                // ChineseCustomDict). Rendered on every update so that the hash below notices
                // changes; the file itself is only rewritten when the content differs.
                val customDict = ChineseCustomDict.render(
                    ChineseCustomDict.entries(UserDictionaryIO(helper.context).get())
                )

                val config = Configuration(
                    schema, learning, simplified, autocorrect, fuzzy, customDict.hashCode()
                )
```

`rimeLoading.value = true` 之前（`if (config != prevConfiguration) { ... }` 區塊內、重試預算判斷之後）插入：

```kotlin
                    if(!ChineseCustomDict.write(getUser(helper.context), customDict)) {
                        // Leave prevConfiguration untouched so the next update retries; Rime keeps
                        // using the last successfully compiled personal dictionary.
                        Log.e(TAG, "Failed to write ${ChineseCustomDict.FILE_NAME}, not deploying")
                        return@launch
                    }

                    rimeLoading.value = true
                    writeCustomizationFile(config)
                    rime.deploy()
```

- [ ] **Step 4: 處理 `ReloadPersonalDict`**

`GlobalIMEMessage.collect` 的 `when` 由

```kotlin
                    else -> {}
```

改為

```kotlin
                    IMEMessage.ReloadPersonalDict -> updateConfig()
                    else -> {}
```

（`updateConfig()` 自己會比較 hash：中文詞有變才重新部署，改了別的語言的詞則什麼都不做。）

- [ ] **Step 5: 編譯**

Run: `./gradlew assembleUnstableDebug`
Expected: `BUILD SUCCESSFUL`。若 `when` 因 enum 已被完全列舉而警告 `else` 多餘，保留 `else -> {}` 即可（不是錯誤）。

- [ ] **Step 6: 執行全部單元測試**

Run: `./gradlew testUnstableDebugUnitTest`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 7: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseIME.kt
git commit -m "Feed the personal dictionary into the Zhuyin schema

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014T549UmBbW1T2x56SfLxdH"
```

---

### Task 5: 設定頁的注音詞對話框

**Files:**
- Create: `java/src/org/futo/inputmethod/latin/uix/settings/pages/pdict/PersonalDictionaryZh.kt`
- Modify: `java/src/org/futo/inputmethod/latin/uix/settings/pages/pdict/PersonalDictionary.kt:66-75`

**Interfaces:**
- Consumes: `ZhuyinSyllables.toLunaPinyin`（Task 1）；既有 `SettingsTextEdit(text: MutableState<String>, placeholder: String?, error: Boolean)`（`ActionTextEdit.kt`）、`UserDictionaryIO.put/remove`、`GlobalIMEMessage`、`IMEMessage.ReloadPersonalDict`、`LocalNavController`。
- Produces: `@Composable fun ZhuyinWordPopupDialog(selectedWord: PersonalWord? = null, locale: Locale? = null)`。

- [ ] **Step 1: 新增對話框檔案**

```kotlin
package org.futo.inputmethod.latin.uix.settings.pages.pdict

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.engine.GlobalIMEMessage
import org.futo.inputmethod.engine.IMEMessage
import org.futo.inputmethod.engine.general.ZhuyinSyllables
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.LocalNavController
import org.futo.inputmethod.latin.uix.PersonalWord
import org.futo.inputmethod.latin.uix.SettingsTextEdit
import org.futo.inputmethod.latin.uix.UserDictionaryIO
import java.util.Locale

private const val ZHUYIN_SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦ"
private const val ZHUYIN_TONES = "ˉˊˇˋ˙"

/** Frequency stored for words added here; UserDictionary accepts 1..255. */
private const val DEFAULT_FREQUENCY = 250

/**
 * Add/edit dialog for Chinese personal words. The word is typed normally; the Zhuyin reading
 * is entered with the built-in symbol keypad so it does not depend on which keyboard is
 * active (and is not turned into Hanzi by the IME). Stored as PersonalWord(word = Hanzi,
 * shortcut = Zhuyin); ChineseIME turns the reading into a Rime dictionary entry.
 */
@Composable
fun ZhuyinWordPopupDialog(selectedWord: PersonalWord? = null, locale: Locale? = null) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val word = remember { mutableStateOf(selectedWord?.word ?: "") }
    val zhuyin = remember { mutableStateOf(selectedWord?.shortcut ?: "") }

    val readingValid = ZhuyinSyllables.toLunaPinyin(zhuyin.value) != null
    val isError = zhuyin.value.isNotEmpty() && !readingValid

    fun finish() {
        GlobalIMEMessage.tryEmit(IMEMessage.ReloadPersonalDict)
        navController!!.navigateUp()
    }

    AlertDialog(
        icon = { },
        title = {
            Text(
                if (selectedWord == null) {
                    stringResource(R.string.user_dict_settings_add_dialog_title)
                } else {
                    stringResource(R.string.user_dict_settings_edit_dialog_title)
                }
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.user_dict_settings_add_word_option_name))
                SettingsTextEdit(word, placeholder = "...")

                Spacer(Modifier.height(12.dp))

                Text("注音：")
                Text(
                    text = zhuyin.value.ifEmpty { " " },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(8.dp)
                )
                if (isError) {
                    Text(
                        text = "注音不完整或有誤",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(8.dp))

                ZhuyinKeypad(
                    onSymbol = { zhuyin.value += it },
                    onBackspace = { zhuyin.value = zhuyin.value.dropLast(1) }
                )
            }
        },
        onDismissRequest = {
            navController!!.navigateUp()
        },
        confirmButton = {
            Row {
                if (selectedWord != null) {
                    TextButton(
                        onClick = {
                            UserDictionaryIO(context).remove(listOf(selectedWord))
                            finish()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.user_dict_settings_delete))
                    }
                }
                Spacer(Modifier.weight(1.0f))

                TextButton(onClick = { navController!!.navigateUp() }) {
                    Text(stringResource(R.string.action_emoji_clear_recent_emojis_cancel))
                }

                TextButton(
                    onClick = {
                        val udictIo = UserDictionaryIO(context)
                        if (selectedWord != null) {
                            // Edit existing word by deleting it then re-inserting the new one
                            udictIo.remove(listOf(selectedWord))
                        }
                        udictIo.put(
                            listOf(
                                PersonalWord(
                                    word = word.value.trim(),
                                    frequency = DEFAULT_FREQUENCY,
                                    locale = locale?.toString(),
                                    appId = 0,
                                    shortcut = zhuyin.value
                                )
                            ),
                            clear = false
                        )
                        finish()
                    },
                    enabled = word.value.isNotBlank() && zhuyin.value.isNotEmpty() && readingValid
                ) {
                    Text(stringResource(R.string.user_dict_settings_add_dialog_confirm))
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZhuyinKeypad(onSymbol: (Char) -> Unit, onBackspace: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ZHUYIN_SYMBOLS.forEach { symbol ->
                ZhuyinKey(symbol.toString()) { onSymbol(symbol) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZHUYIN_TONES.forEach { tone ->
                ZhuyinKey(tone.toString()) { onSymbol(tone) }
            }
            Spacer(Modifier.weight(1.0f))
            ZhuyinKey("⌫", onClick = onBackspace)
        }
    }
}

@Composable
private fun ZhuyinKey(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
```

- [ ] **Step 2: 開放中文**

`PersonalDictionary.kt` 的

```kotlin
    "zh" to ExceptionalPersonalDictionaryViewConfiguration(supported = false)
```

改為

```kotlin
    "zh" to ExceptionalPersonalDictionaryViewConfiguration(
        supported = true,
        wordPopupDialog = { word, locale -> ZhuyinWordPopupDialog(word, locale) }
    )
```

（`transformNavWord` 用預設值：列表直接顯示 `word` 與 `shortcut`，也就是詞與注音。）

- [ ] **Step 3: 編譯**

Run: `./gradlew assembleUnstableDebug`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: Commit**

```bash
git add java/src/org/futo/inputmethod/latin/uix/settings/pages/pdict/PersonalDictionaryZh.kt java/src/org/futo/inputmethod/latin/uix/settings/pages/pdict/PersonalDictionary.kt
git commit -m "Add the Zhuyin personal dictionary dialog

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_014T549UmBbW1T2x56SfLxdH"
```

---

### Task 6: 真機驗證

**Files:** 無新檔；驗證期間發現的問題各自另開 commit 修正。

- [ ] **Step 1: 安裝**

Run:
```bash
./gradlew assembleUnstableDebug
adb install -r build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk
```
Expected: `Success`。（小米若失敗，確認「USB 安裝」「USB 偵錯（安全設定）」已開。）

- [ ] **Step 2: 確認部署成功**

先在任一文字框叫出注音鍵盤一次（觸發 `onStartInput` → bundled assets 版本 2 → 重新部署），再看日誌：

Run: `adb logcat -d | grep -iE 'futo_custom|custom_dict|rime.*(error|fail)' | tail -30`
Expected: 出現 `futo_custom_bopomofo` 的編譯訊息；`futo_custom.dict.yaml` 相關沒有 `not found`。
Run（Rime 目錄是 `context.getExternalFilesDir("rime")`，unstable 套件名為 `org.futo.inputmethod.latin.unstable`）：
```bash
adb shell ls /sdcard/Android/data/org.futo.inputmethod.latin.unstable/files/rime/user/
adb shell ls /sdcard/Android/data/org.futo.inputmethod.latin.unstable/files/rime/user/build/ | grep futo_custom
```
Expected: 第一個列出 `futo_custom.dict.yaml`；第二個列出 `futo_custom.table.bin` 與 `futo_custom_bopomofo.prism.bin`。

- [ ] **Step 3: 新增詞**

設定 → 個人字典 → 中文（列表現在應顯示中文語言列）→ 「+」→ 詞輸入一個詞典裡沒有或排序靠後的詞（例如「測試詞」），注音點 ㄘ ㄜ ˋ ㄕ ˋ ㄘ ˊ → 確定。
每次 `adb shell input tap` 前先 `adb exec-out screencap -p > /tmp/s.png` 看畫面。
Expected: 列表出現「測試詞」，副標題為 `ㄘㄜˋㄕˋㄘˊ`。

- [ ] **Step 4: 候選驗證**

在任一文字框切到注音布局，打 ㄘㄜˋㄕˋㄘˊ。
Expected: 候選首位為「測試詞」。再打 ㄘㄜˋㄕˋㄘ（省略末聲調），首位仍為「測試詞」。打 ㄘㄜˋ 單字時「測試詞」不出現。

- [ ] **Step 5: 編輯與刪除**

回到個人字典點「測試詞」→ 改注音為 ㄘㄜˋㄕˋ → 確定；再回鍵盤打 ㄘㄜˋㄕˋ 應出現「測試詞」。再刪除該詞；打 ㄘㄜˋㄕˋ 不再出現。

- [ ] **Step 6: 清除學習詞庫後仍有效**

再加回「測試詞 / ㄘㄜˋㄕˋㄘˊ」，設定 → 預測文字 → 清除學習詞庫（會刪掉 Rime user 目錄），回鍵盤打 ㄘㄜˋㄕˋㄘˊ。
Expected: 首位仍為「測試詞」（`clearUserHistoryDictionaries` 之後的 `updateConfig()` 重寫了詞典）。

- [ ] **Step 7: 記錄結果**

把上述每項結果（通過 / 失敗與現象）寫進 `.superpowers/sdd/progress.md`（若目錄不存在則建立）；失敗項修正後另開 commit，訊息說明修了什麼。
