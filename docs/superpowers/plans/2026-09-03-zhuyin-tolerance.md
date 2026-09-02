# 注音容錯與組字修正 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 注音輸入的相鄰鍵容錯（Rime fuzz 規則 + 設定開關）與組字中方向鍵游標修正。

**Architecture:** `ZhuyinKeymap` 依大千物理網格生成相鄰鍵對與 `fuzz|x|y|` 規則（純函式）；`ChineseIME.writeCustomizationFile` 在 `futo_bopomofo*` 方案且開關開啟時以 `speller/algebra/+` 追加規則；`onEvent` 在組字中攔截 ←/→ 送 Rime navigator，並以 `caretAdjusted` 旗標處理 `rawInput` 失同步。

**Tech Stack:** Kotlin、Rime spelling algebra、JUnit4 JVM 測試（`tests/unit`）。

Spec：`docs/superpowers/specs/2026-09-03-zhuyin-tolerance-design.md`

## Global Constraints

- 分支 `zhuyin-tolerance`（自 master 分出）。不推送。
- 網格固定：`listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./")`；鄰居 = 同行 `(i, i+1)` + 下一行 `(i-1)` 與 `(i)`（越界跳過）。空白不參與。
- fuzz 規則用 `|` 作分隔符（`/` 是 ㄥ 的鍵）；pattern 端對正則特殊字元加反斜線跳脫（本網格中只有 `.` 需要）。
- 設定 key `ChineseIME_zhuyin_adjacent_tolerance`（Boolean，默認 true）；字串 `chinese_setting_zhuyin_tolerance` = `Zhuyin adjacent-key tolerance`、`chinese_setting_zhuyin_tolerance_subtitle` = `Accept presses on neighbouring keys, ranked below exact matches`。
- 方向鍵 keysym 用 `icu.astronot233.rime.X11Keys.XK_Left` / `XK_Right`。
- 非 bopomofo hint 行為不變；拼音的模糊音、簡繁、學習設定不受影響。
- 提交訊息結尾附：
  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ
  ```
- 指令：`./gradlew testUnstableDebugUnitTest --tests '<class>'`；`./gradlew compileUnstableDebugKotlin`；APK `./gradlew assembleUnstableDebug`。

## File Structure

| 檔案 | 責任 |
|---|---|
| `java/src/org/futo/inputmethod/engine/general/ZhuyinKeymap.kt` | `adjacentKeyPairs()`、`toleranceRules()` |
| `tests/unit/org/futo/inputmethod/engine/general/ZhuyinKeymapTest.kt` | 鄰居/規則單測 |
| `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt` | 設定 key、Configuration 欄位、custom.yaml 追加、方向鍵與 caretAdjusted |
| `java/res/values/strings-uix.xml` | 兩條字串 |

---

### Task 1: 鄰居表與 fuzz 規則（TDD）

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ZhuyinKeymap.kt`（object 末尾）
- Test: `tests/unit/org/futo/inputmethod/engine/general/ZhuyinKeymapTest.kt`（追加）

**Interfaces:**
- Produces:
  ```kotlin
  fun adjacentKeyPairs(): List<Pair<Char, Char>>   // 無序去重、確定順序（掃描序）
  fun toleranceRules(): List<String>               // 每對兩條 "fuzz|x|y|"、"fuzz|y|x|"，pattern 端已跳脫
  ```

- [ ] **Step 1: 追加失敗測試**（`ZhuyinKeymapTest` 末尾）

```kotlin
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
```

- [ ] **Step 2: 執行確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinKeymapTest'`
Expected: 編譯失敗 `Unresolved reference: adjacentKeyPairs`。

- [ ] **Step 3: 實作**（`ZhuyinKeymap` object 末尾追加）

```kotlin
    /** Physical key grid of the standard (大千式) keyboard, matching zh_bopomofo.yaml's rows. */
    private val KEY_GRID = listOf("1234567890-", "qwertyuiop", "asdfghjkl;", "zxcvbnm,./")

    /**
     * Unordered, de-duplicated pairs of physically adjacent keys: same-row neighbours (i, i+1)
     * plus the next row's keys at index i-1 and i (rows are half-key staggered).
     */
    fun adjacentKeyPairs(): List<Pair<Char, Char>> {
        val pairs = LinkedHashSet<Set<Char>>()
        KEY_GRID.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { i, key ->
                if (i + 1 < row.length) pairs.add(setOf(key, row[i + 1]))
                KEY_GRID.getOrNull(rowIndex + 1)?.let { below ->
                    for (j in intArrayOf(i - 1, i)) {
                        below.getOrNull(j)?.let { pairs.add(setOf(key, it)) }
                    }
                }
            }
        }
        return pairs.map { it.first() to it.last() }
    }

    private val REGEX_SPECIALS = ".\\^$[](){}*+?|".toSet()

    private fun escapeForPattern(c: Char): String =
        if (c in REGEX_SPECIALS) "\\$c" else c.toString()

    /**
     * Rime spelling-algebra fuzz rules accepting a press on a neighbouring key, ranked below
     * exact spellings. `|` is used as the delimiter because `/` is itself a Zhuyin key.
     */
    fun toleranceRules(): List<String> = adjacentKeyPairs().flatMap { (a, b) ->
        listOf(
            "fuzz|${escapeForPattern(a)}|$b|",
            "fuzz|${escapeForPattern(b)}|$a|",
        )
    }
```

- [ ] **Step 4: 執行確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ZhuyinKeymapTest'`
Expected: 9 tests passed（原 6 + 新 3）。若 spot-check 失敗，先核對網格/錯位規則再改實作，不得改弱斷言。

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ZhuyinKeymap.kt tests/unit/org/futo/inputmethod/engine/general/ZhuyinKeymapTest.kt
git commit -m "Add adjacent-key pairs and fuzz rules to ZhuyinKeymap

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 2: 設定開關與 custom.yaml 追加

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`
- Modify: `java/res/values/strings-uix.xml`（`chinese_setting_fuzzy_pinyin` 之後）

**Interfaces:**
- Consumes: Task 1 `ZhuyinKeymap.toleranceRules(): List<String>`；既有 `SettingsKey`、`userSettingToggleDataStore`（`org.futo.inputmethod.latin.uix.settings`）。
- Produces: `ChineseIMESettings.ZhuyinAdjacentToleranceSetting: SettingsKey<Boolean>`；`Configuration.zhuyinTolerance: Boolean`。

- [ ] **Step 1: 字串**

```xml
    <string name="chinese_setting_zhuyin_tolerance">Zhuyin adjacent-key tolerance</string>
    <string name="chinese_setting_zhuyin_tolerance_subtitle">Accept presses on neighbouring keys, ranked below exact matches</string>
```

- [ ] **Step 2: 設定 key 與選單項**（`ChineseIMESettings`）

在 `FuzzyPinyinSetting` 之後加：
```kotlin
    val ZhuyinAdjacentToleranceSetting = SettingsKey(
        booleanPreferencesKey("ChineseIME_zhuyin_adjacent_tolerance"),
        true
    )
```
（import `androidx.datastore.preferences.core.booleanPreferencesKey`。）
`menu` 的 `settings` 列表末尾加：
```kotlin
            userSettingToggleDataStore(
                title = R.string.chinese_setting_zhuyin_tolerance,
                subtitle = R.string.chinese_setting_zhuyin_tolerance_subtitle,
                setting = ZhuyinAdjacentToleranceSetting
            ),
```
（import `org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore`。）

- [ ] **Step 3: Configuration 與寫入**

- `Configuration` 增加欄位：`val zhuyinTolerance: Boolean`（放在 `fuzzyMode` 之前，位置任意但建構處要對應）。
- `updateConfig()` 中 `val config = Configuration(...)` 前加：
```kotlin
                val zhuyinTolerance = helper.context.getSetting(ChineseIMESettings.ZhuyinAdjacentToleranceSetting)
```
並把 `config` 建構改為含 `zhuyinTolerance`。
- `writeCustomizationFile` 的 fuzzy-pinyin 區塊之後加：
```kotlin
            // Adjacent-key tolerance only applies to the Zhuyin schemas
            if(schema.startsWith("futo_bopomofo") && cfg.zhuyinTolerance) {
                appendLine("    speller/algebra/+:")
                ZhuyinKeymap.toleranceRules().forEach {
                    appendLine("        - $it")
                }
            }
```

- [ ] **Step 4: 編譯與全部單測**

Run: `./gradlew compileUnstableDebugKotlin testUnstableDebugUnitTest`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseIME.kt java/res/values/strings-uix.xml
git commit -m "Add Zhuyin adjacent-key tolerance setting

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 3: 組字中方向鍵游標修正

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`

**Interfaces:**
- Consumes: `X11Keys.XK_Left`/`XK_Right`（import 加入既有 X11Keys import 群）；既有 `rawInput`、`rime.preeditFlow`、`handleBopomofoKey`、`subscribeToRimePreedit`、`interruptInput`、`onFinishInput`。

- [ ] **Step 1: `caretAdjusted` 旗標**

在 `private val rawInput = InputKeeper()` 附近加：
```kotlin
    /**
     * True after the user moved the caret inside the Rime composition (Left arrow). rawInput no
     * longer mirrors the key sequence then: composing-state checks fall back to the Rime preedit,
     * rawInput stops being updated, and the pre-edit popup is made non-editable until the
     * composition ends.
     */
    private var caretAdjusted = false
```

- [ ] **Step 2: 方向鍵攔截**（`EVENT_TYPE_DOWN_UP_KEYEVENT` 分支開頭，`interruptInput()` 之前）

```kotlin
            Event.EVENT_TYPE_DOWN_UP_KEYEVENT -> {
                if(isBopomofo && !editingState.active &&
                    (rawInput.text.isNotEmpty() || rime.preeditFlow.value.isNotEmpty())) {
                    when (event.mKeyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            // Move the caret inside the composition instead of committing it.
                            caretAdjusted = true
                            rawInput.clear()
                            coroScope.launch { rime.processX11Code(XK_Left) }
                            return
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            coroScope.launch { rime.processX11Code(XK_Right) }
                            return
                        }
                    }
                }
                interruptInput()
                ...（其餘原樣）
```

- [ ] **Step 3: `handleBopomofoKey` 的 caret 模式**

- 開頭加 `val composingByPreedit = rime.preeditFlow.value.isNotEmpty()`？——**不要**在函式頂層同步讀（會破壞既有 FIFO 註解）；改為逐分支處理：
- 注音鍵分支的聲調守衛改為：
```kotlin
                if(ZhuyinKeymap.isToneKey(key) && !caretAdjusted &&
                    (rawInput.text.isEmpty() || ZhuyinKeymap.isToneKey(rawInput.text.lastOrNull()))) {
                    return@launch
                }
                if(ZhuyinKeymap.isToneKey(key) && caretAdjusted && rime.preeditFlow.value.isEmpty()) {
                    return@launch
                }
                if(!caretAdjusted) rawInput.append(key.code)
                rime.processX11Code(key.code)
```
- 數字分支的 `composing` 改為：
```kotlin
                val composing = rawInput.text.isNotEmpty() || rime.preeditFlow.value.isNotEmpty()
```
（原本已是此式，確認未變。）
- 空白分支的 `when` 改為：
```kotlin
                when {
                    rawInput.text.isEmpty() && rime.preeditFlow.value.isEmpty() -> {
                        rawInput.clear()
                        rime.processX11Code(' '.code)
                    }
                    !caretAdjusted && ZhuyinKeymap.isToneKey(rawInput.text.lastOrNull()) -> {
                        rawInput.clear()
                        if(!rime.selectCandidate(0)) rime.commitComposition()
                    }
                    else -> {
                        if(!caretAdjusted) rawInput.append(' '.code)
                        rime.processX11Code(' '.code)
                    }
                }
```

- [ ] **Step 4: 復位與浮窗**

- `subscribeToRimePreedit` 中 `if(ped.isEmpty() && notEditing) { rawInput.clear() }` 改為同時 `caretAdjusted = false`。
- `interruptInput` 與 `onFinishInput` 的 coroutine 內（`rawInput.clear()` 處）同樣加 `caretAdjusted = false`。
- `subscribeToRimePreedit` 的 `helper.setPreedit(FloatingPreEdit.build(ped, this, currentTransformation, rawInput.text))` 改為：
```kotlin
        helper.setPreedit(FloatingPreEdit.build(ped, this, currentTransformation,
            if(caretAdjusted) "￿" else rawInput.text))
```
（哨兵字元使浮窗的可編輯門檻不通過。）

- [ ] **Step 5: 編譯與全部單測**

Run: `./gradlew compileUnstableDebugKotlin testUnstableDebugUnitTest`
Expected: BUILD SUCCESSFUL。（此路徑無自動化測試——原生 Rime。）

- [ ] **Step 6: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseIME.kt
git commit -m "Move the caret inside the Zhuyin composition with the arrow keys

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 4: 真機驗證

前置：裝置連接且解鎖；**每次 `input tap` 前先截圖確認畫面**；已裝 zidian 詞庫與注音布局；方向鍵行已開啟。

- [ ] **Step 1**：`./gradlew assembleUnstableDebug && adb install -r build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk`
- [ ] **Step 2**：喚起注音鍵盤，`adb logcat` 觀察部署。

| # | 操作 | 預期 |
|---|---|---|
| 1 | 切開關後首次喚起：logcat 兩次 `Deploy` 間隔 | ≤ 15 秒 |
| 2 | ㄋㄩˇ（想按 ㄧ 誤按 ㄩ） | 候選中出現「你」（可靠後）；正常 ㄋㄧˇ 首選仍「你」 |
| 3 | 設定關閉開關後重試 ㄋㄩˇ | 「你」不再出現 |
| 4 | ㄓㄨㄥㄨㄣ 組字中 ← ←、退格、按 ㄩ | 預編輯就地變 ㄓㄨㄥㄩㄣ，候選更新 |
| 5 | 接著 → 回尾端、繼續 ˊ | 正常完成；空白/選字正常 |
| 6 | 非組字時按 ← → | 宿主游標移動（原行為） |
| 7 | 拼音布局輸入 `nihao` | 行為不變 |

- [ ] **Step 3**：若超過 15 秒或候選明顯變差 → 將 `adjacentKeyPairs` 縮為只含同行相鄰（改網格迴圈，移除下一行部分，並同步 spot-check 測試），重測。
- [ ] **Step 4**：有修正則提交（附 trailer）。
