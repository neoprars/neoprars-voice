package com.neoprars.voice.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SilenceDetectorTest {

    private val rate = 16_000
    private val silence = ShortArray(1_600)             // 100 ms de ceros
    private val voice = ShortArray(1_600) { 3_000 }     // 100 ms con nivel ~0,09

    @Test fun triggersAfterFiveSecondsOfSilence() {
        val detector = SilenceDetector(rate)
        repeat(49) { assertFalse(detector.feed(silence)) }
        assertTrue(detector.feed(silence))
        assertEquals(80_000L, detector.silentSamples)
    }

    @Test fun voiceResetsTheCount() {
        val detector = SilenceDetector(rate)
        repeat(40) { detector.feed(silence) }
        assertFalse(detector.feed(voice))
        assertEquals(0L, detector.silentSamples)
        repeat(49) { assertFalse(detector.feed(silence)) }
        assertTrue(detector.feed(silence))
    }

    @Test fun neverTriggersWhileThereIsVoice() {
        val detector = SilenceDetector(rate)
        repeat(200) { assertFalse(detector.feed(voice)) }
    }

    @Test fun quietNoiseBelowThresholdCountsAsSilence() {
        val detector = SilenceDetector(rate, silenceMs = 200)
        val noise = ShortArray(1_600) { 100 }            // nivel ~0,003
        assertFalse(detector.feed(noise))
        assertTrue(detector.feed(noise))
    }

    @Test fun usesOnlyTheGivenCount() {
        val detector = SilenceDetector(rate, silenceMs = 100)
        val chunk = ShortArray(1_600) { i -> if (i < 800) 0 else 3_000 }
        assertFalse(detector.feed(chunk, 800))
        assertEquals(800L, detector.silentSamples)
        assertTrue(detector.feed(chunk, 800))
    }

    @Test fun emptyChunkChangesNothing() {
        val detector = SilenceDetector(rate)
        assertFalse(detector.feed(silence, 0))
        assertEquals(0L, detector.silentSamples)
    }

    @Test fun resetStartsOver() {
        val detector = SilenceDetector(rate, silenceMs = 100)
        assertTrue(detector.feed(silence))
        detector.reset()
        assertEquals(0L, detector.silentSamples)
    }
}
