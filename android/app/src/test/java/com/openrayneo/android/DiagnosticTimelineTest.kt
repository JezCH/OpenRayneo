// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android
import org.junit.Test
import org.junit.Assert.*

class DiagnosticTimelineTest {
    @Test fun boundedDeduplicatedStateAndOrderedEvents() {
        val t = DiagnosticTimeline(limit = 3)
        assertTrue(t.add(1, "audio", "WAITING", true))
        assertFalse(t.add(2, "audio", "WAITING", true))
        assertTrue(t.add(3, "audio", "RECEIVING", true))
        t.add(4, "navigation", "START")
        t.add(5, "navigation", "READY")
        assertEquals(listOf("3 | audio | RECEIVING", "4 | navigation | START", "5 | navigation | READY"), t.entries())
        t.clear()
        assertTrue(t.entries().isEmpty())
        assertTrue(t.add(6, "audio", "RECEIVING", true))
    }
}
