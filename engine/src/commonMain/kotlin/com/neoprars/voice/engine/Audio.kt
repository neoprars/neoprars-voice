package com.neoprars.voice.engine

import kotlin.math.sqrt

/** Utilidades para audio PCM de 16 bits little-endian, mono. */
object Pcm16 {

    fun durationMs(pcm: ByteArray, sampleRate: Int): Long = pcm.size / 2L * 1000L / sampleRate

    /** Nivel RMS normalizado en [0, 1]. */
    fun rms(pcm: ByteArray): Double {
        val n = pcm.size / 2
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt() // con signo
            val v = (hi shl 8) or lo
            val f = v / 32768.0
            sum += f * f
        }
        return sqrt(sum / n)
    }

    fun fromShorts(samples: ShortArray, count: Int = samples.size): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}

object WavEncoder {

    /** Envuelve PCM16 en un contenedor WAV (cabecera de 44 bytes). */
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val out = ByteArray(44 + pcm.size)

        fun text(offset: Int, s: String) {
            for (i in s.indices) out[offset + i] = s[i].code.toByte()
        }
        fun le16(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        fun le32(offset: Int, v: Int) {
            le16(offset, v and 0xFFFF)
            le16(offset + 2, (v shr 16) and 0xFFFF)
        }

        text(0, "RIFF")
        le32(4, 36 + pcm.size)
        text(8, "WAVE")
        text(12, "fmt ")
        le32(16, 16)                          // tamaño del bloque fmt
        le16(20, 1)                           // PCM
        le16(22, channels)
        le32(24, sampleRate)
        le32(28, sampleRate * channels * 2)   // byte rate
        le16(32, channels * 2)                // block align
        le16(34, 16)                          // bits por muestra
        text(36, "data")
        le32(40, pcm.size)
        pcm.copyInto(out, 44)
        return out
    }
}
