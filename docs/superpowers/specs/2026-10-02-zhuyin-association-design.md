# 注音聯想詞設計

日期：2026-10-02

## 目標

注音布局上屏中文後，建議列顯示「聯想詞」：例如上屏「你」後出現「們」「好」「的」…；
點選聯想詞直接上屏，並以新的結尾繼續聯想，直到使用者開始做別的事。

## 背景與限制

- Rime 沒有「上屏後聯想」功能；Android 端的 librime 包裝（`libs/rime-release.aar`）
  也沒有前綴查詢介面，聯想必須在 `ChineseIME` 層自行實作。
- 注音方案使用 `luna_pinyin` 詞庫，來自使用者匯入的 `zidian.dict`（zip），解壓到
  `<externalFiles>/rime/shared`。`luna_pinyin.dict.yaml` 以 `use_preset_vocabulary`
  引用 `essay.txt` 作為詞組來源。已在真機確認 `zidian-v26.02` 的 `rime/shared` 附有 `essay.txt`
  （5.9MB、442,714 行、每行 `詞<TAB>權重`、繁體）；例如「你」開頭依權重為：
  的、們、可以、要、會、就、是、好…
- Rime 的上屏一律經 `RimeMessage.Commit`（`subscribeToRimeMessage`），其中也包含標點；
  標點不會在索引中命中，自然不產生聯想。
- 候選列由 `subscribeToRimeCandidates` 更新；上屏後 Rime 會送出空候選清單，
  聯想詞的顯示不能被這次空更新蓋掉。

## 資料來源與索引（新增 `ChineseAssociation.kt`）

- 來源：`shared/essay.txt`（每行 `詞<TAB>權重`）。檔案不存在或讀取失敗時聯想停用，輸入法其餘行為不變。
  （真機已確認詞庫附有此檔，不實作 `luna_pinyin.dict.yaml` 備援。）
- 建索引：在 IO 執行緒讀一次檔案，建立 `Map<String, Array<String>>`：
  - 只收錄全為漢字（`UnicodeScript.HAN`）且長度 ≥ 2 的詞；格式錯誤的行略過。
  - 鍵為詞的前 1 個字，值為其餘部分；長度 ≥ 3 且權重 ≥ 500 的詞另以前 2 個字為鍵。
    （不設門檻時兩字鍵約 10 萬個、19 萬筆，記憶體過大；門檻 500 時合計約 3.3 萬鍵、8 萬筆。）
  - 每個鍵只保留權重最高的 8 個；同權重依檔案順序；同一值重複出現只留第一次。
  - 長度以 code point 計（支援擴充區漢字）。
- 查詢 `lookup(text: String): List<String>`：
  先以 `text` 結尾 2 個字查詢，無結果再以結尾 1 個字查詢；索引尚未建好時回傳空清單。
  觸發時 `text` 為剛上屏的文字；點選聯想詞時 `text` 為「上一輪的 text + 點選的詞」，
  因此「你」→ 點「們」後以「你們」查詢（這些、自己、可以…），而不是只用「們」（的、就）。
- 載入時機：注音布局下第一次 `onLayoutUpdated`／`onStartInput` 時載入一次（拼音、筆畫布局不載入）；
  `resetSharedFromResources` 回報詞庫有變（重新解壓）時重新載入。
- 純 Kotlin、不依賴 Android，可單元測試（以 `Reader` 為輸入）。
- 真機記錄載入耗時與鍵數（logcat），驗證記憶體增量。

## 互動（`ChineseIME`）

- 狀態：`associationContext: String?`（非 null 表示聯想進行中）與目前顯示的聯想清單。
- 觸發：收到 `RimeMessage.Commit` 上屏後，若目前為注音方案（`futo_bopomofo*`）、聯想開關開啟、非簡體（`futo_zh_simp` 關閉）、
  目前不在英文頁且沒有組字，則以上屏文字查詢；有結果就在建議列顯示，`associationContext` 設為該文字。
- 點選聯想詞：`connect?.commitText(word, 1)`，再以「上一輪 text + word」查詢下一輪聯想（連續聯想）；
  無結果則結束聯想。聯想詞以一般 `SuggestedWordInfo` 呈現，`onEvent` 以「聯想進行中且點選的詞
  與聯想清單同位置的詞相同」判斷是聯想詞。
- 顯示前確認游標前的文字確實以剛上屏的文字結尾；不是的話（例如上屏後又直接輸入了數字）就不顯示。
- 結束聯想（清空建議列、`associationContext = null`）：按任何注音鍵（照常開始組字）、退格、
  移動游標、空白、標點、切到英文頁、`onFinishInput`、`onStartInput`。
- `subscribeToRimeCandidates` 收到空清單且聯想進行中時不覆蓋建議列。

## 設定

中文設定（`IMESettingsHelper` / ChineseIMESettings 相關頁面）新增「聯想詞」開關，預設開啟。

## 測試

- 單元測試 `ChineseAssociation`：排序與前 8 名截斷、同權重順序、2 字退回 1 字、兩字鍵權重門檻、
  非漢字與格式錯誤行略過、擴充區漢字、檔案不存在。
- 真機驗證：打「你」選字 → 建議列出現「們」；點「們」→ 繼續出現聯想；
  按注音鍵 → 聯想消失並正常組字；退格 → 聯想消失；簡體開啟或開關關閉 → 不出現聯想。

## 不在範圍內

- 依使用者個人習慣調整聯想排序。
- 拼音布局。
- 簡體聯想（詞表為繁體）。
