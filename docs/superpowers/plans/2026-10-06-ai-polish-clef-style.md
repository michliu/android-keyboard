# AI 潤飾 Clef 風格判斷 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打開 AI 潤飾視窗時，用 Cloudflare Clef 判斷原文的場合和語氣，預先選好風格標籤；按 Polish 時把所選風格的指示接在 prompt 後面。

**Architecture:** 純 Kotlin 的 `PolishStyle.kt`（場合、風格選擇、組 prompt）和 `ClefClient.kt`（呼叫 Clef、解析回應，共用現有的 `PolishTransport`）都可以在 JVM 上做單元測試。`AiPolishWindow` 在建立時於背景呼叫 Clef，並新增一列 `FilterChip` 標籤。

**Tech Stack:** Kotlin、Jetpack Compose Material3、kotlinx.serialization、kotlinx.coroutines、JUnit4。

**Spec:** `docs/superpowers/specs/2026-10-06-ai-polish-clef-style-design.md`

## Global Constraints

- 套件：`org.futo.inputmethod.latin.uix.actions.aipolish`；原始碼在 `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/`，測試在 `tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/`。
- Clef 端點：`https://api.cloudflare.com/client/v4/accounts/{accountId}/ai/run/@cf/cloudflare/clef`，`Authorization: Bearer {apiToken}`。
- 門檻：場合信心 `>= 0.5` 才預選；語氣分數 `>= 1.5` 打開緩和語氣。
- Clef 逾時 5000 ms（連線和讀取）。潤飾本身維持 60 秒。
- 介面文字用英文（字串資源，`java/res/values/strings-uix.xml`）；送給模型的指示用中文。
- `PolishStyle.kt`、`ClefClient.kt` 不可 import `android.*`。
- 指令：單測 `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.*'`；APK `./gradlew assembleUnstableDebug`。
- Commit 訊息結尾加上：
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01SxYaJPcW5bNCc9K2YVp6xA
  ```

## 檔案

| 檔案 | 職責 |
|---|---|
| `PolishStyle.kt`（新增） | `Scene`、`ClefResult`、`StyleChoice`、`composePrompt` |
| `ClefClient.kt`（新增） | `ClefConfig`、`ClefClient.classify` |
| `AiPolishClient.kt`（修改） | `HttpUrlPolishTransport` 可傳入逾時 |
| `AiPolishSettings.kt`（修改） | 兩個新設定與 `clefConfig` |
| `AiPolishAction.kt`（修改） | 視窗的標籤列、背景判斷、組 prompt |
| `strings-uix.xml`（修改） | 新字串 |

---

### Task 1: 風格定義與 prompt 組合

**Files:**
- Create: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/PolishStyle.kt`
- Test: `tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/PolishStyleTest.kt`

**Interfaces:**
- Produces:
  - `enum class Scene(val key: String, val instruction: String) { Chat, Email, Formal, Social }`，`Scene.fromKey(key: String?): Scene?`
  - `const val SOFTEN_INSTRUCTION: String`、`MIN_SCENE_CONFIDENCE = 0.5`、`MIN_HARSH_TONE = 1.5`
  - `data class ClefResult(val scene: Scene?, val sceneConfidence: Double, val tone: Double)`
  - `data class StyleChoice(val scene: Scene?, val soften: Boolean)`，`StyleChoice.None`，`StyleChoice.from(result: ClefResult): StyleChoice`
  - `fun composePrompt(base: String, style: StyleChoice): String`

- [ ] **Step 1: 寫失敗的測試**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolishStyleTest {
    @Test
    fun sceneFromKey() {
        assertEquals(Scene.Chat, Scene.fromKey("chat"))
        assertEquals(Scene.Email, Scene.fromKey("email"))
        assertEquals(Scene.Formal, Scene.fromKey("formal"))
        assertEquals(Scene.Social, Scene.fromKey("social"))
        assertNull(Scene.fromKey("other"))
        assertNull(Scene.fromKey(null))
    }

    @Test
    fun sceneNeedsHalfConfidence() {
        assertNull(StyleChoice.from(ClefResult(Scene.Email, 0.49, 0.0)).scene)
        assertEquals(Scene.Email, StyleChoice.from(ClefResult(Scene.Email, 0.5, 0.0)).scene)
    }

    @Test
    fun softenFromToneOneAndAHalf() {
        assertEquals(false, StyleChoice.from(ClefResult(null, 0.0, 1.49)).soften)
        assertEquals(true, StyleChoice.from(ClefResult(null, 0.0, 1.5)).soften)
    }

    @Test
    fun unknownSceneStaysNull() {
        assertEquals(StyleChoice(null, false), StyleChoice.from(ClefResult(null, 0.9, 0.1)))
    }

    @Test
    fun composeWithoutStyleKeepsBase() {
        assertEquals("base", composePrompt("base", StyleChoice.None))
    }

    @Test
    fun composeWithSceneOnly() {
        assertEquals("base\n${Scene.Email.instruction}", composePrompt("base", StyleChoice(Scene.Email, false)))
    }

    @Test
    fun composeWithSoftenOnly() {
        assertEquals("base\n$SOFTEN_INSTRUCTION", composePrompt("base", StyleChoice(null, true)))
    }

    @Test
    fun composeWithBoth() {
        assertEquals(
            "base\n${Scene.Chat.instruction}\n$SOFTEN_INSTRUCTION",
            composePrompt("base", StyleChoice(Scene.Chat, true))
        )
    }
}
```

- [ ] **Step 2: 執行測試，確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.PolishStyleTest'`
Expected: 編譯失敗，`Unresolved reference: Scene`。

- [ ] **Step 3: 實作**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

/** What a piece of text is written for; [key] is the Clef choice key, [instruction] is appended to the prompt. */
enum class Scene(val key: String, val instruction: String) {
    Chat("chat", "風格：即時通訊聊天，口語自然、簡短。"),
    Email("email", "風格：工作 Email，禮貌清楚、適合商務往來。"),
    Formal("formal", "風格：正式書面用語。"),
    Social("social", "風格：社群貼文，生動自然，保留表情符號與 hashtag。");

    companion object {
        fun fromKey(key: String?): Scene? = values().firstOrNull { it.key == key }
    }
}

const val SOFTEN_INSTRUCTION = "請把語氣改得平和有禮，但保留原本的訴求。"
const val MIN_SCENE_CONFIDENCE = 0.5
const val MIN_HARSH_TONE = 1.5

/** Clef's verdict: the likeliest scene (null if unknown), how sure it was, and the tone score from 0 (calm) to 3 (harsh). */
data class ClefResult(val scene: Scene?, val sceneConfidence: Double, val tone: Double)

data class StyleChoice(val scene: Scene?, val soften: Boolean) {
    companion object {
        val None = StyleChoice(null, false)

        fun from(result: ClefResult) = StyleChoice(
            scene = result.scene.takeIf { result.sceneConfidence >= MIN_SCENE_CONFIDENCE },
            soften = result.tone >= MIN_HARSH_TONE,
        )
    }
}

/** The system prompt actually sent: the user's prompt followed by one line per chosen style. */
fun composePrompt(base: String, style: StyleChoice): String =
    listOfNotNull(base, style.scene?.instruction, SOFTEN_INSTRUCTION.takeIf { style.soften })
        .joinToString("\n")
```

- [ ] **Step 4: 執行測試，確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.PolishStyleTest'`
Expected: BUILD SUCCESSFUL，8 個測試通過。

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/latin/uix/actions/aipolish/PolishStyle.kt tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/PolishStyleTest.kt
git commit -m "Add AI Polish styles and prompt composition"
```
（訊息結尾加上 Global Constraints 的兩行。）

---

### Task 2: Clef 客戶端

**Files:**
- Create: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/ClefClient.kt`
- Modify: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishClient.kt`（`HttpUrlPolishTransport`）
- Test: `tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/ClefClientTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `Scene`、`ClefResult`；現有的 `PolishTransport`、`TransportResponse`、`PolishException`。
- Produces:
  - `data class ClefConfig(val accountId: String, val apiToken: String)`，`val isConfigured: Boolean`
  - `class ClefClient(transport: PolishTransport)`，`suspend fun classify(config: ClefConfig, text: String): Result<ClefResult>`
  - `ClefClient.TIMEOUT_MS = 5_000`、`ClefClient.NOT_CONFIGURED`、`ClefClient.UNPARSABLE`
  - `class HttpUrlPolishTransport(private val timeoutMs: Int = 60_000)`

- [ ] **Step 1: 寫失敗的測試**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class ClefClientTest {
    private val config = ClefConfig("acct123", "tok456")

    private class RecordingTransport(private val response: () -> TransportResponse) : PolishTransport {
        var url: String? = null
        var headers: Map<String, String>? = null
        var body: String? = null
        var calls = 0
        override fun post(url: String, headers: Map<String, String>, body: String): TransportResponse {
            calls++
            this.url = url; this.headers = headers; this.body = body
            return response()
        }
    }

    private fun ok(body: String) = RecordingTransport { TransportResponse(200, body) }

    private val emailResponse = """{"result":{"model":"clef","answers":{
        "scene":{"type":"choice","choice":"email","probabilities":{"chat":0.05,"email":0.9,"formal":0.04,"social":0.01},"confidence":0.9},
        "tone":{"type":"score","score":0.06,"legend":{"0":"平和"},"probabilities":{"0":0.95},"confidence":0.9}},
        "usage":{"input_tokens":479,"output_tokens":0}},"success":true,"errors":[],"messages":[]}"""

    @Test
    fun buildsRequest() = runBlocking {
        val transport = ok(emailResponse)
        ClefClient(transport).classify(config, "王經理您好")

        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct123/ai/run/@cf/cloudflare/clef",
            transport.url
        )
        assertEquals("Bearer tok456", transport.headers!!["Authorization"])
        assertEquals("application/json", transport.headers!!["Content-Type"])
        val body = Json.parseToJsonElement(transport.body!!).jsonObject
        assertEquals("王經理您好", body["state"]!!.jsonPrimitive.content)
        val questions = body["questions"]!!.jsonObject
        val scene = questions["scene"]!!.jsonObject
        assertEquals("choice", scene["type"]!!.jsonPrimitive.content)
        assertEquals(setOf("chat", "email", "formal", "social"), (scene["criteria"] as JsonObject).keys)
        val tone = questions["tone"]!!.jsonObject
        assertEquals("score", tone["type"]!!.jsonPrimitive.content)
        assertEquals(4, (tone["criteria"] as JsonArray).size)
    }

    @Test
    fun trimsCredentials() = runBlocking {
        val transport = ok(emailResponse)
        ClefClient(transport).classify(ClefConfig(" acct123 ", " tok456\n"), "x")
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/acct123/ai/run/@cf/cloudflare/clef",
            transport.url
        )
        assertEquals("Bearer tok456", transport.headers!!["Authorization"])
    }

    @Test
    fun parsesAnswers() = runBlocking {
        val result = ClefClient(ok(emailResponse)).classify(config, "x").getOrThrow()
        assertEquals(ClefResult(Scene.Email, 0.9, 0.06), result)
    }

    @Test
    fun unknownChoiceGivesNullScene() = runBlocking {
        val body = emailResponse.replace("\"choice\":\"email\"", "\"choice\":\"other\"")
        val result = ClefClient(ok(body)).classify(config, "x").getOrThrow()
        assertNull(result.scene)
    }

    @Test
    fun notConfiguredSendsNothing() = runBlocking {
        val transport = ok(emailResponse)
        val result = ClefClient(transport).classify(ClefConfig("acct", " "), "x")
        assertEquals(ClefClient.NOT_CONFIGURED, result.exceptionOrNull()!!.message)
        assertEquals(0, transport.calls)
    }

    @Test
    fun reportsCloudflareError() = runBlocking {
        val transport = RecordingTransport {
            TransportResponse(401, """{"result":null,"success":false,"errors":[{"code":10000,"message":"Authentication error"}],"messages":[]}""")
        }
        val result = ClefClient(transport).classify(config, "x")
        assertEquals("HTTP 401: Authentication error", result.exceptionOrNull()!!.message)
    }

    @Test
    fun reportsUnsuccessfulOkResponse() = runBlocking {
        val result = ClefClient(ok("""{"result":null,"success":false,"errors":[{"message":"bad input"}]}"""))
            .classify(config, "x")
        assertEquals("HTTP 200: bad input", result.exceptionOrNull()!!.message)
    }

    @Test
    fun reportsRawBodyWhenErrorIsNotJson() = runBlocking {
        val result = ClefClient(RecordingTransport { TransportResponse(502, "Bad Gateway") }).classify(config, "x")
        assertEquals("HTTP 502: Bad Gateway", result.exceptionOrNull()!!.message)
    }

    @Test
    fun reportsUnparsableAnswers() = runBlocking {
        val result = ClefClient(ok("""{"result":{"answers":{}},"success":true}""")).classify(config, "x")
        assertEquals(ClefClient.UNPARSABLE, result.exceptionOrNull()!!.message)
    }

    @Test
    fun reportsTransportFailure() = runBlocking {
        val result = ClefClient(RecordingTransport { throw IOException("timeout") }).classify(config, "x")
        assertEquals("timeout", result.exceptionOrNull()!!.message)
    }
}
```

- [ ] **Step 2: 執行測試，確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.ClefClientTest'`
Expected: 編譯失敗，`Unresolved reference: ClefConfig`。

- [ ] **Step 3: 實作 `ClefClient.kt`**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ClefConfig(val accountId: String, val apiToken: String) {
    val isConfigured: Boolean get() = accountId.isNotBlank() && apiToken.isNotBlank()
}

@Serializable
private data class ClefSceneAnswer(val choice: String? = null, val confidence: Double? = null)

@Serializable
private data class ClefToneAnswer(val score: Double? = null)

@Serializable
private data class ClefAnswers(val scene: ClefSceneAnswer? = null, val tone: ClefToneAnswer? = null)

@Serializable
private data class ClefRunResult(val answers: ClefAnswers? = null)

@Serializable
private data class CloudflareError(val message: String? = null)

@Serializable
private data class ClefResponse(
    val success: Boolean = false,
    val result: ClefRunResult? = null,
    val errors: List<CloudflareError> = emptyList(),
)

/** Asks Cloudflare Workers AI's Clef classifier what scene a text is written for and how harsh it sounds. */
class ClefClient(private val transport: PolishTransport) {
    companion object {
        const val NOT_CONFIGURED = "Set the Cloudflare account ID and API token first"
        const val UNPARSABLE = "The Clef response could not be parsed"
        const val TIMEOUT_MS = 5_000
        private const val MODEL = "@cf/cloudflare/clef"
        private val json = Json { ignoreUnknownKeys = true }

        private val SCENE_CRITERIA = linkedMapOf(
            Scene.Chat to "即時通訊聊天，口語",
            Scene.Email to "工作 Email 或商務往來",
            Scene.Formal to "正式公文、公告",
            Scene.Social to "社群貼文",
        )
        private val TONE_LEVELS = listOf("平和", "稍微不耐", "明顯不滿", "非常衝")
    }

    private fun requestBody(text: String): String = buildJsonObject {
        put("state", text)
        putJsonObject("questions") {
            putJsonObject("scene") {
                put("type", "choice")
                put("instructions", "這段文字最可能用在什麼場合？")
                putJsonObject("criteria") {
                    SCENE_CRITERIA.forEach { (scene, description) -> put(scene.key, description) }
                }
            }
            putJsonObject("tone") {
                put("type", "score")
                put("instructions", "這段文字的語氣有多強硬或帶情緒？")
                putJsonArray("criteria") { TONE_LEVELS.forEach { add(JsonPrimitive(it)) } }
            }
        }
    }.toString()

    suspend fun classify(config: ClefConfig, text: String): Result<ClefResult> {
        if (!config.isConfigured) return Result.failure(PolishException(NOT_CONFIGURED))
        val url = "https://api.cloudflare.com/client/v4/accounts/${config.accountId.trim()}/ai/run/$MODEL"
        val headers = mapOf(
            "Authorization" to "Bearer ${config.apiToken.trim()}",
            "Content-Type" to "application/json",
        )

        val response = try {
            withContext(Dispatchers.IO) { transport.post(url, headers, requestBody(text)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(PolishException(e.message ?: e.toString()))
        }

        val parsed = runCatching { json.decodeFromString<ClefResponse>(response.body) }.getOrNull()
        if (response.status !in 200..299 || parsed?.success == false) {
            val detail = parsed?.errors?.firstNotNullOfOrNull { it.message } ?: response.body.take(200)
            return Result.failure(PolishException("HTTP ${response.status}: $detail"))
        }

        val scene = parsed?.result?.answers?.scene
        val confidence = scene?.confidence
        val tone = parsed?.result?.answers?.tone?.score
        if (confidence == null || tone == null) return Result.failure(PolishException(UNPARSABLE))
        return Result.success(ClefResult(Scene.fromKey(scene?.choice), confidence, tone))
    }
}
```

注意：如果 `json.decodeFromString<ClefResponse>(...)` 編譯失敗（找不到 reified 版本），在檔案開頭加 `import kotlinx.serialization.decodeFromString`。`AiPolishClient.kt` 的寫法可以參考。

- [ ] **Step 4: `HttpUrlPolishTransport` 可傳入逾時**

在 `AiPolishClient.kt` 把：

```kotlin
class HttpUrlPolishTransport : PolishTransport {
```

改成：

```kotlin
class HttpUrlPolishTransport(private val timeoutMs: Int = 60_000) : PolishTransport {
```

並把

```kotlin
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000
```

改成

```kotlin
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
```

- [ ] **Step 5: 執行測試，確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.*'`
Expected: BUILD SUCCESSFUL；`ClefClientTest` 10 個、`PolishStyleTest` 8 個，以及原有的 `AiPolishClientTest`、`AiPolishSourceTest` 全部通過。

- [ ] **Step 6: Commit**

```bash
git add java/src/org/futo/inputmethod/latin/uix/actions/aipolish/ClefClient.kt java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishClient.kt tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/ClefClientTest.kt
git commit -m "Add a Clef client for the AI Polish style"
```
（訊息結尾加上 Global Constraints 的兩行。）

---

### Task 3: 設定與視窗標籤列

**Files:**
- Modify: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSettings.kt`
- Modify: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishAction.kt`
- Modify: `java/res/values/strings-uix.xml`（接在 `action_ai_polish_copied` 那行之後）

**Interfaces:**
- Consumes: Task 1 的 `Scene`、`StyleChoice`、`composePrompt`；Task 2 的 `ClefConfig`、`ClefClient`、`ClefClient.TIMEOUT_MS`、`HttpUrlPolishTransport(timeoutMs)`。
- Produces: `AiPolishSettings.ClefAccountId`、`AiPolishSettings.ClefApiToken`、`AiPolishSettings.clefConfig(context): ClefConfig`。

- [ ] **Step 1: 新增字串**

在 `java/res/values/strings-uix.xml` 的 `<string name="action_ai_polish_copied">Copied</string>` 之後加入：

```xml
    <string name="action_ai_polish_setting_clef_account_id">Cloudflare account ID (style detection)</string>
    <string name="action_ai_polish_setting_clef_api_token">Cloudflare API token (style detection)</string>
    <string name="action_ai_polish_style_label">Style</string>
    <string name="action_ai_polish_style_chat">Chat</string>
    <string name="action_ai_polish_style_email">Email</string>
    <string name="action_ai_polish_style_formal">Formal</string>
    <string name="action_ai_polish_style_social">Social</string>
    <string name="action_ai_polish_style_soften">Soften tone</string>
    <string name="action_ai_polish_style_detecting">Detecting style…</string>
    <string name="action_ai_polish_style_failed">Could not detect the style</string>
    <string name="action_ai_polish_style_harsh">⚠ The tone seems harsh; it will be softened</string>
```

- [ ] **Step 2: 設定**

在 `AiPolishSettings.kt` 的 `val Prompt = ...` 之後加：

```kotlin
    val ClefAccountId = SettingsKey(stringPreferencesKey("ai_polish_clef_account_id"), "")
    val ClefApiToken = SettingsKey(stringPreferencesKey("ai_polish_clef_api_token"), "")
```

在 `fun config(...)` 之後加：

```kotlin
    fun clefConfig(context: Context) = ClefConfig(
        accountId = context.getSetting(ClefAccountId),
        apiToken = context.getSetting(ClefApiToken),
    )
```

在 `menu` 的 `settings = listOf(...)` 裡，`textSetting(R.string.action_ai_polish_setting_prompt, Prompt),` 之後加：

```kotlin
            textSetting(R.string.action_ai_polish_setting_clef_account_id, ClefAccountId),
            textSetting(R.string.action_ai_polish_setting_clef_api_token, ClefApiToken),
```

- [ ] **Step 3: 視窗的 import**

在 `AiPolishAction.kt` 的 import 區加入（依字母順序放進現有清單）：

```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
```

- [ ] **Step 4: 判斷狀態與標籤對照**

在 `private enum class EditorSlot { Prompt, Text, None }` 之後加：

```kotlin
private enum class Detection { Off, Running, Failed, Done }

private val SCENE_LABELS = listOf(
    Scene.Chat to R.string.action_ai_polish_style_chat,
    Scene.Email to R.string.action_ai_polish_style_email,
    Scene.Formal to R.string.action_ai_polish_style_formal,
    Scene.Social to R.string.action_ai_polish_style_social,
)
```

- [ ] **Step 5: 建構子與欄位**

把建構子改成：

```kotlin
private class AiPolishWindow(
    private val manager: KeyboardManagerForAction,
    private val client: AiPolishClient,
    private val clef: ClefClient,
) : ActionWindow() {
```

在 `private var pendingSlot: EditorSlot? = null` 之後加：

```kotlin
    val style: MutableState<StyleChoice> = mutableStateOf(StyleChoice.None)
    val detection: MutableState<Detection> = mutableStateOf(Detection.Off)

    /** Whether Clef judged the tone harsh; the warning shows only while soften is still on. */
    val detectedHarsh: MutableState<Boolean> = mutableStateOf(false)

    /** Once the user picks a style by hand, a late Clef answer must not overwrite it. */
    private var styleTouched = false

    private var detectJob: Job? = null

    init {
        detectStyle()
    }

    private fun detectStyle() {
        val config = AiPolishSettings.clefConfig(context)
        if (!config.isConfigured || text.value.isBlank()) return
        detection.value = Detection.Running
        detectJob = manager.getLifecycleScope().launch {
            clef.classify(config, text.value).fold(
                onSuccess = { result ->
                    val choice = StyleChoice.from(result)
                    detectedHarsh.value = choice.soften
                    if (!styleTouched) style.value = choice
                    detection.value = Detection.Done
                },
                onFailure = { detection.value = Detection.Failed },
            )
        }
    }

    private fun pickScene(scene: Scene) {
        styleTouched = true
        style.value = style.value.copy(scene = if (style.value.scene == scene) null else scene)
    }

    private fun toggleSoften() {
        styleTouched = true
        style.value = style.value.copy(soften = !style.value.soften)
    }
```

（`init` 必須放在 `text` 宣告之後，否則 `text` 還沒初始化。放在 `pendingSlot` 之後就符合。）

- [ ] **Step 6: 送出時組 prompt、關閉時取消**

`run()` 裡把：

```kotlin
            val result = client.polish(AiPolishSettings.config(context), prompt.value, text.value)
```

改成：

```kotlin
            val result = client.polish(
                AiPolishSettings.config(context),
                composePrompt(prompt.value, style.value),
                text.value,
            )
```

`close()` 改成：

```kotlin
    override fun close(): CloseResult {
        job?.cancel()
        detectJob?.cancel()
        return CloseResult.Default
    }
```

- [ ] **Step 7: 標籤列 UI**

在 `Bordered` 函式之後加：

```kotlin
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun StyleRow() {
        Label(R.string.action_ai_polish_style_label)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SCENE_LABELS.forEach { (scene, label) ->
                FilterChip(
                    selected = style.value.scene == scene,
                    onClick = { pickScene(scene) },
                    label = { Text(stringResource(label)) },
                )
            }
            FilterChip(
                selected = style.value.soften,
                onClick = { toggleSoften() },
                label = { Text(stringResource(R.string.action_ai_polish_style_soften)) },
            )
        }
        val status = when {
            detection.value == Detection.Running -> R.string.action_ai_polish_style_detecting
            detection.value == Detection.Failed -> R.string.action_ai_polish_style_failed
            detection.value == Detection.Done && detectedHarsh.value && style.value.soften ->
                R.string.action_ai_polish_style_harsh
            else -> null
        }
        status?.let {
            Text(
                stringResource(it),
                style = Typography.Small,
                color = LocalKeyboardScheme.current.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
```

在 `WindowContents` 的 `Column { ... }` 裡，第一個 `Row(verticalAlignment = Alignment.CenterVertically) {`（Prompt 標題列）之前加一行：

```kotlin
            StyleRow()
```

- [ ] **Step 8: 建立視窗時傳入 Clef 客戶端**

`AiPolishAction` 的 `windowImpl` 改成：

```kotlin
    windowImpl = { manager, _ ->
        AiPolishWindow(
            manager,
            AiPolishClient(HttpUrlPolishTransport()),
            ClefClient(HttpUrlPolishTransport(ClefClient.TIMEOUT_MS)),
        )
    },
```

- [ ] **Step 9: 建置與測試**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.*' assembleUnstableDebug`
Expected: BUILD SUCCESSFUL，`AiPolishAction.kt` 沒有新的 Kotlin 警告（`@OptIn` 在 FilterChip 已是穩定 API 時也不會產生警告；若出現「opt-in 無用」之類的警告，移除 `@OptIn` 和 `ExperimentalMaterial3Api` import）。

- [ ] **Step 10: Commit**

```bash
git add java/res/values/strings-uix.xml java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSettings.kt java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishAction.kt
git commit -m "Preselect the AI Polish style with Clef"
```
（訊息結尾加上 Global Constraints 的兩行。）

---

### Task 4: 真機驗證（由主對話用 adb 執行，不派 subagent）

- [ ] **Step 1:** `./gradlew installUnstableDebug`（或 `adb install -r` 產出的 APK），確認 FUTO 鍵盤仍是目前的輸入法。
- [ ] **Step 2:** 在 AI 潤飾設定填入 Cloudflare 帳號 ID 和 API token（token 不寫進任何檔案或 log）。
- [ ] **Step 3:** 在 Chrome 網址列分別輸入下列句子，打開 AI 潤飾視窗，確認預選結果：
  - 「欸你今天晚上要不要一起吃飯 我請客」→ Chat
  - 「王經理您好，附件為本季報表，煩請撥冗審閱，如有問題再請告知。」→ Email
  - 「茲因本公司辦理年度盤點，訂於十月十五日暫停營業一日，特此公告。」→ Formal
  - 「今天天氣超好！帶毛孩去河濱公園散步 #週末日常」→ Social
  - 「你到底有沒有在看訊息？？我已經說了三次了，不要再拖了」→ Soften tone 開啟，並顯示 ⚠ 警告
- [ ] **Step 4:** 把一句的風格手動改成 Formal 後按 Polish，確認結果是正式語氣。
- [ ] **Step 5:** 清空 token，打開視窗：標籤列照樣顯示、沒有預選、沒有狀態文字，Polish 照常可用。測完把 token 填回去。
- [ ] **Step 6:** 開飛航模式打開視窗：顯示「Could not detect the style」（最多約 5 秒），視窗操作正常。關掉飛航模式。
