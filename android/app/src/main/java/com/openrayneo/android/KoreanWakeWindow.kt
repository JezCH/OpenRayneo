// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** User-selectable software wake settings, independent of firmware wake words. */
internal data class KoreanWakeOptions(val phrases: List<String>, val listenSeconds: Int, val followupSeconds: Int) {
    val label get() = phrases.joinToString(" / ")
    companion object {
        const val DEFAULT_PHRASES = "자비스, 레이네오야"
        val listenChoices = listOf(30, 60, 120)
        val followupChoices = listOf(5, 8, 12)
        // Empty values from pre-feature configurations mean the defaults. Invalid nonempty values fail.
        fun from(raw: String, listen: String, followup: String): KoreanWakeOptions = parse(
            raw.ifBlank { DEFAULT_PHRASES }, listen.ifBlank { "120" }.toIntOrNull() ?: 0,
            followup.ifBlank { "12" }.toIntOrNull() ?: 0)
        fun parse(raw: String = DEFAULT_PHRASES, listen: Int = 120, followup: Int = 12): KoreanWakeOptions {
            val phrases = raw.split(',').map { KoreanLanguage.normalize(it).trim().replace(Regex("\\s+"), " ") }
            require(phrases.size in 1..3 && phrases.map { it.filterNot(Char::isWhitespace) }.distinct().size == phrases.size &&
                phrases.all { it.matches(Regex("[가-힣]+(?: [가-힣]+)*")) && it.count { ch -> ch != ' ' } in 2..12 }) {
                "호출어는 한글 2~12자, 쉼표로 구분하여 최대 3개를 입력하세요. 중복은 허용하지 않습니다."
            }
            require(listen in listenChoices && followup in followupChoices) { "지원하는 호출 대기 시간을 선택하세요." }
            return KoreanWakeOptions(phrases, listen, followup)
        }
    }
}

/** Literal wake prefixes; allow ASR syllable spacing, punctuation and a joined question. */
internal class KoreanWakeMatcher(options: KoreanWakeOptions) {
    private val prefix = Regex("^(?:" + options.phrases.sortedByDescending { it.count { c -> !c.isWhitespace() } }
        .joinToString("|") { phrase -> phrase.filterNot(Char::isWhitespace).map { Regex.escape(it.toString()) }.joinToString("\\s*") } + ")(.*)$")
    fun clean(text: String): String = KoreanLanguage.normalize(text).trim()
        .trimEnd('.', '!', '?', '。', '！', '？').replace(Regex("\\s+"), " ")
    /** null = not a wake, empty = wake only, otherwise the question. */
    fun match(text: String): String? {
        val rest = prefix.matchEntire(clean(text))?.groupValues?.get(1) ?: return null
        val tail = rest.trimStart(' ', ',', '，', ':', '：', '.', '。', '!', '！', '?', '？', '-', '–', '—').trim()
        if (tail.isEmpty()) return ""
        // A joined Korean question is a common ASR output; do not turn a bare name + noun/particle into a request.
        if (rest.firstOrNull()?.isLetterOrDigit() == true && !joinedQuestionStarts.any { tail.startsWith(it) }) return null
        if (Regex("^(가|는|은|를|을|이|의|에|와|과|라는|이라고|라고)(\\s|$)").containsMatchIn(tail) ||
            listOf("라는", "이라고", "라고").any { tail.startsWith(it) }) return null
        return tail
    }
    private val joinedQuestionStarts = listOf("지금", "오늘", "내일", "어제", "현재", "안경", "몇", "뭐", "무엇", "무슨",
        "왜", "어떻게", "얼마", "누구", "어디", "언제", "시간", "날짜", "타이머", "스톱워치", "알려", "보여", "설명", "정리", "번역", "계산")

}

/** Explicit bounded listening; one command per window. */
internal class KoreanWakeWindow(private val started: Long, private val options: KoreanWakeOptions = KoreanWakeOptions.parse()) {
    private var calledAt: Long? = null
    private var consumed = false
    private val matcher = KoreanWakeMatcher(options)
    fun expired(now: Long) = consumed || now - started !in 0L..(options.listenSeconds * 1000L) ||
        calledAt?.let { now - it !in 0L..(options.followupSeconds * 1000L) } == true
    fun accept(text: String, now: Long): String? {
        if (expired(now)) return null
        val clean = matcher.clean(text)
        if (clean.isBlank()) return null
        val match = matcher.match(clean)
        val question = if (match != null) {
            if (match.isEmpty()) { if (calledAt == null) calledAt = now; return null }
            match
        } else if (calledAt != null) clean else return null
        if (question.length !in 2..240) return null
        consumed = true
        return question
    }
}

/** Continuous wake over fresh committed live speech, never over stored/backfill audio. */
internal class AmbientKoreanWake {
    data class Match(val question: String? = null)
    private var record = ""
    private var speaker = ""
    private var armedAt: Long? = null
    private var lastSpeech = 0L
    val awaitingQuestion: Boolean get() = armedAt != null
    fun expire(now: Long, followupSeconds: Int): Boolean {
        val armed = armedAt ?: return false
        if (now - armed <= ProactiveHintPolicy.MAX_AGE_MS + followupSeconds * 1000L) return false
        armedAt = null; return true
    }
    fun reset() { record = ""; speaker = ""; armedAt = null; lastSpeech = 0L }
    fun accept(recordId: String, text: String, speakerId: String, speechAt: Long, now: Long,
               mixed: Boolean, options: KoreanWakeOptions): Match? {
        if (recordId.isBlank() || speechAt <= 0 || now - speechAt !in 0L..ProactiveHintPolicy.MAX_AGE_MS) return null
        if (record != recordId) { reset(); record = recordId }
        if (speechAt < lastSpeech) return null
        lastSpeech = speechAt
        if (mixed) { armedAt = null; return null }
        val matcher = KoreanWakeMatcher(options)
        val called = matcher.match(text)
        val clean = matcher.clean(text)
        if (called != null) {
            armedAt = null
            if (called.isBlank()) { speaker = speakerId; armedAt = speechAt; return Match() }
            return if (called.length in 2..240) Match(called) else null
        }
        val armed = armedAt ?: return null
        if (speechAt - armed !in 0L..(options.followupSeconds * 1000L) ||
            (speaker.isNotBlank() && speakerId.isNotBlank() && speaker != speakerId)) {
            armedAt = null; return null
        }
        if (clean.length !in 2..240) return null
        armedAt = null
        return Match(clean)
    }
}

// Optional short-lived history of explicitly invoked questions. Never learns from
// passive audio, crosses a record/speaker boundary, or persists to the Agent outbox.
internal class AmbientWakeDialogue {
    data class Turn(val question: String, val answer: String)
    private var scope: String? = null
    private var lastAt = 0L
    private val turns = ArrayDeque<Turn>()
    fun clear() { scope = null; lastAt = 0L; turns.clear() }
    fun history(key: String?, now: Long): List<Turn> {
        if (key == null || scope != key || now - lastAt !in 0L..120_000L) {
            clear()
            scope = key
        }
        return turns.toList()
    }
    fun remember(key: String?, question: String, answer: String, now: Long) {
        if (key == null || answer.isBlank() || answer == "[PASS]") return
        history(key, now)
        turns.addLast(Turn(question.take(240), answer.take(600)))
        while (turns.size > 3) turns.removeFirst()
        lastAt = now
    }
}
