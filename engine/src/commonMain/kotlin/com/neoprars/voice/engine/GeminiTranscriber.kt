package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource

/**
 * Transcripción con un modelo de transcripción de Gemini por `POST /v1beta/interactions`, con el audio en
 * base64 dentro del pedido. Idioma y vocabulario van en `transcription_config` (el mecanismo propio del modelo).
 * Se pide `store: false`: si no, Google guarda el pedido (audio incluido) para recuperarlo después.
 * Fuentes: https://ai.google.dev/gemini-api/docs/transcribe y https://ai.google.dev/gemini-api/docs/interactions
 */
class GeminiTranscriber(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val auth: AuthStyle = AuthStyle.X_GOOG_API_KEY,
    private val headers: Map<String, String> = emptyMap(),
) : Transcriber {

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-transcribe"

        /** Google aconseja hasta 100 términos de vocabulario (admite 1000). */
        const val MAX_VOCABULARY = 100

        /** El pedido completo (audio en base64 incluido) no puede pasar de 20 MB; se deja margen para el resto. */
        const val MAX_REQUEST_BYTES = 19_000_000L

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Gemini reconoce `audio/m4a` y `audio/aac`, pero no `audio/mp4`: el M4A de la app se declara como m4a.
         * WAV va tal cual.
         */
        internal fun mimeTypeFor(audio: EncodedAudio): String = when (audio.mimeType) {
            "audio/mp4", "audio/x-m4a" -> "audio/m4a"
            "audio/x-wav", "audio/wave" -> "audio/wav"
            else -> audio.mimeType
        }
    }

    @Serializable
    internal class AudioInput(val type: String, val data: String, val mime_type: String)

    @Serializable
    internal class TranscriptionConfig(
        val language_codes: List<String>? = null, // null no se envía: detecta el idioma solo
        val custom_vocabulary: List<String>? = null,
    )

    @Serializable
    internal class GenerationConfig(val transcription_config: TranscriptionConfig)

    @Serializable
    internal class InteractionRequest(
        val model: String,
        val input: List<AudioInput>,
        val generation_config: GenerationConfig,
        val store: Boolean,
    )

    @Serializable
    internal class Content(val type: String = "", val text: String? = null)

    @Serializable
    internal class Step(val type: String = "", val content: List<Content> = emptyList())

    @Serializable
    internal class InteractionResponse(
        val status: String? = null,
        val steps: List<Step> = emptyList(),
        val error: JsonElement? = null,
    )

    override suspend fun warmUp() = client.warmUpConnection(baseUrl)

    override var lastUploadMs: Long? = null
        private set

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?): String {
        lastUploadMs = null
        val encodedSize = (audio.bytes.size + 2L) / 3 * 4
        if (encodedSize > MAX_REQUEST_BYTES) {
            throw EngineException(EngineException.Kind.BAD_RESPONSE, "El audio es demasiado largo para este servicio de transcripción.")
        }
        val vocabulary = VocabularyTerms.parse(vocabularyHint, MAX_VOCABULARY)
        val request = InteractionRequest(
            model = model,
            input = listOf(AudioInput("audio", Base64.encode(audio.bytes), mimeTypeFor(audio))),
            generation_config = GenerationConfig(
                TranscriptionConfig(
                    language_codes = language?.takeIf { it.isNotBlank() }?.let { listOf(it) },
                    custom_vocabulary = vocabulary.takeIf { it.isNotEmpty() },
                ),
            ),
            store = false,
        )
        val started = TimeSource.Monotonic.markNow()
        try {
            val response = client.post("$baseUrl/v1beta/interactions") {
                applyAuth(apiKey, auth, this@GeminiTranscriber.headers)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
                onUpload { _, _ -> lastUploadMs = started.elapsedNow().inWholeMilliseconds }
            }
            val body = response.bodyOrThrow("Servicio de transcripción")
            val parsed = try {
                json.decodeFromString<InteractionResponse>(body)
            } catch (e: Exception) {
                throw EngineException(EngineException.Kind.BAD_RESPONSE, "Respuesta de transcripción ilegible.")
            }
            return textOf(parsed)
        } catch (e: EngineException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EngineException(EngineException.Kind.NETWORK, "No se pudo conectar con el servicio de transcripción.")
        }
    }

    /**
     * Solo el texto de la salida del modelo. Un error, un estado fallido o una respuesta sin salida (por ejemplo,
     * bloqueada por seguridad: la documentación no dice cómo se informa) es un error, nunca texto inventado.
     * Salida presente pero vacía = no se reconoció voz.
     */
    private fun textOf(response: InteractionResponse): String {
        val status = response.status
        val outputs = response.steps.filter { it.type == "model_output" }
        val text = outputs.flatMap { it.content }.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("")
        val failed = response.error != null || status == "failed" || status == "cancelled"
        val unfinished = status != null && status != "completed" && text.isBlank()
        if (failed || unfinished || outputs.isEmpty()) {
            throw EngineException(
                EngineException.Kind.BAD_RESPONSE,
                "El servicio de transcripción no devolvió texto (estado: ${status ?: "desconocido"}).",
            )
        }
        return TranscriptGuard.clean(text)
    }
}
