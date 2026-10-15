package com.neoprars.voice.app

import com.neoprars.voice.app.ButtonPlacement.Position
import com.neoprars.voice.app.ButtonPlacement.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ButtonPlacementTest {

    private val w = 1080
    private val h = 2340
    private val disc = 156
    private val margin = 36

    @Test fun discSticksToEachSideWithMargin() {
        assertEquals(36, ButtonPlacement.discX(Side.LEFT, w, disc, margin))
        assertEquals(1080 - 156 - 36, ButtonPlacement.discX(Side.RIGHT, w, disc, margin))
    }

    @Test fun defaultIsRightAboveKeyboard() {
        assertEquals(Position(Side.RIGHT, 1500 - 156 - 36), ButtonPlacement.defaultPosition(h, disc, margin, 1500))
    }

    @Test fun chosenPositionIsKeptWhenAboveKeyboard() {
        assertEquals(400, ButtonPlacement.clampY(400, h, disc, margin, keyboardTop = 1500))
    }

    @Test fun neverEndsUpBehindKeyboard() {
        assertEquals(1500 - 156 - 36, ButtonPlacement.clampY(2000, h, disc, margin, keyboardTop = 1500))
    }

    @Test fun staysInsideScreenWithoutKeyboard() {
        assertEquals(36, ButtonPlacement.clampY(-500, h, disc, margin, keyboardTop = null))
        assertEquals(2340 - 156 - 36, ButtonPlacement.clampY(9999, h, disc, margin, keyboardTop = null))
    }

    @Test fun veryTallKeyboardStillLeavesButtonOnScreen() {
        assertEquals(36, ButtonPlacement.clampY(800, h, disc, margin, keyboardTop = 100))
    }

    @Test fun dragStaysInsideScreenHorizontally() {
        assertEquals(0, ButtonPlacement.clampX(-40, w, disc))
        assertEquals(1080 - 156, ButtonPlacement.clampX(5000, w, disc))
        assertEquals(300, ButtonPlacement.clampX(300, w, disc))
    }

    @Test fun snapsToNearestSide() {
        assertEquals(Side.LEFT, ButtonPlacement.nearestSide(100, w, disc))
        assertEquals(Side.RIGHT, ButtonPlacement.nearestSide(700, w, disc))
        assertEquals(Side.RIGHT, ButtonPlacement.nearestSide(540 - 78, w, disc)) // centro exacto
    }

    @Test fun positionSurvivesEncoding() {
        for (p in listOf(Position(Side.LEFT, 0), Position(Side.RIGHT, 1234))) {
            assertEquals(p, ButtonPlacement.decode(ButtonPlacement.encode(p)))
        }
    }

    @Test fun badStoredValuesAreIgnored() {
        for (bad in listOf(null, "", "R", "X:10", "L:abc", "L10")) assertNull(ButtonPlacement.decode(bad))
    }
}
