// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** A bounded, explicitly activated ASR session. Values are captured at start. */
internal data class KoreanWakeOptions(val phrases: List<String>, val seconds: Int, val followSeconds: Int) {
    companion object {
        const val DEFAULT_PHRASES = "자비스, 레이네오야"
        val durationOptions = listOf(30, 60, 120)
        val followOptions = listOf(5, 12, 20)
        fun parsePhrases(raw: String): List<String>? {
            val parts = KoreanLanguage.normalize(raw).split(',').map { it.trim().replace(Regex("\\s+"), " ") }
            if (parts.size !in 1..3 || parts.distinct().size != parts.size) return null
            if (parts.any { !Regex("[가-힣]+(?: [가-힣]+)*").matches(it) || it.count { c -> c != ' ' } !in 2..12 }) return null
            return parts.sortedByDescending { it.length }
        }
        fun from(raw: String, duration: String, follow: String) = KoreanWakeOptions(
            parsePhrases(raw) ?: checkNotNull(parsePhrases(DEFAULT_PHRASES)),
            duration.toIntOrNull()?.takeIf { it in durationOptions } ?: 120,
            follow.toIntOrNull()?.takeIf { it in followOptions } ?: 12)
    }
}
internal class KoreanWakeWindow(private val started: Long, private val options: KoreanWakeOptions = KoreanWakeOptions.from("", "", "")) {
    private var calledAt: Long? = null
    private var consumed = false
    private val pattern = Regex("^(?:${options.phrases.joinToString("|") { Regex.escape(it) }})(?:[\\s,，:：]+(.*))?$")
    fun expired(now: Long) = consumed || now - started !in 0L..options.seconds * 1000L ||
        calledAt?.let { now - it !in 0L..options.followSeconds * 1000L } == true
    fun accept(text: String, now: Long): String? {
        if (expired(now)) return null
        val clean = KoreanLanguage.normalize(text).trim().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', '。', '！', '？')
        if (clean.isBlank()) return null
        val match = pattern.matchEntire(clean)
        val question = if (match != null) {
            val tail = match.groupValues[1].trim()
            if (tail.isEmpty()) { if (calledAt == null) calledAt = now; return null }
            tail
        } else if (calledAt != null) clean else return null
        if (question.length !in 2..240) { consumed = true; return null }
        consumed = true
        return question
    }
}
