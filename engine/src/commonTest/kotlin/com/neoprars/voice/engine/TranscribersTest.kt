package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transcriptores de Deepgram y Gemini contra servidores simulados. */
class TranscribersTest {

    private val key = "clave-secreta-987"
    private val m4a = EncodedAudio(byteArrayOf(1, 2, 3, 4, 5), "audio/mp4", "audio.m4a")

    private class Seen(var request: HttpRequestData? = null, var body: ByteArray = ByteArray(0))

    private fun server(status: Int = 200, reply: String, seen: Seen = Seen()) = HttpClient(MockEngine { request ->
        seen.request = request
        // El cuerpo se lee mientras la petición está viva (la medición de subida lo consume).
        seen.body = request.body.toByteArray()
        respond(reply, HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private val broken = HttpClient(MockEngine { throw IllegalStateException("se cortó la conexión con $key") })

    /** Que ni el mensaje ni el texto de la excepción repitan la clave. */
    private fun assertNoKey(e: Throwable) {
        assertFalse(e.message.orEmpty().contains(key), "el mensaje contiene la clave")
        assertFalse(e.toString().contains(key), "la excepción contiene la clave")
    }

    // ---------------- Deepgram ----------------

    private val deepgramReply =
        """{"metadata":{},"results":{"channels":[{"alternatives":[{"transcript":"  Hola, ¿cómo estás?  ","confidence":0.98}]}]}}"""

    @Test fun deepgramSendsRawAudioWithItsParameters() = runTest {
        val seen = Seen()
        val t = DeepgramTranscriber(server(reply = deepgramReply, seen = seen), key)

        val text = t.transcribe(m4a, "es", "Neoprars, María José;Kotlin\nneoprars")

        assertEquals("Hola, ¿cómo estás?", text)
        val r = seen.request!!
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("api.deepgram.com", r.url.host)
        assertEquals("/v1/listen", r.url.encodedPath)
        assertEquals("Token $key", r.headers[HttpHeaders.Authorization])
        assertEquals("nova-3", r.url.parameters["model"])
        assertEquals("es", r.url.parameters["language"])
        assertEquals("true", r.url.parameters["smart_format"])
        // Un parámetro por término, sin repetidos.
        assertEquals(listOf("Neoprars", "María José", "Kotlin"), r.url.parameters.getAll("keyterm"))
        assertEquals("audio/mp4", r.body.contentType.toString())
        assertContentEquals(m4a.bytes, seen.body)
        assertTrue((t.lastUploadMs ?: -1) >= 0)
    }

    @Test fun deepgramWithoutLanguageDetectsItAndSendsWavAsIs() = runTest {
        val seen = Seen()
        val wav = EncodedAudio.wav(ByteArray(44))
        DeepgramTranscriber(server(reply = deepgramReply, seen = seen), key).transcribe(wav, "", null)

        val r = seen.request!!
        assertEquals("multi", r.url.parameters["language"])
        assertNull(r.url.parameters["keyterm"])
        assertEquals("audio/wav", r.body.contentType.toString())
    }

    @Test fun deepgramOnlySendsKeytermsToModelsThatSupportThem() = runTest {
        val seen = Seen()
        DeepgramTranscriber(server(reply = deepgramReply, seen = seen), key, model = "nova-2").transcribe(m4a, "es", "Neoprars")
        assertNull(seen.request!!.url.parameters["keyterm"])
    }

    @Test fun deepgramWithoutSpeechReturnsEmptyText() = runTest {
        val empty = """{"results":{"channels":[{"alternatives":[{"transcript":""}]}]}}"""
        assertEquals("", DeepgramTranscriber(server(reply = empty), key).transcribe(m4a, "es", null))
        val noAlternatives = """{"results":{"channels":[]}}"""
        assertEquals("", DeepgramTranscriber(server(reply = noAlternatives), key).transcribe(m4a, "es", null))
    }

    @Test fun deepgramWithoutResultsIsAnError() = runTest {
        val e = assertFailsWith<EngineException> { DeepgramTranscriber(server(reply = "{}"), key).transcribe(m4a, "es", null) }
        assertEquals(EngineException.Kind.BAD_RESPONSE, e.kind)
    }

    @Test fun deepgramMapsErrorsWithoutLeakingTheKey() = runTest {
        val echo = """{"err_code":"INVALID_AUTH","err_msg":"Invalid credentials: $key"}"""
        for ((status, kind) in listOf(
            401 to EngineException.Kind.AUTH,
            403 to EngineException.Kind.AUTH,
            402 to EngineException.Kind.RATE_LIMIT,
            429 to EngineException.Kind.RATE_LIMIT,
            500 to EngineException.Kind.SERVER,
            400 to EngineException.Kind.BAD_RESPONSE,
        )) {
            val e = assertFailsWith<EngineException> { DeepgramTranscriber(server(status, echo), key).transcribe(m4a, "es", null) }
            assertEquals(kind, e.kind, "HTTP $status")
            assertNoKey(e)
        }
        val e = assertFailsWith<EngineException> { DeepgramTranscriber(broken, key).transcribe(m4a, "es", null) }
        assertEquals(EngineException.Kind.NETWORK, e.kind)
        assertNoKey(e)
    }

    // ---------------- Gemini ----------------

    private fun geminiReply(text: String, status: String = "completed") =
        """{"id":"interactions/1","status":"$status","steps":[{"id":"s1","type":"model_output","content":[{"type":"text","text":${quoted(text)}}]}]}"""

    private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @OptIn(ExperimentalEncodingApi::class)
    @Test fun geminiSendsAudioInBase64WithItsConfigAndNoStorage() = runTest {
        val seen = Seen()
        val t = GeminiTranscriber(server(reply = geminiReply("Hola, ¿cómo estás?"), seen = seen), key)

        val text = t.transcribe(m4a, "es", "Neoprars, Kubernetes")

        assertEquals("Hola, ¿cómo estás?", text)
        val r = seen.request!!
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", r.url.toString())
        assertEquals(key, r.headers["x-goog-api-key"])
        assertNull(r.headers[HttpHeaders.Authorization])
        assertFalse(r.url.toString().contains(key), "la clave no va en la URL")
        val body = seen.body.decodeToString()
        assertTrue(body.contains("\"model\":\"gemini-3.5-transcribe\""))
        assertTrue(body.contains("\"type\":\"audio\""))
        assertTrue(body.contains("\"data\":\"${Base64.encode(m4a.bytes)}\""))
        // Gemini no reconoce audio/mp4: el M4A se declara como tal.
        assertTrue(body.contains("\"mime_type\":\"audio/m4a\""))
        assertTrue(body.contains("\"language_codes\":[\"es\"]"))
        assertTrue(body.contains("\"custom_vocabulary\":[\"Neoprars\",\"Kubernetes\"]"))
        assertTrue(body.contains("\"store\":false"))
        assertTrue((t.lastUploadMs ?: -1) >= 0)
    }

    @Test fun geminiWithoutLanguageOrVocabularyLeavesThemOut() = runTest {
        val seen = Seen()
        GeminiTranscriber(server(reply = geminiReply("hola"), seen = seen), key).transcribe(EncodedAudio.wav(ByteArray(44)), null, " ")
        val body = seen.body.decodeToString()
        assertFalse(body.contains("language_codes"))
        assertFalse(body.contains("custom_vocabulary"))
        assertTrue(body.contains("\"mime_type\":\"audio/wav\""))
    }

    @Test fun geminiDropsCourtesyPrefixesAndQuotes() = runTest {
        suspend fun cleaned(reply: String) = GeminiTranscriber(server(reply = geminiReply(reply)), key).transcribe(m4a, "es", null)
        assertEquals("Mañana llego tarde.", cleaned("Aquí está la transcripción: Mañana llego tarde."))
        assertEquals("Mañana llego tarde.", cleaned("Claro, aquí tenés la transcripción:\n\"Mañana llego tarde.\""))
        assertEquals("See you tomorrow.", cleaned("Here's the transcript: See you tomorrow."))
        assertEquals("Mañana llego tarde.", cleaned("Transcripción: «Mañana llego tarde.»"))
        assertEquals("Mañana llego tarde.", cleaned("```\nMañana llego tarde.\n```"))
        // Dos puntos dentro de lo dictado no son una presentación.
        assertEquals("Lista: pan, leche y huevos.", cleaned("Lista: pan, leche y huevos."))
        assertEquals("Nos vemos a las 10:30.", cleaned("Nos vemos a las 10:30."))
    }

    @Test fun geminiRefusalIsAnErrorNotText() = runTest {
        for (refusal in listOf(
            "Lo siento, no puedo transcribir este audio.",
            "I'm sorry, but I can't transcribe this audio.",
        )) {
            val e = assertFailsWith<EngineException> {
                GeminiTranscriber(server(reply = geminiReply(refusal)), key).transcribe(m4a, "es", null)
            }
            assertEquals(EngineException.Kind.BAD_RESPONSE, e.kind)
        }
        // Algo dictado que se disculpa, sin hablar del audio, pasa tal cual.
        assertEquals(
            "Lo siento, no puedo ir mañana.",
            GeminiTranscriber(server(reply = geminiReply("Lo siento, no puedo ir mañana.")), key).transcribe(m4a, "es", null),
        )
    }

    @Test fun geminiBlockedOrFailedAnswersAreErrors() = runTest {
        val blocked = listOf(
            // Sin salida del modelo (por ejemplo, bloqueada por seguridad).
            """{"id":"interactions/1","status":"completed","steps":[]}""",
            """{"id":"interactions/1","status":"incomplete","steps":[{"type":"model_output","content":[]}]}""",
            """{"id":"interactions/1","status":"failed","steps":[]}""",
            """{"id":"interactions/1","status":"cancelled"}""",
            """{"error":{"code":"blocked","message":"content blocked"}}""",
        )
        for (reply in blocked) {
            val e = assertFailsWith<EngineException>(reply) {
                GeminiTranscriber(server(reply = reply), key).transcribe(m4a, "es", null)
            }
            assertEquals(EngineException.Kind.BAD_RESPONSE, e.kind, reply)
        }
    }

    @Test fun geminiEmptyTextMeansNoSpeech() = runTest {
        assertEquals("", GeminiTranscriber(server(reply = geminiReply("")), key).transcribe(m4a, "es", null))
        assertEquals("", GeminiTranscriber(server(reply = geminiReply("   ")), key).transcribe(m4a, "es", null))
    }

    @Test fun geminiIgnoresStepsThatAreNotTheModelOutput() = runTest {
        val reply = """{"status":"completed","steps":[{"type":"user_input","content":[{"type":"text","text":"eco"}]},""" +
            """{"type":"thought","content":[{"type":"text","text":"pienso"}]},""" +
            """{"type":"model_output","content":[{"type":"text","text":"Hola "},{"type":"text","text":"mundo"}]}]}"""
        assertEquals("Hola mundo", GeminiTranscriber(server(reply = reply), key).transcribe(m4a, "es", null))
    }

    @Test fun geminiMapsErrorsWithoutLeakingTheKey() = runTest {
        val echo = """{"error":{"code":400,"message":"API key not valid: $key","status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
        for ((status, kind) in listOf(
            400 to EngineException.Kind.AUTH, // así responde Google a una clave inválida
            401 to EngineException.Kind.AUTH,
            403 to EngineException.Kind.AUTH,
            429 to EngineException.Kind.RATE_LIMIT,
            500 to EngineException.Kind.SERVER,
        )) {
            val e = assertFailsWith<EngineException> { GeminiTranscriber(server(status, echo), key).transcribe(m4a, "es", null) }
            assertEquals(kind, e.kind, "HTTP $status")
            assertNoKey(e)
        }
        val other400 = assertFailsWith<EngineException> {
            GeminiTranscriber(server(400, """{"error":{"message":"bad audio $key"}}"""), key).transcribe(m4a, "es", null)
        }
        assertEquals(EngineException.Kind.BAD_RESPONSE, other400.kind)
        assertNoKey(other400)

        val e = assertFailsWith<EngineException> { GeminiTranscriber(broken, key).transcribe(m4a, "es", null) }
        assertEquals(EngineException.Kind.NETWORK, e.kind)
        assertNoKey(e)
    }

    @Test fun geminiRejectsAudioTooLargeForOneRequestWithoutSendingIt() = runTest {
        val seen = Seen()
        val huge = EncodedAudio(ByteArray(15_000_000), "audio/wav", "audio.wav") // ~20 MB en base64
        val e = assertFailsWith<EngineException> {
            GeminiTranscriber(server(reply = geminiReply("x"), seen = seen), key).transcribe(huge, "es", null)
        }
        assertEquals(EngineException.Kind.BAD_RESPONSE, e.kind)
        assertNull(seen.request)
    }

    // ---------------- Fábrica y vocabulario ----------------

    @Test fun theFactoryBuildsEachTranscriberFromTheCatalog() {
        val http = server(reply = "{}")
        fun build(id: String) = EngineFactory.transcriber(
            ProviderCatalog.find(Capability.TRANSCRIBE, id)!!.config()!!, http, KeyResolver { key },
        )
        assertTrue(build("deepgram") is DeepgramTranscriber)
        assertTrue(build("gemini") is GeminiTranscriber)
        assertTrue(build("openai") is OpenAiTranscriber)
    }

    @Test fun vocabularyIsSplitIntoTerms() {
        assertEquals(listOf("Neoprars", "María José", "Kotlin"), VocabularyTerms.parse(" Neoprars, María José ;Kotlin\n\nneoprars,", 10))
        assertEquals(listOf("a", "b"), VocabularyTerms.parse("a,b,c", 2))
        assertEquals(emptyList(), VocabularyTerms.parse(null, 10))
        assertEquals(emptyList(), VocabularyTerms.parse(" , ;", 10))
        assertEquals(emptyList(), VocabularyTerms.parse("x".repeat(101), 10))
    }
}
