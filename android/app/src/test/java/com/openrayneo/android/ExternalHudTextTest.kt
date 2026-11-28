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
    @Test fun blankOnlyPagesAreNotPresentedAsSendableContent() {
        val pages = ExternalHudText.pages("가".repeat(100) + " ".repeat(200) + "나")
        assertEquals(listOf("가".repeat(100), "나"), pages)
        assertTrue(pages.all(ExternalHudText::validPage))
    }
    @Test fun transportSuccessDoesNotClaimDisplayConfirmation() {
        val pending = ExternalHudTransfer(2, ExternalHudTransfer.Phase.WRITING)
        assertTrue(pending.busy)
        val written = pending.complete(true)
        assertEquals(2, written.page)
        assertFalse(written.busy)
        assertEquals(ExternalHudTransfer.Phase.WRITTEN, written.phase)
        assertTrue(written.label.contains("표시 미확인"))
        assertEquals(written, written.complete(false))
        val failed = pending.complete(false)
        assertFalse(failed.busy)
        assertEquals(ExternalHudTransfer.Phase.FAILED, failed.phase)
    }
    @Test(expected = IllegalArgumentException::class)
    fun oversizedInputRejected() { ExternalHudText.pages("가".repeat(4001)) }
}
