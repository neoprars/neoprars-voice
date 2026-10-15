package com.neoprars.voice.app

import com.neoprars.voice.engine.Capability
import com.neoprars.voice.engine.ProviderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsFormTest {

    @Test fun theTranscriptionSelectorAlwaysShowsEveryProvider() {
        val options = SettingsForm.providerOptions(Capability.TRANSCRIBE)
        assertEquals(listOf("openai", "deepgram", "gemini"), options.map { it.id })
        // OpenAI sin etiqueta (probado); el resto, experimental. El estado sale del catálogo.
        assertEquals(
            listOf(ProviderStatus.TESTED, ProviderStatus.EXPERIMENTAL, ProviderStatus.EXPERIMENTAL),
            options.map { it.status },
        )
    }

    @Test fun thePolishSelectorShowsEveryPolisher() {
        assertEquals(listOf("openai", "anthropic", "gemini", "xai"), SettingsForm.providerOptions(Capability.POLISH).map { it.id })
    }

    @Test fun noPendingChangesWhenNothingWasTyped() {
        assertFalse(SettingsForm.hasPendingChanges(listOf("", "  "), listOf("Neoprars" to "Neoprars", "" to "")))
        assertFalse(SettingsForm.hasPendingChanges(emptyList(), listOf(" Neoprars " to "Neoprars")))
    }

    @Test fun anyTypedKeyIsAPendingChange() {
        assertTrue(SettingsForm.hasPendingChanges(listOf("", "sk-nueva"), listOf("Neoprars" to "Neoprars")))
    }

    @Test fun changedVocabularyOrModelIsAPendingChange() {
        assertTrue(SettingsForm.hasPendingChanges(emptyList(), listOf("Neoprars, Mate" to "Neoprars")))
        assertTrue(SettingsForm.hasPendingChanges(emptyList(), listOf("" to "Neoprars")))
        assertTrue(SettingsForm.hasPendingChanges(emptyList(), listOf("" to "", "gpt-6-sol" to "")))
    }

    @Test fun languageOptionsKeepAnUnknownSavedCode() {
        assertEquals(listOf("es", "en", ""), SettingsForm.languageOptions("es"))
        assertEquals(listOf("es", "en", ""), SettingsForm.languageOptions(""))
        assertEquals(listOf("es", "en", "", "pt"), SettingsForm.languageOptions(" pt "))
    }
}
