// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Phone-service utilities, not firmware timers. All state is monotonic and process-local. */
internal object LocalClockPolicy {
    const val MAX_TIMER_MS = 86_400_000L
    enum class Kind { TIMER_START, TIMER_READ, TIMER_CANCEL, WATCH_START, WATCH_READ, WATCH_STOP, WATCH_RESET }
    data class Command(val kind: Kind, val durationMs: Long? = null)
    private const val numeral = KoreanCommandNumbers.TOKEN
    private val duration = "(?:($numeral)시간)?(?:($numeral)분)?(?:($numeral)초)?"
    private val before = Regex("^타이머${duration}(?:로)?(?:시작해줘|설정해줘|맞춰줘)$")
    private val after = Regex("^${duration}타이머(?:를)?(?:시작해줘|설정해줘|맞춰줘)$")
    private val commands = mapOf(
        "타이머남은시간알려줘" to Kind.TIMER_READ, "타이머남은시간얼마야" to Kind.TIMER_READ,
        "타이머취소해줘" to Kind.TIMER_CANCEL, "타이머중지해줘" to Kind.TIMER_CANCEL,
        "스톱워치시작해줘" to Kind.WATCH_START, "스톱워치시간알려줘" to Kind.WATCH_READ,
        "스톱워치중지해줘" to Kind.WATCH_STOP, "스톱워치정지해줘" to Kind.WATCH_STOP,
        "스톱워치초기화해줘" to Kind.WATCH_RESET)
    fun parse(text: String, source: ConversationSource, backfill: Boolean): Command? {
        if (source != ConversationSource.NATIVE || backfill) return null
        return parseExplicit(text)
    }
    /** For separately authorized one-shot UI sessions, never ambient records. */
    fun parseExplicit(text: String): Command? {
        val clean = KoreanLanguage.normalize(text).trim().trimEnd('?', '？', '.', '!', '。', '！').filterNot(Char::isWhitespace)
        val body = when { clean.startsWith("안경") -> clean.removePrefix("안경"); clean.startsWith("레이네오") -> clean.removePrefix("레이네오"); else -> return null }
        commands[body]?.let { return Command(it) }
        val match = before.matchEntire(body) ?: after.matchEntire(body) ?: return null
        val parts = match.groupValues.drop(1).map { if (it.isEmpty()) 0L else KoreanCommandNumbers.parse(it)?.toLong() ?: return Command(Kind.TIMER_START) }
        val ms = if (parts[0] in 0..24 && parts[1] in 0..1440 && parts[2] in 0..86400)
            (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000 else 0L
        return Command(Kind.TIMER_START, ms.takeIf { it in 1000..MAX_TIMER_MS })
    }
    private fun format(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return buildString {
            if (seconds >= 3600) append("${seconds / 3600}시간 ")
            if (seconds >= 60) append("${seconds % 3600 / 60}분 ")
            append("${seconds % 60}초")
        }
    }
    class Clock {
        private var deadline: Long? = null
        private var watchStart: Long? = null
        private var watchElapsed = 0L
        val timerRunning get() = deadline != null
        val watchRunning get() = watchStart != null
        fun poll(now: Long): Boolean {
            val until = deadline ?: return false
            if (now < until) return false
            deadline = null
            return true
        }
        fun clear() { deadline = null; watchStart = null; watchElapsed = 0L }
        private fun elapsed(now: Long) = watchElapsed + (watchStart?.let { (now - it).coerceAtLeast(0) } ?: 0L)
        /** Caller drains poll() first so expiration is delivered exactly once. */
        fun execute(command: Command, now: Long): String = when (command.kind) {
            Kind.TIMER_START -> {
                val duration = command.durationMs
                when {
                    duration == null || duration !in 1000..MAX_TIMER_MS -> "타이머는 1초~24시간으로 지정해 주세요."
                    timerRunning -> "이미 타이머가 실행 중입니다. 먼저 취소해 주세요."
                    else -> { deadline = now + duration; "휴대폰 타이머 ${format(duration)} 시작." }
                }
            }
            Kind.TIMER_READ -> deadline?.let { "타이머 남은 시간 ${format((it - now).coerceAtLeast(0) + 999)}." } ?: "실행 중인 타이머가 없습니다."
            Kind.TIMER_CANCEL -> if (timerRunning) { deadline = null; "타이머를 취소했습니다." } else "실행 중인 타이머가 없습니다."
            Kind.WATCH_START -> if (watchRunning) "이미 스톱워치가 실행 중입니다." else { watchStart = now; "휴대폰 스톱워치 시작." }
            Kind.WATCH_READ -> "스톱워치 ${format(elapsed(now))} (${if (watchRunning) "실행 중" else "정지"})."
            Kind.WATCH_STOP -> { watchElapsed = elapsed(now); watchStart = null; "스톱워치 정지: ${format(watchElapsed)}." }
            Kind.WATCH_RESET -> { watchStart = null; watchElapsed = 0L; "스톱워치를 초기화했습니다." }
        }
    }
}
