# 注音鍵盤中英切換 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在注音布局內加入「英」/「中」鍵切換到內嵌英文 QWERTY 頁，英文直接上屏。

**Architecture:** 布局用黏性子鍵盤 `subKeyboards.Alphabet1`（`code: -20` 切入、`-19` 切回）；`ChineseIME` 在 bopomofo hint 下把所有 ASCII 可見字元直接上屏（不送 Rime）。

**Tech Stack:** Kotlin / Android、YAML 布局（子模組 `java/assets/layouts`，分支 `zhuyin-layout`）、JUnit4 JVM 測試（`tests/unit`）。

Spec：`docs/superpowers/specs/2026-08-30-zhuyin-english-toggle-design.md`

## Global Constraints

- 主倉庫分支 `zhuyin-english`；子模組 `java/assets/layouts` 在既有分支 `zhuyin-layout` 上追加提交（已推到 fork `michliu/futo-keyboard-layouts`，本任務不推送）。
- 切換鍵：`code: -20`（進英文頁）、`code: -19`（回注音頁）；標籤 `英` / `中`；`style: Functional`。
- `ChineseIME` 只改 `handleBopomofoKey` 的數字分支條件與 KDoc；非 bopomofo hint 行為不變。
- 不新增設定項；英文頁無聯想、無自動大寫。
- 提交訊息結尾附：
  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ
  ```
- 測試指令：`./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.v2keyboard.ZhuyinLayoutTest'`；編譯 `./gradlew compileUnstableDebugKotlin`；APK `./gradlew assembleUnstableDebug`。

---

### Task 1: 布局加入英文頁（子模組）+ 測試

**Files:**
- Modify: `java/assets/layouts/Chinese/zh_bopomofo.yaml`
- Modify: `java/assets/layouts/LayoutSpec.md`（`imeHint` 段落）
- Test: `tests/unit/org/futo/inputmethod/v2keyboard/ZhuyinLayoutTest.kt`

**Interfaces:**
- Consumes: `parseKeyboardYamlString`、`Keyboard.subKeyboards: Map<KeyboardLayoutKind, SubKeyboard>`（`org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind`）、`SubKeyboard.rows`、`Row.isLetterRow/isBottomRow/keys`、`org.futo.inputmethod.v2keyboard.BaseKey.code: Int?`。

- [ ] **Step 1: 寫失敗的測試**

在 `ZhuyinLayoutTest` 加入 import `org.futo.inputmethod.keyboard.internal.KeyboardLayoutKind` 與 `org.junit.Assert.assertNotNull`，並新增：

```kotlin
    @Test
    fun hasEnglishSubKeyboardWithToggleKeys() {
        val keyboard = parseKeyboardYamlString(layoutFile.readText())

        val mainBottom = keyboard.getEffectiveRows(0).first { it.isBottomRow }
        assertTrue(mainBottom.keys.filterIsInstance<BaseKey>().any { it.code == -20 && it.spec.startsWith("英") })

        val english = keyboard.subKeyboards[KeyboardLayoutKind.Alphabet1]
        assertNotNull(english)
        val letterRows = english!!.rows.filter { it.isLetterRow }
        assertEquals(listOf(10, 9, 9), letterRows.map { it.keys.size })
        val englishBottom = english.rows.first { it.isBottomRow }
        assertTrue(englishBottom.keys.filterIsInstance<BaseKey>().any { it.code == -19 && it.spec.startsWith("中") })
    }
```

- [ ] **Step 2: 執行測試確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.v2keyboard.ZhuyinLayoutTest'`
Expected: `hasEnglishSubKeyboardWithToggleKeys` FAIL（`mainBottom` 無 code -20 的鍵 / `english` 為 null）。

- [ ] **Step 3: 修改布局**

把 `java/assets/layouts/Chinese/zh_bopomofo.yaml` 的 `bottom:` 段改為，並在檔尾加入 `subKeyboards`：

```yaml
  - bottom:
    - $symbols
    - type: base
      spec: "英|_"
      code: -20
      attributes: { style: Functional }
    - ['，']
    - $action
    - $space
    - type: base
      spec: '。'
      moreKeys: ['…|……', '？', '！', '；', '：', '—|——']
      attributes: { moreKeyMode: OnlyExplicit }
    - $enter
subKeyboards:
  # English page, entered with the 英 key (code -20); 中 (code -19) returns to Zhuyin.
  # Keys here output ASCII, which the IME commits directly without going through Rime.
  Alphabet1:
    rows:
      - letters: q w e r t y u i o p
      - letters: a s d f g h j k l
      - letters: [$shift, z, x, c, v, b, n, m, $delete]
      - bottom:
        - $symbols
        - type: base
          spec: "中|_"
          code: -19
          attributes: { style: Functional }
        - $action
        - $space
        - type: base
          spec: '.'
          moreKeys: [',', '?', '!', "'"]
          attributes: { moreKeyMode: OnlyExplicit }
        - $enter
```

若 YAML 解析對 `spec: "英|_"` 有疑慮：`|_` 表示無輸出（與日文 `"ABC|_"` 相同）。

- [ ] **Step 4: 更新 `LayoutSpec.md`**

在 `Valid values for Chinese are "stroke", "qwerty" or "bopomofo" ...` 那段之後加一句：

```
A "bopomofo" layout may provide an English page via `subKeyboards.Alphabet1` (switch with a key using `code: -20`, return with `code: -19`); ASCII characters typed there are committed directly by the IME.
```

- [ ] **Step 5: 執行測試確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.v2keyboard.ZhuyinLayoutTest'`
Expected: 4 tests passed。若 `letterRows` 鍵數斷言失敗，以實際輸出修正期望值並在報告說明（`$shift`/`$delete` 各算 1 鍵）。

- [ ] **Step 6: Commit（子模組 + 主倉庫）**

```bash
git -C java/assets/layouts add Chinese/zh_bopomofo.yaml LayoutSpec.md
git -C java/assets/layouts commit -m "Add English page to the Zhuyin layout

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
git add java/assets/layouts tests/unit/org/futo/inputmethod/v2keyboard/ZhuyinLayoutTest.kt
git commit -m "Add English page toggle to the Zhuyin layout

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 2: `ChineseIME` 直通所有 ASCII 可見字元

**Files:**
- Modify: `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt`（`handleBopomofoKey` 與其 KDoc）

- [ ] **Step 1: 修改條件與 KDoc**

把
```kotlin
        if(codePoint in '0'.code..'9'.code) {
```
改為
```kotlin
        if(codePoint in 0x21..0x7E) {
            // Printable ASCII never comes from the Zhuyin keys (they emit Zhuyin symbols); it is
            // typed on the English page or the symbols page and must not reach Rime, which
            // would read it as Zhuyin keys.
```
並把 KDoc 第二句 `ASCII digits (from the number row) bypass Rime because the schema would interpret them as Zhuyin keys;` 改為 `printable ASCII (English page, symbols page, number row) bypasses Rime because the schema would interpret it as Zhuyin keys;`。

- [ ] **Step 2: 編譯與單測**

Run: `./gradlew compileUnstableDebugKotlin testUnstableDebugUnitTest`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: Commit**

```bash
git add java/src/org/futo/inputmethod/engine/general/ChineseIME.kt
git commit -m "Commit printable ASCII directly under the Zhuyin layout

Letters and punctuation from the English or symbols page never come from
Zhuyin keys; sending them to Rime made e.g. ',' compose ㄝ.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 3: 真機驗證

前置：裝置已連接並解鎖，已匯入 zidian.dict。**每次 `input tap` 前先截圖確認畫面**。

- [ ] **Step 1**：`./gradlew assembleUnstableDebug && adb install -r build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk`
- [ ] **Step 2**：用 `am start -n org.futo.inputmethod.latin.unstable/org.futo.inputmethod.latin.uix.settings.SettingsActivity` 開啟設定，點「Try typing here…」喚起注音鍵盤。

| # | 操作 | 預期 |
|---|---|---|
| 1 | 底列出現「英」鍵，按下 | 切到 QWERTY 英文頁，底列有「中」 |
| 2 | 輸入 `Hello,` | 直接上屏 `Hello,`，無候選列 |
| 3 | 按 shift 後輸入 `a` | 上屏 `A` |
| 4 | 按「中」→ ㄋㄧˇ | 回注音頁且候選含「你」 |
| 5 | 組字 ㄋㄧˇ 中按「英」再打 `a` | 先上屏「你」再 `a` |
| 6 | 注音頁 `?123` 按 `,` | 得到 `,`（不再組成 ㄝ） |
| 7 | 切到 Chinese QWERTY 輸入 `nihao` | 拼音行為不變 |

- [ ] **Step 3: 若有修正則提交**（訊息附 trailer）。
