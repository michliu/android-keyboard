# 注音輸入法（Zhuyin / Bopomofo）設計

日期：2026-08-30

## 目標

為 FUTO Keyboard 的中文輸入（`ChineseIME`，基於 librime）新增注音輸入：
一個標準大千式排列的注音鍵盤布局，以及讓 Rime 能用注音組字的方案檔。
不需要使用者下載新的詞庫，沿用現有 `zidian.dict` 中的 `luna_pinyin` 詞庫；
若使用者的 Rime shared 目錄中存在 `terra_pinyin.dict.yaml`（帶聲調詞庫），
則自動改用帶聲調的方案。

## 背景與限制

- 中文輸入由 `java/src/org/futo/inputmethod/engine/general/ChineseIME.kt` 透過 Rime 實作。
  Rime 方案由布局的 `imeHint` 決定：`"qwerty"` → 拼音方案、`"stroke"` → `stroke`。
- 布局位於子模組 `java/assets/layouts`（獨立倉庫 futo-keyboard-layouts，Apache-2.0）。
  `mapping.yaml` 把 `zh`、`zh_Hant` 對應到 `zh_qwerty`、`zh_stroke`。
- Rime 方案與詞庫來自使用者匯入的 `zidian.dict`（zip），解壓到 `<externalFiles>/rime/shared`；
  使用者資料與 `*.custom.yaml` 在 `<externalFiles>/rime/user`。
  `zidian-v26.02` 內只有 `luna_pinyin`（無聲調）、雙拼、`stroke` 與 OpenCC；
  沒有 `bopomofo`、`terra_pinyin`。其 `default.yaml` 的 `schema_list` 列了不存在的方案
  （`bopomofo`、`cangjie5`、`terra_pinyin`），Rime 部署時只記錄錯誤、不影響其他方案。
- Rime 的 speller 只接受 ASCII 鍵；注音必須以大千式鍵位（`1qaz2wsx…`）送入 Rime。
- 在 Rime 注音方案中 `1`＝ㄅ、`6 3 4 7`＝聲調，因此鍵盤數字行的數字不能原樣送進 Rime。

## 方案總覽

1. 布局按鍵直接輸出注音 Unicode 字元（ㄅ…ㄦ、ˊ ˇ ˋ ˙）。
2. `ChineseIME` 在送入 Rime 前把注音字元映射為大千式 ASCII 鍵；
   來自數字行的 ASCII 數字不進 Rime，直接提交。
3. App 內建注音方案檔（`java/assets/rime/`），在詞庫解壓後與 IME 啟動時寫入 Rime shared 目錄，
   並用 `default.custom.yaml` 將方案加入 `schema_list`。
4. 執行時依 `shared/terra_pinyin.dict.yaml` 是否存在，選擇 `futo_bopomofo_terra` 或 `futo_bopomofo`。

## 元件

### 1. 布局 `java/assets/layouts/Chinese/zh_bopomofo.yaml`

- `name: Chinese Zhuyin (注音)`、`languages: zh`、`imeHint: "bopomofo"`、`autoShift: false`，
  `attributes: {moreKeyMode: OnlyExplicit}`。
- 無 shift 鍵；`numberRowMode` 維持預設（使用者可設定）。
- 四列字母列（標準大千式，與 Gboard 注音相同），退格在第四列末：

```
ㄅ ㄉ ˇ ˋ ㄓ ˊ ˙ ㄚ ㄞ ㄢ ㄦ
ㄆ ㄊ ㄍ ㄐ ㄔ ㄗ ㄧ ㄛ ㄟ ㄣ
ㄇ ㄋ ㄎ ㄑ ㄕ ㄘ ㄨ ㄜ ㄠ ㄤ
ㄈ ㄌ ㄏ ㄒ ㄖ ㄙ ㄩ ㄝ ㄡ ㄥ $delete
```

- 底列與 `zh_qwerty` 相同：`$symbols`、`，`、`$action`、`$space`、`。`（長按含 `…… ？ ！ ； ： ——`）、`$enter`。
- 聲調鍵使用 Unicode：ˊ U+02CA、ˇ U+02C7、ˋ U+02CB、˙ U+02D9。一聲以空白鍵輸入。
- `mapping.yaml`：在 `zh` 與 `zh_Hant` 下追加 `zh_bopomofo`（排在既有布局之後）。
- `LayoutSpec.md`：`imeHint` 對中文的合法值補上 `"bopomofo"`，並連結本布局。
- 以上變更在子模組內獨立提交，主倉庫更新子模組指標。

### 2. 內建 Rime 資源 `java/assets/rime/`

檔案：

- `futo_zhuyin.yaml`：拼寫運算規則，抄自 rime-bopomofo 的 `zhuyin.yaml`
  （`pinyin_to_zhuyin`、`free_order`、`abbreviation`、`keymap_bopomofo`），另加：
  ```yaml
  luna_tones:
    __append:
      - derive/$/1/
      - derive/$/2/
      - derive/$/3/
      - derive/$/4/
      - derive/$/5/
  ```
  讓無聲調音節可接受任意聲調後綴（聲調只作為音節分隔，不影響候選）。
  `luna_tones` 必須排在 `abbreviation` 之前、`pinyin_to_zhuyin` 之後。
- `futo_bopomofo.schema.yaml`：
  - `schema_id: futo_bopomofo`、`name: 注音`、`dependencies: [stroke]`。
  - `switches`：`ascii_mode`、`full_shape`、`simplification`、`ascii_punct`
    （與 `luna_pinyin` 相同，讓既有 `writeCustomizationFile` 的 `futo_zh_simp` 開關與 `simplifier` 正常運作）。
  - `engine` 與 `luna_pinyin.schema.yaml` 相同（`express_editor`、`script_translator`、`simplifier`、`uniquifier`）。
  - `speller`：`alphabet: '1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'`、
    `initials: '1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-'`、`finals: " 6347"`、
    `delimiter: "'"`、`use_space: true`、
    `algebra: {__patch: [futo_zhuyin:/pinyin_to_zhuyin, futo_zhuyin:/luna_tones, futo_zhuyin:/free_order, futo_zhuyin:/abbreviation, futo_zhuyin:/keymap_bopomofo]}`。
  - `translator`：`dictionary: luna_pinyin`、`prism: futo_bopomofo`、
    `preedit_format` 用 xlit 把 `1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'` 顯示為
    `ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙ `。
  - `key_binder`：`import_preset: default`，並加 `has_menu` 時 `comma`/`period`/`minus` 原樣送出
    （避免預設翻頁綁定吃掉 ㄝ ㄡ ㄦ）。
  - `recognizer`、`reverse_lookup`、`custom_phrase`、`punctuator` 與 `luna_pinyin` 相同。
  - 結尾 `__patch: [grammar:/hant?, futo_bopomofo.custom:/patch?]`。
- `futo_bopomofo_terra.schema.yaml`：`__include: futo_bopomofo.schema:/`，覆寫
  `schema/schema_id: futo_bopomofo_terra`、`schema/name: 注音（聲調）`、
  `translator/dictionary: terra_pinyin`、`translator/prism: futo_bopomofo_terra`、
  `speller/algebra`（同上但不含 `luna_tones`），結尾 patch 改為 `futo_bopomofo_terra.custom:/patch?`。
- `default.custom.yaml`：
  ```yaml
  patch:
    schema_list/+:
      - schema: futo_bopomofo
      - schema: futo_bopomofo_terra
  ```
  此檔寫入 Rime **user** 目錄（Rime 只在 user/shared 目錄尋找 `default.custom.yaml`，
  且 user 目錄清除後由 `BundledRimeAssets` 重寫）。`terra_pinyin` 詞庫不存在時
  `futo_bopomofo_terra` 部署失敗只會記錄錯誤，與 zidian 既有的不存在方案行為一致。

### 3. `BundledRimeAssets`（新檔 `engine/general/BundledRimeAssets.kt`）

- 常數 `VERSION: Int`（每次修改 assets/rime 內容時遞增）、`SHARED_FILES`（三個方案／規則檔）、
  `USER_FILES`（`default.custom.yaml`）。
- `ensure(context, shared: File, user: File): Boolean`：若 pref `ChineseBundledRimeAssetsVersion != VERSION`
  或任一檔案不存在於對應目錄，就從 assets 複製全部檔案（覆寫）、更新 pref，回傳 `true`
  表示需要重新部署；否則回傳 `false`。
- 呼叫點：`ChineseIME.onStartInput` 先呼叫 `resetSharedFromResources`（可能清空重建 shared），
  再呼叫 `ensure`，任一為真即 `rime.deploy()`。

### 4. `ZhuyinKeymap`（新檔 `engine/general/ZhuyinKeymap.kt`）

- `toRimeKey: Map<Char, Char>`：41 個注音字元 → 大千式 ASCII 鍵
  （ㄅㄆㄇㄈ→`1qaz`、ㄉㄊㄋㄌ→`2wsx`、ㄍㄎㄏ→`edc`、ㄐㄑㄒ→`rfv`、ㄓㄔㄕㄖ→`5tgb`、
  ㄗㄘㄙ→`yhn`、ㄧㄨㄩ→`ujm`、ㄚㄛㄜㄝ→`8ik,`、ㄞㄟㄠㄡ→`9ol.`、ㄢㄣㄤㄥ→`0p;/`、ㄦ→`-`、
  ˊˇˋ˙→`6347`、ˉ→` `）。
- `toZhuyin: Map<Char, Char>`：反向映射（供預編輯浮窗顯示與 `FloatingPreEdit.untransform`）。
- `alphabet: Set<Char>`：Rime alphabet 全部字元（含空白），供 `safeguardInputStringForSimulation` 使用。

### 5. `ChineseIME.kt` 變更

- `updateConfig()` 的 `layoutHint` 分支新增 `"bopomofo"`：
  `if (File(getShared(ctx), "terra_pinyin.dict.yaml").exists()) "futo_bopomofo_terra" else "futo_bopomofo"`。
  簡繁仍由既有 `SimplificationSetting`／locale 決定；`writeCustomizationFile` 對此方案照常寫入
  `enable_correction`、`enable_user_dict`、`futo_zh_simp` 開關；模糊音只套用於 `luna_pinyin*`（不變）。
- `currentTransformation`：`"bopomofo" -> ZhuyinKeymap.toZhuyin`。
- `safeguardInputStringForSimulation(input)`：依 `layoutHint`
  過濾——`"bopomofo"` 時保留 `ZhuyinKeymap.alphabet` 中的字元，其他 hint 維持只留 ASCII 字母。
- `onEvent` 的 `EVENT_TYPE_INPUT_KEYPRESS` 在 `"bopomofo"` hint 下：
  - 注音字元（`ZhuyinKeymap.toRimeKey` 含有）→ 轉為 ASCII 鍵後送 `rime.processX11Code`，並 `rawInput.append(ASCII 鍵)`。
  - ASCII 數字 `0-9` → 不送 Rime；若 Rime 有預編輯則先 `rime.commitComposition()`，再 `commitText(數字)`（即 `handlePassByMessage` 的既有路徑）。
  - 空白：Rime 預編輯非空且 `rawInput` 最後一鍵不是聲調時，作為一聲送 Rime 並 `rawInput.append(' ')`；
    最後一鍵已是聲調（` 6347`）時，選取第一個候選（`rime.selectCandidate(0)`，失敗則 `commitComposition`），
    與拼音布局的空白行為一致；預編輯為空時維持既有行為（送 Rime、清 `rawInput`）。
  - 其他鍵（退格、Enter、標點）維持既有行為。
- 非 bopomofo hint 的行為完全不變。
- `debugInfo` 附加 `schema` 已含於 `configuration`，不需額外變更。

## 資料流

```
按鍵 ㄋ ──► ChineseIME.onEvent ──(ZhuyinKeymap: ㄋ→'s')──► rime.processX11Code('s')
                                                             │
Rime preedit "s" ──preedit_format xlit──► "ㄋ" ──► helper.setPreedit(FloatingPreEdit(…, toZhuyin, rawInput="s"))
Rime candidates ──► SuggestedWords ──► 建議列；點選 → rime.selectCandidate → RimeMessage.Commit → commitText
```

## 錯誤處理

- 內建資源複製失敗（IO 例外）：記錄 log，不阻斷 IME；下次 `onStartInput` 會重試（pref 未更新）。
- `selectSchema("futo_bopomofo*")` 失敗：沿用既有 3 秒重試與 log。使用者若尚未匯入 zidian，
  shared 內無 `luna_pinyin`，與現有拼音一樣無法組字（既有行為）。
- `terra_pinyin.dict.yaml` 存在但損壞導致 `futo_bopomofo_terra` 部署失敗：`selectSchema` 失敗後記錄 log；
  不自動回退（使用者可移除該檔）。

## 測試

- 單元測試（`tests/`）：
  - `ZhuyinKeymapTest`：`toRimeKey` 與 `toZhuyin` 互為反函數、共 41 個注音字元 + ˉ、
    值域恰等於 Rime alphabet `'1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'` 的字元集合。
  - 布局載入測試（比照 `KeyboardLayoutSetV2Tests`）：`zh_bopomofo` 可載入、`imeHint == "bopomofo"`、
    四列字母列鍵數為 11/10/10/11、`mapping.yaml` 的 `zh` 與 `zh_Hant` 含 `zh_bopomofo`。
- 手動驗證（真機，已匯入 zidian）：
  1. 語言 `zh_Hant` 選 `zh_bopomofo`，輸入 ㄋㄧˇㄏㄠˇ → 候選出現「你好」。
  2. ㄓㄨㄥ（空白）→ 一聲顯示 ˉ，繼續輸入 ㄨㄣˊ → 「中文」。
  3. 顯示數字行時按 `5` → 直接輸出「5」，不進入組字。
  4. 簡繁設定切到「簡化字」→ 候選為簡體。
  5. 點預編輯浮窗編輯，顯示為注音符號，修改後候選更新。
  6. 將 `terra_pinyin.dict.yaml` 放入 shared 目錄後重新開啟 IME → 聲調影響候選排序。

## 不做的事

- 不新增設定項（鍵盤變體、聲調模式）。
- 不做台灣字形（OpenCC t2tw）轉換。
- 不提供許氏、倚天等其他注音鍵位。
- 不修改 zidian 詞庫或其下載流程。
