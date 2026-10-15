package com.neoprars.voice.engine

/**
 * Defensa para transcriptores que son modelos de lenguaje: a veces agregan una presentación
 * ("Aquí está la transcripción:"), envuelven el texto en comillas o se niegan a transcribir.
 * Nunca inventa texto: o devuelve lo transcripto limpio, o lanza un error.
 */
internal object TranscriptGuard {

    /** Una presentación es una primera frase corta, terminada en dos puntos, que habla de la transcripción. */
    private const val MAX_PREAMBLE = 80
    private val preambleWords = listOf("transcrip", "here is", "here's", "aquí está", "aquí tenés", "acá está", "acá tenés")

    /** Un rechazo es una respuesta corta que se niega Y habla del audio o de transcribir. */
    private const val MAX_REFUSAL = 200
    private val refusalWords = listOf("no puedo", "no es posible", "lo siento", "can't", "cannot", "unable to", "i'm sorry", "sorry,")
    private val refusalTopics = listOf("transcrib", "audio")

    private val quotes = listOf('"', '\'', '“', '”', '«', '»', '`')

    fun clean(raw: String): String {
        var text = raw.trim()
        text = stripFence(text)
        text = stripPreamble(text)
        text = stripQuotes(text)
        if (isRefusal(text)) {
            throw EngineException(EngineException.Kind.BAD_RESPONSE, "El servicio de transcripción no devolvió una transcripción.")
        }
        return text
    }

    private fun stripFence(text: String): String {
        if (!text.startsWith("```") || !text.endsWith("```") || text.length < 6) return text
        val inner = text.substring(3, text.length - 3)
        // Una etiqueta de lenguaje ("```text") ocupa la primera línea.
        val firstBreak = inner.indexOf('\n')
        val body = if (firstBreak in 0..20 && inner.substring(0, firstBreak).none { it == ' ' }) inner.substring(firstBreak + 1) else inner
        return body.trim()
    }

    private fun stripPreamble(text: String): String {
        val colon = text.indexOf(':')
        if (colon < 0 || colon > MAX_PREAMBLE) return text
        val head = text.substring(0, colon).lowercase()
        if (head.contains('\n') || preambleWords.none { head.contains(it) }) return text
        return text.substring(colon + 1).trim()
    }

    private fun stripQuotes(text: String): String {
        if (text.length >= 2 && text.first() in quotes && text.last() in quotes) {
            return text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private fun isRefusal(text: String): Boolean {
        if (text.length > MAX_REFUSAL) return false
        val lower = text.lowercase()
        return refusalWords.any { lower.contains(it) } && refusalTopics.any { lower.contains(it) }
    }
}
