# 注音輸入法（Zhuyin）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 為 FUTO Keyboard 的中文輸入新增標準大千式注音鍵盤，透過 App 內建的 Rime 注音方案組字，不需使用者下載新詞庫。

**Architecture:** 新布局 `zh_bopomofo`（`imeHint: "bopomofo"`）按鍵直接輸出注音 Unicode 字元；`ChineseIME` 用 `ZhuyinKeymap` 把注音字元轉成大千式 ASCII 鍵送入 Rime，數字行的數字則不進 Rime 直接提交。App 內建 `futo_bopomofo`（luna_pinyin 詞庫、聲調只作分隔）與 `futo_bopomofo_terra`（terra_pinyin 詞庫）兩個 Rime 方案，由 `BundledRimeAssets` 寫入 Rime 目錄，執行時依 `shared/terra_pinyin.dict.yaml` 是否存在選方案。

**Tech Stack:** Kotlin / Android、librime（`libs/rime-release.aar`，`icu.astronot233.rime.Rime`）、YAML 布局（子模組 `java/assets/layouts`）、JUnit4（JVM unit test 與 instrumented test）。

Spec：`docs/superpowers/specs/2026-08-30-zhuyin-input-design.md`

## Global Constraints

- 工作分支：`zhuyin-input`（已建立）。子模組 `java/assets/layouts` 內另建分支 `zhuyin-layout` 提交。
- Rime 大千式鍵位字串（方案 alphabet）固定為 `'1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'`；`initials` 為 `'1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-'`；`finals` 為 `" 6347"`。
- 注音字元順序（與 alphabet 一一對應）：`ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙`（ˉ 對應空白＝一聲；ˊ U+02CA、ˇ U+02C7、ˋ U+02CB、˙ U+02D9）。
- 方案 id：`futo_bopomofo`、`futo_bopomofo_terra`；`imeHint` 值：`"bopomofo"`。
- 非 `"bopomofo"` hint 的 `ChineseIME` 行為不得改變。
- 不新增使用者設定項；不做 OpenCC t2tw；不做其他鍵位變體。
- 提交訊息結尾需附：
  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ
  ```
- Gradle 任務名稱：JVM 單元測試 `./gradlew testUnstableDebugUnitTest`；組 APK `./gradlew assembleUnstableDebug`；instrumented 測試需連接裝置：`./gradlew connectedUnstableDebugAndroidTest`。

## File Structure

| 檔案 | 責任 |
|---|---|
| `java/assets/layouts/Chinese/zh_bopomofo.yaml`（子模組） | 注音鍵盤布局定義 |
| `java/assets/layouts/mapping.yaml`（子模組） | 把 `zh`、`zh_Hant` 對應到新布局 |
| `java/assets/layouts/LayoutSpec.md`（子模組） | 文件：`imeHint` 合法值 |
| `java/src/org/futo/inputmethod/engine/general/ZhuyinKeymap.kt` | 注音字元 ↔ Rime ASCII 鍵映射（純 Kotlin，無 Android 相依） |
| `tests/unit/org/futo/inputmethod/engine/general/ZhuyinKeymapTest.kt` | 映射的 JVM 單元測試 |
| `tests/unit/org/futo/inputmethod/v2keyboard/ZhuyinLayoutTest.kt` | 布局 YAML 解析的 JVM 單元測試 |
| `build.gradle` | 新增 `test` sourceSet 指向 `tests/unit` |
| `java/assets/rime/futo_zhuyin.yaml` | 拼寫運算規則 |
| `java/assets/rime/futo_bopomofo.schema.yaml` | luna_pinyin 版注音方案 |
| `java/assets/rime/futo_bopomofo_terra.schema.yaml` | terra_pinyin 版注音方案 |
| `java/assets/rime/default.custom.yaml` | 把兩個方案加進 `schema_list` |
| `java/src/org/futo/inputmethod/engine/general/BundledRimeAssets.kt` | 把 assets/rime 寫入 Rime 目錄 |
| `tests/src/org/futo/inputmethod/engine/general/BundledRimeAssetsTests.kt` | instrumented 測試 |
| `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt` | 接上 hint、方案選擇、按鍵處理、預編輯轉換 |

---

### Task 1: `ZhuyinKeymap` 與 JVM 單元測試基礎

**Files:**
- Modify: `build.gradle:359-363`（`sourceSets` 區塊，新增 `test`）
- Create: `java/src/org/futo/inputmethod/engine/general/ZhuyinKeymap.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/ZhuyinKeymapTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  object ZhuyinKeymap {
      const val RIME_ALPHABET: String   // '1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'
      val toRimeKey: Map<Char, Char>     // ㄅ -> '1'
      val toZhuyin: Map<Char, Char>      // '1' -> ㄅ
      val alphabet: Set<Char>            // RIME_ALPHABET 的字元集合
      fun rimeKeyFor(codePoint: Int): Char?
      fun isToneKey(c: Char?): Boolean   // c in " 6347"
  }
  ```

- [ ] **Step 1: 在 `build.gradle` 加入 JVM 單元測試 sourceSet**

在 `build.gradle` 的 `sourceSets { ... }` 內、`androidTest { ... }` 之前加入：

```groovy
        test {
            java.srcDirs = ['tests/unit']
        }
```

- [ ] **Step 2: 寫失敗的測試**

建立 `tests/unit/org/futo/inputmethod/engine/general/ZhuyinKeymapTest.kt`：

```kotlin
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
```

- [ ] **Step 3: 執行測試確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinKeymapTest'`
Expected: 編譯失敗，`Unresolved reference: ZhuyinKeymap`

- [ ] **Step 4: 實作 `ZhuyinKeymap`**

建立 `java/src/org/futo/inputmethod/engine/general/ZhuyinKeymap.kt`：

```kotlin
package org.futo.inputmethod.engine.general

/**
 * Maps Zhuyin (Bopomofo) symbols to the ASCII keys of the standard (大千式) keyboard layout
 * that the Rime bopomofo schema expects, and back.
 *
 * The key strings below must stay in sync with `speller/alphabet` in
 * `assets/rime/futo_bopomofo.schema.yaml`.
 */
object ZhuyinKeymap {
    private const val ZHUYIN_SYMBOLS = "ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙"

    /** Rime `speller/alphabet`. Space is tone 1, `6347` are tones 2, 3, 4 and neutral. */
    const val RIME_ALPHABET = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347"

    private const val TONE_KEYS = " 6347"

    /** Zhuyin symbol (or tone mark) to the ASCII key sent to Rime. */
    val toRimeKey: Map<Char, Char> = ZHUYIN_SYMBOLS.zip(RIME_ALPHABET).toMap()

    /** ASCII Rime key to the Zhuyin symbol shown to the user. */
    val toZhuyin: Map<Char, Char> = RIME_ALPHABET.zip(ZHUYIN_SYMBOLS).toMap()

    /** Every character Rime accepts as part of a Zhuyin spelling. */
    val alphabet: Set<Char> = RIME_ALPHABET.toSet()

    /** The Rime key for a Zhuyin code point, or null if the code point is not a Zhuyin symbol. */
    fun rimeKeyFor(codePoint: Int): Char? {
        if (codePoint < 0 || codePoint > Char.MAX_VALUE.code) return null
        return toRimeKey[codePoint.toChar()]
    }

    /** Whether [c] is one of the tone keys (space, 6, 3, 4, 7). */
    fun isToneKey(c: Char?): Boolean = c != null && c in TONE_KEYS
}
```

- [ ] **Step 5: 執行測試確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinKeymapTest'`
Expected: `BUILD SUCCESSFUL`，5 tests passed。

若失敗訊息為 Android 類別 `Method ... not mocked`，表示 JVM 測試載入了 Android stub；此測試不應碰到 Android 類別，請確認 `ZhuyinKeymap.kt` 沒有 import 任何 `android.*`。

- [ ] **Step 6: Commit**

```bash
git add build.gradle java/src/org/futo/inputmethod/engine/general/ZhuyinKeymap.kt tests/unit/org/futo/inputmethod/engine/general/ZhuyinKeymapTest.kt
git commit -m "Add ZhuyinKeymap and JVM unit test source set

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 2: 注音鍵盤布局（layouts 子模組）

**Files:**
- Create: `java/assets/layouts/Chinese/zh_bopomofo.yaml`
- Modify: `java/assets/layouts/mapping.yaml:921-926`
- Modify: `java/assets/layouts/LayoutSpec.md:421`
- Test: `tests/unit/org/futo/inputmethod/v2keyboard/ZhuyinLayoutTest.kt`

**Interfaces:**
- Consumes: `org.futo.inputmethod.v2keyboard.parseKeyboardYamlString(yamlString: String): Keyboard`（`LayoutManager.kt:179`）；`Keyboard.rows: List<Row>`、`Row.isLetterRow`、`Row.keys.size`、`Keyboard.imeHint`。
- Produces: 布局 id `zh_bopomofo`，`imeHint == "bopomofo"`。

- [ ] **Step 1: 寫失敗的測試**

建立 `tests/unit/org/futo/inputmethod/v2keyboard/ZhuyinLayoutTest.kt`（JVM 測試的工作目錄是專案根目錄，因此可直接讀 `java/assets/...`）：

```kotlin
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
        val letterRows = keyboard.rows.filter { it.isLetterRow }
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
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.v2keyboard.ZhuyinLayoutTest'`
Expected: 3 tests FAIL（`FileNotFoundException: java/assets/layouts/Chinese/zh_bopomofo.yaml` 與 mapping 斷言失敗）。

若失敗原因是 `java.lang.RuntimeException: Method ... not mocked`（`parseKeyboardYamlString` 觸碰到 Android stub），在 `build.gradle` 的 `android { ... }` 區塊內加入：

```groovy
    testOptions {
        unitTests.returnDefaultValues = true
    }
```

然後重跑，應只剩 `FileNotFoundException` 類的失敗。

- [ ] **Step 3: 在子模組建立分支並新增布局檔**

```bash
git -C java/assets/layouts checkout -b zhuyin-layout
```

建立 `java/assets/layouts/Chinese/zh_bopomofo.yaml`：

```yaml
name: Chinese Zhuyin (注音)
languages: zh
description: Standard (大千式) Zhuyin keyboard. Tone 1 is entered with the space bar.
attributes: {moreKeyMode: OnlyExplicit}
autoShift: false
imeHint: "bopomofo"
rows:
  - letters: ['ㄅ', 'ㄉ', 'ˇ', 'ˋ', 'ㄓ', 'ˊ', '˙', 'ㄚ', 'ㄞ', 'ㄢ', 'ㄦ']
  - letters: ['ㄆ', 'ㄊ', 'ㄍ', 'ㄐ', 'ㄔ', 'ㄗ', 'ㄧ', 'ㄛ', 'ㄟ', 'ㄣ']
  - letters: ['ㄇ', 'ㄋ', 'ㄎ', 'ㄑ', 'ㄕ', 'ㄘ', 'ㄨ', 'ㄜ', 'ㄠ', 'ㄤ']
  - letters: ['ㄈ', 'ㄌ', 'ㄏ', 'ㄒ', 'ㄖ', 'ㄙ', 'ㄩ', 'ㄝ', 'ㄡ', 'ㄥ', $delete]
  - bottom:
    - $symbols
    - ['，']
    - $action
    - $space
    - type: base
      spec: '。'
      moreKeys: ['…|……', '？', '！', '；', '：', '—|——']
      attributes: { moreKeyMode: OnlyExplicit }
    - $enter
```

- [ ] **Step 4: 更新 `mapping.yaml`**

把 `java/assets/layouts/mapping.yaml` 中

```yaml
  zh:
    - zh_qwerty
    - zh_stroke
  zh_Hant:
    - zh_qwerty
    - zh_stroke
```

改為

```yaml
  zh:
    - zh_qwerty
    - zh_stroke
    - zh_bopomofo
  zh_Hant:
    - zh_qwerty
    - zh_stroke
    - zh_bopomofo
```

- [ ] **Step 5: 更新 `LayoutSpec.md`**

把 `java/assets/layouts/LayoutSpec.md` 第 421 行

```
Valid values for Chinese are "stroke" or "qwerty" to specify which schema should be used.
```

改為

```
Valid values for Chinese are "stroke", "qwerty" or "bopomofo" to specify which schema should be used. With "bopomofo", keys output Zhuyin symbols (ㄅ, ㄆ, ˊ, ˇ...) directly and the IME maps them to the standard (大千式) keyboard for Rime.
```

並在其後的連結清單加入一行：

```
* [Chinese Zhuyin](https://github.com/futo-org/futo-keyboard-layouts/blob/main/Chinese/zh_bopomofo.yaml)
```

- [ ] **Step 6: 執行測試確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.v2keyboard.ZhuyinLayoutTest'`
Expected: `BUILD SUCCESSFUL`，3 tests passed。

- [ ] **Step 7: Commit（子模組與主倉庫）**

```bash
git -C java/assets/layouts add Chinese/zh_bopomofo.yaml mapping.yaml LayoutSpec.md
git -C java/assets/layouts commit -m "Add Chinese Zhuyin (bopomofo) layout

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
git add java/assets/layouts tests/unit/org/futo/inputmethod/v2keyboard/ZhuyinLayoutTest.kt build.gradle
git commit -m "Add zh_bopomofo layout and layout parsing test

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

（`build.gradle` 只在 Step 2 有加 `testOptions` 時才需要加入。）

---

### Task 3: 內建 Rime 注音方案檔

**Files:**
- Create: `java/assets/rime/futo_zhuyin.yaml`
- Create: `java/assets/rime/futo_bopomofo.schema.yaml`
- Create: `java/assets/rime/futo_bopomofo_terra.schema.yaml`
- Create: `java/assets/rime/default.custom.yaml`

**Interfaces:**
- Produces: assets 路徑 `rime/futo_zhuyin.yaml`、`rime/futo_bopomofo.schema.yaml`、`rime/futo_bopomofo_terra.schema.yaml`、`rime/default.custom.yaml`；方案 id `futo_bopomofo`、`futo_bopomofo_terra`。

這個 task 沒有可自動執行的測試（需要 librime 部署），驗證在 Task 6 真機進行。YAML 語法用 Step 5 檢查。

- [ ] **Step 1: 建立 `futo_zhuyin.yaml`**

```yaml
# Rime spelling algebra rules for Zhuyin
# Adapted from rime-bopomofo (zhuyin.yaml). `luna_tones` is a FUTO addition for
# dictionaries without tone data: every syllable also accepts any trailing tone.
# encoding: utf-8

pinyin_to_zhuyin:
  __append:
    - xform/^m(\d)$/mu$1/  # 呣
    - xform/^r5$/er5/  # 〜兒
    - xform/iu/iou/
    - xform/ui/uei/
    - xform/ong/ung/
    - xform/^yi?/i/
    - xform/^wu?/u/
    - xform/iu/v/
    - xform/^([jqx])u/$1v/
    - xform/([iuv])n/$1en/
    - xform/^zhi?/Z/
    - xform/^chi?/C/
    - xform/^shi?/S/
    - xform/^([zcsr])i/$1/
    - xform/ai/A/
    - xform/ei/I/
    - xform/ao/O/
    - xform/ou/U/
    - xform/ang/K/
    - xform/eng/G/
    - xform/an/M/
    - xform/en/N/
    - xform/er/R/
    - xform/eh/E/
    - xform/([iv])e/$1E/

# For tone-less dictionaries (luna_pinyin): derive "zhong1".."zhong5" from "zhong" so that
# typed tones are accepted but do not affect candidates. Must come after pinyin_to_zhuyin
# and before abbreviation.
luna_tones:
  __append:
    - derive/^(.+)$/${1}1/
    - derive/^(.+)$/${1}2/
    - derive/^(.+)$/${1}3/
    - derive/^(.+)$/${1}4/
    - derive/^(.+)$/${1}5/

free_order:
  __append:
    - derive/([bpmfdtnlgkhjqxZCSrzcs])([iuv])/$2$1/
    - derive/([iuv])([aoeEAIOUMNKGR])/$2$1/
    - derive/([bpmfdtnlgkhjqxZCSrzcs])([aoeEAIOUMNKGR])/$2$1/
    - derive/([bpmfdtnlgkhjqxZCSrzcs])([iuv])/$2$1/

abbreviation:
  __append:
    - abbrev/^([bpmfdtnlgkhjqxZCSrzcs]).+$/$1/
    - abbrev/^([A-Za-z]+)\d$/$1/
    - abbrev/^([bpmfdtnlgkhjqxZCSrzcs]).+(\d)$/$1$2/

keymap_bopomofo:
  __append:
    - 'xlit|bpmfdtnlgkhjqxZCSrzcsiuvaoeEAIOUMNKGR12345|1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347|'
```

- [ ] **Step 2: 建立 `futo_bopomofo.schema.yaml`**

```yaml
# Rime schema
# encoding: utf-8
#
# Zhuyin schema bundled with FUTO Keyboard. Adapted from rime-bopomofo, but backed by the
# luna_pinyin dictionary (no tone data) that ships in the FUTO Chinese dictionary file.

schema:
  schema_id: futo_bopomofo
  name: 注音
  version: "1.0"
  author:
    - 佛振 <chen.sst@gmail.com>
    - FUTO Keyboard
  description: |
    注音符號輸入，採用「大千式」鍵盤排列。
    使用 luna_pinyin 詞庫，聲調只作為音節分隔，不影響候選字。
  dependencies:
    - stroke

switches:
  - name: ascii_mode
    reset: 0
    states: [ 中文, 西文 ]
  - name: full_shape
    states: [ 半角, 全角 ]
  - name: simplification
    states: [ 漢字, 汉字 ]
  - name: ascii_punct
    states: [ 。，, ．， ]

engine:
  processors:
    - ascii_composer
    - recognizer
    - key_binder
    - speller
    - punctuator
    - selector
    - navigator
    - express_editor
  segmentors:
    - ascii_segmentor
    - matcher
    - abc_segmentor
    - punct_segmentor
    - fallback_segmentor
  translators:
    - punct_translator
    - table_translator@custom_phrase
    - reverse_lookup_translator
    - script_translator
  filters:
    - simplifier
    - uniquifier

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
  dictionary: luna_pinyin
  prism: futo_bopomofo
  preedit_format:
    - "xlit|1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'|ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙ |"

custom_phrase:
  dictionary: ""
  user_dict: custom_phrase
  db_class: stabledb
  enable_completion: false
  enable_sentence: false
  initial_quality: 1

reverse_lookup:
  dictionary: stroke
  enable_completion: true
  prefix: "`"
  suffix: "'"
  tips: 〔筆畫〕
  preedit_format:
    - xlit/hspnz/一丨丿丶乙/
  comment_format:
    - xform/([nl])v/$1ü/

punctuator:
  import_preset: symbols

key_binder:
  import_preset: default
  bindings:
    # The default preset binds these keys to paging; they are Zhuyin keys here.
    - { when: has_menu, accept: comma, send: comma }      # ㄝ
    - { when: has_menu, accept: period, send: period }    # ㄡ
    - { when: has_menu, accept: minus, send: minus }      # ㄦ

recognizer:
  patterns:
    uppercase: "^[A-Z][-_+.'0-9A-Za-z]*$"
    url: "^(www[.]|https?:|ftp:|mailto:).*$"
    reverse_lookup: "`[a-z]*'?$"

__patch:
  # 使用八股文語言模型
  - grammar:/hant?
  # 用家自選配置（FUTO Keyboard 會寫入 futo_bopomofo.custom.yaml）
  - futo_bopomofo.custom:/patch?
```

- [ ] **Step 3: 建立 `futo_bopomofo_terra.schema.yaml`**

```yaml
# Rime schema
# encoding: utf-8
#
# Zhuyin schema backed by the terra_pinyin dictionary (with tones). Selected automatically by
# FUTO Keyboard when terra_pinyin.dict.yaml is present in the Rime shared directory.

__include: futo_bopomofo.schema:/

schema:
  schema_id: futo_bopomofo_terra
  name: 注音（聲調）
  version: "1.0"
  author:
    - 佛振 <chen.sst@gmail.com>
    - FUTO Keyboard
  description: |
    注音符號輸入，採用「大千式」鍵盤排列。
    使用 terra_pinyin 詞庫，聲調參與候選字篩選。
  dependencies:
    - stroke

speller:
  algebra:
    __patch:
      - futo_zhuyin:/pinyin_to_zhuyin
      - futo_zhuyin:/free_order
      - futo_zhuyin:/abbreviation
      - futo_zhuyin:/keymap_bopomofo

translator:
  dictionary: terra_pinyin
  prism: futo_bopomofo_terra

__patch:
  - grammar:/hant?
  - futo_bopomofo_terra.custom:/patch?
```

- [ ] **Step 4: 建立 `default.custom.yaml`**

```yaml
# Written by FUTO Keyboard into the Rime user directory so that the bundled Zhuyin schemas
# are built on deploy. Do not edit; it is regenerated when missing.
patch:
  schema_list/+:
    - schema: futo_bopomofo
    - schema: futo_bopomofo_terra
```

- [ ] **Step 5: 檢查 YAML 語法**

Run: `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in sys.argv[1:]]; print('ok')" java/assets/rime/*.yaml`
Expected: `ok`

（若沒有 PyYAML：`python3 -m pip install --user pyyaml`，或改用 `ruby -ryaml -e 'ARGV.each{|f| YAML.load_file(f)}; puts "ok"' java/assets/rime/*.yaml`。）

- [ ] **Step 6: Commit**

```bash
git add java/assets/rime
git commit -m "Add bundled Rime Zhuyin schemas

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 4: `BundledRimeAssets` 並接上 `ChineseIME.onStartInput`

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/BundledRimeAssets.kt`
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt:532-538`（`onStartInput`）
- Test: `tests/src/org/futo/inputmethod/engine/general/BundledRimeAssetsTests.kt`（instrumented）

**Interfaces:**
- Consumes: Task 3 的 assets 檔名；`Context.getSetting(SettingsKey<T>)`、`Context.setSettingBlocking(Preferences.Key<T>, T)`（`org.futo.inputmethod.latin.uix`）；`ChineseIME.getShared(context)`、`ChineseIME.getUser(context)`。
- Produces:
  ```kotlin
  object BundledRimeAssets {
      const val VERSION: Int
      val SHARED_FILES: List<String>
      val USER_FILES: List<String>
      val VersionSetting: SettingsKey<Int>
      fun ensure(context: Context, shared: File, user: File): Boolean  // true => redeploy needed
  }
  ```

- [ ] **Step 1: 寫 instrumented 測試**

建立 `tests/src/org/futo/inputmethod/engine/general/BundledRimeAssetsTests.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BundledRimeAssetsTests {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var shared: File
    private lateinit var user: File

    @Before
    fun setUp() {
        root = File(context.cacheDir, "bundled_rime_test").apply { deleteRecursively(); mkdirs() }
        shared = File(root, "shared")
        user = File(root, "user")
        context.setSettingBlocking(BundledRimeAssets.VersionSetting.key, 0)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        context.setSettingBlocking(BundledRimeAssets.VersionSetting.key, 0)
    }

    @Test
    fun copiesAllFilesOnFirstRun() {
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        BundledRimeAssets.SHARED_FILES.forEach { assertTrue(it, File(shared, it).length() > 0) }
        BundledRimeAssets.USER_FILES.forEach { assertTrue(it, File(user, it).length() > 0) }
        assertTrue(File(shared, "futo_bopomofo.schema.yaml").readText().contains("schema_id: futo_bopomofo"))
    }

    @Test
    fun doesNothingWhenUpToDate() {
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        assertFalse(BundledRimeAssets.ensure(context, shared, user))
    }

    @Test
    fun recopiesWhenAFileIsMissing() {
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        assertTrue(File(user, "default.custom.yaml").delete())
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        assertTrue(File(user, "default.custom.yaml").exists())
    }

    @Test
    fun recopiesWhenVersionChanges() {
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        val schema = File(shared, "futo_bopomofo.schema.yaml")
        schema.writeText("stale")
        context.setSettingBlocking(BundledRimeAssets.VersionSetting.key, BundledRimeAssets.VERSION - 1)
        assertTrue(BundledRimeAssets.ensure(context, shared, user))
        assertEquals(false, schema.readText() == "stale")
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run（需連接裝置或模擬器）: `./gradlew connectedUnstableDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.futo.inputmethod.engine.general.BundledRimeAssetsTests`
Expected: 編譯失敗，`Unresolved reference: BundledRimeAssets`。

若沒有裝置，改以 `./gradlew compileUnstableDebugAndroidTestKotlin` 確認編譯失敗，並在 Task 6 真機驗證。

- [ ] **Step 3: 實作 `BundledRimeAssets`**

建立 `java/src/org/futo/inputmethod/engine/general/BundledRimeAssets.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.intPreferencesKey
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSettingBlocking
import java.io.File
import java.io.IOException

/**
 * Rime schema files shipped inside the APK under `assets/rime`. The Chinese dictionary the
 * user imports (zidian.dict) does not include a Zhuyin schema, so these are copied next to it
 * where Rime looks for schemas.
 */
object BundledRimeAssets {
    private const val TAG = "BundledRimeAssets"
    private const val ASSET_DIR = "rime"

    /** Bump whenever any file under assets/rime changes, so existing installs are refreshed. */
    const val VERSION = 1

    /** Copied into the Rime shared directory (recreated whenever the dictionary is re-extracted). */
    val SHARED_FILES = listOf(
        "futo_zhuyin.yaml",
        "futo_bopomofo.schema.yaml",
        "futo_bopomofo_terra.schema.yaml",
    )

    /** Copied into the Rime user directory (wiped when the user clears learned words). */
    val USER_FILES = listOf(
        "default.custom.yaml",
    )

    val VersionSetting = SettingsKey(intPreferencesKey("ChineseBundledRimeAssetsVersion"), 0)

    /**
     * Makes sure every bundled file exists and is current.
     * @return true if files were written and Rime needs to be redeployed.
     */
    fun ensure(context: Context, shared: File, user: File): Boolean {
        val upToDate = context.getSetting(VersionSetting) == VERSION
                && SHARED_FILES.all { File(shared, it).exists() }
                && USER_FILES.all { File(user, it).exists() }
        if (upToDate) return false

        return try {
            shared.mkdirs()
            user.mkdirs()
            SHARED_FILES.forEach { copyAsset(context, it, File(shared, it)) }
            USER_FILES.forEach { copyAsset(context, it, File(user, it)) }
            context.setSettingBlocking(VersionSetting.key, VERSION)
            true
        } catch (e: IOException) {
            // Leave the version setting untouched so the next call retries.
            Log.e(TAG, "Failed to copy bundled Rime assets", e)
            false
        }
    }

    private fun copyAsset(context: Context, name: String, destination: File) {
        context.assets.open("$ASSET_DIR/$name").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
```

- [ ] **Step 4: 接上 `ChineseIME.onStartInput`**

把 `ChineseIME.kt` 的

```kotlin
    override fun onStartInput() {
        if(resetSharedFromResources(helper.context, helper.lifecycleScope)) {
            prevConfiguration = null
            coroScope.launch { rime.deploy() }
        }
        updateConfig()
    }
```

改為

```kotlin
    override fun onStartInput() {
        // Order matters: extracting the dictionary wipes the shared directory, so the bundled
        // schemas are (re)copied afterwards.
        val dictionaryChanged = resetSharedFromResources(helper.context, helper.lifecycleScope)
        val bundledChanged = BundledRimeAssets.ensure(
            helper.context, getShared(helper.context), getUser(helper.context)
        )
        if(dictionaryChanged || bundledChanged) {
            prevConfiguration = null
            coroScope.launch { rime.deploy() }
        }
        updateConfig()
    }
```

- [ ] **Step 5: 執行測試確認通過**

Run: `./gradlew connectedUnstableDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.futo.inputmethod.engine.general.BundledRimeAssetsTests`
Expected: 4 tests passed。無裝置時：`./gradlew compileUnstableDebugKotlin compileUnstableDebugAndroidTestKotlin` 需 `BUILD SUCCESSFUL`。

- [ ] **Step 6: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/BundledRimeAssets.kt java/src/org/futo/inputmethod/engine/general/ChineseIME.kt tests/src/org/futo/inputmethod/engine/general/BundledRimeAssetsTests.kt
git commit -m "Copy bundled Rime schemas into the Rime directories on start

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 5: `ChineseIME` 支援 `"bopomofo"` hint

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`
  - `currentTransformation`（約 line 245）
  - `safeguardInputStringForSimulation`（約 line 401）
  - `updateConfig()` 的 `schema` `when`（約 line 617-641）
  - `onEvent()` 的 `EVENT_TYPE_INPUT_KEYPRESS` 分支（約 line 740-775）

**Interfaces:**
- Consumes: `ZhuyinKeymap`（Task 1）、方案 id（Task 3）、`rime.preeditFlow.value: String`、`rime.processX11Code(code: Int)`、`rime.commitComposition()`、`rime.selectCandidate(index: Int): Boolean`、`InputKeeper`（`rawInput`）。

沒有可自動執行的測試（依賴 Rime 原生庫與 InputConnection）；以編譯與 Task 6 真機驗證。

- [ ] **Step 1: 加入 hint 判斷與預編輯轉換**

在 `ChineseIME` 類內，把

```kotlin
    private val currentTransformation get() = when(layoutHint) {
        "stroke" -> StrokeTransformation
        else -> emptyMap()
    }
```

改為

```kotlin
    private val isBopomofo get() = layoutHint == "bopomofo"

    private val currentTransformation get() = when(layoutHint) {
        "stroke" -> StrokeTransformation
        "bopomofo" -> ZhuyinKeymap.toZhuyin
        else -> emptyMap()
    }
```

- [ ] **Step 2: 放行注音鍵位字元給模擬輸入**

把

```kotlin
    private fun safeguardInputStringForSimulation(input: String): String {
        var string = input
        string = string.filter { it in 'a'..'z' || it in 'A'..'Z' }
        return string
    }
```

改為

```kotlin
    private fun safeguardInputStringForSimulation(input: String): String = when {
        isBopomofo -> input.filter { it in ZhuyinKeymap.alphabet }
        else -> input.filter { it in 'a'..'z' || it in 'A'..'Z' }
    }
```

- [ ] **Step 3: 方案選擇**

在 `updateConfig()` 的 `val schema = when (layoutHint) { ... }` 中，`"stroke" -> { "stroke" }` 之後、`null -> { return@launch }` 之前加入：

```kotlin
                    "bopomofo" -> {
                        val hasTerraPinyin = File(getShared(helper.context), "terra_pinyin.dict.yaml").exists()
                        if(hasTerraPinyin) "futo_bopomofo_terra" else "futo_bopomofo"
                    }
```

- [ ] **Step 4: 注音按鍵處理**

在 `onEvent()` 之前新增方法：

```kotlin
    /**
     * Key handling specific to the Zhuyin layout. Zhuyin symbols are translated to the ASCII
     * keys of the standard keyboard; ASCII digits (from the number row) bypass Rime because the
     * schema would interpret them as Zhuyin keys; space is tone 1 while composing.
     *
     * @return true if the key was fully handled.
     */
    private fun handleBopomofoKey(codePoint: Int): Boolean {
        ZhuyinKeymap.rimeKeyFor(codePoint)?.let { key ->
            rawInput.append(key.code)
            coroScope.launch { rime.processX11Code(key.code) }
            return true
        }

        val composing = rime.preeditFlow.value.isNotEmpty()

        if(codePoint in '0'.code..'9'.code) {
            rawInput.clear()
            coroScope.launch {
                if(composing) rime.commitComposition()
                connect?.commitText(String(Character.toChars(codePoint)), 1)
            }
            return true
        }

        if(codePoint == ' '.code && composing) {
            if(ZhuyinKeymap.isToneKey(rawInput.text.lastOrNull())) {
                // The syllable already has a tone: space picks the first candidate, like the
                // pinyin layout does.
                rawInput.clear()
                coroScope.launch {
                    if(!rime.selectCandidate(0)) rime.commitComposition()
                }
            } else {
                rawInput.append(' '.code)
                coroScope.launch { rime.processX11Code(' '.code) }
            }
            return true
        }

        return false
    }
```

然後在 `onEvent()` 的 `EVENT_TYPE_INPUT_KEYPRESS` 分支，`if (triggerIfIsAction(event.mKeyCode)) return` 之後、`val x11Code = ...` 之前加入：

```kotlin
                if(isBopomofo && !editingState.active && event.mKeyCode == Event.NOT_A_KEY_CODE) {
                    if(handleBopomofoKey(event.mCodePoint)) return
                }
```

- [ ] **Step 5: 編譯並跑既有 JVM 測試**

Run: `./gradlew compileUnstableDebugKotlin testUnstableDebugUnitTest`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 6: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseIME.kt
git commit -m "Support bopomofo layout hint in ChineseIME

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 6: 真機驗證

**Files:** 無新檔；依驗證結果修正前面 task 的檔案。

前置：已連接 Android 裝置（`adb devices` 有列出），裝置上已匯入 `zidian.dict`（設定 → Languages & Models → Chinese Dictionary；檔案可從 https://dl.keyboard.futo.org/zidian-v26.02.dict 下載）。

- [ ] **Step 1: 組 APK 並安裝**

Run: `./gradlew assembleUnstableDebug && adb install -r build/outputs/apk/unstable/debug/*.apk`
Expected: `BUILD SUCCESSFUL`、`Success`。（APK 路徑若不同，用 `find build/outputs/apk -name '*.apk'` 查。）

- [ ] **Step 2: 執行 instrumented 測試**

Run: `./gradlew connectedUnstableDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.futo.inputmethod.engine.general.BundledRimeAssetsTests`
Expected: 4 tests passed。

- [ ] **Step 3: 啟用布局並確認部署成功**

1. 設定 → Languages：加入「中文（繁體）」（`zh_Hant`），布局選 `Chinese Zhuyin (注音)`。
2. 打開任一文字欄位叫出鍵盤。
3. Run: `adb logcat -d | grep -E "ChineseIME|rime|Rime" | tail -50`
   Expected: 有 `Deploy: Success`，`Schemas:` 清單含 `futo_bopomofo`；沒有 `Failed to select schema`。若看到 `futo_bopomofo_terra` 的部署錯誤（找不到 `terra_pinyin`），那是預期行為。

若 `Schemas:` 不含 `futo_bopomofo`：用 `adb shell run-as` 不可行時，改用 `adb shell ls /sdcard/Android/data/org.futo.inputmethod.latin*/files/rime/shared` 與 `.../rime/user` 確認檔案存在；確認 `user/default.custom.yaml` 內容正確；查 logcat 中 rime 的 YAML 錯誤訊息並修正 Task 3 的檔案（修正後把 `BundledRimeAssets.VERSION` +1）。

- [ ] **Step 4: 手動輸入驗證**

依序確認，任何一項不符就回到對應 task 修正：

| # | 操作 | 預期 |
|---|---|---|
| 1 | 按 ㄋ ㄧ ˇ ㄏ ㄠ ˇ | 預編輯顯示 `ㄋㄧˇㄏㄠˇ`，候選含「你好」；點選後上屏 |
| 2 | 按 ㄓ ㄨ ㄥ、空白、ㄨ ㄣ ˊ | 預編輯顯示 `ㄓㄨㄥˉㄨㄣˊ`，候選含「中文」 |
| 3 | 輸入 ㄋㄧˇ 後再按空白 | 第一個候選（你）上屏，預編輯清空 |
| 4 | 顯示數字行（設定開啟）後按 `5` | 直接輸出 `5`，不進入組字；組字中按 `5` 則先上屏組字再輸出 `5` |
| 5 | 中文輸入設定 → 簡繁 → 「简化字」，再輸入 ㄋㄧˇㄏㄠˇ | 候選為簡體「你好」 |
| 6 | 點預編輯浮窗進入編輯 | 編輯框顯示注音符號；刪掉一個符號後候選跟著更新 |
| 7 | 按 ㄝ、ㄡ、ㄦ（對應 `,` `.` `-`） | 進入組字而非翻頁或輸出標點 |
| 8 | 底列的 `，` `。` | 組字中：先上屏組字再輸出標點；非組字：直接輸出標點 |
| 9 | 切回 `Chinese QWERTY` 布局輸入 `nihao` | 拼音行為與改動前相同 |

若第 2 項的空白沒有顯示為 `ˉ` 而是把候選上屏，代表 speller 沒吃到空白；檢查 `futo_bopomofo.schema.yaml` 的 `speller/use_space: true` 與 `alphabet` 含空白。

若第 4 項數字在組字上屏之前輸出（順序錯），把 `handleBopomofoKey` 中數字分支改為先 `rime.commitComposition()`、再以 `withContext(Dispatchers.Main) {}` 讓出一輪後 `commitText`。

- [ ] **Step 5: terra_pinyin 自動切換（選擇性）**

1. 從 https://github.com/rime/rime-terra-pinyin 下載 `terra_pinyin.dict.yaml`，`adb push` 到 `/sdcard/Android/data/<packageName>/files/rime/shared/`。
2. 重新開啟鍵盤，`adb logcat -d | grep ChineseIME | tail`。
   Expected: `configuration=Configuration(schema=futo_bopomofo_terra, ...)`；輸入 ㄕˋ 與 ㄕˊ 候選不同。

- [ ] **Step 6: Commit 修正（若有）**

```bash
git add -A java/src java/assets/rime tests
git commit -m "Fix Zhuyin input issues found in device testing

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

## 完成後

- 子模組 `java/assets/layouts` 的 `zhuyin-layout` 分支需要推到 futo-keyboard-layouts 的 fork 並開 PR；主倉庫的子模組指標在 PR 合併前只在本機有效。
- 沿用 superpowers:finishing-a-development-branch 決定合併／PR。
