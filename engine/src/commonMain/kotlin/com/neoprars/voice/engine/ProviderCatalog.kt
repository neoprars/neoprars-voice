package com.neoprars.voice.engine

/** Qué hace un proveedor del catálogo. */
enum class Capability { TRANSCRIBE, POLISH }

/** Qué tan probado está un proveedor: de punta a punta en un teléfono, solo con tests, o sin implementar. */
enum class ProviderStatus { TESTED, EXPERIMENTAL, PLANNED }

/**
 * Un proveedor que la app puede ofrecer. Sin secretos: la clave se busca por [keyId] (una por empresa,
 * compartida entre transcripción y pulido).
 *
 * @param template configuración con el modelo por defecto; null si todavía no hay clase que hable su protocolo.
 * @param source documentación oficial de donde salen endpoint, autenticación y modelo.
 * @param testedEndToEnd true si se probó dictando de verdad en un teléfono, no solo con servidores simulados.
 */
class CatalogEntry(
    val id: String,
    val keyId: String,
    val displayName: String,
    val capability: Capability,
    val defaultModel: String,
    val source: String,
    private val template: ProviderConfig?,
    private val testedEndToEnd: Boolean = false,
) {
    /** false = declarado pero sin implementar: no se muestra en los selectores. */
    val implemented: Boolean get() = template != null

    val status: ProviderStatus
        get() = when {
            !implemented -> ProviderStatus.PLANNED
            testedEndToEnd -> ProviderStatus.TESTED
            else -> ProviderStatus.EXPERIMENTAL
        }

    /** Cómo conseguir la clave de esta empresa. */
    val keyHelp: KeyHelp? get() = ProviderCatalog.keyHelp[keyId]

    /** Configuración lista, con [model] si no está vacío o el modelo por defecto. */
    fun config(model: String = ""): ProviderConfig? = template?.copy(model = model.trim().ifEmpty { defaultModel })
}

/**
 * Ayuda para conseguir la clave de una empresa. Sin textos: la app los busca por [id]
 * (los pasos dependen del idioma de la interfaz).
 *
 * @param keyPageUrl página oficial donde se crea la clave.
 * @param source documentación oficial de donde salen la URL y los pasos.
 */
class KeyHelp(val id: String, val keyPageUrl: String, val source: String)

/** Qué eligió la persona. [polisherId] null = sin pulido con IA. Modelo vacío = el de fábrica. */
data class CatalogSelection(
    val transcriberId: String = ProviderCatalog.DEFAULT_TRANSCRIBER,
    val polisherId: String? = ProviderCatalog.DEFAULT_POLISHER,
    val transcriberModel: String = "",
    val polisherModel: String = "",
)

/**
 * Catálogo de proveedores. Los precios y modelos cambian: revisar las fuentes antes de cambiar un valor por defecto.
 * Verificado el 2026-10-14 contra la documentación oficial de cada proveedor.
 */
object ProviderCatalog {

    const val DEFAULT_TRANSCRIBER = "openai"
    const val DEFAULT_POLISHER = "openai"

    val entries: List<CatalogEntry> = listOf(
        // ---- Transcripción ----
        CatalogEntry(
            id = "openai", keyId = "openai", displayName = "OpenAI", capability = Capability.TRANSCRIBE,
            defaultModel = OpenAiTranscriber.DEFAULT_MODEL,
            source = "https://developers.openai.com/api/docs/guides/speech-to-text",
            template = ProviderConfig(
                id = "openai", protocol = Protocol.OPENAI_AUDIO_TRANSCRIPTIONS,
                baseUrl = "https://api.openai.com", model = OpenAiTranscriber.DEFAULT_MODEL,
            ),
            testedEndToEnd = true,
        ),
        // POST /v1/listen?model=nova-3&language=es&keyterm=…, "Authorization: Token <clave>", audio crudo con su
        // Content-Type (MP4/M4A/AAC y WAV figuran entre los formatos aceptados; hasta 2 GB). Nova-3 admite es / es-419.
        // Fuentes: docs/pre-recorded-audio, docs/supported-audio-formats, docs/keyterm, docs/models-languages-overview.
        CatalogEntry(
            id = "deepgram", keyId = "deepgram", displayName = "Deepgram", capability = Capability.TRANSCRIBE,
            defaultModel = DeepgramTranscriber.DEFAULT_MODEL,
            source = "https://developers.deepgram.com/docs/pre-recorded-audio",
            template = ProviderConfig(
                id = "deepgram", protocol = Protocol.DEEPGRAM_LISTEN,
                baseUrl = "https://api.deepgram.com", model = DeepgramTranscriber.DEFAULT_MODEL,
                auth = AuthStyle.TOKEN,
            ),
        ),
        // Modelo de transcripción dedicado, por la Interactions API: POST /v1beta/interactions, "x-goog-api-key",
        // audio en base64 (pedido de hasta 20 MB; acepta audio/m4a, audio/aac y audio/wav), language_codes y
        // custom_vocabulary en transcription_config, store=false. USD 0,003/min de audio (estimado de Google).
        // Fuentes: docs/transcribe, docs/audio, docs/interactions, docs/models/gemini-3.5-transcribe, docs/pricing.
        CatalogEntry(
            id = "gemini", keyId = "gemini", displayName = "Google Gemini", capability = Capability.TRANSCRIBE,
            defaultModel = GeminiTranscriber.DEFAULT_MODEL,
            source = "https://ai.google.dev/gemini-api/docs/transcribe",
            template = ProviderConfig(
                id = "gemini", protocol = Protocol.GEMINI_INTERACTIONS,
                baseUrl = "https://generativelanguage.googleapis.com", model = GeminiTranscriber.DEFAULT_MODEL,
                auth = AuthStyle.X_GOOG_API_KEY,
            ),
        ),

        // ---- Pulido ----
        CatalogEntry(
            id = "openai", keyId = "openai", displayName = "OpenAI", capability = Capability.POLISH,
            defaultModel = "gpt-6-luna",
            source = "https://developers.openai.com/api/docs/models/gpt-6-luna",
            template = ProviderConfig(
                id = "openai", protocol = Protocol.OPENAI_CHAT_COMPLETIONS,
                baseUrl = "https://api.openai.com", model = "gpt-6-luna",
                options = mapOf("reasoning_effort" to "none"),
            ),
            testedEndToEnd = true,
        ),
        CatalogEntry(
            id = "anthropic", keyId = "anthropic", displayName = "Anthropic Claude", capability = Capability.POLISH,
            defaultModel = ClaudePolisher.DEFAULT_MODEL,
            source = "https://docs.anthropic.com/en/docs/about-claude/models/overview",
            template = ProviderConfig(
                id = "anthropic", protocol = Protocol.ANTHROPIC_MESSAGES,
                baseUrl = "https://api.anthropic.com", model = ClaudePolisher.DEFAULT_MODEL,
                auth = AuthStyle.X_API_KEY,
                options = mapOf("thinking" to "disabled"),
            ),
        ),
        // Compatible con OpenAI en otra ruta. En Gemini 3.x el razonamiento no se apaga; Flash-Lite ya usa el mínimo.
        CatalogEntry(
            id = "gemini", keyId = "gemini", displayName = "Google Gemini", capability = Capability.POLISH,
            defaultModel = "gemini-3.5-flash-lite",
            source = "https://ai.google.dev/gemini-api/docs/openai",
            template = ProviderConfig(
                id = "gemini", protocol = Protocol.OPENAI_CHAT_COMPLETIONS,
                baseUrl = "https://generativelanguage.googleapis.com", model = "gemini-3.5-flash-lite",
                options = mapOf(
                    "chat_path" to "/v1beta/openai/chat/completions",
                    // Para "Probar clave". Una clave inválida se espera como 400 con API_KEY_INVALID (lo habitual
                    // en las APIs de Google), no como 401: no está comprobado en vivo con esta ruta.
                    "models_path" to "/v1beta/openai/models",
                ),
            ),
        ),
        // xAI marca /v1/chat/completions como "deprecated / legacy" (sin fecha de baja); el modelo no razona.
        CatalogEntry(
            id = "xai", keyId = "xai", displayName = "xAI Grok", capability = Capability.POLISH,
            defaultModel = "grok-4.20-0309-non-reasoning",
            source = "https://docs.x.ai/docs/guides/chat/comparison",
            template = ProviderConfig(
                id = "xai", protocol = Protocol.OPENAI_CHAT_COMPLETIONS,
                baseUrl = "https://api.x.ai", model = "grok-4.20-0309-non-reasoning",
            ),
        ),
    )

    /**
     * Cómo conseguir la clave de cada empresa, por `keyId`. Pasos verificados el 2026-10-14
     * en la documentación oficial citada en [KeyHelp.source].
     */
    val keyHelp: Map<String, KeyHelp> = listOf(
        // Quickstart: "create an API key in the dashboard", con enlace a platform.openai.com/api-keys.
        KeyHelp(
            id = "openai", keyPageUrl = "https://platform.openai.com/api-keys",
            source = "https://developers.openai.com/api/docs/quickstart",
        ),
        // Console → API keys → "Create key"; la clave (sk-ant-…) se muestra una sola vez.
        KeyHelp(
            id = "anthropic", keyPageUrl = "https://platform.claude.com/settings/keys",
            source = "https://platform.claude.com/docs/en/get-api-key",
        ),
        // AI Studio crea un proyecto y una clave al aceptar los términos; si no, botón para crearla.
        KeyHelp(
            id = "gemini", keyPageUrl = "https://aistudio.google.com/apikey",
            source = "https://ai.google.dev/gemini-api/docs/api-key",
        ),
        // Registrarse, cargar créditos y crear la clave en la página API Keys de la consola.
        KeyHelp(
            id = "xai", keyPageUrl = "https://console.x.ai/team/default/api-keys",
            source = "https://docs.x.ai/docs/tutorial",
        ),
        // Proyecto → Settings → API Keys → "Create a New API Key"; el secreto se muestra una sola vez.
        KeyHelp(
            id = "deepgram", keyPageUrl = "https://console.deepgram.com/",
            source = "https://developers.deepgram.com/docs/create-additional-api-keys",
        ),
    ).associateBy { it.id }

    /** Los que se pueden elegir (implementados), en el orden del catálogo. */
    fun available(capability: Capability): List<CatalogEntry> =
        entries.filter { it.capability == capability && it.implemented }

    fun find(capability: Capability, id: String): CatalogEntry? =
        entries.firstOrNull { it.capability == capability && it.id == id }

    /** Arma la configuración del motor. Lanza NOT_CONFIGURED si un proveedor no existe, no sirve o no está implementado. */
    fun setup(selection: CatalogSelection): EngineSetup {
        val transcriber = usable(Capability.TRANSCRIBE, selection.transcriberId).config(selection.transcriberModel)!!
        val polisher = selection.polisherId?.let { usable(Capability.POLISH, it).config(selection.polisherModel)!! }
        return EngineSetup(transcriber, polisher)
    }

    /** Ids de las claves que necesita esta selección (sin repetir). */
    fun requiredKeys(selection: CatalogSelection): Set<String> = buildSet {
        add(usable(Capability.TRANSCRIBE, selection.transcriberId).keyId)
        selection.polisherId?.let { add(usable(Capability.POLISH, it).keyId) }
    }

    private fun usable(capability: Capability, id: String): CatalogEntry {
        val entry = find(capability, id) ?: throw EngineException(
            EngineException.Kind.NOT_CONFIGURED,
            "El proveedor '$id' no está en el catálogo para ${capability.name.lowercase()}.",
        )
        if (!entry.implemented) {
            throw EngineException(EngineException.Kind.NOT_CONFIGURED, "El proveedor '$id' todavía no está implementado.")
        }
        return entry
    }
}
