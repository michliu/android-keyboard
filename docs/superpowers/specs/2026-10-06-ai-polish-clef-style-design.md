# AI 潤飾：用 Clef 自動判斷場合與語氣

## 目標

打開 AI 潤飾視窗時，用 Cloudflare Workers AI 的 Clef 判斷原文的場合（聊天／Email／正式／社群）和語氣強度，預先選好對應的潤飾風格。使用者可以在潤飾前改掉判斷結果。

Clef（`@cf/cloudflare/clef`）是分類模型：輸入一段內容和一組問題，回傳每個選項的機率，不產生文字。它不取代現有的潤飾模型，只負責決定要在 prompt 後面附加哪些風格指示。

## 實測依據（2026-10-06，8 句繁中）

- 場合、語氣：`clef` 和 `clef-flash` 都判斷得準。
- 錯字：兩者都不可靠，所以不做錯字判斷，也不用它決定「不需潤飾」。
- 延遲：每次 0.2–1 秒；每次約 470 個輸入 tokens（約 0.0001 美元）。
- 選用完整版 `clef`：它在語氣衝的句子上判斷場合比 flash 更有把握，延遲也差不多。

## 範圍

**要做**
- 呼叫 Clef 的客戶端、風格定義、視窗上的風格標籤列、兩個新設定欄位。

**不做**
- 錯字、敏感資訊判斷；送出前提醒。
- 修改原文後重新判斷（只在打開視窗時判斷一次）。
- 讓使用者自訂風格種類或指示文字。

## 元件

### `ClefClient.kt`（`actions/aipolish`）

- `ClefConfig(accountId, apiToken)`。
- `suspend fun classify(config, text): Result<ClefResult>`，`ClefResult(scene: Scene?, sceneConfidence: Double, tone: Double)`。
- 呼叫 `POST https://api.cloudflare.com/client/v4/accounts/{accountId}/ai/run/@cf/cloudflare/clef`，`Authorization: Bearer {apiToken}`。
- 請求內容：`state` 是原文；`questions` 有兩題：
  - `scene`：`choice` 題，`criteria` 的鍵是 `chat`、`email`、`formal`、`social`，說明分別是「即時通訊聊天，口語」「工作 Email 或商務往來」「正式公文、公告」「社群貼文」；`instructions` 是「這段文字最可能用在什麼場合？」。
  - `tone`：`score` 題，`criteria` 是「平和、稍微不耐、明顯不滿、非常衝」；`instructions` 是「這段文字的語氣有多強硬或帶情緒？」。
- 回應解析：讀 `result.answers.scene.choice`、`result.answers.scene.confidence`、`result.answers.tone.score`。`choice` 不是這四個鍵之一時，`scene` 為 null。
- 共用現有的 `PolishTransport`，方便用假 transport 寫單元測試。
- 失敗情況（帳號 ID 或 token 空白、網路錯誤、非 2xx、`success` 為 false、JSON 無法解析）一律回傳 `Result.failure`；`CancellationException` 要往外拋。
- 逾時 5 秒：`HttpUrlPolishTransport` 改成可以傳入逾時毫秒數（預設維持 60 秒），Clef 用 5 秒的連線和讀取逾時，逾時視為失敗。不用 `withTimeout`，因為它無法中斷阻塞中的 `HttpURLConnection`。

### `PolishStyle.kt`（`actions/aipolish`）

- `enum class Scene { Chat, Email, Formal, Social }`，每種對應一個英文標籤（字串資源）和一句中文指示：
  - Chat：「風格：即時通訊聊天，口語自然、簡短。」
  - Email：「風格：工作 Email，禮貌清楚、適合商務往來。」
  - Formal：「風格：正式書面用語。」
  - Social：「風格：社群貼文，生動自然，保留表情符號與 hashtag。」
- 緩和語氣指示：「請把語氣改得平和有禮，但保留原本的訴求。」
- `data class StyleChoice(scene: Scene?, soften: Boolean)`。
- `fun StyleChoice.Companion.from(result: ClefResult): StyleChoice`：
  - `sceneConfidence >= 0.5` 才採用 `scene`，否則為 null。
  - `tone >= 1.5` 時 `soften = true`。
- `fun composePrompt(base: String, style: StyleChoice): String`：`base` 後面依序接上場合指示和緩和語氣指示，各自換行；沒有選任何風格時原樣回傳 `base`。

### 設定（`AiPolishSettings`）

- 新增 `ClefAccountId`、`ClefApiToken` 兩個字串設定，預設空白，放在現有欄位之後。
- `clefConfig(context)` 回傳 `ClefConfig`。
- 兩欄都有值才會呼叫 Clef。

### 視窗（`AiPolishAction.kt` 的 `AiPolishWindow`）

- 在 Prompt 區塊上方加一列風格標籤：Chat、Email、Formal、Social（單選，可以再點一次取消），以及 Soften tone（開關）。
- 標籤列下方一行狀態文字：
  - 判斷中：「Detecting style…」
  - 失敗：「Could not detect the style」
  - 判斷出 `soften = true`：「⚠ The tone seems harsh; it will be softened」
  - 未設定 Clef：不顯示狀態文字。
- 打開視窗、原文不是空白，而且 Clef 已設定時，在背景呼叫 `classify`。結果回來時，如果使用者還沒動過標籤，就套用 `StyleChoice.from(result)`；使用者已經手動改過標籤，就不覆蓋。
- 按「Polish」時，用 `composePrompt(prompt.value, 目前的 StyleChoice)` 當作 system prompt。prompt 編輯框的內容不變。
- 判斷還沒完成就按「Polish」：直接用目前的標籤狀態（通常是都沒選），不等待判斷。
- 關閉視窗時取消進行中的判斷。

## 隱私

打開潤飾視窗時，就會把原文（有選取時是選取的文字，沒有選取時是整個輸入框的內容）送到 Cloudflare 的 Clef，即使使用者最後沒有按下潤飾也一樣。這是潤飾視窗開啟後多出來的一次對外傳送，不能說隱私範圍不變。只有在使用者已經填好兩項 Cloudflare 設定之後才會發生（需主動啟用），沒填就完全不會呼叫 Clef。密碼欄位維持現有行為，不讀取文字，也就不會呼叫 Clef。

## 測試

**單元測試（JVM）**
- `ClefClient`：請求的網址、標頭和 JSON 內容；正常回應的解析；未知的 `choice`；非 2xx；`success: false`；無法解析的 JSON；帳號 ID 或 token 空白時不送請求。
- `StyleChoice.from`：信心 0.49 和 0.5、語氣 1.49 和 1.5 的邊界。
- `composePrompt`：沒有風格、只有場合、只有緩和語氣、兩者都有。

**真機測試**
- 用實測的那幾句話各打開一次潤飾視窗，確認預選結果：
  - 聊天句選 Chat。
  - 王經理那句選 Email。
  - 公告選 Formal。
  - 毛孩那句選 Social。
  - 「你到底有沒有在看訊息？？」開啟 Soften tone，並顯示警告。
- 手動改標籤後按 Polish，確認潤飾結果符合所選風格。
- 清空 token 後，標籤列照樣顯示、沒有預選，潤飾照常可用。
- 開飛航模式打開視窗，狀態顯示失敗，潤飾照常可用（潤飾本身會失敗，這是預期的）。
