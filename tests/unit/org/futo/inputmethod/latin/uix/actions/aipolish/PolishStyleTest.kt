package org.futo.inputmethod.latin.uix.actions.aipolish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PolishStyleTest {
    @Test
    fun sceneFromKey() {
        assertEquals(Scene.Chat, Scene.fromKey("chat"))
        assertEquals(Scene.Email, Scene.fromKey("email"))
        assertEquals(Scene.Formal, Scene.fromKey("formal"))
        assertEquals(Scene.Social, Scene.fromKey("social"))
        assertNull(Scene.fromKey("other"))
        assertNull(Scene.fromKey(null))
    }

    @Test
    fun sceneNeedsHalfConfidence() {
        assertNull(StyleChoice.from(ClefResult(Scene.Email, 0.49, 0.0)).scene)
        assertEquals(Scene.Email, StyleChoice.from(ClefResult(Scene.Email, 0.5, 0.0)).scene)
    }

    @Test
    fun softenFromToneOneAndAHalf() {
        assertEquals(false, StyleChoice.from(ClefResult(null, 0.0, 1.49)).soften)
        assertEquals(true, StyleChoice.from(ClefResult(null, 0.0, 1.5)).soften)
    }

    @Test
    fun unknownSceneStaysNull() {
        assertEquals(StyleChoice(null, false), StyleChoice.from(ClefResult(null, 0.9, 0.1)))
    }

    @Test
    fun composeWithoutStyleKeepsBase() {
        assertEquals("base", composePrompt("base", StyleChoice.None))
    }

    @Test
    fun composeWithSceneOnly() {
        assertEquals("base\n${Scene.Email.instruction}", composePrompt("base", StyleChoice(Scene.Email, false)))
    }

    @Test
    fun composeWithSoftenOnly() {
        assertEquals("base\n$SOFTEN_INSTRUCTION", composePrompt("base", StyleChoice(null, true)))
    }

    @Test
    fun composeWithBoth() {
        assertEquals(
            "base\n${Scene.Chat.instruction}\n$SOFTEN_INSTRUCTION",
            composePrompt("base", StyleChoice(Scene.Chat, true))
        )
    }
}
