package com.neoprars.voice.app

import com.neoprars.voice.app.ClipboardRestore.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardRestoreTest {

    @Test fun whatWasCopiedBeforeIsAlwaysRestored() {
        for (sdk in 26..36) assertEquals(Action.RESTORE_PREVIOUS, ClipboardRestore.action(hadPrevious = true, sdkInt = sdk))
    }

    @Test fun android8NeverCallsClearBecauseItDoesNotExistThere() {
        assertEquals(Action.REPLACE_WITH_EMPTY, ClipboardRestore.action(hadPrevious = false, sdkInt = 26))
        assertEquals(Action.REPLACE_WITH_EMPTY, ClipboardRestore.action(hadPrevious = false, sdkInt = 27))
    }

    @Test fun android9AndLaterClearTheClipboard() {
        for (sdk in 28..36) assertEquals(Action.CLEAR, ClipboardRestore.action(hadPrevious = false, sdkInt = sdk))
    }
}
