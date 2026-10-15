package com.neoprars.voice.engine

/**
 * Convierte el vocabulario que escribió la persona ("Neoprars, Kubernetes; María José") en términos sueltos,
 * para los servicios que lo reciben como lista (términos clave) en vez de como texto libre.
 */
object VocabularyTerms {

    /**
     * Separa por coma, punto y coma o salto de línea; quita espacios y repetidos (sin distinguir mayúsculas)
     * y se queda con los primeros [max] términos, cada uno de hasta [maxTermLength] caracteres.
     */
    fun parse(hint: String?, max: Int, maxTermLength: Int = 100): List<String> {
        if (hint.isNullOrBlank()) return emptyList()
        val seen = mutableSetOf<String>()
        return hint.split(',', ';', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= maxTermLength }
            .filter { seen.add(it.lowercase()) }
            .take(max)
    }
}
