// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** User-selectable software wake settings, independent of firmware wake words. */
internal data class KoreanWakeOptions(val phrases: List<String>, val listenSeconds: Int, val followupSeconds: Int) {
    val label get() = phrases.joinToString(" / ")
    companion object {
        const val DEFAULT_PHRASES = "자비스, 레이네오야"
        val listenChoices = listOf(30, 60, 120)
        val followupChoices = listOf(5, 8, 12)
        fun parse(raw: String = DEFAULT_PHRASES, listen: Int = 120, followup: Int = 12): KoreanWakeOptions {
            val phrases = raw.split(',').map { KoreanLanguage.normalize(it).trim().replace(Regex("\\s+"), " ") }
            require(phrases.size in 1..3 && phrases.distinct().size == phrases.size &&
                phrases.all { it.matches(Regex("[가-힣]+(?: [가-힣]+)*")) && it.count { ch -> ch != ' ' } in 2..12 }) {
                "호출어는 한글 2~12자, 쉼표로 구분하여 최대 3개를 입력하세요. 중복은 허용하지 않습니다."
            }
            require(listen in listenChoices && followup in followupChoices) { "지원하는 호출 대기 시간을 선택하세요." }
            return KoreanWakeOptions(phrases, listen, followup)
        }
    }
}

/** Explicit bounded listening, with literal user-defined phrases and one command per window. */
internal class KoreanWakeWindow(private val started: Long, private val options: KoreanWakeOptions = KoreanWakeOptions.parse()) {
    private var calledAt: Long? = null
    private var consumed = false
    private val prefix = Regex("^(?:" + options.phrases.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?:[\\s,，:：]+(.*))?$")
    fun expired(now: Long) = consumed || now - started !in 0L..(options.listenSeconds * 1000L) ||
        calledAt?.let { now - it !in 0L..(options.followupSeconds * 1000L) } == true
    fun accept(text: String, now: Long): String? {
        if (expired(now)) return null
        val clean = KoreanLanguage.normalize(text).trim().trimEnd('.', '!', '?', '。', '！', '？').replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return null
        val match = prefix.matchEntire(clean)
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
