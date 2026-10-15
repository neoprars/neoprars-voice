package com.neoprars.voice.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.cancellation.CancellationException

/** Duraciones de un tramo (nunca contenido). */
class ChunkTimings(
    /** Posición del tramo en el dictado, desde 0. */
    val index: Int,
    val audioMs: Long,
    val status: Status,
    val encodeMs: Long = 0,
    val sttMs: Long = 0,
    /** null si no se intentó pulir. */
    val polishMs: Long? = null,
    val audioBytes: Int = 0,
    /** Reintentos de la transcripción. */
    val retries: Int = 0,
    /** true si se reenvió el audio en paralelo porque la primera transcripción tardaba. */
    val hedged: Boolean = false,
) {
    enum class Status {
        OK,
        /** Sin voz suficiente (por ejemplo, la cola de silencio al final): no se mandó a transcribir. */
        SKIPPED,
        /** La transcripción falló incluso tras los reintentos. */
        FAILED,
    }
}

/**
 * Dictado por tramos: mientras se graba, cada pausa (ver `DictationConfig.chunkSilenceMs`) cierra un tramo que
 * se transcribe y pule en segundo plano. Al parar solo queda esperar el último tramo, no el dictado entero.
 *
 * - [push] recibe el audio a medida que se graba (se puede llamar desde cualquier hilo).
 * - [finish] cierra el último tramo, espera los pendientes y une los textos en orden.
 * - [cancel] descarta todo.
 *
 * Si nunca se llegó a cortar un tramo, [finish] equivale a `DictationEngine.dictate` con todo el audio.
 * Se crea con `DictationEngine.startSession`.
 */
class ChunkedDictationSession internal constructor(
    private val engine: DictationEngine,
    private val config: DictationConfig,
    parentScope: CoroutineScope,
) {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val input = Channel<ByteArray>(Channel.UNLIMITED)
    private val permits = Semaphore(config.maxParallelChunks.coerceAtLeast(1))

    // Estado del cortador: solo lo toca la corrutina que consume [input] y, cuando ésta terminó, [finish].
    private val current = PcmBuffer()
    private val blocks = ArrayList<Block>()
    private var silence = SilenceDetector(config.sampleRate, config.chunkSilenceMs, config.silenceThresholdMin)
    private val chunks = ArrayList<Chunk>()

    // Calibración del umbral de pausa con el ruido del comienzo.
    private var calibrationBytes = 0
    private var quietestStartRms = Double.MAX_VALUE
    private var noiseRms: Double? = null
    private var pauseThreshold = config.silenceThresholdMin

    private val cutter = scope.launch { for (pcm in input) accept(pcm) }

    /** Audio PCM16 little-endian mono a `config.sampleRate`. No modificar el arreglo después de pasarlo. */
    fun push(pcm: ByteArray) {
        input.trySend(pcm) // si la sesión ya terminó, el audio se descarta
    }

    /**
     * Devuelve el texto completo. Si algún tramo falló pero otros no, entrega el resto y deja el error en
     * [DictationResult.chunkError]; si no se pudo transcribir ninguno, lanza el error del primero.
     */
    suspend fun finish(): DictationResult {
        try {
            input.close()
            cutter.join()
            if (noiseRms == null && calibrationBytes > 0) noiseRms = quietestStartRms
            if (chunks.isEmpty()) return engine.dictate(current.take(current.size)).withLevels()
            if (current.size > 0) cut(current.size)
            return combine(chunks.map { it.outcome.await() })
        } catch (e: Throwable) {
            job.cancel()
            throw e
        } finally {
            job.complete()
        }
    }

    /** Descarta el audio y los tramos en curso. */
    fun cancel() {
        input.close()
        job.cancel()
    }

    // ---- Corte en tramos ----

    /** Un bloque tal como llegó por [push]: dónde termina dentro del tramo actual y su nivel. */
    private class Block(var end: Int, val bytes: Int, val rms: Double)

    private fun accept(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val level = Pcm16.rms(pcm)
        calibrate(level, pcm.size)
        current.append(pcm)
        blocks.add(Block(current.size, pcm.size, level))
        val pause = silence.feedLevel(level, pcm.size / 2)
        val ms = msOf(current.size)
        when {
            pause && ms >= config.minChunkMs -> cut(current.size)
            ms >= config.maxChunkMs -> cut(quietestCutPoint())
        }
    }

    /**
     * Mientras dura `noiseCalibrationMs`, busca el bloque más silencioso (el ruido de fondo, aunque la persona
     * empiece a hablar enseguida) y después ajusta el umbral de pausa a ese ruido más un margen.
     */
    private fun calibrate(level: Double, bytes: Int) {
        if (noiseRms != null) return
        quietestStartRms = minOf(quietestStartRms, level)
        calibrationBytes += bytes
        if (msOf(calibrationBytes) < config.noiseCalibrationMs) return
        noiseRms = quietestStartRms
        pauseThreshold = pauseThresholdFor(quietestStartRms, config)
        silence = SilenceDetector(config.sampleRate, config.chunkSilenceMs, pauseThreshold)
    }

    /** Para el corte forzado: el final del bloque más silencioso de la última ventana, para no partir palabras. */
    private fun quietestCutPoint(): Int {
        val windowStart = current.size - bytesOf(config.forcedCutWindowMs)
        var best: Block? = null
        for (b in blocks) {
            if (b.end > windowStart && (best == null || b.rms <= best.rms)) best = b
        }
        return best?.end ?: current.size
    }

    private fun cut(at: Int) {
        var voicedBytes = 0
        // La voz se cuenta con el umbral fijo: con ruido alto, el adaptativo podría tomar voz baja por silencio.
        for (b in blocks) if (b.end <= at && b.rms >= config.silenceThresholdMin) voicedBytes += b.bytes
        val remaining = blocks.filter { it.end > at }
        remaining.forEach { it.end -= at }
        blocks.clear()
        blocks.addAll(remaining)
        silence.reset()

        val pcm = current.take(at)
        val voicedMs = msOf(voicedBytes)
        val chunk = Chunk(chunks.size, chunks.lastOrNull())
        chunk.outcome = scope.async { process(chunk, pcm, voicedMs) }
        chunks.add(chunk)
    }

    // ---- Procesamiento de cada tramo ----

    private class Chunk(val index: Int, val previous: Chunk?) {
        /** Texto limpio por reglas, para dar contexto al pulido del tramo siguiente ("" si no hubo). */
        val ruled = CompletableDeferred<String>()
        lateinit var outcome: Deferred<Outcome>
    }

    private class Outcome(val result: DictationResult?, val error: EngineException?, val timings: ChunkTimings)

    private suspend fun process(chunk: Chunk, pcm: ByteArray, voicedMs: Long): Outcome {
        val audioMs = Pcm16.durationMs(pcm, config.sampleRate)
        try {
            if (voicedMs < config.minAudioMs || Pcm16.rms(pcm) < config.minRms) {
                return Outcome(null, null, ChunkTimings(chunk.index, audioMs, ChunkTimings.Status.SKIPPED))
            }
            var retries = 0
            var hedged = false
            val transcript = try {
                permits.withPermit {
                    transcribeWithRetry(pcm, onRetry = { retries++ }, canHedge = { !hedged }, onHedge = { hedged = true })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = e as? EngineException
                    ?: EngineException(EngineException.Kind.BAD_RESPONSE, e.message ?: "Error al transcribir.")
                return Outcome(
                    null, error,
                    ChunkTimings(chunk.index, audioMs, ChunkTimings.Status.FAILED, retries = retries, hedged = hedged),
                )
            }
            chunk.ruled.complete(transcript.ruled)

            val result = if (transcript.raw.isEmpty()) {
                DictationResult("", "", polished = false, timings = transcript.timings())
            } else {
                val context = previousContext(chunk)
                permits.withPermit { engine.polish(transcript, context) }
            }
            val t = result.timings
            return Outcome(
                result,
                null,
                ChunkTimings(chunk.index, audioMs, ChunkTimings.Status.OK, t.encodeMs, t.sttMs, t.polishMs, t.audioBytes, retries, hedged),
            )
        } finally {
            chunk.ruled.complete("") // sin efecto si ya tenía texto; destraba al tramo siguiente si éste falló
        }
    }

    private suspend fun transcribeWithRetry(
        pcm: ByteArray,
        onRetry: () -> Unit,
        canHedge: () -> Boolean,
        onHedge: () -> Unit,
    ): DictationEngine.Transcript {
        var attempt = 0
        while (true) {
            try {
                return transcribeHedged(pcm, canHedge, onHedge)
            } catch (e: EngineException) {
                val transient = e.kind == EngineException.Kind.NETWORK || e.kind == EngineException.Kind.SERVER
                if (!transient || attempt >= config.chunkRetries) throw e
                attempt++
                onRetry()
                delay(config.chunkRetryDelayMs)
            }
        }
    }

    /**
     * Transcribe; si la respuesta tarda más que `hedgeAfterMs`, manda el mismo audio otra vez en paralelo y usa la
     * primera que llegue bien (la otra se cancela). Solo falla si fallan las dos.
     */
    private suspend fun transcribeHedged(
        pcm: ByteArray,
        canHedge: () -> Boolean,
        onHedge: () -> Unit,
    ): DictationEngine.Transcript = coroutineScope {
        val first = async { attempt(pcm) }
        val hedgeAfter = config.hedgeAfterMs
        val early = if (hedgeAfter == null || !canHedge()) first.await() else withTimeoutOrNull(hedgeAfter) { first.await() }
        if (early != null) return@coroutineScope early.getOrThrow()

        onHedge()
        val second = async { attempt(pcm) }
        val (winner, from) = select {
            first.onAwait { it to first }
            second.onAwait { it to second }
        }
        val result = if (winner.isSuccess) winner else {
            val other = if (from === first) second else first
            other.await().takeIf { it.isSuccess } ?: winner
        }
        coroutineContext.cancelChildren()
        result.getOrThrow()
    }

    private suspend fun attempt(pcm: ByteArray): Result<DictationEngine.Transcript> = try {
        Result.success(engine.transcribe(pcm))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Final del texto del tramo anterior con contenido (solo lectura), o null si es el primero. */
    private suspend fun previousContext(chunk: Chunk): String? {
        var p = chunk.previous
        while (p != null) {
            val text = p.ruled.await()
            if (text.isNotBlank()) return tail(text, config.polishContextChars)
            p = p.previous
        }
        return null
    }

    private fun combine(outcomes: List<Outcome>): DictationResult {
        val ok = outcomes.mapNotNull { it.result }
        val firstError = outcomes.firstNotNullOfOrNull { it.error }
        if (ok.isEmpty()) {
            throw firstError ?: EngineException(EngineException.Kind.AUDIO_SILENT, "No se detectó voz.")
        }
        val chunkTimings = outcomes.map { it.timings }
        return DictationResult(
            text = joinChunks(ok.map { it.text }),
            rawText = joinChunks(ok.map { it.rawText }),
            polished = ok.any { it.polished },
            polishError = ok.firstNotNullOfOrNull { it.polishError },
            // Totales del trabajo hecho; el detalle por tramo está en [chunks].
            timings = DictationTimings(
                audioMs = chunkTimings.sumOf { it.audioMs },
                sttMs = chunkTimings.sumOf { it.sttMs },
                polishMs = chunkTimings.mapNotNull { it.polishMs }.takeIf { it.isNotEmpty() }?.sum(),
                encodeMs = chunkTimings.sumOf { it.encodeMs },
                audioBytes = chunkTimings.sumOf { it.audioBytes },
            ),
            chunks = chunkTimings,
            chunkError = firstError,
            noiseRms = noiseRms,
            silenceThreshold = pauseThreshold,
        )
    }

    private fun DictationResult.withLevels() = DictationResult(
        text, rawText, polished, polishError, timings, chunks, chunkError,
        noiseRms = this@ChunkedDictationSession.noiseRms,
        silenceThreshold = pauseThreshold,
    )

    private fun msOf(bytes: Int): Long = bytes / 2L * 1000L / config.sampleRate
    private fun bytesOf(ms: Long): Int = (ms * config.sampleRate / 1000L).toInt() * 2

    internal companion object {
        /** Umbral de pausa para un ruido de fondo dado: ruido × margen, entre el mínimo y el máximo configurados. */
        fun pauseThresholdFor(noiseRms: Double, config: DictationConfig): Double =
            (noiseRms * config.silenceNoiseFactor).coerceIn(config.silenceThresholdMin, config.silenceThresholdMax)

        /** Une los textos de los tramos en orden: un espacio entre tramos, nada si uno termina o empieza en salto de línea. */
        fun joinChunks(parts: List<String>): String {
            val sb = StringBuilder()
            for (part in parts) {
                val text = part.trim { it == ' ' || it == '\t' }
                if (text.isEmpty()) continue
                if (sb.isNotEmpty() && sb.last() != '\n' && text.first() != '\n') sb.append(' ')
                sb.append(text)
            }
            return sb.toString()
        }

        /** Últimos [max] caracteres, empezando en una palabra entera. */
        fun tail(text: String, max: Int): String {
            val t = text.trim()
            if (t.length <= max) return t
            val end = t.takeLast(max)
            val space = end.indexOf(' ')
            return if (space in 0 until end.length - 1) end.substring(space + 1) else end
        }
    }
}

/** PCM que crece sin copiar en cada bloque. */
private class PcmBuffer {
    private var data = ByteArray(64 * 1024)
    var size = 0
        private set

    fun append(pcm: ByteArray) {
        if (size + pcm.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + pcm.size))
        pcm.copyInto(data, size)
        size += pcm.size
    }

    /** Saca los primeros [n] bytes y deja el resto al principio. */
    fun take(n: Int): ByteArray {
        val out = data.copyOf(n)
        data.copyInto(data, 0, n, size)
        size -= n
        return out
    }
}
