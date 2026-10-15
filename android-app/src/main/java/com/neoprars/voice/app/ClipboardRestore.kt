package com.neoprars.voice.app

/**
 * Qué hacer con el portapapeles después de pegar el dictado. Separado de Android para poder probar la
 * rama por versión: `ClipboardManager.clearPrimaryClip()` existe recién desde Android 9 (API 28) y en
 * Android 8 (API 26-27) llamarlo hace fallar la app.
 */
object ClipboardRestore {

    /** Android 9: primera versión con `clearPrimaryClip()`. */
    const val CLEAR_MIN_SDK = 28

    enum class Action {
        /** Había algo copiado antes: se vuelve a poner. */
        RESTORE_PREVIOUS,

        /** No había nada: se vacía (Android 9 o posterior). */
        CLEAR,

        /** No había nada y no se puede vaciar (Android 8): se reemplaza el dictado por un texto vacío. */
        REPLACE_WITH_EMPTY,
    }

    fun action(hadPrevious: Boolean, sdkInt: Int): Action = when {
        hadPrevious -> Action.RESTORE_PREVIOUS
        sdkInt >= CLEAR_MIN_SDK -> Action.CLEAR
        else -> Action.REPLACE_WITH_EMPTY
    }
}
