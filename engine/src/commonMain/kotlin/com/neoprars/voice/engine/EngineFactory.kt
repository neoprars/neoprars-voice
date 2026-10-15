package com.neoprars.voice.engine

import io.ktor.client.HttpClient

/**
 * Único lugar que conoce qué clase implementa cada [Protocol].
 * Para soportar un proveedor con protocolo nuevo: crear su clase y sumar una línea acá.
 */
object EngineFactory {

    fun transcriber(config: ProviderConfig, client: HttpClient, keys: KeyResolver): Transcriber {
        val key = requireKey(config, keys)
        return when (config.protocol) {
            Protocol.OPENAI_AUDIO_TRANSCRIPTIONS ->
                OpenAiTranscriber(client, key, config.model, config.baseUrl, config.auth, config.headers)
            Protocol.DEEPGRAM_LISTEN ->
                DeepgramTranscriber(client, key, config.model, config.baseUrl, config.auth, config.headers)
            Protocol.GEMINI_INTERACTIONS ->
                GeminiTranscriber(client, key, config.model, config.baseUrl, config.auth, config.headers)
            else -> throw EngineException(
                EngineException.Kind.NOT_CONFIGURED,
                "El protocolo ${config.protocol} no sirve para transcribir (proveedor '${config.id}').",
            )
        }
    }

    fun polisher(config: ProviderConfig, client: HttpClient, keys: KeyResolver): Polisher {
        val key = requireKey(config, keys)
        return when (config.protocol) {
            Protocol.ANTHROPIC_MESSAGES ->
                ClaudePolisher(
                    client, key, config.model, config.baseUrl, auth = config.auth, headers = config.headers,
                    thinking = config.options["thinking"],
                )
            Protocol.OPENAI_CHAT_COMPLETIONS ->
                OpenAiChatPolisher(
                    client, key, config.model, config.baseUrl, config.auth, config.headers,
                    reasoningEffort = config.options["reasoning_effort"],
                    chatPath = config.options["chat_path"] ?: OpenAiChatPolisher.DEFAULT_CHAT_PATH,
                )
            else -> throw EngineException(
                EngineException.Kind.NOT_CONFIGURED,
                "El protocolo ${config.protocol} no sirve para pulir texto (proveedor '${config.id}').",
            )
        }
    }

    /**
     * Arma el motor completo. Si falta la clave del transcriptor lanza NOT_CONFIGURED.
     * El pulido es opcional: sin clave o sin configuración, el motor usa solo las reglas.
     */
    fun create(
        setup: EngineSetup,
        client: HttpClient,
        keys: KeyResolver,
        dictation: DictationConfig = DictationConfig(),
        encoder: AudioEncoder = WavAudioEncoder,
    ): DictationEngine {
        val polisher = setup.polisher
            ?.takeIf { !keys.keyFor(it.id).isNullOrBlank() }
            ?.let { polisher(it, client, keys) }
        return DictationEngine(transcriber(setup.transcriber, client, keys), polisher, dictation, encoder)
    }

    private fun requireKey(config: ProviderConfig, keys: KeyResolver): String {
        val key = keys.keyFor(config.id)
        if (key.isNullOrBlank()) {
            throw EngineException(EngineException.Kind.NOT_CONFIGURED, "Falta la clave del proveedor '${config.id}'.")
        }
        return key
    }
}
