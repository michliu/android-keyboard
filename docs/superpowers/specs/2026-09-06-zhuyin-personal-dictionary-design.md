# 注音個人字典設計

日期：2026-09-06

## 目標

讓設定頁的「個人字典」對中文（注音布局）生效：使用者在個人字典新增「詞 + 注音」後，
在注音布局打出該注音時，這個詞出現在候選列首位；刪除或修改後立即反映。
只支援注音布局（`futo_bopomofo`、`futo_bopomofo_terra`）；拼音布局不在本次範圍。

## 背景與限制

- 個人字典的儲存是 Android `UserDictionary` provider（`UserDictionaryIO`），
  每筆為 `PersonalWord(word, frequency, locale, appId, shortcut)`。
  日文引擎用 `shortcut` 欄存讀音與詞性，本設計沿用此做法：`shortcut` 存注音串。
- 設定頁 `PersonalDictionary.kt` 以 `ExceptionalPersonalDictionaryLanguages["zh"].supported = false`
  把中文從語言列表隱藏；`ChineseIME` 不讀 `UserDictionaryIO`，也不處理 `IMEMessage.ReloadPersonalDict`。
- `futo_bopomofo.schema.yaml` 已有 `table_translator@custom_phrase`（讀 `custom_phrase.txt`），
  但它按原始按鍵精確比對；注音每個音節的聲調鍵有六種可能（含省略），
  無法用它可靠地匹配使用者的輸入，本設計不採用。
- Rime 是預編譯的 AAR（`libs/rime-release.aar`），只有 `RimeApi` 公開的操作
  （deploy、selectSchema、按鍵、候選等），沒有反查讀音的介面，所以讀音必須由使用者提供。
- 使用者 Rime 目錄（`<externalFiles>/rime/user`）會在「清除學習詞庫」時整個刪除；
  放在其中的檔案都必須能重新產生。
- Rime 部署時，若某方案的 `translator/dictionary` 找不到對應的 `*.dict.yaml`，
  該方案編譯失敗但不影響其他方案；引用它的 translator 只記錄錯誤。

## 方案總覽

1. 個人字典的中文詞由 `ChineseIME` 寫成一本小型 Rime 詞典 `rime/user/futo_custom.dict.yaml`
   （欄位：詞、無聲調 luna 拼音、權重）。
2. 新增內建方案 `futo_custom_bopomofo.schema.yaml`，作為 `futo_bopomofo` 的依賴方案，
   用與注音方案相同的拼寫規則（`futo_zhuyin.yaml` 的 luna 規則）替這本詞典編譯 prism，
   因此聲調、免聲調縮寫、大千式鍵位映射全部沿用，使用者怎麼打詞庫詞就怎麼打個人詞。
3. `futo_bopomofo` 加一個 `script_translator@custom_dict` 讀這本詞典，候選排在詞庫候選之前。
   （不能用 `table_translator`：它把整段輸入當成單一編碼查表，這是 custom_phrase 與倉頡類方案的模型，
   不會按音節圖比對多音節的個人詞；真機驗證時個人詞完全不出現。）
4. 設定頁開放中文，提供注音專用的新增/編輯對話框，讀音由對話框內建的注音符號按鍵輸入。
5. 存詞後透過既有的 `IMEMessage.ReloadPersonalDict` 通知 `ChineseIME` 重新產生詞典並部署。

## 元件

### 1. `ZhuyinSyllables`（新檔，`java/src/org/futo/inputmethod/engine/general/ZhuyinSyllables.kt`）

純 Kotlin，無 Android 依賴。

- `fun toLunaPinyin(zhuyin: String): String?`：把注音串轉成以空格分隔的 luna 拼音音節，
  例如 `ㄋㄧˇㄏㄠˇ` → `ni hao`；任何一個音節無法解析時回傳 null。
- 切分規則：一個音節依序最多含一個聲母（ㄅ…ㄙ）、一個介音（ㄧㄨㄩ）、一個韻母（ㄚ…ㄦ）、
  一個聲調（ˉ ˊ ˇ ˋ ˙）。遇到不能接續的符號（例如已有韻母後再出現介音、
  已有介音或韻母後出現聲母）就開始新音節。聲調只作為分隔，不輸出。
  空音節（例如連續兩個聲調、開頭就是聲調）視為錯誤。
- 拼音規則（與 `luna_pinyin` 詞典音節表一致）：
  - ㄓㄔㄕㄖㄗㄘㄙ 單獨成音節 → `zhi chi shi ri zi ci si`。
  - 無聲母時：ㄧ → `yi`，ㄧㄚ `ya`，ㄧㄛ `yo`，ㄧㄝ `ye`，ㄧㄞ `yai`，ㄧㄠ `yao`，ㄧㄡ `you`，
    ㄧㄢ `yan`，ㄧㄣ `yin`，ㄧㄤ `yang`，ㄧㄥ `ying`；
    ㄨ → `wu`，ㄨㄚ `wa`，ㄨㄛ `wo`，ㄨㄞ `wai`，ㄨㄟ `wei`，ㄨㄢ `wan`，ㄨㄣ `wen`，ㄨㄤ `wang`，ㄨㄥ `weng`；
    ㄩ → `yu`，ㄩㄝ `yue`，ㄩㄢ `yuan`，ㄩㄣ `yun`，ㄩㄥ `yong`。
  - 有聲母時：ㄧ `i`、ㄨ `u`、ㄩ 在 ㄐㄑㄒ 後 `u`、在 ㄋㄌ 後 `v`；
    ㄧㄡ `iu`，ㄨㄟ `ui`，ㄨㄣ `un`，ㄧㄣ `in`，ㄩㄣ `un`，ㄧㄥ `ing`，ㄨㄥ `ong`，ㄩㄥ `iong`，
    ㄧㄢ `ian`，ㄩㄢ `uan`，ㄧㄝ `ie`，ㄩㄝ `ue`（ㄋㄌ 後 `ve`）。
  - 單獨韻母：ㄚ `a`，ㄛ `o`，ㄜ `e`，ㄝ `eh`，ㄞ `ai`，ㄟ `ei`，ㄠ `ao`，ㄡ `ou`，
    ㄢ `an`，ㄣ `en`，ㄤ `ang`，ㄥ `eng`，ㄦ `er`。ㄦ 不能帶聲母或介音。
  - 其他組合（例如 ㄅㄩ、ㄧㄨ 同一音節）視為錯誤。
- 產生的拼音不驗證是否真的存在於詞典音節表；不存在的音節在 Rime 部署時只是打不出來，
  不會造成錯誤。

### 2. `ChineseCustomDict`（新檔，`java/src/org/futo/inputmethod/engine/general/ChineseCustomDict.kt`）

純 Kotlin。

- `const val DICT_NAME = "futo_custom"`，檔名 `futo_custom.dict.yaml`。
- `fun entries(words: List<PersonalWord>): List<Entry>`：
  取 `locale` 的語言為 `zh` 且 `shortcut` 能被 `ZhuyinSyllables.toLunaPinyin` 解析的詞；
  `Entry(text, code, weight)`，`weight` 為 `frequency`（小於 1 時視為 1）。
  `locale` 為 null（「所有語言」）的詞沒有注音，略過。詞中含 Tab 或換行的略過。
- `fun render(entries: List<Entry>): String`：產生詞典內容：

  ```yaml
  # Rime dictionary
  # 由 FUTO Keyboard 依個人字典自動產生，請勿手動編輯。
  ---
  name: futo_custom
  version: "1"
  sort: by_weight
  use_preset_vocabulary: false
  ...
  你好	ni hao	250
  ```

  沒有任何詞時只有表頭，仍是合法詞典。
- 表頭之後、真實詞條之後固定附加 256 筆填充詞條 `pad0…pad255`，編碼 `#`、權重 1。
  原因：librime 1.16 建表時以 `4096 + 32 × 音節數 + 64 × 詞條數` 估算檔案大小，字串表（marisa trie）
  的固定開銷有數 KB，只有幾個詞的詞典會超出估算而觸發檔案擴大，`metadata_` 指標懸空，
  部署在 `rime::Table::OnBuildFinish` 以 SIGSEGV 崩潰並不斷重啟。`#` 不在注音 speller 的
  alphabet 內（會交給 punctuator），填充詞永遠打不出來；文字不能以 `#` 開頭，否則被當成註解。
- `fun write(userDir: File, content: String): Boolean`：內容與現有檔案相同時不寫入、回傳 true；
  寫入失敗（IOException）記錄日誌並回傳 false。
- `content.hashCode()` 作為設定指紋（見下）。

### 3. Rime 方案檔（`java/assets/rime/`）

- 新增 `futo_custom_bopomofo.schema.yaml`：

  ```yaml
  schema:
    schema_id: futo_custom_bopomofo
    name: 注音個人字典
    version: "1.0"
  engine:            # 只為了讓部署器編譯詞典與 prism；不會被選為輸入方案
    processors: [ speller ]
    segmentors: [ abc_segmentor ]
    translators: [ table_translator ]
  speller:
    alphabet: '1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'
    delimiter: "'"
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
  ```

  聲調規則固定用 `luna_tones`（詞典沒有聲調），terra 布局下也一樣。
- `futo_bopomofo.schema.yaml`：`schema/dependencies` 加 `futo_custom_bopomofo`；
  `engine/translators` 在 `punct_translator` 之後、`table_translator@custom_phrase` 之前
  加 `script_translator@custom_dict`；新增：

  ```yaml
  custom_dict:
    dictionary: futo_custom
    prism: futo_custom_bopomofo
    enable_completion: false
    enable_sentence: false
    enable_user_dict: false
    initial_quality: 1000000000000
    preedit_format:
      - "xlit|1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/- 6347'|ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙㄧㄨㄩㄚㄛㄜㄝㄞㄟㄠㄡㄢㄣㄤㄥㄦˉˊˇˋ˙ |"
  ```

  `enable_completion: false`：只有整個輸入的音節序列與詞完全對應時才出現，
  避免打單字時被個人詞插隊。省略末尾聲調仍能匹配，因為 prism 本身含無聲調形式。
  Rime 先比候選涵蓋的長度（較長者在前），長度相同才比品質；`initial_quality` 直接加進品質，
  用極大的值保證同長度時個人詞排在詞庫候選之前，輸入比個人詞更長時詞庫的長詞仍會勝出；`preedit_format` 與主 translator 相同，
  否則個人詞成為首選時預編輯會顯示原始按鍵而不是注音。
- `futo_bopomofo_terra.schema.yaml`：透過 `__include` 已繼承 `engine` 與 `custom_dict`；
  其自行宣告的 `schema/dependencies` 同樣加 `futo_custom_bopomofo`。
- `default.custom.yaml` 不變：依賴方案不需要列在 `schema_list`。
- `BundledRimeAssets`：`SHARED_FILES` 加 `futo_custom_bopomofo.schema.yaml`，`VERSION` 升為 4。
  `ensure()` 在主執行緒（`onStartInput`）被呼叫，原本以 `setSettingBlocking`（`runBlocking`）
  寫版本號，在真機上造成主執行緒死鎖（ANR，卡住 3 分鐘以上）；改為在 IO 協程寫入，
  並以程序內的 `ensuredVersion` 避免同一程序重複複製。

### 4. `ChineseIME` 變更

- `Configuration` 增加 `customDictHash: Int`；只有 `futo_bopomofo*` 方案用詞典內容的 hash，
  其他方案固定為 0，拼音、筆畫布局不會因中文個人詞變動而重新部署（詞典檔仍無條件寫出）。
- `updateConfig()` 在組出 `Configuration` 前：在 `Dispatchers.IO` 讀 `UserDictionaryIO(context).get()`
  （跨程序 ContentResolver 查詢），經 `ChineseCustomDict.entries` / `render` 得到內容與 hash；
  `entries` 依詞、編碼、權重排序，指紋不受 provider 回傳順序影響。
  IO 區段是掛起點，回來後再檢查一次 `editingState.active`，避免編輯會話中重新部署。
  `config != prevConfiguration` 時，在 `writeCustomizationFile` 旁呼叫 `ChineseCustomDict.write`；
  寫入失敗就結束本次 `updateConfig()`（不部署、`prevConfiguration` 維持不變），
  成功則照原流程 `rime.deploy()` + `selectSchema`。
  Rime 只會重新編譯內容有變的詞典，所以沒有改詞時的部署成本不變。
- `GlobalIMEMessage` 的 `when` 增加 `IMEMessage.ReloadPersonalDict -> updateConfig()`。
  hash 變了就會重新部署；沒變（例如編輯了別的語言的詞）就什麼都不做。
- 首次啟動與清除學習詞庫後：`onStartInput` 與 `clearUserHistoryDictionaries`
  最後都會呼叫 `updateConfig()`，此時 `prevConfiguration` 為 null，一定會寫詞典再部署，
  所以 `futo_custom.dict.yaml` 在任何部署前都存在。
  `onStartInput` 中 `BundledRimeAssets.ensure` 回傳 true 時會先立即 `rime.deploy()` 一次；
  那次部署詞典可能還不存在，只會記錄一條錯誤，接著的 `updateConfig()` 會再部署一次補上。
- `coroScope` 是 `Dispatchers.Main`，所以讀個人字典、渲染與寫檔都包在 `withContext(Dispatchers.IO)`。

### 5. 設定頁

- `PersonalDictionary.kt`：`"zh"` 改為
  `ExceptionalPersonalDictionaryViewConfiguration(supported = true, wordPopupDialog = { w, l -> ZhuyinWordPopupDialog(w, l) })`。
  `transformNavWord` 用預設值：列表直接顯示 `word`（詞）與 `shortcut`（注音）。
- 新檔 `pdict/PersonalDictionaryZh.kt`，`ZhuyinWordPopupDialog(selectedWord: PersonalWord?, locale: Locale?)`：
  - 「詞」：`SettingsTextEdit`，預填既有的 `word`。
  - 「注音」標籤下有一行說明「只在注音鍵盤生效，拼音與筆畫鍵盤不會使用這些字詞」，
    因為 `zh` 語言整體開放，簡體拼音使用者也看得到這個對話框。
  - 「注音」：唯讀顯示目前的注音串（預填既有的 `shortcut`），下方是按鍵區：
    37 個注音符號（ㄅ…ㄦ）、5 個聲調（ˉ ˊ ˇ ˋ ˙）、退格；點一下在尾端附加或刪除一個符號。
    這樣不依賴當前啟用的鍵盤，也不會被輸入法把符號轉成漢字。
  - 注音串非空且 `ZhuyinSyllables.toLunaPinyin` 回傳 null 時，顯示錯誤文字「注音不完整或有誤」。
  - 確定鍵在詞非空、注音非空且能解析時才啟用。
  - 儲存：`PersonalWord(word, frequency = 250, locale = locale.toString(), appId = 0, shortcut = 注音串)`；
    編輯時先 `remove` 舊詞再 `put`；刪除鍵 `remove`。`UserDictionaryIO.remove` 的條件加入
    `shortcut`（原本只比對 word 與 locale），否則一詞多音（行：ㄒㄧㄥˊ／ㄏㄤˊ）編輯其中一個會把另一個一起刪掉。三者之後都 `GlobalIMEMessage.tryEmit(ReloadPersonalDict)`
    並 `navigateUp()`，與日文對話框相同。
  - 字串：「注音：」與錯誤文字「注音不完整或有誤」直接寫在對話框裡（與日文對話框的
    「よみ:」「単語:」相同做法；`translations` 是子模組，不在這裡加資源字串）；
    詞、標題、按鈕沿用既有 `user_dict_settings_*` 字串。

## 資料流

設定頁存詞 → `ReloadPersonalDict` → `ChineseIME.updateConfig()` → 讀個人字典、產生詞典內容
→ hash 與上次不同 → 寫 `futo_custom.dict.yaml`、寫 custom.yaml、`deploy`、`selectSchema`
→ Rime 編譯 `futo_custom` 詞典與 `futo_custom_bopomofo` prism
→ 使用者打出對應注音時，`custom_dict` 的候選排在 `script_translator` 候選之前。

## 錯誤處理

- 詞典寫入失敗：記錄日誌，不更新 `prevConfiguration`，下次 `updateConfig()` 重試；
  Rime 沿用上次成功編譯的詞典。
- 詞典檔不存在時的部署：`futo_custom_bopomofo` 編譯失敗、`custom_dict` 載入失敗，
  只有日誌，注音方案其餘功能正常。
- 個人字典中 `shortcut` 無法解析的中文詞（例如由匯入產生）：略過，不影響其他詞。
- 部署中 `rimeLoading` 為 true，沿用現有的載入提示。

## 測試

- `tests/unit/.../ZhuyinSyllablesTest.kt`：
  單音節（有聲母、無聲母、ㄧㄨㄩ 特例、ㄦ、ㄩ 在 ㄐㄑㄒ 與 ㄋㄌ 後）、多音節有無聲調、
  聲調當分隔、非法輸入（空字串、開頭聲調、連續聲調、ㄅㄩ、ㄧㄨ、非注音字元）回傳 null。
- `tests/unit/.../ChineseCustomDictTest.kt`：
  只取 zh 詞、略過無法解析與 locale 為 null 的詞、權重下限、空詞典的表頭、
  相同輸入內容與 hash 穩定、含 Tab 的詞略過。
- 手動（小米手機）：新增「你好 / ㄋㄧˇㄏㄠˇ」→ 注音布局打 ㄋㄧˇㄏㄠˇ 候選首位為「你好」，
  打 ㄋㄧˇㄏㄠ（省略末聲調）同樣出現；刪除後不再出現；清除學習詞庫後仍然有效；
  terra 詞庫存在時同樣有效。
