// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.assertEquals
import org.junit.Test
import com.openrayneo.android.SystemStatusPolicy.Phase

class SystemStatusPresentationTest {
    @Test fun unavailableServiceCannotClaimAiReadiness() {
        for (legacy in listOf(false, true))
            for (configured in listOf(false, true))
                for (recent in listOf(false, true))
                    assertEquals(Phase.UNKNOWN, SystemStatusPolicy.selectedAi(false, legacy, configured, recent).phase)
    }

    @Test fun directModelConfigurationIsNotResponseEvidence() {
        for (recent in listOf(false, true)) {
            assertEquals(SystemStatusPolicy.Status(Phase.READY, "설정됨 · 응답 미확인"),
                SystemStatusPolicy.selectedAi(true, false, true, recent))
            assertEquals(Phase.BLOCKED, SystemStatusPolicy.selectedAi(true, false, false, recent).phase)
        }
    }

    @Test fun legacyReceiverRetainsObservedReadiness() {
        assertEquals(Phase.OFF, SystemStatusPolicy.selectedAi(true, true, false, true).phase)
        assertEquals(Phase.WAITING, SystemStatusPolicy.selectedAi(true, true, true, false).phase)
        assertEquals(Phase.READY, SystemStatusPolicy.selectedAi(true, true, true, true).phase)
    }
}
