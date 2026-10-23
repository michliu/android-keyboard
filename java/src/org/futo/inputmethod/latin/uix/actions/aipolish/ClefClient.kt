package org.futo.inputmethod.latin.uix.actions.aipolish

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
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
