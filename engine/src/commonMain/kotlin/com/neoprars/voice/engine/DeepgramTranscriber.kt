package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Transcripción de audio pregrabado con `POST /v1/listen`: el audio va crudo en el cuerpo, con su
 * Content-Type, y las opciones en la URL. Acepta tanto el M4A/AAC de la app como WAV.
 * Fuente: https://developers.deepgram.com/docs/pre-recorded-audio
 */
class DeepgramTranscriber(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.deepgram.com",
    private val auth: AuthStyle = AuthStyle.TOKEN,
    private val headers: Map<String, String> = emptyMap(),
) : Transcriber {

    companion object {
        /** Recomendado para audio pregrabado; admite español (`es`, `es-419`) y términos clave. */
        const val DEFAULT_MODEL = "nova-3"

        /** Deepgram aconseja concentrarse en 20 a 50 términos (tope: 500 tokens entre todos). */
        const val MAX_KEYTERMS = 50
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    internal class Alternative(val transcript: String = "")

    @Serializable
    internal class Channel(val alternatives: List<Alternative> = emptyList())

    @Serializable
    internal class Results(val channels: List<Channel> = emptyList())

    @Serializable
    internal class ListenResponse(val results: Results? = null)

    override suspend fun warmUp() = client.warmUpConnection(baseUrl)

    override var lastUploadMs: Long? = null
        private set

    /** Los términos clave (`keyterm`) solo existen en Nova-3 y Flux; con otros modelos no se mandan. */
    private val supportsKeyterms: Boolean get() = model.startsWith("nova-3") || model.startsWith("flux")

    override suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?): String {
        lastUploadMs = null
        val started = TimeSource.Monotonic.markNow()
        try {
            val response = client.post("$baseUrl/v1/listen") {
                applyAuth(apiKey, auth, this@DeepgramTranscriber.headers)
                parameter("model", model)
                // Sin idioma, Deepgram asume inglés: "multi" detecta el idioma (y mezclas, como español e inglés).
                parameter("language", language?.takeIf { it.isNotBlank() } ?: "multi")
                parameter("smart_format", "true")
                if (supportsKeyterms) {
                    // Un parámetro por término, repetido; nunca separados por coma (Deepgram lo tomaría como uno solo).
                    VocabularyTerms.parse(vocabularyHint, MAX_KEYTERMS).forEach { parameter("keyterm", it) }
                }
                contentType(ContentType.parse(audio.mimeType))
                setBody(audio.bytes)
                onUpload { _, _ -> lastUploadMs = started.elapsedNow().inWholeMilliseconds }
            }
            val body = response.bodyOrThrow("Servicio de transcripción")
            val parsed = try {
                json.decodeFromString<ListenResponse>(body)
            } catch (e: Exception) {
                throw EngineException(EngineException.Kind.BAD_RESPONSE, "Respuesta de transcripción ilegible.")
            }
            val results = parsed.results
                ?: throw EngineException(EngineException.Kind.BAD_RESPONSE, "La transcripción llegó sin resultados.")
            // Sin canales o sin alternativas = no se reconoció voz: texto vacío, como con los otros servicios.
            return results.channels.firstOrNull()?.alternatives?.firstOrNull()?.transcript.orEmpty().trim()
        } catch (e: EngineException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Sin el mensaje de la excepción: puede traer la URL, que lleva el vocabulario de la persona.
            throw EngineException(EngineException.Kind.NETWORK, "No se pudo conectar con el servicio de transcripción.")
        }
    }
}
