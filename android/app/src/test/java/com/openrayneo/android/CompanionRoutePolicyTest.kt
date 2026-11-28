// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.*
import org.junit.Test

class CompanionRoutePolicyTest {
    @Test fun containsAllExistingDetails() {
        assertEquals(setOf("settings", "feature-controls", "system-status", "app-update",
            "local-clock", "about-you", "model", "computer", "diagnostics", "voice-settings",
            "voice-profiles", "device-settings", "content", "teleprompter", "agent-api", "navigation",
            "display-controls", "translation", "captions", "media-captions", "korean-wake",
            "korean-glyphs", "phone-notifications", "record-migration"), CompanionRoutePolicy.titles.keys)
        assertTrue(CompanionRoutePolicy.titles.values.all { it.isNotBlank() })
    }

    @Test fun returnsToExistingRouteAndDiscardsOnlyPoppedState() {
        val stack = listOf("settings", "voice-settings", "system-status", "device-settings")
        val next = CompanionRoutePolicy.open(stack, "voice-settings")
        assertEquals(listOf("settings", "voice-settings"), next)
        assertEquals(listOf("system-status", "device-settings"), CompanionRoutePolicy.removed(stack, next))
    }

    @Test fun backAndExitRetainOnlyActiveAncestors() {
        val stack = listOf("settings", "model")
        assertEquals(listOf("settings"), CompanionRoutePolicy.back(stack))
        assertEquals(listOf("model"), CompanionRoutePolicy.removed(stack, CompanionRoutePolicy.back(stack)))
        assertEquals(stack, CompanionRoutePolicy.removed(stack, emptyList()))
        assertEquals(emptyList<String>(), CompanionRoutePolicy.back(emptyList()))
    }

    @Test fun externalTextIsAValidationOnlyAdditionalRoute() {
        val stack = listOf("settings")
        assertNull(CompanionRoutePolicy.title("external-hud"))
        assertEquals(stack, CompanionRoutePolicy.open(stack, "external-hud"))
        assertNotNull(CompanionRoutePolicy.title("external-hud", validation = true))
        assertEquals(stack + "external-hud", CompanionRoutePolicy.open(stack, "external-hud", validation = true))
        assertEquals(24, CompanionRoutePolicy.titles.size)
    }

    @Test fun invalidDestinationDoesNotAlterStackAndNewRouteAppends() {
        val stack = listOf("settings")
        assertEquals(stack, CompanionRoutePolicy.open(stack, "unknown"))
        assertEquals(listOf("settings", "system-status"), CompanionRoutePolicy.open(stack, "system-status"))
        assertEquals(stack, CompanionRoutePolicy.open(stack, "settings"))
    }
}
