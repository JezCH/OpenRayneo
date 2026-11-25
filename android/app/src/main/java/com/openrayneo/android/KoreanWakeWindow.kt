// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** One explicit, short-lived listening window. No ambient command execution. */
internal class KoreanWakeWindow(private val started: Long) {
    private var calledAt: Long? = null
    private var consumed = false
    fun expired(now: Long) = consumed || now - started !in 0L..120_000L ||
        calledAt?.let { now - it !in 0L..12_000L } == true
    fun accept(text: String, now: Long): String? {
        if (expired(now)) return null
        val clean = KoreanLanguage.normalize(text).trim().trimEnd('.', '!', '?', '。', '！', '？')
        if (clean.isBlank()) return null
        val match = Regex("^(?:자비스|레이네오야)(?:[\\s,，:：]+(.*))?$").matchEntire(clean)
        val question = if (match != null) {
            val tail = match.groupValues[1].trim()
            if (tail.isEmpty()) { calledAt = now; return null }
            tail
        } else if (calledAt != null) clean else return null
        if (question.length !in 2..240) { consumed = true; return null }
        consumed = true
        return question
    }
}
