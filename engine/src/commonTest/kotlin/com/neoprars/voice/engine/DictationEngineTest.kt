package com.neoprars.voice.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DictationEngineTest {

    private class FakeTranscriber(var result: String = "eh, hola que que tal", var calls: Int = 0) : Transcriber {
        var lastAudio: EncodedAudio? = null
        override suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?): String {
            lastAudio = audio
            calls++
            return result
        }
    }

    private class FakePolisher(val result: () -> String) : Polisher {
        var calls = 0
        var warmUps = 0
        override suspend fun polish(text: String, instructions: String?, previousText: String?): String {
            calls++
            return result()
        }
        override suspend fun warmUp() { warmUps++ }
    }

    /** Pule siempre, sin importar cuántas palabras tenga el dictado. */
    private val always = DictationConfig(minWordsToPolish = 0)

    /** Onda cuadrada audible de [ms] milisegundos a 16 kHz. */
    private fun tone(ms: Int, amplitude: Int = 8000): ByteArray =
        Pcm16.fromShorts(ShortArray(16 * ms) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() })

    @Test fun rejectsTooShortAudioWithoutCallingApi() = runTest {
        val tr = FakeTranscriber()
        val e = assertFailsWith<EngineException> { DictationEngine(tr).dictate(tone(100)) }
        assertEquals(EngineException.Kind.AUDIO_TOO_SHORT, e.kind)
        assertEquals(0, tr.calls)
    }

    @Test fun rejectsSilenceWithoutCallingApi() = runTest {
        val tr = FakeTranscriber()
        val e = assertFailsWith<EngineException> { DictationEngine(tr).dictate(tone(1000, amplitude = 5)) }
        assertEquals(EngineException.Kind.AUDIO_SILENT, e.kind)
        assertEquals(0, tr.calls)
    }

    @Test fun withoutPolisherReturnsRuleCleanedText() = runTest {
        val r = DictationEngine(FakeTranscriber()).dictate(tone(1000))
        assertEquals("Hola que tal", r.text)
        assertEquals("eh, hola que que tal", r.rawText)
        assertFalse(r.polished)
        assertNull(r.polishError)
    }

    @Test fun usesPolishedTextWhenAvailable() = runTest {
        val r = DictationEngine(FakeTranscriber(), FakePolisher { "Hola, ¿qué tal?" }, always).dictate(tone(1000))
        assertEquals("Hola, ¿qué tal?", r.text)
        assertTrue(r.polished)
    }

    @Test fun fallsBackWhenPolisherFails() = runTest {
        val r = DictationEngine(
            FakeTranscriber(),
            FakePolisher { throw EngineException(EngineException.Kind.NETWORK, "sin red") },
            always,
        ).dictate(tone(1000))
        assertEquals("Hola que tal", r.text)
        assertFalse(r.polished)
        assertEquals("sin red", r.polishError)
    }

    @Test fun discardsImplausiblePolishOutput() = runTest {
        val r = DictationEngine(FakeTranscriber(), FakePolisher { "x".repeat(500) }, always).dictate(tone(1000))
        assertEquals("Hola que tal", r.text)
        assertFalse(r.polished)
    }

    @Test fun discardsEmptyPolishOutput() = runTest {
        val r = DictationEngine(FakeTranscriber(), FakePolisher { "  " }, always).dictate(tone(1000))
        assertEquals("Hola que tal", r.text)
        assertFalse(r.polished)
    }

    @Test fun emptyTranscriptionReturnsEmptyResult() = runTest {
        val r = DictationEngine(FakeTranscriber(result = "  "), FakePolisher { "no debería usarse" }).dictate(tone(1000))
        assertEquals("", r.text)
        assertFalse(r.polished)
    }

    @Test fun reportsTimingsWithoutPolish() = runTest {
        val r = DictationEngine(FakeTranscriber()).dictate(tone(1000))
        assertEquals(1000L, r.timings.audioMs)
        assertTrue(r.timings.sttMs >= 0)
        assertNull(r.timings.polishMs)
    }

    @Test fun reportsPolishTimingEvenWhenPolishFails() = runTest {
        val ok = DictationEngine(FakeTranscriber(), FakePolisher { "Hola, ¿qué tal?" }, always).dictate(tone(1000))
        assertTrue((ok.timings.polishMs ?: -1) >= 0)
        val failed = DictationEngine(
            FakeTranscriber(),
            FakePolisher { throw EngineException(EngineException.Kind.NETWORK, "sin red") },
            always,
        ).dictate(tone(1000))
        assertTrue((failed.timings.polishMs ?: -1) >= 0)
    }

    @Test fun skipsPolishForShortDictations() = runTest {
        val polisher = FakePolisher { "no debería usarse" }
        val r = DictationEngine(FakeTranscriber(), polisher).dictate(tone(1000)) // "Hola que tal": 3 palabras
        assertEquals("Hola que tal", r.text)
        assertFalse(r.polished)
        assertNull(r.polishError)
        assertNull(r.timings.polishMs)
        assertEquals(0, polisher.calls)
    }

    @Test fun polishesWhenDictationReachesMinWords() = runTest {
        val polisher = FakePolisher { "Uno, dos, tres, cuatro, cinco, seis." }
        val six = FakeTranscriber(result = "uno dos tres cuatro cinco seis")
        val r = DictationEngine(six, polisher).dictate(tone(1000))
        assertTrue(r.polished)
        assertEquals(1, polisher.calls)

        val five = FakeTranscriber(result = "uno dos tres cuatro cinco")
        assertFalse(DictationEngine(five, polisher).dictate(tone(1000)).polished)
        assertEquals(1, polisher.calls)
    }

    @Test fun minWordsIsConfigurable() = runTest {
        val polisher = FakePolisher { "Hola, ¿qué tal?" }
        val r = DictationEngine(FakeTranscriber(), polisher, DictationConfig(minWordsToPolish = 3)).dictate(tone(1000))
        assertTrue(r.polished)
    }

    @Test fun warmUpReachesBothProvidersAndNeverFails() = runTest {
        var transcriberWarmUps = 0
        val tr = object : Transcriber {
            override suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?) = ""
            override suspend fun warmUp() { transcriberWarmUps++ }
        }
        val polisher = FakePolisher { "" }
        DictationEngine(tr, polisher).warmUp()
        assertEquals(1, transcriberWarmUps)
        assertEquals(1, polisher.warmUps)
        DictationEngine(FakeTranscriber()).warmUp() // sin pulidor y con warmUp por defecto
    }

    @Test fun reportsUploadTimeWhenTranscriberMeasuresIt() = runTest {
        val measuring = object : Transcriber {
            override suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?) = "hola"
            override val lastUploadMs: Long? = 123
        }
        assertEquals(123L, DictationEngine(measuring).dictate(tone(1000)).timings.uploadMs)
        assertNull(DictationEngine(FakeTranscriber()).dictate(tone(1000)).timings.uploadMs)
    }

    @Test fun sendsWavByDefault() = runTest {
        val tr = FakeTranscriber()
        val r = DictationEngine(tr).dictate(tone(1000))
        assertEquals("audio/wav", tr.lastAudio!!.mimeType)
        assertEquals("audio.wav", tr.lastAudio!!.fileName)
        assertEquals(44 + 32_000, tr.lastAudio!!.bytes.size)
        assertEquals(44 + 32_000, r.timings.audioBytes)
        assertTrue(r.timings.encodeMs >= 0)
    }

    @Test fun usesTheGivenEncoder() = runTest {
        val tr = FakeTranscriber()
        val small = object : AudioEncoder {
            override suspend fun encode(pcm: ByteArray, sampleRate: Int) = EncodedAudio(ByteArray(10), "audio/mp4", "audio.m4a")
        }
        val r = DictationEngine(tr, encoder = small).dictate(tone(1000))
        assertEquals("audio/mp4", tr.lastAudio!!.mimeType)
        assertEquals("audio.m4a", tr.lastAudio!!.fileName)
        assertEquals(10, r.timings.audioBytes)
    }

    @Test fun fallsBackToWavWhenEncoderFails() = runTest {
        val tr = FakeTranscriber()
        val broken = object : AudioEncoder {
            override suspend fun encode(pcm: ByteArray, sampleRate: Int): EncodedAudio = throw IllegalStateException("sin códec")
        }
        val r = DictationEngine(tr, encoder = broken).dictate(tone(1000))
        assertEquals("audio/wav", tr.lastAudio!!.mimeType)
        assertEquals("Hola que tal", r.text)
    }

    @Test fun transcriptionErrorsPropagate() = runTest {
        val failing = object : Transcriber {
            override suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?): String =
                throw EngineException(EngineException.Kind.AUTH, "clave inválida")
        }
        val e = assertFailsWith<EngineException> { DictationEngine(failing).dictate(tone(1000)) }
        assertEquals(EngineException.Kind.AUTH, e.kind)
    }
}
