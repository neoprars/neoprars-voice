package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProvidersTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(status: HttpStatusCode = HttpStatusCode.OK, body: String, onRequest: suspend (HttpRequestData) -> Unit = {}) =
        HttpClient(MockEngine { request ->
            onRequest(request)
            respond(content = body, status = status, headers = jsonHeaders)
        })

    // ---- OpenAI ----

    @Test fun transcriberSendsExpectedRequestAndParsesText() = runTest {
        var captured: HttpRequestData? = null
        var body = ""
        // El cuerpo se lee mientras la petición está viva: con medición de subida ya no se puede leer después.
        val http = client(body = """{"text":"  hola mundo  ","usage":{}}""") {
            captured = it
            body = it.body.toByteArray().decodeToString()
        }
        val t = OpenAiTranscriber(http, "sk-test")

        val text = t.transcribe(EncodedAudio.wav(ByteArray(100)), "es", "Neoprars Voice, Kotlin")

        assertEquals("hola mundo", text)
        val req = captured!!
        assertEquals("/v1/audio/transcriptions", req.url.encodedPath)
        assertEquals("Bearer sk-test", req.headers[HttpHeaders.Authorization])
        assertTrue((t.lastUploadMs ?: -1) >= 0)
        assertTrue(body.contains("gpt-4o-mini-transcribe"))
        assertTrue(body.contains("name=language"))
        assertTrue(body.contains("name=prompt"))
        assertTrue(body.contains("audio.wav"))
    }

    @Test fun transcriberMapsAuthError() = runTest {
        val t = OpenAiTranscriber(client(HttpStatusCode.Unauthorized, """{"error":{"message":"bad key sk-abc"}}"""), "sk-abc")
        val e = assertFailsWith<EngineException> { t.transcribe(EncodedAudio.wav(ByteArray(10)), null, null) }
        assertEquals(EngineException.Kind.AUTH, e.kind)
        assertTrue(!e.message!!.contains("sk-abc"))
    }

    @Test fun transcriberMapsRateLimitAndServerErrors() = runTest {
        val rate = OpenAiTranscriber(client(HttpStatusCode.TooManyRequests, "{}"), "k")
        assertEquals(EngineException.Kind.RATE_LIMIT, assertFailsWith<EngineException> { rate.transcribe(EncodedAudio.wav(ByteArray(10)), null, null) }.kind)
        val server = OpenAiTranscriber(client(HttpStatusCode.BadGateway, "{}"), "k")
        assertEquals(EngineException.Kind.SERVER, assertFailsWith<EngineException> { server.transcribe(EncodedAudio.wav(ByteArray(10)), null, null) }.kind)
    }

    @Test fun transcriberRejectsGarbageBody() = runTest {
        val t = OpenAiTranscriber(client(body = "no es json"), "k")
        assertEquals(EngineException.Kind.BAD_RESPONSE, assertFailsWith<EngineException> { t.transcribe(EncodedAudio.wav(ByteArray(10)), null, null) }.kind)
    }

    @Test fun transcriberSendsTheAudioFormatItReceives() = runTest {
        var body = ""
        val http = client(body = """{"text":"hola"}""") { body = it.body.toByteArray().decodeToString() }
        OpenAiTranscriber(http, "k").transcribe(EncodedAudio(ByteArray(20), "audio/mp4", "audio.m4a"), null, null)
        assertTrue(body.contains("audio.m4a"))
        assertTrue(body.contains("audio/mp4"))
        assertTrue(!body.contains("audio.wav"))
    }

    // ---- Pulido con chat completions ----

    @Test fun chatPolisherSendsReasoningEffortOnlyWhenConfigured() = runTest {
        val reply = """{"choices":[{"message":{"role":"assistant","content":"Hola."}}]}"""
        var sent = ""
        val http = client(body = reply) { sent = (it.body as TextContent).text }

        assertEquals("Hola.", OpenAiChatPolisher(http, "k", "modelo", reasoningEffort = "none").polish("hola", null))
        assertTrue(sent.contains("\"reasoning_effort\":\"none\""))

        OpenAiChatPolisher(http, "k", "modelo").polish("hola", null)
        assertTrue(!sent.contains("reasoning_effort"))
    }

    // ---- Precalentado ----

    @Test fun warmUpSendsNoCredentialsNorBody() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = client(body = "") { requests += it }
        OpenAiTranscriber(http, "sk-test").warmUp()
        ClaudePolisher(http, "ak-test").warmUp()
        OpenAiChatPolisher(http, "sk-test", "modelo").warmUp()

        assertEquals(listOf("api.openai.com", "api.anthropic.com", "api.openai.com"), requests.map { it.url.host })
        for (req in requests) {
            assertEquals("HEAD", req.method.value)
            assertEquals(null, req.headers[HttpHeaders.Authorization])
            assertEquals(null, req.headers["x-api-key"])
            assertEquals(0, req.body.toByteArray().size)
        }
    }

    @Test fun warmUpIgnoresErrors() = runTest {
        val failing = HttpClient(MockEngine { throw RuntimeException("sin red") })
        OpenAiTranscriber(failing, "k").warmUp()
        ClaudePolisher(failing, "k").warmUp()
        OpenAiTranscriber(client(HttpStatusCode.NotFound, "no"), "k").warmUp()
    }

    // ---- Claude ----

    @Test fun polisherSendsExpectedRequestAndParsesText() = runTest {
        var captured: HttpRequestData? = null
        val p = ClaudePolisher(
            client(body = """{"content":[{"type":"text","text":"Nos vemos a las 5."}],"stop_reason":"end_turn"}""") { captured = it },
            "ak-test",
        )

        val out = p.polish("nos vemos a las 3, no, mejor a las 5", "tuteá")

        assertEquals("Nos vemos a las 5.", out)
        val req = captured!!
        assertEquals("/v1/messages", req.url.encodedPath)
        assertEquals("ak-test", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        val sent = (req.body as TextContent).text
        assertTrue(sent.contains("claude-haiku-5-5"))
        assertTrue(sent.contains("<dictado>"))
        assertTrue(sent.contains("tuteá"))
    }

    @Test fun polisherMapsAuthError() = runTest {
        val p = ClaudePolisher(client(HttpStatusCode.Unauthorized, "{}"), "k")
        assertEquals(EngineException.Kind.AUTH, assertFailsWith<EngineException> { p.polish("x", null) }.kind)
    }
}
