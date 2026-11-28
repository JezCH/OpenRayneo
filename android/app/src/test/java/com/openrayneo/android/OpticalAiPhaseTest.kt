// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.assertEquals
import org.junit.Test

class OpticalAiPhaseTest {
    @Test fun unavailableSnapshotNeverClaimsReadinessOrRunning() {
        assertEquals(OpticalAiPhase.UNAVAILABLE, opticalAiPhase(false, true, true, true, true))
    }

    @Test fun approvalTakesPrecedenceOverRunningOrPriorAnswer() {
        assertEquals(OpticalAiPhase.APPROVAL, opticalAiPhase(true, true, true, false, true))
    }

    @Test fun inFlightRequestIsNotCompletedResponse() {
        assertEquals(OpticalAiPhase.REQUEST, opticalAiPhase(true, false, true, false, true))
    }

    @Test fun previousAnswerDoesNotClaimCurrentReadiness() {
        assertEquals(OpticalAiPhase.SETUP, opticalAiPhase(true, false, false, false, true))
    }

    @Test fun idleReadyAndReceivedAnswerAreDistinct() {
        assertEquals(OpticalAiPhase.READY, opticalAiPhase(true, false, false, true, false))
        assertEquals(OpticalAiPhase.RESPONSE, opticalAiPhase(true, false, false, true, true))
    }
}
