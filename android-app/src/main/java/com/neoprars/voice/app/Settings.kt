package com.neoprars.voice.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.neoprars.voice.engine.Capability
import com.neoprars.voice.engine.PolishStyle
import com.neoprars.voice.engine.ProviderCatalog

/** Claves y preferencias, cifradas con una clave del Android Keystore. */
class Settings(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        "neoprars_voice_secure",
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var openAiKey: String
        get() = apiKey("openai")
        set(v) = setApiKey("openai", v)

    /** Clave de una empresa del catálogo (`CatalogEntry.keyId`); vacía si no hay. Solo la lee [EngineProvider]. */
    fun apiKey(keyId: String): String = prefs.getString(keyPref(keyId), "").orEmpty()

    fun setApiKey(keyId: String, value: String) = prefs.edit().putString(keyPref(keyId), value.trim()).apply()

    // "openai_key" y "anthropic_key" ya existían antes del catálogo: se conservan para no perder claves guardadas.
    private fun keyPref(keyId: String) = "${keyId}_key"

    /** Proveedor elegido para transcribir y para pulir (ids del catálogo). Se guardan al instante. */
    var transcriberProvider: String
        get() = prefs.getString("provider_transcribe", ProviderCatalog.DEFAULT_TRANSCRIBER).orEmpty()
        set(v) = prefs.edit().putString("provider_transcribe", v).apply()

    var polisherProvider: String
        get() = prefs.getString("provider_polish", ProviderCatalog.DEFAULT_POLISHER).orEmpty()
        set(v) = prefs.edit().putString("provider_polish", v).apply()

    /** Modelo elegido para un proveedor; vacío = el de fábrica del catálogo. Uno por proveedor y tarea. */
    fun model(capability: Capability, providerId: String): String =
        prefs.getString(modelPref(capability, providerId), "").orEmpty()

    fun setModel(capability: Capability, providerId: String, model: String) =
        prefs.edit().putString(modelPref(capability, providerId), model.trim()).apply()

    private fun modelPref(capability: Capability, providerId: String) =
        "model_${capability.name.lowercase()}_$providerId"

    var polishStyle: PolishStyle
        get() = PolishStyle.fromName(prefs.getString("polish_style", null))
        set(v) = prefs.edit().putString("polish_style", v.name).apply()

    var polishEnabled: Boolean
        get() = prefs.getBoolean("polish", true)
        set(v) = prefs.edit().putBoolean("polish", v).apply()

    /** Dictados con menos palabras que esto se entregan sin pulir (más rápido). */
    var polishMinWords: Int
        get() = prefs.getInt("polish_min_words", 6)
        set(v) = prefs.edit().putInt("polish_min_words", v.coerceAtLeast(0)).apply()

    /** Transcribir por tramos mientras se graba, para esperar menos al parar. */
    var chunkedDictation: Boolean
        get() = prefs.getBoolean("chunked_dictation", true)
        set(v) = prefs.edit().putBoolean("chunked_dictation", v).apply()

    /** Posición del botón flotante elegida por la persona, una por orientación; null si nunca lo movió. */
    fun buttonPosition(landscape: Boolean): ButtonPlacement.Position? =
        ButtonPlacement.decode(prefs.getString(positionKey(landscape), null))

    fun saveButtonPosition(landscape: Boolean, position: ButtonPlacement.Position) =
        prefs.edit().putString(positionKey(landscape), ButtonPlacement.encode(position)).apply()

    private fun positionKey(landscape: Boolean) = if (landscape) "button_pos_land" else "button_pos_port"

    var language: String
        get() = prefs.getString("language", "es").orEmpty()
        set(v) = prefs.edit().putString("language", v.trim()).apply()

    var vocabularyHint: String
        get() = prefs.getString("hint", "").orEmpty()
        set(v) = prefs.edit().putString("hint", v.trim()).apply()
}
