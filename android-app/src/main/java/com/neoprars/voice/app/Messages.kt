package com.neoprars.voice.app

import androidx.annotation.StringRes
import com.neoprars.voice.engine.EngineException

/** Mensaje para el usuario según el tipo de error del motor. Compartido por el botón flotante y el teclado. */
@StringRes
fun EngineException.Kind.messageRes(): Int = when (this) {
    EngineException.Kind.AUDIO_TOO_SHORT -> R.string.too_short
    EngineException.Kind.AUDIO_SILENT -> R.string.silent
    EngineException.Kind.AUTH -> R.string.auth_error
    EngineException.Kind.RATE_LIMIT -> R.string.rate_limit
    EngineException.Kind.NETWORK -> R.string.network_error
    EngineException.Kind.SERVER -> R.string.server_error
    EngineException.Kind.NOT_CONFIGURED -> R.string.no_key
    EngineException.Kind.BAD_RESPONSE -> R.string.generic_error
}
