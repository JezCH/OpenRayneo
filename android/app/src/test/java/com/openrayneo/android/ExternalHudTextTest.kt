// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class ExternalHudTextTest {
    @Test fun longTextKeepsEveryPage() {
        val raw = "한".repeat(251)
        val pages = ExternalHudText.pages(raw)
        assertEquals(listOf(100, 100, 51), pages.map { it.length })
        assertEquals(raw, pages.joinToString(""))
        assertTrue(pages.all(ExternalHudText::validPage))
    }
    @Test fun doesNotSplitSurrogatePair() {
        val raw = "가".repeat(99) + "\uD83D\uDE00" + "끝"
        val pages = ExternalHudText.pages(raw)
        assertEquals(99, pages.first().length)
        assertEquals(raw, pages.joinToString(""))
        assertTrue(pages.all(ExternalHudText::validPage))
    }
    @Test fun controlCharactersAndBlankInput() {
        assertEquals(listOf("가 나 다"), ExternalHudText.pages("가\n나\t다"))
        assertTrue(ExternalHudText.pages("\n\t ").isEmpty())
        assertFalse(ExternalHudText.validPage("가\n나"))
        assertFalse(ExternalHudText.validPage(" "))
        assertFalse(ExternalHudText.validPage("가".repeat(101)))
    }
    @Test(expected = IllegalArgumentException::class)
    fun oversizedInputRejected() { ExternalHudText.pages("가".repeat(4001)) }
}
