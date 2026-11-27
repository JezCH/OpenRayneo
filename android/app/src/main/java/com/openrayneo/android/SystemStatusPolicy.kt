// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** Pure presentation policy shared by foreground UI and persistent service notification.
 * No probing, network activity, persisted telemetry or action side effects. */
internal object SystemStatusPolicy {
    enum class Phase { OFF, READY, WAITING, ACTIVE, BUSY, BLOCKED, ERROR, UNKNOWN }
    data class Status(val phase: Phase, val label: String)
    fun link(phase: String, retry: Boolean, closing: Boolean): Status = when {
        closing || phase == "CLOSING" -> Status(Phase.BUSY, "연결 해제 중")
        phase == "FULL" -> Status(Phase.READY, "연결됨")
        phase == "BLE_ONLY" -> Status(Phase.BLOCKED, "BLE만 연결")
        phase == "SCANNING" -> Status(Phase.BUSY, "검색 중")
        phase == "CONNECTING" -> Status(Phase.BUSY, "연결 중")
        retry || phase == "RETRY_WAIT" -> Status(Phase.WAITING, "재연결 대기")
        phase == "DISCONNECTED" -> Status(Phase.OFF, "미연결")
        else -> Status(Phase.UNKNOWN, "미확인")
    }
    fun capture(running: Boolean, stopping: Boolean, replay: Boolean, transcriptOnly: Boolean,
                resume: Boolean, pcm: String, enrollment: Boolean): Status = when {
        stopping -> Status(Phase.BUSY, if (replay) "재전사 마무리 중" else "기록 마무리 중")
        running && replay -> Status(Phase.ACTIVE, "재전사 중")
        resume -> Status(Phase.WAITING, "기록 재연결 대기")
        !running -> Status(Phase.OFF, "기록 꺼짐")
        pcm == "FAILED" -> Status(Phase.ERROR, "음성 수신 오류")
        pcm == "STALLED" -> Status(Phase.BLOCKED, "음성 수신 중단")
        pcm != "RECEIVING" -> Status(Phase.WAITING, "음성 수신 대기")
        enrollment -> Status(Phase.ACTIVE, "목소리 등록 중")
        transcriptOnly -> Status(Phase.ACTIVE, "전사 기록 중")
        else -> Status(Phase.ACTIVE, "녹음 중")
    }
    fun receiver(enabled: Boolean, recent: Boolean): Status = when {
        !enabled -> Status(Phase.OFF, "꺼짐")
        recent -> Status(Phase.READY, "수신 확인")
        else -> Status(Phase.WAITING, "수신 대기")
    }
    /** Direct configuration is not evidence of a received AI response. */
    fun selectedAi(available: Boolean, legacy: Boolean, configured: Boolean, recent: Boolean): Status = when {
        !available -> Status(Phase.UNKNOWN, "미확인")
        legacy -> receiver(configured, recent)
        !configured -> Status(Phase.BLOCKED, "설정 필요")
        else -> Status(Phase.READY, "설정됨 · 응답 미확인")
    }
    fun activity(active: Boolean, allowed: Boolean = active): Status =
        if (active) Status(Phase.ACTIVE, "작동 중") else if (allowed) Status(Phase.READY, "켜짐") else Status(Phase.OFF, "꺼짐")
}
