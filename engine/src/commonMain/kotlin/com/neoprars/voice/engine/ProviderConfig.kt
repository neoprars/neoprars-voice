package com.neoprars.voice.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Qué "idioma HTTP" habla un proveedor. Cambiar de vendor casi siempre es cambiar
 * la configuración (baseUrl + model); un protocolo nuevo es una clase + una línea en [EngineFactory].
 */
@Serializable
enum class Protocol {
    /** POST {baseUrl}/v1/audio/transcriptions (OpenAI y servicios compatibles). */
    OPENAI_AUDIO_TRANSCRIPTIONS,

    /** POST {baseUrl}/v1/chat/completions (OpenAI y servicios compatibles). */
    OPENAI_CHAT_COMPLETIONS,

    /** POST {baseUrl}/v1/messages (Anthropic). */
    ANTHROPIC_MESSAGES,

    /** POST {baseUrl}/v1/listen con el audio crudo en el cuerpo (Deepgram, audio pregrabado). */
    DEEPGRAM_LISTEN,

    /** POST {baseUrl}/v1beta/interactions con el audio en base64 (Gemini, modelos de transcripción). */
    GEMINI_INTERACTIONS,
}

/** Cómo se envía la credencial. */
@Serializable
enum class AuthStyle {
    /** `Authorization: Bearer <clave>` */
    BEARER,

    /** `x-api-key: <clave>` */
    X_API_KEY,

    /** `Authorization: Token <clave>` */
    TOKEN,

    /** `x-goog-api-key: <clave>` */
    X_GOOG_API_KEY,
}

/**
 * Configuración de UN proveedor. No contiene secretos: la clave se resuelve aparte
 * con [KeyResolver] usando [id]. Por eso se puede guardar, versionar o descargar desde un servidor.
 *
 * @param baseUrl sin "/v1" al final (ej.: "https://api.openai.com").
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val protocol: Protocol,
    val baseUrl: String,
    val model: String,
    val auth: AuthStyle = AuthStyle.BEARER,
    val headers: Map<String, String> = emptyMap(),
    /** Parámetros extra propios del protocolo (ej.: "reasoning_effort" en chat completions). */
    val options: Map<String, String> = emptyMap(),
)

/**
 * Qué proveedores usa la app. Serializable: hoy se arma en el dispositivo; en la versión
 * comercial puede llegar desde tu servidor y cambiar de IA sin publicar una actualización.
 */
@Serializable
data class EngineSetup(
    val transcriber: ProviderConfig,
    val polisher: ProviderConfig? = null,
) {
    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): EngineSetup = json.decodeFromString(text)
    }
}

/** Entrega la clave de cada proveedor por su `id`. La app decide dónde se guardan (Keystore, sesión, etc.). */
fun interface KeyResolver {
    fun keyFor(providerId: String): String?
}

/** Configuraciones listas para usar. Los modelos y precios cambian: revisalos antes de fijarlos. */
object Presets {

    fun openAiTranscribe(model: String = OpenAiTranscriber.DEFAULT_MODEL) = ProviderConfig(
        id = "openai",
        protocol = Protocol.OPENAI_AUDIO_TRANSCRIPTIONS,
        baseUrl = "https://api.openai.com",
        model = model,
    )

    fun claudePolish(model: String = ClaudePolisher.DEFAULT_MODEL) = ProviderConfig(
        id = "anthropic",
        protocol = Protocol.ANTHROPIC_MESSAGES,
        baseUrl = "https://api.anthropic.com",
        model = model,
        auth = AuthStyle.X_API_KEY,
        options = mapOf("thinking" to "disabled"),
    )

    /** Pulido con un modelo de chat de OpenAI (misma clave que la transcripción), sin razonar para responder rápido. */
    fun openAiPolish(model: String = OPENAI_POLISH_MODEL) = ProviderConfig(
        id = "openai",
        protocol = Protocol.OPENAI_CHAT_COMPLETIONS,
        baseUrl = "https://api.openai.com",
        model = model,
        options = mapOf("reasoning_effort" to "none"),
    )

    const val OPENAI_POLISH_MODEL = "gpt-6-luna"

    /** Configuración por defecto: la selección de fábrica del catálogo (una sola clave, OpenAI, para transcribir y pulir). */
    fun defaultSetup() = ProviderCatalog.setup(CatalogSelection())
}
