# 注音輸出台灣字形 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 注音輸出台灣字形（為、吃、眾、裡），聯想詞與讀音篩選跟著使用台灣字形。

**Architecture:** Rime 方案加一個 OpenCC t2tw 濾鏡（開關由 App 的 custom patch 控制，簡體輸出時關閉）；Kotlin 的 `TaiwanVariants` 以同一份 OpenCC 對照表把 `essay.txt` 與讀音表的字轉成台灣字形。

**Tech Stack:** Rime schema YAML、Kotlin、JUnit 4。

Spec：`docs/superpowers/specs/2026-10-23-zhuyin-taiwan-forms-design.md`

## Global Constraints

- 只影響注音方案（`futo_bopomofo`、`futo_bopomofo_terra`）；拼音、筆畫方案的 custom patch 不變。
- 簡體輸出（`cfg.simplification`）時 `futo_zh_tw` 為 0，否則為 1。
- 對照表來源：OpenCC `TWVariants.txt`（Apache-2.0），只用一對一、且來源與目標不同的 40 組。
- Commit 結尾必須是這兩行（前面空一行）：
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01SxYaJPcW5bNCc9K2YVp6xA
  ```
- 測試：`./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`；建置：`./gradlew assembleUnstableDebug`。

---

### Task 1: 台灣字形

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/TaiwanVariants.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/TaiwanVariantsTest.kt`
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseAssociation.kt`（`parse`）
- Modify: `tests/unit/org/futo/inputmethod/engine/general/ChineseAssociationTest.kt`（加一個測試）
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseCharReadings.kt`（`parse`）
- Modify: `tests/unit/org/futo/inputmethod/engine/general/ChineseCharReadingsTest.kt`（加一個測試）
- Modify: `java/assets/rime/futo_bopomofo.schema.yaml`
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`（`writeCustomizationFile`）
- Modify: `java/src/org/futo/inputmethod/engine/general/BundledRimeAssets.kt`（`VERSION` 4 → 5）
- Modify: `java/src/org/futo/inputmethod/latin/uix/settings/pages/credits/ThirdPartyList.kt`

**Interfaces:**
- Produces: `object TaiwanVariants { fun toTaiwan(text: String): String }`

- [ ] **Step 1: 寫失敗測試**

`tests/unit/org/futo/inputmethod/engine/general/TaiwanVariantsTest.kt`：

```kotlin
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
```

在 `ChineseAssociationTest` 加：

```kotlin
    @Test
    fun indexesTaiwanForms() {
        val association = parse("爲了\t9", "喫飯\t5")
        assertEquals(listOf("了"), association.lookup("為"))
        assertEquals(listOf("飯"), association.lookup("吃"))
    }
```

（`parse` 是該測試類別既有的 helper：`ChineseAssociation.parse(StringReader(lines.joinToString("\n")))`。）

在 `ChineseCharReadingsTest` 加：

```kotlin
    @Test
    fun alsoReadsTheTaiwanForm() {
        val r = parse("...\n爲\twei\n裏\tli")
        assertTrue(r.has("爲", "wei"))
        assertTrue(r.has("為", "wei"))
        assertTrue(r.has("裡", "li"))
    }
```

- [ ] **Step 2: 確認測試失敗**（`Unresolved reference: TaiwanVariants`）。

- [ ] **Step 3: 實作 `TaiwanVariants`**

`java/src/org/futo/inputmethod/engine/general/TaiwanVariants.kt`：

```kotlin
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
```

- [ ] **Step 4: 聯想詞與讀音表**

`ChineseAssociation.parse` 中，`val word = line.substring(0, tab)` 改成：

```kotlin
                // essay.txt uses OpenCC's traditional forms; the Zhuyin schema commits Taiwan forms.
                val word = TaiwanVariants.toTaiwan(line.substring(0, tab))
```

`ChineseCharReadings.parse` 中，在加入 `(ch, spelling)` 那一筆之後，若 `TaiwanVariants.toTaiwan(ch) != ch`，再以台灣字形的 code point 加入同一個拼音（同樣進 `pairs`，排序與去重沿用既有邏輯）。

- [ ] **Step 5: 確認單元測試通過**：`./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*'`，全部 PASS。

- [ ] **Step 6: Rime 方案**

`java/assets/rime/futo_bopomofo.schema.yaml` 的 `engine/filters`：

```yaml
  filters:
    - simplifier
    - simplifier@zh_tw
    - uniquifier
```

並在檔案中 `simplifier` 相關設定附近（若沒有，就在 `translator:` 區塊之前）加入頂層區塊：

```yaml
# Taiwan character forms (爲→為, 喫→吃, 衆→眾, 裏→裡). FUTO Keyboard's custom patch declares the
# futo_zh_tw switch: on, except with simplified output (t2tw would also turn simplified 着 into 著).
zh_tw:
  opencc_config: t2tw.json
  option_name: futo_zh_tw
```

`futo_bopomofo_terra.schema.yaml` 經由 `__include: futo_bopomofo.schema:/` 共用 filters 與 `zh_tw`，不用改。

- [ ] **Step 7: 開關**

`ChineseIME.writeCustomizationFile` 中，在 `futo_zh_simp` 那個 switch 項目之後（仍在 `switches/+:` 清單內）加：

```kotlin
            // Taiwan character forms, Zhuyin only (see futo_bopomofo.schema.yaml).
            if(schema.startsWith("futo_bopomofo")) {
                appendLine("        - name: futo_zh_tw")
                appendLine("          reset: ${if(cfg.simplification) 0 else 1}")
                appendLine("          states: [ 傳統, 臺灣 ]")
            }
```

`BundledRimeAssets.VERSION` 由 4 改為 5。

- [ ] **Step 8: 致謝**

在 `ThirdPartyList.kt` 的 "RIME" 條目之後加：

```kotlin
    TP(
        "OpenCC",
        "Taiwan character variants table - OpenCC",
        "https://github.com/BYVoid/OpenCC",
        "Copyright (c) Carbo Kuo and contributors",
        License.Apache2
    ),
```

- [ ] **Step 9: 建置與測試**：`./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*' assembleUnstableDebug`，BUILD SUCCESSFUL、全部 PASS、改動的 Kotlin 檔沒有新警告（ChineseIME.kt 既有一個 `locale` 棄用警告）。

- [ ] **Step 10: Commit**："Show Taiwan character forms in Zhuyin"，結尾兩行 trailer。
