// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Only decides whether a completed voice result owns a live display path.
 * Persistence and Agent execution remain independent of this decision.
 */
internal object VoiceOutputPolicy {
    enum class Route { NONE, NATIVE, REALTIME, NOTIFICATION }

    fun isSilent(answer: String, source: ConversationSource?): Boolean =
        answer.isBlank() || (source != ConversationSource.NATIVE && answer.trim() == "[PASS]")

    fun route(source: ConversationSource?, currentService: Boolean, currentContext: Boolean,
              backfill: Boolean, linkReady: Boolean, nativeWakeCurrent: Boolean,
              realtimeCurrent: Boolean, alwaysOnCurrent: Boolean): Route {
        if (!currentService || !currentContext || backfill || !linkReady) return Route.NONE
        return when (source) {
            ConversationSource.NATIVE -> if (nativeWakeCurrent) Route.NATIVE else Route.NONE
            ConversationSource.REALTIME -> if (realtimeCurrent) Route.REALTIME else Route.NONE
            ConversationSource.ALWAYS_ON -> if (alwaysOnCurrent) Route.NOTIFICATION else Route.NONE
            null -> Route.NONE
        }
    }
}
