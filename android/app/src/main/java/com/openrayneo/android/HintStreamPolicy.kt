// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Preview text is uncommitted and never treated as a completed answer. */
internal object HintStreamPolicy {
    const val MAX_CHARS = 240
    fun preview(text: String): String? {
        val value = text.trim()
        if (value.length !in 2..MAX_CHARS || value.startsWith("[") || value == "PASS") return null
        if (value.last().isHighSurrogate()) return null
        return value
    }
    class Gate {
        private var last = ""
        private var lastAt = Long.MIN_VALUE
        fun accept(text: String, now: Long): String? {
            val value = preview(text) ?: return null
            if (value == last || (lastAt != Long.MIN_VALUE && now - lastAt < 200L)) return null
            last = value; lastAt = now
            return value
        }
    }
}
