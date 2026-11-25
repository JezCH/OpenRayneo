// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Passive hints are perishable, read-only suggestions, not durable task requests. */
internal object ProactiveHintPolicy {
    const val MAX_AGE_MS = 60_000L
    const val DUPLICATE_MS = 30_000L

    fun remainingForSpeech(now: Long, speechAt: Long): Long =
        if (speechAt <= 0L || speechAt > now) 0L else (MAX_AGE_MS - (now - speechAt)).coerceAtLeast(0L)

    fun remaining(now: Long, deadline: Long): Long = (deadline - now).coerceAtLeast(0L)

    class DisplayGate {
        private var record = ""
        private var sequence = 0L
        private var lastText = ""
        private var lastAt = 0L
        fun accept(recordId: String, id: Long, answer: String, now: Long, deadline: Long): Boolean {
            if (recordId.isBlank() || id <= 0 || remaining(now, deadline) == 0L) return false
            if (record != recordId) {
                record = recordId; sequence = 0; lastText = ""; lastAt = 0
            }
            if (id <= sequence) return false
            sequence = id
            val normalized = answer.trim().replace(Regex("\\s+"), " ")
            if (normalized.isBlank() || normalized == "[PASS]") return false
            if (normalized == lastText && now - lastAt < DUPLICATE_MS) return false
            lastText = normalized; lastAt = now
            return true
        }
    }
}
