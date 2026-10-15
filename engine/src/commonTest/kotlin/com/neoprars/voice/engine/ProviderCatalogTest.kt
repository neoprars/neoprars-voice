package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCatalogTest {

    @Test fun defaultSelectionIsOpenAiForBothAndMatchesTheDefaultSetup() {
        val setup = ProviderCatalog.setup(CatalogSelection())
        assertEquals("openai", setup.transcriber.id)
        assertEquals("gpt-4o-mini-transcribe", setup.transcriber.model)
        assertEquals("gpt-6-luna", setup.polisher?.model)
        assertEquals("none", setup.polisher?.options?.get("reasoning_effort"))
        assertEquals(setup, Presets.defaultSetup())
    }

    @Test fun everyDeclaredProviderIsSelectable() {
        assertEquals(listOf("openai", "deepgram", "gemini"), ProviderCatalog.available(Capability.TRANSCRIBE).map { it.id })
        assertEquals(listOf("openai", "anthropic", "gemini", "xai"), ProviderCatalog.available(Capability.POLISH).map { it.id })
        assertTrue(ProviderCatalog.entries.all { it.implemented })
    }

    @Test fun transcriptionProvidersUseTheirOwnProtocol() {
        val deepgram = ProviderCatalog.find(Capability.TRANSCRIBE, "deepgram")!!.config()!!
        assertEquals(Protocol.DEEPGRAM_LISTEN, deepgram.protocol)
        assertEquals(AuthStyle.TOKEN, deepgram.auth)
        assertEquals("nova-3", deepgram.model)
        val gemini = ProviderCatalog.find(Capability.TRANSCRIBE, "gemini")!!.config()!!
        assertEquals(Protocol.GEMINI_INTERACTIONS, gemini.protocol)
        assertEquals(AuthStyle.X_GOOG_API_KEY, gemini.auth)
        assertEquals("gemini-3.5-transcribe", gemini.model)
        // Una sola clave de Google para transcribir y pulir.
        assertEquals(setOf("gemini"), ProviderCatalog.requiredKeys(CatalogSelection(transcriberId = "gemini", polisherId = "gemini")))
    }

    @Test fun onlyOpenAiIsTestedEndToEnd() {
        val tested = ProviderCatalog.entries.filter { it.status == ProviderStatus.TESTED }
        assertEquals(setOf("openai"), tested.map { it.id }.toSet())
        assertEquals(2, tested.size) // transcribe y pule
        val experimental = ProviderCatalog.entries.filter { it.status == ProviderStatus.EXPERIMENTAL }
        assertEquals(ProviderCatalog.entries.size - 2, experimental.size)
        assertEquals(ProviderStatus.EXPERIMENTAL, ProviderCatalog.find(Capability.TRANSCRIBE, "deepgram")!!.status)
        assertEquals(ProviderStatus.EXPERIMENTAL, ProviderCatalog.find(Capability.TRANSCRIBE, "gemini")!!.status)
    }

    @Test fun anEntryWithoutImplementationIsPlannedAndRejected() {
        val planned = CatalogEntry(
            id = "futuro", keyId = "futuro", displayName = "Futuro", capability = Capability.TRANSCRIBE,
            defaultModel = "m", source = "https://example.com", template = null,
        )
        assertEquals(ProviderStatus.PLANNED, planned.status)
        assertFalse(planned.implemented)
        assertNull(planned.config())
    }

    @Test fun everyProviderHasHelpToGetItsKey() {
        for (entry in ProviderCatalog.entries) {
            val help = assertNotNull(entry.keyHelp, "sin ayuda para la clave de ${entry.id}")
            assertEquals(entry.keyId, help.id)
            assertTrue(help.keyPageUrl.startsWith("https://"), "URL de claves no https: ${help.keyPageUrl}")
            assertTrue(help.source.startsWith("https://"), "fuente no https: ${help.source}")
            assertFalse(help.keyPageUrl.contains(" "))
        }
        assertEquals(setOf("openai", "anthropic", "gemini", "xai", "deepgram"), ProviderCatalog.keyHelp.keys)
    }

    @Test fun everyEntryCitesItsSource() {
        assertTrue(ProviderCatalog.entries.all { it.source.startsWith("https://") })
    }

    @Test fun anEmptyModelUsesTheDefaultAndAnotherOneReplacesIt() {
        val custom = ProviderCatalog.setup(CatalogSelection(polisherId = "anthropic", polisherModel = "  claude-sonnet-5-5 "))
        assertEquals("claude-sonnet-5-5", custom.polisher?.model)
        assertEquals(AuthStyle.X_API_KEY, custom.polisher?.auth)

        val default = ProviderCatalog.setup(CatalogSelection(polisherId = "anthropic", polisherModel = ""))
        assertEquals(ClaudePolisher.DEFAULT_MODEL, default.polisher?.model)
    }

    @Test fun noPolisherMeansRulesOnly() {
        assertNull(ProviderCatalog.setup(CatalogSelection(polisherId = null)).polisher)
    }

    @Test fun unknownWrongOrUnimplementedProvidersAreRejected() {
        fun kind(selection: CatalogSelection) = assertFailsWith<EngineException> { ProviderCatalog.setup(selection) }.kind
        assertEquals(EngineException.Kind.NOT_CONFIGURED, kind(CatalogSelection(transcriberId = "nadie")))
        assertEquals(EngineException.Kind.NOT_CONFIGURED, kind(CatalogSelection(transcriberId = "anthropic"))) // solo pule
        assertEquals(EngineException.Kind.NOT_CONFIGURED, kind(CatalogSelection(polisherId = "deepgram")))
    }

    @Test fun requiredKeysAreOnePerCompany() {
        assertEquals(setOf("openai"), ProviderCatalog.requiredKeys(CatalogSelection()))
        assertEquals(setOf("openai", "xai"), ProviderCatalog.requiredKeys(CatalogSelection(polisherId = "xai")))
        assertEquals(setOf("openai"), ProviderCatalog.requiredKeys(CatalogSelection(polisherId = null)))
    }

    @Test fun polishStylesMapToInstructions() {
        assertNull(PolishStyle.NEUTRAL.instructions)
        assertTrue(PolishStyle.FORMAL.instructions!!.contains("formal"))
        assertEquals(PolishStyle.CONCISE, PolishStyle.fromName("CONCISE"))
        assertEquals(PolishStyle.NEUTRAL, PolishStyle.fromName("desconocido"))
        assertEquals(PolishStyle.NEUTRAL, PolishStyle.fromName(null))
    }

    // ---- Pulidores del catálogo, con servidor simulado ----

    private class Captured(var url: String = "", var auth: String? = null, var apiKey: String? = null, var body: String = "")

    private fun mock(reply: String, captured: Captured) = HttpClient(MockEngine { request: HttpRequestData ->
        captured.url = request.url.toString()
        captured.auth = request.headers[HttpHeaders.Authorization]
        captured.apiKey = request.headers["x-api-key"]
        captured.body = (request.body as TextContent).text
        respond(reply, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private val chatReply = """{"choices":[{"message":{"role":"assistant","content":"Hola, ¿qué tal?"}}]}"""

    private fun polisherFor(id: String, http: HttpClient, key: String) = EngineFactory.polisher(
        ProviderCatalog.find(Capability.POLISH, id)!!.config()!!, http, KeyResolver { if (it == id) key else null },
    )

    @Test fun geminiPolishesThroughItsOpenAiCompatibleRoute() = runTest {
        val c = Captured()
        val out = polisherFor("gemini", mock(chatReply, c), "g-key").polish("hola que tal", null)

        assertEquals("Hola, ¿qué tal?", out)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", c.url)
        assertEquals("Bearer g-key", c.auth)
        assertTrue(c.body.contains("\"model\":\"gemini-3.5-flash-lite\""))
        assertFalse(c.body.contains("reasoning_effort"))
    }

    @Test fun grokPolishesThroughChatCompletions() = runTest {
        val c = Captured()
        val out = polisherFor("xai", mock(chatReply, c), "x-key").polish("hola que tal", null, previousText = "Antes")

        assertEquals("Hola, ¿qué tal?", out)
        assertEquals("https://api.x.ai/v1/chat/completions", c.url)
        assertEquals("Bearer x-key", c.auth)
        assertTrue(c.body.contains("\"model\":\"grok-4.20-0309-non-reasoning\""))
        assertTrue(c.body.contains("<anterior>"))
        assertFalse(c.body.contains("reasoning_effort"))
    }

    @Test fun claudePolishesWithoutThinking() = runTest {
        val c = Captured()
        val reply = """{"content":[{"type":"text","text":"Hola, ¿qué tal?"}]}"""
        val out = polisherFor("anthropic", mock(reply, c), "a-key").polish("hola que tal", null)

        assertEquals("Hola, ¿qué tal?", out)
        assertEquals("https://api.anthropic.com/v1/messages", c.url)
        assertEquals("a-key", c.apiKey)
        assertNull(c.auth)
        assertTrue(c.body.contains("\"thinking\":{\"type\":\"disabled\"}"))
        assertTrue(c.body.contains("\"model\":\"claude-haiku-5-5\""))
    }

    @Test fun claudeWithoutTheOptionSendsNoThinkingField() = runTest {
        val c = Captured()
        val reply = """{"content":[{"type":"text","text":"Hola."}]}"""
        ClaudePolisher(mock(reply, c), "a-key").polish("hola", null)
        assertFalse(c.body.contains("thinking"))
    }
}
