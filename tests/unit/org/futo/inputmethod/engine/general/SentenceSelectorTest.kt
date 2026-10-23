package org.futo.inputmethod.engine.general

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSelectorTest {
    @Test
    fun remainingEqualsRimesFirst() {
        assertEquals(0, SentenceSelector.next(listOf("登記處進行登記", "登記處", "等級"), "登記處進行登記"))
    }

    @Test
    fun longestLeadingWord() {
        assertEquals(2, SentenceSelector.next(listOf("青島登記處", "青島", "請到", "請"), "請到登記處"))
        // Rime's first may itself be a leading part of the sentence.
        assertEquals(0, SentenceSelector.next(listOf("登記處進行", "登記處", "登記", "等級"), "登記處進行登記"))
    }

    @Test
    fun noWordLeadsThere() {
        assertEquals(-1, SentenceSelector.next(listOf("青島登記處", "青島", "清"), "請到登記處"))
        assertEquals(-1, SentenceSelector.next(emptyList(), "請到"))
        assertEquals(-1, SentenceSelector.next(listOf("請到"), ""))
    }
}
