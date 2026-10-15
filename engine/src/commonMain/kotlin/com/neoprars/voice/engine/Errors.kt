package com.neoprars.voice.engine

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlin.coroutines.cancellation.CancellationException

class EngineException(
    val kind: Kind,
    message: String,
    val httpStatus: Int? = null,
) : Exception(message) {
    enum class Kind {
        AUDIO_TOO_SHORT, AUDIO_SILENT, AUTH, RATE_LIMIT, NETWORK, SERVER, BAD_RESPONSE,

        /** Falta una clave o la configuración de un proveedor es inválida. */
        NOT_CONFIGURED,
    }
}

/**
 * Devuelve el cuerpo si la respuesta es 2xx; si no, lanza una EngineException con el código HTTP.
 * El mensaje nunca incluye el cuerpo del error: algunos proveedores repiten ahí la clave o el texto enviado.
 */
internal suspend fun HttpResponse.bodyOrThrow(provider: String): String {
    val body = bodyAsText()
    val code = status.value
    if (code in 200..299) return body
    // Las APIs de Google responden 400 (no 401) a una clave inválida; se mira solo el código de motivo.
    val invalidKey = code == 400 && body.contains("API_KEY_INVALID")
    when {
        code == 401 || code == 403 || invalidKey -> throw EngineException(
            EngineException.Kind.AUTH, "$provider rechazó la clave de API (HTTP $code).", code
        )
        code == 429 || code == 402 -> throw EngineException(
            EngineException.Kind.RATE_LIMIT, "$provider: límite de uso o saldo agotado (HTTP $code).", code
        )
        code in 500..599 -> throw EngineException(
            EngineException.Kind.SERVER, "$provider tuvo un error del servidor (HTTP $code).", code
        )
        else -> throw EngineException(
            EngineException.Kind.BAD_RESPONSE, "$provider respondió HTTP $code.", code
        )
    }
}

/** Credencial + cabeceras extra, según la configuración del proveedor. */
internal fun HttpRequestBuilder.applyAuth(apiKey: String, auth: AuthStyle, extraHeaders: Map<String, String>) {
    when (auth) {
        AuthStyle.BEARER -> header(HttpHeaders.Authorization, "Bearer $apiKey")
        AuthStyle.X_API_KEY -> header("x-api-key", apiKey)
        AuthStyle.TOKEN -> header(HttpHeaders.Authorization, "Token $apiKey")
        AuthStyle.X_GOOG_API_KEY -> header("x-goog-api-key", apiKey)
    }
    extraHeaders.forEach { (name, value) -> header(name, value) }
}

/**
 * Abre la conexión (DNS + TLS) con una petición liviana, sin credenciales ni datos de la persona.
 * La respuesta no importa y los errores se ignoran: es solo para que la petición real no pague el saludo.
 */
internal suspend fun HttpClient.warmUpConnection(baseUrl: String) {
    try {
        head(baseUrl)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // sin conexión o respuesta inesperada: no pasa nada
    }
}
