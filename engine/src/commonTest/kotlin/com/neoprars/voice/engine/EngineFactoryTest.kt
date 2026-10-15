package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineFactoryTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(body: String, onRequest: (HttpRequestData) -> Unit = {}) =
        HttpClient(MockEngine { request ->
            onRequest(request)
            respond(content = body, status = HttpStatusCode.OK, headers = jsonHeaders)
        })

    private val allKeys = KeyResolver { "key-$it" }

    // ---- Configuración serializable (sin secretos) ----

    @Test fun setupSurvivesJsonRoundTrip() {
        val original = Presets.defaultSetup()
        assertEquals(original, EngineSetup.fromJson(original.toJson()))
    }

    @Test fun setupJsonContainsNoKeys() {
        val json = Presets.defaultSetup().toJson()
        assertTrue(!json.contains("key-"))
        assertTrue(json.contains("gpt-6-luna"))
        assertTrue(json.contains("gpt-4o-mini-transcribe"))
    }

    @Test fun setupIgnoresUnknownFieldsFromNewerServers() {
        val json = """{"transcriber":{"id":"x","protocol":"OPENAI_AUDIO_TRANSCRIPTIONS","baseUrl":"https://a.b","model":"m","futuro":1},"nuevo":true}"""
        val setup = EngineSetup.fromJson(json)
        assertEquals("x", setup.transcriber.id)
        assertNull(setup.polisher)
    }

    // ---- Fábrica ----

    @Test fun factoryPicksImplementationByProtocol() {
        val c = client("{}")
        assertIs<OpenAiTranscriber>(EngineFactory.transcriber(Presets.openAiTranscribe(), c, allKeys))
        assertIs<ClaudePolisher>(EngineFactory.polisher(Presets.claudePolish(), c, allKeys))
        val chat = ProviderConfig("otro", Protocol.OPENAI_CHAT_COMPLETIONS, "https://x.y", "modelo")
        assertIs<OpenAiChatPolisher>(EngineFactory.polisher(chat, c, allKeys))
    }

    @Test fun defaultSetupPolishesWithTheSameProviderWithoutReasoning() = runTest {
        val setup = Presets.defaultSetup()
        assertEquals(setup.transcriber.id, setup.polisher!!.id) // una sola clave
        var captured: HttpRequestData? = null
        val polisher = EngineFactory.polisher(
            setup.polisher!!,
            client("""{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""") { captured = it },
            allKeys,
        )
        assertIs<OpenAiChatPolisher>(polisher)
        polisher.polish("x", null)
        assertEquals("api.openai.com", captured!!.url.host)
        assertTrue((captured!!.body as TextContent).text.contains("\"reasoning_effort\":\"none\""))
    }

    @Test fun factoryRejectsWrongProtocolForRole() {
        val c = client("{}")
        val e = assertFailsWith<EngineException> {
            EngineFactory.transcriber(Presets.claudePolish(), c, allKeys)
        }
        assertEquals(EngineException.Kind.NOT_CONFIGURED, e.kind)
        val e2 = assertFailsWith<EngineException> {
            EngineFactory.polisher(Presets.openAiTranscribe(), c, allKeys)
        }
        assertEquals(EngineException.Kind.NOT_CONFIGURED, e2.kind)
    }

    @Test fun missingTranscriberKeyIsNotConfigured() {
        val e = assertFailsWith<EngineException> {
            EngineFactory.create(Presets.defaultSetup(), client("{}"), KeyResolver { null })
        }
        assertEquals(EngineException.Kind.NOT_CONFIGURED, e.kind)
    }

    @Test fun missingPolisherKeyJustDisablesPolish() = runTest {
        val onlyOpenAi = KeyResolver { if (it == "openai") "sk-x" else null }
        val engine = EngineFactory.create(
            Presets.defaultSetup().copy(polisher = Presets.claudePolish()),
            client("""{"text":"eh, hola que que tal"}"""),
            onlyOpenAi,
            DictationConfig(minWordsToPolish = 0),
        )
        val pcm = Pcm16.fromShorts(ShortArray(16_000) { if (it % 2 == 0) 8000.toShort() else (-8000).toShort() })
        val r = engine.dictate(pcm)
        assertEquals("Hola que tal", r.text)
        assertTrue(!r.polished)
    }

    // ---- Migrar de IA = cambiar configuración ----

    @Test fun swapToOpenAiCompatiblePolisherOnAnotherHost() = runTest {
        var captured: HttpRequestData? = null
        val cheap = ProviderConfig(
            id = "barato",
            protocol = Protocol.OPENAI_CHAT_COMPLETIONS,
            baseUrl = "https://api.barato.example",
            model = "modelo-chico",
        )
        val polisher = EngineFactory.polisher(
            cheap,
            client("""{"choices":[{"message":{"role":"assistant","content":"  Hola, ¿qué tal?  "}}]}""") { captured = it },
            allKeys,
        )

        val out = polisher.polish("hola que tal", "tuteá")

        assertEquals("Hola, ¿qué tal?", out)
        val req = captured!!
        assertEquals("api.barato.example", req.url.host)
        assertEquals("/v1/chat/completions", req.url.encodedPath)
        assertEquals("Bearer key-barato", req.headers[HttpHeaders.Authorization])
        val sent = (req.body as TextContent).text
        assertTrue(sent.contains("modelo-chico"))
        assertTrue(sent.contains("<dictado>"))
        assertTrue(sent.contains("tuteá"))
    }

    @Test fun gatewayConfigNeedsNoCodeChanges() = runTest {
        // Versión comercial: mismo protocolo, otro host, token de sesión y cabecera extra.
        var captured: HttpRequestData? = null
        val gateway = ProviderConfig(
            id = "neoprars",
            protocol = Protocol.OPENAI_AUDIO_TRANSCRIPTIONS,
            baseUrl = "https://api.neoprars.example",
            model = "stt-default",
            headers = mapOf("X-Client-Version" to "1.0"),
        )
        val t = EngineFactory.transcriber(gateway, client("""{"text":"hola"}""") { captured = it }, KeyResolver { "session-token" })

        assertEquals("hola", t.transcribe(EncodedAudio.wav(ByteArray(10)), "es", null))
        val req = captured!!
        assertEquals("api.neoprars.example", req.url.host)
        assertEquals("Bearer session-token", req.headers[HttpHeaders.Authorization])
        assertEquals("1.0", req.headers["X-Client-Version"])
    }

    @Test fun claudeKeepsXApiKeyAuth() = runTest {
        var captured: HttpRequestData? = null
        val p = EngineFactory.polisher(
            Presets.claudePolish(),
            client("""{"content":[{"type":"text","text":"ok"}]}""") { captured = it },
            allKeys,
        )
        p.polish("x", null)
        assertEquals("key-anthropic", captured!!.headers["x-api-key"])
        assertNull(captured!!.headers[HttpHeaders.Authorization])
    }
}
