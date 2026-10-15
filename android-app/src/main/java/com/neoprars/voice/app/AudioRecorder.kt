package com.neoprars.voice.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.neoprars.voice.engine.Pcm16
import com.neoprars.voice.engine.SilenceDetector

/** Graba el micrófono a 16 kHz mono y devuelve PCM16 little-endian al detener. */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val MAX_SECONDS = 120
        /** Silencio que se conserva al final cuando la grabación se corta sola. */
        private const val TAIL_SAMPLES = SAMPLE_RATE / 2
    }

    /**
     * Si está definido, la grabación se corta sola tras unos segundos de silencio o al llegar al tope
     * de duración, y se avisa acá (desde el hilo de grabación). Quien lo recibe debe llamar a [stop].
     */
    var onAutoStop: (() -> Unit)? = null

    /** Si está definido, recibe cada bloque grabado (PCM16) a medida que llega, desde el hilo de grabación. */
    var onPcm: ((ByteArray) -> Unit)? = null

    @Volatile private var recording = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val buffer = ShortArray(SAMPLE_RATE * MAX_SECONDS)
    @Volatile private var count = 0

    @SuppressLint("MissingPermission") // el permiso se verifica antes en NeoprarsVoiceIme
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        record = rec
        count = 0
        recording = true
        rec.startRecording()
        val autoStop = onAutoStop
        val pcmSink = onPcm
        thread = Thread {
            val chunk = ShortArray(1024)
            val silence = SilenceDetector(SAMPLE_RATE)
            var stoppedAlone = false
            while (recording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val room = buffer.size - count
                val take = minOf(n, room)
                System.arraycopy(chunk, 0, buffer, count, take)
                count += take
                if (take > 0) pcmSink?.invoke(Pcm16.fromShorts(chunk, take))
                if (take < n) { // tope de duración alcanzado
                    recording = false
                    stoppedAlone = true
                } else if (autoStop != null && silence.feed(chunk, n)) {
                    // No mandar a transcribir los segundos de silencio del final.
                    val extra = (silence.silentSamples - TAIL_SAMPLES).toInt()
                    if (extra > 0) count = maxOf(0, count - extra)
                    recording = false
                    stoppedAlone = true
                }
            }
            if (stoppedAlone) autoStop?.invoke()
        }.also { it.start() }
        return true
    }

    fun stop(): ByteArray {
        recording = false
        thread?.join(500)
        thread = null
        record?.let {
            try { it.stop() } catch (_: IllegalStateException) {}
            it.release()
        }
        record = null
        return Pcm16.fromShorts(buffer, count)
    }
}
