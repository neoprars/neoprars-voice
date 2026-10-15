package com.neoprars.voice.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.neoprars.voice.engine.AudioEncoder
import com.neoprars.voice.engine.EncodedAudio
import com.neoprars.voice.engine.WavAudioEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor

/**
 * Comprime el dictado a AAC dentro de un .m4a con el codificador del sistema, para subir varias veces
 * menos datos que con WAV. Todo ocurre en memoria (memfd): el audio nunca se escribe en el almacenamiento.
 * En Android anterior al 11 no hay archivo en memoria, así que ahí se sigue enviando WAV.
 * Si algo falla, lanza la excepción y el motor manda WAV.
 */
class AacEncoder(private val bitRate: Int = 48_000) : AudioEncoder {

    override suspend fun encode(pcm: ByteArray, sampleRate: Int): EncodedAudio =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) WavAudioEncoder.encode(pcm, sampleRate)
        else withContext(Dispatchers.Default) { encodeM4a(pcm, sampleRate) }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun encodeM4a(pcm: ByteArray, sampleRate: Int): EncodedAudio {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var fd: FileDescriptor? = null
        var muxer: MediaMuxer? = null
        try {
            fd = Os.memfd_create("dictado", 0)
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var track = -1
            var offset = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                var fedInput = false
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        fedInput = true
                        val buffer = codec.getInputBuffer(index) ?: throw IllegalStateException("sin búfer de entrada")
                        buffer.clear()
                        val ptsUs = offset / 2L * 1_000_000L / sampleRate
                        val size = minOf(buffer.remaining(), pcm.size - offset) and 1.inv() // muestras enteras
                        if (size <= 0) {
                            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.put(pcm, offset, size)
                            codec.queueInputBuffer(index, 0, size, ptsUs, 0)
                            offset += size
                        }
                    }
                }
                // Solo se espera la salida cuando no hubo entrada que cargar: esperar en cada vuelta
                // frena el codificador a una trama por espera.
                val index = codec.dequeueOutputBuffer(info, if (fedInput) 0 else TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index) ?: throw IllegalStateException("sin búfer de salida")
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && track >= 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            muxer.stop()
            muxer.release()
            muxer = null

            return EncodedAudio(readAll(fd), "audio/mp4", "audio.m4a")
        } finally {
            try { muxer?.release() } catch (_: RuntimeException) {}
            try { codec.stop() } catch (_: RuntimeException) {}
            codec.release()
            if (fd != null) try { Os.close(fd) } catch (_: Exception) {}
        }
    }

    private fun readAll(fd: FileDescriptor): ByteArray {
        val size = Os.lseek(fd, 0, OsConstants.SEEK_END).toInt()
        if (size <= 0) throw IllegalStateException("m4a vacío")
        Os.lseek(fd, 0, OsConstants.SEEK_SET)
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = Os.read(fd, bytes, read, size - read)
            if (n <= 0) throw IllegalStateException("m4a incompleto")
            read += n
        }
        return bytes
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
