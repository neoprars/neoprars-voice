package com.neoprars.voice.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkedDictationSessionTest {

    /** Cada tramo de prueba es un tono con su propia amplitud: así el transcriptor falso sabe qué tramo recibió. */
    /**
     * Cómo responde el transcriptor falso a un tramo: [delays] y [failures] van por intento (null = sin fallo);
     * los intentos sin demora propia usan [delayMs].
     */
    private class Script(
        val delayMs: Long = 0,
        val text: String = "",
        val failures: List<EngineException?> = emptyList(),
        val delays: List<Long> = emptyList(),
    )

    private class FakeTranscriber(val scripts: Map<Int, Script>) : Transcriber {
        val completed = mutableListOf<Int>()
        val attempts = mutableMapOf<Int, Int>()
        var running = 0
        var maxRunning = 0

        override suspend fun transcribe(audio: EncodedAudio, language: String?, vocabularyHint: String?): String {
            val amp = amplitude(audio)
            val script = scripts.getValue(amp)
            val attempt = attempts.getOrElse(amp) { 0 }
            attempts[amp] = attempt + 1
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                delay(script.delays.getOrElse(attempt) { script.delayMs })
                script.failures.getOrNull(attempt)?.let { throw it }
            } finally {
                running--
            }
            completed += amp
            return script.text
        }

        /** Máximo valor absoluto del PCM dentro del WAV (cabecera de 44 bytes). */
        private fun amplitude(audio: EncodedAudio): Int {
            var max = 0
            var i = 44
            while (i + 1 < audio.bytes.size) {
                val v = (audio.bytes[i + 1].toInt() shl 8) or (audio.bytes[i].toInt() and 0xFF)
                max = maxOf(max, abs(v))
                i += 2
            }
            return max
        }
    }

    /** Devuelve el texto tal cual y anota qué contexto recibió cada texto. */
    private class EchoPolisher : Polisher {
        val contexts = mutableMapOf<String, String?>()
        override suspend fun polish(text: String, instructions: String?, previousText: String?): String {
            contexts[text] = previousText
            return text
        }
    }

    private val pause = 704 // ms: 11 bloques de 64 ms, apenas más que chunkSilenceMs (700)

    private fun tone(ms: Int, amplitude: Int): ByteArray =
        Pcm16.fromShorts(ShortArray(16 * ms) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() })

    private fun silence(ms: Int): ByteArray = ByteArray(32 * ms)

    /** Manda el audio en bloques de 1024 muestras, como la grabadora. */
    private fun ChunkedDictationSession.pushAll(vararg parts: ByteArray) {
        for (part in parts) {
            var i = 0
            while (i < part.size) {
                val end = minOf(i + 2048, part.size)
                push(part.copyOfRange(i, end))
                i = end
            }
        }
    }

    private val three = mapOf(
        1000 to Script(delayMs = 500, text = "primero"),
        2000 to Script(delayMs = 10, text = "segundo"),
        3000 to Script(delayMs = 10, text = "tercero"),
    )

    private fun TestScope.session(
        transcriber: Transcriber,
        polisher: Polisher? = null,
        config: DictationConfig = DictationConfig(),
    ) = DictationEngine(transcriber, polisher, config).startSession(this)

    @Test fun keepsOrderWhenALaterChunkFinishesFirst() = runTest {
        val tr = FakeTranscriber(three)
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(3200, 2000), silence(pause), tone(1600, 3000))
        val r = s.finish()

        assertTrue(tr.completed.indexOf(2000) < tr.completed.indexOf(1000), "el tramo 2 debía terminar antes")
        assertEquals("Primero Segundo Tercero", r.text)
        assertEquals(3, r.chunks.size)
        assertEquals(listOf(0, 1, 2), r.chunks.map { it.index })
        assertTrue(r.chunks.all { it.status == ChunkTimings.Status.OK })
        assertNull(r.chunkError)
    }

    @Test fun joinsChunksWithOneSpaceAndRespectsLineBreaks() {
        assertEquals("Hola. Qué tal", ChunkedDictationSession.joinChunks(listOf("Hola.", "Qué tal")))
        assertEquals("Uno\ndos", ChunkedDictationSession.joinChunks(listOf("Uno\n", "dos")))
        assertEquals("Uno\ndos", ChunkedDictationSession.joinChunks(listOf("Uno", "\ndos")))
        assertEquals("a b", ChunkedDictationSession.joinChunks(listOf("", " a ", "  ", "b ")))
        assertEquals("", ChunkedDictationSession.joinChunks(emptyList()))
    }

    @Test fun aFailedChunkKeepsTheRestAndReportsTheError() = runTest {
        val network = EngineException(EngineException.Kind.NETWORK, "sin red")
        val tr = FakeTranscriber(three + (2000 to Script(text = "segundo", failures = listOf(network, network))))
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(3200, 2000), silence(pause), tone(1600, 3000))
        val r = s.finish()

        assertEquals("Primero Tercero", r.text)
        assertEquals(EngineException.Kind.NETWORK, r.chunkError?.kind)
        assertEquals(ChunkTimings.Status.FAILED, r.chunks[1].status)
        assertEquals(1, r.chunks[1].retries)
        assertEquals(2, tr.attempts[2000])
    }

    @Test fun aShortRetryRecoversATransientFailure() = runTest {
        val server = EngineException(EngineException.Kind.SERVER, "HTTP 503")
        val tr = FakeTranscriber(three + (2000 to Script(text = "segundo", failures = listOf(server))))
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(3200, 2000), silence(pause), tone(1600, 3000))
        val r = s.finish()

        assertEquals("Primero Segundo Tercero", r.text)
        assertNull(r.chunkError)
        assertEquals(1, r.chunks[1].retries)
    }

    @Test fun keyErrorsAreNotRetried() = runTest {
        val auth = EngineException(EngineException.Kind.AUTH, "clave")
        val tr = FakeTranscriber(three + (2000 to Script(failures = listOf(auth))))
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(3200, 2000), silence(pause), tone(1600, 3000))
        val r = s.finish()

        assertEquals(EngineException.Kind.AUTH, r.chunkError?.kind)
        assertEquals(1, tr.attempts[2000])
    }

    @Test fun whenEveryChunkFailsTheErrorIsThrown() = runTest {
        val auth = EngineException(EngineException.Kind.AUTH, "clave")
        val tr = FakeTranscriber(
            mapOf(1000 to Script(failures = listOf(auth)), 2000 to Script(failures = listOf(auth))),
        )
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(1600, 2000))
        val e = assertFailsWith<EngineException> { s.finish() }
        assertEquals(EngineException.Kind.AUTH, e.kind)
    }

    @Test fun cancelDiscardsEverything() = runTest {
        val tr = FakeTranscriber(three)
        val polisher = EchoPolisher()
        val s = session(tr, polisher, DictationConfig(minWordsToPolish = 0))
        s.pushAll(tone(3200, 1000), silence(pause), tone(3200, 2000), silence(pause))
        advanceUntilIdle()
        val before = tr.completed.size
        s.pushAll(tone(1600, 3000))
        s.cancel()
        advanceUntilIdle()

        assertEquals(before, tr.completed.size, "nada sigue después de cancelar")
        assertFalse(tr.attempts.containsKey(3000), "el audio posterior no se transcribe")
    }

    @Test fun cancelStopsChunksInFlight() = runTest {
        val tr = FakeTranscriber(three)
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause))
        runCurrent()
        assertEquals(1, tr.attempts[1000], "el tramo ya se estaba transcribiendo")
        s.cancel()
        advanceUntilIdle()
        assertTrue(tr.completed.isEmpty())
    }

    @Test fun aSingleChunkBehavesLikeDictate() = runTest {
        val polisher = EchoPolisher()
        val short = FakeTranscriber(mapOf(1000 to Script(text = "eh, hola que que tal")))
        val r = session(short, polisher).also { it.pushAll(tone(2000, 1000)) }.finish()

        val expected = DictationEngine(FakeTranscriber(mapOf(1000 to Script(text = "eh, hola que que tal"))), EchoPolisher())
            .dictate(tone(2000, 1000))
        assertEquals(expected.text, r.text)
        assertEquals("Hola que tal", r.text)
        assertFalse(r.polished, "menos de 6 palabras: no se pule")
        assertTrue(polisher.contexts.isEmpty())
        assertTrue(r.chunks.isEmpty())

        val long = FakeTranscriber(mapOf(1000 to Script(text = "uno dos tres cuatro cinco seis")))
        val r2 = session(long, EchoPolisher()).also { it.pushAll(tone(2000, 1000)) }.finish()
        assertTrue(r2.polished)
    }

    @Test fun aSingleChunkKeepsTheQualityGates() = runTest {
        val tr = FakeTranscriber(emptyMap())
        val e = assertFailsWith<EngineException> { session(tr).also { it.pushAll(tone(100, 1000)) }.finish() }
        assertEquals(EngineException.Kind.AUDIO_TOO_SHORT, e.kind)
    }

    @Test fun trailingSilenceIsNotTranscribed() = runTest {
        // Como el corte automático: habla 3,2 s y después 5 s de silencio.
        val tr = FakeTranscriber(mapOf(1000 to Script(text = "hola")))
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), silence(5000))
        val r = s.finish()

        assertEquals("Hola", r.text)
        assertEquals(ChunkTimings.Status.OK, r.chunks.first().status)
        assertTrue(r.chunks.drop(1).all { it.status == ChunkTimings.Status.SKIPPED })
        assertEquals(1, tr.attempts.values.sum())
    }

    @Test fun onlySilenceIsReportedAsSilent() = runTest {
        val s = session(FakeTranscriber(emptyMap()))
        s.pushAll(silence(4000), silence(4000))
        val e = assertFailsWith<EngineException> { s.finish() }
        assertEquals(EngineException.Kind.AUDIO_SILENT, e.kind)
    }

    @Test fun polishReceivesTheEndOfThePreviousChunkAsContext() = runTest {
        val polisher = EchoPolisher()
        val s = session(FakeTranscriber(three), polisher, DictationConfig(minWordsToPolish = 0))
        s.pushAll(tone(3200, 1000), silence(pause), tone(3200, 2000), silence(pause), tone(1600, 3000))
        val r = s.finish()

        assertEquals("Primero Segundo Tercero", r.text)
        assertTrue(r.polished)
        assertEquals(mapOf("Primero" to null, "Segundo" to "Primero", "Tercero" to "Segundo"), polisher.contexts)
    }

    @Test fun contextIsTheTailOfThePreviousTextStartingAtAWord() {
        assertEquals("corto", ChunkedDictationSession.tail(" corto ", 200))
        assertEquals("cinco seis", ChunkedDictationSession.tail("uno dos tres cuatro cinco seis", 12))
    }

    @Test fun cutsWithoutPausesAtTheMaximumLength() = runTest {
        val tr = FakeTranscriber(mapOf(1000 to Script(text = "x")))
        val s = session(tr)
        s.pushAll(tone(60_000, 1000))
        val r = s.finish()

        assertEquals(5, r.chunks.size) // 12 + 12 + 12 + 12 + 12 s
        assertTrue(r.chunks.take(4).all { it.audioMs in 12_000..12_100 })
        assertEquals(5, tr.attempts.values.sum())
    }

    @Test fun forcedCutLandsOnTheQuietestMoment() = runTest {
        val tr = FakeTranscriber(mapOf(1000 to Script(text = "x"), 50 to Script(text = "y")))
        val s = session(tr)
        // 11,456 s de voz, un instante casi en silencio (64 ms) y más voz: el corte cae al final de ese instante.
        s.pushAll(tone(11_456, 1000), tone(64, 50), tone(5_000, 1000))
        val r = s.finish()

        assertEquals(11_520, r.chunks[0].audioMs)
    }

    // ---- Umbral de pausa adaptativo ----

    /** Ruido de fondo constante (onda cuadrada de baja amplitud). */
    private fun noise(ms: Int, amplitude: Int = 600): ByteArray = tone(ms, amplitude)

    @Test fun pausesAreDetectedOverBackgroundNoise() = runTest {
        // Ruido de ~0,018 RMS: con el umbral fijo de 0,01 las pausas nunca se verían.
        val scripts = mapOf(4000 to Script(text = "uno"), 5000 to Script(text = "dos"))
        val s = session(FakeTranscriber(scripts))
        s.pushAll(noise(320), tone(3200, 4000), noise(pause), tone(1600, 5000))
        val r = s.finish()

        assertEquals("Uno Dos", r.text)
        assertEquals(2, r.chunks.size)
        assertEquals(600 / 32768.0, r.noiseRms!!, 0.001)
        assertEquals(0.03, r.silenceThreshold!!, 0.0001) // 2 × ruido, acotado al máximo
    }

    @Test fun withAFixedThresholdTheNoiseHidesThePauses() = runTest {
        val scripts = mapOf(5000 to Script(text = "todo junto"))
        val fixed = DictationConfig(silenceThresholdMax = SilenceDetector.DEFAULT_THRESHOLD)
        val s = session(FakeTranscriber(scripts), config = fixed)
        s.pushAll(noise(320), tone(3200, 4000), noise(pause), tone(1600, 5000))
        val r = s.finish()

        assertTrue(r.chunks.isEmpty(), "sin pausas detectadas queda un solo tramo")
        assertEquals("Todo junto", r.text)
    }

    @Test fun pauseThresholdIsNoisePlusMarginWithinBounds() {
        val c = DictationConfig()
        assertEquals(0.01, ChunkedDictationSession.pauseThresholdFor(0.001, c), 1e-9) // mínimo
        assertEquals(0.024, ChunkedDictationSession.pauseThresholdFor(0.012, c), 1e-9)
        assertEquals(0.03, ChunkedDictationSession.pauseThresholdFor(0.5, c), 1e-9) // máximo
    }

    @Test fun aSingleChunkAlsoReportsTheNoiseLevel() = runTest {
        val s = session(FakeTranscriber(mapOf(4000 to Script(text = "hola"))))
        s.pushAll(noise(320), tone(1000, 4000))
        val r = s.finish()
        assertTrue(r.chunks.isEmpty())
        assertEquals(600 / 32768.0, r.noiseRms!!, 0.001)
    }

    // ---- Reenvío por lentitud ----

    private val slowFirst = mapOf(
        1000 to Script(text = "uno", delays = listOf(10_000, 100)),
        2000 to Script(text = "dos"),
    )

    @Test fun aSlowTranscriptionIsSentAgainAndTheFirstAnswerWins() = runTest {
        val tr = FakeTranscriber(slowFirst)
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(1600, 2000))
        val r = s.finish()

        assertEquals("Uno Dos", r.text)
        assertTrue(r.chunks[0].hedged)
        assertFalse(r.chunks[1].hedged)
        assertEquals(2, tr.attempts[1000])
        assertTrue(currentTime < 5_000, "no esperó a la respuesta lenta: $currentTime ms")
    }

    @Test fun fastTranscriptionsAreNotSentTwice() = runTest {
        val tr = FakeTranscriber(mapOf(1000 to Script(delayMs = 3_000, text = "uno"), 2000 to Script(text = "dos")))
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(1600, 2000))
        val r = s.finish()
        assertFalse(r.chunks[0].hedged)
        assertEquals(1, tr.attempts[1000])
    }

    @Test fun atMostOneExtraRequestPerChunk() = runTest {
        val bothSlow = mapOf(1000 to Script(delayMs = 10_000, text = "uno"), 2000 to Script(text = "dos"))
        val tr = FakeTranscriber(bothSlow)
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(1600, 2000))
        val r = s.finish()

        assertEquals("Uno Dos", r.text)
        assertEquals(2, tr.attempts[1000])
        assertTrue(currentTime in 10_000..11_000, "usó la primera respuesta: $currentTime ms")
    }

    @Test fun ifTheExtraRequestFailsTheSlowOneStillCounts() = runTest {
        val network = EngineException(EngineException.Kind.NETWORK, "sin red")
        val scripts = mapOf(
            1000 to Script(text = "uno", delays = listOf(6_000, 10), failures = listOf(null, network)),
            2000 to Script(text = "dos"),
        )
        val tr = FakeTranscriber(scripts)
        val s = session(tr)
        s.pushAll(tone(3200, 1000), silence(pause), tone(1600, 2000))
        val r = s.finish()

        assertEquals("Uno Dos", r.text)
        assertNull(r.chunkError)
        assertTrue(r.chunks[0].hedged)
    }

    @Test fun hedgingCanBeTurnedOff() = runTest {
        val tr = FakeTranscriber(slowFirst)
        val s = session(tr, config = DictationConfig(hedgeAfterMs = null))
        s.pushAll(tone(3200, 1000), silence(pause), tone(1600, 2000))
        val r = s.finish()

        assertFalse(r.chunks[0].hedged)
        assertEquals(1, tr.attempts[1000])
        assertTrue(currentTime >= 10_000)
    }

    @Test fun neverRunsMoreChunksAtOnceThanAllowed() = runTest {
        val scripts = (1..5).associate { it * 1000 to Script(delayMs = 100, text = "t$it") }
        val tr = FakeTranscriber(scripts)
        val s = session(tr, config = DictationConfig(maxParallelChunks = 2))
        for (i in 1..5) s.pushAll(tone(3200, i * 1000), silence(pause))
        val r = s.finish()

        assertEquals(2, tr.maxRunning)
        assertEquals("T1 T2 T3 T4 T5", r.text)
    }

    @Test fun polishPromptCarriesThePreviousTextOnlyWhenThereIsOne() {
        assertEquals("<dictado>\nb\n</dictado>", PolishPrompt.user("b"))
        assertEquals("<anterior>\na\n</anterior>\n<dictado>\nb\n</dictado>", PolishPrompt.user("b", " a "))
        assertTrue(PolishPrompt.system(null).contains("<anterior>"))
    }
}
