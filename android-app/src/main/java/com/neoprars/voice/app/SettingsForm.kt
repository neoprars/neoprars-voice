package com.neoprars.voice.app

import com.neoprars.voice.engine.Capability
import com.neoprars.voice.engine.CatalogEntry
import com.neoprars.voice.engine.ProviderCatalog

/**
 * Reglas de la pantalla de ajustes, sin Android (se prueban en `src/test`).
 *
 * - Interruptores y selectores se guardan al instante.
 * - Los campos que se escriben (claves, vocabulario) esperan a "Guardar cambios".
 */
object SettingsForm {

    /**
     * Opciones de un selector de proveedores: todos los del catálogo para esa capacidad, en su orden.
     * Las que no están implementadas se muestran deshabilitadas ("Próximamente").
     */
    fun providerOptions(capability: Capability): List<CatalogEntry> =
        ProviderCatalog.entries.filter { it.capability == capability }

    /** Idiomas del selector, en orden. "" = detección automática. */
    val LANGUAGES = listOf("es", "en", "")

    /** Opciones del selector: las fijas más el idioma guardado si es otro (por ejemplo "pt" de una versión anterior). */
    fun languageOptions(saved: String): List<String> {
        val code = saved.trim()
        return if (code in LANGUAGES) LANGUAGES else LANGUAGES + code
    }

    /**
     * Hay cambios sin guardar si se escribió alguna clave (las guardadas nunca se muestran, así que cualquier texto
     * es nuevo) o si algún campo editable (modelos, vocabulario) difiere de lo guardado.
     *
     * @param edits pares (lo escrito, lo guardado).
     */
    fun hasPendingChanges(keyInputs: Collection<String>, edits: List<Pair<String, String>>): Boolean =
        keyInputs.any { it.isNotBlank() } || edits.any { (input, saved) -> input.trim() != saved.trim() }
}
