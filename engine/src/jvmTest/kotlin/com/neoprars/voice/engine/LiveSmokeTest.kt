package com.neoprars.voice.engine

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Prueba real contra las APIs. Se omite salvo que definas las variables de entorno:
 *
 *   OPENAI_API_KEY=sk-...  NEOPRARS_WAV=/ruta/audio.wav \
 *   ./gradlew :engine:jvmTest --tests '*LiveSmokeTest*'
 *
 * El WAV debe ser PCM16, mono, 16 kHz, con cabecera de 44 bytes (por ejemplo:
 * `ffmpeg -i in.m4a -ar 16000 -ac 1 -c:a pcm_s16le audio.wav`).
 *
 * Como la app, no muestra el texto dictado ni mensajes crudos de error: solo largos, duraciones y tipo de error.
 * La salida queda en el informe del test (`engine/build/test-results`).
 */
class LiveSmokeTest {

    @Test fun liveDictation() {
        runBlocking {
            val openAiKey = System.getenv("OPENAI_API_KEY")
            val wavPath = System.getenv("NEOPRARS_WAV")
            if (openAiKey.isNullOrBlank() || wavPath.isNullOrBlank()) {
                println("LiveSmokeTest omitido: definí OPENAI_API_KEY y NEOPRARS_WAV para correrlo.")
                return@runBlocking
            }
            val bytes = File(wavPath).readBytes()
            val pcm = bytes.copyOfRange(44, bytes.size)

            val client = createHttpClient()
            // La configuración por defecto de la app: transcripción y pulido con la clave de OpenAI.
            val engine = EngineFactory.create(Presets.defaultSetup(), client, KeyResolver { openAiKey })

            try {
                val r = engine.dictate(pcm)
                val t = r.timings
                println(
                    "raw_len=${r.rawText.length} text_len=${r.text.length} polished=${r.polished} " +
                        "polish_failed=${r.polishError != null} audio_ms=${t.audioMs} stt_ms=${t.sttMs} " +
                        "polish_ms=${t.polishMs ?: "-"}",
                )
                assertTrue(r.text.isNotBlank(), "La transcripción llegó vacía.")
            } catch (e: EngineException) {
                println("error=${e.kind}")
                throw AssertionError("Falló el dictado real: ${e.kind}")
            } finally {
                client.close()
            }
        }
    }
}
