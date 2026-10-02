# 注音聯想詞 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 注音布局上屏中文後，建議列顯示聯想詞（上屏「你」→ 們、好、可以…），點選後上屏並繼續聯想。

**Architecture:** 新增純 Kotlin 的 `ChineseAssociation`，從詞庫附帶的 `rime/shared/essay.txt`（`詞<TAB>權重`）建立「前 1／2 個字 → 權重前 8 的後續部分」索引。`ChineseIME` 在注音布局下於背景載入索引；收到 Rime 的 `RimeMessage.Commit` 後查詢並顯示在建議列，點選聯想詞時直接 `commitText` 並以「上一輪文字 + 該詞」繼續查詢；任何按鍵、游標移動、輸入結束都會結束聯想。中文設定新增開關（預設開）。

**Tech Stack:** Kotlin / Android、Jetpack Compose 設定頁、librime（`icu.astronot233.rime`）、JUnit4 JVM 單元測試。

Spec：`docs/superpowers/specs/2026-10-02-zhuyin-association-design.md`

## Global Constraints

- 工作分支：`master`（使用者的 fork 為主，不向上游開 PR）。
- 只在注音布局（`layoutHint == "bopomofo"`）生效；拼音、筆畫布局行為不得改變，也不得載入索引。
- 資料來源固定 `ChineseIME.getShared(context)/essay.txt`；不存在或讀取失敗時聯想停用，不得崩潰。
- 每鍵最多 8 個結果（`MAX_RESULTS = 8`）；兩字鍵只收權重 ≥ 500 的詞（`MIN_TWO_CHAR_WEIGHT = 500`）。
- 簡體（已套用設定 `simplification == true`）時不顯示聯想。
- 設定鍵 `ChineseIME_association`（Boolean，預設 `true`）。
- `ChineseAssociation.kt` 不得 import `android.*`，否則 JVM 單元測試跑不起來。
- 提交訊息結尾需附：
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  ```
- Gradle 任務：JVM 單元測試 `./gradlew testUnstableDebugUnitTest`；組 APK `./gradlew assembleUnstableDebug`。
- 真機（小米，無線偵錯）：每次 `adb shell input tap` 前先截圖確認畫面（曾誤入「恢復原廠設定」頁）；
  `am force-stop` 後要再 `adb shell ime set org.futo.inputmethod.latin.unstable/org.futo.inputmethod.latin.LatinIME`。

## File Structure

| 檔案 | 責任 |
| --- | --- |
| `java/src/org/futo/inputmethod/engine/general/ChineseAssociation.kt`（新） | 解析 essay.txt、建索引、查詢；純 Kotlin |
| `tests/unit/org/futo/inputmethod/engine/general/ChineseAssociationTest.kt`（新） | 上者的單元測試 |
| `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt` | 設定鍵與開關 UI；載入索引；上屏後顯示、點選、結束聯想 |
| `java/res/values/strings-uix.xml` | 開關的標題與說明字串 |

---

### Task 1: `ChineseAssociation` 索引與查詢

**Files:**
- Create: `java/src/org/futo/inputmethod/engine/general/ChineseAssociation.kt`
- Test: `tests/unit/org/futo/inputmethod/engine/general/ChineseAssociationTest.kt`

**Interfaces:**
- Produces（Task 2 使用）：
  - `class ChineseAssociation`，`fun lookup(text: String): List<String>`，`val size: Int`（鍵數）
  - `ChineseAssociation.load(sharedDir: java.io.File): ChineseAssociation?`（無檔回 `null`；讀取錯誤拋 `IOException`）
  - `ChineseAssociation.parse(reader: java.io.Reader): ChineseAssociation`
  - 常數 `FILE_NAME = "essay.txt"`、`MAX_RESULTS = 8`、`MIN_TWO_CHAR_WEIGHT = 500`

- [ ] **Step 1: 寫失敗的測試**

建立 `tests/unit/org/futo/inputmethod/engine/general/ChineseAssociationTest.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.nio.file.Files

class ChineseAssociationTest {
    private fun parse(vararg lines: String) =
        ChineseAssociation.parse(StringReader(lines.joinToString("\n")))

    @Test
    fun keepsTheEightHighestWeightsInOrder() {
        val association = parse(
            "你一\t1", "你二\t2", "你三\t3", "你四\t4", "你五\t5",
            "你六\t6", "你七\t7", "你八\t8", "你九\t9", "你十\t10",
        )
        assertEquals(listOf("十", "九", "八", "七", "六", "五", "四", "三"), association.lookup("你"))
    }

    @Test
    fun equalWeightsKeepFileOrder() {
        val association = parse("你甲\t5", "你乙\t5", "你丙\t9")
        assertEquals(listOf("丙", "甲", "乙"), association.lookup("你"))
    }

    @Test
    fun duplicateContinuationKeepsFirstOccurrence() {
        val association = parse("你們\t5", "你們\t9", "你好\t7")
        assertEquals(listOf("好", "們"), association.lookup("你"))
    }

    @Test
    fun trailingTwoCharactersWinOverTheLastOne() {
        val association = parse("你們這些\t600", "們的\t100")
        assertEquals(listOf("這些"), association.lookup("你們"))
        assertEquals(listOf("的"), association.lookup("他們"))
        assertEquals(listOf("的"), association.lookup("們"))
    }

    @Test
    fun usesOnlyTheEndOfLongerText() {
        val association = parse("你們這些\t600", "們的\t100")
        assertEquals(listOf("這些"), association.lookup("我說你們"))
    }

    @Test
    fun twoCharacterKeysNeedTheMinimumWeight() {
        val association = parse("你們這些\t499", "們的\t100")
        // Below the threshold: no 你們 key, so lookup falls back to 們…
        assertEquals(listOf("的"), association.lookup("你們"))
        // …but the word still counts for its one-character key.
        assertEquals(listOf("們這些"), association.lookup("你"))
    }

    @Test
    fun skipsNonHanSingleCharacterAndMalformedLines() {
        val association = parse(
            "", "AB\t100", "你a\t100", "你\t100", "你好", "你好\tabc", "\t5", "你們\t50",
        )
        assertEquals(listOf("們"), association.lookup("你"))
        assertEquals(1, association.size)
    }

    @Test
    fun unknownTextHasNoAssociations() {
        val association = parse("你們\t50")
        assertEquals(emptyList<String>(), association.lookup(""))
        assertEquals(emptyList<String>(), association.lookup("，"))
        assertEquals(emptyList<String>(), association.lookup("abc"))
    }

    @Test
    fun handlesSupplementaryCharacters() {
        val association = parse("𠮷野家\t600")
        assertEquals(listOf("野家"), association.lookup("𠮷"))
        assertEquals(listOf("家"), association.lookup("𠮷野"))
    }

    @Test
    fun loadReturnsNullWithoutTheFile() {
        val dir = Files.createTempDirectory("assoc").toFile()
        try {
            assertNull(ChineseAssociation.load(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun loadReadsTheFile() {
        val dir = Files.createTempDirectory("assoc").toFile()
        try {
            File(dir, ChineseAssociation.FILE_NAME).writeText("你們\t96181\n你的\t159646\n")
            assertEquals(listOf("的", "們"), ChineseAssociation.load(dir)!!.lookup("你"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ChineseAssociationTest'`
Expected: 編譯失敗，`Unresolved reference: ChineseAssociation`。

- [ ] **Step 3: 實作**

建立 `java/src/org/futo/inputmethod/engine/general/ChineseAssociation.kt`：

```kotlin
package org.futo.inputmethod.engine.general

import java.io.File
import java.io.Reader

/**
 * Associated phrases (聯想詞) for the Zhuyin layout: the words that most often follow some
 * committed text, taken from Rime's preset vocabulary `essay.txt` (one `word<TAB>weight` per
 * line), which ships with the luna_pinyin dictionary.
 *
 * Keys are the first one or two characters of a vocabulary word and values the rest of it,
 * highest weight first. Two-character keys only index words of weight [MIN_TWO_CHAR_WEIGHT] or
 * more: without the threshold they alone are ~100k keys, too much for the keyboard process.
 * Lengths are counted in code points so supplementary-plane Han characters work.
 */
class ChineseAssociation private constructor(private val index: Map<String, Array<String>>) {
    /** Number of keys in the index. */
    val size: Int get() = index.size

    /** What follows the trailing two characters of [text], else what follows its last one. */
    fun lookup(text: String): List<String> {
        val count = text.codePointCount(0, text.length)
        for (n in minOf(count, 2) downTo 1) {
            index[text.substring(text.offsetByCodePoints(text.length, -n))]?.let { return it.toList() }
        }
        return emptyList()
    }

    /** The [MAX_RESULTS] highest-weight values of one key; ties and duplicates keep the first seen. */
    private class TopK {
        private val weights = IntArray(MAX_RESULTS)
        private val words = arrayOfNulls<String>(MAX_RESULTS)
        private var count = 0

        fun offer(word: String, weight: Int) {
            if (count == MAX_RESULTS && weight <= weights[count - 1]) return
            for (i in 0 until count) if (words[i] == word) return

            var i = if (count < MAX_RESULTS) count++ else MAX_RESULTS - 1
            while (i > 0 && weights[i - 1] < weight) {
                weights[i] = weights[i - 1]
                words[i] = words[i - 1]
                i--
            }
            weights[i] = weight
            words[i] = word
        }

        fun toArray(): Array<String> = Array(count) { words[it]!! }
    }

    companion object {
        const val FILE_NAME = "essay.txt"
        const val MAX_RESULTS = 8
        const val MIN_TWO_CHAR_WEIGHT = 500

        /** The index for `essay.txt` in Rime's shared directory, or null if the file is missing. */
        fun load(sharedDir: File): ChineseAssociation? {
            val file = File(sharedDir, FILE_NAME)
            if (!file.isFile) return null
            return file.bufferedReader().use { parse(it) }
        }

        fun parse(reader: Reader): ChineseAssociation {
            val top = HashMap<String, TopK>()
            reader.buffered().forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val word = line.substring(0, tab)
                val weight = line.substring(tab + 1).trim().toIntOrNull() ?: return@forEachLine
                if (!isHan(word)) return@forEachLine
                val length = word.codePointCount(0, word.length)
                if (length < 2) return@forEachLine

                val one = word.offsetByCodePoints(0, 1)
                top.getOrPut(word.substring(0, one)) { TopK() }.offer(word.substring(one), weight)
                if (length >= 3 && weight >= MIN_TWO_CHAR_WEIGHT) {
                    val two = word.offsetByCodePoints(0, 2)
                    top.getOrPut(word.substring(0, two)) { TopK() }.offer(word.substring(two), weight)
                }
            }
            return ChineseAssociation(top.mapValues { it.value.toArray() })
        }

        private fun isHan(text: String): Boolean {
            if (text.isEmpty()) return false
            var i = 0
            while (i < text.length) {
                val codePoint = text.codePointAt(i)
                if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return false
                i += Character.charCount(codePoint)
            }
            return true
        }
    }
}
```

- [ ] **Step 4: 執行測試確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.ChineseAssociationTest'`
Expected: `BUILD SUCCESSFUL`，11 個測試全過。

- [ ] **Step 5: 用真實詞表做一次健全性檢查（選做，不提交）**

若 scratchpad 有從手機拉下的 `essay.txt`（`adb pull /sdcard/Android/data/org.futo.inputmethod.latin.unstable/files/rime/shared/essay.txt`），
可暫時在測試加一個讀它的案例，預期 `lookup("你")` 前三為 `的, 們, 可以`、`lookup("你們")` 前二為 `這些, 自己`、`size` 約 33,000；確認後刪掉該案例。

- [ ] **Step 6: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseAssociation.kt tests/unit/org/futo/inputmethod/engine/general/ChineseAssociationTest.kt
git commit -m "Add the associated-phrase index for Zhuyin

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: `ChineseIME` 顯示與點選聯想詞、設定開關

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`
- Modify: `java/res/values/strings-uix.xml`（`chinese_setting_fuzzy_pinyin` 那一行之後，約第 670 行）

**Interfaces:**
- Consumes: Task 1 的 `ChineseAssociation.load(File): ChineseAssociation?`、`lookup(String): List<String>`、`size`。
  既有：`showSuggestionStrip(SuggestedWords?)`、`setNeutralSuggestionStrip()`、`prevConfiguration?.simplification`、
  `isBopomofo`、`isOnEnglishPage`、`rawInput`、`editingState`、`connect`。
- Produces: `ChineseIMESettings.AssociationSetting: SettingsKey<Boolean>`。

本 Task 沒有 JVM 單元測試可寫（`ChineseIME` 依賴 Android 與 librime）；驗證靠編譯與 Task 3 的真機測試。

- [ ] **Step 1: 字串資源**

在 `java/res/values/strings-uix.xml` 的 `<string name="chinese_setting_fuzzy_pinyin">Fuzzy Pinyin</string>` 之後加：

```xml
    <string name="chinese_setting_association">Associated phrases</string>
    <string name="chinese_setting_association_subtitle">Zhuyin: after a word is entered, suggest words that often follow it</string>
```

- [ ] **Step 2: import**

在 `ChineseIME.kt` 的 import 區：
- `import androidx.datastore.preferences.core.stringPreferencesKey` 之前加 `import androidx.datastore.preferences.core.booleanPreferencesKey`
- `import org.futo.inputmethod.latin.uix.settings.SettingToggleRaw` 之前加 `import org.futo.inputmethod.latin.uix.settings.SettingToggleDataStore`
- `import java.io.File` 之後加 `import java.io.IOException`

- [ ] **Step 3: 設定鍵與開關**

在 `ChineseIMESettings` 的 `FuzzyPinyinSetting` 定義之後加：

```kotlin
    val AssociationSetting = SettingsKey(
        booleanPreferencesKey("ChineseIME_association"),
        true
    )
```

在 `settings = listOf(` 裡模糊拼音那個 `UserSetting(R.string.chinese_setting_fuzzy_pinyin) { ... },` 之後加：

```kotlin
            UserSetting(R.string.chinese_setting_association, R.string.chinese_setting_association_subtitle) {
                SettingToggleDataStore(
                    stringResource(R.string.chinese_setting_association),
                    AssociationSetting,
                    subtitle = stringResource(R.string.chinese_setting_association_subtitle)
                )
            },
```

- [ ] **Step 4: 狀態欄位**

在 `ChineseIME` 類別中 `private val rimeLoading = mutableStateOf(false)` 之後加：

```kotlin
    /** Associated phrases (聯想詞); null until loaded, or when the dictionary has no essay.txt. */
    private var association: ChineseAssociation? = null
    private var associationJob: Job? = null
    /** Text the shown associations continue; null when no associations are shown. */
    private var associationContext: String? = null
    private var associationWords: List<String> = emptyList()
```

- [ ] **Step 5: 載入、顯示、結束聯想的函式**

在 `subscribeToRimeCandidates()` 整個定義（結尾 `}.launchIn(coroScope)`）之後、`override fun onCreate()` 之前加：

```kotlin
    /**
     * Loads the associated-phrase index in the background, once per dictionary. Only the Zhuyin
     * layout uses it, so pinyin and stroke users do not pay for the index.
     */
    private fun ensureAssociationLoaded(reload: Boolean = false) {
        if(reload) {
            associationJob?.cancel()
            associationJob = null
            association = null
        }
        if(!isBopomofo || associationJob != null) return
        associationJob = coroScope.launch {
            val start = SystemClock.elapsedRealtime()
            association = withContext(Dispatchers.IO) {
                try {
                    ChineseAssociation.load(getShared(helper.context))
                } catch(e: IOException) {
                    Log.e(TAG, "Failed to load associated phrases", e)
                    null
                }
            }
            Log.i(TAG, "Associated phrases: ${association?.size ?: 0} keys in ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

    /**
     * Shows the phrases that follow [context] in the suggestion strip, or ends the association if
     * there are none. [committed] is the text that was just committed (the end of [context]).
     */
    private fun showAssociations(context: String, committed: String) {
        val words = association?.lookup(context).orEmpty()
        val show = words.isNotEmpty()
                && isBopomofo && !isOnEnglishPage
                && !editingState.active && rawInput.text.isEmpty()
                && prevConfiguration?.simplification != true
                && helper.context.getSetting(ChineseIMESettings.AssociationSetting)
                // A key typed right after the commit (e.g. a digit, which bypasses Rime) has
                // already ended the association; do not bring it back for text that is no longer
                // in front of the cursor.
                && connect?.getTextBeforeCursor(committed.length, 0)?.toString() == committed
        if(!show) {
            endAssociation()
            return
        }

        associationContext = context
        associationWords = words
        val suggestWordList = words.mapIndexed { index, word -> SuggestedWordInfo(
            word,
            "",
            Int.MAX_VALUE - index,
            1,
            null,
            SuggestedWordInfo.NOT_AN_INDEX,
            SuggestedWordInfo.NOT_A_CONFIDENCE,
            index,
            null
        ) }.let { ArrayList(it) }
        showSuggestionStrip(
            SuggestedWords(
                suggestWordList,
                suggestWordList,
                null,
                false,
                false,
                false,
                0,
                0,
                0
            )
        )
    }

    private fun endAssociation() {
        if(associationContext == null) return
        associationContext = null
        associationWords = emptyList()
        setNeutralSuggestionStrip()
    }
```

- [ ] **Step 6: 上屏後觸發**

`subscribeToRimeMessage()` 中把

```kotlin
        is RimeMessage.Commit -> {
            connect?.commitText(msg.value, 1)
        }
```

改成

```kotlin
        is RimeMessage.Commit -> {
            connect?.commitText(msg.value, 1)
            showAssociations(msg.value, msg.value)
        }
```

- [ ] **Step 7: Rime 的空候選不覆蓋聯想**

`subscribeToRimeCandidates()` 中，在 `waitingToSelect?.let { ... }` 區塊之後、`val suggestWordList = cdd.mapIndexed` 之前加：

```kotlin
        // Rime clears its candidates after a commit; keep the associations shown for that commit.
        if(cdd.isEmpty() && associationContext != null) return@onEach
```

- [ ] **Step 8: `onEvent` 結束聯想與點選聯想詞**

`onEvent` 中：

1. `Event.EVENT_TYPE_INPUT_KEYPRESS_RESUMED -> {` 之後的第一行（`if (triggerIfIsAction(event.mKeyCode))` 之前）加 `endAssociation()`。
2. `Event.EVENT_TYPE_SUGGESTION_PICKED` 分支整個換成：

```kotlin
            Event.EVENT_TYPE_SUGGESTION_PICKED -> {
                val suggestion = event.mSuggestedWordInfo ?: return
                val context = associationContext
                if (suggestion.isKindOf(SuggestedWordInfo.KIND_UNDO)) {
                    endAssociation()
                    connect?.commitText(suggestion.word, 1)
                } else if (context != null &&
                    associationWords.getOrNull(suggestion.mCandidateIndex) == suggestion.word) {
                    connect?.commitText(suggestion.word, 1)
                    showAssociations(context + suggestion.word, suggestion.word)
                } else {
                    coroScope.launch {
                        if(!rime.selectCandidate(suggestion.mCandidateIndex)) {
                            Log.e(TAG, "Couldn't select candidate")
                        }
                    }
                }
            }
```

3. `Event.EVENT_TYPE_SOFTWARE_GENERATED_STRING -> {` 與 `Event.EVENT_TYPE_DOWN_UP_KEYEVENT -> {` 兩個分支的第一行各加 `endAssociation()`。

- [ ] **Step 9: 游標移動、輸入開始／結束、布局更新**

- `override fun onMovePointer(...)` 的第一行加 `endAssociation()`。
- `override fun onMoveDeletePointer(steps: Int)` 的第一行（`setNeutralSuggestionStrip()` 之前）加 `endAssociation()`。
- `override fun onFinishInput()` 的第一行（`if(editingState.active) return` 之前）加 `endAssociation()`。
- `override fun onStartInput()` 改成（新增首行 `endAssociation()` 與最後的 `ensureAssociationLoaded`）：

```kotlin
    override fun onStartInput() {
        endAssociation()
        // Order matters: extracting the dictionary wipes the shared directory, so the bundled
        // schemas are (re)copied afterwards.
        val dictionaryChanged = resetSharedFromResources(helper.context, helper.lifecycleScope)
        val bundledChanged = BundledRimeAssets.ensure(
            helper.context, getShared(helper.context), getUser(helper.context)
        )
        if(dictionaryChanged || bundledChanged) {
            invalidateConfiguration()
            coroScope.launch { rime.deploy() }
        }
        updateConfig()
        ensureAssociationLoaded(reload = dictionaryChanged)
    }
```

- `override fun onLayoutUpdated(...)` 改成：

```kotlin
    override fun onLayoutUpdated(layout: KeyboardLayoutSetV2) {
        layoutHint = layout.mainLayout.imeHint
        ensureAssociationLoaded()
        if(helper.isImeActive(this)) updateConfig()
    }
```

- `debugInfo` 字串結尾 `\nrawInput=${rawInput.text}` 之後接上 `\nassociation=${association?.size}`。

- [ ] **Step 10: 編譯與既有測試**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.engine.general.*' && ./gradlew assembleUnstableDebug`
Expected: 兩者 `BUILD SUCCESSFUL`。

- [ ] **Step 11: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseIME.kt java/res/values/strings-uix.xml
git commit -m "Show associated phrases after Zhuyin commits

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: 真機驗證

**Files:** 無新檔；驗證中發現的問題各自另開 commit 修正並回到本 Task 重測。

- [ ] **Step 1: 安裝並切回輸入法**

```bash
adb install -r build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk
adb shell ime set org.futo.inputmethod.latin.unstable/org.futo.inputmethod.latin.LatinIME
```

- [ ] **Step 2: 載入紀錄與記憶體**

開任一文字輸入框（例如 Chrome 網址列）並切到注音布局，然後：

```bash
adb logcat -d | grep "Associated phrases"
adb shell dumpsys meminfo org.futo.inputmethod.latin.unstable | grep -E "TOTAL PSS|Java Heap"
```

Expected: `Associated phrases: 3xxxx keys in N ms`（鍵數約 33,000；N 記下來，預期數百 ms 到 2 s）。
另將 Step 1 剛裝好、尚未切到注音時的 meminfo 與此比較，Java Heap 增量應 < 15MB。

- [ ] **Step 3: 功能檢查（每次 tap 前先 `adb exec-out screencap -p > shot.png` 看畫面）**

1. 打 ㄋㄧˇ 選「你」→ 建議列出現 的、們、可以、要…
2. 點「們」→ 文字變「你們」，建議列換成 這些、自己、可以…
3. 再點一個聯想詞 → 繼續出現下一輪，或沒有結果時建議列清空。
4. 聯想顯示中按注音鍵 → 聯想消失、正常組字。
5. 聯想顯示中按退格 → 聯想消失、刪一個字。
6. 聯想顯示中按空白 → 輸入空白、聯想消失。
7. 上屏後切到英文頁打字 → 不出現聯想。
8. 組字中按數字（符號頁）→ 中文上屏後數字跟在後面，建議列不留聯想。
9. 中文設定（`am start -n org.futo.inputmethod.latin.unstable/org.futo.inputmethod.latin.uix.settings.SettingsActivity --es navDest ime/zh`，若無效則從設定頁手動進入）關閉「Associated phrases」→ 回到輸入框打字不出現聯想；再開回來。
10. 切到拼音布局（若有安裝）打字 → 行為與之前相同、無聯想。

- [ ] **Step 4: 記錄結果**

在 `.superpowers/sdd/progress.md` 追加一行 Feature 6 的驗證結果（PASS／發現的問題與修正 commit）。

```bash
git add .superpowers/sdd/progress.md
git commit -m "Record device verification of Zhuyin associated phrases

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```
