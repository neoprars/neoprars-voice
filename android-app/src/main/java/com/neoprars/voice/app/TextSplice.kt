package com.neoprars.voice.app

/**
 * Decide cómo meter el dictado en un campo a partir de lo que informa Accesibilidad.
 * Cuentas puras (sin clases de Android) para poder probarlas.
 */
object TextSplice {

    sealed interface Plan {
        /** Reemplazar todo el texto del campo por [text] y dejar el cursor en [caret]. */
        data class SetText(val text: String, val caret: Int) : Plan

        /** Pegar en el cursor: no se sabe dónde está ni si el texto informado es real. */
        object Paste : Plan
    }

    /**
     * @param fieldText texto que informa el campo (puede ser el hint en algunas apps).
     * @param hint hint que informa el campo, si lo expone.
     * @param showingHint el campo avisa que lo que muestra es el hint.
     * @param selectionStart/selectionEnd -1 si el campo no informa selección.
     */
    fun plan(
        fieldText: String?,
        hint: String?,
        showingHint: Boolean,
        selectionStart: Int,
        selectionEnd: Int,
        dictated: String,
    ): Plan {
        val empty = showingHint || fieldText.isNullOrEmpty() || (hint != null && fieldText == hint)
        if (empty) return Plan.SetText(dictated, dictated.length)

        // Hay texto pero no selección: puede ser un hint disfrazado de texto (WhatsApp) o texto real.
        // Reescribir el campo arriesga pisar texto real o arrastrar el hint; pegar en el cursor no.
        if (selectionStart < 0 || selectionEnd < 0) return Plan.Paste

        val current = fieldText.orEmpty()
        val lo = minOf(selectionStart, selectionEnd).coerceIn(0, current.length)
        val hi = maxOf(selectionStart, selectionEnd).coerceIn(0, current.length)
        return Plan.SetText(current.substring(0, lo) + dictated + current.substring(hi), lo + dictated.length)
    }
}
