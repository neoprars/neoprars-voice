package com.neoprars.voice.app

import com.neoprars.voice.engine.ProviderCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class KeyHelpTextsTest {

    @Test fun everyHelpInTheCatalogHasItsSteps() {
        for (entry in ProviderCatalog.entries) {
            val help = assertNotNull(entry.keyHelp, "sin ayuda: ${entry.id}")
            assertNotNull(KeyHelpTexts.steps(help.id), "faltan los pasos de ${help.id} en strings.xml")
        }
    }

    @Test fun eachProviderHasItsOwnSteps() {
        val steps = ProviderCatalog.keyHelp.keys.map { KeyHelpTexts.steps(it) }
        assertEquals(steps.size, steps.toSet().size)
    }

    @Test fun anUnknownHelpFallsBackToTheGenericText() {
        assertNull(KeyHelpTexts.steps("nadie"))
    }
}
