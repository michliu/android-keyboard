# AI 潤飾 Action 設計

日期：2026-08-30

## 目標

新增一個 Action「AI 潤飾」：開啟面板後分三個區域——上：LLM prompt（預設為基本文字潤飾 prompt，可改）、
中：使用者文字（自動預填）、下：LLM 潤飾結果——並可把結果替換／插入回編輯框或複製。
LLM 走遠端 OpenAI 相容的 chat completions API（Base URL、API Key、Model 由設定頁提供）。

## 背景與限制

- Action 機制：`Action(icon, name, windowImpl, settingsMenu, …)` 註冊於
  `java/src/org/futo/inputmethod/latin/uix/actions/Registry.kt` 的 `AllActionsMap`（索引必須穩定，只能在末尾追加）。
  面板為 `ActionWindow`（Compose，`WindowContents`），面板內文字框用 `ActionTextEditor`
  （會把鍵盤輸入接管到面板內，剪貼簿搜尋即此做法）。
- 內建 on-device Transformer LM 只提供 `getSuggestions/rescore`，無法自由生成，故採遠端 API。
- App 目前沒有 `INTERNET` 權限（FUTO 上游刻意離線）；本功能僅用於個人 fork，需新增權限。
  原文會送到使用者設定的外部服務。
- 專案已有 `HttpURLConnection` 與 `kotlinx-serialization-json`，不新增依賴。

## 元件

### 1. 註冊與檔案

- `Registry.kt`：`AllActionsMap` 末尾追加 `"ai_polish" to AiPolishAction`；`DefaultActionSettings` 的 Favorites 末尾加入。
- 新目錄 `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/`：
  - `AiPolishSettings.kt`：`SettingsKey<String>` 四項與設定頁 `UserSettingsMenu`。
  - `AiPolishClient.kt`：請求／回應資料類、`PolishTransport` 介面、`HttpUrlPolishTransport`、`AiPolishClient`。
  - `AiPolishSource.kt`：預填來源的純函式與型別（`SourceKind`、`Source`、`pickSource`，見 §5）。
  - `AiPolishAction.kt`：`AiPolishAction`、`AiPolishWindow`（面板 UI）與寫回邏輯。
- `java/AndroidManifest.xml`：新增 `<uses-permission android:name="android.permission.INTERNET"/>`。
- 字串：`java/res/values/strings-uix.xml` 新增英文字串（`action_ai_polish_*`）；中文翻譯屬 translations 子模組，本次不做。
- 圖示：沿用既有 `R.drawable.edit_text`。

### 2. 設定（`AiPolishSettings.kt`）

| 設定 | key | 預設 |
|---|---|---|
| Base URL | `ai_polish_base_url` | `https://api.openai.com/v1` |
| API Key | `ai_polish_api_key` | 空 |
| Model | `ai_polish_model` | `gpt-4o-mini` |
| 預設 Prompt | `ai_polish_prompt` | 見下 |

預設 Prompt：
`你是文字潤飾助手。請在不改變原意的前提下，修正錯字與語法、讓語句更通順自然，保留原本的語言與語氣，只輸出潤飾後的文字，不要加任何說明。`

設定頁：`UserSettingsMenu(title, navPath = "actions/ai_polish", registerNavPath = true)`，四個 `SettingTextField`
（既有元件為單行；Prompt 也用單行，較長的編輯在面板內進行）。以 `Action.settingsMenu` 掛上，出現在 Actions 設定的齒輪處。

### 3. 面板（`AiPolishWindow : ActionWindow`）

- `Action(canShowKeyboard = true, windowImpl = …)`，面板 `onlyShowAboveKeyboard = false`，顯示關閉鈕。
- 限制：`LatinIME` 只有一個 InputConnection 覆寫槽位，`ActionTextEditor` 進入組合時就會覆寫、dispose 時才解除，因此
  **同一時刻只能掛載一個 `ActionTextEditor`**，且切換必須分兩步：先把槽位設為 `None` 卸載目前的編輯器，
  待舊編輯器真的釋放覆寫後再掛載目標編輯器。第二步由舊編輯器的 `afterUnOverride` callback 觸發
  （`ActionTextEditor` 在 `unsetInputConnection()` 之後呼叫），因此是事件驅動而非計時等待：
  記住 `pendingSlot`，在 callback 中才把槽位設為目標。若在同一次重組中換掉，新編輯器的覆寫會被舊編輯器的解除清掉
  （真機實測：鍵盤輸入落到宿主輸入框）。
- 版面（Column，三區都可見）：
  1. Prompt：預設以只讀文字顯示（點擊或按「Edit」進入編輯），編輯時掛載多行 `ActionTextEditor`（`autofocus = true`），
     按「Done」結束；右側「重設」鈕恢復設定值。
  2. 原文：未編輯 Prompt 時掛載多行 `ActionTextEditor`（`autofocus = false`），初值 = 預填文字（見 §5）；
     編輯 Prompt 期間降級為只讀文字。
  3. 結果：只讀、可捲動 `Text`；請求中顯示 `CircularProgressIndicator`；失敗顯示可捲動的錯誤文字。
  各區以帶邊框的 `Surface` 包裹；高度緊湊（Prompt 編輯器 56dp、原文 80dp、結果 80dp），外層 Column 可捲動。
  4. 按鈕列：「潤飾」（原文為空時停用）、「替換」「插入」「複製」（無結果時停用）。
- 狀態：`Idle / Loading / Success(text) / Error(message)`，存在面板實例內；關閉面板時取消進行中的 coroutine。
- 面板內兩個文字框在編輯時會接管鍵盤輸入（`ActionTextEditor` 既有行為），因此原文與 prompt 皆可在面板內修改。

### 4. `KeyboardManagerForAction` 新增（`Action.kt` 介面 + `UixManager.kt` 實作）

```kotlin
fun getSelectedText(): String?          // ic.getSelectedText(0)?.toString()，無選取回 null
fun getAllText(): String?               // ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
fun replaceAllText(text: String)        // beginBatchEdit; setSelection(0, len); commitText(text, 1); endBatchEdit
```
`replaceAllText` 只做一次 `getExtractedText`，且僅在 `partialStartOffset == -1`（編輯器給的是整段文字）時
才 `setSelection(0, len)`；否則退回單純在游標處 `commitText`，以免取代到未知的前綴。`ic` 為 null 時不做事。

### 5. 預填與寫回（純函式放在 `AiPolishSource.kt`，可單測）

```kotlin
enum class SourceKind { Selection, WholeField, None }
data class Source(val text: String, val kind: SourceKind)
fun pickSource(selected: String?, all: String?): Source
```
- 選取非空 → `Selection`；否則整框非空 → `WholeField`；否則 `None`（空字串）。
- 開啟面板時呼叫一次並記住 `kind`。
- **密碼欄位不預填**：開啟面板時以 `KeyboardManagerForAction.isHostPasswordField()` 判斷宿主輸入框是否為密碼欄位，
  該實作讀取宿主（未被 action 的 `ActionTextEditor` 覆寫過）的 `EditorInfo`（`LatinIME.getBaseInputEditorInfo()`）
  搭配 `InputTypeUtils.isPasswordInputType()/isVisiblePasswordInputType()` 判斷；是的話完全不呼叫
  `getSelectedText()/getAllText()`，直接用 `Source("", SourceKind.None)`，並在結果區的 Idle 狀態顯示
  `action_ai_polish_sensitive_field` 說明未載入。避免密碼以明文顯示或被送到外部 API；使用者仍可自行輸入文字使用。
- 「替換」：`Selection` → `manager.typeText(result)`（commitText 會取代選取區）；
  `WholeField` → `manager.replaceAllText(result)`；`None` → 等同「插入」。
- 「插入」：`manager.typeText(result)`。
- 「複製」：`ClipboardManager.setPrimaryClip`。
- 替換／插入後關閉面板。

### 6. `AiPolishClient`

```kotlin
data class PolishConfig(val baseUrl: String, val apiKey: String, val model: String)
fun interface PolishTransport { fun post(url: String, headers: Map<String, String>, body: String): TransportResponse }
data class TransportResponse(val status: Int, val body: String)
class AiPolishClient(private val transport: PolishTransport) {
    suspend fun polish(config: PolishConfig, prompt: String, text: String): Result<String>
}
```
- URL：`baseUrl.trimEnd('/') + "/chat/completions"`。
- Body（kotlinx.serialization）：`{"model": m, "messages": [{"role":"system","content": prompt},{"role":"user","content": text}], "temperature": 0.3}`。
- Header：`Authorization: Bearer <key>`、`Content-Type: application/json`。
- 回應：`choices[0].message.content`（`Json { ignoreUnknownKeys = true }`），trim 後回傳。
- 錯誤（皆為 `Result.failure(PolishException(message))`）：
  - Base URL 或 API Key 空 → 「請先在設定中填入 API URL 與 Key」；
  - 傳輸例外（無網路、逾時 60 秒）→ 例外訊息；
  - HTTP 非 2xx → `HTTP <status>: <error.message 若可解析，否則 body 前 200 字>`；
  - 解析失敗／無 choices → 「回應格式無法解析」。
- `HttpUrlPolishTransport`：`HttpURLConnection`，connect/read timeout 60 s，在 `Dispatchers.IO` 執行。

### 7. 資料流

```
開啟 Action → pickSource(getSelectedText(), getAllText()) → 原文框預填
「潤飾」→ AiPolishClient.polish(設定, prompt框, 原文框) → 結果框
「替換」→ Selection: typeText / WholeField: replaceAllText → 關閉面板
```

## 測試

- JVM 單測（`tests/unit/.../aipolish/`）：
  - `pickSource`：選取優先、整框次之、皆空為 None。
  - 請求 JSON：URL 拼接（含尾斜線）、header、body 內容。
  - 回應解析：正常 content、非 2xx 含 `error.message`、無 choices、非 JSON。
  - 設定空值 → 失敗訊息。
- 真機：設定 URL/Key/Model → 輸入框打一句有錯字的中文 → 開面板預填 → 潤飾 → 結果 → 替換／插入／複製 →
  錯誤 Key 顯示 HTTP 401 訊息 → 無網路顯示例外訊息。

## 不做

串流輸出、多輪對話、歷史紀錄、多 provider、本地模型、Prompt 範本管理、UI 中文翻譯。
