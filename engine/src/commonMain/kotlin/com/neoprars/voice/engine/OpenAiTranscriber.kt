package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * Transcripción con el protocolo `/v1/audio/transcriptions` de OpenAI.
 * Sirve también para servicios compatibles (cambiando baseUrl y model) y para tu propio backend.
 */
class OpenAiTranscriber(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.openai.com",
    private val auth: AuthStyle = AuthStyle.BEARER,
    private val headers: Map<String, String> = emptyMap(),
) : Transcriber {

    companion object {
        /** El más barato (~US$0,003/min). Alternativas: "gpt-transcribe", "whisper-1". */
        const val DEFAULT_MODEL = "gpt-4o-mini-transcribe"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    internal class TranscriptionResponse(val text: String = "")

    override suspend fun warmUp() = client.warmUpConnection(baseUrl)

    override var lastUploadMs: Long? = null
        private set

    override suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?): String {
        lastUploadMs = null
        val started = TimeSource.Monotonic.markNow()
        try {
            val languageField = if (model == "gpt-transcribe") "languages[]" else "language"
            val response = client.submitFormWithBinaryData(
                url = "$baseUrl/v1/audio/transcriptions",
                formData = formData {
                    append("model", model)
                    append("response_format", "json")
                    if (!language.isNullOrBlank()) append(languageField, language)
                    if (!vocabularyHint.isNullOrBlank()) append("prompt", vocabularyHint)
                    append("file", audio.bytes, Headers.build {
                        append(HttpHeaders.ContentType, audio.mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"${audio.fileName}\"")
                    })
                },
            ) {
                applyAuth(apiKey, auth, this@OpenAiTranscriber.headers)
                // Queda el momento del último tramo enviado: es lo que tardó la subida.
                onUpload { _, _ -> lastUploadMs = started.elapsedNow().inWholeMilliseconds }
            }
            val body = response.bodyOrThrow("Servicio de transcripción")
            return try {
                json.decodeFromString<TranscriptionResponse>(body).text.trim()
            } catch (e: Exception) {
                throw EngineException(EngineException.Kind.BAD_RESPONSE, "Respuesta de transcripción ilegible.")
            }
        } catch (e: EngineException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EngineException(EngineException.Kind.NETWORK, "No se pudo conectar con el servicio de transcripción: ${e.message}")
        }
    }
}
