package com.neoprars.voice.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {

    @Test fun wavHeaderIsCorrect() {
        val pcm = ByteArray(32_000) // 1 s a 16 kHz
        val wav = WavEncoder.encode(pcm, 16_000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", wav.copyOfRange(0, 4).decodeToString())
        assertEquals("WAVE", wav.copyOfRange(8, 12).decodeToString())
        assertEquals("data", wav.copyOfRange(36, 40).decodeToString())
        assertEquals(1, wav[22].toInt())                       // mono
        assertEquals(16_000, le32(wav, 24))                    // sample rate
        assertEquals(32_000, le32(wav, 28))                    // byte rate
        assertEquals(pcm.size, le32(wav, 40))                  // tamaño de datos
        assertEquals(36 + pcm.size, le32(wav, 4))              // tamaño del RIFF
    }

    @Test fun rmsOfConstantSignal() {
        val pcm = Pcm16.fromShorts(ShortArray(100) { 16384 })
        assertTrue(kotlin.math.abs(Pcm16.rms(pcm) - 0.5) < 1e-9)
    }

    @Test fun rmsHandlesNegativeSamples() {
        val pcm = Pcm16.fromShorts(ShortArray(100) { -16384 })
        assertTrue(kotlin.math.abs(Pcm16.rms(pcm) - 0.5) < 1e-9)
    }

    @Test fun rmsOfEmptyIsZero() = assertEquals(0.0, Pcm16.rms(ByteArray(0)))

    @Test fun durationInMs() = assertEquals(1000L, Pcm16.durationMs(ByteArray(32_000), 16_000))

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
