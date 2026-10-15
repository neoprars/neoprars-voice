package com.neoprars.voice.engine

import kotlin.math.sqrt

/**
 * Detecta silencio sostenido en audio PCM16 que llega por tramos, para cortar sola una grabación
 * cuando la persona dejó de hablar. No guarda audio: solo cuenta muestras.
 */
class SilenceDetector(
    sampleRate: Int,
    silenceMs: Long = DEFAULT_SILENCE_MS,
    /** Un tramo con nivel RMS (en [0, 1]) menor que esto cuenta como silencio. */
    private val threshold: Double = DEFAULT_THRESHOLD,
) {

    companion object {
        const val DEFAULT_SILENCE_MS = 5_000L
        const val DEFAULT_THRESHOLD = 0.01
    }

    private val limit: Long = sampleRate * silenceMs / 1000L

    /** Muestras de silencio seguidas al final de lo recibido hasta ahora. */
    var silentSamples: Long = 0
        private set

    fun reset() {
        silentSamples = 0
    }

    /** Recibe el tramo siguiente. Devuelve true cuando el silencio acumulado llegó al límite. */
    fun feed(samples: ShortArray, count: Int = samples.size): Boolean {
        if (count <= 0) return silentSamples >= limit
        var sum = 0.0
        for (i in 0 until count) {
            val f = samples[i] / 32768.0
            sum += f * f
        }
        return feedLevel(sqrt(sum / count), count)
    }

    /** Igual que [feed], para quien ya calculó el nivel RMS del tramo de [count] muestras. */
    fun feedLevel(rms: Double, count: Int): Boolean {
        if (count > 0) {
            if (isSilent(rms)) silentSamples += count else silentSamples = 0
        }
        return silentSamples >= limit
    }

    fun isSilent(rms: Double): Boolean = rms < threshold
}
