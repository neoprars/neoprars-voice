package com.neoprars.voice.engine

/**
 * Limpieza determinista del texto (español), sin red. Es la red de seguridad cuando
 * no hay pulido con IA o cuando éste falla. Evita Regex a propósito: así se comporta
 * igual en JVM, Android y iOS (Kotlin/Native).
 */
object RuleCleaner {

    private val fillers = setOf("eh", "ehh", "ehm", "em", "emm", "mm", "mmm", "hm", "hmm", "um", "umm")

    // Solo palabras funcionales: "muy muy" o "no no" son énfasis legítimo y se respetan.
    private val stutterable = setOf(
        "que", "de", "la", "el", "en", "y", "a", "me", "se", "lo", "los", "las",
        "un", "una", "con", "por", "para", "es", "si", "te", "mi", "tu", "su", "del", "al",
    )

    fun clean(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val withCommands = applyVoiceCommands(text)
        val lines = withCommands.split('\n').joinToString("\n") { dropFillersAndStutters(it) }
        return capitalize(tidy(lines))
    }

    private fun applyVoiceCommands(text: String): String = text
        .replace("punto y aparte", ".\n", ignoreCase = true)
        .replace("nueva línea", "\n", ignoreCase = true)
        .replace("nueva linea", "\n", ignoreCase = true)
        .replace("signo de interrogación", "?", ignoreCase = true)
        .replace("signo de interrogacion", "?", ignoreCase = true)
        .replace("signo de exclamación", "!", ignoreCase = true)
        .replace("signo de exclamacion", "!", ignoreCase = true)

    private fun core(token: String): String = token.trim { !it.isLetter() }.lowercase()

    private fun dropFillersAndStutters(line: String): String {
        val tokens = line.replace('\t', ' ').split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (token in tokens) {
            val c = core(token)
            if (c in fillers) {
                // Conserva un cierre de oración pegado a la muletilla ("eh.").
                val end = token.takeLastWhile { it == '.' || it == '?' || it == '!' }
                if (end.isNotEmpty() && out.isNotEmpty() && out.last().last() !in ".?!") {
                    out[out.lastIndex] = out.last() + end.take(1)
                }
                continue
            }
            if (c in stutterable && out.isNotEmpty() && core(out.last()) == c) continue
            out.add(token)
        }
        return out.joinToString(" ")
    }

    /** Espacios y puntuación: sin espacios dobles ni antes de signos, sin espacio tras salto de línea. */
    private fun tidy(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch == ' ' || ch == '\t' ->
                    if (sb.isNotEmpty() && sb.last() != ' ' && sb.last() != '\n') sb.append(' ')
                ch == '.' || ch == ',' || ch == ';' || ch == ':' || ch == '!' || ch == '?' -> {
                    while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                    if (ch == '.' && sb.isNotEmpty() && sb.last() == ',') sb.setLength(sb.length - 1)
                    sb.append(ch)
                }
                ch == '\n' -> {
                    while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
                    sb.append('\n')
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    private fun capitalize(s: String): String {
        val sb = StringBuilder(s.length)
        var cap = true
        for (i in s.indices) {
            val ch = s[i]
            when {
                ch.isLetter() -> {
                    sb.append(if (cap) ch.uppercaseChar() else ch)
                    cap = false
                }
                ch == '\n' -> { sb.append(ch); cap = true }
                ch == '.' || ch == '!' || ch == '?' -> {
                    sb.append(ch)
                    // "www.google.com" no debe capitalizarse: solo si el signo cierra la oración.
                    if (i + 1 >= s.length || s[i + 1] == ' ' || s[i + 1] == '\n') cap = true
                }
                ch.isDigit() -> { sb.append(ch); cap = false }
                else -> sb.append(ch) // espacios, ¿ ¡ comillas, comas: no cambian el estado
            }
        }
        return sb.toString()
    }
}
