package com.neoprars.voice.app

import com.neoprars.voice.app.TextSplice.Plan
import kotlin.test.Test
import kotlin.test.assertEquals

class TextSpliceTest {

    private fun plan(text: String?, hint: String? = null, showingHint: Boolean = false, start: Int = -1, end: Int = -1) =
        TextSplice.plan(text, hint, showingHint, start, end, "hola")

    @Test fun emptyFieldGetsOnlyTheDictation() {
        assertEquals(Plan.SetText("hola", 4), plan(""))
        assertEquals(Plan.SetText("hola", 4), plan(null))
    }

    @Test fun fieldShowingHintIsTreatedAsEmpty() {
        assertEquals(Plan.SetText("hola", 4), plan("Mensaje", showingHint = true, start = 0, end = 0))
    }

    @Test fun textEqualToHintIsTreatedAsEmpty() {
        assertEquals(Plan.SetText("hola", 4), plan("Mensaje", hint = "Mensaje"))
        assertEquals(Plan.SetText("hola", 4), plan("Mensaje", hint = "Mensaje", start = 7, end = 7))
    }

    /** El caso de WhatsApp: el hint llega como texto, sin bandera, sin hint y sin selección. */
    @Test fun textWithoutSelectionIsPastedInsteadOfRewritten() {
        assertEquals(Plan.Paste, plan("Mensaje"))
        assertEquals(Plan.Paste, plan("texto real", start = 3, end = -1))
    }

    @Test fun insertsAtCursor() {
        assertEquals(Plan.SetText("abholacd", 6), plan("abcd", start = 2, end = 2))
        assertEquals(Plan.SetText("abcdhola", 8), plan("abcd", start = 4, end = 4))
    }

    @Test fun replacesSelectionInEitherDirection() {
        assertEquals(Plan.SetText("aholad", 5), plan("abcd", start = 1, end = 3))
        assertEquals(Plan.SetText("aholad", 5), plan("abcd", start = 3, end = 1))
    }

    @Test fun selectionOutOfRangeIsClamped() {
        assertEquals(Plan.SetText("abcdhola", 8), plan("abcd", start = 99, end = 99))
    }
}
