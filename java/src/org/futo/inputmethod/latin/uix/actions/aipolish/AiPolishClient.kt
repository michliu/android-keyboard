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
