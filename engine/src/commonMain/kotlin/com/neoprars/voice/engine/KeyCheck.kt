package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Prueba de una clave con la llamada más liviana de cada protocolo: listar modelos (o, en Deepgram, los
 * proyectos de la clave). No manda texto ni audio,
 * no consume tokens y no tiene costo. Por eso no detecta la falta de saldo: el cobro se controla al usar el modelo.
 *
 * El resultado es solo un [EngineException.Kind] (null = clave válida): nunca un mensaje ni el cuerpo de la
 * respuesta, porque algunos proveedores repiten parte de la clave en sus errores.
 */
object KeyCheck {

    const val DEFAULT_TIMEOUT_MS = 10_000L

    /** Ruta de listado de modelos por defecto en los protocolos compatibles con OpenAI. */
    const val DEFAULT_MODELS_PATH = "/v1/models"

    /**
     * null = la clave sirve. Si no: AUTH (inválida o sin permisos), RATE_LIMIT (sin saldo o límite alcanzado),
     * NETWORK (sin conexión o sin respuesta a tiempo), NOT_CONFIGURED (clave vacía) o SERVER/BAD_RESPONSE (no se pudo comprobar).
     */
    suspend fun check(
        config: ProviderConfig,
        apiKey: String,
        client: HttpClient,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): EngineException.Kind? {
        if (apiKey.isBlank()) return EngineException.Kind.NOT_CONFIGURED
        val url = config.baseUrl + when (config.protocol) {
            Protocol.OPENAI_AUDIO_TRANSCRIPTIONS, Protocol.OPENAI_CHAT_COMPLETIONS ->
                config.options["models_path"] ?: DEFAULT_MODELS_PATH
            Protocol.ANTHROPIC_MESSAGES -> "/v1/models?limit=1"
            // Datos de los proyectos de la clave: no procesa audio.
            Protocol.DEEPGRAM_LISTEN -> "/v1/projects"
            // models.list de la API nativa; la clave va en la cabecera, no en la URL.
            Protocol.GEMINI_INTERACTIONS -> "/v1beta/models?pageSize=1"
        }
        val headers = when (config.protocol) {
            Protocol.ANTHROPIC_MESSAGES -> mapOf("anthropic-version" to "2023-06-01") + config.headers
            else -> config.headers
        }
        val outcome = withTimeoutOrNull(timeoutMs) { request(client, url, apiKey.trim(), config.auth, headers) }
            ?: return EngineException.Kind.NETWORK // sin respuesta a tiempo
        return outcome.kind
    }

    private suspend fun request(
        client: HttpClient,
        url: String,
        apiKey: String,
        auth: AuthStyle,
        headers: Map<String, String>,
    ): Outcome = try {
        val response = client.get(url) { applyAuth(apiKey, auth, headers) }
        val code = response.status.value
        Outcome(
            when (code) {
                in 200..299 -> null
                401, 403 -> EngineException.Kind.AUTH
                // 400 con clave inválida: así responden algunas APIs de Google. Se mira solo el código de motivo.
                400 -> if (response.bodyAsText().contains("API_KEY_INVALID")) {
                    EngineException.Kind.AUTH
                } else {
                    EngineException.Kind.BAD_RESPONSE
                }
                402, 429 -> EngineException.Kind.RATE_LIMIT
                in 500..599 -> EngineException.Kind.SERVER
                else -> EngineException.Kind.BAD_RESPONSE
            },
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // El mensaje de la excepción no se usa: podría traer datos de la petición.
        Outcome(EngineException.Kind.NETWORK)
    }

    /** Envuelve el resultado para que una clave válida (null) no se confunda con el null del timeout. */
    private class Outcome(val kind: EngineException.Kind?)
}
