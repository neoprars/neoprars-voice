package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pulido con el protocolo `/v1/chat/completions`. Lo hablan OpenAI y muchos proveedores
 * más baratos o locales (Ollama, LM Studio…): para migrar basta cambiar baseUrl y model.
 */
class OpenAiChatPolisher(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.openai.com",
    private val auth: AuthStyle = AuthStyle.BEARER,
    private val headers: Map<String, String> = emptyMap(),
    /** Opcional: en modelos que razonan, cuánto pensar antes de responder ("none" = lo más rápido). */
    private val reasoningEffort: String? = null,
    /** Ruta del endpoint dentro de [baseUrl]; algunos compatibles la cambian (Gemini: "/v1beta/openai/chat/completions"). */
    private val chatPath: String = DEFAULT_CHAT_PATH,
) : Polisher {

    companion object {
        const val DEFAULT_CHAT_PATH = "/v1/chat/completions"
        private val json = Json { ignoreUnknownKeys = true }
    }

    @Serializable
    internal class ChatMessage(val role: String, val content: String)

    @Serializable
    internal class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val reasoning_effort: String? = null, // null no se envía
    )

    @Serializable
    internal class ChatChoiceMessage(val content: String? = null)

    @Serializable
    internal class ChatChoice(val message: ChatChoiceMessage = ChatChoiceMessage())

    @Serializable
    internal class ChatResponse(val choices: List<ChatChoice> = emptyList())

    override suspend fun warmUp() = client.warmUpConnection(baseUrl)

    override suspend fun polish(text: String, instructions: String?, previousText: String?): String {
        try {
            val request = ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", PolishPrompt.system(instructions)),
                    ChatMessage("user", PolishPrompt.user(text, previousText)),
                ),
                reasoning_effort = reasoningEffort,
            )
            val response = client.post("$baseUrl$chatPath") {
                applyAuth(apiKey, auth, this@OpenAiChatPolisher.headers)
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(request))
            }
            val body = response.bodyOrThrow("Servicio de pulido")
            return try {
                json.decodeFromString<ChatResponse>(body).choices.firstOrNull()?.message?.content.orEmpty().trim()
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
