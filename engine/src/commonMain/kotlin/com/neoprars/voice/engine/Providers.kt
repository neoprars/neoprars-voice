package com.neoprars.voice.engine

/** Voz → texto. Implementá esta interfaz para sumar otro proveedor (por ejemplo, uno local). */
interface Transcriber {
    suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?): String

    /**
     * Opcional: deja lista la conexión antes de que haga falta (por ejemplo, mientras la persona habla).
     * No envía datos de la persona ni credenciales, y nunca falla.
     */
    suspend fun warmUp() {}

    /** Cuánto tardó en subir el audio en la última transcripción, si se pudo medir (solo para medir latencia). */
    val lastUploadMs: Long? get() = null
}

/** Texto crudo → texto pulido (autocorrecciones, puntuación, tono). */
interface Polisher {
    /**
     * @param previousText final del texto dictado justo antes (por ejemplo, el tramo anterior), solo como
     *   contexto para que puntuación y mayúsculas del comienzo sean coherentes; no se devuelve ni se modifica.
     */
    suspend fun polish(text: String, instructions: String?, previousText: String? = null): String

    /** Ver [Transcriber.warmUp]. */
    suspend fun warmUp() {}
}
