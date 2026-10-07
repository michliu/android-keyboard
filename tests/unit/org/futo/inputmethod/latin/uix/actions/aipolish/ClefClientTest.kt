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
