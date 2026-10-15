package com.neoprars.voice.app

import android.util.Log
import com.neoprars.voice.engine.ChunkTimings
import com.neoprars.voice.engine.DictationResult
import java.util.Locale

/** Registro para medir latencia. Solo duraciones y datos sin contenido: nunca texto dictado ni audio. */
object DictationLog {

    const val TAG = "NeoprarsVoice"

    /**
     * @param chunked si la grabación usó el dictado por tramos (aunque haya quedado en un solo tramo).
     * @param stopWaitMs desde el toque de parar hasta el texto insertado.
     * @param warmUpMs duración del precalentado de esta grabación; null si no terminó.
     */
    fun result(result: DictationResult, chunked: Boolean, insertMs: Long, stopWaitMs: Long, warmUpMs: Long?) {
        val t = result.timings
        val warm = warmUpMs ?: "-"
        // Niveles RMS (números, sin audio): ruido medido al empezar y umbral de pausa usado.
        val levels = "noise_rms=${level(result.noiseRms)} silence_thr=${level(result.silenceThreshold)}"
        if (result.chunks.isEmpty()) {
            val upload = t.uploadMs
            Log.i(
                TAG,
                "chunked=$chunked chunks=1 audio_ms=${t.audioMs} encode_ms=${t.encodeMs} sent_kb=${t.audioBytes / 1024} " +
                    "stt_ms=${t.sttMs} upload_ms=${upload ?: "-"} " +
                    "wait_ms=${if (upload != null) t.sttMs - upload else "-"} polish_ms=${t.polishMs ?: "-"} " +
                    "polished=${result.polished} insert_ms=$insertMs stop_wait_ms=$stopWaitMs warm_ms=$warm $levels",
            )
            return
        }
        val total = result.chunks.size
        for (c in result.chunks) {
            Log.i(
                TAG,
                "chunk=${c.index + 1}/$total status=${c.status} audio_ms=${c.audioMs} encode_ms=${c.encodeMs} " +
                    "sent_kb=${c.audioBytes / 1024} stt_ms=${c.sttMs} polish_ms=${c.polishMs ?: "-"} retries=${c.retries} hedged=${c.hedged}",
            )
        }
        Log.i(
            TAG,
            "chunked=$chunked chunks=$total failed=${result.chunks.count { it.status == ChunkTimings.Status.FAILED }} " +
                "skipped=${result.chunks.count { it.status == ChunkTimings.Status.SKIPPED }} audio_ms=${t.audioMs} " +
                "polished=${result.polished} insert_ms=$insertMs stop_wait_ms=$stopWaitMs warm_ms=$warm $levels",
        )
    }

    private fun level(rms: Double?): String = if (rms == null) "-" else String.format(Locale.US, "%.4f", rms)

    /** @param changed nombres de los ajustes que cambiaron (nunca sus valores), o "first". */
    fun engineRebuilt(changed: String) {
        Log.i(TAG, "engine_rebuilt changed=$changed")
    }

    /** Resultado de "Probar clave": la empresa y el tipo de resultado, nunca la clave. */
    fun keyCheck(keyId: String, kind: String, ms: Long) {
        Log.i(TAG, "key_check provider=$keyId result=$kind ms=$ms")
    }

    fun failure(kind: String, chunked: Boolean, stopWaitMs: Long) {
        Log.i(TAG, "error=$kind chunked=$chunked stop_wait_ms=$stopWaitMs")
    }
}
