package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** Pulido con el protocolo `/v1/messages` de Anthropic. */
class ClaudePolisher(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = "https://api.anthropic.com",
    private val maxTokens: Int = 1024,
    private val auth: AuthStyle = AuthStyle.X_API_KEY,
    private val headers: Map<String, String> = emptyMap(),
    /** Opcional: tipo de razonamiento ("disabled" = responder sin pensar, lo más rápido); null = el del modelo. */
    private val thinking: String? = null,
) : Polisher {

    companion object {
        const val DEFAULT_MODEL = "claude-haiku-5-5"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    internal class Message(val role: String, val content: String)

    @Serializable
    internal class Thinking(val type: String)

    @Serializable
    internal class MessagesRequest(
        val model: String,
        val max_tokens: Int,
        val system: String,
        val messages: List<Message>,
        val thinking: Thinking? = null, // null no se envía
    )

    @Serializable
    internal class Block(val type: String = "", val text: String = "")

    @Serializable
    internal class MessagesResponse(val content: List<Block> = emptyList())

    override suspend fun warmUp() = client.warmUpConnection(baseUrl)

    override suspend fun polish(text: String, instructions: String?, previousText: String?): String {
        try {
            val request = MessagesRequest(
                model = model,
                max_tokens = maxTokens,
                system = PolishPrompt.system(instructions),
                messages = listOf(Message("user", PolishPrompt.user(text, previousText))),
                thinking = thinking?.let { Thinking(it) },
            )
            val response = client.post("$baseUrl/v1/messages") {
                applyAuth(apiKey, auth, this@ClaudePolisher.headers)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
            val body = response.bodyOrThrow("Servicio de pulido")
            return try {
                json.decodeFromString<MessagesResponse>(body)
                    .content.filter { it.type == "text" }.joinToString("") { it.text }.trim()
            } catch (e: Exception) {
                throw EngineException(EngineException.Kind.BAD_RESPONSE, "Respuesta de pulido ilegible.")
            }
        } catch (e: EngineException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EngineException(EngineException.Kind.NETWORK, "No se pudo conectar con el servicio de pulido: ${e.message}")
        }
    }
}
