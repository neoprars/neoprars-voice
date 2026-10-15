package com.neoprars.voice.app

import android.os.SystemClock
import com.neoprars.voice.engine.CatalogSelection
import com.neoprars.voice.engine.Capability
import com.neoprars.voice.engine.DictationConfig
import com.neoprars.voice.engine.DictationEngine
import com.neoprars.voice.engine.EngineException
import com.neoprars.voice.engine.EngineFactory
import com.neoprars.voice.engine.KeyCheck
import com.neoprars.voice.engine.KeyResolver
import com.neoprars.voice.engine.PolishStyle
import com.neoprars.voice.engine.ProviderCatalog
import com.neoprars.voice.engine.createHttpClient
import io.ktor.client.HttpClient

/**
 * Único punto donde la app decide QUÉ proveedores usa y DE DÓNDE salen las claves.
 * Hoy: los proveedores que la persona eligió del [ProviderCatalog], con sus claves en el dispositivo.
 * Versión comercial: reemplazar [selection] y [keysFor] para apuntar a tu backend
 * (baseUrl propio + token de sesión); el motor y la interfaz no cambian.
 *
 * El motor y su cliente HTTP se reutilizan entre dictados (así las conexiones quedan abiertas) y solo
 * se rearman si cambia la configuración o alguna clave. Se usa siempre desde el hilo principal.
 */
object EngineProvider {

    /** Todo lo que, si cambia, obliga a rearmar el motor. Vive solo en memoria. */
    private data class Snapshot(
        val selection: CatalogSelection,
        /** Solo las claves que la selección necesita, por empresa. */
        val keys: Map<String, String>,
        val polishMinWords: Int,
        val language: String,
        val vocabularyHint: String,
        val style: PolishStyle,
    )

    private var client: HttpClient? = null
    private var cached: Pair<Snapshot, DictationEngine>? = null
    private var users = 0

    /** Lo elegido en ajustes; si un id guardado ya no está disponible, se usa el de fábrica. */
    private fun selection(settings: Settings): CatalogSelection {
        val transcriber = settings.transcriberProvider
            .takeIf { id -> ProviderCatalog.available(Capability.TRANSCRIBE).any { it.id == id } }
            ?: ProviderCatalog.DEFAULT_TRANSCRIBER
        val polisher = settings.polisherProvider
            .takeIf { id -> ProviderCatalog.available(Capability.POLISH).any { it.id == id } }
            ?: ProviderCatalog.DEFAULT_POLISHER
        return CatalogSelection(
            transcriberId = transcriber,
            polisherId = polisher.takeIf { settings.polishEnabled },
            transcriberModel = settings.model(Capability.TRANSCRIBE, transcriber),
            polisherModel = settings.model(Capability.POLISH, polisher),
        )
    }

    private fun keysFor(snapshot: Snapshot) = KeyResolver { keyId -> snapshot.keys[keyId] }

    /** true si está la clave del proveedor de transcripción (sin ella no se puede dictar). */
    fun hasTranscriberKey(settings: Settings): Boolean {
        val id = selection(settings).transcriberId
        val keyId = ProviderCatalog.find(Capability.TRANSCRIBE, id)?.keyId ?: return false
        return settings.apiKey(keyId).isNotBlank()
    }

    /** Empresas cuya clave hace falta para lo elegido (en orden: transcripción, después pulido). */
    fun requiredKeys(settings: Settings): Set<String> = ProviderCatalog.requiredKeys(selection(settings))

    /** Si hay una clave guardada para esa empresa (sin exponer su valor). */
    fun hasKey(settings: Settings, keyId: String): Boolean = settings.apiKey(keyId).isNotBlank()

    /** Empresas cuya clave hace falta para lo elegido y todavía no está guardada. */
    fun missingKeys(settings: Settings): Set<String> =
        requiredKeys(settings).filterNot { hasKey(settings, it) }.toSet()

    /**
     * Prueba la clave de una empresa con la llamada sin costo de su protocolo ([KeyCheck]).
     * [typed] = lo escrito en el campo (aunque no esté guardado); vacío = se prueba la guardada.
     * null = válida; si no, el tipo de problema. La clave no se muestra ni se registra.
     */
    suspend fun checkKey(settings: Settings, keyId: String, typed: String): EngineException.Kind? {
        val selection = selection(settings)
        val entry = listOfNotNull(
            ProviderCatalog.find(Capability.TRANSCRIBE, selection.transcriberId),
            selection.polisherId?.let { ProviderCatalog.find(Capability.POLISH, it) },
        ).firstOrNull { it.keyId == keyId }
        val config = entry?.config() ?: return EngineException.Kind.NOT_CONFIGURED
        val key = typed.trim().ifEmpty { settings.apiKey(keyId) }
        val started = SystemClock.elapsedRealtime()
        // Cliente propio y corto: el del motor solo vive mientras hay servicios dictando.
        val http = createHttpClient()
        val result = try {
            KeyCheck.check(config, key, http)
        } finally {
            http.close()
        }
        DictationLog.keyCheck(keyId, result?.name ?: "VALID", SystemClock.elapsedRealtime() - started)
        return result
    }

    /** Cada servicio que dicta lo llama al crearse y llama a [release] al destruirse. */
    fun acquire() {
        users++
    }

    /** Cuando el último servicio se va, se cierran el motor y sus conexiones. */
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        cached = null
        client?.close()
        client = null
    }

    /** Lanza EngineException(NOT_CONFIGURED) si falta la clave del transcriptor. */
    fun engine(settings: Settings): DictationEngine {
        val selection = selection(settings)
        val snapshot = Snapshot(
            selection = selection,
            keys = ProviderCatalog.requiredKeys(selection).associateWith { settings.apiKey(it) },
            polishMinWords = settings.polishMinWords,
            language = settings.language,
            vocabularyHint = settings.vocabularyHint,
            style = settings.polishStyle,
        )
        val previous = cached?.first
        cached?.let { (old, engine) -> if (old == snapshot) return engine }
        DictationLog.engineRebuilt(changedFields(previous, snapshot))

        val http = client ?: createHttpClient().also { client = it }
        val engine = EngineFactory.create(
            setup = ProviderCatalog.setup(selection),
            client = http,
            keys = keysFor(snapshot),
            dictation = DictationConfig(
                language = snapshot.language.ifBlank { null },
                vocabularyHint = snapshot.vocabularyHint.ifBlank { null },
                polishInstructions = snapshot.style.instructions,
                minWordsToPolish = snapshot.polishMinWords,
            ),
            encoder = AacEncoder(),
        )
        cached = snapshot to engine
        return engine
    }

    /** Nombres (nunca valores) de lo que cambió, para el registro. */
    private fun changedFields(old: Snapshot?, new: Snapshot): String {
        if (old == null) return "first"
        val a = old.selection
        val b = new.selection
        return buildList {
            if (a.transcriberId != b.transcriberId) add("transcriber")
            if (a.polisherId != b.polisherId) add("polisher")
            if (a.transcriberModel != b.transcriberModel) add("transcriber_model")
            if (a.polisherModel != b.polisherModel) add("polisher_model")
            for (keyId in (old.keys.keys + new.keys.keys)) if (old.keys[keyId] != new.keys[keyId]) add("${keyId}_key")
            if (old.polishMinWords != new.polishMinWords) add("polish_min_words")
            if (old.language != new.language) add("language")
            if (old.vocabularyHint != new.vocabularyHint) add("vocabulary")
            if (old.style != new.style) add("style")
        }.joinToString(",")
    }
}
