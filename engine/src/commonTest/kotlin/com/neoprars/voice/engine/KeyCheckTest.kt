package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyCheckTest {

    private val key = "sk-secreta-123456"

    /**
     * Con tiempo real: el servidor simulado responde desde otro hilo y, con el reloj virtual de runTest,
     * el límite de 10 s vencería antes de que llegue la respuesta.
     */
    private suspend fun check(config: ProviderConfig, apiKey: String, client: HttpClient, timeoutMs: Long = KeyCheck.DEFAULT_TIMEOUT_MS) =
        withContext(Dispatchers.Default) { KeyCheck.check(config, apiKey, client, timeoutMs) }

    private class Seen(var request: HttpRequestData? = null, var calls: Int = 0)

    /** Servidor simulado que responde [status] con un cuerpo que repite la clave, como hacen algunos proveedores. */
    private fun server(status: Int, seen: Seen = Seen(), body: String = """{"error":{"message":"Incorrect API key provided: $key"}}""") =
        HttpClient(MockEngine { request ->
            seen.request = request
            seen.calls++
            respond(body, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
        })

    private fun configFor(id: String) =
        ProviderCatalog.available(Capability.POLISH).first { it.id == id }.config()!!

    private val openAi = ProviderCatalog.find(Capability.TRANSCRIBE, "openai")!!.config()!!

    @Test fun aValidKeyListsModelsWithAGetAndNoBody() = runTest {
        val seen = Seen()
        assertNull(check(openAi, key, server(200, seen, """{"data":[]}""")))
        val r = seen.request!!
        assertEquals(HttpMethod.Get, r.method)
        assertEquals("https://api.openai.com/v1/models", r.url.toString())
        assertEquals("Bearer $key", r.headers[HttpHeaders.Authorization])
        assertEquals(0L, r.body.contentLength ?: 0L)
        assertEquals(1, seen.calls)
    }

    @Test fun eachProviderUsesItsOwnRouteAndCredential() = runTest {
        suspend fun requestFor(config: ProviderConfig): HttpRequestData {
            val seen = Seen()
            assertNull(check(config, key, server(200, seen, "{}")))
            return seen.request!!
        }

        val anthropic = requestFor(configFor("anthropic"))
        assertEquals("https://api.anthropic.com/v1/models?limit=1", anthropic.url.toString())
        assertEquals(key, anthropic.headers["x-api-key"])
        assertEquals("2023-06-01", anthropic.headers["anthropic-version"])
        assertNull(anthropic.headers[HttpHeaders.Authorization])

        val gemini = requestFor(configFor("gemini"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models", gemini.url.toString())
        assertEquals("Bearer $key", gemini.headers[HttpHeaders.Authorization])

        val deepgram = requestFor(ProviderCatalog.find(Capability.TRANSCRIBE, "deepgram")!!.config()!!)
        assertEquals(HttpMethod.Get, deepgram.method)
        assertEquals("https://api.deepgram.com/v1/projects", deepgram.url.toString())
        assertEquals("Token $key", deepgram.headers[HttpHeaders.Authorization])

        val geminiStt = requestFor(ProviderCatalog.find(Capability.TRANSCRIBE, "gemini")!!.config()!!)
        assertEquals(HttpMethod.Get, geminiStt.method)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1", geminiStt.url.toString())
        assertEquals(key, geminiStt.headers["x-goog-api-key"])
        assertFalse(geminiStt.url.toString().contains(key), "la clave no va en la URL")

        val xai = requestFor(configFor("xai"))
        assertEquals("https://api.x.ai/v1/models", xai.url.toString())
        assertEquals("Bearer $key", xai.headers[HttpHeaders.Authorization])
    }

    @Test fun rejectedKeysAreAuth() = runTest {
        assertEquals(EngineException.Kind.AUTH, check(openAi, key, server(401)))
        assertEquals(EngineException.Kind.AUTH, check(openAi, key, server(403)))
        // Así responde Google a una clave inválida.
        val google = """{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
        assertEquals(EngineException.Kind.AUTH, check(configFor("gemini"), key, server(400, body = google)))
    }

    @Test fun noCreditOrLimitIsRateLimit() = runTest {
        assertEquals(EngineException.Kind.RATE_LIMIT, check(openAi, key, server(429)))
        assertEquals(EngineException.Kind.RATE_LIMIT, check(configFor("anthropic"), key, server(402)))
    }

    @Test fun otherAnswersCannotBeChecked() = runTest {
        assertEquals(EngineException.Kind.SERVER, check(openAi, key, server(503)))
        assertEquals(EngineException.Kind.BAD_RESPONSE, check(openAi, key, server(400)))
        assertEquals(EngineException.Kind.BAD_RESPONSE, check(openAi, key, server(404)))
    }

    @Test fun aNetworkFailureIsNetworkAndKeepsTheKeyOut() = runTest {
        val broken = HttpClient(MockEngine { throw IllegalStateException("conexión rechazada al mandar $key") })
        assertEquals(EngineException.Kind.NETWORK, check(openAi, key, broken))
    }

    @Test fun noAnswerInTimeIsNetwork() = runTest {
        val never = CompletableDeferred<Unit>()
        val silent = HttpClient(MockEngine { never.await(); respond("{}") })
        assertEquals(EngineException.Kind.NETWORK, check(openAi, key, silent, timeoutMs = 100))
    }

    @Test fun anEmptyKeyIsNotSent() = runTest {
        val seen = Seen()
        assertEquals(EngineException.Kind.NOT_CONFIGURED, check(openAi, "  ", server(200, seen)))
        assertEquals(0, seen.calls)
    }

    @Test fun spacesAroundAPastedKeyAreIgnored() = runTest {
        val seen = Seen()
        assertNull(check(openAi, "  $key\n", server(200, seen, "{}")))
        assertEquals("Bearer $key", seen.request!!.headers[HttpHeaders.Authorization])
    }

    @Test fun theResultNeverCarriesTheKey() = runTest {
        // El resultado es solo un tipo: ni mensaje ni cuerpo. Se comprueba con todas las respuestas posibles.
        val outputs = listOf(200, 400, 401, 402, 403, 404, 429, 500).map { status ->
            check(openAi, key, server(status)).toString()
        } + check(openAi, key, HttpClient(MockEngine { throw IllegalStateException(key) })).toString()
        assertTrue(outputs.isNotEmpty())
        outputs.forEach { assertFalse(it.contains(key), "la salida contiene la clave: $it") }
        outputs.forEach { assertFalse(it.contains("sk-"), "la salida contiene parte de la clave: $it") }
    }
}
