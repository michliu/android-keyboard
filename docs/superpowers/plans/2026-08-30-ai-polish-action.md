# AI 潤飾 Action Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 Action「AI 潤飾」：面板三區（Prompt／原文／結果），呼叫 OpenAI 相容 chat completions API 潤飾文字，結果可替換、插入或複製。

**Architecture:** 純 Kotlin 的 `AiPolishClient`（傳輸層介面化，可單測）＋ DataStore 設定（Base URL / API Key / Model / Prompt）＋ `ActionWindow` Compose 面板；`KeyboardManagerForAction` 增加讀取選取／全文與整框替換三個方法，透過 `LatinIME.getBaseInputConnection()` 操作真正的編輯框（面板文字框會覆寫 InputConnection）。

**Tech Stack:** Kotlin、Jetpack Compose、kotlinx-serialization-json 1.7.1、`HttpURLConnection`、JUnit4 JVM 測試（`tests/unit`）。

Spec：`docs/superpowers/specs/2026-08-30-ai-polish-action-design.md`

## Global Constraints

- 分支 `ai-polish`（自 `master` 分出）。只有 `origin`（michliu fork）；本計畫不推送。
- Action id `"ai_polish"`，只能追加在 `Registry.kt` `AllActionsMap` 末尾；預設加入 `DefaultActionSettings` 的 Favorites 末尾。
- 設定 key：`ai_polish_base_url`（預設 `https://api.openai.com/v1`）、`ai_polish_api_key`（空）、`ai_polish_model`（`gpt-4o-mini`）、`ai_polish_prompt`（預設 prompt 見 Task 3）。
- 請求：POST `baseUrl.trimEnd('/') + "/chat/completions"`，body `{"model", "messages":[system=prompt, user=text], "temperature":0.3}`，header `Authorization: Bearer <key>`、`Content-Type: application/json`，逾時 60 秒；回應取 `choices[0].message.content` 並 trim。
- 圖示 `R.drawable.edit_text`；英文字串加在 `java/res/values/strings-uix.xml`。
- `java/AndroidManifest.xml` 新增 `<uses-permission android:name="android.permission.INTERNET"/>`。
- 讀寫真正的編輯框一律用 `latinIME.getBaseInputConnection()`。
- 提交訊息結尾附：
  ```
  Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ
  ```
- 指令：單測 `./gradlew testUnstableDebugUnitTest --tests '<class>'`；編譯 `./gradlew compileUnstableDebugKotlin`；APK `./gradlew assembleUnstableDebug`。

## File Structure

| 檔案 | 責任 |
|---|---|
| `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishClient.kt` | 請求／回應模型、`PolishTransport` 介面、`HttpUrlPolishTransport`、`AiPolishClient` |
| `tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishClientTest.kt` | 客戶端單測（假傳輸） |
| `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSource.kt` | `pickSource` 與 `SourceKind`（純函式） |
| `tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSourceTest.kt` | 預填規則單測 |
| `java/src/org/futo/inputmethod/latin/uix/Action.kt` | `KeyboardManagerForAction` 新增 3 個方法 |
| `java/src/org/futo/inputmethod/latin/uix/UixManager.kt` | 實作上述 3 個方法 |
| `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSettings.kt` | `SettingsKey` 四項、預設 prompt、設定頁 |
| `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishAction.kt` | `AiPolishAction` 與面板 UI |
| `java/res/values/strings-uix.xml` | 字串 |
| `java/AndroidManifest.xml` | INTERNET 權限 |
| `java/src/org/futo/inputmethod/latin/uix/actions/Registry.kt` | 註冊與預設分組 |

---

### Task 1: `AiPolishClient`（TDD）

**Files:**
- Create: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishClient.kt`
- Test: `tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishClientTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class PolishConfig(val baseUrl: String, val apiKey: String, val model: String)
  data class TransportResponse(val status: Int, val body: String)
  fun interface PolishTransport { fun post(url: String, headers: Map<String, String>, body: String): TransportResponse }
  class PolishException(message: String) : Exception(message)
  class AiPolishClient(private val transport: PolishTransport) {
      suspend fun polish(config: PolishConfig, prompt: String, text: String): Result<String>
      companion object { const val NOT_CONFIGURED = "Set the API base URL and API key in AI Polish settings first" }
  }
  class HttpUrlPolishTransport : PolishTransport
  ```

- [ ] **Step 1: 寫失敗的測試**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPolishClientTest {
    private val config = PolishConfig("https://example.com/v1/", "sk-test", "gpt-test")

    private class RecordingTransport(private val response: TransportResponse) : PolishTransport {
        var url: String? = null
        var headers: Map<String, String>? = null
        var body: String? = null
        override fun post(url: String, headers: Map<String, String>, body: String): TransportResponse {
            this.url = url; this.headers = headers; this.body = body
            return response
        }
    }

    @Test
    fun buildsRequestAndParsesContent() = runBlocking {
        val transport = RecordingTransport(TransportResponse(200,
            """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"  Polished text \n"}}]}"""))
        val result = AiPolishClient(transport).polish(config, "Be nice", "helo")

        assertEquals("Polished text", result.getOrThrow())
        assertEquals("https://example.com/v1/chat/completions", transport.url)
        assertEquals("Bearer sk-test", transport.headers!!["Authorization"])
        assertEquals("application/json", transport.headers!!["Content-Type"])
        val body = transport.body!!
        assertTrue(body, body.contains("\"model\":\"gpt-test\""))
        assertTrue(body, body.contains("\"role\":\"system\",\"content\":\"Be nice\""))
        assertTrue(body, body.contains("\"role\":\"user\",\"content\":\"helo\""))
        assertTrue(body, body.contains("\"temperature\":0.3"))
    }

    @Test
    fun reportsHttpErrorWithServerMessage() = runBlocking {
        val transport = RecordingTransport(TransportResponse(401,
            """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""))
        val result = AiPolishClient(transport).polish(config, "p", "t")
        assertEquals("HTTP 401: Incorrect API key provided", result.exceptionOrNull()!!.message)
    }

    @Test
    fun reportsHttpErrorWithRawBodyWhenNotJson() = runBlocking {
        val transport = RecordingTransport(TransportResponse(502, "Bad Gateway"))
        val result = AiPolishClient(transport).polish(config, "p", "t")
        assertEquals("HTTP 502: Bad Gateway", result.exceptionOrNull()!!.message)
    }

    @Test
    fun reportsUnparsableSuccessBody() = runBlocking {
        val transport = RecordingTransport(TransportResponse(200, """{"choices":[]}"""))
        val result = AiPolishClient(transport).polish(config, "p", "t")
        assertEquals(AiPolishClient.UNPARSABLE, result.exceptionOrNull()!!.message)
    }

    @Test
    fun rejectsMissingConfiguration() = runBlocking {
        val transport = RecordingTransport(TransportResponse(200, "{}"))
        val result = AiPolishClient(transport).polish(PolishConfig("", "", "m"), "p", "t")
        assertEquals(AiPolishClient.NOT_CONFIGURED, result.exceptionOrNull()!!.message)
        assertEquals(null, transport.url)
    }

    @Test
    fun wrapsTransportExceptions() = runBlocking {
        val transport = PolishTransport { _, _, _ -> throw java.io.IOException("Unable to resolve host") }
        val result = AiPolishClient(transport).polish(config, "p", "t")
        assertEquals("Unable to resolve host", result.exceptionOrNull()!!.message)
    }
}
```

- [ ] **Step 2: 執行確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.AiPolishClientTest'`
Expected: 編譯失敗 `Unresolved reference: PolishConfig`。

- [ ] **Step 3: 實作**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class PolishConfig(val baseUrl: String, val apiKey: String, val model: String)

data class TransportResponse(val status: Int, val body: String)

/** Minimal HTTP POST abstraction so the client can be unit-tested without a network. */
fun interface PolishTransport {
    /** Performs a POST and returns the status and body; may throw IOException. */
    fun post(url: String, headers: Map<String, String>, body: String): TransportResponse
}

class PolishException(message: String) : Exception(message)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatRequest(val model: String, val messages: List<ChatMessage>, val temperature: Double)

@Serializable
private data class ChatChoice(val message: ChatMessage? = null)

@Serializable
private data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
private data class ApiError(val message: String? = null)

@Serializable
private data class ApiErrorResponse(val error: ApiError? = null)

/** Calls an OpenAI-compatible chat completions endpoint to polish a piece of text. */
class AiPolishClient(private val transport: PolishTransport) {
    companion object {
        const val NOT_CONFIGURED = "Set the API base URL and API key in AI Polish settings first"
        const val UNPARSABLE = "The server response could not be parsed"
        private const val TEMPERATURE = 0.3
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    suspend fun polish(config: PolishConfig, prompt: String, text: String): Result<String> {
        if (config.baseUrl.isBlank() || config.apiKey.isBlank()) {
            return Result.failure(PolishException(NOT_CONFIGURED))
        }
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val headers = mapOf(
            "Authorization" to "Bearer ${config.apiKey}",
            "Content-Type" to "application/json",
        )
        val body = json.encodeToString(ChatRequest(
            model = config.model,
            messages = listOf(ChatMessage("system", prompt), ChatMessage("user", text)),
            temperature = TEMPERATURE,
        ))

        val response = try {
            withContext(Dispatchers.IO) { transport.post(url, headers, body) }
        } catch (e: IOException) {
            return Result.failure(PolishException(e.message ?: e.toString()))
        }

        if (response.status !in 200..299) {
            val serverMessage = runCatching {
                json.decodeFromString<ApiErrorResponse>(response.body).error?.message
            }.getOrNull()
            val detail = serverMessage ?: response.body.take(200)
            return Result.failure(PolishException("HTTP ${response.status}: $detail"))
        }

        val content = runCatching {
            json.decodeFromString<ChatResponse>(response.body).choices.firstOrNull()?.message?.content
        }.getOrNull()
        return if (content == null) Result.failure(PolishException(UNPARSABLE))
        else Result.success(content.trim())
    }
}

/** Real transport backed by HttpURLConnection. */
class HttpUrlPolishTransport : PolishTransport {
    override fun post(url: String, headers: Map<String, String>, body: String): TransportResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 60_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return TransportResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
```

- [ ] **Step 4: 執行確認通過**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.AiPolishClientTest'`
Expected: 6 tests passed。若 `runBlocking` 無法解析，`kotlinx-coroutines-core` 已是主程式相依；確認 import `kotlinx.coroutines.runBlocking`。

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishClient.kt tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishClientTest.kt
git commit -m "Add AiPolishClient for OpenAI-compatible chat completions

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 2: 預填規則 + `KeyboardManagerForAction` 讀寫方法

**Files:**
- Create: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSource.kt`
- Test: `tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSourceTest.kt`
- Modify: `java/src/org/futo/inputmethod/latin/uix/Action.kt`（`interface KeyboardManagerForAction`，在 `fun getCurrentIME(): IMEInterface` 之前）
- Modify: `java/src/org/futo/inputmethod/latin/uix/UixManager.kt`（`class UixActionKeyboardManager`，在 `override fun getCurrentIME()` 之前）

**Interfaces:**
- Produces:
  ```kotlin
  enum class SourceKind { Selection, WholeField, None }
  data class Source(val text: String, val kind: SourceKind)
  fun pickSource(selected: String?, all: String?): Source
  // KeyboardManagerForAction:
  fun getSelectedText(): String?
  fun getAllText(): String?
  fun replaceAllText(text: String)
  ```

- [ ] **Step 1: 寫失敗的測試**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPolishSourceTest {
    @Test
    fun selectionWins() {
        assertEquals(Source("sel", SourceKind.Selection), pickSource("sel", "whole text"))
    }

    @Test
    fun wholeFieldWhenNoSelection() {
        assertEquals(Source("whole text", SourceKind.WholeField), pickSource(null, "whole text"))
        assertEquals(Source("whole text", SourceKind.WholeField), pickSource("", "whole text"))
    }

    @Test
    fun noneWhenBothEmpty() {
        assertEquals(Source("", SourceKind.None), pickSource(null, null))
        assertEquals(Source("", SourceKind.None), pickSource("  ", ""))
    }
}
```

- [ ] **Step 2: 執行確認失敗**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.AiPolishSourceTest'`
Expected: 編譯失敗 `Unresolved reference: pickSource`。

- [ ] **Step 3: 實作 `AiPolishSource.kt`**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

/** Where the text shown in the AI Polish window came from; decides what "Replace" replaces. */
enum class SourceKind { Selection, WholeField, None }

data class Source(val text: String, val kind: SourceKind)

/** Prefers the selection, then the whole field; blank strings count as absent. */
fun pickSource(selected: String?, all: String?): Source = when {
    !selected.isNullOrBlank() -> Source(selected, SourceKind.Selection)
    !all.isNullOrBlank() -> Source(all, SourceKind.WholeField)
    else -> Source("", SourceKind.None)
}
```

- [ ] **Step 4: 在 `KeyboardManagerForAction` 介面加入**（`Action.kt`，放在 `fun copyToClipboard(...)` 區塊之後、`getCurrentIME` 之前）

```kotlin
    /** Selected text of the real editor (not an overriding action text field), or null. */
    fun getSelectedText(): String?
    /** Entire text of the real editor, or null if unavailable. */
    fun getAllText(): String?
    /** Atomically replaces the entire text of the real editor. */
    fun replaceAllText(text: String)
```

- [ ] **Step 5: 在 `UixActionKeyboardManager` 實作**（`UixManager.kt`，`override fun getCurrentIME()` 之前）。檔案頂部若無，加入 `import android.view.inputmethod.ExtractedTextRequest`。

```kotlin
    override fun getSelectedText(): String? =
        latinIME.getBaseInputConnection()?.getSelectedText(0)?.toString()

    override fun getAllText(): String? =
        latinIME.getBaseInputConnection()?.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()

    override fun replaceAllText(text: String) {
        val ic = latinIME.getBaseInputConnection() ?: return
        val length = getAllText()?.length ?: 0
        ic.beginBatchEdit()
        ic.setSelection(0, length)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }
```

- [ ] **Step 6: 執行測試與編譯**

Run: `./gradlew testUnstableDebugUnitTest --tests 'org.futo.inputmethod.latin.uix.actions.aipolish.AiPolishSourceTest' compileUnstableDebugKotlin`
Expected: 3 tests passed、BUILD SUCCESSFUL。若有其他 `KeyboardManagerForAction` 的實作類別（`grep -rn ": KeyboardManagerForAction" java/src tests/src`）也要補上這三個方法（測試用假物件可回傳 null／不做事）。

- [ ] **Step 7: Commit**

```bash
git add java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSource.kt tests/unit/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSourceTest.kt java/src/org/futo/inputmethod/latin/uix/Action.kt java/src/org/futo/inputmethod/latin/uix/UixManager.kt
git commit -m "Add editor text accessors for actions and AI polish source rules

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 3: 設定、字串、權限

**Files:**
- Create: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSettings.kt`
- Modify: `java/res/values/strings-uix.xml`（`</resources>` 之前）
- Modify: `java/AndroidManifest.xml`（`uses-permission` 區塊）

**Interfaces:**
- Produces:
  ```kotlin
  object AiPolishSettings {
      const val DEFAULT_PROMPT: String
      val BaseUrl: SettingsKey<String>; val ApiKey: SettingsKey<String>; val Model: SettingsKey<String>; val Prompt: SettingsKey<String>
      val menu: UserSettingsMenu
      fun config(context: Context): PolishConfig
  }
  ```
  字串資源：`action_ai_polish_title`、`action_ai_polish_settings_title`、`action_ai_polish_setting_base_url`、`action_ai_polish_setting_api_key`、`action_ai_polish_setting_model`、`action_ai_polish_setting_prompt`、`action_ai_polish_prompt_label`、`action_ai_polish_source_label`、`action_ai_polish_result_label`、`action_ai_polish_result_placeholder`、`action_ai_polish_run`、`action_ai_polish_replace`、`action_ai_polish_insert`、`action_ai_polish_copy`、`action_ai_polish_reset`、`action_ai_polish_copied`。

- [ ] **Step 1: 字串**（加在 `strings-uix.xml` 的 `</resources>` 之前）

```xml
    <!-- AI Polish action -->
    <string name="action_ai_polish_title">AI Polish</string>
    <string name="action_ai_polish_settings_title">AI Polish Settings</string>
    <string name="action_ai_polish_setting_base_url">API base URL</string>
    <string name="action_ai_polish_setting_api_key">API key</string>
    <string name="action_ai_polish_setting_model">Model</string>
    <string name="action_ai_polish_setting_prompt">Default prompt</string>
    <string name="action_ai_polish_prompt_label">Prompt</string>
    <string name="action_ai_polish_source_label">Text</string>
    <string name="action_ai_polish_result_label">Result</string>
    <string name="action_ai_polish_result_placeholder">The polished text will appear here</string>
    <string name="action_ai_polish_run">Polish</string>
    <string name="action_ai_polish_replace">Replace</string>
    <string name="action_ai_polish_insert">Insert</string>
    <string name="action_ai_polish_copy">Copy</string>
    <string name="action_ai_polish_reset">Reset</string>
    <string name="action_ai_polish_copied">Copied</string>
```

- [ ] **Step 2: 權限**（`java/AndroidManifest.xml`，在 `<uses-permission android:name="android.permission.VIBRATE"/>` 之後）

```xml
    <uses-permission android:name="android.permission.INTERNET"/>
```

- [ ] **Step 3: `AiPolishSettings.kt`**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.stringPreferencesKey
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.SettingsKey
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.settings.SettingTextField
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu

object AiPolishSettings {
    const val DEFAULT_PROMPT =
        "你是文字潤飾助手。請在不改變原意的前提下，修正錯字與語法、讓語句更通順自然，保留原本的語言與語氣，只輸出潤飾後的文字，不要加任何說明。"

    val BaseUrl = SettingsKey(stringPreferencesKey("ai_polish_base_url"), "https://api.openai.com/v1")
    val ApiKey = SettingsKey(stringPreferencesKey("ai_polish_api_key"), "")
    val Model = SettingsKey(stringPreferencesKey("ai_polish_model"), "gpt-4o-mini")
    val Prompt = SettingsKey(stringPreferencesKey("ai_polish_prompt"), DEFAULT_PROMPT)

    fun config(context: Context) = PolishConfig(
        baseUrl = context.getSetting(BaseUrl),
        apiKey = context.getSetting(ApiKey),
        model = context.getSetting(Model),
    )

    private fun textSetting(title: Int, key: SettingsKey<String>) = UserSetting(name = title) {
        SettingTextField(stringResource(title), key.default, key)
    }

    val menu = UserSettingsMenu(
        title = R.string.action_ai_polish_settings_title,
        navPath = "actions/ai_polish",
        registerNavPath = true,
        settings = listOf(
            textSetting(R.string.action_ai_polish_setting_base_url, BaseUrl),
            textSetting(R.string.action_ai_polish_setting_api_key, ApiKey),
            textSetting(R.string.action_ai_polish_setting_model, Model),
            textSetting(R.string.action_ai_polish_setting_prompt, Prompt),
        )
    )
}
```

若 `UserSetting(name = …) { … }` 的尾隨 lambda 無法對應 `component` 參數，改寫成 `UserSetting(name = title, component = { … })`。

- [ ] **Step 4: 編譯**

Run: `./gradlew compileUnstableDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: Commit**

```bash
git add java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishSettings.kt java/res/values/strings-uix.xml java/AndroidManifest.xml
git commit -m "Add AI polish settings, strings and internet permission

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 4: 面板 UI 與註冊

> 實作與此草稿的差異：同一時刻只掛載一個 `ActionTextEditor`（`EditorSlot` + `afterUnOverride` 切換）；密碼欄位不預填。以 spec 為準。

**Files:**
- Create: `java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishAction.kt`
- Modify: `java/src/org/futo/inputmethod/latin/uix/actions/Registry.kt`（`AllActionsMap` 末尾、`DefaultActionSettings` Favorites 末尾）

**Interfaces:**
- Consumes: Task 1 `AiPolishClient`/`HttpUrlPolishTransport`/`PolishException`；Task 2 `pickSource`/`SourceKind`/`manager.getSelectedText()`/`getAllText()`/`replaceAllText()`；Task 3 `AiPolishSettings` 與字串。
- Produces: `val AiPolishAction: Action`。

- [ ] **Step 1: `AiPolishAction.kt`**

```kotlin
package org.futo.inputmethod.latin.uix.actions.aipolish

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionTextEditor
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.theme.Typography

private sealed class PolishState {
    object Idle : PolishState()
    object Loading : PolishState()
    data class Success(val text: String) : PolishState()
    data class Error(val message: String) : PolishState()
}

private class AiPolishWindow(
    private val manager: KeyboardManagerForAction,
    private val client: AiPolishClient,
) : ActionWindow() {
    private val context: Context = manager.getContext()
    private val source = pickSource(manager.getSelectedText(), manager.getAllText())

    val prompt: MutableState<String> = mutableStateOf(context.getSetting(AiPolishSettings.Prompt))
    val text: MutableState<String> = mutableStateOf(source.text)
    val state: MutableState<PolishState> = mutableStateOf(PolishState.Idle)
    private var job: Job? = null

    @Composable
    override fun windowName(): String = stringResource(R.string.action_ai_polish_title)

    private fun run() {
        job?.cancel()
        state.value = PolishState.Loading
        job = manager.getLifecycleScope().launch {
            val result = client.polish(AiPolishSettings.config(context), prompt.value, text.value)
            state.value = result.fold(
                onSuccess = { PolishState.Success(it) },
                onFailure = { PolishState.Error(it.message ?: it.toString()) },
            )
        }
    }

    private fun resultOrNull(): String? = (state.value as? PolishState.Success)?.text

    private fun replace() {
        val result = resultOrNull() ?: return
        when (source.kind) {
            SourceKind.WholeField -> manager.replaceAllText(result)
            SourceKind.Selection, SourceKind.None -> manager.typeText(result)
        }
        manager.closeActionWindow()
    }

    private fun insert() {
        val result = resultOrNull() ?: return
        manager.typeText(result)
        manager.closeActionWindow()
    }

    private fun copy() {
        val result = resultOrNull() ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Polish", result))
        Toast.makeText(context, R.string.action_ai_polish_copied, Toast.LENGTH_SHORT).show()
    }

    override fun close(): CloseResult {
        job?.cancel()
        return CloseResult.Default
    }

    @Composable
    private fun Label(textRes: Int) {
        Text(stringResource(textRes), style = Typography.Small, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        val scheme = LocalKeyboardScheme.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(R.string.action_ai_polish_prompt_label)
                TextButton(onClick = { prompt.value = context.getSetting(AiPolishSettings.Prompt) }) {
                    Text(stringResource(R.string.action_ai_polish_reset))
                }
            }
            ActionTextEditor(
                text = prompt,
                multiline = true,
                autofocus = false,
                modifier = Modifier.fillMaxWidth().height(64.dp),
            )

            Label(R.string.action_ai_polish_source_label)
            ActionTextEditor(
                text = text,
                multiline = true,
                autofocus = false,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )

            Label(R.string.action_ai_polish_result_label)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(4.dp),
                contentAlignment = Alignment.TopStart
            ) {
                when (val s = state.value) {
                    PolishState.Idle -> Text(
                        stringResource(R.string.action_ai_polish_result_placeholder),
                        color = scheme.onSurfaceVariant
                    )
                    PolishState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    is PolishState.Success -> Text(
                        s.text,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    )
                    is PolishState.Error -> Text(s.message, color = scheme.error)
                }
            }

            val hasResult = state.value is PolishState.Success
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = { run() },
                    enabled = text.value.isNotBlank() && state.value !is PolishState.Loading,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_ai_polish_run)) }
                Button(onClick = { replace() }, enabled = hasResult, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_ai_polish_replace))
                }
                Button(onClick = { insert() }, enabled = hasResult, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_ai_polish_insert))
                }
                Button(onClick = { copy() }, enabled = hasResult, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_ai_polish_copy))
                }
            }
        }
    }
}

val AiPolishAction = Action(
    icon = R.drawable.edit_text,
    name = R.string.action_ai_polish_title,
    canShowKeyboard = true,
    simplePressImpl = null,
    windowImpl = { manager, _ -> AiPolishWindow(manager, AiPolishClient(HttpUrlPolishTransport())) },
    settingsMenu = AiPolishSettings.menu,
)
```

實作備註：`LocalKeyboardScheme.current` 的欄位名稱以 `java/src/org/futo/inputmethod/latin/uix/ColorScheme.kt` 為準（若無 `onSurfaceVariant`／`error`，改用其中存在的次要文字色與錯誤色，並在報告說明）。`Typography.Small` 來自 `org.futo.inputmethod.latin.uix.theme.Typography`（`MemDebugAction.kt` 有同樣用法）。

- [ ] **Step 2: 註冊**（`Registry.kt`）

`AllActionsMap` 末尾 `"font_typer" to FontTyperAction,` 之後加入：
```kotlin
    "ai_polish" to AiPolishAction,
```
並加入 import `org.futo.inputmethod.latin.uix.actions.aipolish.AiPolishAction`。

`DefaultActionSettings` 的 Favorites 列表末尾（`KeyboardModeAction` 之後）加入 `AiPolishAction`：
```kotlin
    ActionCategory.Favorites to listOfNotNull(if(IsDebug) MemoryDebugAction else null, SwitchLanguageAction, UndoAction, RedoAction, TextEditAction, ClipboardHistoryAction, ThemeAction, KeyboardModeAction, AiPolishAction),
```

- [ ] **Step 3: 編譯與全部單測**

Run: `./gradlew compileUnstableDebugKotlin testUnstableDebugUnitTest`
Expected: BUILD SUCCESSFUL，所有單測通過。

- [ ] **Step 4: Commit**

```bash
git add java/src/org/futo/inputmethod/latin/uix/actions/aipolish/AiPolishAction.kt java/src/org/futo/inputmethod/latin/uix/actions/Registry.kt
git commit -m "Add the AI Polish action window

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Bf6fw8NunnbSZZsKThHxbZ"
```

---

### Task 5: 真機驗證

前置：裝置已連接且解鎖；**每次 `input tap` 前先截圖確認畫面**；需要一個可用的 OpenAI 相容 API Key（由使用者提供，測完從裝置設定移除）。

- [ ] **Step 1**：`./gradlew assembleUnstableDebug && adb install -r build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk`
- [ ] **Step 2**：`adb shell am start -n org.futo.inputmethod.latin.unstable/org.futo.inputmethod.latin.uix.settings.SettingsActivity --es navDest actions/ai_polish` 填入 Base URL / API Key / Model。
- [ ] **Step 3**：設定頁「Try typing here…」輸入一句含錯字的中文（如「我今天很開心因為天氣好很想出去玩玩」），開啟 Actions 的「AI Polish」。

| # | 操作 | 預期 |
|---|---|---|
| 1 | 開啟面板 | Prompt 為預設 prompt；原文框預填整個輸入框文字；結果為占位文字 |
| 2 | 按「Polish」 | 顯示進度圈，數秒後結果框顯示潤飾文字 |
| 3 | 按「Replace」 | 輸入框全文被結果取代，面板關閉 |
| 4 | 先在輸入框選取部分文字再開面板 → Polish → Replace | 只有選取區被取代 |
| 5 | 「Insert」 | 結果插入游標處 |
| 6 | 「Copy」 | Toast「Copied」，貼上得到結果 |
| 7 | 點 Prompt 框修改、按「Reset」 | 可用鍵盤編輯；Reset 恢復設定值 |
| 8 | 把 API Key 改錯再 Polish | 結果區顯示 `HTTP 401: …` |
| 9 | 關閉網路再 Polish | 顯示例外訊息（如 Unable to resolve host） |
| 10 | 關閉面板時請求進行中 | 無崩潰 |

- [ ] **Step 4**：測完在設定頁清空 API Key；若有修正則提交（附 trailer）。
