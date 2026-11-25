// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Passive hints are perishable, read-only suggestions, not durable task requests. */
internal object ProactiveHintPolicy {
    const val MAX_AGE_MS = 60_000L
    const val DUPLICATE_MS = 30_000L

    fun remainingForSpeech(now: Long, speechAt: Long): Long =
        if (speechAt <= 0L || speechAt > now) 0L else (MAX_AGE_MS - (now - speechAt)).coerceAtLeast(0L)

    fun remaining(now: Long, deadline: Long): Long = (deadline - now).coerceAtLeast(0L)

    /** Cheap conservative pre-inference gate: exact repeats and isolated acknowledgements.
     * It does not infer meaning, importance or user intent.
     */
    class InputGate {
        private var scope = ""
        private val seen = linkedMapOf<String, Long>()
        private val fillers = setOf("네", "예", "응", "음", "어", "아", "그래", "그렇구나", "알겠어", "알겠습니다",
            "맞아", "맞아요", "고마워", "감사합니다", "okay", "ok", "yes", "yeah", "um", "uh")
        fun clear() { scope = ""; seen.clear() }
        fun accept(source: String, record: String, text: String, now: Long): Boolean {
            if (record.isBlank()) return false
            val key = "$source:$record"
            if (key != scope) { clear(); scope = key }
            val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
                .trim().lowercase(java.util.Locale.ROOT).replace('？', '?').trimEnd('.', '!', '。', '！')
                .trim().replace(Regex("\\s+"), " ")
            if (normalized.isBlank() || normalized in fillers) return false
            seen.entries.removeAll { now - it.value !in 0L until DUPLICATE_MS }
            if (normalized in seen) return false
            seen[normalized] = now
            while (seen.size > 64) seen.remove(seen.keys.first())
            return true
        }
    }
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
