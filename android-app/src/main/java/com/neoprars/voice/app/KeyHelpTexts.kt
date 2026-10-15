package com.neoprars.voice.app

/** Textos de la ayuda para conseguir la clave, por el identificador que guarda el catálogo (`KeyHelp.id`). */
object KeyHelpTexts {

    /** Pasos numerados (string-array); null = sin pasos propios, se muestra un texto genérico. */
    fun steps(helpId: String): Int? = when (helpId) {
        "openai" -> R.array.key_help_steps_openai
        "anthropic" -> R.array.key_help_steps_anthropic
        "gemini" -> R.array.key_help_steps_gemini
        "xai" -> R.array.key_help_steps_xai
        "deepgram" -> R.array.key_help_steps_deepgram
        else -> null
    }
}
