// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

internal object NavigationVoicePolicy {
    enum class Decision { WAIT, FOLLOW, EXPIRED, UNAVAILABLE, AMBIGUOUS, BUSY }
    fun decide(now: Long, deadline: Long, available: Boolean, activeCount: Int,
               freshCandidateCount: Int, captureFree: Boolean): Decision = when {
        !available -> Decision.UNAVAILABLE
        activeCount > 1 || freshCandidateCount > 1 -> Decision.AMBIGUOUS
        now >= deadline -> if (activeCount == 1 && freshCandidateCount == 1 && !captureFree)
            Decision.BUSY else Decision.EXPIRED
        activeCount != 1 || freshCandidateCount != 1 || !captureFree -> Decision.WAIT
        else -> Decision.FOLLOW
    }
    // The selected route's status frame is safe even if the last direction is stale.
    // Never restore an old direction: the caller must refresh the snapshot.
    fun canRestore(linkReady: Boolean, ownDisplay: Boolean, idle: Boolean,
                   captureFree: Boolean, sameSelectedKey: Boolean, listenerConnected: Boolean,
                   activeSelected: Boolean, error: Boolean): Boolean =
        linkReady && ownDisplay && idle && captureFree && sameSelectedKey &&
            listenerConnected && activeSelected && !error
}
