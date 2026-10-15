package com.neoprars.voice.engine

/** Audio listo para enviar a un transcriptor: los bytes y cómo se llama su formato. */
class EncodedAudio(
    val bytes: ByteArray,
    /** Tipo de contenido, por ejemplo "audio/wav" o "audio/mp4". */
    val mimeType: String,
    /** Nombre con extensión: los proveedores lo usan para reconocer el formato. */
    val fileName: String,
) {
    companion object {
        fun wav(bytes: ByteArray) = EncodedAudio(bytes, "audio/wav", "audio.wav")
    }
}

/**
 * Cómo se empaqueta el PCM antes de subirlo. Por defecto WAV (sin comprimir, sirve en cualquier plataforma);
 * cada app puede dar un codificador comprimido con lo que ofrezca su sistema para subir menos datos.
 */
interface AudioEncoder {
    /** @param pcm audio PCM de 16 bits little-endian, mono. */
    suspend fun encode(pcm: ByteArray, sampleRate: Int): EncodedAudio
}

object WavAudioEncoder : AudioEncoder {
    override suspend fun encode(pcm: ByteArray, sampleRate: Int): EncodedAudio =
        EncodedAudio.wav(WavEncoder.encode(pcm, sampleRate))
}
