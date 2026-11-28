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
    @Test fun noisyCaptionCannotEvictQuietNavigation() {
        val t = DiagnosticTimeline(limit = 8, categoryLimit = 3)
        t.add(0, "navigation", "START")
        repeat(20) { t.add((it + 1).toLong(), "caption", "frame $it") }
        assertEquals(4, t.entries().size)
        assertEquals("0 | navigation | START", t.entries().first())
    }
}
