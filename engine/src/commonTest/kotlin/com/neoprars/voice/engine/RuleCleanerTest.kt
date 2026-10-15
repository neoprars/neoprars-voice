package com.neoprars.voice.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class RuleCleanerTest {

    @Test fun removesFillers() =
        assertEquals("Necesito hablar con vos mañana.", RuleCleaner.clean("eh, necesito hablar con vos mañana."))

    @Test fun collapsesStutteredFunctionWords() =
        assertEquals("Que me llames.", RuleCleaner.clean("que que me llames."))

    @Test fun keepsLegitimateEmphasis() {
        assertEquals("Muy muy bueno", RuleCleaner.clean("muy muy bueno"))
        assertEquals("No no, no me gusta", RuleCleaner.clean("no no, no me gusta"))
    }

    @Test fun capitalizesSentences() =
        assertEquals("Hola. Todo bien?", RuleCleaner.clean("hola. todo bien?"))

    @Test fun newLineCommand() =
        assertEquals("Hola\nChau", RuleCleaner.clean("hola nueva línea chau"))

    @Test fun paragraphCommand() =
        assertEquals("Hola.\nChau", RuleCleaner.clean("hola, punto y aparte chau"))

    @Test fun doesNotCapitalizeInsideUrls() =
        assertEquals("Mirá www.google.com", RuleCleaner.clean("mirá www.google.com"))

    @Test fun keepsDecimals() =
        assertEquals("Son 3,5 metros.", RuleCleaner.clean("son 3,5 metros."))

    @Test fun blankStaysBlank() = assertEquals("", RuleCleaner.clean("   "))
}
