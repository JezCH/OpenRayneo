// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Only explicit native wake commands; a queued start needs a clean end of that same capture. */
internal object AlwaysOnVoicePolicy {
    enum class Command { START, STOP }
    private val start = setOf("종일기록시작해줘", "종일기록켜줘", "하루기록시작해줘", "일상기록시작해줘")
    private val stop = setOf("종일기록중지해줘", "종일기록꺼줘", "종일기록종료해줘", "일상기록중지해줘")
    fun parse(text: String, source: ConversationSource, backfill: Boolean): Command? {
        if (source != ConversationSource.NATIVE || backfill) return null
        val clean = text.trim().trimEnd('?', '？', '.', '!', '。', '！').filterNot(Char::isWhitespace)
        val command = clean.removePrefix("안경").let { if (clean.startsWith("안경")) it else clean.removePrefix("레이네오") }
        if (command == clean) return null
        return when (command) { in start -> Command.START; in stop -> Command.STOP; else -> null }
    }
    class PendingStart(private val windowMs: Long = 90_000L) {
        private var recordId: String? = null
        private var deadline = 0L
        private var ended = false
        val pending: Boolean get() = recordId != null
        fun arm(record: String, now: Long) {
            require(record.isNotBlank())
            recordId = record; deadline = now + windowMs; ended = false
        }
        fun cancel() { recordId = null; ended = false; deadline = 0L }
        fun expire(now: Long): Boolean {
            if (!pending || now < deadline) return false
            cancel(); return true
        }
        fun nativeEnded(record: String?) {
            if (pending && record == recordId) ended = true
            else cancel()
        }
        fun take(now: Long, idleAndAllowed: Boolean): Boolean {
            if (!pending) return false
            val ready = ended && now < deadline && idleAndAllowed
            cancel()
            return ready
        }
    }
}
