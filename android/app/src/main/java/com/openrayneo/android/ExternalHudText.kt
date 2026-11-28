// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** UTF-16 size matches the notification path. Never silently discard the rest. */
internal object ExternalHudText {
    const val MAX_INPUT = 4000
    const val PAGE_LENGTH = 100
    fun pages(input: String): List<String> {
        require(input.length <= MAX_INPUT)
        val clean = input.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
        if (clean.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + PAGE_LENGTH, clean.length)
            if (end < clean.length && Character.isHighSurrogate(clean[end - 1]) && Character.isLowSurrogate(clean[end])) end--
            val page = clean.substring(start, end)
            if (page.isNotBlank()) result.add(page)
            start = end
        }
        return result
    }
    fun validPage(text: String): Boolean = text.isNotBlank() && text.length <= PAGE_LENGTH && text.none { it.isISOControl() }
}

/** Transport write result is not a firmware acknowledgement or visible HUD confirmation. */
internal data class ExternalHudTransfer(
    val page: Int = -1,
    val phase: Phase = Phase.IDLE,
) {
    enum class Phase { IDLE, WRITING, WRITTEN, FAILED }
    val busy: Boolean get() = phase == Phase.WRITING
    val label: String get() = when (phase) {
        Phase.IDLE -> ""
        Phase.WRITING -> "통신 쓰기 대기 중"
        Phase.WRITTEN -> "통신 쓰기 완료 · 안경 표시 미확인"
        Phase.FAILED -> "통신 쓰기 실패 또는 연결 변경 · 자동 재전송 없음"
    }
    fun complete(success: Boolean): ExternalHudTransfer =
        if (!busy) this else copy(phase = if (success) Phase.WRITTEN else Phase.FAILED)
}
