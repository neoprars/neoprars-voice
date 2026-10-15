package com.neoprars.voice.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

class DictationConfig(
    val sampleRate: Int = 16_000,
    /** Audio más corto que esto se descarta sin llamar a la API. */
    val minAudioMs: Long = 400,
    /** Audio más silencioso que esto se descarta: los modelos inventan texto con silencio. */
    val minRms: Double = 0.005,
    val language: String? = "es",
    /** Vocabulario/nombres propios para orientar la transcripción. */
    val vocabularyHint: String? = null,
    /** Preferencias de estilo para el pulido ("tuteá", "tono formal"…). */
    val polishInstructions: String? = null,
    /** Dictados con menos palabras que esto no se pulen: se entrega el texto limpio por reglas (más rápido). */
    val minWordsToPolish: Int = 6,

    // ---- Dictado por tramos (ver ChunkedDictationSession) ----
    /** Silencio que cierra un tramo mientras se graba. */
    val chunkSilenceMs: Long = 700,
    /** Un tramo no se cierra por una pausa antes de tener este audio. */
    val minChunkMs: Long = 3_000,
    /** Si no hay pausas, el tramo se corta igual al llegar a este largo (en el momento más silencioso del final). */
    val maxChunkMs: Long = 12_000,
    /** Ventana al final del tramo donde se busca el punto más silencioso para el corte forzado. */
    val forcedCutWindowMs: Long = 1_500,
    /**
     * Umbral de silencio adaptativo para detectar pausas: se mide el ruido de fondo en el comienzo de la grabación
     * (el bloque más silencioso de estos ms) y el umbral queda en ruido × [silenceNoiseFactor], acotado entre
     * [silenceThresholdMin] y [silenceThresholdMax] (niveles RMS en [0, 1]).
     */
    val noiseCalibrationMs: Long = 300,
    val silenceNoiseFactor: Double = 2.0,
    /** También es el nivel fijo que decide si un tramo tiene voz (para no descartar voz baja si el ruido es alto). */
    val silenceThresholdMin: Double = SilenceDetector.DEFAULT_THRESHOLD,
    val silenceThresholdMax: Double = 0.03,
    /** Tramos que pueden estar transcribiéndose o puliéndose a la vez. */
    val maxParallelChunks: Int = 2,
    /** Reintentos de la transcripción de un tramo ante errores de red o del servidor. */
    val chunkRetries: Int = 1,
    val chunkRetryDelayMs: Long = 300,
    /**
     * Si la transcripción de un tramo tarda más que esto, se manda el mismo audio otra vez en paralelo y se usa la
     * primera respuesta (como mucho un reenvío por tramo; no cuenta para [maxParallelChunks]). null = desactivado.
     */
    val hedgeAfterMs: Long? = 4_000,
    /** Caracteres del final del tramo anterior que recibe el pulido como contexto. */
    val polishContextChars: Int = 200,
)

class DictationResult(
    /** Texto final listo para insertar. */
    val text: String,
    val rawText: String,
    /** true si el texto pasó por el pulido con IA. */
    val polished: Boolean,
    /** Motivo por el que el pulido no se aplicó (si falló). */
    val polishError: String? = null,
    /** Duraciones para medir latencia (nunca contenido). */
    val timings: DictationTimings = DictationTimings(),
    /** Dictado por tramos: duraciones de cada tramo, en orden. Vacío si se dictó de una sola vez. */
    val chunks: List<ChunkTimings> = emptyList(),
    /** Dictado por tramos: error del primer tramo que no se pudo transcribir (el resto del texto sí se entrega). */
    val chunkError: EngineException? = null,
    /** Dictado por tramos: ruido de fondo medido al empezar y umbral de pausa que se usó (niveles RMS). */
    val noiseRms: Double? = null,
    val silenceThreshold: Double? = null,
)

class DictationTimings(
    /** Duración del audio recibido. */
    val audioMs: Long = 0,
    /** Tiempo de la transcripción (subida + espera de la respuesta). */
    val sttMs: Long = 0,
    /** Parte de [sttMs] que fue subir el audio; null si el transcriptor no lo mide. */
    val uploadMs: Long? = null,
    /** Tiempo del pulido con IA; null si no se intentó. */
    val polishMs: Long? = null,
    /** Tiempo de empaquetar/comprimir el audio antes de subirlo. */
    val encodeMs: Long = 0,
    /** Tamaño del audio enviado. */
    val audioBytes: Int = 0,
)

/**
 * Pipeline completo: audio PCM16 → (puerta de calidad) → empaquetado (WAV o comprimido) → transcripción →
 * limpieza por reglas → pulido opcional con IA. No depende de ninguna plataforma.
 */
class DictationEngine(
    private val transcriber: Transcriber,
    private val polisher: Polisher? = null,
    private val config: DictationConfig = DictationConfig(),
    private val encoder: AudioEncoder = WavAudioEncoder,
) {

    /** @param pcm audio PCM de 16 bits little-endian, mono, a `config.sampleRate`. */
    suspend fun dictate(pcm: ByteArray): DictationResult {
        val audioMs = Pcm16.durationMs(pcm, config.sampleRate)
        if (audioMs < config.minAudioMs) {
            throw EngineException(EngineException.Kind.AUDIO_TOO_SHORT, "Audio demasiado corto.")
        }
        if (Pcm16.rms(pcm) < config.minRms) {
            throw EngineException(EngineException.Kind.AUDIO_SILENT, "No se detectó voz.")
        }
        val transcript = transcribe(pcm)
        if (transcript.raw.isEmpty()) return DictationResult("", "", polished = false, timings = transcript.timings())
        return polish(transcript, previousText = null)
    }

    /**
     * Empieza un dictado por tramos: el audio se manda con [ChunkedDictationSession.push] mientras se graba
     * y cada pausa cierra un tramo que se transcribe (y pule) en segundo plano dentro de [scope].
     */
    fun startSession(scope: CoroutineScope): ChunkedDictationSession = ChunkedDictationSession(this, config, scope)

    /** Resultado de la transcripción de un audio, antes del pulido. */
    internal class Transcript(
        val raw: String,
        val ruled: String,
        val audioMs: Long,
        val sttMs: Long,
        val uploadMs: Long?,
        val encodeMs: Long,
        val audioBytes: Int,
    ) {
        fun timings(polishMs: Long? = null) = DictationTimings(audioMs, sttMs, uploadMs, polishMs, encodeMs, audioBytes)
    }

    /** Empaqueta y transcribe, sin puertas de calidad (las aplica quien llama). */
    internal suspend fun transcribe(pcm: ByteArray): Transcript {
        val audioMs = Pcm16.durationMs(pcm, config.sampleRate)
        val encodeStart = TimeSource.Monotonic.markNow()
        val audio = try {
            encoder.encode(pcm, config.sampleRate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Si el codificador de la plataforma falla, se manda WAV: más pesado, pero siempre funciona.
            WavAudioEncoder.encode(pcm, config.sampleRate)
        }
        val encodeMs = encodeStart.elapsedNow().inWholeMilliseconds

        val sttStart = TimeSource.Monotonic.markNow()
        val raw = transcriber.transcribe(audio, config.language, config.vocabularyHint).trim()
        val sttMs = sttStart.elapsedNow().inWholeMilliseconds
        // Con varios tramos en paralelo puede ser la subida de otro tramo: es solo para medir.
        val uploadMs = transcriber.lastUploadMs
        return Transcript(raw, RuleCleaner.clean(raw), audioMs, sttMs, uploadMs, encodeMs, audio.bytes.size)
    }

    /** Limpieza por reglas + pulido opcional (salteado por debajo de `minWordsToPolish` palabras). */
    internal suspend fun polish(transcript: Transcript, previousText: String?): DictationResult {
        val raw = transcript.raw
        val ruled = transcript.ruled
        if (polisher == null || wordCount(ruled) < config.minWordsToPolish) {
            return DictationResult(ruled, raw, polished = false, timings = transcript.timings())
        }

        val polishStart = TimeSource.Monotonic.markNow()
        fun timings() = transcript.timings(polishStart.elapsedNow().inWholeMilliseconds)
        return try {
            val polished = polisher.polish(ruled, config.polishInstructions, previousText).trim()
            if (isPlausible(ruled, polished)) {
                DictationResult(polished, raw, polished = true, timings = timings())
            } else {
                DictationResult(
                    ruled, raw, polished = false, polishError = "Respuesta del pulido descartada.", timings = timings(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // El pulido es opcional: ante cualquier falla se entrega el texto limpio por reglas.
            DictationResult(ruled, raw, polished = false, polishError = e.message, timings = timings())
        }
    }

    /** Abre las conexiones de los proveedores por adelantado (por ejemplo, al empezar a grabar). Nunca falla. */
    suspend fun warmUp(): Unit = coroutineScope {
        launch { transcriber.warmUp() }
        if (polisher != null) launch { polisher.warmUp() }
    }

    private fun wordCount(text: String): Int {
        var count = 0
        var inWord = false
        for (c in text) {
            if (c.isWhitespace()) inWord = false
            else if (!inWord) { inWord = true; count++ }
        }
        return count
    }

    /** Defensa contra respuestas vacías o que se fueron de tema (mucho más largas que la entrada). */
    private fun isPlausible(source: String, output: String): Boolean =
        output.isNotEmpty() && output.length <= source.length * 2 + 40
}
