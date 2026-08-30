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
