package org.futo.inputmethod.latin.uix.actions.aipolish

import org.junit.Assert.assertEquals
import org.junit.Test

class AiPolishSourceTest {
    @Test
    fun selectionWins() {
        assertEquals(Source("sel", SourceKind.Selection), pickSource("sel", "whole text"))
    }

    @Test
    fun wholeFieldWhenNoSelection() {
        assertEquals(Source("whole text", SourceKind.WholeField), pickSource(null, "whole text"))
        assertEquals(Source("whole text", SourceKind.WholeField), pickSource("", "whole text"))
    }

    @Test
    fun noneWhenBothEmpty() {
        assertEquals(Source("", SourceKind.None), pickSource(null, null))
        assertEquals(Source("", SourceKind.None), pickSource("  ", ""))
    }
}
